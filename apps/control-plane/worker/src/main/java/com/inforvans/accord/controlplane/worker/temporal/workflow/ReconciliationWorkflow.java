package com.inforvans.accord.controlplane.worker.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ReconciliationWorkflow {
    @WorkflowMethod(name = "accord.reconciliation.v1")
    ReconciliationOutcome reconcile(ReconciliationWorkflowRef ref);
}
