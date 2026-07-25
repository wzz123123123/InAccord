package com.inforvans.accord.platformkernel;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CanonicalJsonTest {
    private final Path fixtures = Path.of("../../../../contracts/golden-fixtures/jcs");

    @Test
    void canonicalJsonAndDigestMatchTheCrossLanguageVector() throws Exception {
        byte[] input = Files.readAllBytes(fixtures.resolve("domain-event.input.json"));
        byte[] canonical = Files.readAllBytes(fixtures.resolve("domain-event.canonical.json"));
        String digest = Files.readString(fixtures.resolve("domain-event.sha256")).trim();

        assertThat(CanonicalJson.canonicalize(input)).isEqualTo(canonical);
        assertThat(CanonicalJson.sha256(input)).isEqualTo(digest);
    }

    @Test
    void dssePreAuthEncodingIsLengthDelimited() {
        assertThat(Dsse.preAuthEncoding("application/json", "abc".getBytes(UTF_8)))
                .isEqualTo("DSSEv1 16 application/json 3 abc".getBytes(UTF_8));
    }

    @Test
    void dsseRejectsPayloadTypesThatAreNotValidUnicode() {
        assertThatThrownBy(() -> Dsse.preAuthEncoding("application/\ud800", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payloadType must be valid Unicode");
    }

    @Test
    void malformedUtf8IsRejectedBeforeCanonicalization() {
        byte[] malformedJson = {
            '{', '"', 'v', 'a', 'l', 'u', 'e', '"', ':', '"', (byte) 0xc3, '(', '"', '}'
        };

        assertThatThrownBy(() -> CanonicalJson.canonicalize(malformedJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("input must be valid UTF-8 JSON");
    }

    @Test
    void everyTopLevelJsonValueCanBeCanonicalized() {
        for (String json : new String[] {"null", "true", "1", "\"value\""}) {
            assertThat(new String(CanonicalJson.canonicalize(json.getBytes(UTF_8)), UTF_8))
                    .as(json)
                    .isEqualTo(json);
        }
    }

    @Test
    void unpairedUnicodeSurrogatesAreRejected() {
        for (String json : new String[] {"{\"value\":\"\\ud800\"}", "{\"value\":\"\\udc00\"}"}) {
            assertThatThrownBy(() -> CanonicalJson.canonicalize(json.getBytes(UTF_8)))
                    .as(json)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("input must be valid I-JSON");
        }
    }

    @Test
    void unicodeNoncharactersAreRejected() {
        for (int codePoint : new int[] {0xfdd0, 0xfffe, 0x1ffff, 0x10ffff}) {
            String json = "{\"value\":\"" + new String(Character.toChars(codePoint)) + "\"}";

            assertThatThrownBy(() -> CanonicalJson.canonicalize(json.getBytes(UTF_8)))
                    .as("U+%04X", codePoint)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("input must be valid I-JSON");
        }
    }

    @Test
    void pairedUnicodeSurrogatesRemainValid() {
        String escapedPair = "{\"value\":\"\\ud83d\\ude00\"}";
        String emoji = new String(Character.toChars(0x1f600));

        assertThat(new String(CanonicalJson.canonicalize(escapedPair.getBytes(UTF_8)), UTF_8))
                .isEqualTo("{\"value\":\"" + emoji + "\"}");
    }

    @Test
    void emptyMultipleAndNonIJsonInputsAreRejected() {
        String[] invalidInputs = {"", " ", "1,2", "{\"x\":null,\"x\":1}", "{\"x\":01}"};

        for (String input : invalidInputs) {
            assertThatThrownBy(() -> CanonicalJson.canonicalize(input.getBytes(UTF_8)))
                    .as(input)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("input must be valid I-JSON");
        }
    }
}
