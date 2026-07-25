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

**Files:**
- Create: `database/control-plane/migrations/V002__reliable_event_delivery.sql`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/DomainEvent.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxAcceptance.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableEventStore.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliableEventStoreTest.java`
- Modify: `apps/control-plane/modules/reliability/build.gradle`
- Modify: `database/control-plane/src/test/java/com/inforvans/accord/database/PlatformMigrationTest.java`

- [ ] **Step 1: Write the failing transaction and deduplication tests**

Create `ReliableEventStoreTest.java`:

```java
package com.inforvans.accord.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inforvans.accord.database.ControlPlaneTestRoles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class ReliableEventStoreTest extends PostgreSqlReliabilityTest {
    @Test
    void eventAndOutboxCommitAndRollBackTogether() {
        ReliableEventStore store = new ReliableEventStore();
        dsl.transaction(configuration -> store.append(configuration.dsl(), event(1)));
        assertEquals(1, count("domain_event"));
        assertEquals(1, count("outbox_event"));

        assertThrows(IllegalStateException.class, () ->
            dsl.transaction(configuration -> {
                store.append(configuration.dsl(), event(2));
                throw new IllegalStateException("force rollback");
            }));
        assertEquals(1, count("domain_event"));
        assertEquals(1, count("outbox_event"));
    }

    @Test
    void inboxNaturalKeyDeduplicatesDigestAndRejectsChangedDigest() {
        ReliableEventStore store = new ReliableEventStore();
        UUID tenant = UUID.fromString("10000000-0000-0000-0000-000000000001");
        InboxAcceptance first = dsl.transactionResult(configuration ->
            store.acceptInbox(
                configuration.dsl(), tenant, "git-provider", "delivery-17",
                digest('a'), "git.reconcile", "git.signal/1.0", "{}"));
        InboxAcceptance duplicate = dsl.transactionResult(configuration ->
            store.acceptInbox(
                configuration.dsl(), tenant, "git-provider", "delivery-17",
                digest('a'), "git.reconcile", "git.signal/1.0", "{}"));
        InboxAcceptance conflict = dsl.transactionResult(configuration ->
            store.acceptInbox(
                configuration.dsl(), tenant, "git-provider", "delivery-17",
                digest('b'), "git.reconcile", "git.signal/1.0", "{}"));

        assertInstanceOf(InboxAcceptance.Accepted.class, first);
        assertInstanceOf(InboxAcceptance.Duplicate.class, duplicate);
        assertInstanceOf(InboxAcceptance.DigestConflict.class, conflict);
        assertEquals(1, count("inbox_message"));
    }

    private int count(String tableName) {
        return dsl.fetchCount(DSL.table(DSL.name(tableName)));
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgreSqlReliabilityTest {
    protected final UUID fixtureTenantId =
        UUID.fromString("10000000-0000-0000-0000-000000000001");

    private PostgreSQLContainer<?> postgres;
    protected DSLContext dsl;

    @BeforeAll
    void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:17.5");
        postgres.start();
        ControlPlaneTestRoles.bootstrap(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        Path migrations = Path.of(
            System.getProperty("accord.repo-root"),
            "database", "control-plane", "migrations");
        Flyway.configure()
            .dataSource(
                postgres.getJdbcUrl(),
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD)
            .initSql("SET ROLE accord_migrator")
            .locations("filesystem:" + migrations.toAbsolutePath())
            .load()
            .migrate();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @BeforeEach
    void resetReliabilityTables() {
        dsl.execute("""
            TRUNCATE TABLE inbox_message,outbox_event,domain_event,external_call_intent
            CASCADE
            """);
    }

    @AfterAll
    void stopPostgres() {
        dsl.close();
        postgres.stop();
    }

    protected DomainEvent event(long sequence) {
        return new DomainEvent(
            UUID.nameUUIDFromBytes(
                ("event-" + sequence).getBytes(StandardCharsets.UTF_8)),
            fixtureTenantId,
            "project",
            "30000000-0000-0000-0000-000000000001",
            "foundation-test",
            UUID.fromString("40000000-0000-0000-0000-000000000001"),
            sequence,
            "foundation.tested",
            "1.0.0",
            UUID.fromString("50000000-0000-0000-0000-000000000001"),
            UUID.fromString("60000000-0000-0000-0000-000000000001"),
            "fixture",
            "{}",
            OffsetDateTime.parse("2026-07-24T09:59:00Z"),
            "foundation-test");
    }

    protected List<UUID> insertPendingOutbox(int count) {
        List<UUID> eventIds = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            DomainEvent event = event(index);
            dsl.transaction(configuration ->
                new ReliableEventStore().append(configuration.dsl(), event));
            dsl.execute(
                "UPDATE outbox_event SET available_at=? WHERE event_id=?",
                OffsetDateTime.parse("2026-07-24T09:59:00Z"),
                event.eventId());
            eventIds.add(event.eventId());
        }
        return List.copyOf(eventIds);
    }

    protected void insertPendingInbox(String sourceMessageId) {
        InboxAcceptance accepted = dsl.transactionResult(configuration ->
            new ReliableEventStore().acceptInbox(
                configuration.dsl(), fixtureTenantId, "foundation-test", sourceMessageId,
                digest('a'), "foundation.handle", "foundation.message/1.0", "{}"));
        if (!(accepted instanceof InboxAcceptance.Accepted)) {
            throw new IllegalStateException("fixture inbox insert was not accepted");
        }
        dsl.execute("""
            UPDATE inbox_message SET available_at=?
            WHERE tenant_id=? AND source=? AND source_message_id=?
            """,
            OffsetDateTime.parse("2026-07-24T09:59:00Z"),
            fixtureTenantId, "foundation-test", sourceMessageId);
    }

    protected void insertPendingInbox() {
        insertPendingInbox("message-1");
    }

    protected static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
```

- [ ] **Step 2: Run the test and verify the missing delivery tables**

Run: `./gradlew :apps:control-plane:modules:reliability:test --tests '*ReliableEventStoreTest'`

Expected: FAIL because the jOOQ/Testcontainers test dependencies and `ReliableEventStore` are unresolved and `domain_event` does not exist.

- [ ] **Step 3: Add append-only event and delivery tables**

Add the exact production and test dependencies to `apps/control-plane/modules/reliability/build.gradle`:

```groovy
dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':database:control-plane')
    implementation platform(libs.spring.modulith.bom)
    implementation libs.spring.modulith.starter.core
    implementation libs.spring.boot.jooq
    implementation libs.jackson.databind
    runtimeOnly libs.postgresql
    testImplementation platform(libs.junit.bom)
    testImplementation libs.junit.jupiter
    testImplementation libs.assertj.core
    testImplementation testFixtures(project(':database:control-plane'))
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.postgresql
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
}

tasks.withType(Test).configureEach {
    systemProperty 'accord.repo-root', rootProject.projectDir.absolutePath
}
```

Create `V002__reliable_event_delivery.sql`:

```sql
SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE domain_event (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL CHECK (scope_type IN ('tenant', 'project', 'repository')),
    scope_id varchar(255) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    sequence bigint NOT NULL CHECK (sequence >= 1),
    event_type varchar(128) NOT NULL,
    schema_version varchar(32) NOT NULL,
    causation_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    actor_id varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    UNIQUE (tenant_id, event_id),
    UNIQUE (tenant_id, aggregate_type, aggregate_id, sequence)
);

CREATE TABLE outbox_event (
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    destination varchar(128) NOT NULL,
    payload_schema varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DELIVERING', 'DELIVERED', 'DEAD')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    redrive_count integer NOT NULL DEFAULT 0 CHECK (redrive_count >= 0),
    available_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    lease_owner varchar(255),
    lease_until timestamptz,
    delivered_at timestamptz,
    dead_at timestamptz,
    last_error_code varchar(128),
    FOREIGN KEY (tenant_id, event_id) REFERENCES domain_event(tenant_id, event_id),
    CHECK ((state = 'DELIVERING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((state = 'DELIVERED') = (delivered_at IS NOT NULL)),
    CHECK ((state = 'DEAD') = (dead_at IS NOT NULL AND last_error_code IS NOT NULL))
);
CREATE INDEX outbox_pending_idx ON outbox_event (state, available_at) WHERE state IN ('PENDING', 'DELIVERING');

CREATE TABLE inbox_message (
    tenant_id uuid NOT NULL,
    source varchar(128) NOT NULL,
    source_message_id varchar(255) NOT NULL,
    request_digest char(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    handler_key varchar(128) NOT NULL,
    payload_schema varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    redrive_count integer NOT NULL DEFAULT 0 CHECK (redrive_count >= 0),
    available_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    lease_owner varchar(255),
    lease_until timestamptz,
    received_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    completed_at timestamptz,
    dead_at timestamptz,
    last_error_code varchar(128),
    PRIMARY KEY (tenant_id, source, source_message_id),
    CHECK ((state = 'PROCESSING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL)),
    CHECK ((state = 'DEAD') = (dead_at IS NOT NULL AND last_error_code IS NOT NULL))
);
CREATE INDEX inbox_pending_idx ON inbox_message (state, available_at)
    WHERE state IN ('PENDING', 'PROCESSING');

CREATE TABLE external_call_intent (
    intent_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL CHECK (scope_type IN ('tenant', 'project', 'repository')),
    scope_id varchar(255) NOT NULL,
    provider varchar(64) NOT NULL,
    operation varchar(128) NOT NULL,
    request_digest char(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    provider_request_id varchar(255),
    state varchar(24) NOT NULL CHECK (state IN ('RECORDED', 'SENT', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN')),
    result_digest char(71) CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);
CREATE INDEX external_intent_reconcile_idx ON external_call_intent (provider, state, updated_at)
    WHERE state IN ('SENT', 'RESULT_UNKNOWN');

SELECT accord_security.enforce_tenant_table('public.domain_event'::regclass);
SELECT accord_security.enforce_tenant_table('public.outbox_event'::regclass);
SELECT accord_security.enforce_tenant_table('public.inbox_message'::regclass);
SELECT accord_security.enforce_tenant_table('public.external_call_intent'::regclass);

REVOKE ALL ON domain_event, outbox_event, inbox_message, external_call_intent
  FROM PUBLIC, accord_api, accord_worker;
GRANT SELECT, INSERT ON domain_event, outbox_event TO accord_api;
GRANT SELECT, INSERT ON domain_event TO accord_worker;
GRANT SELECT, INSERT, UPDATE ON outbox_event, inbox_message, external_call_intent TO accord_worker;
GRANT SELECT, INSERT, UPDATE ON external_call_intent TO accord_api;
```

V002 owns all four enforcement calls. They execute after every constraint and index and before the explicit grants. No runtime role receives `DELETE`; `domain_event` is append-only for both roles, API cannot mutate inbox state, and only the worker can advance outbox/inbox delivery state. Extend `PlatformMigrationTest` to assert exact `ENABLE`, `FORCE`, `FOR ALL TO PUBLIC`, `USING`, `WITH CHECK`, and exact `information_schema.role_table_grants` rows for all six V001/V002 tables. The test must fail on an extra privilege as well as a missing privilege.

- [ ] **Step 4: Implement the transactional event store**

Create `DomainEvent.java`, `InboxAcceptance.java`, and `ReliableEventStore.java`; each public type is stored in its matching file:

```java
// DomainEvent.java
package com.inforvans.accord.reliability;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DomainEvent(
    UUID eventId,
    UUID tenantId,
    String scopeType,
    String scopeId,
    String aggregateType,
    UUID aggregateId,
    long sequence,
    String eventType,
    String schemaVersion,
    UUID causationId,
    UUID correlationId,
    String actorId,
    String payload,
    OffsetDateTime occurredAt,
    String destination
) {}

// InboxAcceptance.java
package com.inforvans.accord.reliability;

public sealed interface InboxAcceptance
        permits InboxAcceptance.Accepted,
                InboxAcceptance.Duplicate,
                InboxAcceptance.DigestConflict {
    record Accepted() implements InboxAcceptance {}
    record Duplicate(String state) implements InboxAcceptance {}
    record DigestConflict(String storedDigest) implements InboxAcceptance {}
}

// ReliableEventStore.java
package com.inforvans.accord.reliability;

import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

public final class ReliableEventStore {
    public void append(DSLContext tx, DomainEvent event) {
        tx.execute("""
            INSERT INTO domain_event (
              event_id,tenant_id,scope_type,scope_id,aggregate_type,aggregate_id,
              sequence,event_type,schema_version,causation_id,correlation_id,
              actor_id,payload,occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            event.eventId(), event.tenantId(), event.scopeType(), event.scopeId(),
            event.aggregateType(), event.aggregateId(), event.sequence(),
            event.eventType(), event.schemaVersion(), event.causationId(),
            event.correlationId(), event.actorId(), JSONB.valueOf(event.payload()),
            event.occurredAt());
        tx.execute("""
            INSERT INTO outbox_event (
              event_id,tenant_id,destination,payload_schema,payload
            ) VALUES (?, ?, ?, ?, ?)
            """,
            event.eventId(), event.tenantId(), event.destination(),
            event.schemaVersion(), JSONB.valueOf(event.payload()));
    }

    public InboxAcceptance acceptInbox(
            DSLContext tx,
            UUID tenantId,
            String source,
            String messageId,
            String digest,
            String handlerKey,
            String payloadSchema,
            String payload) {
        int inserted = tx.execute("""
            INSERT INTO inbox_message (
              tenant_id,source,source_message_id,request_digest,handler_key,
              payload_schema,payload,state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
            ON CONFLICT DO NOTHING
            """,
            tenantId, source, messageId, digest, handlerKey,
            payloadSchema, JSONB.valueOf(payload));
        if (inserted == 1) {
            return new InboxAcceptance.Accepted();
        }

        Record existing = tx.fetchOne("""
            SELECT request_digest,state
            FROM inbox_message
            WHERE tenant_id=? AND source=? AND source_message_id=?
            FOR UPDATE
            """, tenantId, source, messageId);
        if (existing == null) {
            throw new IllegalStateException("inbox row disappeared");
        }
        String storedDigest = existing.get("request_digest", String.class);
        if (storedDigest.equals(digest)) {
            return new InboxAcceptance.Duplicate(existing.get("state", String.class));
        }
        return new InboxAcceptance.DigestConflict(storedDigest);
    }
}
```

`ReliableEventStore` has no pool-level `DSLContext`; callers can invoke it only with the transaction context that also persists the aggregate mutation. Extend `PlatformMigrationTest` with assertions that `domain_event`, `outbox_event`, `inbox_message`, and `external_call_intent` exist, that `domain_event` has a unique constraint over tenant, aggregate type, aggregate ID, and sequence, and that the outbox foreign key contains both `tenant_id` and `event_id`. Insert a deliberately mismatched tenant/event pair and assert SQLSTATE `23503`.

- [ ] **Step 5: Run transaction, rollback, and inbox tests**

Run:

```bash
./gradlew :database:control-plane:test
./gradlew :apps:control-plane:modules:reliability:test --tests '*ReliableEventStoreTest'
```

Expected: PASS. The forced rollback leaves both event tables at one row; the same inbox natural key and digest returns `Duplicate`; a changed digest returns `DigestConflict` without changing the original row; and a cross-tenant outbox/event link is rejected by PostgreSQL.

- [ ] **Step 6: Commit reliable delivery primitives**

```bash
git add database/control-plane/migrations/V002__reliable_event_delivery.sql database/control-plane/src/test/java/com/inforvans/accord/database/PlatformMigrationTest.java apps/control-plane/modules/reliability/build.gradle apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/DomainEvent.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxAcceptance.java apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableEventStore.java apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliableEventStoreTest.java
git commit -m "feat: add transactional outbox and inbox"
```

### Task 9: Prove RFC 7807, Idempotency, And CAS Through The HTTP API

**Files:**
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationController.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/HttpIdempotencyFingerprint.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ProblemAdvice.java`
- Create: `apps/control-plane/api/src/test/java/com/inforvans/accord/controlplane/http/ContractValidationApiTest.java`
- Modify: `apps/control-plane/api/src/main/resources/application.yml`
- Modify: `apps/control-plane/worker/src/main/resources/application.yml`
- Modify: `apps/control-plane/api/build.gradle`

- [ ] **Step 1: Write the failing HTTP behavior test**

Create `ContractValidationApiTest.java`:

```java
package com.inforvans.accord.controlplane.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inforvans.accord.database.ControlPlaneTestRoles;
import com.inforvans.accord.reliability.JooqCommandGate;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContractValidationTestConfiguration.class)
class ContractValidationApiTest {
    private static final UUID TENANT_A =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B =
        UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final String PATH =
        "/v1/contract-validations/20000000-0000-0000-0000-000000000001";
    private static final String BODY = """
        {"schema_id":"https://schemas.accord.inforvans.com/events/domain-event/1-0-0",
         "document":{}}
        """;
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17.5");

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        ControlPlaneTestRoles.bootstrap(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Path migrations = Path.of(
            System.getProperty("accord.repo-root"),
            "database", "control-plane", "migrations");
        Flyway.configure()
            .dataSource(
                POSTGRES.getJdbcUrl(),
                ControlPlaneTestRoles.MIGRATOR_LOGIN,
                ControlPlaneTestRoles.MIGRATOR_PASSWORD)
            .initSql("SET ROLE accord_migrator")
            .locations("filesystem:" + migrations.toAbsolutePath())
            .load()
            .migrate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add(
            "spring.datasource.username",
            () -> ControlPlaneTestRoles.API_LOGIN);
        registry.add(
            "spring.datasource.password",
            () -> ControlPlaneTestRoles.API_PASSWORD);
        registry.add(
            "spring.datasource.hikari.connection-init-sql",
            () -> "SET ROLE accord_api");
    }

    @BeforeEach
    void clearCommandState() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(
                "TRUNCATE TABLE idempotency_result,aggregate_head CASCADE");
        }
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void identicalRetryReplaysAndChangedBodyReturnsProblemDetails()
            throws Exception {
        String first = mvc.perform(postRequest(
                BODY, "idem-000000000001", "\"0\"", PATH))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        mvc.perform(postRequest(BODY, "idem-000000000001", "\"0\"", PATH))
            .andExpect(status().isCreated())
            .andExpect(content().string(first));
        mvc.perform(postRequest(
                BODY.replace("{}", "{\"changed\":true}"),
                "idem-000000000001", "\"0\"", PATH))
            .andExpect(status().isConflict())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void staleExpectedVersionReturnsCurrentVersion() throws Exception {
        mvc.perform(postRequest(BODY, "idem-000000000002", "\"0\"", PATH))
            .andExpect(status().isCreated());
        mvc.perform(postRequest(BODY, "idem-000000000003", "\"0\"", PATH))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.actual_version").value(1));
    }

    @Test
    void sameKeyCannotReplayAcrossResourcesOrCasPreconditions() throws Exception {
        String key = "idem-fingerprint-000001";
        mvc.perform(postRequest(BODY, key, "\"0\"", PATH))
            .andExpect(status().isCreated());
        mvc.perform(postRequest(
                BODY, key, "\"0\"",
                "/v1/contract-validations/20000000-0000-0000-0000-000000000002"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(postRequest(BODY, key, "\"1\"", PATH))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void reorderedQueriesReplayButLiteralPlusDiffersFromSpace() throws Exception {
        String first = mvc.perform(postRequest(
                BODY, "idem-fingerprint-000002", "\"0\"",
                PATH + "?mode=strict&locale=en"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        mvc.perform(postRequest(
                BODY, "idem-fingerprint-000002", "\"0\"",
                PATH + "?locale=en&mode=strict"))
            .andExpect(status().isCreated())
            .andExpect(content().string(first));

        mvc.perform(postRequest(
                BODY, "idem-fingerprint-000003", "\"1\"", PATH + "?q=a+b"))
            .andExpect(status().isCreated());
        mvc.perform(postRequest(
                BODY, "idem-fingerprint-000003", "\"1\"", PATH + "?q=a%20b"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void queryNormalizationRejectsMalformedEscapesAndInvalidUtf8() {
        HttpIdempotencyFingerprint fingerprints =
            new HttpIdempotencyFingerprint(new ObjectMapper());
        assertThrows(
            IllegalArgumentException.class,
            () -> fingerprints.normalizeQuery("q=%"));
        assertThrows(
            IllegalArgumentException.class,
            () -> fingerprints.normalizeQuery("q=%C3%28"));
    }

    @Test
    void verifiedPrincipalWinsOverForgedTenantHeader() throws Exception {
        mvc.perform(postRequest(
                BODY, "idem-tenant-000001", "\"0\"", PATH)
                .header("X-Accord-Tenant", TENANT_B.toString()))
            .andExpect(status().isCreated());

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(
                 "SELECT tenant_id FROM aggregate_head")) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertEquals(TENANT_A, rows.getObject(1, UUID.class));
            }
        }
    }

    @Test
    void rawBearerValueWithoutVerifiedPrincipalIsUnauthorized() throws Exception {
        mvc.perform(post(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-user-7")
                .header("Idempotency-Key", "idem-auth-000001")
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void productionHealthHandlersMatchOpenApiPaths() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/health/ready"))
            .andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder postRequest(
            String body,
            String key,
            String version,
            String uri) {
        return post(uri)
            .with(authentication(
                UsernamePasswordAuthenticationToken.authenticated(
                    new FoundationTestPrincipal(TENANT_A, "test-user-7"),
                    "test-only-no-credential",
                    List.of())))
            .header("Idempotency-Key", key)
            .header("If-Match", version)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(body);
    }
}

record FoundationTestPrincipal(
    UUID tenantId,
    String actorId
) implements FoundationVerifiedPrincipal {}

@TestConfiguration(proxyBeanMethods = false)
class ContractValidationTestConfiguration {
    @Bean
    JooqCommandGate commandGate(ObjectMapper mapper) {
        return new JooqCommandGate(Clock.systemUTC(), mapper);
    }
}
```

Add two negative cases to this test: a request with a valid tenant-A `FoundationTestPrincipal` plus `X-Accord-Tenant: <tenant-B>` still writes only under tenant A, and a request carrying only `Authorization: Bearer test-user-7` receives 401. The test helper above is the only Foundation authentication converter; it constructs a server-side `Authentication` object and never parses request headers.

- [ ] **Step 2: Run the API test and verify the route is missing**

Run: `./gradlew :apps:control-plane:api:test --tests '*ContractValidationApiTest'`

Expected: FAIL because both requests return HTTP 404.

- [ ] **Step 3: Implement a committed claim followed by one fenced business transaction**

Create `HttpIdempotencyFingerprint.java`:

```java
package com.inforvans.accord.controlplane.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inforvans.accord.platformkernel.CanonicalJson;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class HttpIdempotencyFingerprint {
    private static final Comparator<QueryPair> QUERY_ORDER =
        Comparator.comparing(QueryPair::name).thenComparing(QueryPair::value);

    private final ObjectMapper mapper;

    HttpIdempotencyFingerprint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String create(
            String method,
            String path,
            String rawQuery,
            String routeKey,
            UUID resourceId,
            String canonicalBodyDigest,
            long expectedVersion,
            String requestMediaType,
            String responseMediaType,
            String apiContractVersion,
            UUID tenantId,
            String actorId) {
        String normalizedPath = URI.create(path).normalize().getRawPath();
        if (normalizedPath == null || !normalizedPath.startsWith("/")) {
            throw new IllegalArgumentException("idempotency path must be absolute");
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("actor_id", actorId);
        envelope.put("api_contract_version", apiContractVersion);
        envelope.put("body_digest", canonicalBodyDigest);
        envelope.put("expected_version", Long.toString(expectedVersion));
        envelope.put("http_method", method.toUpperCase(Locale.ROOT));
        envelope.put("normalized_path", normalizedPath);
        envelope.put("normalized_query", normalizeQuery(rawQuery));
        envelope.put("request_media_type", requestMediaType.toLowerCase(Locale.ROOT));
        envelope.put("resource_id", resourceId.toString().toLowerCase(Locale.ROOT));
        envelope.put("response_media_type", responseMediaType.toLowerCase(Locale.ROOT));
        envelope.put("route_key", routeKey);
        envelope.put("tenant_id", tenantId.toString().toLowerCase(Locale.ROOT));
        try {
            return CanonicalJson.sha256(mapper.writeValueAsBytes(envelope));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot encode idempotency envelope", error);
        }
    }

    String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<QueryPair> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            pairs.add(new QueryPair(percentDecode(name), percentDecode(value)));
        }
        pairs.sort(QUERY_ORDER);
        return pairs.stream()
            .map(pair -> encode(pair.name()) + "=" + encode(pair.value()))
            .reduce((left, right) -> left + "&" + right)
            .orElse("");
    }

    private String percentDecode(String value) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            if (value.charAt(index) == '%') {
                if (index + 2 >= value.length()) {
                    throw new IllegalArgumentException("truncated percent escape in query");
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("invalid percent escape in query");
                }
                decoded.write((high << 4) | low);
                index += 3;
            } else {
                int codePoint = value.codePointAt(index);
                decoded.writeBytes(
                    new String(Character.toChars(codePoint))
                        .getBytes(StandardCharsets.UTF_8));
                index += Character.charCount(codePoint);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded.toByteArray()))
                .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("query is not valid UTF-8", error);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%7E", "~");
    }

    private record QueryPair(String name, String value) {}
}
```

The envelope itself is RFC 8785-canonicalized by `CanonicalJson.sha256`; it is not a delimiter-based
concatenation. Query names and values use strict percent-decoding as UTF-8 (literal `+` remains plus),
are sorted as decoded `(name, value)` pairs with duplicates preserved, and are re-encoded with RFC
3986 space and unreserved-character spelling.
Thus reordered equivalent pairs replay, while a changed method, path, resource, query value, body,
CAS precondition, representation, API version, tenant, or actor conflicts.

Create `ContractValidationController.java`:

```java
package com.inforvans.accord.controlplane.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inforvans.accord.platformkernel.CanonicalJson;
import com.inforvans.accord.reliability.Claim;
import com.inforvans.accord.reliability.ClaimLease;
import com.inforvans.accord.reliability.CommandKey;
import com.inforvans.accord.reliability.ExpectedVersion;
import com.inforvans.accord.reliability.JooqCommandGate;
import com.inforvans.accord.reliability.StoredHttpResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

record ValidationRequest(
    @JsonProperty("schema_id") String schemaId,
    JsonNode document
) {}

record ValidationResponse(
    @JsonProperty("validation_id") UUID validationId,
    boolean valid,
    @JsonProperty("document_digest") String documentDigest,
    long version
) {}

interface FoundationVerifiedPrincipal {
    UUID tenantId();
    String actorId();
}

@Component
final class FoundationTenantTransactions {
    private final DSLContext dsl;

    FoundationTenantTransactions(DSLContext dsl) {
        this.dsl = dsl;
    }

    <T> T write(UUID tenantId, Function<DSLContext, T> block) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            tx.execute(
                "SELECT set_config('app.tenant_id', ?, true)", tenantId.toString());
            UUID effectiveTenant = tx.fetchOne(
                "SELECT accord_security.current_tenant_id()").get(0, UUID.class);
            if (!tenantId.equals(effectiveTenant)) {
                throw new IllegalStateException("tenant transaction context was not installed");
            }
            return block.apply(tx);
        });
    }
}

@Configuration(proxyBeanMethods = false)
class FoundationMutationSecurity {
    @Bean
    SecurityFilterChain foundationSecurity(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(rules -> rules
                .requestMatchers("/v1/contract-validations/**")
                .access((authentication, context) -> new AuthorizationDecision(
                    authentication.get().getPrincipal()
                        instanceof FoundationVerifiedPrincipal))
                .anyRequest().permitAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .build();
    }
}

@RestController
public class ContractValidationController {
    private static final String ROUTE_KEY = "contract-validations.create";

    private final JooqCommandGate gate;
    private final FoundationTenantTransactions transactions;
    private final ObjectMapper mapper;
    private final String commandOwner;
    private final HttpIdempotencyFingerprint fingerprints;

    public ContractValidationController(
            JooqCommandGate gate,
            FoundationTenantTransactions transactions,
            ObjectMapper mapper,
            @Value("$" + "{accord.process.instance-id}") String commandOwner) {
        this.gate = gate;
        this.transactions = transactions;
        this.mapper = mapper;
        this.commandOwner = commandOwner;
        this.fingerprints = new HttpIdempotencyFingerprint(mapper);
    }

    @PostMapping("/v1/contract-validations/{validationId}")
    public ResponseEntity<String> validate(
            @AuthenticationPrincipal FoundationVerifiedPrincipal principal,
            @PathVariable UUID validationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ValidationRequest request,
            HttpServletRequest servletRequest) throws JsonProcessingException {
        ExpectedVersion expected = parseExpectedVersion(ifMatch);
        String requestMediaType = normalizedMediaType(servletRequest.getContentType());
        String bodyDigest = CanonicalJson.sha256(mapper.writeValueAsBytes(request));
        String fingerprint = fingerprints.create(
            servletRequest.getMethod(),
            "/v1/contract-validations/" + validationId,
            servletRequest.getQueryString(),
            ROUTE_KEY,
            validationId,
            bodyDigest,
            expected.value(),
            requestMediaType,
            MediaType.APPLICATION_JSON_VALUE,
            "0.1.0",
            principal.tenantId(),
            principal.actorId());

        CommandKey key = new CommandKey(
            principal.tenantId(), principal.actorId(), ROUTE_KEY, idempotencyKey);
        Claim claim = transactions.write(
            principal.tenantId(),
            tx -> gate.claim(
                tx, key, fingerprint, commandOwner, Duration.ofMinutes(2)));

        if (claim instanceof Claim.Replay replay) {
            HttpHeaders headers = new HttpHeaders();
            replay.result().headers().forEach(headers::set);
            return ResponseEntity
                .status(replay.result().status())
                .headers(headers)
                .body(replay.result().body());
        }
        if (claim instanceof Claim.RequestConflict conflict) {
            throw new IdempotencyKeyReused(conflict.originalFingerprint());
        }
        if (claim instanceof Claim.InProgress inProgress) {
            throw new CommandInProgress(inProgress.leaseUntil());
        }
        ClaimLease lease = ((Claim.Acquired) claim).lease();

        return transactions.write(principal.tenantId(), tx -> {
            long version = gate.advance(
                tx,
                principal.tenantId(),
                "contract-validation",
                validationId,
                expected);
            String documentDigest = CanonicalJson.sha256(
                serialize(request.document()));
            ValidationResponse response = new ValidationResponse(
                validationId, true, documentDigest, version);
            String body = serializeToString(response);
            StoredHttpResult stored = new StoredHttpResult(
                201,
                java.util.Map.of(
                    "ETag", "\"" + version + "\"",
                    "Content-Type", MediaType.APPLICATION_JSON_VALUE),
                body);
            gate.complete(
                tx,
                key,
                lease,
                stored,
                "contract-validation",
                validationId,
                version,
                Duration.ofHours(24));
            return ResponseEntity.status(201)
                .eTag(Long.toString(version))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        });
    }

    private static ExpectedVersion parseExpectedVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.length() < 3
                || ifMatch.charAt(0) != '"'
                || ifMatch.charAt(ifMatch.length() - 1) != '"') {
            throw new IllegalArgumentException("If-Match must be one quoted integer ETag");
        }
        try {
            return new ExpectedVersion(
                Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                "If-Match must be one quoted integer ETag", error);
        }
    }

    private static String normalizedMediaType(String value) {
        MediaType mediaType = MediaType.parseMediaType(value);
        return mediaType.getType() + "/" + mediaType.getSubtype();
    }

    private byte[] serialize(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize canonical response input", error);
        }
    }

    private String serializeToString(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize response", error);
        }
    }
}

final class IdempotencyKeyReused extends RuntimeException {
    private final String originalFingerprint;

    IdempotencyKeyReused(String originalFingerprint) {
        this.originalFingerprint = originalFingerprint;
    }

    String originalFingerprint() {
        return originalFingerprint;
    }
}

final class CommandInProgress extends RuntimeException {
    private final OffsetDateTime retryAfter;

    CommandInProgress(OffsetDateTime retryAfter) {
        this.retryAfter = retryAfter;
    }

    OffsetDateTime retryAfter() {
        return retryAfter;
    }
}
```

- [ ] **Step 4: Map every foundation conflict to RFC 7807**

Create `ProblemAdvice.java`:

```java
package com.inforvans.accord.controlplane.http;

import com.inforvans.accord.reliability.VersionConflict;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProblemAdvice {
    @ExceptionHandler(IdempotencyKeyReused.class)
    ProblemDetail idempotency(
            IdempotencyKeyReused error,
            HttpServletRequest request) {
        ProblemDetail detail = problem(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency key was used with a different request",
            request);
        detail.setProperty(
            "original_request_fingerprint", error.originalFingerprint());
        return detail;
    }

    @ExceptionHandler(CommandInProgress.class)
    ProblemDetail inProgress(
            CommandInProgress error,
            HttpServletRequest request) {
        ProblemDetail detail = problem(
            HttpStatus.CONFLICT,
            "COMMAND_IN_PROGRESS",
            "The original command is still running",
            request);
        detail.setProperty("retry_after", error.retryAfter().toString());
        return detail;
    }

    @ExceptionHandler(VersionConflict.class)
    ProblemDetail version(
            VersionConflict error,
            HttpServletRequest request) {
        ProblemDetail detail = problem(
            HttpStatus.CONFLICT,
            "VERSION_CONFLICT",
            error.getMessage(),
            request);
        detail.setProperty("expected_version", error.expected());
        detail.setProperty("actual_version", error.actual());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(
            IllegalArgumentException error,
            HttpServletRequest request) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            error.getMessage(),
            request);
    }

    private ProblemDetail problem(
            HttpStatus status,
            String code,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(
            URI.create("https://errors.accord.inforvans.com/" + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        String correlationId = request.getHeader("X-Correlation-ID");
        problem.setProperty(
            "correlation_id",
            correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId);
        return problem;
    }
}
```

Replace API `application.yml` while preserving the module-detection invariant established in Task 5:

```yaml
spring:
  application.name: accord-control-api
  modulith.detection-strategy: explicitly-annotated
  datasource:
    url: ${ACCORD_DB_URL:jdbc:postgresql://localhost:5432/accord}
    username: ${ACCORD_API_DB_USER:accord_api_login}
    password: ${ACCORD_API_DB_PASSWORD:local-api-only}
    hikari.connection-init-sql: SET ROLE ${ACCORD_DB_SESSION_ROLE:accord_api}
  flyway.enabled: false
server:
  port: 8080
  error.include-stacktrace: never
management:
  endpoints.web.exposure.include: health,prometheus
  endpoint.health.probes.enabled: true
accord:
  process-role: control-api
  process.instance-id: ${ACCORD_PROCESS_INSTANCE_ID:${random.uuid}}
```

Replace worker `application.yml` while preserving the module-detection invariant established in Task 5:

```yaml
spring:
  application.name: accord-control-worker
  modulith.detection-strategy: explicitly-annotated
  main.web-application-type: none
  datasource:
    url: ${ACCORD_DB_URL:jdbc:postgresql://localhost:5432/accord}
    username: ${ACCORD_WORKER_DB_USER:accord_worker_login}
    password: ${ACCORD_WORKER_DB_PASSWORD:local-worker-only}
    hikari.connection-init-sql: SET ROLE ${ACCORD_DB_SESSION_ROLE:accord_worker}
  flyway.enabled: false
management:
  endpoints.web.exposure.include: health,prometheus
  endpoint.health.probes.enabled: true
accord:
  process-role: control-worker
  process.instance-id: ${ACCORD_PROCESS_INSTANCE_ID:${random.uuid}}
```

Add these exact dependencies to `apps/control-plane/api/build.gradle`:

```groovy
implementation libs.spring.boot.jooq
implementation 'org.springframework.boot:spring-boot-starter-security'
runtimeOnly libs.postgresql
testImplementation 'org.springframework.security:spring-security-test'
testImplementation testFixtures(project(':database:control-plane'))
testImplementation libs.flyway.core
testImplementation libs.flyway.postgresql
testImplementation libs.testcontainers.junit
testImplementation libs.testcontainers.postgresql

tasks.withType(Test).configureEach {
    systemProperty 'accord.repo-root', rootProject.projectDir.absolutePath
}
```

The `DynamicPropertySource` starts PostgreSQL, bootstraps the exact roles, and migrates as `accord_migrator_login` with `SET ROLE accord_migrator` before Spring creates its pool. The pool then connects as `accord_api_login`; Hikari initializes every physical connection with `SET ROLE accord_api`, and the production defaults follow the same session-user/current-user split. The API build consumes the database test fixtures explicitly. `FoundationVerifiedPrincipal` is a narrow temporary port, not an early copy of the Identity domain model: production has no request-header converter for it, the Foundation route fails closed unless a trusted server-side `Authentication` supplies it, and Identity Task 5 replaces the port and security chain with `VerifiedRequestIdentity`. Neither `X-Accord-Tenant` nor a raw bearer value is ever parsed into tenant or actor. Do not substitute H2 because JSONB, forced RLS, grants, and row-lock behavior are part of the contract.

The claim transaction commits before the controller starts the aggregate transaction. The acquired
lease handle is carried into the second transaction, where CAS, response construction, every
domain/audit/outbox write for the command, and fenced completion commit or roll back together. The API never
discards the generation/token fence and never performs business writes in the claim transaction.

- [ ] **Step 5: Run HTTP and OpenAPI policy tests**

Run:

```bash
./gradlew :apps:control-plane:api:test --tests '*ContractValidationApiTest'
pnpm contracts:test
```

Expected: PASS. The replay body and status are identical; changed input returns `application/problem+json` with `IDEMPOTENCY_KEY_REUSED`; the same key/body cannot replay across resource IDs or a changed `If-Match`; reordered equivalent query pairs do replay while literal `+` remains distinct from percent-encoded space; malformed percent escapes and invalid UTF-8 are rejected instead of being canonicalized through replacement characters; a distinct key with stale `If-Match` returns `actual_version: 1`; a forged tenant header cannot change scope; a raw bearer string without the test-only verified principal receives 401; `/actuator/health/liveness` and `/actuator/health/readiness` return the declared `UP` shape; and legacy `/health/ready` is 404.

- [ ] **Step 6: Commit the API safety slice**

```bash
git add apps/control-plane/api/build.gradle apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationController.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/HttpIdempotencyFingerprint.java apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ProblemAdvice.java apps/control-plane/api/src/test/java/com/inforvans/accord/controlplane/http/ContractValidationApiTest.java apps/control-plane/api/src/main/resources/application.yml apps/control-plane/worker/src/main/resources/application.yml
git commit -m "feat: expose idempotent CAS command endpoint"
```

### Task 10: Constrain Temporal To Durable Orchestration

**Files:**
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflow.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowImpl.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationRef.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationOutcome.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationActivities.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/temporal/ReconciliationWorkflowTest.java`
- Modify: `apps/control-plane/worker/build.gradle`

- [ ] **Step 1: Write the failing workflow retry and payload-boundary test**

Create `ReconciliationWorkflowTest.java`:

```java
package com.inforvans.accord.controlplane.worker.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReconciliationWorkflowTest {
    @Test
    void workflowCarriesIdentifiersWhileActivityReloadsAuthoritativeState() {
        try (TestWorkflowEnvironment environment =
                TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("reconciliation");
            worker.registerWorkflowImplementationTypes(
                ReconciliationWorkflowImpl.class);
            RecordingActivities activities = new RecordingActivities();
            worker.registerActivitiesImplementations(activities);
            environment.start();

            ReconciliationWorkflow client =
                environment.getWorkflowClient().newWorkflowStub(
                    ReconciliationWorkflow.class,
                    WorkflowOptions.newBuilder()
                        .setTaskQueue("reconciliation")
                        .build());
            ReconciliationRef ref = new ReconciliationRef(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("40000000-0000-0000-0000-000000000001"));

            assertEquals(ReconciliationOutcome.CONVERGED, client.run(ref));
            assertEquals(2, activities.attempts());
            assertEquals(ref, activities.loadedRef());

            Set<String> fields = Arrays.stream(
                    ReconciliationRef.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());
            assertEquals(Set.of("tenantId", "reconciliationId"), fields);
            assertFalse(fields.stream().anyMatch(name -> {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                return lower.contains("authorization")
                    || lower.contains("businessstate");
            }));
        }
    }
}

final class RecordingActivities implements ReconciliationActivities {
    private int attempts;
    private ReconciliationRef loadedRef;

    @Override
    public ReconciliationOutcome reconcileFromDatabase(ReconciliationRef ref) {
        attempts++;
        loadedRef = ref;
        if (attempts == 1) {
            throw ApplicationFailure.newFailure(
                "provider temporarily unavailable",
                "TRANSIENT_PROVIDER");
        }
        return ReconciliationOutcome.CONVERGED;
    }

    int attempts() {
        return attempts;
    }

    ReconciliationRef loadedRef() {
        return loadedRef;
    }
}
```

- [ ] **Step 2: Run the test and verify workflow types are absent**

Run: `./gradlew :apps:control-plane:worker:test --tests '*ReconciliationWorkflowTest'`

Expected: FAIL because `ReconciliationWorkflow` and its identifier-only input are unresolved.

- [ ] **Step 3: Define an identifier-only workflow and retry policy**

Create `ReconciliationRef.java`, `ReconciliationOutcome.java`, `ReconciliationWorkflow.java`, and `ReconciliationWorkflowImpl.java`; each public type is stored in its matching file:

```java
// ReconciliationRef.java
package com.inforvans.accord.controlplane.worker.temporal;

import java.util.UUID;

public record ReconciliationRef(UUID tenantId, UUID reconciliationId) {}

// ReconciliationOutcome.java
package com.inforvans.accord.controlplane.worker.temporal;

public enum ReconciliationOutcome {
    CONVERGED,
    DIVERGED
}

// ReconciliationWorkflow.java
package com.inforvans.accord.controlplane.worker.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ReconciliationWorkflow {
    @WorkflowMethod
    ReconciliationOutcome run(ReconciliationRef ref);
}

// ReconciliationWorkflowImpl.java
package com.inforvans.accord.controlplane.worker.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public final class ReconciliationWorkflowImpl implements ReconciliationWorkflow {
    private final ReconciliationActivities activities =
        Workflow.newActivityStub(
            ReconciliationActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setBackoffCoefficient(2.0)
                        .setMaximumAttempts(5)
                        .build())
                .build());

    @Override
    public ReconciliationOutcome run(ReconciliationRef ref) {
        return activities.reconcileFromDatabase(ref);
    }
}
```

Create `ReconciliationActivities.java`:

```java
package com.inforvans.accord.controlplane.worker.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ReconciliationActivities {
    @ActivityMethod
    ReconciliationOutcome reconcileFromDatabase(ReconciliationRef ref);
}
```

The test implementation records only `ReconciliationRef`, fails its first call with the retryable `TRANSIENT_PROVIDER` application failure, and returns `CONVERGED` on the second. The production activity added by the Git integration plan must load intent, authorization, and current state from PostgreSQL on every attempt and persist its result through a CAS command.

Add to the worker build:

```groovy
implementation libs.temporal.sdk
testImplementation libs.temporal.testing
testImplementation platform(libs.junit.bom)
testImplementation libs.junit.jupiter
testImplementation libs.assertj.core
```

- [ ] **Step 4: Run the deterministic retry test**

Run: `./gradlew :apps:control-plane:worker:test --tests '*ReconciliationWorkflowTest'`

Expected: PASS; virtual time retries once, the activity runs twice, and workflow input has exactly `tenantId` and `reconciliationId`.

- [ ] **Step 5: Commit the orchestration boundary**

```bash
git add apps/control-plane/worker
git commit -m "feat: add identifier-only Temporal orchestration"
```

### Task 11: Build The Isolated Java Webhook Edge

**Files:**
- Create: `contracts/events/provider-webhook-signal.schema.json`
- Create: `contracts/golden-fixtures/webhooks/github-push.signal.json`
- Create: `database/webhook-edge/bootstrap/00-pre-flyway-roles.sql`
- Create: `database/webhook-edge/migrations/V001__webhook_delivery.sql`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/WebhookEdgeApplication.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/Binding.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/ProviderWebhookSignal.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/RecordOutcome.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/BindingResolver.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/SignalStore.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/FileBindingResolver.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/PostgresSignalStore.java`
- Create: `apps/webhook-edge/src/main/java/com/inforvans/accord/webhook/WebhookController.java`
- Create: `apps/webhook-edge/src/main/resources/application.yml`
- Create: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhook/WebhookDatabaseFixture.java`
- Create: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhook/WebhookControllerTest.java`
- Create: `apps/webhook-edge/src/test/java/com/inforvans/accord/webhook/PostgresSignalStoreTest.java`
- Modify: `apps/webhook-edge/build.gradle`
- Modify: `settings.gradle`

- [ ] **Step 1: Write failing verify-first, size-limit, and deduplication tests**

`WebhookControllerTest` must use a fixed `Clock`, a fixed server-side binding, and a recording store. Add these tests before production code:

1. Two correctly signed deliveries with the same tenant, immutable repository ID, delivery ID, and digest return 202 while the store records one logical signal.
2. A wrong signature over syntactically invalid JSON returns 401, not 400, and the store receives no call. This proves verification precedes JSON parsing.
3. A valid signature whose body repository ID differs from the binding returns 403 and writes nothing.
4. A request larger than 2 MiB returns 413 without parsing or persistence.
5. Missing provider identity headers return 400; a reused delivery ID with a changed raw-body digest returns 409.
6. The normalized signal contains only the allowlisted schema fields and never the raw body, source archive, diff, authorization header, or secret.

The request helper computes `HmacSHA256` over the exact request bytes and sends `X-Hub-Signature-256: sha256=<lowercase hex>`. Run:

~~~bash
./gradlew :apps:webhook-edge:test --tests '*WebhookControllerTest'
~~~

Expected: FAIL because the Java binding, controller, signal, and store contracts do not exist.

- [ ] **Step 2: Define the closed normalized signal contract**

Create `provider-webhook-signal.schema.json`:

~~~json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schemas.accord.inforvans.com/events/provider-webhook-signal/1-0-0",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "schema_version", "tenant_id", "scope_type", "scope_id", "provider",
    "immutable_repository_id", "delivery_id", "event_type", "body_digest",
    "observed_at"
  ],
  "properties": {
    "schema_version": { "const": "1.0.0" },
    "tenant_id": { "type": "string", "format": "uuid" },
    "scope_type": { "const": "repository" },
    "scope_id": { "type": "string", "minLength": 1 },
    "provider": { "const": "github" },
    "immutable_repository_id": { "type": "string", "minLength": 1 },
    "delivery_id": { "type": "string", "minLength": 1, "maxLength": 255 },
    "event_type": { "type": "string", "minLength": 1, "maxLength": 128 },
    "ref": { "type": ["string", "null"] },
    "head_sha": { "type": ["string", "null"], "pattern": "^[0-9a-f]{40,64}$" },
    "body_digest": { "type": "string", "pattern": "^sha256:[0-9a-f]{64}$" },
    "observed_at": { "type": "string", "format": "date-time" }
  }
}
~~~

Create the golden fixture with repository ID `77831`, a fixed UTC timestamp, and a valid digest. Validate it with AJV. The fixture contains the normalized signal only.

- [ ] **Step 3: Implement verify-first normalization in the independent Java process**

Create one public type per matching file:

~~~java
// Binding.java
package com.inforvans.accord.webhook;

import java.util.UUID;

public record Binding(
    UUID tenantId,
    String immutableRepositoryId,
    byte[] secret
) {
    public Binding {
        secret = secret.clone();
        if (secret.length < 32) {
            throw new IllegalArgumentException("webhook secret must be at least 256 bits");
        }
    }

    @Override
    public byte[] secret() {
        return secret.clone();
    }
}

// ProviderWebhookSignal.java
package com.inforvans.accord.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProviderWebhookSignal(
    @JsonProperty("schema_version") String schemaVersion,
    @JsonProperty("tenant_id") UUID tenantId,
    @JsonProperty("scope_type") String scopeType,
    @JsonProperty("scope_id") String scopeId,
    String provider,
    @JsonProperty("immutable_repository_id") String immutableRepositoryId,
    @JsonProperty("delivery_id") String deliveryId,
    @JsonProperty("event_type") String eventType,
    String ref,
    @JsonProperty("head_sha") String headSha,
    @JsonProperty("body_digest") String bodyDigest,
    @JsonProperty("observed_at") OffsetDateTime observedAt
) {}

// RecordOutcome.java
package com.inforvans.accord.webhook;

public enum RecordOutcome {
    ACCEPTED,
    DUPLICATE,
    DIGEST_CONFLICT
}

// BindingResolver.java
package com.inforvans.accord.webhook;

public interface BindingResolver {
    Binding resolve(String bindingId);
}

// SignalStore.java
package com.inforvans.accord.webhook;

public interface SignalStore {
    RecordOutcome record(ProviderWebhookSignal signal);
}
~~~

`WebhookController.java` reads at most 2 MiB plus one byte directly from `HttpServletRequest`. It resolves the binding from server-side configuration, verifies the exact raw bytes in constant time, and only then parses the JSON:

~~~java
package com.inforvans.accord.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class WebhookController {
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final BindingResolver bindings;
    private final SignalStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WebhookController(
            BindingResolver bindings,
            SignalStore store,
            ObjectMapper mapper,
            Clock clock) {
        this.bindings = bindings;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping("/webhooks/github/{bindingId}")
    public ResponseEntity<Void> receive(
            @PathVariable String bindingId,
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestHeader("X-GitHub-Event") String eventType,
            HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        Binding binding = bindings.resolve(bindingId);
        if (!validSignature(body, signature, binding.secret())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        JsonNode payload = mapper.readTree(body);
        String repositoryId = payload.path("repository").path("id").asText();
        if (!binding.immutableRepositoryId().equals(repositoryId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        String bodyDigest;
        try {
            bodyDigest = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        ProviderWebhookSignal signal = new ProviderWebhookSignal(
            "1.0.0",
            binding.tenantId(),
            "repository",
            binding.immutableRepositoryId(),
            "github",
            binding.immutableRepositoryId(),
            deliveryId,
            eventType,
            nullableText(payload, "ref"),
            nullableText(payload, "after"),
            bodyDigest,
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));

        return switch (store.record(signal)) {
            case ACCEPTED, DUPLICATE -> ResponseEntity.accepted().build();
            case DIGEST_CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    private static boolean validSignature(
            byte[] body,
            String header,
            byte[] secret) {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            byte[] supplied = HexFormat.of().parseHex(header.substring(7));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return MessageDigest.isEqual(supplied, mac.doFinal(body));
        } catch (IllegalArgumentException | GeneralSecurityException error) {
            return false;
        }
    }

    private static String nullableText(JsonNode payload, String name) {
        JsonNode value = payload.path(name);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
~~~

`FileBindingResolver` re-reads a secret-store-projected JSON file for rotation, rejects unknown IDs and secrets shorter than 32 decoded bytes, and accepts only `tenant_id`, `immutable_repository_id`, and `secret_base64`. The projection is a memory-backed read-only volume and never contains Git content credentials. `WebhookEdgeApplication` supplies `Clock.systemUTC()` and starts only this service.

- [ ] **Step 4: Create isolated database identities and the raw-body-free schema**

The administrator-owned bootstrap creates four principals:

| Principal | Login | Purpose |
| --- | --- | --- |
| `accord_webhook_owner` | No | Owns schema, tables, functions, and policies |
| `accord_webhook_migrator_login` | Yes | Can set only `accord_webhook_owner` |
| `accord_webhook_runtime` | No | Holds exact runtime table/function privileges |
| `accord_webhook_runtime_login` | Yes | Can set only `accord_webhook_runtime` |

All four are `NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS`; the login roles have no direct schema/table privileges and no sibling membership. Migration tooling connects as the migrator login then runs `SET ROLE accord_webhook_owner`. The application connects as the runtime login and every physical connection runs `SET ROLE accord_webhook_runtime`.

Create `WebhookDatabaseFixture.java` in the Webhook Edge test source set. It is owned by the edge tests and imports no control-plane test or production package. It loads `/bootstrap/00-pre-flyway-roles.sql` through its class loader, removes only the psql `\\set ON_ERROR_STOP on` meta-command, executes the bootstrap as the Testcontainer administrator, assigns test-only passwords to the two webhook login roles, and runs Flyway from `classpath:migrations` as `accord_webhook_migrator_login` after setting `accord_webhook_owner`. A missing resource, unexpected role name, bootstrap failure, or migration failure aborts the test fixture; no fallback to `user.dir` or a repository-relative path is allowed.

Create `V001__webhook_delivery.sql`:

~~~sql
SET lock_timeout = '5s';
SET statement_timeout = '30s';

CREATE TABLE webhook_delivery (
    tenant_id uuid NOT NULL,
    provider varchar(32) NOT NULL,
    immutable_repository_id varchar(255) NOT NULL,
    delivery_id varchar(255) NOT NULL,
    body_digest char(71) NOT NULL
      CHECK (body_digest ~ '^sha256:[0-9a-f]{64}$'),
    event_type varchar(128) NOT NULL,
    normalized_signal jsonb NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING'
      CHECK (state IN ('PENDING', 'FORWARDED', 'DEAD')),
    received_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    forwarded_at timestamptz,
    PRIMARY KEY (
      tenant_id, provider, immutable_repository_id, delivery_id
    )
);
CREATE INDEX webhook_delivery_pending_idx
  ON webhook_delivery (state, received_at)
  WHERE state = 'PENDING';

CREATE SCHEMA accord_security AUTHORIZATION accord_webhook_owner;
REVOKE ALL ON SCHEMA accord_security FROM PUBLIC;
CREATE FUNCTION accord_security.current_tenant_id() RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE
RETURN NULLIF(current_setting('app.tenant_id', true), '')::uuid;
REVOKE ALL ON FUNCTION accord_security.current_tenant_id() FROM PUBLIC;
GRANT USAGE ON SCHEMA accord_security TO accord_webhook_runtime;
GRANT EXECUTE ON FUNCTION accord_security.current_tenant_id()
  TO accord_webhook_runtime;

ALTER TABLE webhook_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_delivery
  FOR ALL TO PUBLIC
  USING (tenant_id = accord_security.current_tenant_id())
  WITH CHECK (tenant_id = accord_security.current_tenant_id());

REVOKE ALL ON webhook_delivery FROM PUBLIC, accord_webhook_runtime;
GRANT SELECT, INSERT, UPDATE ON webhook_delivery TO accord_webhook_runtime;
~~~

The migration contains no raw request, header map, secret, source, archive, or diff column. The catalog test requires exact ownership, forced RLS, policy expressions, and privileges, failing on any extra grant.

- [ ] **Step 5: Implement transaction-local tenant scoping and digest conflict detection**

`PostgresSignalStore.java` owns no alternate connection or unscoped query path:

~~~java
package com.inforvans.accord.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public final class PostgresSignalStore implements SignalStore {
    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public PostgresSignalStore(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    @Override
    public RecordOutcome record(ProviderWebhookSignal signal) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            tx.execute(
                "SELECT set_config('app.tenant_id', ?, true)",
                signal.tenantId().toString());
            int inserted = tx.execute("""
                INSERT INTO webhook_delivery (
                  tenant_id,provider,immutable_repository_id,delivery_id,
                  body_digest,event_type,normalized_signal
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                signal.tenantId(), signal.provider(),
                signal.immutableRepositoryId(), signal.deliveryId(),
                signal.bodyDigest(), signal.eventType(),
                JSONB.valueOf(serialize(signal)));
            if (inserted == 1) {
                return RecordOutcome.ACCEPTED;
            }
            Record row = tx.fetchOne("""
                SELECT body_digest FROM webhook_delivery
                WHERE tenant_id=? AND provider=?
                  AND immutable_repository_id=? AND delivery_id=?
                FOR UPDATE
                """,
                signal.tenantId(), signal.provider(),
                signal.immutableRepositoryId(), signal.deliveryId());
            if (row == null) {
                throw new IllegalStateException("webhook delivery disappeared");
            }
            return signal.bodyDigest().equals(
                    row.get("body_digest", String.class))
                ? RecordOutcome.DUPLICATE
                : RecordOutcome.DIGEST_CONFLICT;
        });
    }

    private String serialize(ProviderWebhookSignal signal) {
        try {
            return mapper.writeValueAsString(signal);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(
                "normalized signal cannot be serialized", error);
        }
    }
}
~~~

`PostgresSignalStoreTest` starts PostgreSQL 17.5, applies the administrator bootstrap, migrates as the dedicated migrator login/owner role, and connects the store as the runtime login/runtime role. It must prove:

1. Identical delivery/digest returns `DUPLICATE`; changed digest returns `DIGEST_CONFLICT`.
2. Colliding IDs for tenants A and B remain distinct.
3. A tenant-A session cannot select or update tenant B even with all of B's identifiers.
4. A transaction without `app.tenant_id` sees zero rows and cannot insert.
5. Runtime cannot set the owner/sibling role and has no `BYPASSRLS`, schema `CREATE`, ownership, `DELETE`, `TRUNCATE`, `REFERENCES`, or `TRIGGER` privilege.
6. A database-column scan and serialized-signal scan find no raw body or credential-bearing field.

- [ ] **Step 6: Configure, build, and verify the independent artifact**

Replace `apps/webhook-edge/build.gradle` with Groovy DSL:

~~~groovy
plugins {
    alias(libs.plugins.spring.boot)
    id 'java'
}

dependencies {
    implementation libs.spring.boot.web
    implementation libs.spring.boot.actuator
    implementation libs.spring.boot.jooq
    implementation libs.jackson.databind
    runtimeOnly libs.postgresql

    testImplementation libs.spring.boot.test
    testImplementation libs.flyway.core
    testImplementation libs.flyway.postgresql
    testImplementation libs.testcontainers.junit
    testImplementation libs.testcontainers.postgresql
}

sourceSets {
    test {
        resources {
            srcDir rootProject.file('database/webhook-edge')
            include 'bootstrap/**'
            include 'migrations/**'
        }
    }
}
~~~

The canonical Webhook SQL is therefore packaged into the Webhook Edge test runtime without introducing a Gradle dependency on `database/control-plane` or a control-plane test-fixture JAR. Add ArchUnit assertions that both main and test code have no dependency on `database.controlplane` or `apps/control-plane/modules/**`, and make the supply-chain policy reject any `testFixtures(project(':database:control-plane'))` declaration in `apps/webhook-edge/build.gradle`.

Create `application.yml` with Flyway disabled, runtime-login credentials, Hikari `SET ROLE accord_webhook_runtime` initialization, Actuator liveness/readiness probes, a required binding-file path, and no Git content credential property. Build and validate:

~~~bash
./gradlew :apps:webhook-edge:test :apps:webhook-edge:bootJar
pnpm exec ajv validate --spec=draft2020 -s contracts/events/provider-webhook-signal.schema.json -d contracts/golden-fixtures/webhooks/github-push.signal.json
jar tf apps/webhook-edge/build/libs/*.jar
~~~

Expected: tests and schema validation pass; the independent executable jar contains the edge code and bounded libraries but no control-plane domain packages. Invalid signatures are rejected before parsing, no raw body is stored, and database A/B isolation is proven.

- [ ] **Step 7: Commit the isolated ingress service**

~~~bash
git add settings.gradle apps/webhook-edge contracts/events/provider-webhook-signal.schema.json contracts/golden-fixtures/webhooks database/webhook-edge
git commit -m "feat: verify and deduplicate provider webhooks"
~~~

### Task 12: Run Outbox Delivery And Inbox Consumption In `control-worker`

**Files:**
- Create: `database/control-plane/migrations/V003__reliability_coordination_and_fences.sql`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableEventStore.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/TenantWorkRepository.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxRepository.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxDispatcher.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxRepository.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxDispatcher.java`
- Create: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableMessageRedriveService.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/TenantWorkRepositoryTest.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/OutboxDispatcherTest.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/InboxDispatcherTest.java`
- Create: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliableMessageRedriveTest.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/WorkerScheduling.java`
- Create: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/WorkerHealthCoordinator.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/WorkerSchedulingTest.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/WorkerHealthCoordinatorTest.java`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `apps/control-plane/worker/src/main/resources/application.yml`
- Create: `docs/runbooks/reliable-message-redrive.md`

- [ ] **Step 1: Write failing concurrency, recovery, and role-selection tests**

Write PostgreSQL 17.5 Testcontainers tests before implementation. They must cover this complete matrix:

| Test | Required proof |
| --- | --- |
| `TenantWorkRepositoryTest.twoWorkersClaimDifferentTenants` | Two open transactions using `FOR UPDATE SKIP LOCKED` never claim the same tenant work slot |
| `TenantWorkRepositoryTest.staleTenantLeaseCannotReleaseNewGeneration` | Expiry increments generation and replaces the UUID token even when owner text is reused |
| `OutboxDispatcherTest.twoWorkersNeverLeaseSameEvent` | Within one claimed tenant, concurrent workers get disjoint event IDs |
| `OutboxDispatcherTest.expiredLeaseIsReclaimed` | Old generation/token cannot deliver, fail, renew, or release the recovered row |
| `OutboxDispatcherTest.retryIsExactAndBounded` | Backoff and deterministic jitter match the policy; attempt limit transitions once to `DEAD` |
| `InboxDispatcherTest.handlerCrashReplaysSafely` | Crash after domain commit but before acknowledgement replays without duplicating the domain command |
| `InboxDispatcherTest.digestAndSchemaAreRechecked` | Changed digest never enters processing; unsupported schema fails closed and becomes retry/dead state |
| `ReliableMessageRedriveTest.auditFailureRollsBack` | Audit exception leaves state, attempts, and redrive count unchanged |
| `ReliableMessageRedriveTest.optimisticRedriveRunsOnce` | Expected attempts/redrives fence one explicit natural key; duplicate command changes zero rows |
| `WorkerSchedulingTest.processRoleSelectsBeans` | One outbox and one inbox poller exist only for `control-worker`, never `control-api` |
| `WorkerHealthCoordinatorTest.livenessAndReadinessHaveIndependentFailureRules` | Scheduler progress refreshes liveness; database, Temporal, or poller-staleness failure removes readiness without falsely killing liveness |
| `WorkerHealthCoordinatorTest.stateFilesFailClosedAndRecoverAtomically` | Startup and failed checks leave readiness absent; recovery atomically replaces bounded owned files; shutdown removes both files |
| `WorkerHealthCoordinatorTest.unsafeHealthDirectoryIsRejected` | A symlink, non-directory, wrong owner, or group/world-writable health directory aborts worker startup |

Every database test uses the worker login/role, not the container administrator, for runtime operations. Add an SQL-listener assertion that no outbox or inbox table is queried before `set_config('app.tenant_id', ..., true)` in that transaction.

Run:

~~~bash
./gradlew :apps:control-plane:modules:reliability:test --tests '*TenantWorkRepositoryTest' --tests '*OutboxDispatcherTest' --tests '*InboxDispatcherTest' --tests '*ReliableMessageRedriveTest'
./gradlew :apps:control-plane:worker:test --tests '*WorkerSchedulingTest'
~~~

Expected: FAIL because the durable tenant-work directory, generation/token fences, repositories, dispatchers, audited redrive service, and conditional scheduling do not exist.

- [ ] **Step 2: Add a PostgreSQL-only cross-tenant scheduling directory**

Directly scanning `outbox_event` or `inbox_message` without `app.tenant_id` is forbidden and cannot work under forced RLS. V003 therefore adds a payload-free coordination table that the multi-tenant worker may lease before entering a tenant-scoped transaction:

~~~sql
SET lock_timeout = '5s';
SET statement_timeout = '30s';

ALTER TABLE outbox_event
  ADD COLUMN lease_generation bigint NOT NULL DEFAULT 0
    CHECK (lease_generation >= 0),
  ADD COLUMN lease_token uuid,
  ADD CONSTRAINT outbox_lease_token_state
    CHECK ((state = 'DELIVERING') = (lease_token IS NOT NULL));

ALTER TABLE inbox_message
  ADD COLUMN lease_generation bigint NOT NULL DEFAULT 0
    CHECK (lease_generation >= 0),
  ADD COLUMN lease_token uuid,
  ADD CONSTRAINT inbox_lease_token_state
    CHECK ((state = 'PROCESSING') = (lease_token IS NOT NULL));

CREATE TABLE reliability_tenant_work (
    tenant_id uuid PRIMARY KEY,
    available_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    lease_owner varchar(255),
    lease_generation bigint NOT NULL DEFAULT 0
      CHECK (lease_generation >= 0),
    lease_token uuid,
    lease_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CHECK (
      (lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
      OR
      (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    )
);
CREATE INDEX reliability_tenant_work_available_idx
  ON reliability_tenant_work (available_at, tenant_id);

REVOKE ALL ON reliability_tenant_work
  FROM PUBLIC, accord_api, accord_worker;
GRANT SELECT, INSERT, UPDATE ON reliability_tenant_work TO accord_worker;

CREATE FUNCTION accord_security.signal_reliability_work(
    ready_at timestamptz DEFAULT transaction_timestamp()
) RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, public, accord_security
AS $body$
  INSERT INTO public.reliability_tenant_work (tenant_id, available_at)
  VALUES (accord_security.current_tenant_id(), ready_at)
  ON CONFLICT (tenant_id) DO UPDATE
    SET available_at = LEAST(
      public.reliability_tenant_work.available_at,
      EXCLUDED.available_at
    ),
    updated_at = transaction_timestamp()
$body$;
REVOKE ALL ON FUNCTION accord_security.signal_reliability_work(timestamptz)
  FROM PUBLIC;
GRANT EXECUTE ON FUNCTION accord_security.signal_reliability_work(timestamptz)
  TO accord_api, accord_worker;
~~~

`reliability_tenant_work` intentionally contains only tenant UUIDs and coordination timestamps/fences, never business state, payloads, actors, documents, attachments, or provider data. It is the only unscoped multi-tenant reliability table. Its owner is `accord_migrator`; API cannot select or mutate it directly and can only call the parameter-free tenant signal function, whose tenant comes from the transaction-local RLS context. Worker holds exact `SELECT/INSERT/UPDATE` privileges because it is the cross-tenant dispatcher. No role has `DELETE` or `BYPASSRLS`.

Extend `ReliableEventStore.append` and the accepted branch of `acceptInbox` to call `accord_security.signal_reliability_work()` in the same transaction after inserting the message. Retry scheduling and authorized redrive call it with the next `available_at`. Extend `PlatformMigrationTest` to prove exact columns, ownership, grants, function owner/search path/security mode, and absence of payload-bearing columns.

- [ ] **Step 3: Implement tenant-work and message leases with independent fences**

Create public Java records `TenantWorkLease`, `LeasedEvent`, and `LeasedInboxMessage` in matching files. Each contains owner, monotonically increasing generation, opaque UUID token, and exact lease deadline. Acquisition uses one atomic CTE:

~~~sql
WITH candidate AS (
  SELECT tenant_id
  FROM reliability_tenant_work
  WHERE available_at <= ?
    AND (lease_until IS NULL OR lease_until < ?)
  ORDER BY available_at, tenant_id
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
UPDATE reliability_tenant_work work
SET lease_owner=?,
    lease_generation=work.lease_generation+1,
    lease_token=?,
    lease_until=?,
    updated_at=?
FROM candidate
WHERE work.tenant_id=candidate.tenant_id
RETURNING work.tenant_id,work.lease_generation,work.lease_token,work.lease_until
~~~

Release/reschedule compares tenant ID, owner, generation, token, and exact prior lease deadline. A count other than one means the lease was lost; no stale worker may acknowledge a newer lease.

After claiming a tenant, `OutboxRepository.lease` and `InboxRepository.lease` run inside a new worker transaction whose first statement installs `app.tenant_id` and verifies `accord_security.current_tenant_id()`. Their CTEs use `FOR UPDATE SKIP LOCKED` and atomically:

- select only ready `PENDING` rows or expired active rows for that tenant;
- set active state and owner;
- increment `lease_generation` independently of attempt count;
- create a fresh UUID `lease_token`;
- set the exact lease deadline;
- increment attempt count only when beginning a delivery attempt.

Completion, failure, renewal, and recovery compare the complete fence. They clear owner, token, and deadline only after the fenced update succeeds. `attempt_count` measures attempts; it is never reused as a lease generation. All durations are positive and bounded by configuration.

- [ ] **Step 4: Implement bounded dispatch, retry, and downstream idempotency**

`EventTransport` and `InboxHandler` are public functional interfaces in matching files. Implement the two dispatchers in Java 21 with these rules:

1. Claim at most 50 messages for one tenant and cap concurrent work per destination/handler.
2. Transport timeout is shorter than the 30-second lease and includes `event_id` as the downstream idempotency key.
3. Handler validates `payload_schema` and recomputes/verifies `request_digest` before business use. Its domain command key is `(source, source_message_id)`.
4. Retry delay is `min(15 minutes, 5 seconds * 2^(attempt-1))` plus deterministic 0-4 second natural-key jitter.
5. A normalized error code is bounded to 128 characters; stack traces, payloads, tokens, and source material are not persisted.
6. At `maxAttempts`, transition once to `DEAD` and page on dead count/oldest age. `DEAD` is never polled automatically.
7. After each batch, reschedule the tenant work slot to the earliest ready outbox/inbox time, or release it with a bounded idle rescan. This update uses the tenant-work fence.
8. Unknown destination or handler fails closed and follows retry/dead policy; it is never acknowledged as success.

Any idempotency-result cleanup added in this task must use database time and bounded batches, and may delete only rows whose `state='COMPLETED'` and `expires_at < clock_timestamp()`. It must never delete a `STARTED` row, even after its lease or `expires_at` has passed; abandoned `STARTED` work is recovered only through the fenced claim-takeover path.

Add property-based tests with jqwik for delay monotonicity, maximum cap, deterministic jitter, generation growth, and stale-fence rejection. Add fault-injection tests for database disconnect before/after each state transition and process termination after external success but before local acknowledgement.

- [ ] **Step 5: Gate redrive behind fresh authorization and same-transaction audit**

Create one public Java type per file:

~~~java
package com.inforvans.accord.reliability;

import java.time.OffsetDateTime;
import java.util.UUID;

public enum ReliableMessageKind {
    OUTBOX,
    INBOX
}

public record VerifiedRedriveApproval(
    String actorId,
    String authorizationEvidenceDigest,
    String reason,
    String ticketReference
) {
    public VerifiedRedriveApproval {
        if (authorizationEvidenceDigest == null
                || !authorizationEvidenceDigest.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("invalid authorization evidence digest");
        }
        if (reason == null || reason.length() < 8 || reason.length() > 1024) {
            throw new IllegalArgumentException("redrive reason must be 8-1024 characters");
        }
        if (ticketReference == null
                || ticketReference.isBlank()
                || ticketReference.length() > 255) {
            throw new IllegalArgumentException("invalid ticket reference");
        }
    }
}

public record ReliableMessageRedriveEvidence(
    UUID tenantId,
    ReliableMessageKind kind,
    String naturalKey,
    int priorAttemptCount,
    int priorRedriveCount,
    String priorErrorCode,
    String actorId,
    String authorizationEvidenceDigest,
    String reason,
    String ticketReference,
    OffsetDateTime occurredAt
) {}

@FunctionalInterface
public interface ReliableMessageRedriveAudit {
    void append(
        org.jooq.DSLContext tx,
        ReliableMessageRedriveEvidence evidence
    );
}
~~~

`ReliableMessageRedriveService` is the only public redrive entry point. It:

1. accepts a `DSLContext` already inside `TenantTransactions.write(tenantId)`;
2. locks one exact `DEAD` row by natural key, expected attempt count, and expected redrive count;
3. appends bounded audit evidence without payload data;
4. changes the row to `PENDING`, resets attempts, increments redrive count, and signals tenant work;
5. commits all three effects together or rolls all of them back.

Repository `redriveDead` methods are package-private Java methods. Architecture tests forbid controllers, CLI adapters, and schedulers from calling them. Until Identity/Audit binds fresh high-risk authorization, version-bound ActionRequest, tenant transaction, and immutable audit adapter, every production redrive adapter remains absent and fail-closed.

- [ ] **Step 6: Bind polling only in the worker process**

Create `WorkerScheduling.java` with `@ConditionalOnProperty(name = "accord.process-role", havingValue = "control-worker")` and `@EnableScheduling`. It provides:

- one `Clock.systemUTC()` bean unless overridden;
- one tenant-work repository, outbox dispatcher, inbox dispatcher, and one poller for each;
- owner from `accord.process.instance-id`;
- ISO-8601 fixed-delay properties;
- a fail-closed transport when no bounded destination adapter is registered;
- a handler registry keyed by the explicit schema/handler key, rejecting duplicate keys at startup.

Create `WorkerHealthCoordinator.java` in production code and wire it only from `WorkerScheduling`. Its contract is exact:

1. On Linux startup it creates `/tmp/accord` as the worker UID with mode `0700`, rejects an existing symlink/non-directory/wrong-owner/group-or-world-writable path, and removes stale `worker-live` and `worker-ready` files. Tests inject a temporary directory through a package-private constructor; production configuration cannot redirect the path.
2. A dedicated five-second scheduler-progress callback atomically replaces `worker-live` with an ASCII UTC epoch-second plus LF, mode `0600`, only after both poller scheduling loops have completed a turn. It writes a same-directory temporary file with `CREATE_NEW` and `NOFOLLOW_LINKS`, verifies its owner/type/mode, then uses `ATOMIC_MOVE` plus `REPLACE_EXISTING`; unsupported atomic move aborts startup rather than silently weakening the probe contract.
3. A separate readiness pass has a hard two-second total budget. It requires a fresh JDBC connection whose `session_user` is `accord_worker_login` and `current_user` is `accord_worker`, a zero-row privilege probe against `reliability_tenant_work`, a Temporal `GetSystemInfo` RPC with the remaining deadline, and last-success timestamps for both pollers no older than twice their configured fixed delay plus five seconds.
4. Only a fully successful pass atomically replaces `worker-ready` with the same bounded timestamp format. Any exception, timeout, role mismatch, stale poller, interrupt, or failed atomic replacement removes `worker-ready` immediately and records only a normalized metric/error code. It never logs a DSN, SQL text, exception message, tenant, payload, or credential.
5. Liveness does not depend on PostgreSQL, Temporal, or telemetry availability; it expires only when the worker scheduling loop stops making progress. Readiness does depend on PostgreSQL, Temporal, and both pollers. A graceful shutdown deletes both files before closing the scheduler.

`WorkerHealthCoordinatorTest` uses a fake `Clock`, fake readiness checks, and a temporary POSIX filesystem fixture to exercise the full state transition matrix. A Linux-only integration case runs under UID 10002 and proves the final files are regular, non-symlink, owner-only, at most 32 bytes, and never partially readable. Task 16's separate probe consumes this file contract; no shell script is involved.

The worker build remains Groovy DSL:

~~~groovy
plugins {
    alias(libs.plugins.spring.boot)
    id 'java'
}

dependencies {
    implementation project(':apps:control-plane:modules:platform-kernel')
    implementation project(':apps:control-plane:modules:reliability')
    implementation platform(libs.spring.modulith.bom)
    implementation libs.spring.modulith.starter.core
    implementation libs.spring.boot.actuator
    implementation libs.spring.boot.jooq
    implementation libs.temporal.sdk
    runtimeOnly libs.postgresql

    testImplementation libs.spring.boot.test
    testImplementation libs.archunit.junit
    testImplementation libs.temporal.testing
}
~~~

`WorkerSchedulingTest` uses `ApplicationContextRunner` to prove the worker role creates exactly one of each poller and one health coordinator, while the API role creates none. It also proves duplicate handler keys fail startup. `application.yml` defines ISO-8601 poll delays and the fixed health cadence/max-lag bounds; invalid, zero, or excessive durations fail configuration binding before scheduling begins.

- [ ] **Step 7: Create the operational redrive runbook**

`docs/runbooks/reliable-message-redrive.md` must define:

- page triggers for dead count, oldest deliverable age, and abnormal attempt growth;
- triage using only tenant, destination/handler, natural key, schema, counts, timestamps, and normalized error;
- fresh high-risk authorization binding tenant, kind, natural key, expected counts, actor, reason, ticket, and evidence digest;
- one message per command, with an incident-commander manifest capped at 25 explicit keys;
- verification of one redrive-count increment, final delivery, duplicate suppression, and downstream receipt;
- stop conditions for digest/schema/auth/audit/fence failures or unexpected side effects;
- an explicit prohibition on direct SQL, wildcard, destination-wide, tenant-wide, and unbounded redrive.

- [ ] **Step 8: Run and commit the reliable worker slice**

Run:

~~~bash
./gradlew :database:control-plane:test
./gradlew :apps:control-plane:modules:reliability:test
./gradlew :apps:control-plane:worker:test
~~~

Expected: parallel workers receive disjoint tenant and message keys; all stale fences fail; retries and dead-letter transitions are exact; handler replay is idempotent; audited redrive commits once or rolls back fully; no message query bypasses tenant context; worker beans exist only in `control-worker`; heartbeat/readiness files follow the tested fail-closed lifecycle; and the implementation uses PostgreSQL only.

~~~bash
git add database/control-plane/migrations/V003__reliability_coordination_and_fences.sql database/control-plane/src/test/java/com/inforvans/accord/database/PlatformMigrationTest.java apps/control-plane/modules/reliability apps/control-plane/worker docs/runbooks/reliable-message-redrive.md
git commit -m "feat: dispatch and redrive reliable messages"
~~~

### Task 13: Provide A Reproducible Local Integration Harness

**Files:**
- Create: `contracts/capabilities/object-storage-adapter.schema.json`
- Create: `infra/local/object-storage-capabilities.json`
- Create: `infra/local/compose.yaml`
- Create: `infra/local/minio/normal-runtime-policy.json`
- Create: `infra/local/minio/quarantine-scanner-policy.json`
- Create: `infra/local/postgres/00-roles-and-databases.sql`
- Create: `infra/local/wiremock/mappings/get-repository.json`
- Create: `infra/local/localstack/ready.d/10-create-kms-key.sh`
- Create: `infra/local/otel-collector.yaml`
- Create: `scripts/local-up.ps1`
- Create: `scripts/local-down.ps1`
- Create: `tests/integration/local-foundation.ps1`

- [ ] **Step 1: Write the failing harness inventory test**

Create `tests/integration/local-foundation.ps1`:

```powershell
$ErrorActionPreference = 'Stop'
$compose = 'infra/local/compose.yaml'
$required = @('postgres', 'temporal', 'temporal-ui', 'minio', 'minio-init', 'mock-git-provider', 'mock-kms', 'otel-collector')
$services = docker compose -f $compose config --services
$missing = $required | Where-Object { $_ -notin $services }
if ($missing.Count -gt 0) { throw "Missing local services: $($missing -join ', ')" }

$postgres = docker compose -f $compose exec -T postgres pg_isready -U postgres
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL is not ready: $postgres" }
function Invoke-PostgresCatalog([string] $Sql) {
  $rows = @(docker compose -f $compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -At -c $Sql)
  if ($LASTEXITCODE -ne 0) { throw "PostgreSQL catalog query failed: $Sql" }
  return @($rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}
$signingOwner = @(Invoke-PostgresCatalog "SELECT concat_ws('|',rolname,rolcanlogin,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolinherit,rolbypassrls) FROM pg_roles WHERE rolname='accord_signing_owner'")
if ($signingOwner.Count -ne 1 -or $signingOwner[0] -ne 'accord_signing_owner|f|f|f|f|f|f|t') {
  throw "accord_signing_owner is not the exact approved NOLOGIN BYPASSRLS role: $signingOwner"
}
$unexpectedElevated = @(Invoke-PostgresCatalog "SELECT rolname FROM pg_roles WHERE rolname LIKE 'accord_%' AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR (rolbypassrls AND rolname <> 'accord_signing_owner')) ORDER BY rolname")
if ($unexpectedElevated.Count -ne 0) { throw "Unexpected elevated Accord roles: $unexpectedElevated" }
$signingMembers = @(Invoke-PostgresCatalog "SELECT concat_ws('|',member.rolname,am.admin_option,am.inherit_option,am.set_option) FROM pg_auth_members am JOIN pg_roles parent ON parent.oid=am.roleid JOIN pg_roles member ON member.oid=am.member WHERE parent.rolname='accord_signing_owner' ORDER BY member.rolname")
if ($signingMembers.Count -ne 1 -or $signingMembers[0] -ne 'accord_signing_migrator|f|f|t') {
  throw "Signing owner membership is not migration-only: $signingMembers"
}
$webhookMembers = @(Invoke-PostgresCatalog "SELECT concat_ws('|',member.rolname,parent.rolname,am.admin_option,am.inherit_option,am.set_option) FROM pg_auth_members am JOIN pg_roles parent ON parent.oid=am.roleid JOIN pg_roles member ON member.oid=am.member WHERE member.rolname IN ('accord_webhook_migrator_login','accord_webhook_runtime_login') ORDER BY member.rolname")
$expectedWebhookMembers = @(
  'accord_webhook_migrator_login|accord_webhook_owner|f|f|t',
  'accord_webhook_runtime_login|accord_webhook_runtime|f|f|t'
)
if (($webhookMembers -join "`n") -cne ($expectedWebhookMembers -join "`n")) {
  throw "Webhook role memberships are not exact: $webhookMembers"
}
$webhookRoleCount = @(Invoke-PostgresCatalog "SELECT count(*) FROM pg_roles WHERE rolname IN ('accord_webhook_owner','accord_webhook_runtime') AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolinherit AND NOT rolbypassrls")
if ($webhookRoleCount.Count -ne 1 -or $webhookRoleCount[0] -ne '2') {
  throw "Webhook privilege roles are not exact NOLOGIN roles: $webhookRoleCount"
}
$runtimeBypassMembership = @(Invoke-PostgresCatalog "WITH RECURSIVE closure(member_oid,role_oid) AS (SELECT member,roleid FROM pg_auth_members UNION SELECT c.member_oid,m.roleid FROM closure c JOIN pg_auth_members m ON m.member=c.role_oid) SELECT DISTINCT member.rolname || '->' || parent.rolname FROM closure c JOIN pg_roles member ON member.oid=c.member_oid JOIN pg_roles parent ON parent.oid=c.role_oid WHERE member.rolname IN ('accord_api_login','accord_worker_login','accord_webhook_runtime_login','accord_signing_runtime') AND parent.rolbypassrls ORDER BY 1")
if ($runtimeBypassMembership.Count -ne 0) { throw "Runtime role reaches BYPASSRLS transitively: $runtimeBypassMembership" }
$temporal = Invoke-RestMethod -Uri 'http://localhost:8233/api/v1/namespaces' -TimeoutSec 5
if (-not $temporal) { throw 'Temporal UI API did not return namespaces' }
$git = Invoke-RestMethod -Uri 'http://localhost:9080/repos/acme/demo' -TimeoutSec 5
if ($git.id -ne 77831) { throw "Mock Git immutable repository id was $($git.id)" }
$capabilitySchema = 'contracts/capabilities/object-storage-adapter.schema.json'
$capabilityProfile = 'infra/local/object-storage-capabilities.json'
pnpm exec ajv validate --spec=draft2020 -s $capabilitySchema -d $capabilityProfile
if ($LASTEXITCODE -ne 0) { throw 'Local object-storage capability profile is invalid' }
$profile = Get-Content -Raw -Encoding utf8 $capabilityProfile | ConvertFrom-Json -Depth 20
if ($profile.production_eligible -ne $false) {
  throw 'The local MinIO profile must never claim production eligibility'
}
$requiredLocal = @('immutable_versions','sha256_checksums','worm_retention','legal_hold','multipart','quarantine_isolation')
$missingLocal = $requiredLocal | Where-Object { $profile.capabilities.$_.status -ne 'SUPPORTED_AND_TESTED' }
if ($missingLocal.Count -ne 0) { throw "Unproved local object capabilities: $missingLocal" }
$externalOnly = @('replication_evidence','deletion_receipts')
$wrongExternal = $externalOnly | Where-Object { $profile.capabilities.$_.status -ne 'EXTERNAL_EVIDENCE_REQUIRED' }
if ($wrongExternal.Count -ne 0) { throw "Invalid local external-evidence declarations: $wrongExternal" }
$retention = @(docker compose -f $compose run --rm --entrypoint /bin/sh minio-init -ec "mc alias set local http://minio:9000 accord-local accord-local-secret >/dev/null && mc retention info local/accord-object-lock")
if ($LASTEXITCODE -ne 0) { throw "Object-lock retention is unavailable: $retention" }
$retentionText = $retention -join "`n"
if (-not $retentionText.Contains('GOVERNANCE') -or $retentionText -notmatch '(?i)\b30d\b') {
  throw "Object-lock default is not 30-day GOVERNANCE: $retentionText"
}
$aliases = docker compose -f $compose exec -T mock-kms awslocal kms list-aliases
if (-not $aliases.Contains('alias/accord-local-signing')) { throw 'Mock KMS signing alias is missing' }
Write-Output 'local-foundation: PASS'
```

- [ ] **Step 2: Run the inventory test and verify Compose is absent**

Run: `pwsh -NoProfile -File tests/integration/local-foundation.ps1`

Expected: FAIL because `infra/local/compose.yaml` does not exist.

- [ ] **Step 3: Define isolated local services and PostgreSQL coordination**

Create `object-storage-adapter.schema.json` as a closed JSON Schema 2020-12 contract. It requires `schema_version`, `adapter_id`, `adapter_version`, `adapter_artifact_digest`, `environment_class`, `tested_at`, `expires_at`, `evidence_bundle_digest`, `production_eligible`, and exactly these capability objects: `immutable_versions`, `sha256_checksums`, `worm_retention`, `legal_hold`, `multipart`, `quarantine_isolation`, `replication_evidence`, and `deletion_receipts`. Each capability has only `status`, `test_id`, and a non-empty array of immutable evidence references. Status is one of `SUPPORTED_AND_TESTED`, `UNSUPPORTED`, or `EXTERNAL_EVIDENCE_REQUIRED`. Digests use lowercase `sha256:<64 hex>`; timestamps are UTC and expiry must be checked by consumers because JSON Schema cannot compare time values.

The schema's production branch requires `environment_class: production`, `production_eligible: true`, and `SUPPORTED_AND_TESTED` for all eight capabilities. The local profile is closed and has `production_eligible: false`: it marks version IDs, SHA-256 verification, WORM retention, legal hold, multipart, and quarantine isolation as `SUPPORTED_AND_TESTED`, while replication evidence and deletion receipts are `EXTERNAL_EVIDENCE_REQUIRED`. Its artifact/evidence digests are real hashes created during implementation and reviewed with the pinned MinIO image; neither zero/fake digests nor mutable evidence URLs pass validation.

Create `infra/local/compose.yaml`:

```yaml
name: accord-local
services:
  postgres:
    image: postgres:17.5
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres-local-only
    ports: ["5432:5432"]
    volumes:
      - ./postgres/00-roles-and-databases.sql:/docker-entrypoint-initdb.d/00-roles-and-databases.sql:ro
      - ./data/postgres:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 2s
      timeout: 2s
      retries: 30
  temporal:
    image: temporalio/auto-setup:1.28.1
    depends_on:
      postgres: { condition: service_healthy }
    environment:
      DB: postgres12
      DB_PORT: 5432
      POSTGRES_SEEDS: postgres
      POSTGRES_USER: postgres
      POSTGRES_PWD: postgres-local-only
      DYNAMIC_CONFIG_FILE_PATH: config/dynamicconfig/development-sql.yaml
    ports: ["7233:7233"]
  temporal-ui:
    image: temporalio/ui:2.39.0
    depends_on: [temporal]
    environment:
      TEMPORAL_ADDRESS: temporal:7233
    ports: ["8233:8080"]
  minio:
    image: quay.io/minio/minio:RELEASE.2025-06-13T11-33-47Z
    command: server /data --console-address :9001
    environment:
      MINIO_ROOT_USER: accord-local
      MINIO_ROOT_PASSWORD: accord-local-secret
    ports: ["9000:9000", "9001:9001"]
    volumes: ["./data/minio:/data"]
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 2s
      timeout: 2s
      retries: 30
  minio-init:
    image: quay.io/minio/mc:RELEASE.2025-05-21T01-59-54Z
    depends_on:
      minio: { condition: service_healthy }
    entrypoint: ["/bin/sh", "-ec"]
    command:
      - >-
        mc alias set local http://minio:9000 accord-local accord-local-secret;
        mc mb --ignore-existing --with-lock local/accord-object-lock;
        mc mb --ignore-existing --with-lock local/accord-quarantine;
        mc version enable local/accord-object-lock;
        mc version enable local/accord-quarantine;
        mc retention set --default GOVERNANCE 30d local/accord-object-lock;
  mock-git-provider:
    image: wiremock/wiremock:3.13.1
    command: ["--global-response-templating", "--disable-gzip"]
    ports: ["9080:8080"]
    volumes: ["./wiremock:/home/wiremock:ro"]
  mock-kms:
    image: localstack/localstack:4.6.0
    environment:
      SERVICES: kms
      AWS_DEFAULT_REGION: ap-northeast-1
    ports: ["4566:4566"]
    volumes:
      - ./localstack/ready.d:/etc/localstack/init/ready.d:ro
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.129.0
    command: ["--config=/etc/otelcol/config.yaml"]
    volumes: ["./otel-collector.yaml:/etc/otelcol/config.yaml:ro"]
    ports: ["4317:4317", "4318:4318", "9464:9464"]
```

The init container also installs the two mounted closed MinIO policies and creates separate local-only service accounts: the normal runtime can access `accord-object-lock` but is explicitly denied `accord-quarantine`; the scanner can read/write quarantine but cannot read normal objects. No application receives the root credential. `local-foundation.ps1` uploads a deterministic fixture twice and requires distinct immutable version IDs, checks the provider-reported SHA-256 against a locally recomputed digest on upload and download, applies/reads governance retention and legal hold, completes a forced multi-part upload with no orphaned parts, and proves both directions of the quarantine deny matrix. It then verifies that the two external-only capabilities remain non-production declarations. The fixed test definitions and redacted result bundle are hashed and must match the immutable evidence references in the local profile.

Create `infra/local/postgres/00-roles-and-databases.sql`:

```sql
CREATE ROLE accord_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE ROLE accord_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE ROLE accord_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE ROLE accord_migrator_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD 'local-migrator-only';
CREATE ROLE accord_api_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD 'local-api-only';
CREATE ROLE accord_worker_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD 'local-worker-only';
CREATE ROLE accord_webhook_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE ROLE accord_webhook_migrator_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD 'local-webhook-migrator-only';
CREATE ROLE accord_webhook_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE ROLE accord_webhook_runtime_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD 'local-webhook-runtime-only';
CREATE ROLE accord_signing_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT BYPASSRLS;
CREATE ROLE accord_signing_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD 'local-signing-migrator-only';
CREATE ROLE accord_signing_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD 'local-signing-runtime-only';

GRANT accord_migrator TO accord_migrator_login WITH INHERIT FALSE;
GRANT accord_migrator TO accord_migrator_login WITH SET TRUE;
GRANT accord_migrator TO accord_migrator_login WITH ADMIN FALSE;
GRANT accord_api TO accord_api_login WITH INHERIT FALSE;
GRANT accord_api TO accord_api_login WITH SET TRUE;
GRANT accord_api TO accord_api_login WITH ADMIN FALSE;
GRANT accord_worker TO accord_worker_login WITH INHERIT FALSE;
GRANT accord_worker TO accord_worker_login WITH SET TRUE;
GRANT accord_worker TO accord_worker_login WITH ADMIN FALSE;
GRANT accord_webhook_owner TO accord_webhook_migrator_login WITH INHERIT FALSE;
GRANT accord_webhook_owner TO accord_webhook_migrator_login WITH SET TRUE;
GRANT accord_webhook_owner TO accord_webhook_migrator_login WITH ADMIN FALSE;
GRANT accord_webhook_runtime TO accord_webhook_runtime_login WITH INHERIT FALSE;
GRANT accord_webhook_runtime TO accord_webhook_runtime_login WITH SET TRUE;
GRANT accord_webhook_runtime TO accord_webhook_runtime_login WITH ADMIN FALSE;
GRANT accord_signing_owner TO accord_signing_migrator WITH INHERIT FALSE;
GRANT accord_signing_owner TO accord_signing_migrator WITH SET TRUE;
GRANT accord_signing_owner TO accord_signing_migrator WITH ADMIN FALSE;

CREATE DATABASE accord OWNER accord_migrator;
CREATE DATABASE accord_webhook OWNER accord_webhook_owner;
CREATE DATABASE accord_signing OWNER accord_signing_owner;

\connect accord
REVOKE CONNECT ON DATABASE accord FROM PUBLIC;
GRANT CONNECT ON DATABASE accord TO accord_migrator_login, accord_api_login, accord_worker_login;
GRANT USAGE, CREATE ON SCHEMA public TO accord_migrator;
GRANT USAGE ON SCHEMA public TO accord_api, accord_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator IN SCHEMA public
  REVOKE ALL ON TABLES FROM accord_api, accord_worker;

\connect accord_webhook
REVOKE CONNECT ON DATABASE accord_webhook FROM PUBLIC;
GRANT CONNECT ON DATABASE accord_webhook TO accord_webhook_migrator_login, accord_webhook_runtime_login;
GRANT USAGE, CREATE ON SCHEMA public TO accord_webhook_owner;
GRANT USAGE ON SCHEMA public TO accord_webhook_runtime;

\connect accord_signing
GRANT USAGE, CREATE ON SCHEMA public TO accord_signing_owner;
GRANT CONNECT ON DATABASE accord_signing TO accord_signing_runtime;
GRANT USAGE ON SCHEMA public TO accord_signing_runtime;
```

The control-plane DSNs use only `accord_migrator_login`, `accord_api_login`, and `accord_worker_login`; each session must explicitly set its one paired NOLOGIN role. `accord_webhook_migrator_login` and `accord_signing_migrator` are migration-only credentials and are never mounted into runtime workloads; their table owners are NOLOGIN. Webhook runtime connects only as `accord_webhook_runtime_login` and sets `accord_webhook_runtime`; signing runtime connects only as `accord_signing_runtime`. No runtime login directly or transitively reaches an owner role. The signing table owner is the sole approved Accord `BYPASSRLS` role, and no database installs a default table DML grant. Every migration grants exact table privileges only after forced RLS succeeds.

Create `infra/local/wiremock/mappings/get-repository.json`:

```json
{
  "request": { "method": "GET", "urlPath": "/repos/acme/demo" },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "id": 77831, "node_id": "R_kgDOABCD", "name": "demo", "default_branch": "main" }
  }
}
```

Create `infra/local/localstack/ready.d/10-create-kms-key.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
key_id="$(awslocal kms create-key --description 'Accord local signing' --key-usage SIGN_VERIFY --key-spec ECC_NIST_P256 --query KeyMetadata.KeyId --output text)"
awslocal kms create-alias --alias-name alias/accord-local-signing --target-key-id "$key_id"
```

Create `infra/local/otel-collector.yaml`:

```yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }
      http: { endpoint: 0.0.0.0:4318 }
processors:
  memory_limiter: { limit_mib: 256, check_interval: 1s }
  batch: {}
exporters:
  debug: { verbosity: basic }
  prometheus: { endpoint: 0.0.0.0:9464 }
service:
  pipelines:
    traces: { receivers: [otlp], processors: [memory_limiter, batch], exporters: [debug] }
    metrics: { receivers: [otlp], processors: [memory_limiter, batch], exporters: [prometheus] }
```

- [ ] **Step 4: Add deterministic start and stop scripts**

Create `scripts/local-up.ps1`:

```powershell
$ErrorActionPreference = 'Stop'
$compose = 'infra/local/compose.yaml'
docker compose -f $compose up -d --wait postgres temporal temporal-ui minio minio-init mock-git-provider mock-kms otel-collector
$env:ACCORD_DB_URL = 'jdbc:postgresql://localhost:5432/accord'
$env:ACCORD_DB_MIGRATOR_USER = 'accord_migrator_login'
$env:ACCORD_DB_MIGRATOR_PASSWORD = 'local-migrator-only'
./gradlew :database:control-plane:flywayMigrate
Get-ChildItem -LiteralPath 'database/webhook-edge/migrations' -Filter '*.sql' | Sort-Object Name | ForEach-Object {
  $migration = "SET ROLE accord_webhook_owner;`n" + (Get-Content -Raw -Encoding utf8 $_.FullName)
  $migration | docker compose -f $compose exec -T -e PGPASSWORD=local-webhook-migrator-only postgres psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -U accord_webhook_migrator_login -d accord_webhook
  if ($LASTEXITCODE -ne 0) { throw "Webhook migration failed: $($_.Name)" }
}
Write-Output 'Accord local foundation is ready'
```

Create `scripts/local-down.ps1`:

```powershell
$ErrorActionPreference = 'Stop'
docker compose -f infra/local/compose.yaml down --remove-orphans
if ($LASTEXITCODE -ne 0) { throw 'Failed to stop Accord local services' }
Write-Output 'Accord local foundation is stopped; persisted data remains under infra/local/data'
```

- [ ] **Step 5: Start and verify the complete harness**

Run:

```bash
pwsh -NoProfile -File scripts/local-up.ps1
./gradlew :apps:control-plane:modules:reliability:test --tests '*TenantWorkRepositoryTest' --tests '*OutboxDispatcherTest' --tests '*InboxDispatcherTest'
pwsh -NoProfile -File tests/integration/local-foundation.ps1
```

Expected: startup reports all health checks ready and applies both database schemas; the verification proves `accord_signing_owner` is the only Accord `BYPASSRLS` role, is NOLOGIN with only the migration member, and is unreachable from every runtime role; PostgreSQL tenant-work, message-lease generation/token fencing, and stale-acknowledgement rejection pass; MinIO proves the six locally testable capabilities and quarantine deny matrix; the schema rejects using the local profile as production evidence because replication and deletion receipts require external proof; and the run ends with `local-foundation: PASS`.

- [ ] **Step 6: Commit the local integration harness**

```bash
git add contracts/capabilities/object-storage-adapter.schema.json infra/local/object-storage-capabilities.json infra/local/compose.yaml infra/local/minio infra/local/postgres/00-roles-and-databases.sql infra/local/wiremock/mappings/get-repository.json infra/local/localstack/ready.d/10-create-kms-key.sh infra/local/otel-collector.yaml scripts/local-up.ps1 scripts/local-down.ps1 tests/integration/local-foundation.ps1
git commit -m "build: add local Accord integration harness"
```

### Task 14: Wire OpenTelemetry With Sensitive-Data Guardrails

**Files:**
- Create: `libs/java/observability/src/main/java/com/inforvans/accord/observability/SafeTelemetryAttributes.java`
- Create: `libs/java/observability/src/test/java/com/inforvans/accord/observability/SafeTelemetryAttributesTest.java`
- Create: `tests/security-negative/src/test/java/com/inforvans/accord/security/TelemetryLeakTest.java`
- Modify: `libs/java/observability/build.gradle`
- Modify: `gradle/libs.versions.toml`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `apps/webhook-edge/build.gradle`
- Modify: `apps/control-plane/api/src/main/resources/application.yml`
- Modify: `apps/control-plane/worker/src/main/resources/application.yml`
- Modify: `apps/webhook-edge/src/main/resources/application.yml`

- [ ] **Step 1: Write failing allowlist and end-to-end leak tests**

`SafeTelemetryAttributesTest` supplies allowed scope fields plus authorization, cookie, token, webhook body, attachment text, source, diff, prompt, and arbitrary user text. Assert that only the exact allowlist remains, values are capped at 255 Unicode code points, control characters are rejected, and caller-owned maps cannot mutate the result.

`TelemetryLeakTest` starts API, worker, and Webhook Edge with an in-memory OpenTelemetry exporter and a JSON log appender, submits sentinel secrets through headers and bodies, and asserts the sentinels do not occur in:

- span names, attributes, events, links, resource attributes, or baggage;
- metric names, tags, exemplars, or descriptions;
- structured log fields, exception messages, or MDC;
- health responses and error details.

It also proves HTTP spans use locked route templates rather than raw paths/query strings and that error telemetry records a bounded result code rather than an exception message containing input.

Run:

~~~bash
./gradlew :libs:java:observability:test :tests:security-negative:test --tests '*TelemetryLeakTest'
~~~

Expected: FAIL because the bounded observability library and process wiring are absent.

- [ ] **Step 2: Implement one immutable Java allowlist**

Create `SafeTelemetryAttributes.java`:

~~~java
package com.inforvans.accord.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class SafeTelemetryAttributes {
    private static final Set<String> ALLOWED = Set.of(
        "tenant_id",
        "scope_type",
        "scope_id",
        "correlation_id",
        "causation_id",
        "aggregate_type",
        "event_type",
        "operation",
        "provider",
        "result_code"
    );
    private static final int MAX_CODE_POINTS = 255;

    private SafeTelemetryAttributes() {}

    public static Map<String, String> from(Map<String, String> input) {
        Map<String, String> output = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (!ALLOWED.contains(key) || value == null) {
                return;
            }
            if (value.codePoints().anyMatch(codePoint ->
                    Character.isISOControl(codePoint))) {
                throw new IllegalArgumentException(
                    "telemetry attribute contains a control character");
            }
            int end = value.offsetByCodePoints(
                0,
                Math.min(value.codePointCount(0, value.length()), MAX_CODE_POINTS));
            output.put(key, value.substring(0, end));
        });
        return Map.copyOf(output);
    }
}
~~~

No overload accepts arbitrary objects, request/response types, headers, exception instances, or `toString()` values. Instrumentation must construct an explicit string map and pass it through this method. A build-time ArchUnit rule forbids direct calls to span `setAttribute` from Accord packages outside `libs/java/observability` and generated instrumentation.

- [ ] **Step 3: Configure Spring Boot OpenTelemetry consistently in all Java processes**

Add version-catalog aliases:

~~~toml
micrometer-tracing-otel = { module = "io.micrometer:micrometer-tracing-bridge-otel" }
micrometer-registry-prometheus = { module = "io.micrometer:micrometer-registry-prometheus" }
otel-exporter-otlp = { module = "io.opentelemetry:opentelemetry-exporter-otlp" }
otel-sdk-testing = { module = "io.opentelemetry:opentelemetry-sdk-testing" }
~~~

`libs/java/observability/build.gradle` uses `java-library` and exposes only the OpenTelemetry API needed by its bounded adapter. API, worker, and Webhook Edge each depend on that library plus Micrometer bridge, Prometheus registry, and OTLP exporter. They remain separate processes and emit distinct `service.name` values.

Merge this management configuration into all three `application.yml` files without removing their existing datasource, Modulith, process-role, or health settings:

~~~yaml
management:
  endpoints.web.exposure.include: health,prometheus
  endpoint.health.probes.enabled: true
  tracing:
    sampling.probability: ${ACCORD_TRACE_SAMPLE_RATE:0.1}
    baggage.enabled: false
  otlp:
    tracing.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
    metrics.export.url: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/metrics
  metrics.tags:
    service: ${spring.application.name}
server:
  forward-headers-strategy: framework
~~~

Configure instrumentation to suppress request/response headers, query strings, bodies, form values, cookies, and exception messages. Only normalized method, locked route template, status code, duration, and `SafeTelemetryAttributes` output are permitted. The Webhook Edge continues to store only body digest plus normalized signal; telemetry never receives raw bytes.

- [ ] **Step 4: Add cardinality, failure, and shutdown controls**

For each process:

1. Bound route, operation, provider, result, aggregate, and event values to registered enums/templates; never use IDs as metric names or span names.
2. Apply tenant/scope IDs only to traces and structured audit correlation, not Prometheus labels.
3. Use batch span export with bounded queue, export timeout, and drop counters; application work must not block on collector failure.
4. Flush exporters during graceful shutdown with a five-second cap, then terminate without extending the pod grace period indefinitely.
5. Keep readiness independent from collector availability; expose exporter failure/drop metrics.
6. Use head sampling defaults locally and a production parent-based sampler configured by environment. Security/audit events are business records, not guaranteed by trace sampling.
7. Prohibit automatic log capture of MDC keys not explicitly installed by the observability adapter.

Add tests for collector outage, queue saturation, shutdown timeout, long Unicode values, metric-cardinality bounds, and no telemetry recursion.

- [ ] **Step 5: Verify collector ingestion and negative sentinels**

Run:

~~~bash
./gradlew :libs:java:observability:test :tests:security-negative:test
pwsh -NoProfile -File scripts/local-up.ps1
./gradlew :apps:control-plane:api:bootRun --args='--server.port=18080'
~~~

From a second shell, invoke readiness and one authenticated fixture request, then inspect collector output. Expected:

- resources identify `accord-control-api`, `accord-control-worker`, and `accord-webhook-edge` independently;
- readiness remains 200 when the collector is stopped;
- exporter failure/drop metrics increase as designed;
- searching logs/exported telemetry for every sentinel secret returns zero matches;
- no header, body, raw URL, attachment text, source, prompt, token, or cookie is present.

- [ ] **Step 6: Commit observable, redacted process wiring**

~~~bash
git add gradle/libs.versions.toml libs/java/observability apps/control-plane/api/build.gradle apps/control-plane/worker/build.gradle apps/webhook-edge/build.gradle apps/control-plane/api/src/main/resources/application.yml apps/control-plane/worker/src/main/resources/application.yml apps/webhook-edge/src/main/resources/application.yml tests/security-negative
git commit -m "feat: add redacted OpenTelemetry instrumentation"
~~~

### Task 15: Encode Kubernetes Process Isolation With Helm, OpenTofu, And Argo CD

**Files:**
- Create: `infra/helm/accord/Chart.yaml`
- Create: `infra/helm/accord/values.yaml`
- Create: `infra/helm/accord/values.schema.json`
- Create: `infra/helm/accord/templates/serviceaccounts.yaml`
- Create: `infra/helm/accord/templates/workloads.yaml`
- Create: `infra/helm/accord/templates/services.yaml`
- Create: `infra/helm/accord/templates/networkpolicies.yaml`
- Create: `infra/helm/accord/templates/poddisruptionbudgets.yaml`
- Create: `infra/policy/accord.rego`
- Create: `infra/opentofu/modules/accord-foundation-contract/variables.tf`
- Create: `infra/opentofu/modules/accord-foundation-contract/main.tf`
- Create: `infra/opentofu/modules/accord-foundation-contract/outputs.tf`
- Create: `infra/argocd/accord.yaml`
- Create: `tests/integration/deployment-contract.ps1`

- [ ] **Step 1: Write the failing rendered-deployment contract**

`deployment-contract.ps1` must run `helm lint --strict`, render every production value variant, validate with Kubeconform, and run Conftest. Parse the rendered YAML structurally and fail unless all of the following hold:

1. `control-api`, `control-worker`, and `webhook-edge` have different ServiceAccounts, database Secrets, login roles, session roles, Deployments, and immutable image-digest references.
2. API uses `accord_api_login` then `accord_api`; worker uses `accord_worker_login` then `accord_worker`; Webhook Edge uses `accord_webhook_runtime_login` then `accord_webhook_runtime`.
3. API and Webhook Edge probes use `/actuator/health/readiness` and `/actuator/health/liveness`. Worker uses bounded exec probes backed by its database/Temporal readiness and scheduler heartbeat, never `kill -0 1` alone.
4. No workload receives another workload's Secret, ServiceAccount, role, projected webhook secret, or network destination.
5. No control-plane workload receives a Git content/source/archive credential. Webhook binding projection contains webhook verification secrets only.
6. Every container runs non-root with a fixed UID/GID, read-only root filesystem, default seccomp, all capabilities dropped, no privilege escalation, bounded `/tmp`, resource requests/limits, termination grace period, and rolling-update constraints.
7. Every replicated process has topology spread, anti-affinity, and a PodDisruptionBudget that preserves one ready replica.
8. Default-deny ingress/egress is present; every allowed edge matches the explicit matrix below.
9. ServiceAccount token automount is false unless a later plan supplies an explicit workload-identity projection.
10. Production values use digest-pinned images and external secret references; plaintext credentials fail schema/policy validation.

Run the test before creating the chart. Expected: FAIL because the chart is absent.

- [ ] **Step 2: Define strict chart values and process-specific workloads**

`Chart.yaml` pins the Kubernetes floor to 1.31. `values.schema.json` sets `additionalProperties: false` at every owned object, requires all three components, validates image digests, positive replica/resource/probe values, exact role names, and unique secret/account names.

The baseline values are:

~~~yaml
global:
  imagePullPolicy: IfNotPresent
  otlpEndpoint: http://otel-collector.observability.svc:4318
  terminationGracePeriodSeconds: 30
components:
  control-api:
    image: registry.example.invalid/accord-control-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    replicas: 2
    port: 8080
    processRole: control-api
    databaseSecret: accord-control-api-db
    databaseLoginRole: accord_api_login
    databaseSessionRole: accord_api
    readinessPath: /actuator/health/readiness
    livenessPath: /actuator/health/liveness
    serviceAccount: accord-control-api
    runAsUser: 10001
  control-worker:
    image: registry.example.invalid/accord-control-worker@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    replicas: 2
    processRole: control-worker
    databaseSecret: accord-control-worker-db
    databaseLoginRole: accord_worker_login
    databaseSessionRole: accord_worker
    readinessCommand: ["/usr/bin/java", "-jar", "/opt/accord/bin/worker-probe.jar", "ready", "20"]
    livenessCommand: ["/usr/bin/java", "-jar", "/opt/accord/bin/worker-probe.jar", "live", "20"]
    serviceAccount: accord-control-worker
    runAsUser: 10002
  webhook-edge:
    image: registry.example.invalid/accord-webhook-edge@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
    replicas: 2
    port: 8080
    processRole: webhook-edge
    databaseSecret: accord-webhook-edge-db
    databaseLoginRole: accord_webhook_runtime_login
    databaseSessionRole: accord_webhook_runtime
    webhookBindingSecret: accord-webhook-bindings
    readinessPath: /actuator/health/readiness
    livenessPath: /actuator/health/liveness
    serviceAccount: accord-webhook-edge
    runAsUser: 10003
~~~

The templates render one workload per component, not one shared pod. Database secret keys map only to the owning process's URL/login/password environment names. Webhook bindings mount read-only at `/run/secrets/accord/webhook-bindings.json` on a memory-backed Secret/CSI projection and are never exposed as environment values.

This Foundation checkpoint renders only the repository-event `webhook-edge` profile because the Provider authorization callback contract and edge-local V003 inbox do not exist until Git Delivery Task 5. The canonical V1 boundary manifest already reserves the required `provider-auth-callback-edge` runtime profile; Git Delivery Task 5 must extend this same central chart with that second profile using the same signed image digest and a distinct ServiceAccount, database role, encryption key, ingress path, mTLS audience, queue and NetworkPolicy. M0 must not expose a callback route or create a placeholder identity that can receive traffic.

Worker implementation from Task 12 maintains `/tmp/accord/worker-live` and `worker-ready` with distinct liveness/readiness semantics. The exec command starts the independently packaged, JDK-only `worker-probe.jar` with the Java executable already present in the approved distroless runtime; it never invokes `/bin/sh`. The probe validates argument bounds, ownership, owner-only mode, regular-file type with `NOFOLLOW_LINKS`, bounded content, timestamp parsing, future-clock skew, and maximum age, and exits within the Kubernetes two-second timeout.

- [ ] **Step 3: Encode the exact network and workload policy**

Create separate NetworkPolicies, never one broad control-plane policy:

| Source | Allowed destination | Port/purpose |
| --- | --- | --- |
| ingress gateway | control-api | TCP 8080 |
| edge gateway | webhook-edge | TCP 8080 |
| control-api | control-plane PostgreSQL | TCP 5432 |
| control-worker | control-plane PostgreSQL | TCP 5432 |
| control-worker | Temporal frontend | TCP 7233 mTLS |
| each process | OTel collector | TCP 4318 |
| each process | cluster DNS | UDP/TCP 53 |
| webhook-edge | webhook PostgreSQL | TCP 5432 |

API has no Temporal or provider egress. Webhook Edge has no control-plane database, Temporal, object-storage, or provider egress. Worker provider/object-storage egress is absent until an owning feature plan adds a destination-specific policy. Namespace selectors and pod selectors are both required; IP-wide or namespace-only data-plane allows fail policy.

`accord.rego` denies mutable image tags in production, missing security context/resources/probes/PDB/spread constraints, automatic tokens, host namespaces/paths, privileged ports, broad egress, inline Secret data, and any environment/volume name implying source credentials in control plane. It also compares component role/secret/account tuples against the locked matrix.

- [ ] **Step 4: Bind environment capabilities in OpenTofu**

Create variables for:

- separate TLS endpoints for control-plane PostgreSQL, Webhook PostgreSQL, and the dedicated Temporal PostgreSQL cluster;
- Temporal frontend `grpcs://` mTLS endpoint;
- private object-storage endpoint and provider adapter name;
- a path to the closed object-storage capability report and a path to the CI-generated signature-verification receipt; individual capability booleans are not accepted as infrastructure inputs;
- unique workload identity/service-account IDs for all three processes;
- external secret references, encryption key references, backup/PITR policy IDs, and network-policy namespace labels.

`main.tf` validates the contract with `terraform_data` preconditions:

~~~hcl
terraform {
  required_version = "= 1.9.1"
}

locals {
  required_object_capabilities = toset([
    "immutable_versions",
    "sha256_checksums",
    "worm_retention",
    "legal_hold",
    "multipart",
    "quarantine_isolation",
    "replication_evidence",
    "deletion_receipts"
  ])
  object_capability_report = jsondecode(
    file(var.object_store_capability_report_path)
  )
  object_capability_receipt = jsondecode(
    file(var.object_store_capability_verification_receipt_path)
  )
  object_capability_report_digest = format(
    "sha256:%s",
    filesha256(var.object_store_capability_report_path)
  )
}

resource "terraform_data" "foundation_contract" {
  input = {
    control_postgres_endpoint = var.control_postgres_tls_endpoint
    webhook_postgres_endpoint = var.webhook_postgres_tls_endpoint
    temporal_postgres_endpoint = var.temporal_postgres_tls_endpoint
    temporal_endpoint = var.temporal_mtls_endpoint
    object_store_endpoint = var.object_store_endpoint
    object_store_adapter = var.object_store_adapter
    object_store_capability_report_digest = local.object_capability_report_digest
  }

  lifecycle {
    precondition {
      condition = alltrue([
        startswith(var.control_postgres_tls_endpoint, "postgresql://"),
        startswith(var.webhook_postgres_tls_endpoint, "postgresql://"),
        startswith(var.temporal_postgres_tls_endpoint, "postgresql://")
      ])
      error_message = "All PostgreSQL endpoints must use the approved TLS configuration."
    }
    precondition {
      condition = length(distinct([
        var.control_postgres_tls_endpoint,
        var.webhook_postgres_tls_endpoint,
        var.temporal_postgres_tls_endpoint
      ])) == 3
      error_message = "Control, webhook, and Temporal production databases must be isolated endpoints."
    }
    precondition {
      condition = startswith(var.temporal_mtls_endpoint, "grpcs://")
      error_message = "Temporal frontend must use mTLS."
    }
    precondition {
      condition = (
        local.object_capability_report.environment_class == "production" &&
        local.object_capability_report.production_eligible == true &&
        timecmp(timestamp(), local.object_capability_report.expires_at) < 0 &&
        alltrue([
          for name in local.required_object_capabilities :
          local.object_capability_report.capabilities[name].status == "SUPPORTED_AND_TESTED" &&
          length(local.object_capability_report.capabilities[name].evidence) > 0
        ]) &&
        local.object_capability_receipt.result == "VERIFIED" &&
        local.object_capability_receipt.report_digest == local.object_capability_report_digest &&
        local.object_capability_receipt.adapter_artifact_digest ==
          local.object_capability_report.adapter_artifact_digest
      )
      error_message = "The selected object-store adapter has not proved every production capability."
    }
    precondition {
      condition = length(distinct(var.workload_identity_ids)) == 3
      error_message = "API, worker, and Webhook Edge require distinct workload identities."
    }
  }
}
~~~

Before OpenTofu runs, `deployment-contract.ps1` validates the report against the Task 13 schema, canonicalizes it with JCS, checks time bounds and exact adapter artifact digest, verifies the capability authority's DSSE signature against the environment trust policy, and verifies every immutable evidence reference. Only that verifier emits the short-lived receipt consumed above; a caller-supplied `VERIFIED` JSON file, local profile, expired report, missing capability, changed report byte, or mismatched adapter digest fails a negative fixture. The receipt and report digests are retained with the deployment evidence.

Do not declare any additional cache or coordination database. PostgreSQL lease/fence migrations and their backup/PITR contract are mandatory outputs. Mark secret values sensitive; the module accepts secret references, not credential contents.

- [ ] **Step 5: Configure GitOps without weakening supply-chain gates**

`infra/argocd/accord.yaml` uses a dedicated AppProject, destination namespace allowlist, sync windows, Server-Side Apply, prune/self-heal, and revision history. Production image values are digest pinned and updated only by the signed promotion workflow from Task 16. Argo CD may read deployment Git metadata but receives no customer source credentials, database owner credential, signing key, or runtime secret value.

Add a policy test that rejects a production `targetRevision` or values reference not protected by the organization's reviewed GitOps promotion rule. Health checks wait for Deployments and PDBs; failed policy/pre-sync migrations stop rollout.

- [ ] **Step 6: Run deployment and infrastructure contract checks**

~~~bash
pwsh -NoProfile -File tests/integration/deployment-contract.ps1
tofu -chdir=infra/opentofu/modules/accord-foundation-contract init -backend=false
tofu -chdir=infra/opentofu/modules/accord-foundation-contract validate
tofu -chdir=infra/opentofu/modules/accord-foundation-contract test
~~~

Expected: Helm, JSON schema, Kubeconform, Conftest, OpenTofu validation/tests, and role/credential/network assertions pass. Negative fixtures for shared identity, mutable image, broad network, wrong database role, missing object capability, plaintext secret, and control-plane source credential all fail for the intended reason.

- [ ] **Step 7: Commit deployment and policy contracts**

~~~bash
git add infra/helm/accord infra/policy/accord.rego infra/opentofu/modules/accord-foundation-contract infra/argocd/accord.yaml tests/integration/deployment-contract.ps1
git commit -m "build: enforce platform deployment isolation"
~~~

### Task 16: Enforce Dependency Locks, SBOMs, Image Provenance, And Signatures In CI

**Files:**
- Create: `apps/control-plane/api/Dockerfile`
- Create: `apps/control-plane/worker/Dockerfile`
- Modify: `apps/control-plane/worker/build.gradle`
- Create: `apps/control-plane/worker/src/probe/java/com/inforvans/accord/controlplane/worker/probe/WorkerProbe.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/probe/WorkerProbeTest.java`
- Create: `apps/webhook-edge/Dockerfile`
- Create: `.dockerignore`
- Modify: `.gitignore`
- Create: `scripts/ci/verify.ps1`
- Create: `scripts/ci/lock-base-images.ps1`
- Create: `scripts/ci/lock-local-images.ps1`
- Create: `scripts/ci/verify-local-images.ps1`
- Create: `scripts/ci/render-runtime-dockerfiles.ps1`
- Create: `scripts/ci/seed-gradle-cache.ps1`
- Create: `scripts/ci/package-accordctl.ps1`
- Create: `tests/bootstrap/verify-supply-chain.ps1`
- Create: `.github/workflows/verify.yml`
- Create: `.github/workflows/release-images.yml`
- Create: `.github/workflows/release-accordctl.yml`
- Create: `renovate.json`
- Generate: `infra/local/images.lock.json`
- Generate: `infra/images/base-images.lock.json`

- [ ] **Step 1: Write the failing supply-chain policy test**

`verify-supply-chain.ps1` must parse, not substring-match, the workflow YAML and image-lock JSON. It fails unless:

- all Java Dockerfiles, Java worker-probe source/test/JAR task, verification scripts, three workflows, and both lock files exist;
- every Dockerfile `ARG` used by `FROM` has a digest-pinned default, every effective `FROM` resolves to a real `@sha256:<64 hex>` reference present in `base-images.lock.json`, and the approved runtime exposes Java at `/usr/bin/java`;
- release builds request maximal provenance and SBOM, scan the pushed digest, sign by digest, attest SBOM/provenance, and verify both before promotion;
- GitHub Actions use full commit SHAs and minimum permissions; release jobs use protected environments and OIDC, not stored signing keys;
- Gradle strict verification/locks, pnpm frozen lock, uv locked sync, Buf compatibility, Java tests, Python tests/type/lint, deployment policy, image lock, and no-diff checks all run;
- `accordctl` is built/tested as a Picocli modular application and packaged with a host-specific `jlink` runtime on Windows, Linux, and macOS;
- no obsolete runtime source/module/command, extra coordination database, or mutable production image reference occurs.

Expected red result: missing delivery files are listed before any build starts.

- [ ] **Step 2: Create reproducible Java runtime images**

`lock-base-images.ps1` resolves the approved Java 21 build image and shell-free Java 21 runtime image through the authenticated corporate mirror. `base-images.lock.json` records a schema version and, for each purpose, the canonical registry/repository, reviewed tag, manifest-list digest, per-platform digests for `linux/amd64` and `linux/arm64`, retrieval time, upstream license, end-of-support date, and runtime Java path. The command verifies registry TLS, the manifest bytes/digest, both required platforms, Java 21, non-root execution, and `/usr/bin/java`; it rejects a mutable/tag-only result. Ordinary CI reads this lock and never rewrites it.

`render-runtime-dockerfiles.ps1` is the only writer for the three committed Dockerfiles. It parses the lock structurally, writes `ARG BUILD_IMAGE=` and `ARG RUNTIME_IMAGE=` followed immediately by the corresponding canonical locked references, then writes `FROM ${BUILD_IMAGE}` and `FROM ${RUNTIME_IMAGE}`. Generation fails unless each value is an exact registry/repository reference followed by `@sha256:` and 64 lowercase hexadecimal characters. `verify-supply-chain.ps1` parses every emitted `ARG`/`FROM`, recomputes the effective reference, and requires exact equality with the lock; a missing default, unexpanded token, tag, unknown override, or digest mismatch fails. CI may override an ARG only with another exact reference already present for the same purpose/platform in the reviewed lock.

`seed-gradle-cache.ps1` creates a fresh task-specific `GRADLE_USER_HOME`, resolves the exact locked build, plugin, annotation-processor, test, jOOQ, and `jlink` configurations through authenticated corporate mirrors with strict dependency verification, then writes a deterministic SHA-256 manifest for every cached file. It must not copy credentials, Gradle daemon state, build outputs, init scripts, or repository source. CI publishes the cache as a content-addressed artifact tied to the Gradle wrapper digest, Java vendor/version, dependency locks, and verification-metadata digest. The image-build job restores it into `.ci-cache/gradle`, verifies the manifest before use, and supplies no repository/network credential to BuildKit.

Each generated build stage copies that verified cache to `/opt/accord/gradle-home`, sets `GRADLE_USER_HOME` there, copies the locked build inputs and source, and executes the owning `bootJar` target with both `RUN --network=none` and Gradle `--offline --dependency-verification=strict`. The whole `buildx` build also uses the no-network entitlement after base manifests and the cache artifact have been fetched and verified. This makes `--offline` executable in an otherwise empty build image rather than an assertion with no dependency source. `.dockerignore` denies everything secret or irrelevant, ignores `.ci-cache/**` by default, and re-allows only `.ci-cache/gradle/**` plus its manifest; `.gitignore` prevents the cache from being committed.

Use a Java 21 distroless runtime with no shell/package manager. API uses UID 10001, worker 10002, and Webhook Edge 10003. All entry points use `/usr/bin/java`, immutable JVM flags, and one application JAR. The worker additionally copies `worker-probe.jar` to `/opt/accord/bin/worker-probe.jar`; no script or native binary is implied. It writes only to the bounded `/tmp` volume. Webhook Edge is built from its independent Gradle project and contains no control-plane domain modules. Reproducibility tests build each platform image twice with the same `SOURCE_DATE_EPOCH`, cache manifest, and base digests, then compare application-layer and final manifest digests.

Add a JDK-only `probe` source set and reproducible thin JAR to `apps/control-plane/worker/build.gradle`:

~~~groovy
sourceSets {
    probe {
        java.srcDir 'src/probe/java'
    }
    test {
        compileClasspath += sourceSets.probe.output
        runtimeClasspath += sourceSets.probe.output
    }
}

tasks.register('workerProbeJar', Jar) {
    dependsOn tasks.named('compileProbeJava')
    archiveFileName = 'worker-probe.jar'
    destinationDirectory = layout.buildDirectory.dir('probe')
    from sourceSets.probe.output
    preserveFileTimestamps = false
    reproducibleFileOrder = true
    manifest {
        attributes('Main-Class':
            'com.inforvans.accord.controlplane.worker.probe.WorkerProbe')
    }
}

tasks.named('test') {
    dependsOn tasks.named('compileProbeJava')
}
~~~

`WorkerProbe.java` has no Spring or third-party dependency. `main` accepts exactly `ready|live` and an integer maximum age from 5 through 300 seconds; any other input exits 64. It resolves only `/tmp/accord/worker-ready` or `/tmp/accord/worker-live`, reads directory and file POSIX attributes with `NOFOLLOW_LINKS`, and exits 2 unless the directory is owned by the current process user with mode `0700`, the state file is an owner-matching regular file with mode `0600`, and its ASCII content is exactly one 1-12 digit UTC epoch-second plus LF and at most 32 bytes. It rejects a timestamp more than five seconds in the future or older than the caller's maximum. Expected absence, parse/type/owner/mode/age failures, and I/O errors emit only a fixed normalized reason code and never a path, file content, user-controlled exception message, or stack trace. Success exits 0.

~~~java
package com.inforvans.accord.controlplane.worker.probe;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public final class WorkerProbe {
    private static final Set<PosixFilePermission> DIRECTORY_MODE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_MODE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );

    private WorkerProbe() {}

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC(), Path.of("/tmp/accord")));
    }

    static int run(String[] args, Clock clock, Path root) {
        if (args.length != 2) {
            return usage();
        }
        String fileName = switch (args[0]) {
            case "ready" -> "worker-ready";
            case "live" -> "worker-live";
            default -> null;
        };
        int maximumAge;
        try {
            maximumAge = Integer.parseInt(args[1]);
        } catch (NumberFormatException error) {
            return usage();
        }
        if (fileName == null || maximumAge < 5 || maximumAge > 300) {
            return usage();
        }

        try {
            PosixFileAttributes directory = Files.readAttributes(
                root, PosixFileAttributes.class, NOFOLLOW_LINKS);
            if (!directory.isDirectory()
                    || !directory.permissions().equals(DIRECTORY_MODE)) {
                return unhealthy("directory");
            }
            int processUid = ((Number) Files.getAttribute(
                Path.of("/proc/self"), "unix:uid", NOFOLLOW_LINKS)).intValue();
            int directoryUid = ((Number) Files.getAttribute(
                root, "unix:uid", NOFOLLOW_LINKS)).intValue();
            if (directoryUid != processUid) {
                return unhealthy("directory-owner");
            }

            Path statePath = root.resolve(fileName);
            PosixFileAttributes state = Files.readAttributes(
                statePath, PosixFileAttributes.class, NOFOLLOW_LINKS);
            int stateUid = ((Number) Files.getAttribute(
                statePath, "unix:uid", NOFOLLOW_LINKS)).intValue();
            if (!state.isRegularFile()
                    || stateUid != processUid
                    || !state.permissions().equals(FILE_MODE)) {
                return unhealthy("state-file");
            }

            byte[] content = Files.readAllBytes(statePath);
            if (content.length == 0 || content.length > 32) {
                return unhealthy("state-size");
            }
            String timestamp = new String(content, StandardCharsets.US_ASCII);
            if (!timestamp.matches("[0-9]{1,12}\\n")) {
                return unhealthy("state-format");
            }
            long epochSecond = Long.parseLong(
                timestamp.substring(0, timestamp.length() - 1));
            long age = Duration.between(
                Instant.ofEpochSecond(epochSecond), clock.instant()).getSeconds();
            if (age < -5 || age > maximumAge) {
                return unhealthy("state-age");
            }
            return 0;
        } catch (IOException | RuntimeException error) {
            return unhealthy("state-read");
        }
    }

    private static int usage() {
        System.err.println("worker-probe:usage");
        return 64;
    }

    private static int unhealthy(String reason) {
        System.err.println("worker-probe:" + reason);
        return 2;
    }
}
~~~

`WorkerProbeTest` calls the package-visible `run(args, clock, root)` seam with a temporary POSIX directory and proves success, usage error, missing/expired/future/malformed/oversized files, symlink substitution, wrong mode, wrong owner when supported, and both ready/live selection. A container integration test runs the built JAR as UID 10002 in the selected runtime image, removes shell and host Java from consideration, and requires the Helm command to complete in under two seconds.

- [ ] **Step 3: Lock and verify every third-party image**

`lock-local-images.ps1` resolves each Compose image to one canonical repository digest and writes schema-versioned, deterministically sorted JSON. `verify-local-images.ps1` proves exact set equality between Compose and lock entries, pulls by digest, compares manifest/platform digests, and rejects tag-only, duplicate, unknown-registry, or extra lock entries.

The same rules apply to Dockerfile base images. Updating either lock is a separate reviewed maintenance command; ordinary verification never rewrites it.

- [ ] **Step 4: Create one read-only verification entry point**

`scripts/ci/verify.ps1` runs in this order and stops on the first failure:

~~~powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

pwsh -NoProfile -File tests/bootstrap/verify-design-baseline.ps1
pwsh -NoProfile -File tests/bootstrap/verify-workspace.ps1
pwsh -NoProfile -File tests/bootstrap/verify-supply-chain.ps1

$gradleCache = $env:ACCORD_VERIFIED_GRADLE_CACHE
if ([string]::IsNullOrWhiteSpace($gradleCache)) {
  throw 'ACCORD_VERIFIED_GRADLE_CACHE must point to the restored CI cache artifact'
}
pwsh -NoProfile -File scripts/ci/seed-gradle-cache.ps1 -Mode Verify -CacheDirectory $gradleCache
$env:GRADLE_USER_HOME = (Resolve-Path -LiteralPath $gradleCache).Path

corepack enable
pnpm install --frozen-lockfile
uv sync --locked --all-packages

./gradlew test --no-daemon --offline --dependency-verification=strict
./gradlew :cmd:accordctl:jlink :cmd:accordctl:jlinkZip --no-daemon --offline --dependency-verification=strict
./gradlew :apps:control-plane:worker:workerProbeJar --no-daemon --offline --dependency-verification=strict
pnpm contracts:test
pnpm contracts:lint
buf lint
buf build -o build/contracts.binpb
buf breaking --against '.git#ref=origin/main'

uv run --package accord-agent-runtime ruff check apps/agent-runtime
uv run --package accord-agent-runtime mypy apps/agent-runtime/src
uv run --package accord-agent-runtime pytest apps/agent-runtime

pwsh -NoProfile -File tests/integration/deployment-contract.ps1
pwsh -NoProfile -File scripts/ci/verify-local-images.ps1
./gradlew cyclonedxBom --no-daemon --offline --dependency-verification=strict
syft dir:. -o spdx-json=build/sbom/source.spdx.json

git diff --exit-code -- gradle.lockfile '**/gradle.lockfile' gradle/verification-metadata.xml pnpm-lock.yaml uv.lock infra/local/images.lock.json infra/images/base-images.lock.json
~~~

Before the offline phase, a dedicated cache-seeding job invokes `seed-gradle-cache.ps1 -Mode Seed` and resolves only artifacts allowed by dependency verification metadata through authenticated corporate mirrors. The verification job starts with an empty checkout, restores that content-addressed artifact to a job-local writable directory, verifies every byte before assigning `GRADLE_USER_HOME`, disables public fallback, and proves no lock or verification metadata changes. It never silently falls back to a developer cache. `origin/main` is fetched explicitly so Buf does not silently skip compatibility.

The script also runs static forbidden-runtime scans, Java compilation with `-Werror`, ArchUnit, jqwik, Testcontainers, security-negative, fault-injection, Helm/Kubeconform/Conftest, OpenTofu tests, SBOM schema validation, license policy, secret scan, and critical/high vulnerability gates.

- [ ] **Step 5: Package the self-contained Picocli CLI on each operating system**

`package-accordctl.ps1`:

1. runs Picocli public-contract tests and `jdeps`/module checks;
2. builds `jlink` and `jlinkZip` on the host OS;
3. unpacks into a clean temporary directory with host Java removed from `PATH`;
4. proves `accordctl --help`, `accordctl --version`, invalid-command exit code, UTF-8 output, and a signed-fixture command;
5. produces deterministic ZIP/TAR archive, SHA-256 checksum, CycloneDX SBOM, and provenance subject;
6. scans the bundled runtime and dependencies;
7. signs/attests the immutable archive digest.

`release-accordctl.yml` uses a Windows, Ubuntu, and macOS matrix. Platform archives cannot be substituted for one another. Windows Authenticode and macOS notarization identities are separate protected-environment integrations; absence blocks GA publication for that platform. The generic Cosign signature and provenance remain required on every archive.

- [ ] **Step 6: Add least-privilege verification and release workflows**

`verify.yml` uses pinned action commit SHAs, a 45-minute timeout, read-only repository permissions, corporate mirrors, clean caches, and `scripts/ci/verify.ps1`. It uploads test/SBOM reports even on failure but never uploads secrets or local database volumes.

`release-images.yml`:

1. triggers only from protected semantic-version tags whose commit is reachable from protected `main`;
2. re-runs verification and checks that the tag/version/Gradle artifacts agree;
3. builds API, worker, and Webhook Edge independently for `linux/amd64` and `linux/arm64`;
4. pushes by immutable digest with `--provenance=mode=max --sbom=true`;
5. runs Trivy and policy checks against the pushed digest, failing on fixable high/critical findings;
6. generates CycloneDX and SPDX SBOMs, license reports, and SLSA/BuildKit provenance;
7. uses keyless Cosign signing through GitHub OIDC and a protected release environment;
8. immediately verifies certificate identity/issuer, image signature, provenance predicate/type/subject, and SBOM attestation;
9. publishes only verified digests to the GitOps promotion input.

No release step authenticates with a long-lived registry or signing credential. Artifacts and attestations have defined retention and incident revocation procedures.

- [ ] **Step 7: Configure controlled dependency updates**

`renovate.json` pins all managers and Docker digests, groups Java/Gradle, browser, Python, container, and infrastructure updates separately, enforces a seven-day minimum release age except approved security emergencies, and requires lock/verification metadata regeneration in the update PR. There is no obsolete runtime manager.

Major updates, Spring/Temporal/PostgreSQL compatibility changes, base-image changes, and cryptographic libraries require named code owners and full integration tests. Automerge is limited to policy-approved patch updates after all required checks.

- [ ] **Step 8: Verify and commit the delivery pipeline**

~~~bash
pwsh -NoProfile -File scripts/ci/render-runtime-dockerfiles.ps1 -Mode Verify
pwsh -NoProfile -File tests/bootstrap/verify-supply-chain.ps1
pwsh -NoProfile -File scripts/ci/verify.ps1
docker buildx build --network=none -f apps/control-plane/api/Dockerfile --load -t accord-control-api:test .
docker buildx build --network=none -f apps/control-plane/worker/Dockerfile --load -t accord-control-worker:test .
docker buildx build --network=none -f apps/webhook-edge/Dockerfile --load -t accord-webhook-edge:test .
pwsh -NoProfile -File scripts/ci/package-accordctl.ps1
~~~

Expected: policy and full verification pass without lock drift; all images run as their fixed non-root users with read-only-root compatibility; Webhook Edge has no control-plane classes; the worker probe works without a shell in the Java container; each host CLI archive runs without a host JDK; scans, SBOMs, provenance, signatures, and verification all bind the exact artifact digest.

~~~bash
git add .dockerignore .gitignore apps/control-plane/api/Dockerfile apps/control-plane/worker/Dockerfile apps/control-plane/worker/build.gradle apps/control-plane/worker/src/probe apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/probe apps/webhook-edge/Dockerfile scripts/ci tests/bootstrap/verify-supply-chain.ps1 .github/workflows infra/local/images.lock.json infra/images/base-images.lock.json renovate.json
git commit -m "build: attest and sign locked platform artifacts"
~~~

### Task 17: Run The Foundation Acceptance Suite And Record The Architecture

**Files:**
- Create: `tests/integration/foundation-acceptance.ps1`
- Create: `tests/integration/verify-sensitive-logs.ps1`
- Create: `tests/architecture/verify-platform-foundation.ps1`
- Create: `docs/architecture/platform-foundation.md`

- [ ] **Step 1: Write the failing acceptance orchestrator**

`foundation-acceptance.ps1` runs named checks without shell-built command strings:

~~~powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$checks = @(
  @{ Name = 'architecture'; Command = @('pwsh','-NoProfile','-File','tests/architecture/verify-platform-foundation.ps1') },
  @{ Name = 'workspace'; Command = @('pwsh','-NoProfile','-File','tests/bootstrap/verify-workspace.ps1') },
  @{ Name = 'supply-chain'; Command = @('pwsh','-NoProfile','-File','tests/bootstrap/verify-supply-chain.ps1') },
  @{ Name = 'java'; Command = @('./gradlew','test','--offline','--dependency-verification=strict') },
  @{ Name = 'cli-image'; Command = @('./gradlew',':cmd:accordctl:jlink',':cmd:accordctl:jlinkZip','--offline','--dependency-verification=strict') },
  @{ Name = 'browser-contracts'; Command = @('pnpm','contracts:test') },
  @{ Name = 'browser-lint'; Command = @('pnpm','contracts:lint') },
  @{ Name = 'protobuf-lint'; Command = @('buf','lint') },
  @{ Name = 'protobuf-breaking'; Command = @('buf','breaking','--against','.git#ref=origin/main') },
  @{ Name = 'python-lint'; Command = @('uv','run','--package','accord-agent-runtime','ruff','check','apps/agent-runtime') },
  @{ Name = 'python-types'; Command = @('uv','run','--package','accord-agent-runtime','mypy','apps/agent-runtime/src') },
  @{ Name = 'python-tests'; Command = @('uv','run','--package','accord-agent-runtime','pytest','apps/agent-runtime') },
  @{ Name = 'deployment'; Command = @('pwsh','-NoProfile','-File','tests/integration/deployment-contract.ps1') },
  @{ Name = 'local'; Command = @('pwsh','-NoProfile','-File','tests/integration/local-foundation.ps1') },
  @{ Name = 'sensitive-logs'; Command = @('pwsh','-NoProfile','-File','tests/integration/verify-sensitive-logs.ps1') }
)
foreach ($check in $checks) {
  $command = $check.Command
  & $command[0] @($command[1..($command.Count - 1)])
  if ($LASTEXITCODE -ne 0) {
    throw "Foundation acceptance failed: $($check.Name)"
  }
}
Write-Output 'foundation-acceptance: PASS'
~~~

The runner assumes dependency caches were seeded and verified by Task 16. It never regenerates locks, verification metadata, source, schemas, or image manifests.

- [ ] **Step 2: Add architecture and sensitive-data assertions**

`verify-platform-foundation.ps1` checks both the approved requirement specification and architecture record are tracked, non-empty, and free of unfinished markers. It requires the record to state:

- Java 21/Spring Boot/Spring Modulith control plane and independent Java edge/security applications;
- Python 3.12 Agent Runtime, React/TypeScript browser, and Picocli/`jlink` CLI;
- PostgreSQL as the only durable business/coordination system, including tenant-work scheduling and generation/token/deadline fences;
- exact login/NOLOGIN role pairs, forced RLS, and sole approved signing-owner `BYPASSRLS` exception;
- verify-first Webhook Edge with no raw-body/source persistence;
- identifier-only Temporal histories with database reload on every activity attempt;
- Actuator probe paths for Java HTTP services and stateful exec probes for the worker;
- separate identities, credentials, databases, network policies, and artifacts;
- object-storage capability evidence and supply-chain verification.

The script also scans implementation directories and build/deployment configuration for forbidden old runtime files, plugins, dependencies, commands, module manifests, and additional coordination databases. Explicit prohibition strings in documentation tests are the only allowed matches.

`verify-sensitive-logs.ps1` collects local API, worker, Webhook Edge, Temporal, and collector logs without echoing matched values. It scans for named rules covering authorization, bearer/cookie/token values, webhook bodies, attachment text, source/diff/archive fields, prompts, secrets, and seeded sentinels. On failure it prints rule names and service names only, never the matched secret. The same sentinels are checked in exported traces and metric labels.

- [ ] **Step 3: Run the red gate and fix the owning task**

~~~bash
pwsh -NoProfile -File tests/integration/foundation-acceptance.ps1
~~~

Expected: FAIL at the first absent or incorrect artifact from Tasks 1-16. Do not skip or weaken that check; return to the owning task and make its focused test green.

- [ ] **Step 4: Record the implemented authority and process boundaries**

Create `docs/architecture/platform-foundation.md` with this normative content:

~~~markdown
# Accord Platform Foundation

## Authority And Process Boundaries

Java 21, Spring Boot 3.5.3, and Spring Modulith 1.4.1 implement the control-plane modules. `control-api` and `control-worker` share bounded domain artifacts but are separate processes with different entry points, identities, credentials, network policies, and database roles. Webhook Edge and every security boundary are independent Java applications and artifacts. The Agent Runtime is Python 3.12; the browser is React/TypeScript; `accordctl` is a Picocli application shipped with a platform-specific `jlink` runtime.

PostgreSQL 17.5 is the only durable business and coordination system. Aggregate CAS, idempotency results, outbox/inbox, tenant-work scheduling, leases, fencing, capabilities, and certification facts live in PostgreSQL. Process-local caches are disposable and never decide correctness. Temporal history contains stable identifiers and retry progress only; every activity reloads current authority and state from PostgreSQL.

## Database Sessions And Command Safety

`accord_migrator`, `accord_api`, `accord_worker`, `accord_webhook_owner`, and `accord_webhook_runtime` are NOLOGIN privilege/owner roles. Each login has exactly one set-only membership. `accord_api_login` connects and then runs `SET ROLE accord_api`; worker, migrator, and Webhook Edge follow their own one-to-one pairs. Runtime roles cannot set siblings or owners. `accord_signing_owner` is the sole approved Accord BYPASSRLS role, is NOLOGIN, and is reachable only from its migration identity.

Business tables use forced tenant RLS. The payload-free `reliability_tenant_work` directory is the explicit worker-only cross-tenant coordination exception: API can signal only its current transaction tenant through a fixed security-definer function. Worker first leases a tenant by owner, generation, opaque token, and deadline, then installs that tenant context before touching outbox/inbox rows. Message acknowledgement uses an independent generation/token/deadline fence.

An idempotency fingerprint binds method, normalized path/query, route/resource, canonical body digest, expected version, media types, API version, tenant, and actor. Claim commits before work; aggregate, domain/audit/outbox writes, and fenced completion commit together.

## Isolated Webhook Ingress

Webhook Edge resolves an opaque server-side binding, bounds the request, verifies HMAC over exact raw bytes in constant time, and parses only after verification. It checks immutable repository identity and persists only digest plus the closed normalized signal in its separate database. It never stores request bodies, headers, source, archives, diffs, or Git content credentials. Webhooks are hints; provider reconciliation establishes external facts.

## Operational Health And Telemetry

API and Webhook Edge expose `/actuator/health/readiness` and `/actuator/health/liveness`. The non-web worker uses scheduler/database/Temporal-aware exec probes. Telemetry uses a bounded allowlist and cannot record headers, bodies, tokens, attachment text, source material, prompts, raw URLs, or exception messages containing input. Collector failure does not make workloads unready.

## Deployment And Supply Chain

Every process uses a distinct ServiceAccount, secret, database role, network policy, and digest-pinned artifact. Workloads are non-root, read-only, resource bounded, spread across zones, disruption protected, and default-deny. The object-storage adapter must prove immutable versions, checksums, WORM, legal hold, multipart, quarantine, replication evidence, and deletion receipts before production use.

Locked Java, browser, and Python dependencies resolve through approved mirrors with strict verification. CI emits and validates SBOMs/provenance, scans artifacts, signs immutable digests, and publishes only verified digests. Each `accordctl` platform package includes its runtime and works without a host JDK.
~~~

- [ ] **Step 5: Run acceptance from a clean checkout**

In a new clean worktree descended from protected `main`:

~~~powershell
pwsh -NoProfile -File scripts/local-up.ps1
pwsh -NoProfile -File scripts/ci/verify.ps1
pwsh -NoProfile -File tests/integration/foundation-acceptance.ps1
git diff --exit-code
~~~

Expected: `foundation-acceptance: PASS`; no generated lock/source/configuration differs; all local services are healthy; PostgreSQL/RLS/fence, telemetry leak, deployment, object capability, CLI runtime, image, SBOM, provenance, and signature checks pass.

- [ ] **Step 6: Commit the architecture record and acceptance gate**

~~~bash
git add tests/integration/foundation-acceptance.ps1 tests/integration/verify-sensitive-logs.ps1 tests/architecture/verify-platform-foundation.ps1 docs/architecture/platform-foundation.md
git commit -m "docs: record verified platform foundation"
~~~

## Plan Self-Review

- [x] Tasks 1-17 remain continuous and map workspace, contracts, module/process boundaries, PostgreSQL reliability, HTTP/Temporal boundaries, isolated Webhook Edge, local integration, telemetry, deployment, supply chain, CLI packaging, and acceptance.
- [x] All implementation paths, code shapes, Gradle files, commands, images, and runtime dependencies use Java 21/Groovy DSL, Python 3.12, React/TypeScript, and PostgreSQL coordination.
- [x] Language consolidation does not consolidate authority: edge/security services remain independent deployables and the control plane never receives source access.
- [x] Public mutations use `Idempotency-Key`, quoted `If-Match`, exact durable replay, generation/token/deadline fencing, CAS, and RFC 7807.
- [x] Static scans, task/step numbering, fence balance, unfinished markers, repeated references, and architecture/runtime terms have been checked against this document.
