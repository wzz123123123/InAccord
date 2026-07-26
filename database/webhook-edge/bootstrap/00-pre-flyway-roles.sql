\set ON_ERROR_STOP on

BEGIN;

DO $roles$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM pg_catalog.pg_roles
      WHERE rolname = 'accord_webhook_owner') THEN
    CREATE ROLE accord_webhook_owner NOLOGIN NOSUPERUSER NOCREATEDB
      NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (
      SELECT 1 FROM pg_catalog.pg_roles
      WHERE rolname = 'accord_webhook_migrator_login') THEN
    CREATE ROLE accord_webhook_migrator_login LOGIN PASSWORD NULL NOSUPERUSER
      NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (
      SELECT 1 FROM pg_catalog.pg_roles
      WHERE rolname = 'accord_webhook_runtime') THEN
    CREATE ROLE accord_webhook_runtime NOLOGIN NOSUPERUSER
      NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (
      SELECT 1 FROM pg_catalog.pg_roles
      WHERE rolname = 'accord_webhook_runtime_login') THEN
    CREATE ROLE accord_webhook_runtime_login LOGIN PASSWORD NULL NOSUPERUSER
      NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
END $roles$;

ALTER ROLE accord_webhook_owner NOLOGIN NOSUPERUSER NOCREATEDB
  NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_webhook_migrator_login LOGIN NOSUPERUSER NOCREATEDB
  NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_webhook_runtime NOLOGIN NOSUPERUSER NOCREATEDB
  NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_webhook_runtime_login LOGIN NOSUPERUSER NOCREATEDB
  NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

DO $memberships$
DECLARE
  membership_edge record;
BEGIN
  FOR membership_edge IN
    SELECT granted.rolname AS granted_name,
      member.rolname AS member_name,
      grantor.rolname AS grantor_name
    FROM pg_catalog.pg_auth_members membership
    JOIN pg_catalog.pg_roles granted ON granted.oid = membership.roleid
    JOIN pg_catalog.pg_roles member ON member.oid = membership.member
    JOIN pg_catalog.pg_roles grantor ON grantor.oid = membership.grantor
    WHERE granted.rolname IN (
          'accord_webhook_owner', 'accord_webhook_migrator_login',
          'accord_webhook_runtime', 'accord_webhook_runtime_login')
       OR member.rolname IN (
          'accord_webhook_owner', 'accord_webhook_migrator_login',
          'accord_webhook_runtime', 'accord_webhook_runtime_login')
  LOOP
    EXECUTE pg_catalog.format(
      'REVOKE %I FROM %I GRANTED BY %I CASCADE',
      membership_edge.granted_name,
      membership_edge.member_name,
      membership_edge.grantor_name);
  END LOOP;
END $memberships$;

GRANT accord_webhook_owner TO accord_webhook_migrator_login
  WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
GRANT accord_webhook_runtime TO accord_webhook_runtime_login
  WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

DO $ownership$
BEGIN
  EXECUTE pg_catalog.format(
    'ALTER DATABASE %I OWNER TO accord_webhook_owner',
    pg_catalog.current_database());
  ALTER SCHEMA public OWNER TO accord_webhook_owner;
END $ownership$;

DO $database_acl$
BEGIN
  EXECUTE pg_catalog.format(
    'REVOKE ALL PRIVILEGES ON DATABASE %I FROM PUBLIC',
    pg_catalog.current_database());
  EXECUTE pg_catalog.format(
    'REVOKE ALL PRIVILEGES ON DATABASE %I FROM accord_webhook_migrator_login, accord_webhook_runtime, accord_webhook_runtime_login',
    pg_catalog.current_database());
  EXECUTE pg_catalog.format(
    'GRANT CONNECT ON DATABASE %I TO accord_webhook_migrator_login, accord_webhook_runtime_login',
    pg_catalog.current_database());
END $database_acl$;

REVOKE ALL PRIVILEGES ON SCHEMA public
  FROM PUBLIC, accord_webhook_migrator_login,
    accord_webhook_runtime, accord_webhook_runtime_login CASCADE;
GRANT USAGE ON SCHEMA public TO accord_webhook_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE accord_webhook_owner
  REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_webhook_owner IN SCHEMA public
  REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_webhook_owner IN SCHEMA public
  REVOKE ALL PRIVILEGES ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_webhook_owner IN SCHEMA public
  REVOKE ALL PRIVILEGES ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_webhook_owner
  REVOKE ALL PRIVILEGES ON TYPES FROM PUBLIC;

COMMIT;
