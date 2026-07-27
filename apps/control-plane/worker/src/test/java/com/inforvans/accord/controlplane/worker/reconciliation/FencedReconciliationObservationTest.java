package com.inforvans.accord.controlplane.worker.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReadOnlyReconciliationActivity;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationActivities;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationObservationPort;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflow;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowImpl;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowRef;
import com.inforvans.accord.database.ControlPlaneTestRoles;
import com.inforvans.accord.reliability.ExecutionClaim;
import com.inforvans.accord.reliability.ExecutionResolution;
import com.inforvans.accord.reliability.ExternalIntentDefinition;
import com.inforvans.accord.reliability.ExternalIntentRef;
import com.inforvans.accord.reliability.ExternalIntentRegistration;
import com.inforvans.accord.reliability.ExternalIntentState;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import com.inforvans.accord.reliability.ReconciliationLease;
import com.inforvans.accord.reliability.ReconciliationResolution;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import javax.sql.DataSource;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.SQLDialect;
import org.jooq.TransactionContext;
import org.jooq.TransactionProvider;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class FencedReconciliationObservationTest {
    private static final UUID TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(8);
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302")
        .asCompatibleSubstituteFor("postgres");

    private PostgreSQLContainer<?> postgres;
    private HikariDataSource dataSource;
    private WorkerTenantTransactions transactions;
    private final JooqExternalIntentStore store = new JooqExternalIntentStore();

    @BeforeAll
    void startDatabase() throws Exception {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
        postgres.start();
        ControlPlaneTestRoles.bootstrap(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway flyway = Flyway.configure()
            .dataSource(
                postgres.getJdbcUrl(),
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

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(postgres.getJdbcUrl());
        hikari.setUsername(ControlPlaneTestRoles.WORKER_LOGIN);
        hikari.setPassword(ControlPlaneTestRoles.WORKER_PASSWORD);
        hikari.setConnectionInitSql("SET ROLE accord_worker");
        hikari.setConnectionTimeout(2_000);
        hikari.setValidationTimeout(1_000);
        hikari.setMaximumPoolSize(1);
        hikari.addDataSourceProperty("connectTimeout", "2");
        hikari.addDataSourceProperty("socketTimeout", "4");
        hikari.addDataSourceProperty("cancelSignalTimeout", "2");
        hikari.addDataSourceProperty("options", String.join(" ",
            "-c statement_timeout=2000",
            "-c lock_timeout=1000",
            "-c idle_in_transaction_session_timeout=3000",
            "-c transaction_timeout=6000"));
        dataSource = new HikariDataSource(hikari);
        transactions = new WorkerTenantTransactions(callerOwnedDsl(dataSource));
    }

    @AfterAll
    void stopDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
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
                  domain_event, aggregate_head, idempotency_result,
                  contract_validation CASCADE
                """);
        }
    }

    @Test
    void tenantTransactionsExposeOnlyCallerOwnedScopedWork() {
        Method[] publicMethods = Arrays.stream(WorkerTenantTransactions.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .toArray(Method[]::new);

        assertThat(publicMethods).hasSize(1);
        assertThat(publicMethods[0].getName()).isEqualTo("inTenant");
        assertThat(publicMethods[0].getParameterTypes())
            .containsExactly(UUID.class, java.util.function.Function.class);
        assertThat(publicMethods[0].getGenericReturnType().getTypeName()).isEqualTo("T");
        assertThat(Arrays.stream(WorkerTenantTransactions.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName()))
            .containsExactly(DSLContext.class.getName());
    }

    @Test
    void tenantTransactionsUseTheCallerTransactionProvider() {
        var callerConfiguration = DSL.using(dataSource, SQLDialect.POSTGRES).configuration();
        TransactionProvider delegate = callerConfiguration.transactionProvider();
        AtomicInteger begins = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        TransactionProvider recording = new TransactionProvider() {
            @Override
            public void begin(TransactionContext context) {
                begins.incrementAndGet();
                delegate.begin(context);
            }

            @Override
            public void commit(TransactionContext context) {
                commits.incrementAndGet();
                delegate.commit(context);
            }

            @Override
            public void rollback(TransactionContext context) {
                rollbacks.incrementAndGet();
                delegate.rollback(context);
            }
        };
        WorkerTenantTransactions callerTransactions = new WorkerTenantTransactions(
            DSL.using(callerConfiguration.derive(recording)));

        String role = callerTransactions.inTenant(TENANT_ID,
            tx -> tx.fetchOne("SELECT current_user").get(0, String.class));

        assertThat(role).isEqualTo("accord_worker");
        assertThat(begins).hasValue(1);
        assertThat(commits).hasValue(1);
        assertThat(rollbacks).hasValue(0);
    }

    @Test
    void tenantTransactionsRejectAnySessionRoleOtherThanAccordWorker()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                 postgres.getJdbcUrl(),
                 ControlPlaneTestRoles.API_LOGIN,
                 ControlPlaneTestRoles.API_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE accord_api");
            WorkerTenantTransactions apiTransactions = new WorkerTenantTransactions(
                DSL.using(connection, SQLDialect.POSTGRES));
            AtomicInteger callbacks = new AtomicInteger();

            assertThatThrownBy(() -> apiTransactions.inTenant(TENANT_ID, tx -> {
                    callbacks.incrementAndGet();
                    return null;
                }))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("worker tenant context was not installed");

            assertThat(callbacks).hasValue(0);
        }
    }

    @Test
    void reconciliationConfigurationIsClosedAndFixed() {
        assertThatThrownBy(() -> new ReconciliationRuntimeProperties(null, Duration.ofSeconds(8)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconciliationRuntimeProperties("", Duration.ofSeconds(8)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconciliationRuntimeProperties("-worker", Duration.ofSeconds(8)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconciliationRuntimeProperties("worker/one", Duration.ofSeconds(8)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconciliationRuntimeProperties(
                "a" + "b".repeat(128), Duration.ofSeconds(8)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconciliationRuntimeProperties("worker-one", Duration.ofSeconds(9)))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(new ReconciliationRuntimeProperties("a", Duration.ofSeconds(8)).instanceId())
            .isEqualTo("a");
        assertThat(new ReconciliationRuntimeProperties(
                "a._:-Z9", Duration.ofSeconds(8)).instanceId())
            .isEqualTo("a._:-Z9");
        String maximumInstanceId = "a" + "b".repeat(127);
        ReconciliationRuntimeProperties maximumProperties =
            new ReconciliationRuntimeProperties(maximumInstanceId, Duration.ofSeconds(8));
        assertThat(maximumProperties.instanceId()).isEqualTo(maximumInstanceId);
        assertThat(maximumProperties.claimLease()).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void postgresFixtureEnforcesTheExactVersionAndRuntimeTimeouts() {
        assertThat(dataSource.getConnectionInitSql()).isEqualTo("SET ROLE accord_worker");
        assertThat(dataSource.getConnectionTimeout()).isEqualTo(2_000L);
        assertThat(dataSource.getValidationTimeout()).isEqualTo(1_000L);
        assertThat(dataSource.getDataSourceProperties())
            .containsEntry("connectTimeout", "2")
            .containsEntry("socketTimeout", "4")
            .containsEntry("cancelSignalTimeout", "2");

        transactions.inTenant(TENANT_ID, tx -> {
            assertThat(tx.fetchOne(
                    "SELECT current_setting('server_version_num')")
                    .get(0, String.class))
                .startsWith("17");
            assertThat(tx.fetch("""
                    SELECT name, setting, unit
                    FROM pg_catalog.pg_settings
                    WHERE name IN (
                      'statement_timeout',
                      'lock_timeout',
                      'idle_in_transaction_session_timeout',
                      'transaction_timeout')
                    ORDER BY name
                    """).map(row -> row.get("name", String.class)
                        + "=" + row.get("setting", String.class)
                        + row.get("unit", String.class)))
                .containsExactly(
                    "idle_in_transaction_session_timeout=3000ms",
                    "lock_timeout=1000ms",
                    "statement_timeout=2000ms",
                    "transaction_timeout=6000ms");
            return null;
        });
    }

    @Test
    void providerPortIsReadOnlyAndReturnsOnlyClosedResolution() throws Exception {
        Method observe = ProviderObservationPort.class.getDeclaredMethod(
            "observe", ReconciliationLease.class, Duration.class);

        assertThat(ProviderObservationPort.class.getDeclaredMethods()).containsExactly(observe);
        assertThat(observe.getReturnType()).isEqualTo(ReconciliationResolution.class);
    }

    @Test
    void providerObservationNeverCallsAMutationSurface() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-provider-no-mutation-001");
        ProviderProbe provider = new ProviderProbe((lease, timeout) -> {
            assertThat(lease.intentId()).isEqualTo(intent.intentId());
            assertThat(timeout).isEqualTo(Duration.ofSeconds(2));
            return new ReconciliationResolution.Succeeded(digest('b'), null);
        });
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            provider,
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            ignored -> {});

        assertThat(observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isEqualTo(ReconciliationOutcome.CONVERGED);

        assertThat(provider.observeCalls()).isEqualTo(1);
        assertThat(provider.mutateCalls()).isZero();
        ExternalIntentState state = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(state).isEqualTo(ExternalIntentState.SUCCEEDED);
        assertThat(count("domain_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void productionHasOneHighLevelReadOnlyObservationBoundary() {
        var productionClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.inforvans.accord.controlplane.worker");
        List<String> implementations = productionClasses.stream()
            .filter(type -> !type.isInterface())
            .filter(type -> type.isAssignableTo(ReconciliationObservationPort.class))
            .map(type -> type.getName())
            .sorted()
            .toList();

        assertThat(implementations).containsExactly(
            FencedReconciliationObservation.class.getName());
        assertThat(Modifier.isFinal(FencedReconciliationObservation.class.getModifiers()))
            .isTrue();
        assertThat(Arrays.stream(ReadOnlyReconciliationActivity.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
            .containsExactly(ReconciliationObservationPort.class.getName());
    }

    @Test
    void databaseFailuresUseOnlyTheExactRetryableEvidenceAllowlist()
            throws Exception {
        for (String sqlState : List.of(
                "08006", "40001", "53000", "57014", "57P01", "25P04")) {
            assertPrivateDatabaseFailure(
                new IllegalArgumentException(
                    "outer secret",
                    new org.jooq.exception.DataAccessException(
                        "jooq secret",
                        new java.sql.SQLException("sql secret", sqlState))),
                ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
        }
        assertPrivateDatabaseFailure(
            new IllegalArgumentException(
                "outer secret",
                new org.jooq.exception.DataAccessException(
                    "jooq secret",
                    new java.sql.SQLTransientConnectionException(
                        "sql secret", (String) null))),
            ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
        for (String sqlState : List.of("P0001", "25000")) {
            assertPrivateDatabaseFailure(
                new IllegalArgumentException(
                    "outer secret",
                    new org.jooq.exception.DataAccessException(
                        "jooq secret",
                        new java.sql.SQLException("sql secret", sqlState))),
                ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
        }
    }

    @Test
    void missingIntentFailsClosedWithoutObservationOrPersistence() throws Exception {
        UUID missingIntentId = UUID.randomUUID();
        AtomicInteger providerCalls = new AtomicInteger();
        List<UUID> heartbeats = new ArrayList<>();
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::add);

        assertFailure(ReconciliationFailure.Code.RECONCILIATION_MISSING, () ->
            observation.observe(new ReconciliationWorkflowRef(TENANT_ID, missingIntentId)));

        assertThat(providerCalls).hasValue(0);
        assertThat(heartbeats).isEmpty();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void liveReconciliationFailsBusyWithoutObservationOrPersistence() throws Exception {
        ExternalIntentRef busy = seedOutcomeUnknown("action-live-reconciliation-001");
        transactions.inTenant(TENANT_ID, tx -> store.claimReconciliation(
            tx, TENANT_ID, busy.intentId(), "other-reconciler", CLAIM_LEASE));
        AtomicInteger providerCalls = new AtomicInteger();
        List<UUID> heartbeats = new ArrayList<>();
        FencedReconciliationObservation observation = countingObservation(
            providerCalls, heartbeats);

        assertFailure(ReconciliationFailure.Code.RECONCILIATION_BUSY, () ->
            observation.observe(new ReconciliationWorkflowRef(TENANT_ID, busy.intentId())));

        assertThat(providerCalls).hasValue(0);
        assertThat(heartbeats).isEmpty();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void recordedAndExecutingStatesFailNonReconcilableWithoutSideEffects()
            throws Exception {
        ExternalIntentRef recorded = record("action-recorded-nonreconcilable-001");
        ExternalIntentRef executing = record("action-executing-nonreconcilable-001");
        transactions.inTenant(TENANT_ID, tx -> store.claimExecution(
            tx, TENANT_ID, executing.intentId(), "execution-worker", CLAIM_LEASE));
        AtomicInteger providerCalls = new AtomicInteger();
        List<UUID> heartbeats = new ArrayList<>();
        FencedReconciliationObservation observation = countingObservation(
            providerCalls, heartbeats);

        assertFailure(ReconciliationFailure.Code.RECONCILIATION_NOT_RECONCILABLE, () ->
            observation.observe(new ReconciliationWorkflowRef(TENANT_ID, recorded.intentId())));
        assertFailure(ReconciliationFailure.Code.RECONCILIATION_NOT_RECONCILABLE, () ->
            observation.observe(new ReconciliationWorkflowRef(TENANT_ID, executing.intentId())));

        assertThat(providerCalls).hasValue(0);
        assertThat(heartbeats).isEmpty();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void succeededIntentReplaysWithoutASecondObservationOrWrite() throws Exception {
        assertTerminalReplay(
            "action-replay-succeeded-001",
            new ReconciliationResolution.Succeeded(digest('b'), null),
            ReconciliationOutcome.CONVERGED,
            ExternalIntentState.SUCCEEDED);
    }

    @Test
    void confirmedNoEffectIntentReplaysWithoutASecondObservationOrWrite() throws Exception {
        assertTerminalReplay(
            "action-replay-no-effect-001",
            new ReconciliationResolution.ConfirmedNoEffect(digest('c'), null),
            ReconciliationOutcome.CONFIRMED_NO_EFFECT,
            ExternalIntentState.CONFIRMED_NO_EFFECT);
    }

    @Test
    void divergedIntentReplaysWithoutASecondObservationOrWrite() throws Exception {
        assertTerminalReplay(
            "action-replay-diverged-001",
            new ReconciliationResolution.Diverged(
                digest('d'), "REMOTE_STATE_DIVERGED", null),
            ReconciliationOutcome.DIVERGED,
            ExternalIntentState.DIVERGED);
    }

    @Test
    void acquiredObservationCommitsTerminalIntentEventAndOutboxAtomically() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-fenced-success-001");
        List<UUID> heartbeats = new ArrayList<>();
        heartbeats.add(intent.intentId());
        ProviderObservationPort provider = (lease, timeout) -> {
            assertThat(timeout).isEqualTo(Duration.ofSeconds(2));
            assertThat(lease.intentId()).isEqualTo(intent.intentId());
            assertThatNoException().isThrownBy(() -> {
                try (Connection ignored = dataSource.getConnection()) {
                    assertThat(ignored.isClosed()).isFalse();
                }
            });
            return new ReconciliationResolution.Succeeded(digest('b'), null);
        };
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            provider,
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::add);

        ReconciliationOutcome outcome = observation.observe(
            new ReconciliationWorkflowRef(TENANT_ID, intent.intentId()));

        assertThat(outcome).isEqualTo(ReconciliationOutcome.CONVERGED);
        assertThat(heartbeats).containsExactly(
            intent.intentId(), intent.intentId(), intent.intentId(),
            intent.intentId(), intent.intentId(), intent.intentId());
        ExternalIntentState state = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(state).isEqualTo(ExternalIntentState.SUCCEEDED);
        assertThat(count("domain_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertPersistedEvent(intent, ReconciliationOutcome.CONVERGED);
    }

    @Test
    void committedTerminalCompletionEmitsTelemetryAfterTheAtomicCommit() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-terminal-telemetry-001");
        AtomicInteger telemetrySignals = new AtomicInteger();
        FencedReconciliationObservation observation = observationWithTelemetry(
            (lease, timeout) -> new ReconciliationResolution.Succeeded(digest('b'), null),
            ignored -> {},
            () -> {
                telemetrySignals.incrementAndGet();
                try {
                    ExternalIntentState committedState = transactions.inTenant(
                        TENANT_ID,
                        tx -> store.load(tx, TENANT_ID, intent.intentId())
                            .orElseThrow()
                            .state());
                    assertThat(committedState).isEqualTo(ExternalIntentState.SUCCEEDED);
                    assertThat(count("domain_event")).isEqualTo(1);
                    assertThat(count("outbox_event")).isEqualTo(1);
                } catch (Exception failure) {
                    throw new AssertionError("terminal telemetry ran before commit", failure);
                }
            });

        assertThat(observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isEqualTo(ReconciliationOutcome.CONVERGED);

        assertThat(telemetrySignals).hasValue(1);
    }

    @Test
    void stillUnknownCompletionDoesNotEmitSuccessTelemetry() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-unknown-telemetry-001");
        AtomicInteger telemetrySignals = new AtomicInteger();
        FencedReconciliationObservation observation = observationWithTelemetry(
            (lease, timeout) -> new ReconciliationResolution.StillUnknown(
                "OBSERVATION_INCOMPLETE", null),
            ignored -> {},
            telemetrySignals::incrementAndGet);

        assertThat(observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isEqualTo(ReconciliationOutcome.STILL_UNKNOWN);

        assertThat(telemetrySignals).hasValue(0);
    }

    @Test
    void rolledBackTerminalCompletionDoesNotEmitSuccessTelemetry() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-rollback-telemetry-001");
        AtomicInteger telemetrySignals = new AtomicInteger();
        installTerminalWriteFailureTrigger(TerminalWriteStage.OUTBOX);
        FencedReconciliationObservation observation = observationWithTelemetry(
            (lease, timeout) -> new ReconciliationResolution.Succeeded(digest('b'), null),
            ignored -> {},
            telemetrySignals::incrementAndGet);
        try {
            assertFailure(ReconciliationFailure.Code.RECONCILIATION_INTERNAL, () ->
                observation.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));
        } finally {
            removeTerminalWriteFailureTrigger(TerminalWriteStage.OUTBOX);
        }

        assertThat(telemetrySignals).hasValue(0);
    }

    @Test
    void stillUnknownResolutionCommitsTheFencedUnknownOutcome() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-still-unknown-001");
        List<UUID> heartbeats = new ArrayList<>();
        heartbeats.add(intent.intentId());
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> new ReconciliationResolution.StillUnknown(
                "OBSERVATION_INCOMPLETE", "provider-request-001"),
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::add);

        assertThat(observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isEqualTo(ReconciliationOutcome.STILL_UNKNOWN);

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.OUTCOME_UNKNOWN);
        assertThat(snapshot.lastErrorCode()).isEqualTo("OBSERVATION_INCOMPLETE");
        assertThat(snapshot.providerRequestId()).isEqualTo("provider-request-001");
        assertThat(heartbeats).containsExactly(
            intent.intentId(), intent.intentId(), intent.intentId(),
            intent.intentId(), intent.intentId(), intent.intentId());
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void normalizedObservationFailureCommitsUnknownBeforeRethrow() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-normalized-error-001");
        List<UUID> heartbeats = new ArrayList<>();
        heartbeats.add(intent.intentId());
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::add);

        assertFailure(ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE, () ->
            observation.observe(new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.OUTCOME_UNKNOWN);
        assertThat(snapshot.lastErrorCode()).isEqualTo("OBSERVATION_UNAVAILABLE");
        assertThat(heartbeats).containsExactly(
            intent.intentId(), intent.intentId(), intent.intentId(),
            intent.intentId(), intent.intentId(), intent.intentId());
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void nullProviderResolutionFailsInternalWithoutOpeningTx2() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-null-resolution-001");
        AtomicInteger providerCalls = new AtomicInteger();
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                return null;
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            ignored -> {});

        assertFailure(ReconciliationFailure.Code.RECONCILIATION_INTERNAL, () ->
            observation.observe(new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(providerCalls).hasValue(1);
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(snapshot.reconciliationGeneration()).isEqualTo(1L);
        assertThat(snapshot.lastErrorCode()).isNull();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void unexpectedProviderFailureFailsInternalWithoutOpeningTx2() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-provider-internal-001");
        AtomicInteger providerCalls = new AtomicInteger();
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                throw new IllegalStateException("provider secret");
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            ignored -> {});

        assertFailure(ReconciliationFailure.Code.RECONCILIATION_INTERNAL, () ->
            observation.observe(new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(providerCalls).hasValue(1);
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(snapshot.reconciliationGeneration()).isEqualTo(1L);
        assertThat(snapshot.lastErrorCode()).isNull();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void nonObservationProviderFailurePreservesItsCodeWithoutOpeningTx2()
            throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-provider-fence-lost-001");
        AtomicInteger providerCalls = new AtomicInteger();
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_FENCE_LOST);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            ignored -> {});

        assertFailure(ReconciliationFailure.Code.RECONCILIATION_FENCE_LOST, () ->
            observation.observe(new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(providerCalls).hasValue(1);
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(snapshot.reconciliationGeneration()).isEqualTo(1L);
        assertThat(snapshot.lastErrorCode()).isNull();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void staleFullFenceRollsBackTerminalWriteAndReturnsOnlyClosedFenceLost()
            throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-stale-fence-001");
        List<UUID> heartbeats = new ArrayList<>();
        heartbeats.add(intent.intentId());
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                transactions.inTenant(TENANT_ID, tx -> {
                    store.markReconciliationOutcomeUnknown(
                        tx,
                        lease,
                        new ReconciliationResolution.StillUnknown(
                            "COMPETING_RECONCILIATION", null));
                    return null;
                });
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::add);

        assertFailure(ReconciliationFailure.Code.RECONCILIATION_FENCE_LOST, () ->
            observation.observe(new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.OUTCOME_UNKNOWN);
        assertThat(snapshot.lastErrorCode()).isEqualTo("COMPETING_RECONCILIATION");
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void statementTimeoutRollsBackTx1AndMapsToPersistenceUnavailable() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-statement-timeout-001");
        installClaimDelayTrigger(Duration.ofSeconds(3));
        AtomicInteger providerCalls = new AtomicInteger();
        List<UUID> heartbeats = new ArrayList<>();
        FencedReconciliationObservation observation = countingObservation(
            providerCalls, heartbeats);
        try {
            assertThat(captureClaimSqlState(intent)).isEqualTo("57014");
            assertFailure(
                ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE,
                () -> observation.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));
        } finally {
            removeTrigger("task10_claim_delay", "task10_claim_delay");
        }

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.OUTCOME_UNKNOWN);
        assertThat(providerCalls).hasValue(0);
        assertThat(heartbeats).isEmpty();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void statementJustBelowLimitCompletesWithOrderedHeartbeats() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-statement-near-limit-001");
        installClaimDelayTrigger(Duration.ofMillis(1_800));
        AtomicInteger providerCalls = new AtomicInteger();
        HeartbeatTrace heartbeats = new HeartbeatTrace(intent.intentId());
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::complete);
        long startedAtNanos = System.nanoTime();
        try {
            assertThat(observation.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
                .isEqualTo(ReconciliationOutcome.CONVERGED);
        } finally {
            removeTrigger("task10_claim_delay", "task10_claim_delay");
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAtNanos);

        assertThat(elapsed).isBetween(Duration.ofMillis(1_650), Duration.ofSeconds(3));
        assertThat(providerCalls).hasValue(1);
        heartbeats.assertSixOrdered(intent.intentId());
        ExternalIntentState state = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(state).isEqualTo(ExternalIntentState.SUCCEEDED);
        assertThat(count("domain_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void failureAfterTerminalIntentWriteRollsBackTx2() throws Exception {
        assertTerminalWriteRollback(
            "action-intent-write-rollback-001", TerminalWriteStage.INTENT);
    }

    @Test
    void failureAfterDomainEventWriteRollsBackTx2() throws Exception {
        assertTerminalWriteRollback(
            "action-domain-event-rollback-001", TerminalWriteStage.DOMAIN_EVENT);
    }

    @Test
    void failureAfterOutboxWriteRollsBackTx2() throws Exception {
        assertTerminalWriteRollback(
            "action-outbox-rollback-001", TerminalWriteStage.OUTBOX);
    }

    @Test
    void unknownWriteFailureRollsBackTheEntireSecondTransaction() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-unknown-rollback-001");
        installUnknownFailureTrigger();
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> new ReconciliationResolution.StillUnknown(
                "OBSERVATION_INCOMPLETE", null),
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            ignored -> {});
        try {
            assertFailure(ReconciliationFailure.Code.RECONCILIATION_INTERNAL, () ->
                observation.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));
        } finally {
            removeUnknownFailureTrigger();
        }

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void renewalFailureRollsBackClaimSnapshotAndRenewalTransaction() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-renewal-rollback-001");
        installRenewalFailureTrigger();
        AtomicInteger providerCalls = new AtomicInteger();
        List<UUID> heartbeats = new ArrayList<>();
        FencedReconciliationObservation observation = countingObservation(
            providerCalls, heartbeats);
        try {
            assertFailure(ReconciliationFailure.Code.RECONCILIATION_INTERNAL, () ->
                observation.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));
        } finally {
            removeRenewalFailureTrigger();
        }

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.OUTCOME_UNKNOWN);
        assertThat(snapshot.reconciliationGeneration()).isZero();
        assertThat(providerCalls).hasValue(0);
        assertThat(heartbeats).isEmpty();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void connectionAcquisitionTimeoutMapsToPersistenceUnavailableBeforeTx1()
            throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-connection-timeout-001");
        AtomicInteger providerCalls = new AtomicInteger();
        List<UUID> heartbeats = new ArrayList<>();
        FencedReconciliationObservation observation = countingObservation(
            providerCalls, heartbeats);

        try (Connection held = dataSource.getConnection()) {
            assertThat(held.isClosed()).isFalse();
            assertThat(dataSource.getConnectionTimeout()).isEqualTo(2_000L);
            long directStarted = System.nanoTime();
            java.sql.SQLException poolFailure = captureDirectConnectionAcquisitionFailure();
            Duration directElapsed = Duration.ofNanos(System.nanoTime() - directStarted);
            assertThat((Throwable) poolFailure)
                .isExactlyInstanceOf(java.sql.SQLTransientConnectionException.class);
            assertThat(poolFailure.getSQLState()).isNull();
            assertThat(directElapsed).isBetween(
                Duration.ofMillis(1_800), Duration.ofMillis(3_000));
            AtomicInteger connectionAttempts = new AtomicInteger();
            DataSource countingDataSource = countingDataSource(connectionAttempts);
            WorkerTenantTransactions countedTransactions = new WorkerTenantTransactions(
                callerOwnedDsl(countingDataSource));
            long transactionStarted = System.nanoTime();
            RuntimeException rawTransactionFailure = captureTransactionConnectionFailure(
                countedTransactions);
            java.sql.SQLException transactionFailure = sqlException(rawTransactionFailure);
            Duration transactionElapsed = Duration.ofNanos(
                System.nanoTime() - transactionStarted);
            assertThat((Throwable) transactionFailure)
                .isExactlyInstanceOf(java.sql.SQLTransientConnectionException.class);
            assertThat(transactionFailure.getSQLState()).isNull();
            assertThat(connectionAttempts).hasValue(1);
            assertThat(transactionElapsed).isBetween(
                Duration.ofMillis(1_800), Duration.ofMillis(3_000));
            long observationStarted = System.nanoTime();
            assertFailure(
                ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE,
                () -> observation.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));
            Duration observationElapsed = Duration.ofNanos(
                System.nanoTime() - observationStarted);
            assertThat(observationElapsed).isBetween(
                Duration.ofMillis(1_800), Duration.ofMillis(3_000));
        }

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.OUTCOME_UNKNOWN);
        assertThat(snapshot.reconciliationGeneration()).isZero();
        assertThat(providerCalls).hasValue(0);
        assertThat(heartbeats).isEmpty();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void connectionAcquisitionJustBelowLimitCompletesWithOrderedHeartbeats()
            throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-connection-near-limit-001");
        AtomicInteger providerCalls = new AtomicInteger();
        HeartbeatTrace heartbeats = new HeartbeatTrace(intent.intentId());
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::complete);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Connection held = dataSource.getConnection();
        try {
            Future<ReconciliationOutcome> result = executor.submit(() -> observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));
            awaitPoolWaiter();
            waitAtLeast(Duration.ofMillis(1_500));
            held.close();
            held = null;

            assertThat(result.get(10, TimeUnit.SECONDS))
                .isEqualTo(ReconciliationOutcome.CONVERGED);
        } finally {
            if (held != null) {
                held.close();
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(providerCalls).hasValue(1);
        heartbeats.assertSixOrdered(intent.intentId());
        assertThat(heartbeats.intervalAfterEntry())
            .isBetween(Duration.ofMillis(1_350), Duration.ofSeconds(3));
        ExternalIntentState state = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(state).isEqualTo(ExternalIntentState.SUCCEEDED);
        assertThat(count("domain_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void lateProviderReturnPersistsUnknownBeforeExposingClosedTimeout() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-provider-late-return-001");
        List<UUID> heartbeats = new ArrayList<>();
        heartbeats.add(intent.intentId());
        ProviderObservationPort provider = (lease, timeout) -> {
            assertThat(timeout).isEqualTo(Duration.ofSeconds(2));
            waitAtLeast(Duration.ofMillis(2_500));
            return new ReconciliationResolution.Succeeded(digest('b'), null);
        };
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            provider,
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::add);

        assertThatThrownBy(() -> observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isInstanceOfSatisfying(ReconciliationFailure.class, failure -> {
                assertThat(failure.code())
                    .isEqualTo(ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
                assertThat(failure.getCause()).isNull();
                assertThat(failure.getStackTrace()).isEmpty();
            });

        assertThat(heartbeats).containsExactly(
            intent.intentId(), intent.intentId(), intent.intentId(),
            intent.intentId(), intent.intentId(), intent.intentId());
        ExternalIntentState state = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(state).isEqualTo(ExternalIntentState.OUTCOME_UNKNOWN);
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void providerReturnJustBelowLimitCompletesWithOrderedHeartbeats() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-provider-near-limit-001");
        AtomicInteger providerCalls = new AtomicInteger();
        HeartbeatTrace heartbeats = new HeartbeatTrace(intent.intentId());
        ProviderObservationPort provider = (lease, timeout) -> {
            providerCalls.incrementAndGet();
            assertThat(timeout).isEqualTo(Duration.ofSeconds(2));
            assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
            waitAtLeast(Duration.ofMillis(1_500));
            return new ReconciliationResolution.Succeeded(digest('b'), null);
        };
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            provider,
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::complete);

        assertThat(observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isEqualTo(ReconciliationOutcome.CONVERGED);

        assertThat(providerCalls).hasValue(1);
        heartbeats.assertSixOrdered(intent.intentId());
        assertThat(heartbeats.intervalAcrossObservation())
            .isBetween(Duration.ofMillis(1_350), Duration.ofSeconds(3));
        ExternalIntentState state = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(state).isEqualTo(ExternalIntentState.SUCCEEDED);
        assertThat(count("domain_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void transactionsJustBelowLimitCommitWithOrderedHeartbeats() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-transaction-near-limit-001");
        AtomicReference<TransactionPhase> phase =
            new AtomicReference<>(TransactionPhase.TX1);
        AtomicInteger tx1Statements = new AtomicInteger();
        AtomicInteger tx2Statements = new AtomicInteger();
        WorkerTenantTransactions delayedTransactions = delayedTransactions(
            phase,
            tx1Statements,
            Duration.ofMillis(900),
            tx2Statements,
            Duration.ofMillis(1_050));
        AtomicInteger providerCalls = new AtomicInteger();
        HeartbeatTrace heartbeats = new HeartbeatTrace(intent.intentId());
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            delayedTransactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                assertThat(tx1Statements).hasValue(6);
                phase.set(TransactionPhase.TX2);
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::complete);

        assertThat(observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isEqualTo(ReconciliationOutcome.CONVERGED);
        phase.set(TransactionPhase.COMPLETE);

        assertThat(providerCalls).hasValue(1);
        assertThat(tx1Statements).hasValue(6);
        assertThat(tx2Statements).hasValue(5);
        assertThat(heartbeats.intervalAfterEntry())
            .isBetween(Duration.ofSeconds(5), Duration.ofSeconds(6));
        assertThat(heartbeats.intervalAcrossSecondTransaction())
            .isBetween(Duration.ofSeconds(5), Duration.ofSeconds(6));
        heartbeats.assertSixOrdered(intent.intentId());
        ExternalIntentState state = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(state).isEqualTo(ExternalIntentState.SUCCEEDED);
        assertThat(count("domain_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void transactionTimeoutRollsBackTx1AndFailsClosed() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-transaction-timeout-tx1-001");
        AtomicReference<TransactionPhase> phase =
            new AtomicReference<>(TransactionPhase.TX1);
        AtomicInteger tx1Statements = new AtomicInteger();
        AtomicInteger tx2Statements = new AtomicInteger();
        WorkerTenantTransactions delayedTransactions = delayedTransactions(
            phase,
            tx1Statements,
            Duration.ofMillis(1_300),
            tx2Statements,
            Duration.ZERO);
        AtomicInteger providerCalls = new AtomicInteger();
        HeartbeatTrace heartbeats = new HeartbeatTrace(intent.intentId());
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            delayedTransactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::complete);

        RuntimeException rawTransactionFailure = captureTransactionTimeoutFailure(
            delayedTransactions);
        assertThat(sqlStates(rawTransactionFailure))
            .isNotEmpty()
            .containsAnyOf("25P04", "08006")
            .allMatch(state -> state.startsWith("08")
                || state.startsWith("40")
                || state.startsWith("53")
                || "57014".equals(state)
                || "25P04".equals(state)
                || "57P01".equals(state));
        tx1Statements.set(0);

        assertFailure(
            ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE,
            () -> observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));
        phase.set(TransactionPhase.COMPLETE);

        assertThat(tx1Statements).hasValue(5);
        assertThat(tx2Statements).hasValue(0);
        assertThat(providerCalls).hasValue(0);
        heartbeats.assertOnlyActivityEntry(intent.intentId());
        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.OUTCOME_UNKNOWN);
        assertThat(snapshot.reconciliationGeneration()).isZero();
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void transactionTimeoutAfterTx2WritesRollsBackAndFailsClosed() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-transaction-timeout-tx2-001");
        AtomicReference<TransactionPhase> phase =
            new AtomicReference<>(TransactionPhase.TX1);
        AtomicInteger tx1Statements = new AtomicInteger();
        AtomicInteger tx2Statements = new AtomicInteger();
        WorkerTenantTransactions delayedTransactions = delayedTransactions(
            phase,
            tx1Statements,
            Duration.ZERO,
            tx2Statements,
            Duration.ofMillis(1_600));
        AtomicInteger providerCalls = new AtomicInteger();
        HeartbeatTrace heartbeats = new HeartbeatTrace(intent.intentId());
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            delayedTransactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                assertThat(tx1Statements).hasValue(6);
                phase.set(TransactionPhase.TX2);
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::complete);

        assertFailure(
            ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE,
            () -> observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));
        phase.set(TransactionPhase.COMPLETE);

        assertThat(tx1Statements).hasValue(6);
        assertThat(tx2Statements).hasValue(4);
        assertThat(providerCalls).hasValue(1);
        heartbeats.assertBeforeFailedSecondTransaction(intent.intentId());
        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(snapshot.reconciliationGeneration()).isEqualTo(1L);
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void heartbeatBudgetRollsBackTx2BeforeAnOverBudgetCommit() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-heartbeat-tx2-budget-001");
        AtomicReference<TransactionPhase> phase =
            new AtomicReference<>(TransactionPhase.TX1);
        AtomicBoolean delayedAfterWrite = new AtomicBoolean();
        AtomicReference<ReconciliationFailure> observedFailure = new AtomicReference<>();
        ExecuteListener listener = new ExecuteListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void executeEnd(ExecuteContext context) {
                if (phase.get() == TransactionPhase.TX2
                        && context.sql().contains(
                            "complete_external_intent_reconciliation")
                        && delayedAfterWrite.compareAndSet(false, true)) {
                    waitAtLeast(Duration.ofMillis(8_500));
                }
            }
        };

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                ControlPlaneTestRoles.WORKER_LOGIN,
                ControlPlaneTestRoles.WORKER_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE accord_worker");
            statement.execute("SET transaction_timeout = 0");
            statement.execute("SET statement_timeout = 0");
            statement.execute("SET lock_timeout = 0");
            var configuration = DSL.using(connection, SQLDialect.POSTGRES)
                .configuration()
                .derive(new DefaultExecuteListenerProvider(listener));
            WorkerTenantTransactions unboundedTransactions =
                new WorkerTenantTransactions(DSL.using(configuration));
            HeartbeatTrace heartbeats = new HeartbeatTrace(intent.intentId());
            FencedReconciliationObservation observation =
                new FencedReconciliationObservation(
                    unboundedTransactions,
                    store,
                    (lease, timeout) -> {
                        phase.set(TransactionPhase.TX2);
                        return new ReconciliationResolution.Succeeded(digest('b'), null);
                    },
                    new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
                    heartbeats::complete);

            try {
                observation.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, intent.intentId()));
                throw new AssertionError("over-budget transaction unexpectedly returned");
            } catch (ReconciliationFailure failure) {
                observedFailure.set(failure);
            }
            phase.set(TransactionPhase.COMPLETE);
            assertThat(delayedAfterWrite).isTrue();
            heartbeats.assertBeforeFailedSecondTransaction(intent.intentId());
        }

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(snapshot.reconciliationGeneration()).isEqualTo(1L);
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(observedFailure.get().code()).isEqualTo(
            ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
        assertThat(observedFailure.get().getCause()).isNull();
        assertThat(observedFailure.get().getStackTrace()).isEmpty();
    }

    @Test
    void expiredHeartbeatCallbackCannotResetTheCompletedHeartbeatBudget() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-heartbeat-budget-001");
        List<UUID> heartbeats = new ArrayList<>();
        heartbeats.add(intent.intentId());
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger heartbeatCalls = new AtomicInteger();
        ProviderObservationPort provider = (lease, timeout) -> {
            providerCalls.incrementAndGet();
            return new ReconciliationResolution.Succeeded(digest('b'), null);
        };
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            provider,
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeat -> {
                heartbeats.add(heartbeat);
                if (heartbeatCalls.getAndIncrement() == 0) {
                    waitAtLeast(Duration.ofMillis(8_500));
                }
            });

        assertThatThrownBy(() -> observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isInstanceOfSatisfying(ReconciliationFailure.class, failure ->
                assertThat(failure.code())
                    .isEqualTo(ReconciliationFailure.Code.RECONCILIATION_INTERNAL));

        assertThat(heartbeats).containsExactly(intent.intentId(), intent.intentId());
        assertThat(providerCalls).hasValue(0);
        ExternalIntentState state = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(state).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void temporalHeartbeatCancellationIsNeverSwallowedOrRemapped() throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-heartbeat-cancellation-001");
        AtomicInteger providerCalls = new AtomicInteger();
        io.temporal.failure.CanceledFailure cancellation =
            new io.temporal.failure.CanceledFailure("sdk cancellation");
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            ignored -> {
                throw cancellation;
            });

        assertThatThrownBy(() -> observation.observe(
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isSameAs(cancellation);

        assertThat(providerCalls).hasValue(0);
        ExternalIntentState state = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(state).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void workflowRetriesObservationUnavailableThenConvergesThroughProductionFences()
            throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-production-retry-001");
        List<Long> observedGenerations = Collections.synchronizedList(new ArrayList<>());
        List<Duration> observedTimeouts = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean firstObservation = new AtomicBoolean(true);
        ProviderProbe probe = new ProviderProbe((lease, timeout) -> {
            observedGenerations.add(lease.generation());
            observedTimeouts.add(timeout);
            if (firstObservation.compareAndSet(true, false)) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
            }
            return new ReconciliationResolution.Succeeded(digest('b'), null);
        });
        AtomicInteger activityAttempts = new AtomicInteger();
        AtomicInteger latestAttempt = new AtomicInteger();
        String taskQueue = "reconciliation-production-retry-" + UUID.randomUUID();
        FencedReconciliationObservation fenced = new FencedReconciliationObservation(
            transactions,
            store,
            probe,
            new ReconciliationRuntimeProperties("reconciler-retry", CLAIM_LEASE));

        TestWorkflowEnvironment environment = startProductionWorkflowEnvironment(
            taskQueue,
            fenced,
            activityCountingInterceptor(
                activityAttempts, latestAttempt, null, null));
        try {
            ReconciliationWorkflow workflow = newWorkflow(
                environment, taskQueue, "reconciliation-production-retry-");

            assertThat(workflow.reconcile(
                    new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
                .isEqualTo(ReconciliationOutcome.CONVERGED);

            assertThat(activityAttempts).hasValue(2);
            assertThat(latestAttempt).hasValue(2);
            assertThat(probe.observeCalls()).isEqualTo(2);
            assertThat(probe.mutateCalls()).isZero();
            assertThat(observedGenerations).containsExactly(1L, 2L);
            assertThat(observedTimeouts).containsExactly(
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        } finally {
            environment.close();
        }

        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.SUCCEEDED);
        assertThat(snapshot.reconciliationGeneration()).isEqualTo(2L);
        assertThat(count("domain_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void workflowStopsAfterOneAttemptForProductionNonRetryableFailures()
            throws Exception {
        UUID missingIntentId = UUID.randomUUID();
        ExternalIntentRef recorded = record("action-production-nonretryable-001");
        ProviderProbe probe = new ProviderProbe((lease, timeout) ->
            new ReconciliationResolution.Succeeded(digest('b'), null));
        AtomicInteger activityAttempts = new AtomicInteger();
        AtomicInteger latestAttempt = new AtomicInteger();
        String taskQueue = "reconciliation-production-nonretryable-" + UUID.randomUUID();
        FencedReconciliationObservation fenced = new FencedReconciliationObservation(
            transactions,
            store,
            probe,
            new ReconciliationRuntimeProperties("reconciler-nonretryable", CLAIM_LEASE));

        TestWorkflowEnvironment environment = startProductionWorkflowEnvironment(
            taskQueue,
            fenced,
            activityCountingInterceptor(
                activityAttempts, latestAttempt, null, null));
        try {
            ApplicationFailure missing = workflowApplicationFailure(
                environment,
                taskQueue,
                new ReconciliationWorkflowRef(TENANT_ID, missingIntentId));
            assertApplicationFailure(
                missing,
                ReconciliationFailure.Code.RECONCILIATION_MISSING,
                "reconciliation rejected",
                true);
            assertThat(activityAttempts).hasValue(1);
            assertThat(latestAttempt).hasValue(1);

            ApplicationFailure notReconcilable = workflowApplicationFailure(
                environment,
                taskQueue,
                new ReconciliationWorkflowRef(TENANT_ID, recorded.intentId()));
            assertApplicationFailure(
                notReconcilable,
                ReconciliationFailure.Code.RECONCILIATION_NOT_RECONCILABLE,
                "reconciliation rejected",
                true);
            assertThat(activityAttempts).hasValue(2);
            assertThat(latestAttempt).hasValue(1);
            assertThat(probe.observeCalls()).isZero();
            assertThat(probe.mutateCalls()).isZero();
        } finally {
            environment.close();
        }

        ExternalIntentState recordedState = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, recorded.intentId()).orElseThrow().state());
        assertThat(recordedState).isEqualTo(ExternalIntentState.RECORDED);
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void productionActivityFailuresExposeOnlyClosedShapeAndIdentifierHeartbeats()
            throws Exception {
        ExternalIntentRef unavailable = seedOutcomeUnknown(
            "action-production-activity-unavailable-001");
        ExternalIntentRef internal = seedOutcomeUnknown(
            "action-production-activity-internal-001");
        ProviderProbe probe = new ProviderProbe((lease, timeout) -> {
            if (lease.intentId().equals(unavailable.intentId())) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
            }
            return null;
        });
        FencedReconciliationObservation fenced = new FencedReconciliationObservation(
            transactions,
            store,
            probe,
            new ReconciliationRuntimeProperties("reconciler-activity-shape", CLAIM_LEASE));
        List<UUID> heartbeats = new ArrayList<>();
        TestActivityEnvironment environment = TestActivityEnvironment.newInstance();
        try {
            environment.setActivityHeartbeatListener(UUID.class, heartbeats::add);
            environment.registerActivitiesImplementations(
                new ReadOnlyReconciliationActivity(fenced));
            ReconciliationActivities activities = environment.newActivityStub(
                ReconciliationActivities.class);

            ApplicationFailure unavailableFailure = findCause(
                catchThrowable(() -> activities.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, unavailable.intentId()))),
                ApplicationFailure.class);
            assertApplicationFailure(
                unavailableFailure,
                ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE,
                "reconciliation retry required",
                false);
            assertThat(heartbeats).containsExactly(unavailable.intentId());
            heartbeats.clear();

            ApplicationFailure internalFailure = findCause(
                catchThrowable(() -> activities.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, internal.intentId()))),
                ApplicationFailure.class);
            assertApplicationFailure(
                internalFailure,
                ReconciliationFailure.Code.RECONCILIATION_INTERNAL,
                "reconciliation rejected",
                true);
            assertThat(heartbeats).containsExactly(internal.intentId());
            assertThat(probe.observeCalls()).isEqualTo(2);
            assertThat(probe.mutateCalls()).isZero();
        } finally {
            environment.close();
        }

        var unavailableSnapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, unavailable.intentId()).orElseThrow());
        var internalSnapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, internal.intentId()).orElseThrow());
        assertThat(unavailableSnapshot.state()).isEqualTo(ExternalIntentState.OUTCOME_UNKNOWN);
        assertThat(internalSnapshot.state()).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(internalSnapshot.reconciliationGeneration()).isEqualTo(1L);
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    @Timeout(45)
    void lostActivityResponseIsReassignedAfterHeartbeatTimeoutWithoutDuplicateWrites()
            throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-response-loss-001");
        AtomicInteger workerAAttempts = new AtomicInteger();
        AtomicInteger workerBAttempts = new AtomicInteger();
        AtomicInteger workerAAttemptNumber = new AtomicInteger();
        AtomicInteger workerBAttemptNumber = new AtomicInteger();
        ProviderProbe probe = new ProviderProbe((lease, timeout) ->
            new ReconciliationResolution.Succeeded(digest('b'), null));
        CountDownLatch activityReturned = new CountDownLatch(1);
        CountDownLatch releaseLostResponse = new CountDownLatch(1);
        WorkerInterceptor responseLoss = activityCountingInterceptor(
            workerAAttempts, workerAAttemptNumber, activityReturned, releaseLostResponse);
        WorkerInterceptor replacementCounter = activityCountingInterceptor(
            workerBAttempts, workerBAttemptNumber, null, null);
        String taskQueue = "reconciliation-response-loss-" + UUID.randomUUID();

        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setUseTimeskipping(false)
                .build());
        WorkerFactory workerAFactory = null;
        WorkerFactory workerBFactory = null;
        try {
            Worker workflowWorker = environment.newWorker(
                taskQueue,
                WorkerOptions.newBuilder()
                    .setIdentity("reconciliation-workflow-worker")
                    .setDisableEagerExecution(true)
                    .build());
            workflowWorker.registerWorkflowImplementationTypes(
                ReconciliationWorkflowImpl.class);
            workerAFactory = WorkerFactory.newInstance(
                environment.getWorkflowClient(),
                WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(responseLoss)
                    .build());
            Worker workerA = workerAFactory.newWorker(
                taskQueue,
                WorkerOptions.newBuilder()
                    .setIdentity("reconciliation-worker-a")
                    .setDisableEagerExecution(true)
                    .build());
            FencedReconciliationObservation fencedA = new FencedReconciliationObservation(
                transactions,
                store,
                probe,
                new ReconciliationRuntimeProperties("reconciler-a", CLAIM_LEASE));
            workerA.registerActivitiesImplementations(
                new ReadOnlyReconciliationActivity(fencedA));

            workerBFactory = WorkerFactory.newInstance(
                environment.getWorkflowClient(),
                WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(replacementCounter)
                    .build());
            Worker workerB = workerBFactory.newWorker(
                taskQueue,
                WorkerOptions.newBuilder()
                    .setIdentity("reconciliation-worker-b")
                    .setDisableEagerExecution(true)
                    .build());
            FencedReconciliationObservation fencedB = new FencedReconciliationObservation(
                transactions,
                store,
                probe,
                new ReconciliationRuntimeProperties("reconciler-b", CLAIM_LEASE));
            workerB.registerActivitiesImplementations(
                new ReadOnlyReconciliationActivity(fencedB));

            environment.start();
            workerAFactory.start();
            ReconciliationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                ReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("reconciliation-response-loss-" + intent.intentId())
                    .setTaskQueue(taskQueue)
                    .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
                    .build());
            var execution = WorkflowClient.start(
                workflow::reconcile,
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId()));

            assertThat(activityReturned.await(15, TimeUnit.SECONDS)).isTrue();
            long responseLostAtNanos = System.nanoTime();
            workerAFactory.shutdownNow();
            workerBFactory.start();

            ReconciliationOutcome outcome;
            try {
                outcome = WorkflowStub.fromTyped(workflow)
                    .getResult(20, TimeUnit.SECONDS, ReconciliationOutcome.class);
            } catch (java.util.concurrent.TimeoutException timeout) {
                var diagnosticHistory = environment.getWorkflowClient().fetchHistory(
                    execution.getWorkflowId(), execution.getRunId());
                var diagnosticState = transactions.inTenant(TENANT_ID, tx ->
                    store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
                throw new AssertionError(
                    "response-loss workflow did not finish; events="
                        + diagnosticHistory.getEvents().stream()
                            .map(event -> event.getEventType().name())
                            .toList()
                        + ", workerA=" + workerAAttempts.get()
                        + ", workerB=" + workerBAttempts.get()
                        + ", state=" + diagnosticState,
                    timeout);
            }
            assertThat(outcome).isEqualTo(ReconciliationOutcome.CONVERGED);
            assertThat(workerAAttempts).hasValue(1);
            assertThat(workerBAttempts).hasValue(1);
            assertThat(workerAAttemptNumber).hasValue(1);
            assertThat(workerBAttemptNumber.get()).isGreaterThanOrEqualTo(2);
            assertThat(probe.observeCalls()).isEqualTo(1);
            assertThat(probe.mutateCalls()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - responseLostAtNanos))
                .isBetween(Duration.ofSeconds(8), Duration.ofSeconds(25));

            var snapshot = transactions.inTenant(TENANT_ID, tx ->
                store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
            assertThat(snapshot.state()).isEqualTo(ExternalIntentState.SUCCEEDED);
            assertThat(snapshot.reconciliationGeneration()).isEqualTo(1L);
            assertThat(count("domain_event")).isEqualTo(1);
            assertThat(count("outbox_event")).isEqualTo(1);
        } finally {
            releaseLostResponse.countDown();
            if (workerBFactory != null && !workerBFactory.isShutdown()) {
                workerBFactory.shutdownNow();
            }
            if (workerAFactory != null && !workerAFactory.isShutdown()) {
                workerAFactory.shutdownNow();
            }
            environment.close();
        }
    }

    @Test
    @Timeout(90)
    void lostAcquiredAttemptRetriesBusyUntilFenceExpiresThenAcquiresGenerationTwo()
            throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown("action-acquired-loss-001");
        AtomicInteger workerAAttempts = new AtomicInteger();
        AtomicInteger workerBAttempts = new AtomicInteger();
        AtomicInteger workerAAttemptNumber = new AtomicInteger();
        AtomicInteger workerBAttemptNumber = new AtomicInteger();
        AtomicReference<ReconciliationLease> firstLease = new AtomicReference<>();
        AtomicReference<ReconciliationLease> secondLease = new AtomicReference<>();
        AtomicReference<Duration> firstTimeout = new AtomicReference<>();
        AtomicReference<Duration> secondTimeout = new AtomicReference<>();
        CountDownLatch providerAEntered = new CountDownLatch(1);
        CountDownLatch releaseProviderA = new CountDownLatch(1);
        List<String> workerBFailures = Collections.synchronizedList(new ArrayList<>());
        ProviderProbe probe = new ProviderProbe((lease, timeout) -> {
            if (lease.generation() == 1L) {
                firstLease.compareAndSet(null, lease);
                firstTimeout.compareAndSet(null, timeout);
                providerAEntered.countDown();
                awaitUninterruptibly(releaseProviderA);
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            }
            secondLease.compareAndSet(null, lease);
            secondTimeout.compareAndSet(null, timeout);
            return new ReconciliationResolution.Succeeded(digest('c'), null);
        });
        WorkerInterceptor workerACounter = activityCountingInterceptor(
            workerAAttempts, workerAAttemptNumber, null, null);
        WorkerInterceptor workerBRecorder = activityFailureRecordingInterceptor(
            workerBAttempts, workerBAttemptNumber, workerBFailures);
        String taskQueue = "reconciliation-acquired-loss-" + UUID.randomUUID();

        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setUseTimeskipping(false)
                .build());
        WorkerFactory workerAFactory = null;
        WorkerFactory workerBFactory = null;
        try {
            Worker workflowWorker = environment.newWorker(
                taskQueue,
                WorkerOptions.newBuilder()
                    .setIdentity("reconciliation-workflow-worker")
                    .setDisableEagerExecution(true)
                    .build());
            workflowWorker.registerWorkflowImplementationTypes(
                ReconciliationWorkflowImpl.class);

            workerAFactory = WorkerFactory.newInstance(
                environment.getWorkflowClient(),
                WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(workerACounter)
                    .build());
            Worker workerA = workerAFactory.newWorker(
                taskQueue,
                WorkerOptions.newBuilder()
                    .setIdentity("reconciliation-worker-a")
                    .setDisableEagerExecution(true)
                    .build());
            workerA.registerActivitiesImplementations(
                new ReadOnlyReconciliationActivity(new FencedReconciliationObservation(
                    transactions,
                    store,
                    probe,
                    new ReconciliationRuntimeProperties("reconciler-a", CLAIM_LEASE))));

            workerBFactory = WorkerFactory.newInstance(
                environment.getWorkflowClient(),
                WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(workerBRecorder)
                    .build());
            Worker workerB = workerBFactory.newWorker(
                taskQueue,
                WorkerOptions.newBuilder()
                    .setIdentity("reconciliation-worker-b")
                    .setDisableEagerExecution(true)
                    .build());
            workerB.registerActivitiesImplementations(
                new ReadOnlyReconciliationActivity(new FencedReconciliationObservation(
                    transactions,
                    store,
                    probe,
                    new ReconciliationRuntimeProperties("reconciler-b", CLAIM_LEASE))));

            environment.start();
            workerAFactory.start();
            ReconciliationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                ReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("reconciliation-acquired-loss-" + intent.intentId())
                    .setTaskQueue(taskQueue)
                    .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
                    .build());
            WorkflowClient.start(
                workflow::reconcile,
                new ReconciliationWorkflowRef(TENANT_ID, intent.intentId()));

            assertThat(providerAEntered.await(15, TimeUnit.SECONDS)).isTrue();
            long workerALostAtNanos = System.nanoTime();
            workerAFactory.shutdownNow();
            assertThat(workerAFactory.isShutdown()).isTrue();
            workerBFactory.start();

            ReconciliationOutcome outcome = WorkflowStub.fromTyped(workflow)
                .getResult(60, TimeUnit.SECONDS, ReconciliationOutcome.class);

            assertThat(outcome).isEqualTo(ReconciliationOutcome.CONVERGED);
            assertThat(workerAAttempts).hasValue(1);
            assertThat(workerAAttemptNumber).hasValue(1);
            assertThat(workerBAttempts.get()).isGreaterThanOrEqualTo(2);
            assertThat(workerBAttemptNumber.get()).isGreaterThanOrEqualTo(3);
            assertThat(workerBFailures).contains(
                ReconciliationFailure.Code.RECONCILIATION_BUSY.name());
            assertThat(firstLease.get().generation()).isEqualTo(1L);
            assertThat(firstLease.get().owner()).isEqualTo("reconciler-a");
            assertThat(secondLease.get().generation()).isEqualTo(2L);
            assertThat(secondLease.get().owner()).isEqualTo("reconciler-b");
            assertThat(firstTimeout.get()).isEqualTo(Duration.ofSeconds(2));
            assertThat(secondTimeout.get()).isEqualTo(Duration.ofSeconds(2));
            assertThat(probe.observeCalls()).isEqualTo(2);
            assertThat(probe.mutateCalls()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - workerALostAtNanos))
                .isBetween(Duration.ofSeconds(25), Duration.ofSeconds(55));

            var snapshot = transactions.inTenant(TENANT_ID, tx ->
                store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
            assertThat(snapshot.state()).isEqualTo(ExternalIntentState.SUCCEEDED);
            assertThat(snapshot.reconciliationGeneration()).isEqualTo(2L);
            assertThat(count("domain_event")).isEqualTo(1);
            assertThat(count("outbox_event")).isEqualTo(1);
        } finally {
            releaseProviderA.countDown();
            if (workerBFactory != null && !workerBFactory.isShutdown()) {
                workerBFactory.shutdownNow();
            }
            if (workerAFactory != null && !workerAFactory.isShutdown()) {
                workerAFactory.shutdownNow();
            }
            if (workerBFactory != null) {
                workerBFactory.awaitTermination(10, TimeUnit.SECONDS);
                assertThat(workerBFactory.isTerminated()).isTrue();
            }
            if (workerAFactory != null) {
                workerAFactory.awaitTermination(10, TimeUnit.SECONDS);
                assertThat(workerAFactory.isTerminated()).isTrue();
            }
            environment.close();
        }
    }

    private ExternalIntentRef seedOutcomeUnknown(String logicalKey) throws Exception {
        ExternalIntentRef intent = record(logicalKey);
        var permit = assertInstanceOf(ExecutionClaim.Acquired.class,
            transactions.inTenant(TENANT_ID, tx -> store.claimExecution(
                tx, TENANT_ID, intent.intentId(), "execution-worker", CLAIM_LEASE)))
            .permit();
        transactions.inTenant(TENANT_ID, tx -> {
            store.markExecutionOutcomeUnknown(
                tx, permit,
                new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", null));
            return null;
        });
        return intent;
    }

    private ExternalIntentRef record(String logicalKey) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                 postgres.getJdbcUrl(),
                 ControlPlaneTestRoles.API_LOGIN,
                 ControlPlaneTestRoles.API_PASSWORD)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE accord_api");
                statement.execute("SELECT set_config('app.tenant_id', '" + TENANT_ID + "', true)");
            }
            DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
            ExternalIntentRegistration registration = store.record(tx, new ExternalIntentDefinition(
                TENANT_ID,
                UUID.randomUUID(),
                logicalKey,
                "repository",
                "repository-1",
                "gitlab",
                "installation-1",
                "repository-immutable-1",
                "git.branch.create",
                "delivery-work-item",
                "work-item-1",
                1,
                digest('a')));
            connection.commit();
            return assertInstanceOf(
                ExternalIntentRegistration.Created.class, registration).intent();
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private int count(String table) throws Exception {
        if (!Set.of("domain_event", "outbox_event").contains(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private void installClaimDelayTrigger(Duration delay) throws Exception {
        String delaySeconds;
        if (Duration.ofMillis(1_800).equals(delay)) {
            delaySeconds = "1.8";
        } else if (Duration.ofSeconds(3).equals(delay)) {
            delaySeconds = "3.0";
        } else {
            throw new IllegalArgumentException("unsupported test delay");
        }
        executeAdmin("""
            CREATE OR REPLACE FUNCTION public.task10_claim_delay()
            RETURNS trigger LANGUAGE plpgsql AS $body$
            BEGIN
              IF OLD.state = 'OUTCOME_UNKNOWN' AND NEW.state = 'RECONCILING' THEN
                PERFORM pg_catalog.pg_sleep(%s);
              END IF;
              RETURN NEW;
            END
            $body$
            """.formatted(delaySeconds));
        executeAdmin("""
            CREATE TRIGGER task10_claim_delay
            BEFORE UPDATE ON public.external_call_intent
            FOR EACH ROW EXECUTE FUNCTION public.task10_claim_delay()
            """);
    }

    private void removeTrigger(String trigger, String function) throws Exception {
        if (!"task10_claim_delay".equals(trigger)
                || !"task10_claim_delay".equals(function)) {
            throw new IllegalArgumentException("unexpected test trigger");
        }
        executeAdmin("DROP TRIGGER IF EXISTS task10_claim_delay ON public.external_call_intent");
        executeAdmin("DROP FUNCTION IF EXISTS public.task10_claim_delay()");
    }

    private void assertTerminalWriteRollback(String key, TerminalWriteStage stage)
            throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown(key);
        installTerminalWriteFailureTrigger(stage);
        FencedReconciliationObservation observation = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> new ReconciliationResolution.Succeeded(digest('b'), null),
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            ignored -> {});
        boolean writeWasVisible;
        try {
            assertFailure(ReconciliationFailure.Code.RECONCILIATION_INTERNAL, () ->
                observation.observe(
                    new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())));
            writeWasVisible = terminalWriteWasVisible();
        } finally {
            removeTerminalWriteFailureTrigger(stage);
        }

        assertThat(writeWasVisible).isTrue();
        var snapshot = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow());
        assertThat(snapshot.state()).isEqualTo(ExternalIntentState.RECONCILING);
        assertThat(snapshot.reconciliationGeneration()).isEqualTo(1L);
        assertThat(count("domain_event")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    private void installTerminalWriteFailureTrigger(TerminalWriteStage stage)
            throws Exception {
        executeAdmin("CREATE SEQUENCE public.task10_terminal_write_witness");
        String functionBody = switch (stage) {
            case INTENT -> """
              BEGIN
                IF NEW.state IN ('SUCCEEDED', 'CONFIRMED_NO_EFFECT', 'DIVERGED') THEN
                  IF NOT EXISTS (
                       SELECT 1 FROM public.external_call_intent
                       WHERE tenant_id = NEW.tenant_id AND intent_id = NEW.intent_id
                         AND state = NEW.state) THEN
                    RAISE EXCEPTION 'terminal intent write was not visible'
                      USING ERRCODE = 'P0001';
                  END IF;
                  PERFORM pg_catalog.nextval(
                    'public.task10_terminal_write_witness'::pg_catalog.regclass);
                  RAISE EXCEPTION 'task10 terminal intent fault' USING ERRCODE = 'P0001';
                END IF;
                RETURN NEW;
              END
              """;
            case DOMAIN_EVENT -> """
              BEGIN
                IF NOT EXISTS (
                  SELECT 1 FROM public.domain_event
                  WHERE tenant_id = NEW.tenant_id AND event_id = NEW.event_id) THEN
                  RAISE EXCEPTION 'domain event write was not visible'
                    USING ERRCODE = 'P0001';
                END IF;
                PERFORM pg_catalog.nextval(
                  'public.task10_terminal_write_witness'::pg_catalog.regclass);
                RAISE EXCEPTION 'task10 domain event fault' USING ERRCODE = 'P0001';
              END
              """;
            case OUTBOX -> """
              BEGIN
                IF NOT EXISTS (
                  SELECT 1 FROM public.outbox_event
                  WHERE tenant_id = NEW.tenant_id AND event_id = NEW.event_id) THEN
                  RAISE EXCEPTION 'outbox write was not visible'
                    USING ERRCODE = 'P0001';
                END IF;
                PERFORM pg_catalog.nextval(
                  'public.task10_terminal_write_witness'::pg_catalog.regclass);
                RAISE EXCEPTION 'task10 outbox fault' USING ERRCODE = 'P0001';
              END
              """;
        };
        executeAdmin("""
            CREATE OR REPLACE FUNCTION public.task10_fail_terminal_write()
            RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
            SET search_path = pg_catalog, pg_temp AS $body$
            %s
            $body$
            """.formatted(functionBody));
        executeAdmin(switch (stage) {
            case INTENT -> """
                CREATE TRIGGER task10_fail_terminal_write
                AFTER UPDATE ON public.external_call_intent
                FOR EACH ROW EXECUTE FUNCTION public.task10_fail_terminal_write()
                """;
            case DOMAIN_EVENT -> """
                CREATE TRIGGER task10_fail_terminal_write
                AFTER INSERT ON public.domain_event
                FOR EACH ROW EXECUTE FUNCTION public.task10_fail_terminal_write()
                """;
            case OUTBOX -> """
                CREATE TRIGGER task10_fail_terminal_write
                AFTER INSERT ON public.outbox_event
                FOR EACH ROW EXECUTE FUNCTION public.task10_fail_terminal_write()
                """;
        });
    }

    private void removeTerminalWriteFailureTrigger(TerminalWriteStage stage)
            throws Exception {
        String table = switch (stage) {
            case INTENT -> "external_call_intent";
            case DOMAIN_EVENT -> "domain_event";
            case OUTBOX -> "outbox_event";
        };
        executeAdmin("DROP TRIGGER IF EXISTS task10_fail_terminal_write ON public." + table);
        executeAdmin("DROP FUNCTION IF EXISTS public.task10_fail_terminal_write()");
        executeAdmin("DROP SEQUENCE IF EXISTS public.task10_terminal_write_witness");
    }

    private boolean terminalWriteWasVisible() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             var row = statement.executeQuery(
                 "SELECT is_called FROM public.task10_terminal_write_witness")) {
            assertThat(row.next()).isTrue();
            return row.getBoolean(1);
        }
    }

    private void installUnknownFailureTrigger() throws Exception {
        executeAdmin("""
            CREATE OR REPLACE FUNCTION public.task10_fail_unknown()
            RETURNS trigger LANGUAGE plpgsql AS $body$
            BEGIN
              IF OLD.state = 'RECONCILING' AND NEW.state = 'OUTCOME_UNKNOWN' THEN
                RAISE EXCEPTION 'task10 unknown fault' USING ERRCODE = 'P0001';
              END IF;
              RETURN NEW;
            END
            $body$
            """);
        executeAdmin("""
            CREATE TRIGGER task10_fail_unknown
            AFTER UPDATE ON public.external_call_intent
            FOR EACH ROW EXECUTE FUNCTION public.task10_fail_unknown()
            """);
    }

    private void removeUnknownFailureTrigger() throws Exception {
        executeAdmin("DROP TRIGGER IF EXISTS task10_fail_unknown ON public.external_call_intent");
        executeAdmin("DROP FUNCTION IF EXISTS public.task10_fail_unknown()");
    }

    private void installRenewalFailureTrigger() throws Exception {
        executeAdmin("""
            CREATE OR REPLACE FUNCTION public.task10_fail_renewal()
            RETURNS trigger LANGUAGE plpgsql AS $body$
            BEGIN
              IF OLD.state = 'RECONCILING' AND NEW.state = 'RECONCILING'
                 AND NEW.reconciliation_lease_until > OLD.reconciliation_lease_until THEN
                RAISE EXCEPTION 'task10 renewal fault' USING ERRCODE = 'P0001';
              END IF;
              RETURN NEW;
            END
            $body$
            """);
        executeAdmin("""
            CREATE TRIGGER task10_fail_renewal
            AFTER UPDATE ON public.external_call_intent
            FOR EACH ROW EXECUTE FUNCTION public.task10_fail_renewal()
            """);
    }

    private void removeRenewalFailureTrigger() throws Exception {
        executeAdmin("DROP TRIGGER IF EXISTS task10_fail_renewal ON public.external_call_intent");
        executeAdmin("DROP FUNCTION IF EXISTS public.task10_fail_renewal()");
    }

    private void executeAdmin(String sql) throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String captureClaimSqlState(ExternalIntentRef intent) {
        try {
            transactions.inTenant(TENANT_ID, tx -> store.claimReconciliation(
                tx,
                TENANT_ID,
                intent.intentId(),
                "diagnostic-reconciler",
                CLAIM_LEASE));
        } catch (RuntimeException failure) {
            Throwable cursor = failure;
            while (cursor != null) {
                if (cursor instanceof java.sql.SQLException sql) {
                    return sql.getSQLState();
                }
                cursor = cursor.getCause();
            }
            return null;
        }
        throw new AssertionError("claim did not reach the statement timeout");
    }

    private java.sql.SQLException captureDirectConnectionAcquisitionFailure() {
        Connection acquired = null;
        try {
            acquired = dataSource.getConnection();
            throw new AssertionError("pool acquisition unexpectedly succeeded");
        } catch (java.sql.SQLException failure) {
            return failure;
        } finally {
            if (acquired != null) {
                try {
                    acquired.close();
                } catch (java.sql.SQLException ignored) {
                    // The assertion above already records the unexpected acquisition.
                }
            }
        }
    }

    private void awaitPoolWaiter() {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection() == 1) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        }
        throw new AssertionError("reconciliation did not wait for the held connection");
    }

    private DataSource countingDataSource(AtomicInteger attempts) {
        return (DataSource) Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, arguments) -> {
                if ("getConnection".equals(method.getName())) {
                    attempts.incrementAndGet();
                }
                try {
                    return method.invoke(dataSource, arguments);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    private static DSLContext callerOwnedDsl(DataSource callerDataSource) {
        TransactionAwareDataSourceProxy connections =
            new TransactionAwareDataSourceProxy(callerDataSource);
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(SQLDialect.POSTGRES);
        configuration.set(new DataSourceConnectionProvider(connections));
        configuration.set(new SpringTransactionProvider(
            new DataSourceTransactionManager(callerDataSource)));
        return DSL.using(configuration);
    }

    private static TestWorkflowEnvironment startProductionWorkflowEnvironment(
            String taskQueue,
            FencedReconciliationObservation observation,
            WorkerInterceptor interceptor) {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setUseTimeskipping(false)
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(interceptor)
                    .build())
                .build());
        Worker worker = environment.newWorker(
            taskQueue,
            WorkerOptions.newBuilder()
                .setIdentity("production-reconciliation-worker")
                .setDisableEagerExecution(true)
                .build());
        worker.registerWorkflowImplementationTypes(ReconciliationWorkflowImpl.class);
        worker.registerActivitiesImplementations(
            new ReadOnlyReconciliationActivity(observation));
        environment.start();
        return environment;
    }

    private static ReconciliationWorkflow newWorkflow(
            TestWorkflowEnvironment environment,
            String taskQueue,
            String workflowIdPrefix) {
        return environment.getWorkflowClient().newWorkflowStub(
            ReconciliationWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowIdPrefix + UUID.randomUUID())
                .setTaskQueue(taskQueue)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
                .build());
    }

    private static ApplicationFailure workflowApplicationFailure(
            TestWorkflowEnvironment environment,
            String taskQueue,
            ReconciliationWorkflowRef ref) {
        ReconciliationWorkflow workflow = newWorkflow(
            environment, taskQueue, "reconciliation-production-rejected-");
        return findCause(
            catchThrowable(() -> workflow.reconcile(ref)),
            ApplicationFailure.class);
    }

    private static void assertApplicationFailure(
            ApplicationFailure failure,
            ReconciliationFailure.Code code,
            String message,
            boolean nonRetryable) {
        assertThat(failure.getOriginalMessage()).isEqualTo(message);
        assertThat(failure.getType()).isEqualTo(code.name());
        assertThat(failure.isNonRetryable()).isEqualTo(nonRetryable);
        assertThat(failure.getDetails().getSize()).isZero();
        assertThat(failure.getCause()).isNull();
    }

    private static <T extends Throwable> T findCause(
            Throwable failure, Class<T> type) {
        Throwable cursor = failure;
        while (cursor != null && !type.isInstance(cursor)) {
            cursor = cursor.getCause();
        }
        assertThat(cursor).isInstanceOf(type);
        return type.cast(cursor);
    }

    private static WorkerInterceptor activityCountingInterceptor(
            AtomicInteger attempts,
            AtomicInteger attemptNumber,
            CountDownLatch returned,
            CountDownLatch release) {
        return new WorkerInterceptorBase() {
            @Override
            public ActivityInboundCallsInterceptor interceptActivity(
                    ActivityInboundCallsInterceptor next) {
                return new ActivityInboundCallsInterceptorBase(next) {
                    @Override
                    public void init(ActivityExecutionContext context) {
                        super.init(context);
                        attemptNumber.set(context.getInfo().getAttempt());
                    }

                    @Override
                    public ActivityOutput execute(ActivityInput input) {
                        attempts.incrementAndGet();
                        ActivityOutput output = super.execute(input);
                        if (returned != null) {
                            returned.countDown();
                            awaitUninterruptibly(release);
                        }
                        return output;
                    }
                };
            }
        };
    }

    private static WorkerInterceptor activityFailureRecordingInterceptor(
            AtomicInteger attempts,
            AtomicInteger attemptNumber,
            List<String> failureTypes) {
        return new WorkerInterceptorBase() {
            @Override
            public ActivityInboundCallsInterceptor interceptActivity(
                    ActivityInboundCallsInterceptor next) {
                return new ActivityInboundCallsInterceptorBase(next) {
                    @Override
                    public void init(ActivityExecutionContext context) {
                        super.init(context);
                        attemptNumber.set(context.getInfo().getAttempt());
                    }

                    @Override
                    public ActivityOutput execute(ActivityInput input) {
                        attempts.incrementAndGet();
                        try {
                            return super.execute(input);
                        } catch (ApplicationFailure failure) {
                            failureTypes.add(failure.getType());
                            throw failure;
                        }
                    }
                };
            }
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitAtLeast(Duration duration) {
        long deadlineNanos = System.nanoTime() + duration.toNanos();
        boolean interrupted = false;
        while (deadlineNanos - System.nanoTime() > 0) {
            LockSupport.parkNanos(deadlineNanos - System.nanoTime());
            interrupted |= Thread.interrupted();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private WorkerTenantTransactions delayedTransactions(
            AtomicReference<TransactionPhase> phase,
            AtomicInteger tx1Statements,
            Duration tx1Delay,
            AtomicInteger tx2Statements,
            Duration tx2Delay) {
        ExecuteListener listener = new ExecuteListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void executeEnd(ExecuteContext context) {
                if (phase.get() == TransactionPhase.TX1) {
                    tx1Statements.incrementAndGet();
                    waitAtLeast(tx1Delay);
                } else if (phase.get() == TransactionPhase.TX2) {
                    tx2Statements.incrementAndGet();
                    waitAtLeast(tx2Delay);
                }
            }
        };
        var configuration = callerOwnedDsl(dataSource)
            .configuration()
            .derive(new DefaultExecuteListenerProvider(listener));
        return new WorkerTenantTransactions(DSL.using(configuration));
    }

    private RuntimeException captureTransactionConnectionFailure(
            WorkerTenantTransactions targetTransactions) {
        try {
            targetTransactions.inTenant(TENANT_ID, tx -> null);
        } catch (RuntimeException failure) {
            return failure;
        }
        throw new AssertionError("transaction connection acquisition unexpectedly succeeded");
    }

    private RuntimeException captureTransactionTimeoutFailure(
            WorkerTenantTransactions targetTransactions) {
        try {
            targetTransactions.inTenant(TENANT_ID, tx -> {
                tx.fetch("SELECT 1");
                tx.fetch("SELECT 1");
                tx.fetch("SELECT 1");
                return null;
            });
        } catch (RuntimeException failure) {
            return failure;
        }
        throw new AssertionError("transaction timeout unexpectedly committed");
    }

    private static java.sql.SQLException sqlException(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof java.sql.SQLException sql) {
                return sql;
            }
            cursor = cursor.getCause();
        }
        throw new AssertionError("connection failure had no SQLException", failure);
    }

    private static Set<String> sqlStates(Throwable failure) {
        Set<String> states = new LinkedHashSet<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof java.sql.SQLException sql
                    && sql.getSQLState() != null) {
                states.add(sql.getSQLState());
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            pending.addAll(Arrays.asList(current.getSuppressed()));
        }
        return states;
    }

    private static void assertPrivateDatabaseFailure(
            RuntimeException rawFailure,
            ReconciliationFailure.Code expected) throws Exception {
        Method database = FencedReconciliationObservation.class.getDeclaredMethod(
            "database", Supplier.class);
        database.setAccessible(true);
        Supplier<Object> operation = () -> {
            throw rawFailure;
        };
        try {
            database.invoke(null, operation);
            throw new AssertionError("database failure unexpectedly returned");
        } catch (InvocationTargetException invocation) {
            ReconciliationFailure failure = assertInstanceOf(
                ReconciliationFailure.class, invocation.getCause());
            assertThat(failure.code()).isEqualTo(expected);
            assertThat(failure.getMessage()).isEqualTo(expected.name());
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getStackTrace()).isEmpty();
        }
    }

    private void assertPersistedEvent(
            ExternalIntentRef intent, ReconciliationOutcome outcome) throws Exception {
        String expectedPayload = "{\"intent_id\":\"" + intent.intentId()
            + "\",\"outcome\":\"" + outcome.name() + "\"}";
        Method canonicalPayload = FencedReconciliationObservation.class.getDeclaredMethod(
            "canonicalPayload", UUID.class, ReconciliationOutcome.class);
        canonicalPayload.setAccessible(true);
        assertThat((String) canonicalPayload.invoke(null, intent.intentId(), outcome))
            .isEqualTo(expectedPayload);

        try (Connection connection = adminConnection()) {
            var row = DSL.using(connection, SQLDialect.POSTGRES).fetchOne("""
                SELECT e.tenant_id,e.event_id,e.scope_type,e.scope_id,
                       e.aggregate_type,e.aggregate_id,e.sequence,e.event_type,
                       e.schema_version,e.causation_id,e.correlation_id,e.actor_id,
                       e.occurred_at IS NOT NULL AS has_occurred_at,
                       e.payload = CAST(? AS jsonb) AS payload_matches,
                       (SELECT pg_catalog.count(*)
                          FROM pg_catalog.jsonb_object_keys(e.payload)) AS payload_fields,
                       o.destination,o.payload_schema,o.payload = e.payload AS payload_equal
                FROM public.domain_event e
                JOIN public.outbox_event o
                  ON o.tenant_id=e.tenant_id AND o.event_id=e.event_id
                WHERE e.tenant_id=? AND e.aggregate_id=?
                """, expectedPayload, TENANT_ID, intent.intentId());
            assertThat(row).isNotNull();
            UUID eventId = row.get("event_id", UUID.class);
            assertThat(row.get("tenant_id", UUID.class)).isEqualTo(TENANT_ID);
            assertThat(row.get("scope_type", String.class)).isEqualTo("repository");
            assertThat(row.get("scope_id", String.class)).isEqualTo("repository-1");
            assertThat(row.get("aggregate_type", String.class)).isEqualTo("external_intent");
            assertThat(row.get("aggregate_id", UUID.class)).isEqualTo(intent.intentId());
            assertThat(row.get("sequence", Long.class)).isEqualTo(1L);
            assertThat(row.get("event_type", String.class))
                .isEqualTo("external_intent.completed");
            assertThat(row.get("schema_version", String.class)).isEqualTo("1.0.0");
            assertThat(row.get("causation_id", UUID.class)).isEqualTo(eventId);
            assertThat(row.get("correlation_id", UUID.class)).isEqualTo(intent.rootIntentId());
            assertThat(row.get("actor_id", String.class)).isEqualTo("reconciler-one");
            assertThat(row.get("has_occurred_at", Boolean.class)).isTrue();
            assertThat(row.get("payload_matches", Boolean.class)).isTrue();
            assertThat(row.get("payload_fields", Long.class)).isEqualTo(2L);
            assertThat(row.get("destination", String.class)).isEqualTo("external-intents");
            assertThat(row.get("payload_schema", String.class))
                .isEqualTo("external-intent.event/1.0");
            assertThat(row.get("payload_equal", Boolean.class)).isTrue();
        }
    }

    private FencedReconciliationObservation countingObservation(
            AtomicInteger providerCalls, List<UUID> heartbeats) {
        return new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                providerCalls.incrementAndGet();
                return new ReconciliationResolution.Succeeded(digest('b'), null);
            },
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeats::add);
    }

    private FencedReconciliationObservation observationWithTelemetry(
            ProviderObservationPort provider,
            java.util.function.Consumer<UUID> heartbeat,
            Runnable terminalSuccessTelemetry) {
        return new FencedReconciliationObservation(
            transactions,
            store,
            provider,
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            heartbeat,
            terminalSuccessTelemetry);
    }

    private void assertTerminalReplay(
            String key,
            ReconciliationResolution.Terminal resolution,
            ReconciliationOutcome outcome,
            ExternalIntentState state) throws Exception {
        ExternalIntentRef intent = seedOutcomeUnknown(key);
        FencedReconciliationObservation first = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> resolution,
            new ReconciliationRuntimeProperties("reconciler-one", CLAIM_LEASE),
            ignored -> {});
        assertThat(first.observe(new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isEqualTo(outcome);
        assertPersistedEvent(intent, outcome);

        int eventsBeforeReplay = count("domain_event");
        int outboxBeforeReplay = count("outbox_event");
        AtomicInteger replayProviderCalls = new AtomicInteger();
        List<UUID> replayHeartbeats = new ArrayList<>();
        FencedReconciliationObservation replay = new FencedReconciliationObservation(
            transactions,
            store,
            (lease, timeout) -> {
                replayProviderCalls.incrementAndGet();
                return new ReconciliationResolution.StillUnknown(
                    "SHOULD_NOT_BE_CALLED", null);
            },
            new ReconciliationRuntimeProperties("reconciler-two", CLAIM_LEASE),
            replayHeartbeats::add);

        assertThat(replay.observe(new ReconciliationWorkflowRef(TENANT_ID, intent.intentId())))
            .isEqualTo(outcome);
        assertThat(replayProviderCalls).hasValue(0);
        assertThat(replayHeartbeats).isEmpty();
        ExternalIntentState replayedState = transactions.inTenant(TENANT_ID, tx ->
            store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state());
        assertThat(replayedState).isEqualTo(state);
        assertThat(count("domain_event")).isEqualTo(eventsBeforeReplay);
        assertThat(count("outbox_event")).isEqualTo(outboxBeforeReplay);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class HeartbeatTrace {
        private final List<UUID> details = new ArrayList<>();
        private final List<Long> completedAtNanos = new ArrayList<>();

        private HeartbeatTrace(UUID activityEntryDetail) {
            complete(activityEntryDetail);
        }

        private void complete(UUID detail) {
            details.add(detail);
            completedAtNanos.add(System.nanoTime());
        }

        private Duration intervalAfterEntry() {
            return Duration.ofNanos(completedAtNanos.get(1) - completedAtNanos.get(0));
        }

        private Duration intervalAcrossObservation() {
            return Duration.ofNanos(completedAtNanos.get(3) - completedAtNanos.get(2));
        }

        private Duration intervalAcrossSecondTransaction() {
            return Duration.ofNanos(completedAtNanos.get(5) - completedAtNanos.get(4));
        }

        private void assertSixOrdered(UUID intentId) {
            assertThat(details).containsExactly(
                intentId, intentId, intentId, intentId, intentId, intentId);
            for (int index = 1; index < completedAtNanos.size(); index++) {
                Duration interval = Duration.ofNanos(
                    completedAtNanos.get(index) - completedAtNanos.get(index - 1));
                assertThat(interval).isLessThan(Duration.ofSeconds(10));
            }
        }

        private void assertOnlyActivityEntry(UUID intentId) {
            assertThat(details).containsExactly(intentId);
        }

        private void assertBeforeFailedSecondTransaction(UUID intentId) {
            assertThat(details).containsExactly(
                intentId, intentId, intentId, intentId, intentId);
            for (int index = 1; index < completedAtNanos.size(); index++) {
                Duration interval = Duration.ofNanos(
                    completedAtNanos.get(index) - completedAtNanos.get(index - 1));
                assertThat(interval).isLessThan(Duration.ofSeconds(10));
            }
        }
    }

    private enum TransactionPhase {
        TX1,
        TX2,
        COMPLETE
    }

    private enum TerminalWriteStage {
        INTENT,
        DOMAIN_EVENT,
        OUTBOX
    }

    private static final class ProviderProbe
            implements ProviderObservationPort {
        private final ProviderObservationPort observation;
        private final AtomicInteger observeCalls = new AtomicInteger();
        private final AtomicInteger mutateCalls = new AtomicInteger();

        private ProviderProbe(ProviderObservationPort observation) {
            this.observation = java.util.Objects.requireNonNull(
                observation, "observation");
        }

        @Override
        public ReconciliationResolution observe(
                ReconciliationLease lease, Duration timeout) {
            observeCalls.incrementAndGet();
            return observation.observe(lease, timeout);
        }

        public void mutate() {
            mutateCalls.incrementAndGet();
        }

        private int observeCalls() {
            return observeCalls.get();
        }

        private int mutateCalls() {
            return mutateCalls.get();
        }
    }

    private static void assertFailure(
            ReconciliationFailure.Code expected, Runnable operation) {
        assertThatThrownBy(operation::run)
            .isInstanceOfSatisfying(ReconciliationFailure.class, failure -> {
                assertThat(failure.code()).isEqualTo(expected);
                assertThat(failure.getCause()).isNull();
                assertThat(failure.getStackTrace()).isEmpty();
            });
    }
}
