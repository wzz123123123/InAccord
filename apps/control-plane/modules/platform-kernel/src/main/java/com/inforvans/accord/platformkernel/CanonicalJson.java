package com.inforvans.accord.platformkernel;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
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

    public static byte[] canonicalizePreservingExactIntegers(byte[] input) {
        String json = decodeUtf8(input);
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new IOException("JSON input must contain one value");
            }
            byte[] canonical = canonicalizePreservingExactIntegers(parser, first);
            if (parser.nextToken() != null) {
                throw new IOException("JSON input must contain exactly one value");
            }
            return canonical;
        } catch (IOException exception) {
            throw new IllegalArgumentException("input must be valid I-JSON", exception);
        }
    }

    private static byte[] canonicalizePreservingExactIntegers(
            JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> canonicalizeObject(parser);
            case START_ARRAY -> canonicalizeArray(parser);
            case VALUE_NUMBER_INT -> parser.getBigIntegerValue().toString().getBytes(UTF_8);
            case VALUE_STRING -> canonicalize(encodeJsonString(parser.getText()));
            case VALUE_NUMBER_FLOAT, VALUE_TRUE, VALUE_FALSE, VALUE_NULL ->
                canonicalize(parser.getText().getBytes(UTF_8));
            default -> throw new IOException("unexpected JSON token");
        };
    }

    private static byte[] canonicalizeObject(JsonParser parser) throws IOException {
        List<ObjectMember> members = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token != JsonToken.FIELD_NAME) {
                throw new IOException("object member name is missing");
            }
            String name = parser.currentName();
            byte[] canonicalName = canonicalize(encodeJsonString(name));
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw new IOException("object member value is missing");
            }
            members.add(new ObjectMember(
                name,
                canonicalName,
                canonicalizePreservingExactIntegers(parser, valueToken)));
        }
        members.sort(Comparator.comparing(ObjectMember::name));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write('{');
        for (int index = 0; index < members.size(); index++) {
            if (index > 0) {
                output.write(',');
            }
            ObjectMember member = members.get(index);
            output.writeBytes(member.canonicalName());
            output.write(':');
            output.writeBytes(member.canonicalValue());
        }
        output.write('}');
        return output.toByteArray();
    }

    private static byte[] canonicalizeArray(JsonParser parser) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write('[');
        int index = 0;
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) {
                throw new IOException("array is incomplete");
            }
            if (index++ > 0) {
                output.write(',');
            }
            output.writeBytes(canonicalizePreservingExactIntegers(parser, token));
        }
        output.write(']');
        return output.toByteArray();
    }

    private static byte[] encodeJsonString(String value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(output)) {
            generator.writeString(value);
        }
        return output.toByteArray();
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

    private record ObjectMember(String name, byte[] canonicalName, byte[] canonicalValue) {}
}
