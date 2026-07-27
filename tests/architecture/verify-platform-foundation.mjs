import { readFile, readdir, stat } from 'node:fs/promises';
import { resolve, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const EXPECTED_IMAGE_LOCK = 'infra/images/images.lock.json';
const REQUIRED_DOCUMENT_FACTS = [
  'Java 21 with Spring Boot and Spring Modulith',
  'PostgreSQL is the sole business fact and coordination database',
  'Forced row-level security',
  'Temporal is orchestration progress, not business authority',
  'mandatory frontend TLS 1.3 mutual authentication',
  'final whole-record guard',
  'ServiceAccount token automount is disabled',
  'Standard NetworkPolicy proves only L3/L4 reachability',
  'GitLab-first',
  '`infra/images/images.lock.json` is the sole third-party image lock',
  'Cosign keyless signatures',
  'Overall `PASS` requires both verdicts',
];

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));

function fail(message) {
  process.stderr.write(`platform-foundation-architecture: FAIL ${message}\n`);
  process.exitCode = 1;
}

async function filesUnder(directory) {
  const output = [];
  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error?.code === 'ENOENT') return output;
    throw error;
  }
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    if (['.git', '.gradle', '.worktrees', 'build', 'node_modules'].includes(entry.name)) continue;
    const absolute = resolve(directory, entry.name);
    if (entry.isDirectory()) output.push(...await filesUnder(absolute));
    else if (entry.isFile()) output.push(absolute);
  }
  return output;
}

async function verifyDocument() {
  const document = await readFile(resolve(root, 'docs/architecture/platform-foundation.md'), 'utf8');
  for (const fact of REQUIRED_DOCUMENT_FACTS) {
    if (!document.includes(fact)) fail(`missing documented fact: ${fact}`);
  }
  if (/\b(?:TODO|FIXME|TBD|placeholder)\b/i.test(document)) {
    fail('architecture document contains an unfinished marker');
  }
}

async function verifySingleImageLock() {
  const candidates = (await filesUnder(root))
    .map((path) => relative(root, path).split(sep).join('/'))
    .filter((path) => /(?:^|\/)images[.]lock[.]json$/i.test(path));
  const extras = candidates.filter((path) => path !== EXPECTED_IMAGE_LOCK);
  if (extras.length > 0) fail(`secondary image locks: ${extras.join(',')}`);
}

async function verifyProviderNeutralCore() {
  const ciFiles = (await filesUnder(resolve(root, 'scripts/ci')))
    .filter((path) => path.endsWith('.mjs'));
  for (const path of ciFiles) {
    const normalized = relative(root, path).split(sep).join('/');
    if (normalized === 'scripts/ci/adapters/github-actions-context.mjs') continue;
    const source = await readFile(path, 'utf8');
    if (/GITHUB_|github\.com|CI_COMMIT|origin\/main/.test(source)) {
      fail(`provider-specific CI core: ${normalized}`);
    }
  }
  const gitlabFixture = await readFile(
    resolve(root, 'infra/local/wiremock/mappings/gitlab-get-project.json'),
    'utf8',
  );
  if (!gitlabFixture.includes('/api/v4/projects/77831') || /api\.github|\/repos\//.test(gitlabFixture)) {
    fail('local product provider fixture is not GitLab-first');
  }
}

async function verifyTrustBoundaries() {
  const collector = await readFile(resolve(root, 'infra/local/otel-collector.yaml'), 'utf8');
  if (/^\s*debug:/m.test(collector) || collector.includes('exporters: [debug]')) {
    fail('collector debug exporter is enabled');
  }
  const summarySchema = JSON.parse(await readFile(
    resolve(root, 'contracts/acceptance/foundation-summary.schema.json'),
    'utf8',
  ));
  const serialized = JSON.stringify(summarySchema);
  if (!serialized.includes('code_verdict_digest') || !serialized.includes('environment_verdict_digest')) {
    fail('summary schema does not bind both verdicts');
  }
  const composeInfo = await stat(resolve(root, 'infra/local/compose.yaml'));
  if (!composeInfo.isFile()) fail('local topology is absent');
}

await verifyDocument();
await verifySingleImageLock();
await verifyProviderNeutralCore();
await verifyTrustBoundaries();
if (!process.exitCode) process.stdout.write('platform-foundation-architecture: PASS\n');
