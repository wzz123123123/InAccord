package com.inforvans.accord.reliability;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InboxDispatcher {
    private static final String UNKNOWN_HANDLER = "UNKNOWN_HANDLER";
    private static final String SCHEMA_MISMATCH = "SCHEMA_MISMATCH";
    private static final String HANDLER_FAILURE = "HANDLER_FAILED";

    private final InboxRepository repository;
    private final Map<String, HandlerRegistration> handlers;
    private final OutboxDispatcher.TenantTransactions transactions;
    private final OutboxDispatcher.PermitFinisher permitFinisher;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public InboxDispatcher(
            InboxRepository repository,
            Map<String, HandlerRegistration> handlers,
            OutboxDispatcher.TenantTransactions transactions,
            OutboxDispatcher.PermitFinisher permitFinisher,
            int maxAttempts,
            Duration baseBackoff,
            Duration maxBackoff) {
        this.repository = Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(handlers, "handlers");
        if (handlers.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("handler registry contains null");
        }
        this.handlers = Map.copyOf(handlers);
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.permitFinisher = Objects.requireNonNull(permitFinisher, "permitFinisher");
        OutboxRepository.requireAttempts(maxAttempts);
        this.maxAttempts = maxAttempts;
        TenantWorkRepository.durationMicros(baseBackoff, "baseBackoff");
        TenantWorkRepository.durationMicros(maxBackoff, "maxBackoff");
        if (baseBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("baseBackoff exceeds maxBackoff");
        }
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
    }

    public DispatchResult dispatch(
            TenantWorkPermit permit, List<LeasedInboxMessage> leasedMessages) {
        TenantWorkRepository.requirePermit(permit);
        List<LeasedInboxMessage> messages = List.copyOf(
            Objects.requireNonNull(leasedMessages, "leasedMessages"));
        if (messages.size() > 500) {
            throw new IllegalArgumentException("leasedMessages exceeds the bounded batch");
        }
        int completed = 0;
        int failed = 0;
        boolean uncertain = false;
        for (LeasedInboxMessage message : messages) {
            if (!permit.tenantId().equals(message.tenantId())) {
                throw new IllegalArgumentException("inbox tenant does not match permit");
            }
            try {
                if (processOne(permit, message)) {
                    completed++;
                } else {
                    failed++;
                }
            } catch (RuntimeException error) {
                uncertain = true;
                break;
            }
        }
        if (!uncertain) {
            Optional<OffsetDateTime> next = transactions.inTenant(
                permit.tenantId(), tx -> repository.earliestAvailableAt(tx, permit));
            permitFinisher.finish(permit, next);
        }
        return new DispatchResult(completed, failed, uncertain);
    }

    private boolean processOne(TenantWorkPermit permit, LeasedInboxMessage message) {
        Optional<HandlerReceipt> stored = transactions.inTenant(
            permit.tenantId(), tx -> repository.findReceipt(tx, permit, message));
        if (stored.isPresent()) {
            String digest = stored.orElseThrow().receiptDigest();
            transactions.inTenant(permit.tenantId(), tx -> {
                repository.lockMessage(tx, permit, message);
                repository.complete(tx, permit, message, digest);
                return null;
            });
            return true;
        }

        HandlerRegistration registration = handlers.get(message.handlerKey());
        if (registration == null) {
            fail(permit, message, UNKNOWN_HANDLER);
            return false;
        }
        if (!registration.payloadSchema().equals(message.payloadSchema())) {
            fail(permit, message, SCHEMA_MISMATCH);
            return false;
        }
        try {
            transactions.inTenant(permit.tenantId(), tx -> {
                repository.lockMessage(tx, permit, message);
                HandlerReceipt receipt = Objects.requireNonNull(
                    registration.handler().handle(tx, message), "handler receipt");
                repository.recordReceipt(tx, permit, message, receipt);
                repository.complete(tx, permit, message, receipt.receiptDigest());
                return null;
            });
            return true;
        } catch (RuntimeException error) {
            fail(permit, message, HANDLER_FAILURE);
            return false;
        }
    }

    private void fail(
            TenantWorkPermit permit, LeasedInboxMessage message, String errorCode) {
        transactions.inTenant(permit.tenantId(), tx -> {
            repository.recordFailure(tx, permit, message, errorCode,
                maxAttempts, baseBackoff, maxBackoff);
            return null;
        });
    }

    public record HandlerRegistration(
        String payloadSchema,
        TransactionalInboxHandler handler
    ) {
        public HandlerRegistration {
            payloadSchema = CommandKey.requireBounded(
                payloadSchema, "payloadSchema", 255);
            Objects.requireNonNull(handler, "handler");
        }
    }

    public record DispatchResult(int completed, int failed, boolean uncertain) {
        public DispatchResult {
            if (completed < 0 || failed < 0) {
                throw new IllegalArgumentException("dispatch counts cannot be negative");
            }
        }
    }
}
