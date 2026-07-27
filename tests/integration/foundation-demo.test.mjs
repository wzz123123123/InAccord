import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import {
  collectEnvironmentEvidence,
  verifyReceipt,
} from '../../scripts/acceptance/collect-environment-evidence.mjs';
import { canonicalJson } from '../../scripts/acceptance/merge-verdicts.mjs';
import {
  inspectDemoBoundaries,
  runFoundationDemo,
} from '../../scripts/acceptance/run-foundation-demo.mjs';
import {
  scanSensitiveText,
  verifySensitiveLogs,
} from './verify-sensitive-logs.mjs';

test('missing repository-owned demo boundaries are ASSERTION_FAILED, never provider BLOCKED', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-demo-missing-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const output = join(root, 'external-evidence');
  const boundaries = await inspectDemoBoundaries(root);
  assert.ok(boundaries.length >= 8);
  assert.ok(boundaries.every((entry) => entry.present === false));
  const result = await runFoundationDemo({
    repository: root,
    outputDirectory: output,
    now: new Date('2026-07-26T00:00:00Z'),
  });
  assert.equal(result.status, 'FAIL');
  assert.equal(result.reason_code, 'ASSERTION_FAILED');
  assert.equal(Object.hasOwn(result, 'external'), false);
  const stored = await readFile(join(output, 'demo-evidence.json'), 'utf8');
  assert.doesNotMatch(stored, /ReadOnlyReconciliationActivity|ProviderObservationPort/u);
});

test('sensitive scanner reports only service, rule, and count without matched material', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-sensitive-log-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const sentinel = 'sentinel-value-123456789';
  await writeFile(join(root, 'safe.jsonl'), '{"operation":"reconcile","result":"ok"}\n', 'utf8');
  assert.equal((await verifySensitiveLogs({
    services: [{ service: 'worker', path: root }],
    sentinels: [sentinel],
  })).status, 'PASS');
  await writeFile(join(root, 'unsafe.jsonl'), `authorization: Bearer abcdefghijklmnop\n${sentinel}\n`, 'utf8');
  const result = await verifySensitiveLogs({
    services: [{ service: 'worker', path: root }],
    sentinels: [sentinel],
  });
  assert.equal(result.status, 'FAIL');
  assert.ok(result.matches.some((entry) => entry.rule_id === 'AUTHORIZATION_HEADER'));
  assert.ok(result.matches.some((entry) => entry.rule_id === 'SEEDED_SENTINEL'));
  assert.doesNotMatch(JSON.stringify(result), /abcdefghijklmnop|sentinel-value/u);
  assert.deepEqual(scanSensitiveText('clean operation', [sentinel]), []);
});

test('missing external authorities yield named independent BLOCKED receipts', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-environment-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const code = JSON.parse(await readFile(resolve('contracts/golden-fixtures/acceptance/code-pass.json'), 'utf8'));
  const codePath = join(root, 'code.json');
  const outputPath = join(root, 'environment.json');
  await writeFile(codePath, JSON.stringify(code), 'utf8');
  const verdict = await collectEnvironmentEvidence({
    codeVerdictPath: codePath,
    receiptsDirectory: join(root, 'absent-receipts'),
    outputPath,
    now: new Date('2026-07-26T00:00:10Z'),
  });
  assert.equal(verdict.status, 'BLOCKED');
  assert.equal(verdict.checks.length, 12);
  assert.equal(new Set(verdict.checks.map((entry) => entry.reason_code)).size, 12);
  assert.ok(verdict.checks.every((entry) => (
    entry.status === 'BLOCKED'
    && entry.attempt_evidence.length === 0
    && !Object.hasOwn(entry, 'stdout')
    && !Object.hasOwn(entry, 'stderr')
  )));
});

test('external receipt PASS requires an Ed25519 signature, exact binding, and bounded validity', async () => {
  const code = JSON.parse(await readFile(resolve('contracts/golden-fixtures/acceptance/code-pass.json'), 'utf8'));
  const spec = {
    checkId: '01.branch-protection',
    capability: 'authoritative-branch-protection',
    authority: 'accord-source-host',
    evidenceType: 'branch-protection-receipt-v1',
  };
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const receipt = {
    schema_version: '1.0.0',
    check_id: spec.checkId,
    remote_sha: code.remote_sha,
    tree_sha: code.tree_sha,
    image_lock_digest: code.image_lock_digest,
    release_manifest_digest: code.release_manifest_digest,
    evidence_policy_version: code.evidence_policy_version,
    capability: spec.capability,
    authority: spec.authority,
    evidence_type: spec.evidenceType,
    issued_at: '2026-07-26T00:00:00Z',
    expires_at: '2026-07-26T01:00:00Z',
    evidence_digest: '8'.repeat(64),
    signing: {
      algorithm: 'Ed25519',
      key_id: 'production-evidence-1',
    },
  };
  receipt.signing.signature = sign(null, Buffer.from(canonicalJson(receipt)), privateKey).toString('base64');
  const trustStore = {
    schema_version: '1.0.0',
    keys: [{
      authority: spec.authority,
      key_id: receipt.signing.key_id,
      algorithm: 'Ed25519',
      public_key_pem: publicKey.export({ type: 'spki', format: 'pem' }),
    }],
  };
  assert.equal(verifyReceipt({
    receipt,
    receiptDigest: '7'.repeat(64),
    spec,
    code,
    trustStore,
    now: new Date('2026-07-26T00:30:00Z'),
  }), true);
  const changed = structuredClone(receipt);
  changed.tree_sha = '9'.repeat(40);
  assert.throws(() => verifyReceipt({
    receipt: changed,
    receiptDigest: '7'.repeat(64),
    spec,
    code,
    trustStore,
    now: new Date('2026-07-26T00:30:00Z'),
  }));
});
