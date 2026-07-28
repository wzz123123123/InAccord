package com.inforvans.accord.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

public final class ControlPlaneTestRoles {
    public static final String MIGRATOR_LOGIN = "accord_migrator_login";
    public static final String MIGRATOR_PASSWORD = "migrator-test";
    public static final String API_LOGIN = "accord_api_login";
    public static final String API_PASSWORD = "api-test";
    public static final String WORKER_LOGIN = "accord_worker_login";
    public static final String WORKER_PASSWORD = "worker-test";
    public static final String AUDIT_LOGIN = "accord_audit_test";
    public static final String AUDIT_PASSWORD = "audit-test";

    private static final Pattern SESSION_ROLE = Pattern.compile("^accord_[a-z_]+$");

    private ControlPlaneTestRoles() {}

    public static String jdbcUrlWithRole(String jdbcUrl, String role) {
        if (!SESSION_ROLE.matcher(role).matches()) {
            throw new IllegalArgumentException("invalid test session role");
        }
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "options=-c%20role%3D" + role;
    }

    public static void bootstrap(String jdbcUrl, String user, String password) {
        try (InputStream resource = ControlPlaneTestRoles.class
                 .getResourceAsStream("/00-pre-flyway-roles.sql");
             Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement()) {
            if (resource == null) {
                throw new IllegalStateException(
                    "packaged control-plane role bootstrap is missing");
            }
            String bootstrap = new String(
                resource.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\\set ON_ERROR_STOP on", "");
            statement.execute(bootstrap);
            statement.execute("""
                DO $roles$
                BEGIN
                  IF NOT EXISTS (
                      SELECT 1 FROM pg_catalog.pg_roles
                      WHERE rolname = 'accord_audit_test') THEN
                    CREATE ROLE accord_audit_test LOGIN PASSWORD 'audit-test'
                      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
                      NOREPLICATION NOBYPASSRLS;
                  END IF;
                END $roles$;
                ALTER ROLE accord_audit_test LOGIN PASSWORD 'audit-test'
                  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
                  NOREPLICATION NOBYPASSRLS;
                REVOKE accord_migrator, accord_api, accord_worker
                  FROM accord_audit_test;
                """);
            statement.execute("ALTER ROLE " + MIGRATOR_LOGIN
                + " PASSWORD '" + MIGRATOR_PASSWORD + "'");
            statement.execute("ALTER ROLE " + API_LOGIN
                + " PASSWORD '" + API_PASSWORD + "'");
            statement.execute("ALTER ROLE " + WORKER_LOGIN
                + " PASSWORD '" + WORKER_PASSWORD + "'");

            String database = "\"" + connection.getCatalog().replace("\"", "\"\"") + "\"";
            statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + AUDIT_LOGIN);
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("cannot bootstrap control-plane test roles", error);
        }
    }
}
