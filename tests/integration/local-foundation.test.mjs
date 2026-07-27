import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

const root = path.resolve(import.meta.dirname, '..', '..');
const read = (relative) => readFile(path.join(root, relative), 'utf8');

test('local topology exposes exactly the dependency services and no Accord worker', async () => {
  const compose = YAML.parse(await read('infra/local/compose.yaml'));
  assert.deepEqual(Object.keys(compose.services).sort(), [
    'minio', 'minio-init', 'mock-gitlab', 'mock-kms', 'otel-collector', 'postgres',
    'temporal-namespace', 'temporal-schema', 'temporal-server', 'temporal-ui',
  ].sort());
  const serialized = JSON.stringify(compose);
  assert.doesNotMatch(serialized, /TemporalLocalTopologyIT|accord-worker|worker[.]pem|worker-key[.]pem/u);
  assert.doesNotMatch(serialized, /auto-setup|start-dev/u);
  assert.deepEqual(compose.services.postgres.ports, ['127.0.0.1:55432:5432']);
});

test('GitLab 19.1 fixture has GitLab project facts only', async () => {
  const mapping = JSON.parse(await read('infra/local/wiremock/mappings/gitlab-get-project.json'));
  assert.deepEqual(mapping.request, { method: 'GET', urlPath: '/api/v4/projects/77831' });
  const body = JSON.parse(mapping.response.body);
  assert.equal(body.id, 77831);
  assert.equal(body.path_with_namespace, 'acme/demo');
  assert.equal(body.default_branch, 'main');
  assert.equal(body.archived, false);
  assert.equal('node_id' in body, false);
  assert.doesNotMatch(JSON.stringify(mapping), /\/repos\/|github/iu);
});

test('local object-storage capability profile is closed and never production eligible', async () => {
  const schema = JSON.parse(await read('contracts/capabilities/object-storage-adapter.schema.json'));
  const profile = JSON.parse(await read('infra/local/object-storage-capabilities.json'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  assert.equal(validate(profile), true, JSON.stringify(validate.errors));
  assert.equal(profile.production_eligible, false);
  const capabilities = profile.capabilities;
  for (const name of ['immutable_versions', 'sha256_checksums', 'worm', 'legal_hold', 'multipart', 'quarantine_isolation']) {
    assert.equal(capabilities[name].status, 'SUPPORTED_AND_TESTED', name);
  }
  for (const name of ['replication_evidence', 'deletion_receipts']) {
    assert.equal(capabilities[name].status, 'EXTERNAL_EVIDENCE_REQUIRED', name);
  }
});

test('KMS bootstrap creates only the stable local alias', async () => {
  const script = await read('infra/local/localstack/ready.d/10-create-kms-key.sh');
  assert.match(script, /alias\/accord-foundation-local/u);
  assert.match(script, /awslocal kms create-key/u);
  assert.match(script, /awslocal kms create-alias/u);
  assert.doesNotMatch(script, /AWS_SECRET_ACCESS_KEY=/u);
});

test('purging local state also removes persistent Compose volumes', async () => {
  const script = await read('scripts/local-down.mjs');
  assert.match(script, /if \(options\.purge\) downArguments\.push\('--volumes'\)/u);
  assert.match(script, /if \(options\.purge\) await guardedPurge\(\)/u);
});
