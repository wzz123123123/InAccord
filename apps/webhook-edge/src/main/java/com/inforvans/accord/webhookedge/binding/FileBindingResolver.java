package com.inforvans.accord.webhookedge.binding;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class FileBindingResolver implements BindingResolver {
    public static final int MAX_PROJECTION_BYTES = 1_048_576;
    private static final int MAX_BINDINGS = 4_096;
    private static final Set<String> ROOT_FIELDS = Set.of("bindings");
    private static final Set<String> BINDING_FIELDS = Set.of(
            "binding_id",
            "tenant_id",
            "immutable_repository_id",
            "mode",
            "signing_token",
            "legacy_token");

    private final Path projectionPath;
    private final ObjectMapper mapper;

    public FileBindingResolver(Path projectionPath) {
        this.projectionPath = projectionPath.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper(JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public Optional<WebhookBinding> resolve(UUID bindingId) {
        try {
            return Optional.ofNullable(readProjection().get(Objects.requireNonNull(bindingId, "bindingId")));
        } catch (IOException | RuntimeException exception) {
            throw new BindingResolver.UnavailableException();
        }
    }

    public void validateProjection() {
        try {
            readProjection();
        } catch (IOException | RuntimeException exception) {
            throw new BindingResolver.UnavailableException();
        }
    }

    private Map<UUID, WebhookBinding> readProjection() throws IOException {
        byte[] json;
        try (InputStream input = Files.newInputStream(projectionPath)) {
            json = input.readNBytes(MAX_PROJECTION_BYTES + 1);
        }
        if (json.length > MAX_PROJECTION_BYTES) {
            throw new IOException("projection too large");
        }

        JsonNode root = mapper.readTree(json);
        requireObjectWithExactFields(root, ROOT_FIELDS);
        JsonNode bindings = root.required("bindings");
        if (!bindings.isArray() || bindings.size() > MAX_BINDINGS) {
            throw new IOException("invalid bindings array");
        }

        Set<UUID> bindingIds = new HashSet<>();
        Map<UUID, WebhookBinding> parsedBindings = new LinkedHashMap<>();
        for (JsonNode node : bindings) {
            requireObjectWithExactFields(node, BINDING_FIELDS);
            UUID bindingId = canonicalUuid(requiredText(node, "binding_id"));
            UUID tenantId = canonicalUuid(requiredText(node, "tenant_id"));
            if (!bindingIds.add(bindingId)) {
                throw new IOException("duplicate binding");
            }

            JsonNode repositoryIdNode = node.required("immutable_repository_id");
            if (!repositoryIdNode.isIntegralNumber() || !repositoryIdNode.canConvertToLong()) {
                throw new IOException("invalid repository id");
            }
            long repositoryId = repositoryIdNode.longValue();
            JsonNode modeNode = node.get("mode");
            WebhookVerificationMode mode = modeNode == null
                    ? WebhookVerificationMode.STANDARD_REQUIRED
                    : WebhookVerificationMode.valueOf(requiredText(node, "mode"));
            WebhookBinding parsed = new WebhookBinding(
                    bindingId,
                    tenantId,
                    repositoryId,
                    mode,
                    optionalText(node, "signing_token"),
                    optionalText(node, "legacy_token"));
            parsedBindings.put(bindingId, parsed);
        }
        return parsedBindings;
    }

    private static void requireObjectWithExactFields(JsonNode node, Set<String> allowedFields) throws IOException {
        if (node == null || !node.isObject()) {
            throw new IOException("object required");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowedFields.contains(names.next())) {
                throw new IOException("unknown field");
            }
        }
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IOException("required field missing");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IOException("text field required");
        }
        return value.textValue();
    }

    private static UUID canonicalUuid(String value) throws IOException {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IOException("non-canonical uuid");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid uuid");
        }
    }
}
