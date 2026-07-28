locals {
  expected_roles = {
    control-api    = ["accord_api_login", "accord_api"]
    control-worker = ["accord_worker_login", "accord_worker"]
    webhook-edge   = ["accord_webhook_runtime_login", "accord_webhook_runtime"]
  }
  expected_profiles = {
    control-api    = "CONTROL_API"
    control-worker = "CONTROL_WORKER"
    webhook-edge   = "WEBHOOK_EDGE"
  }
  expected_destinations = {
    control-api    = toset(["control_postgres", "otlp", "dns"])
    control-worker = toset(["control_postgres", "temporal", "otlp", "dns"])
    webhook-edge   = toset(["webhook_postgres", "otlp", "dns"])
  }
  component_values = values(var.components)
}

check "exact_component_roles" {
  assert {
    condition = alltrue([
      for name, component in var.components :
      [component.database.login_role, component.database.session_role] == local.expected_roles[name]
    ])
    error_message = "The exact API, worker, and webhook login/session role pairs are mandatory."
  }
}

check "remote_authority_binding" {
  assert {
    condition = (
      var.remote_authority.commit_sha == var.promotion_remote_authority.commit_sha &&
      var.remote_authority.tree_sha == var.promotion_remote_authority.tree_sha
    )
    error_message = "promotion commit/tree SHAs must match the authoritative remote tuple."
  }
}

check "component_identity_uniqueness" {
  assert {
    condition = alltrue([
      length(distinct([for component in local.component_values : component.service_account])) == 3,
      length(distinct([for component in local.component_values : component.identity.provider_name])) == 3,
      length(distinct([for component in local.component_values : component.identity.subject])) == 3,
      length(distinct([for component in local.component_values : component.identity.audience])) == 3,
      length(distinct([for component in local.component_values : component.external_secret.store.name])) == 3,
      length(distinct([for component in local.component_values : component.external_secret.remote_ref])) == 3,
      length(distinct([for component in local.component_values : component.external_secret.target_name])) == 3,
      length(distinct([for component in local.component_values : component.database.secret_name])) == 3,
      length(distinct([for component in local.component_values : component.database.login_role])) == 3,
      length(distinct([for component in local.component_values : component.database.session_role])) == 3,
      length(distinct([for component in local.component_values : component.image.repository])) == 3,
    ])
    error_message = "ServiceAccount, workload identity, secret, role, and image ownership must be unique."
  }
}

check "network_matrix" {
  assert {
    condition = alltrue([
      for name, component in var.components :
      component.network.profile == local.expected_profiles[name] &&
      toset(keys(component.network.destinations)) == local.expected_destinations[name]
    ])
    error_message = "Network destinations must match the exact API/worker/webhook matrix."
  }
}

check "application_tls" {
  assert {
    condition = alltrue([
      for name, component in var.components :
      component.tls.postgres.mode == "VERIFY_FULL" &&
      component.tls.postgres.ca_secret_key == "postgres-ca.crt" &&
      length(component.tls.postgres.server_name) > 0 &&
      component.tls.otlp.mode == "TLS" &&
      component.tls.otlp.protocol == "HTTPS" &&
      component.tls.otlp.ca_secret_key == "otlp-ca.crt" &&
      length(component.tls.otlp.server_name) > 0 &&
      (name == "control-worker" ?
        component.tls.temporal != null &&
        component.tls.temporal.mode == "MTLS" &&
        component.tls.temporal.ca_secret_key == "temporal-ca.crt" &&
        component.tls.temporal.client_certificate_secret_key == "temporal-client.crt" &&
        component.tls.temporal.client_private_key_secret_key == "temporal-client.key" &&
        component.external_secret.temporal_client_certificate_ref != null :
        component.tls.temporal == null && component.external_secret.temporal_client_certificate_ref == null)
    ])
    error_message = "PostgreSQL verify-full, production OTLP TLS, and worker-only Temporal mTLS are mandatory and separate from L3/L4 routes."
  }
}

check "production_object_capabilities" {
  assert {
    condition = var.environment_class != "production" || (
      var.object_capability_evidence.environment_class == "production" &&
      !startswith(var.object_capability_evidence.adapter_id, "minio-")
    )
    error_message = "Production object storage requires the production Task 13 report/receipt and all eight digest-bound capabilities."
  }
}
