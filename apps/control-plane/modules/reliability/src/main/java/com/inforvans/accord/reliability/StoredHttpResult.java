package com.inforvans.accord.reliability;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record StoredHttpResult(int status, Map<String, String> headers, String body) {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final Pattern HEADER_NAME =
        Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
        "authentication-info",
        "authorization",
        "connection",
        "cookie",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authentication-info",
        "proxy-authorization",
        "set-cookie",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "www-authenticate"
    );

    public StoredHttpResult {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be between 100 and 599");
        }
        headers = canonicalHeaders(headers);
        Objects.requireNonNull(body, "body");
        if (body.indexOf(0) >= 0) {
            throw new IllegalArgumentException("response body contains U+0000");
        }
        int encodedBytes = strictUtf8Bytes(body, "response body is not valid UTF-8");
        if (encodedBytes > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("response body exceeds 1 MiB");
        }
    }

    private static Map<String, String> canonicalHeaders(Map<String, String> source) {
        Objects.requireNonNull(source, "headers");
        Map<String, String> canonical = new TreeMap<>();
        source.forEach((name, value) -> {
            if (name == null || !HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid HTTP header name");
            }
            if (value == null || containsInvalidValueCharacter(value)) {
                throw new IllegalArgumentException("invalid HTTP header value");
            }
            strictUtf8Bytes(value, "invalid HTTP header value");
            String normalized = name.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_HEADERS.contains(normalized)) {
                throw new IllegalArgumentException(
                    "hop-by-hop and credential-bearing headers cannot be replayed");
            }
            if (canonical.putIfAbsent(normalized, value) != null) {
                throw new IllegalArgumentException(
                    "duplicate case-insensitive HTTP header name");
            }
        });
        return Collections.unmodifiableMap(canonical);
    }

    private static int strictUtf8Bytes(String value, String message) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return encoder.encode(CharBuffer.wrap(value)).remaining();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(message, error);
        }
    }

    private static boolean containsInvalidValueCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < 0x20 && character != '\t') || character == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
