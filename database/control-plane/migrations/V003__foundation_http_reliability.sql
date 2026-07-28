SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE public.contract_validation (
    tenant_id uuid NOT NULL,
    validation_id uuid NOT NULL,
    aggregate_type varchar(64)
        GENERATED ALWAYS AS ('contract-validation') STORED,
    schema_id varchar(512) NOT NULL,
    document_digest char(71) NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    CONSTRAINT contract_validation_pkey
        PRIMARY KEY (tenant_id, validation_id),
    CONSTRAINT contract_validation_head_fkey
        FOREIGN KEY (tenant_id, aggregate_type, validation_id)
        REFERENCES public.aggregate_head (tenant_id, aggregate_type, aggregate_id),
    CONSTRAINT contract_validation_schema_id_known CHECK (
        schema_id ~ '^https://schemas[.]accord[.]inforvans[.]com/[A-Za-z0-9._~:/-]+$'
        AND pg_catalog.octet_length(schema_id)
              - pg_catalog.octet_length('https://schemas.accord.inforvans.com/') >= 1
        AND pg_catalog.octet_length(schema_id)
              - pg_catalog.octet_length('https://schemas.accord.inforvans.com/') <= 448),
    CONSTRAINT contract_validation_document_digest_format
        CHECK (document_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT contract_validation_version_positive CHECK (version >= 1),
    CONSTRAINT contract_validation_timestamps_ordered CHECK (updated_at >= created_at)
);

ALTER TABLE public.idempotency_result
    ADD CONSTRAINT idempotency_result_aggregate_binding_consistent CHECK (
        (aggregate_type IS NULL AND aggregate_id IS NULL AND aggregate_version IS NULL)
        OR
        (aggregate_type IS NOT NULL AND aggregate_id IS NOT NULL
          AND aggregate_version IS NOT NULL)
    ),
    ADD CONSTRAINT idempotency_result_detached_status_known CHECK (
        state <> 'COMPLETED'
        OR aggregate_type IS NOT NULL
        OR response_status IN (404, 412, 422)
    );

CREATE FUNCTION accord_security.guard_contract_validation_version()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $body$
DECLARE
    head_version bigint;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
           OR OLD.validation_id IS DISTINCT FROM NEW.validation_id
           OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
            RAISE EXCEPTION 'contract_validation_immutable'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.version = 9223372036854775807
           OR NEW.version IS DISTINCT FROM OLD.version + 1 THEN
            RAISE EXCEPTION 'contract_validation_version_step'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.version < 1 THEN
        RAISE EXCEPTION 'contract_validation_version_positive'
            USING ERRCODE = '23514';
    END IF;

    SELECT head.version INTO head_version
    FROM public.aggregate_head AS head
    WHERE head.tenant_id = NEW.tenant_id
      AND head.aggregate_type = 'contract-validation'
      AND head.aggregate_id = NEW.validation_id;
    IF head_version IS DISTINCT FROM NEW.version THEN
        RAISE EXCEPTION 'contract_validation_head_version_match'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        NEW.updated_at := pg_catalog.clock_timestamp();
    END IF;
    RETURN NEW;
END
$body$;

REVOKE ALL ON FUNCTION accord_security.guard_contract_validation_version()
    FROM PUBLIC, accord_api, accord_worker;

CREATE TRIGGER contract_validation_guard
BEFORE INSERT OR UPDATE ON public.contract_validation
FOR EACH ROW EXECUTE FUNCTION accord_security.guard_contract_validation_version();

SELECT accord_security.enforce_tenant_table(
    'public.contract_validation'::pg_catalog.regclass);

REVOKE ALL ON public.contract_validation
    FROM PUBLIC, accord_api, accord_worker;
GRANT SELECT, INSERT ON public.contract_validation TO accord_api;
GRANT UPDATE (schema_id, document_digest, version)
    ON public.contract_validation TO accord_api;
