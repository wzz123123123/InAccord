package com.inforvans.accord.controlplane.worker.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.inforvans.accord.database.ControlPlaneTestRoles;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

final class TemporalMtlsTestServer implements AutoCloseable {
    static final String NAMESPACE = "accord-reconciliation-test-v1";
    static final String SERVER_SOURCE = "temporalio/server:1.28.1";
    static final String SERVER_REPOSITORY_DIGEST =
        "sha256:acaf8454947544312216c6e153929b7db060571f736bbced290bfaf76e287499";
    static final String SERVER_AMD64_MANIFEST =
        "sha256:0842f5e71b5c935adad01d133457d886e1748a675f79f5cbb6758aee5031dcb0";
    static final String ADMIN_TOOLS_SOURCE =
        "temporalio/admin-tools:1.28.1-tctl-1.18.4-cli-1.4.1";
    static final String ADMIN_TOOLS_REPOSITORY_DIGEST =
        "sha256:01537b62d995f27a0f0d33a01ac4caa6779622f454fb2eb36fed0dccd45c6244";
    static final String ADMIN_TOOLS_AMD64_MANIFEST =
        "sha256:00864ac86e79aec0d418582892d3435564c613b68b72fe2e41e9c50176794983";
    static final String POSTGRES_SOURCE =
        "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302";

    private static final DockerImageName SERVER_IMAGE =
        DockerImageName.parse(SERVER_SOURCE);
    private static final DockerImageName ADMIN_TOOLS_IMAGE =
        DockerImageName.parse(ADMIN_TOOLS_SOURCE);
    private static final DockerImageName POSTGRES_IMAGE =
        DockerImageName.parse(POSTGRES_SOURCE).asCompatibleSubstituteFor("postgres");
    private static final String TEMPORAL_DATABASE_PASSWORD = "temporal-schema-test-only";
    private static final String TEMPORAL_DATABASE_USER = "temporal_schema_admin";

    private static final List<List<String>> SCHEMA_COMMANDS = List.of(
        List.of("/usr/local/bin/temporal-sql-tool", "--endpoint", "temporal-postgres",
            "--port", "5432", "--user", TEMPORAL_DATABASE_USER,
            "--database", "temporal", "--plugin", "postgres12",
            "create-database", "--defaultdb", "postgres"),
        List.of("/usr/local/bin/temporal-sql-tool", "--endpoint", "temporal-postgres",
            "--port", "5432", "--user", TEMPORAL_DATABASE_USER,
            "--database", "temporal", "--plugin", "postgres12",
            "setup-schema", "-v", "0.0"),
        List.of("/usr/local/bin/temporal-sql-tool", "--endpoint", "temporal-postgres",
            "--port", "5432", "--user", TEMPORAL_DATABASE_USER,
            "--database", "temporal", "--plugin", "postgres12",
            "update-schema", "-d",
            "/etc/temporal/schema/postgresql/v12/temporal/versioned"),
        List.of("/usr/local/bin/temporal-sql-tool", "--endpoint", "temporal-postgres",
            "--port", "5432", "--user", TEMPORAL_DATABASE_USER,
            "--database", "temporal_visibility", "--plugin", "postgres12",
            "create-database", "--defaultdb", "postgres"),
        List.of("/usr/local/bin/temporal-sql-tool", "--endpoint", "temporal-postgres",
            "--port", "5432", "--user", TEMPORAL_DATABASE_USER,
            "--database", "temporal_visibility", "--plugin", "postgres12",
            "setup-schema", "-v", "0.0"),
        List.of("/usr/local/bin/temporal-sql-tool", "--endpoint", "temporal-postgres",
            "--port", "5432", "--user", TEMPORAL_DATABASE_USER,
            "--database", "temporal_visibility", "--plugin", "postgres12",
            "update-schema", "-d",
            "/etc/temporal/schema/postgresql/v12/visibility/versioned"));

    private final TemporalTestCertificates.Material certificates;
    private final TemporalTestCertificates.CertificateFiles serverIdentity;
    private Network network;
    private PostgreSQLContainer<?> controlPostgres;
    private PostgreSQLContainer<?> temporalPostgres;
    private GenericContainer<?> temporalServer;

    TemporalMtlsTestServer(TemporalTestCertificates.Material certificates) {
        this(certificates, certificates.server());
    }

    TemporalMtlsTestServer(
            TemporalTestCertificates.Material certificates,
            TemporalTestCertificates.CertificateFiles serverIdentity) {
        this.certificates = Objects.requireNonNull(certificates, "certificates");
        this.serverIdentity = Objects.requireNonNull(serverIdentity, "serverIdentity");
    }

    void start() {
        if (network != null) {
            throw new IllegalStateException("Temporal test server already started");
        }
        network = Network.newNetwork();
        try {
            controlPostgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("accord")
                .withNetwork(network)
                .withNetworkAliases("control-postgres");
            temporalPostgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername(TEMPORAL_DATABASE_USER)
                .withPassword(TEMPORAL_DATABASE_PASSWORD)
                .withNetwork(network)
                .withNetworkAliases("temporal-postgres");
            controlPostgres.start();
            migrateControlPlane();
            temporalPostgres.start();
            for (List<String> command : SCHEMA_COMMANDS) {
                runOneShot(command, List.of());
            }
            temporalServer = configuredServer();
            temporalServer.start();
            createNamespace();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    String endpoint() {
        requireStarted();
        return "grpcs://" + temporalServer.getHost()
            + ":" + temporalServer.getMappedPort(7233);
    }

    String controlJdbcUrl() {
        requireStarted();
        return controlPostgres.getJdbcUrl();
    }

    String controlWorkerUser() {
        return ControlPlaneTestRoles.WORKER_LOGIN;
    }

    String controlWorkerPassword() {
        return ControlPlaneTestRoles.WORKER_PASSWORD;
    }

    static List<List<String>> schemaCommands() {
        return SCHEMA_COMMANDS;
    }

    private void migrateControlPlane() {
        ControlPlaneTestRoles.bootstrap(
            controlPostgres.getJdbcUrl(),
            controlPostgres.getUsername(),
            controlPostgres.getPassword());
        Flyway flyway = Flyway.configure()
            .dataSource(controlPostgres.getJdbcUrl(),
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD)
            .initSql("SET ROLE accord_migrator")
            .locations("classpath:db/migration")
            .target("003")
            .load();
        flyway.migrate();
        assertThat(Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().toString()))
            .containsExactly("001", "002", "003");
    }

    private GenericContainer<?> configuredServer() {
        GenericContainer<?> server = new GenericContainer<>(SERVER_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("temporal")
            .withExposedPorts(7233)
            .withEnv("DB", "postgres12")
            .withEnv("DB_PORT", "5432")
            .withEnv("POSTGRES_SEEDS", "temporal-postgres")
            .withEnv("POSTGRES_USER", TEMPORAL_DATABASE_USER)
            .withEnv("POSTGRES_PWD", TEMPORAL_DATABASE_PASSWORD)
            .withEnv("DBNAME", "temporal")
            .withEnv("VISIBILITY_DBNAME", "temporal_visibility")
            .withEnv("BIND_ON_IP", "0.0.0.0")
            .withEnv("SKIP_SCHEMA_SETUP", "true")
            .withEnv("TEMPORAL_TLS_REQUIRE_CLIENT_AUTH", "true")
            .withEnv("TEMPORAL_TLS_SERVER_CERT", "/run/accord/server-cert.pem")
            .withEnv("TEMPORAL_TLS_SERVER_KEY", "/run/accord/server-key.pem")
            .withEnv("TEMPORAL_TLS_SERVER_CA_CERT", "/run/accord/trusted-ca.pem")
            .withEnv("TEMPORAL_TLS_FRONTEND_CERT", "/run/accord/server-cert.pem")
            .withEnv("TEMPORAL_TLS_FRONTEND_KEY", "/run/accord/server-key.pem")
            .withEnv("TEMPORAL_TLS_CLIENT1_CA_CERT", "/run/accord/trusted-ca.pem")
            .withEnv("TEMPORAL_TLS_CLIENT2_CA_CERT", "/run/accord/trusted-ca.pem")
            .withEnv("TEMPORAL_TLS_INTERNODE_SERVER_NAME",
                TemporalTestCertificates.SERVER_NAME)
            .withEnv("TEMPORAL_TLS_FRONTEND_SERVER_NAME",
                TemporalTestCertificates.SERVER_NAME)
            .withCopyFileToContainer(
                MountableFile.forHostPath(serverIdentity.certificate()),
                "/run/accord/server-cert.pem")
            .withCopyFileToContainer(
                MountableFile.forHostPath(serverIdentity.privateKey()),
                "/run/accord/server-key.pem")
            .withCopyFileToContainer(
                MountableFile.forHostPath(certificates.trustedCa()),
                "/run/accord/trusted-ca.pem")
            .waitingFor(Wait.forListeningPort()
                .withStartupTimeout(Duration.ofMinutes(2)));
        return server;
    }

    private void createNamespace() {
        List<String> command = List.of(
            "/usr/local/bin/temporal",
            "--address", "temporal:7233",
            "--tls-ca-path", "/run/accord/trusted-ca.pem",
            "--tls-cert-path", "/run/accord/client-cert.pem",
            "--tls-key-path", "/run/accord/client-key.pem",
            "--tls-server-name", TemporalTestCertificates.SERVER_NAME,
            "--command-timeout", "10s",
            "operator", "namespace", "create",
            "--namespace", NAMESPACE,
            "--retention", "72h");
        runOneShot(command, List.of(
            new Copy(certificates.trustedCa(), "/run/accord/trusted-ca.pem"),
            new Copy(certificates.client().certificate(), "/run/accord/client-cert.pem"),
            new Copy(certificates.client().privateKey(), "/run/accord/client-key.pem")));
    }

    private void runOneShot(List<String> command, List<Copy> copies) {
        if (command.isEmpty() || !command.get(0).startsWith("/usr/local/bin/")) {
            throw new IllegalArgumentException("one-shot executable must be absolute");
        }
        try (GenericContainer<?> container = new GenericContainer<>(ADMIN_TOOLS_IMAGE)
                .withNetwork(network)
                .withEnv("SQL_PASSWORD", TEMPORAL_DATABASE_PASSWORD)
                .withCreateContainerCmdModifier(create ->
                    create.withEntrypoint(command.get(0)))
                .withCommand(command.subList(1, command.size()).toArray(String[]::new))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy())) {
            for (Copy copy : copies) {
                container.withCopyFileToContainer(
                    MountableFile.forHostPath(copy.source()), copy.target());
            }
            container.start();
            assertThat(container.getCurrentContainerInfo().getState().getExitCodeLong())
                .isZero();
            assertThat(container.getLogs()).doesNotContain(TEMPORAL_DATABASE_PASSWORD);
        }
    }

    private void requireStarted() {
        if (temporalServer == null || !temporalServer.isRunning()) {
            throw new IllegalStateException("Temporal test server is not running");
        }
    }

    @Override
    public void close() {
        if (temporalServer != null) {
            temporalServer.stop();
            temporalServer = null;
        }
        if (temporalPostgres != null) {
            temporalPostgres.stop();
            temporalPostgres = null;
        }
        if (controlPostgres != null) {
            controlPostgres.stop();
            controlPostgres = null;
        }
        if (network != null) {
            network.close();
            network = null;
        }
    }

    private record Copy(Path source, String target) {
    }
}
