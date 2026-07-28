import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { lstat, mkdir, open, readFile, readdir, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { REPOSITORY_ROOT, canonicalJson, sha256 } from '../verification/check-result.mjs';
import { validateContext } from './create-ci-context.mjs';
import {
  assertProductionImageLock,
  loadImageLock,
  lockedReference,
  renderAll,
} from './render-runtime-dockerfiles.mjs';

const DIGEST = /^sha256:[0-9a-f]{64}$/u;
const PLATFORMS = new Set(['linux/amd64', 'linux/arm64']);
const MAX_OUTPUT = 128 * 1024;

class BuildBlockedError extends Error {
  constructor(reasonCode, detailCode) {
    super(detailCode);
    this.reasonCode = reasonCode;
    this.detailCode = detailCode;
  }
}

function parseCli(argv) {
  const result = { platforms: [] };
  const singles = new Set();
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) throw new TypeError(`Missing value for ${option}`);
    if (option === '--platform') result.platforms.push(value);
    else if (['--context', '--cache-sha256', '--output'].includes(option)) {
      if (singles.has(option)) throw new TypeError(`Repeated option: ${option}`);
      singles.add(option);
      result[option.slice(2).replace(/-([a-z])/gu, (_match, letter) => letter.toUpperCase())] = value;
    } else throw new TypeError(`Unknown build option: ${option}`);
  }
  if (!result.context || !result.cacheSha256 || !result.output || result.platforms.length === 0) throw new TypeError('Context, cache digest, output, and at least one platform are required');
  if (!DIGEST.test(result.cacheSha256) || result.platforms.some((item) => !PLATFORMS.has(item)) || new Set(result.platforms).size !== result.platforms.length) {
    throw new TypeError('Build cache digest or platform set is invalid');
  }
  return result;
}

function runNative(executable, argv, options = {}) {
  if (argv.some((argument) => /(?:password|token|secret|Bearer\s+)/iu.test(argument))) throw new TypeError('Native argv contains secret material');
  return new Promise((resolve, reject) => {
    let output = '';
    let bytes = 0;
    const child = spawn(executable, argv, { cwd: REPOSITORY_ROOT, env: options.env ?? process.env, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const append = (chunk) => {
      if (bytes >= MAX_OUTPUT) return;
      const slice = chunk.subarray(0, MAX_OUTPUT - bytes);
      bytes += slice.length;
      output += slice.toString('utf8');
    };
    child.stdout.on('data', append);
    child.stderr.on('data', append);
    child.once('error', () => reject(new BuildBlockedError('BLOCKED_TOOLCHAIN', 'NATIVE_TOOL_UNAVAILABLE')));
    child.once('close', (code) => resolve({ exitCode: code ?? -1, output }));
  });
}

function isCanonicalRelativePath(value) {
  if (!value || value.includes('\\') || /[\u0000-\u001f\u007f]/u.test(value) || value.normalize('NFC') !== value) return false;
  const segments = value.split('/');
  return segments.every((segment) => segment !== '' && segment !== '.' && segment !== '..')
    && !path.posix.isAbsolute(value)
    && path.posix.normalize(value) === value;
}

function isCanonicalCachePath(value) {
  const segments = value.split('/');
  return isCanonicalRelativePath(value)
    && segments.length > 1
    && new Set(['cache', 'gradle-home']).has(segments[0]);
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

export async function verifyCacheArtifact(root, cacheDigest) {
  if (!DIGEST.test(cacheDigest)) throw new TypeError('Verified Gradle cache digest is invalid');
  const rootStat = await lstat(root).catch((error) => {
    if (error?.code === 'ENOENT') throw new BuildBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'VERIFIED_GRADLE_CACHE_ABSENT');
    throw error;
  });
  if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) throw new TypeError('Verified Gradle cache root is not a real directory');
  let manifestBytes;
  try {
    manifestBytes = await readFile(path.join(root, 'manifest.sha256'));
  } catch (error) {
    if (error?.code === 'ENOENT') throw new BuildBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'VERIFIED_GRADLE_CACHE_ABSENT');
    throw error;
  }
  if (sha256(manifestBytes) !== cacheDigest) throw new TypeError('Verified Gradle cache content address differs from its manifest');
  const manifest = manifestBytes.toString('utf8');
  if (!Buffer.from(manifest, 'utf8').equals(manifestBytes) || manifest.includes('\r') || !manifest.endsWith('\n')) {
    throw new TypeError('Verified Gradle cache manifest encoding is not canonical');
  }
  const lines = manifest.split('\n');
  lines.pop();
  if (lines.shift() !== '# accord-verified-gradle-cache-v1' || lines.some((line) => line === '')) {
    throw new TypeError('Verified Gradle cache manifest header or line structure is invalid');
  }
  const entries = new Map();
  const inputs = new Set();
  let fileRowsStarted = false;
  for (const line of lines) {
    const input = /^# input (.+) (sha256:[0-9a-f]{64})$/u.exec(line);
    if (input) {
      if (fileRowsStarted || !isCanonicalRelativePath(input[1]) || inputs.has(input[1])) {
        throw new TypeError('Verified Gradle cache input binding is invalid, duplicated, or non-canonical');
      }
      inputs.add(input[1]);
      continue;
    }
    fileRowsStarted = true;
    const match = /^([0-9a-f]{64})  (.+)$/u.exec(line);
    if (!match || !isCanonicalCachePath(match[2]) || entries.has(match[2])) {
      throw new TypeError('Verified Gradle cache manifest entry is invalid, duplicated, or non-canonical');
    }
    entries.set(match[2], match[1]);
  }
  if (![...entries.keys()].some((item) => item.startsWith('cache/'))
      || ![...entries.keys()].some((item) => item.startsWith('gradle-home/'))) {
    throw new TypeError('Verified Gradle cache manifest inventory is incomplete');
  }

  const files = await walkCache(root);
  const actual = files.map((file) => file.relative).sort();
  const expected = ['manifest.sha256', ...entries.keys()].sort();
  if (actual.length !== expected.length || actual.some((item, index) => item !== expected[index])) {
    throw new TypeError('Verified Gradle cache contains an unlisted, missing, or extra file in its inventory');
  }
  for (const file of files) {
    const stat = await lstat(file.absolute);
    if (!stat.isFile() || stat.isSymbolicLink()) throw new TypeError(`Verified Gradle cache contains a non-regular file: ${file.relative}`);
    const observed = createHash('sha256').update(await readFile(file.absolute)).digest('hex');
    const expectedDigest = file.relative === 'manifest.sha256' ? cacheDigest.slice(7) : entries.get(file.relative);
    if (observed !== expectedDigest) throw new TypeError(`Verified Gradle cache file digest mismatch: ${file.relative}`);
  }
  return root;
}

async function verifyCache(cacheDigest) {
  const root = path.join(REPOSITORY_ROOT, 'build', 'ci', 'verified-gradle-cache', cacheDigest.slice(7));
  return verifyCacheArtifact(root, cacheDigest);
}

async function atomicWriteJson(output, document) {
  const target = path.resolve(REPOSITORY_ROOT, output);
  const relative = path.relative(REPOSITORY_ROOT, target);
  if (relative.startsWith('..') || path.isAbsolute(relative) || path.extname(target) !== '.json') throw new TypeError('Build facts output must be repository-local JSON');
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

function metadataDigest(metadata) {
  const digest = metadata['containerimage.digest'] ?? metadata['containerimage.descriptor']?.digest;
  if (!DIGEST.test(digest) || /^sha256:0{64}$/u.test(digest)) throw new TypeError('Buildx did not return an immutable image digest');
  return digest;
}

async function main(argv) {
  const options = parseCli(argv);
  const contextBytes = await readFile(path.resolve(REPOSITORY_ROOT, options.context));
  const context = await validateContext(JSON.parse(contextBytes.toString('utf8')));
  const { lock, digest: imageLockDigest } = await loadImageLock();
  assertProductionImageLock(lock);
  await verifyCache(options.cacheSha256);
  const timestamp = await runNative('git', ['show', '-s', '--format=%ct', context.remote_sha]);
  if (timestamp.exitCode !== 0 || !/^[1-9][0-9]{8,12}\r?\n?$/u.test(timestamp.output.trim())) throw new TypeError('Source commit timestamp is unavailable');
  const sourceDateEpoch = Number(timestamp.output.trim());
  const artifacts = [];
  const temporaryRoot = path.join(REPOSITORY_ROOT, 'build', 'ci', 'image-metadata', randomUUID());
  await mkdir(temporaryRoot, { recursive: true });
  try {
    for (const platform of options.platforms) {
      const buildImage = lockedReference(lock, 'java-build', platform);
      const runtimeImage = lockedReference(lock, 'java-runtime', platform);
      const rendered = renderAll(lock, 'linux/amd64');
      for (const application of rendered) {
        const dockerfile = path.join(REPOSITORY_ROOT, application.dockerfile);
        const actual = await readFile(dockerfile, 'utf8').catch((error) => {
          if (error?.code === 'ENOENT') throw new BuildBlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', 'LOCK_RENDERED_DOCKERFILES_ABSENT');
          throw error;
        });
        if (actual !== application.bytes) throw new TypeError(`Dockerfile is not the exact authoritative rendering: ${application.dockerfile}`);
        const arch = platform.slice('linux/'.length);
        const publicationTag = `${context.registry_repository}/${application.name}:${context.remote_sha}-${arch}`;
        const metadataPath = path.join(temporaryRoot, `${application.name}-${arch}.json`);
        const command = [
          'buildx', 'build', '--platform', platform, '--file', application.dockerfile,
          '--build-arg', `BUILD_IMAGE=${buildImage}`, '--build-arg', `RUNTIME_IMAGE=${runtimeImage}`,
          '--build-arg', `GRADLE_CACHE_SHA256=${options.cacheSha256.slice(7)}`,
          '--build-arg', `SOURCE_DATE_EPOCH=${sourceDateEpoch}`,
          '--tag', publicationTag, '--push', '--metadata-file', metadataPath, '.',
        ];
        const execution = await runNative('docker', command);
        if (execution.exitCode !== 0) throw new BuildBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'REGISTRY_OR_BUILDER_UNAVAILABLE');
        const digest = metadataDigest(JSON.parse(await readFile(metadataPath, 'utf8')));
        const immutableLocator = `${context.registry_repository}/${application.name}@${digest}`;
        const inspect = await runNative('docker', ['buildx', 'imagetools', 'inspect', '--raw', immutableLocator]);
        if (inspect.exitCode !== 0) throw new BuildBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'PUSHED_DIGEST_UNAVAILABLE');
        const inspected = `sha256:${createHash('sha256').update(inspect.output).digest('hex')}`;
        if (inspected !== digest) throw new TypeError('Registry bytes differ from the pushed image digest');
        artifacts.push({ name: application.name, kind: 'image', platform, digest, immutable_locator: immutableLocator });
      }
    }
    artifacts.sort((left, right) => `${left.name}/${left.platform}`.localeCompare(`${right.name}/${right.platform}`));
    const facts = {
      schema_version: '1.0.0', context_sha256: sha256(contextBytes), image_lock_sha256: imageLockDigest,
      remote_sha: context.remote_sha, source_date_epoch: sourceDateEpoch, artifacts,
    };
    await atomicWriteJson(options.output, facts);
    process.stdout.write(`${canonicalJson(facts)}\n`);
    return 0;
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    if (error instanceof BuildBlockedError || error?.name === 'ImageLockBlockedError') {
      const reasonCode = error.reasonCode ?? 'BLOCKED_EXTERNAL_IMAGE_RESOLUTION';
      process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'BLOCKED', reason_code: reasonCode, detail_code: error.detailCode ?? 'AUTHORITATIVE_IMAGE_LOCK_UNAVAILABLE' })}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`build-images: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
