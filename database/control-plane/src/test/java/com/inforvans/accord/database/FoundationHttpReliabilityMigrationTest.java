package com.inforvans.accord.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FoundationHttpReliabilityMigrationTest {
    private static final String POSTGRES_IMAGE = "postgres:17.5";
    private static final String SCHEMA_ID =
        "https://schemas.accord.inforvans.com/events/domain-event/1-0-0";
    private static final String DIGEST = "sha256:" + "a".repeat(64);

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
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void contractValidationHasTheExactEightColumnCatalogAndConstraints() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT column_name || ':' || udt_name || ':' || is_nullable || ':'
                       || COALESCE(character_maximum_length::text, '-') || ':'
                       || is_generated
                FROM information_schema.columns
                WHERE table_schema='public' AND table_name='contract_validation'
                ORDER BY ordinal_position
                """)).containsExactly(
                    "tenant_id:uuid:NO:-:NEVER",
                    "validation_id:uuid:NO:-:NEVER",
                    "aggregate_type:varchar:YES:64:ALWAYS",
                    "schema_id:varchar:NO:512:NEVER",
                    "document_digest:bpchar:NO:71:NEVER",
                    "version:int8:NO:-:NEVER",
                    "created_at:timestamptz:NO:-:NEVER",
                    "updated_at:timestamptz:NO:-:NEVER");
            assertThat(string(statement, """
                SELECT generation_expression
                FROM information_schema.columns
                WHERE table_schema='public' AND table_name='contract_validation'
                  AND column_name='aggregate_type'
                """)).isEqualTo("'contract-validation'::character varying");
            assertThat(strings(statement, """
                SELECT column_name || ':' || column_default
                FROM information_schema.columns
                WHERE table_schema='public' AND table_name='contract_validation'
                  AND column_name IN ('created_at','updated_at')
                ORDER BY column_name
                """)).containsExactly(
                    "created_at:transaction_timestamp()",
                    "updated_at:transaction_timestamp()");
            assertThat(strings(statement, """
                SELECT constraint_row.conname || ':' || constraint_row.contype::text || ':'
                       || pg_catalog.pg_get_constraintdef(constraint_row.oid, true)
                FROM pg_catalog.pg_constraint constraint_row
                WHERE constraint_row.conrelid='public.contract_validation'::regclass
                ORDER BY constraint_row.conname
                """)).containsExactly(
                    "contract_validation_document_digest_format:c:CHECK (document_digest ~ '^sha256:[0-9a-f]{64}$'::text)",
                    "contract_validation_head_fkey:f:FOREIGN KEY (tenant_id, aggregate_type, validation_id) REFERENCES aggregate_head(tenant_id, aggregate_type, aggregate_id)",
                    "contract_validation_pkey:p:PRIMARY KEY (tenant_id, validation_id)",
                    "contract_validation_schema_id_known:c:CHECK (schema_id::text ~ '^https://schemas[.]accord[.]inforvans[.]com/[A-Za-z0-9._~:/-]+$'::text AND (octet_length(schema_id::text) - octet_length('https://schemas.accord.inforvans.com/'::text)) >= 1 AND (octet_length(schema_id::text) - octet_length('https://schemas.accord.inforvans.com/'::text)) <= 448)",
                    "contract_validation_timestamps_ordered:c:CHECK (updated_at >= created_at)",
                    "contract_validation_version_positive:c:CHECK (version >= 1)");
            assertThat(strings(statement, """
                SELECT constraint_row.conname || ':'
                       || pg_catalog.pg_get_constraintdef(constraint_row.oid, true)
                FROM pg_catalog.pg_constraint constraint_row
                WHERE constraint_row.conrelid='public.idempotency_result'::regclass
                  AND constraint_row.conname IN (
                    'idempotency_result_aggregate_binding_consistent',
                    'idempotency_result_detached_status_known')
                ORDER BY constraint_row.conname
                """)).containsExactly(
                    "idempotency_result_aggregate_binding_consistent:CHECK (aggregate_type IS NULL AND aggregate_id IS NULL AND aggregate_version IS NULL OR aggregate_type IS NOT NULL AND aggregate_id IS NOT NULL AND aggregate_version IS NOT NULL)",
                    "idempotency_result_detached_status_known:CHECK (state::text <> 'COMPLETED'::text OR aggregate_type IS NOT NULL OR (response_status = ANY (ARRAY[404, 412, 422])))");
            assertThat(number(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_roles owner ON owner.oid=relation.relowner
                WHERE relation.oid='public.contract_validation'::regclass
                  AND owner.rolname='accord_migrator'
                """)).isEqualTo(1);
            assertThat(strings(statement, """
                SELECT index.relname || ':' || catalog_index.indisunique || ':'
                       || catalog_index.indisvalid || ':' || catalog_index.indisready
                       || ':' || pg_catalog.pg_get_indexdef(index.oid)
                FROM pg_catalog.pg_index catalog_index
                JOIN pg_catalog.pg_class index
                  ON index.oid=catalog_index.indexrelid
                WHERE catalog_index.indrelid='public.contract_validation'::regclass
                ORDER BY index.relname
                """)).containsExactly(
                    "contract_validation_pkey:true:true:true:"
                        + "CREATE UNIQUE INDEX contract_validation_pkey "
                        + "ON public.contract_validation USING btree "
                        + "(tenant_id, validation_id)");
        }
    }

    @Test
    void triggerIsInvokerOnlyAndGuardsInsertAndUpdateAgainstAggregateHead() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT trigger_row.tgname || ':' || trigger_row.tgtype || ':'
                       || procedure.proname
                FROM pg_catalog.pg_trigger trigger_row
                JOIN pg_catalog.pg_proc procedure ON procedure.oid=trigger_row.tgfoid
                WHERE trigger_row.tgrelid='public.contract_validation'::regclass
                  AND NOT trigger_row.tgisinternal
                ORDER BY trigger_row.tgname
                """)).containsExactly(
                    "contract_validation_guard:23:guard_contract_validation_version");
            assertThat(strings(statement, """
                SELECT procedure.proname || ':' || owner.rolname || ':'
                       || procedure.prosecdef || ':'
                       || pg_catalog.array_to_string(procedure.proconfig, ',')
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid=procedure.pronamespace
                JOIN pg_catalog.pg_roles owner ON owner.oid=procedure.proowner
                WHERE namespace.nspname='accord_security'
                  AND procedure.proname='guard_contract_validation_version'
                """)).containsExactly(
                    "guard_contract_validation_version:accord_migrator:false:search_path=pg_catalog, pg_temp");
            assertThat(number(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid=procedure.pronamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(
                  COALESCE(procedure.proacl,
                    pg_catalog.acldefault('f', procedure.proowner))) acl
                WHERE namespace.nspname='accord_security'
                  AND procedure.proname='guard_contract_validation_version'
                  AND acl.grantee <> procedure.proowner
                """)).isZero();
            assertThat(string(statement, """
                SELECT pg_catalog.pg_get_functiondef(procedure.oid)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid=procedure.pronamespace
                WHERE namespace.nspname='accord_security'
                  AND procedure.proname='guard_contract_validation_version'
                """)).doesNotContain(
                    "NEW.aggregate_type IS NOT NULL",
                    "OLD.aggregate_type IS DISTINCT FROM NEW.aggregate_type");
        }

        UUID tenantId = UUID.randomUUID();
        UUID validationId = UUID.randomUUID();
        try (Connection api = tenantConnection("accord_api", tenantId)) {
            insertHead(api, tenantId, validationId, 1);
            insertValidation(api, tenantId, validationId, 1, SCHEMA_ID, DIGEST);
            api.commit();
        }
        try (Connection api = tenantConnection("accord_api", tenantId);
             Statement statement = api.createStatement()) {
            assertNamedCheck(api, "contract_validation_head_version_match", () ->
                insertValidation(api, tenantId, UUID.randomUUID(), 1, SCHEMA_ID, DIGEST));

            updateHead(api, tenantId, validationId, 2);
            OffsetDateTime before = timestamp(statement, """
                SELECT updated_at FROM contract_validation
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(tenantId, validationId));
            assertThat(statement.executeUpdate("""
                UPDATE contract_validation
                SET schema_id='%s', document_digest='%s', version=2
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(SCHEMA_ID, "sha256:" + "b".repeat(64), tenantId, validationId)))
                .isEqualTo(1);
            OffsetDateTime after = timestamp(statement, """
                SELECT updated_at FROM contract_validation
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(tenantId, validationId));
            assertThat(after).isAfter(before);
            api.commit();
        }
        try (Connection owner = tenantConnection("accord_migrator", tenantId);
             Statement statement = owner.createStatement()) {
            assertNamedCheck(owner, "contract_validation_immutable", () -> statement.executeUpdate("""
                UPDATE contract_validation SET created_at=created_at - interval '1 second'
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(tenantId, validationId)));
            owner.rollback();
        }
    }

    @Test
    void generatedAggregateTypeRejectsExplicitValuesAndAcceptsDefault() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID validationId = UUID.randomUUID();
        seedValidation(tenantId, validationId);

        try (Connection owner = tenantConnection("accord_migrator", tenantId);
             Statement statement = owner.createStatement()) {
            assertSqlStateWithSavepoint(owner, "428C9", () -> statement.executeUpdate("""
                UPDATE contract_validation
                SET aggregate_type='contract-validation'
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(tenantId, validationId)));

            updateHead(owner, tenantId, validationId, 2);
            assertThat(statement.executeUpdate("""
                UPDATE contract_validation
                SET aggregate_type=DEFAULT, version=2
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(tenantId, validationId))).isEqualTo(1);
            assertThat(string(statement, """
                SELECT aggregate_type || ':' || version
                FROM contract_validation
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(tenantId, validationId)))
                .isEqualTo("contract-validation:2");
            owner.rollback();
        }
    }

    @Test
    void runtimePrivilegesRlsAndNoDeleteAreExact() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT COALESCE(grantee.rolname, 'PUBLIC') || ':'
                       || acl.privilege_type || ':' || acl.is_grantable
                FROM pg_catalog.pg_class relation
                CROSS JOIN LATERAL pg_catalog.aclexplode(
                  COALESCE(relation.relacl,
                    pg_catalog.acldefault('r', relation.relowner))) acl
                LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                WHERE relation.oid='public.contract_validation'::regclass
                  AND acl.grantee <> relation.relowner
                ORDER BY 1
                """)).containsExactly(
                    "accord_api:INSERT:false",
                    "accord_api:SELECT:false");
            assertThat(strings(statement, """
                WITH principals AS (
                  SELECT role.oid, role.rolname
                  FROM pg_catalog.pg_roles role
                  UNION ALL
                  SELECT 0::pg_catalog.oid, 'PUBLIC'
                )
                SELECT principal.rolname || ':' || attribute.attname || ':'
                       || acl.privilege_type || ':' || acl.is_grantable
                FROM pg_catalog.pg_attribute attribute
                CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) acl
                JOIN principals principal ON principal.oid=acl.grantee
                WHERE attribute.attrelid='public.contract_validation'::regclass
                  AND attribute.attnum>0 AND NOT attribute.attisdropped
                ORDER BY 1
                """)).containsExactly(
                    "accord_api:document_digest:UPDATE:false",
                    "accord_api:schema_id:UPDATE:false",
                    "accord_api:version:UPDATE:false");
            assertThat(number(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_class relation
                WHERE relation.oid='public.contract_validation'::regclass
                  AND relation.relrowsecurity AND relation.relforcerowsecurity
                """)).isEqualTo(1);
            assertThat(strings(statement, """
                SELECT policy.polname || ':' || policy.polcmd::text || ':'
                       || pg_catalog.pg_get_expr(policy.polqual, policy.polrelid) || ':'
                       || pg_catalog.pg_get_expr(policy.polwithcheck, policy.polrelid)
                FROM pg_catalog.pg_policy policy
                WHERE policy.polrelid='public.contract_validation'::regclass
                """)).containsExactly(
                    "tenant_isolation:*:(tenant_id = accord_security.current_tenant_id()):"
                        + "(tenant_id = accord_security.current_tenant_id())");
        }

        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID validationA = UUID.randomUUID();
        UUID validationB = UUID.randomUUID();
        seedValidation(tenantA, validationA);
        seedValidation(tenantB, validationB);
        try (Connection api = tenantConnection("accord_api", tenantA);
             Statement statement = api.createStatement()) {
            assertThat(number(statement, "SELECT count(*) FROM contract_validation")).isEqualTo(1);
            assertThat(statement.executeUpdate("""
                UPDATE contract_validation SET schema_id='%s'
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(SCHEMA_ID, tenantB, validationB))).isZero();
            assertSqlStateWithSavepoint(api, "42501", () -> statement.executeUpdate("""
                DELETE FROM contract_validation
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(tenantA, validationA)));
            assertNamedCheck(api, "contract_validation_head_version_match", () ->
                insertValidation(api, tenantB, UUID.randomUUID(), 1, SCHEMA_ID, DIGEST));
            api.rollback();
        }
        try (Connection worker = tenantConnection("accord_worker", tenantA);
             Statement statement = worker.createStatement()) {
            assertSqlState("42501", () -> statement.executeQuery(
                "SELECT count(*) FROM contract_validation"));
            worker.rollback();
        }
    }

    @Test
    void tableChecksAndUpdateShapeRejectEveryInvalidMutation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID validationId = UUID.randomUUID();
        try (Connection api = tenantConnection("accord_api", tenantId);
             Statement statement = api.createStatement()) {
            insertHead(api, tenantId, validationId, 1);
            assertNamedCheck(api, "contract_validation_schema_id_known", () -> insertValidation(
                api, tenantId, validationId, 1, "https://example.com/schema", DIGEST));
            assertNamedCheck(api, "contract_validation_document_digest_format", () -> insertValidation(
                api, tenantId, validationId, 1, SCHEMA_ID, "sha256:" + "g".repeat(64)));
            assertNamedCheck(api, "contract_validation_version_positive", () -> insertValidation(
                api, tenantId, validationId, 0, SCHEMA_ID, DIGEST));

            insertValidation(api, tenantId, validationId, 1, SCHEMA_ID, DIGEST);
            updateHead(api, tenantId, validationId, 2);
            assertNamedCheck(api, "contract_validation_version_step", () -> statement.executeUpdate("""
                UPDATE contract_validation SET version=3
                WHERE tenant_id='%s' AND validation_id='%s'
                """.formatted(tenantId, validationId)));
            api.rollback();
        }
    }

    @Test
    void updateVersionStepStillRequiresTheMatchingAggregateHead() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID validationId = UUID.randomUUID();
        try (Connection api = tenantConnection("accord_api", tenantId);
             Statement statement = api.createStatement()) {
            insertHead(api, tenantId, validationId, 1);
            insertValidation(api, tenantId, validationId, 1, SCHEMA_ID, DIGEST);

            assertNamedCheck(api, "contract_validation_head_version_match", () ->
                statement.executeUpdate("""
                    UPDATE contract_validation
                    SET schema_id='%s', document_digest='%s', version=2
                    WHERE tenant_id='%s' AND validation_id='%s'
                    """.formatted(
                        SCHEMA_ID,
                        "sha256:" + "b".repeat(64),
                        tenantId,
                        validationId)));
            api.rollback();
        }
    }

    @Test
    void detachedCompletionAllowlistAndAggregateBindingConstraintsAreExact() throws Exception {
        UUID tenantId = UUID.randomUUID();
        try (Connection owner = tenantConnection("accord_migrator", tenantId)) {
            for (int status : List.of(404, 412, 422)) {
                insertDetachedCompletion(owner, tenantId, status);
            }
            for (int status : List.of(201, 400, 409, 415, 500)) {
                assertNamedCheck(owner, "idempotency_result_detached_status_known", () ->
                    insertDetachedCompletion(owner, tenantId, status));
            }
            assertNamedCheck(owner, "idempotency_result_aggregate_binding_consistent", () ->
                insertPartialBinding(owner, tenantId));
            owner.rollback();
        }
    }

    private void seedValidation(UUID tenantId, UUID validationId) throws SQLException {
        try (Connection api = tenantConnection("accord_api", tenantId)) {
            insertHead(api, tenantId, validationId, 1);
            insertValidation(api, tenantId, validationId, 1, SCHEMA_ID, DIGEST);
            api.commit();
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Connection tenantConnection(String role, UUID tenantId) throws SQLException {
        String login;
        String password;
        if ("accord_api".equals(role)) {
            login = ControlPlaneTestRoles.API_LOGIN;
            password = ControlPlaneTestRoles.API_PASSWORD;
        } else if ("accord_worker".equals(role)) {
            login = ControlPlaneTestRoles.WORKER_LOGIN;
            password = ControlPlaneTestRoles.WORKER_PASSWORD;
        } else {
            login = ControlPlaneTestRoles.MIGRATOR_LOGIN;
            password = ControlPlaneTestRoles.MIGRATOR_PASSWORD;
        }
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), login, password);
        connection.setAutoCommit(false);
        try (Statement roleStatement = connection.createStatement();
             PreparedStatement tenant = connection.prepareStatement(
                 "SELECT set_config('app.tenant_id', ?, true)")) {
            roleStatement.execute("SET ROLE " + role);
            tenant.setString(1, tenantId.toString());
            tenant.executeQuery().close();
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    private static void insertHead(
            Connection connection, UUID tenantId, UUID validationId, long version)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO aggregate_head (tenant_id,aggregate_type,aggregate_id,version)
            VALUES (?,'contract-validation',?,?)
            """)) {
            insert.setObject(1, tenantId);
            insert.setObject(2, validationId);
            insert.setLong(3, version);
            insert.executeUpdate();
        }
    }

    private static void updateHead(
            Connection connection, UUID tenantId, UUID validationId, long version)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE aggregate_head SET version=?
            WHERE tenant_id=? AND aggregate_type='contract-validation' AND aggregate_id=?
            """)) {
            update.setLong(1, version);
            update.setObject(2, tenantId);
            update.setObject(3, validationId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private static void insertValidation(
            Connection connection,
            UUID tenantId,
            UUID validationId,
            long version,
            String schemaId,
            String digest) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO contract_validation (
              tenant_id,validation_id,schema_id,document_digest,version)
            VALUES (?,?,?,?,?)
            """)) {
            insert.setObject(1, tenantId);
            insert.setObject(2, validationId);
            insert.setString(3, schemaId);
            insert.setString(4, digest);
            insert.setLong(5, version);
            insert.executeUpdate();
        }
    }

    private static void insertDetachedCompletion(
            Connection connection, UUID tenantId, int status) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO idempotency_result (
              tenant_id,actor_id,route_key,idempotency_key,request_fingerprint,
              state,claim_generation,response_status,response_headers,response_body,
              completed_at,expires_at)
            VALUES (?,'actor','route',?,'sha256:%s','COMPLETED',1,?,'{}','',
              transaction_timestamp(),transaction_timestamp()+interval '1 day')
            """.formatted("0".repeat(64)))) {
            insert.setObject(1, tenantId);
            insert.setString(2, UUID.randomUUID().toString());
            insert.setInt(3, status);
            insert.executeUpdate();
        }
    }

    private static void insertPartialBinding(Connection connection, UUID tenantId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO idempotency_result (
              tenant_id,actor_id,route_key,idempotency_key,request_fingerprint,
              state,claim_generation,response_status,response_headers,response_body,
              aggregate_version,completed_at,expires_at)
            VALUES (?,'actor','route',?,'sha256:%s','COMPLETED',1,422,'{}','',1,
              transaction_timestamp(),transaction_timestamp()+interval '1 day')
            """.formatted("0".repeat(64)))) {
            insert.setObject(1, tenantId);
            insert.setString(2, UUID.randomUUID().toString());
            insert.executeUpdate();
        }
    }

    private static void assertNamedCheck(
            Connection connection, String name, SqlOperation operation) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SAVEPOINT expected_failure");
            SQLException error = assertThrows(SQLException.class, operation::run);
            assertThat(error.getMessage()).contains(name);
            statement.execute("ROLLBACK TO SAVEPOINT expected_failure");
        }
    }

    private static SQLException assertSqlState(String state, SqlOperation operation) {
        SQLException error = assertThrows(SQLException.class, operation::run);
        assertThat(error.getSQLState()).isEqualTo(state);
        return error;
    }

    private static void assertSqlStateWithSavepoint(
            Connection connection, String state, SqlOperation operation) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SAVEPOINT expected_sql_state");
            assertSqlState(state, operation);
            statement.execute("ROLLBACK TO SAVEPOINT expected_sql_state");
        }
    }

    private static List<String> strings(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return List.copyOf(values);
        }
    }

    private static String string(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static long number(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static OffsetDateTime timestamp(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getObject(1, OffsetDateTime.class);
        }
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }
}
