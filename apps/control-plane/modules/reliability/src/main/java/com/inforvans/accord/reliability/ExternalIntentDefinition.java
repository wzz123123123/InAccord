package com.inforvans.accord.reliability;

import java.util.Objects;
import java.util.UUID;

public record ExternalIntentDefinition(
    UUID tenantId,
    UUID intentId,
    String logicalActionKey,
    String scopeType,
    String scopeId,
    String provider,
    String providerInstallationId,
    String providerRepositoryId,
    String operation,
    String requestReferenceType,
    String requestReferenceId,
    long requestReferenceVersion,
    String requestDigest
) {
    public ExternalIntentDefinition {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        logicalActionKey = ReliabilityValues.logicalKey(logicalActionKey);
        if (!java.util.Set.of("tenant", "project", "repository").contains(scopeType)) {
            throw new IllegalArgumentException("scopeType is unknown");
        }
        scopeId = ReliabilityValues.safeIdentifier(scopeId, "scopeId");
        provider = ReliabilityValues.symbol(provider, "provider", 64);
        providerInstallationId = ReliabilityValues.safeIdentifier(
            providerInstallationId, "providerInstallationId");
        providerRepositoryId = ReliabilityValues.optionalSafeIdentifier(
            providerRepositoryId, "providerRepositoryId");
        if (("repository".equals(scopeType)) != (providerRepositoryId != null)) {
            throw new IllegalArgumentException(
                "providerRepositoryId must exist exactly for repository scope");
        }
        operation = ReliabilityValues.symbol(operation, "operation", 128);
        requestReferenceType = ReliabilityValues.symbol(
            requestReferenceType, "requestReferenceType", 64);
        requestReferenceId = ReliabilityValues.safeIdentifier(
            requestReferenceId, "requestReferenceId");
        if (requestReferenceVersion < 1) {
            throw new IllegalArgumentException("requestReferenceVersion must be positive");
        }
        requestDigest = ReliabilityValues.digest(requestDigest, "requestDigest");
    }
}
