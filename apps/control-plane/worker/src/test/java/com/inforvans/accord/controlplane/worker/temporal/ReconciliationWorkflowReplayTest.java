package com.inforvans.accord.controlplane.worker.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.history.v1.History;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.WorkflowReplayer;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowImpl;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReconciliationWorkflowReplayTest {
    static final String HISTORY_RESOURCE =
        "temporal/reconciliation-workflow-v1.json";
    static final String PROVENANCE_RESOURCE =
        "temporal/reconciliation-workflow-v1.provenance.json";
    static final String NAMESPACE = "accord-reconciliation-test-v1";
    static final String TASK_QUEUE = "accord-reconciliation-v1";
    static final String SERVER_NAME = "temporal.test";
    static final String WORKFLOW_ID = "reconciliation-golden-v1";
    static final String WORKFLOW_TYPE = "accord.reconciliation.v1";
    static final UUID TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000010");
    static final UUID INTENT_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000010");

    private static final String SCHEMA =
        "accord.temporal.reconciliation-history-provenance";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DataConverter CONVERTER =
        DefaultDataConverter.STANDARD_INSTANCE;
    private static final Pattern FORBIDDEN_FIELD = Pattern.compile(
        "(?i)(credential|authorization|secret|token(?:_value)?|owner|generation|"
            + "deadline|lease_until|global(?:_idempotency)?_key|provider(?:_request)?|"
            + "installation|repository|operation|request(?:_reference)?"
            + "(?:_type|_id|_version)?|request_digest|url|source|diff|raw_body)");
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
        "capture_argv", "history_resource", "history_sha256", "namespace", "schema",
        "schema_tool", "sdk", "server", "server_name", "task_queue", "transport",
        "version", "workflow_id", "workflow_type");
    private static final Set<String> IMAGE_FIELDS = Set.of(
        "coordinate", "linux_amd64_manifest_digest", "repository_digest");
    private static final Set<String> SDK_FIELDS = Set.of(
        "coordinate", "artifact_sha256");
    private static final List<String> CAPTURE_ARGV = List.of(
        "./gradlew.bat",
        ":apps:control-plane:worker:test",
        "--tests",
        "*TemporalMtlsIT.captureGoldenHistory",
        "-PaccordCaptureTemporalHistory=true",
        "--no-daemon",
        "--dependency-verification=strict");

    @Test
    void replaysAndStructurallyInspectsTheCommittedProductionHistory()
            throws Exception {
        byte[] historyBytes = resource(HISTORY_RESOURCE);
        History history = parseHistory(historyBytes);

        WorkflowReplayer.replayWorkflowExecutionFromResource(
            HISTORY_RESOURCE, ReconciliationWorkflowImpl.class);
        inspectHistory(history, TENANT_ID, INTENT_ID, ReconciliationOutcome.CONVERGED);
        validateProvenance(historyBytes, resource(PROVENANCE_RESOURCE));
    }

    static History parseHistory(byte[] json) throws IOException {
        History.Builder history = History.newBuilder();
        JsonFormat.parser().merge(
            new String(json, StandardCharsets.UTF_8), history);
        assertThat(history.getEventsCount())
            .as("captured Temporal history must not be empty")
            .isPositive();
        return history.build();
    }

    static void inspectHistory(
            History history,
            UUID tenantId,
            UUID intentId,
            ReconciliationOutcome expectedOutcome) {
        PayloadInspection inspection = new PayloadInspection(
            tenantId, intentId, expectedOutcome);
        inspection.visitMessage(history, "history");
        inspection.assertRequiredIdentifiersObserved();
    }

    private static void validateProvenance(byte[] history, byte[] provenance)
            throws Exception {
        JsonNode root = JSON.readTree(provenance);
        assertThat(root.isObject()).isTrue();
        assertExactFields(root, TOP_LEVEL_FIELDS, "provenance");
        assertKeysSortedRecursively(root, "provenance");
        assertThat(root.path("capture_argv").isArray()).isTrue();
        assertThat(strings(root.path("capture_argv"))).containsExactlyElementsOf(CAPTURE_ARGV);
        assertText(root, "history_resource", HISTORY_RESOURCE);
        assertText(root, "history_sha256", sha256(history));
        assertText(root, "namespace", NAMESPACE);
        assertText(root, "schema", SCHEMA);
        assertText(root, "server_name", SERVER_NAME);
        assertText(root, "task_queue", TASK_QUEUE);
        assertText(root, "transport", "mtls");
        assertThat(root.path("version").isIntegralNumber()).isTrue();
        assertThat(root.path("version").intValue()).isEqualTo(1);
        assertText(root, "workflow_id", WORKFLOW_ID);
        assertText(root, "workflow_type", WORKFLOW_TYPE);

        validateImage(root.path("server"), TemporalMtlsTestServer.SERVER_SOURCE,
            TemporalMtlsTestServer.SERVER_AMD64_MANIFEST,
            TemporalMtlsTestServer.SERVER_REPOSITORY_DIGEST);
        validateImage(root.path("schema_tool"), TemporalMtlsTestServer.ADMIN_TOOLS_SOURCE,
            TemporalMtlsTestServer.ADMIN_TOOLS_AMD64_MANIFEST,
            TemporalMtlsTestServer.ADMIN_TOOLS_REPOSITORY_DIGEST);

        JsonNode sdk = root.path("sdk");
        assertExactFields(sdk, SDK_FIELDS, "sdk");
        assertText(sdk, "coordinate", "io.temporal:temporal-sdk:1.28.1");
        String artifactDigest = requiredText(sdk, "artifact_sha256");
        assertThat(artifactDigest).matches(DIGEST);
        assertThat(artifactDigest).isEqualTo(sha256(temporalSdkArtifact()));

        assertLocalRepoDigest(
            TemporalMtlsTestServer.SERVER_SOURCE,
            TemporalMtlsTestServer.SERVER_REPOSITORY_DIGEST);
        assertLocalRepoDigest(
            TemporalMtlsTestServer.ADMIN_TOOLS_SOURCE,
            TemporalMtlsTestServer.ADMIN_TOOLS_REPOSITORY_DIGEST);
    }

    private static void validateImage(
            JsonNode node,
            String coordinate,
            String amd64Digest,
            String repositoryDigest) {
        assertExactFields(node, IMAGE_FIELDS, coordinate);
        assertText(node, "coordinate", coordinate);
        assertText(node, "linux_amd64_manifest_digest", amd64Digest);
        assertText(node, "repository_digest", repositoryDigest);
        assertThat(amd64Digest).matches(DIGEST);
        assertThat(repositoryDigest).matches(DIGEST);
    }

    private static void assertLocalRepoDigest(String coordinate, String expected)
            throws Exception {
        Process process = new ProcessBuilder(
            "docker", "image", "inspect", "--format={{json .RepoDigests}}", coordinate)
            .redirectErrorStream(true)
            .start();
        boolean exited = process.waitFor(15, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("Docker image identity inspection timed out for " + coordinate);
        }
        String output = new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue())
            .as("immutable local image identity for %s: %s", coordinate, output)
            .isZero();
        assertThat(output).contains("@" + expected);
    }

    private static Path temporalSdkArtifact() throws Exception {
        URI location = WorkflowClient.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI();
        Path artifact = Path.of(location).toRealPath();
        assertThat(Files.isRegularFile(artifact))
            .as("Temporal SDK must resolve to one approved artifact")
            .isTrue();
        return artifact;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = ReconciliationWorkflowReplayTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("required captured Temporal resource is absent: " + name);
            }
            return input.readAllBytes();
        }
    }

    private static String sha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void assertExactFields(
            JsonNode node, Set<String> expected, String description) {
        assertThat(node.isObject()).as(description + " must be an object").isTrue();
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).as(description + " fields")
            .containsExactlyInAnyOrderElementsOf(expected);
    }

    private static void assertKeysSortedRecursively(JsonNode node, String path) {
        if (node.isObject()) {
            List<String> actual = new ArrayList<>();
            node.fieldNames().forEachRemaining(actual::add);
            List<String> sorted = actual.stream().sorted(Comparator.naturalOrder()).toList();
            assertThat(actual).as(path + " keys must be canonical").containsExactlyElementsOf(sorted);
            for (String field : actual) {
                assertKeysSortedRecursively(node.get(field), path + "." + field);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                assertKeysSortedRecursively(node.get(index), path + "[" + index + "]");
            }
        }
    }

    private static void assertText(JsonNode node, String field, String expected) {
        assertThat(requiredText(node, field)).isEqualTo(expected);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        assertThat(value.isTextual()).as(field + " must be text").isTrue();
        return value.textValue();
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array) {
            assertThat(value.isTextual()).isTrue();
            result.add(value.textValue());
        }
        return result;
    }

    private static final class PayloadInspection {
        private static final Set<String> IDENTIFIER_FIELDS = Set.of(
            "tenantId", "intentId", "tenant_id", "intent_id");
        private final String tenantId;
        private final String intentId;
        private final String expectedOutcome;
        private boolean sawTenant;
        private boolean sawIntent;
        private boolean sawOutcome;

        private PayloadInspection(
                UUID tenantId,
                UUID intentId,
                ReconciliationOutcome expectedOutcome) {
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId").toString();
            this.intentId = Objects.requireNonNull(intentId, "intentId").toString();
            this.expectedOutcome = Objects.requireNonNull(
                expectedOutcome, "expectedOutcome").name();
        }

        private void visitMessage(Message message, String path) {
            rejectLeaseName(message.getClass().getName(), path);
            if (message instanceof Payload payload) {
                visitPayload(payload, path);
                return;
            }
            for (Map.Entry<FieldDescriptor, Object> entry
                    : message.getAllFields().entrySet()) {
                FieldDescriptor field = entry.getKey();
                Object value = entry.getValue();
                String childPath = path + "." + field.getJsonName();
                if (field.isRepeated()) {
                    int index = 0;
                    for (Object item : (List<?>) value) {
                        visitField(field, item, childPath + "[" + index++ + "]");
                    }
                } else {
                    visitField(field, value, childPath);
                }
            }
        }

        private void visitField(FieldDescriptor field, Object value, String path) {
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                visitMessage((Message) value, path);
            }
        }

        private void visitPayload(Payload payload, String path) {
            assertThat(payload.containsMetadata("encoding"))
                .as(path + " payload encoding")
                .isTrue();
            String encoding = payload.getMetadataOrThrow("encoding").toStringUtf8();
            Object decoded;
            switch (encoding) {
                case "json/plain" -> decoded = CONVERTER.fromPayload(
                    payload, Object.class, Object.class);
                case "binary/null" -> {
                    decoded = CONVERTER.fromPayload(payload, Object.class, Object.class);
                    assertThat(payload.getData().isEmpty()).as(path).isTrue();
                }
                case "binary/plain" -> {
                    decoded = CONVERTER.fromPayload(payload, byte[].class, byte[].class);
                    assertThat(payload.getData().isEmpty())
                        .as(path + " contains an opaque binary payload")
                        .isTrue();
                }
                case "json/protobuf", "binary/protobuf" -> decoded = decodeProtobuf(
                    payload, encoding, path);
                default -> throw new AssertionError(
                    path + " contains unsupported payload encoding " + encoding);
            }
            inspectDecoded(decoded, path);
        }

        private Object decodeProtobuf(Payload payload, String encoding, String path) {
            ByteString messageType = payload.getMetadataOrDefault(
                "messageType", ByteString.EMPTY);
            assertThat(messageType.isEmpty())
                .as(path + " protobuf payload must identify its message type")
                .isFalse();
            try {
                Class<?> type = Class.forName(messageType.toStringUtf8());
                assertThat(Message.class.isAssignableFrom(type))
                    .as(path + " protobuf type")
                    .isTrue();
                return CONVERTER.fromPayload(payload, type, type);
            } catch (ClassNotFoundException failure) {
                throw new AssertionError(
                    path + " contains unknown " + encoding + " type", failure);
            }
        }

        private void inspectDecoded(Object value, String path) {
            if (value == null) {
                return;
            }
            rejectLeaseName(value.getClass().getName(), path);
            if (value instanceof Message message) {
                visitMessage(message, path);
                return;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String field = String.valueOf(entry.getKey());
                    assertThat(FORBIDDEN_FIELD.matcher(field).find())
                        .as(path + " forbidden field " + field)
                        .isFalse();
                    assertThat(IDENTIFIER_FIELDS)
                        .as(path + " unexpected business field " + field)
                        .contains(field);
                    inspectDecoded(entry.getValue(), path + "." + field);
                }
                return;
            }
            if (value instanceof Collection<?> collection) {
                int index = 0;
                for (Object item : collection) {
                    inspectDecoded(item, path + "[" + index++ + "]");
                }
                return;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                assertThat(length).as(path + " opaque array length").isZero();
                return;
            }
            if (value instanceof String text) {
                if (tenantId.equals(text)) {
                    sawTenant = true;
                } else if (intentId.equals(text)) {
                    sawIntent = true;
                } else if (expectedOutcome.equals(text)) {
                    sawOutcome = true;
                } else {
                    fail(path + " contains a non-allowlisted payload scalar");
                }
                return;
            }
            fail(path + " contains unexpected payload type " + value.getClass().getName());
        }

        private static void rejectLeaseName(String value, String path) {
            assertThat(value).as(path + " type")
                .doesNotContain("ReconciliationLease");
        }

        private void assertRequiredIdentifiersObserved() {
            assertThat(sawTenant).as("workflow tenant identifier payload").isTrue();
            assertThat(sawIntent).as("workflow/activity/heartbeat intent identifier payload")
                .isTrue();
            assertThat(sawOutcome).as("closed workflow outcome payload").isTrue();
        }
    }
}
