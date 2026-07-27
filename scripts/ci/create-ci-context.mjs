import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

import { REPOSITORY_ROOT, canonicalJson } from '../verification/check-result.mjs';

const SCHEMA_PATH = path.join(REPOSITORY_ROOT, 'contracts', 'ci', 'ci-context.schema.json');
const GIT_SHA = /^[0-9a-f]{40}$/u;
const MAX_OUTPUT_BYTES = 64 * 1024;
const GIT_TIMEOUT_MS = 30_000;
const FORBIDDEN_ARGUMENT = /(?:\bBearer\s+|(?:password|token|secret|private[_-]?key)=)/iu;

export class ContextBlockedError extends Error {
  constructor(detailCode, message) {
    super(message);
    this.name = 'ContextBlockedError';
    this.detailCode = detailCode;
  }
}

export function normalizeRemoteUrl(value) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new TypeError('Authoritative remote URL is not an absolute URI');
  }
  if (!['https:', 'ssh:'].includes(parsed.protocol)) throw new TypeError('Authoritative remote URL must use HTTPS or SSH');
  if (parsed.password || parsed.search || parsed.hash) throw new TypeError('Authoritative remote URL contains forbidden credentials or parameters');
  if (parsed.username && parsed.protocol !== 'ssh:') throw new TypeError('HTTPS remote URL must not contain user information');
  parsed.hostname = parsed.hostname.toLowerCase();
  parsed.pathname = parsed.pathname.replace(/\/+$/u, '').replace(/[.]git$/u, '') + '.git';
  return parsed.toString().replace(/\/$/u, '');
}

export function validateProductionContext(context) {
  if (context.environment_class !== 'production') return context;
  if (/[.]invalid(?::|\/|$)/u.test(context.remote_url)
      || /[.]invalid(?::|\/|$)/u.test(context.registry_repository)) {
    throw new TypeError('Production context contains a reserved fixture host');
  }
  for (const field of ['remote_sha', 'base_sha', 'tree_sha']) {
    if (/^([0-9a-f])\1{39}$/u.test(context[field])) throw new TypeError(`Production context contains a reserved ${field}`);
  }
  return context;
}

export async function validateContext(context) {
  const schema = JSON.parse(await readFile(SCHEMA_PATH, 'utf8'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  if (!validate(context)) throw new TypeError(`Invalid closed CI context: ${ajv.errorsText(validate.errors, { separator: '; ' })}`);
  validateProductionContext(context);
  return context;
}

export function runNative(executable, argv, options = {}) {
  if (!Array.isArray(argv) || argv.some((argument) => typeof argument !== 'string')) throw new TypeError('Native argv must be a string array');
  if (argv.some((argument) => argument.includes('\0') || argument.includes('\n') || FORBIDDEN_ARGUMENT.test(argument))) {
    throw new TypeError('Native argv contains forbidden secret material');
  }
  return new Promise((resolve, reject) => {
    let output = '';
    let bytes = 0;
    let settled = false;
    const child = spawn(executable, argv, {
      cwd: options.cwd ?? REPOSITORY_ROOT,
      env: options.env ?? process.env,
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const append = (chunk) => {
      if (bytes >= MAX_OUTPUT_BYTES) return;
      const slice = chunk.subarray(0, MAX_OUTPUT_BYTES - bytes);
      bytes += slice.length;
      output += slice.toString('utf8');
    };
    child.stdout.on('data', append);
    child.stderr.on('data', append);
    const timer = setTimeout(() => child.kill('SIGKILL'), options.timeoutMs ?? GIT_TIMEOUT_MS);
    timer.unref();
    const finish = (error, code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve({ exitCode: code ?? -1, output: output.trim() });
    };
    child.once('error', (error) => finish(error));
    child.once('close', (code) => finish(undefined, code));
  });
}

async function git(argv, { blocked = false } = {}) {
  let result;
  try {
    result = await runNative('git', argv);
  } catch {
    if (blocked) throw new ContextBlockedError('GIT_UNAVAILABLE', 'Git is unavailable');
    throw new Error('Git execution failed');
  }
  if (result.exitCode !== 0) {
    if (blocked) throw new ContextBlockedError('REMOTE_AUTHORITY_UNAVAILABLE', 'Remote authority could not be verified');
    throw new Error(`Git assertion failed for ${argv[0]}`);
  }
  return result.output;
}

export async function bindContext(input) {
  const remoteSha = input.remote_sha;
  const baseSha = input.base_sha;
  if (!GIT_SHA.test(remoteSha) || !GIT_SHA.test(baseSha)) throw new TypeError('Remote and base SHAs must be explicit lowercase commit IDs');
  const requestedRemote = normalizeRemoteUrl(input.remote_url);
  const configured = (await git(['remote', 'get-url', '--all', 'origin'])).split(/\r?\n/u).filter(Boolean).map(normalizeRemoteUrl);
  if (!configured.includes(requestedRemote)) throw new TypeError('Context remote URL differs from the configured authoritative remote');

  const head = await git(['rev-parse', '--verify', 'HEAD^{commit}']);
  if (head !== remoteSha) throw new TypeError('Checked-out HEAD differs from the authoritative remote SHA');
  const treeSha = await git(['rev-parse', '--verify', `${remoteSha}^{tree}`]);
  if (input.tree_sha && input.tree_sha !== treeSha) throw new TypeError('Adapter tree SHA differs from the checked-out tree');
  const base = await git(['rev-parse', '--verify', `${baseSha}^{commit}`]);
  if (base !== baseSha) throw new TypeError('Base SHA is not an explicit commit');
  const ancestry = await runNative('git', ['merge-base', '--is-ancestor', baseSha, remoteSha]);
  if (ancestry.exitCode !== 0) throw new TypeError('Base SHA is not an ancestor of the authoritative remote SHA');

  const remoteRows = (await git(['ls-remote', '--exit-code', requestedRemote, input.remote_ref], { blocked: true }))
    .split(/\r?\n/u).filter(Boolean).map((line) => line.split(/\s+/u));
  if (remoteRows.length !== 1 || remoteRows[0][0] !== remoteSha || remoteRows[0][1] !== input.remote_ref) {
    throw new TypeError('Remote ref does not bind exactly one authoritative SHA');
  }
  const context = {
    ...input,
    schema_version: '1.0.0',
    remote_url: requestedRemote,
    tree_sha: treeSha,
  };
  return validateContext(context);
}

export async function atomicWriteContext(output, context) {
  const target = path.resolve(REPOSITORY_ROOT, output);
  const relative = path.relative(REPOSITORY_ROOT, target);
  if (relative.startsWith('..') || path.isAbsolute(relative) || path.extname(target) !== '.json') {
    throw new TypeError('Context output must be a JSON file inside the repository');
  }
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = path.join(path.dirname(target), `.${path.basename(target)}.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(`${canonicalJson(context)}\n`, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, target);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
}

function parseCli(argv) {
  const optionFields = new Map([
    ['--ci-system', 'ci_system'], ['--remote-url', 'remote_url'], ['--remote-ref', 'remote_ref'],
    ['--remote-sha', 'remote_sha'], ['--base-sha', 'base_sha'], ['--tree-sha', 'tree_sha'],
    ['--run-id', 'run_id'], ['--workflow-id', 'workflow_id'], ['--builder-issuer', 'builder_issuer'],
    ['--builder-subject', 'builder_subject'], ['--builder-audience', 'builder_audience'],
    ['--registry-repository', 'registry_repository'], ['--protected-environment', 'protected_environment'],
    ['--environment-class', 'environment_class'], ['--created-at', 'created_at'],
  ]);
  const parsed = { input: {} };
  const seen = new Set();
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) throw new TypeError(`Missing value for ${option ?? 'option'}`);
    if (seen.has(option)) throw new TypeError(`Repeated option: ${option}`);
    seen.add(option);
    if (option === '--adapter-json') parsed.adapterJson = value;
    else if (option === '--output') parsed.output = value;
    else if (optionFields.has(option)) parsed.input[optionFields.get(option)] = value;
    else throw new TypeError(`Unknown option: ${option}`);
  }
  if (!parsed.output) throw new TypeError('Missing --output');
  if (parsed.adapterJson && Object.keys(parsed.input).length > 0) throw new TypeError('Adapter JSON and explicit context fields are mutually exclusive');
  return parsed;
}

function status(status, reasonCode, detailCode) {
  const now = new Date().toISOString();
  return {
    schema_version: '1.0.0', check_id: 'create-ci-context', status, reason_code: reasonCode,
    started_at: now, finished_at: now, evidence: [], tool_observations: [], detail_code: detailCode,
  };
}

async function main(argv) {
  const options = parseCli(argv);
  const input = options.adapterJson
    ? JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options.adapterJson), 'utf8'))
    : options.input;
  const context = await bindContext(input);
  await atomicWriteContext(options.output, context);
  process.stdout.write(`${canonicalJson(context)}\n`);
  return 0;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    if (error instanceof ContextBlockedError) {
      process.stdout.write(`${canonicalJson(status('BLOCKED', 'BLOCKED_EXTERNAL_ENVIRONMENT', error.detailCode))}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`create-ci-context: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
