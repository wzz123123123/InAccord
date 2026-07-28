package com.inforvans.accord.reliability;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ReconciliationLease(
    UUID tenantId,
    UUID intentId,
    String owner,
    long generation,
    UUID token,
    OffsetDateTime leaseUntil,
    String globalIdempotencyKey,
    String provider,
    String providerInstallationId,
    String providerRepositoryId,
    String operation,
    String requestReferenceType,
    String requestReferenceId,
    long requestReferenceVersion,
    String requestDigest,
    String providerRequestId
) {
    public ReconciliationLease {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        owner = CommandKey.requireBounded(owner, "owner", 255);
        if (generation < 1) {
            throw new IllegalArgumentException("reconciliation generation must be positive");
        }
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        ReliabilityValues.globalIdempotencyKey(tenantId, intentId, globalIdempotencyKey);
        ReliabilityValues.symbol(provider, "provider", 64);
        ReliabilityValues.safeIdentifier(providerInstallationId, "providerInstallationId");
        ReliabilityValues.optionalSafeIdentifier(providerRepositoryId, "providerRepositoryId");
        ReliabilityValues.symbol(operation, "operation", 128);
        ReliabilityValues.symbol(requestReferenceType, "requestReferenceType", 64);
        ReliabilityValues.safeIdentifier(requestReferenceId, "requestReferenceId");
        if (requestReferenceVersion < 1) {
            throw new IllegalArgumentException("requestReferenceVersion must be positive");
        }
        ReliabilityValues.digest(requestDigest, "requestDigest");
        providerRequestId = ReliabilityValues.optionalSafeIdentifier(
            providerRequestId, "providerRequestId");
    }
}
