package com.inforvans.accord.platformkernel;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;

public final class Dsse {
    private Dsse() {}

    public static byte[] preAuthEncoding(String payloadType, byte[] payload) {
        if (payloadType == null || payloadType.isBlank()) {
            throw new IllegalArgumentException("payloadType must not be blank");
        }
        byte[] type = encodePayloadType(payloadType);
        var output = new ByteArrayOutputStream();
        output.writeBytes(("DSSEv1 " + type.length + " ").getBytes(UTF_8));
        output.writeBytes(type);
        output.writeBytes((" " + payload.length + " ").getBytes(UTF_8));
        output.writeBytes(payload);
        return output.toByteArray();
    }

    private static byte[] encodePayloadType(String payloadType) {
        try {
            ByteBuffer encoded = UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(payloadType));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("payloadType must be valid Unicode", exception);
        }
    }
}
