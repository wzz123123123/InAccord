package com.inforvans.accord.observability;

import static io.opentelemetry.sdk.common.export.MemoryMode.IMMUTABLE_DATA;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.Data;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.data.GaugeData;
import io.opentelemetry.sdk.metrics.data.HistogramData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.data.SumData;
import io.opentelemetry.sdk.metrics.data.SummaryData;
import io.opentelemetry.sdk.metrics.data.SummaryPointData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.resources.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Last-mile metric exporter that drops complete unsafe points without rebuilding their fields. */
public final class GuardedMetricExporter implements MetricExporter {
    private final MetricExporter delegate;
    private final TelemetryRecordGuard guard;
    private final TelemetryGuardMetrics metrics;
    private final GuardedSpanExporter.ExporterGuardSupport support;

    public GuardedMetricExporter(
            MetricExporter delegate,
            TelemetryRecordGuard guard,
            TelemetryGuardMetrics metrics,
            Duration exportTimeout,
            int maximumConcurrentExports) {
        this.delegate = Objects.requireNonNull(delegate, "metric exporter must not be null");
        this.guard = Objects.requireNonNull(guard, "telemetry guard must not be null");
        this.metrics = Objects.requireNonNull(metrics, "telemetry metrics must not be null");
        this.support = new GuardedSpanExporter.ExporterGuardSupport(
                TelemetryGuardMetrics.Signal.METRIC,
                metrics,
                exportTimeout,
                maximumConcurrentExports);
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metricsToExport) {
        if (metricsToExport == null) {
            return support.failedExport();
        }
        List<MetricData> acceptedMetrics = new ArrayList<>(metricsToExport.size());
        int acceptedPointCount = 0;
        for (MetricData metric : metricsToExport) {
            try {
                java.util.Optional<TelemetryDropReason> descriptorRejection =
                        guard.findMetricDescriptorRejection(metric);
                if (descriptorRejection.isPresent()) {
                    guard.recordMetricDrop(descriptorRejection.orElseThrow());
                    continue;
                }
                Set<PointData> acceptedPoints =
                        Collections.newSetFromMap(new IdentityHashMap<>());
                int totalPoints = 0;
                for (PointData point : List.copyOf(metric.getData().getPoints())) {
                    totalPoints++;
                    java.util.Optional<TelemetryDropReason> pointRejection =
                            guard.findMetricPointRejection(metric, point);
                    if (pointRejection.isPresent()) {
                        guard.recordMetricDrop(pointRejection.orElseThrow());
                    } else {
                        acceptedPoints.add(point);
                    }
                }
                if (acceptedPoints.isEmpty()) {
                    continue;
                }
                acceptedPointCount += acceptedPoints.size();
                acceptedMetrics.add(acceptedPoints.size() == totalPoints
                        ? metric
                        : new FilteredMetricData(metric, filterData(metric, acceptedPoints)));
            } catch (RuntimeException ignored) {
                guard.recordMetricDrop(TelemetryDropReason.INVALID_VALUE);
            }
        }
        if (acceptedMetrics.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        List<MetricData> immutable = List.copyOf(acceptedMetrics);
        return support.export(acceptedPointCount, () -> delegate.export(immutable));
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        try {
            AggregationTemporality temporality =
                    delegate.getAggregationTemporality(instrumentType);
            if (temporality != null) {
                return temporality;
            }
        } catch (RuntimeException ignored) {
        }
        metrics.recordExportFailure(TelemetryGuardMetrics.Signal.METRIC);
        return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
        try {
            Aggregation aggregation = delegate.getDefaultAggregation(instrumentType);
            if (aggregation != null) {
                return aggregation;
            }
        } catch (RuntimeException ignored) {
        }
        metrics.recordExportFailure(TelemetryGuardMetrics.Signal.METRIC);
        return Aggregation.defaultAggregation();
    }

    @Override
    public MemoryMode getMemoryMode() {
        return IMMUTABLE_DATA;
    }

    @Override
    public CompletableResultCode flush() {
        return support.flush(delegate::flush);
    }

    @Override
    public CompletableResultCode shutdown() {
        return support.shutdown(delegate::flush, delegate::shutdown);
    }

    private static Data<?> filterData(MetricData metric, Set<PointData> accepted) {
        return switch (metric.getType()) {
            case LONG_GAUGE -> new FilteredGaugeData<>(
                    retain(metric.getLongGaugeData().getPoints(), accepted));
            case DOUBLE_GAUGE -> new FilteredGaugeData<>(
                    retain(metric.getDoubleGaugeData().getPoints(), accepted));
            case LONG_SUM -> new FilteredSumData<>(
                    retain(metric.getLongSumData().getPoints(), accepted),
                    metric.getLongSumData().isMonotonic(),
                    metric.getLongSumData().getAggregationTemporality());
            case DOUBLE_SUM -> new FilteredSumData<>(
                    retain(metric.getDoubleSumData().getPoints(), accepted),
                    metric.getDoubleSumData().isMonotonic(),
                    metric.getDoubleSumData().getAggregationTemporality());
            case HISTOGRAM -> new FilteredHistogramData(
                    retain(metric.getHistogramData().getPoints(), accepted),
                    metric.getHistogramData().getAggregationTemporality());
            case EXPONENTIAL_HISTOGRAM -> new FilteredExponentialHistogramData(
                    retain(metric.getExponentialHistogramData().getPoints(), accepted),
                    metric.getExponentialHistogramData().getAggregationTemporality());
            case SUMMARY -> new FilteredSummaryData(
                    retain(metric.getSummaryData().getPoints(), accepted));
        };
    }

    private static <T extends PointData> List<T> retain(
            Collection<T> points, Set<PointData> accepted) {
        List<T> result = new ArrayList<>(accepted.size());
        for (T point : points) {
            if (accepted.contains(point)) {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    private record FilteredMetricData(MetricData delegate, Data<?> filteredData)
            implements MetricData {
        @Override
        public Resource getResource() {
            return delegate.getResource();
        }

        @Override
        public InstrumentationScopeInfo getInstrumentationScopeInfo() {
            return delegate.getInstrumentationScopeInfo();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public String getUnit() {
            return delegate.getUnit();
        }

        @Override
        public MetricDataType getType() {
            return delegate.getType();
        }

        @Override
        public Data<?> getData() {
            return filteredData;
        }
    }

    private record FilteredGaugeData<T extends PointData>(Collection<T> points)
            implements GaugeData<T> {
        @Override
        public Collection<T> getPoints() {
            return points;
        }
    }

    private record FilteredSumData<T extends PointData>(
            Collection<T> points,
            boolean monotonic,
            AggregationTemporality temporality) implements SumData<T> {
        @Override
        public Collection<T> getPoints() {
            return points;
        }

        @Override
        public boolean isMonotonic() {
            return monotonic;
        }

        @Override
        public AggregationTemporality getAggregationTemporality() {
            return temporality;
        }
    }

    private record FilteredHistogramData(
            Collection<HistogramPointData> points,
            AggregationTemporality temporality) implements HistogramData {
        @Override
        public Collection<HistogramPointData> getPoints() {
            return points;
        }

        @Override
        public AggregationTemporality getAggregationTemporality() {
            return temporality;
        }
    }

    private record FilteredExponentialHistogramData(
            Collection<ExponentialHistogramPointData> points,
            AggregationTemporality temporality) implements ExponentialHistogramData {
        @Override
        public Collection<ExponentialHistogramPointData> getPoints() {
            return points;
        }

        @Override
        public AggregationTemporality getAggregationTemporality() {
            return temporality;
        }
    }

    private record FilteredSummaryData(Collection<SummaryPointData> points)
            implements SummaryData {
        @Override
        public Collection<SummaryPointData> getPoints() {
            return points;
        }
    }
}
