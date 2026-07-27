import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { lstat, mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import {
  assertNoSensitiveMaterial,
  atomicWriteJson,
  mergeVerdicts,
  validateBlockedEvidence,
  validateVerdictDocument,
} from '../../scripts/acceptance/merge-verdicts.mjs';
import {
  AcceptanceAssertionError,
  resolveRemoteSha,
} from '../../scripts/acceptance/resolve-remote-sha.mjs';
import {
  assertDetachedClean,
  createDetachedWorktree,
} from '../../scripts/acceptance/create-detached-worktree.mjs';
import {
  probeTool,
  REQUIRED_TOOLCHAIN,
} from '../../scripts/acceptance/run-foundation-acceptance.mjs';

const FIXTURE_ROOT = resolve('contracts', 'golden-fixtures', 'acceptance');

test('closed acceptance fixtures enforce status-specific evidence semantics', async () => {
  const blocked = await fixture('blocked-toolchain.json');
  const code = await fixture('code-pass.json');
  const environment = await fixture('environment-blocked.json');
  await validateBlockedEvidence(blocked);
  await validateVerdictDocument(code);
  await validateVerdictDocument(environment);

  await assert.rejects(validateBlockedEvidence({ ...blocked, stdout: 'forbidden' }));
  await assert.rejects(validateBlockedEvidence({
    ...blocked,
    tool: undefined,
    external: {
      capability: 'toolchain',
      authority: 'local-host',
      required_evidence_type: 'tool-version-v1',
    },
  }));
  const unordered = structuredClone(code);
  unordered.checks.reverse();
  await assert.rejects(validateVerdictDocument(unordered), /CHECK_ORDER_INVALID/u);

  const passWithBlocked = structuredClone(code);
  passWithBlocked.checks.push(blocked);
  passWithBlocked.checks.sort((left, right) => left.check_id.localeCompare(right.check_id));
  await assert.rejects(validateVerdictDocument(passWithBlocked));

  const failWithoutAssertion = structuredClone(code);
  failWithoutAssertion.status = 'FAIL';
  failWithoutAssertion.checks[0] = {
    check_id: '01.architecture',
    status: 'FAIL',
    reason_code: 'ASSERTION_FAILED',
    attempted_argv: ['node', 'missing.mjs'],
    attempt_exit_code: 1,
    evidence_digests: ['1'.repeat(64)],
    occurred_at: '2026-07-26T00:00:01Z',
    owner: 'platform-engineering',
  };
  await assert.rejects(validateVerdictDocument(failWithoutAssertion));

  const blockedWithoutEvidence = structuredClone(environment);
  blockedWithoutEvidence.checks = [passCheck('01.branch-protection')];
  await assert.rejects(validateVerdictDocument(blockedWithoutEvidence));
});

test('blocked documents reject raw diagnostic and private material at any depth', () => {
  for (const document of [
    { nested: { stderr: 'x' } },
    { nested: { token: 'x' } },
    { nested: { cookie: 'x' } },
    { nested: { raw_body: 'x' } },
    { nested: { source: 'x' } },
    { nested: { diff: 'x' } },
    { nested: { safe: '-----BEGIN PRIVATE KEY-----' } },
  ]) {
    assert.throws(() => assertNoSensitiveMaterial(document));
  }
});

test('summary requires both PASS verdicts and byte-equal bindings', async (context) => {
  const directory = await mkdtemp(join(tmpdir(), 'accord-verdict-test-'));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const code = await fixture('code-pass.json');
  const environmentPass = asEnvironmentPass(code);
  const codePath = join(directory, 'code.json');
  const environmentPath = join(directory, 'environment.json');
  const summaryPath = join(directory, 'summary.json');
  await atomicWriteJson(codePath, code);
  await atomicWriteJson(environmentPath, environmentPass);
  assert.equal((await mergeVerdicts({ codePath, environmentPath, outputPath: summaryPath })).status, 'PASS');

  const mismatch = structuredClone(environmentPass);
  mismatch.tree_sha = '9'.repeat(40);
  await atomicWriteJson(environmentPath, mismatch);
  const mismatchSummary = await mergeVerdicts({ codePath, environmentPath, outputPath: summaryPath });
  assert.equal(mismatchSummary.status, 'BLOCKED');
  assert.equal(mismatchSummary.binding_equal, false);

  const failure = structuredClone(code);
  failure.status = 'FAIL';
  failure.checks[0] = failCheck('01.architecture');
  await atomicWriteJson(codePath, failure);
  await atomicWriteJson(environmentPath, environmentPass);
  assert.equal((await mergeVerdicts({ codePath, environmentPath, outputPath: summaryPath })).status, 'FAIL');
});

test('all nine unavailable required tools produce independent BLOCKED_TOOLCHAIN evidence', () => {
  assert.deepEqual(REQUIRED_TOOLCHAIN.map((entry) => entry.name), [
    'buf', 'cosign', 'conftest', 'helm', 'kubeconform', 'oras', 'syft', 'tofu', 'trivy',
  ]);
  const remoteSha = 'a'.repeat(40);
  const treeSha = 'b'.repeat(40);
  const outcomes = REQUIRED_TOOLCHAIN.map((specification) => probeTool({
    specification,
    root: process.cwd(),
    remoteSha,
    treeSha,
    run: () => ({
      exitCode: null,
      errorCode: 'ENOENT',
      stdout: '',
      stderr: '',
      durationMs: 1,
    }),
    now: new Date('2026-07-26T00:00:00Z'),
  }));
  assert.equal(new Set(outcomes.map((entry) => entry.check.check_id)).size, 9);
  assert.ok(outcomes.every((entry) => (
    entry.check.status === 'BLOCKED'
    && entry.check.reason_code === 'BLOCKED_TOOLCHAIN'
    && entry.toolchain.status === 'BLOCKED'
  )));
});

test('authoritative resolver fetches one exact remote ref and rejects a different SHA', async (context) => {
  const fixtureRepository = await createRemoteFixture(context);
  const proof = await resolveRemoteSha({
    repository: fixtureRepository.source,
    remoteUrl: fixtureRepository.remote,
    remoteRef: 'refs/heads/main',
    remoteSha: fixtureRepository.sha,
  });
  assert.equal(proof.remote_sha, fixtureRepository.sha);
  assert.equal(proof.tree_sha, fixtureRepository.tree);
  await assert.rejects(
    resolveRemoteSha({
      repository: fixtureRepository.source,
      remoteUrl: fixtureRepository.remote,
      remoteRef: 'refs/heads/main',
      remoteSha: '0'.repeat(40),
    }),
    (error) => error instanceof AcceptanceAssertionError && error.code === 'REMOTE_SHA_MISMATCH',
  );
});

test('temporary worktree is exact, detached, strict-clean, and removed at its scoped path', async (context) => {
  const fixtureRepository = await createRemoteFixture(context);
  await resolveRemoteSha({
    repository: fixtureRepository.source,
    remoteUrl: fixtureRepository.remote,
    remoteRef: 'refs/heads/main',
    remoteSha: fixtureRepository.sha,
  });
  const detached = await createDetachedWorktree({
    repository: fixtureRepository.source,
    remoteSha: fixtureRepository.sha,
    treeSha: fixtureRepository.tree,
    evidenceDirectory: join(fixtureRepository.root, 'evidence'),
  });
  const temporaryParent = detached.temporary_parent;
  await assertDetachedClean({
    worktree: detached.worktree,
    remoteSha: fixtureRepository.sha,
    treeSha: fixtureRepository.tree,
  });
  await writeFile(join(detached.worktree, 'untracked.txt'), 'dirty\n', 'utf8');
  await assert.rejects(assertDetachedClean({
    worktree: detached.worktree,
    remoteSha: fixtureRepository.sha,
    treeSha: fixtureRepository.tree,
  }), /DETACHED_STATUS_DIRTY/u);
  await rm(join(detached.worktree, 'untracked.txt'));
  await detached.cleanup();
  await assert.rejects(lstat(temporaryParent), { code: 'ENOENT' });
});

test('acceptance subprocesses are centralized on argv execution with shell disabled', async () => {
  const scripts = [
    'resolve-remote-sha.mjs',
    'create-detached-worktree.mjs',
    'collect-environment-evidence.mjs',
    'merge-verdicts.mjs',
    'run-foundation-acceptance.mjs',
    'run-foundation-demo.mjs',
  ];
  const sources = await Promise.all(scripts.map((name) => readFile(resolve('scripts', 'acceptance', name), 'utf8')));
  assert.match(sources[0], /spawnSync\(command, args,[\s\S]*shell: false/u);
  for (const source of sources) {
    assert.doesNotMatch(source, /\bexec(?:File)?Sync?\s*\(/u);
    assert.doesNotMatch(source, /shell\s*:\s*true/u);
  }
});

async function fixture(name) {
  return JSON.parse(await readFile(resolve(FIXTURE_ROOT, name), 'utf8'));
}

function passCheck(checkId) {
  return {
    check_id: checkId,
    status: 'PASS',
    attempted_argv: ['verify', checkId],
    attempt_exit_code: 0,
    evidence_digests: ['1'.repeat(64)],
    occurred_at: '2026-07-26T00:00:00Z',
    owner: 'platform-engineering',
  };
}

function failCheck(checkId) {
  return {
    check_id: checkId,
    status: 'FAIL',
    reason_code: 'ASSERTION_FAILED',
    executed: true,
    attempted_argv: ['verify', checkId],
    attempt_exit_code: 1,
    evidence_digests: ['1'.repeat(64)],
    occurred_at: '2026-07-26T00:00:00Z',
    owner: 'platform-engineering',
  };
}

function asEnvironmentPass(code) {
  return {
    ...structuredClone(code),
    kind: 'ENVIRONMENT',
    dependency_lock_digests: [],
    toolchain_results: [],
    checks: [passCheck('01.environment')],
    demo_evidence: [],
  };
}

async function createRemoteFixture(context) {
  const root = await mkdtemp(join(tmpdir(), 'accord-remote-test-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const source = join(root, 'source');
  const remote = join(root, 'authority.git');
  await mkdir(source);
  git(root, ['init', '--bare', remote]);
  git(source, ['init']);
  git(source, ['config', 'user.email', 'acceptance@example.invalid']);
  git(source, ['config', 'user.name', 'Acceptance Test']);
  git(source, ['config', 'core.autocrlf', 'false']);
  await writeFile(join(source, 'README.md'), 'foundation\n', 'utf8');
  git(source, ['add', 'README.md']);
  git(source, ['commit', '-m', 'fixture']);
  git(source, ['push', remote, 'HEAD:refs/heads/main']);
  const sha = git(source, ['rev-parse', 'HEAD']);
  const tree = git(source, ['rev-parse', 'HEAD^{tree}']);
  return { root, source, remote, sha, tree };
}

function git(cwd, args) {
  const result = spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    shell: false,
    windowsHide: true,
    timeout: 30_000,
  });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, `git ${args[0]} failed`);
  return result.stdout.trim();
}
