import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import YAML from 'yaml';

test('the V1 runtime and contract boundaries are singular', async () => {
  const raw = await readFile('contracts/compatibility/v1-boundaries.yaml', 'utf8');
  const milestoneBuild = await readFile('tests/integration/build.gradle', 'utf8');
  const manifest = YAML.parse(raw);
  assert.deepEqual(manifest.runtimes, {
    control_plane: 'java-21-spring-modulith',
    edge_services: 'java-21-spring-boot-isolated',
    security_services: 'java-21-spring-boot-isolated',
    cli: 'java-21-picocli-jlink',
    agent_runtime: 'python-3.12-pydantic',
    web: 'react-19.1-typescript-5.8-vite-7'
  });
  assert.equal(manifest.infrastructure.iac, 'opentofu');
  assert.deepEqual(manifest.security_services.sort(),
    ['break-glass-broker', 'credential-broker', 'merge-controller', 'provider-connector', 'signing-service']);
  assert.deepEqual(manifest.edge_services.sort(),
    ['agent-pack-gateway', 'attachment-scanner', 'webhook-edge']);
  assert.deepEqual(manifest.edge_workload_profiles.sort(),
    ['agent-pack-gateway', 'attachment-scanner', 'provider-auth-callback-edge', 'webhook-edge']);
  assert.equal(manifest.contracts.http, 'contracts/openapi/accord-control-api.yaml');
  assert.match(raw, /database\/control-plane\/migrations/);
  assert.match(milestoneBuild, /milestoneTest\s*\{/);
  assert.match(milestoneBuild, /tasks\.register\(['"]milestoneTest['"],\s*Test\)/);
  assert.doesNotMatch(milestoneBuild, /(?:check|test).{0,80}dependsOn.{0,80}milestoneTest/s);
});
