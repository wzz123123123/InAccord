package com.inforvans.accord.webhookedge.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GitLabWebhookHeaders {
    static final String WEBHOOK_ID = "webhook-id";
    static final String WEBHOOK_TIMESTAMP = "webhook-timestamp";
    static final String WEBHOOK_SIGNATURE = "webhook-signature";
    static final String IDEMPOTENCY_KEY = "idempotency-key";
    static final String LEGACY_TOKEN = "x-gitlab-token";
    static final String EVENT_TYPE = "x-gitlab-event";
    private static final Set<String> CAPTURED_NAMES = Set.of(
            WEBHOOK_ID,
            WEBHOOK_TIMESTAMP,
            WEBHOOK_SIGNATURE,
            IDEMPOTENCY_KEY,
            LEGACY_TOKEN,
            EVENT_TYPE);
    private static final Set<String> COMMA_FORBIDDEN = Set.of(
            WEBHOOK_ID,
            WEBHOOK_TIMESTAMP,
            IDEMPOTENCY_KEY,
            LEGACY_TOKEN,
            EVENT_TYPE);

    private final Map<String, HeaderValues> headers;

    private GitLabWebhookHeaders(Map<String, HeaderValues> headers) {
        this.headers = Map.copyOf(headers);
    }

    public static GitLabWebhookHeaders from(Map<String, ? extends List<String>> source) {
        Objects.requireNonNull(source, "source");
        Map<String, List<String>> accumulated = new LinkedHashMap<>();
        Set<String> present = new java.util.HashSet<>();
        source.forEach((name, values) -> {
            String normalized = Objects.requireNonNull(name, "header name").toLowerCase(Locale.ROOT);
            if (CAPTURED_NAMES.contains(normalized)) {
                present.add(normalized);
                List<String> target = accumulated.computeIfAbsent(normalized, ignored -> new ArrayList<>());
                if (values != null) {
                    target.addAll(values);
                }
            }
        });
        Map<String, HeaderValues> copied = new LinkedHashMap<>();
        for (String name : present) {
            copied.put(name, new HeaderValues(true, List.copyOf(accumulated.getOrDefault(name, List.of()))));
        }
        return new GitLabWebhookHeaders(copied);
    }

    boolean isPresent(String name) {
        HeaderValues values = headers.get(name);
        return values != null && values.present();
    }

    String singleton(String name) {
        HeaderValues values = headers.get(name);
        return values != null && values.values().size() == 1 ? values.values().get(0) : null;
    }

    boolean hasUnsafeMultiplicityOrValue(String... names) {
        for (String name : names) {
            HeaderValues header = headers.get(name);
            if (header == null) {
                continue;
            }
            List<String> values = header.values();
            if (values.size() != 1) {
                return true;
            }
            String value = values.get(0);
            if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
                return true;
            }
            if (COMMA_FORBIDDEN.contains(name) && value.indexOf(',') >= 0) {
                return true;
            }
        }
        return false;
    }

    private record HeaderValues(boolean present, List<String> values) {}
}
