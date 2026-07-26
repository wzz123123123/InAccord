package com.inforvans.accord.webhookedge.inbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class WebhookDatabaseFixture implements AutoCloseable {
    static final String OWNER = "accord_webhook_owner";
    static final String MIGRATOR = "accord_webhook_migrator_login";
    static final String MIGRATOR_PASSWORD = "edge-migrator-test";
    static final String RUNTIME = "accord_webhook_runtime";
    static final String RUNTIME_LOGIN = "accord_webhook_runtime_login";
    static final String RUNTIME_PASSWORD = "edge-runtime-test";

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
                    "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302")
            .asCompatibleSubstituteFor("postgres");

    private PostgreSQLContainer<?> postgres;

    public void start() {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        bootstrap();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD)
                .initSql("SET ROLE " + OWNER)
                .defaultSchema("public")
                .locations("classpath:webhook-edge/migration")
                .load()
                .migrate();
    }

    public String jdbcUrl() {
        ensureStarted();
        return postgres.getJdbcUrl();
    }

    public String runtimeUsername() {
        return RUNTIME_LOGIN;
    }

    public String runtimePassword() {
        return RUNTIME_PASSWORD;
    }

    DSLContext runtimeDsl() {
        ensureStarted();
        return DSL.using(
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), RUNTIME_LOGIN, RUNTIME_PASSWORD, RUNTIME),
                SQLDialect.POSTGRES);
    }

    Connection adminConnection() throws SQLException {
        ensureStarted();
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    Connection runtimeConnection(UUID tenantId) throws SQLException {
        Connection connection = runtimeConnectionWithoutTenant();
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId.toString());
            statement.executeQuery();
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    Connection runtimeConnectionWithoutTenant() throws SQLException {
        ensureStarted();
        Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), RUNTIME_LOGIN, RUNTIME_PASSWORD);
        configureRuntimeSession(connection, RUNTIME);
        connection.setAutoCommit(false);
        return connection;
    }

    void truncate() throws SQLException {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE public.webhook_outbox, public.webhook_inbox");
        }
    }

    void rebootstrap() {
        bootstrap();
    }

    long adminCount(String table) throws SQLException {
        if (!table.equals("webhook_inbox") && !table.equals("webhook_outbox")) {
            throw new IllegalArgumentException("unsafe edge table");
        }
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement();
                java.sql.ResultSet rows = statement.executeQuery(
                        "SELECT pg_catalog.count(*) FROM public." + table)) {
            if (!rows.next()) {
                throw new IllegalStateException("count returned no row");
            }
            return rows.getLong(1);
        }
    }

    @Override
    public void close() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private void bootstrap() {
        try (InputStream resource = WebhookDatabaseFixture.class.getResourceAsStream(
                        "/webhook-edge/bootstrap/00-pre-flyway-roles.sql");
                Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            if (resource == null) {
                throw new IllegalStateException("packaged webhook edge role bootstrap is missing");
            }
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\\set ON_ERROR_STOP on", "");
            statement.execute(sql);
            statement.execute("ALTER ROLE " + MIGRATOR + " PASSWORD '" + MIGRATOR_PASSWORD + "'");
            statement.execute("ALTER ROLE " + RUNTIME_LOGIN + " PASSWORD '" + RUNTIME_PASSWORD + "'");
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("cannot bootstrap webhook edge test database", exception);
        }
    }

    private void ensureStarted() {
        if (postgres == null || !postgres.isRunning()) {
            throw new IllegalStateException("webhook edge test database is not running");
        }
    }

    private static Connection configureRuntimeSession(Connection connection, String sessionRole)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + sessionRole);
            statement.execute("SET statement_timeout = '5s'");
            statement.execute("SET lock_timeout = '2s'");
            statement.execute("SET idle_in_transaction_session_timeout = '10s'");
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private static final class DriverManagerDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;
        private final String sessionRole;

        private DriverManagerDataSource(String url, String user, String password, String sessionRole) {
            this.url = Objects.requireNonNull(url, "url");
            this.user = Objects.requireNonNull(user, "user");
            this.password = Objects.requireNonNull(password, "password");
            this.sessionRole = Objects.requireNonNull(sessionRole, "sessionRole");
        }

        @Override
        public Connection getConnection() throws SQLException {
            return assumeSessionRole(DriverManager.getConnection(url, user, password));
        }

        @Override
        public Connection getConnection(String requestedUser, String requestedPassword)
                throws SQLException {
            return assumeSessionRole(DriverManager.getConnection(url, requestedUser, requestedPassword));
        }

        private Connection assumeSessionRole(Connection connection) throws SQLException {
            return configureRuntimeSession(connection, sessionRole);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("global");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
