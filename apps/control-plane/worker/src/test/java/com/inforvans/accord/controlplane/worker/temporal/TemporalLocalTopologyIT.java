package com.inforvans.accord.controlplane.worker.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.inforvans.accord.observability.AccordWorkflowTelemetry;
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
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.History;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(170)
final class TemporalLocalTopologyIT {
    private static final String NAMESPACE = "accord-foundation-local-v1";
    private static final String TASK_QUEUE = "accord-reconciliation-ft13-v1";
    private static final String TEMPORAL_ENDPOINT = "grpcs://127.0.0.1:7233";
    private static final String CONTROL_JDBC = "jdbc:postgresql://127.0.0.1:55432/accord";
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> CASE_IDS = List.of(
        "production-worker-reconciliation",
        "ui-client",
        "plaintext-rejected",
        "missing-client-certificate-rejected",
        "wrong-client-ca-rejected",
        "wrong-server-name-rejected",
        "wrong-client-eku-rejected");
    private static final Set<String> COMPLETED_CASES =
        Collections.synchronizedSet(new LinkedHashSet<>());
    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean();
    private static final AtomicBoolean WORKER_STOPPED = new AtomicBoolean();
    private static final AtomicInteger MUTATION_CALLS = new AtomicInteger(-1);

    private static Path repositoryRoot;
    private static Path pkiRoot;
    private static String runId;
    private static String attemptId;
    private static ReconciliationOutcome workflowOutcome;

    @BeforeAll
    static void authorizeAttemptBeforeAnyProductSideEffect() throws Exception {
        String rawNonce = System.getenv("ACCORD_FT13_SUPERVISOR_NONCE");
        byte[] nonceText = requireNonce(rawNonce);
        String nonceDigest = sha256(nonceText);
        Arrays.fill(nonceText, (byte) 0);

        String validatedRunId = canonicalUuid(
            System.getProperty("accord.ft13.run-id"), "run ID");
        String validatedAttemptId = canonicalUuid(
            System.getProperty("accord.ft13.it-attempt-id"), "attempt ID");
        String pendingDigest = requireDigest(
            System.getProperty("accord.ft13.pending-state-sha256"));
        Path root = locateRepositoryRoot();
        Path state = requireRunStatePath(root,
            System.getProperty("accord.ft13.run-state"));

        claimPendingAttempt(state, validatedRunId, validatedAttemptId,
            nonceDigest, pendingDigest);
        repositoryRoot = root;
        pkiRoot = root.resolve("infra/local/state/pki");
        runId = validatedRunId;
        attemptId = validatedAttemptId;
    }

    @Test
    @Order(1)
    void productionWorkerRunsTheRealFencedReconciliationWorkflow() throws Exception {
        String superuserPassword = requireLocalPassword(
            "ACCORD_POSTGRES_SUPERUSER_PASSWORD");
        migrateControlPlane(superuserPassword);

        UUID tenantId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        AtomicInteger observations = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();
        AtomicBoolean transactionLeak = new AtomicBoolean();
        TemporalWorkerLifecycle lifecycle = null;
        WorkerFactory factory = null;
        WorkflowServiceStubs stubs = null;

        try (HikariDataSource dataSource = workerDataSource()) {
            TemporalRuntimeConfiguration runtime = new TemporalRuntimeConfiguration();
            JooqExternalIntentStore store = runtime.externalIntentStore();
            WorkerTenantTransactions transactions = runtime.workerTenantTransactions(
                callerOwnedDsl(dataSource));
            seedOutcomeUnknown(tenantId, intentId, store, transactions);
            AccordWorkflowTelemetry workflowTelemetry =
                mock(AccordWorkflowTelemetry.class);

            FencedReconciliationObservation observation =
                runtime.fencedReconciliationObservation(
                    transactions,
                    store,
                    (lease, timeout) -> {
                        transactionLeak.compareAndSet(false,
                            TransactionSynchronizationManager.isActualTransactionActive());
                        int call = observations.incrementAndGet();
                        if (call == 1) {
                            throw ReconciliationFailure.of(
                                ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
                        }
                        return new ReconciliationResolution.Succeeded(
                            "sha256:" + "b".repeat(64), null);
                    },
                    new ReconciliationRuntimeProperties(
                        "ft13-local-worker", Duration.ofSeconds(8)),
                    workflowTelemetry);
            ReadOnlyReconciliationActivity activity =
                runtime.readOnlyReconciliationActivity(observation);
            TemporalConnectionProperties properties = properties(
                "worker", LocalTemporalPki.SERVER_NAME);
            TemporalSecretAclPolicy aclPolicy = runtime.temporalSecretAclPolicy();
            TemporalServiceStubsFactory stubsFactory =
                runtime.temporalServiceStubsFactory(
                    runtime.temporalSecretFileLoader(aclPolicy));
            lifecycle = runtime.temporalWorkerLifecycle(
                properties, stubsFactory, activity);

            try {
                lifecycle.start();
                WORKER_STARTED.set(true);
                factory = lifecycle.workerFactory();
                stubs = lifecycle.serviceStubs();
                assertNotNull(factory);
                assertNotNull(stubs);

                WorkflowClient client = WorkflowClient.newInstance(
                    stubs,
                    WorkflowClientOptions.newBuilder()
                        .setNamespace(NAMESPACE)
                        .validateAndBuildWithDefaults());
                String workflowId = "ft13-reconciliation-" + intentId;
                ReconciliationWorkflow workflow = client.newWorkflowStub(
                    ReconciliationWorkflow.class,
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
                        .build());
                ReconciliationOutcome outcome = workflow.reconcile(
                    new ReconciliationWorkflowRef(tenantId, intentId));

                assertEquals(ReconciliationOutcome.CONVERGED, outcome);
                assertEquals(2, observations.get());
                verify(workflowTelemetry, times(1))
                    .recordProviderReconciliationSuccess();
                assertEquals(0, mutations.get());
                assertFalse(transactionLeak.get(),
                    "Provider observation ran inside a JDBC transaction");
                assertPersistedTerminalEvidence(tenantId, intentId);
                assertIdentifierOnlyHistory(readCompleteHistory(stubs, workflowId));

                workflowOutcome = outcome;
                MUTATION_CALLS.set(mutations.get());
            } finally {
                lifecycle.stop();
                assertFalse(lifecycle.isRunning());
                assertTrue(factory == null || factory.isTerminated(),
                    "Temporal worker factory survived shutdown");
                assertTrue(stubs == null || stubs.isTerminated(),
                    "Temporal service stubs survived shutdown");
                assertTrue(ProcessHandle.current().children().noneMatch(ProcessHandle::isAlive),
                    "Temporal integration test left a child process alive");
                WORKER_STOPPED.set(WORKER_STARTED.get());
            }
        }
        completeCase("production-worker-reconciliation");
    }

    @Test
    @Order(2)
    void uiClientCertificateCanDescribeTheNamespace() throws Exception {
        assertConnectionSucceeds(properties("ui", LocalTemporalPki.SERVER_NAME));
        completeCase("ui-client");
    }

    @Test
    @Order(3)
    void plaintextConnectionIsRejected() {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget("127.0.0.1:7233")
            .setEnableHttps(false)
            .setRpcTimeout(RPC_TIMEOUT)
            .validateAndBuildWithDefaults();
        assertStubsRejected(WorkflowServiceStubs.newServiceStubs(options));
        completeCase("plaintext-rejected");
    }

    @Test
    @Order(4)
    void missingClientCertificateIsRejectedByTheFrontend() throws Exception {
        SslContext trustOnly;
        try (InputStream trust = Files.newInputStream(
                pkiRoot.resolve("server-ca.pem"), StandardOpenOption.READ)) {
            trustOnly = GrpcSslContexts.forClient().trustManager(trust).build();
        }
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget("127.0.0.1:7233")
            .setEnableHttps(true)
            .setSslContext(trustOnly)
            .setChannelInitializer(channel ->
                channel.overrideAuthority(LocalTemporalPki.SERVER_NAME))
            .setRpcTimeout(RPC_TIMEOUT)
            .validateAndBuildWithDefaults();
        assertStubsRejected(WorkflowServiceStubs.newServiceStubs(options));
        completeCase("missing-client-certificate-rejected");
    }

    @Test
    @Order(5)
    void clientSignedByTheWrongCaIsRejected() throws Exception {
        assertConnectionRejected(properties("wrong-ca", LocalTemporalPki.SERVER_NAME));
        completeCase("wrong-client-ca-rejected");
    }

    @Test
    @Order(6)
    void wrongServerNameIsRejected() throws Exception {
        assertConnectionRejected(properties("worker", "wrong.temporal.invalid"));
        completeCase("wrong-server-name-rejected");
    }

    @Test
    @Order(7)
    void trustedClientWithWrongEkuIsRejected() throws Exception {
        assertConnectionRejected(properties("wrong-eku", LocalTemporalPki.SERVER_NAME));
        completeCase("wrong-client-eku-rejected");
    }

    @AfterAll
    static void writeReceiptOnlyForTheClosedPassingSuite() throws Exception {
        Set<String> completed;
        synchronized (COMPLETED_CASES) {
            completed = Set.copyOf(COMPLETED_CASES);
        }
        if (!completed.equals(Set.copyOf(CASE_IDS))
                || !WORKER_STARTED.get()
                || !WORKER_STOPPED.get()
                || workflowOutcome != ReconciliationOutcome.CONVERGED
                || MUTATION_CALLS.get() != 0) {
            return;
        }
        writeClosedReceipt();
    }

    private static void claimPendingAttempt(
            Path state,
            String expectedRunId,
            String expectedAttemptId,
            String nonceDigest,
            String pendingDigest) throws Exception {
        Path lockPath = state.resolveSibling("temporal-it-attempt.lock");
        UserPrincipal stateOwner = snapshotRegularFile(state).owner();
        createLockIfAbsent(lockPath);
        FileSnapshot lockSnapshot = snapshotRegularFile(lockPath);
        if (!lockSnapshot.owner().equals(stateOwner)) {
            throw new IllegalStateException("Temporal attempt lock owner mismatch");
        }

        try (FileChannel channel = FileChannel.open(lockPath,
                 StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock lock = acquireLock(channel, Duration.ofSeconds(2))) {
            if (!lock.isValid()) {
                throw new IllegalStateException("Temporal attempt lock is invalid");
            }
            if (!lockSnapshot.sameIdentity(snapshotRegularFile(lockPath))) {
                throw new IllegalStateException("Temporal attempt lock changed");
            }
            FileSnapshot stateSnapshot = snapshotRegularFile(state);
            if (!stateSnapshot.owner().equals(stateOwner)) {
                throw new IllegalStateException("Temporal run-state owner changed");
            }
            byte[] pendingBytes = Files.readAllBytes(state);
            if (pendingBytes.length < 2 || pendingBytes.length > 1_048_576
                    || !sha256(pendingBytes).equals(pendingDigest)) {
                throw new IllegalStateException("Temporal run-state CAS digest mismatch");
            }

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = requirePendingState(
                mapper.readTree(pendingBytes), expectedRunId,
                expectedAttemptId, nonceDigest);
            ObjectNode next = root.deepCopy();
            ObjectNode attempt = (ObjectNode) next.get("temporal_it_attempt");
            attempt.put("state", "RUNNING");
            attempt.put("runner_pid", ProcessHandle.current().pid());
            byte[] canonical = CanonicalJson.canonicalize(mapper.writeValueAsBytes(next));
            byte[] runningBytes = Arrays.copyOf(canonical, canonical.length + 1);
            runningBytes[runningBytes.length - 1] = (byte) '\n';

            atomicCompareAndReplace(
                state, pendingBytes, stateSnapshot, runningBytes, stateOwner);
            ObjectNode stored = requireRunningState(
                mapper.readTree(Files.readAllBytes(state)), expectedRunId,
                expectedAttemptId, nonceDigest, ProcessHandle.current().pid());
            assertNotNull(stored);
        }
    }

    private static ObjectNode requirePendingState(
            JsonNode value,
            String expectedRunId,
            String expectedAttemptId,
            String nonceDigest) {
        ObjectNode root = requireClosedRunState(value);
        assertEquals(expectedRunId, requiredText(root, "run_id"));
        JsonNode attemptValue = root.get("temporal_it_attempt");
        if (!(attemptValue instanceof ObjectNode attempt)
                || !fieldNames(attempt).equals(Set.of(
                    "run_id", "it_attempt_id", "state",
                    "supervisor_nonce_sha256"))) {
            throw new IllegalStateException("Temporal pending attempt is not closed");
        }
        assertEquals(expectedRunId, requiredText(attempt, "run_id"));
        assertEquals(expectedAttemptId, requiredText(attempt, "it_attempt_id"));
        assertEquals("PENDING", requiredText(attempt, "state"));
        assertEquals(nonceDigest, requiredText(attempt, "supervisor_nonce_sha256"));
        return root;
    }

    private static ObjectNode requireRunningState(
            JsonNode value,
            String expectedRunId,
            String expectedAttemptId,
            String nonceDigest,
            long expectedPid) {
        ObjectNode root = requireClosedRunState(value);
        assertEquals(expectedRunId, requiredText(root, "run_id"));
        JsonNode attemptValue = root.get("temporal_it_attempt");
        if (!(attemptValue instanceof ObjectNode attempt)
                || !fieldNames(attempt).equals(Set.of(
                    "run_id", "it_attempt_id", "state",
                    "supervisor_nonce_sha256", "runner_pid"))) {
            throw new IllegalStateException("Temporal running attempt is not closed");
        }
        assertEquals(expectedRunId, requiredText(attempt, "run_id"));
        assertEquals(expectedAttemptId, requiredText(attempt, "it_attempt_id"));
        assertEquals("RUNNING", requiredText(attempt, "state"));
        assertEquals(nonceDigest, requiredText(attempt, "supervisor_nonce_sha256"));
        assertEquals(expectedPid, attempt.path("runner_pid").longValue());
        return root;
    }

    private static ObjectNode requireClosedRunState(JsonNode value) {
        if (!(value instanceof ObjectNode root)
                || !fieldNames(root).equals(Set.of(
                    "schema_version", "run_id", "image_lock_sha256",
                    "compose_project", "dependency_services", "local_endpoints",
                    "ready", "tool_observations", "temporal_it_attempt"))) {
            throw new IllegalStateException("Temporal run-state is not closed");
        }
        assertEquals("1.0.0", requiredText(root, "schema_version"));
        return root;
    }

    private static void atomicCompareAndReplace(
            Path target,
            byte[] expectedBytes,
            FileSnapshot expectedSnapshot,
            byte[] replacement,
            UserPrincipal expectedOwner) throws Exception {
        Path temporary = target.resolveSibling(
            ".run-state." + ProcessHandle.current().pid() + "."
                + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE,
                     LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(replacement);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }
            restrictOwnerOnlyWhenPosix(temporary);
            FileSnapshot temporarySnapshot = snapshotRegularFile(temporary);
            if (!temporarySnapshot.owner().equals(expectedOwner)
                    || !Arrays.equals(expectedBytes, Files.readAllBytes(target))
                    || !expectedSnapshot.sameIdentity(snapshotRegularFile(target))) {
                throw new IllegalStateException("Temporal run-state CAS was lost");
            }
            try {
                Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic Temporal run-state replacement unavailable",
                    unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static FileLock acquireLock(FileChannel channel, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException busy) {
                // Another contender in this JVM owns the one-use attempt.
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Temporal attempt lock timed out");
    }

    private static void createLockIfAbsent(Path lockPath) throws Exception {
        try {
            Files.createFile(lockPath);
            restrictOwnerOnlyWhenPosix(lockPath);
        } catch (FileAlreadyExistsException existing) {
            snapshotRegularFile(lockPath);
        }
    }

    private static Path requireRunStatePath(Path root, String configured)
            throws Exception {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Temporal run-state path is absent");
        }
        Path expected = root.resolve("infra/local/state/run-state.json").normalize();
        Path supplied = Path.of(configured).toAbsolutePath().normalize();
        if (!supplied.equals(expected)) {
            throw new IllegalStateException("Temporal run-state path is not canonical");
        }
        requireDirectoryHierarchy(root, expected.getParent());
        snapshotRegularFile(expected);
        if (!expected.toRealPath(LinkOption.NOFOLLOW_LINKS)
                .startsWith(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalStateException("Temporal run-state escapes the repository");
        }
        return expected;
    }

    private static Path locateRepositoryRoot() throws Exception {
        Path cursor = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && cursor != null; depth++) {
            if (Files.isRegularFile(cursor.resolve("settings.gradle"),
                    LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(cursor.resolve("infra/local"),
                        LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(
                    cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !attributes.isDirectory()
                        || attributes.fileKey() == null) {
                    throw new IllegalStateException("repository path is unsafe");
                }
                return cursor.toRealPath(LinkOption.NOFOLLOW_LINKS);
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("repository root is unavailable");
    }

    private static void requireDirectoryHierarchy(Path root, Path directory)
            throws Exception {
        if (!directory.startsWith(root)) {
            throw new IllegalStateException("state directory escapes repository");
        }
        Path current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            BasicFileAttributes attributes = Files.readAttributes(
                current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()
                    || attributes.fileKey() == null) {
                throw new IllegalStateException("state directory is unsafe");
            }
        }
    }

    private static FileSnapshot snapshotRegularFile(Path path) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                || attributes.fileKey() == null) {
            throw new IllegalStateException("evidence path is not a regular file");
        }
        return new FileSnapshot(
            attributes.fileKey(),
            attributes.size(),
            attributes.lastModifiedTime().toMillis(),
            Files.getOwner(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static void migrateControlPlane(String superuserPassword) {
        ControlPlaneTestRoles.bootstrap(CONTROL_JDBC, "postgres", superuserPassword);
        Flyway flyway = Flyway.configure()
            .dataSource(CONTROL_JDBC,
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD)
            .initSql("SET ROLE accord_migrator")
            .locations("classpath:db/migration")
            .target("004")
            .load();
        flyway.migrate();
        assertEquals(List.of("001", "002", "003", "004"),
            Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().toString())
                .toList());
    }

    private static HikariDataSource workerDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(CONTROL_JDBC);
        config.setUsername(ControlPlaneTestRoles.WORKER_LOGIN);
        config.setPassword(ControlPlaneTestRoles.WORKER_PASSWORD);
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
            UUID tenantId,
            UUID intentId,
            JooqExternalIntentStore store,
            WorkerTenantTransactions transactions) throws Exception {
        ExternalIntentRef intent;
        try (Connection connection = DriverManager.getConnection(
                CONTROL_JDBC,
                ControlPlaneTestRoles.API_LOGIN,
                ControlPlaneTestRoles.API_PASSWORD)) {
            connection.setAutoCommit(false);
            installTenantRole(connection, tenantId);
            ExternalIntentRegistration registration = store.record(
                DSL.using(connection, SQLDialect.POSTGRES),
                new ExternalIntentDefinition(
                    tenantId,
                    intentId,
                    "ft13-local-reconciliation-v1",
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
                "ft13-local-seed", Duration.ofSeconds(8)));
        var permit = ((ExecutionClaim.Acquired) claim).permit();
        transactions.inTenant(intent.tenantId(), tx -> {
            store.markExecutionOutcomeUnknown(tx, permit,
                new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", null));
            return null;
        });
    }

    private static void assertPersistedTerminalEvidence(UUID tenantId, UUID intentId)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                CONTROL_JDBC,
                ControlPlaneTestRoles.API_LOGIN,
                ControlPlaneTestRoles.API_PASSWORD)) {
            connection.setAutoCommit(false);
            installTenantRole(connection, tenantId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT state,reconciliation_generation
                    FROM public.external_call_intent
                    WHERE tenant_id=? AND intent_id=?
                    """)) {
                statement.setObject(1, tenantId);
                statement.setObject(2, intentId);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("SUCCEEDED", rows.getString(1));
                    assertEquals(2L, rows.getLong(2));
                    assertFalse(rows.next());
                }
            }
            assertEquals(1L, count(connection, """
                SELECT count(*) FROM public.domain_event
                WHERE tenant_id=? AND aggregate_type='external_intent'
                  AND aggregate_id=?
                """, tenantId, intentId));
            assertEquals(1L, count(connection, """
                SELECT count(*)
                FROM public.outbox_event outbox
                JOIN public.domain_event event USING (tenant_id,event_id)
                WHERE event.tenant_id=? AND event.aggregate_type='external_intent'
                  AND event.aggregate_id=?
                """, tenantId, intentId));
        }
    }

    private static long count(
            Connection connection, String sql, UUID tenantId, UUID intentId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, intentId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static void installTenantRole(Connection connection, UUID tenantId)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE accord_api");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant_id',?,true)")) {
            statement.setString(1, tenantId.toString());
            statement.execute();
        }
    }

    private static History readCompleteHistory(
            WorkflowServiceStubs stubs, String workflowId) {
        History.Builder history = History.newBuilder();
        ByteString pageToken = ByteString.EMPTY;
        do {
            var response = stubs.blockingStub()
                .withDeadlineAfter(RPC_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)
                .getWorkflowExecutionHistory(
                    GetWorkflowExecutionHistoryRequest.newBuilder()
                        .setNamespace(NAMESPACE)
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

    private static void assertIdentifierOnlyHistory(History history) throws Exception {
        assertFalse(history.getEventsList().isEmpty());
        String serialized = JsonFormat.printer()
            .omittingInsignificantWhitespace().print(history);
        for (String forbidden : List.of(
                "repository-immutable-1",
                "installation-1",
                "delivery-work-item",
                "work-item-1",
                "git.branch.create",
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64))) {
            assertFalse(serialized.contains(forbidden),
                "Temporal history contains a Provider or capability fact");
        }
    }

    private static TemporalConnectionProperties properties(
            String identity, String serverName) {
        return new TemporalConnectionProperties(
            TEMPORAL_ENDPOINT,
            NAMESPACE,
            TASK_QUEUE,
            serverName,
            pkiRoot,
            pkiRoot.resolve(identity + ".pem"),
            pkiRoot.resolve(identity + "-key.pem"),
            pkiRoot.resolve("server-ca.pem"),
            RPC_TIMEOUT,
            SHUTDOWN_TIMEOUT);
    }

    private static TemporalServiceStubsFactory productionStubsFactory() throws Exception {
        TemporalRuntimeConfiguration runtime = new TemporalRuntimeConfiguration();
        return runtime.temporalServiceStubsFactory(
            runtime.temporalSecretFileLoader(runtime.temporalSecretAclPolicy()));
    }

    private static void assertConnectionSucceeds(
            TemporalConnectionProperties properties) throws Exception {
        WorkflowServiceStubs stubs = productionStubsFactory().create(properties);
        try {
            describeNamespace(stubs);
        } finally {
            shutdown(stubs);
        }
        assertTrue(stubs.isTerminated());
    }

    private static void assertConnectionRejected(
            TemporalConnectionProperties properties) throws Exception {
        WorkflowServiceStubs stubs = productionStubsFactory().create(properties);
        assertStubsRejected(stubs);
    }

    private static void assertStubsRejected(WorkflowServiceStubs stubs) {
        try {
            assertThrows(RuntimeException.class, () -> describeNamespace(stubs));
        } finally {
            shutdown(stubs);
        }
        assertTrue(stubs.isTerminated());
    }

    private static void describeNamespace(WorkflowServiceStubs stubs) {
        stubs.connect(RPC_TIMEOUT);
        stubs.blockingStub()
            .withDeadlineAfter(RPC_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)
            .describeNamespace(DescribeNamespaceRequest.newBuilder()
                .setNamespace(NAMESPACE)
                .build());
    }

    private static void shutdown(WorkflowServiceStubs stubs) {
        stubs.shutdown();
        if (!stubs.awaitTermination(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
            stubs.shutdownNow();
            stubs.awaitTermination(
                SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static void completeCase(String caseId) {
        assertTrue(CASE_IDS.contains(caseId));
        assertTrue(COMPLETED_CASES.add(caseId), "duplicate Temporal matrix case");
    }

    private static void writeClosedReceipt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode receipt = mapper.createObjectNode();
        receipt.put("schema_version", "1.0.0");
        receipt.put("run_id", runId);
        receipt.put("it_attempt_id", attemptId);
        receipt.put("suite_name", "accord-foundation-temporal-local-v1");
        receipt.put("class_name", TemporalLocalTopologyIT.class.getName());
        receipt.put("status", "PASS");
        receipt.put("test_count", 7);
        ArrayNode cases = receipt.putArray("case_ids");
        CASE_IDS.forEach(cases::add);
        receipt.put("configuration_class", TemporalRuntimeConfiguration.class.getName());
        receipt.put("lifecycle_class", TemporalWorkerLifecycle.class.getName());
        receipt.put("worker_started", true);
        receipt.put("worker_stopped", true);
        receipt.put("workflow_outcome", ReconciliationOutcome.CONVERGED.name());
        receipt.put("mutation_calls", 0);
        ObjectNode junit = receipt.putObject("junit");
        junit.put("tests", 7);
        junit.put("failures", 0);
        junit.put("errors", 0);
        junit.put("skips", 0);
        ObjectNode exit = receipt.putObject("exit_semantics");
        exit.put("PASS", 0);
        exit.put("FAIL", 1);
        exit.put("BLOCKED", 2);

        byte[] canonicalReceipt = CanonicalJson.canonicalize(
            mapper.writeValueAsBytes(receipt));
        ObjectNode envelope = mapper.createObjectNode();
        envelope.set("receipt", receipt);
        envelope.put("receipt_sha256", sha256(canonicalReceipt));
        byte[] canonicalEnvelope = CanonicalJson.canonicalize(
            mapper.writeValueAsBytes(envelope));
        byte[] bytes = Arrays.copyOf(canonicalEnvelope, canonicalEnvelope.length + 1);
        bytes[bytes.length - 1] = (byte) '\n';

        Path build = secureDirectory(repositoryRoot.resolve("build"));
        Path verification = secureDirectory(build.resolve("verification"));
        Path target = verification.resolve("ft13-temporal-it.json");
        Path temporary = verification.resolve(
            ".ft13-temporal-it." + ProcessHandle.current().pid() + "."
                + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE,
                     LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            restrictOwnerOnlyWhenPosix(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic Temporal receipt write unavailable", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path secureDirectory(Path directory) throws Exception {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory);
            restrictOwnerOnlyWhenPosix(directory);
        }
        BasicFileAttributes attributes = Files.readAttributes(
            directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()
                || attributes.fileKey() == null
                || !directory.toRealPath(LinkOption.NOFOLLOW_LINKS)
                    .startsWith(repositoryRoot)) {
            throw new IllegalStateException("Temporal receipt directory is unsafe");
        }
        return directory;
    }

    private static void restrictOwnerOnlyWhenPosix(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            } else {
                Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            }
        }
    }

    private static byte[] requireNonce(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalStateException("invalid Temporal supervisor nonce");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("invalid Temporal supervisor nonce", failure);
        }
        if (decoded.length != 32) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException("invalid Temporal supervisor nonce");
        }
        Arrays.fill(decoded, (byte) 0);
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException("invalid Temporal pending-state digest");
        }
        return value;
    }

    private static String canonicalUuid(String value, String name) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException(name);
            }
            return value;
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("invalid Temporal " + name, failure);
        }
    }

    private static String requireLocalPassword(String name) {
        String value = System.getenv(name);
        if (value == null || !value.matches("[A-Za-z0-9_-]{24,128}")) {
            throw new IllegalStateException("local PostgreSQL authority is unavailable");
        }
        return value;
    }

    private static Set<String> fieldNames(ObjectNode object) {
        Set<String> names = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static String requiredText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalStateException("Temporal run-state field is invalid");
        }
        return value.textValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record FileSnapshot(
        Object fileKey,
        long size,
        long lastModifiedMillis,
        UserPrincipal owner
    ) {
        boolean sameIdentity(FileSnapshot other) {
            return fileKey.equals(other.fileKey)
                && size == other.size
                && lastModifiedMillis == other.lastModifiedMillis
                && owner.equals(other.owner);
        }
    }
}
