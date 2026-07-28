package com.inforvans.accord.reliability;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ExternalIntentSnapshot(
    UUID tenantId,
    UUID intentId,
    UUID rootIntentId,
    UUID predecessorIntentId,
    int attemptOrdinal,
    String scopeType,
    String scopeId,
    String logicalActionKey,
    String globalIdempotencyKey,
    ExternalIntentState state,
    long executionGeneration,
    long reconciliationGeneration,
    String providerRequestId,
    String outcomeDigest,
    String lastErrorCode,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime terminalAt
) {
    public ExternalIntentSnapshot {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(rootIntentId, "rootIntentId");
        if (attemptOrdinal < 1 || executionGeneration < 0 || reconciliationGeneration < 0) {
            throw new IllegalArgumentException("persisted intent counters are invalid");
        }
        ReliabilityValues.logicalKey(logicalActionKey);
        if (!java.util.Set.of("tenant", "project", "repository").contains(scopeType)) {
            throw new IllegalArgumentException("scopeType is unknown");
        }
        scopeId = ReliabilityValues.safeIdentifier(scopeId, "scopeId");
        ReliabilityValues.globalIdempotencyKey(tenantId, intentId, globalIdempotencyKey);
        Objects.requireNonNull(state, "state");
        providerRequestId = ReliabilityValues.optionalSafeIdentifier(
            providerRequestId, "providerRequestId");
        if (outcomeDigest != null) {
            ReliabilityValues.digest(outcomeDigest, "outcomeDigest");
        }
        if (lastErrorCode != null) {
            ReliabilityValues.errorCode(lastErrorCode);
        }
        if (executionGeneration > 1
                || (state == ExternalIntentState.RECORDED && executionGeneration != 0)
                || (state != ExternalIntentState.RECORDED && executionGeneration != 1)) {
            throw new IllegalArgumentException("execution generation does not match state");
        }
        boolean terminal = java.util.Set.of(
            ExternalIntentState.SUCCEEDED,
            ExternalIntentState.CONFIRMED_NO_EFFECT,
            ExternalIntentState.DIVERGED).contains(state);
        if (terminal != (terminalAt != null) || terminal != (outcomeDigest != null)) {
            throw new IllegalArgumentException("terminal fields do not match state");
        }
        boolean requiresError = state == ExternalIntentState.OUTCOME_UNKNOWN
            || state == ExternalIntentState.DIVERGED;
        if (requiresError != (lastErrorCode != null)) {
            throw new IllegalArgumentException("error code does not match state");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
