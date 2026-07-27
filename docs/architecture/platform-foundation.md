# Accord Platform Foundation

## Status

This document records the executable Foundation architecture. It is not a production-environment
attestation. A release is eligible only when Task 17 emits matching `CODE` and `ENVIRONMENT`
verdicts with status `PASS` for the same authoritative remote SHA, tree, image lock, release
manifest, and evidence-policy version. Missing external authority remains `BLOCKED`; broken or
missing repository code is `FAIL`.

## Runtime Boundaries

The control plane is Java 21 with Spring Boot and Spring Modulith. `accord-control-api` and
`accord-control-worker` share bounded domain modules but are separate executable artifacts,
process identities, database login/session roles, ServiceAccounts, secrets, and network profiles.
The worker is a non-web process. The GitLab Webhook Edge is an independently deployable Java
service and has no control-plane module dependency.

Security services remain separate processes. Language consolidation never consolidates authority.
The Python 3.12 Agent Runtime consumes source-free structured projections and cannot become a
source-code or Git-credential boundary.

## Durable Authority

PostgreSQL is the sole business fact and coordination database. Forced row-level security applies
tenant isolation after an exact login role changes to its NOLOGIN session role. The database owns
idempotency results, expected-version state, domain events, outbox/inbox messages, tenant permits,
message fences, immutable delivery/handler receipts, external-intent fencing, and cleanup
authority. Redis, Kafka, and process-local caches cannot decide authorization, replay protection,
leases, or workflow state.

Cross-tenant scheduling reads only the payload-free `reliability_tenant_work` directory. A worker
must acquire its tenant permit before installing tenant context and before touching an outbox or
inbox message. Permit and message operations compare owner, monotonically increasing generation,
opaque token, exact prior database deadline, and database liveness. External delivery occurs after
the lease transaction commits; a durable receipt commits before a separate acknowledgement.

## Temporal Boundary

Temporal is orchestration progress, not business authority. Workflow inputs, history, heartbeats,
and results carry `ReconciliationWorkflowRef` identifiers and closed outcomes only. Every activity
attempt reloads PostgreSQL authority. The production `FencedReconciliationObservation` executes
transaction 1 for snapshot/fence acquisition, performs the read-only Provider observation with no
JDBC transaction open, and executes transaction 2 for fenced completion or unknown resolution.

The local topology runs a real Temporal 1.28.1 server with independent schema/runtime PostgreSQL
identities and mandatory frontend TLS 1.3 mutual authentication. Worker, UI, namespace admin,
server, and internode identities have separate keys and narrowly scoped EKUs. The worker verifies
the server name `temporal`. Plaintext, absent client identity, wrong CA, wrong name, and wrong EKU
must fail before a Temporal response.

## Telemetry Boundary

Application code can use only the closed `TelemetryAttributes`, `TelemetryIdentifiers`, operation,
provider, and result-code types from `libs/java/observability`. Only that library constructs OTel
attributes and SDK exporters. Metrics cannot carry tenant, scope, correlation, causation, route,
aggregate, or event identifiers.

Span, metric, and log exporters apply a final whole-record guard after automatic instrumentation.
Unknown keys, invalid types/values, headers, cookies, authorization values, tokens, PEM/JWT data,
raw URLs, query strings, exception content, and configured sentinels drop the complete record or
metric point. The fixed-cardinality drop counters contain only signal and closed reason. Export
failure, bounded-queue saturation, timeout, shutdown, or collector outage cannot fail business
work or readiness. The local collector has no debug exporter and writes bounded ignored JSONL.

## Deployment Authority

The deployment contract has exactly `control-api`, `control-worker`, and `webhook-edge` keys. Each
key cross-binds one artifact digest, ServiceAccount, workload-identity provider/subject/audience,
ExternalSecret reference, database secret, login/session role, non-root UID/GID, probes, TLS
references, and closed network profile. ServiceAccount token automount is disabled; any projected
token has the component's exact audience and bounded lifetime. No credential value enters Helm,
OpenTofu state inputs, Argo manifests, environment evidence, or Git.

Standard NetworkPolicy proves only L3/L4 reachability. PostgreSQL verify-full, Temporal mTLS, and
OTLP/HTTP over TLS on port 4318 requires separate application and handshake evidence. Destinations use one closed route
mode: Kubernetes service selectors, an exact egress gateway, an exact Cilium FQDN, or a bounded
audited CIDR with owner, expiry, and evidence digest. Foundation grants no Provider/content/object
storage egress.

The OpenTofu contract module validates normalized nonsensitive bindings and creates no fake cloud
resource. The local Kubernetes adapter creates a real namespace and Helm release. Argo CD accepts
only an immutable lowercase commit SHA verified against the authoritative Accord source remote;
it never deploys a branch, tag, `HEAD`, or a local-only commit.

## Source And Provider Semantics

GitHub may host the Accord source repository and thin GitHub Actions adapters. Only the GitHub CI
adapter reads `GITHUB_*` values. Product Git behavior is GitLab-first: the Webhook Edge validates
GitLab 19.1 requests and provider facts, and no GitHub product token or endpoint enters the control
plane, Argo, or product fixtures.

## Supply Chain

`infra/images/images.lock.json` is the sole third-party image lock. Compose and runtime Dockerfiles
consume exact canonical repository digests from it. Provider-neutral core scripts consume a closed
CI context rather than CI environment aliases. Builds use locked offline Gradle inputs and
network-disabled BuildKit stages; runtime images are shell-free, non-root, and read-only-root
compatible.

When direct Docker Hub access is unavailable, local lock maintenance may read the same immutable
manifests through the explicitly approved `docker.m.daocloud.io` mirror. The lock retains official
canonical repositories and records mirror provenance. MinIO approval is time-bounded and
`local-foundation-only`; it is never production object-storage authority. Production object
capability remains an independently verified environment receipt, including the configured OSS
service, retention, isolation, and recovery evidence.

Every source, runtime image, and `accordctl` archive is addressed by digest and receives CycloneDX
and SPDX SBOMs plus exact-subject scan evidence. HIGH/CRITICAL findings require a separately signed,
unexpired exception bound to that finding and subject. Cosign keyless signatures, closed DSSE
in-toto provenance, ORAS referrers, and the release manifest bind the same digest chain. Tags never
serve as verification subjects.

## Acceptance

Acceptance resolves one full remote ref/SHA using `git ls-remote`, fetches that object, verifies its
tree, and creates an OS-temporary detached clean worktree. All checks run there without changing
tracked source. The code verdict independently records tools and repository assertions; the
environment verdict independently verifies branch protection, mirrors, OIDC, registry/referrers,
Kubernetes, Argo, PKI, external secrets, database recovery, object capabilities, and platform
signing receipts. Overall `PASS` requires both verdicts to pass with byte-equal bindings.
