package com.inforvans.accord.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

final class MessageFenceProperties {
    private static final OffsetDateTime DEADLINE =
        OffsetDateTime.parse("2026-07-27T00:00:00Z");

    @Property
    void positiveBoundedLeaseDurationsHaveExactMicroseconds(
            @ForAll @LongRange(min = 1, max = 3_600_000_000L) long micros) {
        assertEquals(micros, TenantWorkRepository.durationMicros(
            Duration.ofNanos(micros * 1_000), "lease"));
    }

    @Property
    void generationsAreMonotonicAndTakeoversReplaceTokens(
            @ForAll @IntRange(min = 1, max = 1_000_000) int generation) {
        UUID firstToken = UUID.nameUUIDFromBytes(("first-" + generation).getBytes());
        UUID secondToken = UUID.nameUUIDFromBytes(("second-" + generation).getBytes());
        MessageFence first = new MessageFence(
            "owner", generation, firstToken, DEADLINE);
        MessageFence second = new MessageFence(
            "owner", generation + 1L, secondToken, DEADLINE.plusSeconds(1));
        assertEquals(first.generation() + 1, second.generation());
        assertNotEquals(first.token(), second.token());
    }

    @Property
    void nonpositiveGenerationsAreRejected(
            @ForAll @IntRange(min = -1_000_000, max = 0) int generation) {
        assertThrows(IllegalArgumentException.class, () -> new MessageFence(
            "owner", generation, UUID.randomUUID(), DEADLINE));
    }
}
