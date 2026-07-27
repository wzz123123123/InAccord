import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readFile, mkdtemp, rm, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

import {
  assertDeploymentBindings,
  deriveProductionPromotion,
  renderValues,
  validateAuditedCidrs,
} from '../../scripts/deployment/render-values.mjs';
import {
  BlockedExternalEnvironmentError,
  renderArgoApplication,
} from '../../scripts/deployment/render-argocd.mjs';
import {
  assertAuthoritativeProductionValues,
  preflightDeploymentToolchain,
  tlsReceiptSigningPayload,
  verifyProductionTlsReceipt,
} from '../../scripts/deployment/verify-deployment.mjs';

const root = path.resolve(import.meta.dirname, '..', '..');
const digest = (character) => `sha256:${character.repeat(64)}`;
const sha = (character) => character.repeat(40);
const sha256 = (bytes) => `sha256:${createHash('sha256').update(bytes).digest('hex')}`;

async function json(relativePath) {
  return JSON.parse(await readFile(path.join(root, relativePath), 'utf8'));
}

function kubernetesRoute(name, port, protocol = 'TCP') {
  return {
    mode: 'KUBERNETES_SERVICE',
    namespace_selector: { key: 'kubernetes.io/metadata.name', value: 'accord-system' },
    pod_selector: { key: 'app.kubernetes.io/name', value: name },
    service_name: `accord-${name}`,
    ports: [{ protocol, port }],
  };
}

function dnsRoute() {
  return {
    mode: 'KUBERNETES_SERVICE',
    namespace_selector: { key: 'kubernetes.io/metadata.name', value: 'kube-system' },
    pod_selector: { key: 'k8s-app', value: 'kube-dns' },
    service_name: 'kube-dns',
    ports: [{ protocol: 'UDP', port: 53 }, { protocol: 'TCP', port: 53 }],
  };
}

function ingressRoute(name) {
  return {
    mode: 'KUBERNETES_SERVICE',
    namespace_selector: { key: 'kubernetes.io/metadata.name', value: 'ingress-system' },
    pod_selector: { key: 'app.kubernetes.io/name', value: name },
    service_name: name,
    ports: [{ protocol: 'TCP', port: 8080 }],
  };
}

function component({
  name,
  serviceAccount,
  providerName,
  loginRole,
  sessionRole,
  repository,
  imageDigest,
  uid,
  databaseName,
  databaseServer,
  otlpServer,
  network,
  worker = false,
}) {
  const targetName = `${serviceAccount}-runtime`;
  const targetKeys = {
    database_password: 'database-password',
    postgres_ca: 'postgres-ca.crt',
    otlp_ca: 'otlp-ca.crt',
  };
  if (worker) {
    Object.assign(targetKeys, {
      temporal_ca: 'temporal-ca.crt',
      temporal_client_certificate: 'temporal-client.crt',
      temporal_client_private_key: 'temporal-client.key',
    });
  }
  if (name === 'webhook-edge') {
    targetKeys.webhook_bindings = 'webhook-bindings.json';
  }
  const value = {
    service_account: serviceAccount,
    identity: {
      provider: 'KUBERNETES',
      provider_name: providerName,
      subject: `system:serviceaccount:accord-system:${serviceAccount}`,
      audience: serviceAccount,
    },
    external_secret: {
      store: { kind: 'ClusterSecretStore', name: `${serviceAccount}-store` },
      remote_ref: `accord/test/${name}/runtime`,
      target_name: targetName,
      target_keys: targetKeys,
    },
    database: {
      database_name: databaseName,
      secret_name: targetName,
      password_key: 'database-password',
      login_role: loginRole,
      session_role: sessionRole,
    },
    image: { repository, digest: imageDigest },
    runtime: { uid, gid: uid },
    resources: {
      requests: { cpu: '100m', memory: '256Mi' },
      limits: { cpu: '1', memory: '512Mi' },
    },
    probes: worker
      ? {
          type: 'EXEC_JAVA',
          java_path: '/usr/bin/java',
          jar: '/opt/accord/bin/worker-probe.jar',
          liveness_argument: 'live',
          readiness_argument: 'ready',
        }
      : {
          type: 'HTTP',
          port_name: 'http',
          liveness_path: '/actuator/health/liveness',
          readiness_path: '/actuator/health/readiness',
        },
    network,
    tls: {
      postgres: {
        mode: 'VERIFY_FULL',
        ca_secret_key: 'postgres-ca.crt',
        server_name: databaseServer,
      },
      otlp: {
        mode: 'TLS',
        protocol: 'HTTPS',
        ca_secret_key: 'otlp-ca.crt',
        server_name: otlpServer,
      },
    },
  };
  if (worker) {
    value.external_secret.temporal_client_certificate_ref = {
      remote_ref: 'accord/test/control-worker/temporal-client',
      certificate_property: 'certificate',
      private_key_property: 'private-key',
    };
    value.tls.temporal = {
      mode: 'MTLS',
      ca_secret_key: 'temporal-ca.crt',
      client_certificate_secret_key: 'temporal-client.crt',
      client_private_key_secret_key: 'temporal-client.key',
      server_name: 'temporal-frontend.accord-system.svc.cluster.local',
      namespace: 'accord-reconciliation-v1',
      task_queue: 'accord-reconciliation-v1',
    };
  }
  return value;
}

function validIdentity() {
  return {
    schema_version: '1.0.0',
    environment_class: 'test',
    namespace: 'accord-system',
    remote_authority: {
      repository_url: 'https://github.com/inforvans/accord.git',
      commit_sha: sha('a'),
      tree_sha: sha('b'),
    },
    release_evidence: {
      release_manifest_digest: digest('c'),
      dsse_envelope_digest: digest('d'),
      verification_receipt_digest: digest('e'),
    },
    components: {
      'control-api': component({
        name: 'control-api',
        serviceAccount: 'accord-control-api',
        providerName: 'accord-test-control-api',
        loginRole: 'accord_api_login',
        sessionRole: 'accord_api',
        repository: 'ghcr.io/inforvans/accord-control-api',
        imageDigest: digest('1'),
        uid: 10001,
        databaseName: 'accord_control',
        databaseServer: 'control-postgres.accord-system.svc.cluster.local',
        otlpServer: 'otel-collector.accord-system.svc.cluster.local',
        network: {
          profile: 'CONTROL_API',
          ingress_gateway: ingressRoute('control-ingress-gateway'),
          destinations: {
            control_postgres: kubernetesRoute('control-postgres', 5432),
            otlp: kubernetesRoute('otel-collector', 4318),
            dns: dnsRoute(),
          },
        },
      }),
      'control-worker': component({
        name: 'control-worker',
        serviceAccount: 'accord-control-worker',
        providerName: 'accord-test-control-worker',
        loginRole: 'accord_worker_login',
        sessionRole: 'accord_worker',
        repository: 'ghcr.io/inforvans/accord-control-worker',
        imageDigest: digest('2'),
        uid: 10002,
        databaseName: 'accord_control',
        databaseServer: 'control-postgres.accord-system.svc.cluster.local',
        otlpServer: 'otel-collector.accord-system.svc.cluster.local',
        worker: true,
        network: {
          profile: 'CONTROL_WORKER',
          destinations: {
            control_postgres: kubernetesRoute('control-postgres', 5432),
            temporal: kubernetesRoute('temporal-frontend', 7233),
            otlp: kubernetesRoute('otel-collector', 4318),
            dns: dnsRoute(),
          },
        },
      }),
      'webhook-edge': component({
        name: 'webhook-edge',
        serviceAccount: 'accord-webhook-edge',
        providerName: 'accord-test-webhook-edge',
        loginRole: 'accord_webhook_runtime_login',
        sessionRole: 'accord_webhook_runtime',
        repository: 'ghcr.io/inforvans/accord-webhook-edge',
        imageDigest: digest('3'),
        uid: 10003,
        databaseName: 'accord_webhook',
        databaseServer: 'webhook-postgres.accord-system.svc.cluster.local',
        otlpServer: 'otel-collector.accord-system.svc.cluster.local',
        network: {
          profile: 'WEBHOOK_EDGE',
          ingress_gateway: ingressRoute('edge-ingress-gateway'),
          destinations: {
            webhook_postgres: kubernetesRoute('webhook-postgres', 5432),
            otlp: kubernetesRoute('otel-collector', 4318),
            dns: dnsRoute(),
          },
        },
      }),
    },
  };
}

function validators(componentSchema, promotionSchema) {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  return {
    component: ajv.compile(componentSchema),
    promotion: ajv.compile(promotionSchema),
  };
}

test('closed schemas bind exactly three independent workload identities and immutable promotion evidence', async () => {
  const [componentSchema, promotionSchema, promotion] = await Promise.all([
    json('contracts/deployment/component-identity.schema.json'),
    json('contracts/deployment/promotion-input.schema.json'),
    json('tests/integration/fixtures/deployment/valid-test-promotion.json'),
  ]);
  const validate = validators(componentSchema, promotionSchema);
  const identity = validIdentity();
  assert.equal(validate.component(identity), true, JSON.stringify(validate.component.errors));
  assert.equal(validate.promotion(promotion), true, JSON.stringify(validate.promotion.errors));
  assert.deepEqual(Object.keys(identity.components), ['control-api', 'control-worker', 'webhook-edge']);
  assertDeploymentBindings(identity, promotion);

  const tuples = Object.values(identity.components).map((entry) => [
    entry.service_account,
    entry.identity.provider_name,
    entry.identity.subject,
    entry.identity.audience,
    entry.external_secret.store.name,
    entry.external_secret.remote_ref,
    entry.external_secret.target_name,
    entry.database.secret_name,
    entry.image.repository,
  ]);
  for (let index = 0; index < tuples[0].length; index += 1) {
    assert.equal(new Set(tuples.map((tuple) => tuple[index])).size, 3);
  }
  assert.deepEqual(
    Object.fromEntries(Object.entries(identity.components).map(([name, entry]) => [name, [entry.database.login_role, entry.database.session_role]])),
    {
      'control-api': ['accord_api_login', 'accord_api'],
      'control-worker': ['accord_worker_login', 'accord_worker'],
      'webhook-edge': ['accord_webhook_runtime_login', 'accord_webhook_runtime'],
    },
  );

  const wrongRole = structuredClone(identity);
  wrongRole.components['control-worker'].database.session_role = 'accord_api';
  assert.equal(validate.component(wrongRole), false);

  const mutableImage = structuredClone(identity);
  mutableImage.components['control-api'].image = { repository: 'ghcr.io/inforvans/accord-control-api', tag: 'latest' };
  assert.equal(validate.component(mutableImage), false);

  const annotations = structuredClone(identity);
  annotations.components['control-api'].identity.annotations = { unsafe: 'free-form' };
  assert.equal(validate.component(annotations), false);

  const production = { ...structuredClone(promotion), environment_class: 'production' };
  assert.equal(validate.promotion(production), false);
  production.signature_verified = true;
  production.scan_policy_passed = true;
  assert.equal(validate.promotion(production), true, JSON.stringify(validate.promotion.errors));
  assert.throws(() => assertDeploymentBindings(identity, production), /environment class/u);
});

test('fixture-directed negatives fail their intended identity, route, and plaintext rules', async () => {
  const [componentSchema, promotionSchema, promotion, sharedCase, broadCase, plaintextCase] = await Promise.all([
    json('contracts/deployment/component-identity.schema.json'),
    json('contracts/deployment/promotion-input.schema.json'),
    json('tests/integration/fixtures/deployment/valid-test-promotion.json'),
    json('tests/integration/fixtures/deployment/invalid-shared-identity.json'),
    json('tests/integration/fixtures/deployment/invalid-broad-egress.json'),
    json('tests/integration/fixtures/deployment/invalid-plaintext-secret.json'),
  ]);
  const validate = validators(componentSchema, promotionSchema);

  const shared = validIdentity();
  for (const field of sharedCase.fields) {
    const source = field.split('.').reduce((value, key) => value[key], shared.components[sharedCase.from]);
    const segments = field.split('.');
    const final = segments.pop();
    const target = segments.reduce((value, key) => value[key], shared.components[sharedCase.to]);
    target[final] = structuredClone(source);
  }
  assert.throws(() => assertDeploymentBindings(shared, promotion), /unique/u);

  const broad = validIdentity();
  broad.components[broadCase.component].network.destinations[broadCase.destination] = broadCase.route;
  assert.equal(validate.component(broad), false);
  assert.throws(() => validateAuditedCidrs(broadCase.route), /default route/u);

  const plaintext = validIdentity();
  plaintext.components[plaintextCase.component].external_secret[plaintextCase.property] = '<runtime-only-plaintext-fixture>';
  assert.equal(validate.component(plaintext), false);
  assert.throws(() => assertDeploymentBindings(plaintext, promotion), /plaintext/u);
  assert.equal(validate.promotion(promotion), true);
});

test('route modes remain closed and application TLS is bound separately from allowed ports', async () => {
  const componentSchema = await json('contracts/deployment/component-identity.schema.json');
  const promotionSchema = await json('contracts/deployment/promotion-input.schema.json');
  const promotion = await json('tests/integration/fixtures/deployment/valid-test-promotion.json');
  const validate = validators(componentSchema, promotionSchema);

  const identity = validIdentity();
  identity.components['control-api'].network.destinations.otlp = {
    mode: 'EGRESS_GATEWAY',
    namespace_selector: { key: 'kubernetes.io/metadata.name', value: 'egress-system' },
    pod_selector: { key: 'app.kubernetes.io/name', value: 'accord-egress-gateway' },
    service_name: 'accord-egress-gateway',
    ports: [{ protocol: 'TCP', port: 4318 }],
  };
  assert.equal(validate.component(identity), true, JSON.stringify(validate.component.errors));

  identity.components['control-api'].network.destinations.otlp = {
    mode: 'CILIUM_FQDN',
    fqdn: 'otel.test.inforvans.com',
    ports: [{ protocol: 'TCP', port: 4318 }],
  };
  assert.equal(validate.component(identity), true, JSON.stringify(validate.component.errors));
  identity.components['control-api'].network.destinations.otlp.fqdn = '*.inforvans.com';
  assert.equal(validate.component(identity), false);

  const tlsFail = validIdentity();
  delete tlsFail.components['control-worker'].tls.temporal.client_certificate_secret_key;
  assert.equal(validate.component(tlsFail), false);
  assert.throws(() => assertDeploymentBindings(tlsFail, promotion), /Temporal mTLS/u);

  const metadata = {
    mode: 'AUDITED_CIDR',
    cidrs: ['169.254.169.254/32'],
    evidence_digest: digest('9'),
    evidence_expires_at: '2099-01-01T00:00:00Z',
    owner: 'platform-network',
    ports: [{ protocol: 'TCP', port: 443 }],
  };
  assert.throws(() => validateAuditedCidrs(metadata), /metadata/u);
  assert.throws(
    () => validateAuditedCidrs({ ...metadata, cidrs: ['fd00::/8'] }),
    /metadata/u,
  );
});

test('production TLS receipts are closed, fresh, signed, and bound to rendered promotion targets', async () => {
  const temporary = await mkdtemp(path.join(tmpdir(), 'accord-ft15-tls-receipt-'));
  try {
    const identity = validIdentity();
    identity.environment_class = 'production';
    const promotion = await json('tests/integration/fixtures/deployment/valid-test-promotion.json');
    promotion.environment_class = 'production';
    promotion.signature_verified = true;
    promotion.scan_policy_passed = true;
    await assert.rejects(
      renderValues({
        identity,
        promotion,
        outputPath: path.join(temporary, 'unauthorized-values.yaml'),
      }),
      /authoritative release-chain verification/u,
    );
    identity.environment_class = 'test';
    promotion.environment_class = 'test';
    promotion.signature_verified = false;
    promotion.scan_policy_passed = false;
    const { rendered } = await renderValues({
      identity,
      promotion,
      outputPath: path.join(temporary, 'values.yaml'),
    });
    rendered.environment_class = 'production';
    const { publicKey, privateKey } = generateKeyPairSync('ed25519');
    const receipt = {
      schema_version: '1.0.0',
      receipt_kind: 'POSTGRES_VERIFY_FULL',
      status: 'PASS',
      authority: 'production-pki',
      environment_class: 'production',
      remote_sha: rendered.contract.remote_sha,
      remote_tree_sha: rendered.contract.remote_tree_sha,
      release_manifest_digest: rendered.contract.release_manifest_digest,
      promotion_sha256: rendered.contract.promotion_sha256,
      targets: Object.entries(rendered.components)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([componentName, componentValue]) => ({
          component: componentName,
          server_name: componentValue.tls.postgres.server_name,
          tls_mode: 'VERIFY_FULL',
          protocol: 'TLSv1.3',
          status: 'PASS',
          peer_certificate_sha256: digest('4'),
          client_certificate_sha256: null,
          handshake_evidence_digest: digest('5'),
        })),
      issued_at: '2026-07-27T00:00:00Z',
      expires_at: '2026-07-27T01:00:00Z',
      evidence_digest: digest('6'),
      signing: {
        algorithm: 'Ed25519',
        key_id: 'production-pki-1',
        signature: '',
      },
    };
    receipt.signing.signature = sign(
      null,
      tlsReceiptSigningPayload(receipt),
      privateKey,
    ).toString('base64');
    const trustStore = {
      schema_version: '1.0.0',
      keys: [{
        authority: 'production-pki',
        key_id: 'production-pki-1',
        algorithm: 'Ed25519',
        public_key_pem: publicKey.export({ type: 'spki', format: 'pem' }),
      }],
    };
    await assert.doesNotReject(verifyProductionTlsReceipt({
      receipt,
      receiptKind: 'POSTGRES_VERIFY_FULL',
      values: rendered,
      trustStore,
      now: new Date('2026-07-27T00:30:00Z'),
    }));

    await assert.rejects(verifyProductionTlsReceipt({
      receipt: { ...receipt, remote_sha: sha('f') },
      receiptKind: 'POSTGRES_VERIFY_FULL',
      values: rendered,
      trustStore,
      now: new Date('2026-07-27T00:30:00Z'),
    }));
    await assert.rejects(verifyProductionTlsReceipt({
      receipt: { ...receipt, unexpected: true },
      receiptKind: 'POSTGRES_VERIFY_FULL',
      values: rendered,
      trustStore,
      now: new Date('2026-07-27T00:30:00Z'),
    }));
    await assert.rejects(verifyProductionTlsReceipt({
      receipt,
      receiptKind: 'POSTGRES_VERIFY_FULL',
      values: rendered,
      trustStore,
      now: new Date('2026-07-28T00:30:00Z'),
    }));
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test('production promotion is derived only from the pinned Accord release verifier output', () => {
  const identity = validIdentity();
  identity.environment_class = 'production';
  const context = {
    environment_class: 'production',
    remote_url: 'https://github.com/inforvans/accord.git',
    remote_ref: 'refs/heads/main',
    remote_sha: '0123456789abcdef0123456789abcdef01234567',
    tree_sha: '89abcdef0123456789abcdef0123456789abcdef',
    builder_issuer: 'https://token.actions.githubusercontent.com',
    builder_subject: 'repo:inforvans/accord:environment:accord-release',
    builder_audience: 'sigstore',
    protected_environment: 'accord-release',
  };
  identity.remote_authority.repository_url = context.remote_url;
  identity.remote_authority.commit_sha = context.remote_sha;
  identity.remote_authority.tree_sha = context.tree_sha;

  const manifestBytes = Buffer.from('verified release manifest bytes', 'utf8');
  const verificationBytes = Buffer.from('verified release receipt bytes', 'utf8');
  const sourceEnvelope = digest('d');
  const manifest = {
    remote_sha: context.remote_sha,
    tree_sha: context.tree_sha,
    generated_at: '2026-07-27T00:00:00Z',
    artifacts: [
      {
        name: 'accord-source',
        kind: 'source',
        platform: 'none',
        provenance: { envelope_sha256: sourceEnvelope },
      },
      ...[
        ['control-api', 'control-plane-api'],
        ['control-worker', 'control-plane-worker'],
        ['webhook-edge', 'webhook-edge'],
      ].map(([componentName, artifactName]) => {
        const image = identity.components[componentName].image;
        return {
          name: artifactName,
          kind: 'image',
          platform: 'linux/amd64',
          digest: image.digest,
          immutable_locator: `${image.repository}@${image.digest}`,
        };
      }),
    ],
  };
  const verification = {
    schema_version: '1.0.0',
    remote_sha: context.remote_sha,
    tree_sha: context.tree_sha,
    release_manifest_sha256: sha256(manifestBytes),
    artifacts: manifest.artifacts.map(({ name, platform, immutable_locator }) => ({
      name,
      platform,
      immutable_locator,
    })),
  };
  identity.release_evidence.release_manifest_digest = sha256(manifestBytes);
  identity.release_evidence.dsse_envelope_digest = sourceEnvelope;
  identity.release_evidence.verification_receipt_digest = sha256(verificationBytes);

  const promotion = deriveProductionPromotion({
    identity,
    context,
    manifest,
    manifestBytes,
    verification,
    verificationBytes,
  });
  assert.equal(promotion.signature_verified, true);
  assert.equal(promotion.scan_policy_passed, true);
  assert.equal(promotion.verification_receipt_digest, sha256(verificationBytes));
  assert.deepEqual(promotion.images, Object.fromEntries(
    Object.entries(identity.components).map(([name, value]) => [name, value.image]),
  ));

  assert.throws(
    () => deriveProductionPromotion({
      identity,
      context: { ...context, builder_subject: 'repo:attacker/accord:environment:accord-release' },
      manifest,
      manifestBytes,
      verification,
      verificationBytes,
    }),
    /pinned Accord release identity/u,
  );
});

test('production deployment CLI cannot replace the repository-pinned TLS trust root', async () => {
  const source = await readFile('scripts/deployment/verify-deployment.mjs', 'utf8');
  assert.doesNotMatch(source, /--tls-trust-store/u);
  assert.match(source, /contracts[\s\S]*deployment[\s\S]*production-trust-store[.]json/u);
  assert.match(source, /renderProductionValues/u);
  assert.match(source, /writeFile\(valuesSnapshot, valuesBytes/u);
  assert.match(source, /'--values', valuesSnapshot/u);
  const execution = spawnSync(process.execPath, [
    'scripts/deployment/verify-deployment.mjs',
    '--tls-trust-store',
    'attacker-controlled.json',
  ], {
    cwd: root,
    encoding: 'utf8',
    shell: false,
    windowsHide: true,
  });
  assert.equal(execution.status, 1);
  assert.match(execution.stderr, /Unknown option: --tls-trust-store/u);
});

test('production deployment requires byte-identical values from the authoritative renderer', () => {
  const authoritative = Buffer.from('schema_version: 1.0.0\nenvironment_class: production\n', 'utf8');
  assert.doesNotThrow(() => assertAuthoritativeProductionValues(authoritative, authoritative));
  assert.throws(
    () => assertAuthoritativeProductionValues(
      Buffer.from(`${authoritative.toString('utf8')}components: { control-api: tampered }\n`, 'utf8'),
      authoritative,
    ),
    /authoritative production rendering/u,
  );
});

test('renderer cross-binds both inputs and emits secret-free digest-pinned values atomically', async () => {
  const identity = validIdentity();
  const promotion = await json('tests/integration/fixtures/deployment/valid-test-promotion.json');
  const temporary = await mkdtemp(path.join(tmpdir(), 'accord-ft15-'));
  try {
    const output = path.join(temporary, 'rendered-values.yaml');
    const result = await renderValues({ identity, promotion, outputPath: output });
    const rendered = YAML.parse(await readFile(output, 'utf8'));
    assert.equal(result.outputPath, output);
    assert.match(rendered.contract.identity_sha256, /^sha256:[0-9a-f]{64}$/u);
    assert.match(rendered.contract.promotion_sha256, /^sha256:[0-9a-f]{64}$/u);
    for (const [name, entry] of Object.entries(rendered.components)) {
      assert.equal(entry.image.reference, `${promotion.images[name].repository}@${promotion.images[name].digest}`);
      assert.equal(entry.service_account, identity.components[name].service_account);
      assert.equal(entry.database.secret_name, identity.components[name].database.secret_name);
    }
    const bytes = await readFile(output, 'utf8');
    assert.doesNotMatch(bytes, /^\s*(?:stringData|password|credentials?|token|access_key|secret_key|client_secret|private_key):/imu);
    const valuesSchema = await json('infra/helm/accord/values.schema.json');
    const valuesAjv = new Ajv2020({ allErrors: true, strict: true });
    addFormats(valuesAjv);
    const validateValues = valuesAjv.compile(valuesSchema);
    assert.equal(validateValues(rendered), true, JSON.stringify(validateValues.errors));

    const mismatch = structuredClone(promotion);
    mismatch.remote_sha = sha('f');
    await assert.rejects(renderValues({ identity, promotion: mismatch, outputPath: output }), /remote SHA/u);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }

  const defaults = YAML.parse(await readFile(path.join(root, 'infra/helm/accord/values.yaml'), 'utf8')) ?? {};
  assert.equal(JSON.stringify(defaults).includes('image'), false);
});

test('Helm sources render isolated service accounts, external secrets, hardened workloads, and exact network policies', async () => {
  const files = Object.fromEntries(await Promise.all([
    '_helpers.tpl', 'serviceaccounts.yaml', 'externalsecrets.yaml', 'workloads.yaml', 'services.yaml',
    'networkpolicies.yaml', 'cilium-networkpolicies.yaml', 'poddisruptionbudgets.yaml',
  ].map(async (name) => [name, await readFile(path.join(root, 'infra/helm/accord/templates', name), 'utf8')])));

  assert.match(files['serviceaccounts.yaml'], /automountServiceAccountToken:\s*false/u);
  assert.match(files['serviceaccounts.yaml'], /eks[.]amazonaws[.]com\/role-arn/u);
  assert.match(files['serviceaccounts.yaml'], /iam[.]gke[.]io\/gcp-service-account/u);
  assert.match(files['serviceaccounts.yaml'], /azure[.]workload[.]identity\/client-id/u);
  assert.match(files['externalsecrets.yaml'], /kind:\s*ExternalSecret/u);
  assert.doesNotMatch(files['externalsecrets.yaml'], /^kind:\s*Secret$/mu);
  assert.match(files['workloads.yaml'], /automountServiceAccountToken:\s*false/u);
  assert.match(files['workloads.yaml'], /name:\s*ACCORD_DEPLOYMENT_ENVIRONMENT/u);
  assert.match(files['workloads.yaml'], /name:\s*ACCORD_SERVICE_VERSION/u);
  assert.match(files['workloads.yaml'], /name:\s*SPRING_DATASOURCE_URL/u);
  assert.match(files['workloads.yaml'], /sslmode=verify-full/u);
  assert.match(files['workloads.yaml'], /name:\s*ACCORD_PROCESS_INSTANCE_ID/u);
  assert.match(files['workloads.yaml'], /fieldPath:\s*metadata[.]uid/u);
  assert.match(files['workloads.yaml'], /name:\s*ACCORD_TEMPORAL_RECONCILIATION_TASK_QUEUE/u);
  assert.match(files['workloads.yaml'], /name:\s*ACCORD_WEBHOOK_EDGE_BINDINGS_FILE/u);
  assert.match(files['externalsecrets.yaml'], /property:\s*webhook-bindings/u);
  assert.match(files['workloads.yaml'], /readOnlyRootFilesystem:\s*true/u);
  assert.match(files['workloads.yaml'], /runAsNonRoot:\s*true/u);
  assert.match(files['workloads.yaml'], /seccompProfile:/u);
  assert.match(files['workloads.yaml'], /allowPrivilegeEscalation:\s*false/u);
  assert.match(files['workloads.yaml'], /drop:\s*\[?\s*ALL/u);
  assert.match(files['workloads.yaml'], /medium:\s*Memory/u);
  assert.match(files['workloads.yaml'], /\$component[.]probes[.]jar/u);
  assert.doesNotMatch(files['workloads.yaml'], /(?:\/bin\/sh|sh\s+-c|bash)/u);
  assert.match(files['workloads.yaml'], /topologySpreadConstraints/u);
  assert.match(files['workloads.yaml'], /podAntiAffinity/u);
  assert.match(files['networkpolicies.yaml'], /policyTypes:\s*\n\s*- Ingress\s*\n\s*- Egress/u);
  assert.match(files['networkpolicies.yaml'], /namespaceSelector:/u);
  assert.match(files['networkpolicies.yaml'], /podSelector:/u);
  assert.doesNotMatch(files['networkpolicies.yaml'], /CILIUM_FQDN/u);
  assert.match(files['cilium-networkpolicies.yaml'], /kind:\s*CiliumNetworkPolicy/u);
  assert.match(files['cilium-networkpolicies.yaml'], /matchName:/u);
  assert.match(files['poddisruptionbudgets.yaml'], /minAvailable:\s*1/u);
});

test('policy and OpenTofu keep contract validation separate from the real local adapter', async () => {
  const [policy, moduleVariables, moduleChecks, moduleOutputs, versions, main, outputs, tofuTests] = await Promise.all([
    readFile(path.join(root, 'infra/policy/accord.rego'), 'utf8'),
    readFile(path.join(root, 'infra/opentofu/modules/accord-foundation-contract/variables.tf'), 'utf8'),
    readFile(path.join(root, 'infra/opentofu/modules/accord-foundation-contract/checks.tf'), 'utf8'),
    readFile(path.join(root, 'infra/opentofu/modules/accord-foundation-contract/outputs.tf'), 'utf8'),
    readFile(path.join(root, 'infra/opentofu/environments/local-kubernetes/versions.tf'), 'utf8'),
    readFile(path.join(root, 'infra/opentofu/environments/local-kubernetes/main.tf'), 'utf8'),
    readFile(path.join(root, 'infra/opentofu/environments/local-kubernetes/outputs.tf'), 'utf8'),
    readFile(path.join(root, 'infra/opentofu/environments/local-kubernetes/tests/local-kubernetes.tftest.hcl'), 'utf8'),
  ]);
  assert.doesNotMatch(`${moduleVariables}\n${moduleChecks}\n${moduleOutputs}`, /\b(?:provider|resource)\s+"/u);
  assert.match(moduleChecks, /accord_webhook_runtime_login/u);
  assert.match(moduleChecks, /VERIFY_FULL/u);
  assert.match(moduleChecks, /MTLS/u);
  assert.doesNotMatch(moduleOutputs, /sensitive\s*=\s*true/u);
  assert.match(versions, /required_version\s*=\s*"= 1[.]9[.]1"/u);
  assert.match(versions, /hashicorp\/kubernetes/u);
  assert.match(versions, /hashicorp\/helm/u);
  assert.match(main, /resource\s+"kubernetes_namespace_v1"\s+"accord"/u);
  assert.match(main, /resource\s+"helm_release"\s+"accord"/u);
  assert.match(main, /values\s*=\s*\[file\(var[.]rendered_values_path\)\]/u);
  assert.doesNotMatch(outputs, /password|credential|secret_value/iu);
  assert.match(tofuTests, /mock_provider\s+"kubernetes"/u);
  assert.match(tofuTests, /mock_provider\s+"helm"/u);
  assert.match(tofuTests, /expect_failures/u);
  assert.match(policy, /automountServiceAccountToken/u);
  assert.match(policy, /readOnlyRootFilesystem/u);
  assert.match(policy, /0[.]0[.]0[.]0\/0/u);
  assert.match(policy, /169[.]254[.]169[.]254/u);
  assert.match(policy, /TLS evidence/u);
});

test('Argo generator accepts only a remote-authoritative lowercase SHA and never writes on BLOCKED', async () => {
  const promotion = await json('tests/integration/fixtures/deployment/valid-test-promotion.json');
  const temporary = await mkdtemp(path.join(tmpdir(), 'accord-argo-'));
  const output = path.join(temporary, 'application.yaml');
  const calls = [];
  const success = (command, args, options) => {
    calls.push({ command, args, options });
    return { status: 0, stdout: `${promotion.remote_sha}\t${promotion.remote_ref}\n`, stderr: '' };
  };
  try {
    const application = await renderArgoApplication({
      remoteUrl: promotion.remote_url,
      remoteRef: promotion.remote_ref,
      remoteSha: promotion.remote_sha,
      promotion,
      outputPath: output,
      spawnSyncImpl: success,
    });
    assert.deepEqual(calls[0].args, ['ls-remote', '--exit-code', promotion.remote_url, promotion.remote_ref]);
    assert.equal(calls[0].options.shell, false);
    assert.equal(application.spec.source.targetRevision, promotion.remote_sha);
    assert.equal(YAML.parse(await readFile(output, 'utf8')).spec.source.targetRevision, promotion.remote_sha);

    await rm(output, { force: true });
    await assert.rejects(
      renderArgoApplication({
        remoteUrl: promotion.remote_url,
        remoteRef: 'HEAD',
        remoteSha: promotion.remote_sha,
        promotion,
        outputPath: output,
        spawnSyncImpl: success,
      }),
      /fully qualified refs\/heads/u,
    );
    await assert.rejects(access(output));

    await assert.rejects(
      renderArgoApplication({
        remoteUrl: promotion.remote_url,
        remoteRef: promotion.remote_ref,
        remoteSha: promotion.remote_sha,
        promotion,
        outputPath: output,
        spawnSyncImpl: () => ({ status: 128, stdout: '', stderr: 'unavailable' }),
      }),
      BlockedExternalEnvironmentError,
    );
    await assert.rejects(access(output));
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }

  const project = YAML.parse(await readFile(path.join(root, 'infra/argocd/project.yaml'), 'utf8'));
  assert.deepEqual(project.spec.clusterResourceWhitelist, []);
  assert.equal(project.spec.namespaceResourceBlacklist.some((entry) => entry.kind === 'Secret'), true);
  assert.equal(project.spec.syncWindows.some((entry) => entry.kind === 'allow'), true);
});

test('deployment preflight blocks before invoking any dependent tool when the toolchain is incomplete', async () => {
  let invocations = 0;
  const result = await preflightDeploymentToolchain({
    resolveExecutableImpl: async (name) => {
      if (name === 'helm') return 'helm';
      throw Object.assign(new Error('missing'), { code: 'ENOENT' });
    },
    runToolImpl: () => { invocations += 1; },
  });
  assert.equal(result.status, 'BLOCKED');
  assert.equal(result.reason_code, 'BLOCKED_TOOLCHAIN');
  assert.equal(result.exit_code, 2);
  assert.equal(invocations, 0);
});
