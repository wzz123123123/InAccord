package com.inforvans.accord.controlplane.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inforvans.accord.platformkernel.CanonicalJson;
import com.inforvans.accord.reliability.StoredHttpResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ContractValidationController.class)
final class ProblemAdvice {
    private final FoundationProblemFactory problems;

    ProblemAdvice(FoundationProblemFactory problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(FoundationHttpRequestPolicy.PolicyFailure.class)
    ResponseEntity<byte[]> policyFailure(
            FoundationHttpRequestPolicy.PolicyFailure failure,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        StoredHttpResult result = switch (failure.code()) {
            case "REQUEST_INVALID" ->
                problems.requestInvalid(correlationId, request.getRequestURI());
            case "NOT_ACCEPTABLE" ->
                problems.notAcceptable(correlationId, request.getRequestURI());
            case "UNSUPPORTED_MEDIA_TYPE" ->
                problems.unsupportedMediaType(correlationId, request.getRequestURI());
            default -> throw new IllegalStateException("unknown request policy failure code");
        };
        return response(result);
    }

    @ExceptionHandler(ContractValidationJson.JsonFailure.class)
    ResponseEntity<byte[]> jsonFailure(
            ContractValidationJson.JsonFailure failure,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        StoredHttpResult result = switch (failure.kind()) {
            case REQUEST_INVALID ->
                problems.requestInvalid(correlationId, request.getRequestURI());
            case JSON_INVALID ->
                problems.jsonInvalid(correlationId, request.getRequestURI());
        };
        return response(result);
    }

    static ResponseEntity<byte[]> response(StoredHttpResult result) {
        byte[] body = FoundationHttpResponseWriter.strictUtf8(result.body());
        HttpHeaders headers = new HttpHeaders();
        result.headers().forEach(headers::set);
        return new ResponseEntity<>(
            body,
            headers,
            HttpStatusCode.valueOf(result.status()));
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object accepted = request.getAttribute(
            FoundationHttpRequestPolicy.CORRELATION_ATTRIBUTE);
        return accepted instanceof UUID correlationId ? correlationId : UUID.randomUUID();
    }
}

final class FoundationProblemFactory {
    private static final String PROBLEM_TYPE_PREFIX =
        "https://problems.accord.inforvans.com/";
    private static final Map<String, String> PROBLEM_HEADERS =
        Map.of("content-type", "application/problem+json");
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final Consumer<ObjectNode> NO_EXTENSIONS = ignored -> {};

    private final ObjectMapper mapper;

    FoundationProblemFactory() {
        mapper = JsonMapper.builder().build();
    }

    StoredHttpResult requestInvalid(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "REQUEST_INVALID",
            "request-invalid",
            "Request is invalid",
            400,
            NO_EXTENSIONS);
    }

    StoredHttpResult jsonInvalid(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "JSON_INVALID",
            "json-invalid",
            "JSON document is invalid",
            400,
            NO_EXTENSIONS);
    }

    StoredHttpResult authenticationRequired(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "AUTHENTICATION_REQUIRED",
            "authentication-required",
            "Authentication is required",
            401,
            NO_EXTENSIONS);
    }

    StoredHttpResult authorizationDenied(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "AUTHORIZATION_DENIED",
            "authorization-denied",
            "Authorization is denied",
            403,
            NO_EXTENSIONS);
    }

    StoredHttpResult csrfValidationFailed(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "CSRF_VALIDATION_FAILED",
            "csrf-validation-failed",
            "CSRF validation failed",
            403,
            NO_EXTENSIONS);
    }

    StoredHttpResult schemaNotFound(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "SCHEMA_NOT_FOUND",
            "schema-not-found",
            "Contract schema was not found",
            404,
            NO_EXTENSIONS);
    }

    StoredHttpResult contractValidationNotFound(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "CONTRACT_VALIDATION_NOT_FOUND",
            "contract-validation-not-found",
            "Contract validation was not found",
            404,
            NO_EXTENSIONS);
    }

    StoredHttpResult notAcceptable(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "NOT_ACCEPTABLE",
            "not-acceptable",
            "Requested representation is not acceptable",
            406,
            NO_EXTENSIONS);
    }

    StoredHttpResult idempotencyKeyReused(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "IDEMPOTENCY_KEY_REUSED",
            "idempotency-key-reused",
            "Idempotency key was reused for a different request",
            409,
            NO_EXTENSIONS);
    }

    StoredHttpResult commandInProgress(
            UUID correlationId, String requestTarget, int retryAfter) {
        if (retryAfter < 1 || retryAfter > 120) {
            throw new IllegalArgumentException("retry_after must be between 1 and 120");
        }
        return problem(
            correlationId,
            requestTarget,
            "COMMAND_IN_PROGRESS",
            "command-in-progress",
            "Command is already in progress",
            409,
            body -> body.put("retry_after", retryAfter));
    }

    StoredHttpResult versionConflict(
            UUID correlationId,
            String requestTarget,
            long expectedVersion,
            Long actualVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expected_version must not be negative");
        }
        if (actualVersion != null && actualVersion < 1) {
            throw new IllegalArgumentException(
                "actual_version must be null or greater than zero");
        }
        return problem(
            correlationId,
            requestTarget,
            "VERSION_CONFLICT",
            "version-conflict",
            "Expected version does not match",
            412,
            body -> {
                putInteger(body, "expected_version", expectedVersion);
                if (actualVersion == null) {
                    body.putNull("actual_version");
                } else {
                    putInteger(body, "actual_version", actualVersion);
                }
            });
    }

    StoredHttpResult unsupportedMediaType(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "UNSUPPORTED_MEDIA_TYPE",
            "unsupported-media-type",
            "Content type is not supported",
            415,
            NO_EXTENSIONS);
    }

    StoredHttpResult versionLimitReached(UUID correlationId, String requestTarget) {
        return problem(
            correlationId,
            requestTarget,
            "VERSION_LIMIT_REACHED",
            "version-limit-reached",
            "Aggregate version limit was reached",
            422,
            NO_EXTENSIONS);
    }

    StoredHttpResult documentSchemaInvalid(
            UUID correlationId,
            String requestTarget,
            List<ContractValidationJson.ValidationError> errors) {
        validateErrors(errors);
        List<ContractValidationJson.ValidationError> stableErrors = List.copyOf(errors);
        return problem(
            correlationId,
            requestTarget,
            "DOCUMENT_SCHEMA_INVALID",
            "document-schema-invalid",
            "Document does not satisfy the schema",
            422,
            body -> {
                var errorArray = body.putArray("errors");
                for (ContractValidationJson.ValidationError error : stableErrors) {
                    errorArray.addObject()
                        .put("field", error.field())
                        .put("reason", error.reason());
                }
            });
    }

    private StoredHttpResult problem(
            UUID correlationId,
            String requestTarget,
            String code,
            String typeSuffix,
            String title,
            int status,
            Consumer<ObjectNode> extensions) {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", PROBLEM_TYPE_PREFIX + typeSuffix);
        body.put("title", title);
        body.put("status", status);
        body.put("code", code);
        body.put("correlation_id", Objects.requireNonNull(
            correlationId, "correlationId").toString());
        body.put("instance", instancePath(requestTarget));
        extensions.accept(body);

        try {
            byte[] canonicalBody = CanonicalJson.canonicalizePreservingExactIntegers(
                mapper.writeValueAsBytes(body));
            return new StoredHttpResult(
                status, PROBLEM_HEADERS, new String(canonicalBody, UTF_8));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("problem response cannot be serialized", error);
        }
    }

    private static void validateErrors(
            List<ContractValidationJson.ValidationError> errors) {
        if (errors == null || errors.isEmpty() || errors.size() > 128) {
            throw new IllegalArgumentException("errors must contain between 1 and 128 items");
        }
        for (ContractValidationJson.ValidationError error : errors) {
            if (error == null
                    || error.field() == null
                    || error.reason() == null
                    || error.field().codePointCount(0, error.field().length()) < 1
                    || error.field().codePointCount(0, error.field().length()) > 512
                    || error.reason().length() < 1
                    || error.reason().length() > 64
                    || !REASON.matcher(error.reason()).matches()) {
                throw new IllegalArgumentException("errors contains an invalid item");
            }
        }
    }

    private static String instancePath(String requestTarget) {
        Objects.requireNonNull(requestTarget, "requestTarget");
        int queryStart = requestTarget.indexOf('?');
        String path = queryStart < 0 ? requestTarget : requestTarget.substring(0, queryStart);
        if (path.isEmpty() || path.charAt(0) != '/' || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("instance must be an absolute path");
        }
        return path;
    }

    private static void putInteger(ObjectNode body, String field, long value) {
        if (value <= Integer.MAX_VALUE) {
            body.put(field, (int) value);
        } else {
            body.put(field, value);
        }
    }

}

final class FoundationHttpResponseWriter {
    private FoundationHttpResponseWriter() {}

    static void write(HttpServletResponse response, StoredHttpResult result) throws IOException {
        byte[] body = strictUtf8(result.body());
        response.setStatus(result.status());
        result.headers().forEach(response::setHeader);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    static byte[] strictUtf8(String body) {
        try {
            ByteBuffer encoded = UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(Objects.requireNonNull(body, "body")));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException error) {
            throw new IllegalStateException(
                "response body cannot be encoded as UTF-8", error);
        }
    }
}
