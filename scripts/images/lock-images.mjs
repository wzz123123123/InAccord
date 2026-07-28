import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

import {
  REPOSITORY_ROOT,
  canonicalJson,
  exitCodeForStatus,
  sha256,
  writeCheckResult,
} from '../verification/check-result.mjs';

const LOCK_PATH = path.join(REPOSITORY_ROOT, 'infra', 'images', 'images.lock.json');
const PROVENANCE_PATH = path.join(
  REPOSITORY_ROOT,
  'apps', 'control-plane', 'worker', 'src', 'test', 'resources',
  'temporal', 'reconciliation-workflow-v1.provenance.json',
);
const MINIO_APPROVAL_PATH = path.join(REPOSITORY_ROOT, 'infra', 'images', 'minio-source-approvals.json');
const MINIO_APPROVAL_EVIDENCE_PATH = path.join(
  REPOSITORY_ROOT,
  'infra',
  'images',
  'minio-source-approval-evidence.json',
);
const RESULT_PATH = 'build/verification/ft13-image-lock.json';
const ROLES = Object.freeze([
  'postgres',
  'temporal-schema-tool',
  'temporal-server',
  'temporal-ui',
  'minio',
  'minio-client',
  'wiremock',
  'localstack',
  'otel-collector',
  'java-build',
  'java-runtime',
]);
const APPROVED_REGISTRIES = new Set(['docker.io', 'ghcr.io', 'quay.io', 'public.ecr.aws']);
const APPROVED_PUBLIC_MIRRORS = new Set(['docker.m.daocloud.io']);
const DIGEST_PATTERN = /^sha256:[0-9a-f]{64}$/u;
const TEMPORAL_EXPECTED = Object.freeze({
  schema_tool: {
    coordinate: 'temporalio/admin-tools:1.28.1-tctl-1.18.4-cli-1.4.1',
    repository_digest: 'sha256:01537b62d995f27a0f0d33a01ac4caa6779622f454fb2eb36fed0dccd45c6244',
    linux_amd64_manifest_digest: 'sha256:00864ac86e79aec0d418582892d3435564c613b68b72fe2e41e9c50176794983',
  },
  server: {
    coordinate: 'temporalio/server:1.28.1',
    repository_digest: 'sha256:acaf8454947544312216c6e153929b7db060571f736bbced290bfaf76e287499',
    linux_amd64_manifest_digest: 'sha256:0842f5e71b5c935adad01d133457d886e1748a675f79f5cbb6758aee5031dcb0',
  },
});
const STATIC_SOURCES = Object.freeze({
  postgres: source('postgres:17.5', 'docker.io/library/postgres', 'PostgreSQL', '2029-11-08'),
  'temporal-ui': source('temporalio/ui:2.39.0', 'docker.io/temporalio/ui', 'MIT', '2027-06-30'),
  wiremock: source('wiremock/wiremock:3.13.1', 'docker.io/wiremock/wiremock', 'Apache-2.0', '2027-06-30'),
  localstack: source('localstack/localstack:4.6.0', 'docker.io/localstack/localstack', 'Apache-2.0', '2027-06-30'),
  'otel-collector': source('otel/opentelemetry-collector-contrib:0.129.1', 'docker.io/otel/opentelemetry-collector-contrib', 'Apache-2.0', '2027-06-30'),
  'java-build': source('eclipse-temurin:21.0.11_10-jdk-noble', 'docker.io/library/eclipse-temurin', 'GPL-2.0-with-classpath-exception', '2031-10-01'),
  'java-runtime': source('eclipse-temurin:21.0.11_10-jre-noble', 'docker.io/library/eclipse-temurin', 'GPL-2.0-with-classpath-exception', '2031-10-01'),
});

function source(sourceTag, canonicalRepository, licenseSpdx, endOfSupport, provenance = 'registry-manifest-v2') {
  return { sourceTag, canonicalRepository, licenseSpdx, endOfSupport, provenance };
}

export function resolutionReference(sourceTag, canonicalRepository, publicMirror) {
  if (typeof publicMirror !== 'string' || publicMirror.length === 0) {
    return {
      index: sourceTag,
      repository: canonicalRepository,
      provenance: 'registry-manifest-v2',
    };
  }
  if (!APPROVED_PUBLIC_MIRRORS.has(publicMirror)) {
    throw new TypeError('Image resolution requires an approved public registry mirror');
  }
  if (!canonicalRepository.startsWith('docker.io/')) {
    return {
      index: sourceTag,
      repository: canonicalRepository,
      provenance: 'registry-manifest-v2',
    };
  }
  const separator = sourceTag.lastIndexOf(':');
  if (separator <= sourceTag.lastIndexOf('/')) {
    throw new TypeError('Docker Hub source tag is not closed');
  }
  const mirrorRepository = `${publicMirror}/${canonicalRepository.slice('docker.io/'.length)}`;
  return {
    index: `${mirrorRepository}:${sourceTag.slice(separator + 1)}`,
    repository: mirrorRepository,
    provenance: 'approved-public-mirror-v1',
  };
}

export function requiredPlatformsForRole(role) {
  return ['java-build', 'java-runtime', 'minio', 'minio-client'].includes(role)
    ? ['linux/amd64', 'linux/arm64']
    : ['linux/amd64'];
}

export function findPlatformDescriptor(manifest, platformName) {
  const [os, architecture] = platformName.split('/');
  const candidates = Array.isArray(manifest?.manifests)
    ? manifest.manifests.filter((item) => {
      if (item?.platform?.os !== os || item.platform.architecture !== architecture) return false;
      if (architecture === 'arm64') {
        return item.platform.variant === undefined || item.platform.variant === 'v8';
      }
      return item.platform.variant === undefined;
    })
    : [];
  return candidates.length === 1 ? candidates[0] : undefined;
}

class BlockedError extends Error {
  constructor(reasonCode, message, toolObservations = []) {
    super(message);
    this.reasonCode = reasonCode;
    this.toolObservations = toolObservations;
  }
}

function parseCli(argv) {
  let mode;
  let role;
  const seen = new Set();
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    if (!['--mode', '--role'].includes(option)) throw new TypeError(`Unknown option: ${option}`);
    if (seen.has(option)) throw new TypeError(`Repeated option: ${option}`);
    seen.add(option);
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) throw new TypeError(`Missing value for ${option}`);
    index += 1;
    if (option === '--mode') mode = value;
    else role = value;
  }
  if (!mode || !role) throw new TypeError('Exactly --mode and --role are required');
  if (!['initialize', 'refresh'].includes(mode)) throw new TypeError(`Invalid mode: ${mode}`);
  if (mode === 'initialize' && role !== 'all') throw new TypeError('initialize requires --role all');
  if (mode === 'refresh' && (role === 'all' || !ROLES.includes(role))) {
    throw new TypeError('refresh requires one exact image role');
  }
  return { mode, role };
}

function assertClosedTemporalRecord(name, actual) {
  const expected = TEMPORAL_EXPECTED[name];
  if (!actual || typeof actual !== 'object' || Array.isArray(actual)) {
    throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `Task 10 ${name} provenance is absent`);
  }
  const keys = Object.keys(actual).sort();
  const expectedKeys = ['coordinate', 'linux_amd64_manifest_digest', 'repository_digest'];
  if (canonicalJson(keys) !== canonicalJson(expectedKeys)) {
    throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `Task 10 ${name} provenance is not closed`);
  }
  for (const key of expectedKeys) {
    if (actual[key] !== expected[key]) {
      throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `Task 10 ${name}.${key} differs from the pinned value`);
    }
  }
  return actual;
}

async function readTemporalSources() {
  let parsed;
  try {
    parsed = JSON.parse(await readFile(PROVENANCE_PATH, 'utf8'));
  } catch {
    throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', 'Committed Task 10 Temporal provenance is unavailable');
  }
  const schemaTool = assertClosedTemporalRecord('schema_tool', parsed.schema_tool);
  const server = assertClosedTemporalRecord('server', parsed.server);
  return {
    'temporal-schema-tool': source(schemaTool.coordinate, 'docker.io/temporalio/admin-tools', 'MIT', '2027-06-30', 'task10-temporal-provenance'),
    'temporal-server': source(server.coordinate, 'docker.io/temporalio/server', 'MIT', '2027-06-30', 'task10-temporal-provenance'),
  };
}

function validateMinioSource(value, label) {
  if (!value || value.includes('@') || /:(?:latest|stable|edge)$/iu.test(value)) {
    throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `${label} must be an immutable-reviewed registry/repository:source-tag`);
  }
  const match = /^([a-z0-9.-]+)\/([a-z0-9]+(?:[._/-][a-z0-9]+)*):([A-Za-z0-9][A-Za-z0-9._-]{0,127})$/u.exec(value);
  if (!match || !APPROVED_REGISTRIES.has(match[1])) {
    throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `${label} is not from an approved canonical registry`);
  }
  return { sourceTag: value, canonicalRepository: `${match[1]}/${match[2]}` };
}

async function readMinioSources() {
  const server = validateMinioSource(process.env.ACCORD_MINIO_SERVER_SOURCE, 'ACCORD_MINIO_SERVER_SOURCE');
  const client = validateMinioSource(process.env.ACCORD_MINIO_CLIENT_SOURCE, 'ACCORD_MINIO_CLIENT_SOURCE');
  let approval;
  let evidence;
  let evidenceBytes;
  try {
    approval = JSON.parse(await readFile(MINIO_APPROVAL_PATH, 'utf8'));
    evidenceBytes = await readFile(MINIO_APPROVAL_EVIDENCE_PATH);
    evidence = JSON.parse(evidenceBytes.toString('utf8'));
  } catch {
    throw new BlockedError(
      'BLOCKED_EXTERNAL_IMAGE_RESOLUTION',
      'Approved MinIO license, support, and platform-manifest evidence is unavailable',
    );
  }
  const evidenceKeys = evidence && typeof evidence === 'object'
    ? Object.keys(evidence).sort()
    : [];
  const expectedEvidenceKeys = [
    'approved_at',
    'approved_by',
    'production_authority',
    'recorded_by',
    'review_expires_on',
    'schema_version',
    'scope',
    'sources',
    'support_basis',
  ];
  if (canonicalJson(evidenceKeys) !== canonicalJson(expectedEvidenceKeys)
      || evidence.schema_version !== '1.0.0'
      || evidence.scope !== 'local-foundation-only'
      || evidence.production_authority !== false
      || !/^\d{4}-\d{2}-\d{2}$/u.test(evidence.review_expires_on)
      || Date.parse(`${evidence.review_expires_on}T23:59:59Z`) < Date.now()) {
    throw new BlockedError(
      'BLOCKED_EXTERNAL_IMAGE_RESOLUTION',
      'MinIO local source approval evidence is invalid, expired, or exceeds its authority',
    );
  }
  const evidenceDigest = sha256(evidenceBytes);
  const records = {};
  for (const [role, input, key] of [['minio', server, 'server'], ['minio-client', client, 'client']]) {
    const record = approval[key];
    const sourceEvidence = evidence.sources?.[key];
    const keys = record && typeof record === 'object' ? Object.keys(record).sort() : [];
    if (canonicalJson(keys) !== canonicalJson(['end_of_support', 'evidence_digest', 'license_spdx', 'source_tag'])) {
      throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `MinIO ${key} approval is absent or not closed`);
    }
    if (record.source_tag !== input.sourceTag
        || record.evidence_digest !== evidenceDigest
        || record.source_tag !== sourceEvidence?.source_tag
        || record.license_spdx !== sourceEvidence?.license_spdx
        || record.end_of_support !== evidence.review_expires_on
        || !DIGEST_PATTERN.test(sourceEvidence?.license_sha256)
        || !DIGEST_PATTERN.test(sourceEvidence?.manifest_digest_observed)
        || !DIGEST_PATTERN.test(sourceEvidence?.linux_amd64_manifest_observed)
        || !DIGEST_PATTERN.test(sourceEvidence?.linux_arm64_manifest_observed)) {
      throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `MinIO ${key} approval does not bind the requested source`);
    }
    records[role] = {
      ...source(input.sourceTag, input.canonicalRepository, record.license_spdx, record.end_of_support),
      expectedManifestDigest: sourceEvidence.manifest_digest_observed,
      expectedPlatforms: {
        'linux/amd64': sourceEvidence.linux_amd64_manifest_observed,
        'linux/arm64': sourceEvidence.linux_arm64_manifest_observed,
      },
      sourceApprovalDigest: evidenceDigest,
    };
  }
  return records;
}

function runRawInspect(reference) {
  const executable = 'docker';
  const argv = ['buildx', 'imagetools', 'inspect', '--raw', reference];
  return new Promise((resolve, reject) => {
    const started = process.hrtime.bigint();
    const chunks = [];
    let total = 0;
    let diagnostics = '';
    const child = spawn(executable, argv, {
      cwd: REPOSITORY_ROOT,
      env: process.env,
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.on('data', (chunk) => {
      total += chunk.length;
      if (total <= 8 * 1024 * 1024) chunks.push(chunk);
      else child.kill('SIGKILL');
    });
    child.stderr.on('data', (chunk) => {
      if (diagnostics.length < 4096) diagnostics += chunk.toString('utf8').slice(0, 4096 - diagnostics.length);
    });
    const timer = setTimeout(() => child.kill('SIGKILL'), 30_000);
    timer.unref();
    child.once('error', (error) => {
      clearTimeout(timer);
      reject(new BlockedError('BLOCKED_TOOLCHAIN', `docker buildx is unavailable: ${error.code ?? 'spawn failure'}`));
    });
    child.once('close', (code) => {
      clearTimeout(timer);
      const duration = Number((process.hrtime.bigint() - started) / 1_000_000n);
      const observation = {
        tool_id: 'docker-buildx',
        executable,
        argv,
        exit_code: code ?? -1,
        duration_ms: Math.min(duration, 300_000),
        observed_version: code === 0 ? 'raw-manifest-resolved' : 'manifest-resolution-failed',
        normalized_version: 'imagetools-inspect',
      };
      if (code !== 0 || total > 8 * 1024 * 1024) {
        reject(new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `Manifest resolution failed for ${reference}: ${diagnostics.trim() || 'no diagnostic'}`, [observation]));
      } else resolve({ bytes: Buffer.concat(chunks), observation });
    });
  });
}

async function resolveImage(role, definition, temporalExpected) {
  const resolution = resolutionReference(
    definition.sourceTag,
    definition.canonicalRepository,
    process.env.ACCORD_PUBLIC_IMAGE_MIRROR,
  );
  const index = await runRawInspect(resolution.index);
  let manifest;
  try {
    manifest = JSON.parse(index.bytes.toString('utf8'));
  } catch {
    throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `Registry returned non-JSON manifest bytes for ${role}`, [index.observation]);
  }
  const repositoryDigest = sha256(index.bytes);
  if (!Array.isArray(manifest.manifests)) {
    throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `${role} is not a multi-platform manifest list`, [index.observation]);
  }
  const platforms = {};
  const observations = [index.observation];
  for (const platformName of requiredPlatformsForRole(role)) {
    const descriptor = findPlatformDescriptor(manifest, platformName);
    if (!descriptor || !DIGEST_PATTERN.test(descriptor.digest)) {
      throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `${role} has no unambiguous ${platformName} manifest`, observations);
    }
    const raw = await runRawInspect(`${resolution.repository}@${descriptor.digest}`);
    observations.push(raw.observation);
    if (sha256(raw.bytes) !== descriptor.digest) {
      throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `${role} ${platformName} raw bytes do not match its digest`, observations);
    }
    platforms[platformName] = {
      digest: descriptor.digest,
      media_type: descriptor.mediaType,
    };
  }
  if (temporalExpected) {
    if (repositoryDigest !== temporalExpected.repository_digest || platforms['linux/amd64'].digest !== temporalExpected.linux_amd64_manifest_digest) {
      throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `${role} registry bytes differ from Task 10 provenance`, observations);
    }
  }
  if (definition.expectedManifestDigest) {
    if (repositoryDigest !== definition.expectedManifestDigest) {
      throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `${role} registry manifest differs from approved evidence`, observations);
    }
    for (const [platformName, expectedDigest] of Object.entries(definition.expectedPlatforms)) {
      if (platforms[platformName]?.digest !== expectedDigest) {
        throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', `${role} ${platformName} differs from approved evidence`, observations);
      }
    }
  }
  return {
    image: {
      source_tag: definition.sourceTag,
      canonical_repository: definition.canonicalRepository,
      manifest_digest: repositoryDigest,
      platforms,
      retrieved_at: new Date().toISOString(),
      license_spdx: definition.licenseSpdx,
      end_of_support: definition.endOfSupport,
      evidence: {
        resolver: 'docker-buildx-imagetools',
        raw_manifest_sha256: repositoryDigest,
        provenance: definition.provenance === 'task10-temporal-provenance'
          ? definition.provenance
          : resolution.provenance,
        ...(definition.sourceApprovalDigest
          ? {
            source_approval_sha256: definition.sourceApprovalDigest,
            approval_scope: 'local-foundation-only',
            production_authority: false,
          }
          : {}),
      },
    },
    observations,
  };
}

async function validateLock(lock) {
  const schema = JSON.parse(await readFile(path.join(REPOSITORY_ROOT, 'contracts', 'supply-chain', 'image-lock.schema.json'), 'utf8'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  if (!validate(lock)) throw new TypeError(`Generated image lock is invalid: ${ajv.errorsText(validate.errors)}`);
  const seenDigests = new Map();
  for (const [role, image] of Object.entries(lock.images)) {
    const owner = seenDigests.get(image.manifest_digest);
    if (owner && owner !== image.canonical_repository) throw new TypeError(`Digest is reused by ${owner} and ${image.canonical_repository}`);
    seenDigests.set(image.manifest_digest, image.canonical_repository);
    if (!ROLES.includes(role)) throw new TypeError(`Unknown role: ${role}`);
  }
}

async function atomicWriteLock(lock) {
  await mkdir(path.dirname(LOCK_PATH), { recursive: true });
  const temporary = path.join(path.dirname(LOCK_PATH), `.images.lock.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(`${canonicalJson(lock)}\n`, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, LOCK_PATH);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
}

async function execute({ mode, role }) {
  const startedAt = new Date().toISOString();
  const temporal = await readTemporalSources();
  const minio = await readMinioSources();
  const definitions = { ...STATIC_SOURCES, ...temporal, ...minio };
  let existing;
  if (mode === 'refresh') {
    try {
      existing = JSON.parse(await readFile(LOCK_PATH, 'utf8'));
      await validateLock(existing);
    } catch {
      throw new BlockedError('BLOCKED_EXTERNAL_IMAGE_RESOLUTION', 'A valid authoritative lock is required before refresh');
    }
  }
  const requested = mode === 'initialize' ? ROLES : [role];
  const images = mode === 'initialize' ? {} : { ...existing.images };
  const observations = [];
  for (const requestedRole of requested) {
    const temporalExpected = requestedRole === 'temporal-schema-tool'
      ? TEMPORAL_EXPECTED.schema_tool
      : requestedRole === 'temporal-server' ? TEMPORAL_EXPECTED.server : undefined;
    const resolved = await resolveImage(requestedRole, definitions[requestedRole], temporalExpected);
    images[requestedRole] = resolved.image;
    observations.push(...resolved.observations);
  }
  const lock = { schema_version: '1.0.0', generated_at: new Date().toISOString(), images };
  await validateLock(lock);
  await atomicWriteLock(lock);
  const lockBytes = await readFile(LOCK_PATH);
  const result = {
    schema_version: '1.0.0',
    check_id: 'ft13-image-lock',
    status: 'PASS',
    reason_code: 'PASS',
    started_at: startedAt,
    finished_at: new Date().toISOString(),
    evidence: [sha256(lockBytes)],
    tool_observations: observations,
  };
  await writeCheckResult(result, RESULT_PATH);
  return result;
}

async function main(argv) {
  let options;
  try {
    options = parseCli(argv);
  } catch (error) {
    process.stderr.write(`lock-images: ${error instanceof Error ? error.message : String(error)}\n`);
    return 1;
  }
  try {
    const result = await execute(options);
    process.stdout.write(`${canonicalJson(result)}\n`);
    return 0;
  } catch (error) {
    if (!(error instanceof BlockedError)) throw error;
    const now = new Date().toISOString();
    const result = {
      schema_version: '1.0.0',
      check_id: 'ft13-image-lock',
      status: 'BLOCKED',
      reason_code: error.reasonCode,
      started_at: now,
      finished_at: now,
      evidence: [],
      tool_observations: error.toolObservations,
    };
    await writeCheckResult(result, RESULT_PATH);
    process.stderr.write(`lock-images: ${error.message}\n`);
    process.stdout.write(`${canonicalJson(result)}\n`);
    return exitCodeForStatus(result.status);
  }
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`lock-images: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
