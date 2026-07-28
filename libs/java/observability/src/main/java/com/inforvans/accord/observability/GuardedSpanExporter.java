package com.inforvans.accord.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Last-mile span exporter that rejects complete unsafe spans before delegation. */
public final class GuardedSpanExporter implements SpanExporter {
    private final SpanExporter delegate;
    private final TelemetryRecordGuard guard;
    private final ExporterGuardSupport support;

    public GuardedSpanExporter(
            SpanExporter delegate,
            TelemetryRecordGuard guard,
            TelemetryGuardMetrics metrics,
            Duration exportTimeout,
            int maximumConcurrentExports) {
        this.delegate = Objects.requireNonNull(delegate, "span exporter must not be null");
        this.guard = Objects.requireNonNull(guard, "telemetry guard must not be null");
        this.support = new ExporterGuardSupport(
                TelemetryGuardMetrics.Signal.SPAN,
                metrics,
                exportTimeout,
                maximumConcurrentExports);
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (spans == null) {
            return support.failedExport();
        }
        List<SpanData> accepted = new ArrayList<>(spans.size());
        for (SpanData span : spans) {
            if (guard.inspectSpan(span).isEmpty()) {
                accepted.add(span);
            }
        }
        if (accepted.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        List<SpanData> immutable = List.copyOf(accepted);
        return support.export(immutable.size(), () -> delegate.export(immutable));
    }

    @Override
    public CompletableResultCode flush() {
        return support.flush(delegate::flush);
    }

    @Override
    public CompletableResultCode shutdown() {
        return support.shutdown(delegate::flush, delegate::shutdown);
    }

    static final class ExporterGuardSupport {
        private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(5);
        private final TelemetryGuardMetrics.Signal signal;
        private final TelemetryGuardMetrics metrics;
        private final Duration timeout;
        private final ThreadPoolExecutor worker;
        private final ScheduledExecutorService timer;
        private final Semaphore inFlight;
        private final AtomicBoolean shutdown = new AtomicBoolean();

        ExporterGuardSupport(
            TelemetryGuardMetrics.Signal signal,
            TelemetryGuardMetrics metrics,
            Duration timeout,
            int maximumConcurrentExports) {
        this.signal = Objects.requireNonNull(signal, "telemetry signal must not be null");
        this.metrics = Objects.requireNonNull(metrics, "telemetry metrics must not be null");
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("invalid telemetry export timeout");
        }
        if (maximumConcurrentExports < 1 || maximumConcurrentExports > 32) {
            throw new IllegalArgumentException("invalid telemetry export concurrency");
        }
        this.timeout = timeout;
        this.inFlight = new Semaphore(maximumConcurrentExports);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory workerFactory = task -> {
            Thread thread = new Thread(
                    task,
                    "accord-telemetry-export-"
                            + signal.wireName()
                            + "-"
                            + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.worker = new ThreadPoolExecutor(
                maximumConcurrentExports,
                maximumConcurrentExports,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                workerFactory,
                new ThreadPoolExecutor.AbortPolicy());
        this.timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "accord-telemetry-timeout-" + signal.wireName());
            thread.setDaemon(true);
            return thread;
        });
    }

        CompletableResultCode export(int recordCount, Supplier<CompletableResultCode> operation) {
        if (recordCount < 1) {
            return CompletableResultCode.ofSuccess();
        }
        if (shutdown.get()) {
            metrics.recordDropped(signal, TelemetryDropReason.EXPORT_QUEUE_FULL, recordCount);
            return CompletableResultCode.ofSuccess();
        }
        if (!inFlight.tryAcquire()) {
            metrics.recordDropped(signal, TelemetryDropReason.EXPORT_QUEUE_FULL, recordCount);
            return CompletableResultCode.ofSuccess();
        }
        CompletableResultCode result = new CompletableResultCode();
        result.whenComplete(inFlight::release);
        AtomicBoolean completed = new AtomicBoolean();
        ScheduledFuture<?> timeoutFuture;
        try {
            timeoutFuture = timer.schedule(
                    () -> {
                        if (completed.compareAndSet(false, true)) {
                            metrics.recordDropped(
                                    signal, TelemetryDropReason.EXPORT_TIMEOUT, recordCount);
                            result.fail();
                        }
                    },
                    timeout.toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) {
            metrics.recordDropped(signal, TelemetryDropReason.EXPORT_QUEUE_FULL, recordCount);
            result.succeed();
            return result;
        }
        try {
            worker.execute(() -> invoke(operation, result, completed, timeoutFuture));
        } catch (RejectedExecutionException ignored) {
            timeoutFuture.cancel(false);
            if (completed.compareAndSet(false, true)) {
                metrics.recordDropped(signal, TelemetryDropReason.EXPORT_QUEUE_FULL, recordCount);
                result.succeed();
            }
        }
        return result;
    }

        CompletableResultCode failedExport() {
        metrics.recordExportFailure(signal);
        return CompletableResultCode.ofFailure();
    }

        CompletableResultCode flush(Supplier<CompletableResultCode> operation) {
        if (shutdown.get()) {
            return CompletableResultCode.ofSuccess();
        }
        return control(operation, timeout);
    }

        CompletableResultCode shutdown(
            Supplier<CompletableResultCode> flushOperation,
            Supplier<CompletableResultCode> shutdownOperation) {
        if (!shutdown.compareAndSet(false, true)) {
            return CompletableResultCode.ofSuccess();
        }
        CompletableResultCode result = new CompletableResultCode();
        long deadline = System.nanoTime() + MAXIMUM_TIMEOUT.toNanos();
        AtomicBoolean finished = new AtomicBoolean();
        ScheduledFuture<?> finalTimeout = timer.schedule(
                () -> {
                    if (finished.compareAndSet(false, true)) {
                        metrics.recordDropped(signal, TelemetryDropReason.EXPORT_TIMEOUT);
                        result.fail();
                        stopExecutors();
                    }
                },
                MAXIMUM_TIMEOUT.toNanos(),
                TimeUnit.NANOSECONDS);
        CompletableResultCode flushResult = control(
                flushOperation, Duration.ofNanos(MAXIMUM_TIMEOUT.toNanos() / 2L));
        flushResult.whenComplete(() -> {
            CompletableResultCode shutdownResult = control(shutdownOperation, remaining(deadline));
            shutdownResult.whenComplete(() -> {
                if (finished.compareAndSet(false, true)) {
                    finalTimeout.cancel(false);
                    if (flushResult.isSuccess() && shutdownResult.isSuccess()) {
                        result.succeed();
                    } else {
                        result.fail();
                    }
                    stopExecutors();
                }
            });
        });
        return result;
    }

        private CompletableResultCode control(
            Supplier<CompletableResultCode> operation, Duration controlTimeout) {
        CompletableResultCode result = new CompletableResultCode();
        AtomicBoolean completed = new AtomicBoolean();
        long timeoutNanos = Math.max(1L, controlTimeout.toNanos());
        ScheduledFuture<?> timeoutFuture;
        try {
            timeoutFuture = timer.schedule(
                    () -> {
                        if (completed.compareAndSet(false, true)) {
                            metrics.recordDropped(signal, TelemetryDropReason.EXPORT_TIMEOUT);
                            result.fail();
                        }
                    },
                    timeoutNanos,
                    TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) {
            metrics.recordExportFailure(signal);
            return CompletableResultCode.ofFailure();
        }
        try {
            worker.execute(() -> invoke(operation, result, completed, timeoutFuture));
        } catch (RejectedExecutionException ignored) {
            timeoutFuture.cancel(false);
            if (completed.compareAndSet(false, true)) {
                metrics.recordExportFailure(signal);
                result.fail();
            }
        }
        return result;
    }

        private void invoke(
            Supplier<CompletableResultCode> operation,
            CompletableResultCode result,
            AtomicBoolean completed,
            ScheduledFuture<?> timeoutFuture) {
        try {
            CompletableResultCode delegateResult = operation.get();
            if (delegateResult == null) {
                completeFailure(result, completed, timeoutFuture);
                return;
            }
            delegateResult.whenComplete(() -> {
                if (completed.compareAndSet(false, true)) {
                    timeoutFuture.cancel(false);
                    if (delegateResult.isSuccess()) {
                        result.succeed();
                    } else {
                        metrics.recordExportFailure(signal);
                        result.fail();
                    }
                }
            });
        } catch (RuntimeException ignored) {
            completeFailure(result, completed, timeoutFuture);
        }
    }

        private void completeFailure(
            CompletableResultCode result,
            AtomicBoolean completed,
            ScheduledFuture<?> timeoutFuture) {
        if (completed.compareAndSet(false, true)) {
            timeoutFuture.cancel(false);
            metrics.recordExportFailure(signal);
            result.fail();
        }
    }

        private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(1L, deadline - System.nanoTime()));
    }

        private void stopExecutors() {
        worker.shutdownNow();
        timer.shutdownNow();
        }
    }
}
