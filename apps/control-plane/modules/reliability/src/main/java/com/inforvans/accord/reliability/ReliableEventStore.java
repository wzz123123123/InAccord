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
            INSERT INTO domain_event (
              tenant_id,event_id,scope_type,scope_id,aggregate_type,aggregate_id,
              sequence,event_type,schema_version,causation_id,correlation_id,
              actor_id,payload,occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                      CAST(? AS timestamptz))
            """,
            event.tenantId(), event.eventId(), event.scopeType(), event.scopeId(),
            event.aggregateType(), event.aggregateId(), event.sequence(), event.eventType(),
            event.schemaVersion(), event.causationId(), event.correlationId(),
            event.actorId(), event.payload(), event.occurredAt());
        tx.execute("""
            INSERT INTO outbox_event (
              tenant_id,event_id,destination,payload_schema,payload
            ) VALUES (?, ?, ?, ?, CAST(? AS jsonb))
            """,
            event.tenantId(), event.eventId(), outbox.destination(),
            outbox.payloadSchema(), outbox.payload());
    }

    public InboxAcceptance acceptInbox(DSLContext tx, InboxMessage message) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(message, "message");
        int inserted = tx.execute("""
            INSERT INTO inbox_message (
              tenant_id,source,source_message_id,request_digest,handler_key,
              payload_schema,payload
            ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT DO NOTHING
            """,
            message.tenantId(), message.source(), message.sourceMessageId(),
            message.requestDigest(), message.handlerKey(), message.payloadSchema(),
            message.payload());
        if (inserted == 1) {
            return new InboxAcceptance.Accepted();
        }
        Record existing = tx.fetchOne("""
            SELECT request_digest,state
            FROM inbox_message
            WHERE tenant_id=? AND source=? AND source_message_id=?
            """, message.tenantId(), message.source(), message.sourceMessageId());
        if (existing == null) {
            throw new IllegalStateException("inbox conflict row disappeared");
        }
        String storedDigest = existing.get("request_digest", String.class);
        if (message.requestDigest().equals(storedDigest)) {
            return new InboxAcceptance.Duplicate(
                Objects.requireNonNull(existing.get("state", String.class), "state"));
        }
        return new InboxAcceptance.DigestConflict();
    }
}
