package com.inforvans.accord.controlplane.worker;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("accord.worker")
public record WorkerReliabilityProperties(
    Duration pollDelay,
    Duration tenantPermitLease,
    Duration messageLease,
    Duration transportTimeout,
    int batchSize,
    int concurrency,
    int maxAttempts,
    int cleanupBatchSize,
    Duration drainTimeout
) {
    private static final Duration MAX_DURATION = Duration.ofHours(1);

    public WorkerReliabilityProperties {
        pollDelay = requireDuration(pollDelay, "pollDelay");
        tenantPermitLease = requireDuration(tenantPermitLease, "tenantPermitLease");
        messageLease = requireDuration(messageLease, "messageLease");
        transportTimeout = requireDuration(transportTimeout, "transportTimeout");
        drainTimeout = requireDuration(drainTimeout, "drainTimeout");
        requireRange(batchSize, 1, 500, "batchSize");
        requireRange(concurrency, 1, 256, "concurrency");
        requireRange(maxAttempts, 1, 100, "maxAttempts");
        requireRange(cleanupBatchSize, 1, 500, "cleanupBatchSize");
        if (transportTimeout.compareTo(messageLease) >= 0) {
            throw new IllegalArgumentException(
                "transportTimeout must be shorter than messageLease");
        }
        if (drainTimeout.compareTo(tenantPermitLease) >= 0
                || drainTimeout.compareTo(messageLease) >= 0) {
            throw new IllegalArgumentException(
                "drainTimeout must be shorter than both lease durations");
        }
    }

    private static Duration requireDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException(name + " must be positive and at most PT1H");
        }
        long nanos;
        try {
            nanos = value.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " is too large", error);
        }
        if (nanos % 1_000 != 0) {
            throw new IllegalArgumentException(
                name + " must be representable in PostgreSQL microseconds");
        }
        return value;
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be between " + minimum + " and " + maximum);
        }
    }
}
