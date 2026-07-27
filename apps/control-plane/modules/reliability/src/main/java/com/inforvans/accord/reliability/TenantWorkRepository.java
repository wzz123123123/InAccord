package com.inforvans.accord.reliability;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class TenantWorkRepository {
    private static final long MAX_LEASE_MICROS = Duration.ofHours(1).toNanos() / 1_000;

    public Optional<TenantWorkPermit> acquire(
            DSLContext tx, String owner, Duration leaseDuration) {
        Objects.requireNonNull(tx, "tx");
        owner = CommandKey.requireBounded(owner, "owner", 255);
        long leaseMicros = durationMicros(leaseDuration, "leaseDuration");
        Record row = tx.fetchOne("""
            SELECT tenant_id,permit_owner,permit_generation,permit_token,permit_lease_until
            FROM accord_security.acquire_reliability_tenant_work(
              CAST(? AS varchar),CAST(? AS bigint))
            """, owner, leaseMicros);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(toPermit(row));
    }

    public TenantWorkPermit renew(
            DSLContext tx, TenantWorkPermit permit, Duration extension) {
        Objects.requireNonNull(tx, "tx");
        requirePermit(permit);
        long extensionMicros = durationMicros(extension, "extension");
        MessageFence fence = permit.fence();
        Record row = tx.fetchOne("""
            SELECT permit_lease_until
            FROM accord_security.renew_reliability_tenant_work(
              CAST(? AS uuid),CAST(? AS varchar),CAST(? AS bigint),CAST(? AS uuid),
              CAST(? AS timestamptz),CAST(? AS bigint))
            """, permit.tenantId(), fence.owner(), fence.generation(), fence.token(),
            fence.leaseUntil(), extensionMicros);
        if (row == null) {
            throw new IllegalStateException("tenant permit renewal returned no row");
        }
        return new TenantWorkPermit(permit.tenantId(), new MessageFence(
            fence.owner(), fence.generation(), fence.token(),
            Objects.requireNonNull(row.get("permit_lease_until", OffsetDateTime.class))));
    }

    public void lock(DSLContext tx, TenantWorkPermit permit) {
        Objects.requireNonNull(tx, "tx");
        requirePermit(permit);
        MessageFence fence = permit.fence();
        tx.execute("""
            SELECT accord_security.lock_reliability_tenant_work_permit(
              CAST(? AS uuid),CAST(? AS varchar),CAST(? AS bigint),CAST(? AS uuid),
              CAST(? AS timestamptz))
            """, permit.tenantId(), fence.owner(), fence.generation(), fence.token(),
            fence.leaseUntil());
    }

    public void finish(
            DSLContext tx, TenantWorkPermit permit, OffsetDateTime nextAvailableAt) {
        Objects.requireNonNull(tx, "tx");
        requirePermit(permit);
        Objects.requireNonNull(nextAvailableAt, "nextAvailableAt");
        MessageFence fence = permit.fence();
        tx.execute("""
            SELECT accord_security.finish_reliability_tenant_work(
              CAST(? AS uuid),CAST(? AS varchar),CAST(? AS bigint),CAST(? AS uuid),
              CAST(? AS timestamptz),CAST(? AS timestamptz))
            """, permit.tenantId(), fence.owner(), fence.generation(), fence.token(),
            fence.leaseUntil(), nextAvailableAt);
    }

    public void finishNoKnownWork(DSLContext tx, TenantWorkPermit permit) {
        Objects.requireNonNull(tx, "tx");
        requirePermit(permit);
        MessageFence fence = permit.fence();
        tx.execute("""
            SELECT accord_security.finish_reliability_tenant_work(
              CAST(? AS uuid),CAST(? AS varchar),CAST(? AS bigint),CAST(? AS uuid),
              CAST(? AS timestamptz),'infinity'::timestamptz)
            """, permit.tenantId(), fence.owner(), fence.generation(), fence.token(),
            fence.leaseUntil());
    }

    static long durationMicros(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " is too large", error);
        }
        if (nanos % 1_000 != 0) {
            throw new IllegalArgumentException(
                name + " must be representable in PostgreSQL microseconds");
        }
        long micros = nanos / 1_000;
        if (micros < 1 || micros > MAX_LEASE_MICROS) {
            throw new IllegalArgumentException(name + " must be between 1 microsecond and 1 hour");
        }
        return micros;
    }

    static void requirePermit(TenantWorkPermit permit) {
        Objects.requireNonNull(permit, "permit");
    }

    private static TenantWorkPermit toPermit(Record row) {
        UUID tenantId = Objects.requireNonNull(row.get("tenant_id", UUID.class));
        return new TenantWorkPermit(tenantId, new MessageFence(
            Objects.requireNonNull(row.get("permit_owner", String.class)),
            Objects.requireNonNull(row.get("permit_generation", Long.class)),
            Objects.requireNonNull(row.get("permit_token", UUID.class)),
            Objects.requireNonNull(row.get("permit_lease_until", OffsetDateTime.class))));
    }
}
