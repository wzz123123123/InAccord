module "foundation_contract" {
  source = "../../modules/accord-foundation-contract"

  environment_class          = var.environment_class
  remote_authority           = var.remote_authority
  promotion_remote_authority = var.promotion_remote_authority
  release_evidence           = var.release_evidence
  components                 = var.components
  object_capability_evidence = var.object_capability_evidence
  backup_policy              = var.backup_policy
}

resource "kubernetes_namespace_v1" "accord" {
  metadata {
    name = var.namespace
    labels = {
      "app.kubernetes.io/part-of"             = "accord"
      "accord.inforvans.com/environment"      = var.environment_name
      "accord.inforvans.com/environment-class" = var.environment_class
    }
  }
}

resource "helm_release" "accord" {
  name              = "accord"
  namespace         = kubernetes_namespace_v1.accord.metadata[0].name
  chart             = abspath("../../../helm/accord")
  dependency_update = false
  atomic            = true
  cleanup_on_fail   = true
  wait              = true
  wait_for_jobs     = true
  timeout           = 600
  values            = [file(var.rendered_values_path)]

  lifecycle {
    precondition {
      condition     = var.environment_class == "test"
      error_message = "The local-kubernetes adapter is test-only and cannot claim a production environment."
    }
  }
}
