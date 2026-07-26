package com.inforvans.accord.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ReliabilityValuesTest {
    private static final UUID TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID AGGREGATE_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID CAUSATION_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID CORRELATION_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final String PAYLOAD =
        "{\"version\":9223372036854775807,\"decimal\":1.2300,\"a\":true}";
    private static final String EXACT_PAYLOAD =
        "{\"a\":true,\"decimal\":1.23,\"version\":9223372036854775807}";
    private static final String RFC_8785_PAYLOAD =
        "{\"a\":true,\"decimal\":1.23,\"version\":9223372036854776000}";

    @Test
    void contractValidationCompletedVersion100PreservesExactLongIntegers() {
        DomainEvent event = domainEvent("contract-validation.completed", "1.0.0");
        OutboxMessage outbox = outbox("contract-validation.completed/1.0.0");
        InboxMessage inbox = inbox("contract-validation.completed/1.0.0");

        assertEquals(Long.MAX_VALUE, event.sequence());
        assertEquals(EXACT_PAYLOAD, event.payload());
        assertEquals(EXACT_PAYLOAD, outbox.payload());
        assertEquals(EXACT_PAYLOAD, inbox.payload());
    }

    @Test
    void ordinarySchemasUseStandardRfc8785NumberCanonicalization() {
        DomainEvent event = domainEvent("ordinary.completed", "1.0.0");
        OutboxMessage outbox = outbox("ordinary.completed/1.0.0");
        InboxMessage inbox = inbox("ordinary.completed/1.0.0");

        assertEquals(RFC_8785_PAYLOAD, event.payload());
        assertEquals(RFC_8785_PAYLOAD, outbox.payload());
        assertEquals(RFC_8785_PAYLOAD, inbox.payload());
    }

    @Test
    void newerContractValidationSchemaReturnsToStandardRfc8785Canonicalization() {
        DomainEvent event = domainEvent("contract-validation.completed", "1.0.1");
        OutboxMessage outbox = outbox("contract-validation.completed/1.0.1");
        InboxMessage inbox = inbox("contract-validation.completed/1.0.1");

        assertEquals(RFC_8785_PAYLOAD, event.payload());
        assertEquals(RFC_8785_PAYLOAD, outbox.payload());
        assertEquals(RFC_8785_PAYLOAD, inbox.payload());
    }

    private static DomainEvent domainEvent(String eventType, String schemaVersion) {
        return new DomainEvent(
            TENANT_ID,
            EVENT_ID,
            "tenant",
            TENANT_ID.toString(),
            "contract-validation",
            AGGREGATE_ID,
            Long.MAX_VALUE,
            eventType,
            schemaVersion,
            CAUSATION_ID,
            CORRELATION_ID,
            "actor-1",
            PAYLOAD,
            OffsetDateTime.parse("2026-07-26T00:00:00Z"));
    }

    private static OutboxMessage outbox(String payloadSchema) {
        return new OutboxMessage("contract-validations", payloadSchema, PAYLOAD);
    }

    private static InboxMessage inbox(String payloadSchema) {
        return new InboxMessage(
            TENANT_ID,
            "contract-validations",
            "message-1",
            "sha256:" + "0".repeat(64),
            "contract-validation.complete",
            payloadSchema,
            PAYLOAD);
    }
}
