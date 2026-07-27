import { access, lstat } from 'node:fs/promises';
import { constants } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { atomicWriteJson, canonicalJson, sha256 } from './merge-verdicts.mjs';
import { runNative } from './resolve-remote-sha.mjs';
import { verifySensitiveLogs } from '../../tests/integration/verify-sensitive-logs.mjs';

const OWNER = 'platform-engineering';
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
  sentinels = [],
  run = runNative,
  now = new Date(),
}) {
  const root = resolve(repository);
  const output = resolve(outputDirectory);
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
  if (boundaries.some((entry) => !entry.present)) {
    const result = {
      schema_version: '1.0.0',
      status: 'FAIL',
      reason_code: 'ASSERTION_FAILED',
      attempted_argv: attemptedArgv,
      attempt_exit_code: 1,
      evidence_digests: [boundaryDigest],
      occurred_at: now.toISOString(),
      owner: OWNER,
    };
    await atomicWriteJson(resolve(output, 'demo-evidence.json'), result);
    return result;
  }

  const command = run(attemptedArgv[0], attemptedArgv.slice(1), {
    cwd: root,
    timeout: 30 * 60_000,
    maxBuffer: 8 * 1024 * 1024,
  });
  const commandDigest = sha256(canonicalJson({
    argv: attemptedArgv,
    duration_ms: command.durationMs,
    exit_code: command.exitCode,
    error_code: command.errorCode,
  }));
  if (command.errorCode !== null || command.exitCode !== 0) {
    const result = {
      schema_version: '1.0.0',
      status: 'FAIL',
      reason_code: 'ASSERTION_FAILED',
      attempted_argv: attemptedArgv,
      attempt_exit_code: command.exitCode ?? 1,
      evidence_digests: [boundaryDigest, commandDigest].sort(),
      occurred_at: now.toISOString(),
      owner: OWNER,
    };
    await atomicWriteJson(resolve(output, 'demo-evidence.json'), result);
    return result;
  }

  let sensitiveResult;
  try {
    sensitiveResult = await verifySensitiveLogs({
      services: [
        { service: 'foundation-demo', path: resolve(output, 'logs') },
        { service: 'foundation-telemetry', path: resolve(output, 'telemetry') },
      ],
      sentinels,
    });
  } catch {
    sensitiveResult = { status: 'FAIL', files_scanned: 0, matches: [] };
  }
  const sensitiveDigest = sha256(canonicalJson(sensitiveResult));
  const status = sensitiveResult.status === 'PASS' ? 'PASS' : 'FAIL';
  const result = {
    schema_version: '1.0.0',
    status,
    ...(status === 'FAIL' ? { reason_code: 'ASSERTION_FAILED' } : {}),
    attempted_argv: attemptedArgv,
    attempt_exit_code: status === 'PASS' ? 0 : 1,
    evidence_digests: [boundaryDigest, commandDigest, sensitiveDigest].sort(),
    occurred_at: now.toISOString(),
    owner: OWNER,
  };
  await atomicWriteJson(resolve(output, 'demo-evidence.json'), result);
  return result;
}

function parseCli(argv) {
  const options = { repository: process.cwd() };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (value === undefined) throw safeError('ARGUMENTS_INVALID');
    if (flag === '--repository') options.repository = value;
    else if (flag === '--output') options.outputDirectory = value;
    else throw safeError('ARGUMENTS_INVALID');
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
    process.exitCode = result.status === 'PASS' ? 0 : 1;
  } catch (error) {
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`foundation-demo: FAIL (${code})\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
