package com.inforvans.accord.controlplane.worker.temporal.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public final class ReconciliationWorkflowImpl implements ReconciliationWorkflow {
    private static final ActivityOptions OBSERVATION_OPTIONS = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofMinutes(2))
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setHeartbeatTimeout(Duration.ofSeconds(10))
        .setRetryOptions(RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofSeconds(8))
            .setMaximumAttempts(8)
            .setDoNotRetry(
                "RECONCILIATION_NOT_RECONCILABLE",
                "RECONCILIATION_MISSING")
            .build())
        .build();

    private final ReconciliationActivities activities = Workflow.newActivityStub(
        ReconciliationActivities.class, OBSERVATION_OPTIONS);

    @Override
    public ReconciliationOutcome reconcile(ReconciliationWorkflowRef ref) {
        return activities.observe(ref);
    }
}
