package com.inforvans.accord.observability;

import io.opentelemetry.api.common.Attributes;
import java.util.Locale;
import java.util.Objects;

/** Closed attribute variants available to Accord business code. */
public sealed interface TelemetryAttributes
        permits TelemetryAttributes.Http,
                TelemetryAttributes.Workflow,
                TelemetryAttributes.DomainEvent,
                TelemetryAttributes.Metric {
    default Attributes toOtelAttributes() {
        return TelemetryRecordGuard.validatedAttributes(this);
    }

    record Http(
            TelemetryIdentifiers identifiers,
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode,
            HttpMethod method,
            RouteTemplate route,
            HttpStatus status) implements TelemetryAttributes {
        public Http {
            requireCommon(identifiers, operation, provider, resultCode);
            Objects.requireNonNull(method, "telemetry HTTP method must not be null");
            Objects.requireNonNull(route, "telemetry route must not be null");
            Objects.requireNonNull(status, "telemetry status must not be null");
        }
    }

    record Workflow(
            TelemetryIdentifiers identifiers,
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode) implements TelemetryAttributes {
        public Workflow {
            requireCommon(identifiers, operation, provider, resultCode);
        }
    }

    record DomainEvent(
            TelemetryIdentifiers identifiers,
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode,
            AggregateType aggregateType,
            EventType eventType) implements TelemetryAttributes {
        public DomainEvent {
            requireCommon(identifiers, operation, provider, resultCode);
            Objects.requireNonNull(aggregateType, "telemetry aggregate type must not be null");
            Objects.requireNonNull(eventType, "telemetry event type must not be null");
            if (operation != TelemetryOperation.DOMAIN_EVENT) {
                throw new IllegalArgumentException("invalid telemetry operation variant");
            }
        }
    }

    record Metric(
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode) implements TelemetryAttributes {
        public Metric {
            requireProviderPair(operation, provider);
            Objects.requireNonNull(resultCode, "telemetry result code must not be null");
        }
    }

    private static void requireCommon(
            TelemetryIdentifiers identifiers,
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode) {
        Objects.requireNonNull(identifiers, "telemetry identifiers must not be null");
        requireProviderPair(operation, provider);
        Objects.requireNonNull(resultCode, "telemetry result code must not be null");
    }

    private static void requireProviderPair(
            TelemetryOperation operation, TelemetryProvider provider) {
        Objects.requireNonNull(operation, "telemetry operation must not be null");
        Objects.requireNonNull(provider, "telemetry provider must not be null");
        if (operation.providerContextRequired()
                == (provider == TelemetryProvider.NOT_APPLICABLE)) {
            throw new IllegalArgumentException("invalid telemetry provider context");
        }
    }

    enum HttpMethod {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE,
        HEAD,
        OPTIONS;

        String wireName() {
            return name();
        }

        static HttpMethod fromWireName(String candidate) {
            if (candidate != null) {
                for (HttpMethod method : values()) {
                    if (method.wireName().equals(candidate)) {
                        return method;
                    }
                }
            }
            throw new IllegalArgumentException("unregistered telemetry HTTP method");
        }
    }

    record RouteTemplate(String value) {
        public RouteTemplate {
            if (!isRegistered(value)) {
                throw new IllegalArgumentException("unregistered telemetry route");
            }
        }

        static boolean isRegistered(String candidate) {
            return switch (candidate) {
                case "/v1/contract-validations/{validationId}",
                        "/webhooks/gitlab/{bindingId}",
                        "/actuator/health",
                        "/actuator/prometheus" -> true;
                case null, default -> false;
            };
        }

        static boolean isRegisteredSpanName(String candidate) {
            if (isRegistered(candidate)) {
                return true;
            }
            for (HttpMethod method : HttpMethod.values()) {
                String prefix = method.wireName() + " ";
                if (candidate != null
                        && candidate.startsWith(prefix)
                        && isRegistered(candidate.substring(prefix.length()))) {
                    return true;
                }
            }
            return false;
        }
    }

    record HttpStatus(int value) {
        public HttpStatus {
            if (value < 100 || value > 599) {
                throw new IllegalArgumentException("invalid telemetry HTTP status");
            }
        }
    }

    enum AggregateType {
        CONTRACT_VALIDATION;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static AggregateType fromWireName(String candidate) {
            if (CONTRACT_VALIDATION.wireName().equals(candidate)) {
                return CONTRACT_VALIDATION;
            }
            throw new IllegalArgumentException("unregistered telemetry aggregate type");
        }
    }

    enum EventType {
        CONTRACT_VALIDATION_COMPLETED;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static EventType fromWireName(String candidate) {
            if (CONTRACT_VALIDATION_COMPLETED.wireName().equals(candidate)) {
                return CONTRACT_VALIDATION_COMPLETED;
            }
            throw new IllegalArgumentException("unregistered telemetry event type");
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
}
