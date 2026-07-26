package com.inforvans.accord.webhookedge.binding;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public final class WebhookBinding {
    private static final String SIGNING_TOKEN_PREFIX = "whsec_";
    private static final int MIN_SIGNING_SECRET_BYTES = 24;
    private static final int MAX_SIGNING_SECRET_BYTES = 64;
    private static final int MAX_LEGACY_TOKEN_BYTES = 256;

    private final UUID bindingId;
    private final UUID tenantId;
    private final long immutableRepositoryId;
    private final WebhookVerificationMode verificationMode;
    private final byte[] signingSecret;
    private final byte[] legacyToken;

    public WebhookBinding(
            UUID bindingId,
            UUID tenantId,
            long immutableRepositoryId,
            WebhookVerificationMode verificationMode,
            String signingToken,
            String legacyToken) {
        this.bindingId = Objects.requireNonNull(bindingId, "bindingId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        if (immutableRepositoryId <= 0) {
            throw new IllegalArgumentException("invalid immutable repository id");
        }
        this.immutableRepositoryId = immutableRepositoryId;
        this.verificationMode = Objects.requireNonNull(verificationMode, "verificationMode");
        this.signingSecret = decodeSigningToken(signingToken);
        this.legacyToken = decodeLegacyToken(legacyToken);

        if (verificationMode == WebhookVerificationMode.STANDARD_REQUIRED && this.signingSecret == null) {
            throw new IllegalArgumentException("standard signing token required");
        }
        if (verificationMode == WebhookVerificationMode.LEGACY_ALLOWED && this.legacyToken == null) {
            throw new IllegalArgumentException("legacy token required");
        }
    }

    public UUID bindingId() {
        return bindingId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public long immutableRepositoryId() {
        return immutableRepositoryId;
    }

    public WebhookVerificationMode verificationMode() {
        return verificationMode;
    }

    public byte[] copySigningSecret() {
        return signingSecret == null ? null : signingSecret.clone();
    }

    public byte[] copyLegacyToken() {
        return legacyToken == null ? null : legacyToken.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WebhookBinding that)) {
            return false;
        }
        return immutableRepositoryId == that.immutableRepositoryId
                && bindingId.equals(that.bindingId)
                && tenantId.equals(that.tenantId)
                && verificationMode == that.verificationMode
                && Arrays.equals(signingSecret, that.signingSecret)
                && Arrays.equals(legacyToken, that.legacyToken);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(bindingId, tenantId, immutableRepositoryId, verificationMode);
        result = 31 * result + Arrays.hashCode(signingSecret);
        return 31 * result + Arrays.hashCode(legacyToken);
    }

    @Override
    public String toString() {
        return "WebhookBinding[bindingId=" + bindingId
                + ", tenantId=" + tenantId
                + ", immutableRepositoryId=" + immutableRepositoryId
                + ", verificationMode=" + verificationMode
                + ", credentials=REDACTED]";
    }

    private static byte[] decodeSigningToken(String token) {
        if (token == null) {
            return null;
        }
        if (!token.startsWith(SIGNING_TOKEN_PREFIX)) {
            throw new IllegalArgumentException("invalid signing token");
        }
        try {
            String encoded = token.substring(SIGNING_TOKEN_PREFIX.length());
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length < MIN_SIGNING_SECRET_BYTES
                    || decoded.length > MAX_SIGNING_SECRET_BYTES
                    || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
                throw new IllegalArgumentException("invalid signing token");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid signing token");
        }
    }

    private static byte[] decodeLegacyToken(String token) {
        if (token == null) {
            return null;
        }
        byte[] encoded = token.getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 1
                || encoded.length > MAX_LEGACY_TOKEN_BYTES
                || token.indexOf('\r') >= 0
                || token.indexOf('\n') >= 0
                || token.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid legacy token");
        }
        return encoded;
    }
}
