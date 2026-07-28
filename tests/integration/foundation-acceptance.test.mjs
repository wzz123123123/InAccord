import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { lstat, mkdtemp, mkdir, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import {
  assertNoSensitiveMaterial,
  atomicWriteJson,
  canonicalJson,
  ENVIRONMENT_CHECK_IDS,
  mergeVerdicts,
  sha256,
  validateBlockedEvidence,
  validateBootstrapResult,
  validateCheckEvidence,
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
import { collectEnvironmentEvidence } from '../../scripts/acceptance/collect-environment-evidence.mjs';
import {
  executeRepositoryCheck,
  parseAcceptanceCli,
  probeTool,
  recordBootstrapFailure,
  REPOSITORY_CHECKS,
  REQUIRED_TOOLCHAIN,
  validateReleaseInputBindings,
} from '../../scripts/acceptance/run-foundation-acceptance.mjs';
import { verifyOfflineDependencies } from '../../scripts/verification/verify-offline-dependencies.mjs';

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

  const blockedWithoutArtifacts = structuredClone(code);
  blockedWithoutArtifacts.status = 'BLOCKED';
  blockedWithoutArtifacts.image_lock_digest = null;
  blockedWithoutArtifacts.release_manifest_digest = null;
  blockedWithoutArtifacts.checks = [blocked];
  await validateVerdictDocument(blockedWithoutArtifacts);

  const passWithoutImageLock = structuredClone(code);
  passWithoutImageLock.image_lock_digest = null;
  await assert.rejects(validateVerdictDocument(passWithoutImageLock));

  const failWithoutArtifacts = structuredClone(code);
  failWithoutArtifacts.status = 'FAIL';
  failWithoutArtifacts.image_lock_digest = null;
  failWithoutArtifacts.release_manifest_digest = null;
  failWithoutArtifacts.checks[0] = failCheck('01.architecture');
  await assert.rejects(validateVerdictDocument(failWithoutArtifacts));

  const failWithExplicitMissingImage = structuredClone(code);
  failWithExplicitMissingImage.status = 'FAIL';
  failWithExplicitMissingImage.image_lock_digest = null;
  const imageCheck = failWithExplicitMissingImage.checks.findIndex(
    (entry) => entry.check_id === '00.image-lock',
  );
  failWithExplicitMissingImage.checks[imageCheck] = failCheck('00.image-lock');
  await validateVerdictDocument(failWithExplicitMissingImage);
});

test('PASS verdicts require the complete fixed code and environment inventories', async () => {
  const code = await fixture('code-pass.json');
  const incompleteCode = structuredClone(code);
  incompleteCode.checks.pop();
  await assert.rejects(
    validateVerdictDocument(incompleteCode),
    /CODE_(?:CHECK|TOOLCHAIN)_INVENTORY_INVALID/u,
  );
  const environment = asEnvironmentPass(code);
  environment.checks.pop();
  await assert.rejects(
    validateVerdictDocument(environment),
    /ENVIRONMENT_CHECK_INVENTORY_INVALID/u,
  );
});

test('environment collection rejects a caller-supplied trust-root override', async (context) => {
  const directory = await mkdtemp(join(tmpdir(), 'accord-trust-root-test-'));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const code = bindEvidenceBundle(await fixture('code-pass.json'));
  const codePath = join(directory, 'code.json');
  await atomicWriteJson(codePath, code);
  await assert.rejects(
    collectEnvironmentEvidence({
      codeVerdictPath: codePath,
      receiptsDirectory: join(directory, 'receipts'),
      trustStorePath: join(directory, 'caller-controlled.json'),
      outputPath: join(directory, 'environment.json'),
    }),
    /TRUST_STORE_OVERRIDE_FORBIDDEN/u,
  );
});

test('outer acceptance independently validates the detached evidence bundle', async (context) => {
  const acceptance = await import('../../scripts/acceptance/run-foundation-acceptance.mjs');
  assert.equal(typeof acceptance.verifyDetachedAcceptanceEvidence, 'function');
  const directory = await mkdtemp(join(tmpdir(), 'accord-detached-evidence-test-'));
  context.after(() => rm(directory, { recursive: true, force: true }));
  await writeFile(join(directory, 'summary.json'), '{"status":"PASS"}\n', 'utf8');
  await assert.rejects(
    acceptance.verifyDetachedAcceptanceEvidence({
      evidenceDirectory: directory,
      remoteUrl: 'https://git.example.com/accord/accord.git',
      remoteRef: 'refs/heads/main',
      remoteSha: 'a'.repeat(40),
      treeSha: 'b'.repeat(40),
    }),
    /DETACHED_EVIDENCE_/u,
  );
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
  const code = bindEvidenceBundle(await fixture('code-pass.json'));
  const environmentPass = bindEvidenceBundle(asEnvironmentPass(code));
  const codePath = join(directory, 'code.json');
  const environmentPath = join(directory, 'environment.json');
  const summaryPath = join(directory, 'summary.json');
  await atomicWriteJson(codePath, code);
  await atomicWriteJson(environmentPath, environmentPass);
  assert.equal((await mergeVerdicts({ codePath, environmentPath, outputPath: summaryPath })).status, 'PASS');

  const mismatch = structuredClone(environmentPass);
  mismatch.tree_sha = '9'.repeat(40);
  bindEvidenceBundle(mismatch);
  await atomicWriteJson(environmentPath, mismatch);
  const mismatchSummary = await mergeVerdicts({ codePath, environmentPath, outputPath: summaryPath });
  assert.equal(mismatchSummary.status, 'BLOCKED');
  assert.equal(mismatchSummary.binding_equal, false);

  const failure = structuredClone(code);
  failure.status = 'FAIL';
  failure.checks = failure.checks.map((check) => (
    check.check_id === '01.architecture' ? failCheck('01.architecture') : check
  ));
  bindEvidenceBundle(failure);
  await atomicWriteJson(codePath, failure);
  await atomicWriteJson(environmentPath, environmentPass);
  assert.equal((await mergeVerdicts({ codePath, environmentPath, outputPath: summaryPath })).status, 'FAIL');

  const tampered = structuredClone(code);
  tampered.checks[0].evidence_digests = ['9'.repeat(64)];
  await atomicWriteJson(codePath, tampered);
  await assert.rejects(
    mergeVerdicts({ codePath, environmentPath, outputPath: summaryPath }),
    /VERDICT_EVIDENCE_BUNDLE_DIGEST_MISMATCH/u,
  );
});

test('full pinned toolchain produces independent BLOCKED_TOOLCHAIN evidence', async () => {
  assert.deepEqual(REQUIRED_TOOLCHAIN.map((entry) => entry.name), [
    'buf', 'cosign', 'conftest', 'docker', 'docker-buildx', 'docker-compose', 'git',
    'gradle-wrapper', 'helm', 'java', 'kubeconform', 'node', 'oras', 'pnpm', 'python',
    'syft', 'tofu', 'trivy', 'uv',
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
  assert.equal(new Set(outcomes.map((entry) => entry.check.check_id)).size, REQUIRED_TOOLCHAIN.length);
  assert.ok(outcomes.every((entry) => (
    entry.check.status === 'BLOCKED'
    && entry.check.reason_code === 'BLOCKED_TOOLCHAIN'
    && entry.toolchain.status === 'BLOCKED'
  )));
  await Promise.all(outcomes.map((entry) => validateCheckEvidence(entry.check)));

  const mismatched = probeTool({
    specification: REQUIRED_TOOLCHAIN[0],
    root: process.cwd(),
    remoteSha,
    treeSha,
    run: () => ({
      exitCode: 0,
      errorCode: null,
      stdout: '0.0.1',
      stderr: '',
      durationMs: 1,
    }),
    now: new Date('2026-07-26T00:00:00Z'),
  });
  assert.equal(mismatched.toolchain.status, 'BLOCKED');
  assert.equal(mismatched.check.reason_code, 'BLOCKED_TOOLCHAIN');
  assert.equal(mismatched.check.tool.observed_version, '0.0.1');
  await validateCheckEvidence(mismatched.check);

  const java = REQUIRED_TOOLCHAIN.find((entry) => entry.name === 'java');
  const exactJava = probeTool({
    specification: java,
    root: process.cwd(),
    remoteSha,
    treeSha,
    run: () => ({
      exitCode: 0,
      errorCode: null,
      stdout: 'openjdk 21.0.11 2026-04-21 LTS',
      stderr: 'OpenJDK Runtime Environment Temurin-21.0.11+10 (build 21.0.11+10-LTS)',
      durationMs: 1,
    }),
    now: new Date('2026-07-26T00:00:00Z'),
  });
  assert.equal(exactJava.toolchain.status, 'PASS');
  await validateCheckEvidence(exactJava.check);
});

test('detached CLI accepts and preserves external release evidence inputs', () => {
  const parsed = parseAcceptanceCli([
    '--inside-detached',
    '--remote-url', 'https://git.example.com/accord/accord.git',
    '--remote-ref', 'refs/heads/main',
    '--remote-sha', 'a'.repeat(40),
    '--tree-sha', 'b'.repeat(40),
    '--evidence-dir', 'C:/external/acceptance',
    '--ci-context', 'C:/external/context.json',
    '--release-manifest', 'C:/external/release-manifest.json',
    '--release-evidence-index', 'C:/external/evidence-index.json',
    '--release-evidence-root', 'C:/external',
  ]);
  assert.equal(parsed.ciContextPath, 'C:/external/context.json');
  assert.equal(parsed.releaseEvidenceIndexPath, 'C:/external/evidence-index.json');
  assert.equal(parsed.releaseEvidenceRootPath, 'C:/external');
});

test('release context and manifest must bind to the authoritative acceptance proof', () => {
  const remoteSha = 'a'.repeat(40);
  const treeSha = 'b'.repeat(40);
  const context = { remote_sha: remoteSha, tree_sha: treeSha };
  const manifest = { remote_sha: remoteSha, tree_sha: treeSha };
  assert.doesNotThrow(() => validateReleaseInputBindings({ context, manifest, remoteSha, treeSha }));
  assert.throws(
    () => validateReleaseInputBindings({
      context: { ...context, remote_sha: 'c'.repeat(40) },
      manifest,
      remoteSha,
      treeSha,
    }),
    /RELEASE_INPUT_AUTHORITY_MISMATCH/u,
  );
});

test('offline dependency gate covers Java, browser, Python, and protobuf without a shell', async () => {
  const dependency = REPOSITORY_CHECKS.find((entry) => entry.checkId === '03.dependency-verification');
  const telemetry = REPOSITORY_CHECKS.find((entry) => entry.checkId === '05.telemetry');
  const release = REPOSITORY_CHECKS.find((entry) => entry.checkId === '07.release-chain');
  assert.deepEqual(dependency.requiredTools, ['buf', 'gradle-wrapper', 'java', 'node', 'pnpm', 'python', 'uv']);
  assert.deepEqual(telemetry.requiredTools, ['gradle-wrapper', 'java']);
  assert.deepEqual(release.requiredTools, ['cosign', 'git', 'oras', 'syft', 'trivy']);
  const source = await readFile(resolve('scripts', 'verification', 'verify-offline-dependencies.mjs'), 'utf8');
  for (const required of [
    'GradleWrapperMain', '--offline', '--dependency-verification=strict',
    'install', '--frozen-lockfile', 'typecheck', 'contracts:lint',
    'sync', '--frozen', 'ruff', 'mypy', 'pytest', 'lint', 'build',
  ]) assert.ok(source.includes(required), `offline gate is missing ${required}`);
  assert.doesNotMatch(source, /shell\s*:\s*true/u);
});

test('offline dependency gate invokes pnpm through Corepack on Windows', async () => {
  const nodeExecutable = 'C:\\runtime\\node.exe';
  const invocations = [];
  await verifyOfflineDependencies({
    platform: 'win32',
    nodeExecutable,
    run: async (executable, argv) => {
      invocations.push({ executable, argv });
      return { exitCode: 0, errorCode: null };
    },
  });

  const pnpmInvocations = invocations.filter(({ argv }) => (
    argv.includes('install') || argv.includes('contracts:lint') || argv.includes('--if-present')
  ));
  assert.equal(pnpmInvocations.length, 4);
  for (const invocation of pnpmInvocations) {
    assert.equal(invocation.executable, nodeExecutable);
    assert.equal(
      invocation.argv[0],
      'C:\\runtime\\node_modules\\corepack\\dist\\pnpm.js',
    );
  }
});

test('Windows production gates invoke pnpm through Node and Corepack', async () => {
  const preflight = await import('../../scripts/verification/preflight.mjs');
  assert.equal(typeof preflight.nativeToolInvocation, 'function');
  const nodeExecutable = 'C:\\runtime\\node.exe';
  const expectedEntrypoint = 'C:\\runtime\\node_modules\\corepack\\dist\\pnpm.js';
  assert.deepEqual(
    preflight.nativeToolInvocation('pnpm', ['--version'], {
      platform: 'win32',
      nodeExecutable,
    }),
    { executable: nodeExecutable, argv: [expectedEntrypoint, '--version'] },
  );

  let captured;
  const pnpm = REQUIRED_TOOLCHAIN.find((entry) => entry.name === 'pnpm');
  const outcome = probeTool({
    specification: pnpm,
    root: process.cwd(),
    remoteSha: 'a'.repeat(40),
    treeSha: 'b'.repeat(40),
    platform: 'win32',
    nodeExecutable,
    run: (executable, argv) => {
      captured = { executable, argv };
      return commandResult({ exitCode: 0, stdout: '10.12.4' });
    },
    now: new Date('2026-07-26T00:00:00Z'),
  });
  assert.deepEqual(captured, {
    executable: nodeExecutable,
    argv: [expectedEntrypoint, '--version'],
  });
  assert.equal(outcome.toolchain.status, 'PASS');

  const ciSource = await readFile(resolve('scripts', 'ci', 'verify.mjs'), 'utf8');
  assert.match(ciSource, /fullPreflight[\s\S]*nativeToolInvocation\(definition[.]executable/u);
  assert.match(ciSource, /async function gate[\s\S]*nativeToolInvocation\(executable/u);
});

test('authoritative acceptance bootstraps locked offline dependencies before detached execution', async () => {
  const acceptance = await import('../../scripts/acceptance/run-foundation-acceptance.mjs');
  assert.equal(typeof acceptance.detachedDependencyBootstrapInvocation, 'function');
  const invocation = acceptance.detachedDependencyBootstrapInvocation({
    platform: 'win32',
    nodeExecutable: 'C:\\runtime\\node.exe',
    storeDirectory: 'D:\\pnpm-store\\v10',
  });
  assert.deepEqual(invocation, {
    executable: 'C:\\runtime\\node.exe',
    argv: [
      'C:\\runtime\\node_modules\\corepack\\dist\\pnpm.js',
      'install',
      '--frozen-lockfile',
      '--offline',
      '--store-dir',
      'D:\\pnpm-store\\v10',
    ],
  });
  const source = await readFile(resolve('scripts', 'acceptance', 'run-foundation-acceptance.mjs'), 'utf8');
  assert.match(
    source,
    /resolvePnpmStoreDirectory[\s\S]*detachedDependencyBootstrapInvocation[\s\S]*cwd:\s*worktree[\s\S]*assertDetachedClean[\s\S]*const child/u,
  );
});

test('detached cleanup removes residual files after Git unregisters the worktree', async (context) => {
  const detached = await import('../../scripts/acceptance/create-detached-worktree.mjs');
  assert.equal(typeof detached.removeDetachedWorktree, 'function');
  const temporaryRoot = await realpath(tmpdir());
  const parent = await mkdtemp(join(temporaryRoot, 'accord-foundation-'));
  context.after(() => rm(parent, { recursive: true, force: true }));
  const worktree = join(parent, 'source');
  await mkdir(join(worktree, 'node_modules'), { recursive: true });
  await writeFile(join(worktree, 'node_modules', 'residual'), 'ignored\n', 'utf8');

  await detached.removeDetachedWorktree({
    repositoryRoot: process.cwd(),
    temporaryRoot,
    parent,
    worktree,
    run: (_command, argv) => {
      if (argv[1] === 'remove') return commandResult({ exitCode: 1 });
      assert.deepEqual(argv, ['worktree', 'list', '--porcelain', '-z']);
      return commandResult({ exitCode: 0, stdout: '' });
    },
  });
  await assert.rejects(lstat(parent), { code: 'ENOENT' });
});

test('remote bootstrap failure writes closed auditable evidence without inventing a tree SHA', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-bootstrap-result-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const remoteSha = 'a'.repeat(40);
  const result = await recordBootstrapFailure({
    repository: root,
    outputBase: join(root, 'acceptance'),
    remoteUrl: 'https://git.example.com/accord/accord.git',
    remoteRef: 'refs/heads/main',
    remoteSha,
  }, Object.assign(new Error('raw network diagnostics must not be persisted'), {
    code: 'REMOTE_AUTHORITY_UNAVAILABLE',
    verdictStatus: 'BLOCKED',
    reasonCode: 'BLOCKED_REMOTE_AUTHORITY',
  }), new Date('2026-07-27T00:00:00Z'));
  assert.equal(result.status, 'BLOCKED');
  assert.equal(Object.hasOwn(result, 'tree_sha'), false);
  await validateBootstrapResult(result);
  const stored = await readFile(join(root, 'acceptance', remoteSha, 'remote-authority-result.json'), 'utf8');
  assert.doesNotMatch(stored, /raw network diagnostics/u);
});

test('repository checks preserve external and missing-tool blockers while missing source fails', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-repository-check-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const remoteSha = 'a'.repeat(40);
  const treeSha = 'b'.repeat(40);
  const now = new Date('2026-07-26T00:00:00Z');
  const release = REPOSITORY_CHECKS.find((entry) => entry.checkId === '07.release-chain');
  const dependency = REPOSITORY_CHECKS.find((entry) => entry.checkId === '03.dependency-verification');

  for (const specification of [release, dependency]) {
    const required = join(root, specification.requiredPath);
    await mkdir(dirname(required), { recursive: true });
    await writeFile(required, 'fixture', 'utf8');
  }

  const external = await executeRepositoryCheck({
    root,
    specification: release,
    remoteSha,
    treeSha,
    run: () => commandResult({ exitCode: 2 }),
    now,
  });
  assert.equal(external.status, 'BLOCKED');
  assert.equal(external.reason_code, 'BLOCKED_REGISTRY');
  await validateCheckEvidence(external);

  const toolchain = await executeRepositoryCheck({
    root,
    specification: dependency,
    remoteSha,
    treeSha,
    run: () => commandResult({ exitCode: null, errorCode: 'ENOENT' }),
    now,
  });
  assert.equal(toolchain.status, 'BLOCKED');
  assert.equal(toolchain.reason_code, 'BLOCKED_TOOLCHAIN');
  assert.equal(toolchain.tool.name, 'java');
  await validateCheckEvidence(toolchain);

  await rm(join(root, release.requiredPath));
  const missing = await executeRepositoryCheck({
    root,
    specification: release,
    remoteSha,
    treeSha,
    run: () => assert.fail('missing repository checks must not execute'),
    now,
  });
  assert.equal(missing.status, 'FAIL');
  assert.equal(missing.reason_code, 'ASSERTION_FAILED');
  await validateCheckEvidence(missing);
});

test('atomic JSON writes remove temporary files when serialization fails', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-atomic-json-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  await assert.rejects(atomicWriteJson(join(root, 'result.json'), { unsupported: 1n }));
  assert.deepEqual(await readdir(root), []);
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
    checks: ENVIRONMENT_CHECK_IDS.map((checkId) => passCheck(checkId)),
    demo_evidence: [],
  };
}

function bindEvidenceBundle(verdict) {
  const copy = { ...verdict };
  delete copy.evidence_bundle_digest;
  verdict.evidence_bundle_digest = sha256(canonicalJson(copy));
  return verdict;
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

function commandResult({ exitCode, errorCode = null, stdout = '', stderr = '' }) {
  return {
    exitCode,
    errorCode,
    stdout,
    stderr,
    durationMs: 1,
  };
}
