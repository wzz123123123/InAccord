package com.inforvans.accord.webhookedge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.inforvans.accord.webhookedge.binding.BindingResolver;
import com.inforvans.accord.webhookedge.binding.FileBindingResolver;
import com.inforvans.accord.webhookedge.binding.WebhookBinding;
import com.inforvans.accord.webhookedge.binding.WebhookVerificationMode;
import com.inforvans.accord.webhookedge.webhook.VerifiedGitLabWebhook;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class GitLabWebhookVerifierTest {
    private static final Instant NOW = Instant.parse("2026-07-26T01:02:03Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID BINDING_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000011");
    private static final UUID TENANT_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000022");
    private static final UUID DELIVERY_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000033");
    private static final long REPOSITORY_ID = 4_294_967_296L;
    private static final byte[] SECRET = "0123456789abcdefghijklmnopqrstuv".getBytes(StandardCharsets.US_ASCII);
    private static final String SIGNING_TOKEN = "whsec_" + Base64.getEncoder().encodeToString(SECRET);
    private static final String LEGACY_TOKEN = "legacy-token-value-which-must-stay-secret";
    private static final byte[] BODY = "{\"project_id\":4294967296}".getBytes(StandardCharsets.UTF_8);

    @Test
    void verifiesStandardSignatureOverExactRawBytes() {
        GitLabWebhookVerifier verifier = verifier();
        GitLabWebhookHeaders headers = standardHeaders(BODY, NOW.getEpochSecond());

        VerifiedGitLabWebhook verified = verifier.verify(standardBinding(), headers, BODY);

        assertThat(verified.binding()).isEqualTo(standardBinding());
        assertThat(verified.deliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(verified.eventType()).isEqualTo("Push Hook");
        assertThat(verified.observedAt()).isEqualTo(NOW);
        assertThat(verified.bodyDigest()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void alteredRawBytesDoNotAuthenticate() {
        GitLabWebhookHeaders headers = standardHeaders(BODY, NOW.getEpochSecond());

        assertFailure(
                () -> verifier().verify(standardBinding(), headers, "{ \"project_id\":4294967296}".getBytes(StandardCharsets.UTF_8)),
                GitLabWebhookVerifier.Failure.AUTHENTICATION);
    }

    @Test
    void missingWebhookIdFailsAuthentication() {
        Map<String, List<String>> values = signedHeaders(BODY, NOW.getEpochSecond());
        values.remove("webhook-id");

        assertFailure(
                () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                GitLabWebhookVerifier.Failure.AUTHENTICATION);
    }

    @Test
    void missingWebhookTimestampFailsAuthentication() {
        Map<String, List<String>> values = signedHeaders(BODY, NOW.getEpochSecond());
        values.remove("webhook-timestamp");

        assertFailure(
                () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                GitLabWebhookVerifier.Failure.AUTHENTICATION);
    }

    @Test
    void missingLegacyTokenFailsAuthentication() {
        assertFailure(
                () -> verifier().verify(legacyBinding(), GitLabWebhookHeaders.from(baseHeaders()), BODY),
                GitLabWebhookVerifier.Failure.AUTHENTICATION);
    }

    @Test
    void acceptsInclusiveTimestampWindowAndRejectsOneSecondBeyondIt() {
        for (long accepted : List.of(NOW.getEpochSecond() - 300L, NOW.getEpochSecond() + 300L)) {
            byte[] body = BODY.clone();
            verifier().verify(standardBinding(), standardHeaders(body, accepted), body);
        }

        for (long rejected : List.of(NOW.getEpochSecond() - 301L, NOW.getEpochSecond() + 301L)) {
            GitLabWebhookHeaders headers = standardHeaders(BODY, rejected);
            assertFailure(
                    () -> verifier().verify(standardBinding(), headers, BODY),
                    GitLabWebhookVerifier.Failure.AUTHENTICATION);
        }
    }

    @Test
    void rejectsNonCanonicalOrOverflowingTimestamps() {
        for (String timestamp : List.of("", "0" + NOW.getEpochSecond(), "+" + NOW.getEpochSecond(), "-1", "1.0", " 1", "9223372036854775808")) {
            Map<String, List<String>> values = baseHeaders();
            values.put("webhook-timestamp", List.of(timestamp));
            values.put("webhook-signature", List.of("v1," + Base64.getEncoder().encodeToString(new byte[32])));
            assertFailure(
                    () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                    GitLabWebhookVerifier.Failure.AUTHENTICATION);
        }
    }

    @Test
    void comparesEveryAdmittedCandidateEvenWhenItsVersionCannotAuthorize() {
        AtomicInteger comparisons = new AtomicInteger();
        GitLabStandardWebhookVerifier standard = new GitLabStandardWebhookVerifier(
                CLOCK,
                (expected, candidate) -> {
                    comparisons.incrementAndGet();
                    return java.security.MessageDigest.isEqual(expected, candidate);
                });
        GitLabWebhookVerifier verifier = new GitLabWebhookVerifier(
                standard, new GitLabLegacyTokenVerifier(), CLOCK);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String valid = Base64.getEncoder().encodeToString(signature(BODY, timestamp));
        String invalid = Base64.getEncoder().encodeToString(new byte[32]);
        Map<String, List<String>> values = baseHeaders();
        values.put("webhook-timestamp", List.of(timestamp));
        values.put("webhook-signature", List.of("v1," + invalid + " v1," + valid + " v2," + valid + " v1," + invalid));

        verifier.verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY);

        assertThat(comparisons).hasValue(4);
    }

    @Test
    void acceptsAValidSignatureInFirstMiddleOrLastPosition() {
        String timestamp = Long.toString(NOW.getEpochSecond());
        String valid = "v1," + Base64.getEncoder().encodeToString(signature(BODY, timestamp));
        String invalid = "v1," + Base64.getEncoder().encodeToString(new byte[32]);

        for (String candidates : List.of(
                valid + " " + invalid + " " + invalid,
                invalid + " " + valid + " " + invalid,
                invalid + " " + invalid + " " + valid)) {
            Map<String, List<String>> values = baseHeaders();
            values.put("webhook-timestamp", List.of(timestamp));
            values.put("webhook-signature", List.of(candidates));
            verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY);
        }
    }

    @Test
    void rejectsMalformedOrOversizedSignatureLists() {
        List<String> malformed = List.of(
                "",
                "v1",
                "v1,",
                "v1,%%%",
                "v1," + Base64.getEncoder().encodeToString(new byte[31]),
                "v1," + Base64.getEncoder().encodeToString(new byte[33]),
                "v1," + Base64.getEncoder().encodeToString(new byte[32]) + "  v1," + Base64.getEncoder().encodeToString(new byte[32]),
                String.join(" ", java.util.Collections.nCopies(9, "v2," + Base64.getEncoder().encodeToString(new byte[32]))));

        for (String value : malformed) {
            Map<String, List<String>> values = baseHeaders();
            values.put("webhook-timestamp", List.of(Long.toString(NOW.getEpochSecond())));
            values.put("webhook-signature", List.of(value));
            assertFailure(
                    () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                    GitLabWebhookVerifier.Failure.AUTHENTICATION);
        }
    }

    @Test
    void rejectsAnySignatureCandidateWhoseDecodedLengthIsNotSha256Length() {
        String timestamp = Long.toString(NOW.getEpochSecond());
        String valid = Base64.getEncoder().encodeToString(signature(BODY, timestamp));
        Map<String, List<String>> values = baseHeaders();
        values.put("webhook-timestamp", List.of(timestamp));
        values.put("webhook-signature", List.of(
                "v1," + valid + " v2," + Base64.getEncoder().encodeToString(new byte[31])));

        assertFailure(
                () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                GitLabWebhookVerifier.Failure.AUTHENTICATION);
    }

    @Test
    void unknownSignatureVersionsNeverMatch() {
        Map<String, List<String>> values = baseHeaders();
        values.put("webhook-timestamp", List.of(Long.toString(NOW.getEpochSecond())));
        values.put("webhook-signature", List.of("v2," + Base64.getEncoder().encodeToString(signature(BODY, Long.toString(NOW.getEpochSecond())))));

        assertFailure(
                () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                GitLabWebhookVerifier.Failure.AUTHENTICATION);
    }

    @Test
    void signaturePresenceForcesStandardAndNeverFallsBackToLegacy() {
        Map<String, List<String>> values = baseHeaders();
        values.put("webhook-signature", List.of("v1," + Base64.getEncoder().encodeToString(new byte[32])));
        values.put("webhook-timestamp", List.of(Long.toString(NOW.getEpochSecond())));
        values.put("X-Gitlab-Token", List.of(LEGACY_TOKEN));

        assertFailure(
                () -> verifier().verify(legacyBinding(), GitLabWebhookHeaders.from(values), BODY),
                GitLabWebhookVerifier.Failure.AUTHENTICATION);
    }

    @Test
    void legacyIsAllowedOnlyByServerBindingModeAndOnlyWithoutSignature() {
        Map<String, List<String>> values = baseHeaders();
        values.put("X-Gitlab-Token", List.of(LEGACY_TOKEN));

        VerifiedGitLabWebhook verified = verifier().verify(legacyBinding(), GitLabWebhookHeaders.from(values), BODY);
        assertThat(verified.deliveryId()).isEqualTo(DELIVERY_ID);

        assertFailure(
                () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                GitLabWebhookVerifier.Failure.AUTHENTICATION);
    }

    @Test
    void duplicateOrSmuggledAuthenticationHeadersFailAuthentication() {
        List<Map<String, List<String>>> standardCases = List.of(
                with(baseHeaders(), "webhook-id", List.of(DELIVERY_ID.toString(), DELIVERY_ID.toString())),
                with(baseHeaders(), "Webhook-Id", List.of(DELIVERY_ID.toString())),
                with(baseHeaders(), "webhook-timestamp", List.of(
                        Long.toString(NOW.getEpochSecond()), Long.toString(NOW.getEpochSecond()))),
                with(baseHeaders(), "webhook-signature", List.of(
                        "v1," + Base64.getEncoder().encodeToString(new byte[32]),
                        "v1," + Base64.getEncoder().encodeToString(new byte[32]))));

        for (Map<String, List<String>> values : standardCases) {
            values.putIfAbsent("webhook-timestamp", List.of(Long.toString(NOW.getEpochSecond())));
            values.putIfAbsent("webhook-signature", List.of("v1," + Base64.getEncoder().encodeToString(new byte[32])));
            assertFailure(
                    () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                    GitLabWebhookVerifier.Failure.AUTHENTICATION);
        }

        Map<String, List<String>> duplicateLegacy = baseHeaders();
        duplicateLegacy.put("X-Gitlab-Token", List.of(LEGACY_TOKEN, LEGACY_TOKEN));
        assertFailure(
                () -> verifier().verify(legacyBinding(), GitLabWebhookHeaders.from(duplicateLegacy), BODY),
                GitLabWebhookVerifier.Failure.AUTHENTICATION);
    }

    @Test
    void duplicateOrSmuggledNormalizedIdentityHeadersFailAsInvalidRequests() {
        for (Map<String, List<String>> values : List.of(
                with(signedHeaders(BODY, NOW.getEpochSecond()), "Idempotency-Key", List.of(DELIVERY_ID + "," + DELIVERY_ID)),
                with(signedHeaders(BODY, NOW.getEpochSecond()), "X-Gitlab-Event", List.of("Push Hook", "Push Hook")))) {
            assertFailure(
                    () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                    GitLabWebhookVerifier.Failure.INVALID_REQUEST);
        }
    }

    @Test
    void unsafeLegacyHeaderIsInvalidAfterSuccessfulStandardAuthentication() {
        Map<String, List<String>> values = signedHeaders(BODY, NOW.getEpochSecond());
        values.put("X-Gitlab-Token", List.of(LEGACY_TOKEN, LEGACY_TOKEN));

        assertFailure(
                () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                GitLabWebhookVerifier.Failure.INVALID_REQUEST);
    }

    @Test
    void requiresCanonicalMatchingDeliveryIdentity() {
        for (String webhookId : List.of(DELIVERY_ID.toString().toUpperCase(), "{" + DELIVERY_ID + "}", "not-a-uuid")) {
            Map<String, List<String>> values = signedHeaders(BODY, NOW.getEpochSecond());
            values.put("webhook-id", List.of(webhookId));
            values.put("webhook-signature", List.of("v1," + Base64.getEncoder().encodeToString(
                    signature(BODY, Long.toString(NOW.getEpochSecond()), webhookId))));
            assertFailure(
                    () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(values), BODY),
                    GitLabWebhookVerifier.Failure.INVALID_REQUEST);
        }

        Map<String, List<String>> disagreement = signedHeaders(BODY, NOW.getEpochSecond());
        disagreement.put("Idempotency-Key", List.of(UUID.randomUUID().toString()));
        assertFailure(
                () -> verifier().verify(standardBinding(), GitLabWebhookHeaders.from(disagreement), BODY),
                GitLabWebhookVerifier.Failure.INVALID_REQUEST);
    }

    @Test
    void bindingRejectsInvalidSecretsAndNeverRendersThem() {
        assertThatThrownBy(() -> new WebhookBinding(
                        BINDING_ID, TENANT_ID, REPOSITORY_ID, WebhookVerificationMode.STANDARD_REQUIRED, "whsec_%%%", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("whsec_%%%");
        assertThatThrownBy(() -> new WebhookBinding(
                        BINDING_ID, TENANT_ID, REPOSITORY_ID, WebhookVerificationMode.LEGACY_ALLOWED, SIGNING_TOKEN, null))
                .isInstanceOf(IllegalArgumentException.class);

        String rendered = legacyBinding().toString();
        assertThat(rendered)
                .contains(BINDING_ID.toString(), TENANT_ID.toString())
                .doesNotContain(SIGNING_TOKEN, LEGACY_TOKEN, Base64.getEncoder().encodeToString(SECRET));
    }

    @Test
    void fileResolverStrictlyReloadsBoundedProjection(@TempDir Path directory) throws Exception {
        Path projection = directory.resolve("bindings.json");
        Files.writeString(projection, projectionJson(REPOSITORY_ID));
        FileBindingResolver resolver = new FileBindingResolver(projection);

        WebhookBinding first = resolver.resolve(BINDING_ID).orElseThrow();
        assertThat(first.immutableRepositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(first.verificationMode()).isEqualTo(WebhookVerificationMode.STANDARD_REQUIRED);

        Files.writeString(projection, projectionJson(REPOSITORY_ID + 1));
        assertThat(resolver.resolve(BINDING_ID).orElseThrow().immutableRepositoryId()).isEqualTo(REPOSITORY_ID + 1);
        assertThat(resolver.resolve(UUID.randomUUID())).isEmpty();
    }

    @Test
    void fileResolverReadinessRejectsDuplicateBindingIds(@TempDir Path directory) throws Exception {
        Path projection = directory.resolve("bindings.json");
        Files.writeString(
                projection,
                "{\"bindings\":[" + projectionBindingJson(REPOSITORY_ID) + ","
                        + projectionBindingJson(REPOSITORY_ID + 1) + "]}");
        FileBindingResolver resolver = new FileBindingResolver(projection);

        assertThatThrownBy(() -> validateProjection(resolver))
                .isInstanceOf(BindingResolver.UnavailableException.class)
                .hasMessage("binding projection unavailable")
                .hasMessageNotContaining(SIGNING_TOKEN);
    }

    @Test
    void fileResolverReadinessRejectsTrailingJsonToken(@TempDir Path directory) throws Exception {
        Path projection = directory.resolve("bindings.json");
        Files.writeString(projection, projectionJson(REPOSITORY_ID) + " true");
        FileBindingResolver resolver = new FileBindingResolver(projection);

        assertThatThrownBy(() -> validateProjection(resolver))
                .isInstanceOf(BindingResolver.UnavailableException.class)
                .hasMessage("binding projection unavailable")
                .hasMessageNotContaining(SIGNING_TOKEN);
    }

    @Test
    void fileResolverRejectsDuplicatesUnknownFieldsAndUnsafeBindings(@TempDir Path directory) throws Exception {
        Path projection = directory.resolve("bindings.json");
        FileBindingResolver resolver = new FileBindingResolver(projection);
        List<String> invalid = List.of(
                "{\"bindings\":[],\"bindings\":[]}",
                "{\"bindings\":[],\"unknown\":true}",
                "{\"bindings\":[{\"binding_id\":\"" + BINDING_ID + "\",\"binding_id\":\"" + BINDING_ID + "\",\"tenant_id\":\"" + TENANT_ID + "\",\"immutable_repository_id\":1,\"signing_token\":\"" + SIGNING_TOKEN + "\"}]}",
                "{\"bindings\":[{\"binding_id\":\"" + BINDING_ID + "\",\"tenant_id\":\"" + TENANT_ID + "\",\"immutable_repository_id\":1,\"mode\":null,\"signing_token\":\"" + SIGNING_TOKEN + "\"}]}",
                "{\"bindings\":[{\"binding_id\":\"" + BINDING_ID + "\",\"tenant_id\":\"" + TENANT_ID + "\",\"immutable_repository_id\":1,\"mode\":\"LEGACY_ALLOWED\",\"signing_token\":\"" + SIGNING_TOKEN + "\"}]}",
                "{\"bindings\":[{\"binding_id\":\"" + BINDING_ID + "\",\"tenant_id\":\"" + TENANT_ID + "\",\"immutable_repository_id\":1,\"signing_token\":\"" + SIGNING_TOKEN + "\"},{\"binding_id\":\"" + BINDING_ID + "\",\"tenant_id\":\"" + TENANT_ID + "\",\"immutable_repository_id\":2,\"signing_token\":\"" + SIGNING_TOKEN + "\"}]} trailing");

        List<Executable> assertions = new ArrayList<>();
        for (String json : invalid) {
            assertions.add(() -> {
                Files.writeString(projection, json);
                assertThatThrownBy(() -> resolver.resolve(BINDING_ID))
                        .isInstanceOf(BindingResolver.UnavailableException.class)
                        .hasMessage("binding projection unavailable")
                        .hasMessageNotContaining(SIGNING_TOKEN)
                        .hasMessageNotContaining(LEGACY_TOKEN)
                        .hasMessageNotContaining(json);
            });
        }
        assertAll(assertions);

        Files.writeString(projection, "x".repeat(FileBindingResolver.MAX_PROJECTION_BYTES + 1));
        assertThatThrownBy(() -> resolver.resolve(BINDING_ID))
                .isInstanceOf(BindingResolver.UnavailableException.class)
                .hasMessage("binding projection unavailable");
    }

    private static GitLabWebhookVerifier verifier() {
        return new GitLabWebhookVerifier(CLOCK);
    }

    private static WebhookBinding standardBinding() {
        return new WebhookBinding(
                BINDING_ID,
                TENANT_ID,
                REPOSITORY_ID,
                WebhookVerificationMode.STANDARD_REQUIRED,
                SIGNING_TOKEN,
                null);
    }

    private static WebhookBinding legacyBinding() {
        return new WebhookBinding(
                BINDING_ID,
                TENANT_ID,
                REPOSITORY_ID,
                WebhookVerificationMode.LEGACY_ALLOWED,
                SIGNING_TOKEN,
                LEGACY_TOKEN);
    }

    private static GitLabWebhookHeaders standardHeaders(byte[] body, long epochSecond) {
        return GitLabWebhookHeaders.from(signedHeaders(body, epochSecond));
    }

    private static Map<String, List<String>> signedHeaders(byte[] body, long epochSecond) {
        String timestamp = Long.toString(epochSecond);
        Map<String, List<String>> values = baseHeaders();
        values.put("webhook-timestamp", List.of(timestamp));
        values.put("webhook-signature", List.of("v1," + Base64.getEncoder().encodeToString(signature(body, timestamp))));
        return values;
    }

    private static Map<String, List<String>> baseHeaders() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("webhook-id", List.of(DELIVERY_ID.toString()));
        values.put("Idempotency-Key", List.of(DELIVERY_ID.toString()));
        values.put("X-Gitlab-Event", List.of("Push Hook"));
        return values;
    }

    private static Map<String, List<String>> with(
            Map<String, List<String>> source, String name, List<String> values) {
        Map<String, List<String>> copy = new LinkedHashMap<>(source);
        copy.put(name, values);
        return copy;
    }

    private static byte[] signature(byte[] body, String timestamp) {
        return signature(body, timestamp, DELIVERY_ID.toString());
    }

    private static byte[] signature(byte[] body, String timestamp, String webhookId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            mac.update(webhookId.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String projectionJson(long repositoryId) {
        return "{\"bindings\":[" + projectionBindingJson(repositoryId) + "]}";
    }

    private static String projectionBindingJson(long repositoryId) {
        return "{"
                + "\"binding_id\":\"" + BINDING_ID + "\","
                + "\"tenant_id\":\"" + TENANT_ID + "\","
                + "\"immutable_repository_id\":" + repositoryId + ","
                + "\"signing_token\":\"" + SIGNING_TOKEN + "\"}";
    }

    private static void validateProjection(FileBindingResolver resolver) throws Exception {
        try {
            FileBindingResolver.class.getMethod("validateProjection").invoke(resolver);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw exception;
        }
    }

    private static void assertFailure(Runnable invocation, GitLabWebhookVerifier.Failure expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(
                        GitLabWebhookVerifier.VerificationException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(expected))
                .hasMessage("webhook verification failed");
    }
}
