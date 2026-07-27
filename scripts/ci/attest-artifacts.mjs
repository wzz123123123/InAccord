import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, open, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

import { REPOSITORY_ROOT, canonicalJson, sha256 } from '../verification/check-result.mjs';
import { validateContext } from './create-ci-context.mjs';
import { validateCycloneDx, validateSpdx } from './generate-sboms.mjs';
import { validatePlatformEvidence } from './package-accordctl.mjs';
import { assertProductionImageLock, loadImageLock } from './render-runtime-dockerfiles.mjs';

const PAYLOAD_TYPE = 'application/vnd.in-toto+json';
const PREDICATE_TYPE = 'https://schemas.accord.inforvans.com/provenance/build/v1';
const DIGEST = /^sha256:[0-9a-f]{64}$/u;
const SIGSTORE_BUNDLE_MEDIA_TYPE = 'application/vnd.dev.sigstore.bundle.v0.3+json';

class AttestationBlockedError extends Error {
  constructor(reasonCode, detailCode) {
    super(detailCode);
    this.reasonCode = reasonCode;
    this.detailCode = detailCode;
  }
}

function parseCli(argv) {
  const result = { facts: [] };
  const singles = new Set();
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) throw new TypeError(`Missing value for ${option}`);
    if (option === '--facts') result.facts.push(value);
    else if (['--context', '--sboms', '--scans', '--output', '--evidence-index'].includes(option)) {
      if (singles.has(option)) throw new TypeError(`Repeated option: ${option}`);
      singles.add(option);
      result[option.slice(2).replace(/-([a-z])/gu, (_match, letter) => letter.toUpperCase())] = value;
    } else throw new TypeError(`Unknown attestation option: ${option}`);
  }
  if (!result.context || !result.sboms || !result.scans || !result.output || !result.evidenceIndex || result.facts.length === 0) throw new TypeError('Context, facts, SBOMs, scans, manifest, and evidence index are required');
  return result;
}

function runNative(executable, argv, options = {}) {
  const forbiddenOption = /^(?:--identity-token|--password|--secret|--client-secret)(?:=|$)/iu;
  if (argv.some((argument) => forbiddenOption.test(argument) || /^Bearer\s+/iu.test(argument))) throw new TypeError('Secret material is forbidden in native argv');
  return new Promise((resolve, reject) => {
    let output = '';
    const child = spawn(executable, argv, { cwd: options.cwd ?? REPOSITORY_ROOT, env: process.env, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', (chunk) => { if (output.length < 128 * 1024) output += chunk.toString('utf8'); });
    child.stderr.on('data', (chunk) => { if (output.length < 128 * 1024) output += chunk.toString('utf8'); });
    child.once('error', () => reject(new AttestationBlockedError('BLOCKED_TOOLCHAIN', `${executable.toUpperCase()}_UNAVAILABLE`)));
    child.once('close', (code) => resolve({ exitCode: code ?? -1, output }));
  });
}

async function validator(relative) {
  const schema = JSON.parse(await readFile(path.join(REPOSITORY_ROOT, relative), 'utf8'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  return (document) => {
    if (!validate(document)) throw new TypeError(`Closed supply-chain schema rejected document: ${ajv.errorsText(validate.errors, { separator: '; ' })}`);
    return document;
  };
}

async function atomicWrite(target, document) {
  const absolute = path.resolve(REPOSITORY_ROOT, target);
  const relative = path.relative(REPOSITORY_ROOT, absolute);
  if (relative.startsWith('..') || path.isAbsolute(relative) || path.extname(absolute) !== '.json') throw new TypeError('Attestation output must be repository-local JSON');
  await mkdir(path.dirname(absolute), { recursive: true });
  const temporary = path.join(path.dirname(absolute), `.${path.basename(absolute)}.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(`${canonicalJson(document)}\n`, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, absolute);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
  return absolute;
}

async function dependencyLocks() {
  const result = await runNative('git', ['ls-files', '--', '*gradle.lockfile', 'pnpm-lock.yaml', 'uv.lock', 'gradle/verification-metadata.xml']);
  if (result.exitCode !== 0) throw new Error('Dependency lock inventory failed');
  const rows = result.output.split(/\r?\n/u).filter(Boolean).sort();
  if (rows.length === 0) throw new TypeError('Dependency lock inventory is empty');
  return Promise.all(rows.map(async (item) => ({ path: item.replaceAll('\\', '/'), sha256: sha256(await readFile(path.join(REPOSITORY_ROOT, item))) })));
}

function descriptorDigest(output) {
  let document;
  try { document = JSON.parse(output); } catch { throw new TypeError('Registry tool did not return JSON descriptor metadata'); }
  if (!DIGEST.test(document?.digest)) throw new TypeError('Registry tool did not return an immutable root descriptor digest');
  return document.digest;
}

function decodeCanonicalBase64(value, label) {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError(`${label} is absent`);
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length === 0 || bytes.toString('base64') !== value) throw new TypeError(`${label} is not canonical base64`);
  return bytes;
}

export function createDsseEnvelope(statement, signature) {
  decodeCanonicalBase64(signature, 'DSSE signature');
  return {
    payloadType: PAYLOAD_TYPE,
    payload: Buffer.from(canonicalJson(statement), 'utf8').toString('base64'),
    signatures: [{ sig: signature }],
  };
}

export function dssePae(envelope) {
  if (!envelope || envelope.payloadType !== PAYLOAD_TYPE || typeof envelope.payload !== 'string'
      || !Array.isArray(envelope.signatures) || envelope.signatures.length !== 1) {
    throw new TypeError('DSSE envelope cannot be encoded as PAE');
  }
  const payload = decodeCanonicalBase64(envelope.payload, 'DSSE payload');
  const payloadType = Buffer.from(envelope.payloadType, 'utf8');
  return Buffer.concat([
    Buffer.from(`DSSEv1 ${payloadType.length} `, 'ascii'), payloadType,
    Buffer.from(` ${payload.length} `, 'ascii'), payload,
  ]);
}

export function assertBundleBindsEnvelope(bundle, envelope) {
  const messageSignature = assertBundleBindsBlob(bundle, dssePae(envelope));
  if (envelope.signatures.length !== 1 || messageSignature.signature !== envelope.signatures[0]?.sig) {
    throw new TypeError('Cosign bundle signature does not match the standalone DSSE signature');
  }
  return envelope;
}

export function assertBundleBindsBlob(bundle, bytes) {
  const messageSignature = bundle?.messageSignature;
  if (bundle?.mediaType !== SIGSTORE_BUNDLE_MEDIA_TYPE || !messageSignature
      || messageSignature.messageDigest?.algorithm !== 'SHA2_256') {
    throw new TypeError('Cosign bundle is not the required Sigstore v0.3 message-signature bundle');
  }
  decodeCanonicalBase64(messageSignature.signature, 'Sigstore message signature');
  const expected = createHash('sha256').update(bytes).digest();
  const observed = decodeCanonicalBase64(messageSignature.messageDigest.digest, 'Sigstore message digest');
  if (observed.length !== expected.length || !timingSafeEqual(observed, expected)) {
    throw new TypeError('Cosign bundle does not bind the exact payload bytes');
  }
  return messageSignature;
}

function artifactIdentity(item) {
  return `${item.name}/${item.kind}/${item.platform}`;
}

export function validateArtifactInventory(inventory, artifacts) {
  if (canonicalJson(Object.keys(inventory ?? {}).sort()) !== canonicalJson(['release_sets', 'schema_version'])
      || inventory.schema_version !== '1.0.0' || !Array.isArray(inventory.release_sets) || inventory.release_sets.length === 0
      || !Array.isArray(artifacts)) throw new TypeError('Artifact inventory contract is closed and required');
  const setIds = new Set();
  const normalizedSets = inventory.release_sets.map((releaseSet) => {
    if (canonicalJson(Object.keys(releaseSet ?? {}).sort()) !== canonicalJson(['artifacts', 'id'])
        || !/^[a-z0-9][a-z0-9-]{1,63}$/u.test(releaseSet.id) || setIds.has(releaseSet.id)
        || !Array.isArray(releaseSet.artifacts) || releaseSet.artifacts.length < 2) {
      throw new TypeError('Artifact inventory release set is invalid or duplicated');
    }
    setIds.add(releaseSet.id);
    const identities = releaseSet.artifacts.map((item) => {
      if (canonicalJson(Object.keys(item ?? {}).sort()) !== canonicalJson(['kind', 'name', 'platform'])) throw new TypeError('Artifact inventory entry is open');
      return artifactIdentity(item);
    });
    if (new Set(identities).size !== identities.length) throw new TypeError('Artifact inventory contains duplicate entries');
    return { releaseSet, identities: identities.sort() };
  });
  const presented = artifacts.map(artifactIdentity).sort();
  if (new Set(presented).size !== presented.length) throw new TypeError('Presented artifacts contain duplicate identities');
  const match = normalizedSets.find((item) => canonicalJson(item.identities) === canonicalJson(presented));
  if (!match) throw new TypeError('Presented artifacts do not match one complete artifact inventory');
  return match.releaseSet;
}

export function validateOciBlobManifest(manifest, artifact, mediaType) {
  if (manifest?.schemaVersion !== 2 || manifest.artifactType !== mediaType || !Array.isArray(manifest.layers)
      || manifest.layers.length !== 1) throw new TypeError('OCI artifact root must contain one exact raw blob layer');
  const layer = manifest.layers[0];
  if (layer?.digest !== artifact.digest || layer?.mediaType !== mediaType || layer?.size !== artifact.size) {
    throw new TypeError('OCI artifact raw blob layer digest, media type, or size mismatch');
  }
  return manifest;
}

async function retainedPlatformEvidence(artifact, context) {
  const nativePlatform = artifact.kind === 'archive' && (artifact.platform.startsWith('windows/') || artifact.platform.startsWith('darwin/'));
  const local = artifact.ga_platform_evidence ?? null;
  if (!nativePlatform) {
    if (local !== null) throw new TypeError('Unexpected platform evidence on a generic artifact');
    return { manifest: null, paths: { bundle: null, document: null, receipt: null } };
  }
  if (canonicalJson(Object.keys(local ?? {}).sort()) !== canonicalJson([
    'bundle_path', 'bundle_sha256', 'document_path', 'document_sha256', 'expires_at', 'identity', 'issuer',
    'kind', 'receipt_path', 'receipt_sha256',
  ])) throw new TypeError('Native platform evidence is required and closed');
  const paths = {
    bundle: path.resolve(REPOSITORY_ROOT, local.bundle_path),
    document: path.resolve(REPOSITORY_ROOT, local.document_path),
    receipt: path.resolve(REPOSITORY_ROOT, local.receipt_path),
  };
  for (const target of Object.values(paths)) {
    const relative = path.relative(REPOSITORY_ROOT, target);
    if (relative.startsWith('..') || path.isAbsolute(relative)) throw new TypeError('Native platform evidence must be repository-local');
  }
  const documentBytes = await readFile(paths.document);
  const bundleBytes = await readFile(paths.bundle);
  const receiptBytes = await readFile(paths.receipt);
  if (sha256(documentBytes) !== local.document_sha256 || sha256(bundleBytes) !== local.bundle_sha256 || sha256(receiptBytes) !== local.receipt_sha256
      || local.issuer !== context.builder_issuer || local.identity !== context.builder_subject || Date.parse(local.expires_at) <= Date.now()) {
    throw new TypeError('Native platform evidence bytes, authority, or expiry changed');
  }
  const document = validatePlatformEvidence(JSON.parse(documentBytes.toString('utf8')), artifact.digest, artifact.platform, receiptBytes);
  if (document.kind !== local.kind || document.expires_at !== local.expires_at || document.signature_digest !== local.receipt_sha256) {
    throw new TypeError('Native platform evidence declaration binding mismatch');
  }
  const verification = await runNative('cosign', [
    'verify-blob', '--bundle', paths.bundle, '--certificate-identity', context.builder_subject,
    '--certificate-oidc-issuer', context.builder_issuer, paths.document,
  ]);
  if (verification.exitCode !== 0) throw new TypeError('Native platform evidence signature, issuer, or identity is invalid');
  return {
    manifest: {
      kind: local.kind, expires_at: local.expires_at, document_sha256: local.document_sha256,
      bundle_sha256: local.bundle_sha256, receipt_sha256: local.receipt_sha256, issuer: local.issuer, identity: local.identity,
    },
    paths,
  };
}

async function attach(subject, artifactType, documentPath) {
  const fileName = path.basename(documentPath);
  const result = await runNative('oras', ['attach', '--artifact-type', artifactType, '--format', 'json', subject, `${fileName}:${artifactType}`], { cwd: path.dirname(documentPath) });
  if (result.exitCode !== 0) throw new AttestationBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'REGISTRY_REFERRER_ATTACH_FAILED');
  const digest = descriptorDigest(result.output);
  const discovery = await runNative('oras', ['discover', '--format', 'json', subject]);
  if (discovery.exitCode !== 0 || !discovery.output.includes(digest)) throw new TypeError('Attached registry referrer was not rediscovered by digest');
  const refetch = path.join(path.dirname(documentPath), `refetch-${randomUUID()}`);
  await mkdir(refetch, { recursive: true });
  try {
    const repository = subject.slice(0, subject.indexOf('@'));
    const pull = await runNative('oras', ['pull', '--output', refetch, `${repository}@${digest}`]);
    if (pull.exitCode !== 0) throw new TypeError('Attached registry referrer could not be refetched');
    const files = await readdir(refetch);
    if (files.length !== 1 || sha256(await readFile(path.join(refetch, files[0]))) !== sha256(await readFile(documentPath))) throw new TypeError('Refetched registry referrer bytes differ from local evidence');
  } finally {
    await rm(refetch, { recursive: true, force: true });
  }
  return digest;
}

async function ensureRegistrySubject(artifact, context, scratch) {
  if (artifact.kind === 'image') {
    if (!artifact.immutable_locator.endsWith(`@${artifact.digest}`)) throw new TypeError('Image attestation subject is not its exact digest');
    const manifestPath = path.join(scratch, `${artifact.name}-${artifact.platform.replaceAll('/', '-')}.oci-manifest.json`);
    const fetched = await runNative('oras', ['manifest', 'fetch', '--output', manifestPath, artifact.immutable_locator]);
    if (fetched.exitCode !== 0) throw new AttestationBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'REGISTRY_IMAGE_MANIFEST_UNAVAILABLE');
    if (sha256(await readFile(manifestPath)) !== artifact.digest) throw new TypeError('Image registry manifest bytes differ from the immutable digest');
    return { ...artifact, registry_subject: artifact.immutable_locator, blob_path: manifestPath, oci_blob: null };
  }
  let blobPath = artifact.path ? path.resolve(REPOSITORY_ROOT, artifact.path) : null;
  if (artifact.kind === 'source') {
    blobPath = path.join(scratch, 'accord-source.tar');
    const archive = await runNative('git', ['archive', '--format=tar', '--output', blobPath, context.remote_sha]);
    if (archive.exitCode !== 0) throw new Error('Source subject could not be reproduced');
  }
  const blobBytes = blobPath ? await readFile(blobPath) : null;
  if (!blobBytes || sha256(blobBytes) !== artifact.digest) throw new TypeError('Blob attestation subject digest mismatch');
  const blobSize = (await stat(blobPath)).size;
  const tag = `${context.registry_repository}/${artifact.name}:${context.remote_sha}-${artifact.platform.replaceAll('/', '-')}`;
  const mediaType = artifact.kind === 'source' ? 'application/vnd.accord.source.v1+tar' : 'application/vnd.accord.accordctl.v1+zip';
  const push = await runNative('oras', ['push', '--format', 'json', '--artifact-type', mediaType, tag, `${path.basename(blobPath)}:${mediaType}`], { cwd: path.dirname(blobPath) });
  if (push.exitCode !== 0) throw new AttestationBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'REGISTRY_ARTIFACT_PUSH_FAILED');
  const registrySubject = `${tag.slice(0, tag.lastIndexOf(':'))}@${descriptorDigest(push.output)}`;
  const fetched = await runNative('oras', ['manifest', 'fetch', registrySubject]);
  if (fetched.exitCode !== 0) throw new TypeError('Pushed OCI artifact root manifest could not be refetched');
  validateOciBlobManifest(JSON.parse(fetched.output), { digest: artifact.digest, size: blobSize }, mediaType);
  const refetch = path.join(scratch, `root-${randomUUID()}`);
  await mkdir(refetch, { recursive: true });
  const pull = await runNative('oras', ['pull', '--output', refetch, registrySubject]);
  if (pull.exitCode !== 0) throw new TypeError('Pushed OCI artifact raw blob could not be refetched');
  const files = await readdir(refetch);
  if (files.length !== 1 || sha256(await readFile(path.join(refetch, files[0]))) !== artifact.digest) throw new TypeError('Pushed OCI artifact bytes differ from the raw archive');
  return {
    ...artifact,
    registry_subject: registrySubject,
    blob_path: blobPath,
    size: blobSize,
    oci_blob: { layer_digest: artifact.digest, media_type: mediaType, size: blobSize },
  };
}

export function buildProvenance(context, artifact, imageLockDigest, locks, sbom, scan) {
  return {
    _type: 'https://in-toto.io/Statement/v1',
    subject: [{ name: artifact.registry_subject, digest: { sha256: artifact.digest.slice(7) } }],
    predicateType: PREDICATE_TYPE,
    predicate: {
      domain: 'accord.build-provenance.v1',
      builder: { issuer: context.builder_issuer, subject: context.builder_subject, audience: context.builder_audience },
      ci: { system: context.ci_system, run_id: context.run_id, workflow_id: context.workflow_id },
      source: {
        remote_url: context.remote_url, remote_ref: context.remote_ref, remote_sha: context.remote_sha,
        base_sha: context.base_sha, tree_sha: context.tree_sha,
      },
      materials: { image_lock_sha256: imageLockDigest, dependency_locks: locks },
      parameters: { platform: artifact.platform, source_date_epoch: artifact.source_date_epoch, network: 'none', reproducible: true },
      artifact: { kind: artifact.kind, platform: artifact.platform, digest: artifact.digest, immutable_locator: artifact.registry_subject },
      sboms: { cyclonedx_sha256: sbom.cyclonedx.digest, spdx_sha256: sbom.spdx.digest },
      scan_sha256: scan.digest,
    },
  };
}

async function main(argv) {
  const options = parseCli(argv);
  const contextBytes = await readFile(path.resolve(REPOSITORY_ROOT, options.context));
  const context = await validateContext(JSON.parse(contextBytes.toString('utf8')));
  const sboms = JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options.sboms), 'utf8'));
  const scans = JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options.scans), 'utf8'));
  if (sboms.remote_sha !== context.remote_sha || scans.remote_sha !== context.remote_sha) throw new TypeError('Release evidence differs from the canonical context');
  const { lock: imageLock, digest: imageLockDigest } = await loadImageLock();
  assertProductionImageLock(imageLock);
  const locks = await dependencyLocks();
  const validateProvenance = await validator('contracts/dsse-payloads/build-provenance.schema.json');
  const validateManifest = await validator('contracts/supply-chain/release-manifest.schema.json');
  const validateInventory = await validator('contracts/supply-chain/artifact-inventory.schema.json');
  const inventoryBytes = await readFile(path.join(REPOSITORY_ROOT, 'contracts', 'supply-chain', 'artifact-inventory.json'));
  const inventory = validateInventory(JSON.parse(inventoryBytes.toString('utf8')));
  const factsArtifacts = [];
  for (const factsPath of options.facts) {
    const facts = JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, factsPath), 'utf8'));
    if (facts.remote_sha !== context.remote_sha || !Array.isArray(facts.artifacts)) throw new TypeError('Artifact facts differ from the canonical context');
    factsArtifacts.push(...facts.artifacts.map((item) => ({ ...item, source_date_epoch: facts.source_date_epoch ?? 1 })));
  }
  const known = new Map(factsArtifacts.map((item) => [`${item.name}/${item.platform}`, item]));
  for (const sbom of sboms.documents) {
    const key = `${sbom.name}/${sbom.platform}`;
    if (!known.has(key)) known.set(key, { name: sbom.name, kind: sbom.kind, platform: sbom.platform, digest: sbom.subject_digest, immutable_locator: sbom.immutable_locator, source_date_epoch: 1 });
  }
  const releaseSet = validateArtifactInventory(inventory, [...known.values()]);
  const scanException = scans.exception ?? null;
  if (scanException !== null) {
    if (canonicalJson(Object.keys(scanException).sort()) !== canonicalJson([
      'bundle_path', 'bundle_sha256', 'document_path', 'document_sha256', 'expires_at', 'identity', 'issuer',
    ])) throw new TypeError('Scan exception index entry is open');
    const exceptionDocumentBytes = await readFile(path.resolve(REPOSITORY_ROOT, scanException.document_path));
    const exceptionBundleBytes = await readFile(path.resolve(REPOSITORY_ROOT, scanException.bundle_path));
    if (sha256(exceptionDocumentBytes) !== scanException.document_sha256 || sha256(exceptionBundleBytes) !== scanException.bundle_sha256
        || scanException.issuer !== context.builder_issuer || scanException.identity !== context.builder_subject
        || Date.parse(scanException.expires_at) <= Date.now()) throw new TypeError('Scan exception evidence changed or expired before attestation');
  }

  const scratch = path.join(REPOSITORY_ROOT, 'build', 'ci', 'attestation', randomUUID());
  await mkdir(scratch, { recursive: true });
  const manifestArtifacts = [];
  const evidenceArtifacts = [];
  try {
    for (const raw of [...known.values()].sort((left, right) => `${left.name}/${left.platform}`.localeCompare(`${right.name}/${right.platform}`))) {
      const artifact = await ensureRegistrySubject(raw, context, scratch);
      const platformEvidence = await retainedPlatformEvidence(artifact, context);
      const sbom = sboms.documents.find((item) => item.name === artifact.name && item.platform === artifact.platform && item.subject_digest === artifact.digest);
      const scan = scans.scans.find((item) => item.name === artifact.name && item.platform === artifact.platform && item.subject_digest === artifact.digest);
      if (!sbom || !scan || scan.status !== 'PASS') throw new TypeError('Artifact is missing exact passing SBOM/scan evidence');
      const cyclonePath = path.resolve(REPOSITORY_ROOT, sbom.cyclonedx.path);
      const spdxPath = path.resolve(REPOSITORY_ROOT, sbom.spdx.path);
      const scanPath = path.resolve(REPOSITORY_ROOT, scan.path);
      const cycloneBytes = await readFile(cyclonePath);
      const spdxBytes = await readFile(spdxPath);
      const scanBytes = await readFile(scanPath);
      if (sha256(cycloneBytes) !== sbom.cyclonedx.digest || sha256(spdxBytes) !== sbom.spdx.digest || sha256(scanBytes) !== scan.digest) {
        throw new TypeError('SBOM or scan bytes changed before signing');
      }
      validateCycloneDx(JSON.parse(cycloneBytes), artifact.digest);
      validateSpdx(JSON.parse(spdxBytes), artifact.digest);
      const scanDocument = JSON.parse(scanBytes);
      if (scanDocument.subject_digest !== artifact.digest || scanDocument.status !== 'PASS'
          || !Array.isArray(scanDocument.unexcepted_findings) || scanDocument.unexcepted_findings.length !== 0
          || (scanDocument.exception_document_sha256 ?? null) !== (scanException?.document_sha256 ?? null)
          || (scanDocument.exception_bundle_sha256 ?? null) !== (scanException?.bundle_sha256 ?? null)) {
        throw new TypeError('Scan policy is not an exact passing subject result');
      }
      const provenance = validateProvenance(buildProvenance(context, artifact, imageLockDigest, locks, sbom, scan));
      const base = `${artifact.name}-${artifact.platform.replaceAll('/', '-')}`;
      const signatureBundle = path.join(scratch, `${base}.signature.bundle.json`);
      const attestationBundle = path.join(scratch, `${base}.attestation.bundle.json`);
      const paePath = path.join(scratch, `${base}.dsse.pae`);
      const dsseSignaturePath = path.join(scratch, `${base}.dsse.signature`);
      const unsignedEnvelope = createDsseEnvelope(provenance, Buffer.alloc(64, 1).toString('base64'));
      await writeFile(paePath, dssePae(unsignedEnvelope), { flag: 'wx', mode: 0o600 });

      const signArgs = ['sign-blob', '--yes', '--new-bundle-format', '--bundle', signatureBundle, artifact.blob_path];
      const sign = await runNative('cosign', signArgs);
      if (sign.exitCode !== 0) throw new AttestationBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'KEYLESS_IDENTITY_OR_TRANSPARENCY_LOG_UNAVAILABLE');
      assertBundleBindsBlob(JSON.parse(await readFile(signatureBundle, 'utf8')), await readFile(artifact.blob_path));
      const attestArgs = [
        'sign-blob', '--yes', '--new-bundle-format', '--bundle', attestationBundle,
        '--output-signature', dsseSignaturePath, paePath,
      ];
      const attest = await runNative('cosign', attestArgs);
      if (attest.exitCode !== 0) throw new AttestationBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'KEYLESS_ATTESTATION_UNAVAILABLE');
      const bundle = JSON.parse(await readFile(attestationBundle, 'utf8'));
      const signature = (await readFile(dsseSignaturePath, 'utf8')).trim();
      const envelope = createDsseEnvelope(provenance, signature);
      assertBundleBindsEnvelope(bundle, envelope);
      if (!timingSafeEqual(await readFile(paePath), dssePae(envelope))) throw new TypeError('Cosign signed PAE differs from the final DSSE envelope');
      const attestationVerification = await runNative('cosign', [
        'verify-blob', '--new-bundle-format', '--bundle', attestationBundle,
        '--certificate-identity', context.builder_subject, '--certificate-oidc-issuer', context.builder_issuer, paePath,
      ]);
      if (attestationVerification.exitCode !== 0) throw new TypeError('New-format Cosign provenance bundle did not verify immediately after signing');
      const decoded = JSON.parse(Buffer.from(envelope.payload, 'base64').toString('utf8'));
      if (canonicalJson(decoded) !== canonicalJson(provenance)) throw new TypeError('Signed DSSE payload differs from the validated provenance');
      const envelopePath = path.join(scratch, `${base}.dsse.json`);
      await writeFile(envelopePath, `${canonicalJson(envelope)}\n`, { encoding: 'utf8', flag: 'wx', mode: 0o600 });

      const cycloneRef = await attach(artifact.registry_subject, 'application/vnd.cyclonedx+json', cyclonePath);
      const spdxRef = await attach(artifact.registry_subject, 'application/vnd.spdx+json', spdxPath);
      const scanRef = await attach(artifact.registry_subject, 'application/vnd.accord.trivy.scan.v1+json', scanPath);
      const provenanceRef = await attach(artifact.registry_subject, 'application/vnd.dsse.envelope.v1+json', envelopePath);
      manifestArtifacts.push({
        name: artifact.name, kind: artifact.kind, platform: artifact.platform, digest: artifact.digest,
        immutable_locator: artifact.registry_subject,
        oci_blob: artifact.oci_blob,
        platform_evidence: platformEvidence.manifest,
        sboms: {
          cyclonedx: { document_sha256: sbom.cyclonedx.digest, referrer_digest: cycloneRef, artifact_type: 'application/vnd.cyclonedx+json' },
          spdx: { document_sha256: sbom.spdx.digest, referrer_digest: spdxRef, artifact_type: 'application/vnd.spdx+json' },
        },
        scan: { document_sha256: scan.digest, referrer_digest: scanRef, artifact_type: 'application/vnd.accord.trivy.scan.v1+json' },
        signature: { bundle_sha256: sha256(await readFile(signatureBundle)), issuer: context.builder_issuer, identity: context.builder_subject },
        provenance: {
          envelope_sha256: sha256(await readFile(envelopePath)), bundle_sha256: sha256(await readFile(attestationBundle)), pae_sha256: sha256(await readFile(paePath)),
          referrer_digest: provenanceRef, payload_type: PAYLOAD_TYPE, predicate_type: PREDICATE_TYPE,
        },
      });
      evidenceArtifacts.push({
        name: artifact.name, platform: artifact.platform, subject: artifact.registry_subject,
        cyclonedx_path: path.relative(REPOSITORY_ROOT, cyclonePath).replaceAll('\\', '/'),
        spdx_path: path.relative(REPOSITORY_ROOT, spdxPath).replaceAll('\\', '/'),
        scan_path: path.relative(REPOSITORY_ROOT, scanPath).replaceAll('\\', '/'),
        signature_bundle_path: path.relative(REPOSITORY_ROOT, signatureBundle).replaceAll('\\', '/'),
        provenance_bundle_path: path.relative(REPOSITORY_ROOT, attestationBundle).replaceAll('\\', '/'),
        provenance_envelope_path: path.relative(REPOSITORY_ROOT, envelopePath).replaceAll('\\', '/'),
        provenance_pae_path: path.relative(REPOSITORY_ROOT, paePath).replaceAll('\\', '/'),
        blob_path: artifact.blob_path ? path.relative(REPOSITORY_ROOT, artifact.blob_path).replaceAll('\\', '/') : null,
        platform_bundle_path: platformEvidence.paths.bundle ? path.relative(REPOSITORY_ROOT, platformEvidence.paths.bundle).replaceAll('\\', '/') : null,
        platform_document_path: platformEvidence.paths.document ? path.relative(REPOSITORY_ROOT, platformEvidence.paths.document).replaceAll('\\', '/') : null,
        platform_receipt_path: platformEvidence.paths.receipt ? path.relative(REPOSITORY_ROOT, platformEvidence.paths.receipt).replaceAll('\\', '/') : null,
      });
    }
    const manifest = validateManifest({
      schema_version: '1.0.0', context_sha256: sha256(contextBytes), remote_sha: context.remote_sha,
      tree_sha: context.tree_sha, image_lock_sha256: imageLockDigest, generated_at: new Date().toISOString(),
      artifact_inventory_sha256: sha256(inventoryBytes), artifact_set: releaseSet.id, scan_exception: scanException === null ? null : {
        document_sha256: scanException.document_sha256, bundle_sha256: scanException.bundle_sha256,
        expires_at: scanException.expires_at, issuer: scanException.issuer, identity: scanException.identity,
      },
      artifacts: manifestArtifacts,
    });
    const manifestPath = await atomicWrite(options.output, manifest);
    const manifestAttachments = [];
    for (const artifact of manifestArtifacts) {
      manifestAttachments.push({ subject: artifact.immutable_locator, referrer_digest: await attach(artifact.immutable_locator, 'application/vnd.accord.release-manifest.v1+json', manifestPath) });
    }
    await atomicWrite(options.evidenceIndex, {
      schema_version: '1.0.0', release_manifest_sha256: sha256(await readFile(manifestPath)),
      artifact_inventory_sha256: sha256(inventoryBytes), artifact_set: releaseSet.id, scan_exception: scanException,
      artifacts: evidenceArtifacts,
      manifest_attachments: manifestAttachments,
    });
    process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'PASS', reason_code: 'PASS', release_manifest_sha256: sha256(await readFile(manifestPath)) })}\n`);
    return 0;
  } finally {
    // Signed evidence is copied to the configured evidence index paths by protected runners before cleanup.
    // Keep the scratch tree until independent verification consumes it in the same job.
  }
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    if (error instanceof AttestationBlockedError || error?.name === 'ImageLockBlockedError') {
      process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'BLOCKED', reason_code: error.reasonCode ?? 'BLOCKED_EXTERNAL_IMAGE_RESOLUTION', detail_code: error.detailCode })}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`attest-artifacts: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
