package com.inforvans.accord.observability;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Fixed sensitive-content classifier. It never retains or renders an examined candidate. */
public final class SensitiveTelemetryCandidate {
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
    private static final Pattern URI_SCHEME = Pattern.compile(
            "(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*://");
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:set-cookie|cookie|session(?:id)?|csrf)(?:[^a-z0-9]|$)");
    private final List<String> sentinels;

    SensitiveTelemetryCandidate(List<String> sentinels) {
        if (sentinels == null) {
            throw new IllegalArgumentException("invalid telemetry sentinel configuration");
        }
        for (String sentinel : sentinels) {
            if (sentinel == null
                    || sentinel.isBlank()
                    || codePointLength(sentinel) > 255
                    || containsControl(sentinel)) {
                throw new IllegalArgumentException("invalid telemetry sentinel configuration");
            }
        }
        this.sentinels = List.copyOf(sentinels);
    }

    Optional<TelemetryDropReason> classifyKey(String candidate) {
        if (candidate == null || candidate.isBlank() || containsControl(candidate)) {
            return Optional.of(TelemetryDropReason.SENSITIVE_KEY);
        }
        String normalized = candidate.toLowerCase(Locale.ROOT);
        if (normalized.contains("exception")) {
            return Optional.of(TelemetryDropReason.EXCEPTION_CONTENT);
        }
        if (normalized.contains("url")
                || normalized.contains("uri")
                || normalized.contains("query")
                || normalized.contains("fragment")) {
            return Optional.of(TelemetryDropReason.RAW_URL);
        }
        if (normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("csrf")
                || normalized.contains("header")
                || normalized.contains("baggage")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("request.body")
                || normalized.contains("response.body")) {
            return Optional.of(TelemetryDropReason.SENSITIVE_KEY);
        }
        return Optional.empty();
    }

    Optional<TelemetryDropReason> classifyValue(String candidate) {
        if (candidate == null) {
            return Optional.of(TelemetryDropReason.INVALID_TYPE);
        }
        if (containsControl(candidate)) {
            return Optional.of(TelemetryDropReason.SENSITIVE_VALUE);
        }
        String normalized = candidate.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("authorization:")
                || normalized.startsWith("authorization ")
                || normalized.startsWith("basic ")
                || normalized.startsWith("bearer ")
                || normalized.contains("cookie=")
                || normalized.contains("session=")
                || normalized.contains("sessionid=")
                || normalized.contains("csrf=")
                || SENSITIVE_NAME.matcher(candidate).find()) {
            return Optional.of(TelemetryDropReason.SENSITIVE_VALUE);
        }
        if (normalized.contains("glpat-")
                || normalized.contains("ghp_")
                || normalized.contains("whsec_")
                || normalized.contains("-----begin ")
                || JWT.matcher(candidate).find()) {
            return Optional.of(TelemetryDropReason.SENSITIVE_VALUE);
        }
        if (URI_SCHEME.matcher(candidate).find()
                || candidate.indexOf('?') >= 0
                || candidate.indexOf('#') >= 0
                || (candidate.startsWith("/")
                        && !TelemetryAttributes.RouteTemplate.isRegistered(candidate))) {
            return Optional.of(TelemetryDropReason.RAW_URL);
        }
        for (String sentinel : sentinels) {
            if (candidate.contains(sentinel)) {
                return Optional.of(TelemetryDropReason.SENSITIVE_VALUE);
            }
        }
        return Optional.empty();
    }

    static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
