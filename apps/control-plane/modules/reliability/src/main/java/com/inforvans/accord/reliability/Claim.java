package com.inforvans.accord.reliability;

import java.time.OffsetDateTime;
import java.util.Objects;

public sealed interface Claim
        permits Claim.Acquired, Claim.InProgress, Claim.Replay, Claim.RequestConflict {
    record Acquired(ClaimLease lease) implements Claim {
        public Acquired {
            Objects.requireNonNull(lease, "lease");
        }
    }

    record InProgress(OffsetDateTime leaseUntil) implements Claim {
        public InProgress {
            Objects.requireNonNull(leaseUntil, "leaseUntil");
        }
    }

    record Replay(StoredHttpResult result) implements Claim {
        public Replay {
            Objects.requireNonNull(result, "result");
        }
    }

    record RequestConflict() implements Claim {}
}
