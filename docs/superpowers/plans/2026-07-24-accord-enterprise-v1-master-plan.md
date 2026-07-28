# Accord Enterprise V1 Master Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the complete production Accord Enterprise V1, from tenant-safe requirement intake through source-free Agent analysis, controlled Git delivery, exact-candidate acceptance, recovery, and evidence-derived GA certification.

**Architecture:** A Java 21 Spring Modulith control plane owns business truth in PostgreSQL and runs as separately permissioned API and worker processes; Temporal only coordinates retries and waits. Independent Java 21 services isolate webhook ingress, signed Agent Pack download, signing, Provider connection, credential resolution, strict merging, attachment scanning, and emergency operations without sharing identities or privileged credentials. Signed Requirement Baselines, Development Packages, Project Context, and Patches remain in Accord/OSS; branch release creates only zero-diff refs through typed Provider APIs, while developers alone clone, edit, commit, and push customer source. A Java/Picocli `accordctl` ships as a jlink runtime image, a Python/Pydantic Agent Runtime performs typed source-free model work, and a React/Vite SPA renders server-owned state and actions. All boundaries are versioned through JSON Schema, OpenAPI, protobuf/gRPC with mTLS, RFC 8785 JCS, and DSSE, and all deployment artifacts are Kubernetes/Helm/OpenTofu/Argo CD resources.

**Tech Stack:** Java 21, Gradle 8.14.3 Groovy DSL, Spring Boot 3.5.3, Spring Modulith 1.4.1, Picocli 4.7.7, jlink, jOOQ 3.19.24, Flyway 11.8.2, PostgreSQL 17.5, Temporal, Python 3.12, Pydantic v2, React 19.1, TypeScript 5.8, Vite 7, JSON Schema 2020-12, OpenAPI 3.1, protobuf/gRPC+mTLS, RFC 8785 JCS, DSSE, OSS/S3-compatible object storage, Kubernetes, Helm, OpenTofu, Argo CD, OpenTelemetry, Playwright, Testcontainers, pytest, JUnit 5, AssertJ, jqwik, and Cosign.

---

## Specification Binding And Delivery Rule

This plan implements `requirements-agent-platform-design.md` at normalized UTF-8/LF SHA-256 `0755A08228254311588EE0B867AFEBED6CD49E26B0FCCB790247BFDA31AB2C77`. The approved runtime binding is `docs/superpowers/specs/2026-07-25-accord-java-python-runtime-design.md` at normalized UTF-8/LF SHA-256 `46DE7309CB28F8E59B3FDAB4D6FA664AE4D932FC7F517741681CC25C3B84E4E1`. Recompute both digests with the exact normalization function below before every milestone review; raw platform-native line endings are not part of the identity. A changed digest requires an explicit specification or runtime-design review and updated coverage matrix; an implementer must never silently implement against a different document.

M0 through M6 are an internal dependency sequence, not independently marketable products. A milestone can be demonstrated only after its exit gate passes, and the product can be called production V1 only after every M6 certification gate passes. No later gate can waive an earlier security, product, or evidence failure.

## Milestone Runner Isolation Rule

Master-level tests are deliberately outside every component plan's default verification surface. JVM gates live under `tests/integration/src/milestoneTest/**` and run only through `:tests:integration:milestoneTest`; ordinary `test`, `check`, and component-plan filters neither compile nor execute that source set. Browser gates live under `tests/milestones/playwright/**` and run only with `tests/milestones/playwright.config.ts`; neither `apps/web/playwright.config.ts` nor `tests/system/playwright.config.ts` discovers them. PowerShell milestone exercises remain explicit scripts and are never called by a component's default test task.

This isolation is mandatory, not a convenience. A milestone test may reference behavior owned by several later modules, while each component plan must remain executable and green on its own. Every red command below therefore targets an already-created runner and fails on a missing public contract or cross-component behavior, never because a Gradle project, package manager, Playwright installation, or test configuration is absent. The master plan invokes every isolated runner again at M6 and at the final release gate.

Browser milestone commands require `ACCORD_SYSTEM_BASE_URL` to name the current commit's ephemeral, production-like front door that serves the SPA and `/api/**` through the same-origin ingress with deterministic test identities. Provisioning that environment is the same prerequisite used by the shared `tests/system` suite; a Vite-only preview, browser MSW, or a stale shared environment is not valid milestone evidence. Record the release commit, environment ID, fixture-set digest, and URL digest in each gate result.

M5 recovery and M6 GA certification additionally require `ACCORD_STAGING_ENVIRONMENT_ID` to be the immutable staging-environment identifier resolved by the control plane, `ACCORD_RESTORE_POINT_RFC3339` to be the explicit protected RFC 3339 instant used by the PITR drill, and `ACCORD_PROVIDER_BASELINE_DIGEST` to be the signed pre-drill provider-sandbox baseline in `sha256:<64 lowercase hex>` form. A mutable environment name, current-time default, or unsigned Provider snapshot is not a valid recovery input.

Set `ACCORD_COMPAT_BASE_TAG` to the signed annotated tag containing the previously accepted protobuf baseline and `ACCORD_COMPAT_BASE_REF` to the full 40-character lowercase commit SHA resolved from that tag. For the first production release, use `accord-contract-baseline-v1-m0` and its M0 gate commit. `tests/architecture/verify-protobuf-compatibility.ps1` verifies the tag signature, exact tag-to-commit binding, ancestry, pinned Buf `1.55.1`, and inequality with `HEAD` before invoking the supported local Git input `.git#ref=<full-sha>`; comparing descriptors to the branch currently being built is a vacuous gate and is forbidden.

## Canonical Repository Topology

```text
apps/
  web/                                  # React 19.1 + TypeScript 5.8 + Vite 7
  control-plane/
    api/                                # Java HTTP/SSE entry point
    worker/                             # Java outbox, reconciliation, Temporal workers
    modules/                            # bounded Spring Modulith business modules
  webhook-edge/                        # isolated Java image; distinct webhook and auth-callback workload profiles
  agent-pack-gateway/                  # isolated Java, one-use Pack capability consumer
  agent-runtime/                       # Python 3.12/Pydantic, source-free Agent jobs
  attachment-scanner/                  # isolated Java, quarantine-only scanner identity
security-services/
  signing-service/                     # isolated Java, purpose-separated DSSE signing
  provider-connector/                  # isolated Java, capability-gated Provider operations
  credential-broker/                   # isolated Java, external-secret resolution only
  merge-controller/                    # isolated Java, strict-mode one-shot merger
  break-glass-broker/                  # isolated Java, emergency-only credential lane
contracts/
  json-schema/ openapi/ protobuf/ events/ dsse-payloads/ golden-fixtures/
agent-pack/                             # signed customer-installed pack
cmd/accordctl/                          # Java/Picocli CLI with jlink runtime images
libs/java/                              # bounded Java libraries only
database/control-plane/migrations/
database/webhook-edge/migrations/
database/signing-service/migrations/
tests/bootstrap/ contracts/ integration/ architecture/ milestones/ e2e/
infra/local/ helm/ opentofu/ argocd/ policy/
docs/architecture/ operations/ security/ user-guides/
```

The root Gradle build owns all Java processes, test runners, and `accordctl` through Groovy DSL build files and Java 21 toolchains. Independent edge and security subprojects may import generated protobuf/JSON contract packages plus bounded `libs/java/**`; they must not import `apps/control-plane/modules/**` or share workload credentials. Python and web workspaces consume generated contracts and must not duplicate authorization, state-machine, scoring, or completion rules.

## Authority, Trust, And Deployment Boundaries

| Boundary | May read or mutate | Must never do |
|---|---|---|
| `control-api` | authenticated commands/queries, PostgreSQL business transactions | trust browser tenant headers, hold Git-content or signing credentials |
| `control-worker` | outbox/inbox, reconciliation, typed Temporal activities | decide business truth from workflow history or retry an uncertain effect blindly |
| `webhook-edge` | verify raw provider delivery and persist normalized metadata signal | authorize state transitions or read repository blobs/diffs |
| `provider-auth-callback-edge` | consume one-time Provider OAuth/App callbacks, encrypt the assertion and forward only an opaque receipt | read webhook/domain rows, hold Provider credentials, call repository APIs, or reuse a callback |
| `agent-pack-gateway` | authenticate an actor-bound one-use capability, fetch one allowlisted fixed OCI digest, verify it fully into encrypted cache, atomically consume, and stream that exact object | accept tags/arbitrary registries, expose a token or registry credential, access customer Git/source, or stream any unverified byte |
| Python Agent Runtime | authorized Requirement, Context, and attachment projections in typed jobs | read customer repositories, approve, publish, merge, or directly update aggregates |
| Signing Service | sign allowed DSSE domains after workload and payload-policy checks | publish Git content, merge, or share keys across purposes |
| Provider Connector | execute one typed, capability-gated repository/ref/ChangeRequest/check/protection/merge operation for an exact installation and immutable repository | expose a generic Provider proxy, read blobs/source/diffs, accept caller URLs, or reuse one credential profile for another operation class |
| Credential Broker | resolve an installation-scoped external-secret reference for one authenticated Connector audience and short execution window | return credentials to the control plane/browser, persist access tokens, broaden scopes, or authorize a Provider operation |
| Merge Controller | merge one already-existing, fully verified current-v2 subject onto a protected strict ref: WorkItem PR, accepted delivery Candidate, or emergency change | create/edit source or metadata, resolve conflicts, reuse authorization, change subject type/digest, or merge when Provider facts are uncertain |
| Attachment Scanner | read quarantined object versions and emit signed verdict metadata | expose unscanned content or access unrelated tenant objects |
| React web | render OpenAPI projections and submit allowed, versioned commands | infer RBAC/state transitions or cache data across tenant partitions |
| Customer Codex/CI | read source, create code/patches, test, build, attest | impersonate platform confirmation, publication, acceptance, or audit authority |

PostgreSQL 17.5 is the only business database technology and is authoritative for control-plane facts, durable rate limits, replay claims, leases, fencing tokens, and certification coordination. Every mutation is tenant-scoped, compare-and-swap versioned, persistently idempotent, and transactionally emits domain/audit/outbox records. Process-local bounded caches are disposable and never decide authorization or workflow state. Provider webhooks are hints; reconciliation reads current metadata facts. Temporal histories contain identifiers and progress, not approvals or the only copy of state.

AWS Tokyo (`ap-northeast-1`) with Osaka (`ap-northeast-3`) warm disaster recovery is the first certified reference deployment. Region names, AWS service identifiers, and reference capacity belong only in the matching `infra/opentofu/environments/aws-*` and certification-unit data; domain contracts and application code remain cloud- and region-neutral.

## Versioned Boundary Contract

The following map is normative. Compatibility tests reject a boundary that lacks its declared tenant, correlation, version, digest, replay, and authentication fields.

| Boundary | Canonical contract | Producer -> consumer | Required semantics |
|---|---|---|---|
| Browser/API | `contracts/openapi/accord-control-api.yaml` | React -> Java API | tenant/actor derived only from browser-session or OIDC `VerifiedRequestIdentity`; `Idempotency-Key`; expected version; RFC 7807; SSE sequence resume |
| Domain events | `contracts/events/*.json` + `domain-event.schema.json` | Java transaction -> Java workers/projections | tenant/scope, aggregate sequence, causation/correlation, schema version, transactional outbox |
| Agent jobs | `contracts/json-schema/agent-job.schema.json` | Java worker -> Python runtime | allowlisted projection references/digests, no source text/locator, run id, deadline, model bundle |
| Agent results | `contracts/json-schema/agent-result.schema.json` | Python runtime -> Java inbox | job digest, structured claims/evidence/unknowns, model/prompt IDs, idempotent result id |
| Security calls | `contracts/protobuf/accord/{signing,connector,credential,merge}/v1/*.proto` | Java worker -> isolated Java security services | mTLS workload identity, tenant/installation/repository/ref, capability snapshot, expected Provider fact, nonce/expiry, request digest |
| Signed payloads | `contracts/dsse-payloads/*.schema.json` | authorized signer -> all verifiers | domain-separated payload type, JCS content digest, purpose key, trust time, no self-referential digest |
| Customer CLI/CI | JSON Schema + DSSE golden fixtures | Java `accordctl`/CI -> Java API | repository immutable ID, exact heads/trees, patch/evidence digest, signed identity and replay defense |
| Agent Pack download | closed OpenAPI capability response + signed release metadata | Java API -> isolated Java Gateway -> customer browser/CI | exact HTTPS origin, actor/session-or-workload audience, fixed OCI/release digests, distribution epoch, one use, at most 60 seconds, no redirect/token persistence |
| Provider signals | `contracts/protobuf/accord/webhook/v1/webhook.proto` | isolated Java edge -> Java inbox | delivery ID/digest, immutable repository ID, metadata only; never authorization |

All UUIDs, enums, digest formats, timestamps, scope rules, and canonicalization vectors have one definition under `contracts/**`. Generated Java, Python, and TypeScript types are build outputs and are checked for clean regeneration. Independently built Java consumers validate the same golden fixtures; sharing a language never permits sharing a privileged domain module. A locally convenient second schema is a release-blocking defect.

## Component Plan Index

Execute each component plan task-by-task; this master adds ordering and integration gates and does not replace their TDD steps.

| Plan | Primary ownership | Milestones |
|---|---|---|
| `docs/superpowers/plans/2026-07-24-accord-platform-foundation-plan.md` | monorepo, contracts, reliability kernel, local platform | M0-M6 |
| `docs/superpowers/plans/2026-07-24-accord-identity-tenancy-audit-plan.md` | identity, tenant isolation, RBAC/delegation, isolated Java signing, append-only audit | M0-M6 |
| `docs/superpowers/plans/2026-07-24-accord-requirement-workflow-plan.md` | Requirement Graph, drafts, attachments, proposals, ActionRequests, confirmation | M1-M3 |
| `docs/superpowers/plans/2026-07-24-accord-web-experience-plan.md` | complete role-oriented React web journeys and accessibility | M1-M6 |
| `docs/superpowers/plans/2026-07-24-accord-agent-context-assessment-plan.md` | Agent Pack, Project Context, Agent Runtime, assessment | M2-M6 |
| `docs/superpowers/plans/2026-07-24-accord-git-delivery-control-plan.md` | batch, WorkItem, Provider adapters, Development Packages, branch release, merge modes, recovery | M3-M5 |
| `docs/superpowers/plans/2026-07-24-accord-candidate-acceptance-plan.md` | Candidate, AcceptanceRun, CorrectionRun, promotion, completion | M4-M6 |
| `docs/superpowers/plans/2026-07-24-accord-production-operations-ga-plan.md` | observability, resilience, supply chain, release, certification | M0-M6 |

When two plans touch a contract, migration, or module, the earlier milestone owns the primitive and the later plan extends it without renaming fields or creating a parallel type. Resolve overlapping migration numbers before implementation; never rewrite an applied migration.

The cumulative HTTP surface is one OpenAPI document and one generated TypeScript package. At each milestone, architecture tests compare the sorted OpenAPI owner set, Spring controller annotations, application operation registry, generated exports, and the corresponding Web adapter registry. The exact V1 domain owner counts are normative:

| Owner | Exact operations | First complete milestone |
|---|---:|---:|
| `identity-public` | 72 | M0 |
| `requirement-workflow` | 47 | M1 |
| `agent-context-assessment` | 53 | M2 |
| `provider-onboarding` | 12 | M3 |
| `delivery-control` | 24 | M3/M5 |
| `candidate-acceptance` | 26 | M4 |

Foundation operations remain protected by their own baseline test and every later cumulative merge must retain them. An alias, handwritten browser URL/DTO, operation present in only one layer, deleted earlier owner key, or owner-count drift is a release-blocking contract failure rather than acceptable generated-client churn.

## Milestone Dependency Sequence And Exit Gates

| Milestone | Depends on | User-verifiable exit | Automated evidence |
|---|---|---|---|
| M0 Contract and trust foundation | none | Admin creates a tenant/project, maps a human and Git identity, sees an authorized action plus immutable audit proof, and a cross-tenant attempt is denied without disclosure | JCS/DSSE independent-consumer vectors, protobuf/OpenAPI compatibility, RLS/authorization negative matrix, audit-chain verification, outbox replay |
| M1 Business requirement loop | M0 | Business user creates structured text/material intake, sees graph/canvas/list and development projection, resolves an ActionRequest, compares revisions, and confirms only the exact current revision | attachment quarantine/access tests, graph/revision properties, API/SSE and Playwright journey, p95 visibility probe |
| M2 Project context and assessment | M1 | Developer installs a signed pack, submits CI-attested source-free Context, reviews impact, confirms policy/scores, handles anomaly override, and reaches or is truthfully blocked from developable | pack verification, no-source canary, patch lineage, B/H/A/D property tests, model quality report |
| M3 Alignment, package and branch release | M2 | Both sides complete ordered confirmation, freeze one cross-Provider DeliveryBatch, retrieve signed per-repository Development Packages, release complete zero-diff branch refs, and complete the standard-mode path with bypass detection | proposal/receipt invalidation, project-scoped Ready Pool CAS, package signature, per-repository release proofs, Provider metadata reconciliation, standard-mode recovery |
| M4 Development and exact acceptance | M3 | Assigned developers use `accordctl`/Codex with the signed packages and native Git, CI proves completion/patch, business accepts each exact repository Candidate, a failure takes the correct correction path, and exact artifact digests are aggregated into CompletionSet | WorkItem gates, candidate immutability, acceptance continuity, artifact digest match, atomic completion formula |
| M5 Strict delivery and recovery | M4 | Every current-v2 protected-ref merge subject in a strict project is merged at most once and only by Merge Controller; operators demonstrate WorkItem/Candidate/emergency paths plus drift suspension, convergence, abort eligibility, and break-glass degradation/restoration | subject-bound controller authorization/replay tests, protection proof, uncertainty reconciliation, hotfix/abort/break-glass fault matrix |
| M6 Production GA certification | M5 | Each certification unit shows its support status and a signed evidence bundle; all roles complete the end-to-end journey; SLO, recovery, security, model, value, documentation, and contractual gates are green | production rehearsal, 21-section traceability, zero-tolerance counters, regional drill, model/value reports, unit-scoped `contractual-terms` evidence with `contractual_terms_pass=true`, derived GA status |

## Production, Security, And GA Gates

- **Product:** all included V1 journeys work without engineering assistance; requirement-caused rework follows the preregistered value policy and every quality/adoption/data guardrail passes.
- **Authorization:** negative RBAC/delegation matrices pass for every role, tenant, side, repository, WorkItem, attachment, and machine identity; unauthorized publish/merge/accept is zero.
- **Data boundary:** the platform never receives customer source bodies; cross-tenant disclosure and source-canary matches in PostgreSQL, object storage, queues, logs, metrics, traces, and model requests are zero.
- **Integrity:** exact revision/context/head/tree/artifact digests close the chain; signature domain, replay, expiry, key revocation, CAS, idempotency, inbox/outbox, and audit-chain tests pass.
- **Delivery assurance:** standard bypasses are detected within 15 minutes; strict normal merges are 100% Merge Controller; provider uncertainty fails closed and reconciles from current facts.
- **Reliability:** browser/API p95 is at most 2 seconds; ActionRequest visibility p95 is at most 3 seconds; synchronous multi-AZ confirmed-transaction RPO is 0; regional RPO is at most 5 minutes and core RTO at most 4 hours.
- **Supply chain:** locked dependencies, SBOMs, vulnerability/license/malware reports, SLSA provenance, Cosign signatures, image admission, and signed Agent Pack/release bundle verification pass.
- **Model:** every certified language/framework/analyzer unit meets section 19.4 sample, confidence, abstention, traceability, and high-risk blocker thresholds independently.
- **Operations:** capacity/cost envelope, on-call, alerts, runbooks, backup/PITR, regional DR, key rotation/compromise, chaos convergence, release rollback, and support ownership are rehearsed.
- **Contractual:** the logical evidence type `contractual-terms` binds the tenant-neutral product-terms package and the exact eligible certification unit's accepted/applicable DPA, customer-data/source boundary, subprocessor, region, retention, deletion, support/SLA, security, incident, exit, and export versions/digests, locale, approvals, release/environment, signature, immutable object version/receipt, and current effective/expiry/revocation state; `contractual_terms_pass` is derived only by the GA verifier.
- **GA:** section 20.5 conditions and the explicit contractual gate are complete; missing, stale, expired, revoked, mistranslated, misbound, wrongly signed/unapproved/unretained, or unaccepted contractual evidence caps the unit at `limited_availability`, and status cannot be set or overridden by an administrator.

## Specification Coverage Matrix

| Spec section | Owning plan/tasks | Milestone evidence |
|---|---|---|
| 1 Product definition | master Tasks 3-8; all workflow plans | one uninterrupted intent-to-value journey and source-free boundary proof |
| 2 Users and delivery modes | identity, web, Git delivery | role journeys plus standard/strict assurance evidence |
| 3 Core principles | foundation, all domain plans | architecture checks, hash/version invariants, human-decision tests |
| 4 Overall architecture | foundation Tasks 1-10; master Task 1 | topology manifest and forbidden-dependency test |
| 5 Requirement Graph | requirement workflow Tasks 1-3 | graph/relation/revision property and projection rebuild tests |
| 6 Canvas and intake | requirement workflow Tasks 4, 9-10; web | accessible canvas/list/detail and structured intake E2E |
| 7 Agent Pack and Context | agent/context Tasks 1-8, 15 | signed pack, baseline/patch/rebuild and no-source evidence |
| 8 Assessment | agent/context Tasks 9-14 | policy versioning, B/H/A/D gates, override, public API contract, evaluation report, and per-support-unit quality evidence |
| 9 Collaboration and confirmation | requirement workflow Tasks 6, 8-10 | proposal rounds, invalidation, ordered exact-revision receipts |
| 10 Ready Pool and DeliveryBatch | requirement workflow Task 8; Git delivery Tasks 1-2 | admission/freeze/amendment and one-active-batch proofs |
| 11 Git contract and modes | Git delivery Tasks 3-6, 8-11 | source-free SPI, signed Development Packages, zero-diff branch release, standard/strict certification |
| 12 WorkItem and Context Patch | Git delivery Task 7; agent/context Tasks 2, 6 | assignment, PR gate, completion, patch/no-change lineage |
| 13 Candidate and acceptance | candidate/acceptance Tasks 1-10 | immutable candidate through exact artifact promotion journey |
| 14 Roles, delegation, separation | identity/tenancy/audit; web | positive/negative authorization matrix and role UX |
| 15 ActionRequest, notification, attachment | requirement workflow Tasks 4-7; web | quarantine/access, idempotent action, notification fallback tests |
| 16 Unified state model | every domain plan; master Tasks 2-7 | state/property tests and cross-aggregate completion invariant |
| 17 Trust, isolation, audit | foundation; identity; Git delivery | mTLS/DSSE/replay/RLS/audit-anchor and credential-separation evidence |
| 18 Failure, recovery, emergency | Git delivery Tasks 8-10; candidate; operations Tasks 5-10 | suspension/reconciliation/abort/hotfix/break-glass drills |
| 19 Metrics, model, production validation | agent/context Task 14; operations Tasks 1-12 | SLO, model certification, value and fault reports |
| 20 Scope, milestones, GA | master Tasks 2-8; operations Tasks 13-14 | milestone manifests, certification-unit bundle, and unit-scoped `contractual-terms` evidence with explicit `contractual_terms_pass` gate |
| 21 Design conclusions | master Task 8 | signed traceability report proving every conclusion in the final journey |
| Appendix A Requirement Contract | requirement workflow; Git delivery | `contracts/golden-fixtures/requirement-contract/appendix-a.json` independent-consumer digest/signature vector |
| Appendix B AssessmentPolicy | agent/context | `contracts/golden-fixtures/assessment-policy/appendix-b.json` formula and gate vector |
| Appendix C Context Patch | agent/context; Git delivery | `contracts/golden-fixtures/context-patch/appendix-c.json` CI/merge lineage vector |
| Appendix D terminology | web | business-label snapshot and accessibility/translation review |

### Task 1: Lock The Canonical Topology And Versioned Contracts

**Files:**
- Create: `docs/architecture/accord-v1-boundaries.yaml`
- Create: `docs/architecture/decisions/ADR-0001-canonical-runtime-boundaries.md`
- Create: `contracts/compatibility/v1-boundaries.yaml`
- Create: `tests/architecture/v1-boundaries.test.mjs`
- Create: `tests/architecture/verify-protobuf-compatibility.ps1`
- Modify: `package.json`
- Modify: `tests/integration/build.gradle`
- Modify: `tests/integration/gradle.lockfile`

- [ ] **Step 1: Execute the repository bootstrap prerequisite**

Complete only Task 1 in `2026-07-24-accord-platform-foundation-plan.md`. Repository inception and the tracked design baseline must already exist on `main`; Foundation Task 1 runs from an isolated feature branch and must not initialize Git. It creates and locks Node, pnpm, Gradle/Java, and Python workspaces plus the Java edge, security, CLI, control-plane, and verification targets defined by that task. Verify its commit and all Task 1 checks before continuing; do not begin Foundation Task 2 yet.

Expected: `package.json`, `pnpm-lock.yaml`, `gradlew`, `gradle/verification-metadata.xml`, the `accordctl` jlink image, and `:tests:integration` exist; `corepack pnpm@10.12.4 install --frozen-lockfile`, `./gradlew projects`, and the Foundation Task 1 verification suite exit 0. This is the sole code-workspace bootstrap prerequisite for the master plan, so no command below is allowed to rely on a runner that does not yet exist.

- [ ] **Step 2: Write the failing topology and compatibility test**

Create `tests/architecture/v1-boundaries.test.mjs`:

```javascript
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import YAML from 'yaml';

test('the V1 runtime and contract boundaries are singular', async () => {
  const raw = await readFile('contracts/compatibility/v1-boundaries.yaml', 'utf8');
  const milestoneBuild = await readFile('tests/integration/build.gradle', 'utf8');
  const manifest = YAML.parse(raw);
  assert.deepEqual(manifest.runtimes, {
    control_plane: 'java-21-spring-modulith',
    edge_services: 'java-21-spring-boot-isolated',
    security_services: 'java-21-spring-boot-isolated',
    cli: 'java-21-picocli-jlink',
    agent_runtime: 'python-3.12-pydantic',
    web: 'react-19.1-typescript-5.8-vite-7'
  });
  assert.equal(manifest.infrastructure.iac, 'opentofu');
  assert.deepEqual(manifest.security_services.sort(),
    ['break-glass-broker', 'credential-broker', 'merge-controller', 'provider-connector', 'signing-service']);
  assert.deepEqual(manifest.edge_services.sort(),
    ['agent-pack-gateway', 'attachment-scanner', 'webhook-edge']);
  assert.deepEqual(manifest.edge_workload_profiles.sort(),
    ['agent-pack-gateway', 'attachment-scanner', 'provider-auth-callback-edge', 'webhook-edge']);
  assert.equal(manifest.contracts.http, 'contracts/openapi/accord-control-api.yaml');
  assert.match(raw, /database\/control-plane\/migrations/);
  assert.match(milestoneBuild, /milestoneTest\s*\{/);
  assert.match(milestoneBuild, /tasks\.register\(['"]milestoneTest['"],\s*Test\)/);
  assert.doesNotMatch(milestoneBuild, /(?:check|test).{0,80}dependsOn.{0,80}milestoneTest/s);
});
```

- [ ] **Step 3: Run the architecture test and verify it fails**

Run: `node --test tests/architecture/v1-boundaries.test.mjs`

Expected: FAIL with `ENOENT` for `contracts/compatibility/v1-boundaries.yaml`.

- [ ] **Step 4: Write the canonical manifest, ADR, and isolated JVM milestone runner**

Create `contracts/compatibility/v1-boundaries.yaml` with this exact interface inventory:

```yaml
schema_version: "1.0"
runtimes:
  control_plane: java-21-spring-modulith
  edge_services: java-21-spring-boot-isolated
  security_services: java-21-spring-boot-isolated
  cli: java-21-picocli-jlink
  agent_runtime: python-3.12-pydantic
  web: react-19.1-typescript-5.8-vite-7
security_services: [signing-service, provider-connector, credential-broker, merge-controller, break-glass-broker]
edge_services: [webhook-edge, attachment-scanner, agent-pack-gateway]
edge_workload_profiles: [webhook-edge, provider-auth-callback-edge, attachment-scanner, agent-pack-gateway]
contracts:
  http: contracts/openapi/accord-control-api.yaml
  events: contracts/events
  grpc: contracts/protobuf/accord
  agent_jobs: contracts/json-schema/agent-job.schema.json
  signed_payloads: contracts/dsse-payloads
  canonicalization: contracts/golden-fixtures/jcs
persistence:
  authority: postgresql-17.5
  migrations: database/control-plane/migrations
  reliability: [expected-version-cas, persistent-idempotency, transactional-outbox, inbox, durable-leases, fencing-tokens]
  optional_cache_layer: disabled
infrastructure:
  iac: opentofu
  deployment: [kubernetes, helm, argocd]
reference_deployment:
  name: aws-japan-primary-warm-dr
  primary: ap-northeast-1
  warm_dr: ap-northeast-3
  architecture_assumption: false
```

`docs/architecture/accord-v1-boundaries.yaml` expands this manifest with each deployable, workload identity, database role, egress allowlist, credential purpose, and prohibited dependency. `edge_services` is the three-project build/image inventory, while `edge_workload_profiles` is the four-entry independently authorized runtime identity inventory. Its extra entry exists because `webhook-edge` and `provider-auth-callback-edge` use the exact same signed `webhook-edge` image digest but have different ServiceAccounts, database roles, encryption keys, ingress paths, mTLS audiences, queues and NetworkPolicies. It assigns the Agent Pack component chart to the Agent Context plan but makes `infra/helm/accord` the only production release, and binds the Gateway to its separate IRSA/cache-KMS/OCI allowlist with no customer-Git egress. ADR-0001 records the same decisions, explicitly states that Temporal is not business truth, and rejects a TypeScript server control plane, direct customer-source access, independently installed Gateway release, shared security credentials, and provider webhooks as authority. Add `"architecture:test": "node --test tests/architecture/*.test.mjs"` to the root `package.json`.

Append this isolated source set and task to `tests/integration/build.gradle`; preserve all Foundation dependencies and task configuration:

```groovy
sourceSets {
    milestoneTest {
        java.srcDir file('src/milestoneTest/java')
        resources.srcDir file('src/milestoneTest/resources')
    }
}

configurations {
    milestoneTestImplementation.extendsFrom testImplementation
    milestoneTestRuntimeOnly.extendsFrom testRuntimeOnly
}

dependencies {
    milestoneTestImplementation project(':apps:control-plane:api')
}

tasks.register('milestoneTest', Test) {
    group = 'verification'
    description = 'Runs isolated cross-component M0-M6 JVM gates.'
    testClassesDirs = sourceSets.milestoneTest.output.classesDirs
    classpath = sourceSets.milestoneTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter tasks.named('test')
}
```

Do not add `milestoneTest` to `test`, `check`, a root aggregate, or a component CI task. Later plans may append normal `testImplementation(project(...))` dependencies; inheritance makes them available to the isolated source set without changing its execution ownership.

Create `tests/architecture/verify-protobuf-compatibility.ps1` so the same shell-independent preflight is used by milestone and release verification:

```powershell
param(
  [string]$BaselineCommit = $env:ACCORD_COMPAT_BASE_REF,
  [string]$SignedBaselineTag = $env:ACCORD_COMPAT_BASE_TAG
)

$ErrorActionPreference = 'Stop'
if ($BaselineCommit -cnotmatch '^[0-9a-f]{40}$') { throw 'ACCORD_COMPAT_BASE_REF must be a full lowercase commit SHA' }
if ([string]::IsNullOrWhiteSpace($SignedBaselineTag)) { throw 'ACCORD_COMPAT_BASE_TAG is required' }

$bufVersionOutput = buf --version
if ($LASTEXITCODE -ne 0) { throw 'Buf is unavailable' }
$bufVersion = $bufVersionOutput.Trim()
if ($bufVersion -ne '1.55.1') { throw "expected Buf 1.55.1, got '$bufVersion'" }

git verify-tag $SignedBaselineTag
if ($LASTEXITCODE -ne 0) { throw 'protobuf baseline tag signature is invalid' }
$tagCommit = (git rev-parse --verify "${SignedBaselineTag}^{commit}").Trim()
if ($LASTEXITCODE -ne 0) { throw 'protobuf baseline tag does not resolve to a commit' }
$resolvedCommit = (git rev-parse --verify "${BaselineCommit}^{commit}").Trim()
if ($LASTEXITCODE -ne 0) { throw 'protobuf baseline commit does not exist' }
$headCommit = (git rev-parse --verify 'HEAD^{commit}').Trim()
if ($LASTEXITCODE -ne 0) { throw 'HEAD does not resolve to a commit' }
if ($tagCommit -ne $resolvedCommit) { throw 'baseline tag and commit do not bind the same object' }
if ($resolvedCommit -eq $headCommit) { throw 'protobuf compatibility baseline must differ from HEAD' }
git merge-base --is-ancestor $resolvedCommit $headCommit
if ($LASTEXITCODE -ne 0) { throw 'protobuf compatibility baseline is not an ancestor of HEAD' }

buf breaking --against ".git#ref=$resolvedCommit"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

- [ ] **Step 5: Verify topology and runner ownership**

Run: `node --test tests/architecture/v1-boundaries.test.mjs && pwsh -NoProfile -Command "[scriptblock]::Create((Get-Content -Raw tests/architecture/verify-protobuf-compatibility.ps1)) | Out-Null" && pwsh -NoProfile -File tests/bootstrap/verify-workspace.ps1 && ./gradlew :tests:integration:dependencies --configuration milestoneTestRuntimeClasspath --write-locks && ./gradlew :tests:integration:milestoneTest --dry-run && ./gradlew test`

Expected: PASS; the workspace and boundary manifest agree, Gradle locks and resolves the dedicated `milestoneTestRuntimeClasspath`, the custom target exists, and the ordinary aggregate remains green without executing or compiling `src/milestoneTest`.

- [ ] **Step 6: Commit the architecture lock and runner isolation**

```bash
git add docs/architecture contracts/compatibility/v1-boundaries.yaml tests/architecture/v1-boundaries.test.mjs tests/architecture/verify-protobuf-compatibility.ps1 tests/integration/build.gradle tests/integration/gradle.lockfile package.json
git commit -m "arch: lock Accord V1 runtime and trust boundaries"
```

### Task 2: Integrate M0 Contracts, Identity, Tenancy, And Audit

**Files:**
- Verify: `.gitignore`
- Verify: `infra/opentofu/modules/accord-foundation-contract/main.tf`
- Create: `tests/integration/src/milestoneTest/java/com/inforvans/accord/milestones/M0ExitGateIT.java`
- Modify: `tests/integration/gradle.lockfile`
- Create: `tests/milestones/m0/independent-signing-vector.json`
- Create: `tests/milestones/m0/gate.yaml`
- Create: `docs/operations/milestones/m0-demo.md`

- [ ] **Step 1: Finish the Foundation prerequisite before targeting the API**

Complete Foundation Tasks 2-17, in order, from `2026-07-24-accord-platform-foundation-plan.md`. Task 1 was completed by master Task 1 and must not be repeated. Before Foundation Task 15 initializes OpenTofu, verify its committed contract still matches the Task 1 toolchain: `infra/opentofu/modules/accord-foundation-contract/main.tf` declares `required_version = "= 1.9.1"`; `.gitignore` includes `**/.terraform/`, `*.tfstate`, `*.tfstate.*`, `crash.log`, and `crash.*.log`; no rule ignores a `.terraform.lock.hcl`; and the task uses its explicit file manifest rather than a broad `git add infra/opentofu`. A mismatch is a failed prerequisite, not an instruction to mutate the plan during execution. Re-run the Foundation acceptance suite and verify the exact specification digest before adding the M0 gate.

Expected: `ControlApiApplication`, PostgreSQL/Flyway, idempotency/CAS, outbox/inbox, the security-service process skeleton and protobuf toolchain, the local integration harness, and every target used below exist and are green. Identity, tenancy authorization, purpose-bound signing, audit chaining, and audit anchoring are still absent, which is the intended M0 red boundary.

- [ ] **Step 2: Write the failing isolated M0 integration test**

Create `M0ExitGateIT.java` under the isolated `milestoneTest` source set as a Spring Boot/Testcontainers test. A package-private `M0HttpFixture` nested in the same test class drives only public HTTP contracts with `MockMvc`, fixed PostgreSQL 17.5 container properties, and Java records for string/JSON representations; it must not import identity, authorization, audit, or signing implementation types. Keep these exact assertions:

```java
@Test
void tenantCommandIsIdempotentAuditedAndCannotCrossTenant() {
    var tenantA = fixture.tenant("10000000-0000-0000-0000-000000000001");
    var tenantB = fixture.tenant("20000000-0000-0000-0000-000000000001");
    var identityA = fixture.verifiedRequestIdentity(tenantA, "project_owner");
    var identityB = fixture.verifiedRequestIdentity(tenantB, "project_owner");
    var first = api.createProject(identityA, "m0-create-0001", 0);
    var replay = api.createProject(identityA, "m0-create-0001", 0);
    assertEquals(first.bodyDigest(), replay.bodyDigest());
    assertEquals(1, fixture.businessEffectCount(first.projectId()));
    var concealed = api.readProject(identityB, first.projectId());
    assertEquals(404, concealed.status());
    assertEquals("RESOURCE_NOT_FOUND", concealed.problemCode());
    assertFalse(concealed.body().contains(first.projectId().toString()));
    assertTrue(audit.verifyTenantChain(tenantA));
    assertEquals(1, audit.events(tenantA, first.correlationId()).stream()
        .filter(event -> event.effect().equals("project.created")).count());
    assertEquals("accord.audit-anchor.v1", signer.verifyLatestAnchor(tenantA).payloadType());
}
```

`M0HttpFixture` installs the two principals through the authentication test adapter and sends their resulting browser session or OIDC credential. The public request path, headers, and body never contain tenant ID, actor/account ID, natural-person ID, role, or side; the API derives all of them from `VerifiedRequestIdentity`. Add hostile variants that send forged tenant/actor headers and body properties and prove they are rejected or ignored without changing the derived identity. Tenant A and tenant B therefore differ only by authenticated principal, and every cross-tenant project lookup returns the same concealed RFC 7807 `404 RESOURCE_NOT_FOUND` as an unknown UUID.

- [ ] **Step 3: Run the M0 test before Identity owns the missing behavior**

Run: `./gradlew :tests:integration:dependencies --configuration milestoneTestRuntimeClasspath --write-locks && ./gradlew :tests:integration:milestoneTest --tests '*M0ExitGateIT'`

Expected: the target and test compile, then FAIL on a public M0 assertion: `/v1/session` or `POST /v1/projects` is absent, the cross-tenant request is not yet concealed by the final policy, or no audit anchor receipt exists. A missing Gradle target, unresolved test helper, or missing test dependency is an orchestration defect and must be fixed before continuing.

- [ ] **Step 4: Execute Identity, Tenancy, Audit, and Signing and freeze the gate vector**

Complete Identity Tasks 1-16 in order, then stop before Identity Task 17: that task generates into `packages/api-client`, which does not exist until the Web workspace owns it. Complete Web Experience Tasks 1-2 in order to create the locked frontend workspace, cumulative OpenAPI generator, generated package, ownership checks, and no-handwritten-transport boundary; do not execute Web Task 3 or any product screen at M0. Then complete Identity Task 17 against that existing generated-client package. This `Identity 1-16 -> Web 1-2 -> Identity 17` sequence is mandatory, and none of these tasks is repeated at M1. Create `independent-signing-vector.json` as a valid Identity Task 14 audit-export fixture, not a signing-only sample. It contains the exact parser fields `payload_type`, `initial_previous_hash`, `events` (each with `canonical_json` and `event_hash`), `anchor_payload`, `dsse_envelope`, and `public_key_spki_base64`; its canonical events form a valid tenant-A hash chain and its anchor payload binds the final event hash. The same fixture records key purpose `audit_anchor`, nonce, validity interval, fixed JCS digest, and identical verification results from the separately packaged Signing Service and `accordctl` implementations. Create `gate.yaml`:

```yaml
milestone: M0
requires: []
gates:
  - openapi-and-protobuf-compatible
  - independent-java-jcs-dsse-consumers-identical
  - tenant-isolation-negative-matrix
  - server-derived-tenant-actor-and-concealed-not-found
  - human-and-git-identity-mapping
  - role-delegation-separation-of-duties
  - cas-idempotency-outbox-inbox
  - append-only-audit-chain-and-anchor
forbidden: [cross-tenant-disclosure, unsigned-security-call, duplicate-business-effect]
demo: docs/operations/milestones/m0-demo.md
```

The demo provisions two tenant fixtures, authenticates one distinct human/Git identity in each, performs one allowed action as tenant A and one concealed cross-tenant lookup as tenant B without ever sending tenant or actor fields to the API, replays the allowed command, and independently verifies tenant A's audit anchor with `accordctl audit verify`.

- [ ] **Step 5: Run the complete M0 gate**

Run: `./gradlew :tests:integration:dependencies --configuration milestoneTestRuntimeClasspath --write-locks && ./gradlew test :apps:webhook-edge:test :security-services:signing-service:test :cmd:accordctl:test :cmd:accordctl:installDist && corepack pnpm@10.12.4 contracts:test && ./gradlew :tests:integration:milestoneTest --tests '*M0ExitGateIT' && cmd/accordctl/build/install/accordctl/bin/accordctl audit verify --fixture tests/milestones/m0/independent-signing-vector.json`

Expected: every command exits 0; the CLI prints `chain=valid signature=valid payload_type=accord.audit-anchor.v1`; the tenant-B request returns the same concealed `404 RESOURCE_NOT_FOUND` as an unknown project without resource disclosure; forged tenant/actor inputs never replace the authenticated identity; and the repeated command has one business effect.

- [ ] **Step 6: Commit the M0 integration gate**

```bash
git add tests/integration/src/milestoneTest/java/com/inforvans/accord/milestones/M0ExitGateIT.java tests/integration/gradle.lockfile tests/milestones/m0 docs/operations/milestones/m0-demo.md
git commit -m "test: certify the M0 contract and trust foundation"
git tag -s accord-contract-baseline-v1-m0 -m "Accord V1 M0 protobuf compatibility baseline"
git verify-tag accord-contract-baseline-v1-m0
```

### Task 3: Integrate M1 Requirement Graph, Attachments, Actions, And Web Journey

**Files:**
- Create: `tests/integration/src/milestoneTest/java/com/inforvans/accord/milestones/M1ExitGateIT.java`
- Modify: `tests/integration/gradle.lockfile`
- Create: `tests/milestones/playwright.config.ts`
- Create: `tests/milestones/playwright/m1-requirement-journey.spec.ts`
- Create: `tests/milestones/fixtures/m1/stock-policy.txt`
- Create: `tests/milestones/m1/intake-fixture.json`
- Create: `tests/milestones/m1/gate.yaml`
- Create: `docs/operations/milestones/m1-demo.md`

- [ ] **Step 1: Write the failing isolated M1 API journey**

Create `M1ExitGateIT.java` in `src/milestoneTest/java`. Its nested package-private HTTP fixture follows the same Spring/Testcontainers pattern as M0 and uses only OpenAPI JSON requests; it does not depend on M0 test classes or shared mutable test state. It submits text plus a quarantined attachment, requests structuring, reads both role projections, checks the ActionRequest revision hash, and attempts business confirmation before development confirmation:

```java
@Test
void businessIntakeReachesTheExactRevisionConfirmationBoundary() {
    var draft = api.submitIntake("Prevent release when inventory is negative");
    assertEquals("scanning", api.attachment(draft.attachmentId()).state());
    var revision = api.completeStructuring(draft.id());
    assertEquals(revision.hash(), api.businessView(revision.ref()).revisionHash());
    assertEquals(revision.hash(), api.developmentView(revision.ref()).revisionHash());
    assertEquals(revision.hash(), api.action("business_confirmation").revisionHash());
    var question = api.createBusinessQuestion(revision.ref(), true);
    assertProblem(api.confirmDevelopment(revision.hash()), 409, "blocking_business_question_open");
    api.answerAndResolveBusinessQuestion(question.id());
    assertProblem(api.confirmBusinessFirst(revision.hash()), 409, "development_confirmation_required");
}
```

Use Java `record` response projections, `MockMvc`, Jackson, PostgreSQL Testcontainers, and `@SpringBootTest(classes = ControlApiApplication.class)`; all are available from the completed Foundation/Identity test dependencies. `M0ExitGateIT` and later JVM gates follow the same black-box pattern and keep their fixture helpers nested in the test class so every `--tests` filter compiles and runs independently.

- [ ] **Step 2: Run the M1 gate before requirement modules exist**

Run: `./gradlew :tests:integration:milestoneTest --tests '*M1ExitGateIT'`

Expected: the isolated target compiles and FAILS on the absent intake/Requirement Graph/attachment/action public behavior. The already-green M0 test and every ordinary component test remain unaffected.

- [ ] **Step 3: Execute the requirement and web plans in dependency order, then add browser certification**

Execute this exact sequence; do not skip ahead inside either component plan:

1. Complete Requirement Workflow Tasks 1-8 in order.
2. Verify the Web Tasks 1-2 commits completed at M0 are present and green by running their workspace, ownership, generator, and `check:generated` gates; do not rerun or recreate either task.
3. Complete Requirement Workflow Task 9; its contract-generation step updates the existing cumulative client rather than targeting an absent package.
4. Complete Web Experience Tasks 3-11 in order against the extended Requirement API.
5. Complete Requirement Workflow Task 10. Its root system runner is now allowed to exercise the implemented M1 UI; it must not discover `tests/milestones/**`.

Create `tests/milestones/playwright.config.ts` as a separate runner over the same ephemeral production-like endpoint used by the shared system suite:

```typescript
import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.ACCORD_SYSTEM_BASE_URL;
if (!baseURL) throw new Error('ACCORD_SYSTEM_BASE_URL must identify the current-commit milestone ingress');

export default defineConfig({
  testDir: './playwright',
  testMatch: '**/*.spec.ts',
  forbidOnly: true,
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 2 : 0,
  timeout: 120_000,
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  }
});
```

Create `m1-requirement-journey.spec.ts`:

```typescript
import { expect, test } from '@playwright/test';

test('@m1 business intake reaches the exact-revision confirmation gate', async ({ page }) => {
  await page.goto('/t/10000000-0000-0000-0000-000000000001/p/30000000-0000-0000-0000-000000000001/requirements/new');
  await page.getByLabel('Expected outcome').fill('Prevent release when inventory is negative');
  await page.getByLabel('Business material').setInputFiles('tests/milestones/fixtures/m1/stock-policy.txt');
  await page.getByRole('button', { name: 'Submit for structuring' }).click();
  await expect(page.getByText('Scanning attachment')).toBeVisible();
  await expect(page.getByText('Inventory release rule')).toBeVisible();
  await page.getByRole('tab', { name: 'Development view' }).click();
  await expect(page.getByText('Evidence and unknowns')).toBeVisible();
  await page.getByRole('link', { name: 'Needs my action' }).click();
  await expect(page.getByTestId('action-request')).toHaveAttribute('data-revision-hash', /^sha256:/);
  await page.getByRole('button', { name: 'Confirm this revision' }).click();
  await expect(page.getByText('Development confirmation required first')).toBeVisible();
});
```

The intake fixture must contain all seven standard block types, every standard relation, contractual and reference attachments, accepted unknown and blocking unknown examples, and two revisions whose hashes differ only through semantic payload. `stock-policy.txt` contains `Block inventory release when projected_quantity < 0; acceptance requires a rejected release event and no inventory mutation.` Create `gate.yaml`:

```yaml
milestone: M1
requires: [M0]
gates:
  - one-requirement-graph-two-role-projections
  - jcs-revision-hash-and-version-diff
  - draft-resume-and-structured-intake
  - attachment-quarantine-scan-version-access-preflight
  - action-request-visible-p95-3s-and-idempotent-resolution
  - revision-bound-business-question-block-answer-and-resolution
  - exact-revision-confirmation-order
  - canvas-list-search-mobile-and-wcag
zero_tolerance: [cross-tenant-attachment, unscanned-download, confirmation-of-stale-revision]
```

Use the Appendix A fixture as the expected formal projection; temporary object URLs, notification state, layout coordinates, and active personnel must not enter `revision_hash`.

- [ ] **Step 4: Verify the complete M1 exit gate**

Run: `./gradlew :tests:integration:dependencies --configuration milestoneTestRuntimeClasspath --write-locks && ./gradlew :apps:control-plane:modules:requirement-graph:test :apps:control-plane:modules:attachments-metadata:test :apps:control-plane:modules:actions-notifications:test :tests:api:test --tests '*RequirementApiContractTest' && ./gradlew :tests:integration:milestoneTest --tests '*M1ExitGateIT' && pnpm --filter @accord/api-client check:generated && pnpm --filter @accord/web test && pnpm exec playwright test --config tests/milestones/playwright.config.ts --grep @m1`

Expected: PASS; the exact `requirement-workflow` OpenAPI/controller/application/generated-client/Web owner set contains 47 operations with no alias or deletion; the fixture produces stable cross-process hashes, quarantined content is inaccessible, the blocking revision-bound BusinessQuestion must be answered and resolved by the business side before confirmation, stale confirmation is rejected, ActionRequest visibility is within 3 seconds, and the browser journey reaches the ordered confirmation gate.

- [ ] **Step 5: Commit the M1 integration gate**

```bash
git add tests/integration/src/milestoneTest/java/com/inforvans/accord/milestones/M1ExitGateIT.java tests/integration/gradle.lockfile tests/milestones/playwright.config.ts tests/milestones/playwright/m1-requirement-journey.spec.ts tests/milestones/fixtures/m1/stock-policy.txt tests/milestones/m1 docs/operations/milestones/m1-demo.md
git commit -m "test: certify the M1 requirement workflow"
```

### Task 4: Integrate M2 Agent Pack, Project Context, And Assessment

**Files:**
- Create: `tests/e2e/milestones/m2-agent-context-assessment.ps1`
- Modify: `tests/integration/gradle.lockfile`
- Create: `tests/milestones/m2/source-canary.txt`
- Create: `tests/milestones/m2/gate.yaml`
- Create: `docs/operations/milestones/m2-demo.md`

- [ ] **Step 1: Write the failing source-free M2 journey**

Create `m2-agent-context-assessment.ps1` to install the fixed signed Agent Pack fixture, submit Appendix C baseline/patch attestations through `accordctl`, request impact analysis, submit H scores, confirm Appendix B policy, and assert the returned result:

```powershell
$sourceCanaryDigest = 'sha256:' + (Get-FileHash -Algorithm SHA256 tests/milestones/m2/source-canary.txt).Hash.ToLowerInvariant()
$accordctl = if ($IsWindows) { 'cmd/accordctl/build/install/accordctl/bin/accordctl.bat' } else { 'cmd/accordctl/build/install/accordctl/bin/accordctl' }
$result = & $accordctl milestone m2 `
  --auth-profile m2-tenant-a `
  --context contracts/golden-fixtures/context-patch/appendix-c.json `
  --policy contracts/golden-fixtures/assessment-policy/appendix-b.json `
  --source-canary-digest $sourceCanaryDigest `
  --json | ConvertFrom-Json
if ($result.project_state -ne 'active') { throw 'project setup not active' }
if ($result.context_state -ne 'active') { throw 'context not active' }
if ($result.agent_context_owner_operation_count -ne 53) { throw 'Agent Context operation surface drifted' }
if (-not $result.pack_download.fixed_digest_verified -or -not $result.pack_download.capability_consumed_once) { throw 'Pack distribution proof incomplete' }
if ($result.pack_download.distribution_epoch -lt 1) { throw 'Pack distribution epoch missing' }
if ($result.impact_child_revision_no -le $result.impact_parent_revision_no) { throw 'Impact review did not create a child Revision' }
if (-not $result.impact_work_item_order_valid) { throw 'Impact WorkItem dependencies are invalid' }
if ($result.development_score -ne (0.6 * $result.h + 0.4 * $result.a)) { throw 'D formula mismatch' }
if ($result.platform_source_canary_matches -ne 0) { throw 'customer source crossed boundary' }
if (-not $result.blocking_unknown_failed_closed) { throw 'blocking unknown was bypassed' }
```

`--auth-profile` selects a locally configured browser-session or OIDC credential and is never serialized into an API route, header, query, or body. The server derives tenant, account, natural person, roles, and side only from `VerifiedRequestIdentity`; registered CI calls use the Identity-owned `Workload` branch and a purpose-prefixed idempotency subject. The runner fails if the authenticated principal does not own the project/workload scope. The command verifies the signed Pack release metadata and compatibility matrix, issues one structured same-origin HTTPS capability bound to actor/workload, fixed OCI/release digests and current distribution epoch, races a replay, and accepts bytes only after the Gateway proves full digest/signature verification and one atomic consumption. It then uses Identity Task 17's generated operations to resume setup, validates Agent Pack/CI/Context/AssessmentPolicy references after Agent Task 13 is live, confirms an exact Context-bound Impact Draft into a child Revision with ordered proposed WorkItems, records distinct development then business confirmations, and activates the exact setup digest before returning `project_state=active`.

- [ ] **Step 2: Run the journey before the M2 components exist**

Run: `pwsh -NoProfile -File tests/e2e/milestones/m2-agent-context-assessment.ps1`

Expected: FAIL because the `accordctl milestone m2` command, signed Pack, or Context ingestion endpoint is missing.

- [ ] **Step 3: Execute the Agent Context and Assessment plan**

Execute this exact dependency sequence:

1. Complete Agent Context and Assessment Tasks 1-14 in order. Task 13 publishes the cumulative Project Context and Assessment API before Task 14 certifies every advertised support unit.
2. Run `pnpm api:generate && pnpm --filter @accord/api-client check:generated` against the now-cumulative Context and Assessment OpenAPI.
3. Extend the M2 runner to call the already-generated Identity Task 17 setup validation/submission/development-confirmation/business-confirmation/activation operations after Context and AssessmentPolicy become current; no internal repository shortcut may activate the project.
4. Complete Web Experience Task 12, which consumes those generated server types for assessment, proposals, and ordered confirmation.
5. Complete Agent Context and Assessment Task 15 so its source-free system journey runs only after the corresponding web slice exists.

Create `gate.yaml`:

```yaml
milestone: M2
requires: [M1]
gates:
  - signed-pack-install-pin-revoke
  - resumable-project-setup-evidence-confirmation-and-activation
  - ci-attested-context-baseline-patch-no-change-rebuild
  - source-free-platform-and-agent-runtime
  - evidence-grounded-impact-with-unknown-and-conflict
  - development-reviewed-impact-child-revision-and-workitem-order
  - exact-53-operation-controller-application-generated-client-owner-set
  - versioned-assessment-policy-and-ordered-human-confirmation
  - b-h-a-d-dimension-floor-hard-blocker-and-override
  - signed-assessment-brief-and-support-unit-quality
zero_tolerance: [critical-unsupported-claim, high-risk-blocker-miss, source-canary-leak]
```

The canary file contains a unique high-entropy source fragment. The gate scans platform PostgreSQL, object storage, Temporal payloads, messages, model requests, process-local diagnostic snapshots, logs, metrics, and traces; only the customer-side test harness may contain the literal.

- [ ] **Step 4: Run the M2 component and boundary suites**

Run: `./gradlew :apps:agent-pack-gateway:test :cmd:accordctl:test :cmd:accordctl:installDist && uv run pytest apps/agent-runtime/tests -q && ./gradlew :tests:integration:dependencies --configuration milestoneTestRuntimeClasspath --write-locks && ./gradlew :apps:control-plane:modules:project-context:test :apps:control-plane:modules:assessment:test :tests:api:test --tests '*ContextAssessmentApiContractTest' && ./gradlew :tests:integration:milestoneTest --tests '*M0ExitGateIT' --tests '*M1ExitGateIT' && corepack pnpm@10.12.4 --filter @accord/api-client check:generated && corepack pnpm@10.12.4 --filter @accord/web test -- development-impact-workbench.test.tsx assessment-summary.test.tsx assessment-brief.test.tsx confirmation-panel.test.tsx && pwsh -NoProfile -File tests/e2e/milestones/m2-agent-context-assessment.ps1`

Expected: PASS; the exact `agent-context-assessment` OpenAPI/controller/application/generated-client/Web owner set contains 53 operations and every controller method has one method-level authenticated principal; Pack compatibility, fixed digest, actor binding, epoch, one-use claim and attestations verify; the exact setup is validated, distinctly confirmed, and active through public operations; the development-reviewed Impact Draft creates the exact child Revision with acyclic ordered WorkItems; Context is active with intact linkage/watermarks/merge receipts; D equals `0.6H + 0.4A`; blockers cannot be averaged or overridden away; and every platform sink reports zero canary matches.

- [ ] **Step 5: Commit the M2 integration gate**

```bash
git add tests/e2e/milestones/m2-agent-context-assessment.ps1 tests/integration/gradle.lockfile tests/milestones/m2 docs/operations/milestones/m2-demo.md
git commit -m "test: certify the M2 context and assessment loop"
```

### Task 5: Integrate M3 Alignment, Multi-Repository Package Release, And Standard Mode

**Files:**
- Create: `tests/integration/src/milestoneTest/java/com/inforvans/accord/milestones/M3ExitGateIT.java`
- Modify: `tests/integration/gradle.lockfile`
- Create: `tests/milestones/m3/provider-facts.json`
- Create: `tests/milestones/m3/gate.yaml`
- Create: `docs/operations/milestones/m3-demo.md`

- [ ] **Step 1: Write the failing M3 cross-Provider transaction and branch-release test**

Create `M3ExitGateIT.java` in the isolated source set. Its nested fixture talks through public HTTP plus GitHub and GitLab metadata-only Provider sandboxes, so it compiles without importing future Delivery, Connector, package, or reconciliation implementation classes. Use string contract values for `STANDARD`, `PUBLISHING`, `READY`, `SUSPENDED`, `RECONCILIATION_REQUIRED`, `PARTIAL`, and `COMPLETE`, and keep this required sequence:

```java
@Test
void confirmedRevisionsReleaseEveryRepositoryWithoutWritingGitContentAndRemainTruthful() {
    var githubRepository = fixture.connectDiscoverAndBind("GITHUB", githubCloudSandbox);
    var gitlabRepository = fixture.connectDiscoverAndBind("GITLAB", gitlabSaasSandbox);
    assertEquals("ACTIVE", githubRepository.bindingState());
    assertEquals("ACTIVE", gitlabRepository.bindingState());
    assertTrue(githubRepository.currentTrustRegistrationCapabilityAndEpochAgree());
    assertTrue(gitlabRepository.currentTrustRegistrationCapabilityAndEpochAgree());

    var githubRevision = fixture.developableRevision(githubRepository);
    var gitlabRevision = fixture.developableRevision(gitlabRepository);
    fixture.confirmDevelopmentThenBusiness(githubRevision.hash());
    fixture.confirmDevelopmentThenBusiness(gitlabRevision.hash());

    var readyPool = api.readyPool();
    var batch = api.createBatch(
        readyPool.etag(), "STANDARD",
        List.of(fixture.workSet(githubRepository, githubRevision),
                fixture.workSet(gitlabRepository, gitlabRevision)));

    var githubPackage = api.developmentPackage(batch.id(), batch.githubWorkSetId());
    var gitlabPackage = api.developmentPackage(batch.id(), batch.gitlabWorkSetId());
    assertTrue(githubPackage.dsseVerified());
    assertTrue(gitlabPackage.dsseVerified());
    assertEquals(batch.effectiveManifestDigest(), githubPackage.effectiveManifestDigest());
    assertEquals(batch.effectiveManifestDigest(), gitlabPackage.effectiveManifestDigest());

    release.releaseAndReconcile(batch.id(), batch.githubWorkSetId());
    assertEquals("PARTIAL", api.batch(batch.id()).repositoryReleaseCoverage());
    assertEquals("PUBLISHING", api.batch(batch.id()).phase());

    release.releaseAndReconcile(batch.id(), batch.gitlabWorkSetId());
    var ready = api.batch(batch.id());
    assertEquals("COMPLETE", ready.repositoryReleaseCoverage());
    assertEquals("READY", ready.phase());
    assertEquals(github.defaultHeadSha(), github.deliveryHeadSha());
    assertEquals(github.defaultTreeSha(), github.deliveryTreeSha());
    assertEquals(gitlab.defaultHeadSha(), gitlab.deliveryHeadSha());
    assertEquals(gitlab.defaultTreeSha(), gitlab.deliveryTreeSha());
    assertEquals(0, github.contentMutationCalls() + gitlab.contentMutationCalls());

    gitlab.simulateAdministratorBypass(batch.gitlabWorkSetId());
    reconciliation.run(gitlabRepository.id());
    var suspended = api.batch(batch.id());
    assertEquals("SUSPENDED", suspended.operationalState());
    assertEquals("RECONCILIATION_REQUIRED", suspended.consistencyState());
    assertNotEquals("STRICT", suspended.assuranceState());
}
```

- [ ] **Step 2: Run the M3 test before batch, package, Connector, and release integration exists**

Run: `./gradlew :tests:integration:milestoneTest --tests '*M3ExitGateIT'`

Expected: the target compiles, M0 through M2 remain green, and M3 FAILS because Provider connection/discovery/Binding onboarding, cross-repository Batch creation, signed package retrieval, branch release, or standard-mode reconciliation returns an absent-operation problem or does not reach the asserted failed-closed state. An unresolved implementation type is forbidden in this black-box gate.

- [ ] **Step 3: Complete Git delivery and its web slices through standard mode**

Requirement Workflow Tasks 1-10 were completed in order at M1; do not rerun or cherry-pick individual tasks here. Complete Git Delivery Tasks 1-8 in order, run `pnpm api:generate && pnpm --filter @accord/api-client check:generated`, then complete Web Experience Tasks 13-14 in order. Git migrations V040 and V041 are one indivisible M3 rollout: the migration Job must apply both under one Flyway lock before any M3 application pod starts, readiness must reject a schema history ending at V040, and no production deployment may run a V040-only control plane. The `provider-facts.json` fixture contains two single-use connection/callback receipts, active Provider installations, opaque discovery receipts, Identity trust-establishment receipts, registry associations, repositories, immutable repository IDs, default/delivery refs, exact base and observed heads/trees, capability snapshots, PR/check/protection metadata, merge actor and a webhook cursor gap. It contains no callback code, credential, raw state, arbitrary endpoint, source body or Provider-native identity accepted from an API caller. Each repository has one closed signed Development Package publication and one branch-release receipt containing `release_id`, Batch/RepositoryWorkSet/installation/repository/effective-manifest identifiers, `delivery_ref`, unchanged baseline commit/tree, capability/package/Provider-fact digests, `provider_state=VERIFIED`, receipt-bound `provider_observed_at`, external-intent binding and recomputable `receipt_digest`; it contains no repository path, published commit, blob, diff, source text or credential. A Provider timestamp is evidence, not a browser-clock TTL: a later contradictory current fact suspends the affected WorkSet and Batch, while an unchanged later observation does not become invalid merely due to age. Create `gate.yaml`:

```yaml
milestone: M3
requires: [M2]
gates:
  - five-family-provider-connection-discovery-and-identity-owned-binding
  - exact-12-operation-provider-onboarding-owner-set
  - proposal-rounds-field-ownership-and-revision-invalidation
  - development-then-business-confirmation-of-one-hash
  - project-scoped-ready-pool-cas-and-one-active-batch-per-repository
  - frozen-delivery-commitment-and-amendment-rules
  - signed-platform-development-packages-never-written-to-git
  - cross-provider-zero-diff-branch-release-with-provider-cas
  - complete-per-repository-release-proof-before-ready-or-workitem-start
  - exact-24-operation-delivery-owner-set
  - standard-mode-check-detect-suspend-reconcile
maximum_bypass_detection_seconds: 900
```

- [ ] **Step 4: Verify M3 package/branch release and standard-mode recovery**

Run: `./gradlew :tests:integration:dependencies --configuration milestoneTestRuntimeClasspath --write-locks && ./gradlew :apps:control-plane:modules:requirement-graph:test :apps:control-plane:modules:delivery:test :apps:control-plane:modules:development-package:test :apps:control-plane:modules:git-coordination:test :security-services:provider-connector:test :security-services:credential-broker:test :apps:webhook-edge:test :tests:api:test --tests '*DeliveryOpenApiContractTest' --tests '*ProviderOnboardingOpenApiContractTest' && ./gradlew :tests:integration:milestoneTest --tests '*M3ExitGateIT' && corepack pnpm@10.12.4 --filter @accord/api-client check:generated && corepack pnpm@10.12.4 --filter @accord/web test -- ready-pool.test.tsx batch-workspace.test.tsx batch-manifest-review.test.tsx batch-branch-release-panel.test.tsx batch-progress.test.tsx context-overview.test.tsx work-item-table.test.tsx git-checks-panel.test.tsx reconciliation-panel.test.tsx && pwsh -NoProfile -File tests/architecture/verify-protobuf-compatibility.ps1`

Expected: PASS; the exact `provider-onboarding` owner set contains 12 operations and the separate callback contract is absent from the browser client; all five Provider families pass their declared Cloud/Enterprise path; Identity alone owns Binding activation and no Binding is selectable before current trust, registration, unexpired capability facts and an exact CapabilitySnapshot-to-installation credential-epoch match agree. The exact `delivery-control` OpenAPI/controller/application/generated-client/Web owner set contains 24 operations; signed platform Development Packages remain in immutable Accord/OSS storage; platform Provider operations create only zero-diff refs and never repository content; `READY` and WorkItem start are impossible until every current RepositoryWorkSet has a complete package plus branch-release receipt whose current Provider fact agrees on every installation/repository/ref/commit/tree/manifest/capability/package digest; one normal batch executes per repository even when a Batch spans Providers; bypass is found within 900 seconds; and the product shows `SUSPENDED/RECONCILIATION_REQUIRED` rather than a false strict claim.

- [ ] **Step 5: Commit the M3 integration gate**

```bash
git add tests/integration/src/milestoneTest/java/com/inforvans/accord/milestones/M3ExitGateIT.java tests/integration/gradle.lockfile tests/milestones/m3 docs/operations/milestones/m3-demo.md
git commit -m "test: certify M3 package release and standard delivery"
```

### Task 6: Integrate M4 WorkItem, Candidate, Acceptance, And Completion

**Files:**
- Create: `tests/integration/src/milestoneTest/java/com/inforvans/accord/milestones/M4ExitGateIT.java`
- Modify: `tests/integration/gradle.lockfile`
- Create: `tests/milestones/playwright/m4-candidate-acceptance.spec.ts`
- Create: `tests/milestones/m4/candidate-chain.json`
- Create: `tests/milestones/m4/gate.yaml`
- Create: `docs/operations/milestones/m4-demo.md`

- [ ] **Step 1: Write the failing exact-candidate acceptance tests**

The isolated Java test drives completion, candidate, acceptance, correction, continuity, promotion, and completion through public APIs and sandbox ports, without importing future Candidate/Acceptance implementation types, then asserts:

```java
assertAll(
    () -> assertEquals(candidate.repositoryTreeSha(), provider.actualDefaultTreeSha()),
    () -> assertEquals(candidate.artifactDigest(), artifact.promotedDigest()),
    () -> assertEquals("passed", acceptance.result()),
    () -> assertEquals("active", acceptance.validity()),
    () -> assertFalse(api.accept(staleCandidate.id()).isAccepted()),
    () -> assertEquals("correction_run", api.classifyFailure("implementation_mismatch").nextObject()),
    () -> assertTrue(api.batch(batch.id()).completionInvariantSatisfied())
);
```

Tag the isolated Playwright test `@m4`. It logs in as the business acceptance owner, inspects revision/context/test/artifact evidence, rejects a stale Candidate, passes the current Candidate, and observes promotion plus `completed` only after reconciliation.

- [ ] **Step 2: Run the M4 tests before candidate and acceptance modules exist**

Run each command independently so both red results are recorded against the current M3 environment:

```bash
./gradlew :tests:integration:milestoneTest --tests '*M4ExitGateIT'
pnpm exec playwright test --config tests/milestones/playwright.config.ts --grep @m4
```

Expected: both commands reach their existing runners and FAIL because Candidate/AcceptanceRun public APIs, views, or the completion invariant are absent. M0-M3 remain green; no default API or web test command discovers either M4 source.

- [ ] **Step 3: Complete Candidate Acceptance and its web slice**

Git Delivery Task 7 was already completed as part of the ordered M3 Tasks 1-8 and must not be repeated. Execute this exact sequence:

1. Complete Candidate Acceptance Tasks 1-9 in order.
2. Run `pnpm api:generate && pnpm --filter @accord/api-client check:generated` against the Candidate/Acceptance APIs.
3. Complete Web Experience Task 15.
4. Complete Candidate Acceptance Task 10 so its end-to-end proof runs after the acceptance UI exists.

`candidate-chain.json` records exact revision, context watermark, source head, target head, result tree, normalized diff digest, test attestation, Candidate ID/tree/artifact, AcceptanceRun evidence digest, promotion receipt, and actual default tree. Create `gate.yaml`:

```yaml
milestone: M4
requires: [M3]
gates:
  - assigned-workitem-local-codex-and-pr-gates
  - valid-completion-and-context-watermark
  - immutable-candidate-exact-tree-artifact-and-provenance
  - exact-business-acceptance-and-targeted-retest
  - implementation-error-correction-versus-requirement-change
  - acceptance-continuity-with-explicit-unaffected-proof
  - promote-exact-accepted-digest
  - atomic-batch-completion-invariant
zero_tolerance: [stale-candidate-accepted, rebuilt-artifact-substituted, wrong-completed]
```

- [ ] **Step 4: Run the M4 exactness and role journey**

Run: `./gradlew :tests:integration:dependencies --configuration milestoneTestRuntimeClasspath --write-locks && ./gradlew :apps:control-plane:modules:delivery:test :apps:control-plane:modules:candidate-acceptance:test && ./gradlew :tests:integration:milestoneTest --tests '*M4ExitGateIT' && pnpm --filter @accord/web test -- candidate-summary.test.tsx acceptance-workbench.test.tsx failure-disposition.test.tsx correction-summary.test.tsx && pnpm exec playwright test --config tests/milestones/playwright.config.ts --grep @m4`

Expected: PASS; stale/wrong candidates and artifacts are rejected, implementation mismatch stays on the same revision as a CorrectionRun, changed business intent creates a new revision, and only the reconciled exact chain reaches `completed`.

- [ ] **Step 5: Commit the M4 integration gate**

```bash
git add tests/integration/src/milestoneTest/java/com/inforvans/accord/milestones/M4ExitGateIT.java tests/integration/gradle.lockfile tests/milestones/playwright/m4-candidate-acceptance.spec.ts tests/milestones/m4 docs/operations/milestones/m4-demo.md
git commit -m "test: certify M4 exact candidate acceptance"
```

### Task 7: Integrate M5 Strict Merge, Reconciliation, And Emergency Recovery

**Files:**
- Create: `tests/e2e/milestones/m5-strict-recovery.ps1`
- Modify: `tests/integration/gradle.lockfile`
- Create: `contracts/json-schema/milestone-m5-strict-recovery-report.schema.json`
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/chaos/ChaosCommands.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/chaos/ChaosCommandsTest.java`
- Verify: `infra/opentofu/roots.json`
- Verify: `scripts/ci/verify-opentofu.ps1`
- Create: `tests/milestones/m5/fault-matrix.yaml`
- Create: `tests/milestones/m5/gate.yaml`
- Create: `docs/operations/milestones/m5-demo.md`

- [ ] **Step 1: Write the failing strict-mode recovery exercise**

Create `m5-strict-recovery.ps1` to invoke an ephemeral provider sandbox, verify the signed machine report, and only then assert one-time merge authorization, exact-head CAS, protection proof, provider-result uncertainty, drift, abort, emergency change, and break-glass behavior:

```powershell
param(
  [string]$EnvironmentId = $env:ACCORD_STAGING_ENVIRONMENT_ID,
  [string]$ProviderBaselineDigest = $env:ACCORD_PROVIDER_BASELINE_DIGEST,
  [string]$ReleaseBundleDigest = $env:ACCORD_RELEASE_BUNDLE_DIGEST
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if ([string]::IsNullOrWhiteSpace($EnvironmentId)) { throw 'ACCORD_STAGING_ENVIRONMENT_ID is required' }
if ($ProviderBaselineDigest -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'ACCORD_PROVIDER_BASELINE_DIGEST is invalid' }
if ($ReleaseBundleDigest -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'ACCORD_RELEASE_BUNDLE_DIGEST is invalid' }

$releaseCommit = (git rev-parse --verify 'HEAD^{commit}').Trim()
$matrixPath = 'tests/milestones/m5/fault-matrix.yaml'
$matrixDigest = 'sha256:' + (Get-FileHash -Algorithm SHA256 $matrixPath).Hash.ToLowerInvariant()
$outputDirectory = 'build/milestones/m5'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$evidencePath = Join-Path $outputDirectory 'strict-recovery-report.dsse.json'
$summaryPath = Join-Path $outputDirectory 'strict-recovery-summary.json'

$accordctl = if ($IsWindows) { 'cmd/accordctl/build/install/accordctl/bin/accordctl.bat' } else { 'cmd/accordctl/build/install/accordctl/bin/accordctl' }
& $accordctl ops chaos run `
  --matrix $matrixPath `
  --environment staging `
  --expected-environment-id $EnvironmentId `
  --release $releaseCommit `
  --release-bundle $ReleaseBundleDigest `
  --provider-baseline-digest $ProviderBaselineDigest `
  --output $evidencePath `
  --confirm-drill

& $accordctl ops chaos verify `
  --evidence $evidencePath `
  --release $releaseCommit `
  --release-bundle $ReleaseBundleDigest `
  --expected-environment-id $EnvironmentId `
  --expected-provider-baseline-digest $ProviderBaselineDigest `
  --matrix $matrixPath `
  --schema contracts/json-schema/milestone-m5-strict-recovery-report.schema.json `
  --summary-output $summaryPath

$report = Get-Content -Raw $summaryPath | ConvertFrom-Json -Depth 64
if ($report.report_type -ne 'accord.milestone.m5.strict-recovery.v1') { throw 'recovery report type mismatch' }
if ($report.source_commit_sha -ne $releaseCommit) { throw 'recovery report release mismatch' }
if ($report.certified_release_bundle_digest -ne $ReleaseBundleDigest) { throw 'recovery report bundle mismatch' }
if ($report.immutable_environment_id -ne $EnvironmentId) { throw 'recovery report environment mismatch' }
if ($report.provider_baseline_digest -ne $ProviderBaselineDigest) { throw 'recovery report provider baseline mismatch' }
if ($report.matrix_digest -ne $matrixDigest) { throw 'recovery report matrix mismatch' }
if ($report.normal_merge_controller_ratio -ne 1.0) { throw 'strict merge actor breach' }
foreach ($subjectType in @('work_item_pr', 'accepted_delivery_candidate', 'emergency_change')) {
  if ($report.merge_subject_authorization_counts.$subjectType -lt 1) { throw "strict subject was not exercised: $subjectType" }
}
if ($report.authorization_replay_total -ne 0) { throw 'merge authorization replayed' }
if (-not $report.all_scenarios_converged) { throw 'one or more recovery scenarios did not converge' }
if (-not $report.provider_baseline_restored) { throw 'provider baseline was not restored' }
if (@($report.scenario_evidence | Where-Object { $_.result -ne 'converged' -or $_.forbidden_outcome_count -ne 0 }).Count -ne 0) {
  throw 'scenario retained a non-converged result or forbidden outcome'
}
if ($report.uncertain_result_state -ne 'suspended+reconciliation_required') { throw 'uncertainty did not fail closed' }
if ($report.break_glass_assurance_state -ne 'degraded') { throw 'break glass retained strict claim' }
if (-not $report.strict_restored_after_new_context_candidate_acceptance) { throw 'strict recovery incomplete' }
if ($report.wrong_completed_total -ne 0) { throw 'false completion observed' }
```

- [ ] **Step 2: Run the M5 exercise before strict and recovery paths exist**

Run: `pwsh -NoProfile -File tests/e2e/milestones/m5-strict-recovery.ps1`

Expected: FAIL because the fault matrix, Merge Controller sandbox, signed `run` output, or `verify` interface is missing; it must never fail merely because `$report` was uninitialized.

- [ ] **Step 3: Complete strict delivery and define the fault matrix**

Execute this exact sequence:

1. Complete Git Delivery Tasks 9-11 in order; Tasks 1-8 were completed at M3 and must not be repeated.
2. Run `pnpm api:generate && pnpm --filter @accord/api-client check:generated` against the completed strict/recovery API.
3. Complete Web Experience Tasks 16-19 in order. Task 19 establishes the app-owned Playwright configuration before any Operations task invokes it.
4. Complete Production Operations Tasks 1-10 in order. This continuous prefix owns observability, source-boundary controls, SLO/capacity, restore/DR/key/supply-chain drills, fault injection, and on-call readiness; do not jump directly to Operations Task 9. Before committing Operations Task 9, extend its command registry and runner with the exact `ops chaos run --matrix --environment --expected-environment-id --release --release-bundle --provider-baseline-digest --output --confirm-drill` and `ops chaos verify --evidence --release --release-bundle --expected-environment-id --expected-provider-baseline-digest --matrix --schema --summary-output` interfaces used above. `--release-bundle` is mandatory and becomes `certified_release_bundle_digest`; no runner or verifier may infer a protected binding from a mutable file, ambient alias, or the report under verification.

Apply these normative OpenTofu integration requirements while executing Operations Tasks 4-7; they replace any root-level `validate` command that would otherwise inspect an empty configuration:

- Operations Task 4 creates and commits four independent roots: the non-deployable integration root `infra/opentofu/`, the organization-governance-backed `infra/opentofu/bootstrap/aws-state-backends/`, and the deployable Tokyo/Osaka roots under `infra/opentofu/environments/`. Every root owns `versions.tf`, `backend.tf`, and a committed dual-platform `.terraform.lock.hcl`; all pin OpenTofu `= 1.9.1`, AWS `= 5.100.0`, and only the integration root also pins Kubernetes `= 2.36.0`. Generate each lock once with its root-specific `providers lock -platform=linux_amd64 -platform=windows_amd64` command and never generate/update a lock during certification.
- `roots.schema.json` is closed and `roots.json` contains exactly `integration-test`, `aws-state-backends`, `aws-tokyo-primary`, and `aws-osaka-warm-dr`. Each entry has only immutable `id`, repository-relative `path`, backend ownership, tracked lock, `tests`, exact providers, and an ordinal-sorted module array that must equal parsed static local HCL sources. A fifth/backend root, dynamic or non-local module source, production module exercised only by integration, untracked/stale lock, or source-array mismatch fails.
- Operations Tasks 4-7 grow the union deterministically from 12 to 16 to 17 to the final 18 modules: `accord-autoscaling`, `accord-foundation-contract`, `aws-agent-pack-distribution`, `aws-backup`, `aws-budgets`, `aws-container-registry`, `aws-edge`, `aws-eks`, `aws-network`, `aws-object-storage`, `aws-operations-evidence`, `aws-postgresql`, `aws-purpose-keys`, `aws-regional-dr`, `aws-state-backends`, `aws-telemetry`, `aws-temporal`, and `aws-workload-identity`. Every module is an explicit integration-root source and at least one production/bootstrap-root source. Gateway distribution additionally proves immutable OCI replication, region-local encrypted object cache, exact IRSA, fixed egress and epoch fencing. PostgreSQL modules provision the durable coordination schemas, leases, and fencing tokens; no cache service is authoritative or required.
- `verify-opentofu.ps1 -Manifest infra/opentofu/roots.json` first invokes the HCL/JSON root verifier, requires exactly OpenTofu `1.9.1`, resolves all paths beneath the repository, proves each lock tracked and unchanged, then runs `init -backend=false -input=false -lockfile=readonly`, `fmt -check`, `validate`, and `test` only for declared test roots. It rejects missing/extra roots or modules, symlink/path escape, mutable provider selections, initialization changes, real backend/apply attempts, and any lock diff. Every filtered `tofu test` command runs this verifier first; the final result is exactly `roots=4 modules=18`.

Create `fault-matrix.yaml` with `schema_version: "1.0"`, immutable `report_type: accord.milestone.m5.strict-recovery.v1`, and scenarios `strict-work-item-merge`, `strict-accepted-candidate-merge`, `worker-crash-before-call`, `worker-crash-after-call`, `webhook-gap`, `provider-throttle`, `provider-result-unknown`, `protection-drift`, `non-controller-write`, `context-patch-gap`, `artifact-promotion-unknown`, `abort-inflight-merge`, `emergency-hotfix`, and `break-glass`. `strict-work-item-merge`, `strict-accepted-candidate-merge`, and `emergency-hotfix` prove the three closed current-v2 `VerifiedMergeSubject` types against protected refs, exact fact bindings, distinct purpose-bound authorization, one-time reservation/finalization, and current Provider result. Branch creation is exercised separately as an exact zero-diff create-ref capability and never counted as a merge subject. Historical v1 `REQUIREMENT_METADATA` fixtures remain verification-only compatibility evidence and cannot be issued, reserved, merged, or counted by this current-protocol report. Every row declares injection, expected failed-closed state, source of current facts, convergence proof, forbidden outcome, maximum detection, and retained audit evidence. The matrix also names the exact current-fact queries from which the runner derives `uncertain_result_state`, `break_glass_assurance_state`, `strict_restored_after_new_context_candidate_acceptance`, `merge_subject_authorization_counts`, `authorization_replay_total`, `all_scenarios_converged`, and `provider_baseline_restored`; these values are never supplied as CLI inputs.

Create `milestone-m5-strict-recovery-report.schema.json` as the closed JSON Schema 2020-12 M5 profile over `contracts/json-schema/operations-chaos-report.schema.json`. Its `allOf` references that base schema and then requires `report_type` to equal `accord.milestone.m5.strict-recovery.v1`; the base schema remains the single owner of `additionalProperties: false`, scalar patterns, and common field definitions. Require the complete common report fields: `schema_version`, `report_type`, `certified_release_bundle_digest`, full `source_commit_sha`, `logical_environment`, immutable `immutable_environment_id`, `tool_artifact_digest`, `policy_digest`, `input_set_digest`, `provider_baseline_digest`, `matrix_digest`, `fixture_set_digest`, `evidence_index_digest`, `audit_correlation_id`, `run_id`, `started_at`, `completed_at`, `retention_until`, `result`, `normal_merge_controller_ratio`, `merge_subject_authorization_counts`, `authorization_replay_total`, `all_scenarios_converged`, `provider_baseline_restored`, `forbidden_outcome_total`, `wrong_completed_total`, and nonempty `scenario_evidence`. `merge_subject_authorization_counts` is a closed object requiring exactly nonnegative integer keys `work_item_pr`, `accepted_delivery_candidate`, and `emergency_change`; a passing M5 profile requires each to be at least one and rejects any historical or unknown key. The generic Operations-only `agent_pack_distribution_outcomes` field is forbidden in this M5 profile. M5 additionally requires `uncertain_result_state`, `break_glass_assurance_state`, and `strict_restored_after_new_context_candidate_acceptance`. Each scenario entry binds scenario ID, current-fact source, injection receipt digest, convergence evidence digest, cleanup/baseline-restoration digest, result, intermediate states, forbidden-outcome count, and start/end time. Require SHA-256 and Git-SHA patterns, UUID run/audit correlation IDs, ratios in 0-1, nonnegative counters, at least 400 days of retention, and exact release-bundle/environment bindings; the verifier performs cross-field time-order, exact-scenario, subject-count/evidence correspondence, zero replay/forbidden/wrong-completion totals, exact matrix membership, and digest closure checks that JSON Schema cannot express.

`ops chaos run` resolves the logical environment to `expected-environment-id`, binds the mandatory signed release bundle to the exact source commit, rejects a missing `--confirm-drill` before provider access, verifies the signed provider baseline before injection, hashes the matrix and all referenced fixtures, runs every row, restores and verifies the exact baseline, signs the closed report, and atomically writes the DSSE envelope. A deliberately injected unknown Provider result may exit `0` only after the durable failed-closed observation is followed by authoritative reconciliation to `converged`, every forbidden-outcome count is zero, evidence is persisted, and the exact Provider baseline is restored. A known `diverged`/final `uncertain` result, incomplete-but-known cleanup, or baseline mismatch still writes retained signed failure evidence and exits `1`; exit `2` is invalid invocation/preflight, while exit `3` is reserved for inability to determine whether injection, durable evidence persistence, or cleanup occurred. None can satisfy M5 or GA. The signing trust domain is the platform-level operations evidence signer defined by Operations Task 9, the DSSE `payloadType` is `application/vnd.accord.operations-chaos-report.v1+jcs`, and the signed payload's `report_type` is the M5 constant above; these three values are distinct and all are verified.

`ops chaos verify` validates the selected closed schema, platform signer identity/purpose/payloadType/trust time, payload `report_type`, exact source-commit/release-bundle/environment/provider bindings, scenario evidence digests, completeness, and time ordering before writing canonical payload JSON to `summary-output`. It takes those four protected expectations explicitly, independently hashes the exact regular non-symlink matrix path and every closed relative fixture reference, and requires the resulting matrix/fixture digests to match the signed report; no expected value comes from the report itself. For the M5 profile it additionally requires every expected scenario exactly once, every result `converged`, every scenario and aggregate forbidden-outcome count zero, all three current-v2 subject counts backed by distinct authorization/reservation/finalization/current-result receipts, replay and wrong-completed totals zero, and exact baseline restoration; an authentic signed failure report therefore verifies as retained evidence but returns a non-passing exit and cannot produce the success summary consumed above. The signed report contains a preassigned `audit_correlation_id`, not a circular audit-event digest. After immutable envelope storage, an external audit receipt binds that correlation ID to the envelope digest, object version, and retention deadline; the verifier resolves and checks the receipt independently. Tests cover changed reports, wrong release bundle/commit/environment, stale baselines, changed matrix/fixture bytes, missing/non-converged/duplicate scenarios, historical/unknown subject keys, subject type or digest confusion, replay, forged signatures, missing/mismatched audit receipts, and output-path failures; stdout is not parsed as evidence.

Create `gate.yaml`:

```yaml
milestone: M5
requires: [M4]
gates:
  - strict-protection-capability-proof
  - one-time-exact-authorization-for-every-current-v2-verified-merge-subject
  - one-hundred-percent-controller-normal-merges
  - metadata-only-reconciliation-and-convergence
  - abort-only-before-default-merge-with-no-inflight-intent
  - controlled-hotfix-and-baseline-forward-reconciliation
  - break-glass-two-person-expiry-nonce-and-degraded-assurance
  - strict-restoration-requires-context-candidate-and-reacceptance
zero_tolerance: [normal-non-controller-strict-merge, duplicate-effect, wrong-completed]
```

- [ ] **Step 4: Run strict certification and every recovery scenario**

Run: `./gradlew :security-services:merge-controller:test :apps:webhook-edge:test :apps:agent-pack-gateway:test :cmd:accordctl:test :cmd:accordctl:installDist && ./gradlew :tests:integration:dependencies --configuration milestoneTestRuntimeClasspath --write-locks && ./gradlew :apps:control-plane:modules:delivery:test :apps:control-plane:modules:git-coordination:test :tests:integration:milestoneTest && pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json && helm dependency build infra/helm/accord --skip-refresh && git diff --exit-code -- infra/helm/accord/Chart.lock && helm lint infra/helm/agent-pack-gateway && helm lint infra/helm/accord && pwsh -NoProfile -File tests/e2e/milestones/m5-strict-recovery.ps1`

Expected: PASS; all normal strict merges name only the Merge Controller, every uncertain action suspends before reconciliation, abort never hides a merged result, break-glass is visibly degraded, and restoration requires a new trusted chain. The infrastructure verifier reports exactly four roots and 18 modules, the central chart renders exactly one isolated Agent Pack Gateway from its locked component dependency, and the Operations fault report's scenarios retain zero Pack capability/digest/epoch and delivery-integrity violations.

- [ ] **Step 5: Commit the M5 integration gate**

```bash
git add tests/e2e/milestones/m5-strict-recovery.ps1 tests/integration/gradle.lockfile contracts/json-schema/milestone-m5-strict-recovery-report.schema.json cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCli.java cmd/accordctl/src/main/java/com/inforvans/accord/cli/ops/chaos cmd/accordctl/src/test/java/com/inforvans/accord/cli/ops/chaos tests/milestones/m5 docs/operations/milestones/m5-demo.md
git commit -m "test: certify M5 strict delivery and recovery"
```

### Task 8: Certify M6 Spec Coverage, Cross-Role E2E, And GA Handoff

**Files:**
- Modify: `.gitignore`
- Create: `tests/milestones/playwright/m6-complete-v1-journey.spec.ts`
- Modify: `tests/integration/gradle.lockfile`
- Create: `tests/milestones/m6/spec-coverage.yaml`
- Create: `tests/milestones/m6/ga-gate.yaml`
- Create: `tests/milestones/verify-master-plan.mjs`
- Create: `docs/operations/milestones/m6-production-rehearsal.md`
- Create: `docs/user-guides/business-user.md`
- Create: `docs/user-guides/developer.md`
- Create: `docs/user-guides/acceptance-owner.md`
- Create: `docs/user-guides/administrator.md`
- Create: `docs/user-guides/auditor.md`

- [ ] **Step 1: Write the failing master coverage and cross-role tests**

`verify-master-plan.mjs` has two explicit surfaces. `node --test` exercises valid, missing, stale, forged-signature, wrong-release, wrong-release-bundle, wrong-environment, wrong restore point, wrong Provider baseline, wrong-unit, wrong or replayed `certification_run_id`, missing or forged run-evidence wrapper, wrong wrapped source digest/object version/receipt, wrong expected index digest, wrong expected index object version, digest-mismatch, duplicate-index, path-traversal, and symlink-escape fixtures created in temporary directories.

Its live CLI accepts exactly `--coverage`, `--gate`, `--index`, `--expected-index-digest`, `--expected-index-object-version`, `--certification-run-id`, `--release`, `--release-bundle`, `--expected-environment-id`, `--expected-restore-point-rfc3339`, and `--expected-provider-baseline-digest`; it never searches an evidence directory or ambient environment for a convenient binding. It requires keys `section_01` through `section_21`, `appendix_a` through `appendix_d`, the exact specification digest, existing committed plan/fixture references, and a logical `evidence_type` for every row, then verifies the explicit signed content-addressed index and resolves only entries bound to the requested certification run, source commit, release bundle, immutable environment, restore point, Provider baseline, and eligible certification unit.

The unit surface also covers the `contractual-terms` logical type and `contractual_terms_pass` gate with missing, stale, not-yet-effective, expired, revoked, wrong-locale/translation digest, wrong term digest, a tenant-neutral package that self-references its source commit or release bundle, wrong package-publisher or acceptance-attestor signer purpose, wrong run/release/environment/unit/customer authority/approval, insufficient retention, missing Object Lock version/receipt, and absent or cross-unit terms-acceptance fixtures. Each negative case derives at most `limited_availability`; an embedded `result=pass`, administrator input, or another tenant/unit/run's valid acceptance cannot change it.

Create two tests in `m6-complete-v1-journey.spec.ts`. `@m6-pre-collection` uses separate authenticated sessions for business contributor, business principal, development lead, developer, acceptance owner, administrator, and auditor to execute intake through exact completion and record the release-bound journey gate. `@m6-post-collection` proves those restricted roles can inspect the derived GA status and downloadable evidence after collection without an all-powerful test identity or direct evidence-store access. The file remains under the isolated milestone Playwright directory, not `apps/web/tests`.

- [ ] **Step 2: Run M6 verification before operations evidence and documentation exist**

Run each command independently so all red results are recorded:

```bash
node --test tests/milestones/verify-master-plan.mjs
node tests/milestones/verify-master-plan.mjs --coverage tests/milestones/m6/spec-coverage.yaml --gate tests/milestones/m6/ga-gate.yaml --index "$GA_INDEX_PATH" --expected-index-digest "$GA_INDEX_DIGEST" --expected-index-object-version "$GA_INDEX_OBJECT_VERSION" --certification-run-id "$ACCORD_CERTIFICATION_RUN_ID" --release $(git rev-parse HEAD) --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --expected-restore-point-rfc3339 "$ACCORD_RESTORE_POINT_RFC3339" --expected-provider-baseline-digest "$ACCORD_PROVIDER_BASELINE_DIGEST"
pnpm exec playwright test --config tests/milestones/playwright.config.ts --grep @m6-post-collection
```

Expected: the unit surface initially fails until strict index validation and the explicit `contractual-terms`/`contractual_terms_pass` evaluator exist; the live surface fails with a missing current-release signed index, and the browser surface fails because no derived GA bundle is available. A flat evidence filename, prose-only data-terms claim, stale bundle, or one unit's terms acceptance reused for another unit must not make either latter command pass.

- [ ] **Step 3: Execute the operations plan and close every coverage row**

Tasks 1-10 were completed at M5 and must not be repeated. Execute this exact remaining sequence:

1. Complete Production Operations Tasks 11-13 in order.
2. Implement the master verifier, coverage/gate files, M6 journey, role guides, rehearsal guide, and evidence ignore rules described here. Add `certification/evidence/*` and the negation `!certification/evidence/signature-policy.yaml` to `.gitignore`; generated local evidence is never source-controlled.
3. Complete only Production Operations Task 14 Steps 1-4 against these master inputs and commit that implementation; defer its Step 5 remote rehearsal until after the final candidate is frozen below. The Task 14 implementation commit is a predecessor, not a release candidate and not an admissible `source_commit`. Its component tests are integration evidence only. Require `certification-gate.spec.ts` to carry `@post-collection`; require `ga collect` to emit the signed index contract below; require the `ga-verifier` protected job to invoke the master verifier with the same certification run ID plus the immutable index path, digest, and object version handed off by `ga-collector`; and require the separate approval and per-unit promotion workflows, jobs, policies, coordination store, fencing, reconciliation, and external read-only promotion-rehearsal verification surfaces to exist before this step is committed. Do not dispatch `ga-certification.yaml`, `ga-approval.yaml`, or `ga-promotion.yaml` yet. No local shell, administrator, or combined workflow job may certify, approve, or promote a release.
4. Complete Web Experience Task 20 after the operational API, certification view, and role documentation are present.
5. Refresh `milestoneTestRuntimeClasspath` with `./gradlew :tests:integration:dependencies --configuration milestoneTestRuntimeClasspath --write-locks`, then run the verifier unit surface. Both must be green before the release-candidate commit. No tracked file may change after Step 4 below. Any failed integrity or business predicate that requires changing candidate bytes or a protected binding requires a new candidate commit, release bundle, certification run, approval session, and promotion ID; a recoverable external uncertainty keeps the original immutable candidate/request and follows only its fenced reconciliation path.

Populate `spec-coverage.yaml` with this schema:

```yaml
digest_normalization: utf8-lf
specification:
  path: requirements-agent-platform-design.md
  sha256: 0755A08228254311588EE0B867AFEBED6CD49E26B0FCCB790247BFDA31AB2C77
runtime_design:
  path: docs/superpowers/specs/2026-07-25-accord-java-python-runtime-design.md
  sha256: 46DE7309CB28F8E59B3FDAB4D6FA664AE4D932FC7F517741681CC25C3B84E4E1
evidence_resolution:
  root: certification/evidence
  digest_directory_encoding: sha256-<64-lowercase-hex>
  run_directory_encoding: canonical-lowercase-rfc4122-uuid
  path_binding_order: [release_bundle_digest, certification_run_id, evidence_scope]
  signed_index_name: evidence-index.dsse.json
  release_binding: source_commit_sha
  run_binding: certification_run_id
  restore_point_binding: restore_point_rfc3339
  provider_baseline_binding: provider_baseline_digest
coverage:
  section_01: { plan: accord-enterprise-v1-master, gate: complete-v1-journey, evidence_type: product-journey }
  section_02: { plan: accord-identity-tenancy-audit-and-web-experience, gate: cross-role-and-delivery-mode-journeys, evidence_type: roles-and-modes }
  section_03: { plan: accord-platform-foundation, gate: core-principle-invariants, evidence_type: core-principles }
  section_04: { plan: accord-platform-foundation, gate: canonical-topology-and-contract-boundaries, evidence_type: architecture-boundaries }
  section_05: { plan: accord-requirement-workflow, gate: requirement-graph-properties, evidence_type: requirement-graph }
  section_06: { plan: accord-requirement-workflow-and-web-experience, gate: structured-intake-canvas-and-dual-view, evidence_type: intake-and-views }
  section_07: { plan: accord-agent-context-assessment, gate: signed-pack-context-lineage-and-rebuild, evidence_type: agent-pack-and-context }
  section_08: { plan: accord-agent-context-assessment, gate: assessment-policy-score-and-admission, evidence_type: assessment }
  section_09: { plan: accord-requirement-workflow, gate: proposal-revision-and-ordered-confirmation, evidence_type: collaboration }
  section_10: { plan: accord-git-delivery-control, gate: ready-pool-batch-and-commitment, evidence_type: delivery-batch }
  section_11: { plan: accord-git-delivery-control, gate: package-branch-release-standard-and-strict-modes, evidence_type: git-delivery }
  section_12: { plan: accord-git-delivery-control-and-agent-context-assessment, gate: workitem-completion-and-context-patch, evidence_type: workitem-and-patch }
  section_13: { plan: accord-candidate-acceptance, gate: candidate-acceptance-correction-and-promotion, evidence_type: candidate-and-acceptance }
  section_14: { plan: accord-identity-tenancy-audit, gate: rbac-delegation-and-separation, evidence_type: authorization }
  section_15: { plan: accord-requirement-workflow-and-web-experience, gate: action-notification-and-attachment-security, evidence_type: actions-and-attachments }
  section_16: { plan: accord-enterprise-v1-master, gate: cross-aggregate-state-and-completion-properties, evidence_type: state-model }
  section_17: { plan: accord-identity-tenancy-audit-and-platform-foundation, gate: trust-isolation-signature-and-audit, evidence_type: trust-and-audit }
  section_18: { plan: accord-git-delivery-control-and-production-operations-ga, gate: failure-reconciliation-and-emergency-matrix, evidence_type: recovery }
  section_19: { plan: accord-production-operations-ga, gate: product-model-workflow-and-slo-validation, evidence_type: production-validation }
  section_20: { plan: accord-enterprise-v1-master-and-production-operations-ga, gate: m0-through-m6-and-ga-prerequisites, evidence_type: ga-prerequisites }
  section_21: { plan: accord-enterprise-v1-master, gate: design-conclusion-traceability, evidence_type: design-conclusions }
  appendix_a: { fixture: contracts/golden-fixtures/requirement-contract/appendix-a.json, evidence_type: contract-vector }
  appendix_b: { fixture: contracts/golden-fixtures/assessment-policy/appendix-b.json, evidence_type: assessment-vector }
  appendix_c: { fixture: contracts/golden-fixtures/context-patch/appendix-c.json, evidence_type: context-patch-vector }
  appendix_d: { plan: accord-web-experience, gate: business-terminology-mapping-snapshot-accessibility-and-translation-review, evidence_type: business-terminology-mapping }
ga_required_evidence:
  contractual_terms:
    plan: accord-production-operations-ga
    gate: versioned-signed-contractual-terms-per-eligible-unit
    evidence_type: contractual-terms
    scope: certification-unit
    report_gate_field: contractual_terms_pass
```

`ga collect` reads evidence only beneath `certification/evidence/sha256-<bundle-hex>/<certification-run-id>/<certification-unit-id-or-global>/` while retaining `sha256:<bundle-hex>` and the canonical UUID `certification_run_id` inside signed payloads, and writes the one signed index only to its explicit `--output` path before storing the identical object in the retention-locked certification store. The signed payload binds schema version, certification run ID, release commit, release-bundle digest, immutable environment ID, exact protected restore point, signed Provider baseline digest, eligible certification units, the predecessor rehearsal receipt-set digest/object version, and entries containing logical evidence type, scope (`global` or exact unit), relative path, media type, SHA-256, generation time, and signer purpose. A reusable operational source envelope is admissible only through the Task 14 signed run-evidence wrapper that binds this run and both protected inputs to the source envelope digest, immutable object version, and receipt digest; copying source bytes beneath the run prefix is not a binding. The ignored local tree and workflow artifact are caches; only the protected handoff's immutable object version/digest and the retained receipt it identifies are authoritative.

The live verifier opens only the explicit `--index`, requires its path to be a regular non-symlink file beneath the protected job workspace, rehashes those exact bytes against `--expected-index-digest`, resolves their Object Lock receipt and version, and requires digest, version, restore point, and Provider baseline to match the explicit expected flags and protected collector handoff before following any entry. It verifies DSSE trust time and signature policy and rejects wrong certification-run/release/release-bundle/environment/restore-point/provider-baseline bindings, unknown/duplicate logical types, absolute or escaping paths, symlinks, digest or unit mismatches, stale evidence, ineligible-unit substitution, and any evidence not beneath the indexed bundle/run/scope. For reusable operational source evidence it additionally verifies the signed run-evidence wrapper, re-resolves and rehashes the exact source envelope/object version/receipt, and rejects any cross-run, cross-input, or unsigned path association. Every globally required type must exist once; every unit-scoped type must exist for every eligible unit. `ga-gate.yaml` names the same logical types for product journey, security, source boundary, supply chain, SLO window, capacity/cost, backup/PITR, regional DR, key rotation/compromise, four-root/18-module applied IaC, locked central Helm inventory with exactly one Gateway, chaos convergence, on-call, model threshold, value threshold, role documentation, Appendix D business terminology/accessibility/translation review, support ownership, release rollback, and the explicit contractual gate below; prose such as `data terms complete` is not evidence. Documentation states mode guarantees, score interpretation, attachment/source boundary, escalation, retention/deletion, and emergency effects for the appropriate role.

```yaml
milestone: M6
requires: [M5]
gates:
  versioned-signed-contractual-terms-per-eligible-unit:
    evidence_type: contractual-terms
    scope: certification-unit
    required_for_each_eligible_unit: true
    report_gate_field: contractual_terms_pass
    pass_requires_independent_verification: true
    failure_max_status: limited_availability
    administrator_override_allowed: false
```

The gate resolves a stable, tracked, closed JSON Schema 2020-12 tenant-neutral package whose bytes contain no release-bundle digest, source commit, certification run, tenant/customer identifier, or other downstream back-reference. It verifies that package's RFC 8785 JCS/DSSE envelope only under the tag-bound `contract-terms-package-publisher` identity and KMS purpose `contractual_terms_package`, then verifies the exact unit acceptance only under the protected runtime `contract-acceptance-attestor` identity and distinct KMS purpose `contractual_terms_acceptance`; either purpose used for the other payload fails. The acceptance binds the certification run ID, exact release bundle, source commit, immutable environment, certification unit and tenant/customer, all six certification dimensions, package and term digests, customer authority proof, Legal/Privacy/Product Security/Support approvals, locale/translation digest, effective/expiry/revocation state, compliance Object Lock version, immutable receipt, retention, and freshness. The gate independently proves the one-way chain `terms package bytes -> package digest -> release bundle digest -> unit acceptance`, recomputes every predicate from indexed bytes, and never trusts an artifact's `result`, `accepted`, or precomputed gate field.

- [ ] **Step 4: Freeze the final immutable M6 release candidate**

```bash
git add .gitignore tests/milestones/playwright/m6-complete-v1-journey.spec.ts tests/integration/gradle.lockfile tests/milestones/m6 tests/milestones/verify-master-plan.mjs docs/operations/milestones/m6-production-rehearsal.md docs/user-guides
git diff --cached --name-only --diff-filter=ACMR
git commit -m "test: freeze complete Accord Enterprise V1 candidate"
test -z "$(git status --porcelain=v1)"
```

Expected: this final HEAD contains every preceding subsystem and Production Operations implementation commit plus every lock, test, mapping, and role document needed for certification, while `certification/evidence/**` contains no tracked runtime output. This exact HEAD, not the earlier Task 14 implementation commit, is the only `source_commit` accepted by the protected release build, release-artifact handoff, release bundle, rehearsal, certification, approval, promotion request, and final receipt. The Production Operations Task 8 protected supply-chain workflow builds, signs, and publishes the release manifest, manifest Cosign bundle, `accordctl` provenance, and immutable release receipt for this exact commit, then emits the closed strings-only `release/artifact-handoff.schema.json` locator. That handoff is unsigned and is never authorization: every consumer resolves its exact content-addressed object versions and independently verifies their checksums, retention, signatures, provenance, source/release binding, and authoritative release-author object. From this point through release promotion, do not amend the commit or change tracked files; failed verification produces a new candidate commit and release bundle, and invalidates every downstream run, artifact handoff, approval, and promotion identity derived from the old candidate.

- [ ] **Step 5: Run immutable preflight, protected certification, dual-control approval, and isolated GA promotion**

Run this implementation preflight in PowerShell 7.4 from a clean checkout of the Step 4 commit. This block may prove that the candidate is eligible for certification, but it has no GA signing identity, does not create a production evidence index or verification report, and cannot derive or publish a GA status:

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$accordReleaseCommit = (git rev-parse --verify 'HEAD^{commit}').Trim()
$accordReleaseBundle = $env:ACCORD_RELEASE_BUNDLE_DIGEST
$accordEnvironmentId = $env:ACCORD_STAGING_ENVIRONMENT_ID
$accordCertificationRunId = $env:ACCORD_CERTIFICATION_RUN_ID
$accordRestorePoint = $env:ACCORD_RESTORE_POINT_RFC3339
$accordProviderBaseline = $env:ACCORD_PROVIDER_BASELINE_DIGEST
$accordParsedCertificationRunId = [Guid]::Empty
$accordParsedRestorePoint = [DateTimeOffset]::MinValue
if ($accordReleaseCommit -cnotmatch '^[0-9a-f]{40}$') { throw 'release commit is invalid' }
if ($accordReleaseBundle -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'release bundle digest is invalid' }
if ([string]::IsNullOrWhiteSpace($accordEnvironmentId)) { throw 'immutable environment ID is required' }
if (-not [Guid]::TryParseExact($accordCertificationRunId, 'D', [ref]$accordParsedCertificationRunId) -or $accordCertificationRunId -cne $accordParsedCertificationRunId.ToString('D')) { throw 'certification run ID must be a canonical lowercase UUID' }
if ($accordRestorePoint -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$' -or
    -not [DateTimeOffset]::TryParse($accordRestorePoint, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind, [ref]$accordParsedRestorePoint)) { throw 'restore point must be an explicit RFC 3339 timestamp' }
if ($accordProviderBaseline -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Provider baseline digest is invalid' }
if (git status --porcelain --untracked-files=all) { throw 'release checkout is not clean' }

function Get-AccordNormalizedSha256([string]$Path) {
  $accordUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
  $accordBytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
  $accordNormalizedBytes = $accordUtf8.GetBytes($accordUtf8.GetString($accordBytes).Replace("`r`n", "`n"))
  $accordSha256 = [System.Security.Cryptography.SHA256]::Create()
  try {
    return [BitConverter]::ToString($accordSha256.ComputeHash($accordNormalizedBytes)).Replace('-', '')
  } finally {
    $accordSha256.Dispose()
  }
}
$accordSpecHash = Get-AccordNormalizedSha256 requirements-agent-platform-design.md
if ($accordSpecHash -cne '0755A08228254311588EE0B867AFEBED6CD49E26B0FCCB790247BFDA31AB2C77') { throw 'specification digest mismatch' }
$accordRuntimeDesignHash = Get-AccordNormalizedSha256 docs/superpowers/specs/2026-07-25-accord-java-python-runtime-design.md
if ($accordRuntimeDesignHash -cne '46DE7309CB28F8E59B3FDAB4D6FA664AE4D932FC7F517741681CC25C3B84E4E1') { throw 'runtime design digest mismatch' }

corepack pnpm@10.12.4 install --frozen-lockfile
node --test tests/architecture/v1-boundaries.test.mjs tests/milestones/verify-master-plan.mjs
node --test tests/contracts/openapi-cumulative-merge.test.mjs
corepack pnpm@10.12.4 --filter @accord/api-client check:generated
corepack pnpm@10.12.4 lint
corepack pnpm@10.12.4 typecheck
./gradlew clean test :tests:integration:milestoneTest
./gradlew :apps:webhook-edge:test :apps:agent-pack-gateway:test :apps:attachment-scanner:test :security-services:signing-service:test :security-services:provider-connector:test :security-services:credential-broker:test :security-services:merge-controller:test :security-services:break-glass-broker:test :cmd:accordctl:test :cmd:accordctl:jlink
uv run --project apps/agent-runtime pytest apps/agent-runtime/tests -q
corepack pnpm@10.12.4 -r test
corepack pnpm@10.12.4 build:web
node apps/web/scripts/check-bundle.mjs
corepack pnpm@10.12.4 test:system
corepack pnpm@10.12.4 test:security
corepack pnpm@10.12.4 test:fault
corepack pnpm@10.12.4 test:provider
corepack pnpm@10.12.4 --filter @accord/web test:e2e -- --grep-invert '@post-collection'
corepack pnpm@10.12.4 exec playwright test --config tests/milestones/playwright.config.ts --grep-invert '@m6-post-collection'
pwsh -NoProfile -File tests/e2e/milestones/m2-agent-context-assessment.ps1
pwsh -NoProfile -File tests/e2e/milestones/m5-strict-recovery.ps1
buf lint
pwsh -NoProfile -File tests/architecture/verify-protobuf-compatibility.ps1
pwsh -NoProfile -File scripts/ci/verify-opentofu.Tests.ps1
pwsh -NoProfile -File scripts/ci/verify-opentofu.ps1 -Manifest infra/opentofu/roots.json
tofu -chdir=infra/opentofu fmt -check -recursive
tofu -chdir=infra/opentofu test
helm dependency build infra/helm/accord --skip-refresh
git diff --exit-code -- infra/helm/accord/Chart.lock
helm lint infra/helm/agent-pack-gateway
helm lint infra/helm/accord
conftest test infra/helm infra/opentofu infra/argocd -p infra/policy
conftest verify -p infra/policy/ci infra/policy/ci/ga-separation_test.yaml
conftest verify -p infra/policy/supply-chain infra/policy/supply-chain/admission_test.yaml
conftest test .github/workflows/ga-certification.yaml .github/workflows/ga-approval.yaml .github/workflows/ga-promotion.yaml .github/workflows/contract-terms-package-publish.yaml .github/workflows/contract-acceptance-attest.yaml operations/ga/jobs -p infra/policy/ci
helm unittest infra/helm/accord -f 'tests/ga-control-lanes.yaml'
tofu -chdir=infra/opentofu test -filter=tests/ga-control-lanes.tftest.hcl
promtool check rules operations/alerts/*.yaml
bash scripts/ga/launch-immutable-job.test.sh
bash scripts/release/verify-accordctl.test.sh

if ((git rev-parse HEAD).Trim() -ne $accordReleaseCommit) { throw 'release commit changed during preflight' }
if (git status --porcelain --untracked-files=all) { throw 'preflight changed tracked or unignored files' }
```

The cumulative OpenAPI test verifies the final ownership manifest and merged surface exactly: Identity `72`, Requirement `47`, Agent `53`, Provider Onboarding `12`, Delivery `24`, and Candidate `26`. A missing, duplicate, reassigned, or unowned operation fails preflight before any protected GA lane starts. The separate Provider callback-edge OpenAPI is verified independently and cannot appear in the browser-generated ownership union.

Before certification, release automation resolves the exact final candidate's `release-artifact-handoff.json` and downloads only its named manifest, manifest Cosign bundle, `accordctl` SLSA provenance, and immutable release receipt by exact digest and object version. It verifies the unsigned handoff schema and Object Lock identity, then independently verifies the signed manifest, Cosign issuer/workflow identity, provenance builder/materials/subjects, final `source_commit`, release-bundle digest, and canonical natural-person release author. The verified release-artifact tuple is a required immutable predecessor, not a seventh run binding; a missing body, mutable locator, cross-release object, caller-nominated author, or handoff without independent body verification stops the protocol before `ga-certification.yaml`.

After preflight, protected release automation dispatches `.github/workflows/ga-certification.yaml` from that exact commit with exactly six immutable inputs: `certification_run_id`, `source_commit`, `release_bundle_digest`, `immutable_environment_id`, `restore_point_rfc3339`, and `provider_baseline_digest`. The workflow is the only production certification orchestrator. It creates exactly the following three job IDs, each in the identically named protected environment, fresh workspace, and distinct OIDC/IRSA/KMS/object-prefix lane; no job may reuse another job's token or writable workspace.

The `staging-operations` job independently verifies the published `accordctl` artifact and runs only:

```bash
make ga-rehearse GA_ENVIRONMENT=staging GA_CERTIFICATION_RUN_ID="$ACCORD_CERTIFICATION_RUN_ID" GA_RELEASE="$ACCORD_SOURCE_COMMIT" GA_RELEASE_BUNDLE="$ACCORD_RELEASE_BUNDLE_DIGEST" GA_ENVIRONMENT_ID="$ACCORD_STAGING_ENVIRONMENT_ID" GA_RESTORE_POINT_RFC3339="$ACCORD_RESTORE_POINT_RFC3339" GA_PROVIDER_BASELINE_DIGEST="$ACCORD_PROVIDER_BASELINE_DIGEST" GA_REHEARSAL_HANDOFF_OUTPUT="$GA_REHEARSAL_HANDOFF_OUTPUT"
```

The `ga-collector` job has a default-success `needs: [staging-operations]` edge, resolves the immutable rehearsal receipt set by digest and object version, independently verifies the same published artifact, and runs only:

```bash
make ga-collect GA_ENVIRONMENT=staging GA_CERTIFICATION_RUN_ID="$ACCORD_CERTIFICATION_RUN_ID" GA_RELEASE="$ACCORD_SOURCE_COMMIT" GA_RELEASE_BUNDLE="$ACCORD_RELEASE_BUNDLE_DIGEST" GA_ENVIRONMENT_ID="$ACCORD_STAGING_ENVIRONMENT_ID" GA_RESTORE_POINT_RFC3339="$ACCORD_RESTORE_POINT_RFC3339" GA_PROVIDER_BASELINE_DIGEST="$ACCORD_PROVIDER_BASELINE_DIGEST" GA_REHEARSAL_RECEIPT_SET_PATH="$GA_REHEARSAL_RECEIPT_SET_PATH" GA_REHEARSAL_RECEIPT_SET_DIGEST="$GA_REHEARSAL_RECEIPT_SET_DIGEST" GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION="$GA_REHEARSAL_RECEIPT_SET_OBJECT_VERSION" GA_INDEX_PATH="$GA_INDEX_PATH" GA_INDEX_HANDOFF_OUTPUT="$GA_INDEX_HANDOFF_OUTPUT"
```

The `ga-verifier` job has a default-success `needs: [ga-collector]` edge, downloads the index by the collector's immutable object version and digest, independently verifies the published artifact, verifies Master coverage against that explicit local handoff, runs its own lane target, and only after a signed passing report exercises the restricted post-collection views:

```bash
node tests/milestones/verify-master-plan.mjs --coverage tests/milestones/m6/spec-coverage.yaml --gate tests/milestones/m6/ga-gate.yaml --index "$GA_INDEX_PATH" --expected-index-digest "$GA_INDEX_DIGEST" --expected-index-object-version "$GA_INDEX_OBJECT_VERSION" --certification-run-id "$ACCORD_CERTIFICATION_RUN_ID" --release "$ACCORD_SOURCE_COMMIT" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" --expected-restore-point-rfc3339 "$ACCORD_RESTORE_POINT_RFC3339" --expected-provider-baseline-digest "$ACCORD_PROVIDER_BASELINE_DIGEST"
make ga-verify GA_ENVIRONMENT=staging GA_CERTIFICATION_RUN_ID="$ACCORD_CERTIFICATION_RUN_ID" GA_RELEASE="$ACCORD_SOURCE_COMMIT" GA_RELEASE_BUNDLE="$ACCORD_RELEASE_BUNDLE_DIGEST" GA_ENVIRONMENT_ID="$ACCORD_STAGING_ENVIRONMENT_ID" GA_RESTORE_POINT_RFC3339="$ACCORD_RESTORE_POINT_RFC3339" GA_PROVIDER_BASELINE_DIGEST="$ACCORD_PROVIDER_BASELINE_DIGEST" GA_INDEX_PATH="$GA_INDEX_PATH" GA_INDEX_DIGEST="$GA_INDEX_DIGEST" GA_INDEX_OBJECT_VERSION="$GA_INDEX_OBJECT_VERSION" GA_REPORT_OUTPUT="$GA_REPORT_OUTPUT" GA_SUMMARY_OUTPUT="$GA_SUMMARY_OUTPUT"
pnpm --filter @accord/web test:e2e -- --grep '@post-collection'
pnpm exec playwright test --config tests/milestones/playwright.config.ts --grep '@m6-post-collection'
```

The three Make targets must expand to these exact, closed command surfaces; metavariables below document the interface and are not a fourth runnable certification path:

```text
ga rehearse --certification-run-id <uuid> --environment <name> --expected-environment-id <id> --release <40-lowercase-hex> --release-bundle <sha256> --restore-point-rfc3339 <timestamp> --provider-baseline-digest <sha256> --plan <rehearsal.yaml> --handoff-output <receipt-set-handoff.json> --confirm
ga collect --certification-run-id <uuid> --release <40-lowercase-hex> --release-bundle <sha256> --expected-environment-id <id> --expected-restore-point-rfc3339 <timestamp> --expected-provider-baseline-digest <sha256> --runtime-bundle <path> --units <path> --evidence-root <path> --rehearsal-receipt-set <path> --expected-rehearsal-receipt-set-digest <sha256> --expected-rehearsal-receipt-set-object-version <version> --output <index.dsse.json> --handoff-output <index-handoff.json>
ga verify --certification-run-id <uuid> --index <index.dsse.json> --expected-index-digest <sha256> --expected-index-object-version <version> --release <40-lowercase-hex> --release-bundle <sha256> --expected-environment-id <id> --expected-restore-point-rfc3339 <timestamp> --expected-provider-baseline-digest <sha256> --schema <ga-evidence-manifest.schema.json> --report-schema <ga-verification-report.schema.json> --signature-policy <signature-policy.yaml> --report-output <report.dsse.json> --summary-output <report.json>
```

Certification is not authorization to mutate Argo. After resolving the immutable passing report and index handoffs, release automation dispatches `.github/workflows/ga-approval.yaml` with `operation=open`, a fresh dispatch ID, the exact six protected values, one certification unit, the report and index envelope/digest/object-version/receipt handoffs, the verified release-artifact handoff plus exact manifest/Cosign/provenance/receipt bodies, and equality assertions for the release-author subject and role-binding version. The approval Job re-verifies every release body and derives the authoritative release author from the signed release manifest and protected workflow provenance; caller input cannot nominate a substitute. It re-verifies the selected unit's independently derived `ga` status and emits three bound outputs: the signed 30-minute approval-session DSSE, its immutable receipt, and a verified unsigned strings-only handoff to those exact bytes. Open failure, a missing body, or an uncertain handoff stops the release; no output is reconstructed from stdout, a database row, or the locator alone.

```bash
"$POD_ACCORDCTL" ga approval open \
  --certification-run-id "$ACCORD_CERTIFICATION_RUN_ID" --unit "$GA_CERTIFICATION_UNIT_ID" \
  --source-commit "$ACCORD_SOURCE_COMMIT" --release-bundle "$ACCORD_RELEASE_BUNDLE_DIGEST" \
  --expected-environment-id "$ACCORD_STAGING_ENVIRONMENT_ID" \
  --restore-point-rfc3339 "$ACCORD_RESTORE_POINT_RFC3339" \
  --provider-baseline-digest "$ACCORD_PROVIDER_BASELINE_DIGEST" \
  --expected-release-author-subject "$GA_RELEASE_AUTHOR_SUBJECT" \
  --expected-release-author-role-binding-version "$GA_RELEASE_AUTHOR_ROLE_BINDING_VERSION" \
  --release-artifact-handoff "$GA_RELEASE_ARTIFACT_HANDOFF" \
  --release-artifact-handoff-schema release/artifact-handoff.schema.json \
  --release-manifest "$GA_RELEASE_MANIFEST" \
  --release-manifest-cosign-bundle "$GA_RELEASE_MANIFEST_COSIGN_BUNDLE" \
  --release-manifest-provenance "$GA_RELEASE_MANIFEST_PROVENANCE" \
  --release-manifest-receipt "$GA_RELEASE_MANIFEST_RECEIPT" \
  --release-manifest-schema release/manifest.schema.json \
  --release-builder-policy operations/supply-chain/allowed-builders.yaml \
  --expected-release-manifest-digest "$GA_RELEASE_MANIFEST_DIGEST" \
  --expected-release-manifest-object-version "$GA_RELEASE_MANIFEST_OBJECT_VERSION" \
  --expected-release-manifest-receipt-digest "$GA_RELEASE_MANIFEST_RECEIPT_DIGEST" \
  --verification-report "$GA_REPORT_OUTPUT" \
  --expected-verification-report-digest "$GA_REPORT_DIGEST" \
  --expected-verification-report-object-version "$GA_REPORT_OBJECT_VERSION" \
  --evidence-index "$GA_INDEX_PATH" --expected-evidence-index-digest "$GA_INDEX_DIGEST" \
  --expected-evidence-index-object-version "$GA_INDEX_OBJECT_VERSION" \
  --policy operations/ga/approval-policy.yaml \
  --index-schema contracts/json-schema/ga-evidence-manifest.schema.json \
  --report-schema contracts/json-schema/ga-verification-report.schema.json \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json \
  --signature-policy certification/evidence/signature-policy.yaml \
  --session-output "$GA_APPROVAL_SESSION_ENVELOPE" \
  --session-receipt-output "$GA_APPROVAL_SESSION_RECEIPT" \
  --handoff-output "$GA_APPROVAL_SESSION_HANDOFF" --confirm
```

Two different eligible natural people then use independently downloaded and verified copies of the released `accordctl`, without a source checkout or platform signing key, to submit the two roles in separate browser/terminal sessions. Through ordinary authenticated workforce access, the platform UI gives each person a minimal offline package containing the unsigned session handoff, complete session DSSE envelope, and immutable receipt. `accordctl` must verify all three local regular non-symlink files, their digests/object versions/retention, the DSSE signature and purpose, and the release/run/unit/author bindings before it starts OAuth, WebAuthn, or SigV4:

```bash
"$VERIFIED_ACCORDCTL" ga approval submit \
  --session-handoff "$GA_APPROVAL_SESSION_HANDOFF" \
  --session-envelope "$GA_APPROVAL_SESSION_ENVELOPE" \
  --session-receipt "$GA_APPROVAL_SESSION_RECEIPT" \
  --role platform_operations \
  --policy operations/ga/approval-policy.yaml \
  --signature-policy certification/evidence/signature-policy.yaml \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --receipt-schema contracts/json-schema/immutable-evidence-receipt.schema.json \
  --submission-schema contracts/json-schema/ga-approval-submission.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json
```

```bash
"$VERIFIED_ACCORDCTL" ga approval submit \
  --session-handoff "$GA_APPROVAL_SESSION_HANDOFF" \
  --session-envelope "$GA_APPROVAL_SESSION_ENVELOPE" \
  --session-receipt "$GA_APPROVAL_SESSION_RECEIPT" \
  --role product_security \
  --policy operations/ga/approval-policy.yaml \
  --signature-policy certification/evidence/signature-policy.yaml \
  --session-schema contracts/json-schema/ga-approval-session.schema.json \
  --receipt-schema contracts/json-schema/immutable-evidence-receipt.schema.json \
  --submission-schema contracts/json-schema/ga-approval-submission.schema.json \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json
```

Each submission must complete WebAuthn step-up, S256 PKCE, OAuth RAR, DPoP proof-of-possession, the one-time transaction-receipt exchange, and its role-specific SigV4 route. At the trusted submission time the WebAuthn authentication is no more than 5 minutes old and the receipt lifetime is no more than 5 minutes. The people must be distinct from each other, currently hold their respective role bindings, and neither may be the independently derived release author. Secrets and temporary credentials remain process-local and are zeroed; only the minimum append-only submission record is retained.

Release automation then dispatches `.github/workflows/ga-approval.yaml` again with `operation=finalize`, a new dispatch ID, and the exact session handoff, DSSE envelope, and immutable receipt. This is a fresh `ga-approval-attestor` Job, not a continuation of the opener, and it runs the closed surface below with no defaulted input:

```bash
"$POD_ACCORDCTL" ga approval finalize \
  --session-handoff "$GA_APPROVAL_SESSION_HANDOFF" \
  --session-envelope "$GA_APPROVAL_SESSION_ENVELOPE" \
  --session-receipt "$GA_APPROVAL_SESSION_RECEIPT" \
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

Finalization must finish before session expiry and no later than 15 minutes after the later selected submission. One serializable PostgreSQL transaction under the dedicated certification-coordination role consumes both selected receipt JTIs, closes the session, and inserts the immutable finalization claim with a unique fencing token before KMS or Object Lock side effects. Successful read-back yields a verified unsigned handoff to the signed approval-record DSSE and its immutable receipt; a timeout, role/directory change, same-person pair, release-author collision, replay, stale observation, serialization exhaustion, or uncertain finalization fails closed and requires a new session rather than an edit or override. A new promotion must durably enter `EXECUTING` within 15 minutes of the frozen approval decision; after that, starting a Provider call requires a new session and two new submissions. An operation already in `EXECUTING` or `OUTCOME_UNKNOWN` remains eligible only for its original read-only reconciliation path and cannot use expiry as a reason to issue another call.

Only the verified approval-record handoff resolving to the signed DSSE and immutable receipt permits `.github/workflows/ga-promotion.yaml`. Capture the unit's authoritative Argo Application UID, resource version, target revision, Provider audit cursor, Kubernetes audit cursor, and cumulative matching mutation count before the first dispatch. Run exactly these three independent `operation=promote` workflow dispatches in order; each has a new UUID `dispatch_id`, fresh protected environment OIDC token, fresh workspace, one newly admitted unit-specific immutable Job, and one command. They must never be function calls or multiple invocations inside one Job:

| Dispatch | Canonical request identity | Required result |
|---|---|---|
| Negative binding test | New rejected `promotion_id`; report/index/approval handoffs remain valid, but the admitted Provider-baseline value deliberately disagrees with them | Actual command exit `1`, signed `PRECONDITION_REJECTED` attempt, no promotion receipt, no Provider permit, and mutation-count delta `0` |
| Authorized promotion | New real `promotion_id`; exact six-value tuple, handoffs, unit map, expected current release, policy, and target digest all agree | One `EXECUTION_AUTHORIZED` attempt, one `APPLIED` resolution, one retained promotion receipt, and cumulative mutation-count delta exactly `1` |
| Idempotent retry | Same real `promotion_id` and byte-identical canonical request as the authorized promotion; only `dispatch_id` and local output destination differ and neither participates in `request_digest` | Signed `RETAINED_RECEIPT_RETURNED` attempt, byte-for-byte identical retained promotion receipt, no Provider permit, and cumulative mutation-count delta still exactly `1` |

For every row, admission binds the exact released binary/image, unit-specific ServiceAccount/IRSA, Application name and UID, command digest, immutable release-artifact and predecessor handoffs, both signed approval/promotion policies, the applied cluster connection, and six protected values. Each promoter Pod executes only its single real `accordctl release promote` process; it may not contain an `if`, `test`, `cmp`, exit-code translation, or a second verifier command. Its authenticated immutable launch receipt records the real terminal result. A shared promoter identity, cross-unit object/key/partition access, wildcard Argo permission, caller-selected Application or cluster connection, unsigned mutation counter, or promoter-side wrapper invalidates the rehearsal.

If the authorized call returns `OUTCOME_UNKNOWN`, stop the sequence before the retry or rehearsal verifier and dispatch a separate `operation=reconcile` Job. Reconciliation may retain only `APPLIED`, `NO_EFFECT`, or `DIVERGED` from authoritative Application/history/audit facts and never patches Argo. `APPLIED` resumes the frozen original receipt; `NO_EFFECT` permits only a later explicit promotion with the coordination-assigned next fencing generation; `DIVERGED` fences the unit and fails this candidate. No promote retry may steal an `EXECUTING` lease or issue another Provider call while outcome is unknown. The canonical retry and fourth verification dispatch may proceed only after the selected successful promotion is authoritatively `APPLIED` and its retained receipt is available.

After all three promoter workflows reach terminal status, dispatch the same `.github/workflows/ga-promotion.yaml` once more with `operation=verify-rehearsal`. This fourth dispatch runs in the fixed read-only `ga-verifier` environment, is not part of any promoter Pod, and executes exactly one command:

```bash
"$POD_ACCORDCTL" ga verify-promotion-rehearsal \
  --unit "$GA_CERTIFICATION_UNIT_ID" \
  --rejected-promotion-id "$GA_REJECTED_PROMOTION_ID" --promotion-id "$GA_PROMOTION_ID" \
  --negative-dispatch-id "$GA_NEGATIVE_DISPATCH_ID" \
  --success-dispatch-id "$GA_SUCCESS_DISPATCH_ID" --retry-dispatch-id "$GA_RETRY_DISPATCH_ID" \
  --negative-launch-receipt "$GA_NEGATIVE_LAUNCH_RECEIPT" \
  --negative-attempt-handoff "$GA_REJECTED_ATTEMPT_HANDOFF" \
  --success-launch-receipt "$GA_SUCCESS_LAUNCH_RECEIPT" \
  --success-attempt-handoff "$GA_PROMOTION_ATTEMPT_HANDOFF" \
  --success-resolution-handoff "$GA_PROMOTION_RESOLUTION_HANDOFF" \
  --success-receipt-handoff "$GA_PROMOTION_RECEIPT_HANDOFF" \
  --retry-launch-receipt "$GA_RETRY_LAUNCH_RECEIPT" \
  --retry-attempt-handoff "$GA_RETRY_ATTEMPT_HANDOFF" \
  --retry-receipt-handoff "$GA_RETRY_RECEIPT_HANDOFF" \
  --provider-audit-cursor-before "$GA_PROVIDER_AUDIT_CURSOR_BEFORE" \
  --provider-audit-cursor-after "$GA_PROVIDER_AUDIT_CURSOR_AFTER" \
  --kubernetes-audit-cursor-before "$GA_KUBERNETES_AUDIT_CURSOR_BEFORE" \
  --kubernetes-audit-cursor-after "$GA_KUBERNETES_AUDIT_CURSOR_AFTER" \
  --signature-policy certification/evidence/signature-policy.yaml \
  --approval-policy operations/ga/approval-policy.yaml \
  --promotion-policy operations/ga/promotion-policy.yaml \
  --handoff-schema contracts/json-schema/ga-handoff-record.schema.json \
  --attempt-schema contracts/json-schema/ga-promotion-attempt.schema.json \
  --resolution-schema contracts/json-schema/ga-promotion-resolution.schema.json \
  --receipt-schema contracts/json-schema/ga-promotion-receipt.schema.json \
  --summary-output "$GA_PROMOTION_REHEARSAL_VERIFICATION"
```

The verifier resolves each unsigned handoff to the exact DSSE and immutable receipt, strongly reads both coordination partitions, and queries the retained Provider plus Kubernetes/Argo audit streams between the independent cursors. It must prove the negative command really exited `1` with `known_rejection`, `PRECONDITION_REJECTED`, `provider_call_authorized=false`, no claim/lease/generation/request ID/permit/resolution/final receipt, and zero Provider/Argo mutation delta; the success produced one `EXECUTION_AUTHORIZED` attempt, one CAS, `APPLIED`, and one retained receipt; and the retry produced `RETAINED_RECEIPT_RETURNED`, no new permit or mutation, byte-identical receipt bytes, and cumulative mutation count exactly one. A known mismatch exits `1`; missing or ambiguous evidence exits `3`.

Exit `0` means the current command's schema, signature, identity, retention, immutable receipt, and business predicates passed and its output was read back successfully. Evidence or business-gate failure exits `1` without a new pass output; malformed invocation or missing required input exits `2`; inability to determine whether a Kubernetes Job, immutable object, approval, Provider call, or receipt/handoff was created exits `3` without a blind retry or success claim. Make and workflow layers preserve the exact nonzero code. A failed, cancelled, or uncertain predecessor prevents successors by default and cannot be bypassed with `always()`, manual status input, database mutation, administrator override, direct Argo patch, or a downstream-only rerun.

Expected: pre-collection journeys produce release-bound facts before collection; the signed index resolves all 21 sections and Appendices A-D for the exact source commit, release bundle, immutable environment, protected restore point, signed Provider baseline, and eligible units; all four applied OpenTofu roots and the exact 19-module union are proven; the live central Helm inventory contains exactly one locked-dependency Agent Pack Gateway; the eighteen Operations chaos scenarios converge with zero forbidden Pack capability/digest/epoch outcomes; and every fully evidenced certification unit has its own currently applicable and accepted `contractual-terms` evidence, independently derives `contractual_terms_pass=true`, then derives `ga`. Missing, stale, expired, revoked, wrong-locale/digest/release/environment/unit/signer/approval/retention/acceptance fixtures deterministically derive `contractual_terms_pass=false` and at most `limited_availability`, while restricted post-collection roles display the same signed status without becoming evidence inputs. Certification alone creates no final release record. The canonical completion record is the Object-Lock-retained promotion-receipt DSSE plus its verified strings-only handoff; it transitively binds the verified handoffs to the signed report/index DSSEs, the signed finalized dual-control approval DSSE and both transaction-receipt digests, one execution-authorized attempt, one `APPLIED` resolution, and authoritative evidence of exactly one Argo CAS. The fourth read-only rehearsal-verification dispatch proves the complete negative/success/retry sequence but does not become or replace that completion record. Negative and retry attempts remain retained audit evidence but cannot substitute for it.

## Final Master Verification

The canonical final master verification is the complete ordered protocol in Step 5: a fresh clean-checkout preflight of the exact final candidate and independent resolution of its release-artifact handoff; one protected `.github/workflows/ga-certification.yaml` dispatch; one protected approval-open dispatch; two separate eligible humans' WebAuthn/PKCE/RAR/DPoP submissions from independently verified offline session packages; one protected approval-finalize dispatch; three separate admission-bound `.github/workflows/ga-promotion.yaml` `operation=promote` dispatches for negative binding, authorized promotion, and byte-identical retry; then a fourth independent `operation=verify-rehearsal` dispatch in the read-only `ga-verifier` lane. No prefix of that protocol, including a passing certification report, finalized approval, or three unverified promoter runs, is a completed GA release.

Release automation reruns preflight from a second clean checkout of the exact candidate commit, then supplies only protected credentials plus `ACCORD_SYSTEM_BASE_URL`, `ACCORD_COMPAT_BASE_TAG`, `ACCORD_COMPAT_BASE_REF`, immutable `certification_run_id`, `source_commit`, `release_bundle_digest`, `immutable_environment_id`, explicit RFC 3339 `restore_point_rfc3339`, `provider_baseline_digest`, and exact release-artifact/predecessor content identities. It must not reuse a working tree, OIDC token, prior job workspace, prior evidence directory, mutable environment alias, locally rebuilt binary, approval session, dispatch ID, or manually supplied status. Every workflow is loaded from that source commit and every protected Job independently resolves unsigned handoffs and verifies the signed released artifacts, immutable receipts, and predecessor bindings before acting.

Promotion consumes and revalidates the release-artifact handoff plus exact manifest/Cosign/provenance/receipt bodies, certification run ID, externally retained verification-report receipt, signed evidence-index digest and Object Lock version, finalized approval-record digest/object version/receipt, release-bundle digest, source commit, immutable environment ID, protected restore point, signed Provider baseline digest, unit map, both approval/promotion policies, expected current release, applied cluster connection, and target digest only after the verifier derives `contractual_terms_pass=true` and the same `ga` status for that unit. The negative dispatch must leave the authoritative Provider/Argo mutation count unchanged; the authorized dispatch must increase it by exactly one; the identical retry must leave it at one and return byte-identical receipt bytes; and the fourth read-only verifier must prove all three facts from retained evidence. The final promotion receipt must bind the verified approval-record handoff, its signed DSSE and immutable receipt, and an `APPLIED` resolution proving exactly that one CAS. `OUTCOME_UNKNOWN`, `NO_EFFECT`, `DIVERGED`, a missing final receipt, an unverified rehearsal, or a mismatch in any binding is not GA completion.

Any missing, stale, expired, revoked, mistranslated, misbound, wrongly signed, unapproved, unretained, or unaccepted terms evidence caps only its bound unit at `limited_availability`; another unit's evidence, a database edit, a shared identity, or an administrator action cannot replace or override it. The full protocol and its canonical retained record are the sole final handoff to release governance.
