package com.inforvans.accord.observability;

import java.util.Locale;

/** Bounded operation labels shared by traces, metrics, and structured logs. */
public enum TelemetryOperation {
    HTTP_REQUEST(false),
    CONTRACT_VALIDATION(false),
    DOMAIN_EVENT(false),
    RELIABILITY_DELIVERY(false),
    INBOX_HANDLING(false),
    IDEMPOTENCY_CLEANUP(false),
    PROVIDER_WEBHOOK_RECEIVE(true),
    PROVIDER_RECONCILIATION(true),
    PROVIDER_DELIVERY(true);

    private final boolean providerContextRequired;
    private final String wireName;

    TelemetryOperation(boolean providerContextRequired) {
        this.providerContextRequired = providerContextRequired;
        this.wireName = name().toLowerCase(Locale.ROOT);
    }

    public boolean providerContextRequired() {
        return providerContextRequired;
    }

    public String wireName() {
        return wireName;
    }

    public static TelemetryOperation fromWireName(String candidate) {
        if (candidate != null) {
            for (TelemetryOperation operation : values()) {
                if (operation.wireName.equals(candidate)) {
                    return operation;
                }
            }
        }
        throw new IllegalArgumentException("unregistered telemetry operation");
    }

    static boolean isRegistered(String candidate) {
        try {
            fromWireName(candidate);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
