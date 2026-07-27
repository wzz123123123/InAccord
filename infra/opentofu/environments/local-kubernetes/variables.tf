variable "namespace" {
  type        = string
  description = "Namespace owned by this adapter."
  default     = "accord-system"

  validation {
    condition     = can(regex("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$", var.namespace))
    error_message = "namespace must be a Kubernetes DNS label."
  }
}

variable "environment_name" {
  type        = string
  description = "Nonsensitive environment identifier."
  default     = "local-kubernetes"
}

variable "kube_config_path" {
  type        = string
  description = "Path to a local kubeconfig; contents are never output."
  default     = null
  nullable    = true
}

variable "kube_context" {
  type        = string
  description = "Selected kubeconfig context."
  default     = null
  nullable    = true
}

variable "rendered_values_path" {
  type        = string
  description = "Path to schema-validated, secret-free rendered Helm values."
}

variable "environment_class" {
  type = string
}

variable "remote_authority" {
  type = object({
    repository_url = string
    commit_sha     = string
    tree_sha       = string
  })
}

variable "promotion_remote_authority" {
  type = object({
    commit_sha = string
    tree_sha   = string
  })
}

variable "release_evidence" {
  type = object({
    release_manifest_digest     = string
    dsse_envelope_digest        = string
    verification_receipt_digest = string
  })
}

variable "components" {
  description = "Credential-free component contract, validated by accord-foundation-contract."
  type        = any
  nullable    = false
}

variable "object_capability_evidence" {
  description = "Task 13 digest bindings; no caller-supplied capability booleans."
  type        = any
  nullable    = false
}

variable "backup_policy" {
  type = object({
    pitr_enabled         = bool
    retention_days      = number
    restore_test_digest = string
  })
}
