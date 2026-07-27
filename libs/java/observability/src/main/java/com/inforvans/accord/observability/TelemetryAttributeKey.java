package com.inforvans.accord.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import java.util.EnumSet;
import java.util.Optional;

/** The complete key, scalar-type, signal, and cardinality policy for exported attributes. */
public enum TelemetryAttributeKey {
    TENANT_ID("accord.tenant.id", ValueKind.STRING, 36, signals(TelemetryGuardMetrics.Signal.SPAN)),
    SCOPE_TYPE("accord.scope.type", ValueKind.STRING, 32, signals(TelemetryGuardMetrics.Signal.SPAN)),
    SCOPE_ID("accord.scope.id", ValueKind.STRING, 128, signals(TelemetryGuardMetrics.Signal.SPAN)),
    CORRELATION_ID(
            "accord.correlation.id",
            ValueKind.STRING,
            36,
            signals(TelemetryGuardMetrics.Signal.SPAN)),
    CAUSATION_ID(
            "accord.causation.id",
            ValueKind.STRING,
            36,
            signals(TelemetryGuardMetrics.Signal.SPAN)),
    OPERATION("accord.operation", ValueKind.STRING, 64, allSignals()),
    PROVIDER("accord.provider", ValueKind.STRING, 32, allSignals()),
    RESULT_CODE("accord.result_code", ValueKind.STRING, 32, allSignals()),
    AGGREGATE_TYPE(
            "accord.aggregate.type",
            ValueKind.STRING,
            64,
            signals(TelemetryGuardMetrics.Signal.SPAN, TelemetryGuardMetrics.Signal.LOG)),
    EVENT_TYPE(
            "accord.event.type",
            ValueKind.STRING,
            64,
            signals(TelemetryGuardMetrics.Signal.SPAN, TelemetryGuardMetrics.Signal.LOG)),
    HTTP_METHOD(
            "http.request.method",
            ValueKind.STRING,
            16,
            signals(TelemetryGuardMetrics.Signal.SPAN)),
    HTTP_ROUTE(
            "http.route",
            ValueKind.STRING,
            255,
            signals(TelemetryGuardMetrics.Signal.SPAN)),
    HTTP_STATUS(
            "http.response.status_code",
            ValueKind.LONG,
            0,
            signals(TelemetryGuardMetrics.Signal.SPAN)),
    SERVICE_NAME("service.name", ValueKind.STRING, 64, allSignals()),
    SERVICE_NAMESPACE("service.namespace", ValueKind.STRING, 64, allSignals()),
    SERVICE_VERSION("service.version", ValueKind.STRING, 64, allSignals()),
    DEPLOYMENT_ENVIRONMENT(
            "deployment.environment.name", ValueKind.STRING, 64, allSignals()),
    TELEMETRY_SDK_NAME("telemetry.sdk.name", ValueKind.STRING, 32, allSignals()),
    TELEMETRY_SDK_LANGUAGE("telemetry.sdk.language", ValueKind.STRING, 16, allSignals()),
    TELEMETRY_SDK_VERSION("telemetry.sdk.version", ValueKind.STRING, 32, allSignals()),
    DROP_SIGNAL(
            "accord.telemetry.signal",
            ValueKind.STRING,
            16,
            signals(TelemetryGuardMetrics.Signal.METRIC)),
    DROP_REASON(
            "accord.telemetry.reason",
            ValueKind.STRING,
            32,
            signals(TelemetryGuardMetrics.Signal.METRIC));

    private final AttributeKey<?> otelKey;
    private final ValueKind valueKind;
    private final int maximumCodePoints;
    private final EnumSet<TelemetryGuardMetrics.Signal> allowedSignals;

    TelemetryAttributeKey(
            String key,
            ValueKind valueKind,
            int maximumCodePoints,
            EnumSet<TelemetryGuardMetrics.Signal> allowedSignals) {
        this.valueKind = valueKind;
        this.maximumCodePoints = maximumCodePoints;
        this.allowedSignals = allowedSignals.clone();
        this.otelKey = switch (valueKind) {
            case STRING -> AttributeKey.stringKey(key);
            case LONG -> AttributeKey.longKey(key);
        };
    }

    static Optional<TelemetryAttributeKey> find(String candidate) {
        if (candidate != null) {
            for (TelemetryAttributeKey key : values()) {
                if (key.otelKey.getKey().equals(candidate)) {
                    return Optional.of(key);
                }
            }
        }
        return Optional.empty();
    }

    boolean allows(TelemetryGuardMetrics.Signal signal) {
        return allowedSignals.contains(signal);
    }

    boolean hasExpectedType(AttributeKey<?> actualKey, Object value) {
        if (actualKey == null || value == null || actualKey.getType() != otelKey.getType()) {
            return false;
        }
        return switch (valueKind) {
            case STRING -> actualKey.getType() == AttributeType.STRING && value instanceof String;
            case LONG -> actualKey.getType() == AttributeType.LONG && value instanceof Long;
        };
    }

    int maximumCodePoints() {
        return maximumCodePoints;
    }

    AttributeKey<?> otelKey() {
        return otelKey;
    }

    @SuppressWarnings("unchecked")
    AttributeKey<String> stringKey() {
        if (valueKind != ValueKind.STRING) {
            throw new IllegalStateException("telemetry key is not a string");
        }
        return (AttributeKey<String>) otelKey;
    }

    @SuppressWarnings("unchecked")
    AttributeKey<Long> longKey() {
        if (valueKind != ValueKind.LONG) {
            throw new IllegalStateException("telemetry key is not a long");
        }
        return (AttributeKey<Long>) otelKey;
    }

    private static EnumSet<TelemetryGuardMetrics.Signal> signals(
            TelemetryGuardMetrics.Signal first,
            TelemetryGuardMetrics.Signal... remaining) {
        return EnumSet.of(first, remaining);
    }

    private static EnumSet<TelemetryGuardMetrics.Signal> allSignals() {
        return EnumSet.allOf(TelemetryGuardMetrics.Signal.class);
    }

    enum ValueKind {
        STRING,
        LONG
    }
}
