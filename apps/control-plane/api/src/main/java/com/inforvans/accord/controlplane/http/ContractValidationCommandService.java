package com.inforvans.accord.controlplane.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inforvans.accord.controlplane.http.FoundationHttpConfiguration.ContractValidationCommand;
import com.inforvans.accord.platformkernel.CanonicalJson;
import com.inforvans.accord.reliability.Claim;
import com.inforvans.accord.reliability.ClaimLease;
import com.inforvans.accord.reliability.CommandKey;
import com.inforvans.accord.reliability.DomainEvent;
import com.inforvans.accord.reliability.JooqCommandGate;
import com.inforvans.accord.reliability.OutboxMessage;
import com.inforvans.accord.reliability.ReliableEventStore;
import com.inforvans.accord.reliability.StoredHttpResult;
import com.inforvans.accord.reliability.VersionConflict;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.Record;

final class ContractValidationCommandService implements ContractValidationCommand {
    private static final String AGGREGATE_TYPE = "contract-validation";
    private static final String ROUTE_KEY = "contract-validations.create";
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    private final FoundationTenantTransactions transactions;
    private final ContractValidationJson json;
    private final JooqCommandGate gate;
    private final ReliableEventStore events;
    private final ContractValidationReconciliationRegistration reconciliation;
    private final FoundationProblemFactory problems;
    private final String processInstanceId;
    private final Duration resultTtl;
    private final JsonMapper mapper = JsonMapper.builder().build();

    ContractValidationCommandService(
            FoundationTenantTransactions transactions,
            ContractValidationJson json,
            JooqCommandGate gate,
            ReliableEventStore events,
            ContractValidationReconciliationRegistration reconciliation,
            FoundationProblemFactory problems,
            String processInstanceId,
            Duration resultTtl) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.json = Objects.requireNonNull(json, "json");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.events = Objects.requireNonNull(events, "events");
        this.reconciliation = Objects.requireNonNull(
            reconciliation, "reconciliation");
        this.problems = Objects.requireNonNull(problems, "problems");
        this.processInstanceId = Objects.requireNonNull(
            processInstanceId, "processInstanceId");
        this.resultTtl = Objects.requireNonNull(resultTtl, "resultTtl");
    }

    @Override
    public StoredHttpResult execute(
            FoundationVerifiedPrincipal principal,
            UUID validationId,
            FoundationHttpRequestPolicy.ParsedRequest request,
            ContractValidationJson.DecodedRequest body,
            String requestFingerprint) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(validationId, "validationId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");

        CommandKey key = new CommandKey(
            principal.tenantId(), principal.actorId(), ROUTE_KEY, request.idempotencyKey());
        Claim claim = transactions.write(principal.tenantId(), tx ->
            gate.claim(tx, key, requestFingerprint, processInstanceId, CLAIM_LEASE));
        if (claim instanceof Claim.Replay replay) {
            return replay.result();
        }
        String requestTarget = request.requestTarget();
        if (claim instanceof Claim.RequestConflict) {
            return problems.idempotencyKeyReused(request.correlationId(), requestTarget);
        }
        if (claim instanceof Claim.InProgress) {
            return problems.commandInProgress(request.correlationId(), requestTarget, 1);
        }
        if (!(claim instanceof Claim.Acquired acquired)) {
            throw new IllegalStateException("claim outcome is unknown");
        }

        ClaimLease lease = acquired.lease();
        ContractValidationJson.SchemaEvaluation evaluation =
            json.evaluate(body.schemaId(), body.document());
        StoredHttpResult rejection = null;
        if (evaluation instanceof ContractValidationJson.SchemaEvaluation.SchemaNotFound) {
            rejection = problems.schemaNotFound(request.correlationId(), requestTarget);
        } else if (evaluation instanceof ContractValidationJson.SchemaEvaluation.Invalid invalid) {
            rejection = problems.documentSchemaInvalid(
                request.correlationId(), requestTarget, invalid.errors());
        }
        if (rejection != null) {
            StoredHttpResult storedRejection = rejection;
            return transactions.write(principal.tenantId(), tx -> {
                gate.completeRejection(tx, key, lease, storedRejection, resultTtl);
                return storedRejection;
            });
        }
        String documentDigest = documentDigest(body);

        return transactions.write(principal.tenantId(), tx -> {
            long version;
            try {
                version = gate.advance(
                    tx,
                    principal.tenantId(),
                    AGGREGATE_TYPE,
                    validationId,
                    request.expectedVersion());
            } catch (VersionConflict conflict) {
                StoredHttpResult versionRejection;
                if (conflict.actual() == null) {
                    versionRejection = problems.contractValidationNotFound(
                        request.correlationId(), requestTarget);
                } else if (conflict.expected() == Long.MAX_VALUE
                        && conflict.actual() == Long.MAX_VALUE) {
                    versionRejection = problems.versionLimitReached(
                        request.correlationId(), requestTarget);
                } else {
                    versionRejection = problems.versionConflict(
                        request.correlationId(),
                        requestTarget,
                        conflict.expected(),
                        conflict.actual());
                }
                gate.completeRejection(tx, key, lease, versionRejection, resultTtl);
                return versionRejection;
            }
            int changed;
            if (request.expectedVersion().value() == 0) {
                changed = tx.execute("""
                    INSERT INTO contract_validation (
                      tenant_id, validation_id, schema_id, document_digest, version
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    principal.tenantId(),
                    validationId,
                    body.schemaId().toASCIIString(),
                    documentDigest,
                    version);
            } else {
                changed = tx.execute("""
                    UPDATE contract_validation
                    SET schema_id=?, document_digest=?, version=?
                    WHERE tenant_id=? AND validation_id=? AND version=?
                    """,
                    body.schemaId().toASCIIString(),
                    documentDigest,
                    version,
                    principal.tenantId(),
                    validationId,
                    request.expectedVersion().value());
            }
            if (changed != 1) {
                throw new IllegalStateException(
                    "contract validation mutation affected an unexpected row count");
            }

            UUID reconciliationIntentId = reconciliation.record(
                tx, principal, validationId, version, documentDigest);
            String eventPayload = eventPayload(
                reconciliationIntentId,
                validationId,
                body.schemaId().toASCIIString(),
                documentDigest,
                version);
            UUID eventId = UUID.randomUUID();
            OffsetDateTime occurredAt = databaseNow(tx.fetchOne(
                "SELECT clock_timestamp() AS occurred_at"));
            events.append(
                tx,
                new DomainEvent(
                    principal.tenantId(),
                    eventId,
                    "tenant",
                    principal.tenantId().toString(),
                    AGGREGATE_TYPE,
                    validationId,
                    version,
                    "contract-validation.completed",
                    "1.0.0",
                    request.correlationId(),
                    request.correlationId(),
                    principal.actorId(),
                    eventPayload,
                    occurredAt),
                new OutboxMessage(
                    "contract-validations",
                    "contract-validation.completed/1.0.0",
                    eventPayload));

            StoredHttpResult result = createdResult(
                validationId, documentDigest, version);
            gate.complete(
                tx,
                key,
                lease,
                result,
                AGGREGATE_TYPE,
                validationId,
                version,
                resultTtl);
            return result;
        });
    }

    private String documentDigest(ContractValidationJson.DecodedRequest body) {
        try {
            return CanonicalJson.sha256(mapper.writeValueAsBytes(body.document()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("document cannot be serialized", error);
        }
    }

    private String eventPayload(
            UUID intentId,
            UUID validationId,
            String schemaId,
            String documentDigest,
            long version) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("intent_id", intentId.toString());
        payload.put("validation_id", validationId.toString());
        payload.put("schema_id", schemaId);
        payload.put("document_digest", documentDigest);
        payload.put("version", version);
        return canonicalString(payload);
    }

    private StoredHttpResult createdResult(
            UUID validationId, String documentDigest, long version) {
        try {
            byte[] serialized = mapper.writeValueAsBytes(
                new ContractValidationResponse(validationId, true, documentDigest, version));
            String body = new String(
                CanonicalJson.canonicalizePreservingExactIntegers(serialized), UTF_8);
            return new StoredHttpResult(
                201,
                Map.of("content-type", "application/json", "etag", "\"" + version + "\""),
                body);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("contract validation response cannot be serialized", error);
        }
    }

    private String canonicalString(ObjectNode value) {
        try {
            return new String(
                CanonicalJson.canonicalizePreservingExactIntegers(
                    mapper.writeValueAsBytes(value)),
                UTF_8);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("event payload cannot be serialized", error);
        }
    }

    private static OffsetDateTime databaseNow(Record row) {
        if (row == null) {
            throw new IllegalStateException("database clock returned no row");
        }
        OffsetDateTime value = row.get("occurred_at", OffsetDateTime.class);
        if (value == null) {
            throw new IllegalStateException("database clock returned no value");
        }
        return value;
    }
}
