package com.inforvans.accord.webhookedge.security;

import com.inforvans.accord.webhookedge.binding.WebhookBinding;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class GitLabStandardWebhookVerifier {
    private static final long ALLOWED_SKEW_SECONDS = 300;
    private static final int MAX_SIGNATURE_CANDIDATES = 8;
    private static final int HMAC_SHA256_BYTES = 32;
    private static final Pattern CANONICAL_SECONDS = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern VERSION = Pattern.compile("v[0-9]+");

    private final Clock clock;
    private final CandidateComparator comparator;

    public GitLabStandardWebhookVerifier(Clock clock) {
        this(clock, MessageDigest::isEqual);
    }

    GitLabStandardWebhookVerifier(Clock clock, CandidateComparator comparator) {
        this.clock = Objects.requireNonNull(clock, "clock").withZone(ZoneOffset.UTC);
        this.comparator = Objects.requireNonNull(comparator, "comparator");
    }

    boolean verify(
            WebhookBinding binding,
            String webhookId,
            String timestampValue,
            String signatureValue,
            byte[] body) {
        byte[] secret = binding.copySigningSecret();
        if (secret == null || !timestampInWindow(timestampValue)) {
            return false;
        }
        List<SignatureCandidate> candidates = parseCandidates(signatureValue);
        if (candidates == null) {
            return false;
        }
        byte[] expected = hmac(secret, webhookId, timestampValue, body);
        if (expected == null) {
            return false;
        }

        boolean matched = false;
        for (SignatureCandidate candidate : candidates) {
            boolean authorizedVersion = candidate.version().equals("v1");
            boolean signatureMatched = comparator.matches(expected, candidate.signature());
            matched |= authorizedVersion & signatureMatched;
        }
        return matched;
    }

    private boolean timestampInWindow(String value) {
        if (value == null || !CANONICAL_SECONDS.matcher(value).matches()) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(value);
            long now = clock.instant().getEpochSecond();
            return timestamp >= now - ALLOWED_SKEW_SECONDS && timestamp <= now + ALLOWED_SKEW_SECONDS;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static List<SignatureCandidate> parseCandidates(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] encodedCandidates = value.split(" ", -1);
        if (encodedCandidates.length > MAX_SIGNATURE_CANDIDATES) {
            return null;
        }
        List<SignatureCandidate> candidates = new ArrayList<>(encodedCandidates.length);
        try {
            for (String encodedCandidate : encodedCandidates) {
                int comma = encodedCandidate.indexOf(',');
                if (comma <= 0 || comma != encodedCandidate.lastIndexOf(',') || comma == encodedCandidate.length() - 1) {
                    return null;
                }
                String version = encodedCandidate.substring(0, comma);
                String encoded = encodedCandidate.substring(comma + 1);
                if (!VERSION.matcher(version).matches()) {
                    return null;
                }
                byte[] signature = Base64.getDecoder().decode(encoded);
                if (!Base64.getEncoder().encodeToString(signature).equals(encoded)
                        || signature.length != HMAC_SHA256_BYTES) {
                    return null;
                }
                candidates.add(new SignatureCandidate(version, signature));
            }
            return candidates;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static byte[] hmac(byte[] secret, String webhookId, String timestamp, byte[] body) {
        if (webhookId == null || timestamp == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(webhookId.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (GeneralSecurityException exception) {
            return null;
        }
    }

    @FunctionalInterface
    interface CandidateComparator {
        boolean matches(byte[] expected, byte[] candidate);
    }

    private record SignatureCandidate(String version, byte[] signature) {
        private SignatureCandidate {
            signature = signature.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }
}
