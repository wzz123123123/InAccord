import { lstat, readFile, readdir } from 'node:fs/promises';
import { basename, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { atomicWriteJson } from '../../scripts/acceptance/merge-verdicts.mjs';

const MAX_FILE_BYTES = 64 * 1024 * 1024;
const RULES = [
  ['AUTHORIZATION_HEADER', /\bauthorization\s*[:=]\s*["']?(?:bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}/giu],
  ['COOKIE_HEADER', /\b(?:cookie|set-cookie)\s*[:=]\s*["']?[^\s,"']{4,}/giu],
  ['TOKEN_OR_CREDENTIAL', /\b(?:access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|client[_-]?secret|password)\b\s*[:=]\s*["']?[^\s,"']{6,}/giu],
  ['PEM_MATERIAL', /-----BEGIN(?: [A-Z0-9]+)* (?:CERTIFICATE|PRIVATE KEY)-----/gu],
  ['PRIVATE_KEY_MATERIAL', /\bprivate[_ -]?key\b\s*[:=]\s*["']?[A-Za-z0-9+/=_-]{8,}/giu],
  ['RAW_WEBHOOK_BODY', /["']?(?:raw[_-]?(?:webhook[_-]?)?body|request[_-]?body)["']?\s*[:=]/giu],
  ['SOURCE_DIFF_ARCHIVE', /(?:^|\n)(?:diff --git |@@ -\d|--- a\/|\+\+\+ b\/)|["']?(?:source[_-]?code|source[_-]?archive|diff[_-]?text|archive[_-]?base64)["']?\s*[:=]/giu],
  ['PROMPT_CONTENT', /["']?(?:system[_-]?prompt|user[_-]?prompt|prompt[_-]?content)["']?\s*[:=]/giu],
  ['SECRET_VALUE', /\bsecret\b\s*[:=]\s*["']?[^\s,"']{6,}/giu],
  ['SENSITIVE_URL_QUERY', /https?:\/\/[^\s"']+[?&](?:token|access_token|key|secret|password)=[^\s&#"']+/giu],
  ['EXCEPTION_DETAIL', /(?:^|\n)(?:[A-Za-z0-9_.$]+(?:Exception|Error):|\s+at [A-Za-z0-9_.$]+\()/gu],
];

export function scanSensitiveText(text, sentinels = []) {
  const counts = new Map();
  for (const [ruleId, expression] of RULES) {
    expression.lastIndex = 0;
    let count = 0;
    while (expression.exec(text) !== null) count += 1;
    if (count > 0) counts.set(ruleId, count);
  }
  for (const sentinel of sentinels) {
    if (typeof sentinel !== 'string' || sentinel.length < 8) continue;
    let count = 0;
    let offset = 0;
    while ((offset = text.indexOf(sentinel, offset)) !== -1) {
      count += 1;
      offset += sentinel.length;
    }
    if (count > 0) counts.set('SEEDED_SENTINEL', (counts.get('SEEDED_SENTINEL') ?? 0) + count);
  }
  return [...counts.entries()]
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
    .map(([rule_id, count]) => ({ rule_id, count }));
}

export async function verifySensitiveLogs({ services, sentinels = [] }) {
  const aggregated = new Map();
  let filesScanned = 0;
  for (const entry of [...services].sort((left, right) => left.service.localeCompare(right.service))) {
    validateServiceName(entry.service);
    for (const file of await listRegularFiles(resolve(entry.path))) {
      const info = await lstat(file);
      if (info.size > MAX_FILE_BYTES) throw safeError('LOG_FILE_TOO_LARGE');
      const text = (await readFile(file)).toString('utf8');
      filesScanned += 1;
      for (const match of scanSensitiveText(text, sentinels)) {
        const key = `${entry.service}\u0000${match.rule_id}`;
        aggregated.set(key, (aggregated.get(key) ?? 0) + match.count);
      }
    }
  }
  const matches = [...aggregated.entries()]
    .map(([key, count]) => {
      const [service, rule_id] = key.split('\u0000');
      return { service, rule_id, count };
    })
    .sort((left, right) => {
      const leftKey = `${left.service}\u0000${left.rule_id}`;
      const rightKey = `${right.service}\u0000${right.rule_id}`;
      return leftKey < rightKey ? -1 : leftKey > rightKey ? 1 : 0;
    });
  return {
    schema_version: '1.0.0',
    status: matches.length === 0 ? 'PASS' : 'FAIL',
    files_scanned: filesScanned,
    matches,
  };
}

async function listRegularFiles(root) {
  const info = await lstat(root);
  if (info.isSymbolicLink()) throw safeError('LOG_SYMLINK_FORBIDDEN');
  if (info.isFile()) return [root];
  if (!info.isDirectory()) throw safeError('LOG_PATH_INVALID');
  const files = [];
  const entries = await readdir(root, { withFileTypes: true });
  entries.sort((left, right) => left.name.localeCompare(right.name));
  for (const entry of entries) {
    const child = resolve(root, entry.name);
    if (entry.isSymbolicLink()) throw safeError('LOG_SYMLINK_FORBIDDEN');
    if (entry.isDirectory()) files.push(...await listRegularFiles(child));
    else if (entry.isFile()) files.push(child);
  }
  return files;
}

function validateServiceName(service) {
  if (typeof service !== 'string' || !/^[a-z0-9][a-z0-9_.-]{1,63}$/u.test(service)) {
    throw safeError('SERVICE_NAME_INVALID');
  }
}

function safeError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

function parseCli(argv) {
  const services = [];
  let sentinelFile;
  let output;
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (value === undefined) throw safeError('ARGUMENTS_INVALID');
    if (flag === '--service') {
      const separator = value.indexOf('=');
      if (separator < 2) throw safeError('ARGUMENTS_INVALID');
      services.push({ service: value.slice(0, separator), path: value.slice(separator + 1) });
    } else if (flag === '--sentinel-file') {
      sentinelFile = value;
    } else if (flag === '--output') {
      output = value;
    } else {
      throw safeError('ARGUMENTS_INVALID');
    }
  }
  if (services.length === 0) throw safeError('ARGUMENTS_INVALID');
  return { services, sentinelFile, output };
}

async function main() {
  try {
    const options = parseCli(process.argv.slice(2));
    const sentinels = options.sentinelFile
      ? JSON.parse(await readFile(resolve(options.sentinelFile), 'utf8'))
      : [];
    if (!Array.isArray(sentinels)) throw safeError('SENTINEL_FILE_INVALID');
    const result = await verifySensitiveLogs({ services: options.services, sentinels });
    if (options.output) await atomicWriteJson(options.output, result);
    process.stdout.write(`sensitive-log-verification: ${result.status}\n`);
    process.exitCode = result.status === 'PASS' ? 0 : 1;
  } catch (error) {
    const code = typeof error?.code === 'string' ? error.code : 'UNEXPECTED_ERROR';
    process.stderr.write(`sensitive-log-verification: FAIL (${code})\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
