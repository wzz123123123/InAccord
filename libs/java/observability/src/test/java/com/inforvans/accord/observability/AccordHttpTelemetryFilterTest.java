package com.inforvans.accord.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class AccordHttpTelemetryFilterTest {
    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final Resource resource = Resource.create(Attributes.of(
            TelemetryAttributeKey.SERVICE_NAME.stringKey(), "accord-control-api"));
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build();
    private final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(metrics)
            .build();
    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build();

    @AfterEach
    void closeTelemetry() {
        openTelemetry.close();
    }

    @Test
    void recordsRegisteredControlRouteWithoutRawUriCardinality() throws Exception {
        AccordHttpTelemetryFilter filter = new AccordHttpTelemetryFilter(
                openTelemetry, AccordOpenTelemetryConfiguration.ServiceName.CONTROL_API);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/v1/contract-validations/018f928e-4e89-71a2-999e-abb50daf6237");
        request.setQueryString("token=must-not-escape");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                    "/v1/contract-validations/{validationId}");
            ((MockHttpServletResponse) servletResponse).setStatus(202);
        };

        filter.doFilter(request, response, chain);

        assertThat(spans.getFinishedSpanItems()).singleElement().satisfies(span -> {
            assertThat(span.getName())
                    .isEqualTo("POST /v1/contract-validations/{validationId}")
                    .doesNotContain("018f928e", "token");
            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(span.getInstrumentationScopeInfo().getName())
                    .isEqualTo("com.inforvans.accord.http");
            assertThat(span.getAttributes().asMap()).isEqualTo(httpAttributes(
                    TelemetryOperation.HTTP_REQUEST,
                    TelemetryProvider.NOT_APPLICABLE,
                    TelemetryResultCode.ACCEPTED,
                    "/v1/contract-validations/{validationId}",
                    202).asMap());
            assertThat(TelemetryRecordGuard.create(
                    new TelemetryGuardMetrics(), List.of()).inspectSpan(span)).isEmpty();
        });
        assertRequestMetric(
                TelemetryOperation.HTTP_REQUEST,
                TelemetryProvider.NOT_APPLICABLE,
                TelemetryResultCode.ACCEPTED);
    }

    @Test
    void mapsWebhookProviderAndIgnoresUnregisteredRoutes() throws Exception {
        AccordHttpTelemetryFilter filter = new AccordHttpTelemetryFilter(
                openTelemetry, AccordOpenTelemetryConfiguration.ServiceName.WEBHOOK_EDGE);
        MockHttpServletRequest webhook = new MockHttpServletRequest(
                "POST", "/webhooks/gitlab/raw-binding-id");
        MockHttpServletResponse rejected = new MockHttpServletResponse();

        filter.doFilter(webhook, rejected, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                    "/webhooks/gitlab/{bindingId}");
            rejected.setStatus(401);
        });
        filter.doFilter(
                new MockHttpServletRequest("GET", "/not-registered/secret"),
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> servletRequest.setAttribute(
                        HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        "/not-registered/{secret}"));

        assertThat(spans.getFinishedSpanItems()).singleElement()
                .extracting(span -> span.getAttributes().asMap())
                .isEqualTo(httpAttributes(
                        TelemetryOperation.PROVIDER_WEBHOOK_RECEIVE,
                        TelemetryProvider.GITLAB,
                        TelemetryResultCode.REJECTED,
                        "/webhooks/gitlab/{bindingId}",
                        401).asMap());
        assertRequestMetric(
                TelemetryOperation.PROVIDER_WEBHOOK_RECEIVE,
                TelemetryProvider.GITLAB,
                TelemetryResultCode.REJECTED);
    }

    private void assertRequestMetric(
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode) {
        List<MetricData> requestMetrics = metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals("accord.http.requests"))
                .toList();
        assertThat(requestMetrics).singleElement().satisfies(metric -> {
            assertThat(metric.getInstrumentationScopeInfo().getName())
                    .isEqualTo("com.inforvans.accord.http");
            assertThat(metric.getDescription())
                    .isEqualTo("Completed bounded-route HTTP operations.");
            assertThat(metric.getUnit()).isEqualTo("{request}");
            assertThat(metric.getLongSumData().getPoints()).singleElement().satisfies(point -> {
                assertThat(point.getValue()).isEqualTo(1L);
                assertThat(point.getAttributes().asMap()).isEqualTo(new TelemetryAttributes.Metric(
                        operation, provider, resultCode).toOtelAttributes().asMap());
            });
            assertThat(TelemetryRecordGuard.create(
                    new TelemetryGuardMetrics(), List.of()).inspectMetric(metric)).isEmpty();
        });
    }

    private static Attributes httpAttributes(
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode,
            String route,
            int status) {
        return new TelemetryAttributes.Http(
                TelemetryIdentifiers.none(),
                operation,
                provider,
                resultCode,
                TelemetryAttributes.HttpMethod.POST,
                new TelemetryAttributes.RouteTemplate(route),
                new TelemetryAttributes.HttpStatus(status))
                .toOtelAttributes();
    }
}
