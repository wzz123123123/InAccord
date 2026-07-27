import assert from 'node:assert/strict';
import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import test from 'node:test';

import { renderAll } from '../../scripts/ci/render-runtime-dockerfiles.mjs';

const root = path.resolve(import.meta.dirname, '..', '..');
const lockPath = path.join(root, 'infra/images/images.lock.json');
const dockerfiles = [
  'apps/control-plane/api/Dockerfile',
  'apps/control-plane/worker/Dockerfile',
  'apps/webhook-edge/Dockerfile',
];
const digest = (character) => `sha256:${character.repeat(64)}`;

async function exists(target) {
  try { await access(target); return true; } catch { return false; }
}

function syntheticLock() {
  return {
    images: {
      'java-build': { canonical_repository: 'docker.io/library/eclipse-temurin', platforms: { 'linux/amd64': { digest: digest('a') }, 'linux/arm64': { digest: digest('b') } } },
      'java-runtime': { canonical_repository: 'docker.io/library/eclipse-temurin', platforms: { 'linux/amd64': { digest: digest('c') }, 'linux/arm64': { digest: digest('d') } } },
    },
  };
}

test('Dockerfile renderer produces exact lock-bound offline runtime definitions', async () => {
  const rendered = renderAll(syntheticLock(), 'linux/amd64');
  assert.equal(rendered.length, 3);
  for (const item of rendered) {
    assert.match(item.bytes, /^ARG BUILD_IMAGE=docker[.]io\/library\/eclipse-temurin@sha256:a{64}$/mu);
    assert.match(item.bytes, /^ARG RUNTIME_IMAGE=docker[.]io\/library\/eclipse-temurin@sha256:c{64}$/mu);
    assert.match(item.bytes, /RUN --network=none .*--offline.*--dependency-verification=strict/u);
    assert.match(item.bytes, /verified-gradle-cache/u);
    assert.match(item.bytes, /ENTRYPOINT \["\/usr\/bin\/java","-jar","\/opt\/accord\/app[.]jar"\]/u);
    assert.doesNotMatch(item.bytes.split('FROM ${RUNTIME_IMAGE}')[1], /^RUN\s/mu);
  }
  assert.match(rendered[0].bytes, /USER 10001:10001/u);
  assert.match(rendered[1].bytes, /USER 10002:10002/u);
  assert.match(rendered[2].bytes, /USER 10003:10003/u);
  assert.match(rendered[1].bytes, /\/opt\/accord\/bin\/worker-probe[.]jar/u);
});

test('missing authoritative lock is an honest external block and never fabricates Dockerfiles', async (context) => {
  if (await exists(lockPath)) {
    const lock = JSON.parse(await readFile(lockPath, 'utf8'));
    const rendered = renderAll(lock, 'linux/amd64');
    for (const item of rendered) assert.equal(await readFile(path.join(root, item.dockerfile), 'utf8'), item.bytes);
    return;
  }
  for (const relative of dockerfiles) assert.equal(await exists(path.join(root, relative)), false, relative);
  const result = await new Promise((resolve) => {
    let output = '';
    const child = spawn(process.execPath, ['scripts/ci/render-runtime-dockerfiles.mjs', '--mode', 'verify'], {
      cwd: root, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.on('data', (chunk) => { output += chunk.toString('utf8'); });
    child.once('close', (code) => resolve({ code, output }));
  });
  assert.equal(result.code, 2);
  assert.equal(JSON.parse(result.output).status, 'BLOCKED');
  context.diagnostic('CODE policy verified; EXTERNAL authoritative image lock is absent');
});

test('worker probe and Gradle task enforce JDK-only fixed-code behavior', async () => {
  const source = await readFile(path.join(root, 'apps/control-plane/worker/src/probe/java/com/inforvans/accord/controlplane/worker/probe/WorkerProbe.java'), 'utf8');
  assert.doesNotMatch(source, /^import (?!java[.])/mu);
  assert.match(source, /LinkOption[.]NOFOLLOW_LINKS/u);
  assert.match(source, /MAX_STATE_BYTES = 32/u);
  assert.match(source, /MIN_TIMEOUT_SECONDS = 5/u);
  assert.match(source, /MAX_TIMEOUT_SECONDS = 300/u);
  assert.match(source, /result[.]get\(2, TimeUnit[.]SECONDS\)/u);
  assert.doesNotMatch(source, /printStackTrace|exception[.]getMessage|state[.]toString/u);
  const build = await readFile(path.join(root, 'apps/control-plane/worker/build.gradle'), 'utf8');
  assert.match(build, /sourceSets\s*\{[\s\S]*probe\s*\{/u);
  assert.match(build, /workerProbeJar/u);
  assert.match(build, /archiveFileName = 'worker-probe[.]jar'/u);
});

test('Docker context is deny-first and admits only content-addressed cache evidence', async () => {
  const source = await readFile(path.join(root, '.dockerignore'), 'utf8');
  assert.match(source, /^\*\*$/mu);
  for (const pattern of ['.git', '.gradle', 'node_modules', '.venv', '.terraform', '.env', '*credential*', '*secret*', 'infra/local/data']) assert.ok(source.includes(pattern), pattern);
  assert.match(source, /!build\/ci\/verified-gradle-cache\/\*\*/u);
  assert.doesNotMatch(source, /!\*\*\/build\/\*\*/u);
});
