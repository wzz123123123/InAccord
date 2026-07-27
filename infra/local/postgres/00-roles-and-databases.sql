\set ON_ERROR_STOP on

DO $accord$
DECLARE
    schema_password text := current_setting('accord.temporal_schema_password', true);
    runtime_password text := current_setting('accord.temporal_runtime_password', true);
BEGIN
    IF schema_password IS NULL OR length(schema_password) < 20 THEN
        RAISE EXCEPTION 'accord.temporal_schema_password must contain at least 20 characters';
    END IF;
    IF runtime_password IS NULL OR length(runtime_password) < 20 THEN
        RAISE EXCEPTION 'accord.temporal_runtime_password must contain at least 20 characters';
    END IF;
    IF schema_password = runtime_password THEN
        RAISE EXCEPTION 'Temporal schema and runtime credentials must differ';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_temporal_owner') THEN
        CREATE ROLE accord_temporal_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_temporal_visibility_owner') THEN
        CREATE ROLE accord_temporal_visibility_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_temporal_runtime') THEN
        CREATE ROLE accord_temporal_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_temporal_schema_login') THEN
        EXECUTE format(
            'CREATE ROLE accord_temporal_schema_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L',
            schema_password);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_temporal_runtime_login') THEN
        EXECUTE format(
            'CREATE ROLE accord_temporal_runtime_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L',
            runtime_password);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_app_login') THEN
        CREATE ROLE accord_app_login NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_webhook_login') THEN
        CREATE ROLE accord_webhook_login NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_signing_login') THEN
        CREATE ROLE accord_signing_login NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END
$accord$;

GRANT accord_temporal_owner, accord_temporal_visibility_owner TO accord_temporal_schema_login;
GRANT accord_temporal_runtime TO accord_temporal_runtime_login;

SELECT 'CREATE DATABASE accord OWNER accord_app_login'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'accord')\gexec
SELECT 'CREATE DATABASE accord_webhook OWNER accord_webhook_login'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'accord_webhook')\gexec
SELECT 'CREATE DATABASE accord_signing OWNER accord_signing_login'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'accord_signing')\gexec
SELECT 'CREATE DATABASE accord_temporal OWNER accord_temporal_owner'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'accord_temporal')\gexec
SELECT 'CREATE DATABASE accord_temporal_visibility OWNER accord_temporal_visibility_owner'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'accord_temporal_visibility')\gexec

REVOKE CONNECT ON DATABASE accord FROM PUBLIC;
REVOKE CONNECT ON DATABASE accord_webhook FROM PUBLIC;
REVOKE CONNECT ON DATABASE accord_signing FROM PUBLIC;
REVOKE CONNECT ON DATABASE accord_temporal FROM PUBLIC;
REVOKE CONNECT ON DATABASE accord_temporal_visibility FROM PUBLIC;

GRANT CONNECT ON DATABASE accord TO accord_app_login;
GRANT CONNECT ON DATABASE accord_webhook TO accord_webhook_login;
GRANT CONNECT ON DATABASE accord_signing TO accord_signing_login;
GRANT CONNECT ON DATABASE accord_temporal TO accord_temporal_schema_login, accord_temporal_runtime_login;
GRANT CONNECT ON DATABASE accord_temporal_visibility TO accord_temporal_schema_login, accord_temporal_runtime_login;

REVOKE CONNECT ON DATABASE accord FROM accord_temporal_schema_login, accord_temporal_runtime_login;
REVOKE CONNECT ON DATABASE accord_webhook FROM accord_temporal_schema_login, accord_temporal_runtime_login;
REVOKE CONNECT ON DATABASE accord_signing FROM accord_temporal_schema_login, accord_temporal_runtime_login;
REVOKE CONNECT ON DATABASE accord_temporal FROM accord_app_login, accord_webhook_login, accord_signing_login;
REVOKE CONNECT ON DATABASE accord_temporal_visibility FROM accord_app_login, accord_webhook_login, accord_signing_login;

\connect accord_temporal
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO accord_temporal_schema_login;
GRANT USAGE ON SCHEMA public TO accord_temporal_runtime, accord_temporal_runtime_login;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_temporal_schema_login IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO accord_temporal_runtime, accord_temporal_runtime_login;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_temporal_schema_login IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO accord_temporal_runtime, accord_temporal_runtime_login;

\connect accord_temporal_visibility
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO accord_temporal_schema_login;
GRANT USAGE ON SCHEMA public TO accord_temporal_runtime, accord_temporal_runtime_login;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_temporal_schema_login IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO accord_temporal_runtime, accord_temporal_runtime_login;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_temporal_schema_login IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO accord_temporal_runtime, accord_temporal_runtime_login;
