import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { lstat, mkdir, open, readFile, readdir, realpath, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

import { REPOSITORY_ROOT, canonicalJson, sha256 } from '../verification/check-result.mjs';
import { validateContext } from './create-ci-context.mjs';
import { validateCycloneDx, validateSpdx } from './generate-sboms.mjs';
import { assertBundleBindsBlob, assertBundleBindsEnvelope, dssePae, validateArtifactInventory, validateOciBlobManifest } from './attest-artifacts.mjs';
import { validatePlatformEvidence } from './package-accordctl.mjs';
import { validateExceptionDocument } from './scan-artifacts.mjs';
import { assertProductionImageLock, loadImageLock } from './render-runtime-dockerfiles.mjs';

const PAYLOAD_TYPE = 'application/vnd.in-toto+json';
const PREDICATE_TYPE = 'https://schemas.accord.inforvans.com/provenance/build/v1';
const DIGEST = /^sha256:[0-9a-f]{64}$/u;

class VerificationBlockedError extends Error {
  constructor(reasonCode, detailCode) {
    super(detailCode);
    this.reasonCode = reasonCode;
    this.detailCode = detailCode;
  }
}

function parseCli(argv) {
  const result = {};
  const allowed = new Set(['--context', '--manifest', '--evidence-index', '--evidence-root', '--output']);
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!allowed.has(option) || !value || value.startsWith('--') || Object.hasOwn(result, option)) throw new TypeError(`Invalid release verification option: ${option}`);
    result[option] = value;
  }
  for (const option of allowed) if (!result[option]) throw new TypeError(`Missing ${option}`);
  return result;
}

function runNative(executable, argv, options = {}) {
  assertSafeNativeArgv(argv);
  return new Promise((resolve, reject) => {
    let output = '';
    const child = spawn(executable, argv, { cwd: options.cwd ?? REPOSITORY_ROOT, env: process.env, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', (chunk) => { if (output.length < 128 * 1024) output += chunk.toString('utf8'); });
    child.stderr.on('data', (chunk) => { if (output.length < 128 * 1024) output += chunk.toString('utf8'); });
    child.once('error', () => reject(new VerificationBlockedError('BLOCKED_TOOLCHAIN', `${executable.toUpperCase()}_UNAVAILABLE`)));
    child.once('close', (code) => resolve({ exitCode: code ?? -1, output }));
  });
}

export function assertSafeNativeArgv(argv) {
  const forbiddenOption = /^(?:--identity-token|--password|--secret|--client-secret)(?:=|$)/iu;
  if (argv.some((argument) => forbiddenOption.test(argument) || /^Bearer\s+/iu.test(argument))) {
    throw new TypeError('Secret material is forbidden in verification argv');
  }
  return argv;
}

export function resolveEvidencePath(evidenceRoot, relativePath) {
  if (typeof relativePath !== 'string' || relativePath.length === 0 || path.isAbsolute(relativePath)
      || relativePath.includes('\\') || relativePath.split('/').some((part) => !part || part === '.' || part === '..')) {
    throw new TypeError('Evidence path may not escape its explicit root');
  }
  const root = path.resolve(evidenceRoot);
  const target = path.resolve(root, ...relativePath.split('/'));
  const relative = path.relative(root, target);
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new TypeError('Evidence path may not escape its explicit root');
  return target;
}

export async function readEvidenceFile(evidenceRoot, relativePath) {
  const root = path.resolve(evidenceRoot);
  const rootInfo = await lstat(root);
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()) throw new TypeError('Evidence root must be a real directory');
  const target = resolveEvidencePath(root, relativePath);
  let current = root;
  for (const part of relativePath.split('/')) {
    current = path.join(current, part);
    const info = await lstat(current);
    if (info.isSymbolicLink()) throw new TypeError('Evidence path contains a symbolic link');
  }
  const targetInfo = await lstat(target);
  if (!targetInfo.isFile()) throw new TypeError('Evidence entry is not a regular file');
  const canonicalRoot = await realpath(root);
  const canonicalTarget = await realpath(target);
  const relative = path.relative(canonicalRoot, canonicalTarget);
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new TypeError('Evidence path escapes its explicit root');
  return { path: target, bytes: await readFile(target) };
}

async function schemaValidator(relative) {
  const schema = JSON.parse(await readFile(path.join(REPOSITORY_ROOT, relative), 'utf8'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  return (document) => {
    if (!validate(document)) throw new TypeError(`Closed release schema rejected evidence: ${ajv.errorsText(validate.errors, { separator: '; ' })}`);
    return document;
  };
}

export function assertImmutableSubject(artifact) {
  if (!DIGEST.test(artifact.digest) || typeof artifact.immutable_locator !== 'string' || !/@sha256:[0-9a-f]{64}$/u.test(artifact.immutable_locator)) {
    throw new TypeError('Release artifact has no immutable registry subject');
  }
  if (artifact.kind === 'image' && !artifact.immutable_locator.endsWith(`@${artifact.digest}`)) throw new TypeError('Image locator digest differs from its artifact digest');
  return artifact;
}

export function validateDsseEnvelope(envelope, artifact, context, imageLockDigest, validateProvenance, expectedDependencyLocks) {
  if (canonicalJson(Object.keys(envelope).sort()) !== canonicalJson(['payload', 'payloadType', 'signatures'])
      || envelope.payloadType !== PAYLOAD_TYPE || !Array.isArray(envelope.signatures) || envelope.signatures.length === 0
      || envelope.signatures.some((item) => typeof item?.sig !== 'string' || item.sig.length < 16)) {
    throw new TypeError('DSSE envelope is unsigned, open, or has the wrong payload type');
  }
  const statement = validateProvenance(JSON.parse(Buffer.from(envelope.payload, 'base64').toString('utf8')));
  if (statement.predicateType !== PREDICATE_TYPE || statement.predicate.domain !== 'accord.build-provenance.v1') throw new TypeError('Provenance predicate type or domain mismatch');
  if (statement.subject.length !== 1 || statement.subject[0].name !== artifact.immutable_locator || `sha256:${statement.subject[0].digest.sha256}` !== artifact.digest) throw new TypeError('Provenance subject mismatch');
  const builder = statement.predicate.builder;
  const ci = statement.predicate.ci;
  const source = statement.predicate.source;
  if (builder.issuer !== context.builder_issuer || builder.subject !== context.builder_subject || builder.audience !== context.builder_audience
      || ci.system !== context.ci_system || ci.run_id !== context.run_id || ci.workflow_id !== context.workflow_id
      || source.remote_url !== context.remote_url || source.remote_ref !== context.remote_ref || source.remote_sha !== context.remote_sha
      || source.tree_sha !== context.tree_sha || source.base_sha !== context.base_sha
      || statement.predicate.materials.image_lock_sha256 !== imageLockDigest
      || (expectedDependencyLocks && canonicalJson(statement.predicate.materials.dependency_locks) !== canonicalJson(expectedDependencyLocks))
      || statement.predicate.parameters.platform !== artifact.platform
      || statement.predicate.artifact.digest !== artifact.digest || statement.predicate.artifact.immutable_locator !== artifact.immutable_locator) {
    throw new TypeError('Provenance authority, source, material, or artifact binding mismatch');
  }
  return statement;
}

export function validateScanDocument(document, artifact, scanException = null) {
  if (document.schema_version !== '1.0.0' || document.subject_digest !== artifact.digest || document.status !== 'PASS'
      || !Array.isArray(document.unexcepted_findings) || document.unexcepted_findings.length !== 0) throw new TypeError('Scan evidence is not an exact passing subject result');
  if ((document.exception_document_sha256 ?? null) !== (scanException?.document_sha256 ?? null)
      || (document.exception_bundle_sha256 ?? null) !== (scanException?.bundle_sha256 ?? null)) {
    throw new TypeError('Scan exception evidence is absent or bound to another signed document');
  }
  return document;
}

async function atomicWrite(target, document) {
  const absolute = path.resolve(REPOSITORY_ROOT, target);
  const relative = path.relative(REPOSITORY_ROOT, absolute);
  if (relative.startsWith('..') || path.isAbsolute(relative) || path.extname(absolute) !== '.json') throw new TypeError('Promotion output must be repository-local JSON');
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
}

function referrerDigests(discovery) {
  const result = [];
  const visit = (value) => {
    if (!value || typeof value !== 'object') return;
    if (DIGEST.test(value.digest)) result.push(value.digest);
    for (const child of Object.values(value)) visit(child);
  };
  visit(discovery);
  return new Set(result);
}

async function dependencyLockMaterials() {
  const inventory = await runNative('git', ['ls-files', '--', '*gradle.lockfile', 'pnpm-lock.yaml', 'uv.lock', 'gradle/verification-metadata.xml']);
  if (inventory.exitCode !== 0) throw new Error('Dependency-lock inventory failed');
  const paths = inventory.output.split(/\r?\n/u).map((item) => item.trim()).filter(Boolean).sort();
  if (paths.length === 0) throw new TypeError('Dependency-lock inventory is empty');
  return Promise.all(paths.map(async (item) => ({ path: item.replaceAll('\\', '/'), sha256: sha256(await readFile(path.join(REPOSITORY_ROOT, item))) })));
}

async function refetchAndVerify(subject, digest, expectedDocumentDigest, scratch) {
  const repository = subject.slice(0, subject.indexOf('@'));
  const target = path.join(scratch, digest.slice(7));
  await mkdir(target, { recursive: true });
  const pull = await runNative('oras', ['pull', '--output', target, `${repository}@${digest}`]);
  if (pull.exitCode !== 0) throw new TypeError('Registry referrer could not be refetched');
  const files = await readdir(target);
  if (files.length !== 1 || sha256(await readFile(path.join(target, files[0]))) !== expectedDocumentDigest) throw new TypeError('Registry referrer payload digest mismatch');
}

async function verifyOciBlobSubject(artifact, blobBytes, scratch) {
  if (!artifact.oci_blob || artifact.oci_blob.layer_digest !== artifact.digest || artifact.oci_blob.size !== blobBytes.length) {
    throw new TypeError('Non-image OCI blob contract is missing or stale');
  }
  const fetched = await runNative('oras', ['manifest', 'fetch', artifact.immutable_locator]);
  if (fetched.exitCode !== 0) throw new VerificationBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'REGISTRY_ARTIFACT_MANIFEST_UNAVAILABLE');
  validateOciBlobManifest(JSON.parse(fetched.output), { digest: artifact.digest, size: blobBytes.length }, artifact.oci_blob.media_type);
  const target = path.join(scratch, `subject-${randomUUID()}`);
  await mkdir(target, { recursive: true });
  const pull = await runNative('oras', ['pull', '--output', target, artifact.immutable_locator]);
  if (pull.exitCode !== 0) throw new VerificationBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'REGISTRY_ARTIFACT_BLOB_UNAVAILABLE');
  const files = await readdir(target);
  if (files.length !== 1 || sha256(await readFile(path.join(target, files[0]))) !== artifact.digest) {
    throw new TypeError('Non-image OCI subject does not contain the exact raw archive');
  }
}

async function verifyImageSubject(artifact, manifestBytes, scratch) {
  if (artifact.oci_blob !== null || sha256(manifestBytes) !== artifact.digest) throw new TypeError('Image manifest evidence is missing or stale');
  const target = path.join(scratch, `image-${randomUUID()}.manifest.json`);
  const fetched = await runNative('oras', ['manifest', 'fetch', '--output', target, artifact.immutable_locator]);
  if (fetched.exitCode !== 0) throw new VerificationBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'REGISTRY_IMAGE_MANIFEST_UNAVAILABLE');
  const refetched = await readFile(target);
  if (sha256(refetched) !== artifact.digest || !refetched.equals(manifestBytes)) throw new TypeError('Registry image manifest differs from the exact signed bytes');
}

async function main(argv) {
  const options = parseCli(argv);
  const evidenceRoot = path.resolve(options['--evidence-root']);
  const contextBytes = await readFile(path.resolve(REPOSITORY_ROOT, options['--context']));
  const context = await validateContext(JSON.parse(contextBytes.toString('utf8')));
  const manifestBytes = await readFile(path.resolve(REPOSITORY_ROOT, options['--manifest']));
  const manifestText = manifestBytes.toString('utf8');
  const manifest = JSON.parse(manifestText);
  if (manifestText !== `${canonicalJson(manifest)}\n`) throw new TypeError('Release manifest is not canonical key-sorted JSON');
  const validateManifest = await schemaValidator('contracts/supply-chain/release-manifest.schema.json');
  const validateProvenance = await schemaValidator('contracts/dsse-payloads/build-provenance.schema.json');
  const validateInventory = await schemaValidator('contracts/supply-chain/artifact-inventory.schema.json');
  validateManifest(manifest);
  const inventoryBytes = await readFile(path.join(REPOSITORY_ROOT, 'contracts', 'supply-chain', 'artifact-inventory.json'));
  const inventory = validateInventory(JSON.parse(inventoryBytes.toString('utf8')));
  const releaseSet = validateArtifactInventory(inventory, manifest.artifacts);
  if (manifest.artifact_inventory_sha256 !== sha256(inventoryBytes) || manifest.artifact_set !== releaseSet.id) {
    throw new TypeError('Release manifest does not bind the authoritative complete artifact inventory');
  }
  const evidence = JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options['--evidence-index']), 'utf8'));
  if (canonicalJson(Object.keys(evidence).sort()) !== canonicalJson([
    'artifact_inventory_sha256', 'artifact_set', 'artifacts', 'manifest_attachments', 'release_manifest_sha256', 'scan_exception', 'schema_version',
  ])
      || evidence.schema_version !== '1.0.0' || !Array.isArray(evidence.artifacts) || !Array.isArray(evidence.manifest_attachments)) {
    throw new TypeError('Release evidence index is open or malformed');
  }
  if (evidence.release_manifest_sha256 !== sha256(manifestBytes) || manifest.context_sha256 !== sha256(contextBytes)
      || manifest.remote_sha !== context.remote_sha || manifest.tree_sha !== context.tree_sha
      || evidence.artifact_inventory_sha256 !== manifest.artifact_inventory_sha256 || evidence.artifact_set !== manifest.artifact_set) {
    throw new TypeError('Release manifest context, inventory, or evidence index binding mismatch');
  }
  const { lock: imageLock, digest: imageLockDigest } = await loadImageLock();
  assertProductionImageLock(imageLock);
  if (manifest.image_lock_sha256 !== imageLockDigest) throw new TypeError('Release manifest image-lock material mismatch');
  const expectedDependencyLocks = await dependencyLockMaterials();
  let scanException = null;
  if (manifest.scan_exception === null) {
    if (evidence.scan_exception !== null) throw new TypeError('Unsigned scan exception evidence is forbidden');
  } else {
    const localException = evidence.scan_exception;
    if (canonicalJson(Object.keys(localException ?? {}).sort()) !== canonicalJson([
      'bundle_path', 'bundle_sha256', 'document_path', 'document_sha256', 'expires_at', 'identity', 'issuer',
    ])) throw new TypeError('Release scan exception evidence is open or missing');
    if (canonicalJson({
      document_sha256: localException.document_sha256, bundle_sha256: localException.bundle_sha256,
      expires_at: localException.expires_at, issuer: localException.issuer, identity: localException.identity,
    }) !== canonicalJson(manifest.scan_exception)) throw new TypeError('Release scan exception manifest binding mismatch');
    const exceptionDocument = await readEvidenceFile(evidenceRoot, localException.document_path);
    const exceptionBundle = await readEvidenceFile(evidenceRoot, localException.bundle_path);
    if (sha256(exceptionDocument.bytes) !== localException.document_sha256 || sha256(exceptionBundle.bytes) !== localException.bundle_sha256
        || localException.issuer !== context.builder_issuer || localException.identity !== context.builder_subject) {
      throw new TypeError('Release scan exception bytes or authority changed');
    }
    const validity = validateExceptionDocument(JSON.parse(exceptionDocument.bytes.toString('utf8')));
    if (validity.expires_at !== localException.expires_at) throw new TypeError('Release scan exception expiry binding mismatch');
    const verification = await runNative('cosign', [
      'verify-blob', '--bundle', exceptionBundle.path, '--certificate-identity', context.builder_subject,
      '--certificate-oidc-issuer', context.builder_issuer, exceptionDocument.path,
    ]);
    if (verification.exitCode !== 0) throw new TypeError('Release scan exception signature, issuer, or identity is invalid');
    scanException = manifest.scan_exception;
  }
  const identities = new Set();
  const scratch = path.join(REPOSITORY_ROOT, 'build', 'ci', 'release-refetch', randomUUID());
  await mkdir(scratch, { recursive: true });
  try {
    for (const artifact of manifest.artifacts) {
      assertImmutableSubject(artifact);
      const identity = `${artifact.name}/${artifact.platform}`;
      if (identities.has(identity)) throw new TypeError('Release manifest contains duplicate artifacts');
      identities.add(identity);
      const local = evidence.artifacts.find((item) => item.name === artifact.name && item.platform === artifact.platform && item.subject === artifact.immutable_locator);
      if (!local) throw new TypeError('Release artifact local evidence index is missing or stale');
      if (canonicalJson(Object.keys(local).sort()) !== canonicalJson([
        'blob_path', 'cyclonedx_path', 'name', 'platform', 'provenance_bundle_path',
        'platform_bundle_path', 'platform_document_path', 'platform_receipt_path', 'provenance_envelope_path',
        'provenance_pae_path', 'scan_path', 'signature_bundle_path', 'spdx_path', 'subject',
      ])) throw new TypeError('Release artifact evidence entry is open');
      const cyclone = await readEvidenceFile(evidenceRoot, local.cyclonedx_path);
      const spdx = await readEvidenceFile(evidenceRoot, local.spdx_path);
      const scanEvidence = await readEvidenceFile(evidenceRoot, local.scan_path);
      const envelopeEvidence = await readEvidenceFile(evidenceRoot, local.provenance_envelope_path);
      const paeEvidence = await readEvidenceFile(evidenceRoot, local.provenance_pae_path);
      const signatureBundleEvidence = await readEvidenceFile(evidenceRoot, local.signature_bundle_path);
      const provenanceBundleEvidence = await readEvidenceFile(evidenceRoot, local.provenance_bundle_path);
      const blobEvidence = local.blob_path === null ? null : await readEvidenceFile(evidenceRoot, local.blob_path);
      const nativePlatform = artifact.kind === 'archive'
        && (artifact.platform.startsWith('windows/') || artifact.platform.startsWith('darwin/'));
      let platformBundleEvidence = null;
      let platformDocumentEvidence = null;
      let platformReceiptEvidence = null;
      if (nativePlatform) {
        if (artifact.platform_evidence === null) throw new TypeError('Native archive platform evidence is absent');
        platformBundleEvidence = await readEvidenceFile(evidenceRoot, local.platform_bundle_path);
        platformDocumentEvidence = await readEvidenceFile(evidenceRoot, local.platform_document_path);
        platformReceiptEvidence = await readEvidenceFile(evidenceRoot, local.platform_receipt_path);
      } else if (artifact.platform_evidence !== null || local.platform_bundle_path !== null
          || local.platform_document_path !== null || local.platform_receipt_path !== null) {
        throw new TypeError('Generic artifact contains unexpected native platform evidence');
      }
      const cycloneBytes = cyclone.bytes;
      const spdxBytes = spdx.bytes;
      const scanBytes = scanEvidence.bytes;
      const envelopeBytes = envelopeEvidence.bytes;
      const signatureBundle = signatureBundleEvidence.bytes;
      const provenanceBundle = provenanceBundleEvidence.bytes;
      if (sha256(cycloneBytes) !== artifact.sboms.cyclonedx.document_sha256 || sha256(spdxBytes) !== artifact.sboms.spdx.document_sha256
          || sha256(scanBytes) !== artifact.scan.document_sha256 || sha256(envelopeBytes) !== artifact.provenance.envelope_sha256
          || sha256(paeEvidence.bytes) !== artifact.provenance.pae_sha256
          || sha256(signatureBundle) !== artifact.signature.bundle_sha256 || sha256(provenanceBundle) !== artifact.provenance.bundle_sha256) {
        throw new TypeError('Release evidence local digest mismatch');
      }
      if (nativePlatform) {
        const platformEvidence = artifact.platform_evidence;
        if (sha256(platformDocumentEvidence.bytes) !== platformEvidence.document_sha256
            || sha256(platformBundleEvidence.bytes) !== platformEvidence.bundle_sha256
            || sha256(platformReceiptEvidence.bytes) !== platformEvidence.receipt_sha256
            || platformEvidence.issuer !== context.builder_issuer || platformEvidence.identity !== context.builder_subject) {
          throw new TypeError('Native platform evidence bytes or authority changed');
        }
        const platformDocument = validatePlatformEvidence(
          JSON.parse(platformDocumentEvidence.bytes.toString('utf8')),
          artifact.digest,
          artifact.platform,
          platformReceiptEvidence.bytes,
        );
        if (platformDocument.kind !== platformEvidence.kind || platformDocument.expires_at !== platformEvidence.expires_at
            || platformDocument.signature_digest !== platformEvidence.receipt_sha256) {
          throw new TypeError('Native platform evidence declaration binding mismatch');
        }
        const platformVerification = await runNative('cosign', [
          'verify-blob', '--bundle', platformBundleEvidence.path,
          '--certificate-identity', context.builder_subject, '--certificate-oidc-issuer', context.builder_issuer,
          platformDocumentEvidence.path,
        ]);
        if (platformVerification.exitCode !== 0) throw new TypeError('Native platform evidence signature, issuer, or identity is invalid');
      }
      if (artifact.kind === 'image') {
        if (!blobEvidence) throw new TypeError('Image evidence is missing its exact registry manifest bytes');
        await verifyImageSubject(artifact, blobEvidence.bytes, scratch);
      } else {
        if (!blobEvidence || sha256(blobEvidence.bytes) !== artifact.digest) throw new TypeError('Non-image local raw archive evidence mismatch');
        await verifyOciBlobSubject(artifact, blobEvidence.bytes, scratch);
      }
      validateCycloneDx(JSON.parse(cycloneBytes), artifact.digest);
      validateSpdx(JSON.parse(spdxBytes), artifact.digest);
      const scan = validateScanDocument(JSON.parse(scanBytes), artifact, scanException);
      const envelope = JSON.parse(envelopeBytes);
      const statement = validateDsseEnvelope(envelope, artifact, context, imageLockDigest, validateProvenance, expectedDependencyLocks);
      if (!paeEvidence.bytes.equals(dssePae(envelope))) throw new TypeError('Standalone DSSE envelope differs from the signed PAE bytes');
      assertBundleBindsEnvelope(JSON.parse(provenanceBundle), envelope);
      assertBundleBindsBlob(JSON.parse(signatureBundle), blobEvidence.bytes);
      if (statement.predicate.sboms.cyclonedx_sha256 !== artifact.sboms.cyclonedx.document_sha256
          || statement.predicate.sboms.spdx_sha256 !== artifact.sboms.spdx.document_sha256
          || statement.predicate.scan_sha256 !== artifact.scan.document_sha256 || scan.subject_digest !== artifact.digest) throw new TypeError('Provenance SBOM or scan binding mismatch');

      const verifyArgs = [
        'verify-blob', '--new-bundle-format', '--bundle', signatureBundleEvidence.path,
        '--certificate-identity', context.builder_subject, '--certificate-oidc-issuer', context.builder_issuer, blobEvidence.path,
      ];
      if ((await runNative('cosign', verifyArgs)).exitCode !== 0) throw new TypeError('Artifact signature identity, issuer, bundle, or subject verification failed');
      const attestArgs = [
        'verify-blob', '--new-bundle-format', '--bundle', provenanceBundleEvidence.path,
        '--certificate-identity', context.builder_subject, '--certificate-oidc-issuer', context.builder_issuer, paeEvidence.path,
      ];
      if ((await runNative('cosign', attestArgs)).exitCode !== 0) throw new TypeError('Artifact provenance signature verification failed');

      const discovery = await runNative('oras', ['discover', '--format', 'json', artifact.immutable_locator]);
      if (discovery.exitCode !== 0) throw new VerificationBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'REGISTRY_REFERRERS_UNAVAILABLE');
      const refs = referrerDigests(JSON.parse(discovery.output));
      const expected = [artifact.sboms.cyclonedx, artifact.sboms.spdx, artifact.scan, { referrer_digest: artifact.provenance.referrer_digest, document_sha256: artifact.provenance.envelope_sha256 }];
      for (const referrer of expected) {
        if (!refs.has(referrer.referrer_digest)) throw new TypeError('Required ORAS referrer digest is absent');
        await refetchAndVerify(artifact.immutable_locator, referrer.referrer_digest, referrer.document_sha256, scratch);
      }
      const manifestAttachment = evidence.manifest_attachments.find((item) => item.subject === artifact.immutable_locator);
      if (!manifestAttachment || canonicalJson(Object.keys(manifestAttachment).sort()) !== canonicalJson(['referrer_digest', 'subject'])
          || !refs.has(manifestAttachment.referrer_digest)) throw new TypeError('Release manifest referrer is absent from its exact subject');
      await refetchAndVerify(artifact.immutable_locator, manifestAttachment.referrer_digest, sha256(manifestBytes), scratch);
    }
    if (evidence.artifacts.length !== manifest.artifacts.length || evidence.manifest_attachments.length !== manifest.artifacts.length) throw new TypeError('Release evidence contains missing or extra artifacts');
    const promotion = {
      schema_version: '1.0.0', remote_sha: context.remote_sha, tree_sha: context.tree_sha,
      release_manifest_sha256: sha256(manifestBytes), artifacts: manifest.artifacts.map((item) => ({ name: item.name, platform: item.platform, immutable_locator: item.immutable_locator })),
    };
    await atomicWrite(options['--output'], promotion);
    process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'PASS', reason_code: 'PASS', promotion_sha256: sha256(Buffer.from(`${canonicalJson(promotion)}\n`, 'utf8')) })}\n`);
    return 0;
  } finally {
    await rm(scratch, { recursive: true, force: true });
  }
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    if (error instanceof VerificationBlockedError || error?.name === 'ImageLockBlockedError') {
      process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'BLOCKED', reason_code: error.reasonCode ?? 'BLOCKED_EXTERNAL_IMAGE_RESOLUTION', detail_code: error.detailCode })}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`verify-release-chain: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
