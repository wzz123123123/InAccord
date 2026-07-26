package com.inforvans.accord.reliability;

import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

public final class ReliableEventStore {
    public void append(DSLContext tx, DomainEvent event, OutboxMessage outbox) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(outbox, "outbox");
        tx.execute("""
            SELECT accord_security.append_reliable_event(
              ?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS timestamptz),
              ?,?,CAST(? AS jsonb))
            """,
            event.tenantId(), event.eventId(), event.scopeType(), event.scopeId(),
            event.aggregateType(), event.aggregateId(), event.sequence(), event.eventType(),
            event.schemaVersion(), event.causationId(), event.correlationId(),
            event.actorId(), event.payload(), event.occurredAt(), outbox.destination(),
            outbox.payloadSchema(), outbox.payload());
    }

    public InboxAcceptance acceptInbox(DSLContext tx, InboxMessage message) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(message, "message");
        Record result = tx.fetchOne("""
            SELECT disposition,stored_state
            FROM accord_security.accept_inbox_message(
              ?,?,?,?,?,?,CAST(? AS jsonb))
            """,
            message.tenantId(), message.source(), message.sourceMessageId(),
            message.requestDigest(), message.handlerKey(), message.payloadSchema(),
            message.payload());
        if (result == null) {
            throw new IllegalStateException("inbox acceptance returned no result");
        }
        return switch (Objects.requireNonNull(
            result.get("disposition", String.class), "disposition")) {
            case "ACCEPTED" -> new InboxAcceptance.Accepted();
            case "DUPLICATE" -> new InboxAcceptance.Duplicate(
                Objects.requireNonNull(result.get("stored_state", String.class), "storedState"));
            case "DIGEST_CONFLICT" -> new InboxAcceptance.DigestConflict();
            default -> throw new IllegalStateException("unknown inbox acceptance result");
        };
    }
}
