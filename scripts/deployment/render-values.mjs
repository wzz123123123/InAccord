import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { lstat, mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

import { validateContext } from '../ci/create-ci-context.mjs';

export const REPOSITORY_ROOT = path.resolve(import.meta.dirname, '..', '..');
export const COMPONENT_NAMES = Object.freeze(['control-api', 'control-worker', 'webhook-edge']);
const digestPattern = /^sha256:[0-9a-f]{64}$/u;
const gitShaPattern = /^[0-9a-f]{40}$/u;
const forbiddenPlaintextKey = /^(?:password|credential|credentials|token|access_key|secret_key|client_secret|private_key)$/iu;
const forbiddenDestination = /(?:provider|content|object(?:[-_]?storage)?|source|git)/iu;
const productionRenderAuthority = Symbol('verified Accord production release');
const RELEASE_VERIFICATION_TIMEOUT_MS = 15 * 60 * 1000;
const MAX_RELEASE_VERIFIER_OUTPUT_BYTES = 128 * 1024;
const MAX_RELEASE_INPUT_BYTES = 32 * 1024 * 1024;
const PINNED_RELEASE_AUTHORITY = Object.freeze({
  remoteUrl: 'https://github.com/inforvans/accord.git',
  issuer: 'https://token.actions.githubusercontent.com',
  subject: 'repo:inforvans/accord:environment:accord-release',
  audience: 'sigstore',
  protectedEnvironment: 'accord-release',
});
const RELEASE_IMAGE_NAMES = Object.freeze({
  'control-api': 'control-plane-api',
  'control-worker': 'control-plane-worker',
  'webhook-edge': 'webhook-edge',
});
const expectedComponents = Object.freeze({
  'control-api': Object.freeze({
    serviceAccount: 'accord-control-api',
    loginRole: 'accord_api_login',
    sessionRole: 'accord_api',
    profile: 'CONTROL_API',
    destinations: Object.freeze(['control_postgres', 'otlp', 'dns']),
  }),
  'control-worker': Object.freeze({
    serviceAccount: 'accord-control-worker',
    loginRole: 'accord_worker_login',
    sessionRole: 'accord_worker',
    profile: 'CONTROL_WORKER',
    destinations: Object.freeze(['control_postgres', 'temporal', 'otlp', 'dns']),
  }),
  'webhook-edge': Object.freeze({
    serviceAccount: 'accord-webhook-edge',
    loginRole: 'accord_webhook_runtime_login',
    sessionRole: 'accord_webhook_runtime',
    profile: 'WEBHOOK_EDGE',
    destinations: Object.freeze(['webhook_postgres', 'otlp', 'dns']),
  }),
});

function canonicalJson(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
}

function sha256(value) {
  const bytes = typeof value === 'string' || Buffer.isBuffer(value) ? value : canonicalJson(value);
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
}

async function loadValidators() {
  const [identitySchema, promotionSchema] = await Promise.all([
    readFile(path.join(REPOSITORY_ROOT, 'contracts', 'deployment', 'component-identity.schema.json'), 'utf8'),
    readFile(path.join(REPOSITORY_ROOT, 'contracts', 'deployment', 'promotion-input.schema.json'), 'utf8'),
  ]);
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  return {
    ajv,
    identity: ajv.compile(JSON.parse(identitySchema)),
    promotion: ajv.compile(JSON.parse(promotionSchema)),
  };
}

let validatorPromise;
async function validators() {
  validatorPromise ??= loadValidators();
  return validatorPromise;
}

function schemaError(label, validate, ajv) {
  return new TypeError(`${label} violates its closed schema: ${ajv.errorsText(validate.errors, { separator: '; ' })}`);
}

function assertExactKeys(value, expected, label) {
  const actual = Object.keys(value ?? {}).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    throw new TypeError(`${label} must contain exactly ${wanted.join(', ')}`);
  }
}

function findForbiddenPlaintext(value, currentPath = '$') {
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index += 1) findForbiddenPlaintext(value[index], `${currentPath}[${index}]`);
    return;
  }
  if (!value || typeof value !== 'object') return;
  for (const [key, child] of Object.entries(value)) {
    if (key === 'workload_identity_ids') throw new TypeError(`${currentPath}.${key} is a forbidden positional identity list`);
    const inlineSecretContainer = /(?:^|[.])external_secret(?:[.]|$)/u.test(currentPath)
      && /^(?:value|values|data|stringData)$/u.test(key);
    if (forbiddenPlaintextKey.test(key) || inlineSecretContainer) throw new TypeError(`${currentPath}.${key} is a forbidden plaintext secret field`);
    findForbiddenPlaintext(child, `${currentPath}.${key}`);
  }
}

function ipv4Number(address) {
  const octets = address.split('.').map(Number);
  if (octets.length !== 4 || octets.some((octet) => !Number.isInteger(octet) || octet < 0 || octet > 255)) {
    throw new TypeError(`Invalid IPv4 address: ${address}`);
  }
  return octets.reduce((result, octet) => (result << 8n) | BigInt(octet), 0n);
}

function ipv4Range(cidr) {
  const match = /^((?:[0-9]{1,3}[.]){3}[0-9]{1,3})\/(\d|[12]\d|3[0-2])$/u.exec(cidr);
  if (!match) return undefined;
  const prefix = Number(match[2]);
  const address = ipv4Number(match[1]);
  const hostBits = 32n - BigInt(prefix);
  const mask = prefix === 0 ? 0n : ((1n << 32n) - 1n) ^ ((1n << hostBits) - 1n);
  const start = address & mask;
  return { start, end: start + ((1n << hostBits) - 1n), prefix };
}

function ipv6Number(address) {
  if (typeof address !== 'string' || address.includes('.')) throw new TypeError(`Invalid IPv6 address: ${address}`);
  const compression = address.indexOf('::');
  if (compression !== -1 && compression !== address.lastIndexOf('::')) {
    throw new TypeError(`Invalid IPv6 address: ${address}`);
  }
  const [headText, tailText = ''] = compression === -1 ? [address] : address.split('::');
  const parseSide = (text) => text === ''
    ? []
    : text.split(':').map((part) => {
        if (!/^[0-9a-f]{1,4}$/iu.test(part)) throw new TypeError(`Invalid IPv6 address: ${address}`);
        return Number.parseInt(part, 16);
      });
  const head = parseSide(headText);
  const tail = parseSide(tailText);
  const specified = head.length + tail.length;
  if ((compression === -1 && specified !== 8) || (compression !== -1 && specified >= 8)) {
    throw new TypeError(`Invalid IPv6 address: ${address}`);
  }
  const groups = compression === -1
    ? head
    : [...head, ...Array(8 - specified).fill(0), ...tail];
  return groups.reduce((result, group) => (result << 16n) | BigInt(group), 0n);
}

function ipv6Range(cidr) {
  const match = /^([0-9a-f:]+)\/(\d|[1-9]\d|1[01]\d|12[0-8])$/iu.exec(cidr);
  if (!match) return undefined;
  const prefix = Number(match[2]);
  const address = ipv6Number(match[1]);
  const hostBits = 128n - BigInt(prefix);
  const mask = prefix === 0 ? 0n : ((1n << 128n) - 1n) ^ ((1n << hostBits) - 1n);
  const start = address & mask;
  return { start, end: start + ((1n << hostBits) - 1n), prefix };
}

export function validateAuditedCidrs(route, now = new Date()) {
  if (route?.mode !== 'AUDITED_CIDR') throw new TypeError('Expected an AUDITED_CIDR route');
  if (!Array.isArray(route.cidrs) || route.cidrs.length === 0) throw new TypeError('AUDITED_CIDR requires at least one CIDR');
  if (!digestPattern.test(route.evidence_digest ?? '')) throw new TypeError('AUDITED_CIDR requires a nonzero evidence digest');
  if (typeof route.owner !== 'string' || route.owner.trim() === '') throw new TypeError('AUDITED_CIDR requires an owner');
  const expiresAt = Date.parse(route.evidence_expires_at ?? '');
  if (!Number.isFinite(expiresAt) || expiresAt <= now.getTime()) throw new TypeError('AUDITED_CIDR evidence is expired');

  const metadataAddresses = [
    ipv4Number('169.254.169.254'),
    ipv4Number('169.254.170.2'),
    ipv4Number('100.100.100.200'),
  ];
  const metadataIpv6Addresses = [ipv6Number('fd00:ec2::254')];
  for (const cidr of route.cidrs) {
    if (cidr === '0.0.0.0/0' || cidr === '::/0') throw new TypeError('AUDITED_CIDR may not contain a default route');
    const range = ipv4Range(cidr);
    if (range) {
      if (range.prefix === 0) throw new TypeError('AUDITED_CIDR may not contain a default route');
      if (metadataAddresses.some((address) => address >= range.start && address <= range.end)) {
        throw new TypeError(`AUDITED_CIDR overlaps a cloud metadata endpoint: ${cidr}`);
      }
      continue;
    }
    let ipv6;
    try {
      ipv6 = ipv6Range(cidr);
    } catch {
      throw new TypeError(`Invalid CIDR: ${cidr}`);
    }
    if (!ipv6) throw new TypeError(`Invalid CIDR: ${cidr}`);
    if (metadataIpv6Addresses.some((address) => address >= ipv6.start && address <= ipv6.end)) {
      throw new TypeError(`AUDITED_CIDR overlaps a cloud metadata endpoint: ${cidr}`);
    }
  }
  return route;
}

function assertRoute(route, label) {
  if (!route || typeof route !== 'object') throw new TypeError(`${label} route is absent`);
  if (!Array.isArray(route.ports) || route.ports.length === 0) throw new TypeError(`${label} has no bounded ports`);
  if (route.mode === 'KUBERNETES_SERVICE' || route.mode === 'EGRESS_GATEWAY') {
    if (!route.namespace_selector?.key || !route.namespace_selector?.value) throw new TypeError(`${label} lacks an exact namespace selector`);
    if (!route.pod_selector?.key || !route.pod_selector?.value) throw new TypeError(`${label} lacks an exact pod selector`);
    if (!route.service_name) throw new TypeError(`${label} lacks an exact service name`);
  } else if (route.mode === 'CILIUM_FQDN') {
    if (typeof route.fqdn !== 'string' || route.fqdn.includes('*') || !route.fqdn.includes('.')) {
      throw new TypeError(`${label} requires an exact non-wildcard FQDN`);
    }
  } else if (route.mode === 'AUDITED_CIDR') {
    validateAuditedCidrs(route);
  } else {
    throw new TypeError(`${label} uses an unsupported route mode`);
  }
}

function assertUniqueComponents(components, selector, label) {
  const values = COMPONENT_NAMES.map((name) => selector(components[name]));
  if (values.some((value) => value === undefined || value === null || value === '')) throw new TypeError(`${label} is absent`);
  if (new Set(values).size !== COMPONENT_NAMES.length) throw new TypeError(`${label} must be unique across components`);
}

function assertTls(name, component) {
  const targetKeys = new Set(Object.values(component.external_secret.target_keys));
  const postgres = component.tls?.postgres;
  if (postgres?.mode !== 'VERIFY_FULL' || !targetKeys.has(postgres.ca_secret_key) || !postgres.server_name) {
    throw new TypeError(`${name} PostgreSQL requires verify-full, CA, and server name`);
  }
  const otlp = component.tls?.otlp;
  if (otlp?.mode !== 'TLS' || otlp.protocol !== 'HTTPS' || !targetKeys.has(otlp.ca_secret_key) || !otlp.server_name) {
    throw new TypeError(`${name} OTLP requires TLS, CA, and server name`);
  }
  if (name === 'control-worker') {
    const temporal = component.tls?.temporal;
    if (temporal?.mode !== 'MTLS'
      || !targetKeys.has(temporal.ca_secret_key)
      || !targetKeys.has(temporal.client_certificate_secret_key)
      || !targetKeys.has(temporal.client_private_key_secret_key)
      || !temporal.server_name
      || !temporal.namespace
      || !temporal.task_queue
      || !component.external_secret.temporal_client_certificate_ref) {
      throw new TypeError('control-worker Temporal mTLS requires exact namespace, task queue, material, and server name');
    }
  } else if (component.tls?.temporal || component.external_secret.temporal_client_certificate_ref) {
    throw new TypeError('Temporal client certificate binding belongs only to control-worker');
  }
}

export function assertDeploymentBindings(
  identity,
  promotion,
  requestedEnvironmentClass = promotion?.environment_class,
  authority = undefined,
) {
  if (!identity || !promotion) throw new TypeError('Both component identity and promotion inputs are required');
  findForbiddenPlaintext(identity);
  findForbiddenPlaintext(promotion);
  assertExactKeys(identity.components, COMPONENT_NAMES, 'components');
  assertExactKeys(promotion.images, COMPONENT_NAMES, 'promotion images');
  if (!['test', 'production'].includes(requestedEnvironmentClass)) throw new TypeError('Requested environment class must be test or production');
  if (identity.environment_class !== promotion.environment_class || requestedEnvironmentClass !== promotion.environment_class) {
    throw new TypeError('Component, promotion, and requested environment class must match');
  }
  if (requestedEnvironmentClass === 'production' && authority !== productionRenderAuthority) {
    throw new TypeError('Production rendering requires authoritative release-chain verification');
  }
  if (requestedEnvironmentClass === 'production' && (!promotion.signature_verified || !promotion.scan_policy_passed)) {
    throw new TypeError('Production requires verified signatures and passing scan policy');
  }
  const remotePairs = [
    ['remote URL', identity.remote_authority.repository_url, promotion.remote_url],
    ['remote SHA', identity.remote_authority.commit_sha, promotion.remote_sha],
    ['remote tree SHA', identity.remote_authority.tree_sha, promotion.remote_tree_sha],
    ['release manifest digest', identity.release_evidence.release_manifest_digest, promotion.release_manifest_digest],
    ['DSSE envelope digest', identity.release_evidence.dsse_envelope_digest, promotion.dsse_envelope_digest],
    ['verification receipt digest', identity.release_evidence.verification_receipt_digest, promotion.verification_receipt_digest],
  ];
  for (const [label, left, right] of remotePairs) if (left !== right) throw new TypeError(`${label} does not cross-bind`);
  if (!gitShaPattern.test(promotion.remote_sha) || !gitShaPattern.test(promotion.remote_tree_sha)) throw new TypeError('Remote SHAs must be lowercase immutable SHAs');

  const components = identity.components;
  assertUniqueComponents(components, (entry) => entry.service_account, 'ServiceAccount');
  assertUniqueComponents(components, (entry) => entry.identity.provider_name, 'workload identity provider name');
  assertUniqueComponents(components, (entry) => entry.identity.subject, 'workload identity subject');
  assertUniqueComponents(components, (entry) => entry.identity.audience, 'workload identity audience');
  assertUniqueComponents(components, (entry) => entry.external_secret.store.name, 'ExternalSecret store');
  assertUniqueComponents(components, (entry) => entry.external_secret.remote_ref, 'ExternalSecret remote reference');
  assertUniqueComponents(components, (entry) => entry.external_secret.target_name, 'ExternalSecret target');
  assertUniqueComponents(components, (entry) => entry.database.secret_name, 'database secret');
  assertUniqueComponents(components, (entry) => entry.database.login_role, 'database login role');
  assertUniqueComponents(components, (entry) => entry.database.session_role, 'database session role');
  assertUniqueComponents(components, (entry) => entry.image.repository, 'image repository');
  assertUniqueComponents(components, (entry) => `${entry.runtime.uid}:${entry.runtime.gid}`, 'runtime UID/GID');

  for (const name of COMPONENT_NAMES) {
    const component = components[name];
    const expected = expectedComponents[name];
    if (component.service_account !== expected.serviceAccount
      || component.database.login_role !== expected.loginRole
      || component.database.session_role !== expected.sessionRole) {
      throw new TypeError(`${name} has a wrong ServiceAccount or database role tuple`);
    }
    if (component.identity.subject !== `system:serviceaccount:${identity.namespace}:${component.service_account}`
      || component.identity.audience !== component.service_account) {
      throw new TypeError(`${name} workload subject/audience is not bound to its ServiceAccount`);
    }
    if (component.database.secret_name !== component.external_secret.target_name
      || component.database.password_key !== component.external_secret.target_keys.database_password) {
      throw new TypeError(`${name} database secret is not bound to its ExternalSecret target/key`);
    }
    if (name === 'webhook-edge') {
      if (component.external_secret.target_keys.webhook_bindings !== 'webhook-bindings.json') {
        throw new TypeError('webhook-edge binding projection is not bound to its ExternalSecret');
      }
    } else if (component.external_secret.target_keys.webhook_bindings) {
      throw new TypeError('webhook binding projection belongs only to webhook-edge');
    }
    const promotedImage = promotion.images[name];
    if (component.image.repository !== promotedImage.repository || component.image.digest !== promotedImage.digest) {
      throw new TypeError(`${name} image repository/digest does not cross-bind to promotion input`);
    }
    if (!digestPattern.test(component.image.digest) || /[:@]/u.test(component.image.repository)) throw new TypeError(`${name} has a mutable image`);
    if (component.network.profile !== expected.profile) throw new TypeError(`${name} has the wrong network profile`);
    assertExactKeys(component.network.destinations, expected.destinations, `${name} destinations`);
    for (const [destination, route] of Object.entries(component.network.destinations)) {
      if (forbiddenDestination.test(destination)) throw new TypeError(`${name} grants forbidden Provider/content/object egress`);
      assertRoute(route, `${name}.${destination}`);
    }
    if (name === 'control-api' || name === 'webhook-edge') assertRoute(component.network.ingress_gateway, `${name}.ingress_gateway`);
    else if (component.network.ingress_gateway) throw new TypeError('control-worker may not expose ingress');
    assertTls(name, component);
    if (name === 'control-worker') {
      if (component.probes?.type !== 'EXEC_JAVA' || component.probes.jar !== '/opt/accord/bin/worker-probe.jar') {
        throw new TypeError('control-worker probe must invoke Task16 worker-probe.jar directly');
      }
    } else if (component.probes?.type !== 'HTTP') {
      throw new TypeError(`${name} must use HTTP actuator probes`);
    }
  }
  return { identity, promotion };
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

async function renderValidatedValues({ identity, promotion, outputPath, environmentClass, authority }) {
  const compiled = await validators();
  if (!compiled.identity(identity)) throw schemaError('Component identity', compiled.identity, compiled.ajv);
  if (!compiled.promotion(promotion)) throw schemaError('Promotion input', compiled.promotion, compiled.ajv);
  assertDeploymentBindings(identity, promotion, environmentClass, authority);

  const components = Object.fromEntries(COMPONENT_NAMES.map((name) => {
    const value = structuredClone(identity.components[name]);
    value.image.reference = `${value.image.repository}@${value.image.digest}`;
    return [name, value];
  }));
  const rendered = {
    schema_version: '1.0.0',
    environment_class: environmentClass,
    namespace: identity.namespace,
    contract: {
      identity_sha256: sha256(identity),
      promotion_sha256: sha256(promotion),
      remote_url: promotion.remote_url,
      remote_sha: promotion.remote_sha,
      remote_tree_sha: promotion.remote_tree_sha,
      release_manifest_digest: promotion.release_manifest_digest,
      dsse_envelope_digest: promotion.dsse_envelope_digest,
      verification_receipt_digest: promotion.verification_receipt_digest,
      created_at: promotion.created_at,
    },
    components,
  };
  const resolvedOutput = path.resolve(outputPath);
  await atomicWriteYaml(resolvedOutput, rendered);
  return { outputPath: resolvedOutput, rendered };
}

export async function renderValues({ identity, promotion, outputPath, environmentClass = promotion?.environment_class }) {
  if (environmentClass === 'production' || identity?.environment_class === 'production' || promotion?.environment_class === 'production') {
    throw new TypeError('Production rendering requires authoritative release-chain verification');
  }
  return renderValidatedValues({ identity, promotion, outputPath, environmentClass, authority: undefined });
}

function assertExactObjectKeys(value, expected, label) {
  const actual = Object.keys(value ?? {}).sort();
  const wanted = [...expected].sort();
  if (canonicalJson(actual) !== canonicalJson(wanted)) throw new TypeError(`${label} is open or malformed`);
}

function assertPinnedProductionReleaseContext(context) {
  if (context?.environment_class !== 'production'
      || context.remote_url !== PINNED_RELEASE_AUTHORITY.remoteUrl
      || context.builder_issuer !== PINNED_RELEASE_AUTHORITY.issuer
      || context.builder_subject !== PINNED_RELEASE_AUTHORITY.subject
      || context.builder_audience !== PINNED_RELEASE_AUTHORITY.audience
      || context.protected_environment !== PINNED_RELEASE_AUTHORITY.protectedEnvironment) {
    throw new TypeError('Production deployment requires the pinned Accord release identity');
  }
  return context;
}

export function deriveProductionPromotion({
  identity,
  context,
  manifest,
  manifestBytes,
  verification,
  verificationBytes,
}) {
  assertPinnedProductionReleaseContext(context);
  assertExactObjectKeys(
    verification,
    ['schema_version', 'remote_sha', 'tree_sha', 'release_manifest_sha256', 'artifacts'],
    'Release verification receipt',
  );
  if (verification.schema_version !== '1.0.0'
      || !Array.isArray(verification.artifacts)
      || verification.remote_sha !== context.remote_sha
      || verification.tree_sha !== context.tree_sha
      || manifest.remote_sha !== context.remote_sha
      || manifest.tree_sha !== context.tree_sha
      || verification.release_manifest_sha256 !== sha256(manifestBytes)) {
    throw new TypeError('Release verification receipt does not bind the authoritative manifest and context');
  }
  for (const artifact of verification.artifacts) {
    assertExactObjectKeys(artifact, ['name', 'platform', 'immutable_locator'], 'Verified release artifact');
  }

  const sourceArtifacts = manifest.artifacts?.filter((artifact) => (
    artifact.name === 'accord-source' && artifact.kind === 'source' && artifact.platform === 'none'
  )) ?? [];
  if (sourceArtifacts.length !== 1 || !digestPattern.test(sourceArtifacts[0]?.provenance?.envelope_sha256 ?? '')) {
    throw new TypeError('Verified release manifest has no unique Accord source DSSE envelope');
  }
  const source = sourceArtifacts[0];
  const verifiedSource = verification.artifacts.filter((artifact) => (
    artifact.name === source.name
    && artifact.platform === source.platform
    && artifact.immutable_locator === source.immutable_locator
  ));
  if (verifiedSource.length !== 1) throw new TypeError('Release verification receipt does not bind the Accord source artifact');

  const images = {};
  for (const componentName of COMPONENT_NAMES) {
    const image = identity?.components?.[componentName]?.image;
    const expectedLocator = `${image?.repository}@${image?.digest}`;
    const artifactName = RELEASE_IMAGE_NAMES[componentName];
    const manifestMatches = manifest.artifacts?.filter((artifact) => (
      artifact.name === artifactName
      && artifact.kind === 'image'
      && artifact.digest === image?.digest
      && artifact.immutable_locator === expectedLocator
      && /^linux\/(?:amd64|arm64)$/u.test(artifact.platform)
    )) ?? [];
    if (manifestMatches.length !== 1) throw new TypeError(`${componentName} does not select one verified release image`);
    const selected = manifestMatches[0];
    const receiptMatches = verification.artifacts.filter((artifact) => (
      artifact.name === selected.name
      && artifact.platform === selected.platform
      && artifact.immutable_locator === selected.immutable_locator
    ));
    if (receiptMatches.length !== 1) throw new TypeError(`${componentName} image is absent from the release verification receipt`);
    images[componentName] = { repository: image.repository, digest: image.digest };
  }

  if (!Number.isFinite(Date.parse(manifest.generated_at ?? ''))) throw new TypeError('Release manifest generation time is invalid');
  return {
    schema_version: '1.0.0',
    environment_class: 'production',
    remote_url: context.remote_url,
    remote_ref: context.remote_ref,
    remote_sha: context.remote_sha,
    remote_tree_sha: context.tree_sha,
    images,
    release_manifest_digest: sha256(manifestBytes),
    dsse_envelope_digest: source.provenance.envelope_sha256,
    verification_receipt_digest: sha256(verificationBytes),
    signature_verified: true,
    scan_policy_passed: true,
    created_at: manifest.generated_at,
  };
}

export class ProductionRenderBlockedError extends Error {
  constructor(reasonCode, detailCode) {
    super(detailCode);
    this.name = 'ProductionRenderBlockedError';
    this.reasonCode = reasonCode;
    this.detailCode = detailCode;
  }
}

function verifierBlockedResult(output) {
  for (const line of output.trim().split(/\r?\n/u).reverse()) {
    try {
      const value = JSON.parse(line);
      if (value?.schema_version === '1.0.0'
          && value.status === 'BLOCKED'
          && ['BLOCKED_TOOLCHAIN', 'BLOCKED_EXTERNAL_IMAGE_RESOLUTION', 'BLOCKED_EXTERNAL_ENVIRONMENT'].includes(value.reason_code)
          && typeof value.detail_code === 'string'
          && value.detail_code.length > 0) {
        return value;
      }
    } catch {
      // Continue until the verifier's closed status record is found.
    }
  }
  return undefined;
}

async function runReleaseVerifier({ contextPath, manifestPath, evidenceIndexPath, evidenceRoot, outputPath }) {
  const args = [
    path.join(REPOSITORY_ROOT, 'scripts', 'ci', 'verify-release-chain.mjs'),
    '--context', contextPath,
    '--manifest', manifestPath,
    '--evidence-index', evidenceIndexPath,
    '--evidence-root', evidenceRoot,
    '--output', outputPath,
  ];
  const result = await new Promise((resolve, reject) => {
    let output = '';
    let outputBytes = 0;
    let settled = false;
    let timedOut = false;
    const child = spawn(process.execPath, args, {
      cwd: REPOSITORY_ROOT,
      env: process.env,
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const append = (chunk) => {
      if (outputBytes >= MAX_RELEASE_VERIFIER_OUTPUT_BYTES) return;
      const slice = chunk.subarray(0, MAX_RELEASE_VERIFIER_OUTPUT_BYTES - outputBytes);
      outputBytes += slice.length;
      output += slice.toString('utf8');
    };
    child.stdout.on('data', append);
    child.stderr.on('data', append);
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill('SIGKILL');
    }, RELEASE_VERIFICATION_TIMEOUT_MS);
    timer.unref();
    const finish = (error, exitCode) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve({ exitCode: exitCode ?? -1, output, timedOut });
    };
    child.once('error', (error) => finish(error));
    child.once('close', (exitCode) => finish(undefined, exitCode));
  }).catch(() => {
    throw new ProductionRenderBlockedError('BLOCKED_TOOLCHAIN', 'RELEASE_VERIFIER_UNAVAILABLE');
  });
  if (result.timedOut) {
    throw new ProductionRenderBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'RELEASE_VERIFIER_TIMEOUT');
  }
  if (result.exitCode === 2) {
    const blocked = verifierBlockedResult(result.output);
    if (!blocked) throw new TypeError('Release verifier returned BLOCKED without closed evidence');
    throw new ProductionRenderBlockedError(blocked.reason_code, blocked.detail_code);
  }
  if (result.exitCode !== 0) throw new TypeError('Authoritative release-chain verification failed');
}

function resolveRepositoryInput(candidate, label) {
  const resolved = path.resolve(REPOSITORY_ROOT, candidate);
  const relative = path.relative(REPOSITORY_ROOT, resolved);
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new TypeError(`${label} must be a repository-local path`);
  }
  return resolved;
}

async function readRepositoryInput(candidate, label) {
  const resolved = resolveRepositoryInput(candidate, label);
  const relative = path.relative(REPOSITORY_ROOT, resolved);
  let current = REPOSITORY_ROOT;
  for (const segment of relative.split(path.sep)) {
    current = path.join(current, segment);
    if ((await lstat(current)).isSymbolicLink()) {
      throw new TypeError(`${label} path contains a symbolic link`);
    }
  }
  const info = await lstat(resolved);
  if (!info.isFile() || info.isSymbolicLink() || info.size < 2 || info.size > MAX_RELEASE_INPUT_BYTES) {
    throw new TypeError(`${label} must be a bounded regular file`);
  }
  return { resolved, bytes: await readFile(resolved) };
}

async function writeSnapshotFile(target, bytes) {
  const handle = await open(target, 'wx', 0o400);
  try {
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
}

export async function renderProductionValues({
  identity,
  outputPath,
  releaseContextPath,
  releaseManifestPath,
  releaseEvidenceIndexPath,
  releaseEvidenceRoot,
}) {
  const stableIdentity = structuredClone(identity);
  const evidenceRoot = path.resolve(releaseEvidenceRoot);
  let contextInput;
  let manifestInput;
  let evidenceIndexInput;
  try {
    [contextInput, manifestInput, evidenceIndexInput] = await Promise.all([
      readRepositoryInput(releaseContextPath, 'Release context'),
      readRepositoryInput(releaseManifestPath, 'Release manifest'),
      readRepositoryInput(releaseEvidenceIndexPath, 'Release evidence index'),
    ]);
  } catch (error) {
    if (error?.code === 'ENOENT') {
      throw new ProductionRenderBlockedError(
        'BLOCKED_EXTERNAL_ENVIRONMENT',
        'PRODUCTION_RELEASE_EVIDENCE_ABSENT',
      );
    }
    throw error;
  }
  const contextBytes = contextInput.bytes;
  const manifestBytes = manifestInput.bytes;
  const context = await validateContext(JSON.parse(contextBytes.toString('utf8')));
  assertPinnedProductionReleaseContext(context);
  const manifest = JSON.parse(manifestBytes.toString('utf8'));
  const evidenceIndex = JSON.parse(evidenceIndexInput.bytes.toString('utf8'));
  if (contextBytes.toString('utf8') !== `${canonicalJson(context)}\n`
      || manifestBytes.toString('utf8') !== `${canonicalJson(manifest)}\n`
      || evidenceIndexInput.bytes.toString('utf8') !== `${canonicalJson(evidenceIndex)}\n`) {
    throw new TypeError('Production release inputs must be canonical JSON');
  }

  const snapshotDirectory = path.join(REPOSITORY_ROOT, 'build', 'deployment', `release-verification-${randomUUID()}`);
  const contextPath = path.join(snapshotDirectory, 'context.json');
  const manifestPath = path.join(snapshotDirectory, 'manifest.json');
  const evidenceIndexPath = path.join(snapshotDirectory, 'evidence-index.json');
  const verificationPath = path.join(snapshotDirectory, 'receipt.json');
  try {
    await mkdir(path.dirname(snapshotDirectory), { recursive: true });
    await mkdir(snapshotDirectory, { recursive: false });
    await Promise.all([
      writeSnapshotFile(contextPath, contextBytes),
      writeSnapshotFile(manifestPath, manifestBytes),
      writeSnapshotFile(evidenceIndexPath, evidenceIndexInput.bytes),
    ]);
    await runReleaseVerifier({
      contextPath,
      manifestPath,
      evidenceIndexPath,
      evidenceRoot,
      outputPath: verificationPath,
    });
    const verificationBytes = await readFile(verificationPath);
    const verification = JSON.parse(verificationBytes.toString('utf8'));
    if (verificationBytes.toString('utf8') !== `${canonicalJson(verification)}\n`) {
      throw new TypeError('Release verification receipt is not canonical JSON');
    }
    const promotion = deriveProductionPromotion({
      identity: stableIdentity,
      context,
      manifest,
      manifestBytes,
      verification,
      verificationBytes,
    });
    return renderValidatedValues({
      identity: stableIdentity,
      promotion,
      outputPath,
      environmentClass: 'production',
      authority: productionRenderAuthority,
    });
  } finally {
    await rm(snapshotDirectory, { recursive: true, force: true }).catch(() => {});
  }
}

function parseCli(argv) {
  const parsed = {};
  const aliases = new Map([
    ['--component-identity', 'identity'],
    ['--identity', 'identity'],
    ['--promotion', 'promotion'],
    ['--output', 'output'],
    ['--environment-class', 'environmentClass'],
    ['--release-context', 'releaseContext'],
    ['--release-manifest', 'releaseManifest'],
    ['--release-evidence-index', 'releaseEvidenceIndex'],
    ['--release-evidence-root', 'releaseEvidenceRoot'],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    const key = aliases.get(option);
    if (!key) throw new TypeError(`Unknown option: ${option}`);
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) throw new TypeError(`Missing value for ${option}`);
    if (parsed[key]) throw new TypeError(`Repeated option: ${option}`);
    parsed[key] = value;
    index += 1;
  }
  if (!parsed.identity) throw new TypeError('--component-identity is required');
  parsed.output ??= path.join(REPOSITORY_ROOT, 'build', 'deployment', 'rendered-values.yaml');
  const output = path.resolve(REPOSITORY_ROOT, parsed.output);
  const relativeToRepository = path.relative(REPOSITORY_ROOT, output);
  if (!relativeToRepository.startsWith('..') && !path.isAbsolute(relativeToRepository)) {
    const relativeToBuild = path.relative(path.join(REPOSITORY_ROOT, 'build'), output);
    if (relativeToBuild === '' || relativeToBuild.startsWith('..') || path.isAbsolute(relativeToBuild)) {
      throw new TypeError('Rendered values inside the repository must be written below ignored build/');
    }
  }
  return { ...parsed, output };
}

async function main(argv) {
  const options = parseCli(argv);
  const identity = JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options.identity), 'utf8'));
  const environmentClass = options.environmentClass ?? identity.environment_class;
  let result;
  if (environmentClass === 'production') {
    if (options.promotion) throw new TypeError('Production rendering does not accept caller-supplied promotion input');
    const required = ['releaseContext', 'releaseManifest', 'releaseEvidenceIndex', 'releaseEvidenceRoot'];
    if (required.some((name) => !options[name])) {
      throw new ProductionRenderBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'RELEASE_CHAIN_EVIDENCE_ABSENT');
    }
    result = await renderProductionValues({
      identity,
      outputPath: options.output,
      releaseContextPath: options.releaseContext,
      releaseManifestPath: options.releaseManifest,
      releaseEvidenceIndexPath: options.releaseEvidenceIndex,
      releaseEvidenceRoot: options.releaseEvidenceRoot,
    });
  } else {
    if (!options.promotion) throw new TypeError('Test rendering requires --promotion');
    if (options.releaseContext || options.releaseManifest || options.releaseEvidenceIndex || options.releaseEvidenceRoot) {
      throw new TypeError('Release-chain evidence options belong only to production rendering');
    }
    const promotion = JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options.promotion), 'utf8'));
    result = await renderValues({
      identity,
      promotion,
      outputPath: options.output,
      environmentClass,
    });
  }
  process.stdout.write(`Rendered closed deployment values: ${path.relative(REPOSITORY_ROOT, result.outputPath)}\n`);
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    await main(process.argv.slice(2));
  } catch (error) {
    if (error instanceof ProductionRenderBlockedError) {
      process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'BLOCKED', reason_code: error.reasonCode, detail_code: error.detailCode })}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`render-values: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
