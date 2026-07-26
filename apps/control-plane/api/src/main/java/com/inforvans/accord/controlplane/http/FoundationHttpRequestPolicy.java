package com.inforvans.accord.controlplane.http;

import com.inforvans.accord.reliability.ExpectedVersion;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FoundationHttpRequestPolicy {
    static final String CORRELATION_HEADER = "X-Correlation-ID";
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String EXPECTED_VERSION_HEADER = "If-Match";
    static final String CSRF_HEADER = "X-CSRF-Token";
    static final String CORRELATION_ATTRIBUTE =
        FoundationHttpRequestPolicy.class.getName() + ".correlationId";
    static final int MAX_RAW_BODY_BYTES = 1_048_576;

    private static final Pattern IDEMPOTENCY_KEY =
        Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern EXPECTED_VERSION =
        Pattern.compile("^\"(0|[1-9][0-9]*)\"$");
    private static final Pattern TOKEN =
        Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Pattern QUALITY =
        Pattern.compile("^(?:0(?:[.][0-9]{1,3})?|1(?:[.]0{1,3})?)$");

    ParsedRequest parse(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        FoundationHttpSecurity.applicationRelativePath(request);
        String requestTarget = request.getRequestURI();
        List<String> correlations = visibleValues(request, CORRELATION_HEADER);
        if (correlations.size() > 1) {
            throw invalid(CORRELATION_HEADER + " must have at most one visible value");
        }
        UUID correlationId = correlations.isEmpty()
            ? UUID.randomUUID()
            : canonicalUuid(correlations.getFirst());
        request.setAttribute(CORRELATION_ATTRIBUTE, correlationId);

        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            throw invalid("query must be empty");
        }

        List<String> contentTypes = visibleValues(request, "Content-Type");
        List<String> idempotencyKeys = visibleValues(request, IDEMPOTENCY_HEADER);
        List<String> expectedVersions = visibleValues(request, EXPECTED_VERSION_HEADER);
        requireAtMostOne(contentTypes, "Content-Type");
        requireAtMostOne(idempotencyKeys, IDEMPOTENCY_HEADER);
        requireAtMostOne(expectedVersions, EXPECTED_VERSION_HEADER);

        String requestMediaType = requireContentType(contentTypes);
        String normalizedAccept = normalizeAccept(visibleValues(request, "Accept"));
        String idempotencyKey = requireIdempotencyKey(idempotencyKeys);
        ExpectedVersion expectedVersion = requireExpectedVersion(expectedVersions);
        return new ParsedRequest(
            correlationId,
            requestTarget,
            new FoundationMutationHeaders(
                idempotencyKey, expectedVersion, requestMediaType, normalizedAccept));
    }

    static List<String> visibleValues(HttpServletRequest request, String name) {
        Enumeration<String> enumeration = request.getHeaders(name);
        if (enumeration == null) {
            return List.of();
        }
        return List.copyOf(Collections.list(enumeration));
    }

    private static UUID canonicalUuid(String value) {
        return canonicalUuid(value, CORRELATION_HEADER);
    }

    static UUID canonicalValidationId(String value) {
        return canonicalUuid(value, "validationId");
    }

    private static UUID canonicalUuid(String value, String name) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw invalid(name + " must be a canonical UUID");
        }
        if (!parsed.toString().equals(value)) {
            throw invalid(name + " must be a canonical UUID");
        }
        return parsed;
    }

    private static void requireAtMostOne(List<String> values, String name) {
        if (values.size() > 1) {
            throw invalid(name + " must have at most one visible value");
        }
    }

    private static String requireContentType(List<String> values) {
        if (values.isEmpty() || !"application/json".equalsIgnoreCase(values.getFirst())) {
            throw new PolicyFailure(
                415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
        }
        return "application/json";
    }

    private static String requireIdempotencyKey(List<String> values) {
        if (values.isEmpty() || !IDEMPOTENCY_KEY.matcher(values.getFirst()).matches()) {
            throw invalid(IDEMPOTENCY_HEADER + " is missing or invalid");
        }
        return values.getFirst();
    }

    private static ExpectedVersion requireExpectedVersion(List<String> values) {
        if (values.isEmpty()) {
            throw invalid(EXPECTED_VERSION_HEADER + " is missing or invalid");
        }
        Matcher matcher = EXPECTED_VERSION.matcher(values.getFirst());
        if (!matcher.matches()) {
            throw invalid(EXPECTED_VERSION_HEADER + " is missing or invalid");
        }
        try {
            return new ExpectedVersion(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException error) {
            throw invalid(EXPECTED_VERSION_HEADER + " is missing or invalid");
        }
    }

    private static String normalizeAccept(List<String> values) {
        if (values.isEmpty()) {
            return "application/json";
        }
        long visibleBytes = 0;
        for (String value : values) {
            visibleBytes += strictUtf8Length(value);
            if (visibleBytes > 2_048) {
                throw invalid("Accept exceeds 2048 UTF-8 bytes");
            }
        }
        String combined = String.join(",", values);
        String[] parts = combined.split(",", -1);
        if (parts.length > 16) {
            throw invalid("Accept exceeds 16 media ranges");
        }

        Map<String, Integer> qualities = new HashMap<>();
        for (String part : parts) {
            MediaRange mediaRange = parseMediaRange(part);
            if (qualities.putIfAbsent(mediaRange.range(), mediaRange.quality()) != null) {
                throw invalid("Accept contains a duplicate media range");
            }
        }

        Integer selected = qualities.get("application/json");
        if (selected == null) {
            selected = qualities.get("application/*");
        }
        if (selected == null) {
            selected = qualities.get("*/*");
        }
        if (selected == null || selected == 0) {
            throw new PolicyFailure(
                406, "NOT_ACCEPTABLE", "Accept does not select application/json");
        }

        List<String> canonical = new ArrayList<>();
        qualities.forEach((range, quality) -> canonical.add(
            quality == 1_000 ? range : range + ";q=" + canonicalQuality(quality)));
        canonical.sort(Comparator.naturalOrder());
        return String.join(",", canonical);
    }

    private static MediaRange parseMediaRange(String raw) {
        String[] sections = raw.trim().split(";", -1);
        if (sections.length < 1 || sections.length > 2 || sections[0].isEmpty()) {
            throw invalid("Accept contains malformed syntax");
        }
        String[] typeParts = sections[0].trim().split("/", -1);
        if (typeParts.length != 2
                || !validMediaToken(typeParts[0])
                || !validMediaToken(typeParts[1])
                || ("*".equals(typeParts[0]) && !"*".equals(typeParts[1]))) {
            throw invalid("Accept contains malformed syntax");
        }
        String range = typeParts[0].toLowerCase(Locale.ROOT)
            + "/" + typeParts[1].toLowerCase(Locale.ROOT);
        int quality = 1_000;
        if (sections.length == 2) {
            String parameter = sections[1].trim();
            int equals = parameter.indexOf('=');
            if (equals < 1 || parameter.indexOf('=', equals + 1) >= 0
                    || !"q".equalsIgnoreCase(parameter.substring(0, equals).trim())) {
                throw invalid("Accept contains malformed parameters");
            }
            String rawQuality = parameter.substring(equals + 1).trim();
            if (!QUALITY.matcher(rawQuality).matches()) {
                throw invalid("Accept contains malformed quality");
            }
            quality = qualityThousandths(rawQuality);
        }
        return new MediaRange(range, quality);
    }

    private static boolean validMediaToken(String token) {
        return "*".equals(token) || TOKEN.matcher(token).matches();
    }

    private static int qualityThousandths(String quality) {
        if (quality.charAt(0) == '1') {
            return 1_000;
        }
        int dot = quality.indexOf('.');
        if (dot < 0) {
            return 0;
        }
        String fraction = quality.substring(dot + 1);
        return Integer.parseInt(fraction + "0".repeat(3 - fraction.length()));
    }

    private static String canonicalQuality(int quality) {
        if (quality == 0) {
            return "0";
        }
        String digits = String.format(Locale.ROOT, "%03d", quality);
        int last = digits.length();
        while (last > 1 && digits.charAt(last - 1) == '0') {
            last--;
        }
        return "0." + digits.substring(0, last);
    }

    private static int strictUtf8Length(String value) {
        try {
            return StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
                .remaining();
        } catch (CharacterCodingException error) {
            throw invalid("Accept is not valid UTF-8");
        }
    }

    private static PolicyFailure invalid(String message) {
        return new PolicyFailure(400, "REQUEST_INVALID", message);
    }

    record ParsedRequest(
            UUID correlationId,
            String requestTarget,
            FoundationMutationHeaders headers) {
        ParsedRequest {
            Objects.requireNonNull(correlationId, "correlationId");
            if (requestTarget == null
                    || requestTarget.isEmpty()
                    || requestTarget.charAt(0) != '/'
                    || requestTarget.indexOf('?') >= 0
                    || requestTarget.indexOf('#') >= 0) {
                throw new IllegalArgumentException(
                    "requestTarget must be an absolute path without query or fragment");
            }
            Objects.requireNonNull(headers, "headers");
        }

        String idempotencyKey() {
            return headers.idempotencyKey();
        }

        ExpectedVersion expectedVersion() {
            return headers.expectedVersion();
        }

        String requestMediaType() {
            return headers.requestMediaType();
        }

        String normalizedAccept() {
            return headers.normalizedAccept();
        }
    }

    private record MediaRange(String range, int quality) {}

    static final class PolicyFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int status;
        private final String code;

        PolicyFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = Objects.requireNonNull(code, "code");
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }
    }
}

record FoundationMutationHeaders(
        String idempotencyKey,
        ExpectedVersion expectedVersion,
        String requestMediaType,
        String normalizedAccept) {
    FoundationMutationHeaders {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        Objects.requireNonNull(requestMediaType, "requestMediaType");
        Objects.requireNonNull(normalizedAccept, "normalizedAccept");
    }
}
