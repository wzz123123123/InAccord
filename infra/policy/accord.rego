package accord

import rego.v1

documents := input if {
  is_array(input)
}

documents := [input] if {
  not is_array(input)
}

deployments := [document |
  some document in documents
  document.kind == "Deployment"
]

service_accounts := [document |
  some document in documents
  document.kind == "ServiceAccount"
]

external_secrets := [document |
  some document in documents
  document.kind == "ExternalSecret"
]

network_policies := [document |
  some document in documents
  document.kind == "NetworkPolicy"
]

cilium_policies := [document |
  some document in documents
  document.kind == "CiliumNetworkPolicy"
]

pod_disruption_budgets := [document |
  some document in documents
  document.kind == "PodDisruptionBudget"
]

component_names := {"control-api", "control-worker", "webhook-edge"}

expected_service_accounts := {
  "control-api": "accord-control-api",
  "control-worker": "accord-control-worker",
  "webhook-edge": "accord-webhook-edge",
}

expected_login_roles := {
  "control-api": "accord_api_login",
  "control-worker": "accord_worker_login",
  "webhook-edge": "accord_webhook_runtime_login",
}

expected_session_roles := {
  "control-api": "accord_api",
  "control-worker": "accord_worker",
  "webhook-edge": "accord_webhook_runtime",
}

component(document) := object.get(object.get(document.metadata, "labels", {}), "accord.inforvans.com/component", "")

first_container(deployment) := deployment.spec.template.spec.containers[0]

environment_values(container) := {
  entry.name: object.get(entry, "value", "") |
  some entry in object.get(container, "env", [])
}

environment(container, name) := object.get(environment_values(container), name, "")

pdb_for_component(name) if {
  some pdb in pod_disruption_budgets
  component(pdb) == name
}

allowed_service_account_annotation(key) if startswith(key, "accord.inforvans.com/")
allowed_service_account_annotation("eks.amazonaws.com/role-arn")
allowed_service_account_annotation("iam.gke.io/gcp-service-account")
allowed_service_account_annotation("azure.workload.identity/client-id")
allowed_service_account_annotation("azure.workload.identity/tenant-id")

deny contains "deployment set must contain exactly the three closed Accord components" if {
  count(deployments) > 0
  {component(deployment) | some deployment in deployments} != component_names
}

deny contains "ServiceAccount set must contain exactly the three closed Accord components" if {
  count(service_accounts) > 0
  {component(service_account) | some service_account in service_accounts} != component_names
}

deny contains sprintf("%s uses the wrong ServiceAccount", [name]) if {
  some deployment in deployments
  name := component(deployment)
  expected_service_accounts[name] != deployment.spec.template.spec.serviceAccountName
}

deny contains sprintf("%s permits an automatic ServiceAccount token", [component(service_account)]) if {
  some service_account in service_accounts
  object.get(service_account, "automountServiceAccountToken", true) != false
}

deny contains sprintf("%s permits an automatic ServiceAccount token", [component(deployment)]) if {
  some deployment in deployments
  object.get(deployment.spec.template.spec, "automountServiceAccountToken", true) != false
}

deny contains sprintf("%s ServiceAccount has a free-form provider annotation %s", [component(service_account), key]) if {
  some service_account in service_accounts
  some key, _ in object.get(service_account.metadata, "annotations", {})
  not allowed_service_account_annotation(key)
}

deny contains "workload identity provider names must be unique" if {
  count(service_accounts) == 3
  values := [object.get(service_account.metadata.annotations, "accord.inforvans.com/workload-identity-provider", "") | some service_account in service_accounts]
  count({value | some value in values}) != 3
}

deny contains "workload identity subjects must be unique" if {
  count(service_accounts) == 3
  values := [object.get(service_account.metadata.annotations, "accord.inforvans.com/workload-identity-subject", "") | some service_account in service_accounts]
  count({value | some value in values}) != 3
}

deny contains "workload identity audiences must be unique" if {
  count(service_accounts) == 3
  values := [object.get(service_account.metadata.annotations, "accord.inforvans.com/workload-identity-audience", "") | some service_account in service_accounts]
  count({value | some value in values}) != 3
}

deny contains "ExternalSecret targets must be unique" if {
  count(external_secrets) == 3
  values := [external_secret.spec.target.name | some external_secret in external_secrets]
  count({value | some value in values}) != 3
}

deny contains sprintf("%s has an inline Kubernetes Secret", [object.get(document.metadata, "name", "unnamed")]) if {
  some document in documents
  document.kind == "Secret"
  object.get(document, "data", {}) != {}
}

deny contains sprintf("%s has inline stringData", [object.get(document.metadata, "name", "unnamed")]) if {
  some document in documents
  document.kind == "Secret"
  object.get(document, "stringData", {}) != {}
}

deny contains sprintf("%s ExternalSecret lacks remote-only data bindings", [component(external_secret)]) if {
  some external_secret in external_secrets
  some binding in object.get(external_secret.spec, "data", [])
  object.get(binding, "remoteRef", {}) == {}
}

deny contains "only control-worker may receive a Temporal client certificate" if {
  some external_secret in external_secrets
  component(external_secret) != "control-worker"
  some binding in external_secret.spec.data
  contains(lower(binding.secretKey), "temporal-client")
}

deny contains sprintf("%s image is mutable", [component(deployment)]) if {
  some deployment in deployments
  some container in deployment.spec.template.spec.containers
  not regex.match("^[^:@]+(/[^:@]+)+@sha256:[0-9a-f]{64}$", container.image)
}

deny contains sprintf("%s is not fixed non-root", [component(deployment)]) if {
  some deployment in deployments
  pod_security := object.get(deployment.spec.template.spec, "securityContext", {})
  object.get(pod_security, "runAsNonRoot", false) != true
}

deny contains sprintf("%s uses UID/GID zero or an unset identity", [component(deployment)]) if {
  some deployment in deployments
  pod_security := object.get(deployment.spec.template.spec, "securityContext", {})
  object.get(pod_security, "runAsUser", 0) <= 0
}

deny contains sprintf("%s lacks RuntimeDefault seccomp", [component(deployment)]) if {
  some deployment in deployments
  object.get(object.get(object.get(deployment.spec.template.spec, "securityContext", {}), "seccompProfile", {}), "type", "") != "RuntimeDefault"
}

deny contains sprintf("%s container may escalate privilege", [component(deployment)]) if {
  some deployment in deployments
  container := first_container(deployment)
  security := object.get(container, "securityContext", {})
  object.get(security, "allowPrivilegeEscalation", true) != false
}

deny contains sprintf("%s container has a writable root filesystem", [component(deployment)]) if {
  some deployment in deployments
  container := first_container(deployment)
  security := object.get(container, "securityContext", {})
  object.get(security, "readOnlyRootFilesystem", false) != true
}

deny contains sprintf("%s container does not drop ALL capabilities", [component(deployment)]) if {
  some deployment in deployments
  container := first_container(deployment)
  security := object.get(container, "securityContext", {})
  not "ALL" in object.get(object.get(security, "capabilities", {}), "drop", [])
}

deny contains sprintf("%s lacks bounded requests and limits", [component(deployment)]) if {
  some deployment in deployments
  resources := object.get(first_container(deployment), "resources", {})
  object.get(resources, "requests", {}) == {}
}

deny contains sprintf("%s lacks bounded requests and limits", [component(deployment)]) if {
  some deployment in deployments
  resources := object.get(first_container(deployment), "resources", {})
  object.get(resources, "limits", {}) == {}
}

deny contains sprintf("%s lacks liveness/readiness probes", [component(deployment)]) if {
  some deployment in deployments
  container := first_container(deployment)
  object.get(container, "livenessProbe", {}) == {}
}

deny contains sprintf("%s lacks liveness/readiness probes", [component(deployment)]) if {
  some deployment in deployments
  container := first_container(deployment)
  object.get(container, "readinessProbe", {}) == {}
}

deny contains sprintf("%s lacks topology spread", [component(deployment)]) if {
  some deployment in deployments
  count(object.get(deployment.spec.template.spec, "topologySpreadConstraints", [])) < 2
}

deny contains sprintf("%s lacks pod anti-affinity", [component(deployment)]) if {
  some deployment in deployments
  object.get(object.get(deployment.spec.template.spec, "affinity", {}), "podAntiAffinity", {}) == {}
}

deny contains sprintf("%s lacks a matching PDB", [component(deployment)]) if {
  some deployment in deployments
  name := component(deployment)
  not pdb_for_component(name)
}

deny contains sprintf("%s enables a host namespace", [component(deployment)]) if {
  some deployment in deployments
  spec := deployment.spec.template.spec
  object.get(spec, "hostNetwork", false) == true
}

deny contains sprintf("%s enables a host namespace", [component(deployment)]) if {
  some deployment in deployments
  spec := deployment.spec.template.spec
  object.get(spec, "hostPID", false) == true
}

deny contains sprintf("%s enables a host namespace", [component(deployment)]) if {
  some deployment in deployments
  spec := deployment.spec.template.spec
  object.get(spec, "hostIPC", false) == true
}

deny contains sprintf("%s mounts a hostPath", [component(deployment)]) if {
  some deployment in deployments
  some volume in object.get(deployment.spec.template.spec, "volumes", [])
  object.get(volume, "hostPath", {}) != {}
}

deny contains sprintf("%s exposes a privileged container port", [component(deployment)]) if {
  some deployment in deployments
  some container in deployment.spec.template.spec.containers
  some port in object.get(container, "ports", [])
  port.containerPort < 1024
}

deny contains "control-worker probe invokes a shell instead of worker-probe.jar" if {
  some deployment in deployments
  component(deployment) == "control-worker"
  probe := object.get(first_container(deployment), "livenessProbe", {})
  command := object.get(object.get(probe, "exec", {}), "command", [])
  not "/opt/accord/bin/worker-probe.jar" in command
}

deny contains "control-worker probe invokes a shell instead of worker-probe.jar" if {
  some deployment in deployments
  component(deployment) == "control-worker"
  probe := object.get(first_container(deployment), "livenessProbe", {})
  some argument in object.get(object.get(probe, "exec", {}), "command", [])
  regex.match("(^|/)(sh|bash)$", argument)
}

deny contains sprintf("%s has the wrong login role", [name]) if {
  some deployment in deployments
  name := component(deployment)
  environment(first_container(deployment), "ACCORD_DATABASE_LOGIN_ROLE") != expected_login_roles[name]
}

deny contains sprintf("%s has the wrong session role", [name]) if {
  some deployment in deployments
  name := component(deployment)
  environment(first_container(deployment), "ACCORD_DATABASE_SESSION_ROLE") != expected_session_roles[name]
}

deny contains sprintf("%s places a credential value directly in an environment variable", [component(deployment)]) if {
  some deployment in deployments
  some container in deployment.spec.template.spec.containers
  some entry in object.get(container, "env", [])
  regex.match("(?i)(password|token|secret|credential|private[_-]?key|access[_-]?key)", entry.name)
  object.get(entry, "value", "") != ""
  not startswith(object.get(entry, "value", ""), "/var/run/accord/secrets/")
}

deny contains sprintf("%s requests forbidden source/content/object credentials", [component(deployment)]) if {
  some deployment in deployments
  some container in deployment.spec.template.spec.containers
  some entry in object.get(container, "env", [])
  regex.match("(?i)(git|source|content|object[_-]?storage).*(token|password|credential|key)", entry.name)
}

deny contains sprintf("%s does not configure PostgreSQL verify-full", [component(deployment)]) if {
  some deployment in deployments
  not contains(environment(first_container(deployment), "SPRING_DATASOURCE_URL"), "sslmode=verify-full")
}

deny contains sprintf("%s lacks the mounted PostgreSQL CA", [component(deployment)]) if {
  some deployment in deployments
  not contains(environment(first_container(deployment), "SPRING_DATASOURCE_URL"), "sslrootcert=/var/run/accord/secrets/")
}

deny contains sprintf("%s lacks its exact PostgreSQL login role", [component(deployment)]) if {
  some deployment in deployments
  environment(first_container(deployment), "SPRING_DATASOURCE_USERNAME") != environment(first_container(deployment), "ACCORD_DATABASE_LOGIN_ROLE")
}

deny contains sprintf("%s lacks its exact PostgreSQL session role", [component(deployment)]) if {
  some deployment in deployments
  environment(first_container(deployment), "SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL") != sprintf("SET ROLE %s", [environment(first_container(deployment), "ACCORD_DATABASE_SESSION_ROLE")])
}

deny contains sprintf("%s does not configure OTLP TLS and CA", [component(deployment)]) if {
  some deployment in deployments
  not startswith(environment(first_container(deployment), "OTEL_EXPORTER_OTLP_ENDPOINT"), "https://")
}

deny contains sprintf("%s does not configure OTLP TLS and CA", [component(deployment)]) if {
  some deployment in deployments
  environment(first_container(deployment), "OTEL_EXPORTER_OTLP_CERTIFICATE") == ""
}

deny contains "control-worker lacks exact Temporal namespace, queue, or mTLS configuration" if {
  some deployment in deployments
  component(deployment) == "control-worker"
  some name in {"ACCORD_TEMPORAL_NAMESPACE", "ACCORD_TEMPORAL_RECONCILIATION_TASK_QUEUE", "ACCORD_TEMPORAL_SERVER_NAME", "ACCORD_TEMPORAL_SECRET_ROOT", "ACCORD_TEMPORAL_CLIENT_CERTIFICATE", "ACCORD_TEMPORAL_CLIENT_PRIVATE_KEY", "ACCORD_TEMPORAL_TRUST_CERTIFICATE"}
  environment(first_container(deployment), name) == ""
}

deny contains "control-worker Temporal endpoint does not require TLS" if {
  some deployment in deployments
  component(deployment) == "control-worker"
  not startswith(environment(first_container(deployment), "ACCORD_TEMPORAL_ENDPOINT"), "grpcs://")
}

deny contains "webhook-edge lacks its ExternalSecret binding projection" if {
  some deployment in deployments
  component(deployment) == "webhook-edge"
  not startswith(environment(first_container(deployment), "ACCORD_WEBHOOK_EDGE_BINDINGS_FILE"), "/var/run/accord/secrets/")
}

deny contains sprintf("%s has a broad default egress CIDR %s", [component(policy), peer.ipBlock.cidr]) if {
  some policy in network_policies
  some rule in object.get(policy.spec, "egress", [])
  some peer in object.get(rule, "to", [])
  peer.ipBlock.cidr in {"0.0.0.0/0", "::/0"}
}

deny contains sprintf("%s egress overlaps cloud metadata %s", [component(policy), peer.ipBlock.cidr]) if {
  some policy in network_policies
  some rule in object.get(policy.spec, "egress", [])
  some peer in object.get(rule, "to", [])
  contains(peer.ipBlock.cidr, "169.254.169.254")
}

deny contains sprintf("%s has namespace-only egress", [component(policy)]) if {
  some policy in network_policies
  some rule in object.get(policy.spec, "egress", [])
  some peer in object.get(rule, "to", [])
  object.get(peer, "namespaceSelector", {}) != {}
  object.get(peer, "podSelector", {}) == {}
}

deny contains sprintf("%s has namespace-only ingress", [component(policy)]) if {
  some policy in network_policies
  some rule in object.get(policy.spec, "ingress", [])
  some peer in object.get(rule, "from", [])
  object.get(peer, "namespaceSelector", {}) != {}
  object.get(peer, "podSelector", {}) == {}
}

deny contains sprintf("%s standard NetworkPolicy claims a CILIUM_FQDN route", [component(policy)]) if {
  some policy in network_policies
  object.get(object.get(policy.metadata, "annotations", {}), "accord.inforvans.com/route-mode", "") == "CILIUM_FQDN"
}

deny contains sprintf("%s Cilium policy has a wildcard FQDN", [component(policy)]) if {
  some policy in cilium_policies
  some rule in policy.spec.egress
  some fqdn in rule.toFQDNs
  contains(fqdn.matchName, "*")
}

deny contains sprintf("%s AUDITED_CIDR lacks immutable evidence", [component(policy)]) if {
  some policy in network_policies
  annotations := object.get(policy.metadata, "annotations", {})
  object.get(annotations, "accord.inforvans.com/route-mode", "") == "AUDITED_CIDR"
  not regex.match("^sha256:[0-9a-f]{64}$", object.get(annotations, "accord.inforvans.com/cidr-evidence-digest", ""))
}

deny contains "Network policy cannot be TLS evidence or report TLS PASS" if {
  some policy in array.concat(network_policies, cilium_policies)
  some key, value in object.get(policy.metadata, "annotations", {})
  contains(lower(key), "tls")
  upper(value) in {"PASS", "TRUE", "VERIFIED"}
}
