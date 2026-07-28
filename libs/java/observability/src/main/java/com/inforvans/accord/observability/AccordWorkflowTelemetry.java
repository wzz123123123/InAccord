package com.inforvans.accord.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.Objects;

/** Closed producer for durable workflow completion evidence. */
public final class AccordWorkflowTelemetry {
    private static final String INSTRUMENTATION_SCOPE = "com.inforvans.accord.foundation";
    private static final String WORKFLOW_METRIC = "accord.workflow.executions";
    private static final String COMPLETION_LOG_BODY = "foundation recovery completed";
    private static final Attributes WORKFLOW_ATTRIBUTES = new TelemetryAttributes.Workflow(
            TelemetryIdentifiers.none(),
            TelemetryOperation.PROVIDER_RECONCILIATION,
            TelemetryProvider.GITLAB,
            TelemetryResultCode.SUCCESS)
            .toOtelAttributes();
    private static final Attributes METRIC_ATTRIBUTES = new TelemetryAttributes.Metric(
            TelemetryOperation.PROVIDER_RECONCILIATION,
            TelemetryProvider.GITLAB,
            TelemetryResultCode.SUCCESS)
            .toOtelAttributes();

    private final Tracer tracer;
    private final LongCounter workflowExecutions;
    private final Logger logger;

    AccordWorkflowTelemetry(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "OpenTelemetry must not be null");
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        this.workflowExecutions = openTelemetry.getMeter(INSTRUMENTATION_SCOPE)
                .counterBuilder(WORKFLOW_METRIC)
                .setDescription("Completed durable workflow operations.")
                .setUnit("{operation}")
                .build();
        this.logger = openTelemetry.getLogsBridge()
                .loggerBuilder(INSTRUMENTATION_SCOPE)
                .build();
    }

    public void recordProviderReconciliationSuccess() {
        emitSpan();
        emitMetric();
        emitLog();
    }

    private void emitSpan() {
        Span span = null;
        try {
            span = tracer.spanBuilder(TelemetryOperation.PROVIDER_RECONCILIATION.wireName())
                    .startSpan();
            span.setAllAttributes(WORKFLOW_ATTRIBUTES);
            span.setStatus(StatusCode.OK);
        } catch (RuntimeException ignored) {
            // Telemetry failures never alter durable workflow completion.
        } finally {
            if (span != null) {
                try {
                    span.end();
                } catch (RuntimeException ignored) {
                    // Telemetry failures never alter durable workflow completion.
                }
            }
        }
    }

    private void emitMetric() {
        try {
            workflowExecutions.add(1L, METRIC_ATTRIBUTES);
        } catch (RuntimeException ignored) {
            // Telemetry failures never alter durable workflow completion.
        }
    }

    private void emitLog() {
        try {
            logger.logRecordBuilder()
                    .setSeverity(Severity.INFO)
                    .setSeverityText("INFO")
                    .setBody(COMPLETION_LOG_BODY)
                    .setAllAttributes(WORKFLOW_ATTRIBUTES)
                    .emit();
        } catch (RuntimeException ignored) {
            // Telemetry failures never alter durable workflow completion.
        }
    }
}
