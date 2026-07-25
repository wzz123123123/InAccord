package com.inforvans.accord.reliability;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record CommandKey(
    UUID tenantId,
    String actorId,
    String routeKey,
    String idempotencyKey
) {
    private static final Pattern IDEMPOTENCY_KEY =
        Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");

    public CommandKey {
        Objects.requireNonNull(tenantId, "tenantId");
        actorId = requireBounded(actorId, "actorId", 255);
        routeKey = requireBounded(routeKey, "routeKey", 128);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                "idempotencyKey must be 16-128 approved ASCII characters");
        }
    }

    static String requireBounded(String value, String name, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        if (value.indexOf(0) >= 0) {
            throw new IllegalArgumentException(name + " contains U+0000");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
        }
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            encoder.encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(name + " is not valid UTF-8", error);
        }
        if (value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
