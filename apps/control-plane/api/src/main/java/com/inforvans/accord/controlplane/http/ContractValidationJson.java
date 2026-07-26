package com.inforvans.accord.controlplane.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.inforvans.accord.platformkernel.CanonicalJson;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.DisallowSchemaLoader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class ContractValidationJson {
    private static final URI DOMAIN_EVENT_SCHEMA = URI.create(
        "https://schemas.accord.inforvans.com/events/domain-event/1-0-0");
    private static final String DRAFT_2020_12 =
        "https://json-schema.org/draft/2020-12/schema";
    private static final String DOMAIN_EVENT_RESOURCE =
        "/accord/contracts/domain-event.schema.json";
    private static final int MAX_ERRORS = 128;
    private static final int MAX_FIELD_CODE_POINTS = 512;
    private static final int BOUNDED_FIELD_PREFIX_CODE_POINTS = 440;
    private static final int REASON_HASH_CHARACTERS = 52;
    private static final Pattern SCHEMA_IDENTIFIER = Pattern.compile(
        "^https://schemas[.]accord[.]inforvans[.]com/[A-Za-z0-9._~:/-]{1,448}$");
    private static final Comparator<ValidationError> ERROR_ORDER = Comparator
        .comparing(ValidationError::field)
        .thenComparing(ValidationError::reason);

    private final ObjectMapper mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
    private final Map<URI, JsonSchema> schemas;

    ContractValidationJson() {
        schemas = Map.of(DOMAIN_EVENT_SCHEMA, loadDomainEventSchema());
    }

    Set<URI> schemaIds() {
        return schemas.keySet();
    }

    SchemaEvaluation evaluate(URI schemaId, JsonNode document) {
        Objects.requireNonNull(schemaId, "schemaId");
        JsonSchema schema = schemas.get(schemaId);
        if (schema == null) {
            return new SchemaEvaluation.SchemaNotFound();
        }

        List<SchemaViolation> violations = schema
            .validate(Objects.requireNonNull(document, "document"))
            .stream()
            .map(message -> new SchemaViolation(
                pointer(message.getInstanceLocation()), message.getType()))
            .toList();
        if (violations.isEmpty()) {
            return new SchemaEvaluation.Valid();
        }
        return new SchemaEvaluation.Invalid(projectViolations(violations));
    }

    static String pointer(JsonNodePath path) {
        Objects.requireNonNull(path, "path");
        if (path.getNameCount() == 0) {
            return "$";
        }

        StringBuilder result = new StringBuilder();
        for (int index = 0; index < path.getNameCount(); index++) {
            String segment = Objects.requireNonNull(path.getName(index), "path segment");
            result.append('/')
                .append(segment.replace("~", "~0").replace("/", "~1"));
        }
        return result.toString();
    }

    static String boundField(String field) {
        Objects.requireNonNull(field, "field");
        int codePoints = field.codePointCount(0, field.length());
        if (codePoints <= MAX_FIELD_CODE_POINTS) {
            return field;
        }

        int prefixEnd = field.offsetByCodePoints(0, BOUNDED_FIELD_PREFIX_CODE_POINTS);
        return field.substring(0, prefixEnd) + "#sha256:" + uppercaseSha256(field);
    }

    static String normalizeReason(String keyword) {
        Objects.requireNonNull(keyword, "keyword");
        StringBuilder normalized = new StringBuilder(keyword.length());
        boolean previousWasLowercaseOrDigit = false;
        for (int index = 0; index < keyword.length(); index++) {
            char character = keyword.charAt(index);
            if (character >= 'A' && character <= 'Z') {
                if (previousWasLowercaseOrDigit
                        && !normalized.isEmpty()
                        && normalized.charAt(normalized.length() - 1) != '_') {
                    normalized.append('_');
                }
                normalized.append(character);
                previousWasLowercaseOrDigit = false;
            } else if (character >= 'a' && character <= 'z') {
                normalized.append((char) (character - ('a' - 'A')));
                previousWasLowercaseOrDigit = true;
            } else if (character >= '0' && character <= '9') {
                normalized.append(character);
                previousWasLowercaseOrDigit = true;
            } else {
                if (!normalized.isEmpty()
                        && normalized.charAt(normalized.length() - 1) != '_') {
                    normalized.append('_');
                }
                previousWasLowercaseOrDigit = false;
            }
        }
        while (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == '_') {
            normalized.deleteCharAt(normalized.length() - 1);
        }

        String reason = "SCHEMA_" + normalized;
        if (normalized.isEmpty() || reason.length() > 64) {
            return hashedReason(keyword);
        }
        return reason;
    }

    static List<ValidationError> projectViolations(List<SchemaViolation> violations) {
        Objects.requireNonNull(violations, "violations");
        return violations.stream()
            .map(violation -> {
                Objects.requireNonNull(violation, "violation");
                return new ValidationError(
                    boundField(violation.field()), normalizeReason(violation.keyword()));
            })
            .sorted(ERROR_ORDER)
            .distinct()
            .limit(MAX_ERRORS)
            .toList();
    }

    DecodedRequest decode(byte[] rawBody) {
        Objects.requireNonNull(rawBody, "rawBody");
        if (rawBody.length > FoundationHttpRequestPolicy.MAX_RAW_BODY_BYTES) {
            throw new JsonFailure(FailureKind.REQUEST_INVALID, "request body exceeds 1 MiB");
        }

        byte[] canonicalBody;
        String bodyDigest;
        try {
            canonicalBody = CanonicalJson.canonicalize(rawBody);
            bodyDigest = CanonicalJson.sha256(rawBody);
        } catch (IllegalArgumentException error) {
            throw new JsonFailure(FailureKind.JSON_INVALID, "request body is not valid I-JSON", error);
        }

        ContractValidationRequest request;
        try {
            request = mapper.readValue(canonicalBody, ContractValidationRequest.class);
        } catch (IOException error) {
            throw new JsonFailure(
                FailureKind.REQUEST_INVALID, "request body does not match the closed contract", error);
        }
        validate(request);
        return new DecodedRequest(request, canonicalBody.clone(), bodyDigest);
    }

    private static void validate(ContractValidationRequest request) {
        if (request == null || request.schemaId() == null || request.document() == null
                || !request.document().isObject()) {
            throw new JsonFailure(
                FailureKind.REQUEST_INVALID, "request fields are missing or invalid");
        }
        String identifier = request.schemaId().toASCIIString();
        if (!SCHEMA_IDENTIFIER.matcher(identifier).matches()
                || !"https".equals(request.schemaId().getScheme())
                || !"schemas.accord.inforvans.com".equals(request.schemaId().getHost())
                || request.schemaId().getRawUserInfo() != null
                || request.schemaId().getPort() != -1
                || request.schemaId().getRawQuery() != null
                || request.schemaId().getRawFragment() != null) {
            throw new JsonFailure(FailureKind.REQUEST_INVALID, "schema_id is not an allowed identifier");
        }
    }

    private JsonSchema loadDomainEventSchema() {
        JsonNode schemaNode;
        try (InputStream resource = ContractValidationJson.class.getResourceAsStream(
                DOMAIN_EVENT_RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException(
                    "packaged domain-event schema resource is missing");
            }
            schemaNode = mapper.readTree(resource);
        } catch (IOException error) {
            throw new IllegalStateException(
                "packaged domain-event schema resource cannot be read", error);
        }

        if (schemaNode == null
                || !DOMAIN_EVENT_SCHEMA.toString().equals(schemaNode.path("$id").textValue())
                || !DRAFT_2020_12.equals(schemaNode.path("$schema").textValue())) {
            throw new IllegalStateException(
                "packaged domain-event schema metadata does not match its registry entry");
        }

        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true)
            .pathType(PathType.JSON_POINTER)
            .build();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaLoaders(
                loaders -> loaders.add(DisallowSchemaLoader.getInstance())));
        return factory.getSchema(DOMAIN_EVENT_SCHEMA, schemaNode, config);
    }

    private static String hashedReason(String keyword) {
        return "SCHEMA_RULE_" + uppercaseSha256(keyword).substring(0, REASON_HASH_CHARACTERS);
    }

    private static String uppercaseSha256(String value) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    record DecodedRequest(
            ContractValidationRequest request, byte[] canonicalBody, String bodyDigest) {
        DecodedRequest {
            Objects.requireNonNull(request, "request");
            canonicalBody = Objects.requireNonNull(canonicalBody, "canonicalBody").clone();
            Objects.requireNonNull(bodyDigest, "bodyDigest");
        }

        @Override
        public byte[] canonicalBody() {
            return canonicalBody.clone();
        }

        URI schemaId() {
            return request.schemaId();
        }

        JsonNode document() {
            return request.document();
        }
    }

    enum FailureKind {
        REQUEST_INVALID,
        JSON_INVALID
    }

    sealed interface SchemaEvaluation {
        record Valid() implements SchemaEvaluation {}

        record SchemaNotFound() implements SchemaEvaluation {}

        record Invalid(List<ValidationError> errors) implements SchemaEvaluation {
            public Invalid {
                errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
            }
        }
    }

    record ValidationError(String field, String reason) {}

    record SchemaViolation(String field, String keyword) {}

    static final class JsonFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final FailureKind kind;

        JsonFailure(FailureKind kind, String message) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        JsonFailure(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        FailureKind kind() {
            return kind;
        }
    }
}

record ContractValidationRequest(
        @JsonProperty("schema_id") URI schemaId,
        @JsonProperty("document") JsonNode document) {}
