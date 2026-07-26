package com.inforvans.accord.reliability;

public record OutboxMessage(
    String destination,
    String payloadSchema,
    String payload
) {
    public OutboxMessage {
        destination = CommandKey.requireBounded(destination, "destination", 128);
        payloadSchema = CommandKey.requireBounded(payloadSchema, "payloadSchema", 255);
        payload = ReliabilityValues.canonicalJson(payload, "payload", payloadSchema);
    }
}
