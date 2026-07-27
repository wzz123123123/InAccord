output "namespace_id" {
  description = "Created namespace identifier."
  sensitive   = false
  value       = kubernetes_namespace_v1.accord.id
}

output "helm_release_id" {
  description = "Installed Helm release identifier."
  sensitive   = false
  value       = helm_release.accord.id
}

output "component_deployment_ids" {
  description = "Nonsensitive normalized component ownership bindings."
  sensitive   = false
  value       = module.foundation_contract.component_deployment_ids
}

output "validated_components" {
  description = "Validated reference-only component contract for mock-provider tests."
  sensitive   = false
  value       = module.foundation_contract.validated_components
}

output "remote_authority" {
  description = "Immutable Git authority bound by the contract."
  sensitive   = false
  value       = module.foundation_contract.remote_authority
}

output "release_evidence_digests" {
  description = "Release evidence digests only."
  sensitive   = false
  value       = module.foundation_contract.release_evidence_digests
}
