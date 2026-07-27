import { spawn } from 'node:child_process';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { randomUUID } from 'node:crypto';

const ROOT = path.resolve(import.meta.dirname, '..', '..', '..');
const SHA = /^[0-9a-f]{40}$/u;
const SEMVER_TAG = /^refs\/tags\/v(?:0|[1-9][0-9]*)[.](?:0|[1-9][0-9]*)[.](?:0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?$/u;

function canonicalJson(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
}

function requiredEnvironment(name, environment = process.env) {
  const value = environment[name];
  if (!value) throw new Error(`BLOCKED_EXTERNAL_ENVIRONMENT:${name}`);
  return value;
}

function parseCli(argv) {
  const parsed = {};
  const allowed = new Set(['--expected-repository', '--registry-repository', '--protected-environment', '--environment-class', '--output']);
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!allowed.has(option) || !value || value.startsWith('--')) throw new TypeError(`Invalid adapter option: ${option}`);
    if (parsed[option]) throw new TypeError(`Repeated adapter option: ${option}`);
    parsed[option] = value;
  }
  for (const option of allowed) if (!parsed[option]) throw new TypeError(`Missing ${option}`);
  return parsed;
}

function git(argv) {
  return new Promise((resolve, reject) => {
    let output = '';
    const child = spawn('git', argv, { cwd: ROOT, env: process.env, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', (chunk) => { output += chunk.toString('utf8'); });
    child.stderr.on('data', (chunk) => { output += chunk.toString('utf8'); });
    child.once('error', () => reject(new Error('BLOCKED_TOOLCHAIN:GIT')));
    child.once('close', (code) => code === 0 ? resolve(output.trim()) : reject(new Error('ASSERTION_FAILED:GIT')));
  });
}

function normalizeRemote(value) {
  const parsed = new URL(value);
  if (parsed.password || parsed.search || parsed.hash) throw new TypeError('Configured remote contains credentials or parameters');
  parsed.hostname = parsed.hostname.toLowerCase();
  parsed.pathname = parsed.pathname.replace(/\/+$/u, '').replace(/[.]git$/u, '') + '.git';
  return parsed.toString().replace(/\/$/u, '');
}

async function eventBase(eventName, event, sha) {
  const candidate = eventName === 'pull_request' ? event.pull_request?.base?.sha : event.before;
  if (typeof candidate === 'string' && SHA.test(candidate) && !/^0{40}$/u.test(candidate)) return candidate;
  const parent = await git(['rev-parse', '--verify', `${sha}^1`]);
  if (!SHA.test(parent)) throw new TypeError('Event has no explicit base commit');
  return parent;
}

async function atomicWrite(output, document) {
  const target = path.resolve(ROOT, output);
  const relative = path.relative(ROOT, target);
  if (relative.startsWith('..') || path.isAbsolute(relative) || path.extname(target) !== '.json') throw new TypeError('Adapter output must be repository-local JSON');
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = path.join(path.dirname(target), `.${path.basename(target)}.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(`${canonicalJson(document)}\n`, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, target);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
}

export async function createAdapterContext(options, environment = process.env) {
  const required = (name) => requiredEnvironment(name, environment);
  const repository = required('GITHUB_REPOSITORY');
  if (repository !== options['--expected-repository']) throw new TypeError('Source repository identity is not allowlisted');
  const serverUrl = required('GITHUB_SERVER_URL');
  if (serverUrl !== 'https://github.com') throw new TypeError('Unexpected source repository server');
  const sha = required('GITHUB_SHA');
  const remoteRef = required('GITHUB_REF');
  const eventName = required('GITHUB_EVENT_NAME');
  if (!SHA.test(sha) || !/^refs\/(?:heads|tags|pull)\//u.test(remoteRef)) throw new TypeError('Event ref or SHA is not canonical');
  if (!['push', 'pull_request', 'workflow_dispatch'].includes(eventName)) throw new TypeError('Source event is not protected');
  if (options['--environment-class'] === 'production' && !SEMVER_TAG.test(remoteRef)) throw new TypeError('Production release ref is not a protected semantic tag');

  const eventPath = required('GITHUB_EVENT_PATH');
  const event = JSON.parse(await readFile(eventPath, 'utf8'));
  const eventSha = eventName === 'pull_request' ? event.after ?? sha : event.after ?? event.pull_request?.merge_commit_sha ?? sha;
  if (eventSha !== sha) throw new TypeError('Event payload SHA differs from the checkout SHA');
  const head = await git(['rev-parse', '--verify', 'HEAD^{commit}']);
  if (head !== sha) throw new TypeError('Checkout HEAD differs from the event SHA');
  const treeSha = await git(['rev-parse', '--verify', `${sha}^{tree}`]);
  const baseSha = await eventBase(eventName, event, sha);

  const expectedRemote = normalizeRemote(`${serverUrl}/${repository}.git`);
  const configuredRemote = normalizeRemote(await git(['remote', 'get-url', 'origin']));
  if (configuredRemote !== expectedRemote) throw new TypeError('Configured remote differs from the event repository');
  if (options['--environment-class'] === 'production') required('ACTIONS_ID_TOKEN_REQUEST_URL');

  const protectedEnvironment = options['--protected-environment'];
  const builderSubject = options['--environment-class'] === 'production'
    ? `repo:${repository}:environment:${protectedEnvironment}`
    : `repo:${repository}:ref:${remoteRef}`;
  return {
    schema_version: '1.0.0',
    ci_system: 'github-actions',
    remote_url: expectedRemote,
    remote_ref: remoteRef,
    remote_sha: sha,
    base_sha: baseSha,
    tree_sha: treeSha,
    run_id: `${required('GITHUB_RUN_ID')}.${required('GITHUB_RUN_ATTEMPT')}`,
    workflow_id: required('GITHUB_WORKFLOW'),
    builder_issuer: 'https://token.actions.githubusercontent.com',
    builder_subject: builderSubject,
    builder_audience: 'sigstore',
    registry_repository: options['--registry-repository'],
    protected_environment: protectedEnvironment,
    environment_class: options['--environment-class'],
    created_at: new Date().toISOString(),
  };
}

async function main(argv) {
  const options = parseCli(argv);
  const context = await createAdapterContext(options);
  await atomicWrite(options['--output'], context);
  process.stdout.write(`${canonicalJson(context)}\n`);
  return 0;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    const blocked = message.startsWith('BLOCKED_');
    process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: blocked ? 'BLOCKED' : 'FAIL', reason_code: blocked ? message.split(':', 1)[0] : 'ASSERTION_FAILED' })}\n`);
    process.exitCode = blocked ? 2 : 1;
  }
}
