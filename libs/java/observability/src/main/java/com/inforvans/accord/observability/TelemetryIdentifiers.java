package com.inforvans.accord.observability;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Typed trace identifiers. This type is structurally unavailable to metric attributes. */
public record TelemetryIdentifiers(
        Optional<TenantId> tenantId,
        Optional<Scope> scope,
        Optional<CorrelationId> correlationId,
        Optional<CausationId> causationId) {
    private static final Pattern BOUNDED_IDENTIFIER =
            Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");

    public TelemetryIdentifiers {
        tenantId = requireOptional(tenantId);
        scope = requireOptional(scope);
        correlationId = requireOptional(correlationId);
        causationId = requireOptional(causationId);
    }

    public static TelemetryIdentifiers none() {
        return new TelemetryIdentifiers(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static <T> Optional<T> requireOptional(Optional<T> value) {
        return Objects.requireNonNull(value, "telemetry optional must not be null");
    }

    private static String canonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            throw new IllegalArgumentException("invalid telemetry identifier");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)
                    || !value.equals(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("invalid telemetry identifier");
            }
            return value;
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("invalid telemetry identifier");
        }
    }

    public record TenantId(String value) {
        public TenantId {
            value = canonicalUuid(value);
        }
    }

    public record CorrelationId(String value) {
        public CorrelationId {
            value = canonicalUuid(value);
        }
    }

    public record CausationId(String value) {
        public CausationId {
            value = canonicalUuid(value);
        }
    }

    public record Scope(ScopeType type, ScopeId id) {
        public Scope {
            Objects.requireNonNull(type, "telemetry scope type must not be null");
            Objects.requireNonNull(id, "telemetry scope id must not be null");
        }
    }

    public record ScopeId(String value) {
        public ScopeId {
            if (value == null || !BOUNDED_IDENTIFIER.matcher(value).matches()) {
                throw new IllegalArgumentException("invalid telemetry identifier");
            }
        }
    }

    public enum ScopeType {
        ORGANIZATION,
        PROJECT,
        REQUIREMENT,
        BATCH,
        WORK_ITEM;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static ScopeType fromWireName(String candidate) {
            if (candidate != null) {
                for (ScopeType scopeType : values()) {
                    if (scopeType.wireName().equals(candidate)) {
                        return scopeType;
                    }
                }
            }
            throw new IllegalArgumentException("invalid telemetry identifier");
        }
    }
}
