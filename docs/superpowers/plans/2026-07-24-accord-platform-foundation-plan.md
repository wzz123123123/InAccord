# Accord Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the locked, testable Accord monorepo foundation, shared contracts, control-plane reliability primitives, isolated webhook ingress, local integration environment, and verified delivery pipeline on which all product modules depend.

**Architecture:** Java 21 and Spring Modulith provide the control-plane domain implementation, launched as separately permissioned `control-api` and `control-worker` Spring Boot processes. Every edge and security boundary remains an independently deployable Java 21 application with its own identity, database role, credential set, network policy, and artifact; language consolidation never consolidates authority. PostgreSQL is authoritative for business facts, idempotency, leases, fencing, capabilities, and coordination, while Temporal carries identifiers and retry progress only and the Python Agent Runtime consumes only signed, source-free structured projections.

**Tech Stack:** Java 21, Gradle 8.14.3 Groovy DSL, Spring Boot 3.5.3, Spring Modulith 1.4.1, jOOQ 3.19.24, Flyway 11.8.2, PostgreSQL 17.5, Temporal Java SDK 1.28.1, Picocli with platform-specific `jlink` runtime images, OpenAPI 3.1, JSON Schema 2020-12, protobuf/gRPC with mTLS, RFC 8785 JCS, DSSE, React 19.1, React Router 7.6.3, TanStack Query, TypeScript 5.8, Vite 7, Python 3.12.11 with Pydantic v2 and uv, capability-tested OSS/S3-compatible object storage, OpenTelemetry, Docker Compose, Kubernetes, Helm, OpenTofu, Argo CD, CycloneDX, Syft, and Cosign.

---

## Scope And Invariants

- Treat `requirements-agent-platform-design.md` as the approved product and trust-boundary specification. This plan establishes M0 platform primitives; it does not implement Requirement Graph, assessment, delivery, or acceptance workflows.
- PostgreSQL is the only V1 durable state system. It owns command results, rate limits, leases, fencing tokens, one-time capability claims, outbox/inbox delivery, and certification coordination. Process-local caches may hold bounded public or already-authorized immutable data but cannot decide authorization, replay protection, workflow state, or cross-replica coordination. Temporal history contains identifiers and orchestration progress only.
- Every persisted resource and message carries `tenant_id`. Repository-scoped records additionally carry the provider's immutable repository ID. Generic keys use `{tenant_id, scope_type, scope_id}` and never synthesize repository IDs for tenant-level or pre-binding state.
- `control-api` and `control-worker` use the same modular application code but have distinct entry points, service accounts, network policies, and database roles.
- Webhook Edge accepts untrusted provider requests, verifies them, deduplicates them, and emits normalized signals. A webhook never authorizes a state transition and is never the only source of provider truth.
- Webhook Edge, Attachment Scanner, Agent Pack Gateway, Provider Connector, Credential Broker, Signing Service, Strict Merge Controller, and break-glass brokers remain independent Java deployments. They may depend on generated contracts and narrowly scoped libraries; security services may not depend on `apps/control-plane/modules/**`.
- Do not introduce an additional distributed cache or coordination database, Kafka, Camunda, a generic `common` module, source-code storage, or Git-content credentials in the control plane.

## Target Repository Map

```text
apps/
  web/                              # React workspace; product UI arrives in later plans
  control-plane/
    api/                            # HTTP entry point and RFC 7807 mapping
    worker/                         # outbox, reconciliation, and Temporal workers
    modules/
      platform-kernel/              # canonical values and module-neutral ports
      reliability/                  # CAS, idempotency, outbox, inbox, external intents
  webhook-edge/                    # isolated Java service for untrusted webhook ingress
  agent-runtime/                   # isolated Python/Pydantic workspace
  attachment-scanner/              # isolated scanner deployment, added by attachment plan
security-services/                 # independent Java Spring Boot applications
  signing-service/                 # added by identity/trust plan
  provider-connector/              # added by Git delivery plan
  credential-broker/               # added by Git delivery plan
  merge-controller/                # added by strict-delivery plan
contracts/
  json-schema/ openapi/ protobuf/ events/ dsse-payloads/ golden-fixtures/
agent-pack/
libs/java/                         # bounded Java libraries; never a generic utility module
database/control-plane/migrations/
database/webhook-edge/migrations/
tests/bootstrap/ contracts/ integration/ architecture/
infra/local/ helm/ opentofu/ argocd/ policy/
docs/architecture/ runbooks/
```

## Implementation Entry Gates

Foundation Task 1 starts only after the following repository-inception gate is green. Repository inception is an operator action outside this plan; Task 1 must never run `git init`, invent a history, or commit an unapproved design.

- The approved requirement specification, Java/Python runtime decision, master plan, and every dependent implementation plan are mutually consistent, contain no executable old-runtime instructions, and are committed on local `main`.
- `main` is pushed to the authoritative remote, protected against force-push/direct write, and the implementation worktree is an isolated feature branch descended from that exact baseline.
- Engineering, product, security, database, platform, and incident owners are named; required reviewers and CODEOWNERS paths are agreed before generated policy files are accepted.
- Java/Gradle, Node/pnpm, Python/uv, Buf, container, policy, infrastructure, SBOM, signing, and scanning tool versions are available. CI can reach authenticated corporate plugin/package/container mirrors; public fallback is disabled in CI.
- The artifact registry, OIDC trust, protected release environments, external secret mechanism, PKI/KMS ownership, vulnerability exception process, and audit retention owner are selected. No credential value is stored in a plan or repository bootstrap script.

Tasks 1-12 may use the approved local Testcontainers/Compose profiles. Tasks 13-16 cannot claim production readiness until the environment gate also records three isolated production PostgreSQL endpoints (control, webhook, Temporal), RPO/RTO and restore-test policy, supported regions/data residency, SLO/error-budget owners, and an object-storage adapter capability report covering immutable versions, checksums, WORM, legal hold, multipart, quarantine, replication evidence, and deletion receipts.

The current workspace is not implementation-ready merely because this file exists. The entry-gate evidence is a tracked baseline commit plus remote branch-protection/mirror/owner records; `tests/bootstrap/verify-design-baseline.ps1` verifies the repository portion again as the first Task 1 action.

### Task 1: Bootstrap And Lock The Java, Python, And Browser Monorepo

**Files:**
- Create: `.editorconfig`
- Create: `.gitattributes`
- Create: `.gitignore`
- Create: `.tool-versions`
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `apps/control-plane/api/build.gradle`
- Create: `apps/control-plane/worker/build.gradle`
- Create: `apps/control-plane/modules/platform-kernel/build.gradle`
- Create: `apps/control-plane/modules/reliability/build.gradle`
- Create: `apps/webhook-edge/build.gradle`
- Create: `security-services/signing-service/build.gradle`
- Create: `security-services/merge-controller/build.gradle`
- Create: `cmd/accordctl/build.gradle`
- Create: `libs/java/observability/build.gradle`
- Create: `tests/contract/build.gradle`
- Create: `tests/integration/build.gradle`
- Create: `tests/api/build.gradle`
- Create: `tests/security-negative/build.gradle`
- Create: `tests/state-machine/build.gradle`
- Create: `tests/fault-injection/build.gradle`
- Create: `cmd/accordctl/src/main/java/module-info.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCtl.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/AccordCtlTest.java`
- Create: `package.json`
- Create: `tsconfig.base.json`
- Create: `pnpm-workspace.yaml`
- Create: `apps/web/package.json`
- Create: `pyproject.toml`
- Create: `apps/agent-runtime/pyproject.toml`
- Create: `apps/agent-runtime/src/accord_agent_runtime/__init__.py`
- Create: `apps/agent-runtime/src/accord_agent_runtime/boundary.py`
- Create: `apps/agent-runtime/tests/test_bootstrap.py`
- Create: `tests/bootstrap/verify-design-baseline.ps1`
- Create: `tests/bootstrap/verify-workspace.ps1`
- Create: `scripts/run-gradle.ps1`
- Generate: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Generate: `gradle/wrapper/gradle-wrapper.jar.sha256`
- Generate: `gradle/verification-metadata.xml`
- Generate: `pnpm-lock.yaml`
- Generate: `uv.lock`
- Generate: `gradle.lockfile`, `settings-gradle.lockfile`, and subproject `gradle.lockfile` files

- [ ] **Step 1: Write the failing workspace-layout test**

Create `tests/bootstrap/verify-design-baseline.ps1`. Repository inception is complete before this task: the approved specification and all implementation plans are already committed and pushed to `main`, and FT1 runs only on an isolated feature branch.

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

if ((git rev-parse --is-inside-work-tree).Trim() -ne 'true') { throw 'FT1 requires an existing Git worktree' }
git show-ref --verify --quiet refs/heads/main
if ($LASTEXITCODE -ne 0) { throw 'tracked main design baseline is required before FT1' }
$requiredDesign = @(
  'requirements-agent-platform-design.md',
  'docs/superpowers/specs/2026-07-25-accord-java-python-runtime-design.md',
  'docs/superpowers/plans/2026-07-24-accord-platform-foundation-plan.md'
)
foreach ($path in $requiredDesign) {
  git ls-files --error-unmatch -- $path | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "design baseline is not tracked: $path" }
}
$approvedDigests = [ordered]@{
  'requirements-agent-platform-design.md' = '0755A08228254311588EE0B867AFEBED6CD49E26B0FCCB790247BFDA31AB2C77'
  'docs/superpowers/specs/2026-07-25-accord-java-python-runtime-design.md' = '46DE7309CB28F8E59B3FDAB4D6FA664AE4D932FC7F517741681CC25C3B84E4E1'
}
function Get-NormalizedSha256([string]$Path) {
  $utf8 = New-Object System.Text.UTF8Encoding($false, $true)
  $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
  $normalizedBytes = $utf8.GetBytes($utf8.GetString($bytes).Replace("`r`n", "`n"))
  $sha256 = [System.Security.Cryptography.SHA256]::Create()
  try {
    return [BitConverter]::ToString($sha256.ComputeHash($normalizedBytes)).Replace('-', '')
  } finally {
    $sha256.Dispose()
  }
}
foreach ($entry in $approvedDigests.GetEnumerator()) {
  $actual = Get-NormalizedSha256 -Path $entry.Key
  if ($actual -cne $entry.Value) {
    throw "approved design digest mismatch for $($entry.Key): $actual"
  }
}
git merge-base --is-ancestor main HEAD
if ($LASTEXITCODE -ne 0) { throw 'feature branch must descend from local main' }
Write-Output 'design-baseline: PASS'
```

Create `tests/bootstrap/verify-workspace.ps1`:

```powershell
$ErrorActionPreference = 'Stop'
$required = @(
  '.editorconfig',
  '.gitattributes',
  '.gitignore',
  '.tool-versions',
  'settings.gradle',
  'build.gradle',
  'gradle.properties',
  'gradle/libs.versions.toml',
  'gradlew',
  'gradlew.bat',
  'gradle/wrapper/gradle-wrapper.jar',
  'gradle/wrapper/gradle-wrapper.properties',
  'gradle/wrapper/gradle-wrapper.jar.sha256',
  'gradle/verification-metadata.xml',
  'gradle.lockfile',
  'settings-gradle.lockfile',
  'package.json',
  'pnpm-lock.yaml',
  'tsconfig.base.json',
  'pnpm-workspace.yaml',
  'pyproject.toml',
  'uv.lock',
  'apps/control-plane/api/build.gradle',
  'apps/control-plane/worker/build.gradle',
  'apps/control-plane/modules/platform-kernel/build.gradle',
  'apps/control-plane/modules/reliability/build.gradle',
  'apps/webhook-edge/build.gradle',
  'security-services/signing-service/build.gradle',
  'security-services/merge-controller/build.gradle',
  'cmd/accordctl/build.gradle',
  'libs/java/observability/build.gradle',
  'tests/contract/build.gradle',
  'tests/integration/build.gradle',
  'tests/api/build.gradle',
  'tests/security-negative/build.gradle',
  'tests/state-machine/build.gradle',
  'tests/fault-injection/build.gradle',
  'cmd/accordctl/src/main/java/module-info.java',
  'cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCtl.java',
  'cmd/accordctl/src/test/java/com/inforvans/accord/cli/AccordCtlTest.java',
  'apps/web/package.json',
  'apps/agent-runtime/pyproject.toml',
  'apps/agent-runtime/src/accord_agent_runtime/__init__.py',
  'apps/agent-runtime/src/accord_agent_runtime/boundary.py',
  'apps/agent-runtime/tests/test_bootstrap.py',
  'scripts/run-gradle.ps1'
)
$gradleProjectDirectories = @(
  'apps/control-plane/api',
  'apps/control-plane/worker',
  'apps/control-plane/modules/platform-kernel',
  'apps/control-plane/modules/reliability',
  'apps/webhook-edge',
  'security-services/signing-service',
  'security-services/merge-controller',
  'cmd/accordctl',
  'libs/java/observability',
  'tests/contract',
  'tests/integration',
  'tests/api',
  'tests/security-negative',
  'tests/state-machine',
  'tests/fault-injection'
)
$required += $gradleProjectDirectories | ForEach-Object { "$_/gradle.lockfile" }
$missing = $required | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }
if ($missing.Count -gt 0) {
  throw "Missing workspace files: $($missing -join ', ')"
}
$sourceRoots = @('apps', 'security-services', 'cmd', 'libs', 'tests')
$generatedDirectoryPattern = '[\\/](?:\.gradle|\.venv|node_modules|build|dist|\.pytest_cache|__pycache__|\.mypy_cache|\.ruff_cache)[\\/]'
$forbiddenDirectories = Get-ChildItem $sourceRoots -Directory -Recurse -ErrorAction SilentlyContinue |
  Where-Object {
    $_.FullName -notmatch $generatedDirectoryPattern -and
    $_.Name -eq 'common'
  }
if ($forbiddenDirectories) {
  throw "Unbounded common module found: $($forbiddenDirectories.FullName -join ', ')"
}
$approvedExtensions = @(
  '.java', '.py', '.ts', '.tsx', '.js', '.mjs', '.sql', '.proto',
  '.json', '.yaml', '.yml', '.md', '.toml', '.ps1', '.gradle',
  '.properties', '.xml', '.html', '.css', '.svg', '.lock', '.lockfile', '.bat',
  '.jar', '.sha256'
)
$approvedExtensionlessNames = @('Dockerfile', 'gradlew')
$unexpectedRuntimeFiles = Get-ChildItem $sourceRoots -Recurse -File -ErrorAction SilentlyContinue |
  Where-Object {
    $_.FullName -notmatch $generatedDirectoryPattern -and
    $_.Extension -notin $approvedExtensions -and
    $_.Name -notin $approvedExtensionlessNames
  }
if ($unexpectedRuntimeFiles) {
  throw "source file outside the approved runtime matrix: $($unexpectedRuntimeFiles.FullName -join ', ')"
}

$settings = Get-Content -Raw -Encoding utf8 settings.gradle
@(
  ':apps:control-plane:api',
  ':apps:control-plane:worker',
  ':apps:control-plane:modules:platform-kernel',
  ':apps:control-plane:modules:reliability',
  ':apps:webhook-edge',
  ':security-services:signing-service',
  ':security-services:merge-controller',
  ':cmd:accordctl',
  ':libs:java:observability',
  ':tests:contract',
  ':tests:integration',
  ':tests:api',
  ':tests:security-negative',
  ':tests:state-machine',
  ':tests:fault-injection'
) | ForEach-Object {
  if (-not $settings.Contains($_)) { throw "Gradle project not included: $_" }
}
$toolVersions = Get-Content -Encoding utf8 .tool-versions
$expectedTools = [ordered]@{
  java = 'temurin-21.0.7+6.0.LTS'; gradle = '8.14.3'; nodejs = '22.17.0';
  pnpm = '10.12.4'; python = '3.12.11'; uv = '0.7.13'; buf = '1.55.1';
  helm = '3.17.3'; opentofu = '1.9.1'; k6 = '0.57.0';
  conftest = '0.61.2'; kubeconform = '0.7.0'; syft = '1.27.1';
  cosign = '2.5.0'; trivy = '0.63.0'
}
foreach ($tool in $expectedTools.GetEnumerator()) {
  if ($toolVersions -notcontains "$($tool.Key) $($tool.Value)") {
    throw "Tool version is not pinned: $($tool.Key) $($tool.Value)"
  }
}
if (-not (Select-String -Quiet gradle/wrapper/gradle-wrapper.properties -Pattern '^distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531$')) {
  throw 'Gradle wrapper distribution checksum is absent or wrong'
}
$expectedWrapperJarHash = (Get-Content -Raw -Encoding ascii gradle/wrapper/gradle-wrapper.jar.sha256).Trim()
$actualWrapperJarHash = (Get-FileHash -Algorithm SHA256 gradle/wrapper/gradle-wrapper.jar).Hash.ToLowerInvariant()
if ($actualWrapperJarHash -cne $expectedWrapperJarHash) {
  throw "Gradle wrapper JAR checksum mismatch: $actualWrapperJarHash"
}
Write-Output 'workspace-layout: PASS'
```

Create `apps/agent-runtime/tests/test_bootstrap.py` before the package implementation:

```python
from uuid import UUID

import pytest
from pydantic import ValidationError
from temporalio import workflow

from accord_agent_runtime.boundary import WorkflowRef


def test_runtime_dependencies_and_identifier_only_boundary() -> None:
    reference = WorkflowRef(
        tenant_id=UUID("10000000-0000-0000-0000-000000000001"),
        workflow_id=UUID("40000000-0000-0000-0000-000000000001"),
    )

    assert callable(workflow.defn)
    assert set(WorkflowRef.model_fields) == {"tenant_id", "workflow_id"}
    with pytest.raises(ValidationError):
        WorkflowRef(
            tenant_id=reference.tenant_id,
            workflow_id=reference.workflow_id,
            source_code="customer source must not enter workflow payloads",
        )
```

- [ ] **Step 2: Run the tests and verify the greenfield failure**

Run:

```bash
pwsh -NoProfile -File tests/bootstrap/verify-design-baseline.ps1
pwsh -NoProfile -File tests/bootstrap/verify-workspace.ps1
uv run --package accord-agent-runtime pytest apps/agent-runtime/tests/test_bootstrap.py -q
```

Expected: `design-baseline: PASS`, then FAIL. The layout test reports `Missing workspace files:` with at least `settings.gradle`, and the Python command reports that `accord-agent-runtime` is not yet a workspace package. A missing `main`, untracked design file, or specification digest mismatch fails before any generated file is accepted.

- [ ] **Step 3: Add the locked root workspace files**

Create `.tool-versions`:

```text
java temurin-21.0.7+6.0.LTS
gradle 8.14.3
nodejs 22.17.0
pnpm 10.12.4
python 3.12.11
uv 0.7.13
buf 1.55.1
helm 3.17.3
opentofu 1.9.1
k6 0.57.0
conftest 0.61.2
kubeconform 0.7.0
syft 1.27.1
cosign 2.5.0
trivy 0.63.0
```

Create `settings.gradle`. CI requires the two mirror environment variables; an explicitly authorized developer workstation may use the public defaults only while producing the same verification metadata and locks:

```groovy
pluginManagement {
    def pluginMirror = System.getenv('ACCORD_GRADLE_PLUGIN_MIRROR_URL')
    if (System.getenv('CI') == 'true' && !pluginMirror) {
        throw new GradleException('ACCORD_GRADLE_PLUGIN_MIRROR_URL is required in CI')
    }
    repositories {
        if (pluginMirror) { maven { url = uri(pluginMirror) } }
        else { gradlePluginPortal(); mavenCentral() }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    def dependencyMirror = System.getenv('ACCORD_MAVEN_MIRROR_URL')
    if (System.getenv('CI') == 'true' && !dependencyMirror) {
        throw new GradleException('ACCORD_MAVEN_MIRROR_URL is required in CI')
    }
    repositories {
        if (dependencyMirror) { maven { url = uri(dependencyMirror) } }
        else { mavenCentral() }
    }
}

rootProject.name = 'accord'

include(
    ':apps:control-plane:api',
    ':apps:control-plane:worker',
    ':apps:control-plane:modules:platform-kernel',
    ':apps:control-plane:modules:reliability',
    ':apps:webhook-edge',
    ':security-services:signing-service',
    ':security-services:merge-controller',
    ':cmd:accordctl',
    ':libs:java:observability',
    ':tests:contract',
    ':tests:integration',
    ':tests:api',
    ':tests:security-negative',
    ':tests:state-machine',
    ':tests:fault-injection'
)
```

Create `gradle/libs.versions.toml`:

```toml
[versions]
spring-boot = "3.5.3"
spring-modulith = "1.4.1"
flyway = "11.8.2"
jooq = "3.19.24"
testcontainers = "1.21.3"
archunit = "1.4.1"
temporal = "1.28.1"
jcs = "1.1"
cyclonedx = "2.3.1"
junit = "5.13.1"
assertj = "3.27.3"
jqwik = "1.9.2"
json-schema-validator = "1.5.6"
pitest-gradle = "1.15.0"
picocli = "4.7.7"
jlink = "3.2.1"
jackson = "2.19.1"
slf4j = "2.0.17"

[libraries]
spring-modulith-bom = { module = "org.springframework.modulith:spring-modulith-bom", version.ref = "spring-modulith" }
spring-modulith-starter-core = { module = "org.springframework.modulith:spring-modulith-starter-core" }
spring-modulith-starter-jdbc = { module = "org.springframework.modulith:spring-modulith-starter-jdbc" }
spring-modulith-test = { module = "org.springframework.modulith:spring-modulith-starter-test" }
spring-boot-web = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-jooq = { module = "org.springframework.boot:spring-boot-starter-jooq" }
spring-boot-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-test = { module = "org.springframework.boot:spring-boot-starter-test" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-postgresql = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }
postgresql = { module = "org.postgresql:postgresql", version = "42.7.7" }
jooq-codegen = { module = "org.jooq:jooq-codegen", version.ref = "jooq" }
testcontainers-postgresql = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
testcontainers-junit = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
archunit-junit = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
temporal-sdk = { module = "io.temporal:temporal-sdk", version.ref = "temporal" }
temporal-testing = { module = "io.temporal:temporal-testing", version.ref = "temporal" }
jcs = { module = "io.github.erdtman:java-json-canonicalization", version.ref = "jcs" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
jqwik = { module = "net.jqwik:jqwik", version.ref = "jqwik" }
json-schema-validator = { module = "com.networknt:json-schema-validator", version.ref = "json-schema-validator" }
picocli = { module = "info.picocli:picocli", version.ref = "picocli" }
picocli-codegen = { module = "info.picocli:picocli-codegen", version.ref = "picocli" }
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
jackson-bom = { module = "com.fasterxml.jackson:jackson-bom", version.ref = "jackson" }
slf4j-bom = { module = "org.slf4j:slf4j-bom", version.ref = "slf4j" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
cyclonedx = { id = "org.cyclonedx.bom", version.ref = "cyclonedx" }
pitest = { id = "info.solidsoft.pitest", version.ref = "pitest-gradle" }
jlink = { id = "org.beryx.jlink", version.ref = "jlink" }
```

Create `build.gradle`:

```groovy
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.jlink) apply false
}

allprojects {
    group = 'com.inforvans.accord'
    version = '0.1.0-SNAPSHOT'
}

configurations {
    platformBaselines {
        canBeConsumed = false
        canBeResolved = true
        visible = false
        description = 'Resolves cross-project BOMs into the root dependency lock.'
    }
}

dependencies {
    platformBaselines enforcedPlatform(libs.junit.bom)
    platformBaselines enforcedPlatform(libs.jackson.bom)
    platformBaselines enforcedPlatform(libs.slf4j.bom)
}

dependencyLocking { lockAllConfigurations() }

subprojects {
    plugins.withId('java') {
        java {
            toolchain {
                languageVersion = JavaLanguageVersion.of(21)
                vendor = JvmVendorSpec.ADOPTIUM
            }
        }
        dependencies {
            testRuntimeOnly libs.junit.platform.launcher
        }
        tasks.withType(JavaCompile).configureEach {
            options.release = 21
            options.encoding = 'UTF-8'
            options.compilerArgs += ['-parameters', '-Xlint:all', '-Werror']
        }
        tasks.withType(Test).configureEach { useJUnitPlatform() }
        tasks.withType(AbstractArchiveTask).configureEach {
            preserveFileTimestamps = false
            reproducibleFileOrder = true
        }
    }
    configurations.configureEach { resolutionStrategy.failOnVersionConflict() }
    dependencyLocking { lockAllConfigurations() }
}

tasks.register('resolveAndLockAll') {
    notCompatibleWithConfigurationCache('Resolves every project configuration to refresh dependency locks.')
    doLast {
        allprojects.each { project ->
            project.configurations.findAll { it.canBeResolved }.each { it.resolve() }
        }
    }
}
```

Use this exact Groovy DSL baseline for the four control-plane projects, Webhook Edge, the two initial security-service projects, and `libs/java/observability`. The application plugins and bounded dependencies are added only by the owning tasks; registering each target now prevents later red tests from failing merely because a runner is absent:

```groovy
plugins {
    id 'java-library'
}

dependencies {
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
}
```

Create each of the six shared verification-project build files listed in this task with the following exact baseline. They are deliberately registered at bootstrap so every later `:tests:*` command resolves to one owned project; feature plans add only their bounded project dependencies and test sources.

```groovy
plugins { id 'java-library' }

dependencies {
    testImplementation project(':apps:control-plane:modules:platform-kernel')
    testImplementation enforcedPlatform(libs.junit.bom)
    testImplementation enforcedPlatform(libs.jackson.bom)
    testImplementation enforcedPlatform(libs.slf4j.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.jqwik
    testImplementation libs.json.schema.validator
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
    testRuntimeOnly libs.slf4j.simple
}

```

Create `gradle.properties`:

```properties
org.gradle.configuration-cache=true
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.java.installations.auto-download=false
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
```

Create the root `package.json`:

```json
{
  "name": "accord",
  "private": true,
  "packageManager": "pnpm@10.12.4",
  "engines": { "node": "22.17.0", "pnpm": "10.12.4" },
  "scripts": {
    "check": "pnpm -r --if-present test && pnpm -r --if-present typecheck",
    "contracts:lint": "redocly lint contracts/openapi/accord-control-api.yaml"
  },
  "pnpm": {
    "onlyBuiltDependencies": ["esbuild"],
    "ignoredBuiltDependencies": ["core-js", "protobufjs"]
  },
  "devDependencies": {
    "@redocly/cli": "1.34.3",
    "ajv": "8.17.1",
    "ajv-cli": "5.0.0",
    "ajv-formats": "3.0.1",
    "yaml": "2.8.0"
  }
}
```

Create the root `tsconfig.base.json`; application-specific `tsconfig.json`, Vite configuration, HTML, and React entry files remain owned by the Web Experience plan:

```json
{
  "compilerOptions": {
    "target": "ES2023",
    "lib": ["ES2023", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "exactOptionalPropertyTypes": true,
    "noImplicitOverride": true,
    "useUnknownInCatchVariables": true,
    "verbatimModuleSyntax": true,
    "useDefineForClassFields": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "skipLibCheck": false
  }
}
```

Create `pnpm-workspace.yaml`:

```yaml
packages:
  - apps/web
```

Create `apps/web/package.json`:

```json
{
  "name": "@accord/web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": { "test": "node --test", "typecheck": "tsc --noEmit" },
  "dependencies": {
    "@tanstack/react-query": "5.81.5",
    "react": "19.1.0",
    "react-dom": "19.1.0",
    "react-router": "7.6.3",
    "react-router-dom": "7.6.3"
  },
  "devDependencies": {
    "@types/react": "19.1.8",
    "@types/react-dom": "19.1.6",
    "typescript": "5.8.3",
    "vite": "7.0.2"
  }
}
```

Create the root `pyproject.toml`:

```toml
[tool.uv.workspace]
members = ["apps/agent-runtime"]

[tool.uv]
required-version = "==0.7.13"
```

Create `apps/agent-runtime/pyproject.toml`:

```toml
[project]
name = "accord-agent-runtime"
version = "0.1.0"
requires-python = "==3.12.11"
dependencies = [
  "nexus-rpc==1.1.0",
  "pydantic==2.11.7",
  "temporalio==1.14.1",
]

[dependency-groups]
dev = ["pytest==8.4.1", "mypy==1.16.1", "ruff==0.12.1"]

[build-system]
requires = ["hatchling==1.27.0"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/accord_agent_runtime"]
```

Create `apps/agent-runtime/src/accord_agent_runtime/__init__.py`:

```python
from .boundary import WorkflowRef

__all__ = ["WorkflowRef"]
```

Create `apps/agent-runtime/src/accord_agent_runtime/boundary.py`:

```python
from uuid import UUID

from pydantic import BaseModel, ConfigDict


class WorkflowRef(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    tenant_id: UUID
    workflow_id: UUID
```

Create `cmd/accordctl/build.gradle`; the CLI is a modular Java application and `jlink` supplies its runtime, so customer and operator workstations do not need a host JDK:

```groovy
plugins {
    id 'application'
    alias(libs.plugins.jlink)
}

dependencies {
    implementation libs.picocli
    annotationProcessor libs.picocli.codegen
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
}

application {
    mainModule = 'com.inforvans.accord.cli'
    mainClass = 'com.inforvans.accord.cli.AccordCtl'
}

tasks.named('compileJava', JavaCompile) {
    options.compilerArgs += ['-Xlint:-processing']
}

tasks.configureEach { task ->
    if (task.class.name.startsWith('org.beryx.jlink.')) {
        task.notCompatibleWithConfigurationCache('Beryx jlink 3.2.1 requires uncached execution on Gradle 8.14.3.')
    }
}

def normalizedOs = System.getProperty('os.name', 'unknown')
    .toLowerCase(java.util.Locale.ROOT)
def archiveOs
if (normalizedOs.contains('windows')) {
    archiveOs = 'windows'
} else if (normalizedOs.contains('mac') || normalizedOs.contains('darwin')) {
    archiveOs = 'macos'
} else if (normalizedOs.contains('linux')) {
    archiveOs = 'linux'
} else {
    throw new GradleException("Unsupported accordctl packaging OS: ${normalizedOs}")
}

jlink {
    options = ['--strip-debug', '--no-header-files', '--no-man-pages', '--compress=zip-6']
    launcher { name = 'accordctl' }
    imageZip = layout.buildDirectory
        .file("distributions/accordctl-${archiveOs}.zip")
        .get()
        .asFile
}
```

Create `cmd/accordctl/src/main/java/module-info.java`:

```java
module com.inforvans.accord.cli {
    requires info.picocli;
    exports com.inforvans.accord.cli;
    opens com.inforvans.accord.cli to info.picocli;
}
```

Create `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCtl.java`. Later plans add bounded Picocli subcommands to this one root rather than creating another executable:

```java
package com.inforvans.accord.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "accordctl", mixinStandardHelpOptions = true,
        description = "Accord developer and operator commands")
public final class AccordCtl implements Callable<Integer> {
    public AccordCtl() {
    }

    @Override
    public Integer call() {
        return 0;
    }

    public static int execute(String... args) {
        return new CommandLine(new AccordCtl()).execute(args);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }
}
```

Create `cmd/accordctl/src/test/java/com/inforvans/accord/cli/AccordCtlTest.java`:

```java
package com.inforvans.accord.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

final class AccordCtlTest {
    @Test
    void helpUsesTheSinglePublicCommandRoot() {
        var output = new ByteArrayOutputStream();
        var command = new CommandLine(new AccordCtl());
        command.setOut(new PrintWriter(output, true));

        assertThat(command.execute("--help")).isZero();
        assertThat(output.toString()).contains("Usage: accordctl");
    }
}
```

The Webhook Edge and two initial security-service build files use the Java library baseline above only until their owning tasks add Spring Boot entry points. They remain separate Gradle projects and deployable artifacts, may depend on generated contracts and capability-specific libraries, and may not depend on a control-plane domain module. Provider Connector, Credential Broker, Agent Pack Gateway, Attachment Scanner, and BreakGlass Broker are registered only by their owning later plans rather than as empty Foundation placeholders. `libs/java/observability` is the only initial shared-library target; it may contain telemetry types only and cannot become a generic utility package.

Create `scripts/run-gradle.ps1` so every plan command has the same Windows/Linux wrapper selection:

```powershell
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$launcher = if ($IsWindows) { Join-Path $repositoryRoot 'gradlew.bat' } else { Join-Path $repositoryRoot 'gradlew' }
$requiresUncachedExecution = $GradleArgs | Where-Object {
  $_ -eq 'resolveAndLockAll' -or $_ -match '(^|:)(?:jlink|jpackage)[A-Za-z]*$'
}
if ($requiresUncachedExecution -and $GradleArgs -contains '--configuration-cache') {
  throw 'jlink, jpackage, and dependency-lock refresh tasks do not support --configuration-cache'
}
if ($requiresUncachedExecution -and $GradleArgs -notcontains '--no-configuration-cache') {
  $GradleArgs = @('--no-configuration-cache') + $GradleArgs
}
& $launcher @GradleArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Create `.editorconfig`, `.gitattributes`, and `.gitignore` with these contents:

```ini
# .editorconfig
root = true
[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
indent_style = space
indent_size = 2
[*.{java,groovy,gradle}]
indent_size = 4
```

```gitattributes
* text=auto eol=lf
*.bat text eol=crlf
*.png binary
*.jar binary
```

```gitignore
.worktrees/
.gradle/
build/
**/build/
node_modules/
.venv/
__pycache__/
.pytest_cache/
.mypy_cache/
.ruff_cache/
*.py[cod]
dist/
.idea/
*.iml
.env
infra/local/data/
```

- [ ] **Step 4: Generate wrappers and dependency locks**

Run:

```powershell
pwsh -NoProfile -File tests/bootstrap/verify-design-baseline.ps1
gradle wrapper --gradle-version 8.14.3 --distribution-type bin
$wrapperProperties = 'gradle/wrapper/gradle-wrapper.properties'
$properties = Get-Content $wrapperProperties
if ($properties -notmatch '^distributionSha256Sum=') {
  Add-Content -Encoding utf8 $wrapperProperties 'distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531'
}
(Get-FileHash -Algorithm SHA256 gradle/wrapper/gradle-wrapper.jar).Hash.ToLowerInvariant() |
  Set-Content -Encoding ascii gradle/wrapper/gradle-wrapper.jar.sha256
pwsh -NoProfile -File scripts/run-gradle.ps1 resolveAndLockAll --write-locks --write-verification-metadata sha256,pgp
corepack pnpm install --lockfile-only
$env:UV_PYTHON_DOWNLOADS = 'never'
uv lock --python 3.12.11
```

Expected: the tracked design baseline passes before generation; no repository initialization occurs; Gradle writes wrapper 8.14.3 with the exact distribution SHA-256, a separately recorded wrapper-JAR SHA-256, strict dependency verification metadata, and one lock per project; pnpm creates lockfile version 9; uv resolves only the installed Python 3.12.11 interpreter and pins Pydantic 2.11.7. A public artifact may enter the lock only with recorded digest/signature metadata, and CI later resolves the same coordinates through the approved mirrors.

- [ ] **Step 5: Run the workspace checks**

Run:

```powershell
pwsh -NoProfile -File tests/bootstrap/verify-design-baseline.ps1
pwsh -NoProfile -File tests/bootstrap/verify-workspace.ps1
pwsh -NoProfile -File scripts/run-gradle.ps1 projects
pwsh -NoProfile -File scripts/run-gradle.ps1 test :cmd:accordctl:jlink :cmd:accordctl:jlinkZip --dependency-verification=strict
corepack pnpm install --frozen-lockfile
uv sync --locked --all-packages
uv run --package accord-agent-runtime pytest apps/agent-runtime/tests/test_bootstrap.py -q
$launcher = if ($IsWindows) { 'cmd/accordctl/build/image/bin/accordctl.bat' } else { 'cmd/accordctl/build/image/bin/accordctl' }
& $launcher --help
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
git diff --exit-code -- gradle.lockfile settings-gradle.lockfile ':(glob)**/gradle.lockfile' gradle pnpm-lock.yaml uv.lock
```

Expected: `design-baseline: PASS` and `workspace-layout: PASS`; Gradle lists exactly fifteen included subprojects (nine Java runtime/library/CLI projects and six shared verification projects); every Java compilation uses an Adoptium Java 21 toolchain; pnpm and uv complete without lock drift; the Python smoke test rejects `source_code`; the Picocli public contract test passes; the generated self-contained launcher prints `Usage: accordctl`; and strict dependency verification accepts every resolved artifact.

- [ ] **Step 6: Commit the locked workspace**

```bash
git add .editorconfig .gitattributes .gitignore .tool-versions settings.gradle settings-gradle.lockfile build.gradle gradle.properties gradle.lockfile gradle gradlew gradlew.bat apps security-services cmd/accordctl libs/java package.json tsconfig.base.json pnpm-workspace.yaml pnpm-lock.yaml pyproject.toml uv.lock scripts/run-gradle.ps1 tests/bootstrap/verify-design-baseline.ps1 tests/bootstrap/verify-workspace.ps1 tests/contract/build.gradle tests/integration/build.gradle tests/api/build.gradle tests/security-negative/build.gradle tests/state-machine/build.gradle tests/fault-injection/build.gradle
git commit -m "build: bootstrap locked Accord monorepo"
```

### Task 2: Establish JCS, SHA-256, And DSSE Golden Contracts

**Files:**
- Create: `contracts/json-schema/domain-event.schema.json`
- Create: `contracts/dsse-payloads/platform-event.schema.json`
- Create: `contracts/golden-fixtures/jcs/domain-event.input.json`
- Create: `contracts/golden-fixtures/jcs/domain-event.canonical.json`
- Create: `contracts/golden-fixtures/jcs/domain-event.sha256`
- Create: `apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platformkernel/CanonicalJson.java`
- Create: `apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platformkernel/Dsse.java`
- Create: `apps/control-plane/modules/platform-kernel/src/test/java/com/inforvans/accord/platformkernel/CanonicalJsonTest.java`
- Modify: `apps/control-plane/modules/platform-kernel/build.gradle`

- [ ] **Step 1: Write failing golden-vector tests**

Create `CanonicalJsonTest.java`:

```java
package com.inforvans.accord.platformkernel;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CanonicalJsonTest {
    private final Path fixtures = Path.of("../../../../contracts/golden-fixtures/jcs");

    @Test
    void canonicalJsonAndDigestMatchTheCrossLanguageVector() throws Exception {
        byte[] input = Files.readAllBytes(fixtures.resolve("domain-event.input.json"));
        String canonical = Files.readString(fixtures.resolve("domain-event.canonical.json")).trim();
        String digest = Files.readString(fixtures.resolve("domain-event.sha256")).trim();

        assertThat(new String(CanonicalJson.canonicalize(input), UTF_8)).isEqualTo(canonical);
        assertThat(CanonicalJson.sha256(input)).isEqualTo(digest);
    }

    @Test
    void dssePreAuthEncodingIsLengthDelimited() {
        assertThat(Dsse.preAuthEncoding("application/json", "abc".getBytes(UTF_8)))
                .isEqualTo("DSSEv1 16 application/json 3 abc".getBytes(UTF_8));
    }
}
```

- [ ] **Step 2: Run the test and verify the missing implementation**

Run: `./gradlew :apps:control-plane:modules:platform-kernel:test --tests '*CanonicalJsonTest'`

Expected: FAIL because `CanonicalJson` and `Dsse` are unresolved.

- [ ] **Step 3: Add the canonical fixtures and schemas**

Create `domain-event.input.json`:

```json
{"sequence":7,"tenant_id":"TEN-01","payload":{"b":2,"a":1}}
```

Create `domain-event.canonical.json`:

```json
{"payload":{"a":1,"b":2},"sequence":7,"tenant_id":"TEN-01"}
```

Create `domain-event.sha256`:

```text
sha256:c5d735824c5230344aefb2ea01601e55aba00fdc1bc91c795e547199738b1333
```

Create `contracts/json-schema/domain-event.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schemas.accord.inforvans.com/events/domain-event/1-0-0",
  "title": "AccordDomainEvent",
  "type": "object",
  "additionalProperties": false,
  "required": ["event_id", "tenant_id", "scope_type", "scope_id", "aggregate_type", "aggregate_id", "sequence", "event_type", "schema_version", "causation_id", "correlation_id", "actor_id", "occurred_at", "payload"],
  "properties": {
    "event_id": { "type": "string", "format": "uuid" },
    "tenant_id": { "type": "string", "format": "uuid" },
    "scope_type": { "enum": ["tenant", "project", "repository"] },
    "scope_id": { "type": "string", "minLength": 1, "maxLength": 255 },
    "aggregate_type": { "type": "string", "pattern": "^[a-z][a-z0-9_.-]{1,63}$" },
    "aggregate_id": { "type": "string", "format": "uuid" },
    "sequence": { "type": "integer", "minimum": 1 },
    "event_type": { "type": "string", "pattern": "^[a-z][a-z0-9_.-]{1,127}$" },
    "schema_version": { "type": "string", "pattern": "^[1-9][0-9]*\\.[0-9]+\\.[0-9]+$" },
    "causation_id": { "type": "string", "format": "uuid" },
    "correlation_id": { "type": "string", "format": "uuid" },
    "actor_id": { "type": "string", "minLength": 1, "maxLength": 255 },
    "occurred_at": { "type": "string", "format": "date-time" },
    "payload": { "type": "object" }
  }
}
```

Create `contracts/dsse-payloads/platform-event.schema.json` as the same schema plus a mandatory domain separator:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schemas.accord.inforvans.com/dsse/platform-event/1-0-0",
  "type": "object",
  "additionalProperties": false,
  "required": ["payload_type", "schema_version", "domain", "tenant_id", "scope_type", "scope_id", "object_id", "content_digest"],
  "properties": {
    "payload_type": { "const": "application/vnd.accord.platform-event+json" },
    "schema_version": { "const": "1.0.0" },
    "domain": { "const": "accord.platform-event.v1" },
    "tenant_id": { "type": "string", "format": "uuid" },
    "scope_type": { "enum": ["tenant", "project", "repository"] },
    "scope_id": { "type": "string", "minLength": 1 },
    "object_id": { "type": "string", "minLength": 1 },
    "content_digest": { "type": "string", "pattern": "^sha256:[0-9a-f]{64}$" }
  }
}
```

- [ ] **Step 4: Implement RFC 8785 canonicalization and DSSE PAE**

Replace `platform-kernel/build.gradle` with:

```groovy
plugins { id 'java-library' }

dependencies {
    implementation libs.jcs
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
}
```

Create `CanonicalJson.java`:

```java
package com.inforvans.accord.platformkernel;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;

public final class CanonicalJson {
    private CanonicalJson() {}

    public static byte[] canonicalize(byte[] input) {
        return new JsonCanonicalizer(new String(input, UTF_8)).getEncodedUTF8();
    }

    public static String sha256(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalize(input));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
```

Create `Dsse.java`:

```java
package com.inforvans.accord.platformkernel;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;

public final class Dsse {
    private Dsse() {}

    public static byte[] preAuthEncoding(String payloadType, byte[] payload) {
        if (payloadType == null || payloadType.isBlank()) {
            throw new IllegalArgumentException("payloadType must not be blank");
        }
        byte[] type = payloadType.getBytes(UTF_8);
        var output = new ByteArrayOutputStream();
        output.writeBytes(("DSSEv1 " + type.length + " ").getBytes(UTF_8));
        output.writeBytes(type);
        output.writeBytes((" " + payload.length + " ").getBytes(UTF_8));
        output.writeBytes(payload);
        return output.toByteArray();
    }
}
```

- [ ] **Step 5: Run the golden-vector tests**

Run: `./gradlew :apps:control-plane:modules:platform-kernel:test --tests '*CanonicalJsonTest'`

Expected: PASS with two tests; the canonical digest is exactly `sha256:c5d735824c5230344aefb2ea01601e55aba00fdc1bc91c795e547199738b1333`.

- [ ] **Step 6: Commit the canonical contract foundation**

```bash
git add contracts/json-schema contracts/dsse-payloads contracts/golden-fixtures apps/control-plane/modules/platform-kernel
git commit -m "feat: add JCS and DSSE contract primitives"
```

### Task 3: Define The External OpenAPI 3.1 And RFC 7807 Contract

**Files:**
- Create: `contracts/json-schema/problem-details.schema.json`
- Create: `contracts/openapi/accord-control-api.yaml`
- Create: `tests/contracts/openapi-contract.test.mjs`
- Modify: `package.json`
- Modify: `pnpm-lock.yaml`

- [ ] **Step 1: Write the failing OpenAPI policy test**

Create `tests/contracts/openapi-contract.test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import YAML from 'yaml';

test('mutating operations require idempotency, expected version, and problem responses', async () => {
  const source = await readFile('contracts/openapi/accord-control-api.yaml', 'utf8');
  const api = YAML.parse(source);
  assert.equal(api.openapi, '3.1.0');
  for (const [path, pathItem] of Object.entries(api.paths)) {
    for (const method of ['post', 'put', 'patch', 'delete']) {
      const operation = pathItem[method];
      if (!operation) continue;
      const refs = (operation.parameters ?? []).map((entry) => entry.$ref);
      assert.ok(refs.includes('#/components/parameters/IdempotencyKey'), `${method} ${path} lacks Idempotency-Key`);
      assert.ok(refs.includes('#/components/parameters/ExpectedVersion'), `${method} ${path} lacks If-Match`);
      assert.equal(operation.responses.default.$ref, '#/components/responses/ProblemResponse');
    }
  }
});

test('operations health contract uses the production Spring Actuator handlers', async () => {
  const source = await readFile('contracts/openapi/accord-control-api.yaml', 'utf8');
  const api = YAML.parse(source);
  const healthPaths = Object.keys(api.paths).filter((path) => path.includes('/health/')).sort();
  assert.deepEqual(healthPaths, [
    '/actuator/health/liveness',
    '/actuator/health/readiness',
  ]);
  assert.equal(api.paths['/health/ready'], undefined, 'legacy non-handler path must not be advertised');
  for (const path of healthPaths) {
    assert.equal(
      api.paths[path].get.responses['200'].content['application/json'].schema.properties.status.const,
      'UP',
    );
  }
});
```

- [ ] **Step 2: Run the contract test and verify the missing API file**

Run: `node --test tests/contracts/openapi-contract.test.mjs`

Expected: FAIL with `ENOENT` for `contracts/openapi/accord-control-api.yaml`.

- [ ] **Step 3: Add the RFC 7807 schema and OpenAPI document**

Create `contracts/json-schema/problem-details.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schemas.accord.inforvans.com/http/problem-details/1-0-0",
  "type": "object",
  "additionalProperties": true,
  "required": ["type", "title", "status", "code", "correlation_id"],
  "properties": {
    "type": { "type": "string", "format": "uri-reference" },
    "title": { "type": "string", "minLength": 1 },
    "status": { "type": "integer", "minimum": 400, "maximum": 599 },
    "detail": { "type": "string" },
    "instance": { "type": "string", "format": "uri-reference" },
    "code": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]+$" },
    "correlation_id": { "type": "string", "format": "uuid" },
    "errors": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["field", "reason"],
        "properties": { "field": { "type": "string" }, "reason": { "type": "string" } }
      }
    }
  }
}
```

Create `contracts/openapi/accord-control-api.yaml`:

```yaml
openapi: 3.1.0
info:
  title: Accord Control API
  version: 0.1.0
servers:
  - url: https://api.accord.example
paths:
  /actuator/health/liveness:
    get:
      operationId: getLiveness
      responses:
        '200':
          description: Process is live
          content:
            application/json:
              schema:
                type: object
                additionalProperties: true
                required: [status]
                properties:
                  status: { const: UP }
  /actuator/health/readiness:
    get:
      operationId: getReadiness
      responses:
        '200':
          description: Process and required dependencies are ready
          content:
            application/json:
              schema:
                type: object
                additionalProperties: true
                required: [status]
                properties:
                  status: { const: UP }
  /v1/contract-validations/{validationId}:
    post:
      operationId: validateContract
      parameters:
        - name: validationId
          in: path
          required: true
          schema: { type: string, format: uuid }
        - $ref: '#/components/parameters/IdempotencyKey'
        - $ref: '#/components/parameters/ExpectedVersion'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ContractValidationRequest'
      responses:
        '201':
          description: Validation result persisted
          headers:
            ETag:
              required: true
              schema: { type: string, pattern: '^"[0-9]+"$' }
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ContractValidationResponse'
        default:
          $ref: '#/components/responses/ProblemResponse'
components:
  parameters:
    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      description: >-
        Reuse is valid only for the same canonical request fingerprint. The fingerprint binds the
        uppercase HTTP method, normalized path and sorted query pairs, route resource identifier,
        RFC 8785 body digest, normalized If-Match value, request and response media types, API
        contract version, authenticated tenant, and actor. Reuse with any changed bound field is 409.
      schema: { type: string, minLength: 16, maxLength: 128, pattern: '^[A-Za-z0-9._:-]+$' }
    ExpectedVersion:
      name: If-Match
      in: header
      required: true
      description: Quoted aggregate sequence, for example "7".
      schema: { type: string, pattern: '^"[0-9]+"$' }
  schemas:
    ContractValidationRequest:
      type: object
      additionalProperties: false
      required: [schema_id, document]
      properties:
        schema_id: { type: string, format: uri }
        document: { type: object }
    ContractValidationResponse:
      type: object
      additionalProperties: false
      required: [validation_id, valid, document_digest, version]
      properties:
        validation_id: { type: string, format: uuid }
        valid: { type: boolean }
        document_digest: { type: string, pattern: '^sha256:[0-9a-f]{64}$' }
        version: { type: integer, minimum: 1 }
    Problem:
      type: object
      additionalProperties: true
      required: [type, title, status, code, correlation_id]
      properties:
        type: { type: string, format: uri-reference }
        title: { type: string }
        status: { type: integer, minimum: 400, maximum: 599 }
        detail: { type: string }
        instance: { type: string, format: uri-reference }
        code: { type: string, pattern: '^[A-Z][A-Z0-9_]+$' }
        correlation_id: { type: string, format: uuid }
  responses:
    ProblemResponse:
      description: RFC 7807 error
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
```

Add the root test script without changing the locked versions:

```json
"scripts": {
  "check": "pnpm -r --if-present test && pnpm -r --if-present typecheck",
  "contracts:lint": "redocly lint contracts/openapi/accord-control-api.yaml",
  "contracts:test": "node --test tests/contracts/*.test.mjs"
}
```

- [ ] **Step 4: Regenerate the JavaScript lock and run both checks**

Run:

```bash
pnpm install --lockfile-only
pnpm contracts:test
pnpm contracts:lint
```

Expected: the Node test passes; Redocly exits 0 with no errors; `pnpm-lock.yaml` changes only because the new script exposes already-declared packages.

- [ ] **Step 5: Commit the HTTP contract**

```bash
git add contracts/json-schema/problem-details.schema.json contracts/openapi/accord-control-api.yaml tests/contracts/openapi-contract.test.mjs package.json pnpm-lock.yaml
git commit -m "feat: define OpenAPI and problem contracts"
```

### Task 4: Define Protobuf Boundaries And Buf Compatibility Checks

**Files:**
- Create: `buf.yaml`
- Create: `buf.gen.yaml`
- Create: `contracts/protobuf/accord/common/v1/context.proto`
- Create: `contracts/protobuf/accord/reliability/v1/reliability.proto`
- Create: `tests/contracts/verify-protobuf.ps1`
- Generate: `build/generated/protobuf/**`

- [ ] **Step 1: Write the failing protobuf boundary test**

Create `tests/contracts/verify-protobuf.ps1`:

```powershell
$ErrorActionPreference = 'Stop'
buf lint
buf build -o build/contracts.binpb
$context = Get-Content -Raw -Encoding utf8 contracts/protobuf/accord/common/v1/context.proto
@('tenant_id', 'scope_type', 'scope_id', 'correlation_id', 'actor_id') | ForEach-Object {
  if (-not $context.Contains($_)) { throw "RequestContext is missing $_" }
}
Write-Output 'protobuf-contracts: PASS'
```

- [ ] **Step 2: Run the test and verify the missing Buf module**

Run: `pwsh -NoProfile -File tests/contracts/verify-protobuf.ps1`

Expected: FAIL because `buf.yaml` does not exist.

- [ ] **Step 3: Add bounded request context and reliability contracts**

Create `buf.yaml`:

```yaml
version: v2
modules:
  - path: contracts/protobuf
lint:
  use: [STANDARD]
breaking:
  use: [FILE]
```

Create `buf.gen.yaml`:

```yaml
version: v2
clean: true
plugins:
  - remote: buf.build/protocolbuffers/java:v29.3
    out: build/generated/protobuf/java
  - remote: buf.build/grpc/java:v1.71.0
    out: build/generated/protobuf/grpc
```

Create `context.proto`:

```proto
syntax = "proto3";

package accord.common.v1;
option java_multiple_files = true;
option java_package = "com.inforvans.accord.contracts.common.v1";

enum ScopeType {
  SCOPE_TYPE_UNSPECIFIED = 0;
  SCOPE_TYPE_TENANT = 1;
  SCOPE_TYPE_PROJECT = 2;
  SCOPE_TYPE_REPOSITORY = 3;
}

message RequestContext {
  string tenant_id = 1;
  ScopeType scope_type = 2;
  string scope_id = 3;
  string correlation_id = 4;
  string actor_id = 5;
}
```

Create `reliability.proto`:

```proto
syntax = "proto3";

package accord.reliability.v1;
option java_multiple_files = true;
option java_package = "com.inforvans.accord.contracts.reliability.v1";

import "accord/common/v1/context.proto";

service ReliabilityAdminService {
  rpc RetryOutboxEvent(RetryOutboxEventRequest) returns (RetryOutboxEventResponse);
}

message RetryOutboxEventRequest {
  accord.common.v1.RequestContext context = 1;
  string event_id = 2;
  string idempotency_key = 3;
}

message RetryOutboxEventResponse {
  string event_id = 1;
  string state = 2;
}
```

- [ ] **Step 4: Lint and generate the protobuf contracts**

Run:

```bash
pwsh -NoProfile -File tests/contracts/verify-protobuf.ps1
buf generate
```

Expected: `protobuf-contracts: PASS`; generation creates Java and gRPC sources under ignored `build/`.

- [ ] **Step 5: Commit the initial protobuf development baseline**

```bash
git add docs/superpowers/plans/2026-07-24-accord-platform-foundation-plan.md buf.yaml buf.gen.yaml contracts/protobuf tests/contracts/verify-protobuf.ps1
git commit -m "feat: define protobuf service boundaries"
```

Expected: the commit succeeds and establishes the first development baseline. Do not run
`buf breaking` against `HEAD`; a self-comparison is vacuous and is not compatibility evidence.
The first accepted production baseline is the signed `accord-contract-baseline-v1-m0` tag created
by the enterprise master plan. After that tag exists, every later protobuf change runs
`tests/architecture/verify-protobuf-compatibility.ps1`, which verifies the signed tag, its exact
full commit binding, ancestry, pinned Buf `1.55.1`, and inequality with `HEAD` before invoking
`buf breaking --against ".git#ref=<full-baseline-sha>"`.

### Task 5: Enforce Spring Modulith And Deployment Boundaries

**Files:**
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/ControlApiApplication.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/ControlWorkerApplication.java`
- Create: `apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platformkernel/package-info.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/package-info.java`
- Create: `apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/WorkerModuleBoundaryTest.java`
- Create: `apps/control-plane/api/src/main/resources/application.yml`
- Create: `apps/control-plane/worker/src/main/resources/application.yml`
- Create: `tests/architecture/NoSecurityServiceCouplingTest.java`
- Create: `tests/architecture/control-plane-runtime-boundaries.gradle`
- Create: `tests/architecture/fixtures/com/inforvans/accord/signing/SigningBoundaryFixture.java`
- Create: `tests/architecture/fixtures/com/inforvans/accord/gitprovider/GitProviderBoundaryFixture.java`
- Create: `tests/architecture/fixtures/org/eclipse/jgit/JGitBoundaryFixture.java`
- Create: `tests/architecture/fixtures/org/gitlab4j/api/GitLabBoundaryFixture.java`
- Create: `tests/architecture/fixtures/org/kohsuke/github/GitHubBoundaryFixture.java`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `apps/control-plane/modules/platform-kernel/build.gradle`
- Modify: `apps/control-plane/modules/reliability/build.gradle`
- Modify: `gradle/libs.versions.toml`
- Generate: `apps/control-plane/{api,worker}/gradle.lockfile`
- Generate: `apps/control-plane/modules/{platform-kernel,reliability}/gradle.lockfile`
- Generate: `tests/{api,contract,fault-injection,integration,security-negative,state-machine}/gradle.lockfile`
- Modify: `gradle/verification-metadata.xml`

- [ ] **Step 1: Write failing module and trust-boundary tests**

Create `ModuleBoundaryTest.java`:

```java
package com.inforvans.accord;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {
    private static final Set<String> REQUIRED_MODULES = Set.of("platformkernel", "reliability");

    @Test
    void controlPlaneModulesHaveNoCyclesOrUndeclaredAccess() {
        ApplicationModules modules = ApplicationModules.of(ControlApiApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
            .containsExactlyInAnyOrderElementsOf(REQUIRED_MODULES);
        modules.verify();
    }
}
```

Create `WorkerModuleBoundaryTest.java` with the same exact-set assertion against
`ControlWorkerApplication.class`; this prevents either deployable from silently discovering an
entry-point or adapter package as a domain module:

```java
package com.inforvans.accord;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class WorkerModuleBoundaryTest {
    private static final Set<String> REQUIRED_MODULES = Set.of("platformkernel", "reliability");

    @Test
    void workerLoadsTheSameControlPlaneModules() {
        ApplicationModules modules = ApplicationModules.of(ControlWorkerApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
            .containsExactlyInAnyOrderElementsOf(REQUIRED_MODULES);
        modules.verify();
    }
}
```

Create `NoSecurityServiceCouplingTest.java`:

```java
package com.inforvans.accord.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class NoSecurityServiceCouplingTest {
    private static final DescribedPredicate<JavaClass> FORBIDDEN_DEPENDENCIES =
        JavaClass.Predicates.resideInAnyPackage(
            "..securityservices..",
            "..signing..",
            "..gitprovider..",
            "..gitcontent..",
            "..providerconnector..",
            "..credentialbroker..",
            "..mergecontroller..",
            "org.eclipse.jgit..",
            "org.gitlab4j..",
            "org.kohsuke.github..");

    private final JavaClasses classes = new ClassFileImporter()
        .importPackages("com.inforvans.accord");

    @Test
    void controlPlaneCannotDependOnSecurityDeploymentsOrGitContentClients() {
        assertThat(classes.stream()
            .anyMatch(type -> type.getPackageName().startsWith("com.inforvans.accord.platformkernel")))
            .as("platform-kernel classes must be imported")
            .isTrue();
        assertThat(classes.stream()
            .anyMatch(type -> type.getPackageName().startsWith("com.inforvans.accord.reliability")))
            .as("reliability classes must be imported")
            .isTrue();

        noClasses().that().resideInAPackage("com.inforvans.accord..")
            .should().dependOnClassesThat(FORBIDDEN_DEPENDENCIES)
            .check(classes);
    }

    @Test
    void forbiddenPredicateMatchesRealSecurityAndGitClientNamespaces() throws Exception {
        Class<?>[] fixtures = {
            Class.forName("com.inforvans.accord.signing.SigningBoundaryFixture"),
            Class.forName("com.inforvans.accord.gitprovider.GitProviderBoundaryFixture"),
            Class.forName("org.eclipse.jgit.JGitBoundaryFixture"),
            Class.forName("org.gitlab4j.api.GitLabBoundaryFixture"),
            Class.forName("org.kohsuke.github.GitHubBoundaryFixture")
        };
        JavaClasses fixtureClasses = new ClassFileImporter().importClasses(fixtures);

        assertThat(fixtureClasses).allSatisfy(type ->
            assertThat(FORBIDDEN_DEPENDENCIES.test(type))
                .as(type.getName())
                .isTrue());
    }
}
```

Create each listed fixture as an empty `public final` class in the package encoded by its path,
with only a private constructor. The fixtures are test-only namespace probes: the string-based
`Class.forName` references prove the deny predicate matches real signing and provider namespaces
without creating a dependency that would make the production coupling rule fail.

```java
// tests/architecture/fixtures/com/inforvans/accord/signing/SigningBoundaryFixture.java
package com.inforvans.accord.signing;
public final class SigningBoundaryFixture {
    private SigningBoundaryFixture() {}
}
```

```java
// tests/architecture/fixtures/com/inforvans/accord/gitprovider/GitProviderBoundaryFixture.java
package com.inforvans.accord.gitprovider;
public final class GitProviderBoundaryFixture {
    private GitProviderBoundaryFixture() {}
}
```

```java
// tests/architecture/fixtures/org/eclipse/jgit/JGitBoundaryFixture.java
package org.eclipse.jgit;
public final class JGitBoundaryFixture {
    private JGitBoundaryFixture() {}
}
```

```java
// tests/architecture/fixtures/org/gitlab4j/api/GitLabBoundaryFixture.java
package org.gitlab4j.api;
public final class GitLabBoundaryFixture {
    private GitLabBoundaryFixture() {}
}
```

```java
// tests/architecture/fixtures/org/kohsuke/github/GitHubBoundaryFixture.java
package org.kohsuke.github;
public final class GitHubBoundaryFixture {
    private GitHubBoundaryFixture() {}
}
```

- [ ] **Step 2: Run the tests and verify the missing application roots**

Run:

```bash
./gradlew :apps:control-plane:api:test :apps:control-plane:worker:test \
  --tests '*ModuleBoundaryTest' --tests '*NoSecurityServiceCouplingTest'
```

Expected: FAIL because both application roots are unresolved and the Modulith test dependency is
absent. The architecture rule must already prove that both control-plane module packages were
imported, so an empty import can never make the rule pass.

- [ ] **Step 3: Create separate API and worker entry points with one explicit module root**

Create `ControlApiApplication.java`:

```java
package com.inforvans.accord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "Accord Control API")
@SpringBootApplication(scanBasePackages = "com.inforvans.accord")
public class ControlApiApplication {
    public static void main(String[] args) {
        System.setProperty("accord.process-role", "control-api");
        SpringApplication.run(ControlApiApplication.class, args);
    }
}
```

Create `ControlWorkerApplication.java`:

```java
package com.inforvans.accord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "Accord Control Worker")
@SpringBootApplication(scanBasePackages = "com.inforvans.accord")
public class ControlWorkerApplication {
    public static void main(String[] args) {
        System.setProperty("accord.process-role", "control-worker");
        SpringApplication.run(ControlWorkerApplication.class, args);
    }
}
```

Create this minimal `application.yml` in the API project:

```yaml
spring:
  modulith:
    detection-strategy: explicitly-annotated
```

Create the Worker `application.yml` with the same module strategy and an explicit non-web
process type:

```yaml
spring:
  main:
    web-application-type: none
  modulith:
    detection-strategy: explicitly-annotated
```

The root package and scan base intentionally cover every current and future `com.inforvans.accord.*` control-plane module. The `explicitly-annotated` strategy is the Spring Modulith 1.4.1 built-in equivalent of `ApplicationModuleDetectionStrategy.explicitlyAnnotated()`; it recursively discovers only packages carrying `@ApplicationModule`, so entry-point and adapter packages are not accidentally promoted to modules. The worker must use the same component-scan root and strategy because it loads the same domain modules in a different process role.

Both application classes deliberately live in `com.inforvans.accord`. Placing the Worker root
below that package would change the Modulith source root and make discovery differ from the API.

Create the two `package-info.java` declarations:

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Platform Kernel",
    allowedDependencies = {}
)
package com.inforvans.accord.platformkernel;
```

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Reliability",
    allowedDependencies = {"platformkernel"}
)
package com.inforvans.accord.reliability;
```

- [ ] **Step 4: Wire only the declared module dependencies**

Replace `apps/control-plane/api/build.gradle` with:

```groovy
plugins {
    alias(libs.plugins.spring.boot)
    id 'java'
}

dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':apps:control-plane:modules:reliability')
    implementation enforcedPlatform(libs.spring.boot.bom)
    implementation platform(libs.spring.modulith.bom)
    implementation libs.spring.modulith.starter.core
    implementation libs.spring.boot.web
    implementation libs.spring.boot.actuator
    testImplementation libs.spring.boot.test
    testImplementation libs.spring.modulith.test
    testImplementation libs.archunit.junit
}

tasks.withType(Test).configureEach { useJUnitPlatform() }

ext.controlPlaneBoundary = [
    expectedStartClass: 'com.inforvans.accord.ControlApiApplication',
    webRuntime: 'required'
]
apply from: rootProject.file('tests/architecture/control-plane-runtime-boundaries.gradle')
```

Replace `apps/control-plane/worker/build.gradle` with:

```groovy
plugins {
    alias(libs.plugins.spring.boot)
    id 'java'
}

dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':apps:control-plane:modules:reliability')
    implementation enforcedPlatform(libs.spring.boot.bom)
    implementation platform(libs.spring.modulith.bom)
    implementation libs.spring.modulith.starter.core
    implementation libs.spring.boot.actuator
    testImplementation libs.spring.boot.test
    testImplementation libs.spring.modulith.test
    testImplementation libs.archunit.junit
}

tasks.withType(Test).configureEach { useJUnitPlatform() }

ext.controlPlaneBoundary = [
    expectedStartClass: 'com.inforvans.accord.ControlWorkerApplication',
    webRuntime: 'forbidden'
]
apply from: rootProject.file('tests/architecture/control-plane-runtime-boundaries.gradle')
```

Add the lightweight Modulith annotation API, without a Boot runtime, to
`apps/control-plane/modules/platform-kernel/build.gradle`:

```groovy
dependencies {
    api platform(libs.spring.modulith.bom)
    api libs.spring.modulith.api
    implementation libs.jackson.core
    implementation libs.jcs
    testImplementation enforcedPlatform(libs.junit.bom)
    testImplementation enforcedPlatform(libs.jackson.bom)
    testImplementation enforcedPlatform(libs.slf4j.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
}
```

Replace `apps/control-plane/modules/reliability/build.gradle` with:

```groovy
plugins {
    id 'java-library'
}

dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    api platform(libs.spring.modulith.bom)
    api libs.spring.modulith.api
    testImplementation enforcedPlatform(libs.junit.bom)
    testImplementation enforcedPlatform(libs.jackson.bom)
    testImplementation enforcedPlatform(libs.slf4j.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
}
```

Add exact catalog aliases for `spring-boot-dependencies` and
`spring-modulith-api`:

```toml
[libraries]
spring-boot-bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "spring-boot" }
spring-modulith-api = { module = "org.springframework.modulith:spring-modulith-api" }
```

The deployable applications use the Boot BOM as an enforced platform:
Modulith 1.4.1 requests Boot 3.5.2 transitively, while the approved and plugin-aligned runtime is
Boot 3.5.3. The explicit enforced platform makes that intentional patch alignment visible and
keeps `failOnVersionConflict()` effective for every unrelated conflict. Boot application tests
retain the coherent JUnit line managed and tested by Boot; standalone library tests continue to use
the repository JUnit BOM directly.

Place `NoSecurityServiceCouplingTest.java` under both deployables' test source sets as well as
`tests/architecture/` by adding this source-set declaration to both build files:

```groovy
sourceSets {
    test {
        java.srcDir '../../../tests/architecture'
    }
}
```

Create `control-plane-runtime-boundaries.gradle` as the shared executable-boundary gate applied
by both deployables:

```groovy
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = 'This verification task has no outputs to cache.')
abstract class VerifyControlPlaneRuntimeBoundary extends DefaultTask {
    private static final List<String> FORBIDDEN_CLASS_PREFIXES = [
        'BOOT-INF/classes/com/inforvans/accord/securityservices/',
        'BOOT-INF/classes/com/inforvans/accord/signing/',
        'BOOT-INF/classes/com/inforvans/accord/gitprovider/',
        'BOOT-INF/classes/com/inforvans/accord/gitcontent/',
        'BOOT-INF/classes/com/inforvans/accord/providerconnector/',
        'BOOT-INF/classes/com/inforvans/accord/credentialbroker/',
        'BOOT-INF/classes/com/inforvans/accord/mergecontroller/'
    ]
    private static final List<String> FORBIDDEN_LIBRARY_FRAGMENTS = [
        'jgit',
        'gitlab4j',
        'github-api',
        'git-content',
        'signing-service',
        'provider-connector',
        'credential-broker',
        'merge-controller'
    ]
    private static final List<String> WEB_LIBRARY_PREFIXES = [
        'BOOT-INF/lib/spring-web-',
        'BOOT-INF/lib/spring-webmvc-',
        'BOOT-INF/lib/tomcat-',
        'BOOT-INF/lib/jetty-',
        'BOOT-INF/lib/undertow-'
    ]
    private static final List<String> EMBEDDED_SERVER_LIBRARY_PREFIXES = [
        'BOOT-INF/lib/tomcat-embed-core-',
        'BOOT-INF/lib/jetty-server-',
        'BOOT-INF/lib/undertow-core-'
    ]
    private static final List<String> FORBIDDEN_NESTED_CLASS_PREFIXES = [
        'com/inforvans/accord/securityservices/',
        'com/inforvans/accord/signing/',
        'com/inforvans/accord/gitprovider/',
        'com/inforvans/accord/gitcontent/',
        'com/inforvans/accord/providerconnector/',
        'com/inforvans/accord/credentialbroker/',
        'com/inforvans/accord/mergecontroller/',
        'org/eclipse/jgit/',
        'org/gitlab4j/',
        'org/kohsuke/github/'
    ]

    @Classpath
    abstract ConfigurableFileCollection getRuntimeClasspath()

    @Input
    abstract ListProperty<String> getRuntimeComponents()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getArchiveFile()

    @Input
    abstract Property<String> getExpectedStartClass()

    @Input
    abstract Property<String> getWebRuntimePolicy()

    @TaskAction
    void verifyBoundary() {
        [
            'project|:security-services:signing-service',
            'module|org.eclipse.jgit|org.eclipse.jgit',
            'module|org.gitlab4j|gitlab4j-api',
            'module|org.kohsuke|github-api',
            'module|com.example|git-content-client'
        ].each { probe ->
            if (!VerifyControlPlaneRuntimeBoundary.isForbiddenCoordinate(probe)) {
                throw new GradleException("Forbidden component policy missed probe: ${probe}")
            }
        }
        [
            'BOOT-INF/classes/com/inforvans/accord/signing/service/Signer.class',
            'BOOT-INF/classes/com/inforvans/accord/gitprovider/GitClient.class',
            'BOOT-INF/lib/org.eclipse.jgit-7.0.0.jar',
            'BOOT-INF/lib/gitlab4j-api-6.0.0.jar',
            'BOOT-INF/lib/github-api-1.330.jar',
            'BOOT-INF/lib/git-content-client-1.0.0.jar'
        ].each { probe ->
            if (!VerifyControlPlaneRuntimeBoundary.isForbiddenArchiveEntry(probe)) {
                throw new GradleException("Forbidden archive policy missed probe: ${probe}")
            }
        }
        if (VerifyControlPlaneRuntimeBoundary.hasEmbeddedServer([
                'BOOT-INF/lib/spring-web-6.2.8.jar'
            ]) || !VerifyControlPlaneRuntimeBoundary.hasEmbeddedServer([
                'BOOT-INF/lib/tomcat-embed-core-10.1.42.jar'
            ])) {
            throw new GradleException('Embedded server policy failed its positive/negative probes')
        }
        [
            'com/inforvans/accord/signing/Signer.class',
            'org/eclipse/jgit/api/Git.class'
        ].each { probe ->
            if (!VerifyControlPlaneRuntimeBoundary.isForbiddenNestedClassEntry(probe)) {
                throw new GradleException("Nested archive policy missed probe: ${probe}")
            }
        }

        def forbiddenComponents = runtimeComponents.get().findAll {
            VerifyControlPlaneRuntimeBoundary.isForbiddenCoordinate(it)
        }
        if (!forbiddenComponents.empty) {
            throw new GradleException(
                "Forbidden runtime components: ${forbiddenComponents.join(', ')}")
        }

        new JarFile(archiveFile.get().asFile).withCloseable { jar ->
            def startClass = jar.manifest.mainAttributes.getValue('Start-Class')
            if (startClass != expectedStartClass.get()) {
                throw new GradleException(
                    "Expected Start-Class ${expectedStartClass.get()}, found ${startClass}")
            }

            def entries = []
            def enumeration = jar.entries()
            while (enumeration.hasMoreElements()) {
                entries.add(enumeration.nextElement().name)
            }

            def forbiddenEntries = entries.findAll {
                VerifyControlPlaneRuntimeBoundary.isForbiddenArchiveEntry(it)
            }
            if (!forbiddenEntries.empty) {
                throw new GradleException(
                    "Forbidden executable archive entries: ${forbiddenEntries.join(', ')}")
            }

            def forbiddenNestedEntries = []
            entries.findAll {
                it.startsWith('BOOT-INF/lib/') && it.endsWith('.jar')
            }.each { nestedArchive ->
                new JarInputStream(jar.getInputStream(jar.getJarEntry(nestedArchive)))
                    .withCloseable { nestedJar ->
                        def nestedEntry = nestedJar.nextJarEntry
                        while (nestedEntry != null) {
                            if (VerifyControlPlaneRuntimeBoundary
                                    .isForbiddenNestedClassEntry(nestedEntry.name)) {
                                forbiddenNestedEntries.add(
                                    "${nestedArchive}!/${nestedEntry.name}")
                            }
                            nestedEntry = nestedJar.nextJarEntry
                        }
                    }
            }
            if (!forbiddenNestedEntries.empty) {
                throw new GradleException(
                    "Forbidden nested archive entries: ${forbiddenNestedEntries.join(', ')}")
            }

            def webLibraries = entries.findAll { entry ->
                VerifyControlPlaneRuntimeBoundary.WEB_LIBRARY_PREFIXES.any {
                    entry.startsWith(it)
                }
            }
            if (webRuntimePolicy.get() == 'required' &&
                    !VerifyControlPlaneRuntimeBoundary.hasEmbeddedServer(entries)) {
                throw new GradleException('The control API executable is missing its web runtime')
            }
            if (webRuntimePolicy.get() == 'forbidden' && !webLibraries.empty) {
                throw new GradleException(
                    "The control Worker executable contains web libraries: ${webLibraries.join(', ')}")
            }
        }
    }

    static boolean isForbiddenCoordinate(String coordinate) {
        if (coordinate.startsWith('project|')) {
            return coordinate.substring('project|'.length()).startsWith(':security-services:')
        }
        if (!coordinate.startsWith('module|')) {
            return false
        }

        def parts = coordinate.split('\\|', -1)
        if (parts.length != 3) {
            return false
        }
        def group = parts[1]
        def module = parts[2]
        def normalizedModule = module.toLowerCase(Locale.ROOT).replaceAll('[^a-z0-9]', '')
        group.startsWith('org.eclipse.jgit') ||
            group.startsWith('org.gitlab4j') ||
            (group == 'org.kohsuke' && module == 'github-api') ||
            normalizedModule.contains('gitcontent')
    }

    static boolean isForbiddenArchiveEntry(String entry) {
        def normalized = entry.toLowerCase(Locale.ROOT)
        VerifyControlPlaneRuntimeBoundary.FORBIDDEN_CLASS_PREFIXES.any {
            entry.startsWith(it)
        } ||
            (entry.startsWith('BOOT-INF/lib/') &&
                (VerifyControlPlaneRuntimeBoundary.FORBIDDEN_LIBRARY_FRAGMENTS.any {
                    normalized.contains(it)
                } || normalized.replaceAll('[^a-z0-9]', '').contains('gitcontent')))
    }

    static boolean hasEmbeddedServer(Collection<String> entries) {
        entries.any { entry ->
            VerifyControlPlaneRuntimeBoundary.EMBEDDED_SERVER_LIBRARY_PREFIXES.any {
                entry.startsWith(it)
            }
        }
    }

    static boolean isForbiddenNestedClassEntry(String entry) {
        VerifyControlPlaneRuntimeBoundary.FORBIDDEN_NESTED_CLASS_PREFIXES.any {
            entry.startsWith(it)
        }
    }
}

def boundary = project.extensions.extraProperties.get('controlPlaneBoundary')
def configuredStartClass = boundary.expectedStartClass as String
def configuredWebRuntimePolicy = boundary.webRuntime as String

if (!(configuredWebRuntimePolicy in ['required', 'forbidden'])) {
    throw new GradleException(
        "Unsupported control-plane web runtime policy: ${configuredWebRuntimePolicy}")
}

def runtimeComponentCoordinates = configurations.runtimeClasspath.incoming.artifacts
    .resolvedArtifacts.map { artifacts ->
        artifacts.collect { artifact ->
            def id = artifact.id.componentIdentifier
            if (id instanceof ProjectComponentIdentifier) {
                return "project|${id.projectPath}"
            }
            if (id instanceof ModuleComponentIdentifier) {
                return "module|${id.group}|${id.module}"
            }
            "other|${id.displayName}"
        }.unique().sort()
    }

def bootJarTask = tasks.named('bootJar')
def boundaryTask = tasks.register(
    'verifyControlPlaneRuntimeBoundary', VerifyControlPlaneRuntimeBoundary) {
    group = 'verification'
    description = 'Verifies control-plane process, dependency, and executable archive boundaries.'
    dependsOn bootJarTask
    runtimeClasspath.from(configurations.runtimeClasspath)
    runtimeComponents.set(runtimeComponentCoordinates)
    archiveFile.set(bootJarTask.flatMap { it.archiveFile })
    expectedStartClass.set(configuredStartClass)
    webRuntimePolicy.set(configuredWebRuntimePolicy)
}

tasks.named('check') {
    dependsOn boundaryTask
}
```

The component and archive policy probes make the deny logic non-vacuous. The task resolves the
actual runtime graph, opens the produced Boot JAR, checks its `Start-Class`, scans embedded
classes/libraries and every nested dependency JAR namespace, enforces the API/Worker web-runtime
policy, and is a dependency of standard
`check` in both deployables. Its task action reads only annotated scalar, component-list,
classpath, and archive-file inputs; it never captures a `Project`, `Configuration`, `Task`, or
`TaskProvider`, so the repository's default configuration cache remains enforceable.

Refresh every affected lock and then regenerate verification evidence in a separate invocation:

```bash
./gradlew resolveAndLockAll --write-locks --no-configuration-cache --no-daemon
./gradlew resolveAndLockAll --write-verification-metadata sha256,pgp \
  --no-configuration-cache --no-daemon
```

Expected: both commands succeed. Review every new ignored PGP key; an unavailable key is acceptable
only when the entry retains the exact key-server reason and every affected artifact remains pinned
by SHA-256. `verify-metadata` and `verify-signatures` remain enabled, every trusted key is scoped,
and no artifact lacks checksum or trusted-signature evidence.

- [ ] **Step 5: Verify both application boundaries**

Run:

```bash
./gradlew :apps:control-plane:api:check :apps:control-plane:worker:check \
  --rerun-tasks --dependency-verification=strict
./gradlew :apps:control-plane:api:check :apps:control-plane:worker:check \
  --rerun-tasks --dependency-verification=strict
```

The two `check` lifecycles run the shared ArchUnit suite and
`verifyControlPlaneRuntimeBoundary`. That gate opens both Boot JAR manifests and entries with a
structured ZIP reader. It asserts that the API starts
`com.inforvans.accord.ControlApiApplication`, the Worker starts
`com.inforvans.accord.ControlWorkerApplication`, neither JAR contains `securityservices` or
`gitcontent`/`git-content`, including inside a renamed nested library; the API embeds a supported
Tomcat, Jetty, or Undertow server core rather than only `spring-web`, and the Worker embeds no
Spring Web, Tomcat, Undertow, or Jetty library. It also resolves both `runtimeClasspath` graphs and fails on any
`security-services`, JGit, GitLab4J, GitHub, or `gitcontent` component.

Expected: both architecture suites pass; exactly two different executable JARs are produced; the
Worker stays non-web; and all forbidden namespace probes, package rules, archive entries, and
dependency-graph checks pass inside the standard Gradle lifecycle. The first invocation stores a
configuration-cache entry with zero problems, and the identical second invocation reports
`Configuration cache entry reused`; disabling the cache is not an acceptable substitute.

- [ ] **Step 6: Commit the executable boundaries**

```bash
git add apps/control-plane tests/architecture gradle/libs.versions.toml \
  gradle/verification-metadata.xml tests/*/gradle.lockfile \
  docs/superpowers/plans/2026-07-24-accord-platform-foundation-plan.md
git commit -m "feat: enforce control-plane module boundaries"
```

### Task 6: Create The PostgreSQL, Flyway, And jOOQ Baseline

**Files:**
- Create: `database/control-plane/build.gradle`
- Create: `database/control-plane/bootstrap/00-pre-flyway-roles.sql`
- Create: `database/control-plane/migrations/V001__platform_command_state.sql`
- Create: `database/control-plane/src/testFixtures/java/com/inforvans/accord/database/ControlPlaneTestRoles.java`
- Create: `database/control-plane/src/test/java/com/inforvans/accord/database/PlatformMigrationTest.java`
- Create: `database/control-plane/src/test/java/com/inforvans/accord/database/TestcontainersConfigurationTest.java`
- Create: `tests/integration/src/test/java/com/inforvans/accord/integration/TestcontainersConfigurationTest.java`
- Create: `config/testcontainers/testcontainers.properties`
- Create: `scripts/generate-control-plane-jooq.ps1`
- Create: `tests/bootstrap/verify-control-plane-jooq-generator.ps1`
- Modify: `.gitignore`
- Modify: `build.gradle`
- Modify: `settings.gradle`
- Modify: `gradle/libs.versions.toml`
- Modify: `tests/bootstrap/verify-workspace.ps1`
- Modify: `docs/superpowers/plans/2026-07-24-accord-platform-foundation-plan.md`
- Generate: `database/control-plane/gradle.lockfile`
- Generate: `database/control-plane/buildscript-gradle.lockfile`
- Modify: `gradle/verification-metadata.xml`
- Generate: `database/control-plane/build/generated-src/jooq/**`

- [ ] **Step 1: Register a test-capable database project and write the failing migration contract test**

Add `include(":database:control-plane")` to `settings.gradle`, add the same project and its lock to
`tests/bootstrap/verify-workspace.ps1`, and add this catalog alias before creating the test-capable
database build:

```toml
jooq-runtime = { module = "org.jooq:jooq", version.ref = "jooq" }
```

Create `database/control-plane/build.gradle` before invoking its Gradle target:

```groovy
plugins {
    id 'java-library'
    id 'java-test-fixtures'
}

dependencies {
    api libs.jooq.runtime
    runtimeOnly libs.postgresql
    testFixturesRuntimeOnly libs.postgresql
    testImplementation enforcedPlatform(libs.junit.bom)
    testImplementation enforcedPlatform(libs.jackson.bom)
    testImplementation enforcedPlatform(libs.slf4j.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
    testRuntimeOnly libs.slf4j.simple
}

sourceSets {
    testFixtures {
        resources {
            srcDir 'bootstrap'
            include '00-pre-flyway-roles.sql'
        }
    }
}

tasks.withType(Test).configureEach { useJUnitPlatform() }
```

Before the intended RED test, run lock and verification-metadata generation with configuration
cache disabled. This is supply-chain bootstrap, not a behavioral GREEN: the subsequent test must
reach PostgreSQL and fail on the missing role/schema behavior rather than fail dependency
verification. Commit neither an unlocked configuration nor an artifact without SHA-256 or scoped
trusted-signature evidence.

The root Java test convention must map process environment `DOCKER_API_VERSION` to the docker-java
JVM system property `api.version`, defaulting to `1.40`. Testcontainers 1.21.3 otherwise falls back
to API 1.32, which modern Docker Engine 29 rejects before any container starts. Keep the override
centrally applied to every `Test` task so all current and future Testcontainers suites use the same
declared compatibility floor.

Pin Testcontainers 1.21.3's helper images once through
`config/testcontainers/testcontainers.properties` using the reviewed repository digests, without
disabling Ryuk:

```properties
ryuk.container.image=testcontainers/ryuk:0.12.0@sha256:dd3f023a6ed7015b3f95a49ccd65a2daf0c56e681422c12952b19a810dfa6298
tinyimage.container.image=alpine:3.17@sha256:8fc3dacfb6d69da8d44e42390de777e48577085db99aa4e4af35f483eb08b989
```

The root Java convention adds that canonical directory to every Java project's test resources and
sets every `processTestResources` task to `DuplicatesStrategy.FAIL`; a module-local file therefore
fails instead of shadowing or duplicating the canonical resource. Effective-configuration tests in
both the database project and the independent `tests:integration` project assert both pins and
reject `TESTCONTAINERS_RYUK_DISABLED=true`. `verify-workspace.ps1` compares the canonical file to
these two exact ordered lines, verifies the global Gradle wiring, and rejects any other source-level
`testcontainers.properties`. Test execution may create `.jqwik-database`; it is generated cache
state and remains ignored rather than becoming a repository artifact.

Create `database/control-plane/bootstrap/00-pre-flyway-roles.sql` as the administrator-owned prerequisite. It is the only place that creates database roles; Flyway remains `NOCREATEROLE`, and no default table privilege is installed:

```sql
\set ON_ERROR_STOP on

BEGIN;

DO $roles$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_migrator') THEN
    CREATE ROLE accord_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_api') THEN
    CREATE ROLE accord_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_worker') THEN
    CREATE ROLE accord_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_migrator_login') THEN
    CREATE ROLE accord_migrator_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_api_login') THEN
    CREATE ROLE accord_api_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_worker_login') THEN
    CREATE ROLE accord_worker_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD NULL;
  END IF;
END $roles$;

ALTER ROLE accord_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_migrator_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_api_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE accord_worker_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

DO $memberships$
DECLARE edge record;
BEGIN
  FOR edge IN
    SELECT granted.rolname AS granted_name, member.rolname AS member_name,
      grantor.rolname AS grantor_name
    FROM pg_catalog.pg_auth_members membership
    JOIN pg_catalog.pg_roles granted ON granted.oid = membership.roleid
    JOIN pg_catalog.pg_roles member ON member.oid = membership.member
    JOIN pg_catalog.pg_roles grantor ON grantor.oid = membership.grantor
    WHERE member.rolname IN (
      'accord_migrator', 'accord_api', 'accord_worker',
      'accord_migrator_login', 'accord_api_login', 'accord_worker_login')
      OR granted.rolname IN (
        'accord_migrator', 'accord_api', 'accord_worker',
        'accord_migrator_login', 'accord_api_login', 'accord_worker_login')
  LOOP
    EXECUTE pg_catalog.format(
      'REVOKE %I FROM %I GRANTED BY %I CASCADE',
      edge.granted_name, edge.member_name, edge.grantor_name);
  END LOOP;
END $memberships$;

GRANT accord_migrator TO accord_migrator_login WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
GRANT accord_api TO accord_api_login WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
GRANT accord_worker TO accord_worker_login WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

DO $owners$
BEGIN
  EXECUTE pg_catalog.format(
    'ALTER DATABASE %I OWNER TO accord_migrator', pg_catalog.current_database());
  ALTER SCHEMA public OWNER TO accord_migrator;
END $owners$;

DO $database$
DECLARE grantee_name text;
BEGIN
  EXECUTE pg_catalog.format(
    'REVOKE ALL ON DATABASE %I FROM PUBLIC', pg_catalog.current_database());
  FOR grantee_name IN
    SELECT DISTINCT grantee.rolname
    FROM pg_catalog.pg_database database
    CROSS JOIN LATERAL pg_catalog.aclexplode(
      COALESCE(database.datacl, pg_catalog.acldefault('d', database.datdba))) acl
    JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
    WHERE database.datname = pg_catalog.current_database()
      AND acl.grantee <> database.datdba
  LOOP
    EXECUTE pg_catalog.format(
      'REVOKE ALL PRIVILEGES ON DATABASE %I FROM %I CASCADE',
      pg_catalog.current_database(), grantee_name);
  END LOOP;
  EXECUTE pg_catalog.format(
    'GRANT CONNECT ON DATABASE %I TO accord_migrator_login, accord_api_login, accord_worker_login',
    pg_catalog.current_database());
END $database$;
DO $schema_acl$
DECLARE grantee record;
BEGIN
  FOR grantee IN
    SELECT acl.grantee AS grantee_oid, roles.rolname AS grantee_name
    FROM pg_catalog.pg_namespace namespace
    CROSS JOIN LATERAL pg_catalog.aclexplode(
      COALESCE(namespace.nspacl, pg_catalog.acldefault('n', namespace.nspowner))) acl
    LEFT JOIN pg_catalog.pg_roles roles ON roles.oid = acl.grantee
    WHERE namespace.nspname = 'public' AND acl.grantee <> namespace.nspowner
    GROUP BY acl.grantee, roles.rolname
  LOOP
    IF grantee.grantee_oid = 0 THEN
      REVOKE ALL PRIVILEGES ON SCHEMA public FROM PUBLIC CASCADE;
    ELSE
      EXECUTE pg_catalog.format(
        'REVOKE ALL PRIVILEGES ON SCHEMA public FROM %I CASCADE', grantee.grantee_name);
    END IF;
  END LOOP;
END $schema_acl$;

GRANT USAGE ON SCHEMA public TO accord_api, accord_worker;

DO $default_table_acl$
DECLARE grantee record;
BEGIN
  FOR grantee IN
    SELECT defaults.defaclnamespace AS namespace_oid,
      acl.grantee AS grantee_oid, roles.rolname AS grantee_name
    FROM pg_catalog.pg_default_acl defaults
    CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) acl
    JOIN pg_catalog.pg_roles owner ON owner.oid = defaults.defaclrole
    LEFT JOIN pg_catalog.pg_namespace namespace ON namespace.oid = defaults.defaclnamespace
    LEFT JOIN pg_catalog.pg_roles roles ON roles.oid = acl.grantee
    WHERE owner.rolname = 'accord_migrator'
      AND (defaults.defaclnamespace = 0 OR namespace.nspname = 'public')
      AND defaults.defaclobjtype = 'r' AND acl.grantee <> defaults.defaclrole
    GROUP BY defaults.defaclnamespace, acl.grantee, roles.rolname
  LOOP
    IF grantee.namespace_oid = 0 THEN
      IF grantee.grantee_oid = 0 THEN
        ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator
          REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC CASCADE;
      ELSE
        EXECUTE pg_catalog.format(
          'ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator REVOKE ALL PRIVILEGES ON TABLES FROM %I CASCADE',
          grantee.grantee_name);
      END IF;
    ELSIF grantee.grantee_oid = 0 THEN
      ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator IN SCHEMA public
        REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC CASCADE;
    ELSE
      EXECUTE pg_catalog.format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator IN SCHEMA public REVOKE ALL PRIVILEGES ON TABLES FROM %I CASCADE',
        grantee.grantee_name);
    END IF;
  END LOOP;
END $default_table_acl$;

COMMIT;
```

The three uncredentialed roles are the only control-plane object/privilege principals. Secret
provisioning assigns passwords only to the three `_login` roles. Those LOGIN roles have no direct
schema or table grants, cannot inherit privileges, cannot set a sibling role, and receive exactly one
membership with `SET TRUE`, `INHERIT FALSE`, and `ADMIN FALSE`.
Before installing those three edges, the bootstrap dynamically revokes every membership where
either the member or granted role is one of the six principals. It makes `accord_migrator` the exact
owner of both the current database and `public` schema before ACL reconciliation, so migration
authority is implicit owner authority rather than a redundant explicit ACL row. It then revokes
every non-owner database ACL before granting only `CONNECT` to the LOGIN roles; every non-owner
`public` schema ACL before granting only `USAGE` to `accord_api`/`accord_worker`; and every non-owner
global or explicit-`public` default table ACL for `accord_migrator`. The entire bootstrap is enclosed
in one explicit transaction, so a late failure rolls back role attributes, memberships, ownership,
and ACL changes together. Re-running it therefore converges direct, reverse, transitive,
grant-option, owner, and previously unknown hostile state rather than merely adding approved grants.

Create `ControlPlaneTestRoles.java` in the test-fixture source set. It executes the same role attributes and memberships immediately after a Testcontainer starts, including the `accord_audit_test` role needed by later audit tests, but it never grants table default privileges:

```java
package com.inforvans.accord.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

public final class ControlPlaneTestRoles {
    public static final String MIGRATOR_LOGIN = "accord_migrator_login";
    public static final String MIGRATOR_PASSWORD = "migrator-test";
    public static final String API_LOGIN = "accord_api_login";
    public static final String API_PASSWORD = "api-test";
    public static final String WORKER_LOGIN = "accord_worker_login";
    public static final String WORKER_PASSWORD = "worker-test";

    private static final Pattern SESSION_ROLE = Pattern.compile("^accord_[a-z_]+$");

    private ControlPlaneTestRoles() {}

    public static String jdbcUrlWithRole(String jdbcUrl, String role) {
        if (!SESSION_ROLE.matcher(role).matches()) {
            throw new IllegalArgumentException("invalid test session role");
        }
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "options=-c%20role%3D" + role;
    }

    public static void bootstrap(String jdbcUrl, String user, String password) {
        try (InputStream resource = ControlPlaneTestRoles.class
                 .getResourceAsStream("/00-pre-flyway-roles.sql");
             Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement()) {
            if (resource == null) {
                throw new IllegalStateException(
                    "packaged control-plane role bootstrap is missing");
            }
            String bootstrap = new String(
                resource.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\\set ON_ERROR_STOP on", "");
            statement.execute(bootstrap);
            statement.execute("""
                DO $roles$
                BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_audit_test') THEN
                    CREATE ROLE accord_audit_test LOGIN PASSWORD 'audit-test'
                      NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
                      NOREPLICATION NOBYPASSRLS;
                  END IF;
                END $roles$;
                """);
            statement.execute("ALTER ROLE " + MIGRATOR_LOGIN + " PASSWORD '" + MIGRATOR_PASSWORD + "'");
            statement.execute("ALTER ROLE " + API_LOGIN + " PASSWORD '" + API_PASSWORD + "'");
            statement.execute("ALTER ROLE " + WORKER_LOGIN + " PASSWORD '" + WORKER_PASSWORD + "'");

            String database = "\"" + connection.getCatalog().replace("\"", "\"\"") + "\"";
            statement.execute("GRANT CONNECT ON DATABASE " + database
                + " TO accord_audit_test");
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("cannot bootstrap control-plane test roles", error);
        }
    }
}
```

`bootstrap/00-pre-flyway-roles.sql` remains the single source of truth. Gradle packages that exact file into the published test-fixture JAR, and `ControlPlaneTestRoles` resolves it through its own class loader. Consequently database, Reliability, and API tests behave identically whether Gradle starts them from the database project, another consuming project, the repository root, or an IDE; no test relies on `user.dir` or a repository-relative path.

Create `PlatformMigrationTest.java`:

```java
package com.inforvans.accord.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class PlatformMigrationTest {
    @Test
    void migrationCreatesTenantScopedAggregateAndIdempotencyState() throws Exception {
        DockerImageName postgresImage = DockerImageName.parse(
            "postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302")
            .asCompatibleSubstituteFor("postgres");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(postgresImage)) {
            postgres.start();
            ControlPlaneTestRoles.bootstrap(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

            Flyway.configure()
                .dataSource(
                    postgres.getJdbcUrl(),
                    ControlPlaneTestRoles.MIGRATOR_LOGIN,
                    ControlPlaneTestRoles.MIGRATOR_PASSWORD)
                .initSql("SET ROLE accord_migrator")
                .locations("classpath:db/migration")
                .load()
                .migrate();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                assertScalar(statement, "SELECT count(*) FROM aggregate_head", 0);
                assertScalar(statement, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='public' AND table_name='idempotency_result'
                      AND column_name IN (
                        'tenant_id','actor_id','request_fingerprint','claim_generation',
                        'claim_token','lease_until','response_body'
                      )
                    """, 7);
                assertScalar(statement, """
                    SELECT count(*) FROM pg_class c
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public'
                      AND c.relname IN ('aggregate_head','idempotency_result')
                      AND c.relrowsecurity AND c.relforcerowsecurity
                    """, 2);
                assertScalar(statement, """
                    SELECT count(*) FROM pg_policy p
                    JOIN pg_class c ON c.oid=p.polrelid
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public'
                      AND c.relname IN ('aggregate_head','idempotency_result')
                      AND p.polname='tenant_isolation' AND p.polcmd='*'
                      AND p.polroles=ARRAY[0::oid]
                      AND pg_get_expr(p.polqual,p.polrelid)
                          ='(tenant_id = accord_security.current_tenant_id())'
                      AND pg_get_expr(p.polwithcheck,p.polrelid)
                          ='(tenant_id = accord_security.current_tenant_id())'
                    """, 2);
                assertScalar(statement, """
                    WITH expected(grantee,table_name,privilege_type) AS (VALUES
                      ('accord_api','aggregate_head','SELECT'),
                      ('accord_api','aggregate_head','INSERT'),
                      ('accord_api','aggregate_head','UPDATE'),
                      ('accord_api','idempotency_result','SELECT'),
                      ('accord_api','idempotency_result','INSERT'),
                      ('accord_api','idempotency_result','UPDATE'),
                      ('accord_worker','aggregate_head','SELECT'),
                      ('accord_worker','aggregate_head','INSERT'),
                      ('accord_worker','aggregate_head','UPDATE'),
                      ('accord_worker','idempotency_result','SELECT'),
                      ('accord_worker','idempotency_result','INSERT'),
                      ('accord_worker','idempotency_result','UPDATE'),
                      ('accord_worker','idempotency_result','DELETE')
                    ), actual AS (
                      SELECT grantee::text,table_name::text,privilege_type::text
                      FROM information_schema.role_table_grants
                      WHERE table_schema='public'
                        AND table_name IN ('aggregate_head','idempotency_result')
                        AND grantee IN ('accord_api','accord_worker')
                    ), differences AS (
                      (SELECT * FROM expected EXCEPT SELECT * FROM actual)
                      UNION ALL
                      (SELECT * FROM actual EXCEPT SELECT * FROM expected)
                    ) SELECT count(*) FROM differences
                    """, 0);
                assertScalar(statement, """
                    SELECT count(*) FROM pg_class c
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public'
                      AND c.relname IN ('aggregate_head','idempotency_result')
                      AND c.relowner=(SELECT oid FROM pg_roles WHERE rolname='accord_migrator')
                    """, 2);
                assertScalar(statement, """
                    SELECT count(*) FROM pg_roles
                    WHERE rolname IN ('accord_migrator','accord_api','accord_worker')
                      AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb
                      AND NOT rolcreaterole AND NOT rolreplication
                      AND NOT rolinherit AND NOT rolbypassrls
                    """, 3);
                assertScalar(statement, """
                    SELECT count(*) FROM pg_roles
                    WHERE rolname IN (
                      'accord_migrator_login','accord_api_login','accord_worker_login'
                    )
                      AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb
                      AND NOT rolcreaterole AND NOT rolreplication
                      AND NOT rolinherit AND NOT rolbypassrls
                    """, 3);
                assertScalar(statement, """
                    SELECT count(*) FROM pg_auth_members am
                    JOIN pg_roles granted ON granted.oid=am.roleid
                    JOIN pg_roles member ON member.oid=am.member
                    WHERE (member.rolname,granted.rolname) IN (
                      ('accord_migrator_login','accord_migrator'),
                      ('accord_api_login','accord_api'),
                      ('accord_worker_login','accord_worker')
                    )
                      AND NOT am.admin_option AND NOT am.inherit_option AND am.set_option
                    """, 3);
                assertScalar(statement, """
                    SELECT count(*) FROM pg_auth_members am
                    JOIN pg_roles member ON member.oid=am.member
                    WHERE member.rolname IN (
                      'accord_migrator_login','accord_api_login','accord_worker_login'
                    )
                    """, 3);
            }

            List<SessionCase> sessions = List.of(
                new SessionCase(
                    ControlPlaneTestRoles.MIGRATOR_LOGIN,
                    ControlPlaneTestRoles.MIGRATOR_PASSWORD,
                    "accord_migrator",
                    "accord_api"),
                new SessionCase(
                    ControlPlaneTestRoles.API_LOGIN,
                    ControlPlaneTestRoles.API_PASSWORD,
                    "accord_api",
                    "accord_worker"),
                new SessionCase(
                    ControlPlaneTestRoles.WORKER_LOGIN,
                    ControlPlaneTestRoles.WORKER_PASSWORD,
                    "accord_worker",
                    "accord_api")
            );
            for (SessionCase session : sessions) {
                assertSessionIsolation(postgres.getJdbcUrl(), session);
            }
        }
    }

    private static void assertSessionIsolation(String jdbcUrl, SessionCase session)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, session.login(), session.password());
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT session_user,current_user")) {
                assertTrue(rows.next());
                assertEquals(session.login(), rows.getString(1));
                assertEquals(session.login(), rows.getString(2));
            }
            SQLException denied = assertThrows(
                SQLException.class,
                () -> statement.executeQuery("SELECT count(*) FROM public.aggregate_head"));
            assertEquals("42501", denied.getSQLState());

            statement.execute("SET ROLE " + session.privilegeRole());
            try (ResultSet rows = statement.executeQuery("SELECT session_user,current_user")) {
                assertTrue(rows.next());
                assertEquals(session.login(), rows.getString(1));
                assertEquals(session.privilegeRole(), rows.getString(2));
            }
            SQLException siblingDenied = assertThrows(
                SQLException.class,
                () -> statement.execute("SET ROLE " + session.siblingRole()));
            assertEquals("42501", siblingDenied.getSQLState());
        }
    }

    private static void assertScalar(Statement statement, String sql, int expected)
            throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            assertEquals(expected, rows.getInt(1));
        }
    }

    private record SessionCase(
        String login,
        String password,
        String privilegeRole,
        String siblingRole
    ) {}
}
```

Split the class into independently named tests over one PostgreSQL 17.5 container: role/default
privileges, table/constraint/owner contract, function ACL/search-path contract, exact RLS/grant
catalog contract, and behavioral tenant isolation. The behavioral test must use runtime and owner
sessions to prove all of the following against real rows: missing tenant context returns no rows
and rejects writes; tenant A cannot select/update/delete tenant B; a wrong-tenant insert and a
tenant-id move both fail with RLS SQLSTATE `42501`; `accord_migrator`, although table owner, remains
constrained by `FORCE ROW LEVEL SECURITY`; malformed tenant context fails rather than broadening
access; and `set_config('app.tenant_id', ..., true)` disappears after commit and rollback. Assert
that PUBLIC has neither database CONNECT/TEMPORARY nor schema privileges, both security functions
are `SECURITY INVOKER`, owned by `accord_migrator`, executable only by their declared roles, and
have exactly `search_path=pg_catalog, pg_temp`. Also prove inserting stored aggregate version zero
fails while version one succeeds.

- [ ] **Step 2: Run the test and verify the migration is absent**

Run: `./gradlew :database:control-plane:test --tests '*PlatformMigrationTest'`

Expected: FAIL from PostgreSQL with `relation "aggregate_head" does not exist`; the registered Gradle project and Testcontainers test both start successfully, proving the red result reaches the database contract.

- [ ] **Step 3: Add production Flyway and jOOQ configuration plus the exact schema**

Add these entries to `gradle/libs.versions.toml`:

```toml
[plugins]
flyway = { id = "org.flywaydb.flyway", version.ref = "flyway" }
jooq-codegen = { id = "org.jooq.jooq-codegen-gradle", version.ref = "jooq" }
```

The existing `flyway = "11.8.2"` and `jooq = "3.19.24"` version keys remain the single version
source; do not add plugin-only duplicates.

Replace the test-capable `database/control-plane/build.gradle` with:

```groovy
buildscript {
    def dependencyMirror = System.getenv('ACCORD_MAVEN_MIRROR_URL')
    if (System.getenv('CI') == 'true' && !dependencyMirror) {
        throw new GradleException('ACCORD_MAVEN_MIRROR_URL is required in CI')
    }
    repositories {
        if (dependencyMirror) { maven { url = uri(dependencyMirror) } }
        else { mavenCentral() }
    }
    dependencies {
        classpath "org.flywaydb:flyway-database-postgresql:${libs.versions.flyway.get()}"
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.flyway)
    alias(libs.plugins.jooq.codegen)
    id 'java-library'
    id 'java-test-fixtures'
}

def databaseUrl = providers.environmentVariable('ACCORD_DB_URL')
    .orElse('jdbc:postgresql://localhost:5432/accord').get()
def separator = databaseUrl.contains('?') ? '&' : '?'
def migratorSessionUrl = "${databaseUrl}${separator}options=-c%20role%3Daccord_migrator"

dependencies {
    api libs.jooq.runtime
    runtimeOnly libs.postgresql
    jooqCodegen libs.postgresql
    testFixturesRuntimeOnly libs.postgresql
    testImplementation enforcedPlatform(libs.junit.bom)
    testImplementation enforcedPlatform(libs.jackson.bom)
    testImplementation enforcedPlatform(libs.slf4j.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
    testRuntimeOnly libs.slf4j.simple
}

flyway {
    url = databaseUrl
    user = providers.environmentVariable('ACCORD_DB_MIGRATOR_USER').orElse('accord_migrator_login').get()
    password = providers.environmentVariable('ACCORD_DB_MIGRATOR_PASSWORD').orElse('local-migrator-only').get()
    initSql = 'SET ROLE accord_migrator'
    locations = ["filesystem:${projectDir}/migrations"]
    cleanDisabled = true
}

jooq {
    configuration {
        jdbc {
            driver = 'org.postgresql.Driver'
            url = migratorSessionUrl
            user = providers.environmentVariable('ACCORD_DB_MIGRATOR_USER').orElse('accord_migrator_login').get()
            password = providers.environmentVariable('ACCORD_DB_MIGRATOR_PASSWORD').orElse('local-migrator-only').get()
        }
        generator {
            database {
                name = 'org.jooq.meta.postgres.PostgresDatabase'
                inputSchema = 'public'
                includes = 'aggregate_head|idempotency_result|domain_event|outbox_event|inbox_message|external_call_intent'
            }
            target {
                packageName = 'com.inforvans.accord.database.jooq'
                directory = 'build/generated-src/jooq/main'
            }
        }
    }
}

sourceSets.main.java.srcDir 'build/generated-src/jooq/main'
tasks.named('processTestFixturesResources') {
    from('bootstrap') { include '00-pre-flyway-roles.sql' }
    from('migrations') {
        include 'V*.sql'
        into 'db/migration'
    }
}
tasks.withType(Test).configureEach { useJUnitPlatform() }
tasks.named('flywayMigrate') {
    notCompatibleWithConfigurationCache('Flyway uses live database credentials.')
}
tasks.named('jooqCodegen') {
    dependsOn tasks.named('flywayMigrate')
    notCompatibleWithConfigurationCache('jOOQ introspects a live migrated database.')
}
tasks.register('verifyGeneratedJooq') {
    dependsOn tasks.named('jooqCodegen')
    notCompatibleWithConfigurationCache('Verifies live-database code generation output.')
    doLast {
        [
            'tables/AggregateHead.java',
            'tables/IdempotencyResult.java',
            'tables/records/AggregateHeadRecord.java',
            'tables/records/IdempotencyResultRecord.java'
        ].each { relativePath ->
            def generated = layout.buildDirectory.file(
                "generated-src/jooq/main/com/inforvans/accord/database/jooq/${relativePath}"
            ).get().asFile
            if (!generated.isFile()) {
                throw new GradleException("Missing generated jOOQ source: ${relativePath}")
            }
        }
    }
}
```

The Flyway PostgreSQL database module is an explicit, mirror-aware buildscript classpath dependency
because Flyway resolves database support while applying the plugin. Its exact graph is locked in
`database/control-plane/buildscript-gradle.lockfile`; the project runtime graph remains separately
locked in `database/control-plane/gradle.lockfile`.

Create `V001__platform_command_state.sql`:

```sql
SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE aggregate_head (
    tenant_id uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version >= 1),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id, aggregate_type, aggregate_id)
);

CREATE TABLE idempotency_result (
    tenant_id uuid NOT NULL,
    actor_id varchar(255) NOT NULL,
    route_key varchar(128) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint char(71) NOT NULL CHECK (request_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    state varchar(16) NOT NULL CHECK (state IN ('STARTED', 'COMPLETED')),
    claim_owner varchar(255),
    claim_generation bigint NOT NULL CHECK (claim_generation >= 1),
    claim_token uuid,
    lease_until timestamptz,
    response_status integer CHECK (response_status BETWEEN 100 AND 599),
    response_headers jsonb,
    response_body text,
    aggregate_type varchar(64),
    aggregate_id uuid,
    aggregate_version bigint CHECK (aggregate_version >= 1),
    started_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    completed_at timestamptz,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, actor_id, route_key, idempotency_key),
    CHECK (response_headers IS NULL OR pg_column_size(response_headers) <= 65536),
    CHECK (response_body IS NULL OR octet_length(response_body) <= 1048576),
    CHECK (
      (state = 'STARTED' AND claim_owner IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL
       AND response_status IS NULL AND completed_at IS NULL)
      OR
      (state = 'COMPLETED' AND claim_owner IS NULL AND claim_token IS NULL AND lease_until IS NULL
       AND response_status IS NOT NULL AND response_headers IS NOT NULL
       AND response_body IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idempotency_result_expiry_idx ON idempotency_result (expires_at);

CREATE SCHEMA accord_security;
REVOKE ALL ON SCHEMA accord_security FROM PUBLIC;

CREATE FUNCTION accord_security.current_tenant_id() RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
RETURN NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::pg_catalog.uuid;
REVOKE ALL ON FUNCTION accord_security.current_tenant_id() FROM PUBLIC;
GRANT USAGE ON SCHEMA accord_security TO accord_api, accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.current_tenant_id() TO accord_api, accord_worker;

CREATE FUNCTION accord_security.enforce_tenant_table(target regclass) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $policy$
DECLARE target_schema text; target_name text;
BEGIN
  SELECT n.nspname, c.relname INTO target_schema, target_name
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE c.oid=target AND c.relkind IN ('r','p');
  IF target_schema IS DISTINCT FROM 'public' OR target_name IS NULL THEN
    RAISE EXCEPTION 'tenant RLS target must be a public table: %', target;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_attribute
    WHERE attrelid=target AND attname='tenant_id' AND NOT attisdropped AND atttypid='uuid'::regtype
  ) THEN
    RAISE EXCEPTION 'tenant RLS target lacks uuid tenant_id: %', target;
  END IF;
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
  IF NOT EXISTS (SELECT 1 FROM pg_policy WHERE polrelid=target AND polname='tenant_isolation') THEN
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON %s FOR ALL TO PUBLIC USING (tenant_id = accord_security.current_tenant_id()) WITH CHECK (tenant_id = accord_security.current_tenant_id())',
      target
    );
  END IF;
  IF (SELECT pg_catalog.count(*) FROM pg_catalog.pg_policy p WHERE p.polrelid=target) <> 1
     OR NOT EXISTS (
    SELECT 1 FROM pg_policy p
    WHERE p.polrelid=target AND p.polname='tenant_isolation' AND p.polpermissive
      AND p.polcmd='*' AND p.polroles=ARRAY[0::oid]
      AND pg_get_expr(p.polqual,p.polrelid)='(tenant_id = accord_security.current_tenant_id())'
      AND pg_get_expr(p.polwithcheck,p.polrelid)='(tenant_id = accord_security.current_tenant_id())'
  ) THEN
    RAISE EXCEPTION 'tenant RLS policy set is non-standard on %', target;
  END IF;
END $policy$;
REVOKE ALL ON FUNCTION accord_security.enforce_tenant_table(regclass) FROM PUBLIC, accord_api, accord_worker;
GRANT EXECUTE ON FUNCTION accord_security.enforce_tenant_table(regclass) TO accord_migrator;

SELECT accord_security.enforce_tenant_table('public.aggregate_head'::regclass);
SELECT accord_security.enforce_tenant_table('public.idempotency_result'::regclass);

REVOKE ALL ON aggregate_head, idempotency_result FROM PUBLIC, accord_api, accord_worker;
GRANT SELECT, INSERT, UPDATE ON aggregate_head, idempotency_result TO accord_api, accord_worker;
GRANT DELETE ON idempotency_result TO accord_worker;
```

The two individual enforcement calls are in V001 itself and precede every runtime table grant.
Each target must have exactly one total policy: permissive `tenant_isolation`, `FOR ALL TO PUBLIC`,
with the exact `USING` and `WITH CHECK` expressions; an additional permissive or restrictive policy
causes enforcement to fail closed. `PlatformMigrationTest` compares complete catalog sets in both
directions for columns and defaults, normalized constraint definitions, indexes including validity,
all non-owner table ACLs, database/default ACLs, policies, and role memberships. It also proves the
fingerprint, generation, lifecycle/state/status, aggregate-version, header-size, and body-size checks
with focused invalid inserts, plus direct and transitive hostile `SET ROLE` denial. The exact role
topology comparison uses `EXCEPT ALL` in both directions so duplicate grantor-specific membership
rows cannot collapse under set semantics. Hostile reruns assign the database and `public` schema to
workload identities, then prove exact migrator ownership and denied runtime DDL after convergence.
A packaged-bootstrap psql test injects an undefined-relation failure immediately before `COMMIT`
and proves all earlier role, membership, owner, and ACL state rolls back. Hostile test cleanup keeps
the primary test/bootstrap exception and attaches every cleanup error as suppressed evidence.

- [ ] **Step 4: Run the migration test**

Run:

```powershell
./gradlew :tests:integration:test --tests '*TestcontainersConfigurationTest'
./gradlew :database:control-plane:test --tests '*PlatformMigrationTest' --tests '*TestcontainersConfigurationTest'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/bootstrap/verify-control-plane-jooq-generator.ps1
```

Expected: the independent integration project resolves both canonical helper-image pins,
Testcontainers starts PostgreSQL 17.5 using the same digest-pinned Ryuk helper, Flyway applies
version `001`, both tenant-scoped tables are present, atomic hostile-state tests pass, and the
generator failure injection removes its simulated created container without masking the primary
failure.

- [ ] **Step 5: Generate jOOQ sources from the migrated local database**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/generate-control-plane-jooq.ps1 -Port 55532
```

Create `scripts/generate-control-plane-jooq.ps1` with a parameterized host port defaulting to
`55532` and the exact image reference
`postgres:17.5@sha256:aadf2c0696f5ef357aa7a68da995137f0cf17bad0bf6e1f17de06ae5c769b302`.
It must reject an occupied port, assign a unique
`accord-control-plane-jooq-<pid>-<guid>` name before creation, capture the exact returned container
ID, bound
`pg_isready` to 45 one-second attempts, assert `SHOW server_version_num` equals `170005`, copy the
bootstrap into the container and invoke `psql -v ON_ERROR_STOP=1 -f`, set only process-local
`ACCORD_DB_*` variables, invoke the wrapper separately for `flywayMigrate`, `jooqCodegen`,
`verifyGeneratedJooq`, and `compileJava` with strict verification and no configuration cache, and
force each requested task to rerun so both passes regenerate, then check every exit code. It
preserves the primary `ErrorRecord`; process-environment restoration and exact-name container
lookup/removal run independently in guarded cleanup, even when `docker run` exits nonzero or emits
no valid ID, and cleanup failures are reported as secondary details without replacing the primary
failure. `tests/bootstrap/verify-control-plane-jooq-generator.ps1` injects that create-then-start
failure with a Docker shim and proves the orphan is removed by its preassigned name.

Expected: the dedicated pinned PostgreSQL 17.5 container becomes healthy; Flyway connects as `accord_migrator_login`, activates `accord_migrator`, and reports schema version `001`; jOOQ uses the same session-role startup option and generates `AggregateHead` and `IdempotencyResult` tables and records under `database/control-plane/build/generated-src/jooq/main`; compilation passes and the disposable container is absent afterward. Port `55532` is reserved for this code-generation step so it does not depend on the Task 13 Compose stack.

Refresh locks and verification metadata in separate no-cache invocations, then run
`resolveAndLockAll` without write flags under `--dependency-verification=strict`, execute
`tests/bootstrap/verify-workspace.ps1`, and repeat generation while comparing SHA-256 for every
tracked lock plus `gradle/verification-metadata.xml`. Expected: the new database lock exists, every
artifact has checksum or scoped signature evidence, ignored PGP keys retain explicit key-server
reasons, and the second generation changes no dependency-evidence file.

Run the generator twice; each invocation creates and removes a fresh container. After each run,
build sorted manifests in memory and compare exact `relative-path|lowercase-sha256` lines:

```powershell
$generatedRoot = (Resolve-Path 'database/control-plane/build/generated-src/jooq/main').Path
function Get-GeneratedManifest {
  Get-ChildItem -LiteralPath $generatedRoot -Recurse -File -Filter '*.java' |
    Sort-Object FullName | ForEach-Object {
      $relative = $_.FullName.Substring($generatedRoot.Length + 1).Replace('\', '/')
      "$relative|$((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant())"
    }
}
function Get-DependencyEvidenceManifest {
  $files = @(
    Get-ChildItem -LiteralPath . -Recurse -File -Filter 'gradle.lockfile' |
      Where-Object { $_.FullName -notmatch '[\\/](?:\.gradle|build)[\\/]' }
    Get-Item -LiteralPath 'settings-gradle.lockfile'
    Get-Item -LiteralPath 'database/control-plane/buildscript-gradle.lockfile'
    Get-Item -LiteralPath 'gradle/verification-metadata.xml'
  )
  $files | Sort-Object FullName -Unique | ForEach-Object {
    $root = (Resolve-Path '.').Path
    $relative = $_.FullName.Substring($root.Length + 1).Replace('\', '/')
    "$relative|$((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant())"
  }
}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/generate-control-plane-jooq.ps1 -Port 55532
if ($LASTEXITCODE -ne 0) { throw 'First jOOQ generation failed' }
$generatedFirst = @(Get-GeneratedManifest)
$evidenceFirst = @(Get-DependencyEvidenceManifest)
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/generate-control-plane-jooq.ps1 -Port 55532
if ($LASTEXITCODE -ne 0) { throw 'Second jOOQ generation failed' }
$generatedSecond = @(Get-GeneratedManifest)
$evidenceSecond = @(Get-DependencyEvidenceManifest)
if (@(Compare-Object $generatedFirst $generatedSecond -CaseSensitive).Count -ne 0) {
  throw 'Generated jOOQ Java manifest is not reproducible'
}
if (@(Compare-Object $evidenceFirst $evidenceSecond -CaseSensitive).Count -ne 0) {
  throw 'Dependency evidence changed across generation runs'
}
```

Both generated manifests must cover the complete sorted Java file set and match exactly; both
dependency-evidence manifests must also match, and `docker ps --all` must show no container ID from
either invocation.

- [ ] **Step 6: Commit the persistence baseline**

```bash
git add .gitignore build.gradle settings.gradle gradle/libs.versions.toml gradle/verification-metadata.xml \
  config/testcontainers/testcontainers.properties \
  docs/superpowers/plans/2026-07-24-accord-platform-foundation-plan.md \
  tests/integration/src/test/java/com/inforvans/accord/integration/TestcontainersConfigurationTest.java \
  tests/bootstrap/verify-workspace.ps1 \
  tests/bootstrap/verify-control-plane-jooq-generator.ps1 \
  scripts/generate-control-plane-jooq.ps1 \
  database/control-plane
git commit -m "feat: add tenant-scoped persistence baseline"
```

`database/control-plane/build/generated-src/jooq/**` is an ignored build verification output, not a
committed source file; Step 5 regenerates and compiles it, while the commit manifest stages every
created or modified source/configuration file listed above.

### Task 7: Implement Expected-Version CAS And Persistent Idempotency

**Files:**
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExpectedVersion.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/CommandKey.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/StoredHttpResult.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ClaimLease.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/Claim.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/VersionConflict.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/JooqCommandGate.java
- Create: apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqCommandGateTest.java
- Modify: apps/control-plane/modules/reliability/build.gradle
- Modify: apps/control-plane/modules/reliability/gradle.lockfile
- Modify: apps/control-plane/api/gradle.lockfile
- Modify: apps/control-plane/worker/gradle.lockfile
- Modify: gradle/verification-metadata.xml

- [ ] **Step 1: Write failing PostgreSQL integration tests for command ownership and CAS**

Use the pinned PostgreSQL image and the real API/worker login roles. Each test opens explicit physical JDBC connections, executes SET ROLE, installs transaction-local app.tenant_id, and calls JooqCommandGate with the caller-owned jOOQ DSLContext. Cover these exact behaviors:

1. simultaneous initial claims produce one generation-1 acquisition and one in-progress result;
2. the same key with a changed fingerprint returns a payload-free conflict without changing the stored fingerprint;
3. simultaneous expired takeovers produce one new generation/token and one in-progress result;
4. an independent administrator connection observes the waiter's exact PostgreSQL backend PID as an active `FOR UPDATE` Lock wait with an ungranted `pg_locks` entry before the original database deadline; the waiter then decides expiry using database time observed after it acquires the row lock;
5. simultaneous renewals of the same fence allow exactly one extension;
6. renewal extends the prior stored deadline and rejects altered owner, generation, token, or exact deadline;
7. simultaneous ExpectedVersion(0) creations store version 1 and report actual version 1 to the loser;
8. simultaneous ExpectedVersion(1) updates store version 2 and report actual version 2 to the loser;
9. a missing positive expected version does not insert an aggregate head;
10. stale or naturally expired completion rolls back a preceding aggregate mutation;
11. completed replay preserves exact body text and canonical logical headers;
12. completion requires the matching tenant/type/id/version row in aggregate_head;
13. public values reject malformed, oversized, control-bearing, or sensitive input before SQL; shared actor, route, owner, and aggregate-type limits count Unicode code points like PostgreSQL `varchar(n)`, accept supplementary code points at each exact limit, reject one beyond it, and reject U+0000 or isolated surrogates; the idempotency key rejects non-ASCII characters; response header values allow horizontal tab and valid supplementary Unicode but reject controls and isolated surrogates with strict UTF-8 validation; and the bounded response body accepts exact-limit UTF-8;
14. malformed persisted header JSON fails closed;
15. deletion between insert conflict and locked select is retried once.

Run:

~~~powershell
$env:JAVA_HOME='C:\Users\m1560\.jdks\jdk-21.0.11+10'
.\gradlew.bat :apps:control-plane:modules:reliability:test --tests '*JooqCommandGateTest' --no-daemon --rerun-tasks
~~~

Expected RED: test compilation fails because the seven production command-gate types do not exist. After the initial implementation, behavioral failures must remain visible until database timestamp and JSONB parameters are explicitly typed.

- [ ] **Step 2: Define closed, validated command-gate values**

Use these public shapes:

~~~java
public record ExpectedVersion(long value) {}
public record CommandKey(
    UUID tenantId, String actorId, String routeKey, String idempotencyKey) {}
public record StoredHttpResult(
    int status, Map<String, String> headers, String body) {}
public record ClaimLease(
    String owner, long generation, UUID token, OffsetDateTime leaseUntil) {}
public sealed interface Claim
        permits Claim.Acquired, Claim.InProgress, Claim.Replay, Claim.RequestConflict {
    record Acquired(ClaimLease lease) implements Claim {}
    record InProgress(OffsetDateTime leaseUntil) implements Claim {}
    record Replay(StoredHttpResult result) implements Claim {}
    record RequestConflict() implements Claim {}
}
public final class VersionConflict extends RuntimeException {
    public long expected();
    public Long actual();
}
~~~

ExpectedVersion rejects negative values. CommandKey requires a tenant, bounded actor and route values, and an idempotency key matching [A-Za-z0-9._:-]{16,128}. The shared bounded-text validator used by actor ID, route key, claim owner, and aggregate type rejects U+0000 and ISO control characters, runs a UTF-8 `CharsetEncoder` with malformed and unmappable input set to `REPORT`, and only then enforces its maximum with Unicode code-point count rather than UTF-16 code-unit count. Valid Unicode, including supplementary code points through the exact PostgreSQL `varchar(n)` character limit, remains accepted. Conflict results never return the stored request fingerprint.

StoredHttpResult accepts status 100-599, copies and case-folds headers into an immutable sorted map, rejects case-insensitive duplicates, invalid names/values, `Authentication-Info`, other credential-bearing headers, and hop-by-hop headers. Header values allow horizontal tab but reject U+0000, other controls, and malformed or unmappable input with a strict UTF-8 encoder before SQL. The response body rejects U+0000 and malformed or unmappable input with the same strict encoder, then bounds the encoder output to 1 MiB. The database JSON representation of the headers is independently bounded to 64 KiB.

- [ ] **Step 3: Implement initial claim, replay, and takeover with PostgreSQL time**

JooqCommandGate has no clock, pool, or transaction ownership. Its public constructor needs no infrastructure argument; each operation receives the caller's transaction-scoped DSLContext.

Initial claim inserts a valid placeholder before it asks the database for current time:

~~~sql
INSERT INTO idempotency_result (
  tenant_id, actor_id, route_key, idempotency_key,
  request_fingerprint, state, claim_owner, claim_generation,
  claim_token, lease_until, expires_at
) VALUES (?, ?, ?, ?, ?, 'STARTED', ?, 1, ?,
          '-infinity'::timestamptz, '-infinity'::timestamptz)
ON CONFLICT DO NOTHING
RETURNING claim_generation
~~~

If inserted, read clock_timestamp() and replace both placeholder deadlines with database_now + lease_duration under owner/generation/token/-infinity predicates. Never calculate a correctness deadline from a JVM clock.

If the insert conflicts, lock the exact tenant/actor/route/key row with FOR UPDATE. A missing row can occur when a concurrent worker deletes an expired row between the conflict and select; retry the insert/select sequence once, then fail closed. Compare the fingerprint after the lock. Completed state returns exact persisted status, validated canonical headers, and exact text body. A live started state returns InProgress.

For an expired state, increment generation with overflow detection, generate a fresh opaque token, and update through all of these predicates:

~~~sql
state = 'STARTED'
AND claim_owner = ?
AND claim_generation = ?
AND claim_token = ?
AND lease_until = CAST(? AS timestamptz)
AND lease_until <= CAST(? AS timestamptz)
~~~

The update computes its returned deadline from the same captured database timestamp. Owner strings identify executors but are never treated as unique fences.

- [ ] **Step 4: Implement renewal and completion with full fences**

Before renewal or completion, lock the command row and then fetch clock_timestamp(). Renewal adds the requested interval to the prior stored deadline, not to observation time. It requires matching tenant/actor/route/key, started state, owner, generation, token, exact deadline, and a deadline still greater than database time.

Completion validates the response before persistence and atomically transitions the row to completed. It clears owner/token/deadline, stores status/canonical headers/exact text body, binds aggregate type/id/version, and computes retention from the captured database timestamp. Its WHERE clause requires the same full live lease fence and:

~~~sql
EXISTS (
  SELECT 1
  FROM aggregate_head AS aggregate
  WHERE aggregate.tenant_id = stored.tenant_id
    AND aggregate.aggregate_type = ?
    AND aggregate.aggregate_id = ?
    AND aggregate.version = ?
)
~~~

The caller must commit the visible STARTED claim before business work. Aggregate, domain, audit, outbox, and completion writes share a later transaction, so any stale/expired/mismatched completion fence rolls all preceding business writes back. A deterministic rejection that is eligible for replay follows the same two-transaction rule: after acquisition, its outcome aggregate/version, required audit/outbox facts, rejection response, and fenced completion persist together in the second transaction. Authentication, protocol, and fingerprint validation that fail before a claim are never stored as command results.

- [ ] **Step 5: Implement exact expected-version aggregate CAS**

advance validates tenant/type/id and computes next = expected + 1 with overflow detection.

For ExpectedVersion(0), use INSERT ... ON CONFLICT DO NOTHING to create version 1. For positive expectations, use one update containing WHERE version = expected. Both paths set updated_at=clock_timestamp(). If no row changes, read the actual row under tenant RLS and throw VersionConflict(expected, actual); never insert for a missing positive expectation.

- [ ] **Step 6: Declare the minimal production and test dependency boundary**

Reliability exposes jOOQ in its public method signatures and therefore declares api libs.jooq.runtime. It imports no production class from database:control-plane; that project appears only as testFixtures for role/bootstrap support. The library does not own a pool, transaction manager, or Spring auto-configuration, so it must not depend on spring-boot-starter-jooq or export the Spring Boot BOM. A process that later constructs the caller-owned DSLContext declares that starter at the process boundary.

~~~groovy
implementation project(':apps:control-plane:modules:platform-kernel')
api platform(libs.spring.modulith.bom)
api libs.spring.modulith.api
api libs.jooq.runtime
implementation libs.jackson.databind

testImplementation enforcedPlatform(libs.junit.bom)
testImplementation enforcedPlatform(libs.jackson.bom)
testImplementation enforcedPlatform(libs.slf4j.bom)
testImplementation testFixtures(project(':database:control-plane'))
testImplementation libs.flyway.core
testImplementation libs.flyway.postgresql
testImplementation libs.testcontainers.junit
testImplementation libs.testcontainers.postgresql
testCompileOnly 'jakarta.xml.bind:jakarta.xml.bind-api:4.0.2'
testRuntimeOnly libs.slf4j.simple
~~~

Use the existing enforced JUnit, Jackson, and SLF4J test platforms plus the catalog-pinned Flyway and Testcontainers modules. Resolve naturally without force/strict constraints; add a narrow constraint only if a clean lock refresh proves a real unresolved version conflict. Do not add a Spring Boot platform/starter to this library and do not add a second SLF4J provider.

Refresh only this project lock with configuration cache disabled:

~~~powershell
$env:JAVA_HOME='C:\Users\m1560\.jdks\jdk-21.0.11+10'
.\gradlew.bat :apps:control-plane:modules:reliability:dependencies --write-locks --no-configuration-cache --no-daemon --dependency-verification=strict
~~~

Because jOOQ is an exported method-signature dependency, run the repository lock resolver after the focused reliability report so it resolves the API and worker outgoing variants. Only reliability, API, and worker locks may change. Only jOOQ, R2DBC SPI, and Reactive Streams may be added to the consumer compile/runtime/test lock sets; Spring JDBC, Hikari, and jOOQ auto-configuration remain process-owned and must not arrive transitively from reliability.

~~~powershell
$env:JAVA_HOME='C:\Users\m1560\.jdks\jdk-21.0.11+10'
.\gradlew.bat resolveAndLockAll --write-locks --no-configuration-cache --no-daemon --dependency-verification=strict
~~~

Generate any newly required artifact checksums/signature trust with --write-verification-metadata sha256,pgp, inspect the exact XML diff, repeat both writers, and require identical hashes on the second run.

- [ ] **Step 7: Verify and commit command safety primitives**

Run fresh tests and boundary checks:

~~~powershell
$env:JAVA_HOME='C:\Users\m1560\.jdks\jdk-21.0.11+10'
.\gradlew.bat :database:control-plane:test :apps:control-plane:modules:reliability:test :apps:control-plane:api:check :apps:control-plane:worker:check --no-daemon --rerun-tasks --dependency-verification=strict
.\gradlew.bat check --no-daemon --dependency-verification=strict
powershell.exe -NoProfile -File tests/bootstrap/verify-workspace.ps1
git diff --check
~~~

Also require no production database:control-plane dependency/import, no java.time.Clock or clock.instant, no reliability Spring Boot enforcedPlatform, no unsafe replay header, and no secret-like material. Confirm the fresh JUnit XML reports 19 tests, zero failures/errors/skips, and exactly one runtime SLF4J provider.

~~~bash
git add docs/superpowers/plans/2026-07-24-accord-platform-foundation-plan.md apps/control-plane/api/gradle.lockfile apps/control-plane/worker/gradle.lockfile apps/control-plane/modules/reliability/build.gradle apps/control-plane/modules/reliability/gradle.lockfile apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqCommandGateTest.java gradle/verification-metadata.xml
git commit -m "feat: add persistent command gate"
~~~
### Task 8: Add Transactional Domain Events, Outbox, Inbox, And External Intents

**Goal:** Add tenant-isolated event, outbox, inbox, and external-intent persistence. Runtime roles
mutate only through fixed-owner PostgreSQL routines; Provider I/O occurs after a committed claim;
terminal intent, domain-event, and outbox changes remain one atomic database unit.

**Exact 25-file implementation manifest:**
- Modify: database/control-plane/build.gradle
- Create: database/control-plane/migrations/V002__reliable_event_delivery.sql
- Create: database/control-plane/src/test/java/com/inforvans/accord/database/ReliableDeliveryMigrationTest.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/DomainEvent.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExecutionClaim.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExecutionResolution.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentDefinition.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentRef.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentRegistration.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentSnapshot.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentState.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalWritePermit.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxAcceptance.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxMessage.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/JooqExternalIntentStore.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/LostExternalIntentFence.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxMessage.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReconciliationClaim.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReconciliationLease.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReconciliationResolution.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliabilityValues.java
- Create: apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableEventStore.java
- Create: apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqExternalIntentStoreTest.java
- Create: apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/PostgreSqlReliabilityTestSupport.java
- Create: apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliableEventStoreTest.java

database/control-plane/build.gradle adds all four V002 tables to jOOQ generation.
ReliableDeliveryMigrationTest owns schema/catalog/real-role proofs. PostgreSqlReliabilityTestSupport
is the sole reusable PostgreSQL fixture. The nineteen main types and two behavior tests own the
validated Java boundary and integration behavior; no second inline PostgreSQL fixture is allowed.

**Authoritative invariants:**
1. Both stores own no pool, transaction manager, Spring configuration, or clock; every operation
   receives the caller's transaction DSLContext.
2. accord_api and accord_worker have zero INSERT, UPDATE, and DELETE on all four V002 tables.
   Runtime table DML is exactly zero.
3. API SELECT is exactly domain_event and outbox_event. Worker SELECT is exactly domain_event,
   outbox_event, and inbox_message. Both have zero table/column SELECT on external_call_intent.
4. All four tables have ENABLE+FORCE RLS and the sole FOR ALL TO PUBLIC tenant_isolation policy,
   using/checking tenant_id=accord_security.current_tenant_id().
5. All fifteen runtime routines are accord_migrator-owned SECURITY DEFINER routines with exact
   search_path=pg_catalog, pg_temp, schema-qualified objects, and explicit p_tenant_id/app.tenant_id
   comparison. Every returned row has named columns/types; no table composite result is permitted.
6. reject_reliability_row_change, guard_external_intent_successor, and
   guard_external_intent_transition remain RETURNS trigger and grant no runtime EXECUTE.
7. SAFE_REF is exactly result_tenant_id uuid, result_intent_id uuid, result_root_intent_id uuid,
   result_attempt_ordinal integer, result_global_idempotency_key varchar, result_state varchar.
8. SNAPSHOT is exactly tenant_id uuid, intent_id uuid, root_intent_id uuid,
   predecessor_intent_id uuid, attempt_ordinal integer, scope_type varchar, scope_id varchar,
   logical_action_key varchar,
   global_idempotency_key varchar, state varchar, execution_generation bigint,
   reconciliation_generation bigint, provider_request_id varchar, outcome_digest char(71),
   last_error_code varchar, created_at timestamptz, updated_at timestamptz, terminal_at timestamptz.
   Scope is non-sensitive event-routing context already bound to the intent definition. The snapshot
   excludes owner, token, deadline, request digest, and Provider invocation bindings.
9. Execution claim returns disposition text and state varchar, then nullable tenant/id,
   execution owner/generation/token/deadline, global key, provider/installation/repository,
   operation, request-reference type/id/version, and request digest, in that exact order/type.
10. Reconciliation claim uses reconciliation owner/generation/token/deadline and appends nullable
    provider_request_id. Missing, losing, active, repeated, and same-token outcomes disclose no
    capability: missing yields no row; every capability/provider column in a non-winning row is null.
11. Only the call changing RECORDED->EXECUTING or OUTCOME_UNKNOWN->RECONCILING with its candidate
    token receives capability. Java rechecks owner/token. Renew requires the full prior capability,
    returns only a replacement deadline, and Java copies all other caller-held fields.
12. record exposes only disposition+SAFE_REF and null SAFE_REF on conflict; successor exposes only
    SAFE_REF; load exposes only SNAPSHOT through load_external_intent_snapshot. No runtime direct
    external_call_intent query is allowed.
13. The state graph is RECORDED->EXECUTING->SUCCEEDED|CONFIRMED_NO_EFFECT|OUTCOME_UNKNOWN and
    OUTCOME_UNKNOWN->RECONCILING->SUCCEEDED|CONFIRMED_NO_EFFECT|DIVERGED|OUTCOME_UNKNOWN.
14. Successor creation requires CONFIRMED_NO_EFFECT, preserves root and immutable Provider/request
    definition, increments ordinal, creates a new intent/global key, and is unique per predecessor.
15. Append writes event+outbox atomically; inbox deduplicates by tenant/source/message and keeps
    digest conflict payload-free. Execution/reconciliation terminal routines validate the complete
    live fence and event binding. Commit, outer rollback, event-conflict rollback, and caught-stale
    rollback are symmetric and leave no partial intent/event/outbox state.

**Exact result/ACL matrix:**

| Routine | Result | API | Worker |
|---|---|---:|---:|
| append_reliable_event | void | EXECUTE | EXECUTE |
| accept_inbox_message | TABLE(disposition text, stored_digest text, stored_state text) | none | EXECUTE |
| record_external_intent | TABLE(disposition text, SAFE_REF) | EXECUTE | none |
| load_external_intent_snapshot | TABLE(SNAPSHOT) | EXECUTE | EXECUTE |
| create_external_intent_successor | TABLE(SAFE_REF) | none | EXECUTE |
| claim_external_intent_execution | TABLE(disposition, state, nullable execution capability) | none | EXECUTE |
| renew_external_intent_execution | TABLE(execution_lease_until timestamptz) | none | EXECUTE |
| mark_external_intent_execution_unknown | void | none | EXECUTE |
| expire_external_intent_execution | boolean | none | EXECUTE |
| complete_external_intent_execution | void | none | EXECUTE |
| claim_external_intent_reconciliation | TABLE(disposition, state, nullable reconciliation capability) | none | EXECUTE |
| renew_external_intent_reconciliation | TABLE(reconciliation_lease_until timestamptz) | none | EXECUTE |
| mark_external_intent_reconciliation_unknown | void | none | EXECUTE |
| expire_external_intent_reconciliation | boolean | none | EXECUTE |
| complete_external_intent_reconciliation | void | none | EXECUTE |

Revoke PUBLIC/API/worker first, then grant exactly this matrix. Catalog tests assert owner,
SECURITY DEFINER, search path, result names/order/types, no SETOF external_call_intent, and exact
ACL. record and successor map only ExternalIntentRef; load maps only ExternalIntentSnapshot.
ReliableEventStore exposes append(tx,event,outbox) and acceptInbox(tx,message).
JooqExternalIntentStore exposes record, createSuccessor, claim/renew/markUnknown/complete/expire for
execution and reconciliation, plus load; all take DSLContext first. Claims are Acquired,
NotExecutable/NotReconcilable, or Missing. Registration is Created, Duplicate, or payload-free
Conflict. Inbox acceptance is Accepted, Duplicate(state), or payload-free DigestConflict.

- [ ] **TDD Slice 1 of 6: Final schema, RLS, ownership, and ACL**

Write ReliableDeliveryMigrationTest first for tables, constraints/FKs/indexes, ENABLE+FORCE RLS,
policies, routine owner/search path, exact table/column/function ACL, and real API/worker SET ROLE
denials for direct external SELECT and all DML.
RED: ./gradlew.bat :database:control-plane:test --tests '*ReliableDeliveryMigrationTest' --no-daemon --rerun-tasks --dependency-verification=strict
Expected RED: V002, four tables, routines, and generated-table include set are absent.
GREEN responsibility: create constrained schema, RLS/triggers/revokes, fixed-owner routines, and
the six-table jOOQ include/verification manifest. Re-run RED command; all checks pass.

- [ ] **TDD Slice 2 of 6: Event, outbox, and inbox**

Write ReliableEventStoreTest first for append commit/outer rollback, tenant/FK mismatch, duplicate
inbox digest, changed-digest payload-free conflict, JSON/bounds, and routine access.
RED: ./gradlew.bat :apps:control-plane:modules:reliability:test --tests '*ReliableEventStoreTest' --no-daemon --rerun-tasks --dependency-verification=strict
Expected RED: event/inbox values, fixture, store, and append/accept routines are missing.
GREEN responsibility: implement DomainEvent, OutboxMessage, InboxMessage, InboxAcceptance,
ReliabilityValues, shared fixture/store, append_reliable_event, and accept_inbox_message. Re-run the
focused reliability and migration tests.

- [ ] **TDD Slice 3 of 6: Safe registration and snapshot**

Add JooqExternalIntentStoreTest cases first for create/duplicate/conflict, generated key,
cross-tenant denial, duplicate EXECUTING root exposing only safe ref, API+worker safe load, missing
load, exact record components, and direct external SELECT denial.
RED: ./gradlew.bat :apps:control-plane:modules:reliability:test --tests '*JooqExternalIntentStoreTest' --no-daemon --rerun-tasks --dependency-verification=strict
Expected RED: intent values/store plus record/load routines are absent.
GREEN responsibility: implement definition/ref/registration/snapshot/state, record_external_intent,
load_external_intent_snapshot, and safe Java mapping. Re-run the focused test.

- [ ] **TDD Slice 4 of 6: One-shot execution capability**

Add tests first for simultaneous/repeated/same-token claims, losing raw null columns, candidate
owner/token validation, database-time lease, full-fence renewal, select-then-complete attack,
unknown/expiry, commit, outer rollback, event-conflict rollback, and caught-stale rollback.
RED: ./gradlew.bat :apps:control-plane:modules:reliability:test --tests '*JooqExternalIntentStoreTest' --no-daemon --rerun-tasks --dependency-verification=strict
Expected RED: execution values/methods and five lifecycle routines are absent.
GREEN responsibility: implement claim, deadline-only renew, unknown, expiry, terminal completion,
and full capability/provider binding; only the state-changing candidate receives it. Re-run focused
Java and migration tests.

- [ ] **TDD Slice 5 of 6: Reconciliation and successor**

Add tests first for losing/repeated/same-token reconciliation, deadline-only renew, still-unknown,
expiry recovery, forged/select-then-complete attacks, three terminal outcomes, symmetric atomicity,
and one exact-definition successor only after CONFIRMED_NO_EFFECT.
RED: ./gradlew.bat :apps:control-plane:modules:reliability:test --tests '*JooqExternalIntentStoreTest' --no-daemon --rerun-tasks --dependency-verification=strict
Expected RED: reconciliation values/methods, successor, and lifecycle routines are absent.
GREEN responsibility: implement claims/lease/resolutions, LostExternalIntentFence, successor,
unknown/expiry, and atomic terminal routines. Re-run focused Java and migration tests.

- [ ] **TDD Slice 6 of 6: Catalog and non-disclosure closure**

Add exact pg_proc OUT-name/type/order and ACL tests, no-composite assertion, real-role SELECT
attacks, raw non-winning null assertions, candidate mismatch checks, and paired execution/
reconciliation commit, outer rollback, event-conflict rollback, and caught-stale rollback.
RED: ./gradlew.bat :database:control-plane:test --tests '*ReliableDeliveryMigrationTest' :apps:control-plane:modules:reliability:test --tests '*JooqExternalIntentStoreTest' --no-daemon --rerun-tasks --dependency-verification=strict
Expected RED: any broad SELECT, rowtype result, capability leak, or asymmetric rollback fails.
GREEN responsibility: remove external SELECT, add narrow snapshot load, use exact claim/renew
results, revalidate candidates, copy caller-held capabilities on renewal, and map the exact safe
scope fields without exposing capability or Provider binding data. Re-run RED command.

**Verification:**

~~~powershell
$env:JAVA_HOME='C:/Users/m1560/.jdks/jdk-21.0.11+10'
./gradlew.bat :database:control-plane:test :apps:control-plane:modules:reliability:test --no-daemon --rerun-tasks --dependency-verification=strict
./gradlew.bat check --no-daemon --rerun-tasks --dependency-verification=strict
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/bootstrap/verify-workspace.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/generate-control-plane-jooq.ps1 -Port 55532
git diff --check
~~~

The disposable jOOQ run migrates through V002, generates/compiles all six table+record types,
removes its pinned PostgreSQL 17.5 container, and leaves generated sources ignored. Fresh JUnit XML
must have zero failures/errors/skips. Static review rejects a production database-control-plane
import, owned pool/transaction/Spring configuration/JVM correctness clock, runtime external DML or
SELECT, composite routine result, secret-like value, or capability disclosure.

**Exact staging and commits:**

Greenfield staging is exactly the 25 manifest paths:

~~~powershell
git add -- database/control-plane/build.gradle database/control-plane/migrations/V002__reliable_event_delivery.sql database/control-plane/src/test/java/com/inforvans/accord/database/ReliableDeliveryMigrationTest.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/DomainEvent.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExecutionClaim.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExecutionResolution.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentDefinition.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentRef.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentRegistration.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentSnapshot.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentState.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalWritePermit.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxAcceptance.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxMessage.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/JooqExternalIntentStore.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/LostExternalIntentFence.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxMessage.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReconciliationClaim.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReconciliationLease.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReconciliationResolution.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliabilityValues.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableEventStore.java apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqExternalIntentStoreTest.java apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/PostgreSqlReliabilityTestSupport.java apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliableEventStoreTest.java
git diff --cached --name-only
git commit -m "feat: add reliable event delivery primitives"
~~~

Keep a04bbe0 intact. Keep 96b2afb's exact eight-file review intact: ExecutionResolution.java,
JooqExternalIntentStore.java, ReconciliationResolution.java, ReliabilityValues.java,
ReliableEventStore.java, JooqExternalIntentStoreTest.java, V002__reliable_event_delivery.sql, and
ReliableDeliveryMigrationTest.java. Do not amend or squash either commit. The final review is a
separate exact four-file capability hardening:

~~~powershell
git add -- database/control-plane/migrations/V002__reliable_event_delivery.sql database/control-plane/src/test/java/com/inforvans/accord/database/ReliableDeliveryMigrationTest.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/JooqExternalIntentStore.java apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqExternalIntentStoreTest.java
git diff --cached --name-only
git commit -m "fix: prevent external intent capability disclosure"
~~~

Task 10's first TDD substep owns one independent unpublished-V002 safe-scope follow-up without
amending either prior commit. A reconciliation lease does not contain scope and must never be treated
as sufficient event authority; Task 10 is blocked until that focused migration/catalog/store test is
GREEN.

### Task 9: Prove Enterprise HTTP Reliability At The Control API

**Goal:** Expose `POST /v1/contract-validations/{validationId}` as a real PostgreSQL-backed
mutation that proves strict HTTP parsing, trusted identity, browser-session CSRF, RFC 7807,
persistent idempotent replay, ExpectedVersion CAS, transactional domain event/outbox emission, and
full-fence rollback without performing any external Provider I/O in a database transaction.

**Files:**
- Create: `database/control-plane/migrations/V003__foundation_http_reliability.sql`
- Create: `database/control-plane/src/test/java/com/inforvans/accord/database/FoundationHttpReliabilityMigrationTest.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationVerifiedPrincipal.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationTenantTransactions.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationHttpRequestPolicy.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationJson.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/HttpIdempotencyFingerprint.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationHttpConfiguration.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationCommandService.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationController.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationHttpSecurity.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ProblemAdvice.java`
- Create: `apps/control-plane/api/src/test/java/com/inforvans/accord/controlplane/http/HttpIdempotencyFingerprintTest.java`
- Create: `apps/control-plane/api/src/test/java/com/inforvans/accord/controlplane/http/ContractValidationApiTest.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliabilityValuesTest.java`
- Modify: `contracts/openapi/accord-control-api.yaml`
- Modify: `contracts/json-schema/problem-details.schema.json`
- Modify: `tests/contracts/openapi-contract.test.mjs`
- Modify: `database/control-plane/src/test/java/com/inforvans/accord/database/PlatformMigrationTest.java`
- Modify: `apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platformkernel/CanonicalJson.java`
- Modify: `apps/control-plane/modules/platform-kernel/src/test/java/com/inforvans/accord/platformkernel/CanonicalJsonTest.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliabilityValues.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/DomainEvent.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxMessage.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxMessage.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/JooqCommandGate.java`
- Modify: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqCommandGateTest.java`
- Modify: `gradle/libs.versions.toml`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/api/gradle.lockfile`
- Modify: `gradle/verification-metadata.xml`
- Modify: `apps/control-plane/api/src/main/resources/application.yml`

Do not modify the worker configuration in this task. Task 10 owns the worker's baseline datasource,
jOOQ, PostgreSQL, and Temporal wiring; Task 12 later reuses that baseline and adds only delivery
scheduling/drain configuration. Do not add `contract_validation` to database jOOQ generation: the existing
reliability boundary accepts the caller-owned transaction `DSLContext` and uses explicit bounded
SQL, while generated database classes remain an ignored verification output.

**Authoritative invariants:**

1. Authentication, authorization, CSRF, route/header/media policy, raw JSON/I-JSON parsing, closed
   request-shape parsing, and request fingerprint construction all finish before the idempotency
   claim. A failure in this phase creates no `idempotency_result`, aggregate, validation, event, or
   outbox row.
2. An acquired command uses exactly two database transactions. Transaction 1 installs and verifies
   tenant context, calls `JooqCommandGate.claim`, and commits `STARTED`. Local schema lookup and JSON
   Schema evaluation may run after that commit but perform no network or database I/O. Transaction
   2 installs tenant context again and commits either a fenced rejection or the entire business
   result.
3. Transaction 2 for success contains ExpectedVersion CAS, `contract_validation`, `domain_event`,
   `outbox_event`, serialized HTTP response, and fenced command completion. A lost, expired, or
   replaced claim fence rolls all of them back. No controller or service method has an outer
   `@Transactional` annotation.
4. Only deterministic business outcomes `404`, `412`, and `422` may complete without an aggregate
   binding. They are stored through `completeRejection` and replay byte-for-byte. Protocol,
   authentication, CSRF, `409`, `415`, and `406` results are not stored as completed commands.
5. `Claim.RequestConflict` remains payload-free. Neither the stored request fingerprint nor any
   secret/session/CSRF value appears in a response, Problem, log assertion, event, or outbox payload.
6. `schema_id` is an identifier into a classpath allowlist. It is never dereferenced as a URL. This
   proof has no Provider client. Future external work must record an FT8 external intent in a
   business transaction, let a worker commit `claimExecution`, call the Provider after that commit,
   and finish under the returned fence in another transaction.
7. Early policy has one strict precedence and short-circuits at the first failure with zero claim:
   trusted authentication; authorization; browser CSRF; correlation/query/visible-singleton-header
   shape; Content-Type; Accept syntax/negotiation; Idempotency-Key; If-Match; then raw-body byte
   limit, UTF-8, I-JSON, and closed JSON decoding. A malformed `X-Correlation-ID` is
   `REQUEST_INVALID` only after security succeeds. Authentication/security Problem creation instead
   generates a fresh UUID and never reflects malformed correlation input, preserving security
   precedence.
8. The application boundary is only the value enumeration visible through
   `HttpServletRequest.getHeaders(name)`; it never claims to reconstruct container-merged physical
   lines. Singleton-header count and missing-value behavior follow the Step 2 table exactly. Accept
   is an RFC list header: all visible values combine under comma-list semantics with at most 2048
   total visible UTF-8 bytes and 16 total ranges.
9. The controller returns `ResponseEntity<byte[]>`. It strict-UTF-8 encodes
   `StoredHttpResult.body()` exactly once, applies only the canonical persisted safe-header map, and
   never routes the body through a String message converter or JSON reserialization. Replay compares
   status, canonical persisted safe headers, and raw UTF-8 body bytes.

**Authoritative Problem table:**

Every endpoint Problem body has exactly base keys `type`, `title`, `status`, `code`,
`correlation_id`, and `instance`, plus only the row's extensions. There is no `detail` key.
`instance` is the request path without query. Type is the listed absolute URI and is never relative.

| Code | Absolute type | Stable title | Status | Exact extensions |
| --- | --- | --- | ---: | --- |
| `REQUEST_INVALID` | `https://problems.accord.inforvans.com/request-invalid` | `Request is invalid` | 400 | none |
| `JSON_INVALID` | `https://problems.accord.inforvans.com/json-invalid` | `JSON document is invalid` | 400 | none |
| `AUTHENTICATION_REQUIRED` | `https://problems.accord.inforvans.com/authentication-required` | `Authentication is required` | 401 | none |
| `AUTHORIZATION_DENIED` | `https://problems.accord.inforvans.com/authorization-denied` | `Authorization is denied` | 403 | none |
| `CSRF_VALIDATION_FAILED` | `https://problems.accord.inforvans.com/csrf-validation-failed` | `CSRF validation failed` | 403 | none |
| `SCHEMA_NOT_FOUND` | `https://problems.accord.inforvans.com/schema-not-found` | `Contract schema was not found` | 404 | none |
| `CONTRACT_VALIDATION_NOT_FOUND` | `https://problems.accord.inforvans.com/contract-validation-not-found` | `Contract validation was not found` | 404 | none |
| `NOT_ACCEPTABLE` | `https://problems.accord.inforvans.com/not-acceptable` | `Requested representation is not acceptable` | 406 | none |
| `IDEMPOTENCY_KEY_REUSED` | `https://problems.accord.inforvans.com/idempotency-key-reused` | `Idempotency key was reused for a different request` | 409 | none |
| `COMMAND_IN_PROGRESS` | `https://problems.accord.inforvans.com/command-in-progress` | `Command is already in progress` | 409 | `retry_after` |
| `VERSION_CONFLICT` | `https://problems.accord.inforvans.com/version-conflict` | `Expected version does not match` | 412 | `expected_version`, `actual_version` |
| `UNSUPPORTED_MEDIA_TYPE` | `https://problems.accord.inforvans.com/unsupported-media-type` | `Content type is not supported` | 415 | none |
| `VERSION_LIMIT_REACHED` | `https://problems.accord.inforvans.com/version-limit-reached` | `Aggregate version limit was reached` | 422 | none |
| `DOCUMENT_SCHEMA_INVALID` | `https://problems.accord.inforvans.com/document-schema-invalid` | `Document does not satisfy the schema` | 422 | `errors` |

`expected_version` is a JSON integer in `0..9223372036854775807`; `actual_version` is either null or
an integer in `1..9223372036854775807`; `retry_after` is integer seconds in `1..120`. `errors` contains
`1..128` closed items with exactly `field` (string length `1..512`) and `reason` (stable uppercase
keyword/code length `1..64`, pattern `^[A-Z][A-Z0-9_]{0,63}$`); validator prose is never copied.
The JSON Schema retains `additionalProperties: true` only for generic RFC 7807 reuse, while the
endpoint factory and tests assert the exact per-row key set. Persisted 404/412/422 Problems use this
same table and byte representation, never a second stored variant.

- [ ] **Step 1: Make the cumulative HTTP contract fail on the enterprise policy**

Replace the current mutation-policy assertions in
`tests/contracts/openapi-contract.test.mjs` with assertions that the foundation operation has both
security alternatives, conditional browser CSRF metadata, the exact success response, and every
explicit failure response:

```javascript
test('foundation mutation publishes the complete HTTP reliability policy', async () => {
  const source = await readFile('contracts/openapi/accord-control-api.yaml', 'utf8');
  const api = YAML.parse(source);
  const operation = api.paths['/v1/contract-validations/{validationId}'].post;

  assert.equal(operation.operationId, 'validateContract');
  assert.deepEqual(operation.security, [{ browserSession: [] }, { oidc: [] }]);
  assert.equal(operation['x-browser-csrf-required'], 'conditional');
  assert.equal(operation['x-accept-policy'], 'application/json-or-wildcard');
  assert.deepEqual(operation['x-problem-codes-by-status'], {
    '400': ['REQUEST_INVALID', 'JSON_INVALID'],
    '401': ['AUTHENTICATION_REQUIRED'],
    '403': ['AUTHORIZATION_DENIED', 'CSRF_VALIDATION_FAILED'],
    '404': ['SCHEMA_NOT_FOUND', 'CONTRACT_VALIDATION_NOT_FOUND'],
    '406': ['NOT_ACCEPTABLE'],
    '409': ['IDEMPOTENCY_KEY_REUSED', 'COMMAND_IN_PROGRESS'],
    '412': ['VERSION_CONFLICT'],
    '415': ['UNSUPPORTED_MEDIA_TYPE'],
    '422': ['DOCUMENT_SCHEMA_INVALID', 'VERSION_LIMIT_REACHED'],
  });
  assert.deepEqual(
    Object.keys(operation.responses).sort(),
    ['201', '400', '401', '403', '404', '406', '409', '412', '415', '422', 'default'],
  );
  assert.equal(operation.responses['201'].headers.ETag.required, true);
  for (const status of ['400', '401', '403', '404', '406', '409', '412', '415', '422']) {
    assert.equal(operation.responses[status].$ref, '#/components/responses/ProblemResponse');
  }
  const refs = operation.parameters.map((entry) => entry.$ref).filter(Boolean);
  assert.ok(refs.includes('#/components/parameters/IdempotencyKey'));
  assert.ok(refs.includes('#/components/parameters/ExpectedVersion'));
  assert.ok(refs.includes('#/components/parameters/BrowserCsrfToken'));
});
```

Update `contracts/openapi/accord-control-api.yaml` so the operation contains these exact policy
fields and responses:

```yaml
    post:
      summary: Validate a structured contract document
      operationId: validateContract
      security:
        - browserSession: []
        - oidc: []
      x-browser-csrf-required: conditional
      x-accept-policy: application/json-or-wildcard
      x-problem-codes-by-status:
        '400': [REQUEST_INVALID, JSON_INVALID]
        '401': [AUTHENTICATION_REQUIRED]
        '403': [AUTHORIZATION_DENIED, CSRF_VALIDATION_FAILED]
        '404': [SCHEMA_NOT_FOUND, CONTRACT_VALIDATION_NOT_FOUND]
        '406': [NOT_ACCEPTABLE]
        '409': [IDEMPOTENCY_KEY_REUSED, COMMAND_IN_PROGRESS]
        '412': [VERSION_CONFLICT]
        '415': [UNSUPPORTED_MEDIA_TYPE]
        '422': [DOCUMENT_SCHEMA_INVALID, VERSION_LIMIT_REACHED]
      parameters:
        - name: validationId
          in: path
          required: true
          schema: { type: string, format: uuid }
        - $ref: '#/components/parameters/IdempotencyKey'
        - $ref: '#/components/parameters/ExpectedVersion'
        - $ref: '#/components/parameters/BrowserCsrfToken'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ContractValidationRequest'
      responses:
        '201':
          description: Validation result persisted
          headers:
            ETag:
              required: true
              schema: { type: string, pattern: '^"(0|[1-9][0-9]*)"$' }
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ContractValidationResponse'
        '400': { $ref: '#/components/responses/ProblemResponse' }
        '401': { $ref: '#/components/responses/ProblemResponse' }
        '403': { $ref: '#/components/responses/ProblemResponse' }
        '404': { $ref: '#/components/responses/ProblemResponse' }
        '406': { $ref: '#/components/responses/ProblemResponse' }
        '409': { $ref: '#/components/responses/ProblemResponse' }
        '412': { $ref: '#/components/responses/ProblemResponse' }
        '415': { $ref: '#/components/responses/ProblemResponse' }
        '422': { $ref: '#/components/responses/ProblemResponse' }
        default:
          $ref: '#/components/responses/ProblemResponse'
```

Define the optional CSRF header and tighten `If-Match` without weakening the existing idempotency
key contract:

```yaml
    ExpectedVersion:
      name: If-Match
      in: header
      required: true
      description: One canonical quoted non-negative aggregate sequence.
      schema: { type: string, pattern: '^"(0|[1-9][0-9]*)"$' }
    BrowserCsrfToken:
      name: X-CSRF-Token
      in: header
      required: false
      description: Required only for unsafe requests authenticated by browserSession.
      schema: { type: string, minLength: 32, maxLength: 256, pattern: '^[A-Za-z0-9._~-]+$' }
```

Extend `contracts/json-schema/problem-details.schema.json` with the exact integer bounds and closed
error-item shape from the authoritative table. Require base fields `type`, `title`, `status`, `code`,
UUID `correlation_id`, and `instance`; forbid `detail` explicitly. Retain document-level
`additionalProperties: true` only because the shared RFC 7807 schema permits extensions. Contract
tests enumerate every table row and prove the endpoint factory emits exactly the six base keys plus
that row's extensions, with byte-equal type/title/status/code for stored and unstored Problems.
Parse the same JSON Schema source a second time with the YAML parser's BigInt mode and assert both
64-bit maxima independently; JavaScript `Number` equality and a first-match source regex are not
precise enough to distinguish neighboring values at this magnitude:

```javascript
const exactSchema = YAML.parse(source, { intAsBigInt: true });
assert.equal(exactSchema.properties.expected_version.maximum, 9223372036854775807n);
assert.equal(
  exactSchema.properties.actual_version.anyOf[1].maximum,
  9223372036854775807n,
);
```

Run:

```powershell
corepack pnpm contracts:test
corepack pnpm contracts:lint
```

Expected RED: the test reports missing conditional CSRF metadata and missing explicit status
responses. Expected GREEN after the contract edit: both commands pass, the existing health and
Problem reuse assertions remain green, and every mutation still references idempotency,
ExpectedVersion, and the default Problem response.

- [ ] **Step 2: Drive strict raw-request parsing and the JCS fingerprint from unit tests**

Create `HttpIdempotencyFingerprintTest.java`. Its parameterized cases must prove all of the
following without starting Spring or PostgreSQL:

- Enumerate values only with `HttpServletRequest.getHeaders(name)` and enforce this exact visible
  singleton table. The shape stage short-circuits only counts greater than one; zero values continue
  to the named later stage. If a container has combined multiple wire occurrences into one visible singleton,
  the corresponding value parser rejects comma-bearing or otherwise invalid syntax without claiming
  knowledge of the original lines.

  | Header | 0 visible values | 1 visible value | >1 visible values |
  | --- | --- | --- | --- |
  | `Content-Type` | flow to media stage, then `UNSUPPORTED_MEDIA_TYPE` 415 | parse at media stage | `REQUEST_INVALID` 400 at shape stage |
  | `Idempotency-Key` | flow to its stage, then `REQUEST_INVALID` 400 | parse at its stage | `REQUEST_INVALID` 400 at shape stage |
  | `If-Match` | flow to its stage, then `REQUEST_INVALID` 400 | parse at its stage | `REQUEST_INVALID` 400 at shape stage |
  | `X-Correlation-ID` | generate a fresh UUID | parse canonical UUID | `REQUEST_INVALID` 400 at shape stage |
  | browser `Origin` | `CSRF_VALIDATION_FAILED` 403 | validate during CSRF | `CSRF_VALIDATION_FAILED` 403 |
  | browser `Sec-Fetch-Site` | `CSRF_VALIDATION_FAILED` 403 | validate during CSRF | `CSRF_VALIDATION_FAILED` 403 |
  | browser `X-CSRF-Token` | `CSRF_VALIDATION_FAILED` 403 | validate during CSRF | `CSRF_VALIDATION_FAILED` 403 |

- The one visible `Content-Type` value is case-insensitive `application/json` with no parameters;
  `application/*+json`, `text/json`, charset, comma-combined, and other invalid variants return `415`.
- The one visible `Idempotency-Key` value matches `[A-Za-z0-9._:-]{16,128}`; comma lists and invalid
  syntax return `400` at its stage.
- The one visible `If-Match` value is one canonical quoted decimal. Reject weak tags, lists,
  wildcard, signs, whitespace, leading zeroes, overflow, missing quotes, and comma-combined syntax.
- Accept is an RFC-combinable list header. Zero visible values normalize to `application/json`; one
  or more visible values are combined using HTTP comma-list semantics and canonicalized together.
  Bound total visible UTF-8 bytes to 2048 and total parsed ranges to 16. Cross-value and single-value
  forms with equal list semantics produce the same fingerprint. Each range has lowercase-normalized
  type/subtype and at most one optional `q`; all other parameters, duplicate media-ranges across any
  visible values, malformed tokens/q, or excess ranges fail `400`. Quality accepts only
  canonicalizable `0`, `1`, or values with at most three decimals; canonicalization omits `q=1`,
  strips trailing zeroes otherwise, and lexicographically sorts the complete distinct range list.
- Representation matching uses specificity `application/json` > `application/*` > `*/*`; the most
  specific present matching range determines quality. Thus exact `q=0` overrides both positive
  wildcards, and `application/*;q=0` overrides `*/*;q=1`. No matching range or selected `q=0` yields
  `NOT_ACCEPTABLE` 406. The full canonical list enters the fingerprint, so ordering/q spelling may be
  equivalent while any semantic list change changes the fingerprint.
- This route rejects every non-empty query string because the OpenAPI operation declares no query
  parameters.
- The body limit is 1 MiB of raw bytes. Empty input, malformed UTF-8, duplicate object keys,
  multiple top-level values, invalid I-JSON Unicode, an array top level, unknown request fields, a
  non-object `document`, and an invalid `schema_id` fail before claim.
- Whitespace, object-member order, Unicode escape spelling, and valid number spelling that RFC 8785
  canonicalizes equivalently produce the same body digest and request fingerprint.
- Changing method, route key, validation UUID, canonical body, expected version, normalized Accept,
  API contract version, verified tenant, or verified actor changes the fingerprint.
- A table-driven precedence test injects two simultaneous faults at every adjacent boundary and
  proves the earlier result wins in this exact order: trusted authentication, authorization, browser
  CSRF, correlation/query/visible-singleton-header shape, Content-Type, Accept, Idempotency-Key, If-Match, raw
  byte limit/UTF-8/I-JSON/closed JSON. Every case performs zero claim/database writes. A malformed
  correlation ID is reflected nowhere: authenticated requests get `REQUEST_INVALID`; authentication
  or authorization failures generate a fresh correlation UUID and keep their security Problem.

Use these public/package-private shapes consistently in the implementation:

```java
public sealed interface FoundationVerifiedPrincipal
        permits FoundationVerifiedPrincipal.Bearer,
                FoundationVerifiedPrincipal.BrowserSession {
    UUID tenantId();
    String actorId();

    record Bearer(UUID tenantId, String actorId)
            implements FoundationVerifiedPrincipal {}

    record BrowserSession(
        UUID tenantId,
        String actorId,
        String sessionId,
        long sessionGeneration,
        String csrfBindingDigest
    ) implements FoundationVerifiedPrincipal {}
}

record ContractValidationRequest(URI schemaId, JsonNode document) {}

record FoundationMutationHeaders(
    String idempotencyKey,
    ExpectedVersion expectedVersion,
    String requestMediaType,
    String normalizedAccept
) {}
```

Keep `ContractValidationRequest` package-private in `ContractValidationJson.java` and
`FoundationMutationHeaders` package-private in `FoundationHttpRequestPolicy.java`. The compact
constructors of both principal records require a tenant, a bounded nonblank actor, and valid UTF-8;
the browser record additionally requires a bounded opaque session ID, a positive generation, and a
canonical `sha256:` binding digest.

`FoundationHttpRequestPolicy` enumerates only servlet-visible values with `getHeaders`, owns the exact
precedence, singleton table, media policy and combined Accept-list canonicalization, canonical
ETag/correlation parsing, query rejection, and the raw-byte limit. It contains no raw-socket or
wire-occurrence reconstruction claim. `ContractValidationJson` first calls
`CanonicalJson.canonicalize(rawBody)` and `CanonicalJson.sha256(rawBody)`, then parses the canonical
bytes with strict duplicate detection, `FAIL_ON_TRAILING_TOKENS`, and
`FAIL_ON_UNKNOWN_PROPERTIES`. The closed DTO uses JSON property `schema_id`; the schema identifier
must be an absolute HTTPS URI under `schemas.accord.inforvans.com`, and `document` must be an object.

`HttpIdempotencyFingerprint` hashes RFC 8785 canonical JSON for exactly this envelope:

```json
{
  "accept":"application/json",
  "actor_id":"actor-7",
  "api_contract_version":"0.1.0",
  "body_digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "expected_version":"0",
  "http_method":"POST",
  "normalized_path":"/v1/contract-validations/20000000-0000-0000-0000-000000000001",
  "normalized_query":"",
  "request_media_type":"application/json",
  "resource_id":"20000000-0000-0000-0000-000000000001",
  "route_key":"contract-validations.create",
  "tenant_id":"10000000-0000-0000-0000-000000000001"
}
```

The implementation must not build a delimiter-concatenated string and must not serialize the DTO
back to JSON before computing the body digest. It does not include authorization, cookie, CSRF,
correlation, trace, claim-owner, or lease values.

Run:

```powershell
./gradlew.bat :apps:control-plane:api:test --tests '*HttpIdempotencyFingerprintTest' --no-daemon
```

Expected RED: the request-policy, strict decoder, and fingerprint types do not exist. Expected GREEN:
the pure tests pass with no Docker dependency and the existing `CanonicalJsonTest` remains the
single implementation of RFC 8785/I-JSON rules.

- [ ] **Step 3: Add V003 with the business proof row and detached-result constraints**

Create `V003__foundation_http_reliability.sql`. The migration must use the existing
`accord_migrator` ownership, `accord_security.enforce_tenant_table`, and explicit privilege model.
Create this exact durable row shape; raw contract documents are not persisted:

```sql
SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE public.contract_validation (
    tenant_id uuid NOT NULL,
    validation_id uuid NOT NULL,
    aggregate_type varchar(64)
        GENERATED ALWAYS AS ('contract-validation') STORED,
    schema_id varchar(512) NOT NULL,
    document_digest char(71) NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.transaction_timestamp(),
    CONSTRAINT contract_validation_pkey PRIMARY KEY (tenant_id, validation_id),
    CONSTRAINT contract_validation_head_fkey
        FOREIGN KEY (tenant_id, aggregate_type, validation_id)
        REFERENCES public.aggregate_head (tenant_id, aggregate_type, aggregate_id),
    CONSTRAINT contract_validation_schema_id_known CHECK (
        schema_id ~ '^https://schemas[.]accord[.]inforvans[.]com/[A-Za-z0-9._~:/-]{1,448}$'),
    CONSTRAINT contract_validation_document_digest_format CHECK (
        document_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT contract_validation_version_positive CHECK (version >= 1),
    CONSTRAINT contract_validation_timestamps_ordered CHECK (updated_at >= created_at)
);

ALTER TABLE public.idempotency_result
    ADD CONSTRAINT idempotency_result_aggregate_binding_consistent CHECK (
        (aggregate_type IS NULL AND aggregate_id IS NULL AND aggregate_version IS NULL)
        OR
        (aggregate_type IS NOT NULL AND aggregate_id IS NOT NULL
          AND aggregate_version IS NOT NULL)
    ),
    ADD CONSTRAINT idempotency_result_detached_status_known CHECK (
        state <> 'COMPLETED'
        OR aggregate_type IS NOT NULL
        OR response_status IN (404, 412, 422)
    );
```

Install `accord_security.guard_contract_validation_version()` as a `SECURITY INVOKER` trigger.
On insert it must require the matching `aggregate_head.version = NEW.version`. On update it must
reject changes to tenant, validation ID, generated aggregate type, or creation time; require
`NEW.version = OLD.version + 1`; require the matching head to equal the new version; and replace
`NEW.updated_at` with `clock_timestamp()`. Revoke the function from `PUBLIC`, `accord_api`, and
`accord_worker`; trigger execution does not require a direct function grant.

Finish the migration with this exact policy and privilege direction:

```sql
SELECT accord_security.enforce_tenant_table(
    'public.contract_validation'::pg_catalog.regclass);

REVOKE ALL ON public.contract_validation
    FROM PUBLIC, accord_api, accord_worker;
GRANT SELECT, INSERT ON public.contract_validation TO accord_api;
GRANT UPDATE (schema_id, document_digest, version)
    ON public.contract_validation TO accord_api;
```

Create `FoundationHttpReliabilityMigrationTest.java` before the migration and assert:

1. the exact eight columns, types, nullability, primary key, composite foreign key, checks, trigger,
   owner, and indexes;
2. forced RLS with the sole canonical `tenant_isolation` policy;
3. the exact API table/column privileges above and zero worker/PUBLIC privileges;
4. cross-tenant reads and writes are concealed, identity columns cannot be updated, and no runtime
   role can delete;
5. direct inserts/updates with a missing or mismatched aggregate head/version fail;
6. completed detached 404/412/422 rows satisfy V003, while detached 201/400/409/415/500 rows fail;
7. aggregate binding is either all null or all non-null.

Because `PlatformMigrationTest` compares the exact V001 constraint set, add the two V003
`idempotency_result` constraints to its expected catalog rows and add negative inserts for partial
aggregate bindings and an unapproved detached status.

Run:

```powershell
./gradlew.bat :database:control-plane:test --tests '*FoundationHttpReliabilityMigrationTest' --no-daemon
./gradlew.bat :database:control-plane:test --tests '*PlatformMigrationTest' --no-daemon
```

Expected RED: V003 and `contract_validation` are absent. Expected GREEN: both suites pass against
PostgreSQL 17.5, including exact RLS/grant comparisons and every direct-SQL rejection.

- [ ] **Step 4: Extend the command gate with fenced persistent rejections**

Add this public API to `JooqCommandGate` without changing `Claim.RequestConflict`:

```java
public void completeRejection(
        DSLContext tx,
        CommandKey key,
        ClaimLease lease,
        StoredHttpResult result,
        Duration resultTtl)
```

It accepts only status `404`, `412`, or `422`. It performs the same input validation, row lock,
database `clock_timestamp()`, live owner/generation/token/exact-deadline fence, expiry calculation,
and one-row completion check as `complete`. It clears owner/token/deadline, stores exact status,
canonical safe headers and exact body, sets all aggregate binding columns to null, and has no
aggregate `EXISTS` clause. A zero-row update throws `IllegalStateException`, causing the caller's
transaction to roll back. Keep `complete` as the only API for success and retain its aggregate-head
`EXISTS` requirement.

Add focused tests to `JooqCommandGateTest` before implementation:

- each allowed status completes and reclaims as an exact `Claim.Replay`;
- status 201, 400, 401, 403, 406, 409, 415, and 500 is rejected before SQL;
- a partial aggregate binding cannot be persisted through raw SQL;
- natural expiry and takeover invalidate `completeRejection`;
- a rejected completion attempted with a stale fence rolls back a preceding transaction-local
  marker insert;
- two simultaneous rejection completions using the same lease allow exactly one commit.

Run:

```powershell
./gradlew.bat :apps:control-plane:modules:reliability:test --tests '*JooqCommandGateTest' --no-daemon
```

Expected RED: `completeRejection` does not compile. Expected GREEN: all prior claim, renewal, CAS,
success completion, replay-header, Unicode-bound, and race tests remain green, and the new detached
completion cases pass.

- [ ] **Step 5: Make trusted identity, conditional CSRF, and every early Problem fail closed**

Add the API dependencies through catalog aliases rather than unversioned scattered coordinates:

```toml
spring-boot-security = { module = "org.springframework.boot:spring-boot-starter-security" }
spring-security-test = { module = "org.springframework.security:spring-security-test" }
```

In `apps/control-plane/api/build.gradle`, add:

```groovy
implementation libs.spring.boot.jooq
implementation libs.spring.boot.security
implementation libs.json.schema.validator
runtimeOnly libs.postgresql
testImplementation libs.spring.security.test
testImplementation testFixtures(project(':database:control-plane'))
testImplementation libs.flyway.core
testImplementation libs.flyway.postgresql
testImplementation libs.testcontainers.junit
testImplementation libs.testcontainers.postgresql

tasks.named('processResources') {
    from(rootProject.file('contracts/json-schema/domain-event.schema.json')) {
        into 'accord/contracts'
    }
}
```

`ContractValidationJson` must build an immutable registry containing only the packaged
`/accord/contracts/domain-event.schema.json`, verify its checked-in `$id`, compile it as JSON Schema
2020-12 with format assertions enabled, and return `SCHEMA_NOT_FOUND` for every other URI. Network
fallback, redirects, filesystem lookup, and production use of `accord.repo-root` are forbidden.
Project schema failures into executable stable `errors[{field,reason}]` without copying validator
prose. Convert each validator instance location to an RFC 6901 pointer by escaping `~` as `~0` and
`/` as `~1`; represent the root as `$`. If the full pointer exceeds 512 Unicode code points, `field`
is its first 440 complete code points plus `#sha256:` plus the 64-character uppercase hexadecimal
SHA-256 of the full pointer's UTF-8 bytes, totaling exactly 512 code points.

Normalize the validator keyword to `reason` by inserting ASCII camel-case and punctuation
boundaries, collapsing them to underscores, uppercasing with locale-independent ASCII rules, and
prefixing `SCHEMA_`. If the keyword is empty or the prefixed result exceeds 64 characters, use
`SCHEMA_RULE_` plus the first 52 uppercase hexadecimal characters of the keyword UTF-8 SHA-256,
totaling exactly 64. Sort projected pairs by `field`, then `reason`; deduplicate exact pairs; then
take the first 128. Zero validator failures is never a 422. Tests cover root, `~`/`/` escaping,
supplementary Unicode/code-point truncation, both reason branches, sort/dedup/cap, and prove no
validator message/prose appears.

`FoundationHttpSecurity` must expose no request-header authentication converter. It accepts the
route only when Spring Security already holds a `FoundationVerifiedPrincipal`. A raw bearer header,
raw session cookie, forged tenant header, or ordinary string principal returns `401` or `403` and
cannot supply tenant/actor identity. The Foundation route therefore fails closed in production
until the Identity plan replaces this narrow port; only tests construct these principals directly.

For `FoundationVerifiedPrincipal.BrowserSession`, require one exact configured HTTPS `Origin`,
`Sec-Fetch-Site: same-origin`, and one `X-CSRF-Token`. Hash the submitted token, then JCS-hash the
closed binding `{tenant_id,actor_id,session_id,session_generation,token_digest}` and compare it to
`csrfBindingDigest` with `MessageDigest.isEqual`. For `Bearer`, CSRF is exempt only because the
trusted principal subtype proves the authentication channel. Configure Spring CSRF to ignore this
single route because the stronger binding filter owns it; do not globally disable Spring CSRF.
The route filter runs only after trusted authentication and authorization have succeeded, then
performs browser CSRF before any generic header/media/body policy or controller entry. Authentication
and access-denied handlers therefore retain precedence over malformed CSRF/correlation input.

`ProblemAdvice` and the security `AuthenticationEntryPoint`/`AccessDeniedHandler` use one
`FoundationProblemFactory`. It emits `application/problem+json` with absolute type URI, stable title,
status/code, request path without query, and a UUID correlation ID exactly from the authoritative
Problem table. The factory has one constructor/factory path per table row and asserts its exact key
set; it never emits `detail`. After security succeeds, accept one canonical `X-Correlation-ID` UUID
or return `REQUEST_INVALID`; security handlers presented with malformed input generate a new UUID
and never reflect it. Persisted 404/412/422 use the same factory bytes.

Keep `FoundationProblemFactory` and the closed exception types package-private in
`ProblemAdvice.java`; security handlers receive the factory as a bean and never duplicate Problem
serialization.

Add security/protocol cases first to `ContractValidationApiTest`. Parameterize browser and bearer
authentication, then assert missing/wrong Origin, missing/wrong/session-generation CSRF, stale
session binding, malformed headers, unsupported media, unacceptable representation, malformed JSON,
duplicate JSON keys, raw bearer, raw cookie, and forged tenant all return the exact Problem. After
each case query as administrator and assert zero rows in `idempotency_result`, `aggregate_head`,
`contract_validation`, `domain_event`, and `outbox_event`. A current same-origin browser token and a
trusted bearer principal reach the command service; bearer requires no CSRF header.

The stale-session test constructs a trusted principal with a new positive `sessionGeneration` but
the old generation's `csrfBindingDigest`; the otherwise valid old token must return exact
`CSRF_VALIDATION_FAILED` 403 with zero claim. Task 9 does not claim logout, revocation-list, or session
authority.

Run:

```powershell
./gradlew.bat :apps:control-plane:api:test --tests '*ContractValidationApiTest' --no-daemon
```

Expected RED: the route/security handlers are absent. The first GREEN checkpoint is reached when
all early-failure cases return the declared Problem and create no durable row, even before success
behavior is implemented.

- [ ] **Step 6: Implement the two-transaction command and prove replay, CAS, and rollback**

`FoundationTenantTransactions.write` is the only transaction helper. For every invocation it calls
`DSLContext.transactionResult`, executes
`SELECT set_config('app.tenant_id', ?, true)`, reads
`accord_security.current_tenant_id()`, compares it to the trusted principal tenant, and only then
invokes the supplied function with `configuration.dsl()`.

`FoundationHttpConfiguration` supplies singleton `JooqCommandGate` and `ReliableEventStore` beans.
Neither bean owns a datasource or transaction manager. `ContractValidationController` receives the
trusted principal, path UUID, raw body bytes, and servlet request; it applies request policy and
strict decoding, creates the fingerprint, and delegates. Its endpoint return type is exactly
`ResponseEntity<byte[]>`. It applies the persisted canonical safe-header map explicitly, encodes
`StoredHttpResult.body()` once with a strict UTF-8 encoder configured to report malformed/unmappable
input, and returns those bytes. No String converter, JSON converter, platform-default charset, or
reserialization may touch stored or replayed bodies.

Keep this response record package-private in `ContractValidationController.java` so its JSON names
remain explicit and independent of Java parameter naming:

```java
record ContractValidationResponse(
    @JsonProperty("validation_id") UUID validationId,
    boolean valid,
    @JsonProperty("document_digest") String documentDigest,
    long version
) {}
```

`ContractValidationCommandService` uses this exact order:

1. Construct `CommandKey(tenant, actor, "contract-validations.create", idempotencyKey)`.
2. Run transaction 1 and call `gate.claim` with the JCS fingerprint, configured process instance ID,
   and a two-minute lease; return exact replay immediately, map payload-free conflict to nonstored
   `IDEMPOTENCY_KEY_REUSED` 409, and map live claim to nonstored `COMMAND_IN_PROGRESS` 409 with the
   stable bounded `retry_after: 1` integer extension.
3. For `Claim.Acquired`, resolve the schema and evaluate the object locally outside a database
   transaction. Build the final 404 or 422 Problem body before transaction 2.
4. Run transaction 2. For schema 404 or document 422 call `completeRejection` and return the stored
   Problem. No aggregate version changes.
5. For a valid document call `gate.advance` with aggregate type `contract-validation`. The gate
   reads the actual version before handling `expected == Long.MAX_VALUE`; a missing row
   therefore still yields `VersionConflict(Long.MAX_VALUE, null)`, a smaller actual version yields
   the ordinary stale conflict, and an actual version equal to `Long.MAX_VALUE` yields the closed
   exhausted-version sentinel without evaluating `actual + 1`. In the transaction-2 catch, map
   `actual == null` to stored `CONTRACT_VALIDATION_NOT_FOUND` 404, then map
   `expected == actual == Long.MAX_VALUE` to stored `VERSION_LIMIT_REACHED` 422, and map every other
   conflict to stored `VERSION_CONFLICT` 412 with `expected_version` and `actual_version`. Catch only
   this domain exception; SQL, serialization, fence, and invariant failures escape and roll back.
   Every maximum-version branch completes or replays under the fence, never overflows, and never
   leaves the command in `STARTED`.
6. After successful CAS, insert version 1 or update exactly the prior expected validation version.
   Store only tenant, ID, canonical schema ID, document JCS digest, and new version; require exactly
   one affected row.
7. Append one `DomainEvent` and matching `OutboxMessage` with aggregate sequence equal to the new
   version, stable event type `contract-validation.completed`, schema version `1.0.0`, trusted actor,
   correlation/causation IDs, and canonical payload containing validation ID, schema ID, document
   digest, and version. The payload contains no document body, credential, token, or fingerprint.
8. Serialize `ContractValidationResponse(validation_id, true, document_digest, version)` once.
   Complete with status 201 and replay-safe headers `Content-Type: application/json` and quoted
   numeric `ETag`; return those same bytes only after transaction 2 commits.

`contract-validation.completed/1.0.0` is an explicit, versioned exact-integer payload policy.
`CanonicalJson.canonicalizePreservingExactIntegers` preserves `Long.MAX_VALUE` in the success
response, and `ReliabilityValues` selects that exact helper only when `DomainEvent` derives this
exact `eventType/schemaVersion` or an outbox/inbox message declares this exact `payloadSchema`.
The ordinary `CanonicalJson.canonicalize` and `sha256` APIs remain RFC 8785, every other reliability
payload schema remains RFC 8785, and `contract-validation.completed/1.0.1` returns to RFC 8785.
Tests prove the success response, domain event, and outbox payload retain the same exact long value,
while ordinary DomainEvent/OutboxMessage/InboxMessage payloads use standard number normalization;
the exact helper also retains the ordinary duplicate-key, single-root, and I-JSON Unicode rejection
rules.

Add the remaining integration and concurrency tests:

- create at `If-Match: "0"` returns 201, quoted ETag `"1"`, one validation/head/event/outbox row,
  and a completed command row;
- an identical retry returns byte-identical status, safe headers, and body without another business
  write; the assertion compares status, the canonical persisted safe-header map, and raw UTF-8 body
  bytes captured from `ResponseEntity<byte[]>`. Changed body, resource ID, expected version, or Accept under the same tenant/actor/key
  returns nonstored 409 and reveals no original fingerprint; another tenant or actor using the same
  key is isolated by `CommandKey`, never receives the first result, and owns an independent command;
- unknown local schema, missing positive-version target, stale target, and invalid document produce
  stored 404/404/412/422 respectively; after unrelated state advances, the original key still
  replays the original exact Problem bytes;
- maximum expected version has a closed three-state matrix: a missing target stores/replays 404, a
  smaller actual version stores/replays 412 with exact long extensions, and an actual version equal
  to `Long.MAX_VALUE` stores/replays `VERSION_LIMIT_REACHED` 422. Every result has null idempotency
  aggregate type/ID/version, and its stored status, canonical headers, and raw body are byte-equal
  to both the first response and replay. Missing rows remain absent; stale and exhausted aggregate
  heads and validations retain both their original version and `updated_at`; none emits an event or
  outbox row, overflows a long, or leaves a `STARTED` command;
- invalid document never advances an aggregate or emits an event;
- 32 distinct keys racing on the same expected version yield one 201 and 31 stored 412 results, one
  aggregate increment, one validation mutation, and one event/outbox pair;
- 32 identical-key requests produce at most one business execution; concurrent observers may see
  nonstored `COMMAND_IN_PROGRESS`, and every retry after completion replays the winner;
- a blocking test schema evaluator lets an independent connection observe committed `STARTED`
  before transaction 2 and zero business rows, proving the transactions are not nested;
- expiry or takeover before `complete` causes the HTTP execution to fail closed and rolls back the
  validation row, aggregate CAS, domain event, and outbox together;
- an unregistered URI under the allowed schema host returns stored `SCHEMA_NOT_FOUND`; an
  architecture assertion proves the HTTP package has no dependency on `HttpClient`, `RestClient`,
  `WebClient`, a Provider client, or any FT8 execution-claim API.

Run:

```powershell
./gradlew.bat :apps:control-plane:api:test --tests '*ContractValidationApiTest' --no-daemon
./gradlew.bat :apps:control-plane:modules:reliability:test --no-daemon
./gradlew.bat :database:control-plane:test --no-daemon
corepack pnpm contracts:test
corepack pnpm contracts:lint
```

Expected GREEN: every protocol/security failure is pre-claim, every acquired deterministic
business outcome in the integration matrix is durable and replayable as specified, only success
changes aggregate/business/event state, CAS races have one winner, and a lost fence rolls the full
transaction back.

- [ ] **Step 7: Configure production defaults and verify locked dependencies**

Expand API `application.yml` while retaining explicitly annotated Modulith detection:

```yaml
spring:
  application:
    name: accord-control-api
  modulith:
    detection-strategy: explicitly-annotated
  datasource:
    url: ${ACCORD_DB_URL:jdbc:postgresql://localhost:5432/accord}
    username: ${ACCORD_API_DB_USER:accord_api_login}
    password: ${ACCORD_API_DB_PASSWORD:local-api-only}
    hikari:
      connection-init-sql: SET ROLE ${ACCORD_DB_SESSION_ROLE:accord_api}
  flyway:
    enabled: false
server:
  port: 8080
  error:
    include-message: never
    include-binding-errors: never
    include-stacktrace: never
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      probes:
        enabled: true
accord:
  process:
    role: control-api
    instance-id: ${ACCORD_PROCESS_INSTANCE_ID:${random.uuid}}
  http:
    api-contract-version: 0.1.0
    command-result-ttl: PT24H
  security:
    allowed-origin: ${ACCORD_BROWSER_ORIGIN:https://app.accord.example}
```

Bootstrap the focused API lock and verification metadata together before any strict resolution,
because the new executable closure is not trusted yet. This first command is intentionally focused
and non-strict; immediately inspect only the newly introduced Spring Security, JDBC/Hikari,
PostgreSQL, JSON Schema, Flyway, and Testcontainers artifacts/signatures. Any unrelated coordinate or
unverifiable artifact stops the task. Only after that review may the repository-wide strict lock
writer run; then rerun the exact focused lock/metadata writer and require no byte change before the
read-only strict check:

```powershell
./gradlew.bat :apps:control-plane:api:dependencies --write-locks --write-verification-metadata sha256,pgp --no-configuration-cache --no-daemon
git diff -- apps/control-plane/api/gradle.lockfile gradle/verification-metadata.xml
./gradlew.bat resolveAndLockAll --write-locks --no-configuration-cache --no-daemon --dependency-verification=strict
$beforeRepeat = @(
  (Get-FileHash apps/control-plane/api/gradle.lockfile -Algorithm SHA256).Hash
  (Get-FileHash gradle/verification-metadata.xml -Algorithm SHA256).Hash
)
./gradlew.bat :apps:control-plane:api:dependencies --write-locks --write-verification-metadata sha256,pgp --no-configuration-cache --no-daemon
$afterRepeat = @(
  (Get-FileHash apps/control-plane/api/gradle.lockfile -Algorithm SHA256).Hash
  (Get-FileHash gradle/verification-metadata.xml -Algorithm SHA256).Hash
)
if (Compare-Object $beforeRepeat $afterRepeat -SyncWindow 0) { throw 'API lock/metadata writer is not stable' }
./gradlew.bat resolveAndLockAll --no-configuration-cache --no-daemon --dependency-verification=strict
./gradlew.bat :apps:control-plane:api:check :apps:control-plane:modules:reliability:check :database:control-plane:check --no-daemon --dependency-verification=strict
corepack pnpm contracts:test
corepack pnpm contracts:lint
```

Expected: only the API lock and genuinely new verification entries change; reliability does not gain
a Spring datasource or security dependency; the API executable still passes the control-plane
runtime boundary; the focused metadata bootstrap precedes every strict command; repeating that same
focused lock/metadata writer after the strict global lock produces no byte change; and all Task 9
tests pass.

- [ ] **Step 8: Commit the complete HTTP reliability proof**

Review the staged manifest so no Task 8 file or generated source is included, then commit
only the files listed by this task:

```powershell
git diff --check
git add docs/superpowers/plans/2026-07-24-accord-platform-foundation-plan.md apps/control-plane/modules/platform-kernel/src/main/java/com/inforvans/accord/platformkernel/CanonicalJson.java apps/control-plane/modules/platform-kernel/src/test/java/com/inforvans/accord/platformkernel/CanonicalJsonTest.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliabilityValues.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/DomainEvent.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxMessage.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxMessage.java apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliabilityValuesTest.java
git add contracts/openapi/accord-control-api.yaml contracts/json-schema/problem-details.schema.json tests/contracts/openapi-contract.test.mjs database/control-plane/migrations/V003__foundation_http_reliability.sql database/control-plane/src/test/java/com/inforvans/accord/database/FoundationHttpReliabilityMigrationTest.java database/control-plane/src/test/java/com/inforvans/accord/database/PlatformMigrationTest.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/JooqCommandGate.java apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqCommandGateTest.java gradle/libs.versions.toml apps/control-plane/api/build.gradle apps/control-plane/api/gradle.lockfile gradle/verification-metadata.xml apps/control-plane/api/src/main/resources/application.yml apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationVerifiedPrincipal.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationTenantTransactions.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationHttpRequestPolicy.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationJson.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/HttpIdempotencyFingerprint.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationHttpConfiguration.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationCommandService.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationController.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/FoundationHttpSecurity.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ProblemAdvice.java apps/control-plane/api/src/test/java/com/inforvans/accord/controlplane/http/HttpIdempotencyFingerprintTest.java apps/control-plane/api/src/test/java/com/inforvans/accord/controlplane/http/ContractValidationApiTest.java
git diff --cached --name-only
git commit -m "feat: prove enterprise HTTP command reliability"
```

Expected: the staged manifest contains only the exact Task 9 files; previously Task 8-owned shared
files appear only where this review requires a Task 9 modification, and unrelated Task 8 files
remain absent; the Task 9 plan file is present as this review-driven authoritative-plan
synchronization; the branch contains one reviewable HTTP reliability slice; and Task 10 begins
immediately after this section without modification.

### Task 10: Prove Durable Temporal Orchestration Over Production mTLS

**Goal:** Run an identifier-only reconciliation workflow with Temporal Java SDK `1.28.1` through
a production-shaped mTLS client and worker, prove deterministic replay from a committed history,
and prove a replacement worker recovers after the first worker JVM is killed without making any
Provider mutation call.

**Files:**
- Modify: `database/control-plane/migrations/V002__reliable_event_delivery.sql`
- Modify: `database/control-plane/src/test/java/com/inforvans/accord/database/ReliableDeliveryMigrationTest.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentSnapshot.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/JooqExternalIntentStore.java`
- Modify: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqExternalIntentStoreTest.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowRef.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationOutcome.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationObservationPort.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationActivities.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReadOnlyReconciliationActivity.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflow.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowImpl.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/TemporalConnectionProperties.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/TemporalRuntimeConfiguration.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/TemporalWorkerLifecycle.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/WorkerTenantTransactions.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/ProviderObservationPort.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/FencedReconciliationObservation.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowTest.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/reconciliation/FencedReconciliationObservationTest.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalMtlsTestServer.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalTestCertificates.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalMtlsIT.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowReplayTest.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationProbeServer.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkerChildMain.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationCrashRecoveryIT.java`
- Create: `apps/control-plane/worker/src/test/resources/temporal/reconciliation-workflow-v1.json`
- Create: `apps/control-plane/worker/src/test/resources/temporal/reconciliation-workflow-v1.provenance.json`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `apps/control-plane/worker/gradle.lockfile`
- Modify: `apps/control-plane/worker/src/main/resources/application.yml`
- Modify: `gradle/libs.versions.toml`
- Modify: `gradle/verification-metadata.xml`

Task 10 owns the first edit to the three shared worker files: `build.gradle`, `gradle.lockfile`, and
`application.yml`, including the baseline caller-owned datasource/jOOQ/PostgreSQL dependencies and
configuration required by production reconciliation. Task 12 starts from the committed Task 10
versions, reuses that database baseline without adding it again, and adds only scheduling/drain
dependencies and configuration without replacing the mTLS properties, fenced reconciliation, or
lifecycle. Do not implement Task 10 and the Task 12 worker-wiring step concurrently in one worktree.

**Authoritative invariants:**

1. Workflow, activity, and history inputs are exactly `ReconciliationWorkflowRef(UUID tenantId,
   UUID intentId)`. The ref rejects null values and is the only Temporal input DTO. FT8
   `ReconciliationLease`, a reference/command/authorization DTO, and mutable business state never
   enter a workflow/activity payload, heartbeat, result, failure, log, header, memo, or search
   attribute.
2. Workflow history contains only the ref, orchestration progress, retry metadata, and the closed
   `ReconciliationOutcome`; it contains no Provider fact. Temporal
   `ReconciliationObservationPort` accepts the ref and is the high-level boundary, and production
   final class `reconciliation.FencedReconciliationObservation` is its only runtime implementation.
   Provider adapters cannot implement, wrap, or replace that high-level port; they implement only
   the lower `ProviderObservationPort`. Every activity attempt must:
   (a) in tx1 install `ref.tenantId()` as tenant context, call FT8 `claimReconciliation`, and commit;
   (b) expose only state for `NotReconcilable`, expose no capability for `Missing`, and retain an
   `Acquired` `ReconciliationLease` only in that activity attempt's process memory; (c) perform the
   bounded read-only `ProviderObservationPort.observe(lease, PT2S)` with no JDBC transaction open;
   and (d) in tx2 reinstall tenant context and use that same in-memory lease. Terminal resolutions
   call `completeReconciliation` with canonical safe `DomainEvent`/`OutboxMessage` so terminal intent,
   event, and outbox commit atomically. `STILL_UNKNOWN` or a normalized retryable observation error
   calls `markReconciliationOutcomeUnknown`. A stale fence rolls tx2 back. Capability data is
   discarded when the attempt ends.
3. Reconciliation is observation-only. Production workflow/activity packages and their runtime
   dependency graph contain no Provider mutation port, generic Provider client, Provider SDK, or
   generic HTTP/gRPC client. Every integration path that executes an activity uses the same probe
   with separate `/observe` and `/mutate` counters and requires mutation count `0`.
4. Production accepts only a `grpcs://` endpoint plus a client certificate, PKCS#8 private key,
   trust certificate, expected server name, and allowed secret root. Blank, unreadable, malformed,
   mismatched, expired, untrusted, wrong-name, escaping, or over-permissioned material prevents a
   synchronous connection and polling; there is no plaintext, trust-all, or resolver fallback.
5. `TemporalWorkerLifecycle` is a Spring `SmartLifecycle` at phase `100`. Startup creates the stubs,
   proves connectivity synchronously within `rpc-timeout`, creates the client/factory/worker,
   registers implementations, and only then calls `WorkerFactory.start()`. Shutdown calls
   `worker.suspendPolling()`, `factory.shutdown()` plus bounded `awaitTermination`, and finally
   `stubs.shutdown()` plus bounded termination. `WorkflowClient` is not treated as closeable.
6. Workflow code uses only Temporal deterministic APIs. No JVM clock, random UUID, thread,
   filesystem, environment, Spring bean lookup, network call, or database call appears in workflow
   implementation code.
7. The crash test is not an in-process worker restart. A parent JVM owns the TLS Temporal service
   and probe counters, kills the first child with `Process.destroyForcibly()`, waits for its exit,
   starts a distinct replacement child, and observes completion from persisted Temporal history.
8. Integration tests use exactly `temporalio/server:1.28.1`, the source coordinate later incorporated
   unchanged by Task 13. A one-shot schema-setup container must finish successfully before a distinct
   real server container starts; `auto-setup`, `start-dev`, an in-process service, fake frontend, or
   TLS proxy does not satisfy Task 10. Tests resolve and record the coordinate's actual immutable
   repository digest. A registry-resolution failure, including the current local failure, is not
   GREEN and cannot be replaced with a guessed/cached digest. The real server frontend terminates and
   verifies mTLS and owns the test namespace.
9. `WorkerTenantTransactions` receives the worker process's caller-owned `DSLContext` backed by its
   caller-owned datasource and uses only `DSLContext.transactionResult`. Every call executes
   `set_config('app.tenant_id', tenantId, true)`, reads `accord_security.current_tenant_id()`, and
   verifies equality before invoking the callback with the transaction DSLContext. It owns no pool,
   datasource, transaction manager, implicit transaction, or retry policy.
10. `ProviderObservationPort` is read-only and has exactly
    `ReconciliationResolution observe(ReconciliationLease lease, Duration timeout)`. It exposes no
    mutation method and returns no raw Provider response. `FencedReconciliationObservation` always
    supplies the constant `Duration.ofSeconds(2)`; a Provider adapter cannot choose or extend it.

- [ ] **TDD prerequisite: expose safe intent scope before production reconciliation**

Because V002 is still unpublished, extend `ExternalIntentSnapshot` and
`load_external_intent_snapshot` in place with the exact non-sensitive `scopeType`/`scopeId` pair from
Task 8. Write the catalog and store tests first: `ReliableDeliveryMigrationTest` asserts exact OUT
order/types and unchanged ACL, while `JooqExternalIntentStoreTest` proves same/cross-tenant loads,
successor scope preservation, and unchanged null capability/provider columns on non-winning claims.
Runtime roles retain zero table/column SELECT on `external_call_intent`; the security-definer snapshot
routine remains the only scope source. RED is the changed exact snapshot expectation; GREEN requires
the SQL routine, Java record, and mapper to agree without adding a capability field.

Run the focused FT8 tests, then stage this unpublished-V002 fix separately before any Task 10 worker
implementation:

~~~powershell
./gradlew.bat :database:control-plane:test --tests '*ReliableDeliveryMigrationTest' :apps:control-plane:modules:reliability:test --tests '*JooqExternalIntentStoreTest' --no-daemon --dependency-verification=strict
$task10SafeScopePaths = @(
  'database/control-plane/migrations/V002__reliable_event_delivery.sql'
  'database/control-plane/src/test/java/com/inforvans/accord/database/ReliableDeliveryMigrationTest.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ExternalIntentSnapshot.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/JooqExternalIntentStore.java'
  'apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqExternalIntentStoreTest.java'
)
git add -- $task10SafeScopePaths
git diff --cached --name-only
git commit -m "fix: expose safe external intent scope"
~~~

- [ ] **Step 1: Write the RED workflow boundary and retry tests**

Create `ReconciliationWorkflowTest.java` with these exact assertions:

- `ReconciliationWorkflow.reconcile`, `ReconciliationActivities.observe`, and
  `ReconciliationObservationPort.observe` each have one parameter of type
  `ReconciliationWorkflowRef`; the record has exactly non-null `tenantId` and `intentId` UUIDs.
- The workflow and activity interfaces expose no other workflow/activity method.
- The first observation attempt throws retryable `OBSERVATION_UNAVAILABLE`; the second returns
  `CONVERGED`; the workflow retries once and returns the closed outcome without learning a lease or
  Provider fact. Non-retryable `RECONCILIATION_NOT_RECONCILABLE` and `RECONCILIATION_MISSING` each
  stop after one activity failure.
- ArchUnit scans main classes, bytecode references, module dependencies, and resolved runtime
  coordinates. It rejects `ExternalWritePermit`, `ExecutionClaim`, `JooqExternalIntentStore`, every
  Provider mutation/client/SDK type, and generic HTTP/gRPC clients anywhere under the temporal
  workflow/activity package; this is not a three-class blacklist. The production orchestrator lives
  outside that package under `worker.reconciliation`.

Use these production signatures consistently:

~~~java
public enum ReconciliationOutcome {
    CONVERGED,
    CONFIRMED_NO_EFFECT,
    DIVERGED,
    STILL_UNKNOWN
}

public record ReconciliationWorkflowRef(UUID tenantId, UUID intentId) {
    public ReconciliationWorkflowRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
    }
}

@FunctionalInterface
public interface ReconciliationObservationPort {
    ReconciliationOutcome observe(ReconciliationWorkflowRef ref);
}

@ActivityInterface
public interface ReconciliationActivities {
    @ActivityMethod
    ReconciliationOutcome observe(ReconciliationWorkflowRef ref);
}

@WorkflowInterface
public interface ReconciliationWorkflow {
    @WorkflowMethod
    ReconciliationOutcome reconcile(ReconciliationWorkflowRef ref);
}

@FunctionalInterface
public interface ProviderObservationPort {
    ReconciliationResolution observe(ReconciliationLease lease, Duration timeout);
}
~~~

Run:

~~~powershell
./gradlew.bat :apps:control-plane:worker:test --tests '*ReconciliationWorkflowTest' --no-daemon
~~~

Expected RED: compilation fails because the temporal types do not exist. No database or Docker
service is needed for this first failure.

- [ ] **Step 2: Add the deterministic workflow and production fenced observation**

Implement `ReadOnlyReconciliationActivity` as a final class with one non-null high-level
`ReconciliationObservationPort`; its `observe(ReconciliationWorkflowRef)` method delegates exactly
once and has no capability-bearing field or other port. Production final class
`FencedReconciliationObservation implements ReconciliationObservationPort`; runtime configuration
constructs it from `WorkerTenantTransactions`, FT8 `JooqExternalIntentStore`, and exactly one
`ProviderObservationPort`. No Provider adapter or integration test supplies an alternate high-level
implementation. The activity heartbeats only `ref.intentId()` immediately before and after its one
high-level port invocation. Implement
`ReconciliationWorkflowImpl` with one activity stub
using a 30-second schedule-to-close timeout, ten-second start-to-close timeout, three-second
heartbeat timeout, one-second initial retry, coefficient `2.0`, eight-second maximum interval, and
five maximum attempts. Treat `RECONCILIATION_NOT_RECONCILABLE` and `RECONCILIATION_MISSING` as
non-retryable. Tests prove heartbeat details contain only the intent UUID, never tenant/capability/
Provider facts, and that missed heartbeats make the attempt eligible for prompt reassignment.

Create `FencedReconciliationObservationTest.java` first with the real FT8 PostgreSQL fixture and
worker login/role. For every attempt, assert this exact production sequence:

1. `WorkerTenantTransactions` opens tx1 through the injected caller `DSLContext.transactionResult`,
   installs and reads back transaction-local tenant context, calls `claimReconciliation`, and only
   for `Acquired` loads the safe snapshot scope/root identifier in that same transaction. It commits
   before any Provider observation.
2. `Missing` returns no capability; `NotReconcilable` exposes only its state. Both fail closed with
   their normalized non-retryable code and make zero low-level observation calls.
3. Only `Acquired` retains the lease plus the minimal safe snapshot `scopeType`, `scopeId`, and
   `rootIntentId` finalization context in that activity invocation's memory. The low-level port
   receives `(lease, PT2S)` after tx1 closes; a connection/transaction probe proves no JDBC
   transaction is open, and its `/mutate` count remains zero.
4. Terminal `ReconciliationResolution` opens tx2, reinstalls/verifies tenant context, and calls
   `completeReconciliation` with one canonical `DomainEvent`/`OutboxMessage`. The activity process
   generates `eventId`; it never enters Temporal input/history/heartbeat/result/log. Event time comes
   from tx2 PostgreSQL `clock_timestamp()`. Set scope from the tx1 snapshot, `aggregate_type` to
   `external_intent`, `aggregate_id` to `ref.intentId()`, `sequence` to `lease.generation()`,
   `event_type` to `external_intent.completed`, schema version to `1.0.0`, `causation_id` to
   `eventId`, `correlation_id` to snapshot `rootIntentId`, and actor to the bounded configured process
   instance ID. Its canonical payload is exactly `{intent_id,outcome}`. The outbox destination is
   `external-intents`, payload schema is `external-intent.event/1.0`, and its payload is the same
   closed identifiers/outcome. Neither payload contains lease or Provider facts. The database
   completion routine revalidates scope against the intent row together with the full lease fence;
   terminal intent, event, and outbox commit atomically.
5. `STILL_UNKNOWN` and a normalized retryable low-level error each open tx2 with the same in-memory
   lease and call `markReconciliationOutcomeUnknown`. The retryable error is rethrown only after that
   commit. A stale lease at either completion path rolls back tx2 and exposes no capability.

The test also proves rollback after each tx2 write, exact safe-event bytes, all terminal resolution
mappings, no mutation API on `ProviderObservationPort`, `FencedReconciliationObservation` as the sole
production `ReconciliationObservationPort` implementation, and zero Provider mutation calls.

Run:

~~~powershell
./gradlew.bat :apps:control-plane:worker:test --tests '*ReconciliationWorkflowTest' --tests '*FencedReconciliationObservationTest' --no-daemon
~~~

Expected GREEN: retryable observation runs twice, non-retryable observation runs once, workflow
input is only `ReconciliationWorkflowRef`, every acquired attempt follows the fenced transaction
sequence through the production orchestrator, safe event scope comes from PostgreSQL snapshot rather
than the lease/Provider adapter, no capability leaves process memory, and every case reports mutation
count zero.

- [ ] **Step 3: Write RED production mTLS configuration tests**

Create `TemporalMtlsIT.java`. Add test-only Bouncy Castle `bcpkix-jdk18on` `1.81` through the version
catalog. `TemporalTestCertificates` uses it to create an ephemeral CA, a server certificate for
`temporal.test`, and distinct trusted, untrusted, expired, wrong-EKU, wrong-SAN, and key-mismatched
cases under the JUnit temporary directory; no private key is checked into the repository.

`TemporalMtlsTestServer` starts PostgreSQL 17.5, runs an explicit one-shot schema setup to successful
exit, and then starts a distinct real server from exactly `temporalio/server:1.28.1` on a private
Testcontainers network. It resolves the source through the registry, obtains the actual repository
digest from Docker inspection, and rejects missing, tag-only, cached-but-unresolved, or fabricated
identity; the current local registry-resolution failure therefore blocks GREEN. Capture mode writes
both the exact coordinate and actual digest to `reconciliation-workflow-v1.provenance.json`; once
committed, both mTLS and replay tests require byte-equal coordinate and digest. Mount an ephemeral Temporal server
configuration that enables TLS on the real frontend listener, requires and verifies client
certificates, uses the generated server key/certificate and CA, and creates one fixed test namespace.
Readiness is the actual frontend gRPC health/namespace response over trusted mTLS. The helper rejects
`TestWorkflowEnvironment`, an in-process service, a fake frontend, or a separate TLS-terminating proxy.

The trusted case and every other integration case that can dispatch an activity use
production `FencedReconciliationObservation`; `ReconciliationProbeServer` is wired only through a
test-owned low-level `ProviderObservationPort`. They assert the `/mutate` counter is zero before
teardown. TLS-negative cases also prove neither probe endpoint is reached. No test implements the
high-level `ReconciliationObservationPort`.

Test this matrix with real `WorkflowServiceStubs`, `WorkflowClient`, `WorkerFactory`, and `Worker`:

| Case | Required result |
| --- | --- |
| trusted client, trusted CA, `temporal.test` | worker polls and one workflow completes |
| blank endpoint, namespace, queue, server name, secret root, or path | binding fails before stubs exist |
| non-`grpcs`, user-info, path, query, fragment, missing host, or missing port endpoint | URI validation fails before stubs exist |
| no, untrusted, wrong-EKU, or wrong-CA client certificate | synchronous TLS connect fails before polling |
| wrong trust CA or wrong expected SAN/server name | synchronous TLS connect fails before polling |
| expired client or server certificate | synchronous TLS connect fails before polling |
| client certificate with a different PKCS#8 key | TLS context construction fails before stubs exist |
| missing, unreadable, empty, oversized, or malformed certificate/key/trust file | setup fails before stubs exist |
| symlink projection resolving outside allowed root, loop, or broken link | path validation fails before any read |
| valid Kubernetes `..data` symlink projection inside allowed root | resolved files are accepted |
| insecure private-key owner/mode or ACL for the current platform | setup fails before stubs exist |
| non-positive RPC/shutdown timeout | property binding fails before stubs exist |

Before the first GREEN mTLS run, add the Temporal and Bouncy Castle build coordinates and run the
metadata-generation command from Step 7, then review only those new artifacts/signatures. Do not
weaken dependency verification or attempt strict lock resolution before their metadata exists.

Run:

~~~powershell
./gradlew.bat :apps:control-plane:worker:test --tests '*TemporalMtlsIT' --no-daemon
~~~

Expected RED: `TemporalConnectionProperties`, the TLS stubs factory, and lifecycle are absent.

- [ ] **Step 4: Implement the production TLS client, worker, and lifecycle**

Bind the worker's baseline datasource and required Temporal properties in Task 10:

~~~yaml
spring:
  datasource:
    url: ${ACCORD_DB_URL:jdbc:postgresql://localhost:5432/accord}
    username: ${ACCORD_WORKER_DB_USER:accord_worker_login}
    password: ${ACCORD_WORKER_DB_PASSWORD:local-worker-only}
    hikari:
      connection-init-sql: SET ROLE ${ACCORD_DB_SESSION_ROLE:accord_worker}
  flyway:
    enabled: false
accord:
  temporal:
    endpoint: ${ACCORD_TEMPORAL_ENDPOINT}
    namespace: ${ACCORD_TEMPORAL_NAMESPACE}
    task-queue: ${ACCORD_TEMPORAL_RECONCILIATION_TASK_QUEUE}
    server-name: ${ACCORD_TEMPORAL_SERVER_NAME}
    secret-root: ${ACCORD_TEMPORAL_SECRET_ROOT}
    client-certificate: ${ACCORD_TEMPORAL_CLIENT_CERTIFICATE}
    client-private-key: ${ACCORD_TEMPORAL_CLIENT_PRIVATE_KEY}
    trust-certificate: ${ACCORD_TEMPORAL_TRUST_CERTIFICATE}
    rpc-timeout: PT5S
    shutdown-timeout: PT20S
~~~

Parse the endpoint once as a URI and require scheme `grpcs`, a host and explicit port, and no user
info, path, query, or fragment. Pass only the validated `host:port` authority to
`WorkflowServiceStubsOptions.setTarget`; enable HTTPS, install the exact client/trust `SslContext`,
and apply the validated server name through the channel authority override. Never pass
`grpcs://...` to gRPC name resolution and never retry with the raw endpoint.

Resolve the configured secret root and each Kubernetes Secret symlink chain with `toRealPath`, reject
loops/broken links, and require every resolved file to remain beneath the resolved root. Recheck a
bounded regular file before opening it and read at most 64 KiB. On POSIX, a private key is owned by
root or the effective user, any readable group is one of the process groups, and no unrelated-user
permission or group/other write bit is allowed; certificate/trust files forbid group/other writes.
On Windows, an ACL adapter accepts only the service identity, Administrators, and SYSTEM as owners,
rejects broad read/write grants on the private key, and rejects broad writes on certificates. Tests
exercise both permission adapters independent of the host OS. Error messages name only the property.

`TemporalRuntimeConfiguration` builds `WorkerTenantTransactions` from the process-owned `DSLContext`,
builds the final `FencedReconciliationObservation` around the configured low-level
`ProviderObservationPort`, and injects that concrete high-level implementation into
`ReadOnlyReconciliationActivity`. Startup fails when no low-level read-only adapter exists; it never
falls back to an alternate high-level implementation.

`TemporalWorkerLifecycle.start()` creates stubs, calls bounded synchronous
`WorkflowServiceStubs.connect(rpcTimeout)` and verifies the fixed namespace, then creates the client,
factory, and worker, registers only `ReconciliationWorkflowImpl` and the injected
`ReadOnlyReconciliationActivity`, calls `factory.start()`, and finally reports running. Its phase is
exactly `100`. `stop()` calls `worker.suspendPolling()`, `factory.shutdown()`, bounded
`factory.awaitTermination`, force-closes the factory only on timeout, then shuts down/awaits stubs
within the remaining deadline. A partial startup failure unwinds factory then stubs; it never tries
to start or close `WorkflowClient` independently.

Run:

~~~powershell
./gradlew.bat :apps:control-plane:worker:test --tests '*TemporalMtlsIT' --no-daemon
~~~

Expected GREEN: only the trusted matrix case starts a polling worker; all negative cases fail closed
at TLS/property setup and never invoke observation or mutation.

- [ ] **Step 5: Capture, inspect, commit, and replay one golden workflow history**

Create `ReconciliationWorkflowReplayTest.java` using
`WorkflowReplayer.replayWorkflowExecutionFromResource` against
`temporal/reconciliation-workflow-v1.json`. Add an opt-in
`TemporalMtlsIT.captureGoldenHistory` path that starts the real pinned server and production mTLS
client, runs one fixed workflow with one retryable observation failure and terminal `CONVERGED`,
fetches the persisted execution history from that same authenticated stubs instance, and exports
canonical protobuf JSON. Capture is reproducible only through:

~~~powershell
./gradlew.bat :apps:control-plane:worker:test --tests '*TemporalMtlsIT.captureGoldenHistory' -PaccordCaptureTemporalHistory=true --no-daemon --dependency-verification=strict
git diff -- apps/control-plane/worker/src/test/resources/temporal/reconciliation-workflow-v1.json apps/control-plane/worker/src/test/resources/temporal/reconciliation-workflow-v1.provenance.json
~~~

The capture also writes the closed provenance sidecar with SDK coordinate/version `1.28.1`, the SDK
artifact SHA-256 approved in `verification-metadata.xml`, exact server source coordinate
`temporalio/server:1.28.1` and its registry-resolved actual repository digest, namespace, task queue,
workflow ID/type, exact capture command, and history SHA-256. Tests recompute every value available
locally and reject a tag-only/cached-but-unresolved server identity, coordinate or digest mismatch,
history edit, unknown provenance field, or capture over a non-mTLS channel. Capture cannot become
GREEN until the source coordinate resolves to that actual digest.

The replay test parses JSON into Temporal's protobuf `History`, recursively visits every `Payload`
field in every event attribute/header/memo, reads `metadata.encoding`, and decodes every non-empty
`data` value with the matching SDK payload converter before inspecting structured content. Unknown
or opaque non-empty encodings fail. Every decoded payload position, including workflow/activity
input, result, failure, heartbeat, header, memo, and search attribute, rejects the
`ReconciliationLease` type/name and field names matching
`(?i)(credential|authorization|secret|token(?:_value)?|owner|generation|deadline|lease_until|global(?:_idempotency)?_key|provider(?:_request)?|installation|repository|operation|request(?:_reference)?(?:_type|_id|_version)?|request_digest|url|source|diff|raw_body)`.
There is no lease exception. The history-shape test also allowlists the two ref UUIDs, orchestration
metadata, and closed outcome so Provider facts cannot enter history under an innocuous field name.
Raw/base64 substring scans do not satisfy this inspection.

Run:

~~~powershell
./gradlew.bat :apps:control-plane:worker:test --tests '*ReconciliationWorkflowReplayTest' --no-daemon
~~~

Expected RED before the resource is added: the history resource is absent. Expected GREEN after the
captured history and provenance are added: `WorkflowReplayer` completes with no nondeterminism,
every payload is decoded and structurally inspected, provenance matches, and the capture probe's
mutation count is zero.

- [ ] **Step 6: Prove recovery after a child JVM is killed forcibly**

Create `ReconciliationCrashRecoveryIT.java`, `ReconciliationProbeServer.java`, and
`ReconciliationWorkerChildMain.java` with this exact sequence:

1. The parent starts the mTLS Temporal service and a loopback JDK HTTP probe with separate
   `/observe` and `/mutate` counters.
2. Child A creates the production TLS stubs/client/factory/worker from command-line paths and writes
   `READY\n` to stdout only after polling starts. Both children use production
   `FencedReconciliationObservation` plus the probe-backed low-level `ProviderObservationPort`.
3. The parent starts one workflow with a fixed `ReconciliationWorkflowRef`. Child A's attempt commits
   tx1 tenant-context installation plus FT8 claim, keeps the acquired lease only in its process
   memory, and issues the first `/observe` request outside any JDBC transaction; the activity's last
   heartbeat detail before the high-level port invocation is only the intent UUID. The handler
   increments its counter and blocks past the three-second heartbeat timeout.
4. The parent calls `destroyForcibly()`, waits at most ten seconds for Child A to exit, and verifies
   it did not run a shutdown hook completion marker.
5. The parent releases the blocked probe, starts Child B with the same namespace and task queue, and
   requires a replacement activity task within ten seconds. After the database reconciliation fence
   is reclaimable, Child B commits its own tx1 claim, observes outside JDBC, then commits tx2
   `completeReconciliation` plus event/outbox atomically; the original workflow ID returns
   `CONVERGED` within another twenty seconds.
6. The parent asserts observation count is at least two, mutation count is exactly zero, heartbeat
   details contain only the intent UUID, decoded history contains no capability or Provider fact,
   there is one terminal workflow execution/event/outbox result, and Child B exits cleanly after an
   explicit stop command.

Build the child command from `java.home/bin/java`, the current test runtime classpath, and fixed
arguments. Give the entire test one monotonic 45-second hard deadline; every wait consumes that
single budget, and `finally` forcibly terminates any surviving child before stopping containers.
Do not invoke a shell, wait for the ten-second start-to-close timeout when heartbeat expiry is
available, or infer success from process exit alone.

Run:

~~~powershell
./gradlew.bat :apps:control-plane:worker:test --tests '*ReconciliationCrashRecoveryIT' --no-daemon
~~~

Expected GREEN: Child A is forcibly terminated, Child B completes the same persisted execution,
observation is safely repeated, and `/mutate` remains untouched.

- [ ] **Step 7: Lock, verify, and commit Task 10 before Task 12 worker wiring**

Add version `bouncycastle = "1.81"` and library alias `bouncycastle-pkix` for
`org.bouncycastle:bcpkix-jdk18on` to `gradle/libs.versions.toml`. Add
`implementation libs.temporal.sdk`, `implementation libs.spring.boot.jooq`,
`runtimeOnly libs.postgresql`, `testImplementation libs.temporal.testing`,
`testImplementation libs.bouncycastle.pkix`, database control-plane test fixtures, Flyway core plus
PostgreSQL, and the existing JUnit/Testcontainers PostgreSQL dependencies to the worker build. These
are Task 10's baseline worker datasource/jOOQ/PostgreSQL dependencies; Task 12 must reuse them rather
than add duplicates. Bouncy Castle is test-only and absent from the runtime graph. Use the same
bootstrap order as Task 9: generate the focused worker lock and metadata together without strict
verification, immediately review only the worker lock/new dependency closure, then run the strict
global lock writer. Hash the worker lock plus metadata, repeat the exact focused combined writer and
require no byte change, then run strict read-only global resolution and fenced/full checks:

~~~powershell
./gradlew.bat :apps:control-plane:worker:dependencies --write-locks --write-verification-metadata sha256,pgp --no-configuration-cache --no-daemon
git diff -- apps/control-plane/worker/gradle.lockfile gradle/verification-metadata.xml
./gradlew.bat resolveAndLockAll --write-locks --no-configuration-cache --no-daemon --dependency-verification=strict
$workerBeforeRepeat = @(
  (Get-FileHash apps/control-plane/worker/gradle.lockfile -Algorithm SHA256).Hash
  (Get-FileHash gradle/verification-metadata.xml -Algorithm SHA256).Hash
)
./gradlew.bat :apps:control-plane:worker:dependencies --write-locks --write-verification-metadata sha256,pgp --no-configuration-cache --no-daemon
$workerAfterRepeat = @(
  (Get-FileHash apps/control-plane/worker/gradle.lockfile -Algorithm SHA256).Hash
  (Get-FileHash gradle/verification-metadata.xml -Algorithm SHA256).Hash
)
if (Compare-Object $workerBeforeRepeat $workerAfterRepeat -SyncWindow 0) { throw 'Worker lock/metadata writer is not stable' }
./gradlew.bat resolveAndLockAll --no-configuration-cache --no-daemon --dependency-verification=strict
./gradlew.bat :apps:control-plane:worker:test --tests '*FencedReconciliationObservationTest' --no-daemon --dependency-verification=strict
./gradlew.bat :apps:control-plane:worker:check --no-daemon --dependency-verification=strict
git diff --check
$task10Paths = @(
  'apps/control-plane/worker/build.gradle'
  'apps/control-plane/worker/gradle.lockfile'
  'apps/control-plane/worker/src/main/resources/application.yml'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowRef.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationOutcome.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationObservationPort.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationActivities.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReadOnlyReconciliationActivity.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflow.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowImpl.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/TemporalConnectionProperties.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/TemporalRuntimeConfiguration.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/TemporalWorkerLifecycle.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/WorkerTenantTransactions.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/ProviderObservationPort.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/reconciliation/FencedReconciliationObservation.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowTest.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/reconciliation/FencedReconciliationObservationTest.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalMtlsTestServer.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalTestCertificates.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalMtlsIT.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowReplayTest.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationProbeServer.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkerChildMain.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationCrashRecoveryIT.java'
  'apps/control-plane/worker/src/test/resources/temporal/reconciliation-workflow-v1.json'
  'apps/control-plane/worker/src/test/resources/temporal/reconciliation-workflow-v1.provenance.json'
  'gradle/libs.versions.toml'
  'gradle/verification-metadata.xml'
)
git add -- $task10Paths
git diff --cached --name-only
git commit -m "feat: prove durable Temporal reconciliation"
~~~

Expected: the safe-scope staging set plus `$task10Paths` equal the declared Task 10 files exactly.
Temporal resolves at `1.28.1`, Bouncy Castle `1.81` is test-only, strict resolution makes no
second metadata/lock change, `temporalio/server:1.28.1` resolves to the actual provenance digest, all
TLS, boundary, replay, and crash tests pass, the two staging manifests jointly match the declared
Task 10 list exactly, history contains no
lease/capability/Provider fact, and mutation count is zero in every
activity-executing test. Registry resolution failure remains non-GREEN.

### Task 11: Build The GitLab 19.1-First Isolated Webhook Edge

**Goal:** Authenticate GitLab 19.1 Standard Webhooks over exact raw bytes, allow an explicit and
strict legacy-token migration path, normalize only closed metadata, and atomically persist an edge
inbox plus outbox in a separately permissioned PostgreSQL database.

**Files:**
- Create: `contracts/events/provider-webhook-signal.schema.json`
- Create: `contracts/golden-fixtures/webhooks/gitlab-19.1-push.signal.json`
- Create: `database/webhook-edge/bootstrap/00-pre-flyway-roles.sql`
- Create: `database/webhook-edge/migrations/V001__webhook_inbox_outbox.sql`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/WebhookEdgeApplication.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/binding/WebhookVerificationMode.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/binding/WebhookBinding.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/binding/BindingResolver.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/binding/FileBindingResolver.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitLabWebhookHeaders.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitLabStandardWebhookVerifier.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitLabLegacyTokenVerifier.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitLabWebhookVerifier.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/ProviderWebhookSignal.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/VerifiedGitLabWebhook.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/WebhookRecordOutcome.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/WebhookInbox.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/WebhookHandler.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/inbox/PostgresWebhookInbox.java`
- Create: `apps/webhook-edge/src/main/resources/application.yml`
- Create: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/security/GitLabWebhookVerifierTest.java`
- Create: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/webhook/WebhookHandlerTest.java`
- Create: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/inbox/WebhookDatabaseFixture.java`
- Create: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/inbox/PostgresWebhookInboxTest.java`
- Create: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/WebhookEdgeBoundaryTest.java`
- Modify: `apps/webhook-edge/build.gradle`
- Modify: `apps/webhook-edge/gradle.lockfile`
- Modify: `gradle/verification-metadata.xml`

`settings.gradle` already includes `:apps:webhook-edge`; do not edit it. The package root is
`com.inforvans.accord.webhookedge`, matching the downstream Git Delivery plan. The edge must not
depend on `apps/control-plane/modules/**`, `database:control-plane`, control-plane test fixtures, or
a generated control-plane database package.

**Authoritative invariants:**

1. `STANDARD_REQUIRED` is the default. Standard verification decodes the configured signing token
   by removing `whsec_`, Base64-decoding 24-64 key bytes, and computing HMAC-SHA256 over
   `{webhook-id}.{webhook-timestamp}.{exact raw body}`.
2. `webhook-signature` is a space-delimited list. Accept at most eight well-formed `v1,<base64>`
   candidates, decode each to exactly 32 bytes, compare every candidate in constant time, and accept
   if at least one matches. Unknown versions do not match.
3. The allowed timestamp skew is exactly five minutes in either direction using an injected UTC
   `Clock`. Missing, duplicated, non-decimal, non-canonical, overflowed, stale, or future timestamps
   fail authentication before JSON parsing and persistence.
4. If any `webhook-signature` value is present, Standard verification is mandatory. A malformed,
   stale, untrusted, or mismatched signature never falls back to `X-Gitlab-Token`, even if that token
   is valid.
5. Legacy comparison is allowed only when the signature header is completely absent and the
   server-side binding mode is `LEGACY_ALLOWED`. The legacy header must occur exactly once and is
   compared to the configured secret in constant time. Request data cannot select the mode.
6. Read at most 2 MiB plus one byte. Size rejection occurs before verification; verification occurs
   before JSON parsing; immutable repository identity validation occurs before persistence.
7. The edge persists only bounded identifiers, event/ref/SHA metadata, raw-body SHA-256, timestamps,
   and the closed normalized signal. It never persists or logs the raw body, header map, URL,
   hostname, token, signature, commit message, author data, source, archive, or diff.
8. The first accepted delivery inserts one inbox row and one outbox row in one transaction. An
   identical retry returns the original acceptance with no second outbox row. Reusing a webhook ID
   with a different body digest returns `409` and does not change either stored row.

- [ ] **Step 1: Write RED Standard Webhooks and downgrade-matrix tests**

Create `GitLabWebhookVerifierTest.java` with fixed raw UTF-8 bytes, a fixed clock, one `whsec_`
signing token, and a distinct legacy token. Cover:

- exact HMAC success, raw-byte mutation failure, and valid signatures in first/middle/last position;
- malformed prefix, invalid Base64, decoded length other than 32, more than eight candidates, and no
  matching `v1` candidate;
- missing/duplicate `webhook-id`, timestamp, signature, and legacy header values;
- timestamp at exactly minus/plus five minutes succeeds; one second outside either bound fails;
- an omitted binding mode defaults to `STANDARD_REQUIRED` and cannot use a valid legacy token;
- `STANDARD_REQUIRED` plus no signature fails even with a valid legacy token;
- `LEGACY_ALLOWED` plus no signature and valid legacy token succeeds;
- any present invalid signature plus valid legacy token fails without invoking the legacy verifier;
- constant-time comparison receives all eligible candidates rather than stopping at the first match.

Use these closed modes:

~~~java
public enum WebhookVerificationMode {
    STANDARD_REQUIRED,
    LEGACY_ALLOWED
}
~~~

Run:

~~~powershell
./gradlew.bat :apps:webhook-edge:test --tests '*GitLabWebhookVerifierTest' --no-daemon
~~~

Expected RED: the binding and verifier types are absent.

- [ ] **Step 2: Implement strict GitLab 19.1 verification**

`GitLabStandardWebhookVerifier` owns signing-token decoding, canonical timestamp validation,
signature-list parsing, exact message construction, HMAC, and constant-time comparisons.
`GitLabLegacyTokenVerifier` owns only the single-header constant-time token comparison.
`GitLabWebhookVerifier` chooses the path from server-side `WebhookBinding`: signature present means
Standard; signature absent plus `LEGACY_ALLOWED` means legacy; all other combinations fail with one
normalized authentication error code.

`FileBindingResolver` reloads a read-only projected JSON file and accepts only binding ID, tenant
UUID, immutable repository ID, verification mode, signing token, and legacy token. It rejects an
unknown field, duplicate binding ID, invalid token shape, `STANDARD_REQUIRED` without a signing
token, or `LEGACY_ALLOWED` without a legacy token. A missing mode becomes `STANDARD_REQUIRED`; only
the explicit `LEGACY_ALLOWED` value enables legacy comparison. It never returns token text from
`toString`, an exception, or an actuator value.

Run the focused verifier test again. Expected GREEN: the complete Standard and downgrade matrix
passes with no JSON parser or database dependency.

- [ ] **Step 3: Write RED verify-before-parse and normalization tests**

Create `WebhookHandlerTest.java` with a recording inbox and parser. Prove:

- wrong signature over invalid JSON returns `401`, parser calls `0`, inbox calls `0`;
- 2 MiB succeeds when signed; 2 MiB plus one byte returns `413` before verifier/parser/store calls;
- valid signature plus malformed JSON returns `400` and stores nothing;
- body project ID different from the binding returns `403` and stores nothing;
- accepted GitLab push stores only provider `gitlab`, immutable repository ID, `webhook-id`,
  `X-Gitlab-Event`, `ref`, `before`, `after`, body digest, and observed time;
- payload URLs, commit arrays/messages/authors, user data, and unknown JSON fields never enter the
  normalized signal;
- duplicate/smuggled identity headers and a disagreement between `webhook-id` and
  `Idempotency-Key` fail before persistence;
- captured application logs for accepted and rejected requests contain none of the raw-body sentinel,
  URL, commit message, author, signing token, legacy token, signature, or header-map values.

`WebhookHandler` reads the servlet stream once into a bounded byte array, resolves the binding,
verifies, parses with a duplicate-key-rejecting Jackson mapper, checks the immutable project ID, and
then calls `WebhookInbox.record`. It returns `202` for accepted/identical duplicate and `409` for a
digest conflict.

Run:

~~~powershell
./gradlew.bat :apps:webhook-edge:test --tests '*WebhookHandlerTest' --no-daemon
~~~

Expected GREEN after implementation: verification always precedes parsing, and parsing always
precedes the single persistence call.

- [ ] **Step 4: Define and validate the closed normalized signal**

Create `provider-webhook-signal.schema.json` as JSON Schema 2020-12 with
`additionalProperties: false`. Require exactly `schema_version`, `tenant_id`, `scope_type`,
`scope_id`, `provider`, `immutable_repository_id`, `delivery_id`, `event_type`, `body_digest`, and
`observed_at`; allow optional nullable `ref`, `before_sha`, and `after_sha`. Fix `schema_version` to
`1.0.0`, `scope_type` to `repository`, provider to `gitlab`, digests to lowercase
`sha256:<64 hex>`, and SHAs to lowercase 40-64 hex.

Create `gitlab-19.1-push.signal.json` with fixed UUIDs, repository ID `77831`, delivery UUID,
`Push Hook`, `refs/heads/main`, fixed before/after SHAs, digest, and UTC timestamp. The fixture is the
normalized signal only.

Run:

~~~powershell
corepack pnpm exec ajv validate --spec=draft2020 -s contracts/events/provider-webhook-signal.schema.json -d contracts/golden-fixtures/webhooks/gitlab-19.1-push.signal.json
~~~

Expected: PASS. Adding `url`, `commits`, `message`, `token`, `headers`, or `raw_body` to a negative
copy fails schema validation.

- [ ] **Step 5: Write RED isolated database, RLS, and atomicity tests**

`WebhookDatabaseFixture` starts PostgreSQL 17.5, applies the edge bootstrap as container
administrator, migrates as `accord_webhook_migrator_login` after `SET ROLE accord_webhook_owner`,
and opens runtime connections as `accord_webhook_runtime_login` after
`SET ROLE accord_webhook_runtime`. It imports no control-plane fixture.

Create `PostgresWebhookInboxTest.java` and prove:

- one accepted request creates exactly one inbox and one outbox row;
- failure injected after inbox insert but before outbox insert rolls both back;
- 32 simultaneous identical deliveries converge to one inbox/outbox pair;
- same natural key plus changed digest returns conflict and preserves original bytes;
- tenant A cannot read, collide with, or mutate tenant B rows;
- missing transaction-local tenant context reads zero and cannot insert;
- runtime has only `SELECT, INSERT`, cannot update/delete/truncate/own/bypass RLS/set owner role;
- catalog and JSON scans find no raw body, URL, header, token, signature, credential, commit message,
  author, source, archive, or diff field.

Run the focused test. Expected RED: bootstrap, V001, and `PostgresWebhookInbox` are absent.

- [ ] **Step 6: Add V001 inbox/outbox and one atomic store**

`V001__webhook_inbox_outbox.sql` creates `webhook_inbox` keyed by
`(tenant_id, provider, immutable_repository_id, webhook_id)` and `webhook_outbox` keyed by
`(tenant_id, signal_id)` with a unique foreign-key-backed natural key to the inbox. The inbox stores
body digest, event type, and receive time; the outbox stores schema version, closed signal JSON, and
creation time. Both tables use bounded text/JSON constraints, forced tenant RLS, the single
canonical policy, owner `accord_webhook_owner`, and exact runtime `SELECT, INSERT` grants. Neither
table has an UPDATE/DELETE runtime path.

`PostgresWebhookInbox.record` installs and verifies transaction-local tenant context, inserts the
inbox, inserts the outbox only for the winning inbox insert, and handles uniqueness conflicts by
locking and comparing the existing digest. Serialization happens before SQL. Any exception rolls
back both inserts.

Run:

~~~powershell
./gradlew.bat :apps:webhook-edge:test --tests '*PostgresWebhookInboxTest' --no-daemon
~~~

Expected GREEN: role, RLS, atomicity, concurrency, digest-conflict, and no-sensitive-column tests
all pass against only the edge database.

- [ ] **Step 7: Configure and verify the isolated executable artifact**

Use Spring Boot web, actuator, jOOQ, Jackson, and PostgreSQL dependencies only. Package edge
bootstrap/migrations into the test resources from `database/webhook-edge`; do not add a Gradle
project dependency on any control-plane module or database fixture. Configure Flyway disabled at
runtime, the edge runtime login/session role, required binding-file path, actuator probes, and no
Provider content credential property.

`WebhookEdgeBoundaryTest` must scan main/test imports, runtime coordinates, and the boot jar. It
rejects `com.inforvans.accord.reliability`, `com.inforvans.accord.database`, any
`:apps:control-plane` project dependency, Git SDK/client libraries, and raw-body fixture resources in
the executable artifact.

Run:

~~~powershell
./gradlew.bat :apps:webhook-edge:dependencies --write-locks --no-configuration-cache --no-daemon --dependency-verification=strict
./gradlew.bat resolveAndLockAll --write-verification-metadata sha256,pgp --no-configuration-cache --no-daemon
./gradlew.bat :apps:webhook-edge:check :apps:webhook-edge:bootJar --no-daemon --dependency-verification=strict
corepack pnpm exec ajv validate --spec=draft2020 -s contracts/events/provider-webhook-signal.schema.json -d contracts/golden-fixtures/webhooks/gitlab-19.1-push.signal.json
git diff --check
~~~

Expected: the executable is independently deployable, contains no control-plane package, accepts
only authenticated bounded GitLab metadata, and persists no raw request material.

- [ ] **Step 8: Commit the isolated GitLab edge**

~~~powershell
$task11Paths = @(
  'contracts/events/provider-webhook-signal.schema.json'
  'contracts/golden-fixtures/webhooks/gitlab-19.1-push.signal.json'
  'database/webhook-edge/bootstrap/00-pre-flyway-roles.sql'
  'database/webhook-edge/migrations/V001__webhook_inbox_outbox.sql'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/WebhookEdgeApplication.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/binding/WebhookVerificationMode.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/binding/WebhookBinding.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/binding/BindingResolver.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/binding/FileBindingResolver.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitLabWebhookHeaders.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitLabStandardWebhookVerifier.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitLabLegacyTokenVerifier.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/security/GitLabWebhookVerifier.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/ProviderWebhookSignal.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/VerifiedGitLabWebhook.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/WebhookRecordOutcome.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/WebhookInbox.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/webhook/WebhookHandler.java'
  'apps/webhook-edge/src/main/java/com/inforvans/accord/webhookedge/inbox/PostgresWebhookInbox.java'
  'apps/webhook-edge/src/main/resources/application.yml'
  'apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/security/GitLabWebhookVerifierTest.java'
  'apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/webhook/WebhookHandlerTest.java'
  'apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/inbox/WebhookDatabaseFixture.java'
  'apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/inbox/PostgresWebhookInboxTest.java'
  'apps/webhook-edge/src/test/java/com/inforvans/accord/webhookedge/WebhookEdgeBoundaryTest.java'
  'apps/webhook-edge/build.gradle'
  'apps/webhook-edge/gradle.lockfile'
  'gradle/verification-metadata.xml'
)
git add -- $task11Paths
git diff --cached --name-only
git commit -m "feat: add GitLab Standard Webhook edge"
~~~

Expected: the staged set contains no control-plane file and no raw webhook body; the edge database
commit is independent from Tasks 10 and 12.

### Task 12: Dispatch Reliable Messages With Fair Permits And Durable Receipts

**Goal:** Add control-plane V004 and a non-web `control-worker` runtime that schedules tenants
fairly, acquires a tenant permit before any message lease, enforces complete database-time fences,
persists receipts around every effect, drains cleanly, and deletes only expired completed
idempotency results.

**Files:**
- Create: `database/control-plane/migrations/V004__reliability_coordination_and_fences.sql`
- Create: `database/control-plane/src/test/java/com/inforvans/accord/database/ReliabilityCoordinationMigrationTest.java`
- Modify: `apps/control-plane/modules/reliability/build.gradle`
- Modify: `apps/control-plane/modules/reliability/gradle.lockfile`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableEventStore.java`
- Modify: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliableEventStoreTest.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/TenantWorkPermit.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/MessageFence.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/LeasedEvent.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/LeasedInboxMessage.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/DeliveryReceipt.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/HandlerReceipt.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/TenantWorkRepository.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxRepository.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxRepository.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/EventTransport.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/TransactionalInboxHandler.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxDispatcher.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxDispatcher.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/IdempotencyResultCleaner.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/TenantWorkRepositoryTest.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/MessageFenceProperties.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/OutboxDispatcherTest.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/InboxDispatcherTest.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/IdempotencyResultCleanerTest.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/WorkerReliabilityProperties.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/WorkerScheduling.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/WorkerDrainCoordinator.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/WorkerSchedulingTest.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/WorkerDrainCoordinatorTest.java`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `apps/control-plane/worker/gradle.lockfile`
- Modify: `apps/control-plane/worker/src/main/resources/application.yml`
- Modify: `gradle/verification-metadata.xml`

Task 9 owns `V003__foundation_http_reliability.sql`; Task 12 must not rename, edit, or duplicate it.
V004 migrates a database that already contains V001, FT8 V002, and Task 9 V003. Task 10 must be
committed before the Task 12 worker files are changed, because both tasks own the worker build,
lock, YAML, and lifecycle ordering. Task 12 reuses Task 10's datasource, jOOQ, PostgreSQL,
`WorkerTenantTransactions`, and production fenced reconciliation wiring; it neither adds duplicate
database dependencies nor alters Task 10 TLS/fence semantics.

**Authoritative invariants:**

1. `reliability_tenant_work` is the sole unscoped cross-tenant scheduling directory. It contains
   tenant UUID, readiness/fairness timestamps, and permit fence fields only. API can signal only its
   transaction tenant through a parameter-free security-definer function; API cannot read or
   mutate the table directly.
2. Fair acquisition orders ready tenants by `last_granted_at NULLS FIRST`, then `available_at`, then
   tenant UUID, and uses `FOR UPDATE SKIP LOCKED`. Finishing a bounded turn updates
   `last_granted_at`, so a continuously hot tenant cannot starve another ready tenant.
3. A live `TenantWorkPermit` is acquired before transaction-local tenant context is installed for a
   message operation and before any outbox/inbox query or lease. No code path scans message tables
   to discover a tenant.
4. Tenant permit and message fences each contain owner, monotonically increasing generation, opaque
   UUID token, and exact database deadline. Acquire, renew, receipt, acknowledge, fail, reschedule,
   release, and takeover compare tenant/natural key, state, owner, generation, token, exact prior
   deadline, and `deadline > clock_timestamp()` where the operation requires a live lease.
5. V004 drops the V002 all-update-blocking triggers only while replacing them with strict outbox and
   inbox transition guards. Runtime receives column-level UPDATE grants for lifecycle columns only;
   it receives no table ownership, trigger bypass, TRUNCATE, or message DELETE privilege.
6. Outbox leasing commits before `EventTransport.deliver`. The Provider/downstream call runs with no
   JDBC transaction open. A successful call returns a bounded `DeliveryReceipt`; the worker commits
   that receipt under the full fence before a separate fenced acknowledgement. Recovery with a
   valid stored receipt acknowledges without another external call. A crash after external success
   but before receipt persistence relies on the event ID downstream idempotency key.
7. `TransactionalInboxHandler.handle(DSLContext, LeasedInboxMessage)` performs database/domain work
   only. Handler domain mutations, domain event, outbox, handler receipt, and `COMPLETED` transition
   commit in one tenant transaction under the full permit/message fences. A fence loss or any
   exception rolls all of them back. No network call occurs inside this transaction.
8. Worker shutdown first rejects new permits and makes readiness false, then drains bounded in-flight
   work while renewing live fences when needed, then releases unstarted work, and only afterward
   stops Task 10 Temporal polling and infrastructure. It never acknowledges an interrupted,
   expired, or unreceipted effect.
9. Worker has no direct `DELETE` on `idempotency_result`. A bounded security-definer function uses
   database time and the installed tenant context to delete only rows with `state='COMPLETED' AND
   expires_at < clock_timestamp()`. It never deletes `STARTED`, regardless of lease or expiry.

- [ ] **Step 1: Write RED V004 migration and privilege tests**

Create `ReliabilityCoordinationMigrationTest.java`. Migrate explicitly through V003, then V004, and
assert:

- Flyway history contains successful V001, V002, V003, V004 in order with one row per version;
- V002 checksums are unchanged;
- `reliability_tenant_work`, `outbox_delivery_receipt`, and `inbox_handler_receipt` have exact
  columns, keys, bounded constraints, ownership, and indexes;
- V002 `outbox_v002_immutable` and `inbox_v002_immutable` are absent, and exactly one named V004
  transition trigger protects each message table;
- outbox/inbox include independent `lease_generation`, `lease_token`, owner, and deadline fields;
- receipt tables are forced-RLS and append-only;
- worker has exact SELECT/INSERT plus lifecycle-column UPDATE grants and no message DELETE;
- worker direct DELETE on `idempotency_result` is revoked and only the bounded cleanup function is
  executable;
- API can execute tenant work signal but has no direct tenant-directory or receipt-table grant.

Run:

~~~powershell
./gradlew.bat :database:control-plane:test --tests '*ReliabilityCoordinationMigrationTest' --no-daemon
~~~

Expected RED: V004 and all coordination/receipt objects are absent.

- [ ] **Step 2: Add V004 state machines, permits, receipts, and cleanup authority**

`V004__reliability_coordination_and_fences.sql` must perform these operations in this order:

1. Set bounded lock and statement timeouts and assert V003's `contract_validation` table exists.
2. Add message generation/token columns and constraints without changing V002 data meaning.
3. Create the payload-free fair tenant directory and its ready/fairness index.
4. Create append-only outbox delivery and inbox handler receipt tables with tenant-first foreign
   keys and one receipt per message natural key.
5. Drop the two V002 immutable message triggers and install strict transition functions/triggers.
6. Apply forced tenant RLS to both receipt tables before grants.
7. Revoke all table privileges, then grant only the exact API/worker matrix.
8. Revoke worker `DELETE` on `idempotency_result`; create the bounded completed-result cleanup
   function with fixed search path and grant only its execution.

The cleanup function accepts `batch_size` in `1..500`, selects the current tenant's eligible rows in
`expires_at`/primary-key order with `FOR UPDATE SKIP LOCKED`, deletes by the selected primary keys,
and returns the deleted count. Its SQL predicate contains both `state='COMPLETED'` and
`expires_at < clock_timestamp()`; there is no predicate branch for `STARTED`.

The outbox guard permits only `PENDING -> DELIVERING`, expired `DELIVERING -> DELIVERING` takeover,
live `DELIVERING -> DELIVERING` deadline extension, `DELIVERING -> PENDING`, `DELIVERING -> DEAD`,
and receipted `DELIVERING -> DELIVERED`. The inbox guard permits the corresponding
`PENDING/PROCESSING/DEAD/COMPLETED` transitions and requires a matching handler receipt before
`COMPLETED`. Terminal rows cannot be reopened or deleted.

Run the focused migration test again. Expected GREEN: exact catalog, state-machine, RLS, grant, and
cleanup-authority assertions pass.

- [ ] **Step 3: Write RED fair permit-first and complete-fence tests**

Create `TenantWorkRepositoryTest.java` using worker login/role for runtime SQL. Prove:

- three ready tenants are granted in round-robin fairness order even when tenant A is continuously
  re-signaled;
- 32 concurrent workers claim disjoint live permits with `SKIP LOCKED`;
- takeover increments generation and changes token even when owner text is reused;
- old owner/generation/token/deadline cannot renew, release, reschedule, or lease a message;
- API signal derives tenant from transaction context and cannot name or inspect another tenant;
- an SQL listener fails the test if outbox/inbox is touched before a live permit is acquired and
  the exact tenant context is installed.

Create `MessageFenceProperties.java` with jqwik properties for positive bounded durations,
monotonic generation, token replacement, exact-deadline comparison, deterministic retry jitter,
backoff cap, and stale-fence rejection.

Run:

~~~powershell
./gradlew.bat :apps:control-plane:modules:reliability:test --tests '*TenantWorkRepositoryTest' --tests '*MessageFenceProperties' --no-daemon
~~~

Expected RED: permit/fence records and repositories are absent.

- [ ] **Step 4: Implement fair permits and fenced message repositories**

Use these immutable fence shapes:

~~~java
public record MessageFence(
    String owner,
    long generation,
    UUID token,
    OffsetDateTime leaseUntil
) {}

public record TenantWorkPermit(
    UUID tenantId,
    MessageFence fence
) {}
~~~

`TenantWorkRepository.acquire` uses one atomic CTE ordered by fairness fields and computes deadline
from `clock_timestamp()`. `OutboxRepository.lease` and `InboxRepository.lease` require a
`TenantWorkPermit`, install and verify its tenant context as the first SQL in a new transaction,
revalidate the live permit, then lease at most the configured batch size. No repository owns a pool
or opens a connection.

All lifecycle SQL uses database time. JVM `Clock` may format telemetry but cannot calculate a lease,
expiry decision, retry eligibility, or cleanup eligibility.

Run the focused permit/property tests. Expected GREEN: fair ordering, permit-first SQL order,
database-time deadlines, and every stale-fence case pass.

- [ ] **Step 5: Write RED durable outbox receipt and crash-cut tests**

Create `OutboxDispatcherTest.java` with a counting idempotent transport. Inject failures at these
boundaries:

| Boundary | Required recovery |
| --- | --- |
| before external call | no receipt, retry may call once |
| external success before receipt commit | no receipt; retry uses the same event ID idempotency key |
| receipt commit before acknowledgement | retry reads receipt, makes zero new external calls, then acknowledges |
| acknowledgement commit | terminal delivered row and one immutable receipt |
| stale fence during receipt | receipt and acknowledgement both absent |
| stale fence after stored receipt | new owner may acknowledge the verified receipt without calling transport |

Also prove transport timeout is shorter than the message lease, retries are bounded, terminal
`DEAD` occurs exactly once, and receipt IDs/digests/error codes are bounded and contain no payload,
URL, credential, or exception message.

Run the focused test. Expected RED: receipt repository operations and dispatcher do not exist.

- [ ] **Step 6: Implement transaction-free delivery and receipt-before-ack**

Define `EventTransport.deliver(LeasedEvent)` to return `DeliveryReceipt` and require the event UUID as
the downstream idempotency key. `OutboxDispatcher` executes exactly:

1. inspect for an existing valid receipt;
2. if absent, call transport with no transaction open;
3. persist the returned receipt in its own tenant transaction under both live fences;
4. acknowledge in a later tenant transaction under the current full fences and exact receipt digest;
5. release/reschedule the tenant permit to the earliest ready message time.

Failure normalization stores only a closed uppercase error code. Unknown destinations fail closed
through the same retry/dead policy.

Run `OutboxDispatcherTest`. Expected GREEN: every crash boundary converges to one receipt, one
terminal acknowledgement, and the minimum external call count permitted by the crash location.

- [ ] **Step 7: Write RED atomic inbox handler tests**

Create `InboxDispatcherTest.java` with a transactional handler that inserts one aggregate row,
appends one domain event/outbox pair through `ReliableEventStore`, and returns a deterministic
`HandlerReceipt`. Prove:

- handler write, domain event, outbox, receipt, and `COMPLETED` commit together;
- injected failure after each write rolls back all five effects;
- lost tenant permit or message fence at completion rolls back handler business writes;
- a crash after commit replays as the stored receipt and does not call the handler again;
- digest/schema mismatch and unknown handler fail before business writes and follow retry/dead policy;
- handler attempts network access through the test guard fail the handler and roll back;
- colliding natural keys in two tenants never share receipt or business effects.

Use this handler boundary:

~~~java
@FunctionalInterface
public interface TransactionalInboxHandler {
    HandlerReceipt handle(DSLContext tx, LeasedInboxMessage message);
}
~~~

Run the focused test. Expected RED: transactional handler registry, receipt persistence, and atomic
completion do not exist.

- [ ] **Step 8: Implement atomic inbox processing and work signaling**

`InboxDispatcher` validates stored digest and schema before opening the handler transaction. Inside
one transaction it revalidates both fences, invokes the selected local handler, appends the handler
receipt, marks the inbox `COMPLETED`, and signals/reschedules tenant work. The final fenced update is
the last SQL statement; a changed row count other than one throws and rolls back the transaction.

Modify `ReliableEventStore.append` and the accepted branch of `acceptInbox` to call the tenant work
signal function in their existing transaction after message insertion. Duplicate inbox acceptance
does not emit a second signal unless the existing row is nonterminal and its current availability
is earlier than the directory value. Extend `ReliableEventStoreTest` to assert that a successful
append/first inbox acceptance signals exactly once and every rollback or terminal duplicate leaves
the directory unchanged.

Run `InboxDispatcherTest` plus existing FT8 reliability tests. Expected GREEN: atomic completion,
replay, schema/digest rejection, tenant isolation, and original append/deduplication behavior pass.

- [ ] **Step 9: Prove bounded completed-only idempotency cleanup**

Create `IdempotencyResultCleanerTest.java` with expired/future `COMPLETED` rows and expired/future,
live/stale `STARTED` rows in two tenants. Assert a batch of two deletes exactly two eligible rows for
the installed tenant, repeated calls finish the remaining eligible completed rows, and every
`STARTED`, future completed, and other-tenant row remains. Test batch sizes `0`, `501`, and negative
as rejected before deletion, and race cleanup against claim takeover.

`IdempotencyResultCleaner` calls only the V004 function inside an already tenant-scoped worker
transaction; it contains no direct `DELETE` SQL.

Run the focused test. Expected GREEN after implementation: only expired `COMPLETED` rows are removed
in bounded batches using database time.

- [ ] **Step 10: Write RED worker selection and drain tests**

Create `WorkerSchedulingTest.java` and `WorkerDrainCoordinatorTest.java`. Prove:

- `accord.process-role=control-worker` creates exactly one tenant poller, outbox dispatcher, inbox
  dispatcher, cleanup poller, and drain coordinator; API role creates none;
- duplicate destination or handler keys fail startup; absent adapters install fail-closed handlers;
- polling never holds more work than configured concurrency and always acquires permit first;
- drain changes `RUNNING -> QUIESCING -> DRAINED`, rejects new acquisitions immediately, and marks
  readiness false before waiting;
- work that completes within the 20-second drain timeout persists receipt/acknowledgement;
- unstarted leased work is fenced-rescheduled before permit release;
- work exceeding the deadline is never falsely acknowledged and remains recoverable by expiry;
- Task 12 drain lifecycle phase `200` stops before Task 10 Temporal lifecycle phase `100` and before
  datasource shutdown.

Run:

~~~powershell
./gradlew.bat :apps:control-plane:worker:test --tests '*WorkerSchedulingTest' --tests '*WorkerDrainCoordinatorTest' --no-daemon
~~~

Expected RED: scheduling properties, conditional beans, and drain lifecycle are absent.

- [ ] **Step 11: Wire bounded scheduling and graceful drain after Task 10**

Reuse the worker datasource/jOOQ/PostgreSQL dependencies and tenant transaction helper already owned
by Task 10; add only Task 12's scheduling/receipt dependencies without removing Temporal or fenced
reconciliation dependencies. Bind positive bounded values for poll delay, tenant/message lease,
transport timeout, batch size, concurrency, max attempts, cleanup batch, and drain timeout. Extend,
rather than replace, the Task 10 YAML:

~~~yaml
accord:
  worker:
    poll-delay: PT1S
    tenant-permit-lease: PT30S
    message-lease: PT30S
    transport-timeout: PT10S
    batch-size: 50
    concurrency: 8
    max-attempts: 8
    cleanup-batch-size: 200
    drain-timeout: PT20S
~~~

`WorkerDrainCoordinator` is a Spring `SmartLifecycle` at phase `200`. `WorkerScheduling` stops
scheduling before `WorkerDrainCoordinator` waits for in-flight work.
During drain, live work may renew its full fences within the remaining grace period; no renewal may
extend beyond that period. On timeout, cancel local waiting, leave unconfirmed external outcomes
unacknowledged, and let database leases recover them.

Run the worker selection/drain tests. Expected GREEN: role selection, permit-first polling,
configuration validation, lifecycle ordering, and bounded drain all pass.

- [ ] **Step 12: Run concurrency, property, fault, and full regression verification**

Run:

~~~powershell
./gradlew.bat :database:control-plane:test --no-daemon --dependency-verification=strict
./gradlew.bat :apps:control-plane:modules:reliability:test --no-daemon --dependency-verification=strict
./gradlew.bat :apps:control-plane:worker:test --no-daemon --dependency-verification=strict
./gradlew.bat :apps:control-plane:api:test --no-daemon --dependency-verification=strict
~~~

Expected: V001-V004 migration and checksum checks pass; concurrent workers never share a live
permit/message; ready tenants make bounded progress; every stale fence fails; outbox crash cuts
converge through durable receipts; inbox effects are atomic; shutdown leaves no false completion;
and cleanup never deletes a `STARTED` row.

- [ ] **Step 13: Lock and commit the V004 worker slice**

Refresh only Task 12's affected locks and approved metadata without re-adding Task 10's baseline
database closure, verify the second resolution is stable, then stage the exact Task 12 paths:

~~~powershell
./gradlew.bat :apps:control-plane:modules:reliability:dependencies :apps:control-plane:worker:dependencies --write-locks --no-configuration-cache --no-daemon --dependency-verification=strict
./gradlew.bat resolveAndLockAll --write-verification-metadata sha256,pgp --no-configuration-cache --no-daemon
./gradlew.bat resolveAndLockAll --no-configuration-cache --no-daemon --dependency-verification=strict
git diff --check
$task12Paths = @(
  'database/control-plane/migrations/V004__reliability_coordination_and_fences.sql'
  'database/control-plane/src/test/java/com/inforvans/accord/database/ReliabilityCoordinationMigrationTest.java'
  'apps/control-plane/modules/reliability/build.gradle'
  'apps/control-plane/modules/reliability/gradle.lockfile'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableEventStore.java'
  'apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliableEventStoreTest.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/TenantWorkPermit.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/MessageFence.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/LeasedEvent.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/LeasedInboxMessage.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/DeliveryReceipt.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/HandlerReceipt.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/TenantWorkRepository.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxRepository.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxRepository.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/EventTransport.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/TransactionalInboxHandler.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxDispatcher.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxDispatcher.java'
  'apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/IdempotencyResultCleaner.java'
  'apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/TenantWorkRepositoryTest.java'
  'apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/MessageFenceProperties.java'
  'apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/OutboxDispatcherTest.java'
  'apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/InboxDispatcherTest.java'
  'apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/IdempotencyResultCleanerTest.java'
  'apps/control-plane/worker/build.gradle'
  'apps/control-plane/worker/gradle.lockfile'
  'apps/control-plane/worker/src/main/resources/application.yml'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/WorkerReliabilityProperties.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/WorkerScheduling.java'
  'apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/WorkerDrainCoordinator.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/WorkerSchedulingTest.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/WorkerDrainCoordinatorTest.java'
  'gradle/verification-metadata.xml'
)
git add -- $task12Paths
git diff --cached --name-only
git commit -m "feat: dispatch reliable work with durable fences"
~~~

Expected: V003 remains owned by Task 9 and unchanged; the staged set contains V004 plus reliability
and worker files only; Task 10 mTLS/replay tests remain green; and Task 13 begins immediately after
this section.


### Task 13: Run One Digest-Locked Local Foundation Topology

**Goal:** Start the Foundation dependencies from one closed image-digest lock, give Temporal
independent PostgreSQL authority, run a pinned real Temporal server with mandatory frontend mTLS,
let the UI and worker authenticate with different client certificates, and expose GitLab-first
Provider facts without using GitHub product semantics.

**Files:**
- Create: `contracts/capabilities/object-storage-adapter.schema.json`
- Create: `contracts/supply-chain/image-lock.schema.json`
- Create: `contracts/verification/check-result.schema.json`
- Generate: `infra/images/images.lock.json`
- Create: `infra/local/compose.yaml`
- Create: `infra/local/postgres/00-roles-and-databases.sql`
- Create: `infra/local/temporal/server.yaml`
- Create: `infra/local/temporal/dynamicconfig/development-sql.yaml`
- Create: `infra/local/minio/normal-runtime-policy.json`
- Create: `infra/local/minio/quarantine-scanner-policy.json`
- Create: `infra/local/wiremock/mappings/gitlab-get-project.json`
- Create: `infra/local/localstack/ready.d/10-create-kms-key.sh`
- Create: `infra/local/otel-collector.yaml`
- Create: `infra/local/object-storage-capabilities.json`
- Create: `scripts/verification/check-result.mjs`
- Create: `scripts/verification/preflight.mjs`
- Create: `scripts/images/lock-images.mjs`
- Create: `scripts/images/render-compose-images.mjs`
- Create: `scripts/images/verify-images.mjs`
- Create: `scripts/local-up.mjs`
- Create: `scripts/local-down.mjs`
- Create: `scripts/local-up.ps1`
- Create: `scripts/local-down.ps1`
- Create: `tests/integration/image-lock.test.mjs`
- Create: `tests/integration/local-foundation.test.mjs`
- Create: `tests/integration/temporal-mtls.test.mjs`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/LocalTemporalPki.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalLocalTopologyIT.java`
- Modify: `.tool-versions`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `.gitignore`

`infra/images/images.lock.json` is the only image lock in the repository. There is no
`infra/local/images.lock.json`, `infra/images/base-images.lock.json`, second platform lock, or
workflow-owned digest list. Task 13 owns its schema, initial resolution, and maintenance command.
Task 16 consumes the same bytes when it renders Dockerfiles and never creates another lock.

**Authoritative invariants:**

1. The closed lock has exactly these role keys: `postgres`, `temporal-server`, `temporal-ui`,
   `minio`, `minio-client`, `wiremock`, `localstack`, `otel-collector`, `java-build`, and
   `java-runtime`. Each entry contains one reviewed source tag, canonical registry/repository,
   manifest-list digest, required platform digests, retrieval time, license identifier, and
   end-of-support date. Every digest is lowercase `sha256:<64 hex>` and is recomputed from fetched
   manifest bytes.
2. Compose image values are rendered into ignored `infra/local/state/images.env` from that lock.
   Every Compose `image` is a required environment expansion and resolves to the exact canonical
   repository plus locked digest. Dockerfile base entries are already present in the same lock;
   Task 16 later verifies every effective `FROM` against them. A tag-only, duplicate, extra,
   unknown-registry, absent-platform, zero, or fabricated digest is a hard failure.
3. Updating a source tag or digest is an explicit maintenance action. `verify-images.mjs` is
   read-only and proves exact consumer/lock set equality; ordinary local startup and CI never
   rewrite the lock.
4. One local PostgreSQL instance may host separate databases, but Temporal uses only
   `accord_temporal_schema_login` for schema setup and `accord_temporal_runtime_login` for runtime.
   Neither identity can connect to `accord`, `accord_webhook`, or `accord_signing`, and no Accord
   application login can connect to the Temporal or visibility database.
5. `temporal-server` incorporates Task 10's source coordinate unchanged as exactly
   `temporalio/server:1.28.1` plus its registry-resolved actual immutable digest. It is a real server,
   not `auto-setup`, a dev server, an in-process service, or `TestWorkflowEnvironment`. An explicit
   one-shot schema service prepares `accord_temporal` and `accord_temporal_visibility` and exits
   successfully before the distinct server starts. A missing actual digest is non-GREEN.
6. The Temporal frontend on 7233 requires a client certificate signed by the local Temporal client
   CA and verifies the server name `temporal`. Server, worker, UI, and schema/admin identities use
   separate keys and Extended Key Usage. Private keys are generated under ignored
   `infra/local/state/pki` and are never committed, logged, placed in an environment variable, or
   included in a verdict.
7. The production Task 10 `TemporalRuntimeConfiguration` is used for the worker success path.
   Correct worker and UI certificates succeed. Plaintext, no client certificate, a certificate
   signed by the wrong CA, the wrong server-name/SAN, and a client certificate with the wrong EKU all
   fail before a namespace or workflow response.
8. The Git mock models GitLab 19.1 project facts at `/api/v4/projects/77831`. GitHub is only the
   current host of the Accord source repository and is not a product Provider fixture.
9. The local MinIO profile proves only its six executable local capabilities and remains
   `production_eligible: false`. Replication evidence and deletion receipts remain
   `EXTERNAL_EVIDENCE_REQUIRED`.
10. Node 22 `.mjs` files own orchestration and spawn native commands with argument arrays and
    `shell: false`. Windows PowerShell 5.1 files are thin launchers only; they do not parse JSON,
    inspect native stderr, build command strings, or rely on
    `PSNativeCommandUseErrorActionPreference`.
11. Every verification check returns exit 0 for `PASS`, 1 for `FAIL`, or 2 for `BLOCKED` and writes
     a closed check-result JSON. An absent executable or wrong version is
     `BLOCKED_TOOLCHAIN`, never a skip or pass.
12. Task 13 adds exact normalized core pins `docker 29.4.2`, `docker-buildx 0.33.0`, and
    `git 2.52.0` to `.tool-versions`. Preflight derives requirements only from that lock. A parser may
    remove a documented vendor wrapper or platform suffix solely to compare the normalized core
    version, but evidence retains the complete observed version string. A missing pin is itself
    `BLOCKED_TOOLCHAIN`; no default or script-local version is permitted.

- [ ] **Step 1: Write RED image-lock and check-result contract tests**

Create `image-lock.test.mjs` with Node's test runner, Ajv 2020-12, and `yaml`. It must fail until the
schema, lock, renderer, and verifier exist. Cover:

- `additionalProperties: false` at the document, image, platform, and evidence levels;
- exact sorted role-key equality and unique canonical repository/digest tuples;
- manifest and `linux/amd64` plus `linux/arm64` digests for both Java base entries;
- required host platform digest for every Compose entry;
- rejection of a tag-only reference, uppercase/zero digest, mutable alias, duplicate role,
  duplicate digest under a different repository, unknown registry, and absent platform;
- structural parsing of `compose.yaml` and exact equality with rendered `images.env`;
- a repository scan proving the only path matching `images*.lock.json` is
  `infra/images/images.lock.json`;
- `verify-images.mjs` leaves the lock, Compose file, and rendered Dockerfiles byte-identical.
- `.tool-versions` contains exactly the Task 13 core pins `docker 29.4.2`,
  `docker-buildx 0.33.0`, and `git 2.52.0`; preflight tests cover exact match, allowed wrapper/platform
  suffix normalization with full observed-version evidence, mismatch, absent executable, and absent
  pin as `BLOCKED_TOOLCHAIN`.

Create `check-result.schema.json` with this closed shape:

~~~json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["schema_version", "check_id", "status", "reason_code", "started_at", "finished_at", "evidence"],
  "properties": {
    "schema_version": { "const": "1.0.0" },
    "check_id": { "type": "string", "pattern": "^[a-z0-9][a-z0-9.-]{2,95}$" },
    "status": { "enum": ["PASS", "FAIL", "BLOCKED"] },
    "reason_code": {
      "enum": ["PASS", "ASSERTION_FAILED", "BLOCKED_TOOLCHAIN", "BLOCKED_EXTERNAL_IMAGE_RESOLUTION", "BLOCKED_EXTERNAL_ENVIRONMENT"]
    },
    "started_at": { "type": "string", "format": "date-time" },
    "finished_at": { "type": "string", "format": "date-time" },
    "evidence": {
      "type": "array",
      "items": { "type": "string", "pattern": "^sha256:[0-9a-f]{64}$" },
      "uniqueItems": true
    }
  }
}
~~~

`check-result.mjs` validates `PASS -> reason_code=PASS`, `FAIL -> ASSERTION_FAILED`, and
`BLOCKED -> BLOCKED_*` before atomically writing JSON outside the source tree.

Run:

~~~powershell
node --test tests/integration/image-lock.test.mjs
~~~

Expected RED: the image-lock schema, single lock, and Node scripts are absent.

- [ ] **Step 2: Implement the closed verification result and tool preflight**

`preflight.mjs` reads every exact required version from `.tool-versions`, resolves executables without
a shell, runs a bounded version command, and emits one result per required tool. Its closed per-tool
parser may normalize only a documented vendor wrapper or platform suffix to the core token used for
comparison; evidence always retains the complete observed version string. Docker Buildx is queried
through an argument array equivalent to `docker buildx version` but is keyed by the independent
`docker-buildx` pin. An absent pin/executable, unparsable output, or mismatch is
`BLOCKED_TOOLCHAIN`. It accepts repeated `--require name` arguments and never has `--skip`,
`--best-effort`, `--allow-missing`, fallback pins, or script-local defaults.

Use this exact PowerShell 5.1 launcher pattern for both local wrappers:

~~~powershell
$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'local-up.mjs') @args
exit $LASTEXITCODE
~~~

`local-down.ps1` differs only in the target module name. Node receives each argument separately.

Run:

~~~powershell
node scripts/verification/preflight.mjs --check-id ft13-toolchain --require node --require docker --require docker-buildx --require git --require java
~~~

Expected GREEN on a configured local machine only when every required pin exists and the normalized
core versions match. If a pin or executable is absent, output is unparsable, or a pinned version
cannot be established, expected status is `BLOCKED` with reason `BLOCKED_TOOLCHAIN`, complete
observed version when available, and exit 2.

- [ ] **Step 3: Implement and populate the one authoritative image lock**

`image-lock.schema.json` fixes `schema_version` to `1.0.0` and the exact ten role keys above.
`lock-images.mjs` accepts only `--initialize` or `--refresh` followed by one exact lock role; for
example, `--refresh temporal-server`. Initialization uses the reviewed versions already named in
this plan for PostgreSQL 17.5, exact Temporal source `temporalio/server:1.28.1`, Temporal UI 2.39.0,
WireMock 3.13.1, LocalStack 4.6.0, and OTel Collector 0.129.0. It does not invent MinIO tags. MinIO
sources are closed required inputs `ACCORD_MINIO_SERVER_SOURCE` and
`ACCORD_MINIO_CLIENT_SOURCE`; each value must be an immutable-reviewed exact
`registry/repository:source-tag`, and empty, `latest`, digest-only, tagless, or ambiguous values are
rejected. The two Java source references remain required inputs `ACCORD_JAVA_BUILD_SOURCE` and
`ACCORD_JAVA_RUNTIME_SOURCE` from the approved authenticated mirror.

For every entry, invoke `docker buildx imagetools inspect --raw` with an argument array, hash raw
manifest bytes locally, resolve each required platform manifest, reject redirects to an unapproved
registry, and atomically write canonical key-sorted JSON. Missing or invalid required MinIO/Java
source input, or any source (including the currently failing Temporal registry source) that cannot
resolve to actual manifest bytes and digest, emits `BLOCKED_EXTERNAL_IMAGE_RESOLUTION` and writes no
partial lock or temporary replacement. Never preserve registry credentials, response headers, or
bearer challenges.

Run:

~~~powershell
node scripts/images/lock-images.mjs --initialize --output infra/images/images.lock.json
node scripts/images/verify-images.mjs --scope local
node --test tests/integration/image-lock.test.mjs
~~~

Expected GREEN only when authenticated registry resolution returns actual digest evidence for every
closed source. With the current registry failure, network disabled, a required MinIO/Java source
missing/invalid, or any source unresolvable, leave no partial lock and emit
`BLOCKED_EXTERNAL_IMAGE_RESOLUTION`; that result is not FT13 completion evidence.

- [ ] **Step 4: Write RED Temporal topology, database-authority, and GitLab-facts tests**

Create `temporal-mtls.test.mjs` and `local-foundation.test.mjs`. Parse Compose/server YAML and assert:

- services are `postgres`, `temporal-schema`, `temporal-server`, `temporal-ui`, `minio`,
  `minio-init`, `mock-gitlab`, `mock-kms`, and `otel-collector`;
- no Compose image contains a tag or literal digest; every image comes from the rendered lock env;
- Temporal schema is a one-shot dependency and server is not `auto-setup`/`start-dev`;
- frontend TLS requires client auth, separate server/client CA paths, and name `temporal`;
- UI mounts only its client material; worker material is not mounted into UI;
- Temporal identities have no membership or CONNECT path into an Accord business database;
- `mock-gitlab` exposes `/api/v4/projects/77831` and no `/repos/` route;
- tracked files contain no generated PEM/private-key bytes.

`TemporalLocalTopologyIT` uses Task 10's production `TemporalRuntimeConfiguration`,
`ReadOnlyReconciliationActivity`, `ReconciliationWorkflowRef`, and production
`FencedReconciliationObservation`. The test supplies only a bounded low-level
`ProviderObservationPort`; it cannot implement or replace the high-level Temporal port. The
production orchestrator executes Task 10's full tx1 safe-snapshot/claim, no-JDBC-transaction
observation, and tx2 finalize boundary on every activity attempt. The low-level adapter fails the
first observation, the orchestrator atomically marks the acquired attempt unknown, then retries and
atomically completes `CONVERGED` plus event/outbox using database snapshot scope. Assert exactly two
claims/reloads, capability only in attempt memory, identifier-only heartbeats/history, and zero
Provider mutation calls.

Run the Node tests before creating the topology.

Expected RED: Compose, Temporal config, GitLab facts, and local integration are absent.

- [ ] **Step 5: Implement isolated roles, explicit schema setup, and mandatory Temporal mTLS**

`00-roles-and-databases.sql` retains Task 9-12 boundaries and adds:

- NOLOGIN `accord_temporal_owner` and `accord_temporal_visibility_owner`;
- LOGIN `accord_temporal_schema_login` with set-only owner memberships;
- NOLOGIN `accord_temporal_runtime` plus LOGIN `accord_temporal_runtime_login` with set-only runtime
  membership;
- separate `accord_temporal` and `accord_temporal_visibility` databases;
- revoked PUBLIC connect/schema privileges and exact schema/runtime grants;
- explicit revocations between all application and Temporal databases.

`LocalTemporalPki` reuses Task 10's test-only Bouncy Castle version to generate server/client CAs,
a server certificate, and distinct `worker`, `ui`, and `admin` client certificates under ignored
`infra/local/state/pki`, plus an untrusted CA/client pair and a trusted-CA wrong-EKU certificate
carrying only serverAuth. The server EKU is serverAuth; valid client EKUs are clientAuth. SANs are
exact and private keys are owner-only where supported.

`server.yaml` configures PostgreSQL persistence, committed dynamic config, frontend server
certificate/key, trusted client CA, mandatory client auth, and TLS 1.3. `temporal-schema` runs the
pinned server image SQL tool as the schema login, then exits. `temporal-server` runs as the runtime
login. UI uses its own certificate, CA, host verification, and server name.

The required Compose image form is:

~~~yaml
services:
  temporal-server:
    image: ${ACCORD_IMAGE_TEMPORAL_SERVER:?run render-compose-images.mjs}
    command: ["temporal-server", "start", "--env", "docker"]
    volumes:
      - ./temporal/server.yaml:/etc/temporal/config/docker.yaml:ro
      - ./state/pki:/run/accord/temporal/pki:ro
    ports: ["7233:7233"]
  temporal-ui:
    image: ${ACCORD_IMAGE_TEMPORAL_UI:?run render-compose-images.mjs}
    environment:
      TEMPORAL_ADDRESS: temporal-server:7233
      TEMPORAL_TLS_CA: /run/accord/temporal/pki/client-ca.pem
      TEMPORAL_TLS_CERT: /run/accord/temporal/pki/ui.pem
      TEMPORAL_TLS_KEY: /run/accord/temporal/pki/ui-key.pem
      TEMPORAL_TLS_ENABLE_HOST_VERIFICATION: "true"
      TEMPORAL_TLS_SERVER_NAME: temporal
~~~

`render-compose-images.mjs` is the only writer of `infra/local/state/images.env` and refuses any
missing or extra Compose image variable.

Run structural tests again. Expected GREEN without starting containers.

- [ ] **Step 6: Implement GitLab-first and object-storage local contracts**

`gitlab-get-project.json` matches `GET /api/v4/projects/77831` and returns project ID `77831`,
`path_with_namespace: acme/demo`, `default_branch: main`, archived false, and a fixed
`last_activity_at`. It has no GitHub `node_id`, `/repos` route, or GitHub header.

`object-storage-adapter.schema.json` is closed and requires exact adapter identity/version/digest,
environment class, tested/expiry times, evidence digest, eligibility, and exactly immutable
versions, SHA-256 checksums, WORM, legal hold, multipart, quarantine isolation, replication
evidence, and deletion receipts. The local profile marks the first six `SUPPORTED_AND_TESTED`, the
final two `EXTERNAL_EVIDENCE_REQUIRED`, and `production_eligible: false`. Evidence references are
immutable digests or result paths, never mutable URLs.

The MinIO init service creates separate normal-runtime and scanner accounts. Tests require distinct
version IDs for two uploads, recomputed upload/download digests, retention and legal hold, a forced
multipart upload with no orphan, and both quarantine deny directions.

Expected GREEN: schema, profile, GitLab facts, KMS alias, and MinIO policy tests pass structurally.

- [ ] **Step 7: Add deterministic Node-owned start, TLS negative matrix, and stop**

`local-up.mjs` runs preflight, verifies/renders images, generates fresh PKI, starts PostgreSQL,
executes Temporal schema setup, starts the remaining services, runs all local checks, and writes
`build/verification/ft13-local.json` only through `check-result.mjs`.

Prove this exact matrix:

| Client case | Expected |
| --- | --- |
| worker cert + trusted CA + server name `temporal` | production worker completes one workflow |
| UI cert + trusted CA + server name `temporal` | UI namespace request succeeds |
| plaintext on 7233 | closes without a Temporal response |
| trusted CA and no client cert | TLS handshake fails |
| wrong-CA client cert | TLS handshake fails |
| worker cert with server name `wrong.temporal.test` | hostname verification fails |
| trusted-CA certificate with serverAuth-only EKU | TLS client-auth handshake fails |

`local-down.mjs` preserves data/PKI unless `--purge-local-state` is present. Purge resolves and
proves the target is exactly `infra/local/state` under the repository before removal.

Run:

~~~powershell
node scripts/local-up.mjs
node --test tests/integration/temporal-mtls.test.mjs tests/integration/local-foundation.test.mjs
./gradlew.bat :apps:control-plane:worker:test --tests '*TemporalLocalTopologyIT' --no-daemon --dependency-verification=strict
node scripts/local-down.mjs
~~~

Expected GREEN: real server/UI/worker mTLS, database separation, GitLab facts, KMS, and local object
capabilities pass; every negative TLS case fails for its named reason; no key appears in evidence.

- [ ] **Step 8: Verify and commit only FT13 paths**

~~~powershell
node scripts/images/verify-images.mjs --scope local
node --test tests/integration/image-lock.test.mjs tests/integration/local-foundation.test.mjs tests/integration/temporal-mtls.test.mjs
./gradlew.bat :apps:control-plane:worker:test --tests '*TemporalLocalTopologyIT' --no-daemon --dependency-verification=strict
git diff --check
$task13Paths = @(
  'contracts/capabilities/object-storage-adapter.schema.json'
  'contracts/supply-chain/image-lock.schema.json'
  'contracts/verification/check-result.schema.json'
  'infra/images/images.lock.json'
  'infra/local/compose.yaml'
  'infra/local/postgres/00-roles-and-databases.sql'
  'infra/local/temporal/server.yaml'
  'infra/local/temporal/dynamicconfig/development-sql.yaml'
  'infra/local/minio/normal-runtime-policy.json'
  'infra/local/minio/quarantine-scanner-policy.json'
  'infra/local/wiremock/mappings/gitlab-get-project.json'
  'infra/local/localstack/ready.d/10-create-kms-key.sh'
  'infra/local/otel-collector.yaml'
  'infra/local/object-storage-capabilities.json'
  'scripts/verification/check-result.mjs'
  'scripts/verification/preflight.mjs'
  'scripts/images/lock-images.mjs'
  'scripts/images/render-compose-images.mjs'
  'scripts/images/verify-images.mjs'
  'scripts/local-up.mjs'
  'scripts/local-down.mjs'
  'scripts/local-up.ps1'
  'scripts/local-down.ps1'
  'tests/integration/image-lock.test.mjs'
  'tests/integration/local-foundation.test.mjs'
  'tests/integration/temporal-mtls.test.mjs'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/LocalTemporalPki.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/TemporalLocalTopologyIT.java'
  'apps/control-plane/worker/build.gradle'
  '.tool-versions'
  '.gitignore'
)
git add -- $task13Paths
git diff --cached --name-only
git commit -m "build: add digest-locked Temporal mTLS topology"
~~~

Expected: the staged set contains the single image lock and `.tool-versions` core pins, no generated
PKI/state, no GitHub product mock, and no undeclared Task 12-or-earlier implementation or migration change. The shared
`apps/control-plane/worker/build.gradle` is the only intentional pre-existing implementation path.
Missing external image resolution leaves FT13 `BLOCKED` and does not produce a partial commit.

### Task 14: Enforce Typed, Fail-Closed OpenTelemetry

**Goal:** Expose only closed typed telemetry attributes to Accord code, reject sensitive candidates
without partial export, and place a final SDK exporter guard after automatic instrumentation so
unknown or invalid span, metric, and log records cannot escape.

**Files:**
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryAttributeKey.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryIdentifiers.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryOperation.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryProvider.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryResultCode.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryAttributes.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/SensitiveTelemetryCandidate.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryDropReason.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryRecordGuard.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryGuardMetrics.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/GuardedSpanExporter.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/GuardedMetricExporter.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/GuardedLogRecordExporter.java`
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/AccordOpenTelemetryConfiguration.java`
- Create: `libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryAttributesTest.java`
- Create: `libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryRecordGuardTest.java`
- Create: `libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryExporterGuardTest.java`
- Create: `tests/security-negative/src/test/java/com/inforvans/accord/security/TelemetryApiBoundaryTest.java`
- Create: `tests/security-negative/src/test/java/com/inforvans/accord/security/TelemetryLeakTest.java`
- Modify: `libs/java/observability/build.gradle`
- Modify: `libs/java/observability/gradle.lockfile`
- Modify: `gradle/libs.versions.toml`
- Modify: `gradle/verification-metadata.xml`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/api/gradle.lockfile`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `apps/control-plane/worker/gradle.lockfile`
- Modify: `apps/webhook-edge/build.gradle`
- Modify: `apps/webhook-edge/gradle.lockfile`
- Modify: `apps/control-plane/api/src/main/resources/application.yml`
- Modify: `apps/control-plane/worker/src/main/resources/application.yml`
- Modify: `apps/webhook-edge/src/main/resources/application.yml`
- Modify: `infra/local/otel-collector.yaml`

The library is the only Accord package allowed to import OpenTelemetry SDK exporters, construct
`AttributesBuilder`, call span `setAttribute`/`addEvent`, create metric tag sets, or populate OTel
log attributes. Application packages receive typed operations only.

**Authoritative invariants:**

1. No public method accepts `Map<String, String>`, `Map<String, ?>`, arbitrary attribute names,
   headers, request/response objects, exceptions, or `Object.toString()`. `TelemetryAttributes`
   accepts only the closed value types and returns immutable OTel `Attributes`.
2. `TelemetryAttributeKey` is exhaustive. Trace-only identifier keys are tenant ID, scope type/ID,
   correlation ID, and causation ID. Bounded keys are operation, provider, result code, aggregate
   type, event type, HTTP method, locked route template, and HTTP status. Tenant/scope/correlation
   identifiers are forbidden metric labels.
3. `TelemetryOperation`, `TelemetryProvider`, and `TelemetryResultCode` are enums.
   `TelemetryOperation` declares whether provider context applies: non-provider operations require
   `TelemetryProvider.NOT_APPLICABLE`, while provider operations forbid it. Identifier value objects
   accept only canonical UUIDs or the task's existing bounded identifier syntax. `HttpMethod`,
   `AggregateType`, and `EventType` are closed enums nested in `TelemetryAttributes`; nested
   `RouteTemplate` and `HttpStatus` records accept only registered templates and status 100-599.
   No value is truncated: an over-limit or invalid value rejects the record.
4. `SensitiveTelemetryCandidate` uses a fixed, tested ruleset: authorization/basic/bearer prefixes,
   cookie/session/CSRF names, `glpat-`, `ghp_`, `whsec_`, PEM headers, three-segment JWT shape,
   URI query/fragment text, control characters, and configured sentinels. Matching any key or value
   drops the complete record; it never exports a redacted prefix or remaining attributes.
5. The final guard validates resource attributes, span name, every span/event/link attribute,
   metric name/unit/description/point attributes/exemplars, and log body/attributes. Unknown key,
   invalid typed value, sensitive candidate, raw URL, exception message, or non-registered name
   drops that complete span, metric point, or log record before the delegate exporter sees it.
6. Drop handling increments only `accord.telemetry.records.dropped` with closed dimensions
   `signal=span|metric|log` and `reason=<TelemetryDropReason>`. The counter contains no tenant,
   route, value, exception, class name, or caller-provided text.
7. Export failure, queue saturation, timeout, and collector outage never block business work or make
   readiness false. Batches and queues are bounded; shutdown attempts one flush for at most five
   seconds, records a fixed outcome, and terminates.
8. Automatic instrumentation cannot capture request/response headers, cookies, bodies, form data,
   query strings, raw URLs, baggage, stack traces, or exception messages. HTTP spans use the locked
   route template.
9. The default local collector has no debug exporter. It writes bounded JSONL under ignored
   `infra/local/state/otel` and exposes Prometheus metrics. A collector-side keep-list is defense in
   depth, not the primary application guard.
10. Security/audit events remain PostgreSQL business records. Sampling or collector availability
    never decides whether those records exist.

- [ ] **Step 1: Write RED compile-time typed-API tests**

`TelemetryApiBoundaryTest` scans compiled application classes and source imports. It rejects:

- `Map`, `Object`, `Throwable`, servlet, Spring HTTP, header, cookie, or request/response parameters
  in public observability methods;
- direct OTel `Attributes.builder`, `setAttribute`, `addEvent`, exporter, or Micrometer tag calls
  outside `libs/java/observability` and generated instrumentation;
- application-defined telemetry key string literals;
- tenant/scope/correlation keys used by a meter operation;
- any catch block that sends `Throwable.getMessage()` or `toString()` to telemetry.

Use this closed public shape. All variant and variant-only value types remain in
`TelemetryAttributes.java`; no generic attribute-value container is exposed:

~~~java
public sealed interface TelemetryAttributes
        permits TelemetryAttributes.Http, TelemetryAttributes.Workflow,
                TelemetryAttributes.DomainEvent, TelemetryAttributes.Metric {
    default Attributes toOtelAttributes() {
        return TelemetryRecordGuard.validatedAttributes(this);
    }

    record Http(
            TelemetryIdentifiers identifiers,
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode,
            HttpMethod method,
            RouteTemplate route,
            HttpStatus status) implements TelemetryAttributes {}

    record Workflow(
            TelemetryIdentifiers identifiers,
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode) implements TelemetryAttributes {}

    record DomainEvent(
            TelemetryIdentifiers identifiers,
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode,
            AggregateType aggregateType,
            EventType eventType) implements TelemetryAttributes {}

    record Metric(
            TelemetryOperation operation,
            TelemetryProvider provider,
            TelemetryResultCode resultCode) implements TelemetryAttributes {}

    enum HttpMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }
    record RouteTemplate(String value) {}
    record HttpStatus(int value) {}
    enum AggregateType { CONTRACT_VALIDATION }
    enum EventType { CONTRACT_VALIDATION_COMPLETED }
}
~~~

Each variant's compact constructor rejects a null field, an invalid provider/operation pairing, or
a value outside its declared variant. `RouteTemplate` checks the immutable route registry and
`HttpStatus` enforces 100-599. `Metric` structurally cannot carry tenant, scope, correlation,
causation, route, aggregate, or event values. `TelemetryIdentifiers` contains typed optional tenant,
scope, correlation, and causation fields; it does not implement a generic key/value container.

Run:

~~~powershell
./gradlew.bat :tests:security-negative:test --tests '*TelemetryApiBoundaryTest' --no-daemon
~~~

Expected RED: the closed API and boundary rule do not exist.

- [ ] **Step 2: Write RED value, sensitive-candidate, and whole-record-drop tests**

`TelemetryAttributesTest` covers canonical identifiers, every enum value, route-template
registration, immutability, deterministic key order, and exact OTel scalar types. It rejects null,
blank, non-canonical UUID, unregistered route/operation/provider/result, control characters, and a
value above 255 Unicode code points without truncation.

`TelemetryRecordGuardTest` constructs final SDK data, including data that bypasses the typed API as
automatic instrumentation would, and proves:

- one unknown attribute among otherwise valid attributes drops the entire record;
- each sensitive-candidate rule drops the complete record;
- raw path, query, URL, exception message, event, link, resource, exemplar, and log-body sentinels
  never reach an exporter;
- allowed OTel semantic-convention keys have exact type and bounded value validators;
- a rejected record increments exactly one fixed reason counter;
- the counter itself passes the guard and cannot recursively produce another drop.

Run:

~~~powershell
./gradlew.bat :libs:java:observability:test --tests '*TelemetryAttributesTest' --tests '*TelemetryRecordGuardTest' --no-daemon
~~~

Expected RED: typed values, sensitive classifier, and final record guard are absent.

- [ ] **Step 3: Implement the typed attribute model and fixed rejection taxonomy**

`TelemetryAttributeKey` maps each closed key to its OTel `AttributeKey<?>`, allowed signal set, value
kind, and maximum length. Only this enum contains OTel key strings. `TelemetryIdentifiers` validates
canonical values at construction. The three behavior enums expose stable lowercase wire names and
reject unknown external strings before telemetry construction.

Use this exact drop taxonomy:

~~~java
public enum TelemetryDropReason {
    UNKNOWN_KEY,
    INVALID_TYPE,
    INVALID_VALUE,
    SENSITIVE_KEY,
    SENSITIVE_VALUE,
    RAW_URL,
    EXCEPTION_CONTENT,
    UNREGISTERED_NAME,
    CARDINALITY_POLICY,
    EXPORT_QUEUE_FULL,
    EXPORT_TIMEOUT
}
~~~

`SensitiveTelemetryCandidate` returns only an enum reason and never includes the examined text in
an exception or `toString()`. `TelemetryAttributes.toOtelAttributes()` delegates to the same guard
rules used at export.

Run the focused tests again. Expected GREEN: every accepted typed value becomes exact immutable
`Attributes` and every invalid input fails with a fixed non-sensitive reason.

- [ ] **Step 4: Write RED final exporter, cardinality, outage, and shutdown tests**

`TelemetryExporterGuardTest` wraps recording span, metric, and log exporters and proves:

- valid records are delegated byte-for-byte;
- invalid records are individually removed from a mixed batch and the delegate never sees them;
- 10,000 distinct tenant/scope IDs create zero new metric label combinations;
- operation/provider/result labels remain bounded by the enum Cartesian product;
- a delegate failure returns a normalized exporter failure without throwing into application code;
- queue saturation drops new telemetry with `EXPORT_QUEUE_FULL`;
- a delegate that never completes is abandoned at the configured timeout;
- shutdown performs at most one flush and returns within five seconds;
- guard/drop metrics do not recurse.

Run the focused exporter test. Expected RED: exporter decorators and guard metrics are absent.

- [ ] **Step 5: Implement final guards around every SDK exporter**

`GuardedSpanExporter`, `GuardedMetricExporter`, and `GuardedLogRecordExporter` filter individual
records through `TelemetryRecordGuard` immediately before delegation. They use bounded immutable
copies and return the SDK's success result when all records were intentionally dropped; the fixed
drop counter makes the loss visible. Delegate exceptions and failed futures are normalized and
never include delegate exception text.

`AccordOpenTelemetryConfiguration` owns SDK construction, installs all three guarded exporters,
sets bounded batch/queue/timeout values, disables baggage, and registers graceful shutdown. No
application creates a second SDK/exporter bean. The library depends on OTel API/SDK/exporter SPI;
applications depend on the library and Micrometer bridge only.

Run:

~~~powershell
./gradlew.bat :libs:java:observability:test --no-daemon
~~~

Expected GREEN: typed, guard, exporter, cardinality, outage, recursion, and shutdown tests pass.

- [ ] **Step 6: Write RED end-to-end leak tests for all executable processes**

`TelemetryLeakTest` starts API, worker, and Webhook Edge with in-memory final exporters and a JSON
log appender. Send a distinct sentinel through authorization, cookies, CSRF, path/query, GitLab
headers/body, contract body, attachment/source/diff/prompt-shaped fields, and a thrown exception.
Assert no sentinel appears in:

- span names, resources, attributes, events, links, status descriptions, or baggage;
- metric names, units, descriptions, points, labels, or exemplars;
- log body, structured fields, MDC, exception field, or stack trace;
- health responses, RFC 7807 bodies, collector JSONL, or Prometheus labels.

Also assert route templates rather than raw IDs, a fixed result code rather than exception text,
readiness 200 during collector outage, and increasing fixed exporter failure/drop metrics.

Run:

~~~powershell
./gradlew.bat :tests:security-negative:test --tests '*TelemetryLeakTest' --no-daemon
~~~

Expected RED: process wiring and final guards are not installed.

- [ ] **Step 7: Wire the three processes and collector without broad capture**

Add version-catalog aliases for the Micrometer OTel bridge, Prometheus registry, OTel SDK/exporter
SPI/testing, and OTLP exporters at versions governed by the Spring Boot BOM where available.
Each process has a distinct immutable `service.name`.

Merge these properties without removing Task 9-13 datasource, process-role, Temporal, or health
configuration:

~~~yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      probes:
        enabled: true
  tracing:
    sampling:
      probability: ${ACCORD_TRACE_SAMPLE_RATE:0.1}
    baggage:
      enabled: false
  metrics:
    tags:
      service: ${spring.application.name}
accord:
  telemetry:
    endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}
    queue-capacity: 2048
    batch-size: 256
    export-timeout: PT2S
    shutdown-timeout: PT5S
~~~

Explicitly disable HTTP header/body/query capture, log correlation fields outside the closed set,
and exception-message/status-description capture. Collector `transform`/`filter` processors keep
only the documented semantic and Accord keys; the file exporter writes rotated bounded JSONL to
`infra/local/state/otel`. There is no debug exporter.

Run the leak test and then stop the collector during one authenticated API request.

Expected GREEN: business response and readiness remain correct; final exporters and collector
contain zero sentinel values; only fixed drop/outage metrics increase.

- [ ] **Step 8: Refresh locks, run regression, and commit only FT14 paths**

~~~powershell
./gradlew.bat :libs:java:observability:dependencies :apps:control-plane:api:dependencies :apps:control-plane:worker:dependencies :apps:webhook-edge:dependencies --write-locks --no-configuration-cache --no-daemon --dependency-verification=strict
./gradlew.bat resolveAndLockAll --write-verification-metadata sha256,pgp --no-configuration-cache --no-daemon
./gradlew.bat resolveAndLockAll --no-configuration-cache --no-daemon --dependency-verification=strict
./gradlew.bat :libs:java:observability:test :tests:security-negative:test :apps:control-plane:api:test :apps:control-plane:worker:test :apps:webhook-edge:test --no-daemon --dependency-verification=strict
git diff --check
$task14Paths = @(
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryAttributeKey.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryIdentifiers.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryOperation.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryProvider.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryResultCode.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryAttributes.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/SensitiveTelemetryCandidate.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryDropReason.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryRecordGuard.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/TelemetryGuardMetrics.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/GuardedSpanExporter.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/GuardedMetricExporter.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/GuardedLogRecordExporter.java'
  'libs/java/observability/src/main/java/com/inforvans/accord/observability/AccordOpenTelemetryConfiguration.java'
  'libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryAttributesTest.java'
  'libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryRecordGuardTest.java'
  'libs/java/observability/src/test/java/com/inforvans/accord/observability/TelemetryExporterGuardTest.java'
  'libs/java/observability/build.gradle'
  'libs/java/observability/gradle.lockfile'
  'tests/security-negative/src/test/java/com/inforvans/accord/security/TelemetryApiBoundaryTest.java'
  'tests/security-negative/src/test/java/com/inforvans/accord/security/TelemetryLeakTest.java'
  'gradle/libs.versions.toml'
  'gradle/verification-metadata.xml'
  'apps/control-plane/api/build.gradle'
  'apps/control-plane/api/gradle.lockfile'
  'apps/control-plane/worker/build.gradle'
  'apps/control-plane/worker/gradle.lockfile'
  'apps/webhook-edge/build.gradle'
  'apps/webhook-edge/gradle.lockfile'
  'apps/control-plane/api/src/main/resources/application.yml'
  'apps/control-plane/worker/src/main/resources/application.yml'
  'apps/webhook-edge/src/main/resources/application.yml'
  'infra/local/otel-collector.yaml'
)
git add -- $task14Paths
git diff --cached --name-only
git commit -m "feat: enforce typed fail-closed telemetry"
~~~

Expected: strict dependency resolution is stable, the staged set contains no unrelated application
code, direct application OTel attribute calls are zero, every leak/outage/cardinality test passes,
and no telemetry record is partially exported after a policy violation.

### Task 15: Bind Workload Identity, Deployment, Network, And GitOps Authority

**Goal:** Render and provision three independently identified workloads from one closed component
contract, enforce exact secret/database/network bindings, use a real OpenTofu environment adapter,
and make Argo CD deploy only an immutable authoritative Accord source SHA.

**Files:**
- Create: `contracts/deployment/component-identity.schema.json`
- Create: `contracts/deployment/promotion-input.schema.json`
- Create: `infra/helm/accord/Chart.yaml`
- Create: `infra/helm/accord/values.yaml`
- Create: `infra/helm/accord/values.schema.json`
- Create: `infra/helm/accord/templates/_helpers.tpl`
- Create: `infra/helm/accord/templates/serviceaccounts.yaml`
- Create: `infra/helm/accord/templates/externalsecrets.yaml`
- Create: `infra/helm/accord/templates/workloads.yaml`
- Create: `infra/helm/accord/templates/services.yaml`
- Create: `infra/helm/accord/templates/networkpolicies.yaml`
- Create: `infra/helm/accord/templates/cilium-networkpolicies.yaml`
- Create: `infra/helm/accord/templates/poddisruptionbudgets.yaml`
- Create: `infra/policy/accord.rego`
- Create: `infra/opentofu/modules/accord-foundation-contract/variables.tf`
- Create: `infra/opentofu/modules/accord-foundation-contract/checks.tf`
- Create: `infra/opentofu/modules/accord-foundation-contract/outputs.tf`
- Create: `infra/opentofu/environments/local-kubernetes/versions.tf`
- Create: `infra/opentofu/environments/local-kubernetes/variables.tf`
- Create: `infra/opentofu/environments/local-kubernetes/main.tf`
- Create: `infra/opentofu/environments/local-kubernetes/outputs.tf`
- Create: `infra/opentofu/environments/local-kubernetes/tests/local-kubernetes.tftest.hcl`
- Create: `infra/argocd/project.yaml`
- Generate: `infra/argocd/application.yaml`
- Create: `scripts/deployment/render-values.mjs`
- Create: `scripts/deployment/render-argocd.mjs`
- Create: `scripts/deployment/verify-deployment.mjs`
- Create: `tests/integration/deployment-contract.test.mjs`
- Create: `tests/integration/deployment-contract.ps1`
- Create: `tests/integration/fixtures/deployment/valid-test-promotion.json`
- Create: `tests/integration/fixtures/deployment/invalid-shared-identity.json`
- Create: `tests/integration/fixtures/deployment/invalid-broad-egress.json`
- Create: `tests/integration/fixtures/deployment/invalid-plaintext-secret.json`

`values.yaml` contains safe non-secret defaults but no image reference. `render-values.mjs` consumes a
schema-valid promotion input and writes an ignored rendered values file. Production rendering
cannot substitute a syntactic test digest for Task 16's verified promotion manifest.

**Authoritative invariants:**

1. The component object has exactly `control-api`, `control-worker`, and `webhook-edge`. Each binds
   one unique ServiceAccount name, workload-identity provider, subject, audience, external-secret
   store/reference, database secret, login role, session role, image digest, run UID/GID, probes,
   and network profile. There is no positional `workload_identity_ids` list.
2. Exact database pairs are API `accord_api_login -> accord_api`, worker
   `accord_worker_login -> accord_worker`, and edge
   `accord_webhook_runtime_login -> accord_webhook_runtime`. Secret, role, subject, and
   ServiceAccount tuples are compared in both JSON Schema and Rego. Cross-component reuse fails.
3. ServiceAccount token automount is false. When workload federation requires a projected token,
   the pod mounts only a bounded token with the component's exact audience and expiration. Provider
   annotations are rendered from closed AWS/GCP/Azure/Kubernetes branches rather than an arbitrary
   annotations map.
4. ExternalSecret objects contain only remote secret references and target-key names. Helm values,
   Terraform state inputs, Argo manifests, environment variables, and Git contain no credential
   value. Worker alone receives its Temporal client certificate reference; UI identity remains in
   the local topology and is not mounted into an Accord workload.
5. Each Deployment runs a distinct artifact, ServiceAccount, secret set, fixed non-root UID/GID,
   read-only root, default seccomp, no privilege escalation, dropped capabilities, bounded
   memory-backed `/tmp`, requests/limits, rolling update, topology spread, anti-affinity, and PDB.
6. Network reachability and application TLS are different proofs. Standard NetworkPolicy proves
   only L3/L4. PostgreSQL verify-full, Temporal mTLS, and OTLP TLS configuration/certificate
   handshakes are validated separately and cannot be inferred from an allowed port.
7. Every destination selects one closed route mode:
   `KUBERNETES_SERVICE` requires namespace plus pod selectors;
   `EGRESS_GATEWAY` requires the exact gateway namespace/pod/service;
   `CILIUM_FQDN` requires a non-wildcard FQDN and Cilium policy;
   `AUDITED_CIDR` requires bounded non-zero CIDRs, evidence digest, expiry, and owner.
   Standard NetworkPolicy never claims to select a managed external endpoint by pod label.
8. API has ingress-gateway, control PostgreSQL, OTLP, and DNS edges only. Worker has control
   PostgreSQL, Temporal frontend, OTLP, and DNS only. Webhook Edge has edge-gateway, webhook
   PostgreSQL, OTLP, and DNS only. Foundation grants no Provider/content/object-storage egress.
9. The contract-only OpenTofu module uses variable validation, `check` blocks, and outputs; it
   creates no fake resource. The `local-kubernetes` adapter is real: it configures pinned
   Kubernetes and Helm providers, creates a namespace, and installs the chart with
   `kubernetes_namespace_v1` and `helm_release` resources. Tests use OpenTofu mock providers to
   assert the resource graph without contacting a cluster.
10. Production object storage remains blocked until the Task 13 capability report and its signed
    verification receipt prove all eight capabilities. OpenTofu consumes immutable report/receipt
    digests, never caller-supplied booleans.
11. `infra/argocd/application.yaml` is generated only after the infrastructure commit is present on
    the authoritative remote. `spec.source.targetRevision` is the exact lowercase remote commit SHA,
    never a branch, tag, `HEAD`, local branch, or remote-tracking guess.
12. GitHub may host Accord deployment Git and its thin CI adapter. Argo receives no GitLab product
    token; GitLab remains the product Provider exercised by Task 11/13.

- [ ] **Step 1: Write RED closed identity and promotion contract tests**

`component-identity.schema.json` uses `additionalProperties: false` recursively and a discriminator
for `KUBERNETES`, `AWS`, `GCP`, or `AZURE` workload identity. Each branch requires exact subject and
audience syntax and only its named provider fields.

`promotion-input.schema.json` requires `schema_version`, environment class, authoritative remote
SHA/tree, three component image repository-digest references, release-manifest digest, DSSE
envelope digest, verification receipt digest, and creation time. Production requires
`signature_verified: true` and `scan_policy_passed: true`; test fixtures explicitly use
`environment_class: test` and can never render a production release.

`deployment-contract.test.mjs` first proves:

- exactly three component keys and exact role tuples;
- unique SA, subject, secret target, DB secret, and image repository;
- audience equals the component's registered workload audience;
- wrong role, shared identity/secret, plaintext value, unknown field, mutable image, or test
  promotion under production is rejected;
- `values.yaml` has no fake/default image.

Run:

~~~powershell
node --test tests/integration/deployment-contract.test.mjs
~~~

Expected RED: schemas, fixtures, chart, and renderers are absent.

- [ ] **Step 2: Implement closed values and cross-bound Helm resources**

`render-values.mjs` validates promotion input and component identity before producing the Helm
values. It hashes both inputs into output annotations and refuses a remote SHA/tree or image digest
mismatch. The three keyed component values have this exact ownership:

| Component | ServiceAccount | Login/session role | Workload audience |
| --- | --- | --- | --- |
| `control-api` | `accord-control-api` | `accord_api_login` / `accord_api` | `accord-control-api` |
| `control-worker` | `accord-control-worker` | `accord_worker_login` / `accord_worker` | `accord-control-worker` |
| `webhook-edge` | `accord-webhook-edge` | `accord_webhook_runtime_login` / `accord_webhook_runtime` | `accord-webhook-edge` |

`serviceaccounts.yaml` renders fixed token automount and the selected closed identity binding.
`externalsecrets.yaml` renders one ExternalSecret per workload and no inline Secret.
`workloads.yaml` cross-references the same component key for SA, image, secret, DB roles, TLS
mounts, probes, UID/GID, and policy labels. It cannot index a separate positional list.

API/edge use actuator readiness/liveness paths. Worker uses the independently packaged JDK
`worker-probe.jar` from Task 16 and never invokes a shell. Until that artifact exists,
`verify-deployment.mjs` reports `BLOCKED_TOOLCHAIN` for the container-execution check while
structural chart tests remain executable.

Run the Node test again. Expected GREEN for schema and rendered identity cross-binding.

- [ ] **Step 3: Write RED L3/L4 route-mode and application-TLS tests**

Add positive/negative fixture generation inside `deployment-contract.test.mjs`. Prove:

- `KUBERNETES_SERVICE` emits standard NetworkPolicy with both namespace and pod selectors;
- `EGRESS_GATEWAY` permits only the named gateway workload;
- `CILIUM_FQDN` emits only CiliumNetworkPolicy, rejects `*` and suffix wildcards, and has no claim
  that standard NetworkPolicy selects the FQDN;
- `AUDITED_CIDR` rejects `0.0.0.0/0`, `::/0`, overlap with metadata endpoints, missing digest,
  expired evidence, and absent owner;
- cross-component destinations and broad namespace-only policies fail;
- Temporal port 7233 is insufficient without worker CA/client-cert/server-name binding;
- PostgreSQL endpoints require verify-full plus CA/server name; OTLP production endpoints require
  HTTPS/gRPC TLS and a CA reference;
- a network-pass/TLS-fail fixture remains failed.

Run the Node test. Expected RED: network templates and policy are absent.

- [ ] **Step 4: Implement exact network and TLS policies**

`networkpolicies.yaml` renders default-deny ingress/egress, DNS limited to the selected cluster DNS
pods, component ingress, Kubernetes-service destinations, and egress-gateway destinations.
`cilium-networkpolicies.yaml` renders only the selected exact FQDN mode. Audited CIDRs render exact
`ipBlock` entries with evidence/expiry annotations that Rego verifies.

`accord.rego` rejects mutable images, missing security context/resources/probes/PDB/spread, shared
identity/secret/role tuples, automatic tokens, host namespaces/paths, privileged ports, broad
egress, inline secret data, source/content credential names, a route-mode/template mismatch, and
any attempt to label a port allow as TLS evidence.

Application TLS evidence is checked from rendered Deployment configuration plus Task 13/17
handshake receipts. The policy never reports TLS `PASS` from YAML alone.

Run:

~~~powershell
node scripts/verification/preflight.mjs --check-id ft15-policy-toolchain --require helm --require kubeconform --require conftest
node scripts/deployment/verify-deployment.mjs --mode test
~~~

On the known machine without Helm/Kubeconform/Conftest, expected result is `BLOCKED_TOOLCHAIN` and
exit 2. On a provisioned runner, expected GREEN includes Helm lint, structural parse, Kubeconform,
Conftest, and every named negative fixture failing for its intended rule.

- [ ] **Step 5: Write RED OpenTofu contract and real-adapter tests**

The contract module variables are closed objects for components, route modes, TLS endpoints,
external-secret references, workload identity, object capability report/receipt, backup/PITR
policy, and remote SHA/tree. Variable validation and `check` blocks enforce all cross-field
conditions. Outputs are nonsensitive IDs/digests only.

`local-kubernetes.tftest.hcl` uses `mock_provider "kubernetes"` and `mock_provider "helm"`. Assert
the plan has exactly one namespace and one Helm release, passes one rendered values path, exposes
no secret value, and fails for shared identity, wrong role, mutable image, broad network, missing
TLS binding, expired CIDR evidence, local object profile used as production, and mismatched remote
SHA.

Run:

~~~powershell
node scripts/verification/preflight.mjs --check-id ft15-tofu-toolchain --require tofu
tofu -chdir=infra/opentofu/environments/local-kubernetes init -backend=false
tofu -chdir=infra/opentofu/environments/local-kubernetes validate
tofu -chdir=infra/opentofu/environments/local-kubernetes test
~~~

Expected RED on a provisioned runner because modules do not exist. On the known machine the
preflight must instead emit `BLOCKED_TOOLCHAIN`; the missing executable is not a test pass.

- [ ] **Step 6: Implement contract checks and the real local Kubernetes adapter**

`accord-foundation-contract` declares no provider and no resource. `checks.tf` validates exact
component keys/tuples, endpoint TLS schemes, unique identities, route-mode branches, immutable
digests, and the capability report/receipt binding. It returns normalized nonsensitive objects.

`local-kubernetes/versions.tf` pins OpenTofu `= 1.9.1`, the HashiCorp Kubernetes provider, and the
HashiCorp Helm provider. `main.tf` contains these real resources:

~~~hcl
resource "kubernetes_namespace_v1" "accord" {
  metadata {
    name = var.namespace
    labels = {
      "accord.inforvans.com/environment" = var.environment_name
    }
  }
}

resource "helm_release" "accord" {
  name             = "accord"
  namespace        = kubernetes_namespace_v1.accord.metadata[0].name
  chart            = abspath("../../../helm/accord")
  dependency_update = false
  atomic           = true
  wait             = true
  timeout          = 600
  values           = [file(var.rendered_values_path)]
}
~~~

The adapter never provisions production cloud identity or claims it has. A selected production
provider/environment adapter and its OIDC, secret-store, network, backup, and restore evidence are
required by the environment verdict in Task 17.

Run validate/tests again on a provisioned runner. Expected GREEN with two real resource addresses
and all negative runs rejected.

- [ ] **Step 7: Write RED Argo immutable-revision tests and generate from remote authority**

`project.yaml` defines one AppProject with repository/destination allowlists, namespace-scoped
resource kinds, sync windows, and no cluster-admin or Secret write permission.

`render-argocd.mjs` accepts a canonical CI context from Task 16 or explicit `--remote-url`,
`--remote-ref`, and `--remote-sha`. It performs `git ls-remote --exit-code <url> <ref>`, requires the
returned SHA to equal the input, and writes `application.yaml` atomically. Without authoritative
network evidence it emits `BLOCKED_EXTERNAL_ENVIRONMENT` and does not write a mutable revision.

The generator assigns the verified value directly rather than substituting text into a shell or
committed template:

~~~javascript
application.spec.source.path = 'infra/helm/accord';
application.spec.source.targetRevision = context.remote_sha;
application.spec.syncPolicy = {
  automated: { prune: true, selfHeal: true },
  syncOptions: ['ServerSideApply=true'],
};
await writeYamlAtomically(outputPath, application);
~~~

Tests reject branch names, tags, `HEAD`, a local SHA not returned by
`ls-remote`, a mismatched promotion SHA, mutable image values, and a repository URL outside the
AppProject allowlist.

- [ ] **Step 8: Verify, stage the infrastructure commit, then bind Argo to its remote SHA**

First run all locally executable tests and the tool preflight:

~~~powershell
node --test tests/integration/deployment-contract.test.mjs
node scripts/verification/preflight.mjs --check-id ft15-toolchain --require node --require helm --require tofu --require conftest --require kubeconform
node scripts/deployment/verify-deployment.mjs --mode test
tofu -chdir=infra/opentofu/environments/local-kubernetes test
git diff --check
~~~

The exact `ft15-toolchain` preflight runs before any Helm, OpenTofu, Conftest, or Kubeconform
invocation in this step. Exit 2 preserves its closed receipt and stops immediately: do not run the
deployment verifier, raw `tofu`, or any dependent tool, and do not stage a pass claim. Only exit 0
may proceed. An absent `helm`, `tofu`, `conftest`, or `kubeconform` produces
`BLOCKED_TOOLCHAIN`; raw command-not-found output is never accepted as blocked evidence.

On a provisioned runner, stage the exact infrastructure paths excluding the not-yet-generated
Argo Application:

~~~powershell
$task15InfrastructurePaths = @(
  'contracts/deployment/component-identity.schema.json'
  'contracts/deployment/promotion-input.schema.json'
  'infra/helm/accord/Chart.yaml'
  'infra/helm/accord/values.yaml'
  'infra/helm/accord/values.schema.json'
  'infra/helm/accord/templates/_helpers.tpl'
  'infra/helm/accord/templates/serviceaccounts.yaml'
  'infra/helm/accord/templates/externalsecrets.yaml'
  'infra/helm/accord/templates/workloads.yaml'
  'infra/helm/accord/templates/services.yaml'
  'infra/helm/accord/templates/networkpolicies.yaml'
  'infra/helm/accord/templates/cilium-networkpolicies.yaml'
  'infra/helm/accord/templates/poddisruptionbudgets.yaml'
  'infra/policy/accord.rego'
  'infra/opentofu/modules/accord-foundation-contract/variables.tf'
  'infra/opentofu/modules/accord-foundation-contract/checks.tf'
  'infra/opentofu/modules/accord-foundation-contract/outputs.tf'
  'infra/opentofu/environments/local-kubernetes/versions.tf'
  'infra/opentofu/environments/local-kubernetes/variables.tf'
  'infra/opentofu/environments/local-kubernetes/main.tf'
  'infra/opentofu/environments/local-kubernetes/outputs.tf'
  'infra/opentofu/environments/local-kubernetes/tests/local-kubernetes.tftest.hcl'
  'infra/argocd/project.yaml'
  'scripts/deployment/render-values.mjs'
  'scripts/deployment/render-argocd.mjs'
  'scripts/deployment/verify-deployment.mjs'
  'tests/integration/deployment-contract.test.mjs'
  'tests/integration/deployment-contract.ps1'
  'tests/integration/fixtures/deployment/valid-test-promotion.json'
  'tests/integration/fixtures/deployment/invalid-shared-identity.json'
  'tests/integration/fixtures/deployment/invalid-broad-egress.json'
  'tests/integration/fixtures/deployment/invalid-plaintext-secret.json'
)
git add -- $task15InfrastructurePaths
git diff --cached --name-only
git commit -m "build: bind isolated deployment identities"
~~~

After that commit is pushed by the authorized operator/CI and its exact remote SHA is known:

~~~powershell
node scripts/deployment/render-argocd.mjs --remote-url $env:ACCORD_REMOTE_URL --remote-ref $env:ACCORD_REMOTE_REF --remote-sha $env:ACCORD_REMOTE_SHA --output infra/argocd/application.yaml
git add -- infra/argocd/application.yaml
git diff --cached --name-only
git commit -m "build: pin Accord GitOps revision"
~~~

Expected: the first staged set has no application with a guessed revision; the second contains only
the generated Application; its `targetRevision` is the verified remote SHA. Without remote,
Kubernetes, Argo, external-secret, OIDC, PKI, and production capability evidence, Task 17 records
the environment checks as `BLOCKED` rather than treating rendered YAML as deployment success.

### Task 16: Produce Provider-Neutral, Digest-Bound Release Evidence

**Goal:** Run one provider-neutral verification/release core, build reproducible runtime artifacts from
Task 13's sole image lock, emit SBOMs and scans, sign and attest exact digests with Cosign/ORAS and
closed DSSE predicates, and use GitHub Actions only as the thin adapter for the Accord source
repository.

**Files:**
- Create: `contracts/ci/ci-context.schema.json`
- Create: `contracts/dsse-payloads/build-provenance.schema.json`
- Create: `contracts/supply-chain/release-manifest.schema.json`
- Create: `contracts/golden-fixtures/supply-chain/ci-context.json`
- Create: `contracts/golden-fixtures/supply-chain/build-provenance.json`
- Create: `apps/control-plane/api/Dockerfile`
- Create: `apps/control-plane/worker/Dockerfile`
- Create: `apps/webhook-edge/Dockerfile`
- Create: `apps/control-plane/worker/src/probe/java/com/inforvans/accord/controlplane/worker/probe/WorkerProbe.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/probe/WorkerProbeTest.java`
- Create: `.dockerignore`
- Create: `scripts/ci/create-ci-context.mjs`
- Create: `scripts/ci/adapters/github-actions-context.mjs`
- Create: `scripts/ci/render-runtime-dockerfiles.mjs`
- Create: `scripts/ci/seed-gradle-cache.mjs`
- Create: `scripts/ci/build-images.mjs`
- Create: `scripts/ci/generate-sboms.mjs`
- Create: `scripts/ci/scan-artifacts.mjs`
- Create: `scripts/ci/attest-artifacts.mjs`
- Create: `scripts/ci/verify-release-chain.mjs`
- Create: `scripts/ci/package-accordctl.mjs`
- Create: `scripts/ci/verify.mjs`
- Create: `scripts/ci/verify.ps1`
- Create: `tests/bootstrap/supply-chain-policy.test.mjs`
- Create: `tests/bootstrap/ci-provider-neutral.test.mjs`
- Create: `tests/bootstrap/release-chain.test.mjs`
- Create: `.github/workflows/verify.yml`
- Create: `.github/workflows/release-images.yml`
- Create: `.github/workflows/release-accordctl.yml`
- Create: `renovate.json`
- Modify: `.tool-versions`
- Modify: `.gitignore`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `apps/control-plane/worker/gradle.lockfile`
- Modify: `gradle/verification-metadata.xml`

Task 16 does not create or rewrite an image lock. All Dockerfile base references come from
`infra/images/images.lock.json`. First-party image/archive digests are outputs in the release
manifest, not third-party lock entries.

**Authoritative invariants:**

1. `ci-context.schema.json` is provider-neutral and closed. It binds CI system, authoritative remote
   URL/ref/SHA, base SHA, tree SHA, run/workflow IDs, builder issuer/subject/audience, registry
   repository, protected environment, and creation time. Core scripts read only that JSON, never
   `GITHUB_*`, `CI_*`, branch aliases, or `origin/main`.
2. `github-actions-context.mjs` is the only code that reads GitHub environment names. It maps the
   Accord repository event into the canonical context after verifying the event SHA/ref and remote.
   GitHub hosting Accord does not make GitHub the product Git Provider; product fixtures remain
   GitLab-first.
3. Preflight verifies exact versions for Node, Java, Gradle wrapper, pnpm, uv, Buf, Helm, OpenTofu,
   Conftest, Kubeconform, Syft, Cosign, Trivy, ORAS, Docker/Buildx, and Git. Add
   only `oras 1.2.2` to the Task 13 `.tool-versions`; do not replace or rewrite its normalized core
   pins `docker 29.4.2`, `docker-buildx 0.33.0`, or `git 2.52.0`. Missing or mismatched tools emit one
   `BLOCKED_TOOLCHAIN` result with required/complete observed version and exit 2; no check is skipped
   or converted to pass. Full FT16 preflight and Task 17 acceptance require an independent
   `docker-buildx` result; a passing `docker` result cannot satisfy or subsume it.
4. All native execution uses argument arrays with `shell: false`. The PowerShell 5.1 wrapper only
   launches `node scripts/ci/verify.mjs` and propagates its exit code.
5. Dockerfiles are rendered from the `java-build` and `java-runtime` entries in the Task 13 lock.
   Every effective `FROM` is an exact canonical repository digest. Overrides are accepted only when
   byte-equal to the same locked role/platform reference.
6. Build stages use a verified content-addressed Gradle cache, `--offline`,
   `--dependency-verification=strict`, and BuildKit `RUN --network=none`. Runtime images are
   shell-free, fixed non-root UIDs, read-only-root compatible, and contain one application JAR.
7. Source, each runtime image, and each platform `accordctl` archive receive CycloneDX JSON and SPDX
   JSON SBOMs. Every SBOM names the exact subject digest; schema/subject/package/license validation
   occurs before signing.
8. Trivy scans exact pushed image/archive digests and source dependencies. A HIGH or CRITICAL
   finding fails unless a separately signed, unexpired exception names the same finding and subject
   digest. Foundation creates no unsigned exception path.
9. Cosign signatures and attestations are keyless and bind the immutable subject. ORAS attaches
   SBOM, scan, DSSE bundle, and release manifest artifacts by subject digest, then lists/refetches
   referrers and verifies their digests. A tag is never a verification subject.
10. Build provenance is a DSSE envelope with exact `payloadType:
    application/vnd.in-toto+json`, predicate type
    `https://schemas.accord.inforvans.com/provenance/build/v1`, and domain
    `accord.build-provenance.v1`. The predicate binds builder issuer/subject/audience, CI system,
    remote SHA/tree/base SHA, image-lock digest, dependency-lock digests, build parameters, subject
    digest, SBOM digests, and scan digest.
11. Verification checks DSSE envelope structure, payload type, predicate type/domain, canonical
    subject digest, builder identity/trust policy, certificate issuer, remote SHA/tree, lock
    materials, SBOM subjects, scan policy, signature bundle, and ORAS referrer digests. Any mismatch
    prevents promotion.
12. The release manifest is closed and lists every artifact with exact digest, platform, SBOMs,
    scan, signature bundle, provenance envelope, and immutable locator. GitOps receives only entries
    whose entire chain verifies.
13. Workflows use full action commit SHAs, minimum permissions, bounded timeouts, protected release
    environments, and OIDC. Artifact upload on failure may use `if: always()`, but verification,
    build, scan, sign, attest, verify, and promotion steps never use `continue-on-error` or `|| true`.

- [ ] **Step 1: Write RED canonical CI-context and provider-neutrality tests**

`ci-provider-neutral.test.mjs` validates the schema and golden fixture, then scans every core file
under `scripts/ci` except `adapters`. Reject `GITHUB_`, `github.com`, `CI_COMMIT`,
`origin/main`, branch-derived acceptance SHA, provider API calls, shell-built commands, and
environment-specific OIDC assumptions.
It also proves Task 16's `.tool-versions` delta adds only `oras 1.2.2` and preserves Task 13's Docker,
Buildx, and Git pins byte-for-byte.

The canonical fixture has these exact field classes:

~~~json
{
  "schema_version": "1.0.0",
  "ci_system": "test",
  "remote_url": "ssh://git.example.invalid/accord.git",
  "remote_ref": "refs/heads/foundation",
  "remote_sha": "1111111111111111111111111111111111111111",
  "base_sha": "2222222222222222222222222222222222222222",
  "tree_sha": "3333333333333333333333333333333333333333",
  "run_id": "fixture-run-1",
  "workflow_id": "fixture-workflow-1",
  "builder_issuer": "https://issuer.example.invalid",
  "builder_subject": "repo:accord:ref:refs/heads/foundation",
  "builder_audience": "sigstore",
  "registry_repository": "registry.example.invalid/accord",
  "protected_environment": "fixture",
  "created_at": "2026-07-26T00:00:00Z"
}
~~~

The reserved example host and repeated SHAs are test fixture data and are rejected whenever
`environment_class=production`.

Run:

~~~powershell
node --test tests/bootstrap/ci-provider-neutral.test.mjs
~~~

Expected RED: schema, canonical creator, adapter boundary, and fixture are absent.

- [ ] **Step 2: Implement canonical context creation and fail-closed preflight**

`create-ci-context.mjs` accepts explicit arguments or an adapter-produced JSON object, obtains
`remote_sha` and `tree_sha` from Git with array arguments, verifies `base_sha` is an ancestor, and
atomically writes canonical JSON. It never resolves a symbolic base during verification.

`github-actions-context.mjs` requires the Accord repository identity, protected event/ref,
`GITHUB_SHA`, server URL, repository, run IDs, OIDC issuer/subject/audience policy, and registry
repository. It verifies `git rev-parse HEAD` equals the event SHA and emits the generic context.
No product GitLab credential or Provider endpoint enters this adapter.

Run:

~~~powershell
node scripts/verification/preflight.mjs --check-id ft16-toolchain --require node --require java --require pnpm --require uv --require buf --require helm --require tofu --require conftest --require kubeconform --require syft --require cosign --require trivy --require oras --require docker --require docker-buildx --require git
~~~

On the known Windows host, expected status is `BLOCKED`/`BLOCKED_TOOLCHAIN` naming absent
`uv`, `buf`, `helm`, `tofu`, `conftest`, `kubeconform`, `syft`, `cosign`, `trivy`, and `oras` with
their pinned versions. Evidence contains separate ordered `docker` and `docker-buildx` check results
even when both are available. It must not continue to a PASS summary.

- [ ] **Step 3: Write RED single-lock Dockerfile, cache, and worker-probe tests**

`supply-chain-policy.test.mjs` parses Dockerfiles, Gradle tasks, and the sole image lock. Prove:

- exactly one repository image lock exists;
- each `ARG` used by `FROM` has a digest default rendered from `java-build`/`java-runtime`;
- effective refs equal the lock's platform digest and no tag/mutable override exists;
- builder runs offline, strict verification, and network-none using only a verified cache;
- runtime is shell-free and uses UID 10001/10002/10003 for API/worker/edge;
- worker image contains `/opt/accord/bin/worker-probe.jar` and uses `/usr/bin/java`;
- reproducible archive/JAR flags and `SOURCE_DATE_EPOCH` are fixed;
- `.dockerignore` denies Git data, local state, credentials, builds, environment files, and arbitrary
  caches while allowing only the verified cache artifact/manifest.

`WorkerProbeTest` covers exact `ready|live` arguments, 5-300 second bound, missing/expired/future/
malformed/oversized state, symlink substitution, non-regular file, wrong POSIX mode/owner, and a
two-second maximum. Output is only `worker-probe:<fixed-code>`.

Expected RED: Dockerfiles, renderer, verified cache flow, and probe do not exist.

- [ ] **Step 4: Implement reproducible images and host-independent probe**

`render-runtime-dockerfiles.mjs` is the only Dockerfile writer. It reads
`infra/images/images.lock.json`, selects the requested platform digests, and constructs the exact
lines from resolved lock values:

~~~javascript
const buildImage = lockedReference(lock, 'java-build', platform);
const runtimeImage = lockedReference(lock, 'java-runtime', platform);
const dockerfile = [
  `ARG BUILD_IMAGE=${buildImage}`,
  'FROM ${BUILD_IMAGE} AS build',
  `ARG RUNTIME_IMAGE=${runtimeImage}`,
  'FROM ${RUNTIME_IMAGE}',
].join('\n');
~~~

`lockedReference` returns only `registry/repository@sha256:<64 lowercase hex>`. Verification
rerenders in memory and requires byte equality.

`seed-gradle-cache.mjs` creates a fresh task-scoped `GRADLE_USER_HOME`, resolves only locked
configurations through approved mirrors with strict metadata, removes credentials/daemon/build
state, and writes a sorted SHA-256 manifest tied to wrapper, Java, locks, and verification metadata.
The Docker build receives only that cache directory and manifest.

`WorkerProbe` is JDK-only. It reads only `/tmp/accord/worker-ready` or `worker-live` with
`NOFOLLOW_LINKS`, exact owner/mode/type, ASCII epoch plus LF, size <=32, and fixed age/skew rules.
It never prints a path, content, user name, exception, or stack trace.

Run focused Node/Java tests, render Dockerfiles twice, and compare bytes. Expected GREEN before any
image push.

- [ ] **Step 5: Write RED SBOM, scan, DSSE, signature, and ORAS-chain tests**

`build-provenance.schema.json` is closed and requires the exact predicate type/domain and all
authority/material/subject fields above. `release-manifest.schema.json` is closed, key-sorted, and
requires every artifact chain digest.

`release-chain.test.mjs` uses fixed local fixture bytes and mocked process results to prove:

- source/image/archive SBOM subject mismatch fails;
- missing CycloneDX or SPDX document fails;
- HIGH/CRITICAL scan result fails without an exact signed exception;
- DSSE payload type, predicate type, domain, builder issuer/subject/audience, remote SHA/tree,
  lock/material digest, or subject change fails;
- a signature verified against the wrong artifact, issuer, identity, or trust policy fails;
- an ORAS referrer under a tag or different subject fails;
- missing, duplicate, extra, or stale release-manifest evidence fails;
- no command receives a secret/token as an argv value and captured diagnostics contain no token.

Run:

~~~powershell
node --test tests/bootstrap/release-chain.test.mjs
~~~

Expected RED: SBOM, scan, attestation, and chain scripts are absent.

- [ ] **Step 6: Implement exact-digest SBOM, scan, signing, attestation, and verification**

`generate-sboms.mjs` runs the root CycloneDX Gradle task and Syft for source, each OCI digest, and
each CLI archive. It validates format/version, sorted package identifiers, license fields, and exact
subject digest, then hashes outputs.

`scan-artifacts.mjs` runs Trivy against exact subjects and writes canonical JSON. It fails on every
unexcepted HIGH/CRITICAL finding. Scan database identity/version and vulnerability exception digest
are included in evidence.

`attest-artifacts.mjs` builds an in-toto statement from the canonical CI context and release facts,
validates the predicate, calls Cosign keyless sign/attest by digest, and calls ORAS attach with
explicit artifact types. OIDC token material is obtained by the tool's protected identity flow,
never written into context, argv, logs, or evidence.

`verify-release-chain.mjs` immediately refetches registry subjects/referrers, verifies image/archive
digest, Cosign identity/issuer/signature, DSSE envelope/payload/predicate, remote and builder facts,
SBOM/scan subjects, and release manifest. It emits promotion input only after all checks PASS.

Run on a provisioned protected runner:

~~~powershell
node scripts/ci/generate-sboms.mjs --context build/ci/context.json
node scripts/ci/scan-artifacts.mjs --context build/ci/context.json
node scripts/ci/attest-artifacts.mjs --context build/ci/context.json
node scripts/ci/verify-release-chain.mjs --context build/ci/context.json
~~~

Expected GREEN: every digest in the release manifest is independently recomputed and the promotion
input binds the exact remote SHA. Missing tools, OIDC, registry, or network yield `BLOCKED` evidence,
not an unsigned local substitute.

- [ ] **Step 7: Implement the read-only verification entry point and CLI packaging**

`verify.mjs` runs, in order: design/workspace/supply-chain static tests; full tool preflight; verified
cache restore; frozen pnpm and locked uv sync; offline strict Gradle; browser/OpenAPI; Buf lint/build/
breaking against `ci-context.base_sha`; Python lint/types/tests; image-lock all-consumer verification;
deployment policy/Tofu; SBOM validation; scan policy; and a full tracked/untracked status check. It
never regenerates locks, schemas, code, Dockerfiles, or manifests.

`package-accordctl.mjs` builds/test-runs host-specific jlink archives on Windows, Linux, and macOS
without host Java in child PATH, then creates checksums/SBOM/scan/provenance/signature chains.
Windows Authenticode and macOS notarization are separate environment evidence. Their absence blocks
GA for that platform without invalidating already verified generic Cosign evidence.

Use this PS5.1 wrapper only:

~~~powershell
$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'verify.mjs') @args
exit $LASTEXITCODE
~~~

Expected: the wrapper and Node entry point return 0/1/2 for PASS/FAIL/BLOCKED respectively.

- [ ] **Step 8: Add thin GitHub Actions adapters and controlled updates**

All three workflows pin actions to full 40-character commit SHAs and map the GitHub event into
`build/ci/context.json` before invoking Node. `verify.yml` has read-only repository permissions.
Release workflows add only `id-token: write` and registry package permission in protected
environments. They verify a protected semantic tag points to the context SHA, build independently
for `linux/amd64` and `linux/arm64`, and promote only the verified release manifest.

`ci-provider-neutral.test.mjs` parses workflow YAML and proves the workflows contain adapter calls
but the core contains no GitHub variable/API. It also proves there is no requirement for a
`.gitlab-ci.yml` merely because GitLab is the product Provider.

`renovate.json` groups Java/Gradle, browser, Python, container lock, infrastructure, and security
tool updates; enforces seven-day minimum release age except signed security emergencies; and never
automerge major/runtime/crypto/base-image changes.

- [ ] **Step 9: Run full FT16 verification and commit declared delivery paths**

On the known machine, run preflight first and retain the `BLOCKED_TOOLCHAIN` result; do not execute
later commands as if tools existed. On a fully provisioned runner:

~~~powershell
node --test tests/bootstrap/supply-chain-policy.test.mjs tests/bootstrap/ci-provider-neutral.test.mjs tests/bootstrap/release-chain.test.mjs
node scripts/images/verify-images.mjs --scope all
node scripts/ci/render-runtime-dockerfiles.mjs --mode verify
node scripts/ci/verify.mjs --context build/ci/context.json
git diff --check
$task16Paths = @(
  'contracts/ci/ci-context.schema.json'
  'contracts/dsse-payloads/build-provenance.schema.json'
  'contracts/supply-chain/release-manifest.schema.json'
  'contracts/golden-fixtures/supply-chain/ci-context.json'
  'contracts/golden-fixtures/supply-chain/build-provenance.json'
  'apps/control-plane/api/Dockerfile'
  'apps/control-plane/worker/Dockerfile'
  'apps/webhook-edge/Dockerfile'
  'apps/control-plane/worker/src/probe/java/com/inforvans/accord/controlplane/worker/probe/WorkerProbe.java'
  'apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/probe/WorkerProbeTest.java'
  'apps/control-plane/worker/build.gradle'
  'apps/control-plane/worker/gradle.lockfile'
  '.dockerignore'
  '.gitignore'
  '.tool-versions'
  'scripts/ci/create-ci-context.mjs'
  'scripts/ci/adapters/github-actions-context.mjs'
  'scripts/ci/render-runtime-dockerfiles.mjs'
  'scripts/ci/seed-gradle-cache.mjs'
  'scripts/ci/build-images.mjs'
  'scripts/ci/generate-sboms.mjs'
  'scripts/ci/scan-artifacts.mjs'
  'scripts/ci/attest-artifacts.mjs'
  'scripts/ci/verify-release-chain.mjs'
  'scripts/ci/package-accordctl.mjs'
  'scripts/ci/verify.mjs'
  'scripts/ci/verify.ps1'
  'tests/bootstrap/supply-chain-policy.test.mjs'
  'tests/bootstrap/ci-provider-neutral.test.mjs'
  'tests/bootstrap/release-chain.test.mjs'
  '.github/workflows/verify.yml'
  '.github/workflows/release-images.yml'
  '.github/workflows/release-accordctl.yml'
  'renovate.json'
  'gradle/verification-metadata.xml'
)
git add -- $task16Paths
git diff --cached --name-only
git commit -m "build: attest provider-neutral release artifacts"
~~~

Expected: staged files contain no second image lock, provider-specific core branch, mutable image,
unsigned promotion, or missing-tool bypass. Every produced artifact is reachable from one verified
release manifest through exact digest-bound SBOM, scan, signature, DSSE, and ORAS evidence.

### Task 17: Accept One Authoritative Remote SHA With Two Honest Verdicts

**Goal:** Verify an authoritative remote commit in a detached clean worktree, demonstrate the minimum
GitLab/API/worker/Temporal/telemetry Foundation loop, and emit separate closed code and environment
verdicts whose overall result is PASS only when both are PASS for the same evidence chain.

**Files:**
- Create: `contracts/acceptance/blocked-evidence.schema.json`
- Create: `contracts/acceptance/foundation-verdict.schema.json`
- Create: `contracts/acceptance/foundation-summary.schema.json`
- Create: `contracts/golden-fixtures/acceptance/blocked-toolchain.json`
- Create: `contracts/golden-fixtures/acceptance/code-pass.json`
- Create: `contracts/golden-fixtures/acceptance/environment-blocked.json`
- Create: `scripts/acceptance/resolve-remote-sha.mjs`
- Create: `scripts/acceptance/create-detached-worktree.mjs`
- Create: `scripts/acceptance/run-foundation-demo.mjs`
- Create: `scripts/acceptance/run-foundation-acceptance.mjs`
- Create: `scripts/acceptance/collect-environment-evidence.mjs`
- Create: `scripts/acceptance/merge-verdicts.mjs`
- Create: `tests/integration/foundation-acceptance.test.mjs`
- Create: `tests/integration/foundation-demo.test.mjs`
- Create: `tests/integration/foundation-acceptance.ps1`
- Create: `tests/integration/verify-sensitive-logs.mjs`
- Create: `tests/integration/src/test/java/com/inforvans/accord/integration/FoundationProductLoopIT.java`
- Create: `tests/architecture/verify-platform-foundation.mjs`
- Create: `docs/architecture/platform-foundation.md`
- Modify: `tests/integration/build.gradle`
- Modify: `tests/integration/gradle.lockfile`
- Modify: `gradle/verification-metadata.xml`
- Modify: `.gitignore`

Verdict/evidence output goes to the caller-selected directory, default
`path.join('build', 'acceptance', remoteSha)` in the invoking checkout. The detached source worktree
receives no generated evidence, lock updates, manifests, or source edits.

**Authoritative invariants:**

1. Acceptance requires `--remote-url`, full `--remote-ref`, and lowercase 40-64 hex
   `--remote-sha`. `resolve-remote-sha.mjs` queries the authoritative remote, requires exactly one
   matching ref/SHA, fetches that object, and verifies its tree. A local branch, local `main`,
   remote-tracking ref, event label, tag text, or merge-base is not authority.
2. `create-detached-worktree.mjs` creates a fresh OS-temporary Git worktree with `--detach` at the
   verified SHA. Inside it, `HEAD` equals the requested SHA, `git symbolic-ref -q HEAD` fails,
   `git status --porcelain=v1 --untracked-files=all` is empty, submodules are absent or exact, and
   the tree SHA matches remote evidence.
3. Every acceptance command runs from the detached worktree with argument arrays and writes only
   into the external evidence directory or ignored build/cache paths. Before and after execution,
   tracked diff plus strict status including all non-ignored untracked files must be empty.
4. `foundation-verdict.schema.json` has exactly `CODE` and `ENVIRONMENT` kinds. Status is exactly
   `PASS`, `FAIL`, or `BLOCKED`. `FAIL` means a check ran and an assertion failed. `BLOCKED` means a
   required tool or, after its implementation boundary is proven present, an external authority,
   service, credentialless trust path, capability, or evidence was unavailable. Missing or broken
   repository-owned implementation/test wiring is `FAIL`/`ASSERTION_FAILED`, never `BLOCKED`.
   Neither verdict is converted to the other.
5. A code verdict binds remote URL/ref/SHA, tree SHA, image-lock digest, dependency-lock digests,
   toolchain results, check results, demo evidence, start/end time, and evidence bundle digest.
6. An environment verdict binds the same SHA/tree/image lock plus remote branch protection, mirror,
   OIDC, registry/referrers, Kubernetes, Argo, PKI, external-secret, production PostgreSQL/RPO/RTO,
   object capability, restore-test, and signing/notarization evidence. In either verdict,
   `release_manifest_digest` may be null only when status is `BLOCKED`; `PASS` and `FAIL` require the
   digest of the exact release manifest under evaluation.
7. `blocked-evidence.schema.json` requires common remote SHA, tree SHA, check ID, reason code,
   attempted argv, attempt exit code or null, bounded evidence-digest array, rerun argv, UTC time,
   and accountable owner. Its `TOOLCHAIN` branch is selected only by `BLOCKED_TOOLCHAIN`, requires
   `tool{name,required_version,observed_version|null}`, and forbids `external`. Its `EXTERNAL` branch
   handles every other reason, requires
   `external{capability,authority,required_evidence_type}`, and forbids `tool`. Both branches forbid
   raw stdout/stderr, token, credential, certificate private material, and arbitrary exception text.
8. Missing `uv`, `buf`, `helm`, `tofu`, `conftest`, `kubeconform`, `syft`, `cosign`, `trivy`, or
   `oras` on the known PowerShell 5.1 host creates one `BLOCKED_TOOLCHAIN` entry per check and makes
   the code verdict `BLOCKED`. PowerShell 5.1 itself is supported only through thin Node launchers;
   absence of `pwsh` is not bypassed and no plan command requires it.
9. Missing authoritative branch-protection, OIDC, registry, Kubernetes, Argo, external-secret, PKI,
   or production capability evidence creates named external `BLOCKED` evidence and makes the
   environment verdict `BLOCKED`. Rendered files, local Docker health, or caller-supplied
   `verified=true` booleans cannot satisfy those checks.
10. `foundation-summary.schema.json` binds the two verdict digests. Overall is `PASS` only when both
    status values are PASS and remote SHA, tree SHA, image-lock digest, release-manifest digest, and
    evidence policy version are byte-equal. Any FAIL makes overall FAIL; otherwise overall is
    BLOCKED.
11. GitHub branch/OIDC evidence concerns only the Accord source/release repository. Product
    behavior evidence remains GitLab-first and exercises Task 11's Standard Webhook contract.
12. The minimum demonstration has two honest external entry legs in one evidence bundle:
    GitLab webhook authentication/deduplication in the isolated edge database, and Task 9's
    idempotent API command through Task 12 durable delivery into Task 10's identifier-only
    `ReadOnlyReconciliationActivity`. No test-only cross-database bridge pretends the edge outbox is
    already a production Provider Connector.
13. The API/worker leg starts the real Task 13 Temporal mTLS server and uses Task 10's
    `ReconciliationWorkflowRef`, production `FencedReconciliationObservation`, and a test-owned
    bounded low-level `ProviderObservationPort` on each attempt. It proves safe-snapshot scope, tx1
    claim, transaction-free observation, tx2 finalize, durable retry/receipt, and zero Provider
    mutations. Missing `ReadOnlyReconciliationActivity`, `FencedReconciliationObservation`, Task 12
    `EventTransport`, the bounded low-level adapter, or the real mTLS integration is a CODE `FAIL` with
    `ASSERTION_FAILED`. `BLOCKED_BY_PROVIDER_ADAPTER` is reserved strictly for an unavailable real
    external Provider authority/capability/evidence after all those implementation boundaries are
    present and their local contract assertions pass.
14. Typed telemetry from the demo contains the fixed operation/result facts and zero seeded
    sentinels in spans, metrics, logs, collector JSONL, and labels.

- [ ] **Step 1: Write RED verdict, blocked-evidence, and summary contract tests**

`foundation-acceptance.test.mjs` validates all golden fixtures and rejects:

- unknown fields, unknown status/kind/reason, missing owner/time/rerun command, or unordered checks;
- `PASS` with any blocked/failed child check;
- `FAIL` without an executed assertion and evidence digest;
- `BLOCKED` without blocked evidence;
- toolchain blocked evidence without required/observed tool version, external blocked evidence
  without capability/authority/evidence type, or either branch without attempted argv;
- `BLOCKED_BY_PROVIDER_ADAPTER` when a repository-owned activity, production fenced orchestrator,
  transport, bounded low-level test adapter, or real mTLS integration is absent/broken, or when
  external evidence does not name a real Provider
  authority/capability; those implementation cases must be CODE `FAIL`/`ASSERTION_FAILED`;
- code/environment SHA, tree, lock, release manifest, or policy-version mismatch;
- overall PASS when either verdict is not PASS;
- stdout/stderr, token, cookie, PEM, private key, raw webhook body, source, or diff fields anywhere.

Use this exact blocked reason set:

~~~text
BLOCKED_TOOLCHAIN
BLOCKED_REMOTE_AUTHORITY
BLOCKED_BRANCH_PROTECTION
BLOCKED_MIRROR
BLOCKED_OIDC
BLOCKED_REGISTRY
BLOCKED_KUBERNETES
BLOCKED_ARGOCD
BLOCKED_EXTERNAL_SECRET
BLOCKED_PKI
BLOCKED_DATABASE_RECOVERY
BLOCKED_OBJECT_CAPABILITY
BLOCKED_PLATFORM_SIGNING
BLOCKED_BY_PROVIDER_ADAPTER
~~~

Run:

~~~powershell
node --test tests/integration/foundation-acceptance.test.mjs
~~~

Expected RED: schemas, fixtures, and merge logic are absent.

- [ ] **Step 2: Implement deterministic verdict and blocked-evidence writers**

All three schemas are closed JSON Schema 2020-12 contracts with conditional branches for
PASS/FAIL/BLOCKED. `merge-verdicts.mjs` loads schemas, recomputes both file digests, checks the
shared binding tuple, applies FAIL-over-BLOCKED-over-PASS precedence, and atomically writes the
summary.

Check entries have this bounded semantic shape:

~~~json
{
  "check_id": "toolchain.syft",
  "status": "BLOCKED",
  "reason_code": "BLOCKED_TOOLCHAIN",
  "tool": {
    "name": "syft",
    "required_version": "1.27.1",
    "observed_version": null
  },
  "attempted_argv": ["syft", "version"],
  "attempt_exit_code": null,
  "attempt_evidence": [],
  "rerun_argv": ["node", "scripts/acceptance/run-foundation-acceptance.mjs"],
  "occurred_at": "2026-07-26T00:00:00Z",
  "owner": "platform-engineering"
}
~~~

For every non-toolchain blocked reason, the same common fields replace `tool` with this closed
branch. `BLOCKED_BY_PROVIDER_ADAPTER` may use it only for unavailable real external Provider
authority/capability/evidence after the repository-owned integration boundary has passed:

~~~json
"external": {
  "capability": "authoritative-branch-protection",
  "authority": "accord-source-host",
  "required_evidence_type": "branch-protection-receipt-v1"
}
~~~

The timestamp is fixed fixture data; runtime uses its actual UTC instant. Diagnostics may name a
check, tool or external capability, and reason only.

Run the contract test again. Expected GREEN for valid fixture/merge behavior and every negative
mutation.

- [ ] **Step 3: Write RED authoritative-remote and detached-clean tests**

Use temporary bare remotes and worktrees without network. Cover:

- exact remote ref/SHA success;
- wrong SHA, ambiguous ref, missing object, local-only commit, moved ref, and tree mismatch;
- detached HEAD required;
- tracked modification, staged change, non-ignored untracked file, and uninitialized/wrong submodule
  rejection;
- ignored build output allowed only outside the detached source output contract;
- before/after status checks catch a verifier that writes a new source file;
- cleanup resolves the exact temporary worktree path and removes no other path.

The production resolver uses these exact argument arrays:

~~~javascript
run('git', ['ls-remote', '--exit-code', remoteUrl, remoteRef]);
run('git', ['fetch', '--no-tags', '--force', remoteUrl, remoteRef]);
run('git', ['cat-file', '-e', `${remoteSha}^{commit}`]);
run('git', ['rev-parse', `${remoteSha}^{tree}`]);
run('git', ['worktree', 'add', '--detach', validatedTemporaryPath, remoteSha]);
~~~

No command string or shell interpolation exists in implementation.

Run focused tests. Expected RED: remote resolver/worktree launcher are absent.

- [ ] **Step 4: Implement detached execution and immutable evidence location**

`resolve-remote-sha.mjs` parses `ls-remote` records strictly, compares full hashes case-sensitively,
fetches the named ref, verifies commit/tree, and hashes the remote proof. Network/auth failure emits
`BLOCKED_REMOTE_AUTHORITY` without accepting cached state.

`create-detached-worktree.mjs` uses `fs.mkdtemp` under the OS temp directory, validates resolved
paths before add/remove, and registers cleanup. It launches the detached copy's
`run-foundation-acceptance.mjs --inside-detached` with an absolute evidence directory outside that
copy.

Inside-detached mode refuses to run if a symbolic branch exists, HEAD/tree differ, or strict status
is nonempty. It records command argv/duration/exit/evidence digest, not raw output containing
sentinels.

Run detached tests again. Expected GREEN for exact remote authority and every dirty/attached
negative case.

- [ ] **Step 5: Write RED minimum Foundation demonstration tests**

`foundation-demo.test.mjs` and `FoundationProductLoopIT` prove the complete minimum evidence bundle:

1. start the Task 13 digest-locked dependency topology;
2. start API, worker, and Webhook Edge with their own database roles and Task 14 telemetry;
3. send one correctly signed GitLab 19.1 webhook twice and require two `202` responses but one edge
   inbox/outbox pair; send changed bytes under the same webhook ID and require `409`;
4. submit the Task 9 API command twice with one Idempotency-Key and require byte-identical status,
   body, headers, and ETag plus one aggregate/domain-event/outbox result;
5. let Task 12's test-owned `EventTransport` construct only Task 10
   `ReconciliationWorkflowRef(tenantId, intentId)` and start that workflow; it never reads the GitLab
   edge database or carries a lease/capability;
6. force one retry, prove two PostgreSQL authority reloads, kill/restart the worker once, and require
   one durable final receipt with zero duplicate side effects;
7. query final state through the public API or read-only observer, never by test database write;
8. verify zero sentinel matches in application/Temporal/collector logs, spans, metrics, labels, and
   evidence.

The integration test imports production Task 9-14 types, including
`FencedReconciliationObservation`, and supplies only the bounded transport plus a test-owned
low-level `ProviderObservationPort`. It never implements `ReconciliationObservationPort`. Tests
assert that absence or breakage of `ReadOnlyReconciliationActivity`, the production orchestrator,
`EventTransport`, that bounded low-level adapter, or the real mTLS integration produces CODE
`FAIL`/`ASSERTION_FAILED`; it is never
`BLOCKED_BY_PROVIDER_ADAPTER` and is never substituted with `TestWorkflowEnvironment` or an
in-memory queue. Only after those boundaries exist and pass may an unavailable real external
Provider authority/capability/evidence produce external `BLOCKED_BY_PROVIDER_ADAPTER` evidence.

Run:

~~~powershell
node --test tests/integration/foundation-demo.test.mjs
./gradlew.bat :tests:integration:test --tests '*FoundationProductLoopIT' --no-daemon --dependency-verification=strict
~~~

Expected RED: demo orchestration, cross-process fixture, and evidence assertions are absent, and the
CODE check reports `FAIL`/`ASSERTION_FAILED` rather than external BLOCKED.

- [ ] **Step 6: Implement and run the real local Foundation demonstration**

`run-foundation-demo.mjs` uses Node argument arrays, bounded readiness polling, public HTTP entry
points, and read-only catalog/evidence queries. It seeds sentinels only through supported request
inputs, never prints them, and stores match counts/rule IDs rather than matched text.

`FoundationProductLoopIT` reuses Task 10's real mTLS configuration and Task 12 fences/receipts.
The transport starts a `ReconciliationWorkflowRef` workflow after the outbox lease transaction
commits. Production `FencedReconciliationObservation` commits tx1 tenant context plus safe snapshot
and reconciliation claim, retains only snapshot scope and an acquired lease in process memory,
observes through the bounded low-level port with no JDBC transaction, and commits tx2
complete-plus-event/outbox or mark-unknown. Completion revalidates event scope in PostgreSQL. It
returns only a closed outcome; heartbeat/history/result/log contain no capability or Provider fact.
Worker crash/restart preserves Temporal history and database receipt authority.

`verify-sensitive-logs.mjs` scans API, worker, edge, Temporal, and collector outputs plus exported
telemetry. On failure it reports only service and rule ID. It rejects authorization/cookie/token,
raw webhook/body, source/diff/archive, prompt, secret, URL/query, exception, and all seeded
sentinels.

Run after Task 13 local startup. Expected GREEN: both ingress legs, durable API-to-worker workflow,
mTLS recovery, PostgreSQL reload, and typed telemetry evidence pass without claiming a production
GitLab-to-control connector.

- [ ] **Step 7: Define and execute the code verdict**

Inside the detached worktree, `run-foundation-acceptance.mjs` executes these named checks in order:

1. architecture and workspace boundaries;
2. full toolchain preflight;
3. dependency-lock and strict offline Java/browser/Python/Buf verification;
4. Task 13 single image lock and local Temporal mTLS matrix;
5. Task 14 typed telemetry and leak tests;
6. Task 15 Helm/Kubeconform/Conftest/OpenTofu/GitOps static contracts;
7. Task 16 Dockerfile/SBOM/scan/DSSE/signature/ORAS chain verification;
8. the minimum Foundation demonstration;
9. final tracked diff, index diff, strict untracked status, and submodule status.

An absent required tool creates blocked check evidence and stops dependent checks as BLOCKED, not
PASS. An assertion failure creates FAIL. Independent checks continue only when they can add safe
evidence.

On the known machine, expected `code_verdict.status` is `BLOCKED` with explicit
`BLOCKED_TOOLCHAIN` entries for the ten absent tools. No `foundation-acceptance: PASS` text is
printed.

- [ ] **Step 8: Define and execute the environment verdict**

`collect-environment-evidence.mjs` consumes immutable receipts, never booleans, for:

- authoritative remote ref and branch-protection/no-force-push/required-review policy;
- authenticated dependency/container mirrors and cache provenance;
- OIDC issuer/subject/audience and protected release environment;
- registry subjects, Cosign bundles, DSSE/ORAS referrers, revocation/retention policy;
- Kubernetes workload identities, external-secret store, network-policy enforcement, live TLS
  handshakes, and exact deployed image digests;
- Argo AppProject/Application, immutable targetRevision, sync/health/history;
- three isolated production PostgreSQL endpoints, backup/PITR/RPO/RTO and restore test;
- production object capability report/receipt;
- Windows Authenticode and macOS notarization for published platform packages.

Each receipt is schema-validated, time-bounded, digest-bound to the same remote SHA/release manifest,
and independently verified. Unavailable evidence yields its named BLOCKED reason.

Expected on a local offline host: `environment_verdict.status=BLOCKED`. Local Compose, rendered
Helm, or a cached remote-tracking ref cannot change it to PASS.

- [ ] **Step 9: Record architecture and enforce two-verdict completion**

`verify-platform-foundation.mjs` requires `docs/architecture/platform-foundation.md` to state:

- Java/Spring Modulith control API/worker and independent GitLab Webhook Edge;
- PostgreSQL authority, forced RLS, exact login/session roles, fences, receipts, and no second
  coordination database;
- real Temporal server mTLS, identifier-only history, and PostgreSQL reload per activity;
- typed whole-record-drop telemetry and final exporter guard;
- component-keyed workload identities, external secrets, route-mode-specific L3/L4 policy, and
  separate TLS proof;
- the sole image lock and provider-neutral release core;
- GitHub as Accord source host only and GitLab as product Provider;
- exact-digest SBOM/scan/Cosign/ORAS/DSSE chain;
- detached remote-SHA acceptance and dual-verdict BLOCKED semantics.

`merge-verdicts.mjs` writes summary PASS only for two PASS verdicts with an identical binding tuple.
The process exit is 0 PASS, 1 FAIL, 2 BLOCKED.

- [ ] **Step 10: Run acceptance from an authoritative SHA and commit Task 17 files**

Run from the invoking checkout:

~~~powershell
node scripts/acceptance/run-foundation-acceptance.mjs --remote-url $env:ACCORD_REMOTE_URL --remote-ref $env:ACCORD_REMOTE_REF --remote-sha $env:ACCORD_REMOTE_SHA --output build/acceptance
node scripts/acceptance/merge-verdicts.mjs --code build/acceptance/$env:ACCORD_REMOTE_SHA/code-verdict.json --environment build/acceptance/$env:ACCORD_REMOTE_SHA/environment-verdict.json --output build/acceptance/$env:ACCORD_REMOTE_SHA/summary.json
~~~

On the known host, expected exit is 2 and both the missing-tool and external-evidence blockers are
present. On a fully provisioned authoritative runner, expected exit is 0 only after both verdicts
are PASS and the detached worktree remains clean.

After focused schema/unit tests pass, stage only Task 17 source files:

~~~powershell
git diff --check
$task17Paths = @(
  'contracts/acceptance/blocked-evidence.schema.json'
  'contracts/acceptance/foundation-verdict.schema.json'
  'contracts/acceptance/foundation-summary.schema.json'
  'contracts/golden-fixtures/acceptance/blocked-toolchain.json'
  'contracts/golden-fixtures/acceptance/code-pass.json'
  'contracts/golden-fixtures/acceptance/environment-blocked.json'
  'scripts/acceptance/resolve-remote-sha.mjs'
  'scripts/acceptance/create-detached-worktree.mjs'
  'scripts/acceptance/run-foundation-demo.mjs'
  'scripts/acceptance/run-foundation-acceptance.mjs'
  'scripts/acceptance/collect-environment-evidence.mjs'
  'scripts/acceptance/merge-verdicts.mjs'
  'tests/integration/foundation-acceptance.test.mjs'
  'tests/integration/foundation-demo.test.mjs'
  'tests/integration/foundation-acceptance.ps1'
  'tests/integration/verify-sensitive-logs.mjs'
  'tests/integration/src/test/java/com/inforvans/accord/integration/FoundationProductLoopIT.java'
  'tests/integration/build.gradle'
  'tests/integration/gradle.lockfile'
  'tests/architecture/verify-platform-foundation.mjs'
  'docs/architecture/platform-foundation.md'
  'gradle/verification-metadata.xml'
  '.gitignore'
)
git add -- $task17Paths
git diff --cached --name-only
git commit -m "test: accept detached foundation evidence"
~~~

Expected: no verdict/evidence/build output is staged; staging arrays include no Task 12-or-earlier
source; and no completion claim is made from a BLOCKED summary.

- [ ] **Step 11: Perform the final plan and repository boundary self-review**

Run read-only checks that assert:

- Task 13-17 contain no unfinished marker or vague implementation instruction;
- `infra/images/images.lock.json` is the sole image lock and every Compose/Dockerfile/tool image
  consumer is checked against it;
- Task 13 mTLS negative cases and independent Temporal database identities are exhaustive;
- Task 14 exposes no generic attribute map and guards automatic instrumentation at final export;
- Task 15 identity/secret/role/subject/audience tuples are component-keyed and network/TLS evidence
  is not conflated;
- only the GitHub CI adapter mentions GitHub environment fields, while product mocks remain GitLab;
- every missing tool/external capability produces BLOCKED evidence and no skip/PASS branch exists;
- every task commit uses an explicit PowerShell staging array and no broad `git add .`;
- detached status includes non-ignored untracked files and both verdicts are required for PASS.

Run `git diff --check` last. Expected: no whitespace error, no replacement marker, no change outside
this plan's intended implementation paths, and a mechanically complete Task 13-17 execution path.
## Plan Self-Review

- [x] Tasks 1-17 remain continuous and map workspace, contracts, module/process boundaries, PostgreSQL reliability, HTTP/Temporal boundaries, isolated Webhook Edge, local integration, telemetry, deployment, supply chain, CLI packaging, and acceptance.
- [x] All implementation paths, code shapes, Gradle files, commands, images, and runtime dependencies use Java 21/Groovy DSL, Python 3.12, React/TypeScript, and PostgreSQL coordination.
- [x] Language consolidation does not consolidate authority: edge/security services remain independent deployables and the control plane never receives source access.
- [x] Public mutations use `Idempotency-Key`, quoted `If-Match`, exact durable replay, generation/token/deadline fencing, CAS, and RFC 7807.
- [x] Static scans, task/step numbering, fence balance, unfinished markers, repeated references, and architecture/runtime terms have been checked against this document.
