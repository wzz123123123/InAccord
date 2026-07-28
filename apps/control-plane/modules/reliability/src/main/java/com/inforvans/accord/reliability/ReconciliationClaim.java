package com.inforvans.accord.reliability;

import java.util.Objects;

public sealed interface ReconciliationClaim
        permits ReconciliationClaim.Acquired,
                ReconciliationClaim.NotReconcilable,
                ReconciliationClaim.Missing {
    record Acquired(ReconciliationLease lease) implements ReconciliationClaim {
        public Acquired {
            Objects.requireNonNull(lease, "lease");
        }
    }

    record NotReconcilable(ExternalIntentState state) implements ReconciliationClaim {
        public NotReconcilable {
            Objects.requireNonNull(state, "state");
        }
    }

    record Missing() implements ReconciliationClaim {}
}
