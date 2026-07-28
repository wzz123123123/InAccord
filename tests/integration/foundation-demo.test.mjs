import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import {
  collectEnvironmentEvidence,
  verifyReceipt,
} from '../../scripts/acceptance/collect-environment-evidence.mjs';
import {
  canonicalJson,
  validateCheckEvidence,
} from '../../scripts/acceptance/merge-verdicts.mjs';
import {
  inspectDemoBoundaries,
  runFoundationDemo,
} from '../../scripts/acceptance/run-foundation-demo.mjs';
import {
  scanSensitiveText,
  verifySensitiveLogs,
} from './verify-sensitive-logs.mjs';

const REMOTE_SHA = 'a'.repeat(40);
const TREE_SHA = 'b'.repeat(40);
const REQUIRED_DEMO_FILES = [
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/EventTransport.java',
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/FencedReconciliationObservation.java',
  'tests/integration/src/test/java/com/inforvans/accord/integration/FoundationProductLoopIT.java',
  'infra/images/images.lock.json',
  'infra/local/compose.yaml',
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/ProviderObservationPort.java',
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/workflow/ReadOnlyReconciliationActivity.java',
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalMtlsIT.java',
];

test('missing repository-owned demo boundaries are ASSERTION_FAILED, never provider BLOCKED', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-demo-missing-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const output = join(root, 'external-evidence');
  const boundaries = await inspectDemoBoundaries(root);
  assert.ok(boundaries.length >= 8);
  assert.ok(boundaries.every((entry) => entry.present === false));
  const result = await runFoundationDemo({
    repository: root,
    outputDirectory: output,
    now: new Date('2026-07-26T00:00:00Z'),
  });
  assert.equal(result.status, 'FAIL');
  assert.equal(result.reason_code, 'ASSERTION_FAILED');
  assert.equal(result.check_id, '08.foundation-demo');
  assert.equal(Object.hasOwn(result, 'external'), false);
  await validateCheckEvidence(result);
  const stored = await readFile(join(output, 'demo-evidence.json'), 'utf8');
  assert.doesNotMatch(stored, /ReadOnlyReconciliationActivity|ProviderObservationPort/u);
});

test('resolved code boundaries with no committed authoritative image lock are ASSERTION_FAILED', async (context) => {
  const root = await createDemoRepository('accord-demo-image-lock-blocked-', new Set([
    'infra/images/images.lock.json',
  ]));
  context.after(() => rm(root, { recursive: true, force: true }));
  const result = await runFoundationDemo({
    repository: root,
    outputDirectory: join(root, 'external-evidence'),
    remoteSha: REMOTE_SHA,
    treeSha: TREE_SHA,
    run: () => assert.fail('a missing authoritative image lock must not start the topology'),
    now: new Date('2026-07-26T00:00:00Z'),
  });
  assert.equal(result.status, 'FAIL');
  assert.equal(result.reason_code, 'ASSERTION_FAILED');
  assert.equal(Object.hasOwn(result, 'external'), false);
  await validateCheckEvidence(result);
});

test('demo rejects successful commands plus test-authored PASS log and telemetry', async (context) => {
  const root = await createDemoRepository('accord-demo-lifecycle-');
  context.after(() => rm(root, { recursive: true, force: true }));
  const output = join(root, 'external-evidence');
  await mkdir(join(output, 'logs'), { recursive: true });
  await mkdir(join(output, 'telemetry'), { recursive: true });
  await writeFile(
    join(output, 'logs', 'foundation-product-loop.json'),
    '{"record_type":"foundation_product_loop","status":"PASS"}\n',
    'utf8',
  );
  await writeFile(
    join(output, 'telemetry', 'foundation-product-loop.json'),
    '{"record_type":"foundation_product_loop_metrics","status":"PASS"}\n',
    'utf8',
  );
  const calls = [];
  const run = (command, args, options) => {
    calls.push({ command, args: [...args], env: options.env });
    return {
      command,
      args: [...args],
      exitCode: 0,
      signal: null,
      errorCode: null,
      stdout: '',
      stderr: '',
      durationMs: 1,
    };
  };

  const result = await runFoundationDemo({
    repository: root,
    outputDirectory: output,
    run,
    now: new Date('2026-07-26T00:00:00Z'),
  });

  assert.equal(result.status, 'FAIL');
  assert.equal(result.reason_code, 'ASSERTION_FAILED');
  await validateCheckEvidence(result);
  assert.deepEqual(calls.map((call) => call.args), [
    ['scripts/local-up.mjs'],
    ['scripts/local-up.mjs', '--run-temporal-it'],
    ['scripts/local-up.mjs', '--finalize'],
    [
      '-classpath',
      'gradle/wrapper/gradle-wrapper.jar',
      'org.gradle.wrapper.GradleWrapperMain',
      ':tests:integration:test',
      '--tests',
      '*FoundationProductLoopIT',
      '--no-daemon',
      '--dependency-verification=strict',
    ],
    [
      'compose', '--env-file', 'infra/local/state/images.env',
      '-f', 'infra/local/compose.yaml', '-p', 'accord-foundation-local',
      'logs', '--no-color', '--timestamps',
      'temporal-schema', 'temporal-server', 'temporal-namespace',
    ],
    ['scripts/local-down.mjs', '--purge-local-state'],
  ]);
  const generatedSecrets = [
    'ACCORD_POSTGRES_SUPERUSER_PASSWORD',
    'ACCORD_TEMPORAL_SCHEMA_PASSWORD',
    'ACCORD_TEMPORAL_RUNTIME_PASSWORD',
    'ACCORD_MINIO_ROOT_PASSWORD',
    'ACCORD_MINIO_NORMAL_PASSWORD',
    'ACCORD_MINIO_SCANNER_PASSWORD',
  ].map((name) => calls[0].env[name]);
  assert.equal(new Set(generatedSecrets).size, generatedSecrets.length);
  assert.ok(generatedSecrets.every((value) => /^[A-Za-z0-9_-]{43}$/u.test(value)));
  assert.equal(calls[0].env.ACCORD_FOUNDATION_EVIDENCE_DIR, resolve(output));
  assert.match(calls[0].env.ACCORD_FT17_WEBHOOK_HMAC_SECRET, /^[A-Za-z0-9+/]{43}=$/u);
  assert.match(calls[0].env.ACCORD_FT17_DEMO_CERTIFICATE, /^[A-Za-z0-9_-]{43}$/u);
  assert.notEqual(
    calls[0].env.ACCORD_FT17_WEBHOOK_HMAC_SECRET,
    calls[0].env.ACCORD_FT17_DEMO_CERTIFICATE,
  );
  const stored = await readFile(join(output, 'demo-evidence.json'), 'utf8');
  for (const secret of [
    ...generatedSecrets,
    calls[0].env.ACCORD_FT17_WEBHOOK_HMAC_SECRET,
    calls[0].env.ACCORD_FT17_DEMO_CERTIFICATE,
  ]) assert.doesNotMatch(stored, new RegExp(secret, 'u'));
});

test('collector proof requires current-run worker recovery trace, metric, and log', async () => {
  const demo = await import('../../scripts/acceptance/run-foundation-demo.mjs');
  assert.equal(typeof demo.validateCollectorExport, 'function');
  const withoutWorker = collectorExport({ includeWorker: false });
  assert.throws(
    () => demo.validateCollectorExport(withoutWorker),
    (error) => error?.code === 'COLLECTOR_SIGNAL_MISSING',
  );
  assert.deepEqual(demo.validateCollectorExport(collectorExport({ includeWorker: true })), {
    traces: true,
    metrics: true,
    logs: true,
  });
});

test('collector proof rejects worker recovery outside the guarded scope or typed dimensions', async () => {
  const demo = await import('../../scripts/acceptance/run-foundation-demo.mjs');
  const wrongScope = JSON.parse(collectorExport({ includeWorker: true }).toString('utf8'));
  for (const signal of ['resourceSpans', 'resourceMetrics', 'resourceLogs']) {
    const worker = wrongScope[signal].find((entry) => (
      entry.resource.attributes[0].value.stringValue === 'accord-control-worker'
    ));
    const scopes = worker[{
      resourceSpans: 'scopeSpans',
      resourceMetrics: 'scopeMetrics',
      resourceLogs: 'scopeLogs',
    }[signal]];
    scopes[0].scope.name = 'accord.foundation.ft17';
  }
  assert.throws(
    () => demo.validateCollectorExport(jsonLine(wrongScope)),
    (error) => error?.code === 'COLLECTOR_WORKER_RECOVERY_MISSING',
  );

  const missingDimensions = JSON.parse(
    collectorExport({ includeWorker: true }).toString('utf8'),
  );
  const workerSpans = missingDimensions.resourceSpans.find((entry) => (
    entry.resource.attributes[0].value.stringValue === 'accord-control-worker'
  ));
  workerSpans.scopeSpans[0].spans[0].attributes = [];
  assert.throws(
    () => demo.validateCollectorExport(jsonLine(missingDimensions)),
    (error) => error?.code === 'COLLECTOR_WORKER_RECOVERY_MISSING',
  );

  const missingMetricDimensions = JSON.parse(
    collectorExport({ includeWorker: true }).toString('utf8'),
  );
  const workerMetrics = missingMetricDimensions.resourceMetrics.find((entry) => (
    entry.resource.attributes[0].value.stringValue === 'accord-control-worker'
  ));
  workerMetrics.scopeMetrics[0].metrics[0].sum.dataPoints[0].attributes = [];
  assert.throws(
    () => demo.validateCollectorExport(jsonLine(missingMetricDimensions)),
    (error) => error?.code === 'COLLECTOR_WORKER_RECOVERY_MISSING',
  );

  const missingLogDimensions = JSON.parse(
    collectorExport({ includeWorker: true }).toString('utf8'),
  );
  const workerLogs = missingLogDimensions.resourceLogs.find((entry) => (
    entry.resource.attributes[0].value.stringValue === 'accord-control-worker'
  ));
  workerLogs.scopeLogs[0].logRecords[0].attributes = [];
  assert.throws(
    () => demo.validateCollectorExport(jsonLine(missingLogDimensions)),
    (error) => error?.code === 'COLLECTOR_WORKER_RECOVERY_MISSING',
  );
});

test('demo harness uses distinct owned JVM workers and a forced first-process cut', async () => {
  const integration = await readFile(
    resolve('tests/integration/src/test/java/com/inforvans/accord/integration/FoundationProductLoopIT.java'),
    'utf8',
  );
  const child = await readFile(
    resolve('tests/integration/src/test/java/com/inforvans/accord/integration/FoundationWorkerProcessMain.java'),
    'utf8',
  ).catch(() => '');
  assert.doesNotMatch(integration, /new TemporalWorkerLifecycle\s*\(/u);
  assert.doesNotMatch(integration, /CompletableFuture\.supplyAsync/u);
  assert.match(integration, /new ProcessBuilder\s*\(/u);
  assert.match(integration, /destroyForcibly\s*\(/u);
  assert.match(integration, /waitFor\s*\(/u);
  assert.match(integration, /newSingleThreadExecutor/u);
  assert.match(child, /new TemporalWorkerLifecycle\s*\(/u);
  assert.match(child, /getBean\(AccordWorkflowTelemetry\.class\)/u);
  assert.match(
    child,
    /new FencedReconciliationObservation\([\s\S]{0,400}workflowTelemetry\)/u,
  );
  assert.match(child, /new ProcessObservationPort\(values\)/u);
  assert.doesNotMatch(child, /telemetry\.recordProviderReconciliationSuccess\(\)/u);
});

test('demo preserves startup exit 2 as BLOCKED and still tears down', async (context) => {
  const root = await createDemoRepository('accord-demo-blocked-');
  context.after(() => rm(root, { recursive: true, force: true }));
  const calls = [];
  const result = await runFoundationDemo({
    repository: root,
    outputDirectory: join(root, 'external-evidence'),
    remoteSha: REMOTE_SHA,
    treeSha: TREE_SHA,
    run: (command, args) => {
      calls.push([command, ...args]);
      return commandResult(args[0] === 'scripts/local-up.mjs' && args.length === 1 ? 2 : 0);
    },
    now: new Date('2026-07-26T00:00:00Z'),
  });

  assert.equal(result.status, 'BLOCKED');
  assert.equal(result.reason_code, 'BLOCKED_TOOLCHAIN');
  assert.deepEqual(calls.map((entry) => entry.slice(1)), [
    ['scripts/local-up.mjs'],
    [
      'compose', '--env-file', 'infra/local/state/images.env',
      '-f', 'infra/local/compose.yaml', '-p', 'accord-foundation-local',
      'logs', '--no-color', '--timestamps',
      'temporal-schema', 'temporal-server', 'temporal-namespace',
    ],
    ['scripts/local-down.mjs', '--purge-local-state'],
  ]);
  await validateCheckEvidence(result);
});

test('demo normalizes a thrown startup runner and still tears down', async (context) => {
  const root = await createDemoRepository('accord-demo-thrown-');
  context.after(() => rm(root, { recursive: true, force: true }));
  const calls = [];
  const result = await runFoundationDemo({
    repository: root,
    outputDirectory: join(root, 'external-evidence'),
    remoteSha: REMOTE_SHA,
    treeSha: TREE_SHA,
    run: (command, args) => {
      calls.push([command, ...args]);
      if (args[0] === 'scripts/local-up.mjs') throw new Error('runner failure must not enter evidence');
      return commandResult(0);
    },
    now: new Date('2026-07-26T00:00:00Z'),
  });

  assert.equal(result.status, 'FAIL');
  assert.deepEqual(calls.map((entry) => entry.slice(1)), [
    ['scripts/local-up.mjs'],
    [
      'compose', '--env-file', 'infra/local/state/images.env',
      '-f', 'infra/local/compose.yaml', '-p', 'accord-foundation-local',
      'logs', '--no-color', '--timestamps',
      'temporal-schema', 'temporal-server', 'temporal-namespace',
    ],
    ['scripts/local-down.mjs', '--purge-local-state'],
  ]);
  await validateCheckEvidence(result);
  assert.doesNotMatch(
    await readFile(join(root, 'external-evidence', 'demo-evidence.json'), 'utf8'),
    /runner failure/u,
  );
});

test('sensitive scanner reports only service, rule, and count without matched material', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-sensitive-log-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const sentinel = 'sentinel-value-123456789';
  await writeFile(join(root, 'safe.jsonl'), '{"operation":"reconcile","result":"ok"}\n', 'utf8');
  assert.equal((await verifySensitiveLogs({
    services: [{ service: 'worker', path: root }],
    sentinels: [sentinel],
  })).outcome, 'PASS');
  await writeFile(join(root, 'unsafe.jsonl'), `authorization: Bearer abcdefghijklmnop\n${sentinel}\n`, 'utf8');
  const result = await verifySensitiveLogs({
    services: [{ service: 'worker', path: root }],
    sentinels: [sentinel],
  });
  assert.equal(result.outcome, 'FAIL');
  assert.ok(result.matches.some((entry) => entry.rule_id === 'AUTHORIZATION_HEADER'));
  assert.ok(result.matches.some((entry) => entry.rule_id === 'SEEDED_SENTINEL'));
  assert.doesNotMatch(JSON.stringify(result), /abcdefghijklmnop|sentinel-value/u);
  assert.deepEqual(scanSensitiveText('clean operation', [sentinel]), []);
});

test('sensitive scanner includes ephemeral process output without persisting raw matches', async () => {
  const sentinel = 'node-owned-webhook-sentinel-123456789';
  const result = await verifySensitiveLogs({
    services: [],
    texts: [{ service: 'temporal-worker-output', text: `worker output ${sentinel}\n` }],
    sentinels: [sentinel],
  });
  assert.equal(result.outcome, 'FAIL');
  assert.equal(result.files_scanned, 0);
  assert.deepEqual(result.matches, [{
    service: 'temporal-worker-output',
    rule_id: 'SEEDED_SENTINEL',
    count: 1,
  }]);
  assert.doesNotMatch(JSON.stringify(result), /node-owned-webhook-sentinel/u);
});

test('topology output capture and teardown are independently attempted', async (context) => {
  const root = await createDemoRepository('accord-demo-cleanup-');
  context.after(() => rm(root, { recursive: true, force: true }));
  const calls = [];
  const result = await runFoundationDemo({
    repository: root,
    outputDirectory: join(root, 'external-evidence'),
    remoteSha: REMOTE_SHA,
    treeSha: TREE_SHA,
    run: (command, args) => {
      calls.push([command, ...args]);
      const isLogs = command === 'docker' && args.includes('logs');
      const isDown = args[0] === 'scripts/local-down.mjs';
      return commandResult(isLogs || isDown ? 1 : 0);
    },
    now: new Date('2026-07-26T00:00:00Z'),
  });
  assert.equal(result.status, 'FAIL');
  assert.ok(calls.some(([command, ...args]) => command === 'docker' && args.includes('logs')));
  assert.ok(calls.some(([, ...args]) => args[0] === 'scripts/local-down.mjs'));
});

test('missing external authorities yield named independent BLOCKED receipts', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-environment-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const code = JSON.parse(await readFile(resolve('contracts/golden-fixtures/acceptance/code-pass.json'), 'utf8'));
  const codePath = join(root, 'code.json');
  const outputPath = join(root, 'environment.json');
  await writeFile(codePath, JSON.stringify(code), 'utf8');
  const verdict = await collectEnvironmentEvidence({
    codeVerdictPath: codePath,
    receiptsDirectory: join(root, 'absent-receipts'),
    outputPath,
    now: new Date('2026-07-26T00:00:10Z'),
  });
  assert.equal(verdict.status, 'BLOCKED');
  assert.equal(verdict.checks.length, 12);
  assert.equal(new Set(verdict.checks.map((entry) => entry.reason_code)).size, 12);
  assert.ok(verdict.checks.every((entry) => (
    entry.status === 'BLOCKED'
    && entry.attempt_evidence.length === 0
    && !Object.hasOwn(entry, 'stdout')
    && !Object.hasOwn(entry, 'stderr')
  )));
});

test('malformed receipt fails before a missing pinned trust root can block signature verification', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'accord-environment-malformed-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const receipts = join(root, 'receipts');
  await mkdir(receipts);
  await writeFile(join(receipts, '01.branch-protection.receipt.json'), '{broken', 'utf8');
  const code = JSON.parse(await readFile(resolve('contracts/golden-fixtures/acceptance/code-pass.json'), 'utf8'));
  const codePath = join(root, 'code.json');
  await writeFile(codePath, JSON.stringify(code), 'utf8');
  const verdict = await collectEnvironmentEvidence({
    codeVerdictPath: codePath,
    receiptsDirectory: receipts,
    outputPath: join(root, 'environment.json'),
    now: new Date('2026-07-26T00:00:10Z'),
  });
  const branchProtection = verdict.checks.find(
    (entry) => entry.check_id === '01.branch-protection',
  );
  assert.equal(branchProtection.status, 'FAIL');
  assert.equal(branchProtection.reason_code, 'ASSERTION_FAILED');
});

test('external receipt PASS requires an Ed25519 signature, exact binding, and bounded validity', async () => {
  const code = JSON.parse(await readFile(resolve('contracts/golden-fixtures/acceptance/code-pass.json'), 'utf8'));
  const spec = {
    checkId: '01.branch-protection',
    capability: 'authoritative-branch-protection',
    authority: 'accord-source-host',
    evidenceType: 'branch-protection-receipt-v1',
  };
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const receipt = {
    schema_version: '1.0.0',
    check_id: spec.checkId,
    remote_sha: code.remote_sha,
    tree_sha: code.tree_sha,
    image_lock_digest: code.image_lock_digest,
    release_manifest_digest: code.release_manifest_digest,
    evidence_policy_version: code.evidence_policy_version,
    capability: spec.capability,
    authority: spec.authority,
    evidence_type: spec.evidenceType,
    issued_at: '2026-07-26T00:00:00Z',
    expires_at: '2026-07-26T01:00:00Z',
    evidence_digest: '8'.repeat(64),
    signing: {
      algorithm: 'Ed25519',
      key_id: 'production-evidence-1',
    },
  };
  receipt.signing.signature = sign(null, Buffer.from(canonicalJson(receipt)), privateKey).toString('base64');
  const trustStore = {
    schema_version: '1.0.0',
    keys: [{
      authority: spec.authority,
      key_id: receipt.signing.key_id,
      algorithm: 'Ed25519',
      public_key_pem: publicKey.export({ type: 'spki', format: 'pem' }),
    }],
  };
  assert.equal(verifyReceipt({
    receipt,
    receiptDigest: '7'.repeat(64),
    spec,
    code,
    trustStore,
    now: new Date('2026-07-26T00:30:00Z'),
  }), true);
  const changed = structuredClone(receipt);
  changed.tree_sha = '9'.repeat(40);
  assert.throws(() => verifyReceipt({
    receipt: changed,
    receiptDigest: '7'.repeat(64),
    spec,
    code,
    trustStore,
    now: new Date('2026-07-26T00:30:00Z'),
  }));
});

async function createDemoRepository(prefix, excluded = new Set()) {
  const root = await mkdtemp(join(tmpdir(), prefix));
  for (const relativePath of REQUIRED_DEMO_FILES) {
    if (excluded.has(relativePath)) continue;
    const path = join(root, relativePath);
    await mkdir(join(path, '..'), { recursive: true });
    await writeFile(path, 'fixture', 'utf8');
  }
  return root;
}

function commandResult(exitCode) {
  return {
    exitCode,
    errorCode: null,
    stdout: '',
    stderr: '',
    durationMs: 1,
  };
}

function collectorExport({ includeWorker }) {
  const resource = (service, spans, metrics, logs, scopeName) => ({
    resource: { attributes: [{ key: 'service.name', value: { stringValue: service } }] },
    scopeSpans: [{ scope: scopeName ? { name: scopeName } : undefined, spans }],
    scopeMetrics: [{ scope: scopeName ? { name: scopeName } : undefined, metrics }],
    scopeLogs: [{ scope: scopeName ? { name: scopeName } : undefined, logRecords: logs }],
  });
  const httpSpan = (route, status) => ({
    attributes: [
      { key: 'http.route', value: { stringValue: route } },
      { key: 'http.response.status_code', value: { intValue: status } },
    ],
  });
  const services = [
    resource('accord-webhook-edge', [
      httpSpan('/webhooks/gitlab/{bindingId}', 202),
      httpSpan('/webhooks/gitlab/{bindingId}', 202),
      httpSpan('/webhooks/gitlab/{bindingId}', 409),
    ], [{ name: 'http.server.requests' }], [{ body: { stringValue: 'request completed' } }]),
    resource('accord-control-api', [
      httpSpan('/v1/contract-validations/{validationId}', 201),
      httpSpan('/v1/contract-validations/{validationId}', 201),
    ], [{ name: 'http.server.requests' }], [{ body: { stringValue: 'request completed' } }]),
  ];
  if (includeWorker) {
    const dimensions = [
      { key: 'accord.operation', value: { stringValue: 'provider_reconciliation' } },
      { key: 'accord.provider', value: { stringValue: 'gitlab' } },
      { key: 'accord.result_code', value: { stringValue: 'success' } },
    ];
    services.push(resource(
      'accord-control-worker',
      [{ name: 'provider_reconciliation', attributes: dimensions }],
      [{
        name: 'accord.workflow.executions',
        sum: { dataPoints: [{ attributes: dimensions }] },
      }],
      [{
        body: { stringValue: 'foundation recovery completed' },
        attributes: dimensions,
      }],
      'com.inforvans.accord.foundation',
    ));
  }
  const payload = {
    resourceSpans: services.map(({ resource: value, scopeSpans }) => ({
      resource: value,
      scopeSpans,
    })),
    resourceMetrics: services.map(({ resource: value, scopeMetrics }) => ({
      resource: value,
      scopeMetrics,
    })),
    resourceLogs: services.map(({ resource: value, scopeLogs }) => ({
      resource: value,
      scopeLogs,
    })),
  };
  return jsonLine(payload);
}

function jsonLine(payload) {
  return Buffer.from(`${JSON.stringify(payload)}\n`, 'utf8');
}
