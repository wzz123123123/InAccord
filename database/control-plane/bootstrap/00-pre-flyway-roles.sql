\set ON_ERROR_STOP on

BEGIN;

DO $roles$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'accord_migrator') THEN
    CREATE ROLE accord_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
      NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'accord_api') THEN
    CREATE ROLE accord_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
      NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'accord_worker') THEN
    CREATE ROLE accord_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
      NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'accord_migrator_login') THEN
    CREATE ROLE accord_migrator_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
      NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'accord_api_login') THEN
    CREATE ROLE accord_api_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
      NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'accord_worker_login') THEN
    CREATE ROLE accord_worker_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
      NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD NULL;
  END IF;
END $roles$;

ALTER ROLE accord_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_migrator_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_api_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_worker_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;

DO $memberships$
DECLARE
  edge record;
BEGIN
  FOR edge IN
    SELECT granted.rolname AS granted_name, member.rolname AS member_name,
      grantor.rolname AS grantor_name
    FROM pg_catalog.pg_auth_members membership
    JOIN pg_catalog.pg_roles granted ON granted.oid = membership.roleid
    JOIN pg_catalog.pg_roles member ON member.oid = membership.member
    JOIN pg_catalog.pg_roles grantor ON grantor.oid = membership.grantor
    WHERE member.rolname IN (
        'accord_migrator', 'accord_api', 'accord_worker',
        'accord_migrator_login', 'accord_api_login', 'accord_worker_login')
       OR granted.rolname IN (
        'accord_migrator', 'accord_api', 'accord_worker',
        'accord_migrator_login', 'accord_api_login', 'accord_worker_login')
  LOOP
    EXECUTE pg_catalog.format(
      'REVOKE %I FROM %I GRANTED BY %I CASCADE',
      edge.granted_name, edge.member_name, edge.grantor_name);
  END LOOP;
END $memberships$;

GRANT accord_migrator TO accord_migrator_login
  WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
GRANT accord_api TO accord_api_login
  WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
GRANT accord_worker TO accord_worker_login
  WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

DO $owners$
BEGIN
  EXECUTE pg_catalog.format(
    'ALTER DATABASE %I OWNER TO accord_migrator', pg_catalog.current_database());
  ALTER SCHEMA public OWNER TO accord_migrator;
END $owners$;

DO $database$
DECLARE
  grantee_name text;
BEGIN
  EXECUTE pg_catalog.format(
    'REVOKE ALL ON DATABASE %I FROM PUBLIC', pg_catalog.current_database());
  FOR grantee_name IN
    SELECT DISTINCT grantee.rolname
    FROM pg_catalog.pg_database database
    CROSS JOIN LATERAL pg_catalog.aclexplode(
      COALESCE(database.datacl,
        pg_catalog.acldefault('d', database.datdba))) acl
    JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
    WHERE database.datname = pg_catalog.current_database()
      AND acl.grantee <> database.datdba
  LOOP
    EXECUTE pg_catalog.format(
      'REVOKE ALL PRIVILEGES ON DATABASE %I FROM %I CASCADE',
      pg_catalog.current_database(), grantee_name);
  END LOOP;
  EXECUTE pg_catalog.format(
    'GRANT CONNECT ON DATABASE %I TO accord_migrator_login, accord_api_login, accord_worker_login',
    pg_catalog.current_database());
END $database$;

DO $schema_acl$
DECLARE
  grantee record;
BEGIN
  FOR grantee IN
    SELECT acl.grantee AS grantee_oid, roles.rolname AS grantee_name
    FROM pg_catalog.pg_namespace namespace
    CROSS JOIN LATERAL pg_catalog.aclexplode(
      COALESCE(namespace.nspacl,
        pg_catalog.acldefault('n', namespace.nspowner))) acl
    LEFT JOIN pg_catalog.pg_roles roles ON roles.oid = acl.grantee
    WHERE namespace.nspname = 'public'
      AND acl.grantee <> namespace.nspowner
    GROUP BY acl.grantee, roles.rolname
  LOOP
    IF grantee.grantee_oid = 0 THEN
      REVOKE ALL PRIVILEGES ON SCHEMA public FROM PUBLIC CASCADE;
    ELSE
      EXECUTE pg_catalog.format(
        'REVOKE ALL PRIVILEGES ON SCHEMA public FROM %I CASCADE',
        grantee.grantee_name);
    END IF;
  END LOOP;
END $schema_acl$;

GRANT USAGE ON SCHEMA public TO accord_api, accord_worker;

DO $default_table_acl$
DECLARE
  grantee record;
BEGIN
  FOR grantee IN
    SELECT defaults.defaclnamespace AS namespace_oid,
      acl.grantee AS grantee_oid, roles.rolname AS grantee_name
    FROM pg_catalog.pg_default_acl defaults
    CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) acl
    JOIN pg_catalog.pg_roles owner ON owner.oid = defaults.defaclrole
    LEFT JOIN pg_catalog.pg_namespace namespace
      ON namespace.oid = defaults.defaclnamespace
    LEFT JOIN pg_catalog.pg_roles roles ON roles.oid = acl.grantee
    WHERE owner.rolname = 'accord_migrator'
      AND (defaults.defaclnamespace = 0 OR namespace.nspname = 'public')
      AND defaults.defaclobjtype = 'r'
      AND acl.grantee <> defaults.defaclrole
    GROUP BY defaults.defaclnamespace, acl.grantee, roles.rolname
  LOOP
    IF grantee.namespace_oid = 0 THEN
      IF grantee.grantee_oid = 0 THEN
        ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator
          REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC CASCADE;
      ELSE
        EXECUTE pg_catalog.format(
          'ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator '
            || 'REVOKE ALL PRIVILEGES ON TABLES FROM %I CASCADE',
          grantee.grantee_name);
      END IF;
    ELSIF grantee.grantee_oid = 0 THEN
      ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator IN SCHEMA public
        REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC CASCADE;
    ELSE
      EXECUTE pg_catalog.format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator IN SCHEMA public '
          || 'REVOKE ALL PRIVILEGES ON TABLES FROM %I CASCADE',
        grantee.grantee_name);
    END IF;
  END LOOP;
END $default_table_acl$;

COMMIT;
