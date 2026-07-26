package com.inforvans.accord.reliability;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqExternalIntentStore {
    public ExternalIntentRegistration record(
            DSLContext tx, ExternalIntentDefinition definition) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(definition, "definition");
        Record result = tx.fetchOne("""
            SELECT disposition,
                   result_tenant_id AS tenant_id,
                   result_intent_id AS intent_id,
                   result_root_intent_id AS root_intent_id,
                   result_attempt_ordinal AS attempt_ordinal,
                   result_global_idempotency_key AS global_idempotency_key,
                   result_state AS state
            FROM accord_security.record_external_intent(
              ?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            definition.tenantId(), definition.intentId(),
            definition.logicalActionKey(), definition.scopeType(), definition.scopeId(),
            definition.provider(), definition.providerInstallationId(),
            definition.providerRepositoryId(), definition.operation(),
            definition.requestReferenceType(), definition.requestReferenceId(),
            definition.requestReferenceVersion(), definition.requestDigest());
        if (result == null) {
            throw new IllegalStateException("external intent registration returned no result");
        }
        return switch (required(result, "disposition", String.class)) {
            case "CREATED" -> new ExternalIntentRegistration.Created(requiredRef(result));
            case "DUPLICATE" -> new ExternalIntentRegistration.Duplicate(requiredRef(result));
            case "CONFLICT" -> new ExternalIntentRegistration.Conflict();
            default -> throw new IllegalStateException("unknown external intent registration");
        };
    }

    public ExternalIntentRef createSuccessor(
            DSLContext tx, UUID tenantId, UUID predecessorIntentId) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(predecessorIntentId, "predecessorIntentId");
        UUID successorId = UUID.randomUUID();
        Record result = tx.fetchOne("""
            SELECT result_tenant_id AS tenant_id,
                   result_intent_id AS intent_id,
                   result_root_intent_id AS root_intent_id,
                   result_attempt_ordinal AS attempt_ordinal,
                   result_global_idempotency_key AS global_idempotency_key,
                   result_state AS state
            FROM accord_security.create_external_intent_successor(?,?,?)
            """, tenantId, predecessorIntentId, successorId);
        if (result == null) {
            throw new IllegalStateException("successor creation returned no result");
        }
        return requiredRef(result);
    }

    public ExecutionClaim claimExecution(
            DSLContext tx,
            UUID tenantId,
            UUID intentId,
            String owner,
            Duration lease) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        owner = CommandKey.requireBounded(owner, "owner", 255);
        long leaseMicros = ReliabilityValues.leaseMicros(lease, "lease");
        UUID candidateToken = UUID.randomUUID();
        Record result = tx.fetchOne("""
            SELECT * FROM accord_security.claim_external_intent_execution(
              ?,?,?,?,?)
            """, tenantId, intentId, owner, leaseMicros, candidateToken);
        if (result == null) {
            return new ExecutionClaim.Missing();
        }
        return switch (required(result, "disposition", String.class)) {
            case "NOT_EXECUTABLE" -> new ExecutionClaim.NotExecutable(state(result));
            case "ACQUIRED" -> {
                if (!candidateToken.equals(required(
                        result, "execution_token", UUID.class))
                        || !owner.equals(required(
                            result, "execution_owner", String.class))) {
                    throw new IllegalStateException(
                        "execution claim returned a mismatched capability");
                }
                yield new ExecutionClaim.Acquired(writePermit(result));
            }
            default -> throw new IllegalStateException(
                "unknown execution claim disposition");
        };
    }

    public ExternalWritePermit renewExecution(
            DSLContext tx, ExternalWritePermit permit, Duration extension) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(permit, "permit");
        long extensionMicros = ReliabilityValues.leaseMicros(extension, "extension");
        Record renewed = fetchFenced(
            () -> tx.fetchOne("""
                SELECT * FROM accord_security.renew_external_intent_execution(
                  ?,?,?,?,?,CAST(? AS timestamptz),?,?,?,?,?,?,?,?,?,?)
                """,
                permit.tenantId(), permit.intentId(), permit.owner(), permit.generation(),
                permit.token(), permit.leaseUntil(), permit.globalIdempotencyKey(),
                permit.provider(), permit.providerInstallationId(),
                permit.providerRepositoryId(), permit.operation(),
                permit.requestReferenceType(), permit.requestReferenceId(),
                permit.requestReferenceVersion(), permit.requestDigest(), extensionMicros),
            "execution renewal fence was lost");
        return renewedExecutionPermit(
            permit, required(
                renewed, "execution_lease_until", OffsetDateTime.class));
    }

    public void markExecutionOutcomeUnknown(
            DSLContext tx,
            ExternalWritePermit permit,
            ExecutionResolution.OutcomeUnknown resolution) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(resolution, "resolution");
        runFenced(
            () -> tx.execute("""
                SELECT accord_security.mark_external_intent_execution_unknown(
                  ?,?,?,?,?,CAST(? AS timestamptz),?,?,?,?,?,?,?,?,?,?,?)
                """,
                permit.tenantId(), permit.intentId(), permit.owner(), permit.generation(),
                permit.token(), permit.leaseUntil(), permit.globalIdempotencyKey(),
                permit.provider(), permit.providerInstallationId(),
                permit.providerRepositoryId(), permit.operation(),
                permit.requestReferenceType(), permit.requestReferenceId(),
                permit.requestReferenceVersion(), permit.requestDigest(),
                resolution.errorCode(), resolution.providerRequestId()),
            "execution completion fence was lost");
    }

    public void completeExecution(
            DSLContext tx,
            ExternalWritePermit permit,
            ExecutionResolution.Terminal resolution,
            DomainEvent event,
            OutboxMessage outbox) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(outbox, "outbox");
        String target;
        String outcomeDigest;
        String providerRequestId;
        if (resolution instanceof ExecutionResolution.Succeeded succeeded) {
            target = "SUCCEEDED";
            outcomeDigest = succeeded.outcomeDigest();
            providerRequestId = succeeded.providerRequestId();
        } else if (resolution instanceof ExecutionResolution.ConfirmedNoEffect noEffect) {
            target = "CONFIRMED_NO_EFFECT";
            outcomeDigest = noEffect.evidenceDigest();
            providerRequestId = noEffect.providerRequestId();
        } else {
            throw new IllegalStateException("unsupported execution terminal resolution");
        }
        runFenced(
            () -> tx.execute("""
                SELECT accord_security.complete_external_intent_execution(
                  ?,?,?,?,?,CAST(? AS timestamptz),?,?,?,?,?,?,?,?,?,?,?,?,?,
                  ?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS timestamptz),
                  ?,?,CAST(? AS jsonb))
                """,
                permit.tenantId(), permit.intentId(), permit.owner(), permit.generation(),
                permit.token(), permit.leaseUntil(), permit.globalIdempotencyKey(),
                permit.provider(), permit.providerInstallationId(),
                permit.providerRepositoryId(), permit.operation(),
                permit.requestReferenceType(), permit.requestReferenceId(),
                permit.requestReferenceVersion(), permit.requestDigest(),
                target, outcomeDigest, providerRequestId,
                event.tenantId(), event.eventId(), event.scopeType(), event.scopeId(),
                event.aggregateType(), event.aggregateId(), event.sequence(),
                event.eventType(), event.schemaVersion(), event.causationId(),
                event.correlationId(), event.actorId(), event.payload(),
                event.occurredAt(), outbox.destination(), outbox.payloadSchema(),
                outbox.payload()),
            "execution terminal fence was lost");
    }

    public boolean expireExecution(DSLContext tx, UUID tenantId, UUID intentId) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        Record result = tx.fetchOne(
            "SELECT accord_security.expire_external_intent_execution(?,?)",
            tenantId, intentId);
        return result != null && Boolean.TRUE.equals(result.get(0, Boolean.class));
    }
    public ReconciliationClaim claimReconciliation(
            DSLContext tx,
            UUID tenantId,
            UUID intentId,
            String owner,
            Duration lease) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        owner = CommandKey.requireBounded(owner, "owner", 255);
        long leaseMicros = ReliabilityValues.leaseMicros(lease, "lease");
        UUID candidateToken = UUID.randomUUID();
        Record result = tx.fetchOne("""
            SELECT * FROM accord_security.claim_external_intent_reconciliation(
              ?,?,?,?,?)
            """, tenantId, intentId, owner, leaseMicros, candidateToken);
        if (result == null) {
            return new ReconciliationClaim.Missing();
        }
        return switch (required(result, "disposition", String.class)) {
            case "NOT_RECONCILABLE" ->
                new ReconciliationClaim.NotReconcilable(state(result));
            case "ACQUIRED" -> {
                if (!candidateToken.equals(required(
                        result, "reconciliation_token", UUID.class))
                        || !owner.equals(required(
                            result, "reconciliation_owner", String.class))) {
                    throw new IllegalStateException(
                        "reconciliation claim returned a mismatched capability");
                }
                yield new ReconciliationClaim.Acquired(reconciliationLease(result));
            }
            default -> throw new IllegalStateException(
                "unknown reconciliation claim disposition");
        };
    }

    public ReconciliationLease renewReconciliation(
            DSLContext tx, ReconciliationLease lease, Duration extension) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(lease, "lease");
        long extensionMicros = ReliabilityValues.leaseMicros(extension, "extension");
        Record renewed = fetchFenced(
            () -> tx.fetchOne("""
                SELECT * FROM accord_security.renew_external_intent_reconciliation(
                  ?,?,?,?,?,CAST(? AS timestamptz),?,?,?,?,?,?,?,?,?,?,?)
                """,
                lease.tenantId(), lease.intentId(), lease.owner(), lease.generation(),
                lease.token(), lease.leaseUntil(), lease.globalIdempotencyKey(),
                lease.provider(), lease.providerInstallationId(),
                lease.providerRepositoryId(), lease.operation(),
                lease.requestReferenceType(), lease.requestReferenceId(),
                lease.requestReferenceVersion(), lease.requestDigest(),
                lease.providerRequestId(), extensionMicros),
            "reconciliation renewal fence was lost");
        return renewedReconciliationLease(
            lease, required(
                renewed, "reconciliation_lease_until", OffsetDateTime.class));
    }

    public void markReconciliationOutcomeUnknown(
            DSLContext tx,
            ReconciliationLease lease,
            ReconciliationResolution.StillUnknown resolution) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(resolution, "resolution");
        runFenced(
            () -> tx.execute("""
                SELECT accord_security.mark_external_intent_reconciliation_unknown(
                  ?,?,?,?,?,CAST(? AS timestamptz),?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                lease.tenantId(), lease.intentId(), lease.owner(), lease.generation(),
                lease.token(), lease.leaseUntil(), lease.globalIdempotencyKey(),
                lease.provider(), lease.providerInstallationId(),
                lease.providerRepositoryId(), lease.operation(),
                lease.requestReferenceType(), lease.requestReferenceId(),
                lease.requestReferenceVersion(), lease.requestDigest(),
                lease.providerRequestId(), resolution.errorCode(),
                resolution.providerRequestId()),
            "reconciliation completion fence was lost");
    }

    public void completeReconciliation(
            DSLContext tx,
            ReconciliationLease lease,
            ReconciliationResolution.Terminal resolution,
            DomainEvent event,
            OutboxMessage outbox) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(outbox, "outbox");
        String target;
        String outcomeDigest;
        String errorCode;
        String providerRequestId;
        if (resolution instanceof ReconciliationResolution.Succeeded succeeded) {
            target = "SUCCEEDED";
            outcomeDigest = succeeded.outcomeDigest();
            errorCode = null;
            providerRequestId = succeeded.providerRequestId();
        } else if (resolution
                instanceof ReconciliationResolution.ConfirmedNoEffect noEffect) {
            target = "CONFIRMED_NO_EFFECT";
            outcomeDigest = noEffect.evidenceDigest();
            errorCode = null;
            providerRequestId = noEffect.providerRequestId();
        } else if (resolution instanceof ReconciliationResolution.Diverged diverged) {
            target = "DIVERGED";
            outcomeDigest = diverged.evidenceDigest();
            errorCode = diverged.errorCode();
            providerRequestId = diverged.providerRequestId();
        } else {
            throw new IllegalStateException("unsupported reconciliation terminal resolution");
        }
        runFenced(
            () -> tx.execute("""
                SELECT accord_security.complete_external_intent_reconciliation(
                  ?,?,?,?,?,CAST(? AS timestamptz),?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                  ?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS timestamptz),
                  ?,?,CAST(? AS jsonb))
                """,
                lease.tenantId(), lease.intentId(), lease.owner(), lease.generation(),
                lease.token(), lease.leaseUntil(), lease.globalIdempotencyKey(),
                lease.provider(), lease.providerInstallationId(),
                lease.providerRepositoryId(), lease.operation(),
                lease.requestReferenceType(), lease.requestReferenceId(),
                lease.requestReferenceVersion(), lease.requestDigest(),
                lease.providerRequestId(), target, outcomeDigest, errorCode,
                providerRequestId, event.tenantId(), event.eventId(), event.scopeType(),
                event.scopeId(), event.aggregateType(), event.aggregateId(),
                event.sequence(), event.eventType(), event.schemaVersion(),
                event.causationId(), event.correlationId(), event.actorId(),
                event.payload(), event.occurredAt(), outbox.destination(),
                outbox.payloadSchema(), outbox.payload()),
            "reconciliation terminal fence was lost");
    }

    public boolean expireReconciliation(DSLContext tx, UUID tenantId, UUID intentId) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        Record result = tx.fetchOne(
            "SELECT accord_security.expire_external_intent_reconciliation(?,?)",
            tenantId, intentId);
        return result != null && Boolean.TRUE.equals(result.get(0, Boolean.class));
    }
    public Optional<ExternalIntentSnapshot> load(
            DSLContext tx, UUID tenantId, UUID intentId) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        Record row = tx.fetchOne("""
            SELECT * FROM accord_security.load_external_intent_snapshot(?,?)
            """, tenantId, intentId);
        return Optional.ofNullable(row).map(JooqExternalIntentStore::snapshot);
    }

    private static Record fetchFenced(
            java.util.function.Supplier<Record> operation, String message) {
        try {
            Record result = operation.get();
            if (result == null) {
                throw new LostExternalIntentFence(message);
            }
            return result;
        } catch (org.jooq.exception.DataAccessException error) {
            if (hasSqlState(error, "55000")) {
                throw new LostExternalIntentFence(message);
            }
            throw error;
        }
    }

    private static void runFenced(Runnable operation, String message) {
        try {
            operation.run();
        } catch (org.jooq.exception.DataAccessException error) {
            if (hasSqlState(error, "55000")) {
                throw new LostExternalIntentFence(message);
            }
            throw error;
        }
    }

    private static boolean hasSqlState(Throwable error, String expected) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql
                    && expected.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
    private static ExternalIntentRef requiredRef(Record row) {
        return new ExternalIntentRef(
            required(row, "tenant_id", UUID.class),
            required(row, "intent_id", UUID.class),
            required(row, "root_intent_id", UUID.class),
            required(row, "attempt_ordinal", Integer.class),
            required(row, "global_idempotency_key", String.class),
            state(row));
    }

    private static ExternalWritePermit writePermit(Record row) {
        return new ExternalWritePermit(
            required(row, "tenant_id", UUID.class),
            required(row, "intent_id", UUID.class),
            required(row, "execution_owner", String.class),
            required(row, "execution_generation", Long.class),
            required(row, "execution_token", UUID.class),
            required(row, "execution_lease_until", OffsetDateTime.class),
            required(row, "global_idempotency_key", String.class),
            required(row, "provider", String.class),
            required(row, "provider_installation_id", String.class),
            row.get("provider_repository_id", String.class),
            required(row, "operation", String.class),
            required(row, "request_reference_type", String.class),
            required(row, "request_reference_id", String.class),
            required(row, "request_reference_version", Long.class),
            required(row, "request_digest", String.class));
    }

    private static ReconciliationLease reconciliationLease(Record row) {
        return new ReconciliationLease(
            required(row, "tenant_id", UUID.class),
            required(row, "intent_id", UUID.class),
            required(row, "reconciliation_owner", String.class),
            required(row, "reconciliation_generation", Long.class),
            required(row, "reconciliation_token", UUID.class),
            required(row, "reconciliation_lease_until", OffsetDateTime.class),
            required(row, "global_idempotency_key", String.class),
            required(row, "provider", String.class),
            required(row, "provider_installation_id", String.class),
            row.get("provider_repository_id", String.class),
            required(row, "operation", String.class),
            required(row, "request_reference_type", String.class),
            required(row, "request_reference_id", String.class),
            required(row, "request_reference_version", Long.class),
            required(row, "request_digest", String.class),
            row.get("provider_request_id", String.class));
    }

    private static ExternalWritePermit renewedExecutionPermit(
            ExternalWritePermit permit, OffsetDateTime leaseUntil) {
        return new ExternalWritePermit(
            permit.tenantId(), permit.intentId(), permit.owner(), permit.generation(),
            permit.token(), leaseUntil, permit.globalIdempotencyKey(), permit.provider(),
            permit.providerInstallationId(), permit.providerRepositoryId(),
            permit.operation(), permit.requestReferenceType(), permit.requestReferenceId(),
            permit.requestReferenceVersion(), permit.requestDigest());
    }

    private static ReconciliationLease renewedReconciliationLease(
            ReconciliationLease lease, OffsetDateTime leaseUntil) {
        return new ReconciliationLease(
            lease.tenantId(), lease.intentId(), lease.owner(), lease.generation(),
            lease.token(), leaseUntil, lease.globalIdempotencyKey(), lease.provider(),
            lease.providerInstallationId(), lease.providerRepositoryId(), lease.operation(),
            lease.requestReferenceType(), lease.requestReferenceId(),
            lease.requestReferenceVersion(), lease.requestDigest(),
            lease.providerRequestId());
    }

    private static ExternalIntentSnapshot snapshot(Record row) {
        return new ExternalIntentSnapshot(
            required(row, "tenant_id", UUID.class),
            required(row, "intent_id", UUID.class),
            required(row, "root_intent_id", UUID.class),
            row.get("predecessor_intent_id", UUID.class),
            required(row, "attempt_ordinal", Integer.class),
            required(row, "scope_type", String.class),
            required(row, "scope_id", String.class),
            required(row, "logical_action_key", String.class),
            required(row, "global_idempotency_key", String.class),
            state(row),
            required(row, "execution_generation", Long.class),
            required(row, "reconciliation_generation", Long.class),
            row.get("provider_request_id", String.class),
            row.get("outcome_digest", String.class),
            row.get("last_error_code", String.class),
            required(row, "created_at", OffsetDateTime.class),
            required(row, "updated_at", OffsetDateTime.class),
            row.get("terminal_at", OffsetDateTime.class));
    }

    private static ExternalIntentState state(Record row) {
        return ExternalIntentState.valueOf(required(row, "state", String.class));
    }

    private static <T> T required(Record row, String field, Class<T> type) {
        if (row == null) {
            throw new IllegalStateException("required row is absent");
        }
        T value = row.get(field, type);
        if (value == null) {
            throw new IllegalStateException(field + " is unexpectedly null");
        }
        return value;
    }
}
