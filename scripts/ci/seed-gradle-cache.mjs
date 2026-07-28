import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { cp, mkdir, open, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { REPOSITORY_ROOT, canonicalJson, sha256 } from '../verification/check-result.mjs';

const MAX_OUTPUT = 128 * 1024;
const CACHE_ROOT = path.join(REPOSITORY_ROOT, 'build', 'ci', 'verified-gradle-cache');
const SCRUB_NAMES = /^(?:daemon|workers|notifications|buildOutputCleanup|fileChanges|fileContent|fileHashes|executionHistory|build-cache-|journal-|vcs-|[.]tmp|gradle[.]properties|credentials?)$/iu;
const SECRET_TEXT = /(?:password|token|secret|private[_-]?key|access[_-]?key)/iu;

class SeedBlockedError extends Error {
  constructor(reasonCode, detailCode) {
    super(detailCode);
    this.reasonCode = reasonCode;
    this.detailCode = detailCode;
  }
}

function parseCli(argv) {
  const result = { outputRoot: CACHE_ROOT };
  const allowed = new Set(['--output-root', '--plugin-mirror', '--dependency-mirror']);
  const seen = new Set();
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!allowed.has(option) || !value || value.startsWith('--') || seen.has(option)) throw new TypeError(`Invalid cache-seed option: ${option}`);
    seen.add(option);
    result[option.slice(2).replace(/-([a-z])/gu, (_match, letter) => letter.toUpperCase())] = value;
  }
  result.pluginMirror ??= process.env.ACCORD_GRADLE_PLUGIN_MIRROR_URL;
  result.dependencyMirror ??= process.env.ACCORD_MAVEN_MIRROR_URL;
  if (!result.pluginMirror || !result.dependencyMirror) throw new SeedBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'APPROVED_GRADLE_MIRRORS_ABSENT');
  const approvedHosts = new Set((process.env.ACCORD_APPROVED_MIRROR_HOSTS ?? '')
    .split(',').map((item) => item.trim().toLowerCase()).filter(Boolean));
  for (const [label, value] of [['plugin', result.pluginMirror], ['dependency', result.dependencyMirror]]) {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) throw new TypeError(`${label} mirror is not a credential-free HTTPS URL`);
    if (approvedHosts.size > 0 && !approvedHosts.has(url.hostname.toLowerCase())) throw new TypeError(`${label} mirror host is not approved`);
  }
  return result;
}

function runNative(executable, argv, env) {
  return new Promise((resolve, reject) => {
    let output = '';
    let bytes = 0;
    const child = spawn(executable, argv, { cwd: REPOSITORY_ROOT, env, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const append = (chunk) => {
      if (bytes >= MAX_OUTPUT) return;
      const slice = chunk.subarray(0, MAX_OUTPUT - bytes);
      bytes += slice.length;
      output += slice.toString('utf8');
    };
    child.stdout.on('data', append);
    child.stderr.on('data', append);
    child.once('error', () => reject(new SeedBlockedError('BLOCKED_TOOLCHAIN', 'GRADLE_UNAVAILABLE')));
    child.once('close', (code) => resolve({ exitCode: code ?? -1, output }));
  });
}

async function walk(directory, prefix = '') {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    const absolute = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new TypeError(`Verified cache contains a symbolic link: ${relative}`);
    if (entry.isDirectory()) files.push(...await walk(absolute, relative));
    else if (entry.isFile()) files.push({ relative, absolute });
    else throw new TypeError(`Verified cache contains a non-regular entry: ${relative}`);
  }
  return files;
}

async function scrub(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name);
    if (SCRUB_NAMES.test(entry.name) || SECRET_TEXT.test(entry.name)) {
      await rm(target, { recursive: true, force: true });
    } else if (entry.isDirectory()) {
      await scrub(target);
    }
  }
}

async function collectBindingInputs(javaVersion) {
  const required = [
    '.tool-versions', 'settings.gradle', 'build.gradle', 'gradle/libs.versions.toml',
    'gradle/verification-metadata.xml', 'gradle/wrapper/gradle-wrapper.jar',
    'gradle/wrapper/gradle-wrapper.properties',
  ];
  const inventory = await runNative('git', ['ls-files', '--', '*gradle.lockfile'], process.env);
  if (inventory.exitCode !== 0) throw new TypeError('Dependency-lock inventory failed');
  const locks = inventory.output.split(/\r?\n/u).map((item) => item.trim()).filter(Boolean);
  const inputs = [];
  for (const relative of [...required, ...locks].sort()) {
    const bytes = await readFile(path.join(REPOSITORY_ROOT, relative));
    inputs.push({ path: relative, digest: sha256(bytes) });
  }
  inputs.push({ path: 'toolchain/java-version', digest: sha256(Buffer.from(javaVersion.trim(), 'utf8')) });
  return inputs.sort((left, right) => left.path.localeCompare(right.path));
}

async function writeManifest(stage, inputs) {
  const files = (await walk(stage)).sort((left, right) => left.relative.localeCompare(right.relative));
  const lines = ['# accord-verified-gradle-cache-v1'];
  for (const input of inputs) lines.push(`# input ${input.path} ${input.digest}`);
  for (const file of files) {
    const digest = createHash('sha256').update(await readFile(file.absolute)).digest('hex');
    lines.push(`${digest}  ${file.relative}`);
  }
  const bytes = `${lines.join('\n')}\n`;
  await writeFile(path.join(stage, 'manifest.sha256'), bytes, { encoding: 'utf8', flag: 'wx', mode: 0o600 });
  return sha256(Buffer.from(bytes, 'utf8'));
}

function parseGradleHome(output) {
  const match = /^ACCORD_GRADLE_HOME=(.+)$/mu.exec(output);
  if (!match) throw new TypeError('Gradle did not disclose its fixed installation home');
  return path.resolve(match[1].trim());
}

async function main(argv) {
  const options = parseCli(argv);
  const outputRoot = path.resolve(options.outputRoot);
  const relativeOutput = path.relative(REPOSITORY_ROOT, outputRoot);
  if (relativeOutput.startsWith('..') || path.isAbsolute(relativeOutput)) throw new TypeError('Verified cache output must remain inside the repository');
  const taskRoot = path.join(REPOSITORY_ROOT, 'build', 'ci', 'cache-seed', randomUUID());
  const gradleUserHome = path.join(taskRoot, 'gradle-user-home');
  const initScript = path.join(taskRoot, 'init.gradle');
  const stage = path.join(taskRoot, 'artifact');
  await mkdir(gradleUserHome, { recursive: true });
  await mkdir(stage, { recursive: true });
  await writeFile(initScript, "gradle.settingsEvaluated { println('ACCORD_GRADLE_HOME=' + gradle.gradleHomeDir.absolutePath) }\n", { encoding: 'utf8', flag: 'wx', mode: 0o600 });
  const env = {
    ...process.env,
    GRADLE_USER_HOME: gradleUserHome,
    ACCORD_GRADLE_PLUGIN_MIRROR_URL: options.pluginMirror,
    ACCORD_MAVEN_MIRROR_URL: options.dependencyMirror,
  };
  try {
    const java = await runNative('java', ['--version'], env).catch((error) => { throw error; });
    if (java.exitCode !== 0) throw new SeedBlockedError('BLOCKED_TOOLCHAIN', 'JAVA_UNAVAILABLE');
    const execution = await runNative('gradle', [
      '--no-daemon', '--dependency-verification=strict', '--init-script', initScript, 'resolveAndLockAll',
    ], env);
    if (execution.exitCode !== 0) throw new Error('Locked dependency resolution failed');
    const gradleHome = parseGradleHome(execution.output);
    if (!(await stat(gradleHome)).isDirectory()) throw new TypeError('Gradle installation home is not a directory');

    await scrub(gradleUserHome);
    await cp(gradleUserHome, path.join(stage, 'cache'), { recursive: true, force: false, dereference: true, errorOnExist: true });
    await cp(gradleHome, path.join(stage, 'gradle-home'), { recursive: true, force: false, dereference: true, errorOnExist: true });
    await scrub(stage);
    const bindingInputs = await collectBindingInputs(java.output);
    const manifestDigest = await writeManifest(stage, bindingInputs);
    const digestName = manifestDigest.slice('sha256:'.length);
    await mkdir(outputRoot, { recursive: true });
    const target = path.join(outputRoot, digestName);
    try {
      await rename(stage, target);
    } catch (error) {
      if (error?.code !== 'EEXIST') throw error;
      const existing = await readFile(path.join(target, 'manifest.sha256'));
      if (sha256(existing) !== manifestDigest) throw new TypeError('Existing content-addressed Gradle cache is inconsistent');
    }
    process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'PASS', reason_code: 'PASS', cache_sha256: manifestDigest, binding_inputs: bindingInputs })}\n`);
    return 0;
  } finally {
    await rm(taskRoot, { recursive: true, force: true });
  }
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    if (error instanceof SeedBlockedError) {
      process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'BLOCKED', reason_code: error.reasonCode, detail_code: error.detailCode })}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`seed-gradle-cache: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
