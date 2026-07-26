package com.inforvans.accord.webhookedge.webhook;

import com.inforvans.accord.webhookedge.binding.WebhookBinding;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VerifiedGitLabWebhook(
        WebhookBinding binding,
        UUID deliveryId,
        String eventType,
        String bodyDigest,
        Instant observedAt) {
    public VerifiedGitLabWebhook {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(bodyDigest, "bodyDigest");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
