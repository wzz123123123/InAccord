import { constants as fsConstants } from 'node:fs';
import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  REPOSITORY_ROOT,
  canonicalJson,
  exitCodeForStatus,
  sha256,
  writeCheckResult,
} from './check-result.mjs';

const MAX_VERSION_BYTES = 16 * 1024;
const VERSION_TIMEOUT_MS = 10_000;
const definitions = Object.freeze({
  node: { pin: 'nodejs', executable: 'node', argv: ['--version'] },
  docker: { pin: 'docker', executable: 'docker', argv: ['version', '--format', '{{.Client.Version}}'] },
  'docker-buildx': { pin: 'docker-buildx', executable: 'docker', argv: ['buildx', 'version'] },
  'docker-compose': { pin: 'docker-compose', executable: 'docker', argv: ['compose', 'version', '--short'] },
  git: { pin: 'git', executable: 'git', argv: ['--version'] },
  java: { pin: 'java', executable: 'java', argv: ['--version'] },
  pnpm: { pin: 'pnpm', executable: 'pnpm', argv: ['--version'] },
  uv: { pin: 'uv', executable: 'uv', argv: ['--version'] },
  buf: { pin: 'buf', executable: 'buf', argv: ['--version'] },
  helm: { pin: 'helm', executable: 'helm', argv: ['version', '--short'] },
  tofu: { pin: 'opentofu', executable: 'tofu', argv: ['version'] },
  conftest: { pin: 'conftest', executable: 'conftest', argv: ['--version'] },
  kubeconform: { pin: 'kubeconform', executable: 'kubeconform', argv: ['-v'] },
  syft: { pin: 'syft', executable: 'syft', argv: ['version'] },
  cosign: { pin: 'cosign', executable: 'cosign', argv: ['version'] },
  trivy: { pin: 'trivy', executable: 'trivy', argv: ['--version'] },
  oras: { pin: 'oras', executable: 'oras', argv: ['version'] },
});

export function nativeToolInvocation(executable, argv, {
  platform = process.platform,
  nodeExecutable = process.execPath,
} = {}) {
  if (platform !== 'win32' || executable !== 'pnpm') {
    return { executable, argv: [...argv] };
  }
  return {
    executable: nodeExecutable,
    argv: [
      path.win32.join(
        path.win32.dirname(nodeExecutable),
        'node_modules',
        'corepack',
        'dist',
        'pnpm.js',
      ),
      ...argv,
    ],
  };
}

function parseCli(argv) {
  let checkId;
  let output;
  const required = [];
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    if (!['--check-id', '--output', '--require'].includes(option)) throw new TypeError(`Unknown option: ${option}`);
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) throw new TypeError(`Missing value for ${option}`);
    index += 1;
    if (option === '--require') required.push(value);
    else if (option === '--check-id') {
      if (checkId) throw new TypeError('Repeated --check-id');
      checkId = value;
    } else {
      if (output) throw new TypeError('Repeated --output');
      output = value;
    }
  }
  if (!checkId) throw new TypeError('Missing --check-id');
  if (required.length === 0) throw new TypeError('At least one --require is mandatory');
  if (new Set(required).size !== required.length) throw new TypeError('Repeated --require');
  for (const name of required) if (!definitions[name]) throw new TypeError(`Unknown required tool: ${name}`);
  return { checkId, output: output ?? `build/verification/${checkId}.json`, required };
}

function parsePins(text) {
  const pins = new Map();
  for (const rawLine of text.split(/\r?\n/u)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const match = /^(\S+)\s+(\S+)$/u.exec(line);
    if (!match) throw new TypeError(`Malformed .tool-versions line: ${line}`);
    if (pins.has(match[1])) throw new TypeError(`Duplicate .tool-versions pin: ${match[1]}`);
    pins.set(match[1], match[2]);
  }
  return pins;
}

async function resolveExecutable(command) {
  if (path.isAbsolute(command)) {
    await access(command, fsConstants.X_OK);
    return command;
  }
  const extensions = process.platform === 'win32'
    ? (process.env.PATHEXT ?? '.COM;.EXE;.BAT;.CMD').split(';')
    : [''];
  for (const directory of (process.env.PATH ?? '').split(path.delimiter)) {
    if (!directory) continue;
    for (const extension of extensions) {
      const candidate = path.join(directory, `${command}${extension}`);
      try {
        await access(candidate, fsConstants.X_OK);
        return candidate;
      } catch {
        // Keep searching PATH.
      }
    }
  }
  throw new Error(`Executable not found: ${command}`);
}

function runVersion(executable, argv) {
  return new Promise((resolve) => {
    const started = process.hrtime.bigint();
    let bytes = 0;
    let output = '';
    let settled = false;
    const child = spawn(executable, argv, {
      cwd: REPOSITORY_ROOT,
      env: process.env,
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const append = (chunk) => {
      if (bytes >= MAX_VERSION_BYTES) return;
      const remaining = MAX_VERSION_BYTES - bytes;
      const sliced = chunk.subarray(0, remaining);
      bytes += sliced.length;
      output += sliced.toString('utf8');
    };
    child.stdout.on('data', append);
    child.stderr.on('data', append);
    const timer = setTimeout(() => child.kill('SIGKILL'), VERSION_TIMEOUT_MS);
    timer.unref();
    const finish = (exitCode) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const duration = Number((process.hrtime.bigint() - started) / 1_000_000n);
      resolve({ exitCode, duration: Math.min(duration, 300_000), output: output.trim() });
    };
    child.once('error', () => finish(-1));
    child.once('close', (code, signal) => finish(code ?? (signal ? -2 : -1)));
  });
}

function normalizedCore(toolId, observed) {
  const patterns = {
    node: /\bv?(\d+[.]\d+[.]\d+)\b/u,
    docker: /\b(\d+[.]\d+[.]\d+)\b/u,
    'docker-buildx': /\bv?(\d+[.]\d+[.]\d+)\b/u,
    'docker-compose': /\bv?(\d+[.]\d+[.]\d+)\b/u,
    git: /\bgit version (\d+[.]\d+[.]\d+)(?:[.]windows[.]\d+)?\b/iu,
    java: /\bTemurin-(\d+[.]\d+[.]\d+\+\d+)(?:-LTS)?\b/iu,
  };
  return (patterns[toolId] ?? /(?:^|[^0-9])v?(\d+[.]\d+[.]\d+)(?:[^0-9]|$)/u)
    .exec(observed)?.[1] ?? '';
}

function expectedCore(toolId, pin) {
  if (toolId === 'java') return pin.replace(/^temurin-/u, '');
  return pin;
}

export async function runPreflight({ checkId, output, required }) {
  const startedAt = new Date().toISOString();
  const toolVersionsPath = path.join(REPOSITORY_ROOT, '.tool-versions');
  const toolVersionBytes = await readFile(toolVersionsPath);
  const pins = parsePins(toolVersionBytes.toString('utf8'));
  const observations = [];
  let blocked = false;

  for (const toolId of required) {
    const definition = definitions[toolId];
    const expected = pins.get(definition.pin);
    if (!expected) {
      blocked = true;
      continue;
    }
    let executable;
    const invocation = nativeToolInvocation(definition.executable, definition.argv);
    try {
      executable = toolId === 'node'
        ? process.execPath
        : invocation.executable === definition.executable
          ? await resolveExecutable(definition.executable)
          : invocation.executable;
    } catch {
      blocked = true;
      observations.push({
        tool_id: toolId,
        executable: definition.executable,
        argv: definition.argv,
        exit_code: -1,
        duration_ms: 0,
        observed_version: 'UNAVAILABLE',
        normalized_version: 'UNAVAILABLE',
      });
      continue;
    }
    const execution = await runVersion(executable, invocation.argv);
    const normalized = normalizedCore(toolId, execution.output);
    observations.push({
      tool_id: toolId,
      executable: definition.executable,
      argv: definition.argv,
      exit_code: execution.exitCode,
      duration_ms: execution.duration,
      observed_version: execution.output.slice(0, 1024) || 'UNAVAILABLE',
      normalized_version: normalized || 'UNPARSEABLE',
    });
    if (execution.exitCode !== 0 || normalized !== expectedCore(toolId, expected)) blocked = true;
  }

  const result = {
    schema_version: '1.0.0',
    check_id: checkId,
    status: blocked ? 'BLOCKED' : 'PASS',
    reason_code: blocked ? 'BLOCKED_TOOLCHAIN' : 'PASS',
    started_at: startedAt,
    finished_at: new Date().toISOString(),
    evidence: [sha256(toolVersionBytes)],
    tool_observations: observations,
  };
  await writeCheckResult(result, output);
  return result;
}

async function main(argv) {
  const options = parseCli(argv);
  const result = await runPreflight(options);
  process.stdout.write(`${canonicalJson(result)}\n`);
  return exitCodeForStatus(result.status);
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`preflight: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
