package com.inforvans.accord.controlplane.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inforvans.accord.reliability.EventTransport;
import com.inforvans.accord.reliability.IdempotencyResultCleaner;
import com.inforvans.accord.reliability.InboxDispatcher;
import com.inforvans.accord.reliability.LeasedEvent;
import com.inforvans.accord.reliability.MessageFence;
import com.inforvans.accord.reliability.OutboxDispatcher;
import com.inforvans.accord.reliability.TenantWorkPermit;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkerSchedulingTest {
    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner().withUserConfiguration(WorkerScheduling.class);

    @Test
    void apiRoleCreatesNoReliabilityWorkerGraph() {
        contextRunner
            .withPropertyValues("accord.process-role=control-api")
            .run(context -> {
                assertThat(context).doesNotHaveBean(WorkerScheduling.class);
                assertThat(context).doesNotHaveBean(WorkerDrainCoordinator.class);
                assertThat(context).doesNotHaveBean(OutboxDispatcher.class);
                assertThat(context).doesNotHaveBean(InboxDispatcher.class);
                assertThat(context).doesNotHaveBean(IdempotencyResultCleaner.class);
            });
    }

    @Test
    void exactProductionConfigurationIsAcceptedAndExposed() {
        WorkerReliabilityProperties properties = productionProperties();

        assertThat(properties.pollDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.tenantPermitLease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.messageLease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.transportTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.batchSize()).isEqualTo(50);
        assertThat(properties.concurrency()).isEqualTo(8);
        assertThat(properties.maxAttempts()).isEqualTo(8);
        assertThat(properties.cleanupBatchSize()).isEqualTo(200);
        assertThat(properties.drainTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void unsafeConfigurationIsRejectedBeforeScheduling() {
        assertThatThrownBy(() -> new WorkerReliabilityProperties(
            Duration.ZERO,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            50,
            8,
            8,
            200,
            Duration.ofSeconds(20)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pollDelay");

        assertThatThrownBy(() -> new WorkerReliabilityProperties(
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            50,
            8,
            8,
            200,
            Duration.ofSeconds(20)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("transportTimeout");

        assertThatThrownBy(() -> new WorkerReliabilityProperties(
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            501,
            8,
            8,
            200,
            Duration.ofSeconds(20)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batchSize");
    }

    @Test
    void equalReadyChannelsRotateByPermitGenerationInRequiredOrder() {
        OffsetDateTime readyAt = OffsetDateTime.of(
            2026, 7, 27, 12, 0, 0, 0, ZoneOffset.UTC);
        WorkerScheduling.ChannelReadiness readiness =
            new WorkerScheduling.ChannelReadiness(
                Optional.of(readyAt), Optional.of(readyAt), Optional.of(readyAt));

        assertThat(WorkerScheduling.selectChannel(permit(1), readiness))
            .contains(WorkerScheduling.WorkChannel.OUTBOX);
        assertThat(WorkerScheduling.selectChannel(permit(2), readiness))
            .contains(WorkerScheduling.WorkChannel.INBOX);
        assertThat(WorkerScheduling.selectChannel(permit(3), readiness))
            .contains(WorkerScheduling.WorkChannel.CLEANUP);
        assertThat(WorkerScheduling.selectChannel(permit(4), readiness))
            .contains(WorkerScheduling.WorkChannel.OUTBOX);
    }

    @Test
    void earliestChannelWinsBeforeTieRotation() {
        OffsetDateTime first = OffsetDateTime.now(ZoneOffset.UTC);
        WorkerScheduling.ChannelReadiness readiness =
            new WorkerScheduling.ChannelReadiness(
                Optional.of(first.plusSeconds(1)),
                Optional.of(first),
                Optional.of(first.plusSeconds(2)));

        assertThat(WorkerScheduling.selectChannel(permit(99), readiness))
            .contains(WorkerScheduling.WorkChannel.INBOX);
    }

    @Test
    void duplicateAdapterKeysFailAndMissingDestinationFailsClosed() {
        EventTransport transport = (event, timeout) -> null;
        WorkerScheduling.DestinationAdapter first =
            new WorkerScheduling.DestinationAdapter("git-events", transport);
        WorkerScheduling.DestinationAdapter duplicate =
            new WorkerScheduling.DestinationAdapter("git-events", transport);

        assertThatThrownBy(() -> WorkerScheduling.routingTransport(
            List.of(first, duplicate)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("duplicate event transport destination");

        EventTransport failClosed = WorkerScheduling.routingTransport(List.of());
        assertThatThrownBy(() -> failClosed.deliver(event("unregistered"),
            Duration.ofSeconds(10)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no event transport is registered");
    }

    @Test
    void duplicateInboxHandlerKeysFailAtRegistryConstruction() {
        WorkerScheduling.InboxHandlerAdapter first =
            new WorkerScheduling.InboxHandlerAdapter(
                "requirements", "requirement/1.0", (tx, message) -> null);
        WorkerScheduling.InboxHandlerAdapter duplicate =
            new WorkerScheduling.InboxHandlerAdapter(
                "requirements", "requirement/1.0", (tx, message) -> null);

        assertThatThrownBy(() -> WorkerScheduling.handlerRegistry(
            List.of(first, duplicate)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("duplicate inbox handler key");
        assertThat(WorkerScheduling.handlerRegistry(List.of())).isEmpty();
    }

    private static WorkerReliabilityProperties productionProperties() {
        return new WorkerReliabilityProperties(
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            50,
            8,
            8,
            200,
            Duration.ofSeconds(20));
    }

    private static TenantWorkPermit permit(long generation) {
        return new TenantWorkPermit(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            new MessageFence(
                "worker-1",
                generation,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)));
    }

    private static LeasedEvent event(String destination) {
        return new LeasedEvent(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            destination,
            "event/1.0",
            "{}",
            1,
            new MessageFence(
                "worker-1",
                1,
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)));
    }
}
