package com.inforvans.accord.controlplane.http;

import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

final class ContractValidationReconciliationRegistration {
    private static final String LOGICAL_ACTION_PREFIX =
        "contract-validation.reconcile:";

    UUID record(
            DSLContext tx,
            FoundationVerifiedPrincipal principal,
            UUID validationId,
            long version,
            String documentDigest) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(validationId, "validationId");
        Objects.requireNonNull(documentDigest, "documentDigest");
        UUID intentId = UUID.randomUUID();
        Record registration = tx.fetchOne("""
            SELECT disposition,
                   result_tenant_id AS tenant_id,
                   result_intent_id AS intent_id
            FROM accord_security.record_external_intent(
              ?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            principal.tenantId(),
            intentId,
            LOGICAL_ACTION_PREFIX + intentId,
            "tenant",
            principal.tenantId().toString(),
            "accord",
            "control-api",
            null,
            "contract.validation",
            "contract-validation",
            validationId.toString(),
            version,
            documentDigest);
        if (registration == null
                || !"CREATED".equals(registration.get("disposition", String.class))) {
            throw new IllegalStateException(
                "contract validation reconciliation intent conflicts with durable authority");
        }
        if (!principal.tenantId().equals(registration.get("tenant_id", UUID.class))
                || !intentId.equals(registration.get("intent_id", UUID.class))) {
            throw new IllegalStateException(
                "contract validation reconciliation intent identity changed");
        }
        return intentId;
    }
}
