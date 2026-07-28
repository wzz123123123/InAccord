package com.inforvans.accord.reliability;

public final class VersionConflict extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final long expected;
    private final Long actual;

    public VersionConflict(long expected, Long actual) {
        super("expected aggregate version " + expected + " but was "
            + (actual == null ? "absent" : actual));
        this.expected = expected;
        this.actual = actual;
    }

    public long expected() {
        return expected;
    }

    public Long actual() {
        return actual;
    }
}
