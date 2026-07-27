import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { lstat, mkdir, open, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { REPOSITORY_ROOT, canonicalJson, sha256 } from '../verification/check-result.mjs';
import { validateContext } from './create-ci-context.mjs';

const DIGEST = /^sha256:[0-9a-f]{64}$/u;
const PLATFORM = `${process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'darwin' : process.platform}/${process.arch === 'x64' ? 'amd64' : process.arch}`;

class PackageBlockedError extends Error {
  constructor(reasonCode, detailCode) {
    super(detailCode);
    this.reasonCode = reasonCode;
    this.detailCode = detailCode;
  }
}

function parseCli(argv) {
  const result = { requireGaEvidence: false };
  const single = new Set();
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    if (option === '--require-ga-evidence') {
      if (result.requireGaEvidence) throw new TypeError('Repeated --require-ga-evidence');
      result.requireGaEvidence = true;
      continue;
    }
    if (!['--context', '--cache-sha256', '--platform', '--output', '--platform-evidence', '--platform-evidence-bundle', '--platform-evidence-receipt'].includes(option)) throw new TypeError(`Unknown package option: ${option}`);
    const value = argv[++index];
    if (!value || value.startsWith('--') || single.has(option)) throw new TypeError(`Invalid package option: ${option}`);
    single.add(option);
    result[option.slice(2).replace(/-([a-z])/gu, (_match, letter) => letter.toUpperCase())] = value;
  }
  if (!result.context || !result.cacheSha256 || !result.platform || !result.output || !DIGEST.test(result.cacheSha256)) throw new TypeError('Context, cache digest, platform, and output are required');
  const evidenceParts = [result.platformEvidence, result.platformEvidenceBundle, result.platformEvidenceReceipt].filter(Boolean).length;
  if (![0, 3].includes(evidenceParts)) throw new TypeError('Platform evidence document, signature bundle, and native receipt must be provided together');
  return result;
}

function runNative(executable, argv, options = {}) {
  return new Promise((resolve, reject) => {
    let output = '';
    const child = spawn(executable, argv, {
      cwd: options.cwd ?? REPOSITORY_ROOT,
      env: options.env ?? process.env,
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.on('data', (chunk) => { if (output.length < 64 * 1024) output += chunk.toString('utf8'); });
    child.stderr.on('data', (chunk) => { if (output.length < 64 * 1024) output += chunk.toString('utf8'); });
    child.once('error', () => reject(new PackageBlockedError('BLOCKED_TOOLCHAIN', 'PACKAGING_TOOL_UNAVAILABLE')));
    child.once('close', (code) => resolve({ exitCode: code ?? -1, output }));
  });
}

async function atomicWrite(output, document) {
  const target = path.resolve(REPOSITORY_ROOT, output);
  const relative = path.relative(REPOSITORY_ROOT, target);
  if (relative.startsWith('..') || path.isAbsolute(relative) || path.extname(target) !== '.json') throw new TypeError('Package facts output must be repository-local JSON');
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = path.join(path.dirname(target), `.${path.basename(target)}.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(`${canonicalJson(document)}\n`, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, target);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
}

function hostArchiveName() {
  if (PLATFORM.startsWith('windows/')) return 'accordctl-windows.zip';
  if (PLATFORM.startsWith('darwin/')) return 'accordctl-macos.zip';
  return 'accordctl-linux.zip';
}

export function releaseArchiveName(platform) {
  if (!['windows/amd64', 'linux/amd64', 'linux/arm64', 'darwin/amd64', 'darwin/arm64'].includes(platform)) throw new TypeError('Unsupported accordctl archive platform');
  return `accordctl-${platform.replace('/', '-')}.zip`;
}

async function walkCache(directory, prefix = '') {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    const absolute = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new TypeError(`Verified Gradle cache contains a symbolic link: ${relative}`);
    if (entry.isDirectory()) files.push(...await walkCache(absolute, relative));
    else if (entry.isFile()) files.push({ relative, absolute });
    else throw new TypeError(`Verified Gradle cache contains a non-regular entry: ${relative}`);
  }
  return files;
}

export async function verifyCacheArtifact(cacheRoot, cacheDigest) {
  const rootStat = await lstat(cacheRoot);
  if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) throw new TypeError('Verified Gradle cache root is not a real directory');
  const manifestBytes = await readFile(path.join(cacheRoot, 'manifest.sha256'));
  if (sha256(manifestBytes) !== cacheDigest) throw new TypeError('Packaging cache digest mismatch');
  const lines = manifestBytes.toString('utf8').split(/\r?\n/u);
  if (lines.shift() !== '# accord-verified-gradle-cache-v1') throw new TypeError('Verified Gradle cache manifest header is invalid');
  const entries = new Map();
  for (const line of lines) {
    if (!line) continue;
    if (/^# input (?![/\\])(?!.*(?:^|[/\\])[.][.](?:[/\\]|$)).+ sha256:[0-9a-f]{64}$/u.test(line)) continue;
    const match = /^([0-9a-f]{64})  ((?:cache|gradle-home)\/(?!.*(?:^|\/)[.][.](?:\/|$)).+)$/u.exec(line);
    if (!match || entries.has(match[2])) throw new TypeError('Verified Gradle cache manifest entry is invalid or duplicated');
    entries.set(match[2], match[1]);
  }
  if (![...entries].some(([item]) => item.startsWith('cache/')) || ![...entries].some(([item]) => item.startsWith('gradle-home/'))) {
    throw new TypeError('Verified Gradle cache manifest is incomplete');
  }
  const files = (await walkCache(cacheRoot)).filter((item) => item.relative !== 'manifest.sha256');
  const actual = files.map((item) => item.relative).sort();
  const expected = [...entries.keys()].sort();
  if (canonicalJson(actual) !== canonicalJson(expected)) throw new TypeError('Verified Gradle cache contains an unlisted or missing file');
  for (const file of files) {
    const observed = createHash('sha256').update(await readFile(file.absolute)).digest('hex');
    if (observed !== entries.get(file.relative)) throw new TypeError(`Verified Gradle cache file digest mismatch: ${file.relative}`);
  }
  return cacheRoot;
}

export function packagedSmokeCommand(hostPlatform, imageRoot) {
  if (hostPlatform === 'win32') {
    return {
      executable: path.join(imageRoot, 'bin', 'java.exe'),
      argv: ['-m', 'com.inforvans.accord.cli/com.inforvans.accord.cli.AccordCtl', '--help'],
    };
  }
  return { executable: path.join(imageRoot, 'bin', 'accordctl'), argv: ['--help'] };
}

export function validatePlatformEvidence(document, artifactDigest, platform, receiptBytes, now = Date.now()) {
  if (canonicalJson(Object.keys(document ?? {}).sort()) !== canonicalJson(['artifact_digest', 'expires_at', 'kind', 'signature_digest'])) throw new TypeError('Platform evidence is not closed');
  const expectedKind = platform.startsWith('windows/') ? 'authenticode' : platform.startsWith('darwin/') ? 'notarization' : 'none';
  const receiptDigest = sha256(receiptBytes);
  if (receiptBytes.length === 0 || document.artifact_digest !== artifactDigest || document.kind !== expectedKind || document.signature_digest !== receiptDigest
      || !DIGEST.test(document.signature_digest)) throw new TypeError('Platform evidence is not bound to the exact archive and native verification receipt');
  const expiry = Date.parse(document.expires_at);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:[.]\d{3})?Z$/u.test(document.expires_at) || !Number.isFinite(expiry) || expiry <= now) {
    throw new TypeError('Platform evidence is stale or has an invalid expiry');
  }
  return document;
}

async function main(argv) {
  const options = parseCli(argv);
  if (options.platform !== PLATFORM || !['windows/amd64', 'linux/amd64', 'linux/arm64', 'darwin/amd64', 'darwin/arm64'].includes(options.platform)) {
    throw new PackageBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'HOST_PLATFORM_MISMATCH');
  }
  const contextBytes = await readFile(path.resolve(REPOSITORY_ROOT, options.context));
  const context = await validateContext(JSON.parse(contextBytes.toString('utf8')));
  const cacheRoot = path.join(REPOSITORY_ROOT, 'build', 'ci', 'verified-gradle-cache', options.cacheSha256.slice(7));
  await readFile(path.join(cacheRoot, 'manifest.sha256')).catch((error) => {
    if (error?.code === 'ENOENT') throw new PackageBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'VERIFIED_GRADLE_CACHE_ABSENT');
    throw error;
  });
  await verifyCacheArtifact(cacheRoot, options.cacheSha256);
  const gradleHome = path.join(cacheRoot, 'gradle-home');
  const gradleExecutable = process.platform === 'win32' ? 'java' : path.join(gradleHome, 'bin', 'gradle');
  const gradlePrefix = process.platform === 'win32'
    ? ['-classpath', path.join(gradleHome, 'lib', '*'), 'org.gradle.launcher.GradleMain']
    : [];
  const execution = await runNative(gradleExecutable, [...gradlePrefix, '--offline', '--no-daemon', '--dependency-verification=strict', ':cmd:accordctl:test', ':cmd:accordctl:jlinkZip'], {
    env: { ...process.env, GRADLE_USER_HOME: path.join(cacheRoot, 'cache') },
  });
  if (execution.exitCode !== 0) throw new Error('accordctl locked offline build failed');
  const distributionDirectory = path.join(REPOSITORY_ROOT, 'cmd', 'accordctl', 'build', 'distributions');
  const hostArchive = path.join(distributionDirectory, hostArchiveName());
  const archive = path.join(distributionDirectory, releaseArchiveName(options.platform));
  if (hostArchive !== archive) {
    await rm(archive, { force: true });
    await rename(hostArchive, archive);
  }
  const archiveBytes = await readFile(archive);
  const digest = `sha256:${createHash('sha256').update(archiveBytes).digest('hex')}`;

  const imageDirectory = path.join(REPOSITORY_ROOT, 'cmd', 'accordctl', 'build', 'image');
  const isolatedPath = process.platform === 'win32' ? 'C:\\Windows\\System32' : '/usr/bin:/bin';
  const smokeCommand = packagedSmokeCommand(process.platform, imageDirectory);
  const smoke = await runNative(smokeCommand.executable, smokeCommand.argv, { env: { PATH: isolatedPath } });
  if (smoke.exitCode !== 0 || !smoke.output.includes('accordctl')) throw new Error('Packaged accordctl launcher smoke test failed without host Java');

  let platformEvidence = null;
  if (options.platformEvidence) {
    const evidencePath = path.resolve(REPOSITORY_ROOT, options.platformEvidence);
    const evidenceBundlePath = path.resolve(REPOSITORY_ROOT, options.platformEvidenceBundle);
    const evidenceReceiptPath = path.resolve(REPOSITORY_ROOT, options.platformEvidenceReceipt);
    for (const target of [evidencePath, evidenceBundlePath, evidenceReceiptPath]) {
      const relative = path.relative(REPOSITORY_ROOT, target);
      if (relative.startsWith('..') || path.isAbsolute(relative)) throw new TypeError('Platform evidence must be repository-local');
    }
    const evidenceBytes = await readFile(evidencePath);
    const evidenceBundleBytes = await readFile(evidenceBundlePath);
    const evidenceReceiptBytes = await readFile(evidenceReceiptPath);
    const evidenceDocument = validatePlatformEvidence(JSON.parse(evidenceBytes.toString('utf8')), digest, options.platform, evidenceReceiptBytes);
    const verification = await runNative('cosign', [
      'verify-blob', '--bundle', evidenceBundlePath, '--certificate-identity', context.builder_subject,
      '--certificate-oidc-issuer', context.builder_issuer, evidencePath,
    ]);
    if (verification.exitCode !== 0) throw new TypeError('Platform signing/notarization receipt signature is invalid');
    const evidenceDirectory = path.join(path.dirname(path.resolve(REPOSITORY_ROOT, options.output)), 'platform-evidence');
    const evidenceBase = options.platform.replace('/', '-');
    await mkdir(evidenceDirectory, { recursive: true });
    const retainedDocument = path.join(evidenceDirectory, `${evidenceBase}.json`);
    const retainedBundle = path.join(evidenceDirectory, `${evidenceBase}.bundle.json`);
    const retainedReceipt = path.join(evidenceDirectory, `${evidenceBase}.receipt`);
    await writeFile(retainedDocument, evidenceBytes, { mode: 0o600 });
    await writeFile(retainedBundle, evidenceBundleBytes, { mode: 0o600 });
    await writeFile(retainedReceipt, evidenceReceiptBytes, { mode: 0o600 });
    platformEvidence = {
      kind: evidenceDocument.kind, expires_at: evidenceDocument.expires_at,
      document_sha256: sha256(evidenceBytes), bundle_sha256: sha256(evidenceBundleBytes), receipt_sha256: sha256(evidenceReceiptBytes),
      issuer: context.builder_issuer, identity: context.builder_subject,
      document_path: path.relative(REPOSITORY_ROOT, retainedDocument).replaceAll('\\', '/'),
      bundle_path: path.relative(REPOSITORY_ROOT, retainedBundle).replaceAll('\\', '/'),
      receipt_path: path.relative(REPOSITORY_ROOT, retainedReceipt).replaceAll('\\', '/'),
    };
  }
  const needsNativeEvidence = options.platform.startsWith('windows/') || options.platform.startsWith('darwin/');
  if (options.requireGaEvidence && needsNativeEvidence && !platformEvidence) throw new PackageBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'PLATFORM_SIGNING_EVIDENCE_ABSENT');
  const document = {
    schema_version: '1.0.0', context_sha256: sha256(contextBytes), remote_sha: context.remote_sha,
    artifacts: [{
      name: `accordctl-${options.platform.replace('/', '-')}`, kind: 'archive', platform: options.platform,
      digest, immutable_locator: `file+sha256:${digest.slice(7)}`, path: path.relative(REPOSITORY_ROOT, archive).replaceAll('\\', '/'),
      ga_platform_evidence: platformEvidence,
    }],
  };
  await atomicWrite(options.output, document);
  process.stdout.write(`${canonicalJson(document)}\n`);
  return 0;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    if (error instanceof PackageBlockedError) {
      process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'BLOCKED', reason_code: error.reasonCode, detail_code: error.detailCode })}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`package-accordctl: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
