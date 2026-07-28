import { constants as fsConstants } from 'node:fs';
import { verify as verifySignature } from 'node:crypto';
import { access, lstat, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

import {
  REPOSITORY_ROOT,
  canonicalJson,
  exitCodeForStatus,
  sha256,
  writeCheckResult,
} from '../verification/check-result.mjs';
import {
  ProductionRenderBlockedError,
  renderProductionValues,
} from './render-values.mjs';

const REQUIRED_TOOLS = Object.freeze(['helm', 'tofu', 'conftest', 'kubeconform']);
const VERSION_ARGUMENTS = Object.freeze({
  helm: ['version', '--short'],
  tofu: ['version'],
  conftest: ['--version'],
  kubeconform: ['-v'],
});
const MAX_OUTPUT = 1024 * 1024;
const MAX_RECEIPT_BYTES = 1024 * 1024;
const MAX_RECEIPT_AGE_MS = 24 * 60 * 60 * 1000;
const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000;
const TLS_RECEIPT_KINDS = Object.freeze([
  'POSTGRES_VERIFY_FULL',
  'TEMPORAL_MTLS',
  'OTLP_TLS',
]);
const PRODUCTION_TLS_TRUST_STORE = path.join(
  REPOSITORY_ROOT,
  'contracts',
  'deployment',
  'production-trust-store.json',
);

let tlsValidatorPromise;

async function loadTlsValidators() {
  tlsValidatorPromise ??= (async () => {
    const [receiptSchema, trustStoreSchema] = await Promise.all([
      readFile(path.join(REPOSITORY_ROOT, 'contracts', 'deployment', 'production-tls-receipt.schema.json'), 'utf8'),
      readFile(path.join(REPOSITORY_ROOT, 'contracts', 'deployment', 'tls-trust-store.schema.json'), 'utf8'),
    ]);
    const ajv = new Ajv2020({ allErrors: true, strict: true });
    addFormats(ajv);
    return {
      receipt: ajv.compile(JSON.parse(receiptSchema)),
      trustStore: ajv.compile(JSON.parse(trustStoreSchema)),
    };
  })();
  return tlsValidatorPromise;
}

export function tlsReceiptSigningPayload(receipt) {
  const unsigned = structuredClone(receipt);
  if (unsigned?.signing && typeof unsigned.signing === 'object') {
    delete unsigned.signing.signature;
  }
  return Buffer.from(canonicalJson(unsigned), 'utf8');
}

function compare(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function expectedTlsTargets(values, receiptKind) {
  const entries = Object.entries(values?.components ?? {}).sort(([left], [right]) => compare(left, right));
  if (receiptKind === 'POSTGRES_VERIFY_FULL') {
    return entries.map(([component, value]) => ({
      component,
      server_name: value?.tls?.postgres?.server_name,
      tls_mode: 'VERIFY_FULL',
      requires_client_certificate: false,
    }));
  }
  if (receiptKind === 'TEMPORAL_MTLS') {
    const worker = values?.components?.['control-worker'];
    return [{
      component: 'control-worker',
      server_name: worker?.tls?.temporal?.server_name,
      tls_mode: 'MTLS',
      requires_client_certificate: true,
    }];
  }
  if (receiptKind === 'OTLP_TLS') {
    return entries.map(([component, value]) => ({
      component,
      server_name: value?.tls?.otlp?.server_name,
      tls_mode: 'TLS',
      requires_client_certificate: false,
    }));
  }
  throw new TypeError('Unsupported production TLS receipt kind');
}

export async function verifyProductionTlsReceipt({
  receipt,
  receiptKind,
  values,
  trustStore,
  now = new Date(),
}) {
  const validate = await loadTlsValidators();
  if (!validate.receipt(receipt) || !validate.trustStore(trustStore)) {
    throw new TypeError('Production TLS evidence violates its closed schema');
  }
  if (values?.environment_class !== 'production'
      || receipt.receipt_kind !== receiptKind
      || receipt.environment_class !== values.environment_class
      || receipt.remote_sha !== values?.contract?.remote_sha
      || receipt.remote_tree_sha !== values?.contract?.remote_tree_sha
      || receipt.release_manifest_digest !== values?.contract?.release_manifest_digest
      || receipt.promotion_sha256 !== values?.contract?.promotion_sha256) {
    throw new TypeError('Production TLS receipt binding mismatch');
  }

  const issuedAt = Date.parse(receipt.issued_at);
  const expiresAt = Date.parse(receipt.expires_at);
  if (!Number.isFinite(issuedAt)
      || !Number.isFinite(expiresAt)
      || issuedAt > now.getTime() + MAX_CLOCK_SKEW_MS
      || expiresAt <= now.getTime()
      || expiresAt <= issuedAt
      || expiresAt - issuedAt > MAX_RECEIPT_AGE_MS) {
    throw new TypeError('Production TLS receipt is stale or has an invalid validity window');
  }

  const expected = expectedTlsTargets(values, receiptKind);
  const actual = [...receipt.targets].sort((left, right) => compare(left.component, right.component));
  if (actual.length !== expected.length
      || new Set(actual.map((target) => target.component)).size !== actual.length) {
    throw new TypeError('Production TLS receipt target inventory mismatch');
  }
  for (let index = 0; index < expected.length; index += 1) {
    const required = expected[index];
    const observed = actual[index];
    if (!required.server_name
        || observed.component !== required.component
        || observed.server_name !== required.server_name
        || observed.tls_mode !== required.tls_mode
        || (required.requires_client_certificate
          ? observed.client_certificate_sha256 === null
          : observed.client_certificate_sha256 !== null)) {
      throw new TypeError('Production TLS receipt target binding mismatch');
    }
  }

  const keys = trustStore.keys.filter((candidate) => (
    candidate.authority === receipt.authority
    && candidate.key_id === receipt.signing.key_id
    && candidate.algorithm === receipt.signing.algorithm
  ));
  if (keys.length !== 1 || !verifySignature(
    null,
    tlsReceiptSigningPayload(receipt),
    keys[0].public_key_pem,
    Buffer.from(receipt.signing.signature, 'base64'),
  )) {
    throw new TypeError('Production TLS receipt signature is invalid');
  }
  return sha256(Buffer.from(canonicalJson(receipt), 'utf8'));
}

export function assertAuthoritativeProductionValues(valuesBytes, authoritativeBytes) {
  if (!Buffer.isBuffer(valuesBytes)
      || !Buffer.isBuffer(authoritativeBytes)
      || !valuesBytes.equals(authoritativeBytes)) {
    throw new TypeError('Deployment values differ from the authoritative production rendering');
  }
}

async function readEvidenceFile(candidate) {
  const target = path.resolve(REPOSITORY_ROOT, candidate);
  const info = await lstat(target);
  if (!info.isFile() || info.isSymbolicLink() || info.size > MAX_RECEIPT_BYTES) {
    throw new TypeError('Production evidence file is invalid');
  }
  return readFile(target);
}

export async function resolveExecutable(name) {
  const extensions = process.platform === 'win32'
    ? (process.env.PATHEXT ?? '.COM;.EXE;.BAT;.CMD').split(';')
    : [''];
  for (const directory of (process.env.PATH ?? '').split(path.delimiter)) {
    if (!directory) continue;
    for (const extension of extensions) {
      const candidate = path.join(directory, `${name}${extension}`);
      try {
        await access(candidate, fsConstants.X_OK);
        return candidate;
      } catch {
        // Continue the read-only PATH search.
      }
    }
  }
  throw Object.assign(new Error(`Executable not found: ${name}`), { code: 'ENOENT' });
}

function runTool(executable, args, options = {}) {
  return spawnSync(executable, args, {
    cwd: options.cwd ?? REPOSITORY_ROOT,
    encoding: 'utf8',
    shell: false,
    windowsHide: true,
    timeout: options.timeout ?? 120_000,
    maxBuffer: MAX_OUTPUT,
    input: options.input,
  });
}

export async function preflightDeploymentToolchain({
  resolveExecutableImpl = resolveExecutable,
  runToolImpl = runTool,
} = {}) {
  const executables = {};
  const missing = [];
  for (const name of REQUIRED_TOOLS) {
    try {
      executables[name] = await resolveExecutableImpl(name);
    } catch {
      missing.push(name);
    }
  }
  if (missing.length > 0) {
    return {
      status: 'BLOCKED',
      reason_code: 'BLOCKED_TOOLCHAIN',
      exit_code: 2,
      missing,
      executables,
      observations: REQUIRED_TOOLS.map((name) => ({
        tool_id: name,
        executable: name,
        argv: VERSION_ARGUMENTS[name],
        exit_code: missing.includes(name) ? -1 : 0,
        duration_ms: 0,
        observed_version: missing.includes(name) ? 'UNAVAILABLE' : 'PRESENT_NOT_EXECUTED',
        normalized_version: missing.includes(name) ? 'UNAVAILABLE' : 'PRESENT',
      })),
    };
  }

  const observations = [];
  let blocked = false;
  for (const name of REQUIRED_TOOLS) {
    const started = process.hrtime.bigint();
    const result = runToolImpl(executables[name], VERSION_ARGUMENTS[name], { timeout: 10_000 });
    const duration = Number((process.hrtime.bigint() - started) / 1_000_000n);
    const output = `${result.stdout ?? ''}${result.stderr ?? ''}`.trim();
    if (result.error || result.status !== 0) blocked = true;
    observations.push({
      tool_id: name,
      executable: name,
      argv: VERSION_ARGUMENTS[name],
      exit_code: result.status ?? -1,
      duration_ms: Math.min(duration, 300_000),
      observed_version: output.slice(0, 1024) || 'UNAVAILABLE',
      normalized_version: output.match(/\d+[.]\d+[.]\d+/u)?.[0] ?? 'UNPARSEABLE',
    });
  }
  return {
    status: blocked ? 'BLOCKED' : 'PASS',
    reason_code: blocked ? 'BLOCKED_TOOLCHAIN' : 'PASS',
    exit_code: blocked ? 2 : 0,
    missing: [],
    executables,
    observations,
  };
}

function parseCli(argv) {
  const options = { mode: 'test' };
  const allowed = new Map([
    ['--mode', 'mode'],
    ['--values', 'valuesPath'],
    ['--output', 'output'],
    ['--postgres-tls-receipt', 'postgresTlsReceipt'],
    ['--temporal-tls-receipt', 'temporalTlsReceipt'],
    ['--otlp-tls-receipt', 'otlpTlsReceipt'],
    ['--component-identity', 'componentIdentity'],
    ['--release-context', 'releaseContext'],
    ['--release-manifest', 'releaseManifest'],
    ['--release-evidence-index', 'releaseEvidenceIndex'],
    ['--release-evidence-root', 'releaseEvidenceRoot'],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    const name = allowed.get(option);
    if (!name) throw new TypeError(`Unknown option: ${option}`);
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) throw new TypeError(`Missing value for ${option}`);
    if (name !== 'mode' && options[name]) throw new TypeError(`Repeated option: ${option}`);
    options[name] = value;
    index += 1;
  }
  if (!['test', 'production'].includes(options.mode)) throw new TypeError('--mode must be test or production');
  options.valuesPath ??= path.join(REPOSITORY_ROOT, 'build', 'deployment', 'rendered-values.yaml');
  options.output ??= 'build/verification/ft15-deployment.json';
  return options;
}

function observation(executable, args, result, duration) {
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`.trim();
  return {
    tool_id: path.basename(executable).replace(/[.]exe$/iu, '').replace('opentofu', 'tofu'),
    executable: path.basename(executable),
    argv: args,
    exit_code: result.status ?? -1,
    duration_ms: Math.min(duration, 300_000),
    observed_version: output.slice(0, 1024) || 'NO_OUTPUT',
    normalized_version: result.status === 0 ? 'EXECUTED' : 'FAILED',
  };
}

async function writeResult({ startedAt, status, reasonCode, evidence, observations, output }) {
  const result = {
    schema_version: '1.0.0',
    check_id: 'ft15-deployment-structure',
    status,
    reason_code: reasonCode,
    started_at: startedAt,
    finished_at: new Date().toISOString(),
    evidence: [...new Set(evidence)],
    tool_observations: observations,
  };
  await writeCheckResult(result, output);
  process.stdout.write(`${canonicalJson(result)}\n`);
  return exitCodeForStatus(status);
}

async function verifyAuthoritativeProductionRendering(options, valuesBytes) {
  const required = [
    'componentIdentity',
    'releaseContext',
    'releaseManifest',
    'releaseEvidenceIndex',
    'releaseEvidenceRoot',
  ];
  if (required.some((name) => !options[name])) {
    throw new ProductionRenderBlockedError(
      'BLOCKED_EXTERNAL_ENVIRONMENT',
      'PRODUCTION_RELEASE_EVIDENCE_ABSENT',
    );
  }
  const identity = JSON.parse(await readFile(
    path.resolve(REPOSITORY_ROOT, options.componentIdentity),
    'utf8',
  ));
  const temporary = await mkdtemp(path.join(tmpdir(), 'accord-ft15-production-authority-'));
  const authoritativeValuesPath = path.join(temporary, 'rendered-values.yaml');
  try {
    await renderProductionValues({
      identity,
      outputPath: authoritativeValuesPath,
      releaseContextPath: options.releaseContext,
      releaseManifestPath: options.releaseManifest,
      releaseEvidenceIndexPath: options.releaseEvidenceIndex,
      releaseEvidenceRoot: options.releaseEvidenceRoot,
    });
    assertAuthoritativeProductionValues(
      valuesBytes,
      await readFile(authoritativeValuesPath),
    );
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
}

async function main(argv) {
  const options = parseCli(argv);
  const startedAt = new Date().toISOString();
  const toolVersions = await readFile(path.join(REPOSITORY_ROOT, '.tool-versions'));
  const evidence = [sha256(toolVersions)];
  const preflight = await preflightDeploymentToolchain();
  if (preflight.status === 'BLOCKED') {
    return writeResult({
      startedAt,
      status: 'BLOCKED',
      reasonCode: 'BLOCKED_TOOLCHAIN',
      evidence,
      observations: preflight.observations,
      output: options.output,
    });
  }

  let valuesBytes;
  try {
    valuesBytes = await readFile(path.resolve(REPOSITORY_ROOT, options.valuesPath));
  } catch {
    return writeResult({ startedAt, status: 'FAIL', reasonCode: 'ASSERTION_FAILED', evidence, observations: preflight.observations, output: options.output });
  }
  const values = YAML.parse(valuesBytes.toString('utf8'));
  evidence.push(sha256(valuesBytes));
  if (values?.environment_class !== options.mode) {
    return writeResult({ startedAt, status: 'FAIL', reasonCode: 'ASSERTION_FAILED', evidence, observations: preflight.observations, output: options.output });
  }

  if (options.mode === 'production') {
    try {
      await verifyAuthoritativeProductionRendering(options, valuesBytes);
    } catch (error) {
      if (error instanceof ProductionRenderBlockedError) {
        return writeResult({ startedAt, status: 'BLOCKED', reasonCode: error.reasonCode, evidence, observations: preflight.observations, output: options.output });
      }
      if (error?.code === 'ENOENT') {
        return writeResult({ startedAt, status: 'BLOCKED', reasonCode: 'BLOCKED_EXTERNAL_ENVIRONMENT', evidence, observations: preflight.observations, output: options.output });
      }
      return writeResult({ startedAt, status: 'FAIL', reasonCode: 'ASSERTION_FAILED', evidence, observations: preflight.observations, output: options.output });
    }
    const receipts = [
      ['POSTGRES_VERIFY_FULL', options.postgresTlsReceipt],
      ['TEMPORAL_MTLS', options.temporalTlsReceipt],
      ['OTLP_TLS', options.otlpTlsReceipt],
    ];
    if (receipts.some(([, receipt]) => !receipt)) {
      return writeResult({ startedAt, status: 'BLOCKED', reasonCode: 'BLOCKED_EXTERNAL_ENVIRONMENT', evidence, observations: preflight.observations, output: options.output });
    }
    try {
      const trustStoreBytes = await readEvidenceFile(PRODUCTION_TLS_TRUST_STORE);
      const trustStore = JSON.parse(trustStoreBytes.toString('utf8'));
      evidence.push(sha256(trustStoreBytes));
      for (const [receiptKind, receiptPath] of receipts) {
        const receiptBytes = await readEvidenceFile(receiptPath);
        const receipt = JSON.parse(receiptBytes.toString('utf8'));
        if (receiptBytes.toString('utf8') !== `${canonicalJson(receipt)}\n`) {
          throw new TypeError('Production TLS receipt is not canonical JSON');
        }
        evidence.push(sha256(receiptBytes));
        evidence.push(await verifyProductionTlsReceipt({
          receipt,
          receiptKind,
          values,
          trustStore,
          now: new Date(),
        }));
      }
    } catch (error) {
      if (error?.code === 'ENOENT') {
        return writeResult({ startedAt, status: 'BLOCKED', reasonCode: 'BLOCKED_EXTERNAL_ENVIRONMENT', evidence, observations: preflight.observations, output: options.output });
      }
      return writeResult({ startedAt, status: 'FAIL', reasonCode: 'ASSERTION_FAILED', evidence, observations: preflight.observations, output: options.output });
    }
  }

  const temporary = await mkdtemp(path.join(tmpdir(), 'accord-ft15-verify-'));
  const valuesSnapshot = path.join(temporary, 'rendered-values.yaml');
  const renderedManifest = path.join(temporary, 'accord.yaml');
  const observations = [...preflight.observations];
  const commands = [
    { tool: 'helm', args: ['lint', path.join(REPOSITORY_ROOT, 'infra', 'helm', 'accord'), '--values', valuesSnapshot, '--strict'] },
    { tool: 'helm', args: ['template', 'accord', path.join(REPOSITORY_ROOT, 'infra', 'helm', 'accord'), '--namespace', values.namespace, '--values', valuesSnapshot] },
  ];
  let failed = false;
  try {
    await writeFile(valuesSnapshot, valuesBytes, { flag: 'wx', mode: 0o400 });
    for (const command of commands) {
      const started = process.hrtime.bigint();
      const result = runTool(preflight.executables[command.tool], command.args);
      const duration = Number((process.hrtime.bigint() - started) / 1_000_000n);
      observations.push(observation(preflight.executables[command.tool], command.args, result, duration));
      if (result.error || result.status !== 0) {
        failed = true;
        break;
      }
      if (command.args[0] === 'template') await writeFile(renderedManifest, result.stdout, { encoding: 'utf8', mode: 0o600 });
    }
    if (!failed) {
      const remaining = [
        { tool: 'kubeconform', args: ['-strict', '-summary', '-ignore-missing-schemas', renderedManifest] },
        { tool: 'conftest', args: ['test', '--combine', '--namespace', 'accord', '--policy', path.join(REPOSITORY_ROOT, 'infra', 'policy'), renderedManifest] },
        { tool: 'tofu', args: [`-chdir=${path.join(REPOSITORY_ROOT, 'infra', 'opentofu', 'environments', 'local-kubernetes')}`, 'validate'] },
      ];
      for (const command of remaining) {
        const started = process.hrtime.bigint();
        const result = runTool(preflight.executables[command.tool], command.args);
        const duration = Number((process.hrtime.bigint() - started) / 1_000_000n);
        observations.push(observation(preflight.executables[command.tool], command.args, result, duration));
        if (result.error || result.status !== 0) {
          failed = true;
          break;
        }
      }
    }
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
  if (failed) return writeResult({ startedAt, status: 'FAIL', reasonCode: 'ASSERTION_FAILED', evidence, observations, output: options.output });

  try {
    const workerProbe = await readFile(path.join(REPOSITORY_ROOT, 'apps', 'control-plane', 'worker', 'build', 'probe', 'worker-probe.jar'));
    evidence.push(sha256(workerProbe));
  } catch {
    return writeResult({ startedAt, status: 'BLOCKED', reasonCode: 'BLOCKED_TOOLCHAIN', evidence, observations, output: options.output });
  }
  return writeResult({ startedAt, status: 'PASS', reasonCode: 'PASS', evidence, observations, output: options.output });
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`verify-deployment: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
