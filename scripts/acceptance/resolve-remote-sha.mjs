import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { atomicWriteJson, canonicalJson, sha256 } from './merge-verdicts.mjs';

const OBJECT_ID = /^[a-f0-9]{40,64}$/u;
const FULL_REF = /^refs\/[A-Za-z0-9][A-Za-z0-9._/-]{1,1023}$/u;

export class AcceptanceAssertionError extends Error {
  constructor(code) {
    super(code);
    this.name = 'AcceptanceAssertionError';
    this.code = code;
    this.verdictStatus = 'FAIL';
    this.reasonCode = 'ASSERTION_FAILED';
  }
}

export class AcceptanceBlockedError extends Error {
  constructor(code, reasonCode = 'BLOCKED_REMOTE_AUTHORITY') {
    super(code);
    this.name = 'AcceptanceBlockedError';
    this.code = code;
    this.verdictStatus = 'BLOCKED';
    this.reasonCode = reasonCode;
  }
}

export function validateRemoteRequest({ remoteUrl, remoteRef, remoteSha }) {
  if (
    typeof remoteUrl !== 'string'
    || remoteUrl.length === 0
    || remoteUrl.length > 4096
    || remoteUrl.startsWith('-')
    || /[\u0000\r\n]/u.test(remoteUrl)
    || /^(?:https?|ssh):\/\/[^/\s]*:[^/@\s]+@/iu.test(remoteUrl)
    || /[?&](?:access_token|token|key|secret|password)=/iu.test(remoteUrl)
  ) {
    throw new AcceptanceAssertionError('REMOTE_URL_INVALID');
  }
  if (
    typeof remoteRef !== 'string'
    || !FULL_REF.test(remoteRef)
    || remoteRef.includes('..')
    || remoteRef.includes('@{')
    || remoteRef.includes('//')
    || remoteRef.endsWith('/')
    || remoteRef.endsWith('.')
    || remoteRef.endsWith('.lock')
  ) {
    throw new AcceptanceAssertionError('REMOTE_REF_INVALID');
  }
  if (typeof remoteSha !== 'string' || !OBJECT_ID.test(remoteSha)) {
    throw new AcceptanceAssertionError('REMOTE_SHA_INVALID');
  }
  return { remoteUrl, remoteRef, remoteSha };
}

export function runNative(command, args, options = {}) {
  if (typeof command !== 'string' || !Array.isArray(args) || args.some((entry) => typeof entry !== 'string')) {
    throw new AcceptanceAssertionError('NATIVE_COMMAND_INVALID');
  }
  const started = Date.now();
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    encoding: 'utf8',
    shell: false,
    windowsHide: true,
    timeout: options.timeout ?? 60_000,
    maxBuffer: options.maxBuffer ?? 4 * 1024 * 1024,
  });
  return {
    command,
    args: [...args],
    exitCode: Number.isInteger(result.status) ? result.status : null,
    signal: result.signal ?? null,
    errorCode: typeof result.error?.code === 'string' ? result.error.code : null,
    stdout: typeof result.stdout === 'string' ? result.stdout : '',
    stderr: typeof result.stderr === 'string' ? result.stderr : '',
    durationMs: Date.now() - started,
  };
}

export function parseLsRemote(output, expectedRef) {
  const records = [];
  for (const line of output.split(/\r?\n/u)) {
    if (line.length === 0) continue;
    const match = /^([a-f0-9]{40,64})\t([^\t\r\n]+)$/u.exec(line);
    if (!match) throw new AcceptanceAssertionError('REMOTE_RESPONSE_INVALID');
    records.push({ sha: match[1], ref: match[2] });
  }
  return records.filter((record) => record.ref === expectedRef);
}

export async function resolveRemoteSha({
  repository = process.cwd(),
  remoteUrl,
  remoteRef,
  remoteSha,
  expectedTreeSha,
  run = runNative,
}) {
  validateRemoteRequest({ remoteUrl, remoteRef, remoteSha });
  if (expectedTreeSha !== undefined && !OBJECT_ID.test(expectedTreeSha)) {
    throw new AcceptanceAssertionError('EXPECTED_TREE_SHA_INVALID');
  }
  const cwd = resolve(repository);
  const gitEnvironment = { ...process.env, GIT_TERMINAL_PROMPT: '0' };
  const remoteQuery = run('git', ['ls-remote', '--exit-code', remoteUrl, remoteRef], {
    cwd,
    env: gitEnvironment,
  });
  ensureRemoteCommand(remoteQuery, 'REMOTE_QUERY_UNAVAILABLE');
  const matches = parseLsRemote(remoteQuery.stdout, remoteRef);
  if (matches.length !== 1) {
    throw new AcceptanceAssertionError('REMOTE_REF_NOT_UNIQUE');
  }
  if (matches[0].sha !== remoteSha) {
    throw new AcceptanceAssertionError('REMOTE_SHA_MISMATCH');
  }

  const fetch = run('git', ['fetch', '--no-tags', '--force', remoteUrl, remoteRef], {
    cwd,
    env: gitEnvironment,
  });
  ensureRemoteCommand(fetch, 'REMOTE_FETCH_UNAVAILABLE');
  const fetchedHead = requireSuccessfulGit(
    run('git', ['rev-parse', '--verify', 'FETCH_HEAD^{commit}'], { cwd, env: gitEnvironment }),
    'FETCHED_COMMIT_UNVERIFIED',
  );
  if (fetchedHead !== remoteSha) {
    throw new AcceptanceAssertionError('REMOTE_REF_MOVED_DURING_FETCH');
  }
  requireSuccessfulGit(
    run('git', ['cat-file', '-e', `${remoteSha}^{commit}`], { cwd, env: gitEnvironment }),
    'REMOTE_COMMIT_MISSING',
    false,
  );
  const treeSha = requireSuccessfulGit(
    run('git', ['rev-parse', `${remoteSha}^{tree}`], { cwd, env: gitEnvironment }),
    'REMOTE_TREE_UNVERIFIED',
  );
  if (!OBJECT_ID.test(treeSha)) {
    throw new AcceptanceAssertionError('REMOTE_TREE_INVALID');
  }
  if (expectedTreeSha !== undefined && treeSha !== expectedTreeSha) {
    throw new AcceptanceAssertionError('REMOTE_TREE_MISMATCH');
  }
  const proof = {
    schema_version: '1.0.0',
    remote_url: remoteUrl,
    remote_ref: remoteRef,
    remote_sha: remoteSha,
    tree_sha: treeSha,
  };
  return { ...proof, proof_digest: sha256(canonicalJson(proof)) };
}

function ensureRemoteCommand(result, code) {
  if (result.errorCode === 'ENOENT') {
    throw new AcceptanceBlockedError('GIT_TOOLCHAIN_UNAVAILABLE', 'BLOCKED_TOOLCHAIN');
  }
  if (result.errorCode !== null || result.exitCode !== 0) {
    throw new AcceptanceBlockedError(code);
  }
}

function requireSuccessfulGit(result, code, capture = true) {
  if (result.errorCode === 'ENOENT') {
    throw new AcceptanceBlockedError('GIT_TOOLCHAIN_UNAVAILABLE', 'BLOCKED_TOOLCHAIN');
  }
  if (result.errorCode !== null || result.exitCode !== 0) {
    throw new AcceptanceAssertionError(code);
  }
  const value = capture ? result.stdout.trim() : '';
  if (capture && /[\r\n]/u.test(value)) {
    throw new AcceptanceAssertionError(code);
  }
  return value;
}

function parseCli(argv) {
  const options = { repository: process.cwd() };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (value === undefined) throw new AcceptanceAssertionError('ARGUMENTS_INVALID');
    const key = {
      '--repository': 'repository',
      '--remote-url': 'remoteUrl',
      '--remote-ref': 'remoteRef',
      '--remote-sha': 'remoteSha',
      '--expected-tree-sha': 'expectedTreeSha',
      '--output': 'output',
    }[flag];
    if (!key) throw new AcceptanceAssertionError('ARGUMENTS_INVALID');
    options[key] = value;
  }
  if (!options.remoteUrl || !options.remoteRef || !options.remoteSha) {
    throw new AcceptanceAssertionError('ARGUMENTS_INVALID');
  }
  return options;
}

async function main() {
  try {
    const options = parseCli(process.argv.slice(2));
    const proof = await resolveRemoteSha(options);
    if (options.output) await atomicWriteJson(options.output, proof);
    process.stdout.write(`remote-authority: PASS ${proof.remote_sha} ${proof.tree_sha}\n`);
  } catch (error) {
    const status = error?.verdictStatus === 'BLOCKED' ? 'BLOCKED' : 'FAIL';
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`remote-authority: ${status} (${code})\n`);
    process.exitCode = status === 'BLOCKED' ? 2 : 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
