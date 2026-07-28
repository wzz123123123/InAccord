package com.inforvans.accord.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Last-mile log exporter that rejects complete unsafe records before delegation. */
public final class GuardedLogRecordExporter implements LogRecordExporter {
    private final LogRecordExporter delegate;
    private final TelemetryRecordGuard guard;
    private final GuardedSpanExporter.ExporterGuardSupport support;

    public GuardedLogRecordExporter(
            LogRecordExporter delegate,
            TelemetryRecordGuard guard,
            TelemetryGuardMetrics metrics,
            Duration exportTimeout,
            int maximumConcurrentExports) {
        this.delegate = Objects.requireNonNull(delegate, "log exporter must not be null");
        this.guard = Objects.requireNonNull(guard, "telemetry guard must not be null");
        this.support = new GuardedSpanExporter.ExporterGuardSupport(
                TelemetryGuardMetrics.Signal.LOG,
                metrics,
                exportTimeout,
                maximumConcurrentExports);
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
        if (logs == null) {
            return support.failedExport();
        }
        List<LogRecordData> accepted = new ArrayList<>(logs.size());
        for (LogRecordData log : logs) {
            if (guard.inspectLog(log).isEmpty()) {
                accepted.add(log);
            }
        }
        if (accepted.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        List<LogRecordData> immutable = List.copyOf(accepted);
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
}
