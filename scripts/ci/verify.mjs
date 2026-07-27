import { constants as fsConstants } from 'node:fs';
import { access, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  REPOSITORY_ROOT,
  canonicalJson,
  exitCodeForStatus,
  sha256,
  writeCheckResult,
} from '../verification/check-result.mjs';
import { nativeToolInvocation } from '../verification/preflight.mjs';
import { validateContext } from './create-ci-context.mjs';

const MAX_OUTPUT = 128 * 1024;
const TOOL_TIMEOUT_MS = 15_000;
const STEP_TIMEOUT_MS = 20 * 60_000;
const TOOL_DEFINITIONS = Object.freeze([
  { id: 'node', pin: 'nodejs', executable: 'node', argv: ['--version'] },
  { id: 'java', pin: 'java', executable: 'java', argv: ['--version'] },
  {
    id: 'gradle',
    pin: 'gradle',
    executable: 'java',
    argv: [
      '-classpath', 'gradle/wrapper/gradle-wrapper.jar',
      'org.gradle.wrapper.GradleWrapperMain', '--version',
    ],
  },
  { id: 'pnpm', pin: 'pnpm', executable: 'pnpm', argv: ['--version'] },
  { id: 'uv', pin: 'uv', executable: 'uv', argv: ['--version'] },
  { id: 'buf', pin: 'buf', executable: 'buf', argv: ['--version'] },
  { id: 'helm', pin: 'helm', executable: 'helm', argv: ['version', '--short'] },
  { id: 'tofu', pin: 'opentofu', executable: 'tofu', argv: ['version'] },
  { id: 'conftest', pin: 'conftest', executable: 'conftest', argv: ['--version'] },
  { id: 'kubeconform', pin: 'kubeconform', executable: 'kubeconform', argv: ['-v'] },
  { id: 'syft', pin: 'syft', executable: 'syft', argv: ['version'] },
  { id: 'cosign', pin: 'cosign', executable: 'cosign', argv: ['version'] },
  { id: 'trivy', pin: 'trivy', executable: 'trivy', argv: ['--version'] },
  { id: 'oras', pin: 'oras', executable: 'oras', argv: ['version'] },
  { id: 'docker', pin: 'docker', executable: 'docker', argv: ['version', '--format', '{{.Client.Version}}'] },
  { id: 'docker-buildx', pin: 'docker-buildx', executable: 'docker', argv: ['buildx', 'version'] },
  { id: 'git', pin: 'git', executable: 'git', argv: ['--version'] },
]);

function parseCli(argv) {
  const result = {};
  const allowed = new Set(['--context', '--release-manifest', '--evidence-index', '--evidence-root', '--promotion-output']);
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!allowed.has(option) || !value || value.startsWith('--') || Object.hasOwn(result, option)) throw new TypeError(`Invalid verification option: ${option}`);
    result[option] = value;
  }
  if (!result['--context']) throw new TypeError('Missing --context');
  const chain = ['--release-manifest', '--evidence-index', '--evidence-root', '--promotion-output'].filter((item) => result[item]);
  if (chain.length !== 0 && chain.length !== 4) throw new TypeError('Release-chain verification inputs are all-or-none');
  return result;
}

function parsePins(text) {
  const result = new Map();
  for (const raw of text.split(/\r?\n/u)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const match = /^(\S+)\s+(\S+)$/u.exec(line);
    if (!match || result.has(match[1])) throw new TypeError('Tool-version pins are malformed or duplicated');
    result.set(match[1], match[2]);
  }
  return result;
}

async function resolveExecutable(command) {
  if (command === 'node') return process.execPath;
  const extensions = process.platform === 'win32' ? (process.env.PATHEXT ?? '.COM;.EXE;.BAT;.CMD').split(';') : [''];
  for (const directory of (process.env.PATH ?? '').split(path.delimiter)) {
    if (!directory) continue;
    for (const extension of extensions) {
      const candidate = path.join(directory, `${command}${extension}`);
      try {
        await access(candidate, process.platform === 'win32' ? fsConstants.F_OK : fsConstants.X_OK);
        return candidate;
      } catch {
        // Continue through the complete PATH.
      }
    }
  }
  return null;
}

function runNative(executable, argv, options = {}) {
  if (!Array.isArray(argv) || argv.some((argument) => typeof argument !== 'string'
      || argument.includes('\0') || argument.includes('\n') || /(?:password|token|secret|Bearer\s+)/iu.test(argument))) {
    throw new TypeError('Verification native argv is unsafe');
  }
  return new Promise((resolve) => {
    const started = process.hrtime.bigint();
    let output = '';
    let bytes = 0;
    let settled = false;
    const child = spawn(executable, argv, {
      cwd: options.cwd ?? REPOSITORY_ROOT,
      env: options.env ?? process.env,
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const append = (chunk) => {
      if (bytes >= MAX_OUTPUT) return;
      const slice = chunk.subarray(0, MAX_OUTPUT - bytes);
      bytes += slice.length;
      output += slice.toString('utf8');
    };
    child.stdout.on('data', append);
    child.stderr.on('data', append);
    const timer = setTimeout(() => child.kill('SIGKILL'), options.timeoutMs ?? STEP_TIMEOUT_MS);
    timer.unref();
    const finish = (exitCode) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const duration = Number((process.hrtime.bigint() - started) / 1_000_000n);
      resolve({ exitCode, output: output.trim(), durationMs: Math.min(duration, 300_000) });
    };
    child.once('error', () => finish(-1));
    child.once('close', (code) => finish(code ?? -1));
  });
}

function normalizedVersion(tool, output) {
  const patterns = {
    node: /\bv?(\d+[.]\d+[.]\d+)\b/u,
    java: /\b(?:Temurin-)?(\d+[.]\d+[.]\d+\+\d+)\b/iu,
    gradle: /\bGradle\s+(\d+[.]\d+(?:[.]\d+)?)\b/iu,
    pnpm: /^v?(\d+[.]\d+[.]\d+)$/mu,
    uv: /\buv\s+(\d+[.]\d+[.]\d+)\b/iu,
    buf: /\b(\d+[.]\d+[.]\d+)\b/u,
    helm: /\bv?(\d+[.]\d+[.]\d+)\b/u,
    tofu: /\bv?(\d+[.]\d+[.]\d+)\b/u,
    conftest: /\b(\d+[.]\d+[.]\d+)\b/u,
    kubeconform: /\bv?(\d+[.]\d+[.]\d+)\b/u,
    syft: /\b(\d+[.]\d+[.]\d+)\b/u,
    cosign: /\bv?(\d+[.]\d+[.]\d+)\b/u,
    trivy: /\b(?:Version:\s*)?(\d+[.]\d+[.]\d+)\b/iu,
    oras: /\bVersion:\s*(\d+[.]\d+[.]\d+)\b/iu,
    docker: /\b(\d+[.]\d+[.]\d+)\b/u,
    'docker-buildx': /\bv?(\d+[.]\d+[.]\d+)\b/u,
    git: /\bgit version\s+(\d+[.]\d+[.]\d+)(?:[.]windows[.]\d+)?\b/iu,
  };
  return patterns[tool].exec(output)?.[1] ?? '';
}

function expectedVersion(tool, pin) {
  return tool === 'java' ? pin.replace(/^temurin-/u, '') : pin;
}

export async function fullPreflight() {
  const startedAt = new Date().toISOString();
  const pinBytes = await readFile(path.join(REPOSITORY_ROOT, '.tool-versions'));
  const pins = parsePins(pinBytes.toString('utf8'));
  const observations = [];
  let blocked = false;
  for (const definition of TOOL_DEFINITIONS) {
    const expected = pins.get(definition.pin);
    const invocation = nativeToolInvocation(definition.executable, definition.argv);
    const executable = invocation.executable === definition.executable
      ? await resolveExecutable(definition.executable)
      : invocation.executable;
    if (!expected || !executable) {
      blocked = true;
      observations.push({
        tool_id: definition.id, executable: definition.executable, argv: definition.argv, exit_code: -1,
        duration_ms: 0, observed_version: 'UNAVAILABLE', normalized_version: expected ? 'UNAVAILABLE' : 'PIN_ABSENT',
      });
      continue;
    }
    const execution = await runNative(executable, invocation.argv, { timeoutMs: TOOL_TIMEOUT_MS });
    const normalized = normalizedVersion(definition.id, execution.output);
    observations.push({
      tool_id: definition.id, executable: definition.executable, argv: definition.argv,
      exit_code: execution.exitCode, duration_ms: execution.durationMs,
      observed_version: execution.output.slice(0, 1024) || 'UNAVAILABLE', normalized_version: normalized || 'UNPARSEABLE',
    });
    if (execution.exitCode !== 0 || normalized !== expectedVersion(definition.id, expected)) blocked = true;
  }
  const result = {
    schema_version: '1.0.0', check_id: 'ft16-toolchain', status: blocked ? 'BLOCKED' : 'PASS',
    reason_code: blocked ? 'BLOCKED_TOOLCHAIN' : 'PASS', started_at: startedAt,
    finished_at: new Date().toISOString(), evidence: [sha256(pinBytes)], tool_observations: observations,
  };
  await writeCheckResult(result, 'build/verification/ft16-toolchain.json');
  return result;
}

async function gate(id, executable, argv, options = {}) {
  const invocation = nativeToolInvocation(executable, argv);
  const result = await runNative(invocation.executable, invocation.argv, options);
  if (result.exitCode !== 0) throw new Error(`Verification gate failed: ${id}`);
  return result.output;
}

async function main(argv) {
  const options = parseCli(argv);
  const context = await validateContext(JSON.parse(await readFile(path.resolve(REPOSITORY_ROOT, options['--context']), 'utf8')));
  await gate('static-policy', process.execPath, ['--test',
    'tests/bootstrap/supply-chain-policy.test.mjs',
    'tests/bootstrap/ci-provider-neutral.test.mjs',
    'tests/bootstrap/release-chain.test.mjs',
  ]);
  const preflight = await fullPreflight();
  process.stdout.write(`${canonicalJson(preflight)}\n`);
  if (preflight.status === 'BLOCKED') return exitCodeForStatus(preflight.status);

  await gate('frozen-pnpm', 'pnpm', ['install', '--frozen-lockfile', '--offline']);
  await gate('locked-python', 'uv', ['sync', '--frozen', '--offline']);
  await gate('offline-gradle', 'java', [
    '-classpath', 'gradle/wrapper/gradle-wrapper.jar',
    'org.gradle.wrapper.GradleWrapperMain',
    '--offline', '--no-daemon', '--dependency-verification=strict', 'check',
  ]);
  await gate('browser', 'pnpm', ['-r', '--if-present', 'test']);
  await gate('openapi', 'pnpm', ['contracts:lint']);
  await gate('buf-lint', 'buf', ['lint']);
  await gate('buf-build', 'buf', ['build']);
  await gate('buf-breaking', 'buf', ['breaking', '--against', `.git#ref=${context.base_sha}`]);
  await gate('python-lint', 'uv', ['run', 'ruff', 'check', '.']);
  await gate('python-types', 'uv', ['run', 'mypy', '.']);
  await gate('python-tests', 'uv', ['run', 'pytest']);
  await gate('image-consumers', process.execPath, ['scripts/images/verify-images.mjs', '--scope', 'all']);
  await gate('dockerfiles', process.execPath, ['scripts/ci/render-runtime-dockerfiles.mjs', '--mode', 'verify']);
  await gate('helm', 'helm', ['lint', 'infra/helm/accord']);
  const rendered = await gate('helm-render', 'helm', ['template', 'accord', 'infra/helm/accord']);
  const renderedPath = path.join(REPOSITORY_ROOT, 'build', 'verification', 'helm-rendered.yaml');
  await writeFile(renderedPath, rendered, { encoding: 'utf8', flag: 'w', mode: 0o600 });
  await gate('kubeconform', 'kubeconform', ['-strict', '-summary', renderedPath]);
  await gate('conftest', 'conftest', ['test', '--policy', 'infra/policy', renderedPath, 'infra/argocd/project.yaml']);
  await gate('tofu-format', 'tofu', ['fmt', '-check', '-recursive', 'infra/opentofu']);
  if (options['--release-manifest']) {
    await gate('release-chain', process.execPath, [
      'scripts/ci/verify-release-chain.mjs', '--context', options['--context'],
      '--manifest', options['--release-manifest'], '--evidence-index', options['--evidence-index'],
      '--evidence-root', options['--evidence-root'],
      '--output', options['--promotion-output'],
    ]);
  }
  const status = await gate('repository-status', 'git', ['status', '--porcelain=v2', '--untracked-files=all']);
  if (status) throw new Error('Repository has tracked or untracked changes after read-only verification');
  process.stdout.write(`${canonicalJson({ schema_version: '1.0.0', status: 'PASS', reason_code: 'PASS', remote_sha: context.remote_sha })}\n`);
  return 0;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`verify: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
