package com.inforvans.accord.controlplane.worker.reconciliation;

import com.inforvans.accord.reliability.ReconciliationLease;
import com.inforvans.accord.reliability.ReconciliationResolution;
import java.time.Duration;

@FunctionalInterface
public interface ProviderObservationPort {
    ReconciliationResolution observe(ReconciliationLease lease, Duration timeout);
}
