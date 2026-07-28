package com.inforvans.accord.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.common.ValueType;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.metrics.data.Data;
import io.opentelemetry.sdk.metrics.data.DoubleExemplarData;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.ExemplarData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramBuckets;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.data.GaugeData;
import io.opentelemetry.sdk.metrics.data.HistogramData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongExemplarData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.data.SumData;
import io.opentelemetry.sdk.metrics.data.SummaryData;
import io.opentelemetry.sdk.metrics.data.SummaryPointData;
import io.opentelemetry.sdk.metrics.data.ValueAtQuantile;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Final fail-closed validator used both by typed construction and SDK exporter decorators. */
public final class TelemetryRecordGuard {
    private static final Pattern SERVICE_VERSION = Pattern.compile("[0-9][0-9A-Za-z.+-]{0,63}");
    private static final Pattern ENVIRONMENT = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern SCOPE_NAME =
            Pattern.compile("(?:com\\.inforvans\\.accord|io\\.opentelemetry)(?:[.][A-Za-z0-9_-]+)*");
    private static final Pattern SCOPE_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private final TelemetryGuardMetrics metrics;
    private final SensitiveTelemetryCandidate sensitive;

    private TelemetryRecordGuard(
            TelemetryGuardMetrics metrics, SensitiveTelemetryCandidate sensitive) {
        this.metrics = Objects.requireNonNull(metrics, "telemetry guard metrics must not be null");
        this.sensitive = Objects.requireNonNull(sensitive, "telemetry classifier must not be null");
    }

    public static TelemetryRecordGuard create(
            TelemetryGuardMetrics metrics, List<String> configuredSentinels) {
        return new TelemetryRecordGuard(metrics, new SensitiveTelemetryCandidate(configuredSentinels));
    }

    static Attributes validatedAttributes(TelemetryAttributes attributes) {
        Objects.requireNonNull(attributes, "telemetry attributes must not be null");
        AttributesBuilder builder = Attributes.builder();
        TelemetryGuardMetrics.Signal signal;
        if (attributes instanceof TelemetryAttributes.Http http) {
            putIdentifiers(builder, http.identifiers());
            putCommon(builder, http.operation(), http.provider(), http.resultCode());
            builder.put(TelemetryAttributeKey.HTTP_METHOD.stringKey(), http.method().wireName());
            builder.put(TelemetryAttributeKey.HTTP_ROUTE.stringKey(), http.route().value());
            builder.put(TelemetryAttributeKey.HTTP_STATUS.longKey(), (long) http.status().value());
            signal = TelemetryGuardMetrics.Signal.SPAN;
        } else if (attributes instanceof TelemetryAttributes.Workflow workflow) {
            putIdentifiers(builder, workflow.identifiers());
            putCommon(builder, workflow.operation(), workflow.provider(), workflow.resultCode());
            signal = TelemetryGuardMetrics.Signal.SPAN;
        } else if (attributes instanceof TelemetryAttributes.DomainEvent domainEvent) {
            putIdentifiers(builder, domainEvent.identifiers());
            putCommon(
                    builder,
                    domainEvent.operation(),
                    domainEvent.provider(),
                    domainEvent.resultCode());
            builder.put(
                    TelemetryAttributeKey.AGGREGATE_TYPE.stringKey(),
                    domainEvent.aggregateType().wireName());
            builder.put(
                    TelemetryAttributeKey.EVENT_TYPE.stringKey(),
                    domainEvent.eventType().wireName());
            signal = TelemetryGuardMetrics.Signal.SPAN;
        } else if (attributes instanceof TelemetryAttributes.Metric metric) {
            putCommon(builder, metric.operation(), metric.provider(), metric.resultCode());
            signal = TelemetryGuardMetrics.Signal.METRIC;
        } else {
            throw new IllegalArgumentException("unregistered telemetry attribute variant");
        }
        Attributes result = builder.build();
        TelemetryRecordGuard validation = new TelemetryRecordGuard(
                new TelemetryGuardMetrics(), new SensitiveTelemetryCandidate(List.of()));
        if (validation.findAttributeRejection(result, signal).isPresent()) {
            throw new IllegalArgumentException("invalid telemetry attributes");
        }
        return result;
    }

    public Optional<TelemetryDropReason> inspectSpan(SpanData span) {
        Optional<TelemetryDropReason> rejection;
        try {
            rejection = findSpanRejection(span);
        } catch (RuntimeException ignored) {
            rejection = Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        rejection.ifPresent(reason -> metrics.recordDropped(TelemetryGuardMetrics.Signal.SPAN, reason));
        return rejection;
    }

    public Optional<TelemetryDropReason> inspectMetric(MetricData metric) {
        Optional<TelemetryDropReason> rejection;
        try {
            rejection = findMetricDescriptorRejection(metric);
            if (rejection.isEmpty()) {
                for (PointData point : metric.getData().getPoints()) {
                    rejection = findMetricPointRejection(metric, point);
                    if (rejection.isPresent()) {
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            rejection = Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        rejection.ifPresent(reason -> metrics.recordDropped(TelemetryGuardMetrics.Signal.METRIC, reason));
        return rejection;
    }

    public Optional<TelemetryDropReason> inspectLog(LogRecordData log) {
        Optional<TelemetryDropReason> rejection;
        try {
            rejection = findLogRejection(log);
        } catch (RuntimeException ignored) {
            rejection = Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        rejection.ifPresent(reason -> metrics.recordDropped(TelemetryGuardMetrics.Signal.LOG, reason));
        return rejection;
    }

    Optional<TelemetryDropReason> findMetricDescriptorRejection(MetricData metric) {
        if (metric == null
                || metric.getData() == null
                || metric.getType() == null
                || !dataMatchesType(metric.getType(), metric.getData())
                || metric.getData().getPoints() == null) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        Optional<TelemetryDropReason> rejection = findResourceRejection(
                metric.getResource(), TelemetryGuardMetrics.Signal.METRIC);
        if (rejection.isPresent()) {
            return rejection;
        }
        rejection = findScopeRejection(
                metric.getInstrumentationScopeInfo(), TelemetryGuardMetrics.Signal.METRIC);
        if (rejection.isPresent()) {
            return rejection;
        }
        rejection = sensitive.classifyValue(metric.getName());
        if (rejection.isPresent()) {
            return rejection;
        }
        MetricSpec spec = MetricSpec.find(metric.getName());
        if (spec == null) {
            return Optional.of(TelemetryDropReason.UNREGISTERED_NAME);
        }
        if (!spec.description.equals(metric.getDescription()) || !spec.unit.equals(metric.getUnit())) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        return Optional.empty();
    }

    Optional<TelemetryDropReason> findMetricPointRejection(MetricData metric, PointData point) {
        if (metric == null
                || metric.getType() == null
                || point == null
                || point.getAttributes() == null
                || point.getExemplars() == null
                || point.getStartEpochNanos() < 0L
                || point.getEpochNanos() <= 0L
                || point.getStartEpochNanos() > point.getEpochNanos()
                || !pointMatchesType(metric.getType(), point)
                || !hasValidPointValue(metric.getType(), point)) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        Optional<TelemetryDropReason> rejection = findAttributeRejection(
                point.getAttributes(), TelemetryGuardMetrics.Signal.METRIC);
        if (rejection.isPresent()) {
            return rejection;
        }
        rejection = findMetricLabelShapeRejection(metric.getName(), point.getAttributes());
        if (rejection.isPresent()) {
            return rejection;
        }
        for (ExemplarData exemplar : point.getExemplars()) {
            if (!hasValidExemplar(metric.getType(), point, exemplar)) {
                return Optional.of(TelemetryDropReason.INVALID_VALUE);
            }
            rejection = findAttributeRejection(
                    exemplar.getFilteredAttributes(), TelemetryGuardMetrics.Signal.METRIC);
            if (rejection.isPresent()) {
                return rejection;
            }
            rejection = findSpanContextRejection(exemplar.getSpanContext(), false);
            if (rejection.isPresent()) {
                return rejection;
            }
        }
        return Optional.empty();
    }

    private static boolean dataMatchesType(MetricDataType type, Data<?> data) {
        return switch (type) {
            case LONG_GAUGE, DOUBLE_GAUGE -> data instanceof GaugeData<?>;
            case LONG_SUM, DOUBLE_SUM ->
                    data instanceof SumData<?> sum && sum.getAggregationTemporality() != null;
            case HISTOGRAM ->
                    data instanceof HistogramData histogram
                            && histogram.getAggregationTemporality() != null;
            case EXPONENTIAL_HISTOGRAM ->
                    data instanceof ExponentialHistogramData histogram
                            && histogram.getAggregationTemporality() != null;
            case SUMMARY -> data instanceof SummaryData;
        };
    }

    private static boolean pointMatchesType(MetricDataType type, PointData point) {
        return switch (type) {
            case LONG_GAUGE, LONG_SUM -> point instanceof LongPointData;
            case DOUBLE_GAUGE, DOUBLE_SUM -> point instanceof DoublePointData;
            case HISTOGRAM -> point instanceof HistogramPointData;
            case EXPONENTIAL_HISTOGRAM -> point instanceof ExponentialHistogramPointData;
            case SUMMARY -> point instanceof SummaryPointData;
        };
    }

    private static boolean hasValidPointValue(MetricDataType type, PointData point) {
        return switch (type) {
            case LONG_GAUGE, LONG_SUM -> true;
            case DOUBLE_GAUGE, DOUBLE_SUM ->
                    Double.isFinite(((DoublePointData) point).getValue());
            case HISTOGRAM -> hasValidHistogram((HistogramPointData) point);
            case EXPONENTIAL_HISTOGRAM ->
                    hasValidExponentialHistogram((ExponentialHistogramPointData) point);
            case SUMMARY -> hasValidSummary((SummaryPointData) point);
        };
    }

    private static boolean hasValidHistogram(HistogramPointData point) {
        List<Double> boundaries = point.getBoundaries();
        List<Long> counts = point.getCounts();
        if (point.getCount() < 0L
                || !Double.isFinite(point.getSum())
                || boundaries == null
                || counts == null
                || counts.size() != boundaries.size() + 1
                || !countsMatch(counts, point.getCount())) {
            return false;
        }
        double previous = Double.NEGATIVE_INFINITY;
        for (Double boundary : boundaries) {
            if (boundary == null || !Double.isFinite(boundary) || boundary <= previous) {
                return false;
            }
            previous = boundary;
        }
        if (point.hasMin() && (!Double.isFinite(point.getMin()) || point.getCount() == 0L)) {
            return false;
        }
        if (point.hasMax() && (!Double.isFinite(point.getMax()) || point.getCount() == 0L)) {
            return false;
        }
        return !point.hasMin() || !point.hasMax() || point.getMin() <= point.getMax();
    }

    private static boolean hasValidExponentialHistogram(ExponentialHistogramPointData point) {
        ExponentialHistogramBuckets positive = point.getPositiveBuckets();
        ExponentialHistogramBuckets negative = point.getNegativeBuckets();
        if (point.getCount() < 0L
                || point.getZeroCount() < 0L
                || !Double.isFinite(point.getSum())
                || positive == null
                || negative == null
                || !hasValidBuckets(positive, point.getScale())
                || !hasValidBuckets(negative, point.getScale())) {
            return false;
        }
        try {
            long bucketCount = Math.addExact(positive.getTotalCount(), negative.getTotalCount());
            bucketCount = Math.addExact(bucketCount, point.getZeroCount());
            if (bucketCount != point.getCount()) {
                return false;
            }
        } catch (ArithmeticException ignored) {
            return false;
        }
        if (point.hasMin() && (!Double.isFinite(point.getMin()) || point.getCount() == 0L)) {
            return false;
        }
        if (point.hasMax() && (!Double.isFinite(point.getMax()) || point.getCount() == 0L)) {
            return false;
        }
        return !point.hasMin() || !point.hasMax() || point.getMin() <= point.getMax();
    }

    private static boolean hasValidBuckets(ExponentialHistogramBuckets buckets, int scale) {
        return buckets.getScale() == scale
                && buckets.getBucketCounts() != null
                && buckets.getTotalCount() >= 0L
                && countsMatch(buckets.getBucketCounts(), buckets.getTotalCount());
    }

    private static boolean hasValidSummary(SummaryPointData point) {
        List<ValueAtQuantile> values = point.getValues();
        if (point.getCount() < 0L || !Double.isFinite(point.getSum()) || values == null) {
            return false;
        }
        double previous = -1.0D;
        for (ValueAtQuantile value : values) {
            if (value == null
                    || !Double.isFinite(value.getQuantile())
                    || value.getQuantile() < 0.0D
                    || value.getQuantile() > 1.0D
                    || value.getQuantile() <= previous
                    || !Double.isFinite(value.getValue())) {
                return false;
            }
            previous = value.getQuantile();
        }
        return true;
    }

    private static boolean countsMatch(List<Long> counts, long expected) {
        long total = 0L;
        try {
            for (Long count : counts) {
                if (count == null || count < 0L) {
                    return false;
                }
                total = Math.addExact(total, count);
            }
        } catch (ArithmeticException ignored) {
            return false;
        }
        return total == expected;
    }

    private static boolean hasValidExemplar(
            MetricDataType type, PointData point, ExemplarData exemplar) {
        if (exemplar == null
                || exemplar.getFilteredAttributes() == null
                || exemplar.getSpanContext() == null
                || exemplar.getEpochNanos() <= 0L
                || exemplar.getEpochNanos() > point.getEpochNanos()
                || (point.getStartEpochNanos() > 0L
                        && exemplar.getEpochNanos() < point.getStartEpochNanos())) {
            return false;
        }
        return switch (type) {
            case LONG_GAUGE, LONG_SUM -> exemplar instanceof LongExemplarData;
            case DOUBLE_GAUGE, DOUBLE_SUM, HISTOGRAM, EXPONENTIAL_HISTOGRAM ->
                    exemplar instanceof DoubleExemplarData value
                            && Double.isFinite(value.getValue());
            case SUMMARY -> false;
        };
    }

    private Optional<TelemetryDropReason> findSpanContextRejection(
            SpanContext context, boolean validRequired) {
        if (context == null || context.getTraceFlags() == null || context.getTraceState() == null) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        if (!context.isValid()) {
            return validRequired
                    ? Optional.of(TelemetryDropReason.INVALID_VALUE)
                    : Optional.empty();
        }
        if (!TRACE_ID.matcher(context.getTraceId()).matches()
                || !SPAN_ID.matcher(context.getSpanId()).matches()) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        Optional<TelemetryDropReason> rejection = sensitive.classifyValue(context.getTraceId());
        if (rejection.isPresent()) {
            return rejection;
        }
        rejection = sensitive.classifyValue(context.getSpanId());
        if (rejection.isPresent()) {
            return rejection;
        }
        for (Map.Entry<String, String> entry : context.getTraceState().asMap().entrySet()) {
            rejection = sensitive.classifyKey(entry.getKey());
            if (rejection.isPresent()) {
                return rejection;
            }
            rejection = sensitive.classifyValue(entry.getValue());
            if (rejection.isPresent()) {
                return rejection;
            }
            if (SensitiveTelemetryCandidate.codePointLength(entry.getKey()) > 255
                    || SensitiveTelemetryCandidate.codePointLength(entry.getValue()) > 255) {
                return Optional.of(TelemetryDropReason.INVALID_VALUE);
            }
        }
        return Optional.empty();
    }

    void recordMetricDrop(TelemetryDropReason reason) {
        metrics.recordDropped(TelemetryGuardMetrics.Signal.METRIC, reason);
    }

    private Optional<TelemetryDropReason> findSpanRejection(SpanData span) {
        if (span == null
                || span.getAttributes() == null
                || span.getEvents() == null
                || span.getLinks() == null
                || span.getStatus() == null
                || span.getKind() == null
                || span.getSpanContext() == null
                || span.getParentSpanContext() == null
                || !span.hasEnded()
                || span.getStartEpochNanos() <= 0L
                || span.getEndEpochNanos() < span.getStartEpochNanos()) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        Optional<TelemetryDropReason> rejection =
                findSpanContextRejection(span.getSpanContext(), true);
        if (rejection.isPresent()) {
            return rejection;
        }
        rejection = findSpanContextRejection(span.getParentSpanContext(), false);
        if (rejection.isPresent()) {
            return rejection;
        }
        rejection = findResourceRejection(
                span.getResource(), TelemetryGuardMetrics.Signal.SPAN);
        if (rejection.isPresent()) {
            return rejection;
        }
        rejection = findScopeRejection(
                span.getInstrumentationScopeInfo(), TelemetryGuardMetrics.Signal.SPAN);
        if (rejection.isPresent()) {
            return rejection;
        }
        rejection = sensitive.classifyValue(span.getName());
        if (rejection.isPresent()) {
            return rejection;
        }
        if (!TelemetryOperation.isRegistered(span.getName())
                && !TelemetryAttributes.RouteTemplate.isRegisteredSpanName(span.getName())) {
            return Optional.of(TelemetryDropReason.UNREGISTERED_NAME);
        }
        if (!span.getStatus().getDescription().isEmpty()) {
            return Optional.of(TelemetryDropReason.EXCEPTION_CONTENT);
        }
        if (span.getTotalAttributeCount() != span.getAttributes().size()
                || span.getTotalRecordedEvents() != span.getEvents().size()
                || span.getTotalRecordedLinks() != span.getLinks().size()) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        rejection = findAttributeRejection(
                span.getAttributes(), TelemetryGuardMetrics.Signal.SPAN);
        if (rejection.isPresent()) {
            return rejection;
        }
        for (EventData event : span.getEvents()) {
            rejection = findEventRejection(event);
            if (rejection.isPresent()) {
                return rejection;
            }
        }
        for (LinkData link : span.getLinks()) {
            rejection = findLinkRejection(link);
            if (rejection.isPresent()) {
                return rejection;
            }
        }
        return Optional.empty();
    }

    private Optional<TelemetryDropReason> findEventRejection(EventData event) {
        if (event == null || event instanceof ExceptionEventData || event.getAttributes() == null) {
            return Optional.of(TelemetryDropReason.EXCEPTION_CONTENT);
        }
        Optional<TelemetryDropReason> rejection = sensitive.classifyValue(event.getName());
        if (rejection.isPresent()) {
            return rejection;
        }
        if (!TelemetryAttributes.EventType.isRegistered(event.getName())) {
            return Optional.of(TelemetryDropReason.UNREGISTERED_NAME);
        }
        if (event.getTotalAttributeCount() != event.getAttributes().size()) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        return findAttributeRejection(event.getAttributes(), TelemetryGuardMetrics.Signal.SPAN);
    }

    private Optional<TelemetryDropReason> findLinkRejection(LinkData link) {
        if (link == null || link.getAttributes() == null || link.getSpanContext() == null) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        Optional<TelemetryDropReason> rejection =
                findSpanContextRejection(link.getSpanContext(), true);
        if (rejection.isPresent()) {
            return rejection;
        }
        if (link.getTotalAttributeCount() != link.getAttributes().size()) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        return findAttributeRejection(link.getAttributes(), TelemetryGuardMetrics.Signal.SPAN);
    }

    private Optional<TelemetryDropReason> findLogRejection(LogRecordData log) {
        if (log == null || log.getAttributes() == null) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        Optional<TelemetryDropReason> rejection = findResourceRejection(
                log.getResource(), TelemetryGuardMetrics.Signal.LOG);
        if (rejection.isPresent()) {
            return rejection;
        }
        rejection = findScopeRejection(
                log.getInstrumentationScopeInfo(), TelemetryGuardMetrics.Signal.LOG);
        if (rejection.isPresent()) {
            return rejection;
        }
        if (log.getTotalAttributeCount() != log.getAttributes().size()) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        String severityText = log.getSeverityText();
        if (severityText != null) {
            rejection = sensitive.classifyValue(severityText);
            if (rejection.isPresent() || SensitiveTelemetryCandidate.codePointLength(severityText) > 16) {
                return rejection.isPresent()
                        ? rejection
                        : Optional.of(TelemetryDropReason.INVALID_VALUE);
            }
        }
        Value<?> body = log.getBodyValue();
        if (body != null) {
            if (body.getType() != ValueType.STRING || !(body.getValue() instanceof String bodyText)) {
                return Optional.of(TelemetryDropReason.INVALID_TYPE);
            }
            rejection = sensitive.classifyValue(bodyText);
            if (rejection.isPresent()) {
                return rejection;
            }
            if (SensitiveTelemetryCandidate.codePointLength(bodyText) > 255) {
                return Optional.of(TelemetryDropReason.INVALID_VALUE);
            }
        }
        return findAttributeRejection(log.getAttributes(), TelemetryGuardMetrics.Signal.LOG);
    }

    private Optional<TelemetryDropReason> findResourceRejection(
            Resource resource, TelemetryGuardMetrics.Signal signal) {
        if (resource == null || resource.getAttributes() == null) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        if (resource.getSchemaUrl() != null) {
            return Optional.of(TelemetryDropReason.RAW_URL);
        }
        return findAttributeRejection(resource.getAttributes(), signal);
    }

    private Optional<TelemetryDropReason> findScopeRejection(
            InstrumentationScopeInfo scope, TelemetryGuardMetrics.Signal signal) {
        if (scope == null
                || scope.getName() == null
                || !SCOPE_NAME.matcher(scope.getName()).matches()) {
            return Optional.of(TelemetryDropReason.UNREGISTERED_NAME);
        }
        Optional<TelemetryDropReason> rejection = sensitive.classifyValue(scope.getName());
        if (rejection.isPresent()) {
            return rejection;
        }
        if (scope.getVersion() != null) {
            rejection = sensitive.classifyValue(scope.getVersion());
            if (rejection.isPresent()) {
                return rejection;
            }
            if (!SCOPE_VERSION.matcher(scope.getVersion()).matches()) {
                return Optional.of(TelemetryDropReason.INVALID_VALUE);
            }
        }
        if (scope.getSchemaUrl() != null) {
            return Optional.of(TelemetryDropReason.RAW_URL);
        }
        return findAttributeRejection(scope.getAttributes(), signal);
    }

    private Optional<TelemetryDropReason> findAttributeRejection(
            Attributes attributes, TelemetryGuardMetrics.Signal signal) {
        if (attributes == null) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        for (Map.Entry<AttributeKey<?>, Object> entry : attributes.asMap().entrySet()) {
            AttributeKey<?> actualKey = entry.getKey();
            Object value = entry.getValue();
            if (actualKey == null) {
                return Optional.of(TelemetryDropReason.UNKNOWN_KEY);
            }
            Optional<TelemetryDropReason> rejection = sensitive.classifyKey(actualKey.getKey());
            if (rejection.isPresent()) {
                return rejection;
            }
            Optional<TelemetryAttributeKey> registered =
                    TelemetryAttributeKey.find(actualKey.getKey());
            if (registered.isEmpty()) {
                return Optional.of(TelemetryDropReason.UNKNOWN_KEY);
            }
            TelemetryAttributeKey key = registered.orElseThrow();
            if (!key.allows(signal)) {
                return Optional.of(TelemetryDropReason.CARDINALITY_POLICY);
            }
            if (!key.hasExpectedType(actualKey, value)) {
                return Optional.of(TelemetryDropReason.INVALID_TYPE);
            }
            if (value instanceof String stringValue) {
                rejection = sensitive.classifyValue(stringValue);
                if (rejection.isPresent()) {
                    return rejection;
                }
                if (SensitiveTelemetryCandidate.codePointLength(stringValue)
                        > key.maximumCodePoints()) {
                    return Optional.of(TelemetryDropReason.INVALID_VALUE);
                }
            }
            rejection = findKeyValueRejection(key, value);
            if (rejection.isPresent()) {
                return rejection;
            }
        }
        return findOperationTripleRejection(attributes);
    }

    private static Optional<TelemetryDropReason> findKeyValueRejection(
            TelemetryAttributeKey key, Object value) {
        try {
            switch (key) {
                case TENANT_ID -> new TelemetryIdentifiers.TenantId((String) value);
                case SCOPE_TYPE -> TelemetryIdentifiers.ScopeType.fromWireName((String) value);
                case SCOPE_ID -> new TelemetryIdentifiers.ScopeId((String) value);
                case CORRELATION_ID -> new TelemetryIdentifiers.CorrelationId((String) value);
                case CAUSATION_ID -> new TelemetryIdentifiers.CausationId((String) value);
                case OPERATION -> TelemetryOperation.fromWireName((String) value);
                case PROVIDER -> TelemetryProvider.fromWireName((String) value);
                case RESULT_CODE -> TelemetryResultCode.fromWireName((String) value);
                case AGGREGATE_TYPE ->
                        TelemetryAttributes.AggregateType.fromWireName((String) value);
                case EVENT_TYPE -> TelemetryAttributes.EventType.fromWireName((String) value);
                case HTTP_METHOD -> TelemetryAttributes.HttpMethod.fromWireName((String) value);
                case HTTP_ROUTE -> new TelemetryAttributes.RouteTemplate((String) value);
                case HTTP_STATUS -> new TelemetryAttributes.HttpStatus(Math.toIntExact((Long) value));
                case SERVICE_NAME -> validateServiceName((String) value);
                case SERVICE_NAMESPACE -> validateExact((String) value, "accord");
                case SERVICE_VERSION -> validatePattern((String) value, SERVICE_VERSION);
                case DEPLOYMENT_ENVIRONMENT -> validatePattern((String) value, ENVIRONMENT);
                case TELEMETRY_SDK_NAME -> validateExact((String) value, "opentelemetry");
                case TELEMETRY_SDK_LANGUAGE -> validateExact((String) value, "java");
                case TELEMETRY_SDK_VERSION -> validatePattern((String) value, SERVICE_VERSION);
                case DROP_SIGNAL -> validateDropSignal((String) value);
                case DROP_REASON -> validateDropReason((String) value);
            }
            return Optional.empty();
        } catch (ArithmeticException | IllegalArgumentException ignored) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
    }

    private static Optional<TelemetryDropReason> findOperationTripleRejection(Attributes attributes) {
        String operation = attributes.get(TelemetryAttributeKey.OPERATION.stringKey());
        String provider = attributes.get(TelemetryAttributeKey.PROVIDER.stringKey());
        String resultCode = attributes.get(TelemetryAttributeKey.RESULT_CODE.stringKey());
        boolean any = operation != null || provider != null || resultCode != null;
        if (!any) {
            return Optional.empty();
        }
        if (operation == null || provider == null || resultCode == null) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
        try {
            TelemetryOperation typedOperation = TelemetryOperation.fromWireName(operation);
            TelemetryProvider typedProvider = TelemetryProvider.fromWireName(provider);
            TelemetryResultCode.fromWireName(resultCode);
            if (typedOperation.providerContextRequired()
                    == (typedProvider == TelemetryProvider.NOT_APPLICABLE)) {
                return Optional.of(TelemetryDropReason.INVALID_VALUE);
            }
            return Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.of(TelemetryDropReason.INVALID_VALUE);
        }
    }

    private static Optional<TelemetryDropReason> findMetricLabelShapeRejection(
            String metricName, Attributes attributes) {
        MetricSpec spec = MetricSpec.find(metricName);
        if (spec == null) {
            return Optional.of(TelemetryDropReason.UNREGISTERED_NAME);
        }
        if (spec == MetricSpec.RECORDS_DROPPED) {
            return hasExactly(attributes, TelemetryAttributeKey.DROP_SIGNAL, TelemetryAttributeKey.DROP_REASON)
                    ? Optional.empty()
                    : Optional.of(TelemetryDropReason.CARDINALITY_POLICY);
        }
        if (spec == MetricSpec.EXPORT_FAILURES) {
            return hasExactly(attributes, TelemetryAttributeKey.DROP_SIGNAL)
                    ? Optional.empty()
                    : Optional.of(TelemetryDropReason.CARDINALITY_POLICY);
        }
        return hasExactly(
                        attributes,
                        TelemetryAttributeKey.OPERATION,
                        TelemetryAttributeKey.PROVIDER,
                        TelemetryAttributeKey.RESULT_CODE)
                ? Optional.empty()
                : Optional.of(TelemetryDropReason.CARDINALITY_POLICY);
    }

    private static boolean hasExactly(Attributes attributes, TelemetryAttributeKey... keys) {
        if (attributes.size() != keys.length) {
            return false;
        }
        for (TelemetryAttributeKey key : keys) {
            if (!attributes.asMap().containsKey(key.otelKey())) {
                return false;
            }
        }
        return true;
    }

    private static void putIdentifiers(
            AttributesBuilder builder, TelemetryIdentifiers identifiers) {
        identifiers.tenantId().ifPresent(value ->
                builder.put(TelemetryAttributeKey.TENANT_ID.stringKey(), value.value()));
        identifiers.scope().ifPresent(value -> {
            builder.put(TelemetryAttributeKey.SCOPE_TYPE.stringKey(), value.type().wireName());
            builder.put(TelemetryAttributeKey.SCOPE_ID.stringKey(), value.id().value());
        });
        identifiers.correlationId().ifPresent(value ->
                builder.put(TelemetryAttributeKey.CORRELATION_ID.stringKey(), value.value()));
        identifiers.causationId().ifPresent(value ->
                builder.put(TelemetryAttributeKey.CAUSATION_ID.stringKey(), value.value()));
    }

    private static void putCommon(
            AttributesBuilder builder,
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode) {
        builder.put(TelemetryAttributeKey.OPERATION.stringKey(), operation.wireName());
        builder.put(TelemetryAttributeKey.PROVIDER.stringKey(), provider.wireName());
        builder.put(TelemetryAttributeKey.RESULT_CODE.stringKey(), resultCode.wireName());
    }

    private static void validateServiceName(String value) {
        if (!value.equals("accord-control-api")
                && !value.equals("accord-control-worker")
                && !value.equals("accord-webhook-edge")) {
            throw new IllegalArgumentException("invalid telemetry service name");
        }
    }

    private static void validateExact(String value, String expected) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException("invalid telemetry value");
        }
    }

    private static void validatePattern(String value, Pattern pattern) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid telemetry value");
        }
    }

    private static void validateDropSignal(String value) {
        for (TelemetryGuardMetrics.Signal signal : TelemetryGuardMetrics.Signal.values()) {
            if (signal.wireName().equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException("invalid telemetry drop signal");
    }

    private static void validateDropReason(String value) {
        for (TelemetryDropReason reason : TelemetryDropReason.values()) {
            if (reason.wireName().equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException("invalid telemetry drop reason");
    }

    private enum MetricSpec {
        RECORDS_DROPPED(
                TelemetryGuardMetrics.DROPPED_METRIC_NAME,
                "Telemetry records rejected by the final export guard.",
                "{record}"),
        EXPORT_FAILURES(
                TelemetryGuardMetrics.EXPORT_FAILURE_METRIC_NAME,
                "Telemetry export attempts that failed without affecting business work.",
                "{attempt}"),
        HTTP_REQUESTS(
                "accord.http.requests",
                "Completed bounded-route HTTP operations.",
                "{request}"),
        WORKFLOW_EXECUTIONS(
                "accord.workflow.executions",
                "Completed durable workflow operations.",
                "{operation}"),
        RELIABILITY_DELIVERIES(
                "accord.reliability.deliveries",
                "Completed durable message delivery operations.",
                "{message}");

        private final String name;
        private final String description;
        private final String unit;

        MetricSpec(String name, String description, String unit) {
            this.name = name;
            this.description = description;
            this.unit = unit;
        }

        static MetricSpec find(String candidate) {
            if (candidate != null) {
                for (MetricSpec spec : values()) {
                    if (spec.name.equals(candidate)) {
                        return spec;
                    }
                }
            }
            return null;
        }
    }
}
