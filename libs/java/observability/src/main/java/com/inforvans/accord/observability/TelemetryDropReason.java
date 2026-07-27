package com.inforvans.accord.observability;

/** Closed, non-sensitive telemetry rejection taxonomy. */
public enum TelemetryDropReason {
    UNKNOWN_KEY,
    INVALID_TYPE,
    INVALID_VALUE,
    SENSITIVE_KEY,
    SENSITIVE_VALUE,
    RAW_URL,
    EXCEPTION_CONTENT,
    UNREGISTERED_NAME,
    CARDINALITY_POLICY,
    EXPORT_QUEUE_FULL,
    EXPORT_TIMEOUT;

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
