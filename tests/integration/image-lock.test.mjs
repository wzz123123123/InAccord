import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

import {
  findPlatformDescriptor,
  requiredPlatformsForRole,
  resolutionReference,
} from '../../scripts/images/lock-images.mjs';
import {
  assertProductionImageLock,
  ImageLockBlockedError,
} from '../../scripts/ci/render-runtime-dockerfiles.mjs';

const root = path.resolve(import.meta.dirname, '..', '..');
const roles = [
  'java-build',
  'java-runtime',
  'localstack',
  'minio',
  'minio-client',
  'otel-collector',
  'postgres',
  'temporal-schema-tool',
  'temporal-server',
  'temporal-ui',
  'wiremock',
];
const digest = (character) => `sha256:${character.repeat(64)}`;

async function json(relative) {
  return JSON.parse(await readFile(path.join(root, relative), 'utf8'));
}

function validator(schema) {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  return ajv.compile(schema);
}

function sampleImage(index) {
  const hexadecimal = (index + 1).toString(16).padStart(64, '0');
  return {
    source_tag: `docker.io/library/example-${index}:1.0.0`,
    canonical_repository: `docker.io/library/example-${index}`,
    manifest_digest: `sha256:${hexadecimal}`,
    platforms: {
      'linux/amd64': { digest: digest('a'), media_type: 'application/vnd.oci.image.manifest.v1+json' },
      'linux/arm64': { digest: digest('b'), media_type: 'application/vnd.oci.image.manifest.v1+json' },
    },
    retrieved_at: '2026-07-27T00:00:00.000Z',
    license_spdx: 'Apache-2.0',
    end_of_support: '2027-07-27',
    evidence: {
      resolver: 'docker-buildx-imagetools',
      raw_manifest_sha256: `sha256:${hexadecimal}`,
      provenance: 'registry-manifest-v2',
    },
  };
}

test('image lock schema is closed and fixes the exact role set', async () => {
  const validate = validator(await json('contracts/supply-chain/image-lock.schema.json'));
  const images = Object.fromEntries(roles.map((role, index) => [role, sampleImage(index)]));
  for (const role of ['minio', 'minio-client']) {
    images[role].evidence = {
      ...images[role].evidence,
      source_approval_sha256: digest('c'),
      approval_scope: 'local-foundation-only',
      production_authority: false,
    };
  }
  const candidate = { schema_version: '1.0.0', generated_at: '2026-07-27T00:00:00.000Z', images };
  assert.equal(validate(candidate), true, JSON.stringify(validate.errors));

  for (const mutation of [
    { ...candidate, unexpected: true },
    { ...candidate, images: { ...images, unknown: sampleImage(12) } },
    { ...candidate, images: { ...images, postgres: { ...images.postgres, unexpected: true } } },
    {
      ...candidate,
      images: {
        ...images,
        postgres: {
          ...images.postgres,
          platforms: { ...images.postgres.platforms, 'linux/s390x': { digest: digest('c'), media_type: 'x' } },
        },
      },
    },
    {
      ...candidate,
      images: { ...images, postgres: { ...images.postgres, manifest_digest: digest('A') } },
    },
    {
      ...candidate,
      images: { ...images, postgres: { ...images.postgres, manifest_digest: digest('0') } },
    },
  ]) {
    assert.equal(validate(mutation), false, 'mutation must be rejected');
  }
});

test('check result schema rejects unknown evidence and status/reason mismatches', async () => {
  const schema = await json('contracts/verification/check-result.schema.json');
  const validate = validator(schema);
  const base = {
    schema_version: '1.0.0',
    check_id: 'ft13-contract',
    status: 'PASS',
    reason_code: 'PASS',
    started_at: '2026-07-27T00:00:00.000Z',
    finished_at: '2026-07-27T00:00:01.000Z',
    evidence: [digest('a')],
    tool_observations: [],
  };
  assert.equal(validate(base), true, JSON.stringify(validate.errors));
  assert.equal(validate({ ...base, stdout: 'forbidden' }), false);
  assert.equal(validate({ ...base, status: 'FAIL' }), false);
});

test('compose consumes only required environment image references', async () => {
  const compose = YAML.parse(await readFile(path.join(root, 'infra/local/compose.yaml'), 'utf8'));
  for (const [name, service] of Object.entries(compose.services)) {
    assert.match(service.image, /^\$\{ACCORD_IMAGE_[A-Z0-9_]+:\?run render-compose-images[.]mjs\}$/u, name);
    assert.doesNotMatch(service.image, /(?:@sha256:|:[\w.-]+$)/u, name);
  }
});

test('repository has exactly one authoritative image lock path', async () => {
  const matches = [];
  async function visit(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      if (['.git', '.gradle', 'build', 'node_modules', 'state'].includes(entry.name)) continue;
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) await visit(target);
      if (entry.isFile() && /^images.*[.]lock[.]json$/u.test(entry.name)) matches.push(path.relative(root, target).replaceAll('\\', '/'));
    }
  }
  await visit(root);
  assert.deepEqual(matches.sort(), ['infra/images/images.lock.json']);
  assert.equal((await stat(path.join(root, matches[0]))).isFile(), true);
});

test('all-scope image verification does not require ignored rendered state', async () => {
  const verifier = await import('../../scripts/images/verify-images.mjs');
  assert.equal(typeof verifier.verifiedComposeEnvironment, 'function');
  const missing = Object.assign(new Error('missing generated state'), { code: 'ENOENT' });
  assert.equal(
    await verifier.verifiedComposeEnvironment('all', 'canonical\n', {
      read: async () => { throw missing; },
    }),
    'canonical\n',
  );
  await assert.rejects(
    verifier.verifiedComposeEnvironment('local', 'canonical\n', {
      read: async () => { throw missing; },
    }),
    /missing generated state/u,
  );
  await assert.rejects(
    verifier.verifiedComposeEnvironment('all', 'canonical\n', {
      read: async () => 'stale\n',
    }),
    /stale or non-canonical/u,
  );
});

test('local MinIO source approval binds the reviewed evidence bytes and expires closed', async () => {
  const evidenceBytes = await readFile(path.join(root, 'infra/images/minio-source-approval-evidence.json'));
  const evidence = JSON.parse(evidenceBytes.toString('utf8'));
  const approval = await json('infra/images/minio-source-approvals.json');
  const evidenceDigest = `sha256:${createHash('sha256').update(evidenceBytes).digest('hex')}`;

  assert.equal(evidence.scope, 'local-foundation-only');
  assert.equal(evidence.production_authority, false);
  assert.equal(evidence.review_expires_on, '2026-10-27');
  for (const key of ['server', 'client']) {
    const role = key === 'server' ? 'minio' : 'minio-client';
    assert.equal(approval[key].source_tag, evidence.sources[key].source_tag);
    assert.equal(approval[key].license_spdx, evidence.sources[key].license_spdx);
    assert.match(evidence.sources[key].license_sha256, /^sha256:[0-9a-f]{64}$/u);
    assert.equal(approval[key].end_of_support, evidence.review_expires_on);
    assert.equal(approval[key].evidence_digest, evidenceDigest);
    const lock = await json('infra/images/images.lock.json');
    assert.equal(lock.images[role].evidence.source_approval_sha256, evidenceDigest);
    assert.equal(lock.images[role].evidence.approval_scope, 'local-foundation-only');
    assert.equal(lock.images[role].evidence.production_authority, false);
  }
});

test('production image publication rejects a lock carrying local-only authority', async () => {
  const lock = await json('infra/images/images.lock.json');
  assert.throws(
    () => assertProductionImageLock(lock),
    (error) => error instanceof ImageLockBlockedError
      && error.detailCode === 'NON_PRODUCTION_IMAGE_AUTHORITY_MINIO',
  );
});

test('public mirror resolution is closed to the approved Docker Hub mirror', () => {
  assert.deepEqual(
    resolutionReference(
      'postgres:17.5',
      'docker.io/library/postgres',
      'docker.m.daocloud.io',
    ),
    {
      index: 'docker.m.daocloud.io/library/postgres:17.5',
      repository: 'docker.m.daocloud.io/library/postgres',
      provenance: 'approved-public-mirror-v1',
    },
  );
  assert.deepEqual(
    resolutionReference(
      'quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z',
      'quay.io/minio/minio',
      'docker.m.daocloud.io',
    ),
    {
      index: 'quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z',
      repository: 'quay.io/minio/minio',
      provenance: 'registry-manifest-v2',
    },
  );
  assert.throws(
    () => resolutionReference('postgres:17.5', 'docker.io/library/postgres', 'mirror.invalid'),
    /approved public registry mirror/u,
  );
});

test('only Java and MinIO roles require dual-architecture local evidence', () => {
  for (const role of ['java-build', 'java-runtime', 'minio', 'minio-client']) {
    assert.deepEqual(requiredPlatformsForRole(role), ['linux/amd64', 'linux/arm64']);
  }
  for (const role of ['postgres', 'temporal-schema-tool', 'temporal-server', 'temporal-ui', 'wiremock', 'localstack', 'otel-collector']) {
    assert.deepEqual(requiredPlatformsForRole(role), ['linux/amd64']);
  }
});

test('OCI arm64 v8 is accepted only when the platform descriptor is unique', () => {
  const arm64 = { digest: digest('a'), platform: { os: 'linux', architecture: 'arm64', variant: 'v8' } };
  assert.equal(findPlatformDescriptor({ manifests: [arm64] }, 'linux/arm64'), arm64);
  assert.equal(findPlatformDescriptor({ manifests: [{ ...arm64, platform: { ...arm64.platform, variant: 'v7' } }] }, 'linux/arm64'), undefined);
  assert.equal(findPlatformDescriptor({ manifests: [arm64, { ...arm64 }] }, 'linux/arm64'), undefined);
});

test('toolchain contains the four independent FT13 pins', async () => {
  const pins = Object.fromEntries((await readFile(path.join(root, '.tool-versions'), 'utf8'))
    .trim().split(/\r?\n/u).map((line) => line.trim().split(/\s+/u, 2)));
  assert.equal(pins.docker, '29.4.2');
  assert.equal(pins['docker-buildx'], '0.33.0');
  assert.equal(pins['docker-compose'], '5.1.3');
  assert.equal(pins.git, '2.52.0');
});
