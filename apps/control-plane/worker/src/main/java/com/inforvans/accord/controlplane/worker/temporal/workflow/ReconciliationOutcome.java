package com.inforvans.accord.controlplane.worker.temporal.workflow;

public enum ReconciliationOutcome {
    CONVERGED,
    CONFIRMED_NO_EFFECT,
    DIVERGED,
    STILL_UNKNOWN
}
