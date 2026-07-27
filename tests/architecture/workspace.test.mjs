import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

test('web workspace extends the locked foundation toolchain', async () => {
  const root = JSON.parse(await readFile(new URL('../../package.json', import.meta.url)));
  const web = JSON.parse(await readFile(new URL('../../apps/web/package.json', import.meta.url)));
  const tsconfig = JSON.parse(await readFile(new URL('../../tsconfig.base.json', import.meta.url)));
  const workspace = await readFile(new URL('../../pnpm-workspace.yaml', import.meta.url), 'utf8');

  assert.equal(root.packageManager, 'pnpm@10.12.4');
  assert.equal(root.engines.node, '22.22.1');
  assert.equal(web.dependencies.react, '19.1.0');
  assert.match(web.dependencies['react-router'], /^7\./);
  assert.match(web.dependencies['@tanstack/react-query'], /^5\./);
  assert.equal(tsconfig.compilerOptions.strict, true);
  assert.equal(tsconfig.compilerOptions.noUncheckedIndexedAccess, true);
  assert.match(workspace, /^\s*- packages\/\*\s*$/m);
  assert.equal(root.scripts['build:web'], 'pnpm --filter @accord/web build');
  assert.equal(web.scripts.build, 'tsc -p tsconfig.json --noEmit --pretty false && vite build');
  assert.equal(web.scripts.typecheck, 'tsc -p tsconfig.json --noEmit --pretty false && tsc -p tsconfig.test.json --noEmit --pretty false');
});
