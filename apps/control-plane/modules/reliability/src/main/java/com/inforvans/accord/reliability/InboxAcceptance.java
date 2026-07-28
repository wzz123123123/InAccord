package com.inforvans.accord.reliability;

import java.util.Objects;

public sealed interface InboxAcceptance
        permits InboxAcceptance.Accepted,
                InboxAcceptance.Duplicate,
                InboxAcceptance.DigestConflict {
    record Accepted() implements InboxAcceptance {}

    record Duplicate(String state) implements InboxAcceptance {
        public Duplicate {
            Objects.requireNonNull(state, "state");
        }
    }

    record DigestConflict() implements InboxAcceptance {}
}
