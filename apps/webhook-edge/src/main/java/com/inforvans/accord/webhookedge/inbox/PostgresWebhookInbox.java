package com.inforvans.accord.webhookedge.inbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inforvans.accord.webhookedge.webhook.ProviderWebhookSignal;
import com.inforvans.accord.webhookedge.webhook.WebhookInbox;
import com.inforvans.accord.webhookedge.webhook.WebhookRecordOutcome;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class PostgresWebhookInbox implements WebhookInbox {
    private final DSLContext database;
    private final ObjectMapper mapper;
    private final AfterInboxInsert afterInboxInsert;

    @Autowired
    public PostgresWebhookInbox(DSLContext database, ObjectMapper mapper) {
        this(database, mapper, () -> {});
    }

    PostgresWebhookInbox(
            DSLContext database,
            ObjectMapper mapper,
            AfterInboxInsert afterInboxInsert) {
        this.database = Objects.requireNonNull(database, "database");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.afterInboxInsert = Objects.requireNonNull(afterInboxInsert, "afterInboxInsert");
    }

    @Override
    public WebhookRecordOutcome record(ProviderWebhookSignal signal) {
        Objects.requireNonNull(signal, "signal");
        String serializedSignal;
        try {
            serializedSignal = mapper.writeValueAsString(signal);
        } catch (JsonProcessingException exception) {
            throw new WebhookInbox.UnavailableException();
        }

        try {
            return database.transactionResult(configuration -> {
                DSLContext transaction = DSL.using(configuration);
                String tenant = signal.tenantId().toString();
                Record installedContext = transaction.fetchOne(
                        "SELECT pg_catalog.set_config('app.tenant_id', ?, true) AS tenant_context",
                        tenant);
                if (installedContext == null
                        || !tenant.equals(installedContext.get("tenant_context", String.class))) {
                    throw new IllegalStateException("webhook tenant context unavailable");
                }

                Record inserted = transaction.fetchOne("""
                    INSERT INTO public.webhook_inbox (
                      tenant_id,scope_id,provider,immutable_repository_id,webhook_id,
                      event_type,body_digest,observed_at,ref,before_sha,after_sha)
                    VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,CAST(? AS uuid),?,?,
                      CAST(? AS timestamptz),?,?,?)
                    ON CONFLICT (tenant_id,provider,immutable_repository_id,webhook_id) DO NOTHING
                    RETURNING 1 AS inserted
                    """,
                        signal.tenantId(),
                        signal.scopeId(),
                        signal.provider(),
                        signal.immutableRepositoryId(),
                        signal.deliveryId(),
                        signal.eventType(),
                        signal.bodyDigest(),
                        OffsetDateTime.parse(signal.observedAt()),
                        signal.ref(),
                        signal.beforeSha(),
                        signal.afterSha());

                if (inserted != null) {
                    afterInboxInsert.run();
                    UUID signalId = UUID.randomUUID();
                    int outboxRows = transaction.execute("""
                        INSERT INTO public.webhook_outbox (
                          tenant_id,signal_id,scope_id,provider,immutable_repository_id,
                          webhook_id,schema_version,signal)
                        VALUES (CAST(? AS uuid),CAST(? AS uuid),CAST(? AS uuid),?,?,
                          CAST(? AS uuid),?,CAST(? AS jsonb))
                        """,
                            signal.tenantId(),
                            signalId,
                            signal.scopeId(),
                            signal.provider(),
                            signal.immutableRepositoryId(),
                            signal.deliveryId(),
                            signal.schemaVersion(),
                            serializedSignal);
                    if (outboxRows != 1) {
                        throw new IllegalStateException("webhook outbox insert failed");
                    }
                    return WebhookRecordOutcome.RECORDED;
                }

                Record stored = transaction.fetchOne("""
                    SELECT stored_tenant_id,stored_provider,stored_immutable_repository_id,
                           stored_webhook_id,stored_body_digest
                    FROM webhook_edge_security.lock_webhook_inbox(
                      CAST(? AS uuid),CAST(? AS varchar),?,CAST(? AS uuid))
                    """,
                        signal.tenantId(),
                        signal.provider(),
                        signal.immutableRepositoryId(),
                        signal.deliveryId());
                if (stored == null || !sameIdentity(stored, signal)) {
                    throw new IllegalStateException("webhook inbox identity unavailable");
                }
                String storedDigest = stored.get("stored_body_digest", String.class);
                return signal.bodyDigest().equals(storedDigest)
                        ? WebhookRecordOutcome.IDENTICAL_RETRY
                        : WebhookRecordOutcome.DIGEST_CONFLICT;
            });
        } catch (RuntimeException exception) {
            throw new WebhookInbox.UnavailableException();
        }
    }

    private static boolean sameIdentity(Record stored, ProviderWebhookSignal signal) {
        return signal.tenantId().equals(stored.get("stored_tenant_id", UUID.class))
                && signal.provider().equals(stored.get("stored_provider", String.class))
                && signal.immutableRepositoryId()
                        == stored.get("stored_immutable_repository_id", Long.class)
                && signal.deliveryId().equals(stored.get("stored_webhook_id", UUID.class));
    }

    @FunctionalInterface
    interface AfterInboxInsert {
        void run();
    }
}
