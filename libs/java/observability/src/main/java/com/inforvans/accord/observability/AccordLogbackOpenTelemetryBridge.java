package com.inforvans.accord.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/** Routes bounded Accord application logs into the guarded SDK logger provider. */
final class AccordLogbackOpenTelemetryBridge implements AutoCloseable {
    private static final String APPENDER_NAME = "accord-guarded-otlp";
    private final Logger root;
    private final AccordLogAppender appender;

    private AccordLogbackOpenTelemetryBridge(Logger root, AccordLogAppender appender) {
        this.root = root;
        this.appender = appender;
    }

    static AccordLogbackOpenTelemetryBridge install(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException("Accord requires the managed Logback runtime");
        }
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) != null) {
            throw new IllegalStateException("Accord OTLP log appender is already installed");
        }
        io.opentelemetry.api.logs.Logger logger = openTelemetry.getLogsBridge()
            .loggerBuilder("com.inforvans.accord.logback")
            .setInstrumentationVersion("1.0.0")
            .build();
        AccordLogAppender appender = new AccordLogAppender(logger);
        appender.setName(APPENDER_NAME);
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
        return new AccordLogbackOpenTelemetryBridge(root, appender);
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }

    private static final class AccordLogAppender extends AppenderBase<ILoggingEvent> {
        private static final String APPLICATION_LOGGER_PREFIX = "com.inforvans.accord";
        private final io.opentelemetry.api.logs.Logger logger;

        private AccordLogAppender(io.opentelemetry.api.logs.Logger logger) {
            this.logger = Objects.requireNonNull(logger, "logger");
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (event == null
                    || event.getLoggerName() == null
                    || !event.getLoggerName().startsWith(APPLICATION_LOGGER_PREFIX)) {
                return;
            }
            LogRecordBuilder record = logger.logRecordBuilder()
                .setTimestamp(Instant.ofEpochMilli(event.getTimeStamp()))
                .setObservedTimestamp(Instant.now())
                .setContext(Context.current())
                .setSeverity(severity(event.getLevel()))
                .setSeverityText(event.getLevel().levelStr);
            String body = event.getFormattedMessage();
            if (body != null) {
                record.setBody(body);
            }
            record.emit();
        }

        private static Severity severity(Level level) {
            if (level == null) {
                return Severity.UNDEFINED_SEVERITY_NUMBER;
            }
            return switch (level.toInt()) {
                case Level.TRACE_INT -> Severity.TRACE;
                case Level.DEBUG_INT -> Severity.DEBUG;
                case Level.INFO_INT -> Severity.INFO;
                case Level.WARN_INT -> Severity.WARN;
                case Level.ERROR_INT -> Severity.ERROR;
                default -> Severity.UNDEFINED_SEVERITY_NUMBER;
            };
        }
    }
}
