mock_provider "kubernetes" {}
mock_provider "helm" {}

variables {
  namespace            = "accord-system"
  environment_name     = "ft15-test"
  environment_class    = "test"
  rendered_values_path = "../../../helm/accord/values.yaml"

  remote_authority = {
    repository_url = "https://github.com/inforvans/accord.git"
    commit_sha     = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    tree_sha       = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
  promotion_remote_authority = {
    commit_sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    tree_sha   = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
  release_evidence = {
    release_manifest_digest      = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    dsse_envelope_digest         = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    verification_receipt_digest = "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
  }
  object_capability_evidence = {
    environment_class            = "test"
    adapter_id                   = "minio-s3-local"
    capability_report_digest     = "sha256:4444444444444444444444444444444444444444444444444444444444444444"
    verification_receipt_digest = "sha256:5555555555555555555555555555555555555555555555555555555555555555"
    capability_evidence_digests = {
      immutable_versions   = "sha256:6666666666666666666666666666666666666666666666666666666666666666"
      sha256_checksums     = "sha256:7777777777777777777777777777777777777777777777777777777777777777"
      worm                 = "sha256:8888888888888888888888888888888888888888888888888888888888888888"
      legal_hold           = "sha256:9999999999999999999999999999999999999999999999999999999999999999"
      multipart            = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      quarantine_isolation = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      replication_evidence = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
      deletion_receipts    = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
  }
  backup_policy = {
    pitr_enabled         = true
    retention_days      = 14
    restore_test_digest = "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
  }
  components = {
    control-api = {
      service_account = "accord-control-api"
      identity = {
        provider      = "KUBERNETES"
        provider_name = "accord-test-control-api"
        subject       = "system:serviceaccount:accord-system:accord-control-api"
        audience      = "accord-control-api"
      }
      external_secret = {
        store = { kind = "ClusterSecretStore", name = "accord-control-api-store" }
        remote_ref = "accord/test/control-api/runtime"
        target_name = "accord-control-api-runtime"
        target_keys = { database_password = "database-password", postgres_ca = "postgres-ca.crt", otlp_ca = "otlp-ca.crt" }
      }
      database = { database_name = "accord_control", secret_name = "accord-control-api-runtime", password_key = "database-password", login_role = "accord_api_login", session_role = "accord_api" }
      image    = { repository = "ghcr.io/inforvans/accord-control-api", digest = "sha256:1111111111111111111111111111111111111111111111111111111111111111" }
      runtime  = { uid = 10001, gid = 10001 }
      probes   = { type = "HTTP", port_name = "http", liveness_path = "/actuator/health/liveness", readiness_path = "/actuator/health/readiness" }
      network = {
        profile = "CONTROL_API"
        destinations = {
          control_postgres = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "accord-system" }, pod_selector = { key = "app.kubernetes.io/name", value = "control-postgres" }, service_name = "accord-control-postgres", ports = [{ protocol = "TCP", port = 5432 }] }
          otlp = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "accord-system" }, pod_selector = { key = "app.kubernetes.io/name", value = "otel-collector" }, service_name = "accord-otel-collector", ports = [{ protocol = "TCP", port = 4318 }] }
          dns  = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "kube-system" }, pod_selector = { key = "k8s-app", value = "kube-dns" }, service_name = "kube-dns", ports = [{ protocol = "UDP", port = 53 }, { protocol = "TCP", port = 53 }] }
        }
      }
      tls = {
        postgres = { mode = "VERIFY_FULL", ca_secret_key = "postgres-ca.crt", server_name = "control-postgres.accord-system.svc.cluster.local" }
        otlp     = { mode = "TLS", protocol = "HTTPS", ca_secret_key = "otlp-ca.crt", server_name = "otel-collector.accord-system.svc.cluster.local" }
      }
    }
    control-worker = {
      service_account = "accord-control-worker"
      identity = {
        provider      = "KUBERNETES"
        provider_name = "accord-test-control-worker"
        subject       = "system:serviceaccount:accord-system:accord-control-worker"
        audience      = "accord-control-worker"
      }
      external_secret = {
        store = { kind = "ClusterSecretStore", name = "accord-control-worker-store" }
        remote_ref = "accord/test/control-worker/runtime"
        target_name = "accord-control-worker-runtime"
        target_keys = { database_password = "database-password", postgres_ca = "postgres-ca.crt", otlp_ca = "otlp-ca.crt", temporal_ca = "temporal-ca.crt", temporal_client_certificate = "temporal-client.crt", temporal_client_private_key = "temporal-client.key" }
        temporal_client_certificate_ref = { remote_ref = "accord/test/control-worker/temporal-client", certificate_property = "certificate", private_key_property = "private-key" }
      }
      database = { database_name = "accord_control", secret_name = "accord-control-worker-runtime", password_key = "database-password", login_role = "accord_worker_login", session_role = "accord_worker" }
      image    = { repository = "ghcr.io/inforvans/accord-control-worker", digest = "sha256:2222222222222222222222222222222222222222222222222222222222222222" }
      runtime  = { uid = 10002, gid = 10002 }
      probes   = { type = "EXEC_JAVA", java_path = "/usr/bin/java", jar = "/opt/accord/bin/worker-probe.jar", liveness_argument = "live", readiness_argument = "ready" }
      network = {
        profile = "CONTROL_WORKER"
        destinations = {
          control_postgres = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "accord-system" }, pod_selector = { key = "app.kubernetes.io/name", value = "control-postgres" }, service_name = "accord-control-postgres", ports = [{ protocol = "TCP", port = 5432 }] }
          temporal = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "accord-system" }, pod_selector = { key = "app.kubernetes.io/name", value = "temporal-frontend" }, service_name = "accord-temporal", ports = [{ protocol = "TCP", port = 7233 }] }
          otlp = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "accord-system" }, pod_selector = { key = "app.kubernetes.io/name", value = "otel-collector" }, service_name = "accord-otel-collector", ports = [{ protocol = "TCP", port = 4318 }] }
          dns  = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "kube-system" }, pod_selector = { key = "k8s-app", value = "kube-dns" }, service_name = "kube-dns", ports = [{ protocol = "UDP", port = 53 }, { protocol = "TCP", port = 53 }] }
        }
      }
      tls = {
        postgres = { mode = "VERIFY_FULL", ca_secret_key = "postgres-ca.crt", server_name = "control-postgres.accord-system.svc.cluster.local" }
        temporal = { mode = "MTLS", ca_secret_key = "temporal-ca.crt", client_certificate_secret_key = "temporal-client.crt", client_private_key_secret_key = "temporal-client.key", server_name = "temporal-frontend.accord-system.svc.cluster.local" }
        otlp     = { mode = "TLS", protocol = "HTTPS", ca_secret_key = "otlp-ca.crt", server_name = "otel-collector.accord-system.svc.cluster.local" }
      }
    }
    webhook-edge = {
      service_account = "accord-webhook-edge"
      identity = {
        provider      = "KUBERNETES"
        provider_name = "accord-test-webhook-edge"
        subject       = "system:serviceaccount:accord-system:accord-webhook-edge"
        audience      = "accord-webhook-edge"
      }
      external_secret = {
        store = { kind = "ClusterSecretStore", name = "accord-webhook-edge-store" }
        remote_ref = "accord/test/webhook-edge/runtime"
        target_name = "accord-webhook-edge-runtime"
        target_keys = { database_password = "database-password", postgres_ca = "postgres-ca.crt", otlp_ca = "otlp-ca.crt" }
      }
      database = { database_name = "accord_webhook", secret_name = "accord-webhook-edge-runtime", password_key = "database-password", login_role = "accord_webhook_runtime_login", session_role = "accord_webhook_runtime" }
      image    = { repository = "ghcr.io/inforvans/accord-webhook-edge", digest = "sha256:3333333333333333333333333333333333333333333333333333333333333333" }
      runtime  = { uid = 10003, gid = 10003 }
      probes   = { type = "HTTP", port_name = "http", liveness_path = "/actuator/health/liveness", readiness_path = "/actuator/health/readiness" }
      network = {
        profile = "WEBHOOK_EDGE"
        destinations = {
          webhook_postgres = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "accord-system" }, pod_selector = { key = "app.kubernetes.io/name", value = "webhook-postgres" }, service_name = "accord-webhook-postgres", ports = [{ protocol = "TCP", port = 5432 }] }
          otlp = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "accord-system" }, pod_selector = { key = "app.kubernetes.io/name", value = "otel-collector" }, service_name = "accord-otel-collector", ports = [{ protocol = "TCP", port = 4318 }] }
          dns  = { mode = "KUBERNETES_SERVICE", namespace_selector = { key = "kubernetes.io/metadata.name", value = "kube-system" }, pod_selector = { key = "k8s-app", value = "kube-dns" }, service_name = "kube-dns", ports = [{ protocol = "UDP", port = 53 }, { protocol = "TCP", port = 53 }] }
        }
      }
      tls = {
        postgres = { mode = "VERIFY_FULL", ca_secret_key = "postgres-ca.crt", server_name = "webhook-postgres.accord-system.svc.cluster.local" }
        otlp     = { mode = "TLS", protocol = "HTTPS", ca_secret_key = "otlp-ca.crt", server_name = "otel-collector.accord-system.svc.cluster.local" }
      }
    }
  }
}

run "valid_real_resource_graph" {
  command = plan

  assert {
    condition     = kubernetes_namespace_v1.accord.metadata[0].name == "accord-system"
    error_message = "adapter must plan exactly the selected namespace"
  }
  assert {
    condition     = helm_release.accord.name == "accord" && length(helm_release.accord.values) == 1
    error_message = "adapter must plan exactly one Helm release with one rendered values input"
  }
}

run "reject_shared_identity" {
  command = plan
  variables {
    components = merge(run.valid_real_resource_graph.validated_components, {
      control-worker = merge(run.valid_real_resource_graph.validated_components["control-worker"], {
        identity = merge(run.valid_real_resource_graph.validated_components["control-worker"].identity, {
          provider_name = run.valid_real_resource_graph.validated_components["control-api"].identity.provider_name
          subject       = run.valid_real_resource_graph.validated_components["control-api"].identity.subject
        })
      })
    })
  }
  expect_failures = [module.foundation_contract.check.component_identity_uniqueness]
}

run "reject_wrong_role" {
  command = plan
  variables {
    components = merge(run.valid_real_resource_graph.validated_components, {
      control-worker = merge(run.valid_real_resource_graph.validated_components["control-worker"], {
        database = merge(run.valid_real_resource_graph.validated_components["control-worker"].database, { session_role = "accord_api" })
      })
    })
  }
  expect_failures = [module.foundation_contract.check.exact_component_roles, module.foundation_contract.check.component_identity_uniqueness]
}

run "reject_mutable_image" {
  command = plan
  variables {
    components = merge(run.valid_real_resource_graph.validated_components, {
      control-api = merge(run.valid_real_resource_graph.validated_components["control-api"], {
        image = { repository = "ghcr.io/inforvans/accord-control-api:latest", digest = "sha256:1111111111111111111111111111111111111111111111111111111111111111" }
      })
    })
  }
  expect_failures = [module.foundation_contract.var.components]
}

run "reject_broad_network" {
  command = plan
  variables {
    components = merge(run.valid_real_resource_graph.validated_components, {
      control-worker = merge(run.valid_real_resource_graph.validated_components["control-worker"], {
        network = merge(run.valid_real_resource_graph.validated_components["control-worker"].network, {
          destinations = merge(run.valid_real_resource_graph.validated_components["control-worker"].network.destinations, {
            temporal = { mode = "AUDITED_CIDR", cidrs = ["0.0.0.0/0"], evidence_digest = "sha256:9999999999999999999999999999999999999999999999999999999999999999", evidence_expires_at = "2099-01-01T00:00:00Z", owner = "platform-network", ports = [{ protocol = "TCP", port = 7233 }] }
          })
        })
      })
    })
  }
  expect_failures = [module.foundation_contract.var.components]
}

run "reject_missing_tls_binding" {
  command = plan
  variables {
    components = merge(run.valid_real_resource_graph.validated_components, {
      control-worker = merge(run.valid_real_resource_graph.validated_components["control-worker"], {
        tls = merge(run.valid_real_resource_graph.validated_components["control-worker"].tls, { temporal = null })
      })
    })
  }
  expect_failures = [module.foundation_contract.check.application_tls]
}

run "reject_expired_cidr_evidence" {
  command = plan
  variables {
    components = merge(run.valid_real_resource_graph.validated_components, {
      control-worker = merge(run.valid_real_resource_graph.validated_components["control-worker"], {
        network = merge(run.valid_real_resource_graph.validated_components["control-worker"].network, {
          destinations = merge(run.valid_real_resource_graph.validated_components["control-worker"].network.destinations, {
            temporal = { mode = "AUDITED_CIDR", cidrs = ["203.0.113.8/32"], evidence_digest = "sha256:9999999999999999999999999999999999999999999999999999999999999999", evidence_expires_at = "2020-01-01T00:00:00Z", owner = "platform-network", ports = [{ protocol = "TCP", port = 7233 }] }
          })
        })
      })
    })
  }
  expect_failures = [module.foundation_contract.var.components]
}

run "reject_local_object_profile_as_production" {
  command = plan
  variables { environment_class = "production" }
  expect_failures = [module.foundation_contract.check.production_object_capabilities, helm_release.accord]
}

run "reject_mismatched_remote_sha" {
  command = plan
  variables {
    promotion_remote_authority = {
      commit_sha = "ffffffffffffffffffffffffffffffffffffffff"
      tree_sha   = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
  }
  expect_failures = [module.foundation_contract.check.remote_authority_binding]
}
