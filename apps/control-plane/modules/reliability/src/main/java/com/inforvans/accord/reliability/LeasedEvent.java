package com.inforvans.accord.reliability;

import java.util.Objects;
import java.util.UUID;

public record LeasedEvent(
    UUID tenantId,
    UUID eventId,
    String destination,
    String payloadSchema,
    String payload,
    int attemptCount,
    MessageFence fence
) {
    public LeasedEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventId, "eventId");
        destination = CommandKey.requireBounded(destination, "destination", 128);
        payloadSchema = CommandKey.requireBounded(payloadSchema, "payloadSchema", 255);
        payload = ReliabilityValues.canonicalJson(payload, "payload", payloadSchema);
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        Objects.requireNonNull(fence, "fence");
    }

    public String idempotencyKey() {
        return eventId.toString();
    }
}
