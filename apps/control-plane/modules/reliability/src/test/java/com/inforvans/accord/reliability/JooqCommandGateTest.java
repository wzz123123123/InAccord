package com.inforvans.accord.reliability;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inforvans.accord.database.ControlPlaneTestRoles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class JooqCommandGateTest {
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302")
        .asCompatibleSubstituteFor("postgres");
    private static final UUID TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AGGREGATE_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Duration LEASE = Duration.ofSeconds(5);

    private PostgreSQLContainer<?> postgres;
    private JooqCommandGate gate;

    @BeforeAll
    void startPostgresAndMigrate() {
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
        gate = new JooqCommandGate();
    }

    @AfterAll
    void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clearRuntimeRows() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                "TRUNCATE TABLE contract_validation, aggregate_head, idempotency_result");
        }
    }

    @Test
    void sameKeyInitialRaceHasOneGenerationOneWinnerAndOneInProgress() throws Exception {
        CommandKey key = key("requirements.create", "idem-000000000001");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome<Claim>> first = racedApi(
                executor, ready, start, tx -> gate.claim(tx, key, digest('a'), "api-1", LEASE));
            Future<Outcome<Claim>> second = racedApi(
                executor, ready, start, tx -> gate.claim(tx, key, digest('a'), "api-2", LEASE));
            assertTrue(ready.await(5, SECONDS));
            start.countDown();

            List<Outcome<Claim>> outcomes = List.of(
                first.get(10, SECONDS), second.get(10, SECONDS));
            assertEquals(0, outcomes.stream().filter(Outcome::failed).count());
            List<Claim.Acquired> acquired = outcomes.stream()
                .map(Outcome::value)
                .filter(Claim.Acquired.class::isInstance)
                .map(Claim.Acquired.class::cast)
                .toList();
            assertEquals(1, acquired.size());
            assertEquals(1L, acquired.getFirst().lease().generation());
            assertEquals(
                1,
                outcomes.stream().map(Outcome::value)
                    .filter(Claim.InProgress.class::isInstance).count());
            assertEquals(1, idempotencyRowCount(key));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void changedFingerprintConflictsWithoutChangingPersistedDigest() throws Exception {
        CommandKey key = key("requirements.create", "idem-000000000002");
        assertInstanceOf(
            Claim.Acquired.class,
            inApiTenant(tx -> gate.claim(tx, key, digest('b'), "api-1", LEASE)));

        Claim conflict = inApiTenant(
            tx -> gate.claim(tx, key, digest('c'), "api-2", LEASE));

        assertInstanceOf(Claim.RequestConflict.class, conflict);
        assertEquals(digest('b'), persistedFingerprint(key));
    }

    @Test
    void expiredTakeoverRaceHasOneNewGenerationAndToken() throws Exception {
        CommandKey key = key("requirements.update", "idem-000000000003");
        ClaimLease original = assertInstanceOf(
            Claim.Acquired.class,
            inApiTenant(tx -> gate.claim(
                tx, key, digest('d'), "api-original", Duration.ofMillis(100))))
            .lease();
        serverSleep(Duration.ofMillis(250));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome<Claim>> first = racedApi(
                executor, ready, start,
                tx -> gate.claim(tx, key, digest('d'), "api-takeover-1", LEASE));
            Future<Outcome<Claim>> second = racedApi(
                executor, ready, start,
                tx -> gate.claim(tx, key, digest('d'), "api-takeover-2", LEASE));
            assertTrue(ready.await(5, SECONDS));
            start.countDown();
            List<Outcome<Claim>> outcomes = List.of(
                first.get(10, SECONDS), second.get(10, SECONDS));

            assertEquals(0, outcomes.stream().filter(Outcome::failed).count());
            ClaimLease takeover = outcomes.stream()
                .map(Outcome::value)
                .filter(Claim.Acquired.class::isInstance)
                .map(Claim.Acquired.class::cast)
                .map(Claim.Acquired::lease)
                .findFirst()
                .orElseThrow();
            assertEquals(2L, takeover.generation());
            assertNotEquals(original.token(), takeover.token());
            assertEquals(
                1,
                outcomes.stream().map(Outcome::value)
                    .filter(Claim.InProgress.class::isInstance).count());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void waiterBlockedBeforeExpiryUsesDatabaseTimeAfterTakingRowLock() throws Exception {
        CommandKey key = key("requirements.update", "idem-000000000004");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch conflictInsertFinished = new CountDownLatch(1);
        CountDownLatch releaseWaiter = new CountDownLatch(1);
        AtomicBoolean insertIntercepted = new AtomicBoolean();
        try (Connection waiterConnection = openApiConnection();
             Connection lockConnection = openApiConnection();
             Connection observerConnection = adminConnection()) {
            try {
                ClaimLease original = assertInstanceOf(
                    Claim.Acquired.class,
                    inApiTenant(tx -> gate.claim(
                        tx, key, digest('e'), "api-original", LEASE)))
                    .lease();
                int waiterBackendPid = backendPid(waiterConnection);
                waiterConnection.setAutoCommit(false);
                setTenantContext(waiterConnection);
                ExecuteListener listener = new ExecuteListener() {
                    @Override
                    public void end(ExecuteContext context) {
                        String sql = context.sql();
                        String normalized = sql == null
                            ? ""
                            : sql.toLowerCase(java.util.Locale.ROOT);
                        if (normalized.contains("insert into idempotency_result")
                                && insertIntercepted.compareAndSet(false, true)) {
                            conflictInsertFinished.countDown();
                            try {
                                if (!releaseWaiter.await(5, SECONDS)) {
                                    throw new IllegalStateException("waiter was not released");
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(
                                    "interrupted awaiting waiter release", error);
                            }
                        }
                    }
                };
                DefaultConfiguration configuration = new DefaultConfiguration();
                configuration.set(waiterConnection);
                configuration.set(SQLDialect.POSTGRES);
                configuration.set(new DefaultExecuteListenerProvider(listener));
                DSLContext intercepting = DSL.using(configuration);

                Future<Outcome<Claim>> waiter = executor.submit(() -> capture(() -> {
                    try {
                        Claim result = gate.claim(
                            intercepting, key, digest('e'), "api-waiter", LEASE);
                        waiterConnection.commit();
                        return result;
                    } catch (Throwable error) {
                        rollbackWithSuppressed(waiterConnection, error);
                        throwFailure(error);
                        return null;
                    }
                }));
                assertTrue(conflictInsertFinished.await(5, SECONDS));

                lockConnection.setAutoCommit(false);
                setTenantContext(lockConnection);
                try (PreparedStatement lock = lockConnection.prepareStatement("""
                        SELECT 1 FROM idempotency_result
                        WHERE tenant_id=? AND actor_id=? AND route_key=? AND idempotency_key=?
                        FOR UPDATE
                        """)) {
                    bindKey(lock, key);
                    try (ResultSet rows = lock.executeQuery()) {
                        assertTrue(rows.next());
                    }
                }

                releaseWaiter.countDown();
                LockWaitObservation lockWait = awaitForUpdateLockWait(
                    observerConnection, waiterBackendPid, original.leaseUntil());
                assertTrue(lockWait.databaseTime().isBefore(original.leaseUntil()));
                OffsetDateTime releaseTime = awaitDatabaseTime(
                    lockConnection, original.leaseUntil());
                assertFalse(releaseTime.isBefore(original.leaseUntil()));
                lockConnection.commit();

                Outcome<Claim> outcome = waiter.get(10, SECONDS);
                assertNull(outcome.error());
                ClaimLease takeover = assertInstanceOf(
                    Claim.Acquired.class, outcome.value()).lease();
                assertEquals(2L, takeover.generation());
                assertNotEquals(original.token(), takeover.token());
            } finally {
                releaseWaiter.countDown();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameFenceRenewRaceAllowsExactlyOneExtension() throws Exception {
        CommandKey key = key("requirements.update", "idem-000000000005");
        ClaimLease lease = acquire(key, digest('f'), "worker-1", LEASE);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome<ClaimLease>> first = racedApi(
                executor, ready, start,
                tx -> gate.renew(tx, key, lease, Duration.ofSeconds(2)));
            Future<Outcome<ClaimLease>> second = racedApi(
                executor, ready, start,
                tx -> gate.renew(tx, key, lease, Duration.ofSeconds(2)));
            assertTrue(ready.await(5, SECONDS));
            start.countDown();
            List<Outcome<ClaimLease>> outcomes = List.of(
                first.get(10, SECONDS), second.get(10, SECONDS));

            assertEquals(1, outcomes.stream().filter(outcome -> !outcome.failed()).count());
            assertEquals(1, outcomes.stream()
                .map(Outcome::error)
                .filter(IllegalStateException.class::isInstance)
                .count());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void renewalReturnsExactStoredDeadlineExtendsPriorAndRejectsEveryTamperedFence()
            throws Exception {
        CommandKey key = key("requirements.update", "idem-000000000006");
        ClaimLease lease = acquire(key, digest('0'), "worker-1", LEASE);

        ClaimLease renewed = inApiTenant(
            tx -> gate.renew(tx, key, lease, Duration.ofSeconds(2)));

        assertEquals(lease.leaseUntil().plusSeconds(2), renewed.leaseUntil());
        assertEquals(renewed.leaseUntil(), persistedLeaseUntil(key));

        List<ClaimLease> tampered = new ArrayList<>();
        tampered.add(new ClaimLease(
            "worker-other", renewed.generation(), renewed.token(), renewed.leaseUntil()));
        tampered.add(new ClaimLease(
            renewed.owner(), renewed.generation() + 1, renewed.token(), renewed.leaseUntil()));
        tampered.add(new ClaimLease(
            renewed.owner(), renewed.generation(), UUID.randomUUID(), renewed.leaseUntil()));
        tampered.add(new ClaimLease(
            renewed.owner(), renewed.generation(), renewed.token(),
            renewed.leaseUntil().plusNanos(1_000)));
        for (ClaimLease invalid : tampered) {
            assertThrows(
                IllegalStateException.class,
                () -> inApiTenant(tx -> gate.renew(
                    tx, key, invalid, Duration.ofSeconds(1))));
        }
    }

    @Test
    void expectedZeroCreationRaceStoresOneAndReportsActualOneToLoser() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<OffsetDateTime> firstTransactionStarted = new AtomicReference<>();
        AtomicReference<OffsetDateTime> secondTransactionStarted = new AtomicReference<>();
        try {
            Future<Outcome<Long>> first = racedApi(
                executor, ready, start,
                firstTransactionStarted::set,
                tx -> gate.advance(
                    tx, TENANT_ID, "requirement", AGGREGATE_ID, new ExpectedVersion(0)));
            Future<Outcome<Long>> second = racedApi(
                executor, ready, start,
                secondTransactionStarted::set,
                tx -> gate.advance(
                    tx, TENANT_ID, "requirement", AGGREGATE_ID, new ExpectedVersion(0)));
            assertTrue(ready.await(5, SECONDS));
            serverSleep(Duration.ofMillis(250));
            start.countDown();
            List<Outcome<Long>> outcomes = List.of(
                first.get(10, SECONDS), second.get(10, SECONDS));

            assertEquals(1, outcomes.stream()
                .filter(outcome -> Long.valueOf(1L).equals(outcome.value())).count());
            VersionConflict conflict = assertInstanceOf(
                VersionConflict.class,
                outcomes.stream().map(Outcome::error).filter(VersionConflict.class::isInstance)
                    .findFirst().orElseThrow());
            assertEquals(0L, conflict.expected());
            assertEquals(1L, conflict.actual());
            assertEquals(1L, aggregateVersion("requirement", AGGREGATE_ID));
            OffsetDateTime latestTransactionStart = firstTransactionStarted.get().isAfter(
                secondTransactionStarted.get())
                ? firstTransactionStarted.get()
                : secondTransactionStarted.get();
            assertTrue(aggregateUpdatedAt("requirement", AGGREGATE_ID)
                .isAfter(latestTransactionStart));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void expectedOneUpdateRaceStoresTwoAndReportsActualTwoToLoser() throws Exception {
        inApiTenant(tx -> gate.advance(
            tx, TENANT_ID, "requirement", AGGREGATE_ID, new ExpectedVersion(0)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome<Long>> first = racedApi(
                executor, ready, start,
                tx -> gate.advance(
                    tx, TENANT_ID, "requirement", AGGREGATE_ID, new ExpectedVersion(1)));
            Future<Outcome<Long>> second = racedApi(
                executor, ready, start,
                tx -> gate.advance(
                    tx, TENANT_ID, "requirement", AGGREGATE_ID, new ExpectedVersion(1)));
            assertTrue(ready.await(5, SECONDS));
            start.countDown();
            List<Outcome<Long>> outcomes = List.of(
                first.get(10, SECONDS), second.get(10, SECONDS));

            assertEquals(1, outcomes.stream()
                .filter(outcome -> Long.valueOf(2L).equals(outcome.value())).count());
            VersionConflict conflict = assertInstanceOf(
                VersionConflict.class,
                outcomes.stream().map(Outcome::error).filter(VersionConflict.class::isInstance)
                    .findFirst().orElseThrow());
            assertEquals(1L, conflict.expected());
            assertEquals(2L, conflict.actual());
            assertEquals(2L, aggregateVersion("requirement", AGGREGATE_ID));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void missingPositiveExpectedVersionDoesNotInsert() throws Exception {
        VersionConflict conflict = assertThrows(
            VersionConflict.class,
            () -> inApiTenant(tx -> gate.advance(
                tx, TENANT_ID, "requirement", AGGREGATE_ID, new ExpectedVersion(1))));

        assertEquals(1L, conflict.expected());
        assertNull(conflict.actual());
        assertEquals(0, aggregateCount("requirement", AGGREGATE_ID));
    }

    @Test
    void maximumExpectedVersionAgainstMissingAggregateReportsMissingWithoutOverflow()
            throws Exception {
        VersionConflict conflict = assertThrows(
            VersionConflict.class,
            () -> inApiTenant(tx -> gate.advance(
                tx,
                TENANT_ID,
                "requirement",
                AGGREGATE_ID,
                new ExpectedVersion(Long.MAX_VALUE))));

        assertEquals(Long.MAX_VALUE, conflict.expected());
        assertNull(conflict.actual());
        assertEquals(0, aggregateCount("requirement", AGGREGATE_ID));
    }

    @Test
    void maximumExpectedVersionAgainstSmallerAggregateReportsStaleWithoutOverflow()
            throws Exception {
        insertAggregateHead("requirement", AGGREGATE_ID, 7);

        VersionConflict conflict = assertThrows(
            VersionConflict.class,
            () -> inApiTenant(tx -> gate.advance(
                tx,
                TENANT_ID,
                "requirement",
                AGGREGATE_ID,
                new ExpectedVersion(Long.MAX_VALUE))));

        assertEquals(Long.MAX_VALUE, conflict.expected());
        assertEquals(7L, conflict.actual());
        assertEquals(7L, aggregateVersion("requirement", AGGREGATE_ID));
    }

    @Test
    void exhaustedMaximumAggregateReportsVersionLimitWithoutOverflow()
            throws Exception {
        insertAggregateHead("requirement", AGGREGATE_ID, Long.MAX_VALUE);

        VersionConflict conflict = assertThrows(
            VersionConflict.class,
            () -> inApiTenant(tx -> gate.advance(
                tx,
                TENANT_ID,
                "requirement",
                AGGREGATE_ID,
                new ExpectedVersion(Long.MAX_VALUE))));

        assertEquals(Long.MAX_VALUE, conflict.expected());
        assertEquals(Long.MAX_VALUE, conflict.actual());
        assertEquals(Long.MAX_VALUE, aggregateVersion("requirement", AGGREGATE_ID));
    }

    @Test
    void staleCompletionEscapesTransactionAndRollsBackPrecedingAggregateWrite()
            throws Exception {
        CommandKey key = key("requirements.update", "idem-000000000007");
        ClaimLease stale = acquire(
            key, digest('1'), "worker-stale", Duration.ofMillis(100));
        serverSleep(Duration.ofMillis(250));
        ClaimLease current = assertInstanceOf(
            Claim.Acquired.class,
            inApiTenant(tx -> gate.claim(tx, key, digest('1'), "worker-current", LEASE)))
            .lease();
        StoredHttpResult response = new StoredHttpResult(
            200, Map.of("ETag", "\"1\""), "{\"version\": 1}");

        assertThrows(IllegalStateException.class, () -> inApiTenant(tx -> {
            long version = gate.advance(
                tx, TENANT_ID, "requirement", AGGREGATE_ID, new ExpectedVersion(0));
            gate.complete(
                tx, key, stale, response, "requirement", AGGREGATE_ID, version,
                Duration.ofHours(24));
            return null;
        }));

        assertEquals(0, aggregateCount("requirement", AGGREGATE_ID));
        assertEquals(current, persistedLease(key));
    }

    @Test
    void naturallyExpiredCompletionFailsAndRollsBackWithoutAnyTakeover() throws Exception {
        CommandKey key = key("requirements.update", "idem-000000000012");
        ClaimLease expired = acquire(
            key, digest('6'), "worker-expired", Duration.ofMillis(100));
        serverSleep(Duration.ofMillis(250));

        assertThrows(IllegalStateException.class, () -> inApiTenant(tx -> {
            long version = gate.advance(
                tx, TENANT_ID, "requirement", AGGREGATE_ID, new ExpectedVersion(0));
            gate.complete(
                tx, key, expired, new StoredHttpResult(200, Map.of(), "{}"),
                "requirement", AGGREGATE_ID, version, Duration.ofHours(1));
            return null;
        }));

        assertEquals(0, aggregateCount("requirement", AGGREGATE_ID));
        assertEquals(expired, persistedLease(key));
    }

    @Test
    void persistentRejectionStatusesReplayExactlyWithoutAggregateBinding() throws Exception {
        int[] statuses = {404, 412, 422};
        char[] fingerprints = {'4', 'c', 'e'};
        for (int index = 0; index < statuses.length; index++) {
            int status = statuses[index];
            char fingerprint = fingerprints[index];
            CommandKey key = key(
                "validations.create", "idem-rejection-" + status);
            ClaimLease lease = acquire(
                key, digest(fingerprint), "worker-" + status, LEASE);
            StoredHttpResult response = new StoredHttpResult(
                status,
                Map.of(
                    "Content-Type", "application/problem+json",
                    "X-Correlation-ID", "30000000-0000-0000-0000-000000000001"),
                "{\n  \"status\": " + status + ", \"code\": \"REJECTED\"\n}");

            inApiTenant(tx -> {
                gate.completeRejection(tx, key, lease, response, Duration.ofHours(24));
                return null;
            });

            Claim.Replay replay = assertInstanceOf(
                Claim.Replay.class,
                inApiTenant(tx -> gate.claim(
                    tx, key, digest(fingerprint), "api-replay", LEASE)));
            assertEquals(response, replay.result());
            assertDetachedAggregateBinding(key);
        }
    }

    @Test
    void persistentRejectionRejectsEveryUnapprovedStatusBeforeSql() {
        DSLContext disconnected = DSL.using(SQLDialect.POSTGRES);
        CommandKey key = key("validations.create", "idem-rejection-status");
        ClaimLease lease = new ClaimLease(
            "worker", 1, UUID.randomUUID(),
            OffsetDateTime.parse("2026-07-26T00:00:00Z"));

        for (int status : List.of(201, 400, 401, 403, 406, 409, 415, 500)) {
            assertThrows(
                IllegalArgumentException.class,
                () -> gate.completeRejection(
                    disconnected,
                    key,
                    lease,
                    new StoredHttpResult(status, Map.of(), "{}"),
                    Duration.ofHours(1)),
                "status " + status + " must fail before disconnected SQL is used");
        }
    }

    @Test
    void rawSqlCannotPersistPartialAggregateBindingForRejection() throws Exception {
        CommandKey key = key("validations.create", "idem-rejection-partial");
        ClaimLease lease = acquire(key, digest('9'), "worker-partial", LEASE);

        DataAccessException violation = assertThrows(
            DataAccessException.class,
            () -> inApiTenant(tx -> {
                tx.execute("""
                    UPDATE idempotency_result
                    SET state='COMPLETED',
                        claim_owner=NULL,
                        claim_token=NULL,
                        lease_until=NULL,
                        response_status=404,
                        response_headers='{}'::jsonb,
                        response_body='{}',
                        aggregate_type='contract-validation',
                        aggregate_id=NULL,
                        aggregate_version=NULL,
                        completed_at=clock_timestamp(),
                        expires_at=clock_timestamp() + INTERVAL '1 hour'
                    WHERE tenant_id=? AND actor_id=?
                      AND route_key=? AND idempotency_key=?
                    """,
                    key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey());
                return null;
            }));

        assertTrue(
            violation.getMessage().contains(
                "idempotency_result_aggregate_binding_consistent"),
            violation.getMessage());
        assertEquals(lease, persistedLease(key));
    }

    @Test
    void naturallyExpiredPersistentRejectionFailsWithoutTakeover() throws Exception {
        CommandKey key = key("validations.create", "idem-rejection-expired");
        ClaimLease expired = acquire(
            key, digest('a'), "worker-expired", Duration.ofMillis(100));
        serverSleep(Duration.ofMillis(250));

        assertThrows(IllegalStateException.class, () -> inApiTenant(tx -> {
            gate.completeRejection(
                tx, key, expired,
                new StoredHttpResult(404, Map.of(), "{}"),
                Duration.ofHours(1));
            return null;
        }));

        assertEquals(expired, persistedLease(key));
    }

    @Test
    void takenOverPersistentRejectionRollsBackPrecedingTransactionWrite() throws Exception {
        CommandKey key = key("validations.create", "idem-rejection-taken");
        ClaimLease stale = acquire(
            key, digest('b'), "worker-stale", Duration.ofMillis(100));
        serverSleep(Duration.ofMillis(250));
        ClaimLease current = assertInstanceOf(
            Claim.Acquired.class,
            inApiTenant(tx -> gate.claim(tx, key, digest('b'), "worker-current", LEASE)))
            .lease();
        UUID markerId = UUID.fromString("20000000-0000-0000-0000-000000000099");

        assertThrows(IllegalStateException.class, () -> inApiTenant(tx -> {
            gate.advance(
                tx, TENANT_ID, "transaction-marker", markerId, new ExpectedVersion(0));
            gate.completeRejection(
                tx, key, stale,
                new StoredHttpResult(412, Map.of(), "{}"),
                Duration.ofHours(1));
            return null;
        }));

        assertEquals(0, aggregateCount("transaction-marker", markerId));
        assertEquals(current, persistedLease(key));
    }

    @Test
    void sameLeasePersistentRejectionRaceAllowsExactlyOneCommit() throws Exception {
        CommandKey key = key("validations.create", "idem-rejection-race");
        ClaimLease lease = acquire(key, digest('d'), "worker-race", LEASE);
        StoredHttpResult response = new StoredHttpResult(
            422, Map.of("Content-Type", "application/problem+json"), "{\"status\":422}");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome<Void>> first = racedApi(executor, ready, start, tx -> {
                gate.completeRejection(tx, key, lease, response, Duration.ofHours(1));
                return null;
            });
            Future<Outcome<Void>> second = racedApi(executor, ready, start, tx -> {
                gate.completeRejection(tx, key, lease, response, Duration.ofHours(1));
                return null;
            });
            assertTrue(ready.await(5, SECONDS));
            start.countDown();

            List<Outcome<Void>> outcomes = List.of(
                first.get(10, SECONDS), second.get(10, SECONDS));
            assertEquals(1, outcomes.stream().filter(outcome -> !outcome.failed()).count());
            assertEquals(1, outcomes.stream()
                .map(Outcome::error)
                .filter(IllegalStateException.class::isInstance)
                .count());
            Claim.Replay replay = assertInstanceOf(
                Claim.Replay.class,
                inApiTenant(tx -> gate.claim(tx, key, digest('d'), "api-replay", LEASE)));
            assertEquals(response, replay.result());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void completionThenReclaimReplaysLogicalHeadersAndExactBodyText() throws Exception {
        CommandKey key = key("validations.create", "idem-000000000008");
        ClaimLease lease = acquire(key, digest('2'), "worker-1", LEASE);
        StoredHttpResult response = new StoredHttpResult(
            201,
            Map.of("ETag", "\"1\"", "X-Trace", "alpha, beta"),
            "{\n  \"version\": 1, \"details\": {\"b\": 2, \"a\": 1}\n}");
        inApiTenant(tx -> {
            long version = gate.advance(
                tx, TENANT_ID, "validation", AGGREGATE_ID, new ExpectedVersion(0));
            gate.complete(
                tx, key, lease, response, "validation", AGGREGATE_ID, version,
                Duration.ofHours(24));
            return null;
        });

        Claim.Replay replay = assertInstanceOf(
            Claim.Replay.class,
            inApiTenant(tx -> gate.claim(tx, key, digest('2'), "api-replay", LEASE)));

        assertEquals(response.status(), replay.result().status());
        assertEquals(response.headers(), replay.result().headers());
        assertEquals(response.body(), replay.result().body());
    }

    @Test
    void completionRequiresMatchingTenantAggregateHeadAndVersion() throws Exception {
        CommandKey missingKey = key("validations.create", "idem-000000000013");
        ClaimLease missingLease = acquire(missingKey, digest('7'), "worker-1", LEASE);
        StoredHttpResult response = new StoredHttpResult(200, Map.of(), "{}");

        assertThrows(IllegalStateException.class, () -> inApiTenant(tx -> {
            gate.complete(
                tx, missingKey, missingLease, response,
                "validation", AGGREGATE_ID, 1, Duration.ofHours(1));
            return null;
        }));

        inApiTenant(tx -> gate.advance(
            tx, TENANT_ID, "validation", AGGREGATE_ID, new ExpectedVersion(0)));
        CommandKey mismatchKey = key("validations.update", "idem-000000000014");
        ClaimLease mismatchLease = acquire(mismatchKey, digest('8'), "worker-2", LEASE);
        assertThrows(IllegalStateException.class, () -> inApiTenant(tx -> {
            gate.complete(
                tx, mismatchKey, mismatchLease, response,
                "validation", AGGREGATE_ID, 2, Duration.ofHours(1));
            return null;
        }));
    }

    @Test
    void sharedBoundedTextRejectsUnsafeUnicodeBeforeSql() {
        String nulText = "invalid" + (char) 0 + "text";
        String highSurrogate = String.valueOf((char) 0xd800);
        String lowSurrogate = String.valueOf((char) 0xdc00);
        String controlText = "invalid\ntext";
        String unicodeText = "valid-" + new String(Character.toChars(0x1f680));
        OffsetDateTime deadline = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        DSLContext disconnected = DSL.using(SQLDialect.POSTGRES);
        CommandKey key = key("requirements.update", "idem-000000000015");
        ClaimLease dummy = new ClaimLease("worker", 1, UUID.randomUUID(), deadline);

        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, nulText, "route", "idem-000000000015")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, highSurrogate, "route", "idem-000000000015")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, "actor", lowSurrogate, "idem-000000000015")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, "actor", controlText, "idem-000000000015")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(
                    TENANT_ID, "actor", "bad" + (char) 0x7f,
                    "idem-000000000015")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new ClaimLease(nulText, 1, UUID.randomUUID(), deadline)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new ClaimLease(highSurrogate, 1, UUID.randomUUID(), deadline)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new ClaimLease(controlText, 1, UUID.randomUUID(), deadline)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.claim(disconnected, key, digest('3'), nulText, LEASE)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.claim(disconnected, key, digest('3'), lowSurrogate, LEASE)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.claim(disconnected, key, digest('3'), controlText, LEASE)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.advance(
                    disconnected, TENANT_ID, highSurrogate,
                    AGGREGATE_ID, new ExpectedVersion(0))),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.advance(
                    disconnected, TENANT_ID, controlText,
                    AGGREGATE_ID, new ExpectedVersion(0))),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.complete(
                    disconnected, key, dummy,
                    new StoredHttpResult(200, Map.of(), "{}"),
                    nulText, AGGREGATE_ID, 1, Duration.ofHours(1))));
        assertAll(
            () -> assertNotNull(new CommandKey(
                TENANT_ID, unicodeText, unicodeText, "A0._:-a0_________")),
            () -> assertNotNull(new ClaimLease(
                unicodeText, 1, UUID.randomUUID(), deadline)));
    }

    @Test
    void boundedTextLimitsUseUnicodeCodePointsBeforeSql() {
        String supplementary = new String(Character.toChars(0x1f680));
        String actorAtLimit = supplementary.repeat(255);
        String actorOverLimit = supplementary.repeat(256);
        String routeAtLimit = supplementary.repeat(128);
        String routeOverLimit = supplementary.repeat(129);
        String ownerAtLimit = supplementary.repeat(255);
        String ownerOverLimit = supplementary.repeat(256);
        String aggregateTypeAtLimit = supplementary.repeat(64);
        String aggregateTypeOverLimit = supplementary.repeat(65);
        OffsetDateTime deadline = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        DSLContext disconnected = DSL.using(SQLDialect.POSTGRES);
        CommandKey key = key("requirements.update", "idem-000000000016");

        assertAll(
            () -> assertNotNull(new CommandKey(
                TENANT_ID, actorAtLimit, "route", "idem-000000000016")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(
                    TENANT_ID, actorOverLimit, "route", "idem-000000000016")),
            () -> assertNotNull(new CommandKey(
                TENANT_ID, "actor", routeAtLimit, "idem-000000000016")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(
                    TENANT_ID, "actor", routeOverLimit, "idem-000000000016")),
            () -> assertNotNull(new ClaimLease(
                ownerAtLimit, 1, UUID.randomUUID(), deadline)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new ClaimLease(ownerOverLimit, 1, UUID.randomUUID(), deadline)),
            () -> assertThrows(DataAccessException.class, () ->
                gate.claim(disconnected, key, digest('3'), ownerAtLimit, LEASE)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.claim(disconnected, key, digest('3'), ownerOverLimit, LEASE)),
            () -> assertThrows(DataAccessException.class, () ->
                gate.advance(
                    disconnected, TENANT_ID, aggregateTypeAtLimit,
                    AGGREGATE_ID, new ExpectedVersion(0))),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.advance(
                    disconnected, TENANT_ID, aggregateTypeOverLimit,
                    AGGREGATE_ID, new ExpectedVersion(0))));
    }

    @Test
    void responseHeaderValuesRequireStrictUtf8BeforeSql() {
        String highSurrogate = String.valueOf((char) 0xd800);
        String lowSurrogate = String.valueOf((char) 0xdc00);
        String supplementary = new String(Character.toChars(0x1f680));
        StoredHttpResult valid = assertDoesNotThrow(() -> new StoredHttpResult(
            200, Map.of("X-Trace", "before\t" + supplementary + "after"), "{}"));
        DSLContext disconnected = DSL.using(SQLDialect.POSTGRES);
        CommandKey key = key("requirements.update", "idem-000000000017");
        ClaimLease dummy = new ClaimLease(
            "worker", 1, UUID.randomUUID(),
            OffsetDateTime.parse("2026-07-26T00:00:00Z"));

        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of("X-Trace", highSurrogate), "{}")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of("X-Trace", lowSurrogate), "{}")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.complete(
                    disconnected, key, dummy,
                    new StoredHttpResult(
                        200, Map.of("X-Trace", highSurrogate), "{}"),
                    "requirement", AGGREGATE_ID, 1, Duration.ofHours(1))),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.complete(
                    disconnected, key, dummy,
                    new StoredHttpResult(
                        200, Map.of("X-Trace", lowSurrogate), "{}"),
                    "requirement", AGGREGATE_ID, 1, Duration.ofHours(1))),
            () -> assertThrows(DataAccessException.class, () ->
                gate.complete(
                    disconnected, key, dummy, valid,
                    "requirement", AGGREGATE_ID, 1, Duration.ofHours(1))));
    }

    @Test
    void publicTypesAndInputsFailClosedBeforeSql() throws Exception {
        Constructor<JooqCommandGate> publicConstructor = JooqCommandGate.class.getConstructor();
        Constructor<JooqCommandGate> mapperConstructor =
            JooqCommandGate.class.getDeclaredConstructor(ObjectMapper.class);
        assertAll(
            () -> assertTrue(Modifier.isPublic(publicConstructor.getModifiers())),
            () -> assertFalse(Modifier.isPublic(mapperConstructor.getModifiers())),
            () -> assertFalse(Modifier.isProtected(mapperConstructor.getModifiers())),
            () -> assertFalse(Modifier.isPrivate(mapperConstructor.getModifiers())));

        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> new ExpectedVersion(-1)),
            () -> assertThrows(NullPointerException.class, () ->
                new CommandKey(null, "actor", "route", "idem-000000000009")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, " ", "route", "idem-000000000009")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, "a".repeat(256), "route", "idem-000000000009")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, "actor", "r".repeat(129), "idem-000000000009")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, "actor", "route", "123456789012345")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, "actor", "route", "x".repeat(129))),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, "actor", "route", "idem-00000000000/")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new CommandKey(TENANT_ID, "actor", "route", "idem-00000000000\u00e9")));
        assertNotNull(new CommandKey(
            TENANT_ID, "actor", "route", "A0._:-a0_________"));

        OffsetDateTime deadline = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () ->
                new ClaimLease(" ", 1, UUID.randomUUID(), deadline)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new ClaimLease("o".repeat(256), 1, UUID.randomUUID(), deadline)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new ClaimLease("owner", 0, UUID.randomUUID(), deadline)),
            () -> assertThrows(NullPointerException.class, () ->
                new ClaimLease("owner", 1, null, deadline)),
            () -> assertThrows(NullPointerException.class, () ->
                new ClaimLease("owner", 1, UUID.randomUUID(), null)),
            () -> assertThrows(NullPointerException.class, () -> new Claim.Acquired(null)),
            () -> assertThrows(NullPointerException.class, () -> new Claim.InProgress(null)),
            () -> assertThrows(NullPointerException.class, () -> new Claim.Replay(null)));

        Map<String, String> mutableHeaders = new HashMap<>();
        mutableHeaders.put("X-Trace", "original");
        StoredHttpResult copied = new StoredHttpResult(200, mutableHeaders, "{}");
        mutableHeaders.put("X-Trace", "changed");
        assertEquals("original", copied.headers().get("x-trace"));
        assertThrows(
            UnsupportedOperationException.class,
            () -> copied.headers().put("X-New", "value"));
        String boundedUtf8Body = "\u00e9".repeat(524_288);
        StoredHttpResult boundedUtf8 = new StoredHttpResult(
            200, Map.of("Content-Type", "text/plain; charset=utf-8"), boundedUtf8Body);
        assertEquals(boundedUtf8Body, boundedUtf8.body());
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(99, Map.of(), "{}")),
            () -> assertThrows(NullPointerException.class, () ->
                new StoredHttpResult(200, Map.of(), null)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of("Bad Name", "value"), "{}")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of("X-Test", "a\r\nb"), "{}")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of("Connection", "close"), "{}")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of("Authorization", "secret"), "{}")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(
                    200, Map.of("AuThEnTiCaTiOn-InFo", "nextnonce=secret"), "{}")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of("Set-Cookie", "secret=x"), "{}")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(
                    200, Map.of("ETag", "one", "etag", "two"), "{}")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of(), "\u00e9".repeat(524_289))),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of(), "before" + (char) 0 + "after")),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of(), String.valueOf((char) 0xd800))),
            () -> assertThrows(IllegalArgumentException.class, () ->
                new StoredHttpResult(200, Map.of(), String.valueOf((char) 0xdc00))));
        assertEquals(
            new StoredHttpResult(200, Map.of("ETag", "one"), "{}"),
            new StoredHttpResult(200, Map.of("etag", "one"), "{}"));

        DSLContext disconnected = DSL.using(SQLDialect.POSTGRES);
        CommandKey key = key("requirements.update", "idem-000000000009");
        ClaimLease dummy = new ClaimLease("worker", 1, UUID.randomUUID(), deadline);
        StoredHttpResult oversizedHeaders = new StoredHttpResult(
            200, Map.of("X-Large", "x".repeat(65_536)), "{}");
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.claim(disconnected, key, "bad", "worker", LEASE)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.claim(disconnected, key, digest('3'), " ", LEASE)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.claim(disconnected, key, digest('3'), "worker", Duration.ZERO)),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.claim(disconnected, key, digest('3'), "worker", Duration.ofNanos(1))),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.claim(
                    disconnected, key, digest('3'), "worker",
                    Duration.ofSeconds(Long.MAX_VALUE))),
            () -> assertThrows(IllegalArgumentException.class, () ->
                gate.complete(
                    disconnected, key, dummy, oversizedHeaders,
                    "requirement", AGGREGATE_ID, 1, Duration.ofHours(1))));
    }

    @Test
    void malformedPersistedReplayHeadersFailClosed() throws Exception {
        CommandKey key = key("validations.create", "idem-000000000010");
        ClaimLease lease = acquire(key, digest('4'), "worker-1", LEASE);
        inApiTenant(tx -> {
            long version = gate.advance(
                tx, TENANT_ID, "validation", AGGREGATE_ID, new ExpectedVersion(0));
            gate.complete(
                tx, key, lease, new StoredHttpResult(200, Map.of(), "{}"),
                "validation", AGGREGATE_ID, version, Duration.ofHours(1));
            return null;
        });
        try (Connection connection = adminConnection();
             PreparedStatement update = connection.prepareStatement("""
                 UPDATE idempotency_result
                 SET response_headers = '{"X-Nested":{"unsafe":true}}'::jsonb
                 WHERE tenant_id=? AND actor_id=? AND route_key=? AND idempotency_key=?
                 """)) {
            bindKey(update, key);
            assertEquals(1, update.executeUpdate());
        }

        assertThrows(
            IllegalStateException.class,
            () -> inApiTenant(tx -> gate.claim(tx, key, digest('4'), "api-replay", LEASE)));
    }

    @Test
    void conflictRowDeletedBeforeSelectIsRetriedOnce() throws Exception {
        CommandKey key = key("requirements.update", "idem-000000000011");
        acquire(key, digest('5'), "worker-old", LEASE);
        CountDownLatch conflictInsertFinished = new CountDownLatch(1);
        CountDownLatch deletionCommitted = new CountDownLatch(1);
        AtomicBoolean intercepted = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection claimantConnection = openApiConnection()) {
            claimantConnection.setAutoCommit(false);
            setTenantContext(claimantConnection);
            ExecuteListener listener = new ExecuteListener() {
                @Override
                public void end(ExecuteContext context) {
                    String sql = context.sql();
                    if (sql != null
                            && sql.toLowerCase(java.util.Locale.ROOT)
                                .contains("insert into idempotency_result")
                            && intercepted.compareAndSet(false, true)) {
                        conflictInsertFinished.countDown();
                        try {
                            if (!deletionCommitted.await(5, SECONDS)) {
                                throw new IllegalStateException("row deletion did not commit");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted awaiting row deletion", error);
                        }
                    }
                }
            };
            DefaultConfiguration configuration = new DefaultConfiguration();
            configuration.set(claimantConnection);
            configuration.set(SQLDialect.POSTGRES);
            configuration.set(new DefaultExecuteListenerProvider(listener));
            DSLContext intercepting = DSL.using(configuration);

            Future<Outcome<Claim>> claimant = executor.submit(() -> capture(() -> {
                try {
                    Claim result = gate.claim(
                        intercepting, key, digest('5'), "worker-new", LEASE);
                    claimantConnection.commit();
                    return result;
                } catch (Throwable error) {
                    rollbackWithSuppressed(claimantConnection, error);
                    throwFailure(error);
                    return null;
                }
            }));
            assertTrue(conflictInsertFinished.await(5, SECONDS));
            Future<Integer> deleted = executor.submit(() -> inWorkerTenant(tx -> tx.execute("""
                DELETE FROM idempotency_result
                WHERE tenant_id=? AND actor_id=? AND route_key=? AND idempotency_key=?
                """, key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey())));
            try {
                assertEquals(1, deleted.get(5, SECONDS));
            } finally {
                deletionCommitted.countDown();
            }

            Outcome<Claim> outcome = claimant.get(10, SECONDS);
            assertNull(outcome.error());
            ClaimLease lease = assertInstanceOf(Claim.Acquired.class, outcome.value()).lease();
            assertEquals(1L, lease.generation());
            assertEquals(1, idempotencyRowCount(key));
        } finally {
            deletionCommitted.countDown();
            executor.shutdownNow();
        }
    }

    private ClaimLease acquire(
            CommandKey key, String fingerprint, String owner, Duration duration) throws Exception {
        return assertInstanceOf(
            Claim.Acquired.class,
            inApiTenant(tx -> gate.claim(tx, key, fingerprint, owner, duration)))
            .lease();
    }

    private CommandKey key(String route, String idempotencyKey) {
        return new CommandKey(TENANT_ID, "user-7", route, idempotencyKey);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private <T> Future<Outcome<T>> racedApi(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            SqlWork<T> work) {
        return racedApi(executor, ready, start, ignored -> {}, work);
    }

    private <T> Future<Outcome<T>> racedApi(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            Consumer<OffsetDateTime> transactionStartObserver,
            SqlWork<T> work) {
        return executor.submit(() -> capture(() -> {
            try (Connection connection = openApiConnection()) {
                connection.setAutoCommit(false);
                setTenantContext(connection);
                DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
                transactionStartObserver.accept(required(tx.fetchOne("""
                    SELECT transaction_timestamp() AS transaction_started_at
                    """), "transaction_started_at", OffsetDateTime.class));
                ready.countDown();
                if (!start.await(5, SECONDS)) {
                    throw new IllegalStateException("race start barrier timed out");
                }
                return runTransaction(connection, false, work);
            }
        }));
    }

    private <T> T inApiTenant(SqlWork<T> work) throws Exception {
        try (Connection connection = openApiConnection()) {
            return runTransaction(connection, true, work);
        }
    }

    private <T> T inWorkerTenant(SqlWork<T> work) throws Exception {
        try (Connection connection = openWorkerConnection()) {
            return runTransaction(connection, true, work);
        }
    }

    private <T> T runTransaction(Connection connection, boolean initialize, SqlWork<T> work)
            throws Exception {
        if (initialize) {
            connection.setAutoCommit(false);
            setTenantContext(connection);
        }
        DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
        try {
            T result = work.run(tx);
            connection.commit();
            return result;
        } catch (Throwable error) {
            rollbackWithSuppressed(connection, error);
            throwFailure(error);
            return null;
        }
    }

    private Connection openApiConnection() throws SQLException {
        return openRoleConnection(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api");
    }

    private Connection openWorkerConnection() throws SQLException {
        return openRoleConnection(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker");
    }

    private Connection openRoleConnection(String login, String password, String role)
            throws SQLException {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), login, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
            return connection;
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void setTenantContext(Connection connection) throws SQLException {
        try (PreparedStatement context = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            context.setString(1, TENANT_ID.toString());
            try (ResultSet ignored = context.executeQuery()) {
                assertTrue(ignored.next());
            }
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            if (!rows.next()) {
                throw new IllegalStateException("PostgreSQL backend PID is absent");
            }
            return rows.getInt(1);
        }
    }

    private static LockWaitObservation awaitForUpdateLockWait(
            Connection observer,
            int backendPid,
            OffsetDateTime deadline) throws SQLException {
        try (PreparedStatement probe = observer.prepareStatement("""
                SELECT clock_timestamp() AS database_now,
                       activity.state,
                       activity.wait_event_type,
                       activity.wait_event,
                       activity.query,
                       EXISTS (
                         SELECT 1
                         FROM pg_catalog.pg_locks AS waiting
                         WHERE waiting.pid=activity.pid AND NOT waiting.granted
                       ) AS has_ungranted_lock
                FROM pg_catalog.pg_stat_activity AS activity
                WHERE activity.pid=?
                """)) {
            probe.setInt(1, backendPid);
            while (true) {
                LockWaitObservation observation;
                try (ResultSet rows = probe.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError(
                            "waiter PostgreSQL backend disappeared before lock observation");
                    }
                    observation = new LockWaitObservation(
                        rows.getObject("database_now", OffsetDateTime.class),
                        rows.getString("state"),
                        rows.getString("wait_event_type"),
                        rows.getString("wait_event"),
                        rows.getString("query"),
                        rows.getBoolean("has_ungranted_lock"));
                }
                if (observation.isForUpdateLockWait()) {
                    return observation;
                }
                if (!observation.databaseTime().isBefore(deadline)) {
                    throw new AssertionError(
                        "lease deadline passed before PostgreSQL exposed the FOR UPDATE lock wait; "
                            + observation.diagnostic());
                }
                pauseConditionPoll();
            }
        }
    }

    private static OffsetDateTime awaitDatabaseTime(
            Connection connection, OffsetDateTime deadline) throws SQLException {
        try (Statement clock = connection.createStatement()) {
            while (true) {
                OffsetDateTime databaseTime;
                try (ResultSet rows = clock.executeQuery("SELECT clock_timestamp()")) {
                    if (!rows.next()) {
                        throw new IllegalStateException("PostgreSQL clock row is absent");
                    }
                    databaseTime = rows.getObject(1, OffsetDateTime.class);
                }
                if (!databaseTime.isBefore(deadline)) {
                    return databaseTime;
                }
                pauseConditionPoll();
            }
        }
    }

    private static void pauseConditionPoll() {
        LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("interrupted while polling PostgreSQL state");
        }
    }

    private void serverSleep(Duration duration) throws Exception {
        inApiTenant(tx -> {
            tx.execute("SELECT pg_sleep(?)", duration.toMillis() / 1_000.0d);
            return null;
        });
    }

    private static void serverSleep(Connection connection, Duration duration) throws SQLException {
        try (PreparedStatement sleep = connection.prepareStatement("SELECT pg_sleep(?)")) {
            sleep.setDouble(1, duration.toMillis() / 1_000.0d);
            sleep.execute();
        }
    }

    private int idempotencyRowCount(CommandKey key) throws Exception {
        return inApiTenant(tx -> tx.fetchOne("""
            SELECT count(*) AS value FROM idempotency_result
            WHERE tenant_id=? AND actor_id=? AND route_key=? AND idempotency_key=?
            """, key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey())
            .get("value", Integer.class));
    }

    private void assertDetachedAggregateBinding(CommandKey key) throws Exception {
        inApiTenant(tx -> {
            Record row = tx.fetchOne("""
                SELECT aggregate_type, aggregate_id, aggregate_version
                FROM idempotency_result
                WHERE tenant_id=? AND actor_id=? AND route_key=? AND idempotency_key=?
                """, key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey());
            assertNotNull(row);
            assertAll(
                () -> assertNull(row.get("aggregate_type")),
                () -> assertNull(row.get("aggregate_id")),
                () -> assertNull(row.get("aggregate_version")));
            return null;
        });
    }

    private String persistedFingerprint(CommandKey key) throws Exception {
        return inApiTenant(tx -> required(tx.fetchOne("""
            SELECT request_fingerprint FROM idempotency_result
            WHERE tenant_id=? AND actor_id=? AND route_key=? AND idempotency_key=?
            """, key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey()),
            "request_fingerprint", String.class));
    }

    private OffsetDateTime persistedLeaseUntil(CommandKey key) throws Exception {
        return inApiTenant(tx -> required(tx.fetchOne("""
            SELECT lease_until FROM idempotency_result
            WHERE tenant_id=? AND actor_id=? AND route_key=? AND idempotency_key=?
            """, key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey()),
            "lease_until", OffsetDateTime.class));
    }

    private ClaimLease persistedLease(CommandKey key) throws Exception {
        return inApiTenant(tx -> {
            Record row = tx.fetchOne("""
                SELECT claim_owner, claim_generation, claim_token, lease_until
                FROM idempotency_result
                WHERE tenant_id=? AND actor_id=? AND route_key=? AND idempotency_key=?
                """, key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey());
            return new ClaimLease(
                required(row, "claim_owner", String.class),
                required(row, "claim_generation", Long.class),
                required(row, "claim_token", UUID.class),
                required(row, "lease_until", OffsetDateTime.class));
        });
    }

    private int aggregateCount(String aggregateType, UUID aggregateId) throws Exception {
        return inApiTenant(tx -> tx.fetchOne("""
            SELECT count(*) AS value FROM aggregate_head
            WHERE tenant_id=? AND aggregate_type=? AND aggregate_id=?
            """, TENANT_ID, aggregateType, aggregateId).get("value", Integer.class));
    }

    private long aggregateVersion(String aggregateType, UUID aggregateId) throws Exception {
        return inApiTenant(tx -> required(tx.fetchOne("""
            SELECT version FROM aggregate_head
            WHERE tenant_id=? AND aggregate_type=? AND aggregate_id=?
            """, TENANT_ID, aggregateType, aggregateId), "version", Long.class));
    }

    private void insertAggregateHead(String aggregateType, UUID aggregateId, long version)
            throws Exception {
        inApiTenant(tx -> {
            assertEquals(1, tx.execute("""
                INSERT INTO aggregate_head (
                  tenant_id, aggregate_type, aggregate_id, version
                ) VALUES (?, ?, ?, ?)
                """, TENANT_ID, aggregateType, aggregateId, version));
            return null;
        });
    }

    private OffsetDateTime aggregateUpdatedAt(String aggregateType, UUID aggregateId)
            throws Exception {
        return inApiTenant(tx -> required(tx.fetchOne("""
            SELECT updated_at FROM aggregate_head
            WHERE tenant_id=? AND aggregate_type=? AND aggregate_id=?
            """, TENANT_ID, aggregateType, aggregateId), "updated_at", OffsetDateTime.class));
    }

    private static void bindKey(PreparedStatement statement, CommandKey key)
            throws SQLException {
        statement.setObject(1, key.tenantId());
        statement.setString(2, key.actorId());
        statement.setString(3, key.routeKey());
        statement.setString(4, key.idempotencyKey());
    }

    private static <T> T required(Record row, String field, Class<T> type) {
        if (row == null) {
            throw new IllegalStateException("required row is absent");
        }
        T value = row.get(field, type);
        if (value == null) {
            throw new IllegalStateException(field + " is unexpectedly null");
        }
        return value;
    }

    private static <T> Outcome<T> capture(CheckedSupplier<T> operation) {
        try {
            return new Outcome<>(operation.get(), null);
        } catch (Throwable error) {
            return new Outcome<>(null, error);
        }
    }

    private static void rollbackWithSuppressed(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException cleanupError) {
            original.addSuppressed(cleanupError);
        }
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        throw new IllegalStateException("unexpected throwable", failure);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(DSLContext tx) throws Exception;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private record Outcome<T>(T value, Throwable error) {
        boolean failed() {
            return error != null;
        }
    }

    private record LockWaitObservation(
        OffsetDateTime databaseTime,
        String state,
        String waitEventType,
        String waitEvent,
        String query,
        boolean hasUngrantedLock
    ) {
        boolean isForUpdateLockWait() {
            String normalized = query == null
                ? ""
                : query.toLowerCase(java.util.Locale.ROOT);
            return "active".equals(state)
                && "Lock".equals(waitEventType)
                && normalized.contains("from idempotency_result")
                && normalized.contains("for update")
                && hasUngrantedLock;
        }

        String diagnostic() {
            return "state=" + state
                + ", wait_event_type=" + waitEventType
                + ", wait_event=" + waitEvent
                + ", has_ungranted_lock=" + hasUngrantedLock;
        }
    }
}
