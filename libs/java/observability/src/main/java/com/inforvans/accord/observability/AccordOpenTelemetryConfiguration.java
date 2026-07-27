package com.inforvans.accord.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Constructs the single guarded OpenTelemetry SDK owned by an Accord process. */
public final class AccordOpenTelemetryConfiguration implements AutoCloseable {
    private static final Pattern VERSION = Pattern.compile("[0-9][0-9A-Za-z.+-]{0,63}");
    private static final Duration MAXIMUM_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private final OpenTelemetrySdk sdk;
    private final TelemetryGuardMetrics guardMetrics;
    private final Duration shutdownTimeout;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final Thread shutdownHook;

    private AccordOpenTelemetryConfiguration(
            OpenTelemetrySdk sdk,
            TelemetryGuardMetrics guardMetrics,
            Duration shutdownTimeout,
            ServiceName serviceName) {
        this.sdk = sdk;
        this.guardMetrics = guardMetrics;
        this.shutdownTimeout = shutdownTimeout;
        this.shutdownHook = new Thread(
                this::close, "accord-telemetry-shutdown-" + serviceName.wireName());
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static AccordOpenTelemetryConfiguration create(
            Settings settings,
            SpanExporter spanExporter,
            MetricExporter metricExporter,
            LogRecordExporter logExporter) {
        Objects.requireNonNull(settings, "telemetry settings must not be null");
        TelemetryGuardMetrics metrics = new TelemetryGuardMetrics();
        TelemetryRecordGuard guard = TelemetryRecordGuard.create(metrics, settings.sentinels());
        GuardedSpanExporter guardedSpans = new GuardedSpanExporter(
                spanExporter, guard, metrics, settings.exportTimeout(), 1);
        GuardedMetricExporter guardedMetrics = new GuardedMetricExporter(
                metricExporter, guard, metrics, settings.exportTimeout(), 1);
        GuardedLogRecordExporter guardedLogs = new GuardedLogRecordExporter(
                logExporter, guard, metrics, settings.exportTimeout(), 1);
        Resource resource = Resource.create(resourceAttributes(settings));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.traceIdRatioBased(settings.traceSampleRate()))
                .addSpanProcessor(BatchSpanProcessor.builder(guardedSpans)
                        .setScheduleDelay(Duration.ofSeconds(1))
                        .setMaxQueueSize(settings.queueCapacity())
                        .setMaxExportBatchSize(settings.batchSize())
                        .setExporterTimeout(settings.exportTimeout())
                        .build())
                .build();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(guardedMetrics)
                        .setInterval(Duration.ofSeconds(30))
                        .build())
                .build();
        SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(guardedLogs)
                        .setScheduleDelay(Duration.ofSeconds(1))
                        .setMaxQueueSize(settings.queueCapacity())
                        .setMaxExportBatchSize(settings.batchSize())
                        .setExporterTimeout(settings.exportTimeout())
                        .build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .setLoggerProvider(loggerProvider)
                .setPropagators(ContextPropagators.create(
                        W3CTraceContextPropagator.getInstance()))
                .build();
        return new AccordOpenTelemetryConfiguration(
                sdk, metrics, settings.shutdownTimeout(), settings.serviceName());
    }

    public OpenTelemetrySdk openTelemetrySdk() {
        return sdk;
    }

    public TelemetryGuardMetrics guardMetrics() {
        return guardMetrics;
    }

    public ShutdownOutcome shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return ShutdownOutcome.ALREADY_SHUT_DOWN;
        }
        ShutdownOutcome outcome;
        try {
            CompletableResultCode result = sdk.shutdown();
            result.join(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!result.isDone()) {
                outcome = ShutdownOutcome.TIMED_OUT;
            } else if (!result.isSuccess()) {
                outcome = ShutdownOutcome.FAILED;
            } else {
                outcome = ShutdownOutcome.COMPLETED;
            }
        } catch (RuntimeException ignored) {
            outcome = ShutdownOutcome.FAILED;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException | SecurityException ignored) {
            // The JVM is already executing this hook.
        }
        return outcome;
    }

    @Override
    public void close() {
        shutdown();
    }

    private static Attributes resourceAttributes(Settings settings) {
        AttributesBuilder attributes = Attributes.builder();
        attributes.put(
                TelemetryAttributeKey.SERVICE_NAME.stringKey(), settings.serviceName().wireName());
        attributes.put(TelemetryAttributeKey.SERVICE_NAMESPACE.stringKey(), "accord");
        attributes.put(
                TelemetryAttributeKey.SERVICE_VERSION.stringKey(), settings.serviceVersion());
        attributes.put(
                TelemetryAttributeKey.DEPLOYMENT_ENVIRONMENT.stringKey(),
                settings.environment().wireName());
        attributes.put(TelemetryAttributeKey.TELEMETRY_SDK_NAME.stringKey(), "opentelemetry");
        attributes.put(TelemetryAttributeKey.TELEMETRY_SDK_LANGUAGE.stringKey(), "java");
        attributes.put(TelemetryAttributeKey.TELEMETRY_SDK_VERSION.stringKey(), "1.49.0");
        return attributes.build();
    }

    public record Settings(
            ServiceName serviceName,
            String serviceVersion,
            DeploymentEnvironment environment,
            int queueCapacity,
            int batchSize,
            Duration exportTimeout,
            Duration shutdownTimeout,
            double traceSampleRate,
            List<String> sentinels) {
        public Settings {
            Objects.requireNonNull(serviceName, "telemetry service name must not be null");
            Objects.requireNonNull(environment, "telemetry environment must not be null");
            if (serviceVersion == null || !VERSION.matcher(serviceVersion).matches()) {
                throw new IllegalArgumentException("invalid telemetry service version");
            }
            if (queueCapacity < 1 || queueCapacity > 65_536) {
                throw new IllegalArgumentException("invalid telemetry queue capacity");
            }
            if (batchSize < 1 || batchSize > queueCapacity) {
                throw new IllegalArgumentException("invalid telemetry batch size");
            }
            validateDuration(exportTimeout, "invalid telemetry export timeout");
            validateDuration(shutdownTimeout, "invalid telemetry shutdown timeout");
            if (!Double.isFinite(traceSampleRate)
                    || traceSampleRate < 0.0D
                    || traceSampleRate > 1.0D) {
                throw new IllegalArgumentException("invalid telemetry sample rate");
            }
            sentinels = List.copyOf(Objects.requireNonNull(
                    sentinels, "telemetry sentinels must not be null"));
            new SensitiveTelemetryCandidate(sentinels);
        }

        public static Settings production(ServiceName serviceName, String serviceVersion) {
            return new Settings(
                    serviceName,
                    serviceVersion,
                    DeploymentEnvironment.PRODUCTION,
                    2_048,
                    256,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    0.1D,
                    List.of());
        }

        private static void validateDuration(Duration duration, String failureMessage) {
            if (duration == null
                    || duration.isZero()
                    || duration.isNegative()
                    || duration.compareTo(MAXIMUM_SHUTDOWN_TIMEOUT) > 0) {
                throw new IllegalArgumentException(failureMessage);
            }
        }
    }

    public enum ServiceName {
        CONTROL_API("accord-control-api"),
        CONTROL_WORKER("accord-control-worker"),
        WEBHOOK_EDGE("accord-webhook-edge");

        private final String wireName;

        ServiceName(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static ServiceName parse(String value) {
            return Arrays.stream(values())
                    .filter(candidate -> candidate.wireName.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unregistered telemetry service name"));
        }
    }

    public enum DeploymentEnvironment {
        LOCAL,
        DEVELOPMENT,
        STAGING,
        PRODUCTION,
        TEST;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static DeploymentEnvironment parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("telemetry environment is required");
            }
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException("unregistered telemetry environment");
            }
        }
    }

    public enum ShutdownOutcome {
        COMPLETED,
        TIMED_OUT,
        FAILED,
        ALREADY_SHUT_DOWN
    }

    /** Spring Boot entry point shared by all Accord executable processes. */
    @AutoConfiguration(beforeName =
            "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration")
    public static class SpringAutoConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean({AccordOpenTelemetryConfiguration.class, OpenTelemetry.class})
        AccordOpenTelemetryConfiguration accordOpenTelemetryConfiguration(Environment environment) {
            ServiceName serviceName = ServiceName.parse(
                    environment.getRequiredProperty("spring.application.name"));
            DeploymentEnvironment deploymentEnvironment = DeploymentEnvironment.parse(
                    environment.getProperty("accord.telemetry.environment", "local"));
            Duration exportTimeout = environment.getProperty(
                    "accord.telemetry.export-timeout", Duration.class, Duration.ofSeconds(2));
            Settings settings = new Settings(
                    serviceName,
                    environment.getProperty("accord.telemetry.service-version", "0.1.0"),
                    deploymentEnvironment,
                    environment.getProperty("accord.telemetry.queue-capacity", Integer.class, 2_048),
                    environment.getProperty("accord.telemetry.batch-size", Integer.class, 256),
                    exportTimeout,
                    environment.getProperty(
                            "accord.telemetry.shutdown-timeout",
                            Duration.class,
                            Duration.ofSeconds(5)),
                    environment.getProperty(
                            "management.tracing.sampling.probability", Double.class, 0.1D),
                    sentinelList(environment));
            String endpoint = signalEndpoint(
                    environment.getProperty(
                            "accord.telemetry.endpoint", "http://localhost:4318"),
                    deploymentEnvironment,
                    environment.getProperty("accord.telemetry.server-name"),
                    "traces");
            String metricsEndpoint = replaceSignal(endpoint, "metrics");
            String logsEndpoint = replaceSignal(endpoint, "logs");
            byte[] trustedCertificates = trustedCertificates(
                    environment.getProperty("accord.telemetry.trust-certificate"),
                    deploymentEnvironment);
            var spanExporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .setTimeout(exportTimeout);
            var metricExporter = OtlpHttpMetricExporter.builder()
                    .setEndpoint(metricsEndpoint)
                    .setTimeout(exportTimeout);
            var logExporter = OtlpHttpLogRecordExporter.builder()
                    .setEndpoint(logsEndpoint)
                    .setTimeout(exportTimeout);
            if (trustedCertificates != null) {
                spanExporter.setTrustedCertificates(trustedCertificates);
                metricExporter.setTrustedCertificates(trustedCertificates);
                logExporter.setTrustedCertificates(trustedCertificates);
            }
            return create(
                    settings,
                    spanExporter.build(),
                    metricExporter.build(),
                    logExporter.build());
        }

        @Bean(destroyMethod = "")
        @ConditionalOnMissingBean(OpenTelemetry.class)
        OpenTelemetry accordOpenTelemetrySdk(
                AccordOpenTelemetryConfiguration configuration) {
            return configuration.openTelemetrySdk();
        }

        @Bean
        @ConditionalOnBean(OpenTelemetry.class)
        @ConditionalOnMissingBean(AccordWorkflowTelemetry.class)
        AccordWorkflowTelemetry accordWorkflowTelemetry(OpenTelemetry openTelemetry) {
            return new AccordWorkflowTelemetry(openTelemetry);
        }

        @Bean
        @ConditionalOnBean(OpenTelemetry.class)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        @ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
        @ConditionalOnMissingBean(name = "accordHttpTelemetryFilterRegistration")
        FilterRegistrationBean<AccordHttpTelemetryFilter> accordHttpTelemetryFilterRegistration(
                OpenTelemetry openTelemetry,
                Environment environment) {
            ServiceName serviceName = ServiceName.parse(
                    environment.getRequiredProperty("spring.application.name"));
            FilterRegistrationBean<AccordHttpTelemetryFilter> registration =
                    new FilterRegistrationBean<>(
                            new AccordHttpTelemetryFilter(openTelemetry, serviceName));
            registration.setName("accordHttpTelemetryFilter");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            return registration;
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnBean(AccordOpenTelemetryConfiguration.class)
        @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
        AccordLogbackOpenTelemetryBridge accordLogbackOpenTelemetryBridge(
                AccordOpenTelemetryConfiguration configuration) {
            return AccordLogbackOpenTelemetryBridge.install(
                    configuration.openTelemetrySdk());
        }

        @Bean
        @ConditionalOnBean(AccordOpenTelemetryConfiguration.class)
        MeterBinder accordTelemetryGuardMeterBinder(
                AccordOpenTelemetryConfiguration configuration) {
            return registry -> {
                TelemetryGuardMetrics guardMetrics = configuration.guardMetrics();
                for (TelemetryGuardMetrics.Signal signal : TelemetryGuardMetrics.Signal.values()) {
                    for (TelemetryDropReason reason : TelemetryDropReason.values()) {
                        FunctionCounter.builder(
                                        TelemetryGuardMetrics.DROPPED_METRIC_NAME,
                                        guardMetrics,
                                        metrics -> metrics.droppedCount(signal, reason))
                                .description("Telemetry records rejected by the final export guard.")
                                .baseUnit("{record}")
                                .tag(
                                        TelemetryAttributeKey.DROP_SIGNAL.stringKey().getKey(),
                                        signal.wireName())
                                .tag(
                                        TelemetryAttributeKey.DROP_REASON.stringKey().getKey(),
                                        reason.wireName())
                                .register(registry);
                    }
                    FunctionCounter.builder(
                                    TelemetryGuardMetrics.EXPORT_FAILURE_METRIC_NAME,
                                    guardMetrics,
                                    metrics -> metrics.exportFailureCount(signal))
                            .description(
                                    "Telemetry export attempts that failed without affecting business work.")
                            .baseUnit("{attempt}")
                            .tag(
                                    TelemetryAttributeKey.DROP_SIGNAL.stringKey().getKey(),
                                    signal.wireName())
                            .register(registry);
                }
            };
        }

        private static List<String> sentinelList(Environment environment) {
            String[] values = environment.getProperty(
                    "accord.telemetry.sentinels", String[].class, new String[0]);
            return Arrays.stream(values).map(String::trim).filter(value -> !value.isEmpty()).toList();
        }

        private static String signalEndpoint(
                String value,
                DeploymentEnvironment environment,
                String expectedServerName,
                String signal) {
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException("invalid telemetry endpoint");
            }
            if (uri.getScheme() == null
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                throw new IllegalArgumentException("invalid telemetry endpoint");
            }
            if (environment == DeploymentEnvironment.PRODUCTION
                    && !uri.getScheme().equals("https")) {
                throw new IllegalArgumentException("production telemetry endpoint requires TLS");
            }
            if (environment == DeploymentEnvironment.PRODUCTION
                    && (expectedServerName == null
                    || expectedServerName.isBlank()
                    || !expectedServerName.equals(uri.getHost()))) {
                throw new IllegalArgumentException(
                        "production telemetry server name must match endpoint");
            }
            String normalized = value.endsWith("/")
                    ? value.substring(0, value.length() - 1)
                    : value;
            if (normalized.endsWith("/v1/traces")
                    || normalized.endsWith("/v1/metrics")
                    || normalized.endsWith("/v1/logs")) {
                normalized = normalized.substring(0, normalized.lastIndexOf("/v1/"));
            }
            return normalized + "/v1/" + signal;
        }

        private static String replaceSignal(String tracesEndpoint, String signal) {
            return tracesEndpoint.substring(0, tracesEndpoint.length() - "traces".length()) + signal;
        }

        private static byte[] trustedCertificates(
                String configuredPath, DeploymentEnvironment environment) {
            if (configuredPath == null || configuredPath.isBlank()) {
                if (environment == DeploymentEnvironment.PRODUCTION) {
                    throw new IllegalArgumentException(
                            "production telemetry trust certificate is required");
                }
                return null;
            }
            final Path certificate;
            try {
                certificate = Path.of(configuredPath).normalize();
            } catch (RuntimeException ignored) {
                throw new IllegalArgumentException("invalid telemetry trust certificate path");
            }
            if (!certificate.isAbsolute() || !Files.isRegularFile(certificate)) {
                throw new IllegalArgumentException("telemetry trust certificate is unavailable");
            }
            try {
                long size = Files.size(certificate);
                if (size < 1L || size > 1_048_576L) {
                    throw new IllegalArgumentException("invalid telemetry trust certificate size");
                }
                return Files.readAllBytes(certificate);
            } catch (IOException ignored) {
                throw new IllegalArgumentException("telemetry trust certificate is unreadable");
            }
        }
    }
}
