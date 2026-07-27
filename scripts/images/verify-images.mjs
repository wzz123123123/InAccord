import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  REPOSITORY_ROOT,
  canonicalJson,
  exitCodeForStatus,
  sha256,
  writeCheckResult,
} from '../verification/check-result.mjs';
import {
  COMPOSE_PATH,
  IMAGE_ENV_PATH,
  LOCK_PATH,
  expectedComposeEnvironment,
} from './render-compose-images.mjs';
import {
  ImageLockBlockedError,
  loadImageLock,
  renderAll,
} from '../ci/render-runtime-dockerfiles.mjs';

const RESULT_PATH = 'build/verification/ft13-image-consumers.json';

async function findImageLocks(directory, matches = []) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (['.git', '.gradle', 'build', 'node_modules', 'state'].includes(entry.name)) continue;
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) await findImageLocks(target, matches);
    else if (/^images.*[.]lock[.]json$/u.test(entry.name)) matches.push(target);
  }
  return matches;
}

async function verify(scope) {
  const startedAt = new Date().toISOString();
  try {
    const expected = await expectedComposeEnvironment();
    const actualEnvironment = await readFile(IMAGE_ENV_PATH, 'utf8');
    if (actualEnvironment !== expected.bytes) throw new TypeError('Rendered Compose image environment is stale or non-canonical');
    const locks = (await findImageLocks(REPOSITORY_ROOT)).map((item) => path.relative(REPOSITORY_ROOT, item).replaceAll('\\', '/')).sort();
    if (JSON.stringify(locks) !== JSON.stringify(['infra/images/images.lock.json'])) {
      throw new TypeError(`Repository image-lock set is not unique: ${locks.join(', ')}`);
    }
    const composeBytes = await readFile(COMPOSE_PATH);
    const evidence = [sha256(expected.lockBytes), sha256(composeBytes), sha256(Buffer.from(actualEnvironment))];
    if (scope === 'all') {
      const { lock } = await loadImageLock();
      for (const image of renderAll(lock, expected.platform)) {
        const dockerfile = await readFile(path.join(REPOSITORY_ROOT, image.dockerfile), 'utf8');
        if (dockerfile !== image.bytes) {
          throw new TypeError(`Rendered Dockerfile differs from the authoritative lock: ${image.dockerfile}`);
        }
        evidence.push(sha256(Buffer.from(dockerfile)));
      }
    }
    const result = {
      schema_version: '1.0.0',
      check_id: 'ft13-image-consumers',
      status: 'PASS',
      reason_code: 'PASS',
      started_at: startedAt,
      finished_at: new Date().toISOString(),
      evidence: [...new Set(evidence)].sort(),
      tool_observations: [],
    };
    await writeCheckResult(result, RESULT_PATH);
    return result;
  } catch (error) {
    const blocked = error?.code === 'BLOCKED_EXTERNAL_IMAGE_RESOLUTION'
      || error instanceof ImageLockBlockedError;
    const result = {
      schema_version: '1.0.0',
      check_id: 'ft13-image-consumers',
      status: blocked ? 'BLOCKED' : 'FAIL',
      reason_code: blocked ? 'BLOCKED_EXTERNAL_IMAGE_RESOLUTION' : 'ASSERTION_FAILED',
      started_at: startedAt,
      finished_at: new Date().toISOString(),
      evidence: [],
      tool_observations: [],
    };
    await writeCheckResult(result, RESULT_PATH);
    process.stderr.write(`verify-images: ${error instanceof Error ? error.message : String(error)}\n`);
    return result;
  }
}

async function main(argv) {
  if (argv.length !== 2 || argv[0] !== '--scope' || !['all', 'local'].includes(argv[1])) {
    throw new TypeError('Expected exactly --scope local or --scope all');
  }
  const result = await verify(argv[1]);
  process.stdout.write(`${canonicalJson(result)}\n`);
  return exitCodeForStatus(result.status);
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`verify-images: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
