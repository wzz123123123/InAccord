package com.inforvans.accord.controlplane.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inforvans.accord.reliability.ExpectedVersion;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@SuppressWarnings("auxiliaryclass")
final class HttpIdempotencyFingerprintTest {
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VALIDATION_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String IDEMPOTENCY_KEY = "validation-key-0001";
    private static final String BINDING_DIGEST = "sha256:" + "a".repeat(64);

    private final FoundationHttpRequestPolicy policy = new FoundationHttpRequestPolicy();
    private final ContractValidationJson json = new ContractValidationJson();
    private final HttpIdempotencyFingerprint fingerprints =
        new HttpIdempotencyFingerprint("0.1.0");

    @Test
    void verifiedPrincipalsRejectUntrustedOrUnboundedIdentityMaterial() {
        FoundationVerifiedPrincipal.Bearer bearer =
            new FoundationVerifiedPrincipal.Bearer(TENANT_ID, "actor-1");
        FoundationVerifiedPrincipal.BrowserSession browser =
            new FoundationVerifiedPrincipal.BrowserSession(
                TENANT_ID, "actor-1", "session-1", 7, BINDING_DIGEST);

        assertThat(bearer.getName()).isEqualTo("actor-1");
        assertThat(browser.tenantId()).isEqualTo(TENANT_ID);
        assertThatThrownBy(() -> new FoundationVerifiedPrincipal.Bearer(null, "actor-1"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FoundationVerifiedPrincipal.Bearer(TENANT_ID, " "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FoundationVerifiedPrincipal.Bearer(
                TENANT_ID, "a".repeat(256)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FoundationVerifiedPrincipal.Bearer(
                TENANT_ID, "actor-\ud800"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FoundationVerifiedPrincipal.BrowserSession(
                TENANT_ID, "actor-1", " ", 1, BINDING_DIGEST))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FoundationVerifiedPrincipal.BrowserSession(
                TENANT_ID, "actor-1", "session-1", 0, BINDING_DIGEST))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FoundationVerifiedPrincipal.BrowserSession(
                TENANT_ID, "actor-1", "session-1", 1, "SHA256:" + "a".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyReadsOnlyVisibleHeaderValuesAndNormalizesTheMutationContract() {
        MockHttpServletRequest request = validRequest();
        UUID correlationId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        request.addHeader("X-Correlation-ID", correlationId.toString());
        request.addHeader("Accept", "Application/*; q=0.500");
        request.addHeader("Accept", "application/json;Q=1.000, */*;q=0");

        FoundationHttpRequestPolicy.ParsedRequest parsed = policy.parse(request);

        assertThat(parsed.correlationId()).isEqualTo(correlationId);
        assertThat(parsed.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(parsed.expectedVersion()).isEqualTo(new ExpectedVersion(0));
        assertThat(parsed.requestMediaType()).isEqualTo("application/json");
        assertThat(parsed.normalizedAccept())
            .isEqualTo("*/*;q=0,application/*;q=0.5,application/json");
    }

    @Test
    void absentAcceptSelectsApplicationJsonAndAbsentCorrelationCreatesAnId() {
        MockHttpServletRequest request = validRequest();

        FoundationHttpRequestPolicy.ParsedRequest first = policy.parse(request);
        FoundationHttpRequestPolicy.ParsedRequest second = policy.parse(request);

        assertThat(first.normalizedAccept()).isEqualTo("application/json");
        assertThat(first.correlationId()).isNotEqualTo(second.correlationId());
    }

    @Test
    void acceptByteLimitCountsVisibleValuesWithoutSyntheticSeparators() {
        MockHttpServletRequest request = validRequest();
        request.addHeader("Accept", "application/json");
        request.addHeader("Accept", "x/" + "a".repeat(2_030));

        assertThat(policy.parse(request).normalizedAccept())
            .contains("application/json", "x/");
    }

    @Test
    void exactOrApplicationWildcardQZeroOverridesLessSpecificWildcard() {
        MockHttpServletRequest exactZero = validRequest();
        exactZero.addHeader("Accept", "application/json;q=0, */*;q=1");
        assertPolicyFailure(exactZero, 406, "NOT_ACCEPTABLE", "Accept");

        MockHttpServletRequest applicationZero = validRequest();
        applicationZero.addHeader("Accept", "application/*;q=0, */*;q=1");
        assertPolicyFailure(applicationZero, 406, "NOT_ACCEPTABLE", "Accept");
    }

    @Test
    void malformedOrOverLimitAcceptIsRequestInvalidWhileValidNonMatchesAreNotAcceptable() {
        for (String value : new String[] {
            "application/json, Application/Json;q=0.5",
            "application/json;level=1",
            "application/json;q=0.1234",
            "application/json;q=1.001",
            "application/json;q=0;q=1",
            "application/json,"
        }) {
            MockHttpServletRequest request = validRequest();
            request.addHeader("Accept", value);
            assertPolicyFailure(request, 400, "REQUEST_INVALID", "Accept");
        }

        MockHttpServletRequest nonMatch = validRequest();
        nonMatch.addHeader("Accept", "text/plain");
        assertPolicyFailure(nonMatch, 406, "NOT_ACCEPTABLE", "Accept");

        MockHttpServletRequest tooMany = validRequest();
        tooMany.addHeader("Accept", String.join(",", java.util.Collections.nCopies(
            17, "text/plain;q=0.1")));
        assertPolicyFailure(tooMany, 400, "REQUEST_INVALID", "Accept");

        MockHttpServletRequest tooLarge = validRequest();
        tooLarge.addHeader("Accept", "x/" + "a".repeat(2048));
        assertPolicyFailure(tooLarge, 400, "REQUEST_INVALID", "Accept");
    }

    @Test
    void earlyProtocolPrecedenceIsStableAcrossAdjacentFailures() {
        MockHttpServletRequest shapeBeforeMedia = validRequest();
        shapeBeforeMedia.addHeader("X-Correlation-ID", UUID.randomUUID().toString());
        shapeBeforeMedia.addHeader("X-Correlation-ID", UUID.randomUUID().toString());
        shapeBeforeMedia.removeHeader("Content-Type");
        assertPolicyFailure(shapeBeforeMedia, 400, "REQUEST_INVALID", "X-Correlation-ID");

        MockHttpServletRequest mediaBeforeAccept = validRequest();
        mediaBeforeAccept.removeHeader("Content-Type");
        mediaBeforeAccept.addHeader("Accept", "broken");
        assertPolicyFailure(mediaBeforeAccept, 415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type");

        MockHttpServletRequest acceptBeforeIdempotency = validRequest();
        acceptBeforeIdempotency.addHeader("Accept", "broken");
        acceptBeforeIdempotency.removeHeader("Idempotency-Key");
        assertPolicyFailure(acceptBeforeIdempotency, 400, "REQUEST_INVALID", "Accept");

        MockHttpServletRequest idempotencyBeforeVersion = validRequest();
        idempotencyBeforeVersion.removeHeader("Idempotency-Key");
        idempotencyBeforeVersion.removeHeader("If-Match");
        assertPolicyFailure(idempotencyBeforeVersion, 400, "REQUEST_INVALID", "Idempotency-Key");
    }

    @Test
    void malformedIfMatchPrecedesRawBodyAndJsonFaults() {
        Map<String, byte[]> bodyFaults = Map.of(
            "malformed UTF-8",
            new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '(', '"', '}'},
            "duplicate request key",
            ("{\"schema_id\":\"https://schemas.accord.inforvans.com/events/"
                + "domain-event/1-0-0\",\"document\":{},\"document\":{}}")
                .getBytes(UTF_8),
            "unknown request field",
            ("{\"schema_id\":\"https://schemas.accord.inforvans.com/events/"
                + "domain-event/1-0-0\",\"document\":{},\"unknown\":true}")
                .getBytes(UTF_8));

        bodyFaults.forEach((name, rawBody) -> {
            MockHttpServletRequest request = validRequest();
            request.removeHeader("If-Match");
            request.addHeader("If-Match", "W/\"0\"");
            int[] downstreamStageEntries = new int[2];

            assertThatThrownBy(() -> {
                policy.parse(request);
                downstreamStageEntries[0]++;
                json.decode(rawBody);
                downstreamStageEntries[1]++;
            })
                .as(name)
                .isInstanceOf(FoundationHttpRequestPolicy.PolicyFailure.class)
                .satisfies(error -> {
                    FoundationHttpRequestPolicy.PolicyFailure failure =
                        (FoundationHttpRequestPolicy.PolicyFailure) error;
                    assertThat(failure.status()).isEqualTo(400);
                    assertThat(failure.code()).isEqualTo("REQUEST_INVALID");
                    assertThat(failure).hasMessageContaining("If-Match");
                });
            assertThat(downstreamStageEntries)
                .as(name + " [JSON decode, command claim]")
                .containsExactly(0, 0);
        });
    }

    @Test
    void contentTypeIdempotencyVersionQueryAndSingletonShapesAreStrict() {
        for (String mediaType : new String[] {
            "application/json;charset=utf-8", "application/problem+json", "text/json",
            "application/json,application/json"
        }) {
            MockHttpServletRequest request = validRequest();
            request.removeHeader("Content-Type");
            request.addHeader("Content-Type", mediaType);
            assertPolicyFailure(request, 415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type");
        }

        for (String version : new String[] {
            "0", "W/\"0\"", "*", "\"01\"", "\"+1\"", "\"1\",\"2\"",
            " \"1\"", "\"9223372036854775808\""
        }) {
            MockHttpServletRequest request = validRequest();
            request.removeHeader("If-Match");
            request.addHeader("If-Match", version);
            assertPolicyFailure(request, 400, "REQUEST_INVALID", "If-Match");
        }

        MockHttpServletRequest duplicateContentType = validRequest();
        assertPolicyFailure(visibleHeaders(
            duplicateContentType,
            "Content-Type",
            List.of("application/json", "application/json")),
            400, "REQUEST_INVALID", "Content-Type");

        MockHttpServletRequest duplicateIdempotency = validRequest();
        duplicateIdempotency.addHeader("Idempotency-Key", "validation-key-0002");
        assertPolicyFailure(duplicateIdempotency, 400, "REQUEST_INVALID", "Idempotency-Key");

        MockHttpServletRequest duplicateVersion = validRequest();
        duplicateVersion.addHeader("If-Match", "\"1\"");
        assertPolicyFailure(duplicateVersion, 400, "REQUEST_INVALID", "If-Match");

        MockHttpServletRequest invalidIdempotency = validRequest();
        invalidIdempotency.removeHeader("Idempotency-Key");
        invalidIdempotency.addHeader("Idempotency-Key", "short,key");
        assertPolicyFailure(invalidIdempotency, 400, "REQUEST_INVALID", "Idempotency-Key");

        MockHttpServletRequest query = validRequest();
        query.setQueryString("ignored=true");
        assertPolicyFailure(query, 400, "REQUEST_INVALID", "query");
    }

    @Test
    void rawBodyDecodeUsesJcsDigestAndClosedRequestMapping() {
        byte[] first = ("{\"schema_id\":\"https://schemas.accord.inforvans.com/events/"
            + "domain-event/1-0-0\",\"document\":{\"b\":2,\"a\":1}}").getBytes(UTF_8);
        byte[] second = ("{ \"document\" : {\"a\":1,\"b\":2}, \"schema_id\" : "
            + "\"https://schemas.accord.inforvans.com/events/domain-event/1-0-0\" }")
            .getBytes(UTF_8);

        ContractValidationJson.DecodedRequest firstDecoded = json.decode(first);
        ContractValidationJson.DecodedRequest secondDecoded = json.decode(second);

        ContractValidationJson.DecodedRequest canonicalScalars = json.decode(validBody(
            "{\"number\":1.0,\"unicode\":\"\\u0061\"}"));
        ContractValidationJson.DecodedRequest equivalentScalars = json.decode(validBody(
            "{\"unicode\":\"a\",\"number\":1}"));

        assertThat(firstDecoded.bodyDigest()).isEqualTo(secondDecoded.bodyDigest());
        assertThat(firstDecoded.canonicalBody()).isEqualTo(secondDecoded.canonicalBody());
        assertThat(firstDecoded.schemaId()).isEqualTo(URI.create(
            "https://schemas.accord.inforvans.com/events/domain-event/1-0-0"));
        assertThat(firstDecoded.document()).isEqualTo(secondDecoded.document());
        assertThat(canonicalScalars.bodyDigest()).isEqualTo(equivalentScalars.bodyDigest());
        assertThat(canonicalScalars.document()).isEqualTo(equivalentScalars.document());
    }

    @Test
    void rawBodyRejectsInvalidJsonBeforeClosedSemanticDecode() {
        Map<String, ContractValidationJson.FailureKind> invalid = Map.ofEntries(
            Map.entry("", ContractValidationJson.FailureKind.JSON_INVALID),
            Map.entry("{\"schema_id\":1", ContractValidationJson.FailureKind.JSON_INVALID),
            Map.entry("{\"document\":{},\"document\":{}}",
                ContractValidationJson.FailureKind.JSON_INVALID),
            Map.entry("{} {}", ContractValidationJson.FailureKind.JSON_INVALID),
            Map.entry("[]", ContractValidationJson.FailureKind.REQUEST_INVALID),
            Map.entry("{}", ContractValidationJson.FailureKind.REQUEST_INVALID),
            Map.entry("{\"schema_id\":\"https://schemas.accord.inforvans.com/x\"}",
                ContractValidationJson.FailureKind.REQUEST_INVALID),
            Map.entry("{\"document\":{}}", ContractValidationJson.FailureKind.REQUEST_INVALID),
            Map.entry("{\"schema_id\":\"http://schemas.accord.inforvans.com/x\",\"document\":{}}",
                ContractValidationJson.FailureKind.REQUEST_INVALID),
            Map.entry("{\"schema_id\":\"https://other.example/x\",\"document\":{}}",
                ContractValidationJson.FailureKind.REQUEST_INVALID),
            Map.entry("{\"schema_id\":\"https://schemas.accord.inforvans.com/x\",\"document\":[]}",
                ContractValidationJson.FailureKind.REQUEST_INVALID),
            Map.entry("{\"schema_id\":\"https://schemas.accord.inforvans.com/x\",\"document\":{},"
                + "\"extra\":true}", ContractValidationJson.FailureKind.REQUEST_INVALID),
            Map.entry("{\"schema_id\":\"https://schemas.accord.inforvans.com/x\",\"document\":{"
                + "\"value\":\"\\ud800\"}}", ContractValidationJson.FailureKind.JSON_INVALID),
            Map.entry("{\"schema_id\":\"https://schemas.accord.inforvans.com/x\",\"document\":{"
                + "\"value\":\"\\ufffe\"}}", ContractValidationJson.FailureKind.JSON_INVALID)
        );
        invalid.forEach((body, expectedKind) -> assertThatThrownBy(
                () -> json.decode(body.getBytes(UTF_8)))
            .isInstanceOf(ContractValidationJson.JsonFailure.class)
            .satisfies(error -> assertThat(
                ((ContractValidationJson.JsonFailure) error).kind()).isEqualTo(expectedKind)));

        byte[] malformedUtf8 = {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '(', '"', '}'};
        assertThatThrownBy(() -> json.decode(malformedUtf8))
            .isInstanceOf(ContractValidationJson.JsonFailure.class)
            .satisfies(error -> assertThat(((ContractValidationJson.JsonFailure) error).kind())
                .isEqualTo(ContractValidationJson.FailureKind.JSON_INVALID));

        assertThatThrownBy(() -> json.decode(new byte[1_048_577]))
            .isInstanceOf(ContractValidationJson.JsonFailure.class)
            .satisfies(error -> assertThat(((ContractValidationJson.JsonFailure) error).kind())
                .isEqualTo(ContractValidationJson.FailureKind.REQUEST_INVALID));
    }

    @Test
    void fingerprintBindsSemanticFieldsButExcludesSessionCsrfAndCorrelation() {
        ContractValidationJson.DecodedRequest firstBody = json.decode(validBody("{\"b\":2,\"a\":1}"));
        ContractValidationJson.DecodedRequest equivalentBody =
            json.decode(validBody("{\"a\":1,\"b\":2}"));
        FoundationHttpRequestPolicy.ParsedRequest firstHeaders = parsedHeaders(null, "\"0\"");
        FoundationHttpRequestPolicy.ParsedRequest equivalentHeaders =
            parsedHeaders("application/json;q=1.000", "\"0\"");
        FoundationVerifiedPrincipal bearer =
            new FoundationVerifiedPrincipal.Bearer(TENANT_ID, "actor-1");
        FoundationVerifiedPrincipal browser = new FoundationVerifiedPrincipal.BrowserSession(
            TENANT_ID, "actor-1", "other-session", 99, "sha256:" + "b".repeat(64));

        String expected = fingerprints.contractValidation(
            bearer, VALIDATION_ID, firstHeaders, firstBody.bodyDigest());
        HttpIdempotencyFingerprint.Envelope envelope = fingerprints.contractValidationEnvelope(
            bearer, VALIDATION_ID, firstHeaders, firstBody.bodyDigest());

        assertThat(fingerprints.contractValidation(
            browser, VALIDATION_ID, equivalentHeaders, equivalentBody.bodyDigest()))
            .isEqualTo(expected);
        assertThat(expected).matches("^sha256:[0-9a-f]{64}$");
        assertThat(new String(fingerprints.canonicalEnvelope(envelope), UTF_8)).isEqualTo(
            "{\"accept\":\"application/json\",\"actor_id\":\"actor-1\","
                + "\"api_contract_version\":\"0.1.0\",\"body_digest\":\""
                + firstBody.bodyDigest() + "\",\"expected_version\":\"0\","
                + "\"http_method\":\"POST\",\"normalized_path\":\"/v1/contract-validations/"
                + VALIDATION_ID + "\",\"normalized_query\":\"\","
                + "\"request_media_type\":\"application/json\",\"resource_id\":\""
                + VALIDATION_ID + "\",\"route_key\":\"contract-validations.create\","
                + "\"tenant_id\":\"" + TENANT_ID + "\"}");
        assertThat(fingerprints.contractValidation(
            new FoundationVerifiedPrincipal.Bearer(TENANT_ID, "actor-2"),
            VALIDATION_ID, firstHeaders, firstBody.bodyDigest())).isNotEqualTo(expected);
        assertThat(fingerprints.contractValidation(
            new FoundationVerifiedPrincipal.Bearer(UUID.randomUUID(), "actor-1"),
            VALIDATION_ID, firstHeaders, firstBody.bodyDigest())).isNotEqualTo(expected);
        assertThat(fingerprints.contractValidation(
            bearer, UUID.randomUUID(), firstHeaders, firstBody.bodyDigest())).isNotEqualTo(expected);
        assertThat(fingerprints.contractValidation(
            bearer, VALIDATION_ID,
            parsedHeaders("application/json", "\"1\""),
            firstBody.bodyDigest())).isNotEqualTo(expected);
        assertThat(fingerprints.contractValidation(
            bearer, VALIDATION_ID,
            parsedHeaders("*/*", "\"0\""),
            firstBody.bodyDigest())).isNotEqualTo(expected);
        assertThat(new HttpIdempotencyFingerprint("0.1.1").contractValidation(
            bearer, VALIDATION_ID, firstHeaders, firstBody.bodyDigest())).isNotEqualTo(expected);
        assertThat(fingerprints.contractValidation(
            bearer, VALIDATION_ID, firstHeaders, "sha256:" + "f".repeat(64)))
            .isNotEqualTo(expected);

        Map<String, String> changedEnvelopeFields = Map.ofEntries(
            Map.entry("accept", "*/*"),
            Map.entry("actor_id", "actor-2"),
            Map.entry("api_contract_version", "0.1.1"),
            Map.entry("body_digest", "sha256:" + "f".repeat(64)),
            Map.entry("expected_version", "1"),
            Map.entry("http_method", "PUT"),
            Map.entry("normalized_path", "/v1/contract-validations/other"),
            Map.entry("normalized_query", "x=1"),
            Map.entry("request_media_type", "application/problem+json"),
            Map.entry("resource_id", UUID.randomUUID().toString()),
            Map.entry("route_key", "other.create"),
            Map.entry("tenant_id", UUID.randomUUID().toString())
        );
        changedEnvelopeFields.forEach((field, value) -> assertThat(
            fingerprints.fingerprint(replace(envelope, field, value)))
            .as(field)
            .isNotEqualTo(expected));
    }

    @Test
    void equivalentMultiValueAndCommaListAcceptProduceIdenticalFingerprints() {
        FoundationHttpRequestPolicy.ParsedRequest multiValue = policy.parse(visibleHeaders(
            validRequest(),
            "Accept",
            List.of(
                "Application/*; q=0.500",
                "application/json;Q=1.000, */*;q=0")));
        FoundationHttpRequestPolicy.ParsedRequest commaList = policy.parse(visibleHeaders(
            validRequest(),
            "Accept",
            List.of("*/*;Q=0.000, APPLICATION/JSON;q=1, application/*;Q=0.5")));
        FoundationHttpRequestPolicy.ParsedRequest changedQuality = policy.parse(visibleHeaders(
            validRequest(),
            "Accept",
            List.of("APPLICATION/JSON;q=1, */*;Q=0, application/*;q=0.4")));
        FoundationVerifiedPrincipal principal =
            new FoundationVerifiedPrincipal.Bearer(TENANT_ID, "actor-1");
        String bodyDigest = json.decode(validBody("{}")).bodyDigest();

        String expected = fingerprints.contractValidation(
            principal, VALIDATION_ID, multiValue, bodyDigest);

        assertThat(fingerprints.contractValidation(
            principal, VALIDATION_ID, commaList, bodyDigest)).isEqualTo(expected);
        assertThat(fingerprints.contractValidation(
            principal, VALIDATION_ID, changedQuality, bodyDigest)).isNotEqualTo(expected);
    }

    private void assertPolicyFailure(
            HttpServletRequest request, int status, String code, String messageFragment) {
        assertThatThrownBy(() -> policy.parse(request))
            .isInstanceOf(FoundationHttpRequestPolicy.PolicyFailure.class)
            .satisfies(error -> {
                FoundationHttpRequestPolicy.PolicyFailure failure =
                    (FoundationHttpRequestPolicy.PolicyFailure) error;
                assertThat(failure.status()).isEqualTo(status);
                assertThat(failure.code()).isEqualTo(code);
                assertThat(failure).hasMessageContaining(messageFragment);
            });
    }

    private static MockHttpServletRequest validRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST", "/v1/contract-validations/" + VALIDATION_ID);
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Idempotency-Key", IDEMPOTENCY_KEY);
        request.addHeader("If-Match", "\"0\"");
        return request;
    }

    private static byte[] validBody(String document) {
        return ("{\"schema_id\":\"https://schemas.accord.inforvans.com/events/"
            + "domain-event/1-0-0\",\"document\":" + document + "}").getBytes(UTF_8);
    }

    private FoundationHttpRequestPolicy.ParsedRequest parsedHeaders(
            String accept, String expectedVersion) {
        MockHttpServletRequest request = validRequest();
        request.removeHeader("If-Match");
        request.addHeader("If-Match", expectedVersion);
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        return policy.parse(request);
    }

    private static HttpServletRequest visibleHeaders(
            HttpServletRequest request, String name, List<String> values) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public Enumeration<String> getHeaders(String requestedName) {
                if (name.equalsIgnoreCase(requestedName)) {
                    return Collections.enumeration(values);
                }
                return super.getHeaders(requestedName);
            }
        };
    }

    private static HttpIdempotencyFingerprint.Envelope replace(
            HttpIdempotencyFingerprint.Envelope source, String field, String value) {
        return new HttpIdempotencyFingerprint.Envelope(
            field.equals("accept") ? value : source.accept(),
            field.equals("actor_id") ? value : source.actorId(),
            field.equals("api_contract_version") ? value : source.apiContractVersion(),
            field.equals("body_digest") ? value : source.bodyDigest(),
            field.equals("expected_version") ? value : source.expectedVersion(),
            field.equals("http_method") ? value : source.httpMethod(),
            field.equals("normalized_path") ? value : source.normalizedPath(),
            field.equals("normalized_query") ? value : source.normalizedQuery(),
            field.equals("request_media_type") ? value : source.requestMediaType(),
            field.equals("resource_id") ? value : source.resourceId(),
            field.equals("route_key") ? value : source.routeKey(),
            field.equals("tenant_id") ? value : source.tenantId());
    }
}
