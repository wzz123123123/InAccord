package com.inforvans.accord.controlplane.worker.temporal.workflow;

@FunctionalInterface
public interface ReconciliationObservationPort {
    ReconciliationOutcome observe(ReconciliationWorkflowRef ref);
}
