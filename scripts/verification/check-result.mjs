import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

export const REPOSITORY_ROOT = path.resolve(import.meta.dirname, '..', '..');
const schemaPath = path.join(REPOSITORY_ROOT, 'contracts', 'verification', 'check-result.schema.json');
const schema = JSON.parse(await readFile(schemaPath, 'utf8'));
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
const validateSchema = ajv.compile(schema);
const digestPattern = /^sha256:[0-9a-f]{64}$/u;
const forbiddenArgument = /(?:\bBearer\s+\S+|(?:password|token|secret|access[_-]?key|private[_-]?key)=[^*\s][^\s]*)/iu;

export function sha256(bytes) {
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
}

export function canonicalJson(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
}

export function exitCodeForStatus(status) {
  if (status === 'PASS') return 0;
  if (status === 'FAIL') return 1;
  if (status === 'BLOCKED') return 2;
  throw new TypeError(`Unsupported check status: ${status}`);
}

export function validateCheckResult(result) {
  if (!validateSchema(result)) {
    const details = ajv.errorsText(validateSchema.errors, { separator: '; ' });
    throw new TypeError(`Invalid closed check result: ${details}`);
  }
  if (Date.parse(result.finished_at) < Date.parse(result.started_at)) {
    throw new TypeError('finished_at precedes started_at');
  }
  if (result.evidence.some((item) => !digestPattern.test(item))) {
    throw new TypeError('Evidence contains a non-digest value');
  }
  for (const observation of result.tool_observations) {
    for (const argument of observation.argv) {
      if (argument.includes('\0') || argument.includes('\n') || forbiddenArgument.test(argument)) {
        throw new TypeError(`Unsafe argument evidence for ${observation.tool_id}`);
      }
    }
  }
  return result;
}

function resolveOutput(output) {
  const absolute = path.resolve(REPOSITORY_ROOT, output);
  const verificationRoot = path.join(REPOSITORY_ROOT, 'build', 'verification');
  const relative = path.relative(verificationRoot, absolute);
  if (relative === '' || relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new TypeError('Check results may be written only below build/verification');
  }
  if (path.extname(absolute) !== '.json') throw new TypeError('Check-result output must be JSON');
  return absolute;
}

export async function writeCheckResult(result, output) {
  validateCheckResult(result);
  const target = resolveOutput(output);
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = path.join(path.dirname(target), `.${path.basename(target)}.${process.pid}.${randomUUID()}.tmp`);
  const bytes = `${canonicalJson(result)}\n`;
  let handle;
  try {
    handle = await open(temporary, 'wx', 0o600);
    await handle.writeFile(bytes, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, target);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
  }
  return target;
}

function parseCli(argv) {
  const parsed = { evidence: [], toolObservations: [] };
  const single = new Set();
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    if (!['--output', '--check-id', '--status', '--reason-code', '--started-at', '--finished-at', '--evidence', '--tool-observation'].includes(option)) {
      throw new TypeError(`Unknown option: ${option}`);
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) throw new TypeError(`Missing value for ${option}`);
    index += 1;
    if (option === '--evidence') parsed.evidence.push(value);
    else if (option === '--tool-observation') parsed.toolObservations.push(JSON.parse(value));
    else {
      if (single.has(option)) throw new TypeError(`Repeated option: ${option}`);
      single.add(option);
      parsed[option.slice(2).replaceAll('-', '')] = value;
    }
  }
  for (const required of ['output', 'checkid', 'status', 'reasoncode']) {
    if (!parsed[required]) throw new TypeError(`Missing required option: ${required}`);
  }
  return parsed;
}

async function main(argv) {
  const parsed = parseCli(argv);
  const now = new Date().toISOString();
  const result = {
    schema_version: '1.0.0',
    check_id: parsed.checkid,
    status: parsed.status,
    reason_code: parsed.reasoncode,
    started_at: parsed.startedat ?? now,
    finished_at: parsed.finishedat ?? now,
    evidence: parsed.evidence,
    tool_observations: parsed.toolObservations,
  };
  await writeCheckResult(result, parsed.output);
  process.stdout.write(`${canonicalJson(result)}\n`);
  return exitCodeForStatus(result.status);
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`check-result: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
