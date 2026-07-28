package com.inforvans.accord.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inforvans.accord.controlplane.http.FoundationIdentityAdapter;
import com.inforvans.accord.controlplane.http.FoundationVerifiedPrincipal;
import com.inforvans.accord.controlplane.worker.reconciliation.FencedReconciliationObservation;
import com.inforvans.accord.controlplane.worker.reconciliation.ProviderObservationPort;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationRuntimeProperties;
import com.inforvans.accord.controlplane.worker.reconciliation.WorkerTenantTransactions;
import com.inforvans.accord.controlplane.worker.temporal.PosixTemporalSecretAclPolicy;
import com.inforvans.accord.controlplane.worker.temporal.TemporalConnectionProperties;
import com.inforvans.accord.controlplane.worker.temporal.TemporalRuntimeConfiguration;
import com.inforvans.accord.controlplane.worker.temporal.TemporalReconciliationEventTransport;
import com.inforvans.accord.controlplane.worker.temporal.TemporalSecretAclPolicy;
import com.inforvans.accord.controlplane.worker.temporal.TemporalSecretFileLoader;
import com.inforvans.accord.controlplane.worker.temporal.TemporalServiceStubsFactory;
import com.inforvans.accord.controlplane.worker.temporal.TemporalWorkerLifecycle;
import com.inforvans.accord.controlplane.worker.temporal.WindowsTemporalSecretAclPolicy;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReadOnlyReconciliationActivity;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowRef;
import com.inforvans.accord.database.ControlPlaneTestRoles;
import com.inforvans.accord.platformkernel.CanonicalJson;
import com.inforvans.accord.reliability.DeliveryReceipt;
import com.inforvans.accord.reliability.EventTransport;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import com.inforvans.accord.reliability.LeasedEvent;
import com.inforvans.accord.reliability.MessageFence;
import com.inforvans.accord.reliability.OutboxDispatcher;
import com.inforvans.accord.reliability.OutboxRepository;
import com.inforvans.accord.reliability.ReconciliationLease;
import com.inforvans.accord.reliability.ReconciliationResolution;
import com.inforvans.accord.reliability.TenantWorkPermit;
import com.inforvans.accord.reliability.TenantWorkRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.History;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@Timeout(value = 180, unit = TimeUnit.SECONDS)
final class FoundationProductLoopIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private static final String DOMAIN_SCHEMA =
        "https://schemas.accord.inforvans.com/events/domain-event/1-0-0";
    private static final Set<String> CONTRACT_VALIDATION_EVENT_FIELDS = Set.of(
        "intent_id", "validation_id", "schema_id", "document_digest", "version");
    private static final String DEMO_CERTIFICATE_HEADER =
        "X-Accord-Demo-Certificate";
    private static final long WEBHOOK_REPOSITORY_ID = 4_294_967_296L;
    private static final String NAMESPACE = "accord-foundation-local-v1";
    private static final String SERVER_NAME = "temporal";
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302")
        .asCompatibleSubstituteFor("postgres");
    private static final Set<String> RUN_STATE_FIELDS = Set.of(
        "schema_version", "run_id", "image_lock_sha256", "compose_project",
        "dependency_services", "local_endpoints", "ready", "tool_observations",
        "temporal_it_attempt", "temporal_it_observation");
    private static final Set<String> RECEIPT_FIELDS = Set.of(
        "schema_version", "run_id", "it_attempt_id", "suite_name", "class_name",
        "status", "test_count", "case_ids", "configuration_class",
        "lifecycle_class", "worker_started", "worker_stopped", "workflow_outcome",
        "mutation_calls", "junit", "exit_semantics");

    @Test
    void realMtlsOutboxWorkflowReloadsPostgresqlAndCommitsOneReceipt()
            throws Exception {
        Path repository = locateRepositoryRoot();
        Path evidenceRoot = requireEvidenceDirectories(repository);
        TemporalEvidence temporalEvidence = requireTemporalEvidence(repository);
        assertProductionBoundaries();

        PostgreSQLContainer<?> controlPostgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
        PostgreSQLContainer<?> edgePostgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
        Throwable primary = null;
        try {
            Startables.deepStart(Stream.of(controlPostgres, edgePostgres)).join();
            migrate(controlPostgres);
            EdgeDatabase edge = migrateWebhookEdge(repository, edgePostgres);
            executeProductLoop(
                repository,
                evidenceRoot,
                temporalEvidence,
                controlPostgres,
                edge);
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanupAll(primary, edgePostgres::stop, controlPostgres::stop);
        }
    }

    private static void executeProductLoop(
            Path repository,
            Path evidenceRoot,
            TemporalEvidence temporalEvidence,
            PostgreSQLContainer<?> controlPostgres,
            EdgeDatabase edge) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID validationId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        WebhookHttpFacts webhookFacts = exerciseWebhookHttp(
            evidenceRoot, edge, tenantId);
        ApiHttpFacts apiFacts = exerciseControlApiHttp(
            evidenceRoot,
            controlPostgres,
            tenantId,
            validationId,
            correlationId);
        UUID intentId = apiFacts.intentId();
        String taskQueue = "accord-reconciliation-ft17-" + intentId;
        Path pkiRoot = repository.resolve("infra/local/state/pki");
        TemporalConnectionProperties temporalProperties = new TemporalConnectionProperties(
            temporalEvidence.endpoint(), NAMESPACE, taskQueue, SERVER_NAME, pkiRoot,
            pkiRoot.resolve("worker.pem"), pkiRoot.resolve("worker-key.pem"),
            pkiRoot.resolve("server-ca.pem"), RPC_TIMEOUT, SHUTDOWN_TIMEOUT);
        TemporalServiceStubsFactory stubsFactory = productionStubsFactory();
        WorkflowServiceStubs clientStubs = null;
        HikariDataSource dataSource = null;
        WorkerProcess firstWorker = null;
        WorkerProcess secondWorker = null;
        ExecutorService dispatcherExecutor = null;
        Future<OutboxDispatcher.DispatchResult> dispatchFuture = null;
        Throwable primary = null;

        try {
            dataSource = workerDataSource(controlPostgres.getJdbcUrl());
            DSLContext workerDsl = callerOwnedDsl(dataSource);
            WorkerTenantTransactions transactions = new WorkerTenantTransactions(workerDsl);
            JooqExternalIntentStore intentStore = new JooqExternalIntentStore();
            ReconciliationRuntimeProperties reconciliationProperties =
                new ReconciliationRuntimeProperties(
                    "ft17-foundation-worker", Duration.ofSeconds(8));

            clientStubs = stubsFactory.create(temporalProperties);
            describeNamespace(clientStubs);
            WorkflowClient workflowClient = WorkflowClient.newInstance(
                clientStubs,
                WorkflowClientOptions.newBuilder()
                    .setNamespace(NAMESPACE)
                    .validateAndBuildWithDefaults());

            Path markerRoot = evidenceRoot.resolve("logs/worker-process-markers");
            Files.createDirectory(markerRoot);
            Map<String, String> childEnvironment = workerProcessEnvironment(
                temporalProperties,
                controlPostgres.getJdbcUrl(),
                tenantId,
                intentId,
                markerRoot);
            firstWorker = WorkerProcess.start(
                1,
                childEnvironment,
                evidenceRoot.resolve("logs/accord-control-worker-1.log"));
            assertTrue(awaitMarker(
                markerRoot.resolve(FoundationWorkerProcessMain.READY_PREFIX + "1"),
                firstWorker,
                Duration.ofSeconds(15)),
                "first worker process did not become ready");

            TemporalReconciliationEventTransport transport =
                new TemporalReconciliationEventTransport(
                    workflowClient,
                    taskQueue,
                    transactions,
                    intentStore,
                    reconciliationProperties);
            assertTransportCarriesNoLeaseOrCapability(transport.getClass());

            TenantWorkRepository tenantWork = new TenantWorkRepository();
            OutboxRepository outbox = new OutboxRepository();
            TenantWorkPermit permit = workerDsl.transactionResult(configuration ->
                tenantWork.acquire(
                    DSL.using(configuration),
                    "ft17-foundation-dispatcher",
                    Duration.ofSeconds(45)).orElseThrow(
                        () -> new IllegalStateException("seeded tenant work is absent")));
            assertEquals(tenantId, permit.tenantId());
            List<LeasedEvent> leased = transactions.inTenant(
                tenantId,
                tx -> outbox.lease(
                    tx,
                    permit,
                    "ft17-foundation-dispatcher",
                    1,
                    Duration.ofSeconds(30)));
            assertEquals(1, leased.size());
            LeasedEvent trigger = leased.getFirst();
            assertEquals(TemporalReconciliationEventTransport.DESTINATION,
                trigger.destination());
            assertEquals(TemporalReconciliationEventTransport.PAYLOAD_SCHEMA,
                trigger.payloadSchema());
            UUID triggerEventId = trigger.eventId();

            OutboxDispatcher dispatcher = new OutboxDispatcher(
                transport,
                outbox,
                transactions::inTenant,
                (ownedPermit, next) -> transactions.inTenant(
                    ownedPermit.tenantId(),
                    tx -> {
                        if (next.isPresent()) {
                            tenantWork.finish(tx, ownedPermit, next.orElseThrow());
                        } else {
                            tenantWork.finishNoKnownWork(tx, ownedPermit);
                        }
                        return null;
                    }),
                Duration.ofSeconds(20),
                Duration.ofSeconds(30),
                1,
                3,
                Duration.ofMillis(50),
                Duration.ofSeconds(1));

            dispatcherExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ft17-outbox-dispatcher");
                thread.setDaemon(false);
                return thread;
            });
            dispatchFuture = dispatcherExecutor.submit(
                () -> dispatcher.dispatch(permit, leased));
            assertTrue(awaitMarker(
                markerRoot.resolve(FoundationWorkerProcessMain.OBSERVATION_PREFIX + "1"),
                firstWorker,
                Duration.ofSeconds(15)),
                "first worker did not enter the production observation activity");
            awaitReconciliationClaim(transactions, tenantId, intentId);

            long firstPid = firstWorker.pid();
            firstWorker.destroyForcibly(Duration.ofSeconds(10));
            assertFalse(Files.exists(markerRoot.resolve(
                FoundationWorkerProcessMain.STOPPED_PREFIX + "1")));

            secondWorker = WorkerProcess.start(
                2,
                childEnvironment,
                evidenceRoot.resolve("logs/accord-control-worker-2.log"));
            assertTrue(awaitMarker(
                markerRoot.resolve(FoundationWorkerProcessMain.READY_PREFIX + "2"),
                secondWorker,
                Duration.ofSeconds(15)),
                "replacement worker process did not become ready");
            assertTrue(firstPid != secondWorker.pid(),
                "replacement worker must be a distinct JVM process");

            OutboxDispatcher.DispatchResult dispatch = dispatchFuture.get(
                45, TimeUnit.SECONDS);
            assertEquals(1, dispatch.delivered());
            assertEquals(0, dispatch.failed());
            assertFalse(dispatch.uncertain());
            assertTrue(Files.isRegularFile(markerRoot.resolve(
                FoundationWorkerProcessMain.OBSERVATION_PREFIX + "2")));

            AuthorityFacts authority = loadAuthorityFacts(
                transactions, tenantId, intentId, triggerEventId);
            assertEquals("SUCCEEDED", authority.intentState());
            assertEquals(2L, authority.reconciliationGeneration());
            assertEquals(1L, authority.completionEvents());
            assertEquals(1L, authority.outboxEvents());
            assertEquals(1L, authority.deliveryReceipts());
            assertEquals("DELIVERED", authority.triggerState());
            assertEquals("workflow." + intentId, authority.receiptId());
            assertEquals(
                receiptDigest(tenantId, intentId, ReconciliationOutcome.CONVERGED),
                authority.receiptDigest());

            String workflowId = TemporalReconciliationEventTransport.workflowId(
                new ReconciliationWorkflowRef(tenantId, intentId));
            History history = readCompleteHistory(clientStubs, workflowId);
            assertIdentifierOnlyHistory(
                history,
                tenantId,
                intentId,
                trigger,
                forbiddenHistoryFacts(apiFacts.documentDigest()));
            writeHttpReceipt(evidenceRoot, webhookFacts, apiFacts);
            secondWorker.stop(Duration.ofSeconds(15));
            assertTrue(Files.isRegularFile(markerRoot.resolve(
                FoundationWorkerProcessMain.STOPPED_PREFIX + "2")));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            Future<OutboxDispatcher.DispatchResult> ownedFuture = dispatchFuture;
            ExecutorService ownedExecutor = dispatcherExecutor;
            WorkerProcess ownedSecond = secondWorker;
            WorkerProcess ownedFirst = firstWorker;
            WorkflowServiceStubs ownedStubs = clientStubs;
            HikariDataSource ownedDataSource = dataSource;
            cleanupAll(primary,
                () -> {
                    if (ownedSecond != null) ownedSecond.close();
                },
                () -> {
                    if (ownedFirst != null) ownedFirst.close();
                },
                () -> {
                    if (ownedFuture != null && !ownedFuture.isDone()) {
                        ownedFuture.cancel(true);
                    }
                },
                () -> shutdownExecutor(ownedExecutor),
                () -> {
                    if (ownedStubs != null) shutdown(ownedStubs);
                },
                () -> {
                    if (ownedDataSource != null) ownedDataSource.close();
                });
        }
    }

    private static WebhookHttpFacts exerciseWebhookHttp(
            Path evidenceRoot, EdgeDatabase database, UUID tenantId) throws Exception {
        UUID bindingId = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        String encodedSigningSecret = requiredEnvironment(
            "ACCORD_FT17_WEBHOOK_HMAC_SECRET", "^[A-Za-z0-9+/]{43}=$");
        byte[] signingSecret = Base64.getDecoder().decode(encodedSigningSecret);
        Path temporary = Files.createTempDirectory("accord-ft17-edge-");
        Path projection = temporary.resolve("bindings.json");
        try {
            ObjectNode binding = MAPPER.createObjectNode();
            binding.put("binding_id", bindingId.toString());
            binding.put("tenant_id", tenantId.toString());
            binding.put("immutable_repository_id", WEBHOOK_REPOSITORY_ID);
            binding.put("mode", "STANDARD_REQUIRED");
            binding.put(
                "signing_token",
                "whsec_" + encodedSigningSecret);
            binding.putNull("legacy_token");
            ObjectNode projectionJson = MAPPER.createObjectNode();
            projectionJson.putArray("bindings").add(binding);
            Files.write(
                projection,
                CanonicalJson.canonicalize(MAPPER.writeValueAsBytes(projectionJson)),
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);

            Map<String, Object> properties = commonHttpProperties(
                "accord-webhook-edge",
                evidenceRoot.resolve("logs/accord-webhook-edge.log"));
            properties.put("spring.datasource.url", database.jdbcUrl());
            properties.put("spring.datasource.username", database.runtimeUser());
            properties.put("spring.datasource.password", database.runtimePassword());
            properties.put(
                "spring.datasource.hikari.connection-init-sql",
                "SET ROLE accord_webhook_runtime");
            properties.put("spring.datasource.hikari.auto-commit", "false");
            properties.put("spring.datasource.hikari.initialization-fail-timeout", "5000");
            properties.put("spring.datasource.hikari.maximum-pool-size", "4");
            properties.put("spring.flyway.enabled", "false");
            properties.put(
                "accord.webhook-edge.bindings-file",
                projection.toAbsolutePath().normalize().toString());
            properties.put(
                "accord.telemetry.sentinels",
                database.runtimePassword() + ","
                    + encodedSigningSecret);

            List<Integer> statuses = new ArrayList<>();
            try (ConfigurableApplicationContext context = startHttpApplication(
                    EmbeddedWebhookEdge.class, properties)) {
                int port = httpPort(context);
                URI endpoint = URI.create(
                    "http://127.0.0.1:" + port + "/webhooks/gitlab/" + bindingId);
                byte[] original = webhookBody("refs/heads/main");
                byte[] changed = webhookBody("refs/heads/reviewed");
                String timestamp = Long.toString(
                    java.time.Instant.now().getEpochSecond());
                statuses.add(sendWebhook(
                    endpoint, webhookId, timestamp, original, signingSecret));
                statuses.add(sendWebhook(
                    endpoint, webhookId, timestamp, original, signingSecret));
                statuses.add(sendWebhook(
                    endpoint, webhookId, timestamp, changed, signingSecret));
            }
            assertEquals(List.of(202, 202, 409), statuses);
            long inboxRows = edgeCount(database, "webhook_inbox");
            long outboxRows = edgeCount(database, "webhook_outbox");
            assertEquals(1L, inboxRows);
            assertEquals(1L, outboxRows);
            return new WebhookHttpFacts(statuses, inboxRows, outboxRows);
        } finally {
            Arrays.fill(signingSecret, (byte) 0);
            Files.deleteIfExists(projection);
            Files.deleteIfExists(temporary);
        }
    }

    private static ApiHttpFacts exerciseControlApiHttp(
            Path evidenceRoot,
            PostgreSQLContainer<?> database,
            UUID tenantId,
            UUID validationId,
            UUID correlationId) throws Exception {
        String demoCertificate = requiredEnvironment(
            "ACCORD_FT17_DEMO_CERTIFICATE", "^[A-Za-z0-9_-]{43}$");
        Map<String, Object> properties = commonHttpProperties(
            "accord-control-api",
            evidenceRoot.resolve("logs/accord-control-api.log"));
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", ControlPlaneTestRoles.API_LOGIN);
        properties.put("spring.datasource.password", ControlPlaneTestRoles.API_PASSWORD);
        properties.put(
            "spring.datasource.hikari.connection-init-sql", "SET ROLE accord_api");
        properties.put("spring.flyway.enabled", "false");
        properties.put("server.servlet.encoding.enabled", "false");
        properties.put("accord.process.role", "control-api");
        properties.put("accord.process.instance-id", "ft17-control-api");
        properties.put("accord.http.api-contract-version", "0.1.0");
        properties.put("accord.http.command-result-ttl", "PT24H");
        properties.put("accord.security.allowed-origin", "https://app.accord.test");
        properties.put("accord.security.local-demo.mode", "test-certificate");
        properties.put("accord.security.local-demo.certificate", demoCertificate);
        properties.put("accord.security.local-demo.tenant-id", tenantId.toString());
        properties.put("accord.security.local-demo.actor-id", "foundation-demo");
        properties.put(
            "accord.telemetry.sentinels",
            demoCertificate + "," + ControlPlaneTestRoles.API_PASSWORD);

        HttpExchange first;
        HttpExchange replay;
        try (ConfigurableApplicationContext context = startHttpApplication(
                EmbeddedControlApi.class, properties)) {
            URI endpoint = URI.create(
                "http://127.0.0.1:" + httpPort(context)
                    + "/v1/contract-validations/" + validationId);
            byte[] body = contractValidationBody(
                tenantId, validationId, correlationId);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Correlation-ID", correlationId.toString())
                .header("Idempotency-Key", "foundation-idempotency-0001")
                .header("If-Match", "\"0\"")
                .header(DEMO_CERTIFICATE_HEADER, demoCertificate)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            alignToServerDateWindow();
            first = send(request);
            replay = send(request);
        }

        assertEquals(201, first.status());
        assertEquals(201, replay.status());
        boolean bytesEqual = Arrays.equals(first.body(), replay.body());
        boolean headersEqual = first.headers().equals(replay.headers());
        String firstEtag = singletonHeader(first.headers(), "etag");
        boolean etagEqual = firstEtag.equals(singletonHeader(replay.headers(), "etag"));
        assertTrue(bytesEqual);
        assertTrue(headersEqual);
        assertTrue(etagEqual);
        assertEquals("\"1\"", firstEtag);

        JsonNode response = MAPPER.readTree(first.body());
        String documentDigest = requireDigest(
            requiredText((ObjectNode) response, "document_digest"));
        ControlApiRows rows = controlApiRows(
            database, tenantId, validationId, documentDigest);
        assertEquals(1L, rows.aggregateRows());
        assertEquals(1L, rows.domainEventRows());
        assertEquals(1L, rows.outboxRows());
        assertEquals(1L, rows.intentRows());
        assertEquals("RECORDED", rows.intentState());
        return new ApiHttpFacts(
            List.of(first.status(), replay.status()),
            bytesEqual,
            headersEqual,
            etagEqual,
            rows.aggregateRows(),
            rows.domainEventRows(),
            rows.outboxRows(),
            rows.intentId(),
            documentDigest);
    }

    private static Map<String, Object> commonHttpProperties(
            String serviceName, Path logFile) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.application.name", serviceName);
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.output.ansi.enabled", "never");
        properties.put("spring.jmx.enabled", "false");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        properties.put("server.shutdown", "graceful");
        properties.put("spring.lifecycle.timeout-per-shutdown-phase", "PT5S");
        properties.put("logging.file.name", logFile.toAbsolutePath().normalize().toString());
        properties.put("management.tracing.sampling.probability", "1.0");
        properties.put("accord.telemetry.endpoint", "http://127.0.0.1:4318");
        properties.put("accord.telemetry.environment", "test");
        properties.put("accord.telemetry.service-version", "0.1.0");
        properties.put("accord.telemetry.export-timeout", "PT2S");
        properties.put("accord.telemetry.shutdown-timeout", "PT5S");
        properties.put("otel.instrumentation.http.client.capture-request-headers", "");
        properties.put("otel.instrumentation.http.client.capture-response-headers", "");
        properties.put("otel.instrumentation.http.server.capture-request-headers", "");
        properties.put("otel.instrumentation.http.server.capture-response-headers", "");
        return properties;
    }

    private static ConfigurableApplicationContext startHttpApplication(
            Class<?> source, Map<String, Object> properties) {
        String[] arguments = properties.entrySet().stream()
            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new);
        return new SpringApplicationBuilder(source)
            .web(WebApplicationType.SERVLET)
            .registerShutdownHook(false)
            .run(arguments);
    }

    private static int httpPort(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static int sendWebhook(
            URI endpoint,
            UUID webhookId,
            String timestamp,
            byte[] body,
            byte[] signingSecret) throws Exception {
        String signature = Base64.getEncoder().encodeToString(
            webhookSignature(webhookId, timestamp, body, signingSecret));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("webhook-id", webhookId.toString())
            .header("Idempotency-Key", webhookId.toString())
            .header("webhook-timestamp", timestamp)
            .header("webhook-signature", "v1," + signature)
            .header("X-Gitlab-Event", "Push Hook")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        return send(request).status();
    }

    private static HttpExchange send(HttpRequest request) throws Exception {
        HttpResponse<byte[]> response = HTTP.send(
            request, HttpResponse.BodyHandlers.ofByteArray());
        return new HttpExchange(
            response.statusCode(),
            response.body(),
            Map.copyOf(response.headers().map()));
    }

    private static byte[] webhookBody(String ref) {
        return ("{\"after\":\"" + "b".repeat(40)
                + "\",\"before\":\"" + "a".repeat(40)
                + "\",\"project\":{\"id\":" + WEBHOOK_REPOSITORY_ID
                + "},\"project_id\":" + WEBHOOK_REPOSITORY_ID
                + ",\"ref\":\"" + ref + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] webhookSignature(
            UUID webhookId, String timestamp, byte[] body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(webhookId.toString().getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }

    private static byte[] contractValidationBody(
            UUID tenantId, UUID validationId, UUID correlationId) throws Exception {
        ObjectNode document = MAPPER.createObjectNode();
        document.put("event_id", UUID.randomUUID().toString());
        document.put("tenant_id", tenantId.toString());
        document.put("scope_type", "tenant");
        document.put("scope_id", tenantId.toString());
        document.put("aggregate_type", "contract-validation");
        document.put("aggregate_id", validationId.toString());
        document.put("sequence", 1);
        document.put("event_type", "contract-validation.completed");
        document.put("schema_version", "1.0.0");
        document.put("causation_id", UUID.randomUUID().toString());
        document.put("correlation_id", correlationId.toString());
        document.put("actor_id", "foundation-demo");
        document.put("occurred_at", "2026-07-27T00:00:00Z");
        document.putObject("payload");
        ObjectNode request = MAPPER.createObjectNode();
        request.put("schema_id", DOMAIN_SCHEMA);
        request.set("document", document);
        return CanonicalJson.canonicalizePreservingExactIntegers(
            MAPPER.writeValueAsBytes(request));
    }

    private static void alignToServerDateWindow() {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.currentTimeMillis() % 1_000L > 100L
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
        }
    }

    private static String singletonHeader(
            Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.size() != 1) {
            throw new IllegalStateException("HTTP response header is not singular");
        }
        return values.getFirst();
    }

    private static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    private static void writeHttpReceipt(
            Path evidenceRoot,
            WebhookHttpFacts webhook,
            ApiHttpFacts api) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", "1.0.0");
        root.put("evidence_type", "foundation-public-http-v1");
        ObjectNode edge = root.putObject("webhook_edge");
        edge.put("route", "/webhooks/gitlab/{bindingId}");
        edge.put("verification_mode", "STANDARD_REQUIRED");
        webhook.statuses().forEach(edge.putArray("statuses")::add);
        edge.put("inbox_rows", webhook.inboxRows());
        edge.put("outbox_rows", webhook.outboxRows());
        ObjectNode control = root.putObject("control_api");
        control.put("route", "/v1/contract-validations/{validationId}");
        api.statuses().forEach(control.putArray("statuses")::add);
        control.put("response_bytes_equal", api.responseBytesEqual());
        control.put("response_headers_equal", api.responseHeadersEqual());
        control.put("etag_equal", api.etagEqual());
        control.put("aggregate_rows", api.aggregateRows());
        control.put("domain_event_rows", api.domainEventRows());
        control.put("outbox_rows", api.outboxRows());
        byte[] canonical = CanonicalJson.canonicalizePreservingExactIntegers(
            MAPPER.writeValueAsBytes(root));
        byte[] terminated = Arrays.copyOf(canonical, canonical.length + 1);
        terminated[canonical.length] = '\n';
        Files.write(
            evidenceRoot.resolve("http/foundation-http-receipt.json"),
            terminated,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE);
    }

    private static void assertProductionBoundaries() throws Exception {
        assertTrue(EventTransport.class.isInterface());
        Method deliver = EventTransport.class.getDeclaredMethod(
            "deliver", LeasedEvent.class, Duration.class);
        assertEquals(DeliveryReceipt.class, deliver.getReturnType());

        assertTrue(ReconciliationWorkflowRef.class.isRecord());
        RecordComponent[] components = ReconciliationWorkflowRef.class.getRecordComponents();
        assertEquals(2, components.length);
        assertEquals("tenantId", components[0].getName());
        assertEquals(UUID.class, components[0].getType());
        assertEquals("intentId", components[1].getName());
        assertEquals(UUID.class, components[1].getType());

        Method[] providerMethods = ProviderObservationPort.class.getDeclaredMethods();
        assertEquals(1, providerMethods.length);
        assertEquals("observe", providerMethods[0].getName());
        assertEquals(ReconciliationResolution.class, providerMethods[0].getReturnType());
        assertTrue(Arrays.equals(
            new Class<?>[] {ReconciliationLease.class, Duration.class},
            providerMethods[0].getParameterTypes()));

        Class<?> observationPort = Class.forName(
            "com.inforvans.accord.controlplane.worker.temporal.workflow."
                + "ReconciliationObservationPort");
        assertTrue(observationPort.isAssignableFrom(FencedReconciliationObservation.class));
        assertFalse(observationPort.isAssignableFrom(FoundationProductLoopIT.class));
        for (Class<?> nested : FoundationProductLoopIT.class.getDeclaredClasses()) {
            assertFalse(observationPort.isAssignableFrom(nested),
                "the integration fixture must not replace the production observation port");
        }
        Constructor<?> activityConstructor =
            ReadOnlyReconciliationActivity.class.getDeclaredConstructors()[0];
        assertTrue(Arrays.equals(
            new Class<?>[] {observationPort}, activityConstructor.getParameterTypes()));
    }

    private static void assertTransportCarriesNoLeaseOrCapability(Class<?> transportType) {
        Set<Class<?>> forbiddenTypes = Set.of(
            LeasedEvent.class,
            MessageFence.class,
            ReconciliationLease.class,
            TenantWorkPermit.class);
        for (Field field : transportType.getDeclaredFields()) {
            assertFalse(forbiddenTypes.contains(field.getType()),
                "EventTransport retained a lease or capability field");
            assertFalse(field.getType().getSimpleName().contains("Capability"),
                "EventTransport retained a capability field");
        }
    }

    private static TemporalEvidence requireTemporalEvidence(Path repository)
            throws Exception {
        Path statePath = repository.resolve("infra/local/state/run-state.json");
        Path receiptPath = repository.resolve("build/verification/ft13-temporal-it.json");
        ObjectNode state = readClosedJson(statePath, RUN_STATE_FIELDS);
        assertEquals("1.0.0", requiredText(state, "schema_version"));
        assertEquals("accord-foundation-local", requiredText(state, "compose_project"));
        String runId = canonicalUuid(requiredText(state, "run_id"));

        ObjectNode attempt = requireClosedObject(
            state.get("temporal_it_attempt"),
            Set.of("run_id", "it_attempt_id", "state", "supervisor_nonce_sha256",
                "runner_pid"));
        String attemptId = canonicalUuid(requiredText(attempt, "it_attempt_id"));
        assertEquals(runId, requiredText(attempt, "run_id"));
        assertEquals("COMPLETED", requiredText(attempt, "state"));
        requireDigest(requiredText(attempt, "supervisor_nonce_sha256"));
        assertTrue(requiredLong(attempt, "runner_pid") > 0);

        ObjectNode observation = requireClosedObject(
            state.get("temporal_it_observation"),
            Set.of("run_id", "it_attempt_id", "supervisor_nonce_sha256",
                "runner_pid", "receipt_sha256", "junit_xml_sha256", "observation",
                "observation_sha256"));
        assertEquals(runId, requiredText(observation, "run_id"));
        assertEquals(attemptId, requiredText(observation, "it_attempt_id"));
        assertEquals(
            requiredText(attempt, "supervisor_nonce_sha256"),
            requiredText(observation, "supervisor_nonce_sha256"));
        assertEquals(
            requiredLong(attempt, "runner_pid"),
            requiredLong(observation, "runner_pid"));
        String boundReceiptDigest = requireDigest(
            requiredText(observation, "receipt_sha256"));
        String junitDigest = requireDigest(requiredText(observation, "junit_xml_sha256"));
        ObjectNode runnerObservation = requireClosedObject(
            observation.get("observation"),
            Set.of("run_id", "it_attempt_id", "supervisor_nonce_sha256", "runner_pid",
                "receipt_sha256", "junit_xml_sha256", "runner_descendant_verified",
                "tool_id", "executable", "argv", "exit_code", "duration_ms",
                "observed_version", "normalized_version"));
        assertEquals(runId, requiredText(runnerObservation, "run_id"));
        assertEquals(attemptId, requiredText(runnerObservation, "it_attempt_id"));
        assertTrue(requiredBoolean(runnerObservation, "runner_descendant_verified"));
        assertEquals("gradle-temporal-it", requiredText(runnerObservation, "tool_id"));
        assertEquals("java", requiredText(runnerObservation, "executable"));
        assertEquals(0L, requiredLong(runnerObservation, "exit_code"));
        assertTrue(requiredLong(runnerObservation, "duration_ms") > 0);
        ObjectNode observationPreimage = observation.deepCopy();
        String observationDigest = requireDigest(
            requiredText(observationPreimage, "observation_sha256"));
        observationPreimage.remove("observation_sha256");
        assertEquals(observationDigest, canonicalDigest(observationPreimage));

        ObjectNode envelope = readClosedJson(
            receiptPath, Set.of("receipt", "receipt_sha256"));
        ObjectNode receipt = requireClosedObject(envelope.get("receipt"), RECEIPT_FIELDS);
        String receiptDigest = requireDigest(requiredText(envelope, "receipt_sha256"));
        assertEquals(receiptDigest, canonicalDigest(receipt));
        assertEquals(boundReceiptDigest, receiptDigest);
        assertEquals(runId, requiredText(receipt, "run_id"));
        assertEquals(attemptId, requiredText(receipt, "it_attempt_id"));
        assertEquals("1.0.0", requiredText(receipt, "schema_version"));
        assertEquals("accord-foundation-temporal-local-v1",
            requiredText(receipt, "suite_name"));
        assertEquals("PASS", requiredText(receipt, "status"));
        assertEquals(7L, requiredLong(receipt, "test_count"));
        assertEquals(Set.of(
                "production-worker-reconciliation",
                "ui-client",
                "plaintext-rejected",
                "missing-client-certificate-rejected",
                "wrong-client-ca-rejected",
                "wrong-server-name-rejected",
                "wrong-client-eku-rejected"),
            Set.copyOf(stringValues(receipt.get("case_ids"))));
        assertEquals(
            "com.inforvans.accord.controlplane.worker.temporal.TemporalLocalTopologyIT",
            requiredText(receipt, "class_name"));
        assertEquals(TemporalRuntimeConfiguration.class.getName(),
            requiredText(receipt, "configuration_class"));
        assertEquals(TemporalWorkerLifecycle.class.getName(),
            requiredText(receipt, "lifecycle_class"));
        assertTrue(requiredBoolean(receipt, "worker_started"));
        assertTrue(requiredBoolean(receipt, "worker_stopped"));
        assertEquals(ReconciliationOutcome.CONVERGED.name(),
            requiredText(receipt, "workflow_outcome"));
        assertEquals(0L, requiredLong(receipt, "mutation_calls"));
        ObjectNode junit = requireClosedObject(
            receipt.get("junit"), Set.of("tests", "failures", "errors", "skips"));
        assertEquals(7L, requiredLong(junit, "tests"));
        assertEquals(0L, requiredLong(junit, "failures"));
        assertEquals(0L, requiredLong(junit, "errors"));
        assertEquals(0L, requiredLong(junit, "skips"));
        ObjectNode exitSemantics = requireClosedObject(
            receipt.get("exit_semantics"), Set.of("PASS", "FAIL", "BLOCKED"));
        assertEquals(0L, requiredLong(exitSemantics, "PASS"));
        assertEquals(1L, requiredLong(exitSemantics, "FAIL"));
        assertEquals(2L, requiredLong(exitSemantics, "BLOCKED"));

        Path junitPath = repository.resolve(
            "tests/integration/build/test-results/temporalLocalTopologyTest/"
                + "TEST-com.inforvans.accord.controlplane.worker.temporal."
                + "TemporalLocalTopologyIT.xml");
        assertEquals(junitDigest, sha256(readRegularFile(junitPath)));

        ObjectNode endpoints = requireClosedObject(
            state.get("local_endpoints"), Set.of("postgres", "temporal", "temporal_ui"));
        String endpoint = requiredText(endpoints, "temporal");
        assertEquals("127.0.0.1:7233", endpoint);
        ObjectNode ready = requireClosedObject(
            state.get("ready"), Set.copyOf(stringValues(state.get("dependency_services"))));
        ready.properties().forEach(entry -> assertTrue(entry.getValue().asBoolean(false)));
        return new TemporalEvidence("grpcs://" + endpoint, receiptDigest);
    }

    private static void migrate(PostgreSQLContainer<?> postgres) {
        ControlPlaneTestRoles.bootstrap(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway flyway = Flyway.configure()
            .dataSource(
                postgres.getJdbcUrl(),
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD)
            .initSql("SET ROLE accord_migrator")
            .locations("classpath:db/migration")
            .target("004")
            .load();
        flyway.migrate();
        assertEquals(
            List.of("001", "002", "003", "004"),
            Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().toString())
                .toList());
    }

    private static EdgeDatabase migrateWebhookEdge(
            Path repository, PostgreSQLContainer<?> postgres) throws Exception {
        String migratorPassword = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(randomBytes(32));
        String runtimePassword = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(randomBytes(32));
        String bootstrap = Files.readString(
            repository.resolve(
                "database/webhook-edge/bootstrap/00-pre-flyway-roles.sql"),
            StandardCharsets.UTF_8).replace("\\set ON_ERROR_STOP on", "");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(bootstrap);
            statement.execute(
                "ALTER ROLE accord_webhook_migrator_login PASSWORD '"
                    + migratorPassword + "'");
            statement.execute(
                "ALTER ROLE accord_webhook_runtime_login PASSWORD '"
                    + runtimePassword + "'");
        }
        String migrationPath = repository.resolve("database/webhook-edge/migrations")
            .toAbsolutePath().normalize().toString().replace('\\', '/');
        Flyway flyway = Flyway.configure()
            .dataSource(
                postgres.getJdbcUrl(),
                "accord_webhook_migrator_login",
                migratorPassword)
            .initSql("SET ROLE accord_webhook_owner")
            .defaultSchema("public")
            .locations("filesystem:" + migrationPath)
            .load();
        flyway.migrate();
        assertEquals(
            List.of("001"),
            Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().toString())
                .toList());
        return new EdgeDatabase(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            "accord_webhook_runtime_login",
            runtimePassword);
    }

    private static long edgeCount(EdgeDatabase database, String table)
            throws Exception {
        if (!Set.of("webhook_inbox", "webhook_outbox").contains(table)) {
            throw new IllegalArgumentException("unsafe edge table");
        }
        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.adminUser(), database.adminPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                "SELECT count(*) FROM public." + table)) {
            assertTrue(rows.next());
            long count = rows.getLong(1);
            assertFalse(rows.next());
            return count;
        }
    }

    private static ControlApiRows controlApiRows(
            PostgreSQLContainer<?> database,
            UUID tenantId,
            UUID validationId,
            String documentDigest)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
            DSLContext admin = DSL.using(connection, SQLDialect.POSTGRES);
            Record row = admin.fetchOne("""
                SELECT
                  (SELECT count(*) FROM public.contract_validation
                   WHERE tenant_id=? AND validation_id=?) AS validation_rows,
                  (SELECT count(*) FROM public.aggregate_head
                   WHERE tenant_id=? AND aggregate_type='contract-validation'
                     AND aggregate_id=?) AS aggregate_rows,
                  (SELECT count(*) FROM public.domain_event
                   WHERE tenant_id=? AND aggregate_type='contract-validation'
                     AND aggregate_id=?) AS domain_event_rows,
                  (SELECT count(*) FROM public.outbox_event event
                   WHERE event.tenant_id=? AND EXISTS (
                     SELECT 1 FROM public.domain_event domain
                     WHERE domain.tenant_id=event.tenant_id
                       AND domain.event_id=event.event_id
                       AND domain.aggregate_type='contract-validation'
                       AND domain.aggregate_id=?)) AS outbox_rows,
                  (SELECT event.payload::text
                   FROM public.outbox_event event
                   JOIN public.domain_event domain
                     ON domain.tenant_id=event.tenant_id
                    AND domain.event_id=event.event_id
                   WHERE domain.tenant_id=?
                     AND domain.aggregate_type='contract-validation'
                     AND domain.aggregate_id=?) AS outbox_payload
                """,
                tenantId, validationId,
                tenantId, validationId,
                tenantId, validationId,
                tenantId, validationId,
                tenantId, validationId);
            assertNotNull(row);
            assertEquals(1L, required(row, "validation_rows", Long.class));

            ObjectNode payload = requireClosedObject(
                MAPPER.readTree(required(row, "outbox_payload", String.class)),
                CONTRACT_VALIDATION_EVENT_FIELDS);
            UUID intentId = UUID.fromString(canonicalUuid(
                requiredText(payload, "intent_id")));
            assertFalse(intentId.equals(validationId));
            assertEquals(validationId.toString(), requiredText(payload, "validation_id"));
            assertEquals(DOMAIN_SCHEMA, requiredText(payload, "schema_id"));
            assertEquals(documentDigest, requireDigest(
                requiredText(payload, "document_digest")));
            assertEquals(1L, requiredLong(payload, "version"));

            Record intent = admin.fetchOne("""
                SELECT intent_id,logical_action_key,scope_type,scope_id,provider,
                       provider_installation_id,provider_repository_id,operation,
                       request_reference_type,request_reference_id,
                       request_reference_version,request_digest,state
                FROM public.external_call_intent
                WHERE tenant_id=? AND intent_id=?
                """, tenantId, intentId);
            assertNotNull(intent);
            assertEquals(intentId, required(intent, "intent_id", UUID.class));
            assertEquals("contract-validation.reconcile:" + intentId,
                required(intent, "logical_action_key", String.class));
            assertEquals("tenant", required(intent, "scope_type", String.class));
            assertEquals(tenantId.toString(), required(intent, "scope_id", String.class));
            assertEquals("accord", required(intent, "provider", String.class));
            assertEquals("control-api",
                required(intent, "provider_installation_id", String.class));
            assertTrue(intent.get("provider_repository_id", String.class) == null);
            assertEquals("contract.validation",
                required(intent, "operation", String.class));
            assertEquals("contract-validation",
                required(intent, "request_reference_type", String.class));
            assertEquals(validationId.toString(),
                required(intent, "request_reference_id", String.class));
            assertEquals(1L,
                required(intent, "request_reference_version", Long.class));
            assertEquals(documentDigest,
                required(intent, "request_digest", String.class));
            String intentState = required(intent, "state", String.class);
            return new ControlApiRows(
                required(row, "aggregate_rows", Long.class),
                required(row, "domain_event_rows", Long.class),
                required(row, "outbox_rows", Long.class),
                1L,
                intentState,
                intentId);
        }
    }

    private static void awaitReconciliationClaim(
            WorkerTenantTransactions transactions, UUID tenantId, UUID intentId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Record state = transactions.inTenant(tenantId, tx -> tx.fetchOne("""
                SELECT state,reconciliation_generation
                FROM public.external_call_intent
                WHERE tenant_id=? AND intent_id=?
                """, tenantId, intentId));
            if (state != null
                    && "RECONCILING".equals(state.get("state", String.class))
                    && Long.valueOf(1L).equals(
                        state.get("reconciliation_generation", Long.class))) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        fail("first worker did not persist its PostgreSQL reconciliation claim");
    }

    private static List<String> forbiddenHistoryFacts(String requestDigest) {
        return List.of(
            "accord",
            "control-api",
            "contract.validation",
            "contract-validation",
            requireDigest(requestDigest),
            "sha256:" + "b".repeat(64));
    }

    private static Map<String, String> workerProcessEnvironment(
            TemporalConnectionProperties temporal,
            String jdbcUrl,
            UUID tenantId,
            UUID intentId,
            Path markerRoot) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("ACCORD_FT17_TEMPORAL_ENDPOINT", temporal.endpoint());
        environment.put("ACCORD_FT17_TEMPORAL_NAMESPACE", temporal.namespace());
        environment.put("ACCORD_FT17_TEMPORAL_TASK_QUEUE", temporal.taskQueue());
        environment.put("ACCORD_FT17_TEMPORAL_SERVER_NAME", temporal.serverName());
        environment.put("ACCORD_FT17_TEMPORAL_SECRET_ROOT", temporal.secretRoot().toString());
        environment.put(
            "ACCORD_FT17_TEMPORAL_CLIENT_CERTIFICATE",
            temporal.clientCertificate().toString());
        environment.put(
            "ACCORD_FT17_TEMPORAL_CLIENT_PRIVATE_KEY",
            temporal.clientPrivateKey().toString());
        environment.put(
            "ACCORD_FT17_TEMPORAL_TRUST_CERTIFICATE",
            temporal.trustCertificate().toString());
        environment.put("ACCORD_FT17_JDBC_URL", jdbcUrl);
        environment.put("ACCORD_FT17_JDBC_USER", ControlPlaneTestRoles.WORKER_LOGIN);
        environment.put("ACCORD_FT17_JDBC_PASSWORD", ControlPlaneTestRoles.WORKER_PASSWORD);
        environment.put("ACCORD_FT17_TENANT_ID", tenantId.toString());
        environment.put("ACCORD_FT17_INTENT_ID", intentId.toString());
        environment.put("ACCORD_FT17_MARKER_ROOT", markerRoot.toString());
        environment.put("ACCORD_FT17_TELEMETRY_ENDPOINT", "http://127.0.0.1:4318");
        environment.put(
            "ACCORD_FT17_WEBHOOK_HMAC_SECRET",
            requiredEnvironment(
                "ACCORD_FT17_WEBHOOK_HMAC_SECRET", "^[A-Za-z0-9+/]{43}=$"));
        environment.put(
            "ACCORD_FT17_DEMO_CERTIFICATE",
            requiredEnvironment(
                "ACCORD_FT17_DEMO_CERTIFICATE", "^[A-Za-z0-9_-]{43}$"));
        return Map.copyOf(environment);
    }

    private static String requiredEnvironment(String name, String expression) {
        String value = System.getenv(name);
        if (value == null || !value.matches(expression)) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static boolean awaitMarker(
            Path marker, WorkerProcess worker, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return true;
            if (!worker.isAlive()) return false;
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
            if (Thread.interrupted()) throw new InterruptedException();
        }
        return false;
    }

    private static String testRuntimeClasspath() throws Exception {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(
                java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
        for (ClassLoader loader = FoundationProductLoopIT.class.getClassLoader();
                loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        entries.add(Path.of(url.toURI()).toRealPath().toString());
                    }
                }
            }
        }
        assertTrue(entries.stream().anyMatch(entry ->
            entry.contains("classes\\java\\test")
                || entry.contains("classes/java/test")),
            "Gradle test runtime classpath is unavailable");
        return String.join(File.pathSeparator, entries);
    }

    private static void shutdownExecutor(ExecutorService executor)
            throws InterruptedException {
        if (executor == null) return;
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
            "dispatcher executor did not terminate");
    }

    private static void cleanupAll(Throwable primary, Cleanup... cleanups)
            throws Exception {
        Throwable failure = primary;
        for (Cleanup cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (Exception | Error cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
        }
        if (primary == null && failure != null) {
            if (failure instanceof Exception exception) throw exception;
            throw (Error) failure;
        }
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws Exception;
    }

    private static AuthorityFacts loadAuthorityFacts(
            WorkerTenantTransactions transactions,
            UUID tenantId,
            UUID intentId,
            UUID triggerEventId) {
        Record row = transactions.inTenant(tenantId, tx -> tx.fetchOne("""
            SELECT intent.state AS intent_state,
                   intent.reconciliation_generation,
                   (SELECT count(*) FROM public.domain_event event
                    WHERE event.tenant_id=intent.tenant_id
                      AND event.aggregate_type='external_intent'
                      AND event.aggregate_id=intent.intent_id
                      AND event.event_type='external_intent.completed') AS completion_events,
                   (SELECT count(*) FROM public.outbox_event event
                    WHERE event.tenant_id=intent.tenant_id
                      AND EXISTS (
                        SELECT 1 FROM public.domain_event domain
                        WHERE domain.tenant_id=event.tenant_id
                          AND domain.event_id=event.event_id
                          AND domain.aggregate_type='external_intent'
                          AND domain.aggregate_id=intent.intent_id)) AS outbox_events,
                   (SELECT count(*) FROM public.outbox_delivery_receipt receipt
                    WHERE receipt.tenant_id=intent.tenant_id) AS delivery_receipts,
                   (SELECT state FROM public.outbox_event event
                    WHERE event.tenant_id=intent.tenant_id AND event.event_id=?)
                     AS trigger_state,
                   (SELECT receipt_id FROM public.outbox_delivery_receipt receipt
                    WHERE receipt.tenant_id=intent.tenant_id AND receipt.event_id=?)
                     AS receipt_id,
                   (SELECT receipt_digest FROM public.outbox_delivery_receipt receipt
                    WHERE receipt.tenant_id=intent.tenant_id AND receipt.event_id=?)
                     AS receipt_digest
            FROM public.external_call_intent intent
            WHERE intent.tenant_id=? AND intent.intent_id=?
            """, triggerEventId, triggerEventId, triggerEventId, tenantId, intentId));
        assertNotNull(row);
        return new AuthorityFacts(
            required(row, "intent_state", String.class),
            required(row, "reconciliation_generation", Long.class),
            required(row, "completion_events", Long.class),
            required(row, "outbox_events", Long.class),
            required(row, "delivery_receipts", Long.class),
            required(row, "trigger_state", String.class),
            required(row, "receipt_id", String.class),
            required(row, "receipt_digest", String.class));
    }

    private static void assertIdentifierOnlyHistory(
            History history,
            UUID tenantId,
            UUID intentId,
            LeasedEvent trigger,
            List<String> providerFacts) {
        assertFalse(history.getEventsList().isEmpty());
        String serialized = new String(
            history.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(serialized.contains(tenantId.toString()));
        assertTrue(serialized.contains(intentId.toString()));
        List<String> forbidden = new ArrayList<>(providerFacts);
        forbidden.add(trigger.eventId().toString());
        forbidden.add(trigger.destination());
        forbidden.add(trigger.payloadSchema());
        forbidden.add(trigger.fence().owner());
        forbidden.add(trigger.fence().token().toString());
        for (String value : forbidden) {
            assertFalse(serialized.contains(value),
                "Temporal history contains an outbox lease or Provider fact");
        }
    }

    private static History readCompleteHistory(
            WorkflowServiceStubs stubs, String workflowId) {
        History.Builder history = History.newBuilder();
        com.google.protobuf.ByteString pageToken =
            com.google.protobuf.ByteString.EMPTY;
        do {
            var response = stubs.blockingStub()
                .withDeadlineAfter(RPC_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)
                .getWorkflowExecutionHistory(
                    GetWorkflowExecutionHistoryRequest.newBuilder()
                        .setNamespace(NAMESPACE)
                        .setExecution(WorkflowExecution.newBuilder()
                            .setWorkflowId(workflowId)
                            .build())
                        .setNextPageToken(pageToken)
                        .build());
            history.addAllEvents(response.getHistory().getEventsList());
            pageToken = response.getNextPageToken();
        } while (!pageToken.isEmpty());
        return history.build();
    }

    private static TemporalServiceStubsFactory productionStubsFactory()
            throws IOException {
        TemporalSecretAclPolicy aclPolicy;
        String osName = System.getProperty("os.name", "");
        if (osName.startsWith("Windows")) {
            aclPolicy = new WindowsTemporalSecretAclPolicy();
        } else if (osName.startsWith("Linux") || osName.startsWith("Mac")
                || osName.startsWith("FreeBSD") || osName.startsWith("Unix")) {
            aclPolicy = new PosixTemporalSecretAclPolicy();
        } else {
            throw new IOException("unsupported Temporal secret ACL platform");
        }
        return new TemporalServiceStubsFactory(
            new TemporalSecretFileLoader(aclPolicy));
    }

    private static void describeNamespace(WorkflowServiceStubs stubs) {
        stubs.connect(RPC_TIMEOUT);
        stubs.blockingStub()
            .withDeadlineAfter(RPC_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)
            .describeNamespace(DescribeNamespaceRequest.newBuilder()
                .setNamespace(NAMESPACE)
                .build());
    }

    private static void shutdown(WorkflowServiceStubs stubs) {
        stubs.shutdown();
        if (!stubs.awaitTermination(
                SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
            stubs.shutdownNow();
            stubs.awaitTermination(
                SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        }
        assertTrue(stubs.isTerminated());
    }

    private static HikariDataSource workerDataSource(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(ControlPlaneTestRoles.WORKER_LOGIN);
        config.setPassword(ControlPlaneTestRoles.WORKER_PASSWORD);
        config.setConnectionInitSql("SET ROLE accord_worker");
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setMaximumPoolSize(4);
        config.addDataSourceProperty("connectTimeout", "2");
        config.addDataSourceProperty("socketTimeout", "4");
        config.addDataSourceProperty("cancelSignalTimeout", "2");
        config.addDataSourceProperty("options", String.join(" ",
            "-c statement_timeout=2000",
            "-c lock_timeout=1000",
            "-c idle_in_transaction_session_timeout=3000",
            "-c transaction_timeout=6000"));
        return new HikariDataSource(config);
    }

    private static DSLContext callerOwnedDsl(HikariDataSource dataSource) {
        TransactionAwareDataSourceProxy connections =
            new TransactionAwareDataSourceProxy(dataSource);
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(SQLDialect.POSTGRES);
        configuration.set(new DataSourceConnectionProvider(connections));
        configuration.set(new SpringTransactionProvider(
            new DataSourceTransactionManager(dataSource)));
        return DSL.using(configuration);
    }

    private static String receiptDigest(
            UUID tenantId, UUID intentId, ReconciliationOutcome outcome) {
        try {
            byte[] canonical = CanonicalJson.canonicalize(MAPPER.writeValueAsBytes(Map.of(
                "tenant_id", tenantId.toString(),
                "intent_id", intentId.toString(),
                "outcome", outcome.name())));
            return sha256(canonical);
        } catch (IOException impossible) {
            throw new IllegalStateException("closed receipt is not serializable", impossible);
        }
    }

    private static Path requireEvidenceDirectories(Path repository)
            throws IOException {
        String configured = System.getenv("ACCORD_FOUNDATION_EVIDENCE_DIR");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                "ACCORD_FOUNDATION_EVIDENCE_DIR is required");
        }
        Path root = Path.of(configured);
        if (!root.isAbsolute() || !root.equals(root.normalize())) {
            throw new IllegalStateException(
                "ACCORD_FOUNDATION_EVIDENCE_DIR must be a normalized absolute path");
        }
        BasicFileAttributes rootAttributes = Files.readAttributes(
            root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!rootAttributes.isDirectory() || rootAttributes.isSymbolicLink()) {
            throw new IllegalStateException("foundation evidence root is not a directory");
        }
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireEvidenceChild(realRoot, "logs");
        requireEvidenceChild(realRoot, "telemetry");
        requireEvidenceChild(realRoot, "http");
        assertFalse(realRoot.equals(repository.toRealPath()),
            "evidence directory cannot replace the repository root");
        return realRoot;
    }

    private static Path requireEvidenceChild(Path root, String name) throws IOException {
        Path child = root.resolve(name);
        if (Files.notExists(child, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(child);
        }
        BasicFileAttributes attributes = Files.readAttributes(
            child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IllegalStateException("foundation evidence child is not a directory");
        }
        Path real = child.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.getParent().equals(root)) {
            throw new IllegalStateException("foundation evidence child escaped its root");
        }
        return real;
    }

    private static ObjectNode readClosedJson(Path path, Set<String> fields)
            throws IOException {
        ObjectNode object = requireClosedObject(MAPPER.readTree(readRegularFile(path)), fields);
        return object;
    }

    private static byte[] readRegularFile(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.size() < 2
                || attributes.size() > 1_048_576) {
            throw new IllegalStateException("foundation evidence file is invalid");
        }
        return Files.readAllBytes(path);
    }

    private static ObjectNode requireClosedObject(JsonNode value, Set<String> fields) {
        if (!(value instanceof ObjectNode object) || !fieldNames(object).equals(fields)) {
            throw new IllegalStateException("foundation evidence object is not closed");
        }
        return object;
    }

    private static Set<String> fieldNames(ObjectNode object) {
        Set<String> result = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return Set.copyOf(result);
    }

    private static List<String> stringValues(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new IllegalStateException("foundation evidence array is invalid");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalStateException("foundation evidence array is invalid");
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private static String requiredText(ObjectNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("foundation evidence text is invalid");
        }
        return value.textValue();
    }

    private static long requiredLong(ObjectNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalStateException("foundation evidence integer is invalid");
        }
        return value.longValue();
    }

    private static boolean requiredBoolean(ObjectNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException("foundation evidence boolean is invalid");
        }
        return value.booleanValue();
    }

    private static String canonicalUuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return value;
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("foundation evidence UUID is invalid", failure);
        }
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IllegalStateException("foundation evidence digest is invalid");
        }
        return value;
    }

    private static String canonicalDigest(JsonNode value) throws IOException {
        return sha256(CanonicalJson.canonicalize(MAPPER.writeValueAsBytes(value)));
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Path locateRepositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(
                    candidate.resolve("settings.gradle"), LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("repository root is unavailable");
    }

    private static <T> T required(Record row, String field, Class<T> type) {
        T value = Objects.requireNonNull(row, "required database row").get(field, type);
        return Objects.requireNonNull(value, field + " is unexpectedly null");
    }

    private record TemporalEvidence(String endpoint, String receiptDigest) {}

    private record EdgeDatabase(
        String jdbcUrl,
        String adminUser,
        String adminPassword,
        String runtimeUser,
        String runtimePassword
    ) {}

    private record WebhookHttpFacts(
        List<Integer> statuses,
        long inboxRows,
        long outboxRows
    ) {
        private WebhookHttpFacts {
            statuses = List.copyOf(statuses);
        }
    }

    private record ApiHttpFacts(
        List<Integer> statuses,
        boolean responseBytesEqual,
        boolean responseHeadersEqual,
        boolean etagEqual,
        long aggregateRows,
        long domainEventRows,
        long outboxRows,
        UUID intentId,
        String documentDigest
    ) {
        private ApiHttpFacts {
            statuses = List.copyOf(statuses);
        }
    }

    private record ControlApiRows(
        long aggregateRows,
        long domainEventRows,
        long outboxRows,
        long intentRows,
        String intentState,
        UUID intentId
    ) {}

    private record HttpExchange(
        int status,
        byte[] body,
        Map<String, List<String>> headers
    ) {
        private HttpExchange {
            body = body.clone();
            Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
            headers.forEach((name, values) ->
                copiedHeaders.put(name, List.copyOf(values)));
            headers = Map.copyOf(copiedHeaders);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private record AuthorityFacts(
        String intentState,
        long reconciliationGeneration,
        long completionEvents,
        long outboxEvents,
        long deliveryReceipts,
        String triggerState,
        String receiptId,
        String receiptDigest
    ) {}

    @EnableAutoConfiguration
    @ComponentScan("com.inforvans.accord.webhookedge")
    private static final class EmbeddedWebhookEdge {}

    @EnableAutoConfiguration
    @ComponentScan("com.inforvans.accord.controlplane.http")
    private static final class EmbeddedControlApi {
        @Bean
        DemoIdentityFilter foundationDemoIdentityAdapter(
                @Value("${accord.security.local-demo.mode}") String mode,
                @Value("${accord.security.local-demo.certificate}") String certificate,
                @Value("${accord.security.local-demo.tenant-id}") UUID tenantId,
                @Value("${accord.security.local-demo.actor-id}") String actorId) {
            return new DemoIdentityFilter(mode, certificate, tenantId, actorId);
        }

        @Bean
        FilterRegistrationBean<DemoIdentityFilter> foundationDemoIdentityRegistration(
                DemoIdentityFilter filter) {
            FilterRegistrationBean<DemoIdentityFilter> registration =
                new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }
    }

    private static final class DemoIdentityFilter extends OncePerRequestFilter
            implements FoundationIdentityAdapter {
        private static final String MODE = "test-certificate";

        private final byte[] certificateDigest;
        private final FoundationVerifiedPrincipal principal;

        private DemoIdentityFilter(
                String mode,
                String certificate,
                UUID tenantId,
                String actorId) {
            if (!MODE.equals(mode)
                    || certificate == null
                    || !certificate.matches("^[A-Za-z0-9_-]{43}$")) {
                throw new IllegalArgumentException(
                    "foundation demo identity configuration is invalid");
            }
            certificateDigest = rawSha256(certificate.getBytes(StandardCharsets.UTF_8));
            principal = new FoundationVerifiedPrincipal.Bearer(tenantId, actorId);
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String remoteAddress = request.getRemoteAddr();
            String certificate = singletonRequestHeader(
                request, DEMO_CERTIFICATE_HEADER);
            if (!isLoopback(remoteAddress)
                    || certificate == null
                    || !MessageDigest.isEqual(
                        certificateDigest,
                        rawSha256(certificate.getBytes(StandardCharsets.UTF_8)))) {
                chain.doFilter(request, response);
                return;
            }

            Authentication previous = SecurityContextHolder.getContext()
                .getAuthentication();
            try {
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                        principal, null, List.of()));
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.getContext().setAuthentication(previous);
            }
        }

        private static boolean isLoopback(String address) {
            return "127.0.0.1".equals(address)
                || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
        }

        private static String singletonRequestHeader(
                HttpServletRequest request, String name) {
            var values = request.getHeaders(name);
            if (values == null || !values.hasMoreElements()) {
                return null;
            }
            String value = values.nextElement();
            return values.hasMoreElements() ? null : value;
        }

        private static byte[] rawSha256(byte[] value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
    }

    private static final class WorkerProcess {
        private final Process process;
        private final BufferedWriter input;
        private boolean inputClosed;

        private WorkerProcess(Process process) {
            this.process = process;
            this.input = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.US_ASCII));
        }

        static WorkerProcess start(
                int epoch, Map<String, String> environment, Path logPath)
                throws Exception {
            Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows")
                    ? "java.exe" : "java");
            ProcessBuilder builder = new ProcessBuilder(
                javaExecutable.toString(),
                "-classpath",
                testRuntimeClasspath(),
                FoundationWorkerProcessMain.class.getName(),
                Integer.toString(epoch));
            builder.environment().putAll(environment);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(logPath.toFile()));
            Process process = builder.start();
            return new WorkerProcess(process);
        }

        long pid() {
            return process.pid();
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void destroyForcibly(Duration timeout) throws Exception {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS),
                "forced worker process did not exit");
            closeInput();
        }

        void stop(Duration timeout) throws Exception {
            if (!process.isAlive()) {
                throw new IllegalStateException("replacement worker exited before stop");
            }
            input.write("STOP\n");
            input.flush();
            assertTrue(process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS),
                "replacement worker did not stop");
            assertEquals(0, process.exitValue(), "replacement worker exit code");
            closeInput();
        }

        private void closeInput() throws IOException {
            if (!inputClosed) {
                inputClosed = true;
                input.close();
            }
        }

        public void close() throws Exception {
            Throwable primary = null;
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS),
                        "owned worker process did not exit during cleanup");
                }
            } catch (Exception | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                cleanupAll(primary, this::closeInput);
            }
        }
    }
}
