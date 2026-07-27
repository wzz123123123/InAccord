package com.inforvans.accord.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ReliableEventStoreTest extends PostgreSqlReliabilityTestSupport {
    private final ReliableEventStore store = new ReliableEventStore();

    @Test
    void domainEventAndOutboxCommitAndRollBackTogether() throws Exception {
        inApi(TENANT_ID, tx -> {
            store.append(tx, event(1), outbox());
            return null;
        });
        assertEquals(1, count("domain_event"));
        assertEquals(1, count("outbox_event"));

        assertThrows(IllegalStateException.class, () -> inApi(TENANT_ID, tx -> {
            store.append(tx, event(2), outbox());
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(1, count("domain_event"));
        assertEquals(1, count("outbox_event"));
    }

    @Test
    void firstMessageInsertsSignalExactlyOneDirectoryRowAndRollbackSignalsNothing()
            throws Exception {
        inApi(TENANT_ID, tx -> {
            store.append(tx, event(1), outbox());
            return null;
        });
        assertEquals(1, count("reliability_tenant_work"));

        assertThrows(IllegalStateException.class, () -> inApi(OTHER_TENANT_ID, tx -> {
            store.append(tx, new DomainEvent(
                OTHER_TENANT_ID, UUID.randomUUID(), "tenant", OTHER_TENANT_ID.toString(),
                "requirement", UUID.randomUUID(), 1, "requirement.changed", "1.0.0",
                UUID.randomUUID(), UUID.randomUUID(), "user-1", "{}",
                OffsetDateTime.parse("2026-07-26T00:00:00Z")), outbox());
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(1, count("reliability_tenant_work"));
    }

    @Test
    void firstInboxAcceptanceCreatesVisibleTenantWork() throws Exception {
        assertInstanceOf(InboxAcceptance.Accepted.class,
            inWorker(TENANT_ID, tx -> store.acceptInbox(
                tx, inbox("directory-message", digest('d')))));
        assertEquals(1, count("reliability_tenant_work"));
    }

    @Test
    void inboxNaturalKeyDeduplicatesDigestAndRejectsChangedDigest() throws Exception {
        InboxMessage first = inbox("message-1", digest('a'));
        assertInstanceOf(
            InboxAcceptance.Accepted.class,
            inWorker(TENANT_ID, tx -> store.acceptInbox(tx, first)));
        assertInstanceOf(
            InboxAcceptance.Duplicate.class,
            inWorker(TENANT_ID, tx -> store.acceptInbox(tx, first)));
        assertInstanceOf(
            InboxAcceptance.DigestConflict.class,
            inWorker(TENANT_ID, tx ->
                store.acceptInbox(tx, inbox("message-1", digest('b')))));
        assertEquals(1, count("inbox_message"));
    }

    @Test
    void valuesRejectMalformedAndOversizedPayloadsBeforeSql() {
        assertThrows(IllegalArgumentException.class, () ->
            new OutboxMessage("events", "event/1.0", "{"));
        assertThrows(IllegalArgumentException.class, () ->
            new OutboxMessage("events", "event/1.0", "{\"x\":\""
                + "x".repeat(1_048_577) + "\"}"));
        assertThrows(IllegalArgumentException.class, () ->
            inbox("message-1", "sha256:not-a-digest"));
    }

    @Test
    void payloadsRejectUnpairedSurrogatesAndAcceptSupplementaryCharacters() {
        assertThrows(IllegalArgumentException.class, () ->
            new OutboxMessage("events", "event/1.0", "{\"value\":\"\uD800\"}"));
        assertThrows(IllegalArgumentException.class, () ->
            new OutboxMessage("events", "event/1.0", "{\"value\":\"\uDC00\"}"));
        OutboxMessage valid = new OutboxMessage(
            "events", "event/1.0", "{\"value\":\"\uD83D\uDE00\"}");
        assertEquals("{\"value\":\"\uD83D\uDE00\"}", valid.payload());
    }

    @Test
    void aggregateSequenceRaceCommitsExactlyOneEventAndOutbox() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> racedAppend(
                event(1, UUID.randomUUID()), ready, start));
            Future<Boolean> second = executor.submit(() -> racedAppend(
                event(1, UUID.randomUUID()), ready, start));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, java.util.stream.Stream.of(first.get(), second.get())
                .filter(Boolean::booleanValue).count());
            assertEquals(1, count("domain_event"));
            assertEquals(1, count("outbox_event"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void inboxDuplicateRaceStoresOneMessage() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<InboxAcceptance> first = executor.submit(() -> racedInbox(ready, start));
            Future<InboxAcceptance> second = executor.submit(() -> racedInbox(ready, start));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            java.util.List<InboxAcceptance> outcomes = java.util.List.of(
                first.get(), second.get());
            assertEquals(1, outcomes.stream()
                .filter(InboxAcceptance.Accepted.class::isInstance).count());
            assertEquals(1, outcomes.stream()
                .filter(InboxAcceptance.Duplicate.class::isInstance).count());
            assertEquals(1, count("inbox_message"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void forcedRlsAndTenantFirstForeignKeyRejectCrossTenantAccess() throws Exception {
        DomainEvent event = event(1);
        inApi(TENANT_ID, tx -> {
            store.append(tx, event, outbox());
            return null;
        });
        assertEquals(0, inApi(OTHER_TENANT_ID, tx -> tx.fetchCount(
            org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("domain_event")))).intValue());
        try (Connection connection = adminConnection();
             PreparedStatement insert = connection.prepareStatement("""
                 INSERT INTO outbox_event (
                   tenant_id,event_id,destination,payload_schema,payload)
                 VALUES (?,?,'events','event/1.0','{}'::jsonb)
                 """)) {
            insert.setObject(1, OTHER_TENANT_ID);
            insert.setObject(2, event.eventId());
            SQLException error = assertThrows(SQLException.class, insert::executeUpdate);
            assertEquals("23503", error.getSQLState());
        }
    }

    private boolean racedAppend(
            DomainEvent event, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = openApi(TENANT_ID)) {
            ready.countDown();
            if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("event race did not start");
            }
            try {
                runTransaction(connection, tx -> {
                    store.append(tx, event, outbox());
                    return null;
                });
                return true;
            } catch (org.jooq.exception.DataAccessException expectedConflict) {
                return false;
            }
        }
    }

    private InboxAcceptance racedInbox(CountDownLatch ready, CountDownLatch start)
            throws Exception {
        try (Connection connection = openWorker(TENANT_ID)) {
            ready.countDown();
            if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("inbox race did not start");
            }
            return runTransaction(connection, tx ->
                store.acceptInbox(tx, inbox("message-race", digest('c'))));
        }
    }

    private static DomainEvent event(long sequence) {
        return event(
            sequence,
            UUID.nameUUIDFromBytes(("event-" + sequence).getBytes(
                java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static DomainEvent event(long sequence, UUID eventId) {
        return new DomainEvent(
            TENANT_ID,
            eventId,
            "project",
            "project-1",
            "requirement",
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            sequence,
            "requirement.changed",
            "1.0.0",
            UUID.fromString("30000000-0000-0000-0000-000000000001"),
            UUID.fromString("40000000-0000-0000-0000-000000000001"),
            "user-1",
            "{\"value\":" + sequence + "}",
            OffsetDateTime.parse("2026-07-26T00:00:00Z"));
    }

    private static OutboxMessage outbox() {
        return new OutboxMessage("requirements", "requirement.event/1.0", "{\"kind\":\"changed\"}");
    }

    private static InboxMessage inbox(String messageId, String requestDigest) {
        return new InboxMessage(
            TENANT_ID,
            "git-provider",
            messageId,
            requestDigest,
            "git.reconcile",
            "git.signal/1.0",
            "{\"kind\":\"ref_changed\"}");
    }
}
