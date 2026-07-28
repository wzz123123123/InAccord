package com.inforvans.accord.controlplane.worker.temporal;

import com.inforvans.accord.controlplane.worker.reconciliation.FencedReconciliationObservation;
import com.inforvans.accord.controlplane.worker.reconciliation.ProviderObservationPort;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationFailure;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationRuntimeProperties;
import com.inforvans.accord.controlplane.worker.reconciliation.WorkerTenantTransactions;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReadOnlyReconciliationActivity;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationObservationPort;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import com.inforvans.accord.reliability.ReconciliationResolution;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

public final class ReconciliationWorkerChildMain {
    static final String READY = "READY";
    static final String ACTIVITY_ATTEMPT = "ACTIVITY_ATTEMPT";
    static final String TX2_COMMITTED_RESPONSE_PENDING =
        "TX2_COMMITTED_RESPONSE_PENDING";
    static final String SHUTDOWN_HOOK_COMPLETED = "SHUTDOWN_HOOK_COMPLETED";
    static final String STOPPED = "STOPPED";

    private static final String NAMESPACE = "accord-reconciliation-test-v1";
    private static final String TASK_QUEUE = "accord-reconciliation-v1";
    private static final String SERVER_NAME = "temporal.test";
    private static final Set<String> INSTANCE_IDS = Set.of(
        "reconciliation-crash-a", "reconciliation-crash-b");
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(8);
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final String SUCCESS_DIGEST = "sha256:" + "a".repeat(64);
    private static final String NO_EFFECT_DIGEST = "sha256:" + "b".repeat(64);
    private static final String DIVERGED_DIGEST = "sha256:" + "c".repeat(64);

    private ReconciliationWorkerChildMain() {
    }

    public static void main(String[] args) throws Exception {
        ChildArguments values = ChildArguments.parse(args);
        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> System.out.println(SHUTDOWN_HOOK_COMPLETED),
            "reconciliation-child-shutdown-marker"));

        HikariDataSource dataSource = dataSource(values);
        TemporalWorkerLifecycle lifecycle = null;
        try {
            DSLContext context = DSL.using(dataSource, SQLDialect.POSTGRES);
            WorkerTenantTransactions transactions = new WorkerTenantTransactions(context);
            ProviderObservationPort provider = new HttpObservationPort(values.probeEndpoint());
            FencedReconciliationObservation fenced = new FencedReconciliationObservation(
                transactions,
                new JooqExternalIntentStore(),
                provider,
                new ReconciliationRuntimeProperties(values.instanceId(), CLAIM_LEASE));
            ReconciliationObservationPort instrumented = ref -> {
                System.out.println(ACTIVITY_ATTEMPT);
                ReconciliationOutcome outcome = fenced.observe(ref);
                if (values.mode() == Mode.POST_TX2_RESPONSE_LOSS) {
                    System.out.println(TX2_COMMITTED_RESPONSE_PENDING);
                    awaitForcedTermination();
                }
                return outcome;
            };

            TemporalConnectionProperties temporal = new TemporalConnectionProperties(
                values.endpoint(), values.namespace(), values.taskQueue(), values.serverName(),
                values.secretRoot(), values.clientCertificate(), values.clientPrivateKey(),
                values.trustCertificate(), RPC_TIMEOUT, SHUTDOWN_TIMEOUT);
            TemporalSecretAclPolicy aclPolicy = aclPolicy();
            TemporalServiceStubsFactory stubsFactory = new TemporalServiceStubsFactory(
                new TemporalSecretFileLoader(aclPolicy));
            lifecycle = new TemporalWorkerLifecycle(
                temporal, stubsFactory, new ReadOnlyReconciliationActivity(instrumented));
            lifecycle.start();
            System.out.println(READY);

            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, java.nio.charset.StandardCharsets.US_ASCII))) {
                String command;
                while ((command = input.readLine()) != null) {
                    if ("STOP".equals(command)) {
                        break;
                    }
                    throw new IllegalArgumentException("unknown child command");
                }
            }
        } finally {
            if (lifecycle != null) {
                lifecycle.stop();
            }
            dataSource.close();
        }
        System.out.println(STOPPED);
    }

    private static void awaitForcedTermination() {
        CountDownLatch neverReleased = new CountDownLatch(1);
        try {
            neverReleased.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ReconciliationFailure.of(
                ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
        }
    }

    private static TemporalSecretAclPolicy aclPolicy() throws IOException {
        String osName = System.getProperty("os.name", "");
        if (osName.startsWith("Windows")) {
            return new WindowsTemporalSecretAclPolicy();
        }
        return new PosixTemporalSecretAclPolicy();
    }

    private static HikariDataSource dataSource(ChildArguments values) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("reconciliation-" + values.instanceId());
        config.setJdbcUrl(values.jdbcUrl());
        config.setUsername(values.jdbcUser());
        config.setPassword(values.jdbcPassword());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setInitializationFailTimeout(2_000);
        config.setConnectionTestQuery("SELECT 1");
        config.addDataSourceProperty("connectTimeout", "2");
        config.addDataSourceProperty("socketTimeout", "4");
        config.addDataSourceProperty("cancelSignalTimeout", "2");
        config.addDataSourceProperty("options", String.join(" ",
            "-c role=accord_worker",
            "-c statement_timeout=2000",
            "-c lock_timeout=1000",
            "-c idle_in_transaction_session_timeout=3000",
            "-c transaction_timeout=6000"));
        return new HikariDataSource(config);
    }

    private enum Mode {
        NORMAL,
        POST_TX2_RESPONSE_LOSS
    }

    private record ChildArguments(
        String endpoint,
        String namespace,
        String taskQueue,
        String serverName,
        Path secretRoot,
        Path clientCertificate,
        Path clientPrivateKey,
        Path trustCertificate,
        String jdbcUrl,
        String jdbcUser,
        String jdbcPassword,
        String instanceId,
        URI probeEndpoint,
        Mode mode
    ) {
        private ChildArguments {
            Objects.requireNonNull(endpoint, "endpoint");
            if (!NAMESPACE.equals(namespace)
                    || !TASK_QUEUE.equals(taskQueue)
                    || !SERVER_NAME.equals(serverName)) {
                throw new IllegalArgumentException("unexpected Temporal child identity");
            }
            Objects.requireNonNull(secretRoot, "secretRoot");
            Objects.requireNonNull(clientCertificate, "clientCertificate");
            Objects.requireNonNull(clientPrivateKey, "clientPrivateKey");
            Objects.requireNonNull(trustCertificate, "trustCertificate");
            required(jdbcUrl, "jdbcUrl");
            required(jdbcUser, "jdbcUser");
            required(jdbcPassword, "jdbcPassword");
            if (!INSTANCE_IDS.contains(instanceId)) {
                throw new IllegalArgumentException("unexpected reconciliation instance ID");
            }
            if (probeEndpoint == null
                    || !"http".equals(probeEndpoint.getScheme())
                    || !probeEndpoint.getHost().equals("127.0.0.1")
                    || probeEndpoint.getPort() < 1
                    || !probeEndpoint.getPath().isEmpty()
                    || probeEndpoint.getRawQuery() != null
                    || probeEndpoint.getRawFragment() != null) {
                throw new IllegalArgumentException("invalid loopback probe endpoint");
            }
            Objects.requireNonNull(mode, "mode");
        }

        static ChildArguments parse(String[] args) {
            if (args.length != 14) {
                throw new IllegalArgumentException("expected 14 child arguments");
            }
            return new ChildArguments(
                args[0], args[1], args[2], args[3], Path.of(args[4]), Path.of(args[5]),
                Path.of(args[6]), Path.of(args[7]), args[8], args[9], args[10], args[11],
                URI.create(args[12]), Mode.valueOf(args[13].toUpperCase(Locale.ROOT)));
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("invalid " + name);
            }
            return value;
        }
    }

    private static final class HttpObservationPort implements ProviderObservationPort {
        private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        private final URI observeEndpoint;

        private HttpObservationPort(URI probeEndpoint) {
            this.observeEndpoint = probeEndpoint.resolve("/observe");
        }

        @Override
        public ReconciliationResolution observe(
                com.inforvans.accord.reliability.ReconciliationLease lease,
                Duration timeout) {
            Objects.requireNonNull(lease, "lease");
            if (!Duration.ofSeconds(2).equals(timeout)) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
            }
            HttpRequest request = HttpRequest.newBuilder(observeEndpoint)
                .timeout(timeout)
                .header("Accept", "text/plain")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            try {
                HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(
                        java.nio.charset.StandardCharsets.US_ASCII));
                if (response.statusCode() != 200) {
                    throw ReconciliationFailure.of(
                        ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
                }
                return switch (ReconciliationOutcome.valueOf(response.body())) {
                    case CONVERGED -> new ReconciliationResolution.Succeeded(
                        SUCCESS_DIGEST, null);
                    case CONFIRMED_NO_EFFECT ->
                        new ReconciliationResolution.ConfirmedNoEffect(
                            NO_EFFECT_DIGEST, null);
                    case DIVERGED -> new ReconciliationResolution.Diverged(
                        DIVERGED_DIGEST, "PROVIDER_DIVERGED", null);
                    case STILL_UNKNOWN -> new ReconciliationResolution.StillUnknown(
                        "OBSERVATION_UNAVAILABLE", null);
                };
            } catch (IOException failure) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
            } catch (IllegalArgumentException invalidResponse) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
            }
        }
    }
}
