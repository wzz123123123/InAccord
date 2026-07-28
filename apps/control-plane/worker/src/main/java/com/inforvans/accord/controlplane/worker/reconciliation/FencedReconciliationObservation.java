package com.inforvans.accord.controlplane.worker.reconciliation;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationObservationPort;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowRef;
import com.inforvans.accord.platformkernel.CanonicalJson;
import com.inforvans.accord.observability.AccordWorkflowTelemetry;
import com.inforvans.accord.reliability.DomainEvent;
import com.inforvans.accord.reliability.ExternalIntentSnapshot;
import com.inforvans.accord.reliability.ExternalIntentState;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import com.inforvans.accord.reliability.LostExternalIntentFence;
import com.inforvans.accord.reliability.OutboxMessage;
import com.inforvans.accord.reliability.ReconciliationClaim;
import com.inforvans.accord.reliability.ReconciliationLease;
import com.inforvans.accord.reliability.ReconciliationResolution;
import io.temporal.activity.Activity;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class FencedReconciliationObservation implements ReconciliationObservationPort {
    private static final Duration RENEWAL = Duration.ofSeconds(30);
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration HEARTBEAT_INTERVAL_BUDGET = Duration.ofSeconds(8);

    private final WorkerTenantTransactions transactions;
    private final JooqExternalIntentStore store;
    private final ProviderObservationPort provider;
    private final ReconciliationRuntimeProperties properties;
    private final Consumer<UUID> heartbeat;
    private final Runnable terminalSuccessTelemetry;

    public FencedReconciliationObservation(
            WorkerTenantTransactions transactions,
            JooqExternalIntentStore store,
            ProviderObservationPort provider,
            ReconciliationRuntimeProperties properties) {
        this(transactions, store, provider, properties,
            intentId -> Activity.getExecutionContext().heartbeat(intentId),
            () -> {});
    }

    public FencedReconciliationObservation(
            WorkerTenantTransactions transactions,
            JooqExternalIntentStore store,
            ProviderObservationPort provider,
            ReconciliationRuntimeProperties properties,
            AccordWorkflowTelemetry telemetry) {
        this(transactions, store, provider, properties,
            intentId -> Activity.getExecutionContext().heartbeat(intentId),
            Objects.requireNonNull(telemetry, "telemetry")
                ::recordProviderReconciliationSuccess);
    }

    FencedReconciliationObservation(
            WorkerTenantTransactions transactions,
            JooqExternalIntentStore store,
            ProviderObservationPort provider,
            ReconciliationRuntimeProperties properties,
            Consumer<UUID> heartbeat) {
        this(transactions, store, provider, properties, heartbeat, () -> {});
    }

    FencedReconciliationObservation(
            WorkerTenantTransactions transactions,
            JooqExternalIntentStore store,
            ProviderObservationPort provider,
            ReconciliationRuntimeProperties properties,
            Consumer<UUID> heartbeat,
            Runnable terminalSuccessTelemetry) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.store = Objects.requireNonNull(store, "store");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.terminalSuccessTelemetry = Objects.requireNonNull(
            terminalSuccessTelemetry, "terminalSuccessTelemetry");
    }

    @Override
    public ReconciliationOutcome observe(ReconciliationWorkflowRef ref) {
        Objects.requireNonNull(ref, "ref");
        HeartbeatBudget budget = new HeartbeatBudget();
        ClaimResult claim = database(() -> transactions.inTenantWithinBudget(
            ref.tenantId(),
            () -> budget.remainingMillis(
                ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE),
            tx -> {
            ReconciliationClaim result = store.claimReconciliation(
                tx,
                ref.tenantId(),
                ref.intentId(),
                properties.instanceId(),
                properties.claimLease());
            if (result instanceof ReconciliationClaim.Missing) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_MISSING);
            }
            if (result instanceof ReconciliationClaim.NotReconcilable rejected) {
                ClaimResult terminal = new ClaimResult.Terminal(
                    mapPersistedState(rejected.state()));
                budget.ensureOpen(
                    ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
                return terminal;
            }
            ReconciliationLease claimed =
                ((ReconciliationClaim.Acquired) result).lease();
            ExternalIntentSnapshot snapshot = store.load(
                    tx, ref.tenantId(), ref.intentId())
                .orElseThrow(() -> ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_MISSING));
            ReconciliationLease renewed = store.renewReconciliation(
                tx, claimed, RENEWAL);
            ClaimResult acquired = new ClaimResult.Acquired(
                renewed,
                snapshot.scopeType(),
                snapshot.scopeId(),
                snapshot.rootIntentId());
            budget.ensureOpen(
                ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
            return acquired;
        }));
        budget.ensureOpen(ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
        if (claim instanceof ClaimResult.Terminal terminal) {
            return terminal.outcome();
        }
        ClaimResult.Acquired acquired = (ClaimResult.Acquired) claim;

        budget.heartbeat(ref.intentId(), heartbeat);
        budget.heartbeat(ref.intentId(), heartbeat);

        ReconciliationResolution resolution;
        ReconciliationFailure deferredFailure = null;
        try {
            budget.ensureRemaining(
                PROVIDER_TIMEOUT, ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
            long observationStartedNanos = System.nanoTime();
            resolution = provider.observe(acquired.lease(), PROVIDER_TIMEOUT);
            if (System.nanoTime() - observationStartedNanos
                    >= PROVIDER_TIMEOUT.toNanos()) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
            }
            if (resolution == null) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
            }
        } catch (ReconciliationFailure failure) {
            if (failure.code()
                    != ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE) {
                throw failure;
            }
            resolution = new ReconciliationResolution.StillUnknown(
                failure.code().name(), null);
            deferredFailure = failure;
        } catch (RuntimeException ignored) {
            throw ReconciliationFailure.of(
                ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
        }
        budget.ensureOpen(ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE);
        budget.heartbeat(ref.intentId(), heartbeat);
        budget.heartbeat(ref.intentId(), heartbeat);

        ReconciliationOutcome outcome = complete(
            ref, acquired, resolution, budget);
        budget.heartbeat(ref.intentId(), heartbeat);
        if (deferredFailure != null) {
            throw deferredFailure;
        }
        if (outcome != ReconciliationOutcome.STILL_UNKNOWN) {
            terminalSuccessTelemetry.run();
        }
        return outcome;
    }

    private ReconciliationOutcome complete(
            ReconciliationWorkflowRef ref,
            ClaimResult.Acquired acquired,
            ReconciliationResolution resolution,
            HeartbeatBudget budget) {
        return database(() -> transactions.inTenantWithinBudget(
            ref.tenantId(),
            () -> budget.remainingMillis(
                ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE),
            tx -> {
            if (resolution instanceof ReconciliationResolution.StillUnknown unknown) {
                store.markReconciliationOutcomeUnknown(tx, acquired.lease(), unknown);
                budget.ensureOpen(
                    ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
                return ReconciliationOutcome.STILL_UNKNOWN;
            }

            ReconciliationOutcome outcome = mapTerminal(
                (ReconciliationResolution.Terminal) resolution);
            UUID eventId = UUID.randomUUID();
            OffsetDateTime occurredAt = Objects.requireNonNull(
                tx.fetchOne("SELECT clock_timestamp()")
                    .get(0, OffsetDateTime.class),
                "database event time");
            String payload = canonicalPayload(ref.intentId(), outcome);
            DomainEvent event = new DomainEvent(
                ref.tenantId(),
                eventId,
                acquired.scopeType(),
                acquired.scopeId(),
                "external_intent",
                ref.intentId(),
                acquired.lease().generation(),
                "external_intent.completed",
                "1.0.0",
                eventId,
                acquired.rootIntentId(),
                properties.instanceId(),
                payload,
                occurredAt);
            OutboxMessage outbox = new OutboxMessage(
                "external-intents", "external-intent.event/1.0", payload);
            store.completeReconciliation(
                tx,
                acquired.lease(),
                (ReconciliationResolution.Terminal) resolution,
                event,
                outbox);
            budget.ensureOpen(
                ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
            return outcome;
        }));
    }

    private static ReconciliationOutcome mapPersistedState(ExternalIntentState state) {
        return switch (state) {
            case SUCCEEDED -> ReconciliationOutcome.CONVERGED;
            case CONFIRMED_NO_EFFECT -> ReconciliationOutcome.CONFIRMED_NO_EFFECT;
            case DIVERGED -> ReconciliationOutcome.DIVERGED;
            case RECONCILING -> throw ReconciliationFailure.of(
                ReconciliationFailure.Code.RECONCILIATION_BUSY);
            default -> throw ReconciliationFailure.of(
                ReconciliationFailure.Code.RECONCILIATION_NOT_RECONCILABLE);
        };
    }

    private static ReconciliationOutcome mapTerminal(
            ReconciliationResolution.Terminal terminal) {
        if (terminal instanceof ReconciliationResolution.Succeeded) {
            return ReconciliationOutcome.CONVERGED;
        }
        if (terminal instanceof ReconciliationResolution.ConfirmedNoEffect) {
            return ReconciliationOutcome.CONFIRMED_NO_EFFECT;
        }
        if (terminal instanceof ReconciliationResolution.Diverged) {
            return ReconciliationOutcome.DIVERGED;
        }
        throw ReconciliationFailure.of(ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
    }

    private static String canonicalPayload(UUID intentId, ReconciliationOutcome outcome) {
        String json = "{\"intent_id\":\"" + intentId
            + "\",\"outcome\":\"" + outcome.name() + "\"}";
        return new String(CanonicalJson.canonicalize(json.getBytes(UTF_8)), UTF_8);
    }

    private static <T> T database(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ReconciliationFailure failure) {
            throw failure;
        } catch (LostExternalIntentFence ignored) {
            throw ReconciliationFailure.of(
                ReconciliationFailure.Code.RECONCILIATION_FENCE_LOST);
        } catch (RuntimeException failure) {
            if (hasRetryableDatabaseEvidence(failure)) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_PERSISTENCE_UNAVAILABLE);
            }
            throw ReconciliationFailure.of(
                ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
        }
    }

    private static boolean hasRetryableDatabaseEvidence(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof SQLException sql) {
                String sqlState = sql.getSQLState();
                if (sqlState != null && (sqlState.startsWith("08")
                        || sqlState.startsWith("40")
                        || sqlState.startsWith("53")
                        || "57014".equals(sqlState)
                        || "25P04".equals(sqlState)
                        || "57P01".equals(sqlState))) {
                    return true;
                }
                if (sqlState == null
                        && sql instanceof java.sql.SQLTransientConnectionException) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private sealed interface ClaimResult {
        record Terminal(ReconciliationOutcome outcome) implements ClaimResult {
            public Terminal {
                Objects.requireNonNull(outcome, "outcome");
            }
        }

        record Acquired(
            ReconciliationLease lease,
            String scopeType,
            String scopeId,
            UUID rootIntentId
        ) implements ClaimResult {
            public Acquired {
                Objects.requireNonNull(lease, "lease");
                Objects.requireNonNull(scopeType, "scopeType");
                Objects.requireNonNull(scopeId, "scopeId");
                Objects.requireNonNull(rootIntentId, "rootIntentId");
            }
        }
    }

    private static final class HeartbeatBudget {
        private long deadlineNanos = System.nanoTime() + HEARTBEAT_INTERVAL_BUDGET.toNanos();

        void ensureRemaining(Duration required, ReconciliationFailure.Code code) {
            if (required.isNegative() || required.isZero()
                    || deadlineNanos - System.nanoTime() < required.toNanos()) {
                throw ReconciliationFailure.of(code);
            }
        }

        void ensureOpen(ReconciliationFailure.Code code) {
            if (deadlineNanos - System.nanoTime() <= 0) {
                throw ReconciliationFailure.of(code);
            }
        }

        private long remainingMillis(ReconciliationFailure.Code code) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            long remainingMillis = Duration.ofNanos(Math.max(remainingNanos, 0)).toMillis();
            if (remainingMillis <= 0) {
                throw ReconciliationFailure.of(code);
            }
            return remainingMillis;
        }

        void heartbeat(UUID intentId, Consumer<UUID> callback) {
            ensureOpen(ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
            long previousDeadlineNanos = deadlineNanos;
            callback.accept(intentId);
            long completedAtNanos = System.nanoTime();
            if (previousDeadlineNanos - completedAtNanos <= 0) {
                throw ReconciliationFailure.of(
                    ReconciliationFailure.Code.RECONCILIATION_INTERNAL);
            }
            deadlineNanos = completedAtNanos + HEARTBEAT_INTERVAL_BUDGET.toNanos();
        }
    }
}
