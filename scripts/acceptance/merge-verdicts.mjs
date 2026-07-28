import { createHash, randomUUID } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const REPOSITORY_ROOT = resolve(SCRIPT_DIRECTORY, '..', '..');
const CONTRACT_DIRECTORY = resolve(REPOSITORY_ROOT, 'contracts', 'acceptance');

const FORBIDDEN_KEYS = new Set([
  'stdout',
  'stderr',
  'token',
  'cookie',
  'pem',
  'private_key',
  'privatekey',
  'raw_body',
  'raw_webhook_body',
  'source',
  'diff',
  'exception',
  'stack',
]);

const FORBIDDEN_TEXT = [
  /-----BEGIN(?: [A-Z0-9]+)+-----/u,
  /\bAuthorization\s*:\s*(?:Bearer|Basic)\s+\S+/iu,
  /\b(?:access_token|refresh_token|client_secret|password)=\S+/iu,
  /(?:^|\s)--?(?:authorization|cookie|password|secret|token)(?:=|\s|$)/iu,
  /\b(?:glpat-|ghp_|whsec_)[A-Za-z0-9_-]{8,}/u,
  /(?:https?|ssh):\/\/[^\s/?#]+:[^\s/@]+@/iu,
];

export const CODE_TOOLCHAIN_NAMES = Object.freeze([
  'buf',
  'cosign',
  'conftest',
  'docker',
  'docker-buildx',
  'docker-compose',
  'git',
  'gradle-wrapper',
  'helm',
  'java',
  'kubeconform',
  'node',
  'oras',
  'pnpm',
  'python',
  'syft',
  'tofu',
  'trivy',
  'uv',
].sort(compareBytes));

export const CODE_CHECK_IDS = Object.freeze([
  '00.dependency-locks',
  '00.image-lock',
  '00.release-manifest',
  '01.architecture',
  ...CODE_TOOLCHAIN_NAMES.map((name) => `02.toolchain.${name}`),
  '03.dependency-verification',
  '04.image-and-temporal',
  '04.temporal-mtls-contract',
  '05.telemetry',
  '06.deployment',
  '07.release-chain',
  '08.foundation-demo',
  '09.final-clean-state',
].sort(compareBytes));

export const ENVIRONMENT_CHECK_IDS = Object.freeze([
  '01.branch-protection',
  '02.mirror',
  '03.oidc',
  '04.registry',
  '05.kubernetes',
  '06.argocd',
  '07.external-secret',
  '08.pki',
  '09.database-recovery',
  '10.object-capability',
  '11.platform-signing',
  '12.provider-adapter',
]);

let validatorPromise;

export function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

export function canonicalJson(value) {
  return `${JSON.stringify(sortValue(value))}\n`;
}

function sortValue(value) {
  if (Array.isArray(value)) {
    return value.map(sortValue);
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort(compareBytes)
        .map((key) => [key, sortValue(value[key])]),
    );
  }
  return value;
}

function compareBytes(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

export function assertNoSensitiveMaterial(value, location = '$') {
  if (Array.isArray(value)) {
    value.forEach((entry, index) => assertNoSensitiveMaterial(entry, `${location}[${index}]`));
    return;
  }
  if (value !== null && typeof value === 'object') {
    for (const [key, entry] of Object.entries(value)) {
      const normalized = key.toLowerCase().replaceAll('-', '_');
      if (FORBIDDEN_KEYS.has(normalized)) {
        throw contractError('SENSITIVE_FIELD_FORBIDDEN', location);
      }
      assertNoSensitiveMaterial(entry, `${location}.${key}`);
    }
    return;
  }
  if (typeof value === 'string' && FORBIDDEN_TEXT.some((pattern) => pattern.test(value))) {
    throw contractError('SENSITIVE_VALUE_FORBIDDEN', location);
  }
}

export async function loadContractValidators() {
  validatorPromise ??= (async () => {
    const [blockedSchema, verdictSchema, summarySchema, bootstrapSchema] = await Promise.all([
      readJson(resolve(CONTRACT_DIRECTORY, 'blocked-evidence.schema.json')),
      readJson(resolve(CONTRACT_DIRECTORY, 'foundation-verdict.schema.json')),
      readJson(resolve(CONTRACT_DIRECTORY, 'foundation-summary.schema.json')),
      readJson(resolve(CONTRACT_DIRECTORY, 'foundation-bootstrap-result.schema.json')),
    ]);
    const ajv = new Ajv2020({ allErrors: true, strict: true, validateFormats: true });
    addFormats(ajv);
    ajv.addSchema(blockedSchema);
    const verdict = ajv.compile(verdictSchema);
    return {
      blocked: ajv.getSchema(blockedSchema.$id),
      verdict,
      passCheck: ajv.compile({ $ref: `${verdictSchema.$id}#/$defs/passCheck` }),
      failCheck: ajv.compile({ $ref: `${verdictSchema.$id}#/$defs/failCheck` }),
      summary: ajv.compile(summarySchema),
      bootstrap: ajv.compile(bootstrapSchema),
    };
  })();
  return validatorPromise;
}

export async function validateBlockedEvidence(document) {
  assertNoSensitiveMaterial(document);
  const { blocked } = await loadContractValidators();
  if (!blocked(document)) {
    throw contractError('BLOCKED_EVIDENCE_SCHEMA_INVALID');
  }
  return document;
}

export async function validateBootstrapResult(document) {
  assertNoSensitiveMaterial(document);
  const { bootstrap } = await loadContractValidators();
  if (!bootstrap(document)) {
    throw contractError('FOUNDATION_BOOTSTRAP_RESULT_SCHEMA_INVALID');
  }
  return document;
}

export async function validateCheckEvidence(document) {
  assertNoSensitiveMaterial(document);
  const validators = await loadContractValidators();
  const validate = document?.status === 'PASS'
    ? validators.passCheck
    : document?.status === 'FAIL'
      ? validators.failCheck
      : document?.status === 'BLOCKED'
        ? validators.blocked
        : null;
  if (validate === null || !validate(document)) {
    throw contractError('FOUNDATION_CHECK_SCHEMA_INVALID');
  }
  return document;
}

export async function validateVerdictDocument(document) {
  assertNoSensitiveMaterial(document);
  const { verdict } = await loadContractValidators();
  if (!verdict(document)) {
    throw contractError('FOUNDATION_VERDICT_SCHEMA_INVALID');
  }
  assertSortedUnique(document.checks, (entry) => entry.check_id, 'CHECK_ORDER_INVALID');
  assertSortedUnique(document.dependency_lock_digests, (entry) => entry.path, 'LOCK_ORDER_INVALID');
  assertSortedUnique(document.toolchain_results, (entry) => entry.name, 'TOOLCHAIN_ORDER_INVALID');
  if (Date.parse(document.started_at) > Date.parse(document.ended_at)) {
    throw contractError('VERDICT_TIME_RANGE_INVALID');
  }
  for (const check of document.checks) {
    if (check.status === 'BLOCKED') {
      if (check.remote_sha !== document.remote_sha || check.tree_sha !== document.tree_sha) {
        throw contractError('BLOCKED_BINDING_MISMATCH');
      }
      await validateBlockedEvidence(check);
    }
  }
  assertArtifactDigestBinding(document, 'image_lock_digest', '00.image-lock');
  assertArtifactDigestBinding(document, 'release_manifest_digest', '00.release-manifest');
  validateVerdictInventory(document);
  return document;
}

function assertArtifactDigestBinding(document, field, checkId) {
  if (document[field] !== null || document.status === 'BLOCKED') return;
  const artifactCheck = document.checks.find((entry) => entry.check_id === checkId);
  if (!artifactCheck || artifactCheck.status === 'PASS') {
    throw contractError('VERDICT_ARTIFACT_DIGEST_MISSING_WITHOUT_FAILURE');
  }
}

function validateVerdictInventory(document) {
  if (document.kind === 'CODE') {
    const checkIds = document.checks.map((entry) => entry.check_id);
    const toolNames = document.toolchain_results.map((entry) => entry.name);
    if (document.status === 'PASS') {
      assertExactInventory(checkIds, CODE_CHECK_IDS, 'CODE_CHECK_INVENTORY_INVALID');
      assertExactInventory(toolNames, CODE_TOOLCHAIN_NAMES, 'CODE_TOOLCHAIN_INVENTORY_INVALID');
    } else {
      assertInventorySubset(checkIds, CODE_CHECK_IDS, 'CODE_CHECK_INVENTORY_INVALID');
      assertInventorySubset(toolNames, CODE_TOOLCHAIN_NAMES, 'CODE_TOOLCHAIN_INVENTORY_INVALID');
    }
    return;
  }
  if (document.dependency_lock_digests.length !== 0
      || document.toolchain_results.length !== 0
      || document.demo_evidence.length !== 0) {
    throw contractError('ENVIRONMENT_BOUNDARY_INVENTORY_INVALID');
  }
  const actual = document.checks.map((entry) => entry.check_id);
  if (document.status === 'PASS' && !sameInventory(actual, ENVIRONMENT_CHECK_IDS)) {
    throw contractError('ENVIRONMENT_CHECK_INVENTORY_INVALID');
  }
  if (document.status !== 'PASS') {
    assertInventorySubset(actual, ENVIRONMENT_CHECK_IDS, 'ENVIRONMENT_CHECK_INVENTORY_INVALID');
  }
}

export function evidenceBundleDigestOf(document) {
  const content = { ...document };
  delete content.evidence_bundle_digest;
  return sha256(canonicalJson(content));
}

export function assertEvidenceBundleDigest(document) {
  if (document.evidence_bundle_digest !== evidenceBundleDigestOf(document)) {
    throw contractError('VERDICT_EVIDENCE_BUNDLE_DIGEST_MISMATCH');
  }
}

export async function validateSummaryDocument(document) {
  assertNoSensitiveMaterial(document);
  const { summary } = await loadContractValidators();
  if (!summary(document)) throw contractError('FOUNDATION_SUMMARY_SCHEMA_INVALID');
  return document;
}

export async function verifySummaryAgainstVerdicts({
  summary,
  code,
  environment,
  codeBytes,
  environmentBytes,
}) {
  await Promise.all([
    validateSummaryDocument(summary),
    validateVerdictDocument(code),
    validateVerdictDocument(environment),
  ]);
  assertEvidenceBundleDigest(code);
  assertEvidenceBundleDigest(environment);
  if (code.kind !== 'CODE' || environment.kind !== 'ENVIRONMENT') {
    throw contractError('VERDICT_KIND_INVALID');
  }
  const codeBinding = bindingOf(code);
  const environmentBinding = bindingOf(environment);
  const bindingEqual = canonicalJson(codeBinding) === canonicalJson(environmentBinding);
  const status = code.status === 'FAIL' || environment.status === 'FAIL'
    ? 'FAIL'
    : code.status === 'PASS' && environment.status === 'PASS' && bindingEqual
      ? 'PASS'
      : 'BLOCKED';
  if (summary.status !== status
      || summary.binding_equal !== bindingEqual
      || summary.code_verdict_digest !== sha256(codeBytes)
      || summary.environment_verdict_digest !== sha256(environmentBytes)
      || canonicalJson(summary.code_binding) !== canonicalJson(codeBinding)
      || canonicalJson(summary.environment_binding) !== canonicalJson(environmentBinding)) {
    throw contractError('FOUNDATION_SUMMARY_BINDING_INVALID');
  }
  return summary;
}

export function bindingOf(verdict) {
  return {
    remote_url: verdict.remote_url,
    remote_ref: verdict.remote_ref,
    remote_sha: verdict.remote_sha,
    tree_sha: verdict.tree_sha,
    image_lock_digest: verdict.image_lock_digest,
    release_manifest_digest: verdict.release_manifest_digest,
    evidence_policy_version: verdict.evidence_policy_version,
  };
}

export function verdictExitCode(status) {
  return status === 'PASS' ? 0 : status === 'FAIL' ? 1 : 2;
}

export async function mergeVerdicts({ codePath, environmentPath, outputPath, now = new Date() }) {
  const [codeBytes, environmentBytes] = await Promise.all([
    readFile(resolve(codePath)),
    readFile(resolve(environmentPath)),
  ]);
  const code = parseJson(codeBytes, 'CODE_VERDICT_JSON_INVALID');
  const environment = parseJson(environmentBytes, 'ENVIRONMENT_VERDICT_JSON_INVALID');
  const codeBinding = bindingOf(code);
  const environmentBinding = bindingOf(environment);
  const bindingEqual = canonicalJson(codeBinding) === canonicalJson(environmentBinding);
  const status = code.status === 'FAIL' || environment.status === 'FAIL'
    ? 'FAIL'
    : code.status === 'PASS' && environment.status === 'PASS' && bindingEqual
      ? 'PASS'
      : 'BLOCKED';
  const summary = {
    schema_version: '1.0.0',
    status,
    binding_equal: bindingEqual,
    code_verdict_digest: sha256(codeBytes),
    environment_verdict_digest: sha256(environmentBytes),
    code_binding: codeBinding,
    environment_binding: environmentBinding,
    created_at: now.toISOString(),
  };
  await verifySummaryAgainstVerdicts({
    summary,
    code,
    environment,
    codeBytes,
    environmentBytes,
  });
  await atomicWriteJson(outputPath, summary);
  return summary;
}

export async function atomicWriteJson(outputPath, document) {
  assertNoSensitiveMaterial(document);
  const target = resolve(outputPath);
  const parent = dirname(target);
  await mkdir(parent, { recursive: true });
  const temporary = resolve(parent, `.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(canonicalJson(document), 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, target);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
}

function assertSortedUnique(values, selector, code) {
  let previous;
  for (const value of values) {
    const current = selector(value);
    if (previous !== undefined && compareBytes(previous, current) >= 0) {
      throw contractError(code);
    }
    previous = current;
  }
}

function assertExactInventory(actual, expected, code) {
  if (!sameInventory(actual, expected)) throw contractError(code);
}

function assertInventorySubset(actual, expected, code) {
  const allowed = new Set(expected);
  if (actual.some((entry) => !allowed.has(entry))) throw contractError(code);
}

function sameInventory(actual, expected) {
  return actual.length === expected.length
    && actual.every((entry, index) => entry === expected[index]);
}

async function readJson(path) {
  return parseJson(await readFile(path), 'CONTRACT_JSON_INVALID');
}

function parseJson(bytes, code) {
  try {
    return JSON.parse(bytes.toString('utf8'));
  } catch {
    throw contractError(code);
  }
}

function contractError(code, location) {
  const error = new Error(code);
  error.code = code;
  if (location) error.location = location;
  return error;
}

function parseCli(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (!['--code', '--environment', '--output'].includes(flag) || value === undefined) {
      throw contractError('ARGUMENTS_INVALID');
    }
    options[flag.slice(2)] = value;
  }
  if (!options.code || !options.environment || !options.output) {
    throw contractError('ARGUMENTS_INVALID');
  }
  return options;
}

async function main() {
  try {
    const options = parseCli(process.argv.slice(2));
    const summary = await mergeVerdicts({
      codePath: options.code,
      environmentPath: options.environment,
      outputPath: options.output,
    });
    process.stdout.write(`foundation-summary: ${summary.status}\n`);
    process.exitCode = verdictExitCode(summary.status);
  } catch (error) {
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`foundation-summary: FAIL (${code})\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
