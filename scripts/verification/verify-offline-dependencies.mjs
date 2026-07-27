import { spawn } from 'node:child_process';
import { resolve, win32 } from 'node:path';
import { pathToFileURL } from 'node:url';

const REPOSITORY_ROOT = resolve(import.meta.dirname, '..', '..');
const STEP_TIMEOUT_MS = 30 * 60_000;
function step(id, executable, argv) {
  return Object.freeze({ id, executable, argv: Object.freeze(argv) });
}

function pnpmStep(id, argv, { platform, nodeExecutable }) {
  if (platform !== 'win32') return step(id, 'pnpm', argv);
  const corepackPnpm = win32.join(
    win32.dirname(nodeExecutable),
    'node_modules',
    'corepack',
    'dist',
    'pnpm.js',
  );
  return step(id, nodeExecutable, [corepackPnpm, ...argv]);
}

export function offlineDependencySteps({
  platform = process.platform,
  nodeExecutable = process.execPath,
} = {}) {
  const runtime = { platform, nodeExecutable };
  return Object.freeze([
    step('gradle', 'java', [
      '-classpath', 'gradle/wrapper/gradle-wrapper.jar',
      'org.gradle.wrapper.GradleWrapperMain',
      '--offline', '--no-daemon', '--dependency-verification=strict', 'check',
    ]),
    pnpmStep('pnpm-install', ['install', '--frozen-lockfile', '--offline'], runtime),
    pnpmStep('browser-test', ['-r', '--if-present', 'test'], runtime),
    pnpmStep('browser-typecheck', ['-r', '--if-present', 'typecheck'], runtime),
    pnpmStep('contracts-lint', ['contracts:lint'], runtime),
    step('python-sync', 'uv', ['sync', '--frozen', '--offline']),
    step('python-ruff', 'uv', ['run', 'ruff', 'check', 'apps/agent-runtime']),
    step('python-mypy', 'uv', ['run', 'mypy', 'apps/agent-runtime']),
    step('python-pytest', 'uv', ['run', 'pytest', 'apps/agent-runtime']),
    step('buf-lint', 'buf', ['lint']),
    step('buf-build', 'buf', ['build']),
  ]);
}

function runNative(executable, argv, { cwd, timeoutMs = STEP_TIMEOUT_MS } = {}) {
  return new Promise((complete) => {
    let settled = false;
    const child = spawn(executable, argv, {
      cwd,
      env: process.env,
      shell: false,
      windowsHide: true,
      stdio: 'ignore',
    });
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    timer.unref();
    const finish = (exitCode, errorCode = null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      complete({ exitCode, errorCode });
    };
    child.once('error', (error) => finish(null, error?.code === 'ENOENT' ? 'ENOENT' : 'NATIVE_ERROR'));
    child.once('close', (code, signal) => finish(code ?? (signal ? 1 : null)));
  });
}

export async function verifyOfflineDependencies({
  repository = REPOSITORY_ROOT,
  run = runNative,
  platform = process.platform,
  nodeExecutable = process.execPath,
} = {}) {
  const root = resolve(repository);
  const steps = offlineDependencySteps({ platform, nodeExecutable });
  for (const specification of steps) {
    const result = await run(specification.executable, specification.argv, {
      cwd: root,
      timeoutMs: STEP_TIMEOUT_MS,
    });
    if (result?.errorCode !== null || result?.exitCode !== 0) {
      const error = new Error(`OFFLINE_DEPENDENCY_GATE_FAILED:${specification.id}`);
      error.code = 'OFFLINE_DEPENDENCY_GATE_FAILED';
      throw error;
    }
  }
  return { status: 'PASS', steps: steps.map(({ id }) => id) };
}

async function main() {
  try {
    await verifyOfflineDependencies();
    process.stdout.write('offline-dependencies: PASS\n');
    process.exitCode = 0;
  } catch (error) {
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`offline-dependencies: FAIL (${code})\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
