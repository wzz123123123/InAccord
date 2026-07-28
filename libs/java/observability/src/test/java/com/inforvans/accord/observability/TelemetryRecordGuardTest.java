package com.inforvans.accord.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelemetryRecordGuardTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";
    private final TelemetryGuardMetrics metrics = new TelemetryGuardMetrics();
    private final TelemetryRecordGuard guard = TelemetryRecordGuard.create(metrics, List.of("needle"));

    @Test
    void rejectsWholeSpanForOneUnknownAttributeAndCountsOnce() {
        Attributes invalid = Attributes.builder()
                .putAll(validOperationAttributes())
                .put(AttributeKey.stringKey("custom.dynamic"), "value")
                .build();

        TelemetryDropReason reason = guard.inspectSpan(span(invalid)).orElseThrow();

        assertThat(reason).isEqualTo(TelemetryDropReason.UNKNOWN_KEY);
        assertThat(metrics.droppedCount(
                TelemetryGuardMetrics.Signal.SPAN,
                TelemetryDropReason.UNKNOWN_KEY)).isOne();
        assertThat(metrics.totalDropped()).isOne();
    }

    @Test
    void rejectsEverySensitiveFamilyWithoutEchoingExaminedText() {
        List<String> candidates = List.of(
                "Bearer abcdef",
                "Basic YTpi",
                "glpat-secret",
                "ghp_secret",
                "whsec_secret",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
                "-----BEGIN PRIVATE KEY-----",
                "https://example.invalid/path?token=secret",
                "line\u0000break",
                "contains-needle-value");

        for (String candidate : candidates) {
            Attributes invalid = Attributes.builder()
                    .putAll(validOperationAttributes())
                    .put(TelemetryAttributeKey.EVENT_TYPE.stringKey(), candidate)
                    .build();
            assertThat(guard.inspectSpan(span(invalid))).isPresent();
        }

        assertThat(metrics.totalDropped()).isEqualTo(candidates.size());
    }

    @Test
    void inspectsResourceEventLinkAndStatusDescription() {
        TestSpanData invalid = baseSpanBuilder(validOperationAttributes())
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("authorization"), "secret")))
                .setEvents(List.of(EventData.create(
                        1L,
                        "contract_validation_completed",
                        Attributes.of(AttributeKey.stringKey("unknown.event.key"), "value"))))
                .setTotalRecordedEvents(1)
                .setStatus(StatusData.create(
                        io.opentelemetry.api.trace.StatusCode.ERROR,
                        "database exception detail"))
                .build();

        assertThat(guard.inspectSpan(invalid))
                .contains(TelemetryDropReason.SENSITIVE_KEY);
    }

    @Test
    void rejectsSensitiveTraceStateOnSpansAndLinks() {
        SpanContext sensitiveContext = contextWithTraceState("contains-needle-value");
        TestSpanData spanWithTraceState = baseSpanBuilder(validOperationAttributes())
                .setSpanContext(sensitiveContext)
                .build();
        TestSpanData spanWithLinkTraceState = baseSpanBuilder(validOperationAttributes())
                .setLinks(List.of(LinkData.create(sensitiveContext)))
                .setTotalRecordedLinks(1)
                .build();

        assertThat(guard.inspectSpan(spanWithTraceState))
                .contains(TelemetryDropReason.SENSITIVE_VALUE);
        assertThat(guard.inspectSpan(spanWithLinkTraceState))
                .contains(TelemetryDropReason.SENSITIVE_VALUE);
    }

    private static Attributes validOperationAttributes() {
        return new TelemetryAttributes.Workflow(
                TelemetryIdentifiers.none(),
                TelemetryOperation.CONTRACT_VALIDATION,
                TelemetryProvider.NOT_APPLICABLE,
                TelemetryResultCode.SUCCESS)
                .toOtelAttributes();
    }

    private static TestSpanData span(Attributes attributes) {
        return baseSpanBuilder(attributes).build();
    }

    private static TestSpanData.Builder baseSpanBuilder(Attributes attributes) {
        return TestSpanData.builder()
                .setName("contract_validation")
                .setKind(SpanKind.INTERNAL)
                .setStartEpochNanos(1L)
                .setEndEpochNanos(2L)
                .setHasEnded(true)
                .setStatus(StatusData.ok())
                .setSpanContext(contextWithTraceState(null))
                .setParentSpanContext(SpanContext.getInvalid())
                .setAttributes(attributes)
                .setTotalAttributeCount(attributes.size())
                .setInstrumentationScopeInfo(
                        InstrumentationScopeInfo.create("com.inforvans.accord.test"))
                .setResource(Resource.create(Attributes.of(
                        TelemetryAttributeKey.SERVICE_NAME.stringKey(), "accord-control-api")));
    }

    private static SpanContext contextWithTraceState(String value) {
        TraceState traceState = value == null
                ? TraceState.getDefault()
                : TraceState.builder().put("vendor", value).build();
        return SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), traceState);
    }
}
