package com.inforvans.accord.reliability;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

final class OutboxDispatcherTest {
    @Test
    void transportTimeoutMustRemainStrictlyInsideTheMessageLease() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher(
            Duration.ofSeconds(30), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> dispatcher(
            Duration.ofSeconds(31), Duration.ofSeconds(30)));
    }

    @Test
    void rejectsCrossTenantLeasesBeforeOpeningATransactionOrCallingTransport() {
        OutboxDispatcher dispatcher = dispatcher(
            Duration.ofSeconds(5), Duration.ofSeconds(30));
        TenantWorkPermit permit = permit(TENANT);
        LeasedEvent event = new LeasedEvent(
            UUID.randomUUID(), UUID.randomUUID(), "events", "event/1.0", "{}", 1,
            permit.fence());
        assertThrows(IllegalArgumentException.class,
            () -> dispatcher.dispatch(permit, List.of(event)));
    }

    private static OutboxDispatcher dispatcher(Duration timeout, Duration lease) {
        return new OutboxDispatcher(
            (event, explicitTimeout) -> {
                throw new AssertionError("transport must not be called");
            },
            new OutboxRepository(),
            new FailingTransactions(),
            (permit, next) -> {
                throw new AssertionError("permit must not be finished");
            },
            timeout, lease, 2, 8, Duration.ofMillis(10), Duration.ofSeconds(1));
    }

    private static TenantWorkPermit permit(UUID tenant) {
        return new TenantWorkPermit(tenant, new MessageFence(
            "owner", 1, UUID.randomUUID(), OffsetDateTime.now().plusMinutes(1)));
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
