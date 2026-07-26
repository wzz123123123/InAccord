package com.inforvans.accord.reliability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inforvans.accord.database.ControlPlaneTestRoles;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
abstract class PostgreSqlReliabilityTestSupport {
    protected static final UUID TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    protected static final UUID OTHER_TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302")
        .asCompatibleSubstituteFor("postgres");

    protected PostgreSQLContainer<?> postgres;

    @BeforeAll
    void startDatabase() {
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
    void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clearRows() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                TRUNCATE TABLE external_call_intent, inbox_message, outbox_event,
                  domain_event, aggregate_head, idempotency_result CASCADE
                """);
        }
    }

    protected <T> T inApi(UUID tenantId, SqlWork<T> work) throws Exception {
        return inRole(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api",
            tenantId,
            work);
    }

    protected <T> T inWorker(UUID tenantId, SqlWork<T> work) throws Exception {
        return inRole(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            tenantId,
            work);
    }

    protected Connection openWorker(UUID tenantId) throws Exception {
        return openRole(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            tenantId);
    }

    protected Connection openApi(UUID tenantId) throws Exception {
        return openRole(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api",
            tenantId);
    }

    protected <T> T runTransaction(Connection connection, SqlWork<T> work)
            throws Exception {
        DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
        try {
            T result = work.run(tx);
            connection.commit();
            return result;
        } catch (Throwable error) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throwFailure(error);
            return null;
        }
    }

    private Connection openRole(
            String login, String password, String roleName, UUID tenantId) throws Exception {
        Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), login, password);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + roleName);
        }
        setTenant(connection, tenantId);
        return connection;
    }

    protected Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    protected int count(String table) throws Exception {
        if (!table.matches("^[a-z_]+$")) {
            throw new IllegalArgumentException("unsafe table name");
        }
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                 "SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    protected void awaitDatabaseAfter(OffsetDateTime instant) throws Exception {
        long timeout = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < timeout) {
            try (Connection connection = adminConnection();
                 PreparedStatement query = connection.prepareStatement(
                     "SELECT clock_timestamp() > CAST(? AS timestamptz)")) {
                query.setObject(1, instant);
                try (java.sql.ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    if (rows.getBoolean(1)) {
                        return;
                    }
                }
            }
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
        }
        throw new IllegalStateException("database clock did not pass the deadline");
    }

    protected static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private <T> T inRole(
            String login,
            String password,
            String role,
            UUID tenantId,
            SqlWork<T> work) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                 postgres.getJdbcUrl(), login, password)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + role);
            }
            setTenant(connection, tenantId);
            DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
            try {
                T result = work.run(tx);
                connection.commit();
                return result;
            } catch (Throwable error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throwFailure(error);
                return null;
            }
        }
    }

    private static void setTenant(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement context = connection.prepareStatement(
                 "SELECT set_config('app.tenant_id', ?, true)")) {
            context.setString(1, tenantId.toString());
            try (java.sql.ResultSet result = context.executeQuery()) {
                assertTrue(result.next());
            }
        }
    }

    private static void throwFailure(Throwable error) throws Exception {
        if (error instanceof Error fatal) {
            throw fatal;
        }
        if (error instanceof Exception exception) {
            throw exception;
        }
        throw new IllegalStateException("unexpected throwable", error);
    }

    @FunctionalInterface
    protected interface SqlWork<T> {
        T run(DSLContext tx) throws Exception;
    }
}
