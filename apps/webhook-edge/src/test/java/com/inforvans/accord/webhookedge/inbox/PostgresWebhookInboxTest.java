package com.inforvans.accord.webhookedge.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inforvans.accord.webhookedge.binding.WebhookBinding;
import com.inforvans.accord.webhookedge.binding.WebhookVerificationMode;
import com.inforvans.accord.webhookedge.security.GitLabWebhookVerifier;
import com.inforvans.accord.webhookedge.webhook.ProviderWebhookSignal;
import com.inforvans.accord.webhookedge.webhook.WebhookHandler;
import com.inforvans.accord.webhookedge.webhook.WebhookInbox;
import com.inforvans.accord.webhookedge.webhook.WebhookRecordOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class PostgresWebhookInboxTest {
    private static final UUID TENANT_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000022");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000099");
    private static final UUID BINDING_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000011");
    private static final UUID OTHER_BINDING_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000012");
    private static final UUID DELIVERY_ID = UUID.fromString("019adf8e-04e8-7000-8000-000000000033");
    private static final long REPOSITORY_ID = 4_294_967_296L;
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-26T01:02:03Z");
    private static final byte[] SIGNING_SECRET =
            "0123456789abcdefghijklmnopqrstuv".getBytes(StandardCharsets.US_ASCII);
    private static final String DIGEST_A = "sha256:" + "a".repeat(64);
    private static final String DIGEST_B = "sha256:" + "b".repeat(64);
    private static final Set<String> SIGNAL_FIELDS = Set.of(
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
    private static final List<String> FORBIDDEN_PAYLOAD_TERMS = List.of(
            "url",
            "hostname",
            "commit",
            "commits",
            "message",
            "author",
            "user",
            "token",
            "secret",
            "signature",
            "credential",
            "header",
            "headers",
            "raw_body",
            "source",
            "archive",
            "diff");

    private WebhookDatabaseFixture database;

    @BeforeAll
    void startDatabase() {
        database = new WebhookDatabaseFixture();
        database.start();
    }

    @AfterAll
    void stopDatabase() {
        database.close();
    }

    @BeforeEach
    void clearTables() throws Exception {
        database.truncate();
    }

    @Test
    void firstDeliveryWritesExactlyOneInboxAndOneClosedOutboxSignal() throws Exception {
        PostgresWebhookInbox inbox = inbox();

        assertThat(inbox.record(signal(TENANT_ID, DIGEST_A))).isEqualTo(WebhookRecordOutcome.RECORDED);

        assertThat(database.adminCount("webhook_inbox")).isEqualTo(1);
        assertThat(database.adminCount("webhook_outbox")).isEqualTo(1);
        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT signal FROM public.webhook_outbox")) {
            assertThat(rows.next()).isTrue();
            com.fasterxml.jackson.databind.JsonNode stored = new ObjectMapper().readTree(rows.getString(1));
            Set<String> names = new java.util.HashSet<>();
            stored.fieldNames().forEachRemaining(names::add);
            assertThat(names).isEqualTo(SIGNAL_FIELDS);
            assertThat(stored.path("tenant_id").asText()).isEqualTo(TENANT_ID.toString());
            assertThat(stored.path("scope_id").asText()).isEqualTo(BINDING_ID.toString());
            assertThat(stored.path("body_digest").asText()).isEqualTo(DIGEST_A);
        }
    }

    @Test
    void nonMicrosecondObservedAtPersistsWithoutApplicationNormalization() throws Exception {
        String observedAt = "2026-07-26T01:02:03.123456789Z";

        assertThat(inbox().record(signalAt(TENANT_ID, DIGEST_A, observedAt)))
                .isEqualTo(WebhookRecordOutcome.RECORDED);

        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT signal->>'observed_at' FROM public.webhook_outbox")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(observedAt);
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void signedServletRequestPersistsOnlyTheClosedSignalThroughTheRealDatabase() throws Exception {
        String urlSentinel = "https://must-not-persist.invalid/private";
        String messageSentinel = "must-not-persist-commit-message";
        byte[] body = ("{\"project_id\":" + REPOSITORY_ID
                + ",\"project\":{\"id\":" + REPOSITORY_ID + ",\"web_url\":\"" + urlSentinel + "\"}"
                + ",\"ref\":\"refs/heads/main\",\"before\":\"" + "1".repeat(40)
                + "\",\"after\":\"" + "2".repeat(40)
                + "\",\"commits\":[{\"message\":\"" + messageSentinel + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(OBSERVED_AT.getEpochSecond());
        WebhookBinding binding = new WebhookBinding(
                BINDING_ID,
                TENANT_ID,
                REPOSITORY_ID,
                WebhookVerificationMode.STANDARD_REQUIRED,
                "whsec_" + Base64.getEncoder().encodeToString(SIGNING_SECRET),
                null);
        WebhookHandler handler = new WebhookHandler(
                requested -> requested.equals(BINDING_ID) ? java.util.Optional.of(binding) : java.util.Optional.empty(),
                new GitLabWebhookVerifier(Clock.fixed(OBSERVED_AT, ZoneOffset.UTC)),
                inbox());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        request.setContentType("application/json");
        request.addHeader("webhook-id", DELIVERY_ID.toString());
        request.addHeader("webhook-timestamp", timestamp);
        request.addHeader("webhook-signature", "v1," + Base64.getEncoder().encodeToString(
                standardSignature(body, DELIVERY_ID.toString(), timestamp)));
        request.addHeader("Idempotency-Key", DELIVERY_ID.toString());
        request.addHeader("X-Gitlab-Event", "Push Hook");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.receive(BINDING_ID.toString(), request, response);

        assertThat(response.getStatus()).isEqualTo(202);
        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT inbox.body_digest,outbox.signal
                        FROM public.webhook_inbox inbox
                        JOIN public.webhook_outbox outbox
                          USING (tenant_id,provider,immutable_repository_id,webhook_id)
                        """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).startsWith("sha256:").hasSize(71);
            var stored = new ObjectMapper().readTree(rows.getString(2));
            assertThat(stored.path("immutable_repository_id").asLong()).isEqualTo(REPOSITORY_ID);
            assertThat(stored.path("delivery_id").asText()).isEqualTo(DELIVERY_ID.toString());
            assertThat(stored.toString()).doesNotContain(urlSentinel, messageSentinel, "commits");
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void inboxAndOutboxUseTheFrozenNaturalAndSignalKeys() throws Exception {
        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                    SELECT relation.relname || ':' || catalog_constraint.conname || ':'
                           || pg_catalog.pg_get_constraintdef(catalog_constraint.oid)
                    FROM pg_catalog.pg_constraint catalog_constraint
                    JOIN pg_catalog.pg_class relation ON relation.oid=catalog_constraint.conrelid
                    JOIN pg_catalog.pg_namespace namespace ON namespace.oid=relation.relnamespace
                    WHERE namespace.nspname='public'
                      AND relation.relname IN ('webhook_inbox','webhook_outbox')
                      AND catalog_constraint.contype IN ('p','u','f')
                    ORDER BY relation.relname,catalog_constraint.contype,catalog_constraint.conname
                    """)).containsExactly(
                    "webhook_inbox:webhook_inbox_pkey:PRIMARY KEY (tenant_id, provider, immutable_repository_id, webhook_id)",
                    "webhook_outbox:webhook_outbox_inbox_fkey:FOREIGN KEY (tenant_id, provider, immutable_repository_id, webhook_id) REFERENCES webhook_inbox(tenant_id, provider, immutable_repository_id, webhook_id)",
                    "webhook_outbox:webhook_outbox_pkey:PRIMARY KEY (tenant_id, signal_id)",
                    "webhook_outbox:webhook_outbox_inbox_key:UNIQUE (tenant_id, provider, immutable_repository_id, webhook_id)");
        }
    }

    @Test
    void injectedFailureBetweenInsertsRollsBackTheInbox() throws Exception {
        PostgresWebhookInbox inbox = new PostgresWebhookInbox(
                database.runtimeDsl(),
                new ObjectMapper(),
                () -> {
                    throw new IllegalStateException("injected rollback sentinel");
                });

        assertThatThrownBy(() -> inbox.record(signal(TENANT_ID, DIGEST_A)))
                .isInstanceOf(WebhookInbox.UnavailableException.class)
                .hasMessage("webhook inbox unavailable")
                .hasMessageNotContaining("injected rollback sentinel");
        assertThat(database.adminCount("webhook_inbox")).isZero();
        assertThat(database.adminCount("webhook_outbox")).isZero();
    }

    @Test
    void thirtyTwoSimultaneousIdenticalDeliveriesConvergeOnOnePair() throws Exception {
        PostgresWebhookInbox inbox = inbox();
        int workers = 32;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<WebhookRecordOutcome>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return inbox.record(signal(TENANT_ID, DIGEST_A));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<WebhookRecordOutcome> outcomes = new ArrayList<>();
            for (Future<WebhookRecordOutcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsOnly(WebhookRecordOutcome.RECORDED, WebhookRecordOutcome.IDENTICAL_RETRY);
            assertThat(outcomes.stream().filter(WebhookRecordOutcome.RECORDED::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(WebhookRecordOutcome.IDENTICAL_RETRY::equals).count()).isEqualTo(31);
        } finally {
            executor.shutdownNow();
        }
        assertThat(database.adminCount("webhook_inbox")).isEqualTo(1);
        assertThat(database.adminCount("webhook_outbox")).isEqualTo(1);
    }

    @Test
    void digestConflictPreservesTheOriginalPair() throws Exception {
        PostgresWebhookInbox inbox = inbox();
        assertThat(inbox.record(signal(TENANT_ID, DIGEST_A))).isEqualTo(WebhookRecordOutcome.RECORDED);

        assertThat(inbox.record(signal(TENANT_ID, DIGEST_B))).isEqualTo(WebhookRecordOutcome.DIGEST_CONFLICT);

        assertThat(database.adminCount("webhook_inbox")).isEqualTo(1);
        assertThat(database.adminCount("webhook_outbox")).isEqualTo(1);
        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT body_digest, signal->>'body_digest' FROM public.webhook_inbox JOIN public.webhook_outbox USING (tenant_id,provider,immutable_repository_id,webhook_id)")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(DIGEST_A);
            assertThat(rows.getString(2)).isEqualTo(DIGEST_A);
        }
    }

    @Test
    void runtimeSessionsUseTheFixedPostgresqlTimeouts() throws Exception {
        try (Connection connection = database.runtimeConnectionWithoutTenant();
                Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                    SELECT name || '=' || setting || unit
                    FROM pg_catalog.pg_settings
                    WHERE name IN (
                      'statement_timeout','lock_timeout','idle_in_transaction_session_timeout')
                    ORDER BY name
                    """)).containsExactly(
                    "idle_in_transaction_session_timeout=10000ms",
                    "lock_timeout=2000ms",
                    "statement_timeout=5000ms");
        }
    }

    @Test
    @Timeout(10)
    void duplicateDeliveryFailsClosedWhenItsInboxRowRemainsLocked() throws Exception {
        PostgresWebhookInbox inbox = inbox();
        assertThat(inbox.record(signal(TENANT_ID, DIGEST_A)))
                .isEqualTo(WebhookRecordOutcome.RECORDED);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = database.adminConnection();
                PreparedStatement lock = blocker.prepareStatement("""
                        SELECT 1
                        FROM public.webhook_inbox
                        WHERE tenant_id=? AND provider='gitlab'
                          AND immutable_repository_id=? AND webhook_id=?
                        FOR UPDATE
                        """)) {
            blocker.setAutoCommit(false);
            lock.setObject(1, TENANT_ID);
            lock.setLong(2, REPOSITORY_ID);
            lock.setObject(3, DELIVERY_ID);
            try (ResultSet rows = lock.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }

            Future<WebhookRecordOutcome> blocked = executor.submit(
                    () -> inbox.record(signal(TENANT_ID, DIGEST_A)));
            long started = System.nanoTime();
            try {
                assertThatThrownBy(() -> blocked.get(4, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .cause()
                        .isInstanceOf(WebhookInbox.UnavailableException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .isBetween(Duration.ofSeconds(1), Duration.ofSeconds(4));
            } finally {
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void runtimeCannotInsertAnOutboxSignalThatDivergesFromItsInbox() throws Exception {
        inbox().record(signal(TENANT_ID, DIGEST_A));
        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("DELETE FROM public.webhook_outbox")).isEqualTo(1);
        }

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode baseline = mapper.valueToTree(signal(TENANT_ID, DIGEST_A));
        List<ObjectNode> divergent = new ArrayList<>();
        divergent.add(baseline.deepCopy().put("scope_id", OTHER_BINDING_ID.toString()));
        divergent.add(baseline.deepCopy().put("event_type", "Tag Push Hook"));
        divergent.add(baseline.deepCopy().put("body_digest", DIGEST_B));
        divergent.add(baseline.deepCopy().put("observed_at", "2026-07-26T01:02:04Z"));
        divergent.add(baseline.deepCopy().put("observed_at", "2026-02-30T01:02:03Z"));
        divergent.add(baseline.deepCopy().put("ref", "refs/heads/other"));
        divergent.add(baseline.deepCopy().put("before_sha", "3".repeat(40)));
        divergent.add(baseline.deepCopy().put("after_sha", "4".repeat(40)));

        for (ObjectNode candidate : divergent) {
            assertDivergentOutboxRejected(candidate.toString());
        }
    }

    @Test
    void naturalKeyCollisionsAreTenantIsolated() throws Exception {
        PostgresWebhookInbox inbox = inbox();
        assertThat(inbox.record(signal(TENANT_ID, DIGEST_A))).isEqualTo(WebhookRecordOutcome.RECORDED);
        assertThat(inbox.record(signal(OTHER_TENANT_ID, DIGEST_B))).isEqualTo(WebhookRecordOutcome.RECORDED);

        assertThat(database.adminCount("webhook_inbox")).isEqualTo(2);
        assertThat(database.adminCount("webhook_outbox")).isEqualTo(2);
        assertThat(runtimeCount(TENANT_ID, "webhook_inbox")).isEqualTo(1);
        assertThat(runtimeCount(OTHER_TENANT_ID, "webhook_inbox")).isEqualTo(1);
        assertThat(runtimeCount(TENANT_ID, "webhook_outbox")).isEqualTo(1);
        assertThat(runtimeCount(OTHER_TENANT_ID, "webhook_outbox")).isEqualTo(1);
    }

    @Test
    void naturalKeyUsesRepositoryAndWebhookIdentityRatherThanBindingScope() throws Exception {
        PostgresWebhookInbox inbox = inbox();

        assertThat(inbox.record(signal(TENANT_ID, BINDING_ID, REPOSITORY_ID, DIGEST_A)))
                .isEqualTo(WebhookRecordOutcome.RECORDED);
        assertThat(inbox.record(signal(TENANT_ID, OTHER_BINDING_ID, REPOSITORY_ID, DIGEST_A)))
                .isEqualTo(WebhookRecordOutcome.IDENTICAL_RETRY);
        assertThat(inbox.record(signal(TENANT_ID, OTHER_BINDING_ID, REPOSITORY_ID + 1, DIGEST_B)))
                .isEqualTo(WebhookRecordOutcome.RECORDED);

        assertThat(database.adminCount("webhook_inbox")).isEqualTo(2);
        assertThat(database.adminCount("webhook_outbox")).isEqualTo(2);
    }

    @Test
    void missingTransactionTenantReadsZeroAndCannotInsert() throws Exception {
        try (Connection connection = database.runtimeConnectionWithoutTenant();
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM public.webhook_inbox")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
            SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO public.webhook_inbox (
                      tenant_id,scope_id,provider,immutable_repository_id,webhook_id,
                      event_type,body_digest,observed_at,ref,before_sha,after_sha)
                    VALUES ('019adf8e-04e8-7000-8000-000000000022',
                      '019adf8e-04e8-7000-8000-000000000011','gitlab',
                      4294967296,'019adf8e-04e8-7000-8000-000000000033',
                      'Push Hook','sha256:' || repeat('a',64),clock_timestamp(),'refs/heads/main',
                      repeat('1',40),repeat('2',40))
                    """));
            assertThat(failure.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    void ownerMigratorRuntimeRlsAndAclAreExact() throws Exception {
        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                    SELECT rolname || ':' || rolcanlogin || ':' || rolsuper || ':'
                           || rolinherit || ':' || rolcreaterole || ':' || rolcreatedb
                           || ':' || rolreplication || ':' || rolbypassrls
                    FROM pg_catalog.pg_roles
                    WHERE rolname IN ('accord_webhook_owner','accord_webhook_migrator_login',
                      'accord_webhook_runtime','accord_webhook_runtime_login')
                    ORDER BY rolname
                    """)).containsExactly(
                    "accord_webhook_migrator_login:true:false:false:false:false:false:false",
                    "accord_webhook_owner:false:false:false:false:false:false:false",
                    "accord_webhook_runtime:false:false:false:false:false:false:false",
                    "accord_webhook_runtime_login:true:false:false:false:false:false:false");
            assertThat(strings(statement, """
                    SELECT granted.rolname || '->' || member.rolname || ':'
                           || membership.admin_option || ':' || membership.inherit_option
                           || ':' || membership.set_option
                    FROM pg_catalog.pg_auth_members membership
                    JOIN pg_catalog.pg_roles granted ON granted.oid=membership.roleid
                    JOIN pg_catalog.pg_roles member ON member.oid=membership.member
                    WHERE granted.rolname IN ('accord_webhook_owner','accord_webhook_migrator_login',
                           'accord_webhook_runtime','accord_webhook_runtime_login')
                       OR member.rolname IN ('accord_webhook_owner','accord_webhook_migrator_login',
                           'accord_webhook_runtime','accord_webhook_runtime_login')
                    ORDER BY 1
                    """)).containsExactly(
                    "accord_webhook_owner->accord_webhook_migrator_login:false:false:true",
                    "accord_webhook_runtime->accord_webhook_runtime_login:false:false:true");
            assertThat(strings(statement, """
                    SELECT database.datname || ':' || owner.rolname
                    FROM pg_catalog.pg_database database
                    JOIN pg_catalog.pg_roles owner ON owner.oid=database.datdba
                    WHERE database.datname=pg_catalog.current_database()
                    """)).allMatch(value -> value.endsWith(":" + WebhookDatabaseFixture.OWNER));
            assertThat(strings(statement, """
                    SELECT relation.relname || ':' || owner.rolname || ':'
                           || relation.relrowsecurity || ':' || relation.relforcerowsecurity
                           || ':' || policy.polname || ':' || policy.polcmd::text
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace ON namespace.oid=relation.relnamespace
                    JOIN pg_catalog.pg_roles owner ON owner.oid=relation.relowner
                    LEFT JOIN pg_catalog.pg_policy policy ON policy.polrelid=relation.oid
                    WHERE namespace.nspname='public'
                      AND relation.relname IN ('webhook_inbox','webhook_outbox')
                    ORDER BY relation.relname
                    """)).containsExactly(
                    "webhook_inbox:accord_webhook_owner:true:true:tenant_isolation:*",
                    "webhook_outbox:accord_webhook_owner:true:true:tenant_isolation:*");
            assertThat(strings(statement, """
                    SELECT table_name || ':' || privilege_type
                    FROM information_schema.role_table_grants
                    WHERE grantee='accord_webhook_runtime'
                      AND table_schema='public'
                    ORDER BY table_name,privilege_type
                    """)).containsExactly(
                    "webhook_inbox:INSERT",
                    "webhook_inbox:SELECT",
                    "webhook_outbox:INSERT",
                    "webhook_outbox:SELECT");
            assertThat(strings(statement, """
                    SELECT procedure.proname || ':' || owner.rolname || ':'
                           || procedure.prosecdef || ':' || procedure.proconfig[1]
                    FROM pg_catalog.pg_proc procedure
                    JOIN pg_catalog.pg_namespace namespace ON namespace.oid=procedure.pronamespace
                    JOIN pg_catalog.pg_roles owner ON owner.oid=procedure.proowner
                    WHERE namespace.nspname='webhook_edge_security'
                    ORDER BY procedure.proname
                    """)).containsExactly(
                    "lock_webhook_inbox:accord_webhook_owner:true:search_path=pg_catalog, pg_temp",
                    "validate_webhook_outbox:accord_webhook_owner:false:search_path=pg_catalog, pg_temp");
            assertThat(strings(statement, """
                    SELECT grantee || ':' || routine_name || ':' || privilege_type
                    FROM information_schema.role_routine_grants
                    WHERE specific_schema='webhook_edge_security'
                      AND grantee <> 'accord_webhook_owner'
                    ORDER BY grantee,routine_name
                    """)).containsExactly(
                    "accord_webhook_runtime:lock_webhook_inbox:EXECUTE");
            assertThat(strings(statement, """
                    SELECT parameter_name || ':' || data_type
                    FROM information_schema.parameters
                    WHERE specific_schema='webhook_edge_security'
                      AND specific_name LIKE 'lock_webhook_inbox_%'
                      AND parameter_mode='OUT'
                    ORDER BY ordinal_position
                    """)).containsExactly(
                    "stored_tenant_id:uuid",
                    "stored_provider:character varying",
                    "stored_immutable_repository_id:bigint",
                    "stored_webhook_id:uuid",
                    "stored_body_digest:character varying");
        }

        try (Connection runtime = database.runtimeConnection(TENANT_ID);
                Statement statement = runtime.createStatement()) {
            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> statement.execute("SET ROLE " + WebhookDatabaseFixture.OWNER));
            assertThat(failure.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    void bootstrapRemovesInboundAndOutboundMembershipPollution() throws Exception {
        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE accord_webhook_pollution_inbound NOLOGIN");
            statement.execute("CREATE ROLE accord_webhook_pollution_outbound NOLOGIN");
            statement.execute("GRANT accord_webhook_pollution_inbound TO accord_webhook_owner");
            statement.execute("GRANT accord_webhook_migrator_login TO accord_webhook_pollution_outbound");
        }

        database.rebootstrap();

        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                    SELECT granted.rolname || '->' || member.rolname
                    FROM pg_catalog.pg_auth_members membership
                    JOIN pg_catalog.pg_roles granted ON granted.oid=membership.roleid
                    JOIN pg_catalog.pg_roles member ON member.oid=membership.member
                    WHERE granted.rolname IN ('accord_webhook_owner','accord_webhook_migrator_login',
                           'accord_webhook_runtime','accord_webhook_runtime_login')
                       OR member.rolname IN ('accord_webhook_owner','accord_webhook_migrator_login',
                           'accord_webhook_runtime','accord_webhook_runtime_login')
                    ORDER BY 1
                    """)).containsExactly(
                    "accord_webhook_owner->accord_webhook_migrator_login",
                    "accord_webhook_runtime->accord_webhook_runtime_login");
            statement.execute("DROP ROLE accord_webhook_pollution_inbound");
            statement.execute("DROP ROLE accord_webhook_pollution_outbound");
        }
    }

    @Test
    void runtimeCannotUpdateDeleteTruncateOwnBypassOrCrossTenantLock() throws Exception {
        PostgresWebhookInbox inbox = inbox();
        inbox.record(signal(TENANT_ID, DIGEST_A));

        for (String sql : List.of(
                "UPDATE public.webhook_inbox SET event_type='Tag Push Hook'",
                "DELETE FROM public.webhook_inbox",
                "TRUNCATE TABLE public.webhook_inbox",
                "ALTER TABLE public.webhook_inbox DISABLE ROW LEVEL SECURITY")) {
            assertRuntimeSqlState(TENANT_ID, sql, "42501");
        }
        assertRuntimeSqlState(TENANT_ID, """
                SELECT * FROM webhook_edge_security.lock_webhook_inbox(
                  '019adf8e-04e8-7000-8000-000000000099'::uuid,
                  'gitlab',4294967296,
                  '019adf8e-04e8-7000-8000-000000000033'::uuid)
                """, "42501");
    }

    @Test
    void constraintsAndCatalogContainNoSensitivePayloadSurface() throws Exception {
        inbox().record(signal(TENANT_ID, DIGEST_A));
        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement()) {
            assertThat(strings(statement, """
                    SELECT attribute.attname
                    FROM pg_catalog.pg_attribute attribute
                    WHERE attribute.attrelid IN (
                      'public.webhook_inbox'::regclass,'public.webhook_outbox'::regclass)
                      AND attribute.attnum > 0 AND NOT attribute.attisdropped
                      AND attribute.attname ~* '(url|hostname|commits?|messages?|authors?|users?|tokens?|secrets?|signatures?|credentials?|headers?|raw_body|source|archive|diff)'
                    ORDER BY attribute.attname
                    """)).isEmpty();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT signal::text FROM public.webhook_outbox")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).doesNotContain(
                        FORBIDDEN_PAYLOAD_TERMS.toArray(String[]::new));
            }
            for (String rejectedField : List.of(
                    "url", "commits", "message", "token", "headers", "raw_body", "signature", "credential")) {
                assertThatThrownBy(() -> statement.executeUpdate(
                                "UPDATE public.webhook_outbox SET signal=signal || "
                                        + "pg_catalog.jsonb_build_object('"
                                        + rejectedField
                                        + "','sensitive')"))
                        .hasMessageContaining("webhook_outbox_signal_closed");
            }
        }
    }

    @Test
    void closedSignalConstraintsRejectNullWrongTypedMismatchedAndOversizedValues() throws Exception {
        inbox().record(signal(TENANT_ID, DIGEST_A));
        try (Connection connection = database.adminConnection();
                Statement statement = connection.createStatement()) {
            for (String mutation : List.of(
                    "signal || '{\"event_type\":null}'::jsonb",
                    "signal || '{\"immutable_repository_id\":\"4294967296\"}'::jsonb",
                    "signal || '{\"observed_at\":null}'::jsonb",
                    "signal || '{\"before_sha\":[]}'::jsonb",
                    "signal || '{\"tenant_id\":\"019adf8e-04e8-7000-8000-000000000099\"}'::jsonb",
                    "pg_catalog.jsonb_set(signal, '{ref}', pg_catalog.to_jsonb(repeat('x',4097)::text), true)")) {
                assertThatThrownBy(() -> statement.executeUpdate(
                                "UPDATE public.webhook_outbox SET signal=" + mutation))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private PostgresWebhookInbox inbox() {
        return new PostgresWebhookInbox(database.runtimeDsl(), new ObjectMapper());
    }

    private static ProviderWebhookSignal signal(UUID tenantId, String digest) {
        return signal(tenantId, BINDING_ID, REPOSITORY_ID, digest);
    }

    private static ProviderWebhookSignal signal(
            UUID tenantId,
            UUID bindingId,
            long repositoryId,
            String digest) {
        return signalAt(tenantId, bindingId, repositoryId, digest, "2026-07-26T01:02:03Z");
    }

    private static ProviderWebhookSignal signalAt(
            UUID tenantId,
            String digest,
            String observedAt) {
        return signalAt(tenantId, BINDING_ID, REPOSITORY_ID, digest, observedAt);
    }

    private static ProviderWebhookSignal signalAt(
            UUID tenantId,
            UUID bindingId,
            long repositoryId,
            String digest,
            String observedAt) {
        return new ProviderWebhookSignal(
                "1.0.0",
                tenantId,
                "repository",
                bindingId,
                "gitlab",
                repositoryId,
                DELIVERY_ID,
                "Push Hook",
                digest,
                observedAt,
                "refs/heads/main",
                "1".repeat(40),
                "2".repeat(40));
    }

    private long runtimeCount(UUID tenantId, String table) throws Exception {
        if (!table.equals("webhook_inbox") && !table.equals("webhook_outbox")) {
            throw new IllegalArgumentException("unsafe edge table");
        }
        try (Connection connection = database.runtimeConnection(tenantId);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM public." + table)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private void assertRuntimeSqlState(UUID tenantId, String sql, String expectedState)
            throws Exception {
        try (Connection connection = database.runtimeConnection(tenantId);
                Statement statement = connection.createStatement()) {
            SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertThat(failure.getSQLState()).isEqualTo(expectedState);
        }
    }

    private void assertDivergentOutboxRejected(String signalJson) throws Exception {
        ObjectNode signal = (ObjectNode) new ObjectMapper().readTree(signalJson);
        try (Connection connection = database.runtimeConnection(TENANT_ID);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO public.webhook_outbox (
                          tenant_id,signal_id,scope_id,provider,immutable_repository_id,
                          webhook_id,schema_version,signal)
                        VALUES (?, ?, ?, 'gitlab', ?, ?, '1.0.0', CAST(? AS jsonb))
                        """)) {
            statement.setObject(1, TENANT_ID);
            statement.setObject(2, UUID.fromString("019adf8e-04e8-7000-8000-000000000044"));
            statement.setObject(3, UUID.fromString(signal.path("scope_id").asText()));
            statement.setLong(4, REPOSITORY_ID);
            statement.setObject(5, DELIVERY_ID);
            statement.setString(6, signalJson);

            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private static List<String> strings(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return List.copyOf(values);
        }
    }

    private static byte[] standardSignature(byte[] body, String webhookId, String timestamp)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_SECRET, "HmacSHA256"));
        mac.update(webhookId.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) '.');
        mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) '.');
        return mac.doFinal(body);
    }
}
