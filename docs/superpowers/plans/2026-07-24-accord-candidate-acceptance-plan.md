# Accord Candidate Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build immutable delivery Candidates, exact business AcceptanceRuns, three-way failure classification, same-revision CorrectionRuns, evidence-based acceptance continuity, digest-preserving artifact promotion, and the atomic DeliveryBatch completion invariant.

**Architecture:** Customer CI constructs and attests candidate source trees, tests, environment identity, build provenance, and immutable artifact locators. Accord stores and verifies only metadata, digests, signed evidence, human decisions, and lifecycle facts. Candidate, per-Requirement AcceptanceRun or continuity evidence, a sealed complete acceptance-evidence set, promotion, and completion are separate aggregates joined by exact hashes; no mutable "accepted" flag or rebuilt artifact can substitute for those facts. Transactional outbox events drive a durable completion evaluator that always reloads authoritative state and can complete a batch exactly once.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Spring Modulith 1.4.1, Gradle 8.14.3 Groovy DSL, jOOQ, Flyway, PostgreSQL 17.5, Temporal, JSON Schema 2020-12, RFC 8785 JCS, DSSE/SLSA provenance, OpenAPI 3.1, SSE, JUnit 5, AssertJ, jqwik, Testcontainers, WireMock, Toxiproxy, Playwright.

---

## Dependencies And Invariants

Milestone M4 executes after canonical requirements, identity/trust, Context/Patch, and Git Delivery Tasks 1-8. Git Delivery Tasks 9-11 are downstream strict-delivery work and are not prerequisites for M4. Artifact adapters operate on promote-by-digest commands against customer registries; Accord never receives artifact or source bodies.

- A Candidate is immutable and binds exact tenant/repository/batch/effective manifest/default base/delivery head/candidate commit/tree/context watermark/revisions/criteria/environment/artifact evidence.
- Any bound tree, revision, criteria, artifact, environment, acceptance-owner binding, or blocking hold change creates a new Candidate ID.
- Only the business-side `BusinessAcceptanceOwner` signs final acceptance; an external supplier cannot accept its own delivery.
- AcceptanceRun decisions bind one Candidate and never migrate to another Candidate.
- Every Candidate Requirement entry freezes the exact business acceptance-owner role-binding ID, version, account/natural-person snapshot, and JCS binding digest; later binding drift invalidates the Candidate but never rewrites that snapshot.
- Promotion and completion bind one canonical complete-set digest containing exactly one active direct AcceptanceRun or valid continuity attestation for every Candidate Requirement; a singular `acceptance_run_id` is never accepted as whole-Candidate proof.
- `implementation_defect` creates a CorrectionRun under the same Revision; `requirement_change` creates a new Revision; `environment_issue` reruns acceptance against the same Candidate.
- Promotion uses the exact accepted artifact digest and never rebuilds it.
- The controlled default merge of the Candidate is expected lifecycle progress: exact merge evidence advances `MERGED -> RECONCILED` without invalidation. An exact owned merge with an uncertain outcome or unproven ancestry remains `ACTIVE` and suspended in reconciliation; only confirmed unrelated drift/hotfix or a confirmed mismatched result tree invalidates it.
- Batch `COMPLETED` is an atomic result of the full invariant, never an administrator command or projection value.
- Every Testcontainers fixture in this plan that starts control-plane PostgreSQL must call `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)` immediately after `postgres.start()` and before its first `Flyway.configure()`, `load()`, or `migrate()` call. No fixture may create runtime roles locally or repair role membership or grants after Flyway.

## File Map

```text
contracts/json-schema/acceptance/
  candidate.schema.json
  candidate-ci-attestation.schema.json
  acceptance-run.schema.json
  acceptance-evidence-set.schema.json
  failure-disposition.schema.json
  correction-run.schema.json
  acceptance-continuity-attestation.schema.json
  artifact-promotion.schema.json
contracts/events/acceptance/
  acceptance-events.schema.json
database/control-plane/migrations/
  V050__candidate_acceptance.sql
  V051__correction_and_continuity.sql
  V052__artifact_promotion.sql
  V053__delivery_completion_evaluation.sql
apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/
  domain/CandidateModels.java
  domain/AcceptanceModels.java
  domain/CorrectionModels.java
  domain/CompletionInvariant.java
  domain/AcceptanceBindingValidator.java
  application/CandidateService.java
  application/AcceptanceService.java
  application/FailureDispositionService.java
  application/CorrectionRunService.java
  application/AcceptanceContinuityService.java
  application/ArtifactPromotionService.java
  application/AcceptanceEvidenceSetService.java
  application/BatchCompletionService.java
  infrastructure/JooqAcceptanceRepository.java
  api/AcceptanceController.java
  workflow/CandidateWorkflow.java
  workflow/ArtifactPromotionWorkflow.java
  workflow/CompletionEventTypes.java
  workflow/CompletionFactsLoader.java
  workflow/CompletionEvaluationTriggerHandler.java
  workflow/CompletionEvaluationWorker.java
apps/control-plane/worker/src/main/java/com/inforvans/accord/worker/
  AcceptanceCompletionWorkerConfiguration.java
contracts/golden-fixtures/acceptance/
  candidate.json
  candidate.hash-input.jcs.json
  candidate.sha256
  candidate-ci-attestation.json
  acceptance-run.json
  acceptance-evidence-set.json
  acceptance-evidence-set.hash-input.jcs.json
  acceptance-evidence-set.sha256
  failure-disposition.json
  correction-run.json
  acceptance-continuity-attestation.json
  artifact-promotion.json
  events/completion-evaluation-requested.json
  events/completion-evaluated.json
  events/delivery-batch-completed.json
tests/contract/src/test/java/com/inforvans/accord/contracts/AcceptanceContractTest.java
tests/state-machine/src/test/java/com/inforvans/accord/state/AcceptanceStateProperties.java
tests/e2e/candidate-acceptance.spec.ts
tests/fault-injection/acceptance/
docs/runbooks/
  artifact-promotion-uncertain.md
  acceptance-recovery.md
  correction-limit.md
```

### Task 1: Freeze Candidate, Acceptance, Correction, And Promotion Contracts

**Files:**
- Modify: `settings.gradle`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java`
- Modify: `apps/control-plane/worker/build.gradle`
- Create: `apps/control-plane/modules/candidate-acceptance/build.gradle`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/package-info.java`
- Modify: `tests/contract/build.gradle`
- Modify: `tests/integration/build.gradle`
- Modify: `tests/api/build.gradle`
- Modify: `tests/security-negative/build.gradle`
- Modify: `tests/state-machine/build.gradle`
- Modify: `tests/fault-injection/build.gradle`
- Create: `contracts/json-schema/acceptance/candidate.schema.json`
- Create: `contracts/json-schema/acceptance/candidate-ci-attestation.schema.json`
- Create: `contracts/json-schema/acceptance/acceptance-run.schema.json`
- Create: `contracts/json-schema/acceptance/acceptance-evidence-set.schema.json`
- Create: `contracts/json-schema/acceptance/failure-disposition.schema.json`
- Create: `contracts/json-schema/acceptance/correction-run.schema.json`
- Create: `contracts/json-schema/acceptance/acceptance-continuity-attestation.schema.json`
- Create: `contracts/json-schema/acceptance/artifact-promotion.schema.json`
- Create: `contracts/events/acceptance/acceptance-events.schema.json`
- Create: `contracts/golden-fixtures/acceptance/candidate.json`
- Create: `contracts/golden-fixtures/acceptance/candidate.hash-input.jcs.json`
- Create: `contracts/golden-fixtures/acceptance/candidate.sha256`
- Create: `contracts/golden-fixtures/acceptance/candidate-ci-attestation.json`
- Create: `contracts/golden-fixtures/acceptance/acceptance-run.json`
- Create: `contracts/golden-fixtures/acceptance/acceptance-evidence-set.json`
- Create: `contracts/golden-fixtures/acceptance/acceptance-evidence-set.hash-input.jcs.json`
- Create: `contracts/golden-fixtures/acceptance/acceptance-evidence-set.sha256`
- Create: `contracts/golden-fixtures/acceptance/failure-disposition.json`
- Create: `contracts/golden-fixtures/acceptance/correction-run.json`
- Create: `contracts/golden-fixtures/acceptance/acceptance-continuity-attestation.json`
- Create: `contracts/golden-fixtures/acceptance/artifact-promotion.json`
- Create: `contracts/golden-fixtures/acceptance/events/completion-evaluation-requested.json`
- Create: `contracts/golden-fixtures/acceptance/events/completion-evaluated.json`
- Create: `contracts/golden-fixtures/acceptance/events/delivery-batch-completed.json`
- Test: `tests/contract/src/test/java/com/inforvans/accord/contracts/AcceptanceContractTest.java`
- Create: `tests/contract/src/test/java/com/inforvans/accord/contracts/AcceptanceFixtureSemanticValidator.java`
- Create: `tests/contract/node/acceptance-canonical-bytes.test.mjs`

- [ ] **Step 1: Add failing exact-binding contract tests**

```java
@Test
void acceptanceRunBindsTheCompleteImmutableTargetTuple() {
    var run = fixture("acceptance/acceptance-run.json");
    assertThat(acceptanceRunSchema.validate(run)).isEmpty();
    var fieldNames = new HashSet<String>();
    run.path("target").fieldNames().forEachRemaining(fieldNames::add);
    assertThat(fieldNames).containsExactlyInAnyOrder(
        "candidate_id", "candidate_payload_digest", "requirement_id", "candidate_requirement_digest",
        "revision_hash", "acceptance_criteria_hash", "repository_tree_sha", "artifact_digest",
        "environment_configuration_hash", "acceptance_owner_binding_id",
        "acceptance_owner_binding_version", "acceptance_owner_binding_digest");
}

@Test
void everyAcceptanceShapeSchemaHasACheckedInValidFixture() {
    var fixtures = Map.of(
        candidateSchema, "acceptance/candidate.json",
        candidateCiAttestationSchema, "acceptance/candidate-ci-attestation.json",
        acceptanceRunSchema, "acceptance/acceptance-run.json",
        acceptanceEvidenceSetSchema, "acceptance/acceptance-evidence-set.json",
        failureDispositionSchema, "acceptance/failure-disposition.json",
        correctionRunSchema, "acceptance/correction-run.json",
        acceptanceContinuityAttestationSchema, "acceptance/acceptance-continuity-attestation.json",
        artifactPromotionSchema, "acceptance/artifact-promotion.json");
    fixtures.forEach((schema, fixturePath) ->
        assertThat(schema.validate(fixture(fixturePath))).isEmpty());
}

@Test
void schemaAcceptsShapeWhileSemanticValidationRejectsCrossInstanceDigestMismatch() {
    var candidate = fixture("acceptance/candidate.json");
    var evidenceSet = fixture("acceptance/acceptance-evidence-set.json");
    var promotion = fixture("acceptance/artifact-promotion.json").deepCopy();
    ((ObjectNode) promotion).put("source_digest", otherDigest);
    ((ObjectNode) promotion).put("acceptance_complete_set_digest", anotherDigest);

    assertThat(artifactPromotionSchema.validate(promotion)).isEmpty();
    assertThat(AcceptanceFixtureSemanticValidator.validatePromotion(candidate, evidenceSet, promotion))
        .extracting(SemanticViolation::code)
        .containsExactlyInAnyOrder(
            "promotion_source_digest_mismatch",
            "acceptance_complete_set_digest_mismatch");
}

@Test
void candidateJcsEnvelopeFreezesEveryRequirementOwnerBinding() {
    var candidate = fixture("acceptance/candidate.json");
    var hashInput = fixtureBytes("acceptance/candidate.hash-input.jcs.json");
    assertThat(FoundationJcs.canonicalize(candidateHashEnvelope(candidate))).isEqualTo(hashInput);
    assertThat(candidate.path("candidate_payload_digest").asText())
        .isEqualTo("sha256:" + fixtureText("acceptance/candidate.sha256").trim());
    var requirementFields = new HashSet<String>();
    candidate.path("requirements").elements().next().fieldNames().forEachRemaining(requirementFields::add);
    assertThat(requirementFields).contains(
        "acceptance_owner_binding_id", "acceptance_owner_binding_version",
        "acceptance_owner_account_id", "acceptance_owner_natural_person_id",
        "acceptance_owner_role", "acceptance_owner_side", "acceptance_owner_scope_type",
        "acceptance_owner_scope_id", "acceptance_owner_starts_at", "acceptance_owner_expires_at",
        "acceptance_owner_binding_digest", "candidate_requirement_digest");
}

@Test
void acceptanceEventFixturesAreClosedAndVersioned() {
    List.of(
        "completion-evaluation-requested.json",
        "completion-evaluated.json",
        "delivery-batch-completed.json"
    ).forEach(name -> assertThat(
        acceptanceEventsSchema.validate(fixture("acceptance/events/" + name))).isEmpty());
}
```

JSON Schema 2020-12 validates only each document's structure, formats, closed enums, and digest syntax. It must not pretend to compare a promotion document with a separately stored Candidate or evidence-set document. `AcceptanceFixtureSemanticValidator` is the contract-level cross-document oracle; Task 2 adds the production typed validator and exact Candidate/Run database bindings, while Task 7 repeats the check under locks and uses digest-bearing composite foreign keys. The negative tests above must prove that a syntactically valid mismatch passes schema validation and still fails semantic validation with the exact codes.

Create the test-only oracle with an explicit three-document API; it must not call a schema extension keyword:

```java
record SemanticViolation(String code) {}

final class AcceptanceFixtureSemanticValidator {
    private AcceptanceFixtureSemanticValidator() {}

    static List<SemanticViolation> validatePromotion(
        JsonNode candidate, JsonNode evidenceSet, JsonNode promotion
    ) {
        var violations = new ArrayList<SemanticViolation>();
        mismatch(candidate, "candidate_id", evidenceSet, "candidate_id",
            "evidence_set_candidate_mismatch", violations);
        mismatch(candidate, "candidate_payload_digest", evidenceSet, "candidate_payload_digest",
            "evidence_set_candidate_payload_mismatch", violations);
        mismatch(candidate, "candidate_id", promotion, "candidate_id",
            "promotion_candidate_mismatch", violations);
        mismatch(candidate, "candidate_payload_digest", promotion, "candidate_payload_digest",
            "promotion_candidate_payload_mismatch", violations);
        mismatch(candidate, "artifact_digest", promotion, "source_digest",
            "promotion_source_digest_mismatch", violations);
        mismatch(evidenceSet, "acceptance_complete_set_digest", promotion,
            "acceptance_complete_set_digest", "acceptance_complete_set_digest_mismatch", violations);
        if (!canonicalCandidateDigest(candidate).equals(candidate.path("candidate_payload_digest").asText())) {
            violations.add(new SemanticViolation("candidate_payload_digest_not_canonical"));
        }
        if (!canonicalCompleteSetDigest(candidate, evidenceSet)
            .equals(evidenceSet.path("acceptance_complete_set_digest").asText())) {
            violations.add(new SemanticViolation("acceptance_complete_set_digest_not_canonical"));
        }
        return List.copyOf(violations);
    }

    private static void mismatch(
        JsonNode left, String leftField, JsonNode right, String rightField,
        String code, List<SemanticViolation> violations
    ) {
        if (!left.path(leftField).asText().equals(right.path(rightField).asText())) {
            violations.add(new SemanticViolation(code));
        }
    }
}
```

`canonicalCompleteSetDigest` also performs the exact one-entry-per-Candidate-Requirement coverage comparison defined in Step 5 and throws no implicit coercions; its negative fixture mutations assert each stable code independently so one early mismatch cannot mask the next.

Create all listed golden fixtures in this task. Every acceptance schema has at least one valid fixture; no later task may refer to an implicit classpath object. `candidate.hash-input.jcs.json` and `acceptance-evidence-set.hash-input.jcs.json` are checked-in exact UTF-8 RFC 8785 bytes with no BOM or trailing newline, and each `.sha256` contains the lowercase 64-hex digest of its corresponding bytes. `acceptance-events.schema.json` is a closed `oneOf` contract for acceptance-owned events and all three completion coordination events; the common envelope requires `event_id`, `event_type`, `schema_version`, `tenant_id`, `aggregate_type`, `aggregate_id`, `sequence`, `causation_id`, `correlation_id`, `occurred_at`, and a closed event-specific `payload`.

In `ModuleBoundaryTest.java`, replace `requiredModules` with:

```java
private static final Set<String> REQUIRED_MODULES = Set.of(
    "platformkernel", "reliability",
    "identity", "authorization", "audit",
    "requirement", "attachment", "collaboration", "action",
    "context", "assessment",
    "delivery", "git", "workitem", "acceptance"
);
```

- [ ] **Step 2: Run and verify missing schemas**

Run: `./gradlew :tests:contract:test --tests '*AcceptanceContractTest'`

Expected: failure identifies the first missing acceptance schema or golden fixture; the semantic mismatch case must not fail at JSON Schema validation.

- [ ] **Step 3: Register the Candidate and Acceptance module**

Append `include ':apps:control-plane:modules:candidate-acceptance'` to `settings.gradle`, then create its build file:

```groovy
plugins {
    id 'java-library'
    alias(libs.plugins.pitest)
}
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':apps:control-plane:modules:reliability')
    implementation project(':apps:control-plane:modules:identity')
    implementation project(':apps:control-plane:modules:authorization')
    implementation project(':apps:control-plane:modules:audit')
    implementation project(':apps:control-plane:modules:requirement-graph')
    implementation project(':apps:control-plane:modules:project-context')
    implementation project(':apps:control-plane:modules:assessment')
    implementation project(':apps:control-plane:modules:delivery')
    implementation project(':apps:control-plane:modules:git-coordination')
    implementation project(':apps:control-plane:modules:workitem-execution')
    implementation project(':apps:control-plane:modules:actions-notifications')
    implementation project(':database:control-plane')
    implementation libs.spring.boot.jooq
    implementation libs.spring.boot.web
    implementation libs.spring.boot.validation
    implementation libs.temporal.sdk
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.jqwik
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.temporal.testing
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
    testImplementation testFixtures(project(':database:control-plane'))
}
pitest {
    targetClasses = [
        'com.inforvans.accord.acceptance.domain.*',
        'com.inforvans.accord.acceptance.application.BatchCompletionService'
    ]
    targetTests = ['com.inforvans.accord.acceptance.*']
    junit5PluginVersion = '1.2.1'
    mutationThreshold = 95
    outputFormats = ['XML', 'HTML']
    timestampedReports = false
}
tasks.withType(Test).configureEach { useJUnitPlatform() }
```

Add the module to API and worker `implementation` dependencies and to the `contract`, `integration`, `api`, `security-negative`, `state-machine`, and `fault-injection` test projects. Keep `testImplementation(testFixtures(project(":database:control-plane")))` in the new Candidate Acceptance module and in every Identity-plan-owned shared test configuration that starts PostgreSQL: `tests/integration/build.gradle`, `tests/api/build.gradle`, `tests/security-negative/build.gradle`, `tests/state-machine/build.gradle`, and `tests/fault-injection/build.gradle`. A control-plane test must consume `ControlPlaneTestRoles`, never create local roles or repair grants after migration. Create `package-info.java` with `@ApplicationModule` and an allowlist exactly matching the project dependencies above. Candidate Acceptance may depend only on the `actions-notifications` named interface `action::action-request-api`, whose exported surface is `action.api.ActionRequestPort` plus its envelope and command types; `ModuleBoundaryTest.java` must permit that exact named-interface edge while rejecting imports of `ActionRequestService`, repositories, infrastructure, or any action package outside the named interface. A direct dependency on any security-service implementation is forbidden.

- [ ] **Step 4: Define the Candidate envelope**

```json
{
  "schema_version": "1.0",
  "candidate_id": "d8d481c8-18e3-4f61-9cf4-2a034295f042",
  "tenant_id": "f4bdb839-a0d8-47eb-88b0-45c908f59621",
  "repository_id": "16ec0635-b60d-4141-8126-525317211831",
  "batch_id": "f5e31ad7-111a-4b9f-956c-35dcda589008",
  "effective_batch_manifest_digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "default_base_sha": "1111111111111111111111111111111111111111",
  "delivery_head_sha": "2222222222222222222222222222222222222222",
  "candidate_commit_sha": "3333333333333333333333333333333333333333",
  "repository_tree_sha": "4444444444444444444444444444444444444444",
  "code_fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "context_version": "8a3ffb17-c28b-4a71-9440-4d650f83f037",
  "patch_watermark": 18,
  "requirements": [{
    "requirement_id": "6d36b8c8-7f57-4f32-9b73-2ec6ef11a042",
    "requirement_revision_id": "77849d83-9fbf-42fa-94e2-03240f48b042",
    "revision_hash": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "acceptance_criteria_hash": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
    "acceptance_owner_binding_id": "72cd6fb6-2bf5-4fac-8953-0a5eb61ea042",
    "acceptance_owner_binding_version": 7,
    "acceptance_owner_account_id": "4ac8e2d8-26a2-47aa-ab0c-8567c8f1a042",
    "acceptance_owner_natural_person_id": "0f881cf3-3374-4e13-b43f-034abc39a042",
    "acceptance_owner_role": "business_acceptance_owner",
    "acceptance_owner_side": "business",
    "acceptance_owner_scope_type": "requirement",
    "acceptance_owner_scope_id": "6d36b8c8-7f57-4f32-9b73-2ec6ef11a042",
    "acceptance_owner_starts_at": "2026-07-01T00:00:00Z",
    "acceptance_owner_expires_at": null,
    "acceptance_owner_binding_digest": "sha256:abababababababababababababababababababababababababababababababab",
    "candidate_requirement_digest": "sha256:bcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcbc"
  }],
  "environment_configuration_hash": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
  "artifact_policy": "immutable_artifact",
  "artifact_digest": "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
  "artifact_locator": "registry.example.invalid/product@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
  "build_provenance_digest": "sha256:9999999999999999999999999999999999999999999999999999999999999999",
  "candidate_payload_digest": "sha256:1212121212121212121212121212121212121212121212121212121212121212"
}
```

For `source_tree_only`, artifact digest/locator are null and the payload explicitly states no independent artifact guarantee. Every schema sets `additionalProperties: false`, UUID/digest formats, and bounded arrays/strings. `acceptance_owner_binding_digest` is SHA-256 over JCS of the full closed owner snapshot `{acceptance_owner_binding_id, acceptance_owner_binding_version, acceptance_owner_account_id, acceptance_owner_natural_person_id, acceptance_owner_role, acceptance_owner_side, acceptance_owner_scope_type, acceptance_owner_scope_id, acceptance_owner_starts_at, acceptance_owner_expires_at}`. `candidate_requirement_digest` is SHA-256 over JCS of the closed object `{requirement_id, requirement_revision_id, revision_hash, acceptance_criteria_hash}` plus that complete owner snapshot and its digest. `candidate_payload_digest` is SHA-256 over JCS of the complete Candidate object shown above excluding only `candidate_payload_digest`; the `requirements` array is sorted by lowercase UUID string. Therefore the full immutable owner-binding snapshot is inside both the per-Requirement digest and the whole-Candidate digest. Java and Node verifiers byte-compare this envelope with the checked-in hash input before hashing it.

- [ ] **Step 5: Define and independently digest the complete acceptance-evidence set**

`acceptance-evidence-set.schema.json` is a closed immutable envelope with these exact semantic fields:

```java
record AcceptanceEvidenceSetEnvelope(
    String schema_version,
    UUID tenant_id,
    UUID repository_id,
    UUID batch_id,
    UUID candidate_id,
    String candidate_payload_digest,
    List<AcceptanceEvidenceEntry> requirements,
    String acceptance_complete_set_digest
) {
    AcceptanceEvidenceSetEnvelope {
        requirements = List.copyOf(requirements);
    }
}

record AcceptanceEvidenceEntry(
    UUID requirement_id,
    String candidate_requirement_digest,
    String revision_hash,
    String acceptance_criteria_hash,
    UUID acceptance_owner_binding_id,
    long acceptance_owner_binding_version,
    String acceptance_owner_binding_digest,
    EvidenceKind evidence_kind,
    UUID evidence_id,
    String evidence_envelope_digest
) {}

enum EvidenceKind { ACCEPTANCE_RUN, CONTINUITY_ATTESTATION }
```

Sort entries by lowercase `requirement_id`, require exactly one entry for every Candidate Requirement and no extras, and hash JCS of `{schema_version, tenant_id, repository_id, batch_id, candidate_id, candidate_payload_digest, requirements}`. The digest field itself is excluded. The Java and independent Node test must byte-compare against `acceptance-evidence-set.hash-input.jcs.json`, hash those bytes, and compare with `acceptance-evidence-set.sha256`. Schema validation can establish only shape; `AcceptanceFixtureSemanticValidator` must load `candidate.json`, compare identity/digests and exact Requirement coverage, recompute both JCS envelopes, and reject missing, duplicate, extra, reordered-before-canonicalization, wrong-Candidate, wrong-owner-version, and wrong evidence-envelope cases.

- [ ] **Step 6: Define long-lived acceptance/continuity DSSE payloads and acceptance events**

Bind purpose/domain, tenant/repository, immutable object IDs, actor/account/natural-person/role-binding snapshot, exact target tuple, criterion decisions/evidence digests, authentication strength, signed-at, and key ID. Long-lived facts do not expire solely because the signing key later rotates; trust follows the key trust record and anchor rules.

Define closed event variants for `candidate.phase_changed.v1`, `candidate.validity_changed.v1`, `acceptance_run.finalized.v1`, `acceptance_run.validity_changed.v1`, `candidate.acceptance_evidence_set.sealed.v1`, `acceptance_continuity_attestation.recorded.v1`, `acceptance_continuity_attestation.invalidated.v1`, `artifact_promotion.reconciled.v1`, `source_tree_assurance.recorded.v1`, `delivery.completion_evaluation_requested.v1`, `delivery.completion_evaluated.v1`, and `delivery.batch_completed.v1`. A completion-request envelope's `event_id` is its distinct `request_event_id`; its closed payload is limited to `batch_id`, original `source_event_id`, `source_event_type`, `source_aggregate_version`, `attempt_no`, nullable `previous_request_event_id`, and `not_before`. It is a wake-up signal, never completion evidence. The evaluated payload carries only `evaluation_id`, `request_event_id`, `source_event_id`, `attempt_no`, `can_complete`, `failed_predicates`, and `evidence_digest`; the completed payload carries the immutable receipt/evaluation IDs and digests. Neither output copies mutable facts.

- [ ] **Step 7: Run canonical acceptance-contract and repository contract tests**

Run each command independently:

```bash
./gradlew :tests:contract:test --tests '*AcceptanceContractTest'
node --test tests/contract/node/acceptance-canonical-bytes.test.mjs
./gradlew :apps:control-plane:api:test --tests '*ModuleBoundaryTest'
pnpm contracts:test
```

Expected: every listed fixture exists and validates against its owning shape schema; both runtimes reproduce byte-identical Candidate and complete-set JCS and SHA-256 values; syntactically valid cross-document mismatches fail only the semantic validator; acceptance event examples match their closed variants; the module boundary and root contract suites remain green.

- [ ] **Step 8: Commit acceptance contracts**

```bash
git add settings.gradle \
  apps/control-plane/api/build.gradle \
  apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java \
  apps/control-plane/worker/build.gradle \
  apps/control-plane/modules/candidate-acceptance/build.gradle \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/package-info.java \
  tests/contract/build.gradle tests/integration/build.gradle tests/api/build.gradle \
  tests/security-negative/build.gradle tests/state-machine/build.gradle tests/fault-injection/build.gradle \
  contracts/json-schema/acceptance/candidate.schema.json \
  contracts/json-schema/acceptance/candidate-ci-attestation.schema.json \
  contracts/json-schema/acceptance/acceptance-run.schema.json \
  contracts/json-schema/acceptance/acceptance-evidence-set.schema.json \
  contracts/json-schema/acceptance/failure-disposition.schema.json \
  contracts/json-schema/acceptance/correction-run.schema.json \
  contracts/json-schema/acceptance/acceptance-continuity-attestation.schema.json \
  contracts/json-schema/acceptance/artifact-promotion.schema.json \
  contracts/events/acceptance/acceptance-events.schema.json \
  contracts/golden-fixtures/acceptance/candidate.json \
  contracts/golden-fixtures/acceptance/candidate.hash-input.jcs.json \
  contracts/golden-fixtures/acceptance/candidate.sha256 \
  contracts/golden-fixtures/acceptance/candidate-ci-attestation.json \
  contracts/golden-fixtures/acceptance/acceptance-run.json \
  contracts/golden-fixtures/acceptance/acceptance-evidence-set.json \
  contracts/golden-fixtures/acceptance/acceptance-evidence-set.hash-input.jcs.json \
  contracts/golden-fixtures/acceptance/acceptance-evidence-set.sha256 \
  contracts/golden-fixtures/acceptance/failure-disposition.json \
  contracts/golden-fixtures/acceptance/correction-run.json \
  contracts/golden-fixtures/acceptance/acceptance-continuity-attestation.json \
  contracts/golden-fixtures/acceptance/artifact-promotion.json \
  contracts/golden-fixtures/acceptance/events/completion-evaluation-requested.json \
  contracts/golden-fixtures/acceptance/events/completion-evaluated.json \
  contracts/golden-fixtures/acceptance/events/delivery-batch-completed.json \
  tests/contract/src/test/java/com/inforvans/accord/contracts/AcceptanceContractTest.java \
  tests/contract/src/test/java/com/inforvans/accord/contracts/AcceptanceFixtureSemanticValidator.java \
  tests/contract/node/acceptance-canonical-bytes.test.mjs
git commit -m "feat(acceptance): define immutable delivery evidence contracts"
```

### Task 2: Persist Orthogonal Candidate And Acceptance Lifecycles

**Files:**
- Create: `database/control-plane/migrations/V050__candidate_acceptance.sql`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/CandidateModels.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/AcceptanceModels.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/AcceptanceBindingValidator.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/infrastructure/JooqAcceptanceRepository.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CandidateLifecycleTest.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceBindingValidatorTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/CandidateAcceptanceMigrationIT.java`
- Test: `tests/state-machine/src/test/java/com/inforvans/accord/state/CandidateAcceptanceStateProperties.java`
- Verify: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/TenantRlsTest.java`
- Verify: `tests/architecture/verify-control-plane-fixtures.ps1`

- [ ] **Step 1: Add failing lifecycle, immutability, and uniqueness tests**

```java
@Test
void changingCandidateTreeCreatesANewCandidateInsteadOfUpdating() {
    var first = repository.insert(candidate(treeA));
    var second = service.rebuild(first.id(), facts(treeB));
    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(repository.get(first.id()).repositoryTreeSha()).isEqualTo(treeA);
    assertThat(repository.get(first.id()).validity()).isEqualTo(INVALIDATED);
}

@Test
void completedAcceptanceRunCannotBeEdited() {
    assertThatThrownBy(() -> repository.updateCriterion(completedRun.id(), criterion))
        .isInstanceOf(ImmutableFactViolation.class);
}

@Test
void syntacticallyValidRunCannotChangeCandidateRequirementOwnerBinding() {
    var original = exactTarget(candidate, candidate.requirements().getFirst());
    var target = new ExactAcceptanceTarget(
        original.candidateId(), original.candidatePayloadDigest(), original.requirementId(),
        original.candidateRequirementDigest(), original.revisionHash(), original.acceptanceCriteriaHash(),
        original.repositoryTreeSha(), original.artifactDigest(), original.environmentConfigurationHash(),
        original.acceptanceOwnerBindingId(), targetVersion + 1, original.acceptanceOwnerBindingDigest());
    assertThat(validator.validate(candidate, target))
        .extracting(BindingViolation::code)
        .contains("acceptance_owner_binding_version_mismatch");
}

@Test
void runCannotBorrowPayloadOrRequirementDigestFromAnotherCandidate() {
    var original = exactTarget(candidateA, candidateA.requirements().getFirst());
    var target = new ExactAcceptanceTarget(
        original.candidateId(), candidateB.payloadDigest(), original.requirementId(),
        candidateB.requirements().getFirst().candidateRequirementDigest(), original.revisionHash(),
        original.acceptanceCriteriaHash(), original.repositoryTreeSha(), original.artifactDigest(),
        original.environmentConfigurationHash(), original.acceptanceOwnerBindingId(),
        original.acceptanceOwnerBindingVersion(), original.acceptanceOwnerBindingDigest());
    assertThat(validator.validate(candidateA, target))
        .extracting(BindingViolation::code)
        .contains("candidate_payload_digest_mismatch", "candidate_requirement_digest_mismatch");
}
```

- [ ] **Step 2: Run and observe missing lifecycle types**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*CandidateLifecycleTest'`

Expected: compilation fails for Candidate and Acceptance types.

- [ ] **Step 3: Add tables and state types**

```java
enum CandidatePhase { BUILDING, VERIFIED, AWAITING_ACCEPTANCE, ACCEPTED, MERGE_AUTHORIZED, MERGED, RECONCILED, PROMOTED }
enum CandidateValidity { ACTIVE, INVALIDATED, EXPIRED }
enum AcceptancePhase { REQUESTED, NOTIFIED, IN_PROGRESS, COMPLETED, CANCELLED, EXPIRED }
enum AcceptanceResult { PENDING, PASSED, FAILED }
enum AcceptanceValidity { ACTIVE, INVALIDATED, SUPERSEDED }
enum CriterionResult { PASSED, FAILED, NOT_VERIFIABLE }

record AcceptanceOwnerBindingSnapshot(
    UUID roleBindingId,
    long bindingVersion,
    UUID accountId,
    UUID naturalPersonId,
    String role,
    String side,
    String scopeType,
    String scopeId,
    Instant startsAt,
    @Nullable Instant expiresAt,
    Digest bindingDigest
) {}

record CandidateRequirementBinding(
    UUID candidateId,
    UUID requirementId,
    UUID requirementRevisionId,
    Digest revisionHash,
    Digest acceptanceCriteriaHash,
    AcceptanceOwnerBindingSnapshot acceptanceOwner,
    Digest candidateRequirementDigest
) {}

record Candidate(
    UUID id,
    Digest payloadDigest,
    String repositoryTreeSha,
    @Nullable Digest artifactDigest,
    Digest environmentConfigurationHash,
    List<CandidateRequirementBinding> requirements
) {
    Candidate {
        requirements = List.copyOf(requirements);
    }
}

record ExactAcceptanceTarget(
    UUID candidateId,
    Digest candidatePayloadDigest,
    UUID requirementId,
    Digest candidateRequirementDigest,
    Digest revisionHash,
    Digest acceptanceCriteriaHash,
    String repositoryTreeSha,
    @Nullable Digest artifactDigest,
    Digest environmentConfigurationHash,
    UUID acceptanceOwnerBindingId,
    long acceptanceOwnerBindingVersion,
    Digest acceptanceOwnerBindingDigest
) {}

record BindingViolation(String code) {}

final class AcceptanceBindingValidator {
    List<BindingViolation> validate(Candidate candidate, ExactAcceptanceTarget target) {
        var violations = new ArrayList<BindingViolation>();
        var requirement = candidate.requirements().stream()
            .filter(value -> value.requirementId().equals(target.requirementId()))
            .findFirst();
        if (!candidate.id().equals(target.candidateId())) {
            violations.add(new BindingViolation("candidate_id_mismatch"));
        }
        if (!candidate.payloadDigest().constantTimeEquals(target.candidatePayloadDigest())) {
            violations.add(new BindingViolation("candidate_payload_digest_mismatch"));
        }
        if (!candidate.repositoryTreeSha().equals(target.repositoryTreeSha())) {
            violations.add(new BindingViolation("candidate_tree_mismatch"));
        }
        if (!Digest.constantTimeEqualsNullable(candidate.artifactDigest(), target.artifactDigest())) {
            violations.add(new BindingViolation("candidate_artifact_digest_mismatch"));
        }
        if (!candidate.environmentConfigurationHash().constantTimeEquals(target.environmentConfigurationHash())) {
            violations.add(new BindingViolation("candidate_environment_digest_mismatch"));
        }
        if (requirement.isEmpty()) {
            violations.add(new BindingViolation("requirement_not_in_candidate"));
            return List.copyOf(violations);
        }

        var bound = requirement.orElseThrow();
        if (!bound.candidateRequirementDigest().constantTimeEquals(target.candidateRequirementDigest())) {
            violations.add(new BindingViolation("candidate_requirement_digest_mismatch"));
        }
        if (!bound.revisionHash().constantTimeEquals(target.revisionHash())) {
            violations.add(new BindingViolation("revision_hash_mismatch"));
        }
        if (!bound.acceptanceCriteriaHash().constantTimeEquals(target.acceptanceCriteriaHash())) {
            violations.add(new BindingViolation("acceptance_criteria_hash_mismatch"));
        }
        if (!bound.acceptanceOwner().roleBindingId().equals(target.acceptanceOwnerBindingId())) {
            violations.add(new BindingViolation("acceptance_owner_binding_id_mismatch"));
        }
        if (bound.acceptanceOwner().bindingVersion() != target.acceptanceOwnerBindingVersion()) {
            violations.add(new BindingViolation("acceptance_owner_binding_version_mismatch"));
        }
        if (!bound.acceptanceOwner().bindingDigest().constantTimeEquals(target.acceptanceOwnerBindingDigest())) {
            violations.add(new BindingViolation("acceptance_owner_binding_digest_mismatch"));
        }
        return List.copyOf(violations);
    }
}
```

`AcceptanceBindingValidator.validate(candidate, target)` compares every `ExactAcceptanceTarget` field using constant-time digest equality and returns closed stable codes. Candidate construction computes the owner-binding digest from the Task 1 JCS field set after loading and locking the current Identity `role_binding`; acceptance assignment and final submission rerun the validator against the immutable Candidate row and current authorization facts. All rows contain tenant/repository scope, aggregate version, canonical payload digest, created-at, and immutable linkage. Use database triggers/privileges so canonical Candidate, Candidate Requirement snapshots, completed Run, criterion decisions, and signatures cannot be updated or deleted by the application role; validity changes are append-only overlay rows.

V050 creates exactly these eight `public` tenant tables: `delivery_candidate`, `delivery_candidate_requirement`, `delivery_candidate_ci_attestation`, `delivery_candidate_validity`, `acceptance_run`, `acceptance_criterion_decision`, `acceptance_run_signature`, and `acceptance_run_validity`. Every primary/unique key begins with `tenant_id`. Define composite tenant foreign keys from Candidate to `delivery_batch`; from Candidate Requirement to Candidate, the exact `requirement_revision`, the owner `role_binding`, and the owner `(account_id, natural_person_id)`; from CI attestation and Candidate validity to Candidate; from AcceptanceRun to the exact digest-bearing Candidate Requirement tuple; and from criterion decision, signature, and AcceptanceRun validity to AcceptanceRun. `delivery_candidate_validity` and `acceptance_run_validity` use primary keys `(tenant_id, candidate_id, validity_sequence)` and `(tenant_id, acceptance_run_id, validity_sequence)`, respectively; both store a closed `validity`, begin with sequence 1 `ACTIVE` in the aggregate-creation transaction, and enforce gapless append-only sequences. `delivery_candidate.phase`, `delivery_candidate.artifact_policy`, `acceptance_run.phase`, and `acceptance_run.result` use the closed enums shown above.

The migration must include these exact identity and equality constraints (the remaining lifecycle/audit columns stay as described above):

```sql
ALTER TABLE delivery_batch
  ADD CONSTRAINT delivery_batch_tenant_batch_repository_uk
  UNIQUE (tenant_id, batch_id, repository_id);

-- delivery_candidate
UNIQUE (tenant_id, candidate_id, candidate_payload_digest),
UNIQUE (tenant_id, candidate_id, repository_id, batch_id, candidate_payload_digest),
UNIQUE (tenant_id, candidate_id, artifact_digest),
UNIQUE (tenant_id, candidate_id, repository_tree_sha,
        environment_configuration_hash, artifact_policy, artifact_digest),
CONSTRAINT delivery_candidate_batch_repository_fk
  FOREIGN KEY (tenant_id, batch_id, repository_id)
  REFERENCES delivery_batch(tenant_id, batch_id, repository_id);

-- delivery_candidate_requirement
requirement_id uuid NOT NULL,
requirement_revision_id uuid NOT NULL,
revision_hash char(71) NOT NULL CHECK (revision_hash ~ '^sha256:[0-9a-f]{64}$'),
acceptance_criteria_hash char(71) NOT NULL CHECK (acceptance_criteria_hash ~ '^sha256:[0-9a-f]{64}$'),
acceptance_owner_binding_id uuid NOT NULL,
acceptance_owner_binding_version bigint NOT NULL CHECK (acceptance_owner_binding_version >= 1),
acceptance_owner_account_id uuid NOT NULL,
acceptance_owner_natural_person_id uuid NOT NULL,
acceptance_owner_role varchar(40) NOT NULL CHECK (acceptance_owner_role='BUSINESS_ACCEPTANCE_OWNER'),
acceptance_owner_side varchar(16) NOT NULL CHECK (acceptance_owner_side='BUSINESS'),
acceptance_owner_scope_type varchar(24) NOT NULL,
acceptance_owner_scope_id varchar(255) NOT NULL,
acceptance_owner_starts_at timestamptz NOT NULL,
acceptance_owner_expires_at timestamptz,
acceptance_owner_binding_digest char(71) NOT NULL CHECK (acceptance_owner_binding_digest ~ '^sha256:[0-9a-f]{64}$'),
candidate_requirement_digest char(71) NOT NULL CHECK (candidate_requirement_digest ~ '^sha256:[0-9a-f]{64}$'),
PRIMARY KEY (tenant_id, candidate_id, requirement_id),
UNIQUE (tenant_id, candidate_id, requirement_id, candidate_requirement_digest),
UNIQUE (tenant_id, candidate_id, requirement_id, candidate_requirement_digest,
        revision_hash, acceptance_criteria_hash, acceptance_owner_binding_id,
        acceptance_owner_binding_version, acceptance_owner_binding_digest),
FOREIGN KEY (tenant_id, candidate_id)
  REFERENCES delivery_candidate(tenant_id, candidate_id),
FOREIGN KEY (tenant_id, requirement_id, requirement_revision_id, revision_hash)
  REFERENCES requirement_revision(tenant_id, requirement_id, requirement_revision_id, revision_hash),
FOREIGN KEY (tenant_id, acceptance_owner_binding_id)
  REFERENCES role_binding(tenant_id, role_binding_id),
FOREIGN KEY (tenant_id, acceptance_owner_account_id, acceptance_owner_natural_person_id)
  REFERENCES human_account(tenant_id, account_id, natural_person_id);

-- acceptance_run target columns and exact cross-instance FK
candidate_payload_digest char(71) NOT NULL,
requirement_id uuid NOT NULL,
candidate_requirement_digest char(71) NOT NULL,
revision_hash char(71) NOT NULL,
acceptance_criteria_hash char(71) NOT NULL,
repository_tree_sha varchar(64) NOT NULL,
artifact_digest char(71),
environment_configuration_hash char(71) NOT NULL,
acceptance_owner_binding_id uuid NOT NULL,
acceptance_owner_binding_version bigint NOT NULL,
acceptance_owner_binding_digest char(71) NOT NULL,
FOREIGN KEY (tenant_id, candidate_id, requirement_id, candidate_requirement_digest,
             revision_hash, acceptance_criteria_hash, acceptance_owner_binding_id,
             acceptance_owner_binding_version, acceptance_owner_binding_digest)
  REFERENCES delivery_candidate_requirement(
    tenant_id, candidate_id, requirement_id, candidate_requirement_digest,
    revision_hash, acceptance_criteria_hash, acceptance_owner_binding_id,
    acceptance_owner_binding_version, acceptance_owner_binding_digest),
UNIQUE (tenant_id, acceptance_run_id, candidate_id, requirement_id,
        candidate_requirement_digest, receipt_digest);
```

`assert_candidate_owner_binding_snapshot()` runs `BEFORE INSERT` on `delivery_candidate_requirement`, locks the referenced `role_binding`, and rejects unless its project, account, role=`BUSINESS_ACCEPTANCE_OWNER`, side=`BUSINESS`, scope type/ID coverage, start/expiry, and `binding_version` exactly equal every inserted snapshot column; it also locks/compares the composite human account identity. The application computes and supplies the RFC 8785 binding digest, while this trigger proves the digest is attached to the exact authoritative version being snapshotted. `assert_acceptance_run_exact_target()` runs before Run insert, joins and locks Candidate plus Candidate Requirement, and compares `candidate_payload_digest`, tree, artifact using `IS NOT DISTINCT FROM`, environment, revision/criteria, Requirement digest, and all owner fields; it raises `23514` with stable constraint names such as `acceptance_run_candidate_payload_mismatch` and `acceptance_run_artifact_digest_mismatch`. Thus JSON Schema checks shape, the typed validator checks semantics, and PostgreSQL prevents a service bug from persisting a cross-instance mismatch.

After all eight tables, immutable/append-only protections, indexes, triggers, and foreign keys exist, and before runtime DML grants, execute these individual calls:

```sql
SELECT accord_security.enforce_tenant_table('public.delivery_candidate'::regclass);
SELECT accord_security.enforce_tenant_table('public.delivery_candidate_requirement'::regclass);
SELECT accord_security.enforce_tenant_table('public.delivery_candidate_ci_attestation'::regclass);
SELECT accord_security.enforce_tenant_table('public.delivery_candidate_validity'::regclass);
SELECT accord_security.enforce_tenant_table('public.acceptance_run'::regclass);
SELECT accord_security.enforce_tenant_table('public.acceptance_criterion_decision'::regclass);
SELECT accord_security.enforce_tenant_table('public.acceptance_run_signature'::regclass);
SELECT accord_security.enforce_tenant_table('public.acceptance_run_validity'::regclass);
```

Create `CandidateAcceptanceMigrationIT.java` with PostgreSQL 17.5. Its setup is exactly `postgres.start()`, `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)`, then `Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).target("050").load().migrate()`; it must not create roles or add grants after Flyway. Seed colliding IDs in tenants A and B through the migrator. For every V050 table, prove `accord_api` under tenant A cannot select, insert, update, or delete tenant-B rows and that missing tenant context fails closed. Query `pg_constraint` to verify `delivery_batch_tenant_batch_repository_uk` and every composite tenant FK above, including the exact `(tenant_id, batch_id, repository_id)` Candidate-to-DeliveryBatch binding. A Candidate insert using an existing batch with another repository, and a Candidate insert combining the batch ID from one batch with the repository ID from another, must each fail `delivery_candidate_batch_repository_fk` with SQLSTATE `23503` and leave no Candidate, audit, or outbox row. Reject tenant-A Candidate requirements referencing a tenant-B Candidate, revision, role binding, or natural person and tenant-A AcceptanceRuns/children referencing tenant-B parents. Insert separately shape-valid AcceptanceRuns with the wrong Candidate payload, Requirement digest, tree, artifact, environment, revision, criteria, owner-binding ID, owner-binding version, and owner-binding digest; each must fail its named FK/trigger constraint and leave no Run/signature/outbox row. Change the live role-binding version after Candidate insertion and prove the Candidate snapshot remains byte-identical while subsequent assignment/submission is rejected as drift. Query the catalogs for all eight tables and require enabled and forced RLS plus `tenant_isolation` as `FOR ALL TO PUBLIC` with exact `USING` and `WITH CHECK` expressions.

- [ ] **Step 4: Implement command-only transitions with CAS/idempotency**

Allowed transitions match the specification. New tree/revision/criteria/artifact/environment/owner/hold facts invalidate the current candidate and require a new candidate; they never mutate the original. A role-binding version, account/natural-person identity, scope, active interval, or canonical binding-digest change is owner drift even when the role-binding UUID is unchanged. Acceptance result becomes final only with the signed overall submission.

- [ ] **Step 5: Run state-machine and database immutability tests**

Run:

```bash
./gradlew :apps:control-plane:modules:candidate-acceptance:test :tests:state-machine:test :tests:integration:test --tests '*Candidate*' --tests '*Acceptance*' --tests '*CandidateAcceptanceMigrationIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; arbitrary transition sequences never reach accepted/promoted without every guard; every Candidate Requirement retains an immutable owner-binding snapshot inside both JCS digests; semantic and database mismatch tests fail closed; tenant-A/B collisions and cross-tenant composite references are rejected; all eight V050 tables satisfy the global exact forced-RLS contract; and every control-plane fixture bootstraps roles before Flyway.

- [ ] **Step 6: Commit lifecycle persistence**

```bash
git add database/control-plane/migrations/V050__candidate_acceptance.sql \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/CandidateModels.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/AcceptanceModels.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/AcceptanceBindingValidator.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/infrastructure/JooqAcceptanceRepository.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CandidateLifecycleTest.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceBindingValidatorTest.java \
  tests/integration/src/test/java/com/inforvans/accord/integration/CandidateAcceptanceMigrationIT.java \
  tests/state-machine/src/test/java/com/inforvans/accord/state/CandidateAcceptanceStateProperties.java
git commit -m "feat(acceptance): persist immutable candidate lifecycles"
```

### Task 3: Verify Customer-CI Candidate Evidence

**Files:**
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/CandidateService.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CandidateWorkflow.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CandidateVerificationTest.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CandidateMergeReconciliationTest.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/CandidateVerificationRecoveryTest.java`

- [ ] **Step 1: Add mismatched tree, stale default, manifest, watermark, artifact, signer, and replay tests**

```java
@Test
void candidateTreeMustMatchCiProvenanceAndProviderMetadata() {
    var result = service.ingest(ciCandidate(treeA), providerFacts(treeB));
    assertThat(result.problemCode()).isEqualTo("candidate_tree_mismatch");
}

@Test
void contextWatermarkGapPreventsCandidateVerification() {
    assertThat(service.ingest(validCandidate, factsWithWatermarkGap).problemCode())
        .isEqualTo("context_watermark_incomplete");
}

@Test
void controlledMergeOfThisCandidateReconcilesWithoutInvalidatingIt() {
    var merged = service.observeDefaultChange(
        activeCandidate,
        controlledMergeReceipt(activeCandidate.id(), activeCandidate.repositoryTreeSha()));
    assertThat(merged.phase()).isEqualTo(RECONCILED);
    assertThat(merged.validity()).isEqualTo(ACTIVE);
    assertThat(merged.invalidationCause()).isNull();
}

@Test
void uncertainOutcomeOrAncestryForExactOwnedMergeStaysActiveAndReconciling() {
    for (var uncertainOutcome : List.of(true, false)) {
        var candidate = fixtures.persistActiveCandidate();
        var observation = uncertainOutcome
            ? uncertainOwnedMergeOutcome(candidate)
            : ownedMergeWithUnprovenAncestry(candidate);
        var heldPhase = uncertainOutcome ? MERGE_AUTHORIZED : MERGED;
        var validityBefore = repository.validityHistory(candidate.id());
        var result = service.observeDefaultChange(candidate, observation);

        assertThat(result.phase()).isEqualTo(heldPhase);
        assertThat(result.validity()).isEqualTo(ACTIVE);
        assertThat(result.consistencyState()).isEqualTo(RECONCILING);
        assertThat(result.invalidationCause()).isNull();
        assertThat(repository.validityHistory(candidate.id())).isEqualTo(validityBefore);
    }
}

@Test
void unexplainedDefaultHeadMovementInvalidatesWhilePreservingCandidate() {
    var result = service.observeDefaultChange(
        activeCandidate, providerFactWithoutOwnedMergeIntent(hotfixTree));
    assertThat(result.validity()).isEqualTo(INVALIDATED);
    assertThat(result.invalidationCause()).isEqualTo(UNEXPLAINED_DEFAULT_HEAD_DRIFT);
    assertThat(repository.get(activeCandidate.id()).payloadDigest())
        .isEqualTo(activeCandidate.payloadDigest());
}
```

- [ ] **Step 2: Run and verify CandidateService is absent**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*CandidateVerificationTest'`

Expected: compilation fails for `CandidateService`.

- [ ] **Step 3: Request candidate construction only after development completeness**

Require all active Commitments, valid WorkItemCompletions, cancellation cleanup/no-code proofs, exact effective manifest, continuous Context watermark, no blocking holds/actions, exact delivery head, and current default head. Persist a candidate-build intent before invoking customer CI and reconcile uncertain results through CI/Provider fact APIs.

- [ ] **Step 4: Verify the complete evidence tuple**

Validate DSSE domain/signer/workload identity, tenant/repository, default base, delivery head, candidate commit/tree, code fingerprint, Context version/watermark, test attestation, Requirement/criteria hashes, every frozen acceptance-owner binding ID/version/account/natural-person/digest, environment configuration, artifact profile/digest/immutable locator, and build provenance. Recompute each Candidate Requirement JCS digest and the complete Candidate payload digest before insertion. Cross-check commit/tree metadata with Provider facts without fetching source.

- [ ] **Step 5: Classify expected Candidate merge separately from invalidating drift**

When Provider facts report a default-head change, first lock and match the persisted final-merge intent and Provider/reconciliation receipt from Git Delivery Tasks 1-8 to the same tenant, repository, batch, Candidate ID/payload digest, expected prior default head, source head, merge method, and expected Candidate tree. An owned successful merge advances `MERGE_AUTHORIZED -> MERGED`; after the actual default tree equals the Candidate tree, ancestry is proven, and the exact `LINEAGE_PROMOTED` ContextMergeReceipt covers the Candidate watermark, standard mode advances `MERGED -> RECONCILED` while validity remains `ACTIVE`. Provider-created merge commit SHA may differ; the full tree may not. Replayed observations are idempotent and never append a validity overlay.

M4 must compile, migrate, and run without importing a class, table, or service introduced by Git Delivery Tasks 9-11. For a strict-mode batch, the Task 1 versioned delivery contract exposes strict assurance as unavailable until those downstream tasks publish the exact one-time authorization, merge outcome, and policy-attestation facts. Candidate validity remains `ACTIVE`, phase stays `MERGED`, and consistency stays `RECONCILING`; no default-tree observation is reclassified as drift. Tasks 9-11 later supply those facts through the existing source-free contract and emit a normal reevaluation trigger, after which this same state machine may advance to `RECONCILED` without rewriting Candidate history.

An observation that still matches the exact owned merge intent but has an uncertain Provider outcome or unproven ancestry is not evidence of drift. Keep validity `ACTIVE`, hold the Candidate at `MERGE_AUTHORIZED` or `MERGED` as appropriate, set consistency to `RECONCILING`, and create/reuse one reconciliation ActionRequest without appending a Candidate-validity overlay. Only a confirmed default change not explained by that controlled merge, a confirmed unrelated hotfix/emergency change, or a controlled merge with a confirmed mismatched result tree invalidates the Candidate. Delivery tree, effective manifest, Revision, criteria, artifact, environment, any owner-binding field/digest, Context health, hold, or signer-trust drift also invalidates it. Store a closed cause, create the required reconciliation/rebuild ActionRequest, preserve the original Candidate and merge evidence, and never classify uncertainty or the Candidate's own exact merge as generic default-head drift.

- [ ] **Step 6: Run verification and crash-recovery tests**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test :tests:fault-injection:test --tests '*CandidateVerification*'`

Expected: all tests pass; before/after-CI-call crashes produce one Candidate or one reconciled fail-closed intent; every owner-binding drift is detected; the Candidate's exact controlled default merge reaches `RECONCILED` without invalidation; exact owned-merge uncertainty remains `ACTIVE + RECONCILING` with no validity overlay; confirmed hotfix, unexplained unrelated head movement, and wrong result tree invalidate exactly once.

- [ ] **Step 7: Commit Candidate verification**

```bash
git add apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/CandidateService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CandidateWorkflow.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CandidateVerificationTest.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CandidateMergeReconciliationTest.java \
  tests/fault-injection/src/test/java/com/inforvans/accord/fault/CandidateVerificationRecoveryTest.java
git commit -m "feat(acceptance): verify exact customer-ci candidates"
```

### Task 4: Execute Exact Business AcceptanceRuns

**Files:**
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceService.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/api/AcceptanceController.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceAuthorizationTest.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/AcceptanceAuthorizationSecurityTest.java`

- [ ] **Step 1: Add tests for wrong role/side/candidate/hash/attachment/binding, supplier self-acceptance, and duplicate submit**

```java
@Test
void developmentEvidenceCannotSubstituteForBusinessAcceptance() {
    assertThat(service.submit(developmentPrincipal, run, allPassed).problemCode())
        .isEqualTo("business_acceptance_owner_required");
}

@Test
void externalSupplierCannotAcceptOwnAssignedWork() {
    assertThat(service.submit(supplierWithBusinessRole, runForSupplierWork, allPassed).problemCode())
        .isEqualTo("supplier_self_acceptance_forbidden");
}

@Test
void sameBindingIdWithALaterVersionCannotSubmitFrozenCandidateRequirement() {
    identity.advanceBindingVersion(run.acceptanceOwnerBindingId());
    assertThat(service.submit(originalOwner, run, allPassed).problemCode())
        .isEqualTo("acceptance_owner_binding_drift");
    assertThat(repository.signatures(run.id())).isEmpty();
}
```

- [ ] **Step 2: Run and verify AcceptanceService is absent**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*AcceptanceAuthorizationTest'`

Expected: compilation fails for `AcceptanceService`.

- [ ] **Step 3: Create access-preflighted ActionRequests**

For each Candidate Requirement, create one Run bound to `candidate_id`, `candidate_payload_digest`, `requirement_id`, `candidate_requirement_digest`, revision/criteria/tree/artifact/environment, and the frozen owner-binding ID/version/digest. `AcceptanceBindingValidator` must return no violations before insert, assignment, draft update, and final submit. Before assignment and final submit, also lock/recheck the current business-side role binding against the Candidate snapshot, fresh authentication, exact Candidate validity, contractual attachment access, no hold, and supplier separation. Missing access creates one `access_fix` request and holds acceptance. Any owner ID/version/account/natural-person/scope/interval/digest drift invalidates the Candidate through an append-only validity row; it cannot be repaired by silently retargeting the Run.

- [ ] **Step 4: Record criterion evidence then sign one overall result**

Each criterion records `PASSED`, `FAILED`, or `NOT_VERIFIABLE`, evidence refs/digests, comment, actor, and time. Final result is `PASSED` only when every required criterion passes; any failed/not-verifiable criterion yields `FAILED`. Submit once with `expected_version` and idempotency key, create a long-lived signed receipt whose envelope includes the exact target and criterion-decision digest, and emit `acceptance_run.finalized.v1` in the same audit/outbox transaction. The resulting `receipt_digest` is the only direct-run digest eligible for a Task 7 evidence-set entry. Duplicate submission returns the original receipt bytes and outbox event; no alternate operation can finalize or retarget the Run.

- [ ] **Step 5: Run authorization, stale-target, and idempotency tests**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test :tests:security-negative:test --tests '*AcceptanceAuthorization*'`

Expected: all tests pass; no old Candidate/Revision/Requirement digest/owner-binding snapshot can be accepted; each Run proves one and only one Candidate Requirement; retries create one signed result and one finalization event.

- [ ] **Step 6: Commit business acceptance**

```bash
git add apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/api/AcceptanceController.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceAuthorizationTest.java \
  tests/security-negative/src/test/java/com/inforvans/accord/security/AcceptanceAuthorizationSecurityTest.java
git commit -m "feat(acceptance): add exact business acceptance runs"
```

### Task 5: Classify Failures And Run Same-Revision Corrections

**Files:**
- Create: `database/control-plane/migrations/V051__correction_and_continuity.sql`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/CorrectionModels.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/FailureDispositionService.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/CorrectionRunService.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/FailureDispositionTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/CorrectionContinuityMigrationIT.java`
- Test: `tests/state-machine/src/test/java/com/inforvans/accord/state/FailureDispositionAndCorrectionRunProperties.java`
- Verify: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/TenantRlsTest.java`
- Verify: `tests/architecture/verify-control-plane-fixtures.ps1`
- Create: `docs/runbooks/correction-limit.md`

- [ ] **Step 1: Add failing classification and correction-limit tests**

```java
@Test
void implementationDefectPreservesRequirementRevision() {
    var correction = service.classify(
        businessOwner, developmentPrincipal, failedRun, IMPLEMENTATION_DEFECT);
    assertThat(correction.revisionHash()).isEqualTo(failedRun.revisionHash());
}

@Test
void requirementChangeCannotOpenACorrectionRun() {
    var result = service.classify(
        businessOwner, developmentPrincipal, failedRun, REQUIREMENT_CHANGE);
    assertThat(result).isInstanceOf(NewRequirementRevisionRequired.class);
}
```

- [ ] **Step 2: Run and verify correction services are absent**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*FailureDispositionTest'`

Expected: compilation fails for failure disposition types.

- [ ] **Step 3: Implement bilateral immutable FailureDisposition**

```java
enum FailureClass { IMPLEMENTATION_DEFECT, REQUIREMENT_CHANGE, ENVIRONMENT_ISSUE }
enum CorrectionPhase { OPEN, IN_PROGRESS, READY_FOR_REACCEPTANCE, VERIFIED, CANCELLED }
record FailureDisposition(
    UUID failedRunId,
    FailureClass classification,
    Digest scopeDigest,
    Digest businessReceipt,
    Digest developmentReceipt
) {}
```

Both business acceptance owner and development principal classify the exact failed run. Disagreement remains pending with an ActionRequest. `ENVIRONMENT_ISSUE` restores or repairs the declared environment and creates another Run against the same Candidate only when `environment_configuration_hash` remains identical; a changed environment identity/configuration creates a new Candidate. It cannot hide a changed artifact or tree.

V051 creates exactly these eight `public` tenant tables for this task and Task 6: `failure_disposition`, `failure_disposition_confirmation`, `correction_run`, `correction_run_work_item`, `correction_limit_decision`, `acceptance_continuity_assessment`, `acceptance_continuity_attestation`, and `acceptance_continuity_attestation_validity`. Every primary/unique key begins with `tenant_id`. Define composite tenant foreign keys from disposition to the failed `acceptance_run`; disposition confirmation to disposition; CorrectionRun to disposition, the failed run, and any replacement `delivery_candidate`; CorrectionRun work item to CorrectionRun and `work_item`; limit decision to CorrectionRun; continuity assessment to the source AcceptanceRun and both source/target Candidates; continuity attestation to its assessment, source Run/receipt, target Candidate, and exact target Candidate Requirement tuple; and each append-only validity overlay to its attestation.

The attestation stores non-null `requirement_id`, `target_candidate_requirement_digest`, `source_acceptance_run_id`, `source_acceptance_receipt_digest`, `scope_digest`, `criteria_digest`, `comparison_basis_digest`, `signer_binding_version`, `valid_until`, and `envelope_digest`, with this reference key for Task 7:

```sql
UNIQUE (tenant_id, attestation_id, target_candidate_id, requirement_id,
        target_candidate_requirement_digest, envelope_digest),
FOREIGN KEY (tenant_id, target_candidate_id, requirement_id,
             target_candidate_requirement_digest)
  REFERENCES delivery_candidate_requirement(
    tenant_id, candidate_id, requirement_id, candidate_requirement_digest),
FOREIGN KEY (tenant_id, source_acceptance_run_id, source_candidate_id,
             requirement_id, source_candidate_requirement_digest,
             source_acceptance_receipt_digest)
  REFERENCES acceptance_run(
    tenant_id, acceptance_run_id, candidate_id, requirement_id,
    candidate_requirement_digest, receipt_digest);
```

`acceptance_continuity_attestation_validity` has `(tenant_id, attestation_id, validity_sequence)` as its primary key, closed state `ACTIVE | INVALIDATED | EXPIRED`, a non-null cause/evidence digest for non-active rows, and a uniqueness/trigger rule that sequences are gapless and append-only. The original signed envelope never changes. Run the standard installer after every table, check, append-only control, and foreign key exists and before granting runtime DML:

```sql
SELECT accord_security.enforce_tenant_table('public.failure_disposition'::regclass);
SELECT accord_security.enforce_tenant_table('public.failure_disposition_confirmation'::regclass);
SELECT accord_security.enforce_tenant_table('public.correction_run'::regclass);
SELECT accord_security.enforce_tenant_table('public.correction_run_work_item'::regclass);
SELECT accord_security.enforce_tenant_table('public.correction_limit_decision'::regclass);
SELECT accord_security.enforce_tenant_table('public.acceptance_continuity_assessment'::regclass);
SELECT accord_security.enforce_tenant_table('public.acceptance_continuity_attestation'::regclass);
SELECT accord_security.enforce_tenant_table('public.acceptance_continuity_attestation_validity'::regclass);
```

Create `CorrectionContinuityMigrationIT.java` with PostgreSQL 17.5. Call `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)` immediately after `postgres.start()` and before `Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).target("051").load().migrate()`; do not create roles or repair grants after migration. Seed colliding IDs for tenants A and B through the migrator and prove that `accord_api` under tenant A cannot select or mutate tenant-B rows in any of the eight tables and that missing tenant context fails closed. Query `pg_constraint` for every composite tenant FK above; reject tenant-A dispositions, confirmations, CorrectionRuns, WorkItem links, limit decisions, assessments, attestations, and validity overlays that point to tenant-B parents. Reject a continuity attestation with a wrong target Candidate Requirement digest, wrong source receipt digest, different Requirement, or missing target Candidate tuple. Assert all eight tables meet the exact enabled/forced, `FOR ALL TO PUBLIC`, `USING`, and `WITH CHECK` catalog contract.

- [ ] **Step 4: Implement CorrectionRun under the same Revision**

Bind failed Run, defect scope, revision hash, correction WorkItems/owners, Context Patches, replacement Candidate, and reacceptance result. Old Run stays immutable and does not apply to the new Candidate. CorrectionRun transition back to batch development is explicit and audited.

- [ ] **Step 5: Enforce the project correction limit**

At the default limit of 3 consecutive corrections, require both principals to decide among continued defect correction, new Requirement Revision, rollback, or batch termination. The limit decision uses fresh authentication and cannot merely increment a counter.

- [ ] **Step 6: Run classification, concurrency, and history tests**

Run:

```bash
./gradlew :apps:control-plane:modules:candidate-acceptance:test :tests:state-machine:test :tests:integration:test --tests '*FailureDisposition*' --tests '*CorrectionRun*' --tests '*CorrectionContinuityMigrationIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; implementation defects never mutate semantic Revision; requirement changes never reuse old confirmations; continuity history is append-only and exactly bound; tenant-A/B collisions and cross-tenant composite references are rejected; all eight V051 tables satisfy the global exact forced-RLS contract; and every fixture bootstraps roles before Flyway.

- [ ] **Step 7: Commit correction workflow**

```bash
git add database/control-plane/migrations/V051__correction_and_continuity.sql \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/CorrectionModels.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/FailureDispositionService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/CorrectionRunService.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/FailureDispositionTest.java \
  tests/integration/src/test/java/com/inforvans/accord/integration/CorrectionContinuityMigrationIT.java \
  tests/state-machine/src/test/java/com/inforvans/accord/state/FailureDispositionAndCorrectionRunProperties.java \
  docs/runbooks/correction-limit.md
git commit -m "feat(acceptance): classify failures and correct implementation"
```

### Task 6: Assess Acceptance Continuity Across Later Candidates

**Files:**
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceContinuityService.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceContinuityTest.java`

- [ ] **Step 1: Add unaffected, affected, uncertain, and high-risk tests**

```java
@Test
void changedPermissionClaimCannotReceiveAutomaticContinuity() {
    var result = service.assess(oldRun, newCandidate, change(true));
    assertThat(result.status()).isEqualTo(AFFECTED);
    assertThat(result.criteriaForReacceptance()).contains("AC-PERMISSION-01");
}

@Test
void uncertainRuntimeBehaviorRequiresHumanConfirmationOrReacceptance() {
    assertThat(service.assess(oldRun, newCandidate, dynamicUnknown).status())
        .isEqualTo(UNCERTAIN);
}

@Test
void continuityCannotCoverADifferentTargetRequirementBinding() {
    var attestation = validAttestation.withTargetCandidateRequirementDigest(
        otherRequirement.candidateRequirementDigest());
    assertThat(service.attest(attestation).problemCode())
        .isEqualTo("continuity_target_binding_mismatch");
}
```

- [ ] **Step 2: Run and verify continuity service is absent**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*AcceptanceContinuityTest'`

Expected: compilation fails for `AcceptanceContinuityService`.

- [ ] **Step 3: Compare structured evidence, never source**

For one source AcceptanceRun and one target Candidate Requirement, compare revision/criteria/owner-binding digests, old/new Candidate trees and Context versions, claim IDs/digests, module/interface/data/permission/state/test mappings, Patch provenance, artifact/environment hashes, support coverage, and holds. Produce `UNAFFECTED`, `AFFECTED`, or `UNCERTAIN` with exact evidence and affected criteria. A multi-Requirement Candidate therefore has one independently reviewable continuity decision per reused Requirement; an assessment cannot cover an unlisted or differently bound Requirement.

- [ ] **Step 4: Require a signed continuity attestation for reuse**

`UNAFFECTED` requires active policy permission, no high-risk/coverage/runtime disqualifier, development-principal confirmation, validity period, exact source Run/receipt/Candidate/Requirement digest, exact target Candidate/Requirement digest, criteria/scope digest, analysis basis, and signed envelope. Recompute and compare the envelope digest before insert, then emit `acceptance_continuity_attestation.recorded.v1` in the same transaction. `AFFECTED` creates targeted reacceptance; `UNCERTAIN` requires human classification or targeted/full reacceptance. New contradictory evidence appends a validity overlay and `acceptance_continuity_attestation.invalidated.v1`; it never edits the envelope. Only an `ACTIVE`, unexpired attestation may become a Task 7 evidence entry.

- [ ] **Step 5: Prove historical facts never move**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*AcceptanceContinuityTest'`

Expected: tests pass; old Run signatures remain bound to old Candidate; every attestation targets exactly one current Candidate Requirement; and only the new active continuity attestation supplies current eligibility and its own evidence-envelope digest.

- [ ] **Step 6: Commit continuity assessment**

```bash
git add apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceContinuityService.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceContinuityTest.java
git commit -m "feat(acceptance): attest evidence-based acceptance continuity"
```

### Task 7: Promote The Exact Accepted Artifact Digest

**Files:**
- Create: `database/control-plane/migrations/V052__artifact_promotion.sql`
- Modify: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/infrastructure/JooqAcceptanceRepository.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceEvidenceSetService.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/ArtifactPromotionService.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/ArtifactPromotionWorkflow.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/ArtifactRegistryPort.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceEvidenceSetTest.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceEvidenceSetRaceTest.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/ArtifactPromotionTest.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/ArtifactPromotionRecoveryTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/ArtifactPromotionMigrationIT.java`
- Verify: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/TenantRlsTest.java`
- Verify: `tests/architecture/verify-control-plane-fixtures.ps1`
- Create: `docs/runbooks/artifact-promotion-uncertain.md`

- [ ] **Step 1: Add complete-set, digest mismatch, mutable locator, rebuild, timeout, and race tests**

```java
@Test
void completeSetContainsOneExactEvidenceItemForEveryCandidateRequirement() {
    var sealedSet = evidenceSets.seal(
        candidateWithRequirements(reqA, reqB, reqC),
        List.of(passedRun(reqA), continuity(reqB), passedRun(reqC)));
    assertThat(sealedSet.entries())
        .extracting(AcceptanceEvidenceSelection::requirementId)
        .containsExactlyInAnyOrder(reqA.id(), reqB.id(), reqC.id());
    assertThat(sealedSet.entries()).filteredOn(DirectAcceptanceEvidence.class::isInstance).hasSize(2);
    assertThat(sealedSet.entries()).filteredOn(ContinuityAcceptanceEvidence.class::isInstance).hasSize(1);
    assertThat(sealedSet.completeSetDigest()).isEqualTo(canonicalCompleteSetDigest(sealedSet));
    assertThat(repository.candidate(sealedSet.candidateId()).phase()).isEqualTo(ACCEPTED);
}

@Test
void missingDuplicateExtraOrStaleRequirementEvidenceCannotSeal() {
    assertThat(evidenceSets.seal(
        candidateWithRequirements(reqA, reqB), List.of(passedRun(reqA))).problemCode())
        .isEqualTo("acceptance_evidence_incomplete");
    assertThat(evidenceSets.seal(
        candidateWithRequirements(reqA), List.of(passedRun(reqA), continuity(reqA))).problemCode())
        .isEqualTo("acceptance_evidence_duplicate_requirement");
    assertThat(evidenceSets.seal(
        candidateWithRequirements(reqA), List.of(passedRun(reqA), passedRun(reqB))).problemCode())
        .isEqualTo("acceptance_evidence_not_in_candidate");
}

@Test
void shapeValidPromotionWithAnotherCompleteSetDigestFailsSemanticValidation() {
    var command = new PromoteExistingDigest(
        promotionCommand.candidateId(), promotionCommand.candidatePayloadDigest(),
        promotionCommand.evidenceSetId(), otherDigest, promotionCommand.source(),
        promotionCommand.expectedArtifactDigest(), promotionCommand.targetChannel(),
        promotionCommand.idempotencyKey());
    assertThat(service.promote(command).problemCode())
        .isEqualTo("acceptance_complete_set_digest_mismatch");
    assertThat(registry.calls()).isEmpty();
}

@Test
void promotionRejectsARegistryResultWithADifferentArtifactDigest() {
    registry.returnPromotedDigest(otherDigest);
    assertThat(service.promote(promotionCommand).problemCode()).isEqualTo("promoted_digest_mismatch");
}

@Test
void serviceExposesNoBuildOperation() {
    assertThat(Arrays.stream(ArtifactRegistryPort.class.getDeclaredMethods())
        .map(Method::getName)).doesNotContain("build");
}
```

- [ ] **Step 2: Run and verify evidence-set and promotion services are absent**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*AcceptanceEvidenceSetTest' --tests '*ArtifactPromotionTest'`

Expected: compilation fails for `AcceptanceEvidenceSetService` and `ArtifactPromotionService`.

- [ ] **Step 3: Seal one canonical, append-only acceptance-evidence set**

```java
sealed interface AcceptanceEvidenceSelection
    permits DirectAcceptanceEvidence, ContinuityAcceptanceEvidence {
    UUID requirementId();
    Digest candidateRequirementDigest();
    UUID evidenceId();
    Digest evidenceEnvelopeDigest();
}

record DirectAcceptanceEvidence(
    UUID requirementId,
    Digest candidateRequirementDigest,
    UUID evidenceId,
    Digest evidenceEnvelopeDigest
) implements AcceptanceEvidenceSelection {}

record ContinuityAcceptanceEvidence(
    UUID requirementId,
    Digest candidateRequirementDigest,
    UUID evidenceId,
    Digest evidenceEnvelopeDigest
) implements AcceptanceEvidenceSelection {}

record SealedAcceptanceEvidenceSet(
    UUID evidenceSetId,
    UUID candidateId,
    Digest candidatePayloadDigest,
    List<AcceptanceEvidenceSelection> entries,
    Digest completeSetDigest
) {
    SealedAcceptanceEvidenceSet {
        entries = List.copyOf(entries);
    }
}
```

`AcceptanceEvidenceSetService.seal` runs serializably. It locks the active `AWAITING_ACCEPTANCE` Candidate, every Candidate Requirement, latest Run/attestation validity rows, and selected immutable evidence. It requires exactly one selection for each Candidate Requirement and no extras. A direct selection must be `COMPLETED + PASSED + ACTIVE`, belong to that Candidate Requirement, and expose its signed `receipt_digest`; a continuity selection must be `ACTIVE`, unexpired, target the same Candidate Requirement, and expose its `envelope_digest`. Recompute every Candidate Requirement digest, sort entries by lowercase Requirement UUID, construct the exact Task 1 JCS envelope, and recompute `acceptance_complete_set_digest`. In one transaction insert the set and all joins, CAS `AWAITING_ACCEPTANCE -> ACCEPTED`, append audit, and emit one `candidate.acceptance_evidence_set.sealed.v1` plus the corresponding `candidate.phase_changed.v1`; any failure rolls back all of them. Same-key replay returns the same set/phase result and 64 competing selections can produce only one set and one phase transition. Make both set tables append-only. There is no update, replace-selection, or copy-from-prior-Candidate operation. A later evidence invalidation invalidates the Candidate and makes promotion/completion ineligible; it does not rewrite the sealed set.

- [ ] **Step 4: Persist complete sets and digest-locked assurance paths**

V052 creates exactly five `public` tenant tables: `candidate_acceptance_evidence_set`, `candidate_acceptance_evidence`, `artifact_promotion`, `artifact_promotion_attempt`, and `source_tree_assurance_receipt`. Every primary/unique key begins with `tenant_id`. The core schema is:

```sql
CREATE TABLE candidate_acceptance_evidence_set (
    tenant_id uuid NOT NULL,
    evidence_set_id uuid NOT NULL,
    repository_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    candidate_payload_digest char(71) NOT NULL CHECK (candidate_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    requirement_count integer NOT NULL CHECK (requirement_count > 0),
    acceptance_complete_set_digest char(71) NOT NULL CHECK (acceptance_complete_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    sealed_at timestamptz NOT NULL,
    source_event_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, evidence_set_id),
    UNIQUE (tenant_id, candidate_id),
    UNIQUE (tenant_id, evidence_set_id, candidate_id),
    UNIQUE (tenant_id, evidence_set_id, candidate_id, acceptance_complete_set_digest),
    FOREIGN KEY (tenant_id, candidate_id, repository_id, batch_id, candidate_payload_digest)
      REFERENCES delivery_candidate(
        tenant_id, candidate_id, repository_id, batch_id, candidate_payload_digest)
);

CREATE TABLE candidate_acceptance_evidence (
    tenant_id uuid NOT NULL,
    evidence_set_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    requirement_id uuid NOT NULL,
    candidate_requirement_digest char(71) NOT NULL CHECK (candidate_requirement_digest ~ '^sha256:[0-9a-f]{64}$'),
    evidence_kind varchar(32) NOT NULL CHECK (evidence_kind IN ('ACCEPTANCE_RUN','CONTINUITY_ATTESTATION')),
    acceptance_run_id uuid,
    continuity_attestation_id uuid,
    evidence_envelope_digest char(71) NOT NULL CHECK (evidence_envelope_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (tenant_id, evidence_set_id, requirement_id),
    FOREIGN KEY (tenant_id, evidence_set_id, candidate_id)
      REFERENCES candidate_acceptance_evidence_set(tenant_id, evidence_set_id, candidate_id),
    FOREIGN KEY (tenant_id, candidate_id, requirement_id, candidate_requirement_digest)
      REFERENCES delivery_candidate_requirement(tenant_id, candidate_id, requirement_id, candidate_requirement_digest),
    FOREIGN KEY (tenant_id, acceptance_run_id, candidate_id, requirement_id,
                 candidate_requirement_digest, evidence_envelope_digest)
      REFERENCES acceptance_run(tenant_id, acceptance_run_id, candidate_id, requirement_id,
                                candidate_requirement_digest, receipt_digest),
    FOREIGN KEY (tenant_id, continuity_attestation_id, candidate_id, requirement_id,
                 candidate_requirement_digest, evidence_envelope_digest)
      REFERENCES acceptance_continuity_attestation(
        tenant_id, attestation_id, target_candidate_id, requirement_id,
        target_candidate_requirement_digest, envelope_digest),
    CHECK (
      (evidence_kind='ACCEPTANCE_RUN' AND acceptance_run_id IS NOT NULL AND continuity_attestation_id IS NULL)
      OR
      (evidence_kind='CONTINUITY_ATTESTATION' AND acceptance_run_id IS NULL AND continuity_attestation_id IS NOT NULL)
    )
);

CREATE TABLE artifact_promotion (
    tenant_id uuid NOT NULL,
    promotion_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    evidence_set_id uuid NOT NULL,
    acceptance_complete_set_digest char(71) NOT NULL CHECK (acceptance_complete_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    artifact_policy varchar(32) NOT NULL,
    source_digest char(71) NOT NULL CHECK (source_digest ~ '^sha256:[0-9a-f]{64}$'),
    target_reference_digest char(71) NOT NULL CHECK (target_reference_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_digest char(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    phase varchar(24) NOT NULL,
    consistency_state varchar(24) NOT NULL,
    promoted_digest char(71) CHECK (promoted_digest ~ '^sha256:[0-9a-f]{64}$'),
    receipt_digest char(71) CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    reconciled_event_id uuid,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL,
    reconciled_at timestamptz,
    PRIMARY KEY (tenant_id, promotion_id),
    UNIQUE (tenant_id, candidate_id),
    UNIQUE (tenant_id, reconciled_event_id),
    UNIQUE (tenant_id, promotion_id, candidate_id, evidence_set_id,
            acceptance_complete_set_digest),
    FOREIGN KEY (tenant_id, evidence_set_id, candidate_id, acceptance_complete_set_digest)
      REFERENCES candidate_acceptance_evidence_set(
        tenant_id, evidence_set_id, candidate_id, acceptance_complete_set_digest),
    FOREIGN KEY (tenant_id, candidate_id, source_digest)
      REFERENCES delivery_candidate(tenant_id, candidate_id, artifact_digest),
    FOREIGN KEY (tenant_id, reconciled_event_id)
      REFERENCES domain_event(tenant_id, event_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT artifact_promotion_profile_check
      CHECK (artifact_policy = 'IMMUTABLE_ARTIFACT'),
    CONSTRAINT artifact_promotion_state_shape_check CHECK (
      (phase IN ('REQUESTED','IN_PROGRESS') AND consistency_state='PENDING'
        AND promoted_digest IS NULL AND receipt_digest IS NULL
        AND reconciled_event_id IS NULL AND reconciled_at IS NULL)
      OR
      (phase='SUSPENDED' AND consistency_state='RECONCILING'
        AND promoted_digest IS NULL AND receipt_digest IS NULL
        AND reconciled_event_id IS NULL AND reconciled_at IS NULL)
      OR
      (phase='FAILED' AND consistency_state='DIVERGED'
        AND promoted_digest IS NULL AND receipt_digest IS NULL
        AND reconciled_event_id IS NULL AND reconciled_at IS NULL)
      OR
      (phase='SUCCEEDED' AND consistency_state='CONVERGED'
        AND promoted_digest=source_digest AND receipt_digest IS NOT NULL
        AND reconciled_event_id IS NOT NULL AND reconciled_at IS NOT NULL)
    )
);

CREATE TABLE artifact_promotion_attempt (
    tenant_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    promotion_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    evidence_set_id uuid NOT NULL,
    acceptance_complete_set_digest char(71) NOT NULL CHECK (acceptance_complete_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    attempt_no integer NOT NULL CHECK (attempt_no >= 1),
    request_digest char(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    provider_request_id varchar(255),
    outcome varchar(32) NOT NULL CHECK (
      outcome IN ('IN_FLIGHT','SUCCEEDED','PROVEN_NO_EFFECT','OUTCOME_UNKNOWN','DIGEST_MISMATCH','FAILED')),
    observation_digest char(71) CHECK (observation_digest ~ '^sha256:[0-9a-f]{64}$'),
    attempted_at timestamptz NOT NULL,
    completed_at timestamptz,
    PRIMARY KEY (tenant_id, attempt_id),
    UNIQUE (tenant_id, promotion_id, attempt_no),
    FOREIGN KEY (tenant_id, evidence_set_id, candidate_id,
                 acceptance_complete_set_digest)
      REFERENCES candidate_acceptance_evidence_set(
        tenant_id, evidence_set_id, candidate_id,
        acceptance_complete_set_digest),
    FOREIGN KEY (tenant_id, promotion_id, candidate_id, evidence_set_id,
                 acceptance_complete_set_digest)
      REFERENCES artifact_promotion(
        tenant_id, promotion_id, candidate_id, evidence_set_id,
        acceptance_complete_set_digest),
    CONSTRAINT artifact_promotion_attempt_shape_check CHECK (
      (outcome='IN_FLIGHT' AND completed_at IS NULL AND observation_digest IS NULL)
      OR
      (outcome='OUTCOME_UNKNOWN' AND provider_request_id IS NOT NULL AND completed_at IS NOT NULL)
      OR
      (outcome IN ('SUCCEEDED','PROVEN_NO_EFFECT','DIGEST_MISMATCH','FAILED')
        AND provider_request_id IS NOT NULL AND observation_digest IS NOT NULL
        AND completed_at IS NOT NULL)
    )
);

CREATE TABLE source_tree_assurance_receipt (
    tenant_id uuid NOT NULL,
    source_tree_assurance_receipt_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    candidate_payload_digest char(71) NOT NULL CHECK (candidate_payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    evidence_set_id uuid NOT NULL,
    acceptance_complete_set_digest char(71) NOT NULL CHECK (acceptance_complete_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    artifact_policy varchar(32) NOT NULL,
    repository_tree_sha varchar(64) NOT NULL CHECK (repository_tree_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    receipt_digest char(71) NOT NULL CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    signature_digest char(71) NOT NULL CHECK (signature_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_event_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, source_tree_assurance_receipt_id),
    UNIQUE (tenant_id, candidate_id),
    UNIQUE (tenant_id, recorded_event_id),
    UNIQUE (tenant_id, source_tree_assurance_receipt_id, candidate_id,
            evidence_set_id, acceptance_complete_set_digest),
    FOREIGN KEY (tenant_id, candidate_id, candidate_payload_digest)
      REFERENCES delivery_candidate(tenant_id, candidate_id, candidate_payload_digest),
    FOREIGN KEY (tenant_id, evidence_set_id, candidate_id, acceptance_complete_set_digest)
      REFERENCES candidate_acceptance_evidence_set(
        tenant_id, evidence_set_id, candidate_id, acceptance_complete_set_digest),
    FOREIGN KEY (tenant_id, recorded_event_id)
      REFERENCES domain_event(tenant_id, event_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT source_tree_assurance_profile_check
      CHECK (artifact_policy = 'SOURCE_TREE_ONLY')
);

CREATE FUNCTION assert_complete_acceptance_evidence_set()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_tenant_id uuid;
    v_evidence_set_id uuid;
    v_candidate_id uuid;
    v_declared_count integer;
    v_required_count integer;
    v_evidence_count integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_tenant_id := OLD.tenant_id;
        v_evidence_set_id := OLD.evidence_set_id;
    ELSE
        v_tenant_id := NEW.tenant_id;
        v_evidence_set_id := NEW.evidence_set_id;
    END IF;

    SELECT evidence_set.candidate_id, evidence_set.requirement_count
      INTO v_candidate_id, v_declared_count
      FROM public.candidate_acceptance_evidence_set AS evidence_set
     WHERE evidence_set.tenant_id = v_tenant_id
       AND evidence_set.evidence_set_id = v_evidence_set_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    PERFORM 1
      FROM public.delivery_candidate_requirement AS requirement
     WHERE requirement.tenant_id = v_tenant_id
       AND requirement.candidate_id = v_candidate_id
     ORDER BY requirement.requirement_id
     FOR KEY SHARE;

    SELECT count(*)::integer
      INTO v_required_count
      FROM public.delivery_candidate_requirement AS requirement
     WHERE requirement.tenant_id = v_tenant_id
       AND requirement.candidate_id = v_candidate_id;

    SELECT count(*)::integer
      INTO v_evidence_count
      FROM public.candidate_acceptance_evidence AS evidence
     WHERE evidence.tenant_id = v_tenant_id
       AND evidence.evidence_set_id = v_evidence_set_id
       AND evidence.candidate_id = v_candidate_id;

    IF v_declared_count <> v_required_count OR v_evidence_count <> v_required_count THEN
        RAISE EXCEPTION USING
          ERRCODE = '23514',
          CONSTRAINT = 'candidate_acceptance_evidence_set_incomplete',
          MESSAGE = 'sealed acceptance evidence must cover every Candidate Requirement exactly once';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM public.candidate_acceptance_evidence AS evidence
          LEFT JOIN public.acceptance_run AS run
            ON evidence.evidence_kind = 'ACCEPTANCE_RUN'
           AND run.tenant_id = evidence.tenant_id
           AND run.acceptance_run_id = evidence.acceptance_run_id
          LEFT JOIN LATERAL (
              SELECT validity.validity
                FROM public.acceptance_run_validity AS validity
               WHERE validity.tenant_id = run.tenant_id
                 AND validity.acceptance_run_id = run.acceptance_run_id
               ORDER BY validity.validity_sequence DESC
               LIMIT 1
          ) AS run_validity ON true
          LEFT JOIN public.acceptance_continuity_attestation AS attestation
            ON evidence.evidence_kind = 'CONTINUITY_ATTESTATION'
           AND attestation.tenant_id = evidence.tenant_id
           AND attestation.attestation_id = evidence.continuity_attestation_id
          LEFT JOIN LATERAL (
              SELECT validity.validity
                FROM public.acceptance_continuity_attestation_validity AS validity
               WHERE validity.tenant_id = attestation.tenant_id
                 AND validity.attestation_id = attestation.attestation_id
               ORDER BY validity.validity_sequence DESC
               LIMIT 1
          ) AS attestation_validity ON true
         WHERE evidence.tenant_id = v_tenant_id
           AND evidence.evidence_set_id = v_evidence_set_id
           AND (
             (evidence.evidence_kind = 'ACCEPTANCE_RUN' AND (
                run.phase IS DISTINCT FROM 'COMPLETED'
                OR run.result IS DISTINCT FROM 'PASSED'
                OR run_validity.validity IS DISTINCT FROM 'ACTIVE'
             ))
             OR
             (evidence.evidence_kind = 'CONTINUITY_ATTESTATION' AND (
                attestation_validity.validity IS DISTINCT FROM 'ACTIVE'
                OR attestation.valid_until <= CURRENT_TIMESTAMP
             ))
           )
    ) THEN
        RAISE EXCEPTION USING
          ERRCODE = '23514',
          CONSTRAINT = 'candidate_acceptance_evidence_ineligible',
          MESSAGE = 'sealed acceptance evidence must remain passed, active, and unexpired';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER candidate_acceptance_evidence_set_complete_ck
AFTER INSERT OR UPDATE OR DELETE ON candidate_acceptance_evidence_set
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_complete_acceptance_evidence_set();

CREATE CONSTRAINT TRIGGER candidate_acceptance_evidence_complete_ck
AFTER INSERT OR UPDATE OR DELETE ON candidate_acceptance_evidence
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_complete_acceptance_evidence_set();

CREATE FUNCTION assert_candidate_assurance_path()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_tenant_id uuid;
    v_candidate_id uuid;
    v_candidate_payload_digest char(71);
    v_repository_tree_sha varchar(64);
    v_candidate_artifact_digest char(71);
    v_candidate_artifact_policy varchar(32);
    v_candidate_phase varchar(24);
    v_candidate_validity varchar(24);
    v_evidence_set_candidate_payload_digest char(71);
    v_evidence_set_complete_digest char(71);
    v_promotion_count integer;
    v_source_tree_count integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_tenant_id := OLD.tenant_id;
        v_candidate_id := OLD.candidate_id;
    ELSE
        v_tenant_id := NEW.tenant_id;
        v_candidate_id := NEW.candidate_id;
    END IF;

    SELECT candidate.candidate_payload_digest,
           candidate.repository_tree_sha,
           candidate.artifact_digest,
           candidate.artifact_policy,
           candidate.phase,
           latest_validity.validity
      INTO v_candidate_payload_digest,
           v_repository_tree_sha,
           v_candidate_artifact_digest,
           v_candidate_artifact_policy,
           v_candidate_phase,
           v_candidate_validity
      FROM public.delivery_candidate AS candidate
      LEFT JOIN LATERAL (
          SELECT validity.validity
            FROM public.delivery_candidate_validity AS validity
           WHERE validity.tenant_id = candidate.tenant_id
             AND validity.candidate_id = candidate.candidate_id
           ORDER BY validity.validity_sequence DESC
           LIMIT 1
      ) AS latest_validity ON true
     WHERE candidate.tenant_id = v_tenant_id
       AND candidate.candidate_id = v_candidate_id
       FOR UPDATE OF candidate;

    IF v_candidate_validity IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION USING
          ERRCODE = '23514',
          CONSTRAINT = 'candidate_assurance_requires_active_candidate',
          MESSAGE = 'assurance requires the active Candidate';
    END IF;

    IF TG_OP <> 'DELETE' THEN
        SELECT evidence_set.candidate_payload_digest,
               evidence_set.acceptance_complete_set_digest
          INTO v_evidence_set_candidate_payload_digest,
               v_evidence_set_complete_digest
          FROM public.candidate_acceptance_evidence_set AS evidence_set
         WHERE evidence_set.tenant_id = v_tenant_id
           AND evidence_set.evidence_set_id = NEW.evidence_set_id
           AND evidence_set.candidate_id = v_candidate_id
           FOR KEY SHARE;

        IF NOT FOUND
           OR v_evidence_set_candidate_payload_digest IS DISTINCT FROM v_candidate_payload_digest
           OR NEW.acceptance_complete_set_digest IS DISTINCT FROM v_evidence_set_complete_digest THEN
            RAISE EXCEPTION USING
              ERRCODE = '23514',
              CONSTRAINT = 'candidate_assurance_evidence_set_mismatch',
              MESSAGE = 'assurance must bind the Candidate exact sealed evidence set';
        END IF;
    END IF;

    SELECT count(*)::integer
      INTO v_promotion_count
      FROM public.artifact_promotion AS promotion
     WHERE promotion.tenant_id = v_tenant_id
       AND promotion.candidate_id = v_candidate_id;

    SELECT count(*)::integer
      INTO v_source_tree_count
      FROM public.source_tree_assurance_receipt AS receipt
     WHERE receipt.tenant_id = v_tenant_id
       AND receipt.candidate_id = v_candidate_id;

    IF v_promotion_count + v_source_tree_count > 1 THEN
        RAISE EXCEPTION USING
          ERRCODE = '23514',
          CONSTRAINT = 'candidate_assurance_path_xor',
          MESSAGE = 'a Candidate may have exactly one assurance path';
    END IF;

    IF TG_OP <> 'DELETE' AND TG_TABLE_NAME = 'artifact_promotion' THEN
        IF v_candidate_artifact_policy IS DISTINCT FROM 'IMMUTABLE_ARTIFACT'
           OR NEW.artifact_policy IS DISTINCT FROM v_candidate_artifact_policy
           OR v_candidate_artifact_digest IS NULL
           OR NEW.source_digest IS DISTINCT FROM v_candidate_artifact_digest
           OR (TG_OP = 'INSERT' AND v_candidate_phase IS DISTINCT FROM 'RECONCILED')
           OR (TG_OP = 'UPDATE' AND v_candidate_phase NOT IN ('RECONCILED', 'PROMOTED')) THEN
            RAISE EXCEPTION USING
              ERRCODE = '23514',
              CONSTRAINT = 'artifact_promotion_candidate_profile_mismatch',
              MESSAGE = 'promotion must match the reconciled immutable-artifact Candidate';
        END IF;
    ELSIF TG_OP <> 'DELETE' AND TG_TABLE_NAME = 'source_tree_assurance_receipt' THEN
        IF v_candidate_artifact_policy IS DISTINCT FROM 'SOURCE_TREE_ONLY'
           OR NEW.artifact_policy IS DISTINCT FROM v_candidate_artifact_policy
           OR v_candidate_artifact_digest IS NOT NULL
           OR NEW.candidate_payload_digest IS DISTINCT FROM v_candidate_payload_digest
           OR NEW.repository_tree_sha IS DISTINCT FROM v_repository_tree_sha
           OR v_candidate_phase IS DISTINCT FROM 'RECONCILED' THEN
            RAISE EXCEPTION USING
              ERRCODE = '23514',
              CONSTRAINT = 'source_tree_assurance_candidate_profile_mismatch',
              MESSAGE = 'source-tree assurance must match the reconciled source-tree-only Candidate';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER artifact_promotion_assurance_path_ck
AFTER INSERT OR UPDATE OR DELETE ON artifact_promotion
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_candidate_assurance_path();

CREATE CONSTRAINT TRIGGER source_tree_assurance_path_ck
AFTER INSERT OR UPDATE OR DELETE ON source_tree_assurance_receipt
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_candidate_assurance_path();

REVOKE ALL ON FUNCTION public.assert_complete_acceptance_evidence_set()
  FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION public.assert_candidate_assurance_path()
  FROM PUBLIC, accord_api, accord_worker;
```

V050 already supplies the supporting unique keys `(tenant_id, candidate_id, requirement_id, candidate_requirement_digest)` on `delivery_candidate_requirement` and `(tenant_id, candidate_id, artifact_digest)` on `delivery_candidate`. `assert_complete_acceptance_evidence_set()` is a deferred constraint trigger on both set tables. At commit it locks Candidate Requirements, requires set `requirement_count` and join count to equal the exact Candidate Requirement count, rejects any missing/extra Requirement, and rechecks the latest validity/expiry plus Run phase/result. The service is the RFC 8785 semantic authority; the database trigger is the structural/eligibility backstop.

None of `artifact_promotion`, `artifact_promotion_attempt`, or `source_tree_assurance_receipt` has an `acceptance_run_id` column. The complete-set FKs and the exact source-artifact FK shown above are mandatory, as are the two composite reference keys consumed by V053. `assert_candidate_assurance_path()` is a deferred constraint trigger on promotion and source-tree receipt writes. It locks the Candidate and sealed set, requires `ACTIVE + RECONCILED` when either assurance path is created, compares the Candidate payload and policy-specific tree/artifact facts with the set's complete digest, and raises named `23514` violations if both assurance tables contain a row for one Candidate. A permitted later promotion-state update may observe the Candidate in `PROMOTED`, but it cannot change any binding column. This trigger is the cross-table XOR backstop; the per-table profile and state-shape checks are not a substitute for it. Both trigger functions remain migrator-owned `SECURITY INVOKER` functions with fixed `search_path`; revoke direct execution from `PUBLIC`, `accord_api`, and `accord_worker` after creating the four triggers. Promotion binding columns become immutable after insert and only the named monotonic phase/version transitions may update the state/result columns. Promotion attempts and source-tree receipts reject application `UPDATE`/`DELETE`; successful promotion and source-tree receipt rows retain their complete-set digest permanently.

After all constraints, deferred triggers, indexes, and append-only protections exist and before runtime DML grants, execute:

```sql
SELECT accord_security.enforce_tenant_table('public.candidate_acceptance_evidence_set'::regclass);
SELECT accord_security.enforce_tenant_table('public.candidate_acceptance_evidence'::regclass);
SELECT accord_security.enforce_tenant_table('public.artifact_promotion'::regclass);
SELECT accord_security.enforce_tenant_table('public.artifact_promotion_attempt'::regclass);
SELECT accord_security.enforce_tenant_table('public.source_tree_assurance_receipt'::regclass);
```

Create `ArtifactPromotionMigrationIT.java` with PostgreSQL 17.5. Its setup calls `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)` immediately after `postgres.start()` and before `Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).target("052").load().migrate()`, with no role creation or grant repair after Flyway. Seed colliding IDs for tenants A and B through the migrator; prove `accord_api` under tenant A cannot select or mutate tenant-B rows in any of the five tables and missing tenant context fails closed. Query catalogs to prove all three assurance tables have no `acceptance_run_id`, all digest-bearing composite FKs and both V053 reference keys have the exact column order above, and all five tables have exact forced RLS. Query `pg_trigger`, `pg_proc`, and `aclexplode(proacl)` to require exactly the four non-internal constraint triggers named above on their exact tables and functions, with `tgconstraint <> 0`, `tgdeferrable = true`, and `tginitdeferred = true`; require exactly the two migrator-owned functions above with return type `trigger`, `prosecdef = false`, fixed `search_path`, and no direct `EXECUTE` privilege for `PUBLIC`, `accord_api`, or `accord_worker`.

Exercise these negative/race cases: wrong Candidate/payload/Requirement/evidence-envelope/complete-set/artifact digest; direct Run not passed or not active; expired/invalid continuity; missing/duplicate/extra evidence; cross-tenant parent; an immutable-artifact Candidate receiving a source-tree receipt; a source-tree-only Candidate receiving a promotion; both assurance paths for one Candidate; illegal promotion-state shape; mutation/deletion of either sealed table, any attempt, or a source-tree receipt; 64 concurrent seal attempts choosing different otherwise-valid evidence; and evidence invalidation racing seal/promotion. For the cross-table XOR race, synchronize two `READ COMMITTED` transactions after one inserts `artifact_promotion` and the other inserts `source_tree_assurance_receipt` for the same Candidate, then commit concurrently: exactly one commit succeeds, the loser fails SQLSTATE `23514` with `candidate_assurance_path_xor`, and one assurance row remains. Exactly one seal may win, a validity change that commits first prevents sealing/promotion, and a promotion transaction that commits first retains immutable historical proof while the later invalidation triggers Candidate remediation rather than rewriting it.

- [ ] **Step 5: Define promote-by-digest only**

```java
interface ArtifactRegistryPort {
    ArtifactFact inspect(ImmutableArtifactRef ref);
    ExternalOperationResult promote(PromoteExistingDigest command);
}

record PromoteExistingDigest(
    UUID candidateId,
    Digest candidatePayloadDigest,
    UUID evidenceSetId,
    Digest acceptanceCompleteSetDigest,
    ImmutableArtifactRef source,
    Digest expectedArtifactDigest,
    String targetChannel,
    UUID idempotencyKey
) {}
```

Under one serializable preflight transaction, lock Candidate, latest validity, complete set and all evidence validity rows, merge/Context reconciliation, holds, and target profile. Recompute Candidate and complete-set JCS digests, call `AcceptanceBindingValidator`, and reject any mismatch before persisting an intent. Validate immutable locator, customer-CI provenance, Candidate `RECONCILED`, actual default tree matching Candidate tree, exact target environment/profile, and absence of holds. Persist intent/request digest/provider ID before invocation. A schema-valid command with another Candidate digest, set ID/digest, or source artifact digest must fail in the semantic service and also be impossible to persist through the composite FKs.

- [ ] **Step 6: Reconcile uncertain promotions without rebuilding**

If the registry times out after mutation, query the target channel/tag/reference and compare digest. Retry only when the registry proves no mutation occurred and the same idempotency key is supported. Otherwise remain `SUSPENDED + RECONCILING` with one ActionRequest.

- [ ] **Step 7: Implement source-tree-only assurance honestly**

For `source_tree_only`, require Candidate `RECONCILED`, the same sealed evidence-set ID/digest, and an explicit signed profile receipt; skip promotion and display no artifact identity guarantee. It cannot be relabeled as immutable-artifact GA. Emit `source_tree_assurance.recorded.v1` or `artifact_promotion.reconciled.v1` only in the transaction that stores the exact digest-locked receipt.

- [ ] **Step 8: Run adapter, migration, semantic, and fault-injection tests**

Run:

```bash
./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*AcceptanceEvidenceSet*' --tests '*ArtifactPromotion*'
./gradlew :tests:fault-injection:test --tests '*ArtifactPromotionRecoveryTest'
./gradlew :tests:integration:test --tests '*ArtifactPromotionMigrationIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; every Candidate Requirement contributes exactly one active evidence join; direct and continuity evidence can coexist in one canonical set; the set/artifact digests are preserved through promotion or source-tree receipt; uncertain outcomes never trigger a build; seal/invalidation/promotion races fail closed; tenant-A/B collisions and cross-tenant composite references are rejected; all five V052 tables satisfy the global exact forced-RLS contract; and every fixture bootstraps roles before Flyway.

- [ ] **Step 9: Commit complete-set promotion controls**

```bash
git add database/control-plane/migrations/V052__artifact_promotion.sql \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/infrastructure/JooqAcceptanceRepository.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceEvidenceSetService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/ArtifactPromotionService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/ArtifactRegistryPort.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/ArtifactPromotionWorkflow.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceEvidenceSetTest.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/AcceptanceEvidenceSetRaceTest.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/ArtifactPromotionTest.java \
  tests/fault-injection/src/test/java/com/inforvans/accord/fault/ArtifactPromotionRecoveryTest.java \
  tests/integration/src/test/java/com/inforvans/accord/integration/ArtifactPromotionMigrationIT.java \
  docs/runbooks/artifact-promotion-uncertain.md
git commit -m "feat(acceptance): promote accepted artifact by digest"
```

### Task 8: Encode The Atomic Batch Completion Invariant

**Files:**
- Create: `database/control-plane/migrations/V053__delivery_completion_evaluation.sql`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/CompletionInvariant.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/BatchCompletionService.java`
- Modify: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/infrastructure/JooqAcceptanceRepository.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CompletionEventTypes.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CompletionFactsLoader.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CompletionEvaluationTriggerHandler.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CompletionEvaluationWorker.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/worker/AcceptanceCompletionWorkerConfiguration.java`
- Modify: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/CandidateService.java`
- Modify: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceService.java`
- Modify: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceContinuityService.java`
- Modify: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceEvidenceSetService.java`
- Modify: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/ArtifactPromotionService.java`
- Modify: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/application/BatchService.java`
- Modify: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/application/CommitmentService.java`
- Modify: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/application/WorkItemGateService.java`
- Modify: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/application/CompletionService.java`
- Modify: `apps/control-plane/modules/project-context/src/main/java/com/inforvans/accord/context/application/ContextActivationService.java`
- Modify: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/application/ActionRequestService.java`
- Modify: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/ProviderFactService.java`
- Modify: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/ReconciliationService.java`
- Modify: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/DeliveryLineageService.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CompletionInvariantPropertyTest.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CompletionEventTriggerTest.java`
- Test: `apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CompletionEvaluationWorkerTest.java`
- Test: `tests/state-machine/src/test/java/com/inforvans/accord/state/CompletionStateProperties.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/DeliveryCompletionMigrationIT.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/CompletionEvaluationRecoveryTest.java`
- Verify: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/TenantRlsTest.java`
- Verify: `tests/architecture/verify-control-plane-fixtures.ps1`

- [ ] **Step 1: Add predicate, event-coverage, duplicate, out-of-order, and exactly-once tests**

```java
@Property
void completionFailsWhenAnyInvariantPredicateIsFalse(
    @ForAll("nearlyCompleteBatchFacts") CompletionFacts facts
) {
    var decision = CompletionInvariant.evaluate(facts);
    assertThat(decision.canComplete()).isFalse();
    assertThat(decision.failedPredicates()).contains(facts.removedPredicate());
}

@Test
void unavailableAuthoritativeFactsFailThePositiveAvailabilityPredicate() {
    var decision = CompletionInvariant.evaluate(
        completeFacts.withAuthoritativeFactsAvailable(false));
    assertThat(decision.canComplete()).isFalse();
    assertThat(decision.failedPredicates()).contains(COMPLETION_FACTS_AVAILABLE);
    assertThat(decision.failedPredicates())
        .extracting(Enum::name)
        .doesNotContain("COMPLETION_FACTS_UNAVAILABLE");
}

@Test
void administratorCannotDirectlySetCompleted() {
    assertThat(api.operationsFor("delivery-batch"))
        .extracting(operation -> operation.operationId())
        .doesNotContain("setBatchCompleted");
}

@Test
void everyCompletionAffectingEventHasADurableReevaluationRoute() {
    assertThat(CompletionEventTypes.triggerSources()).containsExactlyInAnyOrderElementsOf(Set.of(
        CANDIDATE_PHASE_CHANGED, CANDIDATE_VALIDITY_CHANGED,
        ACCEPTANCE_RUN_FINALIZED, ACCEPTANCE_RUN_VALIDITY_CHANGED,
        ACCEPTANCE_CONTINUITY_RECORDED, ACCEPTANCE_CONTINUITY_INVALIDATED,
        ACCEPTANCE_EVIDENCE_SET_SEALED, ARTIFACT_PROMOTION_RECONCILED,
        SOURCE_TREE_ASSURANCE_RECORDED, DELIVERY_BATCH_PHASE_CHANGED,
        DELIVERY_COMMITMENT_CHANGED, WORK_ITEM_SNAPSHOT_CHANGED,
        WORK_ITEM_COMPLETION_CHANGED, CONTEXT_MATERIALIZATION_CHANGED,
        CONTEXT_WATERMARK_HEALTH_CHANGED, BLOCKING_HOLD_CHANGED,
        BLOCKING_ACTION_REQUEST_CHANGED, PROVIDER_DEFAULT_FACT_RECORDED,
        CANDIDATE_DEFAULT_MERGE_RECONCILED, MERGE_RECONCILIATION_CHANGED,
        BRANCH_POLICY_ATTESTATION_CHANGED));
}

@Test
void duplicateAndOutOfOrderWakeupsReloadCurrentFactsAndCompleteOnce() {
    publish(sourceEvent(12), 50);
    publish(sourceEvent(9), 50);
    workers.drain();
    assertThat(repository.completionReceipts(batch.id())).hasSize(1);
    assertThat(outbox.events(DELIVERY_BATCH_COMPLETED, batch.id())).hasSize(1);
    assertThat(repository.batch(batch.id()).phase()).isEqualTo(COMPLETED);
    assertThat(repository.latestEvaluation(batch.id()).factsVersion())
        .isEqualTo(currentFacts.version());
}

@Test
void providerUnavailableRetryUsesANewRequestAndLaterCompletesOnce() {
    provider.enqueueUnavailable();
    provider.enqueue(availableCompletionFacts);
    var originalEvent = sourceEvent(12);
    publish(originalEvent);

    workers.drainOneCycle();
    clock.advanceBy(completionRetryBackoff(1));
    workers.drain();

    var evaluations = repository.evaluationsForSource(originalEvent.id()).stream()
        .sorted(Comparator.comparingInt(evaluation -> evaluation.attemptNo()))
        .toList();
    assertThat(evaluations).extracting(evaluation -> evaluation.attemptNo()).containsExactly(1, 2);
    assertThat(evaluations).extracting(evaluation -> evaluation.requestEventId()).doesNotHaveDuplicates();
    assertThat(evaluations).extracting(evaluation -> evaluation.sourceEventId())
        .containsOnly(originalEvent.id());
    assertThat(evaluations.get(1).previousRequestEventId())
        .isEqualTo(evaluations.get(0).requestEventId());
    assertThat(evaluations.get(0).failedPredicates()).contains(COMPLETION_FACTS_AVAILABLE);
    assertThat(outbox.events(COMPLETION_EVALUATED, batch.id())).hasSize(2);
    assertThat(outbox.events(DELIVERY_BATCH_COMPLETED, batch.id())).hasSize(1);
    assertThat(repository.completionReceipts(batch.id())).hasSize(1);
}
```

- [ ] **Step 2: Run and verify invariant is absent**

Run: `./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*CompletionInvariantPropertyTest' --tests '*CompletionEventTriggerTest' --tests '*CompletionEvaluationWorkerTest'`

Expected: compilation fails for `CompletionInvariant`, `CompletionEventTypes`, and `CompletionEvaluationWorker`.

- [ ] **Step 3: Implement every completion predicate**

```java
record CompletionDecision(
    boolean canComplete,
    Set<CompletionPredicate> failedPredicates,
    UUID acceptanceEvidenceSetId,
    Digest acceptanceCompleteSetDigest,
    Digest evidenceDigest
) {
    CompletionDecision {
        failedPredicates = Set.copyOf(failedPredicates);
    }
}

enum CompletionPredicate {
    COMPLETION_FACTS_AVAILABLE, BATCH_RECONCILING, ACTIVE_ACCEPTED_CANDIDATE, DEFAULT_TREE_MATCHES,
    ACCEPTANCE_COMPLETE_SET_VALID, ARTIFACT_POLICY_SATISFIED, ACTIVE_COMMITMENTS_COMPLETE,
    CANCELLED_COMMITMENTS_CLEAN, CONTEXT_WATERMARK_CONTINUOUS,
    NO_BLOCKING_HOLD_OR_ACTION, CONSISTENCY_CONVERGED,
    ASSURANCE_MATCHES_ACTUAL_MODE, STRICT_AUTHORIZATION_CONSUMED_ONCE,
    STRICT_POLICY_ATTESTED_AT_MERGE
}
```

For each active Commitment, require all required WorkItem completions and locate its Requirement in the one sealed evidence set for the current Candidate. Revalidate that join as either a current `COMPLETED + PASSED + ACTIVE` Run on the same Candidate Requirement or a current active/unexpired continuity attestation targeting it. The set must cover every Candidate Requirement exactly once, have no non-Candidate entry, still recompute to `acceptance_complete_set_digest`, and equal the digest locked into the promotion or source-tree receipt. For each cancelled Commitment, require bilateral cancellation plus cleanup/final-tree-absence or CI no-code proof. `COMPLETION_FACTS_AVAILABLE` is the positive persisted predicate; when it fails, the API projection maps it to blocker code `COMPLETION_FACTS_UNAVAILABLE`, which is not itself a predicate value. Completion never accepts a caller-supplied Run ID or independently chooses a favorable subset.

The two strict predicates are mode-gated contract values, not M4 dependencies on future runtime objects. In standard mode they evaluate as satisfied/not-applicable only after the actual standard assurance path and mode digest are verified. In strict mode they require the exact Task 9-11 authorization-consumption and merge-time policy evidence; before that adapter exists or while either fact is unavailable, `COMPLETION_FACTS_AVAILABLE` and the corresponding strict predicate fail closed, leaving the batch reconciling. V053 pre-registers the closed predicate/event names so the later strict tasks add facts and adapters, not a retroactive Candidate schema rewrite.

- [ ] **Step 4: Persist append-only evaluations and one completion receipt**

V053 creates exactly two `public` tenant tables, `delivery_completion_evaluation` and `delivery_completion_receipt`:

```sql
CREATE FUNCTION accord_valid_completion_source_event(source_event_type varchar(96))
RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
  SELECT source_event_type = ANY (ARRAY[
    'candidate.phase_changed.v1',
    'candidate.validity_changed.v1',
    'acceptance_run.finalized.v1',
    'acceptance_run.validity_changed.v1',
    'acceptance_continuity_attestation.recorded.v1',
    'acceptance_continuity_attestation.invalidated.v1',
    'candidate.acceptance_evidence_set.sealed.v1',
    'artifact_promotion.reconciled.v1',
    'source_tree_assurance.recorded.v1',
    'delivery_batch.phase_changed.v1',
    'delivery_commitment.changed.v1',
    'work_item.snapshot_changed.v1',
    'work_item_completion.changed.v1',
    'context_materialization.changed.v1',
    'context_watermark_health.changed.v1',
    'blocking_hold.changed.v1',
    'blocking_action_request.changed.v1',
    'provider_default_fact.recorded.v1',
    'candidate_default_merge.reconciled.v1',
    'merge_reconciliation.changed.v1',
    'branch_policy_attestation.changed.v1'
  ]::varchar(96)[]);
$$;

CREATE FUNCTION accord_valid_completion_predicates(predicates varchar(64)[])
RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
  SELECT array_position(predicates, NULL) IS NULL
    AND predicates <@ ARRAY[
      'COMPLETION_FACTS_AVAILABLE',
      'BATCH_RECONCILING',
      'ACTIVE_ACCEPTED_CANDIDATE',
      'DEFAULT_TREE_MATCHES',
      'ACCEPTANCE_COMPLETE_SET_VALID',
      'ARTIFACT_POLICY_SATISFIED',
      'ACTIVE_COMMITMENTS_COMPLETE',
      'CANCELLED_COMMITMENTS_CLEAN',
      'CONTEXT_WATERMARK_CONTINUOUS',
      'NO_BLOCKING_HOLD_OR_ACTION',
      'CONSISTENCY_CONVERGED',
      'ASSURANCE_MATCHES_ACTUAL_MODE',
      'STRICT_AUTHORIZATION_CONSUMED_ONCE',
      'STRICT_POLICY_ATTESTED_AT_MERGE'
    ]::varchar(64)[]
    AND cardinality(predicates) = (
      SELECT count(DISTINCT predicate)
      FROM unnest(predicates) AS allowed(predicate)
    );
$$;

REVOKE ALL ON FUNCTION accord_valid_completion_source_event(varchar) FROM PUBLIC, accord_api, accord_worker;
REVOKE ALL ON FUNCTION accord_valid_completion_predicates(varchar[]) FROM PUBLIC, accord_api, accord_worker;
GRANT EXECUTE ON FUNCTION accord_valid_completion_source_event(varchar) TO accord_worker;
GRANT EXECUTE ON FUNCTION accord_valid_completion_predicates(varchar[]) TO accord_worker;

CREATE TABLE delivery_completion_evaluation (
    tenant_id uuid NOT NULL,
    evaluation_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    evidence_set_id uuid NOT NULL,
    acceptance_complete_set_digest char(71) NOT NULL CHECK (acceptance_complete_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_event_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    source_event_type varchar(96) NOT NULL,
    source_aggregate_version bigint NOT NULL CHECK (source_aggregate_version >= 0),
    attempt_no integer NOT NULL CHECK (attempt_no >= 1),
    previous_request_event_id uuid,
    not_before timestamptz NOT NULL,
    facts_version bigint NOT NULL CHECK (facts_version >= 1),
    facts_digest char(71) NOT NULL CHECK (facts_digest ~ '^sha256:[0-9a-f]{64}$'),
    can_complete boolean NOT NULL,
    failed_predicates varchar(64)[] NOT NULL,
    evidence_digest char(71) NOT NULL CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    evaluated_event_id uuid NOT NULL,
    evaluated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, evaluation_id),
    UNIQUE (tenant_id, request_event_id),
    UNIQUE (tenant_id, source_event_id, attempt_no),
    UNIQUE (tenant_id, evaluated_event_id),
    UNIQUE (tenant_id, evaluation_id, batch_id, candidate_id, evidence_set_id,
            acceptance_complete_set_digest, can_complete, evidence_digest),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES delivery_batch(tenant_id, batch_id),
    FOREIGN KEY (tenant_id, evidence_set_id, candidate_id, acceptance_complete_set_digest)
      REFERENCES candidate_acceptance_evidence_set(
        tenant_id, evidence_set_id, candidate_id, acceptance_complete_set_digest),
    FOREIGN KEY (tenant_id, request_event_id)
      REFERENCES domain_event(tenant_id, event_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id, source_event_id)
      REFERENCES domain_event(tenant_id, event_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id, previous_request_event_id)
      REFERENCES delivery_completion_evaluation(tenant_id, request_event_id),
    FOREIGN KEY (tenant_id, evaluated_event_id)
      REFERENCES domain_event(tenant_id, event_id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (accord_valid_completion_source_event(source_event_type)),
    CHECK (accord_valid_completion_predicates(failed_predicates)),
    CHECK (can_complete = (cardinality(failed_predicates) = 0)),
    CONSTRAINT delivery_completion_evaluation_retry_shape_check CHECK (
      (attempt_no=1 AND previous_request_event_id IS NULL)
      OR (attempt_no>1 AND previous_request_event_id IS NOT NULL)
    ),
    CONSTRAINT delivery_completion_evaluation_schedule_check
      CHECK (not_before <= evaluated_at),
    CONSTRAINT delivery_completion_evaluation_event_identity_check CHECK (
      request_event_id <> source_event_id
      AND evaluated_event_id <> request_event_id
      AND evaluated_event_id <> source_event_id
      AND (previous_request_event_id IS NULL
        OR previous_request_event_id <> request_event_id)
    )
);

CREATE TABLE delivery_completion_receipt (
    tenant_id uuid NOT NULL,
    completion_receipt_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    evaluation_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    evidence_set_id uuid NOT NULL,
    acceptance_complete_set_digest char(71) NOT NULL,
    artifact_promotion_id uuid,
    source_tree_assurance_receipt_id uuid,
    actual_default_provider_fact_id uuid NOT NULL,
    actual_default_tree_sha varchar(64) NOT NULL,
    context_merge_receipt_id uuid NOT NULL,
    can_complete boolean NOT NULL DEFAULT true CHECK (can_complete),
    completion_evidence_digest char(71) NOT NULL CHECK (completion_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    signature_digest char(71) NOT NULL CHECK (signature_digest ~ '^sha256:[0-9a-f]{64}$'),
    completed_event_id uuid NOT NULL,
    completed_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, completion_receipt_id),
    UNIQUE (tenant_id, batch_id),
    UNIQUE (tenant_id, completed_event_id),
    FOREIGN KEY (tenant_id, evaluation_id, batch_id, candidate_id, evidence_set_id,
                 acceptance_complete_set_digest, can_complete, completion_evidence_digest)
      REFERENCES delivery_completion_evaluation(
        tenant_id, evaluation_id, batch_id, candidate_id, evidence_set_id,
        acceptance_complete_set_digest, can_complete, evidence_digest),
    FOREIGN KEY (tenant_id, artifact_promotion_id, candidate_id, evidence_set_id,
                 acceptance_complete_set_digest)
      REFERENCES artifact_promotion(
        tenant_id, promotion_id, candidate_id, evidence_set_id,
        acceptance_complete_set_digest),
    FOREIGN KEY (tenant_id, source_tree_assurance_receipt_id, candidate_id, evidence_set_id,
                 acceptance_complete_set_digest)
      REFERENCES source_tree_assurance_receipt(
        tenant_id, source_tree_assurance_receipt_id, candidate_id, evidence_set_id,
        acceptance_complete_set_digest),
    FOREIGN KEY (tenant_id, completed_event_id)
      REFERENCES domain_event(tenant_id, event_id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (can_complete = true),
    CHECK ((artifact_promotion_id IS NOT NULL) <> (source_tree_assurance_receipt_id IS NOT NULL))
);
```

`assert_completion_evaluation_event_chain()` locks the four event identities and rejects unless `request_event_id` is a `delivery.completion_evaluation_requested.v1` whose closed payload exactly matches the row, `source_event_id` is the original registered source event, `evaluated_event_id` is a `delivery.completion_evaluated.v1` for this evaluation/request/evidence digest, and each retry increments `attempt_no`, retains the original source identity, names the immediately preceding request, and has a nondecreasing `not_before`. The receipt's `can_complete` column exists only as the local FK discriminator; it is never an application input and its check permits only `true`. Add exact composite tenant FKs from its artifact branch to `artifact_promotion` and its source-tree branch to `source_tree_assurance_receipt`, each including Candidate, evidence-set ID, and complete-set digest; add FKs to the exact Provider fact and `LINEAGE_PROMOTED` ContextMergeReceipt. `assert_delivery_completion_receipt_exact_facts()` locks those rows and rejects unless Provider repository/ref/tree, Candidate tree, Context `LINEAGE_PROMOTED` Candidate/watermark/tree, assurance mode, and evaluation digests all match, using named `23514` constraints. The required V052 reference keys are declared in V052. Both V053 tables reject application `UPDATE`/`DELETE`; evaluations are an append-only history and the unique `(tenant_id, batch_id)` receipt is the database exactly-once backstop.

Install forced RLS only after every constraint/trigger exists and before runtime grants:

```sql
SELECT accord_security.enforce_tenant_table('public.delivery_completion_evaluation'::regclass);
SELECT accord_security.enforce_tenant_table('public.delivery_completion_receipt'::regclass);
```

`DeliveryCompletionMigrationIT.java` uses PostgreSQL 17.5 and the exact bootstrap-before-Flyway sequence targeting `053`. It proves both tables' forced-RLS catalog contract, tenant collision isolation, every composite FK and XOR assurance path, and both exact allowlists. Iterate all 21 `CompletionEventTypes.triggerSources`, seed a matching original `domain_event` for each, and prove each exact lowercase value can persist; then prove an unknown value, case-drifted `Candidate.phase_changed.v1`, and the would-be 22nd recursive output `delivery.completion_evaluated.v1` fail `accord_valid_completion_source_event`. A non-completing row with `failed_predicates=ARRAY['COMPLETION_FACTS_AVAILABLE']` persists, while `COMPLETION_FACTS_UNAVAILABLE`, unknown, null, or duplicate predicate values fail the predicate check. Query `pg_proc` and `aclexplode(proacl)` to require both allowlist functions to be migrator-owned, immutable, strict, parallel-safe, executable only by `accord_worker`, and neither executable nor replaceable by `PUBLIC` or `accord_api`. Reject duplicate request identities, duplicate `(source_event_id, attempt_no)`, aliasing request/source/evaluated event IDs, a retry with the wrong previous request/source/attempt/schedule, a non-request event in `request_event_id`, a non-evaluated event in `evaluated_event_id`, reuse of one evaluated event, a false evaluation receipt, wrong complete-set/artifact/default-tree/Context digest, and update/delete attempts. One hundred concurrent receipt inserts yield one row. A losing completion transaction leaves no batch phase change, evaluation/evaluated event, receipt, audit, completion event, or idempotency residue.

- [ ] **Step 5: Wire every completion-affecting fact through durable outbox/inbox handling**

The producer files listed in this task must emit their source event in the same transaction as the authoritative fact. `CompletionEventTypes` registers these exact event names:

```text
candidate.phase_changed.v1
candidate.validity_changed.v1
acceptance_run.finalized.v1
acceptance_run.validity_changed.v1
acceptance_continuity_attestation.recorded.v1
acceptance_continuity_attestation.invalidated.v1
candidate.acceptance_evidence_set.sealed.v1
artifact_promotion.reconciled.v1
source_tree_assurance.recorded.v1
delivery_batch.phase_changed.v1
delivery_commitment.changed.v1
work_item.snapshot_changed.v1
work_item_completion.changed.v1
context_materialization.changed.v1
context_watermark_health.changed.v1
blocking_hold.changed.v1
blocking_action_request.changed.v1
provider_default_fact.recorded.v1
candidate_default_merge.reconciled.v1
merge_reconciliation.changed.v1
branch_policy_attestation.changed.v1
```

This covers promotion/assurance, direct and continuity acceptance evidence, Candidate validity/phase, Context version/watermark, holds/required actions, exact default merge and reconciliation, branch assurance, Commitment/cancellation changes, and every WorkItem snapshot/completion validity change. `CompletionEvaluationTriggerHandler` consumes each original source through the Foundation shared inbox keyed by `(consumer_name, tenant_id, source_event_id)`, resolves/validates `batch_id` from authoritative scope, allocates a new request event ID, and transactionally writes attempt 1 as `delivery.completion_evaluation_requested.v1` with dedupe key `(tenant_id, source_event_id, attempt_no=1)`, `previous_request_event_id=null`, and `not_before=occurred_at`. Its payload is only a wake-up pointer. Duplicate source delivery returns the same initial request identity; an older source aggregate version still schedules/reuses its own initial wake-up and never overwrites newer state.

`AcceptanceCompletionWorkerConfiguration` is active only in `control-worker`, registers the trigger handler and evaluation worker with bounded leases, transport retries/DLQ, and a capped domain backoff schedule, and fails startup if any listed source event lacks a handler. `CompletionEvaluationWorker` consumes requests through its own inbox keyed by `(consumer_name, tenant_id, request_event_id)` and waits until `not_before`. The inbox claim/acknowledgement, evaluation row, evaluated event, and any next request share one database transaction. The worker does not trust event payload facts or sequence order: `CompletionFactsLoader` reloads all current PostgreSQL facts and, when the default fact is stale/missing, persists a fresh Provider observation through the existing intent/reconciliation path before final evaluation. Every evaluation, whether completing or non-completing, inserts exactly one `delivery.completion_evaluated.v1` outbox event and stores that event's ID in `evaluated_event_id` in the same transaction. Provider uncertainty fails the positive `COMPLETION_FACTS_AVAILABLE` predicate and, in that same transaction, emits the evaluated event plus a new `delivery.completion_evaluation_requested.v1` with a new `request_event_id`, the same original `source_event_id`, `attempt_no + 1`, `previous_request_event_id` equal to the current request, and `not_before` from the capped backoff schedule. It never replays a stored unavailable result as a retry and never guesses success.

- [ ] **Step 6: Complete under one serializable/CAS transaction**

For each request, load external facts before entering the final transaction. If an authoritative fact remains unavailable, atomically insert the non-completing evaluation, its evaluated event, and the next distinct request described in Step 5; do not change batch phase or create a completion receipt/event. Once authoritative facts are available, lock batch, current Candidate/validity, Candidate Requirements, sealed set/joins and latest evidence validity, commitments/cancellations, WorkItem snapshots/completions, promotion or source-tree receipt, Context watermark/merge receipt, holds/actions, Provider default fact, Tasks 1-8 merge intent/reconciliation, and the mode-specific delivery-assurance snapshot exposed by the versioned Git contract. The M4 loader has no query or compile-time reference to Task 9-11 persistence. For standard mode it derives the complete snapshot from Tasks 1-8; for strict mode the contract returns unavailable until the downstream strict adapter can atomically prove authorization consumption and merge-time policy attestation. Recompute the canonical complete-set and completion-facts digests. Insert the evaluation and exactly one evaluated event. Only when every predicate is true, also insert the signed completion receipt, CAS `RECONCILING -> COMPLETED`, append audit, and emit exactly one `delivery.batch_completed.v1` outbox event in that same transaction. The receipt locks the same `acceptance_complete_set_digest` used by promotion/assurance.

An inbox duplicate keyed by the same `request_event_id` returns the stored evaluation and its already-emitted evaluated/next-request identities; it does not evaluate or enqueue again. A domain retry has a new request identity and therefore reloads facts. An out-of-order event always observes current rows and cannot regress a projection. A serialization failure or concurrent fact/version change rolls back the evaluation/evaluated event/next request/receipt/phase/audit/completion event together; transport redelivery may then retry that same uncommitted request. Concurrent workers may record distinct non-completing evaluations for distinct request identities, but the source-attempt uniqueness, phase CAS, unique batch receipt, and completed-event constraints permit exactly one completion; later wake-ups observe terminal `COMPLETED`, acknowledge, and emit nothing.

- [ ] **Step 7: Run exhaustive mutation, migration, event-order, and crash tests**

Run each command independently:

```bash
./gradlew :apps:control-plane:modules:candidate-acceptance:test --tests '*Completion*'
./gradlew :tests:state-machine:test --tests '*CompletionStateProperties'
./gradlew :tests:integration:test --tests '*DeliveryCompletionMigrationIT'
./gradlew :tests:fault-injection:test --tests '*CompletionEvaluationRecoveryTest'
./gradlew :apps:control-plane:modules:candidate-acceptance:pitest
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; mutation score is at least 95%; each trigger family causes durable reevaluation; unavailable-to-available facts produce two request identities, two evaluations/evaluated events, and exactly one completion; duplicated/reordered events and crashes before/after inbox, evaluation/evaluated-event/next-request insert, CAS, and outbox commit converge; a late invalidation or WorkItem/Context/hold/merge/promotion change prevents stale completion; and 100 simultaneous attempts produce one receipt, transition, audit fact, and completion event.

- [ ] **Step 8: Commit durable completion evaluation**

```bash
git add database/control-plane/migrations/V053__delivery_completion_evaluation.sql \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/domain/CompletionInvariant.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/BatchCompletionService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/infrastructure/JooqAcceptanceRepository.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CompletionEventTypes.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CompletionFactsLoader.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CompletionEvaluationTriggerHandler.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/workflow/CompletionEvaluationWorker.java \
  apps/control-plane/worker/src/main/java/com/inforvans/accord/worker/AcceptanceCompletionWorkerConfiguration.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/CandidateService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceContinuityService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/AcceptanceEvidenceSetService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/application/ArtifactPromotionService.java \
  apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/application/BatchService.java \
  apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/application/CommitmentService.java \
  apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/application/WorkItemGateService.java \
  apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/application/CompletionService.java \
  apps/control-plane/modules/project-context/src/main/java/com/inforvans/accord/context/application/ContextActivationService.java \
  apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/application/ActionRequestService.java \
  apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/ProviderFactService.java \
  apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/ReconciliationService.java \
  apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/DeliveryLineageService.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CompletionInvariantPropertyTest.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CompletionEventTriggerTest.java \
  apps/control-plane/modules/candidate-acceptance/src/test/java/com/inforvans/accord/acceptance/CompletionEvaluationWorkerTest.java \
  tests/state-machine/src/test/java/com/inforvans/accord/state/CompletionStateProperties.java \
  tests/integration/src/test/java/com/inforvans/accord/integration/DeliveryCompletionMigrationIT.java \
  tests/fault-injection/src/test/java/com/inforvans/accord/fault/CompletionEvaluationRecoveryTest.java
git commit -m "feat(delivery): enforce atomic completion invariant"
```

### Task 9: Publish Acceptance APIs And Rebuildable Views

**Files:**
- Modify: `contracts/openapi/accord-control-api.yaml`
- Modify: `contracts/openapi/ownership-manifest.yaml`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/api/AcceptanceApiModels.java`
- Create: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/api/AcceptanceApiService.java`
- Modify: `apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/api/AcceptanceController.java`
- Create: `tests/api/src/test/java/com/inforvans/accord/api/AcceptanceApiContractTest.java`
- Create: `tests/api/src/test/java/com/inforvans/accord/api/AcceptanceControllerApiTest.java`
- Modify: `tests/security-negative/src/test/java/com/inforvans/accord/security/AcceptanceAuthorizationSecurityTest.java`
- Create: `tests/integration/src/test/java/com/inforvans/accord/integration/AcceptanceApiIntegrationIT.java`
- Test: `tests/contracts/openapi-cumulative-merge.test.mjs`
- Generate: `packages/api-client/src/generated`

- [ ] **Step 1: Add the failing cumulative OpenAPI and operation-set guard**

```java
final class AcceptanceApiContractTest {
    private final OpenApiFixture api = OpenApiFixture.load(
        "contracts/openapi/accord-control-api.yaml");

    record ExpectedOperation(
        String id,
        String method,
        String path,
        @Nullable String requestSchema,
        int responseStatus,
        String responseSchema,
        String controllerMethod,
        @Nullable String freshAuth,
        Map<String, Boolean> queryParameters
    ) {
        ExpectedOperation {
            queryParameters = Map.copyOf(queryParameters);
        }
    }

    private static ExpectedOperation read(
        String id, String path, String responseSchema, String controllerMethod
    ) {
        return new ExpectedOperation(
            id, "GET", path, null, 200, responseSchema, controllerMethod, null, Map.of());
    }

    private static ExpectedOperation page(
        String id, String path, String responseSchema, String controllerMethod,
        Map<String, Boolean> queryParameters
    ) {
        return new ExpectedOperation(
            id, "GET", path, null, 200, responseSchema, controllerMethod, null, queryParameters);
    }

    private static ExpectedOperation command(
        String id, String method, String path, String requestSchema, int responseStatus,
        String responseSchema, String controllerMethod, String freshAuth
    ) {
        return new ExpectedOperation(
            id, method, path, requestSchema, responseStatus, responseSchema,
            controllerMethod, freshAuth, Map.of());
    }

    private final List<ExpectedOperation> expected = List.of(
        page("listDeliveryCandidates", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates", "DeliveryCandidatePage", "listCandidates", Map.of("repository_id", true, "cursor", false, "limit", false)),
        read("getDeliveryCandidate", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}", "DeliveryCandidateView", "getCandidate"),
        read("getDeliveryCandidateEvidence", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/evidence", "DeliveryCandidateEvidenceView", "getCandidateEvidence"),
        command("createAcceptanceRun", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs", "CreateAcceptanceRunRequest", 201, "AcceptanceRunView", "createAcceptanceRun", "window"),
        page("listAcceptanceRuns", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs", "AcceptanceRunPage", "listAcceptanceRuns", Map.of("cursor", false, "limit", false)),
        read("getAcceptanceRun", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}", "AcceptanceRunView", "getAcceptanceRun"),
        command("upsertAcceptanceCriterionDraft", "PUT", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/criteria/{criterionId}/draft", "UpsertAcceptanceCriterionDraftRequest", 200, "AcceptanceCriterionDraftView", "upsertCriterionDraft", "window"),
        command("submitAcceptanceRun", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/submissions", "SubmitAcceptanceRunRequest", 200, "AcceptanceRunView", "submitAcceptanceRun", "window"),
        command("requestFailureDisposition", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions", "RequestFailureDispositionRequest", 201, "FailureDispositionView", "requestFailureDisposition", "single_action"),
        command("confirmFailureDisposition", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions/{dispositionId}/confirmations", "ConfirmFailureDispositionRequest", 200, "FailureDispositionView", "confirmFailureDisposition", "single_action"),
        read("getFailureDisposition", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions/{dispositionId}", "FailureDispositionView", "getFailureDisposition"),
        command("createCorrectionRun", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions/{dispositionId}/correction-runs", "CreateCorrectionRunRequest", 201, "CorrectionRunView", "createCorrectionRun", "window"),
        read("getCorrectionRun", "/v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}", "CorrectionRunView", "getCorrectionRun"),
        command("advanceCorrectionRun", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/transitions", "AdvanceCorrectionRunRequest", 200, "CorrectionRunView", "advanceCorrectionRun", "window"),
        command("linkCorrectionRunWorkItem", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/work-item-links", "LinkCorrectionRunWorkItemRequest", 201, "CorrectionRunView", "linkCorrectionRunWorkItem", "window"),
        command("requestCorrectionLimitDecision", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/limit-decisions", "RequestCorrectionLimitDecisionRequest", 201, "CorrectionLimitDecisionView", "requestCorrectionLimitDecision", "single_action"),
        read("getCorrectionLimitDecision", "/v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/limit-decisions/{decisionId}", "CorrectionLimitDecisionView", "getCorrectionLimitDecision"),
        command("confirmCorrectionLimitDecision", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/limit-decisions/{decisionId}/confirmations", "ConfirmCorrectionLimitDecisionRequest", 200, "CorrectionLimitDecisionView", "confirmCorrectionLimitDecision", "single_action"),
        command("createAcceptanceContinuityAssessment", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments", "CreateAcceptanceContinuityAssessmentRequest", 201, "AcceptanceContinuityAssessmentView", "createContinuityAssessment", "window"),
        page("listAcceptanceContinuityAssessments", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments", "AcceptanceContinuityAssessmentPage", "listContinuityAssessments", Map.of("cursor", false, "limit", false)),
        read("getAcceptanceContinuityAssessment", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments/{assessmentId}", "AcceptanceContinuityAssessmentView", "getContinuityAssessment"),
        command("submitAcceptanceContinuityAttestation", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments/{assessmentId}/attestations", "SubmitAcceptanceContinuityAttestationRequest", 201, "AcceptanceContinuityAttestationView", "submitContinuityAttestation", "single_action"),
        read("getArtifactPromotion", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/artifact-promotion", "ArtifactPromotionView", "getArtifactPromotion"),
        command("retryArtifactPromotion", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/artifact-promotion/retry-requests", "RetryArtifactPromotionRequest", 202, "ArtifactPromotionIntentView", "retryArtifactPromotion", "window"),
        command("reconcileArtifactPromotion", "POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/artifact-promotion/reconciliation-requests", "ReconcileArtifactPromotionRequest", 202, "ArtifactPromotionIntentView", "reconcileArtifactPromotion", "window"),
        read("getDeliveryCompletionEvaluation", "/v1/projects/{projectId}/delivery-batches/{batchId}/completion-evaluation", "DeliveryCompletionEvaluationView", "getCompletionEvaluation")
    );

    private final Map<String, Set<String>> requiredRequestFields = Map.ofEntries(
        Map.entry("CreateAcceptanceRunRequest", Set.of("expected_version", "candidate_id", "candidate_payload_digest", "requirement_id", "candidate_requirement_digest", "revision_hash", "acceptance_criteria_hash")),
        Map.entry("UpsertAcceptanceCriterionDraftRequest", Set.of("expected_version", "acceptance_run_id", "criterion_id", "result", "note", "evidence_attachment_version_ids")),
        Map.entry("SubmitAcceptanceRunRequest", Set.of("expected_version", "acceptance_run_id", "candidate_id", "candidate_payload_digest", "requirement_id", "candidate_requirement_digest", "revision_hash", "acceptance_criteria_hash", "criterion_decisions_digest", "acceptance_owner_binding_id", "acceptance_owner_binding_version", "acceptance_owner_binding_digest")),
        Map.entry("RequestFailureDispositionRequest", Set.of("expected_version", "acceptance_run_id", "classification", "scope_digest", "consequence_digest", "reason")),
        Map.entry("ConfirmFailureDispositionRequest", Set.of("expected_version", "disposition_id", "classification", "scope_digest")),
        Map.entry("CreateCorrectionRunRequest", Set.of("expected_version", "disposition_id", "failed_acceptance_run_id", "revision_hash", "correction_scope_digest")),
        Map.entry("AdvanceCorrectionRunRequest", Set.of("expected_version", "correction_run_id", "transition", "evidence_digests")),
        Map.entry("LinkCorrectionRunWorkItemRequest", Set.of("expected_version", "correction_run_id", "work_item_id", "requirement_revision_hash")),
        Map.entry("RequestCorrectionLimitDecisionRequest", Set.of("expected_version", "correction_run_id", "option", "scope_digest", "consequence_digest", "reason")),
        Map.entry("ConfirmCorrectionLimitDecisionRequest", Set.of("expected_version", "decision_id", "option", "scope_digest")),
        Map.entry("CreateAcceptanceContinuityAssessmentRequest", Set.of("expected_version", "source_acceptance_run_id", "target_candidate_id", "requirement_id", "target_candidate_requirement_digest", "comparison_basis_digest")),
        Map.entry("SubmitAcceptanceContinuityAttestationRequest", Set.of("expected_version", "assessment_id", "target_candidate_id", "requirement_id", "target_candidate_requirement_digest", "scope_digest", "criteria_digest")),
        Map.entry("RetryArtifactPromotionRequest", Set.of("expected_version", "candidate_id", "promotion_id", "acceptance_evidence_set_id", "acceptance_complete_set_digest", "expected_artifact_digest")),
        Map.entry("ReconcileArtifactPromotionRequest", Set.of("expected_version", "candidate_id", "promotion_id", "acceptance_evidence_set_id", "acceptance_complete_set_digest", "expected_artifact_digest", "observation_digest"))
    );

    @Test
    void candidateAcceptanceTagHasTheExactPublicOperationSet() {
        assertThat(api.operationsWithTag("candidate-acceptance"))
            .extracting(operation -> operation.operationId())
            .containsExactlyInAnyOrderElementsOf(
                expected.stream().map(ExpectedOperation::id).collect(Collectors.toSet()));
    }

    @Test
    void everyOperationHasItsExactMethodPathResponseAndControllerContract() {
        for (var spec : expected) {
            var operation = api.operation(spec.id());
            assertThat(operation.method()).isEqualTo(spec.method());
            assertThat(operation.path()).isEqualTo(spec.path());
            assertThat(operation.success(spec.responseStatus()).schemaRef())
                .isEqualTo("#/components/schemas/" + spec.responseSchema());
            assertThat(operation.success(spec.responseStatus()).header("ETag").required()).isTrue();
            assertThat(operation.extension("x-controller-method")).isEqualTo(spec.controllerMethod());
            assertThat(operation.securityAlternatives())
                .containsExactlyInAnyOrder(Set.of("oidc"), Set.of("browserSession"));
            assertThat(operation.parameters()).extracting(parameter -> parameter.name())
                .doesNotContain("tenantId");
            assertThat(operation.queryParameters().stream().collect(
                Collectors.toMap(parameter -> parameter.name(), parameter -> parameter.required())))
                .isEqualTo(spec.queryParameters());
            assertThat(operation.defaultProblemSchema()).isEqualTo("#/components/schemas/Problem");
            if (spec.requestSchema() != null) {
                assertThat(operation.header("Idempotency-Key").required()).isTrue();
                assertThat(operation.header("If-Match").required()).isTrue();
                assertThat(operation.header("X-CSRF-Token").required()).isFalse();
                assertThat(operation.extension("x-browser-csrf-required")).isEqualTo("conditional");
                assertThat(operation.requestSchema().ref())
                    .isEqualTo("#/components/schemas/" + spec.requestSchema());
                assertThat(operation.requestSchema().required())
                    .isEqualTo(requiredRequestFields.get(spec.requestSchema()));
                assertThat(operation.requestSchema().additionalProperties()).isFalse();
            }
            if (spec.freshAuth() != null) {
                assertThat(operation.header("X-Accord-Fresh-Auth").required()).isTrue();
                assertThat(operation.extension("x-fresh-auth")).isEqualTo(spec.freshAuth());
            }
        }
    }

    @Test
    void publicAcceptanceSchemasExposeMetadataAndDigestsButNoRepositoryMaterial() {
        var forbidden = Set.of(
            "tenant_id", "source", "source_body", "source_excerpt", "blob", "blob_url",
            "content", "diff", "patch", "provider_payload", "provider_response",
            "presigned_url", "artifact_locator", "commit_message");
        expected.stream().map(ExpectedOperation::responseSchema).distinct().forEach(schemaName ->
            assertThat(api.schema(schemaName).recursivePropertyNames())
                .doesNotContainAnyElementsOf(forbidden));
    }
}
```

- [ ] **Step 2: Run and verify operations are missing**

Run: `./gradlew :tests:api:test --tests '*AcceptanceApiContractTest'`

Expected: failure names `listDeliveryCandidates` as the first missing operation; the existing cumulative Foundation, Requirement, Context, and Delivery operations remain unchanged.

- [ ] **Step 3: Add the exact cumulative OpenAPI paths and controls**

Merge the following 26 operations into `contracts/openapi/accord-control-api.yaml` and add the exact `candidate-acceptance` entry to `contracts/openapi/ownership-manifest.yaml` in one AST-backed implementation step. Preserve every existing path, component, security scheme, operation ID, and owner entry. The new owner entry contains exactly the 26 unique operation IDs and their project-scoped paths plus only Candidate-owned components; shared Problem/ObjectRef/action/security/parameter components remain owned by their earlier plans. `openapi-cumulative-merge.test.mjs` must prove every manifest reference exists, the cross-owner operation union is unique, all prior owner keys remain, and the Candidate owner count is exactly 26. Sealing an acceptance-evidence set, scheduling/retrying completion evaluation, and completing a batch are internal event/worker actions and add no public operation. Every route begins with `/v1/projects/{projectId}`; tenant and actor are never path, query, header, or body inputs and come only from `VerifiedRequestIdentity`.

Each row uses tag `candidate-acceptance` and the exact `x-controller-method` from Step 1. The operation-set test rejects an alias operation, a second path to the same command, or an acceptance operation hidden under another tag.

| Operation ID | Method and path | Request -> success | Fresh auth | Authorized action and stable domain problems |
|---|---|---|---|---|
| `listDeliveryCandidates` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/candidates?repository_id&cursor&limit` | none -> `200 DeliveryCandidatePage` | none | `VIEW_CANDIDATE_ACCEPTANCE`; `INVALID_CURSOR`, `PAGE_SIZE_OUT_OF_RANGE` |
| `getDeliveryCandidate` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}` | none -> `200 DeliveryCandidateView` | none | `VIEW_CANDIDATE_ACCEPTANCE`; concealed `RESOURCE_NOT_FOUND` |
| `getDeliveryCandidateEvidence` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/evidence` | none -> `200 DeliveryCandidateEvidenceView` | none | `VIEW_CANDIDATE_EVIDENCE`; `CANDIDATE_EVIDENCE_UNAVAILABLE` |
| `createAcceptanceRun` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs` | `CreateAcceptanceRunRequest` -> `201 AcceptanceRunView` | `window` | `REQUEST_ACCEPTANCE_RUN`; `CANDIDATE_INVALID`, `ACCEPTANCE_TARGET_STALE`, `ACCESS_PREFLIGHT_REQUIRED` |
| `listAcceptanceRuns` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs?cursor&limit` | none -> `200 AcceptanceRunPage` | none | `VIEW_ACCEPTANCE_RUN`; `INVALID_CURSOR`, `PAGE_SIZE_OUT_OF_RANGE` |
| `getAcceptanceRun` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}` | none -> `200 AcceptanceRunView` | none | `VIEW_ACCEPTANCE_RUN`; concealed `RESOURCE_NOT_FOUND` |
| `upsertAcceptanceCriterionDraft` | `PUT /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/criteria/{criterionId}/draft` | `UpsertAcceptanceCriterionDraftRequest` -> `200 AcceptanceCriterionDraftView` | `window` | `EDIT_ACCEPTANCE_CRITERIA`; `CRITERION_NOT_IN_TARGET`, `ACCEPTANCE_RUN_IMMUTABLE`, `ATTACHMENT_ACCESS_REQUIRED` |
| `submitAcceptanceRun` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/submissions` | `SubmitAcceptanceRunRequest` -> `200 AcceptanceRunView` | `window` | `SUBMIT_ACCEPTANCE_RUN`; `BUSINESS_ACCEPTANCE_OWNER_REQUIRED`, `SUPPLIER_SELF_ACCEPTANCE_FORBIDDEN`, `ACCEPTANCE_TARGET_STALE`, `CRITERIA_INCOMPLETE` |
| `requestFailureDisposition` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions` | `RequestFailureDispositionRequest` -> `201 FailureDispositionView` | `single_action` | `REQUEST_FAILURE_DISPOSITION`; `ACCEPTANCE_RUN_NOT_FAILED`, `FAILURE_SCOPE_STALE` |
| `confirmFailureDisposition` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions/{dispositionId}/confirmations` | `ConfirmFailureDispositionRequest` -> `200 FailureDispositionView` | `single_action` | `CONFIRM_FAILURE_DISPOSITION`; `SAME_NATURAL_PERSON_FORBIDDEN`, `FAILURE_CLASSIFICATION_MISMATCH`, `CONFIRMATION_ALREADY_RECORDED` |
| `getFailureDisposition` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions/{dispositionId}` | none -> `200 FailureDispositionView` | none | `VIEW_FAILURE_DISPOSITION`; concealed `RESOURCE_NOT_FOUND` |
| `createCorrectionRun` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions/{dispositionId}/correction-runs` | `CreateCorrectionRunRequest` -> `201 CorrectionRunView` | `window` | `CREATE_CORRECTION_RUN`; `DISPOSITION_NOT_BILATERAL`, `CORRECTION_REQUIRES_IMPLEMENTATION_DEFECT`, `CORRECTION_LIMIT_REACHED` |
| `getCorrectionRun` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}` | none -> `200 CorrectionRunView` | none | `VIEW_CORRECTION_RUN`; concealed `RESOURCE_NOT_FOUND` |
| `advanceCorrectionRun` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/transitions` | `AdvanceCorrectionRunRequest` -> `200 CorrectionRunView` | `window` | `ADVANCE_CORRECTION_RUN`; `CORRECTION_TRANSITION_INVALID`, `CORRECTION_EVIDENCE_INCOMPLETE` |
| `linkCorrectionRunWorkItem` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/work-item-links` | `LinkCorrectionRunWorkItemRequest` -> `201 CorrectionRunView` | `window` | `LINK_CORRECTION_WORK_ITEM`; `WORK_ITEM_SCOPE_MISMATCH`, `REVISION_HASH_MISMATCH`, `WORK_ITEM_ALREADY_LINKED` |
| `requestCorrectionLimitDecision` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/limit-decisions` | `RequestCorrectionLimitDecisionRequest` -> `201 CorrectionLimitDecisionView` | `single_action` | `REQUEST_CORRECTION_LIMIT_DECISION`; `CORRECTION_LIMIT_NOT_REACHED`, `ACTIVE_LIMIT_DECISION_EXISTS`, `CORRECTION_SCOPE_STALE` |
| `getCorrectionLimitDecision` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/limit-decisions/{decisionId}` | none -> `200 CorrectionLimitDecisionView` | none | `VIEW_CORRECTION_LIMIT_DECISION`; concealed `RESOURCE_NOT_FOUND` |
| `confirmCorrectionLimitDecision` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/correction-runs/{correctionRunId}/limit-decisions/{decisionId}/confirmations` | `ConfirmCorrectionLimitDecisionRequest` -> `200 CorrectionLimitDecisionView` | `single_action` | `CONFIRM_CORRECTION_LIMIT_DECISION`; `SAME_NATURAL_PERSON_FORBIDDEN`, `LIMIT_DECISION_OPTION_MISMATCH`, `LIMIT_DECISION_SCOPE_STALE`, `CONFIRMATION_ALREADY_RECORDED` |
| `createAcceptanceContinuityAssessment` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments` | `CreateAcceptanceContinuityAssessmentRequest` -> `201 AcceptanceContinuityAssessmentView` | `window` | `ASSESS_ACCEPTANCE_CONTINUITY`; `SOURCE_ACCEPTANCE_INVALID`, `COMPARISON_BASIS_STALE` |
| `listAcceptanceContinuityAssessments` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments?cursor&limit` | none -> `200 AcceptanceContinuityAssessmentPage` | none | `VIEW_ACCEPTANCE_CONTINUITY`; `INVALID_CURSOR`, `PAGE_SIZE_OUT_OF_RANGE` |
| `getAcceptanceContinuityAssessment` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments/{assessmentId}` | none -> `200 AcceptanceContinuityAssessmentView` | none | `VIEW_ACCEPTANCE_CONTINUITY`; concealed `RESOURCE_NOT_FOUND` |
| `submitAcceptanceContinuityAttestation` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments/{assessmentId}/attestations` | `SubmitAcceptanceContinuityAttestationRequest` -> `201 AcceptanceContinuityAttestationView` | `single_action` | `ATTEST_ACCEPTANCE_CONTINUITY`; `CONTINUITY_NOT_UNAFFECTED`, `CONTINUITY_SCOPE_STALE`, `DEVELOPMENT_PRINCIPAL_REQUIRED` |
| `getArtifactPromotion` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/artifact-promotion` | none -> `200 ArtifactPromotionView` | none | `VIEW_ARTIFACT_PROMOTION`; `PROMOTION_NOT_APPLICABLE` |
| `retryArtifactPromotion` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/artifact-promotion/retry-requests` | `RetryArtifactPromotionRequest` -> `202 ArtifactPromotionIntentView` | `window` | `RETRY_ARTIFACT_PROMOTION`; `PROMOTION_OUTCOME_UNKNOWN`, `RETRY_NOT_PROVEN_SAFE`, `ARTIFACT_DIGEST_MISMATCH` |
| `reconcileArtifactPromotion` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/candidates/{candidateId}/artifact-promotion/reconciliation-requests` | `ReconcileArtifactPromotionRequest` -> `202 ArtifactPromotionIntentView` | `window` | `RECONCILE_ARTIFACT_PROMOTION`; `PROMOTION_ALREADY_RECONCILING`, `OBSERVATION_DIGEST_STALE` |
| `getDeliveryCompletionEvaluation` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/completion-evaluation` | none -> `200 DeliveryCompletionEvaluationView` | none | `VIEW_DELIVERY_COMPLETION`; `COMPLETION_FACTS_UNAVAILABLE` |

All 14 mutations reference the shared `IdempotencyKey`, `ExpectedVersion`, `FreshAuthProof`, and conditionally required `BrowserCsrfToken` parameters, require body `expected_version` to equal quoted `If-Match`, return a required `ETag`, and use the shared RFC 7807 `Problem` for `400`, `401`, `403`, `404`, `409`, and `default`. Exact common mappings are `400 REQUEST_SCHEMA_INVALID` or `ROUTE_BODY_MISMATCH`, `401 AUTHENTICATION_REQUIRED`, `403 AUTHORIZATION_DENIED`, `FRESH_AUTH_REQUIRED`, or `CSRF_VALIDATION_FAILED`, concealed `404 RESOURCE_NOT_FOUND`, and `409 VERSION_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, or the row's stable domain conflict. Every Problem contains `code`, `correlation_id`, and, for stale targets, `expected_version`, `actual_version`, current target digests, and a metadata-only comparison link. Collection cursors are server-signed, tenant/project/batch/resource scoped, bind the sort key and filters, expire after 15 minutes, and page by immutable `(created_at DESC, id DESC)` with `limit` default 50 and range 1-100; invalid, expired, cross-scope, or filter-changed cursors return `INVALID_CURSOR` without leaking existence. All 14 mutations declare the listed `x-fresh-auth` value. Window proofs are action-family, project, actor, tenant, and expiry bound; single-action proofs are consumed exactly once.

Every operation exposes the two explicit security alternatives `{ oidc: [] }` and `{ browserSession: [] }`. For an unsafe request authenticated by the `browserSession` HttpOnly cookie, the Identity-owned `x-browser-csrf-required: conditional` contract makes `Origin`, Fetch Metadata, and `X-CSRF-Token` mandatory even though OpenAPI marks the shared header itself `required: false` for bearer clients. The Identity-owned request-security filter compares the exact normalized Origin against the tenant deployment allowlist and validates the token against the same active session, actor, tenant, expiry, and rotation generation before `AcceptanceController` is entered. A missing/invalid token, disallowed Origin, invalid Fetch Metadata, old-session/old-generation token, or replay after logout returns `403 CSRF_VALIDATION_FAILED` before fresh-auth consumption, idempotency claim, audit, outbox, or domain write. OIDC bearer/service callers do not require CSRF, but cannot select the cookie alternative or inject a browser actor; an `X-CSRF-Token` never supplies tenant, actor, or authorization. GET/HEAD remain CSRF-free and side-effect-free.

`CorrectionRunView.allowed_actions` is the recovery authority when `CORRECTION_LIMIT_REACHED`: an eligible principal receives only `request_correction_limit_decision`, and after a proposal only the opposite side receives `confirm_correction_limit_decision`. The request operation records the proposer's side confirmation; the confirm operation requires the other side, the same option and scope digest, a distinct natural person, and a new single-action fresh-auth proof. The four and only four options are `continue_correction`, `new_requirement_revision`, `rollback`, and `terminate_batch`. A completed decision returns immutable `resolved_outcome_refs` for the continued CorrectionRun, new Revision workflow, rollback workflow, or batch termination workflow, so a refresh can resume from persisted state instead of a dead-end error or browser-only command.

```yaml
components:
  schemas:
    CriterionResultWire:
      type: string
      enum: [passed, failed, unverifiable]
    FailureClassWire:
      type: string
      enum: [implementation_defect, requirement_change, environment_issue]
    CorrectionTransitionWire:
      type: string
      enum: [start, ready_for_reacceptance, verify, cancel]
    ContinuityStatusWire:
      type: string
      enum: [unaffected, affected, uncertain]
    CorrectionLimitOptionWire:
      type: string
      enum: [continue_correction, new_requirement_revision, rollback, terminate_batch]
    ReacceptanceModeWire:
      type: string
      enum: [targeted, full]
```

Use explicit `@JsonProperty` mappings for every wire enum; the domain value `CriterionResult.NOT_VERIFIABLE` serializes as `unverifiable`. Phase, validity, artifact policy, promotion consistency, evidence kind, and overall-result properties are closed OpenAPI enums as well, never unconstrained strings. Do not expose repository source, blob/content, diff/patch, arbitrary Provider payload, commit message, pre-signed URL, artifact locator, or request body echo. Evidence is limited to immutable IDs, `ObjectRef` values, SHA/tree values, typed metadata, bounded business notes, and digests. Attachment download/preview remains an authorization-checked attachment API operation from the Requirement contract; acceptance projections expose metadata and attachment-version `ObjectRef` values only. `ArtifactPromotionView` must require the complete evidence-set object and must not define `acceptance_run_id`; direct Run IDs remain valid only inside per-Requirement evidence refs and AcceptanceRun resources.

- [ ] **Step 4: Define every closed request, response, and API service type**

Create `AcceptanceApiModels.java`. These are the exact OpenAPI component fields; every generated schema uses `additionalProperties: false`, UUID/date-time/digest formats, bounded strings and arrays, and the cumulative `ObjectRef`, `DisplayState`, and `AllowedAction` components rather than redefining them:

```java
enum CriterionResultWire { @JsonProperty("passed") PASSED, @JsonProperty("failed") FAILED, @JsonProperty("unverifiable") NOT_VERIFIABLE }
enum FailureClassWire { @JsonProperty("implementation_defect") IMPLEMENTATION_DEFECT, @JsonProperty("requirement_change") REQUIREMENT_CHANGE, @JsonProperty("environment_issue") ENVIRONMENT_ISSUE }
enum CorrectionTransitionWire { @JsonProperty("start") START, @JsonProperty("ready_for_reacceptance") READY_FOR_REACCEPTANCE, @JsonProperty("verify") VERIFY, @JsonProperty("cancel") CANCEL }
enum ContinuityStatusWire { @JsonProperty("unaffected") UNAFFECTED, @JsonProperty("affected") AFFECTED, @JsonProperty("uncertain") UNCERTAIN }
enum CorrectionLimitOptionWire { @JsonProperty("continue_correction") CONTINUE_CORRECTION, @JsonProperty("new_requirement_revision") NEW_REQUIREMENT_REVISION, @JsonProperty("rollback") ROLLBACK, @JsonProperty("terminate_batch") TERMINATE_BATCH }
enum ReacceptanceModeWire { @JsonProperty("targeted") TARGETED, @JsonProperty("full") FULL }

record EvidenceMetadataView(String kind, String label, @Nullable String summary, String digest, Instant observed_at, @Nullable String signer_key_id, String verification_state) {}
record AttachmentMetadataView(ObjectRef attachment_version_ref, String file_name, String media_type, long byte_size, String classification_label, String scan_state, boolean preview_available, boolean download_allowed) {}
record CandidateRequirementView(ObjectRef requirement_ref, String requirement_label, ObjectRef revision_ref, String revision_hash, String acceptance_criteria_hash, String candidate_requirement_digest, ObjectRef acceptance_owner_binding_ref, long acceptance_owner_binding_version, String acceptance_owner_binding_digest) {}
record AcceptanceHistoryView(ObjectRef acceptance_run_ref, String phase, String result, String validity, @Nullable String receipt_digest, @Nullable Instant completed_at) {}
record DeliveryCandidateSummary(UUID candidate_id, UUID repository_id, String phase, String validity, String repository_tree_sha, String artifact_policy, @Nullable String artifact_digest, String payload_digest, long version, ObjectRef object_ref, List<ObjectRef> acceptance_run_refs, List<ObjectRef> continuity_assessment_refs) {}
record DeliveryCandidatePage(List<DeliveryCandidateSummary> items, @Nullable String next_cursor, long projection_version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}
record DeliveryCandidateView(UUID candidate_id, UUID repository_id, UUID batch_id, String phase, String validity, String effective_manifest_digest, String default_base_sha, String delivery_head_sha, String candidate_commit_sha, String repository_tree_sha, String context_version, long patch_watermark, List<CandidateRequirementView> requirements, String environment_configuration_hash, String artifact_policy, @Nullable String artifact_digest, @Nullable String artifact_reference_digest, String test_attestation_digest, String build_provenance_digest, List<AcceptanceHistoryView> acceptance_history, List<ObjectRef> acceptance_run_refs, List<ObjectRef> continuity_assessment_refs, @Nullable ObjectRef acceptance_evidence_set_ref, @Nullable String acceptance_complete_set_digest, @Nullable ObjectRef artifact_promotion_ref, List<String> blocker_codes, String payload_digest, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}
record DeliveryCandidateEvidenceView(UUID candidate_id, String candidate_payload_digest, String provider_fact_digest, String context_basis_digest, String manifest_digest, String ci_attestation_digest, @Nullable String artifact_evidence_digest, List<EvidenceMetadataView> evidence, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}

record CreateAcceptanceRunRequest(long expected_version, UUID candidate_id, String candidate_payload_digest, UUID requirement_id, String candidate_requirement_digest, String revision_hash, String acceptance_criteria_hash) {}
record UpsertAcceptanceCriterionDraftRequest(long expected_version, UUID acceptance_run_id, UUID criterion_id, CriterionResultWire result, String note, List<UUID> evidence_attachment_version_ids) {}
record SubmitAcceptanceRunRequest(long expected_version, UUID acceptance_run_id, UUID candidate_id, String candidate_payload_digest, UUID requirement_id, String candidate_requirement_digest, String revision_hash, String acceptance_criteria_hash, String criterion_decisions_digest, UUID acceptance_owner_binding_id, long acceptance_owner_binding_version, String acceptance_owner_binding_digest) {}
record AcceptanceCriterionView(UUID criterion_id, ObjectRef criterion_ref, String criterion_digest, String label, String business_expectation, String expected_effect, List<String> evidence_requirements, List<EvidenceMetadataView> reference_evidence, List<AttachmentMetadataView> source_attachments, @Nullable CriterionResultWire result, @Nullable String note, List<AttachmentMetadataView> decision_evidence_attachments, @Nullable String evidence_digest, @Nullable Instant decided_at) {}
record AcceptanceCriterionDraftView(UUID acceptance_run_id, AcceptanceCriterionView criterion, String draft_digest, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}
record AcceptanceRunSummary(UUID acceptance_run_id, ObjectRef candidate_ref, String phase, String result, String validity, @Nullable Instant completed_at, @Nullable String receipt_digest, long version, ObjectRef object_ref) {}
record AcceptanceRunPage(List<AcceptanceRunSummary> items, @Nullable String next_cursor, long projection_version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}
record AcceptanceRunView(UUID acceptance_run_id, UUID candidate_id, ObjectRef candidate_ref, ObjectRef requirement_ref, String candidate_requirement_digest, ObjectRef revision_ref, String revision_hash, String acceptance_criteria_hash, String repository_tree_sha, @Nullable String artifact_digest, String environment_configuration_hash, ObjectRef acceptance_owner_binding_ref, long acceptance_owner_binding_version, String acceptance_owner_binding_digest, String phase, String result, String validity, List<AcceptanceCriterionView> criteria, @Nullable String criterion_decisions_digest, @Nullable String receipt_digest, List<ObjectRef> failure_disposition_refs, List<ObjectRef> correction_run_refs, List<ObjectRef> successor_acceptance_run_refs, List<ObjectRef> continuity_assessment_refs, List<String> blocker_codes, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}

record RequestFailureDispositionRequest(long expected_version, UUID acceptance_run_id, FailureClassWire classification, String scope_digest, String consequence_digest, String reason) {}
record ConfirmFailureDispositionRequest(long expected_version, UUID disposition_id, FailureClassWire classification, String scope_digest) {}
record FailureDispositionConfirmationView(String side, UUID actor_account_id, long actor_binding_version, String receipt_digest, Instant confirmed_at) {}
record FailureDispositionView(UUID disposition_id, ObjectRef failed_acceptance_run_ref, FailureClassWire classification, String scope_digest, String consequence_digest, String phase, List<FailureDispositionConfirmationView> confirmations, @Nullable String next_required_side, List<ObjectRef> resolved_outcome_refs, @Nullable ObjectRef correction_run_ref, @Nullable ObjectRef successor_revision_ref, @Nullable ObjectRef environment_rerun_ref, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}

record CreateCorrectionRunRequest(long expected_version, UUID disposition_id, UUID failed_acceptance_run_id, String revision_hash, String correction_scope_digest) {}
record AdvanceCorrectionRunRequest(long expected_version, UUID correction_run_id, CorrectionTransitionWire transition, List<String> evidence_digests) {}
record LinkCorrectionRunWorkItemRequest(long expected_version, UUID correction_run_id, UUID work_item_id, String requirement_revision_hash) {}
record TargetedCriterionView(ObjectRef criterion_ref, String label, String failure_summary, List<String> required_evidence) {}
record CorrectionWorkItemLinkView(UUID link_id, ObjectRef work_item_ref, String work_item_label, @Nullable String owner_label, String state_label, String requirement_revision_hash, Instant linked_at) {}
record ReacceptanceScopeView(ReacceptanceModeWire mode, List<ObjectRef> criterion_refs, String reason, ObjectRef source_acceptance_run_ref, @Nullable ObjectRef target_candidate_ref) {}
record CorrectionRunView(UUID correction_run_id, ObjectRef disposition_ref, ObjectRef failed_acceptance_run_ref, ObjectRef revision_ref, String revision_hash, String correction_scope_digest, String phase, int consecutive_correction_number, List<TargetedCriterionView> targeted_criteria, List<CorrectionWorkItemLinkView> work_item_links, @Nullable ObjectRef replacement_candidate_ref, ReacceptanceScopeView reacceptance_scope, @Nullable ObjectRef reacceptance_run_ref, @Nullable ObjectRef correction_limit_decision_ref, String evidence_digest, List<String> blocker_codes, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}

record RequestCorrectionLimitDecisionRequest(long expected_version, UUID correction_run_id, CorrectionLimitOptionWire option, String scope_digest, String consequence_digest, String reason) {}
record ConfirmCorrectionLimitDecisionRequest(long expected_version, UUID decision_id, CorrectionLimitOptionWire option, String scope_digest) {}
record CorrectionLimitConfirmationView(String side, UUID actor_account_id, long actor_binding_version, String receipt_digest, Instant confirmed_at) {}
record CorrectionLimitDecisionView(UUID decision_id, ObjectRef correction_run_ref, CorrectionLimitOptionWire option, String scope_digest, String consequence_digest, String phase, List<CorrectionLimitConfirmationView> confirmations, @Nullable String next_required_side, List<ObjectRef> resolved_outcome_refs, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}

record CreateAcceptanceContinuityAssessmentRequest(long expected_version, UUID source_acceptance_run_id, UUID target_candidate_id, UUID requirement_id, String target_candidate_requirement_digest, String comparison_basis_digest) {}
record SubmitAcceptanceContinuityAttestationRequest(long expected_version, UUID assessment_id, UUID target_candidate_id, UUID requirement_id, String target_candidate_requirement_digest, String scope_digest, String criteria_digest) {}
record AcceptanceContinuityAssessmentView(UUID assessment_id, ObjectRef source_acceptance_run_ref, ObjectRef source_candidate_ref, ObjectRef target_candidate_ref, ObjectRef requirement_ref, String target_candidate_requirement_digest, ContinuityStatusWire status, String comparison_basis_digest, List<ObjectRef> affected_criterion_refs, List<EvidenceMetadataView> evidence, @Nullable ObjectRef attestation_ref, @Nullable ObjectRef targeted_reacceptance_run_ref, List<String> blocker_codes, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}
record AcceptanceContinuityAssessmentSummary(UUID assessment_id, ObjectRef source_acceptance_run_ref, ObjectRef source_candidate_ref, ObjectRef target_candidate_ref, ObjectRef requirement_ref, String target_candidate_requirement_digest, ContinuityStatusWire status, List<ObjectRef> affected_criterion_refs, @Nullable ObjectRef attestation_ref, long version, ObjectRef object_ref) {}
record AcceptanceContinuityAssessmentPage(List<AcceptanceContinuityAssessmentSummary> items, @Nullable String next_cursor, long projection_version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}
record AcceptanceContinuityAttestationView(UUID attestation_id, UUID assessment_id, UUID target_candidate_id, ObjectRef requirement_ref, String target_candidate_requirement_digest, String source_acceptance_receipt_digest, String scope_digest, String criteria_digest, long signer_binding_version, String envelope_digest, String validity, Instant valid_until, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}

record RetryArtifactPromotionRequest(long expected_version, UUID candidate_id, UUID promotion_id, UUID acceptance_evidence_set_id, String acceptance_complete_set_digest, String expected_artifact_digest) {}
record ReconcileArtifactPromotionRequest(long expected_version, UUID candidate_id, UUID promotion_id, UUID acceptance_evidence_set_id, String acceptance_complete_set_digest, String expected_artifact_digest, String observation_digest) {}
record ArtifactPromotionAttemptView(UUID attempt_id, String request_digest, @Nullable String provider_request_id, String outcome, @Nullable String observation_digest, Instant attempted_at) {}
record AcceptanceEvidenceRefView(ObjectRef requirement_ref, String candidate_requirement_digest, String evidence_kind, ObjectRef evidence_ref, String evidence_envelope_digest) {}
record AcceptanceEvidenceSetView(UUID evidence_set_id, UUID candidate_id, String candidate_payload_digest, String acceptance_complete_set_digest, int requirement_count, List<AcceptanceEvidenceRefView> evidence, Instant sealed_at, ObjectRef object_ref) {}
record ArtifactPromotionView(@Nullable UUID promotion_id, UUID candidate_id, AcceptanceEvidenceSetView acceptance_evidence_set, String artifact_policy, @Nullable String accepted_artifact_digest, @Nullable String target_reference_digest, @Nullable String assurance_receipt_digest, String phase, String consistency_state, List<ArtifactPromotionAttemptView> attempts, List<String> blocker_codes, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}
record ArtifactPromotionIntentView(UUID intent_id, UUID promotion_id, UUID acceptance_evidence_set_id, String acceptance_complete_set_digest, String request_digest, String state, Instant submitted_at, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}
record CompletionPredicateView(String predicate, boolean satisfied, @Nullable String evidence_digest, @Nullable String blocker_code) {}
record DeliveryCompletionEvaluationView(UUID batch_id, ObjectRef candidate_ref, ObjectRef acceptance_evidence_set_ref, String acceptance_complete_set_digest, boolean can_complete, List<CompletionPredicateView> predicates, String evidence_digest, @Nullable ObjectRef completion_receipt_ref, Instant evaluated_at, long version, ObjectRef object_ref, DisplayState display_state, List<AllowedAction> allowed_actions) {}

record VersionedApiResult<T>(T value, long version) {}
```

Every record component is non-null unless it carries explicit `@Nullable`. Constructors for records with collection components replace them with `List.copyOf`, `Set.copyOf`, or `Map.copyOf` before assignment; Jackson deserialization and projection mappers reject null collection elements. This preserves the immutable API snapshot semantics of the domain records instead of exposing caller-owned mutable collections.

Create `AcceptanceApiService.java` as the only controller-facing service. Its implementation delegates to the Task 3-8 `CandidateService`, `AcceptanceService`, `FailureDispositionService`, `CorrectionRunService`, `AcceptanceContinuityService`, `ArtifactPromotionService`, and `BatchCompletionService`; all authorization, RLS scope, fresh-auth consumption, idempotency claim, CAS, audit, outbox, and byte-exact replay remain in one `AuthorizationService.authorizeAndExecute` transaction:

```java
interface AcceptanceApiService {
    VersionedApiResult<DeliveryCandidatePage> listCandidatesAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID repositoryId, @Nullable String cursor, int limit);
    VersionedApiResult<DeliveryCandidateView> getCandidateAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId);
    VersionedApiResult<DeliveryCandidateEvidenceView> getCandidateEvidenceAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId);
    VersionedApiResult<AcceptanceRunView> createAcceptanceRunAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, CreateAcceptanceRunRequest request);
    VersionedApiResult<AcceptanceRunPage> listAcceptanceRunsAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, @Nullable String cursor, int limit);
    VersionedApiResult<AcceptanceRunView> getAcceptanceRunAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID acceptanceRunId);
    VersionedApiResult<AcceptanceCriterionDraftView> upsertCriterionDraftAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID acceptanceRunId, UUID criterionId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, UpsertAcceptanceCriterionDraftRequest request);
    VersionedApiResult<AcceptanceRunView> submitAcceptanceRunAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID acceptanceRunId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, SubmitAcceptanceRunRequest request);
    VersionedApiResult<FailureDispositionView> requestFailureDispositionAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID acceptanceRunId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, RequestFailureDispositionRequest request);
    VersionedApiResult<FailureDispositionView> confirmFailureDispositionAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID acceptanceRunId, UUID dispositionId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, ConfirmFailureDispositionRequest request);
    VersionedApiResult<FailureDispositionView> getFailureDispositionAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID acceptanceRunId, UUID dispositionId);
    VersionedApiResult<CorrectionRunView> createCorrectionRunAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID acceptanceRunId, UUID dispositionId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, CreateCorrectionRunRequest request);
    VersionedApiResult<CorrectionRunView> getCorrectionRunAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID correctionRunId);
    VersionedApiResult<CorrectionRunView> advanceCorrectionRunAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID correctionRunId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, AdvanceCorrectionRunRequest request);
    VersionedApiResult<CorrectionRunView> linkCorrectionRunWorkItemAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID correctionRunId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, LinkCorrectionRunWorkItemRequest request);
    VersionedApiResult<CorrectionLimitDecisionView> requestCorrectionLimitDecisionAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID correctionRunId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, RequestCorrectionLimitDecisionRequest request);
    VersionedApiResult<CorrectionLimitDecisionView> getCorrectionLimitDecisionAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID correctionRunId, UUID decisionId);
    VersionedApiResult<CorrectionLimitDecisionView> confirmCorrectionLimitDecisionAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID correctionRunId, UUID decisionId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, ConfirmCorrectionLimitDecisionRequest request);
    VersionedApiResult<AcceptanceContinuityAssessmentView> createContinuityAssessmentAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, CreateAcceptanceContinuityAssessmentRequest request);
    VersionedApiResult<AcceptanceContinuityAssessmentPage> listContinuityAssessmentsAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, @Nullable String cursor, int limit);
    VersionedApiResult<AcceptanceContinuityAssessmentView> getContinuityAssessmentAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID assessmentId);
    VersionedApiResult<AcceptanceContinuityAttestationView> submitContinuityAttestationAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID assessmentId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, SubmitAcceptanceContinuityAttestationRequest request);
    VersionedApiResult<ArtifactPromotionView> getArtifactPromotionAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId);
    VersionedApiResult<ArtifactPromotionIntentView> retryArtifactPromotionAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, RetryArtifactPromotionRequest request);
    VersionedApiResult<ArtifactPromotionIntentView> reconcileArtifactPromotionAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId, UUID candidateId, UUID freshAuthSessionId, String idempotencyKey, String ifMatch, ReconcileArtifactPromotionRequest request);
    VersionedApiResult<DeliveryCompletionEvaluationView> getCompletionEvaluationAuthorized(VerifiedRequestIdentity identity, UUID projectId, UUID batchId);
}
```

Every command validates every duplicated route/body ID before authorization and persistence. GETs use the same server-derived tenant/project/repository ownership and return the same `404 Problem` for absent and unauthorized resources. A Candidate evidence response is reconstructed from immutable metadata rows and digests; it never calls a Provider content API.

- [ ] **Step 5: Implement all 26 controller methods with explicit status and ETag**

Replace the Task 4 shell with this complete adapter; do not add generic status-update, arbitrary command, or untyped `Map<String, Object>` endpoints:

```java
@RestController
@RequestMapping("/v1/projects/{projectId}")
final class AcceptanceController {
    private final AcceptanceApiService api;

    AcceptanceController(AcceptanceApiService api) {
        this.api = api;
    }
    private static <T> ResponseEntity<T> respond(HttpStatus status, VersionedApiResult<T> result) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .eTag("\"" + result.version() + "\"").body(result.value());
    }

    @GetMapping("/delivery-batches/{batchId}/candidates")
    ResponseEntity<DeliveryCandidatePage> listCandidates(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @RequestParam("repository_id") UUID repositoryId, @RequestParam(required = false) @Nullable String cursor, @RequestParam(defaultValue = "50") int limit) {
        return respond(HttpStatus.OK, api.listCandidatesAuthorized(identity, projectId, batchId, repositoryId, cursor, limit));
    }

    @GetMapping("/delivery-batches/{batchId}/candidates/{candidateId}")
    ResponseEntity<DeliveryCandidateView> getCandidate(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId) {
        return respond(HttpStatus.OK, api.getCandidateAuthorized(identity, projectId, batchId, candidateId));
    }

    @GetMapping("/delivery-batches/{batchId}/candidates/{candidateId}/evidence")
    ResponseEntity<DeliveryCandidateEvidenceView> getCandidateEvidence(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId) {
        return respond(HttpStatus.OK, api.getCandidateEvidenceAuthorized(identity, projectId, batchId, candidateId));
    }

    @PostMapping("/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs")
    ResponseEntity<AcceptanceRunView> createAcceptanceRun(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateAcceptanceRunRequest request) {
        return respond(HttpStatus.CREATED, api.createAcceptanceRunAuthorized(identity, projectId, batchId, candidateId, fresh, key, ifMatch, request));
    }

    @GetMapping("/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs")
    ResponseEntity<AcceptanceRunPage> listAcceptanceRuns(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @RequestParam(required = false) @Nullable String cursor, @RequestParam(defaultValue = "50") int limit) {
        return respond(HttpStatus.OK, api.listAcceptanceRunsAuthorized(identity, projectId, batchId, candidateId, cursor, limit));
    }

    @GetMapping("/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}")
    ResponseEntity<AcceptanceRunView> getAcceptanceRun(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @PathVariable UUID acceptanceRunId) {
        return respond(HttpStatus.OK, api.getAcceptanceRunAuthorized(identity, projectId, batchId, candidateId, acceptanceRunId));
    }

    @PutMapping("/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/criteria/{criterionId}/draft")
    ResponseEntity<AcceptanceCriterionDraftView> upsertCriterionDraft(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @PathVariable UUID acceptanceRunId, @PathVariable UUID criterionId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody UpsertAcceptanceCriterionDraftRequest request) {
        return respond(HttpStatus.OK, api.upsertCriterionDraftAuthorized(identity, projectId, batchId, candidateId, acceptanceRunId, criterionId, fresh, key, ifMatch, request));
    }

    @PostMapping("/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/submissions")
    ResponseEntity<AcceptanceRunView> submitAcceptanceRun(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @PathVariable UUID acceptanceRunId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody SubmitAcceptanceRunRequest request) {
        return respond(HttpStatus.OK, api.submitAcceptanceRunAuthorized(identity, projectId, batchId, candidateId, acceptanceRunId, fresh, key, ifMatch, request));
    }

    @PostMapping("/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions")
    ResponseEntity<FailureDispositionView> requestFailureDisposition(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @PathVariable UUID acceptanceRunId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody RequestFailureDispositionRequest request) {
        return respond(HttpStatus.CREATED, api.requestFailureDispositionAuthorized(identity, projectId, batchId, candidateId, acceptanceRunId, fresh, key, ifMatch, request));
    }

    @PostMapping("/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions/{dispositionId}/confirmations")
    ResponseEntity<FailureDispositionView> confirmFailureDisposition(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @PathVariable UUID acceptanceRunId, @PathVariable UUID dispositionId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody ConfirmFailureDispositionRequest request) {
        return respond(HttpStatus.OK, api.confirmFailureDispositionAuthorized(identity, projectId, batchId, candidateId, acceptanceRunId, dispositionId, fresh, key, ifMatch, request));
    }

    @GetMapping("/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions/{dispositionId}")
    ResponseEntity<FailureDispositionView> getFailureDisposition(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @PathVariable UUID acceptanceRunId, @PathVariable UUID dispositionId) {
        return respond(HttpStatus.OK, api.getFailureDispositionAuthorized(identity, projectId, batchId, candidateId, acceptanceRunId, dispositionId));
    }

    @PostMapping("/delivery-batches/{batchId}/candidates/{candidateId}/acceptance-runs/{acceptanceRunId}/failure-dispositions/{dispositionId}/correction-runs")
    ResponseEntity<CorrectionRunView> createCorrectionRun(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @PathVariable UUID acceptanceRunId, @PathVariable UUID dispositionId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateCorrectionRunRequest request) {
        return respond(HttpStatus.CREATED, api.createCorrectionRunAuthorized(identity, projectId, batchId, candidateId, acceptanceRunId, dispositionId, fresh, key, ifMatch, request));
    }

    @GetMapping("/delivery-batches/{batchId}/correction-runs/{correctionRunId}")
    ResponseEntity<CorrectionRunView> getCorrectionRun(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID correctionRunId) {
        return respond(HttpStatus.OK, api.getCorrectionRunAuthorized(identity, projectId, batchId, correctionRunId));
    }

    @PostMapping("/delivery-batches/{batchId}/correction-runs/{correctionRunId}/transitions")
    ResponseEntity<CorrectionRunView> advanceCorrectionRun(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID correctionRunId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody AdvanceCorrectionRunRequest request) {
        return respond(HttpStatus.OK, api.advanceCorrectionRunAuthorized(identity, projectId, batchId, correctionRunId, fresh, key, ifMatch, request));
    }

    @PostMapping("/delivery-batches/{batchId}/correction-runs/{correctionRunId}/work-item-links")
    ResponseEntity<CorrectionRunView> linkCorrectionRunWorkItem(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID correctionRunId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody LinkCorrectionRunWorkItemRequest request) {
        return respond(HttpStatus.CREATED, api.linkCorrectionRunWorkItemAuthorized(identity, projectId, batchId, correctionRunId, fresh, key, ifMatch, request));
    }

    @PostMapping("/delivery-batches/{batchId}/correction-runs/{correctionRunId}/limit-decisions")
    ResponseEntity<CorrectionLimitDecisionView> requestCorrectionLimitDecision(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID correctionRunId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody RequestCorrectionLimitDecisionRequest request) {
        return respond(HttpStatus.CREATED, api.requestCorrectionLimitDecisionAuthorized(identity, projectId, batchId, correctionRunId, fresh, key, ifMatch, request));
    }

    @GetMapping("/delivery-batches/{batchId}/correction-runs/{correctionRunId}/limit-decisions/{decisionId}")
    ResponseEntity<CorrectionLimitDecisionView> getCorrectionLimitDecision(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID correctionRunId, @PathVariable UUID decisionId) {
        return respond(HttpStatus.OK, api.getCorrectionLimitDecisionAuthorized(identity, projectId, batchId, correctionRunId, decisionId));
    }

    @PostMapping("/delivery-batches/{batchId}/correction-runs/{correctionRunId}/limit-decisions/{decisionId}/confirmations")
    ResponseEntity<CorrectionLimitDecisionView> confirmCorrectionLimitDecision(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID correctionRunId, @PathVariable UUID decisionId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody ConfirmCorrectionLimitDecisionRequest request) {
        return respond(HttpStatus.OK, api.confirmCorrectionLimitDecisionAuthorized(identity, projectId, batchId, correctionRunId, decisionId, fresh, key, ifMatch, request));
    }

    @PostMapping("/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments")
    ResponseEntity<AcceptanceContinuityAssessmentView> createContinuityAssessment(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateAcceptanceContinuityAssessmentRequest request) {
        return respond(HttpStatus.CREATED, api.createContinuityAssessmentAuthorized(identity, projectId, batchId, candidateId, fresh, key, ifMatch, request));
    }

    @GetMapping("/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments")
    ResponseEntity<AcceptanceContinuityAssessmentPage> listContinuityAssessments(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @RequestParam(required = false) @Nullable String cursor, @RequestParam(defaultValue = "50") int limit) {
        return respond(HttpStatus.OK, api.listContinuityAssessmentsAuthorized(identity, projectId, batchId, candidateId, cursor, limit));
    }

    @GetMapping("/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments/{assessmentId}")
    ResponseEntity<AcceptanceContinuityAssessmentView> getContinuityAssessment(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @PathVariable UUID assessmentId) {
        return respond(HttpStatus.OK, api.getContinuityAssessmentAuthorized(identity, projectId, batchId, candidateId, assessmentId));
    }

    @PostMapping("/delivery-batches/{batchId}/candidates/{candidateId}/continuity-assessments/{assessmentId}/attestations")
    ResponseEntity<AcceptanceContinuityAttestationView> submitContinuityAttestation(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @PathVariable UUID assessmentId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody SubmitAcceptanceContinuityAttestationRequest request) {
        return respond(HttpStatus.CREATED, api.submitContinuityAttestationAuthorized(identity, projectId, batchId, candidateId, assessmentId, fresh, key, ifMatch, request));
    }

    @GetMapping("/delivery-batches/{batchId}/candidates/{candidateId}/artifact-promotion")
    ResponseEntity<ArtifactPromotionView> getArtifactPromotion(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId) {
        return respond(HttpStatus.OK, api.getArtifactPromotionAuthorized(identity, projectId, batchId, candidateId));
    }

    @PostMapping("/delivery-batches/{batchId}/candidates/{candidateId}/artifact-promotion/retry-requests")
    ResponseEntity<ArtifactPromotionIntentView> retryArtifactPromotion(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody RetryArtifactPromotionRequest request) {
        return respond(HttpStatus.ACCEPTED, api.retryArtifactPromotionAuthorized(identity, projectId, batchId, candidateId, fresh, key, ifMatch, request));
    }

    @PostMapping("/delivery-batches/{batchId}/candidates/{candidateId}/artifact-promotion/reconciliation-requests")
    ResponseEntity<ArtifactPromotionIntentView> reconcileArtifactPromotion(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId, @PathVariable UUID candidateId, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody ReconcileArtifactPromotionRequest request) {
        return respond(HttpStatus.ACCEPTED, api.reconcileArtifactPromotionAuthorized(identity, projectId, batchId, candidateId, fresh, key, ifMatch, request));
    }

    @GetMapping("/delivery-batches/{batchId}/completion-evaluation")
    ResponseEntity<DeliveryCompletionEvaluationView> getCompletionEvaluation(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID batchId) {
        return respond(HttpStatus.OK, api.getCompletionEvaluationAuthorized(identity, projectId, batchId));
    }
}
```

`AcceptanceControllerApiTest` loads Spring's `RequestMappingHandlerMapping` and asserts the exact 26 `(HTTP method, path, controller method)` triples from Step 1. It also verifies every success response has the specified status, quoted numeric `ETag`, generated response schema, `Cache-Control: no-store` on views containing fresh-auth-gated actions, both security alternatives, conditional browser-CSRF metadata on every mutation, and no `tenantId` input. It proves list operations honor cursor scope, stable order, page-size bounds, and post-cache-eviction reconstruction. `submitAcceptanceRun` remains the only overall acceptance submission operation; no operation ID contains `setAccepted`, `setCompleted`, `updateStatus`, or `patchCandidate`.

- [ ] **Step 6: Add black-box security and PostgreSQL integration coverage**

Extend `AcceptanceAuthorizationSecurityTest.java` with exact HTTP tests: forged `X-Tenant-ID` is ignored; tenant A cannot read or mutate tenant B Candidate/Run/Disposition/Correction/limit-decision/Continuity/Promotion IDs; wrong project, batch, repository, Candidate, Run, criterion, disposition, CorrectionRun, limit decision, assessment, promotion, or WorkItem linkage returns the concealed `404 Problem`; development-side and supplier actors cannot autosave or submit business acceptance; the same natural person cannot confirm both FailureDisposition or CorrectionLimitDecision sides; missing/expired/replayed/wrong-action fresh auth fails without a domain write; cross-scope cursors return `INVALID_CURSOR`; a caller cannot inject actor, tenant, role, side, receipt, outcome refs, or validity fields because every request schema is closed. Parameterize all 14 mutations over browser cookie and OIDC bearer authentication: cookie requests with missing Origin, missing token, denied Origin, wrong session/tenant/generation token, or post-logout token return the exact CSRF Problem and invoke no controller/service method, while same-origin current-session token succeeds; bearer requests succeed without CSRF and still derive identity only from the verified bearer principal.

Create `AcceptanceApiIntegrationIT.java` with PostgreSQL 17.5. Its setup is exactly `postgres.start()`, `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)`, then `Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).target("053").load().migrate()`. Define a PostgreSQL-backed nested `AcceptanceApiIntegrationScenarios` fixture in the same test file; each method below executes the exact HTTP, database, audit, and outbox assertions specified after the block, and none may be an empty test helper. Add these executable scenarios:

```java
private final AcceptanceApiIntegrationScenarios scenarios =
    AcceptanceApiIntegrationScenarios.using(postgres, httpClient, repositories, clock);

@Test void criterionAutosaveIsCasAndIdempotentAndFinalSubmitFreezesAllDrafts() {
    scenarios.verifyCriterionAutosaveCasAndFinalFreeze();
}
@Test void staleCandidateSubmitReturnsVersionConflictWithCurrentTargetAndNoSignature() {
    scenarios.verifyStaleCandidateSubmitFailsWithoutSignature();
}
@Test void bilateralDispositionRequiresDifferentNaturalPersonsAndMatchingClassification() {
    scenarios.verifyBilateralDispositionIdentityAndClassification();
}
@Test void implementationDefectCorrectionPreservesRevisionAndLinksOnlySameScopeWorkItems() {
    scenarios.verifyCorrectionRevisionAndWorkItemScope();
}
@Test void acceptanceRunAndContinuityPagesRebuildARefreshRouteFromDurableChildRefs() {
    scenarios.verifyDurableRefreshRoutes();
}
@Test void correctionLimitOffersExactlyFourBilateralFreshAuthDecisionsAndPersistsOutcomeRefs() {
    scenarios.verifyCorrectionLimitDecisionContract();
}
@Test void cookieMutationsRequireSameOriginSessionBoundCsrfWhileBearerMutationsAreCsrfExempt() {
    scenarios.verifyCookieAndBearerCsrfPolicy();
}
@Test void unaffectedContinuityRequiresDevelopmentAttestationWhileAffectedRequiresReacceptance() {
    scenarios.verifyContinuityDecisionPaths();
}
@Test void threeRequirementsExposeTwoDirectRunsAndOneContinuityItemInOneCompleteSet() {
    scenarios.verifyMixedCompleteEvidenceSet();
}
@Test void promotionAndCompletionExposeTheIdenticalAcceptanceCompleteSetDigest() {
    scenarios.verifyPromotionCompletionDigestIdentity();
}
@Test void artifactPromotionSchemaHasNoSingularAcceptanceRunProof() {
    scenarios.verifyNoSingularAcceptanceRunProof();
}
@Test void promotionRetryRemainsSuspendedWhenExternalOutcomeIsUnknown() {
    scenarios.verifyUnknownPromotionOutcomeSuspendsRetry();
}
@Test void completionEvaluationExposesEveryPredicateAndNeverOffersSetCompleted() {
    scenarios.verifyCompletionPredicateProjection();
}
@Test void allCommandReplaysReturnByteExactStatusHeadersAndResponse() {
    scenarios.verifyByteExactCommandReplay();
}
```

For every mutation, the integration test posts identical input twice with one idempotency key and asserts byte-identical status, body, and `ETag`; changed input with the same key returns `409 IDEMPOTENCY_KEY_REUSED`; stale `If-Match` or body/header version disagreement returns `409 VERSION_CONFLICT`; a rollback leaves no fresh-auth consumption, audit, outbox, or partial receipt. Exercise all four correction-limit options, prove the first request and opposite-side confirmation each consume a distinct action-bound fresh-auth proof, and verify the second side cannot change option or scope. Query responses and every child `ObjectRef` are rebuilt from PostgreSQL projections after cache eviction and remain byte-equivalent; following Candidate -> AcceptanceRun -> FailureDisposition -> CorrectionRun -> limit decision, Candidate -> continuity refs, and Candidate -> complete evidence set -> promotion/completion receipt needs no cache, event history, singular Run shortcut, or guessed ID. Assert every Candidate Requirement view exposes its frozen owner-binding version/digest and that promotion, source-tree assurance, latest completion evaluation, and completion receipt all return the same stored complete-set digest.

- [ ] **Step 7: Generate the cumulative client and run all API gates**

Run each command independently so a failing owner is unambiguous:

```bash
pnpm contracts:lint
./gradlew :tests:api:test --tests '*AcceptanceApiContractTest'
./gradlew :tests:api:test --tests '*AcceptanceControllerApiTest'
./gradlew :tests:security-negative:test --tests '*AcceptanceAuthorizationSecurityTest'
./gradlew :tests:integration:test --tests '*AcceptanceApiIntegrationIT'
pnpm contracts:generate
pnpm --filter @accord/api-client typecheck
pnpm --filter @accord/api-client test
pnpm --filter @accord/api-client check:generated
node --test tests/contracts/openapi-cumulative-merge.test.mjs
git diff --exit-code packages/api-client
```

Expected: all 26 operation IDs, paths, controller methods, request/response schemas, success statuses, ETags, Problems, authorization actions, query parameters, fresh-auth modes, `{oidc|browserSession}` alternatives, and conditional browser-CSRF controls match exactly; the cumulative manifest retains every earlier owner and registers the exact 26-operation `candidate-acceptance` owner without collision; no internal evidence-seal/completion-worker command leaks into the public surface; the generated client exposes typed functions for all 26 operations, typed `CriterionResultWire` values `passed | failed | unverifiable`, and the four-value `CorrectionLimitOptionWire`; safe business projections expose owner-binding and complete-set digests plus all evidence refs while `ArtifactPromotionView` has no singular `acceptance_run_id`; the recursive forbidden-field test finds no source/blob/content/diff/patch/provider payload; unauthorized, stale, cross-origin, or wrong-session commands create no state; a second generation has no diff. This task is complete before Web Task 15 begins.

- [ ] **Step 8: Commit the acceptance API**

```bash
git add contracts/openapi/accord-control-api.yaml \
  contracts/openapi/ownership-manifest.yaml \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/api/AcceptanceApiModels.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/api/AcceptanceApiService.java \
  apps/control-plane/modules/candidate-acceptance/src/main/java/com/inforvans/accord/acceptance/api/AcceptanceController.java \
  tests/api/src/test/java/com/inforvans/accord/api/AcceptanceApiContractTest.java \
  tests/api/src/test/java/com/inforvans/accord/api/AcceptanceControllerApiTest.java \
  tests/security-negative/src/test/java/com/inforvans/accord/security/AcceptanceAuthorizationSecurityTest.java \
  tests/integration/src/test/java/com/inforvans/accord/integration/AcceptanceApiIntegrationIT.java \
  tests/contracts/openapi-cumulative-merge.test.mjs \
  packages/api-client/src/generated
git commit -m "feat(api): expose exact acceptance workflow"
```

### Task 10: Prove Candidate-To-Promotion End To End

**Files:**
- Create: `tests/e2e/candidate-acceptance.spec.ts`
- Create: `tests/fault-injection/acceptance/candidate-chaos.spec.ts`
- Create: `tests/fault-injection/acceptance/promotion-chaos.spec.ts`
- Create: `tests/fault-injection/acceptance/completion-chaos.spec.ts`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/AcceptanceCompletionSecurityTest.java`
- Create: `tests/performance/k6/candidate-acceptance.js`
- Create: `docs/runbooks/acceptance-recovery.md`

- [ ] **Step 1: Add complete user journeys**

Cover exact Candidate construction with per-Requirement immutable owner-binding versions, business acceptance per criterion, supplier separation, inaccessible attachment hold, implementation defect/CorrectionRun/reacceptance, requirement change/new Revision, environment rerun/same Candidate, continuity unaffected/affected/uncertain, and one three-Requirement evidence set containing direct Run evidence for two Requirements plus continuity evidence for the third. Prove missing/duplicate/stale evidence cannot seal, the JCS complete-set digest is identical in promotion/assurance and completion receipts, and no singular Run proves the whole Candidate. Cover the Candidate's controlled default merge advancing `MERGED -> RECONCILED` without invalidation, exact owned-merge outcome/ancestry uncertainty remaining `ACTIVE + RECONCILING` without a validity overlay, and only confirmed unrelated hotfix/unexplained head drift/wrong result tree invalidating it. Finish both immutable-artifact promotion and source-tree-only profiles through the durable completed invariant.

```ts
test('implementation defect keeps revision and creates a new candidate', async ({ acceptanceOwner, developmentPrincipal }) => {
  const originalRevision = await acceptanceOwner.revisionHash();
  await acceptanceOwner.failCriterion('AC-001');
  await acceptanceOwner.classifyFailure('implementation_defect');
  await developmentPrincipal.confirmFailureDisposition();
  await expect(acceptanceOwner.correctionRun()).toBeVisible();
  expect(await acceptanceOwner.revisionHash()).toBe(originalRevision);
  await expect(acceptanceOwner.newCandidateId()).not.toHaveText(await acceptanceOwner.oldCandidateId());
});
```

- [ ] **Step 2: Run cross-role E2E without retries**

Run: `pnpm test:system --grep @candidate-acceptance`

Expected: all browser projects pass with no retry and no optimistic success on acceptance/promotion.

- [ ] **Step 3: Inject stale facts, evidence races, duplicate/out-of-order events, worker crashes, and registry uncertainty**

Run: `pnpm test:fault --grep @candidate-acceptance`

Expected: duplicate/out-of-order events from every Task 8 trigger family always reload current facts; provider unavailable then available creates a linked attempt-1/attempt-2 request chain and one evaluated event per evaluation before exactly one completion; crashes before/after trigger inbox, evaluation/evaluated-event/next-request insert, completion CAS, and completion outbox commit converge to one receipt/completion event; evidence invalidation racing seal/promotion/completion prevents stale success; each external ambiguity converges to one immutable fact or a documented suspended/reconciling state; no old acceptance is copied and no artifact is rebuilt.

- [ ] **Step 4: Run property, security, performance, and audit gates**

Run: `./gradlew :tests:state-machine:test :tests:security-negative:test --tests '*Acceptance*' --tests '*Completion*' && k6 run tests/performance/k6/candidate-acceptance.js`

Expected: zero unauthorized acceptance, zero wrong-Candidate/Requirement/owner-binding evidence, zero incomplete-set promotion, zero duplicate completion, normal API p95 at most 2 seconds, and a complete audit/outbox/inbox chain with one `delivery.completion_evaluated.v1` for every persisted evaluation.

- [ ] **Step 5: Commit subsystem certification**

```bash
git add tests/e2e/candidate-acceptance.spec.ts \
  tests/fault-injection/acceptance/candidate-chaos.spec.ts \
  tests/fault-injection/acceptance/promotion-chaos.spec.ts \
  tests/fault-injection/acceptance/completion-chaos.spec.ts \
  tests/security-negative/src/test/java/com/inforvans/accord/security/AcceptanceCompletionSecurityTest.java \
  tests/performance/k6/candidate-acceptance.js \
  docs/runbooks/acceptance-recovery.md
git commit -m "test(acceptance): certify candidate to artifact promotion"
```

## Completion Gate

This subsystem is complete only when Candidate evidence cross-checks CI, Provider, Context, manifest, artifact, and immutable per-Requirement acceptance-owner binding facts; every invalidating bound change creates a new Candidate while the Candidate's own exact controlled default merge advances to `RECONCILED` without invalidation and exact owned-merge uncertainty stays `ACTIVE + RECONCILING` without a validity overlay; only an authorized business acceptance owner can sign exact criterion and overall results; failure classification selects the correct Revision/Correction/environment path; continuity never migrates old signatures; a sealed append-only set contains exactly one active evidence item for every Candidate Requirement; promotion/assurance and completion lock the same canonical complete-set digest; promotion preserves the accepted artifact digest or remains suspended; source-tree-only projects make no artifact guarantee; durable outbox/inbox reevaluation gives every unavailable retry a new request identity, emits one evaluated event per evaluation, covers every completion-affecting fact, and completes exactly once under one CAS transaction; and all role, replay, race, stale-fact, out-of-order-event, crash, and uncertain-external-result tests pass.
