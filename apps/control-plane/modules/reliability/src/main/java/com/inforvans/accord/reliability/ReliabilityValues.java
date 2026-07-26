package com.inforvans.accord.reliability;

import com.inforvans.accord.platformkernel.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.nio.CharBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

final class ReliabilityValues {
    static final int MAX_JSON_BYTES = 1_048_576;
    static final Duration MAX_LEASE_STEP = Duration.ofMinutes(5);
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern SAFE_IDENTIFIER =
        Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$");
    private static final Pattern LOGICAL_KEY =
        Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$");
    private static final Pattern URI_PREFIX =
        Pattern.compile("^(?:https?|ssh|git|file|ftp):", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL =
        Pattern.compile("^[a-z][a-z0-9_.-]+$");
    private static final Pattern ERROR_CODE =
        Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");

    private ReliabilityValues() {}

    static String canonicalJson(String value, String name) {
        Objects.requireNonNull(value, name);
        byte[] canonical = CanonicalJson.canonicalize(encodeUtf8(value, name));
        if (canonical.length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException(name + " exceeds 1 MiB");
        }
        return new String(canonical, StandardCharsets.UTF_8);
    }

    static String digest(String value, String name) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be canonical SHA-256");
        }
        return value;
    }

    static String safeIdentifier(String value, String name) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a safe opaque identifier");
        }
        return value;
    }

    static String optionalSafeIdentifier(String value, String name) {
        return value == null ? null : safeIdentifier(value, name);
    }

    static String logicalKey(String value) {
        if (value == null || !LOGICAL_KEY.matcher(value).matches()
                || URI_PREFIX.matcher(value).find()) {
            throw new IllegalArgumentException(
                "logicalActionKey must be 16-128 approved ASCII characters");
        }
        return value;
    }

    static String globalIdempotencyKey(UUID tenantId, UUID intentId, String value) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        String expected = "accord:v1:" + tenantId + ":" + intentId;
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(
                "globalIdempotencyKey does not bind tenant and intent");
        }
        return value;
    }

    static String symbol(String value, String name, int maximum) {
        if (value == null || value.length() > maximum || !SYMBOL.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid symbolic value");
        }
        return value;
    }

    static String errorCode(String value) {
        if (value == null || !ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("errorCode is not normalized");
        }
        return value;
    }

    static long leaseMicros(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(MAX_LEASE_STEP) > 0) {
            throw new IllegalArgumentException(name + " must be positive and at most 5 minutes");
        }
        long nanos;
        try {
            nanos = value.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " is not representable", error);
        }
        if (nanos % 1_000 != 0) {
            throw new IllegalArgumentException(
                name + " must be representable in PostgreSQL microseconds");
        }
        return nanos / 1_000;
    }

    private static byte[] encodeUtf8(String value, String name) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(name + " is not valid Unicode", error);
        }
    }
}
