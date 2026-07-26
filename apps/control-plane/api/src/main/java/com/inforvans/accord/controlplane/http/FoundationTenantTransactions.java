package com.inforvans.accord.controlplane.http;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;

final class FoundationTenantTransactions {
    private final DSLContext dsl;

    FoundationTenantTransactions(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    <T> T write(UUID tenantId, Function<DSLContext, T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(work, "work");
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            Record configured = tx.fetchOne(
                "SELECT set_config('app.tenant_id', ?, true) AS tenant_setting",
                tenantId.toString());
            if (configured == null) {
                throw new IllegalStateException("tenant context was not installed");
            }
            Record verified = tx.fetchOne(
                "SELECT accord_security.current_tenant_id() AS tenant_id");
            UUID currentTenant = verified == null
                ? null
                : verified.get("tenant_id", UUID.class);
            if (!tenantId.equals(currentTenant)) {
                throw new IllegalStateException("tenant context verification failed");
            }
            return work.apply(tx);
        });
    }
}
