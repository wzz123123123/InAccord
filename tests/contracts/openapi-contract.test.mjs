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

test('contract validation mutation exposes the strict HTTP reliability contract', async () => {
  const source = await readFile('contracts/openapi/accord-control-api.yaml', 'utf8');
  const api = YAML.parse(source);
  const operation = api.paths['/v1/contract-validations/{validationId}'].post;

  assert.equal(operation.operationId, 'validateContract');
  assert.deepEqual(operation.security, [{ browserSession: [] }, { oidc: [] }]);
  assert.equal(operation['x-browser-csrf-required'], 'conditional');
  assert.equal(operation['x-accept-policy'], 'application/json-or-wildcard');
  assert.deepEqual(operation['x-problem-codes-by-status'], {
    400: ['REQUEST_INVALID', 'JSON_INVALID'],
    401: ['AUTHENTICATION_REQUIRED'],
    403: ['AUTHORIZATION_DENIED', 'CSRF_VALIDATION_FAILED'],
    404: ['SCHEMA_NOT_FOUND', 'CONTRACT_VALIDATION_NOT_FOUND'],
    406: ['NOT_ACCEPTABLE'],
    409: ['IDEMPOTENCY_KEY_REUSED', 'COMMAND_IN_PROGRESS'],
    412: ['VERSION_CONFLICT'],
    415: ['UNSUPPORTED_MEDIA_TYPE'],
    422: ['DOCUMENT_SCHEMA_INVALID', 'VERSION_LIMIT_REACHED'],
  });
  assert.deepEqual(Object.keys(operation.responses).sort(), [
    '201', '400', '401', '403', '404', '406', '409', '412', '415', '422', 'default',
  ].sort());
  assert.equal(operation.responses['201'].headers.ETag.required, true);
  assert.equal(
    operation.responses['201'].headers.ETag.schema.pattern,
    '^"(0|[1-9][0-9]*)"$',
  );
  for (const status of ['400', '401', '403', '404', '406', '409', '412', '415', '422']) {
    assert.equal(
      operation.responses[status].$ref,
      '#/components/responses/ProblemResponse',
      `${status} must use the canonical Problem response`,
    );
  }

  const parameterRefs = operation.parameters.map((parameter) => parameter.$ref).filter(Boolean);
  assert.ok(parameterRefs.includes('#/components/parameters/IdempotencyKey'));
  assert.ok(parameterRefs.includes('#/components/parameters/ExpectedVersion'));
  assert.ok(parameterRefs.includes('#/components/parameters/BrowserCsrfToken'));
  assert.equal(
    operation.requestBody.content['application/json'].schema.$ref,
    '#/components/schemas/ContractValidationRequest',
  );
  assert.equal(
    operation.responses['201'].content['application/json'].schema.$ref,
    '#/components/schemas/ContractValidationResponse',
  );

  assert.equal(api.components.parameters.ExpectedVersion.schema.pattern, '^"(0|[1-9][0-9]*)"$');
  assert.equal(
    api.components.parameters.ExpectedVersion.description,
    'One canonical quoted non-negative aggregate sequence.',
  );
  assert.deepEqual(api.components.parameters.BrowserCsrfToken, {
    name: 'X-CSRF-Token',
    in: 'header',
    required: false,
    description: 'Required only for unsafe requests authenticated by browserSession.',
    schema: {
      type: 'string',
      minLength: 32,
      maxLength: 256,
      pattern: '^[A-Za-z0-9._~-]+$',
    },
  });
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

test('Problem Details has exact base fields and bounded closed extensions', async () => {
  const source = await readFile('contracts/json-schema/problem-details.schema.json', 'utf8');
  const schema = JSON.parse(source);
  const exactSchema = YAML.parse(source, { intAsBigInt: true });

  assert.equal(schema.additionalProperties, true);
  assert.deepEqual(schema.required, [
    'type', 'title', 'status', 'code', 'correlation_id', 'instance',
  ]);
  assert.equal(schema.properties.detail, undefined, 'detail must be forbidden');
  assert.deepEqual(schema.not, { required: ['detail'] });
  assert.equal(schema.properties.type.format, 'uri');
  assert.equal(schema.properties.instance.format, 'uri-reference');
  assert.equal(schema.properties.expected_version.minimum, 0);
  assert.deepEqual(schema.properties.actual_version.anyOf, [
    { type: 'null' },
    { type: 'integer', minimum: 1, maximum: schema.properties.expected_version.maximum },
  ]);
  assert.equal(exactSchema.properties.expected_version.maximum, 9223372036854775807n);
  assert.equal(
    exactSchema.properties.actual_version.anyOf[1].maximum,
    9223372036854775807n,
  );
  assert.deepEqual(schema.properties.retry_after, {
    type: 'integer',
    minimum: 1,
    maximum: 120,
  });
  assert.equal(schema.properties.errors.minItems, 1);
  assert.equal(schema.properties.errors.maxItems, 128);
  assert.equal(schema.properties.errors.items.additionalProperties, false);
  assert.equal(schema.properties.errors.items.properties.field.minLength, 1);
  assert.equal(schema.properties.errors.items.properties.field.maxLength, 512);
  assert.equal(schema.properties.errors.items.properties.reason.minLength, 1);
  assert.equal(schema.properties.errors.items.properties.reason.maxLength, 64);
  assert.equal(
    schema.properties.errors.items.properties.reason.pattern,
    '^[A-Z][A-Z0-9_]*$',
  );
});
