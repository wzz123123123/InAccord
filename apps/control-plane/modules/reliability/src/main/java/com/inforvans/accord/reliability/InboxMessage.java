package com.inforvans.accord.reliability;

import java.util.Objects;
import java.util.UUID;

public record InboxMessage(
    UUID tenantId,
    String source,
    String sourceMessageId,
    String requestDigest,
    String handlerKey,
    String payloadSchema,
    String payload
) {
    public InboxMessage {
        Objects.requireNonNull(tenantId, "tenantId");
        source = CommandKey.requireBounded(source, "source", 128);
        sourceMessageId = CommandKey.requireBounded(
            sourceMessageId, "sourceMessageId", 255);
        requestDigest = ReliabilityValues.digest(requestDigest, "requestDigest");
        handlerKey = CommandKey.requireBounded(handlerKey, "handlerKey", 128);
        payloadSchema = CommandKey.requireBounded(payloadSchema, "payloadSchema", 255);
        payload = ReliabilityValues.canonicalJson(payload, "payload");
    }
}
