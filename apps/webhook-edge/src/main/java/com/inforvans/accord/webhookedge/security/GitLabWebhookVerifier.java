package com.inforvans.accord.webhookedge.security;

import com.inforvans.accord.webhookedge.binding.WebhookBinding;
import com.inforvans.accord.webhookedge.binding.WebhookVerificationMode;
import com.inforvans.accord.webhookedge.webhook.VerifiedGitLabWebhook;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class GitLabWebhookVerifier {
    private static final int MAX_EVENT_TYPE_LENGTH = 128;

    private final GitLabStandardWebhookVerifier standardVerifier;
    private final GitLabLegacyTokenVerifier legacyVerifier;
    private final Clock clock;

    public GitLabWebhookVerifier(Clock clock) {
        this(new GitLabStandardWebhookVerifier(clock), new GitLabLegacyTokenVerifier(), clock);
    }

    GitLabWebhookVerifier(
            GitLabStandardWebhookVerifier standardVerifier,
            GitLabLegacyTokenVerifier legacyVerifier,
            Clock clock) {
        this.standardVerifier = Objects.requireNonNull(standardVerifier, "standardVerifier");
        this.legacyVerifier = Objects.requireNonNull(legacyVerifier, "legacyVerifier");
        this.clock = Objects.requireNonNull(clock, "clock").withZone(ZoneOffset.UTC);
    }

    public VerifiedGitLabWebhook verify(WebhookBinding binding, GitLabWebhookHeaders headers, byte[] body) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        String rawWebhookId = headers.singleton(GitLabWebhookHeaders.WEBHOOK_ID);
        boolean signaturePresent = headers.isPresent(GitLabWebhookHeaders.WEBHOOK_SIGNATURE);
        boolean useStandard = signaturePresent
                || binding.verificationMode() == WebhookVerificationMode.STANDARD_REQUIRED;
        if (rawWebhookId == null
                || headers.hasUnsafeMultiplicityOrValue(GitLabWebhookHeaders.WEBHOOK_ID)
                || (useStandard && headers.hasUnsafeMultiplicityOrValue(
                        GitLabWebhookHeaders.WEBHOOK_TIMESTAMP,
                        GitLabWebhookHeaders.WEBHOOK_SIGNATURE))
                || (!useStandard && headers.hasUnsafeMultiplicityOrValue(GitLabWebhookHeaders.LEGACY_TOKEN))) {
            throw failure(Failure.AUTHENTICATION);
        }
        boolean authenticated;
        if (useStandard) {
            authenticated = standardVerifier.verify(
                    binding,
                    rawWebhookId,
                    headers.singleton(GitLabWebhookHeaders.WEBHOOK_TIMESTAMP),
                    headers.singleton(GitLabWebhookHeaders.WEBHOOK_SIGNATURE),
                    body);
        } else {
            authenticated = legacyVerifier.verify(
                    binding, headers.singleton(GitLabWebhookHeaders.LEGACY_TOKEN));
        }
        if (!authenticated) {
            throw failure(Failure.AUTHENTICATION);
        }
        if (headers.hasUnsafeMultiplicityOrValue(
                GitLabWebhookHeaders.IDEMPOTENCY_KEY,
                GitLabWebhookHeaders.EVENT_TYPE)
                || (useStandard && headers.hasUnsafeMultiplicityOrValue(GitLabWebhookHeaders.LEGACY_TOKEN))
                || (!useStandard && headers.hasUnsafeMultiplicityOrValue(GitLabWebhookHeaders.WEBHOOK_TIMESTAMP))) {
            throw failure(Failure.INVALID_REQUEST);
        }

        UUID deliveryId = canonicalUuid(rawWebhookId);
        String idempotencyKey = headers.singleton(GitLabWebhookHeaders.IDEMPOTENCY_KEY);
        if (deliveryId == null || idempotencyKey == null || !deliveryId.toString().equals(idempotencyKey)) {
            throw failure(Failure.INVALID_REQUEST);
        }
        String eventType = headers.singleton(GitLabWebhookHeaders.EVENT_TYPE);
        if (eventType == null
                || eventType.isBlank()
                || eventType.length() > MAX_EVENT_TYPE_LENGTH
                || !eventType.chars().allMatch(value -> value >= 0x20 && value <= 0x7e)) {
            throw failure(Failure.INVALID_REQUEST);
        }

        return new VerifiedGitLabWebhook(
                binding,
                deliveryId,
                eventType,
                sha256(body),
                clock.instant());
    }

    private static UUID canonicalUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String sha256(byte[] body) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static VerificationException failure(Failure failure) {
        return new VerificationException(failure);
    }

    public enum Failure {
        AUTHENTICATION,
        INVALID_REQUEST
    }

    public static final class VerificationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Failure failure;

        private VerificationException(Failure failure) {
            super("webhook verification failed");
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }
}
