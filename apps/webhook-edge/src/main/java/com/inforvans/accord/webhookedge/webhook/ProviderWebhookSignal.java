package com.inforvans.accord.webhookedge.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ProviderWebhookSignal(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("tenant_id") UUID tenantId,
        @JsonProperty("scope_type") String scopeType,
        @JsonProperty("scope_id") UUID scopeId,
        @JsonProperty("provider") String provider,
        @JsonProperty("immutable_repository_id") long immutableRepositoryId,
        @JsonProperty("delivery_id") UUID deliveryId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("body_digest") String bodyDigest,
        @JsonProperty("observed_at") String observedAt,
        @JsonProperty("ref") String ref,
        @JsonProperty("before_sha") String beforeSha,
        @JsonProperty("after_sha") String afterSha) {
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40,64}");

    public ProviderWebhookSignal {
        if (!"1.0.0".equals(schemaVersion)
                || !"repository".equals(scopeType)
                || !"gitlab".equals(provider)) {
            throw new IllegalArgumentException("unsupported provider webhook signal metadata");
        }
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        if (immutableRepositoryId <= 0) {
            throw new IllegalArgumentException("invalid immutable repository id");
        }
        if (eventType == null || eventType.isBlank() || eventType.length() > 128) {
            throw new IllegalArgumentException("invalid event type");
        }
        if (bodyDigest == null || !DIGEST.matcher(bodyDigest).matches()) {
            throw new IllegalArgumentException("invalid body digest");
        }
        if (observedAt == null || !Instant.parse(observedAt).toString().equals(observedAt)) {
            throw new IllegalArgumentException("invalid observed time");
        }
        if (ref != null && (ref.isEmpty() || ref.length() > 1_024 || containsControl(ref))) {
            throw new IllegalArgumentException("invalid ref");
        }
        requireSha(beforeSha);
        requireSha(afterSha);
    }

    private static void requireSha(String value) {
        if (value != null && !SHA.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid commit sha");
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }
}
