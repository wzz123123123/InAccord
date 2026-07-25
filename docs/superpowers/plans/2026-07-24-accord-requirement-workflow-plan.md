# Accord Requirement Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the canonical Requirement Graph, versioned business/development projections, secure intake and attachment workflow, proposals, ActionRequests, exact-revision confirmations, and Ready Pool eligibility.

**Architecture:** The `requirement-graph`, `collaboration`, `attachments-metadata`, and `actions-notifications` Spring Modulith modules own one PostgreSQL-backed semantic model. Commands use tenant-scoped authorization, compare-and-swap versions, transactionally persisted audit/outbox records, and idempotent results; projections and SSE are rebuildable and never decide approval eligibility.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Spring Modulith 1.4.1, Gradle 8.14.3 Groovy DSL, jOOQ 3.19.24, PostgreSQL 17.5, JSON Schema 2020-12, RFC 8785 JCS, SHA-256, private OSS/S3-compatible object storage, Temporal activities, OpenAPI 3.1, SSE, Testcontainers, JUnit 5, AssertJ, jqwik, and WireMock.

---

## Dependencies And Non-Negotiable Invariants

Execute after the platform foundation and identity/tenancy/audit plans. The assessment module may initially be represented by the `AssessmentEligibilityPort`; replace that adapter with the real implementation in the Agent Context and Assessment plan without changing this module's commands.

Tasks 1-9 form the backend workflow slice. Task 10 is a cross-plan production certification gate and runs only after Web Experience Tasks 1-11 have produced the route, intake, attachment, dual-view, and confirmation journeys; executing it earlier is a dependency error. Ready Pool and DeliveryBatch user-interface proof remains owned by Web Task 13 at M3 and is not a prerequisite for this M1 workflow gate. Task 10 creates the single shared system-test runner that later Agent, Git, and Candidate plans extend, never a second frontend.

- `RequirementRevision` is the only semantic source. Business and development views are projections of the same revision.
- `revision_hash = SHA-256(JCS({schema_version, requirement_id, revision_no, parent_revision_hash, semantic_payload}))`.
- Layout, people, notifications, timestamps, delivery assignment, and derived status never enter the semantic hash.
- Developers cannot directly mutate business-owned fields; they submit `DevelopmentProposal` objects.
- Development confirmation precedes business confirmation of the exact same hash.
- Strict final confirmations require two different natural-person IDs across sides.
- Contractual attachment versions enter the hash and pass access preflight; reference attachments do not invalidate a revision merely because one is added.
- Every command carries `Idempotency-Key`, `expected_version`, and where applicable `expected_revision_hash`.
- Every Testcontainers fixture in this plan that starts control-plane PostgreSQL must call `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)` immediately after `postgres.start()` and before its first `Flyway.configure()`, `load()`, or `migrate()` call. No fixture may create runtime roles locally or repair role membership or grants after Flyway.

## File Map

```text
contracts/json-schema/requirement/
  requirement-revision.schema.json
  requirement-block.schema.json
  attachment-binding.schema.json
  development-proposal.schema.json
  business-question.schema.json
contracts/events/requirement/
  requirement-events.schema.json
contracts/openapi/accord-control-api.yaml
contracts/golden-fixtures/requirement-contract/
  appendix-a-illustrative.json
  appendix-a-to-canonical.mapping.json
contracts/golden-fixtures/requirement/
  canonical-revision-v1.json
  canonical-revision-v1.hash-input.jcs.json
  canonical-revision-v1.sha256
  canonicalization-vectors.json
  invalid-cross-owner-edit.json
database/control-plane/migrations/
  V020__requirement_graph.sql
  V021__requirement_intake_drafts.sql
  V022__attachment_metadata.sql
  V023__collaboration_actions.sql
apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/
  domain/RequirementModels.java
  domain/RequirementCommands.java
  domain/RequirementAggregate.java
  domain/RevisionCanonicalizer.java
  application/RequirementCommandService.java
  application/RequirementQueryService.java
  application/RequirementPorts.java
  infrastructure/JooqRequirementRepository.java
  api/RequirementController.java
apps/control-plane/modules/attachments-metadata/src/main/java/com/inforvans/accord/attachment/
  domain/AttachmentModels.java
  application/AttachmentService.java
  application/AttachmentAccessPreflight.java
  infrastructure/S3AttachmentStore.java
  api/AttachmentController.java
apps/attachment-scanner/
  src/main/java/com/inforvans/accord/scanner/AttachmentScannerApplication.java
  src/main/java/com/inforvans/accord/scanner/scan/ScanPipeline.java
  src/main/java/com/inforvans/accord/scanner/scan/MimeDetector.java
  src/main/java/com/inforvans/accord/scanner/scan/ArchiveInspector.java
  src/main/java/com/inforvans/accord/scanner/scan/ClamAvClient.java
  src/main/java/com/inforvans/accord/scanner/store/QuarantineStore.java
  src/main/java/com/inforvans/accord/scanner/preview/PreviewRenderer.java
apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/
  domain/ProposalModels.java
  domain/BusinessQuestionModels.java
  domain/ConfirmationModels.java
  application/ProposalService.java
  application/BusinessQuestionService.java
  application/ConfirmationService.java
  application/ReadyPoolService.java
apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/
  domain/ActionRequest.java
  domain/NotificationModels.java
  application/ActionRequestService.java
  application/ActionRoutingService.java
  application/NotificationOrchestrator.java
  application/NotificationPreferencePort.java
  infrastructure/NotificationDeliveryWorker.java
  infrastructure/InAppNotificationAdapter.java
  infrastructure/EmailNotificationAdapter.java
  infrastructure/EnterpriseImNotificationAdapter.java
  infrastructure/MobilePushNotificationAdapter.java
  api/ActionRequestController.java
apps/control-plane/modules/*/src/test/java/com/inforvans/accord/
  requirement/RequirementCanonicalizationTest.java
  requirement/RequirementConcurrencyTest.java
  attachment/AttachmentLifecycleTest.java
  collaboration/ProposalAndConfirmationTest.java
  action/ActionRequestIdempotencyTest.java
tests/e2e/requirement-workflow.spec.ts
```

### Task 1: Freeze Requirement Contracts And Database Constraints

**Files:**
- Modify: `settings.gradle`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java`
- Modify: `apps/control-plane/worker/build.gradle`
- Create: `apps/control-plane/modules/requirement-graph/build.gradle`
- Create: `apps/control-plane/modules/attachments-metadata/build.gradle`
- Create: `apps/control-plane/modules/collaboration/build.gradle`
- Create: `apps/control-plane/modules/actions-notifications/build.gradle`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/package-info.java`
- Create: `apps/control-plane/modules/attachments-metadata/src/main/java/com/inforvans/accord/attachment/package-info.java`
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/package-info.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/package-info.java`
- Modify: `tests/contract/build.gradle`
- Modify: `tests/integration/build.gradle`
- Modify: `tests/api/build.gradle`
- Modify: `tests/security-negative/build.gradle`
- Modify: `tests/state-machine/build.gradle`
- Modify: `tests/fault-injection/build.gradle`
- Create: `contracts/json-schema/requirement/requirement-revision.schema.json`
- Create: `contracts/json-schema/requirement/requirement-block.schema.json`
- Create: `contracts/json-schema/requirement/attachment-binding.schema.json`
- Create: `contracts/events/requirement/requirement-events.schema.json`
- Create: `contracts/golden-fixtures/requirement-contract/appendix-a-illustrative.json`
- Create: `contracts/golden-fixtures/requirement-contract/appendix-a-to-canonical.mapping.json`
- Create: `contracts/golden-fixtures/requirement/canonical-revision-v1.json`
- Create: `contracts/golden-fixtures/requirement/canonical-revision-v1.hash-input.jcs.json`
- Create: `contracts/golden-fixtures/requirement/canonical-revision-v1.sha256`
- Create: `database/control-plane/migrations/V020__requirement_graph.sql`
- Test: `tests/contract/src/test/java/com/inforvans/accord/contracts/RequirementSchemaTest.java`
- Test: `tests/contract/src/test/java/com/inforvans/accord/contracts/RequirementCanonicalBytesTest.java`
- Create: `tests/contract/src/test/java/com/inforvans/accord/contracts/AppendixAIllustrativeNormalizer.java`
- Create: `tests/contract/node/requirement-canonical-bytes.test.mjs`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/contracts/RequirementCanonicalBytesIndependentTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/RequirementMigrationIT.java`
- Verify: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/TenantRlsTest.java`
- Verify: `tests/architecture/verify-control-plane-fixtures.ps1`

- [ ] **Step 1: Add failing schema and canonicalization contract tests**

```java
class RequirementSchemaTest {
    @Test
    void appendixARemainsIllustrativeAndNormalizesToTheCanonicalFixture() {
        var illustrative = fixture("requirement-contract/appendix-a-illustrative.json");
        assertThat(schema("requirement/requirement-revision.schema.json").validate(illustrative)).isNotEmpty();
        var canonical = new AppendixAIllustrativeNormalizer(mapping()).normalize(illustrative);
        assertThat(canonical).isEqualTo(fixture("requirement/canonical-revision-v1.json"));
        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(new AppendixAIllustrativeNormalizer(mapping()).normalize(illustrative)).isEqualTo(canonical);
        }
        assertThat(CanonicalJson.sha256(canonicalHashBytes(canonical)))
            .isEqualTo(canonical.path("revision_hash").asText());
        assertThat(canonical.path("revision_hash").asText())
            .isNotEqualTo(illustrative.path("revision_hash").asText());
    }

    @Test
    void layoutAndActorMetadataAreRejectedFromSemanticPayload() {
        ObjectNode node = fixture("requirement/canonical-revision-v1.json").deepCopy();
        node.withObject("/semantic_payload").put("canvas_x", 120);
        assertThat(schema("requirement/requirement-revision.schema.json").validate(node))
            .extracting(ValidationMessage::getMessage)
            .contains("property 'canvas_x' is not allowed");
    }

    @Test
    void normalizationRejectsUnknownFieldsAmbiguousMappingsAndMixedRequirementIds() {
        assertThatThrownBy(() -> normalize(appendixWith("semantic_payload.blocks[0].owner", "x")))
            .isInstanceOf(IllustrativeMappingRejected.class);
        assertThatThrownBy(() -> normalize(appendixWithTwoTargetsFor("semantic_payload.blocks[0].id")))
            .isInstanceOf(IllustrativeMappingRejected.class);
        assertThatThrownBy(() -> normalize(appendixWithRelationRequirementId("REQ-2026-0042")))
            .isInstanceOf(IllustrativeMappingRejected.class);
    }

    @Test
    void independentCanonicalBytesAndDigestFixturesAgree() {
        var canonical = fixture("requirement/canonical-revision-v1.json");
        byte[] expectedBytes = fixtureBytes("requirement/canonical-revision-v1.hash-input.jcs.json");
        String expectedDigest = fixtureText("requirement/canonical-revision-v1.sha256").trim();
        assertThat(FoundationJcs.canonicalize(canonicalHashEnvelope(canonical))).isEqualTo(expectedBytes);
        assertThat(Sha256.hex(expectedBytes)).isEqualTo(expectedDigest);
        assertThat(canonical.path("revision_hash").asText()).isEqualTo("sha256:" + expectedDigest);
    }
}
```

`appendix-a-illustrative.json` is a byte-for-byte, read-only transcription of specification Appendix A. Its `REQ-2026-0042`, `id/type` block aliases, shortened attachment identifier, and explicitly illustrative digest are not canonical production values. No test may assert that Appendix A validates against the production schema or that its example digest can be recomputed. `appendix-a-to-canonical.mapping.json` is a closed, versioned mapping document with exact source JSON pointers, exact target JSON pointers, named transforms, and constants. It maps the single top-level illustrative Requirement ID to the fixed UUID `6d36b8c8-7f57-4f32-9b73-2ec6ef11a042`, maps block `id/type` to `block_id/block_type`, expands each illustrative block into every required closed canonical field, maps relation endpoints through the block-ID table, and maps the illustrative attachment token to a fixed UUID fixture attachment. A source pointer may have exactly one target and every consumed alias must be declared; duplicate targets, undeclared source fields, lossy fallbacks, implicit ID coercion, and any `REQ-*` value in canonical output fail closed.

`canonical-revision-v1.json` is the only digest-bearing semantic fixture. It uses UUID format for the top-level Requirement ID and every relation endpoint, UUID attachment IDs, `block_id/block_type`, and the exact closed fields of the branch schemas. `canonical-revision-v1.hash-input.jcs.json` is the checked-in exact UTF-8 RFC 8785/JCS byte sequence of the five-field envelope `{schema_version, requirement_id, revision_no, parent_revision_hash, semantic_payload}` with no BOM or trailing newline; `canonical-revision-v1.sha256` is the checked-in lowercase 64-hex SHA-256 of those bytes. The expected bytes/digest are artifacts independent of the normalizer and are reviewed as golden contract inputs. `revision_hash` equals `sha256:` plus that file. RFC 8785/JCS never hashes the illustrative bytes, mapping file, illustrative `revision_hash`, display fields, actors, or receipts.

The contract gate runs Foundation JCS plus the primary Java/Jackson, independently packaged Java/Picocli, and Node verifier entry points against the checked-in byte and digest artifacts. Each verifier parses `canonical-revision-v1.json`, selects the exact five fields, emits JCS, byte-compares with `.hash-input.jcs.json`, hashes those bytes, and compares with `.sha256`; none imports `AppendixAIllustrativeNormalizer`. A mutation to any canonical semantic byte must fail all digest checks, while changing Appendix A's illustrative digest must not change or fail the canonical golden digest.

In `ModuleBoundaryTest.java`, replace `requiredModules` with:

```java
private static final Set<String> REQUIRED_MODULES = Set.of(
    "platformkernel", "reliability",
    "identity", "authorization", "audit",
    "requirement", "attachment", "collaboration", "action"
);
```

- [ ] **Step 2: Run the contract test and verify it fails before schemas exist**

Run: `./gradlew :tests:contract:test --tests '*RequirementSchemaTest'`

Expected: `FAILED` with a missing `requirement-revision.schema.json` resource.

- [ ] **Step 3: Register the bounded modules and executable shared test projects**

Append these projects to `settings.gradle` before any domain-module task is invoked:

```groovy
include(
    ':apps:control-plane:modules:requirement-graph',
    ':apps:control-plane:modules:attachments-metadata',
    ':apps:control-plane:modules:collaboration',
    ':apps:control-plane:modules:actions-notifications'
)
```

Create `requirement-graph/build.gradle`:

```groovy
plugins {
    id 'java-library'
    alias(libs.plugins.pitest)
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':apps:control-plane:modules:reliability')
    implementation project(':apps:control-plane:modules:authorization')
    implementation project(':database:control-plane')
    implementation libs.spring.boot.jooq
    implementation libs.spring.boot.validation
    testImplementation testFixtures(project(':database:control-plane'))
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.jqwik
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
}
pitest {
    targetClasses = ['com.inforvans.accord.requirement.domain.*']
    targetTests = ['com.inforvans.accord.requirement.*']
    junit5PluginVersion = '1.2.1'
    mutationThreshold = 90
    outputFormats = ['XML', 'HTML']
    timestampedReports = false
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
```

Create `attachments-metadata/build.gradle` and `actions-notifications/build.gradle` with this exact content:

```groovy
plugins { id 'java-library' }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':apps:control-plane:modules:reliability')
    implementation project(':apps:control-plane:modules:authorization')
    implementation project(':database:control-plane')
    implementation libs.spring.boot.jooq
    implementation libs.spring.boot.validation
    testImplementation testFixtures(project(':database:control-plane'))
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
```

For `actions-notifications/build.gradle` only, also add `implementation(project(":apps:control-plane:modules:identity"))` so delivery reads Identity-owned project/user preferences through `NotificationPreferencePort`; Identity must not depend on `actions-notifications`, which keeps the module graph acyclic.

Create `collaboration/build.gradle`:

```groovy
plugins { id 'java-library' }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':apps:control-plane:modules:reliability')
    implementation project(':apps:control-plane:modules:authorization')
    implementation project(':apps:control-plane:modules:requirement-graph')
    implementation project(':apps:control-plane:modules:attachments-metadata')
    implementation project(':apps:control-plane:modules:actions-notifications')
    implementation project(':database:control-plane')
    implementation libs.spring.boot.jooq
    testImplementation testFixtures(project(':database:control-plane'))
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.jqwik
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
```

Add all four projects to the API runtime and to the worker runtime. Add these exact dependencies to `tests/contract/build.gradle`:

```groovy
testImplementation project(':apps:control-plane:modules:platform-kernel')
testImplementation project(':apps:control-plane:modules:requirement-graph')
```

Add these exact dependencies to each of `tests/integration/build.gradle`, `tests/api/build.gradle`, and `tests/security-negative/build.gradle`:

```groovy
testImplementation project(':apps:control-plane:api')
testImplementation project(':apps:control-plane:modules:requirement-graph')
testImplementation project(':apps:control-plane:modules:attachments-metadata')
testImplementation project(':apps:control-plane:modules:collaboration')
testImplementation project(':apps:control-plane:modules:actions-notifications')
testImplementation testFixtures(project(':database:control-plane'))
```

Add these exact dependencies to `tests/state-machine/build.gradle`:

```groovy
testImplementation project(':apps:control-plane:modules:requirement-graph')
testImplementation project(':apps:control-plane:modules:collaboration')
testImplementation testFixtures(project(':database:control-plane'))
```

Add these exact dependencies to `tests/fault-injection/build.gradle`:

```groovy
testImplementation project(':apps:control-plane:worker')
testImplementation project(':apps:control-plane:modules:reliability')
testImplementation project(':apps:control-plane:modules:requirement-graph')
testImplementation project(':apps:control-plane:modules:attachments-metadata')
testImplementation project(':apps:control-plane:modules:collaboration')
testImplementation project(':apps:control-plane:modules:actions-notifications')
testImplementation testFixtures(project(':database:control-plane'))
```

Do not use filesystem source-set sharing. Each of the four new module build files must also contain `testImplementation(testFixtures(project(":database:control-plane")))`; a shared suite that starts PostgreSQL must never replace the shared fixture with local role SQL.

Create the four `package-info.java` files with `@ApplicationModule`: Requirement Graph and Attachments Metadata allow `platformkernel`, `reliability`, and `authorization`; Actions/Notifications allows those three plus `identity` solely for `NotificationPreferenceQueryPort`; Collaboration additionally allows `requirement`, `attachment`, `action`, and Identity/Authorization's exported confirmation API. Identity declares no dependency on `action` or `collaboration`. Run `ApplicationModules.of(com.inforvans.accord.ControlApiApplication.class).verify()` after wiring, assert that `requirement`, `attachment`, `collaboration`, and `action` are each returned by `getModuleByName`, assert `action -> identity` and the absence of `identity -> action`, and treat an unrecognized dependency or cycle as a hard failure.

- [ ] **Step 4: Define the revision envelope and semantic payload schema**

The root schema must set `additionalProperties: false`, require `schema_version`, `requirement_id`, `revision_no`, `parent_revision_hash`, `revision_hash`, and `semantic_payload`, and define these stable enums:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schemas.accord.inforvans.com/requirement/requirement-revision/1-0-0",
  "type": "object",
  "additionalProperties": false,
  "required": ["schema_version", "requirement_id", "revision_no", "parent_revision_hash", "revision_hash", "semantic_payload"],
  "$defs": {
    "developmentView": {"type":"object","additionalProperties":false,"required":["impact_summary","affected_components","constraints"],"properties":{"impact_summary":{"type":"string"},"affected_components":{"type":"array","items":{"type":"string"}},"constraints":{"type":"array","items":{"type":"string"}}}},
    "acceptanceCriterion": {"type":"object","additionalProperties":false,"required":["criterion_id","statement","verification_method"],"properties":{"criterion_id":{"type":"string","pattern":"^AC-[A-Z0-9-]{3,64}$"},"statement":{"type":"string","minLength":1},"verification_method":{"type":"string","minLength":1}}},
    "acceptedUnknown": {"type":"object","additionalProperties":false,"required":["unknown_id","statement","accepted_reason"],"properties":{"unknown_id":{"type":"string","pattern":"^UNK-[A-Z0-9-]{3,64}$"},"statement":{"type":"string","minLength":1},"accepted_reason":{"type":"string","minLength":1}}},
    "semanticDecision": {"type":"object","additionalProperties":false,"required":["decision_id","question","decision","rationale"],"properties":{"decision_id":{"type":"string","pattern":"^DEC-[A-Z0-9-]{3,64}$"},"question":{"type":"string","minLength":1},"decision":{"type":"string","minLength":1},"rationale":{"type":"string","minLength":1}}}
  },
  "properties": {
    "schema_version": {"const": "1.0"},
    "requirement_id": {"type": "string", "format": "uuid"},
    "revision_no": {"type": "integer", "minimum": 1},
    "parent_revision_hash": {"type": ["string", "null"], "pattern": "^sha256:[0-9a-f]{64}$"},
    "revision_hash": {"type": "string", "pattern": "^sha256:[0-9a-f]{64}$"},
    "semantic_payload": {
      "type": "object",
      "additionalProperties": false,
      "required": ["business_domain", "title", "blocks", "relations", "attachments", "development_view", "acceptance_criteria", "accepted_unknowns", "semantic_decisions"],
      "properties": {
        "business_domain": {"type": "string", "minLength": 1, "maxLength": 128},
        "title": {"type": "string", "minLength": 1, "maxLength": 240},
        "blocks": {"type": "array", "minItems": 1, "items": {"$ref": "requirement-block.schema.json"}},
        "relations": {"type": "array", "items": {"$ref": "requirement-block.schema.json#/$defs/relation"}},
        "attachments": {"type": "array", "items": {"$ref": "attachment-binding.schema.json"}},
        "development_view": {"$ref": "#/$defs/developmentView"},
        "acceptance_criteria": {"type": "array", "minItems": 1, "items": {"$ref": "#/$defs/acceptanceCriterion"}},
        "accepted_unknowns": {"type": "array", "items": {"$ref": "#/$defs/acceptedUnknown"}},
        "semantic_decisions": {"type": "array", "items": {"$ref": "#/$defs/semanticDecision"}}
      }
    }
  }
}
```

`requirement-block.schema.json` is a discriminator-backed closed union, never a `category + body` bag. Its top-level `oneOf` contains the seven standard branches below plus the separately identified `extension` wrapper. Every standard branch combines `$defs/base` with its own required properties and sets `unevaluatedProperties: false`; every nested object sets `additionalProperties: false`. Use these exact semantic fields:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schemas.accord.inforvans.com/requirement/requirement-block/1-0-0",
  "oneOf": [
    {"$ref": "#/$defs/userScenario"},
    {"$ref": "#/$defs/businessRule"},
    {"$ref": "#/$defs/workflowState"},
    {"$ref": "#/$defs/dataRequirement"},
    {"$ref": "#/$defs/permissionRequirement"},
    {"$ref": "#/$defs/reportNotification"},
    {"$ref": "#/$defs/nonFunctional"},
    {"$ref": "#/$defs/registeredExtension"}
  ],
  "$defs": {
    "attachmentRef": {
      "type": "object",
      "additionalProperties": false,
      "required": ["attachment_id", "version", "content_hash", "binding_type"],
      "properties": {
        "attachment_id": {"type": "string", "format": "uuid"},
        "version": {"type": "integer", "minimum": 1},
        "content_hash": {"type": "string", "pattern": "^sha256:[0-9a-f]{64}$"},
        "binding_type": {"enum": ["contractual", "reference"]}
      }
    },
    "base": {
      "type": "object",
      "required": ["block_id", "block_type", "title", "summary", "expected_effect", "attachment_refs"],
      "properties": {
        "block_id": {"type": "string", "pattern": "^RB-[A-Z0-9-]{3,64}$"},
        "block_type": {"type": "string"},
        "title": {"type": "string", "minLength": 1, "maxLength": 240},
        "summary": {"type": "string", "minLength": 1, "maxLength": 2000},
        "expected_effect": {"type": "string", "minLength": 1, "maxLength": 2000},
        "attachment_refs": {"type": "array", "items": {"$ref": "#/$defs/attachmentRef"}}
      }
    },
    "userScenario": {
      "allOf": [
        {"$ref": "#/$defs/base"},
        {
          "type": "object",
          "required": ["target_users", "preconditions", "trigger", "steps", "expected_outcome", "exception_paths"],
          "properties": {
            "block_type": {"const": "user_scenario"},
            "target_users": {"type": "array", "minItems": 1, "items": {"type": "string", "minLength": 1}},
            "preconditions": {"type": "array", "items": {"type": "string", "minLength": 1}},
            "trigger": {"type": "string", "minLength": 1},
            "steps": {"type": "array", "minItems": 1, "items": {"type": "string", "minLength": 1}},
            "expected_outcome": {"type": "string", "minLength": 1},
            "exception_paths": {"type": "array", "items": {"type": "string", "minLength": 1}}
          }
        }
      ],
      "unevaluatedProperties": false
    },
    "businessRule": {
      "allOf": [
        {"$ref": "#/$defs/base"},
        {
          "type": "object",
          "required": ["rule_kind", "rule_statement", "conditions", "outcome", "boundary_cases", "examples"],
          "properties": {
            "block_type": {"const": "business_rule"},
            "rule_kind": {"enum": ["constraint", "calculation", "eligibility", "boundary"]},
            "rule_statement": {"type": "string", "minLength": 1},
            "conditions": {"type": "array", "items": {"type": "string", "minLength": 1}},
            "outcome": {"type": "string", "minLength": 1},
            "boundary_cases": {"type": "array", "items": {"type": "string", "minLength": 1}},
            "examples": {"type": "array", "items": {"type": "string", "minLength": 1}}
          }
        }
      ],
      "unevaluatedProperties": false
    },
    "state": {
      "type": "object",
      "additionalProperties": false,
      "required": ["state_id", "label", "meaning", "terminal"],
      "properties": {
        "state_id": {"type": "string", "minLength": 1},
        "label": {"type": "string", "minLength": 1},
        "meaning": {"type": "string", "minLength": 1},
        "terminal": {"type": "boolean"}
      }
    },
    "transition": {
      "type": "object",
      "additionalProperties": false,
      "required": ["transition_id", "from_state", "to_state", "trigger", "guard", "success_behavior", "failure_behavior"],
      "properties": {
        "transition_id": {"type": "string", "minLength": 1},
        "from_state": {"type": "string", "minLength": 1},
        "to_state": {"type": "string", "minLength": 1},
        "trigger": {"type": "string", "minLength": 1},
        "guard": {"type": ["string", "null"]},
        "success_behavior": {"type": "string", "minLength": 1},
        "failure_behavior": {"type": "string", "minLength": 1}
      }
    },
    "workflowState": {
      "allOf": [
        {"$ref": "#/$defs/base"},
        {
          "type": "object",
          "required": ["workflow_name", "initial_state", "states", "transitions"],
          "properties": {
            "block_type": {"const": "workflow_state"},
            "workflow_name": {"type": "string", "minLength": 1},
            "initial_state": {"type": "string", "minLength": 1},
            "states": {"type": "array", "minItems": 1, "items": {"$ref": "#/$defs/state"}},
            "transitions": {"type": "array", "items": {"$ref": "#/$defs/transition"}}
          }
        }
      ],
      "unevaluatedProperties": false
    },
    "dataField": {
      "type": "object",
      "additionalProperties": false,
      "required": ["field_id", "name", "business_meaning", "data_type", "required", "source", "classification"],
      "properties": {
        "field_id": {"type": "string", "minLength": 1},
        "name": {"type": "string", "minLength": 1},
        "business_meaning": {"type": "string", "minLength": 1},
        "data_type": {"type": "string", "minLength": 1},
        "required": {"type": "boolean"},
        "source": {"type": "string", "minLength": 1},
        "classification": {"enum": ["public", "internal", "confidential", "restricted"]}
      }
    },
    "dataRequirement": {
      "allOf": [
        {"$ref": "#/$defs/base"},
        {
          "type": "object",
          "required": ["subject", "fields", "retention_requirement", "consistency_requirement"],
          "properties": {
            "block_type": {"const": "data"},
            "subject": {"type": "string", "minLength": 1},
            "fields": {"type": "array", "minItems": 1, "items": {"$ref": "#/$defs/dataField"}},
            "retention_requirement": {"type": "string", "minLength": 1},
            "consistency_requirement": {"type": "string", "minLength": 1}
          }
        }
      ],
      "unevaluatedProperties": false
    },
    "permissionRequirement": {
      "allOf": [
        {"$ref": "#/$defs/base"},
        {
          "type": "object",
          "required": ["actors", "resource", "actions", "effect", "conditions", "segregation_requirements"],
          "properties": {
            "block_type": {"const": "permission"},
            "actors": {"type": "array", "minItems": 1, "items": {"type": "string", "minLength": 1}},
            "resource": {"type": "string", "minLength": 1},
            "actions": {"type": "array", "minItems": 1, "items": {"type": "string", "minLength": 1}},
            "effect": {"enum": ["allow", "deny"]},
            "conditions": {"type": "array", "items": {"type": "string", "minLength": 1}},
            "segregation_requirements": {"type": "array", "items": {"type": "string", "minLength": 1}}
          }
        }
      ],
      "unevaluatedProperties": false
    },
    "reportNotification": {
      "allOf": [
        {"$ref": "#/$defs/base"},
        {
          "type": "object",
          "required": ["artifact_kind", "trigger", "recipients", "channel", "content_requirements", "timing", "escalation"],
          "properties": {
            "block_type": {"const": "report_notification"},
            "artifact_kind": {"enum": ["report", "notification", "reminder", "escalation"]},
            "trigger": {"type": "string", "minLength": 1},
            "recipients": {"type": "array", "minItems": 1, "items": {"type": "string", "minLength": 1}},
            "channel": {"type": "string", "minLength": 1},
            "content_requirements": {"type": "array", "items": {"type": "string", "minLength": 1}},
            "timing": {"type": "string", "minLength": 1},
            "escalation": {"type": ["string", "null"]}
          }
        }
      ],
      "unevaluatedProperties": false
    },
    "nonFunctional": {
      "allOf": [
        {"$ref": "#/$defs/base"},
        {
          "type": "object",
          "required": ["quality_attribute", "scope", "metric", "target", "measurement_method", "operating_conditions", "degradation_behavior"],
          "properties": {
            "block_type": {"const": "non_functional"},
            "quality_attribute": {"enum": ["performance", "security", "availability", "compatibility", "migration", "accessibility", "operability"]},
            "scope": {"type": "string", "minLength": 1},
            "metric": {"type": "string", "minLength": 1},
            "target": {"type": "string", "minLength": 1},
            "measurement_method": {"type": "string", "minLength": 1},
            "operating_conditions": {"type": "string", "minLength": 1},
            "degradation_behavior": {"type": "string", "minLength": 1}
          }
        }
      ],
      "unevaluatedProperties": false
    },
    "registeredExtension": {
      "allOf": [
        {"$ref": "#/$defs/base"},
        {
          "type": "object",
          "required": ["namespace", "extension_type", "extension_schema_uri", "extension_schema_version", "extension_payload"],
          "properties": {
            "block_type": {"const": "extension"},
            "namespace": {"type": "string", "pattern": "^[a-z][a-z0-9.-]{2,127}$"},
            "extension_type": {"type": "string", "pattern": "^[a-z][a-z0-9_]{1,63}$"},
            "extension_schema_uri": {"type": "string", "format": "uri"},
            "extension_schema_version": {"type": "string", "pattern": "^[0-9]+\\.[0-9]+\\.[0-9]+$"},
            "extension_payload": {"type": "object"}
          }
        }
      ],
      "unevaluatedProperties": false
    },
    "relationEndpoint": {
      "oneOf": [
        {
          "type": "object",
          "required": ["node_kind", "requirement_id", "revision_no"],
          "properties": {
            "node_kind": {"const": "requirement"},
            "requirement_id": {"type": "string", "format": "uuid"},
            "revision_no": {"type": "integer", "minimum": 1},
            "block_id": false
          },
          "unevaluatedProperties": false
        },
        {
          "type": "object",
          "required": ["node_kind", "requirement_id", "revision_no", "block_id"],
          "properties": {
            "node_kind": {"const": "block"},
            "requirement_id": {"type": "string", "format": "uuid"},
            "revision_no": {"type": "integer", "minimum": 1},
            "block_id": {"type": "string", "pattern": "^RB-[A-Z0-9-]{3,64}$"}
          },
          "unevaluatedProperties": false
        }
      ]
    },
    "relation": {
      "type": "object",
      "additionalProperties": false,
      "required": ["relation_id", "source", "relation_type", "target", "rationale"],
      "properties": {
        "relation_id": {"type": "string", "pattern": "^RR-[A-Z0-9-]{3,64}$"},
        "source": {"$ref": "#/$defs/relationEndpoint"},
        "relation_type": {"enum": ["precedes", "depends_on", "triggers", "constrains", "affects", "conflicts_with", "supersedes", "split_from", "relates_to"]},
        "target": {"$ref": "#/$defs/relationEndpoint"},
        "rationale": {"type": "string", "minLength": 1, "maxLength": 2000}
      }
    }
  }
}
```

The standard branch schemas are the canonical business vocabulary. An `extension` wrapper is accepted only after the project has pinned an immutable registry entry whose namespace/type/URI/version exactly match and whose schema digest is part of project configuration; the server validates `extension_payload` against that schema before canonicalization. Unknown or revoked registry entries fail closed. Add contract vectors that omit one required field, add one unknown field, use a mismatched discriminator, reference an unregistered extension, and exploit an external `$ref`; all must fail. The schema loader permits only pinned local/cached schema digests and never fetches an attacker-controlled URI at validation time.

- [ ] **Step 5: Add relational constraints and immutable canonical bytes**

Create tables `requirement`, `requirement_revision`, `requirement_block`, `requirement_relation`, `requirement_projection`, and `semantic_decision`. Every key starts with `tenant_id`; every aggregate row has `aggregate_version bigint not null`. Store `canonical_payload bytea not null`, `revision_hash char(71) not null`, and enforce:

```sql
create unique index uq_requirement_revision_no
  on requirement_revision(tenant_id, requirement_id, revision_no);
create unique index uq_requirement_revision_hash
  on requirement_revision(tenant_id, requirement_id, revision_hash);
alter table requirement_revision add constraint ck_revision_hash
  check (revision_hash ~ '^sha256:[0-9a-f]{64}$');
alter table requirement_relation add constraint ck_no_self_relation
  check ((source_requirement_id, source_revision_no, source_block_id)
         is distinct from
         (target_requirement_id, target_revision_no, target_block_id));

SELECT accord_security.enforce_tenant_table('public.requirement'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_revision'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_block'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_relation'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_projection'::regclass);
SELECT accord_security.enforce_tenant_table('public.semantic_decision'::regclass);
```

These six names are the complete set of `public` tables with `tenant_id` created by V020. Use composite primary/unique keys and foreign keys beginning with `tenant_id`. Each relation endpoint first has a required `(tenant_id, requirement_id, revision_no)` foreign key and then an optional `(tenant_id, requirement_id, revision_no, block_id)` foreign key; a check requires `block_id` exactly when `node_kind=block`. This permits exact-version cross-Requirement relations without permitting a cross-tenant edge or a block from another Revision. Execute the six calls after all tables, indexes, checks, and foreign keys exist and before any `GRANT SELECT, INSERT, UPDATE, DELETE` to `accord_api` or `accord_worker`; do not duplicate the policy DDL in V020.

Create `RequirementMigrationIT.java` as a Testcontainers PostgreSQL 17.5 test. Its setup must use this ordering, with no test-local role creation or post-migration grants:

```java
postgres.start();
ControlPlaneTestRoles.bootstrap(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
Flyway.configure()
    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
    .target("020")
    .load()
    .migrate();
```

Seed colliding aggregate and child IDs for tenants A and B through the migrator. Through an `accord_api` connection using transaction-local `app.tenant_id`, assert SELECT, INSERT, UPDATE, and DELETE cannot observe or affect the other tenant for each of the six V020 tables, and assert a missing tenant setting fails closed. Query `pg_constraint` to prove `requirement_revision -> requirement`, `requirement_block -> requirement_revision`, both relation endpoints' Revision and optional block foreign keys, `requirement_projection -> requirement_revision`, and `semantic_decision -> requirement_revision` use composite keys containing `tenant_id`; attempt tenant-A relation endpoints pointing at a tenant-B Requirement, a wrong Revision, and a block outside the named Revision and require foreign-key rejection. Also prove a requirement endpoint rejects `block_id`, a block endpoint requires it, and a self-edge is rejected. Finally query the catalogs for all six tables and assert RLS is enabled and forced and `tenant_isolation` is `FOR ALL TO PUBLIC` with exact `USING` and `WITH CHECK` expressions `(tenant_id = accord_security.current_tenant_id())`.

- [ ] **Step 6: Run migration, schema, and golden-vector tests**

Run:

```bash
./gradlew :tests:contract:test :tests:integration:test --tests '*Requirement*' --tests '*RequirementMigrationIT'
node --test tests/contract/node/requirement-canonical-bytes.test.mjs
./gradlew :cmd:accordctl:test --tests '*RequirementCanonicalBytesIndependentTest'
./gradlew :apps:control-plane:api:test --tests '*ModuleBoundaryTest'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; Foundation/primary-Java/CLI-Java/Node independently reproduce the checked-in exact JCS bytes and `.sha256` without importing the illustrative normalizer; changing Appendix A's illustrative digest has no effect; the Testcontainers PostgreSQL log shows role bootstrap before migration through `V020`; tenant A cannot read or mutate tenant B; no cross-tenant foreign key can be inserted; all six tables satisfy the global exact forced-RLS catalog contract; and every control-plane fixture bootstraps roles before Flyway.

- [ ] **Step 7: Commit the contract boundary**

```bash
git add settings.gradle apps/control-plane/api/build.gradle apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java apps/control-plane/worker/build.gradle apps/control-plane/modules/requirement-graph apps/control-plane/modules/attachments-metadata apps/control-plane/modules/collaboration apps/control-plane/modules/actions-notifications contracts/json-schema/requirement contracts/events/requirement contracts/golden-fixtures/requirement-contract contracts/golden-fixtures/requirement database/control-plane/migrations/V020__requirement_graph.sql tests/contract tests/integration/build.gradle tests/integration/src/test/java/com/inforvans/accord/integration/RequirementMigrationIT.java tests/api/build.gradle tests/security-negative/build.gradle tests/state-machine/build.gradle tests/fault-injection/build.gradle
git commit -m "feat(requirements): define canonical graph contracts"
```

### Task 2: Implement Canonicalization And Revision Aggregate Rules

**Files:**
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/domain/RequirementModels.java`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/domain/RevisionCanonicalizer.java`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/domain/RequirementAggregate.java`
- Test: `apps/control-plane/modules/requirement-graph/src/test/java/com/inforvans/accord/requirement/RequirementCanonicalizationTest.java`
- Test: `apps/control-plane/modules/requirement-graph/src/test/java/com/inforvans/accord/requirement/RequirementPropertyTest.java`

- [ ] **Step 1: Write tests for deterministic hashes and semantic/non-semantic changes**

```java
@Property
void mapInsertionOrderNeverChangesRevisionHash(
        @ForAll("semanticPayloads") SemanticPayload payload) {
    var reorderedBlocks = new ArrayList<>(payload.blocks());
    Collections.shuffle(reorderedBlocks, new Random(7));
    var reordered = payload.withBlocks(List.copyOf(reorderedBlocks));

    assertThat(RevisionCanonicalizer.hash(payload.normalized()))
        .isEqualTo(RevisionCanonicalizer.hash(reordered.normalized()));
}

@Test
void referenceAttachmentAdditionPreservesCurrentRevisionHash() {
    aggregate.attachReference(existingAvailableAttachment);

    assertThat(aggregate.currentRevisionHash()).isEqualTo(originalHash);
    assertThat(aggregate.displayVersion()).isEqualTo(originalDisplayVersion + 1);
}
```

- [ ] **Step 2: Run the tests and observe missing domain types**

Run: `./gradlew :apps:control-plane:modules:requirement-graph:test --tests '*RequirementCanonicalizationTest' --tests '*RequirementPropertyTest'`

Expected: compilation fails because `SemanticPayload` and `RevisionCanonicalizer` do not exist.

- [ ] **Step 3: Add explicit domain types and normalization order**

```java
public final class RequirementModels {
    private RequirementModels() {}

    public enum BlockType { USER_SCENARIO, BUSINESS_RULE, WORKFLOW_STATE, DATA, PERMISSION, REPORT_NOTIFICATION, NON_FUNCTIONAL, EXTENSION }
    public enum RelationType { PRECEDES, DEPENDS_ON, TRIGGERS, CONSTRAINS, AFFECTS, CONFLICTS_WITH, SUPERSEDES, SPLIT_FROM, RELATES_TO }
    public enum RevisionPhase { DRAFT, UNDER_REVIEW, AWAITING_DEVELOPMENT_CONFIRMATION, AWAITING_BUSINESS_CONFIRMATION, BILATERALLY_CONFIRMED, WITHDRAWN, SUPERSEDED }
    public enum RuntimeValidity { ACTIVE, HELD, SUPERSEDED }
    public enum BusinessRuleKind { CONSTRAINT, CALCULATION, ELIGIBILITY, BOUNDARY }
    public enum DataClassification { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }
    public enum PermissionEffect { ALLOW, DENY }
    public enum CommunicationArtifactKind { REPORT, NOTIFICATION, REMINDER, ESCALATION }
    public enum QualityAttribute { PERFORMANCE, SECURITY, AVAILABILITY, COMPATIBILITY, MIGRATION, ACCESSIBILITY, OPERABILITY }

    public record RevisionIdentity(UUID requirementId, int revisionNo, Digest revisionHash) {}
    public record BlockAttachmentRef(UUID attachmentId, int version, Digest contentHash,
                                     AttachmentBindingType bindingType) {}

    public sealed interface RequirementBlock permits UserScenarioBlock, BusinessRuleBlock,
            WorkflowStateBlock, DataRequirementBlock, PermissionRequirementBlock,
            ReportNotificationBlock, NonFunctionalBlock, RegisteredExtensionBlock {
        String id();
        BlockType type();
        String title();
        String summary();
        String expectedEffect();
        List<BlockAttachmentRef> attachmentRefs();
    }

    public record UserScenarioBlock(
            String id, String title, String summary, String expectedEffect,
            List<BlockAttachmentRef> attachmentRefs, List<String> targetUsers,
            List<String> preconditions, String trigger, List<String> steps,
            String expectedOutcome, List<String> exceptionPaths) implements RequirementBlock {
        @Override public BlockType type() { return BlockType.USER_SCENARIO; }
    }

    public record BusinessRuleBlock(
            String id, String title, String summary, String expectedEffect,
            List<BlockAttachmentRef> attachmentRefs, BusinessRuleKind ruleKind,
            String ruleStatement, List<String> conditions, String outcome,
            List<String> boundaryCases, List<String> examples) implements RequirementBlock {
        @Override public BlockType type() { return BlockType.BUSINESS_RULE; }
    }

    public record StateDescriptor(String stateId, String label, String meaning, boolean terminal) {}
    public record TransitionDescriptor(String transitionId, String fromState, String toState,
                                       String trigger, String guard, String successBehavior,
                                       String failureBehavior) {}
    public record WorkflowStateBlock(
            String id, String title, String summary, String expectedEffect,
            List<BlockAttachmentRef> attachmentRefs, String workflowName, String initialState,
            List<StateDescriptor> states, List<TransitionDescriptor> transitions)
            implements RequirementBlock {
        @Override public BlockType type() { return BlockType.WORKFLOW_STATE; }
    }

    public record DataFieldDescriptor(String fieldId, String name, String businessMeaning,
                                      String dataType, boolean required, String source,
                                      DataClassification classification) {}
    public record DataRequirementBlock(
            String id, String title, String summary, String expectedEffect,
            List<BlockAttachmentRef> attachmentRefs, String subject,
            List<DataFieldDescriptor> fields, String retentionRequirement,
            String consistencyRequirement) implements RequirementBlock {
        @Override public BlockType type() { return BlockType.DATA; }
    }

    public record PermissionRequirementBlock(
            String id, String title, String summary, String expectedEffect,
            List<BlockAttachmentRef> attachmentRefs, List<String> actors, String resource,
            List<String> actions, PermissionEffect effect, List<String> conditions,
            List<String> segregationRequirements) implements RequirementBlock {
        @Override public BlockType type() { return BlockType.PERMISSION; }
    }

    public record ReportNotificationBlock(
            String id, String title, String summary, String expectedEffect,
            List<BlockAttachmentRef> attachmentRefs, CommunicationArtifactKind artifactKind,
            String trigger, List<String> recipients, String channel,
            List<String> contentRequirements, String timing, String escalation)
            implements RequirementBlock {
        @Override public BlockType type() { return BlockType.REPORT_NOTIFICATION; }
    }

    public record NonFunctionalBlock(
            String id, String title, String summary, String expectedEffect,
            List<BlockAttachmentRef> attachmentRefs, QualityAttribute qualityAttribute,
            String scope, String metric, String target, String measurementMethod,
            String operatingConditions, String degradationBehavior) implements RequirementBlock {
        @Override public BlockType type() { return BlockType.NON_FUNCTIONAL; }
    }

    public record RegisteredExtensionBlock(
            String id, String title, String summary, String expectedEffect,
            List<BlockAttachmentRef> attachmentRefs, String namespace, String extensionType,
            URI extensionSchemaUri, String extensionSchemaVersion, JsonNode extensionPayload)
            implements RequirementBlock {
        @Override public BlockType type() { return BlockType.EXTENSION; }
    }

    public sealed interface RequirementNodeRef permits RequirementRootRef, RequirementBlockRef {
        UUID requirementId();
        int revisionNo();
    }
    public record RequirementRootRef(UUID requirementId, int revisionNo)
            implements RequirementNodeRef {}
    public record RequirementBlockRef(UUID requirementId, int revisionNo, String blockId)
            implements RequirementNodeRef {}
    public record RequirementRelation(String relationId, RequirementNodeRef source,
                                      RelationType type, RequirementNodeRef target,
                                      String rationale) {}
    public record SemanticPayload(
            String businessDomain, String title, List<RequirementBlock> blocks,
            List<RequirementRelation> relations, List<ContractualAttachmentBinding> attachments,
            DevelopmentView developmentView, List<AcceptanceCriterion> acceptanceCriteria,
            List<AcceptedUnknown> acceptedUnknowns, List<SemanticDecision> semanticDecisions) {
        public SemanticPayload withBlocks(List<RequirementBlock> replacement) {
            return new SemanticPayload(businessDomain, title, List.copyOf(replacement), relations,
                attachments, developmentView, acceptanceCriteria, acceptedUnknowns, semanticDecisions);
        }
    }
}
```

Jackson serialization is discriminator-bound to `block_type`; there is no public constructor that accepts a standard block as `JsonNode`. Relation endpoints are discriminator-bound to `requirement` or `block` and always name an exact Requirement Revision; `block` additionally names a block that exists in that Revision. Validate state IDs, transition endpoints, duplicate field IDs, permission actions, relation endpoint visibility/existence, attachment refs, and registered extension schema identity before canonicalization. Normalize arrays whose ordering has no semantic meaning by stable ID; preserve explicitly ordered scenario steps and workflow transitions through their source order. Feed the exact envelope to the foundation JCS implementation and prefix the lowercase digest with `sha256:`.

`AppendixAIllustrativeNormalizer` exists only in the fixture/test import boundary and is not accepted by a public production endpoint. It loads the pinned mapping version, walks every illustrative object with a consumed-pointer set, rejects an unknown or multiply consumed pointer, requires a one-to-one block alias table, and validates that every mapped Requirement ID is the same UUID before building typed domain objects. It then serializes the typed canonical envelope, validates the closed production schema, derives RFC 8785/JCS bytes, and computes the canonical digest. Tests run the same input repeatedly and with randomized source-object member order, require byte-identical canonical output and digest, and reject unknown members, duplicate mapping targets, ambiguous relation aliases, mixed `REQ-*`/UUID identities, missing constants, and mapping-version drift. Appendix A's own `revision_hash` is retained only as ignored illustrative source data and is never presented as verified evidence.

- [ ] **Step 4: Implement transition guards as commands, never writable status fields**

```java
public sealed interface RequirementCommand permits SubmitForReview, SupersedeRevision {
    TenantId tenantId();
    long expectedVersion();
    UUID idempotencyKey();
}

public record SubmitForReview(TenantId tenantId, UUID requirementId, long expectedVersion,
                              UUID idempotencyKey) implements RequirementCommand {}
public record SupersedeRevision(TenantId tenantId, RevisionIdentity current,
                                SemanticPayload nextPayload, long expectedVersion,
                                UUID idempotencyKey) implements RequirementCommand {}
```

`SupersedeRevision` must atomically mark the prior revision `SUPERSEDED`, create `revision_no + 1` with `parent_revision_hash`, supersede version-bound ActionRequests, append audit/domain events, and write the outbox result.

- [ ] **Step 5: Run module and mutation tests**

Run: `./gradlew :apps:control-plane:modules:requirement-graph:test :apps:control-plane:modules:requirement-graph:pitest`

Expected: tests pass and the mutation score for canonicalization and phase guards is at least 90%.

- [ ] **Step 6: Commit the revision kernel**

```bash
git add apps/control-plane/modules/requirement-graph
git commit -m "feat(requirements): add immutable revision aggregate"
```

### Task 3: Persist Commands With CAS, Idempotency, And Rebuildable Projections

**Files:**
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/application/RequirementCommandService.java`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/application/RequirementQueryService.java`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/infrastructure/JooqRequirementRepository.java`
- Test: `apps/control-plane/modules/requirement-graph/src/test/java/com/inforvans/accord/requirement/RequirementConcurrencyTest.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/RequirementTenantIsolationTest.java`

- [ ] **Step 1: Add concurrent update and retry tests**

```java
@Test
void onlyOneWriterWinsTheSameExpectedVersion() throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
        var first = CompletableFuture.supplyAsync(
            () -> updateAfter(start, command(4, UUID.randomUUID())), executor);
        var second = CompletableFuture.supplyAsync(
            () -> updateAfter(start, command(4, UUID.randomUUID())), executor);
        start.countDown();
        var results = List.of(first.get(), second.get());

        assertThat(results).filteredOn(Updated.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(VersionConflict.class::isInstance).hasSize(1);
    }
}

@Test
void sameIdempotencyKeyReturnsByteIdenticalResponse() {
    var first = service.update(command(currentVersion, key));
    var replay = service.update(command(currentVersion, key));

    assertThat(serialize(replay)).containsExactly(serialize(first));
    assertThat(outbox.countFor(key)).isOne();
}
```

- [ ] **Step 2: Verify both tests fail against the unimplemented repository**

Run: `./gradlew :apps:control-plane:modules:requirement-graph:test --tests '*RequirementConcurrencyTest'`

Expected: compilation fails for `JooqRequirementRepository`.

- [ ] **Step 3: Implement one-transaction command execution**

The repository update must use `where tenant_id = ? and requirement_id = ? and aggregate_version = ?`, check exactly one affected row, insert ordered domain/audit events, insert the outbox row, and persist the serialized HTTP result in `idempotency_result` before commit. Reuse the Foundation `CommandKey`; only a duplicate `(tenant_id, actor_id, route_key, idempotency_key)` with the same request digest returns the stored result without re-running policy logic. A changed digest is a conflict, while the same key on another route is independent.

```java
public interface RequirementRepository {
    RequirementAggregate loadForUpdate(TenantScope scope, UUID id);
    SaveOutcome save(TenantScope scope, RequirementAggregate aggregate,
                     long expectedVersion, CommandResult result);

    sealed interface SaveOutcome permits Saved, Conflict {}
    record Saved(long version, List<DomainEvent> events) implements SaveOutcome {}
    record Conflict(long actualVersion, Digest actualRevisionHash) implements SaveOutcome {}
}
```

- [ ] **Step 4: Add graph, list, detail, and version-diff projections**

Projection consumers key events by `(tenant_id, aggregate_type, aggregate_id, sequence)`, reject sequence gaps, and rebuild from canonical rows plus audit events. Search uses PostgreSQL FTS and `pg_trgm`; graph edges remain adjacency rows, not a second graph database.

- [ ] **Step 5: Run concurrency, projection rebuild, and tenant-isolation tests**

Run: `./gradlew :apps:control-plane:modules:requirement-graph:test :tests:security-negative:test --tests '*Requirement*'`

Expected: all tests pass; 100 concurrent attempts produce one version 5 row, 99 HTTP 409-equivalent results, and one outbox event.

- [ ] **Step 6: Commit transactional persistence**

```bash
git add apps/control-plane/modules/requirement-graph tests/security-negative/src/test/java/com/inforvans/accord/security/RequirementTenantIsolationTest.java
git commit -m "feat(requirements): persist commands with cas and projections"
```

### Task 4: Build Server-Backed Intake Drafts And Structured Submission

**Files:**
- Create: `database/control-plane/migrations/V021__requirement_intake_drafts.sql`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/application/RequirementPorts.java`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/application/IntakeDraftService.java`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/application/TemporaryAudioDeletionService.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/worker/TemporaryAudioDeletionWorker.java`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/api/RequirementController.java`
- Test: `apps/control-plane/modules/requirement-graph/src/test/java/com/inforvans/accord/requirement/IntakeDraftServiceTest.java`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/IntakeDraftApiTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/RequirementIntakeDraftMigrationIT.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/TemporaryAudioDeletionRecoveryTest.java`
- Verify: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/TenantRlsTest.java`
- Verify: `tests/architecture/verify-control-plane-fixtures.ps1`

- [ ] **Step 1: Write tests for draft recovery, transcription confirmation, and stale extraction**

```java
@Test
void refreshedRouteResumesTheEncryptedServerDraft() {
    var draft = service.save(scope, actor, new SaveDraft("订单取消", 0));

    assertThat(service.get(scope, actor, draft.id()).text()).isEqualTo("订单取消");
}

@Test
void extractionForAnOldDraftVersionCannotCreateARevision() {
    assertThat(service.applyExtraction(scope, draftId, 2, 3))
        .isInstanceOf(StaleExtraction.class);
}

@Test
void editingBusinessOwnedFieldCreatesAnExplicitChildRevision() {
    var draft = service.create(businessEditor,
        new ReviseExisting(current.identity(), current.aggregateVersion()));
    assertThat(draft.form()).isEqualTo(businessOwnedFormOf(current));

    var child = service.submit(businessEditor, draft.id(), true);
    assertThat(child.revisionNo()).isEqualTo(current.revisionNo() + 1);
    assertThat(child.parentRevisionHash()).isEqualTo(current.revisionHash());
    assertThat(child.developmentView()).isEqualTo(current.developmentView());
    assertThat(child.developmentViewValidity()).isEqualTo(RuntimeValidity.HELD);
}

@Test
void submitCommitsStructuredRevisionWhileAudioRemainsDeletionPending() {
    var receipt = service.submit(speechDraft, DELETE_AFTER_SUBMIT, 4);

    assertThat(receipt.audioDisposition().state()).isEqualTo(DELETION_PENDING);
    assertThat(revisionRepository.get(receipt.revision()).semanticPayload())
        .isEqualTo(confirmedStructuredText);
    assertThat(provider.versionDeleteCalls()).isZero();
}

@Test
void audioReachesDeletedOnlyAfterReceiptAndAbsenceReadBack() {
    var operation = pendingAudioDeletion();
    worker.run(operation.id());

    assertThat(providerDeleteReceipt(operation.id()).objectDigest())
        .isEqualTo(operation.sourceDigest());
    assertThat(provider.readExactVersion(operation.objectVersion())).isEqualTo(NOT_FOUND);
    assertThat(audioDisposition(operation.id()).state()).isEqualTo(DELETED);
}
```

- [ ] **Step 2: Run and verify the missing service failure**

Run: `./gradlew :apps:control-plane:modules:requirement-graph:test --tests '*IntakeDraftServiceTest'`

Expected: compilation fails for `IntakeDraftService`.

- [ ] **Step 3: Define the Agent extraction port without model leakage**

```java
public interface RequirementExtractionPort {
    AgentJobRef submit(ExtractionRequest request);

    record ExtractionRequest(
        TenantId tenantId,
        ProjectId projectId,
        UUID draftId,
        long draftVersion,
        Digest businessGraphSnapshotDigest,
        List<AttachmentVersionRef> authorizedAttachmentVersions,
        String locale
    ) {}
}
```

The port sends attachment IDs and grants, not reusable URLs. Persist user text, edited transcript, attachment refs, classification hints, draft intent, exact optional base Revision identity, agent job ID, and extraction version. Encrypt draft bodies with tenant data keys and expire abandoned drafts using project policy.

V021 creates exactly four `public` tenant tables: `requirement_intake_draft` for the versioned encrypted draft, `CREATE_REQUIREMENT | REVISE_EXISTING` intent, nullable all-or-none base Requirement/Revision/hash, attachment-reference snapshot, classification hints, current job ID, and expiry; `requirement_intake_extraction` for immutable extraction attempts keyed by draft and extraction version; `requirement_intake_transcription` for each immutable upload/transcription attempt with `transcription_id`, draft/upload IDs, provider bucket/key/version, source digest/length/media type, agent job ID/version, state `UPLOADING | PROCESSING | READY | FAILED`, encrypted transcript ciphertext/key version, transcript hash/language, failure code, expiry, and timestamps; and `temporary_audio_deletion` for the exact transcription/provider bucket/key/version/source digest, state, attempt count, next-attempt time, provider request/receipt digests, read-back result, nullable `action_request_id`, terminal failure code, and timestamps. The transcription table keeps historical attempts; `requirement_intake_draft.current_transcription_id` is only a composite FK-backed head, never the history itself. Deletion state is exactly `DELETION_PENDING | DELETED | DELETION_FAILED`; explicit retention is represented by the immutable reference Attachment and receipt state `RETAINED_AS_REFERENCE`, never a deletion row.

Use primary/unique keys beginning with `tenant_id`, a composite `(tenant_id, project_id)` foreign key from draft to project, a composite `(tenant_id, requirement_id, base_revision_no, base_revision_hash)` foreign key for `REVISE_EXISTING`, composite `(tenant_id, draft_id)` FKs from extraction and transcription to draft, a composite nullable draft head FK to transcription, and composite deletion FKs to the exact draft/transcription. Because `action_request` is not created until V023, V021 stores nullable `(action_request_tenant_id, action_request_id)` with an all-null/all-present check and no FK; V023 adds the composite tenant FK only after creating `action_request`. A check constraint requires all base fields absent for `CREATE_REQUIREMENT` and all present for `REVISE_EXISTING`. After all four tables and constraints exist, and before runtime DML grants, execute these individual statements:

```sql
SELECT accord_security.enforce_tenant_table('public.requirement_intake_draft'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_intake_extraction'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_intake_transcription'::regclass);
SELECT accord_security.enforce_tenant_table('public.temporary_audio_deletion'::regclass);
```

Create `RequirementIntakeDraftMigrationIT.java` with PostgreSQL 17.5. Immediately after `postgres.start()`, call `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)` and only then call `Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).target("021").load().migrate()`. Seed colliding draft, extraction, transcription, and deletion IDs for tenants A and B through the migrator; use the `accord_api` role plus transaction-local `app.tenant_id` to prove each table hides tenant B from tenant A and rejects tenant-A writes, updates, or deletes aimed at tenant B. Assert missing tenant context sees no rows. Query `pg_constraint` to verify every V021 composite tenant FK; reject an extraction/transcription whose tenant-A key names a tenant-B draft, a revise draft whose tenant-A base names a tenant-B Revision or wrong hash, a draft head naming another draft's transcription, and a deletion row naming another tenant's draft/transcription. Assert V021 has no FK to the not-yet-created `action_request`. Query `pg_class`/`pg_policy` for all four tables and require exact `ENABLE`, `FORCE`, `FOR ALL TO PUBLIC`, `USING`, and `WITH CHECK` catalog values.

- [ ] **Step 4: Expose idempotent draft and submit commands**

Add `POST /v1/projects/{projectId}/requirement-drafts`, `PATCH /v1/projects/{projectId}/requirement-drafts/{draftId}`, `POST /v1/projects/{projectId}/requirement-drafts/{draftId}:extract`, and `POST /v1/projects/{projectId}/requirement-drafts/{draftId}:submit`. Tenant and actor come only from `VerifiedRequestIdentity`; `projectId` is authorized and resolved under RLS, and no route/header tenant value participates in lookup or idempotency.

The create request is a closed `oneOf` selected by `intent`. `create_requirement` forbids base fields and compares `expected_version` with the project Requirement-collection ETag. `revise_existing` requires `base_requirement_id`, `base_revision_no`, and `base_revision_hash`, compares `expected_version` with that Requirement aggregate ETag, and authorizes direct editing only for the business-owned projection. The server prepopulates the draft from the exact base; neither a client nor Agent can replace development-owned or joint fields through the form. Business-domain and primary standard block-type values supplied by a canvas region are hints only. Every submit request carries `classification_confirmed=true` for the exact form business domain and primary block type; otherwise it returns `422 CLASSIFICATION_CONFIRMATION_REQUIRED`. The exact `DraftFormDto` defined in Task 9 is persisted and returned without a lossy secondary model: goal/current problem/target result, typed blocks, scope, edge cases, success metrics, acceptance criteria, gaps, and relation suggestions all survive extraction, autosave, refresh, and submit.

For `create_requirement`, `:submit` validates blocking intake questions, requires every relation suggestion to be explicitly accepted or rejected, confirms the edited transcript once with the form, writes provenance digests, and creates Revision 1 atomically. It persists only accepted relations into the semantic payload; rejected suggestions and reasons remain provenance. The temporary voice object defaults to `delete_after_submit`, but the submission transaction records `deletion_pending`, a durable deletion intent/outbox row, and a visible `audio_deletion_pending` ActionRequest; it never performs an object-provider call inside the database transaction and never reports `deleted` from intent alone. Only an explicit `retain_as_reference` selection promotes the exact bytes to an immutable reference Attachment and records `retained_as_reference` without a deletion row. The immutable submission receipt contains `requested_disposition`, `initial_state`, `deletion_operation_id`, and optional reference identity; same-key replay returns those exact bytes. Current state is read from the deletion projection, so an old receipt is not rewritten after worker progress.

`TemporaryAudioDeletionWorker` uses provider idempotency key `(tenant_id, deletion_operation_id, object_version)`, persists the provider version-delete request/receipt, and then performs a strongly consistent read-back for that exact version. It may transition to `deleted` only when the signed/provider-authenticated receipt binds the expected bucket/key/version and source digest and read-back proves absence (or returns an equivalent version-specific tombstone with matching digest). A timeout, ambiguous provider response, digest mismatch, still-readable version, or worker crash leaves `deletion_pending`, schedules bounded exponential backoff with jitter, and preserves the already committed structured text and Revision. Exhaustion transitions to `deletion_failed`, updates/creates the remediation ActionRequest, writes a DLQ record, and pages the configured operational alert; a reconciler can retry from persisted receipts without repeating a confirmed destructive call. Successful verification completes the pending ActionRequest and appends audit/outbox facts. No failure rolls back or discards the submitted structured Requirement.

For `revise_existing`, submit rechecks the base is still current, creates exactly one child Revision with `parent_revision_hash=base_revision_hash`, carries forward unchanged development-owned/joint content, supersedes prior confirmation links and scores, marks the carried development impact stale, and emits the development-review/impact-draft ActionRequests. It never mutates a canonical Revision row. Same-key replay returns the same child identity and identical initial audio-disposition fact; a concurrent newer parent fails `409 HASH_CONFLICT` with no partial Revision or audio disposition.

- [ ] **Step 5: Test refresh, cross-device resume, two-tab conflicts, and sensitive cache headers**

Run:

```bash
./gradlew :apps:control-plane:modules:requirement-graph:test :tests:api:test :tests:integration:test --tests '*Draft*' --tests '*RequirementIntakeDraftMigrationIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; stale writes return RFC 7807 `409 revision_conflict`; draft responses include `Cache-Control: no-store`; tenant-A/B collisions remain isolated; immutable transcription attempts survive retries; all composite tenant FKs reject cross-tenant references; all four V021 tables satisfy the global exact forced-RLS contract; provider ambiguity cannot produce `deleted`; retry/DLQ/alert and ActionRequest facts are durable while structured text remains committed; and every fixture bootstraps roles before Flyway.

- [ ] **Step 6: Commit intake drafts**

```bash
git add database/control-plane/migrations/V021__requirement_intake_drafts.sql apps/control-plane/modules/requirement-graph apps/control-plane/worker/src/main/java/com/inforvans/accord/worker/TemporaryAudioDeletionWorker.java tests/api/src/test/java/com/inforvans/accord/api/IntakeDraftApiTest.java tests/integration/src/test/java/com/inforvans/accord/integration/RequirementIntakeDraftMigrationIT.java tests/fault-injection/src/test/java/com/inforvans/accord/fault/TemporaryAudioDeletionRecoveryTest.java
git commit -m "feat(requirements): add resumable structured intake"
```

### Task 5: Implement Attachment Quarantine, Versioning, And Access Preflight

**Files:**
- Create: `database/control-plane/migrations/V022__attachment_metadata.sql`
- Create: `apps/control-plane/modules/attachments-metadata/src/main/java/com/inforvans/accord/attachment/domain/AttachmentModels.java`
- Create: `apps/control-plane/modules/attachments-metadata/src/main/java/com/inforvans/accord/attachment/application/AttachmentService.java`
- Create: `apps/control-plane/modules/attachments-metadata/src/main/java/com/inforvans/accord/attachment/application/AttachmentAccessPreflight.java`
- Create: `apps/control-plane/modules/attachments-metadata/src/main/java/com/inforvans/accord/attachment/api/AttachmentController.java`
- Create: `apps/attachment-scanner/src/main/java/com/inforvans/accord/scanner/AttachmentScannerApplication.java`
- Modify: `apps/attachment-scanner/build.gradle`
- Modify: `apps/attachment-scanner/gradle.lockfile`
- Create: `apps/attachment-scanner/src/main/java/com/inforvans/accord/scanner/scan/ScanPipeline.java`
- Create: `apps/attachment-scanner/src/main/java/com/inforvans/accord/scanner/scan/ArchiveInspector.java`
- Create: `apps/attachment-scanner/src/main/java/com/inforvans/accord/scanner/scan/ClamAvClient.java`
- Create: `apps/attachment-scanner/src/main/java/com/inforvans/accord/scanner/store/QuarantineStore.java`
- Create: `apps/attachment-scanner/src/main/java/com/inforvans/accord/scanner/preview/PreviewRenderer.java`
- Create: `apps/attachment-scanner/src/test/java/com/inforvans/accord/scanner/scan/ScanPipelineTest.java`
- Create: `infra/helm/attachment-scanner/templates/networkpolicy.yaml`
- Test: `apps/control-plane/modules/attachments-metadata/src/test/java/com/inforvans/accord/attachment/AttachmentLifecycleTest.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/AttachmentAccessSecurityTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/AttachmentMetadataMigrationIT.java`
- Verify: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/TenantRlsTest.java`
- Verify: `tests/architecture/verify-control-plane-fixtures.ps1`

- [ ] **Step 1: Add failing lifecycle and authorization tests**

```java
@Test
void quarantinedObjectCannotBePreviewedDownloadedOrReadByAgent() {
    var ref = fixtureAttachment(QUARANTINED);

    for (var action : List.of(USER_PREVIEW, USER_DOWNLOAD, AGENT_READ)) {
        assertThat(access.authorize(scope, actor, ref, action))
            .isEqualTo(new Denied("attachment_quarantined"));
    }
}

@Test
void contractualPreflightCreatesOneAccessFixAction() {
    preflight.check(revision, confirmationActors);
    preflight.check(revision, confirmationActors);

    var dedupeKey = "access:%s:%d".formatted(revision.hash(), attachment.version());
    assertThat(actions.openByDedupeKey(dedupeKey)).hasSize(1);
}
```

- [ ] **Step 2: Run and see the missing attachment module failure**

Run: `./gradlew :apps:control-plane:modules:attachments-metadata:test --tests '*AttachmentLifecycleTest'`

Expected: compilation fails because the attachment lifecycle is absent.

- [ ] **Step 3: Implement resumable upload metadata and scanner handoff**

```java
public final class AttachmentModels {
    private AttachmentModels() {}

    public enum AttachmentState { UPLOADING, SCANNING, AVAILABLE, QUARANTINED, DELETING, DELETED }
    public enum BindingType { CONTRACTUAL, REFERENCE }
    public record AttachmentVersionRef(UUID attachmentId, int version, Digest contentHash) {}
    public record ScanVerdict(ScanResult result, String detectedMime, boolean macroFound,
                              BigDecimal archiveExpansionRatio, String scannerVersion) {}
}
```

Use multipart upload IDs bound to tenant/project/object key and expiry. The scanner moves objects from `quarantine/{tenantId}/{projectId}/{attachmentId}/{version}/{objectId}` to `available/{tenantId}/{projectId}/{attachmentId}/{version}/{objectId}` only after MIME, malware, macro, archive expansion, and size checks. The API never accepts a client-supplied storage key.

V022 creates exactly these nine `public` tenant tables: `attachment`, `attachment_upload`, `attachment_version`, `attachment_scan_verdict`, `attachment_preview`, `attachment_binding`, `attachment_capability`, `attachment_retention_hold`, and `attachment_deletion_proof`. Every primary/unique key begins with `tenant_id`. Define composite tenant foreign keys from upload and version to attachment; from verdict, preview, binding, capability, retention hold, and deletion proof to the exact attachment version; and from `attachment_binding` to the bound `requirement_revision` identity. After all nine tables, constraints, append-only protections, and indexes exist, and before runtime DML grants, execute:

```sql
SELECT accord_security.enforce_tenant_table('public.attachment'::regclass);
SELECT accord_security.enforce_tenant_table('public.attachment_upload'::regclass);
SELECT accord_security.enforce_tenant_table('public.attachment_version'::regclass);
SELECT accord_security.enforce_tenant_table('public.attachment_scan_verdict'::regclass);
SELECT accord_security.enforce_tenant_table('public.attachment_preview'::regclass);
SELECT accord_security.enforce_tenant_table('public.attachment_binding'::regclass);
SELECT accord_security.enforce_tenant_table('public.attachment_capability'::regclass);
SELECT accord_security.enforce_tenant_table('public.attachment_retention_hold'::regclass);
SELECT accord_security.enforce_tenant_table('public.attachment_deletion_proof'::regclass);
```

Create `AttachmentMetadataMigrationIT.java` with PostgreSQL 17.5. Its setup calls `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)` immediately after `postgres.start()` and before `Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).target("022").load().migrate()`; it creates no roles or grants after Flyway. Seed colliding IDs in tenants A and B through the migrator and, for all nine tables, prove the `accord_api` role under tenant A cannot select, insert, update, or delete tenant-B rows and that missing tenant context fails closed. Query `pg_constraint` for every composite tenant FK above; explicitly reject a tenant-A version attached to tenant B and a tenant-A binding naming either a tenant-B attachment version or requirement revision. Assert the exact standard forced policy catalog values on all nine tables.

- [ ] **Step 4: Implement the isolated scanner pipeline**

```java
import java.util.List;
import java.util.UUID;

public record ScanRequest(
    UUID tenantId,
    UUID projectId,
    UUID attachmentId,
    int version,
    String quarantineKey,
    String expectedSha256,
    String declaredMime,
    long maximumBytes
) {}

public record ScanVerdict(
    ScanResult result,
    String detectedMime,
    String sha256,
    List<String> malwareSignatures,
    boolean macroFound,
    int archiveEntries,
    long archiveExpandedBytes,
    String scannerBundleVersion
) {
    public ScanVerdict {
        malwareSignatures = List.copyOf(malwareSignatures);
    }
}
```

Retain the Foundation-registered isolated `:apps:attachment-scanner` Gradle application project and Java 21 toolchain. Pin the ClamAV protocol, file-type detection, archive, object-store, protobuf, and test libraries in the version catalog; write dependency locks and require Gradle dependency verification before implementing the pipeline. The scanner may consume only bounded Java contract/observability libraries and generated verdict types, never control-plane business modules.

Stream once from the tenant-scoped quarantine key through a byte limit and SHA-256 calculator; detect MIME from bytes rather than filename; scan with a pinned ClamAV signature bundle; recursively inspect archives with maximum depth, entry count, per-entry size, total expanded bytes, and compression-ratio limits; detect active macros; and publish a signed verdict. The scanner has read/delete access only to quarantine and conditional write access only to the matching available key. It has no control-plane DB, model, Git, KMS signing, or public Internet access. Unsupported, timed-out, or scanner-error outcomes are `QUARANTINED`, never `AVAILABLE`.

After a clean verdict, generate previews only for the allowlisted image, PDF, text, spreadsheet, and office-document profiles in a separate sandbox with no network, read-only converter image, CPU/memory/time/page limits, disabled macros/external links, and a fresh ephemeral filesystem. Sanitize output to raster pages or a restricted PDF profile, store it under a non-guessable tenant/version preview key, and bind `preview_digest + renderer_bundle_version` to the attachment version. Preview failure leaves the original clean attachment downloadable to authorized users but marks preview `UNAVAILABLE`; it never changes the malware verdict or serves the original inline.

Run: `./gradlew :apps:attachment-scanner:test --tests '*ScanPipelineTest' && conftest test infra/helm/attachment-scanner/templates/networkpolicy.yaml`

Expected: clean fixtures pass; EICAR, MIME spoofing, macro, symlink, path traversal, nested archive, oversized expansion, truncated stream, and scanner timeout fixtures remain quarantined; network policy exposes only object storage, ClamAV/signature mirror through the controlled update path, and the typed verdict endpoint.

- [ ] **Step 5: Implement short-lived capability URLs and retention holds**

Capabilities bind tenant, attachment version, actor, purpose, content disposition, and a maximum five-minute expiry. Deletion traverses analysis, score, brief, confirmation, revision, audit investigation, and legal-hold references; a referenced object becomes restricted-retained rather than physically deleted.

- [ ] **Step 6: Run scanner contract, permission, and deletion tests**

Run:

```bash
./gradlew :apps:control-plane:modules:attachments-metadata:test :tests:security-negative:test :tests:integration:test --tests '*Attachment*' --tests '*AttachmentMetadataMigrationIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; old signed URLs fail after quarantine/revocation; deletion produces a signed deletion proof without retaining file content; tenant-A/B collisions and cross-tenant attachment bindings are rejected; all nine V022 tables satisfy the global exact forced-RLS contract; and every fixture bootstraps roles before Flyway.

- [ ] **Step 7: Commit attachment controls**

```bash
git add database/control-plane/migrations/V022__attachment_metadata.sql apps/control-plane/modules/attachments-metadata apps/attachment-scanner infra/helm/attachment-scanner tests/security-negative/src/test/java/com/inforvans/accord/security/AttachmentAccessSecurityTest.java tests/integration/src/test/java/com/inforvans/accord/integration/AttachmentMetadataMigrationIT.java
git commit -m "feat(attachments): add private versioned material workflow"
```

### Task 6: Enforce Field Ownership, Business Questions, And Multi-Round Development Proposals

**Files:**
- Create: `contracts/json-schema/requirement/development-proposal.schema.json`
- Create: `contracts/json-schema/requirement/business-question.schema.json`
- Create: `database/control-plane/migrations/V023__collaboration_actions.sql`
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/domain/ProposalModels.java`
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/domain/BusinessQuestionModels.java`
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/application/ProposalService.java`
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/application/BusinessQuestionService.java`
- Test: `apps/control-plane/modules/collaboration/src/test/java/com/inforvans/accord/collaboration/ProposalServiceTest.java`
- Test: `apps/control-plane/modules/collaboration/src/test/java/com/inforvans/accord/collaboration/BusinessQuestionServiceTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/ProposalIntegrationTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/CollaborationActionsMigrationIT.java`
- Verify: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/TenantRlsTest.java`
- Verify: `tests/architecture/verify-control-plane-fixtures.ps1`

- [ ] **Step 1: Write tests for forbidden direct edits and all proposal outcomes**

```java
@Test
void developmentSideCannotOverwriteBusinessOwnedFields() {
    assertThatThrownBy(() -> requirementCommands.patchBusinessField(
        developer, revision, "/blocks/RB-001/expected_outcome", "different"))
        .isInstanceOf(FieldOwnershipViolation.class);
}

@Test
void partialAcceptanceCreatesChildRevisionAndPreservesProposalAudit() {
    var result = proposals.resolve(
        businessEditor, proposal, PARTIALLY_ACCEPTED, selectedOperations);

    assertThat(result.newRevision().parentHash()).isEqualTo(proposal.targetRevisionHash());
    assertThat(result.proposal().status()).isEqualTo(PARTIALLY_ACCEPTED);
}

@Test
void businessQuestionIsRevisionBoundAndCannotBeResolvedByAnsweringSide() {
    var question = questions.create(
        businessEditor, currentRevision, developmentOwnedPointer, true);
    assertThat(question.actionTarget().side()).isEqualTo(Side.DEVELOPMENT);
    questions.addMessage(developmentReviewer, question.id(), QuestionMessageKind.ANSWER,
        "Use the existing policy boundary");

    assertThatThrownBy(() -> questions.resolve(
        developmentReviewer, question.id(), QuestionResolutionKind.ANSWER_ACCEPTED))
        .isInstanceOf(FieldOwnershipViolation.class);
    assertThat(questions.resolve(
        businessEditor, question.id(), QuestionResolutionKind.ANSWER_ACCEPTED).status())
        .isEqualTo(BusinessQuestionStatus.RESOLVED);
}
```

- [ ] **Step 2: Run and verify tests fail**

Run: `./gradlew :apps:control-plane:modules:collaboration:test --tests '*ProposalServiceTest' --tests '*BusinessQuestionServiceTest'`

Expected: compilation fails for `ProposalService`.

- [ ] **Step 3: Define proposal commands and ownership policy**

```java
// ProposalModels.java
public final class ProposalModels {
    private ProposalModels() {}

    public enum ProposalDecision {
        ACCEPTED, PARTIALLY_ACCEPTED, REJECTED, MORE_INFORMATION_REQUIRED
    }

    public record DevelopmentProposal(
        UUID id,
        RevisionIdentity targetRevision,
        String jsonPointer,
        Digest currentValueDigest,
        JsonNode proposedValue,
        String reason,
        List<EvidenceRef> evidenceRefs,
        String risk,
        String expectedImpact,
        boolean blocking
    ) {}
}

// BusinessQuestionModels.java
public final class BusinessQuestionModels {
    private BusinessQuestionModels() {}

    public enum BusinessQuestionStatus { OPEN, ANSWERED, RESOLVED, SUPERSEDED }
    public enum QuestionMessageKind { CONTEXT, ANSWER, CLARIFICATION }
    public enum QuestionResolutionKind { ANSWER_ACCEPTED, REVISION_REQUIRED, WITHDRAWN }

    public record BusinessQuestion(
        UUID id, RevisionIdentity targetRevision, long version, String targetJsonPointer,
        FieldOwnerSide targetOwnerSide, String prompt, String reason, boolean blocking,
        List<BlockAttachmentRef> evidenceAttachmentRefs, BusinessQuestionStatus status,
        UUID createdByNaturalPersonId, Instant createdAt
    ) {}

    public record BusinessQuestionMessage(
        UUID id, UUID questionId, long sequence, QuestionMessageKind kind,
        FieldOwnerSide authorSide, UUID authorNaturalPersonId, String body,
        List<BlockAttachmentRef> attachmentRefs, Instant createdAt
    ) {}

    public record BusinessQuestionResolution(
        UUID id, UUID questionId, QuestionResolutionKind resolutionKind,
        UUID acceptedMessageId, String summary, RevisionIdentity resultingRevision,
        UUID resolvedByNaturalPersonId, Instant resolvedAt
    ) {}
}
```

Map every semantic JSON pointer to `BUSINESS`, `DEVELOPMENT`, or `JOINT`. Joint changes require both sides in the eventual revision confirmation; accepted semantic operations always create a child revision. Display-label-only operations update `display_version` only when canonical bytes remain byte-identical.

`BusinessQuestionService` is not a generic chat store. Only an authorized business-side member can create a `BusinessQuestion`, and its target must resolve to a development-owned field in the exact target Revision. Both sides may append messages, but `authorSide` and `authorNaturalPersonId` come only from `VerifiedRequestIdentity`; message content, kind, and attachment refs become immutable. A development-side natural person must author an `ANSWER`; only an authorized business-side natural person may resolve. `ANSWER_ACCEPTED` requires an answer message, `REVISION_REQUIRED` requires the resulting child Revision identity created by the owning workflow, and `WITHDRAWN` requires a reason. A new Revision supersedes every unresolved question bound to the old hash unless the new Revision command explicitly carries it forward with a new question ID and audit link. A blocking open/answered question prevents development confirmation; no score or administrator override bypasses it.

V023 creates exactly these fourteen `public` tenant tables for this task and the persistence consumed by Tasks 7-8: `development_proposal`, `development_proposal_operation`, `development_proposal_resolution`, `requirement_business_question`, `requirement_business_question_message`, `requirement_business_question_resolution`, `action_request`, `requirement_confirmation_link`, `ready_pool_entry`, `notification_intent`, `notification_delivery_attempt`, `notification_delivery_receipt`, `notification_delivery_dead_letter`, and `notification_escalation_schedule`. Every primary/unique key begins with `tenant_id`. Use composite tenant foreign keys from proposal and question to their exact `requirement_revision`, operation and proposal resolution to proposal, question message and resolution to question, `requirement_confirmation_link` to both its exact Revision and Identity's authoritative `(tenant_id, receipt_id, confirmation_sequence)` `confirmation_receipt`, and Ready Pool entry to its exact Revision and required link identities. The link contains only `tenant_id`, `requirement_id`, `revision_no`, `revision_hash`, `side`, `confirmation_sequence`, `confirmation_receipt_id`, and `linked_at`; it has no account, natural person, role, permission, FreshAuth, nonce, signature, or independent validity columns and allocates no sequence of its own. Enforce unique `(tenant_id, requirement_id, revision_no, revision_hash, side, confirmation_sequence)` and unique `(tenant_id, confirmation_receipt_id)` so a receipt cannot authorize two Requirement links while a new current principal can append a later link for the same immutable Revision after an older authorization becomes invalid. The composite FK plus catalog/integration assertion proves the stored sequence equals the linked Identity receipt sequence.

Notification intent children reference their exact intent and ActionRequest. The database dedupe constraint is precisely the five conceptual fields from section 15.3: `(tenant_id, recipient_natural_person_id, action_key, object_ref, object_version, channel)`, where tenant scopes the row and the dedupe key itself is `user + action + object + version + channel`; `object_ref` is the canonical `object_type:object_id` value, never display text. Enforce unique `(tenant_id, question_id, sequence)` and at most one resolution; messages, resolutions, notification receipts, and dead letters are append-only and deny runtime UPDATE/DELETE, while mutable headers advance only by aggregate-version CAS. `action_request` keeps a typed subject identity and digest rather than an unsafe cross-domain scalar FK. Execute the standard installer only after those constraints and append-only protections exist and before runtime grants:

After `action_request` exists, V023 executes `ALTER TABLE temporary_audio_deletion ADD CONSTRAINT temporary_audio_deletion_action_request_fk FOREIGN KEY (action_request_tenant_id, action_request_id) REFERENCES action_request(tenant_id, action_request_id)` with the V021 all-null/all-present check retained. No earlier migration or test pre-creates the target table.

```sql
SELECT accord_security.enforce_tenant_table('public.development_proposal'::regclass);
SELECT accord_security.enforce_tenant_table('public.development_proposal_operation'::regclass);
SELECT accord_security.enforce_tenant_table('public.development_proposal_resolution'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_business_question'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_business_question_message'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_business_question_resolution'::regclass);
SELECT accord_security.enforce_tenant_table('public.action_request'::regclass);
SELECT accord_security.enforce_tenant_table('public.requirement_confirmation_link'::regclass);
SELECT accord_security.enforce_tenant_table('public.ready_pool_entry'::regclass);
SELECT accord_security.enforce_tenant_table('public.notification_intent'::regclass);
SELECT accord_security.enforce_tenant_table('public.notification_delivery_attempt'::regclass);
SELECT accord_security.enforce_tenant_table('public.notification_delivery_receipt'::regclass);
SELECT accord_security.enforce_tenant_table('public.notification_delivery_dead_letter'::regclass);
SELECT accord_security.enforce_tenant_table('public.notification_escalation_schedule'::regclass);
```

Create `CollaborationActionsMigrationIT.java` with PostgreSQL 17.5. Call `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)` immediately after `postgres.start()` and before migrating a completely empty application schema in order through V020, V021, V022, and target V023, with no pre-created domain table and no role/grant repair after migration. Seed colliding rows for tenants A and B through the migrator and prove the `accord_api` role under tenant A cannot select or mutate tenant-B rows in any of the fourteen V023 tables and that missing tenant context fails closed. Query `pg_constraint` to verify the composite tenant FKs above plus the deferred V023 `temporary_audio_deletion -> action_request` FK; reject tenant-A proposal operations/resolutions naming tenant-B proposals, question messages/resolutions naming tenant-B questions, confirmation links naming tenant-B revisions or receipts, Ready Pool entries naming tenant-B revisions or links, notification children naming another tenant's intent/ActionRequest, and an audio deletion naming another tenant's ActionRequest. Assert all fourteen V023 tables have exact enabled/forced `tenant_isolation`, `FOR ALL TO PUBLIC`, `USING`, and `WITH CHECK` catalog definitions; direct UPDATE/DELETE of append-only rows must reach the trigger and fail.

- [ ] **Step 4: Add proposal and BusinessQuestion ActionRequests with stale-version closure**

Resolve against the exact target hash. A new revision marks unresolved requests for the old hash `SUPERSEDED`; rejection requires a reason and routes blocking proposals back to the development lead with the recorded decision. Creating a BusinessQuestion atomically creates one deduplicated `CLARIFICATION` ActionRequest for the current development-side owner. A development answer completes that responder action and opens a business resolution ActionRequest; resolving or superseding the question closes both by the same underlying action key. Transaction rollback leaves neither an orphan question nor an orphan ActionRequest.

- [ ] **Step 5: Run module, audit, and stale-proposal tests**

Run:

```bash
./gradlew :apps:control-plane:modules:collaboration:test :tests:integration:test --tests '*Proposal*' --tests '*BusinessQuestion*' --tests '*CollaborationActionsMigrationIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; an empty schema migrates V020→V021→V022→V023 without a forward-reference failure; no accepted operation can mutate an existing canonical revision row; questions and messages preserve exact revision/side/actor ownership; tenant-A/B collisions remain isolated; every applicable composite tenant FK rejects cross-tenant references; all fourteen V023 tables satisfy the global exact forced-RLS contract; and every fixture bootstraps roles before Flyway.

- [ ] **Step 6: Commit collaboration ownership**

```bash
git add contracts/json-schema/requirement/development-proposal.schema.json contracts/json-schema/requirement/business-question.schema.json database/control-plane/migrations/V023__collaboration_actions.sql apps/control-plane/modules/collaboration tests/integration/src/test/java/com/inforvans/accord/integration/ProposalIntegrationTest.java tests/integration/src/test/java/com/inforvans/accord/integration/CollaborationActionsMigrationIT.java
git commit -m "feat(collaboration): enforce proposal-based semantic edits"
```

### Task 7: Build The Unified ActionRequest Queue

**Files:**
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/domain/ActionRequest.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/application/ActionRequestService.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/application/ActionRoutingService.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/domain/NotificationModels.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/application/NotificationOrchestrator.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/application/NotificationPreferencePort.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/infrastructure/NotificationDeliveryWorker.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/infrastructure/InAppNotificationAdapter.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/infrastructure/EmailNotificationAdapter.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/infrastructure/EnterpriseImNotificationAdapter.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/infrastructure/MobilePushNotificationAdapter.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/api/ActionRequestController.java`
- Test: `apps/control-plane/modules/actions-notifications/src/test/java/com/inforvans/accord/action/ActionRequestIdempotencyTest.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/ActionNotificationRecoveryTest.java`

- [ ] **Step 1: Write tests for deduplication, supersession, vacancy, delegation, and rejection routing**

```java
@Test
void twoEntryPointsCompleteOneUnderlyingActionOnce() {
    var first = service.complete(actor, requestA, actionKey, 8);
    var second = service.complete(actor, requestB, actionKey, 8);

    assertThat(second.resultDigest()).isEqualTo(first.resultDigest());
    assertThat(audit.count("action.completed", actionKey)).isOne();
}

@Test
void missingOwnerCreatesVacancyHoldInsteadOfOwnerlessRequest() {
    assertThat(routing.route(commandWithoutResolvableOwner))
        .isInstanceOf(RoleVacancyHoldCreated.class);
}

@Test
void notificationDedupeIsExactlyUserActionObjectVersionChannel() {
    IntStream.range(0, 2).forEach(ignored ->
        notifications.enqueue(intent(recipient, actionKey, objectRef, 8, EMAIL)));

    assertThat(notificationIntents.count(recipient, actionKey, objectRef, 8, EMAIL)).isOne();
    assertThat(notificationIntents.count(recipient, actionKey, objectRef, 9, EMAIL)).isZero();
}

@Test
void staleRecipientIsSuppressedWithoutChangingTheActionRequest() {
    membership.revoke(recipient);
    worker.deliver(pendingIntent);

    assertThat(deliveryReceipt(pendingIntent).outcome())
        .isEqualTo(SUPPRESSED_STALE_RECIPIENT);
    assertThat(actionRequest(pendingIntent.actionRequestId()).phase()).isEqualTo(OPEN);
}
```

- [ ] **Step 2: Run and verify the queue tests fail**

Run: `./gradlew :apps:control-plane:modules:actions-notifications:test --tests '*ActionRequestIdempotencyTest'`

Expected: compilation fails for `ActionRequestService`.

- [ ] **Step 3: Implement the authoritative action model**

```java
// ActionRequest.java
public final class ActionRequest {
    public enum ActionPhase { OPEN, COMPLETED, DECLINED, EXPIRED, SUPERSEDED, CANCELLED }
    public enum ActionType { CLARIFICATION, DEVELOPMENT_REVIEW, PROPOSAL_RESOLUTION, HUMAN_SCORE, POLICY_CONFIRMATION, DEVELOPMENT_CONFIRMATION, BUSINESS_CONFIRMATION, OVERRIDE_APPROVAL, DELIVERY_COMMITMENT, BATCH_CONFIRMATION, BATCH_AMENDMENT_OR_CANCELLATION, ACCEPTANCE, FAILURE_DISPOSITION, CONTEXT_REBUILD, ACCESS_FIX, AUDIO_DELETION_PENDING, AUDIO_DELETION_FAILED, RECONCILIATION, EMERGENCY_AUTHORIZATION }
    public record ActionTarget(String objectType, UUID objectId, long objectVersion,
                               Digest objectDigest) {}
}

// NotificationModels.java
public final class NotificationModels {
    private NotificationModels() {}

    public enum NotificationChannel { IN_APP, EMAIL, ENTERPRISE_IM, MOBILE_PUSH }
    public enum NotificationUrgency { URGENT, DIGEST }
    public enum NotificationDeliveryOutcome { DELIVERED, SUPPRESSED_STALE_RECIPIENT, SUPPRESSED_STALE_ROLE, SUPPRESSED_STALE_OBJECT, SUPPRESSED_TENANT_MISMATCH, FAILED_RETRYABLE, DEAD_LETTERED }
    public record NotificationDedupeKey(UUID userId, String action, String objectRef,
                                        long version, NotificationChannel channel) {}
    public record NotificationDeliveryReceipt(
        UUID intentId, NotificationDedupeKey dedupeKey, NotificationUrgency urgency,
        int attempt, NotificationDeliveryOutcome outcome, String providerRequestId,
        Digest responseDigest, Instant recordedAt
    ) {}
}
```

Persist target side/role/account rule, blocker, decision options, risk, due time, escalation policy, causation, and a unique `underlying_action_key`. Read/unread is a separate user projection.

- [ ] **Step 4: Add SSE and the complete notification delivery contract**

Emit SSE events with tenant-scoped monotonic `projection_sequence` and exact object version. A client gap causes refetch. `actions-notifications` owns notification intents, urgency classification, digest buckets, escalation schedules, attempts, receipts, retry/backoff, DLQ, and channel adapters; it does not own preferences. `NotificationPreferencePort` is implemented by Identity's read-only project/user preference API, so the dependency is one-way (`actions-notifications -> identity`) and Identity never imports ActionRequest or delivery classes.

Classify blocking questions, high-risk actions, stale/invalid confirmations, attachment access loss, acceptance failure, Project Context stale, reconciliation failure, and audio deletion failure as `URGENT`; ordinary progress, non-blocking risk, and Agent suggestions are `DIGEST`. Urgent intents schedule immediate eligible-channel delivery; digest intents join a user/project/time-zone bucket and emit at the configured cadence. Supported adapters are exactly `IN_APP`, `EMAIL`, `ENTERPRISE_IM`, and `MOBILE_PUSH`. Each adapter receives only a minimal summary, same-origin deep link, five-field dedupe key, and provider idempotency key; no Requirement body, attachment, or sensitive evidence leaves the platform.

Before every attempt, and again before an escalation attempt, the worker opens a tenant transaction and reloads current project membership, role/side ownership, delegation, object permission/version, ActionRequest phase, recipient channel preference, and tenant/project binding. A departed member, revoked role, moved responsibility, completed/superseded ActionRequest, stale object version, or tenant mismatch produces an append-only suppression receipt and routes a new intent to the current owner when applicable. Due-time scheduling reminds the current assignee, then escalates at policy offsets to the active delegate and current same-side principal; escalation never completes or approves the ActionRequest.

Persist intent, attempt number, request digest, provider request ID, response digest/code, timestamps, and a terminal delivery/suppression receipt. Retry transient errors with bounded exponential backoff plus jitter under the same five-field key; permanent errors or exhausted attempts create `notification_delivery_dead_letter` and an operational alert. Provider reconciliation resolves crashes around sends. Notification transactions begin only after the authoritative ActionRequest/outbox transaction commits, and every delivery failure leaves the in-app ActionRequest and domain event unchanged.

Delivery attempts/receipts/DLQ are internal worker and viewer-filtered audit schemas, not a new public notification URL. A dead-letter remediation ActionRequest exposes decision `retry_delivery` through its existing `allowed_actions`; invoking the existing project-scoped `completeActionRequest` validates current ownership and calls `NotificationOrchestrator.retryDeadLetter` under the same ActionRequest idempotency/CAS contract. This preserves the fixed 47-operation Requirement public owner set while retaining an operator-controlled retry path.

- [ ] **Step 5: Run queue, expiry, and notification replay tests**

Run: `./gradlew :apps:control-plane:modules:actions-notifications:test :tests:fault-injection:test --tests '*Action*' --tests '*Notification*'`

Expected: all tests pass; each of the four adapters receives at most one message for `user + action + object + version + channel`; urgent and digest schedules differ; escalations target only current delegates/principals; stale membership/role/tenant/object recipients are suppressed; transient delivery retries and permanent failure reaches DLQ/alert; every attempt has a receipt; and no channel failure completes, deletes, or rolls back an ActionRequest.

- [ ] **Step 6: Commit the action ledger**

```bash
git add apps/control-plane/modules/actions-notifications tests/fault-injection/src/test/java/com/inforvans/accord/fault/ActionNotificationRecoveryTest.java
git commit -m "feat(actions): add authoritative human action queue"
```

### Task 8: Enforce Exact-Revision Confirmation And Ready Pool Revalidation

**Files:**
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/domain/RequirementConfirmationLink.java`
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/application/RequirementConfirmationCoordinator.java`
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/application/ReadyPoolService.java`
- Test: `apps/control-plane/modules/collaboration/src/test/java/com/inforvans/accord/collaboration/ProposalAndConfirmationTest.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/ConfirmationSecurityTest.java`
- Test: `tests/state-machine/src/test/java/com/inforvans/accord/state/RequirementStateProperties.java`

- [ ] **Step 1: Write failing ordering, hash, identity, and revalidation tests**

```java
@Test
void businessConfirmationBeforeDevelopmentConfirmationIsRejected() {
    assertThat(confirm.business(actorBusiness, revision).problemCode())
        .isEqualTo("development_confirmation_required");
}

@Test
void strictCrossSideConfirmationsRejectTheSameNaturalPerson() {
    confirm.development(devAccount, revision);

    assertThat(confirm.business(secondAccountForSamePerson, revision).problemCode())
        .isEqualTo("strict_natural_person_separation");
}

@Test
void changedClaimDigestRemovesRequirementFromReadyPool() {
    assertThat(readyPool.revalidate(revision, contextWithChangedClaim).eligibility())
        .isEqualTo(INVALIDATED);
}

@Test
void identityReceiptLinkAndRequirementOutboxRollBackTogether() {
    assertThatThrownBy(() -> coordinator.confirm(command, true))
        .isInstanceOf(LinkWriteFailure.class);
    assertThat(identityReceipts.count(command.idempotencyKey())).isZero();
    assertThat(requirementLinks.count(command.revision())).isZero();
    assertThat(outbox.count(command.idempotencyKey())).isZero();
}

@Test
void concurrentReplayCreatesOneAuthoritativeReceiptAndOneLink() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
        var first = CompletableFuture.supplyAsync(() -> coordinator.confirm(command), executor);
        var second = CompletableFuture.supplyAsync(() -> coordinator.confirm(command), executor);
        CompletableFuture.allOf(first, second).get();
    }

    assertThat(identityReceipts.count(command.idempotencyKey())).isOne();
    assertThat(requirementLinks.count(command.revision(), command.side())).isOne();
}

@Test
void revokedRoleOrSupersededRevisionCannotCreateALink() {
    roleBindings.revoke(command.roleBindingId());
    assertThat(coordinator.confirm(command).problemCode()).isEqualTo("authorization_stale");
    revisionStore.supersede(command.revision());

    assertThat(coordinator.confirm(command.withIdempotencyKey(UUID.randomUUID())).problemCode())
        .isEqualTo("revision_stale");
}

@Test
void newPrincipalReconfirmsSameRevisionWithoutRewritingHistory() {
    var old = coordinator.confirm(command);
    roleBindings.revoke(command.roleBindingId());
    assertThat(readyPool.revalidate(command.revision()).eligibility()).isEqualTo(INVALIDATED);

    var replacement = coordinator.confirm(
        command.forPrincipal(newPrincipal).withIdempotencyKey(UUID.randomUUID()));
    assertThat(replacement.confirmationSequence()).isEqualTo(old.confirmationSequence() + 1);
    assertThat(readyPool.revalidate(command.revision()).eligibility()).isEqualTo(ELIGIBLE);
    assertThat(requirementLinks.get(old.confirmationReceiptId())).isEqualTo(old);
    assertThat(identityValidity.get(old.confirmationReceiptId())).isEqualTo(INVALID);
}
```

- [ ] **Step 2: Run and observe the missing confirmation service**

Run: `./gradlew :apps:control-plane:modules:collaboration:test --tests '*ProposalAndConfirmationTest'`

Expected: compilation fails for `RequirementConfirmationCoordinator`.

- [ ] **Step 3: Define the FK-backed link and Identity confirmation port**

```java
// RequirementConfirmationLink.java
public record RequirementConfirmationLink(
    RevisionIdentity revision,
    ConfirmationSide side,
    long confirmationSequence,
    UUID confirmationReceiptId,
    Instant linkedAt
) {
    public enum ConfirmationSide { DEVELOPMENT, BUSINESS }
}

// RequirementConfirmationCoordinator.java
public final class RequirementConfirmationCoordinator {
    public interface IdentityConfirmationPort {
        <T> T confirm(
            VerifiedRequestIdentity identity,
            ConfirmationCommand command,
            BiFunction<TenantTransaction, AuthoritativeConfirmationReceiptRef, T> continuation
        );
    }

    public interface AssessmentEligibilityPort {
        EligibilityEvidence evaluate(TenantScope scope, RevisionIdentity revision);
    }

    public interface AttachmentPreflightPort {
        PreflightResult check(TenantScope scope, RevisionIdentity revision, Set<ActorRef> actors);
    }
}
```

`AuthoritativeConfirmationReceiptRef` exposes only receipt ID, `confirmationSequence`, subject digest, side, and confirmed time needed for linking. Actor, natural-person, role/delegation evidence, authorization decision, FreshAuth proof/nonce, idempotency result, signature, and revocation/current-validity interpretation remain exclusively in Identity's `confirmation_receipt` and `ConfirmationCommandService`. `requirement_confirmation_link` copies the authoritative sequence solely as an FK component; it is a domain join/projection and cannot allocate a sequence or independently establish authorization or a signature.

- [ ] **Step 4: Coordinate one tenant transaction without a circular dependency**

`RequirementConfirmationCoordinator` accepts `VerifiedRequestIdentity` as its only tenant/actor authority, locks and validates the exact current Revision plus requirement-owned gates (assessment evidence, context basis, attachment access, unresolved proposals/questions, accepted unknowns, and aggregate version), then calls Identity's exported `ConfirmationCommandService.confirm(identity, command, continuation)` API. Identity rechecks membership, immutable repository binding, role binding version, scope, delegation, side/order/separation, FreshAuth, nonce, idempotency, and authorization and appends the sole long-lived receipt. Before that transaction commits, the continuation inserts the FK-backed Requirement link and Requirement audit/outbox event using the supplied `TenantTransaction`. A link or outbox error rolls back the receipt/FreshAuth consumption/idempotency result; an Identity error leaves no link. The Requirement module depends on Identity's confirmation API, while Identity depends only on opaque subject fields and never imports Requirement types, so there is no circular module dependency. Never copy a receipt or link to a child Revision.

- [ ] **Step 5: Implement deterministic Ready Pool eligibility**

Ready Pool is a query over current `BILATERALLY_CONFIRMED` revisions plus the greatest currently valid Identity `confirmation_sequence` per side joined through the FK-backed link and receipt/current-validity view, current policy/context/claim digests, accessible contractual attachments, valid roles, satisfied dependencies, and no holds. Receipt revocation/trust invalidation, role or membership removal, or a newer Revision invalidates eligibility without mutating the historical receipt/link. After invalidation, a new current principal may confirm the identical `revision_hash`; Identity appends the next receipt sequence and the continuation links that exact sequence, restoring eligibility only when both current sides again satisfy policy. Old receipts/links remain immutable and auditable and replay can never reactivate them. Store invalidation causes and ActionRequests, not a mutable boolean that can drift.

- [ ] **Step 6: Run security matrix and revalidation tests**

Run:

```bash
./gradlew :apps:control-plane:modules:collaboration:test :tests:security-negative:test --tests '*Confirmation*'
./gradlew :tests:state-machine:test --tests '*RequirementStateProperties'
```

Expected: all tests pass; cross-module rollback leaves no receipt/link/outbox; concurrent/replayed commands produce exactly one Identity receipt and one Requirement link sequence; authorization revocation invalidates Ready Pool, a new principal's later receipt/link sequence for the same Revision restores it, and the old receipt/link remains auditable but cannot reactivate; old hash, superseded Revision, revoked role/membership/receipt validity, inaccessible attachment, stale context, and same-person strict confirmations all fail closed.

- [ ] **Step 7: Commit confirmation and Ready Pool behavior**

```bash
git add apps/control-plane/modules/collaboration tests/security-negative/src/test/java/com/inforvans/accord/security/ConfirmationSecurityTest.java tests/state-machine/src/test/java/com/inforvans/accord/state/RequirementStateProperties.java
git commit -m "feat(collaboration): require exact bilateral confirmation"
```

### Task 9: Publish OpenAPI Queries, Diffs, Commands, And SSE Contracts

**Files:**
- Modify: `contracts/openapi/accord-control-api.yaml`
- Create: `apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platform/api/ApiContractDtos.java`
- Modify: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/api/RequirementController.java`
- Create: `apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/api/RequirementApiDtos.java`
- Modify: `apps/control-plane/modules/attachments-metadata/src/main/java/com/inforvans/accord/attachment/api/AttachmentController.java`
- Create: `apps/control-plane/modules/attachments-metadata/src/main/java/com/inforvans/accord/attachment/api/AttachmentApiDtos.java`
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/api/CollaborationController.java`
- Create: `apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/api/CollaborationApiDtos.java`
- Modify: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/api/ActionRequestController.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/api/ActionRequestApiDtos.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/api/ActionRequestPort.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/api/package-info.java`
- Modify: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/application/ActionRequestService.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/api/ProjectEventController.java`
- Create: `apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/api/ProjectEventDtos.java`
- Modify: `apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/RequirementApiContractTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/RequirementWorkflowHttpIT.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/RequirementWorkflowApiSecurityTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/ProjectEventReplayIT.java`
- Create: `packages/api-client/src/requirement-workflow.contract.test.ts`

- [ ] **Step 1: Add failing cumulative OpenAPI and controller-set tests**

```java
package com.inforvans.accord.api;

@SpringBootTest
class RequirementApiContractTest {
    private static final Map<String, String> FOUNDATION_OPERATIONS = Map.of(
        "getReadiness", "GET /health/ready",
        "validateContract", "POST /v1/projects/{projectId}/contract-validations/{validationId}"
    );

    private static final Map<String, String> REQUIREMENT_OPERATIONS = Map.ofEntries(
        Map.entry("listRequirements", "GET /v1/projects/{projectId}/requirements"),
        Map.entry("getRequirementGraph", "GET /v1/projects/{projectId}/requirements/graph"),
        Map.entry("getRequirement", "GET /v1/projects/{projectId}/requirements/{requirementId}"),
        Map.entry("listRequirementRevisions", "GET /v1/projects/{projectId}/requirements/{requirementId}/revisions"),
        Map.entry("getRequirementRevision", "GET /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}"),
        Map.entry("getRequirementBusinessProjection", "GET /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}/business-projection"),
        Map.entry("getRequirementDevelopmentProjection", "GET /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}/development-projection"),
        Map.entry("compareRequirementRevisions", "GET /v1/projects/{projectId}/requirements/{requirementId}/revisions/compare"),
        Map.entry("createRequirementDraft", "POST /v1/projects/{projectId}/requirement-drafts"),
        Map.entry("getRequirementDraft", "GET /v1/projects/{projectId}/requirement-drafts/{draftId}"),
        Map.entry("updateRequirementDraft", "PATCH /v1/projects/{projectId}/requirement-drafts/{draftId}"),
        Map.entry("extractRequirementDraft", "POST /v1/projects/{projectId}/requirement-drafts/{draftId}:extract"),
        Map.entry("getRequirementDraftExtraction", "GET /v1/projects/{projectId}/requirement-drafts/{draftId}/extractions/{extractionId}"),
        Map.entry("createRequirementDraftTranscriptionUpload", "POST /v1/projects/{projectId}/requirement-drafts/{draftId}/transcription-uploads"),
        Map.entry("completeRequirementDraftTranscriptionUpload", "POST /v1/projects/{projectId}/requirement-drafts/{draftId}/transcription-uploads/{uploadId}:complete"),
        Map.entry("getRequirementDraftTranscription", "GET /v1/projects/{projectId}/requirement-drafts/{draftId}/transcriptions/{transcriptionId}"),
        Map.entry("submitRequirementDraft", "POST /v1/projects/{projectId}/requirement-drafts/{draftId}:submit"),
        Map.entry("listAttachments", "GET /v1/projects/{projectId}/attachments"),
        Map.entry("createAttachmentUpload", "POST /v1/projects/{projectId}/attachment-uploads"),
        Map.entry("getAttachmentUpload", "GET /v1/projects/{projectId}/attachment-uploads/{uploadId}"),
        Map.entry("signAttachmentUploadPart", "POST /v1/projects/{projectId}/attachment-uploads/{uploadId}/parts/{partNumber}:sign"),
        Map.entry("listAttachmentUploadParts", "GET /v1/projects/{projectId}/attachment-uploads/{uploadId}/parts"),
        Map.entry("completeAttachmentUpload", "POST /v1/projects/{projectId}/attachment-uploads/{uploadId}:complete"),
        Map.entry("abortAttachmentUpload", "POST /v1/projects/{projectId}/attachment-uploads/{uploadId}:abort"),
        Map.entry("getAttachmentVersion", "GET /v1/projects/{projectId}/attachments/{attachmentId}/versions/{version}"),
        Map.entry("getAttachmentStatus", "GET /v1/projects/{projectId}/attachments/{attachmentId}/versions/{version}/status"),
        Map.entry("createAttachmentPreviewCapability", "POST /v1/projects/{projectId}/attachments/{attachmentId}/versions/{version}/preview-capabilities"),
        Map.entry("createAttachmentDownloadCapability", "POST /v1/projects/{projectId}/attachments/{attachmentId}/versions/{version}/download-capabilities"),
        Map.entry("runAttachmentAccessPreflight", "POST /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}/attachment-access-preflight"),
        Map.entry("createDevelopmentProposal", "POST /v1/projects/{projectId}/requirements/{requirementId}/development-proposals"),
        Map.entry("listDevelopmentProposals", "GET /v1/projects/{projectId}/requirements/{requirementId}/development-proposals"),
        Map.entry("getDevelopmentProposal", "GET /v1/projects/{projectId}/requirements/{requirementId}/development-proposals/{proposalId}"),
        Map.entry("resolveDevelopmentProposal", "POST /v1/projects/{projectId}/requirements/{requirementId}/development-proposals/{proposalId}:resolve"),
        Map.entry("listRequirementBusinessQuestions", "GET /v1/projects/{projectId}/requirements/{requirementId}/business-questions"),
        Map.entry("createRequirementBusinessQuestion", "POST /v1/projects/{projectId}/requirements/{requirementId}/business-questions"),
        Map.entry("getRequirementBusinessQuestion", "GET /v1/projects/{projectId}/requirements/{requirementId}/business-questions/{questionId}"),
        Map.entry("addRequirementBusinessQuestionMessage", "POST /v1/projects/{projectId}/requirements/{requirementId}/business-questions/{questionId}/messages"),
        Map.entry("resolveRequirementBusinessQuestion", "POST /v1/projects/{projectId}/requirements/{requirementId}/business-questions/{questionId}:resolve"),
        Map.entry("listActionRequests", "GET /v1/projects/{projectId}/action-requests"),
        Map.entry("getActionRequest", "GET /v1/projects/{projectId}/action-requests/{actionRequestId}"),
        Map.entry("completeActionRequest", "POST /v1/projects/{projectId}/action-requests/{actionRequestId}:complete"),
        Map.entry("declineActionRequest", "POST /v1/projects/{projectId}/action-requests/{actionRequestId}:decline"),
        Map.entry("delegateActionRequest", "POST /v1/projects/{projectId}/action-requests/{actionRequestId}:delegate"),
        Map.entry("confirmRequirementDevelopment", "POST /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}:confirm-development"),
        Map.entry("confirmRequirementBusiness", "POST /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}:confirm-business"),
        Map.entry("streamProjectEvents", "GET /v1/projects/{projectId}/events"),
        Map.entry("replayProjectEvents", "GET /v1/projects/{projectId}/events/replay")
    );

    private static final Set<String> FRESH_AUTH_OPERATIONS = Set.of(
        "confirmRequirementDevelopment", "confirmRequirementBusiness");
    private static final List<Class<?>> CONTROLLERS = List.of(
        RequirementController.class, AttachmentController.class, CollaborationController.class,
        ActionRequestController.class, ProjectEventController.class);

    private final OpenAPI api = new OpenAPIV3Parser()
        .read("contracts/openapi/accord-control-api.yaml");
    private final RequestMappingHandlerMapping handlerMapping;

    @Autowired
    RequirementApiContractTest(RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @Test
    void canonicalApiRetainsPriorMilestonesAndExactRequirementOwnerSet() {
        var entries = publishedOperations();
        assertThat(entries).extracting(PublishedOperation::operationId)
            .allSatisfy(id -> assertThat(id).isNotBlank()).doesNotHaveDuplicates();
        var actual = entries.stream().collect(Collectors.toMap(
            PublishedOperation::operationId, PublishedOperation::signature));
        assertThat(actual).containsAllEntriesOf(FOUNDATION_OPERATIONS);
        var requirementOwned = entries.stream()
            .filter(entry -> "requirement-workflow".equals(entry.owner()))
            .collect(Collectors.toMap(PublishedOperation::operationId,
                                      PublishedOperation::signature));
        assertThat(requirementOwned).containsExactlyInAnyOrderEntriesOf(REQUIREMENT_OPERATIONS);
        assertThat(api.getComponents().getSchemas().keySet())
            .contains("ContractValidationRequest", "ContractValidationResponse", "Problem");
        assertThat(api.getComponents().getParameters().keySet())
            .contains("IdempotencyKey", "ExpectedVersion");
    }

    @Test
    void everyProjectResourceIsProjectScopedAndHasNoCallerIdentityParameter() {
        assertThat(REQUIREMENT_OPERATIONS.values())
            .allSatisfy(signature -> assertThat(signature.substring(signature.indexOf(' ') + 1))
                .startsWith("/v1/projects/{projectId}/"));
        allOperations().forEach(operation -> assertThat(parameters(operation))
            .allSatisfy(parameter -> assertThat(parameter.getName())
                .isNotIn("tenantId", "tenant_id", "actorId")));
    }

    @Test
    void everyRequirementMutationHasConcurrencyIdempotencyAndProblemGuards() {
        var mutationIds = REQUIREMENT_OPERATIONS.entrySet().stream()
            .filter(entry -> !entry.getValue().startsWith("GET "))
            .map(Map.Entry::getKey).collect(Collectors.toSet());
        mutationIds.forEach(operationId -> {
            var operation = operation(operationId);
            var refs = parameters(operation).stream().map(Parameter::get$ref)
                .filter(Objects::nonNull).toList();
            assertThat(refs).contains("#/components/parameters/IdempotencyKey",
                "#/components/parameters/ExpectedVersion",
                "#/components/parameters/BrowserCsrfToken");
            assertThat(requiredRequestFields(operation)).contains("expected_version");
            assertThat(extensions(operation)).containsEntry(
                "x-browser-csrf-required", "conditional");
            assertThat(operation.getResponses().getDefault().get$ref())
                .isEqualTo("#/components/responses/ProblemResponse");
        });
    }

    @Test
    void bilateralConfirmationsAloneRequireCanonicalFreshAuthProof() {
        var proof = api.getComponents().getParameters().get("FreshAuthProof");
        assertThat(proof.getName()).isEqualTo("X-Accord-Fresh-Auth");
        assertThat(proof.getIn()).isEqualTo("header");
        assertThat(proof.getRequired()).isTrue();
        assertThat(proof.getSchema().getType()).isEqualTo("string");
        assertThat(proof.getSchema().getFormat()).isEqualTo("uuid");

        var methods = declaredControllerMethods();
        assertThat(methods.keySet()).containsExactlyInAnyOrderElementsOf(REQUIREMENT_OPERATIONS.keySet());
        REQUIREMENT_OPERATIONS.keySet().forEach(operationId -> {
            var operation = operation(operationId);
            var proofCount = parameters(operation).stream()
                .map(Parameter::get$ref)
                .filter("#/components/parameters/FreshAuthProof"::equals).count();
            var headers = Arrays.stream(methods.get(operationId).getParameters())
                .map(parameter -> parameter.getAnnotation(RequestHeader.class))
                .filter(Objects::nonNull)
                .filter(header -> "X-Accord-Fresh-Auth".equals(
                    header.name().isBlank() ? header.value() : header.name())).toList();
            if (FRESH_AUTH_OPERATIONS.contains(operationId)) {
                assertThat(proofCount).isOne();
                assertThat(extensions(operation)).containsEntry("x-fresh-auth", "single_action");
                assertThat(headers).singleElement().satisfies(header -> assertThat(header.required()).isTrue());
            } else {
                assertThat(proofCount).isZero();
                assertThat(extensions(operation)).doesNotContainKey("x-fresh-auth");
                assertThat(headers).isEmpty();
            }
        });
    }

    @Test
    void publishedRoutesCloseOverExactlyOneRealSpringHandler() {
        var declared = declaredControllerMethods();
        declared.forEach((operationId, method) -> assertThat(method.getName()).isEqualTo(operationId));
        var controllerTypes = CONTROLLERS.stream().map(Class::getSimpleName).collect(Collectors.toSet());
        var actual = new HashSet<RequirementRuntimeRoute>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            if (!controllerTypes.contains(handler.getBeanType().getSimpleName())) return;
            mapping.getPatternValues().forEach(path -> mapping.getMethodsCondition().getMethods()
                .forEach(httpMethod -> {
                    var annotation = handler.getMethodAnnotation(Operation.class);
                    actual.add(new RequirementRuntimeRoute(
                        annotation == null ? "<missing>" : annotation.operationId(),
                        httpMethod.name(), path,
                        handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName()));
                }));
        });
        var expected = REQUIREMENT_OPERATIONS.entrySet().stream().map(entry -> {
            var operation = operation(entry.getKey());
            var controllerMethod = (String) extensions(operation).get("x-controller-method");
            assertThat(controllerMethod).isEqualTo(
                declared.get(entry.getKey()).getDeclaringClass().getSimpleName()
                    + "#" + declared.get(entry.getKey()).getName());
            var split = entry.getValue().indexOf(' ');
            return new RequirementRuntimeRoute(entry.getKey(), entry.getValue().substring(0, split),
                entry.getValue().substring(split + 1), controllerMethod);
        }).collect(Collectors.toSet());
        assertThat(actual).hasSize(REQUIREMENT_OPERATIONS.size()).isEqualTo(expected);
    }

    private List<PublishedOperation> publishedOperations() {
        var result = new ArrayList<PublishedOperation>();
        api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) ->
            result.add(new PublishedOperation(operation.getOperationId(), method.name() + " " + path,
                operation.getExtensions() == null ? null : operation.getExtensions().get("x-accord-owner")))));
        return result;
    }

    private List<io.swagger.v3.oas.models.Operation> allOperations() {
        return api.getPaths().values().stream().flatMap(item -> item.readOperations().stream()).toList();
    }

    private io.swagger.v3.oas.models.Operation operation(String operationId) {
        return allOperations().stream().filter(candidate -> operationId.equals(candidate.getOperationId()))
            .findFirst().orElseThrow();
    }

    private List<Parameter> parameters(io.swagger.v3.oas.models.Operation operation) {
        return operation.getParameters() == null ? List.of() : operation.getParameters();
    }

    private Map<String, Object> extensions(io.swagger.v3.oas.models.Operation operation) {
        return operation.getExtensions() == null ? Map.of() : operation.getExtensions();
    }

    private Schema<?> resolve(Schema<?> schema) {
        if (schema.get$ref() == null) return schema;
        return api.getComponents().getSchemas().get(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
    }

    private Set<String> requiredRequestFields(io.swagger.v3.oas.models.Operation operation) {
        var root = resolve(operation.getRequestBody().getContent().get("application/json").getSchema());
        var branches = root.getOneOf() == null ? List.of(root)
            : root.getOneOf().stream().map(this::resolve).toList();
        var common = new HashSet<>(required(branches.getFirst()));
        branches.stream().skip(1).forEach(branch -> common.retainAll(required(branch)));
        return Set.copyOf(common);
    }

    private List<String> required(Schema<?> schema) {
        return schema.getRequired() == null ? List.of() : schema.getRequired();
    }

    private Map<String, Method> declaredControllerMethods() {
        return CONTROLLERS.stream().flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .filter(method -> method.isAnnotationPresent(Operation.class))
            .collect(Collectors.toMap(
                method -> method.getAnnotation(Operation.class).operationId(), Function.identity()));
    }

    private record PublishedOperation(String operationId, String signature, Object owner) {}
    private record RequirementRuntimeRoute(
        String operationId, String method, String path, String controllerMethod) {}
}
```

At this M1 checkpoint, `x-accord-owner: requirement-workflow` and an exact `x-controller-method: <ControllerSimpleName>#<methodName>` are required on exactly the 47 operations above. The full canonical API already contains Foundation and Identity public operations; this test checks global operationId uniqueness, retains the post-Identity `validateContract` route, and relies on the Identity public contract test in the same `:tests:api:test` suite to assert the exact `identity-public` owner set. The runtime half loads the packaged Spring application and requires every documented operation ID, HTTP method, full path, controller class, and controller method to resolve to exactly one real handler; the extension must equal both the declared annotated method and runtime handler, and an extra mapping on any of the five owner controllers also fails. The exact two-entry `requirementFreshAuthOperations` set closes the `MF` profile bidirectionally across the shared OpenAPI `FreshAuthProof`, `x-fresh-auth: single_action`, and one required Controller header, so generated clients cannot silently omit confirmation proof. Every later backend plan must retain all owner sets; after Agent Context Task 13 introduces `ownership-manifest.yaml`, the cumulative merge test becomes the cross-owner deletion gate and this Requirement test continues to assert its own exact 47-operation set.

- [ ] **Step 2: Run the contract test and observe the missing operations**

Run: `./gradlew :tests:api:test --tests '*RequirementApiContractTest'`

Expected: FAIL because the canonical document still contains only the Foundation operations and the new controller operation IDs are absent.

- [ ] **Step 3: Extend the canonical OpenAPI with the exact production operation matrix**

Retain every existing path, operation, schema, parameter, response, security scheme, tag, and extension in `accord-control-api.yaml`; this task adds to the cumulative document and never replaces it. Reuse the Identity public contract's `browserSession` cookie and `oidc` security schemes as operation-level alternatives (`browserSession: []` or the row's OIDC scope), and reuse its conditional-CSRF extension/header instead of defining a second authentication model. Do not add tenant or caller actor parameters. Tenant, account, natural person, membership, role bindings, and authentication time come only from the server-created `VerifiedRequestIdentity`.

Use these exact header profiles:

| Profile | Request contract | Success contract |
|---|---|---|
| `Q` | One of HttpOnly `browserSession` cookie or OIDC bearer; optional `If-None-Match: "<version>"` | `200` with quoted numeric `ETag` and `Cache-Control: no-store`, or `304` with the same `ETag` and no body |
| `M` | One of HttpOnly `browserSession` cookie or OIDC bearer; required `Idempotency-Key`, required `If-Match: "<version>"`; JSON body requires `expected_version` equal to `If-Match`; optional shared `X-CSRF-Token` parameter plus `x-browser-csrf-required: conditional` | Declared `200`, `201`, or `202` with quoted numeric `ETag`; an identical retry returns byte-identical status/body/ETag and a changed fingerprint returns `409 IDEMPOTENCY_KEY_REUSED` |
| `MF` | All `M` headers plus required `X-Accord-Fresh-Auth: <fresh-auth-session UUID>` | Same as `M`; the server consumes/validates fresh authentication in the command transaction |
| `S` | One of HttpOnly `browserSession` cookie or OIDC bearer; optional `Last-Event-ID` and optional `after`, which must agree when both exist | `200 text/event-stream`, `Cache-Control: no-store`, `X-Accel-Buffering: no`, heartbeat comments, and monotonically increasing `id` values; no ETag or intermediary replay |

Every `Q` operation uses `IfNoneMatch`, `ETag`, `NoStore`, `ProblemResponse`, and its declared response schema. Every `M`/`MF` operation uses `IdempotencyKey`, `ExpectedVersion`, `BrowserCsrfToken`, `ETag`, `ProblemResponse`, and `x-browser-csrf-required: conditional`; exactly the two `MF` confirmations also reference the shared required UUID `FreshAuthProof` parameter and declare `x-fresh-auth: single_action`. The security filter requires a valid `X-CSRF-Token`, matching allowed `Origin`, and same-site fetch metadata when the resolved authentication is `browserSession`; it rejects the request before controller invocation when any check fails. Bearer-authenticated API/CI clients do not send or require CSRF, and a CSRF header never substitutes for authentication. SSE is the only non-ETag read because it is an unbounded stream; recovery is the versioned `Q` replay resource.

| operationId | Method and path | Query or JSON request | Success response | Profile | OAuth scope | Additional problem codes |
|---|---|---|---|---|---|---|
| `listRequirements` | `GET /v1/projects/{projectId}/requirements` | `cursor?`, `limit=1..100`, `query?`, `business_domain?`, `block_type?`, `risk?`, `phase?` | `200 RequirementPageDto`, `304` | `Q` | `requirement:read` | none |
| `getRequirementGraph` | `GET /v1/projects/{projectId}/requirements/graph` | `at_sequence?`, `domain?`, `block_type?`, `risk?`, `phase?` | `200 RequirementWorkspaceEnvelope`, `304` | `Q` | `requirement:read` | none |
| `getRequirement` | `GET /v1/projects/{projectId}/requirements/{requirementId}` | none | `200 RequirementDetailDto`, `304` | `Q` | `requirement:read` | none |
| `listRequirementRevisions` | `GET /v1/projects/{projectId}/requirements/{requirementId}/revisions` | `cursor?`, `limit=1..100` | `200 RequirementRevisionPageDto`, `304` | `Q` | `requirement:read` | none |
| `getRequirementRevision` | `GET /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}` | none | `200 RequirementRevisionDto`, `304` | `Q` | `requirement:read` | none |
| `getRequirementBusinessProjection` | `GET /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}/business-projection` | none | `200 BusinessProjectionDto`, `304` | `Q` | `requirement:read` | none |
| `getRequirementDevelopmentProjection` | `GET /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}/development-projection` | none | `200 DevelopmentProjectionDto`, `304` | `Q` | `requirement:read` | none |
| `compareRequirementRevisions` | `GET /v1/projects/{projectId}/requirements/{requirementId}/revisions/compare` | required `from_revision_no`, `to_revision_no` | `200 RevisionComparisonDto`, `304` | `Q` | `requirement:read` | none |
| `createRequirementDraft` | `POST /v1/projects/{projectId}/requirement-drafts` | discriminator `CreateRequirementDraftRequest` (`create_requirement` or `revise_existing`) | `201 CommandReceiptDto` | `M` | `requirement:write` | `HASH_CONFLICT`, `FIELD_OWNERSHIP_VIOLATION` |
| `getRequirementDraft` | `GET /v1/projects/{projectId}/requirement-drafts/{draftId}` | none | `200 RequirementDraftDto`, `304` | `Q` | `requirement:write` | `DRAFT_EXPIRED` |
| `updateRequirementDraft` | `PATCH /v1/projects/{projectId}/requirement-drafts/{draftId}` | `UpdateRequirementDraftRequest` | `200 CommandReceiptDto` | `M` | `requirement:write` | `DRAFT_EXPIRED` |
| `extractRequirementDraft` | `POST /v1/projects/{projectId}/requirement-drafts/{draftId}:extract` | `ExtractRequirementDraftRequest` | `202 CommandReceiptDto` | `M` | `requirement:write` | `DRAFT_EXPIRED`, `EXTRACTION_STALE` |
| `getRequirementDraftExtraction` | `GET /v1/projects/{projectId}/requirement-drafts/{draftId}/extractions/{extractionId}` | none | `200 DraftExtractionDto`, `304` | `Q` | `requirement:write` | `DRAFT_EXPIRED` |
| `createRequirementDraftTranscriptionUpload` | `POST /v1/projects/{projectId}/requirement-drafts/{draftId}/transcription-uploads` | `CreateTranscriptionUploadRequest` | `201 TranscriptionUploadSessionDto` | `M` | `requirement:write` | `DRAFT_EXPIRED` |
| `completeRequirementDraftTranscriptionUpload` | `POST /v1/projects/{projectId}/requirement-drafts/{draftId}/transcription-uploads/{uploadId}:complete` | `CompleteTranscriptionUploadRequest` | `202 CommandReceiptDto` | `M` | `requirement:write` | `DRAFT_EXPIRED`, `UPLOAD_PART_MISMATCH` |
| `getRequirementDraftTranscription` | `GET /v1/projects/{projectId}/requirement-drafts/{draftId}/transcriptions/{transcriptionId}` | none | `200 DraftTranscriptionDto`, `304` | `Q` | `requirement:write` | `DRAFT_EXPIRED` |
| `submitRequirementDraft` | `POST /v1/projects/{projectId}/requirement-drafts/{draftId}:submit` | `SubmitRequirementDraftRequest` | `201 RequirementDraftSubmissionReceiptDto` | `M` | `requirement:write` | `DRAFT_EXPIRED`, `EXTRACTION_STALE`, `BLOCKING_QUESTIONS_UNRESOLVED`, `RELATION_CONFIRMATION_REQUIRED`, `TRANSCRIPT_NOT_CONFIRMED`, `AUDIO_DISPOSITION_INVALID`, `CLASSIFICATION_CONFIRMATION_REQUIRED`, `HASH_CONFLICT` |
| `listAttachments` | `GET /v1/projects/{projectId}/attachments` | `cursor?`, `limit=1..100`, `draft_id?`, `requirement_id?`, `state?` | `200 AttachmentPageDto`, `304` | `Q` | `attachment:read` | none |
| `createAttachmentUpload` | `POST /v1/projects/{projectId}/attachment-uploads` | `CreateAttachmentUploadRequest` | `201 CommandReceiptDto` | `M` | `attachment:write` | none |
| `getAttachmentUpload` | `GET /v1/projects/{projectId}/attachment-uploads/{uploadId}` | none | `200 AttachmentUploadDto`, `304` | `Q` | `attachment:write` | `UPLOAD_SESSION_EXPIRED` |
| `signAttachmentUploadPart` | `POST /v1/projects/{projectId}/attachment-uploads/{uploadId}/parts/{partNumber}:sign` | `SignAttachmentUploadPartRequest` | `201 ScopedAttachmentCapabilityDto` | `M` | `attachment:write` | `UPLOAD_SESSION_EXPIRED`, `UPLOAD_PART_MISMATCH` |
| `listAttachmentUploadParts` | `GET /v1/projects/{projectId}/attachment-uploads/{uploadId}/parts` | `cursor?`, `limit=1..1000` | `200 AttachmentUploadPartPageDto`, `304` | `Q` | `attachment:write` | `UPLOAD_SESSION_EXPIRED` |
| `completeAttachmentUpload` | `POST /v1/projects/{projectId}/attachment-uploads/{uploadId}:complete` | `CompleteAttachmentUploadRequest` | `202 CommandReceiptDto` | `M` | `attachment:write` | `UPLOAD_SESSION_EXPIRED`, `UPLOAD_PART_MISMATCH` |
| `abortAttachmentUpload` | `POST /v1/projects/{projectId}/attachment-uploads/{uploadId}:abort` | `AbortAttachmentUploadRequest` | `200 CommandReceiptDto` | `M` | `attachment:write` | `UPLOAD_SESSION_EXPIRED` |
| `getAttachmentVersion` | `GET /v1/projects/{projectId}/attachments/{attachmentId}/versions/{version}` | none | `200 AttachmentVersionDto`, `304` | `Q` | `attachment:read` | `ATTACHMENT_ACCESS_DENIED` |
| `getAttachmentStatus` | `GET /v1/projects/{projectId}/attachments/{attachmentId}/versions/{version}/status` | none | `200 AttachmentStatusDto`, `304` | `Q` | `attachment:read` | `ATTACHMENT_ACCESS_DENIED` |
| `createAttachmentPreviewCapability` | `POST /v1/projects/{projectId}/attachments/{attachmentId}/versions/{version}/preview-capabilities` | `CreateAttachmentCapabilityRequest` | `201 ScopedAttachmentCapabilityDto` | `M` | `attachment:read` | `ATTACHMENT_QUARANTINED`, `ATTACHMENT_SCANNING`, `ATTACHMENT_ACCESS_DENIED` |
| `createAttachmentDownloadCapability` | `POST /v1/projects/{projectId}/attachments/{attachmentId}/versions/{version}/download-capabilities` | `CreateAttachmentCapabilityRequest` | `201 ScopedAttachmentCapabilityDto` | `M` | `attachment:read` | `ATTACHMENT_QUARANTINED`, `ATTACHMENT_SCANNING`, `ATTACHMENT_ACCESS_DENIED` |
| `runAttachmentAccessPreflight` | `POST /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}/attachment-access-preflight` | `RunAttachmentAccessPreflightRequest` | `200 AttachmentAccessPreflightDto` | `M` | `attachment:read` | `ATTACHMENT_ACCESS_DENIED`, `ACCESS_PREFLIGHT_FAILED`, `HASH_CONFLICT` |
| `createDevelopmentProposal` | `POST /v1/projects/{projectId}/requirements/{requirementId}/development-proposals` | `CreateDevelopmentProposalRequest` | `201 CommandReceiptDto` | `M` | `collaboration:write` | `FIELD_OWNERSHIP_VIOLATION`, `HASH_CONFLICT` |
| `listDevelopmentProposals` | `GET /v1/projects/{projectId}/requirements/{requirementId}/development-proposals` | `cursor?`, `limit=1..100`, `status?` | `200 DevelopmentProposalPageDto`, `304` | `Q` | `requirement:read` | none |
| `getDevelopmentProposal` | `GET /v1/projects/{projectId}/requirements/{requirementId}/development-proposals/{proposalId}` | none | `200 DevelopmentProposalDto`, `304` | `Q` | `requirement:read` | none |
| `resolveDevelopmentProposal` | `POST /v1/projects/{projectId}/requirements/{requirementId}/development-proposals/{proposalId}:resolve` | `ResolveDevelopmentProposalRequest` | `200 CommandReceiptDto` | `M` | `collaboration:write` | `PROPOSAL_SUPERSEDED`, `PARTIAL_ACCEPT_SELECTION_REQUIRED`, `HASH_CONFLICT` |
| `listRequirementBusinessQuestions` | `GET /v1/projects/{projectId}/requirements/{requirementId}/business-questions` | `cursor?`, `limit=1..100`, `status?`, `blocking?` | `200 BusinessQuestionPageDto`, `304` | `Q` | `requirement:read` | none |
| `createRequirementBusinessQuestion` | `POST /v1/projects/{projectId}/requirements/{requirementId}/business-questions` | `CreateBusinessQuestionRequest` | `201 CommandReceiptDto` | `M` | `collaboration:write` | `QUESTION_TARGET_NOT_DEVELOPMENT_OWNED`, `HASH_CONFLICT` |
| `getRequirementBusinessQuestion` | `GET /v1/projects/{projectId}/requirements/{requirementId}/business-questions/{questionId}` | none | `200 BusinessQuestionDto`, `304` | `Q` | `requirement:read` | none |
| `addRequirementBusinessQuestionMessage` | `POST /v1/projects/{projectId}/requirements/{requirementId}/business-questions/{questionId}/messages` | `AddBusinessQuestionMessageRequest` | `201 CommandReceiptDto` | `M` | `collaboration:write` | `QUESTION_SUPERSEDED`, `QUESTION_NOT_OPEN`, `HASH_CONFLICT` |
| `resolveRequirementBusinessQuestion` | `POST /v1/projects/{projectId}/requirements/{requirementId}/business-questions/{questionId}:resolve` | `ResolveBusinessQuestionRequest` | `200 CommandReceiptDto` | `M` | `collaboration:write` | `QUESTION_SUPERSEDED`, `QUESTION_NOT_OPEN`, `QUESTION_ANSWER_REQUIRED`, `HASH_CONFLICT` |
| `listActionRequests` | `GET /v1/projects/{projectId}/action-requests` | optional `cursor`, `limit=1..100`, `phase?`, `type?` | `200 ActionRequestPageDto`, `304` | `Q` | `action:read` | none |
| `getActionRequest` | `GET /v1/projects/{projectId}/action-requests/{actionRequestId}` | none | `200 ActionRequestEnvelope`, `304` | `Q` | `action:read` | none |
| `completeActionRequest` | `POST /v1/projects/{projectId}/action-requests/{actionRequestId}:complete` | `CompleteActionRequestRequest` | `200 CommandReceiptDto` | `M` | `action:write` | `ACTION_REQUEST_SUPERSEDED`, `ACTION_REQUEST_NOT_OPEN`, `HASH_CONFLICT` |
| `declineActionRequest` | `POST /v1/projects/{projectId}/action-requests/{actionRequestId}:decline` | `DeclineActionRequestRequest` | `200 CommandReceiptDto` | `M` | `action:write` | `ACTION_REQUEST_SUPERSEDED`, `ACTION_REQUEST_NOT_OPEN`, `DECLINE_REASON_REQUIRED`, `HASH_CONFLICT` |
| `delegateActionRequest` | `POST /v1/projects/{projectId}/action-requests/{actionRequestId}:delegate` | `DelegateActionRequestRequest` | `200 CommandReceiptDto` | `M` | `action:write` | `ACTION_REQUEST_SUPERSEDED`, `ACTION_REQUEST_NOT_OPEN`, `DELEGATION_NOT_ALLOWED`, `HASH_CONFLICT` |
| `confirmRequirementDevelopment` | `POST /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}:confirm-development` | `ConfirmRequirementRequest` | `201 ConfirmationReceiptDto` | `MF` | `collaboration:confirm` | confirmation codes below except `DEVELOPMENT_CONFIRMATION_REQUIRED` |
| `confirmRequirementBusiness` | `POST /v1/projects/{projectId}/requirements/{requirementId}/revisions/{revisionNo}:confirm-business` | `ConfirmRequirementRequest` | `201 ConfirmationReceiptDto` | `MF` | `collaboration:confirm` | all confirmation codes below |
| `streamProjectEvents` | `GET /v1/projects/{projectId}/events` | `Last-Event-ID?`, `after?`, heartbeat `15..60` seconds | `200 ServerSentEvent<OrderedServerEvent>` | `S` | `event:read` | `EVENT_CURSOR_INVALID` |
| `replayProjectEvents` | `GET /v1/projects/{projectId}/events/replay` | required `after`, optional `limit=1..1000` | `200 ProjectEventReplayDto`, `304`, or `410` | `Q` | `event:read` | `EVENT_CURSOR_INVALID`, `REPLAY_WINDOW_EXPIRED` |

All operations can return these exact common RFC 7807 codes; a code has one HTTP status everywhere:

```yaml
x-accord-problem-statuses:
  AUTHENTICATION_REQUIRED: 401
  AUTHORIZATION_DENIED: 403
  CSRF_VALIDATION_FAILED: 403
  RESOURCE_NOT_FOUND: 404
  VERSION_CONFLICT: 409
  HASH_CONFLICT: 409
  IDEMPOTENCY_KEY_REUSED: 409
  COMMAND_IN_PROGRESS: 409
  VALIDATION_FAILED: 422
  RATE_LIMITED: 429
  DRAFT_EXPIRED: 410
  EXTRACTION_STALE: 409
  BLOCKING_QUESTIONS_UNRESOLVED: 422
  CLASSIFICATION_CONFIRMATION_REQUIRED: 422
  TRANSCRIPT_NOT_CONFIRMED: 422
  ATTACHMENT_QUARANTINED: 423
  ATTACHMENT_SCANNING: 425
  ATTACHMENT_ACCESS_DENIED: 403
  UPLOAD_SESSION_EXPIRED: 410
  UPLOAD_PART_MISMATCH: 409
  ACCESS_PREFLIGHT_FAILED: 422
  FIELD_OWNERSHIP_VIOLATION: 422
  PROPOSAL_SUPERSEDED: 409
  PARTIAL_ACCEPT_SELECTION_REQUIRED: 422
  QUESTION_TARGET_NOT_DEVELOPMENT_OWNED: 422
  QUESTION_SUPERSEDED: 409
  QUESTION_NOT_OPEN: 409
  QUESTION_ANSWER_REQUIRED: 422
  ACTION_REQUEST_SUPERSEDED: 409
  ACTION_REQUEST_NOT_OPEN: 409
  DELEGATION_NOT_ALLOWED: 422
  DECLINE_REASON_REQUIRED: 422
  DEVELOPMENT_CONFIRMATION_REQUIRED: 409
  CONFIRMATION_HASH_STALE: 409
  FRESH_AUTH_REQUIRED: 403
  STRICT_SEPARATION_REQUIRED: 403
  ATTACHMENT_ACCESS_BLOCKED: 422
  CONTEXT_STALE: 409
  ASSESSMENT_GATE_FAILED: 422
  ROLE_BINDING_STALE: 409
  EVENT_CURSOR_INVALID: 400
  REPLAY_WINDOW_EXPIRED: 410
```

`Problem` adds optional `current_version`, `current_hash`, `retry_after_seconds`, and `action_request_id`; it never includes inaccessible object IDs or field values. `409 VERSION_CONFLICT` includes the current ETag and a safe comparison link only after authorization. `404` response shape, size class, and timing budget are identical for absent and cross-tenant resources.

- [ ] **Step 4: Define the shared wire envelope and all requirement DTOs**

Create `ApiContractDtos.java`; these are wire-only types and must not be reused as persistence/domain models. The canonical ObjectMapper uses `SNAKE_CASE`, rejects unknown command properties, and serializes the enum values shown by OpenAPI.

```java
package com.inforvans.accord.platform.api;

public final class ApiContractDtos {
    private ApiContractDtos() {}

    public enum FreshAuthRequirement {
        NONE("none"), WINDOW("window"), SINGLE_ACTION("single_action");
        private final String wireValue;
        FreshAuthRequirement(String wireValue) { this.wireValue = wireValue; }
        @JsonValue public String wireValue() { return wireValue; }
    }

    public enum CommandReceiptState { ACCEPTED, COMPLETED }

    @Schema(name = "AllowedAction")
    public record AllowedActionDto(
        String key, String operationId, URI commandHref, String method,
        long expectedVersion, String expectedHash, boolean requiresIdempotencyKey,
        FreshAuthRequirement freshAuth, boolean enabled, String disabledReasonCode
    ) {}

    @Schema(name = "ObjectRef")
    public record ObjectRefDto(String type, UUID id, long version, String hash) {}

    @Schema(name = "DisplayState")
    public record DisplayStateDto(
        String stageLabel, String nextActionLabel, List<String> blockerLabels,
        String originalStageLabel
    ) {}

    public interface ActionBearingDto {
        ObjectRefDto objectRef();
        DisplayStateDto displayState();
        List<AllowedActionDto> allowedActions();
    }

    public record PageMetaDto(String nextCursor, long snapshotSequence) {}

    @Schema(name = "CommandReceipt")
    public record CommandReceiptDto(
        UUID commandId, CommandReceiptState state, ObjectRefDto resource,
        long resultingVersion, String resultingHash, boolean replayed, Instant recordedAt
    ) {}

    public record CommandHeaders(
        String idempotencyKey, String ifMatch, UUID freshAuthSessionId
    ) {
        public CommandHeaders(String idempotencyKey, String ifMatch) {
            this(idempotencyKey, ifMatch, null);
        }
    }

    public record VersionedApiResult<T>(T body, long version) {
        public ResponseEntity<T> toHttp(String ifNoneMatch) {
            var etag = quoted(version);
            if (matchesIfNoneMatch(ifNoneMatch, etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag).cacheControl(CacheControl.noStore()).build();
            }
            return ResponseEntity.ok().eTag(etag)
                .cacheControl(CacheControl.noStore()).body(body);
        }
    }

    public record MutationApiResult<T>(T body, long version) {
        public ResponseEntity<T> toHttp(HttpStatus status) {
            return ResponseEntity.status(status).eTag(quoted(version))
                .cacheControl(CacheControl.noStore()).body(body);
        }
    }

    @Schema(name = "ScopedCapability")
    public record ScopedCapabilityDto(
        UUID capabilityId, URI url, String method, Map<String, String> requiredHeaders,
        Instant expiresAt, int maximumUses, String contentDisposition,
        CommandReceiptDto receipt
    ) {}

    private static String quoted(long version) { return "\"" + version + "\""; }

    private static boolean matchesIfNoneMatch(String header, String etag) {
        if (header == null || header.isBlank()) return false;
        return Arrays.stream(header.split(","))
            .map(String::trim)
            .map(candidate -> candidate.startsWith("W/") ? candidate.substring(2) : candidate)
            .anyMatch(candidate -> candidate.equals("*") || candidate.equals(etag));
    }
}
```

Annotate every Java wire record ending in `Dto` with an explicit OpenAPI schema name that removes only that suffix; for example, `RequirementPageDto -> RequirementPage`, `CommandReceiptDto -> CommandReceipt`, and `ProjectEventReplayDto -> ProjectEventReplay`. Publish the shared schemas as `AllowedAction`, `ObjectRef`, and `DisplayState`. Every action-bearing response schema requires `object_ref`, `display_state`, and `allowed_actions`; `AllowedAction.command_href` is a same-origin path selected by the server, never an absolute third-party URL, and its `operation_id`, method, expected version/hash, idempotency requirement, and fresh-auth mode must match the referenced OpenAPI operation. Add this assertion to `RequirementApiContractTest`:

```java
@Test
void everyActionBearingSchemaUsesTheUniformServerActionEnvelope() {
    var names = Set.of(
        "RequirementPage", "RequirementWorkspaceEnvelope", "RequirementDetail",
        "RequirementRevisionPage", "RequirementRevision", "BusinessProjection",
        "DevelopmentProjection", "RevisionComparison", "RequirementDraft",
        "DraftExtraction", "DraftTranscription", "AttachmentPage", "AttachmentUpload",
        "AttachmentUploadPartPage", "AttachmentVersion", "AttachmentStatus",
        "DevelopmentProposalPage", "DevelopmentProposal", "ActionRequestPage",
        "BusinessQuestionPage", "BusinessQuestion", "ActionRequestEnvelope",
    );
    names.forEach(name -> assertThat(api.getComponents().getSchemas().get(name).getRequired())
        .contains("object_ref", "display_state", "allowed_actions"));
}

@Test
void everyNestedWireRecordHasAnExplicitStableSchemaName() {
    var containers = List.of(
        ApiContractDtos.class, RequirementApiDtos.class, AttachmentApiDtos.class,
        CollaborationApiDtos.class, ActionRequestApiDtos.class, ProjectEventDtos.class);
    var records = containers.stream().flatMap(type -> Arrays.stream(type.getDeclaredClasses()))
        .filter(Class::isRecord)
        .filter(type -> type.getSimpleName().endsWith("Dto"))
        .toList();

    assertThat(records).isNotEmpty().allSatisfy(type -> {
        var annotation = type.getAnnotation(Schema.class);
        assertThat(annotation).as(type.getName()).isNotNull();
        assertThat(annotation.name()).as(type.getName())
            .isEqualTo(type.getSimpleName().substring(0, type.getSimpleName().length() - 3));
    });
}

@Test
void draftAndRevisionSchemasPreserveTheCompleteBusinessRoundTrip() {
    assertThat(schema("DraftForm").getRequired()).contains(
        "business_domain", "primary_block_type", "classification_source", "title",
        "intent", "scope", "blocks", "edge_cases", "success_metrics",
        "acceptance_criteria", "questions", "relation_suggestions");
    assertThat(property(schema("DraftRelationSuggestion"), "decision").getEnum())
        .containsExactly("pending", "accepted", "rejected");
    assertThat(schema("DraftRelationEndpoint").getDiscriminator().getPropertyName())
        .isEqualTo("endpoint_kind");
    assertThat(schema("DraftRelationEndpoint").getDiscriminator().getMapping().keySet())
        .containsExactlyInAnyOrder("local_block", "existing_graph_node");
    assertThat(schema("RequirementGraphNodeRef").getDiscriminator().getPropertyName())
        .isEqualTo("node_kind");
    assertThat(schema("RequirementGraphNodeRef").getDiscriminator().getMapping().keySet())
        .containsExactlyInAnyOrder("requirement", "block");
    assertThat(schema("RequirementRevision").getRequired()).contains(
        "intent", "scope", "blocks", "relations", "edge_cases", "success_metrics",
        "acceptance_criteria", "contractual_attachments", "development_view",
        "accepted_unknowns", "semantic_decisions", "field_sources");
    assertThat(responseSchema(operation("submitRequirementDraft"), "201").get$ref())
        .isEqualTo("#/components/schemas/RequirementDraftSubmissionReceipt");
    assertThat(schema("RequirementDraftSubmissionReceipt").getRequired()).contains(
        "command_receipt", "requirement_id", "revision_no", "revision_hash",
        "audio_disposition");
}
```

Create `RequirementApiDtos.java` with the complete requirement read, draft, extraction, transcription, and command surface:

```java
package com.inforvans.accord.requirement.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.inforvans.accord.controlplane.security.VerifiedRequestIdentity;
import com.inforvans.accord.platform.api.ApiContractDtos.ActionBearingDto;
import com.inforvans.accord.platform.api.ApiContractDtos.AllowedActionDto;
import com.inforvans.accord.platform.api.ApiContractDtos.CommandHeaders;
import com.inforvans.accord.platform.api.ApiContractDtos.CommandReceiptDto;
import com.inforvans.accord.platform.api.ApiContractDtos.DisplayStateDto;
import com.inforvans.accord.platform.api.ApiContractDtos.MutationApiResult;
import com.inforvans.accord.platform.api.ApiContractDtos.ObjectRefDto;
import com.inforvans.accord.platform.api.ApiContractDtos.PageMetaDto;
import com.inforvans.accord.platform.api.ApiContractDtos.ScopedCapabilityDto;
import com.inforvans.accord.platform.api.ApiContractDtos.VersionedApiResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class RequirementApiDtos {
    private RequirementApiDtos() {}

    public interface WireEnum {
        @JsonValue
        default String wireValue() {
            return ((Enum<?>) this).name().toLowerCase(Locale.ROOT);
        }
    }

    public enum RequirementDisplayPhase implements WireEnum { DRAFT, UNDER_REVIEW, AWAITING_DEVELOPMENT_CONFIRMATION, AWAITING_BUSINESS_CONFIRMATION, READY, HELD, SUPERSEDED, WITHDRAWN }
    public enum RequirementRiskLevel implements WireEnum { UNKNOWN, LOW, MEDIUM, HIGH, CRITICAL }
    public enum DraftSourceType implements WireEnum { TEXT, SPEECH, MIXED }
    public enum DraftIntent implements WireEnum { CREATE_REQUIREMENT, REVISE_EXISTING }
    public enum ClassificationSource implements WireEnum { USER_SELECTED, CANVAS_PREFILL, AGENT_SUGGESTED }
    public enum DraftExtractionState implements WireEnum { QUEUED, RUNNING, NEEDS_INPUT, COMPLETED, FAILED, STALE }
    public enum TranscriptionState implements WireEnum { UPLOADING, PROCESSING, READY, FAILED }
    public enum RequestedAudioDisposition implements WireEnum { NOT_APPLICABLE, DELETE_AFTER_SUBMIT, RETAIN_AS_REFERENCE }
    public enum AudioDispositionState implements WireEnum { NOT_APPLICABLE, DELETION_PENDING, DELETED, DELETION_FAILED, RETAINED_AS_REFERENCE }
    public enum DraftGapKind implements WireEnum { GOAL, CURRENT_PROBLEM, TARGET_RESULT, SCENARIO, RULE, SCOPE, EDGE_CASE, METRIC, ACCEPTANCE, RELATION, OTHER }
    public enum DraftRelationSource implements WireEnum { AGENT_SUGGESTED, USER_ADDED }
    public enum DraftRelationReviewTier implements WireEnum { STANDARD, PROMINENT }
    public enum DraftRelationDecision implements WireEnum { PENDING, ACCEPTED, REJECTED }
    public enum RequirementBlockType implements WireEnum { USER_SCENARIO, BUSINESS_RULE, WORKFLOW_STATE, DATA, PERMISSION, REPORT_NOTIFICATION, NON_FUNCTIONAL, EXTENSION }
    public enum RequirementRelationType implements WireEnum { PRECEDES, DEPENDS_ON, TRIGGERS, CONSTRAINS, AFFECTS, CONFLICTS_WITH, SUPERSEDES, SPLIT_FROM, RELATES_TO }
    public enum BusinessRuleKindDto implements WireEnum { CONSTRAINT, CALCULATION, ELIGIBILITY, BOUNDARY }
    public enum DataClassificationDto implements WireEnum { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }
    public enum PermissionEffectDto implements WireEnum { ALLOW, DENY }
    public enum CommunicationArtifactKindDto implements WireEnum { REPORT, NOTIFICATION, REMINDER, ESCALATION }
    public enum QualityAttributeDto implements WireEnum { PERFORMANCE, SECURITY, AVAILABILITY, COMPATIBILITY, MIGRATION, ACCESSIBILITY, OPERABILITY }

    @Schema(name = "AttachmentVersionRef")
    public record AttachmentVersionRefDto(UUID attachmentId, int version, String contentHash,
                                          String bindingType) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "block_type", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = UserScenarioBlockDto.class, name = "user_scenario"),
        @JsonSubTypes.Type(value = BusinessRuleBlockDto.class, name = "business_rule"),
        @JsonSubTypes.Type(value = WorkflowStateBlockDto.class, name = "workflow_state"),
        @JsonSubTypes.Type(value = DataRequirementBlockDto.class, name = "data"),
        @JsonSubTypes.Type(value = PermissionRequirementBlockDto.class, name = "permission"),
        @JsonSubTypes.Type(value = ReportNotificationBlockDto.class, name = "report_notification"),
        @JsonSubTypes.Type(value = NonFunctionalBlockDto.class, name = "non_functional"),
        @JsonSubTypes.Type(value = RegisteredExtensionBlockDto.class, name = "extension")
    })
    @Schema(name = "RequirementBlock", discriminatorProperty = "block_type", oneOf = {
        UserScenarioBlockDto.class, BusinessRuleBlockDto.class, WorkflowStateBlockDto.class,
        DataRequirementBlockDto.class, PermissionRequirementBlockDto.class,
        ReportNotificationBlockDto.class, NonFunctionalBlockDto.class,
        RegisteredExtensionBlockDto.class
    })
    public sealed interface RequirementBlockDto permits UserScenarioBlockDto,
            BusinessRuleBlockDto, WorkflowStateBlockDto, DataRequirementBlockDto,
            PermissionRequirementBlockDto, ReportNotificationBlockDto,
            NonFunctionalBlockDto, RegisteredExtensionBlockDto {
        String blockId();
        RequirementBlockType blockType();
        String title();
        String summary();
        String expectedEffect();
        List<AttachmentVersionRefDto> attachmentRefs();
    }

    @Schema(name = "UserScenarioBlock")
    public record UserScenarioBlockDto(String blockId, RequirementBlockType blockType,
        String title, String summary, String expectedEffect,
        List<AttachmentVersionRefDto> attachmentRefs, List<String> targetUsers,
        List<String> preconditions, String trigger, List<String> steps,
        String expectedOutcome, List<String> exceptionPaths) implements RequirementBlockDto {}
    @Schema(name = "BusinessRuleBlock")
    public record BusinessRuleBlockDto(String blockId, RequirementBlockType blockType,
        String title, String summary, String expectedEffect,
        List<AttachmentVersionRefDto> attachmentRefs, BusinessRuleKindDto ruleKind,
        String ruleStatement, List<String> conditions, String outcome,
        List<String> boundaryCases, List<String> examples) implements RequirementBlockDto {}
    @Schema(name = "WorkflowStateDescriptor")
    public record WorkflowStateDescriptorDto(String stateId, String label, String meaning,
                                             boolean terminal) {}
    @Schema(name = "WorkflowTransitionDescriptor")
    public record WorkflowTransitionDescriptorDto(String transitionId, String fromState,
        String toState, String trigger, String guard, String successBehavior,
        String failureBehavior) {}
    @Schema(name = "WorkflowStateBlock")
    public record WorkflowStateBlockDto(String blockId, RequirementBlockType blockType,
        String title, String summary, String expectedEffect,
        List<AttachmentVersionRefDto> attachmentRefs, String workflowName,
        String initialState, List<WorkflowStateDescriptorDto> states,
        List<WorkflowTransitionDescriptorDto> transitions) implements RequirementBlockDto {}
    @Schema(name = "DataFieldDescriptor")
    public record DataFieldDescriptorDto(String fieldId, String name, String businessMeaning,
        String dataType, boolean required, String source, DataClassificationDto classification) {}
    @Schema(name = "DataRequirementBlock")
    public record DataRequirementBlockDto(String blockId, RequirementBlockType blockType,
        String title, String summary, String expectedEffect,
        List<AttachmentVersionRefDto> attachmentRefs, String subject,
        List<DataFieldDescriptorDto> fields, String retentionRequirement,
        String consistencyRequirement) implements RequirementBlockDto {}
    @Schema(name = "PermissionRequirementBlock")
    public record PermissionRequirementBlockDto(String blockId, RequirementBlockType blockType,
        String title, String summary, String expectedEffect,
        List<AttachmentVersionRefDto> attachmentRefs, List<String> actors, String resource,
        List<String> actions, PermissionEffectDto effect, List<String> conditions,
        List<String> segregationRequirements) implements RequirementBlockDto {}
    @Schema(name = "ReportNotificationBlock")
    public record ReportNotificationBlockDto(String blockId, RequirementBlockType blockType,
        String title, String summary, String expectedEffect,
        List<AttachmentVersionRefDto> attachmentRefs,
        CommunicationArtifactKindDto artifactKind, String trigger, List<String> recipients,
        String channel, List<String> contentRequirements, String timing,
        String escalation) implements RequirementBlockDto {}
    @Schema(name = "NonFunctionalBlock")
    public record NonFunctionalBlockDto(String blockId, RequirementBlockType blockType,
        String title, String summary, String expectedEffect,
        List<AttachmentVersionRefDto> attachmentRefs, QualityAttributeDto qualityAttribute,
        String scope, String metric, String target, String measurementMethod,
        String operatingConditions, String degradationBehavior) implements RequirementBlockDto {}
    @Schema(name = "RegisteredExtensionBlock")
    public record RegisteredExtensionBlockDto(String blockId, RequirementBlockType blockType,
        String title, String summary, String expectedEffect,
        List<AttachmentVersionRefDto> attachmentRefs, String namespace, String extensionType,
        URI extensionSchemaUri, String extensionSchemaVersion,
        JsonNode extensionPayload) implements RequirementBlockDto {}

    public record RequirementGraphNodeRefDto(RequirementGraphNodeKind nodeKind,
        UUID requirementId, int revisionNo, String blockId) {}
    public enum RequirementGraphNodeKind implements WireEnum { REQUIREMENT, BLOCK }
    public record RequirementRelationDto(String relationId, RequirementGraphNodeRefDto source,
        RequirementRelationType relationType, RequirementGraphNodeRefDto target,
        String rationale) {}
    public record RequirementIntentDto(String goal, String currentProblem, String targetResult) {}
    public record RequirementScopeDto(List<String> inScope, List<String> outOfScope) {}
    public record SuccessMetricDto(String metricId, String description, String target,
                                   String measurementMethod) {}
    public record AcceptanceCriterionDto(String criterionId, String businessOutcome,
                                         String verificationMethod, String priority) {}
    public record AcceptedUnknownDto(String unknownId, String statement, String risk,
        String control, String acceptedBySide, Instant acceptedAt) {}
    public record SemanticDecisionDto(String decisionId, String subjectRef, String decision,
        String rationale, String decidedBySide, Instant decidedAt) {}
    public record FieldSourceDto(String jsonPointer, String sourceKind, String sourceRef,
                                 String sourceDigest) {}
    public record DevelopmentContextBasisDto(UUID contextVersionId, String contextBasisDigest,
        String schemaVersion, String agentPackVersion, String analyzerVersion,
        String basisCommitSha, String basisTreeSha, List<String> trustLabels) {}
    public record DevelopmentImpactItemDto(String impactId, String kind, String subjectRef,
        String impactType, String rationale, String confidenceLabel,
        List<String> evidenceRefs) {}
    public record DevelopmentRiskDto(String riskId, String category,
        RequirementRiskLevel severity, String description, String mitigation,
        boolean blocking) {}
    public record DevelopmentUnknownDto(String unknownId, String statement, String reason,
        boolean blocking, List<String> evidenceRefs) {}
    public record DevelopmentWorkItemPlanDto(String planItemId, String title, String goal,
        List<String> nonGoals, List<String> coveredBlockIds, List<String> dependencies,
        List<String> completionConditions) {}
    public record DevelopmentTestMappingDto(String mappingId, String criterionId,
        String testLevel, String verification, List<String> evidenceRefs) {}
    public record DevelopmentViewDto(String status, DevelopmentContextBasisDto contextBasis,
        List<DevelopmentImpactItemDto> impacts, List<String> constraints,
        List<String> modificationSuggestions, List<String> compatibilityConsiderations,
        List<String> migrationConsiderations, List<DevelopmentRiskDto> risks,
        List<DevelopmentWorkItemPlanDto> workItemPlan,
        List<DevelopmentTestMappingDto> testMappings, List<DevelopmentUnknownDto> unknowns,
        List<String> evidenceRefs) {}
    public record ConfirmationSummaryDto(String side, boolean confirmed, UUID accountId,
                                         Instant confirmedAt) {}
    public record BlockerDto(String code, String message, UUID actionRequestId) {}
    public record RequirementSummaryDto(UUID requirementId, String title,
        String businessDomain, int currentRevisionNo, String currentRevisionHash, long version,
        RequirementBlockType primaryBlockType, RequirementRiskLevel riskLevel,
        RequirementDisplayPhase displayPhase, String nextActorLabel,
        List<BlockerDto> blockers, Instant dueAt, List<AllowedActionDto> allowedActions) {}
    public record RequirementPageDto(List<RequirementSummaryDto> items, PageMetaDto page,
        ObjectRefDto objectRef, DisplayStateDto displayState,
        List<AllowedActionDto> allowedActions) implements ActionBearingDto {}

    public record GraphRiskFactDto(String code, String label, RequirementRiskLevel severity,
                                   boolean blocking, int evidenceCount) {}
    public record GraphGateFactDto(String gateKey, String state, String label,
                                   boolean blocking) {}
    public record GraphScoreFactDto(String scoreType, Integer value, String state,
                                    boolean anomaly) {}
    public record GraphRelationCountsDto(int precedes, int dependsOn, int triggers,
        int constrains, int affects, int conflictsWith, int supersedes, int splitFrom,
        int relatesTo) {}
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "node_kind", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = RequirementRootGraphNodeDto.class, name = "requirement"),
        @JsonSubTypes.Type(value = RequirementBlockGraphNodeDto.class, name = "block")
    })
    @Schema(name = "RequirementGraphNode", discriminatorProperty = "node_kind", oneOf = {
        RequirementRootGraphNodeDto.class, RequirementBlockGraphNodeDto.class
    })
    public sealed interface RequirementGraphNodeDto permits RequirementRootGraphNodeDto,
            RequirementBlockGraphNodeDto {
        RequirementGraphNodeKind nodeKind();
        RequirementGraphNodeRefDto ref();
    }
    public record RequirementRootGraphNodeDto(RequirementGraphNodeKind nodeKind,
        RequirementGraphNodeRefDto ref, String title, String businessDomain,
        RequirementBlockType primaryBlockType, RequirementDisplayPhase phase,
        List<GraphScoreFactDto> scores, List<GraphGateFactDto> gateFacts,
        List<GraphRiskFactDto> riskFacts, int blockerCount,
        GraphRelationCountsDto relationCounts) implements RequirementGraphNodeDto {}
    public record RequirementBlockGraphNodeDto(RequirementGraphNodeKind nodeKind,
        RequirementGraphNodeRefDto ref, RequirementBlockType blockType, String title,
        String summary, String expectedEffect, String ownerDisplayLabel,
        RequirementDisplayPhase phase, List<GraphRiskFactDto> riskFacts, int blockerCount,
        List<GraphGateFactDto> gateFacts, GraphRelationCountsDto relationCounts,
        List<AttachmentVersionRefDto> attachmentRefs) implements RequirementGraphNodeDto {}
    public record RequirementGraphEdgeDto(String id, RequirementGraphNodeRefDto source,
        RequirementGraphNodeRefDto target, RequirementRelationType relationType,
        String label, String rationale) {}
    public record RequirementWorkspaceDto(List<RequirementGraphNodeDto> nodes,
        List<RequirementGraphEdgeDto> edges, long snapshotSequence,
        String layoutPolicyVersion) {}
    public record RequirementWorkspaceEnvelope(RequirementWorkspaceDto data, long sequence,
        ObjectRefDto objectRef, DisplayStateDto displayState,
        List<AllowedActionDto> allowedActions) implements ActionBearingDto {}

    public record RequirementDetailDto(RequirementSummaryDto summary,
        List<RequirementBlockDto> blocks, List<RequirementRelationDto> relations,
        List<ConfirmationSummaryDto> confirmations,
        List<AttachmentVersionRefDto> contractualAttachments,
        List<AttachmentVersionRefDto> referenceAttachments, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions)
        implements ActionBearingDto {}
    public record RequirementRevisionDto(UUID requirementId, int revisionNo,
        String revisionHash, String parentRevisionHash, String schemaVersion, long version,
        RequirementIntentDto intent, RequirementScopeDto scope,
        List<RequirementBlockDto> blocks, List<RequirementRelationDto> relations,
        List<String> edgeCases, List<SuccessMetricDto> successMetrics,
        List<AcceptanceCriterionDto> acceptanceCriteria,
        List<AttachmentVersionRefDto> contractualAttachments,
        DevelopmentViewDto developmentView, List<AcceptedUnknownDto> acceptedUnknowns,
        List<SemanticDecisionDto> semanticDecisions, List<FieldSourceDto> fieldSources,
        Instant createdAt, ObjectRefDto objectRef, DisplayStateDto displayState,
        List<AllowedActionDto> allowedActions) implements ActionBearingDto {}
    public record RequirementRevisionPageDto(List<RequirementRevisionDto> items,
        PageMetaDto page, ObjectRefDto objectRef, DisplayStateDto displayState,
        List<AllowedActionDto> allowedActions) implements ActionBearingDto {}
    public record BusinessProjectionDto(RequirementSummaryDto requirement,
        RequirementIntentDto intent, RequirementScopeDto scope,
        List<RequirementBlockDto> blocks, List<RequirementRelationDto> relations,
        List<String> edgeCases, List<SuccessMetricDto> successMetrics,
        List<AcceptanceCriterionDto> acceptanceCriteria,
        List<AcceptedUnknownDto> acceptedUnknowns,
        List<SemanticDecisionDto> semanticDecisions,
        List<AttachmentVersionRefDto> materialAttachments, String assessmentSummary,
        List<FieldSourceDto> fieldSources, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions)
        implements ActionBearingDto {}
    public record DevelopmentProjectionDto(RequirementSummaryDto requirement,
        List<RequirementBlockDto> blocks, DevelopmentViewDto developmentView,
        List<AcceptanceCriterionDto> acceptanceCriteria,
        List<AttachmentVersionRefDto> materialAttachments,
        List<FieldSourceDto> fieldSources, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions)
        implements ActionBearingDto {}
    public record RevisionDiffEntryDto(String key, String label, String jsonPointer,
        String ownerSide, String kind, JsonNode before, JsonNode after, boolean semantic) {}
    public record RevisionComparisonDto(UUID requirementId, int fromRevision, int toRevision,
        String fromHash, String toHash, List<RevisionDiffEntryDto> entries,
        ObjectRefDto objectRef, DisplayStateDto displayState,
        List<AllowedActionDto> allowedActions) implements ActionBearingDto {}

    public record DraftQuestionDto(String questionId, DraftGapKind gapKind, String prompt,
                                   String reason, boolean blocking, String answer) {}
    public enum DraftRelationEndpointKind implements WireEnum { LOCAL_BLOCK, EXISTING_GRAPH_NODE }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "endpoint_kind", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = LocalDraftBlockRelationEndpointDto.class,
            name = "local_block"),
        @JsonSubTypes.Type(value = ExistingGraphNodeRelationEndpointDto.class,
            name = "existing_graph_node")
    })
    @Schema(name = "DraftRelationEndpoint", discriminatorProperty = "endpoint_kind", oneOf = {
        LocalDraftBlockRelationEndpointDto.class, ExistingGraphNodeRelationEndpointDto.class
    })
    public sealed interface DraftRelationEndpointDto permits LocalDraftBlockRelationEndpointDto,
            ExistingGraphNodeRelationEndpointDto {
        DraftRelationEndpointKind endpointKind();
    }
    public record LocalDraftBlockRelationEndpointDto(DraftRelationEndpointKind endpointKind,
        String blockId) implements DraftRelationEndpointDto {}
    public record ExistingGraphNodeRelationEndpointDto(DraftRelationEndpointKind endpointKind,
        RequirementGraphNodeRefDto node) implements DraftRelationEndpointDto {}
    public record DraftRequirementRelationDto(String relationId,
        DraftRelationEndpointDto source, RequirementRelationType relationType,
        DraftRelationEndpointDto target, String rationale) {}
    public record DraftRelationSuggestionDto(String suggestionId,
        DraftRequirementRelationDto relation, DraftRelationSource source,
        DraftRelationReviewTier reviewTier, DraftRelationDecision decision,
        String decisionReason) {}
    public record DraftFormDto(String businessDomain, RequirementBlockType primaryBlockType,
        ClassificationSource classificationSource, String title, RequirementIntentDto intent,
        RequirementScopeDto scope, List<RequirementBlockDto> blocks,
        List<String> edgeCases, List<SuccessMetricDto> successMetrics,
        List<AcceptanceCriterionDto> acceptanceCriteria, List<DraftQuestionDto> questions,
        List<DraftRelationSuggestionDto> relationSuggestions) {}
    public record RequirementDraftDto(UUID draftId, long version, String contentHash,
        DraftIntent intent, UUID baseRequirementId, Integer baseRevisionNo,
        String baseRevisionHash, DraftSourceType sourceType, String inputText,
        String editedTranscript, String locale, DraftFormDto form,
        List<AttachmentVersionRefDto> attachments, UUID currentExtractionId,
        UUID currentTranscriptionId, RequestedAudioDisposition audioDisposition,
        Instant expiresAt, ObjectRefDto objectRef, DisplayStateDto displayState,
        List<AllowedActionDto> allowedActions) implements ActionBearingDto {}
    public record DraftExtractionDto(UUID extractionId, UUID draftId, long basedOnVersion,
        String basedOnHash, DraftExtractionState state, DraftFormDto proposedForm,
        String failureCode, Instant createdAt, Instant completedAt, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions)
        implements ActionBearingDto {}
    public record DraftTranscriptionDto(UUID transcriptionId, UUID draftId,
        TranscriptionState state, String transcript, String transcriptHash, String language,
        Instant temporaryAudioExpiresAt, AudioDispositionFactDto audioDisposition,
        Instant createdAt, ObjectRefDto objectRef, DisplayStateDto displayState,
        List<AllowedActionDto> allowedActions) implements ActionBearingDto {}
    public record TranscriptionUploadSessionDto(UUID uploadId, String acceptedMediaType,
        long maximumBytes, Instant expiresAt, ScopedCapabilityDto uploadCapability) {}
    public record AudioDispositionFactDto(UUID transcriptionId,
        RequestedAudioDisposition requested, AudioDispositionState state,
        UUID deletionOperationId, AttachmentVersionRefDto retainedReference,
        String providerDeleteReceiptDigest, Instant absenceVerifiedAt, String failureCode,
        UUID actionRequestId, Instant recordedAt) {}
    public record RequirementDraftSubmissionReceiptDto(CommandReceiptDto commandReceipt,
        UUID requirementId, int revisionNo, String revisionHash,
        AudioDispositionFactDto audioDisposition) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "intent", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = CreateNewRequirementDraftRequest.class,
            name = "create_requirement"),
        @JsonSubTypes.Type(value = ReviseExistingRequirementDraftRequest.class,
            name = "revise_existing")
    })
    @Schema(name = "CreateRequirementDraftRequest", discriminatorProperty = "intent",
        oneOf = {CreateNewRequirementDraftRequest.class,
            ReviseExistingRequirementDraftRequest.class})
    public sealed interface CreateRequirementDraftRequest permits CreateNewRequirementDraftRequest,
            ReviseExistingRequirementDraftRequest {
        long expectedVersion();
        DraftIntent intent();
        DraftSourceType sourceType();
        String inputText();
        String locale();
        List<AttachmentVersionRefDto> attachmentRefs();
        String prefilledBusinessDomain();
        RequirementBlockType prefilledBlockType();
    }
    public record CreateNewRequirementDraftRequest(long expectedVersion, DraftIntent intent,
        DraftSourceType sourceType, String inputText, String locale,
        List<AttachmentVersionRefDto> attachmentRefs, String prefilledBusinessDomain,
        RequirementBlockType prefilledBlockType) implements CreateRequirementDraftRequest {
        public CreateNewRequirementDraftRequest {
            if (intent != DraftIntent.CREATE_REQUIREMENT) throw new IllegalArgumentException("intent");
        }
    }
    public record ReviseExistingRequirementDraftRequest(long expectedVersion,
        DraftIntent intent, UUID baseRequirementId, int baseRevisionNo,
        String baseRevisionHash, DraftSourceType sourceType, String inputText, String locale,
        List<AttachmentVersionRefDto> attachmentRefs, String prefilledBusinessDomain,
        RequirementBlockType prefilledBlockType) implements CreateRequirementDraftRequest {
        public ReviseExistingRequirementDraftRequest {
            if (intent != DraftIntent.REVISE_EXISTING) throw new IllegalArgumentException("intent");
        }
    }
    public record UpdateRequirementDraftRequest(long expectedVersion, String inputText,
        String editedTranscript, String locale, DraftFormDto form,
        List<AttachmentVersionRefDto> attachmentRefs,
        RequestedAudioDisposition audioDisposition) {}
    public record ExtractRequirementDraftRequest(long expectedVersion, String expectedHash,
                                                 String requestedLocale) {}
    public record CreateTranscriptionUploadRequest(long expectedVersion, String fileName,
        String declaredMediaType, long contentLength, String sha256) {}
    public record CompleteTranscriptionUploadRequest(long expectedVersion,
                                                     long contentLength, String sha256) {}
    public record SubmitRequirementDraftRequest(long expectedVersion, String expectedHash,
        UUID extractionId, DraftFormDto form, boolean classificationConfirmed,
        boolean transcriptConfirmed, RequestedAudioDisposition audioDisposition,
        List<String> acceptedUnknownIds) {}

    public interface RequirementHttpApi {
        VersionedApiResult<RequirementPageDto> listRequirements(VerifiedRequestIdentity identity,
            UUID projectId, String cursor, int limit, String query, String businessDomain,
            RequirementBlockType blockType, RequirementRiskLevel risk,
            RequirementDisplayPhase phase);
        VersionedApiResult<RequirementWorkspaceEnvelope> getRequirementGraph(
            VerifiedRequestIdentity identity, UUID projectId, Long atSequence, String domain,
            RequirementBlockType blockType, RequirementRiskLevel risk,
            RequirementDisplayPhase phase);
        VersionedApiResult<RequirementDetailDto> getRequirement(VerifiedRequestIdentity identity,
            UUID projectId, UUID requirementId);
        VersionedApiResult<RequirementRevisionPageDto> listRequirementRevisions(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId,
            String cursor, int limit);
        VersionedApiResult<RequirementRevisionDto> getRequirementRevision(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId, int revisionNo);
        VersionedApiResult<BusinessProjectionDto> getBusinessProjection(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId, int revisionNo);
        VersionedApiResult<DevelopmentProjectionDto> getDevelopmentProjection(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId, int revisionNo);
        VersionedApiResult<RevisionComparisonDto> compareRevisions(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId,
            int fromRevisionNo, int toRevisionNo);
        MutationApiResult<CommandReceiptDto> createDraft(VerifiedRequestIdentity identity,
            UUID projectId, CommandHeaders headers, CreateRequirementDraftRequest request);
        VersionedApiResult<RequirementDraftDto> getDraft(VerifiedRequestIdentity identity,
            UUID projectId, UUID draftId);
        MutationApiResult<CommandReceiptDto> updateDraft(VerifiedRequestIdentity identity,
            UUID projectId, UUID draftId, CommandHeaders headers,
            UpdateRequirementDraftRequest request);
        MutationApiResult<CommandReceiptDto> extractDraft(VerifiedRequestIdentity identity,
            UUID projectId, UUID draftId, CommandHeaders headers,
            ExtractRequirementDraftRequest request);
        VersionedApiResult<DraftExtractionDto> getDraftExtraction(
            VerifiedRequestIdentity identity, UUID projectId, UUID draftId, UUID extractionId);
        MutationApiResult<TranscriptionUploadSessionDto> createTranscriptionUpload(
            VerifiedRequestIdentity identity, UUID projectId, UUID draftId,
            CommandHeaders headers, CreateTranscriptionUploadRequest request);
        MutationApiResult<CommandReceiptDto> completeTranscriptionUpload(
            VerifiedRequestIdentity identity, UUID projectId, UUID draftId, UUID uploadId,
            CommandHeaders headers, CompleteTranscriptionUploadRequest request);
        VersionedApiResult<DraftTranscriptionDto> getDraftTranscription(
            VerifiedRequestIdentity identity, UUID projectId, UUID draftId,
            UUID transcriptionId);
        MutationApiResult<RequirementDraftSubmissionReceiptDto> submitDraft(
            VerifiedRequestIdentity identity, UUID projectId, UUID draftId,
            CommandHeaders headers, SubmitRequirementDraftRequest request);
    }
}
```


OpenAPI emits `RequirementBlock`, `RequirementGraphNode`, and `DraftRelationEndpoint` as true `oneOf` schemas with required discriminators, not as flattened objects. Contract tests require every branch's discriminator `const`, exact required set, and `unevaluatedProperties: false`. `RequirementGraphNodeRef.block_id` is forbidden for `node_kind=requirement` and required for `node_kind=block`; every ref resolves inside its stated exact Requirement Revision. A relation may connect exact root or block refs within one Requirement or across Requirements, but both endpoints must exist in the authorized project snapshot and neither may silently float to a newer Revision. Layout coordinates and browser node keys are not semantic or client-writable; `layout_policy_version` selects the Web's deterministic auto-layout implementation.

`DraftFormDto` is the single generated round-trip schema used by extraction, autosave, revision editing, and submit. `intent` supplies the first-screen goal/current problem/target result; typed `blocks` carry scenarios, rules, workflow, data, permission, report/notification, and non-functional details; scope, edge cases, success metrics, acceptance criteria, blocking/non-blocking gaps, and relation suggestions remain explicit rather than disappearing into prose. A draft relation endpoint is a closed union: `local_block` names a block in the same draft, while `existing_graph_node` carries the generated exact Requirement/Revision/block ref returned by graph search. Submit resolves local endpoints to the newly created Requirement/Revision and validates existing endpoints under the same tenant, project, visibility snapshot, and exact Revision before one atomic write. Every Agent-suggested relation starts `PENDING`; submit rejects any pending relation, persists only `ACCEPTED` relations, and retains rejected suggestions plus reasons as provenance outside the semantic payload. `CONFLICTS_WITH`, `PRECEDES`, and policy-classified high-risk suggestions use `PROMINENT`, but prominence never implies acceptance.

For speech/mixed drafts, `DELETE_AFTER_SUBMIT` is the server-created default and `RETAIN_AS_REFERENCE` requires an explicit editable selection; text-only drafts use `NOT_APPLICABLE`. Submit atomically creates the Revision and either records `DELETION_PENDING` plus a durable operation/ActionRequest/outbox or promotes immutable bytes to a `reference` Attachment and records `RETAINED_AS_REFERENCE`. The submission receipt is immutable and same-key replay returns the identical initial fact. `DraftTranscriptionDto.audioDisposition` is `null` before submit and is the current projection after submit; polling `getRequirementDraftTranscription` may later expose `DELETED` only with a matching provider version-delete receipt digest plus absence verification time, or `DELETION_FAILED` with a stable failure code and remediation ActionRequest. `deletionOperationId` and `actionRequestId` are both null for `NOT_APPLICABLE`/`RETAINED_AS_REFERENCE` and both non-null for all three deletion states; a `DELETED` projection retains the completed ActionRequest ID. `providerDeleteReceiptDigest` and `absenceVerifiedAt` are non-null only for `DELETED`; `failureCode` is non-null only for `DELETION_FAILED`. Schema `oneOf` branches enforce these nullable rules. All five wire states are closed schema enum values. No browser or Agent can claim deletion/retention from intent alone, provider ambiguity remains pending, and retained audio never becomes contractual unless a later explicit Revision changes its binding.

List and graph cursors bind the canonical digest of `query`, `business_domain/domain`, `block_type`, `risk`, `phase`, authorized visibility scope, stable sort, page size, and snapshot sequence. Reusing a cursor with changed filters returns `422 CURSOR_INVALID`; filtering is always server-side over the full snapshot, never over a fetched page.

- [ ] **Step 5: Define all attachment, collaboration, ActionRequest, and event DTOs**

Create `AttachmentApiDtos.java`. No response type contains `ByteArray`, `InputStream`, Spring `Resource`, an OSS/S3 object key, scanner credentials, or the uploaded object. Browser access is only through an actor-, purpose-, disposition-, version-, and expiry-bound `ScopedAttachmentCapabilityDto`; upload capabilities can address only the server-selected quarantine object and one part.

```java
package com.inforvans.accord.attachment.api;

public final class AttachmentApiDtos {
    private AttachmentApiDtos() {}

    public enum AttachmentApiState { UPLOADING, SCANNING, AVAILABLE, QUARANTINED, DELETING, DELETED }
    public enum AttachmentBindingType { CONTRACTUAL, REFERENCE }
    public enum AttachmentAccessMode { PROJECT_SHARED, RESTRICTED }
    public enum AttachmentCapabilityPurpose { UPLOAD_PART, PREVIEW, DOWNLOAD }
    public enum AttachmentPreflightDecision { PASS, FAIL }

    public record AttachmentVersionDto(
        UUID attachmentId, int version, String fileName, String declaredMediaType,
        String detectedMediaType, long size, String sha256, AttachmentApiState state,
        AttachmentBindingType bindingType, AttachmentAccessMode accessMode, UUID accessScopeId,
        String accessLabel, Instant createdAt, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions
    ) implements ActionBearingDto {}
    public record AttachmentPageDto(
        List<AttachmentVersionDto> items, PageMetaDto page, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions
    ) implements ActionBearingDto {}
    public record AttachmentUploadDto(
        UUID uploadId, UUID attachmentId, int attachmentVersion, long version, long partSize,
        int maximumParts, int receivedParts, Instant expiresAt, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions
    ) implements ActionBearingDto {}
    public record AttachmentUploadPartDto(int partNumber, long size, String etag,
                                          Instant recordedAt) {}
    public record AttachmentUploadPartPageDto(
        List<AttachmentUploadPartDto> items, PageMetaDto page, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions
    ) implements ActionBearingDto {}
    public record AttachmentStatusDto(
        UUID attachmentId, int version, AttachmentApiState state, boolean previewAvailable,
        String reasonCode, Instant updatedAt, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions
    ) implements ActionBearingDto {}
    public record ScopedAttachmentCapabilityDto(
        AttachmentCapabilityPurpose purpose, UUID attachmentId, int attachmentVersion,
        ScopedCapabilityDto capability) {}
    public record AttachmentAccessResultDto(
        UUID attachmentId, int attachmentVersion, UUID actorAccountId,
        boolean accessible, String reasonCode) {}
    public record AttachmentAccessPreflightDto(
        AttachmentPreflightDecision decision, String revisionHash,
        List<AttachmentAccessResultDto> results, List<UUID> actionRequestIds,
        CommandReceiptDto receipt) {}

    public record CreateAttachmentUploadRequest(
        long expectedVersion, String fileName, String declaredMediaType, long contentLength,
        String sha256, AttachmentBindingType bindingType, AttachmentAccessMode accessMode,
        UUID accessScopeId, UUID draftId) {}
    public record SignAttachmentUploadPartRequest(
        long expectedVersion, long contentLength, String sha256) {}
    public record CompletedAttachmentPartDto(int partNumber, String etag, long size) {}
    public record CompleteAttachmentUploadRequest(
        long expectedVersion, List<CompletedAttachmentPartDto> parts, String sha256) {}
    public record AbortAttachmentUploadRequest(long expectedVersion, String reason) {}
    public record CreateAttachmentCapabilityRequest(
        long expectedVersion, String contentDisposition) {}
    public record RunAttachmentAccessPreflightRequest(
        long expectedVersion, String expectedHash, Set<UUID> actorAccountIds) {}

    public interface AttachmentHttpApi {
        VersionedApiResult<AttachmentPageDto> listAttachments(VerifiedRequestIdentity identity,
            UUID projectId, String cursor, int limit, UUID draftId, UUID requirementId,
            AttachmentApiState state);
        MutationApiResult<CommandReceiptDto> createUpload(VerifiedRequestIdentity identity,
            UUID projectId, CommandHeaders headers, CreateAttachmentUploadRequest request);
        VersionedApiResult<AttachmentUploadDto> getUpload(VerifiedRequestIdentity identity,
            UUID projectId, UUID uploadId);
        MutationApiResult<ScopedAttachmentCapabilityDto> signUploadPart(
            VerifiedRequestIdentity identity, UUID projectId, UUID uploadId, int partNumber,
            CommandHeaders headers, SignAttachmentUploadPartRequest request);
        VersionedApiResult<AttachmentUploadPartPageDto> listUploadParts(
            VerifiedRequestIdentity identity, UUID projectId, UUID uploadId,
            String cursor, int limit);
        MutationApiResult<CommandReceiptDto> completeUpload(VerifiedRequestIdentity identity,
            UUID projectId, UUID uploadId, CommandHeaders headers,
            CompleteAttachmentUploadRequest request);
        MutationApiResult<CommandReceiptDto> abortUpload(VerifiedRequestIdentity identity,
            UUID projectId, UUID uploadId, CommandHeaders headers,
            AbortAttachmentUploadRequest request);
        VersionedApiResult<AttachmentVersionDto> getVersion(VerifiedRequestIdentity identity,
            UUID projectId, UUID attachmentId, int version);
        VersionedApiResult<AttachmentStatusDto> getStatus(VerifiedRequestIdentity identity,
            UUID projectId, UUID attachmentId, int version);
        MutationApiResult<ScopedAttachmentCapabilityDto> createPreviewCapability(
            VerifiedRequestIdentity identity, UUID projectId, UUID attachmentId, int version,
            CommandHeaders headers, CreateAttachmentCapabilityRequest request);
        MutationApiResult<ScopedAttachmentCapabilityDto> createDownloadCapability(
            VerifiedRequestIdentity identity, UUID projectId, UUID attachmentId, int version,
            CommandHeaders headers, CreateAttachmentCapabilityRequest request);
        MutationApiResult<AttachmentAccessPreflightDto> runAccessPreflight(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId, int revisionNo,
            CommandHeaders headers, RunAttachmentAccessPreflightRequest request);
    }
}
```

The OpenAPI schema uses `oneOf` to require `access_scope_id` when `access_mode=restricted` and require it to be absent when `access_mode=project_shared`. The controller resolves that ID under the project and tenant before creating an upload; a client cannot name a tenant, storage key, bucket, object prefix, scanner verdict, or capability expiry.

Create `CollaborationApiDtos.java`:

```java
package com.inforvans.accord.collaboration.api;

public final class CollaborationApiDtos {
    private CollaborationApiDtos() {}

    public enum ProposalApiStatus { OPEN, ACCEPTED, PARTIALLY_ACCEPTED, REJECTED, MORE_INFORMATION_REQUIRED, SUPERSEDED }
    public enum ProposalApiDecision { ACCEPT, PARTIALLY_ACCEPT, REJECT, REQUEST_MORE_INFORMATION }
    public enum ConfirmationApiSide { DEVELOPMENT, BUSINESS }
    public enum BusinessQuestionApiStatus { OPEN, ANSWERED, RESOLVED, SUPERSEDED }
    public enum BusinessQuestionMessageKind { CONTEXT, ANSWER, CLARIFICATION }
    public enum BusinessQuestionResolutionKind { ANSWER_ACCEPTED, REVISION_REQUIRED, WITHDRAWN }
    public enum CollaborationSide { BUSINESS, DEVELOPMENT }

    public record ProposalOperationDto(
        UUID operationId, String jsonPointer, String currentValueDigest, JsonNode proposedValue,
        String reason, String risk, String expectedImpact, boolean blocking) {}
    public record DevelopmentProposalDto(
        UUID proposalId, UUID requirementId, int targetRevisionNo, String targetRevisionHash,
        ProposalApiStatus status, long version, List<ProposalOperationDto> operations,
        List<String> evidenceAttachmentRefs, Instant createdAt, Instant resolvedAt,
        ObjectRefDto objectRef, DisplayStateDto displayState,
        List<AllowedActionDto> allowedActions) implements ActionBearingDto {}
    public record DevelopmentProposalPageDto(
        List<DevelopmentProposalDto> items, PageMetaDto page, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions)
        implements ActionBearingDto {}
    public record CreateDevelopmentProposalRequest(
        long expectedVersion, String expectedHash, int targetRevisionNo,
        List<ProposalOperationDto> operations, List<String> evidenceAttachmentRefs) {}
    public record ResolveDevelopmentProposalRequest(
        long expectedVersion, String expectedHash, ProposalApiDecision decision,
        Set<UUID> selectedOperationIds, String reason) {}
    public record BusinessQuestionMessageDto(
        UUID messageId, long sequence, BusinessQuestionMessageKind kind,
        CollaborationSide authorSide, String authorDisplayLabel, String body,
        List<String> attachmentRefs, Instant createdAt) {}
    public record BusinessQuestionResolutionDto(
        UUID resolutionId, BusinessQuestionResolutionKind kind, UUID acceptedMessageId,
        String summary, Integer resultingRevisionNo, String resultingRevisionHash,
        String resolvedByDisplayLabel, Instant resolvedAt) {}
    public record BusinessQuestionDto(
        UUID questionId, UUID requirementId, int targetRevisionNo, String targetRevisionHash,
        String targetJsonPointer, String prompt, String reason, boolean blocking,
        BusinessQuestionApiStatus status, long version, List<String> evidenceAttachmentRefs,
        List<BusinessQuestionMessageDto> messages, BusinessQuestionResolutionDto resolution,
        List<UUID> actionRequestIds, Instant createdAt, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions)
        implements ActionBearingDto {}
    public record BusinessQuestionPageDto(
        List<BusinessQuestionDto> items, PageMetaDto page, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions)
        implements ActionBearingDto {}
    public record CreateBusinessQuestionRequest(
        long expectedVersion, String expectedHash, int targetRevisionNo,
        String targetJsonPointer, String prompt, String reason, boolean blocking,
        List<String> evidenceAttachmentRefs) {}
    public record AddBusinessQuestionMessageRequest(
        long expectedVersion, String expectedHash, BusinessQuestionMessageKind kind,
        String body, List<String> attachmentRefs) {}
    public record ResolveBusinessQuestionRequest(
        long expectedVersion, String expectedHash, BusinessQuestionResolutionKind resolutionKind,
        UUID acceptedMessageId, String summary, Integer resultingRevisionNo,
        String resultingRevisionHash) {}
    public record ConfirmRequirementRequest(
        long expectedVersion, String expectedHash, String projectContextBasisDigest,
        long assessmentPolicyVersion, Set<String> acceptedUnknownIds) {}
    public record RequirementConfirmationLinkDto(
        UUID confirmationReceiptId, UUID requirementId, int revisionNo, String revisionHash,
        ConfirmationApiSide side, long confirmationSequence, Instant linkedAt,
        boolean replayed) {}

    public interface CollaborationHttpApi {
        MutationApiResult<CommandReceiptDto> createProposal(VerifiedRequestIdentity identity,
            UUID projectId, UUID requirementId, CommandHeaders headers,
            CreateDevelopmentProposalRequest request);
        VersionedApiResult<DevelopmentProposalPageDto> listProposals(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId,
            String cursor, int limit, ProposalApiStatus status);
        VersionedApiResult<DevelopmentProposalDto> getProposal(VerifiedRequestIdentity identity,
            UUID projectId, UUID requirementId, UUID proposalId);
        MutationApiResult<CommandReceiptDto> resolveProposal(VerifiedRequestIdentity identity,
            UUID projectId, UUID requirementId, UUID proposalId, CommandHeaders headers,
            ResolveDevelopmentProposalRequest request);
        VersionedApiResult<BusinessQuestionPageDto> listBusinessQuestions(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId,
            String cursor, int limit, BusinessQuestionApiStatus status, Boolean blocking);
        MutationApiResult<CommandReceiptDto> createBusinessQuestion(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId,
            CommandHeaders headers, CreateBusinessQuestionRequest request);
        VersionedApiResult<BusinessQuestionDto> getBusinessQuestion(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId, UUID questionId);
        MutationApiResult<CommandReceiptDto> addBusinessQuestionMessage(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId, UUID questionId,
            CommandHeaders headers, AddBusinessQuestionMessageRequest request);
        MutationApiResult<CommandReceiptDto> resolveBusinessQuestion(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId, UUID questionId,
            CommandHeaders headers, ResolveBusinessQuestionRequest request);
        MutationApiResult<RequirementConfirmationLinkDto> confirmDevelopment(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId, int revisionNo,
            CommandHeaders headers, ConfirmRequirementRequest request);
        MutationApiResult<RequirementConfirmationLinkDto> confirmBusiness(
            VerifiedRequestIdentity identity, UUID projectId, UUID requirementId, int revisionNo,
            CommandHeaders headers, ConfirmRequirementRequest request);
    }
}
```

The collaboration response is deliberately a link DTO. When a detail view needs signer/authorization evidence, the application joins `confirmationReceiptId` through Identity's viewer-filtered confirmation projection; collaboration does not serialize or persist a second actor, role, FreshAuth, signature, or permission record.

Create `ActionRequestApiDtos.java`:

```java
package com.inforvans.accord.action.api;

public final class ActionRequestApiDtos {
    private ActionRequestApiDtos() {}

    public enum ActionRequestApiPhase { OPEN, COMPLETED, DECLINED, EXPIRED, SUPERSEDED, CANCELLED }
    public enum ActionInboxScope {
        MINE("mine"), WAITING("waiting"), TEAM("team");
        private final String wireValue;
        ActionInboxScope(String wireValue) { this.wireValue = wireValue; }
        @JsonValue public String wireValue() { return wireValue; }
    }

    public record ActionRequestDto(
        UUID id, String kind, ActionRequestApiPhase phase, String title, String projectLabel,
        String reason, String gateLabel, Instant dueAt, String risk, String assigneeLabel,
        boolean waitingOnCurrentUser, ObjectRefDto target, String blockerCode,
        List<String> decisionOptions, String assignedRole, UUID assignedAccountId,
        String underlyingActionKey) {}
    public record ActionRequestEnvelope(
        ActionRequestDto data, long sequence, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions)
        implements ActionBearingDto {}
    public record ActionRequestPageDto(
        List<ActionRequestEnvelope> items, PageMetaDto page, ObjectRefDto objectRef,
        DisplayStateDto displayState, List<AllowedActionDto> allowedActions)
        implements ActionBearingDto {}
    public record CompleteActionRequestRequest(
        long expectedVersion, String expectedHash, String decision,
        Set<String> selectedIds, String reason) {}
    public record DeclineActionRequestRequest(
        long expectedVersion, String expectedHash, String reason) {}
    public record DelegateActionRequestRequest(
        long expectedVersion, String expectedHash, UUID delegateAccountId, String reason) {}

    public interface ActionRequestHttpApi {
        VersionedApiResult<ActionRequestPageDto> list(VerifiedRequestIdentity identity,
            UUID projectId, ActionInboxScope scope, String cursor, int limit,
            ActionRequestApiPhase phase, String type);
        VersionedApiResult<ActionRequestEnvelope> get(VerifiedRequestIdentity identity,
            UUID projectId, UUID actionRequestId);
        MutationApiResult<CommandReceiptDto> complete(VerifiedRequestIdentity identity,
            UUID projectId, UUID actionRequestId, CommandHeaders headers,
            CompleteActionRequestRequest request);
        MutationApiResult<CommandReceiptDto> decline(VerifiedRequestIdentity identity,
            UUID projectId, UUID actionRequestId, CommandHeaders headers,
            DeclineActionRequestRequest request);
        MutationApiResult<CommandReceiptDto> delegate(VerifiedRequestIdentity identity,
            UUID projectId, UUID actionRequestId, CommandHeaders headers,
            DelegateActionRequestRequest request);
    }
}
```

Create `ActionRequestPort.java` as the only cross-module mutation surface for the authoritative queue. It accepts the caller's already-open tenant transaction so the consumer's domain write, the ActionRequest row, audit event, and outbox record commit or roll back together; it owns no pool and may not open an independent transaction:

```java
package com.inforvans.accord.action.api;

public interface ActionRequestPort {
    ActionRequestEnvelope openOrReuse(DSLContext tx, OpenActionRequestCommand command);
    ActionRequestEnvelope supersede(DSLContext tx, SupersedeActionRequestCommand command);

    record ActionRequestTargetRef(
        String objectType, UUID objectId, long objectVersion, String objectDigest) {}

    record OpenActionRequestCommand(
        UUID tenantId, UUID projectId, String underlyingActionKey, String kind,
        ActionRequestTargetRef target, String targetSide, String targetRole,
        UUID targetAccountId, long assignmentRuleVersion, String title, String reason,
        String gateLabel, String blockerCode, List<String> decisionOptions, String risk,
        Instant dueAt, long escalationPolicyVersion, String causationType,
        UUID causationId, String causationDigest) {}

    record SupersedeActionRequestCommand(
        UUID tenantId, UUID projectId, String underlyingActionKey,
        long expectedActionVersion, String reasonCode,
        String replacementUnderlyingActionKey) {}
}
```

Create `action/api/package-info.java` with `@org.springframework.modulith.NamedInterface("action-request-api")`. Modify `ActionRequestService` to implement `ActionRequestPort` directly; there is no adapter with a second repository or transaction boundary. `ActionRequestTargetRef` is an immutable application-contract value and is mapped to the Task 7 domain target; no persistence/domain type imports the wire-only `ObjectRefDto`. `openOrReuse` verifies that transaction-local `app.tenant_id` equals `command.tenantId`, the project and target belong to that tenant, `kind` is one of the closed Task 7 `ActionType` values, the assignment side/role/account plus `assignmentRuleVersion` are currently valid, options are closed for that kind, the target version/hash still match, and `causationDigest` is the canonical digest of the named immutable causation object. It JCS-hashes every command field and uses `(tenant_id, underlying_action_key)` as the unique natural key: an exact duplicate returns the existing ActionRequest identity/version, while a changed digest returns `ACTION_KEY_CONFLICT` and never mutates the original. `supersede` locks that same row, requires its exact current version and `OPEN` phase, records the closed reason and optional replacement key, and advances once. Neither method accepts an HTTP principal, caller-selected completion state, dynamic command URL, tenant header, notification outcome, or source/material body.

Extend `ModuleBoundaryTest` and `RequirementApiContractTest` to prove `ActionRequestService` implements this interface, the `action::action-request-api` NamedInterface exports the port, command types, and formal `ActionRequestEnvelope`, every port signature is free of repository/infrastructure/service implementation types, and the module's internal application/repository/infrastructure packages are not named interfaces. Add transaction tests for exact-key reuse, changed-command conflict, cross-tenant transaction rejection, rollback after the caller writes but before commit, stale supersession, and one ActionRequest/audit/outbox effect under concurrent callers. Later modules must declare only `action::action-request-api`; importing `ActionRequestService`, a repository, or infrastructure is an architecture-test failure.

Create `ProjectEventDtos.java`:

```java
package com.inforvans.accord.action.api;

public final class ProjectEventDtos {
    private ProjectEventDtos() {}

    public record OrderedServerEvent(
        UUID eventId, UUID tenantId, UUID projectId, long sequence, String eventType,
        ObjectRefDto objectRef, long objectVersion, List<List<String>> queryKeys,
        JsonNode payload, Instant occurredAt) {}
    public record ProjectEventReplayDto(
        UUID projectId, long after, long headSequence, List<OrderedServerEvent> events,
        boolean hasMore, Long nextAfter) {}

    public interface ProjectEventHttpApi {
        Flux<ServerSentEvent<OrderedServerEvent>> stream(VerifiedRequestIdentity identity,
            UUID projectId, Long after, String lastEventId, int heartbeatSeconds);
        VersionedApiResult<ProjectEventReplayDto> replay(VerifiedRequestIdentity identity,
            UUID projectId, long after, int limit);
    }
}
```

- [ ] **Step 6: Implement every controller method against `VerifiedRequestIdentity`**

Use the following method sets exactly. The HTTP facades must validate authorization first, then parse and compare `If-Match` to body `expected_version`, then compare `expected_hash` where present, and only then reserve the idempotency key in the same tenant-scoped transaction. No controller accepts `tenantId`, actor/account identity for the caller, repository identity, or a reusable object-storage URL from a route, header, query, or body.

```java
// RequirementController.java
@RestController
@RequestMapping("/v1/projects/{projectId}")
public final class RequirementController {
    private final RequirementHttpApi api;

    public RequirementController(RequirementHttpApi api) { this.api = api; }

    @Operation(operationId = "listRequirements")
    @GetMapping("/requirements")
    public ResponseEntity<?> listRequirements(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String query, @RequestParam(name = "business_domain", required = false) String businessDomain, @RequestParam(name = "block_type", required = false) RequirementBlockType blockType, @RequestParam(required = false) RequirementRiskLevel risk, @RequestParam(required = false) RequirementDisplayPhase phase, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.listRequirements(identity, projectId, cursor, limit, query, businessDomain, blockType, risk, phase).toHttp(inm);
    }

    @Operation(operationId = "getRequirementGraph")
    @GetMapping("/requirements/graph")
    public ResponseEntity<?> getRequirementGraph(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @RequestParam(name = "at_sequence", required = false) Long atSequence, @RequestParam(required = false) String domain, @RequestParam(name = "block_type", required = false) RequirementBlockType blockType, @RequestParam(required = false) RequirementRiskLevel risk, @RequestParam(required = false) RequirementDisplayPhase phase, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getRequirementGraph(identity, projectId, atSequence, domain, blockType, risk, phase).toHttp(inm);
    }

    @Operation(operationId = "getRequirement")
    @GetMapping("/requirements/{requirementId}")
    public ResponseEntity<?> getRequirement(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getRequirement(identity, projectId, requirementId).toHttp(inm);
    }

    @Operation(operationId = "listRequirementRevisions")
    @GetMapping("/requirements/{requirementId}/revisions")
    public ResponseEntity<?> listRequirementRevisions(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.listRequirementRevisions(identity, projectId, requirementId, cursor, limit).toHttp(inm);
    }

    @Operation(operationId = "getRequirementRevision")
    @GetMapping("/requirements/{requirementId}/revisions/{revisionNo}")
    public ResponseEntity<?> getRequirementRevision(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable int revisionNo, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getRequirementRevision(identity, projectId, requirementId, revisionNo).toHttp(inm);
    }

    @Operation(operationId = "getRequirementBusinessProjection")
    @GetMapping("/requirements/{requirementId}/revisions/{revisionNo}/business-projection")
    public ResponseEntity<?> getRequirementBusinessProjection(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable int revisionNo, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getBusinessProjection(identity, projectId, requirementId, revisionNo).toHttp(inm);
    }

    @Operation(operationId = "getRequirementDevelopmentProjection")
    @GetMapping("/requirements/{requirementId}/revisions/{revisionNo}/development-projection")
    public ResponseEntity<?> getRequirementDevelopmentProjection(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable int revisionNo, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getDevelopmentProjection(identity, projectId, requirementId, revisionNo).toHttp(inm);
    }

    @Operation(operationId = "compareRequirementRevisions")
    @GetMapping("/requirements/{requirementId}/revisions/compare")
    public ResponseEntity<?> compareRequirementRevisions(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @RequestParam("from_revision_no") int from, @RequestParam("to_revision_no") int to, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.compareRevisions(identity, projectId, requirementId, from, to).toHttp(inm);
    }

    @Operation(operationId = "createRequirementDraft")
    @PostMapping("/requirement-drafts")
    public ResponseEntity<?> createRequirementDraft(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateRequirementDraftRequest request) {
        return api.createDraft(identity, projectId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "getRequirementDraft")
    @GetMapping("/requirement-drafts/{draftId}")
    public ResponseEntity<?> getRequirementDraft(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID draftId, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getDraft(identity, projectId, draftId).toHttp(inm);
    }

    @Operation(operationId = "updateRequirementDraft")
    @PatchMapping("/requirement-drafts/{draftId}")
    public ResponseEntity<?> updateRequirementDraft(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID draftId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody UpdateRequirementDraftRequest request) {
        return api.updateDraft(identity, projectId, draftId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.OK);
    }

    @Operation(operationId = "extractRequirementDraft")
    @PostMapping("/requirement-drafts/{draftId}:extract")
    public ResponseEntity<?> extractRequirementDraft(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID draftId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody ExtractRequirementDraftRequest request) {
        return api.extractDraft(identity, projectId, draftId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.ACCEPTED);
    }

    @Operation(operationId = "getRequirementDraftExtraction")
    @GetMapping("/requirement-drafts/{draftId}/extractions/{extractionId}")
    public ResponseEntity<?> getRequirementDraftExtraction(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID draftId, @PathVariable UUID extractionId, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getDraftExtraction(identity, projectId, draftId, extractionId).toHttp(inm);
    }

    @Operation(operationId = "createRequirementDraftTranscriptionUpload")
    @PostMapping("/requirement-drafts/{draftId}/transcription-uploads")
    public ResponseEntity<?> createRequirementDraftTranscriptionUpload(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID draftId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateTranscriptionUploadRequest request) {
        return api.createTranscriptionUpload(identity, projectId, draftId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "completeRequirementDraftTranscriptionUpload")
    @PostMapping("/requirement-drafts/{draftId}/transcription-uploads/{uploadId}:complete")
    public ResponseEntity<?> completeRequirementDraftTranscriptionUpload(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID draftId, @PathVariable UUID uploadId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CompleteTranscriptionUploadRequest request) {
        return api.completeTranscriptionUpload(identity, projectId, draftId, uploadId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.ACCEPTED);
    }

    @Operation(operationId = "getRequirementDraftTranscription")
    @GetMapping("/requirement-drafts/{draftId}/transcriptions/{transcriptionId}")
    public ResponseEntity<?> getRequirementDraftTranscription(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID draftId, @PathVariable UUID transcriptionId, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getDraftTranscription(identity, projectId, draftId, transcriptionId).toHttp(inm);
    }

    @Operation(operationId = "submitRequirementDraft")
    @PostMapping("/requirement-drafts/{draftId}:submit")
    public ResponseEntity<?> submitRequirementDraft(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID draftId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody SubmitRequirementDraftRequest request) {
        return api.submitDraft(identity, projectId, draftId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }
}
```

```java
// AttachmentController.java
@RestController
@RequestMapping("/v1/projects/{projectId}")
public final class AttachmentController {
    private final AttachmentHttpApi api;
    public AttachmentController(AttachmentHttpApi api) { this.api = api; }

    @Operation(operationId = "listAttachments")
    @GetMapping("/attachments")
    public ResponseEntity<?> listAttachments(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit, @RequestParam(name = "draft_id", required = false) UUID draftId, @RequestParam(name = "requirement_id", required = false) UUID requirementId, @RequestParam(required = false) AttachmentApiState state, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.listAttachments(identity, projectId, cursor, limit, draftId, requirementId, state).toHttp(inm);
    }

    @Operation(operationId = "createAttachmentUpload")
    @PostMapping("/attachment-uploads")
    public ResponseEntity<?> createAttachmentUpload(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateAttachmentUploadRequest request) {
        return api.createUpload(identity, projectId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "getAttachmentUpload")
    @GetMapping("/attachment-uploads/{uploadId}")
    public ResponseEntity<?> getAttachmentUpload(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID uploadId, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getUpload(identity, projectId, uploadId).toHttp(inm);
    }

    @Operation(operationId = "signAttachmentUploadPart")
    @PostMapping("/attachment-uploads/{uploadId}/parts/{partNumber}:sign")
    public ResponseEntity<?> signAttachmentUploadPart(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID uploadId, @PathVariable int partNumber, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody SignAttachmentUploadPartRequest request) {
        return api.signUploadPart(identity, projectId, uploadId, partNumber, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "listAttachmentUploadParts")
    @GetMapping("/attachment-uploads/{uploadId}/parts")
    public ResponseEntity<?> listAttachmentUploadParts(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID uploadId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "1000") int limit, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.listUploadParts(identity, projectId, uploadId, cursor, limit).toHttp(inm);
    }

    @Operation(operationId = "completeAttachmentUpload")
    @PostMapping("/attachment-uploads/{uploadId}:complete")
    public ResponseEntity<?> completeAttachmentUpload(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID uploadId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CompleteAttachmentUploadRequest request) {
        return api.completeUpload(identity, projectId, uploadId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.ACCEPTED);
    }

    @Operation(operationId = "abortAttachmentUpload")
    @PostMapping("/attachment-uploads/{uploadId}:abort")
    public ResponseEntity<?> abortAttachmentUpload(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID uploadId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody AbortAttachmentUploadRequest request) {
        return api.abortUpload(identity, projectId, uploadId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.OK);
    }

    @Operation(operationId = "getAttachmentVersion")
    @GetMapping("/attachments/{attachmentId}/versions/{version}")
    public ResponseEntity<?> getAttachmentVersion(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID attachmentId, @PathVariable int version, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getVersion(identity, projectId, attachmentId, version).toHttp(inm);
    }

    @Operation(operationId = "getAttachmentStatus")
    @GetMapping("/attachments/{attachmentId}/versions/{version}/status")
    public ResponseEntity<?> getAttachmentStatus(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID attachmentId, @PathVariable int version, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getStatus(identity, projectId, attachmentId, version).toHttp(inm);
    }

    @Operation(operationId = "createAttachmentPreviewCapability")
    @PostMapping("/attachments/{attachmentId}/versions/{version}/preview-capabilities")
    public ResponseEntity<?> createAttachmentPreviewCapability(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID attachmentId, @PathVariable int version, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateAttachmentCapabilityRequest request) {
        return api.createPreviewCapability(identity, projectId, attachmentId, version, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "createAttachmentDownloadCapability")
    @PostMapping("/attachments/{attachmentId}/versions/{version}/download-capabilities")
    public ResponseEntity<?> createAttachmentDownloadCapability(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID attachmentId, @PathVariable int version, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateAttachmentCapabilityRequest request) {
        return api.createDownloadCapability(identity, projectId, attachmentId, version, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "runAttachmentAccessPreflight")
    @PostMapping("/requirements/{requirementId}/revisions/{revisionNo}/attachment-access-preflight")
    public ResponseEntity<?> runAttachmentAccessPreflight(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable int revisionNo, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody RunAttachmentAccessPreflightRequest request) {
        return api.runAccessPreflight(identity, projectId, requirementId, revisionNo, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.OK);
    }
}
```

```java
// CollaborationController.java
@RestController
@RequestMapping("/v1/projects/{projectId}/requirements/{requirementId}")
public final class CollaborationController {
    private final CollaborationHttpApi api;
    public CollaborationController(CollaborationHttpApi api) { this.api = api; }

    @Operation(operationId = "createDevelopmentProposal")
    @PostMapping("/development-proposals")
    public ResponseEntity<?> createDevelopmentProposal(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateDevelopmentProposalRequest request) {
        return api.createProposal(identity, projectId, requirementId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "listDevelopmentProposals")
    @GetMapping("/development-proposals")
    public ResponseEntity<?> listDevelopmentProposals(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) ProposalApiStatus status, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.listProposals(identity, projectId, requirementId, cursor, limit, status).toHttp(inm);
    }

    @Operation(operationId = "getDevelopmentProposal")
    @GetMapping("/development-proposals/{proposalId}")
    public ResponseEntity<?> getDevelopmentProposal(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable UUID proposalId, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getProposal(identity, projectId, requirementId, proposalId).toHttp(inm);
    }

    @Operation(operationId = "resolveDevelopmentProposal")
    @PostMapping("/development-proposals/{proposalId}:resolve")
    public ResponseEntity<?> resolveDevelopmentProposal(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable UUID proposalId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody ResolveDevelopmentProposalRequest request) {
        return api.resolveProposal(identity, projectId, requirementId, proposalId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.OK);
    }

    @Operation(operationId = "listRequirementBusinessQuestions")
    @GetMapping("/business-questions")
    public ResponseEntity<?> listRequirementBusinessQuestions(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) BusinessQuestionApiStatus status, @RequestParam(required = false) Boolean blocking, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.listBusinessQuestions(identity, projectId, requirementId, cursor, limit, status, blocking).toHttp(inm);
    }

    @Operation(operationId = "createRequirementBusinessQuestion")
    @PostMapping("/business-questions")
    public ResponseEntity<?> createRequirementBusinessQuestion(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CreateBusinessQuestionRequest request) {
        return api.createBusinessQuestion(identity, projectId, requirementId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "getRequirementBusinessQuestion")
    @GetMapping("/business-questions/{questionId}")
    public ResponseEntity<?> getRequirementBusinessQuestion(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable UUID questionId, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.getBusinessQuestion(identity, projectId, requirementId, questionId).toHttp(inm);
    }

    @Operation(operationId = "addRequirementBusinessQuestionMessage")
    @PostMapping("/business-questions/{questionId}/messages")
    public ResponseEntity<?> addRequirementBusinessQuestionMessage(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable UUID questionId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody AddBusinessQuestionMessageRequest request) {
        return api.addBusinessQuestionMessage(identity, projectId, requirementId, questionId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "resolveRequirementBusinessQuestion")
    @PostMapping("/business-questions/{questionId}:resolve")
    public ResponseEntity<?> resolveRequirementBusinessQuestion(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable UUID questionId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody ResolveBusinessQuestionRequest request) {
        return api.resolveBusinessQuestion(identity, projectId, requirementId, questionId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.OK);
    }

    @Operation(operationId = "confirmRequirementDevelopment")
    @PostMapping("/revisions/{revisionNo}:confirm-development")
    public ResponseEntity<?> confirmRequirementDevelopment(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable int revisionNo, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody ConfirmRequirementRequest request) {
        return api.confirmDevelopment(identity, projectId, requirementId, revisionNo, new CommandHeaders(key, ifMatch, fresh), request).toHttp(HttpStatus.CREATED);
    }

    @Operation(operationId = "confirmRequirementBusiness")
    @PostMapping("/revisions/{revisionNo}:confirm-business")
    public ResponseEntity<?> confirmRequirementBusiness(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID requirementId, @PathVariable int revisionNo, @RequestHeader("X-Accord-Fresh-Auth") UUID fresh, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody ConfirmRequirementRequest request) {
        return api.confirmBusiness(identity, projectId, requirementId, revisionNo, new CommandHeaders(key, ifMatch, fresh), request).toHttp(HttpStatus.CREATED);
    }
}
```

```java
// ActionRequestController.java
@RestController
@RequestMapping("/v1/projects/{projectId}/action-requests")
public final class ActionRequestController {
    private final ActionRequestHttpApi api;
    public ActionRequestController(ActionRequestHttpApi api) { this.api = api; }

    @Operation(operationId = "listActionRequests")
    @GetMapping
    public ResponseEntity<?> listActionRequests(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @RequestParam ActionInboxScope scope, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) ActionRequestApiPhase phase, @RequestParam(required = false) String type, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.list(identity, projectId, scope, cursor, limit, phase, type).toHttp(inm);
    }

    @Operation(operationId = "getActionRequest")
    @GetMapping("/{actionRequestId}")
    public ResponseEntity<?> getActionRequest(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID actionRequestId, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.get(identity, projectId, actionRequestId).toHttp(inm);
    }

    @Operation(operationId = "completeActionRequest")
    @PostMapping("/{actionRequestId}:complete")
    public ResponseEntity<?> completeActionRequest(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID actionRequestId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody CompleteActionRequestRequest request) {
        return api.complete(identity, projectId, actionRequestId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.OK);
    }

    @Operation(operationId = "declineActionRequest")
    @PostMapping("/{actionRequestId}:decline")
    public ResponseEntity<?> declineActionRequest(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID actionRequestId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody DeclineActionRequestRequest request) {
        return api.decline(identity, projectId, actionRequestId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.OK);
    }

    @Operation(operationId = "delegateActionRequest")
    @PostMapping("/{actionRequestId}:delegate")
    public ResponseEntity<?> delegateActionRequest(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @PathVariable UUID actionRequestId, @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch, @RequestBody DelegateActionRequestRequest request) {
        return api.delegate(identity, projectId, actionRequestId, new CommandHeaders(key, ifMatch), request).toHttp(HttpStatus.OK);
    }
}
```

```java
// ProjectEventController.java
@RestController
@RequestMapping("/v1/projects/{projectId}/events")
public final class ProjectEventController {
    private final ProjectEventHttpApi api;
    public ProjectEventController(ProjectEventHttpApi api) { this.api = api; }

    @Operation(operationId = "streamProjectEvents")
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<OrderedServerEvent>> streamProjectEvents(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @RequestParam(required = false) Long after, @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId, @RequestParam(name = "heartbeat_seconds", defaultValue = "30") int heartbeatSeconds) {
        return api.stream(identity, projectId, after, lastEventId, heartbeatSeconds);
    }

    @Operation(operationId = "replayProjectEvents")
    @GetMapping("/replay")
    public ResponseEntity<?> replayProjectEvents(@AuthenticationPrincipal VerifiedRequestIdentity identity, @PathVariable UUID projectId, @RequestParam long after, @RequestParam(defaultValue = "500") int limit, @RequestHeader(name = "If-None-Match", required = false) String inm) {
        return api.replay(identity, projectId, after, limit).toHttp(inm);
    }
}
```

- [ ] **Step 7: Add HTTP, security, SSE replay, and generated-client verification**

`RequirementWorkflowHttpIT` must execute real security filters, transaction boundaries, RLS, and controllers with Testcontainers PostgreSQL. Assert `200 -> If-None-Match -> 304`, quoted ETags, missing/malformed/mismatched `If-Match`, same-key byte-identical replay, changed-body idempotency rejection, stale version/hash conflict, async `202` receipts, development-first confirmation, and no optimistic resource state in mutation responses.

`RequirementWorkflowApiSecurityTest` must reflect over all five controllers and prove the only principal type is `VerifiedRequestIdentity`; reject any tenant/actor route, query, body, or header field; exercise tenant-A credentials against tenant-B colliding IDs; require indistinguishable `404` behavior; require fresh auth on both confirmation operations; and prove attachment responses are metadata or `ScopedAttachmentCapabilityDto`, never uploaded content or storage keys. It also proves only business-side authority can create/resolve a BusinessQuestion, only a development-side natural person can append `ANSWER`, neither side can supply author/actor fields, an answerer cannot self-resolve, stale/cross-Revision questions cannot be carried silently, and open blocking questions prevent confirmation. Delivery retries are internal worker recovery; a human remediation uses the returned ActionRequest's existing `allowed_actions` and `completeActionRequest`, so no dedicated notification URL or destination/provider secret enters the public API. For every mutation, test four authentication cases: browser session plus valid CSRF and allowed Origin succeeds; browser session without CSRF fails `403 CSRF_VALIDATION_FAILED`; browser session with a valid token but foreign/missing Origin fails; and OIDC bearer without CSRF reaches normal authorization. This keeps `Authorization` optional for the browser without exempting cookie requests from CSRF.

```java
@Test
void sameIdempotencyKeyReplaysExactlyAndChangedFingerprintIsRejected() {
    var first = post(command, identityA, "req-01HXYZ", "\"7\"");
    var replay = post(command, identityA, "req-01HXYZ", "\"7\"");

    assertThat(replay.status()).isEqualTo(first.status());
    assertThat(replay.headers().get("ETag")).isEqualTo(first.headers().get("ETag"));
    assertThat(replay.body()).containsExactly(first.body());
    assertThat(post(command.withReason("changed"), identityA, "req-01HXYZ", "\"7\"")
        .problem().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
}

@Test
void attachmentHttpSurfaceCannotReturnUploadedBytesOrStorageCoordinates() {
    assertThat(rawResponseTypes(AttachmentController.class))
        .doesNotContain(byte[].class, InputStream.class, Resource.class);
    assertThat(openApi.attachmentResponseSchemas()).containsExactlyInAnyOrder(
        "AttachmentPageDto", "AttachmentUploadDto", "AttachmentUploadPartPageDto",
        "AttachmentVersionDto", "AttachmentStatusDto", "ScopedAttachmentCapabilityDto",
        "AttachmentAccessPreflightDto", "CommandReceiptDto", "Problem");
}

@Test
void csrfIsConditionalOnBrowserSessionAuthentication() {
    assertThat(mutateAsBrowser(identityA, validCsrf, allowedOrigin).status()).isEqualTo(200);
    assertThat(mutateAsBrowser(identityA, null, allowedOrigin).problem().code())
        .isEqualTo("CSRF_VALIDATION_FAILED");
    assertThat(mutateAsBrowser(identityA, validCsrf, "https://foreign.example")
        .problem().code()).isEqualTo("CSRF_VALIDATION_FAILED");
    assertThat(mutateAsBearer(identityA, null).status()).isEqualTo(200);
}
```

`ProjectEventReplayIT` must persist events and reconnect across API replicas. The event `id` is the tenant-project `projection_sequence`; the same cursor never produces duplicate semantic events, a gap is never papered over, and a cursor older than the retention floor returns `410 REPLAY_WINDOW_EXPIRED` with a full-refetch instruction but no hidden event data.

```java
@Test
void sseReconnectAndReplayAreOrderedAndRetentionExpiryForcesRefetch() {
    publish(projectA, LongStream.rangeClosed(41, 45).boxed().toList());
    assertThat(stream(projectA, 41L, null).take(2).map(ServerSentEvent::id)
        .collectList().block()).containsExactly("42", "43");
    assertThat(stream(projectA, null, "43").take(2).map(ServerSentEvent::id)
        .collectList().block()).containsExactly("44", "45");
    assertThat(replay(projectA, 43).events()).extracting(OrderedServerEvent::sequence)
        .containsExactly(44L, 45L);

    compactProjectEventsBefore(projectA, 45);
    var response = replayResponse(projectA, 43);
    assertThat(response.status()).isEqualTo(410);
    assertThat(response.problem().code()).isEqualTo("REPLAY_WINDOW_EXPIRED");
}
```

Create the generated-client test after extending OpenAPI; it verifies that Orval retained the baseline and generated all 47 operations. It must import generated exports rather than duplicating request/response interfaces.

```ts
import { describe, expect, it } from 'vitest';
import * as generated from './generated/endpoints';

const requirementOperations = [
  'listRequirements', 'getRequirementGraph', 'getRequirement',
  'listRequirementRevisions', 'getRequirementRevision',
  'getRequirementBusinessProjection', 'getRequirementDevelopmentProjection',
  'compareRequirementRevisions', 'createRequirementDraft', 'getRequirementDraft',
  'updateRequirementDraft', 'extractRequirementDraft', 'getRequirementDraftExtraction',
  'createRequirementDraftTranscriptionUpload', 'completeRequirementDraftTranscriptionUpload',
  'getRequirementDraftTranscription', 'submitRequirementDraft', 'listAttachments',
  'createAttachmentUpload', 'getAttachmentUpload', 'signAttachmentUploadPart',
  'listAttachmentUploadParts', 'completeAttachmentUpload', 'abortAttachmentUpload',
  'getAttachmentVersion', 'getAttachmentStatus', 'createAttachmentPreviewCapability',
  'createAttachmentDownloadCapability', 'runAttachmentAccessPreflight',
  'createDevelopmentProposal', 'listDevelopmentProposals', 'getDevelopmentProposal',
  'resolveDevelopmentProposal', 'listRequirementBusinessQuestions',
  'createRequirementBusinessQuestion', 'getRequirementBusinessQuestion',
  'addRequirementBusinessQuestionMessage', 'resolveRequirementBusinessQuestion',
  'listActionRequests', 'getActionRequest',
  'completeActionRequest', 'declineActionRequest', 'delegateActionRequest',
  'confirmRequirementDevelopment', 'confirmRequirementBusiness',
  'streamProjectEvents', 'replayProjectEvents',
] as const;

describe('cumulative requirement workflow client', () => {
  it.each(requirementOperations)('exports %s from generated code', (operationId) => {
    expect(generated[operationId]).toBeTypeOf('function');
  });
  it('retains both foundation operations', () => {
    expect(generated.getReadiness).toBeTypeOf('function');
    expect(generated.validateContract).toBeTypeOf('function');
  });
});
```

Run:

```bash
pnpm contracts:test
pnpm contracts:lint
./gradlew :tests:api:test --tests '*RequirementApiContractTest'
./gradlew :tests:integration:test --tests '*RequirementWorkflowHttpIT' --tests '*ProjectEventReplayIT'
./gradlew :tests:security-negative:test --tests '*RequirementWorkflowApiSecurityTest'
pnpm api:generate
pnpm --filter @accord/api-client test -- requirement-workflow.contract.test.ts
pnpm --filter @accord/api-client typecheck
pnpm --filter @accord/api-client check:generated
```

Expected: all commands pass; the operation set is exactly Foundation plus these 47 operations; generated output is deterministic; every mutation enforces idempotency and concurrency; both confirmation commands require fresh auth; BusinessQuestions preserve revision and side ownership; graph/block discriminators generate closed unions; cross-tenant identifiers disclose nothing; attachment APIs expose only metadata/capabilities; and SSE replay is ordered, duplicate-free, and returns `410` outside the retention window.

- [ ] **Step 8: Commit the public contract**

```bash
git add contracts/openapi/accord-control-api.yaml apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platform/api/ApiContractDtos.java apps/control-plane/modules/requirement-graph/src/main/java/com/inforvans/accord/requirement/api apps/control-plane/modules/attachments-metadata/src/main/java/com/inforvans/accord/attachment/api apps/control-plane/modules/collaboration/src/main/java/com/inforvans/accord/collaboration/api apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/api apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/api/package-info.java apps/control-plane/modules/actions-notifications/src/main/java/com/inforvans/accord/action/application/ActionRequestService.java apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java tests/api/src/test/java/com/inforvans/accord/api/RequirementApiContractTest.java tests/integration/src/test/java/com/inforvans/accord/integration/RequirementWorkflowHttpIT.java tests/integration/src/test/java/com/inforvans/accord/integration/ProjectEventReplayIT.java tests/security-negative/src/test/java/com/inforvans/accord/security/RequirementWorkflowApiSecurityTest.java packages/api-client
git commit -m "feat(api): expose requirement workflow contracts"
```

### Task 10: Prove The Requirement Workflow End To End

**Files:**
- Modify: `package.json`
- Modify: `pnpm-lock.yaml`
- Create: `tests/system/playwright.config.ts`
- Create: `tests/e2e/requirement-workflow.spec.ts`
- Create: `tests/e2e/fixtures/requirement-personas.ts`
- Create: `tests/performance/k6/requirement-list.js`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/RequirementProjectionRecoveryTest.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/AttachmentRecoveryTest.java`
- Create: `docs/runbooks/requirement-projection-rebuild.md`
- Create: `docs/runbooks/attachment-quarantine.md`

- [ ] **Step 1: Add the production journey tests**

Cover text and edited speech intake; manual structured completion while the Agent runtime is unavailable; explicit final domain/type classification; first-screen goal/current-problem/target-result and the complete typed form round trip; prominent and ordinary Agent relation suggestions with explicit accept/reject decisions; default raw-audio `deletion_pending -> deleted`, provider ambiguity/retry/DLQ/`deletion_failed`, and explicit `retained_as_reference`; resumable attachments; contractual/reference classification; server-filtered graph/list parity; all seven standard block renderings; business-owned editing through a `revise_existing` draft and child Revision; a blocking BusinessQuestion with development answer and business resolution; proposal partial acceptance; old ActionRequest supersession; Identity-receipt-backed development-first/business-second confirmation including replay, concurrency, revocation, and Revision staleness; strict natural-person separation; access preflight; AI-eligibility port failure; context invalidation; two-tab 409 diff; Ready Pool revalidation; and all four notification channels with urgent/digest, exact five-field dedupe, escalation, stale-recipient suppression, receipt, retry, and DLQ behavior.
Tag the whole journey `@requirement-workflow`; tag the keyboard-only graph/list parity, dialog focus return, error-summary, 200% zoom, and attachment-status announcement cases `@a11y` so the accessibility gate selects real scenarios rather than an empty suite.

```ts
test('@requirement-workflow semantic change supersedes the exact prior confirmation', async ({ business, developer }) => {
  const oldHash = await developer.confirmCurrentRevision();
  await business.acceptProposalThatChangesScope();
  await expect(business.confirmButton()).toBeDisabled();
  await expect(business.currentRevisionHash()).not.toHaveText(oldHash);
  await expect(developer.action('development_confirmation')).toBeVisible();
});

test('@requirement-workflow business edit and question preserve revision and side ownership', async ({ business, developer }) => {
  const parent = await business.openCurrentRevision();
  await business.reviseExistingRequirement({ expectedEffect: '库存可再次销售' });
  await expect(business.parentRevisionHash()).toHaveText(parent.hash);
  const question = await business.askDevelopmentQuestion('/development_view/interfaces/0', true);
  await developer.answerBusinessQuestion(question, '沿用现有 InventoryReleasePolicy 边界');
  await expect(developer.resolveQuestionButton(question)).toHaveCount(0);
  await business.acceptQuestionAnswer(question);
  await expect(business.openBlockingQuestionCount()).toHaveText('0');
});

test('@requirement-workflow relation suggestions require human decisions before canonicalization', async ({ business }) => {
  const draft = await business.extractCompleteStructuredDraft();
  await business.acceptRelation(draft, 'relation-normal');
  await business.rejectRelation(draft, 'relation-conflict', '与已确认结算顺序冲突');
  const revision = await business.submitDraft(draft);
  await expect(revision.formalRelationIds()).toEqual(['relation-normal']);
  await expect(revision.provenanceDecision('relation-conflict')).toHaveText('已拒绝');
});

test('@requirement-workflow speech deletion is pending until provider proof and replayable', async ({ business }) => {
  const deleteDraft = await business.createSpeechDraft();
  const submitted = await business.submitSpeechDraft(deleteDraft, 'delete_after_submit');
  expect(submitted.audio_disposition.state).toBe('deletion_pending');
  expect(submitted.audio_disposition.retained_reference).toBeNull();
  expect(await business.replayLastSubmit()).toEqual(submitted);
  await business.completeProviderVersionDeleteWithMatchingReceipt();
  await expect.poll(() => business.currentAudioDisposition()).toMatchObject({ state: 'deleted' });

  const retained = await business.submitSpeechDraft(
    await business.createSpeechDraft(), 'retain_as_reference',
  );
  expect(retained.audio_disposition.state).toBe('retained_as_reference');
  expect(retained.audio_disposition.retained_reference?.binding_type).toBe('reference');
});
```

Create the one shared system-test runner used by this and all later subsystem plans. Merge these exact scripts and dependency into the existing root package without replacing Foundation/Web scripts:

```json
// package.json additions
{
  "scripts": {
    "test:system": "playwright test --config tests/system/playwright.config.ts tests/e2e",
    "test:security": "playwright test --config tests/system/playwright.config.ts tests/security-negative",
    "test:fault": "playwright test --config tests/system/playwright.config.ts tests/fault-injection",
    "test:provider": "playwright test --config tests/system/playwright.config.ts tests/provider-certification"
  },
  "devDependencies": { "@playwright/test": "1.61.1" }
}
```

```ts
// tests/system/playwright.config.ts
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '..',
  testMatch: [
    'e2e/**/*.spec.ts',
    'security-negative/**/*.spec.ts',
    'fault-injection/**/*.spec.ts',
    'provider-certification/**/*.spec.ts'
  ],
  forbidOnly: true,
  fullyParallel: false,
  workers: process.env.CI ? 2 : 1,
  retries: process.env.CI ? 2 : 0,
  timeout: 120_000,
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.ACCORD_SYSTEM_BASE_URL ?? 'http://127.0.0.1:18080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    extraHTTPHeaders: { 'X-Accord-Test-Run': process.env.ACCORD_TEST_RUN_ID ?? 'local-system' }
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    {
      name: 'github-enterprise',
      testMatch: ['provider-certification/github-enterprise/**/*.spec.ts'],
      use: { ...devices['Desktop Chrome'] }
    }
  ],
  reporter: [['list'], ['junit', { outputFile: 'build/test-results/system.xml' }]],
  outputDir: 'build/test-results/system-artifacts'
});
```

Run: `pnpm add -Dw @playwright/test@1.61.1 && pnpm install --frozen-lockfile && pnpm exec playwright install chromium firefox webkit`

Expected: the exact Playwright version is locked once, browser binaries install, and no second JavaScript lockfile is created.

- [ ] **Step 2: Run E2E against ephemeral production-like dependencies**

Run: `pnpm test:system --grep @requirement-workflow`

Expected: all Chromium, Firefox, and WebKit projects pass with no retries.

- [ ] **Step 3: Run isolation, property, accessibility, and performance gates**

Run: `./gradlew :tests:security-negative:test :tests:state-machine:test && pnpm test:system --grep @a11y && k6 run tests/performance/k6/requirement-list.js`

Expected: zero unauthorized reads/writes; all state properties pass; zero serious/critical axe violations; API p95 is at most 2 seconds at the certified tenant/project/requirement cardinality.

- [ ] **Step 4: Exercise projection and attachment recovery runbooks**

Rebuild projections from canonical rows plus ordered events, inject an interrupted attachment scan, and verify both converge without changing revision hashes or granting stale URLs.

Run: `./gradlew :tests:fault-injection:test --tests '*RequirementProjectionRecovery*' --tests '*AttachmentRecovery*'`

Expected: all tests pass and audit event counts remain unchanged by projection rebuild.

- [ ] **Step 5: Commit the workflow proof**

```bash
git add package.json pnpm-lock.yaml tests/system/playwright.config.ts tests/e2e tests/performance tests/fault-injection/src/test/java/com/inforvans/accord/fault/RequirementProjectionRecoveryTest.java tests/fault-injection/src/test/java/com/inforvans/accord/fault/AttachmentRecoveryTest.java docs/runbooks
git commit -m "test(requirements): certify the end-to-end workflow"
```

## Completion Gate

The plan is complete only when the read-only illustrative Appendix A deterministically maps to the closed UUID canonical fixture while its example digest is never claimed verifiable; the canonical Requirement Contract validates across Java, TypeScript, and Python implementations, with a separately packaged Java verifier; all standard blocks and graph nodes are closed discriminator unions; the complete business form and formal Revision projections preserve intent, typed blocks, scope, boundaries, metrics, acceptance, accepted relations, attachments, development view, unknowns, decisions, and source facts; every Agent relation suggestion has a human decision before submit; audio deletion proves `deletion_pending | deleted | deletion_failed | retained_as_reference`, provider receipt/read-back, ActionRequest/retry/DLQ/alert, and structured-text durability; semantic hash property tests pass; cross-tenant and cross-owner negative tests pass; server drafts survive refresh without browser persistence and support explicit business-owned child revisions; BusinessQuestions preserve revision, side, actor, message, resolution, and ActionRequest ownership; attachments fail closed through quarantine and revocation; exact-hash confirmations use only Identity's authoritative receipt plus FK-backed Requirement links and obey rollback/replay/concurrency/revocation/staleness/order/separation; Ready Pool revalidation detects policy/context/role/attachment drift; notifications honor Identity-owned per-project/per-user preferences across in-app/email/enterprise IM/mobile push with urgent/digest, exact `user + action + object + version + channel` dedupe, escalation, suppression, receipts, retry/backoff/DLQ, and ActionRequest independence; the graph, list, detail, and diff projections rebuild deterministically; and the full production journey passes without optimistic approval behavior.
