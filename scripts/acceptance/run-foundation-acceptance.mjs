import { readdir, readFile, lstat } from 'node:fs/promises';
import { basename, isAbsolute, relative, resolve, sep, win32 } from 'node:path';
import { pathToFileURL } from 'node:url';
import { assertDetachedClean, isPathInside, withDetachedWorktree } from './create-detached-worktree.mjs';
import { collectEnvironmentEvidence } from './collect-environment-evidence.mjs';
import {
  atomicWriteJson,
  canonicalJson,
  mergeVerdicts,
  sha256,
  validateBootstrapResult,
  validateVerdictDocument,
  verifySummaryAgainstVerdicts,
  verdictExitCode,
} from './merge-verdicts.mjs';
import {
  AcceptanceAssertionError,
  resolveRemoteSha,
  runNative,
} from './resolve-remote-sha.mjs';
import { runFoundationDemo } from './run-foundation-demo.mjs';
import { nativeToolInvocation } from '../verification/preflight.mjs';

const OWNER = 'platform-engineering';
const POLICY_VERSION = 'accord-foundation-evidence-v1';
export const REQUIRED_TOOLCHAIN = [
  tool('buf', '1.55.1', ['--version']),
  tool('cosign', '2.5.0', ['version']),
  tool('conftest', '0.61.2', ['--version']),
  tool('docker', '29.4.2', ['version', '--format', '{{.Client.Version}}']),
  tool('docker-buildx', '0.33.0', ['buildx', 'version'], 'docker'),
  tool('docker-compose', '5.1.3', ['compose', 'version', '--short'], 'docker'),
  tool('git', '2.52.0', ['--version']),
  tool('gradle-wrapper', '8.14.3', [
    '-classpath', 'gradle/wrapper/gradle-wrapper.jar',
    'org.gradle.wrapper.GradleWrapperMain', '--version',
  ], 'java'),
  tool('helm', '3.17.3', ['version', '--short']),
  tool('java', '21.0.11+10', ['--version']),
  tool('kubeconform', '0.7.0', ['-v']),
  tool('node', '22.22.1', ['--version'], process.execPath),
  tool('oras', '1.2.2', ['version']),
  tool('pnpm', '10.12.4', ['--version']),
  tool('python', '3.12.11', ['--version']),
  tool('syft', '1.27.1', ['version']),
  tool('tofu', '1.9.1', ['version']),
  tool('trivy', '0.63.0', ['--version']),
  tool('uv', '0.7.13', ['--version']),
];
export const REPOSITORY_CHECKS = [
  commandCheck('01.architecture', 'tests/architecture/verify-platform-foundation.mjs', ['node', 'tests/architecture/verify-platform-foundation.mjs']),
  commandCheck('03.dependency-verification', 'scripts/verification/verify-offline-dependencies.mjs', [
    'node', 'scripts/verification/verify-offline-dependencies.mjs',
  ], {
    requiredTools: ['buf', 'gradle-wrapper', 'java', 'node', 'pnpm', 'python', 'uv'],
    blockedTool: tool('java', '21.0.11+10', ['--version']),
  }),
  commandCheck('04.image-and-temporal', 'scripts/images/verify-images.mjs', ['node', 'scripts/images/verify-images.mjs', '--scope', 'all'], {
    blockedExternal: {
      reasonCode: 'BLOCKED_REGISTRY',
      capability: 'immutable-container-manifest-set',
      authority: 'approved-container-registries',
      evidenceType: 'image-lock-resolution-v1',
    },
  }),
  commandCheck('04.temporal-mtls-contract', 'tests/integration/temporal-mtls.test.mjs', [
    'node', '--test', 'tests/integration/temporal-mtls.test.mjs',
  ]),
  commandCheck('05.telemetry', 'libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryExporterGuardTest.java', [
    'java', '-classpath', 'gradle/wrapper/gradle-wrapper.jar', 'org.gradle.wrapper.GradleWrapperMain',
    ':libs:java:observability:test', ':tests:security-negative:test', '--no-daemon', '--dependency-verification=strict',
  ], {
    requiredTools: ['gradle-wrapper', 'java'],
    blockedTool: tool('java', '21.0.11+10', ['--version']),
  }),
  commandCheck('06.deployment', 'scripts/deployment/verify-deployment.mjs', ['node', 'scripts/deployment/verify-deployment.mjs'], {
    requiredTools: ['conftest', 'helm', 'kubeconform', 'tofu'],
  }),
  commandCheck('07.release-chain', 'scripts/ci/verify-release-chain.mjs', [
    'node', 'scripts/ci/verify-release-chain.mjs',
    '--context', 'build/ci/context.json',
    '--manifest', 'build/ci/release-manifest.json',
    '--evidence-index', 'build/ci/evidence-index.json',
    '--evidence-root', 'build/ci',
    '--output', 'build/ci/promotion-input.json',
  ], {
    requiredTools: ['cosign', 'git', 'oras', 'syft', 'trivy'],
    blockedExternal: {
      reasonCode: 'BLOCKED_REGISTRY',
      capability: 'release-referrer-verification',
      authority: 'approved-container-registries',
      evidenceType: 'release-referrer-verification-v1',
    },
  }),
];

function tool(name, requiredVersion, versionArgs, executable = name) {
  return { name, requiredVersion, versionArgs, executable };
}

function commandCheck(checkId, requiredPath, argv, options = {}) {
  return {
    checkId,
    requiredPath,
    argv,
    requiredTools: options.requiredTools ?? [],
    blockedTool: options.blockedTool ?? null,
    blockedExternal: options.blockedExternal ?? null,
  };
}

export async function runInsideDetached({
  repository = process.cwd(),
  evidenceDirectory,
  remoteUrl,
  remoteRef,
  remoteSha,
  treeSha,
  receiptsDirectory,
  releaseManifestPath,
  ciContextPath,
  releaseEvidenceIndexPath,
  releaseEvidenceRootPath,
  run = runNative,
  now = () => new Date(),
}) {
  const root = resolve(repository);
  const evidence = resolve(evidenceDirectory);
  assertExternalEvidenceDirectory(root, evidence);
  const startedAt = now();
  await assertDetachedClean({ worktree: root, remoteSha, treeSha, run });

  const checks = [];
  const resolvedCiContextPath = resolve(ciContextPath ?? resolve(root, 'build', 'ci', 'context.json'));
  const resolvedReleaseManifestPath = resolve(releaseManifestPath ?? resolve(root, 'build', 'ci', 'release-manifest.json'));
  const resolvedReleaseEvidenceIndexPath = resolve(releaseEvidenceIndexPath ?? resolve(root, 'build', 'ci', 'evidence-index.json'));
  const resolvedReleaseEvidenceRootPath = resolve(releaseEvidenceRootPath ?? root);
  const releaseInputState = await inspectReleaseInputFiles({
    ciContextPath: resolvedCiContextPath,
    releaseManifestPath: resolvedReleaseManifestPath,
    releaseEvidenceIndexPath: resolvedReleaseEvidenceIndexPath,
    remoteUrl,
    remoteRef,
    remoteSha,
    treeSha,
    now: now(),
  });
  const artifactState = await collectArtifactBindings({
    root,
    releaseManifestPath: resolvedReleaseManifestPath,
    checks,
    remoteSha,
    treeSha,
    now: now(),
  });
  const effectiveChecks = bindReleaseInputs(REPOSITORY_CHECKS, {
    ciContextPath: resolvedCiContextPath,
    releaseManifestPath: resolvedReleaseManifestPath,
    releaseEvidenceIndexPath: resolvedReleaseEvidenceIndexPath,
    releaseEvidenceRootPath: resolvedReleaseEvidenceRootPath,
    evidenceDigests: releaseInputState.evidence,
  });
  const [architectureCheck, ...dependentChecks] = effectiveChecks;
  checks.push(await executeRepositoryCheck({
    root,
    specification: architectureCheck,
    remoteSha,
    treeSha,
    run,
    now: now(),
  }));

  const toolchainResults = [];
  const toolchainByName = new Map();
  for (const specification of REQUIRED_TOOLCHAIN) {
    const outcome = probeTool({ specification, root, remoteSha, treeSha, run, now: now() });
    toolchainResults.push(outcome.toolchain);
    toolchainByName.set(specification.name, { specification, outcome });
    checks.push(outcome.check);
  }
  for (const specification of dependentChecks) {
    if (specification.checkId === '07.release-chain' && !releaseInputState.ready) {
      checks.push(releaseInputState.check);
      continue;
    }
    const unavailable = specification.requiredTools
      .map((name) => toolchainByName.get(name))
      .find((entry) => entry?.outcome.toolchain.status !== 'PASS');
    checks.push(unavailable
      ? blockedDependentCheck({
          checkId: specification.checkId,
          tool: unavailable.specification,
          observedVersion: unavailable.outcome.toolchain.observed_version,
          remoteSha,
          treeSha,
          now: now(),
        })
      : await executeRepositoryCheck({
          root,
          specification,
          remoteSha,
          treeSha,
          run,
          now: now(),
        }));
  }

  const demo = await runFoundationDemo({
    repository: root,
    outputDirectory: evidence,
    remoteSha,
    treeSha,
    run,
    now: now(),
  });
  checks.push(demo);

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
    demo_evidence: [...(demo.status === 'BLOCKED'
      ? demo.attempt_evidence
      : demo.evidence_digests)].sort(),
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
  const ciContextPath = resolve(options.ciContextPath ?? resolve(repository, 'build', 'ci', 'context.json'));
  const releaseManifestPath = resolve(options.releaseManifestPath ?? resolve(repository, 'build', 'ci', 'release-manifest.json'));
  const releaseEvidenceIndexPath = resolve(options.releaseEvidenceIndexPath ?? resolve(repository, 'build', 'ci', 'evidence-index.json'));
  const releaseEvidenceRootPath = resolve(options.releaseEvidenceRootPath ?? repository);
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
  const storeDirectory = resolvePnpmStoreDirectory({
    repository,
    run: options.run ?? runNative,
    platform: options.platform,
    nodeExecutable: options.nodeExecutable,
  });
  return withDetachedWorktree({
    repository,
    remoteSha: proof.remote_sha,
    treeSha: proof.tree_sha,
    evidenceDirectory,
    run: options.run ?? runNative,
  }, async ({ worktree }) => {
    const bootstrapInvocation = detachedDependencyBootstrapInvocation({
      platform: options.platform,
      nodeExecutable: options.nodeExecutable,
      storeDirectory,
    });
    const bootstrap = (options.run ?? runNative)(
      bootstrapInvocation.executable,
      bootstrapInvocation.argv,
      {
        cwd: worktree,
        timeout: 10 * 60_000,
        maxBuffer: 1024 * 1024,
      },
    );
    if (bootstrap.errorCode !== null || bootstrap.exitCode !== 0) {
      throw new AcceptanceAssertionError('DETACHED_DEPENDENCY_BOOTSTRAP_FAILED');
    }
    await assertDetachedClean({
      worktree,
      remoteSha: proof.remote_sha,
      treeSha: proof.tree_sha,
      run: options.run ?? runNative,
    });
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
    argv.push('--ci-context', ciContextPath);
    argv.push('--release-manifest', releaseManifestPath);
    argv.push('--release-evidence-index', releaseEvidenceIndexPath);
    argv.push('--release-evidence-root', releaseEvidenceRootPath);
    const child = (options.run ?? runNative)(process.execPath, argv, {
      cwd: worktree,
      timeout: 2 * 60 * 60_000,
      maxBuffer: 4 * 1024 * 1024,
    });
    await assertDetachedClean({ worktree, remoteSha: proof.remote_sha, treeSha: proof.tree_sha, run: options.run ?? runNative });
    if (child.errorCode !== null || ![0, 1, 2].includes(child.exitCode)) {
      throw new AcceptanceAssertionError('DETACHED_ACCEPTANCE_PROCESS_FAILED');
    }
    const summary = await verifyDetachedAcceptanceEvidence({
      evidenceDirectory,
      remoteUrl: proof.remote_url,
      remoteRef: proof.remote_ref,
      remoteSha: proof.remote_sha,
      treeSha: proof.tree_sha,
    });
    if (verdictExitCode(summary.status) !== child.exitCode) {
      throw new AcceptanceAssertionError('DETACHED_ACCEPTANCE_EXIT_MISMATCH');
    }
    return summary;
  });
}

export function detachedDependencyBootstrapInvocation({
  platform = process.platform,
  nodeExecutable = process.execPath,
  storeDirectory,
} = {}) {
  const absoluteStore = platform === 'win32'
    ? typeof storeDirectory === 'string' && win32.isAbsolute(storeDirectory)
    : typeof storeDirectory === 'string' && isAbsolute(storeDirectory);
  if (!absoluteStore || /[\0\r\n]/u.test(storeDirectory)) {
    throw new AcceptanceAssertionError('PNPM_STORE_PATH_INVALID');
  }
  return nativeToolInvocation(
    'pnpm',
    ['install', '--frozen-lockfile', '--offline', '--store-dir', storeDirectory],
    { platform, nodeExecutable },
  );
}

export function resolvePnpmStoreDirectory({
  repository,
  run = runNative,
  platform = process.platform,
  nodeExecutable = process.execPath,
}) {
  const invocation = nativeToolInvocation(
    'pnpm',
    ['store', 'path', '--silent'],
    { platform, nodeExecutable },
  );
  const result = invokeAcceptanceRunner(run, invocation.executable, invocation.argv, {
    cwd: repository,
    timeout: 30_000,
  });
  const storeDirectory = result.stdout.trim();
  const absoluteStore = platform === 'win32'
    ? win32.isAbsolute(storeDirectory)
    : isAbsolute(storeDirectory);
  if (
    result.errorCode !== null
    || result.exitCode !== 0
    || !absoluteStore
    || /[\0\r\n]/u.test(storeDirectory)
  ) {
    throw new AcceptanceAssertionError('PNPM_STORE_PATH_UNAVAILABLE');
  }
  return storeDirectory;
}

export async function verifyDetachedAcceptanceEvidence({
  evidenceDirectory,
  remoteUrl,
  remoteRef,
  remoteSha,
  treeSha,
}) {
  try {
    const root = resolve(evidenceDirectory);
    const [codeEvidence, environmentEvidence, summaryEvidence] = await Promise.all([
      readCanonicalEvidence(resolve(root, 'code-verdict.json')),
      readCanonicalEvidence(resolve(root, 'environment-verdict.json')),
      readCanonicalEvidence(resolve(root, 'summary.json')),
    ]);
    await verifySummaryAgainstVerdicts({
      summary: summaryEvidence.document,
      code: codeEvidence.document,
      environment: environmentEvidence.document,
      codeBytes: codeEvidence.bytes,
      environmentBytes: environmentEvidence.bytes,
    });
    for (const verdict of [codeEvidence.document, environmentEvidence.document]) {
      if (verdict.remote_url !== remoteUrl
          || verdict.remote_ref !== remoteRef
          || verdict.remote_sha !== remoteSha
          || verdict.tree_sha !== treeSha) {
        throw safeError('DETACHED_EVIDENCE_AUTHORITY_MISMATCH');
      }
    }
    return summaryEvidence.document;
  } catch (error) {
    if (typeof error?.code === 'string' && error.code.startsWith('DETACHED_EVIDENCE_')) {
      throw error;
    }
    throw safeError('DETACHED_EVIDENCE_INVALID');
  }
}

async function readCanonicalEvidence(path) {
  const info = await lstat(path);
  if (!info.isFile() || info.isSymbolicLink() || info.size < 2 || info.size > 16 * 1024 * 1024) {
    throw safeError('DETACHED_EVIDENCE_FILE_INVALID');
  }
  const bytes = await readFile(path);
  const document = JSON.parse(bytes.toString('utf8'));
  if (!bytes.equals(Buffer.from(canonicalJson(document), 'utf8'))) {
    throw safeError('DETACHED_EVIDENCE_NOT_CANONICAL');
  }
  return { bytes, document };
}

function bindReleaseInputs(checks, {
  ciContextPath,
  releaseManifestPath,
  releaseEvidenceIndexPath,
  releaseEvidenceRootPath,
  evidenceDigests,
}) {
  return checks.map((specification) => specification.checkId !== '07.release-chain'
    ? specification
    : {
        ...specification,
        evidenceDigests,
        argv: [
          'node', 'scripts/ci/verify-release-chain.mjs',
          '--context', ciContextPath,
          '--manifest', releaseManifestPath,
          '--evidence-index', releaseEvidenceIndexPath,
          '--evidence-root', releaseEvidenceRootPath,
          '--output', 'build/ci/promotion-input.json',
        ],
      });
}

export function validateReleaseInputBindings({
  context,
  manifest,
  remoteUrl,
  remoteRef,
  remoteSha,
  treeSha,
}) {
  if (context === null || typeof context !== 'object'
      || manifest === null || typeof manifest !== 'object'
      || context.remote_sha !== remoteSha
      || context.tree_sha !== treeSha
      || manifest.remote_sha !== remoteSha
      || manifest.tree_sha !== treeSha
      || (remoteUrl !== undefined && context.remote_url !== remoteUrl)
      || (remoteRef !== undefined && context.remote_ref !== remoteRef)) {
    throw safeError('RELEASE_INPUT_AUTHORITY_MISMATCH');
  }
  return true;
}

async function inspectReleaseInputFiles({
  ciContextPath,
  releaseManifestPath,
  releaseEvidenceIndexPath,
  remoteUrl,
  remoteRef,
  remoteSha,
  treeSha,
  now,
}) {
  const argv = ['verify-release-input-bindings'];
  const paths = [ciContextPath, releaseManifestPath, releaseEvidenceIndexPath];
  const evidence = [];
  try {
    const documents = [];
    for (const path of paths) {
      const info = await lstat(path);
      if (!info.isFile() || info.isSymbolicLink() || info.size > 16 * 1024 * 1024) {
        throw safeError('RELEASE_INPUT_FILE_INVALID');
      }
      const bytes = await readFile(path);
      evidence.push(sha256(bytes));
      documents.push(JSON.parse(bytes.toString('utf8')));
    }
    const [context, manifest] = documents;
    validateReleaseInputBindings({
      context,
      manifest,
      remoteUrl,
      remoteRef,
      remoteSha,
      treeSha,
    });
    if (manifest.context_sha256 !== `sha256:${evidence[0]}`) {
      throw safeError('RELEASE_INPUT_CONTEXT_DIGEST_MISMATCH');
    }
    return { ready: true, evidence: [...evidence].sort(), check: null };
  } catch (error) {
    if (error?.code === 'ENOENT') {
      return {
        ready: false,
        evidence: [...evidence].sort(),
        check: blockedExternalCheck({
          checkId: '07.release-chain',
          argv,
          exitCode: null,
          evidence,
          remoteSha,
          treeSha,
          external: {
            reasonCode: 'BLOCKED_REGISTRY',
            capability: 'immutable-release-evidence-bundle',
            authority: 'approved-artifact-registry',
            evidenceType: 'release-evidence-bundle-v1',
          },
          now,
        }),
      };
    }
    return {
      ready: false,
      evidence: [...evidence].sort(),
      check: failCheck(
        '07.release-chain',
        argv,
        1,
        evidence.length > 0
          ? evidence
          : [sha256(canonicalJson({ state: 'release-input-invalid' }))],
        now,
      ),
    };
  }
}

async function collectArtifactBindings({
  root,
  releaseManifestPath,
  checks,
  remoteSha,
  treeSha,
  now,
}) {
  const imageLockPath = resolve(root, 'infra', 'images', 'images.lock.json');
  const releasePath = resolve(releaseManifestPath ?? resolve(root, 'build', 'ci', 'release-manifest.json'));
  const image = await digestArtifact({
    path: imageLockPath,
    checkId: '00.image-lock',
    checks,
    remoteSha,
    treeSha,
    missingIsFailure: true,
    blockedExternal: {
      reasonCode: 'BLOCKED_REGISTRY',
      capability: 'authoritative-image-lock',
      authority: 'approved-container-registries',
      evidenceType: 'image-lock-resolution-v1',
    },
    now,
  });
  const release = await digestArtifact({
    path: releasePath,
    checkId: '00.release-manifest',
    checks,
    remoteSha,
    treeSha,
    blockedExternal: {
      reasonCode: 'BLOCKED_REGISTRY',
      capability: 'verified-release-manifest',
      authority: 'approved-artifact-registry',
      evidenceType: 'release-manifest-v1',
    },
    now,
  });
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

async function digestArtifact({
  path,
  checkId,
  checks,
  remoteSha,
  treeSha,
  blockedExternal,
  missingIsFailure = false,
  now,
}) {
  try {
    const info = await lstat(path);
    if (!info.isFile() || info.isSymbolicLink()) throw new Error('invalid');
    const digest = sha256(await readFile(path));
    checks.push(passCheck(checkId, ['verify-artifact', basename(path)], 0, [digest], now));
    return digest;
  } catch (error) {
    if (error?.code === 'ENOENT') {
      if (missingIsFailure) {
        checks.push(failCheck(
          checkId,
          ['verify-artifact', basename(path)],
          1,
          [sha256(canonicalJson({ artifact: checkId, state: 'absent' }))],
          now,
        ));
        return null;
      }
      checks.push(blockedExternalCheck({
        checkId,
        argv: ['verify-artifact', basename(path)],
        exitCode: null,
        evidence: [],
        remoteSha,
        treeSha,
        external: blockedExternal,
        now,
      }));
      return null;
    }
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

export async function executeRepositoryCheck({
  root,
  specification,
  remoteSha,
  treeSha,
  run,
  now,
}) {
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
  const result = invokeAcceptanceRunner(run, executable, specification.argv.slice(1), {
    cwd: root,
    timeout: 30 * 60_000,
    maxBuffer: 8 * 1024 * 1024,
  });
  const digest = commandResultDigest(specification.argv, result);
  const evidence = [...(specification.evidenceDigests ?? []), digest].sort();
  if (result.errorCode === 'ENOENT' && specification.blockedTool !== null) {
    return blockedToolCheck({
      checkId: specification.checkId,
      argv: specification.argv,
      exitCode: null,
      evidence,
      remoteSha,
      treeSha,
      tool: specification.blockedTool,
      observedVersion: null,
      now,
    });
  }
  if (result.errorCode === null
      && result.exitCode === 2
      && specification.blockedExternal !== null) {
    return blockedExternalCheck({
      checkId: specification.checkId,
      argv: specification.argv,
      exitCode: result.exitCode,
      evidence,
      remoteSha,
      treeSha,
      external: specification.blockedExternal,
      now,
    });
  }
  return result.errorCode === null && result.exitCode === 0
    ? passCheck(specification.checkId, specification.argv, 0, evidence, now)
    : failCheck(specification.checkId, specification.argv, result.exitCode ?? 1, evidence, now);
}

function invokeAcceptanceRunner(run, executable, args, options) {
  try {
    const result = run(executable, args, options);
    if (result === null || typeof result !== 'object') {
      return normalizedCommandResult(null, 'NATIVE_RESULT_INVALID', '', '', 0);
    }
    return normalizedCommandResult(
      Number.isInteger(result.exitCode) && result.exitCode >= 0 ? result.exitCode : null,
      result.errorCode === null
        ? null
        : typeof result.errorCode === 'string' && result.errorCode.length > 0
          ? result.errorCode
          : 'NATIVE_RESULT_INVALID',
      typeof result.stdout === 'string' ? result.stdout : '',
      typeof result.stderr === 'string' ? result.stderr : '',
      Number.isFinite(result.durationMs) && result.durationMs >= 0
        ? Math.trunc(result.durationMs)
        : 0,
    );
  } catch (error) {
    return normalizedCommandResult(
      null,
      error?.code === 'ENOENT' ? 'ENOENT' : 'NATIVE_RUNNER_ERROR',
      '',
      '',
      0,
    );
  }
}

function normalizedCommandResult(exitCode, errorCode, stdout, stderr, durationMs) {
  return { exitCode, errorCode, stdout, stderr, durationMs };
}

function blockedToolCheck({
  checkId,
  argv,
  exitCode,
  evidence,
  remoteSha,
  treeSha,
  tool: specification,
  observedVersion,
  now,
}) {
  return {
    schema_version: '1.0.0',
    check_id: checkId,
    status: 'BLOCKED',
    remote_sha: remoteSha,
    tree_sha: treeSha,
    reason_code: 'BLOCKED_TOOLCHAIN',
    attempted_argv: argv,
    attempt_exit_code: Number.isInteger(exitCode) && exitCode >= 0 ? exitCode : null,
    attempt_evidence: [...evidence].sort(),
    rerun_argv: ['node', 'scripts/acceptance/run-foundation-acceptance.mjs'],
    occurred_at: now.toISOString(),
    owner: OWNER,
    tool: {
      name: specification.name,
      required_version: specification.requiredVersion,
      observed_version: observedVersion,
    },
  };
}

function blockedDependentCheck({
  checkId,
  tool: specification,
  observedVersion,
  remoteSha,
  treeSha,
  now,
}) {
  const argv = [specification.name, ...specification.versionArgs];
  return {
    schema_version: '1.0.0',
    check_id: checkId,
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
      observed_version: observedVersion,
    },
  };
}

function blockedExternalCheck({
  checkId,
  argv,
  exitCode,
  evidence,
  remoteSha,
  treeSha,
  external,
  now,
}) {
  return {
    schema_version: '1.0.0',
    check_id: checkId,
    status: 'BLOCKED',
    remote_sha: remoteSha,
    tree_sha: treeSha,
    reason_code: external.reasonCode,
    attempted_argv: argv,
    attempt_exit_code: exitCode,
    attempt_evidence: [...evidence].sort(),
    rerun_argv: ['node', 'scripts/acceptance/run-foundation-acceptance.mjs'],
    occurred_at: now.toISOString(),
    owner: OWNER,
    external: {
      capability: external.capability,
      authority: external.authority,
      required_evidence_type: external.evidenceType,
    },
  };
}

export function probeTool({
  specification,
  root,
  remoteSha,
  treeSha,
  run = runNative,
  now = new Date(),
  platform = process.platform,
  nodeExecutable = process.execPath,
}) {
  const argv = [specification.name, ...specification.versionArgs];
  const invocation = nativeToolInvocation(
    specification.executable,
    specification.versionArgs,
    { platform, nodeExecutable },
  );
  const result = invokeAcceptanceRunner(
    run,
    invocation.executable,
    invocation.argv,
    { cwd: root, timeout: 30_000 },
  );
  const observedVersion = extractVersion(`${result.stdout}\n${result.stderr}`, specification.name);
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
      status: matches ? 'PASS' : 'BLOCKED',
    },
    check: matches
      ? passCheck(`02.toolchain.${specification.name}`, argv, 0, [digest], now)
      : {
          schema_version: '1.0.0',
          check_id: `02.toolchain.${specification.name}`,
          status: 'BLOCKED',
          remote_sha: remoteSha,
          tree_sha: treeSha,
          reason_code: 'BLOCKED_TOOLCHAIN',
          attempted_argv: argv,
          attempt_exit_code: Number.isInteger(result.exitCode) && result.exitCode >= 0
            ? result.exitCode
            : null,
          attempt_evidence: [digest],
          rerun_argv: ['node', 'scripts/acceptance/run-foundation-acceptance.mjs'],
          occurred_at: now.toISOString(),
          owner: OWNER,
          tool: {
            name: specification.name,
            required_version: specification.requiredVersion,
            observed_version: observedVersion,
          },
        },
  };
}

function extractVersion(output, toolName) {
  const patterns = {
    java: /\bTemurin-([0-9]+[.][0-9]+[.][0-9]+\+[0-9]+)(?:-LTS)?\b/iu,
    'gradle-wrapper': /\bGradle\s+([0-9]+[.][0-9]+(?:[.][0-9]+)?)\b/iu,
    python: /\bPython\s+([0-9]+[.][0-9]+[.][0-9]+)\b/iu,
    git: /\bgit version\s+([0-9]+[.][0-9]+[.][0-9]+)(?:[.]windows[.]\d+)?\b/iu,
  };
  const match = (patterns[toolName]
    ?? /(?:^|[^0-9])v?([0-9]+[.][0-9]+[.][0-9]+)(?:[^0-9]|$)/u).exec(output);
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

export function parseAcceptanceCli(argv) {
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
      '--ci-context': 'ciContextPath',
      '--release-manifest': 'releaseManifestPath',
      '--release-evidence-index': 'releaseEvidenceIndexPath',
      '--release-evidence-root': 'releaseEvidenceRootPath',
    }[flag];
    if (!key) throw safeError('ARGUMENTS_INVALID');
    options[key] = value;
    index += 2;
  }
  if (!options.remoteUrl || !options.remoteRef || !options.remoteSha) throw safeError('ARGUMENTS_INVALID');
  if (options.insideDetached && (!options.treeSha || !options.evidenceDirectory)) throw safeError('ARGUMENTS_INVALID');
  return options;
}

function bootstrapFailureStatus(error) {
  return error?.verdictStatus === 'BLOCKED' ? 'BLOCKED' : 'FAIL';
}

export async function recordBootstrapFailure(options, error, now = new Date()) {
  const status = bootstrapFailureStatus(error);
  const reasonCode = status === 'BLOCKED' ? 'BLOCKED_REMOTE_AUTHORITY' : 'ASSERTION_FAILED';
  const errorCode = typeof error?.code === 'string' && /^[A-Z0-9_]{2,128}$/u.test(error.code)
    ? error.code
    : 'UNEXPECTED_ERROR';
  const attemptedArgv = ['git', 'ls-remote', '--exit-code', options.remoteUrl, options.remoteRef];
  const document = {
    schema_version: '1.0.0',
    status,
    reason_code: reasonCode,
    remote_url: options.remoteUrl,
    remote_ref: options.remoteRef,
    requested_remote_sha: options.remoteSha,
    attempted_argv: attemptedArgv,
    attempt_exit_code: Number.isInteger(error?.exitCode) && error.exitCode >= 0
      ? error.exitCode
      : null,
    attempt_evidence: [sha256(canonicalJson({
      error_code: errorCode,
      reason_code: reasonCode,
      status,
    }))],
    rerun_argv: [
      'node', 'scripts/acceptance/run-foundation-acceptance.mjs',
      '--remote-url', options.remoteUrl,
      '--remote-ref', options.remoteRef,
      '--remote-sha', options.remoteSha,
    ],
    occurred_at: now.toISOString(),
    owner: OWNER,
  };
  await validateBootstrapResult(document);
  const output = resolve(
    options.outputBase ?? resolve(options.repository, 'build', 'acceptance'),
    options.remoteSha,
  );
  await atomicWriteJson(resolve(output, 'remote-authority-result.json'), document);
  return document;
}

async function main() {
  let options;
  try {
    options = parseAcceptanceCli(process.argv.slice(2));
    const result = options.insideDetached
      ? await runInsideDetached(options)
      : { summary: await runAuthoritativeAcceptance(options) };
    process.stdout.write(`foundation-acceptance: ${result.summary.status}\n`);
    process.exitCode = verdictExitCode(result.summary.status);
  } catch (error) {
    const status = options && !options.insideDetached
      ? (await recordBootstrapFailure(options, error)).status
      : 'FAIL';
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`foundation-acceptance: ${status} (${code})\n`);
    process.exitCode = status === 'BLOCKED' ? 2 : 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
