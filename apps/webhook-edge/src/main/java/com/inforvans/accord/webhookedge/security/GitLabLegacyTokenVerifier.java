package com.inforvans.accord.webhookedge.security;

import com.inforvans.accord.webhookedge.binding.WebhookBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class GitLabLegacyTokenVerifier {
    boolean verify(WebhookBinding binding, String presentedToken) {
        byte[] expected = binding.copyLegacyToken();
        if (expected == null || presentedToken == null) {
            return false;
        }
        byte[] presented = presentedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(sha256(expected), sha256(presented));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
