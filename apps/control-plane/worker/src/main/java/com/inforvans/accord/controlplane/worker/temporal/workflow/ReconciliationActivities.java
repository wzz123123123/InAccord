package com.inforvans.accord.controlplane.worker.temporal.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ReconciliationActivities {
    @ActivityMethod
    ReconciliationOutcome observe(ReconciliationWorkflowRef ref);
}
