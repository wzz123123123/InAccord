import { randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

import { REPOSITORY_ROOT } from '../verification/check-result.mjs';

export const LOCK_PATH = path.join(REPOSITORY_ROOT, 'infra', 'images', 'images.lock.json');
export const COMPOSE_PATH = path.join(REPOSITORY_ROOT, 'infra', 'local', 'compose.yaml');
export const IMAGE_ENV_PATH = path.join(REPOSITORY_ROOT, 'infra', 'local', 'state', 'images.env');
export const COMPOSE_IMAGE_ROLES = Object.freeze([
  'postgres', 'temporal-schema-tool', 'temporal-server', 'temporal-ui',
  'minio', 'minio-client', 'wiremock', 'localstack', 'otel-collector',
]);

export function roleEnvironmentName(role) {
  return `ACCORD_IMAGE_${role.toUpperCase().replaceAll('-', '_')}`;
}

export function dockerPlatform() {
  const explicit = process.env.ACCORD_DOCKER_PLATFORM;
  if (explicit) {
    if (!['linux/amd64', 'linux/arm64'].includes(explicit)) throw new TypeError('ACCORD_DOCKER_PLATFORM must be linux/amd64 or linux/arm64');
    return explicit;
  }
  return process.arch === 'arm64' ? 'linux/arm64' : 'linux/amd64';
}

export async function readAndValidateLock() {
  let lockBytes;
  try {
    lockBytes = await readFile(LOCK_PATH);
  } catch {
    const error = new Error('Authoritative image lock is unavailable');
    error.code = 'BLOCKED_EXTERNAL_IMAGE_RESOLUTION';
    throw error;
  }
  const lock = JSON.parse(lockBytes.toString('utf8'));
  const schema = JSON.parse(await readFile(path.join(REPOSITORY_ROOT, 'contracts', 'supply-chain', 'image-lock.schema.json'), 'utf8'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  if (!validate(lock)) throw new TypeError(`Invalid authoritative image lock: ${ajv.errorsText(validate.errors)}`);
  const digestOwners = new Map();
  for (const image of Object.values(lock.images)) {
    const owner = digestOwners.get(image.manifest_digest);
    if (owner && owner !== image.canonical_repository) throw new TypeError('One digest is assigned to different repositories');
    digestOwners.set(image.manifest_digest, image.canonical_repository);
  }
  return { lock, lockBytes };
}

export async function expectedComposeEnvironment() {
  const { lock, lockBytes } = await readAndValidateLock();
  const compose = YAML.parse(await readFile(COMPOSE_PATH, 'utf8'));
  if (!compose?.services || typeof compose.services !== 'object') throw new TypeError('Compose services are absent');
  const usedVariables = new Set();
  for (const [serviceName, service] of Object.entries(compose.services)) {
    const match = /^\$\{(ACCORD_IMAGE_[A-Z0-9_]+):\?run render-compose-images[.]mjs\}$/u.exec(service.image ?? '');
    if (!match) throw new TypeError(`Compose service ${serviceName} has a non-locked image`);
    usedVariables.add(match[1]);
  }
  const expectedVariables = COMPOSE_IMAGE_ROLES.map(roleEnvironmentName).sort();
  if (JSON.stringify([...usedVariables].sort()) !== JSON.stringify(expectedVariables)) {
    throw new TypeError('Compose image-variable set differs from the closed local role set');
  }
  const platform = dockerPlatform();
  const entries = COMPOSE_IMAGE_ROLES.map((role) => {
    const image = lock.images[role];
    const platformDigest = image?.platforms?.[platform]?.digest;
    if (!platformDigest) throw new TypeError(`Image role ${role} has no ${platform} digest`);
    return [roleEnvironmentName(role), `${image.canonical_repository}@${platformDigest}`];
  }).sort(([left], [right]) => left.localeCompare(right));
  return { bytes: `${entries.map(([name, value]) => `${name}=${value}`).join('\n')}\n`, lockBytes, platform };
}

export async function renderComposeImages() {
  const expected = await expectedComposeEnvironment();
  await mkdir(path.dirname(IMAGE_ENV_PATH), { recursive: true });
  const temporary = path.join(path.dirname(IMAGE_ENV_PATH), `.images.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(expected.bytes, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, IMAGE_ENV_PATH);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
  return expected;
}

async function main(argv) {
  if (argv.length !== 0) throw new TypeError('render-compose-images.mjs accepts no arguments');
  const { platform } = await renderComposeImages();
  process.stdout.write(`Rendered closed Compose image environment for ${platform}\n`);
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`render-compose-images: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = error?.code === 'BLOCKED_EXTERNAL_IMAGE_RESOLUTION' ? 2 : 1;
  }
}
