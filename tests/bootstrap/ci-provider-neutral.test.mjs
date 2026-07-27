import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

const root = path.resolve(import.meta.dirname, '..', '..');

async function json(relative) {
  return JSON.parse(await readFile(path.join(root, relative), 'utf8'));
}

function validator(schema) {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  return ajv.compile(schema);
}

test('canonical CI context is closed and production rejects fixture authorities', async () => {
  const validate = validator(await json('contracts/ci/ci-context.schema.json'));
  const fixture = await json('contracts/golden-fixtures/supply-chain/ci-context.json');
  assert.equal(validate(fixture), true, JSON.stringify(validate.errors));
  assert.equal(validate({ ...fixture, provider_api_url: 'https://example.invalid' }), false);
  assert.equal(validate({ ...fixture, environment_class: 'production' }), false);
  const production = {
    ...fixture,
    environment_class: 'production',
    remote_url: 'ssh://git.example.com/accord.git',
    registry_repository: 'registry.example.com/accord',
    builder_issuer: 'https://issuer.example.com',
  };
  assert.equal(validate(production), false, 'repeated fixture SHAs are forbidden in production');
  assert.equal(validate({
    ...production,
    remote_sha: '0123456789abcdef0123456789abcdef01234567',
    base_sha: '1123456789abcdef0123456789abcdef01234567',
    tree_sha: '2123456789abcdef0123456789abcdef01234567',
  }), true, JSON.stringify(validate.errors));
});

test('provider environment access is isolated to the GitHub adapter', async () => {
  const directory = path.join(root, 'scripts', 'ci');
  const files = (await readdir(directory)).filter((name) => name.endsWith('.mjs'));
  for (const name of files) {
    const source = await readFile(path.join(directory, name), 'utf8');
    assert.doesNotMatch(source, /GITHUB_|CI_COMMIT|CI_JOB|github[.]com|origin\/main|api[.]github/iu, name);
    assert.doesNotMatch(source, /shell\s*:\s*true/u, name);
    if (source.includes('spawn(')) assert.match(source, /shell\s*:\s*false/u, name);
  }
  const adapter = await readFile(path.join(directory, 'adapters', 'github-actions-context.mjs'), 'utf8');
  assert.match(adapter, /GITHUB_SHA/u);
  assert.match(adapter, /GITHUB_EVENT_PATH/u);
  assert.doesNotMatch(adapter, /GITLAB|CI_JOB_TOKEN|PRIVATE_TOKEN/u);
});

test('tool pins preserve FT13 and add only the fixed ORAS version', async () => {
  const pins = Object.fromEntries((await readFile(path.join(root, '.tool-versions'), 'utf8'))
    .trim().split(/\r?\n/u).map((line) => line.split(/\s+/u, 2)));
  assert.equal(pins.docker, '29.4.2');
  assert.equal(pins['docker-buildx'], '0.33.0');
  assert.equal(pins['docker-compose'], '5.1.3');
  assert.equal(pins.git, '2.52.0');
  assert.equal(pins.oras, '1.2.2');
});

test('FT16 verifies and executes the pinned Gradle wrapper instead of a PATH Gradle', async () => {
  const source = await readFile(path.join(root, 'scripts', 'ci', 'verify.mjs'), 'utf8');
  assert.match(source, /id:\s*'gradle'[\s\S]*executable:\s*'java'[\s\S]*gradle-wrapper[.]jar[\s\S]*GradleWrapperMain/u);
  assert.match(source, /gate\('offline-gradle',\s*'java',\s*\[[\s\S]*gradle-wrapper[.]jar[\s\S]*GradleWrapperMain[\s\S]*--offline/u);
  assert.doesNotMatch(source, /gate\('offline-gradle',\s*'gradle'/u);
});

test('PowerShell wrapper is PS5.1-compatible and only delegates to Node', async () => {
  const source = (await readFile(path.join(root, 'scripts/ci/verify.ps1'), 'utf8')).replaceAll('\r\n', '\n');
  assert.equal(source, "$ErrorActionPreference = 'Stop'\n& node (Join-Path $PSScriptRoot 'verify.mjs') @args\nexit $LASTEXITCODE\n");
});

test('workflows use pinned actions, bounded jobs, minimum permissions, and protected releases', async () => {
  for (const name of ['verify.yml', 'release-images.yml', 'release-accordctl.yml']) {
    const source = await readFile(path.join(root, '.github/workflows', name), 'utf8');
    YAML.parse(source);
    for (const match of source.matchAll(/uses:\s*[^\s@]+@([^\s]+)/gu)) assert.match(match[1], /^[0-9a-f]{40}$/u, `${name}: ${match[0]}`);
    assert.match(source, /timeout-minutes:/u, name);
    assert.doesNotMatch(source, /continue-on-error|[|][|]\s*true/u, name);
    assert.match(source, /permissions:\s*\n\s+contents: read/u, name);
    assert.match(source, /github-actions-context[.]mjs/u, name);
  }
  for (const name of ['release-images.yml', 'release-accordctl.yml']) {
    const source = await readFile(path.join(root, '.github/workflows', name), 'utf8');
    assert.match(source, /environment: accord-release/u);
    assert.match(source, /id-token: write/u);
    assert.match(source, /packages: write/u);
    assert.match(source, /verify-release-chain[.]mjs/u);
  }
});
