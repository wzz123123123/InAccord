package com.inforvans.accord.controlplane.worker.temporal.workflow;

import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationFailure;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

public final class ReadOnlyReconciliationActivity implements ReconciliationActivities {
    private final ReconciliationObservationPort observation;

    public ReadOnlyReconciliationActivity(ReconciliationObservationPort observation) {
        this.observation = Objects.requireNonNull(observation, "observation");
    }

    @Override
    public ReconciliationOutcome observe(ReconciliationWorkflowRef ref) {
        Objects.requireNonNull(ref, "ref");
        Activity.getExecutionContext().heartbeat(ref.intentId());
        try {
            return observation.observe(ref);
        } catch (ReconciliationFailure failure) {
            String type = failure.code().name();
            if (failure.code() == ReconciliationFailure.Code.RECONCILIATION_MISSING
                    || failure.code()
                        == ReconciliationFailure.Code.RECONCILIATION_NOT_RECONCILABLE
                    || failure.code() == ReconciliationFailure.Code.RECONCILIATION_INTERNAL) {
                throw ApplicationFailure.newNonRetryableFailure(
                    "reconciliation rejected", type);
            }
            throw ApplicationFailure.newFailure("reconciliation retry required", type);
        }
    }
}
