package com.inforvans.accord.observability;

import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/** In-process fixed-cardinality guard accounting, independent of the guarded export pipeline. */
public final class TelemetryGuardMetrics {
    public static final String DROPPED_METRIC_NAME = "accord.telemetry.records.dropped";
    public static final String EXPORT_FAILURE_METRIC_NAME = "accord.telemetry.export.failures";

    private final EnumMap<Signal, EnumMap<TelemetryDropReason, LongAdder>> dropped =
            new EnumMap<>(Signal.class);
    private final EnumMap<Signal, LongAdder> exportFailures = new EnumMap<>(Signal.class);

    public TelemetryGuardMetrics() {
        for (Signal signal : Signal.values()) {
            EnumMap<TelemetryDropReason, LongAdder> reasons =
                    new EnumMap<>(TelemetryDropReason.class);
            for (TelemetryDropReason reason : TelemetryDropReason.values()) {
                reasons.put(reason, new LongAdder());
            }
            dropped.put(signal, reasons);
            exportFailures.put(signal, new LongAdder());
        }
    }

    void recordDropped(Signal signal, TelemetryDropReason reason) {
        dropped.get(signal).get(reason).increment();
    }

    void recordDropped(Signal signal, TelemetryDropReason reason, int count) {
        if (count > 0) {
            dropped.get(signal).get(reason).add(count);
        }
    }

    void recordExportFailure(Signal signal) {
        exportFailures.get(signal).increment();
    }

    public long droppedCount(Signal signal, TelemetryDropReason reason) {
        return dropped.get(signal).get(reason).sum();
    }

    public long exportFailureCount(Signal signal) {
        return exportFailures.get(signal).sum();
    }

    public long totalDropped() {
        long total = 0L;
        for (Signal signal : Signal.values()) {
            for (TelemetryDropReason reason : TelemetryDropReason.values()) {
                total += droppedCount(signal, reason);
            }
        }
        return total;
    }

    public enum Signal {
        SPAN,
        METRIC,
        LOG;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
