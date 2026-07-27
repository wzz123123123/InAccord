package com.inforvans.accord.controlplane.worker;

import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationRuntimeProperties;
import com.inforvans.accord.controlplane.worker.reconciliation.WorkerTenantTransactions;
import com.inforvans.accord.reliability.EventTransport;
import com.inforvans.accord.reliability.IdempotencyResultCleaner;
import com.inforvans.accord.reliability.InboxDispatcher;
import com.inforvans.accord.reliability.InboxRepository;
import com.inforvans.accord.reliability.LeasedEvent;
import com.inforvans.accord.reliability.LeasedInboxMessage;
import com.inforvans.accord.reliability.OutboxDispatcher;
import com.inforvans.accord.reliability.OutboxRepository;
import com.inforvans.accord.reliability.TenantWorkPermit;
import com.inforvans.accord.reliability.TenantWorkRepository;
import com.inforvans.accord.reliability.TransactionalInboxHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "accord.process-role", havingValue = "control-worker")
@EnableConfigurationProperties(WorkerReliabilityProperties.class)
public class WorkerScheduling implements WorkerDrainCoordinator.DrainControl {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerScheduling.class);
    private static final Duration BASE_RETRY_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofMinutes(5);
    private static final Duration MINIMUM_DRAIN_RESCHEDULE = Duration.ofSeconds(1);
    private static final String DRAIN_ERROR_CODE = "WORKER_DRAINING";

    private final Object lifecycleMonitor = new Object();
    private final WorkerReliabilityProperties properties;
    private final String owner;
    private final DirectoryTransactions directory;
    private final WorkerTenantTransactions tenantTransactions;
    private final TenantWorkRepository tenantWorkRepository;
    private final OutboxRepository outboxRepository;
    private final InboxRepository inboxRepository;
    private final IdempotencyResultCleaner cleaner;
    private final OutboxDispatcher outboxDispatcher;
    private final InboxDispatcher inboxDispatcher;

    private boolean acceptingAcquisitions;
    private int inFlightTurns;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledPoll;

    public WorkerScheduling(
            DSLContext workerDsl,
            WorkerTenantTransactions tenantTransactions,
            WorkerReliabilityProperties properties,
            ReconciliationRuntimeProperties reconciliation,
            ObjectProvider<DestinationAdapter> destinationAdapters,
            ObjectProvider<InboxHandlerAdapter> inboxHandlerAdapters) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.owner = requireAdapterKey(
            Objects.requireNonNull(reconciliation, "reconciliation").instanceId()
                + ":reliability",
            "worker owner",
            255);
        this.tenantTransactions = Objects.requireNonNull(
            tenantTransactions, "tenantTransactions");
        this.tenantWorkRepository = new TenantWorkRepository();
        this.outboxRepository = new OutboxRepository();
        this.inboxRepository = new InboxRepository();
        this.cleaner = new IdempotencyResultCleaner();
        this.directory = new DirectoryTransactions(
            Objects.requireNonNull(workerDsl, "workerDsl"), tenantWorkRepository);

        EventTransport transport = routingTransport(
            Objects.requireNonNull(destinationAdapters, "destinationAdapters")
                .orderedStream().toList());
        Map<String, InboxDispatcher.HandlerRegistration> handlers = handlerRegistry(
            Objects.requireNonNull(inboxHandlerAdapters, "inboxHandlerAdapters")
                .orderedStream().toList());
        OutboxDispatcher.PermitFinisher finisher = (permit, ignored) -> finishPermit(permit);
        this.outboxDispatcher = new OutboxDispatcher(
            transport,
            outboxRepository,
            tenantTransactions::inTenant,
            finisher,
            properties.transportTimeout(),
            properties.messageLease(),
            properties.concurrency(),
            properties.maxAttempts(),
            BASE_RETRY_BACKOFF,
            MAX_RETRY_BACKOFF);
        this.inboxDispatcher = new InboxDispatcher(
            inboxRepository,
            handlers,
            tenantTransactions::inTenant,
            finisher,
            properties.maxAttempts(),
            BASE_RETRY_BACKOFF,
            MAX_RETRY_BACKOFF);
    }

    @Bean
    OutboxDispatcher outboxDispatcher() {
        return outboxDispatcher;
    }

    @Bean
    InboxDispatcher inboxDispatcher() {
        return inboxDispatcher;
    }

    @Bean
    IdempotencyResultCleaner idempotencyResultCleaner() {
        return cleaner;
    }

    @Bean
    WorkerDrainCoordinator workerDrainCoordinator(ApplicationEventPublisher events) {
        return new WorkerDrainCoordinator(this, properties, events);
    }

    @Bean
    @ConditionalOnProperty(
        name = "accord.worker.probe-state.enabled", havingValue = "true")
    WorkerProbeStatePublisher workerProbeStatePublisher(
            @Value("${accord.worker.probe-state.directory}") String directory) {
        return new WorkerProbeStatePublisher(java.nio.file.Path.of(directory));
    }

    @Override
    public void startScheduling() {
        synchronized (lifecycleMonitor) {
            if (scheduler != null && !scheduler.isShutdown()) {
                return;
            }
            acceptingAcquisitions = true;
            ScheduledExecutorService starting = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("accord-reliability-scheduler").factory());
            try {
                scheduledPoll = starting.scheduleWithFixedDelay(
                    this::pollSafely,
                    0,
                    properties.pollDelay().toNanos(),
                    TimeUnit.NANOSECONDS);
                scheduler = starting;
            } catch (RuntimeException | Error failure) {
                acceptingAcquisitions = false;
                starting.shutdownNow();
                throw failure;
            }
        }
    }

    @Override
    public void quiesce() {
        ScheduledExecutorService stopping;
        synchronized (lifecycleMonitor) {
            acceptingAcquisitions = false;
            if (scheduledPoll != null) {
                scheduledPoll.cancel(false);
            }
            stopping = scheduler;
        }
        if (stopping != null) {
            stopping.shutdown();
        }
    }

    @Override
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long remaining = timeout.toNanos();
        long deadline = System.nanoTime() + remaining;
        synchronized (lifecycleMonitor) {
            while (inFlightTurns != 0 && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining);
                remaining = deadline - System.nanoTime();
            }
            return inFlightTurns == 0;
        }
    }

    @Override
    public void forceStop() {
        ScheduledExecutorService stopping;
        synchronized (lifecycleMonitor) {
            acceptingAcquisitions = false;
            if (scheduledPoll != null) {
                scheduledPoll.cancel(true);
            }
            stopping = scheduler;
        }
        if (stopping != null) {
            stopping.shutdownNow();
        }
    }

    void pollOnce() {
        Acquisition acquisition = acquireTurn();
        if (!acquisition.entered()) {
            return;
        }
        try {
            acquisition.permit().ifPresent(this::processPermit);
        } finally {
            finishTurn();
        }
    }

    boolean isAcceptingAcquisitions() {
        synchronized (lifecycleMonitor) {
            return acceptingAcquisitions;
        }
    }

    int inFlightTurns() {
        synchronized (lifecycleMonitor) {
            return inFlightTurns;
        }
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (RuntimeException failure) {
            LOGGER.warn(
                "Reliable worker turn failed; database fences will recover the lease ({})",
                failure.getClass().getName());
        }
    }

    private Acquisition acquireTurn() {
        synchronized (lifecycleMonitor) {
            if (!acceptingAcquisitions) {
                return Acquisition.notEntered();
            }
            inFlightTurns++;
            try {
                return new Acquisition(
                    true, directory.acquire(owner, properties.tenantPermitLease()));
            } catch (RuntimeException | Error failure) {
                inFlightTurns--;
                lifecycleMonitor.notifyAll();
                throw failure;
            }
        }
    }

    private void finishTurn() {
        synchronized (lifecycleMonitor) {
            inFlightTurns--;
            lifecycleMonitor.notifyAll();
        }
    }

    private void processPermit(TenantWorkPermit permit) {
        if (!mayStartWork()) {
            finishPermit(permit);
            return;
        }
        ChannelReadiness readiness = tenantTransactions.inTenant(
            permit.tenantId(), tx -> readReadiness(tx, permit));
        Optional<WorkChannel> selected = selectChannel(permit, readiness);
        if (selected.isEmpty()) {
            directory.finish(permit, Optional.empty());
            return;
        }
        switch (selected.orElseThrow()) {
            case OUTBOX -> runOutboxTurn(permit);
            case INBOX -> runInboxTurn(permit);
            case CLEANUP -> runCleanupTurn(permit);
        }
    }

    private void runOutboxTurn(TenantWorkPermit permit) {
        int leaseLimit = Math.min(properties.batchSize(), properties.concurrency());
        List<LeasedEvent> leased = tenantTransactions.inTenant(
            permit.tenantId(), tx -> outboxRepository.lease(
                tx, permit, owner, leaseLimit, properties.messageLease()));
        if (!mayStartWork()) {
            rescheduleOutbox(permit, leased);
            finishPermit(permit);
            return;
        }
        outboxDispatcher.dispatch(permit, leased);
    }

    private void runInboxTurn(TenantWorkPermit permit) {
        List<LeasedInboxMessage> leased = tenantTransactions.inTenant(
            permit.tenantId(), tx -> inboxRepository.lease(
                tx, permit, owner, 1, properties.messageLease()));
        if (!mayStartWork()) {
            rescheduleInbox(permit, leased);
            finishPermit(permit);
            return;
        }
        inboxDispatcher.dispatch(permit, leased);
    }

    private void runCleanupTurn(TenantWorkPermit permit) {
        if (!mayStartWork()) {
            finishPermit(permit);
            return;
        }
        tenantTransactions.inTenant(permit.tenantId(), tx -> {
            tenantWorkRepository.lock(tx, permit);
            cleaner.clean(tx, properties.cleanupBatchSize());
            return null;
        });
        finishPermit(permit);
    }

    private boolean mayStartWork() {
        synchronized (lifecycleMonitor) {
            return acceptingAcquisitions && !Thread.currentThread().isInterrupted();
        }
    }

    private void rescheduleOutbox(TenantWorkPermit permit, List<LeasedEvent> leased) {
        if (leased.isEmpty()) {
            return;
        }
        long delayMicros = drainRescheduleDelayMicros();
        Duration delay = Duration.ofNanos(delayMicros * 1_000);
        tenantTransactions.inTenant(permit.tenantId(), tx -> {
            for (LeasedEvent event : leased) {
                outboxRepository.release(
                    tx, permit, event, delay, DRAIN_ERROR_CODE);
            }
            return null;
        });
    }

    private void rescheduleInbox(
            TenantWorkPermit permit, List<LeasedInboxMessage> leased) {
        if (leased.isEmpty()) {
            return;
        }
        long delayMicros = drainRescheduleDelayMicros();
        Duration delay = Duration.ofNanos(delayMicros * 1_000);
        tenantTransactions.inTenant(permit.tenantId(), tx -> {
            for (LeasedInboxMessage message : leased) {
                inboxRepository.release(
                    tx, permit, message, delay, DRAIN_ERROR_CODE);
            }
            return null;
        });
    }

    private long drainRescheduleDelayMicros() {
        Duration delay = properties.pollDelay().compareTo(MINIMUM_DRAIN_RESCHEDULE) < 0
            ? MINIMUM_DRAIN_RESCHEDULE
            : properties.pollDelay();
        return delay.toNanos() / 1_000;
    }

    private void finishPermit(TenantWorkPermit permit) {
        ChannelReadiness readiness = tenantTransactions.inTenant(
            permit.tenantId(), tx -> readReadiness(tx, permit));
        directory.finish(permit, readiness.earliest());
    }

    private ChannelReadiness readReadiness(DSLContext tx, TenantWorkPermit permit) {
        tenantWorkRepository.lock(tx, permit);
        Optional<OffsetDateTime> outbox = outboxRepository.earliestAvailableAt(tx, permit);
        Optional<OffsetDateTime> inbox = inboxRepository.earliestAvailableAt(tx, permit);
        Record cleanupRow = tx.fetchOne("""
            SELECT MIN(expires_at) AS ready_at
            FROM public.idempotency_result
            WHERE tenant_id=CAST(? AS uuid) AND state='COMPLETED'
            """, permit.tenantId());
        Optional<OffsetDateTime> cleanup = cleanupRow == null
            ? Optional.empty()
            : Optional.ofNullable(cleanupRow.get("ready_at", OffsetDateTime.class));
        return new ChannelReadiness(outbox, inbox, cleanup);
    }

    static Optional<WorkChannel> selectChannel(
            TenantWorkPermit permit, ChannelReadiness readiness) {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(readiness, "readiness");
        Optional<OffsetDateTime> earliest = readiness.earliest();
        if (earliest.isEmpty()) {
            return Optional.empty();
        }
        OffsetDateTime readyAt = earliest.orElseThrow();
        List<WorkChannel> tied = new ArrayList<>(3);
        addIfTied(tied, WorkChannel.OUTBOX, readiness.outbox(), readyAt);
        addIfTied(tied, WorkChannel.INBOX, readiness.inbox(), readyAt);
        addIfTied(tied, WorkChannel.CLEANUP, readiness.cleanup(), readyAt);
        int selected = (int) Math.floorMod(
            permit.fence().generation() - 1L, (long) tied.size());
        return Optional.of(tied.get(selected));
    }

    private static void addIfTied(
            List<WorkChannel> tied,
            WorkChannel channel,
            Optional<OffsetDateTime> candidate,
            OffsetDateTime earliest) {
        if (candidate.isPresent()
                && candidate.orElseThrow().toInstant().equals(earliest.toInstant())) {
            tied.add(channel);
        }
    }

    static EventTransport routingTransport(List<DestinationAdapter> adapters) {
        Map<String, EventTransport> registry = new LinkedHashMap<>();
        for (DestinationAdapter adapter : List.copyOf(adapters)) {
            EventTransport previous = registry.putIfAbsent(
                adapter.destination(), adapter.transport());
            if (previous != null) {
                throw new IllegalStateException(
                    "duplicate event transport destination: " + adapter.destination());
            }
        }
        Map<String, EventTransport> immutable = Map.copyOf(registry);
        return (event, timeout) -> {
            EventTransport transport = immutable.get(event.destination());
            if (transport == null) {
                throw new IllegalStateException(
                    "no event transport is registered for the requested destination");
            }
            return Objects.requireNonNull(
                transport.deliver(event, timeout), "event transport receipt");
        };
    }

    static Map<String, InboxDispatcher.HandlerRegistration> handlerRegistry(
            List<InboxHandlerAdapter> adapters) {
        Map<String, InboxDispatcher.HandlerRegistration> registry = new LinkedHashMap<>();
        for (InboxHandlerAdapter adapter : List.copyOf(adapters)) {
            InboxDispatcher.HandlerRegistration previous = registry.putIfAbsent(
                adapter.handlerKey(),
                new InboxDispatcher.HandlerRegistration(
                    adapter.payloadSchema(), adapter.handler()));
            if (previous != null) {
                throw new IllegalStateException(
                    "duplicate inbox handler key: " + adapter.handlerKey());
            }
        }
        return Map.copyOf(registry);
    }

    private static String requireAdapterKey(String value, String name, int maximumBytes) {
        Objects.requireNonNull(value, name);
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 1 || bytes > maximumBytes || value.isBlank()
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    public record DestinationAdapter(String destination, EventTransport transport) {
        public DestinationAdapter {
            destination = requireAdapterKey(destination, "destination", 128);
            Objects.requireNonNull(transport, "transport");
        }
    }

    public record InboxHandlerAdapter(
        String handlerKey,
        String payloadSchema,
        TransactionalInboxHandler handler
    ) {
        public InboxHandlerAdapter {
            handlerKey = requireAdapterKey(handlerKey, "handlerKey", 128);
            payloadSchema = requireAdapterKey(payloadSchema, "payloadSchema", 255);
            Objects.requireNonNull(handler, "handler");
        }
    }

    enum WorkChannel {
        OUTBOX,
        INBOX,
        CLEANUP
    }

    record ChannelReadiness(
        Optional<OffsetDateTime> outbox,
        Optional<OffsetDateTime> inbox,
        Optional<OffsetDateTime> cleanup
    ) {
        ChannelReadiness {
            Objects.requireNonNull(outbox, "outbox");
            Objects.requireNonNull(inbox, "inbox");
            Objects.requireNonNull(cleanup, "cleanup");
        }

        Optional<OffsetDateTime> earliest() {
            return List.of(outbox, inbox, cleanup).stream()
                .flatMap(Optional::stream)
                .min(Comparator.comparing(OffsetDateTime::toInstant));
        }
    }

    private record Acquisition(
        boolean entered,
        Optional<TenantWorkPermit> permit
    ) {
        private Acquisition {
            Objects.requireNonNull(permit, "permit");
        }

        static Acquisition notEntered() {
            return new Acquisition(false, Optional.empty());
        }
    }

    private static final class DirectoryTransactions {
        private final DSLContext context;
        private final TenantWorkRepository repository;

        private DirectoryTransactions(DSLContext context, TenantWorkRepository repository) {
            this.context = Objects.requireNonNull(context, "context");
            this.repository = Objects.requireNonNull(repository, "repository");
            context.transactionResult(configuration -> {
                assertDirectoryIdentity(DSL.using(configuration));
                return null;
            });
        }

        private Optional<TenantWorkPermit> acquire(String owner, Duration lease) {
            return context.transactionResult(configuration -> {
                DSLContext tx = DSL.using(configuration);
                assertDirectoryIdentity(tx);
                return repository.acquire(tx, owner, lease);
            });
        }

        @SuppressWarnings("unused")
        private TenantWorkPermit renew(TenantWorkPermit permit, Duration extension) {
            return context.transactionResult(configuration -> {
                DSLContext tx = DSL.using(configuration);
                assertDirectoryIdentity(tx);
                return repository.renew(tx, permit, extension);
            });
        }

        private void finish(
                TenantWorkPermit permit, Optional<OffsetDateTime> nextAvailableAt) {
            Objects.requireNonNull(nextAvailableAt, "nextAvailableAt");
            context.transactionResult(configuration -> {
                DSLContext tx = DSL.using(configuration);
                assertDirectoryIdentity(tx);
                if (nextAvailableAt.isPresent()) {
                    repository.finish(tx, permit, nextAvailableAt.orElseThrow());
                } else {
                    repository.finishNoKnownWork(tx, permit);
                }
                return null;
            });
        }

        private static void assertDirectoryIdentity(DSLContext tx) {
            Record identity = tx.fetchOne("""
                SELECT current_user AS session_role,
                       accord_security.current_tenant_id() AS tenant_id
                """);
            if (identity == null
                    || !"accord_worker".equals(
                        identity.get("session_role", String.class))
                    || identity.get("tenant_id", UUID.class) != null) {
                throw new IllegalStateException(
                    "worker directory transaction requires accord_worker without a tenant");
            }
        }
    }
}
