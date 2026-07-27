import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

import {
  assertBundleBindsEnvelope,
  assertBundleBindsBlob,
  buildProvenance,
  createDsseEnvelope,
  dssePae,
  validateArtifactInventory,
  validateOciBlobManifest,
} from '../../scripts/ci/attest-artifacts.mjs';
import { validateCycloneDx, validateSpdx } from '../../scripts/ci/generate-sboms.mjs';
import {
  findings,
  validateExceptionDocument,
  validateExceptions,
} from '../../scripts/ci/scan-artifacts.mjs';
import {
  assertImmutableSubject,
  assertSafeNativeArgv,
  readEvidenceFile,
  resolveEvidencePath,
  validateDsseEnvelope,
  validateScanDocument,
} from '../../scripts/ci/verify-release-chain.mjs';
import {
  packagedSmokeCommand,
  releaseArchiveName,
  validatePlatformEvidence,
  verifyCacheArtifact,
} from '../../scripts/ci/package-accordctl.mjs';

const root = path.resolve(import.meta.dirname, '..', '..');
const digest = (character) => `sha256:${character.repeat(64)}`;

async function validator(relative) {
  const schema = JSON.parse(await readFile(path.join(root, relative), 'utf8'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  return ajv.compile(schema);
}

test('golden build provenance satisfies the closed payload schema', async () => {
  const validate = await validator('contracts/dsse-payloads/build-provenance.schema.json');
  const fixture = JSON.parse(await readFile(path.join(root, 'contracts/golden-fixtures/supply-chain/build-provenance.json'), 'utf8'));
  assert.equal(validate(fixture), true, JSON.stringify(validate.errors));
  assert.equal(validate({ ...fixture, unsigned: true }), false);
});

test('both SBOM formats require sorted licensed packages and the exact subject', () => {
  const subject = digest('a');
  const cyclone = {
    bomFormat: 'CycloneDX', specVersion: '1.6',
    metadata: { component: { hashes: [{ alg: 'SHA-256', content: subject.slice(7) }] } },
    components: [{ 'bom-ref': 'pkg:a', licenses: [{ license: { id: 'Apache-2.0' } }] }],
  };
  const spdx = {
    spdxVersion: 'SPDX-2.3', documentNamespace: `https://schemas.accord.inforvans.com/sbom/sha256/${subject.slice(7)}`,
    packages: [{ SPDXID: 'SPDXRef-a', licenseDeclared: 'Apache-2.0' }],
  };
  assert.equal(validateCycloneDx(cyclone, subject), cyclone);
  assert.equal(validateSpdx(spdx, subject), spdx);
  assert.throws(() => validateCycloneDx(cyclone, digest('b')), /subject digest mismatch/u);
  assert.throws(() => validateSpdx({ ...spdx, packages: [{ SPDXID: 'x', licenseDeclared: 'NOASSERTION' }] }, subject), /license/u);
});

test('HIGH and CRITICAL findings require signed-document semantics with exact unexpired bindings', () => {
  const subject = digest('a');
  const severe = findings({ Results: [{ Vulnerabilities: [
    { VulnerabilityID: 'CVE-1', PkgName: 'a', Severity: 'HIGH' },
    { VulnerabilityID: 'CVE-2', PkgName: 'b', Severity: 'LOW' },
  ] }] });
  assert.equal(severe.length, 1);
  const exceptions = {
    schema_version: '1.0.0',
    exceptions: [{ finding_id: 'CVE-1', package: 'a', subject_digest: subject, expires_at: '2099-01-01T00:00:00Z' }],
  };
  assert.deepEqual(validateExceptions(exceptions, severe, subject, Date.parse('2026-01-01T00:00:00Z')), []);
  assert.equal(validateExceptions(exceptions, severe, digest('b'), Date.parse('2026-01-01T00:00:00Z')).length, 1);
  assert.equal(validateExceptionDocument(exceptions, Date.parse('2026-01-01T00:00:00Z')).expires_at, '2099-01-01T00:00:00Z');
  assert.throws(() => validateExceptionDocument(exceptions, Date.parse('2100-01-01T00:00:00Z')), /expired/u);
  assert.throws(() => validateExceptionDocument({
    ...exceptions,
    exceptions: [{ ...exceptions.exceptions[0], expires_at: 'not-a-date' }],
  }), /date-time/u);
});

test('verified Sigstore bundle is bound to the exact standalone DSSE envelope', () => {
  const statement = { predicateType: 'test', subject: [{ digest: { sha256: 'a'.repeat(64) } }] };
  const signature = Buffer.alloc(64, 7).toString('base64');
  const envelope = createDsseEnvelope(statement, signature);
  const pae = dssePae(envelope);
  const bundle = {
    mediaType: 'application/vnd.dev.sigstore.bundle.v0.3+json',
    messageSignature: {
      messageDigest: {
        algorithm: 'SHA2_256',
        digest: createHash('sha256').update(pae).digest('base64'),
      },
      signature,
    },
  };
  assert.equal(assertBundleBindsEnvelope(bundle, envelope), envelope);
  assert.equal(assertBundleBindsBlob(bundle, pae).signature, signature);
  const forged = createDsseEnvelope({ ...statement, predicateType: 'forged' }, signature);
  assert.throws(() => assertBundleBindsEnvelope(bundle, forged), /bind/u);
  assert.throws(() => assertBundleBindsEnvelope({ ...bundle, messageSignature: { ...bundle.messageSignature, signature: Buffer.alloc(64, 8).toString('base64') } }, envelope), /signature/u);
});

test('non-image OCI root has one exact raw archive layer', () => {
  const artifact = { digest: digest('a'), size: 321 };
  const mediaType = 'application/vnd.accord.accordctl.v1+zip';
  const manifest = {
    schemaVersion: 2,
    artifactType: mediaType,
    layers: [{ digest: artifact.digest, mediaType, size: artifact.size }],
  };
  assert.equal(validateOciBlobManifest(manifest, artifact, mediaType), manifest);
  assert.throws(() => validateOciBlobManifest({ ...manifest, layers: [{ ...manifest.layers[0], digest: digest('b') }] }, artifact, mediaType), /raw blob layer/u);
  assert.throws(() => validateOciBlobManifest({ ...manifest, layers: [...manifest.layers, manifest.layers[0]] }, artifact, mediaType), /one exact/u);
});

test('independent artifact inventory rejects a presented subset or extra artifact', async () => {
  const inventory = JSON.parse(await readFile(path.join(root, 'contracts/supply-chain/artifact-inventory.json'), 'utf8'));
  const expected = inventory.release_sets.find((item) => item.id === 'accordctl').artifacts;
  assert.equal(validateArtifactInventory(inventory, expected).id, 'accordctl');
  assert.throws(() => validateArtifactInventory(inventory, expected.slice(1)), /complete artifact inventory/u);
  assert.throws(() => validateArtifactInventory(inventory, [...expected, { name: 'extra', kind: 'archive', platform: 'linux/amd64' }]), /complete artifact inventory/u);
});

test('DSSE verification binds payload type, builder, source, lock, subject, SBOM, and scan', async () => {
  const context = JSON.parse(await readFile(path.join(root, 'contracts/golden-fixtures/supply-chain/ci-context.json'), 'utf8'));
  const artifact = {
    name: 'control-plane-api', kind: 'image', platform: 'linux/amd64', digest: digest('a'),
    immutable_locator: `registry.example.invalid/accord/control-plane-api@${digest('a')}`,
    registry_subject: `registry.example.invalid/accord/control-plane-api@${digest('a')}`,
    source_date_epoch: 1785024000,
  };
  const sbom = { cyclonedx: { digest: digest('d') }, spdx: { digest: digest('e') } };
  const scan = { digest: digest('f') };
  const statement = buildProvenance(context, artifact, digest('c'), [{ path: 'gradle.lockfile', sha256: digest('b') }], sbom, scan);
  const validate = await validator('contracts/dsse-payloads/build-provenance.schema.json');
  const schemaGate = (document) => {
    if (!validate(document)) throw new TypeError(JSON.stringify(validate.errors));
    return document;
  };
  const envelope = {
    payloadType: 'application/vnd.in-toto+json',
    payload: Buffer.from(JSON.stringify(statement)).toString('base64'),
    signatures: [{ keyid: '', sig: Buffer.alloc(64, 1).toString('base64') }],
  };
  assert.equal(validateDsseEnvelope(envelope, artifact, context, digest('c'), schemaGate).predicate.domain, 'accord.build-provenance.v1');
  assert.throws(() => validateDsseEnvelope({ ...envelope, payloadType: 'text/plain' }, artifact, context, digest('c'), schemaGate), /payload type/u);
  assert.throws(() => validateDsseEnvelope(envelope, artifact, { ...context, remote_sha: '4'.repeat(40) }, digest('c'), schemaGate), /binding mismatch/u);
});

test('release subjects and scans fail closed on tags, mismatched digest, or unsigned promotion state', () => {
  const artifact = { kind: 'image', digest: digest('a'), immutable_locator: `registry.example.invalid/accord/api@${digest('a')}` };
  assert.equal(assertImmutableSubject(artifact), artifact);
  assert.throws(() => assertImmutableSubject({ ...artifact, immutable_locator: 'registry.example.invalid/accord/api:v1' }), /immutable/u);
  assert.throws(() => assertImmutableSubject({ ...artifact, digest: digest('b') }), /differs/u);
  assert.equal(validateScanDocument({ schema_version: '1.0.0', subject_digest: digest('a'), status: 'PASS', unexcepted_findings: [] }, artifact).status, 'PASS');
  assert.throws(() => validateScanDocument({ schema_version: '1.0.0', subject_digest: digest('a'), status: 'FAIL', unexcepted_findings: [{}] }, artifact), /passing/u);
  assert.throws(() => validateScanDocument({ schema_version: '1.0.0', subject_digest: digest('a'), status: 'PASS', exception_document_sha256: digest('b'), unexcepted_findings: [] }, artifact, null), /exception/u);
});

test('detached evidence paths are rooted and GitHub OIDC issuer is not mistaken for a token', () => {
  const evidenceRoot = path.join(root, 'build', 'ci', 'detached-evidence');
  assert.equal(resolveEvidencePath(evidenceRoot, 'build/ci/file.json'), path.join(evidenceRoot, 'build', 'ci', 'file.json'));
  assert.throws(() => resolveEvidencePath(evidenceRoot, '../file.json'), /escape/u);
  assert.doesNotThrow(() => assertSafeNativeArgv(['--certificate-oidc-issuer', 'https://token.actions.githubusercontent.com']));
  assert.throws(() => assertSafeNativeArgv(['--identity-token', 'sensitive']), /secret/iu);
});

test('accordctl packaging is collision-free, verifies cache contents, and requires a signed platform receipt', async () => {
  assert.equal(releaseArchiveName('darwin/amd64'), 'accordctl-darwin-amd64.zip');
  assert.equal(releaseArchiveName('darwin/arm64'), 'accordctl-darwin-arm64.zip');
  assert.notEqual(releaseArchiveName('darwin/amd64'), releaseArchiveName('darwin/arm64'));
  const command = packagedSmokeCommand('win32', path.join(root, 'build', 'image'));
  assert.match(command.executable, /java[.]exe$/iu);
  assert.equal(command.argv.includes('--help'), true);
  assert.equal(command.executable.endsWith('.bat'), false);

  const temporary = await mkdtemp(path.join(os.tmpdir(), 'accord-cache-test-'));
  try {
    await mkdir(path.join(temporary, 'gradle-home', 'bin'), { recursive: true });
    await mkdir(path.join(temporary, 'cache'), { recursive: true });
    const gradleBytes = Buffer.from('gradle');
    const cacheBytes = Buffer.from('cache');
    await writeFile(path.join(temporary, 'gradle-home', 'bin', 'gradle'), gradleBytes);
    await writeFile(path.join(temporary, 'cache', 'entry.bin'), cacheBytes);
    const manifest = [
      '# accord-verified-gradle-cache-v1',
      `${createHash('sha256').update(cacheBytes).digest('hex')}  cache/entry.bin`,
      `${createHash('sha256').update(gradleBytes).digest('hex')}  gradle-home/bin/gradle`,
      '',
    ].join('\n');
    await writeFile(path.join(temporary, 'manifest.sha256'), manifest);
    const manifestDigest = `sha256:${createHash('sha256').update(manifest).digest('hex')}`;
    await verifyCacheArtifact(temporary, manifestDigest);
    await writeFile(path.join(temporary, 'cache', 'unlisted.init.gradle'), 'tampered');
    await assert.rejects(verifyCacheArtifact(temporary, manifestDigest), /unlisted/u);
    await rm(path.join(temporary, 'cache', 'unlisted.init.gradle'));
    await writeFile(path.join(temporary, 'cache', 'entry.bin'), 'tampered');
    await assert.rejects(verifyCacheArtifact(temporary, manifestDigest), /digest mismatch/u);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }

  const bundleBytes = Buffer.from('{"signed":true}\n');
  const receiptBytes = Buffer.from('{"nativeVerification":"accepted"}\n');
  const receipt = {
    artifact_digest: digest('a'), expires_at: '2099-01-01T00:00:00Z', kind: 'notarization',
    signature_digest: `sha256:${createHash('sha256').update(receiptBytes).digest('hex')}`,
  };
  assert.equal(validatePlatformEvidence(receipt, digest('a'), 'darwin/amd64', receiptBytes, Date.parse('2026-01-01T00:00:00Z')), receipt);
  assert.throws(() => validatePlatformEvidence({ ...receipt, signature_digest: digest('b') }, digest('a'), 'darwin/amd64', receiptBytes), /receipt/u);
  assert.notEqual(receipt.signature_digest, `sha256:${createHash('sha256').update(bundleBytes).digest('hex')}`);
});

test('release manifest requires closed native platform evidence only for Windows and Darwin archives', async () => {
  const schema = JSON.parse(await readFile(path.join(root, 'contracts/supply-chain/release-manifest.schema.json'), 'utf8'));
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  ajv.addSchema(schema);
  const validateArtifact = ajv.compile({ $ref: `${schema.$id}#/properties/artifacts/items` });
  const referrer = {
    document_sha256: digest('b'), referrer_digest: digest('c'), artifact_type: 'application/vnd.accord.test+json',
  };
  const nativeEvidence = {
    kind: 'authenticode', expires_at: '2099-01-01T00:00:00Z', document_sha256: digest('d'),
    bundle_sha256: digest('e'), receipt_sha256: digest('f'), issuer: 'https://issuer.example.invalid',
    identity: 'release-builder@example.invalid',
  };
  const artifact = {
    name: 'accordctl-windows-amd64', kind: 'archive', platform: 'windows/amd64', digest: digest('a'),
    immutable_locator: `registry.example.invalid/accord/accordctl@${digest('a')}`,
    oci_blob: { layer_digest: digest('a'), media_type: 'application/vnd.accord.accordctl.v1+zip', size: 1024 },
    platform_evidence: nativeEvidence,
    sboms: { cyclonedx: referrer, spdx: referrer }, scan: referrer,
    signature: { bundle_sha256: digest('1'), issuer: nativeEvidence.issuer, identity: nativeEvidence.identity },
    provenance: {
      envelope_sha256: digest('2'), bundle_sha256: digest('3'), pae_sha256: digest('4'),
      referrer_digest: digest('5'), payload_type: 'application/vnd.in-toto+json',
      predicate_type: 'https://schemas.accord.inforvans.com/provenance/build/v1',
    },
  };
  assert.equal(validateArtifact(artifact), true, JSON.stringify(validateArtifact.errors));
  assert.equal(validateArtifact({ ...artifact, platform_evidence: null }), false);
  assert.equal(validateArtifact({ ...artifact, platform: 'darwin/amd64', platform_evidence: { ...nativeEvidence, kind: 'notarization' } }), true, JSON.stringify(validateArtifact.errors));
  assert.equal(validateArtifact({ ...artifact, platform: 'darwin/amd64' }), false);
  assert.equal(validateArtifact({ ...artifact, platform: 'linux/amd64', platform_evidence: null }), true, JSON.stringify(validateArtifact.errors));
  assert.equal(validateArtifact({ ...artifact, platform: 'linux/amd64' }), false);
  assert.equal(validateArtifact({ ...artifact, platform_evidence: { ...nativeEvidence, unsigned: true } }), false);
});

test('release verification rejects a symbolic-link evidence entry when the host permits creating it', async (context) => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'accord-evidence-test-'));
  try {
    await writeFile(path.join(temporary, 'outside.json'), '{}');
    try {
      await symlink(path.join(temporary, 'outside.json'), path.join(temporary, 'linked.json'), 'file');
    } catch (error) {
      if (['EPERM', 'EACCES'].includes(error?.code)) {
        context.skip('host does not permit creating file symlinks');
        return;
      }
      throw error;
    }
    await assert.rejects(readEvidenceFile(temporary, 'linked.json'), /symbolic link/u);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test('Cosign 2.5 flow signs DSSE PAE and verifies the same new-format bundle', async () => {
  const attestationSource = await readFile(path.join(root, 'scripts/ci/attest-artifacts.mjs'), 'utf8');
  const verificationSource = await readFile(path.join(root, 'scripts/ci/verify-release-chain.mjs'), 'utf8');
  assert.match(attestationSource, /'sign-blob',[\s\S]*'--new-bundle-format',[\s\S]*'--bundle',[\s\S]*'--output-signature'/u);
  assert.match(verificationSource, /'verify-blob',[\s\S]*'--new-bundle-format',[\s\S]*'--bundle'/u);
  assert.doesNotMatch(attestationSource, /'attest',[\s\S]{0,200}'--bundle'/u);
  assert.doesNotMatch(attestationSource, /\['sign',[\s\S]{0,200}'--bundle'/u);
  const workflow = await readFile(path.join(root, '.github/workflows/release-accordctl.yml'), 'utf8');
  assert.match(workflow, /--platform-evidence "\$ACCORD_PLATFORM_EVIDENCE" --platform-evidence-bundle "\$ACCORD_PLATFORM_EVIDENCE_BUNDLE" --platform-evidence-receipt "\$ACCORD_PLATFORM_EVIDENCE_RECEIPT"/u);
  assert.match(workflow, /--evidence-root "\$GITHUB_WORKSPACE"/u);
  const imageWorkflow = await readFile(path.join(root, '.github/workflows/release-images.yml'), 'utf8');
  assert.match(imageWorkflow, /--evidence-root "\$GITHUB_WORKSPACE"/u);
});

test('native supply-chain commands never accept token material as argv', async () => {
  for (const name of ['build-images.mjs', 'generate-sboms.mjs', 'scan-artifacts.mjs', 'attest-artifacts.mjs', 'verify-release-chain.mjs']) {
    const source = await readFile(path.join(root, 'scripts/ci', name), 'utf8');
    assert.match(source, /shell:\s*false/u, name);
    assert.doesNotMatch(source, /['"`]--(?:identity-)?token['"`]\s*,|process[.]env[.](?:TOKEN|PASSWORD|SECRET)/iu, name);
  }
});
