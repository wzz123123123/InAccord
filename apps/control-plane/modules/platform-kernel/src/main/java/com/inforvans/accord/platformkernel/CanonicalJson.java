package com.inforvans.accord.platformkernel;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;

public final class CanonicalJson {
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private CanonicalJson() {}

    public static byte[] canonicalize(byte[] input) {
        String json = decodeUtf8(input);
        try {
            validateSingleJsonValue(json);
            String wrapped = new JsonCanonicalizer("[" + json + "]").getEncodedString();
            validateIJsonUnicode(wrapped);
            return wrapped.substring(1, wrapped.length() - 1).getBytes(UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("input must be valid I-JSON", exception);
        }
    }

    private static String decodeUtf8(byte[] input) {
        try {
            return UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("input must be valid UTF-8 JSON", exception);
        }
    }

    private static void validateSingleJsonValue(String json) throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            if (parser.nextToken() == null) {
                throw new IOException("JSON input must contain one value");
            }
            parser.skipChildren();
            if (parser.nextToken() != null) {
                throw new IOException("JSON input must contain exactly one value");
            }
        }
    }

    private static void validateIJsonUnicode(String value) {
        boolean hasForbiddenCodePoint = value.codePoints().anyMatch(codePoint ->
                (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)
                        || (codePoint >= 0xfdd0 && codePoint <= 0xfdef)
                        || (codePoint & 0xffff) == 0xfffe
                        || (codePoint & 0xffff) == 0xffff);
        if (hasForbiddenCodePoint) {
            throw new IllegalArgumentException("input must be valid I-JSON");
        }
    }

    public static String sha256(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalize(input));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
