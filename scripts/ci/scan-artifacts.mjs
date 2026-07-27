import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { REPOSITORY_ROOT, canonicalJson, sha256 } from '../verification/check-result.mjs';
import { validateContext } from './create-ci-context.mjs';

const DIGEST = /^sha256:[0-9a-f]{64}$/u;

class ScanBlockedError extends Error {
  constructor(reasonCode, detailCode) {
    super(detailCode);
    this.reasonCode = reasonCode;
    this.detailCode = detailCode;
  }
}

function parseCli(argv) {
  const result = {};
  const allowed = new Set(['--context', '--sboms', '--output', '--exceptions', '--exception-bundle']);
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!allowed.has(option) || !value || value.startsWith('--') || Object.hasOwn(result, option)) throw new TypeError(`Invalid scan option: ${option}`);
    result[option] = value;
  }
  if (!result['--context'] || !result['--sboms'] || !result['--output']) throw new TypeError('Context, SBOM index, and output are required');
  if (Boolean(result['--exceptions']) !== Boolean(result['--exception-bundle'])) throw new TypeError('Signed exceptions require both document and bundle');
  return result;
}

function runNative(executable, argv) {
  return new Promise((resolve, reject) => {
    let output = '';
    const child = spawn(executable, argv, { cwd: REPOSITORY_ROOT, env: process.env, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', (chunk) => { if (output.length < 64 * 1024) output += chunk.toString('utf8'); });
    child.stderr.on('data', (chunk) => { if (output.length < 64 * 1024) output += chunk.toString('utf8'); });
    child.once('error', () => reject(new ScanBlockedError('BLOCKED_TOOLCHAIN', `${executable.toUpperCase()}_UNAVAILABLE`)));
    child.once('close', (code) => resolve({ exitCode: code ?? -1, output }));
  });
}

export function findings(document) {
  const result = [];
  for (const target of document.Results ?? []) {
    for (const vulnerability of target.Vulnerabilities ?? []) {
      if (['HIGH', 'CRITICAL'].includes(vulnerability.Severity)) {
        result.push({ id: vulnerability.VulnerabilityID, package: vulnerability.PkgName, severity: vulnerability.Severity });
      }
    }
  }
  return result.sort((left, right) => `${left.id}/${left.package}`.localeCompare(`${right.id}/${right.package}`));
}

export function validateExceptions(document, severe, subjectDigest, now = Date.now()) {
  validateExceptionDocument(document, now);
  const exact = new Map();
  for (const item of document.exceptions) {
    if (item.subject_digest === subjectDigest) exact.set(`${item.finding_id}/${item.package}`, item);
  }
  return severe.filter((item) => !exact.has(`${item.id}/${item.package}`));
}

export function validateExceptionDocument(document, now = Date.now()) {
  if (canonicalJson(Object.keys(document ?? {}).sort()) !== canonicalJson(['exceptions', 'schema_version'])
      || document.schema_version !== '1.0.0' || !Array.isArray(document.exceptions) || document.exceptions.length === 0) {
    throw new TypeError('Vulnerability exception document is not closed');
  }
  const identities = new Set();
  let earliest = Number.POSITIVE_INFINITY;
  for (const item of document.exceptions) {
    if (canonicalJson(Object.keys(item ?? {}).sort()) !== canonicalJson(['expires_at', 'finding_id', 'package', 'subject_digest'])
        || typeof item.finding_id !== 'string' || item.finding_id.length === 0
        || typeof item.package !== 'string' || item.package.length === 0 || !DIGEST.test(item.subject_digest)) {
      throw new TypeError('Vulnerability exception entry is invalid or open');
    }
    const expiry = Date.parse(item.expires_at);
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:[.]\d{3})?Z$/u.test(item.expires_at) || !Number.isFinite(expiry)) {
      throw new TypeError('Vulnerability exception expiry is not a UTC date-time');
    }
    if (expiry <= now) throw new TypeError('Vulnerability exception is expired');
    const identity = `${item.finding_id}/${item.package}/${item.subject_digest}`;
    if (identities.has(identity)) throw new TypeError('Vulnerability exception is duplicated');
    identities.add(identity);
    earliest = Math.min(earliest, expiry);
  }
  return { expires_at: new Date(earliest).toISOString().replace('.000Z', 'Z') };
}

async function atomicWrite(target, document) {
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = path.join(path.dirname(target), `.${path.basename(target)}.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(`${canonicalJson(document)}\n`, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, target);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
}

async function main(argv) {
  const options = parseCli(argv);
  const context = await validateContext(JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options['--context']), 'utf8')));
  const sboms = JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options['--sboms']), 'utf8'));
  if (sboms.remote_sha !== context.remote_sha || !Array.isArray(sboms.documents)) throw new TypeError('SBOM index differs from the canonical context');
  let exceptionDocument = null;
  let exceptionEvidence = null;
  if (options['--exceptions']) {
    const exceptionPath = path.resolve(REPOSITORY_ROOT, options['--exceptions']);
    const exceptionBundlePath = path.resolve(REPOSITORY_ROOT, options['--exception-bundle']);
    for (const target of [exceptionPath, exceptionBundlePath]) {
      const relative = path.relative(REPOSITORY_ROOT, target);
      if (relative.startsWith('..') || path.isAbsolute(relative)) throw new TypeError('Vulnerability exception evidence must be repository-local');
    }
    const exceptionBytes = await readFile(exceptionPath);
    const exceptionBundleBytes = await readFile(exceptionBundlePath);
    exceptionDocument = JSON.parse(exceptionBytes.toString('utf8'));
    const validity = validateExceptionDocument(exceptionDocument);
    const verification = await runNative('cosign', [
      'verify-blob', '--bundle', exceptionBundlePath, '--certificate-identity', context.builder_subject,
      '--certificate-oidc-issuer', context.builder_issuer, exceptionPath,
    ]);
    if (verification.exitCode !== 0) throw new TypeError('Vulnerability exception signature is invalid');
    exceptionEvidence = {
      document_path: path.relative(REPOSITORY_ROOT, exceptionPath).replaceAll('\\', '/'), document_sha256: sha256(exceptionBytes),
      bundle_path: path.relative(REPOSITORY_ROOT, exceptionBundlePath).replaceAll('\\', '/'), bundle_sha256: sha256(exceptionBundleBytes),
      expires_at: validity.expires_at, issuer: context.builder_issuer, identity: context.builder_subject,
    };
  }
  const output = path.resolve(REPOSITORY_ROOT, options['--output']);
  const scans = [];
  let failed = false;
  for (const item of sboms.documents) {
    if (!DIGEST.test(item.subject_digest)) throw new TypeError('SBOM index contains an invalid subject digest');
    let command;
    if (item.kind === 'image') {
      if (!item.immutable_locator.endsWith(`@${item.subject_digest}`)) throw new TypeError('Trivy image subject is not immutable');
      command = ['image', '--format', 'json', item.immutable_locator];
    } else {
      if (!item.path) throw new TypeError('File scan subject path is absent');
      const archivePath = path.resolve(REPOSITORY_ROOT, item.path);
      if (sha256(await readFile(archivePath)) !== item.subject_digest) throw new TypeError('File scan subject digest mismatch');
      command = ['rootfs', '--format', 'json', archivePath];
    }
    const execution = await runNative('trivy', command);
    if (execution.exitCode !== 0) throw new ScanBlockedError('BLOCKED_EXTERNAL_ENVIRONMENT', 'TRIVY_DATABASE_OR_SUBJECT_UNAVAILABLE');
    const raw = JSON.parse(execution.output);
    const severe = findings(raw);
    const unexcepted = exceptionDocument ? validateExceptions(exceptionDocument, severe, item.subject_digest) : severe;
    const document = {
      schema_version: '1.0.0', subject_digest: item.subject_digest,
      database: {
        updated_at: raw.Metadata?.UpdatedAt ?? raw.Metadata?.DB?.UpdatedAt ?? null,
        version: raw.Metadata?.Version ?? raw.Metadata?.DB?.Version ?? null,
      },
      exception_document_sha256: exceptionEvidence?.document_sha256 ?? null,
      exception_bundle_sha256: exceptionEvidence?.bundle_sha256 ?? null,
      findings: severe,
      status: unexcepted.length === 0 ? 'PASS' : 'FAIL',
      unexcepted_findings: unexcepted,
    };
    const scanPath = path.join(path.dirname(output), `${item.name}-${item.platform.replaceAll('/', '-')}.trivy.json`);
    await atomicWrite(scanPath, document);
    scans.push({
      name: item.name, platform: item.platform, subject_digest: item.subject_digest,
      path: path.relative(REPOSITORY_ROOT, scanPath).replaceAll('\\', '/'), digest: sha256(Buffer.from(`${canonicalJson(document)}\n`, 'utf8')),
      status: document.status,
    });
    failed ||= document.status === 'FAIL';
  }
  await atomicWrite(output, { schema_version: '1.0.0', remote_sha: context.remote_sha, exception: exceptionEvidence, scans });
  process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: failed ? 'FAIL' : 'PASS', reason_code: failed ? 'ASSERTION_FAILED' : 'PASS' })}\n`);
  return failed ? 1 : 0;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    if (error instanceof ScanBlockedError) {
      process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'BLOCKED', reason_code: error.reasonCode, detail_code: error.detailCode })}\n`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`scan-artifacts: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    }
  }
}
