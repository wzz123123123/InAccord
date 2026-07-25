import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import YAML from 'yaml';

test('mutating operations require idempotency, expected version, and problem responses', async () => {
  const source = await readFile('contracts/openapi/accord-control-api.yaml', 'utf8');
  const api = YAML.parse(source);
  assert.equal(api.openapi, '3.1.0');
  assert.equal(
    api.paths['/v1/contract-validations/{validationId}']?.post?.operationId,
    'validateContract',
    'the foundation mutation contract must remain present',
  );
  for (const [path, pathItem] of Object.entries(api.paths)) {
    for (const method of ['post', 'put', 'patch', 'delete']) {
      const operation = pathItem[method];
      if (!operation) continue;
      const refs = (operation.parameters ?? []).map((entry) => entry.$ref);
      assert.ok(refs.includes('#/components/parameters/IdempotencyKey'), `${method} ${path} lacks Idempotency-Key`);
      assert.ok(refs.includes('#/components/parameters/ExpectedVersion'), `${method} ${path} lacks If-Match`);
      assert.equal(operation.responses.default.$ref, '#/components/responses/ProblemResponse');
    }
  }
});

test('operations health contract uses the production Spring Actuator handlers', async () => {
  const source = await readFile('contracts/openapi/accord-control-api.yaml', 'utf8');
  const api = YAML.parse(source);
  const healthPaths = Object.keys(api.paths).filter((path) => path.includes('/health/')).sort();
  assert.deepEqual(healthPaths, [
    '/actuator/health/liveness',
    '/actuator/health/readiness',
  ]);
  assert.equal(api.paths['/health/ready'], undefined, 'legacy non-handler path must not be advertised');
  for (const path of healthPaths) {
    assert.equal(
      api.paths[path].get.responses['200'].content['application/json'].schema.properties.status.const,
      'UP',
    );
  }
});

test('operation documentation and authentication policy are explicit', async () => {
  const source = await readFile('contracts/openapi/accord-control-api.yaml', 'utf8');
  const api = YAML.parse(source);
  assert.deepEqual(api.security, [{ browserSession: [] }, { oidc: [] }]);
  assert.deepEqual(api.components.securitySchemes.browserSession, {
    type: 'apiKey',
    in: 'cookie',
    name: '__Host-accord-session',
  });
  assert.equal(api.components.securitySchemes.oidc.type, 'http');
  assert.equal(api.components.securitySchemes.oidc.scheme, 'bearer');
  for (const [path, pathItem] of Object.entries(api.paths)) {
    for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
      const operation = pathItem[method];
      if (!operation) continue;
      assert.ok(operation.summary, `${method} ${path} lacks summary`);
      if (path.startsWith('/actuator/health/')) {
        assert.deepEqual(operation.security, [], `${method} ${path} must be an anonymous probe`);
      }
    }
  }
});

test('OpenAPI reuses the canonical Problem Details schema', async () => {
  const source = await readFile('contracts/openapi/accord-control-api.yaml', 'utf8');
  const api = YAML.parse(source);
  assert.equal(
    api.components.schemas.Problem.$ref,
    '../json-schema/problem-details.schema.json',
  );
  assert.equal(
    api.components.responses.ProblemResponse.content['application/problem+json'].schema.$ref,
    '#/components/schemas/Problem',
  );
});
