package com.inforvans.accord.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final List<String> RELIABILITY_FUNCTIONS = List.of(
        "accept_inbox_message",
        "append_reliable_event",
        "claim_external_intent_execution",
        "claim_external_intent_reconciliation",
        "complete_external_intent_execution",
        "complete_external_intent_reconciliation",
        "create_external_intent_successor",
        "expire_external_intent_execution",
        "expire_external_intent_reconciliation",
        "guard_external_intent_successor",
        "guard_external_intent_transition",
        "load_external_intent_snapshot",
        "mark_external_intent_execution_unknown",
        "mark_external_intent_reconciliation_unknown",
        "record_external_intent",
        "reject_reliability_row_change",
        "renew_external_intent_execution",
        "renew_external_intent_reconciliation");
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
    void runtimeTablePrivilegesAreReadOnlyAndExact() throws Exception {
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
                    "accord_api:domain_event:SELECT",
                    "accord_api:outbox_event:SELECT",
                    "accord_worker:domain_event:SELECT",
                    "accord_worker:inbox_message:SELECT",
                    "accord_worker:outbox_event:SELECT");
            assertThat(number(statement, """
                SELECT count(*)
                FROM information_schema.column_privileges
                WHERE table_schema='public'
                  AND table_name='external_call_intent'
                  AND grantee IN ('accord_api','accord_worker')
                """)).isZero();
            assertThat(number(statement, """
                SELECT count(*)
                FROM information_schema.column_privileges
                WHERE table_schema='public'
                  AND table_name IN (
                    'domain_event','outbox_event','inbox_message','external_call_intent')
                  AND grantee IN ('accord_api','accord_worker')
                  AND privilege_type <> 'SELECT'
                """)).isZero();
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
    void reliabilityFunctionsHaveFixedDefinersAndExactRuntimeExecutePrivileges()
            throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT procedure.proname || ':' || owner.rolname || ':'
                       || procedure.prosecdef || ':'
                       || pg_catalog.array_to_string(procedure.proconfig, ',')
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid=procedure.pronamespace
                JOIN pg_catalog.pg_roles owner ON owner.oid=procedure.proowner
                WHERE namespace.nspname='accord_security'
                  AND procedure.proname IN (
                    'accept_inbox_message','append_reliable_event',
                    'claim_external_intent_execution',
                    'claim_external_intent_reconciliation',
                    'complete_external_intent_execution',
                    'complete_external_intent_reconciliation',
                    'create_external_intent_successor',
                    'expire_external_intent_execution',
                    'expire_external_intent_reconciliation',
                    'guard_external_intent_successor',
                    'guard_external_intent_transition',
                    'load_external_intent_snapshot',
                    'mark_external_intent_execution_unknown',
                    'mark_external_intent_reconciliation_unknown',
                    'record_external_intent','reject_reliability_row_change',
                    'renew_external_intent_execution',
                    'renew_external_intent_reconciliation')
                ORDER BY procedure.proname
                """)).containsExactlyElementsOf(RELIABILITY_FUNCTIONS.stream()
                    .map(name -> name
                        + ":accord_migrator:"
                        + (!name.equals("guard_external_intent_transition")
                            && !name.equals("reject_reliability_row_change"))
                        + ":search_path=pg_catalog, pg_temp")
                    .toList());
            assertThat(strings(statement, """
                SELECT COALESCE(grantee.rolname, 'PUBLIC') || ':'
                       || procedure.proname
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid=procedure.pronamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(
                  COALESCE(
                    procedure.proacl,
                    pg_catalog.acldefault('f', procedure.proowner))) privilege
                LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=privilege.grantee
                WHERE namespace.nspname='accord_security'
                  AND procedure.proname IN (
                    'accept_inbox_message','append_reliable_event',
                    'claim_external_intent_execution',
                    'claim_external_intent_reconciliation',
                    'complete_external_intent_execution',
                    'complete_external_intent_reconciliation',
                    'create_external_intent_successor',
                    'expire_external_intent_execution',
                    'expire_external_intent_reconciliation',
                    'guard_external_intent_successor',
                    'guard_external_intent_transition',
                    'load_external_intent_snapshot',
                    'mark_external_intent_execution_unknown',
                    'mark_external_intent_reconciliation_unknown',
                    'record_external_intent','reject_reliability_row_change',
                    'renew_external_intent_execution',
                    'renew_external_intent_reconciliation')
                  AND privilege.privilege_type='EXECUTE'
                  AND (privilege.grantee=0 OR grantee.rolname IN (
                    'accord_api','accord_worker'))
                ORDER BY 1
                """)).containsExactly(
                    "accord_api:append_reliable_event",
                    "accord_api:load_external_intent_snapshot",
                    "accord_api:record_external_intent",
                    "accord_worker:accept_inbox_message",
                    "accord_worker:append_reliable_event",
                    "accord_worker:claim_external_intent_execution",
                    "accord_worker:claim_external_intent_reconciliation",
                    "accord_worker:complete_external_intent_execution",
                    "accord_worker:complete_external_intent_reconciliation",
                    "accord_worker:create_external_intent_successor",
                    "accord_worker:expire_external_intent_execution",
                    "accord_worker:expire_external_intent_reconciliation",
                    "accord_worker:load_external_intent_snapshot",
                    "accord_worker:mark_external_intent_execution_unknown",
                    "accord_worker:mark_external_intent_reconciliation_unknown",
                    "accord_worker:renew_external_intent_execution",
                    "accord_worker:renew_external_intent_reconciliation");

            assertThat(number(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid=procedure.pronamespace
                WHERE namespace.nspname='accord_security'
                  AND procedure.proname IN (
                    'claim_external_intent_execution',
                    'claim_external_intent_reconciliation',
                    'renew_external_intent_execution',
                    'renew_external_intent_reconciliation')
                  AND pg_catalog.pg_get_function_result(procedure.oid)
                    LIKE 'SETOF %external_call_intent%'
                """)).isZero();

            assertThat(functionOutputColumns(statement, """
                'create_external_intent_successor',
                'load_external_intent_snapshot',
                'record_external_intent'
                """)).containsExactly(
                    "create_external_intent_successor:result_tenant_id:uuid",
                    "create_external_intent_successor:result_intent_id:uuid",
                    "create_external_intent_successor:result_root_intent_id:uuid",
                    "create_external_intent_successor:result_attempt_ordinal:integer",
                    "create_external_intent_successor:result_global_idempotency_key:"
                        + "character varying",
                    "create_external_intent_successor:result_state:character varying",
                    "load_external_intent_snapshot:tenant_id:uuid",
                    "load_external_intent_snapshot:intent_id:uuid",
                    "load_external_intent_snapshot:root_intent_id:uuid",
                    "load_external_intent_snapshot:predecessor_intent_id:uuid",
                    "load_external_intent_snapshot:attempt_ordinal:integer",
                    "load_external_intent_snapshot:logical_action_key:character varying",
                    "load_external_intent_snapshot:global_idempotency_key:"
                        + "character varying",
                    "load_external_intent_snapshot:state:character varying",
                    "load_external_intent_snapshot:execution_generation:bigint",
                    "load_external_intent_snapshot:reconciliation_generation:bigint",
                    "load_external_intent_snapshot:provider_request_id:character varying",
                    "load_external_intent_snapshot:outcome_digest:character",
                    "load_external_intent_snapshot:last_error_code:character varying",
                    "load_external_intent_snapshot:created_at:timestamp with time zone",
                    "load_external_intent_snapshot:updated_at:timestamp with time zone",
                    "load_external_intent_snapshot:terminal_at:timestamp with time zone",
                    "record_external_intent:disposition:text",
                    "record_external_intent:result_tenant_id:uuid",
                    "record_external_intent:result_intent_id:uuid",
                    "record_external_intent:result_root_intent_id:uuid",
                    "record_external_intent:result_attempt_ordinal:integer",
                    "record_external_intent:result_global_idempotency_key:character varying",
                    "record_external_intent:result_state:character varying");

            assertThat(functionOutputColumns(statement, """
                'claim_external_intent_execution',
                'claim_external_intent_reconciliation',
                'renew_external_intent_execution',
                'renew_external_intent_reconciliation'
                """)).containsExactly(
                    "claim_external_intent_execution:disposition:text",
                    "claim_external_intent_execution:state:character varying",
                    "claim_external_intent_execution:tenant_id:uuid",
                    "claim_external_intent_execution:intent_id:uuid",
                    "claim_external_intent_execution:execution_owner:character varying",
                    "claim_external_intent_execution:execution_generation:bigint",
                    "claim_external_intent_execution:execution_token:uuid",
                    "claim_external_intent_execution:execution_lease_until:"
                        + "timestamp with time zone",
                    "claim_external_intent_execution:global_idempotency_key:"
                        + "character varying",
                    "claim_external_intent_execution:provider:character varying",
                    "claim_external_intent_execution:provider_installation_id:"
                        + "character varying",
                    "claim_external_intent_execution:provider_repository_id:"
                        + "character varying",
                    "claim_external_intent_execution:operation:character varying",
                    "claim_external_intent_execution:request_reference_type:"
                        + "character varying",
                    "claim_external_intent_execution:request_reference_id:"
                        + "character varying",
                    "claim_external_intent_execution:request_reference_version:bigint",
                    "claim_external_intent_execution:request_digest:character",
                    "claim_external_intent_reconciliation:disposition:text",
                    "claim_external_intent_reconciliation:state:character varying",
                    "claim_external_intent_reconciliation:tenant_id:uuid",
                    "claim_external_intent_reconciliation:intent_id:uuid",
                    "claim_external_intent_reconciliation:reconciliation_owner:"
                        + "character varying",
                    "claim_external_intent_reconciliation:reconciliation_generation:bigint",
                    "claim_external_intent_reconciliation:reconciliation_token:uuid",
                    "claim_external_intent_reconciliation:reconciliation_lease_until:"
                        + "timestamp with time zone",
                    "claim_external_intent_reconciliation:global_idempotency_key:"
                        + "character varying",
                    "claim_external_intent_reconciliation:provider:character varying",
                    "claim_external_intent_reconciliation:provider_installation_id:"
                        + "character varying",
                    "claim_external_intent_reconciliation:provider_repository_id:"
                        + "character varying",
                    "claim_external_intent_reconciliation:operation:character varying",
                    "claim_external_intent_reconciliation:request_reference_type:"
                        + "character varying",
                    "claim_external_intent_reconciliation:request_reference_id:"
                        + "character varying",
                    "claim_external_intent_reconciliation:request_reference_version:bigint",
                    "claim_external_intent_reconciliation:request_digest:character",
                    "claim_external_intent_reconciliation:provider_request_id:"
                        + "character varying",
                    "renew_external_intent_execution:execution_lease_until:"
                        + "timestamp with time zone",
                    "renew_external_intent_reconciliation:reconciliation_lease_until:"
                        + "timestamp with time zone");
        }
    }

    @Test
    void runtimeLoginsCannotBypassWriteFunctionsWithTableDml() throws Exception {
        UUID tenantId = UUID.randomUUID();
        assertRuntimeSqlState(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api",
            tenantId,
            "INSERT INTO public.domain_event (tenant_id,event_id) VALUES ('%s','%s')"
                .formatted(tenantId, UUID.randomUUID()),
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api",
            tenantId,
            "INSERT INTO public.external_call_intent (tenant_id,intent_id) "
                + "VALUES ('%s','%s')".formatted(tenantId, UUID.randomUUID()),
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            tenantId,
            "INSERT INTO public.inbox_message (tenant_id,source,source_message_id) "
                + "VALUES ('%s','gitlab','message-1')".formatted(tenantId),
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            tenantId,
            "INSERT INTO public.external_call_intent "
                + "(tenant_id,intent_id,predecessor_intent_id) "
                + "VALUES ('%s','%s','%s')".formatted(
                    tenantId, UUID.randomUUID(), UUID.randomUUID()),
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            tenantId,
            "UPDATE public.external_call_intent SET state='SUCCEEDED' "
                + "WHERE tenant_id='%s'".formatted(tenantId),
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api",
            tenantId,
            "SELECT intent_id FROM public.external_call_intent",
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            tenantId,
            "SELECT execution_owner,execution_token,execution_lease_until "
                + "FROM public.external_call_intent",
            "42501");
    }

    @Test
    void runtimeFunctionExecuteAllowlistBlocksCrossCapabilityCalls() throws Exception {
        UUID tenantId = UUID.randomUUID();
        assertRuntimeSqlState(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api",
            tenantId,
            "SELECT * FROM accord_security.create_external_intent_successor("
                + "'%s'::uuid,'%s'::uuid,'%s'::uuid)".formatted(
                    tenantId, UUID.randomUUID(), UUID.randomUUID()),
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api",
            tenantId,
            ("SELECT * FROM accord_security.claim_external_intent_execution("
                + "'%s'::uuid,'%s'::uuid,'api-owner'::varchar,"
                + "1000000::bigint,'%s'::uuid)").formatted(
                tenantId, UUID.randomUUID(), UUID.randomUUID()),
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            tenantId,
            ("SELECT * FROM accord_security.record_external_intent("
                + "'%s'::uuid,'%s'::uuid,'worker-root-key-0001'::varchar,"
                + "'repository'::varchar,'repository-1'::varchar,'gitlab'::varchar,"
                + "'installation-1'::varchar,'repository-1'::varchar,"
                + "'git.branch.create'::varchar,'delivery-work-item'::varchar,"
                + "'work-item-1'::varchar,1::bigint,"
                + "('sha256:' || repeat('a',64))::char(71))").formatted(
                tenantId, UUID.randomUUID()),
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            tenantId,
            "SELECT accord_security.guard_external_intent_successor()",
            "42501");
        assertRuntimeSqlState(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            tenantId,
            "SELECT accord_security.guard_external_intent_transition()",
            "42501");
    }

    @Test
    void apiRuntimeUsesTheAuthoritativeTenantContextForAllowedRootRecording()
            throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        try (Connection connection = runtimeConnection(
                 ControlPlaneTestRoles.API_LOGIN,
                 ControlPlaneTestRoles.API_PASSWORD,
                 "accord_api",
                 tenantId);
             Statement identityStatement = connection.createStatement();
             ResultSet identity = identityStatement.executeQuery("""
                 SELECT session_user,current_user,
                        current_setting('app.tenant_id', true)::uuid,
                        accord_security.current_tenant_id()
                 """)) {
            assertThat(identity.next()).isTrue();
            assertThat(identity.getString(1)).isEqualTo(ControlPlaneTestRoles.API_LOGIN);
            assertThat(identity.getString(2)).isEqualTo("accord_api");
            assertThat(identity.getObject(3, UUID.class)).isEqualTo(tenantId);
            assertThat(identity.getObject(4, UUID.class)).isEqualTo(tenantId);

            try (PreparedStatement record = connection.prepareStatement("""
                SELECT disposition,result_tenant_id,result_intent_id
                FROM accord_security.record_external_intent(
                  CAST(? AS uuid),CAST(? AS uuid),CAST(? AS varchar),
                  CAST(? AS varchar),CAST(? AS varchar),CAST(? AS varchar),
                  CAST(? AS varchar),CAST(? AS varchar),CAST(? AS varchar),
                  CAST(? AS varchar),CAST(? AS varchar),CAST(? AS bigint),
                  CAST(? AS char(71)))
                """)) {
                record.setObject(1, tenantId);
                record.setObject(2, intentId);
                record.setString(3, "api-root-key-000001");
                record.setString(4, "repository");
                record.setString(5, "repository-1");
                record.setString(6, "gitlab");
                record.setString(7, "installation-1");
                record.setString(8, "repository-1");
                record.setString(9, "git.branch.create");
                record.setString(10, "delivery-work-item");
                record.setString(11, "work-item-1");
                record.setLong(12, 1);
                record.setString(13, "sha256:" + "a".repeat(64));
                try (ResultSet result = record.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("CREATED");
                    assertThat(result.getObject(2, UUID.class)).isEqualTo(tenantId);
                    assertThat(result.getObject(3, UUID.class)).isEqualTo(intentId);
                    assertThat(result.next()).isFalse();
                }
            }
            connection.commit();
        }
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(number(statement, """
                SELECT count(*) FROM external_call_intent
                WHERE tenant_id='%s' AND intent_id='%s'
                """.formatted(tenantId, intentId))).isEqualTo(1);
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
    void logicalActionKeyRejectsSpacesAndFreeText() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(
                SQLException.class,
                () -> insertRoot(statement, "action key with spaces"));
            assertThat(error.getSQLState()).isEqualTo("23514");
            assertThat(error.getMessage())
                .contains("external_call_intent_logical_action_key_format");
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void assertRuntimeSqlState(
            String login,
            String password,
            String role,
            UUID tenantId,
            String sql,
            String expectedSqlState) throws Exception {
        try (Connection connection = runtimeConnection(login, password, role, tenantId);
             Statement statement = connection.createStatement()) {
            try (ResultSet identity = statement.executeQuery(
                    "SELECT session_user,current_user")) {
                assertThat(identity.next()).isTrue();
                assertThat(identity.getString(1)).isEqualTo(login);
                assertThat(identity.getString(2)).isEqualTo(role);
            }
            SQLException error = assertThrows(
                SQLException.class,
                () -> statement.execute(sql));
            assertThat(error.getSQLState()).isEqualTo(expectedSqlState);
        }
    }

    private Connection runtimeConnection(
            String login, String password, String role, UUID tenantId) throws Exception {
        Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), login, password);
        connection.setAutoCommit(false);
        try (Statement roleStatement = connection.createStatement();
             PreparedStatement tenantStatement = connection.prepareStatement(
                 "SELECT set_config('app.tenant_id', ?, true)")) {
            roleStatement.execute("SET ROLE " + role);
            tenantStatement.setString(1, tenantId.toString());
            tenantStatement.executeQuery();
        } catch (Exception error) {
            connection.close();
            throw error;
        }
        return connection;
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

    private static List<String> functionOutputColumns(
            Statement statement, String functionNames) throws Exception {
        return strings(statement, """
            SELECT procedure.proname || ':' || procedure.proargnames[argument.ordinal]
                   || ':' || pg_catalog.format_type(
                     procedure.proallargtypes[argument.ordinal],NULL)
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid=procedure.pronamespace
            CROSS JOIN LATERAL pg_catalog.generate_subscripts(
              procedure.proallargtypes,1) argument(ordinal)
            WHERE namespace.nspname='accord_security'
              AND procedure.proname IN (%s)
              AND procedure.proargmodes[argument.ordinal] IN ('o','t')
            ORDER BY procedure.proname,argument.ordinal
            """.formatted(functionNames));
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
