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

public final class InboxRepository {
    private final TenantWorkRepository tenantWork = new TenantWorkRepository();

    public List<LeasedInboxMessage> lease(
            DSLContext tx,
            TenantWorkPermit permit,
            String owner,
            int limit,
            Duration leaseDuration) {
        Objects.requireNonNull(tx, "tx");
        TenantWorkRepository.requirePermit(permit);
        owner = CommandKey.requireBounded(owner, "owner", 255);
        OutboxRepository.requireLimit(limit);
        long leaseMicros = TenantWorkRepository.durationMicros(
            leaseDuration, "leaseDuration");
        tenantWork.lock(tx, permit);
        return tx.fetch("""
            WITH decision AS (SELECT clock_timestamp() AS at), candidates AS (
              SELECT message.source,message.source_message_id
              FROM public.inbox_message message,decision
              WHERE message.tenant_id=CAST(? AS uuid)
                AND ((message.state='PENDING' AND message.available_at<=decision.at)
                  OR (message.state='PROCESSING' AND message.lease_until<=decision.at))
              ORDER BY message.available_at,message.source,message.source_message_id
              FOR UPDATE OF message SKIP LOCKED LIMIT ?
            )
            UPDATE public.inbox_message message
            SET state='PROCESSING',attempt_count=message.attempt_count+1,
                lease_owner=CAST(? AS varchar),lease_generation=message.lease_generation+1,
                lease_token=gen_random_uuid(),lease_until=LEAST(
                  decision.at+CAST(? AS bigint)*INTERVAL '1 microsecond',
                  CAST(? AS timestamptz)),completed_at=NULL,dead_at=NULL,last_error_code=NULL
            FROM candidates,decision
            WHERE message.tenant_id=CAST(? AS uuid)
              AND message.source=candidates.source
              AND message.source_message_id=candidates.source_message_id
            RETURNING message.tenant_id,message.source,message.source_message_id,
              message.request_digest,message.handler_key,message.payload_schema,message.payload,
              message.attempt_count,message.lease_owner,message.lease_generation,
              message.lease_token,message.lease_until
            """, permit.tenantId(), limit, owner, leaseMicros,
            permit.fence().leaseUntil(), permit.tenantId()).map(this::toMessage);
    }

    public Optional<HandlerReceipt> findReceipt(
            DSLContext tx, TenantWorkPermit permit, LeasedInboxMessage message) {
        requireBoundMessage(tx, permit, message);
        tenantWork.lock(tx, permit);
        Record row = tx.fetchOne("""
            SELECT receipt_id,receipt_digest FROM public.inbox_handler_receipt
            WHERE tenant_id=CAST(? AS uuid) AND source=CAST(? AS varchar)
              AND source_message_id=CAST(? AS varchar)
            """, permit.tenantId(), message.source(), message.sourceMessageId());
        return row == null ? Optional.empty() : Optional.of(new HandlerReceipt(
            row.get("receipt_id", String.class), row.get("receipt_digest", String.class)));
    }

    public void lockMessage(
            DSLContext tx, TenantWorkPermit permit, LeasedInboxMessage message) {
        requireBoundMessage(tx, permit, message);
        tenantWork.lock(tx, permit);
        MessageFence fence = message.fence();
        Record row = tx.fetchOne("""
            SELECT source FROM public.inbox_message
            WHERE tenant_id=CAST(? AS uuid) AND source=CAST(? AS varchar)
              AND source_message_id=CAST(? AS varchar) AND state='PROCESSING'
              AND lease_owner=CAST(? AS varchar) AND lease_generation=CAST(? AS bigint)
              AND lease_token=CAST(? AS uuid) AND lease_until=CAST(? AS timestamptz)
              AND lease_until>clock_timestamp() FOR UPDATE
            """, permit.tenantId(), message.source(), message.sourceMessageId(),
            fence.owner(), fence.generation(), fence.token(), fence.leaseUntil());
        if (row == null) {
            throw new IllegalStateException("inbox message fence was lost");
        }
    }

    public LeasedInboxMessage renew(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedInboxMessage message,
            Duration extension) {
        requireBoundMessage(tx, permit, message);
        long extensionMicros = TenantWorkRepository.durationMicros(extension, "extension");
        tenantWork.lock(tx, permit);
        MessageFence fence = message.fence();
        Record row = tx.fetchOne("""
            UPDATE public.inbox_message item
            SET lease_until=LEAST(
              item.lease_until + CAST(? AS bigint)*INTERVAL '1 microsecond',
              CAST(? AS timestamptz))
            WHERE item.tenant_id=CAST(? AS uuid)
              AND item.source=CAST(? AS varchar)
              AND item.source_message_id=CAST(? AS varchar)
              AND item.state='PROCESSING'
              AND item.lease_owner=CAST(? AS varchar)
              AND item.lease_generation=CAST(? AS bigint)
              AND item.lease_token=CAST(? AS uuid)
              AND item.lease_until=CAST(? AS timestamptz)
              AND item.lease_until>clock_timestamp()
              AND CAST(? AS timestamptz)>item.lease_until
            RETURNING item.lease_until
            """, extensionMicros, permit.fence().leaseUntil(), permit.tenantId(),
            message.source(), message.sourceMessageId(), fence.owner(),
            fence.generation(), fence.token(), fence.leaseUntil(),
            permit.fence().leaseUntil());
        if (row == null) {
            throw new IllegalStateException("inbox renewal fence was lost");
        }
        return withFence(message, new MessageFence(
            fence.owner(), fence.generation(), fence.token(),
            Objects.requireNonNull(row.get("lease_until", OffsetDateTime.class))));
    }

    public void release(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedInboxMessage message,
            Duration delay,
            String errorCode) {
        requireBoundMessage(tx, permit, message);
        long delayMicros = TenantWorkRepository.durationMicros(delay, "delay");
        errorCode = ReliabilityValues.errorCode(errorCode);
        tenantWork.lock(tx, permit);
        MessageFence fence = message.fence();
        int changed = tx.execute("""
            UPDATE public.inbox_message item
            SET state='PENDING',
                available_at=clock_timestamp()
                  + CAST(? AS bigint)*INTERVAL '1 microsecond',
                lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                completed_at=NULL,dead_at=NULL,last_error_code=CAST(? AS varchar)
            WHERE item.tenant_id=CAST(? AS uuid)
              AND item.source=CAST(? AS varchar)
              AND item.source_message_id=CAST(? AS varchar)
              AND item.state='PROCESSING'
              AND item.lease_owner=CAST(? AS varchar)
              AND item.lease_generation=CAST(? AS bigint)
              AND item.lease_token=CAST(? AS uuid)
              AND item.lease_until=CAST(? AS timestamptz)
              AND item.lease_until>clock_timestamp()
            """, delayMicros, errorCode, permit.tenantId(), message.source(),
            message.sourceMessageId(), fence.owner(), fence.generation(),
            fence.token(), fence.leaseUntil());
        OutboxRepository.requireOne(changed, "inbox release fence was lost");
    }

    public void recordReceipt(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedInboxMessage message,
            HandlerReceipt receipt) {
        requireBoundMessage(tx, permit, message);
        Objects.requireNonNull(receipt, "receipt");
        MessageFence p = permit.fence();
        MessageFence m = message.fence();
        int changed = tx.execute("""
            INSERT INTO public.inbox_handler_receipt (
              tenant_id,source,source_message_id,permit_owner,permit_generation,
              permit_token,permit_lease_until,message_owner,message_generation,
              message_token,message_lease_until,receipt_id,receipt_digest)
            VALUES (CAST(? AS uuid),CAST(? AS varchar),CAST(? AS varchar),
              CAST(? AS varchar),CAST(? AS bigint),CAST(? AS uuid),CAST(? AS timestamptz),
              CAST(? AS varchar),CAST(? AS bigint),CAST(? AS uuid),CAST(? AS timestamptz),
              CAST(? AS varchar),CAST(? AS char(71)))
            ON CONFLICT (tenant_id,source,source_message_id) DO NOTHING
            """, permit.tenantId(), message.source(), message.sourceMessageId(),
            p.owner(), p.generation(), p.token(), p.leaseUntil(),
            m.owner(), m.generation(), m.token(), m.leaseUntil(),
            receipt.receiptId(), receipt.receiptDigest());
        if (changed == 0) {
            HandlerReceipt stored = findReceipt(tx, permit, message).orElseThrow();
            if (!stored.equals(receipt)) {
                throw new IllegalStateException("inbox receipt conflicts with immutable receipt");
            }
        }
    }

    public void complete(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedInboxMessage message,
            String storedReceiptDigest) {
        requireBoundMessage(tx, permit, message);
        storedReceiptDigest = ReliabilityValues.digest(
            storedReceiptDigest, "storedReceiptDigest");
        MessageFence fence = message.fence();
        int changed = tx.execute("""
            UPDATE public.inbox_message item
            SET state='COMPLETED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                completed_at=clock_timestamp(),dead_at=NULL,last_error_code=NULL
            WHERE item.tenant_id=CAST(? AS uuid) AND item.source=CAST(? AS varchar)
              AND item.source_message_id=CAST(? AS varchar) AND item.state='PROCESSING'
              AND item.lease_owner=CAST(? AS varchar)
              AND item.lease_generation=CAST(? AS bigint)
              AND item.lease_token=CAST(? AS uuid)
              AND item.lease_until=CAST(? AS timestamptz)
              AND item.lease_until>clock_timestamp()
              AND EXISTS (SELECT 1 FROM public.inbox_handler_receipt receipt
                WHERE receipt.tenant_id=item.tenant_id AND receipt.source=item.source
                  AND receipt.source_message_id=item.source_message_id
                  AND receipt.receipt_digest=CAST(? AS char(71)))
            """, permit.tenantId(), message.source(), message.sourceMessageId(),
            fence.owner(), fence.generation(), fence.token(), fence.leaseUntil(),
            storedReceiptDigest);
        OutboxRepository.requireOne(changed, "inbox completion fence was lost");
    }

    public boolean recordFailure(
            DSLContext tx,
            TenantWorkPermit permit,
            LeasedInboxMessage message,
            String errorCode,
            int maxAttempts,
            Duration baseBackoff,
            Duration maxBackoff) {
        requireBoundMessage(tx, permit, message);
        errorCode = ReliabilityValues.errorCode(errorCode);
        OutboxRepository.requireAttempts(maxAttempts);
        long baseMicros = TenantWorkRepository.durationMicros(baseBackoff, "baseBackoff");
        long maxMicros = TenantWorkRepository.durationMicros(maxBackoff, "maxBackoff");
        if (baseMicros > maxMicros) {
            throw new IllegalArgumentException("baseBackoff exceeds maxBackoff");
        }
        tenantWork.lock(tx, permit);
        MessageFence fence = message.fence();
        Record row = tx.fetchOne("""
            UPDATE public.inbox_message item
            SET state=CASE WHEN item.attempt_count>=CAST(? AS integer)
                           THEN 'DEAD' ELSE 'PENDING' END,
                available_at=CASE WHEN item.attempt_count>=CAST(? AS integer)
                  THEN item.available_at ELSE clock_timestamp()+(
                    LEAST(CAST(? AS bigint),CAST(? AS bigint)*
                      (1::bigint << LEAST(item.attempt_count-1,30)))
                    * (7500+MOD(ABS(hashtextextended(
                      item.source||':'||item.source_message_id,0)),5001))/10000)
                    * INTERVAL '1 microsecond' END,
                lease_owner=NULL,lease_token=NULL,lease_until=NULL,completed_at=NULL,
                dead_at=CASE WHEN item.attempt_count>=CAST(? AS integer)
                             THEN clock_timestamp() ELSE NULL END,
                last_error_code=CAST(? AS varchar)
            WHERE item.tenant_id=CAST(? AS uuid) AND item.source=CAST(? AS varchar)
              AND item.source_message_id=CAST(? AS varchar) AND item.state='PROCESSING'
              AND item.lease_owner=CAST(? AS varchar)
              AND item.lease_generation=CAST(? AS bigint)
              AND item.lease_token=CAST(? AS uuid)
              AND item.lease_until=CAST(? AS timestamptz)
              AND item.lease_until>clock_timestamp() RETURNING state
            """, maxAttempts, maxAttempts, maxMicros, baseMicros, maxAttempts,
            errorCode, permit.tenantId(), message.source(), message.sourceMessageId(),
            fence.owner(), fence.generation(), fence.token(), fence.leaseUntil());
        if (row == null) {
            throw new IllegalStateException("inbox failure fence was lost");
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
            FROM public.inbox_message WHERE tenant_id=CAST(? AS uuid)
              AND state IN ('PENDING','PROCESSING')
            """, permit.tenantId());
        return row == null
            ? Optional.empty()
            : Optional.ofNullable(row.get("available_at", OffsetDateTime.class));
    }

    private LeasedInboxMessage toMessage(Record row) {
        JSONB payload = Objects.requireNonNull(row.get("payload", JSONB.class));
        return new LeasedInboxMessage(row.get("tenant_id", UUID.class),
            row.get("source", String.class), row.get("source_message_id", String.class),
            row.get("request_digest", String.class), row.get("handler_key", String.class),
            row.get("payload_schema", String.class), payload.data(),
            row.get("attempt_count", Integer.class), new MessageFence(
                row.get("lease_owner", String.class), row.get("lease_generation", Long.class),
                row.get("lease_token", UUID.class), row.get("lease_until", OffsetDateTime.class)));
    }

    private static LeasedInboxMessage withFence(
            LeasedInboxMessage message, MessageFence fence) {
        return new LeasedInboxMessage(
            message.tenantId(), message.source(), message.sourceMessageId(),
            message.requestDigest(), message.handlerKey(), message.payloadSchema(),
            message.payload(), message.attemptCount(), fence);
    }

    private static void requireBoundMessage(
            DSLContext tx, TenantWorkPermit permit, LeasedInboxMessage message) {
        Objects.requireNonNull(tx, "tx");
        TenantWorkRepository.requirePermit(permit);
        Objects.requireNonNull(message, "message");
        if (!permit.tenantId().equals(message.tenantId())) {
            throw new IllegalArgumentException("inbox tenant does not match permit");
        }
    }
}
