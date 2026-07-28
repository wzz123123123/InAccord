package com.inforvans.accord.controlplane.worker.temporal.workflow;

import java.util.Objects;
import java.util.UUID;

public record ReconciliationWorkflowRef(UUID tenantId, UUID intentId) {
    public ReconciliationWorkflowRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
    }
}
