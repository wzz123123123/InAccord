package com.inforvans.accord.controlplane.worker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;

/** Publishes shell-free worker liveness and readiness state into the pod's memory volume. */
public final class WorkerProbeStatePublisher implements SmartLifecycle {
    static final int PHASE = 0;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    private static final String LIVE_FILE = "worker-live";
    private static final String READY_FILE = "worker-ready";

    private final Path directory;
    private final Clock clock;
    private ScheduledExecutorService scheduler;
    private boolean ready;
    private volatile boolean running;

    public WorkerProbeStatePublisher(Path directory) {
        this(directory, Clock.systemUTC());
    }

    WorkerProbeStatePublisher(Path directory, Clock clock) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            prepareDirectory();
            publishState();
        } catch (IOException failure) {
            throw new IllegalStateException("worker probe state is unavailable", failure);
        }
        ScheduledExecutorService starting = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("accord-worker-probe-state").factory());
        starting.scheduleWithFixedDelay(
            this::heartbeat,
            HEARTBEAT_INTERVAL.toMillis(),
            HEARTBEAT_INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS);
        scheduler = starting;
        running = true;
    }

    @EventListener
    public synchronized void availabilityChanged(AvailabilityChangeEvent<?> event) {
        if (!(event.getState() instanceof ReadinessState state)) {
            return;
        }
        ready = state == ReadinessState.ACCEPTING_TRAFFIC;
        if (running) {
            try {
                publishState();
            } catch (IOException failure) {
                ready = false;
                throw new IllegalStateException("worker readiness state is unavailable", failure);
            }
        }
    }

    private synchronized void heartbeat() {
        if (!running) {
            return;
        }
        try {
            publishState();
        } catch (IOException failure) {
            running = false;
            throw new IllegalStateException("worker liveness state is unavailable", failure);
        }
    }

    private void publishState() throws IOException {
        byte[] timestamp = (Instant.now(clock).getEpochSecond() + "\n")
            .getBytes(StandardCharsets.US_ASCII);
        atomicWrite(directory.resolve(LIVE_FILE), timestamp);
        if (ready) {
            atomicWrite(directory.resolve(READY_FILE), timestamp);
        } else {
            Files.deleteIfExists(directory.resolve(READY_FILE));
        }
    }

    private void prepareDirectory() throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("worker probe state path is not a directory");
            }
        } else {
            Files.createDirectory(directory);
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(
            directory,
            ".worker-probe-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("atomic worker probe state replacement is unavailable", failure);
            }
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        ready = false;
        ScheduledExecutorService stopping = scheduler;
        scheduler = null;
        if (stopping != null) {
            stopping.shutdownNow();
        }
        try {
            Files.deleteIfExists(directory.resolve(READY_FILE));
            Files.deleteIfExists(directory.resolve(LIVE_FILE));
        } catch (IOException ignored) {
            // Container shutdown must continue; both state files become stale or absent.
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
}
