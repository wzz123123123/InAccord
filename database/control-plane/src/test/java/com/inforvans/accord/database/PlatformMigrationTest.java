package com.inforvans.accord.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformMigrationTest {
    private static final String FINGERPRINT = "sha256:" + "0".repeat(64);
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302")
        .asCompatibleSubstituteFor("postgres");

    private PostgreSQLContainer<?> postgres;

    @BeforeAll
    void startPostgresAndMigrate() {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
        postgres.start();
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
    }

    @AfterAll
    void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void roleBootstrapRevokesPublicDefaultsAndCreatesExactMemberships() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertScalar(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_database d,
                     LATERAL pg_catalog.aclexplode(
                         COALESCE(d.datacl, pg_catalog.acldefault('d', d.datdba))) acl
                WHERE d.datname = current_database()
                  AND acl.grantee = 0
                  AND acl.privilege_type IN ('CONNECT', 'TEMPORARY')
                """, 0);
            assertScalar(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_namespace n,
                     LATERAL pg_catalog.aclexplode(
                         COALESCE(n.nspacl, pg_catalog.acldefault('n', n.nspowner))) acl
                WHERE n.nspname = 'public'
                  AND acl.grantee = 0
                """, 0);
            assertScalar(statement, """
                SELECT count(*) FROM pg_catalog.pg_roles
                WHERE rolname IN ('accord_migrator', 'accord_api', 'accord_worker')
                  AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb
                  AND NOT rolcreaterole AND NOT rolreplication
                  AND NOT rolinherit AND NOT rolbypassrls
                """, 3);
            assertScalar(statement, """
                SELECT count(*) FROM pg_catalog.pg_roles
                WHERE rolname IN (
                    'accord_migrator_login', 'accord_api_login', 'accord_worker_login')
                  AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb
                  AND NOT rolcreaterole AND NOT rolreplication
                  AND NOT rolinherit AND NOT rolbypassrls
                """, 3);
            assertScalar(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_auth_members membership
                JOIN pg_catalog.pg_roles granted ON granted.oid = membership.roleid
                JOIN pg_catalog.pg_roles member ON member.oid = membership.member
                WHERE (member.rolname, granted.rolname) IN (
                    ('accord_migrator_login', 'accord_migrator'),
                    ('accord_api_login', 'accord_api'),
                    ('accord_worker_login', 'accord_worker'))
                  AND NOT membership.admin_option
                  AND NOT membership.inherit_option
                  AND membership.set_option
                """, 3);
            assertScalar(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_auth_members membership
                JOIN pg_catalog.pg_roles member ON member.oid = membership.member
                WHERE member.rolname IN (
                    'accord_migrator_login', 'accord_api_login', 'accord_worker_login')
                """, 3);
            assertScalar(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_default_acl defaults
                CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) acl
                LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
                JOIN pg_catalog.pg_roles owner ON owner.oid = defaults.defaclrole
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = defaults.defaclnamespace
                WHERE owner.rolname = 'accord_migrator'
                  AND namespace.nspname = 'public'
                  AND defaults.defaclobjtype = 'r'
                  AND (acl.grantee = 0 OR grantee.rolname IN (
                    'accord_api', 'accord_worker',
                    'accord_migrator_login', 'accord_api_login',
                    'accord_worker_login', 'accord_audit_test'))
                """, 0);
            assertExactRoleTopology(statement);
            assertExactPersistenceOwners(statement);
            assertExactDatabaseAcl(statement);
            assertExactPublicSchemaAcl(statement);
            assertNoUnexpectedDefaultTablePrivileges(statement);
        }

        assertLoginCanSetOnlyItsPairedRole(
            ControlPlaneTestRoles.MIGRATOR_LOGIN,
            ControlPlaneTestRoles.MIGRATOR_PASSWORD,
            "accord_migrator",
            "accord_api");
        assertLoginCanSetOnlyItsPairedRole(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api",
            "accord_worker");
        assertLoginCanSetOnlyItsPairedRole(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker",
            "accord_api");
    }

    @Test
    void roleBootstrapReclaimsDatabaseAndPublicSchemaOwnership() throws Throwable {
        String database = '"' + postgres.getDatabaseName().replace("\"", "\"\"") + '"';
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER DATABASE " + database + " OWNER TO accord_api");
            executeWithSecondaryCleanup(
                () -> {
                    statement.execute("ALTER SCHEMA public OWNER TO accord_worker_login");
                    ControlPlaneTestRoles.bootstrap(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

                    assertExactPersistenceOwners(statement);
                    assertExactDatabaseAcl(statement);
                    assertExactPublicSchemaAcl(statement);
                },
                () -> statement.execute(
                    "ALTER DATABASE " + database + " OWNER TO accord_migrator"),
                () -> statement.execute("ALTER SCHEMA public OWNER TO accord_migrator"),
                () -> ControlPlaneTestRoles.bootstrap(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        }

        assertRoleCannotCreateDatabaseOrSchemaObjects(
            ControlPlaneTestRoles.API_LOGIN,
            ControlPlaneTestRoles.API_PASSWORD,
            "accord_api");
        assertRoleCannotCreateDatabaseOrSchemaObjects(
            ControlPlaneTestRoles.WORKER_LOGIN,
            ControlPlaneTestRoles.WORKER_PASSWORD,
            "accord_worker");
    }

    @Test
    void roleBootstrapRollsBackEveryChangeAfterALateFailure() throws Throwable {
        String database = '"' + postgres.getDatabaseName().replace("\"", "\"\"") + '"';
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE accord_atomic_hostile_test NOLOGIN");
            executeWithSecondaryCleanup(
                () -> {
                    statement.execute("ALTER ROLE accord_api BYPASSRLS");
                    statement.execute("GRANT accord_atomic_hostile_test TO accord_api");
                    statement.execute("ALTER DATABASE " + database + " OWNER TO accord_api");
                    statement.execute("ALTER SCHEMA public OWNER TO accord_worker_login");
                    statement.execute("GRANT TEMPORARY ON DATABASE " + database
                        + " TO accord_worker_login");
                    statement.execute("GRANT CREATE ON SCHEMA public TO accord_api");

                    String bootstrap = readPackagedBootstrap();
                    String lateFailure = """

                        SELECT * FROM pg_catalog.accord_deliberate_late_failure;
                        """;
                    int commit = bootstrap.lastIndexOf("COMMIT;");
                    String failingBootstrap = commit < 0
                        ? bootstrap + lateFailure
                        : bootstrap.substring(0, commit) + lateFailure
                            + bootstrap.substring(commit);
                    postgres.copyFileToContainer(
                        Transferable.of(failingBootstrap.getBytes(StandardCharsets.UTF_8), 0644),
                        "/tmp/accord-atomic-bootstrap.sql");

                    var result = postgres.execInContainer(
                        "psql",
                        "--username", postgres.getUsername(),
                        "--dbname", postgres.getDatabaseName(),
                        "--set", "ON_ERROR_STOP=1",
                        "--file", "/tmp/accord-atomic-bootstrap.sql");

                    assertNotEquals(0, result.getExitCode());
                    assertTrue((result.getStdout() + result.getStderr())
                        .contains("accord_deliberate_late_failure"));
                    assertScalar(statement, """
                        SELECT count(*)
                        FROM (
                          SELECT database.datdba AS owner_oid, 'accord_api' AS expected_owner
                          FROM pg_catalog.pg_database database
                          WHERE database.datname = pg_catalog.current_database()
                          UNION ALL
                          SELECT namespace.nspowner, 'accord_worker_login'
                          FROM pg_catalog.pg_namespace namespace
                          WHERE namespace.nspname = 'public'
                        ) owned
                        JOIN pg_catalog.pg_roles owner ON owner.oid = owned.owner_oid
                        WHERE owner.rolname = owned.expected_owner
                        """, 2);
                    assertScalar(statement, """
                        SELECT count(*) FROM pg_catalog.pg_roles
                        WHERE rolname = 'accord_api' AND rolbypassrls
                        """, 1);
                    assertScalar(statement, """
                        SELECT count(*)
                        FROM pg_catalog.pg_auth_members membership
                        JOIN pg_catalog.pg_roles granted ON granted.oid = membership.roleid
                        JOIN pg_catalog.pg_roles member ON member.oid = membership.member
                        WHERE granted.rolname = 'accord_atomic_hostile_test'
                          AND member.rolname = 'accord_api'
                        """, 1);
                    assertScalar(statement, """
                        SELECT count(*)
                        WHERE pg_catalog.has_database_privilege(
                          'accord_worker_login', pg_catalog.current_database(), 'TEMPORARY')
                          AND pg_catalog.has_schema_privilege(
                            'accord_api', 'public', 'CREATE')
                        """, 1);
                },
                () -> ControlPlaneTestRoles.bootstrap(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()),
                () -> statement.execute("DROP ROLE accord_atomic_hostile_test"));
        }
    }

    @Test
    void cleanupFailuresRemainSecondaryToThePrimaryFailure() {
        IllegalStateException primary = new IllegalStateException("primary");
        SQLException firstCleanup = new SQLException("first cleanup");
        IOException secondCleanup = new IOException("second cleanup");
        List<String> attemptedCleanups = new ArrayList<>();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> executeWithSecondaryCleanup(
                () -> { throw primary; },
                () -> {
                    attemptedCleanups.add("first");
                    throw firstCleanup;
                },
                () -> {
                    attemptedCleanups.add("second");
                    throw secondCleanup;
                },
                () -> attemptedCleanups.add("third")));

        assertSame(primary, thrown);
        assertEquals(List.of("first", "second", "third"), attemptedCleanups);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(firstCleanup, thrown.getSuppressed()[0]);
        assertSame(secondCleanup, thrown.getSuppressed()[1]);
    }

    @Test
    void roleBootstrapReconcilesHostileMembershipsDatabaseAclAndDefaultAcl()
            throws Throwable {
        String database = '"' + postgres.getDatabaseName().replace("\"", "\"\"") + '"';
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE accord_hostile_direct_test NOLOGIN BYPASSRLS");
            executeWithSecondaryCleanup(
                () -> {
                    statement.execute(
                        "CREATE ROLE accord_hostile_transitive_test NOLOGIN BYPASSRLS");
                    statement.execute(
                        "CREATE ROLE accord_hostile_reverse_test NOLOGIN BYPASSRLS");
                    statement.execute("CREATE ROLE accord_hostile_grantor_test NOLOGIN");
                    statement.execute("""
                        GRANT accord_hostile_direct_test TO accord_hostile_grantor_test
                        WITH ADMIN TRUE, INHERIT FALSE, SET TRUE
                        """);
                    statement.execute("SET ROLE accord_hostile_grantor_test");
                    executeWithSecondaryCleanup(
                        () -> statement.execute("""
                            GRANT accord_hostile_direct_test TO accord_api_login
                            WITH ADMIN TRUE, INHERIT TRUE, SET TRUE
                            """),
                        () -> statement.execute("RESET ROLE"));
                    assertScalar(statement, """
                        SELECT count(*)
                        FROM pg_catalog.pg_auth_members membership
                        JOIN pg_catalog.pg_roles granted ON granted.oid = membership.roleid
                        JOIN pg_catalog.pg_roles member ON member.oid = membership.member
                        JOIN pg_catalog.pg_roles grantor ON grantor.oid = membership.grantor
                        WHERE granted.rolname = 'accord_hostile_direct_test'
                          AND member.rolname = 'accord_api_login'
                          AND grantor.rolname = 'accord_hostile_grantor_test'
                          AND membership.admin_option
                          AND membership.inherit_option
                          AND membership.set_option
                        """, 1);
                    statement.execute("""
                        GRANT accord_hostile_transitive_test TO accord_api
                        WITH ADMIN TRUE, INHERIT TRUE, SET TRUE
                        """);
                    statement.execute("""
                        GRANT accord_worker TO accord_hostile_direct_test
                        WITH ADMIN TRUE, INHERIT TRUE, SET TRUE
                        """);
                    statement.execute("""
                        GRANT accord_api_login TO accord_hostile_reverse_test
                        WITH ADMIN TRUE, INHERIT TRUE, SET TRUE
                        """);
                    try (Connection apiGrant = DriverManager.getConnection(
                            postgres.getJdbcUrl(),
                            ControlPlaneTestRoles.API_LOGIN,
                            ControlPlaneTestRoles.API_PASSWORD);
                         Statement apiGrantStatement = apiGrant.createStatement()) {
                        apiGrantStatement.execute(
                            "GRANT accord_hostile_direct_test TO accord_hostile_reverse_test");
                    }
                    statement.execute("GRANT CREATE, TEMPORARY ON DATABASE " + database
                        + " TO accord_api_login, accord_api WITH GRANT OPTION");
                    statement.execute("GRANT CONNECT, TEMPORARY ON DATABASE " + database
                        + " TO accord_hostile_direct_test WITH GRANT OPTION");
                    statement.execute("GRANT USAGE, CREATE ON SCHEMA public "
                        + "TO accord_hostile_direct_test WITH GRANT OPTION");
                    statement.execute("""
                        ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator IN SCHEMA public
                        GRANT SELECT ON TABLES TO accord_hostile_direct_test WITH GRANT OPTION
                        """);

                    ControlPlaneTestRoles.bootstrap(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

                    assertExactRoleTopology(statement);
                    assertExactPersistenceOwners(statement);
                    assertExactDatabaseAcl(statement);
                    assertExactPublicSchemaAcl(statement);
                    assertNoUnexpectedDefaultTablePrivileges(statement);
                    try (Connection api = DriverManager.getConnection(
                            postgres.getJdbcUrl(),
                            ControlPlaneTestRoles.API_LOGIN,
                            ControlPlaneTestRoles.API_PASSWORD);
                         Statement apiStatement = api.createStatement()) {
                        assertSqlState("42501", () -> apiStatement.execute(
                            "SET ROLE accord_hostile_direct_test"));
                        apiStatement.execute("SET ROLE accord_api");
                        assertSqlState("42501", () -> apiStatement.execute(
                            "SET ROLE accord_hostile_transitive_test"));
                    }
                },
                () -> statement.execute("""
                    ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator IN SCHEMA public
                    REVOKE ALL ON TABLES FROM accord_hostile_direct_test
                    """),
                () -> statement.execute(
                    "REVOKE accord_hostile_direct_test FROM accord_api_login CASCADE"),
                () -> statement.execute(
                    "REVOKE accord_hostile_transitive_test FROM accord_api"),
                () -> statement.execute(
                    "REVOKE accord_worker FROM accord_hostile_direct_test"),
                () -> statement.execute(
                    "REVOKE accord_api_login FROM accord_hostile_reverse_test"),
                () -> statement.execute(
                    "REVOKE accord_hostile_direct_test FROM accord_hostile_reverse_test"),
                () -> statement.execute(
                    "REVOKE accord_hostile_direct_test FROM accord_hostile_grantor_test CASCADE"),
                () -> statement.execute("REVOKE ALL ON DATABASE " + database
                    + " FROM accord_api_login, accord_api, accord_hostile_direct_test"),
                () -> statement.execute(
                    "REVOKE ALL ON SCHEMA public FROM accord_hostile_direct_test"),
                () -> statement.execute("DROP ROLE accord_hostile_direct_test"),
                () -> statement.execute("DROP ROLE accord_hostile_transitive_test"),
                () -> statement.execute("DROP ROLE accord_hostile_reverse_test"),
                () -> statement.execute("DROP ROLE accord_hostile_grantor_test"),
                () -> ControlPlaneTestRoles.bootstrap(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        }
    }

    @Test
    void roleBootstrapReconcilesGlobalDefaultTableAcl() throws Throwable {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE accord_hostile_global_default_test NOLOGIN");
            executeWithSecondaryCleanup(
                () -> {
                    statement.execute("""
                        ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator
                        GRANT INSERT ON TABLES TO accord_hostile_global_default_test
                        WITH GRANT OPTION
                        """);
                    ControlPlaneTestRoles.bootstrap(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    assertNoUnexpectedDefaultTablePrivileges(statement);
                },
                () -> statement.execute("""
                    ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator
                    REVOKE ALL PRIVILEGES ON TABLES
                    FROM accord_hostile_global_default_test CASCADE
                    """),
                () -> statement.execute("DROP ROLE accord_hostile_global_default_test"),
                () -> ControlPlaneTestRoles.bootstrap(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        }
    }

    @Test
    void migrationCreatesOwnedCommandTablesAndRejectsInvalidStoredVersions() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertScalar(statement, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (table_name, column_name) IN (
                    ('aggregate_head', 'tenant_id'),
                    ('aggregate_head', 'aggregate_type'),
                    ('aggregate_head', 'aggregate_id'),
                    ('aggregate_head', 'version'),
                    ('aggregate_head', 'updated_at'),
                    ('idempotency_result', 'tenant_id'),
                    ('idempotency_result', 'actor_id'),
                    ('idempotency_result', 'route_key'),
                    ('idempotency_result', 'idempotency_key'),
                    ('idempotency_result', 'request_fingerprint'),
                    ('idempotency_result', 'state'),
                    ('idempotency_result', 'claim_owner'),
                    ('idempotency_result', 'claim_generation'),
                    ('idempotency_result', 'claim_token'),
                    ('idempotency_result', 'lease_until'),
                    ('idempotency_result', 'response_status'),
                    ('idempotency_result', 'response_headers'),
                    ('idempotency_result', 'response_body'),
                    ('idempotency_result', 'aggregate_type'),
                    ('idempotency_result', 'aggregate_id'),
                    ('idempotency_result', 'aggregate_version'),
                    ('idempotency_result', 'started_at'),
                    ('idempotency_result', 'completed_at'),
                    ('idempotency_result', 'expires_at'))
                """, 24);
            assertScalar(statement, """
                WITH expected(
                  table_name, ordinal_position, column_name, udt_name, is_nullable,
                  character_maximum_length, column_default
                ) AS (VALUES
                  ('aggregate_head', 1, 'tenant_id', 'uuid', 'NO', NULL::bigint, NULL::text),
                  ('aggregate_head', 2, 'aggregate_type', 'varchar', 'NO', 64::bigint, NULL::text),
                  ('aggregate_head', 3, 'aggregate_id', 'uuid', 'NO', NULL::bigint, NULL::text),
                  ('aggregate_head', 4, 'version', 'int8', 'NO', NULL::bigint, NULL::text),
                  ('aggregate_head', 5, 'updated_at', 'timestamptz', 'NO', NULL::bigint,
                    'transaction_timestamp()'),
                  ('idempotency_result', 1, 'tenant_id', 'uuid', 'NO', NULL::bigint, NULL::text),
                  ('idempotency_result', 2, 'actor_id', 'varchar', 'NO', 255::bigint, NULL::text),
                  ('idempotency_result', 3, 'route_key', 'varchar', 'NO', 128::bigint, NULL::text),
                  ('idempotency_result', 4, 'idempotency_key', 'varchar', 'NO', 128::bigint, NULL::text),
                  ('idempotency_result', 5, 'request_fingerprint', 'bpchar', 'NO', 71::bigint, NULL::text),
                  ('idempotency_result', 6, 'state', 'varchar', 'NO', 16::bigint, NULL::text),
                  ('idempotency_result', 7, 'claim_owner', 'varchar', 'YES', 255::bigint, NULL::text),
                  ('idempotency_result', 8, 'claim_generation', 'int8', 'NO', NULL::bigint, NULL::text),
                  ('idempotency_result', 9, 'claim_token', 'uuid', 'YES', NULL::bigint, NULL::text),
                  ('idempotency_result', 10, 'lease_until', 'timestamptz', 'YES', NULL::bigint, NULL::text),
                  ('idempotency_result', 11, 'response_status', 'int4', 'YES', NULL::bigint, NULL::text),
                  ('idempotency_result', 12, 'response_headers', 'jsonb', 'YES', NULL::bigint, NULL::text),
                  ('idempotency_result', 13, 'response_body', 'text', 'YES', NULL::bigint, NULL::text),
                  ('idempotency_result', 14, 'aggregate_type', 'varchar', 'YES', 64::bigint, NULL::text),
                  ('idempotency_result', 15, 'aggregate_id', 'uuid', 'YES', NULL::bigint, NULL::text),
                  ('idempotency_result', 16, 'aggregate_version', 'int8', 'YES', NULL::bigint, NULL::text),
                  ('idempotency_result', 17, 'started_at', 'timestamptz', 'NO', NULL::bigint,
                    'transaction_timestamp()'),
                  ('idempotency_result', 18, 'completed_at', 'timestamptz', 'YES', NULL::bigint, NULL::text),
                  ('idempotency_result', 19, 'expires_at', 'timestamptz', 'NO', NULL::bigint, NULL::text)
                ), actual AS (
                  SELECT table_name::text, ordinal_position, column_name::text,
                         udt_name::text, is_nullable::text, character_maximum_length,
                         column_default::text
                  FROM information_schema.columns
                  WHERE table_schema = 'public'
                    AND table_name IN ('aggregate_head', 'idempotency_result')
                ), differences AS (
                  (SELECT * FROM expected EXCEPT SELECT * FROM actual)
                  UNION ALL
                  (SELECT * FROM actual EXCEPT SELECT * FROM expected)
                )
                SELECT count(*) FROM differences
                """, 0);
            assertScalar(statement, """
                WITH expected(
                  table_name, constraint_name, constraint_type, definition
                ) AS (VALUES
                  ('aggregate_head', 'aggregate_head_pkey', 'p',
                    'PRIMARY KEY (tenant_id, aggregate_type, aggregate_id)'),
                  ('aggregate_head', 'aggregate_head_version_positive', 'c',
                    'CHECK (version >= 1)'),
                  ('idempotency_result', 'idempotency_result_pkey', 'p',
                    'PRIMARY KEY (tenant_id, actor_id, route_key, idempotency_key)'),
                  ('idempotency_result', 'idempotency_result_fingerprint_format', 'c',
                    'CHECK (request_fingerprint ~ ''^sha256:[0-9a-f]{64}$''::text)'),
                  ('idempotency_result', 'idempotency_result_state_known', 'c',
                    'CHECK (state::text = ANY (ARRAY[''STARTED''::character varying, ''COMPLETED''::character varying]::text[]))'),
                  ('idempotency_result', 'idempotency_result_claim_generation_positive', 'c',
                    'CHECK (claim_generation >= 1)'),
                  ('idempotency_result', 'idempotency_result_response_status_valid', 'c',
                    'CHECK (response_status >= 100 AND response_status <= 599)'),
                  ('idempotency_result', 'idempotency_result_aggregate_version_positive', 'c',
                    'CHECK (aggregate_version >= 1)'),
                  ('idempotency_result', 'idempotency_result_response_headers_bounded', 'c',
                    'CHECK (response_headers IS NULL OR pg_column_size(response_headers) <= 65536)'),
                  ('idempotency_result', 'idempotency_result_response_body_bounded', 'c',
                    'CHECK (response_body IS NULL OR octet_length(response_body) <= 1048576)'),
                  ('idempotency_result', 'idempotency_result_lifecycle_consistent', 'c',
                    'CHECK (state::text = ''STARTED''::text AND claim_owner IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL AND response_status IS NULL AND completed_at IS NULL OR state::text = ''COMPLETED''::text AND claim_owner IS NULL AND claim_token IS NULL AND lease_until IS NULL AND response_status IS NOT NULL AND response_headers IS NOT NULL AND response_body IS NOT NULL AND completed_at IS NOT NULL)'),
                  ('idempotency_result', 'idempotency_result_aggregate_binding_consistent', 'c',
                    'CHECK (aggregate_type IS NULL AND aggregate_id IS NULL AND aggregate_version IS NULL OR aggregate_type IS NOT NULL AND aggregate_id IS NOT NULL AND aggregate_version IS NOT NULL)'),
                  ('idempotency_result', 'idempotency_result_detached_status_known', 'c',
                    'CHECK (state::text <> ''COMPLETED''::text OR aggregate_type IS NOT NULL OR (response_status = ANY (ARRAY[404, 412, 422])))')
                ), actual AS (
                  SELECT relation.relname::text, catalog_constraint.conname::text,
                         catalog_constraint.contype::text,
                         pg_catalog.pg_get_constraintdef(
                           catalog_constraint.oid, true)::text
                  FROM pg_catalog.pg_constraint catalog_constraint
                  JOIN pg_catalog.pg_class relation
                    ON relation.oid = catalog_constraint.conrelid
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  WHERE namespace.nspname = 'public'
                    AND relation.relname IN ('aggregate_head', 'idempotency_result')
                ), differences AS (
                  (SELECT * FROM expected EXCEPT SELECT * FROM actual)
                  UNION ALL
                  (SELECT * FROM actual EXCEPT SELECT * FROM expected)
                )
                SELECT count(*) FROM differences
                """, 0);
            assertScalar(statement, """
                WITH expected(
                  table_name, index_name, is_unique, is_valid, is_ready, definition
                ) AS (VALUES
                  ('aggregate_head', 'aggregate_head_pkey', true, true, true,
                    'CREATE UNIQUE INDEX aggregate_head_pkey ON public.aggregate_head USING btree (tenant_id, aggregate_type, aggregate_id)'),
                  ('idempotency_result', 'idempotency_result_pkey', true, true, true,
                    'CREATE UNIQUE INDEX idempotency_result_pkey ON public.idempotency_result USING btree (tenant_id, actor_id, route_key, idempotency_key)'),
                  ('idempotency_result', 'idempotency_result_expiry_idx', false, true, true,
                    'CREATE INDEX idempotency_result_expiry_idx ON public.idempotency_result USING btree (expires_at)')
                ), actual AS (
                  SELECT relation.relname::text, index.relname::text,
                         catalog_index.indisunique, catalog_index.indisvalid,
                         catalog_index.indisready,
                         pg_catalog.pg_get_indexdef(index.oid)
                  FROM pg_catalog.pg_index catalog_index
                  JOIN pg_catalog.pg_class index ON index.oid = catalog_index.indexrelid
                  JOIN pg_catalog.pg_class relation ON relation.oid = catalog_index.indrelid
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  WHERE namespace.nspname = 'public'
                    AND relation.relname IN ('aggregate_head', 'idempotency_result')
                ), differences AS (
                  (SELECT * FROM expected EXCEPT SELECT * FROM actual)
                  UNION ALL
                  (SELECT * FROM actual EXCEPT SELECT * FROM expected)
                )
                SELECT count(*) FROM differences
                """, 0);
            assertScalar(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = 'public'
                  AND relation.relname IN ('aggregate_head', 'idempotency_result')
                  AND relation.relowner = (
                    SELECT oid FROM pg_catalog.pg_roles
                    WHERE rolname = 'accord_migrator')
                """, 2);
        }

        UUID tenantId = UUID.randomUUID();
        try (Connection connection = roleConnection(
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD,
                "accord_migrator")) {
            beginTenant(connection, tenantId);
            SQLException versionZero = assertSqlState("23514", () -> insertAggregate(
                connection, tenantId, UUID.randomUUID(), 0));
            assertTrue(versionZero.getMessage().contains("aggregate_head_version_positive"));
            connection.rollback();

            beginTenant(connection, tenantId);
            insertAggregate(connection, tenantId, UUID.randomUUID(), 1);
            connection.commit();
        }
    }

    @Test
    void securityFunctionsHaveExactOwnerAclAndHardenedSearchPath() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertScalar(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_proc function
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = function.pronamespace
                JOIN pg_catalog.pg_roles owner ON owner.oid = function.proowner
                WHERE namespace.nspname = 'accord_security'
                  AND function.proname IN ('current_tenant_id', 'enforce_tenant_table')
                  AND NOT function.prosecdef
                  AND owner.rolname = 'accord_migrator'
                  AND function.proconfig = ARRAY['search_path=pg_catalog, pg_temp']
                """, 2);
            assertScalar(statement, """
                WITH function_acl AS (
                  SELECT function.proname,
                         COALESCE(grantee.rolname, 'PUBLIC') AS grantee,
                         acl.privilege_type
                  FROM pg_catalog.pg_proc function
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = function.pronamespace
                  CROSS JOIN LATERAL pg_catalog.aclexplode(
                    COALESCE(function.proacl,
                      pg_catalog.acldefault('f', function.proowner))) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
                  WHERE namespace.nspname = 'accord_security'
                    AND function.proname IN (
                      'current_tenant_id', 'enforce_tenant_table')
                    AND acl.grantee <> function.proowner
                ), expected(proname, grantee, privilege_type) AS (VALUES
                  ('current_tenant_id', 'accord_api', 'EXECUTE'),
                  ('current_tenant_id', 'accord_worker', 'EXECUTE')
                ), differences AS (
                  (SELECT * FROM function_acl EXCEPT SELECT * FROM expected)
                  UNION ALL
                  (SELECT * FROM expected EXCEPT SELECT * FROM function_acl)
                )
                SELECT count(*) FROM differences
                """, 0);
        }

        try (Connection api = roleConnection(
                ControlPlaneTestRoles.API_LOGIN,
                ControlPlaneTestRoles.API_PASSWORD,
                "accord_api");
             Statement statement = api.createStatement()) {
            assertScalar(statement, """
                SELECT count(*) FROM accord_security.current_tenant_id()
                """, 1);
            assertSqlState("42501", () -> statement.execute(
                "SELECT accord_security.enforce_tenant_table('public.aggregate_head')"));
        }
        try (Connection audit = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                ControlPlaneTestRoles.AUDIT_LOGIN,
                ControlPlaneTestRoles.AUDIT_PASSWORD);
             Statement statement = audit.createStatement()) {
            assertSqlState("42501", () -> statement.execute(
                "SELECT accord_security.current_tenant_id()"));
        }
    }

    @Test
    void rlsAndRuntimeTableGrantsMatchTheExactContract() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            assertScalar(statement, """
                SELECT count(*)
                FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = 'public'
                  AND relation.relname IN ('aggregate_head', 'idempotency_result')
                  AND relation.relrowsecurity
                  AND relation.relforcerowsecurity
                """, 2);
            assertScalar(statement, """
                WITH expected(
                  table_name, policy_name, permissive, command, roles,
                  using_expression, check_expression
                ) AS (VALUES
                  ('aggregate_head', 'tenant_isolation', true, '*', ARRAY[0::oid],
                    '(tenant_id = accord_security.current_tenant_id())',
                    '(tenant_id = accord_security.current_tenant_id())'),
                  ('idempotency_result', 'tenant_isolation', true, '*', ARRAY[0::oid],
                    '(tenant_id = accord_security.current_tenant_id())',
                    '(tenant_id = accord_security.current_tenant_id())')
                ), actual AS (
                  SELECT relation.relname::text, policy.polname::text,
                         policy.polpermissive, policy.polcmd::text, policy.polroles,
                         pg_catalog.pg_get_expr(policy.polqual, policy.polrelid),
                         pg_catalog.pg_get_expr(policy.polwithcheck, policy.polrelid)
                  FROM pg_catalog.pg_policy policy
                  JOIN pg_catalog.pg_class relation ON relation.oid = policy.polrelid
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  WHERE namespace.nspname = 'public'
                    AND relation.relname IN ('aggregate_head', 'idempotency_result')
                ), differences AS (
                  (SELECT * FROM expected EXCEPT SELECT * FROM actual)
                  UNION ALL
                  (SELECT * FROM actual EXCEPT SELECT * FROM expected)
                )
                SELECT count(*) FROM differences
                """, 0);
            assertScalar(statement, """
                WITH expected(grantee, table_name, privilege_type, is_grantable) AS (VALUES
                  ('accord_api', 'aggregate_head', 'SELECT', false),
                  ('accord_api', 'aggregate_head', 'INSERT', false),
                  ('accord_api', 'aggregate_head', 'UPDATE', false),
                  ('accord_api', 'idempotency_result', 'SELECT', false),
                  ('accord_api', 'idempotency_result', 'INSERT', false),
                  ('accord_api', 'idempotency_result', 'UPDATE', false),
                  ('accord_worker', 'aggregate_head', 'SELECT', false),
                  ('accord_worker', 'aggregate_head', 'INSERT', false),
                  ('accord_worker', 'aggregate_head', 'UPDATE', false),
                  ('accord_worker', 'idempotency_result', 'SELECT', false),
                  ('accord_worker', 'idempotency_result', 'INSERT', false),
                  ('accord_worker', 'idempotency_result', 'UPDATE', false),
                  ('accord_worker', 'idempotency_result', 'DELETE', false)
                ), actual AS (
                  SELECT COALESCE(grantee.rolname, 'PUBLIC'), relation.relname::text,
                         acl.privilege_type, acl.is_grantable
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  CROSS JOIN LATERAL pg_catalog.aclexplode(
                    COALESCE(relation.relacl,
                      pg_catalog.acldefault('r', relation.relowner))) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
                  WHERE namespace.nspname = 'public'
                    AND relation.relname IN ('aggregate_head', 'idempotency_result')
                    AND acl.grantee <> relation.relowner
                ), differences AS (
                  (SELECT * FROM expected EXCEPT SELECT * FROM actual)
                  UNION ALL
                  (SELECT * FROM actual EXCEPT SELECT * FROM expected)
                )
                SELECT count(*) FROM differences
                """, 0);
        }
    }

    @Test
    void enforceTenantTableRejectsAnAdditionalPermissivePolicy() throws Throwable {
        try (Connection owner = roleConnection(
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD,
                "accord_migrator");
             Statement statement = owner.createStatement()) {
            statement.execute("""
                CREATE POLICY tenant_isolation_extra
                ON public.aggregate_head AS PERMISSIVE FOR SELECT TO PUBLIC
                USING (true)
                """);
            executeWithSecondaryCleanup(
                () -> assertSqlState("P0001", () -> statement.execute("""
                    SELECT accord_security.enforce_tenant_table(
                      'public.aggregate_head'::pg_catalog.regclass)
                    """)),
                () -> statement.execute(
                    "DROP POLICY tenant_isolation_extra ON public.aggregate_head"));
        }
    }

    @Test
    void idempotencyInsertRejectsMalformedFingerprint() throws Exception {
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validStarted().withFingerprint("sha256:" + "g".repeat(64)),
            "idempotency_result_fingerprint_format");
    }

    @Test
    void idempotencyInsertRejectsNonpositiveClaimGeneration() throws Exception {
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validStarted().withClaimGeneration(0),
            "idempotency_result_claim_generation_positive");
    }

    @Test
    void idempotencyInsertRejectsUnknownState() throws Exception {
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validStarted().withState("UNKNOWN"),
            "idempotency_result_state_known",
            "idempotency_result_lifecycle_consistent");
    }

    @Test
    void idempotencyInsertRejectsInconsistentLifecycle() throws Exception {
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validStarted().withClaimOwner(null),
            "idempotency_result_lifecycle_consistent");
    }

    @Test
    void idempotencyInsertRejectsInvalidResponseStatus() throws Exception {
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validCompleted()
                .withAggregateVersion(1L)
                .withResponseStatus(99),
            "idempotency_result_response_status_valid");
    }

    @Test
    void idempotencyInsertRejectsNonpositiveAggregateVersion() throws Exception {
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validCompleted().withAggregateVersion(0L),
            "idempotency_result_aggregate_version_positive");
    }

    @Test
    void idempotencyInsertRejectsPartialAggregateBinding() throws Exception {
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validCompleted().withPartialAggregateVersion(1L),
            "idempotency_result_aggregate_binding_consistent");
    }

    @Test
    void idempotencyInsertRejectsDetachedCompletionOutsideTheAllowlist() throws Exception {
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validCompleted().withResponseStatus(201),
            "idempotency_result_detached_status_known");
    }

    @Test
    void idempotencyInsertRejectsOversizedResponseHeaders() throws Exception {
        String headers = "{\"header\":\"" + "x".repeat(65_536) + "\"}";
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validCompleted().withResponseHeaders(headers),
            "idempotency_result_response_headers_bounded");
    }

    @Test
    void idempotencyInsertRejectsOversizedResponseBody() throws Exception {
        assertInvalidIdempotencyInsert(
            UUID.randomUUID(),
            IdempotencyInsert.validCompleted().withResponseBody("x".repeat(1_048_577)),
            "idempotency_result_response_body_bounded");
    }

    @Test
    void tenantContextFailsClosedAndIsTransactionLocalForRuntimeAndOwner() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID aggregateA = UUID.randomUUID();
        UUID aggregateB = UUID.randomUUID();
        seedTenant(tenantA, aggregateA);
        seedTenant(tenantB, aggregateB);

        try (Connection api = roleConnection(
                ControlPlaneTestRoles.API_LOGIN,
                ControlPlaneTestRoles.API_PASSWORD,
                "accord_api");
             Statement statement = api.createStatement()) {
            assertScalar(statement, "SELECT count(*) FROM aggregate_head", 0);
            assertSqlState("42501", () -> insertAggregate(
                api, tenantA, UUID.randomUUID(), 1));

            beginTenant(api, tenantA);
            assertScalar(statement, "SELECT count(*) FROM aggregate_head", 1);
            assertScalar(statement, "SELECT count(*) FROM aggregate_head WHERE tenant_id = '"
                + tenantB + "'", 0);
            assertEquals(0, statement.executeUpdate("UPDATE aggregate_head SET version = 2 "
                + "WHERE tenant_id = '" + tenantB + "'"));
            api.commit();

            beginTenant(api, tenantA);
            assertSqlState("42501", () -> insertAggregate(
                api, tenantB, UUID.randomUUID(), 1));
            api.rollback();

            beginTenant(api, tenantA);
            assertSqlState("42501", () -> statement.executeUpdate(
                "UPDATE aggregate_head SET tenant_id = '" + UUID.randomUUID()
                    + "' WHERE aggregate_id = '" + aggregateA + "'"));
            api.rollback();

            beginTenant(api, tenantA);
            assertEquals(tenantA.toString(), queryString(
                statement, "SELECT current_setting('app.tenant_id', true)"));
            api.commit();
            assertTenantContextCleared(statement);

            beginTenant(api, tenantA);
            api.rollback();
            assertTenantContextCleared(statement);

            beginTenant(api, "not-a-uuid");
            assertSqlState("22P02", () -> statement.executeQuery(
                "SELECT count(*) FROM aggregate_head"));
            api.rollback();

            beginTenant(api, tenantA + "' OR true --");
            assertSqlState("22P02", () -> statement.executeQuery(
                "SELECT count(*) FROM aggregate_head"));
            api.rollback();
        }

        try (Connection worker = roleConnection(
                ControlPlaneTestRoles.WORKER_LOGIN,
                ControlPlaneTestRoles.WORKER_PASSWORD,
                "accord_worker");
             Statement statement = worker.createStatement()) {
            beginTenant(worker, tenantA);
            assertScalar(statement, "SELECT count(*) FROM idempotency_result", 1);
            assertEquals(0, statement.executeUpdate("UPDATE idempotency_result "
                + "SET lease_until = lease_until + interval '1 second' "
                + "WHERE tenant_id = '" + tenantB + "'"));
            assertEquals(0, statement.executeUpdate("DELETE FROM idempotency_result "
                + "WHERE tenant_id = '" + tenantB + "'"));
            worker.commit();
        }

        try (Connection owner = roleConnection(
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD,
                "accord_migrator");
             Statement statement = owner.createStatement()) {
            assertScalar(statement, "SELECT count(*) FROM aggregate_head", 0);
            beginTenant(owner, tenantA);
            assertScalar(statement, "SELECT count(*) FROM aggregate_head", 1);
            assertScalar(statement, "SELECT count(*) FROM aggregate_head WHERE tenant_id = '"
                + tenantB + "'", 0);
            owner.rollback();
        }
    }

    private static void assertExactRoleTopology(Statement statement) throws SQLException {
        assertScalar(statement, """
            WITH expected(
              member_name, granted_name, admin_option, inherit_option, set_option
            ) AS (VALUES
              ('accord_migrator_login', 'accord_migrator', false, false, true),
              ('accord_api_login', 'accord_api', false, false, true),
              ('accord_worker_login', 'accord_worker', false, false, true)
            ), actual AS (
              SELECT member.rolname::text, granted.rolname::text,
                     membership.admin_option, membership.inherit_option,
                     membership.set_option
              FROM pg_catalog.pg_auth_members membership
              JOIN pg_catalog.pg_roles granted ON granted.oid = membership.roleid
              JOIN pg_catalog.pg_roles member ON member.oid = membership.member
              WHERE member.rolname IN (
                  'accord_migrator', 'accord_api', 'accord_worker',
                  'accord_migrator_login', 'accord_api_login', 'accord_worker_login')
                 OR granted.rolname IN (
                  'accord_migrator', 'accord_api', 'accord_worker',
                  'accord_migrator_login', 'accord_api_login', 'accord_worker_login')
            ), differences AS (
              (SELECT * FROM expected EXCEPT ALL SELECT * FROM actual)
              UNION ALL
              (SELECT * FROM actual EXCEPT ALL SELECT * FROM expected)
            )
            SELECT count(*) FROM differences
            """, 0);
    }

    private static void assertExactPersistenceOwners(Statement statement) throws SQLException {
        assertScalar(statement, """
            SELECT count(*)
            FROM (
              SELECT database.datdba AS owner_oid
              FROM pg_catalog.pg_database database
              WHERE database.datname = pg_catalog.current_database()
              UNION ALL
              SELECT namespace.nspowner
              FROM pg_catalog.pg_namespace namespace
              WHERE namespace.nspname = 'public'
            ) owned
            JOIN pg_catalog.pg_roles owner ON owner.oid = owned.owner_oid
            WHERE owner.rolname = 'accord_migrator'
            """, 2);
    }

    private static void assertExactDatabaseAcl(Statement statement) throws SQLException {
        assertScalar(statement, """
            WITH expected(grantee, privilege_type, is_grantable) AS (VALUES
              ('accord_migrator_login', 'CONNECT', false),
              ('accord_api_login', 'CONNECT', false),
              ('accord_worker_login', 'CONNECT', false),
              ('accord_audit_test', 'CONNECT', false)
            ), actual AS (
              SELECT COALESCE(grantee.rolname::text, 'PUBLIC'),
                     acl.privilege_type, acl.is_grantable
              FROM pg_catalog.pg_database database
              CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(database.datacl,
                  pg_catalog.acldefault('d', database.datdba))) acl
              LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
              WHERE database.datname = pg_catalog.current_database()
                AND acl.grantee <> database.datdba
            ), differences AS (
              (SELECT * FROM expected EXCEPT SELECT * FROM actual)
              UNION ALL
              (SELECT * FROM actual EXCEPT SELECT * FROM expected)
            )
            SELECT count(*) FROM differences
            """, 0);
    }

    private static void assertNoUnexpectedDefaultTablePrivileges(Statement statement)
            throws SQLException {
        assertScalar(statement, """
            SELECT count(*)
            FROM pg_catalog.pg_default_acl defaults
            CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) acl
            JOIN pg_catalog.pg_roles owner ON owner.oid = defaults.defaclrole
            LEFT JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = defaults.defaclnamespace
            WHERE owner.rolname = 'accord_migrator'
              AND (defaults.defaclnamespace = 0 OR namespace.nspname = 'public')
              AND defaults.defaclobjtype = 'r'
              AND acl.grantee <> defaults.defaclrole
            """, 0);
    }

    private static void assertExactPublicSchemaAcl(Statement statement) throws SQLException {
        assertScalar(statement, """
            WITH expected(grantee, privilege_type, is_grantable) AS (VALUES
              ('accord_api', 'USAGE', false),
              ('accord_worker', 'USAGE', false)
            ), actual AS (
              SELECT COALESCE(grantee.rolname::text, 'PUBLIC'),
                     acl.privilege_type, acl.is_grantable
              FROM pg_catalog.pg_namespace namespace
              CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(namespace.nspacl,
                  pg_catalog.acldefault('n', namespace.nspowner))) acl
              LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
              WHERE namespace.nspname = 'public'
                AND acl.grantee <> namespace.nspowner
            ), differences AS (
              (SELECT * FROM expected EXCEPT SELECT * FROM actual)
              UNION ALL
              (SELECT * FROM actual EXCEPT SELECT * FROM expected)
            )
            SELECT count(*) FROM differences
            """, 0);
    }

    private void assertInvalidIdempotencyInsert(
            UUID tenantId, IdempotencyInsert row, String... constraintNames)
            throws SQLException {
        try (Connection connection = roleConnection(
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD,
                "accord_migrator")) {
            beginTenant(connection, tenantId);
            try {
                SQLException violation = assertSqlState(
                    "23514", () -> insertIdempotency(connection, tenantId, row));
                boolean namedConstraintMatched = false;
                for (String constraintName : constraintNames) {
                    namedConstraintMatched |= violation.getMessage().contains(constraintName);
                }
                assertTrue(namedConstraintMatched, violation::getMessage);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void insertIdempotency(
            Connection connection, UUID tenantId, IdempotencyInsert row)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO idempotency_result (
                  tenant_id, actor_id, route_key, idempotency_key,
                  request_fingerprint, state, claim_owner, claim_generation,
                  claim_token, lease_until, response_status, response_headers,
                  response_body, aggregate_version, completed_at, expires_at,
                  aggregate_type, aggregate_id)
                VALUES (?, 'actor', 'POST:/requirements', ?, ?, ?, ?, ?, ?,
                  CASE WHEN ? THEN transaction_timestamp() + interval '5 minutes' END,
                  ?, CAST(? AS jsonb), ?, ?,
                  CASE WHEN ? THEN transaction_timestamp() END,
                  transaction_timestamp() + interval '1 day', ?, ?)
                """)) {
            insert.setObject(1, tenantId);
            insert.setString(2, UUID.randomUUID().toString());
            insert.setString(3, row.fingerprint());
            insert.setString(4, row.state());
            insert.setString(5, row.claimOwner());
            insert.setLong(6, row.claimGeneration());
            insert.setObject(7, row.claimToken());
            insert.setBoolean(8, row.leased());
            insert.setObject(9, row.responseStatus());
            insert.setString(10, row.responseHeaders());
            insert.setString(11, row.responseBody());
            insert.setObject(12, row.aggregateVersion());
            insert.setBoolean(13, row.completed());
            insert.setString(14, row.aggregateType());
            insert.setObject(15, row.aggregateId());
            insert.executeUpdate();
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Connection roleConnection(String login, String password, String role)
            throws SQLException {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), login, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    private void seedTenant(UUID tenantId, UUID aggregateId) throws SQLException {
        try (Connection connection = roleConnection(
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD,
                "accord_migrator")) {
            beginTenant(connection, tenantId);
            insertAggregate(connection, tenantId, aggregateId, 1);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO idempotency_result (
                      tenant_id, actor_id, route_key, idempotency_key,
                      request_fingerprint, state, claim_owner, claim_generation,
                      claim_token, lease_until, expires_at)
                    VALUES (?, 'actor', 'POST:/requirements', ?, ?, 'STARTED',
                      'seed', 1, ?, transaction_timestamp() + interval '5 minutes',
                      transaction_timestamp() + interval '1 day')
                    """)) {
                insert.setObject(1, tenantId);
                insert.setString(2, aggregateId.toString());
                insert.setString(3, FINGERPRINT);
                insert.setObject(4, UUID.randomUUID());
                insert.executeUpdate();
            }
            connection.commit();
        }
    }

    private static void insertAggregate(
            Connection connection, UUID tenantId, UUID aggregateId, long version)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO aggregate_head (
                  tenant_id, aggregate_type, aggregate_id, version)
                VALUES (?, 'requirement', ?, ?)
                """)) {
            insert.setObject(1, tenantId);
            insert.setObject(2, aggregateId);
            insert.setLong(3, version);
            insert.executeUpdate();
        }
    }

    private static void beginTenant(Connection connection, UUID tenantId) throws SQLException {
        beginTenant(connection, tenantId.toString());
    }

    private static String readPackagedBootstrap() throws IOException {
        try (InputStream resource = ControlPlaneTestRoles.class
                .getResourceAsStream("/00-pre-flyway-roles.sql")) {
            if (resource == null) {
                throw new IllegalStateException(
                    "packaged control-plane role bootstrap is missing");
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void beginTenant(Connection connection, String tenantId) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement context = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            context.setString(1, tenantId);
            context.executeQuery().close();
        }
    }

    private static void assertTenantContextCleared(Statement statement) throws SQLException {
        assertScalar(statement, """
            SELECT count(*)
            WHERE NULLIF(current_setting('app.tenant_id', true), '') IS NULL
            """, 1);
    }

    private void assertLoginCanSetOnlyItsPairedRole(
            String login, String password, String pairedRole, String siblingRole)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), login, password);
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT session_user, current_user")) {
                assertTrue(rows.next());
                assertEquals(login, rows.getString(1));
                assertEquals(login, rows.getString(2));
            }
            assertSqlState("42501", () -> statement.executeQuery(
                "SELECT count(*) FROM public.aggregate_head"));
            statement.execute("SET ROLE " + pairedRole);
            try (ResultSet rows = statement.executeQuery("SELECT session_user, current_user")) {
                assertTrue(rows.next());
                assertEquals(login, rows.getString(1));
                assertEquals(pairedRole, rows.getString(2));
            }
            assertSqlState("42501", () -> statement.execute("SET ROLE " + siblingRole));
        }
    }

    private void assertRoleCannotCreateDatabaseOrSchemaObjects(
            String login, String password, String pairedRole) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), login, password);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            assertScalar(statement, """
                SELECT count(*)
                WHERE NOT pg_catalog.has_database_privilege(
                  current_user, pg_catalog.current_database(), 'CREATE')
                  AND NOT pg_catalog.has_schema_privilege(
                    current_user, 'public', 'CREATE')
                """, 1);
            statement.execute("SET ROLE " + pairedRole);
            assertScalar(statement, """
                SELECT count(*)
                WHERE NOT pg_catalog.has_database_privilege(
                  current_user, pg_catalog.current_database(), 'CREATE')
                  AND NOT pg_catalog.has_schema_privilege(
                    current_user, 'public', 'CREATE')
                """, 1);
            statement.execute("SAVEPOINT ownership_schema_attempt");
            assertSqlState("42501", () -> statement.execute(
                "CREATE SCHEMA accord_ownership_escape_test"));
            statement.execute("ROLLBACK TO SAVEPOINT ownership_schema_attempt");
            statement.execute("SAVEPOINT ownership_table_attempt");
            assertSqlState("42501", () -> statement.execute(
                "CREATE TABLE public.accord_ownership_escape_test (id integer)"));
            statement.execute("ROLLBACK TO SAVEPOINT ownership_table_attempt");
            connection.rollback();
        }
    }

    private static SQLException assertSqlState(String expected, Executable executable) {
        SQLException denied = assertThrows(SQLException.class, executable);
        assertEquals(expected, denied.getSQLState());
        return denied;
    }

    private static void executeWithSecondaryCleanup(
            Executable operation, Executable... cleanups) throws Throwable {
        Throwable primary = null;
        try {
            operation.execute();
        } catch (Throwable error) {
            primary = error;
            throw error;
        } finally {
            Throwable cleanupFailure = null;
            for (Executable cleanup : cleanups) {
                try {
                    cleanup.execute();
                } catch (Throwable error) {
                    if (primary != null) {
                        primary.addSuppressed(error);
                    } else if (cleanupFailure == null) {
                        cleanupFailure = error;
                    } else {
                        cleanupFailure.addSuppressed(error);
                    }
                }
            }
            if (primary == null && cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    private static void assertScalar(Statement statement, String sql, int expected)
            throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            assertEquals(expected, rows.getInt(1));
        }
    }

    private static String queryString(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private record IdempotencyInsert(
            String fingerprint,
            String state,
            String claimOwner,
            long claimGeneration,
            UUID claimToken,
            boolean leased,
            Integer responseStatus,
            String responseHeaders,
            String responseBody,
            Long aggregateVersion,
            boolean completed,
            String aggregateType,
            UUID aggregateId) {
        private static IdempotencyInsert validStarted() {
            return new IdempotencyInsert(
                FINGERPRINT, "STARTED", "worker", 1, UUID.randomUUID(), true,
                null, null, null, null, false, null, null);
        }

        private static IdempotencyInsert validCompleted() {
            return new IdempotencyInsert(
                FINGERPRINT, "COMPLETED", null, 1, null, false,
                422, "{}", "", null, true, null, null);
        }

        private IdempotencyInsert withFingerprint(String value) {
            return new IdempotencyInsert(
                value, state, claimOwner, claimGeneration, claimToken, leased,
                responseStatus, responseHeaders, responseBody, aggregateVersion, completed,
                aggregateType, aggregateId);
        }

        private IdempotencyInsert withState(String value) {
            return new IdempotencyInsert(
                fingerprint, value, claimOwner, claimGeneration, claimToken, leased,
                responseStatus, responseHeaders, responseBody, aggregateVersion, completed,
                aggregateType, aggregateId);
        }

        private IdempotencyInsert withClaimOwner(String value) {
            return new IdempotencyInsert(
                fingerprint, state, value, claimGeneration, claimToken, leased,
                responseStatus, responseHeaders, responseBody, aggregateVersion, completed,
                aggregateType, aggregateId);
        }

        private IdempotencyInsert withClaimGeneration(long value) {
            return new IdempotencyInsert(
                fingerprint, state, claimOwner, value, claimToken, leased,
                responseStatus, responseHeaders, responseBody, aggregateVersion, completed,
                aggregateType, aggregateId);
        }

        private IdempotencyInsert withResponseStatus(int value) {
            return new IdempotencyInsert(
                fingerprint, state, claimOwner, claimGeneration, claimToken, leased,
                value, responseHeaders, responseBody, aggregateVersion, completed,
                aggregateType, aggregateId);
        }

        private IdempotencyInsert withResponseHeaders(String value) {
            return new IdempotencyInsert(
                fingerprint, state, claimOwner, claimGeneration, claimToken, leased,
                responseStatus, value, responseBody, aggregateVersion, completed,
                aggregateType, aggregateId);
        }

        private IdempotencyInsert withResponseBody(String value) {
            return new IdempotencyInsert(
                fingerprint, state, claimOwner, claimGeneration, claimToken, leased,
                responseStatus, responseHeaders, value, aggregateVersion, completed,
                aggregateType, aggregateId);
        }

        private IdempotencyInsert withAggregateVersion(Long value) {
            return new IdempotencyInsert(
                fingerprint, state, claimOwner, claimGeneration, claimToken, leased,
                responseStatus, responseHeaders, responseBody, value, completed,
                value == null ? null : "requirement",
                value == null ? null : UUID.randomUUID());
        }

        private IdempotencyInsert withPartialAggregateVersion(Long value) {
            return new IdempotencyInsert(
                fingerprint, state, claimOwner, claimGeneration, claimToken, leased,
                responseStatus, responseHeaders, responseBody, value, completed, null, null);
        }
    }
}
