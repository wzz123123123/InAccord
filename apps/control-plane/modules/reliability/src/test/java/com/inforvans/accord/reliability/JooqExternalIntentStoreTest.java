package com.inforvans.accord.reliability;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class JooqExternalIntentStoreTest extends PostgreSqlReliabilityTestSupport {
    private static final Duration LEASE = Duration.ofSeconds(2);
    private final JooqExternalIntentStore store = new JooqExternalIntentStore();

    @Test
    void rootRegistrationIsIdempotentAndChangedDefinitionConflicts() throws Exception {
        ExternalIntentDefinition definition = definition("action-000000000001", digest('a'));
        assertInstanceOf(
            ExternalIntentRegistration.Created.class,
            inApi(TENANT_ID, tx -> store.record(tx, definition)));
        assertInstanceOf(
            ExternalIntentRegistration.Duplicate.class,
            inApi(TENANT_ID, tx -> store.record(
                tx, copyDefinition(definition, UUID.randomUUID(), definition.requestDigest(),
                    definition.providerInstallationId()))));
        assertInstanceOf(
            ExternalIntentRegistration.Conflict.class,
            inApi(TENANT_ID, tx -> store.record(
                tx, copyDefinition(
                    definition, UUID.randomUUID(), digest('b'),
                    definition.providerInstallationId()))));
        assertEquals(1, count("external_call_intent"));
    }

    @Test
    void simultaneousExecutionClaimsIssueExactlyOneWritePermit() throws Exception {
        ExternalIntentRef intent = record("action-000000000002");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ExecutionClaim> first = executor.submit(() -> claimRaced(
                intent, "worker-1", ready, start));
            Future<ExecutionClaim> second = executor.submit(() -> claimRaced(
                intent, "worker-2", ready, start));
            assertTrue(ready.await(5, SECONDS));
            start.countDown();
            ExecutionClaim one = first.get(10, SECONDS);
            ExecutionClaim two = second.get(10, SECONDS);
            assertEquals(1, java.util.stream.Stream.of(one, two)
                .filter(ExecutionClaim.Acquired.class::isInstance).count());
            ExternalWritePermit permit = java.util.stream.Stream.of(one, two)
                .filter(ExecutionClaim.Acquired.class::isInstance)
                .map(ExecutionClaim.Acquired.class::cast)
                .findFirst().orElseThrow().permit();
            assertEquals(1, permit.generation());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rolledBackExecutionClaimDoesNotConsumeTheOnlyWritePermit() throws Exception {
        ExternalIntentRef intent = record("action-claim-rollback-0001");
        try (Connection connection = openWorker(TENANT_ID)) {
            DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
            ExecutionClaim rolledBack = store.claimExecution(
                tx, TENANT_ID, intent.intentId(), "worker-crashed", LEASE);
            assertInstanceOf(ExecutionClaim.Acquired.class, rolledBack);
            connection.rollback();
        }

        ExternalWritePermit committed = acquire(intent, "worker-recovered", LEASE);
        assertEquals(1, committed.generation());
        assertEquals(
            ExternalIntentState.EXECUTING,
            inWorker(TENANT_ID, tx ->
                store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state()));
    }

    @Test
    void expiredExecutionCanOnlyReconcileAndNeverReceivesAnotherPermit() throws Exception {
        ExternalIntentRef intent = record("action-000000000003");
        ExternalWritePermit permit = acquire(intent, "worker-1", Duration.ofMillis(50));
        awaitDatabaseAfter(permit.leaseUntil());
        assertThrows(LostExternalIntentFence.class, () -> inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, permit,
                new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", null));
            return null;
        }));
        assertTrue(inWorker(TENANT_ID, tx ->
            store.expireExecution(tx, TENANT_ID, intent.intentId())).booleanValue());
        ExecutionClaim rejected = inWorker(TENANT_ID, tx ->
            store.claimExecution(tx, TENANT_ID, intent.intentId(), "worker-2", LEASE));
        assertEquals(
            ExternalIntentState.OUTCOME_UNKNOWN,
            assertInstanceOf(ExecutionClaim.NotExecutable.class, rejected).state());
        assertThrows(LostExternalIntentFence.class, () -> inWorker(TENANT_ID, tx -> {
            tx.execute("INSERT INTO aggregate_head VALUES (?, 'test', ?, 1)",
                TENANT_ID, UUID.randomUUID());
            store.finishExecution(
                tx, permit, new ExecutionResolution.Succeeded(digest('b'), "request-1"));
            return null;
        }));
        assertEquals(0, count("aggregate_head"));
    }

    @Test
    void unknownReconciliationCanRepeatButOnlyWithNewGeneration() throws Exception {
        ExternalIntentRef intent = record("action-000000000004");
        ExternalWritePermit permit = acquire(intent, "worker-1", LEASE);
        inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, permit,
                new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", null));
            return null;
        });
        ReconciliationLease first = reconcile(intent, "reconciler-1");
        inWorker(TENANT_ID, tx -> {
            store.finishReconciliation(
                tx, first,
                new ReconciliationResolution.StillUnknown("OBSERVATION_INCOMPLETE", null));
            return null;
        });
        ReconciliationLease second = reconcile(intent, "reconciler-2");
        assertEquals(first.generation() + 1, second.generation());
        assertNotEquals(first.token(), second.token());
        assertThrows(LostExternalIntentFence.class, () -> inWorker(TENANT_ID, tx -> {
            store.finishReconciliation(
                tx, first,
                new ReconciliationResolution.ConfirmedNoEffect(digest('c'), null));
            return null;
        }));
    }

    @Test
    void confirmedNoEffectCreatesExactlyOneCopiedSuccessor() throws Exception {
        ExternalIntentRef intent = record("action-000000000005");
        ExternalWritePermit permit = acquire(intent, "worker-1", LEASE);
        inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, permit,
                new ExecutionResolution.ConfirmedNoEffect(digest('d'), "request-2"));
            return null;
        });
        ExternalIntentRef successor = inApi(TENANT_ID, tx ->
            store.createSuccessor(tx, TENANT_ID, intent.intentId()));
        ExternalIntentRef duplicate = inApi(TENANT_ID, tx ->
            store.createSuccessor(tx, TENANT_ID, intent.intentId()));
        assertEquals(successor, duplicate);
        assertEquals(intent.rootIntentId(), successor.rootIntentId());
        assertEquals(intent.attemptOrdinal() + 1, successor.attemptOrdinal());
        assertNotEquals(intent.globalIdempotencyKey(), successor.globalIdempotencyKey());
        assertEquals(2, count("external_call_intent"));
    }

    @Test
    void simultaneousSuccessorRequestsReturnOneCommittedSuccessor() throws Exception {
        ExternalIntentRef intent = record("action-successor-race-01");
        ExternalWritePermit permit = acquire(intent, "worker-1", LEASE);
        inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, permit,
                new ExecutionResolution.ConfirmedNoEffect(digest('d'), "request-race"));
            return null;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ExternalIntentRef> first = executor.submit(() ->
                createSuccessorRaced(intent, ready, start));
            Future<ExternalIntentRef> second = executor.submit(() ->
                createSuccessorRaced(intent, ready, start));
            assertTrue(ready.await(5, SECONDS));
            start.countDown();
            assertEquals(first.get(10, SECONDS), second.get(10, SECONDS));
            assertEquals(2, count("external_call_intent"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successAndDivergenceAreTerminalAndCannotCreateSuccessors() throws Exception {
        ExternalIntentRef success = record("action-000000000006");
        ExternalWritePermit successPermit = acquire(success, "worker-1", LEASE);
        inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, successPermit,
                new ExecutionResolution.Succeeded(digest('e'), "request-3"));
            return null;
        });
        assertThrows(IllegalStateException.class, () -> inApi(TENANT_ID, tx ->
            store.createSuccessor(tx, TENANT_ID, success.intentId())));

        ExternalIntentRef diverged = record("action-000000000007");
        ExternalWritePermit divergedPermit = acquire(diverged, "worker-2", LEASE);
        inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, divergedPermit,
                new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", null));
            return null;
        });
        ReconciliationLease lease = reconcile(diverged, "reconciler-1");
        inWorker(TENANT_ID, tx -> {
            store.finishReconciliation(
                tx, lease,
                new ReconciliationResolution.Diverged(
                    digest('f'), "REMOTE_STATE_DIVERGED", null));
            return null;
        });
        assertThrows(IllegalStateException.class, () -> inApi(TENANT_ID, tx ->
            store.createSuccessor(tx, TENANT_ID, diverged.intentId())));
    }

    @Test
    void everyNonNoEffectInFlightStateAndOtherTenantRejectSuccessors() throws Exception {
        ExternalIntentRef recorded = record("action-successor-recorded");
        assertSuccessorRejected(TENANT_ID, recorded);
        assertSuccessorRejected(OTHER_TENANT_ID, recorded);

        ExternalIntentRef executing = record("action-successor-executing");
        acquire(executing, "worker-executing", LEASE);
        assertSuccessorRejected(TENANT_ID, executing);

        ExternalIntentRef unknown = record("action-successor-unknown-01");
        ExternalWritePermit unknownPermit = acquire(unknown, "worker-unknown", LEASE);
        inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, unknownPermit,
                new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", null));
            return null;
        });
        assertSuccessorRejected(TENANT_ID, unknown);

        ExternalIntentRef reconciling = record("action-successor-reconcile");
        ExternalWritePermit reconciliationPermit = acquire(
            reconciling, "worker-reconcile", LEASE);
        inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, reconciliationPermit,
                new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", null));
            return null;
        });
        reconcile(reconciling, "reconciler-active");
        assertSuccessorRejected(TENANT_ID, reconciling);
        assertEquals(4, count("external_call_intent"));
    }

    @Test
    void renewalExtendsExactPriorDeadlineAndInvalidatesOldFence() throws Exception {
        ExternalIntentRef intent = record("action-000000000008");
        ExternalWritePermit original = acquire(intent, "worker-1", LEASE);
        ExternalWritePermit renewed = inWorker(TENANT_ID, tx ->
            store.renewExecution(tx, original, Duration.ofSeconds(1)));
        assertEquals(original.leaseUntil().plusSeconds(1), renewed.leaseUntil());
        assertThrows(LostExternalIntentFence.class, () -> inWorker(TENANT_ID, tx ->
            store.renewExecution(tx, original, Duration.ofSeconds(1))));
    }

    @Test
    void tamperedPermitContextCannotRenewOrComplete() throws Exception {
        ExternalIntentRef intent = record("action-000000000011");
        ExternalWritePermit original = acquire(intent, "worker-1", LEASE);
        ExternalWritePermit tampered = new ExternalWritePermit(
            original.tenantId(), original.intentId(), original.owner(), original.generation(),
            original.token(), original.leaseUntil(), original.globalIdempotencyKey(),
            "github", original.providerInstallationId(), original.providerRepositoryId(),
            original.operation(), original.requestReferenceType(), original.requestReferenceId(),
            original.requestReferenceVersion(), original.requestDigest());
        assertThrows(LostExternalIntentFence.class, () -> inWorker(TENANT_ID, tx ->
            store.renewExecution(tx, tampered, Duration.ofSeconds(1))));
        assertThrows(LostExternalIntentFence.class, () -> inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, tampered, new ExecutionResolution.Succeeded(digest('9'), null));
            return null;
        }));
    }

    @Test
    void providerMutationRunsOnlyAfterClaimTransactionCommits() throws Exception {
        ExternalIntentRef intent = record("action-000000000009");
        AtomicInteger providerCalls = new AtomicInteger();
        ExternalWritePermit permit = acquire(intent, "worker-1", LEASE);
        providerCalls.incrementAndGet();
        inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, permit,
                new ExecutionResolution.Succeeded(digest('1'), "request-4"));
            return null;
        });
        assertEquals(1, providerCalls.get());
        assertEquals(
            ExternalIntentState.SUCCEEDED,
            inWorker(TENANT_ID, tx ->
                store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state()));
    }

    @Test
    void terminalTransitionAndEventOutboxShareCommitAndRollback() throws Exception {
        ExternalIntentRef intent = record("action-terminal-event-001");
        ExternalWritePermit permit = acquire(intent, "worker-1", LEASE);
        ReliableEventStore events = new ReliableEventStore();
        inWorker(TENANT_ID, tx -> {
            events.append(tx, event(1), outbox());
            store.finishExecution(
                tx, permit, new ExecutionResolution.Succeeded(digest('2'), "request-atomic"));
            return null;
        });
        assertEquals(1, count("domain_event"));
        assertEquals(1, count("outbox_event"));

        assertThrows(LostExternalIntentFence.class, () -> inWorker(TENANT_ID, tx -> {
            events.append(tx, event(2), outbox());
            store.finishExecution(
                tx, permit, new ExecutionResolution.Succeeded(digest('3'), "request-atomic"));
            return null;
        }));
        assertEquals(1, count("domain_event"));
        assertEquals(1, count("outbox_event"));
    }

    @Test
    void rawWorkerSqlCannotCompleteAnExpiredExecutionFence() throws Exception {
        ExternalIntentRef intent = record("action-expired-raw-sql-1");
        ExternalWritePermit permit = acquire(intent, "worker-1", Duration.ofMillis(50));
        awaitDatabaseAfter(permit.leaseUntil());

        assertThrows(org.jooq.exception.DataAccessException.class, () ->
            inWorker(TENANT_ID, tx -> {
                tx.execute("""
                    UPDATE external_call_intent
                    SET state='SUCCEEDED',outcome_digest=?,terminal_at=clock_timestamp()
                    WHERE tenant_id=? AND intent_id=?
                    """, digest('4'), TENANT_ID, intent.intentId());
                return null;
            }));
        assertEquals(
            ExternalIntentState.EXECUTING,
            inWorker(TENANT_ID, tx ->
                store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state()));
    }

    @Test
    void rawWorkerSqlCannotCompleteAnExpiredReconciliationFence() throws Exception {
        ExternalIntentRef intent = record("action-expired-reconcile-1");
        ExternalWritePermit permit = acquire(intent, "worker-1", LEASE);
        inWorker(TENANT_ID, tx -> {
            store.finishExecution(
                tx, permit,
                new ExecutionResolution.OutcomeUnknown("PROVIDER_TIMEOUT", "request-expired"));
            return null;
        });
        ReconciliationClaim claim = inWorker(TENANT_ID, tx ->
            store.claimReconciliation(
                tx, TENANT_ID, intent.intentId(), "reconciler-1",
                Duration.ofMillis(50)));
        ReconciliationLease lease =
            assertInstanceOf(ReconciliationClaim.Acquired.class, claim).lease();
        awaitDatabaseAfter(lease.leaseUntil());

        assertThrows(org.jooq.exception.DataAccessException.class, () ->
            inWorker(TENANT_ID, tx -> {
                tx.execute("""
                    UPDATE external_call_intent
                    SET state='SUCCEEDED',reconciliation_owner=NULL,
                        reconciliation_token=NULL,reconciliation_started_at=NULL,
                        reconciliation_lease_until=NULL,outcome_digest=?,
                        terminal_at=clock_timestamp()
                    WHERE tenant_id=? AND intent_id=?
                    """, digest('5'), TENANT_ID, intent.intentId());
                return null;
            }));
        assertEquals(
            ExternalIntentState.RECONCILING,
            inWorker(TENANT_ID, tx ->
                store.load(tx, TENANT_ID, intent.intentId()).orElseThrow().state()));
    }

    @Test
    void publicValuesRejectSensitiveOrMalformedIdentifiersBeforeSql() {
        ExternalIntentDefinition valid = definition("action-000000000010", digest('a'));
        assertThrows(IllegalArgumentException.class, () ->
            copyDefinition(
                valid, valid.intentId(), valid.requestDigest(),
                "https://provider.example/install/1"));
        assertThrows(IllegalArgumentException.class, () ->
            copyDefinition(
                valid, valid.intentId(), "sha256:bad", valid.providerInstallationId()));
        assertThrows(IllegalArgumentException.class, () ->
            new ExecutionResolution.OutcomeUnknown("lower-case", null));
        UUID intentId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ExternalIntentRef(
            TENANT_ID, intentId, intentId, 1,
            "accord:v1:" + OTHER_TENANT_ID + ":" + intentId,
            ExternalIntentState.RECORDED));
        assertThrows(IllegalArgumentException.class, () ->
            new ReconciliationResolution.Succeeded(digest('1'), "https://bad.example"));
        assertThrows(IllegalArgumentException.class, () ->
            new ReconciliationResolution.ConfirmedNoEffect(
                digest('1'), "https://bad.example"));
        assertThrows(IllegalArgumentException.class, () ->
            new ReconciliationResolution.Diverged(
                digest('1'), "REMOTE_DIVERGED", "https://bad.example"));
        assertThrows(IllegalArgumentException.class, () ->
            new ReconciliationResolution.StillUnknown(
                "OBSERVATION_INCOMPLETE", "https://bad.example"));
    }

    private ExecutionClaim claimRaced(
            ExternalIntentRef intent,
            String owner,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        try (Connection connection = openWorker(TENANT_ID)) {
            DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
            ready.countDown();
            if (!start.await(5, SECONDS)) {
                throw new IllegalStateException("race start timed out");
            }
            ExecutionClaim result = store.claimExecution(
                tx, TENANT_ID, intent.intentId(), owner, LEASE);
            connection.commit();
            return result;
        }
    }

    private ExternalIntentRef createSuccessorRaced(
            ExternalIntentRef intent,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        try (Connection connection = openApi(TENANT_ID)) {
            ready.countDown();
            if (!start.await(5, SECONDS)) {
                throw new IllegalStateException("successor race start timed out");
            }
            return runTransaction(connection, tx ->
                store.createSuccessor(tx, TENANT_ID, intent.intentId()));
        }
    }

    private ExternalIntentRef record(String logicalKey) throws Exception {
        ExternalIntentRegistration registration = inApi(TENANT_ID, tx ->
            store.record(tx, definition(logicalKey, digest('a'))));
        return assertInstanceOf(ExternalIntentRegistration.Created.class, registration).intent();
    }

    private void assertSuccessorRejected(UUID tenantId, ExternalIntentRef intent) {
        assertThrows(IllegalStateException.class, () -> inApi(tenantId, tx ->
            store.createSuccessor(tx, tenantId, intent.intentId())));
    }

    private ExternalWritePermit acquire(
            ExternalIntentRef intent, String owner, Duration lease) throws Exception {
        ExecutionClaim claim = inWorker(TENANT_ID, tx -> store.claimExecution(
            tx, TENANT_ID, intent.intentId(), owner, lease));
        return assertInstanceOf(ExecutionClaim.Acquired.class, claim).permit();
    }

    private ReconciliationLease reconcile(ExternalIntentRef intent, String owner)
            throws Exception {
        ReconciliationClaim claim = inWorker(TENANT_ID, tx -> store.claimReconciliation(
            tx, TENANT_ID, intent.intentId(), owner, LEASE));
        return assertInstanceOf(ReconciliationClaim.Acquired.class, claim).lease();
    }

    private static ExternalIntentDefinition definition(String logicalKey, String requestDigest) {
        return new ExternalIntentDefinition(
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
            requestDigest);
    }

    private static DomainEvent event(long sequence) {
        return new DomainEvent(
            TENANT_ID,
            UUID.nameUUIDFromBytes(("intent-event-" + sequence).getBytes(
                java.nio.charset.StandardCharsets.UTF_8)),
            "project",
            "project-1",
            "external_intent",
            UUID.fromString("20000000-0000-0000-0000-000000000008"),
            sequence,
            "external_intent.changed",
            "1.0.0",
            UUID.fromString("30000000-0000-0000-0000-000000000008"),
            UUID.fromString("40000000-0000-0000-0000-000000000008"),
            "worker-1",
            "{\"sequence\":" + sequence + "}",
            OffsetDateTime.parse("2026-07-26T00:00:00Z"));
    }

    private static OutboxMessage outbox() {
        return new OutboxMessage(
            "external-intents", "external-intent.event/1.0", "{\"kind\":\"changed\"}");
    }

    private static ExternalIntentDefinition copyDefinition(
            ExternalIntentDefinition source,
            UUID intentId,
            String requestDigest,
            String installationId) {
        return new ExternalIntentDefinition(
            source.tenantId(),
            intentId,
            source.logicalActionKey(),
            source.scopeType(),
            source.scopeId(),
            source.provider(),
            installationId,
            source.providerRepositoryId(),
            source.operation(),
            source.requestReferenceType(),
            source.requestReferenceId(),
            source.requestReferenceVersion(),
            requestDigest);
    }
}
