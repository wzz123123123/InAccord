package com.inforvans.accord.controlplane.worker.reconciliation;

import java.util.Objects;

public final class ReconciliationFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        OBSERVATION_UNAVAILABLE,
        RECONCILIATION_BUSY,
        RECONCILIATION_MISSING,
        RECONCILIATION_NOT_RECONCILABLE,
        RECONCILIATION_FENCE_LOST,
        RECONCILIATION_PERSISTENCE_UNAVAILABLE,
        RECONCILIATION_INTERNAL
    }

    private final Code code;

    private ReconciliationFailure(Code code) {
        super(Objects.requireNonNull(code, "code").name(), null, false, false);
        this.code = code;
    }

    public static ReconciliationFailure of(Code code) {
        return new ReconciliationFailure(code);
    }

    public Code code() {
        return code;
    }
}
