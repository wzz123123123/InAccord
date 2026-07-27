import { mkdtemp, lstat, realpath, rm } from 'node:fs/promises';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
import {
  AcceptanceAssertionError,
  runNative,
} from './resolve-remote-sha.mjs';

const OBJECT_ID = /^[a-f0-9]{40,64}$/u;
const TEMPORARY_PREFIX = 'accord-foundation-';

export function isPathInside(parent, candidate) {
  const delta = relative(resolve(parent), resolve(candidate));
  return delta !== '' && delta !== '..' && !delta.startsWith(`..${sep}`) && !isAbsolute(delta);
}

export async function assertDetachedClean({ worktree, remoteSha, treeSha, run = runNative }) {
  const cwd = resolve(worktree);
  if (!OBJECT_ID.test(remoteSha) || !OBJECT_ID.test(treeSha)) {
    throw new AcceptanceAssertionError('DETACHED_BINDING_INVALID');
  }
  const head = requireGitValue(run('git', ['rev-parse', 'HEAD'], { cwd }), 'DETACHED_HEAD_UNVERIFIED');
  if (head !== remoteSha) throw new AcceptanceAssertionError('DETACHED_HEAD_MISMATCH');

  const symbolic = run('git', ['symbolic-ref', '-q', 'HEAD'], { cwd });
  if (symbolic.errorCode !== null || ![0, 1].includes(symbolic.exitCode)) {
    throw new AcceptanceAssertionError('DETACHED_SYMBOLIC_REF_UNVERIFIED');
  }
  if (symbolic.exitCode === 0) throw new AcceptanceAssertionError('DETACHED_HEAD_REQUIRED');

  const observedTree = requireGitValue(
    run('git', ['rev-parse', 'HEAD^{tree}'], { cwd }),
    'DETACHED_TREE_UNVERIFIED',
  );
  if (observedTree !== treeSha) throw new AcceptanceAssertionError('DETACHED_TREE_MISMATCH');

  const tracked = run('git', ['diff', '--quiet', '--exit-code'], { cwd });
  requireCleanExit(tracked, 'DETACHED_TRACKED_DIRTY');
  const index = run('git', ['diff', '--cached', '--quiet', '--exit-code'], { cwd });
  requireCleanExit(index, 'DETACHED_INDEX_DIRTY');
  const status = requireGitValue(
    run('git', ['status', '--porcelain=v1', '--untracked-files=all'], { cwd }),
    'DETACHED_STATUS_UNVERIFIED',
    true,
  );
  if (status !== '') throw new AcceptanceAssertionError('DETACHED_STATUS_DIRTY');

  const submodules = requireGitValue(
    run('git', ['submodule', 'status', '--recursive'], { cwd }),
    'DETACHED_SUBMODULES_UNVERIFIED',
    true,
    true,
  );
  if (submodules !== '') {
    for (const record of submodules.split(/\r?\n/u)) {
      if (!/^ [a-f0-9]{40,64} /u.test(record)) {
        throw new AcceptanceAssertionError('DETACHED_SUBMODULE_NOT_EXACT');
      }
    }
  }
  return { head, tree_sha: observedTree };
}

export async function createDetachedWorktree({
  repository = process.cwd(),
  remoteSha,
  treeSha,
  evidenceDirectory,
  run = runNative,
}) {
  if (!OBJECT_ID.test(remoteSha) || !OBJECT_ID.test(treeSha)) {
    throw new AcceptanceAssertionError('DETACHED_BINDING_INVALID');
  }
  const repositoryRoot = requireGitValue(
    run('git', ['rev-parse', '--show-toplevel'], { cwd: resolve(repository) }),
    'REPOSITORY_ROOT_UNVERIFIED',
  );
  const temporaryRoot = await realpath(tmpdir());
  const parent = await mkdtemp(join(temporaryRoot, TEMPORARY_PREFIX));
  await assertSafeTemporaryParent(temporaryRoot, parent);
  const worktree = join(parent, 'source');
  if (evidenceDirectory && !isEvidenceOutside(worktree, evidenceDirectory)) {
    await safeRemoveTemporaryParent(temporaryRoot, parent);
    throw new AcceptanceAssertionError('EVIDENCE_DIRECTORY_INSIDE_DETACHED_WORKTREE');
  }

  const added = run('git', ['worktree', 'add', '--detach', worktree, remoteSha], {
    cwd: repositoryRoot,
    timeout: 120_000,
  });
  if (added.errorCode !== null || added.exitCode !== 0) {
    await safeRemoveTemporaryParent(temporaryRoot, parent);
    throw new AcceptanceAssertionError('DETACHED_WORKTREE_CREATE_FAILED');
  }

  let cleaned = false;
  const cleanup = async () => {
    if (cleaned) return;
    cleaned = true;
    await assertSafeTemporaryParent(temporaryRoot, parent);
    const removed = run('git', ['worktree', 'remove', '--force', worktree], {
      cwd: repositoryRoot,
      timeout: 120_000,
    });
    if (removed.errorCode !== null || removed.exitCode !== 0) {
      throw new AcceptanceAssertionError('DETACHED_WORKTREE_REMOVE_FAILED');
    }
    await safeRemoveTemporaryParent(temporaryRoot, parent);
  };

  try {
    await assertDetachedClean({ worktree, remoteSha, treeSha, run });
  } catch (error) {
    await cleanup();
    throw error;
  }
  return { repository_root: repositoryRoot, temporary_parent: parent, worktree, cleanup };
}

export async function withDetachedWorktree(options, operation) {
  const detached = await createDetachedWorktree(options);
  try {
    return await operation(detached);
  } finally {
    await detached.cleanup();
  }
}

function isEvidenceOutside(worktree, evidenceDirectory) {
  const evidence = resolve(evidenceDirectory);
  const source = resolve(worktree);
  return evidence !== source && !isPathInside(source, evidence);
}

async function assertSafeTemporaryParent(temporaryRoot, parent) {
  const root = await realpath(temporaryRoot);
  const observed = await realpath(parent);
  const info = await lstat(observed);
  if (
    !info.isDirectory()
    || info.isSymbolicLink()
    || !isPathInside(root, observed)
    || resolve(observed, '..') !== root
    || !observed.split(sep).at(-1).startsWith(TEMPORARY_PREFIX)
  ) {
    throw new AcceptanceAssertionError('TEMPORARY_WORKTREE_PATH_UNSAFE');
  }
}

async function safeRemoveTemporaryParent(temporaryRoot, parent) {
  await assertSafeTemporaryParent(temporaryRoot, parent);
  await rm(parent, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
}

function requireGitValue(result, code, allowEmpty = false, preserveWhitespace = false) {
  if (result.errorCode !== null || result.exitCode !== 0) {
    throw new AcceptanceAssertionError(code);
  }
  const value = preserveWhitespace
    ? result.stdout.replace(/(?:\r?\n)+$/u, '')
    : result.stdout.trim();
  if ((!allowEmpty && value.length === 0) || (!allowEmpty && /[\r\n]/u.test(value))) {
    throw new AcceptanceAssertionError(code);
  }
  return value;
}

function requireCleanExit(result, code) {
  if (result.errorCode !== null || result.exitCode !== 0) {
    throw new AcceptanceAssertionError(code);
  }
}

function parseCli(argv) {
  const options = { repository: process.cwd() };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (value === undefined) throw new AcceptanceAssertionError('ARGUMENTS_INVALID');
    const key = {
      '--repository': 'repository',
      '--remote-sha': 'remoteSha',
      '--tree-sha': 'treeSha',
      '--evidence-dir': 'evidenceDirectory',
    }[flag];
    if (!key) throw new AcceptanceAssertionError('ARGUMENTS_INVALID');
    options[key] = value;
  }
  if (!options.remoteSha || !options.treeSha || !options.evidenceDirectory) {
    throw new AcceptanceAssertionError('ARGUMENTS_INVALID');
  }
  return options;
}

async function main() {
  try {
    const options = parseCli(process.argv.slice(2));
    await withDetachedWorktree(options, async ({ worktree }) => {
      process.stdout.write(`detached-worktree: PASS ${worktree}\n`);
    });
  } catch (error) {
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`detached-worktree: FAIL (${code})\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
