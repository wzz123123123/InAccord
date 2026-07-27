import { readdir, readFile, lstat } from 'node:fs/promises';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { assertDetachedClean, isPathInside, withDetachedWorktree } from './create-detached-worktree.mjs';
import { collectEnvironmentEvidence } from './collect-environment-evidence.mjs';
import {
  atomicWriteJson,
  canonicalJson,
  mergeVerdicts,
  sha256,
  validateVerdictDocument,
  verdictExitCode,
} from './merge-verdicts.mjs';
import {
  AcceptanceAssertionError,
  resolveRemoteSha,
  runNative,
} from './resolve-remote-sha.mjs';
import { runFoundationDemo } from './run-foundation-demo.mjs';

const OWNER = 'platform-engineering';
const POLICY_VERSION = 'accord-foundation-evidence-v1';
export const REQUIRED_TOOLCHAIN = [
  tool('buf', '1.55.1', ['--version']),
  tool('cosign', '2.5.0', ['version']),
  tool('conftest', '0.61.2', ['--version']),
  tool('helm', '3.17.3', ['version', '--short']),
  tool('kubeconform', '0.7.0', ['-v']),
  tool('oras', '1.2.2', ['version']),
  tool('syft', '1.27.1', ['version']),
  tool('tofu', '1.9.1', ['version']),
  tool('trivy', '0.63.0', ['--version']),
];
const REPOSITORY_CHECKS = [
  commandCheck('01.architecture', 'tests/architecture/verify-platform-foundation.mjs', ['node', 'tests/architecture/verify-platform-foundation.mjs']),
  commandCheck('03.dependency-verification', 'scripts/verification/preflight.mjs', ['node', 'scripts/verification/preflight.mjs', '--check-id', 'foundation-acceptance']),
  commandCheck('04.image-and-temporal', 'scripts/images/verify-images.mjs', ['node', 'scripts/images/verify-images.mjs', '--scope', 'all']),
  commandCheck('05.telemetry', 'libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryExporterGuardTest.java', [
    'java', '-classpath', 'gradle/wrapper/gradle-wrapper.jar', 'org.gradle.wrapper.GradleWrapperMain',
    ':libs:java:observability:test', ':tests:security-negative:test', '--no-daemon', '--dependency-verification=strict',
  ]),
  commandCheck('06.deployment', 'scripts/deployment/verify-deployment.mjs', ['node', 'scripts/deployment/verify-deployment.mjs']),
  commandCheck('07.release-chain', 'scripts/ci/verify-release-chain.mjs', ['node', 'scripts/ci/verify-release-chain.mjs', '--context', 'build/ci/context.json']),
];

function tool(name, requiredVersion, versionArgs) {
  return { name, requiredVersion, versionArgs };
}

function commandCheck(checkId, requiredPath, argv) {
  return { checkId, requiredPath, argv };
}

export async function runInsideDetached({
  repository = process.cwd(),
  evidenceDirectory,
  remoteUrl,
  remoteRef,
  remoteSha,
  treeSha,
  receiptsDirectory,
  trustStorePath,
  releaseManifestPath,
  run = runNative,
  now = () => new Date(),
}) {
  const root = resolve(repository);
  const evidence = resolve(evidenceDirectory);
  assertExternalEvidenceDirectory(root, evidence);
  const startedAt = now();
  await assertDetachedClean({ worktree: root, remoteSha, treeSha, run });

  const checks = [];
  const artifactState = await collectArtifactBindings({ root, releaseManifestPath, checks, now: now() });
  for (const specification of REPOSITORY_CHECKS) {
    checks.push(await executeRepositoryCheck({ root, specification, run, now: now() }));
  }

  const toolchainResults = [];
  for (const specification of REQUIRED_TOOLCHAIN) {
    const outcome = probeTool({ specification, root, remoteSha, treeSha, run, now: now() });
    toolchainResults.push(outcome.toolchain);
    checks.push(outcome.check);
  }

  const demo = await runFoundationDemo({
    repository: root,
    outputDirectory: evidence,
    run,
    now: now(),
  });
  checks.push(demo.status === 'PASS'
    ? passCheck('08.foundation-demo', demo.attempted_argv, 0, demo.evidence_digests, now())
    : failCheck('08.foundation-demo', demo.attempted_argv, demo.attempt_exit_code, demo.evidence_digests, now()));

  try {
    await assertDetachedClean({ worktree: root, remoteSha, treeSha, run });
    checks.push(passCheck(
      '09.final-clean-state',
      ['git', 'status', '--porcelain=v1', '--untracked-files=all'],
      0,
      [sha256(canonicalJson({ head: remoteSha, tree: treeSha, status: 'clean' }))],
      now(),
    ));
  } catch {
    checks.push(failCheck(
      '09.final-clean-state',
      ['git', 'status', '--porcelain=v1', '--untracked-files=all'],
      1,
      [sha256(canonicalJson({ head: remoteSha, tree: treeSha, status: 'dirty' }))],
      now(),
    ));
  }

  checks.sort(byCheckId);
  toolchainResults.sort((left, right) => compare(left.name, right.name));
  const status = checks.some((check) => check.status === 'FAIL')
    ? 'FAIL'
    : checks.some((check) => check.status === 'BLOCKED')
      ? 'BLOCKED'
      : 'PASS';
  const verdict = {
    schema_version: '1.0.0',
    evidence_policy_version: POLICY_VERSION,
    kind: 'CODE',
    status,
    remote_url: remoteUrl,
    remote_ref: remoteRef,
    remote_sha: remoteSha,
    tree_sha: treeSha,
    image_lock_digest: artifactState.imageLockDigest,
    release_manifest_digest: artifactState.releaseManifestDigest,
    dependency_lock_digests: artifactState.dependencyLockDigests,
    toolchain_results: toolchainResults,
    checks,
    demo_evidence: demo.evidence_digests,
    started_at: startedAt.toISOString(),
    ended_at: now().toISOString(),
    evidence_bundle_digest: '0'.repeat(64),
  };
  verdict.evidence_bundle_digest = sha256(canonicalJson(withoutBundleDigest(verdict)));
  await validateVerdictDocument(verdict);

  const codePath = resolve(evidence, 'code-verdict.json');
  const environmentPath = resolve(evidence, 'environment-verdict.json');
  const summaryPath = resolve(evidence, 'summary.json');
  await atomicWriteJson(codePath, verdict);
  const environment = await collectEnvironmentEvidence({
    codeVerdictPath: codePath,
    receiptsDirectory: resolve(receiptsDirectory ?? resolve(evidence, 'environment-receipts')),
    trustStorePath: trustStorePath ? resolve(trustStorePath) : undefined,
    outputPath: environmentPath,
    now: now(),
  });
  const summary = await mergeVerdicts({
    codePath,
    environmentPath,
    outputPath: summaryPath,
    now: now(),
  });
  await assertDetachedClean({ worktree: root, remoteSha, treeSha, run });
  return { code: verdict, environment, summary };
}

export async function runAuthoritativeAcceptance(options) {
  const repository = resolve(options.repository ?? process.cwd());
  const proof = await resolveRemoteSha({
    repository,
    remoteUrl: options.remoteUrl,
    remoteRef: options.remoteRef,
    remoteSha: options.remoteSha,
    run: options.run ?? runNative,
  });
  const outputBase = resolve(options.outputBase ?? resolve(repository, 'build', 'acceptance'));
  const evidenceDirectory = resolve(outputBase, proof.remote_sha);
  const proofPath = resolve(evidenceDirectory, 'remote-proof.json');
  await atomicWriteJson(proofPath, proof);
  return withDetachedWorktree({
    repository,
    remoteSha: proof.remote_sha,
    treeSha: proof.tree_sha,
    evidenceDirectory,
    run: options.run ?? runNative,
  }, async ({ worktree }) => {
    const scriptPath = resolve(worktree, 'scripts', 'acceptance', 'run-foundation-acceptance.mjs');
    const argv = [
      scriptPath,
      '--inside-detached',
      '--evidence-dir', evidenceDirectory,
      '--remote-url', proof.remote_url,
      '--remote-ref', proof.remote_ref,
      '--remote-sha', proof.remote_sha,
      '--tree-sha', proof.tree_sha,
    ];
    if (options.receiptsDirectory) argv.push('--receipts', resolve(options.receiptsDirectory));
    if (options.trustStorePath) argv.push('--trust-store', resolve(options.trustStorePath));
    if (options.releaseManifestPath) argv.push('--release-manifest', resolve(options.releaseManifestPath));
    const child = (options.run ?? runNative)(process.execPath, argv, {
      cwd: worktree,
      timeout: 2 * 60 * 60_000,
      maxBuffer: 4 * 1024 * 1024,
    });
    await assertDetachedClean({ worktree, remoteSha: proof.remote_sha, treeSha: proof.tree_sha, run: options.run ?? runNative });
    if (child.errorCode !== null || ![0, 1, 2].includes(child.exitCode)) {
      throw new AcceptanceAssertionError('DETACHED_ACCEPTANCE_PROCESS_FAILED');
    }
    const summary = JSON.parse(await readFile(resolve(evidenceDirectory, 'summary.json'), 'utf8'));
    if (verdictExitCode(summary.status) !== child.exitCode) {
      throw new AcceptanceAssertionError('DETACHED_ACCEPTANCE_EXIT_MISMATCH');
    }
    return summary;
  });
}

async function collectArtifactBindings({ root, releaseManifestPath, checks, now }) {
  const imageLockPath = resolve(root, 'infra', 'images', 'images.lock.json');
  const releasePath = resolve(releaseManifestPath ?? resolve(root, 'build', 'release', 'release-manifest.json'));
  const image = await digestArtifact(imageLockPath, '00.image-lock', checks, now);
  const release = await digestArtifact(releasePath, '00.release-manifest', checks, now);
  const dependencyLockDigests = await collectDependencyLocks(root);
  if (dependencyLockDigests.length === 0) {
    checks.push(failCheck(
      '00.dependency-locks',
      ['verify-dependency-locks'],
      1,
      [sha256(canonicalJson({ artifact: 'dependency-locks', state: 'absent' }))],
      now,
    ));
  } else {
    checks.push(passCheck(
      '00.dependency-locks',
      ['verify-dependency-locks'],
      0,
      [sha256(canonicalJson(dependencyLockDigests))],
      now,
    ));
  }
  return {
    imageLockDigest: image,
    releaseManifestDigest: release,
    dependencyLockDigests,
  };
}

async function digestArtifact(path, checkId, checks, now) {
  try {
    const info = await lstat(path);
    if (!info.isFile() || info.isSymbolicLink()) throw new Error('invalid');
    const digest = sha256(await readFile(path));
    checks.push(passCheck(checkId, ['verify-artifact', basename(path)], 0, [digest], now));
    return digest;
  } catch {
    const digest = sha256(canonicalJson({ artifact: checkId, state: 'absent-or-invalid' }));
    checks.push(failCheck(checkId, ['verify-artifact', basename(path)], 1, [digest], now));
    return digest;
  }
}

async function collectDependencyLocks(root) {
  const entries = [];
  await walk(root, async (path, name) => {
    if (name === 'gradle.lockfile' || name === 'pnpm-lock.yaml' || name === 'uv.lock' || name === 'verification-metadata.xml') {
      const relativePath = relative(root, path).split(sep).join('/');
      entries.push({ path: relativePath, digest: sha256(await readFile(path)) });
    }
  });
  entries.sort((left, right) => compare(left.path, right.path));
  return entries;
}

async function walk(directory, onFile) {
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((left, right) => compare(left.name, right.name));
  for (const entry of entries) {
    if (entry.name === '.git' || entry.name === '.gradle' || entry.name === 'build' || entry.name === 'node_modules') continue;
    const path = resolve(directory, entry.name);
    if (entry.isSymbolicLink()) continue;
    if (entry.isDirectory()) await walk(path, onFile);
    else if (entry.isFile()) await onFile(path, entry.name);
  }
}

async function executeRepositoryCheck({ root, specification, run, now }) {
  try {
    const info = await lstat(resolve(root, specification.requiredPath));
    if (!info.isFile() || info.isSymbolicLink()) throw new Error('invalid');
  } catch {
    return failCheck(
      specification.checkId,
      specification.argv,
      1,
      [sha256(canonicalJson({ check_id: specification.checkId, repository_boundary: 'absent' }))],
      now,
    );
  }
  const executable = specification.argv[0] === 'node' ? process.execPath : specification.argv[0];
  const result = run(executable, specification.argv.slice(1), {
    cwd: root,
    timeout: 30 * 60_000,
    maxBuffer: 8 * 1024 * 1024,
  });
  const digest = commandResultDigest(specification.argv, result);
  return result.errorCode === null && result.exitCode === 0
    ? passCheck(specification.checkId, specification.argv, 0, [digest], now)
    : failCheck(specification.checkId, specification.argv, result.exitCode ?? 1, [digest], now);
}

export function probeTool({ specification, root, remoteSha, treeSha, run = runNative, now = new Date() }) {
  const argv = [specification.name, ...specification.versionArgs];
  const result = run(specification.name, specification.versionArgs, { cwd: root, timeout: 30_000 });
  const observedVersion = extractVersion(`${result.stdout}\n${result.stderr}`);
  if (result.errorCode !== null || result.exitCode === null) {
    return {
      toolchain: { name: specification.name, required_version: specification.requiredVersion, observed_version: null, status: 'BLOCKED' },
      check: {
        schema_version: '1.0.0',
        check_id: `02.toolchain.${specification.name}`,
        status: 'BLOCKED',
        remote_sha: remoteSha,
        tree_sha: treeSha,
        reason_code: 'BLOCKED_TOOLCHAIN',
        attempted_argv: argv,
        attempt_exit_code: null,
        attempt_evidence: [],
        rerun_argv: ['node', 'scripts/acceptance/run-foundation-acceptance.mjs'],
        occurred_at: now.toISOString(),
        owner: OWNER,
        tool: {
          name: specification.name,
          required_version: specification.requiredVersion,
          observed_version: null,
        },
      },
    };
  }
  const matches = result.exitCode === 0 && observedVersion === specification.requiredVersion;
  const digest = commandResultDigest(argv, result, observedVersion);
  return {
    toolchain: {
      name: specification.name,
      required_version: specification.requiredVersion,
      observed_version: observedVersion,
      status: matches ? 'PASS' : 'FAIL',
    },
    check: matches
      ? passCheck(`02.toolchain.${specification.name}`, argv, 0, [digest], now)
      : failCheck(`02.toolchain.${specification.name}`, argv, result.exitCode, [digest], now),
  };
}

function extractVersion(output) {
  const match = /(?:^|[^0-9])([0-9]+\.[0-9]+\.[0-9]+)(?:[^0-9]|$)/u.exec(output);
  return match?.[1] ?? null;
}

function commandResultDigest(argv, result, observedVersion) {
  return sha256(canonicalJson({
    argv,
    duration_ms: result.durationMs,
    error_code: result.errorCode,
    exit_code: result.exitCode,
    ...(observedVersion === undefined ? {} : { observed_version: observedVersion }),
  }));
}

function passCheck(checkId, argv, exitCode, evidence, occurredAt) {
  return {
    check_id: checkId,
    status: 'PASS',
    attempted_argv: argv,
    attempt_exit_code: exitCode,
    evidence_digests: [...evidence].sort(),
    occurred_at: occurredAt.toISOString(),
    owner: OWNER,
  };
}

function failCheck(checkId, argv, exitCode, evidence, occurredAt) {
  return {
    check_id: checkId,
    status: 'FAIL',
    reason_code: 'ASSERTION_FAILED',
    executed: true,
    attempted_argv: argv,
    attempt_exit_code: Number.isInteger(exitCode) && exitCode >= 0 ? exitCode : 1,
    evidence_digests: [...evidence].sort(),
    occurred_at: occurredAt.toISOString(),
    owner: OWNER,
  };
}

function withoutBundleDigest(verdict) {
  const copy = { ...verdict };
  delete copy.evidence_bundle_digest;
  return copy;
}

function assertExternalEvidenceDirectory(repository, evidence) {
  if (!isAbsolute(evidence) || evidence === repository || isPathInside(repository, evidence)) {
    throw new AcceptanceAssertionError('EVIDENCE_DIRECTORY_MUST_BE_EXTERNAL');
  }
}

function byCheckId(left, right) {
  return compare(left.check_id, right.check_id);
}

function compare(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function safeError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

function parseCli(argv) {
  const options = { repository: process.cwd(), insideDetached: false };
  for (let index = 0; index < argv.length;) {
    const flag = argv[index];
    if (flag === '--inside-detached') {
      options.insideDetached = true;
      index += 1;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined) throw safeError('ARGUMENTS_INVALID');
    const key = {
      '--repository': 'repository',
      '--remote-url': 'remoteUrl',
      '--remote-ref': 'remoteRef',
      '--remote-sha': 'remoteSha',
      '--tree-sha': 'treeSha',
      '--output': 'outputBase',
      '--evidence-dir': 'evidenceDirectory',
      '--receipts': 'receiptsDirectory',
      '--trust-store': 'trustStorePath',
      '--release-manifest': 'releaseManifestPath',
    }[flag];
    if (!key) throw safeError('ARGUMENTS_INVALID');
    options[key] = value;
    index += 2;
  }
  if (!options.remoteUrl || !options.remoteRef || !options.remoteSha) throw safeError('ARGUMENTS_INVALID');
  if (options.insideDetached && (!options.treeSha || !options.evidenceDirectory)) throw safeError('ARGUMENTS_INVALID');
  return options;
}

async function recordBootstrapFailure(options, error) {
  const output = resolve(options.outputBase ?? resolve(options.repository, 'build', 'acceptance'), options.remoteSha);
  const status = error?.verdictStatus === 'BLOCKED' ? 'BLOCKED' : 'FAIL';
  const document = {
    schema_version: '1.0.0',
    status,
    reason_code: status === 'BLOCKED' ? (error.reasonCode ?? 'BLOCKED_REMOTE_AUTHORITY') : 'ASSERTION_FAILED',
    remote_url: options.remoteUrl,
    remote_ref: options.remoteRef,
    remote_sha: options.remoteSha,
    attempted_argv: ['git', 'ls-remote', '--exit-code', options.remoteUrl, options.remoteRef],
    attempt_exit_code: null,
    occurred_at: new Date().toISOString(),
    owner: OWNER,
  };
  await atomicWriteJson(resolve(output, 'remote-authority-result.json'), document);
  return status;
}

async function main() {
  let options;
  try {
    options = parseCli(process.argv.slice(2));
    const result = options.insideDetached
      ? await runInsideDetached(options)
      : { summary: await runAuthoritativeAcceptance(options) };
    process.stdout.write(`foundation-acceptance: ${result.summary.status}\n`);
    process.exitCode = verdictExitCode(result.summary.status);
  } catch (error) {
    const status = options && !options.insideDetached
      ? await recordBootstrapFailure(options, error)
      : 'FAIL';
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`foundation-acceptance: ${status} (${code})\n`);
    process.exitCode = status === 'BLOCKED' ? 2 : 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
