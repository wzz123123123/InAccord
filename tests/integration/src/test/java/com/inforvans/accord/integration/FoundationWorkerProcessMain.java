package com.inforvans.accord.integration;

import com.inforvans.accord.controlplane.worker.reconciliation.FencedReconciliationObservation;
import com.inforvans.accord.controlplane.worker.reconciliation.ProviderObservationPort;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationFailure;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationRuntimeProperties;
import com.inforvans.accord.controlplane.worker.reconciliation.WorkerTenantTransactions;
import com.inforvans.accord.controlplane.worker.temporal.PosixTemporalSecretAclPolicy;
import com.inforvans.accord.controlplane.worker.temporal.TemporalConnectionProperties;
import com.inforvans.accord.controlplane.worker.temporal.TemporalSecretAclPolicy;
import com.inforvans.accord.controlplane.worker.temporal.TemporalSecretFileLoader;
import com.inforvans.accord.controlplane.worker.temporal.TemporalServiceStubsFactory;
import com.inforvans.accord.controlplane.worker.temporal.TemporalWorkerLifecycle;
import com.inforvans.accord.controlplane.worker.temporal.WindowsTemporalSecretAclPolicy;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReadOnlyReconciliationActivity;
import com.inforvans.accord.observability.AccordWorkflowTelemetry;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import com.inforvans.accord.reliability.ReconciliationLease;
import com.inforvans.accord.reliability.ReconciliationResolution;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Test-support process entry point around the production worker lifecycle and activity stack. */
public final class FoundationWorkerProcessMain {
    static final String READY_PREFIX = "worker-ready-";
    static final String OBSERVATION_PREFIX = "worker-observation-";
    static final String STOPPED_PREFIX = "worker-stopped-";

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(8);
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);
    private static final String PROVIDER_OUTCOME_DIGEST = "sha256:" + "b".repeat(64);

    private FoundationWorkerProcessMain() {
    }

    public static void main(String[] args) throws Exception {
        ChildArguments values = ChildArguments.parse(args);
        HikariDataSource dataSource = null;
        TemporalWorkerLifecycle lifecycle = null;
        ConfigurableApplicationContext telemetry = null;
        Throwable primary = null;
        try {
            dataSource = dataSource(values);
            DSLContext context = callerOwnedDsl(dataSource);
            WorkerTenantTransactions transactions = new WorkerTenantTransactions(context);
            telemetry = telemetry(values);
            AccordWorkflowTelemetry workflowTelemetry =
                telemetry.getBean(AccordWorkflowTelemetry.class);
            ProviderObservationPort provider =
                new ProcessObservationPort(values);
            FencedReconciliationObservation observation = new FencedReconciliationObservation(
                transactions,
                new JooqExternalIntentStore(),
                provider,
                new ReconciliationRuntimeProperties(
                    "ft17-foundation-worker-" + values.epoch(), CLAIM_LEASE),
                workflowTelemetry);

            TemporalConnectionProperties temporal = new TemporalConnectionProperties(
                values.temporalEndpoint(), values.namespace(), values.taskQueue(),
                values.serverName(), values.secretRoot(), values.clientCertificate(),
                values.clientPrivateKey(), values.trustCertificate(),
                RPC_TIMEOUT, SHUTDOWN_TIMEOUT);
            TemporalServiceStubsFactory stubsFactory = new TemporalServiceStubsFactory(
                new TemporalSecretFileLoader(aclPolicy()));
            lifecycle = new TemporalWorkerLifecycle(
                temporal, stubsFactory, new ReadOnlyReconciliationActivity(observation));
            lifecycle.start();
            marker(values.markerRoot(), READY_PREFIX + values.epoch());

            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    System.in, StandardCharsets.US_ASCII))) {
                String command = input.readLine();
                if (!"STOP".equals(command)) {
                    throw new IllegalArgumentException("invalid worker process command");
                }
            }
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            HikariDataSource ownedDataSource = dataSource;
            TemporalWorkerLifecycle ownedLifecycle = lifecycle;
            ConfigurableApplicationContext ownedTelemetry = telemetry;
            cleanupAll(primary,
                () -> {
                    if (ownedLifecycle != null) ownedLifecycle.stop();
                },
                () -> {
                    if (ownedDataSource != null) ownedDataSource.close();
                },
                () -> {
                    if (ownedTelemetry != null) ownedTelemetry.close();
                });
        }
        marker(values.markerRoot(), STOPPED_PREFIX + values.epoch());
    }

    private static ConfigurableApplicationContext telemetry(ChildArguments values) {
        return new SpringApplicationBuilder(WorkerTelemetryApplication.class)
            .web(WebApplicationType.NONE)
            .registerShutdownHook(false)
            .properties(
                "spring.application.name=accord-control-worker",
                "spring.main.banner-mode=off",
                "accord.telemetry.endpoint=" + values.telemetryEndpoint(),
                "accord.telemetry.environment=test",
                "accord.telemetry.service-version=0.1.0",
                "accord.telemetry.queue-capacity=256",
                "accord.telemetry.batch-size=64",
                "accord.telemetry.export-timeout=PT2S",
                "accord.telemetry.shutdown-timeout=PT5S",
                "management.tracing.sampling.probability=1.0",
                "accord.telemetry.sentinels="
                    + values.webhookSentinel() + "," + values.certificateSentinel())
            .run();
    }

    private static HikariDataSource dataSource(ChildArguments values) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("foundation-worker-" + values.epoch());
        config.setJdbcUrl(values.jdbcUrl());
        config.setUsername(values.jdbcUser());
        config.setPassword(values.jdbcPassword());
        config.setConnectionInitSql("SET ROLE accord_worker");
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setInitializationFailTimeout(2_000);
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

    private static TemporalSecretAclPolicy aclPolicy() throws IOException {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            return new WindowsTemporalSecretAclPolicy();
        }
        return new PosixTemporalSecretAclPolicy();
    }

    private static void marker(Path root, String name) throws IOException {
        Files.writeString(
            root.resolve(name),
            "observed\n",
            StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
    }

    private static void awaitForcedTermination() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ReconciliationFailure.of(
                ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
        }
    }

    private static void cleanupAll(Throwable primary, Cleanup... cleanups) throws Exception {
        Throwable failure = primary;
        for (Cleanup cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (Exception | Error cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
        }
        if (primary == null && failure != null) {
            if (failure instanceof Exception exception) throw exception;
            throw (Error) failure;
        }
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws Exception;
    }

    private static final class ProcessObservationPort implements ProviderObservationPort {
        private final ChildArguments values;

        private ProcessObservationPort(ChildArguments values) {
            this.values = values;
        }

        @Override
        public ReconciliationResolution observe(ReconciliationLease lease, Duration timeout) {
            if (!Duration.ofSeconds(2).equals(timeout)
                    || !values.tenantId().equals(lease.tenantId())
                    || !values.intentId().equals(lease.intentId())
                    || TransactionSynchronizationManager.isActualTransactionActive()) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
            }
            try {
                marker(values.markerRoot(), OBSERVATION_PREFIX + values.epoch());
            } catch (IOException failure) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
            }
            if (values.epoch() == 1) awaitForcedTermination();
            return new ReconciliationResolution.Succeeded(PROVIDER_OUTCOME_DIGEST, null);
        }
    }

    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        JooqAutoConfiguration.class
    })
    private static final class WorkerTelemetryApplication {
    }

    private record ChildArguments(
        int epoch,
        String temporalEndpoint,
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
        UUID tenantId,
        UUID intentId,
        Path markerRoot,
        String telemetryEndpoint,
        String webhookSentinel,
        String certificateSentinel
    ) {
        private ChildArguments {
            if (epoch != 1 && epoch != 2) {
                throw new IllegalArgumentException("invalid worker epoch");
            }
            required(temporalEndpoint, "temporal endpoint");
            required(namespace, "namespace");
            required(taskQueue, "task queue");
            required(serverName, "server name");
            Objects.requireNonNull(secretRoot, "secretRoot");
            Objects.requireNonNull(clientCertificate, "clientCertificate");
            Objects.requireNonNull(clientPrivateKey, "clientPrivateKey");
            Objects.requireNonNull(trustCertificate, "trustCertificate");
            required(jdbcUrl, "JDBC URL");
            required(jdbcUser, "JDBC user");
            required(jdbcPassword, "JDBC password");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(intentId, "intentId");
            Objects.requireNonNull(markerRoot, "markerRoot");
            if (!"http://127.0.0.1:4318".equals(telemetryEndpoint)
                    || !webhookSentinel.matches("^[A-Za-z0-9+/]{43}=$")
                    || !certificateSentinel.matches("^[A-Za-z0-9_-]{43}$")) {
                throw new IllegalArgumentException("invalid worker telemetry handoff");
            }
        }

        static ChildArguments parse(String[] args) {
            if (args.length != 1) {
                throw new IllegalArgumentException("worker epoch argument is required");
            }
            return new ChildArguments(
                Integer.parseInt(args[0]),
                environment("ACCORD_FT17_TEMPORAL_ENDPOINT"),
                environment("ACCORD_FT17_TEMPORAL_NAMESPACE"),
                environment("ACCORD_FT17_TEMPORAL_TASK_QUEUE"),
                environment("ACCORD_FT17_TEMPORAL_SERVER_NAME"),
                Path.of(environment("ACCORD_FT17_TEMPORAL_SECRET_ROOT")),
                Path.of(environment("ACCORD_FT17_TEMPORAL_CLIENT_CERTIFICATE")),
                Path.of(environment("ACCORD_FT17_TEMPORAL_CLIENT_PRIVATE_KEY")),
                Path.of(environment("ACCORD_FT17_TEMPORAL_TRUST_CERTIFICATE")),
                environment("ACCORD_FT17_JDBC_URL"),
                environment("ACCORD_FT17_JDBC_USER"),
                environment("ACCORD_FT17_JDBC_PASSWORD"),
                UUID.fromString(environment("ACCORD_FT17_TENANT_ID")),
                UUID.fromString(environment("ACCORD_FT17_INTENT_ID")),
                Path.of(environment("ACCORD_FT17_MARKER_ROOT")),
                environment("ACCORD_FT17_TELEMETRY_ENDPOINT"),
                environment("ACCORD_FT17_WEBHOOK_HMAC_SECRET"),
                environment("ACCORD_FT17_DEMO_CERTIFICATE"));
        }

        private static String environment(String name) {
            return required(System.getenv(name), name);
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid " + name);
            }
            return value;
        }
    }
}
