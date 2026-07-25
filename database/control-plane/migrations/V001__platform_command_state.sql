SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE public.aggregate_head (
    tenant_id uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    CONSTRAINT aggregate_head_pkey
        PRIMARY KEY (tenant_id, aggregate_type, aggregate_id),
    CONSTRAINT aggregate_head_version_positive CHECK (version >= 1)
);

CREATE TABLE public.idempotency_result (
    tenant_id uuid NOT NULL,
    actor_id varchar(255) NOT NULL,
    route_key varchar(128) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint char(71) NOT NULL,
    state varchar(16) NOT NULL,
    claim_owner varchar(255),
    claim_generation bigint NOT NULL,
    claim_token uuid,
    lease_until timestamptz,
    response_status integer,
    response_headers jsonb,
    response_body text,
    aggregate_type varchar(64),
    aggregate_id uuid,
    aggregate_version bigint,
    started_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    completed_at timestamptz,
    expires_at timestamptz NOT NULL,
    CONSTRAINT idempotency_result_pkey
        PRIMARY KEY (tenant_id, actor_id, route_key, idempotency_key),
    CONSTRAINT idempotency_result_fingerprint_format
        CHECK (request_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT idempotency_result_state_known
        CHECK (state IN ('STARTED', 'COMPLETED')),
    CONSTRAINT idempotency_result_claim_generation_positive
        CHECK (claim_generation >= 1),
    CONSTRAINT idempotency_result_response_status_valid
        CHECK (response_status BETWEEN 100 AND 599),
    CONSTRAINT idempotency_result_aggregate_version_positive
        CHECK (aggregate_version >= 1),
    CONSTRAINT idempotency_result_response_headers_bounded
        CHECK (response_headers IS NULL OR pg_catalog.pg_column_size(response_headers) <= 65536),
    CONSTRAINT idempotency_result_response_body_bounded
        CHECK (response_body IS NULL OR pg_catalog.octet_length(response_body) <= 1048576),
    CONSTRAINT idempotency_result_lifecycle_consistent CHECK (
        (state = 'STARTED'
          AND claim_owner IS NOT NULL
          AND claim_token IS NOT NULL
          AND lease_until IS NOT NULL
          AND response_status IS NULL
          AND completed_at IS NULL)
        OR
        (state = 'COMPLETED'
          AND claim_owner IS NULL
          AND claim_token IS NULL
          AND lease_until IS NULL
          AND response_status IS NOT NULL
          AND response_headers IS NOT NULL
          AND response_body IS NOT NULL
          AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idempotency_result_expiry_idx
    ON public.idempotency_result (expires_at);

CREATE SCHEMA accord_security AUTHORIZATION accord_migrator;
REVOKE ALL ON SCHEMA accord_security FROM PUBLIC, accord_api, accord_worker;

CREATE FUNCTION accord_security.current_tenant_id()
RETURNS uuid
LANGUAGE sql
STABLE
PARALLEL SAFE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
RETURN NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::pg_catalog.uuid;

REVOKE ALL ON FUNCTION accord_security.current_tenant_id() FROM PUBLIC;
GRANT USAGE ON SCHEMA accord_security TO accord_api, accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.current_tenant_id()
    TO accord_api, accord_worker;

CREATE FUNCTION accord_security.enforce_tenant_table(target pg_catalog.regclass)
RETURNS void
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $policy$
DECLARE
    target_schema text;
    target_name text;
BEGIN
    SELECT namespace.nspname, relation.relname
      INTO target_schema, target_name
    FROM pg_catalog.pg_class relation
    JOIN pg_catalog.pg_namespace namespace
      ON namespace.oid = relation.relnamespace
    WHERE relation.oid = target
      AND relation.relkind IN ('r', 'p');

    IF target_schema IS DISTINCT FROM 'public' OR target_name IS NULL THEN
        RAISE EXCEPTION 'tenant RLS target must be a public table: %', target;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_attribute attribute
        WHERE attribute.attrelid = target
          AND attribute.attname = 'tenant_id'
          AND NOT attribute.attisdropped
          AND attribute.atttypid = 'pg_catalog.uuid'::pg_catalog.regtype
    ) THEN
        RAISE EXCEPTION 'tenant RLS target lacks uuid tenant_id: %', target;
    END IF;

    EXECUTE pg_catalog.format(
        'ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY', target_schema, target_name);
    EXECUTE pg_catalog.format(
        'ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY', target_schema, target_name);

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_policy policy
        WHERE policy.polrelid = target
          AND policy.polname = 'tenant_isolation'
    ) THEN
        EXECUTE pg_catalog.format(
            'CREATE POLICY tenant_isolation ON %I.%I FOR ALL TO PUBLIC '
              || 'USING (tenant_id = accord_security.current_tenant_id()) '
              || 'WITH CHECK (tenant_id = accord_security.current_tenant_id())',
            target_schema,
            target_name);
    END IF;

    IF (SELECT pg_catalog.count(*)
        FROM pg_catalog.pg_policy policy
        WHERE policy.polrelid = target) <> 1
       OR NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_policy policy
        WHERE policy.polrelid = target
          AND policy.polname = 'tenant_isolation'
          AND policy.polpermissive
          AND policy.polcmd = '*'
          AND policy.polroles = ARRAY[0::oid]
          AND pg_catalog.pg_get_expr(policy.polqual, policy.polrelid)
            = '(tenant_id = accord_security.current_tenant_id())'
          AND pg_catalog.pg_get_expr(policy.polwithcheck, policy.polrelid)
            = '(tenant_id = accord_security.current_tenant_id())'
    ) THEN
        RAISE EXCEPTION 'tenant RLS policy set is non-standard on %', target;
    END IF;
END
$policy$;

REVOKE ALL ON FUNCTION accord_security.enforce_tenant_table(pg_catalog.regclass)
    FROM PUBLIC, accord_api, accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.enforce_tenant_table(pg_catalog.regclass)
    TO accord_migrator;

SELECT accord_security.enforce_tenant_table(
    'public.aggregate_head'::pg_catalog.regclass);
SELECT accord_security.enforce_tenant_table(
    'public.idempotency_result'::pg_catalog.regclass);

REVOKE ALL ON public.aggregate_head, public.idempotency_result
    FROM PUBLIC, accord_api, accord_worker;
GRANT SELECT, INSERT, UPDATE
    ON public.aggregate_head, public.idempotency_result
    TO accord_api, accord_worker;
GRANT DELETE ON public.idempotency_result TO accord_worker;
