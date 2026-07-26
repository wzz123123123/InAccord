package com.inforvans.accord.reliability;

import java.util.Objects;

public sealed interface ExternalIntentRegistration
        permits ExternalIntentRegistration.Created,
                ExternalIntentRegistration.Duplicate,
                ExternalIntentRegistration.Conflict {
    record Created(ExternalIntentRef intent) implements ExternalIntentRegistration {
        public Created {
            Objects.requireNonNull(intent, "intent");
        }
    }

    record Duplicate(ExternalIntentRef intent) implements ExternalIntentRegistration {
        public Duplicate {
            Objects.requireNonNull(intent, "intent");
        }
    }

    record Conflict() implements ExternalIntentRegistration {}
}
