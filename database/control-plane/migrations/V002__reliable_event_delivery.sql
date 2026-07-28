SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE public.domain_event (
    tenant_id uuid NOT NULL,
    event_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_id varchar(255) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    sequence bigint NOT NULL,
    event_type varchar(128) NOT NULL,
    schema_version varchar(32) NOT NULL,
    causation_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    actor_id varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    CONSTRAINT domain_event_pkey PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT domain_event_aggregate_sequence_key
        UNIQUE (tenant_id, aggregate_type, aggregate_id, sequence),
    CONSTRAINT domain_event_scope_type_known
        CHECK (scope_type IN ('tenant', 'project', 'repository')),
    CONSTRAINT domain_event_sequence_positive CHECK (sequence >= 1),
    CONSTRAINT domain_event_aggregate_type_format
        CHECK (aggregate_type ~ '^[a-z][a-z0-9_.-]{1,63}$'),
    CONSTRAINT domain_event_event_type_format
        CHECK (event_type ~ '^[a-z][a-z0-9_.-]{1,127}$'),
    CONSTRAINT domain_event_schema_version_format
        CHECK (schema_version ~ '^[1-9][0-9]*[.][0-9]+[.][0-9]+$'),
    CONSTRAINT domain_event_payload_bounded
        CHECK (pg_catalog.octet_length(payload::text) <= 1048576),
    CONSTRAINT domain_event_text_bounded CHECK (
        pg_catalog.btrim(scope_id) <> ''
        AND pg_catalog.btrim(actor_id) <> ''
        AND scope_id !~ '[[:cntrl:]]'
        AND actor_id !~ '[[:cntrl:]]')
);

CREATE TABLE public.outbox_event (
    tenant_id uuid NOT NULL,
    event_id uuid NOT NULL,
    destination varchar(128) NOT NULL,
    payload_schema varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    redrive_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    lease_owner varchar(255),
    lease_until timestamptz,
    delivered_at timestamptz,
    dead_at timestamptz,
    last_error_code varchar(128),
    CONSTRAINT outbox_event_pkey PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT outbox_event_domain_event_fkey
        FOREIGN KEY (tenant_id, event_id)
        REFERENCES public.domain_event (tenant_id, event_id),
    CONSTRAINT outbox_event_state_known
        CHECK (state IN ('PENDING', 'DELIVERING', 'DELIVERED', 'DEAD')),
    CONSTRAINT outbox_event_attempt_count_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT outbox_event_redrive_count_nonnegative CHECK (redrive_count >= 0),
    CONSTRAINT outbox_event_payload_bounded
        CHECK (pg_catalog.octet_length(payload::text) <= 1048576),
    CONSTRAINT outbox_event_error_code_format CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
    CONSTRAINT outbox_event_text_bounded CHECK (
        pg_catalog.btrim(destination) <> ''
        AND pg_catalog.btrim(payload_schema) <> ''
        AND destination !~ '[[:cntrl:]]'
        AND payload_schema !~ '[[:cntrl:]]'),
    CONSTRAINT outbox_event_lifecycle_consistent CHECK (
        (state = 'PENDING'
          AND lease_owner IS NULL AND lease_until IS NULL
          AND delivered_at IS NULL AND dead_at IS NULL)
        OR
        (state = 'DELIVERING'
          AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
          AND delivered_at IS NULL AND dead_at IS NULL)
        OR
        (state = 'DELIVERED'
          AND lease_owner IS NULL AND lease_until IS NULL
          AND delivered_at IS NOT NULL AND dead_at IS NULL)
        OR
        (state = 'DEAD'
          AND lease_owner IS NULL AND lease_until IS NULL
          AND delivered_at IS NULL AND dead_at IS NOT NULL
          AND last_error_code IS NOT NULL))
);

CREATE INDEX outbox_event_ready_idx
    ON public.outbox_event (tenant_id, state, available_at, event_id)
    WHERE state IN ('PENDING', 'DELIVERING');

CREATE TABLE public.inbox_message (
    tenant_id uuid NOT NULL,
    source varchar(128) NOT NULL,
    source_message_id varchar(255) NOT NULL,
    request_digest char(71) NOT NULL,
    handler_key varchar(128) NOT NULL,
    payload_schema varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    redrive_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    lease_owner varchar(255),
    lease_until timestamptz,
    received_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    completed_at timestamptz,
    dead_at timestamptz,
    last_error_code varchar(128),
    CONSTRAINT inbox_message_pkey
        PRIMARY KEY (tenant_id, source, source_message_id),
    CONSTRAINT inbox_message_request_digest_format
        CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT inbox_message_state_known
        CHECK (state IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD')),
    CONSTRAINT inbox_message_attempt_count_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT inbox_message_redrive_count_nonnegative CHECK (redrive_count >= 0),
    CONSTRAINT inbox_message_payload_bounded
        CHECK (pg_catalog.octet_length(payload::text) <= 1048576),
    CONSTRAINT inbox_message_error_code_format CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
    CONSTRAINT inbox_message_text_bounded CHECK (
        pg_catalog.btrim(source) <> ''
        AND pg_catalog.btrim(source_message_id) <> ''
        AND pg_catalog.btrim(handler_key) <> ''
        AND pg_catalog.btrim(payload_schema) <> ''
        AND source !~ '[[:cntrl:]]'
        AND source_message_id !~ '[[:cntrl:]]'
        AND handler_key !~ '[[:cntrl:]]'
        AND payload_schema !~ '[[:cntrl:]]'),
    CONSTRAINT inbox_message_lifecycle_consistent CHECK (
        (state = 'PENDING'
          AND lease_owner IS NULL AND lease_until IS NULL
          AND completed_at IS NULL AND dead_at IS NULL)
        OR
        (state = 'PROCESSING'
          AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
          AND completed_at IS NULL AND dead_at IS NULL)
        OR
        (state = 'COMPLETED'
          AND lease_owner IS NULL AND lease_until IS NULL
          AND completed_at IS NOT NULL AND dead_at IS NULL)
        OR
        (state = 'DEAD'
          AND lease_owner IS NULL AND lease_until IS NULL
          AND completed_at IS NULL AND dead_at IS NOT NULL
          AND last_error_code IS NOT NULL))
);

CREATE INDEX inbox_message_ready_idx
    ON public.inbox_message (
        tenant_id, state, available_at, source, source_message_id)
    WHERE state IN ('PENDING', 'PROCESSING');

CREATE TABLE public.external_call_intent (
    tenant_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    root_intent_id uuid NOT NULL,
    predecessor_intent_id uuid,
    attempt_ordinal integer NOT NULL,
    logical_action_key varchar(128) NOT NULL,
    global_idempotency_key varchar(83) GENERATED ALWAYS AS (
        'accord:v1:' || tenant_id::text || ':' || intent_id::text) STORED,
    scope_type varchar(16) NOT NULL,
    scope_id varchar(255) NOT NULL,
    provider varchar(64) NOT NULL,
    provider_installation_id varchar(255) NOT NULL,
    provider_repository_id varchar(255),
    operation varchar(128) NOT NULL,
    request_reference_type varchar(64) NOT NULL,
    request_reference_id varchar(255) NOT NULL,
    request_reference_version bigint NOT NULL,
    request_digest char(71) NOT NULL,
    state varchar(32) NOT NULL DEFAULT 'RECORDED',
    execution_owner varchar(255),
    execution_generation bigint NOT NULL DEFAULT 0,
    execution_token uuid,
    execution_permitted_at timestamptz,
    execution_lease_until timestamptz,
    reconciliation_owner varchar(255),
    reconciliation_generation bigint NOT NULL DEFAULT 0,
    reconciliation_token uuid,
    reconciliation_started_at timestamptz,
    reconciliation_lease_until timestamptz,
    provider_request_id varchar(255),
    outcome_digest char(71),
    last_error_code varchar(128),
    created_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    terminal_at timestamptz,
    CONSTRAINT external_call_intent_pkey PRIMARY KEY (tenant_id, intent_id),
    CONSTRAINT external_call_intent_root_fkey
        FOREIGN KEY (tenant_id, root_intent_id)
        REFERENCES public.external_call_intent (tenant_id, intent_id),
    CONSTRAINT external_call_intent_predecessor_fkey
        FOREIGN KEY (tenant_id, predecessor_intent_id)
        REFERENCES public.external_call_intent (tenant_id, intent_id),
    CONSTRAINT external_call_intent_attempt_ordinal_positive CHECK (attempt_ordinal >= 1),
    CONSTRAINT external_call_intent_logical_action_key_format
        CHECK (logical_action_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$'
          AND logical_action_key !~* '^(https?|ssh|git|file|ftp):'),
    CONSTRAINT external_call_intent_global_idempotency_key_format
        CHECK (global_idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT external_call_intent_scope_type_known
        CHECK (scope_type IN ('tenant', 'project', 'repository')),
    CONSTRAINT external_call_intent_repository_scope_consistent CHECK (
        (scope_type = 'repository') = (provider_repository_id IS NOT NULL)),
    CONSTRAINT external_call_intent_operation_format
        CHECK (operation ~ '^[a-z][a-z0-9_.-]{1,127}$'),
    CONSTRAINT external_call_intent_provider_format
        CHECK (provider ~ '^[a-z][a-z0-9_.-]{1,63}$'),
    CONSTRAINT external_call_intent_reference_type_format
        CHECK (request_reference_type ~ '^[a-z][a-z0-9_.-]{1,63}$'),
    CONSTRAINT external_call_intent_reference_version_positive
        CHECK (request_reference_version >= 1),
    CONSTRAINT external_call_intent_request_digest_format
        CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT external_call_intent_execution_generation_known
        CHECK (execution_generation IN (0, 1)),
    CONSTRAINT external_call_intent_reconciliation_generation_nonnegative
        CHECK (reconciliation_generation >= 0),
    CONSTRAINT external_call_intent_state_known CHECK (state IN (
        'RECORDED', 'EXECUTING', 'OUTCOME_UNKNOWN', 'RECONCILING',
        'SUCCEEDED', 'CONFIRMED_NO_EFFECT', 'DIVERGED')),
    CONSTRAINT external_call_intent_digest_formats CHECK (
        outcome_digest IS NULL OR outcome_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT external_call_intent_error_code_format CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
    CONSTRAINT external_call_intent_safe_identifiers CHECK (
        scope_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$'
        AND provider_installation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$'
        AND (provider_repository_id IS NULL
          OR provider_repository_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$')
        AND request_reference_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$'
        AND (provider_request_id IS NULL
          OR provider_request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$')
        AND (execution_owner IS NULL
          OR execution_owner ~ '^[ -~]{1,255}$')
        AND (reconciliation_owner IS NULL
          OR reconciliation_owner ~ '^[ -~]{1,255}$')),
    CONSTRAINT external_call_intent_execution_shape CHECK (
        (state = 'RECORDED'
          AND execution_generation = 0
          AND execution_owner IS NULL
          AND execution_token IS NULL
          AND execution_permitted_at IS NULL
          AND execution_lease_until IS NULL)
        OR
        (state <> 'RECORDED'
          AND execution_generation = 1
          AND execution_owner IS NOT NULL
          AND execution_token IS NOT NULL
          AND execution_permitted_at IS NOT NULL
          AND execution_lease_until > execution_permitted_at)),
    CONSTRAINT external_call_intent_reconciliation_shape CHECK (
        (state = 'RECONCILING'
          AND reconciliation_generation >= 1
          AND reconciliation_owner IS NOT NULL
          AND reconciliation_token IS NOT NULL
          AND reconciliation_started_at IS NOT NULL
          AND reconciliation_lease_until > reconciliation_started_at)
        OR
        (state <> 'RECONCILING'
          AND reconciliation_owner IS NULL
          AND reconciliation_token IS NULL
          AND reconciliation_started_at IS NULL
          AND reconciliation_lease_until IS NULL)),
    CONSTRAINT external_call_intent_terminal_shape CHECK (
        ((state IN ('SUCCEEDED', 'CONFIRMED_NO_EFFECT', 'DIVERGED'))
          = (terminal_at IS NOT NULL))
        AND
        ((state IN ('SUCCEEDED', 'CONFIRMED_NO_EFFECT', 'DIVERGED'))
          = (outcome_digest IS NOT NULL))),
    CONSTRAINT external_call_intent_error_shape CHECK (
        ((state IN ('OUTCOME_UNKNOWN', 'DIVERGED'))
          = (last_error_code IS NOT NULL))),
    CONSTRAINT external_call_intent_timestamps_ordered CHECK (
        updated_at >= created_at
        AND (execution_permitted_at IS NULL OR execution_permitted_at >= created_at)
        AND (terminal_at IS NULL OR terminal_at >= created_at))
);

CREATE UNIQUE INDEX external_call_intent_global_idempotency_key_uq
    ON public.external_call_intent (global_idempotency_key);
CREATE UNIQUE INDEX external_call_intent_root_action_uq
    ON public.external_call_intent (tenant_id, logical_action_key)
    WHERE predecessor_intent_id IS NULL;
CREATE UNIQUE INDEX external_call_intent_successor_uq
    ON public.external_call_intent (tenant_id, predecessor_intent_id)
    WHERE predecessor_intent_id IS NOT NULL;
CREATE INDEX external_call_intent_ready_idx
    ON public.external_call_intent (tenant_id, state, created_at, intent_id)
    WHERE state = 'RECORDED';
CREATE INDEX external_call_intent_reconcile_idx
    ON public.external_call_intent (tenant_id, state, updated_at, intent_id)
    WHERE state IN ('EXECUTING', 'OUTCOME_UNKNOWN', 'RECONCILING');

CREATE FUNCTION accord_security.reject_reliability_row_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    RAISE EXCEPTION '% is immutable until its fenced worker migration is installed', TG_TABLE_NAME
        USING ERRCODE = '55000';
END
$body$;

CREATE FUNCTION accord_security.guard_external_intent_successor()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    predecessor public.external_call_intent%ROWTYPE;
BEGIN
    IF NEW.state <> 'RECORDED' THEN
        RAISE EXCEPTION 'external intent must be inserted as RECORDED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.predecessor_intent_id IS NULL THEN
        IF NEW.root_intent_id <> NEW.intent_id OR NEW.attempt_ordinal <> 1 THEN
            RAISE EXCEPTION 'invalid external intent root lineage'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO predecessor
    FROM public.external_call_intent
    WHERE tenant_id = NEW.tenant_id
      AND intent_id = NEW.predecessor_intent_id
    FOR UPDATE;
    IF NOT FOUND OR predecessor.state <> 'CONFIRMED_NO_EFFECT' THEN
        RAISE EXCEPTION 'predecessor is not confirmed no effect'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.root_intent_id IS DISTINCT FROM predecessor.root_intent_id
       OR NEW.attempt_ordinal IS DISTINCT FROM predecessor.attempt_ordinal + 1
       OR NEW.logical_action_key IS DISTINCT FROM predecessor.logical_action_key
       OR NEW.scope_type IS DISTINCT FROM predecessor.scope_type
       OR NEW.scope_id IS DISTINCT FROM predecessor.scope_id
       OR NEW.provider IS DISTINCT FROM predecessor.provider
       OR NEW.provider_installation_id IS DISTINCT FROM predecessor.provider_installation_id
       OR NEW.provider_repository_id IS DISTINCT FROM predecessor.provider_repository_id
       OR NEW.operation IS DISTINCT FROM predecessor.operation
       OR NEW.request_reference_type IS DISTINCT FROM predecessor.request_reference_type
       OR NEW.request_reference_id IS DISTINCT FROM predecessor.request_reference_id
       OR NEW.request_reference_version IS DISTINCT FROM predecessor.request_reference_version
       OR NEW.request_digest IS DISTINCT FROM predecessor.request_digest THEN
        RAISE EXCEPTION 'successor definition differs from predecessor'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$body$;

CREATE FUNCTION accord_security.guard_external_intent_transition()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    decision_time timestamptz := pg_catalog.clock_timestamp();
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'external intents are append-only'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.intent_id IS DISTINCT FROM NEW.intent_id
       OR OLD.root_intent_id IS DISTINCT FROM NEW.root_intent_id
       OR OLD.predecessor_intent_id IS DISTINCT FROM NEW.predecessor_intent_id
       OR OLD.attempt_ordinal IS DISTINCT FROM NEW.attempt_ordinal
       OR OLD.logical_action_key IS DISTINCT FROM NEW.logical_action_key
       OR OLD.scope_type IS DISTINCT FROM NEW.scope_type
       OR OLD.scope_id IS DISTINCT FROM NEW.scope_id
       OR OLD.provider IS DISTINCT FROM NEW.provider
       OR OLD.provider_installation_id IS DISTINCT FROM NEW.provider_installation_id
       OR OLD.provider_repository_id IS DISTINCT FROM NEW.provider_repository_id
       OR OLD.operation IS DISTINCT FROM NEW.operation
       OR OLD.request_reference_type IS DISTINCT FROM NEW.request_reference_type
       OR OLD.request_reference_id IS DISTINCT FROM NEW.request_reference_id
       OR OLD.request_reference_version IS DISTINCT FROM NEW.request_reference_version
       OR OLD.request_digest IS DISTINCT FROM NEW.request_digest
       OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'external intent definition is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.state IN ('SUCCEEDED', 'CONFIRMED_NO_EFFECT', 'DIVERGED') THEN
        RAISE EXCEPTION 'terminal external intent is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.provider_request_id IS NOT NULL
       AND OLD.provider_request_id IS DISTINCT FROM NEW.provider_request_id THEN
        RAISE EXCEPTION 'provider request id is immutable once known'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.state = 'RECORDED' AND NEW.state = 'EXECUTING' THEN
        IF NEW.execution_generation <> 1
           OR NEW.execution_owner IS NULL OR NEW.execution_token IS NULL
           OR NEW.execution_permitted_at IS NULL OR NEW.execution_lease_until IS NULL
           OR NEW.reconciliation_generation <> 0 THEN
            RAISE EXCEPTION 'invalid execution permit'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.state = 'EXECUTING' AND NEW.state = 'EXECUTING' THEN
        IF OLD.execution_lease_until <= decision_time
           OR OLD.execution_owner IS DISTINCT FROM NEW.execution_owner
           OR OLD.execution_generation IS DISTINCT FROM NEW.execution_generation
           OR OLD.execution_token IS DISTINCT FROM NEW.execution_token
           OR OLD.execution_permitted_at IS DISTINCT FROM NEW.execution_permitted_at
           OR NEW.execution_lease_until <= OLD.execution_lease_until
           OR OLD.reconciliation_generation IS DISTINCT FROM NEW.reconciliation_generation
           OR OLD.provider_request_id IS DISTINCT FROM NEW.provider_request_id
           OR OLD.outcome_digest IS DISTINCT FROM NEW.outcome_digest
           OR OLD.last_error_code IS DISTINCT FROM NEW.last_error_code
           OR OLD.terminal_at IS DISTINCT FROM NEW.terminal_at THEN
            RAISE EXCEPTION 'invalid execution renewal'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.state = 'EXECUTING'
          AND NEW.state IN ('SUCCEEDED', 'CONFIRMED_NO_EFFECT', 'OUTCOME_UNKNOWN') THEN
        IF OLD.execution_owner IS DISTINCT FROM NEW.execution_owner
           OR OLD.execution_generation IS DISTINCT FROM NEW.execution_generation
           OR OLD.execution_token IS DISTINCT FROM NEW.execution_token
           OR OLD.execution_permitted_at IS DISTINCT FROM NEW.execution_permitted_at
           OR OLD.execution_lease_until IS DISTINCT FROM NEW.execution_lease_until
           OR OLD.reconciliation_generation IS DISTINCT FROM NEW.reconciliation_generation THEN
            RAISE EXCEPTION 'execution fence changed during completion'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.state = 'OUTCOME_UNKNOWN'
           AND NEW.last_error_code = 'EXECUTION_LEASE_EXPIRED' THEN
            IF OLD.execution_lease_until > decision_time
               OR OLD.provider_request_id IS DISTINCT FROM NEW.provider_request_id
               OR NEW.outcome_digest IS NOT NULL
               OR NEW.terminal_at IS NOT NULL THEN
                RAISE EXCEPTION 'invalid execution expiry'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.execution_lease_until <= decision_time THEN
            RAISE EXCEPTION 'expired execution fence cannot complete'
                USING ERRCODE = '55000';
        END IF;
    ELSIF OLD.state = 'OUTCOME_UNKNOWN' AND NEW.state = 'RECONCILING' THEN
        IF OLD.execution_owner IS DISTINCT FROM NEW.execution_owner
           OR OLD.execution_generation IS DISTINCT FROM NEW.execution_generation
           OR OLD.execution_token IS DISTINCT FROM NEW.execution_token
           OR OLD.execution_permitted_at IS DISTINCT FROM NEW.execution_permitted_at
           OR OLD.execution_lease_until IS DISTINCT FROM NEW.execution_lease_until
           OR NEW.reconciliation_generation <> OLD.reconciliation_generation + 1
           OR NEW.reconciliation_owner IS NULL OR NEW.reconciliation_token IS NULL
           OR NEW.reconciliation_started_at IS NULL
           OR NEW.reconciliation_lease_until IS NULL
           OR OLD.provider_request_id IS DISTINCT FROM NEW.provider_request_id THEN
            RAISE EXCEPTION 'invalid reconciliation permit'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.state = 'RECONCILING' AND NEW.state = 'RECONCILING' THEN
        IF OLD.reconciliation_lease_until <= decision_time
           OR OLD.execution_owner IS DISTINCT FROM NEW.execution_owner
           OR OLD.execution_generation IS DISTINCT FROM NEW.execution_generation
           OR OLD.execution_token IS DISTINCT FROM NEW.execution_token
           OR OLD.execution_permitted_at IS DISTINCT FROM NEW.execution_permitted_at
           OR OLD.execution_lease_until IS DISTINCT FROM NEW.execution_lease_until
           OR OLD.reconciliation_owner IS DISTINCT FROM NEW.reconciliation_owner
           OR OLD.reconciliation_generation IS DISTINCT FROM NEW.reconciliation_generation
           OR OLD.reconciliation_token IS DISTINCT FROM NEW.reconciliation_token
           OR OLD.reconciliation_started_at IS DISTINCT FROM NEW.reconciliation_started_at
           OR NEW.reconciliation_lease_until <= OLD.reconciliation_lease_until
           OR OLD.provider_request_id IS DISTINCT FROM NEW.provider_request_id
           OR OLD.outcome_digest IS DISTINCT FROM NEW.outcome_digest
           OR OLD.last_error_code IS DISTINCT FROM NEW.last_error_code
           OR OLD.terminal_at IS DISTINCT FROM NEW.terminal_at THEN
            RAISE EXCEPTION 'invalid reconciliation renewal'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.state = 'RECONCILING'
          AND NEW.state IN (
              'SUCCEEDED', 'CONFIRMED_NO_EFFECT', 'DIVERGED', 'OUTCOME_UNKNOWN') THEN
        IF OLD.execution_owner IS DISTINCT FROM NEW.execution_owner
           OR OLD.execution_generation IS DISTINCT FROM NEW.execution_generation
           OR OLD.execution_token IS DISTINCT FROM NEW.execution_token
           OR OLD.execution_permitted_at IS DISTINCT FROM NEW.execution_permitted_at
           OR OLD.execution_lease_until IS DISTINCT FROM NEW.execution_lease_until
           OR OLD.reconciliation_generation IS DISTINCT FROM NEW.reconciliation_generation
           OR NEW.reconciliation_owner IS NOT NULL
           OR NEW.reconciliation_token IS NOT NULL
           OR NEW.reconciliation_started_at IS NOT NULL
           OR NEW.reconciliation_lease_until IS NOT NULL THEN
            RAISE EXCEPTION 'reconciliation fence changed during completion'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.state = 'OUTCOME_UNKNOWN'
           AND NEW.last_error_code = 'RECONCILIATION_LEASE_EXPIRED' THEN
            IF OLD.reconciliation_lease_until > decision_time
               OR OLD.provider_request_id IS DISTINCT FROM NEW.provider_request_id
               OR NEW.outcome_digest IS NOT NULL
               OR NEW.terminal_at IS NOT NULL THEN
                RAISE EXCEPTION 'invalid reconciliation expiry'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.reconciliation_lease_until <= decision_time THEN
            RAISE EXCEPTION 'expired reconciliation fence cannot complete'
                USING ERRCODE = '55000';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid external intent transition: % -> %', OLD.state, NEW.state
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$body$;

REVOKE ALL ON FUNCTION accord_security.reject_reliability_row_change()
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.guard_external_intent_successor()
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.guard_external_intent_transition()
    FROM PUBLIC, accord_api, accord_worker;

CREATE TRIGGER domain_event_append_only
BEFORE UPDATE OR DELETE ON public.domain_event
FOR EACH ROW EXECUTE FUNCTION accord_security.reject_reliability_row_change();
CREATE TRIGGER outbox_v002_immutable
BEFORE UPDATE OR DELETE ON public.outbox_event
FOR EACH ROW EXECUTE FUNCTION accord_security.reject_reliability_row_change();
CREATE TRIGGER inbox_v002_immutable
BEFORE UPDATE OR DELETE ON public.inbox_message
FOR EACH ROW EXECUTE FUNCTION accord_security.reject_reliability_row_change();
CREATE TRIGGER external_intent_successor_guard
BEFORE INSERT ON public.external_call_intent
FOR EACH ROW EXECUTE FUNCTION accord_security.guard_external_intent_successor();
CREATE TRIGGER external_intent_transition_guard
BEFORE UPDATE OR DELETE ON public.external_call_intent
FOR EACH ROW EXECUTE FUNCTION accord_security.guard_external_intent_transition();

SELECT accord_security.enforce_tenant_table(
    'public.domain_event'::pg_catalog.regclass);
SELECT accord_security.enforce_tenant_table(
    'public.outbox_event'::pg_catalog.regclass);
SELECT accord_security.enforce_tenant_table(
    'public.inbox_message'::pg_catalog.regclass);
SELECT accord_security.enforce_tenant_table(
    'public.external_call_intent'::pg_catalog.regclass);

CREATE FUNCTION accord_security.append_reliable_event(
    p_tenant_id uuid,
    p_event_id uuid,
    p_scope_type varchar,
    p_scope_id varchar,
    p_aggregate_type varchar,
    p_aggregate_id uuid,
    p_sequence bigint,
    p_event_type varchar,
    p_schema_version varchar,
    p_causation_id uuid,
    p_correlation_id uuid,
    p_actor_id varchar,
    p_event_payload jsonb,
    p_occurred_at timestamptz,
    p_destination varchar,
    p_outbox_schema varchar,
    p_outbox_payload jsonb
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'reliable event tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.domain_event (
        tenant_id,event_id,scope_type,scope_id,aggregate_type,aggregate_id,
        sequence,event_type,schema_version,causation_id,correlation_id,
        actor_id,payload,occurred_at)
    VALUES (
        p_tenant_id,p_event_id,p_scope_type,p_scope_id,p_aggregate_type,
        p_aggregate_id,p_sequence,p_event_type,p_schema_version,p_causation_id,
        p_correlation_id,p_actor_id,p_event_payload,p_occurred_at);
    INSERT INTO public.outbox_event (
        tenant_id,event_id,destination,payload_schema,payload)
    VALUES (
        p_tenant_id,p_event_id,p_destination,p_outbox_schema,p_outbox_payload);
END
$body$;

CREATE FUNCTION accord_security.accept_inbox_message(
    p_tenant_id uuid,
    p_source varchar,
    p_source_message_id varchar,
    p_request_digest char(71),
    p_handler_key varchar,
    p_payload_schema varchar,
    p_payload jsonb
)
RETURNS TABLE(disposition text, stored_digest text, stored_state text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    inserted_count integer;
    existing public.inbox_message%ROWTYPE;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'inbox tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.inbox_message (
        tenant_id,source,source_message_id,request_digest,handler_key,
        payload_schema,payload)
    VALUES (
        p_tenant_id,p_source,p_source_message_id,p_request_digest,p_handler_key,
        p_payload_schema,p_payload)
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS inserted_count = ROW_COUNT;
    IF inserted_count = 1 THEN
        RETURN QUERY VALUES (
            'ACCEPTED'::text,p_request_digest::text,'PENDING'::text);
        RETURN;
    END IF;

    SELECT * INTO existing
    FROM public.inbox_message AS message
    WHERE message.tenant_id=p_tenant_id
      AND message.source=p_source
      AND message.source_message_id=p_source_message_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'inbox conflict row disappeared'
            USING ERRCODE = '55000';
    END IF;
    IF existing.request_digest = p_request_digest THEN
        RETURN QUERY VALUES (
            'DUPLICATE'::text,existing.request_digest::text,existing.state::text);
    ELSE
        RETURN QUERY VALUES (
            'DIGEST_CONFLICT'::text,existing.request_digest::text,
            existing.state::text);
    END IF;
END
$body$;

CREATE FUNCTION accord_security.record_external_intent(
    p_tenant_id uuid,
    p_intent_id uuid,
    p_logical_action_key varchar,
    p_scope_type varchar,
    p_scope_id varchar,
    p_provider varchar,
    p_provider_installation_id varchar,
    p_provider_repository_id varchar,
    p_operation varchar,
    p_request_reference_type varchar,
    p_request_reference_id varchar,
    p_request_reference_version bigint,
    p_request_digest char(71)
)
RETURNS TABLE(
    disposition text,
    result_tenant_id uuid,
    result_intent_id uuid,
    result_root_intent_id uuid,
    result_attempt_ordinal integer,
    result_global_idempotency_key varchar,
    result_state varchar
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    inserted_count integer;
    existing public.external_call_intent%ROWTYPE;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'external intent tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.external_call_intent (
        tenant_id,intent_id,root_intent_id,predecessor_intent_id,
        attempt_ordinal,logical_action_key,scope_type,scope_id,provider,
        provider_installation_id,provider_repository_id,operation,
        request_reference_type,request_reference_id,
        request_reference_version,request_digest,state)
    VALUES (
        p_tenant_id,p_intent_id,p_intent_id,NULL,1,p_logical_action_key,
        p_scope_type,p_scope_id,p_provider,p_provider_installation_id,
        p_provider_repository_id,p_operation,p_request_reference_type,
        p_request_reference_id,p_request_reference_version,p_request_digest,
        'RECORDED')
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS inserted_count = ROW_COUNT;

    SELECT * INTO existing
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id
      AND intent.logical_action_key=p_logical_action_key
      AND intent.predecessor_intent_id IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'external intent conflict row disappeared'
            USING ERRCODE = '55000';
    END IF;
    IF existing.scope_type IS DISTINCT FROM p_scope_type
       OR existing.scope_id IS DISTINCT FROM p_scope_id
       OR existing.provider IS DISTINCT FROM p_provider
       OR existing.provider_installation_id IS DISTINCT FROM p_provider_installation_id
       OR existing.provider_repository_id IS DISTINCT FROM p_provider_repository_id
       OR existing.operation IS DISTINCT FROM p_operation
       OR existing.request_reference_type IS DISTINCT FROM p_request_reference_type
       OR existing.request_reference_id IS DISTINCT FROM p_request_reference_id
       OR existing.request_reference_version IS DISTINCT FROM p_request_reference_version
       OR existing.request_digest IS DISTINCT FROM p_request_digest THEN
        RETURN QUERY VALUES (
            'CONFLICT'::text,NULL::uuid,NULL::uuid,NULL::uuid,NULL::integer,
            NULL::varchar,NULL::varchar);
        RETURN;
    END IF;
    RETURN QUERY VALUES (
        CASE WHEN inserted_count=1 THEN 'CREATED' ELSE 'DUPLICATE' END::text,
        existing.tenant_id,existing.intent_id,existing.root_intent_id,
        existing.attempt_ordinal,existing.global_idempotency_key,existing.state);
END
$body$;

CREATE FUNCTION accord_security.load_external_intent_snapshot(
    p_tenant_id uuid,
    p_intent_id uuid
)
RETURNS TABLE(
    tenant_id uuid,
    intent_id uuid,
    root_intent_id uuid,
    predecessor_intent_id uuid,
    attempt_ordinal integer,
    scope_type varchar,
    scope_id varchar,
    logical_action_key varchar,
    global_idempotency_key varchar,
    state varchar,
    execution_generation bigint,
    reconciliation_generation bigint,
    provider_request_id varchar,
    outcome_digest char(71),
    last_error_code varchar,
    created_at timestamptz,
    updated_at timestamptz,
    terminal_at timestamptz
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'external intent snapshot tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
    SELECT intent.tenant_id,intent.intent_id,intent.root_intent_id,
           intent.predecessor_intent_id,intent.attempt_ordinal,
           intent.scope_type,intent.scope_id,intent.logical_action_key,
           intent.global_idempotency_key,intent.state,intent.execution_generation,
           intent.reconciliation_generation,intent.provider_request_id,
           intent.outcome_digest,intent.last_error_code,intent.created_at,
           intent.updated_at,intent.terminal_at
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id;
END
$body$;

CREATE FUNCTION accord_security.create_external_intent_successor(
    p_tenant_id uuid,
    p_predecessor_intent_id uuid,
    p_successor_intent_id uuid
)
RETURNS TABLE(
    result_tenant_id uuid,
    result_intent_id uuid,
    result_root_intent_id uuid,
    result_attempt_ordinal integer,
    result_global_idempotency_key varchar,
    result_state varchar
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    predecessor public.external_call_intent%ROWTYPE;
    successor public.external_call_intent%ROWTYPE;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'external successor tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    SELECT * INTO predecessor
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id
      AND intent.intent_id=p_predecessor_intent_id
    FOR UPDATE;
    IF NOT FOUND OR predecessor.state <> 'CONFIRMED_NO_EFFECT' THEN
        RAISE EXCEPTION 'predecessor is not confirmed no effect'
            USING ERRCODE = '55000';
    END IF;

    SELECT * INTO successor
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id
      AND intent.predecessor_intent_id=p_predecessor_intent_id;
    IF NOT FOUND THEN
        INSERT INTO public.external_call_intent (
            tenant_id,intent_id,root_intent_id,predecessor_intent_id,
            attempt_ordinal,logical_action_key,scope_type,scope_id,provider,
            provider_installation_id,provider_repository_id,operation,
            request_reference_type,request_reference_id,
            request_reference_version,request_digest,state)
        VALUES (
            predecessor.tenant_id,p_successor_intent_id,predecessor.root_intent_id,
            predecessor.intent_id,predecessor.attempt_ordinal+1,
            predecessor.logical_action_key,predecessor.scope_type,
            predecessor.scope_id,predecessor.provider,
            predecessor.provider_installation_id,
            predecessor.provider_repository_id,predecessor.operation,
            predecessor.request_reference_type,predecessor.request_reference_id,
            predecessor.request_reference_version,predecessor.request_digest,
            'RECORDED')
        RETURNING * INTO successor;
    END IF;
    RETURN QUERY VALUES (
        successor.tenant_id,successor.intent_id,successor.root_intent_id,
        successor.attempt_ordinal,successor.global_idempotency_key,
        successor.state);
END
$body$;

CREATE FUNCTION accord_security.claim_external_intent_execution(
    p_tenant_id uuid,
    p_intent_id uuid,
    p_owner varchar,
    p_lease_micros bigint,
    p_token uuid
)
RETURNS TABLE(
    disposition text,
    state varchar,
    tenant_id uuid,
    intent_id uuid,
    execution_owner varchar,
    execution_generation bigint,
    execution_token uuid,
    execution_lease_until timestamptz,
    global_idempotency_key varchar,
    provider varchar,
    provider_installation_id varchar,
    provider_repository_id varchar,
    operation varchar,
    request_reference_type varchar,
    request_reference_id varchar,
    request_reference_version bigint,
    request_digest char(71)
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'execution claim tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    IF p_lease_micros < 1 OR p_lease_micros > 300000000 THEN
        RAISE EXCEPTION 'execution lease is outside the approved range'
            USING ERRCODE = '22023';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF current_intent.state <> 'RECORDED' THEN
        RETURN QUERY VALUES (
            'NOT_EXECUTABLE'::text,current_intent.state::varchar,
            NULL::uuid,NULL::uuid,NULL::varchar,NULL::bigint,NULL::uuid,
            NULL::timestamptz,NULL::varchar,NULL::varchar,NULL::varchar,
            NULL::varchar,NULL::varchar,NULL::varchar,NULL::varchar,
            NULL::bigint,NULL::char(71));
        RETURN;
    END IF;
    decision_time := pg_catalog.clock_timestamp();
    UPDATE public.external_call_intent AS intent
    SET state='EXECUTING',execution_owner=p_owner,execution_generation=1,
        execution_token=p_token,execution_permitted_at=decision_time,
        execution_lease_until=decision_time
          + p_lease_micros * INTERVAL '1 microsecond'
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='RECORDED' AND intent.execution_generation=0
    RETURNING intent.* INTO current_intent;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'execution permit was not acquired'
            USING ERRCODE = '55000';
    END IF;
    RETURN QUERY VALUES (
        'ACQUIRED'::text,current_intent.state::varchar,
        current_intent.tenant_id,current_intent.intent_id,
        current_intent.execution_owner,current_intent.execution_generation,
        current_intent.execution_token,current_intent.execution_lease_until,
        current_intent.global_idempotency_key,current_intent.provider,
        current_intent.provider_installation_id,
        current_intent.provider_repository_id,current_intent.operation,
        current_intent.request_reference_type,current_intent.request_reference_id,
        current_intent.request_reference_version,current_intent.request_digest);
END
$body$;

CREATE FUNCTION accord_security.renew_external_intent_execution(
    p_tenant_id uuid,
    p_intent_id uuid,
    p_owner varchar,
    p_generation bigint,
    p_token uuid,
    p_exact_deadline timestamptz,
    p_global_idempotency_key varchar,
    p_provider varchar,
    p_provider_installation_id varchar,
    p_provider_repository_id varchar,
    p_operation varchar,
    p_request_reference_type varchar,
    p_request_reference_id varchar,
    p_request_reference_version bigint,
    p_request_digest char(71),
    p_extension_micros bigint
)
RETURNS TABLE(execution_lease_until timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'execution renewal tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    IF p_extension_micros < 1 OR p_extension_micros > 300000000 THEN
        RAISE EXCEPTION 'execution extension is outside the approved range'
            USING ERRCODE = '22023';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    decision_time := pg_catalog.clock_timestamp();
    IF NOT FOUND
       OR current_intent.state <> 'EXECUTING'
       OR current_intent.execution_owner IS DISTINCT FROM p_owner
       OR current_intent.execution_generation IS DISTINCT FROM p_generation
       OR current_intent.execution_token IS DISTINCT FROM p_token
       OR current_intent.execution_lease_until IS DISTINCT FROM p_exact_deadline
       OR current_intent.execution_lease_until <= decision_time
       OR current_intent.global_idempotency_key IS DISTINCT FROM p_global_idempotency_key
       OR current_intent.provider IS DISTINCT FROM p_provider
       OR current_intent.provider_installation_id IS DISTINCT FROM p_provider_installation_id
       OR current_intent.provider_repository_id IS DISTINCT FROM p_provider_repository_id
       OR current_intent.operation IS DISTINCT FROM p_operation
       OR current_intent.request_reference_type IS DISTINCT FROM p_request_reference_type
       OR current_intent.request_reference_id IS DISTINCT FROM p_request_reference_id
       OR current_intent.request_reference_version IS DISTINCT FROM p_request_reference_version
       OR current_intent.request_digest IS DISTINCT FROM p_request_digest THEN
        RAISE EXCEPTION 'execution renewal fence was lost'
            USING ERRCODE = '55000';
    END IF;
    UPDATE public.external_call_intent AS intent
    SET execution_lease_until=p_exact_deadline
          + p_extension_micros * INTERVAL '1 microsecond'
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='EXECUTING'
      AND intent.execution_owner=p_owner
      AND intent.execution_generation=p_generation
      AND intent.execution_token=p_token
      AND intent.execution_lease_until=p_exact_deadline
    RETURNING intent.* INTO current_intent;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'execution renewal fence changed'
            USING ERRCODE = '55000';
    END IF;
    RETURN QUERY VALUES (current_intent.execution_lease_until);
END
$body$;

CREATE FUNCTION accord_security.mark_external_intent_execution_unknown(
    p_tenant_id uuid,
    p_intent_id uuid,
    p_owner varchar,
    p_generation bigint,
    p_token uuid,
    p_exact_deadline timestamptz,
    p_global_idempotency_key varchar,
    p_provider varchar,
    p_provider_installation_id varchar,
    p_provider_repository_id varchar,
    p_operation varchar,
    p_request_reference_type varchar,
    p_request_reference_id varchar,
    p_request_reference_version bigint,
    p_request_digest char(71),
    p_error_code varchar,
    p_provider_request_id varchar
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'execution completion tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    decision_time := pg_catalog.clock_timestamp();
    IF NOT FOUND
       OR current_intent.state <> 'EXECUTING'
       OR current_intent.execution_owner IS DISTINCT FROM p_owner
       OR current_intent.execution_generation IS DISTINCT FROM p_generation
       OR current_intent.execution_token IS DISTINCT FROM p_token
       OR current_intent.execution_lease_until IS DISTINCT FROM p_exact_deadline
       OR current_intent.execution_lease_until <= decision_time
       OR current_intent.global_idempotency_key IS DISTINCT FROM p_global_idempotency_key
       OR current_intent.provider IS DISTINCT FROM p_provider
       OR current_intent.provider_installation_id IS DISTINCT FROM p_provider_installation_id
       OR current_intent.provider_repository_id IS DISTINCT FROM p_provider_repository_id
       OR current_intent.operation IS DISTINCT FROM p_operation
       OR current_intent.request_reference_type IS DISTINCT FROM p_request_reference_type
       OR current_intent.request_reference_id IS DISTINCT FROM p_request_reference_id
       OR current_intent.request_reference_version IS DISTINCT FROM p_request_reference_version
       OR current_intent.request_digest IS DISTINCT FROM p_request_digest
       OR (current_intent.provider_request_id IS NOT NULL
         AND p_provider_request_id IS NOT NULL
         AND current_intent.provider_request_id IS DISTINCT FROM p_provider_request_id) THEN
        RAISE EXCEPTION 'execution completion fence was lost'
            USING ERRCODE = '55000';
    END IF;
    UPDATE public.external_call_intent AS intent
    SET state='OUTCOME_UNKNOWN',
        provider_request_id=COALESCE(intent.provider_request_id,p_provider_request_id),
        outcome_digest=NULL,last_error_code=p_error_code,terminal_at=NULL
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='EXECUTING'
      AND intent.execution_owner=p_owner
      AND intent.execution_generation=p_generation
      AND intent.execution_token=p_token
      AND intent.execution_lease_until=p_exact_deadline;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'execution completion fence changed'
            USING ERRCODE = '55000';
    END IF;
END
$body$;

CREATE FUNCTION accord_security.expire_external_intent_execution(
    p_tenant_id uuid,
    p_intent_id uuid
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'execution expiry tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    IF NOT FOUND OR current_intent.state <> 'EXECUTING' THEN
        RETURN false;
    END IF;
    decision_time := pg_catalog.clock_timestamp();
    IF current_intent.execution_lease_until > decision_time THEN
        RETURN false;
    END IF;
    UPDATE public.external_call_intent AS intent
    SET state='OUTCOME_UNKNOWN',last_error_code='EXECUTION_LEASE_EXPIRED'
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='EXECUTING'
      AND intent.execution_owner=current_intent.execution_owner
      AND intent.execution_generation=current_intent.execution_generation
      AND intent.execution_token=current_intent.execution_token
      AND intent.execution_lease_until=current_intent.execution_lease_until;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'execution expiry fence changed'
            USING ERRCODE = '55000';
    END IF;
    RETURN true;
END
$body$;

CREATE FUNCTION accord_security.complete_external_intent_execution(
    p_tenant_id uuid,
    p_intent_id uuid,
    p_owner varchar,
    p_generation bigint,
    p_token uuid,
    p_exact_deadline timestamptz,
    p_global_idempotency_key varchar,
    p_provider varchar,
    p_provider_installation_id varchar,
    p_provider_repository_id varchar,
    p_operation varchar,
    p_request_reference_type varchar,
    p_request_reference_id varchar,
    p_request_reference_version bigint,
    p_request_digest char(71),
    p_terminal_state varchar,
    p_outcome_digest char(71),
    p_provider_request_id varchar,
    p_event_tenant_id uuid,
    p_event_id uuid,
    p_event_scope_type varchar,
    p_event_scope_id varchar,
    p_event_aggregate_type varchar,
    p_event_aggregate_id uuid,
    p_event_sequence bigint,
    p_event_type varchar,
    p_event_schema_version varchar,
    p_event_causation_id uuid,
    p_event_correlation_id uuid,
    p_event_actor_id varchar,
    p_event_payload jsonb,
    p_event_occurred_at timestamptz,
    p_destination varchar,
    p_outbox_schema varchar,
    p_outbox_payload jsonb
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
    changed_count integer;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id()
       OR p_event_tenant_id IS DISTINCT FROM p_tenant_id THEN
        RAISE EXCEPTION 'terminal execution tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    IF p_terminal_state NOT IN ('SUCCEEDED','CONFIRMED_NO_EFFECT') THEN
        RAISE EXCEPTION 'invalid execution terminal state'
            USING ERRCODE = '22023';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    decision_time := pg_catalog.clock_timestamp();
    IF NOT FOUND
       OR current_intent.state <> 'EXECUTING'
       OR current_intent.execution_owner IS DISTINCT FROM p_owner
       OR current_intent.execution_generation IS DISTINCT FROM p_generation
       OR current_intent.execution_token IS DISTINCT FROM p_token
       OR current_intent.execution_lease_until IS DISTINCT FROM p_exact_deadline
       OR current_intent.execution_lease_until <= decision_time
       OR current_intent.global_idempotency_key IS DISTINCT FROM p_global_idempotency_key
       OR current_intent.provider IS DISTINCT FROM p_provider
       OR current_intent.provider_installation_id IS DISTINCT FROM p_provider_installation_id
       OR current_intent.provider_repository_id IS DISTINCT FROM p_provider_repository_id
       OR current_intent.operation IS DISTINCT FROM p_operation
       OR current_intent.request_reference_type IS DISTINCT FROM p_request_reference_type
       OR current_intent.request_reference_id IS DISTINCT FROM p_request_reference_id
       OR current_intent.request_reference_version IS DISTINCT FROM p_request_reference_version
       OR current_intent.request_digest IS DISTINCT FROM p_request_digest
       OR (current_intent.provider_request_id IS NOT NULL
         AND p_provider_request_id IS NOT NULL
         AND current_intent.provider_request_id IS DISTINCT FROM p_provider_request_id) THEN
        RAISE EXCEPTION 'execution terminal fence was lost'
            USING ERRCODE = '55000';
    END IF;
    IF p_event_scope_type IS DISTINCT FROM current_intent.scope_type
       OR p_event_scope_id IS DISTINCT FROM current_intent.scope_id
       OR p_event_aggregate_type IS DISTINCT FROM 'external_intent'
       OR p_event_aggregate_id IS DISTINCT FROM p_intent_id THEN
        RAISE EXCEPTION 'terminal event does not bind the external intent'
            USING ERRCODE = '22023';
    END IF;

    UPDATE public.external_call_intent AS intent
    SET state=p_terminal_state,
        provider_request_id=COALESCE(intent.provider_request_id,p_provider_request_id),
        outcome_digest=p_outcome_digest,last_error_code=NULL,
        terminal_at=decision_time
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='EXECUTING'
      AND intent.execution_owner=p_owner
      AND intent.execution_generation=p_generation
      AND intent.execution_token=p_token
      AND intent.execution_lease_until=p_exact_deadline;
    GET DIAGNOSTICS changed_count = ROW_COUNT;
    IF changed_count <> 1 THEN
        RAISE EXCEPTION 'execution terminal fence changed'
            USING ERRCODE = '55000';
    END IF;
    INSERT INTO public.domain_event (
        tenant_id,event_id,scope_type,scope_id,aggregate_type,aggregate_id,
        sequence,event_type,schema_version,causation_id,correlation_id,
        actor_id,payload,occurred_at)
    VALUES (
        p_event_tenant_id,p_event_id,p_event_scope_type,p_event_scope_id,
        p_event_aggregate_type,p_event_aggregate_id,p_event_sequence,p_event_type,
        p_event_schema_version,p_event_causation_id,p_event_correlation_id,
        p_event_actor_id,p_event_payload,p_event_occurred_at);
    INSERT INTO public.outbox_event (
        tenant_id,event_id,destination,payload_schema,payload)
    VALUES (
        p_event_tenant_id,p_event_id,p_destination,p_outbox_schema,
        p_outbox_payload);
END
$body$;

CREATE FUNCTION accord_security.claim_external_intent_reconciliation(
    p_tenant_id uuid,
    p_intent_id uuid,
    p_owner varchar,
    p_lease_micros bigint,
    p_token uuid
)
RETURNS TABLE(
    disposition text,
    state varchar,
    tenant_id uuid,
    intent_id uuid,
    reconciliation_owner varchar,
    reconciliation_generation bigint,
    reconciliation_token uuid,
    reconciliation_lease_until timestamptz,
    global_idempotency_key varchar,
    provider varchar,
    provider_installation_id varchar,
    provider_repository_id varchar,
    operation varchar,
    request_reference_type varchar,
    request_reference_id varchar,
    request_reference_version bigint,
    request_digest char(71),
    provider_request_id varchar
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
    next_generation bigint;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'reconciliation claim tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    IF p_lease_micros < 1 OR p_lease_micros > 300000000 THEN
        RAISE EXCEPTION 'reconciliation lease is outside the approved range'
            USING ERRCODE = '22023';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    decision_time := pg_catalog.clock_timestamp();
    IF current_intent.state='RECONCILING'
       AND current_intent.reconciliation_lease_until <= decision_time THEN
        UPDATE public.external_call_intent AS intent
        SET state='OUTCOME_UNKNOWN',reconciliation_owner=NULL,
            reconciliation_token=NULL,reconciliation_started_at=NULL,
            reconciliation_lease_until=NULL,
            last_error_code='RECONCILIATION_LEASE_EXPIRED'
        WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
          AND intent.state='RECONCILING'
          AND intent.reconciliation_owner=current_intent.reconciliation_owner
          AND intent.reconciliation_generation=current_intent.reconciliation_generation
          AND intent.reconciliation_token=current_intent.reconciliation_token
          AND intent.reconciliation_lease_until=current_intent.reconciliation_lease_until
        RETURNING intent.* INTO current_intent;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'reconciliation expiry fence changed'
                USING ERRCODE = '55000';
        END IF;
    END IF;
    IF current_intent.state <> 'OUTCOME_UNKNOWN' THEN
        RETURN QUERY VALUES (
            'NOT_RECONCILABLE'::text,current_intent.state::varchar,
            NULL::uuid,NULL::uuid,NULL::varchar,NULL::bigint,NULL::uuid,
            NULL::timestamptz,NULL::varchar,NULL::varchar,NULL::varchar,
            NULL::varchar,NULL::varchar,NULL::varchar,NULL::varchar,
            NULL::bigint,NULL::char(71),NULL::varchar);
        RETURN;
    END IF;
    next_generation := current_intent.reconciliation_generation + 1;
    decision_time := pg_catalog.clock_timestamp();
    UPDATE public.external_call_intent AS intent
    SET state='RECONCILING',reconciliation_owner=p_owner,
        reconciliation_generation=next_generation,reconciliation_token=p_token,
        reconciliation_started_at=decision_time,
        reconciliation_lease_until=decision_time
          + p_lease_micros * INTERVAL '1 microsecond',
        last_error_code=NULL
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='OUTCOME_UNKNOWN'
      AND intent.reconciliation_generation=next_generation-1
    RETURNING intent.* INTO current_intent;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'reconciliation permit was not acquired'
            USING ERRCODE = '55000';
    END IF;
    RETURN QUERY VALUES (
        'ACQUIRED'::text,current_intent.state::varchar,
        current_intent.tenant_id,current_intent.intent_id,
        current_intent.reconciliation_owner,
        current_intent.reconciliation_generation,
        current_intent.reconciliation_token,
        current_intent.reconciliation_lease_until,
        current_intent.global_idempotency_key,current_intent.provider,
        current_intent.provider_installation_id,
        current_intent.provider_repository_id,current_intent.operation,
        current_intent.request_reference_type,current_intent.request_reference_id,
        current_intent.request_reference_version,current_intent.request_digest,
        current_intent.provider_request_id);
END
$body$;

CREATE FUNCTION accord_security.renew_external_intent_reconciliation(
    p_tenant_id uuid,
    p_intent_id uuid,
    p_owner varchar,
    p_generation bigint,
    p_token uuid,
    p_exact_deadline timestamptz,
    p_global_idempotency_key varchar,
    p_provider varchar,
    p_provider_installation_id varchar,
    p_provider_repository_id varchar,
    p_operation varchar,
    p_request_reference_type varchar,
    p_request_reference_id varchar,
    p_request_reference_version bigint,
    p_request_digest char(71),
    p_provider_request_id varchar,
    p_extension_micros bigint
)
RETURNS TABLE(reconciliation_lease_until timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'reconciliation renewal tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    IF p_extension_micros < 1 OR p_extension_micros > 300000000 THEN
        RAISE EXCEPTION 'reconciliation extension is outside the approved range'
            USING ERRCODE = '22023';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    decision_time := pg_catalog.clock_timestamp();
    IF NOT FOUND
       OR current_intent.state <> 'RECONCILING'
       OR current_intent.reconciliation_owner IS DISTINCT FROM p_owner
       OR current_intent.reconciliation_generation IS DISTINCT FROM p_generation
       OR current_intent.reconciliation_token IS DISTINCT FROM p_token
       OR current_intent.reconciliation_lease_until IS DISTINCT FROM p_exact_deadline
       OR current_intent.reconciliation_lease_until <= decision_time
       OR current_intent.global_idempotency_key IS DISTINCT FROM p_global_idempotency_key
       OR current_intent.provider IS DISTINCT FROM p_provider
       OR current_intent.provider_installation_id IS DISTINCT FROM p_provider_installation_id
       OR current_intent.provider_repository_id IS DISTINCT FROM p_provider_repository_id
       OR current_intent.operation IS DISTINCT FROM p_operation
       OR current_intent.request_reference_type IS DISTINCT FROM p_request_reference_type
       OR current_intent.request_reference_id IS DISTINCT FROM p_request_reference_id
       OR current_intent.request_reference_version IS DISTINCT FROM p_request_reference_version
       OR current_intent.request_digest IS DISTINCT FROM p_request_digest
       OR current_intent.provider_request_id IS DISTINCT FROM p_provider_request_id THEN
        RAISE EXCEPTION 'reconciliation renewal fence was lost'
            USING ERRCODE = '55000';
    END IF;
    UPDATE public.external_call_intent AS intent
    SET reconciliation_lease_until=p_exact_deadline
          + p_extension_micros * INTERVAL '1 microsecond'
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='RECONCILING'
      AND intent.reconciliation_owner=p_owner
      AND intent.reconciliation_generation=p_generation
      AND intent.reconciliation_token=p_token
      AND intent.reconciliation_lease_until=p_exact_deadline
    RETURNING intent.* INTO current_intent;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'reconciliation renewal fence changed'
            USING ERRCODE = '55000';
    END IF;
    RETURN QUERY VALUES (current_intent.reconciliation_lease_until);
END
$body$;

CREATE FUNCTION accord_security.mark_external_intent_reconciliation_unknown(
    p_tenant_id uuid,
    p_intent_id uuid,
    p_owner varchar,
    p_generation bigint,
    p_token uuid,
    p_exact_deadline timestamptz,
    p_global_idempotency_key varchar,
    p_provider varchar,
    p_provider_installation_id varchar,
    p_provider_repository_id varchar,
    p_operation varchar,
    p_request_reference_type varchar,
    p_request_reference_id varchar,
    p_request_reference_version bigint,
    p_request_digest char(71),
    p_known_provider_request_id varchar,
    p_error_code varchar,
    p_provider_request_id varchar
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'reconciliation completion tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    decision_time := pg_catalog.clock_timestamp();
    IF NOT FOUND
       OR current_intent.state <> 'RECONCILING'
       OR current_intent.reconciliation_owner IS DISTINCT FROM p_owner
       OR current_intent.reconciliation_generation IS DISTINCT FROM p_generation
       OR current_intent.reconciliation_token IS DISTINCT FROM p_token
       OR current_intent.reconciliation_lease_until IS DISTINCT FROM p_exact_deadline
       OR current_intent.reconciliation_lease_until <= decision_time
       OR current_intent.global_idempotency_key IS DISTINCT FROM p_global_idempotency_key
       OR current_intent.provider IS DISTINCT FROM p_provider
       OR current_intent.provider_installation_id IS DISTINCT FROM p_provider_installation_id
       OR current_intent.provider_repository_id IS DISTINCT FROM p_provider_repository_id
       OR current_intent.operation IS DISTINCT FROM p_operation
       OR current_intent.request_reference_type IS DISTINCT FROM p_request_reference_type
       OR current_intent.request_reference_id IS DISTINCT FROM p_request_reference_id
       OR current_intent.request_reference_version IS DISTINCT FROM p_request_reference_version
       OR current_intent.request_digest IS DISTINCT FROM p_request_digest
       OR current_intent.provider_request_id IS DISTINCT FROM p_known_provider_request_id
       OR (current_intent.provider_request_id IS NOT NULL
         AND p_provider_request_id IS NOT NULL
         AND current_intent.provider_request_id IS DISTINCT FROM p_provider_request_id) THEN
        RAISE EXCEPTION 'reconciliation completion fence was lost'
            USING ERRCODE = '55000';
    END IF;
    UPDATE public.external_call_intent AS intent
    SET state='OUTCOME_UNKNOWN',reconciliation_owner=NULL,
        reconciliation_token=NULL,reconciliation_started_at=NULL,
        reconciliation_lease_until=NULL,
        provider_request_id=COALESCE(intent.provider_request_id,p_provider_request_id),
        outcome_digest=NULL,last_error_code=p_error_code,terminal_at=NULL
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='RECONCILING'
      AND intent.reconciliation_owner=p_owner
      AND intent.reconciliation_generation=p_generation
      AND intent.reconciliation_token=p_token
      AND intent.reconciliation_lease_until=p_exact_deadline;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'reconciliation completion fence changed'
            USING ERRCODE = '55000';
    END IF;
END
$body$;

CREATE FUNCTION accord_security.expire_external_intent_reconciliation(
    p_tenant_id uuid,
    p_intent_id uuid
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'reconciliation expiry tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    IF NOT FOUND OR current_intent.state <> 'RECONCILING' THEN
        RETURN false;
    END IF;
    decision_time := pg_catalog.clock_timestamp();
    IF current_intent.reconciliation_lease_until > decision_time THEN
        RETURN false;
    END IF;
    UPDATE public.external_call_intent AS intent
    SET state='OUTCOME_UNKNOWN',reconciliation_owner=NULL,
        reconciliation_token=NULL,reconciliation_started_at=NULL,
        reconciliation_lease_until=NULL,
        last_error_code='RECONCILIATION_LEASE_EXPIRED'
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='RECONCILING'
      AND intent.reconciliation_owner=current_intent.reconciliation_owner
      AND intent.reconciliation_generation=current_intent.reconciliation_generation
      AND intent.reconciliation_token=current_intent.reconciliation_token
      AND intent.reconciliation_lease_until=current_intent.reconciliation_lease_until;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'reconciliation expiry fence changed'
            USING ERRCODE = '55000';
    END IF;
    RETURN true;
END
$body$;

CREATE FUNCTION accord_security.complete_external_intent_reconciliation(
    p_tenant_id uuid,
    p_intent_id uuid,
    p_owner varchar,
    p_generation bigint,
    p_token uuid,
    p_exact_deadline timestamptz,
    p_global_idempotency_key varchar,
    p_provider varchar,
    p_provider_installation_id varchar,
    p_provider_repository_id varchar,
    p_operation varchar,
    p_request_reference_type varchar,
    p_request_reference_id varchar,
    p_request_reference_version bigint,
    p_request_digest char(71),
    p_known_provider_request_id varchar,
    p_terminal_state varchar,
    p_outcome_digest char(71),
    p_error_code varchar,
    p_provider_request_id varchar,
    p_event_tenant_id uuid,
    p_event_id uuid,
    p_event_scope_type varchar,
    p_event_scope_id varchar,
    p_event_aggregate_type varchar,
    p_event_aggregate_id uuid,
    p_event_sequence bigint,
    p_event_type varchar,
    p_event_schema_version varchar,
    p_event_causation_id uuid,
    p_event_correlation_id uuid,
    p_event_actor_id varchar,
    p_event_payload jsonb,
    p_event_occurred_at timestamptz,
    p_destination varchar,
    p_outbox_schema varchar,
    p_outbox_payload jsonb
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_intent public.external_call_intent%ROWTYPE;
    decision_time timestamptz;
    changed_count integer;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id()
       OR p_event_tenant_id IS DISTINCT FROM p_tenant_id THEN
        RAISE EXCEPTION 'terminal reconciliation tenant context mismatch'
            USING ERRCODE = '42501';
    END IF;
    IF p_terminal_state NOT IN ('SUCCEEDED','CONFIRMED_NO_EFFECT','DIVERGED')
       OR ((p_terminal_state='DIVERGED') IS DISTINCT FROM (p_error_code IS NOT NULL)) THEN
        RAISE EXCEPTION 'invalid reconciliation terminal state'
            USING ERRCODE = '22023';
    END IF;
    SELECT * INTO current_intent
    FROM public.external_call_intent AS intent
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
    FOR UPDATE;
    decision_time := pg_catalog.clock_timestamp();
    IF NOT FOUND
       OR current_intent.state <> 'RECONCILING'
       OR current_intent.reconciliation_owner IS DISTINCT FROM p_owner
       OR current_intent.reconciliation_generation IS DISTINCT FROM p_generation
       OR current_intent.reconciliation_token IS DISTINCT FROM p_token
       OR current_intent.reconciliation_lease_until IS DISTINCT FROM p_exact_deadline
       OR current_intent.reconciliation_lease_until <= decision_time
       OR current_intent.global_idempotency_key IS DISTINCT FROM p_global_idempotency_key
       OR current_intent.provider IS DISTINCT FROM p_provider
       OR current_intent.provider_installation_id IS DISTINCT FROM p_provider_installation_id
       OR current_intent.provider_repository_id IS DISTINCT FROM p_provider_repository_id
       OR current_intent.operation IS DISTINCT FROM p_operation
       OR current_intent.request_reference_type IS DISTINCT FROM p_request_reference_type
       OR current_intent.request_reference_id IS DISTINCT FROM p_request_reference_id
       OR current_intent.request_reference_version IS DISTINCT FROM p_request_reference_version
       OR current_intent.request_digest IS DISTINCT FROM p_request_digest
       OR current_intent.provider_request_id IS DISTINCT FROM p_known_provider_request_id
       OR (current_intent.provider_request_id IS NOT NULL
         AND p_provider_request_id IS NOT NULL
         AND current_intent.provider_request_id IS DISTINCT FROM p_provider_request_id) THEN
        RAISE EXCEPTION 'reconciliation terminal fence was lost'
            USING ERRCODE = '55000';
    END IF;
    IF p_event_scope_type IS DISTINCT FROM current_intent.scope_type
       OR p_event_scope_id IS DISTINCT FROM current_intent.scope_id
       OR p_event_aggregate_type IS DISTINCT FROM 'external_intent'
       OR p_event_aggregate_id IS DISTINCT FROM p_intent_id THEN
        RAISE EXCEPTION 'terminal event does not bind the external intent'
            USING ERRCODE = '22023';
    END IF;

    UPDATE public.external_call_intent AS intent
    SET state=p_terminal_state,reconciliation_owner=NULL,
        reconciliation_token=NULL,reconciliation_started_at=NULL,
        reconciliation_lease_until=NULL,
        provider_request_id=COALESCE(intent.provider_request_id,p_provider_request_id),
        outcome_digest=p_outcome_digest,last_error_code=p_error_code,
        terminal_at=decision_time
    WHERE intent.tenant_id=p_tenant_id AND intent.intent_id=p_intent_id
      AND intent.state='RECONCILING'
      AND intent.reconciliation_owner=p_owner
      AND intent.reconciliation_generation=p_generation
      AND intent.reconciliation_token=p_token
      AND intent.reconciliation_lease_until=p_exact_deadline;
    GET DIAGNOSTICS changed_count = ROW_COUNT;
    IF changed_count <> 1 THEN
        RAISE EXCEPTION 'reconciliation terminal fence changed'
            USING ERRCODE = '55000';
    END IF;
    INSERT INTO public.domain_event (
        tenant_id,event_id,scope_type,scope_id,aggregate_type,aggregate_id,
        sequence,event_type,schema_version,causation_id,correlation_id,
        actor_id,payload,occurred_at)
    VALUES (
        p_event_tenant_id,p_event_id,p_event_scope_type,p_event_scope_id,
        p_event_aggregate_type,p_event_aggregate_id,p_event_sequence,p_event_type,
        p_event_schema_version,p_event_causation_id,p_event_correlation_id,
        p_event_actor_id,p_event_payload,p_event_occurred_at);
    INSERT INTO public.outbox_event (
        tenant_id,event_id,destination,payload_schema,payload)
    VALUES (
        p_event_tenant_id,p_event_id,p_destination,p_outbox_schema,
        p_outbox_payload);
END
$body$;

REVOKE ALL ON public.domain_event, public.outbox_event,
    public.inbox_message, public.external_call_intent
    FROM PUBLIC, accord_api, accord_worker;
GRANT SELECT ON public.domain_event, public.outbox_event
    TO accord_api, accord_worker;
GRANT SELECT ON public.inbox_message TO accord_worker;

REVOKE ALL ON FUNCTION accord_security.append_reliable_event
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.accept_inbox_message
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.record_external_intent
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.load_external_intent_snapshot
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.create_external_intent_successor
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.claim_external_intent_execution
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.renew_external_intent_execution
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.mark_external_intent_execution_unknown
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.expire_external_intent_execution
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.complete_external_intent_execution
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.claim_external_intent_reconciliation
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.renew_external_intent_reconciliation
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.mark_external_intent_reconciliation_unknown
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.expire_external_intent_reconciliation
    FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_security.complete_external_intent_reconciliation
    FROM PUBLIC, accord_api, accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.append_reliable_event
    TO accord_api, accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.accept_inbox_message
    TO accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.record_external_intent
    TO accord_api;
GRANT EXECUTE ON FUNCTION accord_security.load_external_intent_snapshot
    TO accord_api, accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.create_external_intent_successor
    TO accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.claim_external_intent_execution,
    accord_security.renew_external_intent_execution,
    accord_security.mark_external_intent_execution_unknown,
    accord_security.expire_external_intent_execution,
    accord_security.complete_external_intent_execution,
    accord_security.claim_external_intent_reconciliation,
    accord_security.renew_external_intent_reconciliation,
    accord_security.mark_external_intent_reconciliation_unknown,
    accord_security.expire_external_intent_reconciliation,
    accord_security.complete_external_intent_reconciliation
    TO accord_worker;
