package com.inforvans.accord.controlplane.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.inforvans.accord.ControlApiApplication;
import com.inforvans.accord.controlplane.http.FoundationHttpConfiguration.ContractValidationCommand;
import com.inforvans.accord.database.ControlPlaneTestRoles;
import com.inforvans.accord.platformkernel.CanonicalJson;
import com.inforvans.accord.reliability.Claim;
import com.inforvans.accord.reliability.CommandKey;
import com.inforvans.accord.reliability.ExpectedVersion;
import com.inforvans.accord.reliability.JooqCommandGate;
import com.inforvans.accord.reliability.StoredHttpResult;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.PathType;
import jakarta.servlet.http.Cookie;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("auxiliaryclass")
@SpringBootTest(
    classes = ControlApiApplication.class,
    properties = {
        "accord.http.api-contract-version=0.1.0",
        "accord.http.command-result-ttl=PT24H",
        "accord.process.instance-id=api-test-instance",
        "accord.security.allowed-origin=https://app.accord.test",
        "server.servlet.encoding.enabled=false",
        "spring.flyway.enabled=false"
    })
@AutoConfigureMockMvc
@Timeout(60)
class ContractValidationApiTest {
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302")
        .asCompatibleSubstituteFor("postgres");
    private static final PostgreSQLContainer<?> POSTGRES = migratedPostgres();
    private static final URI DOMAIN_SCHEMA = URI.create(
        "https://schemas.accord.inforvans.com/events/domain-event/1-0-0");
    private static final URI DRAFT_2020_12 =
        URI.create("https://json-schema.org/draft/2020-12/schema");
    private static final URI UNKNOWN_SCHEMA = URI.create(
        "https://schemas.accord.inforvans.com/events/unregistered/1-0-0");
    private static final UUID CORRELATION_ID =
        UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final String REQUEST_PATH =
        "/v1/contract-validations/20000000-0000-0000-0000-000000000001";
    private static final String OTHER_REQUEST_PATH =
        "/v1/contract-validations/20000000-0000-0000-0000-000000000002";
    private static final String CONTEXT_PATH = "/accord";
    private static final String ALLOWED_ORIGIN = "https://app.accord.test";
    private static final String CSRF_TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_CSRF_TOKEN = "fedcba9876543210fedcba9876543210";
    private static final String PLACEHOLDER_BODY =
        "{\"document_digest\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\""
        + ",\"valid\":true,\"validation_id\":\"20000000-0000-0000-0000-000000000001\""
        + ",\"version\":1}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private ContractValidationCommand command;

    @MockitoSpyBean
    private ContractValidationJson contractValidationJson;

    @MockitoSpyBean
    private JooqCommandGate commandGate;

    @MockitoSpyBean
    private FoundationTenantTransactions tenantTransactions;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> ControlPlaneTestRoles.API_LOGIN);
        registry.add("spring.datasource.password", () -> ControlPlaneTestRoles.API_PASSWORD);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET ROLE accord_api");
    }

    @BeforeEach
    void clearDurableRows() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                TRUNCATE TABLE contract_validation, outbox_event, domain_event,
                    aggregate_head, idempotency_result
                """);
        }
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bearer", "cookie", "tenant"})
    void rawCredentialsCannotCreateTrustedIdentity(String credentialKind) throws Exception {
        MockHttpServletRequestBuilder request = malformedSecurityRequest();
        switch (credentialKind) {
            case "bearer" -> request.header("Authorization", "Bearer raw-token");
            case "cookie" -> request.cookie(new Cookie("JSESSIONID", "raw-session"));
            case "tenant" -> request.header(
                "X-Tenant-ID", "10000000-0000-0000-0000-000000000001");
            default -> throw new IllegalArgumentException("unknown credential kind");
        }

        MvcResult result = mockMvc.perform(request).andReturn();

        assertNoDurableRows();
        assertProblem(result, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void ordinaryAuthenticatedPrincipalIsAuthorizationDeniedBeforeProtocolParsing()
            throws Exception {
        UsernamePasswordAuthenticationToken ordinary =
            new UsernamePasswordAuthenticationToken(
                "ordinary-principal", "credential", AuthorityUtils.NO_AUTHORITIES);

        MvcResult result = mockMvc.perform(
            malformedSecurityRequest().with(authentication(ordinary))).andReturn();

        assertNoDurableRows();
        assertProblem(result, "AUTHORIZATION_DENIED");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserCsrfFailure.class)
    void browserCsrfFailuresPrecedeMalformedProtocolAndCreateNoDurableRows(
            BrowserCsrfFailure failure) throws Exception {
        long sessionGeneration = failure == BrowserCsrfFailure.STALE_SESSION_GENERATION ? 8 : 7;
        FoundationVerifiedPrincipal.BrowserSession principal = browserSession(
            sessionGeneration, 7, CSRF_TOKEN);
        UsernamePasswordAuthenticationToken authenticated =
            new UsernamePasswordAuthenticationToken(
                principal, "credential", AuthorityUtils.NO_AUTHORITIES);

        MvcResult result = mockMvc.perform(
            browserCsrfRequest(failure).with(authentication(authenticated))).andReturn();

        assertNoDurableRows();
        assertProblem(result, "CSRF_VALIDATION_FAILED");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TrustedChannel.class)
    void trustedChannelsReachTheRouteAdapterAndReturnExactBytes(TrustedChannel channel)
            throws Exception {
        doReturn(placeholderResult()).when(command).execute(
            any(), any(), any(), any(), any());

        MvcResult result = mockMvc.perform(trustedRequest(
            channel, DOMAIN_SCHEMA, validDomainEvent())).andReturn();

        assertNoDurableRows();
        assertPlaceholder(result);
        ArgumentCaptor<FoundationVerifiedPrincipal> principal =
            ArgumentCaptor.forClass(FoundationVerifiedPrincipal.class);
        verify(command).execute(
            principal.capture(),
            eq(UUID.fromString(REQUEST_PATH.substring(REQUEST_PATH.lastIndexOf('/') + 1))),
            any(FoundationHttpRequestPolicy.ParsedRequest.class),
            any(ContractValidationJson.DecodedRequest.class),
            any(String.class));
        assertThat(principal.getValue()).isInstanceOf(channel.principalType());
    }

    @Test
    void trustedResponsePreservesSupplementaryUnicodeAsStrictUtf8Bytes()
            throws Exception {
        String expectedBody = "{\"destination\":\""
            + Character.toString(0x1F680) + "\"}";
        doReturn(new StoredHttpResult(
            202,
            Map.of("Content-Type", "application/json"),
            expectedBody)).when(command).execute(
                any(), any(), any(), any(), any());

        MvcResult result = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, validDomainEvent())).andReturn();

        assertNoDurableRows();
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        assertThat(result.getResponse().getContentAsByteArray())
            .isEqualTo(expectedBody.getBytes(UTF_8));
        verify(command, times(1)).execute(
            any(FoundationVerifiedPrincipal.class),
            any(UUID.class),
            any(FoundationHttpRequestPolicy.ParsedRequest.class),
            any(ContractValidationJson.DecodedRequest.class),
            any(String.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DeferredSchemaCase.class)
    void schemaLookupAndEvaluationRemainBehindTheRouteAdapter(DeferredSchemaCase schemaCase)
            throws Exception {
        doReturn(placeholderResult()).when(command).execute(
            any(), any(), any(), any(), any());
        URI schemaId = schemaCase == DeferredSchemaCase.UNKNOWN_SCHEMA
            ? UNKNOWN_SCHEMA
            : DOMAIN_SCHEMA;

        MvcResult result = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, schemaId, mapper.createObjectNode())).andReturn();

        assertNoDurableRows();
        assertPlaceholder(result);
        ArgumentCaptor<ContractValidationJson.DecodedRequest> decoded =
            ArgumentCaptor.forClass(ContractValidationJson.DecodedRequest.class);
        verify(command).execute(
            any(FoundationVerifiedPrincipal.class),
            any(UUID.class),
            any(FoundationHttpRequestPolicy.ParsedRequest.class),
            decoded.capture(),
            any(String.class));
        assertThat(decoded.getValue().schemaId()).isEqualTo(schemaId);
        assertThat(decoded.getValue().document()).isEqualTo(mapper.createObjectNode());
    }

    @Test
    void contextPathRouteStillRequiresAuthenticationBeforeTheAdapter() throws Exception {
        doReturn(placeholderResult()).when(command).execute(
            any(), any(), any(), any(), any());

        MvcResult result = mockMvc.perform(
            validContextPathRequest(validRequestBody()).with(csrf())).andReturn();

        assertNoDurableRows();
        assertProblem(
            result, "AUTHENTICATION_REQUIRED", null, CONTEXT_PATH + REQUEST_PATH);
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray())
            .path("correlation_id").textValue()).isNotEqualTo(CORRELATION_ID.toString());
        verifyNoInteractions(command);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TrustedChannel.class)
    void contextPathTrustedChannelsReachAdapterWithoutStockCsrf(TrustedChannel channel)
            throws Exception {
        doReturn(placeholderResult()).when(command).execute(
            any(), any(), any(), any(), any());

        MvcResult result = mockMvc.perform(authenticate(
            validContextPathRequest(validRequestBody()), channel)).andReturn();

        assertNoDurableRows();
        assertPlaceholder(result);
        verify(command).execute(
            any(FoundationVerifiedPrincipal.class),
            any(UUID.class),
            any(FoundationHttpRequestPolicy.ParsedRequest.class),
            any(ContractValidationJson.DecodedRequest.class),
            any(String.class));
    }

    @Test
    void createPersistsOneReliableResultAndIdenticalRetryReplaysItExactly() throws Exception {
        ObjectNode document = validDomainEvent();
        String documentDigest = CanonicalJson.sha256(mapper.writeValueAsBytes(document));
        String expectedBody = "{\"document_digest\":\"" + documentDigest
            + "\",\"valid\":true,\"validation_id\":\""
            + REQUEST_PATH.substring(REQUEST_PATH.lastIndexOf('/') + 1)
            + "\",\"version\":1}";
        MvcResult created = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, document)).andReturn();

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getContentType()).isEqualTo("application/json");
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(created.getResponse().getContentAsByteArray())
            .isEqualTo(expectedBody.getBytes(UTF_8));
        Map<String, Long> createdCounts = durableRowCounts();
        assertThat(createdCounts).containsExactly(
            Map.entry("contract_validation", 1L),
            Map.entry("aggregate_head", 1L),
            Map.entry("domain_event", 1L),
            Map.entry("outbox_event", 1L),
            Map.entry("idempotency_result", 1L));
        assertThat(singleString("SELECT state FROM idempotency_result"))
            .isEqualTo("COMPLETED");
        assertThat(mapper.readTree(singleString(
            "SELECT response_headers::text FROM idempotency_result")))
            .isEqualTo(mapper.valueToTree(Map.of(
                "content-type", "application/json", "etag", "\"1\"")));
        JsonNode eventPayload = mapper.readTree(singleString(
            "SELECT payload::text FROM domain_event"));
        assertThat(eventPayload.propertyStream().map(Map.Entry::getKey).toList())
            .containsExactlyInAnyOrder(
                "validation_id", "schema_id", "document_digest", "version");
        assertThat(eventPayload.path("validation_id").textValue()).isEqualTo(
            REQUEST_PATH.substring(REQUEST_PATH.lastIndexOf('/') + 1));
        assertThat(eventPayload.path("schema_id").textValue())
            .isEqualTo(DOMAIN_SCHEMA.toString());
        assertThat(eventPayload.path("document_digest").textValue())
            .isEqualTo(documentDigest);
        assertThat(eventPayload.path("version").longValue()).isEqualTo(1);
        assertThat(mapper.readTree(singleString("SELECT payload::text FROM outbox_event")))
            .isEqualTo(eventPayload);
        assertThat(singleString("SELECT event_type FROM domain_event"))
            .isEqualTo("contract-validation.completed");
        assertThat(singleString("SELECT schema_version FROM domain_event"))
            .isEqualTo("1.0.0");
        assertThat(singleLong("SELECT sequence FROM domain_event")).isEqualTo(1);
        assertThat(singleString("SELECT actor_id FROM domain_event"))
            .isEqualTo("actor-1");
        assertThat(singleString("SELECT correlation_id::text FROM domain_event"))
            .isEqualTo(CORRELATION_ID.toString());
        assertThat(singleString("SELECT causation_id::text FROM domain_event"))
            .isEqualTo(CORRELATION_ID.toString());
        assertThat(singleString("SELECT destination FROM outbox_event"))
            .isEqualTo("contract-validations");
        assertThat(singleString("SELECT payload_schema FROM outbox_event"))
            .isEqualTo("contract-validation.completed/1.0.0");

        MvcResult replayed = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, validDomainEvent())).andReturn();

        assertThat(replayed.getResponse().getStatus())
            .isEqualTo(created.getResponse().getStatus());
        assertThat(replayed.getResponse().getHeader("Content-Type"))
            .isEqualTo(created.getResponse().getHeader("Content-Type"));
        assertThat(replayed.getResponse().getHeader("ETag"))
            .isEqualTo(created.getResponse().getHeader("ETag"));
        assertThat(replayed.getResponse().getContentAsByteArray())
            .isEqualTo(created.getResponse().getContentAsByteArray());
        assertThat(durableRowCounts()).isEqualTo(createdCounts);
    }

    @Test
    void maximumVersionRemainsExactAcrossResponseReplayAndReliablePersistence()
            throws Exception {
        UUID validationId = UUID.fromString(
            REQUEST_PATH.substring(REQUEST_PATH.lastIndexOf('/') + 1));
        long expectedVersion = Long.MAX_VALUE - 1;
        seedContractValidation(validationId, expectedVersion);
        ObjectNode document = validDomainEvent();
        String documentDigest = CanonicalJson.sha256(mapper.writeValueAsBytes(document));
        String expectedBody = "{\"document_digest\":\"" + documentDigest
            + "\",\"valid\":true,\"validation_id\":\"" + validationId
            + "\",\"version\":" + Long.MAX_VALUE + "}";

        MvcResult created = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER,
            DOMAIN_SCHEMA,
            document,
            "maximum-version-idempotency-key",
            expectedVersion)).andReturn();

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("ETag"))
            .isEqualTo("\"" + Long.MAX_VALUE + "\"");
        assertThat(created.getResponse().getContentAsString()).isEqualTo(expectedBody);
        JsonNode responseBody = mapper.readTree(created.getResponse().getContentAsByteArray());
        assertThat(responseBody.path("version").isIntegralNumber()).isTrue();
        assertThat(responseBody.path("version").bigIntegerValue())
            .isEqualTo(BigInteger.valueOf(Long.MAX_VALUE));

        assertThat(singleLong("SELECT version FROM aggregate_head"))
            .isEqualTo(Long.MAX_VALUE);
        assertThat(singleLong("SELECT version FROM contract_validation"))
            .isEqualTo(Long.MAX_VALUE);
        assertThat(singleLong("SELECT sequence FROM domain_event"))
            .isEqualTo(Long.MAX_VALUE);
        assertThat(singleLong("SELECT aggregate_version FROM idempotency_result"))
            .isEqualTo(Long.MAX_VALUE);
        assertThat(singleString("SELECT response_body FROM idempotency_result"))
            .isEqualTo(expectedBody);

        JsonNode eventPayload = mapper.readTree(singleString(
            "SELECT payload::text FROM domain_event"));
        JsonNode outboxPayload = mapper.readTree(singleString(
            "SELECT payload::text FROM outbox_event"));
        assertThat(eventPayload).isEqualTo(outboxPayload);
        assertThat(eventPayload.path("version").isIntegralNumber()).isTrue();
        assertThat(eventPayload.path("version").bigIntegerValue())
            .isEqualTo(BigInteger.valueOf(Long.MAX_VALUE));

        Map<String, Long> createdCounts = durableRowCounts();
        assertThat(createdCounts).containsExactly(
            Map.entry("contract_validation", 1L),
            Map.entry("aggregate_head", 1L),
            Map.entry("domain_event", 1L),
            Map.entry("outbox_event", 1L),
            Map.entry("idempotency_result", 1L));

        MvcResult replayed = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER,
            DOMAIN_SCHEMA,
            validDomainEvent(),
            "maximum-version-idempotency-key",
            expectedVersion)).andReturn();

        assertThat(replayed.getResponse().getStatus()).isEqualTo(201);
        assertThat(replayed.getResponse().getHeader("ETag"))
            .isEqualTo("\"" + Long.MAX_VALUE + "\"");
        assertThat(replayed.getResponse().getContentAsString()).isEqualTo(expectedBody);
        assertThat(durableRowCounts()).isEqualTo(createdCounts);
    }

    @Test
    void changedRequestUnderCompletedKeyReturnsUnstoredConflict() throws Exception {
        mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, validDomainEvent())).andReturn();
        Map<String, Long> completedCounts = durableRowCounts();
        ObjectNode changed = validDomainEvent();
        ((ObjectNode) changed.path("payload")).put("changed", true);

        MvcResult conflict = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, changed)).andReturn();

        assertProblem(conflict, "IDEMPOTENCY_KEY_REUSED", CORRELATION_ID);
        assertThat(new String(conflict.getResponse().getContentAsByteArray(), UTF_8))
            .doesNotContain("sha256:", "request_fingerprint");
        assertThat(durableRowCounts()).isEqualTo(completedCounts);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IdempotencyMismatch.class)
    void completedKeyRejectsEveryOtherFingerprintedRequestWithoutAnotherWrite(
            IdempotencyMismatch mismatch) throws Exception {
        ObjectNode document = validDomainEvent();
        mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, document)).andReturn();
        Map<String, Long> completedCounts = durableRowCounts();

        String requestPath = mismatch == IdempotencyMismatch.RESOURCE_ID
            ? OTHER_REQUEST_PATH
            : REQUEST_PATH;
        long expectedVersion = mismatch == IdempotencyMismatch.EXPECTED_VERSION ? 1 : 0;
        String accept = mismatch == IdempotencyMismatch.ACCEPT ? "*/*" : "application/json";
        MvcResult conflict = mockMvc.perform(trustedBearerRequest(
            TENANT_ID,
            "actor-1",
            requestPath,
            DOMAIN_SCHEMA,
            document,
            "idempotency-key-0001",
            expectedVersion,
            accept)).andReturn();

        assertProblem(conflict, "IDEMPOTENCY_KEY_REUSED", CORRELATION_ID, requestPath);
        assertThat(new String(conflict.getResponse().getContentAsByteArray(), UTF_8))
            .doesNotContain("sha256:", "request_fingerprint");
        assertThat(durableRowCounts()).isEqualTo(completedCounts);
    }

    @Test
    void sameKeyInAnotherTenantExecutesAnIndependentCommand() throws Exception {
        ObjectNode document = validDomainEvent();
        MvcResult first = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, document)).andReturn();

        MvcResult otherTenant = mockMvc.perform(trustedBearerRequest(
            OTHER_TENANT_ID,
            "actor-1",
            REQUEST_PATH,
            DOMAIN_SCHEMA,
            document,
            "idempotency-key-0001",
            0,
            "application/json")).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(otherTenant.getResponse().getStatus()).isEqualTo(201);
        assertThat(durableRowCounts()).containsExactly(
            Map.entry("contract_validation", 2L),
            Map.entry("aggregate_head", 2L),
            Map.entry("domain_event", 2L),
            Map.entry("outbox_event", 2L),
            Map.entry("idempotency_result", 2L));
        assertThat(singleLong("SELECT count(DISTINCT tenant_id) FROM idempotency_result"))
            .isEqualTo(2);
        assertThat(singleLong("SELECT count(DISTINCT tenant_id) FROM contract_validation"))
            .isEqualTo(2);
    }

    @Test
    void sameKeyForAnotherActorOwnsAnIndependentStoredOutcome() throws Exception {
        ObjectNode document = validDomainEvent();
        MvcResult first = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, document)).andReturn();
        ObjectNode updated = validDomainEvent();
        ((ObjectNode) updated.path("payload")).put("actor_revision", 2);

        MvcResult otherActor = mockMvc.perform(trustedBearerRequest(
            TENANT_ID,
            "actor-2",
            REQUEST_PATH,
            DOMAIN_SCHEMA,
            updated,
            "idempotency-key-0001",
            1,
            "application/json")).andReturn();

        assertThat(otherActor.getResponse().getStatus()).isEqualTo(201);
        assertThat(otherActor.getResponse().getHeader("ETag")).isEqualTo("\"2\"");
        assertThat(otherActor.getResponse().getContentAsByteArray())
            .isNotEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(durableRowCounts()).containsExactly(
            Map.entry("contract_validation", 1L),
            Map.entry("aggregate_head", 1L),
            Map.entry("domain_event", 2L),
            Map.entry("outbox_event", 2L),
            Map.entry("idempotency_result", 2L));
        assertThat(singleLong("SELECT count(DISTINCT actor_id) FROM idempotency_result"))
            .isEqualTo(2);
        assertThat(singleLong("""
            SELECT count(*) FROM idempotency_result
            WHERE actor_id='actor-2' AND response_status=201
            """)).isEqualTo(1);
    }

    @Test
    void matchingLiveClaimReturnsUnstoredBoundedInProgressProblem() throws Exception {
        byte[] rawBody = validRequestBody();
        FoundationVerifiedPrincipal principal =
            new FoundationVerifiedPrincipal.Bearer(TENANT_ID, "actor-1");
        FoundationHttpRequestPolicy.ParsedRequest parsed =
            new FoundationHttpRequestPolicy.ParsedRequest(
                CORRELATION_ID,
                REQUEST_PATH,
                new FoundationMutationHeaders(
                    "idempotency-key-0001",
                    new ExpectedVersion(0),
                    "application/json",
                    "application/json"));
        String fingerprint = new HttpIdempotencyFingerprint("0.1.0")
            .contractValidation(
                principal,
                UUID.fromString(REQUEST_PATH.substring(REQUEST_PATH.lastIndexOf('/') + 1)),
                parsed,
                CanonicalJson.sha256(rawBody));
        insertLiveClaim(fingerprint);
        Map<String, Long> startedCounts = durableRowCounts();

        MvcResult inProgress = mockMvc.perform(validProtocolRequest(
            TrustedChannel.BEARER, rawBody)).andReturn();

        assertProblem(
            inProgress,
            "COMMAND_IN_PROGRESS",
            CORRELATION_ID,
            REQUEST_PATH,
            Map.of("retry_after", 1));
        assertThat(durableRowCounts()).isEqualTo(startedCounts);
        assertThat(singleString("SELECT state FROM idempotency_result"))
            .isEqualTo("STARTED");
    }

    @Test
    void unknownSchemaIsStoredDetachedAndReplayedExactly() throws Exception {
        assertStoredRejectionAndReplay(
            UNKNOWN_SCHEMA,
            validDomainEvent(),
            "SCHEMA_NOT_FOUND",
            Map.of());
    }

    @Test
    void storedSchemaRejectionStillReplaysAfterUnrelatedAggregateAdvances()
            throws Exception {
        ObjectNode document = validDomainEvent();
        MvcResult rejected = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, UNKNOWN_SCHEMA, document)).andReturn();
        assertProblem(rejected, "SCHEMA_NOT_FOUND", CORRELATION_ID);

        MvcResult unrelated = mockMvc.perform(trustedBearerRequest(
            TENANT_ID,
            "actor-1",
            OTHER_REQUEST_PATH,
            DOMAIN_SCHEMA,
            document,
            "idempotency-key-0002",
            0,
            "application/json")).andReturn();
        assertThat(unrelated.getResponse().getStatus()).isEqualTo(201);

        MvcResult replayed = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, UNKNOWN_SCHEMA, document)).andReturn();

        assertThat(replayed.getResponse().getStatus())
            .isEqualTo(rejected.getResponse().getStatus());
        assertThat(replayed.getResponse().getHeader("Content-Type"))
            .isEqualTo(rejected.getResponse().getHeader("Content-Type"));
        assertThat(replayed.getResponse().getContentAsByteArray())
            .isEqualTo(rejected.getResponse().getContentAsByteArray());
        assertThat(durableRowCounts()).containsExactly(
            Map.entry("contract_validation", 1L),
            Map.entry("aggregate_head", 1L),
            Map.entry("domain_event", 1L),
            Map.entry("outbox_event", 1L),
            Map.entry("idempotency_result", 2L));
    }

    @Test
    void invalidDocumentIsStoredDetachedAndReplayedExactly() throws Exception {
        ObjectNode invalid = validDomainEvent();
        invalid.put("event_id", "not-a-uuid");
        assertStoredRejectionAndReplay(
            DOMAIN_SCHEMA,
            invalid,
            "DOCUMENT_SCHEMA_INVALID",
            Map.of(
                "errors",
                List.of(Map.of("field", "/event_id", "reason", "SCHEMA_FORMAT"))));
    }

    @Test
    void contextPathStoredRejectionRetainsActualInstanceOnReplay() throws Exception {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("schema_id", UNKNOWN_SCHEMA.toString());
        requestBody.set("document", validDomainEvent());
        byte[] rawBody = mapper.writeValueAsBytes(requestBody);

        MvcResult rejected = mockMvc.perform(authenticate(
            validContextPathRequest(rawBody), TrustedChannel.BEARER)).andReturn();

        assertProblem(
            rejected,
            "SCHEMA_NOT_FOUND",
            CORRELATION_ID,
            CONTEXT_PATH + REQUEST_PATH,
            Map.of());
        Map<String, Long> rejectedCounts = durableRowCounts();
        assertThat(rejectedCounts).containsExactly(
            Map.entry("contract_validation", 0L),
            Map.entry("aggregate_head", 0L),
            Map.entry("domain_event", 0L),
            Map.entry("outbox_event", 0L),
            Map.entry("idempotency_result", 1L));

        MvcResult replayed = mockMvc.perform(authenticate(
            validContextPathRequest(rawBody), TrustedChannel.BEARER)).andReturn();

        assertThat(replayed.getResponse().getStatus())
            .isEqualTo(rejected.getResponse().getStatus());
        assertThat(replayed.getResponse().getContentAsByteArray())
            .isEqualTo(rejected.getResponse().getContentAsByteArray());
        assertThat(durableRowCounts()).isEqualTo(rejectedCounts);
    }

    @Test
    void exactPriorVersionUpdatesOneValidationAndAppendsOneReliableEvent() throws Exception {
        mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, validDomainEvent())).andReturn();
        ObjectNode updated = validDomainEvent();
        ((ObjectNode) updated.path("payload")).put("revision", 2);
        String documentDigest = CanonicalJson.sha256(mapper.writeValueAsBytes(updated));
        String expectedBody = "{\"document_digest\":\"" + documentDigest
            + "\",\"valid\":true,\"validation_id\":\""
            + REQUEST_PATH.substring(REQUEST_PATH.lastIndexOf('/') + 1)
            + "\",\"version\":2}";

        MvcResult result = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER,
            DOMAIN_SCHEMA,
            updated,
            "idempotency-key-0002",
            1)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        assertThat(result.getResponse().getHeader("ETag")).isEqualTo("\"2\"");
        assertThat(result.getResponse().getContentAsByteArray())
            .isEqualTo(expectedBody.getBytes(UTF_8));
        assertThat(durableRowCounts()).containsExactly(
            Map.entry("contract_validation", 1L),
            Map.entry("aggregate_head", 1L),
            Map.entry("domain_event", 2L),
            Map.entry("outbox_event", 2L),
            Map.entry("idempotency_result", 2L));
        assertThat(singleLong("SELECT version FROM contract_validation")).isEqualTo(2);
        assertThat(singleLong("SELECT version FROM aggregate_head")).isEqualTo(2);
        assertThat(singleString("SELECT document_digest FROM contract_validation"))
            .isEqualTo(documentDigest);
    }

    @Test
    void positiveVersionAgainstMissingValidationIsStoredAndReplayedAsNotFound()
            throws Exception {
        assertStoredRejectionAndReplay(
            DOMAIN_SCHEMA,
            validDomainEvent(),
            "idempotency-key-0001",
            1,
            "CONTRACT_VALIDATION_NOT_FOUND",
            Map.of());
    }

    @ParameterizedTest(name = "actualVersion={0}")
    @MethodSource("maximumVersionOutcomes")
    void maximumExpectedVersionStoresAndReplaysAClosedOutcome(
            Long actualVersion,
            String code,
            int status,
            Map<String, Object> extensions) throws Exception {
        UUID validationId = UUID.fromString(
            REQUEST_PATH.substring(REQUEST_PATH.lastIndexOf('/') + 1));
        if (actualVersion != null) {
            seedContractValidation(validationId, actualVersion);
        }
        String headUpdatedAt = actualVersion == null ? null : singleString(
            "SELECT updated_at::text FROM aggregate_head");
        String validationUpdatedAt = actualVersion == null ? null : singleString(
            "SELECT updated_at::text FROM contract_validation");
        ObjectNode document = validDomainEvent();

        MvcResult rejected = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER,
            DOMAIN_SCHEMA,
            document,
            "maximum-outcome-idempotency-key",
            Long.MAX_VALUE)).andReturn();

        assertProblem(rejected, code, CORRELATION_ID, REQUEST_PATH, extensions);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(status);
        long businessRows = actualVersion == null ? 0 : 1;
        Map<String, Long> rejectedCounts = durableRowCounts();
        assertThat(rejectedCounts).containsExactly(
            Map.entry("contract_validation", businessRows),
            Map.entry("aggregate_head", businessRows),
            Map.entry("domain_event", 0L),
            Map.entry("outbox_event", 0L),
            Map.entry("idempotency_result", 1L));
        assertThat(singleString("SELECT state FROM idempotency_result"))
            .isEqualTo("COMPLETED");
        assertThat(singleLong("SELECT response_status FROM idempotency_result"))
            .isEqualTo(status);
        assertThat(singleLong("""
            SELECT count(*) FROM idempotency_result
            WHERE aggregate_type IS NULL
              AND aggregate_id IS NULL
              AND aggregate_version IS NULL
            """)).isEqualTo(1);
        String storedBody = singleString(
            "SELECT response_body FROM idempotency_result");
        JsonNode storedHeaders = mapper.readTree(singleString(
            "SELECT response_headers::text FROM idempotency_result"));
        assertThat(storedBody.getBytes(UTF_8))
            .isEqualTo(rejected.getResponse().getContentAsByteArray());
        assertThat(storedHeaders).isEqualTo(mapper.valueToTree(Map.of(
            "content-type", rejected.getResponse().getHeader("Content-Type"))));
        assertThat(singleLong("""
            SELECT count(*) FROM idempotency_result WHERE state='STARTED'
            """)).isZero();
        if (actualVersion != null) {
            assertThat(singleLong("SELECT version FROM aggregate_head"))
                .isEqualTo(actualVersion);
            assertThat(singleLong("SELECT version FROM contract_validation"))
                .isEqualTo(actualVersion);
            assertThat(singleString("SELECT updated_at::text FROM aggregate_head"))
                .isEqualTo(headUpdatedAt);
            assertThat(singleString("SELECT updated_at::text FROM contract_validation"))
                .isEqualTo(validationUpdatedAt);
        }

        MvcResult replayed = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER,
            DOMAIN_SCHEMA,
            validDomainEvent(),
            "maximum-outcome-idempotency-key",
            Long.MAX_VALUE)).andReturn();

        assertThat(replayed.getResponse().getStatus()).isEqualTo(status);
        assertThat(replayed.getResponse().getHeader("Content-Type"))
            .isEqualTo(storedHeaders.path("content-type").textValue());
        assertThat(replayed.getResponse().getContentAsByteArray())
            .isEqualTo(storedBody.getBytes(UTF_8));
        assertThat(durableRowCounts()).isEqualTo(rejectedCounts);
        if (actualVersion != null) {
            assertThat(singleLong("SELECT version FROM aggregate_head"))
                .isEqualTo(actualVersion);
            assertThat(singleLong("SELECT version FROM contract_validation"))
                .isEqualTo(actualVersion);
            assertThat(singleString("SELECT updated_at::text FROM aggregate_head"))
                .isEqualTo(headUpdatedAt);
            assertThat(singleString("SELECT updated_at::text FROM contract_validation"))
                .isEqualTo(validationUpdatedAt);
        }
    }

    @Test
    void staleVersionIsStoredAndReplayedWithoutChangingBusinessState() throws Exception {
        ObjectNode document = validDomainEvent();
        mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, document)).andReturn();

        MvcResult stale = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER,
            DOMAIN_SCHEMA,
            document,
            "idempotency-key-0002",
            0)).andReturn();

        assertProblem(
            stale,
            "VERSION_CONFLICT",
            CORRELATION_ID,
            REQUEST_PATH,
            Map.of("expected_version", 0, "actual_version", 1));
        Map<String, Long> staleCounts = durableRowCounts();
        assertThat(staleCounts).containsExactly(
            Map.entry("contract_validation", 1L),
            Map.entry("aggregate_head", 1L),
            Map.entry("domain_event", 1L),
            Map.entry("outbox_event", 1L),
            Map.entry("idempotency_result", 2L));
        assertThat(singleLong("""
            SELECT count(*) FROM idempotency_result
            WHERE aggregate_type IS NULL
              AND aggregate_id IS NULL
              AND aggregate_version IS NULL
            """)).isEqualTo(1);

        MvcResult replayed = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER,
            DOMAIN_SCHEMA,
            document,
            "idempotency-key-0002",
            0)).andReturn();

        assertThat(replayed.getResponse().getStatus())
            .isEqualTo(stale.getResponse().getStatus());
        assertThat(replayed.getResponse().getHeader("Content-Type"))
            .isEqualTo(stale.getResponse().getHeader("Content-Type"));
        assertThat(replayed.getResponse().getContentAsByteArray())
            .isEqualTo(stale.getResponse().getContentAsByteArray());
        assertThat(durableRowCounts()).isEqualTo(staleCounts);
    }

    @Test
    void distinctKeysRacingOneVersionProduceOneMutationAndStoredCasLosers()
            throws Exception {
        int contenderCount = 32;
        CountDownLatch ready = new CountDownLatch(contenderCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < contenderCount; index++) {
                String idempotencyKey = String.format(
                    java.util.Locale.ROOT, "idempotency-race-%04d", index);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("CAS race did not start");
                    }
                    return mockMvc.perform(trustedBearerRequest(
                        TENANT_ID,
                        "actor-1",
                        REQUEST_PATH,
                        DOMAIN_SCHEMA,
                        validDomainEvent(),
                        idempotencyKey,
                        0,
                        "application/json")).andReturn();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(result ->
                result.getResponse().getStatus() == 201).hasSize(1);
            List<MvcResult> rejected = results.stream()
                .filter(result -> result.getResponse().getStatus() == 412)
                .toList();
            assertThat(rejected).hasSize(contenderCount - 1);
            for (MvcResult result : rejected) {
                assertProblem(
                    result,
                    "VERSION_CONFLICT",
                    CORRELATION_ID,
                    REQUEST_PATH,
                    Map.of("expected_version", 0, "actual_version", 1));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(durableRowCounts()).containsExactly(
            Map.entry("contract_validation", 1L),
            Map.entry("aggregate_head", 1L),
            Map.entry("domain_event", 1L),
            Map.entry("outbox_event", 1L),
            Map.entry("idempotency_result", 32L));
        assertThat(singleLong("""
            SELECT count(*) FROM idempotency_result
            WHERE state='COMPLETED' AND response_status=412
            """)).isEqualTo(31);
        assertThat(singleLong("""
            SELECT count(*) FROM idempotency_result
            WHERE state='COMPLETED'
            """)).isEqualTo(32);
    }

    @Test
    void identicalKeyRaceExecutesAtMostOnceThenReplaysWinnerExactly() throws Exception {
        int contenderCount = 32;
        CountDownLatch ready = new CountDownLatch(contenderCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
        List<Future<MvcResult>> futures = new ArrayList<>();
        List<MvcResult> results = new ArrayList<>();
        try {
            for (int index = 0; index < contenderCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("idempotency race did not start");
                    }
                    return mockMvc.perform(trustedRequest(
                        TrustedChannel.BEARER,
                        DOMAIN_SCHEMA,
                        validDomainEvent())).andReturn();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).allMatch(result -> Set.of(201, 409).contains(
            result.getResponse().getStatus()));
        for (MvcResult inProgress : results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .toList()) {
            assertProblem(
                inProgress,
                "COMMAND_IN_PROGRESS",
                CORRELATION_ID,
                REQUEST_PATH,
                Map.of("retry_after", 1));
        }
        MvcResult winner = results.stream()
            .filter(result -> result.getResponse().getStatus() == 201)
            .findFirst()
            .orElseThrow();
        assertThat(durableRowCounts()).containsExactly(
            Map.entry("contract_validation", 1L),
            Map.entry("aggregate_head", 1L),
            Map.entry("domain_event", 1L),
            Map.entry("outbox_event", 1L),
            Map.entry("idempotency_result", 1L));

        MvcResult replayed = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, validDomainEvent())).andReturn();

        assertThat(replayed.getResponse().getStatus()).isEqualTo(201);
        assertThat(replayed.getResponse().getHeader("Content-Type"))
            .isEqualTo(winner.getResponse().getHeader("Content-Type"));
        assertThat(replayed.getResponse().getHeader("ETag"))
            .isEqualTo(winner.getResponse().getHeader("ETag"));
        assertThat(replayed.getResponse().getContentAsByteArray())
            .isEqualTo(winner.getResponse().getContentAsByteArray());
        assertThat(durableRowCounts().get("idempotency_result")).isEqualTo(1);
    }

    @Test
    void schemaEvaluationRunsAfterStartedClaimCommitsAndBeforeBusinessTransaction()
            throws Exception {
        CountDownLatch evaluationStarted = new CountDownLatch(1);
        CountDownLatch releaseEvaluation = new CountDownLatch(1);
        blockSchemaEvaluation(evaluationStarted, releaseEvaluation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> execution = executor.submit(() -> mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, validDomainEvent())).andReturn());
        try {
            assertThat(evaluationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(durableRowCounts()).containsExactly(
                Map.entry("contract_validation", 0L),
                Map.entry("aggregate_head", 0L),
                Map.entry("domain_event", 0L),
                Map.entry("outbox_event", 0L),
                Map.entry("idempotency_result", 1L));
            assertThat(singleString("SELECT state FROM idempotency_result"))
                .isEqualTo("STARTED");

            releaseEvaluation.countDown();
            MvcResult result = execution.get(20, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
        } finally {
            releaseEvaluation.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(durableRowCounts()).containsExactly(
            Map.entry("contract_validation", 1L),
            Map.entry("aggregate_head", 1L),
            Map.entry("domain_event", 1L),
            Map.entry("outbox_event", 1L),
            Map.entry("idempotency_result", 1L));
        verify(tenantTransactions, times(2)).write(eq(TENANT_ID), any());
    }

    @Test
    void naturallyExpiredClaimRollsBackTheEntireBusinessTransaction() throws Exception {
        useShortClaimLease();
        CountDownLatch evaluationStarted = new CountDownLatch(1);
        CountDownLatch releaseEvaluation = new CountDownLatch(1);
        blockSchemaEvaluation(evaluationStarted, releaseEvaluation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> execution = executor.submit(() -> mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER, DOMAIN_SCHEMA, validDomainEvent())).andReturn());
        try {
            assertThat(evaluationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitClaimExpiry();
            releaseEvaluation.countDown();
            assertLostFence(execution);
        } finally {
            releaseEvaluation.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(durableRowCounts()).containsExactly(
            Map.entry("contract_validation", 0L),
            Map.entry("aggregate_head", 0L),
            Map.entry("domain_event", 0L),
            Map.entry("outbox_event", 0L),
            Map.entry("idempotency_result", 1L));
        assertThat(singleString("SELECT state FROM idempotency_result"))
            .isEqualTo("STARTED");
        assertThat(singleString("SELECT claim_owner FROM idempotency_result"))
            .isEqualTo("api-test-instance");
        assertThat(singleLong("""
            SELECT count(*) FROM idempotency_result
            WHERE lease_until <= clock_timestamp()
            """)).isEqualTo(1);
    }

    @Test
    void realTakeoverAfterExpiryRollsBackTheOriginalBusinessTransaction()
            throws Exception {
        useShortClaimLease();
        CountDownLatch evaluationStarted = new CountDownLatch(1);
        CountDownLatch releaseEvaluation = new CountDownLatch(1);
        blockSchemaEvaluation(evaluationStarted, releaseEvaluation);
        byte[] rawBody = validRequestBody();
        Future<MvcResult> execution;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        execution = executor.submit(() -> mockMvc.perform(validProtocolRequest(
            TrustedChannel.BEARER, rawBody)).andReturn());
        try {
            assertThat(evaluationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitClaimExpiry();
            Claim takeover = tenantTransactions.write(TENANT_ID, tx ->
                new JooqCommandGate().claim(
                    tx,
                    new CommandKey(
                        TENANT_ID,
                        "actor-1",
                        "contract-validations.create",
                        "idempotency-key-0001"),
                    requestFingerprint(rawBody),
                    "takeover-instance",
                    Duration.ofMinutes(2)));
            assertThat(takeover).isInstanceOf(Claim.Acquired.class);
            Claim.Acquired acquired = (Claim.Acquired) takeover;
            assertThat(acquired.lease().owner()).isEqualTo("takeover-instance");
            assertThat(acquired.lease().generation()).isEqualTo(2);

            releaseEvaluation.countDown();
            assertLostFence(execution);
        } finally {
            releaseEvaluation.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(durableRowCounts()).containsExactly(
            Map.entry("contract_validation", 0L),
            Map.entry("aggregate_head", 0L),
            Map.entry("domain_event", 0L),
            Map.entry("outbox_event", 0L),
            Map.entry("idempotency_result", 1L));
        assertThat(singleString("SELECT state FROM idempotency_result"))
            .isEqualTo("STARTED");
        assertThat(singleString("SELECT claim_owner FROM idempotency_result"))
            .isEqualTo("takeover-instance");
        assertThat(singleLong("SELECT claim_generation FROM idempotency_result"))
            .isEqualTo(2);
    }

    @Test
    void inconsistentContextPathFailsClosed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContextPath(CONTEXT_PATH);
        request.setRequestURI(REQUEST_PATH);

        assertThatThrownBy(() -> FoundationHttpSecurity.FOUNDATION_MUTATION.matches(request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "validationId={0}")
    @ValueSource(strings = {
        "not-a-uuid",
        "1-1-1-1-1",
        "20000000-0000-0000-0000-00000000000A",
        "20000000-0000-0000-0000-1"
    })
    void nonCanonicalValidationIdsReturnCanonicalRequestInvalidBeforeTheRouteAdapter(
            String rawValidationId) throws Exception {
        String requestPath = validationPath(rawValidationId);
        MvcResult result = mockMvc.perform(authenticate(
            validationIdRequest(rawValidationId), TrustedChannel.BEARER)).andReturn();

        assertNoDurableRows();
        assertProblem(
            result, "REQUEST_INVALID", null, requestPath);
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray())
            .path("correlation_id").textValue()).isNotEqualTo(CORRELATION_ID.toString());
        verifyNoInteractions(command);
    }

    @Test
    void authenticationPrecedesNonCanonicalValidationIdPolicy() throws Exception {
        String rawValidationId = "1-1-1-1-1";
        MvcResult result = mockMvc.perform(
            malformedProtocolOnValidationIdRequest(rawValidationId)).andReturn();

        assertNoDurableRows();
        assertProblem(
            result, "AUTHENTICATION_REQUIRED", null, validationPath(rawValidationId));
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray())
            .path("correlation_id").textValue()).isNotEqualTo("not-a-uuid");
        verifyNoInteractions(command);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("protocolFailureCases")
    void protocolFailuresReturnStableProblemsBeforeTheRouteAdapter(
            TrustedChannel channel, ProtocolFailure failure) throws Exception {
        MvcResult result = mockMvc.perform(protocolFailureRequest(channel, failure)).andReturn();

        assertNoDurableRows();
        assertProblem(
            result,
            failure.code(),
            failure.generatedCorrelation() ? null : CORRELATION_ID);
        verifyNoInteractions(command);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("jsonFailureCases")
    void jsonFailuresReturnStableProblemsBeforeTheRouteAdapter(
            TrustedChannel channel, JsonFailureCase failure) throws Exception {
        MvcResult result = mockMvc.perform(validProtocolRequest(
            channel, failure.rawBody())).andReturn();

        assertNoDurableRows();
        assertProblem(result, failure.code(), CORRELATION_ID);
        verifyNoInteractions(command);
    }

    static Stream<Arguments> protocolFailureCases() {
        return Arrays.stream(TrustedChannel.values()).flatMap(channel ->
            Arrays.stream(ProtocolFailure.values()).map(failure ->
                Arguments.of(channel, failure)));
    }

    static Stream<Arguments> jsonFailureCases() {
        return Arrays.stream(TrustedChannel.values()).flatMap(channel ->
            Arrays.stream(JsonFailureCase.values()).map(failure ->
                Arguments.of(channel, failure)));
    }

    static Stream<Arguments> maximumVersionOutcomes() {
        return Stream.of(
            Arguments.of(
                (Long) null,
                "CONTRACT_VALIDATION_NOT_FOUND",
                404,
                Map.of()),
            Arguments.of(
                7L,
                "VERSION_CONFLICT",
                412,
                Map.of(
                    "expected_version", Long.MAX_VALUE,
                    "actual_version", 7)),
            Arguments.of(
                Long.MAX_VALUE,
                "VERSION_LIMIT_REACHED",
                422,
                Map.of()));
    }

    @Test
    void closedClasspathRegistryVerifiesDialectIdFormatsAndUnknownUris() throws Exception {
        ContractValidationJson json = new ContractValidationJson();

        assertThat(json.schemaIds()).containsExactly(DOMAIN_SCHEMA);
        assertThatThrownBy(() -> json.schemaIds().add(URI.create("https://other.example/schema")))
            .isInstanceOf(UnsupportedOperationException.class);

        try (InputStream resource = ContractValidationApiTest.class.getResourceAsStream(
                "/accord/contracts/domain-event.schema.json")) {
            assertThat(resource).isNotNull();
            JsonNode checkedIn = mapper.readTree(resource);
            assertThat(checkedIn.path("$id").textValue()).isEqualTo(DOMAIN_SCHEMA.toString());
            assertThat(checkedIn.path("$schema").textValue()).isEqualTo(DRAFT_2020_12.toString());
        }

        ContractValidationJson.SchemaEvaluation valid =
            json.evaluate(DOMAIN_SCHEMA, validDomainEvent());
        assertThat(valid).isInstanceOf(ContractValidationJson.SchemaEvaluation.Valid.class);

        ObjectNode invalidFormats = validDomainEvent();
        invalidFormats.put("event_id", "not-a-uuid");
        invalidFormats.put("occurred_at", "not-a-date-time");
        ContractValidationJson.SchemaEvaluation invalidEvaluation =
            json.evaluate(DOMAIN_SCHEMA, invalidFormats);
        assertThat(invalidEvaluation)
            .isInstanceOf(ContractValidationJson.SchemaEvaluation.Invalid.class);
        ContractValidationJson.SchemaEvaluation.Invalid invalid =
            (ContractValidationJson.SchemaEvaluation.Invalid) invalidEvaluation;
        assertThat(invalid.errors())
            .contains(
                new ContractValidationJson.ValidationError("/event_id", "SCHEMA_FORMAT"),
                new ContractValidationJson.ValidationError("/occurred_at", "SCHEMA_FORMAT"));

        assertThat(json.evaluate(
            URI.create("https://schemas.accord.inforvans.com/events/unregistered/1-0-0"),
            validDomainEvent()))
            .isInstanceOf(ContractValidationJson.SchemaEvaluation.SchemaNotFound.class);
    }

    @Test
    void schemaErrorProjectionIsStableBoundedSortedAndProseFree() throws Exception {
        JsonNodePath root = new JsonNodePath(PathType.JSON_POINTER);
        assertThat(ContractValidationJson.pointer(root)).isEqualTo("$");
        assertThat(ContractValidationJson.pointer(root.append("tilde~/slash")))
            .isEqualTo("/tilde~0~1slash");

        String supplementary = new String(Character.toChars(0x1f680));
        String fullPointer = ContractValidationJson.pointer(root.append(supplementary.repeat(600)));
        String bounded = ContractValidationJson.boundField(fullPointer);
        assertThat(bounded.codePointCount(0, bounded.length())).isEqualTo(512);
        int prefixEnd = fullPointer.offsetByCodePoints(0, 440);
        assertThat(bounded).isEqualTo(
            fullPointer.substring(0, prefixEnd) + "#sha256:" + uppercaseSha256(fullPointer));

        assertThat(ContractValidationJson.normalizeReason("additionalProperties"))
            .isEqualTo("SCHEMA_ADDITIONAL_PROPERTIES");
        assertThat(ContractValidationJson.normalizeReason("min-length.value"))
            .isEqualTo("SCHEMA_MIN_LENGTH_VALUE");
        assertThat(ContractValidationJson.normalizeReason(""))
            .isEqualTo("SCHEMA_RULE_" + uppercaseSha256("").substring(0, 52));
        String punctuationKeyword = "...\u00e9...";
        assertThat(ContractValidationJson.normalizeReason(punctuationKeyword))
            .isEqualTo(
                "SCHEMA_RULE_" + uppercaseSha256(punctuationKeyword).substring(0, 52));
        String longKeyword = "a".repeat(58);
        assertThat(ContractValidationJson.normalizeReason(longKeyword))
            .isEqualTo("SCHEMA_RULE_" + uppercaseSha256(longKeyword).substring(0, 52));

        List<ContractValidationJson.SchemaViolation> violations = new ArrayList<>();
        for (int index = 139; index >= 0; index--) {
            violations.add(new ContractValidationJson.SchemaViolation(
                "/field-" + String.format(java.util.Locale.ROOT, "%03d", index),
                index % 2 == 0 ? "minLength" : "additionalProperties"));
        }
        violations.add(violations.getLast());
        List<ContractValidationJson.ValidationError> projected =
            ContractValidationJson.projectViolations(violations);

        assertThat(projected).hasSize(128).doesNotHaveDuplicates().isSortedAccordingTo(
            java.util.Comparator
                .comparing(ContractValidationJson.ValidationError::field)
                .thenComparing(ContractValidationJson.ValidationError::reason));
        JsonNode serialized = mapper.valueToTree(projected);
        assertThat(serialized.isArray()).isTrue();
        serialized.forEach(error -> {
            assertThat(error.propertyStream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("field", "reason");
            assertThat(error.has("message")).isFalse();
            assertThat(error.has("error")).isFalse();
        });
        assertThat(serialized.toString()).doesNotContain("must", "validator", "failed");
    }

    @Test
    void problemFactoryOwnsStableSerializationAndEmitsDeterministicIntegers() throws Exception {
        var constructors = FoundationProblemFactory.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).isEmpty();
        FoundationProblemFactory factory = new FoundationProblemFactory();

        StoredHttpResult inProgressResult = factory.commandInProgress(
            CORRELATION_ID, REQUEST_PATH, 17);
        JsonNode inProgress = mapper.readTree(inProgressResult.body());
        assertThat(inProgress.path("status").isIntegralNumber()).as("status").isTrue();
        assertThat(inProgress.path("retry_after").isIntegralNumber())
            .as("retry_after")
            .isTrue();
        assertThat(factory.commandInProgress(CORRELATION_ID, REQUEST_PATH, 17)
            .body().getBytes(UTF_8)).isEqualTo(inProgressResult.body().getBytes(UTF_8));

        StoredHttpResult conflictResult = factory.versionConflict(
            CORRELATION_ID, REQUEST_PATH, 7, 8L);
        JsonNode conflict = mapper.readTree(conflictResult.body());
        assertThat(conflict.path("expected_version").isIntegralNumber())
            .as("expected_version")
            .isTrue();
        assertThat(conflict.path("actual_version").isIntegralNumber())
            .as("actual_version")
            .isTrue();
        assertThat(factory.versionConflict(CORRELATION_ID, REQUEST_PATH, 7, 8L)
            .body().getBytes(UTF_8)).isEqualTo(conflictResult.body().getBytes(UTF_8));
    }

    @Test
    void responseUtf8EncoderPreservesSupplementaryCodePointsExactly() {
        String rocket = new String(Character.toChars(0x1f680));

        assertThat(FoundationHttpResponseWriter.strictUtf8("A" + rocket))
            .containsExactly(
                (byte) 0x41,
                (byte) 0xf0,
                (byte) 0x9f,
                (byte) 0x9a,
                (byte) 0x80);
    }

    @Test
    void responseUtf8EncoderFailsClosedOnUnpairedSurrogate() {
        assertThatThrownBy(() -> FoundationHttpResponseWriter.strictUtf8("\ud800"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("response body cannot be encoded as UTF-8");
    }

    @Test
    void problemFactoryRejectsEveryOutOfContractExtensionValue() throws Exception {
        FoundationProblemFactory factory = new FoundationProblemFactory();
        assertThatThrownBy(() ->
            factory.commandInProgress(CORRELATION_ID, REQUEST_PATH, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            factory.commandInProgress(CORRELATION_ID, REQUEST_PATH, 121))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            factory.versionConflict(CORRELATION_ID, REQUEST_PATH, -1, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            factory.versionConflict(CORRELATION_ID, REQUEST_PATH, 0, 0L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            factory.versionConflict(CORRELATION_ID, REQUEST_PATH, 0, -1L))
            .isInstanceOf(IllegalArgumentException.class);

        JsonNode nullableActual = mapper.readTree(factory.versionConflict(
            CORRELATION_ID, REQUEST_PATH, Long.MAX_VALUE, null).body());
        assertThat(nullableActual.path("expected_version").longValue())
            .isEqualTo(Long.MAX_VALUE);
        assertThat(nullableActual.path("actual_version").isNull()).isTrue();

        ContractValidationJson.ValidationError valid =
            new ContractValidationJson.ValidationError("$", "SCHEMA_REQUIRED");
        assertThatThrownBy(() ->
            factory.documentSchemaInvalid(CORRELATION_ID, REQUEST_PATH, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.documentSchemaInvalid(
            CORRELATION_ID, REQUEST_PATH, java.util.Collections.nCopies(129, valid)))
            .isInstanceOf(IllegalArgumentException.class);
        for (ContractValidationJson.ValidationError invalid : List.of(
            new ContractValidationJson.ValidationError("", "SCHEMA_REQUIRED"),
            new ContractValidationJson.ValidationError("x".repeat(513), "SCHEMA_REQUIRED"),
            new ContractValidationJson.ValidationError("$", ""),
            new ContractValidationJson.ValidationError("$", "schema_required"),
            new ContractValidationJson.ValidationError("$", "S".repeat(65)))) {
            assertThatThrownBy(() ->
                factory.documentSchemaInvalid(
                    CORRELATION_ID, REQUEST_PATH, List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void problemFactoryEmitsEveryAuthoritativeClosedRow() throws Exception {
        FoundationProblemFactory factory = new FoundationProblemFactory();
        List<ContractValidationJson.ValidationError> errors = List.of(
            new ContractValidationJson.ValidationError("/event_id", "SCHEMA_FORMAT"));
        List<ProblemCase> cases = List.of(
            problem(
                factory.requestInvalid(CORRELATION_ID, REQUEST_PATH + "?ignored=true"),
                "REQUEST_INVALID", "request-invalid", "Request is invalid", 400, Map.of()),
            problem(
                factory.jsonInvalid(CORRELATION_ID, REQUEST_PATH),
                "JSON_INVALID", "json-invalid", "JSON document is invalid", 400, Map.of()),
            problem(
                factory.authenticationRequired(CORRELATION_ID, REQUEST_PATH),
                "AUTHENTICATION_REQUIRED", "authentication-required",
                "Authentication is required", 401, Map.of()),
            problem(
                factory.authorizationDenied(CORRELATION_ID, REQUEST_PATH),
                "AUTHORIZATION_DENIED", "authorization-denied",
                "Authorization is denied", 403, Map.of()),
            problem(
                factory.csrfValidationFailed(CORRELATION_ID, REQUEST_PATH),
                "CSRF_VALIDATION_FAILED", "csrf-validation-failed",
                "CSRF validation failed", 403, Map.of()),
            problem(
                factory.schemaNotFound(CORRELATION_ID, REQUEST_PATH),
                "SCHEMA_NOT_FOUND", "schema-not-found",
                "Contract schema was not found", 404, Map.of()),
            problem(
                factory.contractValidationNotFound(CORRELATION_ID, REQUEST_PATH),
                "CONTRACT_VALIDATION_NOT_FOUND", "contract-validation-not-found",
                "Contract validation was not found", 404, Map.of()),
            problem(
                factory.notAcceptable(CORRELATION_ID, REQUEST_PATH),
                "NOT_ACCEPTABLE", "not-acceptable",
                "Requested representation is not acceptable", 406, Map.of()),
            problem(
                factory.idempotencyKeyReused(CORRELATION_ID, REQUEST_PATH),
                "IDEMPOTENCY_KEY_REUSED", "idempotency-key-reused",
                "Idempotency key was reused for a different request", 409, Map.of()),
            problem(
                factory.commandInProgress(CORRELATION_ID, REQUEST_PATH, 1),
                "COMMAND_IN_PROGRESS", "command-in-progress",
                "Command is already in progress", 409, Map.of("retry_after", 1)),
            problem(
                factory.versionConflict(CORRELATION_ID, REQUEST_PATH, 7, 8L),
                "VERSION_CONFLICT", "version-conflict",
                "Expected version does not match", 412,
                Map.of("expected_version", 7, "actual_version", 8)),
            problem(
                factory.unsupportedMediaType(CORRELATION_ID, REQUEST_PATH),
                "UNSUPPORTED_MEDIA_TYPE", "unsupported-media-type",
                "Content type is not supported", 415, Map.of()),
            problem(
                factory.versionLimitReached(CORRELATION_ID, REQUEST_PATH),
                "VERSION_LIMIT_REACHED", "version-limit-reached",
                "Aggregate version limit was reached", 422, Map.of()),
            problem(
                factory.documentSchemaInvalid(CORRELATION_ID, REQUEST_PATH, errors),
                "DOCUMENT_SCHEMA_INVALID", "document-schema-invalid",
                "Document does not satisfy the schema", 422, Map.of("errors", errors))
        );

        Set<String> baseKeys = Set.of(
            "type", "title", "status", "code", "correlation_id", "instance");
        for (ProblemCase problem : cases) {
            StoredHttpResult result = problem.result();
            assertThat(result.status()).isEqualTo(problem.status());
            assertThat(result.headers()).containsExactly(
                Map.entry("content-type", "application/problem+json"));
            assertThat(result.body()).doesNotContain("\"detail\"");
            assertThat(result.body().getBytes(UTF_8))
                .isEqualTo(CanonicalJson.canonicalize(result.body().getBytes(UTF_8)));

            JsonNode document = mapper.readTree(result.body());
            Set<String> expectedKeys = new java.util.HashSet<>(baseKeys);
            expectedKeys.addAll(problem.extensions().keySet());
            assertThat(document.propertyStream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(expectedKeys);
            assertThat(document.path("type").textValue()).isEqualTo(
                "https://problems.accord.inforvans.com/" + problem.typeSuffix());
            assertThat(document.path("title").textValue()).isEqualTo(problem.title());
            assertThat(document.path("status").intValue()).isEqualTo(problem.status());
            assertThat(document.path("code").textValue()).isEqualTo(problem.code());
            assertThat(document.path("correlation_id").textValue())
                .isEqualTo(CORRELATION_ID.toString());
            assertThat(document.path("instance").textValue()).isEqualTo(REQUEST_PATH);
            for (Map.Entry<String, Object> extension : problem.extensions().entrySet()) {
                assertThat(document.get(extension.getKey()))
                    .isEqualTo(mapper.valueToTree(extension.getValue()));
            }
        }
    }

    @Test
    void commandBoundaryHasNoExternalClientFt8OrOuterTransactionDependency() {
        var classes = new ClassFileImporter().importPackages(
            "com.inforvans.accord.controlplane.http");
        Set<String> forbiddenTypes = Set.of(
            "java.net.http.HttpClient",
            "org.springframework.web.client.RestClient",
            "org.springframework.web.reactive.function.client.WebClient",
            "com.inforvans.accord.reliability.ExecutionClaim",
            "com.inforvans.accord.reliability.ExternalWritePermit",
            "com.inforvans.accord.reliability.JooqExternalIntentStore");
        noClasses()
            .that().resideInAPackage("..controlplane.http..")
            .should().dependOnClassesThat(new DescribedPredicate<>(
                "an HTTP, Provider, or FT8 execution type") {
                @Override
                public boolean test(JavaClass type) {
                    return forbiddenTypes.contains(type.getName())
                        || type.getPackageName().contains(".provider.")
                        || type.getSimpleName().equals("ProviderClient");
                }
            })
            .check(classes);

        for (Class<?> type : List.of(
                ContractValidationController.class,
                ContractValidationCommandService.class,
                FoundationTenantTransactions.class)) {
            assertThat(type.isAnnotationPresent(Transactional.class))
                .as(type.getSimpleName() + " class transaction annotation")
                .isFalse();
            assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Transactional.class))
                .toList())
                .as(type.getSimpleName() + " method transaction annotations")
                .isEmpty();
        }
    }

    private void blockSchemaEvaluation(
            CountDownLatch evaluationStarted,
            CountDownLatch releaseEvaluation) {
        doAnswer(invocation -> {
            evaluationStarted.countDown();
            if (!releaseEvaluation.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("schema evaluation was not released");
            }
            return invocation.callRealMethod();
        }).when(contractValidationJson).evaluate(eq(DOMAIN_SCHEMA), any(JsonNode.class));
    }

    private void useShortClaimLease() {
        JooqCommandGate realGate = new JooqCommandGate();
        doAnswer(invocation -> realGate.claim(
            invocation.getArgument(0),
            invocation.getArgument(1),
            invocation.getArgument(2),
            invocation.getArgument(3),
            Duration.ofMillis(100)))
            .when(commandGate).claim(any(), any(), any(), any(), any());
    }

    private static void awaitClaimExpiry() {
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(10))
            .untilAsserted(() -> assertThat(singleLong("""
                SELECT count(*) FROM idempotency_result
                WHERE state='STARTED' AND lease_until <= clock_timestamp()
                """)).isEqualTo(1));
    }

    private String requestFingerprint(byte[] rawBody) {
        FoundationVerifiedPrincipal principal =
            new FoundationVerifiedPrincipal.Bearer(TENANT_ID, "actor-1");
        FoundationHttpRequestPolicy.ParsedRequest parsed =
            new FoundationHttpRequestPolicy.ParsedRequest(
                CORRELATION_ID,
                REQUEST_PATH,
                new FoundationMutationHeaders(
                    "idempotency-key-0001",
                    new ExpectedVersion(0),
                    "application/json",
                    "application/json"));
        return new HttpIdempotencyFingerprint("0.1.0").contractValidation(
            principal,
            UUID.fromString(REQUEST_PATH.substring(REQUEST_PATH.lastIndexOf('/') + 1)),
            parsed,
            CanonicalJson.sha256(rawBody));
    }

    private static void assertLostFence(Future<MvcResult> execution) {
        assertThatThrownBy(() -> execution.get(20, TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "idempotency lease is stale, expired, lost, or completed");
    }

    private ObjectNode validDomainEvent() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {
              "event_id": "40000000-0000-0000-0000-000000000001",
              "tenant_id": "10000000-0000-0000-0000-000000000001",
              "scope_type": "tenant",
              "scope_id": "10000000-0000-0000-0000-000000000001",
              "aggregate_type": "contract-validation",
              "aggregate_id": "20000000-0000-0000-0000-000000000001",
              "sequence": 1,
              "event_type": "contract-validation.completed",
              "schema_version": "1.0.0",
              "causation_id": "50000000-0000-0000-0000-000000000001",
              "correlation_id": "30000000-0000-0000-0000-000000000001",
              "actor_id": "actor-1",
              "occurred_at": "2026-07-26T00:00:00Z",
              "payload": {}
            }
            """);
    }

    private static ProblemCase problem(
            StoredHttpResult result,
            String code,
            String typeSuffix,
            String title,
            int status,
            Map<String, Object> extensions) {
        return new ProblemCase(result, code, typeSuffix, title, status, extensions);
    }

    private static String uppercaseSha256(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().withUpperCase().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
    }

    private static MockHttpServletRequestBuilder malformedSecurityRequest() {
        return post(REQUEST_PATH + "?unexpected=true")
            .header(FoundationHttpRequestPolicy.CORRELATION_HEADER, "not-a-uuid")
            .header("Content-Type", "text/plain")
            .content("{");
    }

    private MockHttpServletRequestBuilder browserCsrfRequest(BrowserCsrfFailure failure) {
        MockHttpServletRequestBuilder request = malformedSecurityRequest();
        if (failure != BrowserCsrfFailure.MISSING_ORIGIN) {
            String origin = failure == BrowserCsrfFailure.WRONG_ORIGIN
                ? "https://other.accord.test"
                : ALLOWED_ORIGIN;
            request.header("Origin", origin);
            if (failure == BrowserCsrfFailure.DUPLICATE_ORIGIN) {
                request.header("Origin", origin);
            }
        }
        if (failure != BrowserCsrfFailure.MISSING_FETCH_SITE) {
            String fetchSite = failure == BrowserCsrfFailure.WRONG_FETCH_SITE
                ? "cross-site"
                : "same-origin";
            request.header("Sec-Fetch-Site", fetchSite);
            if (failure == BrowserCsrfFailure.DUPLICATE_FETCH_SITE) {
                request.header("Sec-Fetch-Site", fetchSite);
            }
        }
        if (failure != BrowserCsrfFailure.MISSING_TOKEN) {
            String token = switch (failure) {
                case MALFORMED_TOKEN -> "too-short";
                case MISMATCHED_TOKEN -> OTHER_CSRF_TOKEN;
                default -> CSRF_TOKEN;
            };
            request.header("X-CSRF-Token", token);
            if (failure == BrowserCsrfFailure.DUPLICATE_TOKEN) {
                request.header("X-CSRF-Token", token);
            }
        }
        return request;
    }

    private MockHttpServletRequestBuilder trustedRequest(
            TrustedChannel channel, URI schemaId, JsonNode document) throws Exception {
        return trustedRequest(
            channel, schemaId, document, "idempotency-key-0001", 0);
    }

    private MockHttpServletRequestBuilder trustedRequest(
            TrustedChannel channel,
            URI schemaId,
            JsonNode document,
            String idempotencyKey,
            long expectedVersion) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("schema_id", schemaId.toString());
        body.set("document", document);
        return validProtocolRequest(
            channel,
            mapper.writeValueAsBytes(body),
            idempotencyKey,
            expectedVersion);
    }

    private MockHttpServletRequestBuilder trustedBearerRequest(
            UUID tenantId,
            String actorId,
            String requestPath,
            URI schemaId,
            JsonNode document,
            String idempotencyKey,
            long expectedVersion,
            String accept) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("schema_id", schemaId.toString());
        body.set("document", document);
        MockHttpServletRequestBuilder request = post(requestPath)
            .header(FoundationHttpRequestPolicy.CORRELATION_HEADER, CORRELATION_ID.toString())
            .header("Content-Type", "application/json")
            .header("Accept", accept)
            .header(FoundationHttpRequestPolicy.IDEMPOTENCY_HEADER, idempotencyKey)
            .header(
                FoundationHttpRequestPolicy.EXPECTED_VERSION_HEADER,
                "\"" + expectedVersion + "\"")
            .content(mapper.writeValueAsBytes(body));
        FoundationVerifiedPrincipal principal =
            new FoundationVerifiedPrincipal.Bearer(tenantId, actorId);
        return request.with(authentication(new UsernamePasswordAuthenticationToken(
            principal, "credential", AuthorityUtils.NO_AUTHORITIES)));
    }

    private MockHttpServletRequestBuilder validProtocolRequest(
            TrustedChannel channel, byte[] body) throws Exception {
        return validProtocolRequest(
            channel, body, "idempotency-key-0001", 0);
    }

    private MockHttpServletRequestBuilder validProtocolRequest(
            TrustedChannel channel,
            byte[] body,
            String idempotencyKey,
            long expectedVersion) throws Exception {
        MockHttpServletRequestBuilder request = post(REQUEST_PATH)
            .header(FoundationHttpRequestPolicy.CORRELATION_HEADER, CORRELATION_ID.toString())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(FoundationHttpRequestPolicy.IDEMPOTENCY_HEADER, idempotencyKey)
            .header(
                FoundationHttpRequestPolicy.EXPECTED_VERSION_HEADER,
                "\"" + expectedVersion + "\"")
            .content(body);
        return authenticate(request, channel);
    }

    private MockHttpServletRequestBuilder validationIdRequest(String rawValidationId)
            throws Exception {
        return post(validationPath(rawValidationId))
            .header(FoundationHttpRequestPolicy.CORRELATION_HEADER, CORRELATION_ID.toString())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(FoundationHttpRequestPolicy.IDEMPOTENCY_HEADER, "idempotency-key-0001")
            .header(FoundationHttpRequestPolicy.EXPECTED_VERSION_HEADER, "\"0\"")
            .content(validRequestBody());
    }

    private static MockHttpServletRequestBuilder
            malformedProtocolOnValidationIdRequest(String rawValidationId) {
        return post(validationPath(rawValidationId) + "?unexpected=true")
            .header(FoundationHttpRequestPolicy.CORRELATION_HEADER, "not-a-uuid")
            .header("Content-Type", "text/plain")
            .header("Accept", "broken")
            .header(FoundationHttpRequestPolicy.IDEMPOTENCY_HEADER, "short")
            .header(FoundationHttpRequestPolicy.EXPECTED_VERSION_HEADER, "W/\"0\"")
            .content("{");
    }

    private static String validationPath(String rawValidationId) {
        return "/v1/contract-validations/" + rawValidationId;
    }

    private MockHttpServletRequestBuilder validContextPathRequest(byte[] body) {
        return post(CONTEXT_PATH + REQUEST_PATH)
            .contextPath(CONTEXT_PATH)
            .header(FoundationHttpRequestPolicy.CORRELATION_HEADER, CORRELATION_ID.toString())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(FoundationHttpRequestPolicy.IDEMPOTENCY_HEADER, "idempotency-key-0001")
            .header(FoundationHttpRequestPolicy.EXPECTED_VERSION_HEADER, "\"0\"")
            .content(body);
    }

    private MockHttpServletRequestBuilder protocolFailureRequest(
            TrustedChannel channel, ProtocolFailure failure) throws Exception {
        String target = switch (failure) {
            case QUERY, MISSING_CORRELATION_THEN_QUERY -> REQUEST_PATH + "?unexpected=true";
            default -> REQUEST_PATH;
        };
        MockHttpServletRequestBuilder request = post(target)
            .content(validRequestBody());

        if (failure != ProtocolFailure.MISSING_CORRELATION_THEN_QUERY) {
            String correlation = failure == ProtocolFailure.MALFORMED_CORRELATION
                ? "not-a-uuid"
                : CORRELATION_ID.toString();
            request.header(FoundationHttpRequestPolicy.CORRELATION_HEADER, correlation);
            if (failure == ProtocolFailure.DUPLICATE_CORRELATION) {
                request.header(FoundationHttpRequestPolicy.CORRELATION_HEADER, correlation);
            }
        }

        if (failure != ProtocolFailure.MISSING_CONTENT_TYPE) {
            String contentType = failure == ProtocolFailure.WRONG_CONTENT_TYPE
                ? "application/json;charset=UTF-8"
                : "application/json";
            request.header("Content-Type", contentType);
        }

        String accept = switch (failure) {
            case MALFORMED_ACCEPT -> "application/json;q=bogus";
            case NOT_ACCEPTABLE -> "text/plain";
            default -> "application/json";
        };
        request.header("Accept", accept);

        if (failure != ProtocolFailure.MISSING_IDEMPOTENCY_KEY) {
            String idempotencyKey = failure == ProtocolFailure.MALFORMED_IDEMPOTENCY_KEY
                ? "short"
                : "idempotency-key-0001";
            request.header(FoundationHttpRequestPolicy.IDEMPOTENCY_HEADER, idempotencyKey);
            if (failure == ProtocolFailure.DUPLICATE_IDEMPOTENCY_KEY) {
                request.header(FoundationHttpRequestPolicy.IDEMPOTENCY_HEADER, idempotencyKey);
            }
        }

        if (failure != ProtocolFailure.MISSING_IF_MATCH) {
            String ifMatch = failure == ProtocolFailure.MALFORMED_IF_MATCH
                ? "W/\"0\""
                : "\"0\"";
            request.header(FoundationHttpRequestPolicy.EXPECTED_VERSION_HEADER, ifMatch);
            if (failure == ProtocolFailure.DUPLICATE_IF_MATCH) {
                request.header(FoundationHttpRequestPolicy.EXPECTED_VERSION_HEADER, ifMatch);
            }
        }
        return authenticate(request, channel);
    }

    private byte[] validRequestBody() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("schema_id", DOMAIN_SCHEMA.toString());
        body.set("document", validDomainEvent());
        return mapper.writeValueAsBytes(body);
    }

    private MockHttpServletRequestBuilder authenticate(
            MockHttpServletRequestBuilder request, TrustedChannel channel) throws Exception {
        FoundationVerifiedPrincipal principal;
        if (channel == TrustedChannel.BROWSER) {
            principal = browserSession(7, 7, CSRF_TOKEN);
            request
                .header("Origin", ALLOWED_ORIGIN)
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-CSRF-Token", CSRF_TOKEN);
        } else {
            principal = new FoundationVerifiedPrincipal.Bearer(TENANT_ID, "actor-1");
        }
        return request.with(authentication(new UsernamePasswordAuthenticationToken(
            principal, "credential", AuthorityUtils.NO_AUTHORITIES)));
    }

    private static StoredHttpResult placeholderResult() {
        return new StoredHttpResult(
            201,
            Map.of("Content-Type", "application/json", "ETag", "\"1\""),
            PLACEHOLDER_BODY);
    }

    private static void assertPlaceholder(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        assertThat(result.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(result.getResponse().getContentAsByteArray())
            .isEqualTo(PLACEHOLDER_BODY.getBytes(UTF_8));
    }

    private FoundationVerifiedPrincipal.BrowserSession browserSession(
            long sessionGeneration,
            long bindingGeneration,
            String bindingToken) throws Exception {
        String tokenDigest = "sha256:" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bindingToken.getBytes(UTF_8)));
        ObjectNode binding = mapper.createObjectNode();
        binding.put("tenant_id", TENANT_ID.toString());
        binding.put("actor_id", "actor-1");
        binding.put("session_id", "session-1");
        binding.put("session_generation", bindingGeneration);
        binding.put("token_digest", tokenDigest);
        return new FoundationVerifiedPrincipal.BrowserSession(
            TENANT_ID,
            "actor-1",
            "session-1",
            sessionGeneration,
            CanonicalJson.sha256(mapper.writeValueAsBytes(binding)));
    }

    private void assertProblem(MvcResult result, String code) throws Exception {
        assertProblem(result, code, null);
    }

    private void assertProblem(
            MvcResult result, String code, UUID expectedCorrelationId) throws Exception {
        assertProblem(result, code, expectedCorrelationId, REQUEST_PATH);
    }

    private void assertProblem(
            MvcResult result,
            String code,
            UUID expectedCorrelationId,
            String expectedInstance) throws Exception {
        assertProblem(result, code, expectedCorrelationId, expectedInstance, Map.of());
    }

    private void assertProblem(
            MvcResult result,
            String code,
            UUID expectedCorrelationId,
            String expectedInstance,
            Map<String, Object> expectedExtensions) throws Exception {
        ProblemExpectation expected = switch (code) {
            case "REQUEST_INVALID" -> new ProblemExpectation(
                "request-invalid", "Request is invalid", 400);
            case "JSON_INVALID" -> new ProblemExpectation(
                "json-invalid", "JSON document is invalid", 400);
            case "AUTHENTICATION_REQUIRED" -> new ProblemExpectation(
                "authentication-required", "Authentication is required", 401);
            case "AUTHORIZATION_DENIED" -> new ProblemExpectation(
                "authorization-denied", "Authorization is denied", 403);
            case "CSRF_VALIDATION_FAILED" -> new ProblemExpectation(
                "csrf-validation-failed", "CSRF validation failed", 403);
            case "SCHEMA_NOT_FOUND" -> new ProblemExpectation(
                "schema-not-found", "Contract schema was not found", 404);
            case "CONTRACT_VALIDATION_NOT_FOUND" -> new ProblemExpectation(
                "contract-validation-not-found",
                "Contract validation was not found",
                404);
            case "NOT_ACCEPTABLE" -> new ProblemExpectation(
                "not-acceptable", "Requested representation is not acceptable", 406);
            case "IDEMPOTENCY_KEY_REUSED" -> new ProblemExpectation(
                "idempotency-key-reused",
                "Idempotency key was reused for a different request",
                409);
            case "COMMAND_IN_PROGRESS" -> new ProblemExpectation(
                "command-in-progress", "Command is already in progress", 409);
            case "VERSION_CONFLICT" -> new ProblemExpectation(
                "version-conflict", "Expected version does not match", 412);
            case "UNSUPPORTED_MEDIA_TYPE" -> new ProblemExpectation(
                "unsupported-media-type", "Content type is not supported", 415);
            case "VERSION_LIMIT_REACHED" -> new ProblemExpectation(
                "version-limit-reached", "Aggregate version limit was reached", 422);
            case "DOCUMENT_SCHEMA_INVALID" -> new ProblemExpectation(
                "document-schema-invalid",
                "Document does not satisfy the schema",
                422);
            default -> throw new IllegalArgumentException("unknown Problem code");
        };
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(result.getResponse().getStatus()).isEqualTo(expected.status());
        assertThat(result.getResponse().getContentType())
            .isEqualTo("application/problem+json");
        assertThat(body)
            .isEqualTo(CanonicalJson.canonicalizePreservingExactIntegers(body));

        JsonNode problem = mapper.readTree(body);
        Set<String> expectedKeys = new java.util.HashSet<>(Set.of(
            "type", "title", "status", "code", "correlation_id", "instance"));
        expectedKeys.addAll(expectedExtensions.keySet());
        assertThat(problem.propertyStream().map(Map.Entry::getKey).toList())
            .containsExactlyInAnyOrderElementsOf(expectedKeys);
        assertThat(problem.path("type").textValue()).isEqualTo(
            "https://problems.accord.inforvans.com/" + expected.typeSuffix());
        assertThat(problem.path("title").textValue()).isEqualTo(expected.title());
        assertThat(problem.path("status").intValue()).isEqualTo(expected.status());
        assertThat(problem.path("code").textValue()).isEqualTo(code);
        String correlationId = problem.path("correlation_id").textValue();
        assertThat(UUID.fromString(correlationId).toString()).isEqualTo(correlationId);
        if (expectedCorrelationId != null) {
            assertThat(correlationId).isEqualTo(expectedCorrelationId.toString());
        } else {
            assertThat(correlationId).isNotEqualTo("not-a-uuid");
        }
        assertThat(problem.path("instance").textValue()).isEqualTo(expectedInstance);
        for (Map.Entry<String, Object> extension : expectedExtensions.entrySet()) {
            assertThat(problem.get(extension.getKey()))
                .isEqualTo(mapper.valueToTree(extension.getValue()));
        }
        String serialized = new String(body, UTF_8);
        assertThat(serialized).doesNotContain("detail");
        if (!expectedInstance.contains("not-a-uuid")) {
            assertThat(serialized).doesNotContain("not-a-uuid");
        }
    }

    private void assertStoredRejectionAndReplay(
            URI schemaId,
            ObjectNode document,
            String code,
            Map<String, Object> extensions) throws Exception {
        assertStoredRejectionAndReplay(
            schemaId,
            document,
            "idempotency-key-0001",
            0,
            code,
            extensions);
    }

    private void assertStoredRejectionAndReplay(
            URI schemaId,
            ObjectNode document,
            String idempotencyKey,
            long expectedVersion,
            String code,
            Map<String, Object> extensions) throws Exception {
        MvcResult rejected = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER,
            schemaId,
            document,
            idempotencyKey,
            expectedVersion)).andReturn();

        assertProblem(rejected, code, CORRELATION_ID, REQUEST_PATH, extensions);
        Map<String, Long> rejectedCounts = durableRowCounts();
        assertThat(rejectedCounts).containsExactly(
            Map.entry("contract_validation", 0L),
            Map.entry("aggregate_head", 0L),
            Map.entry("domain_event", 0L),
            Map.entry("outbox_event", 0L),
            Map.entry("idempotency_result", 1L));
        assertThat(singleString("SELECT state FROM idempotency_result"))
            .isEqualTo("COMPLETED");
        assertThat(singleLong("""
            SELECT count(*) FROM idempotency_result
            WHERE aggregate_type IS NULL
              AND aggregate_id IS NULL
              AND aggregate_version IS NULL
            """)).isEqualTo(1);

        MvcResult replayed = mockMvc.perform(trustedRequest(
            TrustedChannel.BEARER,
            schemaId,
            document,
            idempotencyKey,
            expectedVersion)).andReturn();

        assertThat(replayed.getResponse().getStatus())
            .isEqualTo(rejected.getResponse().getStatus());
        assertThat(replayed.getResponse().getHeader("Content-Type"))
            .isEqualTo(rejected.getResponse().getHeader("Content-Type"));
        assertThat(replayed.getResponse().getContentAsByteArray())
            .isEqualTo(rejected.getResponse().getContentAsByteArray());
        assertThat(durableRowCounts()).isEqualTo(rejectedCounts);
    }

    private static void assertNoDurableRows() throws SQLException {
        assertThat(durableRowCounts().values()).allMatch(count -> count == 0L);
    }

    private static Map<String, Long> durableRowCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            for (String table : List.of(
                    "contract_validation",
                    "aggregate_head",
                    "domain_event",
                    "outbox_event",
                    "idempotency_result")) {
                try (ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM " + table)) {
                    assertThat(rows.next()).as(table + " count row").isTrue();
                    counts.put(table, rows.getLong(1));
                }
            }
        }
        return counts;
    }

    private static String singleString(String sql) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("one result row").isTrue();
            String value = rows.getString(1);
            assertThat(rows.next()).as("only one result row").isFalse();
            return value;
        }
    }

    private static long singleLong(String sql) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("one result row").isTrue();
            long value = rows.getLong(1);
            assertThat(rows.next()).as("only one result row").isFalse();
            return value;
        }
    }

    private static void seedContractValidation(UUID validationId, long version)
            throws SQLException {
        try (Connection connection = adminConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement head = connection.prepareStatement("""
                     INSERT INTO aggregate_head (
                       tenant_id, aggregate_type, aggregate_id, version
                     ) VALUES (?, 'contract-validation', ?, ?)
                     """);
                 PreparedStatement validation = connection.prepareStatement("""
                     INSERT INTO contract_validation (
                       tenant_id, validation_id, schema_id, document_digest, version
                     ) VALUES (?, ?, ?, ?, ?)
                     """)) {
                head.setObject(1, TENANT_ID);
                head.setObject(2, validationId);
                head.setLong(3, version);
                assertThat(head.executeUpdate()).isEqualTo(1);

                validation.setObject(1, TENANT_ID);
                validation.setObject(2, validationId);
                validation.setString(3, DOMAIN_SCHEMA.toString());
                validation.setString(4, "sha256:" + "a".repeat(64));
                validation.setLong(5, version);
                assertThat(validation.executeUpdate()).isEqualTo(1);
                connection.commit();
            }
        }
    }

    private static void insertLiveClaim(String requestFingerprint) throws SQLException {
        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO idempotency_result (
                   tenant_id, actor_id, route_key, idempotency_key,
                   request_fingerprint, state, claim_owner, claim_generation,
                   claim_token, lease_until, expires_at
                 ) VALUES (
                   ?, 'actor-1', 'contract-validations.create',
                   'idempotency-key-0001', ?, 'STARTED', 'other-instance', 1,
                   '60000000-0000-0000-0000-000000000001',
                   clock_timestamp() + INTERVAL '1 minute',
                   clock_timestamp() + INTERVAL '1 minute'
                 )
                 """)) {
            statement.setObject(1, TENANT_ID);
            statement.setString(2, requestFingerprint);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static PostgreSQLContainer<?> migratedPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
        postgres.start();
        try {
            ControlPlaneTestRoles.bootstrap(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(),
                    ControlPlaneTestRoles.MIGRATOR_LOGIN,
                    ControlPlaneTestRoles.MIGRATOR_PASSWORD)
                .initSql("SET ROLE accord_migrator")
                .locations("classpath:db/migration")
                .load()
                .migrate();
            return postgres;
        } catch (RuntimeException error) {
            postgres.stop();
            throw error;
        }
    }

    private record ProblemCase(
        StoredHttpResult result,
        String code,
        String typeSuffix,
        String title,
        int status,
        Map<String, Object> extensions
    ) {
        ProblemCase {
            extensions = Map.copyOf(new LinkedHashMap<>(extensions));
        }
    }

    private record ProblemExpectation(String typeSuffix, String title, int status) {}

    private enum BrowserCsrfFailure {
        MISSING_ORIGIN,
        DUPLICATE_ORIGIN,
        WRONG_ORIGIN,
        MISSING_FETCH_SITE,
        DUPLICATE_FETCH_SITE,
        WRONG_FETCH_SITE,
        MISSING_TOKEN,
        DUPLICATE_TOKEN,
        MALFORMED_TOKEN,
        MISMATCHED_TOKEN,
        STALE_SESSION_GENERATION
    }

    private enum TrustedChannel {
        BEARER(FoundationVerifiedPrincipal.Bearer.class),
        BROWSER(FoundationVerifiedPrincipal.BrowserSession.class);

        private final Class<? extends FoundationVerifiedPrincipal> principalType;

        TrustedChannel(Class<? extends FoundationVerifiedPrincipal> principalType) {
            this.principalType = principalType;
        }

        Class<? extends FoundationVerifiedPrincipal> principalType() {
            return principalType;
        }
    }

    private enum DeferredSchemaCase {
        UNKNOWN_SCHEMA,
        SCHEMA_INVALID_DOCUMENT
    }

    private enum IdempotencyMismatch {
        RESOURCE_ID,
        EXPECTED_VERSION,
        ACCEPT
    }

    private enum ProtocolFailure {
        MALFORMED_CORRELATION("REQUEST_INVALID", true),
        DUPLICATE_CORRELATION("REQUEST_INVALID", true),
        MISSING_CORRELATION_THEN_QUERY("REQUEST_INVALID", true),
        QUERY("REQUEST_INVALID", false),
        MISSING_CONTENT_TYPE("UNSUPPORTED_MEDIA_TYPE", false),
        WRONG_CONTENT_TYPE("UNSUPPORTED_MEDIA_TYPE", false),
        MALFORMED_ACCEPT("REQUEST_INVALID", false),
        NOT_ACCEPTABLE("NOT_ACCEPTABLE", false),
        MISSING_IDEMPOTENCY_KEY("REQUEST_INVALID", false),
        DUPLICATE_IDEMPOTENCY_KEY("REQUEST_INVALID", false),
        MALFORMED_IDEMPOTENCY_KEY("REQUEST_INVALID", false),
        MISSING_IF_MATCH("REQUEST_INVALID", false),
        DUPLICATE_IF_MATCH("REQUEST_INVALID", false),
        MALFORMED_IF_MATCH("REQUEST_INVALID", false);

        private final String code;
        private final boolean generatedCorrelation;

        ProtocolFailure(String code, boolean generatedCorrelation) {
            this.code = code;
            this.generatedCorrelation = generatedCorrelation;
        }

        String code() {
            return code;
        }

        boolean generatedCorrelation() {
            return generatedCorrelation;
        }
    }

    private enum JsonFailureCase {
        OVERSIZE("REQUEST_INVALID") {
            @Override
            byte[] rawBody() {
                byte[] body = new byte[FoundationHttpRequestPolicy.MAX_RAW_BODY_BYTES + 1];
                Arrays.fill(body, (byte) ' ');
                return body;
            }
        },
        EMPTY("JSON_INVALID") {
            @Override
            byte[] rawBody() {
                return new byte[0];
            }
        },
        MALFORMED_UTF8("JSON_INVALID") {
            @Override
            byte[] rawBody() {
                return new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'};
            }
        },
        MALFORMED_JSON("JSON_INVALID") {
            @Override
            byte[] rawBody() {
                return "{".getBytes(UTF_8);
            }
        },
        DUPLICATE_KEYS("JSON_INVALID") {
            @Override
            byte[] rawBody() {
                return ("{\"schema_id\":\"" + DOMAIN_SCHEMA + "\","
                    + "\"schema_id\":\"" + DOMAIN_SCHEMA + "\",\"document\":{}}")
                    .getBytes(UTF_8);
            }
        },
        TRAILING_JSON("JSON_INVALID") {
            @Override
            byte[] rawBody() {
                return ("{\"schema_id\":\"" + DOMAIN_SCHEMA
                    + "\",\"document\":{}}{}")
                    .getBytes(UTF_8);
            }
        },
        INVALID_I_JSON_UNICODE("JSON_INVALID") {
            @Override
            byte[] rawBody() {
                return ("{\"schema_id\":\"" + DOMAIN_SCHEMA
                    + "\",\"document\":{\"value\":\"\\uDEAD\"}}")
                    .getBytes(UTF_8);
            }
        },
        UNKNOWN_FIELD("REQUEST_INVALID") {
            @Override
            byte[] rawBody() {
                return ("{\"schema_id\":\"" + DOMAIN_SCHEMA
                    + "\",\"document\":{},\"unknown\":true}")
                    .getBytes(UTF_8);
            }
        },
        ARRAY_TOP_LEVEL("REQUEST_INVALID") {
            @Override
            byte[] rawBody() {
                return "[]".getBytes(UTF_8);
            }
        },
        NON_OBJECT_DOCUMENT("REQUEST_INVALID") {
            @Override
            byte[] rawBody() {
                return ("{\"schema_id\":\"" + DOMAIN_SCHEMA + "\",\"document\":[]}")
                    .getBytes(UTF_8);
            }
        },
        INVALID_SCHEMA_ID("REQUEST_INVALID") {
            @Override
            byte[] rawBody() {
                return "{\"schema_id\":\"http://other.test/schema\",\"document\":{}}"
                    .getBytes(UTF_8);
            }
        };

        private final String code;

        JsonFailureCase(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }

        abstract byte[] rawBody();
    }
}
