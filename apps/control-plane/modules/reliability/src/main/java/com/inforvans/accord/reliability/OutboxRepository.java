package com.inforvans.accord.reliability;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

public final class OutboxRepository {
    private final TenantWorkRepository tenantWork = new TenantWorkRepository();

    public List<LeasedEvent> lease(
            DSLContext tx,
            TenantWorkPermit permit,
            String owner,
            int limit,
            Duration leaseDuration) {
        Objects.requireNonNull(tx, "tx");
        TenantWorkRepository.requirePermit(permit);
        owner = CommandKey.requireBounded(owner, "owner", 255);
        requireLimit(limit);
        long leaseMicros = TenantWorkRepository.durationMicros(
            leaseDuration, "leaseDuration");
        tenantWork.lock(tx, permit);
        return tx.fetch("""
            WITH decision AS (
              SELECT clock_timestamp() AS at
            ), candidates AS (
              SELECT message.event_id
              FROM public.outbox_event message,decision
              WHERE message.tenant_id=CAST(? AS uuid)
                AND ((message.state='PENDING' AND message.available_at<=decision.at)
                  OR (message.state='DELIVERING' AND message.lease_until<=decision.at))
              ORDER BY message.available_at,message.event_id
              FOR UPDATE OF message SKIP LOCKED
              LIMIT ?
            )
            UPDATE public.outbox_event message
            SET state='DELIVERING',attempt_count=message.attempt_count+1,
                lease_owner=CAST(? AS varchar),
                lease_generation=message.lease_generation+1,
                lease_token=gen_random_uuid(),
                lease_until=LEAST(
                  decision.at + CAST(? AS bigint)*INTERVAL '1 microsecond',
                  CAST(? AS timestamptz)),
                delivered_at=NULL,dead_at=NULL,last_error_code=NULL
            FROM candidates,decision
            WHERE message.tenant_id=CAST(? AS uuid)
              AND message.event_id=candidates.event_id
            RETURNING message.tenant_id,message.event_id,message.destination,
              message.payload_schema,message.payload,message.attempt_count,
              message.lease_owner,message.lease_generation,message.lease_token,
              message.lease_until
            """, permit.tenantId(), limit, owner, leaseMicros,
            permit.fence().leaseUntil(), permit.tenantId()).map(this::toEvent);
    }

    public Optional<DeliveryReceipt> findReceipt(
            DSLContext tx, TenantWorkPermit permit, LeasedEvent event) {
        requireBoundEvent(tx, permit, event);
        tenantWork.lock(tx, permit);
        lockMessage(tx, permit, event, Duration.ZERO);
        Record row = tx.fetchOne("""
            SELECT receipt_id,receipt_digest
            FROM public.outbox_delivery_receipt
            WHERE tenant_id=CAST(? AS uuid) AND event_id=CAST(? AS uuid)
            """, permit.tenantId(), event.eventId());
        return row == null ? Optional.empty() : Optional.of(new DeliveryReceipt(
            row.get("receipt_id", String.class), row.get("receipt_digest", String.class)));
    }

    void assertTransportWindow(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedEvent event,
            Duration transportTimeout) {
        requireBoundEvent(tx, permit, event);
        tenantWork.lock(tx, permit);
        lockMessage(tx, permit, event, transportTimeout);
    }

    public LeasedEvent renew(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedEvent event,
            Duration extension) {
        requireBoundEvent(tx, permit, event);
        long extensionMicros = TenantWorkRepository.durationMicros(extension, "extension");
        tenantWork.lock(tx, permit);
        MessageFence fence = event.fence();
        Record row = tx.fetchOne("""
            UPDATE public.outbox_event message
            SET lease_until=LEAST(
              message.lease_until + CAST(? AS bigint)*INTERVAL '1 microsecond',
              CAST(? AS timestamptz))
            WHERE message.tenant_id=CAST(? AS uuid)
              AND message.event_id=CAST(? AS uuid)
              AND message.state='DELIVERING'
              AND message.lease_owner=CAST(? AS varchar)
              AND message.lease_generation=CAST(? AS bigint)
              AND message.lease_token=CAST(? AS uuid)
              AND message.lease_until=CAST(? AS timestamptz)
              AND message.lease_until>clock_timestamp()
              AND CAST(? AS timestamptz)>message.lease_until
            RETURNING message.lease_until
            """, extensionMicros, permit.fence().leaseUntil(), permit.tenantId(),
            event.eventId(), fence.owner(), fence.generation(), fence.token(),
            fence.leaseUntil(), permit.fence().leaseUntil());
        if (row == null) {
            throw new IllegalStateException("outbox renewal fence was lost");
        }
        return withFence(event, new MessageFence(
            fence.owner(), fence.generation(), fence.token(),
            Objects.requireNonNull(row.get("lease_until", OffsetDateTime.class))));
    }

    public void release(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedEvent event,
            Duration delay,
            String errorCode) {
        requireBoundEvent(tx, permit, event);
        long delayMicros = TenantWorkRepository.durationMicros(delay, "delay");
        errorCode = ReliabilityValues.errorCode(errorCode);
        tenantWork.lock(tx, permit);
        MessageFence fence = event.fence();
        int changed = tx.execute("""
            UPDATE public.outbox_event message
            SET state='PENDING',
                available_at=clock_timestamp()
                  + CAST(? AS bigint)*INTERVAL '1 microsecond',
                lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                delivered_at=NULL,dead_at=NULL,last_error_code=CAST(? AS varchar)
            WHERE message.tenant_id=CAST(? AS uuid)
              AND message.event_id=CAST(? AS uuid)
              AND message.state='DELIVERING'
              AND message.lease_owner=CAST(? AS varchar)
              AND message.lease_generation=CAST(? AS bigint)
              AND message.lease_token=CAST(? AS uuid)
              AND message.lease_until=CAST(? AS timestamptz)
              AND message.lease_until>clock_timestamp()
            """, delayMicros, errorCode, permit.tenantId(), event.eventId(),
            fence.owner(), fence.generation(), fence.token(), fence.leaseUntil());
        requireOne(changed, "outbox release fence was lost");
    }

    public void recordReceipt(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedEvent event,
            DeliveryReceipt receipt) {
        requireBoundEvent(tx, permit, event);
        Objects.requireNonNull(receipt, "receipt");
        MessageFence p = permit.fence();
        MessageFence m = event.fence();
        int changed = tx.execute("""
            INSERT INTO public.outbox_delivery_receipt (
              tenant_id,event_id,permit_owner,permit_generation,permit_token,
              permit_lease_until,message_owner,message_generation,message_token,
              message_lease_until,receipt_id,receipt_digest)
            VALUES (CAST(? AS uuid),CAST(? AS uuid),CAST(? AS varchar),CAST(? AS bigint),
              CAST(? AS uuid),CAST(? AS timestamptz),CAST(? AS varchar),CAST(? AS bigint),
              CAST(? AS uuid),CAST(? AS timestamptz),CAST(? AS varchar),CAST(? AS char(71)))
            ON CONFLICT (tenant_id,event_id) DO NOTHING
            """, permit.tenantId(), event.eventId(), p.owner(), p.generation(), p.token(),
            p.leaseUntil(), m.owner(), m.generation(), m.token(), m.leaseUntil(),
            receipt.receiptId(), receipt.receiptDigest());
        if (changed == 0) {
            DeliveryReceipt stored = findReceipt(tx, permit, event).orElseThrow();
            if (!stored.equals(receipt)) {
                throw new IllegalStateException("outbox receipt conflicts with immutable receipt");
            }
        }
    }

    public void acknowledge(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedEvent event,
            String storedReceiptDigest) {
        requireBoundEvent(tx, permit, event);
        storedReceiptDigest = ReliabilityValues.digest(
            storedReceiptDigest, "storedReceiptDigest");
        tenantWork.lock(tx, permit);
        MessageFence fence = event.fence();
        int changed = tx.execute("""
            UPDATE public.outbox_event message
            SET state='DELIVERED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                delivered_at=clock_timestamp(),dead_at=NULL,last_error_code=NULL
            WHERE message.tenant_id=CAST(? AS uuid)
              AND message.event_id=CAST(? AS uuid)
              AND message.state='DELIVERING'
              AND message.lease_owner=CAST(? AS varchar)
              AND message.lease_generation=CAST(? AS bigint)
              AND message.lease_token=CAST(? AS uuid)
              AND message.lease_until=CAST(? AS timestamptz)
              AND message.lease_until>clock_timestamp()
              AND EXISTS (
                SELECT 1 FROM public.outbox_delivery_receipt receipt
                WHERE receipt.tenant_id=message.tenant_id
                  AND receipt.event_id=message.event_id
                  AND receipt.receipt_digest=CAST(? AS char(71)))
            """, permit.tenantId(), event.eventId(), fence.owner(), fence.generation(),
            fence.token(), fence.leaseUntil(), storedReceiptDigest);
        requireOne(changed, "outbox acknowledgement fence was lost");
    }

    public boolean recordFailure(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedEvent event,
            String errorCode,
            int maxAttempts,
            Duration baseBackoff,
            Duration maxBackoff) {
        requireBoundEvent(tx, permit, event);
        errorCode = ReliabilityValues.errorCode(errorCode);
        requireAttempts(maxAttempts);
        long baseMicros = TenantWorkRepository.durationMicros(baseBackoff, "baseBackoff");
        long maxMicros = TenantWorkRepository.durationMicros(maxBackoff, "maxBackoff");
        if (baseMicros > maxMicros) {
            throw new IllegalArgumentException("baseBackoff exceeds maxBackoff");
        }
        tenantWork.lock(tx, permit);
        MessageFence fence = event.fence();
        Record row = tx.fetchOne("""
            UPDATE public.outbox_event message
            SET state=CASE WHEN message.attempt_count>=CAST(? AS integer)
                           THEN 'DEAD' ELSE 'PENDING' END,
                available_at=CASE WHEN message.attempt_count>=CAST(? AS integer)
                  THEN message.available_at ELSE clock_timestamp()+(
                    LEAST(CAST(? AS bigint),
                      CAST(? AS bigint)*(1::bigint << LEAST(message.attempt_count-1,30)))
                    * (7500 + MOD(ABS(hashtextextended(message.event_id::text,0)),5001))
                    / 10000) * INTERVAL '1 microsecond' END,
                lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                delivered_at=NULL,
                dead_at=CASE WHEN message.attempt_count>=CAST(? AS integer)
                             THEN clock_timestamp() ELSE NULL END,
                last_error_code=CAST(? AS varchar)
            WHERE message.tenant_id=CAST(? AS uuid) AND message.event_id=CAST(? AS uuid)
              AND message.state='DELIVERING' AND message.lease_owner=CAST(? AS varchar)
              AND message.lease_generation=CAST(? AS bigint)
              AND message.lease_token=CAST(? AS uuid)
              AND message.lease_until=CAST(? AS timestamptz)
              AND message.lease_until>clock_timestamp()
            RETURNING state
            """, maxAttempts, maxAttempts, maxMicros, baseMicros, maxAttempts,
            errorCode, permit.tenantId(), event.eventId(), fence.owner(),
            fence.generation(), fence.token(), fence.leaseUntil());
        if (row == null) {
            throw new IllegalStateException("outbox failure fence was lost");
        }
        return "DEAD".equals(row.get("state", String.class));
    }

    public Optional<OffsetDateTime> earliestAvailableAt(
            DSLContext tx, TenantWorkPermit permit) {
        Objects.requireNonNull(tx, "tx");
        tenantWork.lock(tx, permit);
        Record row = tx.fetchOne("""
            SELECT MIN(CASE WHEN state='PENDING' THEN available_at ELSE lease_until END)
              AS available_at
            FROM public.outbox_event
            WHERE tenant_id=CAST(? AS uuid) AND state IN ('PENDING','DELIVERING')
            """, permit.tenantId());
        return row == null
            ? Optional.empty()
            : Optional.ofNullable(row.get("available_at", OffsetDateTime.class));
    }

    private LeasedEvent toEvent(Record row) {
        JSONB payload = Objects.requireNonNull(row.get("payload", JSONB.class));
        return new LeasedEvent(
            row.get("tenant_id", UUID.class), row.get("event_id", UUID.class),
            row.get("destination", String.class), row.get("payload_schema", String.class),
            payload.data(), row.get("attempt_count", Integer.class), new MessageFence(
                row.get("lease_owner", String.class), row.get("lease_generation", Long.class),
                row.get("lease_token", UUID.class), row.get("lease_until", OffsetDateTime.class)));
    }

    private void lockMessage(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedEvent event,
            Duration requiredWindow) {
        Objects.requireNonNull(requiredWindow, "requiredWindow");
        long requiredMicros = requiredWindow.isZero()
            ? 0
            : TenantWorkRepository.durationMicros(requiredWindow, "requiredWindow");
        MessageFence fence = event.fence();
        Record row = tx.fetchOne("""
            WITH decision AS (SELECT clock_timestamp() AS at)
            SELECT message.event_id
            FROM public.outbox_event message,decision
            WHERE message.tenant_id=CAST(? AS uuid)
              AND message.event_id=CAST(? AS uuid)
              AND message.state='DELIVERING'
              AND message.lease_owner=CAST(? AS varchar)
              AND message.lease_generation=CAST(? AS bigint)
              AND message.lease_token=CAST(? AS uuid)
              AND message.lease_until=CAST(? AS timestamptz)
              AND message.lease_until > decision.at
                + CAST(? AS bigint)*INTERVAL '1 microsecond'
              AND CAST(? AS timestamptz) > decision.at
                + CAST(? AS bigint)*INTERVAL '1 microsecond'
            FOR UPDATE OF message
            """, permit.tenantId(), event.eventId(), fence.owner(), fence.generation(),
            fence.token(), fence.leaseUntil(), requiredMicros,
            permit.fence().leaseUntil(), requiredMicros);
        if (row == null) {
            throw new IllegalStateException("outbox delivery fence is not live for the timeout");
        }
    }

    private static LeasedEvent withFence(LeasedEvent event, MessageFence fence) {
        return new LeasedEvent(
            event.tenantId(), event.eventId(), event.destination(), event.payloadSchema(),
            event.payload(), event.attemptCount(), fence);
    }

    private static void requireBoundEvent(
            DSLContext tx, TenantWorkPermit permit, LeasedEvent event) {
        Objects.requireNonNull(tx, "tx");
        TenantWorkRepository.requirePermit(permit);
        Objects.requireNonNull(event, "event");
        if (!permit.tenantId().equals(event.tenantId())) {
            throw new IllegalArgumentException("event tenant does not match permit");
        }
    }

    static void requireLimit(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
    }

    static void requireAttempts(int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
    }

    static void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }
}
