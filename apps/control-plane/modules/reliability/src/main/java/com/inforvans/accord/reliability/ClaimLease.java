package com.inforvans.accord.reliability;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ClaimLease(
    String owner,
    long generation,
    UUID token,
    OffsetDateTime leaseUntil
) {
    public ClaimLease {
        owner = CommandKey.requireBounded(owner, "owner", 255);
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
    }
}
