import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { parse } from 'yaml';

test('canonical foundation OpenAPI is parseable and preserves command guards', async () => {
  const source = await readFile(new URL('../../contracts/openapi/accord-control-api.yaml', import.meta.url), 'utf8');
  const problemSource = await readFile(new URL('../../contracts/json-schema/problem-details.schema.json', import.meta.url), 'utf8');
  const api = parse(source);
  const problem = JSON.parse(problemSource);
  assert.equal(api?.openapi, '3.1.0');
  assert.equal(api?.info?.title, 'Accord Control API');
  assert.equal(api?.components?.parameters?.IdempotencyKey?.required, true);
  assert.equal(api?.components?.parameters?.ExpectedVersion?.required, true);
  assert.equal(api?.components?.schemas?.Problem?.$ref, '../json-schema/problem-details.schema.json');
  assert.deepEqual(
    problem.required,
    ['type', 'title', 'status', 'code', 'correlation_id', 'instance']
  );
});
