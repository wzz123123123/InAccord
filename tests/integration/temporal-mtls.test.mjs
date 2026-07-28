import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

import YAML from 'yaml';

const root = path.resolve(import.meta.dirname, '..', '..');
const read = (relative) => readFile(path.join(root, relative), 'utf8');

test('Temporal frontend requires TLS 1.3 client authentication and hostname verification', async () => {
  const config = YAML.parse(await read('infra/local/temporal/server.yaml'));
  const frontend = config.global.tls.frontend;
  assert.equal(frontend.server.requireClientAuth, true);
  assert.equal(frontend.server.minVersion, '1.3');
  assert.equal(frontend.server.clientCaFiles[0], '/run/accord/temporal/pki/client-ca.pem');
  assert.equal(frontend.client.serverName, 'temporal');
  assert.equal(frontend.client.disableHostVerification, false);
  assert.equal(frontend.client.rootCaFiles[0], '/run/accord/temporal/pki/server-ca.pem');
  assert.equal(frontend.client.certFile, '/run/accord/temporal/pki/server-internode.pem');
  assert.notEqual(frontend.server.certFile, frontend.server.clientCaFiles[0]);
});

test('Temporal schema/runtime identities and databases are separate', async () => {
  const sql = await read('infra/local/postgres/00-roles-and-databases.sql');
  for (const token of [
    'accord_temporal_schema_login', 'accord_temporal_runtime_login',
    'accord_temporal', 'accord_temporal_visibility',
  ]) assert.match(sql, new RegExp(`\\b${token}\\b`, 'u'));
  assert.match(sql, /REVOKE\s+CONNECT\s+ON\s+DATABASE\s+accord\s+FROM\s+accord_temporal_schema_login/iu);
  assert.match(sql, /REVOKE\s+CONNECT\s+ON\s+DATABASE\s+accord_temporal\s+FROM\s+accord_app_login/iu);
  assert.doesNotMatch(sql, /(?:^|\s)(?:SUPERUSER|BYPASSRLS)(?:\s|;|$)/iu);
});

test('Temporal schema is one shot and server waits for its successful completion', async () => {
  const compose = YAML.parse(await read('infra/local/compose.yaml'));
  const schema = compose.services['temporal-schema'];
  const server = compose.services['temporal-server'];
  assert.equal(schema.restart, 'no');
  assert.equal(server.depends_on['temporal-schema'].condition, 'service_completed_successfully');
  const command = JSON.stringify(schema.command);
  for (const database of ['accord_temporal', 'accord_temporal_visibility']) {
    assert.match(command, new RegExp(database, 'u'));
  }
  assert.match(command, /\/usr\/local\/bin\/temporal-sql-tool/u);
  assert.match(command, /postgres12/u);
});

test('UI and namespace admin mount only their own mTLS identities', async () => {
  const compose = YAML.parse(await read('infra/local/compose.yaml'));
  const ui = JSON.stringify(compose.services['temporal-ui']);
  const admin = JSON.stringify(compose.services['temporal-namespace']);
  assert.match(ui, /ui[.]pem/u);
  assert.match(ui, /ui-key[.]pem/u);
  assert.match(ui, /server-ca[.]pem/u);
  assert.doesNotMatch(ui, /client-ca[.]pem/u);
  assert.doesNotMatch(ui, /worker|admin[.]pem|server-key/iu);
  assert.match(admin, /admin[.]pem/u);
  assert.match(admin, /admin-key[.]pem/u);
  assert.match(admin, /server-ca[.]pem/u);
  assert.doesNotMatch(admin, /client-ca[.]pem/u);
  assert.doesNotMatch(admin, /worker|ui-key|server-key/iu);
});

test('tracked FT13 topology files contain no generated private key material', async () => {
  const files = [
    'infra/local/compose.yaml',
    'infra/local/temporal/server.yaml',
    'scripts/local-up.mjs',
    'scripts/local-down.mjs',
  ];
  for (const file of files) {
    assert.doesNotMatch(await read(file), /-----BEGIN (?:RSA |EC |)PRIVATE KEY-----/u, file);
  }
});
