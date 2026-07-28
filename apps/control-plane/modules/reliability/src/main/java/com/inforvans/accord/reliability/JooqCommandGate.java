package com.inforvans.accord.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

public final class JooqCommandGate {
    private static final int MAX_HEADER_JSON_BYTES = 65_536;
    private static final Pattern FINGERPRINT =
        Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final ObjectMapper mapper;

    public JooqCommandGate() {
        this(new ObjectMapper());
    }

    JooqCommandGate(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public Claim claim(
            DSLContext tx,
            CommandKey key,
            String requestFingerprint,
            String owner,
            Duration leaseDuration) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(key, "key");
        requireFingerprint(requestFingerprint);
        owner = CommandKey.requireBounded(owner, "owner", 255);
        long leaseMicros = requireDurationMicros(leaseDuration, "leaseDuration");
        UUID initialToken = UUID.randomUUID();

        for (int attempt = 0; attempt < 2; attempt++) {
            Record inserted = tx.fetchOne("""
                INSERT INTO idempotency_result (
                  tenant_id, actor_id, route_key, idempotency_key,
                  request_fingerprint, state, claim_owner, claim_generation,
                  claim_token, lease_until, expires_at
                ) VALUES (
                  ?, ?, ?, ?, ?, 'STARTED', ?, 1, ?,
                  '-infinity'::timestamptz, '-infinity'::timestamptz
                )
                ON CONFLICT DO NOTHING
                RETURNING claim_generation
                """,
                key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey(),
                requestFingerprint, owner, initialToken);
            if (inserted != null) {
                OffsetDateTime databaseNow = databaseNow(tx);
                OffsetDateTime deadline = setInitialDeadline(
                    tx, key, owner, initialToken, databaseNow, leaseMicros);
                return new Claim.Acquired(
                    new ClaimLease(owner, 1, initialToken, deadline));
            }

            Record row = lockCommand(tx, key);
            if (row == null) {
                if (attempt == 0) {
                    continue;
                }
                throw new IllegalStateException(
                    "idempotency row disappeared twice during claim");
            }
            OffsetDateTime databaseNow = databaseNow(tx);
            String storedFingerprint = required(
                row, "request_fingerprint", String.class);
            if (!storedFingerprint.equals(requestFingerprint)) {
                return new Claim.RequestConflict();
            }

            String state = required(row, "state", String.class);
            if ("COMPLETED".equals(state)) {
                return new Claim.Replay(readStoredResult(row));
            }
            if (!"STARTED".equals(state)) {
                throw new IllegalStateException("stored idempotency state is invalid");
            }

            String currentOwner = required(row, "claim_owner", String.class);
            long currentGeneration = required(row, "claim_generation", Long.class);
            UUID currentToken = required(row, "claim_token", UUID.class);
            OffsetDateTime currentDeadline = required(
                row, "lease_until", OffsetDateTime.class);
            if (currentDeadline.isAfter(databaseNow)) {
                return new Claim.InProgress(currentDeadline);
            }

            long nextGeneration;
            try {
                nextGeneration = Math.addExact(currentGeneration, 1);
            } catch (ArithmeticException error) {
                throw new IllegalStateException("claim generation is exhausted", error);
            }
            UUID nextToken = UUID.randomUUID();
            OffsetDateTime nextDeadline = takeOver(
                tx,
                key,
                currentOwner,
                currentGeneration,
                currentToken,
                currentDeadline,
                owner,
                nextGeneration,
                nextToken,
                databaseNow,
                leaseMicros);
            return new Claim.Acquired(
                new ClaimLease(owner, nextGeneration, nextToken, nextDeadline));
        }
        throw new IllegalStateException("claim retry loop exhausted");
    }

    public ClaimLease renew(
            DSLContext tx,
            CommandKey key,
            ClaimLease lease,
            Duration leaseExtension) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(lease, "lease");
        long extensionMicros = requireDurationMicros(leaseExtension, "leaseExtension");

        if (lockCommand(tx, key) == null) {
            throw new IllegalStateException("idempotency lease is absent");
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        Record renewed = tx.fetchOne("""
            WITH extension AS MATERIALIZED (
              SELECT ? * INTERVAL '1 microsecond' AS amount
            )
            UPDATE idempotency_result AS stored
            SET lease_until = stored.lease_until + extension.amount,
                expires_at = stored.lease_until + extension.amount
            FROM extension
            WHERE stored.tenant_id=? AND stored.actor_id=?
              AND stored.route_key=? AND stored.idempotency_key=?
              AND stored.state='STARTED'
              AND stored.claim_owner=? AND stored.claim_generation=?
              AND stored.claim_token=?
              AND stored.lease_until=CAST(? AS timestamptz)
              AND stored.lease_until>CAST(? AS timestamptz)
            RETURNING stored.lease_until
            """,
            extensionMicros,
            key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey(),
            lease.owner(), lease.generation(), lease.token(), lease.leaseUntil(), databaseNow);
        if (renewed == null) {
            throw new IllegalStateException("idempotency lease cannot be renewed");
        }
        OffsetDateTime deadline = required(renewed, "lease_until", OffsetDateTime.class);
        if (!deadline.isAfter(lease.leaseUntil())) {
            throw new IllegalStateException("renewal did not extend the stored deadline");
        }
        return new ClaimLease(lease.owner(), lease.generation(), lease.token(), deadline);
    }

    public void complete(
            DSLContext tx,
            CommandKey key,
            ClaimLease lease,
            StoredHttpResult result,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            Duration resultTtl) {
        requireCompletionInputs(tx, key, lease, result);
        aggregateType = CommandKey.requireBounded(aggregateType, "aggregateType", 64);
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("aggregateVersion must be positive");
        }
        long ttlMicros = requireDurationMicros(resultTtl, "resultTtl");
        JSONB encodedHeaders = encodeHeaders(result.headers());

        if (lockCommand(tx, key) == null) {
            throw new IllegalStateException("idempotency lease is absent");
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        Record completed = tx.fetchOne("""
            WITH completion AS MATERIALIZED (
              SELECT CAST(? AS timestamptz) AS completed_at,
                     CAST(? AS timestamptz)
                       + (? * INTERVAL '1 microsecond') AS expires_at
            )
            UPDATE idempotency_result AS stored
            SET state='COMPLETED',
                claim_owner=NULL,
                claim_token=NULL,
                lease_until=NULL,
                response_status=?,
                response_headers=CAST(? AS jsonb),
                response_body=?,
                aggregate_type=?,
                aggregate_id=?,
                aggregate_version=?,
                completed_at=completion.completed_at,
                expires_at=completion.expires_at
            FROM completion
            WHERE stored.tenant_id=? AND stored.actor_id=?
              AND stored.route_key=? AND stored.idempotency_key=?
              AND stored.state='STARTED'
              AND stored.claim_owner=? AND stored.claim_generation=?
              AND stored.claim_token=?
              AND stored.lease_until=CAST(? AS timestamptz)
              AND stored.lease_until>completion.completed_at
              AND EXISTS (
                SELECT 1 FROM aggregate_head AS aggregate
                WHERE aggregate.tenant_id=stored.tenant_id
                  AND aggregate.aggregate_type=?
                  AND aggregate.aggregate_id=?
                  AND aggregate.version=?
              )
            RETURNING stored.completed_at
            """,
            databaseNow, databaseNow, ttlMicros,
            result.status(), encodedHeaders, result.body(),
            aggregateType, aggregateId, aggregateVersion,
            key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey(),
            lease.owner(), lease.generation(), lease.token(), lease.leaseUntil(),
            aggregateType, aggregateId, aggregateVersion);
        if (completed == null) {
            throw new IllegalStateException(
                "idempotency lease is stale, expired, lost, or completed");
        }
    }

    public void completeRejection(
            DSLContext tx,
            CommandKey key,
            ClaimLease lease,
            StoredHttpResult result,
            Duration resultTtl) {
        requireCompletionInputs(tx, key, lease, result);
        if (result.status() != 404 && result.status() != 412 && result.status() != 422) {
            throw new IllegalArgumentException(
                "persistent rejection status must be 404, 412, or 422");
        }
        long ttlMicros = requireDurationMicros(resultTtl, "resultTtl");
        JSONB encodedHeaders = encodeHeaders(result.headers());

        if (lockCommand(tx, key) == null) {
            throw new IllegalStateException("idempotency lease is absent");
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        Record completed = tx.fetchOne("""
            WITH completion AS MATERIALIZED (
              SELECT CAST(? AS timestamptz) AS completed_at,
                     CAST(? AS timestamptz)
                       + (? * INTERVAL '1 microsecond') AS expires_at
            )
            UPDATE idempotency_result AS stored
            SET state='COMPLETED',
                claim_owner=NULL,
                claim_token=NULL,
                lease_until=NULL,
                response_status=?,
                response_headers=CAST(? AS jsonb),
                response_body=?,
                aggregate_type=NULL,
                aggregate_id=NULL,
                aggregate_version=NULL,
                completed_at=completion.completed_at,
                expires_at=completion.expires_at
            FROM completion
            WHERE stored.tenant_id=? AND stored.actor_id=?
              AND stored.route_key=? AND stored.idempotency_key=?
              AND stored.state='STARTED'
              AND stored.claim_owner=? AND stored.claim_generation=?
              AND stored.claim_token=?
              AND stored.lease_until=CAST(? AS timestamptz)
              AND stored.lease_until>completion.completed_at
            RETURNING stored.completed_at
            """,
            databaseNow, databaseNow, ttlMicros,
            result.status(), encodedHeaders, result.body(),
            key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey(),
            lease.owner(), lease.generation(), lease.token(), lease.leaseUntil());
        if (completed == null) {
            throw new IllegalStateException(
                "idempotency lease is stale, expired, lost, or completed");
        }
    }

    public long advance(
            DSLContext tx,
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            ExpectedVersion expected) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(tenantId, "tenantId");
        aggregateType = CommandKey.requireBounded(aggregateType, "aggregateType", 64);
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(expected, "expected");
        if (expected.value() == Long.MAX_VALUE) {
            Long actual = currentVersion(
                tx, tenantId, aggregateType, aggregateId);
            throw new VersionConflict(expected.value(), actual);
        }
        long nextVersion = expected.value() + 1;

        Record changed;
        if (expected.value() == 0) {
            changed = tx.fetchOne("""
                INSERT INTO aggregate_head (
                  tenant_id, aggregate_type, aggregate_id, version, updated_at
                ) VALUES (?, ?, ?, 1, clock_timestamp())
                ON CONFLICT DO NOTHING
                RETURNING version
                """, tenantId, aggregateType, aggregateId);
        } else {
            changed = tx.fetchOne("""
                UPDATE aggregate_head
                SET version=?, updated_at=clock_timestamp()
                WHERE tenant_id=? AND aggregate_type=? AND aggregate_id=? AND version=?
                RETURNING version
                """,
                nextVersion, tenantId, aggregateType, aggregateId, expected.value());
        }
        if (changed != null) {
            return required(changed, "version", Long.class);
        }

        Long actual = currentVersion(tx, tenantId, aggregateType, aggregateId);
        throw new VersionConflict(expected.value(), actual);
    }

    private static Long currentVersion(
            DSLContext tx, UUID tenantId, String aggregateType, UUID aggregateId) {
        Record current = tx.fetchOne("""
            SELECT version FROM aggregate_head
            WHERE tenant_id=? AND aggregate_type=? AND aggregate_id=?
            """, tenantId, aggregateType, aggregateId);
        return current == null ? null : current.get("version", Long.class);
    }

    private OffsetDateTime setInitialDeadline(
            DSLContext tx,
            CommandKey key,
            String owner,
            UUID token,
            OffsetDateTime databaseNow,
            long leaseMicros) {
        Record updated = tx.fetchOne("""
            WITH lease AS MATERIALIZED (
              SELECT CAST(? AS timestamptz)
                + (? * INTERVAL '1 microsecond') AS deadline
            )
            UPDATE idempotency_result AS stored
            SET lease_until=lease.deadline, expires_at=lease.deadline
            FROM lease
            WHERE stored.tenant_id=? AND stored.actor_id=?
              AND stored.route_key=? AND stored.idempotency_key=?
              AND stored.state='STARTED'
              AND stored.claim_owner=? AND stored.claim_generation=1
              AND stored.claim_token=?
              AND stored.lease_until='-infinity'::timestamptz
            RETURNING stored.lease_until
            """,
            databaseNow, leaseMicros,
            key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey(),
            owner, token);
        if (updated == null) {
            throw new IllegalStateException("new idempotency lease could not be initialized");
        }
        return required(updated, "lease_until", OffsetDateTime.class);
    }

    private OffsetDateTime takeOver(
            DSLContext tx,
            CommandKey key,
            String currentOwner,
            long currentGeneration,
            UUID currentToken,
            OffsetDateTime currentDeadline,
            String nextOwner,
            long nextGeneration,
            UUID nextToken,
            OffsetDateTime databaseNow,
            long leaseMicros) {
        Record updated = tx.fetchOne("""
            WITH lease AS MATERIALIZED (
              SELECT CAST(? AS timestamptz)
                + (? * INTERVAL '1 microsecond') AS deadline
            )
            UPDATE idempotency_result AS stored
            SET claim_owner=?,
                claim_generation=?,
                claim_token=?,
                lease_until=lease.deadline,
                expires_at=lease.deadline
            FROM lease
            WHERE stored.tenant_id=? AND stored.actor_id=?
              AND stored.route_key=? AND stored.idempotency_key=?
              AND stored.state='STARTED'
              AND stored.claim_owner=? AND stored.claim_generation=?
              AND stored.claim_token=?
              AND stored.lease_until=CAST(? AS timestamptz)
              AND stored.lease_until<=CAST(? AS timestamptz)
            RETURNING stored.lease_until
            """,
            databaseNow, leaseMicros,
            nextOwner, nextGeneration, nextToken,
            key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey(),
            currentOwner, currentGeneration, currentToken, currentDeadline, databaseNow);
        if (updated == null) {
            throw new IllegalStateException("locked idempotency lease changed unexpectedly");
        }
        return required(updated, "lease_until", OffsetDateTime.class);
    }

    private Record lockCommand(DSLContext tx, CommandKey key) {
        return tx.fetchOne("""
            SELECT request_fingerprint, state, claim_owner, claim_generation,
                   claim_token, lease_until, response_status,
                   response_headers, response_body
            FROM idempotency_result
            WHERE tenant_id=? AND actor_id=? AND route_key=? AND idempotency_key=?
            FOR UPDATE
            """, key.tenantId(), key.actorId(), key.routeKey(), key.idempotencyKey());
    }

    private static OffsetDateTime databaseNow(DSLContext tx) {
        Record row = tx.fetchOne("SELECT clock_timestamp() AS database_now");
        return required(row, "database_now", OffsetDateTime.class);
    }

    private StoredHttpResult readStoredResult(Record row) {
        int status = required(row, "response_status", Integer.class);
        JSONB encoded = required(row, "response_headers", JSONB.class);
        String body = required(row, "response_body", String.class);
        try {
            JsonNode document = mapper.readTree(encoded.data());
            if (document == null || !document.isObject()) {
                throw new IllegalStateException("stored response headers are not an object");
            }
            Map<String, String> headers = new HashMap<>();
            for (Map.Entry<String, JsonNode> field : document.properties()) {
                if (!field.getValue().isTextual()) {
                    throw new IllegalStateException(
                        "stored response header values must be strings");
                }
                headers.put(field.getKey(), field.getValue().textValue());
            }
            return new StoredHttpResult(status, headers, body);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalStateException("stored response headers are invalid", error);
        }
    }

    private JSONB encodeHeaders(Map<String, String> headers) {
        try {
            byte[] encoded = mapper.writeValueAsBytes(headers);
            if (encoded.length > MAX_HEADER_JSON_BYTES) {
                throw new IllegalArgumentException(
                    "serialized response headers exceed 64 KiB");
            }
            return JSONB.valueOf(new String(encoded, StandardCharsets.UTF_8));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("response headers are not serializable", error);
        }
    }

    private static void requireCompletionInputs(
            DSLContext tx,
            CommandKey key,
            ClaimLease lease,
            StoredHttpResult result) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(result, "result");
    }

    private static void requireFingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "request fingerprint must be canonical SHA-256");
        }
    }

    private static long requireDurationMicros(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        long nanoseconds;
        try {
            nanoseconds = value.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " is not representable", error);
        }
        if (nanoseconds <= 0 || nanoseconds % 1_000 != 0) {
            throw new IllegalArgumentException(
                name + " must be representable in PostgreSQL microseconds");
        }
        return nanoseconds / 1_000;
    }

    private static <T> T required(Record row, String field, Class<T> type) {
        if (row == null) {
            throw new IllegalStateException("required database row is absent");
        }
        T value = row.get(field, type);
        if (value == null) {
            throw new IllegalStateException(field + " is unexpectedly null");
        }
        return value;
    }
}
