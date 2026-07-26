package com.inforvans.accord.reliability;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record DomainEvent(
    UUID tenantId,
    UUID eventId,
    String scopeType,
    String scopeId,
    String aggregateType,
    UUID aggregateId,
    long sequence,
    String eventType,
    String schemaVersion,
    UUID causationId,
    UUID correlationId,
    String actorId,
    String payload,
    OffsetDateTime occurredAt
) {
    public DomainEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventId, "eventId");
        if (!java.util.Set.of("tenant", "project", "repository").contains(scopeType)) {
            throw new IllegalArgumentException("scopeType is unknown");
        }
        scopeId = CommandKey.requireBounded(scopeId, "scopeId", 255);
        aggregateType = ReliabilityValues.symbol(aggregateType, "aggregateType", 64);
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        eventType = ReliabilityValues.symbol(eventType, "eventType", 128);
        if (schemaVersion == null || !schemaVersion.matches("^[1-9][0-9]*[.][0-9]+[.][0-9]+$")) {
            throw new IllegalArgumentException("schemaVersion is invalid");
        }
        Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(correlationId, "correlationId");
        actorId = CommandKey.requireBounded(actorId, "actorId", 255);
        payload = ReliabilityValues.canonicalJson(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
