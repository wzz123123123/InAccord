variable "environment_class" {
  description = "Closed deployment environment class."
  type        = string

  validation {
    condition     = contains(["test", "production"], var.environment_class)
    error_message = "environment_class must be test or production."
  }
}

variable "remote_authority" {
  description = "Authoritative immutable deployment Git identity."
  type = object({
    repository_url = string
    commit_sha     = string
    tree_sha       = string
  })

  validation {
    condition = (
      can(regex("^https://github[.]com/[a-z0-9-]+/[A-Za-z0-9._-]+[.]git$", var.remote_authority.repository_url)) &&
      can(regex("^[0-9a-f]{40}$", var.remote_authority.commit_sha)) &&
      can(regex("^[0-9a-f]{40}$", var.remote_authority.tree_sha))
    )
    error_message = "remote authority must use an allowlisted HTTPS Git URL and lowercase commit/tree SHAs."
  }
}

variable "promotion_remote_authority" {
  description = "Promotion manifest copy of the authoritative commit/tree tuple."
  type = object({
    commit_sha = string
    tree_sha   = string
  })

  validation {
    condition = (
      can(regex("^[0-9a-f]{40}$", var.promotion_remote_authority.commit_sha)) &&
      can(regex("^[0-9a-f]{40}$", var.promotion_remote_authority.tree_sha))
    )
    error_message = "promotion authority must contain lowercase commit/tree SHAs."
  }
}

variable "release_evidence" {
  description = "Digest-only release evidence; no signature material or credentials."
  type = object({
    release_manifest_digest     = string
    dsse_envelope_digest        = string
    verification_receipt_digest = string
  })

  validation {
    condition = alltrue([
      for digest in values(var.release_evidence) :
      can(regex("^sha256:[0-9a-f]{64}$", digest)) && digest != "sha256:0000000000000000000000000000000000000000000000000000000000000000"
    ])
    error_message = "release evidence values must be nonzero lowercase sha256 digests."
  }
}

variable "components" {
  description = "Exactly three keyed workload contracts. This object contains references and target keys, never credential values."
  type = map(object({
    service_account = string
    identity = object({
      provider                 = string
      provider_name            = string
      subject                  = string
      audience                 = string
      token_expiration_seconds = optional(number)
      role_arn                 = optional(string)
      service_account_email    = optional(string)
      client_id                = optional(string)
      tenant_id                = optional(string)
    })
    external_secret = object({
      store = object({
        kind = string
        name = string
      })
      remote_ref = string
      target_name = string
      target_keys = map(string)
      temporal_client_certificate_ref = optional(object({
        remote_ref           = string
        certificate_property = string
        private_key_property = string
      }))
    })
    database = object({
      database_name = string
      secret_name   = string
      password_key  = string
      login_role    = string
      session_role  = string
    })
    image = object({
      repository = string
      digest     = string
    })
    runtime = object({
      uid = number
      gid = number
    })
    probes = object({
      type               = string
      java_path          = optional(string)
      jar                = optional(string)
      liveness_argument  = optional(string)
      readiness_argument = optional(string)
      port_name          = optional(string)
      liveness_path      = optional(string)
      readiness_path     = optional(string)
    })
    network = object({
      profile = string
      destinations = map(object({
        mode = string
        namespace_selector = optional(object({
          key   = string
          value = string
        }))
        pod_selector = optional(object({
          key   = string
          value = string
        }))
        service_name       = optional(string)
        fqdn               = optional(string)
        cidrs              = optional(list(string), [])
        evidence_digest    = optional(string)
        evidence_expires_at = optional(string)
        owner              = optional(string)
        ports = list(object({
          protocol = string
          port     = number
        }))
      }))
    })
    tls = object({
      postgres = object({
        mode          = string
        ca_secret_key = string
        server_name   = string
      })
      otlp = object({
        mode          = string
        protocol      = string
        ca_secret_key = string
        server_name   = string
      })
      temporal = optional(object({
        mode                          = string
        ca_secret_key                 = string
        client_certificate_secret_key = string
        client_private_key_secret_key = string
        server_name                   = string
      }))
    })
  }))

  validation {
    condition     = toset(keys(var.components)) == toset(["control-api", "control-worker", "webhook-edge"])
    error_message = "components must contain exactly control-api, control-worker, and webhook-edge."
  }

  validation {
    condition = alltrue([
      for name, component in var.components :
      component.identity.subject == "system:serviceaccount:${split(":", component.identity.subject)[2]}:${component.service_account}" &&
      component.identity.audience == component.service_account &&
      contains(["KUBERNETES", "AWS", "GCP", "AZURE"], component.identity.provider) &&
      (
        component.identity.provider == "KUBERNETES" ? component.identity.token_expiration_seconds == null :
        component.identity.token_expiration_seconds != null && component.identity.token_expiration_seconds >= 600 && component.identity.token_expiration_seconds <= 3600
      )
    ])
    error_message = "each workload identity must use a closed provider branch and its exact ServiceAccount subject/audience."
  }

  validation {
    condition = alltrue([
      for component in values(var.components) :
      component.external_secret.target_name == component.database.secret_name &&
      component.external_secret.target_keys.database_password == component.database.password_key
    ])
    error_message = "database secret name/key must cross-bind to the component ExternalSecret target."
  }

  validation {
    condition = alltrue([
      for component in values(var.components) :
      can(regex("^(ghcr[.]io|public[.]ecr[.]aws|[a-z0-9.-]+[.]azurecr[.]io|[a-z0-9.-]+-docker[.]pkg[.]dev)/", component.image.repository)) &&
      !strcontains(component.image.repository, ":") &&
      !strcontains(component.image.repository, "@") &&
      can(regex("^sha256:[0-9a-f]{64}$", component.image.digest))
    ])
    error_message = "component images must be immutable repository plus sha256 digest pairs."
  }

  validation {
    condition = alltrue(flatten([
      for component in values(var.components) : [
        for route in values(component.network.destinations) :
        contains(["KUBERNETES_SERVICE", "EGRESS_GATEWAY", "CILIUM_FQDN", "AUDITED_CIDR"], route.mode) &&
        (
          contains(["KUBERNETES_SERVICE", "EGRESS_GATEWAY"], route.mode) ?
          route.namespace_selector != null && route.pod_selector != null && route.service_name != null : true
        ) &&
        (
          route.mode == "CILIUM_FQDN" ? route.fqdn != null && !strcontains(route.fqdn, "*") : true
        ) &&
        (
          route.mode == "AUDITED_CIDR" ?
          length(route.cidrs) > 0 &&
          !contains(route.cidrs, "0.0.0.0/0") &&
          !contains(route.cidrs, "::/0") &&
          route.evidence_digest != null && can(regex("^sha256:[0-9a-f]{64}$", route.evidence_digest)) &&
          route.evidence_expires_at != null && can(timecmp(route.evidence_expires_at, timestamp())) && timecmp(route.evidence_expires_at, timestamp()) > 0 &&
          route.owner != null && length(route.owner) >= 3 &&
          alltrue([for cidr in route.cidrs : try(!cidrcontains(cidr, "169.254.169.254"), true) && try(!cidrcontains(cidr, "169.254.170.2"), true) && try(!cidrcontains(cidr, "100.100.100.200"), true)]) : true
        )
      ]
    ]))
    error_message = "every route must satisfy exactly one closed, bounded route mode."
  }
}

variable "object_capability_evidence" {
  description = "Immutable Task 13 capability report and verification receipt bindings."
  type = object({
    environment_class           = string
    adapter_id                  = string
    capability_report_digest    = string
    verification_receipt_digest = string
    capability_evidence_digests = object({
      immutable_versions      = string
      sha256_checksums        = string
      worm                    = string
      legal_hold              = string
      multipart               = string
      quarantine_isolation    = string
      replication_evidence    = string
      deletion_receipts       = string
    })
  })

  validation {
    condition = alltrue([
      for digest in concat(
        [var.object_capability_evidence.capability_report_digest, var.object_capability_evidence.verification_receipt_digest],
        values(var.object_capability_evidence.capability_evidence_digests),
      ) : digest != ""
    ])
    error_message = "all eight object capability evidence bindings and report/receipt digests are required."
  }

  validation {
    condition = alltrue([
      for digest in concat(
        [var.object_capability_evidence.capability_report_digest, var.object_capability_evidence.verification_receipt_digest],
        values(var.object_capability_evidence.capability_evidence_digests),
      ) : can(regex("^sha256:[0-9a-f]{64}$", digest))
    ])
    error_message = "object capability evidence must contain digest bindings, never caller-supplied pass booleans."
  }
}

variable "backup_policy" {
  description = "Backup/PITR operating policy without credentials."
  type = object({
    pitr_enabled        = bool
    retention_days     = number
    restore_test_digest = string
  })

  validation {
    condition = (
      var.backup_policy.pitr_enabled &&
      var.backup_policy.retention_days >= 7 &&
      var.backup_policy.retention_days <= 365 &&
      can(regex("^sha256:[0-9a-f]{64}$", var.backup_policy.restore_test_digest))
    )
    error_message = "backup policy requires PITR, bounded retention, and an immutable restore-test digest."
  }
}
