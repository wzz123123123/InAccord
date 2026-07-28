import { spawn } from 'node:child_process';
import { lstat, readFile, realpath, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const LOCAL_ROOT = path.join(ROOT, 'infra', 'local');
const STATE_DIRECTORY = path.join(LOCAL_ROOT, 'state');
const COMPOSE_FILE = path.join(LOCAL_ROOT, 'compose.yaml');
const IMAGE_ENV = path.join(STATE_DIRECTORY, 'images.env');
const FINAL_RESULT = path.join(ROOT, 'build', 'verification', 'ft13-local.json');
const COMPOSE_PROJECT = 'accord-foundation-local';
const TEARDOWN_ENVIRONMENT = Object.freeze({
  ACCORD_POSTGRES_SUPERUSER_PASSWORD: 'teardown-placeholder-not-used',
  ACCORD_TEMPORAL_SCHEMA_PASSWORD: 'teardown-schema-placeholder',
  ACCORD_TEMPORAL_RUNTIME_PASSWORD: 'teardown-runtime-placeholder',
  ACCORD_MINIO_ROOT_USER: 'teardown-root',
  ACCORD_MINIO_ROOT_PASSWORD: 'teardown-root-placeholder',
  ACCORD_MINIO_NORMAL_USER: 'teardown-normal',
  ACCORD_MINIO_NORMAL_PASSWORD: 'teardown-normal-placeholder',
  ACCORD_MINIO_SCANNER_USER: 'teardown-scanner',
  ACCORD_MINIO_SCANNER_PASSWORD: 'teardown-scanner-placeholder',
});

class BlockedError extends Error {}

function parseCli(argv) {
  if (argv.length === 0) return { purge: false };
  if (argv.length === 1 && argv[0] === '--purge-local-state') return { purge: true };
  throw new TypeError('Expected no arguments or exactly --purge-local-state');
}

function composeArguments(...tail) {
  return ['compose', '--env-file', IMAGE_ENV, '-f', COMPOSE_FILE, '-p', COMPOSE_PROJECT, ...tail];
}

function runNative(executable, argv, timeoutMs) {
  return new Promise((resolve) => {
    const child = spawn(executable, argv, {
      cwd: ROOT,
      env: { ...TEARDOWN_ENVIRONMENT, ...process.env },
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let diagnostic = '';
    const drain = (chunk) => { diagnostic = `${diagnostic}${chunk.toString('utf8')}`.slice(-64 * 1024); };
    child.stdout.on('data', drain);
    child.stderr.on('data', drain);
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    timer.unref();
    let settled = false;
    const finish = (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ code, diagnostic });
    };
    child.once('error', () => finish(-1));
    child.once('close', (code) => finish(code ?? -1));
  });
}

async function guardedPurge() {
  const expected = path.resolve(LOCAL_ROOT, 'state');
  if (path.resolve(STATE_DIRECTORY) !== expected || path.dirname(expected) !== path.resolve(LOCAL_ROOT)) {
    throw new TypeError('Refusing to purge an unexpected state directory');
  }
  try {
    const metadata = await lstat(STATE_DIRECTORY);
    if (metadata.isSymbolicLink() || !metadata.isDirectory()) throw new TypeError('Local state is not a regular directory');
    const resolvedState = await realpath(STATE_DIRECTORY);
    const resolvedLocal = await realpath(LOCAL_ROOT);
    if (resolvedState !== path.join(resolvedLocal, 'state')) throw new TypeError('Resolved state path escapes infra/local');
  } catch (error) {
    if (error?.code === 'ENOENT') return;
    throw error;
  }
  await rm(STATE_DIRECTORY, { recursive: true, force: true });
}

async function main(argv) {
  const options = parseCli(argv);
  try {
    await readFile(IMAGE_ENV);
  } catch {
    throw new BlockedError('Rendered image environment is unavailable; teardown cannot identify the closed topology');
  }
  const downArguments = ['down', '--remove-orphans', '--timeout', '30'];
  if (options.purge) downArguments.push('--volumes');
  const down = await runNative('docker', composeArguments(...downArguments), 90_000);
  if (down.code === -1) throw new BlockedError('Docker executable is unavailable');
  if (down.code !== 0) {
    await rm(FINAL_RESULT, { force: true });
    throw new Error(down.diagnostic || `Docker Compose down exited ${down.code}`);
  }
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const ps = await runNative('docker', composeArguments('ps', '-q'), 10_000);
    if (ps.code === 0 && ps.diagnostic.trim() === '') break;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  const finalPs = await runNative('docker', composeArguments('ps', '-q'), 10_000);
  if (finalPs.code !== 0 || finalPs.diagnostic.trim() !== '') {
    await rm(FINAL_RESULT, { force: true });
    throw new Error('Docker Compose project did not reach empty state');
  }
  if (options.purge) await guardedPurge();
  return 0;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`local-down: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = error instanceof BlockedError ? 2 : 1;
  }
}
