package com.inforvans.accord.reliability;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.jooq.DSLContext;

public final class OutboxDispatcher {
    private static final String DELIVERY_FAILURE = "DELIVERY_FAILED";

    private final EventTransport transport;
    private final OutboxRepository repository;
    private final TenantTransactions transactions;
    private final PermitFinisher permitFinisher;
    private final Duration transportTimeout;
    private final int concurrency;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public OutboxDispatcher(
            EventTransport transport,
            OutboxRepository repository,
            TenantTransactions transactions,
            PermitFinisher permitFinisher,
            Duration transportTimeout,
            Duration messageLease,
            int concurrency,
            int maxAttempts,
            Duration baseBackoff,
            Duration maxBackoff) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.permitFinisher = Objects.requireNonNull(permitFinisher, "permitFinisher");
        this.transportTimeout = requireTimeout(transportTimeout, messageLease);
        if (concurrency < 1 || concurrency > 256) {
            throw new IllegalArgumentException("concurrency must be between 1 and 256");
        }
        this.concurrency = concurrency;
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
            TenantWorkPermit permit, List<LeasedEvent> leasedEvents) {
        TenantWorkRepository.requirePermit(permit);
        List<LeasedEvent> events = List.copyOf(
            Objects.requireNonNull(leasedEvents, "leasedEvents"));
        if (events.size() > 500) {
            throw new IllegalArgumentException("leasedEvents exceeds the bounded batch");
        }
        for (LeasedEvent event : events) {
            if (!permit.tenantId().equals(event.tenantId())) {
                throw new IllegalArgumentException("leased event tenant does not match permit");
            }
        }

        int delivered = 0;
        int failed = 0;
        boolean uncertain = false;
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            List<Future<EventResult>> futures = new ArrayList<>(events.size());
            for (LeasedEvent event : events) {
                futures.add(executor.submit(() -> deliverOne(permit, event)));
            }
            for (Future<EventResult> future : futures) {
                try {
                    EventResult result = future.get();
                    delivered += result == EventResult.DELIVERED ? 1 : 0;
                    failed += result == EventResult.FAILED ? 1 : 0;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    uncertain = true;
                    break;
                } catch (ExecutionException error) {
                    uncertain = true;
                }
            }
        }

        if (!uncertain) {
            Optional<OffsetDateTime> next = transactions.inTenant(
                permit.tenantId(), tx -> repository.earliestAvailableAt(tx, permit));
            permitFinisher.finish(permit, next);
        }
        return new DispatchResult(delivered, failed, uncertain);
    }

    private EventResult deliverOne(TenantWorkPermit permit, LeasedEvent event) {
        Optional<DeliveryReceipt> existing = transactions.inTenant(
            permit.tenantId(), tx -> {
                Optional<DeliveryReceipt> stored = repository.findReceipt(tx, permit, event);
                if (stored.isEmpty()) {
                    repository.assertTransportWindow(
                        tx, permit, event, transportTimeout);
                }
                return stored;
            });
        DeliveryReceipt receipt;
        if (existing.isPresent()) {
            receipt = existing.orElseThrow();
        } else {
            try {
                receipt = Objects.requireNonNull(
                    transport.deliver(event, transportTimeout), "transport receipt");
            } catch (RuntimeException error) {
                transactions.inTenant(permit.tenantId(), tx -> {
                    repository.recordFailure(tx, permit, event, DELIVERY_FAILURE,
                        maxAttempts, baseBackoff, maxBackoff);
                    return null;
                });
                return EventResult.FAILED;
            }
            DeliveryReceipt completed = receipt;
            transactions.inTenant(permit.tenantId(), tx -> {
                repository.recordReceipt(tx, permit, event, completed);
                return null;
            });
        }
        String storedDigest = receipt.receiptDigest();
        transactions.inTenant(permit.tenantId(), tx -> {
            repository.acknowledge(tx, permit, event, storedDigest);
            return null;
        });
        return EventResult.DELIVERED;
    }

    private static Duration requireTimeout(Duration timeout, Duration messageLease) {
        Objects.requireNonNull(timeout, "transportTimeout");
        Objects.requireNonNull(messageLease, "messageLease");
        TenantWorkRepository.durationMicros(timeout, "transportTimeout");
        TenantWorkRepository.durationMicros(messageLease, "messageLease");
        if (timeout.compareTo(messageLease) >= 0) {
            throw new IllegalArgumentException("transportTimeout must be shorter than messageLease");
        }
        return timeout;
    }

    private enum EventResult { DELIVERED, FAILED }

    public record DispatchResult(int delivered, int failed, boolean uncertain) {
        public DispatchResult {
            if (delivered < 0 || failed < 0) {
                throw new IllegalArgumentException("dispatch counts cannot be negative");
            }
        }
    }

    @FunctionalInterface
    public interface TenantTransactions {
        <T> T inTenant(UUID tenantId, Function<DSLContext, T> work);
    }

    @FunctionalInterface
    public interface PermitFinisher {
        void finish(TenantWorkPermit permit, Optional<OffsetDateTime> nextAvailableAt);
    }
}
