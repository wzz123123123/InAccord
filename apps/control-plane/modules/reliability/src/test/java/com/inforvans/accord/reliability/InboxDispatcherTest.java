package com.inforvans.accord.reliability;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

final class InboxDispatcherTest {
    @Test
    void handlerRegistrationsRejectNullAndUnboundedSchemas() {
        assertThrows(NullPointerException.class,
            () -> new InboxDispatcher.HandlerRegistration("event/1.0", null));
        assertThrows(IllegalArgumentException.class,
            () -> new InboxDispatcher.HandlerRegistration("x".repeat(256), (tx, message) ->
                new HandlerReceipt("id", "sha256:" + "a".repeat(64))));
    }

    @Test
    void rejectsCrossTenantMessagesBeforeOpeningAHandlerTransaction() {
        InboxDispatcher dispatcher = new InboxDispatcher(
            new InboxRepository(), Map.of(), new FailingTransactions(),
            (permit, next) -> { throw new AssertionError("must not finish"); },
            8, Duration.ofMillis(10), Duration.ofSeconds(1));
        TenantWorkPermit permit = new TenantWorkPermit(TENANT, new MessageFence(
            "owner", 1, UUID.randomUUID(), OffsetDateTime.now().plusMinutes(1)));
        LeasedInboxMessage message = new LeasedInboxMessage(
            UUID.randomUUID(), "source", "message", "sha256:" + "a".repeat(64),
            "handler", "event/1.0", "{}", 1, permit.fence());
        assertThrows(IllegalArgumentException.class,
            () -> dispatcher.dispatch(permit, List.of(message)));
    }

    private static final UUID TENANT =
        UUID.fromString("10000000-0000-0000-0000-000000000001");

    private static final class FailingTransactions
            implements OutboxDispatcher.TenantTransactions {
        @Override
        public <T> T inTenant(UUID tenantId, Function<DSLContext, T> work) {
            throw new AssertionError("transaction must not be opened");
        }
    }
}
