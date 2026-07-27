package com.inforvans.accord.controlplane.worker.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowRef;
import com.inforvans.accord.reliability.DeliveryReceipt;
import com.inforvans.accord.reliability.LeasedEvent;
import com.inforvans.accord.reliability.MessageFence;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TemporalReconciliationEventTransportTest {
    private static final UUID TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_ID =
        UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID INTENT_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID VALIDATION_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void derivesAStableTenantQualifiedWorkflowId() {
        UUID otherTenantId =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
        ReconciliationWorkflowRef reference =
            new ReconciliationWorkflowRef(TENANT_ID, INTENT_ID);
        ReconciliationWorkflowRef otherTenantReference =
            new ReconciliationWorkflowRef(otherTenantId, INTENT_ID);

        String workflowId = TemporalReconciliationEventTransport.workflowId(reference);

        assertThat(workflowId).isEqualTo(
            "accord-reconciliation-tenant-" + TENANT_ID + "-intent-" + INTENT_ID);
        assertThat(TemporalReconciliationEventTransport.workflowId(reference))
            .isEqualTo(workflowId);
        assertThat(TemporalReconciliationEventTransport.workflowId(otherTenantReference))
            .isEqualTo(
                "accord-reconciliation-tenant-" + otherTenantId + "-intent-" + INTENT_ID)
            .isNotEqualTo(workflowId);
    }

    @Test
    void mapsTheApiOutboxAndReturnsOneDeterministicTemporalReceipt() {
        AtomicInteger mappings = new AtomicInteger();
        AtomicInteger workflows = new AtomicInteger();
        ReconciliationWorkflowRef expected =
            new ReconciliationWorkflowRef(TENANT_ID, INTENT_ID);
        TemporalReconciliationEventTransport transport =
            new TemporalReconciliationEventTransport(
                event -> {
                    mappings.incrementAndGet();
                    assertThat(event).isEqualTo(apiEvent());
                    return expected;
                },
                (reference, workflowId, timeout) -> {
                    workflows.incrementAndGet();
                    assertThat(reference).isEqualTo(expected);
                    assertThat(workflowId).isEqualTo(
                        "accord-reconciliation-tenant-" + TENANT_ID
                            + "-intent-" + INTENT_ID);
                    assertThat(timeout).isEqualTo(Duration.ofSeconds(20));
                    return ReconciliationOutcome.CONVERGED;
                });

        DeliveryReceipt first = transport.deliver(apiEvent(), Duration.ofSeconds(20));
        DeliveryReceipt replay = transport.deliver(apiEvent(), Duration.ofSeconds(20));

        assertThat(first).isEqualTo(replay);
        assertThat(first.receiptId()).isEqualTo("workflow." + INTENT_ID);
        assertThat(first.receiptDigest()).matches("sha256:[0-9a-f]{64}");
        assertThat(mappings).hasValue(2);
        assertThat(workflows).hasValue(2);
    }

    @Test
    void retriesOnlyAfterAClosedFailedRunAndThenReusesTheSuccessfulRun()
            throws Exception {
        String taskQueue = "reconciliation-transport-retry-" + UUID.randomUUID();
        AtomicInteger workflowSideEffects = new AtomicInteger();
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(taskQueue);
            worker.registerWorkflowImplementationTypes(FailOnceWorkflow.class);
            worker.registerActivitiesImplementations((FailOnceActivity) reference -> {
                if (workflowSideEffects.incrementAndGet() == 1) {
                    throw ApplicationFailure.newNonRetryableFailure(
                        "first workflow run failed", "TEST_FIRST_RUN_FAILURE");
                }
                return ReconciliationOutcome.CONVERGED;
            });
            environment.start();

            ReconciliationWorkflowRef expected =
                new ReconciliationWorkflowRef(TENANT_ID, INTENT_ID);
            TemporalReconciliationEventTransport transport =
                new TemporalReconciliationEventTransport(
                    event -> expected,
                    temporalGateway(
                        environment.getWorkflowClient(), taskQueue));

            assertThatThrownBy(
                    () -> transport.deliver(apiEvent(), Duration.ofSeconds(20)))
                .isInstanceOf(WorkflowFailedException.class);

            DeliveryReceipt retried =
                transport.deliver(apiEvent(), Duration.ofSeconds(20));
            DeliveryReceipt replay =
                transport.deliver(apiEvent(), Duration.ofSeconds(20));

            assertThat(retried).isEqualTo(replay);
            assertThat(workflowSideEffects).hasValue(2);
        }
    }

    @Test
    void rejectsUnknownOutboxContractsBeforeMappingOrTemporal() {
        AtomicInteger sideEffects = new AtomicInteger();
        TemporalReconciliationEventTransport transport =
            new TemporalReconciliationEventTransport(
                event -> {
                    sideEffects.incrementAndGet();
                    return new ReconciliationWorkflowRef(TENANT_ID, INTENT_ID);
                },
                (reference, workflowId, timeout) -> {
                    sideEffects.incrementAndGet();
                    return ReconciliationOutcome.CONVERGED;
                });
        LeasedEvent wrong = new LeasedEvent(
            TENANT_ID,
            EVENT_ID,
            "unknown",
            "contract-validation.completed/1.0.0",
            apiEvent().payload(),
            1,
            fence());

        assertThatThrownBy(() -> transport.deliver(wrong, Duration.ofSeconds(20)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(sideEffects).hasValue(0);
    }

    private static LeasedEvent apiEvent() {
        return new LeasedEvent(
            TENANT_ID,
            EVENT_ID,
            "contract-validations",
            "contract-validation.completed/1.0.0",
            "{\"document_digest\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                + "\"intent_id\":\"" + INTENT_ID + "\","
                + "\"schema_id\":\"https://schemas.accord.inforvans.com/events/domain-event/1-0-0\","
                + "\"validation_id\":\"" + VALIDATION_ID + "\",\"version\":1}",
            1,
            fence());
    }

    private static MessageFence fence() {
        return new MessageFence(
            "worker-1",
            1,
            UUID.fromString("60000000-0000-0000-0000-000000000001"),
            OffsetDateTime.parse("2026-07-27T12:00:00Z"));
    }

    private static TemporalReconciliationEventTransport.WorkflowGateway temporalGateway(
            WorkflowClient client, String taskQueue) throws Exception {
        Class<?> gatewayType = Class.forName(
            TemporalReconciliationEventTransport.class.getName()
                + "$TemporalWorkflowGateway");
        Constructor<?> constructor = gatewayType.getDeclaredConstructor(
            Supplier.class, String.class);
        constructor.setAccessible(true);
        return (TemporalReconciliationEventTransport.WorkflowGateway)
            constructor.newInstance((Supplier<WorkflowClient>) () -> client, taskQueue);
    }

    @ActivityInterface
    public interface FailOnceActivity {
        ReconciliationOutcome reconcile(ReconciliationWorkflowRef reference);
    }

    public static final class FailOnceWorkflow
            implements com.inforvans.accord.controlplane.worker.temporal.workflow
                .ReconciliationWorkflow {
        private final FailOnceActivity activity = Workflow.newActivityStub(
            FailOnceActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build());

        @Override
        public ReconciliationOutcome reconcile(ReconciliationWorkflowRef reference) {
            return activity.reconcile(reference);
        }
    }
}
