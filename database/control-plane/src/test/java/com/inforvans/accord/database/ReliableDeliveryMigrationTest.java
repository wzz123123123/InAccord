package com.inforvans.accord.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReliableDeliveryMigrationTest {
    private static final String POSTGRES_IMAGE = "postgres:17.5";
    private PostgreSQLContainer<?> postgres;

    @BeforeAll
    void migrate() {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
        postgres.start();
        ControlPlaneTestRoles.bootstrap(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
            .dataSource(
                postgres.getJdbcUrl(),
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD)
            .initSql("SET ROLE accord_migrator")
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @AfterAll
    void stop() {
        postgres.stop();
    }

    @Test
    void v002CreatesTheFourTenantScopedReliabilityTables() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                 SELECT table_name
                 FROM information_schema.tables
                 WHERE table_schema='public'
                   AND table_name IN (
                     'domain_event','outbox_event','inbox_message','external_call_intent')
                 ORDER BY table_name
                 """)) {
            assertThat(readStrings(rows)).containsExactly(
                "domain_event", "external_call_intent", "inbox_message", "outbox_event");
        }
    }

    @Test
    void everyReliabilityTableHasForcedRlsAndTheCanonicalPolicy() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                 SELECT relation.relname, relation.relrowsecurity, relation.relforcerowsecurity,
                        policy.polname, policy.polcmd,
                        pg_get_expr(policy.polqual, policy.polrelid),
                        pg_get_expr(policy.polwithcheck, policy.polrelid)
                 FROM pg_class relation
                 JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
                 LEFT JOIN pg_policy policy ON policy.polrelid=relation.oid
                 WHERE namespace.nspname='public'
                   AND relation.relname IN (
                     'domain_event','outbox_event','inbox_message','external_call_intent')
                 ORDER BY relation.relname
                 """)) {
            int count = 0;
            while (rows.next()) {
                count++;
                assertThat(rows.getBoolean(2)).isTrue();
                assertThat(rows.getBoolean(3)).isTrue();
                assertThat(rows.getString(4)).isEqualTo("tenant_isolation");
                assertThat(rows.getString(5)).isEqualTo("*");
                assertThat(rows.getString(6))
                    .isEqualTo("(tenant_id = accord_security.current_tenant_id())");
                assertThat(rows.getString(7))
                    .isEqualTo("(tenant_id = accord_security.current_tenant_id())");
            }
            assertThat(count).isEqualTo(4);
        }
    }

    @Test
    void externalIntentUsesTenantFirstKeysAndContainsNoSensitiveMaterialColumns()
            throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT attribute.attname
                FROM pg_constraint constraint_row
                JOIN unnest(constraint_row.conkey) WITH ORDINALITY key(attnum, ordinal)
                  ON true
                JOIN pg_attribute attribute
                  ON attribute.attrelid=constraint_row.conrelid
                 AND attribute.attnum=key.attnum
                WHERE constraint_row.conrelid='public.external_call_intent'::regclass
                  AND constraint_row.contype='p'
                ORDER BY key.ordinal
                """)).containsExactly("tenant_id", "intent_id");
            assertThat(strings(statement, """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema='public' AND table_name='external_call_intent'
                  AND lower(column_name) ~ '(url|body|credential|source|diff)'
                ORDER BY column_name
                """)).isEmpty();
            assertThat(number(statement, """
                SELECT count(*)
                FROM pg_index index_row
                WHERE index_row.indrelid='public.external_call_intent'::regclass
                  AND index_row.indisunique
                  AND pg_get_indexdef(index_row.indexrelid)
                    LIKE '%(global_idempotency_key)%'
                """)).isEqualTo(1);
        }
    }

    @Test
    void runtimePrivilegesAreExactAndApiCannotUpdateIntents() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT grantee || ':' || table_name || ':' || privilege_type
                FROM information_schema.role_table_grants
                WHERE table_schema='public'
                  AND grantee IN ('accord_api','accord_worker')
                  AND table_name IN (
                    'domain_event','outbox_event','inbox_message','external_call_intent')
                ORDER BY 1
                """)).containsExactly(
                    "accord_api:domain_event:INSERT",
                    "accord_api:domain_event:SELECT",
                    "accord_api:external_call_intent:INSERT",
                    "accord_api:external_call_intent:SELECT",
                    "accord_api:outbox_event:INSERT",
                    "accord_api:outbox_event:SELECT",
                    "accord_worker:domain_event:INSERT",
                    "accord_worker:domain_event:SELECT",
                    "accord_worker:external_call_intent:INSERT",
                    "accord_worker:external_call_intent:SELECT",
                    "accord_worker:inbox_message:INSERT",
                    "accord_worker:inbox_message:SELECT",
                    "accord_worker:outbox_event:INSERT",
                    "accord_worker:outbox_event:SELECT");
            assertThat(number(statement, """
                SELECT count(*) FROM information_schema.column_privileges
                WHERE table_schema='public' AND table_name='external_call_intent'
                  AND grantee='accord_api' AND privilege_type='UPDATE'
                """)).isZero();
            assertThat(number(statement, """
                SELECT count(*) FROM information_schema.column_privileges
                WHERE table_schema='public' AND table_name='external_call_intent'
                  AND grantee='accord_worker' AND privilege_type='UPDATE'
                """)).isEqualTo(15);
        }
    }

    @Test
    void appendOnlyAndIntentTransitionTriggersAreInstalled() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT relation.relname || ':' || trigger_row.tgname
                FROM pg_trigger trigger_row
                JOIN pg_class relation ON relation.oid=trigger_row.tgrelid
                JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
                WHERE namespace.nspname='public' AND NOT trigger_row.tgisinternal
                  AND relation.relname IN (
                    'domain_event','outbox_event','inbox_message','external_call_intent')
                ORDER BY 1
                """)).containsExactly(
                    "domain_event:domain_event_append_only",
                    "external_call_intent:external_intent_successor_guard",
                    "external_call_intent:external_intent_transition_guard",
                    "inbox_message:inbox_v002_immutable",
                    "outbox_event:outbox_v002_immutable");
        }
    }

    @Test
    void successorGuardHasFixedDefinerAndNoRuntimeExecutePrivilege() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT owner.rolname || ':' || procedure.prosecdef || ':'
                       || pg_catalog.array_to_string(procedure.proconfig, ',')
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid=procedure.pronamespace
                JOIN pg_catalog.pg_roles owner ON owner.oid=procedure.proowner
                WHERE namespace.nspname='accord_security'
                  AND procedure.proname='guard_external_intent_successor'
                  AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)=''
                """)).containsExactly(
                    "accord_migrator:true:search_path=pg_catalog, pg_temp");
            assertThat(number(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid=procedure.pronamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(
                  COALESCE(
                    procedure.proacl,
                    pg_catalog.acldefault('f', procedure.proowner))) privilege
                LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=privilege.grantee
                WHERE namespace.nspname='accord_security'
                  AND procedure.proname='guard_external_intent_successor'
                  AND privilege.privilege_type='EXECUTE'
                  AND (privilege.grantee=0
                    OR grantee.rolname IN ('accord_api','accord_worker'))
                """)).isZero();
        }
    }

    @Test
    void highlyCompressibleLogicalJsonLargerThanOneMiBIsRejected() throws Exception {
        try (Connection connection = adminConnection();
             PreparedStatement insert = connection.prepareStatement("""
                 INSERT INTO domain_event (
                   tenant_id,event_id,scope_type,scope_id,aggregate_type,aggregate_id,
                   sequence,event_type,schema_version,causation_id,correlation_id,
                   actor_id,payload,occurred_at
                 ) VALUES (?,?,'project','project-1','requirement',?,1,
                   'requirement.changed','1.0.0',?,?,'actor-1',
                   jsonb_build_object('data', repeat('x', 1048577)),clock_timestamp())
                 """)) {
            for (int index = 1; index <= 5; index++) {
                insert.setObject(index, UUID.randomUUID());
            }
            assertThatThrownBy(insert::executeUpdate)
                .hasMessageContaining("domain_event_payload_bounded");
        }
    }

    @Test
    void rawExecutionOwnerWithControlCharacterIsRejected() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            UUID intentId = insertRoot(statement, "action-owner-test-0001");
            assertThatThrownBy(() -> statement.executeUpdate("""
                UPDATE external_call_intent
                SET state='EXECUTING',execution_owner=E'bad\\nowner',
                    execution_generation=1,execution_token=gen_random_uuid(),
                    execution_permitted_at=clock_timestamp(),
                    execution_lease_until=clock_timestamp()+interval '30 seconds'
                WHERE intent_id='%s'
                """.formatted(intentId)))
                .hasMessageContaining("external_call_intent_safe_identifiers");
        }
    }

    @Test
    void reconciliationClaimCannotInventProviderRequestId() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            UUID intentId = insertRoot(statement, "action-provider-id-0001");
            statement.executeUpdate("""
                UPDATE external_call_intent
                SET state='EXECUTING',execution_owner='worker-1',
                    execution_generation=1,execution_token=gen_random_uuid(),
                    execution_permitted_at=clock_timestamp(),
                    execution_lease_until=clock_timestamp()+interval '30 seconds'
                WHERE intent_id='%s'
                """.formatted(intentId));
            statement.executeUpdate("""
                UPDATE external_call_intent
                SET state='OUTCOME_UNKNOWN',last_error_code='PROVIDER_TIMEOUT'
                WHERE intent_id='%s'
                """.formatted(intentId));
            assertThatThrownBy(() -> statement.executeUpdate("""
                UPDATE external_call_intent
                SET state='RECONCILING',reconciliation_owner='reconciler-1',
                    reconciliation_generation=1,reconciliation_token=gen_random_uuid(),
                    reconciliation_started_at=clock_timestamp(),
                    reconciliation_lease_until=clock_timestamp()+interval '30 seconds',
                    last_error_code=NULL,provider_request_id='invented-request'
                WHERE intent_id='%s'
                """.formatted(intentId)))
                .hasMessageContaining("invalid reconciliation permit");
        }
    }

    @Test
    void printableLogicalActionKeyMayContainSpaces() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            UUID intentId = insertRoot(statement, "action key with spaces");
            assertThat(number(statement, """
                SELECT count(*) FROM external_call_intent
                WHERE intent_id='%s'
                """.formatted(intentId))).isEqualTo(1);
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static UUID insertRoot(Statement statement, String logicalKey) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        statement.executeUpdate("""
            INSERT INTO external_call_intent (
              tenant_id,intent_id,root_intent_id,attempt_ordinal,logical_action_key,
              scope_type,scope_id,provider,provider_installation_id,
              provider_repository_id,operation,request_reference_type,
              request_reference_id,request_reference_version,request_digest,state)
            VALUES ('%s','%s','%s',1,'%s','repository','repository-1','gitlab',
              'installation-1','repository-1','git.branch.create',
              'delivery-work-item','work-item-1',1,'%s','RECORDED')
            """.formatted(
                tenantId, intentId, intentId, logicalKey.replace("'", "''"),
                "sha256:" + "a".repeat(64)));
        return intentId;
    }

    private static List<String> strings(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return readStrings(rows);
        }
    }

    private static List<String> readStrings(ResultSet rows) throws Exception {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        while (rows.next()) {
            values.add(rows.getString(1));
        }
        return List.copyOf(values);
    }

    private static long number(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
}
