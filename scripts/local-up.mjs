import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { readdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const COMPOSE_FILE = path.join(ROOT, 'infra', 'local', 'compose.yaml');
const IMAGE_ENV = path.join(ROOT, 'infra', 'local', 'state', 'images.env');
const STATE_DIRECTORY = path.join(ROOT, 'infra', 'local', 'state');
const RUN_STATE = path.join(STATE_DIRECTORY, 'run-state.json');
const IMAGE_LOCK = path.join(ROOT, 'infra', 'images', 'images.lock.json');
const RECEIPT = path.join(ROOT, 'build', 'verification', 'ft13-temporal-it.json');
const FINAL_RESULT = path.join(ROOT, 'build', 'verification', 'ft13-local.json');
const JUNIT_XML = path.join(
  ROOT, 'tests', 'integration', 'build', 'test-results', 'temporalLocalTopologyTest',
  'TEST-com.inforvans.accord.controlplane.worker.temporal.TemporalLocalTopologyIT.xml',
);
const COMPOSE_PROJECT = 'accord-foundation-local';
const LOCAL_ENDPOINTS = Object.freeze({
  postgres: '127.0.0.1:55432',
  temporal: '127.0.0.1:7233',
  temporal_ui: '127.0.0.1:8080',
});
const DEPENDENCY_SERVICES = Object.freeze([
  'postgres', 'temporal-schema', 'temporal-server', 'temporal-namespace', 'temporal-ui',
  'minio', 'minio-init', 'mock-gitlab', 'mock-kms', 'otel-collector',
]);
const GRADLE_TASK_ARGS = Object.freeze([
  ':tests:integration:temporalLocalTopologyTest',
  '--rerun-tasks',
  '--no-daemon',
  '--dependency-verification=strict',
  '--console=plain',
]);
const MAX_DIAGNOSTIC_BYTES = 64 * 1024;

class BlockedError extends Error {}

function sha256(bytes) {
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
}

function canonicalJson(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
}

function parseMode(argv) {
  if (argv.length === 0) return 'start';
  if (argv.length === 1 && argv[0] === '--run-temporal-it') return 'temporal-it';
  if (argv.length === 1 && argv[0] === '--finalize') return 'finalize';
  throw new TypeError('Expected no arguments, exactly --run-temporal-it, or exactly --finalize');
}

function validateLocalEnvironment() {
  const passwordNames = [
    'ACCORD_POSTGRES_SUPERUSER_PASSWORD',
    'ACCORD_TEMPORAL_SCHEMA_PASSWORD',
    'ACCORD_TEMPORAL_RUNTIME_PASSWORD',
    'ACCORD_MINIO_ROOT_PASSWORD',
    'ACCORD_MINIO_NORMAL_PASSWORD',
    'ACCORD_MINIO_SCANNER_PASSWORD',
  ];
  const passwords = passwordNames.map((name) => {
    const value = process.env[name] ?? '';
    if (!/^[A-Za-z0-9_-]{24,128}$/u.test(value)) throw new BlockedError(`${name} must be a 24-128 character Base64URL secret`);
    return value;
  });
  if (new Set(passwords).size !== passwords.length) throw new BlockedError('Local service passwords must all be distinct');
  const userNames = ['ACCORD_MINIO_ROOT_USER', 'ACCORD_MINIO_NORMAL_USER', 'ACCORD_MINIO_SCANNER_USER'];
  const users = userNames.map((name) => {
    const value = process.env[name] ?? '';
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$/u.test(value)) throw new BlockedError(`${name} is absent or invalid`);
    return value;
  });
  if (new Set(users).size !== users.length) throw new BlockedError('Local MinIO identities must be distinct');
}

function assertStateTarget(target) {
  const relative = path.relative(STATE_DIRECTORY, path.resolve(target));
  if (relative.startsWith('..') || path.isAbsolute(relative)) throw new TypeError('State target escapes infra/local/state');
}

async function atomicState(expectedBytes, next) {
  assertStateTarget(RUN_STATE);
  if (expectedBytes !== undefined) {
    const actual = await readFile(RUN_STATE);
    if (!actual.equals(expectedBytes)) throw new Error('Run-state compare-and-swap precondition failed');
  }
  const temporary = path.join(STATE_DIRECTORY, `.run-state.${process.pid}.${randomUUID()}.tmp`);
  await writeFile(temporary, `${canonicalJson(next)}\n`, { encoding: 'utf8', flag: 'wx', mode: 0o600 });
  try {
    await rename(temporary, RUN_STATE);
  } finally {
    await rm(temporary, { force: true }).catch(() => {});
  }
}

function redactor(nonceDigest) {
  const environmentSecrets = Object.entries(process.env)
    .filter(([name, value]) => /(?:PASSWORD|SECRET|TOKEN|PRIVATE_KEY|ACCESS_KEY)/u.test(name) && value && value.length >= 8)
    .map(([, value]) => value);
  return (text) => {
    let redacted = text;
    for (const secret of environmentSecrets) redacted = redacted.replaceAll(secret, '[REDACTED]');
    redacted = redacted.replace(/[A-Za-z0-9_-]{43}/gu, (candidate) => {
      if (!nonceDigest) return candidate;
      return sha256(Buffer.from(candidate, 'utf8')) === nonceDigest ? '[REDACTED_SUPERVISOR_NONCE]' : candidate;
    });
    return redacted;
  };
}

function snapshotWindowsProcesses() {
  return new Promise((resolve) => {
    const script = 'Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId | ConvertTo-Json -Compress';
    const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {
      cwd: ROOT,
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'ignore'],
    });
    let output = '';
    child.stdout.on('data', (chunk) => { if (output.length < 1024 * 1024) output += chunk.toString('utf8'); });
    const timer = setTimeout(() => child.kill('SIGKILL'), 3000);
    timer.unref();
    child.once('error', () => { clearTimeout(timer); resolve([]); });
    child.once('close', (code) => {
      clearTimeout(timer);
      if (code !== 0) return resolve([]);
      try {
        const parsed = JSON.parse(output);
        resolve(Array.isArray(parsed) ? parsed : [parsed]);
      } catch {
        resolve([]);
      }
    });
  });
}

async function snapshotPosixProcesses() {
  const rows = [];
  for (const entry of await readdir('/proc', { withFileTypes: true }).catch(() => [])) {
    if (!entry.isDirectory() || !/^\d+$/u.test(entry.name)) continue;
    try {
      const stat = await readFile(`/proc/${entry.name}/stat`, 'utf8');
      const closing = stat.lastIndexOf(')');
      const fields = stat.slice(closing + 2).split(' ');
      rows.push({ ProcessId: Number(entry.name), ParentProcessId: Number(fields[1]) });
    } catch {
      // A process can exit between directory enumeration and reading stat.
    }
  }
  return rows;
}

async function descendantPids(rootPid) {
  const rows = process.platform === 'win32' ? await snapshotWindowsProcesses() : await snapshotPosixProcesses();
  const result = new Set([rootPid]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const row of rows) {
      if (result.has(Number(row.ParentProcessId)) && !result.has(Number(row.ProcessId))) {
        result.add(Number(row.ProcessId));
        changed = true;
      }
    }
  }
  return result;
}

async function killProcessTree(child) {
  if (!child.pid) return;
  if (process.platform === 'win32') {
    await new Promise((resolve) => {
      const killer = spawn('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], {
        shell: false,
        windowsHide: true,
        stdio: 'ignore',
      });
      killer.once('error', resolve);
      killer.once('close', resolve);
    });
  } else {
    try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); }
  }
}

function runNative(executable, argv, options = {}) {
  const timeoutMs = options.timeoutMs ?? 30_000;
  const maximumCapture = options.maximumCapture ?? 1024 * 1024;
  const redact = redactor(options.nonceDigest);
  return new Promise((resolve) => {
    const started = process.hrtime.bigint();
    const child = spawn(executable, argv, {
      cwd: ROOT,
      env: options.env ?? process.env,
      shell: false,
      windowsHide: true,
      detached: process.platform !== 'win32',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const pids = new Set();
    if (child.pid) pids.add(child.pid);
    let capture = '';
    let diagnostic = '';
    let timedOut = false;
    let sampling = false;
    const drain = (chunk) => {
      const text = redact(chunk.toString('utf8'));
      if (capture.length < maximumCapture) capture += text.slice(0, maximumCapture - capture.length);
      diagnostic = `${diagnostic}${text}`.slice(-MAX_DIAGNOSTIC_BYTES);
    };
    child.stdout.on('data', drain);
    child.stderr.on('data', drain);
    const sample = async () => {
      if (!options.trackDescendants || sampling || !child.pid) return;
      sampling = true;
      try { for (const pid of await descendantPids(child.pid)) pids.add(pid); } finally { sampling = false; }
    };
    void sample();
    const sampler = setInterval(() => { void sample(); }, 1000);
    sampler.unref();
    const timeout = setTimeout(() => {
      timedOut = true;
      void killProcessTree(child);
    }, timeoutMs);
    timeout.unref();
    let settled = false;
    const finish = (exitCode) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      clearInterval(sampler);
      const durationMs = Number((process.hrtime.bigint() - started) / 1_000_000n);
      resolve({ exitCode, durationMs, output: capture, diagnostic, timedOut, pids });
    };
    child.once('error', () => finish(-1));
    child.once('close', (code) => finish(code ?? -1));
  });
}

function javaWrapperCommand(gradleArguments) {
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java';
  const executable = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', javaName)
    : javaName;
  const wrapperJar = path.join(ROOT, 'gradle', 'wrapper', 'gradle-wrapper.jar');
  return {
    executable,
    argv: ['-classpath', wrapperJar, 'org.gradle.wrapper.GradleWrapperMain', ...gradleArguments],
  };
}

async function requireSuccess(executable, argv, options) {
  const execution = await runNative(executable, argv, options);
  if (execution.exitCode === -1 || execution.exitCode === 2) throw new BlockedError(execution.diagnostic || `${executable} is blocked`);
  if (execution.exitCode !== 0 || execution.timedOut) {
    throw new Error(execution.diagnostic || `${executable} exited ${execution.exitCode}`);
  }
  return execution;
}

function composeArguments(...tail) {
  return ['compose', '--env-file', IMAGE_ENV, '-f', COMPOSE_FILE, '-p', COMPOSE_PROJECT, ...tail];
}

async function cleanStaleEvidence() {
  for (const target of [RUN_STATE, RECEIPT, FINAL_RESULT, JUNIT_XML]) await rm(target, { force: true });
}

async function startOnly() {
  validateLocalEnvironment();
  await cleanStaleEvidence();
  const preflight = await requireSuccess(process.execPath, [
    path.join(ROOT, 'scripts', 'verification', 'preflight.mjs'),
    '--check-id', 'ft13-toolchain',
    '--require', 'node', '--require', 'docker', '--require', 'docker-buildx',
    '--require', 'docker-compose', '--require', 'git', '--require', 'java',
  ], { timeoutMs: 60_000 });
  const pki = javaWrapperCommand([
    ':tests:integration:generateTemporalLocalPki',
    '--no-daemon', '--dependency-verification=strict', '--console=plain',
  ]);
  await requireSuccess(pki.executable, pki.argv, { timeoutMs: 90_000, maximumCapture: 0 });
  await requireSuccess(process.execPath, [path.join(ROOT, 'scripts', 'images', 'render-compose-images.mjs')]);
  await requireSuccess(process.execPath, [path.join(ROOT, 'scripts', 'images', 'verify-images.mjs'), '--scope', 'local']);
  await requireSuccess('docker', composeArguments('up', '-d', '--wait', '--wait-timeout', '180', ...DEPENDENCY_SERVICES), { timeoutMs: 210_000 });
  const ps = await requireSuccess('docker', composeArguments('ps', '--all', '--format', 'json'), { timeoutMs: 30_000 });
  let containers;
  try {
    const trimmed = ps.output.trim();
    containers = trimmed.startsWith('[') ? JSON.parse(trimmed) : trimmed.split(/\r?\n/u).filter(Boolean).map((line) => JSON.parse(line));
  } catch {
    throw new Error('Docker Compose returned malformed readiness facts');
  }
  const observedServices = new Set(containers.map((container) => container.Service));
  for (const service of DEPENDENCY_SERVICES) {
    if (!observedServices.has(service)) throw new Error(`Dependency service is absent after startup: ${service}`);
  }
  const preflightResult = JSON.parse(preflight.output.trim().split(/\r?\n/u).at(-1));
  const lockBytes = await readFile(IMAGE_LOCK);
  await atomicState(undefined, {
    schema_version: '1.0.0',
    run_id: randomUUID(),
    image_lock_sha256: sha256(lockBytes),
    compose_project: COMPOSE_PROJECT,
    dependency_services: [...DEPENDENCY_SERVICES],
    local_endpoints: LOCAL_ENDPOINTS,
    ready: Object.fromEntries(DEPENDENCY_SERVICES.map((service) => [service, true])),
    tool_observations: preflightResult.tool_observations,
  });
}

function closedRunState(state) {
  const baseKeys = ['compose_project', 'dependency_services', 'image_lock_sha256', 'local_endpoints', 'ready', 'run_id', 'schema_version', 'tool_observations'];
  const optional = ['temporal_it_attempt', 'temporal_it_observation'];
  const keys = Object.keys(state).sort();
  if (keys.some((key) => !baseKeys.includes(key) && !optional.includes(key)) || baseKeys.some((key) => !(key in state))) {
    throw new Error('Run-state shape is not closed');
  }
  if (state.schema_version !== '1.0.0' || state.compose_project !== COMPOSE_PROJECT) throw new Error('Run-state identity mismatch');
  if (canonicalJson(state.dependency_services) !== canonicalJson(DEPENDENCY_SERVICES)) throw new Error('Run-state dependency set mismatch');
  if (canonicalJson(state.local_endpoints) !== canonicalJson(LOCAL_ENDPOINTS)) throw new Error('Run-state local endpoint mismatch');
  if (DEPENDENCY_SERVICES.some((service) => state.ready?.[service] !== true)) throw new Error('Run-state is not ready');
  return state;
}

async function runTemporalIt() {
  const initialBytes = await readFile(RUN_STATE);
  const initial = closedRunState(JSON.parse(initialBytes.toString('utf8')));
  if (initial.temporal_it_attempt) throw new Error('This run already has a Temporal IT attempt');
  await rm(RECEIPT, { force: true });
  await rm(JUNIT_XML, { force: true });
  await rm(FINAL_RESULT, { force: true });
  const attemptId = randomUUID();
  const nonceBytes = randomBytes(32);
  let nonceText = nonceBytes.toString('base64url');
  const nonceDigest = sha256(Buffer.from(nonceText, 'utf8'));
  const pending = {
    ...initial,
    temporal_it_attempt: {
      run_id: initial.run_id,
      it_attempt_id: attemptId,
      state: 'PENDING',
      supervisor_nonce_sha256: nonceDigest,
    },
  };
  delete pending.temporal_it_observation;
  await atomicState(initialBytes, pending);
  const pendingBytes = await readFile(RUN_STATE);
  const gradleArguments = [`-PaccordFt13ItAttemptId=${attemptId}`, ...GRADLE_TASK_ARGS];
  const command = javaWrapperCommand(gradleArguments);
  const childEnvironment = { ...process.env, ACCORD_FT13_SUPERVISOR_NONCE: nonceText };
  nonceBytes.fill(0);
  nonceText = null;
  const execution = await runNative(command.executable, command.argv, {
    env: childEnvironment,
    timeoutMs: 240_000,
    maximumCapture: 0,
    nonceDigest,
    trackDescendants: true,
  });
  delete childEnvironment.ACCORD_FT13_SUPERVISOR_NONCE;
  if (execution.exitCode !== 0 || execution.timedOut) {
    const currentBytes = await readFile(RUN_STATE).catch(() => undefined);
    if (currentBytes) {
      const current = JSON.parse(currentBytes.toString('utf8'));
      const attempt = current.temporal_it_attempt;
      const ownedPending = attempt?.it_attempt_id === attemptId && attempt.state === 'PENDING';
      const ownedRunning = attempt?.it_attempt_id === attemptId && attempt.state === 'RUNNING' && execution.pids.has(attempt.runner_pid);
      if (ownedPending || ownedRunning) {
        delete current.temporal_it_attempt;
        delete current.temporal_it_observation;
        await atomicState(currentBytes, current).catch(() => {});
      }
    }
    await rm(RECEIPT, { force: true });
    await rm(JUNIT_XML, { force: true });
    await rm(FINAL_RESULT, { force: true });
    throw new Error(execution.diagnostic || 'Supervised Temporal IT failed');
  }
  const runningBytes = await readFile(RUN_STATE);
  const running = closedRunState(JSON.parse(runningBytes.toString('utf8')));
  const attempt = running.temporal_it_attempt;
  if (attempt?.it_attempt_id !== attemptId || attempt.state !== 'RUNNING' || attempt.supervisor_nonce_sha256 !== nonceDigest) {
    throw new Error('Temporal IT did not establish the owned RUNNING attempt');
  }
  if (!Number.isSafeInteger(attempt.runner_pid) || !execution.pids.has(attempt.runner_pid)) {
    throw new Error('Temporal IT runner PID is not an observed Gradle descendant');
  }
  const receiptBytes = await readFile(RECEIPT);
  const junitBytes = await readFile(JUNIT_XML);
  const envelope = JSON.parse(receiptBytes.toString('utf8'));
  if (canonicalJson(Object.keys(envelope).sort()) !== canonicalJson(['receipt', 'receipt_sha256'])) throw new Error('Temporal IT receipt envelope is not closed');
  if (sha256(Buffer.from(canonicalJson(envelope.receipt))) !== envelope.receipt_sha256) throw new Error('Temporal IT receipt digest mismatch');
  if (envelope.receipt.run_id !== running.run_id || envelope.receipt.it_attempt_id !== attemptId || envelope.receipt.status !== 'PASS') {
    throw new Error('Temporal IT receipt identity/status mismatch');
  }
  const observation = {
    run_id: running.run_id,
    it_attempt_id: attemptId,
    supervisor_nonce_sha256: nonceDigest,
    runner_pid: attempt.runner_pid,
    receipt_sha256: envelope.receipt_sha256,
    junit_xml_sha256: sha256(junitBytes),
    runner_descendant_verified: true,
    tool_id: 'gradle-temporal-it',
    executable: 'java',
    argv: gradleArguments,
    exit_code: 0,
    duration_ms: execution.durationMs,
    observed_version: 'Gradle 8.14.3',
    normalized_version: '8.14.3',
  };
  const observationEnvelope = {
    run_id: running.run_id,
    it_attempt_id: attemptId,
    supervisor_nonce_sha256: nonceDigest,
    runner_pid: attempt.runner_pid,
    receipt_sha256: envelope.receipt_sha256,
    junit_xml_sha256: sha256(junitBytes),
    observation,
  };
  observationEnvelope.observation_sha256 = sha256(Buffer.from(canonicalJson(observationEnvelope)));
  const completed = {
    ...running,
    temporal_it_attempt: { ...attempt, state: 'COMPLETED' },
    temporal_it_observation: observationEnvelope,
  };
  await atomicState(runningBytes, completed);
}

async function finalize() {
  const runStateBytes = await readFile(RUN_STATE);
  const state = closedRunState(JSON.parse(runStateBytes.toString('utf8')));
  if (state.temporal_it_attempt?.state !== 'COMPLETED' || !state.temporal_it_observation) {
    throw new Error('Current run has no completed supervised Temporal IT');
  }
  const receiptBytes = await readFile(RECEIPT);
  const junitBytes = await readFile(JUNIT_XML);
  const receipt = JSON.parse(receiptBytes.toString('utf8'));
  if (receipt.receipt?.run_id !== state.run_id || receipt.receipt?.it_attempt_id !== state.temporal_it_attempt.it_attempt_id) {
    throw new Error('Receipt does not belong to the current completed attempt');
  }
  if (sha256(Buffer.from(canonicalJson(receipt.receipt))) !== receipt.receipt_sha256) throw new Error('Receipt digest mismatch');
  const observation = state.temporal_it_observation;
  if (observation.receipt_sha256 !== receipt.receipt_sha256 || observation.junit_xml_sha256 !== sha256(junitBytes)) {
    throw new Error('Completed observation does not bind current receipt/XML bytes');
  }
  const observationPreimage = { ...observation };
  delete observationPreimage.observation_sha256;
  if (sha256(Buffer.from(canonicalJson(observationPreimage))) !== observation.observation_sha256) {
    throw new Error('Completed observation digest mismatch');
  }
  await requireSuccess(process.execPath, [path.join(ROOT, 'scripts', 'images', 'verify-images.mjs'), '--scope', 'local']);
  const lockBytes = await readFile(IMAGE_LOCK);
  const composeBytes = await readFile(COMPOSE_FILE);
  const capabilities = await readFile(path.join(ROOT, 'infra', 'local', 'object-storage-capabilities.json'));
  const { writeCheckResult } = await import('./verification/check-result.mjs');
  await writeCheckResult({
    schema_version: '1.0.0',
    check_id: 'ft13-local',
    status: 'PASS',
    reason_code: 'PASS',
    started_at: new Date().toISOString(),
    finished_at: new Date().toISOString(),
    evidence: [lockBytes, composeBytes, capabilities, runStateBytes, receiptBytes, junitBytes].map(sha256).sort(),
    tool_observations: [
      ...state.tool_observations,
      {
        tool_id: observation.observation.tool_id,
        executable: observation.observation.executable,
        argv: observation.observation.argv,
        exit_code: observation.observation.exit_code,
        duration_ms: observation.observation.duration_ms,
        observed_version: observation.observation.observed_version,
        normalized_version: observation.observation.normalized_version,
      },
    ],
  }, 'build/verification/ft13-local.json');
}

async function main(argv) {
  const mode = parseMode(argv);
  if (mode === 'start') await startOnly();
  else if (mode === 'temporal-it') await runTemporalIt();
  else await finalize();
  return 0;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`local-up: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = error instanceof BlockedError ? 2 : 1;
  }
}
