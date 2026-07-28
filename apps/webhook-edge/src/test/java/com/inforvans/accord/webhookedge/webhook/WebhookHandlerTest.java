package com.inforvans.accord.webhookedge.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inforvans.accord.webhookedge.binding.BindingResolver;
import com.inforvans.accord.webhookedge.binding.WebhookBinding;
import com.inforvans.accord.webhookedge.binding.WebhookVerificationMode;
import com.inforvans.accord.webhookedge.security.GitLabWebhookVerifier;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebhookHandlerTest {
    private static final Instant NOW = Instant.parse("2026-07-26T01:02:03Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID CORRELATION_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000044");
    private static final UUID BINDING_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000011");
    private static final UUID TENANT_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000022");
    private static final UUID DELIVERY_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000033");
    private static final long REPOSITORY_ID = 4_294_967_296L;
    private static final byte[] SECRET = "0123456789abcdefghijklmnopqrstuv".getBytes(StandardCharsets.US_ASCII);
    private static final String SIGNING_TOKEN = "whsec_" + Base64.getEncoder().encodeToString(SECRET);
    private static final String LEGACY_TOKEN = "distinct-legacy-secret-sentinel";
    private static final String BEFORE = "1111111111111111111111111111111111111111";
    private static final String AFTER = "2222222222222222222222222222222222222222";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsExactTwoMiBBodyReadOnceAndRejectsOneByteMoreBeforeResolution() throws Exception {
        byte[] exact = bodyAtSize(WebhookHandler.MAX_BODY_BYTES);
        List<String> exactOrder = new ArrayList<>();
        RecordingInbox inbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, exactOrder);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger verifierCalls = new AtomicInteger();
        AtomicInteger parserCalls = new AtomicInteger();
        WebhookHandler handler = instrumentedHandler(bindingId -> {
            resolutions.incrementAndGet();
            exactOrder.add("resolve");
            return java.util.Optional.of(binding());
        }, inbox, verifierCalls, parserCalls);
        CountingRequest accepted = signedRequest(exact, "application/json");
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();

        handler.receive(BINDING_ID.toString(), accepted, acceptedResponse);

        assertThat(accepted.getInputStreamCalls()).isEqualTo(1);
        assertThat(acceptedResponse.getStatus()).isEqualTo(202);
        assertThat(acceptedResponse.getContentAsByteArray()).isEmpty();
        assertThat(exactOrder).containsExactly("resolve", "store");
        assertThat(verifierCalls).hasValue(1);
        assertThat(parserCalls).hasValue(1);
        assertThat(inbox.calls()).isOne();

        List<String> oversizedOrder = new ArrayList<>();
        RecordingInbox oversizedInbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, oversizedOrder);
        AtomicInteger oversizedVerifierCalls = new AtomicInteger();
        AtomicInteger oversizedParserCalls = new AtomicInteger();
        WebhookHandler oversizedHandler = instrumentedHandler(bindingId -> {
            oversizedOrder.add("resolve");
            return java.util.Optional.of(binding());
        }, oversizedInbox, oversizedVerifierCalls, oversizedParserCalls);
        CountingRequest oversized = signedRequest(append(exact, (byte) ' '), "application/json");
        MockHttpServletResponse oversizedResponse = new MockHttpServletResponse();

        oversizedHandler.receive(BINDING_ID.toString(), oversized, oversizedResponse);

        assertThat(oversized.getInputStreamCalls()).isEqualTo(1);
        assertProblem(oversizedResponse, 413, "WEBHOOK_PAYLOAD_TOO_LARGE");
        assertThat(oversizedOrder).isEmpty();
        assertThat(oversizedVerifierCalls).hasValue(0);
        assertThat(oversizedParserCalls).hasValue(0);
        assertThat(oversizedInbox.calls()).isZero();
        assertThat(resolutions).hasValue(1);
    }

    @Test
    void resolvesOnlyCanonicalServerSideBindingAndConcealsUnknownBindings() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        RecordingInbox inbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>());
        WebhookHandler handler = handler(bindingId -> {
            resolutions.incrementAndGet();
            return java.util.Optional.empty();
        }, inbox);

        MockHttpServletResponse malformed = invoke(handler, BINDING_ID.toString().toUpperCase(), validBody(), "application/json", true);
        MockHttpServletResponse unknown = invoke(handler, UUID.randomUUID().toString(), validBody(), "application/json", true);

        assertProblem(malformed, 401, "WEBHOOK_AUTHENTICATION_FAILED");
        assertProblem(unknown, 401, "WEBHOOK_AUTHENTICATION_FAILED");
        assertThat(resolutions).hasValue(1);
        assertThat(inbox.calls()).isZero();
    }

    @Test
    void rejectsAuthenticationBeforeCheckingMediaTypeOrParsing() throws Exception {
        RecordingInbox invalidInbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>());
        AtomicInteger verifierCalls = new AtomicInteger();
        AtomicInteger parserCalls = new AtomicInteger();
        WebhookHandler invalidHandler = instrumentedHandler(
                fixedResolver(), invalidInbox, verifierCalls, parserCalls);

        MockHttpServletResponse invalidSignature = invoke(
                invalidHandler,
                BINDING_ID.toString(),
                "not-json".getBytes(StandardCharsets.UTF_8),
                "application/json",
                false);

        assertProblem(invalidSignature, 401, "WEBHOOK_AUTHENTICATION_FAILED");
        assertThat(verifierCalls).hasValue(1);
        assertThat(parserCalls).hasValue(0);
        assertThat(invalidInbox.calls()).isZero();

        RecordingInbox wrongMediaInbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>());
        MockHttpServletResponse authenticatedWrongMedia = invoke(
                handler(fixedResolver(), wrongMediaInbox),
                BINDING_ID.toString(),
                validBody(),
                "text/plain",
                true);

        assertProblem(authenticatedWrongMedia, 415, "WEBHOOK_MEDIA_TYPE_UNSUPPORTED");
        assertThat(wrongMediaInbox.calls()).isZero();
    }

    @Test
    void parsesStrictJsonBeforePersistence() throws Exception {
        RecordingInbox inbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>());
        WebhookHandler handler = handler(fixedResolver(), inbox);
        List<String> malformed = List.of(
                "{\"project_id\":" + REPOSITORY_ID + ",\"project_id\":" + REPOSITORY_ID + ",\"project\":{\"id\":" + REPOSITORY_ID + "}}",
                "{\"project_id\":" + REPOSITORY_ID + ",\"project\":{\"id\":" + REPOSITORY_ID + ",\"id\":" + REPOSITORY_ID + "}}",
                "{\"project_id\":" + REPOSITORY_ID + ",\"project\":{\"id\":" + REPOSITORY_ID + "}} true",
                "{\"project_id\":" + REPOSITORY_ID + ",\"project\":{\"id\":" + REPOSITORY_ID + "},\"before\":1}");

        for (String body : malformed) {
            MockHttpServletResponse response = invoke(handler, BINDING_ID.toString(), body.getBytes(StandardCharsets.UTF_8), "application/json", true);
            assertProblem(response, 400, "WEBHOOK_REQUEST_INVALID");
        }
        assertThat(inbox.calls()).isZero();
    }

    @Test
    void distinguishesProjectDisagreementFromAuthenticatedRepositoryMismatch() throws Exception {
        RecordingInbox inbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>());
        WebhookHandler handler = handler(fixedResolver(), inbox);
        byte[] disagreement = jsonBody(REPOSITORY_ID, REPOSITORY_ID + 1, null);
        byte[] mismatch = jsonBody(REPOSITORY_ID + 1, REPOSITORY_ID + 1, null);

        MockHttpServletResponse disagreementResponse = invoke(handler, BINDING_ID.toString(), disagreement, "application/json", true);
        MockHttpServletResponse mismatchResponse = invoke(handler, BINDING_ID.toString(), mismatch, "application/json", true);

        assertProblem(disagreementResponse, 400, "WEBHOOK_REQUEST_INVALID");
        assertProblem(mismatchResponse, 403, "WEBHOOK_REPOSITORY_MISMATCH");
        assertThat(inbox.calls()).isZero();
    }

    @Test
    void rejectsPhysicalIdentitySmuggling() throws Exception {
        RecordingInbox inbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>());
        WebhookHandler handler = handler(fixedResolver(), inbox);
        CountingRequest request = signedRequest(validBody(), "application/json");
        request.addHeader("webhook-id", DELIVERY_ID.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.receive(BINDING_ID.toString(), request, response);

        assertProblem(response, 401, "WEBHOOK_AUTHENTICATION_FAILED");
        assertThat(inbox.calls()).isZero();
    }

    @Test
    void persistsOnlyTheClosedNormalizedFieldSetAndEmitsNoSensitiveLogs() throws Exception {
        String urlSentinel = "https://secret-host.invalid/private/repository";
        String messageSentinel = "distinct-commit-message-sentinel";
        String bodyTokenSentinel = "distinct-body-token-sentinel";
        String extra = ",\"repository\":{\"url\":\"" + urlSentinel + "\"}"
                + ",\"commits\":[{\"message\":\"" + messageSentinel + "\",\"author\":{\"name\":\"private-user\"}}]"
                + ",\"user_name\":\"private-user\",\"token\":\"" + bodyTokenSentinel + "\""
                + ",\"headers\":{\"authorization\":\"private-header\"},\"raw_body\":\"private-raw-body\"";
        byte[] body = jsonBody(REPOSITORY_ID, REPOSITORY_ID, extra);
        RecordingInbox inbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>());
        WebhookHandler handler = handler(fixedResolver(), inbox);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        MockHttpServletResponse response;
        try {
            response = invoke(handler, BINDING_ID.toString(), body, "application/json", true);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(response.getStatus()).isEqualTo(202);
        ProviderWebhookSignal signal = inbox.lastSignal();
        JsonNode normalized = JSON.valueToTree(signal);
        assertThat(toSet(normalized.fieldNames())).containsExactlyInAnyOrder(
                "schema_version",
                "tenant_id",
                "scope_type",
                "scope_id",
                "provider",
                "immutable_repository_id",
                "delivery_id",
                "event_type",
                "body_digest",
                "observed_at",
                "ref",
                "before_sha",
                "after_sha");
        assertThat(normalized.path("schema_version").asText()).isEqualTo("1.0.0");
        assertThat(normalized.path("tenant_id").asText()).isEqualTo(TENANT_ID.toString());
        assertThat(normalized.path("scope_type").asText()).isEqualTo("repository");
        assertThat(normalized.path("scope_id").asText()).isEqualTo(BINDING_ID.toString());
        assertThat(normalized.path("provider").asText()).isEqualTo("gitlab");
        assertThat(normalized.path("immutable_repository_id").asLong()).isEqualTo(REPOSITORY_ID);
        assertThat(normalized.path("delivery_id").asText()).isEqualTo(DELIVERY_ID.toString());
        assertThat(normalized.path("event_type").asText()).isEqualTo("Push Hook");
        assertThat(normalized.path("body_digest").asText()).isEqualTo("sha256:" + sha256(body));
        assertThat(normalized.path("observed_at").asText()).isEqualTo(NOW.toString());
        assertThat(normalized.path("ref").asText()).isEqualTo("refs/heads/main");
        assertThat(normalized.path("before_sha").asText()).isEqualTo(BEFORE);
        assertThat(normalized.path("after_sha").asText()).isEqualTo(AFTER);

        String retained = normalized.toString();
        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(retained + logs)
                .doesNotContain(urlSentinel, messageSentinel, bodyTokenSentinel, "private-user", "private-header", "private-raw-body")
                .doesNotContain(SIGNING_TOKEN, Base64.getEncoder().encodeToString(SECRET), LEGACY_TOKEN);
    }

    @Test
    void rejectedRequestsEmitNoSensitiveLogs() throws Exception {
        String urlSentinel = "https://rejected-secret.invalid/private/repository";
        String messageSentinel = "rejected-commit-message-sentinel";
        String authorSentinel = "rejected-author-sentinel";
        String bodyTokenSentinel = "rejected-body-token-sentinel";
        String headerMapSentinel = "rejected-header-map-sentinel";
        String rawBodySentinel = "rejected-raw-body-sentinel";
        String extra = ",\"repository\":{\"url\":\"" + urlSentinel + "\"}"
                + ",\"commits\":[{\"message\":\"" + messageSentinel + "\",\"author\":{\"name\":\"" + authorSentinel + "\"}}]"
                + ",\"token\":\"" + bodyTokenSentinel + "\""
                + ",\"headers\":{\"authorization\":\"" + headerMapSentinel + "\"}"
                + ",\"raw_body\":\"" + rawBodySentinel + "\"";
        byte[] sensitiveBody = jsonBody(REPOSITORY_ID, REPOSITORY_ID, extra);
        byte[] malformedBody = ("{\"raw_body\":\"" + rawBodySentinel + "\"")
                .getBytes(StandardCharsets.UTF_8);
        String rejectedSignature = Base64.getEncoder().encodeToString(new byte[32]);
        List<MockHttpServletResponse> responses = new ArrayList<>();

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            CountingRequest authenticationRequest = signedRequest(sensitiveBody, "application/json");
            authenticationRequest.removeHeader("webhook-signature");
            authenticationRequest.addHeader("webhook-signature", "v1," + rejectedSignature);
            authenticationRequest.addHeader("X-Gitlab-Token", LEGACY_TOKEN);
            responses.add(invoke(handler(fixedResolver(), new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>())),
                    authenticationRequest));

            responses.add(invoke(
                    handler(fixedResolver(), new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>())),
                    BINDING_ID.toString(),
                    malformedBody,
                    "application/json",
                    true));
            responses.add(invoke(
                    handler(fixedResolver(), new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>())),
                    BINDING_ID.toString(),
                    jsonBody(REPOSITORY_ID + 1, REPOSITORY_ID + 1, extra),
                    "application/json",
                    true));
            responses.add(invoke(
                    handler(fixedResolver(), new RecordingInbox(WebhookRecordOutcome.DIGEST_CONFLICT, new ArrayList<>())),
                    BINDING_ID.toString(),
                    sensitiveBody,
                    "application/json",
                    true));
            responses.add(invoke(
                    handler(bindingId -> {
                        throw new BindingResolver.UnavailableException();
                    }, new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>())),
                    BINDING_ID.toString(),
                    sensitiveBody,
                    "application/json",
                    true));
            RecordingInbox unavailableInbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>());
            unavailableInbox.failUnavailable();
            responses.add(invoke(
                    handler(fixedResolver(), unavailableInbox),
                    BINDING_ID.toString(),
                    sensitiveBody,
                    "application/json",
                    true));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertProblem(responses.get(0), 401, "WEBHOOK_AUTHENTICATION_FAILED");
        assertProblem(responses.get(1), 400, "WEBHOOK_REQUEST_INVALID");
        assertProblem(responses.get(2), 403, "WEBHOOK_REPOSITORY_MISMATCH");
        assertProblem(responses.get(3), 409, "WEBHOOK_DIGEST_CONFLICT");
        assertProblem(responses.get(4), 503, "WEBHOOK_EDGE_UNAVAILABLE");
        assertProblem(responses.get(5), 503, "WEBHOOK_EDGE_UNAVAILABLE");

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        String problemBodies = responses.stream()
                .map(response -> new String(response.getContentAsByteArray(), StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertThat(logs + problemBodies)
                .doesNotContain(
                        urlSentinel,
                        messageSentinel,
                        authorSentinel,
                        bodyTokenSentinel,
                        headerMapSentinel,
                        rawBodySentinel,
                        rejectedSignature)
                .doesNotContain(SIGNING_TOKEN, Base64.getEncoder().encodeToString(SECRET), LEGACY_TOKEN);
    }

    @Test
    void mapsInboxOutcomesWithoutMutatingTheHttpContract() throws Exception {
        for (WebhookRecordOutcome outcome : List.of(WebhookRecordOutcome.RECORDED, WebhookRecordOutcome.IDENTICAL_RETRY)) {
            RecordingInbox inbox = new RecordingInbox(outcome, new ArrayList<>());
            MockHttpServletResponse response = invoke(handler(fixedResolver(), inbox), BINDING_ID.toString(), validBody(), "application/json", true);
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(response.getContentAsByteArray()).isEmpty();
            assertThat(inbox.calls()).isOne();
        }

        RecordingInbox conflicting = new RecordingInbox(WebhookRecordOutcome.DIGEST_CONFLICT, new ArrayList<>());
        MockHttpServletResponse conflict = invoke(handler(fixedResolver(), conflicting), BINDING_ID.toString(), validBody(), "application/json", true);
        assertProblem(conflict, 409, "WEBHOOK_DIGEST_CONFLICT");
        assertThat(conflicting.calls()).isOne();
    }

    @Test
    void mapsBindingAndDatabaseUnavailabilityToSanitizedServiceUnavailable() throws Exception {
        WebhookHandler bindingUnavailable = handler(bindingId -> {
            throw new BindingResolver.UnavailableException();
        }, new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>()));
        RecordingInbox unavailableInbox = new RecordingInbox(WebhookRecordOutcome.RECORDED, new ArrayList<>());
        unavailableInbox.failUnavailable();

        MockHttpServletResponse bindingResponse = invoke(bindingUnavailable, BINDING_ID.toString(), validBody(), "application/json", true);
        MockHttpServletResponse databaseResponse = invoke(handler(fixedResolver(), unavailableInbox), BINDING_ID.toString(), validBody(), "application/json", true);

        assertProblem(bindingResponse, 503, "WEBHOOK_EDGE_UNAVAILABLE");
        assertProblem(databaseResponse, 503, "WEBHOOK_EDGE_UNAVAILABLE");
    }

    private static WebhookHandler handler(BindingResolver resolver, WebhookInbox inbox) {
        return new WebhookHandler(resolver, new GitLabWebhookVerifier(CLOCK), inbox, () -> CORRELATION_ID);
    }

    private static WebhookHandler instrumentedHandler(
            BindingResolver resolver,
            WebhookInbox inbox,
            AtomicInteger verifierCalls,
            AtomicInteger parserCalls) {
        GitLabWebhookVerifier delegate = new GitLabWebhookVerifier(CLOCK);
        return new WebhookHandler(
                resolver,
                (binding, headers, body) -> {
                    verifierCalls.incrementAndGet();
                    return delegate.verify(binding, headers, body);
                },
                body -> {
                    parserCalls.incrementAndGet();
                    return JSON.readTree(body);
                },
                inbox,
                () -> CORRELATION_ID);
    }

    private static BindingResolver fixedResolver() {
        return bindingId -> java.util.Optional.of(binding());
    }

    private static WebhookBinding binding() {
        return new WebhookBinding(
                BINDING_ID,
                TENANT_ID,
                REPOSITORY_ID,
                WebhookVerificationMode.STANDARD_REQUIRED,
                SIGNING_TOKEN,
                null);
    }

    private static MockHttpServletResponse invoke(
            WebhookHandler handler,
            String bindingId,
            byte[] body,
            String contentType,
            boolean validSignature) throws Exception {
        CountingRequest request = signedRequest(body, contentType);
        if (!validSignature) {
            request.removeHeader("webhook-signature");
            request.addHeader("webhook-signature", "v1," + Base64.getEncoder().encodeToString(new byte[32]));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.receive(bindingId, request, response);
        return response;
    }

    private static MockHttpServletResponse invoke(WebhookHandler handler, CountingRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.receive(BINDING_ID.toString(), request, response);
        return response;
    }

    private static CountingRequest signedRequest(byte[] body, String contentType) {
        CountingRequest request = new CountingRequest();
        request.setMethod("POST");
        request.setRequestURI("/webhooks/gitlab/" + BINDING_ID);
        request.setContent(body);
        request.setContentType(contentType);
        request.addHeader("webhook-id", DELIVERY_ID.toString());
        request.addHeader("Idempotency-Key", DELIVERY_ID.toString());
        request.addHeader("webhook-timestamp", Long.toString(NOW.getEpochSecond()));
        request.addHeader("webhook-signature", "v1," + Base64.getEncoder().encodeToString(signature(body)));
        request.addHeader("X-Gitlab-Event", "Push Hook");
        return request;
    }

    private static byte[] validBody() {
        return jsonBody(REPOSITORY_ID, REPOSITORY_ID, null);
    }

    private static byte[] jsonBody(long projectId, long nestedProjectId, String extraFields) {
        String extra = extraFields == null ? "" : extraFields;
        return ("{\"project_id\":" + projectId
                        + ",\"project\":{\"id\":" + nestedProjectId + "}"
                        + ",\"ref\":\"refs/heads/main\""
                        + ",\"before\":\"" + BEFORE + "\""
                        + ",\"after\":\"" + AFTER + "\""
                        + extra
                        + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bodyAtSize(int size) {
        String prefix = "{\"project_id\":" + REPOSITORY_ID
                + ",\"project\":{\"id\":" + REPOSITORY_ID + "}"
                + ",\"ref\":\"refs/heads/main\",\"before\":\"" + BEFORE + "\",\"after\":\"" + AFTER + "\",\"padding\":\"";
        String suffix = "\"}";
        int padding = size - prefix.length() - suffix.length();
        return (prefix + "x".repeat(padding) + suffix).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] append(byte[] source, byte value) {
        byte[] result = java.util.Arrays.copyOf(source, source.length + 1);
        result[source.length] = value;
        return result;
    }

    private static byte[] signature(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            mac.update(DELIVERY_ID.toString().getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(Long.toString(NOW.getEpochSecond()).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Set<String> toSet(java.util.Iterator<String> values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEachRemaining(result::add);
        return result;
    }

    private static void assertProblem(
            MockHttpServletResponse response, int expectedStatus, String expectedCode) throws Exception {
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode problem = JSON.readTree(response.getContentAsByteArray());
        assertThat(toSet(problem.fieldNames())).containsExactlyInAnyOrder(
                "type", "title", "status", "code", "correlation_id", "instance");
        assertThat(problem.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(problem.path("code").asText()).isEqualTo(expectedCode);
        assertThat(problem.path("correlation_id").asText()).isEqualTo(CORRELATION_ID.toString());
        assertThat(problem.path("instance").asText()).isEqualTo("/webhooks/gitlab");
        assertThat(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8))
                .doesNotContain(BINDING_ID.toString(), SIGNING_TOKEN, LEGACY_TOKEN, "detail", "exception");
    }

    private static final class CountingRequest extends MockHttpServletRequest {
        private int inputStreamCalls;

        @Override
        public ServletInputStream getInputStream() {
            inputStreamCalls++;
            return super.getInputStream();
        }

        int getInputStreamCalls() {
            return inputStreamCalls;
        }
    }

    private static final class RecordingInbox implements WebhookInbox {
        private final WebhookRecordOutcome outcome;
        private final List<String> order;
        private int calls;
        private ProviderWebhookSignal lastSignal;
        private boolean unavailable;

        private RecordingInbox(WebhookRecordOutcome outcome, List<String> order) {
            this.outcome = outcome;
            this.order = order;
        }

        @Override
        public WebhookRecordOutcome record(ProviderWebhookSignal signal) {
            calls++;
            order.add("store");
            lastSignal = signal;
            if (unavailable) {
                throw new WebhookInbox.UnavailableException();
            }
            return outcome;
        }

        int calls() {
            return calls;
        }

        ProviderWebhookSignal lastSignal() {
            return lastSignal;
        }

        void failUnavailable() {
            unavailable = true;
        }
    }
}
