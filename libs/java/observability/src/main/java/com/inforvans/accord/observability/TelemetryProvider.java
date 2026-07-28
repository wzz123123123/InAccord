package com.inforvans.accord.observability;

import java.util.Locale;

/** Closed product-provider dimension. */
public enum TelemetryProvider {
    NOT_APPLICABLE,
    GITLAB,
    GITHUB;

    private final String wireName = name().toLowerCase(Locale.ROOT);

    public String wireName() {
        return wireName;
    }

    public static TelemetryProvider fromWireName(String candidate) {
        if (candidate != null) {
            for (TelemetryProvider provider : values()) {
                if (provider.wireName.equals(candidate)) {
                    return provider;
                }
            }
        }
        throw new IllegalArgumentException("unregistered telemetry provider");
    }
}
