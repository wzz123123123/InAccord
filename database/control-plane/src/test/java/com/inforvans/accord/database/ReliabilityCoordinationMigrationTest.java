package com.inforvans.accord.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
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
import org.testcontainers.utility.DockerImageName;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ReliabilityCoordinationMigrationTest {
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302")
        .asCompatibleSubstituteFor("postgres");
    private PostgreSQLContainer<?> postgres;

    @BeforeAll
    void migrateThroughV004() throws Exception {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
        postgres.start();
        ControlPlaneTestRoles.bootstrap(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        flyway("003").migrate();
        seedLegacyReadyRows();
        flyway("004").migrate();
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void recordsTheFourImmutableMigrationSnapshotsInOrder() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT version || ':' || checksum || ':' || success
                FROM flyway_schema_history WHERE version IS NOT NULL
                ORDER BY installed_rank
                """)).containsExactly(
                    "001:-1912123152:true",
                    "002:997992209:true",
                    "003:2145439011:true",
                    "004:" + checksum(statement, "004") + ":true");
        }
    }

    @Test
    void createsDirectoryReceiptsFencesAndStrictTransitionTriggers() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN (
                  'reliability_tenant_work','outbox_delivery_receipt',
                  'inbox_handler_receipt') ORDER BY table_name
                """)).containsExactly(
                    "inbox_handler_receipt", "outbox_delivery_receipt",
                    "reliability_tenant_work");
            assertThat(strings(statement, """
                SELECT table_name || ':' || column_name
                FROM information_schema.columns
                WHERE table_schema='public' AND column_name IN (
                  'lease_generation','lease_token')
                  AND table_name IN ('outbox_event','inbox_message')
                ORDER BY table_name,column_name
                """)).containsExactly(
                    "inbox_message:lease_generation", "inbox_message:lease_token",
                    "outbox_event:lease_generation", "outbox_event:lease_token");
            assertThat(strings(statement, """
                SELECT DISTINCT event_object_table || ':' || trigger_name
                FROM information_schema.triggers
                WHERE trigger_name IN ('outbox_v004_transition','inbox_v004_transition')
                ORDER BY 1
                """)).containsExactly(
                    "inbox_message:inbox_v004_transition",
                    "outbox_event:outbox_v004_transition");
            assertThat(strings(statement, """
                SELECT relname FROM pg_class
                WHERE relname IN (
                  'outbox_event','inbox_message','idempotency_result',
                  'outbox_delivery_receipt','inbox_handler_receipt')
                  AND relrowsecurity AND relforcerowsecurity ORDER BY relname
                """)).containsExactly(
                    "idempotency_result", "inbox_handler_receipt", "inbox_message",
                    "outbox_delivery_receipt", "outbox_event");
        }
    }

    @Test
    void backfillsPayloadFreeWorkAndKeepsDirectoryUninspectableByRuntimeRoles()
            throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertThat(number(statement,
                "SELECT count(*) FROM reliability_tenant_work")).isEqualTo(2);
            assertThat(number(statement, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='reliability_tenant_work'
                  AND column_name IN ('payload','destination','handler_key')
                """)).isZero();
        }
        try (Connection connection = runtimeConnection(
                ControlPlaneTestRoles.WORKER_LOGIN,
                ControlPlaneTestRoles.WORKER_PASSWORD, "accord_worker", null);
             Statement statement = connection.createStatement()) {
            SQLException denied = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT * FROM reliability_tenant_work"));
            assertThat(denied.getSQLState()).isEqualTo("42501");
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD)
            .initSql("SET ROLE accord_migrator")
            .locations("classpath:db/migration")
            .target(target)
            .load();
    }

    private void seedLegacyReadyRows() throws Exception {
        try (Connection connection = runtimeConnection(
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD, "accord_migrator", null);
             Statement statement = connection.createStatement()) {
            for (int index = 1; index <= 2; index++) {
                UUID tenant = UUID.fromString(
                    "10000000-0000-0000-0000-%012d".formatted(index));
                statement.execute("SELECT set_config('app.tenant_id','" + tenant + "',true)");
                UUID event = UUID.randomUUID();
                statement.executeUpdate("""
                    INSERT INTO domain_event (
                      tenant_id,event_id,scope_type,scope_id,aggregate_type,aggregate_id,
                      sequence,event_type,schema_version,causation_id,correlation_id,
                      actor_id,payload,occurred_at)
                    VALUES ('%s','%s','tenant','%s','migration-test','%s',1,
                      'migration.tested','1.0.0','%s','%s','actor','{}',clock_timestamp())
                    """.formatted(tenant, event, tenant, UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID()));
                statement.executeUpdate("""
                    INSERT INTO outbox_event (
                      tenant_id,event_id,destination,payload_schema,payload,available_at)
                    VALUES ('%s','%s','migration','migration/1.0','{}',
                      clock_timestamp()+interval '%s seconds')
                    """.formatted(tenant, event, index));
            }
            connection.commit();
        }
    }

    private Connection runtimeConnection(
            String login, String password, String role, UUID tenant) throws Exception {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), login, password);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
            if (tenant != null) {
                statement.execute("SELECT set_config('app.tenant_id','" + tenant + "',true)");
            }
        }
        return connection;
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String checksum(Statement statement, String version) throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT checksum FROM flyway_schema_history WHERE version='" + version + "'")) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static List<String> strings(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return List.copyOf(values);
        }
    }

    private static long number(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
}
