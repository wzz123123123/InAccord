package com.inforvans.accord.controlplane.worker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

public final class WorkerDrainCoordinator implements SmartLifecycle {
    public static final int PHASE = 200;

    public enum DrainState {
        RUNNING,
        QUIESCING,
        DRAINED
    }

    interface DrainControl {
        void startScheduling();

        void quiesce();

        boolean awaitIdle(Duration timeout) throws InterruptedException;

        void forceStop();
    }

    private final DrainControl scheduling;
    private final Duration drainTimeout;
    private final Consumer<ReadinessState> readiness;
    private final AtomicReference<DrainState> state =
        new AtomicReference<>(DrainState.DRAINED);

    private volatile boolean running;

    public WorkerDrainCoordinator(
            WorkerScheduling scheduling,
            WorkerReliabilityProperties properties,
            ApplicationEventPublisher events) {
        this(scheduling, properties.drainTimeout(), readiness ->
            events.publishEvent(new AvailabilityChangeEvent<>(scheduling, readiness)));
    }

    WorkerDrainCoordinator(
            DrainControl scheduling,
            Duration drainTimeout,
            Consumer<ReadinessState> readiness) {
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.drainTimeout = requirePositive(drainTimeout);
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        scheduling.startScheduling();
        state.set(DrainState.RUNNING);
        running = true;
        readiness.accept(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Override
    public synchronized void stop() {
        if (!running && state.get() == DrainState.DRAINED) {
            return;
        }
        state.set(DrainState.QUIESCING);
        scheduling.quiesce();
        readiness.accept(ReadinessState.REFUSING_TRAFFIC);
        try {
            if (!scheduling.awaitIdle(drainTimeout)) {
                scheduling.forceStop();
            }
        } catch (InterruptedException interrupted) {
            scheduling.forceStop();
            Thread.currentThread().interrupt();
        } finally {
            state.set(DrainState.DRAINED);
            running = false;
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    public DrainState state() {
        return state.get();
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "drainTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("drainTimeout must be positive");
        }
        return timeout;
    }
}
