import { verify as verifySignature } from 'node:crypto';
import { lstat, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  assertNoSensitiveMaterial,
  atomicWriteJson,
  canonicalJson,
  sha256,
  validateVerdictDocument,
  verdictExitCode,
} from './merge-verdicts.mjs';

const OWNER = 'platform-engineering';
const MAX_RECEIPT_AGE_MS = 24 * 60 * 60 * 1000;
const EXTERNAL_CHECKS = [
  externalCheck('01.branch-protection', 'BLOCKED_BRANCH_PROTECTION', 'authoritative-branch-protection', 'accord-source-host', 'branch-protection-receipt-v1'),
  externalCheck('02.mirror', 'BLOCKED_MIRROR', 'authenticated-artifact-mirror', 'enterprise-mirror', 'mirror-provenance-receipt-v1'),
  externalCheck('03.oidc', 'BLOCKED_OIDC', 'protected-release-oidc', 'release-identity-provider', 'oidc-policy-receipt-v1'),
  externalCheck('04.registry', 'BLOCKED_REGISTRY', 'registry-referrers-and-retention', 'enterprise-registry', 'registry-verification-receipt-v1'),
  externalCheck('05.kubernetes', 'BLOCKED_KUBERNETES', 'deployed-workload-identity-and-network-policy', 'production-kubernetes', 'kubernetes-runtime-receipt-v1'),
  externalCheck('06.argocd', 'BLOCKED_ARGOCD', 'immutable-gitops-reconciliation', 'production-argocd', 'argocd-application-receipt-v1'),
  externalCheck('07.external-secret', 'BLOCKED_EXTERNAL_SECRET', 'external-secret-delivery', 'production-secret-store', 'external-secret-receipt-v1'),
  externalCheck('08.pki', 'BLOCKED_PKI', 'live-service-tls', 'production-pki', 'pki-handshake-receipt-v1'),
  externalCheck('09.database-recovery', 'BLOCKED_DATABASE_RECOVERY', 'postgresql-rpo-rto-restore', 'production-postgresql', 'database-recovery-receipt-v1'),
  externalCheck('10.object-capability', 'BLOCKED_OBJECT_CAPABILITY', 'production-object-capability', 'production-object-store', 'object-capability-receipt-v1'),
  externalCheck('11.platform-signing', 'BLOCKED_PLATFORM_SIGNING', 'platform-package-signing', 'platform-signing-authority', 'platform-signing-receipt-v1'),
];
const PROVIDER_CHECK = externalCheck(
  '12.provider-adapter',
  'BLOCKED_BY_PROVIDER_ADAPTER',
  'provider-read-observation',
  'gitlab-provider-authority',
  'provider-capability-receipt-v1',
);

function externalCheck(checkId, reasonCode, capability, authority, evidenceType) {
  return { checkId, reasonCode, capability, authority, evidenceType };
}

export async function collectEnvironmentEvidence({
  codeVerdictPath,
  receiptsDirectory,
  trustStorePath,
  outputPath,
  now = new Date(),
}) {
  const startedAt = now.toISOString();
  const code = JSON.parse(await readFile(resolve(codeVerdictPath), 'utf8'));
  await validateVerdictDocument(code);
  if (code.kind !== 'CODE') throw safeError('CODE_VERDICT_REQUIRED');
  const trustStore = trustStorePath ? await loadTrustStore(trustStorePath) : null;
  const specs = [...EXTERNAL_CHECKS];
  if (code.checks.some((check) => check.check_id === '08.foundation-demo' && check.status === 'PASS')) {
    specs.push(PROVIDER_CHECK);
  }

  const checks = [];
  for (const spec of specs) {
    checks.push(await collectOneReceipt({
      spec,
      code,
      receiptsDirectory,
      trustStore,
      now,
    }));
  }
  checks.sort((left, right) => left.check_id < right.check_id ? -1 : left.check_id > right.check_id ? 1 : 0);
  const status = checks.some((check) => check.status === 'FAIL')
    ? 'FAIL'
    : checks.some((check) => check.status === 'BLOCKED')
      ? 'BLOCKED'
      : 'PASS';
  const verdict = {
    schema_version: '1.0.0',
    evidence_policy_version: code.evidence_policy_version,
    kind: 'ENVIRONMENT',
    status,
    remote_url: code.remote_url,
    remote_ref: code.remote_ref,
    remote_sha: code.remote_sha,
    tree_sha: code.tree_sha,
    image_lock_digest: code.image_lock_digest,
    release_manifest_digest: code.release_manifest_digest,
    dependency_lock_digests: [],
    toolchain_results: [],
    checks,
    demo_evidence: [],
    started_at: startedAt,
    ended_at: now.toISOString(),
    evidence_bundle_digest: '0'.repeat(64),
  };
  verdict.evidence_bundle_digest = sha256(canonicalJson({
    ...verdict,
    evidence_bundle_digest: undefined,
  }));
  await validateVerdictDocument(verdict);
  await atomicWriteJson(outputPath, verdict);
  return verdict;
}

async function collectOneReceipt({ spec, code, receiptsDirectory, trustStore, now }) {
  const receiptPath = resolve(receiptsDirectory, `${spec.checkId}.receipt.json`);
  let bytes;
  try {
    const info = await lstat(receiptPath);
    if (!info.isFile() || info.isSymbolicLink() || info.size > 1024 * 1024) {
      return failedCheck(spec, now, sha256(canonicalJson({ state: 'receipt-file-invalid' })));
    }
    bytes = await readFile(receiptPath);
  } catch (error) {
    if (error?.code !== 'ENOENT') {
      return failedCheck(spec, now, sha256(canonicalJson({ state: 'receipt-read-failed' })));
    }
    return blockedCheck(spec, code, now, []);
  }
  const receiptDigest = sha256(bytes);
  if (trustStore === null) return blockedCheck(spec, code, now, [receiptDigest]);
  try {
    const receipt = JSON.parse(bytes.toString('utf8'));
    verifyReceipt({ receipt, receiptDigest, spec, code, trustStore, now });
    return passedCheck(spec, now, receiptDigest);
  } catch {
    return failedCheck(spec, now, receiptDigest);
  }
}

export function verifyReceipt({ receipt, spec, code, trustStore, now = new Date() }) {
  assertExactKeys(receipt, [
    'schema_version',
    'check_id',
    'remote_sha',
    'tree_sha',
    'image_lock_digest',
    'release_manifest_digest',
    'evidence_policy_version',
    'capability',
    'authority',
    'evidence_type',
    'issued_at',
    'expires_at',
    'evidence_digest',
    'signing',
  ]);
  assertExactKeys(receipt.signing, ['algorithm', 'key_id', 'signature']);
  assertNoSensitiveMaterial(receipt);
  if (
    receipt.schema_version !== '1.0.0'
    || receipt.check_id !== spec.checkId
    || receipt.remote_sha !== code.remote_sha
    || receipt.tree_sha !== code.tree_sha
    || receipt.image_lock_digest !== code.image_lock_digest
    || receipt.release_manifest_digest !== code.release_manifest_digest
    || receipt.evidence_policy_version !== code.evidence_policy_version
    || receipt.capability !== spec.capability
    || receipt.authority !== spec.authority
    || receipt.evidence_type !== spec.evidenceType
    || !/^[a-f0-9]{64}$/u.test(receipt.evidence_digest)
    || receipt.signing.algorithm !== 'Ed25519'
    || !/^[a-z0-9][a-z0-9_.-]{1,127}$/u.test(receipt.signing.key_id)
    || !/^[A-Za-z0-9+/]+={0,2}$/u.test(receipt.signing.signature)
  ) {
    throw safeError('EXTERNAL_RECEIPT_BINDING_INVALID');
  }
  const issuedAt = Date.parse(receipt.issued_at);
  const expiresAt = Date.parse(receipt.expires_at);
  const observedAt = now.getTime();
  if (
    !Number.isFinite(issuedAt)
    || !Number.isFinite(expiresAt)
    || issuedAt > observedAt
    || expiresAt < observedAt
    || expiresAt <= issuedAt
    || expiresAt - issuedAt > MAX_RECEIPT_AGE_MS
  ) {
    throw safeError('EXTERNAL_RECEIPT_TIME_INVALID');
  }
  const trustedKey = trustStore.keys.find((entry) => (
    entry.authority === receipt.authority
    && entry.key_id === receipt.signing.key_id
    && entry.algorithm === 'Ed25519'
  ));
  if (!trustedKey) throw safeError('EXTERNAL_RECEIPT_SIGNER_UNTRUSTED');
  const unsigned = structuredClone(receipt);
  delete unsigned.signing.signature;
  const valid = verifySignature(
    null,
    Buffer.from(canonicalJson(unsigned), 'utf8'),
    trustedKey.public_key_pem,
    Buffer.from(receipt.signing.signature, 'base64'),
  );
  if (!valid) throw safeError('EXTERNAL_RECEIPT_SIGNATURE_INVALID');
  return true;
}

async function loadTrustStore(path) {
  const resolved = resolve(path);
  const info = await lstat(resolved);
  if (!info.isFile() || info.isSymbolicLink() || info.size > 1024 * 1024) {
    throw safeError('TRUST_STORE_INVALID');
  }
  const store = JSON.parse(await readFile(resolved, 'utf8'));
  assertExactKeys(store, ['schema_version', 'keys']);
  if (store.schema_version !== '1.0.0' || !Array.isArray(store.keys) || store.keys.length === 0) {
    throw safeError('TRUST_STORE_INVALID');
  }
  for (const key of store.keys) {
    assertExactKeys(key, ['authority', 'key_id', 'algorithm', 'public_key_pem']);
    if (
      typeof key.authority !== 'string'
      || typeof key.key_id !== 'string'
      || key.algorithm !== 'Ed25519'
      || typeof key.public_key_pem !== 'string'
    ) {
      throw safeError('TRUST_STORE_INVALID');
    }
  }
  return store;
}

function passedCheck(spec, now, receiptDigest) {
  return {
    check_id: spec.checkId,
    status: 'PASS',
    attempted_argv: ['verify-authoritative-receipt', spec.checkId],
    attempt_exit_code: 0,
    evidence_digests: [receiptDigest],
    occurred_at: now.toISOString(),
    owner: OWNER,
  };
}

function failedCheck(spec, now, receiptDigest) {
  return {
    check_id: spec.checkId,
    status: 'FAIL',
    reason_code: 'ASSERTION_FAILED',
    executed: true,
    attempted_argv: ['verify-authoritative-receipt', spec.checkId],
    attempt_exit_code: 1,
    evidence_digests: [receiptDigest],
    occurred_at: now.toISOString(),
    owner: OWNER,
  };
}

function blockedCheck(spec, code, now, evidence) {
  return {
    schema_version: '1.0.0',
    check_id: spec.checkId,
    status: 'BLOCKED',
    remote_sha: code.remote_sha,
    tree_sha: code.tree_sha,
    reason_code: spec.reasonCode,
    attempted_argv: ['verify-authoritative-receipt', spec.checkId],
    attempt_exit_code: null,
    attempt_evidence: evidence,
    rerun_argv: ['node', 'scripts/acceptance/collect-environment-evidence.mjs'],
    occurred_at: now.toISOString(),
    owner: OWNER,
    external: {
      capability: spec.capability,
      authority: spec.authority,
      required_evidence_type: spec.evidenceType,
    },
  };
}

function assertExactKeys(value, expected) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw safeError('EXTERNAL_RECEIPT_SHAPE_INVALID');
  }
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((entry, index) => entry !== wanted[index])) {
    throw safeError('EXTERNAL_RECEIPT_SHAPE_INVALID');
  }
}

function safeError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

function parseCli(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (value === undefined) throw safeError('ARGUMENTS_INVALID');
    const key = {
      '--code': 'codeVerdictPath',
      '--receipts': 'receiptsDirectory',
      '--trust-store': 'trustStorePath',
      '--output': 'outputPath',
    }[flag];
    if (!key) throw safeError('ARGUMENTS_INVALID');
    options[key] = value;
  }
  if (!options.codeVerdictPath || !options.receiptsDirectory || !options.outputPath) {
    throw safeError('ARGUMENTS_INVALID');
  }
  return options;
}

async function main() {
  try {
    const options = parseCli(process.argv.slice(2));
    const verdict = await collectEnvironmentEvidence(options);
    process.stdout.write(`environment-verdict: ${verdict.status}\n`);
    process.exitCode = verdictExitCode(verdict.status);
  } catch (error) {
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`environment-verdict: FAIL (${code})\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
