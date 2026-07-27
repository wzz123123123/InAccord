package com.inforvans.accord.controlplane.worker.temporal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflow;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowRef;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationRuntimeProperties;
import com.inforvans.accord.controlplane.worker.reconciliation.WorkerTenantTransactions;
import com.inforvans.accord.platformkernel.CanonicalJson;
import com.inforvans.accord.reliability.DeliveryReceipt;
import com.inforvans.accord.reliability.EventTransport;
import com.inforvans.accord.reliability.LeasedEvent;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class TemporalReconciliationEventTransport implements EventTransport {
    public static final String DESTINATION = "contract-validations";
    public static final String PAYLOAD_SCHEMA =
        "contract-validation.completed/1.0.0";
    private static final Duration WORKFLOW_EXECUTION_TIMEOUT = Duration.ofMinutes(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ReconciliationEventMapper mapper;
    private final WorkflowGateway workflows;

    public TemporalReconciliationEventTransport(
            WorkflowClient client,
            String taskQueue,
            WorkerTenantTransactions transactions,
            JooqExternalIntentStore intents,
            ReconciliationRuntimeProperties properties) {
        this(
            new ContractValidationReconciliationMapper(
                transactions, intents, properties),
            new TemporalWorkflowGateway(
                () -> Objects.requireNonNull(client, "client"), taskQueue));
    }

    TemporalReconciliationEventTransport(
            Supplier<WorkflowClient> client,
            String taskQueue,
            WorkerTenantTransactions transactions,
            JooqExternalIntentStore intents,
            ReconciliationRuntimeProperties properties) {
        this(
            new ContractValidationReconciliationMapper(
                transactions, intents, properties),
            new TemporalWorkflowGateway(client, taskQueue));
    }

    TemporalReconciliationEventTransport(
            ReconciliationEventMapper mapper, WorkflowGateway workflows) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
    }

    @Override
    public DeliveryReceipt deliver(LeasedEvent event, Duration timeout) {
        Objects.requireNonNull(event, "event");
        requirePositive(timeout);
        if (!DESTINATION.equals(event.destination())
                || !PAYLOAD_SCHEMA.equals(event.payloadSchema())) {
            throw new IllegalArgumentException(
                "unsupported reconciliation outbox contract");
        }

        ReconciliationWorkflowRef reference = Objects.requireNonNull(
            mapper.map(event), "reconciliation workflow reference");
        if (!event.tenantId().equals(reference.tenantId())) {
            throw new IllegalArgumentException(
                "reconciliation workflow tenant does not match the event tenant");
        }
        ReconciliationOutcome outcome = Objects.requireNonNull(
            workflows.execute(reference, workflowId(reference), timeout),
            "reconciliation workflow outcome");
        return new DeliveryReceipt(
            "workflow." + reference.intentId(), receiptDigest(reference, outcome));
    }

    public static String workflowId(ReconciliationWorkflowRef reference) {
        Objects.requireNonNull(reference, "reference");
        return "accord-reconciliation-tenant-" + reference.tenantId()
            + "-intent-" + reference.intentId();
    }

    private static String receiptDigest(
            ReconciliationWorkflowRef reference, ReconciliationOutcome outcome) {
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("tenant_id", reference.tenantId().toString());
        receipt.put("intent_id", reference.intentId().toString());
        receipt.put("outcome", outcome.name());
        try {
            return CanonicalJson.sha256(JSON.writeValueAsBytes(receipt));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(
                "reconciliation receipt cannot be serialized", impossible);
        }
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }

    @FunctionalInterface
    interface ReconciliationEventMapper {
        ReconciliationWorkflowRef map(LeasedEvent event);
    }

    @FunctionalInterface
    interface WorkflowGateway {
        ReconciliationOutcome execute(
            ReconciliationWorkflowRef reference, String workflowId, Duration timeout);
    }

    private static final class TemporalWorkflowGateway implements WorkflowGateway {
        private final Supplier<WorkflowClient> client;
        private final String taskQueue;

        private TemporalWorkflowGateway(
                Supplier<WorkflowClient> client, String taskQueue) {
            this.client = Objects.requireNonNull(client, "client");
            this.taskQueue = requireTaskQueue(taskQueue);
        }

        @Override
        public ReconciliationOutcome execute(
                ReconciliationWorkflowRef reference,
                String workflowId,
                Duration timeout) {
            WorkflowClient workflowClient = Objects.requireNonNull(
                client.get(), "Temporal workflow client is not running");
            ReconciliationWorkflow workflow = workflowClient.newWorkflowStub(
                ReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy
                            .WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                    .setTaskQueue(taskQueue)
                    .setWorkflowExecutionTimeout(WORKFLOW_EXECUTION_TIMEOUT)
                    .build());
            WorkflowStub resultStub;
            try {
                WorkflowClient.start(workflow::reconcile, reference);
                resultStub = WorkflowStub.fromTyped(workflow);
            } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
                ReconciliationWorkflow existing = workflowClient.newWorkflowStub(
                    ReconciliationWorkflow.class, workflowId);
                resultStub = WorkflowStub.fromTyped(existing);
            }
            try {
                return resultStub.getResult(
                    timeout.toNanos(), TimeUnit.NANOSECONDS,
                    ReconciliationOutcome.class);
            } catch (TimeoutException timeoutFailure) {
                throw new IllegalStateException(
                    "reconciliation workflow did not finish before the transport deadline",
                    timeoutFailure);
            }
        }

        private static String requireTaskQueue(String taskQueue) {
            Objects.requireNonNull(taskQueue, "taskQueue");
            if (taskQueue.isBlank() || taskQueue.length() > 255
                    || taskQueue.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("taskQueue is invalid");
            }
            return taskQueue;
        }
    }
}
