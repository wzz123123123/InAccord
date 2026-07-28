package com.inforvans.accord.reliability;

import java.util.Objects;

public sealed interface ExecutionClaim
        permits ExecutionClaim.Acquired,
                ExecutionClaim.NotExecutable,
                ExecutionClaim.Missing {
    record Acquired(ExternalWritePermit permit) implements ExecutionClaim {
        public Acquired {
            Objects.requireNonNull(permit, "permit");
        }
    }

    record NotExecutable(ExternalIntentState state) implements ExecutionClaim {
        public NotExecutable {
            Objects.requireNonNull(state, "state");
        }
    }

    record Missing() implements ExecutionClaim {}
}
