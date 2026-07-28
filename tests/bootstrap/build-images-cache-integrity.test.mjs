import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

const buildImages = await import('../../scripts/ci/build-images.mjs');
const verifyCacheArtifact = buildImages.verifyCacheArtifact;

function sha256(bytes) {
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
}

async function createCacheFixture() {
  const container = await mkdtemp(path.join(os.tmpdir(), 'accord-image-cache-integrity-'));
  const root = path.join(container, 'artifact');
  const cacheBytes = Buffer.from('locked dependency cache\n');
  const gradleBytes = Buffer.from('locked gradle launcher\n');
  await mkdir(path.join(root, 'cache'), { recursive: true });
  await mkdir(path.join(root, 'gradle-home', 'bin'), { recursive: true });
  await writeFile(path.join(root, 'cache', 'entry.bin'), cacheBytes);
  await writeFile(path.join(root, 'gradle-home', 'bin', 'gradle'), gradleBytes);
  const rows = [
    `${sha256(cacheBytes).slice(7)}  cache/entry.bin`,
    `${sha256(gradleBytes).slice(7)}  gradle-home/bin/gradle`,
  ];
  return { container, root, rows };
}

async function writeManifest(root, rows) {
  const bytes = Buffer.from(['# accord-verified-gradle-cache-v1', ...rows, ''].join('\n'), 'utf8');
  await writeFile(path.join(root, 'manifest.sha256'), bytes);
  return sha256(bytes);
}

test('build image cache accepts only the exact manifest inventory', async () => {
  assert.equal(typeof verifyCacheArtifact, 'function', 'build-images must expose its cache verifier for an independent integrity gate');
  const fixture = await createCacheFixture();
  try {
    const digest = await writeManifest(fixture.root, fixture.rows);
    assert.equal(await verifyCacheArtifact(fixture.root, digest), fixture.root);

    await mkdir(path.join(fixture.root, 'cache', 'init.d'), { recursive: true });
    await writeFile(path.join(fixture.root, 'cache', 'init.d', 'compromised.gradle'), 'throw new Error("executed")\n');
    await assert.rejects(verifyCacheArtifact(fixture.root, digest), /unlisted|inventory|extra/u);
  } finally {
    await rm(fixture.container, { recursive: true, force: true });
  }
});

test('build image cache accepts canonical repository input bindings emitted by the cache seeder', async () => {
  assert.equal(typeof verifyCacheArtifact, 'function');
  const fixture = await createCacheFixture();
  try {
    const inputDigest = sha256(Buffer.from('binding\n'));
    const digest = await writeManifest(fixture.root, [
      `# input .tool-versions ${inputDigest}`,
      `# input toolchain/java-version ${inputDigest}`,
      ...fixture.rows,
    ]);
    assert.equal(await verifyCacheArtifact(fixture.root, digest), fixture.root);
  } finally {
    await rm(fixture.container, { recursive: true, force: true });
  }
});

test('build image cache rejects duplicate and non-canonical manifest paths', async () => {
  assert.equal(typeof verifyCacheArtifact, 'function');
  const cases = [
    { label: 'duplicate', rewrite: (rows) => [...rows, rows[0]] },
    { label: 'dot segment', rewrite: (rows) => [rows[0].replace('cache/entry.bin', 'cache/./entry.bin'), rows[1]] },
    { label: 'empty segment', rewrite: (rows) => [rows[0].replace('cache/entry.bin', 'cache//entry.bin'), rows[1]] },
    { label: 'parent segment', rewrite: (rows) => [rows[0].replace('cache/entry.bin', 'cache/../entry.bin'), rows[1]] },
    { label: 'backslash', rewrite: (rows) => [rows[0].replace('cache/entry.bin', 'cache\\entry.bin'), rows[1]] },
  ];
  for (const scenario of cases) {
    const fixture = await createCacheFixture();
    try {
      const digest = await writeManifest(fixture.root, scenario.rewrite(fixture.rows));
      await assert.rejects(verifyCacheArtifact(fixture.root, digest), /manifest|canonical|duplicated|path/u, scenario.label);
    } finally {
      await rm(fixture.container, { recursive: true, force: true });
    }
  }
});

test('build image cache validates manifest content addressing and rejects missing files', async () => {
  assert.equal(typeof verifyCacheArtifact, 'function');
  const fixture = await createCacheFixture();
  try {
    const digest = await writeManifest(fixture.root, fixture.rows);
    await assert.rejects(verifyCacheArtifact(fixture.root, `sha256:${'0'.repeat(64)}`), /content address|digest/u);
    await rm(path.join(fixture.root, 'cache', 'entry.bin'));
    await assert.rejects(verifyCacheArtifact(fixture.root, digest), /missing|inventory/u);
  } finally {
    await rm(fixture.container, { recursive: true, force: true });
  }
});

test('build image cache rejects symbolic links when the host permits creating them', async (context) => {
  assert.equal(typeof verifyCacheArtifact, 'function');
  const fixture = await createCacheFixture();
  try {
    const outside = path.join(fixture.container, 'outside.gradle');
    const linked = path.join(fixture.root, 'cache', 'linked.gradle');
    await writeFile(outside, 'outside\n');
    try {
      await symlink(outside, linked, 'file');
    } catch (error) {
      if (['EPERM', 'EACCES'].includes(error?.code)) {
        context.skip('host does not permit creating file symlinks');
        return;
      }
      throw error;
    }
    const linkedBytes = Buffer.from('outside\n');
    const digest = await writeManifest(fixture.root, [...fixture.rows, `${sha256(linkedBytes).slice(7)}  cache/linked.gradle`]);
    await assert.rejects(verifyCacheArtifact(fixture.root, digest), /symbolic link/u);
  } finally {
    await rm(fixture.container, { recursive: true, force: true });
  }
});
