package com.inforvans.accord.observability;

import java.util.Locale;

/** Stable result labels; exception text is intentionally not representable. */
public enum TelemetryResultCode {
    SUCCESS,
    ACCEPTED,
    REJECTED,
    NOT_FOUND,
    CONFLICT,
    RETRY_REQUIRED,
    TIMEOUT,
    UNAVAILABLE,
    FAILURE;

    private final String wireName = name().toLowerCase(Locale.ROOT);

    public String wireName() {
        return wireName;
    }

    public static TelemetryResultCode fromWireName(String candidate) {
        if (candidate != null) {
            for (TelemetryResultCode resultCode : values()) {
                if (resultCode.wireName.equals(candidate)) {
                    return resultCode;
                }
            }
        }
        throw new IllegalArgumentException("unregistered telemetry result code");
    }
}
