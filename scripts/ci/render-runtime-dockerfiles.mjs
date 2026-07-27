import { randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

import { REPOSITORY_ROOT, canonicalJson, sha256 } from '../verification/check-result.mjs';

const LOCK_PATH = path.join(REPOSITORY_ROOT, 'infra', 'images', 'images.lock.json');
const LOCK_SCHEMA_PATH = path.join(REPOSITORY_ROOT, 'contracts', 'supply-chain', 'image-lock.schema.json');
const DIGEST = /^sha256:[0-9a-f]{64}$/u;
const PLATFORMS = new Set(['linux/amd64', 'linux/arm64']);
const APPLICATIONS = Object.freeze([
  { name: 'control-plane-api', project: ':apps:control-plane:api', jar: 'apps/control-plane/api/build/libs/api-0.1.0-SNAPSHOT.jar', uid: 10001, dockerfile: 'apps/control-plane/api/Dockerfile' },
  { name: 'control-plane-worker', project: ':apps:control-plane:worker', jar: 'apps/control-plane/worker/build/libs/worker-0.1.0-SNAPSHOT.jar', uid: 10002, dockerfile: 'apps/control-plane/worker/Dockerfile', probe: true },
  { name: 'webhook-edge', project: ':apps:webhook-edge', jar: 'apps/webhook-edge/build/libs/webhook-edge-0.1.0-SNAPSHOT.jar', uid: 10003, dockerfile: 'apps/webhook-edge/Dockerfile' },
]);

export class ImageLockBlockedError extends Error {
  constructor(detailCode) {
    super(detailCode);
    this.name = 'ImageLockBlockedError';
    this.detailCode = detailCode;
  }
}

export function lockedReference(lock, role, platform) {
  if (!PLATFORMS.has(platform)) throw new TypeError(`Unsupported runtime platform: ${platform}`);
  const image = lock?.images?.[role];
  const repository = image?.canonical_repository;
  const digest = image?.platforms?.[platform]?.digest;
  if (typeof repository !== 'string' || repository !== repository.toLowerCase()
      || !/^(?:docker[.]io|ghcr[.]io|quay[.]io|public[.]ecr[.]aws)\/[a-z0-9]+(?:[._/-][a-z0-9]+)*$/u.test(repository)
      || !DIGEST.test(digest) || /^sha256:0{64}$/u.test(digest)) {
    throw new ImageLockBlockedError(`LOCKED_REFERENCE_UNAVAILABLE_${role.toUpperCase().replaceAll('-', '_')}_${platform.replaceAll('/', '_').toUpperCase()}`);
  }
  return `${repository}@${digest}`;
}

export function assertLockedOverride(value, expected, label) {
  if (value !== undefined && value !== expected) throw new TypeError(`${label} override differs from the selected image-lock role/platform reference`);
  return expected;
}

export function renderDockerfile(application, buildImage, runtimeImage) {
  const tasks = application.probe
    ? [`${application.project}:bootJar`, `${application.project}:workerProbeJar`]
    : [`${application.project}:bootJar`];
  const runtimeLines = [
    `ARG RUNTIME_IMAGE=${runtimeImage}`,
    'FROM ${RUNTIME_IMAGE}',
    `LABEL org.opencontainers.image.title="${application.name}"`,
    'WORKDIR /opt/accord',
    `COPY --from=build --chown=${application.uid}:${application.uid} /workspace/${application.jar} /opt/accord/app.jar`,
  ];
  if (application.probe) {
    runtimeLines.push(
      `COPY --from=build --chown=${application.uid}:${application.uid} /workspace/apps/control-plane/worker/build/probe/worker-probe.jar /opt/accord/bin/worker-probe.jar`,
      `USER ${application.uid}:${application.uid}`,
      'HEALTHCHECK --interval=10s --timeout=2s --start-period=30s --retries=3 CMD ["/usr/bin/java","-jar","/opt/accord/bin/worker-probe.jar","live","30"]',
    );
  } else {
    runtimeLines.push(`USER ${application.uid}:${application.uid}`);
  }
  runtimeLines.push('ENTRYPOINT ["/usr/bin/java","-jar","/opt/accord/app.jar"]');
  return [
    '# Generated only by scripts/ci/render-runtime-dockerfiles.mjs.',
    `ARG BUILD_IMAGE=${buildImage}`,
    'FROM ${BUILD_IMAGE} AS build',
    'ARG GRADLE_CACHE_SHA256',
    'ARG SOURCE_DATE_EPOCH',
    'ENV GRADLE_USER_HOME=/opt/verified-gradle-cache/cache',
    'ENV SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH}',
    'WORKDIR /opt/verified-gradle-cache',
    'COPY build/ci/verified-gradle-cache/${GRADLE_CACHE_SHA256}/ ./',
    'RUN --network=none ["sha256sum","--check","manifest.sha256"]',
    'WORKDIR /workspace',
    'COPY . .',
    `RUN --network=none ["./gradlew","--offline","--no-daemon","--dependency-verification=strict","${tasks.join('","')}"]`,
    ...runtimeLines,
    '',
  ].join('\n');
}

export async function loadImageLock() {
  let bytes;
  try {
    bytes = await readFile(LOCK_PATH);
  } catch (error) {
    if (error?.code === 'ENOENT') throw new ImageLockBlockedError('AUTHORITATIVE_IMAGE_LOCK_ABSENT');
    throw error;
  }
  const schema = JSON.parse(await readFile(LOCK_SCHEMA_PATH, 'utf8'));
  const lock = JSON.parse(bytes.toString('utf8'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  if (!validate(lock)) throw new ImageLockBlockedError('AUTHORITATIVE_IMAGE_LOCK_INVALID');
  return { lock, digest: sha256(bytes) };
}

export function assertProductionImageLock(lock) {
  for (const [role, image] of Object.entries(lock?.images ?? {})) {
    if (image?.evidence?.production_authority === false
        || image?.evidence?.approval_scope === 'local-foundation-only') {
      throw new ImageLockBlockedError(
        `NON_PRODUCTION_IMAGE_AUTHORITY_${role.toUpperCase().replaceAll('-', '_')}`,
      );
    }
  }
  return lock;
}

export function renderAll(lock, platform = 'linux/amd64', overrides = {}) {
  const buildImage = assertLockedOverride(overrides.buildImage, lockedReference(lock, 'java-build', platform), 'BUILD_IMAGE');
  const runtimeImage = assertLockedOverride(overrides.runtimeImage, lockedReference(lock, 'java-runtime', platform), 'RUNTIME_IMAGE');
  return APPLICATIONS.map((application) => ({
    ...application,
    bytes: renderDockerfile(application, buildImage, runtimeImage),
  }));
}

async function atomicWrite(target, bytes) {
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = path.join(path.dirname(target), `.${path.basename(target)}.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(bytes, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, target);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
}

function parseCli(argv) {
  const result = { mode: 'verify', platform: 'linux/amd64' };
  const allowed = new Set(['--mode', '--platform', '--build-image', '--runtime-image']);
  const seen = new Set();
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!allowed.has(option) || !value || value.startsWith('--') || seen.has(option)) throw new TypeError(`Invalid renderer option: ${option}`);
    seen.add(option);
    const key = option.slice(2).replace(/-([a-z])/gu, (_match, letter) => letter.toUpperCase());
    result[key] = value;
  }
  if (!['write', 'verify'].includes(result.mode) || !PLATFORMS.has(result.platform)) throw new TypeError('Renderer mode or platform is invalid');
  return result;
}

async function main(argv) {
  const options = parseCli(argv);
  const { lock, digest } = await loadImageLock();
  const rendered = renderAll(lock, options.platform, options);
  if (options.mode === 'write') {
    for (const item of rendered) await atomicWrite(path.join(REPOSITORY_ROOT, item.dockerfile), item.bytes);
  } else {
    for (const item of rendered) {
      let actual;
      try {
        actual = await readFile(path.join(REPOSITORY_ROOT, item.dockerfile), 'utf8');
      } catch (error) {
        if (error?.code === 'ENOENT') throw new TypeError(`Rendered Dockerfile is absent: ${item.dockerfile}`);
        throw error;
      }
      if (actual !== item.bytes) throw new TypeError(`Rendered Dockerfile differs from the authoritative lock: ${item.dockerfile}`);
    }
  }
  process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'PASS', reason_code: 'PASS', image_lock_sha256: digest, platform: options.platform })}\n`);
  return 0;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    if (error instanceof ImageLockBlockedError) {
      process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'BLOCKED', reason_code: 'BLOCKED_EXTERNAL_IMAGE_RESOLUTION', detail_code: error.detailCode })}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`render-runtime-dockerfiles: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
