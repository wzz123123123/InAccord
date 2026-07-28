package com.inforvans.accord.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccordWorkflowTelemetryTest {
    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
    private final Resource resource = Resource.create(Attributes.of(
            TelemetryAttributeKey.SERVICE_NAME.stringKey(), "accord-control-worker"));
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build();
    private final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(metrics)
            .build();
    private final SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logs))
            .build();
    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .setLoggerProvider(loggerProvider)
            .build();

    @AfterEach
    void closeTelemetry() {
        openTelemetry.close();
    }

    @Test
    void emitsFixedProviderReconciliationCompletionThroughTheGuard() {
        new AccordWorkflowTelemetry(openTelemetry).recordProviderReconciliationSuccess();

        Attributes workflowAttributes = new TelemetryAttributes.Workflow(
                TelemetryIdentifiers.none(),
                TelemetryOperation.PROVIDER_RECONCILIATION,
                TelemetryProvider.GITLAB,
                TelemetryResultCode.SUCCESS)
                .toOtelAttributes();
        TelemetryRecordGuard guard = TelemetryRecordGuard.create(
                new TelemetryGuardMetrics(), List.of());
        assertThat(spans.getFinishedSpanItems()).singleElement().satisfies(span -> {
            assertThat(span.getInstrumentationScopeInfo().getName())
                    .isEqualTo("com.inforvans.accord.foundation");
            assertThat(span.getName()).isEqualTo("provider_reconciliation");
            assertThat(span.getAttributes().asMap()).isEqualTo(workflowAttributes.asMap());
            assertThat(guard.inspectSpan(span)).isEmpty();
        });

        List<MetricData> workflowMetrics = metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals("accord.workflow.executions"))
                .toList();
        assertThat(workflowMetrics).singleElement().satisfies(metric -> {
            assertThat(metric.getInstrumentationScopeInfo().getName())
                    .isEqualTo("com.inforvans.accord.foundation");
            assertThat(metric.getDescription())
                    .isEqualTo("Completed durable workflow operations.");
            assertThat(metric.getUnit()).isEqualTo("{operation}");
            assertThat(metric.getLongSumData().getPoints()).singleElement().satisfies(point -> {
                assertThat(point.getValue()).isEqualTo(1L);
                assertThat(point.getAttributes().asMap()).isEqualTo(
                        new TelemetryAttributes.Metric(
                                TelemetryOperation.PROVIDER_RECONCILIATION,
                                TelemetryProvider.GITLAB,
                                TelemetryResultCode.SUCCESS)
                                .toOtelAttributes()
                                .asMap());
            });
            assertThat(guard.inspectMetric(metric)).isEmpty();
        });

        assertThat(logs.getFinishedLogRecordItems()).singleElement().satisfies(log -> {
            assertThat(log.getInstrumentationScopeInfo().getName())
                    .isEqualTo("com.inforvans.accord.foundation");
            assertThat(log.getBodyValue().getValue())
                    .isEqualTo("foundation recovery completed");
            assertThat(log.getAttributes().asMap()).isEqualTo(workflowAttributes.asMap());
            assertThat(guard.inspectLog(log)).isEmpty();
        });
    }
}
