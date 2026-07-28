package com.inforvans.accord.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.Data;
import io.opentelemetry.sdk.metrics.data.DoubleExemplarData;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoubleExemplarData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.testing.logs.TestLogRecordData;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TelemetryExporterGuardTest {
    @Test
    void spanExporterDelegatesOnlyCompleteValidRecords() {
        TelemetryGuardMetrics metrics = new TelemetryGuardMetrics();
        TelemetryRecordGuard guard = TelemetryRecordGuard.create(metrics, List.of());
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        GuardedSpanExporter exporter = new GuardedSpanExporter(
                delegate, guard, metrics, Duration.ofSeconds(1), 1);
        SpanData valid = span(validAttributes());
        SpanData invalid = span(Attributes.builder()
                .putAll(validAttributes())
                .put(AttributeKey.stringKey("http.request.header.authorization"), "Bearer secret")
                .build());

        CompletableResultCode result = exporter.export(List.of(valid, invalid));
        result.join(1, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isTrue();
        assertThat(delegate.getFinishedSpanItems()).containsExactly(valid);
        assertThat(metrics.totalDropped()).isOne();
        exporter.shutdown();
    }

    @Test
    void delegateFailuresNeverThrowIntoBusinessCode() {
        TelemetryGuardMetrics metrics = new TelemetryGuardMetrics();
        TelemetryRecordGuard guard = TelemetryRecordGuard.create(metrics, List.of());
        SpanExporter throwing = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                throw new IllegalStateException("secret delegate detail");
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        GuardedSpanExporter exporter = new GuardedSpanExporter(
                throwing, guard, metrics, Duration.ofMillis(25), 1);

        CompletableResultCode result = exporter.export(List.of(span(validAttributes())));
        result.join(1, TimeUnit.SECONDS);

        assertThat(result.isDone()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(metrics.exportFailureCount(TelemetryGuardMetrics.Signal.SPAN)).isOne();
        exporter.shutdown();
    }

    @Test
    void metricExporterDropsInvalidPointsAndExemplarsBeforeDelegation() {
        TelemetryGuardMetrics metrics = new TelemetryGuardMetrics();
        TelemetryRecordGuard guard = TelemetryRecordGuard.create(metrics, List.of());
        InMemoryMetricExporter delegate = InMemoryMetricExporter.create();
        GuardedMetricExporter exporter = new GuardedMetricExporter(
                delegate, guard, metrics, Duration.ofSeconds(1), 1);
        DoublePointData valid = ImmutableDoublePointData.create(
                1L, 2L, validMetricAttributes(), 1.0D);
        DoublePointData invalidPoint = ImmutableDoublePointData.create(
                1L, 2L, validMetricAttributes(), Double.NaN);
        DoubleExemplarData invalidExemplar = ImmutableDoubleExemplarData.create(
                Attributes.empty(), 2L, SpanContext.getInvalid(), Double.POSITIVE_INFINITY);
        DoublePointData pointWithInvalidExemplar = ImmutableDoublePointData.create(
                1L, 2L, validMetricAttributes(), 2.0D, List.of(invalidExemplar));
        MetricData metric = httpRequestMetric(List.of(
                valid, invalidPoint, pointWithInvalidExemplar));

        CompletableResultCode result = exporter.export(List.of(metric));
        result.join(1, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isTrue();
        assertThat(delegate.getFinishedMetricItems()).hasSize(1);
        assertThat(delegate.getFinishedMetricItems().getFirst()
                        .getDoubleGaugeData().getPoints())
                .containsExactly(valid);
        assertThat(metrics.droppedCount(
                TelemetryGuardMetrics.Signal.METRIC,
                TelemetryDropReason.INVALID_VALUE)).isEqualTo(2L);
        exporter.shutdown();
    }

    @Test
    void metricExporterFailsClosedForNullPointsAndAlwaysRequestsImmutableData() {
        TelemetryGuardMetrics metrics = new TelemetryGuardMetrics();
        TelemetryRecordGuard guard = TelemetryRecordGuard.create(metrics, List.of());
        InMemoryMetricExporter recording = InMemoryMetricExporter.create();
        MetricExporter reusableDelegate = new MetricExporter() {
            @Override
            public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
                return AggregationTemporality.CUMULATIVE;
            }

            @Override
            public MemoryMode getMemoryMode() {
                return MemoryMode.REUSABLE_DATA;
            }

            @Override
            public CompletableResultCode export(Collection<MetricData> metricsToExport) {
                return recording.export(metricsToExport);
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        GuardedMetricExporter exporter = new GuardedMetricExporter(
                reusableDelegate, guard, metrics, Duration.ofSeconds(1), 1);
        MetricData malformed = new MetricData() {
            @Override public Resource getResource() { return validResource(); }
            @Override public InstrumentationScopeInfo getInstrumentationScopeInfo() {
                return validScope();
            }
            @Override public String getName() { return "accord.http.requests"; }
            @Override public String getDescription() {
                return "Completed bounded-route HTTP operations.";
            }
            @Override public String getUnit() { return "{request}"; }
            @Override public MetricDataType getType() { return MetricDataType.DOUBLE_GAUGE; }
            @Override public Data<?> getData() { return () -> null; }
        };

        CompletableResultCode result = exporter.export(List.of(malformed));

        assertThat(result.isSuccess()).isTrue();
        assertThat(recording.getFinishedMetricItems()).isEmpty();
        assertThat(metrics.droppedCount(
                TelemetryGuardMetrics.Signal.METRIC,
                TelemetryDropReason.INVALID_VALUE)).isOne();
        assertThat(exporter.getMemoryMode()).isEqualTo(MemoryMode.IMMUTABLE_DATA);
        exporter.shutdown();
    }

    @Test
    void hangingDelegateIsTimedOutAndSaturatedExportIsDropped() {
        TelemetryGuardMetrics metrics = new TelemetryGuardMetrics();
        TelemetryRecordGuard guard = TelemetryRecordGuard.create(metrics, List.of());
        SpanExporter hanging = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return new CompletableResultCode();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        GuardedSpanExporter exporter = new GuardedSpanExporter(
                hanging, guard, metrics, Duration.ofMillis(25), 1);

        CompletableResultCode hangingResult = exporter.export(List.of(span(validAttributes())));
        CompletableResultCode saturatedResult = exporter.export(List.of(span(validAttributes())));
        hangingResult.join(1, TimeUnit.SECONDS);

        assertThat(hangingResult.isSuccess()).isFalse();
        assertThat(saturatedResult.isSuccess()).isTrue();
        assertThat(metrics.droppedCount(
                TelemetryGuardMetrics.Signal.SPAN,
                TelemetryDropReason.EXPORT_QUEUE_FULL)).isOne();
        assertThat(metrics.droppedCount(
                TelemetryGuardMetrics.Signal.SPAN,
                TelemetryDropReason.EXPORT_TIMEOUT)).isOne();
        exporter.shutdown();
    }

    @Test
    void logBodyViolationDropsWholeLogBeforeDelegate() {
        TelemetryGuardMetrics metrics = new TelemetryGuardMetrics();
        TelemetryRecordGuard guard = TelemetryRecordGuard.create(metrics, List.of());
        InMemoryLogRecordExporter delegate = InMemoryLogRecordExporter.create();
        GuardedLogRecordExporter exporter = new GuardedLogRecordExporter(
                delegate, guard, metrics, Duration.ofSeconds(1), 1);
        LogRecordData valid = log(Value.of("operation completed"));
        LogRecordData invalid = log(Value.of("Bearer secret-value"));

        CompletableResultCode result = exporter.export(List.of(valid, invalid));
        result.join(1, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isTrue();
        assertThat(delegate.getFinishedLogRecordItems()).containsExactly(valid);
        assertThat(metrics.droppedCount(
                TelemetryGuardMetrics.Signal.LOG,
                TelemetryDropReason.SENSITIVE_VALUE)).isOne();
        exporter.shutdown();
    }

    private static Attributes validAttributes() {
        return new TelemetryAttributes.Workflow(
                TelemetryIdentifiers.none(),
                TelemetryOperation.CONTRACT_VALIDATION,
                TelemetryProvider.NOT_APPLICABLE,
                TelemetryResultCode.SUCCESS)
                .toOtelAttributes();
    }

    private static Attributes validMetricAttributes() {
        return new TelemetryAttributes.Metric(
                TelemetryOperation.HTTP_REQUEST,
                TelemetryProvider.NOT_APPLICABLE,
                TelemetryResultCode.SUCCESS)
                .toOtelAttributes();
    }

    private static MetricData httpRequestMetric(Collection<DoublePointData> points) {
        return ImmutableMetricData.createDoubleGauge(
                validResource(),
                validScope(),
                "accord.http.requests",
                "Completed bounded-route HTTP operations.",
                "{request}",
                ImmutableGaugeData.create(points));
    }

    private static Resource validResource() {
        return Resource.create(Attributes.of(
                TelemetryAttributeKey.SERVICE_NAME.stringKey(), "accord-control-api"));
    }

    private static InstrumentationScopeInfo validScope() {
        return InstrumentationScopeInfo.create("com.inforvans.accord.test");
    }

    private static TestSpanData span(Attributes attributes) {
        return TestSpanData.builder()
                .setName("contract_validation")
                .setKind(SpanKind.INTERNAL)
                .setStartEpochNanos(1L)
                .setEndEpochNanos(2L)
                .setHasEnded(true)
                .setStatus(StatusData.ok())
                .setSpanContext(io.opentelemetry.api.trace.SpanContext.create(
                        "0123456789abcdef0123456789abcdef",
                        "0123456789abcdef",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault()))
                .setParentSpanContext(io.opentelemetry.api.trace.SpanContext.getInvalid())
                .setAttributes(attributes)
                .setTotalAttributeCount(attributes.size())
                .setInstrumentationScopeInfo(
                        validScope())
                .setResource(validResource())
                .build();
    }

    private static TestLogRecordData log(Value<?> body) {
        return TestLogRecordData.builder()
                .setBodyValue(body)
                .setAttributes(validAttributes())
                .setTotalAttributeCount(validAttributes().size())
                .setInstrumentationScopeInfo(
                        validScope())
                .setResource(validResource())
                .build();
    }
}
