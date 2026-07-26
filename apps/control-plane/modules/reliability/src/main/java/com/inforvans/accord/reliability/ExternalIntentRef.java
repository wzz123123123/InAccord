package com.inforvans.accord.reliability;

import java.util.Objects;
import java.util.UUID;

public record ExternalIntentRef(
    UUID tenantId,
    UUID intentId,
    UUID rootIntentId,
    int attemptOrdinal,
    String globalIdempotencyKey,
    ExternalIntentState state
) {
    public ExternalIntentRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(rootIntentId, "rootIntentId");
        if (attemptOrdinal < 1) {
            throw new IllegalArgumentException("attemptOrdinal must be positive");
        }
        ReliabilityValues.globalIdempotencyKey(tenantId, intentId, globalIdempotencyKey);
        Objects.requireNonNull(state, "state");
    }
}
