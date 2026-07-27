import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

const root = path.resolve(import.meta.dirname, '..', '..');
const shaPattern = /^[0-9a-f]{40}$/u;
const remoteUrlPattern = /^https:\/\/github[.]com\/inforvans\/[A-Za-z0-9._-]+[.]git$/u;
const remoteRefPattern = /^refs\/heads\/[A-Za-z0-9][A-Za-z0-9._/-]{0,254}$/u;
const authoritativeProjectSource = 'https://github.com/inforvans/*.git';

export class BlockedExternalEnvironmentError extends Error {
  constructor(message) {
    super(message);
    this.name = 'BlockedExternalEnvironmentError';
    this.code = 'BLOCKED_EXTERNAL_ENVIRONMENT';
  }
}

async function validatePromotion(promotion) {
  const schema = JSON.parse(await readFile(path.join(root, 'contracts', 'deployment', 'promotion-input.schema.json'), 'utf8'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  if (!validate(promotion)) throw new TypeError(`Promotion input violates its closed schema: ${ajv.errorsText(validate.errors, { separator: '; ' })}`);
}

async function assertProjectAuthority(remoteUrl) {
  const project = YAML.parse(await readFile(path.join(root, 'infra', 'argocd', 'project.yaml'), 'utf8'));
  if (project?.kind !== 'AppProject' || project.metadata?.name !== 'accord') throw new TypeError('Canonical Accord AppProject is absent');
  if (JSON.stringify(project.spec?.sourceRepos) !== JSON.stringify([authoritativeProjectSource])) throw new TypeError('AppProject source allowlist is not closed');
  if (!remoteUrlPattern.test(remoteUrl)) throw new TypeError('Remote URL is outside the AppProject repository allowlist');
  const destinations = project.spec?.destinations ?? [];
  if (destinations.length !== 1 || destinations[0].namespace !== 'accord-system' || destinations[0].server !== 'https://kubernetes.default.svc') {
    throw new TypeError('AppProject destination allowlist is not closed');
  }
  if ((project.spec?.clusterResourceWhitelist ?? []).length !== 0) throw new TypeError('AppProject may not grant cluster-scoped resources');
  const secretDenied = (project.spec?.namespaceResourceBlacklist ?? []).some((entry) => entry.group === '' && entry.kind === 'Secret');
  if (!secretDenied) throw new TypeError('AppProject must explicitly deny Secret resources');
}

function assertRemoteInput(remoteUrl, remoteRef, remoteSha) {
  if (!remoteUrl || !remoteRef || !remoteSha) throw new BlockedExternalEnvironmentError('remote URL, fully qualified ref, and SHA are all required');
  if (!remoteUrlPattern.test(remoteUrl)) throw new TypeError('Remote URL is outside the AppProject repository allowlist');
  if (!remoteRefPattern.test(remoteRef) || remoteRef.startsWith('refs/tags/') || /(?:^|\/)HEAD$/iu.test(remoteRef)) {
    throw new TypeError('Remote ref must use fully qualified refs/heads syntax; branch names, tags, and HEAD are rejected');
  }
  if (!shaPattern.test(remoteSha) || /^0{40}$/u.test(remoteSha)) throw new TypeError('Remote SHA must be an exact nonzero lowercase commit SHA');
}

function verifyRemoteSha({ remoteUrl, remoteRef, remoteSha, spawnSyncImpl }) {
  assertRemoteInput(remoteUrl, remoteRef, remoteSha);
  const result = spawnSyncImpl('git', ['ls-remote', '--exit-code', remoteUrl, remoteRef], {
    cwd: root,
    encoding: 'utf8',
    shell: false,
    windowsHide: true,
    timeout: 30_000,
    maxBuffer: 64 * 1024,
  });
  if (result.error || result.status !== 0) {
    throw new BlockedExternalEnvironmentError(`authoritative git ls-remote is unavailable (${result.error?.code ?? result.status ?? 'spawn failure'})`);
  }
  const lines = String(result.stdout ?? '').trim().split(/\r?\n/u).filter(Boolean);
  if (lines.length !== 1) throw new TypeError('git ls-remote did not return exactly one authoritative ref');
  const match = /^([0-9a-f]{40})\t([^\s]+)$/u.exec(lines[0]);
  if (!match || match[2] !== remoteRef) throw new TypeError('git ls-remote returned a different or malformed ref');
  if (match[1] !== remoteSha) throw new TypeError('Local/requested SHA does not match the authoritative remote SHA');
  return match[1];
}

async function atomicWriteYaml(target, value) {
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = path.join(path.dirname(target), `.${path.basename(target)}.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(YAML.stringify(value, { lineWidth: 0 }), 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, target);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
}

export async function renderArgoApplication({
  remoteUrl,
  remoteRef,
  remoteSha,
  promotion,
  outputPath = path.join(root, 'infra', 'argocd', 'application.yaml'),
  spawnSyncImpl = spawnSync,
}) {
  if (!promotion) throw new BlockedExternalEnvironmentError('a schema-valid promotion input is required');
  await validatePromotion(promotion);
  assertRemoteInput(remoteUrl, remoteRef, remoteSha);
  await assertProjectAuthority(remoteUrl);
  if (promotion.remote_url !== remoteUrl || promotion.remote_ref !== remoteRef || promotion.remote_sha !== remoteSha) {
    throw new TypeError('Argo remote URL/ref/SHA must exactly match the promotion input');
  }
  const verifiedSha = verifyRemoteSha({ remoteUrl, remoteRef, remoteSha, spawnSyncImpl });
  const application = {
    apiVersion: 'argoproj.io/v1alpha1',
    kind: 'Application',
    metadata: {
      name: 'accord',
      namespace: 'argocd',
      annotations: {
        'accord.inforvans.com/release-manifest-digest': promotion.release_manifest_digest,
        'accord.inforvans.com/dsse-envelope-digest': promotion.dsse_envelope_digest,
        'accord.inforvans.com/verification-receipt-digest': promotion.verification_receipt_digest,
      },
    },
    spec: {
      project: 'accord',
      source: {
        repoURL: remoteUrl,
        path: 'infra/helm/accord',
        targetRevision: verifiedSha,
      },
      destination: {
        server: 'https://kubernetes.default.svc',
        namespace: 'accord-system',
      },
      syncPolicy: {
        automated: { prune: true, selfHeal: true, allowEmpty: false },
        syncOptions: ['ServerSideApply=true', 'CreateNamespace=false', 'PruneLast=true'],
        retry: {
          limit: 3,
          backoff: { duration: '5s', factor: 2, maxDuration: '1m' },
        },
      },
    },
  };
  await atomicWriteYaml(path.resolve(outputPath), application);
  return application;
}

function parseCli(argv) {
  const options = {};
  const names = new Map([
    ['--remote-url', 'remoteUrl'],
    ['--remote-ref', 'remoteRef'],
    ['--remote-sha', 'remoteSha'],
    ['--promotion', 'promotionPath'],
    ['--output', 'outputPath'],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    const name = names.get(option);
    if (!name) throw new TypeError(`Unknown option: ${option}`);
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) throw new BlockedExternalEnvironmentError(`missing value for ${option}`);
    if (options[name]) throw new TypeError(`Repeated option: ${option}`);
    options[name] = value;
    index += 1;
  }
  for (const required of ['remoteUrl', 'remoteRef', 'remoteSha', 'promotionPath']) {
    if (!options[required]) throw new BlockedExternalEnvironmentError('remote URL/ref/SHA and promotion input are required');
  }
  options.outputPath ??= path.join(root, 'infra', 'argocd', 'application.yaml');
  return options;
}

async function main(argv) {
  const options = parseCli(argv);
  const promotion = JSON.parse(await readFile(path.resolve(root, options.promotionPath), 'utf8'));
  await renderArgoApplication({ ...options, promotion });
  process.stdout.write(`Pinned Accord Argo Application to authoritative remote SHA ${options.remoteSha}\n`);
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    await main(process.argv.slice(2));
  } catch (error) {
    if (error?.code === 'BLOCKED_EXTERNAL_ENVIRONMENT') {
      process.stderr.write(`BLOCKED_EXTERNAL_ENVIRONMENT: ${error.message}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`render-argocd: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
