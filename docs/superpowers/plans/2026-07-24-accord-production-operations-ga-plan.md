# Accord Production Operations and GA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Operate the complete Accord Enterprise V1 as a measurable, recoverable, supply-chain-verifiable service and derive each certification unit's release status from immutable product, security, reliability, model, and value evidence.

**Architecture:** The Java 21 control plane, independently deployed Java security/edge services, Java Agent Pack download gateway, self-contained Java/Picocli CLI, Python Agent Runtime, and React web client emit one redaction-safe OpenTelemetry model while retaining separate workload identities and failure domains. Kubernetes/Helm/Argo CD deploy immutable signed artifacts and OpenTofu provisions parameterized infrastructure; PostgreSQL is the only transactional database technology and remains authoritative for capability issuance, consumption, leases, fencing, and certification coordination. Backup/DR evidence is independently verified, and no operational system may authorize a business action. AWS Tokyo primary plus Osaka warm DR is the first certified reference profile, not an application or contract assumption.

**Tech Stack:** Java 21/Spring Boot 3.5.3/Spring Modulith/Gradle Groovy DSL, Picocli/jlink, Python 3.12/Pydantic v2, React 19.1/TypeScript 5.8/Vite 7, PostgreSQL 17.5, Temporal, private OSS/S3-compatible object storage, OpenTelemetry Collector, Prometheus-compatible metrics, Grafana, CloudWatch-compatible logs, Tempo-compatible traces, Kubernetes, Helm, OpenTofu, Argo CD, k6, LitmusChaos, Toxiproxy, Playwright, Testcontainers, JUnit 5, AssertJ, jqwik, pytest, Syft, Grype, Cosign, CycloneDX, SLSA provenance, OPA/Conftest, and GitHub Actions with OIDC.

---

## Relationship To The Master Plan

The cross-cutting constraints in this plan apply from M0, while the Master plan integrates Tasks 1-10 as one ordered prefix at M5 and Tasks 11-14 at M6. Earlier feature tasks must already emit correlation-safe telemetry, declare failure and reconciliation behavior, stay within the source-data boundary, and contribute capacity and operational evidence; this does not authorize executing Operations tasks out of the Master sequence. Completing operational automation cannot compensate for an incomplete product journey or a failed trust invariant.

## Reference Deployment Profile

The following is certification data for the first supported production unit. Reusable modules take region, account, network, retention, capacity, and data-processing settings as inputs; only the two named environment directories pin Japan regions.

| Layer | Tokyo primary `ap-northeast-1` | Osaka warm DR `ap-northeast-3` | Authority and recovery rule |
|---|---|---|---|
| Compute | EKS across three AZs; separate edge, API, worker, Agent, Agent Pack gateway, scanner, and security-service identities | warm EKS capacity with signed images/configuration pre-staged | Argo CD deploys an exact release digest; no mutable tags or independently installed duplicate gateway release |
| Database | purpose-separated RDS PostgreSQL 17 Multi-AZ clusters for business facts, Temporal, and certification coordination | encrypted cross-region log/replica streams with independent restore targets | PostgreSQL facts, audit, outbox, durable claims, leases, and fencing tokens are authoritative |
| Attachments | private S3, versioning, malware quarantine, policy-controlled object lock | independent cross-region object replication | attachment RPO and restore integrity are reported separately from database RPO |
| Process-local cache | bounded in-memory immutable projections only | recreated empty | never participates in authorization, replay defense, leases, fencing, or cross-replica coordination |
| Keys | purpose-separated regional KMS keys and workload identities | region-local recovery keys with signed trust records | key history and validity overlays decide whether evidence remains usable |
| Telemetry | redundant collectors and metrics/log/trace sinks | independent health sink plus versioned dashboards/alerts | telemetry can page or block a release; it cannot approve, merge, accept, or complete |

## SLOs And Zero-Tolerance Outcomes

| Indicator | Production objective | Evidence window |
|---|---|---|
| ordinary browser/API latency | p95 at most 2 seconds, excluding Agent, Git, CI, artifact, and notification work | rolling 28 days and per release |
| ActionRequest visibility | p95 at most 3 seconds from committed business transaction | rolling 28 days and per release |
| webhook gap or standard bypass detection | no more than 15 minutes | continuous and quarterly drill |
| confirmed transaction in declared synchronous multi-AZ domain | RPO 0 | each failover drill |
| unrecoverable regional loss | control-plane RPO at most 5 minutes | quarterly regional drill |
| severe regional loss | core-control-plane RTO at most 4 hours | quarterly regional drill |
| strict normal merge actor | 100% Merge Controller | continuous and each release |
| duplicate business action | 0 | continuous |
| incorrect `completed` state | 0 | continuous |
| cross-tenant disclosure | 0 | continuous |
| Agent Pack warm verified-cache first byte | p95 at most 2 seconds | rolling 28 days and per release |
| Agent Pack cold OCI fetch through complete verification | p95 at most 45 seconds; measured separately from warm cache and never allowed to consume an expired capability | rolling 28 days and per release |
| Agent Pack capability multi-consume, unverified-byte stream, raw-token exposure, or OCI-credential exposure | 0 | continuous |

External Agent, provider, CI, artifact, and notification paths expose queue and execution p50/p95 separately. Agent Pack metrics label only the closed `warm_verified_cache` or `cold_oci` fetch class and separately report cache lookup, OCI fetch, full verification, atomic capability claim, first byte, and completed stream; they never label by token, actor, release digest, or registry credential. External time must not be hidden inside the platform API SLO. The UI shows queued/retrying/reconciling states rather than pretending a remote operation is synchronous.

## Operational Evidence Contract

Certification evidence is immutable and content-addressed under `certification/evidence/sha256-<bundle-hex>/<certification-run-id>/<certification-unit-id-or-global>/`; signed payloads retain the canonical `sha256:<bundle-hex>` digest, and global evidence uses the literal `global` scope beneath the same run directory, never a run-free sibling. Reusable continuous-operations source reports remain immutable source objects and cannot become certification evidence merely by being copied beneath that prefix. The verifier, not an operator, derives `experimental`, `limited_availability`, or `ga`. Every SLO, capacity, restore, DR, key-lifecycle, chaos, contractual-terms, collection, and verification artifact uses a closed JSON Schema 2020-12 payload, RFC 8785 JCS, DSSE PAE, a purpose-bound platform KMS signature, and a separately stored immutable audit receipt. A command may persist a truthful failed report, but only independently reverified business predicates can satisfy a release or GA gate; neither an artifact's `result` nor an operator assertion is trusted.

The platform evidence trust domain is independent of tenant signing. It has separate AWS accounts/roles, KMS key policies, trust bundles, object stores, and audit anchors; `security-services/signing-service`, tenant key-purpose tables, and `V090__key_validity_overlay.sql` are never called while producing or verifying operations evidence. `staging-operations`, `ga-collector`, and `ga-verifier` each have two non-interchangeable identities: a protected GitHub-environment OIDC orchestration role that may launch and observe only that lane's immutable Kubernetes Job, and an in-cluster IRSA role that may run only that job, use only that lane's purpose-bound KMS key, and write only that lane's run-scoped object prefix. GitHub OIDC roles cannot call `kms:Sign` or write evidence, IRSA roles cannot request GitHub tokens or launch another lane, and every receipt binds both identities plus the immutable Job UID/image/command digest. The first IRSA lane signs drill reports and run-evidence-binding wrappers under distinct payload purposes, the second signs only evidence indexes, and the third signs only derived verification reports.

Approval and promotion are two further trust domains outside that three-job certification DAG. The fixed `ga-approval-attestor` OIDC/IRSA lane may create approval sessions and sign only approval-session and finalized approval-record payloads with two distinct purpose keys; workforce submitters receive no platform signing key and can only conditionally append their own DPoP-bound one-time transaction receipt. Promotion has no shared ServiceAccount: every certification unit has a distinct protected GitHub environment, orchestration role, Kubernetes ServiceAccount/IRSA role, KMS key family, run/unit object prefix, coordination partition, and Argo RBAC binding restricted to that unit's exact Application `resourceName` and UID. The approval lane cannot mutate Argo; a promoter cannot approve, collect, verify, or mutate another unit. A dedicated KMS-encrypted, PITR-enabled PostgreSQL coordination database with a single-writer role and unit-partitioned row-level policies holds only closed approval session/submission/finalization claims and promotion dispatch/claim/lease/fencing state; serializable transactions, unique constraints, compare-and-swap versions, and monotonic fencing tokens provide conditional-write semantics. Immutable signed records, attempt/resolution receipts, and their audit receipts remain in COMPLIANCE Object Lock. Contract evidence uses two additional identities: tag/release-bound `contract-terms-package-publisher` signs only the stable tenant-neutral package, while protected runtime `contract-acceptance-attestor` validates customer authority/e-sign evidence and signs only unit-scoped acceptance attestations. No identity can use another purpose's key, mutate evidence it verifies, or both authorize and execute a GA promotion.

Every report payload embeds this common metadata in addition to its report-specific fields:

```yaml
schema_version: "1.0"
report_type: string
certified_release_bundle_digest: "sha256:<64 lowercase hex>"
source_commit_sha: "<40 lowercase hex>"
immutable_environment_id: string
logical_environment: string
tool_artifact_digest: "sha256:<64 lowercase hex>"
policy_digest: "sha256:<64 lowercase hex>"
input_set_digest: "sha256:<64 lowercase hex>"
evidence_index_digest: "sha256:<64 lowercase hex>"
started_at: "RFC3339 timestamp"
completed_at: "RFC3339 timestamp"
result: pass | fail | inconclusive
audit_correlation_id: "UUID"
retention_until: "RFC3339 timestamp, at least completed_at + 400 days"
```

`input_set_digest` binds the exact query catalog, load profile, restore target, DR matrix, key drill, or fault matrix used by that report. `evidence_index_digest` closes the already persisted raw observations and cannot include the report envelope itself. After signing, the evidence writer stores the envelope in compliance-mode object lock and obtains an object version before appending the audit event. The independent receipt schema binds `audit_correlation_id`, envelope digest, bucket/key/object version, retention mode/time, KMS key ARN and purpose, signer workload identity, audit event identifier, and receipt timestamp; final-certification receipts additionally require `certification_run_id`, run-scoped object prefix, GitHub OIDC orchestrator identity, Kubernetes Job UID/image/command digest, and IRSA signer subject. The common report payload above is reusable operational source evidence and is not implicitly certification-run-bound. Before Task 14 can index such a report, the `staging-operations` IRSA lane independently verifies its envelope and receipt and signs a closed certification wrapper that binds the canonical `certification_run_id`, stage and scope, source envelope digest, immutable source object version, source receipt digest, release bundle, source commit, immutable environment, exact protected restore point, and signed Provider baseline digest. Verifiers resolve and rehash both wrapper and source object; a copied report, unsigned path association, or wrapper/source/run/input mismatch fails. Verifiers also verify the platform trust bundle at each signing time and current compromise state and reject a missing, mutable, expired, mismatched, tenant-signed, cross-run, or self-referential artifact.

```yaml
schema_version: "1.0"
certification_run_id: "UUID"
certification_unit_id: string
certified_release_bundle_digest: "sha256:<64 lowercase hex>"
source_commit_sha: "<40 lowercase hex>"
generated_at: "RFC3339 timestamp"
dimensions:
  delivery_mode: standard | strict
  provider_profile: string
  language_framework_analyzer: string
  ci_artifact_profile: immutable_artifact | source_tree_only
  repository_envelope: string
  deployment_profile: string
artifacts:
  contract_and_e2e: "sha256:<64 lowercase hex>"
  security_and_source_boundary: "sha256:<64 lowercase hex>"
  sbom_provenance_and_vulnerability: "sha256:<64 lowercase hex>"
  slo_and_capacity: "sha256:<64 lowercase hex>"
  restore_and_regional_dr: "sha256:<64 lowercase hex>"
  key_lifecycle: "sha256:<64 lowercase hex>"
  fault_convergence_dsse: "sha256:<64 lowercase hex>"
  operational_readiness: "sha256:<64 lowercase hex>"
  model_evaluation: "sha256:<64 lowercase hex>"
  value_report: "sha256:<64 lowercase hex>"
  role_documentation: "sha256:<64 lowercase hex>"
  contractual_terms: "sha256:<64 lowercase hex>"
derived_status: experimental | limited_availability | ga
```

## Operational Ownership Boundaries

- Java control-plane instrumentation and operational facts live under `apps/control-plane/modules/**`; no Node server package is introduced.
- Java telemetry adapters live in bounded `libs/java/observability/**`; signing, publishing, merging, edge, scanner, `agent-pack-gateway`, and `accordctl` retain separate Gradle subprojects, packages, deployables, and workload identities.
- Python model evaluation and Agent instrumentation live under `apps/agent-runtime/**` and consume only typed, authorized projections.
- Browser telemetry and product metric views live under `apps/web/**`; raw user/attachment text is never exported.
- Flyway migrations live under `database/control-plane/migrations/**` or a service-specific database directory.
- Infrastructure lives under `infra/opentofu/**`, `infra/helm/**`, `infra/argocd/**`, and `infra/policy/**`. `tofu` is the only IaC command in this plan.

## OpenTofu Root, Backend, And Lock Contract

OpenTofu `1.9.1` from the foundation-owned `.tool-versions` is mandatory. `infra/opentofu/` is an executable, local-backend integration-test root; `infra/opentofu/bootstrap/aws-state-backends/` is an independently governed deployable root that provisions the Tokyo and Osaka remote-state buckets, native S3 lock files, KMS keys, access logs, recovery roles, and retention policy from a pre-existing organization-governance backend; `infra/opentofu/environments/aws-tokyo-primary/` and `infra/opentofu/environments/aws-osaka-warm-dr/` are independently initialized deployable roots with partial S3 backend configuration. The bootstrap root cannot use either backend it creates. Production backend values are supplied from access-controlled CI environment files, never committed or passed as secrets on the command line; all roots have separate encrypted state objects, conditional-create lock objects, purpose-separated KMS keys, state-access audit logging, versioning, least-privilege plan/apply identities, and cross-account break-glass recovery ownership.

`infra/opentofu/roots.schema.json` is a closed JSON Schema and `infra/opentofu/roots.json` is its machine-readable instance. Each root has exactly `id`, `path`, `backend`, `lock`, `tests`, `providers`, and `modules`; unknown or missing fields fail. By the end of Task 6 it names exactly `integration-test`, `aws-state-backends`, `aws-tokyo-primary`, and `aws-osaka-warm-dr`; any identifier outside those four literal IDs is rejected. By the end of Task 7 its module-coverage union is exactly `accord-autoscaling`, `accord-foundation-contract`, `aws-agent-pack-distribution`, `aws-backup`, `aws-budgets`, `aws-container-registry`, `aws-edge`, `aws-eks`, `aws-network`, `aws-object-storage`, `aws-operations-evidence`, `aws-postgresql`, `aws-purpose-keys`, `aws-regional-dr`, `aws-state-backends`, `aws-telemetry`, `aws-temporal`, and `aws-workload-identity`. The verifier parses root HCL with a pinned Java HCL parser executed by `:tests:infrastructure`, resolves every static local `module.source`, and requires each root's declared sorted `modules` array to equal its actual sources exactly. A directory with `backend.tf` outside the inventory, an inventory entry without a root, a dynamic/non-local module source, a discovered module absent from the union, a production module sourced only by the integration root, a missing/untracked/stale provider lock, a mutable provider constraint, a path outside the repository, an initialization change, or a lock diff fails verification.

Every root owns and commits its own `.terraform.lock.hcl`, generated only with its Task 4 or Task 6 dual-platform command. `scripts/ci/verify-opentofu.ps1` always runs `tofu init -backend=false -input=false -lockfile=readonly` before `fmt -check`, `validate`, or any separately requested `tofu test`. Certification and CI never use `-upgrade` and never create or update locks. `.github/CODEOWNERS` requires both Platform Operations and Product Security review for root constraints, backend declarations, the inventory, the verifier, and every provider lock. `.gitignore` ignores `**/.terraform/`, state, and crash output while deliberately leaving all `.terraform.lock.hcl` files trackable.

Production changes use two protected workflows. The plan job pins the source commit and tool/image digest, initializes with the protected backend file, emits one saved binary plan plus JSON form, runs policy/security/cost scans, signs a plan-evidence payload, and stores the plan and evidence immutably. The apply job requires two distinct approvals from Platform Operations and Product Security, downloads the exact plan by digest, re-verifies source/tool/config/backend/plan bindings, and runs only `tofu apply <saved-plan>`. It never creates a new plan. Completion evidence binds the applied plan digest, remote state lineage and serial before/after, state object version, nonsensitive output digest, actor/workload identity, timestamps, and audit receipt; a changed state serial, stale plan, replacement outside policy, or uncertain apply blocks promotion and requires reconciliation.

## `accordctl` Command Ownership

The foundation plan creates `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java` and the Picocli command tree. Every task below that exposes an executable operation must register its `@Command` implementation in that one tree and create its handler in the owning Java package; internal leaf packages alone do not count as a command implementation. `:cmd:accordctl:jlink` and `jlinkZip` produce the self-contained launchers used by every operational workflow.

| Command family | Owning task/package |
|---|---|
| `ops data-boundary` | Task 2 / `com.inforvans.accord.cli.ops.databoundary` |
| `ops slo` | Task 3 / `com.inforvans.accord.cli.ops.slo` |
| `ops capacity` | Task 4 / `com.inforvans.accord.cli.ops.capacity` |
| `ops iac` | Task 4 / `com.inforvans.accord.cli.ops.iac` |
| `ops restore` | Task 5 / `com.inforvans.accord.cli.ops.restore` |
| `ops dr` | Task 6 / `com.inforvans.accord.cli.ops.dr` |
| `ops keys` | Task 7 / `com.inforvans.accord.cli.ops.keys` |
| `release build|verify` | Task 8 / `com.inforvans.accord.cli.release` |
| `ops chaos` | Task 9 / `com.inforvans.accord.cli.ops.chaos` |
| `ops runbooks|synthetic|incident` | Task 10 / matching `com.inforvans.accord.cli.ops` packages |
| `ga value-evidence` | Task 12 / `com.inforvans.accord.cli.ga` |
| `release manifest|promote|inject-stop|rollback` | Task 13 / `com.inforvans.accord.cli.release` |
| `ga rehearse|collect|verify` | Task 14 / `com.inforvans.accord.cli.ga` |
| `contract terms publish-package|attest-acceptance` | Task 14 / `com.inforvans.accord.cli.terms` |

Registry tests enumerate the exact command tree, reject duplicates and undocumented aliases, require deterministic exit codes (`0` success, `1` gate failure, `2` invalid invocation, `3` uncertain external result), and verify that JSON output is schema-versioned and stdout never contains credentials or customer content. Destructive or externally mutating commands require the explicit confirmation flags shown in their task; command registration cannot weaken the underlying authorization or evidence gate.

### Task 1: Define Redaction-Safe OpenTelemetry Conventions And Correlation

**Files:**
- Modify: `apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platformkernel/TelemetrySanitizer.java`
- Create: `apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platformkernel/AccordTelemetry.java`
- Create: `apps/control-plane/modules/platform-kernel/src/test/java/com/inforvans/accord/platformkernel/AccordTelemetryTest.java`
- Modify: `libs/java/observability/build.gradle`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryAttributes.java`
- Create: `libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryAttributesTest.java`
- Modify: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/telemetry/WebhookTelemetry.java`
- Modify: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhook/telemetry/WebhookTelemetryTest.java`
- Create: `apps/agent-pack-gateway/src/main/java/com/inforvans/accord/agentpack/telemetry/AgentPackTelemetry.java`
- Create: `apps/agent-pack-gateway/src/test/java/com/inforvans/accord/agentpack/telemetry/AgentPackTelemetryTest.java`
- Modify: `apps/agent-pack-gateway/src/main/java/com/inforvans/accord/agentpack/http/AgentPackDownloadController.java`
- Modify: `apps/agent-pack-gateway/src/test/java/com/inforvans/accord/agentpack/http/AgentPackDownloadControllerTest.java`
- Create: `apps/agent-runtime/src/accord_agent/observability.py`
- Create: `apps/agent-runtime/tests/test_observability.py`
- Modify: `apps/web/src/shared/telemetry/rum.ts`
- Create: `apps/web/src/shared/telemetry/semantic-convention.test.ts`
- Create: `infra/helm/accord/files/otel/collector.yaml`
- Create: `infra/helm/accord/templates/otel-collector-configmap.yaml`

- [ ] **Step 1: Write failing convention and propagation tests in every runtime**

Assert that commands, events, outbox/inbox deliveries, external intents, provider calls, Agent jobs/results, Context Patches, Agent Pack capability resolution/cache lookup/OCI fetch/verification/claim/stream, publication, merge, acceptance, promotion, reconciliation, and browser navigation propagate `correlation_id` and `causation_id`. Gateway spans must use only the closed `warm_verified_cache|cold_oci` fetch class and stage/outcome enums; they never attach a raw or hashed capability token, capability URL, actor credential, OCI authorization header, registry credential, artifact bytes, cache-object metadata body, or release digest. Assert that tenant/project/repository identifiers are HMAC-hashed at telemetry export and that source, attachment text, prompts, tokens, secrets, email, phone, Git URL, and arbitrary payload serialization are rejected.

- [ ] **Step 2: Run the convention tests before adapters exist**

Run: `./gradlew :apps:control-plane:modules:platform-kernel:test --tests '*AccordTelemetryTest' && ./gradlew :libs:java:observability:test :apps:webhook-edge:test :apps:agent-pack-gateway:test && uv run --project apps/agent-runtime pytest apps/agent-runtime/tests/test_observability.py -q && corepack pnpm@10.12.4 --filter @accord/web test -- src/shared/telemetry/semantic-convention.test.ts`

Expected: FAIL because the production semantic-convention extension, bounded Java observability module, Python adapter, and web convention test do not exist.

- [ ] **Step 3: Implement one semantic convention and collector policy**

Use this Java contract as the canonical names and mirror the constants in generated Python and TypeScript adapters:

```java
public final class AccordTelemetry {
    public static final String CORRELATION_ID = "accord.correlation_id";
    public static final String CAUSATION_ID = "accord.causation_id";
    public static final String TENANT_HASH = "accord.tenant_hash";
    public static final String PROJECT_HASH = "accord.project_hash";
    public static final String REPOSITORY_HASH = "accord.repository_hash";
    public static final String AGGREGATE_TYPE = "accord.aggregate_type";
    public static final String COMMAND_NAME = "accord.command_name";
    public static final String OBJECT_VERSION = "accord.object_version";
    public static final String EXTERNAL_SYSTEM = "accord.external_system";
    public static final String ASSURANCE_MODE = "accord.assurance_mode";
    public static final String OUTCOME = "accord.outcome";
    public static final String AGENT_PACK_FETCH_CLASS = "accord.agent_pack.fetch_class";
    public static final String AGENT_PACK_STAGE = "accord.agent_pack.stage";

    public static final Set<String> EXPORTED_KEYS = Set.of(
        CORRELATION_ID, CAUSATION_ID, TENANT_HASH, PROJECT_HASH, REPOSITORY_HASH,
        AGGREGATE_TYPE, COMMAND_NAME, OBJECT_VERSION, EXTERNAL_SYSTEM, ASSURANCE_MODE, OUTCOME,
        AGENT_PACK_FETCH_CLASS, AGENT_PACK_STAGE
    );

    private AccordTelemetry() {
        throw new AssertionError("No instances");
    }
}
```

The adapters validate `AGENT_PACK_FETCH_CLASS` as `warm_verified_cache|cold_oci` and `AGENT_PACK_STAGE` as `resolve|cache_lookup|oci_fetch|verify|claim|stream`; invalid values are dropped and increment a bounded diagnostic counter. The collector config drops all non-allowlisted `accord.*` attributes, caps accepted strings at 256 characters, rejects raw HTTP query/body and exception local variables, and tail-samples 100% of errors, security/audit/reconciliation spans, Agent Pack verification/claim failures, strict merges, and zero-tolerance counter changes plus a deterministic 10% of successful ordinary traffic. Exporters use workload identity and encrypted endpoints.

- [ ] **Step 4: Verify propagation, generated parity, and Collector configuration**

Run: `./gradlew :apps:control-plane:modules:platform-kernel:test :libs:java:observability:test :apps:webhook-edge:test :apps:agent-pack-gateway:test && uv run --project apps/agent-runtime pytest apps/agent-runtime/tests/test_observability.py -q && corepack pnpm@10.12.4 --filter @accord/web test -- src/shared/telemetry/semantic-convention.test.ts && helm template accord infra/helm/accord | conftest test -p infra/policy -`

Expected: PASS; all runtimes expose identical names and filtering, warm-cache and cold-OCI Gateway stages remain separately observable without high-cardinality or sensitive labels, collector rendering contains no plaintext tenant/project/repository identifiers, and Conftest reports zero denials.

- [ ] **Step 5: Commit telemetry conventions**

```bash
git add apps/control-plane/modules/platform-kernel libs/java/observability apps/webhook-edge/src apps/agent-pack-gateway/src apps/agent-runtime/src/accord_agent/observability.py apps/agent-runtime/tests/test_observability.py apps/web/src/shared/telemetry infra/helm/accord/files/otel infra/helm/accord/templates/otel-collector-configmap.yaml
git commit -m "ops: add cross-runtime telemetry conventions"
```

### Task 2: Enforce Log Redaction And The Customer-Source Data Boundary

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Create: `infra/policy/data-boundary/classification.yaml`
- Create: `infra/policy/data-boundary/redaction.rego`
- Modify: `apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platformkernel/TelemetrySanitizer.java`
- Create: `apps/control-plane/modules/platform-kernel/src/test/java/com/inforvans/accord/platformkernel/TelemetrySanitizerPropertyTest.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryRedactor.java`
- Create: `libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryRedactorTest.java`
- Create: `apps/agent-runtime/src/accord_agent/redaction.py`
- Create: `apps/agent-runtime/tests/test_redaction.py`
- Modify: `apps/web/src/shared/telemetry/redact.ts`
- Modify: `apps/web/src/shared/telemetry/redact.test.ts`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/databoundary/DataBoundaryCommand.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/databoundary/DataBoundaryCommandTest.java`
- Create: `apps/web/tests/e2e/security/source-boundary-canary.spec.ts`
- Create: `docs/operations/runbooks/data-boundary-incident.md`

- [ ] **Step 1: Write failing property, policy, and sink-canary tests**

Generate nested maps, exceptions, protobuf unknown fields, model inputs, browser events, and structured logs containing a unique source fragment, credentials, email/phone, attachment body, prompt injection, Git URL, and high-entropy secret. The test injects the source canary only in a customer-CI fixture, then scans platform PostgreSQL, OSS/S3 object storage, Temporal payload/search attributes, inbox/outbox, model requests, process-local diagnostic snapshots, logs, traces, and metrics.

- [ ] **Step 2: Run the boundary suite before redactors and scanner exist**

Run: `./gradlew :apps:control-plane:modules:platform-kernel:test --tests '*TelemetrySanitizerPropertyTest' && ./gradlew :libs:java:observability:test :cmd:accordctl:test --tests '*DataBoundaryCommandTest' && uv run --project apps/agent-runtime pytest apps/agent-runtime/tests/test_redaction.py -q && corepack pnpm@10.12.4 --filter @accord/web test -- src/shared/telemetry/redact.test.ts`

Expected: FAIL and print the exact unredacted field path and sink class.

- [ ] **Step 3: Implement allowlist serialization and source-boundary policy**

Create the exact classification policy:

```yaml
schema_version: "1.0"
classes:
  public: [service.name, route.template, outcome, error.type]
  internal: [correlation_id, causation_id, aggregate_type, command_name, object_version]
  hashed_identifier: [tenant_id, project_id, repository_id, actor_id, account_id]
  forbidden: [source_text, source_fragment, attachment_text, raw_prompt, token, secret, authorization, cookie, email, phone, git_url]
unknown_field_action: drop_and_increment_accord_redaction_drop_total
maximum_exported_string_length: 256
identifier_transform: hmac-sha256-with-rotating-telemetry-key
```

Java/Python loggers accept typed maps and emit only declared keys; browser RUM retains route templates, web-vital values, outcome, and opaque correlation ID. `accordctl ops data-boundary scan` uses read-only workload identities, produces per-sink counts, hashes the report, requests an operations-evidence DSSE signature, and never prints matched secret content.

- [ ] **Step 4: Verify redaction and zero source-canary persistence**

Run: `./gradlew :apps:control-plane:modules:platform-kernel:test :libs:java:observability:test :cmd:accordctl:test :cmd:accordctl:installDist && uv run --project apps/agent-runtime pytest apps/agent-runtime/tests/test_redaction.py -q && corepack pnpm@10.12.4 --filter @accord/web test && corepack pnpm@10.12.4 --filter @accord/web test:e2e -- tests/e2e/security/source-boundary-canary.spec.ts && cmd/accordctl/build/install/accordctl/bin/accordctl ops data-boundary scan --environment test --expect-zero`

Expected: property tests pass; the CLI prints one line per declared sink with `forbidden_matches=0`; source-canary and cross-tenant-disclosure counters remain zero.

- [ ] **Step 5: Commit source-boundary controls**

```bash
git add infra/policy/data-boundary apps/control-plane/modules/platform-kernel libs/java/observability apps/agent-runtime/src/accord_agent/redaction.py apps/agent-runtime/tests/test_redaction.py apps/web/src/shared/telemetry apps/web/tests/e2e/security/source-boundary-canary.spec.ts cmd/accordctl/src/main/java/com/inforvans/accord/cli cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/databoundary docs/operations/runbooks/data-boundary-incident.md
git commit -m "security: enforce telemetry and source data boundaries"
```

### Task 3: Implement SLIs, SLOs, Dashboards, Alerts, And Error Budgets

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Create: `apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platformkernel/AccordMetrics.java`
- Create: `apps/control-plane/modules/platform-kernel/src/test/java/com/inforvans/accord/platformkernel/SloMetricTest.java`
- Create: `operations/slos/control-plane.yaml`
- Create: `operations/slos/external-dependencies.yaml`
- Create: `operations/alerts/platform.rules.yaml`
- Create: `operations/alerts/security.rules.yaml`
- Create: `operations/alerts/reconciliation.rules.yaml`
- Create: `operations/dashboards/control-plane.json`
- Create: `operations/dashboards/delivery-integrity.json`
- Create: `operations/dashboards/external-dependencies.json`
- Create: `contracts/json-schema/operations-evidence-metadata.schema.json`
- Create: `contracts/json-schema/immutable-evidence-receipt.schema.json`
- Create: `contracts/json-schema/operations-slo-report.schema.json`
- Create: `operations/evidence/platform-trust-policy.yaml`
- Create: `operations/slos/report-signature-policy.yaml`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/evidence/Model.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/evidence/Kms.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/evidence/Store.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/evidence/Verify.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/evidence/EvidenceTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/slo/Report.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/slo/ReportTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/slo/Validate.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/slo/ValidateTest.java`

- [ ] **Step 1: Write failing SLO math and configuration tests**

Test histogram buckets/quantiles, API and browser route-template latency, transaction-commit-to-ActionRequest-visible duration, webhook/bypass detection lag, strict merge actor ratio, duplicate/wrong-completion/cross-tenant counters, availability, missing series, multi-window burn rate, and separation of external queue versus execution time. Require an owner, dashboard, runbook, query, objective, window, and release-gate behavior for every SLO. Add report fixtures proving that a shortened or discontinuous 28-day range, missing scrape interval, changed query catalog, synthetic-only samples, wrong immutable environment, wrong release/tool/policy digest, failed objective relabeled `pass`, tenant-signing key, mutable object, mismatched receipt, or expired retention exits `1` and cannot produce a verified summary.

- [ ] **Step 2: Run SLO validation before metrics and policy exist**

Run: `./gradlew :apps:control-plane:modules:platform-kernel:test --tests '*SloMetricTest' :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ops.slo.*'`

Expected: FAIL with missing `accord_api_duration_seconds`, `accord_action_visibility_seconds`, bypass lag, and zero-tolerance counter definitions.

- [ ] **Step 3: Implement explicit SLO and alert policy**

Create `operations/slos/control-plane.yaml`:

```yaml
schema_version: "1.0"
slos:
  - { id: ordinary_api_latency, query: accord_api_duration_seconds, objective: "p95 <= 2s", window: 28d, owner: control-plane }
  - { id: browser_latency, query: accord_browser_route_duration_seconds, objective: "p95 <= 2s", window: 28d, owner: web }
  - { id: action_visibility, query: accord_action_visibility_seconds, objective: "p95 <= 3s", window: 28d, owner: actions-notifications }
  - { id: webhook_or_bypass_detection, query: accord_integrity_detection_lag_seconds, objective: "max <= 900s", window: 28d, owner: delivery-integrity }
zero_tolerance:
  - accord_duplicate_business_action_total
  - accord_wrong_completed_total
  - accord_cross_tenant_disclosure_total
  - accord_strict_non_controller_normal_merge_total
burn_alerts:
  page: { short_window: 5m, long_window: 1h, multiplier: 14.4 }
  ticket: { short_window: 30m, long_window: 6h, multiplier: 6 }
```

Every zero-tolerance increment pages immediately and freezes affected release expansion. Missing zero-tolerance series are alerting, not treated as zero. Dashboards use only hashed identifiers and link to exact runbook versions.

`operations-evidence-metadata.schema.json` and `immutable-evidence-receipt.schema.json` are closed JSON Schema 2020-12 implementations of the Operational Evidence Contract. `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/evidence` provides constructor-validated digest, Git SHA, environment ID, UUID, and timestamp value types; RFC 8785 canonicalization; DSSE PAE; platform KMS signing through an injected `Signer`; compliance-mode object persistence through an injected `ImmutableStore`; receipt resolution; and trust-policy verification. Its production AWS adapters accept only workload identity, KMS ARN, bucket ARN, and object prefix injected by IRSA. They reject tenant signing endpoints and never hold exportable private key bytes.

`operations-slo-report.schema.json` extends the common metadata with the exact 28-day `[window_start, window_end)` interval, Prometheus-compatible server identity, query-catalog digest, scrape interval, expected/observed sample count and coverage, one result per declared objective, zero-tolerance counter deltas, raw-query evidence digests, and overall `result`. `ops slo collect` performs range queries against the configured production telemetry store, records gaps instead of interpolating them, persists raw responses before signing, and returns gate failure when coverage is below `99.9%`, any required series is missing, any objective fails, or any zero-tolerance delta is nonzero. `ops slo verify` independently replays schema, DSSE, platform trust, digest closure, query math, time window, object lock, and receipt checks; it writes a summary only when the report result is `pass`.

- [ ] **Step 4: Validate metrics, rules, dashboards, and ownership**

Run: `./gradlew :apps:control-plane:modules:platform-kernel:test :cmd:accordctl:test :cmd:accordctl:installDist && cmd/accordctl/build/install/accordctl/bin/accordctl ops slo validate --directory operations/slos && promtool check rules operations/alerts/*.yaml && cmd/accordctl/build/install/accordctl/bin/accordctl ops slo collect --environment staging --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --window 28d --as-of "$ACCORD_EVIDENCE_AS_OF" --output build/operations/slo-report.dsse.json --confirm-evidence && cmd/accordctl/build/install/accordctl/bin/accordctl ops slo verify --evidence build/operations/slo-report.dsse.json --summary-output build/operations/slo-report.json`

Expected: PASS; each objective has a valid histogram/query and runbook/owner; all rule files are valid; zero-tolerance missing-series tests alert; the collector reads a complete real 28-day range and the verifier accepts only the platform-signed, immutable, receipt-bound `pass` report.

- [ ] **Step 5: Commit service objectives and alerts**

```bash
git add apps/control-plane/modules/platform-kernel operations/slos operations/alerts operations/dashboards operations/evidence contracts/json-schema/operations-evidence-metadata.schema.json contracts/json-schema/immutable-evidence-receipt.schema.json contracts/json-schema/operations-slo-report.schema.json cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/evidence cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/evidence cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/slo cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/slo
git commit -m "ops: codify Accord SLOs and zero-tolerance alerts"
```

### Task 4: Establish Capacity, Performance, And Cost Guardrails

**Files:**
- Modify: `.gitignore`
- Create: `.github/CODEOWNERS`
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Create: `operations/capacity/reference-envelope.yaml`
- Create: `tests/performance/k6/ordinary-api.js`
- Create: `tests/performance/k6/action-visibility.js`
- Create: `tests/performance/k6/provider-backlog.js`
- Create: `tests/performance/k6/agent-pack-download.js`
- Create: `operations/cost/reference-aws-japan.yaml`
- Create: `operations/cost/allocation-tags.yaml`
- Create: `infra/opentofu/versions.tf`
- Create: `infra/opentofu/backend.tf`
- Create: `infra/opentofu/main.tf`
- Generate and commit: `infra/opentofu/.terraform.lock.hcl`
- Create: `infra/opentofu/roots.json`
- Create: `infra/opentofu/roots.schema.json`
- Create: `infra/opentofu/bootstrap/aws-state-backends/main.tf`
- Create: `infra/opentofu/bootstrap/aws-state-backends/variables.tf`
- Create: `infra/opentofu/bootstrap/aws-state-backends/outputs.tf`
- Create: `infra/opentofu/bootstrap/aws-state-backends/versions.tf`
- Create: `infra/opentofu/bootstrap/aws-state-backends/backend.tf`
- Create: `infra/opentofu/bootstrap/aws-state-backends/backend.hcl.example`
- Generate and commit: `infra/opentofu/bootstrap/aws-state-backends/.terraform.lock.hcl`
- Create: `infra/opentofu/environments/aws-tokyo-primary/main.tf`
- Create: `infra/opentofu/environments/aws-tokyo-primary/variables.tf`
- Create: `infra/opentofu/environments/aws-tokyo-primary/outputs.tf`
- Create: `infra/opentofu/environments/aws-tokyo-primary/versions.tf`
- Create: `infra/opentofu/environments/aws-tokyo-primary/backend.tf`
- Create: `infra/opentofu/environments/aws-tokyo-primary/backend.hcl.example`
- Create: `infra/opentofu/environments/aws-tokyo-primary/environment.auto.tfvars.example`
- Generate and commit: `infra/opentofu/environments/aws-tokyo-primary/.terraform.lock.hcl`
- Create: `infra/opentofu/environments/aws-osaka-warm-dr/main.tf`
- Create: `infra/opentofu/environments/aws-osaka-warm-dr/variables.tf`
- Create: `infra/opentofu/environments/aws-osaka-warm-dr/outputs.tf`
- Create: `infra/opentofu/environments/aws-osaka-warm-dr/versions.tf`
- Create: `infra/opentofu/environments/aws-osaka-warm-dr/backend.tf`
- Create: `infra/opentofu/environments/aws-osaka-warm-dr/backend.hcl.example`
- Create: `infra/opentofu/environments/aws-osaka-warm-dr/environment.auto.tfvars.example`
- Generate and commit: `infra/opentofu/environments/aws-osaka-warm-dr/.terraform.lock.hcl`
- Create: `infra/opentofu/modules/aws-state-backends/main.tf`
- Create: `infra/opentofu/modules/aws-state-backends/variables.tf`
- Create: `infra/opentofu/modules/aws-state-backends/outputs.tf`
- Create: `infra/opentofu/modules/aws-network/main.tf`
- Create: `infra/opentofu/modules/aws-network/variables.tf`
- Create: `infra/opentofu/modules/aws-network/outputs.tf`
- Create: `infra/opentofu/modules/aws-eks/main.tf`
- Create: `infra/opentofu/modules/aws-eks/variables.tf`
- Create: `infra/opentofu/modules/aws-eks/outputs.tf`
- Create: `infra/opentofu/modules/aws-workload-identity/main.tf`
- Create: `infra/opentofu/modules/aws-workload-identity/variables.tf`
- Create: `infra/opentofu/modules/aws-workload-identity/outputs.tf`
- Create: `infra/opentofu/modules/aws-edge/main.tf`
- Create: `infra/opentofu/modules/aws-edge/variables.tf`
- Create: `infra/opentofu/modules/aws-edge/outputs.tf`
- Create: `infra/opentofu/modules/aws-container-registry/main.tf`
- Create: `infra/opentofu/modules/aws-container-registry/variables.tf`
- Create: `infra/opentofu/modules/aws-container-registry/outputs.tf`
- Create: `infra/opentofu/modules/aws-agent-pack-distribution/main.tf`
- Create: `infra/opentofu/modules/aws-agent-pack-distribution/variables.tf`
- Create: `infra/opentofu/modules/aws-agent-pack-distribution/outputs.tf`
- Create: `infra/opentofu/modules/aws-telemetry/main.tf`
- Create: `infra/opentofu/modules/aws-telemetry/variables.tf`
- Create: `infra/opentofu/modules/aws-telemetry/outputs.tf`
- Create: `infra/opentofu/modules/aws-temporal/main.tf`
- Create: `infra/opentofu/modules/aws-temporal/variables.tf`
- Create: `infra/opentofu/modules/aws-temporal/outputs.tf`
- Create: `infra/opentofu/modules/aws-operations-evidence/main.tf`
- Create: `infra/opentofu/modules/aws-operations-evidence/variables.tf`
- Create: `infra/opentofu/modules/aws-operations-evidence/outputs.tf`
- Create: `infra/opentofu/modules/accord-autoscaling/main.tf`
- Create: `infra/opentofu/modules/accord-autoscaling/variables.tf`
- Create: `infra/opentofu/modules/accord-autoscaling/outputs.tf`
- Create: `infra/opentofu/modules/aws-budgets/main.tf`
- Create: `infra/opentofu/modules/aws-budgets/variables.tf`
- Create: `infra/opentofu/modules/aws-budgets/outputs.tf`
- Create: `infra/opentofu/tests/capacity-policy.tftest.hcl`
- Create: `infra/opentofu/tests/production-foundation.tftest.hcl`
- Create: `infra/opentofu/tests/agent-pack-distribution.tftest.hcl`
- Create: `infra/opentofu/tests/ga-control-lanes.tftest.hcl`
- Create: `infra/policy/opentofu/plan.rego`
- Create: `infra/policy/opentofu/plan_test.yaml`
- Create: `.github/workflows/opentofu-plan.yaml`
- Create: `.github/workflows/opentofu-apply.yaml`
- Modify: `tests/infrastructure/build.gradle`
- Modify: `tests/infrastructure/gradle.lockfile`
- Create: `tests/infrastructure/src/main/java/com/inforvans/accord/infrastructure/opentofu/OpenTofuRootVerifier.java`
- Create: `tests/infrastructure/src/main/java/com/inforvans/accord/infrastructure/opentofu/RootInventory.java`
- Create: `tests/infrastructure/src/test/java/com/inforvans/accord/infrastructure/opentofu/RootInventoryTest.java`
- Create: `scripts/ci/verify-opentofu.ps1`
- Create: `scripts/ci/verify-opentofu.Tests.ps1`
- Create: `contracts/json-schema/opentofu-plan-evidence.schema.json`
- Create: `contracts/json-schema/opentofu-apply-evidence.schema.json`
- Create: `contracts/json-schema/operations-capacity-report.schema.json`
- Create: `operations/capacity/report-signature-policy.yaml`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/capacity/Evidence.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/capacity/EvidenceTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/iac/Evidence.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/iac/EvidenceTest.java`

- [ ] **Step 1: Write failing envelope, backpressure, and cost-policy tests**

The reference certification envelope contains 100 concurrent tenants, 50 active browser users per tenant, 200 ordinary API requests/second, 10,000 open ActionRequests, 20 concurrent WorkItems per repository, 100 simultaneous Agent Pack downloads split across warm-cache and cold-OCI paths, and 100,000 repository files represented only by structured metadata. Test API/worker/Agent/publisher/controller/gateway scaling signals, gateway database and stream connection budgets, cache loss, OCI throttle and digest-verification cost, queue age/depth, provider throttling, database connection budget, retry amplification, mandatory allocation tags, and monthly/forecast/anomaly limits. A passing run must prove a slow or disconnected client cannot retain a consumed capability indefinitely, starve API transactions, expose an unverified byte, or make cold-fetch time disappear inside ordinary API latency.

Also create `verify-opentofu.Tests.ps1` as a hermetic temporary-Git-repository harness. Its valid fixture contains an integration root, a production root, one module sourced by both, an exact `= 1.9.1` constraint, exact provider selections, tracked locks, a closed inventory, and a fake `tofu` executable that records invocation order. Add exact negative cases for: an unknown/missing inventory property; duplicate/unsorted root or module arrays; deleted/untracked/stale lock; undeclared `backend.tf`; path/symlink escape; dynamic, registry, or nonexistent `module.source`; declared module absent from actual HCL; actual HCL module absent from that root's declaration; production-required module sourced only by the integration root; mutable OpenTofu/provider constraint; init changing the lock; `fmt` before `init`; backend/apply attempted by the verifier; and a saved-plan apply whose digest differs from approved plan evidence. Each case asserts exit `1` and a stable diagnostic naming the root/module/field; the valid case asserts `version, init, fmt, validate, test` ordering and `opentofu-roots: PASS`.

- [ ] **Step 2: Run baseline load and OpenTofu tests before policies exist**

Run:

```bash
k6 run tests/performance/k6/ordinary-api.js
pwsh -NoProfile -File scripts/ci/verify-opentofu.Tests.ps1
pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json
tofu -chdir=infra/opentofu test -filter=tests/capacity-policy.tftest.hcl
./gradlew :tests:infrastructure:test :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ops.capacity.*'
```

Expected: FAIL because the root inventory/verifier, tracked provider lock, target envelope, autoscaling outputs, budgets, and signed evidence mapping do not exist. The failure precedes any `fmt`, `validate`, or `test` call against an uninitialized root.

- [ ] **Step 3: Implement the executable test root, closed inventory, and certified envelope**

Append only these generated-artifact patterns to `.gitignore`; do not add a lock-file pattern:

```gitignore
**/.terraform/
*.tfstate
*.tfstate.*
crash.log
crash.*.log
build/secrets/
```

Create `.github/CODEOWNERS` with explicit lock and verifier ownership:

```text
/infra/opentofu/ @inforvans/platform-operations @inforvans/product-security
/scripts/ci/verify-opentofu.ps1 @inforvans/platform-operations @inforvans/product-security
/infra/opentofu/.terraform.lock.hcl @inforvans/platform-operations @inforvans/product-security
/infra/opentofu/environments/aws-tokyo-primary/.terraform.lock.hcl @inforvans/platform-operations @inforvans/product-security
/infra/opentofu/environments/aws-osaka-warm-dr/.terraform.lock.hcl @inforvans/platform-operations @inforvans/product-security
```

Create the test root's `versions.tf`:

```hcl
terraform {
  required_version = "= 1.9.1"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 5.100.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "= 2.36.0"
    }
  }
}
```

Create `backend.tf` as an explicit non-deployable state contract:

```hcl
terraform {
  backend "local" {}
}
```

Create `main.tf` so the root is not empty and every Task 4 module is exercised through an explicit source:

```hcl
module "foundation_contract" {
  source = "./modules/accord-foundation-contract"

  control_postgres_tls_endpoint      = "postgresql://control.test.invalid:5432/accord?sslmode=verify-full"
  webhook_postgres_tls_endpoint      = "postgresql://webhook.test.invalid:5432/accord_webhook?sslmode=verify-full"
  temporal_postgres_tls_endpoint     = "postgresql://temporal-db.test.invalid:5432/temporal?sslmode=verify-full"
  temporal_mtls_endpoint             = "grpcs://temporal.test.invalid:7233"
  object_store_endpoint              = "https://objects.test.invalid"
  object_store_adapter               = "s3-compatible"
  object_store_immutable_versions    = true
  object_store_sha256_checksums      = true
  object_store_worm_retention        = true
  object_store_legal_hold            = true
  object_store_multipart             = true
  object_store_quarantine            = true
  object_store_replication_evidence  = true
  object_store_deletion_receipts     = true
  workload_identity_ids = [
    "accord-control-api",
    "accord-control-worker",
    "accord-webhook-edge"
  ]
}

module "accord_autoscaling" {
  source = "./modules/accord-autoscaling"

  environment                   = "test"
  availability_zones            = ["ap-northeast-1a", "ap-northeast-1c", "ap-northeast-1d"]
  merge_controller_min_replicas = 2
}

module "aws_budgets" {
  source = "./modules/aws-budgets"

  environment                 = "test"
  production_monthly_usd      = 60000
  warm_dr_monthly_usd         = 12000
  forecast_alert_percent      = 80
  daily_anomaly_percent       = 20
  notification_topic_arn      = "arn:aws:sns:ap-northeast-1:111122223333:accord-budget-test"
  mandatory_allocation_tags   = ["service", "environment", "owner", "cost_center", "data_class", "release_digest"]
}

module "aws_state_backends" {
  source = "./modules/aws-state-backends"

  governance_account_id = "111122223333"
  environment_accounts  = { tokyo = "111122223334", osaka = "111122223335" }
  state_regions          = { tokyo = "ap-northeast-1", osaka = "ap-northeast-3" }
  object_versioning      = true
  access_log_retention_days = 400
  require_cross_account_recovery_role = true
}

module "aws_network" {
  source = "./modules/aws-network"

  environment        = "test"
  region             = "ap-northeast-1"
  availability_zones = ["ap-northeast-1a", "ap-northeast-1c", "ap-northeast-1d"]
  vpc_cidr            = "10.20.0.0/16"
  private_subnet_cidrs = ["10.20.0.0/20", "10.20.16.0/20", "10.20.32.0/20"]
  public_subnet_cidrs  = ["10.20.240.0/24", "10.20.241.0/24", "10.20.242.0/24"]
  flow_log_retention_days = 400
}

module "aws_eks" {
  source = "./modules/aws-eks"

  environment        = "test"
  kubernetes_version = "1.33"
  vpc_id              = module.aws_network.vpc_id
  private_subnet_ids  = module.aws_network.private_subnet_ids
  private_api_only    = true
  envelope_encryption = true
  control_plane_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
}

module "aws_workload_identity" {
  source = "./modules/aws-workload-identity"

  environment       = "test"
  oidc_provider_arn = module.aws_eks.oidc_provider_arn
  service_accounts = [
    "control-api", "control-worker", "webhook-edge", "agent-runtime", "agent-pack-gateway", "attachment-scanner",
    "signing-service", "requirement-publisher", "merge-controller",
    "break-glass-merge", "break-glass-protection", "break-glass-fence",
    "otel-collector", "staging-operations", "ga-collector", "ga-verifier", "ga-approval-attestor",
    "ga-promoter-unit-alpha", "ga-promoter-unit-beta", "contract-acceptance-attestor"
  ]
  deny_wildcard_actions = true
}

module "aws_edge" {
  source = "./modules/aws-edge"

  environment           = "test"
  vpc_id                = module.aws_network.vpc_id
  public_subnet_ids     = module.aws_network.public_subnet_ids
  enable_waf            = true
  enable_shield         = true
  certificate_arn       = "arn:aws:acm:ap-northeast-1:111122223333:certificate/00000000-0000-0000-0000-000000000001"
  dns_zone_id           = "Z0000000000000000001"
  access_log_retention_days = 400
}

module "aws_container_registry" {
  source = "./modules/aws-container-registry"

  environment          = "test"
  immutable_tags       = true
  scan_on_push         = true
  cross_region_replica = "ap-northeast-3"
  retention_days       = 400
}

module "aws_agent_pack_distribution" {
  source = "./modules/aws-agent-pack-distribution"

  environment                         = "test"
  primary_region                      = "ap-northeast-1"
  recovery_region                     = "ap-northeast-3"
  gateway_service_account             = "agent-pack-gateway"
  oidc_provider_arn                   = module.aws_eks.oidc_provider_arn
  release_repository_arn              = module.aws_container_registry.agent_pack_repository_arn
  immutable_digest_reads_only         = true
  verified_cache_kms_enabled           = true
  verified_cache_public_access_blocked = true
  verified_cache_retention_days        = 7
  maximum_capability_seconds           = 60
  require_distribution_epoch           = true
  deny_registry_write                  = true
}

module "aws_telemetry" {
  source = "./modules/aws-telemetry"

  environment             = "test"
  log_retention_days      = 400
  trace_retention_days    = 30
  metric_retention_months = 15
  private_ingest_only     = true
}

module "aws_temporal" {
  source = "./modules/aws-temporal"

  environment         = "test"
  private_subnet_ids  = module.aws_network.private_subnet_ids
  mtls_required       = true
  visibility_encrypted = true
  history_retention_days = 30
}

module "aws_operations_evidence" {
  source = "./modules/aws-operations-evidence"

  environment              = "test"
  kubernetes_oidc_provider = module.aws_eks.oidc_provider_arn
  github_oidc_provider_arn = "arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com"
  certification_namespace  = "accord-certification"
  eks_cluster_name         = module.aws_eks.cluster_name
  eks_cluster_arn          = module.aws_eks.cluster_arn
  kube_api_endpoint        = module.aws_eks.private_api_endpoint
  kube_api_audience        = module.aws_eks.kube_api_audience
  kube_api_ca_data         = module.aws_eks.certificate_authority_data
  kube_api_ca_digest       = module.aws_eks.certificate_authority_sha256
  object_lock_mode         = "COMPLIANCE"
  minimum_retention_days   = 400
  ga_signer_service_accounts = ["staging-operations", "ga-collector", "ga-verifier"]
  ga_approval_service_account = "ga-approval-attestor"
  ga_promoter_units = {
    unit-alpha = {
      service_account       = "ga-promoter-unit-alpha"
      github_environment    = "ga-promoter-unit-alpha"
      immutable_environment_id = "env-staging-japan-v1"
      argo_application_name = "accord-unit-alpha"
      argo_application_uid  = "00000000-0000-4000-8000-0000000000a1"
    }
    unit-beta = {
      service_account       = "ga-promoter-unit-beta"
      github_environment    = "ga-promoter-unit-beta"
      immutable_environment_id = "env-staging-japan-v1"
      argo_application_name = "accord-unit-beta"
      argo_application_uid  = "00000000-0000-4000-8000-0000000000b2"
    }
  }
  ga_unit_map_digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  workforce_ga_approval = {
    issuer                       = "https://workforce.example.test"
    authorization_endpoint       = "https://workforce.example.test/oauth2/authorize"
    token_endpoint               = "https://workforce.example.test/oauth2/token"
    token_exchange_endpoint      = "https://workforce.example.test/oauth2/token"
    jwks_uri                     = "https://workforce.example.test/.well-known/jwks.json"
    directory_endpoint           = "https://workforce.example.test/scim/v2/Users"
    submission_endpoint          = "https://ga-approval.example.test/v1/submissions"
    public_client_id             = "accordctl-ga-approval"
    audience                     = "accord-ga-approval"
    rar_authorization_detail_type = "urn:accord:params:oauth:authorization-details:ga-approval"
    platform_operations_group_id = "group-platform-operations"
    product_security_group_id    = "group-product-security"
    required_acr                 = "urn:accord:assurance:webauthn-step-up"
    required_amr                 = ["webauthn"]
    dpop_algorithm               = "ES256"
    tls_trust_bundle_digest      = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
  coordination_single_writer_region = "ap-northeast-1"
  coordination_point_in_time_recovery = true
  contract_acceptance_service_account = "contract-acceptance-attestor"
  github_orchestrator_subjects = {
    staging-operations = "repo:inforvans/accord:environment:staging-operations"
    ga-collector       = "repo:inforvans/accord:environment:ga-collector"
    ga-verifier        = "repo:inforvans/accord:environment:ga-verifier"
    ga-approval        = "repo:inforvans/accord:environment:ga-approval"
    ga-promoter-unit-alpha = "repo:inforvans/accord:environment:ga-promoter-unit-alpha"
    ga-promoter-unit-beta  = "repo:inforvans/accord:environment:ga-promoter-unit-beta"
  }
  contract_package_publisher_subject = "repo:inforvans/accord:ref:refs/tags/contract-terms-v1"
  require_non_signing_orchestrators  = true
  require_distinct_kms_keys          = true
  verifier_read_only                 = true
  tenant_signing_access              = false
}
```

The module and both deployable root `variables.tf` files use these required, nullable-false declarations with no defaults; the existing `aws_operations_evidence` call in each root passes the three variables byte-for-byte and takes `kube_api_audience` only from the applied `aws_eks` output:

```hcl
variable "ga_promoter_units" {
  nullable = false
  type = map(object({
    service_account          = string
    github_environment       = string
    immutable_environment_id = string
    argo_application_name    = string
    argo_application_uid     = string
  }))
  validation {
    condition     = length(var.ga_promoter_units) > 0
    error_message = "ga_promoter_units must contain every signed certification unit"
  }
}

variable "ga_unit_map_digest" {
  nullable = false
  type     = string
  validation {
    condition     = can(regex("^sha256:[0-9a-f]{64}$", var.ga_unit_map_digest))
    error_message = "ga_unit_map_digest must be a canonical SHA-256 digest"
  }
}

variable "workforce_ga_approval" {
  nullable = false
  type = object({
    issuer                        = string
    authorization_endpoint        = string
    token_endpoint                = string
    token_exchange_endpoint       = string
    jwks_uri                      = string
    directory_endpoint            = string
    submission_endpoint           = string
    public_client_id              = string
    audience                      = string
    rar_authorization_detail_type = string
    platform_operations_group_id  = string
    product_security_group_id     = string
    required_acr                  = string
    required_amr                  = set(string)
    dpop_algorithm                = string
    tls_trust_bundle_digest       = string
  })
}

# Exact assignments inside module "aws_operations_evidence" in Tokyo and Osaka.
ga_promoter_units     = var.ga_promoter_units
ga_unit_map_digest    = var.ga_unit_map_digest
workforce_ga_approval = var.workforce_ga_approval
eks_cluster_name      = module.aws_eks.cluster_name
eks_cluster_arn       = module.aws_eks.cluster_arn
kube_api_endpoint     = module.aws_eks.private_api_endpoint
kube_api_audience     = module.aws_eks.kube_api_audience
kube_api_ca_data      = module.aws_eks.certificate_authority_data
kube_api_ca_digest    = module.aws_eks.certificate_authority_sha256
```

`aws-eks/outputs.tf` exposes one closed applied-cluster connection object: `cluster_name`, `cluster_arn`, `private_api_endpoint`, sensitive `certificate_authority_data`, `certificate_authority_sha256`, and the configured `kube_api_audience`. The endpoint output must equal the EKS API's private endpoint, public endpoint access remains disabled, and `certificate_authority_sha256` is `sha256:` plus the lowercase SHA-256 of the decoded CA bytes rather than the digest of their base64 text. Neither root may accept a caller override or default for any member. The Tokyo/Osaka roots pass all six values directly to `aws_operations_evidence`; their nonsensitive root output exposes name/ARN/endpoint/audience/CA digest as a single object while the CA bytes remain sensitive. Protected apply automation passes the CA bytes only to the immutable cluster-trust projection and records only its digest in saved-plan evidence.

The module validates all workforce URLs as HTTPS, requires issuer/endpoint host and signed policy equality, distinct nonempty role groups, public-client PKCE semantics, exact approval audience/RAR/`ES256`/WebAuthn values, and a SHA-256 TLS trust-bundle digest. It also rejects a cluster name/ARN disagreement, an endpoint not owned by that ARN or not private, malformed CA data, a recomputed CA digest mismatch, an empty/changed TokenRequest audience, or Tokyo/Osaka control-lane inputs derived from another cluster. Saved-plan evidence binds the workforce-policy digest, unit-map digest, and complete applied-cluster connection object; the apply verifier rejects any disagreement before Helm values or a workflow credential can be issued.

The module variable files define exactly those typed inputs, reject fewer than three distinct AZs, public worker nodes, wildcard IAM, public EKS control endpoints, missing encryption/logging/WAF/certificate/private endpoints, mutable registry tags, shared evidence keys/roles, fewer than two Merge Controller replicas, percentages outside `1..100`, nonpositive budgets, or missing allocation tags. `aws-operations-evidence` creates three certification OIDC/IRSA signer pairs, the fixed `ga-approval-attestor` OIDC/IRSA pair, and one promoter OIDC/IRSA pair per exact certification-unit map entry. It creates distinct KMS keys and Object Lock prefixes for approval-session, approval-record, and each unit's promotion-attempt, promotion-resolution, and promotion-receipt purposes; `aws-postgresql` creates a dedicated KMS-encrypted operations-coordination database with a synchronous Multi-AZ standby, PITR, deletion protection, connection/serialization-failure alarms, and no cross-region multi-writer mode. Flyway creates closed approval and per-unit promotion tables with forced RLS, append-only submission rows, unique idempotency keys, compare-and-swap versions, leases, and monotonic fencing tokens. Two workforce federation roles, one for `platform_operations` and one for `product_security`, trust only the pinned workforce issuer/audience plus WebAuthn `acr`/`amr` and DPoP confirmation claims. Each role may invoke only its role-specific SigV4 submission route; the route's non-signing integration role verifies the DPoP proof and transaction receipt and can conditionally append only beneath the caller's own role/session scope. Neither the workforce role nor the integration role can read another submission, update a session, finalize, sign, access a Provider, or call Argo.

Every unit promoter IRSA policy is generated from the closed unit map and can read only that run/unit's report/index/approval objects and receipts, transact only its unit coordination partition, and sign only with that unit's three promotion-purpose keys. IRSA has no Kubernetes authorization power. Every control-lane Job sets `automountServiceAccountToken: false`. A promoter Job alone mounts two separate read-only projected tokens at nondefault paths: `/var/run/accord/irsa/token` has exact audience `sts.amazonaws.com`, while `/var/run/accord/kube-api/token` has audience equal to the immutable `kube_api_audience` output, `expirationSeconds: 600`, and the unit ServiceAccount subject. Its container sets fixed `AWS_ROLE_ARN` from the unit map and `AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/accord/irsa/token`; the AWS SDK is configured to reject IMDS, shared profiles, ambient credentials, role chaining, and any web-identity path other than that file. An immutable managed trust Secret, rendered only from the protected applied-cluster outputs, mounts decoded CA bytes read-only at `/var/run/accord/kube-api/ca.crt`; its annotation carries the expected CA digest and it is not readable by an orchestration role. Certification, approval, package, and acceptance Jobs receive only the IRSA projection and never a kube-api token or cluster-trust mount. Helm and runtime tests exchange the two token paths and audiences and require both STS and Kubernetes authentication to fail before any state or evidence mutation.

The promoter's Kubernetes adapter is constructed only as `rest.Config{Host: kubeAPIEndpoint, BearerTokenFile: kubeTokenFile, TLSClientConfig: rest.TLSClientConfig{CAFile: kubeCAFile}}`. The three paths/endpoint and expected CA digest/audience are mandatory explicit command bindings fixed by the rendered Job. Startup rejects a non-HTTPS or non-applied endpoint, any file other than the two admitted read-only mount paths, a missing/symlink/non-regular/writable CA or token file, CA bytes whose recomputed digest differs, an absent/wrong token `aud`, a token lifetime over 600 seconds, `Insecure=true`, system-root fallback, `InClusterConfig`, `KUBECONFIG`, default ServiceAccount paths, or environment inference. The API server then independently authenticates the projected token; the unit Role/RoleBinding authorizes only `get` and `patch` on the exact Argo Application `resourceName`, and UID/resourceVersion/request/fencing checks remain admission conditions.

Each GitHub OIDC orchestration role authenticates to EKS through a dedicated `aws_eks_access_entry` mapped to one lane/unit-specific Kubernetes group. AccessEntry supplies identity mapping only. A digest-pinned ephemeral runner in the cluster VPC has a private DNS/route/security-group path only to the applied private EKS endpoint and may call `eks:DescribeCluster` only on the exact applied `cluster_arn`. Before generating a token it requires returned name, ARN, endpoint, decoded CA digest, public-access flag, and OIDC issuer to equal the signed saved-apply outputs; it then invokes the pinned AWS authenticator for only that `cluster_name` and builds a process-local kubeconfig with the returned private endpoint and CA. No other cluster name/ARN, cached kubeconfig, system CA, public endpoint, caller URL, or default credential chain is accepted. The AccessEntry principal is the same short-lived environment-bound role, so a token for another cluster or role is rejected even if its endpoint is reachable.

A namespace Role/RoleBinding grants that orchestration group `create` only for Jobs and `get|watch|delete` only for the lane's single fixed digest-derived Job `resourceName`; a ValidatingAdmissionPolicy binds create to the expected group, fixed name, dispatch ID, template/image/command digests, immutable labels, ServiceAccount, and owner tuple. Every workflow job declares a policy-fixed concurrency group composed from repository plus its literal protected lane, or repository plus the signed unit-map-resolved protected environment, with `cancel-in-progress: false`; no workflow input can supply or suffix the group. Before create, the launcher strongly confirms the fixed Job is absent. It never replaces, adopts, patches, or deletes a pre-existing Job, and a second concurrent dispatch or residual object exits `3` without preemption.

After create, the orchestration role observes only that exact Job status, never Pods, logs, exec, attach, port-forward, Secret, ConfigMap, service-account-token, Role, or admission-policy resources. The pod IRSA writes a closed strings-only **unsigned** JCS locator to deterministic `handoffs/<lane-or-unit>/<dispatch_id>` storage with create-only semantics and COMPLIANCE Object Lock. The locator contains only validated object keys, content digests, exact object versions, immutable receipt digests/versions, producer Job UID/image/command digest, identities, and operation bindings; it has no signature field and is never an authorization statement. Its integrity comes from the create-only lane/dispatch key, S3 checksum and returned version, Object Lock, CloudTrail write identity, and the immutable launch receipt. The orchestration role may read only that exact handoff version and metadata, cannot list, read an evidence body, or write, and validates the closed locator schema/checksum/Job UID/bindings. Every downstream consumer must separately download the referenced exact envelope and receipt, verify their digests, closed schemas, JCS/DSSE purpose/signature, retention, and all operation bindings; a valid locator can never substitute for those bytes.

Only after terminal Job status, exact UID match, locator persistence/read-back, and required downstream handoff checks may the launcher issue a UID-preconditioned delete and wait until that exact Job is absent. A missing handoff, ambiguous terminal state, UID change, deletion timeout/uncertainty, or object remaining after delete exits `3` and retains the Job for investigation. The fixed approval IRSA can query the pinned workforce JWKS/directory endpoints, transact approval rows, sign approval-session/record payloads, and write only its deterministic handoffs, but has no Provider or kube-api credential. `ga-control-lanes.tftest.hcl` proves all cluster output/input bindings, exact `DescribeCluster` ARN, private runner path, AccessEntry/group/Role, fixed concurrency/name, token/CA mount, identity/key/prefix/table/resource-name, unsigned handoff read, 100-way conditional-claim policy, and workforce-role separations. Its negative matrix covers wrong cluster name/ARN/endpoint/CA/audience, CA or token path mixing, default client fallback, a kube token or trust mount in any non-promoter lane, mutable/missing concurrency, `cancel-in-progress: true`, concurrent dispatch, residual/deletion-unknown Job, wildcard or cross-unit grant, and any orchestration evidence-body read. The other three OpenTofu tests retain their capacity/foundation/Pack assertions. `aws-agent-pack-distribution` remains isolated from every GA control lane.

Both deployable environment roots declare required, no-default `ga_promoter_units`, `ga_unit_map_digest`, and `workforce_ga_approval` variables with the same closed types shown below and pass them explicitly into `aws_operations_evidence`; all applied-cluster connection inputs come directly from the local `aws_eks` module outputs shown above. The Osaka warm-DR root creates only the separately keyed standby resources allowed by the signed active-region/failover policy and cannot become a second writer. Protected plan CI verifies `certification/units.yaml`, renders one ephemeral `*.auto.tfvars.json` from its canonical bytes, supplies those identical bytes/digest to Tokyo and Osaka, and records the manifest/variable and cluster-connection digests in saved-plan evidence. No engineer-maintained second unit list is accepted. Task 14 verification compares that signed manifest and cluster connection with both applied root outputs, protected Helm values, Argo applications, GitHub environments, admission bindings, and GA reports. `ga-control-lanes.tftest.hcl` includes exact failures for an empty map, an extra or omitted unit, duplicate Application name/UID, Tokyo/Osaka digest disagreement, Application name/UID/immutable-environment drift from the signed manifest, cluster output or CA drift, and any missing/non-HTTPS/mismatched workforce endpoint, client, audience, RAR type, group, WebAuthn/DPoP, or TLS trust value.

Create `roots.schema.json` with `additionalProperties: false` at the document, root, and provider-map boundaries, then create this four-root `roots.json` (module arrays are ordinal-sorted and must match parsed HCL exactly):

```json
{
  "schema_version": "1.0",
  "opentofu_version": "1.9.1",
  "roots": [
    {
      "id": "aws-osaka-warm-dr",
      "path": "infra/opentofu/environments/aws-osaka-warm-dr",
      "backend": "s3-partial",
      "lock": "infra/opentofu/environments/aws-osaka-warm-dr/.terraform.lock.hcl",
      "tests": false,
      "providers": { "registry.opentofu.org/hashicorp/aws": "5.100.0" },
      "modules": ["accord-autoscaling", "accord-foundation-contract", "aws-agent-pack-distribution", "aws-budgets", "aws-container-registry", "aws-edge", "aws-eks", "aws-network", "aws-operations-evidence", "aws-telemetry", "aws-temporal", "aws-workload-identity"]
    },
    {
      "id": "aws-state-backends",
      "path": "infra/opentofu/bootstrap/aws-state-backends",
      "backend": "organization-governance-s3-partial",
      "lock": "infra/opentofu/bootstrap/aws-state-backends/.terraform.lock.hcl",
      "tests": false,
      "providers": { "registry.opentofu.org/hashicorp/aws": "5.100.0" },
      "modules": ["aws-state-backends"]
    },
    {
      "id": "aws-tokyo-primary",
      "path": "infra/opentofu/environments/aws-tokyo-primary",
      "backend": "s3-partial",
      "lock": "infra/opentofu/environments/aws-tokyo-primary/.terraform.lock.hcl",
      "tests": false,
      "providers": { "registry.opentofu.org/hashicorp/aws": "5.100.0" },
      "modules": ["accord-autoscaling", "accord-foundation-contract", "aws-agent-pack-distribution", "aws-budgets", "aws-container-registry", "aws-edge", "aws-eks", "aws-network", "aws-operations-evidence", "aws-telemetry", "aws-temporal", "aws-workload-identity"]
    },
    {
      "id": "integration-test",
      "path": "infra/opentofu",
      "backend": "local-ephemeral",
      "lock": "infra/opentofu/.terraform.lock.hcl",
      "tests": true,
      "providers": { "registry.opentofu.org/hashicorp/aws": "5.100.0", "registry.opentofu.org/hashicorp/kubernetes": "2.36.0" },
      "modules": ["accord-autoscaling", "accord-foundation-contract", "aws-agent-pack-distribution", "aws-budgets", "aws-container-registry", "aws-edge", "aws-eks", "aws-network", "aws-operations-evidence", "aws-state-backends", "aws-telemetry", "aws-temporal", "aws-workload-identity"]
    }
  ]
}
```

The Tokyo and Osaka `main.tf` files explicitly source their twelve declared modules; neither reads another root's state. Both source autoscaling and budgets with region-specific capacity/cost inputs and source Agent Pack distribution with the local region, peer region, exact gateway ServiceAccount, fixed OCI repository ARN, cache encryption policy, and starting epoch. The bootstrap root sources only `../../modules/aws-state-backends` and uses a separate organization-governance backend supplied by protected CI. `backend.hcl.example` files contain identifiers only; CI resolves actual bucket/KMS/native-lock-object values from its environment and records their digest in plan evidence.

Create `verify-opentofu.ps1` as the deterministic implementation below. `-RepositoryRoot` and `-TofuExecutable` exist only so the hermetic contract test can supply a temporary Git repository and recording fake; production calls use their defaults.

```powershell
[CmdletBinding()]
param(
  [string]$Manifest = 'infra/opentofu/roots.json',
  [string]$RepositoryRoot = '',
  [string]$TofuExecutable = 'tofu'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
  $RepositoryRoot = (& git rev-parse --show-toplevel).Trim()
  if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve repository root' }
}
$repo = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
$repoPrefix = $repo + [IO.Path]::DirectorySeparatorChar

function Resolve-InRepository([string]$Path) {
  $candidate = if ([IO.Path]::IsPathRooted($Path)) { $Path } else { Join-Path $repo $Path }
  $full = [IO.Path]::GetFullPath($candidate)
  if (-not $full.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Path escapes repository: $Path"
  }
  return $full
}

function Convert-ToRepoPath([string]$Path) {
  return [IO.Path]::GetRelativePath($repo, $Path).Replace('\', '/')
}

function Invoke-Tofu([string]$RootPath, [string[]]$Arguments) {
  & $TofuExecutable "-chdir=$RootPath" @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "tofu $($Arguments[0]) failed for $(Convert-ToRepoPath $RootPath)"
  }
}

$manifestPath = Resolve-InRepository $Manifest
$gradleExecutable = if ($IsWindows) { Resolve-InRepository 'gradlew.bat' } else { Resolve-InRepository 'gradlew' }
& $gradleExecutable -p $repo :tests:infrastructure:run "--args=--manifest $Manifest --schema infra/opentofu/roots.schema.json"
if ($LASTEXITCODE -ne 0) { throw 'closed root inventory or HCL source verification failed' }
$inventory = Get-Content -Raw -Encoding utf8 -LiteralPath $manifestPath | ConvertFrom-Json
$errors = [Collections.Generic.List[string]]::new()
if ($inventory.schema_version -cne '1.0') { $errors.Add('roots.json schema_version must be 1.0') }
if ($inventory.opentofu_version -cne '1.9.1') { $errors.Add('roots.json opentofu_version must be 1.9.1') }

$toolVersions = Get-Content -Encoding utf8 -LiteralPath (Join-Path $repo '.tool-versions')
if ($toolVersions -cnotcontains 'opentofu 1.9.1') { $errors.Add('.tool-versions must contain opentofu 1.9.1') }
$tofuVersion = (& $TofuExecutable version -json | Out-String | ConvertFrom-Json).terraform_version
if ($LASTEXITCODE -ne 0 -or $tofuVersion -cne '1.9.1') {
  $errors.Add("tofu version must be 1.9.1; found $tofuVersion")
}

$entries = @($inventory.roots)
$entryPaths = @($entries | ForEach-Object { $_.path })
if (($entryPaths | Sort-Object -Unique).Count -ne $entryPaths.Count) { $errors.Add('roots.json contains duplicate root paths') }

$declared = @($entryPaths | Sort-Object)
$discovered = @(Get-ChildItem -LiteralPath (Join-Path $repo 'infra/opentofu') -Filter backend.tf -File -Recurse |
  Where-Object { $_.FullName -notmatch '[\\/]modules[\\/]' -and $_.FullName -notmatch '[\\/]\.terraform[\\/]' } |
  ForEach-Object { Convert-ToRepoPath $_.Directory.FullName } | Sort-Object)
if (($declared -join "`n") -cne ($discovered -join "`n")) {
  $errors.Add("declared roots differ from backend.tf roots: declared=$($declared -join ',') discovered=$($discovered -join ',')")
}

$testedModules = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($entry in $entries) {
  try { $rootPath = Resolve-InRepository $entry.path } catch { $errors.Add($_.Exception.Message); continue }
  if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) { $errors.Add("missing root: $($entry.path)"); continue }
  $versionsPath = Join-Path $rootPath 'versions.tf'
  $backendPath = Join-Path $rootPath 'backend.tf'
  if (-not (Test-Path -LiteralPath $versionsPath -PathType Leaf)) { $errors.Add("missing versions.tf: $($entry.path)"); continue }
  if (-not (Test-Path -LiteralPath $backendPath -PathType Leaf)) { $errors.Add("missing backend.tf: $($entry.path)"); continue }
  $versionsText = Get-Content -Raw -Encoding utf8 -LiteralPath $versionsPath
  if ($versionsText -notmatch 'required_version\s*=\s*"= 1\.9\.1"') { $errors.Add("mutable OpenTofu constraint: $($entry.path)") }

  $expectedLock = (($entry.path.TrimEnd('/') + '/.terraform.lock.hcl').TrimStart([char[]]'./'))
  if ($entry.lock -cne $expectedLock) { $errors.Add("lock path does not belong to root: $($entry.path)") }
  try { $lockPath = Resolve-InRepository $entry.lock } catch { $errors.Add($_.Exception.Message); continue }
  if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) { $errors.Add("missing lock: $($entry.lock)"); continue }
  & git -C $repo ls-files --error-unmatch -- $entry.lock 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { $errors.Add("untracked lock: $($entry.lock)") }
  $lockText = Get-Content -Raw -Encoding utf8 -LiteralPath $lockPath

  foreach ($provider in $entry.providers.PSObject.Properties) {
    $shortName = $provider.Name.Split('/')[-1]
    $selection = [Regex]::Match(
      $lockText,
      'provider\s+"' + [Regex]::Escape($provider.Name) + '"\s*\{(?<body>[\s\S]*?)\}',
      [Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    if (-not $selection.Success -or $selection.Groups['body'].Value -notmatch ('version\s*=\s*"' + [Regex]::Escape($provider.Value) + '"')) {
      $errors.Add("stale provider lock: $($entry.lock) $($provider.Name)=$($provider.Value)")
    }
    $constraint = [Regex]::Match(
      $versionsText,
      [Regex]::Escape($shortName) + '\s*=\s*\{(?<body>[\s\S]*?)\}',
      [Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    if (-not $constraint.Success -or $constraint.Groups['body'].Value -notmatch ('version\s*=\s*"= ' + [Regex]::Escape($provider.Value) + '"')) {
      $errors.Add("mutable provider constraint: $($entry.path) $($provider.Name)")
    }
  }

  if ($entry.tests) {
    foreach ($moduleName in @($entry.modules)) { $null = $testedModules.Add([string]$moduleName) }
  }
}

$moduleRoot = Join-Path $repo 'infra/opentofu/modules'
$discoveredModules = @(Get-ChildItem -LiteralPath $moduleRoot -Directory | ForEach-Object Name | Sort-Object)
$coveredModules = @($testedModules | Sort-Object)
if (($discoveredModules -join "`n") -cne ($coveredModules -join "`n")) {
  $errors.Add("tested module coverage differs: discovered=$($discoveredModules -join ',') covered=$($coveredModules -join ',')")
}
foreach ($moduleName in $coveredModules) {
  $referenced = $false
  foreach ($entry in @($entries | Where-Object tests)) {
    $rootPath = Resolve-InRepository $entry.path
    $rootHcl = (Get-ChildItem -LiteralPath $rootPath -Filter '*.tf' -File | ForEach-Object {
      Get-Content -Raw -Encoding utf8 -LiteralPath $_.FullName
    }) -join "`n"
    if ($rootHcl -match ('source\s*=\s*"\./modules/' + [Regex]::Escape($moduleName) + '"')) { $referenced = $true }
  }
  if (-not $referenced) { $errors.Add("module is not sourced by a tested root: $moduleName") }
}

if ($errors.Count -gt 0) { throw (($errors | Sort-Object -Unique) -join [Environment]::NewLine) }

foreach ($entry in @($entries | Sort-Object id)) {
  $rootPath = Resolve-InRepository $entry.path
  $lockPath = Resolve-InRepository $entry.lock
  $before = (Get-FileHash -Algorithm SHA256 -LiteralPath $lockPath).Hash
  Invoke-Tofu $rootPath @('init', '-backend=false', '-input=false', '-lockfile=readonly')
  if ((Get-FileHash -Algorithm SHA256 -LiteralPath $lockPath).Hash -cne $before) { throw "init changed lock: $($entry.lock)" }
  Invoke-Tofu $rootPath @('fmt', '-check')
  if ((Get-FileHash -Algorithm SHA256 -LiteralPath $lockPath).Hash -cne $before) { throw "fmt changed lock: $($entry.lock)" }
  Invoke-Tofu $rootPath @('validate', '-no-color')
  if ((Get-FileHash -Algorithm SHA256 -LiteralPath $lockPath).Hash -cne $before) { throw "validate changed lock: $($entry.lock)" }
  if ($entry.tests) {
    Invoke-Tofu $rootPath @('test', '-no-color')
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $lockPath).Hash -cne $before) { throw "test changed lock: $($entry.lock)" }
  }
  & git -C $repo diff --quiet -- $entry.lock
  if ($LASTEXITCODE -ne 0) { throw "working tree lock diff: $($entry.lock)" }
}

$untrackedLocks = @(& git -C $repo ls-files --others --exclude-standard -- 'infra/opentofu/.terraform.lock.hcl' 'infra/opentofu/**/.terraform.lock.hcl')
if ($LASTEXITCODE -ne 0) { throw 'git lock discovery failed' }
if ($untrackedLocks.Count -gt 0) { throw "untracked provider locks: $($untrackedLocks -join ',')" }
Write-Output "opentofu-roots: PASS roots=$($entries.Count) modules=$($coveredModules.Count)"
```

`tests/infrastructure/build.gradle` pins `com.bertramlabs.plugins:hcl4j:0.9.8`, locks its runtime graph, and exposes `OpenTofuRootVerifier` through the Gradle `application` plugin. The verifier validates `roots.json` against the closed schema before decoding, rejects duplicate or non-ordinal arrays, parses every root-level `.tf` file through HCL4J's structured AST, accepts only literal relative sources resolving beneath `infra/opentofu/modules`, and compares the normalized actual source set with that root's declared modules. It separately discovers every module directory and requires each to be sourced by the integration root and by at least one `tests=false` root. It also discovers `backend.tf` roots, validates exact lock ownership/provider selections, and emits sorted diagnostics. Regex matching is not used for HCL or JSON. The PowerShell layer owns Git/lock/tool invocation ordering only; it never deletes `.terraform`, changes a lock, accepts `-upgrade`, initializes a real backend, plans, or applies.

Create the reference policy:

```yaml
schema_version: "1.0"
envelope_id: aws-japan-v1-small-medium
load:
  concurrent_tenants: 100
  active_users_per_tenant: 50
  ordinary_requests_per_second: 200
  open_action_requests: 10000
  concurrent_workitems_per_repository: 20
  repository_file_metadata_entries: 100000
scale_signals:
  control_api: [cpu, request_concurrency]
  control_worker: [oldest_outbox_age, reconciliation_queue_depth]
  agent_runtime: [job_queue_age, active_jobs]
  security_services: [request_queue_age, pending_authorizations]
limits:
  database_connection_percent: 70
  queue_recovery_minutes_after_throttle: 30
cost:
  production_monthly_usd: 60000
  warm_dr_monthly_usd: 12000
  forecast_alert_percent: 80
  daily_anomaly_percent: 20
```

OpenTofu creates HPAs/KEDA policies with at least two Merge Controller replicas across AZs and budgets/tags for `service`, `environment`, `owner`, `cost_center`, `data_class`, and `release_digest`. Cost controls may stop expansion but must not disable correctness, audit, security, backup, or recovery.

Generate and stage all four Task 4 locks with the pinned OpenTofu binary; staging is required before the verifier's tracked-file assertion:

```bash
tofu -chdir=infra/opentofu providers lock -platform=linux_amd64 -platform=windows_amd64
tofu -chdir=infra/opentofu/bootstrap/aws-state-backends providers lock -platform=linux_amd64 -platform=windows_amd64
tofu -chdir=infra/opentofu/environments/aws-tokyo-primary providers lock -platform=linux_amd64 -platform=windows_amd64
tofu -chdir=infra/opentofu/environments/aws-osaka-warm-dr providers lock -platform=linux_amd64 -platform=windows_amd64
git add infra/opentofu/.terraform.lock.hcl infra/opentofu/bootstrap/aws-state-backends/.terraform.lock.hcl infra/opentofu/environments/aws-tokyo-primary/.terraform.lock.hcl infra/opentofu/environments/aws-osaka-warm-dr/.terraform.lock.hcl
```

Expected: every lock contains signed checksums for AWS `5.100.0` and the integration lock also contains Kubernetes `2.36.0`, on both declared platforms. `git check-ignore infra/opentofu/.terraform/terraform.tfstate build/secrets/opentofu/aws-tokyo-primary.backend.hcl` succeeds; `git check-ignore infra/opentofu/.terraform.lock.hcl` exits `1`, proving caches/protected backend material are ignored and locks are not.

`opentofu-plan.yaml` runs only with the read-only plan role. It verifies a clean pinned commit and tool image, initializes the selected root with its protected backend file, runs `tofu plan -out=build/opentofu/<root>/<commit>.tfplan`, renders that exact file with `tofu show -json`, scans the JSON with `infra/policy/opentofu/plan.rego`, and invokes `ops iac attest-plan`. The closed plan-evidence payload binds root ID, source/release/tool/provider-lock/backend-config/config-archive/plan-binary/plan-JSON/policy digests, remote state lineage/serial/object version, replacement/delete counts, cost result, timestamps, and approval scope. It stores the plan and DSSE envelope under compliance object lock and records the immutable receipt.

`opentofu-apply.yaml` is a protected environment that requires two distinct recorded approvers, one current Platform Operations principal and one Product Security principal; author/self approval and one natural person occupying both slots fail. The job downloads the immutable plan by object version, re-verifies receipt/DSSE/trust/source/tool/backend/state-lineage/serial/expiry and binary digest, and executes only `tofu apply -input=false <downloaded-saved-plan>`. It never invokes `plan`. `ops iac record-apply` reads the remote state after the call and signs the applied plan digest, provider result, state lineage and serial before/after, state object version, nonsensitive output digest, actor/workload IDs, and audit correlation. Provider uncertainty, stale serial, different plan bytes, missing approval, or receipt mismatch exits nonzero and blocks every environment promotion.

`operations-capacity-report.schema.json` extends the common evidence metadata with the envelope/configuration digest, exact k6 binary/script/result digests, start/end load levels, latency/error/queue/connection observations, recovery time, autoscaling decisions, provisioned capacity outputs, budget/cost observations, and zero-tolerance deltas. The runner persists raw k6/metrics/cost/plan observations first, signs through the platform evidence lane, and stores a receipt. The verifier independently recalculates thresholds and accepts only `result=pass`; missing samples or a lower load than the declared envelope is a failure, not an extrapolation.

- [ ] **Step 4: Run the complete load profile and validate evidence**

Run:

```bash
pwsh -NoProfile -File scripts/ci/verify-opentofu.Tests.ps1
pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json
tofu -chdir=infra/opentofu test -filter=tests/capacity-policy.tftest.hcl
tofu -chdir=infra/opentofu test -filter=tests/production-foundation.tftest.hcl
tofu -chdir=infra/opentofu test -filter=tests/agent-pack-distribution.tftest.hcl
tofu -chdir=infra/opentofu test -filter=tests/ga-control-lanes.tftest.hcl
k6 run tests/performance/k6/ordinary-api.js
k6 run tests/performance/k6/action-visibility.js
k6 run tests/performance/k6/provider-backlog.js
k6 run tests/performance/k6/agent-pack-download.js
./gradlew :tests:infrastructure:test :cmd:accordctl:test :cmd:accordctl:installDist
cmd/accordctl/build/install/accordctl/bin/accordctl ops capacity collect --envelope operations/capacity/reference-envelope.yaml --input-dir build/capacity/raw --environment staging --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --output build/operations/capacity-report.dsse.json --confirm-load
cmd/accordctl/build/install/accordctl/bin/accordctl ops capacity verify --evidence build/operations/capacity-report.dsse.json --summary-output build/operations/capacity-report.json
```

Expected: the verifier harness passes every negative case, the real inventory prints `opentofu-roots: PASS roots=4 modules=13`, all four filtered tests pass after read-only initialization, and the GA test proves fixed approval identity, per-unit promoter identity/key/prefix/coordination/Application isolation, exact applied EKS name/ARN/private endpoint/CA/audience propagation, separate IRSA and kube-api projections, explicit no-fallback clients, fixed non-cancelling concurrency, and safe fixed-Job lifecycle. API and ActionRequest objectives hold at the complete envelope, both warm-cache and cold-OCI Agent Pack profiles meet their separate budgets without exposing unverified bytes or exhausting gateway/API connections, queues return below threshold within 30 minutes after throttle removal, no connection budget is exceeded, every production module is sourced by Tokyo/Osaka or bootstrap, and the platform-signed capacity report and immutable receipt verify with `result=pass`.

- [ ] **Step 5: Commit capacity and cost controls**

```bash
git add .gitignore .github/CODEOWNERS .github/workflows/opentofu-plan.yaml .github/workflows/opentofu-apply.yaml operations/capacity operations/cost tests/performance infra/opentofu/versions.tf infra/opentofu/backend.tf infra/opentofu/main.tf infra/opentofu/.terraform.lock.hcl infra/opentofu/roots.json infra/opentofu/roots.schema.json infra/opentofu/bootstrap/aws-state-backends infra/opentofu/environments/aws-tokyo-primary infra/opentofu/environments/aws-osaka-warm-dr infra/opentofu/modules/accord-autoscaling infra/opentofu/modules/aws-agent-pack-distribution infra/opentofu/modules/aws-budgets infra/opentofu/modules/aws-state-backends infra/opentofu/modules/aws-network infra/opentofu/modules/aws-eks infra/opentofu/modules/aws-workload-identity infra/opentofu/modules/aws-edge infra/opentofu/modules/aws-container-registry infra/opentofu/modules/aws-telemetry infra/opentofu/modules/aws-temporal infra/opentofu/modules/aws-operations-evidence infra/opentofu/tests/capacity-policy.tftest.hcl infra/opentofu/tests/production-foundation.tftest.hcl infra/opentofu/tests/agent-pack-distribution.tftest.hcl infra/opentofu/tests/ga-control-lanes.tftest.hcl infra/policy/opentofu tests/infrastructure scripts/ci/verify-opentofu.ps1 scripts/ci/verify-opentofu.Tests.ps1 contracts/json-schema/opentofu-plan-evidence.schema.json contracts/json-schema/opentofu-apply-evidence.schema.json contracts/json-schema/operations-capacity-report.schema.json cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/capacity cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/capacity cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/iac cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/iac
git commit -m "ops: define certified capacity and cost guardrails"
```

### Task 5: Provision Multi-AZ Data Services, Backups, PITR, And Restore Verification

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Modify: `infra/opentofu/main.tf`
- Modify: `infra/opentofu/roots.json`
- Modify: `infra/opentofu/environments/aws-tokyo-primary/main.tf`
- Modify: `infra/opentofu/environments/aws-osaka-warm-dr/main.tf`
- Create: `infra/opentofu/modules/aws-postgresql/main.tf`
- Create: `infra/opentofu/modules/aws-postgresql/variables.tf`
- Create: `infra/opentofu/modules/aws-postgresql/outputs.tf`
- Create: `infra/opentofu/modules/aws-object-storage/main.tf`
- Create: `infra/opentofu/modules/aws-object-storage/variables.tf`
- Create: `infra/opentofu/modules/aws-object-storage/outputs.tf`
- Create: `infra/opentofu/modules/aws-backup/main.tf`
- Create: `infra/opentofu/modules/aws-backup/variables.tf`
- Create: `infra/opentofu/modules/aws-backup/outputs.tf`
- Create: `infra/opentofu/tests/data-resilience.tftest.hcl`
- Create: `operations/backup/policy.yaml`
- Create: `operations/backup/report-signature-policy.yaml`
- Create: `contracts/json-schema/operations-restore-report.schema.json`
- Create: `docs/operations/runbooks/postgresql-restore.md`
- Create: `docs/operations/runbooks/attachment-restore.md`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/restore/Verify.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/restore/Report.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/restore/Sign.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/restore/VerifyTest.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/RestoredStateIntegrityIT.java`

- [ ] **Step 1: Write failing infrastructure and restored-state integrity tests**

Assert synchronous Multi-AZ PostgreSQL, encrypted storage/logs/backups, PITR, deletion protection, restore-isolated networking, S3 versioning/replication metrics/object lock where retention policy requires it, rebuildable non-authoritative verified-object caches, checksum verification, domain-event/audit-chain closure, projection rebuild, deterministic outbox replay, and disabled notification/publication/merge credentials in restore environments. Add report tests for an implicit/latest relative timestamp, missing `--confirm-drill`, mutable environment name without immutable ID, wrong release/tool/policy digest, restore before/after the requested timestamp, incomplete audit chain, enabled side-effect identity, failed result relabeled pass, tenant signer, absent object version, changed receipt, and insufficient retention.

- [ ] **Step 2: Run data-resilience tests before modules exist**

Run:

```bash
pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json
tofu -chdir=infra/opentofu test -filter=tests/data-resilience.tftest.hcl
./gradlew :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ops.restore.*'
./gradlew :apps:control-plane:modules:reliability:test --tests '*RestoredStateIntegrityIT'
```

Expected: FAIL because data modules, restore evidence, and the isolated restored-state fixture are absent.

- [ ] **Step 3: Implement backup and restore policy**

Create `operations/backup/policy.yaml`:

```yaml
schema_version: "1.0"
postgresql:
  synchronous_multi_az: true
  pitr_days: 35
  daily_snapshot_days: 35
  monthly_snapshot_months: 12
  cross_region_log_target_seconds: 300
  backup_vault_lock_mode: COMPLIANCE
  backup_vault_min_retention_days: 35
  backup_vault_max_retention_days: 3650
  cross_account_recovery_copy: required
  recovery_account_role_separation: required
objects:
  versioning: true
  replication_metrics: true
  object_lock_mode: COMPLIANCE
  restore_sample_count: 1000
verification:
  backup_job: daily
  point_in_time_restore: monthly
  projection_rebuild_and_audit_chain: monthly
  object_sample_hash: monthly
restored_environment:
  external_side_effect_credentials: disabled
  network_mode: isolated
  evidence_retention_days: 400
```

Infrastructure backup retention is separate from tenant data retention and legal hold. `aws-backup` creates an AWS Backup Vault Lock in compliance mode, encrypted cross-region and cross-account recovery copies, a recovery account role that production workloads cannot assume, restore-only KMS grants, and CloudTrail/audit retention. Policy tests prove production administrators cannot shorten the lock, delete recovery points, or grant the restore environment any external side-effect credential. A restore verifier compares requested/actual recovery timestamps, aggregate heads, event sequences, audit anchors, outbox outcomes, and sampled object-version hashes before destroying the disposable recovery environment.

Append these exact module blocks to the test-root `main.tf`:

```hcl
module "aws_postgresql" {
  source = "./modules/aws-postgresql"

  environment           = "test"
  region                = "ap-northeast-1"
  availability_zones    = ["ap-northeast-1a", "ap-northeast-1c", "ap-northeast-1d"]
  engine_version        = "17.5"
  multi_az              = true
  storage_encrypted     = true
  deletion_protection   = true
  pitr_days              = 35
  restore_network_mode  = "isolated"
}

module "aws_object_storage" {
  source = "./modules/aws-object-storage"

  environment                 = "test"
  primary_region              = "ap-northeast-1"
  recovery_region             = "ap-northeast-3"
  versioning_enabled          = true
  replication_metrics_enabled = true
  object_lock_enabled         = true
  restore_sample_count        = 1000
}

module "aws_backup" {
  source = "./modules/aws-backup"

  environment                            = "test"
  primary_region                         = "ap-northeast-1"
  recovery_region                        = "ap-northeast-3"
  daily_retention_days                   = 35
  monthly_retention_months               = 12
  cross_region_log_target_seconds        = 300
  restored_external_credentials_enabled  = false
}
```

The three module variable files define exactly these typed inputs with no environment-specific defaults, and the output contracts expose only nonsensitive resource identifiers required by `data-resilience.tftest.hcl`. No module may derive a region or account from a global constant. Process-local caches and the Agent Pack verified-object cache are disposable consumers of signed immutable inputs; neither is a separately provisioned database or backup authority.

Source the same three modules from both Tokyo and Osaka `main.tf` files with root-specific network/KMS/account/region inputs and no local-state reads. Replace their `modules` arrays with this exact sorted production set:

```json
[
  "accord-autoscaling",
  "accord-foundation-contract",
  "aws-agent-pack-distribution",
  "aws-backup",
  "aws-budgets",
  "aws-container-registry",
  "aws-edge",
  "aws-eks",
  "aws-network",
  "aws-object-storage",
  "aws-operations-evidence",
  "aws-postgresql",
  "aws-telemetry",
  "aws-temporal",
  "aws-workload-identity"
]
```

Replace the integration-test root's `modules` array with this exact sorted coverage set:

```json
[
  "accord-autoscaling",
  "accord-foundation-contract",
  "aws-backup",
  "aws-budgets",
  "aws-container-registry",
  "aws-edge",
  "aws-eks",
  "aws-network",
  "aws-object-storage",
  "aws-operations-evidence",
  "aws-postgresql",
  "aws-state-backends",
  "aws-telemetry",
  "aws-temporal",
  "aws-workload-identity"
]
```

Do not regenerate any provider lock: these modules use the already pinned AWS provider. `verify-opentofu.ps1` must prove every old lock hash is unchanged after the new modules are wired.

`operations-restore-report.schema.json` extends common evidence metadata with requested and actual recovery timestamps, backup/recovery point/object-version identifiers, source and isolated restore environment IDs, database and attachment watermarks/RPO, aggregate/event/audit/outbox/projection digests, sampled object counts/hash index, disabled-credential proof, cleanup receipt, and overall result. `ops restore drill` requires an explicit RFC 3339 `--point-in-time`, immutable `--expected-environment-id`, release bundle digest, output path, and `--confirm-drill`; relative values such as `latest` or `latest-15m` are rejected. The runner signs and stores the report even when integrity fails, but returns exit `1`; `ops restore verify` accepts only the platform-signed, receipt-bound `pass` report.

- [ ] **Step 4: Provision a disposable restore and verify integrity**

Run:

```bash
pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json
tofu -chdir=infra/opentofu test -filter=tests/data-resilience.tftest.hcl
./gradlew :cmd:accordctl:test :cmd:accordctl:installDist
cmd/accordctl/build/install/accordctl/bin/accordctl ops restore drill --environment staging --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --point-in-time "$ACCORD_RESTORE_POINT_RFC3339" --sample-objects 1000 --output build/operations/restore-report.dsse.json --confirm-drill
cmd/accordctl/build/install/accordctl/bin/accordctl ops restore verify --evidence build/operations/restore-report.dsse.json --summary-output build/operations/restore-report.json
./gradlew :apps:control-plane:modules:reliability:test --tests '*RestoredStateIntegrityIT'
```

Expected: the root verifier prints `opentofu-roots: PASS roots=4 modules=16` before the filtered test; restore reaches the explicit requested point; synchronous-domain confirmed transactions show RPO 0; aggregate/event/audit digests close; outbox replay is deterministic without external effects; all 1,000 sampled object versions match; the disposable verified Pack cache is excluded from backup authority and can be rebuilt only from the signed fixed-digest OCI release; and only the platform-signed, immutable, receipt-bound `pass` report verifies.

- [ ] **Step 5: Commit data resilience**

```bash
git add infra/opentofu/main.tf infra/opentofu/roots.json infra/opentofu/environments/aws-tokyo-primary/main.tf infra/opentofu/environments/aws-osaka-warm-dr/main.tf infra/opentofu/modules/aws-postgresql infra/opentofu/modules/aws-object-storage infra/opentofu/modules/aws-backup infra/opentofu/tests/data-resilience.tftest.hcl operations/backup contracts/json-schema/operations-restore-report.schema.json docs/operations/runbooks/postgresql-restore.md docs/operations/runbooks/attachment-restore.md cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/restore cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/restore apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/RestoredStateIntegrityIT.java
git commit -m "ops: add multi-AZ backup and verified restore"
```

### Task 6: Implement Regional Disaster Recovery And Evidence-Based Failover

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Modify: `infra/opentofu/main.tf`
- Modify: `infra/opentofu/roots.json`
- Modify: `infra/opentofu/environments/aws-tokyo-primary/main.tf`
- Modify: `infra/opentofu/environments/aws-tokyo-primary/variables.tf`
- Modify: `infra/opentofu/environments/aws-tokyo-primary/outputs.tf`
- Modify: `infra/opentofu/environments/aws-tokyo-primary/environment.auto.tfvars.example`
- Modify: `infra/opentofu/environments/aws-osaka-warm-dr/main.tf`
- Modify: `infra/opentofu/environments/aws-osaka-warm-dr/variables.tf`
- Modify: `infra/opentofu/environments/aws-osaka-warm-dr/outputs.tf`
- Modify: `infra/opentofu/environments/aws-osaka-warm-dr/environment.auto.tfvars.example`
- Create: `infra/opentofu/modules/aws-regional-dr/main.tf`
- Create: `infra/opentofu/modules/aws-regional-dr/variables.tf`
- Create: `infra/opentofu/modules/aws-regional-dr/outputs.tf`
- Create: `infra/opentofu/tests/regional-dr.tftest.hcl`
- Create: `operations/dr/aws-japan-failover.yaml`
- Create: `operations/dr/report-signature-policy.yaml`
- Create: `contracts/json-schema/operations-dr-report.schema.json`
- Create: `docs/operations/runbooks/regional-failover.md`
- Create: `docs/operations/runbooks/regional-failback.md`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/dr/Preflight.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/dr/Drill.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/dr/Report.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/dr/Verify.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/dr/DrillTest.java`

- [ ] **Step 1: Write failing DR preflight and RPO/RTO measurement tests**

Test database log/replica lag, last independently anchored audit event, S3 replication lag as a separate field, DNS/ingress readiness, exact image/configuration digest parity, KMS/trust readiness, provider webhook endpoint switch, merge/publication credential fencing, Agent Pack OCI replica and verified-cache readiness, monotonically increasing Pack distribution epoch, notification deduplication, split-brain prevention, start/end timestamps, control-plane RPO, attachment RPO, and core RTO. Race a pre-failover Pack capability against the regional transition and prove it can neither consume in the recovery region nor stream a byte after the primary fence; only a newly issued recovery-epoch capability may claim a fully verified fixed-digest object. Add report cases for wrong immutable environment/release/tool/profile digest, missing transition receipt, unsigned primary baseline, unfenced credential, split-brain observation, stale/duplicate distribution epoch, tag-only or digest-mismatched OCI replica, unverified or wrong-key cache object, failed RPO/RTO relabeled pass, stale key-version readiness, failed failback proof, tenant signer, altered object version/receipt, and insufficient retention.

- [ ] **Step 2: Run preflight before the warm region exists**

Run: `./gradlew :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ops.dr.*' :cmd:accordctl:installDist && cmd/accordctl/build/install/accordctl/bin/accordctl ops dr preflight --profile aws-japan --environment staging`

Expected: FAIL with a sorted list containing missing warm-region database target, object replication evidence, workload digests, fenced credentials, and recovery keys.

- [ ] **Step 3: Implement the failover/failback state machine**

Retain the identical provider contracts created in Task 4 in both environment `versions.tf` files:

```hcl
terraform {
  required_version = "= 1.9.1"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 5.100.0"
    }
  }
}
```

Each `backend.tf` contains only the partial backend declaration:

```hcl
terraform {
  backend "s3" {}
}
```

Task 4's Tokyo `backend.hcl.example` remains:

```hcl
bucket         = "accord-tofu-state-prod-tokyo"
key            = "production/tokyo/accord.tfstate"
region         = "ap-northeast-1"
use_lockfile = true
kms_key_id     = "alias/accord-tofu-state-prod-tokyo"
encrypt        = true
```

Task 4's Osaka `backend.hcl.example` remains:

```hcl
bucket         = "accord-tofu-state-prod-osaka"
key            = "production/osaka/accord.tfstate"
region         = "ap-northeast-3"
use_lockfile = true
kms_key_id     = "alias/accord-tofu-state-prod-osaka"
encrypt        = true
```

CI materializes protected copies at `build/secrets/opentofu/aws-tokyo-primary.backend.hcl` and `build/secrets/opentofu/aws-osaka-warm-dr.backend.hcl`, confirms those paths are ignored, then runs `tofu -chdir=infra/opentofu/environments/aws-tokyo-primary init -backend-config=../../../../build/secrets/opentofu/aws-tokyo-primary.backend.hcl -input=false -lockfile=readonly` and `tofu -chdir=infra/opentofu/environments/aws-osaka-warm-dr init -backend-config=../../../../build/secrets/opentofu/aws-osaka-warm-dr.backend.hcl -input=false -lockfile=readonly`. The examples are never accepted as proof of backend availability, and `verify-opentofu.ps1` always uses `-backend=false`.

Each environment `variables.tf` retains Task 4's closed `region`, `account_id`, `availability_zones`, `network`, `retention`, `capacity`, and `data_processing` objects and adds `peer_region` plus signed cross-region identifiers with validation; there are no region/account defaults. Tokyo and Osaka retain every foundation/data module source, then both add `../../modules/aws-regional-dr`; Osaka configures warm capacity rather than omitting workloads. Both roots configure `aws-agent-pack-distribution` with the same signed release-index digest, immutable OCI digest replication, region-local verified-cache/KMS resources, the exact Gateway IRSA subject, and a shared epoch authority represented only by signed identifiers. Neither root reads the other's state through a local path. Cross-region identifiers arrive as protected pipeline inputs whose digest is recorded in plan/apply and DR evidence. Each `outputs.tf` marks endpoints and resource identifiers `sensitive = true` except immutable resource ARNs used in signed deployment evidence. Reusable modules contain no Japan region literal.

Append this exact block to the integration-test `main.tf`:

```hcl
module "aws_regional_dr" {
  source = "./modules/aws-regional-dr"

  environment                       = "test"
  primary_region                    = "ap-northeast-1"
  recovery_region                   = "ap-northeast-3"
  maximum_control_plane_rpo_seconds = 300
  maximum_core_rto_seconds          = 14400
  require_primary_credential_fence  = true
  report_object_lag_separately      = true
  failback_required_proofs          = ["exact-digest", "explicit-incident-approval", "new-backup", "reconciliation", "reverse-replication"]
}
```

`regional-dr.tftest.hcl` uses `mock_provider "aws" {}` and asserts that Tokyo/Osaka are distinct, credentials are fenced before promotion, both roots bind the same immutable Pack release digest but distinct cache/KMS resources, Gateway IAM is fixed to one regional ServiceAccount/origin, distribution epoch fencing precedes traffic promotion, object lag is not substituted for database lag, and failback requires all five declared proofs.

Create `operations/dr/aws-japan-failover.yaml`:

```yaml
schema_version: "1.0"
profile: aws-japan
primary_region: ap-northeast-1
recovery_region: ap-northeast-3
maximum_control_plane_rpo_seconds: 300
maximum_core_rto_seconds: 14400
steps:
  - declare_incident_and_freeze_writes_merges_publication
  - record_primary_database_audit_and_object_watermarks
  - verify_database_lag_and_report_object_lag_separately
  - fence_primary_database_and_security_credentials
  - fence_primary_agent_pack_gateway_and_increment_distribution_epoch
  - promote_recovery_database_and_enable_recovery_keys
  - deploy_exact_signed_release_digest
  - verify_fixed_digest_pack_replica_and_rebuild_recovery_cache
  - switch_ingress_webhooks_and_read_only_queries
  - reconcile_provider_artifact_and_notification_facts
  - resume_controlled_writes_then_security_mutations
failback_requires: [new-backup, reverse-replication, exact-digest, reconciliation, explicit-incident-approval]
```

The Java/Picocli CLI persists each transition and evidence digest, rejects out-of-order or unsigned steps, and never infers attachment RPO from the database watermark. Agent Context Task 5's capability authority binds every row and structured response to the issuance `distribution_epoch`; the Gateway reads the current epoch inside the same PostgreSQL transaction that claims the capability. Failover first disables the primary Gateway Service/IRSA and fences its OCI/cache policy, then CAS-increments the epoch, verifies the recovery OCI replica by the release metadata's fixed digest and signature, rebuilds or validates the recovery cache, and only then exposes the recovery HTTPS origin. An old-epoch token, a capability for the other origin, an unreplicated digest, or a cache object lacking the current verification receipt fails before consumption and before response headers. Failback increments the epoch again and repeats the same order. Reusable OpenTofu module inputs do not hard-code either region.

`operations-dr-report.schema.json` extends the common evidence metadata with profile/config/release digests, signed pre-drill provider/database/object/key/workload baselines, ordered transition receipts, primary/recovery environment IDs, database and attachment watermarks/RPO, core RTO, DNS/webhook switches, credential-fence proof, image/config parity, Pack release-index/OCI-replica/cache-verification digests, previous/current distribution epochs, stale-capability rejection receipt, Gateway workload/IRSA/KMS policy digests, reconciliation/failback proofs, zero-tolerance deltas, cleanup/baseline restoration, and overall result. The drill signs and immutably stores truthful failure evidence but exits `1` unless every transition is confirmed, split brain is absent, RPO/RTO pass, primary Gateway authority is fenced, the epoch advances exactly once, stale capabilities stream zero bytes, recovery serves only the signed digest, and baseline/failback invariants close. `ops dr verify` independently validates platform DSSE/trust/schema/digests/receipt and writes a summary only for `result=pass`.

Keep the closed four-root inventory and replace only Tokyo, Osaka, and integration `modules` arrays with the exact sets below:

```json
{
  "schema_version": "1.0",
  "opentofu_version": "1.9.1",
  "roots": [
    {
      "id": "aws-osaka-warm-dr",
      "path": "infra/opentofu/environments/aws-osaka-warm-dr",
      "backend": "s3-partial",
      "lock": "infra/opentofu/environments/aws-osaka-warm-dr/.terraform.lock.hcl",
      "tests": false,
      "providers": { "registry.opentofu.org/hashicorp/aws": "5.100.0" },
      "modules": ["accord-autoscaling", "accord-foundation-contract", "aws-agent-pack-distribution", "aws-backup", "aws-budgets", "aws-container-registry", "aws-edge", "aws-eks", "aws-network", "aws-object-storage", "aws-operations-evidence", "aws-postgresql", "aws-regional-dr", "aws-telemetry", "aws-temporal", "aws-workload-identity"]
    },
    {
      "id": "aws-state-backends",
      "path": "infra/opentofu/bootstrap/aws-state-backends",
      "backend": "organization-governance-s3-partial",
      "lock": "infra/opentofu/bootstrap/aws-state-backends/.terraform.lock.hcl",
      "tests": false,
      "providers": { "registry.opentofu.org/hashicorp/aws": "5.100.0" },
      "modules": ["aws-state-backends"]
    },
    {
      "id": "aws-tokyo-primary",
      "path": "infra/opentofu/environments/aws-tokyo-primary",
      "backend": "s3-partial",
      "lock": "infra/opentofu/environments/aws-tokyo-primary/.terraform.lock.hcl",
      "tests": false,
      "providers": { "registry.opentofu.org/hashicorp/aws": "5.100.0" },
      "modules": ["accord-autoscaling", "accord-foundation-contract", "aws-agent-pack-distribution", "aws-backup", "aws-budgets", "aws-container-registry", "aws-edge", "aws-eks", "aws-network", "aws-object-storage", "aws-operations-evidence", "aws-postgresql", "aws-regional-dr", "aws-telemetry", "aws-temporal", "aws-workload-identity"]
    },
    {
      "id": "integration-test",
      "path": "infra/opentofu",
      "backend": "local-ephemeral",
      "lock": "infra/opentofu/.terraform.lock.hcl",
      "tests": true,
      "providers": {
        "registry.opentofu.org/hashicorp/aws": "5.100.0",
        "registry.opentofu.org/hashicorp/kubernetes": "2.36.0"
      },
      "modules": [
        "accord-autoscaling",
        "accord-foundation-contract",
        "aws-agent-pack-distribution",
        "aws-backup",
        "aws-budgets",
        "aws-container-registry",
        "aws-edge",
        "aws-eks",
        "aws-network",
        "aws-object-storage",
        "aws-operations-evidence",
        "aws-postgresql",
        "aws-regional-dr",
        "aws-state-backends",
        "aws-telemetry",
        "aws-temporal",
        "aws-workload-identity"
      ]
    }
  ]
}
```

The root array and every module array are ordinal-sorted; a fifth root is forbidden. No lock is regenerated: `aws-regional-dr` uses the already pinned AWS provider, and all four lock hashes must remain byte-identical.

- [ ] **Step 4: Execute and measure a full regional exercise**

Run:

```bash
pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json
tofu -chdir=infra/opentofu test -filter=tests/regional-dr.tftest.hcl
./gradlew :cmd:accordctl:test :cmd:accordctl:installDist
cmd/accordctl/build/install/accordctl/bin/accordctl ops dr drill --profile aws-japan --environment staging --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --output build/operations/dr-report.dsse.json --confirm-drill
cmd/accordctl/build/install/accordctl/bin/accordctl ops dr verify --evidence build/operations/dr-report.dsse.json --summary-output build/operations/dr-report.json
```

Expected: the verifier initializes, formats, validates, and tests exactly four declared roots with read-only locks and reports `modules=17`; the regional test passes; database control-plane RPO is at most 300 seconds, attachment lag is independently reported, core RTO is at most 14,400 seconds, old-region Pack capabilities fail after the epoch fence, the recovery Gateway serves only a fully verified fixed-digest replica/cache object, no duplicate action/notification/merge or duplicate capability consumption occurs, failback remains blocked until reconciliation closes, and only the platform-signed receipt-bound `pass` report verifies.

- [ ] **Step 5: Commit regional recovery**

```bash
git add infra/opentofu/main.tf infra/opentofu/roots.json infra/opentofu/environments/aws-tokyo-primary/main.tf infra/opentofu/environments/aws-tokyo-primary/variables.tf infra/opentofu/environments/aws-tokyo-primary/outputs.tf infra/opentofu/environments/aws-tokyo-primary/environment.auto.tfvars.example infra/opentofu/environments/aws-osaka-warm-dr/main.tf infra/opentofu/environments/aws-osaka-warm-dr/variables.tf infra/opentofu/environments/aws-osaka-warm-dr/outputs.tf infra/opentofu/environments/aws-osaka-warm-dr/environment.auto.tfvars.example infra/opentofu/modules/aws-regional-dr infra/opentofu/tests/regional-dr.tftest.hcl operations/dr contracts/json-schema/operations-dr-report.schema.json docs/operations/runbooks/regional-failover.md docs/operations/runbooks/regional-failback.md cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/dr cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/dr
git commit -m "ops: add measured regional disaster recovery"
```

### Task 7: Implement Key Rotation, Revocation, And Compromise Recovery

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Modify: `apps/control-plane/modules/audit/build.gradle`
- Create: `apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/KeyValidityMigrationIT.java`
- Create: `database/control-plane/migrations/V090__key_validity_overlay.sql`
- Create: `database/signing-service/migrations/V004__key_lifecycle.sql`
- Modify: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/trust/Model.java`
- Modify: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/trust/Postgres.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/trust/Lifecycle.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/trust/LifecycleTest.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/trust/MigrationTest.java`
- Modify: `infra/opentofu/main.tf`
- Modify: `infra/opentofu/roots.json`
- Modify: `infra/opentofu/environments/aws-tokyo-primary/main.tf`
- Modify: `infra/opentofu/environments/aws-osaka-warm-dr/main.tf`
- Create: `infra/opentofu/modules/aws-purpose-keys/main.tf`
- Create: `infra/opentofu/modules/aws-purpose-keys/variables.tf`
- Create: `infra/opentofu/modules/aws-purpose-keys/outputs.tf`
- Create: `infra/opentofu/tests/key-separation.tftest.hcl`
- Create: `operations/keys/purpose-policy.yaml`
- Create: `operations/keys/report-signature-policy.yaml`
- Create: `contracts/json-schema/operations-key-lifecycle-report.schema.json`
- Create: `docs/operations/runbooks/key-rotation.md`
- Create: `docs/operations/runbooks/key-compromise.md`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/keys/Rotate.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/keys/Revoke.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/keys/Report.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/keys/Verify.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/keys/KeysTest.java`

- [ ] **Step 1: Write failing purpose separation and validity-overlay tests**

Cover tenant publication, strict merge, break glass, audit anchor, customer CI, notification, and attachment scan purposes; the independent platform operations-evidence KMS lanes; artifact provenance and telemetry keys; and the non-tenant `browser_session_csrf` envelope-encryption key. Test valid-from/until, planned rotation overlap, unknown/known compromise time, independently anchored historical evidence, token expiry/nonce, `new_use_blocked`, `trust_revoked`, `credential_compromised`, and runtime suspension/revalidation without rewriting history. Prove only `control-api` can `Encrypt`/`Decrypt` `browser_session_csrf`; control-worker, Agent, Publisher, Merge Controller, all BreakGlass profiles, GA collectors/verifiers, and tenant signing service are denied. Add report cases for a missing regional key version, shared key/policy, excessive IAM, stale trust bundle, failed rotation/compromise drill relabeled pass, tenant-signed operations report, wrong release/environment/tool/policy digest, receipt mismatch, and insufficient retention.

Add this exact test dependency to `apps/control-plane/modules/audit/build.gradle`:

```java
testImplementation(testFixtures(project(":database:control-plane")))
```

`KeyValidityMigrationIT` must start `PostgreSQLContainer<Nothing>("postgres:17.5")`, call `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)` immediately after `start()` and before `Flyway.configure()`, migrate the complete control-plane migration directory through `V090`, and query `pg_class`, `pg_policy`, and `information_schema.role_table_grants`. Assert that `public.key_validity_overlay` exists, has forced RLS and the exact `tenant_isolation` policy, exposes no grant to `PUBLIC`, and grants runtime DML only after the policy is installed. The test also opens an `accord_api` connection, runs `SELECT set_config('app.tenant_id', '10000000-0000-0000-0000-000000000001', true)` inside a transaction, and proves tenant A cannot read tenant B's overlay.

- [ ] **Step 2: Run key lifecycle and infrastructure tests before implementation**

Run:

```bash
./gradlew :apps:control-plane:modules:audit:test --tests '*KeyValidityMigrationIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
./gradlew :security-services:signing-service:test :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ops.keys.*'
pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json
tofu -chdir=infra/opentofu test -filter=tests/key-separation.tftest.hcl
```

Expected: FAIL because `V090`, its forced tenant policy, lifecycle evaluation, purpose-separated keys, rotation state, and validity-overlay persistence are absent. The shared role bootstrap succeeds before Flyway; no test creates roles after migration.

- [ ] **Step 3: Implement the trust record and rotation protocol**

Create `V090__key_validity_overlay.sql` exactly as a tenant migration and invoke the standard enforcement function before either runtime grant:

```sql
SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE key_validity_overlay (
    tenant_id uuid NOT NULL,
    key_id varchar(255) NOT NULL,
    purpose varchar(64) NOT NULL CHECK (purpose IN (
      'requirement_publication', 'strict_merge', 'break_glass', 'audit_anchor',
      'customer_ci', 'notification', 'attachment_scan'
    )),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    compromised_at timestamptz,
    revoked_at timestamptz,
    revocation_mode varchar(32) CHECK (revocation_mode IN ('new_use_blocked', 'trust_revoked', 'credential_compromised')),
    independent_anchor_time timestamptz,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id, key_id, purpose),
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    CHECK (compromised_at IS NULL OR compromised_at >= valid_from),
    CHECK (revoked_at IS NULL OR revocation_mode IS NOT NULL),
    CHECK (independent_anchor_time IS NULL OR independent_anchor_time >= valid_from)
);

SELECT accord_security.enforce_tenant_table('public.key_validity_overlay'::regclass);

GRANT SELECT ON key_validity_overlay TO accord_api;
GRANT SELECT, INSERT, UPDATE ON key_validity_overlay TO accord_worker;
```

`V004__key_lifecycle.sql` is signing-service-local and extends the V003 strict-subject reservation catalog without editing either immutable migration. It creates tenant-scoped key-version/trust-transition rows, invokes the signing database's standard tenant enforcement before grants, enables and forces RLS, denies `PUBLIC`, gives the runtime signer only current-key reads plus narrowly scoped transition execution, and gives the lifecycle worker write access only through stored procedures with append-only audit rows. `MigrationTest.java` migrates V001 through V004 and inspects `pg_class`, `pg_policy`, functions, and grants for both tenant isolation and least privilege.

Platform operations-evidence key history is not inserted into V090 or the signing-service database. `aws-operations-evidence` publishes a signed platform trust bundle and immutable compromise/rotation records for the three certification IRSA evidence lanes, the fixed approval-session and approval-record purposes, every certification unit's distinct promotion-attempt, promotion-resolution, and promotion-receipt purposes, and the separately purposed contract package publisher and acceptance attestor. The common evidence verifier resolves those records directly and rejects a payload signed under another listed purpose or another unit's promoter key. GitHub orchestration roles, workforce submitters, and approval submission integration roles are recorded in receipts but have no signing key record because they cannot call KMS. `browser_session_csrf` is likewise not a tenant `SigningPurpose`: `aws-purpose-keys` provisions a regional platform KMS envelope-encryption key and alias/version record, and only the `control-api` IRSA role receives `kms:Encrypt`, `kms:Decrypt`, `kms:GenerateDataKey`, and `kms:DescribeKey` constrained by the CSRF encryption context. Every other workload receives an explicit deny.

Use these immutable Java records:

```java
import java.time.Instant;

public record KeyTrustRecord(
    String keyId,
    String purpose,
    Instant validFrom,
    Instant validUntil,
    Instant compromisedAt,
    Instant revokedAt,
    String revocationMode,
    Instant independentAnchorTime
) {}

public record ValidityDecision(
    boolean historicalProofValid,
    boolean newUseAllowed,
    boolean requiresSuspension,
    Instant recoverFromAnchor
) {}
```

Rotate machine-signing keys every 90 days, overlap verification for 14 days, switch signing only after all workloads report the new key, and keep workload credentials at one hour or less. A compromise immediately fences credentials, anchors the incident, marks affected current objects suspended/revalidation-required, and recovers from the last independently trusted point.

Append this exact block to the integration-test root:

```hcl
module "aws_purpose_keys" {
  source = "./modules/aws-purpose-keys"

  environment = "test"
  tenant_signing_purposes = [
    "attachment_scan",
    "audit_anchor",
    "break_glass",
    "customer_ci",
    "notification",
    "requirement_publication",
    "strict_merge"
  ]
  platform_kms_purposes = [
    "artifact_provenance",
    "browser_session_csrf",
    "telemetry"
  ]
  browser_session_csrf_encrypt_decrypt_service_accounts = ["control-api"]
  browser_session_csrf_explicit_deny_service_accounts = [
    "control-worker", "agent-runtime", "agent-pack-gateway", "requirement-publisher", "merge-controller",
    "break-glass-merge", "break-glass-protection", "break-glass-fence",
    "staging-operations", "ga-collector", "ga-verifier"
  ]
  required_key_version_regions     = ["ap-northeast-1", "ap-northeast-3"]
  rotation_days                    = 90
  verification_overlap_days        = 14
  workload_credential_max_seconds = 3600
}
```

The verified-cache KMS key remains owned by `aws-agent-pack-distribution`, not by the tenant signing registry or this general platform-purpose list. Its encryption context requires the exact environment, region, release digest, and cache-object digest; only the regional `agent-pack-gateway` IRSA role may encrypt/decrypt/data-key under that context. Key tests explicitly deny control-api/worker, Agent Runtime, Publisher, Merge Controller, BreakGlass, operations evidence lanes, and the opposite-region Gateway role, and prove the Gateway is denied every tenant-signing, browser-session, and operations-evidence key.

Source `aws-purpose-keys` from both Tokyo and Osaka roots with their regional aliases and cross-region trust-version inputs. Replace both production module arrays with this exact final sorted set:

```json
[
  "accord-autoscaling",
  "accord-foundation-contract",
  "aws-agent-pack-distribution",
  "aws-backup",
  "aws-budgets",
  "aws-container-registry",
  "aws-edge",
  "aws-eks",
  "aws-network",
  "aws-object-storage",
  "aws-operations-evidence",
  "aws-postgresql",
  "aws-purpose-keys",
  "aws-regional-dr",
  "aws-telemetry",
  "aws-temporal",
  "aws-workload-identity"
]
```

The integration array is the same list plus ordinal-positioned `"aws-state-backends"` after `"aws-regional-dr"`. All four existing provider locks remain byte-identical because the module uses the pinned AWS provider.

`operations-key-lifecycle-report.schema.json` extends common evidence metadata with tenant-signing/platform-key/trust-bundle inventory digests, per-purpose and per-region key IDs/versions, exact IAM allow/deny evidence, rotation old/new/overlap times, workload adoption receipts, compromise time and recovery anchor, suspended/revalidated object counts, historical-proof decisions, DR key-version availability, and overall result. Rotation and compromise commands store raw observations, sign through the platform operations-evidence lane, and create an immutable receipt. `ops keys verify` rejects any shared purpose key, missing regional version, overbroad IAM, unclosed compromise recovery, tenant signature, or non-pass result.

- [ ] **Step 4: Execute planned rotation and compromise drills**

Run:

```bash
./gradlew :apps:control-plane:modules:audit:test --tests '*KeyValidityMigrationIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
./gradlew :security-services:signing-service:test :cmd:accordctl:test :cmd:accordctl:installDist
pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json
tofu -chdir=infra/opentofu test -filter=tests/key-separation.tftest.hcl
cmd/accordctl/build/install/accordctl/bin/accordctl ops keys rotate --purpose requirement_publication --environment staging --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --evidence-output build/operations/keys/requirement-publication-rotation.json --confirm
cmd/accordctl/build/install/accordctl/bin/accordctl ops keys rotate --purpose browser_session_csrf --environment staging --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --evidence-output build/operations/keys/browser-session-csrf-rotation.json --confirm
cmd/accordctl/build/install/accordctl/bin/accordctl ops keys compromise-drill --fixture tests/fixtures/security/compromised-ci-key.json --environment staging --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --evidence-output build/operations/keys/compromise.json --confirm
cmd/accordctl/build/install/accordctl/bin/accordctl ops keys collect --input-dir build/operations/keys --output build/operations/key-lifecycle-report.dsse.json
cmd/accordctl/build/install/accordctl/bin/accordctl ops keys verify --evidence build/operations/key-lifecycle-report.dsse.json --summary-output build/operations/key-lifecycle-report.json
```

Expected: `KeyValidityMigrationIT`, signing V004 migration checks, and the global `TenantRlsTest` pass; the root verifier reports exactly four roots and 18 covered modules; new signatures use only the new business-purpose key; only control-api can decrypt new/overlap CSRF envelopes in Tokyo and Osaka; the Gateway can use only its separate verified-cache key/context and cannot use browser-session, tenant-signing, or operations-evidence keys; allowed anchored history still verifies; new use of old keys fails; affected objects suspend; recovery starts at the last trusted anchor; no historical payload changes; and the platform-signed immutable key-lifecycle report verifies with `result=pass`.

- [ ] **Step 5: Commit key lifecycle operations**

```bash
git add apps/control-plane/modules/audit/build.gradle apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/KeyValidityMigrationIT.java database/control-plane/migrations/V090__key_validity_overlay.sql database/signing-service/migrations/V004__key_lifecycle.sql security-services/signing-service/src/main/java/com/inforvans/accord/signing/trust security-services/signing-service/src/test/java/com/inforvans/accord/signing/trust infra/opentofu/main.tf infra/opentofu/roots.json infra/opentofu/environments/aws-tokyo-primary/main.tf infra/opentofu/environments/aws-osaka-warm-dr/main.tf infra/opentofu/modules/aws-purpose-keys infra/opentofu/tests/key-separation.tftest.hcl operations/keys contracts/json-schema/operations-key-lifecycle-report.schema.json docs/operations/runbooks/key-rotation.md docs/operations/runbooks/key-compromise.md cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/keys cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/keys
git commit -m "security: add purpose-separated key lifecycle recovery"
```

### Task 8: Enforce SBOM, SLSA, Cosign, And Release Supply-Chain Policy

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/Build.java`
- Create: `.github/workflows/ci.yaml`
- Create: `.github/workflows/security.yaml`
- Create: `.github/workflows/release.yaml`
- Create: `build/images.yaml`
- Create: `release/manifest.schema.json`
- Create: `release/artifact-handoff.schema.json`
- Modify: `apps/control-plane/api/Dockerfile`
- Modify: `apps/control-plane/worker/Dockerfile`
- Modify: `apps/webhook-edge/Dockerfile`
- Create: `apps/web/Dockerfile`
- Create: `apps/agent-runtime/Dockerfile`
- Create: `apps/agent-pack-gateway/Dockerfile`
- Create: `apps/attachment-scanner/Dockerfile`
- Create: `security-services/signing-service/Dockerfile`
- Create: `security-services/requirement-publisher/Dockerfile`
- Create: `security-services/merge-controller/Dockerfile`
- Create: `security-services/break-glass-broker/Dockerfile`
- Modify: `infra/helm/accord/Chart.yaml`
- Generate and commit: `infra/helm/accord/Chart.lock`
- Modify: `infra/helm/accord/values.yaml`
- Modify: `infra/helm/accord/values.schema.json`
- Modify: `infra/helm/accord/templates/workloads.yaml`
- Modify: `infra/helm/accord/templates/serviceaccounts.yaml`
- Modify: `infra/helm/accord/templates/networkpolicies.yaml`
- Create: `infra/helm/accord/templates/poddisruptionbudgets.yaml`
- Create: `infra/helm/accord/templates/topologyspread.yaml`
- Create: `infra/helm/accord/templates/mtls-policies.yaml`
- Create: `infra/helm/accord/templates/secrets-store-csi.yaml`
- Create: `infra/helm/accord/tests/workload-inventory.yaml`
- Modify: `infra/helm/agent-pack-gateway/Chart.yaml`
- Modify: `infra/helm/agent-pack-gateway/values.yaml`
- Create: `infra/helm/agent-pack-gateway/values.schema.json`
- Modify: `infra/helm/agent-pack-gateway/templates/deployment.yaml`
- Modify: `infra/helm/agent-pack-gateway/templates/networkpolicy.yaml`
- Create: `infra/helm/agent-pack-gateway/templates/service.yaml`
- Create: `infra/helm/agent-pack-gateway/templates/serviceaccount.yaml`
- Create: `infra/helm/agent-pack-gateway/templates/poddisruptionbudget.yaml`
- Create: `infra/helm/agent-pack-gateway/templates/secrets-store-csi.yaml`
- Create: `infra/helm/agent-pack-gateway/tests/workload-isolation.yaml`
- Create: `infra/policy/kubernetes/workload-isolation.rego`
- Create: `infra/policy/kubernetes/workload-isolation_test.yaml`
- Create: `infra/policy/supply-chain/release.rego`
- Create: `infra/policy/supply-chain/admission.rego`
- Create: `infra/policy/supply-chain/release_test.yaml`
- Create: `operations/supply-chain/policy.yaml`
- Create: `operations/supply-chain/allowed-builders.yaml`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/Verify.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/VerifyTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/Publish.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/PublishTest.java`

- [ ] **Step 1: Write failing signed-release and admission policy tests**

Cover pinned GitHub actions/dependencies/base-image digests, OIDC builder identity, protected source tags, immutable image digests, CycloneDX and SPDX SBOMs, SLSA v1 provenance, Cosign signatures, vulnerability exploitability, malware/license policy, Agent Pack manifest/signature/lock, exact release-bundle linkage, reproducibility for Pack/contracts, and rejection of unsigned, mutable, unregistered, or wrong-builder artifacts. The release-author matrix rejects a missing/malformed author; an author supplied through workflow input, commit metadata, PR text, or build manifest; mismatch among protected workflow OIDC `actor_id`, GitHub audit/deployment event, and pinned workforce-directory mapping; ambiguous/deactivated/non-human mapping; substituted natural-person subject; stale directory observation; and an author object omitted from SLSA materials or the signed release manifest. The release-artifact transport matrix also rejects a missing/extra handoff field, symlink or mutable path, non-content-addressed key, overwrite, absent object version, wrong checksum/digest/receipt, swapped manifest/Cosign/provenance body, handoff from another release/source/workflow run, and a consumer that can list or read any other release prefix. Include the Gateway image and the immutable Agent Pack OCI artifact as separate signed release subjects; require the Gateway image provenance to bind the exact capability/cache contract version and require the Pack subject to bind release metadata, resources, SBOM, compatibility/certification reports, and fixed OCI digest. Helm inventory tests require every production workload and reject a missing Agent Pack Gateway, Publisher, Merge Controller, BreakGlass profile, ServiceAccount, IRSA annotation, mTLS identity, PDB, topology spread, default-deny NetworkPolicy, explicit egress allowlist, or CSI-delivered secret.

- [ ] **Step 2: Run policy and verifier tests against unsigned fixtures**

Run: `conftest verify -p infra/policy/supply-chain infra/policy/supply-chain/release_test.yaml && ./gradlew :cmd:accordctl:test --tests 'com.inforvans.accord.cli.release.*'`

Expected: FAIL with deterministic violations for unsigned images, absent provenance/SBOM, mutable tags, and unregistered Agent Pack digest.

- [ ] **Step 3: Implement build manifest and release policy**

Create `operations/supply-chain/policy.yaml`:

```yaml
schema_version: "1.0"
required:
  - immutable-source-commit
  - locked-language-dependencies
  - pinned-base-image-digest
  - cyclonedx-sbom
  - spdx-sbom
  - slsa-v1-provenance
  - cosign-keyless-signature
  - vulnerability-malware-license-reports
  - certified-release-bundle-digest
  - authoritative-release-author
deny:
  known_exploitable_critical: true
  unsigned_artifact: true
  mutable_deployment_reference: true
  unregistered_builder: true
  unregistered_agent_pack: true
```

The release workflow builds every Java service and the self-contained `accordctl` runtime image, the Python runtime, React assets, and the Agent Pack OCI artifact in isolated jobs, publishes by digest, signs with GitHub OIDC, and records one manifest linking source, contracts, database schema, models/prompts/analyzers, the canonical `certification/units.yaml` bytes/schema/digest, Agent Pack, Gateway contract/image, images, SBOMs, provenance, policies, and deployment configuration. Before building, the protected release job obtains its own GitHub OIDC token, verifies repository/ref/workflow/environment and non-reusable `run_id`, reads the matching immutable GitHub audit/deployment event with read-only permission, and maps the trusted numeric `actor_id` through the pinned workforce directory to exactly one active natural-person subject. No workflow input, Git commit/PR author, environment variable, or manifest field may supply that subject. It freezes a closed `release_author` object containing the natural-person subject, trusted GitHub actor ID, OIDC token digest/JTI, audit-event digest, directory snapshot/version/digest, and observation time; no role-binding version is copied because approval open resolves current membership later.

The release-verifier job runs in a digest-pinned bootstrap image containing Git and GNU coreutils, and that image digest is a required provenance material. `release/manifest.schema.json` is closed at every object and requires the immutable source commit, canonical release-bundle digest, builder identity, workflow ref/run ID, authoritative `release_author`, certification-unit-map digest, every artifact name/media type/SHA-256, Cosign bundle locator, SLSA provenance locator, both SBOM locators, and deployment digest. SLSA provenance materials include the canonical author-object digest, while the signed manifest contains the exact object. `release build` emits canonical `build/release/manifest.json`, `build/release/manifest.cosign.bundle.json`, the published `build/release/accordctl` binary, `build/release/accordctl.cosign.bundle.json`, and `build/release/accordctl.intoto.jsonl`; the signed manifest's `accordctl`, Gateway, Pack, unit-map, and release-author subjects/provenance must bind exact bytes and source commit. Task 13 schema extensions must retain this required object unchanged. Kubernetes admission verifies again before scheduling.

`release/artifact-handoff.schema.json` is a closed strings-only RFC 8785 JCS locator, not a signed authorization. It requires schema/handoff version, source commit, release-bundle digest, protected workflow ref/run ID, canonical release-author-object digest, and for the manifest, manifest Cosign bundle, `accordctl` SLSA provenance, and immutable release receipt: deterministic content-addressed object key, SHA-256 body digest, exact object version, media type, and retention deadline. The handoff additionally binds its own deterministic object key/version/checksum and the publishing GitHub OIDC identity. It permits no URL, bucket, region, credential, local path, optional alias, signature, or unknown property. Integrity comes from create-only release-digest keys, compliance Object Lock, S3 checksum/version, and the immutable receipt; every consumer must still download and independently verify all referenced bodies.

`Publish.java` exposes exactly `release publish-artifacts --manifest <manifest.json> --manifest-cosign-bundle <bundle.json> --accordctl-provenance <provenance.intoto.jsonl> --manifest-schema release/manifest.schema.json --handoff-schema release/artifact-handoff.schema.json --policy operations/supply-chain/policy.yaml --expected-source-commit <40-lowercase-hex> --expected-release-bundle <sha256> --handoff-output <release-artifact-handoff.json> --confirm`. Under the protected release publisher identity it first re-runs closed-schema, Cosign issuer/subject, SLSA builder/material/subject, source, release-bundle, and authoritative-author checks; then writes all four bodies to deterministic `releases/<release-bundle-digest>/...` keys with create-only semantics and 400-day COMPLIANCE Object Lock. It reads back exact bytes, versions, checksums, retention, and CloudTrail identity before atomically writing the unsigned handoff. A retry may adopt only byte-identical retained objects. Unknown put outcome is resolved by exact-key/version read-back, never a second key or overwrite, and exits `3` if truth cannot be established. Approval and promotion identities later receive `GetObjectVersion` only for this exact release digest prefix and cannot list, write, or select another bundle.

The central `infra/helm/accord` chart is the only production release and has one closed workload inventory: `control-api`, `control-worker`, `web`, `webhook-edge`, `agent-runtime`, `agent-pack-gateway`, `attachment-scanner`, `signing-service`, `requirement-publisher`, `merge-controller`, `break-glass-merge`, `break-glass-protection`, `break-glass-fence`, `otel-collector`, and Temporal workers. Agent Context Task 5 owns the Gateway component chart; the central chart consumes it as a version-pinned local dependency recorded in `Chart.lock` and supplies only validated production values. Argo CD deploys no independent Gateway release, and the central `workloads.yaml` must not render a duplicate Gateway Deployment. Each workload has a distinct ServiceAccount and IRSA role, digest-only image, non-root/read-only filesystem/seccomp/capability policy, resource requests/limits, probes, PDB, three-AZ topology spread, mTLS identity, default-deny ingress/egress, explicit dependency egress, and Secrets Store CSI mounts with no secret values in Helm. The Gateway accepts ingress only from the same-origin edge, can egress only to identity/session verification, PostgreSQL, telemetry, the exact OCI registry endpoints, the verified-cache bucket/KMS endpoint, and DNS, and its IRSA role matches the `aws-agent-pack-distribution` policy output. It has no customer-Git, Provider-mutation, tenant-signing, browser-session-key, or operations-evidence access. The three BreakGlass profiles use the same signed image but distinct command profile, queue audience, ServiceAccount, IRSA/Provider credential, and NetworkPolicy; no role has two action profiles. Control-api has no Provider mutation credential. Only control-api receives the `browser_session_csrf` KMS encryption/decryption grant.

- [ ] **Step 4: Build twice and verify signatures, provenance, and admission**

Run: `./gradlew :apps:agent-pack-gateway:test :cmd:accordctl:test :cmd:accordctl:installDist && cmd/accordctl/build/install/accordctl/bin/accordctl release build --manifest build/images.yaml --reproducibility-check && cmd/accordctl/build/install/accordctl/bin/accordctl release verify --manifest build/release/manifest.json --policy operations/supply-chain/policy.yaml && cmd/accordctl/build/install/accordctl/bin/accordctl release publish-artifacts --manifest build/release/manifest.json --manifest-cosign-bundle build/release/manifest.cosign.bundle.json --accordctl-provenance build/release/accordctl.intoto.jsonl --manifest-schema release/manifest.schema.json --handoff-schema release/artifact-handoff.schema.json --policy operations/supply-chain/policy.yaml --expected-source-commit "$ACCORD_SOURCE_COMMIT" --expected-release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --handoff-output build/release/release-artifact-handoff.json --confirm && helm dependency build infra/helm/accord --skip-refresh && git diff --exit-code -- infra/helm/accord/Chart.lock && helm lint infra/helm/agent-pack-gateway && helm lint infra/helm/accord && helm template accord infra/helm/accord --namespace accord > build/helm/accord.yaml && conftest test build/helm/accord.yaml -p infra/policy/supply-chain -p infra/policy/kubernetes && conftest verify -p infra/policy/kubernetes infra/policy/kubernetes/workload-isolation_test.yaml`

Expected: reproducible contract/Pack outputs match; the closed canonical release manifest, its Cosign bundle, published `accordctl`, binary Cosign bundle, SLSA provenance, and two SBOM formats are emitted with one exact source/release-bundle binding and one independently derived natural-person release author. The manifest/Cosign/provenance/receipt objects are retained under one deterministic release prefix and the closed unsigned release-artifact handoff resolves their exact digests and versions; every swap, overwrite, wrong author, mutable locator, or cross-release read fails. All images including Agent Pack Gateway, Publisher, Controller, and the three BreakGlass profiles plus the immutable Pack OCI subject have valid SBOM/provenance/signature links; every deployment reference is a digest; the central chart renders exactly one Gateway from its locked component dependency; the closed workload inventory, isolation, HA, mTLS, IRSA, NetworkPolicy, cache/OCI/KMS restrictions, and CSI assertions pass; admission has zero denials.

- [ ] **Step 5: Commit supply-chain enforcement**

```bash
git add .github/workflows build/images.yaml release/manifest.schema.json release/artifact-handoff.schema.json apps/control-plane/api/Dockerfile apps/control-plane/worker/Dockerfile apps/webhook-edge/Dockerfile apps/web/Dockerfile apps/agent-runtime/Dockerfile apps/agent-pack-gateway/Dockerfile apps/attachment-scanner/Dockerfile security-services/signing-service/Dockerfile security-services/requirement-publisher/Dockerfile security-services/merge-controller/Dockerfile security-services/break-glass-broker/Dockerfile infra/helm/accord infra/helm/agent-pack-gateway infra/policy/supply-chain infra/policy/kubernetes operations/supply-chain cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/release cmd/accordctl/src/test/java/com/inforvans/accord/cli/release
git commit -m "security: enforce signed Accord release supply chain"
```

### Task 9: Build Fault-Injection And Fact-Based Convergence Drills

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Create: `operations/chaos/scenario.schema.json`
- Create: `operations/chaos/matrix.yaml`
- Create: `operations/chaos/report-signature-policy.yaml`
- Create: `contracts/json-schema/operations-chaos-report.schema.json`
- Create: `operations/chaos/worker-crash-before-call.yaml`
- Create: `operations/chaos/worker-crash-after-call.yaml`
- Create: `operations/chaos/provider-throttle.yaml`
- Create: `operations/chaos/provider-result-unknown.yaml`
- Create: `operations/chaos/webhook-disorder.yaml`
- Create: `operations/chaos/model-timeout.yaml`
- Create: `operations/chaos/object-storage-isolation.yaml`
- Create: `operations/chaos/agent-pack-capability-race.yaml`
- Create: `operations/chaos/agent-pack-oci-cache-failure.yaml`
- Create: `operations/chaos/ci-result-delay.yaml`
- Create: `operations/chaos/key-revocation.yaml`
- Create: `operations/chaos/artifact-promotion-uncertain.yaml`
- Create: `operations/chaos/strict-policy-drift.yaml`
- Create: `operations/chaos/database-failover.yaml`
- Create: `operations/chaos/strict-work-item-authorization.yaml`
- Create: `operations/chaos/strict-requirement-metadata-authorization.yaml`
- Create: `operations/chaos/strict-accepted-candidate-authorization.yaml`
- Create: `operations/chaos/strict-emergency-authorization.yaml`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/chaos/Scenario.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/chaos/Report.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/chaos/Sign.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/chaos/Verify.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/chaos/Runner.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/chaos/RunnerTest.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/chaos/VerifyTest.java`
- Create: `apps/web/tests/e2e/ga/fault-convergence.spec.ts`

- [ ] **Step 1: Write failing scenario-schema and convergence tests**

Require each scenario to declare prerequisites, injection, user-visible state, failed-closed operations, maximum detection, current-fact query, convergence condition, integrity query, cleanup, runbook, and retained evidence. Assert no duplicate external effect, lost event/audit, Patch double apply, false acceptance/completion, cross-tenant disclosure, or false strict guarantee. `agent-pack-capability-race` launches concurrent consumers with one actor-bound capability and proves exactly one atomic claim, at most one byte stream, no raw token in any sink, and a new capability after a failed stream. `agent-pack-oci-cache-failure` injects cache miss/corruption, OCI throttle, signature/digest mismatch, KMS denial, and region-epoch mismatch; every branch fails before capability consumption or byte exposure, then converges only after the exact signed digest is fetched, fully verified, cached under the correct encryption context, and claimed with a current-epoch capability.

Add table-driven report tests for a valid envelope plus changed payload bytes, non-JCS payload, wrong DSSE `payloadType`, wrong release, wrong immutable environment ID, stale or unsigned provider baseline, wrong matrix digest, changed referenced fixture, missing/duplicate scenario, forged/tenant key, wrong platform trust lane, signature outside key validity, scenario time outside report time, convergence only after cleanup, missing evidence object, wrong digest, nonzero authorization replay, missing/wrong strict-subject count key, missing generic Pack outcomes, Pack outcomes on the M5 profile, zero successful Pack claims, nonzero Pack duplicate/unverified-byte/token/stale-epoch/wrong-digest counters, any final `diverged`/`uncertain`, false `all_scenarios_converged`, false baseline restoration, nonzero forbidden/wrong-completed counter, expired retention, receipt mismatch, and unwritable output/summary paths. Assert exit `1` for every known gate failure, exit `2` for invalid invocation (including missing `--confirm-drill`), and exit `3` only when the runner cannot establish whether injection, durable evidence persistence, or cleanup/baseline restoration completed. Injected Provider uncertainty must be observed in the failed-closed intermediate state and then reconciled to final `converged`; otherwise the report is retained as failed evidence and both run/verify exit `1`. No test parses stdout as evidence.

- [ ] **Step 2: Run validation before injectors and evidence checks exist**

Run:

```bash
./gradlew :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ops.chaos.*'
cmd/accordctl/build/install/accordctl/bin/accordctl ops chaos validate --matrix operations/chaos/matrix.yaml --schema operations/chaos/scenario.schema.json
corepack pnpm@10.12.4 --filter @accord/web test:e2e -- tests/e2e/ga/fault-convergence.spec.ts
```

Expected: FAIL with missing typed report, schema, signer/verifier, executable injector, current-fact assertion, or cleanup/evidence field. Failure must occur before an injection when the environment, release, provider baseline, matrix, or output preflight is invalid.

- [ ] **Step 3: Implement executable scenarios and the signed machine-report boundary**

Every scenario validates against this core record:

```json
{
  "scenario_id": "provider-result-unknown-after-call",
  "maximum_detection_seconds": 900,
  "expected_state": "suspended+reconciliation_required",
  "failed_closed_operations": ["publish", "merge", "accept", "complete"],
  "current_fact_source": "provider_metadata_api",
  "forbidden_outcomes": ["blind_retry", "duplicate_effect", "completed", "strict"],
  "required_evidence": ["external_intent", "request_digest", "provider_request_id", "reconciliation_snapshot", "audit_chain"]
}
```

Create these exact Java boundary types in `Report.java`; use value records for digests, commits, timestamps, and environment identity so compact constructors enforce the JSON Schema patterns before any injection:

```java
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

record Sha256Digest(@JsonValue String value) {
    private static final Pattern FORMAT = Pattern.compile("sha256:[0-9a-f]{64}");
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    Sha256Digest {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid sha256 digest");
    }
}

record GitCommitSha(@JsonValue String value) {
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{40}");
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    GitCommitSha {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid Git commit SHA");
    }
}

record EnvironmentId(@JsonValue String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    EnvironmentId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid environment ID");
    }
}

record ScenarioId(@JsonValue String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9]+(?:[-_][a-z0-9]+)*");
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    ScenarioId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid scenario ID");
    }
}

record RunRequest(Path matrixPath, String environment, EnvironmentId expectedEnvironmentId,
                  GitCommitSha releaseCommit, Sha256Digest releaseBundleDigest,
                  Sha256Digest providerBaselineDigest, Path outputPath, boolean confirmDrill) {}

record ScenarioEvidence(ScenarioId scenarioId, String currentFactSource,
                        Sha256Digest injectionReceiptDigest, Sha256Digest convergenceEvidenceDigest,
                        Sha256Digest cleanupBaselineRestorationDigest, String result,
                        List<String> intermediateStates, long forbiddenOutcomeCount,
                        Instant startedAt, Instant completedAt) {}

record MergeSubjectAuthorizationCounts(long workItemPr, long requirementMetadata,
                                       long acceptedDeliveryCandidate, long emergencyChange) {}

record AgentPackDistributionOutcomes(long successfulClaims, long duplicateClaims,
                                     long unverifiedBytesStreamed, long rawTokenExposureTotal,
                                     long staleEpochAcceptanceTotal, long wrongDigestAcceptanceTotal) {}

public record Report(
    String schemaVersion, String reportType, Sha256Digest certifiedReleaseBundleDigest,
    GitCommitSha sourceCommitSha, String logicalEnvironment, EnvironmentId immutableEnvironmentId,
    Sha256Digest toolArtifactDigest, Sha256Digest policyDigest, Sha256Digest inputSetDigest,
    Sha256Digest providerBaselineDigest, Sha256Digest matrixDigest, Sha256Digest fixtureSetDigest,
    Sha256Digest evidenceIndexDigest, String auditCorrelationId, String runId,
    Instant startedAt, Instant completedAt, Instant retentionUntil, String result,
    double normalMergeControllerRatio, MergeSubjectAuthorizationCounts mergeSubjectAuthorizationCounts,
    AgentPackDistributionOutcomes agentPackDistributionOutcomes, long authorizationReplayTotal,
    boolean allScenariosConverged, boolean providerBaselineRestored, long forbiddenOutcomeTotal,
    long wrongCompletedTotal, List<ScenarioEvidence> scenarioEvidence, String uncertainResultState,
    String breakGlassAssuranceState, Boolean strictRestoredAfterNewContextCandidateAcceptance
) {}

interface ReportSigner {
    byte[] signDsse(String purpose, String payloadType, byte[] canonicalPayload) throws Exception;
}

interface ReportTrustVerifier {
    byte[] verifyDsse(byte[] envelope, Path policyPath, Instant at) throws Exception;
}
```

The Picocli converters and Jackson scalar deserializers call the same compact constructors, so command flags and JSON decoding cannot bypass validation. Tests use the same anchored patterns as the JSON Schema and reject negative counters before signing.

`Runner.java` implements `Report run(RunRequest request)` and `Sign.java` implements `void writeSignedReport(Report report, Path outputPath)`; both accept an injected cancellation/deadline port and never use global clients. The command registry requires the exact `ops chaos run --matrix --environment --expected-environment-id --release --release-bundle --provider-baseline-digest --output --confirm-drill` surface; `run` checks `confirmDrill` before any provider read or injection and a missing confirmation exits `2`. Each signed matrix declares an immutable `report_type`: `operations/chaos/matrix.yaml` uses `accord.operations.chaos.v1`, while the M5 matrix uses `accord.milestone.m5.strict-recovery.v1`. Both profiles require the four-key subject count, replay count, convergence flag, baseline-restored flag, forbidden/wrong-completion totals, and overall result. Only the M5 profile additionally requires the three strict-recovery projection fields; every other report omits them.

Marshal the typed report, canonicalize it with RFC 8785 JCS, compute DSSE PAE, and use the common platform operations-evidence signer under the `staging-operations` workload identity; tenant signing service and V090 are unreachable from this lane. The runner has no private-key access. The exact DSSE `payloadType` is `application/vnd.accord.operations-chaos-report.v1+jcs`, independently of the payload's `report_type`. `evidence_index_digest` closes only the already persisted scenario evidence objects and cannot include the report envelope itself. Generate `audit_correlation_id` before signing, but do not place an audit-event digest in the signed payload: that would create a report-signature/audit-receipt cycle.

Resolve the output beneath the repository, reject symlink components, create the parent with mode `0750`, write the envelope to a mode-`0640` temporary sibling file, `fsync` it, rename atomically, and emit the audit event only after immutable evidence storage returns its envelope digest and object version. The common immutable receipt binds every required field in the Operational Evidence Contract. Known scenario divergence/uncertainty still produces the signed diagnostic envelope and receipt but returns exit `1`. Exit `3` is reserved for runner inability to determine injection, durable persistence, or cleanup/baseline restoration; expected injected uncertainty is an intermediate observation only and must reconcile to final `converged`.

Create `operations-chaos-report.schema.json` as closed JSON Schema 2020-12 (`additionalProperties: false` at every object). Require all common `Report` fields above through `scenario_evidence`, full 40-character lowercase Git SHA, `sha256:<64 lowercase hex>` digests, UUID `run_id` and `audit_correlation_id`, RFC 3339 timestamps, ratio `0..1`, nonnegative counters, nonempty unique scenario evidence, scenario results `converged|diverged|uncertain`, overall result `pass|fail|inconclusive`, and exact `merge_subject_authorization_counts` keys `work_item_pr`, `requirement_metadata`, `accepted_delivery_candidate`, and `emergency_change`. The `accord.operations.chaos.v1` conditional requires the six exact `agent_pack_distribution_outcomes` keys above; the M5 conditional forbids that unrelated field and instead requires the three strict-recovery projection fields. Schema validation happens before signing and after signature verification. Gate code, not schema alone, permits generic Operations exit `0` only when every scenario result is `converged`, all four subject counts match independently observed Controller consumptions and are nonzero, at least one Pack capability claim is independently observed, Pack duplicate/unverified-byte/token/stale-epoch/wrong-digest counters plus authorization replay/forbidden/wrong-completed totals are zero, controller ratio is `1.0`, `all_scenarios_converged` and `provider_baseline_restored` are true, cleanup digest matches the signed baseline, and overall result is `pass`. The M5 profile applies its own strict-recovery gates without pretending it exercised Pack distribution.

Create the verifier policy:

```yaml
schema_version: "1.0"
payload_type: application/vnd.accord.operations-chaos-report.v1+jcs
canonicalization: RFC8785-JCS
dsse_pae: DSSEv1
trust_domain: accord-platform-operations-evidence-v1
platform_key_purpose: chaos_report
signing_lane: staging-operations
accepted_algorithms: [ECDSA_P256_SHA256]
oidc_issuer: https://token.actions.githubusercontent.com
subject: repo:inforvans/accord:environment:staging-operations
kms_key_arn_pattern: '^arn:aws:kms:ap-northeast-(1|3):[0-9]{12}:key/[0-9a-f-]{36}$'
trust_bundle_resolver: platform-evidence-readonly
require_applied_trust_bundle_digest: true
tenant_signing_allowed: false
minimum_valid_signatures: 1
require_key_valid_at_signed_time: true
require_not_compromised_at_verification_time: true
evidence_retention_days: 400
object_lock_mode: COMPLIANCE
audit_event_type: operations.chaos.report.retained.v1
```

`Verify.java` exposes `Report verify(Path envelopePath, GitCommitSha releaseCommit, Sha256Digest releaseBundle, EnvironmentId expectedEnvironmentId, Sha256Digest expectedProviderBaselineDigest, Path matrixPath, Path schemaPath, Path policyPath, Path summaryOutput)`. The fixed CLI interface `ops chaos verify --evidence --release --release-bundle --expected-environment-id --expected-provider-baseline-digest --matrix --schema --summary-output` supplies `operations/chaos/report-signature-policy.yaml` as `policyPath`; callers cannot select a weaker policy. It resolves the explicit matrix as a regular non-symlink repository file, hashes its exact bytes and every closed relative fixture reference before opening the report, and rejects path escape, duplicate scenario, mutable reference, or digest disagreement. It then verifies DSSE before decoding untrusted payload, checks platform trust domain/lane/KMS ARN/OIDC subject/signing time/current compromise against the platform trust bundle, explicitly rejects tenant signing keys and V090, requires the exact payload type, re-canonicalizes to byte equality, validates the closed schema/report type, resolves every scenario object by digest using a read-only evidence identity, and resolves the immutable receipt by audit correlation. It enforces every pass predicate above and requires the report's release commit, release bundle, immutable environment ID, Provider baseline digest, matrix digest, fixture-set digest, time, and retention to equal those independently supplied or recomputed expectations. A cryptographically valid diagnostic report with any non-converged scenario remains evidence but returns exit `1` and produces no pass summary. Failure removes temporary output and leaves any existing destination unchanged.

`matrix.yaml` is the only ordered scenario catalog and references all eighteen scenario files by path and SHA-256, including independent strict WorkItem, requirement metadata, accepted Candidate, EmergencyChange authorization/replay, Agent Pack capability-race, and Pack OCI/cache/epoch scenarios. Use only ephemeral staging tenants and provider/artifact sandboxes. Cleanup restores cluster/provider/Pack distribution configuration to the exact signed baseline digest and verifies health; the DSSE envelope, evidence objects, immutable object versions, signer/trust record, and audit event are retained for at least 400 days rather than deleted.

- [ ] **Step 4: Run the complete failure matrix**

Run from Bash/Git Bash with the two protected staging values already exported:

```bash
./gradlew :cmd:accordctl:test :cmd:accordctl:installDist
release_commit="$(git rev-parse --verify 'HEAD^{commit}')"
: "${ACCORD_STAGING_ENVIRONMENT_ID:?ACCORD_STAGING_ENVIRONMENT_ID is required}"
: "${ACCORD_RELEASE_BUNDLE_DIGEST:?ACCORD_RELEASE_BUNDLE_DIGEST is required}"
: "${ACCORD_PROVIDER_BASELINE_DIGEST:?ACCORD_PROVIDER_BASELINE_DIGEST is required}"
cmd/accordctl/build/install/accordctl/bin/accordctl ops chaos run \
  --matrix operations/chaos/matrix.yaml \
  --environment staging \
  --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" \
  --release "$release_commit" \
  --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" \
  --provider-baseline-digest "$ACCORD_PROVIDER_BASELINE_DIGEST" \
  --output build/operations/chaos-report.dsse.json \
  --confirm-drill
cmd/accordctl/build/install/accordctl/bin/accordctl ops chaos verify \
  --evidence build/operations/chaos-report.dsse.json \
  --release "$release_commit" \
  --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" \
  --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" \
  --expected-provider-baseline-digest "$ACCORD_PROVIDER_BASELINE_DIGEST" \
  --matrix operations/chaos/matrix.yaml \
  --schema contracts/json-schema/operations-chaos-report.schema.json \
  --summary-output build/operations/chaos-report.json
corepack pnpm@10.12.4 --filter @accord/web test:e2e -- tests/e2e/ga/fault-convergence.spec.ts
```

Expected: every one of the eighteen scenarios is present exactly once and finishes `converged`; injected uncertainty appears only in intermediate observations and is reconciled inside its bound; the four strict-subject authorization counts are independently observed and nonzero; Pack capability double-consume, unverified-byte stream, stale-epoch acceptance, raw-token exposure, replay, forbidden-outcome, and wrong-completion totals are zero; `all_scenarios_converged=true`, `provider_baseline_restored=true`, controller ratio is `1.0`, cleanup matches the signed baseline, and overall result is `pass`. Only then do runner and verifier exit `0` and write canonical pass JSON. A final `diverged`/`uncertain` result is signed and retained for diagnosis but both commands exit `1`, and GA cannot consume it.

- [ ] **Step 5: Commit fault-injection coverage**

```bash
git add operations/chaos contracts/json-schema/operations-chaos-report.schema.json cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/chaos cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/chaos apps/web/tests/e2e/ga/fault-convergence.spec.ts
git commit -m "test: add production fault convergence drills"
```

### Task 10: Establish On-Call, Incident Command, Synthetic Checks, And Runbook Validation

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/synthetic/Run.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/synthetic/RunTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/incident/Drill.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/incident/DrillTest.java`
- Create: `operations/oncall/service-catalog.yaml`
- Create: `operations/oncall/escalation.yaml`
- Create: `operations/oncall/severity.yaml`
- Create: `operations/oncall/synthetic-checks.yaml`
- Create: `operations/runbooks/index.yaml`
- Create: `docs/operations/runbooks/api-latency.md`
- Create: `docs/operations/runbooks/action-delay.md`
- Create: `docs/operations/runbooks/webhook-gap.md`
- Create: `docs/operations/runbooks/provider-uncertain.md`
- Create: `docs/operations/runbooks/context-stale.md`
- Create: `docs/operations/runbooks/strict-policy-drift.md`
- Create: `docs/operations/runbooks/artifact-promotion.md`
- Create: `docs/operations/runbooks/model-degradation.md`
- Create: `docs/operations/runbooks/attachment-scanner.md`
- Create: `docs/operations/runbooks/audit-integrity.md`
- Create: `infra/helm/accord/templates/synthetic-checks.yaml`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/runbooks/Validate.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/runbooks/ValidateTest.java`

- [ ] **Step 1: Write failing ownership, runbook, and synthetic-check tests**

Require every paging alert to have primary/secondary owner, severity, dashboard, evidence-safe diagnostics, stop condition, failed-close impact, recovery verification, communication template, evidence path, and post-incident review. Synthetic probes cover authentication, tenant-scoped query, idempotent ActionRequest resolution fixture, provider metadata read, publication dry-run, Agent schema response, attachment upload/scan, audit verify, and Merge Controller preflight without an actual merge.

- [ ] **Step 2: Run runbook validation before the catalog and probes exist**

Run: `./gradlew :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ops.runbooks.*' :cmd:accordctl:installDist && cmd/accordctl/build/install/accordctl/bin/accordctl ops runbooks validate --alerts operations/alerts --index operations/runbooks/index.yaml`

Expected: FAIL listing each alert without an owner, complete runbook, and deployed synthetic probe.

- [ ] **Step 3: Implement severity, escalation, and failed-close policy**

Create `operations/oncall/severity.yaml`:

```yaml
schema_version: "1.0"
severities:
  sev0: [cross_tenant_disclosure, unauthorized_publish_merge_or_acceptance, signing_key_compromise]
  sev1: [wrong_completed, strict_assurance_breach, unrecoverable_audit_gap, regional_outage]
  sev2: [sustained_slo_burn, reconciliation_backlog, context_or_artifact_stall]
acknowledgement_minutes: { sev0: 5, sev1: 10, sev2: 30 }
incident_command_roles: [incident_commander, operations_lead, security_lead, communications_lead, scribe]
sev0_initial_action: freeze_affected_writes_merges_publication_and_preserve_read_only_audit
```

No runbook may instruct a direct database state change, audit/history rewrite, forced `completed`, or manual GA status. Probes use synthetic tenant IDs and least-privileged credentials and clean up only test data through public commands.

- [ ] **Step 4: Deploy probes and conduct a blind on-call exercise**

Run: `./gradlew :cmd:accordctl:test :cmd:accordctl:installDist && helm template accord infra/helm/accord | conftest test -p infra/policy - && cmd/accordctl/build/install/accordctl/bin/accordctl ops synthetic run --environment staging --catalog operations/oncall/synthetic-checks.yaml && cmd/accordctl/build/install/accordctl/bin/accordctl ops incident drill --environment staging --scenario strict-policy-drift`

Expected: every probe passes; page routes to primary then secondary within policy; responder follows the versioned runbook to a converged state; signed drill evidence contains timestamps, decisions, communication, and corrective actions.

- [ ] **Step 5: Commit operational readiness**

```bash
git add operations/oncall operations/runbooks docs/operations/runbooks infra/helm/accord/templates/synthetic-checks.yaml cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops
git commit -m "ops: establish on-call synthetic checks and runbooks"
```

### Task 11: Operationalize Model Regression And Certification-Unit Evaluation

**Files:**
- Create: `certification/units.yaml`
- Create: `certification/units.schema.json`
- Create: `certification/datasets/governance.yaml`
- Create: `certification/runtime-bundle.schema.json`
- Modify: `tests/agent-evaluation/datasets/dataset-manifest.schema.json`
- Modify: `tests/agent-evaluation/runners/evaluate.py`
- Modify: `tests/agent-evaluation/runners/confidence.py`
- Modify: `tests/agent-evaluation/runners/release_gate.py`
- Create: `tests/agent-evaluation/runners/shadow_canary.py`
- Create: `tests/agent-evaluation/test_operations_certification.py`
- Create: `certification/reports/model-evaluation.schema.json`
- Modify: `docs/operations/runbooks/model-degradation.md`

- [ ] **Step 1: Write failing dataset-governance, signed-evidence, and shadow/canary tests**

Extend the Agent-plan harness tests to require at least 8 independently licensed representative repositories and 50 end-to-end scenarios per language/framework/analyzer unit; at least 5 scenarios for each applicable authorization, payment, deletion, migration, and state-change category; independent senior-developer and business/product labels with third-person adjudication; `answerable`, `should_abstain`, and `annotation_uncertain`; immutable dataset digest; no selective exclusion of difficult cases; exact runtime-bundle linkage; offline-to-shadow-to-canary ordering; signed reports; and automatic per-unit degradation. Validate `certification/units.yaml` against a closed schema and reject an empty/duplicate unit; an extra/missing certification dimension; duplicate GitHub environment, ServiceAccount, Argo Application name, or Application UID; a mutable/unknown environment; wildcard/project escape; caller-supplied KMS/prefix/partition; and any unit-map digest not closed by the signed release manifest.

- [ ] **Step 2: Run evaluation tests before the harness exists**

Run: `uv run pytest tests/agent-evaluation/test_release_gate.py tests/agent-evaluation/test_operations_certification.py -q`

Expected: FAIL because operations certification schema, runtime-bundle closure, shadow/canary runner, signed report output, and degradation decisions are missing.

- [ ] **Step 3: Extend the canonical evaluator with operations evidence and orchestration**

Retain the Agent-plan evaluator's exact thresholds in `tests/agent-evaluation/runners/release_gate.py`:

```python
THRESHOLDS = {
    "critical_unsupported_claims": 0,
    "high_risk_blocker_misses_closed_audit": 0,
    "blocking_issue_recall_95ci_lower": 0.90,
    "invalid_question_rate_95ci_upper": 0.10,
    "requirement_to_code_traceability_95ci_lower": 0.95,
}
```

`certification/units.schema.json` is closed JSON Schema 2020-12. Each sorted unique unit contains its stable `certification_unit_id`, exact six certification dimensions, dataset/model policy references, immutable deployment environment, and a closed `ga_control` object with literal protected GitHub environment, Kubernetes ServiceAccount, Argo project, Application name, and Application UID. KMS aliases, object prefixes, coordination keys, roles, and target revisions are derived by infrastructure from the unit ID and cannot be supplied in the manifest. Canonical manifest bytes and digest are required subjects of the signed release manifest; Task 4 OpenTofu variables, Task 8 Helm/Argo release, and Task 14 workflows/policies/reports must all prove equality to that digest.

Extend the evaluation report with immutable dataset/license/adjudication digests, the runtime-bundle digest, unit-map digest, signature metadata, shadow observation window, canary population, rollback target, and degradation decision. The runtime-bundle schema closes model IDs, prompt versions, AssessmentPolicy/Requirement/Context schemas, analyzers, validator, and Agent Pack. `shadow_canary.py` may orchestrate and collect evidence but calls `evaluate.py`, `confidence.py`, and `release_gate.py` for scoring; it must not reimplement metrics or thresholds. Never average a failed unit into a passing global score.

- [ ] **Step 4: Run offline and shadow evaluation for every declared unit**

Run: `uv run pytest tests/agent-evaluation -q && uv run python tests/agent-evaluation/runners/evaluate.py --manifest tests/agent-evaluation/datasets/release.yaml --out build/evaluation && uv run python tests/agent-evaluation/runners/release_gate.py build/evaluation/results.json --units certification/units.yaml --runtime-bundle certification/evidence/runtime-bundle.json --signed-out certification/evidence/model-evaluation && uv run python tests/agent-evaluation/runners/shadow_canary.py --units certification/units.yaml --offline-report certification/evidence/model-evaluation --environment staging --confirm-canary`

Expected: each report is content-addressed and signed; a unit below any sample or quality threshold is `experimental` or `limited_availability`; its automatic suggestions are disabled or rolled back to the last passing signed bundle.

- [ ] **Step 5: Commit model certification**

```bash
git add certification/units.yaml certification/units.schema.json certification/datasets/governance.yaml certification/runtime-bundle.schema.json certification/reports tests/agent-evaluation/datasets/dataset-manifest.schema.json tests/agent-evaluation/runners tests/agent-evaluation/test_operations_certification.py docs/operations/runbooks/model-degradation.md
git commit -m "test: add per-unit Agent model certification"
```

### Task 12: Implement Product Value Metrics And The GA Value Gate

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Modify: `settings.gradle`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java`
- Modify: `apps/control-plane/worker/build.gradle`
- Create: `apps/control-plane/modules/product-metrics/build.gradle`
- Create: `apps/control-plane/modules/product-metrics/src/main/java/com/inforvans/accord/metrics/package-info.java`
- Create: `database/control-plane/migrations/V091__product_metric_policy_and_attribution.sql`
- Create: `apps/control-plane/modules/product-metrics/src/main/java/com/inforvans/accord/metrics/ValueGate.java`
- Create: `apps/control-plane/modules/product-metrics/src/test/java/com/inforvans/accord/metrics/ProductMetricMigrationIT.java`
- Create: `apps/control-plane/modules/product-metrics/src/test/java/com/inforvans/accord/metrics/ValueGatePropertyTest.java`
- Create: `contracts/json-schema/metric-policy.schema.json`
- Create: `contracts/json-schema/rework-attribution.schema.json`
- Create: `certification/value/ga-value-policy.json`
- Create: `apps/web/src/modules/metrics/api/value-report.ts`
- Create: `apps/web/src/modules/metrics/model/value-report.ts`
- Create: `apps/web/src/modules/metrics/components/value-report-page.tsx`
- Create: `apps/web/src/modules/metrics/components/value-report-page.test.tsx`
- Create: `apps/web/src/modules/metrics/index.ts`
- Create: `apps/web/src/routes/metrics-value-route.tsx`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/ValueEvidence.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ValueEvidenceTest.java`

- [ ] **Step 1: Write failing cohort, attribution, confidence, and UI tests**

Cover cohort start at first active DeliveryCommitment; completed/cancelled/aborted outcomes; original/correction/cleanup/revert/pre-abort hours in the denominator; requirement omission/ambiguity/mistranslation hours in the numerator; implementation defect/new scope/refactor/environment exclusions from numerator but not denominator; disputed hours as sensitivity upper bound; missing evidence as `evidence_insufficient`; comparable baseline mapping; immutable MetricPolicy; and efficiency/quality/adoption/governance/data guardrails.

Append `include(":apps:control-plane:modules:product-metrics")` to `settings.gradle` and create the test-capable module before the red run:

```groovy
plugins { id 'java-library' }
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':apps:control-plane:modules:reliability')
    implementation project(':apps:control-plane:modules:authorization')
    implementation project(':apps:control-plane:modules:audit')
    implementation project(':apps:control-plane:modules:delivery')
    implementation project(':apps:control-plane:modules:candidate-acceptance')
    implementation project(':database:control-plane')
    implementation libs.spring.boot.jooq
    implementation libs.spring.boot.web
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.jqwik
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
    testImplementation testFixtures(project(':database:control-plane'))
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
```

`ProductMetricMigrationIT` starts PostgreSQL `17.5`, calls `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)` immediately after the container starts and before `Flyway.configure()`, migrates through `V091`, and uses `accord_api` and `accord_worker` connections to prove tenant isolation, immutable policy rows, and allowed attribution writes. It queries the catalog to require forced RLS plus the exact `tenant_isolation` policy on both V091 tables and no `PUBLIC` privileges.

In `ModuleBoundaryTest.java`, replace `requiredModules` with the final V1 discovery contract:

```java
private static final Set<String> REQUIRED_MODULES = Set.of(
    "platformkernel", "reliability",
    "identity", "authorization", "audit",
    "requirement", "attachment", "collaboration", "action",
    "context", "assessment",
    "delivery", "git", "workitem", "acceptance", "metrics"
);
```

- [ ] **Step 2: Run migration, property, and value-report tests before their implementations exist**

Run:

```bash
./gradlew :apps:control-plane:modules:product-metrics:test
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
./gradlew :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ga.*'
corepack pnpm@10.12.4 --filter @accord/web test -- src/modules/metrics/components/value-report-page.test.tsx
```

Expected: the Gradle target is found and compilation fails on absent `V091`, `ValueGate`, migration assertions, and the Java CLI evidence builder; the web tests fail on their absent value report. The shared pre-Flyway role bootstrap itself succeeds.

- [ ] **Step 3: Implement the preregistered formula and GA policy**

Add the already registered project to API and worker `implementation` dependencies; retain the exact module build created in Step 1.

Create `package-info.java` with `@ApplicationModule(displayName = "Product Metrics", allowedDependencies = {"platformkernel", "reliability", "authorization", "audit", "delivery", "acceptance"})`. The module consumes immutable facts through exported APIs and must not update Requirement, Delivery, Candidate, Acceptance, or audit rows.

Create `V091__product_metric_policy_and_attribution.sql`; both tenant tables must invoke the standard enforcement function in this same migration before grants:

```sql
SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE metric_policy (
    tenant_id uuid NOT NULL,
    metric_policy_id uuid NOT NULL,
    project_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    policy_digest char(71) NOT NULL CHECK (policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    policy_json jsonb NOT NULL CHECK (jsonb_typeof(policy_json) = 'object'),
    effective_from timestamptz NOT NULL,
    frozen_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id, metric_policy_id),
    UNIQUE (tenant_id, project_id, version),
    UNIQUE (tenant_id, project_id, policy_digest)
);

CREATE TABLE rework_attribution (
    tenant_id uuid NOT NULL,
    attribution_id uuid NOT NULL,
    project_id uuid NOT NULL,
    commitment_id uuid NOT NULL,
    work_item_id uuid NOT NULL,
    category varchar(48) NOT NULL CHECK (category IN (
      'requirement_omission', 'requirement_ambiguity', 'requirement_mistranslation',
      'implementation_defect', 'new_scope', 'refactor', 'environment',
      'correction', 'cleanup', 'revert', 'pre_abort'
    )),
    attributed_hours numeric(12,2) NOT NULL CHECK (attributed_hours >= 0),
    disputed boolean NOT NULL DEFAULT false,
    evidence_digest char(71) NOT NULL CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id, attribution_id)
);

SELECT accord_security.enforce_tenant_table('public.metric_policy'::regclass);
SELECT accord_security.enforce_tenant_table('public.rework_attribution'::regclass);

GRANT SELECT ON metric_policy, rework_attribution TO accord_api;
GRANT SELECT, INSERT ON metric_policy, rework_attribution TO accord_worker;
```

Application code treats `metric_policy` as append-only: neither runtime role receives `UPDATE` or `DELETE`; a new version is a new row. `rework_attribution` corrections are compensating rows linked in the signed value evidence, never mutation of an accepted attribution.

Create `certification/value/ga-value-policy.json`:

```json
{
  "schema_version": "1.0",
  "policy_id": "GA-VALUE-2026-01",
  "minimum_relative_reduction": 0.15,
  "confidence_level": 0.95,
  "minimum_power": 0.80,
  "minimum_commitments": 30,
  "guardrails": [
    "alignment_plus_requirement_rework_hours_decrease",
    "first_acceptance_pass_rate_not_worse",
    "requirement_defect_escape_rate_not_worse",
    "eligible_contract_adoption_not_worse",
    "data_completeness_at_least_95_percent"
  ]
}
```

`ValueGate.evaluate` passes only when sample power is reached, the measured rework-rate 95% upper bound is at most `baselineRate * (1 - max(projectTarget, gaMinimum))`, and every guardrail passes. Disputed work contributes to the sensitivity upper bound; missing/unmappable evidence returns `EvidenceInsufficient`. Project policy may be stricter but never weaker than GA policy.

- [ ] **Step 4: Verify eligible, failed, disputed, and insufficient cohorts**

Run:

```bash
./gradlew :apps:control-plane:modules:product-metrics:test
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
./gradlew :apps:control-plane:api:test --tests '*ModuleBoundaryTest'
./gradlew :cmd:accordctl:test :cmd:accordctl:installDist
corepack pnpm@10.12.4 --filter @accord/web test -- src/modules/metrics/components/value-report-page.test.tsx
cmd/accordctl/build/install/accordctl/bin/accordctl ga value-evidence --fixture certification/value/fixtures/pilot-cohort.json --policy certification/value/ga-value-policy.json
```

Expected: `ProductMetricMigrationIT` and the global `TenantRlsTest` pass across all migrations through V091; eligible fixture passes with signed evidence; failed guardrail remains LA; disputed fixture reports both measured and upper-bound outcomes; missing/incomparable fixture reports `evidence_insufficient` and cannot derive GA.

- [ ] **Step 5: Commit value measurement and UI**

```bash
git add settings.gradle apps/control-plane/api/build.gradle apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java apps/control-plane/worker/build.gradle apps/control-plane/modules/product-metrics database/control-plane/migrations/V091__product_metric_policy_and_attribution.sql contracts/json-schema/metric-policy.schema.json contracts/json-schema/rework-attribution.schema.json certification/value apps/web/src/modules/metrics apps/web/src/routes/metrics-value-route.tsx cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga
git commit -m "feat: add preregistered product value gate"
```

### Task 13: Implement Release Promotion, Controlled Expansion, And Rollback

**Files:**
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Modify: `release/manifest.schema.json`
- Create: `release/channels.yaml`
- Create: `release/stop-conditions.yaml`
- Create: `infra/argocd/applications/accord-production.yaml`
- Create: `infra/argocd/rollouts/accord-controlled-expansion.yaml`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/Manifest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/Promote.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/Rollback.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/PromotionTest.java`
- Create: `docs/operations/runbooks/release-promotion.md`
- Create: `docs/operations/runbooks/release-rollback.md`
- Create: `docs/operations/runbooks/agent-pack-rollback.md`
- Create: `apps/web/tests/e2e/ga/release-rollback.spec.ts`

- [ ] **Step 1: Write failing promotion, stop-condition, and rollback tests**

Cover immutable source/image/schema/runtime/Pack digests, expand/contract migrations, sandbox to design-partner LA to 5/25/50/100 percent rollout, minimum 24-hour evidence windows, certification-unit isolation, SLO burn, zero-tolerance outcome, strict capability drift, Agent quality regression, Context chain damage, value failure, previous signed release deployment, Agent Pack rollback by customer PR changing `agent-pack.lock`, and preserved business/audit history.

- [ ] **Step 2: Run release tests before the manifest and commands exist**

Run: `./gradlew :cmd:accordctl:test --tests 'com.inforvans.accord.cli.release.*' && corepack pnpm@10.12.4 --filter @accord/web test:e2e -- tests/e2e/ga/release-rollback.spec.ts`

Expected: FAIL because promotion evidence validation, controlled rollout, and rollback orchestration are absent.

- [ ] **Step 3: Implement promotion channels and mandatory stop conditions**

Create `release/channels.yaml` and `release/stop-conditions.yaml`:

```yaml
channels:
  - { name: internal_sandbox, traffic_percent: 0, minimum_observation_hours: 24 }
  - { name: design_partner_la, traffic_percent: 5, minimum_observation_hours: 24 }
  - { name: controlled_25, traffic_percent: 25, minimum_observation_hours: 24 }
  - { name: controlled_50, traffic_percent: 50, minimum_observation_hours: 24 }
  - { name: ga, traffic_percent: 100, minimum_observation_hours: 0 }
stop_on:
  - any-zero-tolerance-counter
  - fast-error-budget-burn
  - strict-provider-capability-loss
  - agent-unit-threshold-regression
  - context-evidence-chain-damage
  - value-gate-failure
```

Promotion validates exact signed evidence for only the targeted certification unit and updates Argo CD by digest. Rollback redeploys the previous signed platform/runtime bundle and stops new work for affected units; it never rewrites Requirement, confirmation, audit, Candidate, acceptance, or promotion history.

- [ ] **Step 4: Exercise promotion, injected stop, and rollback in staging**

Run: `./gradlew :cmd:accordctl:test :cmd:accordctl:installDist && cmd/accordctl/build/install/accordctl/bin/accordctl release manifest --environment staging --output build/release/manifest.json && cmd/accordctl/build/install/accordctl/bin/accordctl release promote --environment staging --channel design_partner_la --confirm && cmd/accordctl/build/install/accordctl/bin/accordctl release inject-stop --environment staging --condition strict-provider-capability-loss && cmd/accordctl/build/install/accordctl/bin/accordctl release rollback --environment staging --previous-signed --confirm && corepack pnpm@10.12.4 --filter @accord/web test:e2e -- tests/e2e/ga/release-rollback.spec.ts`

Expected: incomplete evidence cannot promote; the 5% step begins only after signed checks; injected stop halts expansion and affects only the unit; rollback restores the previous digest while validity and assurance states remain truthful.

- [ ] **Step 5: Commit release controls**

```bash
git add release infra/argocd cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/release cmd/accordctl/src/test/java/com/inforvans/accord/cli/release docs/operations/runbooks/release-promotion.md docs/operations/runbooks/release-rollback.md docs/operations/runbooks/agent-pack-rollback.md apps/web/tests/e2e/ga/release-rollback.spec.ts
git commit -m "ops: add evidence-gated release promotion and rollback"
```

### Task 14: Assemble GA Evidence And Run The Final Production Rehearsal

**Files:**
- Modify: `.tool-versions`
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/Promote.java`
- Modify: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/PromotionTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/GaCoordination.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/GaCoordinationTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/GaReceipts.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/GaReceiptsTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/ReconcileGa.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/ReconcileGaTest.java`
- Modify: `docs/operations/runbooks/release-promotion.md`
- Create: `docs/operations/runbooks/ga-approval.md`
- Create: `docs/operations/runbooks/ga-promotion-reconciliation.md`
- Create: `contracts/json-schema/ga-evidence-manifest.schema.json`
- Create: `contracts/json-schema/ga-verification-report.schema.json`
- Create: `contracts/json-schema/ga-approval-session.schema.json`
- Create: `contracts/json-schema/ga-approval-submission.schema.json`
- Create: `contracts/json-schema/ga-promotion-request.schema.json`
- Create: `contracts/json-schema/ga-promotion-attempt.schema.json`
- Create: `contracts/json-schema/ga-promotion-resolution.schema.json`
- Create: `contracts/json-schema/ga-promotion-receipt.schema.json`
- Create: `contracts/json-schema/ga-handoff-record.schema.json`
- Create: `contracts/json-schema/ga-run-evidence-binding.schema.json`
- Create: `contracts/json-schema/contractual-terms-manifest.schema.json`
- Create: `contracts/json-schema/contractual-terms-acceptance.schema.json`
- Create: `certification/evidence/signature-policy.yaml`
- Create: `certification/terms/contractual-terms-manifest.v1.json`
- Create: `certification/terms/signature-policy.yaml`
- Create: `certification/terms/fixtures/contractual-terms-manifest.valid.dsse.json`
- Create: `certification/terms/fixtures/contractual-terms-acceptance.valid.dsse.json`
- Create: `operations/ga/checklist.yaml`
- Create: `operations/ga/rehearsal.schema.json`
- Create: `operations/ga/rehearsal.yaml`
- Create: `operations/ga/approval-policy.yaml`
- Create: `operations/ga/promotion-policy.yaml`
- Create: `operations/ga/approval-record.schema.json`
- Create: `operations/ga/jobs/job.schema.json`
- Create: `operations/ga/jobs/staging-operations.yaml`
- Create: `operations/ga/jobs/ga-collector.yaml`
- Create: `operations/ga/jobs/ga-verifier.yaml`
- Create: `operations/ga/jobs/ga-approval-attestor.yaml`
- Create: `operations/ga/jobs/ga-promoter.yaml`
- Create: `operations/ga/jobs/contract-acceptance-attestor.yaml`
- Create: `.github/workflows/ga-certification.yaml`
- Create: `.github/workflows/ga-approval.yaml`
- Create: `.github/workflows/ga-promotion.yaml`
- Create: `.github/workflows/contract-terms-package-publish.yaml`
- Create: `.github/workflows/contract-acceptance-attest.yaml`
- Create: `infra/policy/ci/ga-separation.rego`
- Create: `infra/policy/ci/ga-separation_test.yaml`
- Modify: `infra/policy/supply-chain/admission.rego`
- Create: `infra/policy/supply-chain/admission_test.yaml`
- Modify: `infra/opentofu/tests/ga-control-lanes.tftest.hcl`
- Modify: `infra/helm/accord/values.yaml`
- Modify: `infra/helm/accord/values.schema.json`
- Modify: `infra/helm/accord/templates/serviceaccounts.yaml`
- Modify: `infra/helm/accord/templates/networkpolicies.yaml`
- Create: `infra/helm/accord/templates/ga-control-lanes.yaml`
- Create: `infra/helm/accord/tests/ga-control-lanes.yaml`
- Modify: `infra/argocd/applications/accord-production.yaml`
- Create: `infra/argocd/rbac/ga-promoters.yaml`
- Create: `database/operations-coordination/migrations/V001__ga_coordination.sql`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Approval.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/ApprovalSession.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalSessionTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/ApprovalSubmission.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalSubmissionTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/ApprovalFinalizer.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalFinalizerTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/ApprovalStore.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalStoreTest.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/PostgresGaCoordinationIT.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Bundle.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Collect.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Report.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Verify.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Rehearse.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ContractualTermsTest.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/VerifyTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/terms/ContractPackage.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/terms/Acceptance.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/terms/TermsTest.java`
- Create: `scripts/ga/launch-immutable-job.sh`
- Create: `scripts/ga/launch-immutable-job.test.sh`
- Create: `scripts/release/verify-accordctl.sh`
- Create: `scripts/release/verify-accordctl.test.sh`
- Create: `apps/web/tests/e2e/ga/certification-gate.spec.ts`
- Create: `Makefile`

- [ ] **Step 1: Write failing evidence-completeness, approval-transaction, promotion-coordination, and anti-override tests**

Test exact certification dimensions, current release/runtime bundle, all 21 product/spec sections and Appendices A-D, security/source-boundary zero counters, SBOM/provenance/vulnerability reports, real uninterrupted 28-day SLO window, full capacity/cost envelope, explicit-time backup/PITR restore, regional DR including Pack distribution epoch/replica/cache proof, key rotation/compromise and `browser_session_csrf` regional availability/IAM, all four roots' applied saved-plan evidence with the exact 18-module union, the live central Helm workload inventory including exactly one Gateway, the Task 9 fault-convergence DSSE envelope with all eighteen scenarios and Pack zero-tolerance outcomes, on-call drill, model report, value report, role documentation, the Appendix D business-label snapshot/accessibility/translation review, and the unit-scoped logical evidence type `contractual-terms`. That evidence must contain a closed, versioned, signable contract package plus the eligible unit's exact acceptance/applicability record, platform signature, freshness, immutable storage receipt, and digest closure. Attempted admin command, direct database status update, stale report, mismatched dimension, missing artifact, or cryptographically valid report whose independently recomputed business predicates are not all pass must never yield `ga`.

Use temporary fixture directories built by `VerifyTest.java` and `PromotionTest.java`, except for the two committed contractual-terms interoperability fixtures named in Files; do not rely on any other undeclared repository fixture path. Cover missing Task 9 envelope, valid envelope with changed evidence object, wrong platform lane/key, tenant signature, expired retention, Task 9 summary without envelope, any final non-converged scenario, wrong scenario count, false convergence/baseline flag, missing/wrong four-subject count, nonzero replay/forbidden/wrong-completed or Pack distribution zero-tolerance counter, invalid GA index signature, collector key presented as verifier key, forged verification report, stale release/runtime bundle, wrong or replayed `certification_run_id`, missing or forged run-evidence wrapper, wrapper/source envelope digest/object-version/receipt disagreement, mismatched predecessor handoff digest/object version, index path/digest/object-version disagreement, shortened SLO window, under-load capacity run, failed restore/DR/key result, stale Pack distribution epoch or wrong OCI/cache digest, any missing root/module or IaC plan/apply/approval/state mismatch, incomplete/chart-only Helm inventory or missing/duplicate Gateway, Gateway IRSA/KMS/egress drift, cross-lane KMS/IAM permission, GitHub orchestration role with KMS/evidence-write access, IRSA role able to launch another lane, wrong immutable Job UID/image/command digest, one-person/stale/mismatched GA approval, failed gate relabeled `ga`, dirty source tree, accordctl/Gateway/Pack digest/Cosign/provenance/source mismatch, missing final immutable receipt, and unwritable destinations. The GA promotion matrix separately rejects a summary JSON in place of the signed report; non-`ga` or independently inconsistent unit gates; changed report/index/approval bytes; wrong or missing immutable digest, object version, or receipt; any mismatch in certification run, unit, source commit, release bundle, immutable environment, restore point, Provider baseline, dimensions, policy, or target release; one person holding both approval roles; the release author approving; stale role bindings or replayed/non-operation-bound fresh-auth receipts; a mutable/symlink input; an already-diverged Argo target; an unknown Provider result; and a receipt that cannot be atomically persisted, signed, read back, and retained. The valid case performs exactly one digest-pinned update and a byte-identical retry performs no second external mutation. The contractual matrix separately rejects missing, stale, not-yet-effective, expired, revoked, wrong-locale, translation-digest mismatch, term-content-digest mismatch, wrong release bundle, source commit, immutable environment, certification unit, platform signer, approval set, retention/object version/receipt, or terms acceptance; it also rejects a tenant-neutral manifest containing release/source fields that would create a digest cycle, inline arbitrary terms text, HTML/script, executable content, URL-fetched policy, or unclosed properties, a package-publisher key used for acceptance, an acceptance-attestor key used for the package, and an acceptance from one tenant or unit substituted for another. Assert every such failure sets `contractual_terms_pass=false`, produces at most `limited_availability`, leaves no new pass report or promotion receipt, and cannot be overridden by an administrator.

`ApprovalTest.java` uses a deterministic OAuth authorization server, workforce directory/JWKS server, SigV4 submission endpoint, PostgreSQL coordination adapter, KMS signer, immutable store, and trusted clock behind the production interfaces. `PostgresGaCoordinationIT.java` runs PostgreSQL 17.5 in Testcontainers, applies the real operations-coordination Flyway migration with an owner identity, and exercises runtime roles separately. Test `ga approval open|submit|finalize` end to end and reject an unverified session handoff, unknown or stale issuer/JWKS/directory observation, wrong `aud`, nonce, RAR authorization-details digest, `cnf.jkt`, DPoP `htu`/`htm`/`iat`/`jti`, PKCE verifier, `acr`/`amr`, role, role-binding version, report/index/run tuple, release author, session state, or receipt signature. Approval open and promotion each receive the exact Task 8 release-artifact handoff plus downloaded manifest/Cosign/provenance/receipt bodies; tests reject a missing, mutable, symlinked, swapped, truncated, wrong-version, wrong-receipt, cross-release, or wrong-author body before a session claim or promotion claim exists. Expected author flags alone never satisfy the check. Human submit receives a local offline session package containing the handoff, complete session DSSE envelope, and immutable receipt; tests reject a digest/version/receipt mismatch, mutable URL or redirect, ambient cloud credential dependency, database lookup, stdout substitute, omitted envelope body, and another session's otherwise valid bytes before starting OAuth or SigV4.

Prove a 30-minute session, a WebAuthn `auth_time` no more than 5 minutes old at submission, a transaction receipt lifetime no more than 5 minutes, finalization no later than 15 minutes after the later selected submission and before session expiry, directory/JWKS observations no more than 5 minutes old, and promotion entry to `EXECUTING` no more than 15 minutes after the finalized decision. An older approval requires a new session/two submissions, while an already executing unknown operation remains read-only reconcilable. Timeout, TLS/pin failure, rollback, duplicate nonce/JTI, uncertain directory membership, or clock outside the signed policy fails closed. Inspect process-state hooks to prove that PKCE verifier, access/ID tokens, DPoP private key, authorization code, and temporary cloud credentials are never serialized, logged, handed off, persisted in PostgreSQL, or included in immutable evidence and are zeroed on every success/error/cancellation path.

The approval store tests append multiple immutable submissions per role, including an invalid first submission followed by a valid one. Finalization starts one `SERIALIZABLE` PostgreSQL transaction, locks the session and eligible submission rows with deterministic ordering, validates every candidate at one frozen database timestamp, enumerates eligible cross-role pairs, and deterministically selects the lexicographically first pair after sorting by newest `submitted_at`, then receipt digest and submission UUID; the selected pair must contain two distinct current natural people and exclude the release author. In that same transaction, conditional updates consume both selected one-time receipt JTIs, close the session with compare-and-swap versioning, and insert exactly one finalization claim containing the canonical approval-record payload bytes/digest, decision time, selected submission keys/people, role-binding snapshot digests, and fixed signing purpose. Unique constraints make replay deterministic; serialization failures retry the whole transaction with a bounded policy. Test 100 concurrent finalizers, replay, rollback/serialization failure, an invalid or same-person candidate ahead of a valid pair, directory/JWKS changes during selection, and crashes before/after claim, KMS signing, Object Lock put, receipt put, and read-back. Exactly one claim wins; retries adopt that claim and any deterministic-key retained object, never reselect a person, timestamp, key purpose, payload byte, or receipt.

The signature-policy tests use schema-valid canonical bytes so failures cannot be attributed to parsing. They sign valid approval-session bytes with the approval-record key/purpose and valid approval-record bytes with the approval-session key/purpose. For one unit they exercise every directed attempt/resolution/receipt purpose mismatch; for every promotion purpose they use the same purpose key from another unit; they try payload-controlled `certification_unit_id` values that would expand an alias, subject, prefix, or path; and they use unknown key IDs and unknown purposes. Every case must fail signature-policy verification and leave approval finalization, execution permit creation, and Provider/Argo call counts at zero. The valid control proves the policy resolver selects the applied unit-map key before decoding any payload-selected alias.

The promotion coordination suite starts 100 callers with the same `promotion_id` and canonical request digest and proves one durable mapping, one winning fencing generation, one preassigned Provider request ID, at most one execution-authorized attempt, and at most one Provider/Argo mutation. Advance the trusted clock between the first success and an identical retry: immutable business bindings still canonicalize to the same request bytes/digest and return the byte-identical receipt, while dispatch ID, output paths, attempt time, and current wall clock never enter that digest. Test approval age at exactly 15 minutes (eligible), one clock tick over (signed `PRECONDITION_REJECTED` with no permit/call), and a `PENDING` lease/queue wait that crosses the bound before `PENDING -> EXECUTING`. The PENDING row must contain frozen `approval_not_after`; the same transaction that freezes `execution_authorized_at` and canonical authorized-attempt bytes/digest must condition `execution_authorized_at <= approval_not_after`. A one-tick-late branch atomically records a non-authorized attempt and returns no permit.

The suite also covers a changed immutable intent under the same ID, concurrent different-unit requests, expired `PENDING` leases, an `EXECUTING` lease loss, retry after `APPLIED`, and exact crash cuts immediately before and after the one `PENDING -> EXECUTING` transaction, before/after attempt KMS signing, before/after attempt Object Lock put/read-back, before/after Provider call, outcome freeze, resolution signing/put/read-back, promotion-receipt signing/put/read-back, and coordination receipt update. The transition transaction must atomically change state and freeze generation, Provider request ID, trusted execution-authorized time, and canonical `EXECUTION_AUTHORIZED` attempt bytes/digest before returning an in-memory permit to that call stack. Any crash after it, including before attempt signing or before a Provider call, is fenced as `OUTCOME_UNKNOWN` and can proceed only through reconciliation; ordinary promote never uses an expired lease to create a second permit. `ReconcileGaTest.java` queries the exact configured Argo Application name and UID and derives only `APPLIED`, `NO_EFFECT`, or `DIVERGED` from its resource version, target revision, immutable history, Kubernetes audit event, and request annotation. It rejects ambiguous, stale, partial, caller-supplied, or cross-unit facts. `NO_EFFECT` requires an explicit new generation before a later promote, `DIVERGED` fences the unit, and `APPLIED` resumes the original frozen receipt bytes. Attempt and resolution objects are append-only and make the cumulative Provider call count independently auditable.

`verify-accordctl.test.sh` creates isolated temporary Git repositories and artifact fixtures and uses deterministic test doubles only for external Cosign/SLSA commands at explicit paths. Its table covers a dirty tracked file, staged change, untracked non-ignored file, wrong HEAD, symlink/non-regular binary, binary digest mismatch, unsigned or wrong-identity manifest, wrong release-bundle digest, missing/wrong binary Cosign bundle, provenance for another source commit/builder/subject, mutable locator, malformed/extra manifest property, tool-version drift, and destination/path escape. Every case must fail before the binary is executed; the valid case prints exactly one JCS verification record containing source commit, release-bundle digest, binary digest, builder identity, and manifest digest.

`ga-separation_test.yaml` supplies one valid certification workflow and exact negative fixtures for a non-SHA action, extra or combined certification job, wrong/shared environment, missing `needs`, downstream `if: always()`, broad workflow/job permissions, `pull_request_target`, reusable workflow, non-ephemeral or incorrectly labelled runner, a runner outside the applied cluster VPC/private endpoint security path, artifact download by mutable name, direct signing-role assumption, an OIDC orchestration role with `kms:Sign` or evidence-write access, a Job template/service-account/namespace/image/command mismatch, a credential in job output, missing run/digest/object-version handoff, or invocation of the wrong Make target. For every certification, approval, promotion-unit, package, and acceptance lane it rejects a missing/caller-derived/mutable concurrency group, a group not equal to repository plus fixed protected lane/unit, `cancel-in-progress: true`, a mutable fixed Job name, create without an absence check, delete before terminal UID/handoff verification, and replacement/adoption of a residual Job. It also supplies valid separate approval, per-unit promotion, package-publisher, and acceptance-attestor workflows and rejects placing any of them inside the three-job certification DAG; approval submission or finalization in a promoter; promotion before a verifier handoff and finalized approval record; one shared promoter environment, ServiceAccount, IRSA role, KMS key, object prefix, coordination partition, or Argo grant; a promotion role that can collect/verify/approve evidence; an approval role that can call Argo; or any overlap among their OIDC/IRSA subjects, KMS purposes, payload types, object prefixes, and mutation permission. Every negative case reports the workflow/job and violated invariant.

The CLI, Make, launcher, handoff, collector, verifier, approval opener/finalizer, and promoter tests treat the exact six protected run bindings as one indivisible tuple: `certification_run_id`, `source_commit`, `release_bundle_digest`, `immutable_environment_id`, `restore_point_rfc3339`, and `provider_baseline_digest`. For each field they cover missing, malformed, defaulted, read from ambient environment, changed between workflow and Job, omitted from the admitted command digest, changed between lanes, absent from a handoff/index/report/approval session/final record/promotion request, or inconsistent with source evidence. The separate release-artifact handoff is a required immutable predecessor, not a seventh run binding: its manifest/Cosign/provenance/receipt digest+version tuple must be present in the admitted command and must yield the exact authoritative author object already bound by the release bundle. Every case fails before a drill in `staging-operations`, before index signing in `ga-collector`, before report signing in `ga-verifier`, before approval session claim/finalization in `ga-approval-attestor`, and before a promotion claim or Argo mutation in `release promote`; no layer may infer a protected value or release author from the rehearsal plan, current time, Provider state, mutable environment alias, report summary, expected-author flag, commit metadata, or another field.

`launch-immutable-job.test.sh`, Helm tests, and admission tests cover the complete kube transport: wrong cluster name/ARN/private endpoint, CA data/digest, TokenRequest audience, token or CA path, exchanged IRSA/kube tokens, writable/symlink/default path, missing `AWS_ROLE_ARN`/`AWS_WEB_IDENTITY_TOKEN_FILE`, system CA, ambient `KUBECONFIG`, `InClusterConfig`, public endpoint, and caller-selected Application. They prove certification/approval/terms lanes have no kube token/trust mount and that the promoter uses mandatory Host/BearerTokenFile/CAFile only. Handoff tests prove the closed locator has no signature or embedded trust claim; corrupt checksum/version, extra signature field, mutable/foreign object key, valid envelope from another lane/run/unit, or receipt substitution fails, while a consumer succeeds only after separately verifying the exact DSSE envelope and receipt. A second simultaneous dispatch, residual Job, or uncertain UID-preconditioned delete returns `3` and never deletes or replaces another dispatch.

- [ ] **Step 2: Run the GA verifier against incomplete and forged bundles**

Run each command independently:

```bash
./gradlew :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ga.*' --tests 'com.inforvans.accord.cli.terms.*'
./gradlew :cmd:accordctl:test --tests '*GaApproval*'
./gradlew :cmd:accordctl:test --tests '*GaPromotion*' --tests '*GaReconcile*'
bash scripts/ga/launch-immutable-job.test.sh
bash scripts/release/verify-accordctl.test.sh
conftest verify -p infra/policy/ci infra/policy/ci/ga-separation_test.yaml
conftest verify -p infra/policy/supply-chain infra/policy/supply-chain/admission_test.yaml
helm unittest infra/helm/accord -f 'tests/ga-control-lanes.yaml'
tofu -chdir=infra/opentofu test -filter=tests/ga-control-lanes.tftest.hcl
```

Expected: the GA/terms Java suite fails because typed collection/verification reports, canonicalization, signed run-evidence binding, split contractual signer purposes, run-bound immutable handoffs, Task 9 envelope verification, digest closure, atomic outputs, derived-status enforcement, and the approval transaction state machine are absent; the release Java suite fails because `Promote.java` has no durable claim/lease/fence, cannot yet reverify the GA report/index/approval tuple, and cannot reconcile or emit immutable attempt/resolution/final receipts; the launcher shell suite fails because the OIDC-to-immutable-Job-to-IRSA boundary is absent; the release shell suite fails because independent release-tool verification is absent; Conftest, Helm, and OpenTofu fail because approval/per-unit promoter identities, policies, resources, and admission checks are absent. Invalid evidence, an untrusted tool, or a workflow separation breach expects exit `1` and a deterministic sorted violation list; invocation errors expect exit `2`; inability to determine whether a Kubernetes Job, Provider result, immutable object, or receipt/handoff was created exits `3`. Make and workflow layers preserve the exact nonzero code while preventing downstream execution.

- [ ] **Step 3: Implement content-addressed collection, transactional approval, derived status, and fenced GA promotion closure**

Keep the security-sensitive implementations focused. `Approval.java` is only the Picocli-independent command facade and dependency wiring; `ApprovalSession.java` owns immutable session construction; `ApprovalSubmission.java` owns RAR/PKCE/WebAuthn/DPoP/token-exchange and secret-zeroing; `ApprovalFinalizer.java` owns deterministic pair selection and final record construction; and `ApprovalStore.java` owns the narrow submission endpoint plus PostgreSQL serializable/conditional operations. Their matching tests exercise each boundary without network or AWS globals. In the release package, existing `Promote.java` owns evidence validation and command orchestration, `GaCoordination.java` is the only claim/lease/fencing state adapter, `GaReceipts.java` owns canonical attempt/resolution/final envelopes and deterministic Object Lock recovery, and `ReconcileGa.java` owns authoritative Argo observation and state resolution. The Provider adapter accepts a package-private execution permit constructible only by `GaCoordination.java`, so a code path cannot call Argo before a durable `EXECUTING` transition.

`Bundle.java` accepts the already signed Task 8 release manifest; it does not mint a replacement bundle from the working tree. It validates `release/manifest.schema.json`, Cosign identity/issuer/bundle, SLSA subjects and builder, recomputes the canonical `certified_release_bundle_digest`, and requires the protected input digest and source commit to match. That digest closes model IDs, prompt versions, AssessmentPolicy/Requirement/Context schemas, analyzers, validator, canonical certification-unit map/schema/digest, Agent Pack OCI/release metadata, Gateway image and capability/cache contract, Java/Python/web artifacts, database schema, provider capability adapter, policies, deployment configuration, the stable tenant-neutral contractual-terms package payload/envelope digest and version, and the released `accordctl` digest/provenance. The package payload is an upstream input to this digest and therefore must not contain either `certified_release_bundle_digest` or `source_commit_sha`; release/source binding is added only by the downstream unit acceptance envelope. This one-way chain is `terms package bytes -> package digest -> release bundle digest -> unit acceptance`, never the reverse. `Collect.java` requires the protected `certification_run_id`, explicit RFC 3339 restore point, and signed Provider baseline digest; resolves each artifact only beneath that release/run prefix by SHA-256 and immutable object version; compares those two run inputs with the signed rehearsal handoff and every applicable source wrapper/report; verifies each owning closed schema/platform signature/immutable receipt; requires its units to match the release-closed map exactly; requires exactly one unit-scoped `contractual-terms` entry for every eligible certification unit; and writes an RFC 8785 JCS index in a DSSE envelope that binds the same run ID and both run inputs. It never accepts `derived_status` or any gate boolean and does not convert a signed report into a pass merely because its signature is valid.

`ga-run-evidence-binding.schema.json` is a closed JSON Schema 2020-12 payload for reusable operational source evidence. It requires the canonical certification run UUID, stage ID, `global` or exact certification-unit scope, source payload type, source envelope SHA-256, immutable source object version, source receipt SHA-256, source generation/completion time, release-bundle digest, source commit, immutable environment ID, exact `restore_point_rfc3339`, exact `provider_baseline_digest`, wrapper generation time, retention deadline, and the `staging-operations` Job/IRSA signer identity. `Rehearse.java` writes its RFC 8785 JCS/DSSE wrapper only after independently reading back and verifying the source envelope, object version, receipt, trust time, retention, both protected run inputs, and business predicates; the wrapper is a new signed statement, not a byte copy or path claim. `Collect.java` accepts a reusable source report only through this wrapper and indexes both immutable identities so `Verify.java` can re-resolve them. A wrapper for another run/scope/release/environment/restore-point/provider-baseline, a changed source object, or a missing source receipt is invalid even when either individual signature is trusted.

`Verify.java` verifies the collection envelope before decoding, then invokes a typed evaluator for every profile. It requires the SLO report's complete 28-day interval/coverage/objectives, capacity report's full declared load and recovery thresholds including both Pack fetch classes, restore report's explicit timestamp/integrity/disabled-side-effects, DR report's RPO/RTO/fencing/reconciliation plus exact Pack epoch/replica/cache proof, key report's separation/rotation/compromise/CSRF regional IAM, all four IaC roots' exact approved saved-plan/apply/state evidence and 18-module union, the live central Helm inventory's complete isolated workloads including one Gateway, and Task 9's eighteen final-converged scenarios/four merge counts/Pack outcome proof/replay-zero/baseline-restored/counters-zero. It also re-evaluates supply chain, on-call, model, value, documentation, all 21 specification rows, Appendices A-C contract vectors, Appendix D's versioned business-label mapping plus accessibility/translation review, and dimension conditions. No evaluator trusts a payload's `result`, summary JSON, chart template, plan JSON, or prior derived status without checking its underlying fields and indexed objects.

`contractual-terms-manifest.schema.json` and `contractual-terms-acceptance.schema.json` are closed JSON Schema 2020-12 contracts. `certification/terms/contractual-terms-manifest.v1.json` is the stable tenant-neutral, versioned product contract package payload. It contains only identifiers, versions, digests, timestamps, locale metadata, and approval references: manifest/schema version; tenant-neutral product-terms version; separately versioned references and SHA-256 content digests for DPA/customer-data boundary, subprocessor list, processing region, retention, deletion, no-source-body boundary, support ownership, SLA, security commitments, incident response, exit, and export terms; package and per-term `effective_at`, optional `expires_at`, and revocation status/reference; source-locale digest plus every approved translation's locale, version, and digest; and distinct Legal, Privacy, Product Security, and Support approval identity/role-binding/fresh-auth receipt digests. It explicitly forbids `certified_release_bundle_digest`, `source_commit_sha`, tenant/customer identifiers, inline or arbitrary terms prose, executable/script/template content, remote includes, URLs used as trust roots, desired result/status, and unevaluated properties. Human-readable signed documents remain immutable external objects addressed only by the declared content digests. The tag-bound `contract-terms-package-publisher` validates those bytes, signs only payload type `application/vnd.accord.contractual-terms-manifest.v1+jcs` with KMS purpose `contractual_terms_package`, stores the envelope and receipt immutably, and supplies its digest/version as an upstream release-manifest input; it cannot observe customer acceptance data or use the acceptance key.

Each eligible certification unit has one unit-scoped `contractual-terms` evidence envelope whose closed acceptance payload binds its exact `certification_unit_id`, tenant/customer organization identifier, six certification dimensions, immutable environment ID, package version and payload/envelope digest, every accepted/applicable term version and digest, selected locale and translation digest, authorized customer signatory identity and current role-binding snapshot, e-signature/acceptance receipt digest and acceptance time, platform approval-set digest, revocation/freshness observation time, exact release-bundle digest, exact source commit, `certification_run_id`, and retention deadline. The protected runtime `contract-acceptance-attestor` independently verifies the customer proof and current authority, then uses RFC 8785 JCS, DSSE PAE, and the distinct KMS purpose `contractual_terms_acceptance` to sign only payload type `application/vnd.accord.contractual-terms-acceptance.v1+jcs`; the customer proof cannot substitute for the platform envelope, and the static package-publisher key cannot sign it. The envelope and its external acceptance receipt are stored beneath the exact release/run/unit prefix under compliance Object Lock with immutable object versions and a separately signed audit receipt. A global package or one tenant/unit/run's acceptance never satisfies another eligible unit or run.

`.github/workflows/contract-terms-package-publish.yaml` runs only from the protected immutable contract tag, verifies the clean tagged package bytes and approvals, and invokes the exact `contract terms publish-package --package <manifest.json> --schema <manifest.schema.json> --signature-policy <policy.yaml> --output <envelope.dsse.json> --handoff-output <handoff.json>` surface under the package-publisher OIDC/KMS identity. `.github/workflows/contract-acceptance-attest.yaml` is a separate protected workflow that has no package key and launches only `operations/ga/jobs/contract-acceptance-attestor.yaml`; inside that immutable Job, the exact surface is `contract terms attest-acceptance --certification-run-id <uuid> --package <envelope.dsse.json> --package-digest <sha256> --package-object-version <version> --customer-proof <receipt> --authority-snapshot <snapshot> --revocation-snapshot <snapshot> --unit <unit.json> --release <commit> --release-bundle <sha256> --expected-environment-id <id> --output <acceptance.dsse.json> --handoff-output <handoff.json>`. Both commands validate closed schemas, JCS/DSSE, purpose, Object Lock and receipt read-back before success. They are pre-collection evidence producers, are not jobs in `ga-certification.yaml`, and cannot call `ga collect`, `ga verify`, or another terms command.

The contractual evaluator fetches the package, every referenced immutable terms object, the unit acceptance, customer signature proof, approval receipts, revocation registry snapshot, and storage/audit receipts by indexed digest and object version. It rehashes bytes, revalidates both closed schemas, JCS/DSSE PAE, distinct package/acceptance signer purposes and key IDs, trust time/current compromise state, customer authority at acceptance, Legal/Privacy/Product Security/Support approvals, run/release/source/environment/unit/dimension/locale bindings, effective/expiry/revocation state, retention, and a revocation/freshness observation made within 24 hours of verification. It independently proves that the release bundle contains the exact package digest while the package contains no release/source back-reference. It never trusts `result`, `accepted`, or a prior `contractual_terms_pass` claim. Only an exact, currently applicable and accepted package makes `ContractualTermsPass=true`; every failure emits a deterministic violation, caps status at `limited_availability`, and is outside administrator or approval-record override authority.

The IaC evaluator requires one signed root-profile evidence envelope for each literal inventory ID `integration-test`, `aws-state-backends`, `aws-tokyo-primary`, and `aws-osaka-warm-dr`; aliases are rejected. It replays source/tool/provider-lock/backend/config/plan-binary/plan-JSON digests, exact root-to-HCL module arrays and the final 18-module union, two distinct Platform Operations/Product Security approvals, the profile-appropriate apply/test facts, object version, output digest, workload identity, time, and immutable receipt; deployable roots additionally require state lineage and serial before/after, while the non-deployable `integration-test` root requires its signed `tofu test`/mock-provider receipt and must not pretend to have a production apply. It rejects a plan made during apply, skipped root/module, reused approver, uncertain provider result, changed/stale serial, or a root profile that disagrees with `roots.json`. It separately proves `aws-agent-pack-distribution` is applied in Tokyo and Osaka with immutable OCI replication, distinct region-local cache/KMS resources, exact Gateway IRSA subjects, and epoch fencing.

Evidence must prove the three disjoint certification GitHub OIDC orchestration/IRSA signer pairs, one fixed and disjoint `ga-approval-attestor` orchestration/IRSA pair, both role-specific non-signing workforce submission routes, and one disjoint OIDC/IRSA promoter pair for every exact certification-unit map entry. Certification orchestration roles can launch/watch only their immutable Job and cannot sign or write evidence; each certification IRSA role may `kms:Sign` only with its own purpose key and run-scoped prefix. The approval orchestration role may launch only `ga-approval-attestor`; the approval IRSA may transact only approval partitions and sign only approval-session/approval-record payloads; neither can call Argo. Every unit promoter can transact only its unit partition, sign only that unit's promotion-attempt/resolution/receipt payloads, read only that run/unit's immutable predecessors, and get/patch only its exact Argo Application `resourceName`; a generated Kubernetes admission condition additionally requires the live Application UID to equal the applied unit map before any patch. The package publisher and acceptance attestor retain separate keys/purposes. Wildcards, dynamic caller-selected resource names, dual-trusted roles, a shared promoter ServiceAccount/key/prefix/partition, decrypt/data-key rights, cross-prefix mutation, or any certification/approval/promotion/terms identity overlap fails `IaCApplyPass` and `KeyLifecyclePass`.

The Helm evaluator consumes a platform-signed deployment inventory captured from the certified cluster, not only `helm template`. It binds release-bundle/chart/locked-dependency/values/rendered-manifest digests and, for every closed Task 8 workload, the live namespace/UID/image digest/ServiceAccount/IRSA role/mTLS identity/PDB/topology spread/NetworkPolicy/egress/CSI secret-provider digests plus observation time and immutable receipt. For the Gateway it additionally binds component-chart version/digest, HTTPS origin, OCI allowlist digest, verified-cache bucket/KMS/encryption-context policy, distribution epoch, and absence of customer-Git/Provider mutation/cross-lane access. For GA control lanes it requires exactly one fixed approval ServiceAccount/IRSA/NetworkPolicy and exactly one ServiceAccount/IRSA/Role/RoleBinding/NetworkPolicy/admission binding per declared certification unit. The promoter Role uses literal `resourceNames: [<unit-application-name>]`; the admission binding checks the immutable Application UID, unit identity, request annotation, target digest, and fencing generation; endpoint CIDRs and Kubernetes API access are closed values, and no lane has unrestricted namespace, internet, metadata-service, another unit, or tenant-data egress. It compares that inventory with the signed Task 8 manifest and Kubernetes admission results; a missing or duplicate Gateway, Publisher, Merge Controller, any BreakGlass profile, approval lane, unit promoter, stale observation, mutable image, unexpected workload, shared GA resource, independent Gateway release, or chart/live mismatch makes `HelmInventoryPass=false`.

Use this typed report boundary in `Report.java`:

```java
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

record GaDigest(@JsonValue String value) {
    private static final Pattern FORMAT = Pattern.compile("sha256:[0-9a-f]{64}");
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    GaDigest {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid GA digest");
    }
}

record GaCommitSha(@JsonValue String value) {
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{40}");
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    GaCommitSha {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid source commit");
    }
}

record CertificationUnitId(@JsonValue String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9]+(?:[-_][a-z0-9]+)*");
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    CertificationUnitId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid certification unit ID");
    }
}

record UnitGateResults(
    boolean supplyChainPass, boolean sloPass, boolean capacityPass, boolean restorePass,
    boolean regionalDrPass, boolean keyLifecyclePass, boolean iaCApplyPass,
    boolean helmInventoryPass, boolean faultConvergencePass,
    boolean operationalReadinessPass, boolean modelPass, boolean valuePass,
    boolean contractualTermsPass
) {}

record UnitVerification(
    CertificationUnitId certificationUnitId,
    Map<String, String> dimensions,
    Map<String, GaDigest> artifactDigests,
    UnitGateResults gateResults,
    List<String> violations,
    String derivedStatus
) {}

public record Report(
    String schemaVersion, String reportType, String certificationRunId,
    GaDigest certifiedReleaseBundleDigest, GaCommitSha sourceCommitSha,
    String immutableEnvironmentId, Instant restorePointRfc3339,
    GaDigest providerBaselineDigest, String logicalEnvironment,
    GaDigest toolArtifactDigest, GaDigest policyDigest, GaDigest inputSetDigest,
    GaDigest evidenceIndexDigest, String evidenceIndexObjectVersion,
    String auditCorrelationId, Instant startedAt, Instant completedAt,
    Instant retentionUntil, String result, String verifierIdentity,
    List<UnitVerification> units
) {}
```

These value records use the same compact-constructor-backed Picocli/Jackson conversion rule as Task 9. `dimensions` is schema-closed to the six keys in the Operational Evidence Contract; Java verification rejects extra or missing keys before signing and uses immutable copies of every map/list.

Append exact `cosign 2.4.3`, `slsa-verifier 2.7.1`, and `jq 1.7.1` entries to the foundation-owned `.tool-versions`; bootstrap tests reject duplicate entries or any other version. `git` and GNU coreutils come from the digest-pinned release-verifier runner image recorded in Task 8 provenance. `scripts/release/verify-accordctl.sh` is the independent bootstrap boundary and accepts only `--binary`, `--binary-cosign-bundle`, `--provenance`, `--manifest`, `--manifest-cosign-bundle`, `--manifest-schema`, `--builder-policy`, `--expected-source-commit`, `--expected-release-bundle`, `--verified-output`, and `--record-output`. With `set -euo pipefail` and `umask 027`, it resolves every path under the repository, rejects symlinks/non-regular inputs and output escapes, requires no tracked/staged/untracked-nonignored change, verifies the exact tool versions and runner-image provenance, and verifies exact HEAD. Using those fixed external `jq`, `cosign`, `slsa-verifier`, `sha256sum`, and `git` binaries, it checks the closed manifest keys, manifest Cosign bundle/issuer/workflow identity, manifest byte digest, protected release-bundle input, binary subject digest and media type, binary Cosign bundle, SLSA builder/source/materials/subject, and the expected source commit. It rehashes the input after verification, copies it through a mode-`0555` temporary file to the caller-selected repository-local verified path, atomically renames, rehashes the output, and writes one strings-only JCS verification record atomically. It never executes the candidate binary, accepts a network locator, downloads an artifact, follows a manifest-provided trust root, or lets CLI flags weaken `operations/supply-chain/allowed-builders.yaml`. The CI job is isolated after artifact download and makes the verified directory writable only by that job identity.

`ga rehearse` has the exact in-cluster interface `ga rehearse --certification-run-id <uuid> --environment <name> --expected-environment-id <id> --release <40-lowercase-hex> --release-bundle <sha256> --restore-point-rfc3339 <timestamp> --provider-baseline-digest <sha256> --plan <rehearsal.yaml> --handoff-output <receipt-set-handoff.json> --confirm`. `ga collect` has the exact in-cluster interface `ga collect --certification-run-id <uuid> --release <40-lowercase-hex> --release-bundle <sha256> --expected-environment-id <id> --expected-restore-point-rfc3339 <timestamp> --expected-provider-baseline-digest <sha256> --runtime-bundle <path> --units <path> --evidence-root <path> --rehearsal-receipt-set <path> --expected-rehearsal-receipt-set-digest <sha256> --expected-rehearsal-receipt-set-object-version <version> --output <index.dsse.json> --handoff-output <index-handoff.json>`. It signs with payload type `application/vnd.accord.ga-evidence-index.v1+jcs` through the platform `ga-collector` KMS lane and binds the run ID, restore point, Provider baseline, and predecessor receipt-set digest/version. `ga verify` has the exact in-cluster interface `ga verify --certification-run-id <uuid> --index <index.dsse.json> --expected-index-digest <sha256> --expected-index-object-version <version> --release <40-lowercase-hex> --release-bundle <sha256> --expected-environment-id <id> --expected-restore-point-rfc3339 <timestamp> --expected-provider-baseline-digest <sha256> --schema <ga-evidence-manifest.schema.json> --report-schema <ga-verification-report.schema.json> --signature-policy <signature-policy.yaml> --report-output <report.dsse.json> --summary-output <report.json>`. The verifier rehashes the explicit regular non-symlink index path, resolves the index's immutable receipt, and requires its digest, version, restore point, and Provider baseline to equal both the protected predecessor handoff and CLI bindings before following any entry.

`ga-handoff-record.schema.json` is a closed strings-only unsigned RFC 8785 JCS locator. It requires schema version, handoff type, certification run, source/release/environment, exact restore point, Provider baseline digest, deterministic envelope object key, envelope digest and exact object version, deterministic receipt object key, receipt digest and exact version, producer Job UID/image/command digest, producer OIDC+IRSA identities, and closed command result class/exit-code strings when terminal. It forbids embedded envelope/receipt bytes, URL/bucket/credential/local path, signature/key ID/purpose claims, defaults, and unknown properties. The producer atomically writes it only after both referenced objects have been retained and read back. Orchestrators verify its exact S3 key/version/checksum and launch bindings but do not read evidence bodies; collector, verifier, approval, promoter, terms, and final-rehearsal consumers must fetch the referenced exact bytes and independently verify digest, closed schema, JCS/DSSE signature/purpose, immutable receipt, retention, command result, and semantic bindings before use. Stdout is never parsed as evidence or a locator.

The closed evidence-manifest schema binds the same restore point and Provider baseline, admits `contractual-terms` only as a certification-unit-scoped logical type, and requires its package, acceptance, signature-policy, object-version, receipt, freshness, and run digests; reusable operational source evidence additionally requires a valid `ga-run-evidence-binding` wrapper and both source object identities, and a direct artifact must carry the same certification run itself. It rejects a global contractual-terms entry, run-free artifact, copied source envelope, cross-run/cross-unit alias, or any restore-point/provider-baseline mismatch. At process start all three commands resolve their own executable, recompute its digest, compare it with the immutable signed release bundle, require the exact in-cluster IRSA subject/KMS purpose plus the authenticated GitHub-orchestrator/Job launch receipt, and reject role chaining. A locally rebuilt/swapped executable, wrong run, wrong Job, wrong lane, changed restore point, or changed Provider baseline exits `1` before evidence mutation.

`.github/workflows/ga-certification.yaml` is the only cross-lane orchestrator. Every action reference is a full commit SHA and the workflow is loaded from the exact protected source commit. It has `workflow_dispatch` inputs `certification_run_id`, `source_commit`, `release_bundle_digest`, `immutable_environment_id`, `restore_point_rfc3339`, and `provider_baseline_digest`, validates the UUID and immutable formats before any cloud call, and defines exactly three protected GitHub job IDs whose environment names are identical to their job IDs: `staging-operations`, `ga-collector`, and `ga-verifier`. Each job has a literal `concurrency.group` equal to `${{ github.repository }}:ga-certification:<literal-job-id>` and `cancel-in-progress: false`; inputs cannot alter either field. Approval, terms, and each signed-map-resolved promotion-unit workflow use the same repository-plus-fixed-environment rule.

Each job starts on a one-dispatch digest-pinned ephemeral runner scale-set Pod in the applied EKS VPC, with no retained workspace/credential volume and automatic runner deregistration/destruction after the job. Runner subnets, private DNS, route tables, security groups, and egress policies allow the exact private EKS endpoint and required GitHub/OIDC endpoints only. A fresh environment-bound GitHub OIDC token assumes only a non-signing orchestration role with exact-cluster `eks:DescribeCluster`; the job verifies returned name/ARN/private endpoint/decoded CA digest/OIDC issuer against signed applied-root evidence, obtains a token for that cluster only, and creates an in-memory kubeconfig. It submits the digest-pinned lane template from `operations/ga/jobs/`, substitutes only the six protected values plus predecessor handoff values, proves the lane's fixed digest-derived Job name is absent, creates that one Job in namespace `accord-certification`, and can get/watch/delete only that resource name. A second or residual Job is never adopted or replaced and returns `3`.

The Job uses a fresh `emptyDir`, exact ServiceAccount/IRSA role, digest-only released image/`accordctl`, read-only root filesystem, no service-account-token automount beyond the explicit IRSA projection, and an admission-verified command/argument digest; only the pod IRSA role can use the lane KMS key and run-scoped object prefix. The GitHub role never receives KMS or evidence-store write permission. After terminal status, it reads only the exact unsigned handoff locator, matches Job UID/image/command/bindings, then deletes with a UID precondition and waits for absence. Missing/ambiguous handoff, wrong UID, or uncertain delete exits `3` and blocks later dispatches rather than preempting.

`ga-collector` has a default-success `needs: [staging-operations]` edge, downloads the rehearsal receipt set by the predecessor's exact digest and object version into its fresh workspace/Job, and passes all three values plus the same `certification_run_id` to `ga collect`. `ga-verifier` has a default-success `needs: [ga-collector]` edge, downloads the index by the collector's exact digest and object version to an explicitly supplied `GA_INDEX_PATH`, and passes that path/digest/version plus the same run ID to both the master milestone gate and the independently packaged Java/Picocli verifier. Each GitHub job independently verifies the published artifact and Job image by digest, then invokes only its matching Make target (`ga-rehearse`, `ga-collect`, or `ga-verify`), which launches only the matching immutable Job; the exact `ga ...` command runs inside the Job, not under the GitHub role. Job outputs carry only validated handoff digests/object versions, never credentials, mutable paths, or unsigned report bodies. Environment, IAM, KMS, admission, and object-prefix policies make it impossible for one OIDC token or IRSA role to run/sign/write another lane. Reusable-workflow calls, persistent/unlabelled runners, `pull_request_target`, artifact-by-name download, direct signing-role assumption, and role chaining are forbidden. A failed or cancelled predecessor cannot be manually marked complete or bypassed by rerunning only a downstream job.

All three jobs also pass the original protected `restore_point_rfc3339` and `provider_baseline_digest` as explicit Make and in-pod CLI arguments. The collector compares them with the rehearsal handoff before signing the index; the verifier compares them with the collector handoff, index, wrappers, restore report, and chaos report before signing the final report. They are included in each immutable Job command digest and are never reconstructed from GitHub job environment, Kubernetes metadata, wall-clock time, or current Provider facts.

`ga-separation.rego` parses the workflow and immutable Job templates as structured YAML and enforces that exact trigger/input/job/environment/needs/permission/action/ephemeral-private-runner/output/target/namespace/ServiceAccount/image/command graph. It requires every job's fixed repository+lane/unit concurrency group and `cancel-in-progress: false`, exact applied cluster/endpoint/CA verification, fixed Job name/absence check/terminal handoff/UID-preconditioned delete sequence, `id-token: write` only at each GitHub orchestration job, `contents: read`, no other repository write permission, non-signing OIDC IAM policies, distinct IRSA/KMS/object-prefix policies, immutable digest+object-version downloads, exact run propagation, and default-success dependency semantics. Workflow text scanning is not accepted as the policy implementation.

The verifier independently invokes Task 9 `Verify` against the referenced fault envelope and requires its envelope/payload/object/receipt digests, run/release/environment/provider/matrix/fixture closure, platform staging key, 400-day retention, `result=pass`, all scenarios converged, exact four-key nonzero counts, replay/forbidden/wrong-completed zero, and baseline restored. It does not trust a Task 9 summary. It derives every unit status, validates the GA report schema, JCS-canonicalizes, and signs with payload type `application/vnd.accord.ga-verification-report.v1+jcs` through the distinct platform `ga-verifier` IRSA/KMS lane. Collector and verifier key IDs/purposes must differ; neither may use the staging, contractual, or tenant signing key. Each command writes atomically and stores the envelope before an immutable audit receipt. The final verifier resolves that receipt back through `audit_correlation_id`, verifies its platform OIDC-orchestrator and IRSA-signer identities, Job UID/image/command digest, envelope digest, immutable object version, certification run, explicit evidence-index digest/object version, release/environment, exact restore point, Provider baseline digest, retention, and audit anchor, and only then publishes the local summary and exits `0`; the signed report cannot contain its own receipt digest because that would create a hash cycle. Any schema, business-gate, signature, digest, freshness, retention, storage, signing, receipt read-back, or output failure leaves no new pass output.

Create `certification/evidence/signature-policy.yaml`:

```yaml
schema_version: "1.0"
canonicalization: RFC8785-JCS
dsse_pae: DSSEv1
trust_domain: accord-platform-operations-evidence-v1
accepted_algorithms: [ECDSA_P256_SHA256]
minimum_valid_signatures: 1
require_key_valid_at_signed_time: true
require_not_compromised_at_verification_time: true
require_pairwise_distinct_fixed_key_ids: [staging-operations, ga-collector, ga-verifier, ga-approval-session, ga-approval-record, contract-terms-package-publisher, contract-acceptance-attestor]
require_pairwise_distinct_unit_key_purposes: [ga_promotion_attempt, ga_promotion_resolution, ga_promotion_receipt]
require_unit_keys_distinct_from_all_fixed_keys: true
tenant_signing_allowed: false
payload_identities:
  application/vnd.accord.contractual-terms-manifest.v1+jcs:
    lane: contract-terms-package-publisher
    key_purpose: contractual_terms_package
    kms_alias: alias/accord-platform-evidence-contract-terms-package
    signer_type: github_oidc
    signer_issuer: https://token.actions.githubusercontent.com
    signer_subject: repo:inforvans/accord:ref:refs/tags/contract-terms-v1
  application/vnd.accord.contractual-terms-acceptance.v1+jcs:
    lane: contract-acceptance-attestor
    key_purpose: contractual_terms_acceptance
    kms_alias: alias/accord-platform-evidence-contract-acceptance
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject: system:serviceaccount:accord-certification:contract-acceptance-attestor
  application/vnd.accord.ga-evidence-index.v1+jcs:
    lane: ga-collector
    key_purpose: ga_evidence_index
    kms_alias: alias/accord-platform-evidence-ga-collector
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject: system:serviceaccount:accord-certification:ga-collector
    orchestrator_issuer: https://token.actions.githubusercontent.com
    orchestrator_subject: repo:inforvans/accord:environment:ga-collector
  application/vnd.accord.ga-verification-report.v1+jcs:
    lane: ga-verifier
    key_purpose: ga_verification_report
    kms_alias: alias/accord-platform-evidence-ga-verifier
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject: system:serviceaccount:accord-certification:ga-verifier
    orchestrator_issuer: https://token.actions.githubusercontent.com
    orchestrator_subject: repo:inforvans/accord:environment:ga-verifier
  application/vnd.accord.ga-approval-session.v1+jcs:
    lane: ga-approval-attestor
    key_purpose: ga_approval_session
    kms_alias: alias/accord-platform-evidence-ga-approval-session
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject: system:serviceaccount:accord-certification:ga-approval-attestor
    orchestrator_issuer: https://token.actions.githubusercontent.com
    orchestrator_subject: repo:inforvans/accord:environment:ga-approval
  application/vnd.accord.ga-approval-record.v1+jcs:
    lane: ga-approval-attestor
    key_purpose: ga_approval_record
    kms_alias: alias/accord-platform-evidence-ga-approval-record
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject: system:serviceaccount:accord-certification:ga-approval-attestor
    orchestrator_issuer: https://token.actions.githubusercontent.com
    orchestrator_subject: repo:inforvans/accord:environment:ga-approval
  application/vnd.accord.ga-promotion-attempt.v1+jcs:
    lane_resolver: ga_promoter_units[certification_unit_id].service_account
    key_purpose: ga_promotion_attempt
    kms_alias_pattern: alias/accord-platform-evidence-ga-promoter-{certification_unit_id}-attempt
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject_pattern: system:serviceaccount:accord-certification:ga-promoter-{certification_unit_id}
    orchestrator_issuer: https://token.actions.githubusercontent.com
    orchestrator_subject_pattern: repo:inforvans/accord:environment:ga-promoter-{certification_unit_id}
  application/vnd.accord.ga-promotion-resolution.v1+jcs:
    lane_resolver: ga_promoter_units[certification_unit_id].service_account
    key_purpose: ga_promotion_resolution
    kms_alias_pattern: alias/accord-platform-evidence-ga-promoter-{certification_unit_id}-resolution
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject_pattern: system:serviceaccount:accord-certification:ga-promoter-{certification_unit_id}
    orchestrator_issuer: https://token.actions.githubusercontent.com
    orchestrator_subject_pattern: repo:inforvans/accord:environment:ga-promoter-{certification_unit_id}
  application/vnd.accord.ga-promotion-receipt.v1+jcs:
    lane_resolver: ga_promoter_units[certification_unit_id].service_account
    key_purpose: ga_promotion_receipt
    kms_alias_pattern: alias/accord-platform-evidence-ga-promoter-{certification_unit_id}-receipt
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject_pattern: system:serviceaccount:accord-certification:ga-promoter-{certification_unit_id}
    orchestrator_issuer: https://token.actions.githubusercontent.com
    orchestrator_subject_pattern: repo:inforvans/accord:environment:ga-promoter-{certification_unit_id}
  application/vnd.accord.ga-run-evidence-binding.v1+jcs:
    lane: staging-operations
    key_purpose: ga_run_evidence_binding
    kms_alias: alias/accord-platform-evidence-staging-operations
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject: system:serviceaccount:accord-certification:staging-operations
    orchestrator_issuer: https://token.actions.githubusercontent.com
    orchestrator_subject: repo:inforvans/accord:environment:staging-operations
  application/vnd.accord.operations-chaos-report.v1+jcs:
    lane: staging-operations
    key_purpose: chaos_report
    kms_alias: alias/accord-platform-evidence-staging-operations
    signer_type: kubernetes_irsa
    signer_issuer_resolver: applied-eks-oidc-issuer
    signer_subject: system:serviceaccount:accord-certification:staging-operations
    orchestrator_issuer: https://token.actions.githubusercontent.com
    orchestrator_subject: repo:inforvans/accord:environment:staging-operations
retention:
  days: 400
  object_lock_mode: COMPLIANCE
  require_object_version: true
  audit_event_type: ga.verification.report.retained.v1
  run_binding_audit_event_type: ga.run-evidence-binding.retained.v1
  approval_session_audit_event_type: ga.approval.session.retained.v1
  approval_record_audit_event_type: ga.approval.record.retained.v1
  promotion_attempt_audit_event_type: ga.promotion.attempt.retained.v1
  promotion_resolution_audit_event_type: ga.promotion.resolution.retained.v1
  promotion_receipt_audit_event_type: ga.promotion.receipt.retained.v1
contractual_terms_policy: certification/terms/signature-policy.yaml
```

The signature-policy parser validates each `{certification_unit_id}` against the signed unit map before expanding a subject or alias pattern; the raw payload string cannot select a key. It requires the expanded ServiceAccount, GitHub environment, KMS ARN, object prefix, and coordination partition to match both applied OpenTofu outputs and the immutable Job receipt. Approval session/record keys are distinct from one another, every unit's attempt/resolution/receipt keys are pairwise distinct, and all are distinct from certification, contractual-terms, tenant-signing, collector, verifier, and other units' keys. A correctly signed payload under the wrong purpose or unit is invalid.

`certification/terms/signature-policy.yaml` closes the package payload to the tag-bound `contract-terms-package-publisher` GitHub OIDC subject and `contractual_terms_package` KMS purpose, and closes the acceptance payload to the protected `contract-acceptance-attestor` IRSA subject and distinct `contractual_terms_acceptance` KMS purpose. Cross-purpose signing is rejected even when both keys are otherwise trusted. The policy also closes the platform Legal/Privacy/Product Security/Support approval purposes, accepted customer signature-provider roots, required run/unit/locale/release/source/environment bindings, 24-hour revocation-observation freshness, and 400-day compliance Object Lock/version/receipt rules. The two committed DSSE fixtures contain deterministic non-production keys and digest-stable package/acceptance payloads for cross-language verification; policy and tests reject those fixture keys outside test mode.

The GA report schema is closed JSON Schema 2020-12. It requires every common evidence field, every `GAVerificationReport` field including UUID `certification_run_id`, nonempty `evidence_index_object_version`, exact RFC 3339 `restore_point_rfc3339`, and SHA-256 `provider_baseline_digest`, a 400-day minimum retention interval, unique nonempty units, the exact certification dimension and `UnitGateResults` keys including required `contractual_terms_pass`, SHA-256/Git-SHA patterns, sorted unique violations, overall `result=pass|fail`, and `derived_status` in `experimental|limited_availability|ga`. Cross-field derivation remains verifier code: a unit has `ga` only when violations are empty, every gate boolean including `contractual_terms_pass` is true, every report-specific predicate above passes, the restore and chaos evidence match those exact protected inputs, and overall result is `pass`. Any non-converged Task 9 scenario, wrong restore point, Provider-baseline mismatch, or false baseline restoration forces the applicable gate false; any missing, inapplicable, unaccepted, stale, expired, revoked, mistranslated, misbound, wrongly signed, unapproved, or non-retained terms evidence forces `contractual_terms_pass=false`; either produces a violation and a non-GA status even when an envelope signature or embedded `result` is valid.

`operations/ga/approval-policy.yaml` is a closed, release-signed policy rather than caller-selectable OAuth configuration. It pins the workforce issuer, authorization/token/RFC 8693 exchange/JWKS/directory endpoints and TLS trust, client ID, exact RAR authorization-details type, `aud=accord-ga-approval`, accepted WebAuthn `acr` and `amr`, DPoP algorithms, role names, directory group IDs, submission endpoint, clock-skew bound, and exact schema/signature-policy digests from the release manifest. It also pins one authenticated workforce session-package download origin, TLS trust digest, no-redirect rule, deterministic approval-session object-key pattern, and exact-version response contract. That origin can read only minimal nonsecret approval-session envelopes/receipts, cannot list or write, and returns the complete envelope and receipt selected by the unsigned handoff's content digest/object version; it never returns a mutable URL or cloud credential.

The policy constants are `session_lifetime=30m`, `maximum_step_up_auth_age=5m`, `maximum_transaction_receipt_lifetime=5m`, `finalize_after_second_submission_within=15m`, `maximum_directory_jwks_observation_age=5m`, and `maximum_finalized_approval_age_at_promotion_start=15m`. Endpoint timeout, an uncertain directory result, an unavailable or stale JWKS, a rollback in issuer key/directory version, or failure to establish trusted time fails closed; cached data may be used only while its signed observation remains within 5 minutes. A promotion that has not durably entered `EXECUTING` within 15 minutes of the frozen approval decision needs a new session and two new submissions; no retry or administrator extends the approval. An operation validly in `EXECUTING`/`OUTCOME_UNKNOWN` remains reconcilable after that age because reconciliation cannot initiate another Provider call.

`ga approval open` is source-free: the fixed approval Job consumes only the verified released `accordctl`, immutable release-artifact/verifier/index handoffs and their downloaded exact bodies, the exact six protected bindings, unit, expected release-author equality assertions, and signed policies. The approval IRSA can `GetObjectVersion` only beneath the supplied release-bundle digest's deterministic Task 8 prefix. The command validates the release-artifact handoff, manifest schema and body digest/version/receipt, keyless Cosign issuer/workflow identity, `accordctl` SLSA builder/material/subject, source/release binding, and canonical release-author-object digest before trusting the author. It resolves that author's current role-binding version from the pinned directory, requires both expected CLI values to match, re-verifies report/index/receipts, and independently requires the selected unit's derived status to be `ga`. A caller therefore cannot evade self-approval separation by naming another author or omitting the source bytes.

Before signing or object storage it conditionally creates an `OPENING` coordination claim containing one UUID session ID, 256-bit nonce, canonical session payload bytes/digest, frozen `opened_at`/`expires_at`, approval-intent/RAR digest, exact report/index/release/run/unit bindings, authoritative release author and author-object digest, release-artifact handoff/body identities, and fixed approval-session key purpose. A retry adopts those bytes. It signs payload type `application/vnd.accord.ga-approval-session.v1+jcs`, writes to a deterministic Object Lock key with create-only semantics, reads back envelope/object-version/audit receipt, writes the local envelope/receipt/handoff outputs atomically, and conditionally marks the session `OPEN`; an uncertain put is resolved by reading that exact key and verifying bytes, never by creating a second session record.

`ga approval submit` runs under either eligible human's verified release binary and needs no source checkout, evidence-store credential, database access, or cloud signing key. The platform UI first uses ordinary authenticated workforce access to download one minimal offline package from the policy-pinned origin: the unsigned handoff locator, complete session DSSE bytes, and immutable receipt bytes. Before opening a browser or network authorization flow, the command resolves all three explicit regular non-symlink local paths, validates the handoff and receipt schemas, rehashes the exact envelope/receipt, verifies object versions/retention, validates the session DSSE signature/purpose/JCS and release/run/unit/author bindings, and displays only the existing structured approval details. A missing body, stdout text, database row, mutable URL, ambient object-store lookup, or handoff alone is insufficient.

Only after that offline verification does submit create a process-local P-256 DPoP key and PKCE verifier, start a loopback redirect listener bound to a random state, and request the exact signed RAR details for the verified session/role/intent. The authorization-code exchange requires PKCE and a DPoP proof; WebAuthn step-up is mandatory. A one-time RFC 8693 exchange returns a signed transaction-receipt JWS containing `iss`, constant audience, subject/natural-person ID, role, role-binding version/digest, session ID, nonce, RAR digest, `acr`, `amr`, `auth_time`, `iat`, `exp`, `jti`, and `cnf.jkt`. The command obtains at most 5-minute role-specific temporary credentials, then invokes only the role-specific SigV4 submission route with the receipt and a fresh DPoP proof. The route re-verifies SigV4 principal tags, receipt signature/claims, proof possession and the durable JTI uniqueness constraint, sets its fixed session/role scope on the JDBC transaction, and conditionally inserts one append-only row keyed by `(session_id, role, submitted_at, submission_uuid)`; it never overwrites an earlier submission. Access/ID tokens, authorization code, PKCE verifier, DPoP private key, and temporary credentials exist only in locked process memory, are redacted from telemetry and crash output, and are zeroed on success, error, signal, or cancellation. PostgreSQL retains only the minimum receipt JWS, verified closed claims, proof/receipt/policy digests, endpoint request ID, and trusted submission time.

`ga approval finalize` strongly reads the closed session and all append-only submissions and freezes one trusted decision time plus one directory/JWKS observation set. For every candidate it re-verifies `iss`, `aud`, signature/key validity, `cnf`, nonce, RAR binding, `acr`/`amr`, JTI uniqueness, submission-route attestation, current role binding, exact operation tuple, and release-author separation. The route attestation must prove that `auth_time` was within 5 minutes and the receipt's `iat <= trusted_submitted_at < exp` with `exp-iat <= 5m` when proof possession was checked; the receipt need not remain unexpired at finalization, which may occur during the separate 15-minute window. Multiple submissions per role remain legal so an invalid first attempt cannot occupy a slot. The finalizer sorts each role's valid candidates by descending trusted `submitted_at`, then ascending receipt digest and submission UUID, enumerates cross-role pairs in that stable order, and selects the first pair containing two distinct current natural people and no release author. No candidate order, directory response order, or map iteration may affect selection.

One `SERIALIZABLE` PostgreSQL transaction conditionally requires session `OPEN`, both selected receipt JTIs unconsumed, unchanged directory/JWKS snapshot digests, and absent finalization; it atomically consumes both JTIs, closes the session, and inserts the sole finalization claim. That claim freezes the exact canonical approval-record payload bytes/digest, decision time, selected submission keys/digests/people, role-binding versions, observation digests, and `ga_approval_record` key purpose before KMS or Object Lock side effects. A unique `(session_id)` finalization constraint and expected-version update decide the winner; every retry resumes that claim. The approval Job uses a deterministic object key and create-only write; after any uncertain KMS/store response it first reads and verifies an existing envelope, so a crash cannot reselect people/time or replace retained bytes. Only after DSSE signature, 400-day COMPLIANCE Object Lock put, immutable audit receipt, and digest/object-version read-back does it publish the strings-only handoff.

`ga-approval-session.schema.json` and `ga-approval-submission.schema.json` are closed JSON Schema 2020-12 contracts for the immutable session payload and minimal append item. `operations/ga/approval-record.schema.json` is the closed canonical final authorization payload and is never accepted as bare JSON: it must be the payload of DSSE type `application/vnd.accord.ga-approval-record.v1+jcs`, signed by the fixed approval IRSA/key purpose and resolved through its Object Lock receipt and `ga-handoff-record`. For each certification unit it binds the exact `certification_run_id`, verification envelope/payload/receipt digests and object version, evidence-index envelope/payload/receipt digests and object version, release bundle, source commit, immutable environment ID, restore point, Provider baseline digest, dimensions, policy and approval-intent digests, decision/observation times, release author, session identity, and exactly the two selected Platform Operations/Product Security approvals with receipt/JTI/role-binding/proof digests. The verifier may produce a truthful failed report without approvals, but `release promote --channel ga` accepts this record only for an already independently derived `ga` unit. Approval authorizes promotion; it cannot alter a gate, authorize `limited_availability`, or supply an administrator override.

The exact approval CLI surfaces are:

```text
accordctl ga approval open \
  --certification-run-id <uuid> --unit <certification-unit-id> \
  --source-commit <40-lowercase-hex> --release-bundle <sha256> \
  --expected-environment-id <immutable-id> --restore-point-rfc3339 <timestamp> \
  --provider-baseline-digest <sha256> --expected-release-author-subject <subject> \
  --expected-release-author-role-binding-version <version> \
  --release-artifact-handoff <release-artifact-handoff.json> \
  --release-artifact-handoff-schema release/artifact-handoff.schema.json \
  --release-manifest <manifest.json> --release-manifest-cosign-bundle <bundle.json> \
  --release-manifest-provenance <provenance.intoto.jsonl> --release-manifest-receipt <receipt.json> \
  --release-manifest-schema release/manifest.schema.json --release-builder-policy operations/supply-chain/allowed-builders.yaml \
  --expected-release-manifest-digest <sha256> --expected-release-manifest-object-version <version> \
  --expected-release-manifest-receipt-digest <sha256> \
  --verification-report <report.dsse.json> --expected-verification-report-digest <sha256> \
  --expected-verification-report-object-version <version> \
  --evidence-index <index.dsse.json> --expected-evidence-index-digest <sha256> \
  --expected-evidence-index-object-version <version> \
  --policy operations/ga/approval-policy.yaml \
  --index-schema contracts/json-schema/ga-evidence-manifest.schema.json \
  --report-schema contracts/json-schema/ga-verification-report.schema.json \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json \
  --signature-policy certification/evidence/signature-policy.yaml \
  --session-output <approval-session.dsse.json> --session-receipt-output <approval-session-receipt.json> \
  --handoff-output <approval-session-handoff.json> --confirm

accordctl ga approval submit \
  --session-handoff <approval-session-handoff.json> \
  --session-envelope <approval-session.dsse.json> --session-receipt <approval-session-receipt.json> \
  --role <platform_operations|product_security> \
  --policy operations/ga/approval-policy.yaml \
  --signature-policy certification/evidence/signature-policy.yaml \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --receipt-schema contracts/json-schema/immutable-evidence-receipt.schema.json \
  --submission-schema contracts/json-schema/ga-approval-submission.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json

accordctl ga approval finalize \
  --session-handoff <approval-session-handoff.json> \
  --session-envelope <approval-session.dsse.json> --session-receipt <approval-session-receipt.json> \
  --policy operations/ga/approval-policy.yaml \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --receipt-schema contracts/json-schema/immutable-evidence-receipt.schema.json \
  --submission-schema contracts/json-schema/ga-approval-submission.schema.json \
  --approval-schema operations/ga/approval-record.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json \
  --signature-policy certification/evidence/signature-policy.yaml \
  --approval-output <approval-record.dsse.json> \
  --handoff-output <approval-record-handoff.json> --confirm
```

`.github/workflows/ga-approval.yaml` has a closed `operation=open|finalize` dispatch, literal repository+`ga-approval` concurrency group, `cancel-in-progress: false`, and the fixed `ga-approval` protected environment. Each dispatch verifies the exact released source/tool/image and release-artifact handoff, starts one fresh non-signing OIDC orchestration identity, and launches only the digest-pinned fixed-name `operations/ga/jobs/ga-approval-attestor.yaml`; `open` and `finalize` are separate immutable Jobs and handoffs. Human `submit` never runs inside this workflow. The Job's fixed ServiceAccount/IRSA can read only the exact release-manifest/Cosign/provenance/receipt prefix selected by the release bundle plus named verifier/index/session objects, query only the pinned workforce directory/JWKS, transact only approval partitions, sign only approval-session/approval-record purposes, and write only approval prefixes. It has no tenant-signing, certification, terms, promotion, Argo, Provider, kube-api, or cross-unit mutation permission. `docs/operations/runbooks/ga-approval.md` gives the exact release-artifact retrieval, open, authenticated minimal session-package download, offline verification, two independent submissions, finalize, receipt verification, expiry/restart, role-change, lost-browser, and fail-closed recovery procedures without a mutable URL, ambient cloud credential, database edit, or administrator bypass.

`operations/ga/promotion-policy.yaml` is a closed signed policy that pins the single-writer PostgreSQL coordination region/database/schema, trusted-time source, lease duration, maximum wait, deterministic object-key layout, exact unit-map digest, exact applied EKS name/ARN/private endpoint/CA/audience digest, allowed state transitions, Argo observation sources, audit-log freshness, the three unit-scoped signing purposes, the exact release-manifest digest of `operations/ga/approval-policy.yaml`, and constant `maximum_finalized_approval_age_at_promotion_start=15m`. Normal promotion must receive and verify both this file and `operations/ga/approval-policy.yaml`; their digests must equal each other, the release manifest, approval record, and canonical request. Neither contains a credential or mutable Application locator, and promotion policy cannot weaken or override approval policy.

`ga-promotion-request.schema.json` is a closed JSON Schema 2020-12 JCS payload built only from immutable business bindings. It requires `schema_version`, UUID `promotion_id` and `certification_run_id`, nonempty `certification_unit_id`, constant `target_channel=ga`, 40-lowercase-hex `source_commit`, SHA-256 release-bundle/provider-baseline/unit-map/approval-policy/release-author-object/release-artifact-handoff digests, immutable environment ID, exact RFC 3339 restore point, six-key certification dimensions, policy digest, expected current and target Argo application digests, and the unit map's exact Application name and UID. It carries three immutable predecessor bindings: verification-report envelope/object-version/receipt digests, evidence-index envelope/object-version/receipt digests, and approval-record envelope/object-version/receipt digests. It also binds the independently resolved release-author subject/role-binding version, release-manifest body/object-version/receipt identity, and a digest of normalized security-relevant CLI bindings after replacing local paths with verified content identities. It deliberately contains no wall-clock/request/dispatch/attempt time, local output path, workflow `dispatch_id`, desired gate boolean, derived status, arbitrary patch, image tag, mutable locator, credential, or administrator override. RFC 8785 canonical bytes therefore produce the same `request_digest` at any later clock time for the same immutable intent; trusted start/completion times belong only to attempt/resolution/final receipt payloads.

`ga-promotion-attempt.schema.json` is the closed append-only execution-decision payload signed as `application/vnd.accord.ga-promotion-attempt.v1+jcs`. Every workflow dispatch receives its own UUID `dispatch_id`, Job UID, command digest, and handoff and writes exactly one attempt with disposition `PRECONDITION_REJECTED|CONTENDED|EXECUTION_AUTHORIZED|RETAINED_RECEIPT_RETURNED`. It binds promotion/request/unit IDs, request digest, state observed, unit-map digest, workload identity, all predecessor digests, trusted decision time, frozen `approval_not_after`, `provider_call_authorized`, and the closed command result class/exit code when the attempt is terminal. Only `EXECUTION_AUTHORIZED` may contain the coordination-assigned fencing generation, lease token digest, preassigned Provider request ID, and exact `execution_authorized_at`, and only it may set the boolean true. A negative or replay dispatch therefore leaves durable signed proof that it was never allowed to call the Provider; the unsigned handoff repeats but cannot replace those fields.

`ga-promotion-resolution.schema.json` is the closed append-only authoritative outcome payload signed as `application/vnd.accord.ga-promotion-resolution.v1+jcs`. It binds one execution-authorized attempt, Provider request ID, generation, Application name/UID, before/after resource versions and revisions, immutable Argo history entry, request annotation, Kubernetes/CloudTrail audit event digests, observation interval/source, and exactly `resolution=APPLIED|NO_EFFECT|DIVERGED`. `APPLIED` requires all authoritative facts to match the request; `NO_EFFECT` requires authoritative proof that the exact Provider request made no mutation; anything changed or conflicting is `DIVERGED`; incomplete or ambiguous facts produce no resolution and leave `OUTCOME_UNKNOWN`. Resolution evidence never accepts caller assertions or a mutable summary.

`ga-promotion-receipt.schema.json` is a separate closed JCS success payload signed as DSSE type `application/vnd.accord.ga-promotion-receipt.v1+jcs`. It repeats every request binding, adds `request_digest`, execution-attempt and `APPLIED` resolution envelope/object-version/receipt digests, verified report/index/approval payload/envelope digests, both approval transaction receipt digests, promoter workload/command/dispatch identities, precondition and applied Argo facts, immutable Provider identifiers, frozen started/completed times, and constant `outcome=APPLIED`. There is no success receipt for `OUTCOME_UNKNOWN`, `NO_EFFECT`, or `DIVERGED`. A retained `APPLIED` receipt is returned byte-for-byte before any Provider I/O; a target already at the desired digest without the matching request/attempt/resolution chain is drift and is fenced rather than blessed as already applied. The final envelope and its storage audit receipt are read back under 400-day COMPLIANCE Object Lock before success.

`GaCoordination.java` is the only component allowed to persist a dispatch decision and it always runs before any Provider/Argo read or write. After schema/path/signature-safe parsing, a known evidence/precondition failure conditionally freezes one audit-only `DISPATCH#<dispatch_id>` claim and canonical `PRECONDITION_REJECTED` attempt; that row has no promotion claim, lease, generation, Provider request ID, or execution permit, and deterministic create-only receipt recovery makes retries byte-stable. For a fully valid request, the first side effect is instead one conditional transaction that creates or verifies `promotion_id -> request_digest` in the unit partition and creates `PENDING` generation 1 with a durable lease, cryptographically random preassigned Provider request ID, frozen `approval_decision_at`, and `approval_not_after=approval_decision_at+15m`. A different request digest under that ID fails permanently. A caller holding an expired `PENDING` lease may conditionally create the next generation only because no execution transaction has ever succeeded; it retains the original approval deadline.

Authorization uses a second `SERIALIZABLE` PostgreSQL transaction with one conditional state update and attempt insert, not a check followed by an update in another transaction. From one frozen `clock_timestamp()` value it requires the exact `PENDING` request/generation/lease, absent authorized attempt, unchanged approval-policy digest, and `execution_authorized_at <= approval_not_after`; in the same commit it changes state to `EXECUTING` and freezes the canonical `EXECUTION_AUTHORIZED` attempt bytes/digest, generation, Provider request ID, lease-token digest, and `execution_authorized_at`. Only the successful call stack receives a package-private in-memory permit after that commit. If the frozen time is late, the mutually exclusive conditional transaction records a terminal `PRECONDITION_REJECTED` dispatch/attempt against that PENDING generation and returns no permit; a new approval and new promotion ID are required. No code path signs or retains an authorized attempt while state is still PENDING.

Once the execution transaction commits, no promote caller may steal, renew into another generation, or invoke the Provider again, even if the process crashes before KMS signing, Object Lock put, or the Provider call. Recovery first completes/adopts the frozen attempt object, then moves the operation to `OUTCOME_UNKNOWN` and invokes only reconciliation. The only normal terminal transition is `EXECUTING -> APPLIED`; timeout, cancellation, lost response, or any crash after the execution transaction becomes `OUTCOME_UNKNOWN`. The full execution graph remains `PENDING -> EXECUTING -> APPLIED | OUTCOME_UNKNOWN`; a signed late rejection never creates an execution edge.

The Provider interface requires the opaque permit returned only by the successful transaction above and a mandatory explicit Kubernetes connection object. It constructs only the fixed `rest.Config` Host/BearerTokenFile/CAFile path, validates endpoint/CA/audience/cluster binding, and never falls back to in-cluster/default config. It applies one atomic UID/resourceVersion-tested patch to the unit's exact Application name, setting target revision plus immutable annotations for promotion ID, request digest, Provider request ID, generation, and attempt digest. Admission independently checks caller ServiceAccount, literal resource name, old-object UID, target digest, request annotation, and monotonically fenced generation. One hundred concurrent callers therefore produce one authorized generation and at most one Provider mutation. Contenders wait only within the policy bound and then either return the retained receipt or a signed non-authorized attempt; they do not perform an Argo read as a shortcut.

Every attempt, resolution, and final payload is frozen in coordination state before its corresponding KMS/Object Lock side effects. Objects use deterministic generation/purpose keys and create-only writes. After a crash or uncertain put, `GaReceipts.java` reads that key, verifies canonical payload/signature/purpose/object version/receipt, and adopts exact existing bytes before signing again. The authorized attempt is frozen by the EXECUTING transaction, then signed, retained, and read back before Provider I/O; failure or crash during those post-transaction steps cannot revert to PENDING or authorize another caller. Provider success followed by a crash therefore remains recoverable without another patch. After an immediate authoritative success, the promoter freezes and retains an `APPLIED` resolution and final receipt. If the call outcome cannot be proven, it freezes `OUTCOME_UNKNOWN`, exits `3`, and emits only the attempt handoff; ordinary retry observes the fence and cannot call the Provider.

`ReconcileGa.java` is the sole resolver for `OUTCOME_UNKNOWN`. It loads the frozen request/attempt from coordination, uses the same explicit endpoint/token/CA connection to query the exact configured Application name and UID, target revision, immutable Argo history, request annotation, resource versions, and matching Kubernetes/CloudTrail audit records, and derives only the three resolution values. `APPLIED` atomically changes the state to `APPLIED` and resumes the original frozen final receipt. `NO_EFFECT` retains its signed resolution and conditionally returns the operation to `PENDING` with a required next generation; it never automatically retries, so a later explicit `release promote --channel ga` is required and must obtain a still-current fresh approval or a new approval/promotion ID. `DIVERGED` records the resolution and permanently fences the unit pending a separately authorized remediation and new promotion ID. Reconciliation itself never patches Argo and never reauthorizes, refreshes, or extends approval. `GaCoordinationTest.java`, `GaReceiptsTest.java`, and `ReconcileGaTest.java` assert transaction conditions, call counts, byte identity, and every crash cut with deterministic fakes behind production interfaces.

Modify `Promote.java` rather than introducing a second GA promoter. For `--channel ga`, it resolves every input as a regular non-symlink file; verifies the Task 8 release-artifact handoff plus manifest/Cosign/provenance/receipt bodies and independently derives the authoritative author; verifies the release bundle and exact six protected values; verifies both approval/promotion policies and their release-pinned digests; verifies index/report/approval schemas/JCS/DSSE/purposes/receipts; and independently recomputes the selected unit's gates and `derived_status=ga`. It never consumes the unsigned summary or trusts expected-author flags. It constructs the time-free canonical request and strongly reads coordination before authorization or Provider I/O.

A matching `APPLIED` row returns the already retained and reverified receipt bytes before approval-age or current-role reauthorization; a matching `OUTCOME_UNKNOWN` row exits `3`; a changed request under the same promotion ID fails permanently. Only a missing claim or an explicitly reconciled `PENDING` next generation re-verifies the finalized approval DSSE, current role bindings, two distinct eligible people, consumed operation-bound transaction receipts, independently derived release author, exact bindings, and frozen approval deadline. `GaCoordination.java` enforces that deadline again in the same transaction that enters EXECUTING. Dispatch ID, attempt time, output destination, and advancing clocks cannot change the request digest or extend approval authorization; stale policy/unit map/cluster connection, fenced unit, or binding mismatch fails before an external mutation. APPLIED retry and `reconcile-ga` verify retained operation integrity but never create a new authorization decision or extend the 15-minute threshold.

The exact GA CLI surface is:

```text
accordctl release promote --environment <name> --channel ga \
  --dispatch-id <uuid> --promotion-id <uuid> --certification-run-id <uuid> --unit <certification-unit-id> \
  --source-commit <40-lowercase-hex> --release-bundle <sha256> \
  --expected-environment-id <immutable-id> --restore-point-rfc3339 <timestamp> \
  --provider-baseline-digest <sha256> --expected-current-release-digest <sha256> \
  --release-artifact-handoff <release-artifact-handoff.json> \
  --release-artifact-handoff-schema release/artifact-handoff.schema.json \
  --release-manifest <manifest.json> --release-manifest-cosign-bundle <bundle.json> \
  --release-manifest-provenance <provenance.intoto.jsonl> --release-manifest-receipt <receipt.json> \
  --release-manifest-schema release/manifest.schema.json --release-builder-policy operations/supply-chain/allowed-builders.yaml \
  --expected-release-manifest-digest <sha256> --expected-release-manifest-object-version <version> \
  --expected-release-manifest-receipt-digest <sha256> \
  --verification-report <report.dsse.json> --expected-verification-report-digest <sha256> \
  --expected-verification-report-object-version <version> \
  --evidence-index <index.dsse.json> --expected-evidence-index-digest <sha256> \
  --expected-evidence-index-object-version <version> \
  --approval-record <approval.dsse.json> --expected-approval-record-digest <sha256> \
  --expected-approval-record-object-version <version> \
  --signature-policy certification/evidence/signature-policy.yaml \
  --approval-policy operations/ga/approval-policy.yaml \
  --promotion-policy operations/ga/promotion-policy.yaml \
  --approval-schema operations/ga/approval-record.schema.json \
  --request-schema contracts/json-schema/ga-promotion-request.schema.json \
  --attempt-schema contracts/json-schema/ga-promotion-attempt.schema.json \
  --resolution-schema contracts/json-schema/ga-promotion-resolution.schema.json \
  --receipt-schema contracts/json-schema/ga-promotion-receipt.schema.json \
  --expected-eks-cluster-name <name> --expected-eks-cluster-arn <arn> \
  --kube-api-endpoint <private-https-endpoint> --kube-token-file /var/run/accord/kube-api/token \
  --kube-ca-file /var/run/accord/kube-api/ca.crt --expected-kube-ca-digest <sha256> \
  --expected-kube-token-audience <audience> \
  --attempt-output <promotion-attempt.dsse.json> \
  --attempt-handoff-output <promotion-attempt-handoff.json> \
  --resolution-output <promotion-resolution.dsse.json> \
  --resolution-handoff-output <promotion-resolution-handoff.json> \
  --receipt-output <promotion-receipt.dsse.json> \
  --receipt-handoff-output <promotion-receipt-handoff.json> --confirm

accordctl release promote reconcile-ga \
  --dispatch-id <uuid> --promotion-id <uuid> --request-digest <sha256> --unit <certification-unit-id> \
  --signature-policy certification/evidence/signature-policy.yaml \
  --promotion-policy operations/ga/promotion-policy.yaml \
  --attempt-schema contracts/json-schema/ga-promotion-attempt.schema.json \
  --resolution-schema contracts/json-schema/ga-promotion-resolution.schema.json \
  --receipt-schema contracts/json-schema/ga-promotion-receipt.schema.json \
  --expected-eks-cluster-name <name> --expected-eks-cluster-arn <arn> \
  --kube-api-endpoint <private-https-endpoint> --kube-token-file /var/run/accord/kube-api/token \
  --kube-ca-file /var/run/accord/kube-api/ca.crt --expected-kube-ca-digest <sha256> \
  --expected-kube-token-audience <audience> \
  --resolution-output <promotion-resolution.dsse.json> \
  --resolution-handoff-output <promotion-resolution-handoff.json> \
  --receipt-output <promotion-receipt.dsse.json> \
  --receipt-handoff-output <promotion-receipt-handoff.json> --confirm

accordctl ga verify-promotion-rehearsal \
  --unit <certification-unit-id> --rejected-promotion-id <uuid> --promotion-id <uuid> \
  --negative-dispatch-id <uuid> --success-dispatch-id <uuid> --retry-dispatch-id <uuid> \
  --negative-launch-receipt <receipt.json> --negative-attempt-handoff <handoff.json> \
  --success-launch-receipt <receipt.json> --success-attempt-handoff <handoff.json> \
  --success-resolution-handoff <handoff.json> --success-receipt-handoff <handoff.json> \
  --retry-launch-receipt <receipt.json> --retry-attempt-handoff <handoff.json> \
  --retry-receipt-handoff <handoff.json> \
  --provider-audit-cursor-before <cursor> --provider-audit-cursor-after <cursor> \
  --kubernetes-audit-cursor-before <cursor> --kubernetes-audit-cursor-after <cursor> \
  --signature-policy certification/evidence/signature-policy.yaml \
  --approval-policy operations/ga/approval-policy.yaml --promotion-policy operations/ga/promotion-policy.yaml \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json \
  --attempt-schema contracts/json-schema/ga-promotion-attempt.schema.json \
  --resolution-schema contracts/json-schema/ga-promotion-resolution.schema.json \
  --receipt-schema contracts/json-schema/ga-promotion-receipt.schema.json \
  --summary-output <promotion-rehearsal-verification.json>
```

No GA flag is optional or defaulted. Normal promote requires the signed approval policy and consumes its threshold before claim and again in the atomic execution transaction. `reconcile-ga` deliberately accepts only identity/schema/policy/output and explicit kube-connection bindings and resolves every operation fact from the frozen coordination record and fixed unit map; it does not accept approval policy or restart authorization. `verify-promotion-rehearsal` is implemented in existing `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Verify.java` with table-driven cases in `VerifyTest.java`; it is read-only, uses the ga-verifier identity, and has no Provider or Kubernetes mutation permission. It verifies the three authenticated launch receipts, separately resolves every unsigned handoff to exact DSSE+receipt bytes, strongly reads the exact coordination rows, and queries immutable Provider plus Kubernetes/Argo audit streams between the supplied cursors. The negative dispatch must have actual terminal code `1`, result class `known_rejection`, `PRECONDITION_REJECTED`, `provider_call_authorized=false`, no promotion claim/lease/generation/request ID/permit/resolution/final receipt, and zero audit mutation delta. The success dispatch must have code `0`, `EXECUTION_AUTHORIZED`, one matching Provider/Argo mutation, `APPLIED` resolution, and a retained receipt. The retry must have code `0`, `RETAINED_RECEIPT_RETURNED`, no new permit/mutation, byte-identical final receipt, and cumulative mutation count one. Missing/ambiguous live or audit facts return `3`; a known mismatch returns `1`.

The command exit code and closed result class are frozen in the signed terminal attempt/resolution/receipt and repeated in the authenticated immutable launch receipt and unsigned handoff. `AccordCli.java` maps result classes to `0|1|2|3` and the `accordctl` process exits directly with that code after retained-object read-back; no shell wrapper translates it. A crash before terminal evidence yields no completed launch receipt and is classified `3`, never a deliberate rejection. The Task 13 LA/controlled-channel interfaces remain separate and cannot accept GA approval/report flags or mint a GA receipt. Update `docs/operations/runbooks/release-promotion.md` with exact release-artifact/evidence acquisition, finalized approval, kube trust, dry validation, protected invocation, and receipt verification. `docs/operations/runbooks/ga-promotion-reconciliation.md` covers `OUTCOME_UNKNOWN`, all three authoritative outcomes, evidence acquisition, unit fencing, escalation, and rollback triggers. Neither runbook offers a manual PostgreSQL edit, direct Argo UI patch, blind retry, force flag, or administrator bypass.

`.github/workflows/ga-promotion.yaml` is deliberately outside `ga-certification.yaml` and has closed `operation=promote|reconcile|verify-rehearsal` plus `dispatch_id` where applicable. It receives only immutable handoffs/bindings, unit, promotion ID, release-artifact identity, and expected current release for `promote`; unit/promotion ID/request digest for `reconcile`; or the exact three dispatch/launch/handoff sets plus audit cursors for `verify-rehearsal`. The resolver validates the unit against the signed certification-unit map before selecting a protected environment and emits that fixed environment as a trusted job output. Promote/reconcile concurrency is repository plus that resolved environment; verification concurrency is repository plus literal `ga-verifier`; all set `cancel-in-progress: false`. Caller-supplied environment, concurrency suffix, ServiceAccount, role, prefix, KMS key, Application name, UID, cluster endpoint, or CA is forbidden.

Each allowed unit maps to its own protected GitHub environment and short-lived non-signing OIDC orchestration role, which can create/watch only that unit's fixed-name digest-pinned rendered `operations/ga/jobs/ga-promoter.yaml` instance. Every promote or reconcile dispatch launches exactly one immutable promoter Job whose container command is the single `accordctl release promote ...` or `reconcile-ga ...` process. The negative Job exits `1` itself; there is no `if`, `test`, `cmp`, shell status translation, or second verifier command in that Pod. Negative, successful, retry, and reconcile operations are never concatenated. A later distinct `verify-rehearsal` dispatch launches one fixed-name `ga-verifier` Job running only `accordctl ga verify-promotion-rehearsal`; it reads promotion evidence/coordination/audit facts but has no kube-api token, Argo patch, Provider call, promotion KMS key, or promotion-partition write permission.

The per-unit rendered promoter Job has a unit-specific ServiceAccount/IRSA, promotion-attempt/resolution/receipt KMS keys and prefixes, coordination partition, NetworkPolicy, Kubernetes Role/RoleBinding with literal Application `resourceNames`, and UID-aware admission binding. It can read only the exact Task 8 release prefix and named run/unit report/index/approval objects and receipts and cannot use certification/approval/terms/tenant keys, change evidence, approve, launch a Job, mutate another unit, or widen traffic outside the closed GA target revision. Its explicit IRSA token/AWS role and separate kube token/endpoint/CA connection follow the Task 4 contract. The orchestration role cannot read Provider credentials or evidence bodies, sign, write evidence, or assume the pod role. Admission binds released `accordctl`, Job template/image/UID, complete command digest, dispatch ID, six-value tuple where applicable, release-artifact and predecessor identities, approval/promotion policy digests, unit-map and applied-cluster-connection digests, expected current release, fixed token/CA paths, and exact command mode. The workflow returns only closed unsigned attempt/resolution/promotion handoff locators plus an authenticated launch receipt; downstream consumers verify referenced bodies.

`infra/helm/accord/values.schema.json` makes the fixed approval lane, certification-unit map, and applied-cluster connection object required with no defaults. It rejects empty, duplicate, extra, or incomplete units and any name/ARN/private endpoint/CA data/CA digest/audience disagreement with signed applied-root outputs. `serviceaccounts.yaml` and `networkpolicies.yaml` render the fixed approval resources and one isolated promoter identity/network set per unit; `ga-control-lanes.yaml` renders the fixed submission-route configuration, immutable CA trust Secret, two projected token volumes, fixed AWS role/token environment, explicit kube connection arguments, and UID/fencing ValidatingAdmissionPolicy/Binding, but no duplicate ServiceAccount or RBAC object. `infra/argocd/rbac/ga-promoters.yaml` is the sole owner of each per-unit Kubernetes Role/RoleBinding with literal `resourceNames`, while `accord-production.yaml` is the sole owner of Application name/UID/project/target policy.

Standard NetworkPolicy permits only DNS, the exact private Kubernetes API endpoint, telemetry, and the declared private STS/KMS/PostgreSQL/Object-Store/directory endpoints for that lane; VPC endpoint policies, security groups, TLS client identities, database roles, and forced RLS enforce the same destination/database/schema/scope restrictions. Certification/approval/terms Jobs have no kube token, CA trust mount, or API egress. `admission.rego`, `admission_test.yaml`, Helm tests, `ga-separation` tests, and `ga-control-lanes.tftest.hcl` reject duplicate ownership, a shared identity, wildcard, mutable image, missing default-deny, metadata/internet egress, public/wrong endpoint, wrong CA bytes/digest, wrong or exchanged token audience/path, missing explicit AWS web-identity binding, default kube client fallback, wrong Application name/UID, cross-unit key/prefix/scope, stale generation, missing signed purpose, or any caller override.

Create `operations/ga/checklist.yaml`:

```yaml
schema_version: "1.0"
required_gates:
  - complete-role-oriented-product-journey
  - specification-sections-01-through-21-and-appendices-a-through-d
  - authorization-signature-audit-and-source-boundary
  - signed-release-sbom-provenance-and-admission
  - slo-capacity-cost-and-zero-tolerance-window
  - backup-pitr-regional-dr-and-key-drills
  - fault-convergence-oncall-and-runbooks
  - per-unit-model-thresholds
  - preregistered-product-value-thresholds
  - role-documentation-and-support-ownership
  - versioned-signed-contractual-terms-per-eligible-unit
status_rule: derive_only_never_accept_input_status
required_unit_gate_field: contractual_terms_pass
failed_contractual_terms_max_status: limited_availability
administrator_override_allowed: false
```

`operations/ga/rehearsal.schema.json` is closed and permits only versioned operation IDs registered by `Rehearse.java`; it forbids shell text, executable paths, URLs, inline credentials, desired result/status, or operator-selected evidence types. `rehearsal.yaml` uses this exact structure, with each evidence output resolved beneath the release/run/environment content-addressed root and every protected input supplied by the CI environment rather than the file:

```yaml
schema_version: "1.0"
required_protected_inputs:
  - ACCORD_CERTIFICATION_RUN_ID
  - ACCORD_SOURCE_COMMIT
  - ACCORD_STAGING_ENVIRONMENT_ID
  - ACCORD_RELEASE_BUNDLE_DIGEST
  - ACCORD_RESTORE_POINT_RFC3339
  - ACCORD_PROVIDER_BASELINE_DIGEST
stages:
  - { id: live-workload-inventory, operation_id: ops.helm.capture_live_inventory, evidence_type: accord.operations.helm-inventory.v1 }
  - { id: applied-root-verification, operation_id: ops.iac.verify_applied_roots, evidence_type: accord.operations.iac-apply-set.v1 }
  - { id: synthetic-catalog, operation_id: ops.synthetic.run, evidence_type: accord.operations.synthetic-report.v1 }
  - { id: isolated-pitr, operation_id: ops.restore.drill, evidence_type: accord.operations.restore-report.v1 }
  - { id: regional-dr, operation_id: ops.dr.drill, evidence_type: accord.operations.dr-report.v1 }
  - { id: key-lifecycle, operation_id: ops.keys.rotation_and_compromise_drill, evidence_type: accord.operations.key-lifecycle-report.v1 }
  - { id: fault-convergence, operation_id: ops.chaos.run, evidence_type: accord.operations.chaos.v1 }
  - { id: blind-oncall, operation_id: ops.incident.drill, evidence_type: accord.operations.oncall-drill.v1 }
```

`Rehearse.java` validates the schema and fixed order; pins all stages to the caller-supplied UUID `certification_run_id`, one release bundle, one immutable environment ID, the explicit RFC 3339 restore point, and the exact signed Provider baseline digest; and persists each signed failed or passed artifact plus receipt before continuing. It never generates, defaults, discovers, or substitutes any of those values. The restore stage must use the exact requested point, and every Provider-affecting stage must verify and restore the exact baseline. When a verified stage result is a reusable operational source envelope rather than an already run-bound artifact, it creates and reads back the signed `ga-run-evidence-binding` wrapper above before recording the stage receipt; copying the source bytes into the run prefix is never sufficient. It stops on an inconclusive cleanup/security boundary; ordinary gate failure preserves diagnostics and prevents collection from producing GA. Resume uses the immutable stage receipt and same run/input/wrapper digests, never a client-supplied completed flag. Its final retained receipt-set handoff binds the restore point and Provider baseline, is stored at the deterministic release/run/staging prefix, and is copied atomically to `--handoff-output` only after Object Lock version and receipt read-back succeed.

Create the root `Makefile` as a thin, reproducible wrapper around the evidence commands:

```makefile
GA_ENVIRONMENT ?= staging
GA_CERTIFICATION_RUN_ID ?=
GA_RELEASE ?=
GA_RELEASE_BUNDLE ?=
GA_ENVIRONMENT_ID ?=
GA_RESTORE_POINT_RFC3339 ?=
GA_PROVIDER_BASELINE_DIGEST ?=
GA_REHEARSAL_RECEIPT_SET_PATH ?=
GA_REHEARSAL_RECEIPT_SET_DIGEST ?=
GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION ?=
GA_REHEARSAL_HANDOFF_OUTPUT ?=
GA_INDEX_PATH ?=
GA_INDEX_DIGEST ?=
GA_INDEX_OBJECT_VERSION ?=
GA_INDEX_HANDOFF_OUTPUT ?=
GA_REPORT_OUTPUT ?=
GA_SUMMARY_OUTPUT ?=
PUBLISHED_ACCORDCTL ?= build/release/accordctl
VERIFIED_ACCORDCTL ?= build/ga/verified/accordctl
ACCORDCTL_VERIFICATION ?= build/ga/accordctl-verification.json
RELEASE_MANIFEST ?= build/release/manifest.json
RELEASE_MANIFEST_COSIGN_BUNDLE ?= build/release/manifest.cosign.bundle.json
ACCORDCTL_COSIGN_BUNDLE ?= build/release/accordctl.cosign.bundle.json
ACCORDCTL_PROVENANCE ?= build/release/accordctl.intoto.jsonl
GA_JOB_LAUNCHER ?= ./scripts/ga/launch-immutable-job.sh
POD_ACCORDCTL ?= /opt/accord/bin/accordctl

.PHONY: require-ga-inputs require-rehearsal-output require-rehearsal-handoff require-index-output require-index-handoff require-report-output verify-accordctl ga-rehearse ga-collect ga-verify ga
require-ga-inputs:
	@test -n "$(GA_CERTIFICATION_RUN_ID)" || { echo 'GA_CERTIFICATION_RUN_ID is required' >&2; exit 2; }
	@test -n "$(GA_RELEASE)" || { echo 'GA_RELEASE is required' >&2; exit 2; }
	@test -n "$(GA_RELEASE_BUNDLE)" || { echo 'GA_RELEASE_BUNDLE is required' >&2; exit 2; }
	@test -n "$(GA_ENVIRONMENT_ID)" || { echo 'GA_ENVIRONMENT_ID is required' >&2; exit 2; }
	@test -n "$(GA_RESTORE_POINT_RFC3339)" || { echo 'GA_RESTORE_POINT_RFC3339 is required' >&2; exit 2; }
	@test -n "$(GA_PROVIDER_BASELINE_DIGEST)" || { echo 'GA_PROVIDER_BASELINE_DIGEST is required' >&2; exit 2; }

require-rehearsal-output:
	@test -n "$(GA_REHEARSAL_HANDOFF_OUTPUT)" || { echo 'GA_REHEARSAL_HANDOFF_OUTPUT is required' >&2; exit 2; }

require-rehearsal-handoff:
	@test -n "$(GA_REHEARSAL_RECEIPT_SET_PATH)" || { echo 'GA_REHEARSAL_RECEIPT_SET_PATH is required' >&2; exit 2; }
	@test -n "$(GA_REHEARSAL_RECEIPT_SET_DIGEST)" || { echo 'GA_REHEARSAL_RECEIPT_SET_DIGEST is required' >&2; exit 2; }
	@test -n "$(GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION)" || { echo 'GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION is required' >&2; exit 2; }

require-index-output:
	@test -n "$(GA_INDEX_PATH)" || { echo 'GA_INDEX_PATH is required' >&2; exit 2; }
	@test -n "$(GA_INDEX_HANDOFF_OUTPUT)" || { echo 'GA_INDEX_HANDOFF_OUTPUT is required' >&2; exit 2; }

require-index-handoff:
	@test -n "$(GA_INDEX_PATH)" || { echo 'GA_INDEX_PATH is required' >&2; exit 2; }
	@test -n "$(GA_INDEX_DIGEST)" || { echo 'GA_INDEX_DIGEST is required' >&2; exit 2; }
	@test -n "$(GA_INDEX_OBJECT_VERSION)" || { echo 'GA_INDEX_OBJECT_VERSION is required' >&2; exit 2; }

require-report-output:
	@test -n "$(GA_REPORT_OUTPUT)" || { echo 'GA_REPORT_OUTPUT is required' >&2; exit 2; }
	@test -n "$(GA_SUMMARY_OUTPUT)" || { echo 'GA_SUMMARY_OUTPUT is required' >&2; exit 2; }

verify-accordctl: require-ga-inputs
	./scripts/release/verify-accordctl.sh --binary "$(PUBLISHED_ACCORDCTL)" --binary-cosign-bundle "$(ACCORDCTL_COSIGN_BUNDLE)" --provenance "$(ACCORDCTL_PROVENANCE)" --manifest "$(RELEASE_MANIFEST)" --manifest-cosign-bundle "$(RELEASE_MANIFEST_COSIGN_BUNDLE)" --manifest-schema release/manifest.schema.json --builder-policy operations/supply-chain/allowed-builders.yaml --expected-source-commit "$(GA_RELEASE)" --expected-release-bundle "$(GA_RELEASE_BUNDLE)" --verified-output "$(VERIFIED_ACCORDCTL)" --record-output "$(ACCORDCTL_VERIFICATION)"

ga-rehearse: verify-accordctl require-rehearsal-output
	"$(GA_JOB_LAUNCHER)" --job-id staging-operations --template operations/ga/jobs/staging-operations.yaml --verification-record "$(ACCORDCTL_VERIFICATION)" --certification-run-id "$(GA_CERTIFICATION_RUN_ID)" --source-commit "$(GA_RELEASE)" --release-bundle-digest "$(GA_RELEASE_BUNDLE)" --immutable-environment-id "$(GA_ENVIRONMENT_ID)" --restore-point-rfc3339 "$(GA_RESTORE_POINT_RFC3339)" --provider-baseline-digest "$(GA_PROVIDER_BASELINE_DIGEST)" --handoff-output "$(GA_REHEARSAL_HANDOFF_OUTPUT)" -- "$(POD_ACCORDCTL)" ga rehearse --certification-run-id "$(GA_CERTIFICATION_RUN_ID)" --environment "$(GA_ENVIRONMENT)" --expected-environment-id "$(GA_ENVIRONMENT_ID)" --release "$(GA_RELEASE)" --release-bundle "$(GA_RELEASE_BUNDLE)" --restore-point-rfc3339 "$(GA_RESTORE_POINT_RFC3339)" --provider-baseline-digest "$(GA_PROVIDER_BASELINE_DIGEST)" --plan operations/ga/rehearsal.yaml --handoff-output "$(GA_REHEARSAL_HANDOFF_OUTPUT)" --confirm

ga-collect: verify-accordctl require-rehearsal-handoff require-index-output
	"$(GA_JOB_LAUNCHER)" --job-id ga-collector --template operations/ga/jobs/ga-collector.yaml --verification-record "$(ACCORDCTL_VERIFICATION)" --certification-run-id "$(GA_CERTIFICATION_RUN_ID)" --source-commit "$(GA_RELEASE)" --release-bundle-digest "$(GA_RELEASE_BUNDLE)" --immutable-environment-id "$(GA_ENVIRONMENT_ID)" --restore-point-rfc3339 "$(GA_RESTORE_POINT_RFC3339)" --provider-baseline-digest "$(GA_PROVIDER_BASELINE_DIGEST)" --handoff-output "$(GA_INDEX_HANDOFF_OUTPUT)" -- "$(POD_ACCORDCTL)" ga collect --certification-run-id "$(GA_CERTIFICATION_RUN_ID)" --release "$(GA_RELEASE)" --release-bundle "$(GA_RELEASE_BUNDLE)" --expected-environment-id "$(GA_ENVIRONMENT_ID)" --expected-restore-point-rfc3339 "$(GA_RESTORE_POINT_RFC3339)" --expected-provider-baseline-digest "$(GA_PROVIDER_BASELINE_DIGEST)" --runtime-bundle certification/evidence/runtime-bundle.json --units certification/units.yaml --evidence-root certification/evidence --rehearsal-receipt-set "$(GA_REHEARSAL_RECEIPT_SET_PATH)" --expected-rehearsal-receipt-set-digest "$(GA_REHEARSAL_RECEIPT_SET_DIGEST)" --expected-rehearsal-receipt-set-object-version "$(GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION)" --output "$(GA_INDEX_PATH)" --handoff-output "$(GA_INDEX_HANDOFF_OUTPUT)"

ga-verify: verify-accordctl require-index-handoff require-report-output
	"$(GA_JOB_LAUNCHER)" --job-id ga-verifier --template operations/ga/jobs/ga-verifier.yaml --verification-record "$(ACCORDCTL_VERIFICATION)" --certification-run-id "$(GA_CERTIFICATION_RUN_ID)" --source-commit "$(GA_RELEASE)" --release-bundle-digest "$(GA_RELEASE_BUNDLE)" --immutable-environment-id "$(GA_ENVIRONMENT_ID)" --restore-point-rfc3339 "$(GA_RESTORE_POINT_RFC3339)" --provider-baseline-digest "$(GA_PROVIDER_BASELINE_DIGEST)" -- "$(POD_ACCORDCTL)" ga verify --certification-run-id "$(GA_CERTIFICATION_RUN_ID)" --index "$(GA_INDEX_PATH)" --expected-index-digest "$(GA_INDEX_DIGEST)" --expected-index-object-version "$(GA_INDEX_OBJECT_VERSION)" --release "$(GA_RELEASE)" --release-bundle "$(GA_RELEASE_BUNDLE)" --expected-environment-id "$(GA_ENVIRONMENT_ID)" --expected-restore-point-rfc3339 "$(GA_RESTORE_POINT_RFC3339)" --expected-provider-baseline-digest "$(GA_PROVIDER_BASELINE_DIGEST)" --schema contracts/json-schema/ga-evidence-manifest.schema.json --report-schema contracts/json-schema/ga-verification-report.schema.json --signature-policy certification/evidence/signature-policy.yaml --report-output "$(GA_REPORT_OUTPUT)" --summary-output "$(GA_SUMMARY_OUTPUT)"

ga:
	@echo 'cross-lane GA must run through .github/workflows/ga-certification.yaml' >&2
	@exit 2
```

The Makefile intentionally has no dependency chain between the three certification lane targets. `make ga` fails with exit `2`; only the protected workflow may carry validated immutable handoff fields from one fresh job to the next. The Make targets have no default run input, index, or predecessor path and reject every missing protected binding, digest, object version, or output parameter before launching Kubernetes. Each certification GitHub job invokes exactly one target, starts from a fresh workspace and OIDC token on the private ephemeral runner, and `launch-immutable-job.sh` requires the exact template/fixed-job/namespace/ServiceAccount/image plus all six outer protected flags and the applied cluster name/ARN/private endpoint/CA digest. It compares each value byte-for-byte with signed applied-root evidence, the independent verified-binary record, immutable Job template, and command after `--`; JCS-hashes the normalized complete tuple into the admitted command digest; strongly requires the fixed Job to be absent; creates it with a fresh `emptyDir`; and waits for a terminal state. It then retrieves only the exact unsigned handoff locator, rechecks all bindings and Job UID, deletes with a UID precondition, and waits for absence. A missing/duplicate/mismatched value fails before create; an existing Job, ambiguous handoff, or uncertain delete exits `3` without replacement or preemption.

The same launcher has closed, non-overlapping admission profiles for approval, promotion, reconciliation, and read-only promotion-rehearsal verification. Approval `open`/`finalize` and normal `promote` require the same six-tuple profile plus release-artifact, predecessor, unit, dispatch, policy, and where applicable fixed kube-connection identities. `reconcile-ga` uses a different profile containing only dispatch ID, promotion ID, request digest, unit, unit-map/cluster-connection digests, exact rendered Job identity, explicit kube connection, and output destinations; the in-pod command resolves every six-tuple/Provider fact from the frozen claim. `verify-promotion-rehearsal` accepts only the three immutable launch/handoff sets, policies/schemas, and audit cursors under the ga-verifier read-only profile. A profile cannot omit/add/reinterpret a field or invoke another command. The launcher cannot execute `accordctl` locally, obtain a protected value from ambient environment, parse stdout as evidence, accept a caller-selected ServiceAccount/Application/cluster connection, or assume the pod IRSA role.

- [ ] **Step 4: Commit the immutable GA implementation**

Stage exactly the Files declared by Task 14, review the staged-name allowlist, and commit before any production rehearsal:

```bash
git add \
  .tool-versions Makefile \
  .github/workflows/ga-certification.yaml .github/workflows/ga-approval.yaml .github/workflows/ga-promotion.yaml \
  .github/workflows/contract-terms-package-publish.yaml .github/workflows/contract-acceptance-attest.yaml \
  contracts/json-schema/ga-evidence-manifest.schema.json contracts/json-schema/ga-verification-report.schema.json \
  contracts/json-schema/ga-approval-session.schema.json contracts/json-schema/ga-approval-submission.schema.json \
  contracts/json-schema/ga-promotion-request.schema.json contracts/json-schema/ga-promotion-attempt.schema.json \
  contracts/json-schema/ga-promotion-resolution.schema.json contracts/json-schema/ga-promotion-receipt.schema.json \
  contracts/json-schema/ga-handoff-record.schema.json contracts/json-schema/ga-run-evidence-binding.schema.json \
  contracts/json-schema/contractual-terms-manifest.schema.json contracts/json-schema/contractual-terms-acceptance.schema.json \
  certification/evidence/signature-policy.yaml certification/terms/contractual-terms-manifest.v1.json \
  certification/terms/signature-policy.yaml certification/terms/fixtures/contractual-terms-manifest.valid.dsse.json \
  certification/terms/fixtures/contractual-terms-acceptance.valid.dsse.json \
  operations/ga/checklist.yaml operations/ga/rehearsal.schema.json operations/ga/rehearsal.yaml \
  operations/ga/approval-policy.yaml operations/ga/promotion-policy.yaml operations/ga/approval-record.schema.json \
  operations/ga/jobs/job.schema.json operations/ga/jobs/staging-operations.yaml operations/ga/jobs/ga-collector.yaml \
  operations/ga/jobs/ga-verifier.yaml operations/ga/jobs/ga-approval-attestor.yaml operations/ga/jobs/ga-promoter.yaml \
  operations/ga/jobs/contract-acceptance-attestor.yaml \
  infra/policy/ci/ga-separation.rego infra/policy/ci/ga-separation_test.yaml \
  infra/policy/supply-chain/admission.rego infra/policy/supply-chain/admission_test.yaml \
  infra/opentofu/tests/ga-control-lanes.tftest.hcl \
  infra/helm/accord/values.yaml infra/helm/accord/values.schema.json \
  infra/helm/accord/templates/serviceaccounts.yaml infra/helm/accord/templates/networkpolicies.yaml \
  infra/helm/accord/templates/ga-control-lanes.yaml infra/helm/accord/tests/ga-control-lanes.yaml \
  infra/argocd/applications/accord-production.yaml infra/argocd/rbac/ga-promoters.yaml \
  database/operations-coordination/migrations/V001__ga_coordination.sql \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Approval.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalTest.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/ApprovalSession.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalSessionTest.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/ApprovalSubmission.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalSubmissionTest.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/ApprovalFinalizer.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalFinalizerTest.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/ApprovalStore.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ApprovalStoreTest.java \
  cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/PostgresGaCoordinationIT.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Bundle.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Collect.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Report.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Verify.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ga/Rehearse.java \
  cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/ContractualTermsTest.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/ga/VerifyTest.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/terms/ContractPackage.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/terms/Acceptance.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/terms/TermsTest.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/Promote.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/PromotionTest.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/GaCoordination.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/GaCoordinationTest.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/GaReceipts.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/GaReceiptsTest.java \
  cmd/accordctl/src/main/java/com/inforvans/accord/cli/release/ReconcileGa.java cmd/accordctl/src/test/java/com/inforvans/accord/cli/release/ReconcileGaTest.java \
  scripts/ga/launch-immutable-job.sh scripts/ga/launch-immutable-job.test.sh \
  scripts/release/verify-accordctl.sh scripts/release/verify-accordctl.test.sh \
  docs/operations/runbooks/release-promotion.md docs/operations/runbooks/ga-approval.md \
  docs/operations/runbooks/ga-promotion-reconciliation.md apps/web/tests/e2e/ga/certification-gate.spec.ts
git diff --cached --name-only --diff-filter=ACMR
git commit -m "ops: automate complete Accord GA evidence gate"
```

Expected: the staged list is exactly the Task 14 Files list with no undeclared path and no omitted file; the commit succeeds once; and `git status --porcelain=v1` is empty. Record this SHA as `ACCORD_TASK14_IMPLEMENTATION_COMMIT`; it is a required ancestor of, but not a substitute for, the final immutable candidate. Master Plan Task 8 Step 4 completes the remaining Web/lock/integration work, creates the final signed candidate commit and release bundle, and supplies `ACCORD_FINAL_CANDIDATE_COMMIT`. Only that final candidate SHA is accepted as the rehearsal/certification/promotion `source_commit`.

- [ ] **Step 5: Run the full production rehearsal from a clean checkout of the Master final candidate commit**

Create a detached clean checkout and run all preflight checks there. Keep the implementation checkout unchanged until every remote handoff has been verified:

```bash
test -z "$(git status --porcelain=v1)"
test -n "$ACCORD_TASK14_IMPLEMENTATION_COMMIT"
test -n "$ACCORD_FINAL_CANDIDATE_COMMIT"
git merge-base --is-ancestor "$ACCORD_TASK14_IMPLEMENTATION_COMMIT" "$ACCORD_FINAL_CANDIDATE_COMMIT"
GA_REHEARSAL_ROOT="$(mktemp -d)"
git worktree add --detach "$GA_REHEARSAL_ROOT/accord" "$ACCORD_FINAL_CANDIDATE_COMMIT"
cd "$GA_REHEARSAL_ROOT/accord"
test "$(git rev-parse HEAD)" = "$ACCORD_FINAL_CANDIDATE_COMMIT"
test -z "$(git status --porcelain=v1)"
./gradlew :cmd:accordctl:test --tests 'com.inforvans.accord.cli.ga.*' --tests 'com.inforvans.accord.cli.terms.*'
./gradlew :cmd:accordctl:test --tests '*GaPromotion*' --tests '*GaReconcile*'
bash scripts/ga/launch-immutable-job.test.sh
bash scripts/release/verify-accordctl.test.sh
conftest verify -p infra/policy/ci infra/policy/ci/ga-separation_test.yaml
conftest verify -p infra/policy/supply-chain infra/policy/supply-chain/admission_test.yaml
conftest test .github/workflows/ga-certification.yaml .github/workflows/ga-approval.yaml .github/workflows/ga-promotion.yaml .github/workflows/contract-terms-package-publish.yaml .github/workflows/contract-acceptance-attest.yaml operations/ga/jobs -p infra/policy/ci
helm unittest infra/helm/accord -f 'tests/ga-control-lanes.yaml'
tofu -chdir=infra/opentofu test -filter=tests/ga-control-lanes.tftest.hcl
corepack pnpm@10.12.4 --filter @accord/web test:e2e -- tests/e2e/ga/certification-gate.spec.ts
```

Then dispatch `ga-certification.yaml` with the six immutable inputs. The workflow runs these commands in three different protected jobs and fresh workspaces; these blocks must never be concatenated into one shell:

```bash
# staging-operations job
make ga-rehearse GA_ENVIRONMENT=staging GA_CERTIFICATION_RUN_ID="$ACCORD_CERTIFICATION_RUN_ID" GA_RELEASE="$ACCORD_SOURCE_COMMIT" GA_RELEASE_BUNDLE="$ACCORD_RELEASE_BUNDLE_DIGEST" GA_ENVIRONMENT_ID="$ACCORD_STAGING_ENVIRONMENT_ID" GA_RESTORE_POINT_RFC3339="$ACCORD_RESTORE_POINT_RFC3339" GA_PROVIDER_BASELINE_DIGEST="$ACCORD_PROVIDER_BASELINE_DIGEST" GA_REHEARSAL_HANDOFF_OUTPUT="$GA_REHEARSAL_HANDOFF_OUTPUT"
```

```bash
# ga-collector job, after resolving the immutable rehearsal receipt set
make ga-collect GA_ENVIRONMENT=staging GA_CERTIFICATION_RUN_ID="$ACCORD_CERTIFICATION_RUN_ID" GA_RELEASE="$ACCORD_SOURCE_COMMIT" GA_RELEASE_BUNDLE="$ACCORD_RELEASE_BUNDLE_DIGEST" GA_ENVIRONMENT_ID="$ACCORD_STAGING_ENVIRONMENT_ID" GA_RESTORE_POINT_RFC3339="$ACCORD_RESTORE_POINT_RFC3339" GA_PROVIDER_BASELINE_DIGEST="$ACCORD_PROVIDER_BASELINE_DIGEST" GA_REHEARSAL_RECEIPT_SET_PATH="$GA_REHEARSAL_RECEIPT_SET_PATH" GA_REHEARSAL_RECEIPT_SET_DIGEST="$GA_REHEARSAL_RECEIPT_SET_DIGEST" GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION="$GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION" GA_INDEX_PATH="$GA_INDEX_PATH" GA_INDEX_HANDOFF_OUTPUT="$GA_INDEX_HANDOFF_OUTPUT"
```

```bash
# ga-verifier job, after resolving the immutable index object version and digest
make ga-verify GA_ENVIRONMENT=staging GA_CERTIFICATION_RUN_ID="$ACCORD_CERTIFICATION_RUN_ID" GA_RELEASE="$ACCORD_SOURCE_COMMIT" GA_RELEASE_BUNDLE="$ACCORD_RELEASE_BUNDLE_DIGEST" GA_ENVIRONMENT_ID="$ACCORD_STAGING_ENVIRONMENT_ID" GA_RESTORE_POINT_RFC3339="$ACCORD_RESTORE_POINT_RFC3339" GA_PROVIDER_BASELINE_DIGEST="$ACCORD_PROVIDER_BASELINE_DIGEST" GA_INDEX_PATH="$GA_INDEX_PATH" GA_INDEX_DIGEST="$GA_INDEX_DIGEST" GA_INDEX_OBJECT_VERSION="$GA_INDEX_OBJECT_VERSION" GA_REPORT_OUTPUT="$GA_REPORT_OUTPUT" GA_SUMMARY_OUTPUT="$GA_SUMMARY_OUTPUT"
```

After resolving the verifier handoff, run approval as three separately authenticated operations. `open` and `finalize` are separate `ga-approval.yaml` dispatches/immutable Jobs; the two `submit` commands run in separate human processes and OAuth sessions. The exact in-Job and workforce commands are:

```bash
# ga-approval open dispatch / immutable Job
"$POD_ACCORDCTL" ga approval open \
  --certification-run-id "$ACCORD_CERTIFICATION_RUN_ID" --unit "$GA_CERTIFICATION_UNIT_ID" \
  --source-commit "$ACCORD_SOURCE_COMMIT" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" \
  --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --restore-point-rfc3339 "$ACCORD_RESTORE_POINT_RFC3339" \
  --provider-baseline-digest "$ACCORD_PROVIDER_BASELINE_DIGEST" \
  --expected-release-author-subject "$GA_RELEASE_AUTHOR_SUBJECT" \
  --expected-release-author-role-binding-version "$GA_RELEASE_AUTHOR_ROLE_BINDING_VERSION" \
  --release-artifact-handoff "$GA_RELEASE_ARTIFACT_HANDOFF" \
  --release-artifact-handoff-schema release/artifact-handoff.schema.json \
  --release-manifest "$GA_RELEASE_MANIFEST" --release-manifest-cosign-bundle "$GA_RELEASE_MANIFEST_COSIGN_BUNDLE" \
  --release-manifest-provenance "$GA_RELEASE_MANIFEST_PROVENANCE" --release-manifest-receipt "$GA_RELEASE_MANIFEST_RECEIPT" \
  --release-manifest-schema release/manifest.schema.json --release-builder-policy operations/supply-chain/allowed-builders.yaml \
  --expected-release-manifest-digest "$GA_RELEASE_MANIFEST_DIGEST" \
  --expected-release-manifest-object-version "$GA_RELEASE_MANIFEST_OBJECT_VERSION" \
  --expected-release-manifest-receipt-digest "$GA_RELEASE_MANIFEST_RECEIPT_DIGEST" \
  --verification-report "$GA_REPORT_OUTPUT" --expected-verification-report-digest "$GA_REPORT_DIGEST" \
  --expected-verification-report-object-version "$GA_REPORT_OBJECT_VERSION" \
  --evidence-index "$GA_INDEX_PATH" --expected-evidence-index-digest "$GA_INDEX_DIGEST" \
  --expected-evidence-index-object-version "$GA_INDEX_OBJECT_VERSION" \
  --policy operations/ga/approval-policy.yaml \
  --index-schema contracts/json-schema/ga-evidence-manifest.schema.json \
  --report-schema contracts/json-schema/ga-verification-report.schema.json \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json \
  --signature-policy certification/evidence/signature-policy.yaml \
  --session-output "$GA_APPROVAL_SESSION_ENVELOPE" --session-receipt-output "$GA_APPROVAL_SESSION_RECEIPT" \
  --handoff-output "$GA_APPROVAL_SESSION_HANDOFF" --confirm
```

```bash
# Platform Operations human, on a separately authenticated workstation/process
"$VERIFIED_ACCORDCTL" ga approval submit \
  --session-handoff "$GA_APPROVAL_SESSION_HANDOFF" \
  --session-envelope "$GA_APPROVAL_SESSION_ENVELOPE" --session-receipt "$GA_APPROVAL_SESSION_RECEIPT" \
  --role platform_operations \
  --policy operations/ga/approval-policy.yaml \
  --signature-policy certification/evidence/signature-policy.yaml \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --receipt-schema contracts/json-schema/immutable-evidence-receipt.schema.json \
  --submission-schema contracts/json-schema/ga-approval-submission.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json
```

```bash
# Product Security human, on a separately authenticated workstation/process
"$VERIFIED_ACCORDCTL" ga approval submit \
  --session-handoff "$GA_APPROVAL_SESSION_HANDOFF" \
  --session-envelope "$GA_APPROVAL_SESSION_ENVELOPE" --session-receipt "$GA_APPROVAL_SESSION_RECEIPT" \
  --role product_security \
  --policy operations/ga/approval-policy.yaml \
  --signature-policy certification/evidence/signature-policy.yaml \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --receipt-schema contracts/json-schema/immutable-evidence-receipt.schema.json \
  --submission-schema contracts/json-schema/ga-approval-submission.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json
```

```bash
# ga-approval finalize dispatch / new immutable Job
"$POD_ACCORDCTL" ga approval finalize \
  --session-handoff "$GA_APPROVAL_SESSION_HANDOFF" \
  --session-envelope "$GA_APPROVAL_SESSION_ENVELOPE" --session-receipt "$GA_APPROVAL_SESSION_RECEIPT" \
  --policy operations/ga/approval-policy.yaml \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --receipt-schema contracts/json-schema/immutable-evidence-receipt.schema.json \
  --submission-schema contracts/json-schema/ga-approval-submission.schema.json \
  --approval-schema operations/ga/approval-record.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json \
  --signature-policy certification/evidence/signature-policy.yaml \
  --approval-output "$GA_APPROVAL_RECORD_PATH" \
  --handoff-output "$GA_APPROVAL_RECORD_HANDOFF" --confirm
```

Resolve and verify the approval-record handoff, then launch three distinct `ga-promotion.yaml` workflow dispatches. The first dispatch deliberately supplies a wrong but internally consistent outer Provider-baseline value: workflow input, admitted command digest, Job arguments, and attempt evidence all contain the same wrong value, while the immutable report retains the certified value. Its one immutable Job runs only this command and must fail at report binding before a coordination execution permit or Provider call:

```bash
"$POD_ACCORDCTL" release promote --environment staging --channel ga \
  --dispatch-id "$GA_NEGATIVE_DISPATCH_ID" --promotion-id "$GA_REJECTED_PROMOTION_ID" \
  --certification-run-id "$ACCORD_CERTIFICATION_RUN_ID" --unit "$GA_CERTIFICATION_UNIT_ID" \
  --source-commit "$ACCORD_SOURCE_COMMIT" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" \
  --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --restore-point-rfc3339 "$ACCORD_RESTORE_POINT_RFC3339" \
  --provider-baseline-digest "$GA_MISMATCHED_PROVIDER_BASELINE_DIGEST" \
  --expected-current-release-digest "$GA_EXPECTED_CURRENT_RELEASE_DIGEST" \
  --release-artifact-handoff "$GA_RELEASE_ARTIFACT_HANDOFF" \
  --release-artifact-handoff-schema release/artifact-handoff.schema.json \
  --release-manifest "$GA_RELEASE_MANIFEST" --release-manifest-cosign-bundle "$GA_RELEASE_MANIFEST_COSIGN_BUNDLE" \
  --release-manifest-provenance "$GA_RELEASE_MANIFEST_PROVENANCE" --release-manifest-receipt "$GA_RELEASE_MANIFEST_RECEIPT" \
  --release-manifest-schema release/manifest.schema.json --release-builder-policy operations/supply-chain/allowed-builders.yaml \
  --expected-release-manifest-digest "$GA_RELEASE_MANIFEST_DIGEST" \
  --expected-release-manifest-object-version "$GA_RELEASE_MANIFEST_OBJECT_VERSION" \
  --expected-release-manifest-receipt-digest "$GA_RELEASE_MANIFEST_RECEIPT_DIGEST" \
  --verification-report "$GA_REPORT_OUTPUT" --expected-verification-report-digest "$GA_REPORT_DIGEST" \
  --expected-verification-report-object-version "$GA_REPORT_OBJECT_VERSION" \
  --evidence-index "$GA_INDEX_PATH" --expected-evidence-index-digest "$GA_INDEX_DIGEST" \
  --expected-evidence-index-object-version "$GA_INDEX_OBJECT_VERSION" \
  --approval-record "$GA_APPROVAL_RECORD_PATH" --expected-approval-record-digest "$GA_APPROVAL_RECORD_DIGEST" \
  --expected-approval-record-object-version "$GA_APPROVAL_RECORD_OBJECT_VERSION" \
  --signature-policy certification/evidence/signature-policy.yaml \
  --approval-policy operations/ga/approval-policy.yaml --promotion-policy operations/ga/promotion-policy.yaml \
  --approval-schema operations/ga/approval-record.schema.json \
  --request-schema contracts/json-schema/ga-promotion-request.schema.json \
  --attempt-schema contracts/json-schema/ga-promotion-attempt.schema.json \
  --resolution-schema contracts/json-schema/ga-promotion-resolution.schema.json \
  --receipt-schema contracts/json-schema/ga-promotion-receipt.schema.json \
  --expected-eks-cluster-name "$GA_EKS_CLUSTER_NAME" --expected-eks-cluster-arn "$GA_EKS_CLUSTER_ARN" \
  --kube-api-endpoint "$GA_KUBE_API_ENDPOINT" --kube-token-file /var/run/accord/kube-api/token \
  --kube-ca-file /var/run/accord/kube-api/ca.crt --expected-kube-ca-digest "$GA_KUBE_CA_DIGEST" \
  --expected-kube-token-audience "$GA_KUBE_TOKEN_AUDIENCE" \
  --attempt-output "$GA_REJECTED_ATTEMPT" --attempt-handoff-output "$GA_REJECTED_ATTEMPT_HANDOFF" \
  --resolution-output "$GA_REJECTED_RESOLUTION" --resolution-handoff-output "$GA_REJECTED_RESOLUTION_HANDOFF" \
  --receipt-output "$GA_REJECTED_RECEIPT" --receipt-handoff-output "$GA_REJECTED_RECEIPT_HANDOFF" --confirm
```

Expected process result for this workflow dispatch: exit `1`. The workflow preserves that failure and the authenticated launch receipt; it does not translate it to success. Absence of a receipt is not treated as sufficient proof, and all semantic/no-mutation assertions are deferred to the external verifier below.

The second workflow dispatch has a new dispatch UUID/OIDC token/Job UID/command digest/handoff and performs the valid promotion:

```bash
"$POD_ACCORDCTL" release promote --environment staging --channel ga \
  --dispatch-id "$GA_SUCCESS_DISPATCH_ID" --promotion-id "$GA_PROMOTION_ID" \
  --certification-run-id "$ACCORD_CERTIFICATION_RUN_ID" --unit "$GA_CERTIFICATION_UNIT_ID" \
  --source-commit "$ACCORD_SOURCE_COMMIT" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" \
  --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --restore-point-rfc3339 "$ACCORD_RESTORE_POINT_RFC3339" \
  --provider-baseline-digest "$ACCORD_PROVIDER_BASELINE_DIGEST" \
  --expected-current-release-digest "$GA_EXPECTED_CURRENT_RELEASE_DIGEST" \
  --release-artifact-handoff "$GA_RELEASE_ARTIFACT_HANDOFF" \
  --release-artifact-handoff-schema release/artifact-handoff.schema.json \
  --release-manifest "$GA_RELEASE_MANIFEST" --release-manifest-cosign-bundle "$GA_RELEASE_MANIFEST_COSIGN_BUNDLE" \
  --release-manifest-provenance "$GA_RELEASE_MANIFEST_PROVENANCE" --release-manifest-receipt "$GA_RELEASE_MANIFEST_RECEIPT" \
  --release-manifest-schema release/manifest.schema.json --release-builder-policy operations/supply-chain/allowed-builders.yaml \
  --expected-release-manifest-digest "$GA_RELEASE_MANIFEST_DIGEST" \
  --expected-release-manifest-object-version "$GA_RELEASE_MANIFEST_OBJECT_VERSION" \
  --expected-release-manifest-receipt-digest "$GA_RELEASE_MANIFEST_RECEIPT_DIGEST" \
  --verification-report "$GA_REPORT_OUTPUT" --expected-verification-report-digest "$GA_REPORT_DIGEST" \
  --expected-verification-report-object-version "$GA_REPORT_OBJECT_VERSION" \
  --evidence-index "$GA_INDEX_PATH" --expected-evidence-index-digest "$GA_INDEX_DIGEST" \
  --expected-evidence-index-object-version "$GA_INDEX_OBJECT_VERSION" \
  --approval-record "$GA_APPROVAL_RECORD_PATH" --expected-approval-record-digest "$GA_APPROVAL_RECORD_DIGEST" \
  --expected-approval-record-object-version "$GA_APPROVAL_RECORD_OBJECT_VERSION" \
  --signature-policy certification/evidence/signature-policy.yaml \
  --approval-policy operations/ga/approval-policy.yaml --promotion-policy operations/ga/promotion-policy.yaml \
  --approval-schema operations/ga/approval-record.schema.json \
  --request-schema contracts/json-schema/ga-promotion-request.schema.json \
  --attempt-schema contracts/json-schema/ga-promotion-attempt.schema.json \
  --resolution-schema contracts/json-schema/ga-promotion-resolution.schema.json \
  --receipt-schema contracts/json-schema/ga-promotion-receipt.schema.json \
  --expected-eks-cluster-name "$GA_EKS_CLUSTER_NAME" --expected-eks-cluster-arn "$GA_EKS_CLUSTER_ARN" \
  --kube-api-endpoint "$GA_KUBE_API_ENDPOINT" --kube-token-file /var/run/accord/kube-api/token \
  --kube-ca-file /var/run/accord/kube-api/ca.crt --expected-kube-ca-digest "$GA_KUBE_CA_DIGEST" \
  --expected-kube-token-audience "$GA_KUBE_TOKEN_AUDIENCE" \
  --attempt-output "$GA_PROMOTION_ATTEMPT" --attempt-handoff-output "$GA_PROMOTION_ATTEMPT_HANDOFF" \
  --resolution-output "$GA_PROMOTION_RESOLUTION" --resolution-handoff-output "$GA_PROMOTION_RESOLUTION_HANDOFF" \
  --receipt-output "$GA_PROMOTION_RECEIPT" --receipt-handoff-output "$GA_PROMOTION_RECEIPT_HANDOFF" --confirm
```

The third workflow dispatch has another new dispatch UUID/OIDC token/Job UID/command digest/handoff but repeats the exact same promotion ID and canonical request bindings. It downloads the prior retained receipt by handoff and may only return it:

```bash
"$POD_ACCORDCTL" release promote --environment staging --channel ga \
  --dispatch-id "$GA_RETRY_DISPATCH_ID" --promotion-id "$GA_PROMOTION_ID" \
  --certification-run-id "$ACCORD_CERTIFICATION_RUN_ID" --unit "$GA_CERTIFICATION_UNIT_ID" \
  --source-commit "$ACCORD_SOURCE_COMMIT" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" \
  --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --restore-point-rfc3339 "$ACCORD_RESTORE_POINT_RFC3339" \
  --provider-baseline-digest "$ACCORD_PROVIDER_BASELINE_DIGEST" \
  --expected-current-release-digest "$GA_EXPECTED_CURRENT_RELEASE_DIGEST" \
  --release-artifact-handoff "$GA_RELEASE_ARTIFACT_HANDOFF" \
  --release-artifact-handoff-schema release/artifact-handoff.schema.json \
  --release-manifest "$GA_RELEASE_MANIFEST" --release-manifest-cosign-bundle "$GA_RELEASE_MANIFEST_COSIGN_BUNDLE" \
  --release-manifest-provenance "$GA_RELEASE_MANIFEST_PROVENANCE" --release-manifest-receipt "$GA_RELEASE_MANIFEST_RECEIPT" \
  --release-manifest-schema release/manifest.schema.json --release-builder-policy operations/supply-chain/allowed-builders.yaml \
  --expected-release-manifest-digest "$GA_RELEASE_MANIFEST_DIGEST" \
  --expected-release-manifest-object-version "$GA_RELEASE_MANIFEST_OBJECT_VERSION" \
  --expected-release-manifest-receipt-digest "$GA_RELEASE_MANIFEST_RECEIPT_DIGEST" \
  --verification-report "$GA_REPORT_OUTPUT" --expected-verification-report-digest "$GA_REPORT_DIGEST" \
  --expected-verification-report-object-version "$GA_REPORT_OBJECT_VERSION" \
  --evidence-index "$GA_INDEX_PATH" --expected-evidence-index-digest "$GA_INDEX_DIGEST" \
  --expected-evidence-index-object-version "$GA_INDEX_OBJECT_VERSION" \
  --approval-record "$GA_APPROVAL_RECORD_PATH" --expected-approval-record-digest "$GA_APPROVAL_RECORD_DIGEST" \
  --expected-approval-record-object-version "$GA_APPROVAL_RECORD_OBJECT_VERSION" \
  --signature-policy certification/evidence/signature-policy.yaml \
  --approval-policy operations/ga/approval-policy.yaml --promotion-policy operations/ga/promotion-policy.yaml \
  --approval-schema operations/ga/approval-record.schema.json \
  --request-schema contracts/json-schema/ga-promotion-request.schema.json \
  --attempt-schema contracts/json-schema/ga-promotion-attempt.schema.json \
  --resolution-schema contracts/json-schema/ga-promotion-resolution.schema.json \
  --receipt-schema contracts/json-schema/ga-promotion-receipt.schema.json \
  --expected-eks-cluster-name "$GA_EKS_CLUSTER_NAME" --expected-eks-cluster-arn "$GA_EKS_CLUSTER_ARN" \
  --kube-api-endpoint "$GA_KUBE_API_ENDPOINT" --kube-token-file /var/run/accord/kube-api/token \
  --kube-ca-file /var/run/accord/kube-api/ca.crt --expected-kube-ca-digest "$GA_KUBE_CA_DIGEST" \
  --expected-kube-token-audience "$GA_KUBE_TOKEN_AUDIENCE" \
  --attempt-output "$GA_RETRY_ATTEMPT" --attempt-handoff-output "$GA_RETRY_ATTEMPT_HANDOFF" \
  --resolution-output "$GA_RETRY_RESOLUTION" --resolution-handoff-output "$GA_RETRY_RESOLUTION_HANDOFF" \
  --receipt-output "$GA_RETRY_RECEIPT" --receipt-handoff-output "$GA_RETRY_RECEIPT_HANDOFF" --confirm
```

After all three promoter workflows have reached terminal status, dispatch `ga-promotion.yaml operation=verify-rehearsal`. This is a fourth, read-only external verification dispatch in the fixed `ga-verifier` environment; it is not part of any promoter Pod and runs exactly this one command:

```bash
"$POD_ACCORDCTL" ga verify-promotion-rehearsal \
  --unit "$GA_CERTIFICATION_UNIT_ID" --rejected-promotion-id "$GA_REJECTED_PROMOTION_ID" --promotion-id "$GA_PROMOTION_ID" \
  --negative-dispatch-id "$GA_NEGATIVE_DISPATCH_ID" --success-dispatch-id "$GA_SUCCESS_DISPATCH_ID" --retry-dispatch-id "$GA_RETRY_DISPATCH_ID" \
  --negative-launch-receipt "$GA_NEGATIVE_LAUNCH_RECEIPT" --negative-attempt-handoff "$GA_REJECTED_ATTEMPT_HANDOFF" \
  --success-launch-receipt "$GA_SUCCESS_LAUNCH_RECEIPT" --success-attempt-handoff "$GA_PROMOTION_ATTEMPT_HANDOFF" \
  --success-resolution-handoff "$GA_PROMOTION_RESOLUTION_HANDOFF" --success-receipt-handoff "$GA_PROMOTION_RECEIPT_HANDOFF" \
  --retry-launch-receipt "$GA_RETRY_LAUNCH_RECEIPT" --retry-attempt-handoff "$GA_RETRY_ATTEMPT_HANDOFF" \
  --retry-receipt-handoff "$GA_RETRY_RECEIPT_HANDOFF" \
  --provider-audit-cursor-before "$GA_PROVIDER_AUDIT_CURSOR_BEFORE" --provider-audit-cursor-after "$GA_PROVIDER_AUDIT_CURSOR_AFTER" \
  --kubernetes-audit-cursor-before "$GA_KUBERNETES_AUDIT_CURSOR_BEFORE" --kubernetes-audit-cursor-after "$GA_KUBERNETES_AUDIT_CURSOR_AFTER" \
  --signature-policy certification/evidence/signature-policy.yaml \
  --approval-policy operations/ga/approval-policy.yaml --promotion-policy operations/ga/promotion-policy.yaml \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json \
  --attempt-schema contracts/json-schema/ga-promotion-attempt.schema.json \
  --resolution-schema contracts/json-schema/ga-promotion-resolution.schema.json \
  --receipt-schema contracts/json-schema/ga-promotion-receipt.schema.json \
  --summary-output "$GA_PROMOTION_REHEARSAL_VERIFICATION"
```

The verifier downloads exact handoff versions through its authenticated read-only evidence identity and validates the referenced DSSE envelopes and immutable receipts before decoding. It then strongly reads the two coordination partitions and checks the Provider and Kubernetes/Argo audit streams between independently retained before/after cursors. This command, not file absence or a promoter-side shell condition, proves the negative `exit=1`/`PRECONDITION_REJECTED`/zero-permit/zero-call state, successful `EXECUTION_AUTHORIZED`/one-mutation/`APPLIED` state, and retry `RETAINED_RECEIPT_RETURNED`/byte-identical-receipt/cumulative-one-mutation state.

The protected rehearsal also uses `operations/chaos/artifact-promotion-uncertain.yaml` on dedicated rehearsal units. One cut transitions to `EXECUTING` and terminates before the Provider call, so authoritative absence of the request ID/annotation/history/audit mutation must resolve `NO_EFFECT`. A second drops the response after the atomic Argo patch and before coordination update, so matching UID/revision/history/annotation/audit facts must resolve `APPLIED`. A third drops the response and then uses a separately authorized, signed chaos mutation to replace the target revision/annotation before reconciliation; those conflicting authoritative facts must resolve `DIVERGED`. Each case has a separate `OUTCOME_UNKNOWN` operation and `ga-promotion.yaml operation=reconcile` dispatch using the exact `release promote reconcile-ga` surface above. Ambiguous observation remains fenced and exits `3`. `NO_EFFECT` requires a new explicit generation, `APPLIED` yields the original receipt without a second patch, and `DIVERGED` blocks promotion until the Task 13 protected rollback/remediation restores the signed baseline. Every cut, intentional conflict, reconciliation, rollback, and baseline restoration has its own immutable handoff and audit receipt; a plain crash after a successful matching patch is never classified as `DIVERGED`.

Expected: each GitHub job independently proves the clean final candidate commit and published binary/image digest, Cosign identities, SLSA provenance, and release-bundle closure before launching its immutable Kubernetes Job. STS/admission/KMS/object-policy tests prove that orchestration roles cannot sign/write, IRSA roles cannot launch/cross lanes, approval cannot mutate Argo, and each promoter can touch only its unit. The same six protected values appear in every admitted command and signed object; each downstream consumer rejects a mismatch. Approval session/record, collection, verification, and each unit's attempt/resolution/receipt use the declared distinct identities, purposes, keys, prefixes, and partitions and are retained/read back for at least 400 days. The negative dispatch has a signed `PRECONDITION_REJECTED` attempt and zero Provider calls; the successful dispatch has exactly one authorized attempt, one matching Argo audit mutation, one `APPLIED` resolution, and one signed receipt; the retry has `RETAINED_RECEIPT_RETURNED`, byte-identical receipt bytes, and cumulative Provider mutation count exactly one. All 100-way concurrency and crash-cut evidence agrees with coordination state. Any failed artifact or isolation drift exits nonzero before promotion; no identity, database role, approval, force flag, or administrator can bypass a failed gate or fence.

## Final Operations Verification

Run against the exact signed release candidate and first certified AWS Japan reference profile:

```bash
./gradlew clean test
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
./gradlew :apps:webhook-edge:test :apps:agent-pack-gateway:test :apps:attachment-scanner:test :security-services:signing-service:test :security-services:requirement-publisher:test :security-services:merge-controller:test :security-services:break-glass-broker:test :cmd:accordctl:test :libs:java:observability:test
uv run --project apps/agent-runtime pytest apps/agent-runtime/tests -q
corepack pnpm@10.12.4 --filter @accord/web test
pwsh -NoProfile -File scripts/ci/verify-opentofu.Tests.ps1
pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json
tofu -chdir=infra/opentofu fmt -check -recursive
tofu -chdir=infra/opentofu test
helm dependency build infra/helm/accord --skip-refresh
git diff --exit-code -- infra/helm/accord/Chart.lock
helm lint infra/helm/agent-pack-gateway
helm lint infra/helm/accord
helm unittest infra/helm/accord -f 'tests/ga-control-lanes.yaml'
conftest verify -p infra/policy/ci infra/policy/ci/ga-separation_test.yaml
conftest verify -p infra/policy/supply-chain infra/policy/supply-chain/admission_test.yaml
conftest test .github/workflows/ga-certification.yaml .github/workflows/ga-approval.yaml .github/workflows/ga-promotion.yaml .github/workflows/contract-terms-package-publish.yaml .github/workflows/contract-acceptance-attest.yaml operations/ga/jobs -p infra/policy/ci
conftest test infra/helm infra/opentofu infra/argocd -p infra/policy
promtool check rules operations/alerts/*.yaml
```

The certification phase of canonical production verification runs only through the three protected workflow jobs below. `ACCORD_SOURCE_COMMIT` must equal the Master Plan final immutable candidate commit, not the earlier Task 14 implementation commit. The workflow supplies and validates UUID `ACCORD_CERTIFICATION_RUN_ID`, that source commit, `ACCORD_RELEASE_BUNDLE_DIGEST`, `ACCORD_STAGING_ENVIRONMENT_ID`, explicit RFC 3339 `ACCORD_RESTORE_POINT_RFC3339`, and `ACCORD_PROVIDER_BASELINE_DIGEST`; each downstream job additionally receives only the exact predecessor handoff path/digest/object version shown below. No engineer exports credentials or executes the three blocks in one session.

```bash
# protected environment: staging-operations
make ga-rehearse GA_ENVIRONMENT=staging GA_CERTIFICATION_RUN_ID="$ACCORD_CERTIFICATION_RUN_ID" GA_RELEASE="$ACCORD_SOURCE_COMMIT" GA_RELEASE_BUNDLE="$ACCORD_RELEASE_BUNDLE_DIGEST" GA_ENVIRONMENT_ID="$ACCORD_STAGING_ENVIRONMENT_ID" GA_RESTORE_POINT_RFC3339="$ACCORD_RESTORE_POINT_RFC3339" GA_PROVIDER_BASELINE_DIGEST="$ACCORD_PROVIDER_BASELINE_DIGEST" GA_REHEARSAL_HANDOFF_OUTPUT="$GA_REHEARSAL_HANDOFF_OUTPUT"
```

```bash
# protected environment: ga-collector; immutable rehearsal receipts already resolved
make ga-collect GA_ENVIRONMENT=staging GA_CERTIFICATION_RUN_ID="$ACCORD_CERTIFICATION_RUN_ID" GA_RELEASE="$ACCORD_SOURCE_COMMIT" GA_RELEASE_BUNDLE="$ACCORD_RELEASE_BUNDLE_DIGEST" GA_ENVIRONMENT_ID="$ACCORD_STAGING_ENVIRONMENT_ID" GA_RESTORE_POINT_RFC3339="$ACCORD_RESTORE_POINT_RFC3339" GA_PROVIDER_BASELINE_DIGEST="$ACCORD_PROVIDER_BASELINE_DIGEST" GA_REHEARSAL_RECEIPT_SET_PATH="$GA_REHEARSAL_RECEIPT_SET_PATH" GA_REHEARSAL_RECEIPT_SET_DIGEST="$GA_REHEARSAL_RECEIPT_SET_DIGEST" GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION="$GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION" GA_INDEX_PATH="$GA_INDEX_PATH" GA_INDEX_HANDOFF_OUTPUT="$GA_INDEX_HANDOFF_OUTPUT"
```

```bash
# protected environment: ga-verifier; immutable evidence index already resolved
make ga-verify GA_ENVIRONMENT=staging GA_CERTIFICATION_RUN_ID="$ACCORD_CERTIFICATION_RUN_ID" GA_RELEASE="$ACCORD_SOURCE_COMMIT" GA_RELEASE_BUNDLE="$ACCORD_RELEASE_BUNDLE_DIGEST" GA_ENVIRONMENT_ID="$ACCORD_STAGING_ENVIRONMENT_ID" GA_RESTORE_POINT_RFC3339="$ACCORD_RESTORE_POINT_RFC3339" GA_PROVIDER_BASELINE_DIGEST="$ACCORD_PROVIDER_BASELINE_DIGEST" GA_INDEX_PATH="$GA_INDEX_PATH" GA_INDEX_DIGEST="$GA_INDEX_DIGEST" GA_INDEX_OBJECT_VERSION="$GA_INDEX_OBJECT_VERSION" GA_REPORT_OUTPUT="$GA_REPORT_OUTPUT" GA_SUMMARY_OUTPUT="$GA_SUMMARY_OUTPUT"
```

After the verifier handoff, canonical verification executes the full Step 5 approval and promotion blocks as separate operations: approval `open`; one Platform Operations and one Product Security offline-package `submit`; approval `finalize`; the wrong-but-internally-consistent promotion dispatch; the valid dispatch; the same-request retry dispatch; the external read-only `verify-promotion-rehearsal` dispatch; and the three protected `reconcile-ga` crash-cut dispatches. The external verifier resolves every unsigned session/attempt/resolution/receipt handoff to exact envelope and receipt bytes and validates digest, object version, signer purpose, Job UID/image/command digest, result class/exit code, unit map, and 400-day retention. It performs a read-only repeatable-read transaction against the single-writer PostgreSQL coordination database and checks immutable Provider plus Kubernetes/Argo audit cursors. The negative case has exit `1`, no execution fields, and zero mutations; valid plus retry have a cumulative one mutation and byte-identical final receipt; `NO_EFFECT` has zero effect and no automatic retry; recovered `APPLIED` has one effect and the original receipt; and `DIVERGED` remains fenced until the separately evidenced Task 13 rollback restores baseline.

Expected: every positive command exits `0`, each deliberate rejection exits `1`, and each unresolved uncertainty exits `3`; the source tree is clean and executed `accordctl` is the exact Cosign/SLSA-verified binary in the final candidate release bundle. The OpenTofu harness proves all negative cases and reports four initialized roots, 18 covered modules, identical signed certification-unit maps in Tokyo/Osaka, exact saved-plan apply receipts, and unchanged locks. The live Helm/Argo inventory includes exactly one Gateway, one fixed approval lane, and one isolated promoter resource set per unit. Certification, approval-session, approval-record, every unit's attempt/resolution/receipt, package publisher, and acceptance attestor identities, roles, purposes, keys, prefixes, partitions, and mutation grants are pairwise separated as declared. The global tenant catalog covers V090/V091; browser/API, ActionRequest, Pack, RPO/RTO, merge provenance, source boundary, Task 9 convergence, model/value, and contractual-terms gates pass with all zero-tolerance counters at zero. Missing, stale, expired, revoked, mistranslated, misbound, wrongly signed/unapproved/unretained, or unaccepted evidence deterministically prevents GA without administrator override.
