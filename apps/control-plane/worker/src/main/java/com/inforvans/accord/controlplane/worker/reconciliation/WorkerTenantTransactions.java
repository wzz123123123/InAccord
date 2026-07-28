package com.inforvans.accord.controlplane.worker.reconciliation;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

public final class WorkerTenantTransactions {
    private static final long CONNECTION_ACQUISITION_TIMEOUT_MILLIS = 2_000;
    private static final long STATEMENT_TIMEOUT_MILLIS = 2_000;
    private static final long LOCK_TIMEOUT_MILLIS = 1_000;
    private static final long TRANSACTION_TIMEOUT_MILLIS = 6_000;

    private final DSLContext context;

    public WorkerTenantTransactions(DSLContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public <T> T inTenant(UUID tenantId, Function<DSLContext, T> work) {
        return inTenant(tenantId, null, work);
    }

    <T> T inTenantWithinBudget(
            UUID tenantId,
            LongSupplier remainingMillis,
            Function<DSLContext, T> work) {
        Objects.requireNonNull(remainingMillis, "remainingMillis");
        if (remainingMillis.getAsLong() < CONNECTION_ACQUISITION_TIMEOUT_MILLIS) {
            throw ReconciliationFailure.of(
                ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
        }
        return inTenant(tenantId, remainingMillis, work);
    }

    private <T> T inTenant(
            UUID tenantId,
            LongSupplier remainingMillis,
            Function<DSLContext, T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(work, "work");
        return context.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            if (remainingMillis != null) {
                constrainDatabase(tx, remainingMillis.getAsLong());
            }
            tx.fetchValue(
                "SELECT set_config('app.tenant_id', ?, true)", tenantId.toString());
            Record identity = tx.fetchOne("""
                SELECT accord_security.current_tenant_id() AS tenant_id,
                       current_user AS session_role
                """);
            if (identity == null
                    || !tenantId.equals(identity.get("tenant_id", UUID.class))
                    || !"accord_worker".equals(identity.get("session_role", String.class))) {
                throw new IllegalStateException("worker tenant context was not installed");
            }
            return work.apply(tx);
        });
    }

    private static void constrainDatabase(DSLContext tx, long remainingMillis) {
        if (remainingMillis <= 0) {
            throw ReconciliationFailure.of(
                ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
        }
        tx.fetchOne("""
            SELECT set_config(
                     'transaction_timeout',
                     LEAST(
                       COALESCE(NULLIF(CAST(EXTRACT(EPOCH FROM
                         current_setting('transaction_timeout')::interval) * 1000
                         AS bigint), 0), CAST(? AS bigint)),
                       CAST(? AS bigint), CAST(? AS bigint))::text,
                     true),
                   set_config(
                     'statement_timeout',
                     LEAST(
                       COALESCE(NULLIF(CAST(EXTRACT(EPOCH FROM
                         current_setting('statement_timeout')::interval) * 1000
                         AS bigint), 0), CAST(? AS bigint)),
                       CAST(? AS bigint), CAST(? AS bigint))::text,
                     true),
                   set_config(
                     'lock_timeout',
                     LEAST(
                       COALESCE(NULLIF(CAST(EXTRACT(EPOCH FROM
                         current_setting('lock_timeout')::interval) * 1000
                         AS bigint), 0), CAST(? AS bigint)),
                       CAST(? AS bigint), CAST(? AS bigint))::text,
                     true)
            """,
            TRANSACTION_TIMEOUT_MILLIS,
            TRANSACTION_TIMEOUT_MILLIS,
            remainingMillis,
            STATEMENT_TIMEOUT_MILLIS,
            STATEMENT_TIMEOUT_MILLIS,
            remainingMillis,
            LOCK_TIMEOUT_MILLIS,
            LOCK_TIMEOUT_MILLIS,
            remainingMillis);
    }
}
