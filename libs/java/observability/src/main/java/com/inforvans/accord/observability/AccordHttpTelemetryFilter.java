package com.inforvans.accord.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/** Emits only registered server routes through Accord's closed telemetry schema. */
final class AccordHttpTelemetryFilter extends OncePerRequestFilter {
    private static final String INSTRUMENTATION_SCOPE = "com.inforvans.accord.http";
    private static final String REQUEST_METRIC = "accord.http.requests";

    private final Tracer tracer;
    private final LongCounter requests;
    private final AccordOpenTelemetryConfiguration.ServiceName serviceName;

    AccordHttpTelemetryFilter(
            OpenTelemetry openTelemetry,
            AccordOpenTelemetryConfiguration.ServiceName serviceName) {
        Objects.requireNonNull(openTelemetry, "OpenTelemetry must not be null");
        this.serviceName = Objects.requireNonNull(serviceName, "service name must not be null");
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        this.requests = openTelemetry.getMeter(INSTRUMENTATION_SCOPE)
                .counterBuilder(REQUEST_METRIC)
                .setDescription("Completed bounded-route HTTP operations.")
                .setUnit("{request}")
                .build();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RouteSpec route = RouteSpec.find(serviceName, request.getMethod(), request.getRequestURI());
        if (route == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Span span = tracer.spanBuilder(route.spanName())
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        boolean completed = false;
        Scope scope = span.makeCurrent();
        try {
            filterChain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                scope.close();
            } catch (RuntimeException ignored) {
                // Telemetry context cleanup cannot alter the HTTP operation.
            }
            emitCompletion(route, completed ? response.getStatus() : 500, span);
        }
    }

    private void emitCompletion(RouteSpec route, int responseStatus, Span span) {
        int status = responseStatus >= 100 && responseStatus <= 599 ? responseStatus : 500;
        TelemetryResultCode resultCode = resultCode(status);
        try {
            TelemetryAttributes.Http spanAttributes = new TelemetryAttributes.Http(
                    TelemetryIdentifiers.none(),
                    route.operation,
                    route.provider,
                    resultCode,
                    route.method,
                    route.route,
                    new TelemetryAttributes.HttpStatus(status));
            span.setAllAttributes(spanAttributes.toOtelAttributes());
            if (status >= 500) {
                span.setStatus(StatusCode.ERROR);
            }
            requests.add(1L, new TelemetryAttributes.Metric(
                    route.operation, route.provider, resultCode).toOtelAttributes());
        } catch (RuntimeException ignored) {
            // Telemetry failures never alter the HTTP operation.
        } finally {
            span.end();
        }
    }

    private static TelemetryResultCode resultCode(int status) {
        if (status >= 200 && status < 300) {
            return status == 202
                    ? TelemetryResultCode.ACCEPTED
                    : TelemetryResultCode.SUCCESS;
        }
        return switch (status) {
            case 404 -> TelemetryResultCode.NOT_FOUND;
            case 408, 504 -> TelemetryResultCode.TIMEOUT;
            case 409 -> TelemetryResultCode.CONFLICT;
            case 429 -> TelemetryResultCode.RETRY_REQUIRED;
            case 502, 503 -> TelemetryResultCode.UNAVAILABLE;
            default -> status >= 500
                    ? TelemetryResultCode.FAILURE
                    : TelemetryResultCode.REJECTED;
        };
    }

    private enum RouteSpec {
        CONTRACT_VALIDATION(
                AccordOpenTelemetryConfiguration.ServiceName.CONTROL_API,
                TelemetryAttributes.HttpMethod.POST,
                "/v1/contract-validations/{validationId}",
                TelemetryOperation.HTTP_REQUEST,
                TelemetryProvider.NOT_APPLICABLE),
        GITLAB_WEBHOOK(
                AccordOpenTelemetryConfiguration.ServiceName.WEBHOOK_EDGE,
                TelemetryAttributes.HttpMethod.POST,
                "/webhooks/gitlab/{bindingId}",
                TelemetryOperation.PROVIDER_WEBHOOK_RECEIVE,
                TelemetryProvider.GITLAB),
        CONTROL_HEALTH(
                AccordOpenTelemetryConfiguration.ServiceName.CONTROL_API,
                TelemetryAttributes.HttpMethod.GET,
                "/actuator/health",
                TelemetryOperation.HTTP_REQUEST,
                TelemetryProvider.NOT_APPLICABLE),
        CONTROL_PROMETHEUS(
                AccordOpenTelemetryConfiguration.ServiceName.CONTROL_API,
                TelemetryAttributes.HttpMethod.GET,
                "/actuator/prometheus",
                TelemetryOperation.HTTP_REQUEST,
                TelemetryProvider.NOT_APPLICABLE),
        EDGE_HEALTH(
                AccordOpenTelemetryConfiguration.ServiceName.WEBHOOK_EDGE,
                TelemetryAttributes.HttpMethod.GET,
                "/actuator/health",
                TelemetryOperation.HTTP_REQUEST,
                TelemetryProvider.NOT_APPLICABLE),
        EDGE_PROMETHEUS(
                AccordOpenTelemetryConfiguration.ServiceName.WEBHOOK_EDGE,
                TelemetryAttributes.HttpMethod.GET,
                "/actuator/prometheus",
                TelemetryOperation.HTTP_REQUEST,
                TelemetryProvider.NOT_APPLICABLE);

        private final AccordOpenTelemetryConfiguration.ServiceName serviceName;
        private final TelemetryAttributes.HttpMethod method;
        private final TelemetryAttributes.RouteTemplate route;
        private final TelemetryOperation operation;
        private final TelemetryProvider provider;

        RouteSpec(
                AccordOpenTelemetryConfiguration.ServiceName serviceName,
                TelemetryAttributes.HttpMethod method,
                String route,
                TelemetryOperation operation,
                TelemetryProvider provider) {
            this.serviceName = serviceName;
            this.method = method;
            this.route = new TelemetryAttributes.RouteTemplate(route);
            this.operation = operation;
            this.provider = provider;
        }

        private String spanName() {
            return method.wireName() + " " + route.value();
        }

        private boolean matches(
                AccordOpenTelemetryConfiguration.ServiceName candidateService,
                String candidateMethod,
                String requestUri) {
            if (serviceName != candidateService || !method.wireName().equals(candidateMethod)) {
                return false;
            }
            String template = route.value();
            int parameterStart = template.indexOf('{');
            if (parameterStart < 0) {
                return template.equals(requestUri);
            }
            int parameterEnd = template.indexOf('}', parameterStart);
            String prefix = template.substring(0, parameterStart);
            String suffix = template.substring(parameterEnd + 1);
            if (requestUri == null
                    || !requestUri.startsWith(prefix)
                    || !requestUri.endsWith(suffix)) {
                return false;
            }
            int valueEnd = requestUri.length() - suffix.length();
            String value = requestUri.substring(prefix.length(), valueEnd);
            return !value.isEmpty() && value.indexOf('/') < 0;
        }

        private static RouteSpec find(
                AccordOpenTelemetryConfiguration.ServiceName serviceName,
                String method,
                String requestUri) {
            for (RouteSpec route : values()) {
                if (route.matches(serviceName, method, requestUri)) {
                    return route;
                }
            }
            return null;
        }
    }
}
