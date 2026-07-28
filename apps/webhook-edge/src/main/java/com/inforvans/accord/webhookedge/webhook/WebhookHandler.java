package com.inforvans.accord.webhookedge.webhook;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inforvans.accord.webhookedge.binding.BindingResolver;
import com.inforvans.accord.webhookedge.binding.WebhookBinding;
import com.inforvans.accord.webhookedge.security.GitLabWebhookHeaders;
import com.inforvans.accord.webhookedge.security.GitLabWebhookVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class WebhookHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookHandler.class);
    public static final int MAX_BODY_BYTES = 2 * 1_024 * 1_024;
    private static final String PROBLEM_INSTANCE = "/webhooks/gitlab";
    private static final ObjectMapper REQUEST_JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final BindingResolver bindingResolver;
    private final VerificationStep verifier;
    private final ParsingStep parser;
    private final WebhookInbox inbox;
    private final Supplier<UUID> correlationIds;

    @Autowired
    public WebhookHandler(
            BindingResolver bindingResolver,
            GitLabWebhookVerifier verifier,
            WebhookInbox inbox) {
        this(bindingResolver, verifier, inbox, UUID::randomUUID);
    }

    WebhookHandler(
            BindingResolver bindingResolver,
            GitLabWebhookVerifier verifier,
            WebhookInbox inbox,
            Supplier<UUID> correlationIds) {
        this(
                bindingResolver,
                Objects.requireNonNull(verifier, "verifier")::verify,
                REQUEST_JSON::readTree,
                inbox,
                correlationIds);
    }

    WebhookHandler(
            BindingResolver bindingResolver,
            VerificationStep verifier,
            ParsingStep parser,
            WebhookInbox inbox,
            Supplier<UUID> correlationIds) {
        this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.correlationIds = Objects.requireNonNull(correlationIds, "correlationIds");
    }

    @PostMapping("/webhooks/gitlab/{bindingId}")
    public void receive(
            @PathVariable String bindingId,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException exception) {
            writeProblem(response, Problem.EDGE_UNAVAILABLE);
            return;
        }
        if (body.length > MAX_BODY_BYTES) {
            writeProblem(response, Problem.PAYLOAD_TOO_LARGE);
            return;
        }

        UUID canonicalBindingId = canonicalUuid(bindingId);
        if (canonicalBindingId == null) {
            writeProblem(response, Problem.AUTHENTICATION_FAILED);
            return;
        }

        Optional<WebhookBinding> resolved;
        try {
            resolved = bindingResolver.resolve(canonicalBindingId);
        } catch (BindingResolver.UnavailableException exception) {
            writeProblem(response, Problem.EDGE_UNAVAILABLE);
            return;
        }
        if (resolved.isEmpty()) {
            writeProblem(response, Problem.AUTHENTICATION_FAILED);
            return;
        }

        GitLabWebhookHeaders headers;
        try {
            headers = captureHeaders(request);
        } catch (RuntimeException exception) {
            writeProblem(response, Problem.REQUEST_INVALID);
            return;
        }
        VerifiedGitLabWebhook verified;
        try {
            verified = verifier.verify(resolved.orElseThrow(), headers, body);
        } catch (GitLabWebhookVerifier.VerificationException exception) {
            Problem problem = exception.failure() == GitLabWebhookVerifier.Failure.AUTHENTICATION
                    ? Problem.AUTHENTICATION_FAILED
                    : Problem.REQUEST_INVALID;
            writeProblem(response, problem);
            return;
        }

        if (!isSupportedJson(request.getContentType())) {
            writeProblem(response, Problem.MEDIA_TYPE_UNSUPPORTED);
            return;
        }

        ProviderWebhookSignal signal;
        try {
            signal = normalize(verified, body);
        } catch (ProjectDisagreementException exception) {
            writeProblem(response, Problem.REQUEST_INVALID);
            return;
        } catch (RepositoryMismatchException exception) {
            writeProblem(response, Problem.REPOSITORY_MISMATCH);
            return;
        } catch (IOException | IllegalArgumentException exception) {
            writeProblem(response, Problem.REQUEST_INVALID);
            return;
        }

        WebhookRecordOutcome outcome;
        try {
            outcome = inbox.record(signal);
        } catch (WebhookInbox.UnavailableException exception) {
            writeProblem(response, Problem.EDGE_UNAVAILABLE);
            return;
        }
        if (outcome == WebhookRecordOutcome.DIGEST_CONFLICT) {
            LOGGER.info("webhook_request_digest_conflict");
            writeProblem(response, Problem.DIGEST_CONFLICT);
            return;
        }
        if (outcome == null) {
            writeProblem(response, Problem.EDGE_UNAVAILABLE);
            return;
        }
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        LOGGER.info("webhook_request_accepted");
    }

    private static GitLabWebhookHeaders captureHeaders(HttpServletRequest request) {
        Map<String, List<String>> captured = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return GitLabWebhookHeaders.from(captured);
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            List<String> values = new ArrayList<>();
            Enumeration<String> headerValues = request.getHeaders(name);
            if (headerValues != null) {
                headerValues.asIterator().forEachRemaining(values::add);
            }
            captured.computeIfAbsent(name, ignored -> new ArrayList<>()).addAll(values);
        }
        return GitLabWebhookHeaders.from(captured);
    }

    private static boolean isSupportedJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            if (!MediaType.APPLICATION_JSON.includes(mediaType)) {
                return false;
            }
            return mediaType.getCharset() == null || StandardCharsets.UTF_8.equals(mediaType.getCharset());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private ProviderWebhookSignal normalize(VerifiedGitLabWebhook verified, byte[] body)
            throws IOException {
        JsonNode root = parser.parse(body);
        if (root == null || !root.isObject()) {
            throw new IOException("object required");
        }
        long topLevelProjectId = requiredPositiveLong(root.get("project_id"));
        JsonNode project = root.get("project");
        if (project == null || !project.isObject()) {
            throw new IOException("project object required");
        }
        long nestedProjectId = requiredPositiveLong(project.get("id"));
        if (topLevelProjectId != nestedProjectId) {
            throw new ProjectDisagreementException();
        }
        WebhookBinding binding = verified.binding();
        if (topLevelProjectId != binding.immutableRepositoryId()) {
            throw new RepositoryMismatchException();
        }

        return new ProviderWebhookSignal(
                "1.0.0",
                binding.tenantId(),
                "repository",
                binding.bindingId(),
                "gitlab",
                binding.immutableRepositoryId(),
                verified.deliveryId(),
                verified.eventType(),
                verified.bodyDigest(),
                verified.observedAt().toString(),
                optionalText(root, "ref"),
                optionalText(root, "before"),
                optionalText(root, "after"));
    }

    private static long requiredPositiveLong(JsonNode value) throws IOException {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IOException("positive integer required");
        }
        return value.longValue();
    }

    private static String optionalText(JsonNode object, String field) throws IOException {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IOException("text value required");
        }
        return value.textValue();
    }

    private static UUID canonicalUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void writeProblem(HttpServletResponse response, Problem problem) throws IOException {
        UUID correlationId = correlationIds.get();
        String json = "{"
                + "\"type\":\"" + problem.type + "\","
                + "\"title\":\"" + problem.title + "\","
                + "\"status\":" + problem.status + ","
                + "\"code\":\"" + problem.code + "\","
                + "\"correlation_id\":\"" + correlationId + "\","
                + "\"instance\":\"" + PROBLEM_INSTANCE + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.reset();
        response.setStatus(problem.status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private enum Problem {
        PAYLOAD_TOO_LARGE(
                413,
                "WEBHOOK_PAYLOAD_TOO_LARGE",
                "Webhook payload too large",
                "https://problems.accord.inforvans.com/webhook/payload-too-large"),
        AUTHENTICATION_FAILED(
                401,
                "WEBHOOK_AUTHENTICATION_FAILED",
                "Webhook authentication failed",
                "https://problems.accord.inforvans.com/webhook/authentication-failed"),
        REQUEST_INVALID(
                400,
                "WEBHOOK_REQUEST_INVALID",
                "Webhook request invalid",
                "https://problems.accord.inforvans.com/webhook/request-invalid"),
        REPOSITORY_MISMATCH(
                403,
                "WEBHOOK_REPOSITORY_MISMATCH",
                "Webhook repository mismatch",
                "https://problems.accord.inforvans.com/webhook/repository-mismatch"),
        DIGEST_CONFLICT(
                409,
                "WEBHOOK_DIGEST_CONFLICT",
                "Webhook digest conflict",
                "https://problems.accord.inforvans.com/webhook/digest-conflict"),
        MEDIA_TYPE_UNSUPPORTED(
                415,
                "WEBHOOK_MEDIA_TYPE_UNSUPPORTED",
                "Webhook media type unsupported",
                "https://problems.accord.inforvans.com/webhook/media-type-unsupported"),
        EDGE_UNAVAILABLE(
                503,
                "WEBHOOK_EDGE_UNAVAILABLE",
                "Webhook edge unavailable",
                "https://problems.accord.inforvans.com/webhook/edge-unavailable");

        private final int status;
        private final String code;
        private final String title;
        private final String type;

        Problem(int status, String code, String title, String type) {
            this.status = status;
            this.code = code;
            this.title = title;
            this.type = type;
        }
    }

    private static final class ProjectDisagreementException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
    }

    private static final class RepositoryMismatchException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
    }

    @FunctionalInterface
    interface VerificationStep {
        VerifiedGitLabWebhook verify(
                WebhookBinding binding,
                GitLabWebhookHeaders headers,
                byte[] body);
    }

    @FunctionalInterface
    interface ParsingStep {
        JsonNode parse(byte[] body) throws IOException;
    }
}
