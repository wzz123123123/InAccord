package com.inforvans.accord.reliability;

public record ExpectedVersion(long value) {
    public ExpectedVersion {
        if (value < 0) {
            throw new IllegalArgumentException("expected version must be non-negative");
        }
    }
}
