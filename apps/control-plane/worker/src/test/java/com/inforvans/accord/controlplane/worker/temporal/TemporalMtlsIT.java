package com.inforvans.accord.controlplane.worker.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import com.inforvans.accord.controlplane.worker.reconciliation.FencedReconciliationObservation;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationFailure;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationRuntimeProperties;
import com.inforvans.accord.controlplane.worker.reconciliation.WorkerTenantTransactions;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReadOnlyReconciliationActivity;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflow;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowRef;
import com.inforvans.accord.database.ControlPlaneTestRoles;
import com.inforvans.accord.platformkernel.CanonicalJson;
import com.inforvans.accord.reliability.ExecutionClaim;
import com.inforvans.accord.reliability.ExecutionResolution;
import com.inforvans.accord.reliability.ExternalIntentDefinition;
import com.inforvans.accord.reliability.ExternalIntentRegistration;
import com.inforvans.accord.reliability.ExternalIntentRef;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import com.inforvans.accord.reliability.ReconciliationResolution;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.History;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

@Timeout(180)
class TemporalMtlsIT {
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsIncompleteOrUnsafeConnectionPropertiesBeforeCreatingStubs() {
        Path root = temporaryDirectory.resolve("secrets");
        Path certificate = root.resolve("client-cert.pem");
        Path key = root.resolve("client-key.pem");
        Path trust = root.resolve("trust.pem");

        List<String> invalidEndpoints = List.of(
            "http://temporal.test:7233",
            "grpc://temporal.test:7233",
            "grpcs://user@temporal.test:7233",
            "grpcs://temporal.test:7233/path",
            "grpcs://temporal.test:7233?query=true",
            "grpcs://temporal.test:7233#fragment",
            "grpcs://temporal.test",
            "grpcs://:7233");
        for (String endpoint : invalidEndpoints) {
            assertThatThrownBy(() -> properties(
                endpoint, TemporalTestCertificates.SERVER_NAME, root,
                certificate, key, trust, RPC_TIMEOUT, SHUTDOWN_TIMEOUT))
                .as(endpoint)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid Temporal endpoint");
        }

        assertThatThrownBy(() -> properties(
            " ", TemporalTestCertificates.SERVER_NAME, root,
            certificate, key, trust, RPC_TIMEOUT, SHUTDOWN_TIMEOUT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
            "grpcs://temporal.test:7233", " ", root,
            certificate, key, trust, RPC_TIMEOUT, SHUTDOWN_TIMEOUT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemporalConnectionProperties(
            "grpcs://temporal.test:7233", " ", "queue",
            TemporalTestCertificates.SERVER_NAME, root, certificate, key, trust,
            RPC_TIMEOUT, SHUTDOWN_TIMEOUT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemporalConnectionProperties(
            "grpcs://temporal.test:7233", "namespace", " ",
            TemporalTestCertificates.SERVER_NAME, root, certificate, key, trust,
            RPC_TIMEOUT, SHUTDOWN_TIMEOUT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
            "grpcs://temporal.test:7233", TemporalTestCertificates.SERVER_NAME, root,
            certificate, key, trust, Duration.ZERO, SHUTDOWN_TIMEOUT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
            "grpcs://temporal.test:7233", TemporalTestCertificates.SERVER_NAME, root,
            certificate, key, trust, RPC_TIMEOUT, Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingMalformedAndMismatchedSecretMaterialBeforeConnecting()
            throws Exception {
        TemporalTestCertificates.Material material = TemporalTestCertificates.generate(
            temporaryDirectory.resolve("material"));
        TemporalServiceStubsFactory factory = testFactory();

        Path missing = material.root().resolve("missing.pem");
        assertThatThrownBy(() -> factory.create(properties(
            "grpcs://127.0.0.1:7233", TemporalTestCertificates.SERVER_NAME,
            material.root(), missing, material.client().privateKey(), material.trustedCa(),
            RPC_TIMEOUT, SHUTDOWN_TIMEOUT)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("invalid Temporal TLS material");

        Path malformed = material.root().resolve("malformed.pem");
        Files.writeString(malformed, "not a PEM certificate");
        assertThatThrownBy(() -> factory.create(properties(
            "grpcs://127.0.0.1:7233", TemporalTestCertificates.SERVER_NAME,
            material.root(), malformed, material.client().privateKey(),
            material.trustedCa(), RPC_TIMEOUT, SHUTDOWN_TIMEOUT)))
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> factory.create(properties(
            "grpcs://127.0.0.1:7233", TemporalTestCertificates.SERVER_NAME,
            material.root(), material.client().certificate(), material.mismatchedClientKey(),
            material.trustedCa(), RPC_TIMEOUT, SHUTDOWN_TIMEOUT)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsOnlyTrustedMutualTlsAgainstTheRealTemporalFrontend() throws Exception {
        TemporalTestCertificates.Material material = TemporalTestCertificates.generate(
            temporaryDirectory.resolve("trusted"));
        try (TemporalMtlsTestServer server = new TemporalMtlsTestServer(material)) {
            server.start();

            TemporalConnectionProperties trusted = properties(
                server.endpoint(), TemporalTestCertificates.SERVER_NAME,
                material.root(), material.client().certificate(),
                material.client().privateKey(), material.trustedCa(),
                RPC_TIMEOUT, SHUTDOWN_TIMEOUT);
            try (StubsHandle handle = connect(trusted)) {
                assertThat(handle.stubs().blockingStub()
                    .withDeadlineAfter(RPC_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)
                    .describeNamespace(DescribeNamespaceRequest.newBuilder()
                        .setNamespace(TemporalMtlsTestServer.NAMESPACE)
                        .build())
                    .getNamespaceInfo()
                    .getName())
                    .isEqualTo(TemporalMtlsTestServer.NAMESPACE);
            }

            assertTlsConnectionRejected(properties(
                server.endpoint(), "wrong.test", material.root(),
                material.client().certificate(), material.client().privateKey(),
                material.trustedCa(), RPC_TIMEOUT, SHUTDOWN_TIMEOUT));
            assertTlsConnectionRejected(properties(
                server.endpoint(), TemporalTestCertificates.SERVER_NAME, material.root(),
                material.untrustedClient().certificate(),
                material.untrustedClient().privateKey(), material.trustedCa(),
                RPC_TIMEOUT, SHUTDOWN_TIMEOUT));
            assertTlsConnectionRejected(properties(
                server.endpoint(), TemporalTestCertificates.SERVER_NAME, material.root(),
                material.expiredClient().certificate(),
                material.expiredClient().privateKey(), material.trustedCa(),
                RPC_TIMEOUT, SHUTDOWN_TIMEOUT));
            assertTlsConnectionRejected(properties(
                server.endpoint(), TemporalTestCertificates.SERVER_NAME, material.root(),
                material.wrongEkuClient().certificate(),
                material.wrongEkuClient().privateKey(), material.trustedCa(),
                RPC_TIMEOUT, SHUTDOWN_TIMEOUT));
            assertTlsConnectionRejected(properties(
                server.endpoint(), TemporalTestCertificates.SERVER_NAME, material.root(),
                material.client().certificate(), material.client().privateKey(),
                material.untrustedCa(), RPC_TIMEOUT, SHUTDOWN_TIMEOUT));
        }
    }

    @Test
    void rejectsExpiredOrWrongSanServerIdentity() throws Exception {
        TemporalTestCertificates.Material material = TemporalTestCertificates.generate(
            temporaryDirectory.resolve("server-identities"));
        for (TemporalTestCertificates.CertificateFiles identity : List.of(
                material.expiredServer(), material.wrongSanServer())) {
            try (TemporalMtlsTestServer server = new TemporalMtlsTestServer(material, identity)) {
                assertThatThrownBy(server::start)
                    .isInstanceOf(RuntimeException.class);
            }
        }
    }

    @Test
    @Timeout(240)
    void captureGoldenHistory() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("accordCaptureTemporalHistory"),
            "history capture is an explicit maintenance action");

        TemporalTestCertificates.Material material = TemporalTestCertificates.generate(
            temporaryDirectory.resolve("capture"));
        AtomicInteger observations = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();
        try (TemporalMtlsTestServer server = new TemporalMtlsTestServer(material)) {
            server.start();
            try (HikariDataSource dataSource = workerDataSource(server)) {
                JooqExternalIntentStore store = new JooqExternalIntentStore();
                WorkerTenantTransactions transactions = new WorkerTenantTransactions(
                    callerOwnedDsl(dataSource));
                seedOutcomeUnknown(server, store, transactions);

                FencedReconciliationObservation observation =
                    new FencedReconciliationObservation(
                        transactions,
                        store,
                        (lease, timeout) -> {
                            if (observations.incrementAndGet() == 1) {
                                throw ReconciliationFailure.of(
                                    ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
                            }
                            return new ReconciliationResolution.Succeeded(
                                "sha256:" + "b".repeat(64), null);
                        },
                        new ReconciliationRuntimeProperties(
                            "reconciliation-golden-capture", Duration.ofSeconds(8)));
                TemporalConnectionProperties properties = properties(
                    server.endpoint(), TemporalTestCertificates.SERVER_NAME,
                    material.root(), material.client().certificate(),
                    material.client().privateKey(), material.trustedCa(),
                    RPC_TIMEOUT, SHUTDOWN_TIMEOUT);
                TemporalWorkerLifecycle lifecycle = new TemporalWorkerLifecycle(
                    properties,
                    testFactory(),
                    new ReadOnlyReconciliationActivity(observation));
                try {
                    lifecycle.start();
                    WorkflowClient client = WorkflowClient.newInstance(
                        lifecycle.serviceStubs(),
                        WorkflowClientOptions.newBuilder()
                            .setNamespace(ReconciliationWorkflowReplayTest.NAMESPACE)
                            .validateAndBuildWithDefaults());
                    ReconciliationWorkflow workflow = client.newWorkflowStub(
                        ReconciliationWorkflow.class,
                        WorkflowOptions.newBuilder()
                            .setWorkflowId(ReconciliationWorkflowReplayTest.WORKFLOW_ID)
                            .setTaskQueue(ReconciliationWorkflowReplayTest.TASK_QUEUE)
                            .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
                            .build());
                    ReconciliationOutcome outcome = workflow.reconcile(
                        new ReconciliationWorkflowRef(
                            ReconciliationWorkflowReplayTest.TENANT_ID,
                            ReconciliationWorkflowReplayTest.INTENT_ID));
                    assertThat(outcome).isEqualTo(ReconciliationOutcome.CONVERGED);
                    assertThat(observations).hasValue(2);
                    assertThat(mutations).hasValue(0);

                    History history = readCompleteHistory(
                        lifecycle.serviceStubs(),
                        ReconciliationWorkflowReplayTest.NAMESPACE,
                        ReconciliationWorkflowReplayTest.WORKFLOW_ID);
                    writeCapturedResources(history);
                } finally {
                    lifecycle.stop();
                }
            }
        }
    }

    private static void assertTlsConnectionRejected(TemporalConnectionProperties properties) {
        assertThatThrownBy(() -> {
            try (StubsHandle handle = connect(properties)) {
                assertThat(handle.stubs()).isNotNull();
            }
        }).isInstanceOfAny(StatusRuntimeException.class, IllegalStateException.class);
    }

    private static StubsHandle connect(TemporalConnectionProperties properties) {
        WorkflowServiceStubs stubs = testFactory().create(properties);
        try {
            stubs.connect(properties.rpcTimeout());
            stubs.blockingStub()
                .withDeadlineAfter(properties.rpcTimeout().toNanos(), TimeUnit.NANOSECONDS)
                .describeNamespace(DescribeNamespaceRequest.newBuilder()
                    .setNamespace(properties.namespace())
                    .build());
            return new StubsHandle(stubs, properties.shutdownTimeout());
        } catch (RuntimeException failure) {
            shutdown(stubs, properties.shutdownTimeout());
            throw failure;
        }
    }

    private static TemporalServiceStubsFactory testFactory() {
        TemporalSecretAclPolicy stableTestAcl = (path, attributes, kind) -> { };
        return new TemporalServiceStubsFactory(
            new TemporalSecretFileLoader(stableTestAcl));
    }

    private static HikariDataSource workerDataSource(TemporalMtlsTestServer server) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(server.controlJdbcUrl());
        config.setUsername(server.controlWorkerUser());
        config.setPassword(server.controlWorkerPassword());
        config.setConnectionInitSql("SET ROLE accord_worker");
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setMaximumPoolSize(2);
        config.addDataSourceProperty("connectTimeout", "2");
        config.addDataSourceProperty("socketTimeout", "4");
        config.addDataSourceProperty("cancelSignalTimeout", "2");
        config.addDataSourceProperty("options", String.join(" ",
            "-c statement_timeout=2000",
            "-c lock_timeout=1000",
            "-c idle_in_transaction_session_timeout=3000",
            "-c transaction_timeout=6000"));
        return new HikariDataSource(config);
    }

    private static DSLContext callerOwnedDsl(HikariDataSource dataSource) {
        TransactionAwareDataSourceProxy connections =
            new TransactionAwareDataSourceProxy(dataSource);
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(SQLDialect.POSTGRES);
        configuration.set(new DataSourceConnectionProvider(connections));
        configuration.set(new SpringTransactionProvider(
            new DataSourceTransactionManager(dataSource)));
        return DSL.using(configuration);
    }

    private static void seedOutcomeUnknown(
            TemporalMtlsTestServer server,
            JooqExternalIntentStore store,
            WorkerTenantTransactions transactions) throws Exception {
        ExternalIntentRef intent;
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                server.controlJdbcUrl(),
                ControlPlaneTestRoles.API_LOGIN,
                ControlPlaneTestRoles.API_PASSWORD)) {
            connection.setAutoCommit(false);
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE accord_api");
                statement.execute("SELECT set_config('app.tenant_id','"
                    + ReconciliationWorkflowReplayTest.TENANT_ID + "',true)");
            }
            ExternalIntentRegistration registration = store.record(
                DSL.using(connection, SQLDialect.POSTGRES),
                new ExternalIntentDefinition(
                    ReconciliationWorkflowReplayTest.TENANT_ID,
                    ReconciliationWorkflowReplayTest.INTENT_ID,
                    "reconciliation-golden-action-v1",
                    "repository",
                    "repository-1",
                    "gitlab",
                    "installation-1",
                    "repository-immutable-1",
                    "git.branch.create",
                    "delivery-work-item",
                    "work-item-1",
                    1,
                    "sha256:" + "a".repeat(64)));
            connection.commit();
            intent = ((ExternalIntentRegistration.Created) registration).intent();
        }
        ExecutionClaim claim = transactions.inTenant(intent.tenantId(), tx ->
            store.claimExecution(tx, intent.tenantId(), intent.intentId(),
                "history-seed", Duration.ofSeconds(8)));
        var permit = ((ExecutionClaim.Acquired) claim).permit();
        transactions.inTenant(intent.tenantId(), tx -> {
            store.markExecutionOutcomeUnknown(tx, permit,
                new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", null));
            return null;
        });
    }

    private static History readCompleteHistory(
            WorkflowServiceStubs stubs, String namespace, String workflowId) {
        History.Builder history = History.newBuilder();
        ByteString pageToken = ByteString.EMPTY;
        do {
            var response = stubs.blockingStub()
                .withDeadlineAfter(RPC_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)
                .getWorkflowExecutionHistory(
                    GetWorkflowExecutionHistoryRequest.newBuilder()
                        .setNamespace(namespace)
                        .setExecution(WorkflowExecution.newBuilder()
                            .setWorkflowId(workflowId)
                            .build())
                        .setNextPageToken(pageToken)
                        .build());
            history.addAllEvents(response.getHistory().getEventsList());
            pageToken = response.getNextPageToken();
        } while (!pageToken.isEmpty());
        return history.build();
    }

    private static void writeCapturedResources(History history) throws Exception {
        byte[] historyBytes = CanonicalJson.canonicalize(
            JsonFormat.printer().omittingInsignificantWhitespace()
                .print(history).getBytes(StandardCharsets.UTF_8));
        Path directory = workerProjectDirectory()
            .resolve("src/test/resources/temporal");
        Files.createDirectories(directory);
        Path historyPath = directory.resolve("reconciliation-workflow-v1.json");
        atomicWrite(historyPath, historyBytes);

        ObjectNode provenance = JSON.createObjectNode();
        ArrayNode argv = provenance.putArray("capture_argv");
        for (String argument : List.of(
                "./gradlew.bat",
                ":apps:control-plane:worker:test",
                "--tests",
                "*TemporalMtlsIT.captureGoldenHistory",
                "-PaccordCaptureTemporalHistory=true",
                "--no-daemon",
                "--dependency-verification=strict")) {
            argv.add(argument);
        }
        provenance.put("history_resource", ReconciliationWorkflowReplayTest.HISTORY_RESOURCE);
        provenance.put("history_sha256", sha256(historyBytes));
        provenance.put("namespace", ReconciliationWorkflowReplayTest.NAMESPACE);
        provenance.put("schema", "accord.temporal.reconciliation-history-provenance");
        imageIdentity(provenance.putObject("schema_tool"),
            TemporalMtlsTestServer.ADMIN_TOOLS_SOURCE,
            TemporalMtlsTestServer.ADMIN_TOOLS_AMD64_MANIFEST,
            TemporalMtlsTestServer.ADMIN_TOOLS_REPOSITORY_DIGEST);
        ObjectNode sdk = provenance.putObject("sdk");
        sdk.put("artifact_sha256", sha256(Files.readAllBytes(temporalSdkArtifact())));
        sdk.put("coordinate", "io.temporal:temporal-sdk:1.28.1");
        imageIdentity(provenance.putObject("server"),
            TemporalMtlsTestServer.SERVER_SOURCE,
            TemporalMtlsTestServer.SERVER_AMD64_MANIFEST,
            TemporalMtlsTestServer.SERVER_REPOSITORY_DIGEST);
        provenance.put("server_name", ReconciliationWorkflowReplayTest.SERVER_NAME);
        provenance.put("task_queue", ReconciliationWorkflowReplayTest.TASK_QUEUE);
        provenance.put("transport", "mtls");
        provenance.put("version", 1);
        provenance.put("workflow_id", ReconciliationWorkflowReplayTest.WORKFLOW_ID);
        provenance.put("workflow_type", ReconciliationWorkflowReplayTest.WORKFLOW_TYPE);
        byte[] provenanceBytes = CanonicalJson.canonicalize(
            JSON.writeValueAsBytes(provenance));
        atomicWrite(directory.resolve(
            "reconciliation-workflow-v1.provenance.json"), provenanceBytes);
    }

    private static void imageIdentity(
            ObjectNode target, String coordinate, String platform, String repository) {
        target.put("coordinate", coordinate);
        target.put("linux_amd64_manifest_digest", platform);
        target.put("repository_digest", repository);
    }

    private static Path temporalSdkArtifact() throws Exception {
        URI location = WorkflowClient.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI();
        return Path.of(location).toRealPath();
    }

    private static Path workerProjectDirectory() {
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path nested = working.resolve("apps/control-plane/worker");
        return Files.isRegularFile(nested.resolve("build.gradle")) ? nested : working;
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(),
            ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static TemporalConnectionProperties properties(
            String endpoint,
            String serverName,
            Path root,
            Path certificate,
            Path key,
            Path trust,
            Duration rpcTimeout,
            Duration shutdownTimeout) {
        return new TemporalConnectionProperties(
            endpoint,
            TemporalMtlsTestServer.NAMESPACE,
            "accord-reconciliation-v1",
            serverName,
            root,
            certificate,
            key,
            trust,
            rpcTimeout,
            shutdownTimeout);
    }

    private static void shutdown(WorkflowServiceStubs stubs, Duration timeout) {
        stubs.shutdown();
        if (!stubs.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            stubs.shutdownNow();
            stubs.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private record StubsHandle(WorkflowServiceStubs stubs, Duration timeout)
            implements AutoCloseable {
        @Override
        public void close() {
            shutdown(stubs, timeout);
        }
    }
}
