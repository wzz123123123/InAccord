# Accord Git Delivery Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build auditable multi-Provider onboarding and immutable RepositoryBindings, cross-repository DeliveryBatch contracts, platform-signed development packages, source-free branch/PR/check control, customer-owned WorkItem execution gates, standard-mode detection/recovery, strict single-entry merge control, CompletionSet aggregation, and controlled abort/hotfix/break-glass paths.

**Architecture:** The Java control plane owns Provider connection/discovery/onboarding orchestration, batch, commitment, RepositoryWorkSet, WorkItem, Assignment, capability snapshot, intent, CompletionSet, and reconciliation facts; Identity remains the only RepositoryBinding lifecycle authority. Development Package Service creates signed, downloadable platform artifacts without writing Git. Independent Java Spring Boot workload profiles retain distinct deployment, identity, database role, credential, and network boundaries: Provider Auth Callback Edge consumes one-time authorization responses, Webhook Edge authenticates signals, Credential Broker resolves external secret references, Connector Runtime executes one capability-gated Provider adapter, Merge Controller merges one of three verified strict subjects, and BreakGlass Broker uses a separately fenced recovery lane. Provider adapters expose immutable repository/ref/commit/tree/check/protection metadata and narrowly typed branch, ChangeRequest, check, and merge commands; they expose no blob, source, diff-read, clone, archive, arbitrary URL, or generic Provider proxy.

**Tech Stack:** Java 21, Gradle 8.14.3 Groovy DSL, Spring Boot 3.5.3, Spring Modulith 1.4.1, jOOQ, Flyway, PostgreSQL 17.5, Temporal Java SDK, protobuf/gRPC with mTLS, JSON Schema 2020-12, RFC 8785 JCS, DSSE, capability-gated adapters for GitHub Cloud/Enterprise Server, GitLab SaaS/Self-Managed, Gitee/Gitee Enterprise, Azure DevOps Services/Server, and Bitbucket Cloud/Data Center, JUnit 5, AssertJ, jqwik, Testcontainers, WireMock, Toxiproxy, Pact/Buf, and Kubernetes NetworkPolicy.

---

## Dependencies And Guarantees

Execute after foundation, identity/trust, Requirement Workflow, and Project Context validation. Candidate construction and final acceptance are completed by the Candidate Acceptance plan.

The Identity plan must expose its internal `RepositoryBindingLifecyclePort` and Provider-availability evidence port before Git Task 5, and its finalized closed `BreakGlassAuthorizationBinding`, `IssueBreakGlassAuthorization`, and read-only `AuthorizationEvidenceService` contracts before Git Task 10 starts. These are dependency gates, not invitations for this plan to redefine Identity authority: NamedInterface/contract compatibility must pass before onboarding or the broker can be built.

Identity migration V010 is the only authority that creates, owns, changes, unbinds, or re-establishes a RepositoryBinding. Throughout this plan, public/schema field `repository_id` is the UUID `repository_binding_id`, never a Provider numeric repository ID, owner/name, URL, or a second delivery-owned identity. Provider-native immutable repository ID, endpoint identity, and external installation ID remain evidence attached through the V010 binding.

- One DeliveryBatch may contain multiple RepositoryWorkSets across Providers; one repository has at most one nonterminal normal DeliveryBatch, while future requirements continue through Ready Pool.
- Requirement Baselines, development packages, Project Context, and Context Patches are platform facts delivered through API/`accordctl`; no platform service commits them to customer Git.
- Developers perform clone/fetch/commit/push directly against their Provider. Context/Patch payloads come from local Codex and customer CI uploads bound to exact Provider repository and commit facts.
- Standard mode guarantees prechecks, bypass detection, audit, and recovery, not physical impossibility of administrator bypass.
- Strict mode's `WORK_ITEM_PR`, `ACCEPTED_DELIVERY_CANDIDATE`, and `EMERGENCY_CHANGE` merges come only from Merge Controller and use an exact-head compare-and-swap plus one-time short-lived authorization. Branch creation is a separately authorized create-ref operation, not a merge subject. Break glass is not a fourth merge subject and uses its independently fenced broker lane.
- Webhooks are hints. Active Provider reconciliation establishes refs, ancestry, checks, merge actor, protection, and uncertainty.
- Delivery mode is fixed at batch freeze and cannot silently degrade or change. A strict multi-repository batch freezes only when every RepositoryWorkSet satisfies the same versioned strict capability policy.
- Cross-Provider work is never presented as an atomic transaction. Partial branch release or delivery preserves successful repository facts, creates no automatic destructive rollback, and blocks overall completion until a complete CompletionSet exists.
- Provider credentials are installation-scoped external-secret references resolved only inside Credential Broker/Connector workloads. PAT, SSH private keys, and app passwords are migration or standard-mode credentials and never satisfy strict authentication policy.
- PostgreSQL 17.5 is the sole durable authority for command results, idempotency, inbox/outbox state, claims, leases, fencing generations, one-time authorization state, and reconciliation evidence. Process-local caches are disposable and cannot decide authorization, replay, ownership, or cross-replica coordination.

## File Map

```text
contracts/json-schema/delivery/
  delivery-batch.schema.json
  batch-amendment.schema.json
  repository-work-set.schema.json
  completion-set.schema.json
  development-package.schema.json
  work-item.schema.json
  work-item-completion.schema.json
  cancellation-decision.schema.json
contracts/json-schema/provider-onboarding/
  provider-connection-intent.schema.json
  provider-installation.schema.json
  repository-discovery.schema.json
  repository-binding-onboarding.schema.json
contracts/dsse-payloads/
  signing-claims-v2.schema.json
  development-package-publication.schema.json
  branch-release-attestation.schema.json
  merge-authorization.schema.json
  merge-subject-work-item-pr.schema.json
  merge-subject-accepted-candidate.schema.json
  merge-subject-emergency-change.schema.json
  branch-policy-attestation.schema.json
contracts/protobuf/accord/git/v1/provider_facts.proto
contracts/protobuf/accord/connector/v1/provider_connector.proto
contracts/protobuf/accord/credential/v1/credential_broker.proto
contracts/protobuf/accord/merge/v1/merge_controller.proto
contracts/gen/java/accord/signing/v1/
contracts/openapi/accord-control-api.yaml
contracts/openapi/provider-auth-callback.yaml
contracts/events/provider-webhook-signal.schema.json
database/control-plane/migrations/
  V040__delivery_batch.sql
  V041__git_intents_and_reconciliation.sql
  V042__workitem_execution.sql
  V043__delivery_emergency_and_abort.sql
database/webhook-edge/migrations/
  V002__forwarding_lease.sql
  V003__provider_auth_callback_inbox.sql
database/signing-service/migrations/V003__merge_subject_reservation.sql
database/break-glass-broker/migrations/V001__grant_consumption.sql
apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/
  domain/DeliveryModels.java
  domain/EffectiveBatchManifest.java
  domain/RepositoryWorkSet.java
  domain/CompletionSet.java
  application/BatchService.java
  api/DeliveryBatchController.java
  application/CommitmentService.java
apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/
  domain/WorkItemModels.java
  application/AssignmentService.java
  application/WorkItemGateService.java
  application/CompletionService.java
  api/WorkItemController.java
apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/
  application/ProviderRegistryService.java
  application/CapabilityEvaluationService.java
  application/ProviderOnboardingService.java
  application/ProviderCapabilityEvidenceAdapter.java
  application/BranchReleaseCoordinator.java
  application/ProviderFactService.java
  application/ReconciliationService.java
  application/StandardModeGuard.java
  application/EmergencyChangeService.java
  api/GitEvidenceController.java
  api/ProviderOnboardingController.java
  api/ReconciliationController.java
  api/StrictDeliveryController.java
  api/EmergencyChangeController.java
  workflow/BranchReleaseWorkflow.java
  workflow/ProviderOnboardingWorkflow.java
  workflow/ReconciliationWorkflow.java
apps/control-plane/modules/development-package/src/main/java/com/inforvans/accord/packagepublication/
  application/DevelopmentPackageService.java
  api/DevelopmentPackageController.java
apps/webhook-edge/
  build.gradle
  src/main/java/com/inforvans/accord/webhookedge/WebhookEdgeApplication.java
  src/main/java/com/inforvans/accord/webhookedge/security/ProviderSignatureVerifierRegistry.java
  src/main/java/com/inforvans/accord/webhookedge/inbox/PostgresWebhookInbox.java
  src/main/java/com/inforvans/accord/webhookedge/forwarding/WebhookSignalForwarder.java
  src/main/java/com/inforvans/accord/webhookedge/providercallback/ProviderAuthorizationCallbackHandler.java
  src/main/java/com/inforvans/accord/webhookedge/providercallback/ProviderAuthorizationCallbackForwarder.java
libs/java/git-provider-spi/
  build.gradle
  src/main/java/com/inforvans/accord/gitprovider/ProviderCapabilities.java
  src/main/java/com/inforvans/accord/gitprovider/ProviderOperations.java
libs/java/git-provider-tck/
libs/java/git-provider-sdk/
libs/java/git-provider-github/
libs/java/git-provider-gitlab/
libs/java/git-provider-gitee/
libs/java/git-provider-azure-devops/
libs/java/git-provider-bitbucket/
security-services/provider-connector/
  build.gradle
  src/main/java/com/inforvans/accord/connector/ProviderConnectorApplication.java
  src/main/java/com/inforvans/accord/connector/ConnectorCommandService.java
  src/main/java/com/inforvans/accord/connector/EndpointPolicy.java
security-services/credential-broker/
  build.gradle
  src/main/java/com/inforvans/accord/credential/CredentialBrokerApplication.java
  src/main/java/com/inforvans/accord/credential/ExternalSecretResolver.java
security-services/signing-service/
  build.gradle
  src/main/java/com/inforvans/accord/signing/nonce/PostgresAuthorizationReservationStore.java
  src/main/java/com/inforvans/accord/signing/signing/SigningService.java
  src/main/java/com/inforvans/accord/signing/api/SigningGrpcService.java
  src/main/java/com/inforvans/accord/signing/transport/MtlsCallerPolicy.java
security-services/merge-controller/
  build.gradle
  src/main/java/com/inforvans/accord/merge/MergeControllerApplication.java
  src/main/java/com/inforvans/accord/merge/MergeService.java
  src/main/java/com/inforvans/accord/merge/AuthorizationVerifier.java
  src/main/java/com/inforvans/accord/merge/ProviderCompareAndSwap.java
security-services/break-glass-broker/
  build.gradle
  src/main/java/com/inforvans/accord/breakglass/BreakGlassBrokerApplication.java
  src/main/java/com/inforvans/accord/breakglass/BreakGlassBrokerService.java
  src/main/java/com/inforvans/accord/breakglass/authorization/PostgresGrantReservationStore.java
tests/provider-certification/github/
tests/provider-certification/gitlab/
tests/provider-certification/gitee/
tests/provider-certification/azure-devops/
tests/provider-certification/bitbucket/
tests/fault-injection/git/
docs/runbooks/
  standard-bypass-recovery.md
  strict-assurance-recovery.md
  provider-uncertain-result.md
  batch-abort.md
  emergency-change.md
```

### Task 1: Freeze Cross-Repository Batch, Package, Branch Release, Completion, And Merge Contracts

**Files:**
- Modify: `settings.gradle`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java`
- Modify: `apps/control-plane/worker/build.gradle`
- Create: `apps/control-plane/modules/delivery/build.gradle`
- Create: `apps/control-plane/modules/git-coordination/build.gradle`
- Create: `apps/control-plane/modules/workitem-execution/build.gradle`
- Create: `apps/control-plane/modules/development-package/build.gradle`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/package-info.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/package-info.java`
- Create: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/package-info.java`
- Create: `apps/control-plane/modules/development-package/src/main/java/com/inforvans/accord/packagepublication/package-info.java`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/api/DeliveryApiModels.java`
- Modify: `tests/contract/build.gradle`
- Modify: `tests/integration/build.gradle`
- Modify: `tests/api/build.gradle`
- Modify: `tests/security-negative/build.gradle`
- Modify: `tests/state-machine/build.gradle`
- Modify: `tests/fault-injection/build.gradle`
- Modify: `contracts/openapi/accord-control-api.yaml`
- Modify: `contracts/openapi/ownership-manifest.yaml`
- Modify: `contracts/protobuf/accord/signing/v1/signing.proto`
- Create: `contracts/dsse-payloads/signing-claims-v2.schema.json`
- Regenerate: `contracts/gen/java/accord/signing/v1/`
- Create: `contracts/dsse-payloads/merge-subject-work-item-pr.schema.json`
- Create: `contracts/dsse-payloads/merge-subject-accepted-candidate.schema.json`
- Create: `contracts/dsse-payloads/merge-subject-emergency-change.schema.json`
- Create: `contracts/json-schema/delivery/delivery-batch.schema.json`
- Create: `contracts/json-schema/delivery/batch-amendment.schema.json`
- Create: `contracts/json-schema/delivery/work-item.schema.json`
- Create: `contracts/json-schema/delivery/work-item-completion.schema.json`
- Create: `contracts/json-schema/delivery/repository-work-set.schema.json`
- Create: `contracts/json-schema/delivery/completion-set.schema.json`
- Create: `contracts/json-schema/delivery/development-package.schema.json`
- Create: `contracts/json-schema/provider-onboarding/provider-connection-intent.schema.json`
- Create: `contracts/json-schema/provider-onboarding/provider-installation.schema.json`
- Create: `contracts/json-schema/provider-onboarding/repository-discovery.schema.json`
- Create: `contracts/json-schema/provider-onboarding/repository-binding-onboarding.schema.json`
- Create: `contracts/openapi/provider-auth-callback.yaml`
- Create: `contracts/dsse-payloads/development-package-publication.schema.json`
- Create: `contracts/dsse-payloads/branch-release-attestation.schema.json`
- Create: `contracts/dsse-payloads/merge-authorization.schema.json`
- Create: `contracts/dsse-payloads/branch-policy-attestation.schema.json`
- Create: `contracts/protobuf/accord/git/v1/provider_facts.proto`
- Create: `contracts/protobuf/accord/connector/v1/provider_connector.proto`
- Create: `contracts/protobuf/accord/credential/v1/credential_broker.proto`
- Create: `contracts/protobuf/accord/merge/v1/merge_controller.proto`
- Test: `tests/contract/src/test/java/com/inforvans/accord/contracts/DeliveryContractTest.java`
- Test: `tests/contract/src/test/java/com/inforvans/accord/contracts/SigningContractCompatibilityTest.java`
- Create: `tests/fixtures/delivery/batch.json`
- Create: `tests/fixtures/delivery/amendments/001-add-requirement.json`
- Create: `tests/fixtures/delivery/amendments/002-supersede-requirement.json`
- Create: `tests/fixtures/signing/merge-authorization-work-item.json`
- Create: `tests/fixtures/signing/merge-authorization-accepted-candidate.json`
- Create: `tests/fixtures/signing/merge-authorization-emergency-change.json`
- Create: `contracts/golden-fixtures/signing/merge-authorization-work-item.dsse.json`
- Create: `contracts/golden-fixtures/signing/merge-authorization-accepted-candidate.dsse.json`
- Create: `contracts/golden-fixtures/signing/merge-authorization-emergency-change.dsse.json`
- Create: `contracts/golden-fixtures/signing/claims-v2.input.json`
- Create: `contracts/golden-fixtures/signing/claims-v2.canonical.json`
- Create: `contracts/golden-fixtures/signing/claims-v2.sha256`
- Create: `contracts/golden-fixtures/package/development-package-publication.dsse.json`
- Create: `contracts/golden-fixtures/branch-release/branch-release-attestation.dsse.json`
- Generate: `packages/api-client/`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/DeliveryOpenApiContractTest.java`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/ProviderOnboardingOpenApiContractTest.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/DeliveryBrowserCsrfTest.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/ProviderOnboardingContractSecurityTest.java`
- Test: `tests/contracts/openapi-cumulative-merge.test.mjs`

- [ ] **Step 1: Add failing manifest-chain and authorization tests**

```java
@Test
void amendmentsFormOneDigestChainAndOneActiveCommitmentPerRequirement() {
    var batch = fixture("delivery/batch.json");
    var amendments = fixtures("delivery/amendments").stream()
        .sorted(comparingInt(node -> node.get("sequence").asInt()))
        .toList();

    assertThat(deliveryBatchSchema.validate(batch)).isEmpty();
    for (int index = 0; index < amendments.size(); index++) {
        var amendment = amendments.get(index);
        assertThat(batchAmendmentSchema.validate(amendment)).isEmpty();
        assertThat(amendment.get("previous_effective_digest").asText())
            .isEqualTo(effectiveDigestBefore(index));
    }

    var folded = foldContractFixtures(batch, amendments);
    assertThat(new HashSet<>(folded.activeRequirementIds()))
        .hasSameSizeAs(folded.activeRequirementIds());
}

@Test
void allThreeMergeSubjectsBindTheCompleteV2Authorization() {
    var fixturesBySubject = Map.of(
        "signing/merge-authorization-work-item.json", "work_item_pr",
        "signing/merge-authorization-accepted-candidate.json", "accepted_delivery_candidate",
        "signing/merge-authorization-emergency-change.json", "emergency_change");
    var expectedFields = Set.of(
        "binding_schema_version", "tenant_id", "repository_id", "target_ref",
        "expected_target_head", "source_head", "expected_result_tree", "normalized_diff_digest",
        "merge_subject", "effective_manifest_digest", "context_evidence_digest",
        "branch_policy_digest", "checks_digest", "ci_attestation_digest", "merge_method",
        "nonce", "expires_at");

    fixturesBySubject.forEach((path, subjectType) -> {
        var authorization = fixture(path);
        assertThat(mergeAuthorizationSchema.validate(authorization)).isEmpty();
        assertThat(fieldNames(authorization)).containsExactlyInAnyOrderElementsOf(expectedFields);
        assertThat(authorization.get("binding_schema_version").asInt()).isEqualTo(2);
        assertThat(authorization.at("/merge_subject/subject_type").asText()).isEqualTo(subjectType);
        assertThat(fieldNames(authorization.get("merge_subject")))
            .containsExactlyInAnyOrder("subject_type", "subject_id", "subject_digest");
    });
}

private static Set<String> fieldNames(JsonNode node) {
    var names = new HashSet<String>();
    node.fieldNames().forEachRemaining(names::add);
    return Set.copyOf(names);
}
```

Create `DeliveryOpenApiContractTest.java` against the cumulative OpenAPI document. It does not replace or regenerate paths owned by Foundation, Requirement Workflow, or Project Context/Assessment:

```java
class DeliveryOpenApiContractTest {
    private final OpenApiFixture api = OpenApiFixture.load("contracts/openapi/accord-control-api.yaml");
    private final Map<String, String> controllerMethods = Map.ofEntries(
        entry("listReadyPool", "DeliveryBatchController#readyPool"),
        entry("createDeliveryBatch", "DeliveryBatchController#create"),
        entry("getDeliveryBatch", "DeliveryBatchController#get"),
        entry("confirmDeliveryBatchManifest", "DeliveryBatchController#confirmManifest"),
        entry("releaseDeliveryBatchBranches", "DeliveryBatchController#releaseBranches"),
        entry("getDevelopmentPackage", "DevelopmentPackageController#get"),
        entry("amendDeliveryBatch", "DeliveryBatchController#amend"),
        entry("createBatchAbortDecision", "DeliveryBatchController#createAbortDecision"),
        entry("listDeliveryBatchWorkItems", "WorkItemController#list"),
        entry("createWorkItemAssignment", "WorkItemController#assign"),
        entry("acceptWorkItemAssignment", "WorkItemController#accept"),
        entry("requestWorkItemCompletionReview", "WorkItemController#requestCompletionReview"),
        entry("requestStrictWorkItemMerge", "WorkItemController#requestStrictMerge"),
        entry("getCompletionSet", "DeliveryBatchController#getCompletionSet"),
        entry("getRepositoryGitEvidence", "GitEvidenceController#get"),
        entry("getRepositoryReconciliation", "ReconciliationController#get"),
        entry("startRepositoryReconciliation", "ReconciliationController#start"),
        entry("applyReconciliationRecoveryCommand", "ReconciliationController#command"),
        entry("issueStrictMergeAuthorization", "StrictDeliveryController#issue"),
        entry("createEmergencyChange", "EmergencyChangeController#create"),
        entry("authorizeEmergencyChange", "EmergencyChangeController#authorize"),
        entry("issueEmergencyMergeAuthorization", "EmergencyChangeController#strictMerge"),
        entry("createBreakGlassGrant", "EmergencyChangeController#grant"),
        entry("consumeBreakGlassGrant", "EmergencyChangeController#consume"));

    private record Contract(
        String method, String path, int status, String schema,
        Set<String> requiredBody, boolean mutation, boolean freshAuth
    ) {}

    @Test
    void deliveryPublicSurfaceHasExactPathsStatusesSchemasAndReplayGuards() {
        var contracts = Map.ofEntries(
            entry("listReadyPool", read("GET", "/v1/projects/{projectId}/ready-pool", 200, "ReadyPoolPage")),
            entry("createDeliveryBatch", mutation("POST", "/v1/projects/{projectId}/delivery-batches", 201, "DeliveryBatchView", false, "expected_version", "repository_work_sets", "delivery_mode", "revision_hashes")),
            entry("getDeliveryBatch", read("GET", "/v1/projects/{projectId}/delivery-batches/{batchId}", 200, "DeliveryBatchView")),
            entry("confirmDeliveryBatchManifest", mutation("POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/manifest-confirmations", 201, "DeliveryBatchView", false, "expected_version", "effective_manifest_digest", "side")),
            entry("releaseDeliveryBatchBranches", mutation("POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/branch-release-requests", 202, "ExternalIntentView", false, "expected_version", "effective_manifest_digest", "repository_work_set_ids")),
            entry("getDevelopmentPackage", read("GET", "/v1/projects/{projectId}/delivery-batches/{batchId}/repository-work-sets/{repositoryWorkSetId}/development-package", 200, "DevelopmentPackageView")),
            entry("amendDeliveryBatch", mutation("POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/amendments", 201, "DeliveryBatchView", false, "expected_version", "previous_effective_digest", "changes")),
            entry("createBatchAbortDecision", mutation("POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/abort-decisions", 201, "BatchAbortDecisionView", true, "expected_version", "effective_manifest_digest", "reason")),
            entry("listDeliveryBatchWorkItems", read("GET", "/v1/projects/{projectId}/delivery-batches/{batchId}/work-items", 200, "WorkItemPage")),
            entry("createWorkItemAssignment", mutation("POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/work-items/{workItemId}/assignments", 201, "WorkItemPage", false, "expected_version", "work_item_id", "owner_account_id", "baseline_head_sha")),
            entry("acceptWorkItemAssignment", mutation("POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/work-items/{workItemId}/assignments/{assignmentId}/acceptance", 200, "WorkItemPage", false, "expected_version", "assignment_id", "baseline_head_sha")),
            entry("requestWorkItemCompletionReview", mutation("POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/work-items/{workItemId}/completion-review-requests", 202, "ActionRequestEnvelope", false, "expected_version", "provider_fact_digest")),
            entry("requestStrictWorkItemMerge", mutation("POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/work-items/{workItemId}/strict-merge-requests", 202, "ExternalIntentView", true, "expected_version", "assignment_id", "provider_fact_digest")),
            entry("getCompletionSet", read("GET", "/v1/projects/{projectId}/delivery-batches/{batchId}/completion-set", 200, "CompletionSetView")),
            entry("getRepositoryGitEvidence", read("GET", "/v1/projects/{projectId}/repositories/{repositoryId}/git-evidence", 200, "GitEvidenceView")),
            entry("getRepositoryReconciliation", read("GET", "/v1/projects/{projectId}/repositories/{repositoryId}/reconciliation", 200, "ReconciliationView")),
            entry("startRepositoryReconciliation", mutation("POST", "/v1/projects/{projectId}/repositories/{repositoryId}/reconciliations", 202, "ReconciliationView", false, "expected_version", "reason")),
            entry("applyReconciliationRecoveryCommand", mutation("POST", "/v1/projects/{projectId}/repositories/{repositoryId}/reconciliations/{reconciliationId}/commands", 202, "ReconciliationView", true, "expected_version", "command", "evidence_digests")),
            entry("issueStrictMergeAuthorization", mutation("POST", "/v1/projects/{projectId}/delivery-batches/{batchId}/strict-merge-authorizations", 201, "StrictMergeAuthorizationView", true, "expected_version", "candidate_id", "candidate_payload_digest", "expected_target_head", "candidate_tree", "acceptance_receipt_digests")),
            entry("createEmergencyChange", mutation("POST", "/v1/projects/{projectId}/emergency-changes", 201, "EmergencyChangeView", true, "expected_version", "repository_id", "incident_id", "change_kind", "severity", "target_ref", "expected_target_head", "responsible_developer_account_id", "scope", "rollback_plan")),
            entry("authorizeEmergencyChange", mutation("POST", "/v1/projects/{projectId}/emergency-changes/{emergencyChangeId}/authorizations", 201, "EmergencyChangeView", true, "expected_version", "emergency_change_id", "authorization_role", "subject_digest")),
            entry("issueEmergencyMergeAuthorization", mutation("POST", "/v1/projects/{projectId}/emergency-changes/{emergencyChangeId}/strict-merge-requests", 202, "ExternalIntentView", true, "expected_version", "emergency_change_id", "emergency_candidate_digest", "provider_fact_digest")),
            entry("createBreakGlassGrant", mutation("POST", "/v1/projects/{projectId}/break-glass-grants", 201, "BreakGlassGrantCreatedView", true, "expected_version", "incident_id", "repository_id", "target_ref", "scope", "reason", "provider_action", "provider_action_parameters", "provider_fact_baseline_digest", "expires_at")),
            entry("consumeBreakGlassGrant", mutation("POST", "/v1/projects/{projectId}/break-glass-grants/{grantId}/consumption", 201, "BreakGlassConsumptionView", true, "expected_version", "grant_id", "expected_grant_action_digest")));

        assertThat(api.operationIdsForTag("delivery-control"))
            .containsExactlyInAnyOrderElementsOf(contracts.keySet());
        contracts.forEach((operationId, expected) -> {
            var operation = api.operation(operationId);
            assertThat(operation.method()).isEqualTo(expected.method());
            assertThat(operation.path()).isEqualTo(expected.path());
            assertThat(operation.extension("x-controller-method"))
                .isEqualTo(controllerMethods.get(operationId));
            assertThat(operation.response(expected.status()).schemaRef())
                .isEqualTo("#/components/schemas/" + expected.schema());
            assertThat(operation.defaultProblemSchema()).isEqualTo("#/components/schemas/Problem");
            assertThat(operation.securityAlternatives())
                .containsExactlyInAnyOrder(Set.of("browserSession"), Set.of("oidc"));
            if (expected.mutation()) {
                assertThat(operation.header("Idempotency-Key").required()).isTrue();
                assertThat(operation.header("If-Match").required()).isTrue();
                assertThat(operation.headerRef("X-CSRF-Token"))
                    .isEqualTo("#/components/parameters/BrowserCsrfToken");
                assertThat(operation.extension("x-browser-csrf-required")).isEqualTo("conditional");
                assertThat(operation.referencedProblemCodes()).contains("CSRF_VALIDATION_FAILED");
                assertThat(operation.inlineProblemSchemaCount()).isZero();
                assertThat(operation.requestSchema().requiredPropertyNames())
                    .containsAll(expected.requiredBody());
                assertThat(operation.response(expected.status()).header("ETag").required()).isTrue();
            }
            if (expected.freshAuth()) {
                assertThat(operation.header("X-Accord-Fresh-Auth").required()).isTrue();
            }
            if (!expected.mutation() && !expected.schema().equals("Problem")) {
                assertThat(operation.response(expected.status()).header("ETag").required()).isTrue();
            }
        });
        assertThat(api.operation("listReadyPool").query("repository_id").required()).isFalse();
    }

    @Test
    void everyRepositoryReleaseIsIndependentlyCompleteAndBatchCoverageCannotLie() {
        var proof = api.schema("RepositoryReleaseProofView");
        assertThat(proof.requiredPropertyNames()).containsExactlyInAnyOrder(
            "release_id", "repository_work_set_id", "provider_installation_id",
            "repository_id", "effective_manifest_digest", "capability_snapshot_digest",
            "development_package_digest", "delivery_ref", "baseline_commit_sha",
            "baseline_tree_sha", "provider_fact_digest", "provider_state",
            "provider_observed_at", "receipt_digest");
        assertThat(proof.additionalPropertiesAllowed()).isFalse();
        assertThat(proof.property("provider_state").enumValues()).containsExactly("VERIFIED");
        var batch = api.schema("DeliveryBatchView");
        assertThat(batch.property("repository_releases").itemSchemaRef())
            .isEqualTo("#/components/schemas/RepositoryReleaseProofView");
        assertThat(batch.property("repository_release_coverage").enumValues())
            .containsExactly("NONE", "PARTIAL", "COMPLETE");
    }

    @Test
    void recoveryContractCannotExposeAStateToggle() {
        var command = api.schema("ReconciliationRecoveryCommand");
        assertThat(command.property("command").enumValues()).containsExactlyInAnyOrder(
            "REFRESH_PROVIDER_FACTS", "REBUILD_PROJECT_CONTEXT", "INVALIDATE_CANDIDATE",
            "REQUEST_REACCEPTANCE", "REVERIFY_BRANCH_POLICY", "PROVE_CONVERGENCE");
        assertThat(command.serializedText().toLowerCase(Locale.ROOT)).doesNotContain("set_active");
    }

    private static Contract read(String method, String path, int status, String schema) {
        return new Contract(method, path, status, schema, Set.of(), false, false);
    }

    private static Contract mutation(
        String method, String path, int status, String schema, boolean freshAuth, String... requiredBody
    ) {
        return new Contract(method, path, status, schema, Set.of(requiredBody), true, freshAuth);
    }
}
```

The cumulative document defines `browserSession` and `oidc` as alternative security requirements on every one of the 24 operations. Every mutation references the Identity-owned `BrowserCsrfToken` parameter (`X-CSRF-Token`, `required: false`) and sets `x-browser-csrf-required: conditional`: the security filter requires the token only when `browserSession` authenticates the request and never treats it as authorization when `oidc` is selected. All mutations reference the shared RFC 7807 problem `CSRF_VALIDATION_FAILED` for a missing/invalid token, disallowed Origin, or invalid Fetch Metadata; they do not create delivery-local headers, extensions, or problem variants. `DeliveryOpenApiContractTest` fails any operation that combines the schemes as an AND requirement, omits either alternative, duplicates a CSRF schema, or omits the conditional extension/problem response.

`DeliveryOpenApiContractTest` also declares the source version for every conditional command. Creating a Batch compares `If-Match` with the project-scoped Ready Pool projection ETag returned by `listReadyPool`; the optional repository filter changes only the page contents and never the version domain, so one monotonic project projection version covers every selected Revision and RepositoryBinding in a cross-repository create. Creating an EmergencyChange or BreakGlassGrant compares it with the repository-control projection ETag; child commands compare it with the parent aggregate ETag. The body `expected_version` must equal the numeric value represented by that ETag. Every versioned `GET` returns a strong quoted numeric `ETag`; creation never uses an unexplained aggregate version and never treats a client-supplied `0` as authority.

Add `DeliveryBrowserCsrfTest`: for every unsafe operation above, a cookie-authenticated request without the shared session-bound CSRF header, trusted `Origin`, and Fetch Metadata proof is rejected before authorization or domain I/O; a mismatched token/origin is rejected; the same operation with a valid browser proof reaches its controller; an OIDC bearer request is not required to send a CSRF token. This is a packaged black-box assertion of the Identity plan's conditional browser guard, not a second CSRF implementation.

- [ ] **Step 2: Run and verify missing contracts**

Run each command independently so both intended red contracts are recorded:

```bash
./gradlew :tests:contract:test --tests '*DeliveryContractTest'
./gradlew :tests:api:test --tests '*DeliveryOpenApiContractTest'
./gradlew :tests:security-negative:test --tests '*DeliveryBrowserCsrfTest'
```

Expected: the contract test fails naming missing delivery schemas and the API test fails naming the first absent delivery operation; the existing non-delivery paths remain parseable.

- [ ] **Step 3: Register the delivery control modules and verification dependencies**

Append these projects to `settings.gradle`:

```groovy
include ':apps:control-plane:modules:delivery',
    ':apps:control-plane:modules:git-coordination',
    ':apps:control-plane:modules:workitem-execution',
    ':apps:control-plane:modules:development-package'
```

Create `delivery/build.gradle`:

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
    implementation project(':apps:control-plane:modules:requirement-graph')
    implementation project(':apps:control-plane:modules:collaboration')
    implementation project(':apps:control-plane:modules:project-context')
    implementation project(':apps:control-plane:modules:assessment')
    implementation project(':database:control-plane')
    implementation libs.spring.boot.web
    implementation libs.spring.boot.validation
    implementation libs.spring.boot.jooq
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
tasks.withType(Test).configureEach { useJUnitPlatform() }
```

Create `git-coordination/build.gradle`:

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
    implementation project(':apps:control-plane:modules:project-context')
    implementation project(':apps:control-plane:modules:delivery')
    implementation project(':database:control-plane')
    implementation libs.spring.boot.web
    implementation libs.spring.boot.validation
    implementation libs.spring.boot.jooq
    implementation libs.temporal.sdk
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.temporal.testing
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
    testImplementation testFixtures(project(':database:control-plane'))
}
tasks.withType(Test).configureEach { useJUnitPlatform() }
```

Create `workitem-execution/build.gradle`:

```groovy
plugins { id 'java-library' }
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':apps:control-plane:modules:reliability')
    implementation project(':apps:control-plane:modules:identity')
    implementation project(':apps:control-plane:modules:authorization')
    implementation project(':apps:control-plane:modules:audit')
    implementation project(':apps:control-plane:modules:actions-notifications')
    implementation project(':apps:control-plane:modules:delivery')
    implementation project(':apps:control-plane:modules:git-coordination')
    implementation project(':apps:control-plane:modules:project-context')
    implementation project(':database:control-plane')
    implementation libs.spring.boot.web
    implementation libs.spring.boot.validation
    implementation libs.spring.boot.jooq
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
    testImplementation testFixtures(project(':database:control-plane'))
}
tasks.withType(Test).configureEach { useJUnitPlatform() }
```

Create `development-package/build.gradle` as a Java 21 `java-library` depending on platform-kernel, authorization, audit, Requirement Graph, attachment metadata, Project Context, delivery, and the generated DSSE contracts. It has no Git Provider adapter or credential dependency. Add all four projects to API and worker `implementation` dependencies. Add them to `integration`, `api`, `security-negative`, `state-machine`, and `fault-injection`; add `delivery` and `development-package` to `contract`. WorkItem Execution consumes only `ActionRequestPort`, its closed commands, and `ActionRequestEnvelope` from the Requirement-owned `action::action-request-api` NamedInterface; it never imports an ActionRequest service, repository, domain-internal, or infrastructure package. The compile-time direction is `workitem-execution -> git-coordination -> delivery` and `development-package -> delivery`; Delivery must never depend back on these projects, Git Coordination must never depend on WorkItem Execution or Development Package, and Development Package must never depend on Git Coordination. The additional `workitem-execution -> action::action-request-api` edge is explicit in `package-info.java`, and the Modulith/ArchUnit test rejects the unqualified `action` module, any other action package, and every reverse dependency.

Create each `package-info.java` with `@ApplicationModule` and the exact allowed dependencies represented above. Run the Modulith verification after registration and fail on a cycle, an undiscovered package, or access through a non-exported package.

In `ModuleBoundaryTest.java`, replace `requiredModules` with:

```java
private static final Set<String> REQUIRED_MODULES = Set.of(
    "platformkernel", "reliability",
    "identity", "authorization", "audit",
    "requirement", "attachment", "collaboration", "action",
    "context", "assessment",
    "delivery", "git", "workitem", "packagepublication");
```

- [ ] **Step 4: Define immutable delivery payloads**

`batch.json` binds tenant, project, batch ID, delivery mode, an ordered set of RepositoryWorkSets, policy/Pack/schema/support-unit versions, environment, commitments, and `batch_manifest_hash`. Each RepositoryWorkSet binds Provider installation, immutable repository ID, default ref/head/tree, planned delivery ref, Project Context lineage/basis, capability snapshot, authentication class, artifact profile, and its WorkItem IDs. Each Commitment binds exact revision hash/receipts/scores or overrides/context claims/WorkItems/assignees/acceptance owner. A WorkItem belongs to exactly one RepositoryWorkSet; cross-repository dependencies are explicit edges. Each amendment requires monotonically increasing sequence and `previous_effective_digest`.

```protobuf
message ProviderRefFact {
  string tenant_id = 1;
  string repository_id = 2;
  string ref = 3;
  string head_sha = 4;
  string tree_sha = 5;
  string observed_at = 6;
  string provider_request_id = 7;
  string provider_installation_id = 8;
  string provider_type = 9;
  string adapter_version = 10;
  string capability_snapshot_digest = 11;
}

message MergeRequestFact {
  string pull_request_id = 1;
  string source_head_sha = 2;
  string target_head_sha = 3;
  string result_tree_sha = 4;
  string actor_immutable_id = 5;
  string checks_digest = 6;
  string merge_state = 7;
}

enum MergeSubjectType {
  MERGE_SUBJECT_TYPE_UNSPECIFIED = 0;
  MERGE_SUBJECT_TYPE_WORK_ITEM_PR = 1;
  reserved 2;
  MERGE_SUBJECT_TYPE_ACCEPTED_DELIVERY_CANDIDATE = 3;
  MERGE_SUBJECT_TYPE_EMERGENCY_CHANGE = 4;
}

message VerifiedMergeSubject {
  MergeSubjectType subject_type = 1;
  string subject_id = 2;
  string subject_digest = 3;
}
```

`VerifiedMergeSubject` is the only generic part of merge authorization. A browser can never submit `subject_type`, `subject_id`, or `subject_digest`; the control plane constructs them from locked domain rows and versioned closed payload schemas. `subject_digest` is the RFC 8785 digest of one immutable subject payload:

- `WORK_ITEM_PR` binds Batch/effective manifest, Requirement Revision, WorkItem, Assignment and binding version, Provider PR ID, source/target/result tree, normalized diff, Patch or no-change receipt, Context basis, checks, and owner/reviewer evidence.
- `ACCEPTED_DELIVERY_CANDIDATE` binds Candidate payload/tree, active AcceptanceRun or continuity receipt digests, artifact policy/digest/build provenance, continuous Context watermark, effective manifest, and all current holds.
- `EMERGENCY_CHANGE` binds the immutable EmergencyChange version, incident/severity/scope/rollback digests, two-human authorization receipts, responsible developer, customer-CI/Patch evidence, emergency candidate, and affected lineage.

Synchronize the pre-existing signing boundary in this task without rewriting history. Identity already defines `VerifiedMergeSubject`, `source_head`, `expected_result_tree`, checks, and protobuf field numbers 1-10; retain those fields and numbers byte-for-byte. Append only the v2 policy/Context/manifest/merge-method binding and the reserve/finalize/direct-cancel RPCs, create `contracts/dsse-payloads/signing-claims-v2.schema.json` plus independent v2 goldens, and keep `signing-claims.schema.json` and `claims.{input,canonical,sha256}` as immutable v1 verification fixtures. The generated Java contracts and compatibility tests must verify historical v1 envelopes indefinitely while rejecting v1 for every new strict issuance or reservation. This introduces no signing-service dependency on delivery domain code. Unknown subject types and any attempted break-glass subject fail closed. BreakGlass uses the separate closed `BreakGlassAuthorizationBinding` owned by Identity Signing Task 10 and consumed by this plan's Task 10; it never consumes a strict merge token.

The protobuf API must not define `GetBlob`, `GetContent`, `GetTreeEntries`, `GetDiff`, `GetPatch`, repository clone/fetch/push, archive, code search, arbitrary GraphQL text, or arbitrary URL methods. Connector commands are closed messages for identity/capability discovery, create-ref, ChangeRequest, checks, protection, reconciliation, and exact merge. Credential Broker responses are audience-bound, installation-scoped and secret-bearing only on mTLS workload RPCs; no public API or Temporal payload may contain them.

Extend `contracts/openapi/accord-control-api.yaml` in place with this exact public surface. Every path is project- or repository-addressed, tenant and actor come only from `VerifiedRequestIdentity`, every mutation references the shared `IdempotencyKey` and `ExpectedVersion` parameters, every successful mutation returns `ETag`, and every operation has the shared RFC 7807 default response.

| Operation ID | Method and path | Success body and non-generic failures |
|---|---|---|
| `listReadyPool` | `GET /v1/projects/{projectId}/ready-pool?repository_id={optionalRepositoryId}` | `200 ReadyPoolPage`, cursor page of exact revision/context/policy/receipt digests, repository coverage and blockers |
| `createDeliveryBatch` | `POST /v1/projects/{projectId}/delivery-batches` | `201 DeliveryBatchView`; `ACTIVE_BATCH_EXISTS`, `READY_POOL_ENTRY_STALE`, `DELIVERY_MODE_MISMATCH` |
| `getDeliveryBatch` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}` | `200 DeliveryBatchView`, effective manifest, RepositoryWorkSets, per-repository release proofs, coverage states, confirmations and `allowed_actions` |
| `confirmDeliveryBatchManifest` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/manifest-confirmations` | `201 DeliveryBatchView`; `MANIFEST_DIGEST_STALE`, `CONFIRMATION_ORDER_INVALID`, `SEPARATION_OF_DUTIES` |
| `releaseDeliveryBatchBranches` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/branch-release-requests` | `202 ExternalIntentView`; `PACKAGE_NOT_SIGNED`, `CAPABILITY_SNAPSHOT_STALE`, `BRANCH_RELEASE_PARTIAL`, `PROVIDER_OUTCOME_UNKNOWN` |
| `getDevelopmentPackage` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/repository-work-sets/{repositoryWorkSetId}/development-package` | `200 DevelopmentPackageView`, signed manifest, object version/digest and time-bounded download authorization; never source |
| `amendDeliveryBatch` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/amendments` | `201 DeliveryBatchView`, new effective digest and reconfirmation actions; `AMENDMENT_CHAIN_CONFLICT` |
| `createBatchAbortDecision` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/abort-decisions` | `201 BatchAbortDecisionView`; `ABORT_NOT_PROVEN_SAFE`, `EXTERNAL_INTENT_UNRESOLVED` |
| `listDeliveryBatchWorkItems` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/work-items` | `200 WorkItemPage`, assignments, gate evidence, completion facts |
| `createWorkItemAssignment` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/work-items/{workItemId}/assignments` | `201 WorkItemPage`; `BASELINE_STALE`, `OWNER_NOT_ELIGIBLE` |
| `acceptWorkItemAssignment` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/work-items/{workItemId}/assignments/{assignmentId}/acceptance` | `200 WorkItemPage`, active assignment and DevelopmentRun; `ASSIGNMENT_SUPERSEDED` |
| `requestWorkItemCompletionReview` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/work-items/{workItemId}/completion-review-requests` | `202 ActionRequestEnvelope`, never a fabricated completion; `PROVIDER_FACT_STALE` |
| `requestStrictWorkItemMerge` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/work-items/{workItemId}/strict-merge-requests` | `202 ExternalIntentView`; server derives `WORK_ITEM_PR`; `STRICT_WORK_ITEM_GATES_FAILED`, `PROVIDER_OUTCOME_UNKNOWN` |
| `getCompletionSet` | `GET /v1/projects/{projectId}/delivery-batches/{batchId}/completion-set` | `200 CompletionSetView`, exact per-repository commits/trees/artifacts/Context evidence and `NONE/PARTIAL/COMPLETE` coverage |
| `getRepositoryGitEvidence` | `GET /v1/projects/{projectId}/repositories/{repositoryId}/git-evidence` | `200 GitEvidenceView`, metadata facts/signatures/checks/watermarks/outcomes, never blobs/diffs/source |
| `getRepositoryReconciliation` | `GET /v1/projects/{projectId}/repositories/{repositoryId}/reconciliation` | `200 ReconciliationView`, latest run, divergence causes, required recovery actions and evidence |
| `startRepositoryReconciliation` | `POST /v1/projects/{projectId}/repositories/{repositoryId}/reconciliations` | `202 ReconciliationView`; `RECONCILIATION_ALREADY_RUNNING` |
| `applyReconciliationRecoveryCommand` | `POST /v1/projects/{projectId}/repositories/{repositoryId}/reconciliations/{reconciliationId}/commands` | `202 ReconciliationView`; `RECOVERY_SEQUENCE_INVALID`, `CONVERGENCE_NOT_PROVEN` |
| `issueStrictMergeAuthorization` | `POST /v1/projects/{projectId}/delivery-batches/{batchId}/strict-merge-authorizations` | `201 StrictMergeAuthorizationView`, server derives `ACCEPTED_DELIVERY_CANDIDATE` and excludes nonce secret; `ASSURANCE_DEGRADED`, `TARGET_HEAD_STALE` |
| `createEmergencyChange` | `POST /v1/projects/{projectId}/emergency-changes` | `201 EmergencyChangeView`; independent emergency lane |
| `authorizeEmergencyChange` | `POST /v1/projects/{projectId}/emergency-changes/{emergencyChangeId}/authorizations` | `201 EmergencyChangeView`; `SECOND_AUTHORIZER_REQUIRED` |
| `issueEmergencyMergeAuthorization` | `POST /v1/projects/{projectId}/emergency-changes/{emergencyChangeId}/strict-merge-requests` | `202 ExternalIntentView`, server derives `EMERGENCY_CHANGE`; `EMERGENCY_EVIDENCE_INCOMPLETE`, `PROVIDER_OUTCOME_UNKNOWN` |
| `createBreakGlassGrant` | `POST /v1/projects/{projectId}/break-glass-grants` | `201 BreakGlassGrantCreatedView`, no secret in the browser response; `FRESH_AUTH_REQUIRED` |
| `consumeBreakGlassGrant` | `POST /v1/projects/{projectId}/break-glass-grants/{grantId}/consumption` | `201 BreakGlassConsumptionView`; `GRANT_ALREADY_CONSUMED`, `GRANT_EXPIRED`, `SCOPE_MISMATCH` |

The OpenAPI components define closed request schemas and versioned response schemas for every row above. `DeliveryBatchView` exposes `phase`, `operational_state`, `consistency_state`, `assurance_state`, `repository_release_coverage`, `repository_delivery_coverage`, `effective_manifest_digest`, and `version` independently. `repository_releases` contains zero or more internally complete `RepositoryReleaseProofView` values keyed by RepositoryWorkSet; a batch cannot expose `COMPLETE` coverage until every required RepositoryWorkSet has exactly one current proof. A reader never receives a receipt digest combined with ref/SHA facts selected from different rows. `GitEvidenceView` contains only immutable Provider/installation/repository IDs, refs, SHA/tree/check/policy/actor facts and digests. `ReconciliationRecoveryCommand.command` uses only the six evidence-producing commands asserted by the contract test; there is no `SET_ACTIVE`, generic state patch, arbitrary URL, provider payload, source body, diff, or blob field. Every recovery command consumes fresh auth; `PROVE_CONVERGENCE` and any action that restores or promotes assurance do so in the same authorization/idempotency/CAS transaction that recomputes invariants from authoritative Provider/CI facts and appends the recovery receipt.

Create `DeliveryApiModels.java` as the server-side owner of every response name used by the controllers. The cumulative OpenAPI schemas use the same property names and closed enums; `DeliveryOpenApiContractTest` reflects over these Java record components and fails on a missing, renamed, or extra wire field. Do not return domain aggregates or inferred service return types directly:

```java
package com.inforvans.accord.delivery.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

public final class DeliveryApiModels {
    private DeliveryApiModels() {}

    public record ObjectRefView(String object_type, String object_id, long version) {}

    public record AllowedActionView(
        String operation_id,
        String method,
        String href,
        @Nullable Long expected_version,
        @Nullable String expected_hash,
        boolean idempotency_required,
        boolean fresh_auth_required
    ) {}

    public sealed interface ActionBearingView permits ReadyPoolPage, DeliveryBatchView,
        DevelopmentPackageView, CompletionSetView, ExternalIntentView,
        BatchAbortDecisionView, WorkItemPage, GitEvidenceView,
        ReconciliationView, StrictMergeAuthorizationView, EmergencyChangeView,
        BreakGlassGrantCreatedView, BreakGlassConsumptionView {
        ObjectRefView object_ref();
        String display_state();
        List<String> gate_explanations();
        List<AllowedActionView> allowed_actions();
    }

    public record BatchAmendmentChange(
        String change_type, String target_object_ref, @Nullable String before_digest,
        String after_digest, String reason
    ) {}

    public record ReadyPoolRowView(
        UUID requirement_id, String revision_hash, String context_basis_digest,
        UUID assessment_policy_id, long assessment_version,
        List<String> confirmation_receipt_digests,
        List<UUID> dependency_requirement_ids,
        @Nullable UUID owner_account_id,
        List<String> risk_labels,
        boolean eligible,
        List<String> gate_explanations
    ) {
        public ReadyPoolRowView {
            confirmation_receipt_digests = List.copyOf(confirmation_receipt_digests);
            dependency_requirement_ids = List.copyOf(dependency_requirement_ids);
            risk_labels = List.copyOf(risk_labels);
            gate_explanations = List.copyOf(gate_explanations);
        }
    }

    public record ReadyPoolPage(
        List<ReadyPoolRowView> items, @Nullable String next_cursor,
        ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public ReadyPoolPage {
            items = List.copyOf(items);
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record DeliveryCommitmentView(
        UUID requirement_id, String revision_hash, String context_basis_digest,
        long assessment_version, String assessment_brief_digest,
        List<String> confirmation_receipt_digests, List<UUID> work_item_ids,
        UUID business_acceptance_owner_account_id
    ) {
        public DeliveryCommitmentView {
            confirmation_receipt_digests = List.copyOf(confirmation_receipt_digests);
            work_item_ids = List.copyOf(work_item_ids);
        }
    }

    public record RepositoryWorkSetView(
        UUID repository_work_set_id, String provider_type, String provider_installation_id,
        UUID repository_id, String default_ref, String base_head_sha, String base_tree_sha,
        String planned_delivery_ref, String context_basis_digest,
        String capability_snapshot_digest, String authentication_class,
        List<UUID> work_item_ids, String state
    ) {
        public RepositoryWorkSetView { work_item_ids = List.copyOf(work_item_ids); }
    }

    public record RepositoryReleaseProofView(
        UUID release_id, UUID repository_work_set_id, String provider_installation_id,
        UUID repository_id, String effective_manifest_digest,
        String capability_snapshot_digest, String development_package_digest,
        String delivery_ref, String baseline_commit_sha, String baseline_tree_sha,
        String provider_fact_digest, String provider_state, Instant provider_observed_at,
        String receipt_digest
    ) {}

    public record DeliveryBatchView(
        UUID batch_id, UUID project_id, String delivery_mode,
        String phase, String operational_state, String consistency_state,
        String assurance_state, String repository_release_coverage,
        String repository_delivery_coverage, String effective_manifest_digest,
        List<RepositoryWorkSetView> repository_work_sets,
        List<RepositoryReleaseProofView> repository_releases,
        List<DeliveryCommitmentView> commitments,
        List<String> manifest_confirmation_receipt_digests,
        long version,
        ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public DeliveryBatchView {
            repository_work_sets = List.copyOf(repository_work_sets);
            repository_releases = List.copyOf(repository_releases);
            commitments = List.copyOf(commitments);
            manifest_confirmation_receipt_digests = List.copyOf(manifest_confirmation_receipt_digests);
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record ExternalIntentView(
        UUID intent_id, String intent_type, String request_digest,
        @Nullable String provider_request_id, String state,
        @Nullable String outcome_digest, long version,
        ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public ExternalIntentView {
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record DevelopmentPackageView(
        UUID package_id, UUID batch_id, UUID repository_work_set_id,
        String package_version, String manifest_digest, String object_version,
        String publication_attestation_digest, Instant published_at,
        Instant download_authorization_expires_at, long version,
        ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public DevelopmentPackageView {
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record RepositoryCompletionView(
        UUID repository_work_set_id, String provider_installation_id, UUID repository_id,
        String final_commit_sha, String final_tree_sha, @Nullable String artifact_digest,
        String context_version_digest, String candidate_digest,
        List<String> completion_receipt_digests, String state
    ) {
        public RepositoryCompletionView {
            completion_receipt_digests = List.copyOf(completion_receipt_digests);
        }
    }

    public record CompletionSetView(
        UUID completion_set_id, UUID batch_id, String coverage,
        List<RepositoryCompletionView> repositories, @Nullable String set_digest,
        long version, ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public CompletionSetView {
            repositories = List.copyOf(repositories);
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record BatchAbortDecisionView(
        UUID decision_id, UUID batch_id, String effective_manifest_digest,
        List<String> confirmation_receipt_digests, List<String> cleanup_evidence_digests,
        String phase, long version, ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public BatchAbortDecisionView {
            confirmation_receipt_digests = List.copyOf(confirmation_receipt_digests);
            cleanup_evidence_digests = List.copyOf(cleanup_evidence_digests);
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record WorkItemView(
        UUID work_item_id, UUID requirement_id, UUID repository_work_set_id,
        UUID repository_id, String revision_hash,
        @Nullable UUID owner_account_id, @Nullable UUID assignment_id,
        @Nullable String assignment_state, String baseline_head_sha,
        @Nullable String branch_ref, @Nullable String pull_request_id,
        @Nullable String check_summary_digest, @Nullable String context_patch_receipt_digest,
        @Nullable String completion_receipt_digest, List<String> gate_explanations,
        long version, List<AllowedActionView> allowed_actions
    ) {
        public WorkItemView {
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record WorkItemPage(
        List<WorkItemView> items, @Nullable String next_cursor,
        ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public WorkItemPage {
            items = List.copyOf(items);
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record GitRefFactView(
        String ref, String head_sha, String tree_sha, String ancestry_state
    ) {}

    public record GitCheckFactView(
        String check_name, String state, String result_digest, Instant observed_at
    ) {}

    public record GitEvidenceView(
        UUID repository_id, String provider_installation_id, List<GitRefFactView> refs,
        List<GitCheckFactView> checks, String branch_policy_digest,
        @Nullable String merge_actor_immutable_id, List<String> ci_attestation_digests,
        String provider_request_id, String fact_digest, Instant observed_at,
        boolean stale, long version, ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public GitEvidenceView {
            refs = List.copyOf(refs);
            checks = List.copyOf(checks);
            ci_attestation_digests = List.copyOf(ci_attestation_digests);
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record ReconciliationDifferenceView(
        String fact_key, String expected_digest, @Nullable String actual_digest, String state
    ) {}

    public record ReconciliationView(
        UUID reconciliation_id, UUID repository_id, String state, String source_cursor,
        List<ReconciliationDifferenceView> differences, List<String> evidence_digests,
        Instant started_at, @Nullable Instant completed_at, long version,
        ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public ReconciliationView {
            differences = List.copyOf(differences);
            evidence_digests = List.copyOf(evidence_digests);
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record StrictMergeAuthorizationView(
        UUID authorization_id, String merge_subject_type, String merge_subject_id,
        String merge_subject_digest, String expected_target_head,
        String expected_result_tree, String effective_manifest_digest,
        String branch_policy_digest, String checks_digest, String state,
        Instant expires_at, long version, ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public StrictMergeAuthorizationView {
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record EmergencyChangeView(
        UUID emergency_change_id, String incident_id, String change_kind, String severity,
        UUID repository_id, String target_ref, String expected_target_head,
        String scope_digest, String rollback_plan_digest,
        List<String> authorization_receipt_digests, String state, long version,
        ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public EmergencyChangeView {
            authorization_receipt_digests = List.copyOf(authorization_receipt_digests);
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record BreakGlassGrantCreatedView(
        UUID grant_id, String incident_id, UUID repository_id, String target_ref,
        String scope_digest, String reason_digest, String grant_action_digest,
        String provider_fact_baseline_digest, String broker_authorization_state,
        Instant expires_at, long version, ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public BreakGlassGrantCreatedView {
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }

    public record BreakGlassConsumptionView(
        UUID consumption_id, UUID grant_id, UUID provider_intent_id,
        @Nullable String provider_request_digest, String state, String audit_event_digest,
        Instant consumed_at, long version, ObjectRefView object_ref, String display_state,
        List<String> gate_explanations, List<AllowedActionView> allowed_actions
    ) implements ActionBearingView {
        public BreakGlassConsumptionView {
            gate_explanations = List.copyOf(gate_explanations);
            allowed_actions = List.copyOf(allowed_actions);
        }
    }
}
```

Every collection-bearing record compact constructor applies `List.copyOf`, which also rejects null elements and references; only components annotated `@Nullable` may be absent. Add a reflection test that rejects a nullable primitive, an unannotated nullable reference, a mutable collection alias, a Jackson property name that differs from the record component, or a domain aggregate exposed as a controller return type.

All 24 operations use tag `delivery-control` and the exact `x-controller-method` value asserted above. For action-bearing schemas, OpenAPI requires `object_ref`, `display_state`, `gate_explanations`, and `allowed_actions`; mutation responses also require `version`. `BreakGlassGrantCreatedView` is stable under global idempotent replay and contains no raw nonce or recoverable secret. The public authorization views never contain a Merge Controller or break-glass nonce.

Use one YAML AST transaction in the implementation change: load the current OpenAPI document and `ownership-manifest.yaml`, add only these `paths`, operation IDs, schemas, problem-code examples, and the `delivery-control` owner entry, then serialize each file once. The owner entry contains the exact 24 unique operation IDs, their exact project-scoped paths, and only Delivery-owned components; it retains the Foundation, Identity, Requirement, and Agent Context entries byte-semantically and cannot claim a shared component owned by an earlier milestone. `openapi-cumulative-merge.test.mjs` verifies every manifest path/operation/component exists, the cross-owner operation union is unique, the owner count is exactly 24, and no previously owned key was deleted or retyped. The Java test separately snapshots all pre-existing operation IDs and component names and asserts they remain a subset after the edit. A missing/retyped earlier operation or a Delivery operation absent from the manifest fails this task rather than being accepted as generated-client churn.

Freeze Provider onboarding as a separate owner so administrative connection operations cannot be mistaken for Delivery actions. `ProviderOnboardingOpenApiContractTest` owns exactly these 12 generated control-plane operations:

```java
private static final Set<String> PROVIDER_ONBOARDING_OPERATIONS = Set.of(
    "createProviderConnectionIntent", "getProviderConnectionIntent",
    "completeProviderConnectionIntent", "listProviderInstallations",
    "getProviderInstallation", "rotateProviderInstallationCredential",
    "revokeProviderInstallation", "refreshProviderRepositoryDiscovery",
    "listProviderRepositoryDiscoveries", "createProjectRepositoryBinding",
    "getProjectRepositoryBindingOnboarding", "retryProjectRepositoryBindingOnboarding"
);
```

| Method and path | Operation ID | Profile |
| --- | --- | --- |
| `POST /v1/provider-connection-intents` | `createProviderConnectionIntent` | MF |
| `GET /v1/provider-connection-intents/{intentId}` | `getProviderConnectionIntent` | Q |
| `POST /v1/provider-connection-intents/{intentId}:complete` | `completeProviderConnectionIntent` | MF |
| `GET /v1/provider-installations` | `listProviderInstallations` | Q |
| `GET /v1/provider-installations/{installationId}` | `getProviderInstallation` | Q |
| `POST /v1/provider-installations/{installationId}:rotate-credential` | `rotateProviderInstallationCredential` | MF |
| `POST /v1/provider-installations/{installationId}:revoke` | `revokeProviderInstallation` | MF |
| `POST /v1/provider-installations/{installationId}/repository-discovery:refresh` | `refreshProviderRepositoryDiscovery` | M |
| `GET /v1/provider-installations/{installationId}/repository-discoveries` | `listProviderRepositoryDiscoveries` | Q |
| `POST /v1/projects/{projectId}/repository-bindings` | `createProjectRepositoryBinding` | MF |
| `GET /v1/projects/{projectId}/repository-binding-onboardings/{onboardingId}` | `getProjectRepositoryBindingOnboarding` | Q |
| `POST /v1/projects/{projectId}/repository-binding-onboardings/{onboardingId}:retry` | `retryProjectRepositoryBindingOnboarding` | MF |

Every schema is closed. Requests derive tenant and actor from `VerifiedRequestIdentity`; connection creation accepts only Provider family, `CLOUD | ENTERPRISE | SELF_MANAGED`, a normalized endpoint candidate, a closed authentication mode and a registered return-route ID. Manual completion accepts only a one-time Credential Broker capability reference and proof digest, never a token or secret reference selected by the browser. Discovery responses expose an opaque signed `repository_discovery_id`, display name, deployment label, default-ref label, expiry and server gate labels; immutable Provider repository/installation IDs remain server evidence. Binding creation accepts exactly `expected_version`, `repository_discovery_id` and requested mode, never an endpoint, owner/name, URL, Provider numeric ID, credential, branch or desired state.

The provider-hosted OAuth/App callback is deliberately not a generated browser operation and is not served by control-api. `contracts/openapi/provider-auth-callback.yaml` defines one exact `GET /callbacks/v1/provider/{provider}` surface on the separately authorized callback-edge workload profile. Its closed Provider path enum is the five built-ins; its allowlisted query variants accept only the fields required by that Provider, and every response is a fixed 303 to a pre-registered same-origin route with no code, state, installation ID or error detail in the redirect query. The edge authenticates the one-time state/PKCE/session binding, encrypts the received code or installation assertion before persistence, and forwards only an opaque callback receipt. It cannot access control-plane domain tables, Provider credentials, repository APIs or a generic redirect target. `ProviderOnboardingContractSecurityTest` rejects raw secret/token/code fields, arbitrary URL/redirect fields, body tenant/actor, Provider IDs used as scope, callback wildcards and callback operations included in the generated client.

- [ ] **Step 5: Run cross-language schema and protobuf compatibility tests**

Run:

```bash
buf format --diff --exit-code
buf lint
pwsh -NoProfile -File tests/architecture/verify-protobuf-compatibility.ps1
buf generate
pnpm contracts:lint
node --test tests/contracts/openapi-cumulative-merge.test.mjs
./gradlew :tests:contract:test --tests '*DeliveryContractTest'
./gradlew :tests:contract:test --tests '*SigningContractCompatibilityTest'
./gradlew :tests:api:test --tests '*DeliveryOpenApiContractTest'
./gradlew :tests:api:test --tests '*ProviderOnboardingOpenApiContractTest'
./gradlew :tests:security-negative:test --tests '*DeliveryBrowserCsrfTest'
./gradlew :tests:security-negative:test --tests '*ProviderOnboardingContractSecurityTest'
./gradlew :apps:control-plane:api:test --tests '*ModuleBoundaryTest'
pwsh -NoProfile -File tests/bootstrap/verify-workspace.ps1
pnpm contracts:generate
git diff --exit-code packages/api-client
./gradlew :tests:contract:compileTestJava
```

Expected: lint and tests pass; all earlier OpenAPI owners and operations still exist; the cumulative ownership manifest contains exactly 24 `delivery-control` and 12 `provider-onboarding` operations with no collision; the callback contract remains outside the browser client; a second client generation is clean; generated descriptors contain no source-reading RPC or secret-bearing onboarding field.

- [ ] **Step 6: Commit delivery contracts**

```bash
git add settings.gradle apps/control-plane/api/build.gradle apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java apps/control-plane/worker/build.gradle apps/control-plane/modules/delivery apps/control-plane/modules/development-package apps/control-plane/modules/git-coordination apps/control-plane/modules/workitem-execution contracts/openapi/accord-control-api.yaml contracts/openapi/ownership-manifest.yaml contracts/openapi/provider-auth-callback.yaml contracts/json-schema/delivery contracts/json-schema/provider-onboarding contracts/dsse-payloads contracts/protobuf/accord/signing/v1/signing.proto contracts/protobuf/accord/git contracts/protobuf/accord/connector contracts/protobuf/accord/credential contracts/protobuf/accord/merge contracts/gen/java contracts/golden-fixtures packages/api-client tests/contract tests/contracts/openapi-cumulative-merge.test.mjs tests/fixtures/delivery tests/fixtures/signing tests/integration/build.gradle tests/api tests/security-negative tests/state-machine/build.gradle tests/fault-injection/build.gradle
git commit -m "feat(delivery): define multi-repository delivery contracts"
```

### Task 2: Persist Cross-Repository DeliveryBatch And Fold Effective Manifests

**Files:**
- Create: `database/control-plane/migrations/V040__delivery_batch.sql`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/domain/DeliveryModels.java`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/domain/EffectiveBatchManifest.java`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/domain/RepositoryWorkSet.java`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/domain/CompletionSet.java`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/application/RepositoryCapabilityEvidencePort.java`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/application/BatchService.java`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/api/DeliveryBatchController.java`
- Test: `apps/control-plane/modules/delivery/src/test/java/com/inforvans/accord/delivery/BatchManifestPropertyTest.java`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/DeliveryBatchApiTest.java`
- Test: `tests/integration/src/test/java/com/inforvans/accord/integration/DeliveryMigrationsRlsIT.java`
- Test: `tests/state-machine/src/test/java/com/inforvans/accord/state/BatchStateProperties.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/BatchSecurityTest.java`

- [ ] **Step 1: Write failing single-active-batch, chain, and freeze tests**

```java
@Test
void everyRepositoryAcceptsOnlyOneNonterminalNormalBatchEvenAcrossProviders() {
    service.create(scope, List.of(githubRepository, gitlabRepository), request);
    assertThat(service.create(scope, List.of(gitlabRepository), request2).problemCode())
        .isEqualTo("ACTIVE_BATCH_EXISTS");
}

@Test
void strictBatchRequiresEveryRepositoryWorkSetToPassTheSameStrictPolicy() {
    assertThat(service.create(scope, List.of(strictCapable, standardOnly), strictRequest).problemCode())
        .isEqualTo("STRICT_CAPABILITY_INCOMPLETE");
}

@Property
void gapForkOrDuplicateActiveCommitmentAlwaysFailsClosed(
    @ForAll("invalidAmendmentChains") List<BatchAmendment> chain
) {
    assertThatThrownBy(() -> EffectiveBatchManifest.fold(initial, chain))
        .isInstanceOf(InvalidManifestChain.class);
}

@Test
void forgedTenantHeaderCannotSelectABatchAndStaleManifestConfirmationConflicts() throws Exception {
    mvc.perform(get("/v1/projects/{projectId}/delivery-batches/{batchId}", projectA, batchA)
            .with(verifiedIdentity(tenantB, businessB))
            .header("X-Accord-Tenant", tenantA.value()))
        .andExpect(status().isNotFound());

    mvc.perform(post("/v1/projects/{projectId}/delivery-batches/{batchId}/manifest-confirmations", projectA, batchA)
            .with(verifiedIdentity(tenantA, developerPrincipal))
            .header("Idempotency-Key", "confirm-manifest-0001")
            .header("If-Match", "\"4\"")
            .contentType(APPLICATION_JSON)
            .content(confirmationBody(staleDigest, 4)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MANIFEST_DIGEST_STALE"));
}
```

- [ ] **Step 2: Run and observe missing delivery module**

Run each command independently:

```bash
./gradlew :apps:control-plane:modules:delivery:test --tests '*BatchManifestPropertyTest'
./gradlew :tests:api:test --tests '*DeliveryBatchApiTest'
```

Expected: compilation fails for `BatchService` and the delivery controller is absent; no fallback mock route may make the API test pass.

- [ ] **Step 3: Add database constraints and explicit state dimensions**

```sql
create unique index uq_one_active_normal_batch_per_repository
on delivery_repository_work_set(tenant_id, repository_id)
where active and batch_kind = 'normal';

SELECT accord_security.enforce_tenant_table(name::regclass)
FROM unnest(ARRAY[
  'public.delivery_batch',
  'public.delivery_repository_work_set',
  'public.delivery_commitment',
  'public.delivery_batch_amendment',
  'public.delivery_manifest_confirmation',
  'public.delivery_completion_set',
  'public.delivery_completion_entry'
]) AS names(name);
```

```java
enum BatchPhase {
    PREPARING, FROZEN, PUBLISHING, READY, IN_DEVELOPMENT, CANDIDATE_BUILDING,
    IN_ACCEPTANCE, MERGE_READY, MERGING, RECONCILING, COMPLETED, ABORTED
}
enum OperationalState { ACTIVE, SUSPENDED }
enum ConsistencyState { CONVERGED, RECONCILIATION_REQUIRED, RECONCILING, DIVERGED }
enum AssuranceState { STANDARD, STRICT, DEGRADED }
enum RepositoryCoverage { NONE, PARTIAL, COMPLETE }
enum DeliveryMode { STANDARD, STRICT }
```

`V040__delivery_batch.sql` uses those seven exact tenant-table names, composite `(tenant_id, ...)` primary/foreign keys, and grants runtime DML only after all seven `enforce_tenant_table` calls succeed. `delivery_repository_work_set` binds one Batch to one V010 RepositoryBinding plus its exact Provider installation, immutable repository ID, default/delivery refs, exact base commit/tree, Context basis, authentication class, and CapabilitySnapshot digest; the complete binding tuple has a composite foreign key to V010, and a WorkItem composite foreign key must target one WorkSet from the same Batch. `delivery_completion_set` is the single batch-scoped aggregate row and stores only coverage, positive CAS version, nullable final set digest and completion time. `delivery_completion_entry` is append-only, has exactly one terminal evidence row per RepositoryWorkSet, and binds that row back to the same Batch, Provider installation and immutable repository. A named deferred constraint trigger rejects a transition of the aggregate to `COMPLETE` unless the current non-cancelled WorkSet set and completion-entry set are equal in both directions; once the final digest is present, neither the aggregate nor its entries may be rewritten or deleted. `delivery_manifest_confirmation` is append-only and binds side, current natural person, role-binding version, optional DualRole Principal authorization version, effective manifest digest, DSSE envelope digest, and confirmation time. `DeliveryMigrationsRlsIT` starts PostgreSQL 17.5, immediately calls `ControlPlaneTestRoles.bootstrap(postgres.jdbcUrl, postgres.username, postgres.password)`, and only then invokes `Flyway.configure()`. It inserts colliding IDs in two tenants through the migrator, proves the application role sees only its transaction tenant, rejects cross-tenant, wrong-binding, wrong-endpoint or cross-installation WorkSet/Commitment/Batch/CompletionEntry foreign keys, proves duplicate or missing repository completion entries cannot produce `COMPLETE`, and queries the catalog for exact `ENABLE`, `FORCE`, `FOR ALL TO PUBLIC`, `USING`, and `WITH CHECK` policy expressions.

Migration ordering is explicit: V040 cannot reference a table first created by V041. V040 therefore stores `provider_installation_id`, `repository_id`, and `capability_snapshot_digest` as one frozen selector, enforces the exact V010 RepositoryBinding tuple in PostgreSQL, and delegates current capability validation to the delivery-owned `RepositoryCapabilityEvidencePort`. The port returns a closed immutable result containing the same tenant, installation, RepositoryBinding, snapshot digest, authentication class, observation/expiry times, credential epoch, and eligible delivery modes; any mismatch, expiry, unavailable verifier, or unknown field fails Batch creation. Task 2 tests use a deterministic test implementation of this port, never an environment bypass. Task 5 supplies the production Git-coordination adapter and V041 adds the composite registration/snapshot foreign keys. V040-only is an implementation checkpoint, not a deployable production schema or runtime.

- [ ] **Step 4: Implement atomic Ready Pool revalidation and freeze**

In one transaction, lock selected Ready Pool rows, revalidate revision/receipts/policy/context claims/overrides/Pack/support unit/WorkItems/roles/acceptance owner and every requested RepositoryBinding/Provider installation/default head/mode/capability snapshot, create immutable RepositoryWorkSets and Commitments, calculate the ordered JCS manifest digest, and create dual-side batch confirmation ActionRequests. A strict batch reaches `FROZEN` only after all WorkSets satisfy the same strict policy and both side receipts bind the same digest. A pre-authorized DualRole Principal may create the two distinct receipts through two fresh-auth actions, but cannot synthesize both in one transaction or one click.

Implement `DeliveryBatchController.java` as the sole HTTP adapter for Ready Pool and Batch operations declared in Task 1. Its DTOs are closed and version-bound:

```java
record RepositoryWorkSetRequest(
    UUID repository_id, String provider_installation_id, String default_ref,
    String expected_base_head_sha, String expected_context_basis_digest,
    String capability_snapshot_digest, List<UUID> work_item_ids
) {
    RepositoryWorkSetRequest { work_item_ids = List.copyOf(work_item_ids); }
}
record CreateDeliveryBatchRequest(
    long expected_version, DeliveryMode delivery_mode,
    List<RepositoryWorkSetRequest> repository_work_sets, List<String> revision_hashes
) {
    CreateDeliveryBatchRequest {
        repository_work_sets = List.copyOf(repository_work_sets);
        revision_hashes = List.copyOf(revision_hashes);
    }
}
record ConfirmManifestRequest(
    long expected_version, String effective_manifest_digest, ConfirmationSide side
) {}
record AmendDeliveryBatchRequest(
    long expected_version, String previous_effective_digest, List<BatchAmendmentChange> changes
) {
    AmendDeliveryBatchRequest { changes = List.copyOf(changes); }
}
record ReleaseDeliveryBatchBranchesRequest(
    long expected_version, String effective_manifest_digest,
    List<UUID> repository_work_set_ids
) {
    ReleaseDeliveryBatchBranchesRequest {
        repository_work_set_ids = List.copyOf(repository_work_set_ids);
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}")
final class DeliveryBatchController {
    private final BatchService batches;
    private final ReadyPoolService readyPool;
    private final Clock clock;

    DeliveryBatchController(BatchService batches, ReadyPoolService readyPool, Clock clock) {
        this.batches = batches;
        this.readyPool = readyPool;
        this.clock = clock;
    }

    @GetMapping("/ready-pool")
    ReadyPoolPage readyPool(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @RequestParam(value = "repository_id", required = false) @Nullable UUID repositoryId,
        @RequestParam(required = false) @Nullable String cursor
    ) {
        return readyPool.listAuthorized(identity, projectId, repositoryId, cursor);
    }

    @GetMapping("/delivery-batches/{batchId}")
    DeliveryBatchView get(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId
    ) {
        return batches.getAuthorized(identity, projectId, batchId);
    }

    @PostMapping("/delivery-batches")
    ResponseEntity<DeliveryBatchView> create(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody CreateDeliveryBatchRequest request
    ) {
        return batches.createAuthorized(
            identity, projectId, idempotencyKey, ifMatch, request, clock.instant());
    }

    @PostMapping("/delivery-batches/{batchId}/manifest-confirmations")
    ResponseEntity<DeliveryBatchView> confirmManifest(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody ConfirmManifestRequest request
    ) {
        return batches.confirmManifestAuthorized(
            identity, projectId, batchId, idempotencyKey, ifMatch, request, clock.instant());
    }

    @PostMapping("/delivery-batches/{batchId}/branch-release-requests")
    ResponseEntity<ExternalIntentView> releaseBranches(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody ReleaseDeliveryBatchBranchesRequest request
    ) {
        return batches.releaseBranchesAuthorized(
            identity, projectId, batchId, idempotencyKey, ifMatch, request, clock.instant());
    }

    @PostMapping("/delivery-batches/{batchId}/amendments")
    ResponseEntity<DeliveryBatchView> amend(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody AmendDeliveryBatchRequest request
    ) {
        return batches.amendAuthorized(
            identity, projectId, batchId, idempotencyKey, ifMatch, request, clock.instant());
    }
}
```

Each `*Authorized` service entry opens one `AuthorizationService.authorizeAndExecute` transaction, derives tenant/actor only from `VerifiedRequestIdentity`, verifies route project and every repository/installation ownership under RLS, compares body `expected_version` with quoted `If-Match`, claims the persistent idempotency key, advances CAS, writes domain/audit/outbox records, and stores the byte-exact response before commit. It uses `Action.FREEZE_DELIVERY_BATCH`, `CONFIRM_DELIVERY_MANIFEST`, `RELEASE_DELIVERY_BRANCHES`, or `AMEND_DELIVERY_BATCH` as appropriate; an administrator binding alone never substitutes for a current side principal or valid DualRole Principal assignment. GET methods use the same server-derived scope and return 404 for both absent and unauthorized objects. Task 10 adds the abort command only after `AbortService` exists.

- [ ] **Step 5: Implement amendment and cancellation semantics**

Before work starts, unfreeze by superseding the manifest and reconfirming. After work starts, changed scope holds affected WorkItems/Candidates and either defers the new Revision or creates an ordered amendment with replacement Commitment. Cancellation requires a signed bilateral decision plus cleanup/revert completion or CI no-code proof.

- [ ] **Step 6: Run state, concurrency, and tenant-isolation tests**

Run:

```bash
./gradlew :apps:control-plane:modules:delivery:test --tests '*BatchManifestPropertyTest'
./gradlew :tests:state-machine:test --tests '*BatchStateProperties'
./gradlew :tests:security-negative:test --tests '*BatchSecurityTest'
./gradlew :tests:api:test --tests '*DeliveryBatchApiTest'
./gradlew :tests:integration:test --tests '*DeliveryMigrationsRlsIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; competing creates produce one batch and one 409; manifest chains never fork; tenant collisions cannot cross; every public tenant table through V040 has the exact forced standard policy; every control-plane fixture bootstraps roles before Flyway.

- [ ] **Step 7: Commit batch control**

```bash
git add database/control-plane/migrations/V040__delivery_batch.sql apps/control-plane/modules/delivery tests/api/src/test/java/com/inforvans/accord/api/DeliveryBatchApiTest.java tests/integration/src/test/java/com/inforvans/accord/integration/DeliveryMigrationsRlsIT.java tests/state-machine/src/test/java/com/inforvans/accord/state/BatchStateProperties.java tests/security-negative/src/test/java/com/inforvans/accord/security/BatchSecurityTest.java
git commit -m "feat(delivery): freeze immutable batch commitments"
```

### Task 3: Build The Capability-Gated Provider SPI, Isolated Connector Runtime, And Five Built-In Adapter Families

**Files:**
- Modify: `settings.gradle`
- Create: `libs/java/git-provider-spi/build.gradle`
- Create: `libs/java/git-provider-spi/src/main/java/com/inforvans/accord/gitprovider/ProviderCapabilities.java`
- Create: `libs/java/git-provider-spi/src/main/java/com/inforvans/accord/gitprovider/ProviderOperations.java`
- Create: `libs/java/git-provider-spi/src/main/java/com/inforvans/accord/gitprovider/ProviderModels.java`
- Create: `libs/java/git-provider-spi/src/test/java/com/inforvans/accord/gitprovider/NoSourceProviderApiTest.java`
- Create: `libs/java/git-provider-tck/`
- Create: `libs/java/git-provider-sdk/`
- Create: `libs/java/git-provider-github/`
- Create: `libs/java/git-provider-gitlab/`
- Create: `libs/java/git-provider-gitee/`
- Create: `libs/java/git-provider-azure-devops/`
- Create: `libs/java/git-provider-bitbucket/`
- Create: `security-services/provider-connector/`
- Create: `security-services/credential-broker/`
- Create: `contracts/provider-sdk/adapter-manifest.schema.json`
- Test: `tests/architecture/provider-boundary.test.mjs`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/ProviderCredentialIsolationTest.java`

- [ ] **Step 1: Add architecture tests that forbid source-reading methods and endpoints**

```java
@Test
void providerInterfaceHasNoSourceReadCapability() {
    var forbidden = Pattern.compile(
        "blob|content|diff|patch|archive|clone|raw|url", Pattern.CASE_INSENSITIVE);
    var methods = Arrays.stream(ProviderOperations.class.getDeclaredClasses())
        .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
        .toList();
    assertThat(methods)
        .allSatisfy(method -> {
            assertThat(method.getName()).doesNotContainPattern(forbidden);
            assertThat(method.getGenericParameterTypes())
                .allSatisfy(type -> assertThat(type.getTypeName()).doesNotContainPattern(forbidden));
            assertThat(method.getGenericReturnType().getTypeName()).doesNotContainPattern(forbidden);
        });
}
```

Add an allowlist-based egress test, not only a denylist. Every built-in adapter pins exact methods, endpoint templates, selected fields, pagination bounds, redirect policy, and maximum body size. GitHub GraphQL uses persisted queries; REST adapters deserialize closed DTOs. Tests reject commit endpoints that return file patches, compare/content/blob/tree-entry/archive/raw endpoints, arbitrary GraphQL text, redirects, generic Provider URLs, and any response-body logger. Fixtures place source, diff, commit-message, PR-body, attachment, and secret canaries in every unselected field and assert those bytes never cross the adapter transport boundary. No workload has an exception for creating platform-owned blobs or commits.

- [ ] **Step 2: Run and verify the provider package is absent**

Run: `./gradlew :libs:java:git-provider-spi:test :libs:java:git-provider-tck:test :security-services:provider-connector:test :security-services:credential-broker:test`

Expected: builds fail for missing capability interfaces, Connector boundary, Credential Broker contract, and TCK.

- [ ] **Step 3: Define only required facts and commands**

Register the SPI, TCK, SDK, five built-in adapter modules, Provider Connector, and Credential Broker in `settings.gradle`. Libraries use `java-library`; the two runtimes use Spring Boot. All pin Java 21 and the locked test platform. Adapters depend only on the SPI and approved HTTP/JSON libraries. The SPI has no Spring, application, control-plane, security-service, or Provider-specific dependency. Control-plane modules depend only on generated Connector RPCs, never concrete adapters. Connector depends on the SPI and exactly one adapter family per deployment image/profile. Credential Broker depends on generated contracts and an external-secret port, never an adapter. Architecture tests reject every reverse edge and any concrete Provider import outside its adapter module and certification tests.

```java
package com.inforvans.accord.gitprovider;

import static com.inforvans.accord.gitprovider.ProviderModels.*;
import java.util.List;

public final class ProviderOperations {
    private ProviderOperations() {}

    public interface Identity {
        ProviderResult<RepositoryIdentity> repositoryIdentity(
            ProviderRequestContext context, InstallationRef installation, RepositoryLocator locator);
    }
    public interface Capabilities {
        ProviderResult<InstallationProbe> probeInstallation(
            ProviderRequestContext context, InstallationRef installation);
        ProviderResult<RepositoryProbe> probeRepository(
            ProviderRequestContext context, RepositoryRef repository);
    }
    public interface Refs {
        ProviderResult<RefFact> refFact(
            ProviderRequestContext context, RepositoryRef repository, RefName ref);
        ProviderResult<OperationReceipt> createRef(
            ProviderCommandContext context, RepositoryRef repository,
            RefName ref, GitSha expectedSourceHead);
    }
    public interface ChangeRequests {
        ProviderResult<ChangeRequestFact> fact(
            ProviderRequestContext context, RepositoryRef repository, ChangeRequestId id);
        ProviderResult<OperationReceipt> create(
            ProviderCommandContext context, RepositoryRef repository,
            CreateChangeRequest command);
    }
    public interface Checks { ProviderResult<List<CheckFact>> facts(/* closed arguments */); }
    public interface Protection { ProviderResult<ProtectionFact> fact(/* closed arguments */); }
    public interface Reconciliation { ProviderResult<ReconciliationPage> facts(/* bounded cursor */); }
    public interface Merge {
        ProviderResult<OperationReceipt> mergeExact(
            ProviderCommandContext context, RepositoryRef repository, ExactMerge command);
    }
}
```

Create `ProviderModels.java` with only immutable, source-free values. The fact records below defensively copy collections in compact constructors; each SHA and digest value object validates its closed lowercase format. No record has a free-form Provider payload field:

```java
package com.inforvans.accord.gitprovider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ProviderModels {
    private ProviderModels() {}

    public record ProviderRequestContext(
        UUID tenantId, UUID correlationId, Instant deadline,
        EvidenceDigest capabilitySnapshotDigest
    ) {}
    public record ProviderCommandContext(
        UUID tenantId, UUID correlationId, UUID operationId, String idempotencyKey,
        Instant deadline, EvidenceDigest capabilitySnapshotDigest
    ) {}
    public record InstallationRef(
        String providerType, String deploymentType, String installationId, String endpointId
    ) {}
    public record RepositoryRef(
        InstallationRef installation, String immutableRepositoryId
    ) {}
    public record RepositoryLocator(String ownerOrProject, String repositoryName) {}
    public record RefName(String value) {}
    public record ChangeRequestId(String value) {}
    public record GitSha(String value) {
        public GitSha {
            if (!value.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid provider SHA");
            }
        }
    }
    public record EvidenceDigest(String value) {
        public EvidenceDigest {
            if (!value.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid evidence digest");
            }
        }
    }
    public record RepositoryIdentity(
        String provider, String enterpriseId, String organizationId,
        String installationId, String immutableRepositoryId
    ) {}
    public record RefFact(
        String ref, GitSha head, GitSha tree, Instant observedAt, String providerRequestId
    ) {}
    public record CommitMetadata(
        GitSha sha, GitSha tree, List<GitSha> parents, Instant observedAt,
        String providerRequestId
    ) {
        public CommitMetadata { parents = List.copyOf(parents); }
    }
    public record ChangeRequestFact(
        String changeRequestId, String sourceRef, GitSha sourceHead,
        String targetRef, GitSha targetHead, Optional<GitSha> resultTree,
        String actorImmutableId, String state, Instant observedAt, String providerRequestId
    ) {}
    public record CheckFact(
        String name, String state, EvidenceDigest resultDigest, Instant observedAt
    ) {}
    public record ProtectionFact(
        List<String> refs, Set<String> requiredChecks, boolean forcePushBlocked,
        boolean deleteBlocked, boolean administratorBypassBlocked,
        EvidenceDigest policyDigest, Instant observedAt, String providerRequestId
    ) {
        public ProtectionFact {
            refs = List.copyOf(refs);
            requiredChecks = Set.copyOf(requiredChecks);
        }
    }
    public record AncestryFact(
        GitSha ancestor, GitSha descendant, AncestryVerdict verdict,
        Instant observedAt, String providerRequestId
    ) {}
    public enum AncestryVerdict { ANCESTOR, NOT_ANCESTOR, UNKNOWN }
    public enum ProviderFailureKind {
        CONFIRMED_REJECTION, RETRYABLE_FAILURE, RATE_LIMITED, AUTHENTICATION_INVALID,
        AUTHORIZATION_INSUFFICIENT, CAPABILITY_DRIFT, CONCURRENCY_CONFLICT, OUTCOME_UNKNOWN
    }

    public sealed interface ProviderResult<T> permits Success, Failure {}
    public record Success<T>(T value) implements ProviderResult<T> {}
    public record Failure<T>(
        ProviderFailureKind kind, String stableCode, Optional<Duration> retryAfter
    ) implements ProviderResult<T> {}
}
```

`ProviderCapabilities.java` defines closed, versioned capability identifiers and semantic levels, including `CREATE_REF_EXACT`, `CHANGE_REQUEST_HEAD_EXACT`, `VERIFIED_WEBHOOK`, `ACTIVE_RECONCILIATION`, `REQUIRED_CHECKS`, `APPROVAL_FACTS`, `PROTECTION_ATTESTATION`, `MERGE_EXPECTED_HEAD_NATIVE`, and `MERGE_RESULT_EXACT`. `AdapterManifest`, `InstallationProbe`, and `RepositoryProbe` fold into an immutable `CapabilitySnapshot`; static declaration alone can never satisfy strict mode. Semantic variants remain explicit, so an emulated read-before-write merge cannot be mislabeled as native conditional merge.

Deploy the same signed Connector image as separately authorized workload profiles for discovery/read, branch/ChangeRequest control, strict merge, and break-glass support. Each profile has a distinct ServiceAccount, mTLS audience, Credential Broker policy, Provider permission set, endpoint allowlist, NetworkPolicy, and queue. No process or credential spans branch control and strict merge. Provider errors use the closed taxonomy above and preserve Provider request IDs without retaining arbitrary payloads.

- [ ] **Step 4: Implement Connector, Credential Broker, SDK, and the five built-in adapter families**

Connector accepts only generated mTLS commands, validates caller workload profile, adapter/SPI version, tenant/installation/repository binding, capability snapshot digest, command idempotency key, deadline, and endpoint policy before obtaining a credential. Credential Broker resolves an external-secret reference only for the exact tenant, installation, adapter, workload audience and operation class; it returns a short-lived secret over mTLS, never writes it to PostgreSQL/Temporal/logs, and zeroes in-memory holders after use. Rotation or revocation increments a credential epoch that invalidates Connector caches and every affected CapabilitySnapshot.

Implement the built-ins with these production identities and immutable identifiers:

- GitHub Cloud/Enterprise Server: GitHub App installation token, enterprise/organization and repository node/database IDs, persisted GraphQL plus closed REST endpoint templates.
- GitLab SaaS/Self-Managed: OAuth application or expiring project/group service identity, instance namespace/project IDs, closed REST/GraphQL selections by certified server version.
- Gitee/Gitee Enterprise: OAuth application plus dedicated service identity, enterprise/namespace/repository IDs, version-pinned API paths.
- Azure DevOps Services/Server: Entra service principal/managed identity or certified Server service identity, organization/collection/project/repository GUIDs and API-version-pinned requests.
- Bitbucket Cloud/Data Center: OAuth/workspace service identity, workspace/project/repository UUIDs or certified Data Center immutable IDs and version-pinned requests.

Repository rename preserves immutable identity; transfer, installation change, endpoint change, or identity reuse forces trust re-establishment. Re-read protected refs and policies at the end of every paginated scan; discard mixed snapshots when a watermark changes. Personal PAT, SSH private key, and app-password adapters are explicitly migration/standard-only and can never produce a strict authentication capability. If a self-managed version lacks a qualified identity or exact control primitive, its matrix row remains standard-only or unsupported.

`git-provider-sdk` publishes the manifest schema, generated RPC client/server contracts, TCK launcher, endpoint-policy API and packaging rules for out-of-process adapters. External packages are signed, digest-pinned OCI artifacts with a declared SPI range. They start standard-only; a separate Accord certification signature is required before their capability proof may contain a strict policy.

- [ ] **Step 5: Run TCK, identity, rate-limit, version, capability-drift, and no-source tests**

Run:

```bash
./gradlew :libs:java:git-provider-spi:test :libs:java:git-provider-tck:test :libs:java:git-provider-sdk:test
./gradlew :libs:java:git-provider-github:test :libs:java:git-provider-gitlab:test :libs:java:git-provider-gitee:test
./gradlew :libs:java:git-provider-azure-devops:test :libs:java:git-provider-bitbucket:test
./gradlew :security-services:provider-connector:test :security-services:credential-broker:test
./gradlew :tests:security-negative:test --tests '*ProviderCredentialIsolationTest'
node --test tests/architecture/provider-boundary.test.mjs
```

Expected: every adapter passes the common TCK for capabilities it declares; undeclared or dishonest strict capabilities fail; rate limits return retry-after metadata; credential epochs fence stale calls; tenant/installation/profile substitutions fail before secret retrieval; and no recorded HTTP exchange, database row, Temporal payload, log, Trace, or object contains patch/source bodies or Authorization headers.

- [ ] **Step 6: Commit Provider SPI**

```bash
git add settings.gradle contracts/provider-sdk contracts/protobuf/accord/connector contracts/protobuf/accord/credential libs/java/git-provider-spi libs/java/git-provider-tck libs/java/git-provider-sdk libs/java/git-provider-github libs/java/git-provider-gitlab libs/java/git-provider-gitee libs/java/git-provider-azure-devops libs/java/git-provider-bitbucket security-services/provider-connector security-services/credential-broker tests/architecture/provider-boundary.test.mjs tests/security-negative/src/test/java/com/inforvans/accord/security/ProviderCredentialIsolationTest.java
git commit -m "feat(git): add isolated multi-provider connector runtime"
```

### Task 4: Model Per-Repository Default And Delivery Project Context Lineages

**Files:**
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/DeliveryLineageService.java`
- Modify: `contracts/json-schema/delivery/delivery-batch.schema.json`
- Test: `apps/control-plane/modules/git-coordination/src/test/java/com/inforvans/accord/git/DeliveryLineageTest.java`
- Test: `tests/state-machine/src/test/java/com/inforvans/accord/state/DeliveryLineageProperties.java`

- [ ] **Step 1: Add failing derivation, reuse, abort, and promotion tests**

```java
@Test
void batchLineageIsDerivedFromImmutableDefaultContextBasis() {
    var delivery = service.derive(repositoryWorkSet, defaultContext);
    assertThat(delivery.parentLineageId()).isEqualTo(defaultContext.lineageId());
    assertThat(delivery.repositoryId()).isEqualTo(repositoryWorkSet.repositoryId());
    assertThat(delivery.basisCommitSha()).isEqualTo(repositoryWorkSet.defaultBaseSha());
    assertThat(delivery.basisTreeSha()).isEqualTo(repositoryWorkSet.defaultBaseTreeSha());
}

@Test
void abortedDeliveryLineageCannotSupportAFutureBatch() {
    service.markAborted(deliveryLineage);
    assertThat(service.reuseForNextBatch(deliveryLineage).problemCode())
        .isEqualTo("DELIVERY_LINEAGE_NOT_PROMOTED");
}
```

- [ ] **Step 2: Run and verify the lineage service is absent**

Run: `./gradlew :apps:control-plane:modules:git-coordination:test --tests '*DeliveryLineageTest'`

Expected: compilation fails for `DeliveryLineageService`.

- [ ] **Step 3: Derive one lineage per RepositoryWorkSet and keep defaults independent**

At batch creation, derive one delivery lineage for every RepositoryWorkSet from that repository's active default lineage at the frozen commit/tree and record `basis_ref = delivery/<batch-id>/develop`. Apply actual WorkItem merge receipts only to the matching tenant/Provider installation/repository lineage. Keep each repository's default lineage active and independently updated by unrelated merges or EmergencyChanges. Future-requirement impact analysis may combine claims across lineages only while preserving a source tuple for every claim; it must display Provider/repository/ref/commit/tree and cannot describe delivery state as production default state.

- [ ] **Step 4: Promote without consuming Patches twice**

After a repository Candidate is accepted and its final default merge is proven, create one `LINEAGE_PROMOTED` ContextMergeReceipt that binds RepositoryWorkSet, source delivery lineage, continuous Patch watermark, accepted Candidate, actual default merge SHA/tree, and target default lineage. Atomically advance only that repository's default materialized Context and watermark to the already-proven delivery state; do not reapply individual WorkItem Patches. Record the result as one CompletionSet entry. If a default changed, tree mismatches, or ancestry is uncertain, suspend only the affected WorkSet and mark the Batch delivery coverage `PARTIAL`; never promote another repository's lineage as compensation.

- [ ] **Step 5: Enforce reuse and failure rules**

A Requirement prepared against multiple delivery lineages can enter a later batch only after every referenced lineage is promoted and the later WorkSets derive from those promoted default results. Any aborted, diverged, unpromoted, or rolled-back referenced lineage forces impact reanalysis for that repository and invalidates the aggregate Analysis Basis until all source tuples are current.

Run: `./gradlew :apps:control-plane:modules:git-coordination:test :tests:state-machine:test --tests '*DeliveryLineage*'`

Expected: all tests pass; promotion advances one watermark exactly once, and aborted/diverged lineages never leak into a new Commitment.

- [ ] **Step 6: Commit lineage control**

```bash
git add apps/control-plane/modules/git-coordination contracts/json-schema/delivery/delivery-batch.schema.json tests/state-machine/src/test/java/com/inforvans/accord/state/DeliveryLineageProperties.java
git commit -m "feat(git): separate and promote delivery context lineages"
```

### Task 5: Persist Provider Registry, Onboard Repository Bindings, And Authenticate Multi-Provider Webhooks

**Files:**
- Modify: `apps/webhook-edge/build.gradle`
- Modify: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/WebhookEdgeApplication.java`
- Modify: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/WebhookHandler.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/ProviderSignatureVerifierRegistry.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitHubSignatureVerifier.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitLabSignatureVerifier.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GiteeSignatureVerifier.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/AzureDevOpsSignatureVerifier.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/BitbucketSignatureVerifier.java`
- Modify: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/inbox/PostgresWebhookInbox.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/forwarding/WebhookSignalForwarder.java`
- Create: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/forwarding/WebhookSignalForwarderTest.java`
- Create: `contracts/protobuf/accord/webhook/v1/webhook.proto`
- Create: `database/webhook-edge/migrations/V002__forwarding_lease.sql`
- Create: `database/webhook-edge/migrations/V003__provider_auth_callback_inbox.sql`
- Create: `database/control-plane/migrations/V041__git_intents_and_reconciliation.sql`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/ProviderRegistryService.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/CapabilityEvaluationService.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/ProviderCapabilityEvidenceAdapter.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/ProviderOnboardingService.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/api/ProviderOnboardingController.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/workflow/ProviderOnboardingWorkflow.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/providercallback/ProviderAuthorizationCallbackHandler.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/providercallback/ProviderAuthorizationCallbackForwarder.java`
- Modify: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/webhook/WebhookHandlerTest.java`
- Test: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/providercallback/ProviderAuthorizationCallbackHandlerTest.java`
- Test: `apps/control-plane/modules/git-coordination/src/test/java/com/inforvans/accord/git/ProviderOnboardingServiceTest.java`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/ProviderOnboardingApiTest.java`
- Modify: `tests/integration/src/test/java/com/inforvans/accord/integration/DeliveryMigrationsRlsIT.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/WebhookSecurityTest.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/ProviderOnboardingSecurityTest.java`

- [ ] **Step 1: Add bad-signature, replay, oversized-body, duplicate, and secret-rotation tests**

```java
@Test
void duplicateDeliveryWritesOneInboxSignal() {
    supportedProviders().forEach(provider -> {
        postSignedWebhook(provider, "delivery-7", body).expectStatus().isAccepted();
        postSignedWebhook(provider, "delivery-7", body).expectStatus().isAccepted();
        assertThat(inbox.count(provider, "delivery-7")).isOne();
    });
}

@Test
void reusedDeliveryWithDifferentBodyIsQuarantined() {
    postSignedWebhook("delivery-8", body).expectStatus().isAccepted();
    postSignedWebhook("delivery-8", changedBody).expectStatus().isEqualTo(409);
    assertThat(inbox.count("github", "delivery-8")).isOne();
    assertThat(securityIncidents.count("WEBHOOK_DELIVERY_DIGEST_CONFLICT", "delivery-8"))
        .isOne();
}
```

Add Provider onboarding tests before implementation:

```java
@Test
void callbackStateIsSingleUseSessionBoundAndNeverLeavesTheCallbackEdge() {
    var intent = connectionIntents.githubEnterprise(validEndpointProfile());
    callback.complete(intent, validProviderCallback()).expectStatus().is3xxRedirection();
    callback.complete(intent, validProviderCallback()).expectStatus().isEqualTo(409);
    assertThat(callbackInbox.onlyRow(intent.id()).encryptedAssertion()).isNotBlank();
    assertThat(callbackInbox.onlyRow(intent.id()).plaintextCode()).isNull();
    assertThat(controlPlaneSignals.onlyFor(intent.id()))
        .extracting(CallbackSignal::receiptDigest).hasSize(1);
}

@Test
void discoveryIdCannotBeReplayedForAnotherTenantProjectOrInstallation() {
    var discovery = onboarding.discover(tenantA, githubInstallation, repository77831);
    assertThatThrownBy(() -> onboarding.createBinding(tenantB, projectB, discovery.opaqueId()))
        .isInstanceOf(RepositoryDiscoveryNotFound.class);
    assertThatThrownBy(() -> onboarding.createBinding(tenantA, projectB, discovery.opaqueId()))
        .isInstanceOf(RepositoryDiscoveryScopeMismatch.class);
}

@Test
void bindingBecomesSelectableOnlyAfterTrustRegistrationAndCapabilityAgree() {
    var started = onboarding.createBinding(tenantA, projectA, currentDiscoveryId);
    assertThat(identity.binding(started.bindingId()).state()).isEqualTo(PENDING_TRUST);
    assertThat(setup.listSelectableBindings(projectA)).isEmpty();

    workflow.complete(started.onboardingId());

    var binding = identity.binding(started.bindingId());
    assertThat(binding.state()).isEqualTo(ACTIVE);
    assertThat(binding.trustEstablishmentId()).isNotNull();
    assertThat(registry.resolve(tenantA, binding.bindingId()).capabilitySnapshotDigest())
        .isEqualTo(workflow.snapshotDigest());
    assertThat(setup.listSelectableBindings(projectA)).containsExactly(binding.bindingId());
}
```

Extend `DeliveryMigrationsRlsIT` with colliding tenant rows for every V041 table and assert that a provider delivery ID, Provider request ID, or reconciliation ID is never sufficient without `tenant_id`. The test must fail before V041 exists and must reuse the pre-Flyway `ControlPlaneTestRoles.bootstrap` sequence established in Task 2.

- [ ] **Step 2: Run and observe missing callback, onboarding, and Webhook packages**

Run:

```bash
./gradlew :apps:webhook-edge:test --tests '*ProviderAuthorizationCallbackHandlerTest' --tests '*WebhookHandlerTest'
./gradlew :apps:control-plane:modules:git-coordination:test --tests '*ProviderOnboardingServiceTest'
./gradlew :tests:api:test --tests '*ProviderOnboardingApiTest'
```

Expected: compilation fails for the missing callback handler, onboarding service/controller and workflow; no fixture may create an `ACTIVE` Binding directly to make the tests pass.

- [ ] **Step 3: Persist onboarding, installation, repository, and capability facts**

`V041__git_intents_and_reconciliation.sql` does not create or own RepositoryBindings. It first asserts that Identity V010 and its endpoint-aware composite RepositoryBinding key are installed, then persists Provider adapter manifests, tenant-scoped installation records, `provider_repository_registration`, credential references and append-only CapabilitySnapshots before Webhook and reconciliation tables. An installation binds Provider family, Cloud/Server deployment, normalized endpoint identity, adapter/SPI versions and authentication class; it contains only an external-secret reference and credential epoch, never a token. `provider_repository_registration` is an update-forbidden technical association, not a second ownership record: its `repository_id` is exactly V010 `repository_binding_id`; its copied Provider family, endpoint identity, external installation ID and immutable repository ID participate only in composite foreign keys to V010 and the matching `provider_installation`; it contains no project owner, display name, URL, trust state or bind/unbind lifecycle. Binding creation, rename, transfer, unbind and trust re-establishment remain Identity operations; Provider Registry can only register and resolve an already-active V010 binding.

V040 and V041 are one indivisible M3 production migration set. V041 runs before any M3 application pod and uses a named preflight to require `delivery_repository_work_set` to be empty; a database containing V040 WorkSets is an unsupported partial rollout and migration stops without guessing registrations or snapshots. After creating the registry tables, V041 adds and immediately validates composite foreign keys from each WorkSet selector `(tenant_id, provider_installation_id, repository_id, capability_snapshot_digest)` to the exact immutable CapabilitySnapshot and from its installation/repository selector to `provider_repository_registration`. Flyway applies both migrations in one locked invocation, and deployment readiness rejects schema history that has V040 success without V041 success. `ProviderCapabilityEvidenceAdapter` implements the Task 2 port from these rows under the same tenant transaction and compares every selector again; the database key and application verifier are independent defenses.

`CapabilityEvaluationService` folds the signed AdapterManifest, live InstallationProbe and RepositoryProbe into an immutable snapshot with evidence digests, observed/expiry times and policy eligibility. It never trusts a self-declared strict flag. Credential epoch, adapter/SPI/server version, authentication class, permissions or protection changes expire the snapshot and append a hold/outbox event. `ProviderRegistryService` is the only Git-coordination application port that resolves `{tenant, repository_binding_id, installation, endpoint identity, immutable repository}` for Connector and Webhook workloads; it has no bind/unbind mutation. Hostname, repository name, Provider numeric ID without endpoint, or body-supplied tenant are never authority.

V041 also persists `provider_connection_intent`, `provider_repository_discovery`, and `repository_binding_onboarding`. A connection intent stores only closed Provider/deployment/authentication enums, normalized endpoint identity, registered redirect-route ID, actor/session binding digests, PKCE/state digests, expiry, callback receipt digest, external-secret reference after successful Broker handoff, state and positive CAS version. It never stores a callback code, token, PAT, private key, raw state or arbitrary URL. A discovery row is an immutable short-lived server projection keyed by a random opaque ID and bound to tenant, installation, credential epoch, immutable repository identity, display/default-ref metadata, observation digest and expiry. An onboarding row binds one project, discovery, resulting V010 Binding, Temporal workflow/run, current phase, last bounded error code and CAS version; it cannot act as repository ownership.

`ProviderOnboardingService` implements the 12 Task 1 control-plane operations. It normalizes and validates self-managed endpoints against a tenant-approved egress profile before creating an intent; Cloud endpoints are selected from the signed adapter manifest and cannot be overridden. OAuth/App intent views return only an exact `authorization_uri`, a separate `authorization_origin`, and one opaque intent reference. The origin comes only from the signed built-in Cloud manifest or the tenant-approved normalized self-managed endpoint profile; the closed schema and contract test require the URI to be HTTPS, contain no userinfo or fragment, and have an origin exactly equal to `authorization_origin`. Manual enterprise completion accepts one unconsumed Credential Broker upload capability; the Broker independently verifies audience, tenant, intent, authentication mode, expiry and proof-of-possession, writes the long-lived secret to the configured external secret manager, and returns only a stable secret reference plus credential epoch. Rotation creates a new epoch and invalidates old snapshots atomically with an outbox hold. Revocation fences the installation first, then moves every related Binding to `RECONCILING` through Identity's exported lifecycle port; it never silently substitutes credentials.

Repository discovery calls only the Connector Repository Identity/Protection probe methods and returns paged opaque discovery IDs. `createProjectRepositoryBinding` consumes one current discovery under fresh authentication and starts `ProviderOnboardingWorkflow`; it cannot accept or echo the Provider-native identity. The workflow uses the Foundation fenced-intent rules for every external call, invokes Identity's `createPendingBinding`, gathers signed Installation/Repository probe evidence, creates an immutable CapabilitySnapshot, calls Identity `activateAfterTrust`, and only then inserts `provider_repository_registration`. A crash or unknown result resumes by exact intent/workflow/binding IDs. If registration fails after Identity activation, the same workflow immediately calls `markReconciliationRequired`; setup remains fail-closed because availability requires both current facts. Retry reuses the same onboarding identity and cannot create a second Binding. Identity change requests of kind `REBIND` or `UNBIND` enter this workflow through a separate typed command and never bypass their required confirmations.

Keep `apps/webhook-edge` as an independently packaged Spring Boot application with its own workload identity, database credentials, Flyway location, ingress policy, and default-deny egress. Its Gradle project applies `java` and `org.springframework.boot`, pins the Java 21 toolchain, and uses the shared JUnit 5/AssertJ/Testcontainers platform. Route by installation-bound Provider type before parsing, then apply that Provider's exact signature/token/certificate verification, current/rotating secret, event/delivery ID rules, content type, replay metadata, body size, and rate limit. Do not invent a timestamp requirement the Provider does not authenticate. Resolve installation to tenant/repository without trusting body tenant fields. Foundation `V001__webhook_delivery.sql` is immutable after release; create `V002__forwarding_lease.sql` rather than editing V001. Store the byte-exact body only in an edge-local envelope-encrypted forensic column/object with a policy-capped short TTL and separate decrypt role; it is never forwarded, logged, indexed, exposed to Agent/administrators, or retained after normalization/replay expiry. Persist and forward only allowlisted normalized metadata plus body digest, stripping commit messages, user prose, file lists and unneeded fields. The edge has no control-plane domain-table or Git mutation permissions.

Deploy the same signed Webhook Edge image as a distinct `provider-auth-callback-edge` workload profile with a separate ServiceAccount, database role, encryption key, ingress host/path, mTLS audience, NetworkPolicy and queue. `V003__provider_auth_callback_inbox.sql` creates its own forced-RLS, envelope-encrypted, TTL-bounded callback inbox with hashed state, ciphertext, receipt digest, lease token/generation and append-only terminal receipt. This profile cannot read webhook rows, control-plane tables or Provider credentials and cannot call Provider repository APIs. It validates the exact Provider query variant, consumes the hashed state once, strips all callback values from redirect/log/trace/metrics, forwards only the opaque receipt over mTLS, and deletes ciphertext after the Broker proves exchange or the TTL expires. A timeout stays `OUTCOME_UNKNOWN`; it never reuses a code or assumes exchange failed.

`V002__forwarding_lease.sql` adds `PROCESSING` to the constrained lifecycle plus `lease_token uuid`, `lease_generation bigint`, `lease_owner`, `leased_until`, `attempt_count`, `next_attempt_at`, and bounded `last_error_code`. Its transition constraints require a token/generation/owner/expiry only in `PROCESSING`, clear lease fields in terminal states, and increase the generation on every takeover. In the same migration, re-run the signing-local `accord_security.enforce_tenant_table` assertion before changing runtime privileges; revoke all first, then grant only `SELECT, INSERT, UPDATE` to `accord_webhook_runtime`. A catalog/A-B test migrates V001 then V002, proves the runtime role has forced RLS and no cross-tenant lease/takeover/complete path, and proves Flyway accepts the original V001 checksum unchanged.

- [ ] **Step 4: Forward normalized signals through an mTLS inbox boundary**

Define `ProviderWebhookSignal` and `AcceptProviderWebhookSignal` in `contracts/protobuf/accord/webhook/v1/webhook.proto`. The edge forwarder atomically leases a `PENDING` or expired `PROCESSING` delivery and carries its random lease token plus monotonically increasing generation through acknowledgement; a late owner cannot complete after takeover. It sends tenant/repository/provider/delivery/event/ref/head/body digest and edge receive time with its workload identity. The control-plane inbox has one natural uniqueness key, `(tenant_id, provider, repository_id, delivery_id)`; `body_digest` is immutable evidence, not part of that key. In one tenant-scoped transaction, insert with `ON CONFLICT DO NOTHING`, reload the existing row on conflict, and compare its digest. An identical digest returns the original durable acknowledgement without another reconciliation signal. A different digest appends one `webhook_delivery_digest_conflict` security incident plus audit/outbox evidence, returns `FAILED_PRECONDITION`, leaves the original inbox fact unchanged, and never awakens reconciliation. Only the current token/generation may mark the edge row `FORWARDED` after durable acknowledgement. Lease expiry recovers a crashed forwarder.

`V041__git_intents_and_reconciliation.sql` creates these exact tenant tables and applies the standard policy before any runtime grant:

```sql
SELECT accord_security.enforce_tenant_table(name::regclass)
FROM unnest(ARRAY[
  'public.provider_adapter_manifest',
  'public.provider_connection_intent',
  'public.provider_installation',
  'public.provider_repository_discovery',
  'public.repository_binding_onboarding',
  'public.provider_repository_registration',
  'public.provider_capability_snapshot',
  'public.provider_webhook_signal',
  'public.git_external_intent',
  'public.git_provider_fact',
  'public.git_reconciliation_run',
  'public.git_reconciliation_command',
  'public.repository_branch_release_receipt'
]) AS names(name);
```

All keys and foreign keys start with `tenant_id`; repository-scoped rows additionally reference `(tenant_id, provider_installation_id, repository_id)` in `provider_repository_registration`, and that registration has the composite V010 identity foreign key described above. Provider signal, fact, reconciliation command, and branch-release receipt rows are append-only. `repository_branch_release_receipt` has non-null release ID, Batch/RepositoryWorkSet/installation/repository/effective-manifest identifiers, delivery ref, exact baseline commit/tree SHA, CapabilitySnapshot/development-package/provider-fact digests, `provider_state`, `provider_observed_at`, receipt digest, external-intent ID, and creation time. It contains no published commit, contract blob, path set, or repository file digest because branch release creates only a ref. Checks permit only `provider_state='VERIFIED'`; the unique key `(tenant_id, repository_work_set_id, effective_manifest_digest)` prevents two authoritative current proofs. `receipt_digest` is the domain-separated RFC 8785 JCS SHA-256 of the closed payload containing every binding field and `recorded_at` but omitting itself. Composite foreign keys bind the Provider fact, CapabilitySnapshot, package publication and terminal external intent to the same tenant/installation/repository. `git_external_intent` uses the Foundation fenced intent state machine rather than a second retry model. `git_reconciliation_run` alone is mutable by versioned state transition and cannot be updated by a runtime query that lacks tenant, repository scope and expected version.

- [ ] **Step 5: Dispatch onboarding and reconciliation workflows, not direct state transitions**

The callback consumer deduplicates its opaque receipt and starts or awakens the exact `ProviderOnboardingWorkflow`; the webhook consumer deduplicates its signal and starts or awakens the relevant reconciliation workflow. Neither consumer writes an Identity Binding state directly. Domain changes occur only after the workflow queries current Provider facts and invokes the typed Identity lifecycle port with exact signed evidence.

- [ ] **Step 6: Run security, forwarding crash, and duplicate-delivery tests**

Run:

```bash
./gradlew :apps:webhook-edge:test
./gradlew :apps:control-plane:modules:git-coordination:test --tests '*ProviderRegistry*' --tests '*Capability*' --tests '*ProviderOnboarding*'
./gradlew :tests:api:test --tests '*ProviderOnboarding*'
./gradlew :tests:security-negative:test --tests '*Webhook*' --tests '*ProviderOnboarding*'
./gradlew :tests:integration:test --tests '*DeliveryMigrationsRlsIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; each of the five Provider families completes its declared Cloud/Enterprise connection path; callback state/code, manual credential capability and discovery ID replay all fail outside their exact tenant/session/intent/installation/project scope; no raw credential or code reaches the browser, control-plane database, Temporal history, logs or audit; many Provider installations coexist without identity or credential collision; V010 remains the sole RepositoryBinding authority; a Binding is selectable only after current trust, registration, unexpired capability facts and the CapabilitySnapshot credential epoch exactly matching the active installation epoch; V041 cannot register an inactive, cross-tenant, wrong-endpoint or wrong-installation binding; immutable repository identity cannot bind two tenants; stale capability snapshots cannot authorize strict work; invalid events cause no domain write; identical retries produce one control-plane inbox row and one reconciliation signal; a reused delivery ID with a different digest produces one quarantined security incident and no second signal; 100 competing forwarders expose only one current PostgreSQL lease generation; crashes before or after callback exchange or webhook mTLS acknowledgement converge; and every public tenant table through V041 plus both edge tables is forced behind its exact policy.

- [ ] **Step 7: Commit Webhook Edge forwarding**

```bash
git add apps/webhook-edge apps/control-plane/modules/git-coordination contracts/protobuf/accord/webhook/v1/webhook.proto database/webhook-edge/migrations/V002__forwarding_lease.sql database/webhook-edge/migrations/V003__provider_auth_callback_inbox.sql database/control-plane/migrations/V041__git_intents_and_reconciliation.sql tests/api/src/test/java/com/inforvans/accord/api/ProviderOnboardingApiTest.java tests/integration/src/test/java/com/inforvans/accord/integration/DeliveryMigrationsRlsIT.java tests/security-negative/src/test/java/com/inforvans/accord/security/WebhookSecurityTest.java tests/security-negative/src/test/java/com/inforvans/accord/security/ProviderOnboardingSecurityTest.java
git commit -m "feat(git): onboard providers and authenticate durable signals"
```

### Task 6: Publish Signed Development Packages And Release Multi-Provider Branch Refs

**Files:**
- Create: `apps/control-plane/modules/development-package/src/main/java/com/inforvans/accord/packagepublication/application/DevelopmentPackageService.java`
- Create: `apps/control-plane/modules/development-package/src/main/java/com/inforvans/accord/packagepublication/api/DevelopmentPackageController.java`
- Create: `apps/control-plane/modules/development-package/src/main/java/com/inforvans/accord/packagepublication/storage/ImmutablePackageStore.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/BranchReleaseCoordinator.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/workflow/BranchReleaseWorkflow.java`
- Test: `apps/control-plane/modules/development-package/src/test/java/com/inforvans/accord/packagepublication/DevelopmentPackageServiceTest.java`
- Test: `apps/control-plane/modules/git-coordination/src/test/java/com/inforvans/accord/git/BranchReleaseCoordinatorTest.java`
- Modify: `tests/api/src/test/java/com/inforvans/accord/api/DeliveryBatchApiTest.java`
- Modify: `infra/helm/accord/values.yaml`
- Modify: `infra/helm/accord/templates/workloads.yaml`
- Modify: `infra/helm/accord/templates/serviceaccounts.yaml`
- Modify: `infra/helm/accord/templates/networkpolicies.yaml`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/DevelopmentPackageBoundaryTest.java`

- [ ] **Step 1: Add package integrity, stale head, duplicate ref, partial release, and uncertain-result tests**

```java
@Test
void packageContainsOnlyClosedPlatformFactsAndNeverARepositoryWritePlan() {
    var published = packages.publish(validFrozenRepositoryWorkSet());
    assertThat(published.manifestDigest()).isEqualTo(canonicalExpectedDigest);
    assertThat(published.manifest().fieldNames()).containsExactlyInAnyOrder(
        "schema_version", "tenant_id", "project_id", "batch_id",
        "repository_work_set", "requirement_baselines", "work_items",
        "acceptance_criteria", "attachment_references", "agent_pack_lock", "context_references");
    assertThat(published.serializedText().toLowerCase(Locale.ROOT))
        .doesNotContain("blob", "commit_body", "repository_path", "diff", "source");
}

@Test
void timeoutAfterCreateRefReturnsUnknownAndDoesNotRetryMutation() {
    provider.failCreateRefAfterCommitWithTimeout();
    var result = coordinator.release(validReleaseIntent());
    assertThat(result.outcome()).isEqualTo(BranchReleaseOutcome.OUTCOME_UNKNOWN);
    assertThat(provider.createRefAttempts()).isOne();
}

@Test
void branchReleaseRejectsRemoteHeadTreePackageOrCapabilityMismatch() {
    provider.returnRefFact(verifiedRefFact(validReleaseIntent()).withTreeSha(differentTreeSha));
    var result = coordinator.release(validReleaseIntent());
    assertThat(result.code()).isEqualTo("REMOTE_PROOF_MISMATCH");
    assertThat(result.verifiedRelease()).isEmpty();
}
```

Add the control-plane aggregation test separately; one repository result cannot declare a multi-repository Batch ready:

```java
@Test
void partialMismatchedOrStaleReleaseNeverCreatesCompleteCoverageOrReadyPhase() {
    List.of(
        releaseResultWithout("capability_snapshot_digest"),
        releaseResultWithMismatched("baseline_tree_sha"),
        releaseResultFor(previousEffectiveManifestDigest),
        releaseResult("OUTCOME_UNKNOWN")
    ).forEach(result -> {
        assertThatThrownBy(() -> coordinator.recordVerifiedRelease(batchId, result))
            .isInstanceOf(BranchReleaseProofRejected.class);
        assertThat(batch(batchId).phase()).isEqualTo(BatchPhase.PUBLISHING);
        assertThat(batchView(batchId).repository_release_coverage()).isNotEqualTo("COMPLETE");
    });
}

@Test
void batchBecomesReadyOnlyAfterEveryWorkSetHasOneCurrentCompleteProof() {
    coordinator.recordVerifiedRelease(batchId, exactGithubResult);
    assertThat(batch(batchId).phase()).isEqualTo(BatchPhase.PUBLISHING);
    assertThat(batchView(batchId).repository_release_coverage()).isEqualTo("PARTIAL");

    coordinator.recordVerifiedRelease(batchId, exactGitlabResult);
    assertThat(batch(batchId).phase()).isEqualTo(BatchPhase.READY);
    assertThat(batchView(batchId).repository_release_coverage()).isEqualTo("COMPLETE");
    assertThat(batchView(batchId).repository_releases())
        .containsExactlyInAnyOrder(exactGithubProof, exactGitlabProof);
}
```

- [ ] **Step 2: Run and verify package publication and branch-release services are absent**

Run each command independently:

```bash
./gradlew :apps:control-plane:modules:development-package:test --tests '*DevelopmentPackageServiceTest'
./gradlew :apps:control-plane:modules:git-coordination:test --tests '*BranchReleaseCoordinatorTest'
```

Expected: both Java compilations fail for the missing package service and branch-release coordinator; no delivery mock may satisfy either test.

- [ ] **Step 3: Canonicalize, sign, store, and authorize development packages**

`DevelopmentPackageService` reads one locked RepositoryWorkSet plus its immutable Requirement Baselines, WorkItems, acceptance criteria, attachment object references, Context references, and Agent Pack lock. It validates that all objects share tenant/project/batch/repository scope and current effective manifest, canonicalizes the closed package manifest with RFC 8785 JCS, stores immutable package members in private versioned OSS, and requests a `DEVELOPMENT_PACKAGE_PUBLICATION` DSSE envelope from Signing Service. The envelope binds tenant/project/batch/RepositoryWorkSet/Provider installation/repository/base head/tree, effective manifest, package object version/digest, attachment-version set, Agent Pack digest, schema version, signer key and audit anchor. It contains no Git path/write plan, source, diff, Provider credential, or long-lived object URL.

`DevelopmentPackageController` returns package metadata plus a short-lived, audience-bound download authorization after server-side RBAC and current Assignment checks. `accordctl` verifies DSSE, package digest, schema, RepositoryWorkSet and local Agent Pack digest before exposing the files to Codex. Download expiry affects access, not historical signature validity. Replays return the same object version and DSSE bytes; a changed effective manifest creates a new immutable package and supersedes the prior one without overwriting it.

Implement the exact Task 1 handler instead of relying on an implicit generated route:

```java
@RestController
@RequestMapping(
    "/v1/projects/{projectId}/delivery-batches/{batchId}"
        + "/repository-work-sets/{repositoryWorkSetId}"
)
final class DevelopmentPackageController {
    private final DevelopmentPackageService packages;
    private final Clock clock;

    DevelopmentPackageController(DevelopmentPackageService packages, Clock clock) {
        this.packages = packages;
        this.clock = clock;
    }

    @GetMapping("/development-package")
    ResponseEntity<DevelopmentPackageView> get(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId,
        @PathVariable UUID repositoryWorkSetId
    ) {
        return packages.getAuthorized(
            identity, projectId, batchId, repositoryWorkSetId, clock.instant());
    }
}
```

`getAuthorized` resolves tenant and actor only from the verified identity, locks the exact Batch/RepositoryWorkSet/package/Assignment projection, rejects a stale or superseded package, and returns `Cache-Control: no-store` plus the aggregate's strong numeric `ETag`. It may mint only a short-lived audience-bound download authorization for the fixed immutable object version and digest; the package bytes, DSSE and historical metadata are unchanged. Authorization issuance is audited and cannot be used to alter package or Git state.

- [ ] **Step 4: Release zero-diff refs and eliminate the strict protection window**

`BranchReleaseWorkflow` creates one durable intent per RepositoryWorkSet and calls the branch-control Connector profile with `createRef(deliveryRef, expectedDefaultHead)`. It never creates a commit, blob, tree, repository file, or metadata PR. If the Provider certifies future-ref patterns, attest `delivery/**` protection first, then use expected-default-head/create-ref CAS. Otherwise create a zero-diff ref at the exact default head, immediately install and attest protection, and keep the RepositoryWorkSet invisible/unstartable until the full interval is proven. Existing refs fail unless reconciliation proves the same Batch correlation, exact head and prior successful receipt; force updates and history rewrites are unrepresentable.

The fallback is accepted only when Provider audit/ref facts prove the ref remained at the exact zero-diff head from creation through protection activation. Any transient write, missing interval, capability drift, or inability to prove the interval makes strict mode unsupported for that Provider/version/authentication row. Branch-control credentials cannot merge, read repository contents, change default refs, or call strict Connector profiles. BatchAmendments produce new platform packages and confirmations; they do not mutate Git until developers create code changes through their normal WorkItem flow.

- [ ] **Step 5: Verify each remote ref before declaring repository or batch readiness**

Query ref/commit/tree metadata and Connector operation IDs; match the exact installation/repository/delivery ref, expected baseline commit/tree, capability snapshot, development-package digest, branch policy and effective manifest. Do not fetch repository trees or blob bodies. The selected Provider observation must be the latest applicable fact at the coordinator's locked projection version, have `provider_state=VERIFIED`, and bind observation time and fact digest; a browser clock never decides freshness. A timeout or 5xx after mutation remains `OUTCOME_UNKNOWN` until reconciliation proves effect or no effect.

`BranchReleaseCoordinator` accepts one closed, signature-verified result per RepositoryWorkSet. In one serializable transaction it locks the WorkSet, Batch and external intent, rechecks the effective manifest, package, confirmations and CapabilitySnapshot, verifies the Provider fact belongs to the same tenant/installation/repository/ref with no newer contradiction, inserts one append-only `repository_branch_release_receipt`, stores audit/outbox records and advances that WorkSet by CAS. A contradictory later fact suspends only the affected WorkSet and sets batch coverage/consistency accordingly while preserving the historical receipt. Missing/mismatched fields, nonterminal intent, old manifest/package/capability, unknown outcome, uniqueness conflict, audit failure or CAS loss rolls back the repository transition and exposes no Assignment/start action. Only after every current required WorkSet has one valid receipt does a separate aggregate CAS set coverage `COMPLETE` and Batch `READY`; partial success stays visible as `PARTIAL` and never deletes successful refs.

- [ ] **Step 6: Lock dependencies and run package, branch-release, and network-policy tests**

Render the central chart. Development Package has no Provider credential; branch release reaches Provider only through the branch-control Connector profile:

```bash
./gradlew :apps:control-plane:modules:development-package:test --tests '*DevelopmentPackageServiceTest'
./gradlew :apps:control-plane:modules:git-coordination:test --tests '*BranchReleaseCoordinatorTest'
./gradlew :security-services:provider-connector:test :security-services:credential-broker:test
./gradlew :tests:api:test --tests '*DeliveryOpenApiContractTest' --tests '*DeliveryBatchApiTest'
./gradlew :tests:security-negative:test --tests '*DevelopmentPackageBoundaryTest'
helm template accord infra/helm/accord --namespace accord > build/helm/accord-branch-release.yaml
conftest test build/helm/accord-branch-release.yaml -p infra/policy
```

Expected: all tests pass; incomplete/old/mismatched/uncertain repository results leave the Batch in `PUBLISHING` with `NONE/PARTIAL` coverage, while all exact proofs atomically produce `COMPLETE` and `READY`; package artifacts contain no source or Git write plan; the rendered chart gives Development Package no Provider secret or Provider egress, and branch-control Connector/Credential Broker have distinct service accounts, PDBs, topology spread, mTLS audiences, default-deny policies and no merge/content-read capability.

- [ ] **Step 7: Commit development-package and branch-release control**

```bash
git add apps/control-plane/modules/development-package apps/control-plane/modules/git-coordination infra/helm/accord tests/api/src/test/java/com/inforvans/accord/api/DeliveryBatchApiTest.java tests/security-negative/src/test/java/com/inforvans/accord/security/DevelopmentPackageBoundaryTest.java
git commit -m "feat(git): publish packages and release repository branches"
```

### Task 7: Implement WorkItem Assignment, PR Gates, And Completion Proof

**Files:**
- Create: `database/control-plane/migrations/V042__workitem_execution.sql`
- Create: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/domain/WorkItemModels.java`
- Create: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/application/AssignmentService.java`
- Create: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/application/WorkItemGateService.java`
- Create: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/application/CompletionService.java`
- Create: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/api/WorkItemController.java`
- Test: `apps/control-plane/modules/workitem-execution/src/test/java/com/inforvans/accord/workitem/WorkItemGateTest.java`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/WorkItemApiTest.java`
- Modify: `tests/integration/src/test/java/com/inforvans/accord/integration/DeliveryMigrationsRlsIT.java`
- Test: `tests/security-negative/src/test/java/com/inforvans/accord/security/WorkItemSecurityTest.java`

- [ ] **Step 1: Add identity, RepositoryWorkSet, package, hold, Patch, CI, and completion tests**

```java
@Test
void commitAuthorEmailCannotSubstituteForMappedPullRequestActor() {
    var result = gate.evaluate(facts.withPullRequestActor(unmappedId, owner.email()));
    assertThat(result.code()).isEqualTo("GIT_ACTOR_NOT_MAPPED");
}

@Test
void patchForAnotherRepositoryOrHeadAlwaysFails() {
    var result = gate.evaluate(facts.withContextPatchBinding(otherRepositoryId, otherHead));
    assertThat(result.code()).isEqualTo("CONTEXT_PATCH_BINDING_MISMATCH");
}

@Test
void completionRequestNeverMarksAWorkItemCompleteWithoutProviderProof() throws Exception {
    requestCompletionReview(workItem, staleProviderDigest)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PROVIDER_FACT_STALE"));
    assertThat(workItemView(workItem).phase()).isEqualTo("IN_REVIEW");
    assertThat(completionCount(workItem)).isZero();
}
```

- [ ] **Step 2: Run and verify WorkItem services are missing**

Run: `./gradlew :apps:control-plane:modules:workitem-execution:test --tests '*WorkItemGateTest'`

Expected: compilation fails for `WorkItemGateService`.

- [ ] **Step 3: Implement explicit responsibility and lifecycle**

```java
enum WorkItemPhase { PLANNED, READY, IN_PROGRESS, IN_REVIEW, COMPLETED, CANCELLED }
enum AssignmentPhase { PROPOSED, ACTIVE, ENDED }
record WorkItemScope(
    String goal,
    List<String> nonGoals,
    Set<String> requirementBlockIds,
    Set<String> expectedAreas,
    Set<UUID> dependencies,
    Set<String> testMappings,
    Set<String> completionConditions
) {
    WorkItemScope {
        nonGoals = List.copyOf(nonGoals);
        requirementBlockIds = Set.copyOf(requirementBlockIds);
        expectedAreas = Set.copyOf(expectedAreas);
        dependencies = Set.copyOf(dependencies);
        testMappings = Set.copyOf(testMappings);
        completionConditions = Set.copyOf(completionConditions);
    }
}
```

`V042__workitem_execution.sql` uses exact tenant-scoped tables and applies RLS before runtime grants:

```sql
SELECT accord_security.enforce_tenant_table(name::regclass)
FROM unnest(ARRAY[
  'public.work_item',
  'public.work_item_version',
  'public.work_item_dependency',
  'public.work_item_assignment',
  'public.development_run',
  'public.work_item_gate_evaluation',
  'public.work_item_completion'
]) AS names(name);
```

Every primary, foreign, and unique key begins with `tenant_id`; repository-scoped keys continue with `provider_installation_id`, `repository_id`, and `repository_work_set_id`. Dependencies reference both endpoints through composite tenant keys and may cross repositories only through an explicit dependency row. V042 deliberately separates a mutable current pointer from immutable historical facts. `work_item` stores only the stable authoritative `project_id`, `delivery_batch_id`, RepositoryWorkSet/installation/repository identity, `requirement_id`, `requirement_revision_no`, `requirement_revision_hash`, immutable `contract_digest`, positive CAS `version`, and `current_snapshot_digest`; it does not duplicate phase, scope, owner, assignment, blocker, or other lifecycle state. `work_item_version` stores one append-only snapshot for every accepted aggregate version, including the same tenant/project/batch/repository/Requirement binding, WorkItem ID, positive version, contract digest, lifecycle state, scope digest, owner/assignment binding when present, blocker digest, causation/correlation IDs, actor, and creation time. Current reads join the pointer to its exact snapshot; historical reads select a snapshot directly. The stable RepositoryWorkSet and Requirement binding columns on `work_item` are update-forbidden; changing either creates a replacement WorkItem through an effective Batch amendment rather than rewriting an existing WorkItem.

`snapshot_digest` is SHA-256 of RFC 8785 JCS over the closed object `{schema_version, tenant_id, project_id, delivery_batch_id, repository_work_set_id, provider_installation_id, repository_id, work_item_id, requirement_id, requirement_revision_no, requirement_revision_hash, version, contract_digest, phase, scope_digest, owner_account_id, assignment_id, blocker_digest, causation_id, correlation_id, actor_id, created_at}` with nullable values represented as JSON `null`; the digest field itself is excluded. `work_item_version` has composite FKs to both the stable `work_item` identity and its immutable RepositoryWorkSet and rejects `UPDATE` and `DELETE`. Named database triggers reject a WorkItem whose initial version is not `1`, whose `current_snapshot_digest` is malformed, any update that changes stable binding/contract/repository columns or advances by anything other than exactly `OLD.version + 1` with a changed snapshot digest, and any snapshot insert whose version, stable binding, repository, contract, and snapshot digest do not equal the locked current pointer. Creation inserts pointer version 1 and snapshot version 1 in one transaction. Every later command locks the current pointer, compares both `If-Match` and body `expected_version`, computes the next canonical snapshot and digest, advances `work_item` with `UPDATE ... WHERE version = :expected_version`, and inserts exactly snapshot `expected_version + 1` in the same transaction. A deferred current-snapshot FK makes commit fail unless the new pointer has its matching immutable snapshot; triggers, uniqueness, and property tests reject duplicate, skipped, divergent, cross-repository, or speculative future versions. A failed CAS, snapshot insert, audit, outbox, or idempotency write rolls the whole transition back. Historical snapshots are never cascaded, rewritten, or synthesized from the current pointer.

V042 creates the exact current-snapshot FK and the two Context candidate keys before any Context link or Delivery write is accepted:

```sql
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM public.context_patch_link
     WHERE work_item_id IS NOT NULL
  ) THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = 'pre-V042 context_patch_link contains an unverifiable WorkItem tuple';
  END IF;
END $$;

ALTER TABLE public.context_patch_link
  ADD COLUMN provider_installation_id varchar(255),
  ADD COLUMN repository_id uuid,
  ADD COLUMN repository_work_set_id uuid;

ALTER TABLE public.context_patch_link
  DROP CONSTRAINT ck_context_patch_link_work_item_tuple_complete,
  ADD CONSTRAINT ck_context_patch_link_work_item_tuple_complete CHECK (
    (delivery_batch_id IS NULL
      AND provider_installation_id IS NULL
      AND repository_id IS NULL
      AND repository_work_set_id IS NULL
      AND work_item_id IS NULL
      AND work_item_version IS NULL
      AND work_item_contract_digest IS NULL)
    OR
    (delivery_batch_id IS NOT NULL
      AND provider_installation_id IS NOT NULL
      AND repository_id IS NOT NULL
      AND repository_work_set_id IS NOT NULL
      AND work_item_id IS NOT NULL
      AND work_item_version IS NOT NULL
      AND work_item_contract_digest IS NOT NULL)
  );

ALTER TABLE public.work_item
  ADD CONSTRAINT uq_work_item_stable_binding
  UNIQUE (tenant_id, project_id, delivery_batch_id,
          provider_installation_id, repository_id, repository_work_set_id,
          work_item_id, contract_digest,
          requirement_id, requirement_revision_no, requirement_revision_hash);

ALTER TABLE public.work_item_version
  ADD CONSTRAINT uq_work_item_context_ref
  UNIQUE (tenant_id, project_id, delivery_batch_id,
          provider_installation_id, repository_id, repository_work_set_id,
          work_item_id, version, contract_digest),
  ADD CONSTRAINT uq_work_item_context_revision_ref
  UNIQUE (tenant_id, project_id, delivery_batch_id,
          provider_installation_id, repository_id, repository_work_set_id,
          work_item_id, version, contract_digest,
          requirement_id, requirement_revision_no, requirement_revision_hash),
  ADD CONSTRAINT uq_work_item_current_snapshot_ref
  UNIQUE (tenant_id, project_id, delivery_batch_id,
          provider_installation_id, repository_id, repository_work_set_id,
          work_item_id, version, contract_digest,
          requirement_id, requirement_revision_no, requirement_revision_hash,
          snapshot_digest);

ALTER TABLE public.work_item_version
  ADD CONSTRAINT fk_work_item_version_stable_binding
  FOREIGN KEY (tenant_id, project_id, delivery_batch_id,
               provider_installation_id, repository_id, repository_work_set_id,
               work_item_id, contract_digest,
               requirement_id, requirement_revision_no, requirement_revision_hash)
  REFERENCES public.work_item
              (tenant_id, project_id, delivery_batch_id,
               provider_installation_id, repository_id, repository_work_set_id,
               work_item_id, contract_digest,
               requirement_id, requirement_revision_no, requirement_revision_hash);

ALTER TABLE public.work_item
  ADD CONSTRAINT fk_work_item_current_version_snapshot
  FOREIGN KEY (tenant_id, project_id, delivery_batch_id,
               provider_installation_id, repository_id, repository_work_set_id,
               work_item_id, version, contract_digest,
               requirement_id, requirement_revision_no, requirement_revision_hash,
               current_snapshot_digest)
  REFERENCES public.work_item_version
              (tenant_id, project_id, delivery_batch_id,
               provider_installation_id, repository_id, repository_work_set_id,
               work_item_id, version, contract_digest,
               requirement_id, requirement_revision_no, requirement_revision_hash,
               snapshot_digest)
  DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.context_patch_link
  ADD CONSTRAINT fk_context_patch_link_work_item
  FOREIGN KEY (tenant_id, project_id, delivery_batch_id,
               provider_installation_id, repository_id, repository_work_set_id,
               work_item_id, work_item_version, work_item_contract_digest)
  REFERENCES public.work_item_version
              (tenant_id, project_id, delivery_batch_id,
               provider_installation_id, repository_id, repository_work_set_id,
               work_item_id, version, contract_digest),
  ADD CONSTRAINT fk_context_patch_link_work_item_revision
  FOREIGN KEY (tenant_id, project_id, delivery_batch_id,
               provider_installation_id, repository_id, repository_work_set_id,
               work_item_id, work_item_version, work_item_contract_digest,
               requirement_id, revision_no, revision_hash)
  REFERENCES public.work_item_version
              (tenant_id, project_id, delivery_batch_id,
               provider_installation_id, repository_id, repository_work_set_id,
               work_item_id, version, contract_digest,
               requirement_id, requirement_revision_no, requirement_revision_hash);
```

Both WorkItem tables are created before these constraints. V030 intentionally had no repository tuple because no WorkItem or RepositoryWorkSet existed yet; its application port rejected every non-null WorkItem link. V042 therefore fails migration if such an unverifiable legacy tuple is present, adds the three repository-scope columns without inventing a binding, and atomically replaces the four-column completeness check with the seven-column check above. It then adds the stable aggregate key and all three snapshot candidate keys, the snapshot-to-stable-aggregate FK, the deferred full current-version/digest FK, and finally the two Context FKs. On initial creation the transaction inserts `work_item` version 1 first and its matching snapshot second; the deferred reverse FK is checked only at commit. Every WorkItem key and Context handoff now carries the exact Provider installation, immutable repository and RepositoryWorkSet, so no globally unique UUID assumption can authorize a cross-repository link. The full current-snapshot FK also includes the Requirement Revision and snapshot digest columns, so the current pointer cannot resolve to another Revision or to different lifecycle bytes. The first Context FK makes every non-null WorkItem reference resolve to the immutable version that actually existed and rejects an absent snapshot or a wrong tenant/project/batch/installation/repository/WorkSet/version/contract digest. The second becomes effective whenever the optional Requirement Revision columns are present and proves that both refs describe the same historical WorkItem-to-Revision binding. V042 adds both Context constraints immediately validated, never `NOT VALID`, before runtime grants or Delivery writes; both nullable tuples remain all-null or all-present, so a caller cannot evade either FK with a partial tuple.

WorkItem snapshots, gate evaluations, and completions are append-only, assignments use explicit ended versions, and no cascade removes evidence. Extend `DeliveryMigrationsRlsIT` with tenant-collision and cross-tenant dependency/assignment rejection tests for all seven V042 tables; the same pre-Flyway role bootstrap migrates an empty database through V042. It queries `pg_constraint` to prove exact source/target column order for the stable-binding FK, deferred full current-snapshot FK, expanded seven-column completeness check, and both named Context-link FKs; asserts all are validated, the current FK has `condeferrable=true` and `condeferred=true`, both Context FKs target `work_item_version`, no Context FK targets mutable `work_item`, and no historical-evidence FK has a cascade delete action. It queries `pg_trigger` to prove the initial/next-version, stable-binding, snapshot-pointer match, and snapshot append-only guards are enabled. It inserts versions 1 and 2, links a Patch to version 1 with the full repository tuple, advances the current pointer to version 2, and proves the version-1 link remains unchanged and valid. It independently proves rejection for a missing snapshot, wrong tenant, wrong project, wrong batch, wrong Provider installation, wrong repository, wrong RepositoryWorkSet, wrong WorkItem version, wrong contract digest, every partial repository/WorkItem tuple, wrong/malformed `current_snapshot_digest`, a pointer paired with a snapshot from another Requirement Revision, a Context WorkItem paired with a different Requirement Revision, mutation/deletion of a snapshot, a current version without a snapshot, orphan/speculative/duplicate/gapped versions, and a rolled-back CAS whose snapshot/audit/outbox/idempotency writes must not survive. A 32-worker race from the same expected version produces exactly one next pointer/snapshot and no losing snapshot. The test also targets V030 in a separate empty database and proves that milestone still migrates while both `to_regclass('public.work_item')` and `to_regclass('public.work_item_version')` are null and no `context_patch_link` FK references either future table; a separate fixture with a fabricated pre-V042 WorkItem tuple proves V042 fails closed instead of guessing its repository binding.

One final owner accepts an Assignment before a DevelopmentRun. Contributor/reviewer identities are separate. Owner replacement ends the old Assignment/Run and creates a new binding from an allowed delivery baseline without changing the Requirement Revision.

For every RepositoryWorkSet the protected topology is `default -> delivery/<batch-id>/develop <- customer work branches`. Work branches may follow the customer's naming convention, but every PR declares Provider installation, immutable repository, Batch, RepositoryWorkSet, exact Requirement Revision, WorkItem, Assignment, source head, and that WorkSet's sole delivery ref. Direct/force push to delivery is forbidden; WorkItem PRs never target default; only that repository's final accepted Candidate targets its default. Once a Candidate is frozen, any delivery-tree write creates a new Candidate rather than modifying the frozen one.

On the customer workstation, `accordctl` downloads the signed Development Package and the pinned `implement-requirement` Skill verifies its DSSE, Agent Pack lock digest, effective Batch Manifest, Requirement Baseline hash, RepositoryWorkSet, Assignment, Provider repository and branch baseline; it then reads the formal business/development projections and WorkItem, plans and changes customer code/tests locally, runs customer checks, and uploads a `prepare-context-patch` candidate or requests customer-CI no-change analysis. The package is not read from a Git commit. Blocking ambiguity emits a structured DevelopmentAnnotation/Proposal and places the WorkItem on hold; the platform never asks the developer to redo a full-repository analysis before discussion.

- [ ] **Step 4: Evaluate every required PR gate**

Verify tenant/Provider installation/repository/RepositoryWorkSet/batch/revision/WorkItem/Assignment/source/target/Git identity, signed package digest, current batch baseline, exact current target head or merge-group result, active receipts/commitment/roles/no holds, platform-stored customer-CI-validated Patch or no-change proof, tests/static/migration/acceptance checks, CODEOWNERS/range review, support matrix, risk/security/data/rollback checks. Customer CI supplies normalized path/diff digests and evidence locators; the platform does not fetch source, diff, or repository document bodies.

- [ ] **Step 5: Create immutable completion only after actual merge facts**

`WorkItemCompletion` binds Provider installation, immutable repository, RepositoryWorkSet, actual merge SHA/tree, normalized diff digest, Patch/no-change receipt, tests, build provenance, owner binding, and Provider fact digest. A user completion click only creates a candidate ActionRequest. Derive repository completion from all required valid WorkItems plus cancellation proofs; derive requirement and Batch completeness only through CompletionSet, never from the first successful repository.

Create the HTTP adapter with no generic aggregate patch endpoint:

```java
record CreateAssignmentRequest(
    long expected_version, UUID work_item_id, UUID owner_account_id, String baseline_head_sha
) {}
record AcceptAssignmentRequest(
    long expected_version, UUID assignment_id, String baseline_head_sha
) {}
record CompletionReviewRequest(long expected_version, String provider_fact_digest) {}

@RestController
@RequestMapping("/v1/projects/{projectId}/delivery-batches/{batchId}/work-items")
final class WorkItemController {
    private final AssignmentService assignments;
    private final CompletionService completions;

    WorkItemController(AssignmentService assignments, CompletionService completions) {
        this.assignments = assignments;
        this.completions = completions;
    }

    @GetMapping
    WorkItemPage list(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId
    ) {
        return assignments.listAuthorized(identity, projectId, batchId);
    }

    @PostMapping("/{workItemId}/assignments")
    ResponseEntity<WorkItemPage> assign(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId,
        @PathVariable UUID workItemId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody CreateAssignmentRequest request
    ) {
        return assignments.proposeAuthorized(
            identity, projectId, batchId, workItemId, idempotencyKey, ifMatch, request);
    }

    @PostMapping("/{workItemId}/assignments/{assignmentId}/acceptance")
    ResponseEntity<WorkItemPage> accept(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId,
        @PathVariable UUID workItemId,
        @PathVariable UUID assignmentId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody AcceptAssignmentRequest request
    ) {
        return assignments.acceptAuthorized(
            identity, projectId, batchId, workItemId, assignmentId,
            idempotencyKey, ifMatch, request);
    }

    @PostMapping("/{workItemId}/completion-review-requests")
    ResponseEntity<ActionRequestEnvelope> requestCompletionReview(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId,
        @PathVariable UUID workItemId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody CompletionReviewRequest request
    ) {
        return completions.requestReviewAuthorized(
            identity, projectId, batchId, workItemId, idempotencyKey, ifMatch, request);
    }
}
```

Each service entry uses the Identity plan's same-transaction authorization/idempotency/CAS/audit/outbox sequence. `CompletionService` calls only the Requirement-owned `action::action-request-api` NamedInterface: it invokes `ActionRequestPort.openOrReuse(tx, command)` with the same tenant `DSLContext` used for the WorkItem command and returns its formal `ActionRequestEnvelope`; it does not invent a delivery-local ActionRequest DTO or let the port open a second transaction. The action command binds the exact WorkItem target version/hash, owner rule, provider-fact causation digest through the natural action key, and the closed completion-review decisions. Assignment creation requires the development-side scope manager; acceptance requires the named natural person; completion review requires the active owner. `work_item_id` and `assignment_id` in the body must equal the route values. A forged tenant header is ignored, Git commit email never establishes identity, and list/detail queries conceal unauthorized existence. The WorkItem/API tests force a rollback after ActionRequest insertion and prove neither WorkItem transition nor ActionRequest/audit/outbox survives; exact replay yields one request, while changed target evidence under the same key yields `ACTION_KEY_CONFLICT`.

- [ ] **Step 6: Run gate, stale-target, and duplicate-completion tests**

Run:

```bash
./gradlew :apps:control-plane:modules:workitem-execution:test --tests '*WorkItemGateTest'
./gradlew :tests:api:test --tests '*WorkItemApiTest'
./gradlew :tests:security-negative:test --tests '*WorkItemSecurityTest'
./gradlew :tests:integration:test --tests '*DeliveryMigrationsRlsIT'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: all tests pass; stale green checks fail after target head advances, one real merge creates one Completion, API clicks alone cannot do so, tenant collisions remain isolated, and every tenant table through V042 has the exact forced policy.

- [ ] **Step 7: Commit WorkItem control**

```bash
git add database/control-plane/migrations/V042__workitem_execution.sql apps/control-plane/modules/workitem-execution tests/api/src/test/java/com/inforvans/accord/api/WorkItemApiTest.java tests/integration/src/test/java/com/inforvans/accord/integration/DeliveryMigrationsRlsIT.java tests/security-negative/src/test/java/com/inforvans/accord/security/WorkItemSecurityTest.java
git commit -m "feat(workitems): gate customer-owned development execution"
```

### Task 8: Implement Standard-Mode Checks, Detection, And Recovery

**Files:**
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/StandardModeGuard.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/ReconciliationService.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/workflow/ReconciliationWorkflow.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/api/GitEvidenceController.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/api/ReconciliationController.java`
- Test: `apps/control-plane/modules/git-coordination/src/test/java/com/inforvans/accord/git/StandardBypassTest.java`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/ReconciliationApiTest.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/StandardBypassRecoveryTest.java`
- Create: `tests/performance/k6/reconciliation-lag.js`
- Create: `docs/runbooks/standard-bypass-recovery.md`

- [ ] **Step 1: Add bypass, missing webhook, force-push, unknown result, and convergence tests**

```java
@Test
void administratorBypassSuspendsBatchAndStalesContext() {
    reconciler.observe(providerFacts(nonAccordMergeIntoProtectedRef));
    assertThat(batch.operationalState()).isEqualTo(SUSPENDED);
    assertThat(batch.consistencyState()).isEqualTo(RECONCILIATION_REQUIRED);
    assertThat(context.health()).isEqualTo(STALE);
}

@Test
void recoveryApiRejectsAGenericActiveStateCommand() throws Exception {
    postRecoveryCommand("SET_ACTIVE")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REQUEST_SCHEMA_INVALID"));
    assertThat(repositoryView().consistencyState()).isEqualTo("RECONCILIATION_REQUIRED");
}
```

- [ ] **Step 2: Run and verify reconciliation is absent**

Run: `./gradlew :apps:control-plane:modules:git-coordination:test --tests '*StandardBypassTest'`

Expected: compilation fails for `ReconciliationService`.

- [ ] **Step 3: Rebuild current truth from Provider facts**

Persist cursors per Provider installation/repository and periodically query protected refs, commit/tree hashes, permitted ancestry metadata, ChangeRequest/check/merge actor, branch protection, Connector operation receipts, and customer CI attestation index. Compare against expected branch-release/merge intents, RepositoryWorkSet, Context watermark, batch state, candidate tree, CompletionSet entry, and artifact provenance. Never infer Git order from Webhook delivery order or combine pagination pages from different Provider watermarks.

Expose only normalized, digest-bound metadata facts:

```java
@RestController
@RequestMapping("/v1/projects/{projectId}/repositories/{repositoryId}")
final class GitEvidenceController {
    private final ProviderFactService facts;

    GitEvidenceController(ProviderFactService facts) {
        this.facts = facts;
    }

    @GetMapping("/git-evidence")
    GitEvidenceView get(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID repositoryId
    ) {
        return facts.viewAuthorized(identity, projectId, repositoryId);
    }
}
```

`GitEvidenceView` is constructed from V041 normalized rows and contains provider installation/repository IDs, refs, commit/tree SHA values, ancestry verdicts, PR/check/merge-actor/protection facts, customer-CI attestation digests, observation time, Provider request ID, fact digest, and staleness. It contains no blob/content/diff/patch body, commit message, arbitrary provider JSON, pre-signed provider URL, or source locator.

- [ ] **Step 4: Fail closed and route recovery**

On bypass/gap/mismatch, atomically suspend the affected RepositoryWorkSet, mark Batch coverage/consistency reconciliation-required, mark that repository Context stale/rebuild-required, invalidate pending package/branch-release/candidate/completion actions for the affected scope, create one reconciliation ActionRequest, and preserve exact external facts. Other unrelated installations continue. Recovery must prove the new repository state, rebuild/advance Context, rebuild Candidate and repeat affected acceptance before CompletionSet can converge; no manual state toggle exists.

Create a typed recovery adapter whose only state-changing verbs produce or validate evidence:

```java
enum RecoveryCommand {
    REFRESH_PROVIDER_FACTS,
    REBUILD_PROJECT_CONTEXT,
    INVALIDATE_CANDIDATE,
    REQUEST_REACCEPTANCE,
    REVERIFY_BRANCH_POLICY,
    PROVE_CONVERGENCE
}
record StartReconciliationRequest(long expected_version, String reason) {}
record RecoveryCommandRequest(
    long expected_version, RecoveryCommand command, Set<String> evidence_digests
) {
    RecoveryCommandRequest { evidence_digests = Set.copyOf(evidence_digests); }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/repositories/{repositoryId}")
final class ReconciliationController {
    private final ReconciliationService reconciliation;

    ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/reconciliation")
    ReconciliationView get(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID repositoryId
    ) {
        return reconciliation.getAuthorized(identity, projectId, repositoryId);
    }

    @PostMapping("/reconciliations")
    ResponseEntity<ReconciliationView> start(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID repositoryId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody StartReconciliationRequest request
    ) {
        return reconciliation.startAuthorized(
            identity, projectId, repositoryId, idempotencyKey, ifMatch, request);
    }

    @PostMapping("/reconciliations/{reconciliationId}/commands")
    ResponseEntity<ReconciliationView> command(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID repositoryId,
        @PathVariable UUID reconciliationId,
        @RequestHeader("X-Accord-Fresh-Auth") UUID freshAuthSessionId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody RecoveryCommandRequest request
    ) {
        return reconciliation.commandAuthorized(
            identity, projectId, repositoryId, reconciliationId, freshAuthSessionId,
            idempotencyKey, ifMatch, request);
    }
}
```

`PROVE_CONVERGENCE` recomputes current Provider facts, expected intents, Context watermark, Candidate/Acceptance validity, branch assurance, and all blocking actions in one versioned transaction after external fact collection. Only that internal invariant evaluator may emit the `reconciliation.converged` event and restore operational state. The API cannot submit desired phase/operational/consistency/assurance values. Every recovery command requires project administration, a current reconciliation ActionRequest, and action-bound fresh authentication. `commandAuthorized` uses one `AuthorizationService.authorizeAndExecute` transaction to consume `X-Accord-Fresh-Auth`, claim idempotency, verify CAS, append evidence/audit/outbox, and advance state; a rollback restores the fresh-auth proof and all writes. Strict assurance restoration additionally requires the high-risk separation rule.

- [ ] **Step 5: Prove the 15-minute detection SLO**

Run: `./gradlew :tests:fault-injection:test --tests '*StandardBypass*' && ./gradlew :tests:api:test --tests '*ReconciliationApiTest' && k6 run tests/performance/k6/reconciliation-lag.js`

Expected: all scenarios detect protected-ref gaps/bypasses within 15 minutes at certified repository cardinality and never produce an incorrect completed state.

- [ ] **Step 6: Commit standard assurance**

```bash
git add apps/control-plane/modules/git-coordination tests/api/src/test/java/com/inforvans/accord/api/ReconciliationApiTest.java tests/fault-injection/src/test/java/com/inforvans/accord/fault/StandardBypassRecoveryTest.java tests/performance/k6/reconciliation-lag.js docs/runbooks/standard-bypass-recovery.md
git commit -m "feat(git): detect and recover standard-mode bypass"
```

### Task 9: Implement Strict Merge Controller With Verified Subjects And One-Time Reservation

**Files:**
- Modify: `contracts/protobuf/accord/signing/v1/signing.proto`
- Modify: `contracts/dsse-payloads/signing-claims-v2.schema.json`
- Modify: `contracts/golden-fixtures/signing/claims-v2.input.json`
- Modify: `contracts/golden-fixtures/signing/claims-v2.canonical.json`
- Modify: `contracts/golden-fixtures/signing/claims-v2.sha256`
- Modify: `tests/contracts/signing-contract.test.mjs`
- Regenerate: `contracts/gen/java/accord/signing/v1/`
- Test: `tests/contract/src/test/java/com/inforvans/accord/contracts/SigningContractCompatibilityTest.java`
- Modify: `security-services/merge-controller/build.gradle`
- Modify: `security-services/signing-service/build.gradle`
- Create: `database/signing-service/migrations/V003__merge_subject_reservation.sql`
- Modify: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/nonce/PostgresAuthorizationReservationStore.java`
- Modify: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/nonce/PostgresAuthorizationReservationStoreTest.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/nonce/V003MigrationTest.java`
- Modify: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/signing/SigningService.java`
- Modify: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/api/SigningGrpcService.java`
- Modify: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/transport/MtlsCallerPolicy.java`
- Modify: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/transport/MtlsCallerPolicyTest.java`
- Create: `security-services/merge-controller/src/main/java/com/inforvans/accord/merge/AuthorizationVerifier.java`
- Create: `security-services/merge-controller/src/main/java/com/inforvans/accord/merge/ProviderCompareAndSwap.java`
- Create: `security-services/merge-controller/src/main/java/com/inforvans/accord/merge/MergeService.java`
- Create: `security-services/merge-controller/src/main/java/com/inforvans/accord/merge/MergeControllerApplication.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/StrictMergeCoordinator.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/CompletionSetCoordinator.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/api/StrictDeliveryController.java`
- Modify: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/api/DeliveryBatchController.java`
- Create: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/application/StrictWorkItemCoordinator.java`
- Modify: `apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/api/WorkItemController.java`
- Modify: `infra/helm/accord/values.yaml`
- Modify: `infra/helm/accord/templates/workloads.yaml`
- Modify: `infra/helm/accord/templates/serviceaccounts.yaml`
- Modify: `infra/helm/accord/templates/networkpolicies.yaml`
- Test: `security-services/merge-controller/src/test/java/com/inforvans/accord/merge/MergeServiceTest.java`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/StrictDeliveryApiTest.java`
- Test: `tests/state-machine/src/test/java/com/inforvans/accord/state/CompletionSetProperties.java`
- Create: `docs/runbooks/strict-assurance-recovery.md`

- [ ] **Step 1: Add wrong repo/ref/head/tree/subject type-ID-digest/policy/check/nonce/expiry and replay tests**

```java
@Test
void mergeAuthorizationCanBeConsumedOnlyOnce() {
    var authorization = validAuthorization();
    assertThat(controller.merge(authorization).status()).isEqualTo(MERGED);
    assertThat(controller.merge(authorization).status()).isEqualTo(AUTHORIZATION_CONSUMED);
    assertThat(provider.mergeCalls()).isOne();
}

@Test
void headMovesBetweenCheckAndMerge() {
    provider.advanceTargetAfterProtectionRead();
    assertThat(controller.merge(validAuthorization()).status())
        .isEqualTo(EXPECTED_HEAD_MISMATCH);
    assertThat(provider.mergeCalls()).isZero();
}
```

`StrictDeliveryApiTest` posts the exact accepted Candidate/tree with a stale target head and expects `409 TARGET_HEAD_STALE`; it then retries the same idempotency key with changed input and expects `409 IDEMPOTENCY_KEY_REUSED`. Table-driven cases exercise all three strict subject types: a gated WorkItem PR succeeds before any final Candidate exists; the final repository Candidate requires current Acceptance/artifact/Context evidence; EmergencyChange uses only its independent lane. Branch release is proven separately and cannot submit a merge subject. Type/ID/digest/Provider-installation/capability substitution fails. The response never exposes the raw authorization nonce, Provider credential, or DSSE signing material.

`V003MigrationTest.java` has two Testcontainers-backed release-path suites. The production-V1 greenfield suite stops at V002, asserts `authorization_nonce` is empty, migrates through V003, and proves epoch 2 remains fenced until a verified cutover receipt activates it. The compatibility suite stops at exactly V002, seeds one unconsumed and one consumed legacy nonce through the V002 API, deploys the fail-closed legacy handler and revokes the old workload epoch, records the signed cutover receipt, and then migrates. It proves the migration has no duplicate-column failure; preserves the original `consumed_at` and `consumed_by_workload_id` columns and values; maps both rows only to `LEGACY_OUTCOME_UNKNOWN`; never makes either row `CONSUMED_MERGED` or reusable `ISSUED`; makes the old INSERT/UPDATE SQL fail by privilege before state change; and permits only proof-bearing Provider reconciliation to terminalize either row. A separate fresh-V003 case covers every new state transition, check, index, grant, trigger, and closed binding field.

- [ ] **Step 2: Run and verify Controller is absent**

Run: `./gradlew :security-services:merge-controller:test`

Expected: build fails for missing merge service.

Keep `security-services/merge-controller` and `security-services/signing-service` as separate Spring Boot Gradle projects with independent images, SPIFFE identities, database roles, ServiceAccounts, and default-deny NetworkPolicies. Both pin Java 21 and the shared JUnit 5/AssertJ/Testcontainers platform. Merge Controller depends only on generated signing, evidence, and Provider Connector protobuf types; it cannot depend on concrete adapters, control-plane modules, Development Package, Branch Release, Credential Broker, or Signing Service implementation packages. It owns no Provider credential and can reach only the strict-merge Connector workload audience. Signing Service exposes only protobuf/gRPC handlers over mTLS and never imports delivery domain code. ArchUnit, Gradle, Helm and NetworkPolicy tests enforce these boundaries before either `bootJar` is accepted.

- [ ] **Step 3: Verify independent facts in one short authorization window**

Verify DSSE purpose/key, immutable tenant/repository, exact `VerifiedMergeSubject`, target ref/head, source head, expected result tree, effective manifest, Context evidence, checks, branch policy digest, merge method, nonce, expiry, and key validity. Git Task 1's signing-contract synchronization is made concrete here with append-only protobuf evolution:

```protobuf
enum StrictMergeMethod {
  STRICT_MERGE_METHOD_UNSPECIFIED = 0;
  STRICT_MERGE_METHOD_MERGE = 1;
  STRICT_MERGE_METHOD_SQUASH = 2;
  STRICT_MERGE_METHOD_REBASE = 3;
}

// Fields 1-10 remain exactly as emitted by Identity; never reuse or renumber them.
message ExactAuthorizationBinding {
  string target_ref = 1;
  string expected_target_head_sha = 2;
  string source_head_sha = 3;
  string verified_result_tree_sha = 4;
  string normalized_diff_digest = 5;
  string required_checks_digest = 6;
  string ci_attestation_digest = 7;
  VerifiedMergeSubject subject = 8;
  string nonce = 9;
  google.protobuf.Timestamp expires_at = 10;
  uint32 binding_schema_version = 11;
  string branch_policy_digest = 12;
  string context_evidence_digest = 13;
  string effective_manifest_digest = 14;
  StrictMergeMethod merge_method = 15;
  string provider_installation_id = 16;
  string capability_snapshot_digest = 17;
}

message ExpectedAuthorizationBinding {
  string expected_ref = 1;
  string expected_target_head_sha = 2;
  string expected_source_head_sha = 3;
  string expected_result_tree_sha = 4;
  string expected_normalized_diff_digest = 5;
  string expected_required_checks_digest = 6;
  string expected_ci_attestation_digest = 7;
  VerifiedMergeSubject expected_subject = 8;
  uint32 expected_binding_schema_version = 9;
  string expected_branch_policy_digest = 10;
  string expected_context_evidence_digest = 11;
  string expected_effective_manifest_digest = 12;
  StrictMergeMethod expected_merge_method = 13;
  string expected_provider_installation_id = 14;
  string expected_capability_snapshot_digest = 15;
}
message ReserveAuthorizationTokenRequest {
  SigningScope scope = 1;
  SigningPurpose purpose = 2;
  bytes envelope_json = 3;
  ExpectedAuthorizationBinding expected_binding = 4;
  string reservation_id = 5;
  string provider_request_id = 6;
}
message ReserveAuthorizationTokenResponse {
  string nonce = 1;
  string reservation_id = 2;
  uint64 reservation_generation = 3;
  google.protobuf.Timestamp reserved_at = 4;
}
enum AuthorizationProviderOutcome {
  AUTHORIZATION_PROVIDER_OUTCOME_UNSPECIFIED = 0;
  AUTHORIZATION_PROVIDER_OUTCOME_MERGED = 1;
  AUTHORIZATION_PROVIDER_OUTCOME_NO_EFFECT = 2;
}
enum AuthorizationTokenState {
  AUTHORIZATION_TOKEN_STATE_UNSPECIFIED = 0;
  AUTHORIZATION_TOKEN_STATE_RESERVED = 1;
  AUTHORIZATION_TOKEN_STATE_CONSUMED_MERGED = 2;
  AUTHORIZATION_TOKEN_STATE_CANCELLED_PROVEN_NO_EFFECT = 3;
}
message FinalizeAuthorizationTokenRequest {
  SigningScope scope = 1;
  SigningPurpose purpose = 2;
  bytes envelope_json = 3;
  ExpectedAuthorizationBinding expected_binding = 4;
  string reservation_id = 5;
  uint64 reservation_generation = 6;
  string expected_reserved_by_workload_id = 7;
  string provider_request_id = 8;
  AuthorizationProviderOutcome provider_outcome = 9;
  string outcome_digest = 10;
  google.protobuf.Timestamp provider_observed_at = 11;
}
message FinalizeAuthorizationTokenResponse {
  AuthorizationTokenState state = 1;
  google.protobuf.Timestamp finalized_at = 2;
}
message CancelUnreservedAuthorizationTokenRequest {
  SigningScope scope = 1;
  SigningPurpose purpose = 2;
  bytes envelope_json = 3;
  ExpectedAuthorizationBinding expected_binding = 4;
  string no_effect_proof_digest = 5;
  google.protobuf.Timestamp provider_observed_at = 6;
}
message CancelUnreservedAuthorizationTokenResponse {
  AuthorizationTokenState state = 1;
  google.protobuf.Timestamp finalized_at = 2;
}

service SigningService {
  rpc SignDsse(SignDsseRequest) returns (SignDsseResponse);
  rpc VerifyDsse(VerifyDsseRequest) returns (VerifyDsseResponse);
  rpc ConsumeAuthorizationToken(ConsumeAuthorizationTokenRequest) returns (ConsumeAuthorizationTokenResponse) {
    option deprecated = true;
  }
  rpc IssueBreakGlassAuthorization(IssueBreakGlassAuthorizationRequest) returns (IssueBreakGlassAuthorizationResponse);
  rpc ReserveAuthorizationToken(ReserveAuthorizationTokenRequest) returns (ReserveAuthorizationTokenResponse);
  rpc FinalizeAuthorizationToken(FinalizeAuthorizationTokenRequest) returns (FinalizeAuthorizationTokenResponse);
  rpc CancelUnreservedAuthorizationToken(CancelUnreservedAuthorizationTokenRequest) returns (CancelUnreservedAuthorizationTokenResponse);
}
```

Keep the existing `ConsumeAuthorizationToken` request, response, and RPC field numbers for wire compatibility and mark only the RPC deprecated; never delete them. Append Reserve, Finalize, and Cancel with new service method names and field numbers. `signing-claims-v2.schema.json` is a closed strict-merge-only schema with `schema_version="2.0.0"`, `binding_schema_version=2`, domain `accord.strict-merge.v2`, and all six appended policy/Context/manifest/method/installation/capability fields required. The signer dispatches by purpose/domain/schema version: it continues to verify historical v1 bytes against the untouched v1 schema and pinned v1 digest, but it can issue strict claims only as v2 and Reserve/Cancel reject every v1 envelope. `SigningContractCompatibilityTest` parses stored v1 and v2 envelopes, proves the v1 canonical bytes/hash never change, proves all original protobuf field numbers remain, and fails any v2 field omission, Provider-installation/capability substitution, unknown merge enum, schema/domain downgrade, or cross-version replay. No V003 code path may accept one of these fields only in a Delivery-local DTO.

Load the closed subject payload by digest through the mTLS evidence boundary, validate its type-specific schema, and refetch capability snapshot, authentication class, protection, target head, ChangeRequest/check/source/result-tree facts and current holds through the strict Connector profile. For `WORK_ITEM_PR`, require current RepositoryWorkSet/Assignment/package/CI/Patch facts; for the accepted Candidate, require the repository-specific Acceptance/artifact/Context evidence and incomplete CompletionSet entry; for EmergencyChange, require dual authorization and emergency CI evidence. The control plane's assertion is necessary but never sufficient.

Create the control-plane command adapter:

```java
record IssueStrictMergeAuthorizationRequest(
    long expected_version,
    UUID candidate_id,
    String candidate_payload_digest,
    String expected_target_head,
    String candidate_tree,
    Set<String> acceptance_receipt_digests
) {
    IssueStrictMergeAuthorizationRequest {
        acceptance_receipt_digests = Set.copyOf(acceptance_receipt_digests);
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/delivery-batches/{batchId}")
final class StrictDeliveryController {
    private final StrictMergeCoordinator strict;

    StrictDeliveryController(StrictMergeCoordinator strict) {
        this.strict = strict;
    }

    @PostMapping("/strict-merge-authorizations")
    ResponseEntity<StrictMergeAuthorizationView> issue(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID batchId,
        @RequestHeader("X-Accord-Fresh-Auth") UUID freshAuthSessionId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @Valid @RequestBody IssueStrictMergeAuthorizationRequest request
    ) {
        return strict.issueAuthorized(
            identity, projectId, batchId, freshAuthSessionId, idempotencyKey, ifMatch, request);
    }
}
```

`issueAuthorized` requires `VerifiedRequestIdentity`, current Development Principal authority, the configured strict separation proof, an active exact Candidate and Acceptance chain, accepted artifact profile/digest/provenance, converged Context, current branch-policy/check digests, and no holds. In one `AuthorizationService.authorizeAndExecute` transaction it validates and consumes the action-bound `X-Accord-Fresh-Auth` session, claims idempotency, CAS-advances the Batch, derives the immutable `ACCEPTED_DELIVERY_CANDIDATE` subject, and creates the purpose-bound authorization intent, audit/outbox evidence, and public response. A failure rolls back fresh-auth consumption with every domain write. The public view contains subject/binding digests, state, and expiry only; the one-time nonce is delivered solely over the mTLS Controller boundary and is never logged or returned to the browser.

The request's Candidate ID, payload/tree digests, expected target head, and receipt digests are optimistic assertions only. Inside that same transaction the service locks the current Candidate, AcceptanceRun/continuity receipts, Batch, Context watermark, and authorization rows, recomputes every canonical digest, compares the assertions byte-for-byte, and constructs `VerifiedMergeSubject` solely from those locked server-side records. A client-supplied digest is never passed through as signing authority.

Task 9 also adds the WorkItem command declared in Task 1:

```java
record StrictWorkItemMergeRequest(
    long expected_version, UUID assignment_id, String provider_fact_digest
) {}
```

Replace the Task 7 `WorkItemController` constructor with the complete three-dependency constructor below, then add the endpoint method to that class:

```java
private final StrictWorkItemCoordinator strictWorkItems;

WorkItemController(
    AssignmentService assignments,
    CompletionService completions,
    StrictWorkItemCoordinator strictWorkItems
) {
    this.assignments = assignments;
    this.completions = completions;
    this.strictWorkItems = strictWorkItems;
}
```

```java
@PostMapping("/{workItemId}/strict-merge-requests")
ResponseEntity<ExternalIntentView> requestStrictMerge(
    @AuthenticationPrincipal VerifiedRequestIdentity identity,
    @PathVariable UUID projectId,
    @PathVariable UUID batchId,
    @PathVariable UUID workItemId,
    @RequestHeader("X-Accord-Fresh-Auth") UUID freshAuthSessionId,
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @RequestHeader("If-Match") String ifMatch,
    @Valid @RequestBody StrictWorkItemMergeRequest request
) {
    return strictWorkItems.requestAuthorized(
        identity, projectId, batchId, workItemId, freshAuthSessionId,
        idempotencyKey, ifMatch, request);
}
```

The controller derives `WORK_ITEM_PR` only after every Task 7 gate passes against a stable Provider snapshot. `strictWorkItems.requestAuthorized` uses one `AuthorizationService.authorizeAndExecute` transaction to consume the action-bound `X-Accord-Fresh-Auth`, claim persistent idempotency, verify route/body/assignment/fact/CAS bindings, freeze `WORK_ITEM_PR`, and append authorization intent/audit/outbox; rollback restores the fresh-auth proof and every domain write.

`BranchReleaseWorkflow` cannot derive or submit a merge subject; it is restricted to zero-diff create-ref through the branch-control Connector profile. Contract and integration tests prove `WORK_ITEM_PR`, `ACCEPTED_DELIVERY_CANDIDATE`, and `EMERGENCY_CHANGE` each reserve/finalize a signing token and reach Provider mutation only through Merge Controller plus the strict-merge Connector profile, while branch release cannot call either service. A generic public `merge-subject` endpoint is forbidden.

- [ ] **Step 4: Atomically reserve and consume authorization**

Identity Signing Task 13's V002 schema is already immutable by the time this plan executes, so V003 is a forward migration over its real columns, not a replacement-table fiction. V002 already owns `source_head_sha`, `verified_result_tree_sha`, `required_checks_digest`, `ci_attestation_digest`, `subject_type`, `subject_id`, `subject_digest`, `consumed_at`, and `consumed_by_workload_id`; V003 preserves every one with its name and value and never re-adds, renames, drops, or repurposes it. The release first deploys the fail-closed legacy RPC and removes the old Controller workload, then V003 takes an exclusive table lock, removes direct runtime DML, and performs only these structural additions:

```sql
LOCK TABLE public.authorization_nonce IN ACCESS EXCLUSIVE MODE;
REVOKE ALL ON TABLE public.authorization_nonce FROM accord_signing_runtime;
DROP INDEX public.authorization_nonce_unconsumed_idx;

ALTER TABLE public.authorization_nonce
  ADD COLUMN binding_schema_version smallint,
  ADD COLUMN branch_policy_digest char(71),
  ADD COLUMN context_evidence_digest char(71),
  ADD COLUMN effective_manifest_digest char(71),
  ADD COLUMN merge_method varchar(16),
  ADD COLUMN state varchar(40),
  ADD COLUMN reservation_id uuid,
  ADD COLUMN reservation_generation bigint NOT NULL DEFAULT 0,
  ADD COLUMN reserved_by_workload_id uuid,
  ADD COLUMN reserved_at timestamptz,
  ADD COLUMN provider_request_id varchar(255),
  ADD COLUMN outcome_digest char(71),
  ADD COLUMN provider_observed_at timestamptz,
  ADD COLUMN finalized_at timestamptz,
  ADD COLUMN finalized_by_workload_id uuid;

UPDATE public.authorization_nonce SET state = 'LEGACY_OUTCOME_UNKNOWN';

ALTER TABLE public.authorization_nonce
  ALTER COLUMN state SET NOT NULL,
  ALTER COLUMN state SET DEFAULT 'ISSUED',
  ADD CONSTRAINT authorization_nonce_binding_version_ck
    CHECK (binding_schema_version IS NULL OR binding_schema_version = 2),
  ADD CONSTRAINT authorization_nonce_state_ck
    CHECK (state IN ('ISSUED','RESERVED','CONSUMED_MERGED','CANCELLED_PROVEN_NO_EFFECT',
      'LEGACY_OUTCOME_UNKNOWN','LEGACY_RECONCILED_MERGED','LEGACY_RECONCILED_NO_EFFECT')),
  ADD CONSTRAINT authorization_nonce_legacy_consumption_pair_ck
    CHECK ((consumed_at IS NULL) = (consumed_by_workload_id IS NULL)),
  ADD CONSTRAINT authorization_nonce_legacy_consumed_time_ck
    CHECK (consumed_at IS NULL OR (consumed_at >= issued_at AND consumed_at < expires_at)),
  ADD CONSTRAINT authorization_nonce_generation_ck CHECK (reservation_generation >= 0),
  ADD CONSTRAINT authorization_nonce_reserved_before_expiry_ck
    CHECK (reserved_at IS NULL OR reserved_at < expires_at),
  ADD CONSTRAINT authorization_nonce_provider_time_ck
    CHECK (provider_observed_at IS NULL OR
      (provider_observed_at >= issued_at AND (reserved_at IS NULL OR provider_observed_at >= reserved_at))),
  ADD CONSTRAINT authorization_nonce_finalized_time_ck
    CHECK (finalized_at IS NULL OR
      (provider_observed_at IS NOT NULL AND finalized_at >= provider_observed_at));

CREATE INDEX authorization_nonce_issued_idx
  ON public.authorization_nonce (tenant_id, purpose, expires_at) WHERE state = 'ISSUED';
CREATE INDEX authorization_nonce_reserved_reconcile_idx
  ON public.authorization_nonce (tenant_id, immutable_repository_id, reserved_at)
  WHERE state = 'RESERVED';
CREATE INDEX authorization_nonce_legacy_reconcile_idx
  ON public.authorization_nonce (tenant_id, immutable_repository_id, issued_at)
  WHERE state = 'LEGACY_OUTCOME_UNKNOWN';
CREATE UNIQUE INDEX authorization_nonce_reservation_uidx
  ON public.authorization_nonce (tenant_id, reservation_id) WHERE reservation_id IS NOT NULL;
CREATE UNIQUE INDEX authorization_nonce_provider_request_uidx
  ON public.authorization_nonce (tenant_id, immutable_repository_id, provider_request_id)
  WHERE provider_request_id IS NOT NULL;
```

V003 also creates the owner-only singleton `authorization_protocol_epoch(protocol_epoch smallint primary key, state, cutover_receipt_digest, activated_at)` with epoch 2 initially `FENCED`. The migration itself never trusts a row count or timestamp as cutover proof. `activate_authorization_protocol_v2` is executable only by the migration-activation workload after it has verified the signed cutover receipt; it atomically stores that receipt digest and changes `FENCED -> ACTIVE`. The v2 issue function checks this row under lock, so migration completion alone cannot resume issuance. The production-V1 path requires the preflight assertion that V002 has zero nonce rows; a non-empty installation is accepted only through the documented compatibility path and still maps every old row to the conservative legacy state above.

Add named digest/enum checks for every optional new field and a closed `authorization_nonce_state_shape_ck` implementing this exact matrix:

| State | v2 binding | V002 `consumed_at/by` | reservation tuple | outcome tuple |
|---|---|---|---|---|
| `LEGACY_OUTCOME_UNKNOWN` | all absent | both absent or both present | generation `0`, all other fields absent | all absent |
| `LEGACY_RECONCILED_MERGED`, `LEGACY_RECONCILED_NO_EFFECT` | all absent | unchanged pair | generation `0`, reservation fields absent; reconciled Provider request ID may be present | digest, `provider_observed_at`, `finalized_at`, finalizer all present |
| `ISSUED` | version `2` and all six appended fields present | both absent | generation `0`, all other fields absent | all absent |
| `RESERVED` | complete | both absent | ID, positive generation, reserving workload, `reserved_at`, Provider request ID all present | all absent |
| `CONSUMED_MERGED` | complete | both absent | complete | digest, `provider_observed_at`, `finalized_at`, finalizer all present |
| `CANCELLED_PROVEN_NO_EFFECT` | complete | both absent | either complete, or generation `0` with every reservation field absent | digest, `provider_observed_at`, `finalized_at`, finalizer all present |

Implement that table literally, rather than approximating it in service code:

```sql
ALTER TABLE public.authorization_nonce
  ADD CONSTRAINT authorization_nonce_new_digest_ck CHECK (
    (branch_policy_digest IS NULL OR branch_policy_digest ~ '^sha256:[0-9a-f]{64}$') AND
    (context_evidence_digest IS NULL OR context_evidence_digest ~ '^sha256:[0-9a-f]{64}$') AND
    (effective_manifest_digest IS NULL OR effective_manifest_digest ~ '^sha256:[0-9a-f]{64}$') AND
    (outcome_digest IS NULL OR outcome_digest ~ '^sha256:[0-9a-f]{64}$') AND
    (provider_request_id IS NULL OR btrim(provider_request_id) <> '') AND
    (merge_method IS NULL OR merge_method IN ('MERGE','SQUASH','REBASE'))
  ),
  ADD CONSTRAINT authorization_nonce_state_shape_ck CHECK (
    (state = 'LEGACY_OUTCOME_UNKNOWN' AND
      binding_schema_version IS NULL AND branch_policy_digest IS NULL AND
      context_evidence_digest IS NULL AND effective_manifest_digest IS NULL AND merge_method IS NULL AND
      reservation_id IS NULL AND reservation_generation = 0 AND reserved_by_workload_id IS NULL AND
      reserved_at IS NULL AND provider_request_id IS NULL AND outcome_digest IS NULL AND
      provider_observed_at IS NULL AND finalized_at IS NULL AND finalized_by_workload_id IS NULL)
    OR
    (state IN ('LEGACY_RECONCILED_MERGED','LEGACY_RECONCILED_NO_EFFECT') AND
      binding_schema_version IS NULL AND branch_policy_digest IS NULL AND
      context_evidence_digest IS NULL AND effective_manifest_digest IS NULL AND merge_method IS NULL AND
      reservation_id IS NULL AND reservation_generation = 0 AND reserved_by_workload_id IS NULL AND
      reserved_at IS NULL AND outcome_digest IS NOT NULL AND provider_observed_at IS NOT NULL AND
      finalized_at IS NOT NULL AND finalized_by_workload_id IS NOT NULL)
    OR
    (state = 'ISSUED' AND consumed_at IS NULL AND consumed_by_workload_id IS NULL AND
      binding_schema_version = 2 AND branch_policy_digest IS NOT NULL AND
      context_evidence_digest IS NOT NULL AND effective_manifest_digest IS NOT NULL AND merge_method IS NOT NULL AND
      reservation_id IS NULL AND reservation_generation = 0 AND reserved_by_workload_id IS NULL AND
      reserved_at IS NULL AND provider_request_id IS NULL AND outcome_digest IS NULL AND
      provider_observed_at IS NULL AND finalized_at IS NULL AND finalized_by_workload_id IS NULL)
    OR
    (state = 'RESERVED' AND consumed_at IS NULL AND consumed_by_workload_id IS NULL AND
      binding_schema_version = 2 AND branch_policy_digest IS NOT NULL AND
      context_evidence_digest IS NOT NULL AND effective_manifest_digest IS NOT NULL AND merge_method IS NOT NULL AND
      reservation_id IS NOT NULL AND reservation_generation > 0 AND reserved_by_workload_id IS NOT NULL AND
      reserved_at IS NOT NULL AND provider_request_id IS NOT NULL AND outcome_digest IS NULL AND
      provider_observed_at IS NULL AND finalized_at IS NULL AND finalized_by_workload_id IS NULL)
    OR
    (state = 'CONSUMED_MERGED' AND consumed_at IS NULL AND consumed_by_workload_id IS NULL AND
      binding_schema_version = 2 AND branch_policy_digest IS NOT NULL AND
      context_evidence_digest IS NOT NULL AND effective_manifest_digest IS NOT NULL AND merge_method IS NOT NULL AND
      reservation_id IS NOT NULL AND reservation_generation > 0 AND reserved_by_workload_id IS NOT NULL AND
      reserved_at IS NOT NULL AND provider_request_id IS NOT NULL AND outcome_digest IS NOT NULL AND
      provider_observed_at IS NOT NULL AND finalized_at IS NOT NULL AND finalized_by_workload_id IS NOT NULL)
    OR
    (state = 'CANCELLED_PROVEN_NO_EFFECT' AND consumed_at IS NULL AND consumed_by_workload_id IS NULL AND
      binding_schema_version = 2 AND branch_policy_digest IS NOT NULL AND
      context_evidence_digest IS NOT NULL AND effective_manifest_digest IS NOT NULL AND merge_method IS NOT NULL AND
      outcome_digest IS NOT NULL AND provider_observed_at IS NOT NULL AND
      finalized_at IS NOT NULL AND finalized_by_workload_id IS NOT NULL AND
      ((reservation_id IS NOT NULL AND reservation_generation > 0 AND reserved_by_workload_id IS NOT NULL AND
          reserved_at IS NOT NULL AND provider_request_id IS NOT NULL)
       OR
       (reservation_id IS NULL AND reservation_generation = 0 AND reserved_by_workload_id IS NULL AND
          reserved_at IS NULL AND provider_request_id IS NULL)))
  );
```

The four v2 binding fields use `^sha256:[0-9a-f]{64}$`, `merge_method` is only `MERGE | SQUASH | REBASE`, outcome digests use the same digest pattern, Provider request IDs are nonblank, and legacy consumption times must be within the original issue/expiry interval. A before-update trigger makes tenant, nonce hash, purpose/scope/repository, every original and v2 binding field, issue/expiry, and both V002 consumption fields immutable. Its transition half permits only `ISSUED -> RESERVED`, `ISSUED -> CANCELLED_PROVEN_NO_EFFECT`, `RESERVED -> CONSUMED_MERGED | CANCELLED_PROVEN_NO_EFFECT`, and, through the isolated reconciliation function only, `LEGACY_OUTCOME_UNKNOWN -> LEGACY_RECONCILED_MERGED | LEGACY_RECONCILED_NO_EFFECT`; every terminal row rejects UPDATE and every row rejects DELETE. Migration tests inspect each named constraint, exact index predicate/key order, trigger enablement, existing `relrowsecurity/relforcerowsecurity`, and the unchanged tenant policy instead of recreating the V002 RLS policy.

Replace direct DML with owner-defined `SECURITY DEFINER` functions that pin `search_path=pg_catalog,public`, force `row_security=on`, set tenant locally, and write the security audit in the same transaction. `issue_authorization_v2` accepts only binding version 2 while protocol epoch 2 is active. `reserve_authorization_v2` compares tenant, nonce hash and every immutable binding field, requires unexpired `ISSUED`, assigns `reservation_generation = reservation_generation + 1`, and records the caller-generated globally unique reservation ID and Provider idempotency request ID. `finalize_authorization_v2` compares the same full binding plus reservation ID, exact generation, stored reserving workload and Provider request ID; normal mTLS caller identity must equal the stored workload. It deliberately has no `expires_at > now()` predicate: a token had to be reserved before expiry, but an already-observed external merge/no-effect must remain recordable after expiry. The separately credentialed reconciler can finalize only through a proof-bearing reconciliation function and can never invoke Provider mutation. `cancel_unreserved_authorization_v2` is the only direct `ISSUED -> CANCELLED_PROVEN_NO_EFFECT` path and requires a current Provider no-effect proof. Runtime roles receive only EXECUTE on their exact functions, never table `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES`, or `TRIGGER`; the legacy reconciler receives only its reconciliation function.

The protobuf keeps `ConsumeAuthorizationToken` only as a wire-compatible deprecated method; its server handler always returns `FAILED_PRECONDITION/LEGACY_CONSUME_DISABLED` before claims verification or database access. Reserve verifies the complete v2 envelope and expected repo/ref/heads/tree/diff/check/CI/subject/policy/Context/manifest/merge-method binding before the function call, then returns the raw nonce once only to the epoch-2 Controller with the database reservation ID/generation. Finalize verifies the same immutable envelope and tuple and records the closed outcome `MERGED | NO_EFFECT` with `provider_observed_at`. Unknown Provider outcome remains `RESERVED`, blocks a second mutation, and is terminalized only from authoritative Provider facts.

The rollout is an explicit strict-lane protocol epoch change, never a mixed-version rolling update:

1. Pause v1 strict issuance and Provider mutation, mark affected strict Batches operationally suspended, and reconcile every known in-flight request to a known or held outcome. Standard delivery and non-Git collaboration remain available.
2. Deploy Signing Service binaries whose legacy Consume handler fails before all SQL, drain predecessors, and prove the fail-closed handler on every replica. Revoke the epoch-1 Controller SPIFFE/workload from caller policy, NetworkPolicy, ServiceAccount/IAM and certificate issuance; scale/drain it; prove zero old sessions and Provider calls in the signed cutover receipt.
3. Run V003 while the lane is fenced. Verify unchanged V002 columns, new constraints/functions/indexes/grants/triggers, forced RLS, epoch state `FENCED`, and rejection of both old direct INSERT and old consume UPDATE before starting a Controller.
4. On compatibility upgrades, keep every old row `LEGACY_OUTCOME_UNKNOWN`. An old unconsumed row may reach `LEGACY_RECONCILED_NO_EFFECT` only after the cutover receipt and Provider facts prove no mutation; an old consumed row remains unknown until Provider reconciliation proves merged or no effect. No consumption timestamp is interpreted as external success, and no legacy row is reissued.
5. Verify and persist the cutover receipt, activate epoch 2, start only epoch-2 Controllers, and run one canary reserve/finalize before unpausing v2 issuance. Any unresolved legacy row keeps its repository degraded/suspended and cannot be manually overridden.

`MtlsCallerPolicy.java` therefore adds immutable `protocol_epoch=2` and workload profile to caller policy; both new RPCs require that Controller epoch, while legacy reconciliation uses a separate non-Provider credential. Rollback before activation leaves the epoch fenced. After activation there is no down migration or v1 re-enable path: rollback fences new issuance and Provider mutation, preserves V003 evidence, and deploys only a V003-compatible prior binary. This prevents ABA reuse, blocks old RPC/SQL bypass, and survives Controller failover without inventing an external outcome.

- [ ] **Step 5: Finalize repository CompletionSet entries and detect assurance drift**

After an accepted repository Candidate is merged and reconciled, `CompletionSetCoordinator` locks the Batch, RepositoryWorkSet, merge intent, Provider fact, Candidate/Acceptance, artifact and Context watermark rows. It inserts one immutable repository completion entry binding Provider installation/repository, final commit/tree, artifact profile/digest, Candidate, Context Version, WorkItem completions and evidence digest. The first of several repositories sets delivery coverage `PARTIAL`; only an exact entry for every current non-cancelled WorkSet computes a canonical CompletionSet digest, sets coverage `COMPLETE`, and permits overall completion. A failed repository never alters or rolls back successful entries. Duplicate facts return the same entry; changed evidence requires reconciliation and cannot overwrite it.

Add the already frozen `getCompletionSet` operation to the existing Delivery controller. The query remains in the Delivery module and reads only its own aggregate/entry projection; `CompletionSetCoordinator` writes through the Delivery module's application port, so Delivery does not acquire a reverse dependency on Git Coordination:

```java
@GetMapping("/delivery-batches/{batchId}/completion-set")
ResponseEntity<CompletionSetView> getCompletionSet(
    @AuthenticationPrincipal VerifiedRequestIdentity identity,
    @PathVariable UUID projectId,
    @PathVariable UUID batchId
) {
    return batches.getCompletionSetAuthorized(identity, projectId, batchId);
}
```

`getCompletionSetAuthorized` returns the single aggregate row and its entries in canonical RepositoryWorkSet order under one repeatable-read snapshot, verifies each entry's Batch/installation/repository binding before mapping, and emits the aggregate's strong numeric `ETag`. It returns `NONE` with an empty entry list before the first terminal repository fact, `PARTIAL` only with internally complete immutable entries, and `COMPLETE` only when the deferred database invariant and recomputed canonical set digest both pass.

Periodic and pre-merge policy attestations cover installation authentication/credential epoch, CapabilitySnapshot, merge/push subjects, required checks, administrator bypass, force push/delete, merge queue and refs. Non-Controller writes, credential/capability/policy drift, force updates or break-glass set the affected RepositoryWorkSet `DEGRADED`, invalidate aggregate completion eligibility, suspend the Batch and disable strict labels until full recovery.

- [ ] **Step 6: Run race, replay, uncertain-result, and network-policy tests**

Run:

```bash
./gradlew :security-services:signing-service:test --tests '*PostgresAuthorizationReservationStoreTest' --tests '*V003MigrationTest' --tests '*MtlsCallerPolicyTest'
./gradlew :security-services:merge-controller:test --tests '*MergeServiceTest'
./gradlew :security-services:signing-service:bootJar :security-services:merge-controller:bootJar
buf format --diff --exit-code
buf lint
pwsh -NoProfile -File tests/architecture/verify-protobuf-compatibility.ps1
buf generate
pnpm contracts:lint
node --test tests/contracts/signing-contract.test.mjs
./gradlew :tests:contract:test --tests '*SigningContractCompatibilityTest'
./gradlew :tests:api:test --tests '*StrictDeliveryApiTest'
./gradlew :tests:state-machine:test --tests '*CompletionSetProperties'
helm template accord infra/helm/accord --namespace accord > build/helm/accord-merge-controller.yaml
conftest test build/helm/accord-merge-controller.yaml -p infra/policy
```

Expected: all tests pass; historical v1 claims still verify against byte-identical v1 schema/goldens but cannot be newly issued or reserved; a real V002-to-V003 migration preserves both legacy rows and both old consumption columns without duplicate columns or fabricated outcomes; the deprecated Consume RPC and V002 INSERT/consume SQL fail closed; epoch 2 cannot issue before cutover-receipt activation; exact binding-version/policy/Context/manifest/method/Provider-installation/capability substitutions fail; direct issued cancellation requires no-effect proof; Finalize records an already-observed result after token expiry; 100 competing requests for each subject yield at most one reservation and Provider merge by reservation-ID/generation CAS; an uncertain result leaves exactly one reserved authorization; legacy unknown rows keep the repository suspended until reconciled; the rendered central chart contains an HA protocol-epoch-2 `accord-merge-controller` with PDB, topology spread, dedicated workload identity and default-deny egress; Controller cannot create refs/blobs/commits or access branch-control/Credential Broker/KMS/control-plane credentials.

- [ ] **Step 7: Commit strict control**

```bash
git add contracts/protobuf/accord/signing/v1/signing.proto contracts/dsse-payloads/signing-claims-v2.schema.json contracts/golden-fixtures/signing/claims-v2.input.json contracts/golden-fixtures/signing/claims-v2.canonical.json contracts/golden-fixtures/signing/claims-v2.sha256 tests/contracts/signing-contract.test.mjs contracts/gen/java tests/contract/src/test/java/com/inforvans/accord/contracts/SigningContractCompatibilityTest.java database/signing-service/migrations/V003__merge_subject_reservation.sql security-services/signing-service/build.gradle security-services/signing-service/src/main/java/com/inforvans/accord/signing/nonce security-services/signing-service/src/test/java/com/inforvans/accord/signing/nonce security-services/signing-service/src/main/java/com/inforvans/accord/signing/signing/SigningService.java security-services/signing-service/src/main/java/com/inforvans/accord/signing/api/SigningGrpcService.java security-services/signing-service/src/main/java/com/inforvans/accord/signing/transport security-services/signing-service/src/test/java/com/inforvans/accord/signing/transport security-services/merge-controller apps/control-plane/modules/git-coordination apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/api/DeliveryBatchController.java apps/control-plane/modules/workitem-execution/src/main/java/com/inforvans/accord/workitem/api/WorkItemController.java infra/helm/accord tests/api/src/test/java/com/inforvans/accord/api/StrictDeliveryApiTest.java docs/runbooks/strict-assurance-recovery.md
git commit -m "feat(git): enforce strict one-time merge control"
```

### Task 10: Implement Abort, Emergency Change, Rollback, And Break-Glass Recovery

**Files:**
- Modify: `settings.gradle`
- Create: `contracts/json-schema/delivery/cancellation-decision.schema.json`
- Create: `database/control-plane/migrations/V043__delivery_emergency_and_abort.sql`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/EmergencyChangeService.java`
- Create: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/application/AbortService.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/application/BreakGlassRecoveryService.java`
- Create: `contracts/protobuf/accord/breakglass/v1/broker.proto`
- Create: `database/break-glass-broker/migrations/V001__grant_consumption.sql`
- Create: `security-services/break-glass-broker/build.gradle`
- Create: `security-services/break-glass-broker/src/main/java/com/inforvans/accord/breakglass/BreakGlassBrokerApplication.java`
- Create: `security-services/break-glass-broker/src/main/java/com/inforvans/accord/breakglass/authorization/BreakGlassAuthorizationVerifier.java`
- Create: `security-services/break-glass-broker/src/main/java/com/inforvans/accord/breakglass/authorization/PostgresGrantReservationStore.java`
- Create: `security-services/break-glass-broker/src/test/java/com/inforvans/accord/breakglass/authorization/BreakGlassAuthorizationVerifierTest.java`
- Create: `tests/contract/src/test/java/com/inforvans/accord/contracts/BreakGlassAuthorizationContractTest.java`
- Create: `tests/fixtures/breakglass/authorization-valid.json`
- Create: `tests/fixtures/breakglass/authorization-wrong-purpose.json`
- Create: `tests/fixtures/breakglass/authorization-wrong-domain.json`
- Create: `tests/fixtures/breakglass/authorization-extra-field.json`
- Create: `security-services/break-glass-broker/src/main/java/com/inforvans/accord/breakglass/BreakGlassBrokerService.java`
- Create: `security-services/break-glass-broker/src/test/java/com/inforvans/accord/breakglass/BreakGlassBrokerServiceTest.java`
- Create: `security-services/break-glass-broker/src/main/java/com/inforvans/accord/breakglass/provider/EmergencyMergeExecutor.java`
- Create: `security-services/break-glass-broker/src/main/java/com/inforvans/accord/breakglass/provider/ProtectionRestoreExecutor.java`
- Create: `security-services/break-glass-broker/src/main/java/com/inforvans/accord/breakglass/provider/ProviderFenceExecutor.java`
- Create: `security-services/break-glass-broker/src/test/java/com/inforvans/accord/breakglass/provider/WorkloadProfileIsolationTest.java`
- Modify: `apps/control-plane/modules/delivery/src/main/java/com/inforvans/accord/delivery/api/DeliveryBatchController.java`
- Create: `apps/control-plane/modules/git-coordination/src/main/java/com/inforvans/accord/git/api/EmergencyChangeController.java`
- Test: `apps/control-plane/modules/git-coordination/src/test/java/com/inforvans/accord/git/EmergencyAndAbortTest.java`
- Test: `tests/api/src/test/java/com/inforvans/accord/api/EmergencyDeliveryApiTest.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/AbortRecoveryTest.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/EmergencyChangeRecoveryTest.java`
- Test: `tests/fault-injection/src/test/java/com/inforvans/accord/fault/BreakGlassRecoveryTest.java`
- Create: `docs/runbooks/batch-abort.md`
- Create: `docs/runbooks/emergency-change.md`
- Modify: `infra/helm/accord/values.yaml`
- Modify: `infra/helm/accord/templates/workloads.yaml`
- Modify: `infra/helm/accord/templates/serviceaccounts.yaml`
- Modify: `infra/helm/accord/templates/networkpolicies.yaml`

- [ ] **Step 1: Add tests for prohibited abort, dual authorization, degraded strict state, and post-hotfix reconciliation**

```java
@Test
void batchCannotAbortAfterDefaultBranchContainsCandidate() {
    var result = abort.request(principals, batch, facts(true));
    assertThat(result.problemCode()).isEqualTo("ROLLBACK_REQUIRED_AFTER_MERGE");
}

@Test
void breakGlassImmediatelyDegradesStrictAssurance() {
    breakGlass.consume(validGrant);
    assertThat(batch.assuranceState()).isEqualTo(DEGRADED);
    assertThat(batch.operationalState()).isEqualTo(SUSPENDED);
}
```

`EmergencyDeliveryApiTest` proves that one authorization cannot activate an emergency change, the same natural person cannot occupy both authorization slots, missing/replayed/wrong-action fresh-auth returns the stable RFC 7807 code without a receipt, an expired grant cannot create a broker intent, a replayed grant command returns the byte-identical secret-free response, and an abort request with unresolved Provider outcome returns `409 EXTERNAL_INTENT_UNRESOLVED` without changing phase. Broker tests prove a nonce is handed to the executor once and is unrecoverable after crash/restart.

- [ ] **Step 2: Run and verify emergency services are absent**

Run: `./gradlew :apps:control-plane:modules:git-coordination:test --tests '*EmergencyAndAbortTest'`

Expected: compilation fails for the emergency services.

- [ ] **Step 3: Implement fail-closed abort**

Require both principals, fresh authentication, no queued/in-flight/unknown merge intent, revoked/unconsumed authorizations, and Provider proof that default ancestry excludes the Candidate. `ABORTED` is terminal. Preserve branches, PRs, contracts, and audit; end Assignments and route cleanup. A merged candidate requires RollbackDelivery/EmergencyChange with real developer code and Context Patch.

Add `createAbortDecision` to the existing `DeliveryBatchController` only in this task, after `AbortService` exists:

```java
record CreateBatchAbortDecisionRequest(
    long expected_version, String effective_manifest_digest, String reason
) {}
```

Replace the Task 2 `DeliveryBatchController` constructor with this four-dependency constructor:

```java
private final AbortService aborts;

DeliveryBatchController(
    BatchService batches, ReadyPoolService readyPool, Clock clock, AbortService aborts
) {
    this.batches = batches;
    this.readyPool = readyPool;
    this.clock = clock;
    this.aborts = aborts;
}
```

Add the typed abort endpoint to that controller:

```java

@PostMapping("/delivery-batches/{batchId}/abort-decisions")
ResponseEntity<BatchAbortDecisionView> createAbortDecision(
    @AuthenticationPrincipal VerifiedRequestIdentity identity,
    @PathVariable UUID projectId,
    @PathVariable UUID batchId,
    @RequestHeader("X-Accord-Fresh-Auth") UUID freshAuthSessionId,
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @RequestHeader("If-Match") String ifMatch,
    @Valid @RequestBody CreateBatchAbortDecisionRequest request
) {
    return aborts.requestAuthorized(
        identity, projectId, batchId, freshAuthSessionId, idempotencyKey, ifMatch, request);
}
```

The replacement constructor injects `AbortService` as `aborts`. Verify body/route/effective-manifest/version equality and, in one authorization/idempotency/CAS transaction for each side, consume that natural person's action-bound fresh-auth proof and append the immutable confirmation. The final side locks both receipts and every no-merge predicate before terminalizing. There is no `DELETE delivery-batch` endpoint and no generic phase update.

`V043__delivery_emergency_and_abort.sql` creates tenant-scoped append-only `batch_abort_decision`, `emergency_change`, `emergency_change_authorization`, `break_glass_grant`, `break_glass_consumption`, and `break_glass_provider_intent` tables with composite tenant foreign keys, unique active/idempotency constraints, frozen subject/action digests, and legal-state checks. It invokes `accord_security.enforce_tenant_table` for every table before granting least-privilege runtime access, uses `FORCE ROW LEVEL SECURITY`, exposes no nonce/raw secret column, and prevents UPDATE/DELETE on authorization/consumption receipts. `EmergencyAndAbortTest` migrates the complete catalog through V043, inspects policies/grants/triggers, proves tenant collision isolation, and exercises two concurrent final authorizations so only one intent can win.

- [ ] **Step 4: Implement EmergencyChange without a second normal batch**

Bind incident, severity, exact default ref/head, scope, responsible developer, tests/Patch, rollback plan, Development Principal, second authorization, CI facts, and outcome. `change_kind` is closed to `HOTFIX | ROLLBACK_DELIVERY`; the latter additionally binds the affected Batch/Candidate and is the concrete RollbackDelivery implementation, not an unimplemented alternate object. Standard mode uses the customer emergency merge lane plus reconciliation. Strict mode uses the dedicated `issueEmergencyMergeAuthorization` command below and Task 9's `EMERGENCY_CHANGE` subject; it cannot reuse the Batch Candidate endpoint. After merge, update default Context first and reconcile the active delivery lineage, requirements, candidates, acceptance, and artifact eligibility.

- [ ] **Step 5: Implement break-glass grant and strict recovery**

Register `:security-services:break-glass-broker` in `settings.gradle`. Its `build.gradle` applies `java` and `org.springframework.boot`, pins Java 21, consumes only generated signing/evidence/Provider Connector contracts, and uses JUnit 5, AssertJ, Flyway, jOOQ, and PostgreSQL Testcontainers. It remains a separately packaged Spring Boot process and cannot depend on concrete adapters, control-plane domain modules, Merge Controller, Development Package, Branch Release, Credential Broker implementation, or Signing Service implementation packages. Require two different authorized humans even when a DualRole Principal exists, separate action-bound fresh-auth sessions, incident ID, exact tenant/Provider installation/repo/ref/scope, reason, a policy-capped expiry of at most 15 minutes, and one broker-internal one-time authorization. It cannot fabricate requirement confirmation or acceptance. Recovery requires restored protection/capability, reconciliation of unknown writes, Context rebuild, a new Candidate, affected acceptance, and a signed strict-restoration decision.

Implement the four public emergency command families in one explicit adapter:

```java
record CreateEmergencyChangeRequest(
    long expected_version,
    UUID repository_id,
    String incident_id,
    EmergencyChangeKind change_kind,
    IncidentSeverity severity,
    String target_ref,
    String expected_target_head,
    UUID responsible_developer_account_id,
    EmergencyScope scope,
    String rollback_plan,
    @Nullable UUID rollback_of_candidate_id
) {}
record AuthorizeEmergencyChangeRequest(
    long expected_version, UUID emergency_change_id,
    EmergencyAuthorizationRole authorization_role, String subject_digest
) {}
record EmergencyStrictMergeRequest(
    long expected_version, UUID emergency_change_id,
    String emergency_candidate_digest, String provider_fact_digest
) {}
record CreateBreakGlassGrantRequest(
    long expected_version,
    String incident_id,
    UUID repository_id,
    String target_ref,
    Set<String> scope,
    String reason,
    BreakGlassProviderAction provider_action,
    BreakGlassProviderActionParameters provider_action_parameters,
    String provider_fact_baseline_digest,
    Instant expires_at
) {
    CreateBreakGlassGrantRequest { scope = Set.copyOf(scope); }
}
record ConsumeBreakGlassGrantRequest(
    long expected_version, UUID grant_id, String expected_grant_action_digest
) {}

@RestController
@RequestMapping("/v1/projects/{projectId}")
final class EmergencyChangeController {
    private final EmergencyChangeService emergency;
    private final BreakGlassRecoveryService breakGlass;

    EmergencyChangeController(
        EmergencyChangeService emergency, BreakGlassRecoveryService breakGlass
    ) {
        this.emergency = emergency;
        this.breakGlass = breakGlass;
    }

    @PostMapping("/emergency-changes")
    ResponseEntity<EmergencyChangeView> create(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @RequestHeader("X-Accord-Fresh-Auth") UUID fresh,
        @RequestHeader("Idempotency-Key") String key,
        @RequestHeader("If-Match") String etag,
        @Valid @RequestBody CreateEmergencyChangeRequest body
    ) {
        return emergency.createAuthorized(identity, projectId, fresh, key, etag, body);
    }

    @PostMapping("/emergency-changes/{emergencyChangeId}/authorizations")
    ResponseEntity<EmergencyChangeView> authorize(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID emergencyChangeId,
        @RequestHeader("X-Accord-Fresh-Auth") UUID fresh,
        @RequestHeader("Idempotency-Key") String key,
        @RequestHeader("If-Match") String etag,
        @Valid @RequestBody AuthorizeEmergencyChangeRequest body
    ) {
        return emergency.authorizeAuthorized(
            identity, projectId, emergencyChangeId, fresh, key, etag, body);
    }

    @PostMapping("/emergency-changes/{emergencyChangeId}/strict-merge-requests")
    ResponseEntity<ExternalIntentView> strictMerge(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID emergencyChangeId,
        @RequestHeader("X-Accord-Fresh-Auth") UUID fresh,
        @RequestHeader("Idempotency-Key") String key,
        @RequestHeader("If-Match") String etag,
        @Valid @RequestBody EmergencyStrictMergeRequest body
    ) {
        return emergency.requestStrictMergeAuthorized(
            identity, projectId, emergencyChangeId, fresh, key, etag, body);
    }

    @PostMapping("/break-glass-grants")
    ResponseEntity<BreakGlassGrantCreatedView> grant(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @RequestHeader("X-Accord-Fresh-Auth") UUID fresh,
        @RequestHeader("Idempotency-Key") String key,
        @RequestHeader("If-Match") String etag,
        @Valid @RequestBody CreateBreakGlassGrantRequest body
    ) {
        return breakGlass.createGrantAuthorized(identity, projectId, fresh, key, etag, body);
    }

    @PostMapping("/break-glass-grants/{grantId}/consumption")
    ResponseEntity<BreakGlassConsumptionView> consume(
        @AuthenticationPrincipal VerifiedRequestIdentity identity,
        @PathVariable UUID projectId,
        @PathVariable UUID grantId,
        @RequestHeader("X-Accord-Fresh-Auth") UUID fresh,
        @RequestHeader("Idempotency-Key") String key,
        @RequestHeader("If-Match") String etag,
        @Valid @RequestBody ConsumeBreakGlassGrantRequest body
    ) {
        return breakGlass.consumeAuthorized(identity, projectId, grantId, fresh, key, etag, body);
    }
}
```

Route/body IDs must match. Every public command above uses verified identity and server-side repository ownership, calls `AuthorizationService.authorizeAndExecute`, and atomically consumes the actor's `X-Accord-Fresh-Auth`, claims persistent idempotency, advances CAS, and writes its receipt/audit/outbox; any authorization, audit, outbox, or storage failure rolls back fresh auth, idempotency, CAS, receipt, and all domain writes. The second natural person cannot reuse the first proof or change the frozen subject digest.

The create-grant transaction validates the action-specific closed parameters, freezes Provider action, typed parameters, Provider baseline fact digest, scope, reason, and expiry, computes `grant_action_digest`, and binds the first human receipt to that exact digest. Consumption cannot introduce or alter an action: it locks the frozen grant, requires `expected_grant_action_digest` to match byte-for-byte, consumes the second distinct natural person's fresh-auth proof over the same digest, and only then creates the broker intent. API/fault tests inject failure after each transactional write and prove no partial fresh-auth consumption, idempotency claim, receipt, degraded state, or external intent survives.

The public grant command is globally idempotent and returns the same secret-free `BreakGlassGrantCreatedView` on replay. It never returns a nonce to the browser and therefore does not conflict with the platform's byte-exact idempotency contract. After both human receipts exist, consumption atomically marks strict assurance `DEGRADED`, suspends every affected Batch/Candidate, and creates a `BREAK_GLASS_PROVIDER_ACTION` external intent before any Provider capability is released.

Consume the Identity Signing Task 10 `break-glass-authorization.schema.json`, signing protobuf, generated Java type, signing golden fixture, and `IssueBreakGlassAuthorization` RPC as the single authoritative contract. `broker.proto` references that generated binding; it does not redefine it. The closed `BreakGlassAuthorizationBinding` is not `VerifiedMergeSubject`, `ExactAuthorizationBinding`, or a strict merge token. It binds `tenant_id`, `repository_id`, `target_ref`, `grant_id`, `grant_action_digest`, the closed Provider action and parameters, scope digest, baseline Provider fact digest, reason digest, exactly two distinct human receipt digests, recovery-obligation digest, issued/expiry time, one positive broker reservation generation, and `nonce_hash = SHA-256(raw_nonce)`.

`BreakGlassProviderAction` is closed to `UNSPECIFIED`, `MERGE_EMERGENCY_CHANGE`, `RESTORE_PROTECTION_POLICY`, and `FENCE_PROVIDER_MUTATIONS`. `MERGE_EMERGENCY_CHANGE` parameters are exactly `emergency_change_id`, `provider_pr_id`, `expected_source_head`, `expected_target_head`, `expected_result_tree`, and `merge_method`; `RESTORE_PROTECTION_POLICY` parameters are exactly `expected_current_policy_digest` and `desired_certified_policy_digest`; `FENCE_PROVIDER_MUTATIONS` parameters are exactly `provider_installation_id`, `expected_credential_epoch`, and `incident_id`. `grant_action_digest` is RFC 8785 JCS over `{action, parameters}`. Arbitrary ref update, force push/delete, blob/commit/content mutation, and arbitrary Provider endpoints are unrepresentable.

The signed payload is the closed wrapper `{schema_version: "1.0", purpose: "BREAK_GLASS", domain: "accord.break-glass.authorization.v1", binding, evidence_snapshot_digest}` with payload type `application/vnd.accord.break-glass-authorization.v1+jcs`. `binding_digest` is SHA-256 of JCS `binding`; the envelope signs the JCS wrapper. Key policy rejects `STRICT_MERGE`, and Merge Controller rejects the break-glass purpose/domain/binding. Broker independently recomputes the binding digest and verifies the signed evidence snapshot digest; it persists both in its reservation and cannot substitute the envelope digest for either.

The pre-existing `IssueBreakGlassAuthorization` request/response accepts only `BreakGlassAuthorizationBinding`; it does not add a fifth `MergeSubjectType` and cannot call `ReserveAuthorizationToken` or `FinalizeAuthorizationToken`. The Broker must create its reservation generation and nonce hash before this RPC. The signing service then verifies the complete frozen grant, binding digest, nonce-hash shape, and two receipt digests through its read-only evidence port and signs with the separate `BREAK_GLASS` KMS key; it has no raw nonce or Provider credential. Broker verifies schema, DSSE PAE, domain, payload type, purpose, key ID, binding digest, current key validity, expiry, reservation generation, nonce hash, and exact grant facts before attaching the envelope to that same reservation. Git-side negative fixtures make wrong-purpose, wrong-domain, missing/substituted nonce hash, wrong generation, and unknown-field behavior byte-stable against the Identity golden fixture. Compatibility tests prove the strict and break-glass generated Java request types are not assignable or accepted by each other's RPC.

The consumed Identity contract exposes only `AuthorizationEvidenceService.GetBreakGlassGrantEvidence({tenant_id, binding, expected_binding_digest}) -> {grant_version, binding_digest, authorization_state, human_authorization_receipt_digests, expires_at, evidence_snapshot_digest}` over mTLS to the signing-service workload audience. The control-plane provider canonicalizes and rehashes the full closed binding and compares every grant-owned field with the frozen grant/intent; it sees only the hash, never the raw nonce, and has no mutation or source fields. `IssueBreakGlassAuthorization` first checks its persistent `(tenant_id, grant_id, binding_digest)` idempotency record, then calls this read-only RPC with a two-second deadline and at most three bounded retries. Timeout/unavailable, non-`AUTHORIZED` state, expiry, version/digest/field mismatch, invalid generation/hash, or anything other than exactly two distinct current receipts fails closed before KMS. On success one signing-DB transaction claims the binding digest, signs with the `BREAK_GLASS` KMS key, and persists the complete DSSE bytes, key ID, and evidence snapshot digest; concurrent or later identical calls return those exact bytes. Signing service never connects directly to the control-plane database.

`break-glass-broker` is a separate HA authorization domain from control-api, branch-control Connector, and Merge Controller. `V001__grant_consumption.sql` installs its own FORCE-RLS/least-privilege reservation store; it does not use `V003__merge_subject_reservation.sql`. The same signed image is deployed as three isolated workload profiles: `break-glass-merge`, `break-glass-protection`, and `break-glass-fence`. Each has a distinct ServiceAccount/IRSA role, queue audience, database policy, NetworkPolicy, Credential Broker audience and Connector endpoint allowlist, and can lease only its matching action. Merge receives only short-lived single-installation/repository/ref/PR merge permission; protection receives only exact repository/ref ruleset permission and cannot merge or read content; fence receives installation-security administration only and cannot read Git content, change refs/protection, or merge. No credential spans two profiles.

After mTLS intake the matching profile first atomically claims the intent, reserves its next generation, generates the 256-bit nonce inside that workload, stores only its hash, and builds the complete binding in state `RESERVED_UNSIGNED`. It requests the signature while retaining the raw value only in that process, verifies that the returned DSSE binds the exact generation and hash, and atomically records the envelope/digests as `SIGNED_READY` on the same reservation. Only then may it obtain its action-scoped Provider credential and call its in-process executor. The executor locks the reservation, recomputes `SHA-256(raw_nonce)`, compares it and the generation with both the row and signed binding, and atomically transitions once to `EXECUTING`; a different raw nonce, a hash/generation substitution, or replay fails before credential use or Provider I/O. Neither the browser, control-plane database, global idempotency response, logs, nor later reads can recover the raw value.

`V001__grant_consumption.sql` enforces unique `(tenant_id, grant_id, reservation_generation)` and unique `(tenant_id, nonce_hash)`, closed transitions `RESERVED_UNSIGNED -> SIGNED_READY -> EXECUTING -> CONSUMED | OUTCOME_UNKNOWN` or `RESERVED_UNSIGNED | SIGNED_READY -> CANCELLED_PROVEN_NO_EFFECT`, and append-only terminal evidence. A crash before Provider credential issuance loses the raw nonce permanently; reconciliation may mark that generation cancelled only after proving no external effect, then allocate and sign a new generation/hash if the grant remains valid. A crash during/after Provider I/O remains `OUTCOME_UNKNOWN` until active reconciliation proves the exact result; it never regenerates a nonce to consume the old envelope, blindly retries, or converts into an `EMERGENCY_CHANGE`/strict token. The action-specific Provider API allowlist contains only its exact mutation and fact queries. An outage in one profile does not grant or affect another profile, normal Controller credentials, or control-api.

- [ ] **Step 6: Run emergency and recovery drills**

Run:

```bash
./gradlew :tests:fault-injection:test --tests '*Abort*' --tests '*EmergencyChange*' --tests '*BreakGlass*'
./gradlew :tests:api:test --tests '*EmergencyDeliveryApiTest'
./gradlew :tests:contract:test --tests '*BreakGlassAuthorizationContractTest'
buf format --diff --exit-code
buf lint
pwsh -NoProfile -File tests/architecture/verify-protobuf-compatibility.ps1
buf generate
./gradlew :security-services:break-glass-broker:test
./gradlew :security-services:break-glass-broker:bootJar
helm template accord infra/helm/accord --namespace accord > build/helm/accord-break-glass.yaml
conftest test build/helm/accord-break-glass.yaml -p infra/policy
```

Expected: all tests pass; a strict EmergencyChange reaches Provider only through its own `EMERGENCY_CHANGE` subject; control-api has no Provider mutation credential; browser responses contain no nonce; a missing/wrong signed `nonce_hash`, raw-nonce substitution, wrong reservation generation, duplicate consume, pre-sign crash, post-sign/pre-credential crash, and crash-after-call all fail or reconcile under the exact state model without Provider replay; uncertain Provider facts remain suspended rather than aborted; and strict assurance returns only after every recovery predicate is proven.

- [ ] **Step 7: Commit emergency paths**

```bash
git add settings.gradle contracts/json-schema/delivery/cancellation-decision.schema.json contracts/protobuf/accord/breakglass database/control-plane/migrations/V043__delivery_emergency_and_abort.sql database/break-glass-broker security-services/break-glass-broker apps/control-plane/modules/git-coordination apps/control-plane/modules/delivery infra/helm/accord tests/contract/src/test/java/com/inforvans/accord/contracts/BreakGlassAuthorizationContractTest.java tests/fixtures/breakglass tests/api/src/test/java/com/inforvans/accord/api/EmergencyDeliveryApiTest.java tests/fault-injection/src/test/java/com/inforvans/accord/fault/AbortRecoveryTest.java tests/fault-injection/src/test/java/com/inforvans/accord/fault/EmergencyChangeRecoveryTest.java tests/fault-injection/src/test/java/com/inforvans/accord/fault/BreakGlassRecoveryTest.java docs/runbooks/batch-abort.md docs/runbooks/emergency-change.md
git commit -m "feat(delivery): add controlled abort and emergency recovery"
```

### Task 11: Certify Five Provider Families, External Adapter Contracts, And Cross-Repository Delivery

**Files:**
- Create: `tests/provider-certification/github/`
- Create: `tests/provider-certification/gitlab/`
- Create: `tests/provider-certification/gitee/`
- Create: `tests/provider-certification/azure-devops/`
- Create: `tests/provider-certification/bitbucket/`
- Create: `tests/provider-certification/external-adapter-sdk/contract-tck.spec.ts`
- Create: `tests/provider-certification/multi-provider/cross-repository.spec.ts`
- Create: `tests/fault-injection/git/uncertain-merge.spec.ts`
- Create: `tests/fault-injection/git/webhook-chaos.spec.ts`
- Modify: `tests/api/src/test/java/com/inforvans/accord/api/DeliveryOpenApiContractTest.java`
- Create: `docs/runbooks/provider-uncertain-result.md`

- [ ] **Step 1: Declare exact Provider, authentication, adapter, and capability certification rows**

Each Provider directory contains a versioned `capability-matrix.yaml`, control API spec, standard journey, strict journey where eligible, webhook fixtures and endpoint-policy fixtures. Rows bind Provider family, Cloud/Server deployment, exact server/API version range, adapter/SPI version, immutable identity scheme, authentication class, permission set, protection/ruleset capability, future-ref protection, ChangeRequest and merge-queue semantics, expected-head behavior, webhook verification, rate limits, reconciliation cursor and supported branch-release strategy. Rows include evidence environment ID, test run digest, issued/expiry time and `unsupported / experimental / limited_availability / ga` status. Missing qualified authentication, live environment, control entry point or evidence makes strict `unsupported` or `limited_availability`; it never silently reduces guarantees.

The external SDK TCK proves manifest signature/digest, SPI compatibility, endpoint allowlist, error taxonomy, idempotency and source boundary. Passing TCK alone grants standard-mode eligibility; strict requires a separate Accord certification signature over the exact adapter digest and live Provider row.

- [ ] **Step 2: Run the standard-mode journey**

For every built-in family, create a Batch through the generated `accord-control-api` client, confirm the exact manifest, download and verify the signed Development Package, and release a zero-diff branch ref. Prove delayed, mismatched and `OUTCOME_UNKNOWN` results remain `PUBLISHING`; in a two-Provider Batch prove one success produces `PARTIAL`, not `READY`, and the second exact proof atomically produces `COMPLETE/READY`. Continue through WorkItem merges with uploaded Patch/no-change evidence, suppress/duplicate/reorder webhooks, perform an administrator bypass, inspect `git-evidence`, and execute only typed reconciliation commands through the public API. The specs run against packaged services and real Provider sandboxes; importing Java services, inserting post-setup domain rows, using MSW, or marking a mock result certified is forbidden.

Run each configured real target independently:

```bash
pnpm test:provider --project github --grep @standard
pnpm test:provider --project gitlab --grep @standard
pnpm test:provider --project gitee --grep @standard
pnpm test:provider --project azure-devops --grep @standard
pnpm test:provider --project bitbucket --grep @standard
pnpm test:provider --project multi-provider --grep @cross-repository
```

Expected: each family has at least one real standard-certified row before production V1; no developer-start state appears before package plus branch proof; partial cross-repository release/delivery never becomes overall ready/completed; bypass is detected within the SLO and never yields incorrect CompletionSet state. An unavailable licensed/self-managed environment leaves its row unissued and blocks the corresponding release claim; it is not replaced by a mock.

- [ ] **Step 3: Run the strict-mode journey and adversarial cases**

For every matrix row marked strict-eligible, execute this order: (1) prove CapabilitySnapshot/authentication and release a protected zero-diff ref through branch-control Connector without a merge subject; (2) every gated WorkItem derives `WORK_ITEM_PR` and merges through Controller before a final Candidate exists; (3) build the repository Candidate and complete the exact AcceptanceRun/artifact/Context chain; (4) issue final authorization, derive `ACCEPTED_DELIVERY_CANDIDATE`, and merge through Controller; (5) create and dual-authorize an EmergencyChange, derive `EMERGENCY_CHANGE`, and merge through the independent emergency operation. For each of the three merge subjects assert signing reservation/finalization, Controller identity, strict-merge Connector audience, exact Provider installation/repository request, subject type/ID/digest/capability binding, and absence of alternative mutation credentials.

Then attempt direct push, force push, non-Controller merge, stale-head merge, reused nonce, changed protection, revoked credential epoch, stale CapabilitySnapshot, wrong installation/repository/checks/subject type/ID/digest, Connector timeout after merge, forbidden content endpoint and branch protection race. Exercise break-glass separately with distinct verified humans and prove it uses only `BreakGlassAuthorizationBinding`, `BREAK_GLASS` purpose/key, broker identity, signed nonce hash/generation and its isolated Connector/Credential Broker audience; substitute a different raw nonce and replay after consumption, assert both fail before a second Provider call, assert the API never exposes either lane's nonce, and immediately report degraded/suspended after consumption.

Run `pnpm test:provider --project <provider-family> --grep @strict` for every row that requests strict certification. The runner rejects a strict label when no matching live matrix row and evidence digest exist.

Expected: all three strict merge subjects come from Controller through the strict Connector profile; branch release never creates a merge subject; break glass comes only from its broker lane; adversarial operations fail or immediately degrade/suspend when only externally detectable.

- [ ] **Step 4: Inject Provider uncertainty and control-plane crashes**

Run: `pnpm test:fault --grep @git-control`

Expected: every before/after-call crash converges through stored intent and installation-scoped Provider request/fact lookup; no mutation is blindly repeated; one installation circuit breaker never blocks another; a successful repository result remains immutable while failed WorkSets retry and overall coverage stays partial.

- [ ] **Step 5: Run the source-boundary scan**

Now that Tasks 2-10 have installed the SPI, Connector profiles and built-in adapters, replace the Task 1 class declaration with this Spring context declaration:

```java
@SpringBootTest
class DeliveryOpenApiContractTest {
    private final RequestMappingHandlerMapping handlerMapping;

    @Autowired
    DeliveryOpenApiContractTest(RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }
```

Retain the existing class body and add the runtime half of the contract closure below; it uses the exact 24-entry `controllerMethods` map frozen in Task 1:

```java
private record HandlerBinding(String method, String path, String controllerMethod) {}

@Test
void allDeliveryOperationsCloseOverExactlyOneRealSpringHandler() {
    var controllerTypes = controllerMethods.values().stream()
        .map(value -> value.substring(0, value.indexOf('#')))
        .collect(toUnmodifiableSet());

    var actual = handlerMapping.getHandlerMethods().entrySet().stream()
        .filter(entry -> controllerTypes.contains(entry.getValue().getBeanType().getSimpleName()))
        .flatMap(entry -> entry.getKey().getPatternValues().stream()
            .flatMap(path -> entry.getKey().getMethodsCondition().getMethods().stream()
                .map(method -> new HandlerBinding(
                    method.name(), path,
                    entry.getValue().getBeanType().getSimpleName()
                        + "#" + entry.getValue().getMethod().getName()))))
        .collect(toUnmodifiableSet());

    var expected = controllerMethods.entrySet().stream()
        .map(entry -> {
            var operation = api.operation(entry.getKey());
            return new HandlerBinding(operation.method(), operation.path(), entry.getValue());
        })
        .collect(toUnmodifiableSet());

    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(api.operationIdsForTag("delivery-control"))
        .containsExactlyInAnyOrderElementsOf(controllerMethods.keySet());
}
```

The test fails for a documented operation with zero or multiple handlers, a method/path drift, a handler not named by `x-controller-method`, an extra mapping on any declared Delivery controller, or any owner count other than 24. It must inspect the packaged application context, not mocks or a hand-maintained route registry.

Run: `pnpm test:security --grep @git-source-boundary`

Expected: control-plane/Connector request captures, logs, traces, messages, databases, and object stores contain no customer source/diff body or Provider credential; only structured hashes, IDs, path/evidence locators, signed packages and Provider metadata are present. The edge-only encrypted raw Webhook forensic store is separately inspected for TTL, decrypt-role isolation and forbidden source/diff fields, and no raw body crosses into control-plane storage.

Regenerate and verify the cumulative client before accepting provider evidence:

```bash
pnpm contracts:lint
pnpm contracts:generate
git diff --exit-code packages/api-client
pwsh -NoProfile -File tests/bootstrap/verify-workspace.ps1
./gradlew :tests:api:test --tests '*DeliveryOpenApiContractTest' --tests '*DeliveryBatchApiTest' --tests '*WorkItemApiTest' --tests '*ReconciliationApiTest' --tests '*StrictDeliveryApiTest' --tests '*EmergencyDeliveryApiTest'
```

Expected: every Web Task 13-14 and Master M3/M5 operation is backed by a real controller, generated request/response type, authorization rule, stable problem code, and packaged black-box test; a second generation has no diff.

- [ ] **Step 6: Commit Provider certification evidence**

```bash
git add tests/provider-certification tests/fault-injection/git tests/api/src/test/java/com/inforvans/accord/api/DeliveryOpenApiContractTest.java docs/runbooks/provider-uncertain-result.md
git commit -m "test(git): certify standard and strict delivery controls"
```

## Completion Gate

This subsystem is complete only when the Foundation workspace/runtime scan passes; manifest folding is deterministic and fork-free; a Batch may span Providers but no repository can participate in two active normal batches; signed Development Packages are immutable platform artifacts and no platform workload writes repository documents or transfers Git source; `READY` and developer-start actions are impossible until every RepositoryWorkSet has a current package plus a complete zero-diff branch-release receipt binding installation/repository/manifest/capability/ref/baseline commit-tree/latest Provider fact; partial release or delivery remains explicit and never triggers automatic rollback; every WorkItem merge binds real Provider/CI/Patch facts and every overall completion has a complete exact CompletionSet; standard bypass is detected and recovered within SLO; strict `WORK_ITEM_PR`, `ACCEPTED_DELIVERY_CANDIDATE`, and `EMERGENCY_CHANGE` merges are 100% Controller-originated through the strict Connector profile with exact-head CAS and one-time reservation; BreakGlass is isolated by binding/purpose/key/workload/Connector/Credential Broker audience; protection, credential and capability drift degrade honestly; abort is impossible after any affected default merge; emergency and break-glass paths retain repository-specific Context and acceptance integrity; all five built-in Provider families have at least one live standard certification row and strict is exposed only for exact passing rows; external adapters remain standard-only without certification; and source/diff bodies and credentials are absent from every control-plane surface.
