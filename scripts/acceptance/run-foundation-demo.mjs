import { randomBytes } from 'node:crypto';
import { access, lstat, mkdir, readFile, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { resolve } from 'node:path';
import { TextDecoder } from 'node:util';
import { pathToFileURL } from 'node:url';
import {
  atomicWriteJson,
  canonicalJson,
  sha256,
  validateCheckEvidence,
  verdictExitCode,
} from './merge-verdicts.mjs';
import { runNative } from './resolve-remote-sha.mjs';
import { verifySensitiveLogs } from '../../tests/integration/verify-sensitive-logs.mjs';

const OWNER = 'platform-engineering';
const COMMAND_TIMEOUT_MS = 30 * 60_000;
const DEMO_CHECK_ID = '08.foundation-demo';
const OBJECT_ID = /^[a-f0-9]{40,64}$/u;
const LOCAL_DEMO_TOOL = {
  name: 'foundation-demo-prerequisites',
  requiredVersion: 'repository-defined',
};
const DOCKER_TOOL = { name: 'docker', requiredVersion: 'repository-defined' };
const JAVA_TOOL = { name: 'java', requiredVersion: '21' };
const MAX_HTTP_RECEIPT_BYTES = 64 * 1024;
const MAX_COLLECTOR_BYTES = 32 * 1024 * 1024;
const WORKER_RECOVERY_SCOPE = 'com.inforvans.accord.foundation';
const HTTP_RECEIPT_FIELDS = new Set([
  'schema_version',
  'evidence_type',
  'webhook_edge',
  'control_api',
]);
const WEBHOOK_RECEIPT_FIELDS = new Set([
  'route',
  'verification_mode',
  'statuses',
  'inbox_rows',
  'outbox_rows',
]);
const API_RECEIPT_FIELDS = new Set([
  'route',
  'statuses',
  'response_bytes_equal',
  'response_headers_equal',
  'etag_equal',
  'aggregate_rows',
  'domain_event_rows',
  'outbox_rows',
]);
const DEMO_SECRET_NAMES = [
  'ACCORD_POSTGRES_SUPERUSER_PASSWORD',
  'ACCORD_TEMPORAL_SCHEMA_PASSWORD',
  'ACCORD_TEMPORAL_RUNTIME_PASSWORD',
  'ACCORD_MINIO_ROOT_PASSWORD',
  'ACCORD_MINIO_NORMAL_PASSWORD',
  'ACCORD_MINIO_SCANNER_PASSWORD',
  'ACCORD_FT17_WEBHOOK_HMAC_SECRET',
  'ACCORD_FT17_DEMO_CERTIFICATE',
];
const REQUIRED_BOUNDARIES = [
  ['event-transport', 'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/EventTransport.java'],
  ['fenced-observation', 'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/FencedReconciliationObservation.java'],
  ['foundation-product-loop', 'tests/integration/src/test/java/com/inforvans/accord/integration/FoundationProductLoopIT.java'],
  ['image-lock', 'infra/images/images.lock.json'],
  ['local-topology', 'infra/local/compose.yaml'],
  ['provider-observation-port', 'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/ProviderObservationPort.java'],
  ['read-only-activity', 'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/workflow/ReadOnlyReconciliationActivity.java'],
  ['temporal-mtls-integration', 'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalMtlsIT.java'],
];

export async function inspectDemoBoundaries(repository = process.cwd()) {
  const root = resolve(repository);
  const boundaries = [];
  for (const [boundaryId, relativePath] of REQUIRED_BOUNDARIES) {
    let present = false;
    try {
      await access(resolve(root, relativePath), constants.R_OK);
      const info = await lstat(resolve(root, relativePath));
      present = info.isFile() && !info.isSymbolicLink();
    } catch {
      present = false;
    }
    boundaries.push({ boundary_id: boundaryId, present });
  }
  return boundaries;
}

export async function runFoundationDemo({
  repository = process.cwd(),
  outputDirectory,
  remoteSha,
  treeSha,
  sentinels = [],
  run = runNative,
  now = new Date(),
}) {
  const root = resolve(repository);
  const output = resolve(outputDirectory);
  const logsDirectory = resolve(output, 'logs');
  const telemetryDirectory = resolve(output, 'telemetry');
  const attemptedArgv = [
    'java',
    '-classpath',
    'gradle/wrapper/gradle-wrapper.jar',
    'org.gradle.wrapper.GradleWrapperMain',
    ':tests:integration:test',
    '--tests',
    '*FoundationProductLoopIT',
    '--no-daemon',
    '--dependency-verification=strict',
  ];
  const boundaries = await inspectDemoBoundaries(root);
  const boundaryDigest = sha256(canonicalJson({ boundaries }));
  const missingBoundaries = boundaries.filter((entry) => !entry.present);
  if (missingBoundaries.length > 0) {
    return writeDemoEvidence(output, failDemoCheck({
      argv: attemptedArgv,
      exitCode: 1,
      evidence: [boundaryDigest],
      now,
    }));
  }

  await mkdir(logsDirectory, { recursive: true });
  await mkdir(telemetryDirectory, { recursive: true });
  const collectorBaseline = await captureCollectorBaseline(root);
  const childEnvironment = demoEnvironment(output);
  const attempts = [];
  let productEvidence = failedProductEvidence();
  let teardownRequired = false;
  const topologyCommands = [
    demoCommand(process.execPath, ['scripts/local-up.mjs'], ['node', 'scripts/local-up.mjs'], LOCAL_DEMO_TOOL),
    demoCommand(process.execPath, ['scripts/local-up.mjs', '--run-temporal-it'], ['node', 'scripts/local-up.mjs', '--run-temporal-it'], LOCAL_DEMO_TOOL),
    demoCommand(process.execPath, ['scripts/local-up.mjs', '--finalize'], ['node', 'scripts/local-up.mjs', '--finalize'], LOCAL_DEMO_TOOL),
  ];
  const productLoopCommand = demoCommand(
    attemptedArgv[0],
    attemptedArgv.slice(1),
    attemptedArgv,
    JAVA_TOOL,
  );
  const temporalLogsCommand = demoCommand(
    'docker',
    [
      'compose', '--env-file', 'infra/local/state/images.env',
      '-f', 'infra/local/compose.yaml', '-p', 'accord-foundation-local',
      'logs', '--no-color', '--timestamps',
      'temporal-schema', 'temporal-server', 'temporal-namespace',
    ],
    [
      'docker', 'compose', '--env-file', 'infra/local/state/images.env',
      '-f', 'infra/local/compose.yaml', '-p', 'accord-foundation-local',
      'logs', '--no-color', '--timestamps',
      'temporal-schema', 'temporal-server', 'temporal-namespace',
    ],
    DOCKER_TOOL,
  );

  try {
    for (const command of topologyCommands) {
      teardownRequired = true;
      const attempt = executeDemoCommand({ command, root, childEnvironment, run });
      attempts.push(attempt);
      if (attempt.problem !== null) break;
    }

    if (attempts.every((attempt) => attempt.problem === null)) {
      attempts.push(executeDemoCommand({
        command: productLoopCommand,
        root,
        childEnvironment,
        run,
      }));
    }
    if (attempts.every((attempt) => attempt.problem === null)) {
      productEvidence = await verifyFoundationProductEvidence({
        root,
        output,
        telemetryDirectory,
        collectorBaseline,
      });
    }
  } finally {
    if (teardownRequired) {
      attempts.push(executeDemoCommand({
        command: temporalLogsCommand,
        root,
        childEnvironment,
        run,
      }));
      attempts.push(executeDemoCommand({
        command: demoCommand(
          process.execPath,
          ['scripts/local-down.mjs', '--purge-local-state'],
          ['node', 'scripts/local-down.mjs', '--purge-local-state'],
          LOCAL_DEMO_TOOL,
        ),
        root,
        childEnvironment,
        run,
      }));
    }
  }

  const executionDigests = attempts.map((attempt) => attempt.digest);
  const problem = attempts.find((attempt) => attempt.problem?.outcome === 'FAIL')
    ?? attempts.find((attempt) => attempt.problem?.outcome === 'BLOCKED');
  if (problem?.problem.outcome === 'FAIL') {
    return writeDemoEvidence(output, failDemoCheck({
      argv: problem.command.argv,
      exitCode: problem.execution.exitCode,
      evidence: [boundaryDigest, ...executionDigests],
      now,
    }));
  }
  if (problem?.problem.outcome === 'BLOCKED') {
    return writeDemoEvidence(output, blockedDemoCheck({
      argv: problem.command.argv,
      execution: problem.execution,
      evidence: [boundaryDigest, ...executionDigests],
      remoteSha,
      treeSha,
      tool: problem.command.blockedTool,
      now,
    }));
  }

  if (productEvidence.outcome !== 'PASS') {
    return writeDemoEvidence(output, failDemoCheck({
      argv: attemptedArgv,
      exitCode: 1,
      evidence: [boundaryDigest, ...executionDigests, productEvidence.digest],
      now,
    }));
  }

  let sensitiveResult;
  try {
    sensitiveResult = await verifySensitiveLogs({
      services: [
        { service: 'foundation-demo', path: resolve(output, 'logs') },
        { service: 'foundation-telemetry', path: resolve(output, 'telemetry') },
        { service: 'foundation-http-proof', path: resolve(output, 'http') },
      ],
      texts: attempts.flatMap((attempt) => [
        { service: 'foundation-subprocess-stdout', text: attempt.execution.stdout },
        { service: 'foundation-subprocess-stderr', text: attempt.execution.stderr },
      ]),
      sentinels: [
        ...sentinels,
        ...DEMO_SECRET_NAMES.map((name) => childEnvironment[name]),
      ],
    });
  } catch {
    sensitiveResult = { outcome: 'FAIL', files_scanned: 0, matches: [] };
  }
  const sensitiveDigest = sha256(canonicalJson(sensitiveResult));
  const evidence = [
    boundaryDigest,
    ...executionDigests,
    productEvidence.digest,
    sensitiveDigest,
  ];
  return writeDemoEvidence(output, sensitiveResult.outcome === 'PASS'
    ? passDemoCheck({ argv: attemptedArgv, evidence, now })
    : failDemoCheck({ argv: attemptedArgv, exitCode: 1, evidence, now }));
}

async function verifyFoundationProductEvidence({
  root,
  output,
  telemetryDirectory,
  collectorBaseline,
}) {
  const summary = {
    schema_version: '1.0.0',
    test_authored_pass_records_absent: false,
    public_http_receipt_valid: false,
    collector_export_valid: false,
    collector_traces_valid: false,
    collector_metrics_valid: false,
    collector_logs_valid: false,
    collector_snapshot_written: false,
    http_receipt_digest: null,
    collector_export_digest: null,
  };
  try {
    await requireAbsent(resolve(output, 'logs', 'foundation-product-loop.json'));
    await requireAbsent(resolve(output, 'telemetry', 'foundation-product-loop.json'));
    summary.test_authored_pass_records_absent = true;

    const httpEvidence = await readJsonEvidence(
      resolve(output, 'http', 'foundation-http-receipt.json'),
      MAX_HTTP_RECEIPT_BYTES,
    );
    const httpReceipt = httpEvidence.value;
    validateHttpReceipt(httpReceipt);
    summary.public_http_receipt_valid = true;
    summary.http_receipt_digest = sha256(httpEvidence.bytes);

    if (!collectorBaseline.valid) throw safeError('COLLECTOR_BASELINE_INVALID');
    const completeCollectorBytes = await readRegularEvidenceFile(
      resolve(root, 'infra', 'local', 'state', 'otel', 'accord-telemetry.jsonl'),
      MAX_COLLECTOR_BYTES,
    );
    const collectorBytes = appendedCollectorBytes(
      collectorBaseline.bytes,
      completeCollectorBytes,
    );
    const signals = validateCollectorExport(collectorBytes);
    summary.collector_export_valid = true;
    summary.collector_export_digest = sha256(collectorBytes);
    summary.collector_traces_valid = signals.traces;
    summary.collector_metrics_valid = signals.metrics;
    summary.collector_logs_valid = signals.logs;
    await writeFile(
      resolve(telemetryDirectory, 'otel-collector.jsonl'),
      collectorBytes,
      { flag: 'wx', mode: 0o600 },
    );
    summary.collector_snapshot_written = true;
    return productEvidence('PASS', summary);
  } catch {
    return productEvidence('FAIL', summary);
  }
}

function failedProductEvidence() {
  return productEvidence('FAIL', {
    schema_version: '1.0.0',
    test_authored_pass_records_absent: false,
    public_http_receipt_valid: false,
    collector_export_valid: false,
    collector_traces_valid: false,
    collector_metrics_valid: false,
    collector_logs_valid: false,
    collector_snapshot_written: false,
    http_receipt_digest: null,
    collector_export_digest: null,
  });
}

function productEvidence(outcome, summary) {
  return {
    outcome,
    digest: sha256(canonicalJson(summary)),
  };
}

async function requireAbsent(path) {
  try {
    await access(path, constants.F_OK);
  } catch (error) {
    if (error?.code === 'ENOENT') return;
    throw error;
  }
  throw safeError('TEST_AUTHORED_PASS_EVIDENCE_FORBIDDEN');
}

async function readJsonEvidence(path, maximumBytes) {
  const bytes = await readRegularEvidenceFile(path, maximumBytes);
  const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  if (!bytes.equals(Buffer.from(canonicalJson(value), 'utf8'))) {
    throw safeError('DEMO_EVIDENCE_JSON_NOT_CANONICAL');
  }
  return { value, bytes };
}

async function readRegularEvidenceFile(path, maximumBytes) {
  const info = await lstat(path);
  if (!info.isFile() || info.isSymbolicLink() || info.size < 2 || info.size > maximumBytes) {
    throw safeError('DEMO_EVIDENCE_FILE_INVALID');
  }
  return readFile(path);
}

async function captureCollectorBaseline(root) {
  const path = resolve(root, 'infra', 'local', 'state', 'otel', 'accord-telemetry.jsonl');
  try {
    return {
      valid: true,
      bytes: await readRegularEvidenceFile(path, MAX_COLLECTOR_BYTES),
    };
  } catch (error) {
    if (error?.code === 'ENOENT') return { valid: true, bytes: Buffer.alloc(0) };
    return { valid: false, bytes: Buffer.alloc(0) };
  }
}

function appendedCollectorBytes(baseline, complete) {
  if (complete.length <= baseline.length
      || !complete.subarray(0, baseline.length).equals(baseline)) {
    throw safeError('COLLECTOR_EXPORT_NOT_CURRENT');
  }
  return complete.subarray(baseline.length);
}

function validateHttpReceipt(receipt) {
  requireClosedObject(receipt, HTTP_RECEIPT_FIELDS);
  if (receipt.schema_version !== '1.0.0'
      || receipt.evidence_type !== 'foundation-public-http-v1') {
    throw safeError('PUBLIC_HTTP_RECEIPT_INVALID');
  }
  requireClosedObject(receipt.webhook_edge, WEBHOOK_RECEIPT_FIELDS);
  requireClosedObject(receipt.control_api, API_RECEIPT_FIELDS);
  const webhook = receipt.webhook_edge;
  if (webhook.route !== '/webhooks/gitlab/{bindingId}'
      || webhook.verification_mode !== 'STANDARD_REQUIRED'
      || !sameIntegerArray(webhook.statuses, [202, 202, 409])
      || webhook.inbox_rows !== 1
      || webhook.outbox_rows !== 1) {
    throw safeError('PUBLIC_HTTP_WEBHOOK_PROOF_INVALID');
  }
  const api = receipt.control_api;
  if (api.route !== '/v1/contract-validations/{validationId}'
      || !sameIntegerArray(api.statuses, [201, 201])
      || api.response_bytes_equal !== true
      || api.response_headers_equal !== true
      || api.etag_equal !== true
      || api.aggregate_rows !== 1
      || api.domain_event_rows !== 1
      || api.outbox_rows !== 1) {
    throw safeError('PUBLIC_HTTP_API_PROOF_INVALID');
  }
}

export function validateCollectorExport(bytes) {
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  const lines = text.split(/\r?\n/u).filter((line) => line.length > 0);
  if (lines.length === 0 || lines.length > 100_000) {
    throw safeError('COLLECTOR_EXPORT_INVALID');
  }
  const requirements = new Map([
    ['accord-webhook-edge', new Map([
      ['/webhooks/gitlab/{bindingId}', new Map([[202, 2], [409, 1]])],
    ])],
    ['accord-control-api', new Map([
      ['/v1/contract-validations/{validationId}', new Map([[201, 2]])],
    ])],
    ['accord-control-worker', new Map()],
  ]);
  const observed = new Map();
  const signalServices = {
    traces: new Set(),
    metrics: new Set(),
    logs: new Set(),
  };
  const workerRecovery = { traces: false, metrics: false, logs: false };
  for (const line of lines) {
    if (Buffer.byteLength(line, 'utf8') > 4 * 1024 * 1024) {
      throw safeError('COLLECTOR_EXPORT_INVALID');
    }
    const payload = JSON.parse(line);
    inspectOtelResources(payload.resourceSpans, 'scopeSpans', 'spans', 'traces',
      signalServices, observed, workerRecovery);
    inspectOtelResources(payload.resourceMetrics, 'scopeMetrics', 'metrics', 'metrics',
      signalServices, observed, workerRecovery);
    inspectOtelResources(payload.resourceLogs, 'scopeLogs', 'logRecords', 'logs',
      signalServices, observed, workerRecovery);
  }
  const services = [...requirements.keys()];
  for (const service of services) {
    for (const signal of Object.values(signalServices)) {
      if (!signal.has(service)) throw safeError('COLLECTOR_SIGNAL_MISSING');
    }
    for (const [route, statuses] of requirements.get(service)) {
      for (const [status, count] of statuses) {
        const key = `${service}\u0000${route}\u0000${status}`;
        if ((observed.get(key) ?? 0) < count) {
          throw safeError('COLLECTOR_HTTP_SPAN_MISSING');
        }
      }
    }
  }
  if (!Object.values(workerRecovery).every(Boolean)) {
    throw safeError('COLLECTOR_WORKER_RECOVERY_MISSING');
  }
  return { traces: true, metrics: true, logs: true };
}

function inspectOtelResources(
  resources,
  scopeField,
  recordsField,
  signal,
  signalServices,
  observed,
  workerRecovery,
) {
  if (!Array.isArray(resources)) return;
  for (const resourceEntry of resources) {
    const service = otelAttributes(resourceEntry?.resource?.attributes).get('service.name');
    if (typeof service !== 'string') continue;
    if (!Array.isArray(resourceEntry[scopeField])) continue;
    let hasRecord = false;
    for (const scope of resourceEntry[scopeField]) {
      if (!Array.isArray(scope?.[recordsField])) continue;
      const guardedWorkerScope = scope?.scope?.name === WORKER_RECOVERY_SCOPE;
      for (const record of scope[recordsField]) {
        hasRecord = true;
        if (service === 'accord-control-worker' && guardedWorkerScope) {
          if (signal === 'traces'
              && record?.name === 'provider_reconciliation'
              && hasWorkerRecoveryDimensions(record?.attributes)) {
            workerRecovery.traces = true;
          } else if (signal === 'metrics'
              && record?.name === 'accord.workflow.executions'
              && hasWorkerRecoveryMetricDimensions(record)) {
            workerRecovery.metrics = true;
          } else if (signal === 'logs'
              && record?.body?.stringValue === 'foundation recovery completed'
              && hasWorkerRecoveryDimensions(record?.attributes)) {
            workerRecovery.logs = true;
          }
        }
        if (signal !== 'traces') continue;
        const attributes = otelAttributes(record?.attributes);
        const route = attributes.get('http.route');
        const status = Number(attributes.get('http.response.status_code'));
        if (typeof route !== 'string' || !Number.isInteger(status)) continue;
        const key = `${service}\u0000${route}\u0000${status}`;
        observed.set(key, (observed.get(key) ?? 0) + 1);
      }
    }
    if (hasRecord) signalServices[signal].add(service);
  }
}

function hasWorkerRecoveryDimensions(attributes) {
  const dimensions = otelAttributes(attributes);
  return dimensions.get('accord.operation') === 'provider_reconciliation'
    && dimensions.get('accord.provider') === 'gitlab'
    && dimensions.get('accord.result_code') === 'success';
}

function hasWorkerRecoveryMetricDimensions(metric) {
  return Array.isArray(metric?.sum?.dataPoints)
    && metric.sum.dataPoints.some((point) => (
      hasWorkerRecoveryDimensions(point?.attributes)
    ));
}

function otelAttributes(attributes) {
  const result = new Map();
  if (!Array.isArray(attributes)) return result;
  for (const attribute of attributes) {
    if (!attribute || typeof attribute.key !== 'string' || !attribute.value) continue;
    const entries = Object.entries(attribute.value);
    if (entries.length !== 1) continue;
    result.set(attribute.key, entries[0][1]);
  }
  return result;
}

function requireClosedObject(value, fields) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw safeError('DEMO_EVIDENCE_OBJECT_INVALID');
  }
  const keys = Object.keys(value);
  if (keys.length !== fields.size || keys.some((key) => !fields.has(key))) {
    throw safeError('DEMO_EVIDENCE_OBJECT_INVALID');
  }
}

function sameIntegerArray(actual, expected) {
  return Array.isArray(actual)
    && actual.length === expected.length
    && actual.every((value, index) => Number.isInteger(value) && value === expected[index]);
}

function demoCommand(executable, args, argv, blockedTool) {
  return { executable, args, argv, blockedTool };
}

function executeDemoCommand({ command, root, childEnvironment, run }) {
  const execution = invokeRunner(run, command.executable, command.args, {
    cwd: root,
    env: childEnvironment,
    timeout: COMMAND_TIMEOUT_MS,
    maxBuffer: 8 * 1024 * 1024,
  });
  return {
    command,
    execution,
    digest: executionDigest(command.argv, execution),
    problem: execution.errorCode === null && execution.exitCode === 0
      ? null
      : { outcome: execution.errorCode === 'ENOENT' || execution.exitCode === 2 ? 'BLOCKED' : 'FAIL' },
  };
}

function invokeRunner(run, executable, args, options) {
  try {
    const result = run(executable, args, options);
    if (result === null || typeof result !== 'object') {
      return normalizedExecution(null, 'NATIVE_RESULT_INVALID', 0, '', '');
    }
    const exitCode = Number.isInteger(result.exitCode) && result.exitCode >= 0
      ? result.exitCode
      : null;
    const errorCode = result.errorCode === null
      ? null
      : typeof result.errorCode === 'string' && result.errorCode.length > 0
        ? result.errorCode
        : 'NATIVE_RESULT_INVALID';
    const durationMs = Number.isFinite(result.durationMs) && result.durationMs >= 0
      ? Math.trunc(result.durationMs)
      : 0;
    return normalizedExecution(
      exitCode,
      errorCode,
      durationMs,
      boundedOutput(result.stdout),
      boundedOutput(result.stderr),
    );
  } catch (error) {
    return normalizedExecution(
      null,
      error?.code === 'ENOENT' ? 'ENOENT' : 'NATIVE_RUNNER_ERROR',
      0,
      '',
      '',
    );
  }
}

function normalizedExecution(exitCode, errorCode, durationMs, stdout, stderr) {
  return { exitCode, errorCode, durationMs, stdout, stderr };
}

function boundedOutput(value) {
  return typeof value === 'string' ? value.slice(0, 8 * 1024 * 1024) : '';
}

function passDemoCheck({ argv, evidence, now }) {
  return {
    check_id: DEMO_CHECK_ID,
    status: 'PASS',
    attempted_argv: argv,
    attempt_exit_code: 0,
    evidence_digests: [...evidence].sort(),
    occurred_at: now.toISOString(),
    owner: OWNER,
  };
}

function failDemoCheck({ argv, exitCode, evidence, now }) {
  return {
    check_id: DEMO_CHECK_ID,
    status: 'FAIL',
    reason_code: 'ASSERTION_FAILED',
    executed: true,
    attempted_argv: argv,
    attempt_exit_code: Number.isInteger(exitCode) && exitCode >= 0 ? exitCode : 1,
    evidence_digests: [...evidence].sort(),
    occurred_at: now.toISOString(),
    owner: OWNER,
  };
}

function blockedDemoCheck({
  argv,
  execution,
  evidence,
  remoteSha,
  treeSha,
  tool,
  now,
}) {
  if (!OBJECT_ID.test(remoteSha) || !OBJECT_ID.test(treeSha)) {
    throw safeError('DEMO_BLOCKED_BINDING_REQUIRED');
  }
  return {
    schema_version: '1.0.0',
    check_id: DEMO_CHECK_ID,
    status: 'BLOCKED',
    remote_sha: remoteSha,
    tree_sha: treeSha,
    reason_code: 'BLOCKED_TOOLCHAIN',
    attempted_argv: argv,
    attempt_exit_code: Number.isInteger(execution.exitCode) && execution.exitCode >= 0
      ? execution.exitCode
      : null,
    attempt_evidence: [...evidence].sort(),
    rerun_argv: ['node', 'scripts/acceptance/run-foundation-acceptance.mjs'],
    occurred_at: now.toISOString(),
    owner: OWNER,
    tool: {
      name: tool.name,
      required_version: tool.requiredVersion,
      observed_version: null,
    },
  };
}

async function writeDemoEvidence(output, result) {
  await validateCheckEvidence(result);
  await atomicWriteJson(resolve(output, 'demo-evidence.json'), result);
  return result;
}

function demoEnvironment(outputDirectory) {
  const secret = () => randomBytes(32).toString('base64url');
  return {
    ...process.env,
    ACCORD_FOUNDATION_EVIDENCE_DIR: outputDirectory,
    ACCORD_POSTGRES_SUPERUSER_PASSWORD: secret(),
    ACCORD_TEMPORAL_SCHEMA_PASSWORD: secret(),
    ACCORD_TEMPORAL_RUNTIME_PASSWORD: secret(),
    ACCORD_MINIO_ROOT_PASSWORD: secret(),
    ACCORD_MINIO_NORMAL_PASSWORD: secret(),
    ACCORD_MINIO_SCANNER_PASSWORD: secret(),
    ACCORD_FT17_WEBHOOK_HMAC_SECRET: randomBytes(32).toString('base64'),
    ACCORD_FT17_DEMO_CERTIFICATE: secret(),
    ACCORD_MINIO_ROOT_USER: 'accordminioroot',
    ACCORD_MINIO_NORMAL_USER: 'accordnormal',
    ACCORD_MINIO_SCANNER_USER: 'accordscanner',
  };
}

function executionDigest(argv, execution) {
  return sha256(canonicalJson({
    argv,
    duration_ms: execution.durationMs,
    exit_code: execution.exitCode,
    error_code: execution.errorCode,
  }));
}

function parseCli(argv) {
  const options = { repository: process.cwd() };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (value === undefined) throw safeError('ARGUMENTS_INVALID');
    const key = {
      '--repository': 'repository',
      '--output': 'outputDirectory',
      '--remote-sha': 'remoteSha',
      '--tree-sha': 'treeSha',
    }[flag];
    if (!key) throw safeError('ARGUMENTS_INVALID');
    options[key] = value;
  }
  if (!options.outputDirectory) throw safeError('ARGUMENTS_INVALID');
  return options;
}

function safeError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

async function main() {
  try {
    const options = parseCli(process.argv.slice(2));
    const result = await runFoundationDemo(options);
    process.stdout.write(`foundation-demo: ${result.status}\n`);
    process.exitCode = verdictExitCode(result.status);
  } catch (error) {
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`foundation-demo: FAIL (${code})\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
