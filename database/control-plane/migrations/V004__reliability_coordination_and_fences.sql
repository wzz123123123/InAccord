SET lock_timeout = '5s';
SET statement_timeout = '30s';

DO $body$
BEGIN
    IF pg_catalog.to_regclass('public.contract_validation') IS NULL THEN
        RAISE EXCEPTION 'V004 requires V003 contract_validation';
    END IF;
END
$body$;

ALTER TABLE public.outbox_event
    ADD COLUMN lease_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN lease_token uuid,
    ADD CONSTRAINT outbox_event_lease_generation_nonnegative
        CHECK (lease_generation >= 0),
    ADD CONSTRAINT outbox_event_complete_fence CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
        OR
        (lease_owner IS NOT NULL AND lease_generation >= 1
          AND lease_token IS NOT NULL AND lease_until IS NOT NULL));

ALTER TABLE public.inbox_message
    ADD COLUMN lease_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN lease_token uuid,
    ADD CONSTRAINT inbox_message_lease_generation_nonnegative
        CHECK (lease_generation >= 0),
    ADD CONSTRAINT inbox_message_complete_fence CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
        OR
        (lease_owner IS NOT NULL AND lease_generation >= 1
          AND lease_token IS NOT NULL AND lease_until IS NOT NULL));

CREATE TABLE public.reliability_tenant_work (
    tenant_id uuid NOT NULL,
    available_at timestamptz NOT NULL,
    last_granted_at timestamptz,
    permit_owner varchar(255),
    permit_generation bigint NOT NULL DEFAULT 0,
    permit_token uuid,
    permit_lease_until timestamptz,
    CONSTRAINT reliability_tenant_work_pkey PRIMARY KEY (tenant_id),
    CONSTRAINT reliability_tenant_work_generation_nonnegative
        CHECK (permit_generation >= 0),
    CONSTRAINT reliability_tenant_work_complete_fence CHECK (
        (permit_owner IS NULL AND permit_token IS NULL
          AND permit_lease_until IS NULL)
        OR
        (permit_owner IS NOT NULL AND permit_generation >= 1
          AND permit_token IS NOT NULL AND permit_lease_until IS NOT NULL)),
    CONSTRAINT reliability_tenant_work_owner_bounded CHECK (
        permit_owner IS NULL OR (
          pg_catalog.octet_length(permit_owner) BETWEEN 1 AND 255
          AND permit_owner !~ '[[:cntrl:]]'))
);

CREATE INDEX reliability_tenant_work_fair_idx
    ON public.reliability_tenant_work
       (last_granted_at NULLS FIRST, available_at, tenant_id);

CREATE TABLE public.outbox_delivery_receipt (
    tenant_id uuid NOT NULL,
    event_id uuid NOT NULL,
    permit_owner varchar(255) NOT NULL,
    permit_generation bigint NOT NULL,
    permit_token uuid NOT NULL,
    permit_lease_until timestamptz NOT NULL,
    message_owner varchar(255) NOT NULL,
    message_generation bigint NOT NULL,
    message_token uuid NOT NULL,
    message_lease_until timestamptz NOT NULL,
    receipt_id varchar(255) NOT NULL,
    receipt_digest char(71) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    CONSTRAINT outbox_delivery_receipt_pkey PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT outbox_delivery_receipt_message_fkey
        FOREIGN KEY (tenant_id, event_id)
        REFERENCES public.outbox_event (tenant_id, event_id),
    CONSTRAINT outbox_delivery_receipt_generations_positive CHECK (
        permit_generation >= 1 AND message_generation >= 1),
    CONSTRAINT outbox_delivery_receipt_id_bounded CHECK (
        receipt_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$'
          AND receipt_id !~* '^(https?|ssh|git|file|ftp):'
          AND receipt_id !~* '(authorization|credential|secret|password|cookie|session|csrf|bearer|basic|token|glpat|ghp|whsec|private|pem)'),
    CONSTRAINT outbox_delivery_receipt_digest_format
        CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE public.inbox_handler_receipt (
    tenant_id uuid NOT NULL,
    source varchar(128) NOT NULL,
    source_message_id varchar(255) NOT NULL,
    permit_owner varchar(255) NOT NULL,
    permit_generation bigint NOT NULL,
    permit_token uuid NOT NULL,
    permit_lease_until timestamptz NOT NULL,
    message_owner varchar(255) NOT NULL,
    message_generation bigint NOT NULL,
    message_token uuid NOT NULL,
    message_lease_until timestamptz NOT NULL,
    receipt_id varchar(255) NOT NULL,
    receipt_digest char(71) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    CONSTRAINT inbox_handler_receipt_pkey
        PRIMARY KEY (tenant_id, source, source_message_id),
    CONSTRAINT inbox_handler_receipt_message_fkey
        FOREIGN KEY (tenant_id, source, source_message_id)
        REFERENCES public.inbox_message (tenant_id, source, source_message_id),
    CONSTRAINT inbox_handler_receipt_generations_positive CHECK (
        permit_generation >= 1 AND message_generation >= 1),
    CONSTRAINT inbox_handler_receipt_id_bounded CHECK (
        receipt_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$'
          AND receipt_id !~* '^(https?|ssh|git|file|ftp):'
          AND receipt_id !~* '(authorization|credential|secret|password|cookie|session|csrf|bearer|basic|token|glpat|ghp|whsec|private|pem)'),
    CONSTRAINT inbox_handler_receipt_digest_format
        CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$')
);

DROP TRIGGER outbox_v002_immutable ON public.outbox_event;
DROP TRIGGER inbox_v002_immutable ON public.inbox_message;

CREATE FUNCTION accord_security.guard_outbox_v004_transition()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    decision_time timestamptz := pg_catalog.clock_timestamp();
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'outbox rows are append-only' USING ERRCODE = '23514';
    END IF;
    IF OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.event_id IS DISTINCT FROM NEW.event_id
       OR OLD.destination IS DISTINCT FROM NEW.destination
       OR OLD.payload_schema IS DISTINCT FROM NEW.payload_schema
       OR OLD.payload IS DISTINCT FROM NEW.payload
       OR OLD.redrive_count IS DISTINCT FROM NEW.redrive_count THEN
        RAISE EXCEPTION 'outbox immutable columns changed' USING ERRCODE = '23514';
    END IF;

    IF OLD.state = 'PENDING' AND NEW.state = 'DELIVERING' THEN
        IF OLD.available_at > decision_time
           OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count + 1
           OR NEW.lease_generation IS DISTINCT FROM OLD.lease_generation + 1
           OR NEW.lease_owner IS NULL OR NEW.lease_token IS NULL
           OR NEW.lease_until <= decision_time
           OR NEW.delivered_at IS NOT NULL OR NEW.dead_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid outbox lease' USING ERRCODE = '55000';
        END IF;
    ELSIF OLD.state = 'DELIVERING' AND NEW.state = 'DELIVERING'
          AND OLD.lease_until <= decision_time THEN
        IF NEW.attempt_count IS DISTINCT FROM OLD.attempt_count + 1
           OR NEW.lease_generation IS DISTINCT FROM OLD.lease_generation + 1
           OR NEW.lease_owner IS NULL OR NEW.lease_token IS NULL
           OR NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token
           OR NEW.lease_until <= decision_time THEN
            RAISE EXCEPTION 'invalid outbox takeover' USING ERRCODE = '55000';
        END IF;
    ELSIF OLD.state = 'DELIVERING' AND NEW.state = 'DELIVERING' THEN
        IF OLD.lease_until <= decision_time
           OR NEW.lease_owner IS DISTINCT FROM OLD.lease_owner
           OR NEW.lease_generation IS DISTINCT FROM OLD.lease_generation
           OR NEW.lease_token IS DISTINCT FROM OLD.lease_token
           OR NEW.lease_until <= OLD.lease_until
           OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count THEN
            RAISE EXCEPTION 'invalid outbox renewal' USING ERRCODE = '55000';
        END IF;
    ELSIF OLD.state = 'DELIVERING' AND NEW.state IN ('PENDING','DEAD','DELIVERED') THEN
        IF OLD.lease_until <= decision_time
           OR NEW.lease_owner IS NOT NULL OR NEW.lease_token IS NOT NULL
           OR NEW.lease_until IS NOT NULL
           OR NEW.lease_generation IS DISTINCT FROM OLD.lease_generation
           OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count
           OR (NEW.state = 'PENDING' AND (
                NEW.available_at <= decision_time OR NEW.delivered_at IS NOT NULL
                OR NEW.dead_at IS NOT NULL))
           OR (NEW.state = 'DEAD' AND (
                NEW.dead_at IS NULL OR NEW.delivered_at IS NOT NULL
                OR NEW.last_error_code IS NULL))
           OR (NEW.state = 'DELIVERED' AND (
                NEW.delivered_at IS NULL OR NEW.dead_at IS NOT NULL
                OR NOT EXISTS (
                    SELECT 1 FROM public.outbox_delivery_receipt receipt
                    WHERE receipt.tenant_id=OLD.tenant_id
                      AND receipt.event_id=OLD.event_id))) THEN
            RAISE EXCEPTION 'invalid outbox completion' USING ERRCODE = '55000';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid outbox state transition' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$body$;

CREATE FUNCTION accord_security.guard_inbox_v004_transition()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    decision_time timestamptz := pg_catalog.clock_timestamp();
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'inbox rows are append-only' USING ERRCODE = '23514';
    END IF;
    IF OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.source IS DISTINCT FROM NEW.source
       OR OLD.source_message_id IS DISTINCT FROM NEW.source_message_id
       OR OLD.request_digest IS DISTINCT FROM NEW.request_digest
       OR OLD.handler_key IS DISTINCT FROM NEW.handler_key
       OR OLD.payload_schema IS DISTINCT FROM NEW.payload_schema
       OR OLD.payload IS DISTINCT FROM NEW.payload
       OR OLD.received_at IS DISTINCT FROM NEW.received_at
       OR OLD.redrive_count IS DISTINCT FROM NEW.redrive_count THEN
        RAISE EXCEPTION 'inbox immutable columns changed' USING ERRCODE = '23514';
    END IF;

    IF OLD.state = 'PENDING' AND NEW.state = 'PROCESSING' THEN
        IF OLD.available_at > decision_time
           OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count + 1
           OR NEW.lease_generation IS DISTINCT FROM OLD.lease_generation + 1
           OR NEW.lease_owner IS NULL OR NEW.lease_token IS NULL
           OR NEW.lease_until <= decision_time THEN
            RAISE EXCEPTION 'invalid inbox lease' USING ERRCODE = '55000';
        END IF;
    ELSIF OLD.state = 'PROCESSING' AND NEW.state = 'PROCESSING'
          AND OLD.lease_until <= decision_time THEN
        IF NEW.attempt_count IS DISTINCT FROM OLD.attempt_count + 1
           OR NEW.lease_generation IS DISTINCT FROM OLD.lease_generation + 1
           OR NEW.lease_owner IS NULL OR NEW.lease_token IS NULL
           OR NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token
           OR NEW.lease_until <= decision_time THEN
            RAISE EXCEPTION 'invalid inbox takeover' USING ERRCODE = '55000';
        END IF;
    ELSIF OLD.state = 'PROCESSING' AND NEW.state = 'PROCESSING' THEN
        IF OLD.lease_until <= decision_time
           OR NEW.lease_owner IS DISTINCT FROM OLD.lease_owner
           OR NEW.lease_generation IS DISTINCT FROM OLD.lease_generation
           OR NEW.lease_token IS DISTINCT FROM OLD.lease_token
           OR NEW.lease_until <= OLD.lease_until
           OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count THEN
            RAISE EXCEPTION 'invalid inbox renewal' USING ERRCODE = '55000';
        END IF;
    ELSIF OLD.state = 'PROCESSING' AND NEW.state IN ('PENDING','DEAD','COMPLETED') THEN
        IF OLD.lease_until <= decision_time
           OR NEW.lease_owner IS NOT NULL OR NEW.lease_token IS NOT NULL
           OR NEW.lease_until IS NOT NULL
           OR NEW.lease_generation IS DISTINCT FROM OLD.lease_generation
           OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count
           OR (NEW.state = 'PENDING' AND (
                NEW.available_at <= decision_time OR NEW.completed_at IS NOT NULL
                OR NEW.dead_at IS NOT NULL))
           OR (NEW.state = 'DEAD' AND (
                NEW.dead_at IS NULL OR NEW.completed_at IS NOT NULL
                OR NEW.last_error_code IS NULL))
           OR (NEW.state = 'COMPLETED' AND (
                NEW.completed_at IS NULL OR NEW.dead_at IS NOT NULL
                OR NOT EXISTS (
                    SELECT 1 FROM public.inbox_handler_receipt receipt
                    WHERE receipt.tenant_id=OLD.tenant_id
                      AND receipt.source=OLD.source
                      AND receipt.source_message_id=OLD.source_message_id))) THEN
            RAISE EXCEPTION 'invalid inbox completion' USING ERRCODE = '55000';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid inbox state transition' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$body$;

CREATE TRIGGER outbox_v004_transition
BEFORE UPDATE OR DELETE ON public.outbox_event
FOR EACH ROW EXECUTE FUNCTION accord_security.guard_outbox_v004_transition();
CREATE TRIGGER inbox_v004_transition
BEFORE UPDATE OR DELETE ON public.inbox_message
FOR EACH ROW EXECUTE FUNCTION accord_security.guard_inbox_v004_transition();

CREATE FUNCTION accord_security.signal_reliability_tenant_work()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    tenant uuid := accord_security.current_tenant_id();
    earliest timestamptz;
BEGIN
    IF tenant IS NULL THEN
        RAISE EXCEPTION 'tenant context is required' USING ERRCODE = '42501';
    END IF;
    SELECT MIN(candidate.available_at) INTO earliest
    FROM (
      SELECT message.available_at
      FROM public.outbox_event message
      WHERE message.tenant_id=tenant AND message.state='PENDING'
      UNION ALL
      SELECT message.lease_until
      FROM public.outbox_event message
      WHERE message.tenant_id=tenant AND message.state='DELIVERING'
      UNION ALL
      SELECT message.available_at
      FROM public.inbox_message message
      WHERE message.tenant_id=tenant AND message.state='PENDING'
      UNION ALL
      SELECT message.lease_until
      FROM public.inbox_message message
      WHERE message.tenant_id=tenant AND message.state='PROCESSING'
      UNION ALL
      SELECT result.expires_at
      FROM public.idempotency_result result
      WHERE result.tenant_id=tenant AND result.state='COMPLETED'
    ) candidate;
    INSERT INTO public.reliability_tenant_work (tenant_id,available_at)
    VALUES (tenant,COALESCE(earliest,pg_catalog.clock_timestamp()))
    ON CONFLICT (tenant_id) DO UPDATE
      SET available_at=LEAST(
        public.reliability_tenant_work.available_at,EXCLUDED.available_at);
END
$body$;

CREATE FUNCTION accord_security.acquire_reliability_tenant_work(
    p_owner varchar,
    p_lease_micros bigint
)
RETURNS TABLE(
    tenant_id uuid,
    permit_owner varchar,
    permit_generation bigint,
    permit_token uuid,
    permit_lease_until timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    IF p_owner IS NULL OR pg_catalog.octet_length(p_owner) NOT BETWEEN 1 AND 255
       OR p_owner ~ '[[:cntrl:]]'
       OR p_lease_micros NOT BETWEEN 1 AND 3600000000 THEN
        RAISE EXCEPTION 'invalid tenant permit request' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    WITH decision AS (
      SELECT pg_catalog.clock_timestamp() AS at
    ), candidate AS (
      SELECT work.tenant_id
      FROM public.reliability_tenant_work work, decision
      WHERE (work.available_at <= decision.at
          OR (work.permit_lease_until IS NOT NULL
            AND work.permit_lease_until <= decision.at))
        AND (work.permit_lease_until IS NULL OR work.permit_lease_until <= decision.at)
      ORDER BY work.last_granted_at NULLS FIRST,work.available_at,work.tenant_id
      FOR UPDATE OF work SKIP LOCKED
      LIMIT 1
    )
    UPDATE public.reliability_tenant_work work
    SET last_granted_at=decision.at,
        available_at='infinity'::pg_catalog.timestamptz,
        permit_owner=p_owner,
        permit_generation=work.permit_generation+1,
        permit_token=pg_catalog.gen_random_uuid(),
        permit_lease_until=decision.at
          + p_lease_micros * INTERVAL '1 microsecond'
    FROM candidate,decision
    WHERE work.tenant_id=candidate.tenant_id
    RETURNING work.tenant_id,work.permit_owner,work.permit_generation,
              work.permit_token,work.permit_lease_until;
END
$body$;

CREATE FUNCTION accord_security.lock_reliability_tenant_work_permit(
    p_tenant_id uuid,
    p_owner varchar,
    p_generation bigint,
    p_token uuid,
    p_exact_deadline timestamptz
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    current_permit public.reliability_tenant_work%ROWTYPE;
BEGIN
    IF p_tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'tenant permit context mismatch' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO current_permit
    FROM public.reliability_tenant_work work
    WHERE work.tenant_id=p_tenant_id
    FOR UPDATE;
    IF NOT FOUND
       OR current_permit.permit_owner IS DISTINCT FROM p_owner
       OR current_permit.permit_generation IS DISTINCT FROM p_generation
       OR current_permit.permit_token IS DISTINCT FROM p_token
       OR current_permit.permit_lease_until IS DISTINCT FROM p_exact_deadline
       OR current_permit.permit_lease_until <= pg_catalog.clock_timestamp() THEN
        RAISE EXCEPTION 'tenant permit fence was lost' USING ERRCODE = '55000';
    END IF;
END
$body$;

CREATE FUNCTION accord_security.renew_reliability_tenant_work(
    p_tenant_id uuid,
    p_owner varchar,
    p_generation bigint,
    p_token uuid,
    p_exact_deadline timestamptz,
    p_extension_micros bigint
)
RETURNS TABLE(permit_lease_until timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    IF accord_security.current_tenant_id() IS NOT NULL
       OR p_extension_micros NOT BETWEEN 1 AND 3600000000 THEN
        RAISE EXCEPTION 'invalid tenant permit renewal' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    UPDATE public.reliability_tenant_work work
    SET permit_lease_until=p_exact_deadline
          + p_extension_micros * INTERVAL '1 microsecond'
    WHERE work.tenant_id=p_tenant_id
      AND work.permit_owner=p_owner
      AND work.permit_generation=p_generation
      AND work.permit_token=p_token
      AND work.permit_lease_until=p_exact_deadline
      AND work.permit_lease_until > pg_catalog.clock_timestamp()
    RETURNING work.permit_lease_until;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'tenant permit renewal fence was lost' USING ERRCODE = '55000';
    END IF;
END
$body$;

CREATE FUNCTION accord_security.finish_reliability_tenant_work(
    p_tenant_id uuid,
    p_owner varchar,
    p_generation bigint,
    p_token uuid,
    p_exact_deadline timestamptz,
    p_available_at timestamptz
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    IF accord_security.current_tenant_id() IS NOT NULL OR p_available_at IS NULL THEN
        RAISE EXCEPTION 'invalid tenant permit finish' USING ERRCODE = '22023';
    END IF;
    UPDATE public.reliability_tenant_work work
    SET available_at=LEAST(work.available_at,p_available_at),
        last_granted_at=pg_catalog.clock_timestamp(),
        permit_owner=NULL,permit_token=NULL,permit_lease_until=NULL
    WHERE work.tenant_id=p_tenant_id
      AND work.permit_owner=p_owner
      AND work.permit_generation=p_generation
      AND work.permit_token=p_token
      AND work.permit_lease_until=p_exact_deadline
      AND work.permit_lease_until > pg_catalog.clock_timestamp();
    IF NOT FOUND THEN
        RAISE EXCEPTION 'tenant permit finish fence was lost' USING ERRCODE = '55000';
    END IF;
END
$body$;

CREATE FUNCTION accord_security.guard_outbox_delivery_receipt()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE current_message public.outbox_event%ROWTYPE;
BEGIN
    PERFORM accord_security.lock_reliability_tenant_work_permit(
      NEW.tenant_id,NEW.permit_owner,NEW.permit_generation,
      NEW.permit_token,NEW.permit_lease_until);
    SELECT * INTO current_message FROM public.outbox_event message
    WHERE message.tenant_id=NEW.tenant_id AND message.event_id=NEW.event_id
    FOR UPDATE;
    IF NOT FOUND OR current_message.state <> 'DELIVERING'
       OR current_message.lease_owner IS DISTINCT FROM NEW.message_owner
       OR current_message.lease_generation IS DISTINCT FROM NEW.message_generation
       OR current_message.lease_token IS DISTINCT FROM NEW.message_token
       OR current_message.lease_until IS DISTINCT FROM NEW.message_lease_until
       OR current_message.lease_until <= pg_catalog.clock_timestamp() THEN
        RAISE EXCEPTION 'outbox receipt message fence was lost' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$body$;

CREATE FUNCTION accord_security.guard_inbox_handler_receipt()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE current_message public.inbox_message%ROWTYPE;
BEGIN
    PERFORM accord_security.lock_reliability_tenant_work_permit(
      NEW.tenant_id,NEW.permit_owner,NEW.permit_generation,
      NEW.permit_token,NEW.permit_lease_until);
    SELECT * INTO current_message FROM public.inbox_message message
    WHERE message.tenant_id=NEW.tenant_id AND message.source=NEW.source
      AND message.source_message_id=NEW.source_message_id
    FOR UPDATE;
    IF NOT FOUND OR current_message.state <> 'PROCESSING'
       OR current_message.lease_owner IS DISTINCT FROM NEW.message_owner
       OR current_message.lease_generation IS DISTINCT FROM NEW.message_generation
       OR current_message.lease_token IS DISTINCT FROM NEW.message_token
       OR current_message.lease_until IS DISTINCT FROM NEW.message_lease_until
       OR current_message.lease_until <= pg_catalog.clock_timestamp() THEN
        RAISE EXCEPTION 'inbox receipt message fence was lost' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$body$;

CREATE FUNCTION accord_security.reject_receipt_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    RAISE EXCEPTION 'reliability receipts are append-only' USING ERRCODE = '23514';
END
$body$;

CREATE TRIGGER outbox_delivery_receipt_guard
BEFORE INSERT ON public.outbox_delivery_receipt
FOR EACH ROW EXECUTE FUNCTION accord_security.guard_outbox_delivery_receipt();
CREATE TRIGGER inbox_handler_receipt_guard
BEFORE INSERT ON public.inbox_handler_receipt
FOR EACH ROW EXECUTE FUNCTION accord_security.guard_inbox_handler_receipt();
CREATE TRIGGER outbox_delivery_receipt_append_only
BEFORE UPDATE OR DELETE ON public.outbox_delivery_receipt
FOR EACH ROW EXECUTE FUNCTION accord_security.reject_receipt_change();
CREATE TRIGGER inbox_handler_receipt_append_only
BEFORE UPDATE OR DELETE ON public.inbox_handler_receipt
FOR EACH ROW EXECUTE FUNCTION accord_security.reject_receipt_change();

CREATE FUNCTION accord_security.signal_outbox_tenant_work()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'outbox signal tenant mismatch' USING ERRCODE = '42501';
    END IF;
    PERFORM accord_security.signal_reliability_tenant_work();
    RETURN NEW;
END
$body$;

CREATE FUNCTION accord_security.signal_inbox_tenant_work()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'inbox signal tenant mismatch' USING ERRCODE = '42501';
    END IF;
    PERFORM accord_security.signal_reliability_tenant_work();
    RETURN NEW;
END
$body$;

CREATE FUNCTION accord_security.signal_completed_idempotency_work()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM accord_security.current_tenant_id() THEN
        RAISE EXCEPTION 'idempotency signal tenant mismatch' USING ERRCODE = '42501';
    END IF;
    IF NEW.state='COMPLETED'
       AND (TG_OP='INSERT' OR OLD.state IS DISTINCT FROM 'COMPLETED'
         OR OLD.expires_at IS DISTINCT FROM NEW.expires_at) THEN
        PERFORM accord_security.signal_reliability_tenant_work();
    END IF;
    RETURN NEW;
END
$body$;

CREATE TRIGGER outbox_v004_signal
AFTER INSERT ON public.outbox_event
FOR EACH ROW EXECUTE FUNCTION accord_security.signal_outbox_tenant_work();
CREATE TRIGGER inbox_v004_signal
AFTER INSERT ON public.inbox_message
FOR EACH ROW EXECUTE FUNCTION accord_security.signal_inbox_tenant_work();
CREATE TRIGGER idempotency_v004_completed_signal
AFTER INSERT OR UPDATE ON public.idempotency_result
FOR EACH ROW EXECUTE FUNCTION accord_security.signal_completed_idempotency_work();

CREATE OR REPLACE FUNCTION accord_security.accept_inbox_message(
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
        RAISE EXCEPTION 'inbox tenant context mismatch' USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.reliability_tenant_work (tenant_id,available_at)
    VALUES (p_tenant_id,'infinity'::pg_catalog.timestamptz)
    ON CONFLICT (tenant_id) DO NOTHING;
    PERFORM 1 FROM public.reliability_tenant_work work
    WHERE work.tenant_id=p_tenant_id FOR UPDATE;

    INSERT INTO public.inbox_message (
        tenant_id,source,source_message_id,request_digest,handler_key,
        payload_schema,payload)
    VALUES (
        p_tenant_id,p_source,p_source_message_id,p_request_digest,p_handler_key,
        p_payload_schema,p_payload)
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS inserted_count = ROW_COUNT;
    IF inserted_count = 1 THEN
        RETURN QUERY VALUES ('ACCEPTED'::text,p_request_digest::text,'PENDING'::text);
        RETURN;
    END IF;
    SELECT * INTO existing FROM public.inbox_message message
    WHERE message.tenant_id=p_tenant_id AND message.source=p_source
      AND message.source_message_id=p_source_message_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'inbox conflict row disappeared' USING ERRCODE = '55000';
    END IF;
    IF existing.state IN ('PENDING','PROCESSING') THEN
        UPDATE public.reliability_tenant_work work
        SET available_at=LEAST(
          work.available_at,
          CASE WHEN existing.state='PROCESSING'
               THEN existing.lease_until ELSE existing.available_at END)
        WHERE work.tenant_id=p_tenant_id;
    END IF;
    IF existing.request_digest=p_request_digest THEN
        RETURN QUERY VALUES ('DUPLICATE'::text,existing.request_digest::text,
                             existing.state::text);
    ELSE
        RETURN QUERY VALUES ('DIGEST_CONFLICT'::text,existing.request_digest::text,
                             existing.state::text);
    END IF;
END
$body$;

-- The migrator owns these FORCE-RLS tables but intentionally has no BYPASSRLS.
-- Relax FORCE only for the set-based legacy snapshot, then restore it before
-- any runtime grants are applied. A migration failure rolls the whole change back.
ALTER TABLE public.outbox_event NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.inbox_message NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.idempotency_result NO FORCE ROW LEVEL SECURITY;

INSERT INTO public.reliability_tenant_work (tenant_id,available_at)
SELECT tenant_id,MIN(available_at) FROM public.outbox_event
WHERE state='PENDING' GROUP BY tenant_id
ON CONFLICT (tenant_id) DO UPDATE SET available_at=LEAST(
  public.reliability_tenant_work.available_at,EXCLUDED.available_at);
INSERT INTO public.reliability_tenant_work (tenant_id,available_at)
SELECT tenant_id,MIN(available_at) FROM public.inbox_message
WHERE state='PENDING' GROUP BY tenant_id
ON CONFLICT (tenant_id) DO UPDATE SET available_at=LEAST(
  public.reliability_tenant_work.available_at,EXCLUDED.available_at);
INSERT INTO public.reliability_tenant_work (tenant_id,available_at)
SELECT tenant_id,MIN(expires_at) FROM public.idempotency_result
WHERE state='COMPLETED' GROUP BY tenant_id
ON CONFLICT (tenant_id) DO UPDATE SET available_at=LEAST(
  public.reliability_tenant_work.available_at,EXCLUDED.available_at);

ALTER TABLE public.outbox_event FORCE ROW LEVEL SECURITY;
ALTER TABLE public.inbox_message FORCE ROW LEVEL SECURITY;
ALTER TABLE public.idempotency_result FORCE ROW LEVEL SECURITY;

SELECT accord_security.enforce_tenant_table(
    'public.outbox_delivery_receipt'::pg_catalog.regclass);
SELECT accord_security.enforce_tenant_table(
    'public.inbox_handler_receipt'::pg_catalog.regclass);

CREATE FUNCTION accord_security.delete_expired_completed_idempotency_results(
    batch_size integer
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE deleted_count integer;
BEGIN
    IF batch_size NOT BETWEEN 1 AND 500 THEN
        RAISE EXCEPTION 'cleanup batch size must be between 1 and 500'
            USING ERRCODE = '22023';
    END IF;
    WITH candidates AS (
      SELECT result.tenant_id,result.actor_id,result.route_key,result.idempotency_key
      FROM public.idempotency_result result
      WHERE result.tenant_id=accord_security.current_tenant_id()
        AND result.state='COMPLETED'
        AND result.expires_at < pg_catalog.clock_timestamp()
      ORDER BY result.expires_at,result.tenant_id,result.actor_id,
               result.route_key,result.idempotency_key
      FOR UPDATE SKIP LOCKED
      LIMIT batch_size
    )
    DELETE FROM public.idempotency_result result USING candidates
    WHERE result.tenant_id=candidates.tenant_id
      AND result.actor_id=candidates.actor_id
      AND result.route_key=candidates.route_key
      AND result.idempotency_key=candidates.idempotency_key;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END
$body$;

REVOKE ALL ON public.reliability_tenant_work,
    public.outbox_delivery_receipt,public.inbox_handler_receipt
    FROM PUBLIC,accord_api,accord_worker;
REVOKE ALL ON public.outbox_event,public.inbox_message
    FROM PUBLIC,accord_api,accord_worker;
GRANT SELECT ON public.outbox_event TO accord_api,accord_worker;
GRANT SELECT ON public.inbox_message TO accord_worker;
GRANT UPDATE (state,attempt_count,available_at,lease_owner,lease_generation,
              lease_token,lease_until,delivered_at,dead_at,last_error_code)
    ON public.outbox_event TO accord_worker;
GRANT UPDATE (state,attempt_count,available_at,lease_owner,lease_generation,
              lease_token,lease_until,completed_at,dead_at,last_error_code)
    ON public.inbox_message TO accord_worker;
GRANT SELECT,INSERT ON public.outbox_delivery_receipt,
    public.inbox_handler_receipt TO accord_worker;
REVOKE DELETE ON public.idempotency_result FROM accord_worker;

REVOKE ALL ON FUNCTION accord_security.signal_reliability_tenant_work()
    FROM PUBLIC,accord_api,accord_worker;
REVOKE ALL ON FUNCTION accord_security.acquire_reliability_tenant_work(varchar,bigint)
    FROM PUBLIC,accord_api,accord_worker;
REVOKE ALL ON FUNCTION accord_security.renew_reliability_tenant_work(
    uuid,varchar,bigint,uuid,timestamptz,bigint)
    FROM PUBLIC,accord_api,accord_worker;
REVOKE ALL ON FUNCTION accord_security.lock_reliability_tenant_work_permit(
    uuid,varchar,bigint,uuid,timestamptz)
    FROM PUBLIC,accord_api,accord_worker;
REVOKE ALL ON FUNCTION accord_security.finish_reliability_tenant_work(
    uuid,varchar,bigint,uuid,timestamptz,timestamptz)
    FROM PUBLIC,accord_api,accord_worker;
REVOKE ALL ON FUNCTION accord_security.delete_expired_completed_idempotency_results(integer)
    FROM PUBLIC,accord_api,accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.signal_reliability_tenant_work()
    TO accord_api,accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.acquire_reliability_tenant_work(varchar,bigint),
    accord_security.renew_reliability_tenant_work(
      uuid,varchar,bigint,uuid,timestamptz,bigint),
    accord_security.lock_reliability_tenant_work_permit(
      uuid,varchar,bigint,uuid,timestamptz),
    accord_security.finish_reliability_tenant_work(
      uuid,varchar,bigint,uuid,timestamptz,timestamptz),
    accord_security.delete_expired_completed_idempotency_results(integer)
    TO accord_worker;

REVOKE ALL ON FUNCTION accord_security.guard_outbox_v004_transition(),
    accord_security.guard_inbox_v004_transition(),
    accord_security.guard_outbox_delivery_receipt(),
    accord_security.guard_inbox_handler_receipt(),
    accord_security.reject_receipt_change(),
    accord_security.signal_outbox_tenant_work(),
    accord_security.signal_inbox_tenant_work(),
    accord_security.signal_completed_idempotency_work()
    FROM PUBLIC,accord_api,accord_worker;

REVOKE ALL ON FUNCTION accord_security.accept_inbox_message(
    uuid,varchar,varchar,char,varchar,varchar,jsonb)
    FROM PUBLIC,accord_api,accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.accept_inbox_message(
    uuid,varchar,varchar,char,varchar,varchar,jsonb)
    TO accord_worker;
