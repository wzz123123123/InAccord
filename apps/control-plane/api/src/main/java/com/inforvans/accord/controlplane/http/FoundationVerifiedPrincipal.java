package com.inforvans.accord.controlplane.http;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public sealed interface FoundationVerifiedPrincipal extends Principal
        permits FoundationVerifiedPrincipal.Bearer,
                FoundationVerifiedPrincipal.BrowserSession {
    int MAX_IDENTITY_CODE_POINTS = 255;
    Pattern SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");

    UUID tenantId();

    String actorId();

    @Override
    default String getName() {
        return actorId();
    }

    record Bearer(UUID tenantId, String actorId) implements FoundationVerifiedPrincipal {
        public Bearer {
            Objects.requireNonNull(tenantId, "tenantId");
            actorId = requireOpaque(actorId, "actorId");
        }
    }

    record BrowserSession(
            UUID tenantId,
            String actorId,
            String sessionId,
            long sessionGeneration,
            String csrfBindingDigest) implements FoundationVerifiedPrincipal {
        public BrowserSession {
            Objects.requireNonNull(tenantId, "tenantId");
            actorId = requireOpaque(actorId, "actorId");
            sessionId = requireOpaque(sessionId, "sessionId");
            if (sessionGeneration < 1) {
                throw new IllegalArgumentException("sessionGeneration must be positive");
            }
            if (csrfBindingDigest == null || !SHA256.matcher(csrfBindingDigest).matches()) {
                throw new IllegalArgumentException("csrfBindingDigest must be a canonical SHA-256 digest");
            }
        }
    }

    private static String requireOpaque(String value, String name) {
        if (value == null || value.isBlank()
                || value.codePointCount(0, value.length()) > MAX_IDENTITY_CODE_POINTS) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 0 || Character.isISOControl(character)) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
        }
        try {
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(name + " is not valid UTF-8", error);
        }
        return value;
    }
}
