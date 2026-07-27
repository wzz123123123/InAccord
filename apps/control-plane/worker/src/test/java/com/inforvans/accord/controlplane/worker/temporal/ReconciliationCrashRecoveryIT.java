package com.inforvans.accord.controlplane.worker.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.inforvans.accord.controlplane.worker.reconciliation.WorkerTenantTransactions;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflow;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowRef;
import com.inforvans.accord.database.ControlPlaneTestRoles;
import com.inforvans.accord.reliability.ExecutionClaim;
import com.inforvans.accord.reliability.ExecutionResolution;
import com.inforvans.accord.reliability.ExternalIntentDefinition;
import com.inforvans.accord.reliability.ExternalIntentRegistration;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ReconciliationCrashRecoveryIT {
    private static final Duration INVOCATION_BUDGET = Duration.ofSeconds(120);
    private static final Duration INFRASTRUCTURE_CAP = Duration.ofSeconds(60);
    private static final Duration CHILD_READY_CAP = Duration.ofSeconds(15);
    private static final Duration CUT_CAP = Duration.ofSeconds(10);
    private static final Duration CHILD_EXIT_CAP = Duration.ofSeconds(10);
    private static final Duration REPLACEMENT_CAP = Duration.ofSeconds(20);
    private static final Duration RESULT_CAP = Duration.ofSeconds(60);
    private static final Duration STOP_CAP = Duration.ofSeconds(10);
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HEARTBEAT_EXPIRY_MARGIN = Duration.ofMillis(250);
    private static final String INSTANCE_A = "reconciliation-crash-a";
    private static final String INSTANCE_B = "reconciliation-crash-b";
    private static final String DIGEST = "sha256:" + "d".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(value = 135, unit = TimeUnit.SECONDS)
    void replacementWorkerRecoversAfterObservationProcessLoss() throws Exception {
        UUID tenantId = UUID.fromString("11000000-0000-0000-0000-000000000010");
        UUID intentId = UUID.fromString("22000000-0000-0000-0000-000000000010");
        String workflowId = "reconciliation-crash-observation-v1";
        Deadline deadline = Deadline.start(INVOCATION_BUDGET);

        try (Harness harness = Harness.start(
                 temporaryDirectory.resolve("observation"), deadline);
             ReconciliationProbeServer probe = new ReconciliationProbeServer(
                 ReconciliationOutcome.CONVERGED, true)) {
            harness.seedUnknown(tenantId, intentId);
            ChildProcess childA = harness.startChild(
                INSTANCE_A, probe.endpoint().toString(), "NORMAL", deadline);
            WorkflowHandle workflow = harness.startWorkflow(
                workflowId, new ReconciliationWorkflowRef(tenantId, intentId));

            assertThat(probe.awaitObservationCount(1, deadline.remaining(CUT_CAP)))
                .as("Child A reached Provider observation outside JDBC")
                .isTrue();
            long observedAt = System.nanoTime();
            childA.destroyForcibly(deadline, CHILD_EXIT_CAP);
            assertThat(childA.output())
                .doesNotContain(ReconciliationWorkerChildMain.SHUTDOWN_HOOK_COMPLETED);

            deadline.awaitUntil(observedAt
                + HEARTBEAT_TIMEOUT.plus(HEARTBEAT_EXPIRY_MARGIN).toNanos());
            probe.releaseObservations();
            ChildProcess childB = harness.startChild(
                INSTANCE_B, probe.endpoint().toString(), "NORMAL", deadline);
            assertThat(childB.awaitLine(
                    ReconciliationWorkerChildMain.ACTIVITY_ATTEMPT,
                    deadline.remaining(REPLACEMENT_CAP)))
                .as("replacement worker received the persisted activity")
                .isTrue();

            ReconciliationOutcome result = workflow.result(deadline.remaining(RESULT_CAP));
            assertThat(result).isEqualTo(ReconciliationOutcome.CONVERGED);
            assertThat(probe.observationCount()).isGreaterThanOrEqualTo(2);
            assertThat(probe.mutationCount()).isZero();
            TerminalSnapshot terminal = harness.snapshot(tenantId, intentId);
            terminal.assertSingleTerminal("SUCCEEDED", 2, INSTANCE_B);
            harness.assertOneWorkflowExecution(workflowId);
            History history = harness.history(workflowId);
            assertThat(history.getEventsList())
                .anyMatch(event -> event.getEventType()
                    == EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT);
            ReconciliationWorkflowReplayTest.inspectHistory(
                history, tenantId, intentId, ReconciliationOutcome.CONVERGED);
            childB.stop(deadline, STOP_CAP);
        }
    }

    @Test
    @Timeout(value = 135, unit = TimeUnit.SECONDS)
    void replacementWorkerMapsCommittedSuccessWithoutRepeatingProviderAccess()
            throws Exception {
        runPostTx2ResponseLoss(
            UUID.fromString("11000000-0000-0000-0000-000000000020"),
            UUID.fromString("22000000-0000-0000-0000-000000000020"),
            "reconciliation-crash-post-tx2-success-v1",
            ReconciliationOutcome.CONVERGED,
            "SUCCEEDED");
    }

    @Test
    @Timeout(value = 135, unit = TimeUnit.SECONDS)
    void replacementWorkerMapsCommittedNoEffectWithoutRepeatingProviderAccess()
            throws Exception {
        runPostTx2ResponseLoss(
            UUID.fromString("11000000-0000-0000-0000-000000000030"),
            UUID.fromString("22000000-0000-0000-0000-000000000030"),
            "reconciliation-crash-post-tx2-no-effect-v1",
            ReconciliationOutcome.CONFIRMED_NO_EFFECT,
            "CONFIRMED_NO_EFFECT");
    }

    @Test
    @Timeout(value = 135, unit = TimeUnit.SECONDS)
    void replacementWorkerMapsCommittedDivergenceWithoutRepeatingProviderAccess()
            throws Exception {
        runPostTx2ResponseLoss(
            UUID.fromString("11000000-0000-0000-0000-000000000040"),
            UUID.fromString("22000000-0000-0000-0000-000000000040"),
            "reconciliation-crash-post-tx2-diverged-v1",
            ReconciliationOutcome.DIVERGED,
            "DIVERGED");
    }

    private void runPostTx2ResponseLoss(
            UUID tenantId,
            UUID intentId,
            String workflowId,
            ReconciliationOutcome providerOutcome,
            String persistedState) throws Exception {
        Deadline deadline = Deadline.start(INVOCATION_BUDGET);
        Path invocationDirectory = temporaryDirectory.resolve(workflowId);
        try (Harness harness = Harness.start(invocationDirectory, deadline);
             ReconciliationProbeServer probe = new ReconciliationProbeServer(
                 providerOutcome, false)) {
            harness.seedUnknown(tenantId, intentId);
            ChildProcess childA = harness.startChild(
                INSTANCE_A,
                probe.endpoint().toString(),
                "POST_TX2_RESPONSE_LOSS",
                deadline);
            WorkflowHandle workflow = harness.startWorkflow(
                workflowId, new ReconciliationWorkflowRef(tenantId, intentId));

            assertThat(childA.awaitLine(
                    ReconciliationWorkerChildMain.TX2_COMMITTED_RESPONSE_PENDING,
                    deadline.remaining(CUT_CAP)))
                .as("tx2 committed before Child A response loss")
                .isTrue();
            long committedAt = System.nanoTime();
            TerminalSnapshot committed = harness.snapshot(tenantId, intentId);
            committed.assertSingleTerminal(persistedState, 1, INSTANCE_A);
            assertThat(probe.observationCount()).isEqualTo(1);
            assertThat(probe.mutationCount()).isZero();

            childA.destroyForcibly(deadline, CHILD_EXIT_CAP);
            assertThat(childA.output())
                .doesNotContain(ReconciliationWorkerChildMain.SHUTDOWN_HOOK_COMPLETED);
            deadline.awaitUntil(committedAt
                + HEARTBEAT_TIMEOUT.plus(HEARTBEAT_EXPIRY_MARGIN).toNanos());

            ChildProcess childB = harness.startChild(
                INSTANCE_B, probe.endpoint().toString(), "NORMAL", deadline);
            assertThat(childB.awaitLine(
                    ReconciliationWorkerChildMain.ACTIVITY_ATTEMPT,
                    deadline.remaining(REPLACEMENT_CAP)))
                .as("replacement worker received the response-lost activity")
                .isTrue();
            ReconciliationOutcome result = workflow.result(deadline.remaining(RESULT_CAP));

            assertThat(result).isEqualTo(providerOutcome);
            assertThat(probe.observationCount()).isEqualTo(1);
            assertThat(probe.mutationCount()).isZero();
            assertThat(harness.snapshot(tenantId, intentId)).isEqualTo(committed);
            harness.assertOneWorkflowExecution(workflowId);
            ReconciliationWorkflowReplayTest.inspectHistory(
                harness.history(workflowId), tenantId, intentId, providerOutcome);
            childB.stop(deadline, STOP_CAP);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final TemporalTestCertificates.Material certificates;
        private final TemporalMtlsTestServer server;
        private final WorkflowServiceStubs stubs;
        private final WorkflowClient client;
        private final Set<ChildProcess> children =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

        private Harness(
                TemporalTestCertificates.Material certificates,
                TemporalMtlsTestServer server,
                WorkflowServiceStubs stubs,
                WorkflowClient client) {
            this.certificates = certificates;
            this.server = server;
            this.stubs = stubs;
            this.client = client;
        }

        static Harness start(Path secretRoot, Deadline deadline) throws Exception {
            Files.createDirectories(secretRoot);
            TemporalTestCertificates.Material certificates =
                TemporalTestCertificates.generate(secretRoot);
            TemporalMtlsTestServer server = new TemporalMtlsTestServer(certificates);
            try {
                runInfrastructureStart(server, deadline.remaining(INFRASTRUCTURE_CAP));
                TemporalConnectionProperties properties = properties(
                    server.endpoint(), certificates);
                TemporalServiceStubsFactory stubsFactory =
                    new TemporalServiceStubsFactory(new TemporalSecretFileLoader(aclPolicy()));
                WorkflowServiceStubs stubs = stubsFactory.create(properties);
                try {
                    stubs.connect(deadline.remaining(Duration.ofSeconds(10)));
                    WorkflowClient client = WorkflowClient.newInstance(
                        stubs,
                        WorkflowClientOptions.newBuilder()
                            .setNamespace(ReconciliationWorkflowReplayTest.NAMESPACE)
                            .validateAndBuildWithDefaults());
                    return new Harness(certificates, server, stubs, client);
                } catch (Exception | Error failure) {
                    stubs.shutdownNow();
                    throw failure;
                }
            } catch (Exception | Error failure) {
                server.close();
                throw failure;
            }
        }

        private static void runInfrastructureStart(
                TemporalMtlsTestServer server, Duration timeout) throws Exception {
            ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "temporal-test-server-start");
                thread.setDaemon(true);
                return thread;
            });
            Future<?> start = executor.submit(server::start);
            try {
                start.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeoutFailure) {
                start.cancel(true);
                throw new AssertionError("Temporal infrastructure did not start within PT60S",
                    timeoutFailure);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(cause);
            } finally {
                executor.shutdownNow();
            }
        }

        private static TemporalSecretAclPolicy aclPolicy() throws IOException {
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                return new WindowsTemporalSecretAclPolicy();
            }
            return new PosixTemporalSecretAclPolicy();
        }

        private static TemporalConnectionProperties properties(
                String endpoint,
                TemporalTestCertificates.Material certificates) {
            return new TemporalConnectionProperties(
                endpoint,
                ReconciliationWorkflowReplayTest.NAMESPACE,
                ReconciliationWorkflowReplayTest.TASK_QUEUE,
                ReconciliationWorkflowReplayTest.SERVER_NAME,
                certificates.root(),
                certificates.client().certificate(),
                certificates.client().privateKey(),
                certificates.trustedCa(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));
        }

        void seedUnknown(UUID tenantId, UUID intentId) {
            JooqExternalIntentStore store = new JooqExternalIntentStore();
            DriverManagerDataSource apiDataSource = new DriverManagerDataSource(
                ControlPlaneTestRoles.jdbcUrlWithRole(
                    server.controlJdbcUrl(), "accord_api"),
                ControlPlaneTestRoles.API_LOGIN,
                ControlPlaneTestRoles.API_PASSWORD);
            DSLContext api = DSL.using(apiDataSource, SQLDialect.POSTGRES);
            ExternalIntentRegistration registration = api.transactionResult(configuration -> {
                DSLContext tx = DSL.using(configuration);
                tx.fetchValue(
                    "SELECT set_config('app.tenant_id', ?, true)", tenantId.toString());
                return store.record(tx, new ExternalIntentDefinition(
                    tenantId,
                    intentId,
                    "crash-recovery-" + intentId,
                    "repository",
                    "repository-" + intentId,
                    "gitlab",
                    "installation-" + intentId,
                    "repository-" + intentId,
                    "git.branch.create",
                    "delivery-work-item",
                    "work-item-" + intentId,
                    1,
                    DIGEST));
            });
            assertThat(registration).isInstanceOf(ExternalIntentRegistration.Created.class);

            WorkerTenantTransactions worker = new WorkerTenantTransactions(workerContext());
            worker.inTenant(tenantId, tx -> {
                ExecutionClaim claim = store.claimExecution(
                    tx, tenantId, intentId, "crash-seed-executor", Duration.ofMinutes(5));
                assertThat(claim).isInstanceOf(ExecutionClaim.Acquired.class);
                store.markExecutionOutcomeUnknown(
                    tx,
                    ((ExecutionClaim.Acquired) claim).permit(),
                    new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", null));
                return null;
            });
        }

        ChildProcess startChild(
                String instanceId,
                String probeEndpoint,
                String mode,
                Deadline deadline) throws Exception {
            Path javaExecutable = Path.of(
                System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows")
                    ? "java.exe" : "java");
            List<String> command = new ArrayList<>();
            command.add(javaExecutable.toString());
            command.add("-classpath");
            command.add(testRuntimeClasspath());
            command.add(ReconciliationWorkerChildMain.class.getName());
            command.add(server.endpoint());
            command.add(ReconciliationWorkflowReplayTest.NAMESPACE);
            command.add(ReconciliationWorkflowReplayTest.TASK_QUEUE);
            command.add(ReconciliationWorkflowReplayTest.SERVER_NAME);
            command.add(certificates.root().toString());
            command.add(certificates.client().certificate().toString());
            command.add(certificates.client().privateKey().toString());
            command.add(certificates.trustedCa().toString());
            command.add(server.controlJdbcUrl());
            command.add(server.controlWorkerUser());
            command.add(server.controlWorkerPassword());
            command.add(instanceId);
            command.add(probeEndpoint);
            command.add(mode);

            ChildProcess child = ChildProcess.start(command);
            children.add(child);
            if (!child.awaitLine(
                    ReconciliationWorkerChildMain.READY,
                    deadline.remaining(CHILD_READY_CAP))) {
                child.destroyForcibly(deadline, CHILD_EXIT_CAP);
                throw new AssertionError("child did not reach READY: " + child.output());
            }
            return child;
        }

        private static String testRuntimeClasspath() throws Exception {
            LinkedHashSet<String> entries = new LinkedHashSet<>();
            String processClasspath = System.getProperty("java.class.path", "");
            for (String entry : processClasspath.split(
                    java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (!entry.isBlank()) {
                    entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
                }
            }
            for (ClassLoader loader = ReconciliationCrashRecoveryIT.class.getClassLoader();
                    loader != null; loader = loader.getParent()) {
                if (loader instanceof URLClassLoader urls) {
                    for (URL url : urls.getURLs()) {
                        if ("file".equals(url.getProtocol())) {
                            entries.add(Path.of(url.toURI()).toRealPath().toString());
                        }
                    }
                }
            }
            assertThat(entries)
                .as("current Gradle test runtime classpath")
                .anyMatch(entry -> entry.endsWith("test")
                    || entry.contains("test-classes")
                    || entry.contains("classes\\java\\test")
                    || entry.contains("classes/java/test"));
            return String.join(java.io.File.pathSeparator, entries);
        }

        WorkflowHandle startWorkflow(
                String workflowId, ReconciliationWorkflowRef ref) {
            ReconciliationWorkflow workflow = client.newWorkflowStub(
                ReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(ReconciliationWorkflowReplayTest.TASK_QUEUE)
                    .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
                    .build());
            WorkflowClient.start(workflow::reconcile, ref);
            return new WorkflowHandle(WorkflowStub.fromTyped(workflow));
        }

        History history(String workflowId) {
            return client.fetchHistory(workflowId).getHistory();
        }

        void assertOneWorkflowExecution(String workflowId) {
            try (java.util.stream.Stream<?> executions = client.listExecutions(
                    "WorkflowId = \"" + workflowId + "\"")) {
                assertThat(executions.count()).isEqualTo(1);
            }
        }

        TerminalSnapshot snapshot(UUID tenantId, UUID intentId) {
            return new WorkerTenantTransactions(workerContext()).inTenant(tenantId, tx -> {
                Record intent = tx.fetchOne("""
                    SELECT state,outcome_digest::text AS outcome_digest,last_error_code,
                           reconciliation_generation
                    FROM public.external_call_intent
                    WHERE tenant_id=? AND intent_id=?
                    """, tenantId, intentId);
                assertThat(intent).isNotNull();
                List<Record> events = tx.fetch("""
                    SELECT event_id,sequence,event_type,actor_id,payload::text AS payload
                    FROM public.domain_event
                    WHERE tenant_id=? AND aggregate_id=?
                    ORDER BY event_id
                    """, tenantId, intentId);
                List<Record> outbox = tx.fetch("""
                    SELECT event_id,destination,payload_schema,payload::text AS payload
                    FROM public.outbox_event
                    WHERE tenant_id=?
                      AND event_id IN (
                        SELECT event_id FROM public.domain_event
                        WHERE tenant_id=? AND aggregate_id=?)
                    ORDER BY event_id
                    """, tenantId, tenantId, intentId);
                return new TerminalSnapshot(
                    intent.get("state", String.class),
                    intent.get("outcome_digest", String.class),
                    intent.get("last_error_code", String.class),
                    Objects.requireNonNull(
                        intent.get("reconciliation_generation", Long.class)),
                    immutableRows(events),
                    immutableRows(outbox));
            });
        }

        private static List<List<String>> immutableRows(List<Record> rows) {
            List<List<String>> result = new ArrayList<>();
            for (Record row : rows) {
                List<String> values = new ArrayList<>();
                for (int index = 0; index < row.size(); index++) {
                    values.add(Objects.toString(row.get(index), null));
                }
                result.add(List.copyOf(values));
            }
            return List.copyOf(result);
        }

        private DSLContext workerContext() {
            DriverManagerDataSource workerDataSource = new DriverManagerDataSource(
                ControlPlaneTestRoles.jdbcUrlWithRole(
                    server.controlJdbcUrl(), "accord_worker"),
                server.controlWorkerUser(),
                server.controlWorkerPassword());
            return DSL.using(workerDataSource, SQLDialect.POSTGRES);
        }

        @Override
        public void close() {
            for (ChildProcess child : children) {
                child.close();
            }
            stubs.shutdownNow();
            stubs.awaitTermination(10, TimeUnit.SECONDS);
            server.close();
        }
    }

    private record WorkflowHandle(WorkflowStub stub) {
        ReconciliationOutcome result(Duration timeout) throws TimeoutException {
            return stub.getResult(
                timeout.toNanos(), TimeUnit.NANOSECONDS, ReconciliationOutcome.class);
        }
    }

    private record TerminalSnapshot(
        String state,
        String outcomeDigest,
        String lastErrorCode,
        long reconciliationGeneration,
        List<List<String>> events,
        List<List<String>> outbox
    ) {
        void assertSingleTerminal(
                String expectedState,
                long expectedGeneration,
                String expectedActor) {
            assertThat(state).isEqualTo(expectedState);
            assertThat(outcomeDigest).matches("^sha256:[0-9a-f]{64}$");
            assertThat(reconciliationGeneration).isEqualTo(expectedGeneration);
            assertThat(events).hasSize(1);
            assertThat(outbox).hasSize(1);
            assertThat(events.get(0).get(1)).isEqualTo(Long.toString(expectedGeneration));
            assertThat(events.get(0).get(2)).isEqualTo("external_intent.completed");
            assertThat(events.get(0).get(3)).isEqualTo(expectedActor);
            assertThat(outbox.get(0).get(0)).isEqualTo(events.get(0).get(0));
        }
    }

    private static final class ChildProcess implements AutoCloseable {
        private final Process process;
        private final BlockingQueue<String> unreadOutput = new LinkedBlockingQueue<>();
        private final List<String> output = new CopyOnWriteArrayList<>();
        private final BufferedWriter input;
        private final Thread outputPump;

        private ChildProcess(Process process) {
            this.process = process;
            this.input = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.US_ASCII));
            this.outputPump = new Thread(this::pumpOutput, "reconciliation-child-output");
            this.outputPump.setDaemon(true);
            this.outputPump.start();
        }

        static ChildProcess start(List<String> command) throws IOException {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            return new ChildProcess(process);
        }

        boolean awaitLine(String expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                String line = unreadOutput.poll(remaining, TimeUnit.NANOSECONDS);
                if (line == null) {
                    return false;
                }
                if (expected.equals(line)) {
                    return true;
                }
                if (!process.isAlive() && unreadOutput.isEmpty()) {
                    return false;
                }
            }
        }

        void destroyForcibly(Deadline deadline, Duration cap) throws InterruptedException {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            assertThat(process.waitFor(
                    deadline.remaining(cap).toNanos(), TimeUnit.NANOSECONDS))
                .as("forced child exit")
                .isTrue();
            outputPump.join(Math.min(
                TimeUnit.NANOSECONDS.toMillis(deadline.remaining(cap).toNanos()), 1_000));
        }

        void stop(Deadline deadline, Duration cap) throws Exception {
            input.write("STOP\n");
            input.flush();
            assertThat(awaitLine(
                    ReconciliationWorkerChildMain.STOPPED,
                    deadline.remaining(cap)))
                .as("Child B acknowledged bounded stop")
                .isTrue();
            assertThat(process.waitFor(
                    deadline.remaining(cap).toNanos(), TimeUnit.NANOSECONDS))
                .as("Child B exited after explicit stop")
                .isTrue();
            assertThat(process.exitValue()).isZero();
        }

        String output() {
            return String.join("\n", output);
        }

        private void pumpOutput() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                    unreadOutput.add(line);
                }
            } catch (IOException failure) {
                if (process.isAlive()) {
                    output.add("OUTPUT_PUMP_FAILED");
                }
            }
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                input.close();
            } catch (IOException ignored) {
                // The killed process has already closed its stdin pipe.
            }
        }
    }

    private static final class Deadline {
        private final long deadlineNanos;

        private Deadline(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        static Deadline start(Duration budget) {
            Objects.requireNonNull(budget, "budget");
            return new Deadline(Math.addExact(System.nanoTime(), budget.toNanos()));
        }

        Duration remaining(Duration localCap) {
            Objects.requireNonNull(localCap, "localCap");
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("reconciliation crash budget expired");
            }
            return Duration.ofNanos(Math.min(localCap.toNanos(), remaining));
        }

        void awaitUntil(long targetNanos) throws InterruptedException {
            while (true) {
                long now = System.nanoTime();
                long remainingToTarget = targetNanos - now;
                if (remainingToTarget <= 0) {
                    return;
                }
                long budgetRemaining = deadlineNanos - now;
                if (budgetRemaining <= 0) {
                    throw new AssertionError("reconciliation crash budget expired");
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingToTarget, budgetRemaining));
            }
        }
    }
}
