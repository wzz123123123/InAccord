output "component_deployment_ids" {
  description = "Normalized nonsensitive workload ownership identifiers."
  sensitive   = false
  value = {
    for name, component in var.components : name => {
      service_account        = component.service_account
      identity_provider      = component.identity.provider
      identity_provider_name = component.identity.provider_name
      identity_subject       = component.identity.subject
      identity_audience      = component.identity.audience
      external_secret_store  = component.external_secret.store.name
      external_secret_target = component.external_secret.target_name
      database_secret        = component.database.secret_name
      login_role             = component.database.login_role
      session_role           = component.database.session_role
      image_reference        = "${component.image.repository}@${component.image.digest}"
      network_profile        = component.network.profile
    }
  }
}

output "validated_components" {
  description = "Validated reference-only component contract used by mock-provider tests."
  sensitive   = false
  value       = var.components
}

output "remote_authority" {
  description = "Authoritative immutable Git URL/commit/tree tuple."
  sensitive   = false
  value       = var.remote_authority
}

output "release_evidence_digests" {
  description = "Digest-only release evidence bindings."
  sensitive   = false
  value       = var.release_evidence
}

output "object_capability_evidence_digests" {
  description = "Task 13 report, receipt, and eight capability evidence digests."
  sensitive   = false
  value = {
    capability_report_digest    = var.object_capability_evidence.capability_report_digest
    verification_receipt_digest = var.object_capability_evidence.verification_receipt_digest
    capability_evidence_digests = var.object_capability_evidence.capability_evidence_digests
  }
}

output "backup_policy_evidence" {
  description = "Nonsensitive PITR policy and restore-test evidence."
  sensitive   = false
  value       = var.backup_policy
}
