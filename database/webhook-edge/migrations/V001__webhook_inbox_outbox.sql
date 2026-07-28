SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE public.webhook_inbox (
    tenant_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    provider varchar(16) NOT NULL,
    immutable_repository_id bigint NOT NULL,
    webhook_id uuid NOT NULL,
    event_type varchar(128) NOT NULL,
    body_digest varchar(71) NOT NULL,
    observed_at timestamptz NOT NULL,
    ref varchar(1024),
    before_sha varchar(64),
    after_sha varchar(64),
    received_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    CONSTRAINT webhook_inbox_pkey
        PRIMARY KEY (tenant_id, provider, immutable_repository_id, webhook_id),
    CONSTRAINT webhook_inbox_provider_gitlab
        CHECK (provider = 'gitlab'),
    CONSTRAINT webhook_inbox_repository_positive
        CHECK (immutable_repository_id >= 1),
    CONSTRAINT webhook_inbox_event_type_safe
        CHECK (pg_catalog.octet_length(event_type) BETWEEN 1 AND 128
          AND event_type ~ '^[ -~]+$'),
    CONSTRAINT webhook_inbox_digest_format
        CHECK (body_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT webhook_inbox_ref_safe
        CHECK (ref IS NULL OR (
          pg_catalog.octet_length(ref) BETWEEN 1 AND 4096
          AND ref !~ '[[:cntrl:]]')),
    CONSTRAINT webhook_inbox_before_sha_format
        CHECK (before_sha IS NULL OR before_sha ~ '^[0-9a-f]{40,64}$'),
    CONSTRAINT webhook_inbox_after_sha_format
        CHECK (after_sha IS NULL OR after_sha ~ '^[0-9a-f]{40,64}$')
);

CREATE TABLE public.webhook_outbox (
    tenant_id uuid NOT NULL,
    signal_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    provider varchar(16) NOT NULL,
    immutable_repository_id bigint NOT NULL,
    webhook_id uuid NOT NULL,
    schema_version varchar(16) NOT NULL,
    signal jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    CONSTRAINT webhook_outbox_pkey
        PRIMARY KEY (tenant_id, signal_id),
    CONSTRAINT webhook_outbox_inbox_key
        UNIQUE (tenant_id, provider, immutable_repository_id, webhook_id),
    CONSTRAINT webhook_outbox_inbox_fkey
        FOREIGN KEY (tenant_id, provider, immutable_repository_id, webhook_id)
        REFERENCES public.webhook_inbox (
          tenant_id, provider, immutable_repository_id, webhook_id),
    CONSTRAINT webhook_outbox_provider_gitlab
        CHECK (provider = 'gitlab'),
    CONSTRAINT webhook_outbox_repository_positive
        CHECK (immutable_repository_id >= 1),
    CONSTRAINT webhook_outbox_schema_version
        CHECK (schema_version = '1.0.0'),
    CONSTRAINT webhook_outbox_signal_object
        CHECK (pg_catalog.jsonb_typeof(signal) = 'object'),
    CONSTRAINT webhook_outbox_signal_bounded
        CHECK (pg_catalog.octet_length(signal::text) <= 16384),
    CONSTRAINT webhook_outbox_signal_required
        CHECK (signal ?& ARRAY[
          'schema_version','tenant_id','scope_type','scope_id','provider',
          'immutable_repository_id','delivery_id','event_type','body_digest',
          'observed_at']::text[]),
    CONSTRAINT webhook_outbox_signal_closed
        CHECK ((signal - ARRAY[
          'schema_version','tenant_id','scope_type','scope_id','provider',
          'immutable_repository_id','delivery_id','event_type','body_digest',
          'observed_at','ref','before_sha','after_sha']::text[]) = '{}'::jsonb),
    CONSTRAINT webhook_outbox_signal_required_types
        CHECK (pg_catalog.jsonb_typeof(signal->'schema_version') = 'string'
          AND pg_catalog.jsonb_typeof(signal->'tenant_id') = 'string'
          AND pg_catalog.jsonb_typeof(signal->'scope_type') = 'string'
          AND pg_catalog.jsonb_typeof(signal->'scope_id') = 'string'
          AND pg_catalog.jsonb_typeof(signal->'provider') = 'string'
          AND pg_catalog.jsonb_typeof(signal->'immutable_repository_id') = 'number'
          AND pg_catalog.jsonb_typeof(signal->'delivery_id') = 'string'
          AND pg_catalog.jsonb_typeof(signal->'event_type') = 'string'
          AND pg_catalog.jsonb_typeof(signal->'body_digest') = 'string'
          AND pg_catalog.jsonb_typeof(signal->'observed_at') = 'string'),
    CONSTRAINT webhook_outbox_signal_identity
        CHECK (signal->>'schema_version' = schema_version
          AND signal->>'tenant_id' = tenant_id::text
          AND signal->>'scope_type' = 'repository'
          AND signal->>'scope_id' = scope_id::text
          AND signal->>'provider' = provider
          AND signal->'immutable_repository_id' = pg_catalog.to_jsonb(immutable_repository_id)
          AND signal->>'delivery_id' = webhook_id::text),
    CONSTRAINT webhook_outbox_signal_event_type
        CHECK (pg_catalog.octet_length(signal->>'event_type') BETWEEN 1 AND 128
          AND signal->>'event_type' ~ '^[ -~]+$'),
    CONSTRAINT webhook_outbox_signal_digest_format
        CHECK (signal->>'body_digest' ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT webhook_outbox_signal_observed_at
        CHECK (pg_catalog.octet_length(signal->>'observed_at') BETWEEN 20 AND 40
          AND signal->>'observed_at'
            ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$'),
    CONSTRAINT webhook_outbox_signal_ref
        CHECK (NOT signal ? 'ref' OR signal->'ref' = 'null'::jsonb OR (
          pg_catalog.jsonb_typeof(signal->'ref') = 'string'
          AND pg_catalog.octet_length(signal->>'ref') BETWEEN 1 AND 4096
          AND signal->>'ref' !~ '[[:cntrl:]]')),
    CONSTRAINT webhook_outbox_signal_before_sha
        CHECK (NOT signal ? 'before_sha' OR signal->'before_sha' = 'null'::jsonb OR (
          pg_catalog.jsonb_typeof(signal->'before_sha') = 'string'
          AND signal->>'before_sha' ~ '^[0-9a-f]{40,64}$')),
    CONSTRAINT webhook_outbox_signal_after_sha
        CHECK (NOT signal ? 'after_sha' OR signal->'after_sha' = 'null'::jsonb OR (
          pg_catalog.jsonb_typeof(signal->'after_sha') = 'string'
          AND signal->>'after_sha' ~ '^[0-9a-f]{40,64}$'))
);

ALTER TABLE public.webhook_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.webhook_inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.webhook_inbox
    FOR ALL TO PUBLIC
    USING (
      tenant_id = NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (
      tenant_id = NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE public.webhook_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.webhook_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.webhook_outbox
    FOR ALL TO PUBLIC
    USING (
      tenant_id = NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (
      tenant_id = NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::uuid);

CREATE SCHEMA webhook_edge_security AUTHORIZATION accord_webhook_owner;
REVOKE ALL PRIVILEGES ON SCHEMA webhook_edge_security
    FROM PUBLIC, accord_webhook_migrator_login,
      accord_webhook_runtime, accord_webhook_runtime_login;
GRANT USAGE ON SCHEMA webhook_edge_security TO accord_webhook_runtime;

CREATE FUNCTION webhook_edge_security.validate_webhook_outbox()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
PARALLEL UNSAFE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.webhook_inbox inbox
        WHERE inbox.tenant_id = NEW.tenant_id
          AND inbox.provider = NEW.provider
          AND inbox.immutable_repository_id = NEW.immutable_repository_id
          AND inbox.webhook_id = NEW.webhook_id
          AND inbox.scope_id = NEW.scope_id
          AND inbox.event_type = NEW.signal->>'event_type'
          AND inbox.body_digest = NEW.signal->>'body_digest'
          AND inbox.observed_at = (NEW.signal->>'observed_at')::timestamptz
          AND inbox.ref IS NOT DISTINCT FROM (NEW.signal->>'ref')
          AND inbox.before_sha IS NOT DISTINCT FROM (NEW.signal->>'before_sha')
          AND inbox.after_sha IS NOT DISTINCT FROM (NEW.signal->>'after_sha')) THEN
        RAISE EXCEPTION 'webhook outbox signal rejected'
          USING ERRCODE = '23514',
            CONSTRAINT = 'webhook_outbox_signal_matches_inbox';
    END IF;
    RETURN NEW;
END
$function$;

REVOKE ALL PRIVILEGES ON FUNCTION
    webhook_edge_security.validate_webhook_outbox()
    FROM PUBLIC, accord_webhook_migrator_login,
      accord_webhook_runtime, accord_webhook_runtime_login;

CREATE TRIGGER validate_webhook_outbox_before_insert
BEFORE INSERT ON public.webhook_outbox
FOR EACH ROW EXECUTE FUNCTION webhook_edge_security.validate_webhook_outbox();

CREATE FUNCTION webhook_edge_security.lock_webhook_inbox(
    expected_tenant_id uuid,
    expected_provider varchar,
    expected_immutable_repository_id bigint,
    expected_webhook_id uuid)
RETURNS TABLE (
    stored_tenant_id uuid,
    stored_provider varchar,
    stored_immutable_repository_id bigint,
    stored_webhook_id uuid,
    stored_body_digest varchar)
LANGUAGE plpgsql
VOLATILE
PARALLEL UNSAFE
SECURITY DEFINER
ROWS 1
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    session_tenant_id uuid;
BEGIN
    session_tenant_id := NULLIF(
      pg_catalog.current_setting('app.tenant_id', true), '')::uuid;
    IF session_tenant_id IS NULL
       OR session_tenant_id IS DISTINCT FROM expected_tenant_id THEN
        RAISE EXCEPTION 'webhook tenant context rejected'
          USING ERRCODE = '42501';
    END IF;

    RETURN QUERY
    SELECT inbox.tenant_id,
           inbox.provider,
           inbox.immutable_repository_id,
           inbox.webhook_id,
           inbox.body_digest
    FROM public.webhook_inbox inbox
    WHERE inbox.tenant_id = expected_tenant_id
      AND inbox.provider = expected_provider
      AND inbox.immutable_repository_id = expected_immutable_repository_id
      AND inbox.webhook_id = expected_webhook_id
    FOR UPDATE;
END
$function$;

REVOKE ALL PRIVILEGES ON FUNCTION webhook_edge_security.lock_webhook_inbox(
    uuid, varchar, bigint, uuid)
    FROM PUBLIC, accord_webhook_migrator_login,
      accord_webhook_runtime, accord_webhook_runtime_login;
GRANT EXECUTE ON FUNCTION webhook_edge_security.lock_webhook_inbox(
    uuid, varchar, bigint, uuid)
    TO accord_webhook_runtime;

REVOKE ALL PRIVILEGES ON TABLE public.webhook_inbox, public.webhook_outbox
    FROM PUBLIC, accord_webhook_migrator_login,
      accord_webhook_runtime, accord_webhook_runtime_login;
GRANT SELECT, INSERT ON TABLE public.webhook_inbox, public.webhook_outbox
    TO accord_webhook_runtime;
