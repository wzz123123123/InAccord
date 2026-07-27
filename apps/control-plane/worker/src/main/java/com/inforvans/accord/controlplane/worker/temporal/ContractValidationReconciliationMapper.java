package com.inforvans.accord.controlplane.worker.temporal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowRef;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationRuntimeProperties;
import com.inforvans.accord.controlplane.worker.reconciliation.WorkerTenantTransactions;
import com.inforvans.accord.reliability.ExecutionClaim;
import com.inforvans.accord.reliability.ExecutionResolution;
import com.inforvans.accord.reliability.ExternalWritePermit;
import com.inforvans.accord.reliability.ExternalIntentSnapshot;
import com.inforvans.accord.reliability.ExternalIntentState;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import com.inforvans.accord.reliability.LeasedEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class ContractValidationReconciliationMapper
        implements TemporalReconciliationEventTransport.ReconciliationEventMapper {
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
        "intent_id", "validation_id", "schema_id", "document_digest", "version");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WorkerTenantTransactions transactions;
    private final JooqExternalIntentStore intents;
    private final String executionOwner;
    private final java.time.Duration executionLease;

    ContractValidationReconciliationMapper(
            WorkerTenantTransactions transactions,
            JooqExternalIntentStore intents,
            ReconciliationRuntimeProperties properties) {
        this.transactions = java.util.Objects.requireNonNull(
            transactions, "transactions");
        this.intents = java.util.Objects.requireNonNull(intents, "intents");
        java.util.Objects.requireNonNull(properties, "properties");
        this.executionOwner = properties.instanceId() + ":ingress";
        this.executionLease = properties.claimLease();
    }

    @Override
    public ReconciliationWorkflowRef map(LeasedEvent event) {
        ObjectNode payload = payload(event.payload());
        UUID intentId = canonicalUuid(requiredText(payload, "intent_id"));
        UUID validationId = canonicalUuid(requiredText(payload, "validation_id"));
        String schemaId = requiredText(payload, "schema_id");
        if (schemaId.length() > 2048 || !schemaId.startsWith("https://")) {
            throw invalidPayload();
        }
        String documentDigest = requiredText(payload, "document_digest");
        if (!documentDigest.matches("^sha256:[0-9a-f]{64}$")) {
            throw invalidPayload();
        }
        JsonNode version = payload.get("version");
        if (version == null || !version.isIntegralNumber()
                || !version.canConvertToLong() || version.longValue() < 1) {
            throw invalidPayload();
        }
        ParsedPayload parsed = new ParsedPayload(
            intentId, validationId, documentDigest, version.longValue());
        prepareIntent(event.tenantId(), parsed);
        return new ReconciliationWorkflowRef(event.tenantId(), intentId);
    }

    private void prepareIntent(UUID tenantId, ParsedPayload payload) {
        UUID intentId = payload.intentId();
        transactions.inTenant(tenantId, tx -> {
            ExternalIntentSnapshot snapshot = intents.load(tx, tenantId, intentId)
                .orElseThrow(() -> new IllegalStateException(
                    "contract validation reconciliation intent is missing"));
            ExternalIntentState state = snapshot.state();
            if (state == ExternalIntentState.RECORDED) {
                ExecutionClaim claim = intents.claimExecution(
                    tx, tenantId, intentId, executionOwner, executionLease);
                if (claim instanceof ExecutionClaim.Acquired acquired) {
                    requireApiAuthority(acquired.permit(), payload);
                    intents.markExecutionOutcomeUnknown(
                        tx,
                        acquired.permit(),
                        new ExecutionResolution.OutcomeUnknown(
                            "PROVIDER_TIMEOUT", null));
                    state = ExternalIntentState.OUTCOME_UNKNOWN;
                } else if (claim instanceof ExecutionClaim.NotExecutable rejected) {
                    state = rejected.state();
                } else {
                    throw new IllegalStateException(
                        "contract validation reconciliation intent disappeared");
                }
            }
            if (state == ExternalIntentState.EXECUTING) {
                if (!intents.expireExecution(tx, tenantId, intentId)) {
                    throw new IllegalStateException(
                        "contract validation reconciliation execution is still leased");
                }
                state = intents.load(tx, tenantId, intentId)
                    .orElseThrow(() -> new IllegalStateException(
                        "contract validation reconciliation intent disappeared"))
                    .state();
            }
            if (state != ExternalIntentState.OUTCOME_UNKNOWN
                    && state != ExternalIntentState.RECONCILING
                    && state != ExternalIntentState.SUCCEEDED
                    && state != ExternalIntentState.CONFIRMED_NO_EFFECT
                    && state != ExternalIntentState.DIVERGED) {
                throw new IllegalStateException(
                    "contract validation reconciliation intent is not recoverable");
            }
            return null;
        });
    }

    private static void requireApiAuthority(
            ExternalWritePermit permit, ParsedPayload payload) {
        if (!"accord".equals(permit.provider())
                || !"control-api".equals(permit.providerInstallationId())
                || permit.providerRepositoryId() != null
                || !"contract.validation".equals(permit.operation())
                || !"contract-validation".equals(permit.requestReferenceType())
                || !payload.validationId().toString().equals(
                    permit.requestReferenceId())
                || payload.version() != permit.requestReferenceVersion()
                || !payload.documentDigest().equals(permit.requestDigest())) {
            throw new IllegalStateException(
                "contract validation reconciliation authority does not match the event");
        }
    }

    private static ObjectNode payload(String encoded) {
        try {
            JsonNode parsed = JSON.readTree(encoded);
            if (!(parsed instanceof ObjectNode object)
                    || !fieldNames(object).equals(PAYLOAD_FIELDS)) {
                throw invalidPayload();
            }
            return object;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                "contract validation reconciliation payload is invalid", failure);
        }
    }

    private static Set<String> fieldNames(ObjectNode object) {
        Set<String> fields = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        return Set.copyOf(fields);
    }

    private static String requiredText(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidPayload();
        }
        return value.textValue();
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw invalidPayload();
            }
            return uuid;
        } catch (IllegalArgumentException failure) {
            throw invalidPayload();
        }
    }

    private static IllegalArgumentException invalidPayload() {
        return new IllegalArgumentException(
            "contract validation reconciliation payload is invalid");
    }

    private record ParsedPayload(
        UUID intentId,
        UUID validationId,
        String documentDigest,
        long version
    ) {}
}
