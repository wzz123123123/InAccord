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
        int inserted = tx.execute("""
            INSERT INTO external_call_intent (
              tenant_id,intent_id,root_intent_id,predecessor_intent_id,
              attempt_ordinal,logical_action_key,scope_type,scope_id,provider,
              provider_installation_id,provider_repository_id,operation,
              request_reference_type,request_reference_id,
              request_reference_version,request_digest,state
            ) VALUES (?, ?, ?, NULL, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECORDED')
            ON CONFLICT DO NOTHING
            """,
            definition.tenantId(), definition.intentId(), definition.intentId(),
            definition.logicalActionKey(), definition.scopeType(), definition.scopeId(),
            definition.provider(), definition.providerInstallationId(),
            definition.providerRepositoryId(), definition.operation(),
            definition.requestReferenceType(), definition.requestReferenceId(),
            definition.requestReferenceVersion(), definition.requestDigest());
        if (inserted == 1) {
            return new ExternalIntentRegistration.Created(requiredRef(
                findById(tx, definition.tenantId(), definition.intentId())));
        }
        Record existing = tx.fetchOne("""
            SELECT * FROM external_call_intent
            WHERE tenant_id=? AND logical_action_key=?
              AND predecessor_intent_id IS NULL
            """, definition.tenantId(), definition.logicalActionKey());
        if (existing == null) {
            throw new IllegalStateException("external intent conflict row disappeared");
        }
        if (sameDefinition(existing, definition)) {
            return new ExternalIntentRegistration.Duplicate(requiredRef(existing));
        }
        return new ExternalIntentRegistration.Conflict();
    }

    public ExternalIntentRef createSuccessor(
            DSLContext tx, UUID tenantId, UUID predecessorIntentId) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(predecessorIntentId, "predecessorIntentId");
        Record predecessor = findById(tx, tenantId, predecessorIntentId);
        if (predecessor == null) {
            throw new IllegalStateException("predecessor is absent");
        }
        Record existing = tx.fetchOne("""
            SELECT * FROM external_call_intent
            WHERE tenant_id=? AND predecessor_intent_id=?
            """, tenantId, predecessorIntentId);
        if (existing != null) {
            return requiredRef(existing);
        }
        if (state(predecessor) != ExternalIntentState.CONFIRMED_NO_EFFECT) {
            throw new IllegalStateException("predecessor is not confirmed no effect");
        }
        UUID successorId = UUID.randomUUID();
        Record inserted = tx.fetchOne("""
            INSERT INTO external_call_intent (
              tenant_id,intent_id,root_intent_id,predecessor_intent_id,
              attempt_ordinal,logical_action_key,scope_type,scope_id,provider,
              provider_installation_id,provider_repository_id,operation,
              request_reference_type,request_reference_id,
              request_reference_version,request_digest,state
            )
            SELECT tenant_id,?,root_intent_id,intent_id,attempt_ordinal+1,
                   logical_action_key,scope_type,scope_id,provider,
                   provider_installation_id,provider_repository_id,operation,
                   request_reference_type,request_reference_id,
                   request_reference_version,request_digest,'RECORDED'
            FROM external_call_intent
            WHERE tenant_id=? AND intent_id=? AND state='CONFIRMED_NO_EFFECT'
            ON CONFLICT (tenant_id, predecessor_intent_id)
              WHERE predecessor_intent_id IS NOT NULL
              DO NOTHING
            RETURNING *
            """, successorId, tenantId, predecessorIntentId);
        if (inserted == null) {
            Record winner = tx.fetchOne("""
                SELECT * FROM external_call_intent
                WHERE tenant_id=? AND predecessor_intent_id=?
                """, tenantId, predecessorIntentId);
            if (winner == null) {
                throw new IllegalStateException("successor could not be created");
            }
            return requiredRef(winner);
        }
        return requiredRef(inserted);
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
        Record locked = lockById(tx, tenantId, intentId);
        if (locked == null) {
            return new ExecutionClaim.Missing();
        }
        ExternalIntentState current = state(locked);
        if (current != ExternalIntentState.RECORDED) {
            return new ExecutionClaim.NotExecutable(current);
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        UUID token = UUID.randomUUID();
        Record acquired = tx.fetchOne("""
            WITH lease AS MATERIALIZED (
              SELECT CAST(? AS timestamptz) AS permitted_at,
                     CAST(? AS timestamptz)
                       + (? * INTERVAL '1 microsecond') AS deadline
            )
            UPDATE external_call_intent AS intent
            SET state='EXECUTING',execution_owner=?,execution_generation=1,
                execution_token=?,execution_permitted_at=lease.permitted_at,
                execution_lease_until=lease.deadline
            FROM lease
            WHERE intent.tenant_id=? AND intent.intent_id=? AND intent.state='RECORDED'
              AND intent.execution_generation=0
            RETURNING intent.*
            """,
            databaseNow, databaseNow, leaseMicros, owner, token, tenantId, intentId);
        if (acquired == null) {
            throw new LostExternalIntentFence("execution permit was not acquired");
        }
        return new ExecutionClaim.Acquired(writePermit(acquired));
    }

    public ExternalWritePermit renewExecution(
            DSLContext tx, ExternalWritePermit permit, Duration extension) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(permit, "permit");
        long extensionMicros = ReliabilityValues.leaseMicros(extension, "extension");
        Record locked = lockById(tx, permit.tenantId(), permit.intentId());
        if (locked == null || !matchesPermitContext(locked, permit)) {
            throw new LostExternalIntentFence("execution intent is absent");
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        Record renewed = tx.fetchOne("""
            WITH extension AS MATERIALIZED (
              SELECT ? * INTERVAL '1 microsecond' AS amount
            )
            UPDATE external_call_intent AS intent
            SET execution_lease_until=intent.execution_lease_until+extension.amount
            FROM extension
            WHERE intent.tenant_id=? AND intent.intent_id=?
              AND intent.state='EXECUTING'
              AND intent.execution_owner=? AND intent.execution_generation=?
              AND intent.execution_token=?
              AND intent.execution_lease_until=CAST(? AS timestamptz)
              AND intent.execution_lease_until>CAST(? AS timestamptz)
            RETURNING intent.execution_lease_until
            """,
            extensionMicros,
            permit.tenantId(), permit.intentId(), permit.owner(), permit.generation(),
            permit.token(), permit.leaseUntil(), databaseNow);
        if (renewed == null) {
            throw new LostExternalIntentFence("execution renewal fence was lost");
        }
        return copyPermit(
            permit, required(renewed, "execution_lease_until", OffsetDateTime.class));
    }

    public void finishExecution(
            DSLContext tx, ExternalWritePermit permit, ExecutionResolution resolution) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(resolution, "resolution");
        Record locked = lockById(tx, permit.tenantId(), permit.intentId());
        if (locked == null || !matchesPermitContext(locked, permit)) {
            throw new LostExternalIntentFence("execution intent is absent");
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        String target;
        String outcomeDigest;
        String errorCode;
        String providerRequestId;
        OffsetDateTime terminalAt;
        if (resolution instanceof ExecutionResolution.Succeeded succeeded) {
            target = "SUCCEEDED";
            outcomeDigest = succeeded.outcomeDigest();
            errorCode = null;
            providerRequestId = succeeded.providerRequestId();
            terminalAt = databaseNow;
        } else if (resolution instanceof ExecutionResolution.ConfirmedNoEffect noEffect) {
            target = "CONFIRMED_NO_EFFECT";
            outcomeDigest = noEffect.evidenceDigest();
            errorCode = null;
            providerRequestId = noEffect.providerRequestId();
            terminalAt = databaseNow;
        } else if (resolution instanceof ExecutionResolution.OutcomeUnknown unknown) {
            target = "OUTCOME_UNKNOWN";
            outcomeDigest = null;
            errorCode = unknown.errorCode();
            providerRequestId = unknown.providerRequestId();
            terminalAt = null;
        } else {
            throw new IllegalStateException("unsupported execution resolution");
        }
        int changed = tx.execute("""
            UPDATE external_call_intent AS intent
            SET state=?,provider_request_id=COALESCE(
                  intent.provider_request_id,CAST(? AS varchar)),
                outcome_digest=?,last_error_code=?,terminal_at=CAST(? AS timestamptz)
            WHERE intent.tenant_id=? AND intent.intent_id=?
              AND intent.state='EXECUTING'
              AND intent.execution_owner=? AND intent.execution_generation=?
              AND intent.execution_token=?
              AND intent.execution_lease_until=CAST(? AS timestamptz)
              AND (intent.provider_request_id IS NULL OR CAST(? AS varchar) IS NULL
                   OR intent.provider_request_id=CAST(? AS varchar))
              AND intent.execution_lease_until>CAST(? AS timestamptz)
            """,
            target, providerRequestId, outcomeDigest, errorCode, terminalAt,
            permit.tenantId(), permit.intentId(), permit.owner(), permit.generation(),
            permit.token(), permit.leaseUntil(), providerRequestId, providerRequestId,
            databaseNow);
        if (changed != 1) {
            throw new LostExternalIntentFence("execution completion fence was lost");
        }
    }

    public boolean expireExecution(DSLContext tx, UUID tenantId, UUID intentId) {
        Objects.requireNonNull(tx, "tx");
        Record locked = lockById(tx, tenantId, intentId);
        if (locked == null || state(locked) != ExternalIntentState.EXECUTING) {
            return false;
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        OffsetDateTime deadline = required(
            locked, "execution_lease_until", OffsetDateTime.class);
        if (deadline.isAfter(databaseNow)) {
            return false;
        }
        int changed = tx.execute("""
            UPDATE external_call_intent
            SET state='OUTCOME_UNKNOWN',last_error_code='EXECUTION_LEASE_EXPIRED'
            WHERE tenant_id=? AND intent_id=? AND state='EXECUTING'
              AND execution_owner=? AND execution_generation=?
              AND execution_token=? AND execution_lease_until=CAST(? AS timestamptz)
            """,
            tenantId, intentId,
            required(locked, "execution_owner", String.class),
            required(locked, "execution_generation", Long.class),
            required(locked, "execution_token", UUID.class), deadline);
        return changed == 1;
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
        Record locked = lockById(tx, tenantId, intentId);
        if (locked == null) {
            return new ReconciliationClaim.Missing();
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        if (state(locked) == ExternalIntentState.RECONCILING
                && !required(locked, "reconciliation_lease_until", OffsetDateTime.class)
                    .isAfter(databaseNow)) {
            expireReconciliationLocked(tx, locked, databaseNow);
            locked = lockById(tx, tenantId, intentId);
        }
        ExternalIntentState current = state(locked);
        if (current != ExternalIntentState.OUTCOME_UNKNOWN) {
            return new ReconciliationClaim.NotReconcilable(current);
        }
        long generation;
        try {
            generation = Math.addExact(
                required(locked, "reconciliation_generation", Long.class), 1L);
        } catch (ArithmeticException error) {
            throw new IllegalStateException("reconciliation generation exhausted", error);
        }
        UUID token = UUID.randomUUID();
        Record acquired = tx.fetchOne("""
            WITH lease AS MATERIALIZED (
              SELECT CAST(? AS timestamptz) AS started_at,
                     CAST(? AS timestamptz)
                       + (? * INTERVAL '1 microsecond') AS deadline
            )
            UPDATE external_call_intent AS intent
            SET state='RECONCILING',reconciliation_owner=?,
                reconciliation_generation=?,reconciliation_token=?,
                reconciliation_started_at=lease.started_at,
                reconciliation_lease_until=lease.deadline,last_error_code=NULL
            FROM lease
            WHERE intent.tenant_id=? AND intent.intent_id=?
              AND intent.state='OUTCOME_UNKNOWN'
              AND intent.reconciliation_generation=?
            RETURNING intent.*
            """,
            databaseNow, databaseNow, leaseMicros,
            owner, generation, token, tenantId, intentId, generation - 1);
        if (acquired == null) {
            throw new LostExternalIntentFence("reconciliation permit was not acquired");
        }
        return new ReconciliationClaim.Acquired(reconciliationLease(acquired));
    }

    public ReconciliationLease renewReconciliation(
            DSLContext tx, ReconciliationLease lease, Duration extension) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(lease, "lease");
        long extensionMicros = ReliabilityValues.leaseMicros(extension, "extension");
        Record locked = lockById(tx, lease.tenantId(), lease.intentId());
        if (locked == null || !matchesReconciliationContext(locked, lease)) {
            throw new LostExternalIntentFence("reconciliation intent is absent");
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        Record renewed = tx.fetchOne("""
            WITH extension AS MATERIALIZED (
              SELECT ? * INTERVAL '1 microsecond' AS amount
            )
            UPDATE external_call_intent AS intent
            SET reconciliation_lease_until=
                  intent.reconciliation_lease_until+extension.amount
            FROM extension
            WHERE intent.tenant_id=? AND intent.intent_id=?
              AND intent.state='RECONCILING'
              AND intent.reconciliation_owner=?
              AND intent.reconciliation_generation=?
              AND intent.reconciliation_token=?
              AND intent.reconciliation_lease_until=CAST(? AS timestamptz)
              AND intent.reconciliation_lease_until>CAST(? AS timestamptz)
            RETURNING intent.reconciliation_lease_until
            """,
            extensionMicros,
            lease.tenantId(), lease.intentId(), lease.owner(), lease.generation(),
            lease.token(), lease.leaseUntil(), databaseNow);
        if (renewed == null) {
            throw new LostExternalIntentFence("reconciliation renewal fence was lost");
        }
        return copyReconciliationLease(
            lease, required(renewed, "reconciliation_lease_until", OffsetDateTime.class));
    }

    public void finishReconciliation(
            DSLContext tx,
            ReconciliationLease lease,
            ReconciliationResolution resolution) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(resolution, "resolution");
        Record locked = lockById(tx, lease.tenantId(), lease.intentId());
        if (locked == null || !matchesReconciliationContext(locked, lease)) {
            throw new LostExternalIntentFence("reconciliation intent is absent");
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        String target;
        String outcomeDigest;
        String errorCode;
        String providerRequestId = null;
        OffsetDateTime terminalAt;
        if (resolution instanceof ReconciliationResolution.Succeeded succeeded) {
            target = "SUCCEEDED";
            outcomeDigest = succeeded.outcomeDigest();
            errorCode = null;
            providerRequestId = succeeded.providerRequestId();
            terminalAt = databaseNow;
        } else if (resolution instanceof ReconciliationResolution.ConfirmedNoEffect noEffect) {
            target = "CONFIRMED_NO_EFFECT";
            outcomeDigest = noEffect.evidenceDigest();
            errorCode = null;
            providerRequestId = noEffect.providerRequestId();
            terminalAt = databaseNow;
        } else if (resolution instanceof ReconciliationResolution.Diverged diverged) {
            target = "DIVERGED";
            outcomeDigest = diverged.evidenceDigest();
            errorCode = diverged.errorCode();
            providerRequestId = diverged.providerRequestId();
            terminalAt = databaseNow;
        } else if (resolution instanceof ReconciliationResolution.StillUnknown unknown) {
            target = "OUTCOME_UNKNOWN";
            outcomeDigest = null;
            errorCode = unknown.errorCode();
            providerRequestId = unknown.providerRequestId();
            terminalAt = null;
        } else {
            throw new IllegalStateException("unsupported reconciliation resolution");
        }
        int changed = tx.execute("""
            UPDATE external_call_intent AS intent
            SET state=?,reconciliation_owner=NULL,reconciliation_token=NULL,
                reconciliation_started_at=NULL,reconciliation_lease_until=NULL,
                provider_request_id=COALESCE(
                  intent.provider_request_id,CAST(? AS varchar)),
                outcome_digest=?,last_error_code=?,terminal_at=CAST(? AS timestamptz)
            WHERE intent.tenant_id=? AND intent.intent_id=?
              AND intent.state='RECONCILING'
              AND intent.reconciliation_owner=?
              AND intent.reconciliation_generation=?
              AND intent.reconciliation_token=?
              AND intent.reconciliation_lease_until=CAST(? AS timestamptz)
              AND intent.reconciliation_lease_until>CAST(? AS timestamptz)
              AND (intent.provider_request_id IS NULL OR CAST(? AS varchar) IS NULL
                   OR intent.provider_request_id=CAST(? AS varchar))
            """,
            target, providerRequestId, outcomeDigest, errorCode, terminalAt,
            lease.tenantId(), lease.intentId(), lease.owner(), lease.generation(),
            lease.token(), lease.leaseUntil(), databaseNow,
            providerRequestId, providerRequestId);
        if (changed != 1) {
            throw new LostExternalIntentFence("reconciliation completion fence was lost");
        }
    }

    public boolean expireReconciliation(DSLContext tx, UUID tenantId, UUID intentId) {
        Objects.requireNonNull(tx, "tx");
        Record locked = lockById(tx, tenantId, intentId);
        if (locked == null || state(locked) != ExternalIntentState.RECONCILING) {
            return false;
        }
        OffsetDateTime databaseNow = databaseNow(tx);
        if (required(locked, "reconciliation_lease_until", OffsetDateTime.class)
                .isAfter(databaseNow)) {
            return false;
        }
        return expireReconciliationLocked(tx, locked, databaseNow);
    }

    public Optional<ExternalIntentSnapshot> load(
            DSLContext tx, UUID tenantId, UUID intentId) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        Record row = tx.fetchOne("""
            SELECT * FROM external_call_intent
            WHERE tenant_id=? AND intent_id=?
            """, tenantId, intentId);
        return Optional.ofNullable(row).map(JooqExternalIntentStore::snapshot);
    }

    private boolean expireReconciliationLocked(
            DSLContext tx, Record locked, OffsetDateTime databaseNow) {
        OffsetDateTime deadline = required(
            locked, "reconciliation_lease_until", OffsetDateTime.class);
        if (deadline.isAfter(databaseNow)) {
            return false;
        }
        int changed = tx.execute("""
            UPDATE external_call_intent
            SET state='OUTCOME_UNKNOWN',reconciliation_owner=NULL,
                reconciliation_token=NULL,reconciliation_started_at=NULL,
                reconciliation_lease_until=NULL,
                last_error_code='RECONCILIATION_LEASE_EXPIRED'
            WHERE tenant_id=? AND intent_id=? AND state='RECONCILING'
              AND reconciliation_owner=? AND reconciliation_generation=?
              AND reconciliation_token=?
              AND reconciliation_lease_until=CAST(? AS timestamptz)
            """,
            required(locked, "tenant_id", UUID.class),
            required(locked, "intent_id", UUID.class),
            required(locked, "reconciliation_owner", String.class),
            required(locked, "reconciliation_generation", Long.class),
            required(locked, "reconciliation_token", UUID.class), deadline);
        return changed == 1;
    }

    private Record lockById(DSLContext tx, UUID tenantId, UUID intentId) {
        return tx.fetchOne("""
            SELECT * FROM external_call_intent
            WHERE tenant_id=? AND intent_id=?
            FOR UPDATE
            """, tenantId, intentId);
    }

    private Record findById(DSLContext tx, UUID tenantId, UUID intentId) {
        return tx.fetchOne("""
            SELECT * FROM external_call_intent
            WHERE tenant_id=? AND intent_id=?
            """, tenantId, intentId);
    }

    private static OffsetDateTime databaseNow(DSLContext tx) {
        return required(
            tx.fetchOne("SELECT clock_timestamp() AS database_now"),
            "database_now", OffsetDateTime.class);
    }

    private static boolean sameDefinition(Record row, ExternalIntentDefinition definition) {
        return Objects.equals(row.get("logical_action_key", String.class),
                    definition.logicalActionKey())
            && Objects.equals(row.get("scope_type", String.class), definition.scopeType())
            && Objects.equals(row.get("scope_id", String.class), definition.scopeId())
            && Objects.equals(row.get("provider", String.class), definition.provider())
            && Objects.equals(row.get("provider_installation_id", String.class),
                    definition.providerInstallationId())
            && Objects.equals(row.get("provider_repository_id", String.class),
                    definition.providerRepositoryId())
            && Objects.equals(row.get("operation", String.class), definition.operation())
            && Objects.equals(row.get("request_reference_type", String.class),
                    definition.requestReferenceType())
            && Objects.equals(row.get("request_reference_id", String.class),
                    definition.requestReferenceId())
            && Objects.equals(row.get("request_reference_version", Long.class),
                    definition.requestReferenceVersion())
            && Objects.equals(row.get("request_digest", String.class),
                    definition.requestDigest());
    }

    private static boolean matchesPermitContext(Record row, ExternalWritePermit permit) {
        return Objects.equals(row.get("global_idempotency_key", String.class),
                    permit.globalIdempotencyKey())
            && Objects.equals(row.get("provider", String.class), permit.provider())
            && Objects.equals(row.get("provider_installation_id", String.class),
                    permit.providerInstallationId())
            && Objects.equals(row.get("provider_repository_id", String.class),
                    permit.providerRepositoryId())
            && Objects.equals(row.get("operation", String.class), permit.operation())
            && Objects.equals(row.get("request_reference_type", String.class),
                    permit.requestReferenceType())
            && Objects.equals(row.get("request_reference_id", String.class),
                    permit.requestReferenceId())
            && Objects.equals(row.get("request_reference_version", Long.class),
                    permit.requestReferenceVersion())
            && Objects.equals(row.get("request_digest", String.class), permit.requestDigest());
    }

    private static boolean matchesReconciliationContext(
            Record row, ReconciliationLease lease) {
        return Objects.equals(row.get("global_idempotency_key", String.class),
                    lease.globalIdempotencyKey())
            && Objects.equals(row.get("provider", String.class), lease.provider())
            && Objects.equals(row.get("provider_installation_id", String.class),
                    lease.providerInstallationId())
            && Objects.equals(row.get("provider_repository_id", String.class),
                    lease.providerRepositoryId())
            && Objects.equals(row.get("operation", String.class), lease.operation())
            && Objects.equals(row.get("request_reference_type", String.class),
                    lease.requestReferenceType())
            && Objects.equals(row.get("request_reference_id", String.class),
                    lease.requestReferenceId())
            && Objects.equals(row.get("request_reference_version", Long.class),
                    lease.requestReferenceVersion())
            && Objects.equals(row.get("request_digest", String.class), lease.requestDigest())
            && Objects.equals(row.get("provider_request_id", String.class),
                    lease.providerRequestId());
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

    private static ExternalWritePermit copyPermit(
            ExternalWritePermit source, OffsetDateTime deadline) {
        return new ExternalWritePermit(
            source.tenantId(), source.intentId(), source.owner(), source.generation(),
            source.token(), deadline, source.globalIdempotencyKey(), source.provider(),
            source.providerInstallationId(), source.providerRepositoryId(), source.operation(),
            source.requestReferenceType(), source.requestReferenceId(),
            source.requestReferenceVersion(), source.requestDigest());
    }

    private static ReconciliationLease copyReconciliationLease(
            ReconciliationLease source, OffsetDateTime deadline) {
        return new ReconciliationLease(
            source.tenantId(), source.intentId(), source.owner(), source.generation(),
            source.token(), deadline, source.globalIdempotencyKey(), source.provider(),
            source.providerInstallationId(), source.providerRepositoryId(), source.operation(),
            source.requestReferenceType(), source.requestReferenceId(),
            source.requestReferenceVersion(), source.requestDigest(),
            source.providerRequestId());
    }

    private static ExternalIntentSnapshot snapshot(Record row) {
        return new ExternalIntentSnapshot(
            required(row, "tenant_id", UUID.class),
            required(row, "intent_id", UUID.class),
            required(row, "root_intent_id", UUID.class),
            row.get("predecessor_intent_id", UUID.class),
            required(row, "attempt_ordinal", Integer.class),
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
