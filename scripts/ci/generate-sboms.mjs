import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, open, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { REPOSITORY_ROOT, canonicalJson, sha256 } from '../verification/check-result.mjs';
import { validateContext } from './create-ci-context.mjs';

const DIGEST = /^sha256:[0-9a-f]{64}$/u;

class SbomBlockedError extends Error {
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
    else if (['--context', '--output'].includes(option)) {
      if (singles.has(option)) throw new TypeError(`Repeated option: ${option}`);
      singles.add(option);
      result[option.slice(2)] = value;
    } else throw new TypeError(`Unknown SBOM option: ${option}`);
  }
  if (!result.context || !result.output || result.facts.length === 0) throw new TypeError('Context, output, and artifact facts are required');
  return result;
}

function runNative(executable, argv, options = {}) {
  return new Promise((resolve, reject) => {
    let output = '';
    const child = spawn(executable, argv, { cwd: options.cwd ?? REPOSITORY_ROOT, env: process.env, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', (chunk) => { if (output.length < 64 * 1024) output += chunk.toString('utf8'); });
    child.stderr.on('data', (chunk) => { if (output.length < 64 * 1024) output += chunk.toString('utf8'); });
    child.once('error', () => reject(new SbomBlockedError('BLOCKED_TOOLCHAIN', 'SBOM_TOOL_UNAVAILABLE')));
    child.once('close', (code) => resolve({ exitCode: code ?? -1, output }));
  });
}

export function validateCycloneDx(document, subjectDigest) {
  if (document?.bomFormat !== 'CycloneDX' || !/^1[.](?:5|6)$/u.test(document.specVersion ?? '') || !Array.isArray(document.components)) {
    throw new TypeError('CycloneDX document has an unsupported schema or package set');
  }
  const components = document.components;
  const identifiers = components.map((item) => item.purl ?? item['bom-ref'] ?? item.name);
  if (identifiers.some((item) => typeof item !== 'string') || canonicalJson(identifiers) !== canonicalJson([...identifiers].sort())) {
    throw new TypeError('CycloneDX package identifiers are absent or unsorted');
  }
  for (const component of components) {
    if (!Array.isArray(component.licenses) || component.licenses.length === 0
        || component.licenses.some((item) => !(item?.license?.id || item?.license?.name || item?.expression))) {
      throw new TypeError('CycloneDX package license evidence is incomplete');
    }
  }
  const subjectHash = document.metadata?.component?.hashes?.find((item) => item.alg === 'SHA-256')?.content;
  if (`sha256:${subjectHash}` !== subjectDigest) throw new TypeError('CycloneDX subject digest mismatch');
  return document;
}

export function validateSpdx(document, subjectDigest) {
  if (document?.spdxVersion !== 'SPDX-2.3' || !Array.isArray(document.packages)) throw new TypeError('SPDX document has an unsupported schema or package set');
  const identifiers = document.packages.map((item) => item.SPDXID);
  if (identifiers.some((item) => typeof item !== 'string') || canonicalJson(identifiers) !== canonicalJson([...identifiers].sort())) throw new TypeError('SPDX package identifiers are absent or unsorted');
  for (const item of document.packages) {
    if (!item.licenseDeclared || ['NOASSERTION', 'NONE'].includes(item.licenseDeclared)) throw new TypeError('SPDX package license evidence is incomplete');
  }
  if (document.documentNamespace !== `https://schemas.accord.inforvans.com/sbom/sha256/${subjectDigest.slice(7)}`) throw new TypeError('SPDX subject digest mismatch');
  return document;
}

function bindCycloneDx(document, digest, name) {
  document.components ??= [];
  document.components.sort((left, right) => (left.purl ?? left['bom-ref'] ?? left.name ?? '').localeCompare(right.purl ?? right['bom-ref'] ?? right.name ?? ''));
  document.metadata ??= {};
  document.metadata.component ??= { type: 'application', name };
  document.metadata.component.hashes = [{ alg: 'SHA-256', content: digest.slice(7) }];
  return document;
}

function bindSpdx(document, digest) {
  document.packages ??= [];
  document.packages.sort((left, right) => (left.SPDXID ?? '').localeCompare(right.SPDXID ?? ''));
  document.documentNamespace = `https://schemas.accord.inforvans.com/sbom/sha256/${digest.slice(7)}`;
  return document;
}

async function atomicWrite(target, bytes) {
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = path.join(path.dirname(target), `.${path.basename(target)}.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(bytes);
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, target);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
}

async function sourceArtifact(context, outputDirectory) {
  await mkdir(outputDirectory, { recursive: true });
  const archive = path.join(outputDirectory, 'accord-source.tar');
  const result = await runNative('git', ['archive', '--format=tar', '--output', archive, context.remote_sha]);
  if (result.exitCode !== 0) throw new Error('Source archive could not be reproduced');
  const digest = sha256(await readFile(archive));
  return {
    name: 'accord-source', kind: 'source', platform: 'none', digest,
    immutable_locator: `git+${context.remote_url}@${context.remote_sha}#${digest}`,
    path: path.relative(REPOSITORY_ROOT, archive).replaceAll('\\', '/'),
  };
}

async function main(argv) {
  const options = parseCli(argv);
  const context = await validateContext(JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options.context), 'utf8')));
  const output = path.resolve(REPOSITORY_ROOT, options.output);
  const outputDirectory = path.dirname(output);
  const scratch = path.join(REPOSITORY_ROOT, 'build', 'ci', 'sbom-scratch', randomUUID());
  await mkdir(scratch, { recursive: true });
  try {
    const artifacts = [await sourceArtifact(context, outputDirectory)];
    for (const factsPath of options.facts) {
      const facts = JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, factsPath), 'utf8'));
      if (facts.remote_sha !== context.remote_sha || !Array.isArray(facts.artifacts)) throw new TypeError('Artifact facts differ from the canonical context');
      artifacts.push(...facts.artifacts);
    }
    const identity = new Set();
    const documents = [];
    for (const artifact of artifacts.sort((left, right) => `${left.name}/${left.platform}`.localeCompare(`${right.name}/${right.platform}`))) {
      if (!DIGEST.test(artifact.digest) || identity.has(`${artifact.name}/${artifact.platform}`)) throw new TypeError('Artifact facts contain invalid or duplicate subjects');
      identity.add(`${artifact.name}/${artifact.platform}`);
      if (artifact.kind === 'image' && !artifact.immutable_locator.endsWith(`@${artifact.digest}`)) throw new TypeError('Image SBOM subject is not an immutable digest');
      if (artifact.kind === 'archive') {
        const actual = sha256(await readFile(path.resolve(REPOSITORY_ROOT, artifact.path)));
        if (actual !== artifact.digest) throw new TypeError('Archive SBOM subject digest mismatch');
      }
      const scanTarget = artifact.kind === 'image' ? artifact.immutable_locator : `file:${path.resolve(REPOSITORY_ROOT, artifact.path)}`;
      const base = `${artifact.name}-${artifact.platform.replaceAll('/', '-')}`;
      const rawCyclone = path.join(scratch, `${base}.cdx.raw.json`);
      const rawSpdx = path.join(scratch, `${base}.spdx.raw.json`);
      for (const [format, target] of [['cyclonedx-json', rawCyclone], ['spdx-json', rawSpdx]]) {
        const result = await runNative('syft', ['scan', scanTarget, '--output', `${format}=${target}`]);
        if (result.exitCode !== 0) throw new SbomBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'SBOM_SUBJECT_UNAVAILABLE');
      }
      const cyclone = bindCycloneDx(JSON.parse(await readFile(rawCyclone, 'utf8')), artifact.digest, artifact.name);
      const spdx = bindSpdx(JSON.parse(await readFile(rawSpdx, 'utf8')), artifact.digest);
      validateCycloneDx(cyclone, artifact.digest);
      validateSpdx(spdx, artifact.digest);
      const cyclonePath = path.join(outputDirectory, `${base}.cdx.json`);
      const spdxPath = path.join(outputDirectory, `${base}.spdx.json`);
      const cycloneBytes = Buffer.from(`${canonicalJson(cyclone)}\n`, 'utf8');
      const spdxBytes = Buffer.from(`${canonicalJson(spdx)}\n`, 'utf8');
      await atomicWrite(cyclonePath, cycloneBytes);
      await atomicWrite(spdxPath, spdxBytes);
      documents.push({
        name: artifact.name, kind: artifact.kind, platform: artifact.platform, subject_digest: artifact.digest,
        immutable_locator: artifact.immutable_locator, path: artifact.path ?? null,
        cyclonedx: { path: path.relative(REPOSITORY_ROOT, cyclonePath).replaceAll('\\', '/'), digest: sha256(cycloneBytes) },
        spdx: { path: path.relative(REPOSITORY_ROOT, spdxPath).replaceAll('\\', '/'), digest: sha256(spdxBytes) },
      });
    }
    await atomicWrite(output, Buffer.from(`${canonicalJson({ schema_version: '1.0.0', remote_sha: context.remote_sha, documents })}\n`, 'utf8'));
    process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'PASS', reason_code: 'PASS', subjects: documents.length })}\n`);
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
    if (error instanceof SbomBlockedError) {
      process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'BLOCKED', reason_code: error.reasonCode, detail_code: error.detailCode })}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`generate-sboms: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
