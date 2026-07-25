# Accord Identity, Tenancy, And Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build tenant-safe enterprise identity, resumable project setup, immutable repository and Git-user binding, side-aware authorization and delegation, dual-mode browser/API authentication, fresh-auth and anti-replay controls, isolated DSSE signing, tamper-evident audit, legally controlled deletion, and the complete generated-client HTTP surface for Accord.

**Architecture:** Identity, authorization, and audit are bounded Java 21 Spring Modulith modules in the control plane, with PostgreSQL row-level security as defense in depth and transaction-local tenant context on every access. A single cumulative OpenAPI contract exposes those modules through controllers that accept only `VerifiedRequestIdentity`; browser sessions and OIDC bearer authentication produce the same server-derived tenant/actor identity, while cookie-authenticated unsafe requests additionally pass the shared Origin, Fetch Metadata, and session-bound CSRF guard. A separate Java 21 Spring Boot Signing Service owns its trust store, purpose-bound KMS keys, mTLS client policy, DSSE/JCS operations, and one-time nonces; it has an independent workload identity, database role, credential set, network policy, and deployable artifact, and shares only generated protobuf/JSON contracts plus bounded Java libraries with the control plane. Audit events are append-only hash chains whose Merkle roots are signed and anchored in S3 Object Lock, while project setup, retention, legal hold, and deletion are explicit versioned workflows.

**Tech Stack:** Java 21, Gradle 8.14.3 Groovy DSL, Spring Boot 3.5.3, Spring Modulith 1.4.1, jOOQ 3.19.24, Flyway 11.8.2, PostgreSQL 17.5 with forced RLS, protobuf/gRPC with mTLS, OIDC, SAML 2.0, SCIM 2.0, RFC 8785 JCS, DSSE, SHA-256, cloud KMS/HSM adapters, OpenTelemetry, S3 Object Lock, JUnit 5, AssertJ, jqwik, Testcontainers, and Toxiproxy.

---

## Scope And Security Invariants

- Implement sections 14, 16.8, and 17 of `requirements-agent-platform-design.md` on top of `2026-07-24-accord-platform-foundation-plan.md`. Do not add delivery, requirement, or acceptance business workflows except for narrow authorization fixtures that prove these controls.
- Every row, object key, cache key, index document, queue message, and log context has `tenant_id`. Repository-scoped resources also have the provider's immutable repository ID. Tenant-level and pre-binding resources never receive a fabricated repository ID.
- The same provider repository cannot be bound to two tenants. Display owner/name and URL may change without changing immutable identity. Transfer, unbind, and rebind require fresh authentication, reconciliation, and a new trust establishment.
- Human identity is an enterprise subject mapped to a stable natural-person record. Git responsibility uses immutable provider user IDs; commit name/email alone is never evidence. Workloads are separate principals with one registered purpose.
- Effective authorization is the intersection of active membership, immutable repository match, active role, allowed action, scope containment, valid delegation, separation of duties, current binding version, and required reauthentication.
- Tenant/Project Administrator roles do not imply business or development approval. Final strict-mode business and development confirmation must be performed by different natural people. Machines never perform human confirmation or acceptance.
- Delegation never crosses tenant or side and never widens roles, actions, scope, or expiry. An actor exercising delegated authority cannot create another delegation.
- The Signing Service has no control-plane domain dependency. KMS keys, credentials, trust records, domains, and nonces are purpose separated. A valid historical signature does not imply current authorization.
- Audit history is append-only and viewer-filtered. Legal hold overrides deletion. Completed deletion removes online data, objects, caches, indexes, and tenant key material while retaining a minimal signed deletion proof and the immutable audit evidence required by policy.

## Target File Map

```text
apps/control-plane/modules/
  identity/                         # tenants, projects, enterprise/Git/workload identity
  authorization/                    # RBAC, sides, principals, delegation, fresh auth, visibility
  audit/                            # append-only events, hash chains, Merkle batches, retention workflow
security-services/signing-service/  # isolated Java gRPC service, trust store, KMS adapters, nonce consumption
contracts/protobuf/accord/signing/v1/
contracts/openapi/accord-control-api.yaml
contracts/json-schema/
contracts/dsse-payloads/
contracts/golden-fixtures/signing/
database/control-plane/migrations/
database/signing-service/migrations/
infra/helm/accord/charts/signing-service/
tests/security/
packages/api-client/                   # cumulative generated browser/API client
```

### Task 1: Establish Identity, Authorization, Audit, And Signing Boundaries

**Files:**
- Create: `apps/control-plane/modules/identity/build.gradle`
- Create: `apps/control-plane/modules/authorization/build.gradle`
- Create: `apps/control-plane/modules/audit/build.gradle`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/package-info.java`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/package-info.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/package-info.java`
- Create: `tests/architecture/IdentityTrustBoundaryTest.java`
- Create: `tests/architecture/verify-signing-boundary.ps1`
- Modify: `apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java`
- Modify: `settings.gradle`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `tests/integration/build.gradle`
- Modify: `tests/api/build.gradle`
- Modify: `tests/security-negative/build.gradle`
- Modify: `tests/state-machine/build.gradle`
- Modify: `tests/fault-injection/build.gradle`
- Create: `tests/architecture/verify-control-plane-fixtures.ps1`
- Modify: `security-services/signing-service/build.gradle`

- [ ] **Step 1: Write failing boundary tests**

Create `IdentityTrustBoundaryTest.java`:

```java
package com.inforvans.accord.architecture

import com.inforvans.accord.ControlApiApplication
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class IdentityTrustBoundaryTest {
    private final var classes = new ClassFileImporter().importPackages("com.inforvans.accord");

    @Test
    void identityModulesAreAcyclicAndExposeDeclaredApisOnly() {
        var modules = ApplicationModules.of(ControlApiApplication.class);
        for (var expected : java.util.Set.of("identity", "authorization", "audit")) {
            assertThat(modules.getModuleByName(expected))
                .as("Module %s was not discovered", expected)
                .isPresent();
        }
        modules.verify();
    }

    @Test
    void controlPlaneHasNoSigningImplementationDependency() {
        noClasses().that().resideInAPackage("com.inforvans.accord..")
            .should().dependOnClassesThat().resideInAnyPackage("..signingservice..", "..kms..")
            .allowEmptyShould(true)
            .check(classes);
    }
}
```

In `ModuleBoundaryTest.java`, replace `requiredModules` with the cumulative discovery contract:

```java
private static final Set<String> REQUIRED_MODULES = Set.of(
    "platformkernel", "reliability", "identity", "authorization", "audit"
);
```

Create `verify-signing-boundary.ps1`:

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$dependencies = & .\gradlew.bat ':security-services:signing-service:dependencies' '--configuration' 'runtimeClasspath' 2>&1
if ($LASTEXITCODE -ne 0) { throw ($dependencies -join [Environment]::NewLine) }
if (($dependencies -join "`n") -match ':apps:control-plane:modules:|:apps:control-plane:api|:apps:control-plane:worker') {
  throw 'Signing Service imports control-plane implementation code'
}
Write-Output 'signing-boundary: PASS'
```

- [ ] **Step 2: Run the tests and verify the modules are absent**

Run:

```bash
./gradlew :apps:control-plane:api:test --tests '*IdentityTrustBoundaryTest'
pwsh -NoProfile -File tests/architecture/verify-signing-boundary.ps1
```

Expected: FAIL. The Gradle test reports that the modules are not in settings; the signing-service dependency check reports that its Java subproject is not configured yet.

- [ ] **Step 3: Add only the permitted module dependencies**

Add these projects to `settings.gradle`:

```groovy
include(
    ":apps:control-plane:modules:identity",
    ":apps:control-plane:modules:authorization",
    ":apps:control-plane:modules:audit"
)
```

Create `identity/build.gradle`:

```groovy
plugins { id 'java-library' }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    implementation(project(":apps:control-plane:modules:platform-kernel"))
    implementation(project(":apps:control-plane:modules:reliability"))
    implementation(project(":database:control-plane"))
    implementation(libs.spring.boot.jooq)
    implementation(libs.postgresql)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
tasks.withType(Test).configureEach { useJUnitPlatform() }
```

Create `authorization/build.gradle`:

```groovy
plugins { id 'java-library' }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    implementation(project(":apps:control-plane:modules:platform-kernel"))
    implementation(project(":apps:control-plane:modules:identity"))
    implementation(project(":apps:control-plane:modules:reliability"))
    implementation(libs.spring.boot.jooq)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
tasks.withType(Test).configureEach { useJUnitPlatform() }
```

Create `audit/build.gradle`:

```groovy
plugins { id 'java-library' }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    implementation(project(":apps:control-plane:modules:platform-kernel"))
    implementation(project(":apps:control-plane:modules:reliability"))
    implementation(project(":database:control-plane"))
    implementation(libs.spring.boot.jooq)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
tasks.withType(Test).configureEach { useJUnitPlatform() }
```

Add all three projects as `implementation` dependencies of API. Add `authorization` and `audit` to worker, but do not add a dependency from any control-plane module to `security-services/signing-service`.

- [ ] **Step 4: Declare Spring Modulith access rules**

Create the three package declarations:

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Identity",
    allowedDependencies = {"platformkernel", "reliability"}
)
package com.inforvans.accord.identity;
```

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Authorization",
    allowedDependencies = {"platformkernel", "identity", "reliability"}
)
package com.inforvans.accord.authorization;
```

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Audit",
    allowedDependencies = {"platformkernel", "reliability"}
)
package com.inforvans.accord.audit;
```

Keep `security-services/signing-service` as the independently bootable Gradle/Spring Boot subproject established by Foundation. It may depend on generated protobuf classes and bounded `libs/java/**` projects only; it must not depend on any control-plane application or domain module.

- [ ] **Step 5: Verify boundaries**

Run:

```bash
./gradlew :apps:control-plane:api:test --tests '*IdentityTrustBoundaryTest'
./gradlew :security-services:signing-service:test
pwsh -NoProfile -File tests/architecture/verify-signing-boundary.ps1
```

Expected: Modulith and ArchUnit pass; the independent Signing Service compiles and its tests pass; `signing-boundary: PASS`; the runtime classpath contains protobuf, gRPC, database, KMS, and bounded Java libraries only, never a control-plane project.

- [ ] **Step 6: Commit bounded identity foundations**

```bash
git add settings.gradle apps/control-plane security-services/signing-service/build.gradle tests/architecture apps/control-plane/api/src/test/java/com/inforvans/accord/ModuleBoundaryTest.java tests/integration/build.gradle tests/api/build.gradle tests/security-negative/build.gradle tests/state-machine/build.gradle tests/fault-injection/build.gradle
git commit -m "feat: establish identity and signing boundaries"
```

### Task 2: Model Tenant, Project, And Immutable Repository Identity

**Files:**
- Create: `database/control-plane/migrations/V010__tenant_project_repository.sql`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/TenantId.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/ScopeIdentity.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/RepositoryBinding.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/RepositoryBindingExceptions.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/RepositoryBindingRepository.java`
- Create: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/IdentityPostgreSqlTest.java`
- Create: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/RepositoryBindingRepositoryTest.java`

- [ ] **Step 1: Write failing immutable-identity and global-binding tests**

Create `RepositoryBindingRepositoryTest.java`:

```java
package com.inforvans.accord.identity

import java.time.OffsetDateTime
import java.util.UUID
import org.junit.jupiter.api.Test

import static com.inforvans.accord.identity.RepositoryBindingExceptions.ProjectAlreadyHasLiveRepository;
import static com.inforvans.accord.identity.RepositoryBindingExceptions.RepositoryAlreadyBound;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryBindingRepositoryTest extends IdentityPostgreSqlTest {
    private final TenantId tenantA = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private final TenantId tenantB = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000002"));
    private final UUID projectA = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private final UUID projectB = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void repositoryRenamePreservesImmutableProviderIdentity() {
        seedTenantAndProject(tenantA.value(), projectA);
        var repository = new RepositoryBindingRepository();
        var binding = repository.bind(dsl, tenantA, projectA, GitProvider.GITHUB, "77831", "acme/demo");
        repository.rename(dsl, tenantA, binding.bindingId(), "acme/renamed", binding.version());
        var renamed = repository.get(dsl, tenantA, binding.bindingId());
        assertThat(renamed.immutableRepositoryId()).isEqualTo("77831");
        assertThat(renamed.displayName()).isEqualTo("acme/renamed");
        assertThat(renamed.version()).isEqualTo(2L);
    }

    @Test
    void oneProviderRepositoryCannotBindToTwoTenants() {
        seedTenantAndProject(tenantA.value(), projectA);
        seedTenantAndProject(tenantB.value(), projectB);
        var repository = new RepositoryBindingRepository();
        repository.bind(dsl, tenantA, projectA, GitProvider.GITHUB, "77831", "acme/demo");
        assertThatThrownBy(() -> repository.bind(dsl, tenantB, projectB, GitProvider.GITHUB, "77831", "acme/demo"))
            .isInstanceOf(RepositoryAlreadyBound.class);
    }

    @Test
    void projectConflictIsDistinctAndUnboundRepositoryCanEstablishNewTrust() {
        seedTenantAndProject(tenantA.value(), projectA);
        seedTenantAndProject(tenantB.value(), projectB);
        var repository = new RepositoryBindingRepository();
        var old = repository.bind(dsl, tenantA, projectA, GitProvider.GITHUB, "77831", "acme/demo");
        assertThatThrownBy(() -> repository.bind(dsl, tenantA, projectA, GitProvider.GITHUB, "99117", "acme/other"))
            .isInstanceOf(ProjectAlreadyHasLiveRepository.class);

        repository.markUnbound(dsl, tenantA, old.bindingId(), old.version(), OffsetDateTime.parse("2026-07-24T10:00:00Z"));
        var rebound = repository.bind(dsl, tenantB, projectB, GitProvider.GITHUB, "77831", "new-owner/demo");
        assertThat(rebound.immutableRepositoryId()).isEqualTo("77831");
        assertThat(rebound.version()).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: Run the test and verify the schema and types are missing**

Run: `./gradlew :apps:control-plane:modules:identity:test --tests '*RepositoryBindingRepositoryTest'`

Expected: FAIL because `TenantId`, `RepositoryBindingRepository`, and migration version 010 are absent.

- [ ] **Step 3: Add tenant, project, and binding tables with correct keys**

Create `V010__tenant_project_repository.sql`:

```sql
CREATE TABLE tenant (
    tenant_id uuid PRIMARY KEY,
    slug varchar(63) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
    display_name varchar(255) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETING', 'DELETED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);

CREATE TABLE project (
    tenant_id uuid NOT NULL REFERENCES tenant(tenant_id),
    project_id uuid NOT NULL,
    name varchar(255) NOT NULL,
    delivery_mode varchar(16) NOT NULL CHECK (delivery_mode IN ('STANDARD', 'STRICT')),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id, project_id)
);

CREATE TABLE repository_binding (
    tenant_id uuid NOT NULL,
    repository_binding_id uuid NOT NULL,
    project_id uuid NOT NULL,
    provider varchar(32) NOT NULL CHECK (provider IN ('GITHUB')),
    immutable_repository_id varchar(255) NOT NULL,
    display_name varchar(255) NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('PENDING_TRUST', 'ACTIVE', 'RECONCILING', 'UNBOUND')),
    trust_establishment_id uuid,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    bound_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    unbound_at timestamptz,
    PRIMARY KEY (tenant_id, repository_binding_id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, project_id),
    CHECK ((state='UNBOUND' AND unbound_at IS NOT NULL) OR (state<>'UNBOUND' AND unbound_at IS NULL))
);

CREATE UNIQUE INDEX uq_repository_binding_live_provider_repository
    ON repository_binding (provider, immutable_repository_id)
    WHERE state IN ('PENDING_TRUST', 'ACTIVE', 'RECONCILING');
CREATE UNIQUE INDEX uq_repository_binding_live_project
    ON repository_binding (tenant_id, project_id)
    WHERE state IN ('PENDING_TRUST', 'ACTIVE', 'RECONCILING');
CREATE INDEX repository_binding_scope_idx
    ON repository_binding (tenant_id, project_id, immutable_repository_id);

SELECT accord_security.enforce_tenant_table('public.tenant'::regclass);
SELECT accord_security.enforce_tenant_table('public.project'::regclass);
SELECT accord_security.enforce_tenant_table('public.repository_binding'::regclass);

REVOKE ALL ON tenant, project, repository_binding FROM PUBLIC, accord_api, accord_worker;
GRANT SELECT, INSERT, UPDATE ON tenant, project, repository_binding TO accord_api;
GRANT SELECT ON tenant, project, repository_binding TO accord_worker;
GRANT UPDATE ON repository_binding TO accord_worker;
```

The live-state global index intentionally forbids one provider repository from being trusted by two tenants at the same time, while retaining immutable `UNBOUND` history and permitting a separately authorized rebind. The project index likewise permits only one live repository per project. Only `repository_binding` contains `immutable_repository_id`; `tenant` and pre-binding identity-provider rows do not. V010 uses the helper already installed by Foundation V001; its three individual calls execute after every index and before the explicit least-privilege grants. Extend `RepositoryBindingRepositoryTest` to require exact forced policy catalogs and exact grants, including no `DELETE`, no worker write to `tenant`/`project`, and no privilege granted to `PUBLIC`.

- [ ] **Step 4: Add scope types that cannot fabricate repository identity**

Create the Java value and scope types. `TenantId.java` is a non-null value record:

```java
package com.inforvans.accord.identity

import java.util.Objects;
import java.util.UUID

public record TenantId(UUID value) {
    public TenantId { Objects.requireNonNull(value, "value"); }
}
```

Create `ScopeIdentity.java`; keeping tenant/project/repository variants as distinct records prevents callers from fabricating an immutable repository ID for a tenant-level operation:

```java
package com.inforvans.accord.identity;

import java.util.Objects;
import java.util.UUID;

public sealed interface ScopeIdentity permits ScopeIdentity.Tenant, ScopeIdentity.Project, ScopeIdentity.Repository {
    TenantId tenantId();
    String scopeType();
    String scopeId();

    record Tenant(TenantId tenantId) implements ScopeIdentity {
        public Tenant { Objects.requireNonNull(tenantId, "tenantId"); }
        public String scopeType() { return "tenant"; }
        public String scopeId() { return tenantId.value().toString(); }
    }

    record Project(TenantId tenantId, UUID projectId) implements ScopeIdentity {
        public Project {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(projectId, "projectId");
        }
        public String scopeType() { return "project"; }
        public String scopeId() { return projectId.toString(); }
    }

    record Repository(
        TenantId tenantId,
        UUID projectId,
        UUID bindingId,
        GitProvider provider,
        String immutableRepositoryId
    ) implements ScopeIdentity {
        public Repository {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(provider, "provider");
            if (immutableRepositoryId == null || immutableRepositoryId.isBlank()) {
                throw new IllegalArgumentException("immutableRepositoryId is required");
            }
        }
        public String scopeType() { return "repository"; }
        public String scopeId() { return immutableRepositoryId; }
    }
}
```

Create `RepositoryBinding.java` with its provider enum:

```java
package com.inforvans.accord.identity;

import java.util.UUID;

enum GitProvider { GITHUB }

public record RepositoryBinding(
    TenantId tenantId,
    UUID bindingId,
    UUID projectId,
    GitProvider provider,
    String immutableRepositoryId,
    String displayName,
    long version
) {}
```

Create `RepositoryBindingExceptions.java`:

```java
package com.inforvans.accord.identity;

import java.util.UUID;

final class RepositoryBindingExceptions {
    private RepositoryBindingExceptions() {}

    static final class RepositoryAlreadyBound extends RuntimeException {
        RepositoryAlreadyBound(GitProvider provider, String id) {
            super(provider + " repository " + id + " is already bound");
        }
    }

    static final class ProjectAlreadyHasLiveRepository extends RuntimeException {
        ProjectAlreadyHasLiveRepository(UUID projectId) {
            super("project " + projectId + " already has a live repository binding");
        }
    }
}
```

- [ ] **Step 5: Implement bind, rename CAS, and exact-tenant lookup**

Create `RepositoryBindingRepository.java`:

```java
package com.inforvans.accord.identity

import java.time.OffsetDateTime;
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.postgresql.util.PSQLException

import static com.inforvans.accord.identity.RepositoryBindingExceptions.ProjectAlreadyHasLiveRepository;
import static com.inforvans.accord.identity.RepositoryBindingExceptions.RepositoryAlreadyBound;

final class RepositoryBindingRepository {
    RepositoryBinding bind(DSLContext tx, TenantId tenantId, UUID projectId, GitProvider provider, String immutableId, String displayName) {
        var bindingId = UUID.randomUUID();
        try {
            tx.execute(
                """INSERT INTO repository_binding
                   (tenant_id, repository_binding_id, project_id, provider, immutable_repository_id, display_name, state)
                   VALUES (?, ?, ?, ?, ?, ?, 'PENDING_TRUST')""",
                tenantId.value(), bindingId, projectId, provider.name(), immutableId, displayName
            );
        } catch (DataAccessException error) {
            if ("23505".equals(error.sqlState()) && postgresCause(error) != null) {
                var constraint = postgresCause(error).getServerErrorMessage().getConstraint();
                if ("uq_repository_binding_live_provider_repository".equals(constraint)) {
                    throw new RepositoryAlreadyBound(provider, immutableId);
                }
                if ("uq_repository_binding_live_project".equals(constraint)) {
                    throw new ProjectAlreadyHasLiveRepository(projectId);
                }
            }
            throw error;
        }
        return get(tx, tenantId, bindingId);
    }

    void rename(DSLContext tx, TenantId tenantId, UUID bindingId, String displayName, long expectedVersion) {
        var changed = tx.execute(
            """UPDATE repository_binding SET display_name=?, version=version+1
               WHERE tenant_id=? AND repository_binding_id=? AND version=?""",
            displayName, tenantId.value(), bindingId, expectedVersion
        );
        if (changed != 1) throw new IllegalStateException("repository binding version conflict");
    }

    void markUnbound(DSLContext tx, TenantId tenantId, UUID bindingId, long expectedVersion, OffsetDateTime now) {
        var changed = tx.execute(
            """UPDATE repository_binding
               SET state='UNBOUND', unbound_at=?, trust_establishment_id=NULL, version=version+1
               WHERE tenant_id=? AND repository_binding_id=?
                 AND state IN ('PENDING_TRUST','ACTIVE','RECONCILING') AND version=?""",
            now, tenantId.value(), bindingId, expectedVersion
        );
        if (changed != 1) throw new IllegalStateException("repository unbind version conflict");
    }

    RepositoryBinding get(DSLContext tx, TenantId tenantId, UUID bindingId) {
        var row = tx.fetchOne(
            """SELECT tenant_id, repository_binding_id, project_id, provider, immutable_repository_id, display_name, version
               FROM repository_binding WHERE tenant_id=? AND repository_binding_id=?""",
            tenantId.value(), bindingId
        );
        if (row == null) throw new java.util.NoSuchElementException("repository binding not found");
        return new RepositoryBinding(
                new TenantId(row.get("tenant_id", UUID.class)), row.get("repository_binding_id", UUID.class),
                row.get("project_id", UUID.class), GitProvider.valueOf(row.get("provider", String.class)),
                row.get("immutable_repository_id", String.class), row.get("display_name", String.class),
                row.get("version", Long.class)
        );
    }

    private static PSQLException postgresCause(Throwable error) {
        for (var current = error; current != null; current = current.getCause()) {
            if (current instanceof PSQLException postgres) return postgres;
        }
        return null;
    }
}
```

`RepositoryBindingRepository` is deliberately stateless: API and worker command handlers must pass the `DSLContext` supplied by `TenantTransactions`. The migration-owner `dsl` in this task is only a pre-RLS fixture; Task 3 rewires the fixture and every production call site to an application-role tenant transaction.

Create `IdentityPostgreSqlTest.java`:

```java
package com.inforvans.accord.identity

import java.nio.charset.StandardCharsets
import java.util.UUID
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IdentityPostgreSqlTest {
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5");
    protected DSLContext dsl;

    @BeforeAll
    void startIdentityDatabase() {
        postgres.start();
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("filesystem:../../../../database/control-plane/migrations")
            .load()
            .migrate();
        dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), SQLDialect.POSTGRES);
    }

    @BeforeEach
    void clearIdentityDatabase() {
        dsl.execute("TRUNCATE TABLE tenant CASCADE");
        dsl.execute("TRUNCATE TABLE domain_event, outbox_event");
    }

    @AfterAll
    void stopIdentityDatabase() {
        dsl.close();
        postgres.stop();
    }

    protected void seedTenantAndProject(UUID tenantId, UUID projectId) {
        dsl.execute(
            "INSERT INTO tenant(tenant_id,slug,display_name,status) VALUES(?,?,?,'ACTIVE')",
            tenantId,
            "tenant-" + tenantId.toString().substring(24),
            "tenant-" + tenantId.toString().substring(32)
        );
        dsl.execute(
            "INSERT INTO project(tenant_id,project_id,name,delivery_mode,status) VALUES(?,?,?,'STANDARD','ACTIVE')",
            tenantId,
            projectId,
            "project-" + projectId.toString().substring(32)
        );
    }

    protected TenantId seedTenant() {
        var tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        dsl.execute(
            "INSERT INTO tenant(tenant_id,slug,display_name,status) VALUES(?,'tenant-a','Tenant A','ACTIVE')",
            tenantId
        );
        return new TenantId(tenantId);
    }

    protected SeededPerson seedPerson(TenantId tenantId, String immutableSubject) {
        var providerId = named(tenantId.value() + ":provider");
        var naturalPersonId = named(tenantId.value() + ":person:" + immutableSubject);
        var accountId = named(tenantId.value() + ":account:" + immutableSubject);
        dsl.execute(
            """INSERT INTO identity_provider
               (tenant_id,identity_provider_id,kind,issuer_or_entity_id,configuration_secret_ref,state)
               VALUES(?,?,'OIDC','https://idp.example.test','secret://test/idp','ACTIVE')
               ON CONFLICT DO NOTHING""",
            tenantId.value(), providerId
        );
        dsl.execute(
            "INSERT INTO natural_person(tenant_id,natural_person_id,status) VALUES(?,?,'ACTIVE')",
            tenantId.value(), naturalPersonId
        );
        dsl.execute(
            """INSERT INTO human_account
               (tenant_id,account_id,natural_person_id,identity_provider_id,immutable_subject,display_name,status)
               VALUES(?,?,?,?,?,?,'ACTIVE')""",
            tenantId.value(), accountId, naturalPersonId, providerId, immutableSubject, immutableSubject
        );
        return new SeededPerson(accountId, naturalPersonId);
    }

    private UUID named(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    protected record SeededPerson(UUID accountId, UUID naturalPersonId) {}
}
```

- [ ] **Step 6: Run binding and migration tests**

Run:

```bash
./gradlew :database:control-plane:test
./gradlew :apps:control-plane:modules:identity:test --tests '*RepositoryBindingRepositoryTest'
```

Expected: PASS. A rename changes only display data and version; the immutable ID remains `77831`; the two named live-state indexes distinguish provider-global and per-project conflicts; an `UNBOUND` historical row no longer prevents a fresh trust establishment.

- [ ] **Step 7: Commit tenant and repository identity**

```bash
git add database/control-plane/migrations/V010__tenant_project_repository.sql apps/control-plane/modules/identity
git commit -m "feat: bind tenants to immutable repositories"
```

### Task 3: Force PostgreSQL RLS With Transaction-Local Tenant Context

**Files:**
- Modify: `database/control-plane/bootstrap/00-pre-flyway-roles.sql`
- Create: `database/control-plane/migrations/V011__tenant_row_level_security.sql`
- Modify: `database/control-plane/src/testFixtures/java/com/inforvans/accord/database/ControlPlaneTestRoles.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/TenantTransactions.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/ActiveTenantDirectory.java`
- Create: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/RlsPostgreSqlTest.java`
- Create: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/TenantRlsTest.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/OutboxDispatcher.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableEventStore.java`
- Modify: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/OutboxDispatcherTest.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/InboxDispatcher.java`
- Modify: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/InboxDispatcherTest.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/ReliableMessageRedrive.java`
- Modify: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliableMessageRedriveTest.java`
- Modify: `apps/control-plane/worker/src/main/java/com/inforvans/accord/controlplane/worker/WorkerScheduling.java`
- Modify: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/WorkerSchedulingTest.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/TenantOutboxPumpTest.java`
- Create: `apps/control-plane/worker/src/test/java/com/inforvans/accord/controlplane/worker/TenantInboxPumpTest.java`
- Modify: `database/control-plane/build.gradle`
- Modify: `apps/control-plane/modules/identity/build.gradle`
- Modify: `apps/control-plane/modules/authorization/build.gradle`
- Modify: `apps/control-plane/modules/audit/build.gradle`
- Modify: `apps/control-plane/modules/reliability/build.gradle`
- Modify: `apps/control-plane/api/build.gradle`
- Modify: `apps/control-plane/worker/build.gradle`
- Modify: `database/control-plane/src/test/java/com/inforvans/accord/database/PlatformMigrationTest.java`
- Modify: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/JooqCommandGateTest.java`
- Modify: `apps/control-plane/modules/reliability/src/test/java/com/inforvans/accord/reliability/ReliableEventStoreTest.java`
- Modify: `apps/control-plane/api/src/test/java/com/inforvans/accord/controlplane/http/ContractValidationApiTest.java`
- Modify: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/IdentityPostgreSqlTest.java`
- Modify: `infra/local/postgres/00-roles-and-databases.sql`
- Create: `docs/operations/runbooks/control-plane-role-bootstrap.md`

- [ ] **Step 1: Write failing cross-tenant and pool-leak tests**

Create `TenantRlsTest.java`:

```java
package com.inforvans.accord.identity;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantRlsTest extends RlsPostgreSqlTest {
    private final TenantId tenantA = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private final TenantId tenantB = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000002"));

    @Test
    void applicationRoleSeesOnlyTransactionTenant() {
        seedTwoTenantsAsMigrator();
        assertThat(transactions.read(tenantA, this::names)).containsExactly("tenant-a");
        assertThat(transactions.read(tenantB, this::names)).containsExactly("tenant-b");
    }

    @Test
    void missingTenantContextFailsClosedAndLocalSettingDoesNotLeak() {
        seedTwoTenantsAsMigrator();
        assertThat(names(appDsl)).isEmpty();
        transactions.read(tenantA, tx -> { assertThat(names(tx)).containsExactly("tenant-a"); return null; });
        assertThat(names(appDsl)).isEmpty();
        assertThatThrownBy(() -> transactions.write(tenantA, tx -> {
            assertThat(names(tx)).containsExactly("tenant-a");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(names(appDsl)).isEmpty();
        transactions.read(tenantB, tx -> { assertThat(names(tx)).containsExactly("tenant-b"); return null; });
        assertThat(names(appDsl)).isEmpty();
        assertThatThrownBy(() -> appDsl.execute(
                "INSERT INTO project(tenant_id,project_id,name,delivery_mode,status) VALUES (?,?,'leak','STANDARD','ACTIVE')",
                tenantA.value(), UUID.randomUUID()
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void everyTenantTableIsForceProtectedByStandardPolicy() {
        var uncovered = migratorDsl.fetch(
            """SELECT n.nspname || '.' || c.relname AS table_name
               FROM pg_class c
               JOIN pg_namespace n ON n.oid=c.relnamespace
               JOIN pg_attribute a ON a.attrelid=c.oid AND a.attname='tenant_id' AND NOT a.attisdropped
               WHERE n.nspname='public' AND c.relkind IN ('r','p')
                 AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity OR NOT EXISTS (
                   SELECT 1 FROM pg_policy p
                   WHERE p.polrelid=c.oid AND p.polname='tenant_isolation'
                     AND p.polcmd='*' AND p.polroles=ARRAY[0::oid]
                     AND pg_get_expr(p.polqual,p.polrelid)='(tenant_id = accord_security.current_tenant_id())'
                     AND pg_get_expr(p.polwithcheck,p.polrelid)='(tenant_id = accord_security.current_tenant_id())'
                 ))
               ORDER BY c.relname"""
        ).getValues("table_name", String.class);
        assertThat(uncovered).isEmpty();
    }

    @Test
    void runtimeRolesCannotBypassRlsAndOnlyWorkerCanEnumerateActiveTenantIds() {
        seedTwoTenantsAsMigrator();
        var unsafe = migratorDsl.fetch(
            "SELECT rolname FROM pg_roles WHERE rolname IN ('accord_api','accord_worker') AND rolbypassrls"
        );
        assertThat(unsafe).isEmpty();
        assertThat(workerDirectory.activeTenantIds()).containsExactly(tenantA.value(), tenantB.value());
        assertThatThrownBy(() -> appDsl.fetch("SELECT * FROM accord_security.active_tenant_ids()"))
            .isInstanceOf(RuntimeException.class);
    }

    private List<String> names(DSLContext context) {
        return context.fetch("SELECT display_name FROM tenant ORDER BY display_name").getValues(0, String.class);
    }
}
```

- [ ] **Step 2: Run the RLS test and verify tenant A can currently see tenant B**

Run: `./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'`

Expected: FAIL because `TenantTransactions` and the worker-only tenant directory do not exist. The Foundation/V010 catalog assertions already prove direct application-role access is forced through RLS; Task 3 must never create a red test that temporarily permits tenant A to see tenant B.

- [ ] **Step 3: Verify the per-migration RLS baseline and add the worker directory**

Modify the Foundation-owned `database/control-plane/bootstrap/00-pre-flyway-roles.sql` only to add the later audit-test role in non-production test setup and to retain the role-attribute verification. Do not move role creation into Flyway and do not install default table DML privileges. The managed PostgreSQL administrator runs this prerequisite before V001 because the migrator is intentionally `NOCREATEROLE`:

```sql
\set ON_ERROR_STOP on

ALTER ROLE accord_api NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
ALTER ROLE accord_worker NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;

DO $bootstrap$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_tenant_catalog_owner') THEN
    CREATE ROLE accord_tenant_catalog_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT BYPASSRLS;
  END IF;
END $bootstrap$;
GRANT accord_tenant_catalog_owner TO accord_migrator WITH SET TRUE;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator IN SCHEMA public REVOKE ALL ON TABLES FROM accord_api, accord_worker;
```

The Helm migration Job uses an administrator-completed bootstrap version as a prerequisite and checks the three role attributes before running Flyway. `docs/operations/runbooks/control-plane-role-bootstrap.md` gives the exact `psql --set ON_ERROR_STOP=1 --file database/control-plane/bootstrap/00-pre-flyway-roles.sql` command, catalog verification query, credential rotation ownership, and rollback procedure. Add the same NOLOGIN owner role and grant to `infra/local/postgres/00-roles-and-databases.sql`; runtime credentials are never members of it.

Create `V011__tenant_row_level_security.sql`. Foundation V001 owns `accord_security.current_tenant_id()` and `enforce_tenant_table(regclass)`; V001, V002, and V010 each enforce their own tables. V011 must not repair or backfill a table created by another migration:

```sql
DO $verify_existing_tenant_tables$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM pg_class c
    JOIN pg_namespace n ON n.oid=c.relnamespace
    JOIN pg_attribute a ON a.attrelid=c.oid AND a.attname='tenant_id' AND NOT a.attisdropped
    WHERE n.nspname='public' AND c.relkind IN ('r','p')
      AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity OR NOT EXISTS (
        SELECT 1 FROM pg_policy p
        WHERE p.polrelid=c.oid AND p.polname='tenant_isolation' AND p.polcmd='*' AND p.polroles=ARRAY[0::oid]
          AND pg_get_expr(p.polqual,p.polrelid)='(tenant_id = accord_security.current_tenant_id())'
          AND pg_get_expr(p.polwithcheck,p.polrelid)='(tenant_id = accord_security.current_tenant_id())'
      ))
  ) THEN
    RAISE EXCEPTION 'a pre-V011 tenant table was not enforced in its creating migration';
  END IF;
END $verify_existing_tenant_tables$;

GRANT USAGE, CREATE ON SCHEMA accord_security TO accord_tenant_catalog_owner;
GRANT SELECT (tenant_id, status) ON tenant TO accord_tenant_catalog_owner;
CREATE FUNCTION accord_security.active_tenant_ids() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$ SELECT tenant_id FROM public.tenant WHERE status='ACTIVE' ORDER BY tenant_id $$;
REVOKE ALL ON FUNCTION accord_security.active_tenant_ids() FROM PUBLIC, accord_api;
GRANT EXECUTE ON FUNCTION accord_security.active_tenant_ids() TO accord_worker;
ALTER FUNCTION accord_security.active_tenant_ids() OWNER TO accord_tenant_catalog_owner;
REVOKE CREATE ON SCHEMA accord_security FROM accord_tenant_catalog_owner;
```

There is deliberately no event trigger: every migration, including V001/V002/V010, owns enforcement for the `public` tenant tables it creates and grants runtime DML only afterward. V011's `DO` block is a failing assertion, not remediation. Because this is a greenfield plan, edit the planned V001/V002/V010 files before their first application. If an environment has already recorded any of those checksums, never rewrite migration history there: create a new forward-only baseline migration, suspend runtime access until it succeeds, and adopt the same per-creating-migration rule for every subsequent deployment line. The only `BYPASSRLS` role is the NOLOGIN owner of the fixed, zero-argument tenant-ID function; it has column-level access only to `tenant_id,status`, and neither runtime role can become it. `accord_api` cannot execute the directory function. Flyway never issues `ALTER ROLE`.

- [ ] **Step 4: Inject tenant context with `SET LOCAL` inside the same transaction**

Create `TenantTransactions.java`:

```java
package com.inforvans.accord.identity;

import java.util.Objects;
import java.util.function.Function;
import org.jooq.DSLContext;

public final class TenantTransactions {
    private final DSLContext dsl;

    public TenantTransactions(DSLContext dsl) { this.dsl = Objects.requireNonNull(dsl, "dsl"); }

    public <T> T read(TenantId tenantId, Function<DSLContext, T> block) { return inTenant(tenantId, block); }
    public <T> T write(TenantId tenantId, Function<DSLContext, T> block) { return inTenant(tenantId, block); }

    private <T> T inTenant(TenantId tenantId, Function<DSLContext, T> block) {
        return dsl.transactionResult(configuration -> {
            var transaction = configuration.dsl();
            transaction.execute("SELECT set_config('app.tenant_id', ?, true)", tenantId.value().toString());
            var currentTenant = transaction.fetchOne(
                "SELECT accord_security.current_tenant_id()", java.util.UUID.class
            );
            if (!tenantId.value().equals(currentTenant)) throw new IllegalStateException("tenant context rejected");
            return block.apply(transaction);
        });
    }
}
```

Never set tenant context at connection/session scope. Repositories invoked inside the callback must use the supplied transactional `DSLContext`, not a separately injected pool-level instance.

- [ ] **Step 5: Make worker leasing explicitly tenant-scoped**

Keep Reliability independent of Identity. Make `OutboxRepository` stateless and require the caller's transactional context and tenant on every state change:

```java
List<LeasedEvent> lease(
    DSLContext tx, UUID tenantId, String owner, int limit, Duration duration, OffsetDateTime now
) {
    return tx.fetch(
        """
        WITH candidates AS (
          SELECT event_id FROM outbox_event
          WHERE tenant_id = ?
            AND ((state = 'PENDING' AND available_at <= ?) OR (state = 'DELIVERING' AND lease_until < ?))
          ORDER BY available_at, event_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE outbox_event o
           SET state = 'DELIVERING', lease_owner = ?, lease_until = ?, attempt_count = attempt_count + 1
          FROM candidates c
         WHERE o.tenant_id = ? AND o.event_id = c.event_id
        RETURNING o.event_id, o.tenant_id, o.destination, o.payload_schema, o.payload::text,
                  o.attempt_count, o.lease_until
        """,
        tenantId, now, now, limit, owner, now.plus(duration), tenantId
    ).map(this::toLeasedEvent);
}

boolean markDelivered(DSLContext tx, UUID tenantId, LeasedEvent event, String owner, OffsetDateTime now) {
    return tx.execute(
        """UPDATE outbox_event SET state='DELIVERED', delivered_at=?, lease_owner=NULL, lease_until=NULL
           WHERE tenant_id=? AND event_id=? AND state='DELIVERING' AND lease_owner=?
             AND attempt_count=? AND lease_until=? AND lease_until>?""",
        now, tenantId, event.eventId(), owner, event.leaseGeneration(), event.leaseUntil(), now
    ) == 1;
}

FailureDisposition recordFailure(
    DSLContext tx,
    UUID tenantId,
    LeasedEvent event,
    String owner,
    OffsetDateTime retryAt,
    OffsetDateTime now,
    String errorCode,
    int maxAttempts
) {
    var state = tx.fetchOne(
        """UPDATE outbox_event
              SET state=CASE WHEN attempt_count>=? THEN 'DEAD' ELSE 'PENDING' END,
                  available_at=CASE WHEN attempt_count>=? THEN available_at ELSE ? END,
                  lease_owner=NULL,lease_until=NULL,
                  dead_at=CASE WHEN attempt_count>=? THEN ? ELSE NULL END,last_error_code=?
            WHERE tenant_id=? AND event_id=? AND state='DELIVERING' AND lease_owner=?
              AND attempt_count=? AND lease_until=? AND lease_until>?
            RETURNING state""",
        maxAttempts, maxAttempts, retryAt, maxAttempts, now, errorCode,
        tenantId, event.eventId(), owner, event.leaseGeneration(), event.leaseUntil(), now
    );
    if (state == null) throw new IllegalStateException("outbox lease was lost");
    return "DEAD".equals(state.get("state", String.class))
        ? FailureDisposition.DEAD_LETTERED
        : FailureDisposition.RETRY_SCHEDULED;
}
```

`toLeasedEvent` maps `attempt_count` to `leaseGeneration` and maps the exact returned `lease_until`; these two fields are the fencing token established by Foundation Task 12 and are never recomputed in memory.

`OutboxDispatcher` owns only the external call and has no database or Identity dependency:

```java
sealed interface DeliveryAttempt permits DeliveryAttempt.Delivered, DeliveryAttempt.Failed {
    record Delivered() implements DeliveryAttempt {}
    record Failed(String errorCode) implements DeliveryAttempt {}
}

final class OutboxDispatcher {
    private final EventTransport transport;

    OutboxDispatcher(EventTransport transport) { this.transport = transport; }

    DeliveryAttempt deliver(LeasedEvent event) {
        try {
            transport.deliver(event);
            return new DeliveryAttempt.Delivered();
        } catch (RuntimeException error) {
            return new DeliveryAttempt.Failed(error.getClass().getSimpleName());
        }
    }
}
```

Create `ActiveTenantDirectory.java` in Identity as the minimal production adapter, then make the worker module the composition boundary:

```java
package com.inforvans.accord.identity;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

@FunctionalInterface
public interface ActiveTenantDirectory { List<UUID> activeTenantIds(); }

final class PostgreSqlActiveTenantDirectory implements ActiveTenantDirectory {
    private final DSLContext workerDsl;

    PostgreSqlActiveTenantDirectory(DSLContext workerDsl) { this.workerDsl = workerDsl; }

    @Override
    public List<UUID> activeTenantIds() {
        return workerDsl.fetch("SELECT value FROM accord_security.active_tenant_ids() AS ids(value)")
            .getValues("value", UUID.class);
    }
}
```

In `WorkerScheduling.java`:

```java
package com.inforvans.accord.controlplane.worker;

import com.inforvans.accord.identity.ActiveTenantDirectory;
import com.inforvans.accord.identity.TenantId;
import com.inforvans.accord.identity.TenantTransactions;
import com.inforvans.accord.reliability.DeliveryAttempt;
import com.inforvans.accord.reliability.OutboxDispatcher;
import com.inforvans.accord.reliability.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

final class TenantOutboxPump {
    private final ActiveTenantDirectory directory;
    private final TenantTransactions transactions;
    private final OutboxRepository repository;
    private final OutboxDispatcher dispatcher;
    private final String owner;
    private final Clock clock;
    private final int maxAttempts;

    TenantOutboxPump(ActiveTenantDirectory directory, TenantTransactions transactions,
                     OutboxRepository repository, OutboxDispatcher dispatcher,
                     String owner, Clock clock, int maxAttempts) {
        this.directory = directory;
        this.transactions = transactions;
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.owner = owner;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    void publishOutbox() {
        for (var tenantId : directory.activeTenantIds()) {
            var now = OffsetDateTime.now(clock);
            var leased = transactions.write(new TenantId(tenantId), tx ->
                repository.lease(tx, tenantId, owner, 50, Duration.ofSeconds(30), now));
            for (var event : leased) {
                // No JDBC transaction or tenant setting remains open across this network call.
                var attempt = dispatcher.deliver(event);
                transactions.write(new TenantId(tenantId), tx -> {
                    var completedAt = OffsetDateTime.now(clock);
                    if (attempt instanceof DeliveryAttempt.Delivered) {
                        if (!repository.markDelivered(tx, tenantId, event, owner, completedAt)) {
                            throw new IllegalStateException("outbox lease lost");
                        }
                    } else if (attempt instanceof DeliveryAttempt.Failed failed) {
                        var exponentialSeconds = Math.min(900L, 5L << Math.min(event.leaseGeneration() - 1, 7));
                        var jitterSeconds = Math.floorMod(event.eventId().hashCode(), 5);
                        repository.recordFailure(
                            tx, tenantId, event, owner,
                            completedAt.plusSeconds(exponentialSeconds + jitterSeconds), completedAt,
                            failed.errorCode(), maxAttempts
                        );
                    }
                    return null;
                });
            }
        }
    }
}
```

`WorkerScheduling` constructs `TenantTransactions(workerDsl)`, `PostgreSqlActiveTenantDirectory(workerDsl)`, the stateless repository, dispatcher, and `TenantOutboxPump`; the scheduled method calls only `pump.publishOutbox()`. Update `WorkerSchedulingTest` beans accordingly. Add `implementation(project(":apps:control-plane:modules:identity"))` to the worker build, while Reliability's build has no Identity dependency. `TenantOutboxPumpTest` uses PostgreSQL, two tenants, and a one-connection worker Hikari pool; it asserts the directory exposes IDs only, tenant A cannot lease B, the transport callback observes `current_tenant_id() IS NULL`, acknowledgement/failure each run in a new short tenant transaction, stale generations cannot acknowledge after expiry or takeover, retry timing remains exponential with deterministic jitter, and the configured attempt limit reaches Foundation's terminal `DEAD` state.

Modify `InboxDispatcher.java` with the same dependency direction. Reliability makes `InboxRepository` stateless; `lease(tx, tenantId, owner, limit, duration, now)`, `markCompleted(tx, tenantId, lease, owner, now)`, and `recordFailure(tx, tenantId, lease, owner, retryAt, now, errorCode, maxAttempts)` require a caller-supplied `DSLContext`. Every completion/failure predicate includes `tenant_id`, natural key, `lease_owner`, `leaseGeneration`, the exact `leaseUntil`, and `lease_until > now`. `recordFailure` preserves Foundation's exponential retry and terminal `DEAD` transition. `InboxDispatcher` owns only handler selection/execution and performs no SQL. `TenantInboxPump` has the same `owner`, `clock`, and `maxAttempts = 10` constructor fields as `TenantOutboxPump`; its directory-driven core is:

```java
void consumeInbox() {
    for (var tenantId : directory.activeTenantIds()) {
        var leases = transactions.write(new TenantId(tenantId), tx ->
            inbox.lease(tx, tenantId, owner, 50, Duration.ofSeconds(30), OffsetDateTime.now(clock)));
        for (var lease : leases) {
            // No JDBC transaction or tenant setting remains open across handler execution.
            var result = dispatcher.handle(lease);
            transactions.write(new TenantId(tenantId), tx -> {
                var completedAt = OffsetDateTime.now(clock);
                if (result instanceof InboxAttempt.Completed) {
                    if (!inbox.markCompleted(tx, tenantId, lease, owner, completedAt)) {
                        throw new IllegalStateException("inbox lease lost");
                    }
                } else if (result instanceof InboxAttempt.Retry retry) {
                    var exponentialSeconds = Math.min(900L, 5L << Math.min(lease.leaseGeneration() - 1, 7));
                    inbox.recordFailure(
                        tx, tenantId, lease, owner,
                        completedAt.plusSeconds(exponentialSeconds), completedAt,
                        retry.errorCode(), maxAttempts
                    );
                }
                return null;
            });
        }
    }
}
```

No broker acknowledgement or handler/network call occurs inside the lease or completion transaction. The Foundation V002 schema already supplies `PENDING/PROCESSING`, payload, lease, retry, and terminal columns, and `ReliableEventStore.acceptInbox(tx, tenantId, ...)` registers `PENDING` idempotently through the supplied transaction. `InboxRepository` uses `PROCESSING` for the lease state and never invents a second lifecycle. `InboxDispatcherTest`, `TenantOutboxPumpTest`, and `TenantInboxPumpTest` seed colliding message IDs in tenants A/B; prove lease/complete/failure cannot cross tenant; prove handler execution sees no transaction-local tenant setting; reject a late result after expiry or takeover even when the process owner string is reused; preserve poison-message `DEAD` and optimistic redrive behavior; and reclaim expired leases only inside their original tenant. No pool-owning overload remains.

Refactor Foundation's `ReliableMessageRedriveService` only enough to instantiate the now-stateless repositories with the transaction argument it already receives. Its `(tenant_id, expectedAttemptCount, expectedRedriveCount)` lock, audit-before-state-change order, and Java package-private repository methods remain unchanged. Task 14 supplies the production `ReliableMessageRedriveAudit` adapter; Task 8's high-risk authorization service is the only factory for a `VerifiedRedriveApproval`. Until both beans exist, redrive endpoints are absent and the capability fails closed.

Enable Gradle's test-fixture variant in `database/control-plane/build.gradle`:

```groovy
plugins {
    id 'java-library'
    id 'java-test-fixtures'
}
dependencies {
    implementation(libs.postgresql)
    testFixturesImplementation(libs.postgresql)
}
```

Modify the Foundation-owned `ControlPlaneTestRoles.java`; do not create a second helper or change ownership. Retain the Foundation role bootstrap and add only the `accord_audit_test` role and its schema usage needed by the audit suites:

```java
package com.inforvans.accord.database;

import java.sql.DriverManager;
import java.sql.SQLException;

public final class ControlPlaneTestRoles {
    private ControlPlaneTestRoles() {}

    public static void bootstrap(String jdbcUrl, String user, String password) {
        try (var connection = DriverManager.getConnection(jdbcUrl, user, password);
             var statement = connection.createStatement()) {
                statement.execute("""
                    DO $roles$
                    BEGIN
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_migrator') THEN
                        CREATE ROLE accord_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
                      END IF;
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_api') THEN
                        CREATE ROLE accord_api LOGIN PASSWORD 'api-test' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
                      END IF;
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_worker') THEN
                        CREATE ROLE accord_worker LOGIN PASSWORD 'worker-test' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
                      END IF;
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_audit_test') THEN
                        CREATE ROLE accord_audit_test LOGIN PASSWORD 'audit-test' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
                      END IF;
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='accord_tenant_catalog_owner') THEN
                        CREATE ROLE accord_tenant_catalog_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT BYPASSRLS;
                      END IF;
                    END $roles$;
                """);
                statement.execute("GRANT accord_tenant_catalog_owner TO accord_migrator WITH SET TRUE");
                statement.execute("GRANT accord_tenant_catalog_owner TO " + connection.getMetaData().getUserName() + " WITH SET TRUE");
                statement.execute("GRANT USAGE ON SCHEMA public TO accord_api, accord_worker, accord_audit_test");
                statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE accord_migrator IN SCHEMA public REVOKE ALL ON TABLES FROM accord_api, accord_worker, accord_audit_test");
        } catch (SQLException error) {
            throw new IllegalStateException("control-plane role bootstrap failed", error);
        }
    }
}
```

The helper is called immediately after every control-plane Testcontainer starts and before Flyway. Add `testImplementation(testFixtures(project(":database:control-plane")))` to API, Identity, Authorization, Audit, Reliability, Worker, and the shared `tests:integration`, `tests:api`, `tests:security-negative`, `tests:state-machine`, and `tests:fault-injection` configurations. Modify `PlatformMigrationTest`, `JooqCommandGateTest`, `PostgreSqlReliabilityTest` in `ReliableEventStoreTest.java`, `ContractValidationApiTest`, and `IdentityPostgreSqlTest` to call `ControlPlaneTestRoles.bootstrap(...)` before their existing `Flyway.configure()` call. Every later shared-suite fixture, including `RequirementMigrationIT`, must use the same sequence.

Create `verify-control-plane-fixtures.ps1` as an executable contract:

```powershell
$ErrorActionPreference = 'Stop'
$files = rg -l 'Flyway\.configure' apps database tests | Where-Object {
  Select-String -Quiet -LiteralPath $_ -Pattern 'PostgreSQLContainer'
}
$invalid = @($files | Where-Object {
  $text = Get-Content -Raw -LiteralPath $_
  $lifecyclePattern = [regex]'(?:\b[A-Za-z_]\w*\.start\(\)|\bPostgreSQLContainer(?:<[^>]+>)?\s*\()'
  $bootstrapPattern = [regex]'ControlPlaneTestRoles\.bootstrap'
  $flywayMatches = [regex]::Matches($text, 'Flyway\.configure')

  @($flywayMatches | Where-Object {
    $beforeFlyway = $text.Substring(0, $_.Index)
    $lifecycles = @($lifecyclePattern.Matches($beforeFlyway))
    if ($lifecycles.Count -eq 0) { return $true }
    $lifecycle = $lifecycles[-1]
    $bootstraps = @($bootstrapPattern.Matches($beforeFlyway) | Where-Object { $_.Index -gt $lifecycle.Index })
    $bootstraps.Count -eq 0
  }).Count -gt 0
})
if ($invalid.Count -gt 0) { throw "Control-plane fixtures missing pre-Flyway role bootstrap: $($invalid -join ', ')" }
Write-Output 'control-plane-fixtures: PASS'
```

This scans every Flyway lifecycle in a file, rather than only its first fixture. It keeps both Foundation/V001 and later fixtures runnable; no test may create runtime roles after migration.

In `IdentityPostgreSqlTest`, keep `dsl` as the migration-owner seeding context, add a one-connection `accord_api` Hikari datasource as `appDsl`, and expose `transactions = TenantTransactions(appDsl)`. Call the shared bootstrap before Flyway, never grant runtime privileges after Flyway, and close both the DSL context and datasource in `@AfterAll`. Task 2's migration-seeding tests continue to pass `dsl`; every Task 4 production repository call passes the callback context from `transactions`.

Create `RlsPostgreSqlTest.java`:

```java
package com.inforvans.accord.identity;

import com.inforvans.accord.database.ControlPlaneTestRoles;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RlsPostgreSqlTest {
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5");
    protected DSLContext migratorDsl;
    protected DSLContext appDsl;
    protected DSLContext workerDsl;
    protected TenantTransactions transactions;
    protected ActiveTenantDirectory workerDirectory;
    private HikariDataSource appDataSource;
    private HikariDataSource workerDataSource;

    @BeforeAll
    void startRlsDatabase() {
        postgres.start();
        ControlPlaneTestRoles.bootstrap(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        migratorDsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), SQLDialect.POSTGRES);
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("filesystem:../../../../database/control-plane/migrations")
            .load()
            .migrate();
        appDataSource = singleConnectionPool("accord_api", "api-test");
        workerDataSource = singleConnectionPool("accord_worker", "worker-test");
        appDsl = DSL.using(appDataSource, SQLDialect.POSTGRES);
        workerDsl = DSL.using(workerDataSource, SQLDialect.POSTGRES);
        transactions = new TenantTransactions(appDsl);
        workerDirectory = new PostgreSqlActiveTenantDirectory(workerDsl);
    }

    @BeforeEach
    void clearRlsDatabase() {
        migratorDsl.execute("TRUNCATE TABLE tenant CASCADE");
        migratorDsl.execute("TRUNCATE TABLE domain_event, outbox_event");
    }

    @AfterAll
    void stopRlsDatabase() {
        appDsl.close();
        workerDsl.close();
        appDataSource.close();
        workerDataSource.close();
        migratorDsl.close();
        postgres.stop();
    }

    private HikariDataSource singleConnectionPool(String user, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setAutoCommit(true);
        config.setPoolName("rls-" + user);
        return new HikariDataSource(config);
    }

    protected void seedTwoTenantsAsMigrator() {
        var tenantA = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var tenantB = UUID.fromString("10000000-0000-0000-0000-000000000002");
        migratorDsl.execute(
            """INSERT INTO tenant(tenant_id,slug,display_name,status) VALUES
               (?,'tenant-a','tenant-a','ACTIVE'), (?,'tenant-b','tenant-b','ACTIVE')""",
            tenantA, tenantB
        );
    }
}
```

- [ ] **Step 6: Run all RLS and worker isolation tests**

Run:

```bash
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
./gradlew :apps:control-plane:modules:reliability:test --tests '*OutboxDispatcherTest' --tests '*InboxDispatcherTest'
./gradlew :apps:control-plane:worker:test --tests '*WorkerSchedulingTest' --tests '*TenantOutboxPumpTest' --tests '*TenantInboxPumpTest'
pwsh -NoProfile -File tests/architecture/verify-control-plane-fixtures.ps1
```

Expected: PASS. App-role queries without context see zero rows; tenant A and B see only themselves; one reused physical connection has no tenant after commit or rollback; every public tenant table is force protected by the standard policy; runtime roles have `rolbypassrls=false`; only the worker's fixed security-definer directory function enumerates active tenant IDs; tenant A cannot lease tenant B outbox or inbox records; transports/handlers run without a database transaction; lease and acknowledgement/completion/failure use separate short tenant transactions with generation/expiry fencing; poison messages still reach `DEAD`; `control-plane-fixtures: PASS`.

- [ ] **Step 7: Commit database-enforced tenant isolation**

```bash
git add database/control-plane infra/local/postgres docs/operations/runbooks/control-plane-role-bootstrap.md apps/control-plane
git commit -m "feat: force transaction-scoped tenant RLS"
```

### Task 4: Add Enterprise Federation, Human/Git Mapping, And Workload Identity

**Files:**
- Create: `database/control-plane/migrations/V012__enterprise_identities.sql`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/FederationAdapters.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/PrincipalIdentity.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/GitIdentityRepository.java`
- Create: `apps/control-plane/modules/identity/src/test/java/com/inforvans/accord/identity/EnterpriseIdentityTest.java`

- [ ] **Step 1: Write failing identity-mapping and machine-separation tests**

Create `EnterpriseIdentityTest.java`:

```java
package com.inforvans.accord.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnterpriseIdentityTest extends IdentityPostgreSqlTest {
    @Test
    void gitEmailCannotReplaceImmutableUserBinding() {
        var tenant = seedTenant();
        var person = seedPerson(tenant, "oidc-subject-7");
        var repository = new GitIdentityRepository();
        transactions.write(tenant, tx -> repository.bind(tx, tenant, person.accountId(), GitProvider.GITHUB, "MDQ6VXNlcjE3", "dev@example.test"));
        assertThat(transactions.read(tenant, tx -> repository.resolve(tx, tenant, GitProvider.GITHUB, "MDQ6VXNlcjE3").accountId()))
            .isEqualTo(person.accountId());
        assertThatThrownBy(() -> transactions.read(tenant, tx -> repository.resolveByCommitEmail(tx, tenant, "dev@example.test")))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void changingGitBindingRequiresReviewAndEmitsOneTransactionalEvent() {
        var tenant = seedTenant();
        var original = seedPerson(tenant, "oidc-subject-7");
        var replacement = seedPerson(tenant, "oidc-subject-8");
        var repository = new GitIdentityRepository();
        transactions.write(tenant, tx -> repository.bind(tx, tenant, original.accountId(), GitProvider.GITHUB, "MDQ6VXNlcjE3", "dev@example.test"));

        var pending = transactions.write(tenant, tx -> repository.requestChange(
            tx, tenant, GitProvider.GITHUB, "MDQ6VXNlcjE3", replacement.accountId(), 1,
            original.accountId(), UUID.fromString("70000000-0000-0000-0000-000000000001"),
            OffsetDateTime.parse("2026-07-24T10:00:00Z")
        ));

        assertThat(pending.state()).isEqualTo(GitBindingState.REVIEW_REQUIRED);
        assertThat(pending.bindingVersion()).isEqualTo(2L);
        assertThat(dsl.fetchCount(org.jooq.impl.DSL.table("outbox_event"))).isOne();
        assertThat(dsl.fetchCount(org.jooq.impl.DSL.table("git_identity_binding_change"))).isOne();
        assertThatThrownBy(() -> transactions.read(tenant, tx -> repository.resolve(tx, tenant, GitProvider.GITHUB, "MDQ6VXNlcjE3")))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void workloadPrincipalCannotSatisfyHumanAction() {
        var workload = new PrincipalIdentity.Workload(UUID.randomUUID(), WorkloadPurpose.CI_ATTESTATION, "repo:acme/demo:ref:refs/heads/main");
        assertThatThrownBy(workload::requireHuman).isInstanceOf(HumanPrincipalRequired.class);
    }

    @Test
    void everyEnterpriseIdentityRelationIsolatesTenantReadsAndWrites() {
        var tenantA = seedTenant();
        var tenantB = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000002"));
        seedTenantAndProject(tenantB.value(), UUID.fromString("20000000-0000-0000-0000-000000000002"));
        var personA = seedPerson(tenantA, "subject-a");
        var personB = seedPerson(tenantB, "subject-b");
        seedGitAndWorkload(tenantA, personA, "git-a", "workload-a");
        seedGitAndWorkload(tenantB, personB, "git-b", "workload-b");
        var tables = List.of("identity_provider", "natural_person", "human_account", "git_identity_binding", "workload_identity");

        transactions.read(tenantA, tx -> { tables.forEach(table -> assertThat(tx.fetchCount(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name(table)))).isOne()); return null; });
        transactions.read(tenantB, tx -> { tables.forEach(table -> assertThat(tx.fetchCount(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name(table)))).isOne()); return null; });
        transactions.write(tenantA, tx -> { tables.forEach(table -> assertThat(tx.execute("DELETE FROM " + table + " WHERE tenant_id=?", tenantB.value())).isZero()); return null; });
        assertThatThrownBy(() -> transactions.write(tenantA, tx -> {
            tx.execute("INSERT INTO natural_person(tenant_id,natural_person_id,status) VALUES (?,?,'ACTIVE')", tenantB.value(), UUID.randomUUID());
            return null;
        })).isInstanceOf(RuntimeException.class);
    }
}
```

Add `seedGitAndWorkload` to `IdentityPostgreSqlTest` as migration-owner setup that inserts one valid Git binding and one workload row for the supplied tenant/account. It is setup only; all reads and cross-tenant delete/insert attempts above use the one-connection `accord_api` pool and `TenantTransactions`.

- [ ] **Step 2: Run the test and verify the identity schema is absent**

Run: `./gradlew :apps:control-plane:modules:identity:test --tests '*EnterpriseIdentityTest'`

Expected: FAIL because enterprise identities and mapping types are unresolved.

- [ ] **Step 3: Create federation, natural-person, Git, and workload tables**

Create `V012__enterprise_identities.sql`:

```sql
CREATE TABLE identity_provider (
    tenant_id uuid NOT NULL REFERENCES tenant(tenant_id),
    identity_provider_id uuid NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('OIDC', 'SAML', 'SCIM')),
    issuer_or_entity_id varchar(1024) NOT NULL,
    configuration_secret_ref varchar(1024) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE', 'DISABLED')),
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, identity_provider_id),
    UNIQUE (tenant_id, kind, issuer_or_entity_id)
);

CREATE TABLE natural_person (
    tenant_id uuid NOT NULL REFERENCES tenant(tenant_id),
    natural_person_id uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEPARTED')),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id, natural_person_id)
);

CREATE TABLE human_account (
    tenant_id uuid NOT NULL,
    account_id uuid NOT NULL,
    natural_person_id uuid NOT NULL,
    identity_provider_id uuid NOT NULL,
    immutable_subject varchar(1024) NOT NULL,
    display_name varchar(255) NOT NULL,
    email_display varchar(320),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEPROVISIONED')),
    identity_version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id, account_id),
    FOREIGN KEY (tenant_id, natural_person_id) REFERENCES natural_person(tenant_id, natural_person_id),
    FOREIGN KEY (tenant_id, identity_provider_id) REFERENCES identity_provider(tenant_id, identity_provider_id),
    UNIQUE (tenant_id, account_id, natural_person_id),
    UNIQUE (tenant_id, identity_provider_id, immutable_subject)
);

CREATE TABLE git_identity_binding (
    tenant_id uuid NOT NULL,
    provider varchar(32) NOT NULL CHECK (provider IN ('GITHUB')),
    immutable_git_user_id varchar(255) NOT NULL,
    account_id uuid NOT NULL,
    display_email varchar(320),
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE', 'REVIEW_REQUIRED', 'REVOKED')),
    binding_version bigint NOT NULL DEFAULT 1,
    bound_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id, provider, immutable_git_user_id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES human_account(tenant_id, account_id),
    UNIQUE (tenant_id, provider, account_id)
);

CREATE TABLE git_identity_binding_change (
    tenant_id uuid NOT NULL,
    change_id uuid NOT NULL,
    provider varchar(32) NOT NULL,
    immutable_git_user_id varchar(255) NOT NULL,
    current_account_id uuid NOT NULL,
    replacement_account_id uuid NOT NULL,
    requested_binding_version bigint NOT NULL CHECK (requested_binding_version >= 1),
    resulting_binding_version bigint NOT NULL CHECK (resulting_binding_version = requested_binding_version + 1),
    requested_by_account_id uuid NOT NULL,
    action_request_id uuid NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('REVIEW_REQUIRED','APPROVED','REJECTED','SUPERSEDED')),
    requested_at timestamptz NOT NULL,
    decided_by_account_id uuid,
    decided_at timestamptz,
    PRIMARY KEY (tenant_id, change_id),
    FOREIGN KEY (tenant_id, provider, immutable_git_user_id)
      REFERENCES git_identity_binding(tenant_id, provider, immutable_git_user_id),
    FOREIGN KEY (tenant_id, current_account_id) REFERENCES human_account(tenant_id, account_id),
    FOREIGN KEY (tenant_id, replacement_account_id) REFERENCES human_account(tenant_id, account_id),
    FOREIGN KEY (tenant_id, requested_by_account_id) REFERENCES human_account(tenant_id, account_id),
    CHECK (current_account_id <> replacement_account_id),
    CHECK ((state='REVIEW_REQUIRED' AND decided_at IS NULL AND decided_by_account_id IS NULL)
        OR (state<>'REVIEW_REQUIRED' AND decided_at IS NOT NULL AND decided_by_account_id IS NOT NULL))
);

CREATE TABLE workload_identity (
    tenant_id uuid NOT NULL REFERENCES tenant(tenant_id),
    workload_identity_id uuid NOT NULL,
    purpose varchar(32) NOT NULL CHECK (purpose IN ('REQUIREMENT_PUBLICATION', 'STRICT_MERGE', 'CI_ATTESTATION', 'ARTIFACT_PROVENANCE', 'NOTIFICATION', 'ATTACHMENT_SCANNING')),
    oidc_issuer varchar(1024) NOT NULL,
    immutable_subject varchar(1024) NOT NULL,
    audience varchar(255) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    identity_version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, workload_identity_id),
    UNIQUE (tenant_id, oidc_issuer, immutable_subject, audience),
    UNIQUE (tenant_id, workload_identity_id, purpose)
);

SELECT accord_security.enforce_tenant_table(name::regclass)
FROM unnest(ARRAY[
  'public.identity_provider', 'public.natural_person', 'public.human_account',
  'public.git_identity_binding', 'public.git_identity_binding_change', 'public.workload_identity'
]) AS names(name);
```

Grant runtime DML only after all six explicit `enforce_tenant_table` calls succeed. The change row is the durable review subject; its `change_id` is used as the ActionRequest subject reference and appears in the transactional outbox payload. The replacement account is never recoverable only from an ephemeral message.

- [ ] **Step 4: Define OIDC, SAML, and SCIM adapter contracts**

Create `FederationAdapters.java`:

```java
package com.inforvans.accord.identity;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

record LoginRequest(TenantId tenantId, URI redirectUri, String state, String nonce) {}
record LoginRedirect(URI location, Instant expiresAt) {}
record FederationAssertion(
    String issuer,
    String immutableSubject,
    Instant authenticationTime,
    Set<String> authenticationMethods
) {}

interface OidcAuthenticationAdapter {
    LoginRedirect begin(LoginRequest request);
    FederationAssertion complete(TenantId tenantId, String code, String expectedState, String expectedNonce);
}

interface SamlAuthenticationAdapter {
    LoginRedirect begin(LoginRequest request);
    FederationAssertion consume(TenantId tenantId, String samlResponse, String expectedRequestId);
}

enum ScimOperation { UPSERT_USER, DISABLE_USER, UPSERT_GROUP, REMOVE_GROUP }
record ScimChange(TenantId tenantId, UUID providerId, ScimOperation operation, String externalId, String version, Map<String, String> attributes) {}
record ScimReceipt(String externalId, String appliedVersion, boolean changed) {}
interface ScimProvisioningAdapter { ScimReceipt apply(ScimChange change); }
```

OIDC adapters must validate issuer, audience, signature, nonce, state, `iat`, and `exp`; SAML adapters must validate XML signature, audience, recipient, `InResponseTo`, and time bounds before creating `FederationAssertion`. SCIM uses the provider's immutable external ID and version for idempotency, never email as identity.

- [ ] **Step 5: Define disjoint human and workload principals**

Create `PrincipalIdentity.java`:

```java
package com.inforvans.accord.identity;

import java.util.UUID;

enum WorkloadPurpose { REQUIREMENT_PUBLICATION, STRICT_MERGE, CI_ATTESTATION, ARTIFACT_PROVENANCE, NOTIFICATION, ATTACHMENT_SCANNING }

public sealed interface PrincipalIdentity permits PrincipalIdentity.Human, PrincipalIdentity.Workload {
    record Human(UUID accountId, UUID naturalPersonId, long identityVersion) implements PrincipalIdentity {}
    record Workload(UUID workloadId, WorkloadPurpose purpose, String immutableSubject) implements PrincipalIdentity {
        public Human requireHuman() { throw new HumanPrincipalRequired(); }
    }

    default Human requireHuman() {
        if (this instanceof Human human) return human;
        throw new HumanPrincipalRequired();
    }
}

final class HumanPrincipalRequired extends RuntimeException {
    HumanPrincipalRequired() { super("a human natural person is required"); }
}
```

Create `GitIdentityRepository.java`; it resolves only immutable active bindings and moves a requested account change into review in the same transaction as its outbox event:

```java
package com.inforvans.accord.identity

import com.inforvans.accord.reliability.DomainEvent
import com.inforvans.accord.reliability.ReliableEventStore
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID
import org.jooq.DSLContext

enum GitBindingState { ACTIVE, REVIEW_REQUIRED, REVOKED }

record GitIdentityBinding(
    TenantId tenantId,
    GitProvider provider,
    String immutableGitUserId,
    UUID accountId,
    String displayEmail,
    GitBindingState state,
    long bindingVersion
) {}

final class GitIdentityRepository {
    GitIdentityBinding bind(
        DSLContext tx,
        TenantId tenantId,
        UUID accountId,
        GitProvider provider,
        String immutableGitUserId,
        String displayEmail
    ) {
        tx.execute(
            """INSERT INTO git_identity_binding
               (tenant_id, provider, immutable_git_user_id, account_id, display_email, state)
               VALUES (?, ?, ?, ?, ?, 'ACTIVE')""",
            tenantId.value(), provider.name(), immutableGitUserId, accountId, displayEmail
        );
        return resolve(tx, tenantId, provider, immutableGitUserId);
    }

    GitIdentityBinding resolve(DSLContext tx, TenantId tenantId, GitProvider provider, String immutableGitUserId) {
        var binding = select(tx, tenantId, provider, immutableGitUserId, GitBindingState.ACTIVE);
        if (binding == null) throw new NoSuchElementException("active Git identity binding not found");
        return binding;
    }

    GitIdentityBinding resolveByCommitEmail(DSLContext tx, TenantId tenantId, String email) {
        throw new NoSuchElementException("commit email is not an identity proof");
    }

    GitIdentityBinding requestChange(
        DSLContext tx,
        TenantId tenantId,
        GitProvider provider,
        String immutableGitUserId,
        UUID replacementAccountId,
        long expectedVersion,
        UUID actorId,
        UUID correlationId,
        OffsetDateTime now
    ) {
        if (tx.fetchOne(
                "SELECT 1 FROM human_account WHERE tenant_id=? AND account_id=? AND status='ACTIVE'",
                tenantId.value(), replacementAccountId
            ) == null) throw new IllegalStateException("replacement human account is not active");
        var current = tx.fetchOne(
            """SELECT account_id,binding_version FROM git_identity_binding
               WHERE tenant_id=? AND provider=? AND immutable_git_user_id=?
                 AND state='ACTIVE' AND binding_version=? FOR UPDATE""",
            tenantId.value(), provider.name(), immutableGitUserId, expectedVersion
        );
        if (current == null) throw new IllegalStateException("Git identity binding version conflict");
        var currentAccountId = current.get("account_id", UUID.class);
        if (currentAccountId.equals(replacementAccountId)) throw new IllegalStateException("replacement account is already bound");
        var changeId = UUID.randomUUID();
        var actionRequestId = UUID.randomUUID();

        var changed = tx.execute(
            """UPDATE git_identity_binding
               SET state='REVIEW_REQUIRED', binding_version=binding_version+1
               WHERE tenant_id=? AND provider=? AND immutable_git_user_id=?
                 AND state='ACTIVE' AND binding_version=?""",
            tenantId.value(), provider.name(), immutableGitUserId, expectedVersion
        );
        if (changed != 1) throw new IllegalStateException("Git identity binding version conflict");

        var pending = select(tx, tenantId, provider, immutableGitUserId, GitBindingState.REVIEW_REQUIRED);
        if (pending == null) throw new IllegalStateException("review-required Git identity binding disappeared");
        tx.execute(
            """INSERT INTO git_identity_binding_change
               (tenant_id,change_id,provider,immutable_git_user_id,current_account_id,replacement_account_id,
                requested_binding_version,resulting_binding_version,requested_by_account_id,action_request_id,state,requested_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,'REVIEW_REQUIRED',?)""",
            tenantId.value(), changeId, provider.name(), immutableGitUserId, currentAccountId, replacementAccountId,
            expectedVersion, pending.bindingVersion(), actorId, actionRequestId, now
        );
        var payloadRow = tx.fetchOne(
            """SELECT jsonb_build_object(
                 'change_id', ?, 'action_request_id', ?, 'provider', ?, 'immutable_git_user_id', ?,
                 'current_account_id', ?, 'replacement_account_id', ?,
                 'binding_version', ?
               )::text""",
            changeId, actionRequestId, provider.name(), immutableGitUserId,
            pending.accountId(), replacementAccountId, pending.bindingVersion()
        );
        if (payloadRow == null) throw new IllegalStateException("Git identity review payload was not created");
        var payload = payloadRow.get(0, String.class);
        var aggregateId = UUID.nameUUIDFromBytes(
            (provider.name() + ":" + immutableGitUserId).getBytes(StandardCharsets.UTF_8)
        );
        new ReliableEventStore(tx).append(new DomainEvent(
            UUID.randomUUID(), tenantId.value(), "tenant", tenantId.value().toString(),
            "git_identity_binding", aggregateId, pending.bindingVersion(),
            "identity.git-binding-review-requested", "1.0.0", correlationId,
            correlationId, actorId.toString(), payload, now, "accord.action-request"
        ));
        return pending;
    }

    private GitIdentityBinding select(
        DSLContext tx,
        TenantId tenantId,
        GitProvider provider,
        String immutableGitUserId,
        GitBindingState state
    ) {
        var row = tx.fetchOne(
        """SELECT tenant_id, provider, immutable_git_user_id, account_id, display_email, state, binding_version
           FROM git_identity_binding
           WHERE tenant_id=? AND provider=? AND immutable_git_user_id=? AND state=?""",
        tenantId.value(), provider.name(), immutableGitUserId, state.name()
        );
        if (row == null) return null;
        return new GitIdentityBinding(
            new TenantId(row.get("tenant_id", UUID.class)),
            GitProvider.valueOf(row.get("provider", String.class)),
            row.get("immutable_git_user_id", String.class),
            row.get("account_id", UUID.class),
            row.get("display_email", String.class),
            GitBindingState.valueOf(row.get("state", String.class)),
            row.get("binding_version", Long.class)
        );
    }
}
```

- [ ] **Step 6: Run identity and RLS tests**

Run:

```bash
./gradlew :apps:control-plane:modules:identity:test --tests '*EnterpriseIdentityTest'
./gradlew :apps:control-plane:modules:identity:test --tests '*TenantRlsTest'
```

Expected: PASS. Immutable Git user ID resolves; email lookup fails; a requested remap becomes `REVIEW_REQUIRED`, increments version, emits exactly one outbox event, and stops resolving as active; workload confirmation fails; a tenant cannot see another tenant's provider, account, Git binding, or workload row.

- [ ] **Step 7: Commit enterprise identity contracts**

```bash
git add database/control-plane/migrations/V012__enterprise_identities.sql apps/control-plane/modules/identity
git commit -m "feat: add enterprise and workload identities"
```

### Task 5: Implement Side-Aware RBAC And Highest-Principal Rules

**Files:**
- Create: `database/control-plane/migrations/V013__side_aware_rbac.sql`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/AuthorizationModel.java`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/JooqAuthorizationService.java`
- Create: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/AuthorizationPostgreSqlTest.java`
- Create: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/AuthorizationServiceTest.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/security/VerifiedRequestIdentity.java`
- Modify: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationController.java`
- Modify: `apps/control-plane/api/src/test/java/com/inforvans/accord/controlplane/http/ContractValidationApiTest.java`
- Modify: `apps/control-plane/modules/reliability/src/main/java/com/inforvans/accord/reliability/JooqCommandGate.java`

- [ ] **Step 1: Write failing administrator, side, scope, and repository tests**

Create `AuthorizationServiceTest.java`:

```java
package com.inforvans.accord.authorization;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationServiceTest extends AuthorizationPostgreSqlTest {
    @Test
    void administratorHasNoImplicitBusinessOrDevelopmentAuthority() {
        var fixture = seedProject(DeliveryMode.STANDARD);
        bind(fixture.admin(), Role.PROJECT_ADMIN, null, new Scope.Project(fixture.projectId()));
        assertThat(decide(fixture.admin(), Action.FINAL_BUSINESS_CONFIRMATION).deniedBy()).isEqualTo(DenyReason.ROLE_DOES_NOT_ALLOW_ACTION);
        assertThat(decide(fixture.admin(), Action.FINAL_DEVELOPMENT_CONFIRMATION).deniedBy()).isEqualTo(DenyReason.ROLE_DOES_NOT_ALLOW_ACTION);
    }

    @Test
    void standardFinalConfirmationBelongsToCurrentSidePrincipal() {
        var fixture = seedProject(DeliveryMode.STANDARD);
        bind(fixture.businessPrincipal(), Role.BUSINESS_PRINCIPAL, Side.BUSINESS, new Scope.Project(fixture.projectId()));
        bind(fixture.businessEditor(), Role.BUSINESS_EDITOR, Side.BUSINESS, new Scope.Project(fixture.projectId()));
        setHighestPrincipal(Side.BUSINESS, fixture.businessPrincipal());
        assertThat(decide(fixture.businessPrincipal(), Action.FINAL_BUSINESS_CONFIRMATION).allowed()).isTrue();
        assertThat(decide(fixture.businessEditor(), Action.FINAL_BUSINESS_CONFIRMATION).deniedBy()).isEqualTo(DenyReason.NOT_CURRENT_SIDE_PRINCIPAL);
    }

    @Test
    void repositoryActionFailsWhenImmutableBindingDiffers() {
        var fixture = seedProject(DeliveryMode.STANDARD);
        bind(fixture.developmentPrincipal(), Role.DEVELOPMENT_PRINCIPAL, Side.DEVELOPMENT, new Scope.Project(fixture.projectId()));
        setHighestPrincipal(Side.DEVELOPMENT, fixture.developmentPrincipal());
        var base = request(fixture.developmentPrincipal(), Action.AUTHORIZE_REPOSITORY_OPERATION);
        var request = new AuthorizationRequest(base.tenantId(), base.projectId(), base.principal(), base.action(),
            new ResourceTarget.Repository(fixture.projectId(), "99999"), base.now(), base.delegationId(), base.freshAuthSessionId());
        assertThat(service.decide(request).deniedBy()).isEqualTo(DenyReason.REPOSITORY_BINDING_MISMATCH);
    }

    @Test
    void authorizedCommandAndDatabaseEffectsShareOneRollbackBoundary() {
        var fixture = seedProject(DeliveryMode.STANDARD);
        bind(fixture.admin(), Role.PROJECT_ADMIN, null, new Scope.Project(fixture.projectId()));
        assertThatThrownBy(() -> service.authorizeAndExecute(request(fixture.admin(), Action.MANAGE_PROJECT), (tx, ignored) -> {
            tx.execute("UPDATE project SET name='must-roll-back' WHERE tenant_id=? AND project_id=?", tenantId.value(), fixture.projectId());
            throw new IllegalStateException("force command rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(migratorDsl.fetchOne("SELECT name FROM project WHERE tenant_id=? AND project_id=?", tenantId.value(), fixture.projectId()).get(0, String.class))
            .isEqualTo("Accord");
    }
}
```

In `ContractValidationApiTest`, authenticate with a test `VerifiedRequestIdentity` for tenant A and add this negative case; a string placed in `Authorization` is not an actor identity:

```java
@Test
void forgedTenantHeaderAndRawBearerCannotChangeServerDerivedScope() throws Exception {
    postAsVerifiedTenantA(projectA, validBody, java.util.Map.of("X-Accord-Tenant", tenantB.toString()))
        .andExpect(status().isCreated());
    assertThat(tenantScopedCount(tenantA, "aggregate_head")).isOne();
    assertThat(tenantScopedCount(tenantB, "aggregate_head")).isZero();

    mvc.perform(post("/v1/projects/{projectId}/contract-validations/{validationId}", projectA, validationId)
        .header("Authorization", "Bearer attacker-controlled-account-id")
        .header("Idempotency-Key", "idem-forged-0001")
        .header("If-Match", "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .content(validBody))
        .andExpect(status().isUnauthorized());
}
```

Create `AuthorizationPostgreSqlTest.java` in the same red step. It owns one PostgreSQL 17.5 container per test class, runs all control-plane Flyway migrations, clears authorization/identity/project rows before each test, and seeds deterministic natural-person/account identities. Its core implementation is:

```java
package com.inforvans.accord.authorization

import com.inforvans.accord.database.ControlPlaneTestRoles
import com.inforvans.accord.identity.PrincipalIdentity
import com.inforvans.accord.identity.TenantId
import com.inforvans.accord.identity.TenantTransactions
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.util.UUID
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AuthorizationPostgreSqlTest {
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5");
    protected DSLContext migratorDsl;
    protected DSLContext appDsl;
    protected TenantTransactions tenantTransactions;
    protected JooqAuthorizationService service;
    private HikariDataSource appDataSource;
    protected final TenantId tenantId = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
    protected final Instant now = Instant.parse("2026-07-24T10:00:00Z");

    record Fixture(
        UUID projectId,
        PrincipalIdentity.Human admin,
        PrincipalIdentity.Human businessPrincipal,
        PrincipalIdentity.Human businessEditor,
        PrincipalIdentity.Human businessRequester,
        PrincipalIdentity.Human developmentPrincipal,
        PrincipalIdentity.Human businessAcceptanceOwner
    ) {}

    @BeforeAll
    void startAuthorizationDatabase() {
        postgres.start();
        ControlPlaneTestRoles.bootstrap(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("filesystem:../../../../database/control-plane/migrations").load().migrate();
        migratorDsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), SQLDialect.POSTGRES);
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername("accord_api");
        config.setPassword("api-test");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setPoolName("authorization-api");
        appDataSource = new HikariDataSource(config);
        appDsl = DSL.using(appDataSource, SQLDialect.POSTGRES);
        tenantTransactions = new TenantTransactions(appDsl);
    }

    @BeforeEach
    void resetAuthorizationDatabase() {
        migratorDsl.execute(
            """TRUNCATE side_principal_binding, role_binding, project_membership,
               project_authorization_policy, git_identity_binding, human_account, natural_person,
               identity_provider, repository_binding, project, tenant RESTART IDENTITY CASCADE"""
        );
        service = new JooqAuthorizationService(tenantTransactions);
    }

    @AfterAll
    void stopAuthorizationDatabase() {
        appDsl.close(); appDataSource.close(); migratorDsl.close(); postgres.stop();
    }

    protected Fixture seedProject(DeliveryMode mode) {
        var projectId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        var providerId = UUID.fromString("21000000-0000-0000-0000-000000000001");
        migratorDsl.execute("INSERT INTO tenant(tenant_id,slug,display_name,status) VALUES (?,?,?,'ACTIVE')", tenantId.value(), "tenant-auth", "Authorization Tenant");
        migratorDsl.execute("INSERT INTO project(tenant_id,project_id,name,delivery_mode,status) VALUES (?,?,?,?,'ACTIVE')", tenantId.value(), projectId, "Accord", mode.name());
        migratorDsl.execute("INSERT INTO repository_binding(tenant_id,repository_binding_id,project_id,provider,immutable_repository_id,display_name,state) VALUES (?,?,?,'GITHUB','77831','accord','ACTIVE')", tenantId.value(), UUID.fromString("22000000-0000-0000-0000-000000000001"), projectId);
        migratorDsl.execute("INSERT INTO identity_provider(tenant_id,identity_provider_id,kind,issuer_or_entity_id,configuration_secret_ref,state) VALUES (?,?,'OIDC','https://idp.example.test','secret://test','ACTIVE')", tenantId.value(), providerId);
        migratorDsl.execute("INSERT INTO project_authorization_policy(tenant_id,project_id) VALUES (?,?)", tenantId.value(), projectId);
        java.util.function.IntFunction<PrincipalIdentity.Human> human = suffix -> {
            var digits = String.format("%012d", suffix);
            var personId = UUID.fromString("30000000-0000-0000-0000-" + digits);
            var accountId = UUID.fromString("31000000-0000-0000-0000-" + digits);
            migratorDsl.execute("INSERT INTO natural_person(tenant_id,natural_person_id,status) VALUES (?,?,'ACTIVE')", tenantId.value(), personId);
            migratorDsl.execute("INSERT INTO human_account(tenant_id,account_id,natural_person_id,identity_provider_id,immutable_subject,display_name,status) VALUES (?,?,?,?,?,?,'ACTIVE')", tenantId.value(), accountId, personId, providerId, "subject-" + suffix, "Actor " + suffix);
            migratorDsl.execute("INSERT INTO project_membership(tenant_id,project_id,account_id,member_kind,state,starts_at) VALUES (?,?,?,'INTERNAL','ACTIVE',?)", tenantId.value(), projectId, accountId, now);
            return new PrincipalIdentity.Human(accountId, personId, 1);
        }
        return new Fixture(projectId, human.apply(1), human.apply(2), human.apply(3), human.apply(4), human.apply(5), human.apply(6));
    }

    protected void bind(PrincipalIdentity.Human actor, Role role, Side side, Scope scope) {
        var projectId = ((Scope.Project) scope).projectId();
        migratorDsl.execute(
            """INSERT INTO role_binding(tenant_id,role_binding_id,project_id,account_id,side,role,scope_type,scope_id,starts_at)
               VALUES (?,?,?,?,?,?, 'PROJECT', ?, ?)""",
            tenantId.value(), UUID.randomUUID(), projectId, actor.accountId(), side == null ? null : side.name(), role.name(), projectId.toString(), now
        );
    }

    protected void setHighestPrincipal(Side side, PrincipalIdentity.Human actor) {
        var projectId = requireProjectId();
        migratorDsl.execute("UPDATE side_principal_binding SET ended_at=? WHERE tenant_id=? AND project_id=? AND side=? AND ended_at IS NULL", now, tenantId.value(), projectId, side.name());
        migratorDsl.execute("INSERT INTO side_principal_binding(tenant_id,project_id,side,account_id,natural_person_id,starts_at,version) VALUES (?,?,?,?,?,?,1)", tenantId.value(), projectId, side.name(), actor.accountId(), actor.naturalPersonId(), now);
    }

    protected AuthorizationRequest request(PrincipalIdentity.Human actor, Action action) {
        var projectId = requireProjectId();
        return new AuthorizationRequest(tenantId, projectId, actor, action, new ResourceTarget.Project(projectId), now, null, null);
    }

    protected AuthorizationDecision decide(PrincipalIdentity.Human actor, Action action) {
        return service.decide(request(actor, action));
    }

    private UUID requireProjectId() {
        var row = migratorDsl.fetchOne("SELECT project_id FROM project WHERE tenant_id=?", tenantId.value());
        if (row == null) throw new IllegalStateException("fixture project missing");
        return row.get(0, UUID.class);
    }
}
```

The fixture deliberately uses the migration owner only to seed prerequisites. Task 3 remains the owning RLS test through the `accord_api` role; authorization tests never substitute H2 or an in-memory policy evaluator.

- [ ] **Step 2: Run the test and verify authorization types are absent**

Run: `./gradlew :apps:control-plane:modules:authorization:test --tests '*AuthorizationServiceTest'`

Expected: FAIL because `Role`, `Action`, `Side`, and `JooqAuthorizationService` are unresolved.

- [ ] **Step 3: Add normalized membership, role, principal, and policy tables**

Create `V013__side_aware_rbac.sql`:

```sql
CREATE TABLE project_authorization_policy (
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    allow_same_side_role_overlap boolean NOT NULL DEFAULT true,
    allow_cross_side_person_standard boolean NOT NULL DEFAULT false,
    fresh_auth_window_seconds integer NOT NULL DEFAULT 300 CHECK (fresh_auth_window_seconds BETWEEN 60 AND 900),
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, project_id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, project_id)
);

CREATE TABLE project_membership (
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    account_id uuid NOT NULL,
    member_kind varchar(16) NOT NULL CHECK (member_kind IN ('INTERNAL', 'EXTERNAL_VENDOR')),
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE', 'SUSPENDED', 'ENDED')),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, project_id, account_id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, project_id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES human_account(tenant_id, account_id),
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE role_binding (
    tenant_id uuid NOT NULL,
    role_binding_id uuid NOT NULL,
    project_id uuid,
    account_id uuid NOT NULL,
    side varchar(16) CHECK (side IN ('BUSINESS', 'DEVELOPMENT')),
    role varchar(40) NOT NULL CHECK (role IN (
      'TENANT_ADMIN', 'PROJECT_ADMIN', 'BUSINESS_PRINCIPAL', 'DEVELOPMENT_PRINCIPAL',
      'BUSINESS_REQUESTER', 'BUSINESS_EDITOR', 'DEVELOPMENT_LEAD', 'DEVELOPMENT_ASSESSOR',
      'WORK_ITEM_OWNER', 'BUSINESS_ACCEPTANCE_OWNER', 'AUDITOR'
    )),
    scope_type varchar(24) NOT NULL CHECK (scope_type IN ('TENANT', 'PROJECT', 'BUSINESS_DOMAIN', 'REQUIREMENT', 'DELIVERY_BATCH', 'WORK_ITEM')),
    scope_id varchar(255) NOT NULL,
    starts_at timestamptz NOT NULL,
    expires_at timestamptz,
    revoked_at timestamptz,
    binding_version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, role_binding_id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, project_id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES human_account(tenant_id, account_id),
    CHECK (expires_at IS NULL OR expires_at > starts_at),
    CHECK (
      (role IN ('BUSINESS_PRINCIPAL', 'BUSINESS_REQUESTER', 'BUSINESS_EDITOR', 'BUSINESS_ACCEPTANCE_OWNER') AND side = 'BUSINESS')
      OR (role IN ('DEVELOPMENT_PRINCIPAL', 'DEVELOPMENT_LEAD', 'DEVELOPMENT_ASSESSOR', 'WORK_ITEM_OWNER') AND side = 'DEVELOPMENT')
      OR (role IN ('TENANT_ADMIN', 'PROJECT_ADMIN', 'AUDITOR') AND side IS NULL)
    )
);

CREATE TABLE side_principal_binding (
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    side varchar(16) NOT NULL CHECK (side IN ('BUSINESS', 'DEVELOPMENT')),
    account_id uuid NOT NULL,
    natural_person_id uuid NOT NULL,
    starts_at timestamptz NOT NULL,
    ended_at timestamptz,
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, project_id, side, version),
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, project_id),
    FOREIGN KEY (tenant_id, account_id, natural_person_id)
      REFERENCES human_account(tenant_id, account_id, natural_person_id)
);
CREATE UNIQUE INDEX one_current_principal_per_side
    ON side_principal_binding (tenant_id, project_id, side) WHERE ended_at IS NULL;

SELECT accord_security.enforce_tenant_table(name::regclass)
FROM unnest(ARRAY[
  'public.project_authorization_policy', 'public.project_membership',
  'public.role_binding', 'public.side_principal_binding'
]) AS names(name);
```

Grant runtime DML only after all four RLS calls. Same-side overlap is represented by multiple active `role_binding` rows and governed by `project_authorization_policy`; it never creates extra final-confirmation clicks. The composite human-account foreign key prevents an account/natural-person pair from being forged.

- [ ] **Step 4: Define roles, sides, targets, and deterministic denial reasons**

Create `AuthorizationModel.java`:

```java
package com.inforvans.accord.authorization;

import com.inforvans.accord.identity.PrincipalIdentity;
import com.inforvans.accord.identity.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

enum DeliveryMode { STANDARD, STRICT }
enum Side { BUSINESS, DEVELOPMENT }
enum Role {
    TENANT_ADMIN, PROJECT_ADMIN, BUSINESS_PRINCIPAL, DEVELOPMENT_PRINCIPAL,
    BUSINESS_REQUESTER, BUSINESS_EDITOR, DEVELOPMENT_LEAD, DEVELOPMENT_ASSESSOR,
    WORK_ITEM_OWNER, BUSINESS_ACCEPTANCE_OWNER, AUDITOR
}
enum Action {
    MANAGE_TENANT, MANAGE_PROJECT, EDIT_BUSINESS_CONTENT, SCORE_DEVELOPMENT,
    FINAL_DEVELOPMENT_CONFIRMATION, FINAL_BUSINESS_CONFIRMATION,
    ACCEPT_CANDIDATE, AUTHORIZE_REPOSITORY_OPERATION, VIEW_AUDIT
}
sealed interface Scope permits Scope.Tenant, Scope.Project, Scope.Resource {
    record Tenant(TenantId tenantId) implements Scope {}
    record Project(UUID projectId) implements Scope {}
    record Resource(String type, String id) implements Scope {}
}
sealed interface ResourceTarget permits ResourceTarget.Project, ResourceTarget.Repository, ResourceTarget.Scoped {
    record Project(UUID projectId) implements ResourceTarget {}
    record Repository(UUID projectId, String immutableRepositoryId) implements ResourceTarget {}
    record Scoped(UUID projectId, String type, String id) implements ResourceTarget {}
}
enum DenyReason {
    TENANT_OR_MEMBERSHIP_INACTIVE, IDENTITY_VERSION_STALE, REPOSITORY_BINDING_MISMATCH,
    ROLE_DOES_NOT_ALLOW_ACTION, SCOPE_DOES_NOT_CONTAIN_TARGET, NOT_CURRENT_SIDE_PRINCIPAL,
    DELEGATION_INVALID, SEPARATION_OF_DUTIES, FRESH_AUTH_REQUIRED, MACHINE_CANNOT_ACT_AS_HUMAN
}
record AuthorizationRequest(
    TenantId tenantId,
    UUID projectId,
    PrincipalIdentity principal,
    Action action,
    ResourceTarget target,
    Instant now,
    UUID delegationId,
    UUID freshAuthSessionId
) {}
record AuthorizationDecision(boolean allowed, DenyReason deniedBy, List<UUID> evidenceIds) {}
```

- [ ] **Step 5: Implement the authorization formula in fixed order**

Create `JooqAuthorizationService.java` with one read-only tenant transaction that loads membership, account and natural-person status, identity/binding versions, current repository binding, all active role bindings, current side principals, project policy, optional delegation, and optional fresh-auth proof. Evaluate in this exact fail-closed order:

```java
AuthorizationDecision decide(AuthorizationRequest request) {
    return tenantTransactions.read(request.tenantId(), tx -> decide(tx, request));
}

AuthorizationDecision decide(DSLContext tx, AuthorizationRequest request) {
    if (!(request.principal() instanceof PrincipalIdentity.Human human)) {
        return denied(DenyReason.MACHINE_CANNOT_ACT_AS_HUMAN);
    }
    var snapshot = repository.loadSnapshot(tx, request, human);
    if (snapshot == null) return denied(DenyReason.TENANT_OR_MEMBERSHIP_INACTIVE);
    if (snapshot.identityVersion() != human.identityVersion()) return denied(DenyReason.IDENTITY_VERSION_STALE);
    if (request.target() instanceof ResourceTarget.Repository target &&
        (snapshot.repositoryBinding() == null ||
         !snapshot.repositoryBinding().matches(request.tenantId(), request.projectId(), target.immutableRepositoryId()))) {
        return denied(DenyReason.REPOSITORY_BINDING_MISMATCH)
    }
    var bindings = snapshot.roleBindings().stream()
        .filter(binding -> binding.activeAt(request.now()) && binding.allows(request.action()))
        .toList();
    if (bindings.isEmpty()) return denied(DenyReason.ROLE_DOES_NOT_ALLOW_ACTION);
    if (bindings.stream().noneMatch(binding -> binding.scope().contains(request.target()))) {
        return denied(DenyReason.SCOPE_DOES_NOT_CONTAIN_TARGET);
    }
    if (finalPrincipalActions.contains(request.action()) &&
        !human.accountId().equals(snapshot.currentPrincipal(request.action()))) {
        return denied(DenyReason.NOT_CURRENT_SIDE_PRINCIPAL);
    }
    if (!snapshot.validDelegation(request)) return denied(DenyReason.DELEGATION_INVALID);
    if (!snapshot.separationPasses(request, human)) return denied(DenyReason.SEPARATION_OF_DUTIES);
    if (!snapshot.freshAuthPasses(request, human)) return denied(DenyReason.FRESH_AUTH_REQUIRED);
    return new AuthorizationDecision(true, null, snapshot.evidenceIds());
}

<T> T authorizeAndExecute(
    AuthorizationRequest request,
    java.util.function.BiFunction<DSLContext, AuthorizationDecision, T> command
) {
    return tenantTransactions.write(request.tenantId(), tx -> {
        var decision = decide(tx, request);
        if (!decision.allowed()) throw new AuthorizationDenied(decision.deniedBy());
        return command.apply(tx, decision);
    });
}
```

`loadSnapshot` executes the repository binding query below through the supplied transaction; neither `AuthorizationRequest` nor an HTTP DTO contains a `boundRepositoryId` field:

```sql
SELECT repository_binding_id, immutable_repository_id, version, state
FROM repository_binding
WHERE tenant_id=? AND project_id=? AND state='ACTIVE'
FOR SHARE;
```

Define `finalPrincipalActions` as development confirmation, business confirmation, and strict repository authorization. The role-action map grants management to administrators but does not include confirmation, assessment override, or acceptance in administrator actions. `Scope.contains` compares project and exact resource ancestry loaded from the same tenant; no client-provided scope ancestry is trusted. Public read-only checks may call `decide`; every protected mutation, including confirmation, proof consumption, acceptance, abort, and break-glass, must use `authorizeAndExecute` or its existing-transaction `decide(tx, request)` overload.

Create `VerifiedRequestIdentity.java` as the only principal type accepted by controllers. The OIDC/SAML security converter creates it only after signature/issuer/audience checks and a server-side `(issuer, immutable_subject, session_tenant_id) -> active human_account` lookup:

```java
public record VerifiedRequestIdentity(
    TenantId tenantId,
    PrincipalIdentity.Human human,
    String issuer,
    String immutableSubject,
    Set<String> authenticationMethods
) {}
```

Change the validation route to `/v1/projects/{projectId}/contract-validations/{validationId}` and remove both `X-Accord-Tenant` and raw `Authorization` parameters. Foundation already exposes only transaction-first `JooqCommandGate` methods and Reliability remains independent of Identity; do not add a pool-owning overload, a `TenantTransactions` delegate, or any Reliability-to-Identity dependency. Replace Foundation's temporary `FoundationVerifiedPrincipal`, `FoundationTenantTransactions`, and temporary security chain with `VerifiedRequestIdentity`, the Identity-owned `TenantTransactions`, and the production authentication converter. The controller's mutation core is:

```java
ResponseEntity<String> validate(
    @AuthenticationPrincipal VerifiedRequestIdentity identity,
    @PathVariable UUID projectId,
    @PathVariable UUID validationId,
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @RequestHeader("If-Match") String ifMatch,
    @RequestBody ValidationRequest request
) throws JsonProcessingException {
    var authorizationRequest = new AuthorizationRequest(
        identity.tenantId(), projectId, identity.human(), Action.MANAGE_PROJECT,
        new ResourceTarget.Project(projectId), clock.instant(), null, null
    );
    return authorization.authorizeAndExecute(authorizationRequest, (tx, decision) -> {
        var digest = CanonicalJson.sha256(write(request));
        var key = new CommandKey(identity.tenantId().value(), identity.human().accountId().toString(),
            "contract-validations.create", idempotencyKey);
        var claim = gate.claim(tx, key, digest, commandOwner, Duration.ofMinutes(2));
        if (claim instanceof Claim.Replay replay) {
            return ResponseEntity.status(replay.result().status())
                .headers(headers(replay.result().headers())).body(replay.result().body());
        }
        if (claim instanceof Claim.RequestConflict conflict) throw new IdempotencyKeyReused(conflict.originalDigest());
        if (claim instanceof Claim.InProgress progress) throw new CommandInProgress(progress.leaseUntil());

        var expected = new ExpectedVersion(Long.parseLong(ifMatch.replace("\"", "")));
        var version = gate.advance(tx, identity.tenantId().value(), "contract-validation", validationId, expected);
        var response = new ValidationResponse(validationId, true, CanonicalJson.sha256(write(request.document())), version);
        var body = writeString(response);
        var stored = new StoredHttpResult(201, java.util.Map.of(
            "ETag", "\"" + version + "\"", "Content-Type", "application/json"), body);
        var eventId = UUID.randomUUID();
        new ReliableEventStore(tx).append(new DomainEvent(
            eventId, identity.tenantId().value(), "project", projectId.toString(), "contract-validation", validationId,
            version, "contract-validation.authorized", "1.0.0", eventId, eventId,
            identity.human().accountId().toString(), writeString(java.util.Map.of("decision_evidence_ids", decision.evidenceIds())),
            OffsetDateTime.now(clock), "accord.audit"
        ));
        gate.complete(tx, key, commandOwner, stored, "contract-validation", validationId, version, Duration.ofHours(24));
        return ResponseEntity.status(201).eTag(Long.toString(version)).contentType(MediaType.APPLICATION_JSON).body(body);
    });
}
```

The verified identity supplies tenant and actor; `projectId` is merely a requested target and current membership/role is loaded under RLS. Claim, CAS, stored idempotency receipt, authorization/audit event, and outbox insert commit or roll back together. The HTTP header named `X-Accord-Tenant`, if supplied, is ignored and never enters a command key or SQL predicate.

- [ ] **Step 6: Run RBAC, transaction, and HTTP identity tests**

Run:

```bash
./gradlew :apps:control-plane:modules:authorization:test --tests '*AuthorizationServiceTest'
./gradlew :apps:control-plane:api:test --tests '*ContractValidationApiTest'
```

Expected: PASS. Admin-only bindings cannot confirm; only the current side principal can perform a standard final confirmation; forged repository identity is rejected against the active database binding; an authorized callback failure rolls back; same-side roles overlap only when policy allows; forged tenant headers cannot change scope and an unverified bearer string receives 401.

- [ ] **Step 7: Commit side-aware authorization**

```bash
git add database/control-plane/migrations/V013__side_aware_rbac.sql apps/control-plane/modules/authorization apps/control-plane/modules/reliability apps/control-plane/api
git commit -m "feat: enforce side-aware scoped RBAC"
```

### Task 6: Enforce Standard And Strict Final-Confirmation Separation

**Files:**
- Create: `database/control-plane/migrations/V013_1__confirmation_receipts.sql`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/ConfirmationPolicy.java`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/ConfirmationCommandService.java`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/ConfirmationModuleApi.java`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/ConfirmationReceiptRepository.java`
- Modify: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/AuthorizationPostgreSqlTest.java`
- Create: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/ConfirmationPolicyTest.java`

- [ ] **Step 1: Write failing standard and strict separation tests**

Create `ConfirmationPolicyTest.java`:

```java
package com.inforvans.accord.authorization

import java.util.UUID
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmationPolicyTest extends AuthorizationPostgreSqlTest {
    private final String revision = "sha256:" + "a".repeat(64);
    private final UUID personA = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private final UUID personB = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Test
    void strictBusinessConfirmationRequiresPriorDevelopmentConfirmationByAnotherPerson() {
        var development = receipt(Side.DEVELOPMENT, personA);
        assertThat(ConfirmationPolicy.authorize(DeliveryMode.STRICT, Side.BUSINESS, personA, revision, java.util.List.of(development), false)).isEqualTo(ConfirmationDenyReason.SAME_NATURAL_PERSON);
        assertThat(ConfirmationPolicy.authorize(DeliveryMode.STRICT, Side.BUSINESS, personB, revision, java.util.List.of(development), false)).isNull();
    }

    @Test
    void receiptForAnotherSubjectDigestNeverSatisfiesOrdering() {
        var original = receipt(Side.DEVELOPMENT, personA);
        var development = new ConfirmationReceiptRef(original.receiptId(), original.confirmationSequence(), original.side(), original.naturalPersonId(), "sha256:" + "b".repeat(64), original.currentValidity());
        assertThat(ConfirmationPolicy.authorize(DeliveryMode.STRICT, Side.BUSINESS, personB, revision, java.util.List.of(development), false)).isEqualTo(ConfirmationDenyReason.DEVELOPMENT_CONFIRMATION_MISSING);
    }

    @Test
    void standardCrossSideOverlapFollowsExplicitTenantPolicy() {
        assertThat(ConfirmationPolicy.authorize(DeliveryMode.STANDARD, Side.BUSINESS, personA, revision, java.util.List.of(receipt(Side.DEVELOPMENT, personA)), false)).isEqualTo(ConfirmationDenyReason.SAME_NATURAL_PERSON);
        assertThat(ConfirmationPolicy.authorize(DeliveryMode.STANDARD, Side.BUSINESS, personA, revision, java.util.List.of(receipt(Side.DEVELOPMENT, personA)), true)).isNull();
    }

    @Test
    void authorizationReceiptAndAuditLinkEventCommitAndRollbackAtomically() {
        var fixture = seedProject(DeliveryMode.STANDARD);
        bind(fixture.developmentPrincipal(), Role.DEVELOPMENT_PRINCIPAL, Side.DEVELOPMENT, new Scope.Project(fixture.projectId()));
        setHighestPrincipal(Side.DEVELOPMENT, fixture.developmentPrincipal());
        var command = confirmationCommand(fixture.developmentPrincipal(), Side.DEVELOPMENT, revision);
        var receiptRef = confirmations.confirm(command);
        var stored = receiptById(receiptRef.receiptId());
        assertThat(stored.accountId()).isEqualTo(fixture.developmentPrincipal().accountId());
        assertThat(stored.naturalPersonId()).isEqualTo(fixture.developmentPrincipal().naturalPersonId());
        assertThat(evidenceCount("confirmation_receipt", receiptRef.receiptId())).isOne();
        assertThat(evidenceCount("domain_event", stored.authorizationEventId())).isOne();

        assertThatThrownBy(() -> failingConfirmations.confirm(command.withSubjectDigest("sha256:" + "c".repeat(64)))).isInstanceOf(RuntimeException.class);
        assertThat(receiptCount("sha256:" + "c".repeat(64))).isZero();
    }

    @Test
    void domainContinuationFailureRollsBackReceiptNonceAndIdempotency() {
        var fixture = seedProject(DeliveryMode.STRICT);
        bind(fixture.developmentPrincipal(), Role.DEVELOPMENT_PRINCIPAL, Side.DEVELOPMENT, new Scope.Project(fixture.projectId()));
        setHighestPrincipal(Side.DEVELOPMENT, fixture.developmentPrincipal());
        var command = confirmationCommand(fixture.developmentPrincipal(), Side.DEVELOPMENT, revision);
        assertThatThrownBy(() -> confirmations.confirm(command, (ignoredTx, ignoredReceipt) -> { throw new DomainLinkWriteFailed(); })).isInstanceOf(DomainLinkWriteFailed.class);
        assertThat(receiptCount(revision)).isZero();
        assertThat(nonceConsumed(command.nonce())).isFalse();
        assertThat(idempotencyResultCount(command.idempotencyKey())).isZero();
    }

    private ConfirmationReceiptRef receipt(Side side, UUID person) {
        return new ConfirmationReceiptRef(UUID.randomUUID(), 1, side, person, revision, ConfirmationReceiptValidity.ACTIVE);
    }
}
```

- [ ] **Step 2: Run the test and verify confirmation policy is missing**

Run: `./gradlew :apps:control-plane:modules:authorization:test --tests '*ConfirmationPolicyTest'`

Expected: FAIL because `ConfirmationPolicy` and receipt types are unresolved.

- [ ] **Step 3: Implement exact-revision, ordered, natural-person separation**

Create `ConfirmationPolicy.java`:

```java
package com.inforvans.accord.authorization;

import java.util.List;
import java.util.UUID;

enum ConfirmationReceiptValidity { ACTIVE, INVALID }
record ConfirmationReceiptRef(
    UUID receiptId,
    long confirmationSequence,
    Side side,
    UUID naturalPersonId,
    String subjectDigest,
    ConfirmationReceiptValidity currentValidity
) {}
record ConfirmationSubject(
    UUID projectId,
    String objectType,
    UUID objectId,
    long objectVersion,
    String subjectDigest
) {}
enum ConfirmationDenyReason { DEVELOPMENT_CONFIRMATION_MISSING, SAME_NATURAL_PERSON, WRONG_CONFIRMATION_ORDER }

final class ConfirmationPolicy {
    private ConfirmationPolicy() {}

    static ConfirmationDenyReason authorize(
        DeliveryMode mode,
        Side side,
        UUID naturalPersonId,
        String subjectDigest,
        List<ConfirmationReceiptRef> receipts,
        boolean allowStandardCrossSidePerson
    ) {
        if (side == Side.DEVELOPMENT) {
            return receipts.stream().anyMatch(receipt ->
                receipt.side() == Side.BUSINESS && receipt.subjectDigest().equals(subjectDigest) &&
                receipt.currentValidity() == ConfirmationReceiptValidity.ACTIVE)
                ? ConfirmationDenyReason.WRONG_CONFIRMATION_ORDER : null;
        }
        var development = receipts.stream()
            .filter(receipt -> receipt.side() == Side.DEVELOPMENT && receipt.subjectDigest().equals(subjectDigest) &&
                receipt.currentValidity() == ConfirmationReceiptValidity.ACTIVE)
            .max(java.util.Comparator.comparingLong(ConfirmationReceiptRef::confirmationSequence))
            .orElse(null);
        if (development == null) return ConfirmationDenyReason.DEVELOPMENT_CONFIRMATION_MISSING;
        if (development.naturalPersonId().equals(naturalPersonId) &&
            (mode == DeliveryMode.STRICT || !allowStandardCrossSidePerson)) {
            return ConfirmationDenyReason.SAME_NATURAL_PERSON;
        }
        return null;
    }
}
```

Create `V013_1__confirmation_receipts.sql`:

```sql
CREATE TABLE confirmation_receipt (
    tenant_id uuid NOT NULL,
    receipt_id uuid NOT NULL,
    project_id uuid NOT NULL,
    subject_type varchar(64) NOT NULL,
    subject_id uuid NOT NULL,
    subject_version bigint NOT NULL CHECK (subject_version >= 1),
    subject_digest char(71) NOT NULL CHECK (subject_digest ~ '^sha256:[0-9a-f]{64}$'),
    confirmation_sequence bigint NOT NULL CHECK (confirmation_sequence >= 1),
    side varchar(16) NOT NULL CHECK (side IN ('BUSINESS','DEVELOPMENT')),
    action varchar(64) NOT NULL CHECK (action IN ('FINAL_BUSINESS_CONFIRMATION','FINAL_DEVELOPMENT_CONFIRMATION')),
    account_id uuid NOT NULL,
    natural_person_id uuid NOT NULL,
    role_binding_id uuid NOT NULL,
    role_binding_version bigint NOT NULL CHECK (role_binding_version >= 1),
    delegation_id uuid,
    project_policy_version bigint NOT NULL CHECK (project_policy_version >= 1),
    authorization_context_digest char(71) NOT NULL CHECK (authorization_context_digest ~ '^sha256:[0-9a-f]{64}$'),
    fresh_auth_session_id uuid,
    authentication_strength varchar(32) NOT NULL,
    authorization_signature_digest char(71) NOT NULL CHECK (authorization_signature_digest ~ '^sha256:[0-9a-f]{64}$'),
    authorization_event_id uuid NOT NULL,
    confirmed_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, receipt_id),
    UNIQUE (tenant_id, receipt_id, confirmation_sequence),
    UNIQUE (tenant_id, project_id, subject_type, subject_id, subject_version, subject_digest, side, confirmation_sequence),
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, project_id),
    FOREIGN KEY (tenant_id, account_id, natural_person_id)
      REFERENCES human_account(tenant_id, account_id, natural_person_id),
    FOREIGN KEY (tenant_id, role_binding_id) REFERENCES role_binding(tenant_id, role_binding_id),
    FOREIGN KEY (tenant_id, authorization_event_id) REFERENCES domain_event(tenant_id, event_id)
      DEFERRABLE INITIALLY DEFERRED
);
SELECT accord_security.enforce_tenant_table('public.confirmation_receipt'::regclass);
GRANT SELECT, INSERT ON confirmation_receipt TO accord_api, accord_worker;
REVOKE UPDATE, DELETE, TRUNCATE ON confirmation_receipt FROM accord_api, accord_worker;

CREATE FUNCTION reject_confirmation_receipt_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'confirmation receipts are append-only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER confirmation_receipt_append_only BEFORE UPDATE OR DELETE ON confirmation_receipt
FOR EACH ROW EXECUTE FUNCTION reject_confirmation_receipt_mutation();
```

Create `ConfirmationReceiptRepository.java` as a stateless repository whose `append(tx, draft)` uses the supplied tenant transaction. `ConfirmationModuleApi` is the only cross-module authorization surface and accepts an opaque `ConfirmationSubject`; it contains no Requirement, setup, ActionRequest, or other consumer-domain type. Create `ConfirmationCommandService.java` with this single transaction boundary:

```java
<T> T confirm(
    ConfirmationCommand command,
    java.util.function.BiFunction<DSLContext, AuthoritativeConfirmationReceiptRef, T> continuation
) {
    return authorization.authorizeAndExecute(command.authorization(), (tx, decision) -> {
        var human = command.authorization().principal().requireHuman();
        var binding = evidence.loadExactRoleBinding(tx, command.authorization().tenantId(), decision.evidenceIds());
        var prior = receipts.forSubject(tx, command.authorization().tenantId(), command.subject());
        var denied = ConfirmationPolicy.authorize(
            command.mode(), command.side(), human.naturalPersonId(), command.subject().subjectDigest(),
            prior, command.allowStandardCrossSidePerson()
        );
        if (denied != null) throw new ConfirmationDenied(denied);
        var eventId = UUID.randomUUID();
        var receipt = receipts.append(tx, ConfirmationReceiptDraft.from(command, human, binding, decision, eventId));
        new ReliableEventStore(tx).append(command.toAuthorizationEvent(receipt, eventId));
        commitHook.afterAppend();
        return continuation.apply(tx, receipt.toAuthoritativeRef());
    });
}
```

`ConfirmationCommandService` is the sole authority for confirmation authorization, current membership/role/delegation evidence, natural-person and side binding, ordering/separation, FreshAuth, nonce consumption, idempotency, signature evidence, and the append-only receipt. It locks the subject/side sequence head and allocates `confirmation_sequence = previous + 1`; uniqueness never prevents a later authorized person from reconfirming the same immutable subject/digest. `ConfirmationReceiptCurrentValidityView` overlays current membership, role/delegation, binding version, trust/key revocation, and supersession without updating a historical receipt. Policy uses the greatest currently active sequence for each side; an invalid old receipt never becomes current again merely because a later receipt is revoked. Consumer modules may add only FK-backed domain links/projections through `continuation`; they must not copy those authority facts. The continuation executes before commit on the same tenant transaction, so its link/outbox failure rolls back the receipt, authorization event, FreshAuth/nonce consumption, and idempotency result. Identity/Authorization depends only on the opaque subject and callback contract and never imports a consumer module, preventing circular dependencies.

Add a PostgreSQL test for `authorization revoked -> current validity invalid -> consumer eligibility false -> new current principal confirms the identical subject digest -> sequence increments -> eligibility true`. Both receipt rows remain append-only and audit-queryable; replay of the old command returns its historical result but cannot change the current-validity view, and no idempotency key can allocate two sequences.

`ConfirmationCommandService` receives a `ConfirmationCommitHook` whose production bean is a no-op; the fixture's `failingConfirmations` injects a hook that throws after append to prove rollback without adding a test flag to any command or HTTP DTO. `ConfirmationReceiptDraft.from` stores account and immutable natural person, exact role/delegation IDs and versions, project policy version, JCS context digest, authentication strength/session, verified intent-signature digest, opaque subject identity/version/digest, authoritative `confirmation_sequence`, and linked event ID. `AuthoritativeConfirmationReceiptRef` exposes receipt ID, confirmation sequence, subject type/ID/version/digest, side, and confirmed time only. The default convenience overload returns that reference by supplying an identity continuation. Task 8 changes the same callback to consume the high-risk proof before `append`; there is never a separate authorization or receipt transaction.

Extend `AuthorizationPostgreSqlTest` with `confirmations`, `failingConfirmations`, `confirmationCommand`, `evidenceCount`, and `receiptCount`, all backed by the app-role services or migration-owner read-only assertions. Do not insert receipts from fixture helpers.

- [ ] **Step 4: Run confirmation and RBAC regression tests**

Run:

```bash
./gradlew :apps:control-plane:modules:authorization:test --tests '*ConfirmationPolicyTest'
./gradlew :apps:control-plane:modules:authorization:test --tests '*AuthorizationServiceTest'
```

Expected: PASS. Strict same-person confirmation is denied; two people pass; wrong subject digest and wrong order fail; standard overlap passes only with the explicit lowered-guarantee policy; concurrent/replayed subject commands create one authoritative receipt; changing `subject_type`, `subject_id`, `subject_version`, or `subject_digest` under the same idempotency key is rejected and a receipt cannot be reused across Requirement/setup subject types; and a consumer continuation failure leaves no receipt, consumed nonce/FreshAuth proof, authorization event, or idempotency result.

- [ ] **Step 5: Commit confirmation separation**

```bash
git add database/control-plane/migrations/V013_1__confirmation_receipts.sql apps/control-plane/modules/authorization
git commit -m "feat: enforce cross-side confirmation separation"
```

### Task 7: Implement Scoped Delegation, Expiry, Revocation, And No Chaining

**Files:**
- Create: `database/control-plane/migrations/V014__delegation.sql`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/DelegationService.java`
- Modify: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/AuthorizationPostgreSqlTest.java`
- Create: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/DelegationServiceTest.java`

- [ ] **Step 1: Write failing delegation subset and cascade tests**

Create `DelegationServiceTest.java`:

```java
package com.inforvans.accord.authorization

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationServiceTest extends AuthorizationPostgreSqlTest {
    @Test
    void delegationCannotCrossSideWidenActionOrOutliveGrantor() {
        var fixture = seedProject(DeliveryMode.STRICT);
        establishBusinessPrincipalAuthority(fixture);
        setPrincipalAuthorityExpiry(fixture.businessPrincipal(), Instant.parse("2026-08-01T00:00:00Z"));
        assertThatThrownBy(() -> createDelegation(fixture.businessPrincipal(), fixture.businessEditor(), Side.DEVELOPMENT, Set.of(Action.FINAL_BUSINESS_CONFIRMATION), Instant.parse("2026-07-31T00:00:00Z"), null)).isInstanceOf(DelegationInvalid.class);
        assertThatThrownBy(() -> createDelegation(fixture.businessPrincipal(), fixture.businessEditor(), Side.BUSINESS, Set.of(Action.MANAGE_PROJECT), Instant.parse("2026-07-31T00:00:00Z"), null)).isInstanceOf(DelegationInvalid.class);
        assertThatThrownBy(() -> createDelegation(fixture.businessPrincipal(), fixture.businessEditor(), Side.BUSINESS, Set.of(Action.FINAL_BUSINESS_CONFIRMATION), Instant.parse("2026-08-02T00:00:00Z"), null)).isInstanceOf(DelegationInvalid.class);
    }

    @Test
    void actorUsingDelegatedAuthorityCannotCreateDelegation() {
        var fixture = seedProject(DeliveryMode.STANDARD);
        establishBusinessPrincipalAuthority(fixture);
        var parent = createDelegation(fixture.businessPrincipal(), fixture.businessEditor(), Side.BUSINESS, Set.of(Action.EDIT_BUSINESS_CONTENT), Instant.parse("2026-08-01T00:00:00Z"), null);
        assertThatThrownBy(() -> createDelegation(
            fixture.businessEditor(), fixture.businessRequester(), Side.BUSINESS,
            Set.of(Action.EDIT_BUSINESS_CONTENT), Instant.parse("2026-07-31T00:00:00Z"), parent.delegationId()
        )).isInstanceOf(DelegationCannotRedelegate.class);
    }

    @Test
    void revocationInvalidatesGrantAndPendingActions() {
        var fixture = seedProject(DeliveryMode.STANDARD);
        establishBusinessPrincipalAuthority(fixture);
        var parent = createDelegation(fixture.businessPrincipal(), fixture.businessEditor(), Side.BUSINESS, Set.of(Action.EDIT_BUSINESS_CONTENT), Instant.parse("2026-08-01T00:00:00Z"), null);
        delegations.revoke(tenantId, parent.delegationId(), fixture.businessPrincipal(), "leave coverage cancelled");
        assertThat(state(parent)).isEqualTo("REVOKED");
        assertThat(pendingActionInvalidationEvents(parent.delegationId())).isOne();
    }
}
```

- [ ] **Step 2: Run the test and verify delegation storage is missing**

Run: `./gradlew :apps:control-plane:modules:authorization:test --tests '*DelegationServiceTest'`

Expected: FAIL because migration version 014 and `DelegationService` are absent.

- [ ] **Step 3: Add delegation and action tables**

Create `V014__delegation.sql`:

```sql
CREATE TABLE delegation (
    tenant_id uuid NOT NULL,
    delegation_id uuid NOT NULL,
    project_id uuid NOT NULL,
    delegator_account_id uuid NOT NULL,
    delegate_account_id uuid NOT NULL,
    side varchar(16) NOT NULL CHECK (side IN ('BUSINESS', 'DEVELOPMENT')),
    role varchar(40) NOT NULL CHECK (role IN (
      'BUSINESS_PRINCIPAL','DEVELOPMENT_PRINCIPAL','BUSINESS_REQUESTER','BUSINESS_EDITOR',
      'DEVELOPMENT_LEAD','DEVELOPMENT_ASSESSOR','WORK_ITEM_OWNER','BUSINESS_ACCEPTANCE_OWNER'
    )),
    scope_type varchar(24) NOT NULL CHECK (scope_type IN ('PROJECT','BUSINESS_DOMAIN','REQUIREMENT','DELIVERY_BATCH','WORK_ITEM')),
    scope_id varchar(255) NOT NULL,
    starts_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    reason varchar(1024) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('SCHEDULED', 'ACTIVE', 'EXPIRED', 'REVOKED')),
    revoked_at timestamptz,
    revoked_by uuid,
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, delegation_id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES project(tenant_id, project_id),
    FOREIGN KEY (tenant_id, delegator_account_id) REFERENCES human_account(tenant_id, account_id),
    FOREIGN KEY (tenant_id, delegate_account_id) REFERENCES human_account(tenant_id, account_id),
    FOREIGN KEY (tenant_id, revoked_by) REFERENCES human_account(tenant_id, account_id),
    CHECK (delegate_account_id <> delegator_account_id),
    CHECK (expires_at > starts_at)
);

CREATE TABLE delegation_action (
    tenant_id uuid NOT NULL,
    delegation_id uuid NOT NULL,
    action varchar(64) NOT NULL CHECK (action IN (
      'EDIT_BUSINESS_CONTENT','SCORE_DEVELOPMENT','FINAL_DEVELOPMENT_CONFIRMATION',
      'FINAL_BUSINESS_CONFIRMATION','ACCEPT_CANDIDATE','AUTHORIZE_REPOSITORY_OPERATION'
    )),
    PRIMARY KEY (tenant_id, delegation_id, action),
    FOREIGN KEY (tenant_id, delegation_id) REFERENCES delegation(tenant_id, delegation_id) ON DELETE RESTRICT
);

SELECT accord_security.enforce_tenant_table('public.delegation'::regclass);
SELECT accord_security.enforce_tenant_table('public.delegation_action'::regclass);
```

Grant runtime DML only after both RLS calls. Do not cascade-delete delegation history; revocation and expiry are state transitions with events.

- [ ] **Step 4: Reject chained grants and revoke delegations transactionally**

Implement `DelegationService.create(tenantId, ...)` so a grant must be issued by the current side principal. Every lookup, including revoke and state, requires `TenantId` plus the UUID and executes through `TenantTransactions`; a bare delegation UUID is not a repository API. Reject an actor using any delegation before evaluating the requested grant:

```java
private void requireDirectAuthority(UUID actingViaDelegation) {
    if (actingViaDelegation != null) {
        throw new DelegationCannotRedelegate("delegation_cannot_redelegate");
    }
}
```

Then validate the delegator's current side-principal status and require the requested role, actions, scope, start, and expiry to be contained by that principal's direct authority. Use this SQL for revocation, then insert one outbox event so pending ActionRequests can be invalidated and routed to the original principal:

```sql
UPDATE delegation
SET state='REVOKED', revoked_at=?, revoked_by=?, version=version+1
WHERE tenant_id=? AND delegation_id=? AND state IN ('SCHEDULED', 'ACTIVE')
RETURNING delegation_id;
```

An expiry worker applies `EXPIRED` only when `expires_at <= transaction_timestamp()` and publishes the same rerouting event. Historical authorization evidence retains delegation ID, version, scope, actions, and validity interval.

Extend `AuthorizationPostgreSqlTest` with `protected DelegationService delegations;`, initialize it with `tenantTransactions` after each reset, and implement the helpers used above as thin calls plus migration-owner read-only evidence queries: `establishBusinessPrincipalAuthority`, `setPrincipalAuthorityExpiry`, `createDelegation`, `state`, and `pendingActionInvalidationEvents`. `establishBusinessPrincipalAuthority` calls the existing `bind(... BUSINESS_PRINCIPAL ...)` and `setHighestPrincipal` before any valid grant. `createDelegation` always passes the fixture `tenantId`, current project, direct actor identity, requested side/actions/expiry, and optional `actingViaDelegation`; it must never bypass the production service with a fixture-only insert.

- [ ] **Step 5: Run delegation and authorization tests**

Run:

```bash
./gradlew :apps:control-plane:modules:authorization:test --tests '*DelegationServiceTest'
./gradlew :apps:control-plane:modules:authorization:test --tests '*AuthorizationServiceTest'
```

Expected: PASS. Cross-side, wider, and later-expiry grants fail; any actor using delegated authority gets `delegation_cannot_redelegate`; revocation invalidates the grant and its pending actions.

- [ ] **Step 6: Commit bounded delegation**

```bash
git add database/control-plane/migrations/V014__delegation.sql apps/control-plane/modules/authorization
git commit -m "feat: add scoped revocable delegation"
```

### Task 8: Require Fresh Authentication And Anti-Replay Sessions

**Files:**
- Create: `database/control-plane/migrations/V015__fresh_auth.sql`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/FreshAuthService.java`
- Modify: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/ConfirmationCommandService.java`
- Modify: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/AuthorizationPostgreSqlTest.java`
- Create: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/FreshAuthServiceTest.java`

- [ ] **Step 1: Write failing nonce, expiry, binding-version, and one-use tests**

Create `FreshAuthServiceTest.java`:

```java
package com.inforvans.accord.authorization

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FreshAuthServiceTest extends AuthorizationPostgreSqlTest {
    private final Instant proofTime = Instant.parse("2026-07-24T10:00:00Z");

    @Test
    void authenticationCallbackNonceIsConsumedOnce() {
        var actor = seedProject(DeliveryMode.STANDARD).admin();
        var challenge = freshAuth.startChallenge(tenantId, actor.accountId(), proofTime.plusSeconds(120));
        var session = freshAuth.completeChallenge(tenantId, challenge.id(), challenge.rawNonce(), actor.identityVersion(), proofTime);
        assertThat(session.identityVersion()).isEqualTo(actor.identityVersion());
        assertThatThrownBy(() -> freshAuth.completeChallenge(tenantId, challenge.id(), challenge.rawNonce(), actor.identityVersion(), proofTime)).isInstanceOf(ReplayRejected.class);
    }

    @Test
    void highRiskProofBindsOneExactActionAndIsConsumedAtomically() {
        var actor = seedProject(DeliveryMode.STANDARD).admin();
        var digest = "sha256:" + "a".repeat(64);
        var proof = issueSingleUseProof(actor, digest, proofTime.plusSeconds(120));
        freshAuth.requireFresh(tenantId, proof, digest, actor.identityVersion(), RiskClass.HIGH, proofTime);
        assertThatThrownBy(() -> freshAuth.requireFresh(tenantId, proof, digest, actor.identityVersion(), RiskClass.HIGH, proofTime.plusSeconds(1))).isInstanceOf(ReplayRejected.class);
    }

    @Test
    void expiredOrStaleIdentityProofIsRejected() {
        var actor = seedProject(DeliveryMode.STANDARD).admin();
        var proof = issueReusableProof(actor, proofTime.plusSeconds(60));
        assertThatThrownBy(() -> freshAuth.requireFresh(tenantId, proof, null, actor.identityVersion(), RiskClass.NORMAL, proofTime.plusSeconds(61))).isInstanceOf(FreshAuthRequired.class);
        assertThatThrownBy(() -> freshAuth.requireFresh(tenantId, proof, null, actor.identityVersion() + 1, RiskClass.NORMAL, proofTime)).isInstanceOf(FreshAuthRequired.class);
    }

    @Test
    void proofConsumptionReceiptAndEventRollBackAsOneCommand() {
        var fixture = seedProject(DeliveryMode.STRICT);
        bind(fixture.developmentPrincipal(), Role.DEVELOPMENT_PRINCIPAL, Side.DEVELOPMENT, new Scope.Project(fixture.projectId()));
        setHighestPrincipal(Side.DEVELOPMENT, fixture.developmentPrincipal());
        var revision = "sha256:" + "d".repeat(64);
        var actionDigest = confirmationActionDigest(fixture.projectId(), revision, Side.DEVELOPMENT);
        var proof = issueSingleUseProof(fixture.developmentPrincipal(), actionDigest, proofTime.plusSeconds(120));
        var command = confirmationCommand(fixture.developmentPrincipal(), Side.DEVELOPMENT, revision, proof);
        assertThatThrownBy(() -> failingConfirmations.confirm(command)).isInstanceOf(RuntimeException.class);
        assertThat(sessionConsumedAt(proof)).isNull();
        assertThat(receiptCount(revision)).isZero();
        confirmations.confirm(command);
        assertThat(sessionConsumedAt(proof)).isNotNull();
        assertThat(receiptCount(revision)).isOne();
    }
}
```

- [ ] **Step 2: Run the test and verify fresh-auth storage is absent**

Run: `./gradlew :apps:control-plane:modules:authorization:test --tests '*FreshAuthServiceTest'`

Expected: FAIL because challenge/session tables and services do not exist.

- [ ] **Step 3: Add hashed challenges and version-bound sessions**

Create `V015__fresh_auth.sql`:

```sql
CREATE TABLE reauth_challenge (
    tenant_id uuid NOT NULL,
    challenge_id uuid NOT NULL,
    account_id uuid NOT NULL,
    nonce_hash char(71) NOT NULL CHECK (nonce_hash ~ '^sha256:[0-9a-f]{64}$'),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id, challenge_id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES human_account(tenant_id, account_id),
    UNIQUE (tenant_id, nonce_hash)
);

CREATE TABLE reauth_session (
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    account_id uuid NOT NULL,
    natural_person_id uuid NOT NULL,
    identity_version bigint NOT NULL,
    authentication_time timestamptz NOT NULL,
    authentication_methods text[] NOT NULL,
    assurance_level varchar(32) NOT NULL,
    bound_action_digest char(71) CHECK (bound_action_digest ~ '^sha256:[0-9a-f]{64}$'),
    single_use boolean NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    PRIMARY KEY (tenant_id, session_id),
    FOREIGN KEY (tenant_id, account_id, natural_person_id)
      REFERENCES human_account(tenant_id, account_id, natural_person_id),
    CHECK ((single_use AND bound_action_digest IS NOT NULL) OR (NOT single_use AND bound_action_digest IS NULL))
);

SELECT accord_security.enforce_tenant_table('public.reauth_challenge'::regclass);
SELECT accord_security.enforce_tenant_table('public.reauth_session'::regclass);
```

Grant runtime DML only after both RLS calls. Store only `sha256:` nonce hashes; return a raw 256-bit nonce once and never log it.

- [ ] **Step 4: Atomically consume challenges and high-risk proofs**

All public methods take `TenantId`; `challenge_id` or `session_id` alone is never a repository key. `startChallenge` and `completeChallenge` open tenant transactions. Implement challenge completion with:

```sql
UPDATE reauth_challenge
SET consumed_at = ?
WHERE tenant_id = ? AND challenge_id = ? AND nonce_hash = ?
  AND consumed_at IS NULL AND expires_at > ?
RETURNING account_id;
```

After consuming a challenge, load the current account in the same transaction and copy its database `identity_version` and natural-person pair into the session; reject a callback whose asserted version differs. Implement `requireFresh(tx, tenantId, ...)` with a locked query that verifies tenant/account/natural-person, current database `identity_version`, expiry, approved step-up method, and the project window. Its convenience overload opens a tenant transaction only for non-mutating callers. For `HIGH`, `ASSESSMENT_OVERRIDE`, `BATCH_ABORT`, `BREAK_GLASS`, `STRICT_RECOVERY`, and policy-marked actions, require `single_use=true`, exact `bound_action_digest`, and consume with:

```sql
UPDATE reauth_session
SET consumed_at = ?
WHERE tenant_id = ? AND session_id = ? AND single_use
  AND consumed_at IS NULL AND expires_at > ?
  AND identity_version = ? AND bound_action_digest = ?
RETURNING account_id, natural_person_id;
```

For `NORMAL`, require a reusable session whose `authentication_time` is within `fresh_auth_window_seconds`; do not consume it per criterion or per row. `ConfirmationCommandService` calls the existing-transaction overload inside `authorizeAndExecute`, before appending the receipt and event. Return `ReplayRejected` when an otherwise matching single-use record is already consumed, and `FreshAuthRequired` for expiry, wrong action, method, or binding version. The rollback test proves a failure after receipt append restores `consumed_at`, removes the receipt/event, and permits exactly one successful retry.

In the `ConfirmationCommandService.confirm` callback from Task 6, insert this call before constructing `ConfirmationReceiptDraft`; use the returned database evidence rather than copying authentication fields from the request:

```java
var authentication = freshAuth.requireFresh(
    tx, command.authorization().tenantId(), command.freshAuthSessionId(), command.actionDigest(),
    human.identityVersion(), RiskClass.HIGH, command.authorization().now()
);
var draft = ConfirmationReceiptDraft.from(command, human, binding, decision, authentication, eventId);
```

Extend `AuthorizationPostgreSqlTest` with `protected FreshAuthService freshAuth`, initialized with `tenantTransactions`, plus `issueSingleUseProof(actor, ...)`, `issueReusableProof(actor, ...)`, `confirmationActionDigest`, and `sessionConsumedAt`. Those helpers create proofs through `FreshAuthService` from the seeded actor's actual `identityVersion`; they may not insert pre-consumed or otherwise impossible sessions directly.

- [ ] **Step 5: Run fresh-auth and authorization tests**

Run:

```bash
./gradlew :apps:control-plane:modules:authorization:test --tests '*FreshAuthServiceTest'
./gradlew :apps:control-plane:modules:authorization:test --tests '*AuthorizationServiceTest'
```

Expected: PASS. Callback nonce and high-risk proof each succeed once; normal fresh-auth is reusable only inside its short window; expired, wrong-action, and stale-binding sessions fail closed.

- [ ] **Step 6: Commit anti-replay authentication**

```bash
git add database/control-plane/migrations/V015__fresh_auth.sql apps/control-plane/modules/authorization
git commit -m "feat: require fresh anti-replay authentication"
```

### Task 9: Restrict External Vendors To Sponsored Assignment Scope

**Files:**
- Create: `database/control-plane/migrations/V016__external_vendor_assignments.sql`
- Create: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/VendorVisibilityService.java`
- Modify: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/AuthorizationPostgreSqlTest.java`
- Create: `apps/control-plane/modules/authorization/src/test/java/com/inforvans/accord/authorization/VendorVisibilityServiceTest.java`

- [ ] **Step 1: Write failing assignment visibility and self-acceptance tests**

Create `VendorVisibilityServiceTest.java`:

```java
package com.inforvans.accord.authorization

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VendorVisibilityServiceTest extends AuthorizationPostgreSqlTest {
    private final Instant visibilityTime = Instant.parse("2026-07-24T10:00:00Z");

    @Test
    void vendorSeesOnlyAssignedResourcesAndNeverMemberDirectory() {
        var fixture = seedVendorProject();
        var assignment = assignVendor(
            fixture.vendor(), fixture.developmentPrincipal(),
            Set.of(new ResourceRef("REQUIREMENT", "req-1"), new ResourceRef("WORK_ITEM", "wi-1"), new ResourceRef("ATTACHMENT", "att-1")),
            visibilityTime.plusSeconds(3600)
        );
        assertThat(vendorVisibility.canView(tenantId, fixture.vendor().accountId(), new ResourceRef("REQUIREMENT", "req-1"), visibilityTime)).isTrue();
        assertThat(vendorVisibility.canView(tenantId, fixture.vendor().accountId(), new ResourceRef("REQUIREMENT", "req-2"), visibilityTime)).isFalse();
        assertThat(vendorVisibility.canView(tenantId, fixture.vendor().accountId(), new ResourceRef("PROJECT_MEMBER_DIRECTORY", fixture.projectId().toString()), visibilityTime)).isFalse();
        assertThat(vendorVisibility.visibilityEvidence(tenantId, fixture.vendor().accountId(), new ResourceRef("WORK_ITEM", "wi-1"), visibilityTime)).isEqualTo(assignment.assignmentId());
    }

    @Test
    void expiredScopeIsDeniedAndAcceptanceUsesFullBusinessAuthorization() {
        var fixture = seedVendorProject();
        assignVendor(fixture.vendor(), fixture.developmentPrincipal(), Set.of(new ResourceRef("WORK_ITEM", "wi-1")), visibilityTime.minusSeconds(1));
        assertThat(vendorVisibility.canView(tenantId, fixture.vendor().accountId(), new ResourceRef("WORK_ITEM", "wi-1"), visibilityTime)).isFalse();
        assignVendor(fixture.vendor(), fixture.developmentPrincipal(), Set.of(new ResourceRef("WORK_ITEM", "wi-2")), visibilityTime.plusSeconds(3600));
        bind(fixture.businessAcceptanceOwner(), Role.BUSINESS_ACCEPTANCE_OWNER, Side.BUSINESS, new Scope.Project(fixture.projectId()));
        var digest = acceptanceActionDigest(fixture.projectId(), "wi-2");
        var proof = issueSingleUseProof(fixture.businessAcceptanceOwner(), digest, visibilityTime.plusSeconds(120));

        assertThat(vendorVisibility.authorizeAcceptance(tenantId, fixture.projectId(), fixture.vendor(), fixture.vendor().accountId(), "wi-2", null, visibilityTime).deniedBy()).isEqualTo(DenyReason.SEPARATION_OF_DUTIES);
        assertThat(vendorVisibility.authorizeAcceptance(tenantId, fixture.projectId(), fixture.developmentPrincipal(), fixture.vendor().accountId(), "wi-2", null, visibilityTime).deniedBy()).isEqualTo(DenyReason.ROLE_DOES_NOT_ALLOW_ACTION);
        assertThat(vendorVisibility.authorizeAcceptance(tenantId, fixture.projectId(), fixture.businessAcceptanceOwner(), fixture.vendor().accountId(), "wi-2", proof, visibilityTime).allowed()).isTrue();
    }
}
```

- [ ] **Step 2: Run the test and verify vendor assignment storage is absent**

Run: `./gradlew :apps:control-plane:modules:authorization:test --tests '*VendorVisibilityServiceTest'`

Expected: FAIL because version 016 and `VendorVisibilityService` are absent.

- [ ] **Step 3: Add expiring sponsored assignments and exact resource grants**

Create `V016__external_vendor_assignments.sql`:

```sql
CREATE TABLE external_assignment (
    tenant_id uuid NOT NULL,
    assignment_id uuid NOT NULL,
    project_id uuid NOT NULL,
    vendor_account_id uuid NOT NULL,
    sponsor_account_id uuid NOT NULL,
    side varchar(16) GENERATED ALWAYS AS ('DEVELOPMENT') STORED,
    starts_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PROPOSED', 'ACTIVE', 'ENDED', 'REVOKED', 'EXPIRED')),
    contract_reference varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, assignment_id),
    FOREIGN KEY (tenant_id, project_id, vendor_account_id) REFERENCES project_membership(tenant_id, project_id, account_id),
    FOREIGN KEY (tenant_id, project_id, sponsor_account_id) REFERENCES project_membership(tenant_id, project_id, account_id),
    CHECK (vendor_account_id <> sponsor_account_id),
    CHECK (expires_at > starts_at)
);

CREATE TABLE external_assignment_resource (
    tenant_id uuid NOT NULL,
    assignment_id uuid NOT NULL,
    resource_type varchar(32) NOT NULL CHECK (resource_type IN ('REQUIREMENT', 'WORK_ITEM', 'ATTACHMENT')),
    resource_id varchar(255) NOT NULL,
    PRIMARY KEY (tenant_id, assignment_id, resource_type, resource_id),
    FOREIGN KEY (tenant_id, assignment_id) REFERENCES external_assignment(tenant_id, assignment_id) ON DELETE RESTRICT
);

CREATE INDEX external_assignment_active_idx
    ON external_assignment (tenant_id, vendor_account_id, expires_at) WHERE state = 'ACTIVE';

SELECT accord_security.enforce_tenant_table('public.external_assignment'::regclass);
SELECT accord_security.enforce_tenant_table('public.external_assignment_resource'::regclass);
```

Grant runtime DML only after both RLS calls. Service creation verifies `project_membership.member_kind='EXTERNAL_VENDOR'` for the vendor, `INTERNAL` for the sponsor, and an active `DEVELOPMENT_PRINCIPAL` binding for the sponsor.

- [ ] **Step 4: Enforce visibility in server-side repository predicates**

Create `VendorVisibilityService.java`:

```java
package com.inforvans.accord.authorization;

import java.time.Instant
import java.util.UUID
import com.inforvans.accord.identity.PrincipalIdentity
import com.inforvans.accord.identity.TenantId
import com.inforvans.accord.identity.TenantTransactions
import org.jooq.DSLContext

record ResourceRef(String type, String id) {}

final class VendorVisibilityService {
    private final TenantTransactions transactions;
    private final JooqAuthorizationService authorization;

    VendorVisibilityService(TenantTransactions transactions, JooqAuthorizationService authorization) {
        this.transactions = transactions;
        this.authorization = authorization;
    }

    boolean canView(TenantId tenantId, UUID accountId, ResourceRef resource, Instant now) {
        return visibilityEvidence(tenantId, accountId, resource, now) != null;
    }

    UUID visibilityEvidence(TenantId tenantId, UUID accountId, ResourceRef resource, Instant now) {
        return transactions.read(tenantId, tx -> visibilityEvidence(tx, tenantId, accountId, resource, now));
    }

    private UUID visibilityEvidence(DSLContext tx, TenantId tenantId, UUID accountId, ResourceRef resource, Instant now) {
        var row = tx.fetchOne(
            """SELECT a.assignment_id
               FROM external_assignment a
               JOIN external_assignment_resource r
                 ON r.tenant_id=a.tenant_id AND r.assignment_id=a.assignment_id
               JOIN project_membership m
                 ON m.tenant_id=a.tenant_id AND m.project_id=a.project_id AND m.account_id=a.vendor_account_id
               WHERE a.tenant_id=? AND a.vendor_account_id=? AND a.state='ACTIVE' AND m.state='ACTIVE'
                 AND a.starts_at<=? AND a.expires_at>?
                 AND r.resource_type=? AND r.resource_id=?""",
            tenantId.value(), accountId, now, now, resource.type(), resource.id()
        );
        return row == null ? null : row.get(0, UUID.class);
    }

    AuthorizationDecision authorizeAcceptance(
        TenantId tenantId, UUID projectId, PrincipalIdentity.Human actor, UUID deliveredBy,
        String workItemId, UUID freshAuthSessionId, Instant now
    ) {
      return transactions.read(tenantId, tx -> {
        if (actor.accountId().equals(deliveredBy)) {
            return new AuthorizationDecision(false, DenyReason.SEPARATION_OF_DUTIES, java.util.List.of());
        }
        var assignmentId = visibilityEvidence(tx, tenantId, deliveredBy, new ResourceRef("WORK_ITEM", workItemId), now);
        if (assignmentId == null) return new AuthorizationDecision(false, DenyReason.TENANT_OR_MEMBERSHIP_INACTIVE, java.util.List.of());
        var decision = authorization.decide(
            tx,
            new AuthorizationRequest(
                tenantId, projectId, actor, Action.ACCEPT_CANDIDATE,
                new ResourceTarget.Scoped(projectId, "WORK_ITEM", workItemId), now, null, freshAuthSessionId
            )
        );
        if (!decision.allowed()) return decision;
        var evidence = new java.util.ArrayList<>(decision.evidenceIds());
        evidence.add(assignmentId);
        return new AuthorizationDecision(true, null, java.util.List.copyOf(evidence));
      });
    }
}
```

`authorizeAcceptance` is a non-consuming preflight, but it executes the same `JooqAuthorizationService` core used by the candidate-acceptance command: active tenant/project membership, `BUSINESS_ACCEPTANCE_OWNER`, business side, exact WorkItem scope, current identity version, fresh-auth eligibility, assignment evidence, and separation of duties all apply. The eventual protected acceptance calls `authorizeAndExecute` in the candidate plan so proof consumption, acceptance receipt, and audit event are atomic. A different UUID alone never grants acceptance.

All requirement, WorkItem, attachment, download, search, and list repositories must join the tenant-bearing predicate for external members. Returning no row is the response for both missing and unauthorized objects. Project member directory, tenant settings, unrelated business domains, and other vendors are never grantable resource types.

- [ ] **Step 5: Expire assignments and route orphaned work**

Add a worker command that runs per tenant and atomically updates:

```sql
UPDATE external_assignment
SET state='EXPIRED', version=version+1
WHERE tenant_id=? AND state='ACTIVE' AND expires_at<=transaction_timestamp()
RETURNING assignment_id, project_id, vendor_account_id, sponsor_account_id;
```

For every returned assignment, insert outbox events that revoke access immediately, place holds on unfinished WorkItems and ActionRequests, and route them to `sponsor_account_id`. The notification contains only assignment ID, expiry, and a platform deep link; it contains no requirement text or attachment data.

Extend `AuthorizationPostgreSqlTest` with `protected VendorVisibilityService vendorVisibility;`, `seedVendorProject`, `assignVendor`, and `acceptanceActionDigest`. Construct the service from `tenantTransactions` and the app-role authorization service. The vendor fixture creates an `EXTERNAL_VENDOR` membership, a distinct internal Development Principal sponsor, and the separate Business Acceptance Owner already present in the base fixture. `assignVendor` invokes the tenant-bearing production creation path, then reads back the assignment and exact resource rows; it never grants `PROJECT_MEMBER_DIRECTORY` because that value is rejected by the database constraint.

- [ ] **Step 6: Run vendor authorization tests**

Run:

```bash
./gradlew :apps:control-plane:modules:authorization:test --tests '*VendorVisibilityServiceTest'
./gradlew :apps:control-plane:modules:authorization:test --tests '*AuthorizationServiceTest'
```

Expected: PASS. Assigned resources are visible; all unassigned and directory resources are invisible; expiry closes access; a vendor cannot accept its own delivery; internal sponsor evidence is recorded.

- [ ] **Step 7: Commit assignment-scoped vendor access**

```bash
git add database/control-plane/migrations/V016__external_vendor_assignments.sql apps/control-plane/modules/authorization
git commit -m "feat: isolate external vendor assignments"
```

### Task 10: Define Purpose-Bound Signing, Verification, And Token Contracts

**Files:**
- Create: `contracts/protobuf/accord/signing/v1/signing.proto`
- Create: `contracts/json-schema/dsse-envelope.schema.json`
- Create: `contracts/dsse-payloads/signing-claims.schema.json`
- Create: `contracts/dsse-payloads/break-glass-authorization.schema.json`
- Create: `contracts/golden-fixtures/signing/claims.input.json`
- Create: `contracts/golden-fixtures/signing/claims.canonical.json`
- Create: `contracts/golden-fixtures/signing/claims.sha256`
- Create: `contracts/golden-fixtures/signing/break-glass-authorization.input.json`
- Create: `contracts/golden-fixtures/signing/break-glass-authorization.canonical.json`
- Create: `contracts/golden-fixtures/signing/break-glass-authorization.sha256`
- Create: `contracts/protobuf/build.gradle`
- Create: `tests/contracts/signing-contract.test.mjs`
- Modify: `buf.gen.yaml`
- Modify: `settings.gradle`

- [ ] **Step 1: Write failing scope-conditional and protobuf tests**

Create `signing-contract.test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const schema = JSON.parse(await readFile('contracts/dsse-payloads/signing-claims.schema.json', 'utf8'));
const breakGlassSchema = JSON.parse(await readFile('contracts/dsse-payloads/break-glass-authorization.schema.json', 'utf8'));
const validate = addFormats(new Ajv2020({ strict: true, allErrors: true }));
const check = validate.compile(schema);
const checkBreakGlass = validate.compile(breakGlassSchema);

test('repository scope requires immutable repository identity', () => {
  const claims = JSON.parse(await readFile('contracts/golden-fixtures/signing/claims.input.json', 'utf8'));
  assert.equal(check(claims), true, JSON.stringify(check.errors));
  const missing = structuredClone(claims);
  delete missing.immutable_repository_id;
  assert.equal(check(missing), false);
});

test('tenant scope forbids a synthetic repository identity', () => {
  const claims = JSON.parse(await readFile('contracts/golden-fixtures/signing/claims.input.json', 'utf8'));
  claims.scope_type = 'tenant';
  claims.scope_id = claims.tenant_id;
  assert.equal(check(claims), false);
  delete claims.immutable_repository_id;
  assert.equal(check(claims), true, JSON.stringify(check.errors));
});

test('short-lived authorization binds every exact resource field', () => {
  const claims = JSON.parse(await readFile('contracts/golden-fixtures/signing/claims.input.json', 'utf8'));
  Object.assign(claims, {
    purpose: 'strict-merge',
    domain: 'accord.strict-merge.v1',
    object_id: 'work_item_pr:work-item-17',
    target_ref: 'refs/heads/main',
    expected_target_head_sha: 'a'.repeat(40),
    source_head_sha: 'b'.repeat(40),
    verified_result_tree_sha: 'c'.repeat(40),
    normalized_diff_digest: `sha256:${'d'.repeat(64)}`,
    required_checks_digest: `sha256:${'e'.repeat(64)}`,
    ci_attestation_digest: `sha256:${'f'.repeat(64)}`,
    subject_type: 'work_item_pr',
    subject_id: 'work-item-17',
    subject_digest: `sha256:${'1'.repeat(64)}`,
    nonce: 'A'.repeat(43),
    expires_at: '2026-07-24T10:05:00Z',
  });
  assert.equal(check(claims), true, JSON.stringify(check.errors));
  delete claims.verified_result_tree_sha;
  assert.equal(check(claims), false);

  for (const subjectType of ['work_item_pr', 'requirement_metadata', 'accepted_delivery_candidate', 'emergency_change']) {
    const typed = structuredClone(claims);
    typed.verified_result_tree_sha = 'c'.repeat(40);
    typed.subject_type = subjectType;
    typed.subject_id = `${subjectType}-17`;
    typed.object_id = `${subjectType}:${typed.subject_id}`;
    assert.equal(check(typed), true, `${subjectType}: ${JSON.stringify(check.errors)}`);
  }
});

test('strict merge and break glass contracts reject each other in both directions', () => {
  const strict = JSON.parse(await readFile('contracts/golden-fixtures/signing/claims.input.json', 'utf8'));
  Object.assign(strict, {
    purpose: 'strict-merge', domain: 'accord.strict-merge.v1', object_id: 'work_item_pr:work-item-17',
    target_ref: 'refs/heads/main', expected_target_head_sha: 'a'.repeat(40), source_head_sha: 'b'.repeat(40),
    verified_result_tree_sha: 'c'.repeat(40), normalized_diff_digest: `sha256:${'d'.repeat(64)}`,
    required_checks_digest: `sha256:${'e'.repeat(64)}`, ci_attestation_digest: `sha256:${'f'.repeat(64)}`,
    subject_type: 'work_item_pr', subject_id: 'work-item-17', subject_digest: `sha256:${'1'.repeat(64)}`,
    nonce: 'A'.repeat(43), expires_at: '2026-07-24T10:05:00Z',
  });
  const breakGlass = JSON.parse(await readFile('contracts/golden-fixtures/signing/break-glass-authorization.input.json', 'utf8'));
  assert.equal(check(strict), true, JSON.stringify(check.errors));
  assert.equal(checkBreakGlass(strict), false);
  assert.equal(checkBreakGlass(breakGlass), true, JSON.stringify(checkBreakGlass.errors));
  assert.equal(check(breakGlass), false);
  const missingNonceHash = structuredClone(breakGlass);
  delete missingNonceHash.binding.nonce_hash;
  assert.equal(checkBreakGlass(missingNonceHash), false);
  const rawNonce = structuredClone(breakGlass);
  rawNonce.binding.raw_nonce = 'A'.repeat(43);
  assert.equal(checkBreakGlass(rawNonce), false);
  breakGlass.binding.unrecognized_provider_command = 'force-push';
  assert.equal(checkBreakGlass(breakGlass), false);
});
```

- [ ] **Step 2: Run the tests and verify signing contracts are absent**

Run:

```bash
node --test tests/contracts/signing-contract.test.mjs
buf lint
```

Expected: FAIL. Node reports `ENOENT` for the claims schema; Buf does not yet expose `accord.signing.v1.SigningService`.

- [ ] **Step 3: Define the internal protobuf API**

Create `signing.proto`:

```proto
syntax = "proto3";

package accord.signing.v1;
option go_package = "github.com/inforvans/accord/contracts/gen/go/accord/signing/v1;signingv1";
option java_multiple_files = true;
option java_package = "com.inforvans.accord.contracts.signing.v1";

import "accord/common/v1/context.proto";
import "google/protobuf/timestamp.proto";

enum SigningPurpose {
  SIGNING_PURPOSE_UNSPECIFIED = 0;
  SIGNING_PURPOSE_REQUIREMENT_PUBLICATION = 1;
  SIGNING_PURPOSE_CONFIRMATION = 2;
  SIGNING_PURPOSE_ACCEPTANCE = 3;
  SIGNING_PURPOSE_STRICT_MERGE = 4;
  SIGNING_PURPOSE_BREAK_GLASS = 5;
  SIGNING_PURPOSE_AUDIT_ANCHOR = 6;
}

message SigningScope {
  accord.common.v1.RequestContext context = 1;
  string immutable_repository_id = 2;
}

enum MergeSubjectType {
  MERGE_SUBJECT_TYPE_UNSPECIFIED = 0;
  MERGE_SUBJECT_TYPE_WORK_ITEM_PR = 1;
  MERGE_SUBJECT_TYPE_REQUIREMENT_METADATA = 2;
  MERGE_SUBJECT_TYPE_ACCEPTED_DELIVERY_CANDIDATE = 3;
  MERGE_SUBJECT_TYPE_EMERGENCY_CHANGE = 4;
}

message VerifiedMergeSubject {
  MergeSubjectType subject_type = 1;
  string subject_id = 2;
  string subject_digest = 3;
}

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
}

enum BreakGlassProviderAction {
  BREAK_GLASS_PROVIDER_ACTION_UNSPECIFIED = 0;
  BREAK_GLASS_PROVIDER_ACTION_MERGE_EMERGENCY_CHANGE = 1;
  BREAK_GLASS_PROVIDER_ACTION_RESTORE_PROTECTION_POLICY = 2;
  BREAK_GLASS_PROVIDER_ACTION_FENCE_PROVIDER_MUTATIONS = 3;
}

message BreakGlassAuthorizationBinding {
  string tenant_id = 1;
  string repository_id = 2;
  string target_ref = 3;
  string grant_id = 4;
  string grant_action_digest = 5;
  BreakGlassProviderAction provider_action = 6;
  string provider_action_parameters_digest = 7;
  string scope_digest = 8;
  string provider_fact_baseline_digest = 9;
  string reason_digest = 10;
  repeated string human_authorization_receipt_digests = 11;
  string recovery_obligation_digest = 12;
  google.protobuf.Timestamp issued_at = 13;
  google.protobuf.Timestamp expires_at = 14;
  uint64 broker_reservation_generation = 15;
  string nonce_hash = 16;
}

message SignDsseRequest {
  SigningScope scope = 1;
  SigningPurpose purpose = 2;
  string payload_type = 3;
  string schema_id = 4;
  string domain = 5;
  string object_id = 6;
  bytes payload_json = 7;
  string idempotency_key = 8;
  ExactAuthorizationBinding authorization = 9;
}

message SignDsseResponse {
  bytes envelope_json = 1;
  string envelope_digest = 2;
  string key_id = 3;
  string algorithm = 4;
  google.protobuf.Timestamp signed_at = 5;
  string trust_record_id = 6;
}

message VerifyDsseRequest { bytes envelope_json = 1; SigningPurpose expected_purpose = 2; string expected_domain = 3; }
message VerifyDsseResponse { bool valid = 1; string key_id = 2; string trust_record_id = 3; string reason_code = 4; }

message ConsumeAuthorizationTokenRequest {
  SigningScope scope = 1;
  SigningPurpose purpose = 2;
  bytes envelope_json = 3;
  string expected_ref = 4;
  string expected_target_head_sha = 5;
  string expected_source_head_sha = 6;
  string expected_result_tree_sha = 7;
  string expected_normalized_diff_digest = 8;
  string expected_required_checks_digest = 9;
  string expected_ci_attestation_digest = 10;
  VerifiedMergeSubject expected_subject = 11;
}
message ConsumeAuthorizationTokenResponse { string nonce = 1; google.protobuf.Timestamp consumed_at = 2; }

message IssueBreakGlassAuthorizationRequest {
  SigningScope scope = 1;
  BreakGlassAuthorizationBinding binding = 2;
  repeated string evidence_receipt_digests = 3;
  string idempotency_key = 4;
}
message IssueBreakGlassAuthorizationResponse {
  bytes dsse_envelope = 1;
  string binding_digest = 2;
  string key_id = 3;
  string evidence_snapshot_digest = 4;
  google.protobuf.Timestamp signed_at = 5;
  string trust_record_id = 6;
}

message GetBreakGlassGrantEvidenceRequest {
  string tenant_id = 1;
  BreakGlassAuthorizationBinding binding = 2;
  string expected_binding_digest = 3;
}
enum BreakGlassAuthorizationState {
  BREAK_GLASS_AUTHORIZATION_STATE_UNSPECIFIED = 0;
  BREAK_GLASS_AUTHORIZATION_STATE_AUTHORIZED = 1;
}
message BreakGlassGrantEvidence {
  uint64 grant_version = 1;
  string binding_digest = 2;
  BreakGlassAuthorizationState authorization_state = 3;
  repeated string human_authorization_receipt_digests = 4;
  google.protobuf.Timestamp expires_at = 5;
  string evidence_snapshot_digest = 6;
}

service SigningService {
  rpc SignDsse(SignDsseRequest) returns (SignDsseResponse);
  rpc VerifyDsse(VerifyDsseRequest) returns (VerifyDsseResponse);
  rpc ConsumeAuthorizationToken(ConsumeAuthorizationTokenRequest) returns (ConsumeAuthorizationTokenResponse);
  rpc IssueBreakGlassAuthorization(IssueBreakGlassAuthorizationRequest) returns (IssueBreakGlassAuthorizationResponse);
}

service AuthorizationEvidenceService {
  rpc GetBreakGlassGrantEvidence(GetBreakGlassGrantEvidenceRequest) returns (BreakGlassGrantEvidence);
}
```

This is the immutable strict-merge v1 baseline. Protobuf fields 1-10 of `ExactAuthorizationBinding`, every field number in `ConsumeAuthorizationToken*`, the v1 RPC wire name, `signing-claims.schema.json`, and `claims.{input,canonical,sha256}` are compatibility records and must never be renumbered, repurposed, or rewritten. Git Delivery Task 1 appends only new protobuf field numbers and creates a separate closed `signing-claims-v2.schema.json` plus `claims-v2.*` goldens; Git Delivery Task 9 makes v2 the only issuable/reservable strict protocol while retaining v1 verification for historical evidence. The package name remains `accord.signing.v1` because protobuf package version and signed claims schema version are independent compatibility axes.

`VerifiedMergeSubject` and `ExactAuthorizationBinding` are exclusively the strict Merge Controller contract. Their closed subject types are `work_item_pr`, `requirement_metadata`, `accepted_delivery_candidate`, and `emergency_change`; no caller may smuggle a type through `subject_id`. Producers calculate `subject_digest` from the authoritative subject evidence using the JCS preimage `{"domain":"accord.verified-merge-subject.v1","subject_type":...,"subject_id":...,"evidence":...}`. The Signing Service signs type, ID, and digest separately, requires `object_id="<subject_type>:<subject_id>"`, and consumers compare all three before nonce consumption. Thus an identical digest byte string under another subject type is a different signed domain and cannot authorize it.

`BreakGlassAuthorizationBinding` is a distinct closed contract and never appears in `SignDsse`, `ConsumeAuthorizationToken`, `ExactAuthorizationBinding`, or `VerifiedMergeSubject`. Its three Provider actions are exhaustive: merge one already-authorized EmergencyChange, restore one certified protection-policy digest, or fence one Provider installation credential epoch. Arbitrary ref update, force push, delete, blob/commit/content mutation, and arbitrary Provider endpoint invocation have no enum or parameters branch. The Git Delivery plan owns the action-specific closed parameter `oneOf`; `provider_action_parameters_digest` is the RFC 8785 digest of that exact typed branch, and `grant_action_digest` is the digest of `{action,parameters}`. Before requesting a signature, the action-specific Broker profile must atomically reserve a positive generation, generate a 256-bit raw nonce inside that workload, and place only `nonce_hash = SHA-256(raw_nonce)` plus the reservation generation in the binding. The raw nonce is never a protobuf, JSON, persistence, audit, log, or browser field. The Signing Service exposes only the separate `IssueBreakGlassAuthorization` RPC under purpose `BREAK_GLASS`, domain `accord.break-glass.authorization.v1`, and DSSE payload type `application/vnd.accord.break-glass-authorization.v1+jcs`; strict and break-glass request types are rejected by each other's handlers.

`AuthorizationEvidenceService` is an internal read-only mTLS service implemented by the control plane through a narrow identity adapter and completed by the Git Delivery grant provider. It accepts only the Signing Service workload/audience, compares the mTLS tenant to `binding.tenant_id`, receives the complete closed binding plus its expected digest, independently JCS-canonicalizes and rehashes it, and checks every grant-owned field against the frozen grant/intent and two current receipts. It validates the positive Broker generation and `nonce_hash` shape but never receives or attempts to recover the raw nonce. It returns no source or mutation capability and conceals missing/unauthorized grants. The Signing Service calls it with a two-second deadline and at most three bounded retries; timeout, `UNAVAILABLE`, wrong version/state/digest, expiry, field mismatch, or a receipt set other than exactly two distinct human authorization receipts fails closed before KMS. The evidence snapshot digest is signed in the authorization wrapper and persisted beside the byte-identical idempotent response. Until Git Delivery installs the provider, the adapter returns `UNAVAILABLE` and no break-glass authorization can be issued.

Update `buf.gen.yaml` to generate Java messages with `buf.build/protocolbuffers/java:v32.0` and Java gRPC stubs with `buf.build/grpc/java:v1.73.0` into `contracts/protobuf/build/generated/sources/buf/main/java`. Generation uses `clean: true` and remains deterministic under the shared protobuf compatibility verifier.

Create `contracts/protobuf/build.gradle` and include `:contracts:protobuf` in `settings.gradle`:

```groovy
plugins { id 'java-library' }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

sourceSets.main.java.srcDir(layout.buildDirectory.dir('generated/sources/buf/main/java'))

dependencies {
    api(libs.protobuf.java)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    compileOnly(libs.javax.annotation.api)
}

tasks.named('compileJava') { dependsOn(rootProject.tasks.named('bufGenerate')) }
```

Every isolated Java security service depends on `:contracts:protobuf` for generated messages and stubs only. The generated contract project contains no control-plane domain code and exports no credentials, persistence adapters, or authorization implementation.

- [ ] **Step 4: Define DSSE envelope and conditionally scoped claims**

Create `dsse-envelope.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schemas.accord.inforvans.com/dsse/envelope/1-0-0",
  "type": "object",
  "additionalProperties": false,
  "required": ["payloadType", "payload", "signatures"],
  "properties": {
    "payloadType": { "type": "string", "minLength": 1 },
    "payload": { "type": "string", "contentEncoding": "base64" },
    "signatures": {
      "type": "array", "minItems": 1, "maxItems": 4,
      "items": {
        "type": "object", "additionalProperties": false, "required": ["keyid", "sig"],
        "properties": { "keyid": { "type": "string", "minLength": 1 }, "sig": { "type": "string", "contentEncoding": "base64" } }
      }
    }
  }
}
```

Create `signing-claims.schema.json` as the permanently retained v1 schema (later milestones add a sibling v2 file; they never edit this schema in place):

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schemas.accord.inforvans.com/dsse/signing-claims/1-0-0",
  "type": "object",
  "additionalProperties": false,
  "required": ["payload_type", "schema_version", "algorithm", "key_id", "purpose", "domain", "tenant_id", "scope_type", "scope_id", "object_id", "content_digest", "issued_at"],
  "properties": {
    "payload_type": { "type": "string", "minLength": 1 },
    "schema_version": { "const": "1.0.0" },
    "algorithm": { "enum": ["ES256"] },
    "key_id": { "type": "string", "minLength": 1 },
    "purpose": { "enum": ["requirement-publication", "confirmation", "acceptance", "strict-merge", "audit-anchor"] },
    "domain": { "type": "string", "pattern": "^accord\\.[a-z0-9.-]+\\.v1$" },
    "tenant_id": { "type": "string", "format": "uuid" },
    "scope_type": { "enum": ["tenant", "project", "repository"] },
    "scope_id": { "type": "string", "minLength": 1 },
    "immutable_repository_id": { "type": "string", "minLength": 1 },
    "object_id": { "type": "string", "minLength": 1 },
    "content_digest": { "type": "string", "pattern": "^sha256:[0-9a-f]{64}$" },
    "issued_at": { "type": "string", "format": "date-time" },
    "expires_at": { "type": "string", "format": "date-time" },
    "nonce": { "type": "string", "pattern": "^[A-Za-z0-9_-]{43}$" },
    "target_ref": { "type": "string", "pattern": "^refs/[^\\s]+$" },
    "expected_target_head_sha": { "type": "string", "pattern": "^[0-9a-f]{40,64}$" },
    "source_head_sha": { "type": "string", "pattern": "^[0-9a-f]{40,64}$" },
    "verified_result_tree_sha": { "type": "string", "pattern": "^[0-9a-f]{40,64}$" },
    "normalized_diff_digest": { "type": "string", "pattern": "^sha256:[0-9a-f]{64}$" },
    "required_checks_digest": { "type": "string", "pattern": "^sha256:[0-9a-f]{64}$" },
    "ci_attestation_digest": { "type": "string", "pattern": "^sha256:[0-9a-f]{64}$" },
    "subject_type": { "enum": ["work_item_pr", "requirement_metadata", "accepted_delivery_candidate", "emergency_change"] },
    "subject_id": { "type": "string", "minLength": 1, "maxLength": 255 },
    "subject_digest": { "type": "string", "pattern": "^sha256:[0-9a-f]{64}$" }
  },
  "allOf": [
    {
      "if": { "properties": { "scope_type": { "const": "repository" } }, "required": ["scope_type"] },
      "then": { "required": ["immutable_repository_id"] },
      "else": { "not": { "required": ["immutable_repository_id"] } }
    },
    {
      "if": { "properties": { "purpose": { "const": "strict-merge" } }, "required": ["purpose"] },
      "then": { "required": ["target_ref", "expected_target_head_sha", "source_head_sha", "verified_result_tree_sha", "normalized_diff_digest", "required_checks_digest", "ci_attestation_digest", "subject_type", "subject_id", "subject_digest", "expires_at", "nonce"] },
      "else": {
        "not": {
          "anyOf": [
            { "required": ["target_ref"] },
            { "required": ["expected_target_head_sha"] },
            { "required": ["source_head_sha"] },
            { "required": ["verified_result_tree_sha"] },
            { "required": ["normalized_diff_digest"] },
            { "required": ["required_checks_digest"] },
            { "required": ["ci_attestation_digest"] },
            { "required": ["subject_type"] },
            { "required": ["subject_id"] },
            { "required": ["subject_digest"] },
            { "required": ["expires_at"] },
            { "required": ["nonce"] }
          ]
        }
      }
    }
  ]
}
```

Create `break-glass-authorization.schema.json` as the separate signed-payload contract. It intentionally does not reference or extend the strict-merge claims schema:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schemas.accord.inforvans.com/dsse/break-glass-authorization/1-0",
  "type": "object",
  "additionalProperties": false,
  "required": ["schema_version", "purpose", "domain", "binding", "evidence_snapshot_digest"],
  "properties": {
    "schema_version": { "const": "1.0" },
    "purpose": { "const": "BREAK_GLASS" },
    "domain": { "const": "accord.break-glass.authorization.v1" },
    "binding": { "$ref": "#/$defs/binding" },
    "evidence_snapshot_digest": { "$ref": "#/$defs/digest" }
  },
  "$defs": {
    "digest": { "type": "string", "pattern": "^sha256:[0-9a-f]{64}$" },
    "binding": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "tenant_id", "repository_id", "target_ref", "grant_id", "grant_action_digest",
        "provider_action", "provider_action_parameters_digest", "scope_digest",
        "provider_fact_baseline_digest", "reason_digest", "human_authorization_receipt_digests",
        "recovery_obligation_digest", "issued_at", "expires_at", "broker_reservation_generation",
        "nonce_hash"
      ],
      "properties": {
        "tenant_id": { "type": "string", "format": "uuid" },
        "repository_id": { "type": "string", "format": "uuid" },
        "target_ref": { "type": "string", "pattern": "^refs/[^\\s]+$" },
        "grant_id": { "type": "string", "format": "uuid" },
        "grant_action_digest": { "$ref": "#/$defs/digest" },
        "provider_action": {
          "enum": ["MERGE_EMERGENCY_CHANGE", "RESTORE_PROTECTION_POLICY", "FENCE_PROVIDER_MUTATIONS"]
        },
        "provider_action_parameters_digest": { "$ref": "#/$defs/digest" },
        "scope_digest": { "$ref": "#/$defs/digest" },
        "provider_fact_baseline_digest": { "$ref": "#/$defs/digest" },
        "reason_digest": { "$ref": "#/$defs/digest" },
        "human_authorization_receipt_digests": {
          "type": "array", "minItems": 2, "maxItems": 2, "uniqueItems": true,
          "items": { "$ref": "#/$defs/digest" }
        },
        "recovery_obligation_digest": { "$ref": "#/$defs/digest" },
        "issued_at": { "type": "string", "format": "date-time" },
        "expires_at": { "type": "string", "format": "date-time" },
        "broker_reservation_generation": { "type": "integer", "minimum": 1 },
        "nonce_hash": { "$ref": "#/$defs/digest" }
      }
    }
  }
}
```

The schema verifier additionally enforces `issued_at < expires_at <= issued_at + 15 minutes`; JSON Schema alone cannot express that interval. It hashes only JCS(`binding`) for `binding_digest`, then signs JCS(`{schema_version,purpose,domain,binding,evidence_snapshot_digest}`) as the DSSE payload. The envelope `payloadType` must exactly equal `application/vnd.accord.break-glass-authorization.v1+jcs`; a broker stores and compares the binding digest and evidence snapshot digest independently and never substitutes the envelope digest for either.

- [ ] **Step 5: Add the repository-scoped golden claims vector**

Create `claims.input.json` and its JCS-normalized `claims.canonical.json` with this semantic value; the canonical file is one line with lexicographically ordered keys:

```json
{
  "payload_type": "application/vnd.accord.audit-anchor+json",
  "schema_version": "1.0.0",
  "algorithm": "ES256",
  "key_id": "kms://tenant-21/audit-anchor/1",
  "purpose": "audit-anchor",
  "domain": "accord.audit-anchor.v1",
  "tenant_id": "10000000-0000-0000-0000-000000000001",
  "scope_type": "repository",
  "scope_id": "77831",
  "immutable_repository_id": "77831",
  "object_id": "anchor-2026-07-24-0001",
  "content_digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "issued_at": "2026-07-24T10:00:00Z"
}
```

Use this exact content for `claims.canonical.json`:

```json
{"algorithm":"ES256","content_digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","domain":"accord.audit-anchor.v1","immutable_repository_id":"77831","issued_at":"2026-07-24T10:00:00Z","key_id":"kms://tenant-21/audit-anchor/1","object_id":"anchor-2026-07-24-0001","payload_type":"application/vnd.accord.audit-anchor+json","purpose":"audit-anchor","schema_version":"1.0.0","scope_id":"77831","scope_type":"repository","tenant_id":"10000000-0000-0000-0000-000000000001"}
```

Use this exact content for `claims.sha256`:

```text
sha256:54cdd46c02ccff798796063e5e9138d73032f7a65af512281852b4de85a88dd7
```

Generate `claims.sha256` from the canonical bytes with:

```bash
openssl dgst -sha256 contracts/golden-fixtures/signing/claims.canonical.json | awk '{print "sha256:" $2}' > contracts/golden-fixtures/signing/claims.sha256
```

Create `break-glass-authorization.input.json` with the following semantic value:

```json
{
  "schema_version": "1.0",
  "purpose": "BREAK_GLASS",
  "domain": "accord.break-glass.authorization.v1",
  "binding": {
    "tenant_id": "10000000-0000-0000-0000-000000000001",
    "repository_id": "70000000-0000-0000-0000-000000000001",
    "target_ref": "refs/heads/main",
    "grant_id": "80000000-0000-0000-0000-000000000001",
    "grant_action_digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "provider_action": "MERGE_EMERGENCY_CHANGE",
    "provider_action_parameters_digest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "scope_digest": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "provider_fact_baseline_digest": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
    "reason_digest": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
    "human_authorization_receipt_digests": [
      "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    ],
    "recovery_obligation_digest": "sha256:2222222222222222222222222222222222222222222222222222222222222222",
    "issued_at": "2026-07-24T10:00:00Z",
    "expires_at": "2026-07-24T10:10:00Z",
    "broker_reservation_generation": 1,
    "nonce_hash": "sha256:4444444444444444444444444444444444444444444444444444444444444444"
  },
  "evidence_snapshot_digest": "sha256:3333333333333333333333333333333333333333333333333333333333333333"
}
```

Use this exact one-line JCS value for `break-glass-authorization.canonical.json`:

```json
{"binding":{"broker_reservation_generation":1,"expires_at":"2026-07-24T10:10:00Z","grant_action_digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","grant_id":"80000000-0000-0000-0000-000000000001","human_authorization_receipt_digests":["sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","sha256:1111111111111111111111111111111111111111111111111111111111111111"],"issued_at":"2026-07-24T10:00:00Z","nonce_hash":"sha256:4444444444444444444444444444444444444444444444444444444444444444","provider_action":"MERGE_EMERGENCY_CHANGE","provider_action_parameters_digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","provider_fact_baseline_digest":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","reason_digest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","recovery_obligation_digest":"sha256:2222222222222222222222222222222222222222222222222222222222222222","repository_id":"70000000-0000-0000-0000-000000000001","scope_digest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","target_ref":"refs/heads/main","tenant_id":"10000000-0000-0000-0000-000000000001"},"domain":"accord.break-glass.authorization.v1","evidence_snapshot_digest":"sha256:3333333333333333333333333333333333333333333333333333333333333333","purpose":"BREAK_GLASS","schema_version":"1.0"}
```

Use this exact digest for `break-glass-authorization.sha256` and verify it from the canonical bytes in the contract test:

```text
sha256:51474f369e1943dd7b72e65ecaa9b3fabd964d219ae09652aa4a32e402a21fa3
```

- [ ] **Step 6: Validate and generate all signing contracts**

Run:

```bash
node --test tests/contracts/signing-contract.test.mjs
buf lint
buf generate
pwsh -NoProfile -File tests/architecture/verify-protobuf-compatibility.ps1
```

Expected: all four Node tests pass; Buf lint and breaking checks pass; generated Java messages and gRPC stubs expose `SignDsse`, `VerifyDsse`, strict-only `ExactAuthorizationBinding`/`ConsumeAuthorizationToken`, the closed `BreakGlassAuthorizationBinding`, `IssueBreakGlassAuthorization`, and the read-only `AuthorizationEvidenceService` without a fifth merge subject.

- [ ] **Step 7: Commit shared signing contracts**

```bash
git add settings.gradle contracts/protobuf contracts/json-schema/dsse-envelope.schema.json contracts/dsse-payloads/signing-claims.schema.json contracts/dsse-payloads/break-glass-authorization.schema.json contracts/golden-fixtures/signing tests/contracts/signing-contract.test.mjs buf.gen.yaml
git commit -m "feat: define purpose-bound signing contracts"
```

### Task 11: Create The Separate Trust Store And Purpose-Key Registry

**Files:**
- Create: `database/signing-service/bootstrap/00-pre-flyway-roles.sql`
- Create: `database/signing-service/migrations/V001__trust_store.sql`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/trust/TrustModel.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/trust/TrustRepository.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/trust/TrustRepositoryPostgreSqlTest.java`
- Create: `security-services/signing-service/src/testFixtures/java/com/inforvans/accord/signing/testdb/SigningPostgreSqlFixture.java`
- Modify: `security-services/signing-service/build.gradle`

- [ ] **Step 1: Write failing purpose and compromise-window tests**

Create `TrustRepositoryPostgreSqlTest.java`:

```java
package com.inforvans.accord.signing.trust;

import com.inforvans.accord.signing.testdb.SigningPostgreSqlFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustRepositoryPostgreSqlTest extends SigningPostgreSqlFixture {
    @Test
    void activeKeyRequiresExactTenantAndPurpose() {
        var repository = new TrustRepository(runtimeDsl(), tenantTransactions());
        var tenantA = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var tenantB = UUID.fromString("10000000-0000-0000-0000-000000000002");
        var now = Instant.parse("2026-07-24T10:00:00Z");
        var record = new TrustModel.TrustRecord(
            UUID.randomUUID(), tenantA, TrustModel.Purpose.AUDIT_ANCHOR,
            "kms://tenant-a/audit-anchor/1", "ES256", new byte[]{1}, TrustModel.State.ACTIVE,
            now.minus(1, ChronoUnit.HOURS), null, null, null, null
        );
        repository.insert(record);
        assertThat(repository.resolveActive(tenantA, TrustModel.Purpose.AUDIT_ANCHOR, now).id()).isEqualTo(record.id());
        assertThatThrownBy(() -> repository.resolveActive(tenantA, TrustModel.Purpose.STRICT_MERGE, now)).isInstanceOf(TrustModel.NoTrustedKey.class);
        assertThatThrownBy(() -> repository.resolveActive(tenantB, TrustModel.Purpose.AUDIT_ANCHOR, now)).isInstanceOf(TrustModel.NoTrustedKey.class);
    }

    @Test
    void historicalTrustUsesSigningAndIndependentAnchorTimes() {
        var compromised = Instant.parse("2026-07-24T12:00:00Z");
        var before = new TrustModel.HistoricalEvidence(compromised.minusSeconds(3600), compromised.minusSeconds(1800));
        var after = new TrustModel.HistoricalEvidence(compromised.plusSeconds(60), compromised.plusSeconds(120));
        var known = trustRecord(TrustModel.RevocationMode.COMPROMISED_FROM_KNOWN_TIME, compromised);
        assertThat(known.trusts(before)).isTrue();
        assertThat(known.trusts(after)).isFalse();
        assertThat(trustRecord(TrustModel.RevocationMode.COMPROMISE_START_UNKNOWN, compromised).trusts(before)).isFalse();
    }
}
```

In the same Testcontainers suite, add `TestSigningRuntimeRLSAndPrivileges`. Seed distinguishable A/B rows only through a `db.MigratorForSecurityTests(t)` transaction that first executes `SET LOCAL ROLE accord_signing_owner`, then query through `db.Pool` as `accord_signing_runtime`: a transaction with `set_config('app.tenant_id', tenantA, true)` sees only A, a transaction set to B sees only B, and an unset runtime transaction sees neither and cannot insert. Assert `pg_class.relrowsecurity=true` and `relforcerowsecurity=true` for all three tables; `pg_policies` has exactly one `tenant_isolation` policy per table with `cmd='ALL'`, roles `{public}`, and both `qual` and `with_check` equivalent to `tenant_id = accord_security.current_tenant_id()`; and `information_schema.role_table_grants` contains only the explicit least-privilege matrix below. The runtime role must have `rolsuper=false`, `rolbypassrls=false`, no membership in `accord_signing_owner`, and no `TRUNCATE`, `REFERENCES`, `TRIGGER`, or schema `CREATE` privilege. Prove runtime UPDATE/DELETE is denied and an owner-context UPDATE/DELETE reaches `signing_audit_event_append_only` and fails with SQLSTATE `55000`; also prove every trust-record reference is a composite tenant foreign key.

- [ ] **Step 2: Run the test and verify trust storage is absent**

Run: `./gradlew :security-services:signing-service:test --tests '*TrustRepositoryPostgreSqlTest'`

Expected: FAIL because `TrustModel.TrustRecord`, `TrustRepository`, and signing migration version 001 do not exist.

- [ ] **Step 3: Add the signing-only trust and idempotency schema**

Create `bootstrap/00-pre-flyway-roles.sql` and run it with an administrator before Flyway. It is idempotent, validates existing role attributes, and creates the runtime role before any migration references it; managed-secret provisioning supplies passwords outside source control:

```sql
DO $roles$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_signing_owner') THEN
    CREATE ROLE accord_signing_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT BYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_signing_migrator') THEN
    CREATE ROLE accord_signing_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'accord_signing_runtime') THEN
    CREATE ROLE accord_signing_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_roles
    WHERE (rolname = 'accord_signing_owner' AND (rolcanlogin OR rolsuper OR NOT rolbypassrls))
       OR (rolname = 'accord_signing_migrator' AND (NOT rolcanlogin OR rolsuper OR rolbypassrls))
       OR (rolname = 'accord_signing_runtime' AND (NOT rolcanlogin OR rolsuper OR rolbypassrls))
  ) THEN
    RAISE EXCEPTION 'signing role attributes do not match the locked contract';
  END IF;
  EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO accord_signing_owner, accord_signing_migrator', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO accord_signing_runtime', current_database());
END $roles$;
GRANT accord_signing_owner TO accord_signing_migrator WITH SET TRUE;
GRANT USAGE, CREATE ON SCHEMA public TO accord_signing_owner;
REVOKE ALL ON SCHEMA public FROM accord_signing_runtime;
GRANT USAGE ON SCHEMA public TO accord_signing_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE accord_signing_owner IN SCHEMA public REVOKE ALL ON TABLES FROM accord_signing_runtime;
```

The deployment bootstrap provisions `accord_signing_migrator` as a separately held migration credential and grants it `accord_signing_owner`; the application pod never receives that credential or owner membership. In disposable tests, the administrator connection applies the same bootstrap and is granted the owner role only long enough to run migrations.

Create `V001__trust_store.sql`; the migration executes as the NOLOGIN owner, installs the signing-local policy helper, enforces all tenant tables, and only then grants runtime DML:

```sql
SET ROLE accord_signing_owner;

CREATE SCHEMA IF NOT EXISTS accord_security AUTHORIZATION accord_signing_owner;
REVOKE ALL ON SCHEMA accord_security FROM PUBLIC;
GRANT USAGE ON SCHEMA accord_security TO accord_signing_runtime;

CREATE OR REPLACE FUNCTION accord_security.current_tenant_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE OR REPLACE FUNCTION accord_security.enforce_tenant_table(target regclass)
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
  EXECUTE format(
    'CREATE POLICY tenant_isolation ON %s FOR ALL TO PUBLIC USING (tenant_id = accord_security.current_tenant_id()) WITH CHECK (tenant_id = accord_security.current_tenant_id())',
    target
  );
END
$function$;

REVOKE ALL ON FUNCTION accord_security.current_tenant_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION accord_security.enforce_tenant_table(regclass) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION accord_security.current_tenant_id() TO accord_signing_runtime;

CREATE TABLE key_trust_record (
    trust_record_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    purpose varchar(40) NOT NULL CHECK (purpose IN ('REQUIREMENT_PUBLICATION', 'CONFIRMATION', 'ACCEPTANCE', 'STRICT_MERGE', 'BREAK_GLASS', 'AUDIT_ANCHOR')),
    key_id varchar(1024) NOT NULL,
    algorithm varchar(16) NOT NULL CHECK (algorithm IN ('ES256')),
    public_key_spki bytea NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE', 'RETIRED', 'REVOKED')),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    compromised_at timestamptz,
    revoked_at timestamptz,
    revocation_mode varchar(40) CHECK (revocation_mode IN ('ROUTINE', 'COMPROMISED_FROM_KNOWN_TIME', 'COMPROMISE_START_UNKNOWN')),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    CHECK (compromised_at IS NULL OR compromised_at >= valid_from),
    CHECK ((state = 'REVOKED') = (revoked_at IS NOT NULL)),
    CHECK ((revoked_at IS NULL AND revocation_mode IS NULL) OR (revoked_at IS NOT NULL AND revocation_mode IS NOT NULL)),
    PRIMARY KEY (tenant_id, trust_record_id),
    UNIQUE (tenant_id, key_id)
);
CREATE UNIQUE INDEX one_active_key_per_purpose
    ON key_trust_record (tenant_id, purpose) WHERE state = 'ACTIVE';

CREATE TABLE signing_idempotency_result (
    tenant_id uuid NOT NULL,
    caller_workload_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('CLAIMED', 'COMPLETED')),
    request_digest char(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    lease_token uuid,
    lease_generation bigint NOT NULL DEFAULT 0 CHECK (lease_generation >= 0),
    lease_expires_at timestamptz,
    response_envelope bytea,
    response_digest char(71) CHECK (response_digest ~ '^sha256:[0-9a-f]{64}$'),
    evidence_snapshot_digest char(71) CHECK (evidence_snapshot_digest ~ '^sha256:[0-9a-f]{64}$'),
    trust_record_id uuid,
    signed_at timestamptz,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, caller_workload_id, operation, idempotency_key),
    FOREIGN KEY (tenant_id, trust_record_id) REFERENCES key_trust_record(tenant_id, trust_record_id),
    CHECK (
      (state = 'CLAIMED' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL
        AND response_envelope IS NULL AND response_digest IS NULL AND trust_record_id IS NULL AND signed_at IS NULL)
      OR
      (state = 'COMPLETED' AND lease_token IS NULL AND lease_expires_at IS NULL
        AND response_envelope IS NOT NULL AND response_digest IS NOT NULL AND trust_record_id IS NOT NULL AND signed_at IS NOT NULL)
    )
);

CREATE TABLE signing_audit_event (
    event_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    caller_workload_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    purpose varchar(40) NOT NULL,
    object_id varchar(255) NOT NULL,
    request_digest char(71) NOT NULL,
    result_code varchar(64) NOT NULL,
    trust_record_id uuid,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, trust_record_id) REFERENCES key_trust_record(tenant_id, trust_record_id)
);

CREATE FUNCTION reject_signing_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'signing audit is append-only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER signing_audit_event_append_only BEFORE UPDATE OR DELETE ON signing_audit_event
FOR EACH ROW EXECUTE FUNCTION reject_signing_audit_mutation();

SELECT accord_security.enforce_tenant_table(name::regclass)
FROM unnest(ARRAY[
  'public.key_trust_record',
  'public.signing_idempotency_result',
  'public.signing_audit_event'
]) AS names(name);

REVOKE ALL ON TABLE key_trust_record, signing_idempotency_result, signing_audit_event FROM PUBLIC, accord_signing_runtime;
GRANT SELECT, INSERT, UPDATE ON TABLE key_trust_record TO accord_signing_runtime;
GRANT SELECT, INSERT, UPDATE ON TABLE signing_idempotency_result TO accord_signing_runtime;
GRANT SELECT, INSERT ON TABLE signing_audit_event TO accord_signing_runtime;
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE signing_audit_event FROM accord_signing_runtime;

RESET ROLE;
```

`signing_idempotency_result` is a fenced two-state operation record, not a cache. Claiming locks the exact primary key under tenant RLS: an absent key inserts `CLAIMED` with a random lease token, generation 1, and a 30-second lease; the same key plus another request digest returns `ALREADY_EXISTS`; an unexpired claim returns `IN_PROGRESS`; and an expired claim advances generation and replaces the lease token. Completion updates only the matching `(lease_token, lease_generation, state='CLAIMED')` row to `COMPLETED`, clears the lease, and stores the exact envelope bytes in `bytea` together with response, trust-record, and evidence-snapshot digests. JSONB is forbidden for the envelope because it cannot preserve byte-identical replay. A completed retry returns those bytes without another evidence lookup or KMS call. A worker that crashes after KMS but before completion exposes no envelope; after lease takeover a new equivalent signature may be produced, but only one fenced envelope can be persisted or returned. Add concurrent, changed-digest, crash-before-completion, stale-generation-completion, and completed-byte-replay cases to `TrustRepositoryPostgreSqlTest.java` and require exactly one completed row and one successful response across 64 callers.

This database is physically separate from `accord`. Only the Signing Service runtime identity can connect for application traffic. The control plane has no credential for it and reaches it solely through mTLS gRPC; the migration owner credential is held only by the migration job.

- [ ] **Step 4: Define exact purpose and historical-trust semantics**

Create `TrustModel.java`:

```java
package com.inforvans.accord.signing.trust;

import java.time.Instant;
import java.util.UUID;

public final class TrustModel {
    private TrustModel() {}

    public enum Purpose { REQUIREMENT_PUBLICATION, CONFIRMATION, ACCEPTANCE, STRICT_MERGE, BREAK_GLASS, AUDIT_ANCHOR }
    public enum State { ACTIVE, RETIRED, REVOKED }
    public enum RevocationMode { ROUTINE, COMPROMISED_FROM_KNOWN_TIME, COMPROMISE_START_UNKNOWN }

    public record HistoricalEvidence(Instant signedAt, Instant anchoredAt) {}

    public record TrustRecord(
        UUID id, UUID tenantId, Purpose purpose, String keyId, String algorithm, byte[] publicKeySpki,
        State state, Instant validFrom, Instant validUntil, Instant compromisedAt,
        Instant revokedAt, RevocationMode revocationMode
    ) {
        public boolean trusts(HistoricalEvidence evidence) {
            if (evidence.signedAt().isBefore(validFrom) || evidence.anchoredAt().isBefore(evidence.signedAt())) return false;
            if (validUntil != null && !evidence.signedAt().isBefore(validUntil)) return false;
            if (revocationMode == RevocationMode.COMPROMISE_START_UNKNOWN) return false;
            if (revocationMode == RevocationMode.COMPROMISED_FROM_KNOWN_TIME) {
                return compromisedAt != null && evidence.signedAt().isBefore(compromisedAt)
                    && evidence.anchoredAt().isBefore(compromisedAt);
            }
            return true;
        }
    }

    public static final class NoTrustedKey extends RuntimeException {
        public NoTrustedKey() { super("no trusted key for tenant and purpose"); }
    }
}
```

- [ ] **Step 5: Implement exact-tenant, exact-purpose resolution**

Create `TrustRepository.java` with parameterized jOOQ queries:

```java
package com.inforvans.accord.signing.trust;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;

public final class TrustRepository {
    private final DSLContext dsl;

    public TrustRepository(DSLContext dsl) { this.dsl = dsl; }

    public TrustModel.TrustRecord resolveActive(UUID tenantId, TrustModel.Purpose purpose, Instant at) {
        return inTenant(tenantId, tx -> {
            var row = tx.fetchOne("""
                SELECT trust_record_id, tenant_id, purpose, key_id, algorithm, public_key_spki,
                       state, valid_from, valid_until, compromised_at, revoked_at, revocation_mode
                FROM key_trust_record
                WHERE tenant_id=? AND purpose=? AND state='ACTIVE'
                  AND valid_from<=? AND (valid_until IS NULL OR valid_until>?)
                """, tenantId, purpose.name(), at, at);
            if (row == null) throw new TrustModel.NoTrustedKey();
            return map(row);
        });
    }

    public void insert(TrustModel.TrustRecord record) {
        inTenant(record.tenantId(), tx -> {
            tx.execute("""
                INSERT INTO key_trust_record
                  (trust_record_id,tenant_id,purpose,key_id,algorithm,public_key_spki,state,
                   valid_from,valid_until,compromised_at,revoked_at,revocation_mode)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, record.id(), record.tenantId(), record.purpose().name(), record.keyId(),
                record.algorithm(), record.publicKeySpki(), record.state().name(), record.validFrom(),
                record.validUntil(), record.compromisedAt(), record.revokedAt(),
                record.revocationMode() == null ? null : record.revocationMode().name());
            return null;
        });
    }

    private <T> T inTenant(UUID tenantId, java.util.function.Function<DSLContext, T> work) {
        return dsl.transactionResult(configuration -> {
            var tx = configuration.dsl();
            tx.execute("SELECT set_config('app.tenant_id', ?, true)", tenantId.toString());
            return work.apply(tx);
        });
    }
}
```

Every trust-store operation follows this transaction pattern. `set_config(..., true)` is transaction-local, every query executes through `tx`, and no repository method issues a tenant query through the pool-level `DSLContext`.

Create `SigningPostgreSqlFixture.java`:

```java
package com.inforvans.accord.signing.testdb;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SigningPostgreSqlFixture {
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5")
        .withDatabaseName("accord_signing").withUsername("postgres").withPassword("postgres");
    private HikariDataSource runtimeDataSource;
    private DSLContext runtimeDsl;
    private DSLContext migratorDsl;

    @BeforeAll
    void startSigningDatabase() throws Exception {
        postgres.start();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(Files.readString(Path.of("../../../../database/signing-service/bootstrap/00-pre-flyway-roles.sql")));
            statement.execute("ALTER ROLE accord_signing_migrator PASSWORD 'migrator-test'");
            statement.execute("ALTER ROLE accord_signing_runtime PASSWORD 'runtime-test'");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), "accord_signing_migrator", "migrator-test")
            .locations("filesystem:../../../../database/signing-service/migrations").load().migrate();
        migratorDsl = DSL.using(postgres.getJdbcUrl(), "accord_signing_migrator", "migrator-test", SQLDialect.POSTGRES);
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername("accord_signing_runtime");
        config.setPassword("runtime-test");
        config.setMaximumPoolSize(4);
        runtimeDataSource = new HikariDataSource(config);
        runtimeDsl = DSL.using(runtimeDataSource, SQLDialect.POSTGRES);
    }

    protected DSLContext runtimeDsl() { return runtimeDsl; }
    protected DSLContext migratorForSecurityTests() { return migratorDsl; }

    @AfterAll
    void stopSigningDatabase() {
        runtimeDsl.close();
        runtimeDataSource.close();
        migratorDsl.close();
        postgres.stop();
    }
}
```
`Pool` is always the NOBYPASSRLS runtime connection. The migrator handle is deliberately available only through `MigratorForSecurityTests` for A/B seeding and catalog assertions; production repositories and service tests receive only `Pool`. The bootstrap always runs before V001, and no test grants runtime table privileges after a migration.

Update `security-services/signing-service/build.gradle`:

```groovy
plugins {
    id 'java'
    alias(libs.plugins.spring.boot)
    id 'java-test-fixtures'
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    implementation(project(':contracts:protobuf'))
    implementation(libs.spring.boot.jooq)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.grpc.server.spring.boot.starter)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.testcontainers.postgresql)
}
tasks.withType(Test).configureEach { useJUnitPlatform() }
```

- [ ] **Step 6: Run trust-store and race tests**

Run:

```bash
./gradlew :security-services:signing-service:dependencies --write-locks
./gradlew :security-services:signing-service:test --tests '*TrustRepositoryPostgreSqlTest'
```

Expected: PASS. Tenant or purpose mismatch yields `NoTrustedKey`; pre-compromise, independently anchored evidence remains trusted only when both times precede a known compromise; unknown compromise start rejects all affected evidence.

- [ ] **Step 7: Commit the isolated trust store**

```bash
git add database/signing-service security-services/signing-service
git commit -m "feat: isolate purpose-bound signing trust"
```

### Task 12: Sign And Verify JCS Payloads Through Purpose-Bound KMS Keys

**Files:**
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/crypto/CanonicalDsse.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/kms/DigestSigner.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/kms/AwsKmsDigestSigner.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/service/SigningModel.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/service/SigningService.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/service/BreakGlassIssuanceService.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/idempotency/SigningIdempotencyRepository.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/evidence/BreakGlassEvidenceClient.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/service/SigningFixtures.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/service/SigningServiceTest.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/service/BreakGlassIssuanceServiceTest.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/idempotency/SigningIdempotencyPostgreSqlTest.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/evidence/BreakGlassEvidenceClientTest.java`
- Modify: `security-services/signing-service/build.gradle`

- [ ] **Step 1: Write failing canonicalization, purpose-confusion, evidence, and replay tests**

Create `SigningServiceTest.java` with JUnit 5 and AssertJ. The test fixture uses an in-memory P-256 key only as a `DigestSigner` test double; production code never loads private key material.

```java
package com.inforvans.accord.signing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inforvans.accord.signing.kms.DigestSigner;
import com.inforvans.accord.signing.trust.TrustModel.Purpose;
import com.inforvans.accord.signing.trust.TrustModel.TrustRecord;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SigningServiceTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

    @Test
    void canonicalizesAndUsesOnlyTheExactPurposeKey() throws Exception {
        var pair = KeyPairGenerator.getInstance("EC");
        pair.initialize(256);
        var keyPair = pair.generateKeyPair();
        DigestSigner signer = (keyId, pae) -> {
            var signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(pae);
            return signature.sign();
        };
        var trust = new TrustRecord(
            UUID.randomUUID(), TENANT, Purpose.AUDIT_ANCHOR,
            "kms://tenant-a/audit-anchor/1", "ES256", keyPair.getPublic().getEncoded(),
            com.inforvans.accord.signing.trust.TrustModel.State.ACTIVE,
            NOW.minusSeconds(3600), null, null, null, null
        );
        var service = SigningFixtures.service(trust, signer, Clock.fixed(NOW, ZoneOffset.UTC));
        var request = SigningModel.SignRequest.repository(
            TENANT, "77831", Purpose.AUDIT_ANCHOR, "accord.audit-anchor.v1",
            "anchor-1", "application/vnd.accord.audit-root+json", "{\"z\":2,\"a\":1}".getBytes()
        );

        var signed = service.sign(request);

        assertThat(new String(signed.canonicalSubject())).isEqualTo("{\"a\":1,\"z\":2}");
        assertThat(signed.keyId()).isEqualTo(trust.keyId());
        assertThat(service.verify(signed.envelope(), Purpose.AUDIT_ANCHOR, "accord.audit-anchor.v1").valid()).isTrue();
    }

    @Test
    void rejectsPurposeDomainRepositoryAndAuthorizationConfusionBeforeKms() {
        var kmsCalls = new java.util.concurrent.atomic.AtomicInteger();
        DigestSigner signer = (keyId, pae) -> {
            kmsCalls.incrementAndGet();
            throw new AssertionError("KMS must not be called");
        };
        var service = SigningFixtures.serviceWithoutTrust(signer, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.sign(SigningFixtures.wrongDomainRequest(TENANT)))
            .hasMessage("purpose_domain_mismatch");
        assertThatThrownBy(() -> service.sign(SigningFixtures.syntheticRepositoryRequest(TENANT)))
            .hasMessage("repository_scope_mismatch");
        assertThatThrownBy(() -> service.sign(SigningFixtures.strictMergeWithChangedTree(TENANT)))
            .hasMessage("authorization_binding_invalid");
        assertThat(kmsCalls).hasValue(0);
    }
}
```

Create `SigningFixtures.java` with the fixed trust repository, local public-key verifier, rejecting/counting signer, fixed clock, and complete valid request builders used above. Builders must return a valid request first and expose named copy methods that change exactly one field, so a negative assertion cannot pass because an unrelated prerequisite is already invalid.

Create `BreakGlassIssuanceServiceTest.java` with a table-driven matrix that changes one input at a time. It must assert: the dedicated RPC rejects a generic signing request; the evidence snapshot is signed and bound to tenant, immutable repository, target ref, expected head, source head, result tree, normalized diff, checks, CI attestation, emergency change subject, action, raw nonce hash, reservation generation, and expiry; exactly two active receipts from distinct natural people are required; the two required organizational roles are both present; `expires_at` is after issuance and no more than 15 minutes later; every evidence timeout, missing field, stale binding, receipt duplication, and closed-enum violation returns before KMS and before nonce persistence; and 64 concurrent identical calls return one byte-identical persisted envelope.

Create `SigningIdempotencyPostgreSqlTest.java` against the Task 11 Testcontainers fixture. Competing claims use the same `(tenant_id, caller_workload_id, operation, idempotency_key)` and request digest. Exactly one claim owns the active `lease_generation`; a stale generation cannot complete; a different digest receives `IDEMPOTENCY_CONFLICT`; a completed retry returns the exact stored envelope bytes, key ID, trust-record ID, and signing instant without invoking KMS.

- [ ] **Step 2: Run the focused tests and verify production classes are absent**

Run:

```bash
./gradlew :security-services:signing-service:test --tests '*SigningServiceTest' --tests '*BreakGlassIssuanceServiceTest' --tests '*SigningIdempotencyPostgreSqlTest'
```

Expected: FAIL at test compilation because `CanonicalDsse`, `SigningService`, `BreakGlassIssuanceService`, and `SigningIdempotencyRepository` do not exist. Testcontainers and Flyway must start successfully; an unavailable fixture is an environment failure, not the expected red state.

- [ ] **Step 3: Add the vetted JCS, KMS, and gRPC client dependencies**

Modify `security-services/signing-service/build.gradle`:

```groovy
dependencies {
    implementation platform('software.amazon.awssdk:bom:2.31.54')
    implementation 'software.amazon.awssdk:kms'
    implementation 'org.erdtman:java-json-canonicalization:1.1'
    implementation 'io.grpc:grpc-stub'
    implementation 'io.grpc:grpc-protobuf'
    implementation 'org.jooq:jooq'
    implementation 'org.springframework.boot:spring-boot-starter-jooq'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.assertj:assertj-core'
    testImplementation 'org.testcontainers:postgresql'
}
```

Dependency verification and locking are mandatory. The RFC 8785 implementation must pass the repository golden fixtures for Unicode, IEEE-754 number rendering, object-key ordering, and invalid duplicate-key inputs. A hand-written key sorter is not acceptable.

- [ ] **Step 4: Implement RFC 8785 canonicalization, DSSE PAE, and the KMS digest adapter**

Create `CanonicalDsse.java`:

```java
package com.inforvans.accord.signing.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.erdtman.jcs.JsonCanonicalizer;

public final class CanonicalDsse {
    private final ObjectMapper mapper;

    public CanonicalDsse(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] canonicalize(byte[] json) {
        try {
            return new JsonCanonicalizer(json).getEncodedUTF8();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("invalid_json", error);
        }
    }

    public byte[] pae(String payloadType, byte[] payload) {
        var type = payloadType.getBytes(StandardCharsets.UTF_8);
        var prefix = ("DSSEv1 " + type.length + " " + payloadType + " " + payload.length + " ")
            .getBytes(StandardCharsets.UTF_8);
        var result = new byte[prefix.length + payload.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(payload, 0, result, prefix.length, payload.length);
        return result;
    }

    public byte[] envelope(String payloadType, String keyId, byte[] payload, byte[] signature) {
        try {
            return mapper.writeValueAsBytes(new Envelope(
                payloadType,
                Base64.getEncoder().encodeToString(payload),
                List.of(new DsseSignature(keyId, Base64.getEncoder().encodeToString(signature)))
            ));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("envelope_encoding_failed", error);
        }
    }

    public String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Envelope(
        @JsonProperty("payloadType") String payloadType,
        String payload,
        List<DsseSignature> signatures
    ) {}

    public record DsseSignature(@JsonProperty("keyid") String keyId, @JsonProperty("sig") String value) {}
}
```

Create `DigestSigner.java` and `AwsKmsDigestSigner.java`:

```java
package com.inforvans.accord.signing.kms;

@FunctionalInterface
public interface DigestSigner {
    byte[] signEs256(String keyId, byte[] dssePreAuthEncoding);
}
```

```java
package com.inforvans.accord.signing.kms;

import java.security.MessageDigest;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

public final class AwsKmsDigestSigner implements DigestSigner {
    private final KmsClient client;

    public AwsKmsDigestSigner(KmsClient client) {
        this.client = client;
    }

    @Override
    public byte[] signEs256(String keyId, byte[] pae) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(pae);
            return client.sign(SignRequest.builder()
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(digest))
                .messageType(MessageType.DIGEST)
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
                .build()).signature().asByteArray();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
```

The Signing Service role receives only `kms:Sign` for the explicit purpose-key ARNs. It cannot decrypt, export data keys, mutate key policies, publish requirements, call merge APIs, or use another tenant's key. KMS request metrics include purpose and non-sensitive key alias dimensions but never subject bodies or raw nonces.

- [ ] **Step 5: Implement closed signing models, domain separation, verification, and durable replay**

Create `SigningModel.java` as a non-instantiable holder of Java records and closed enums. `Purpose` from Task 11 maps one-to-one to these domains: `REQUIREMENT_PUBLICATION -> accord.requirement-publication.v1`, `CONFIRMATION -> accord.confirmation.v1`, `ACCEPTANCE -> accord.acceptance.v1`, `STRICT_MERGE -> accord.strict-merge.v1`, `BREAK_GLASS -> accord.break-glass.authorization.v1`, and `AUDIT_ANCHOR -> accord.audit-anchor.v1`. Generic signing rejects `BREAK_GLASS`.

The exact strict-merge authorization record contains `targetRef`, `expectedTargetHeadSha`, `sourceHeadSha`, `verifiedResultTreeSha`, `normalizedDiffDigest`, `requiredChecksDigest`, `ciAttestationDigest`, a closed subject (`work_item_pr`, `requirement_metadata`, `accepted_delivery_candidate`, or `emergency_change`), 32 random bytes encoded as 43 unpadded base64url characters, and `expiresAt`. SHA values and `sha256:` digests are lowercase and length checked. Repository scope requires `scopeId == immutableRepositoryId`.

Create `SigningService.java`. Its operation order is normative:

1. Validate purpose/domain/payload-type/scope/subject and exact authorization before any external call.
2. Canonicalize the source-free subject with RFC 8785 and calculate its digest.
3. Resolve one active tenant-and-purpose trust record at the signing instant.
4. Build and canonicalize signed claims containing schema version, algorithm, key ID, purpose, domain, tenant, scope, immutable repository ID where applicable, object ID, content digest, issuance time, and all exact authorization fields.
5. Sign DSSE PAE through `DigestSigner`, persist the complete response through the fenced idempotency repository, and return only the persisted bytes.
6. Verify by parsing a single-signature envelope, requiring canonical payload bytes, resolving historical trust at `issued_at`, checking purpose/domain/key/algorithm/scope, validating authorization expiry, calculating DSSE PAE, and verifying ES256 against the stored SPKI public key.

Create `SigningIdempotencyRepository.java` over the Task 11 table. `claim` runs in a transaction with `SET LOCAL app.tenant_id`, locks the exact key, compares the canonical request digest, increments `lease_generation` only when an expired lease is reclaimed, and returns `OWNER`, `IN_PROGRESS`, or `COMPLETED`. `complete` updates only where `state='CLAIMED' AND lease_token=? AND lease_generation=?`; it stores envelope bytes, envelope digest, evidence snapshot digest when applicable, trust-record ID, and signed instant in the same row. A replay resolves the key ID from the persisted trust-record reference. Every method accepts a transaction-scoped jOOQ `DSLContext`; none issues work through a pool-level context after setting the tenant.

- [ ] **Step 6: Implement the separate evidence client and dedicated break-glass issuer**

Create `BreakGlassEvidenceClient.java` around the generated Java gRPC stub. Use a separate client certificate, a pinned private CA bundle, TLS 1.3, authority checking, 2-second per-attempt deadlines, at most two retry attempts for read-only evidence calls, and OpenTelemetry propagation. The response validator requires matching tenant, immutable repository, reservation ID, raw nonce hash, reservation generation, signed snapshot digest, and unexpired observation time. Transport errors, partial responses, unknown enum values, or stale evidence are final fail-closed outcomes.

Create `BreakGlassIssuanceService.java` with a dedicated `issue` method that cannot accept `SignRequest`. It performs these checks before KMS:

- action is one value from the protobuf closed provider-action enum;
- scope is a real immutable repository binding and every Git digest matches the signed evidence snapshot;
- `expiresAt` is strictly after `issuedAt` and no later than `issuedAt + 15 minutes`;
- there are exactly two active human approval receipts, from different natural-person IDs, covering the required business and development emergency roles;
- receipt digests, policy version, binding version, incident ID, reason digest, raw nonce hash, reservation ID, and positive reservation generation all match evidence;
- the request digest includes all fields above, so retrying with any changed evidence produces an idempotency conflict.

The service first obtains a fenced PostgreSQL claim, then reads evidence, then signs. Completion persists the byte sequence and fencing generation. If the process loses its lease after KMS returns, it may not overwrite the winner; it rereads and returns a completed identical response or a retryable in-progress result. It never writes to the strict-merge authorization nonce table.

- [ ] **Step 7: Run golden, negative, concurrency, and dependency gates**

Run:

```bash
./gradlew :contracts:golden-fixtures:test
./gradlew :security-services:signing-service:test --tests '*SigningServiceTest' --tests '*BreakGlassIssuanceServiceTest' --tests '*SigningIdempotencyPostgreSqlTest' --tests '*BreakGlassEvidenceClientTest'
./gradlew :security-services:signing-service:dependencyCheckAnalyze
./gradlew :security-services:signing-service:dependencyInsight --dependency java-json-canonicalization --configuration runtimeClasspath
```

Expected: PASS. Golden JCS and DSSE bytes match exactly on Linux and Windows; wrong purpose, domain, payload type, tenant, scope, repository, subject, Git digest, nonce, expiry, or trust window fails before KMS; strict and emergency contracts cannot enter each other's path; every evidence failure is fail closed; exactly two distinct human receipts and the 15-minute ceiling are enforced; stale fencing cannot complete; and 64 identical concurrent requests expose one persisted envelope byte sequence.

- [ ] **Step 8: Commit signing and verification**

```bash
git add security-services/signing-service
git commit -m "feat: sign canonical claims with purpose KMS keys"
```

### Task 13: Atomically Consume Exact Authorization Nonces Over mTLS

**Files:**
- Create: `database/signing-service/migrations/V002__authorization_nonce.sql`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/nonce/AuthorizationNonce.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/nonce/AuthorizationNonceRepository.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/transport/CallerIdentity.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/transport/MtlsCallerInterceptor.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/nonce/NonceFixtures.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/nonce/AuthorizationNoncePostgreSqlTest.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/transport/MtlsCallerInterceptorTest.java`
- Modify: `security-services/signing-service/build.gradle`

- [ ] **Step 1: Write failing exact-binding, cross-tenant, and concurrent-consumption tests**

Create `AuthorizationNoncePostgreSqlTest.java` using the Task 11 PostgreSQL fixture. Seed only through the migration-owner fixture after `SET LOCAL ROLE accord_signing_owner`; execute application behavior only with `accord_signing_runtime`.

```java
package com.inforvans.accord.signing.nonce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inforvans.accord.signing.testdb.SigningPostgreSqlFixture;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class AuthorizationNoncePostgreSqlTest extends SigningPostgreSqlFixture {
    private static final UUID TENANT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID WORKLOAD = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

    @Test
    void comparesEverySignedFieldAndConsumesExactlyOnce() throws Exception {
        var repository = new AuthorizationNonceRepository(runtimeDataSource());
        var issued = NonceFixtures.strictMerge(TENANT_A, NOW.plusSeconds(60));
        repository.issue(issued);

        assertThatThrownBy(() -> repository.consume(
            NonceFixtures.withExpectedTargetHead(issued, "b".repeat(40)), WORKLOAD, NOW
        )).isInstanceOf(AuthorizationNonce.MismatchOrConsumed.class);

        var successes = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(16)) {
            var jobs = IntStream.range(0, 32)
                .mapToObj(index -> executor.submit(() -> {
                    try {
                        repository.consume(issued, WORKLOAD, NOW);
                        successes.incrementAndGet();
                    } catch (AuthorizationNonce.MismatchOrConsumed expected) {
                        assertThat(expected).hasMessage("authorization_token_mismatch_or_consumed");
                    }
                })).toList();
            for (var job : jobs) {
                job.get();
            }
        }
        assertThat(successes).hasValue(1);
    }

    @Test
    void tenantContextAndPurposeFailClosed() {
        var repository = new AuthorizationNonceRepository(runtimeDataSource());
        var tenantA = NonceFixtures.strictMerge(TENANT_A, NOW.plusSeconds(60));
        var tenantB = NonceFixtures.strictMerge(TENANT_B, NOW.plusSeconds(60));
        migrator().seed(tenantA, tenantB);

        assertThat(repository.findVisibleForTest(TENANT_A)).containsExactly(tenantA.nonceHash());
        assertThat(repository.findVisibleForTest(TENANT_B)).containsExactly(tenantB.nonceHash());
        assertThat(repository.findVisibleWithoutTenantForTest()).isEmpty();
        assertThatThrownBy(() -> repository.issue(NonceFixtures.breakGlass(TENANT_A)))
            .isInstanceOf(AuthorizationNonce.UnsupportedPurpose.class);
    }

    @Test
    void identicalIssueIsIdempotentButSameHashWithChangedBindingConflicts() {
        var repository = new AuthorizationNonceRepository(runtimeDataSource());
        var issued = NonceFixtures.strictMerge(TENANT_A, NOW.plusSeconds(60));

        repository.issue(issued);
        repository.issue(issued);

        assertThatThrownBy(() -> repository.issue(
            NonceFixtures.withSourceHead(issued, "9".repeat(40))))
            .isInstanceOf(AuthorizationNonce.IssueConflict.class);
        assertThat(repository.consume(issued, WORKLOAD, NOW).nonceHash()).isEqualTo(issued.nonceHash());
    }
}
```

Create `NonceFixtures.java` with one fully valid strict-merge record and explicit copy builders for each field. Its emergency-purpose builder exists only to prove pre-SQL rejection and never inserts that record. Extend the first test with one case for each changed field: scope type, scope ID, immutable repository ID, target ref, expected target head, source head, verified result tree, normalized diff digest, required checks digest, CI attestation digest, subject type, subject ID, subject digest, expiry, and nonce hash. Every mismatch must return the same external reason and leave `consumed_at` null.

Create `MtlsCallerInterceptorTest.java`. Build `SSLSession` fixtures whose peer certificates contain one URI SAN. Assert a verified allowlisted SPIFFE URI produces the stored tenant/workload/purpose tuple; absent certificates, unverified chains, multiple URI SANs, inactive policy entries, unknown RPC methods, and caller-purpose confusion return `UNAUTHENTICATED` or `PERMISSION_DENIED` before the handler runs. Protobuf metadata claiming a different tenant or workload must have no effect.

- [ ] **Step 2: Run the tests and verify storage and interceptor are absent**

Run:

```bash
./gradlew :security-services:signing-service:test --tests '*AuthorizationNoncePostgreSqlTest' --tests '*MtlsCallerInterceptorTest'
```

Expected: FAIL at test compilation because migration V002, `AuthorizationNonceRepository`, and `MtlsCallerInterceptor` are absent. The Task 11 database fixture must be healthy.

- [ ] **Step 3: Persist hashed, strict-purpose, exact-resource nonces with forced RLS**

Create `V002__authorization_nonce.sql`:

```sql
SET ROLE accord_signing_owner;

CREATE TABLE authorization_nonce (
    tenant_id uuid NOT NULL,
    nonce_hash char(71) NOT NULL CHECK (nonce_hash ~ '^sha256:[0-9a-f]{64}$'),
    purpose varchar(40) NOT NULL CHECK (purpose = 'STRICT_MERGE'),
    scope_type varchar(16) NOT NULL CHECK (scope_type = 'REPOSITORY'),
    scope_id varchar(255) NOT NULL,
    immutable_repository_id varchar(255) NOT NULL,
    target_ref varchar(1024) NOT NULL CHECK (target_ref LIKE 'refs/%'),
    expected_target_head_sha varchar(64) NOT NULL CHECK (expected_target_head_sha ~ '^[0-9a-f]{40,64}$'),
    source_head_sha varchar(64) NOT NULL CHECK (source_head_sha ~ '^[0-9a-f]{40,64}$'),
    verified_result_tree_sha varchar(64) NOT NULL CHECK (verified_result_tree_sha ~ '^[0-9a-f]{40,64}$'),
    normalized_diff_digest char(71) NOT NULL CHECK (normalized_diff_digest ~ '^sha256:[0-9a-f]{64}$'),
    required_checks_digest char(71) NOT NULL CHECK (required_checks_digest ~ '^sha256:[0-9a-f]{64}$'),
    ci_attestation_digest char(71) NOT NULL CHECK (ci_attestation_digest ~ '^sha256:[0-9a-f]{64}$'),
    subject_type varchar(40) NOT NULL CHECK (
        subject_type IN ('work_item_pr','requirement_metadata','accepted_delivery_candidate','emergency_change')
    ),
    subject_id varchar(255) NOT NULL,
    subject_digest char(71) NOT NULL CHECK (subject_digest ~ '^sha256:[0-9a-f]{64}$'),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    consumed_by_workload_id uuid,
    PRIMARY KEY (tenant_id, nonce_hash),
    CHECK (scope_id = immutable_repository_id),
    CHECK (expires_at > issued_at),
    CHECK ((consumed_at IS NULL) = (consumed_by_workload_id IS NULL))
);

CREATE INDEX authorization_nonce_unconsumed_idx
    ON authorization_nonce (tenant_id, purpose, expires_at)
    WHERE consumed_at IS NULL;

SELECT accord_security.enforce_tenant_table('public.authorization_nonce'::regclass);
REVOKE ALL ON TABLE authorization_nonce FROM PUBLIC, accord_signing_runtime;
GRANT SELECT, INSERT ON TABLE authorization_nonce TO accord_signing_runtime;
GRANT UPDATE (consumed_at, consumed_by_workload_id) ON TABLE authorization_nonce TO accord_signing_runtime;

RESET ROLE;
```

The migration test must inspect catalogs and prove: RLS is enabled and forced; one exact `FOR ALL TO PUBLIC` policy contains both `USING` and `WITH CHECK`; runtime and migrator roles lack `BYPASSRLS`, ownership, create-schema, truncate, delete, and trigger-disable rights; the policy exists before runtime grants; and unset tenant context cannot select, insert, update, or infer row existence.

- [ ] **Step 4: Implement SHA-256 hashing and one-statement comparison-and-consumption**

Create `AuthorizationNonce.java` with a `Record` Java record containing every column above, a `Receipt` record, `MismatchOrConsumed`, `IssueConflict`, and `UnsupportedPurpose`. `hashRawNonce` accepts exactly 43 unpadded base64url characters, decodes exactly 32 bytes, hashes the original ASCII token with SHA-256, and returns lowercase `sha256:` form. Raw nonce values are never persisted or logged.

Create `AuthorizationNonceRepository.java`. `issue` rejects every purpose except `STRICT_MERGE` before opening a connection. It executes `INSERT ... ON CONFLICT (tenant_id, nonce_hash) DO NOTHING`, then selects the exact row and compares every field in constant application flow: an identical retry succeeds, while any changed binding raises `IssueConflict`. This permits a completed signing replay to repair an interrupted nonce issue without another KMS call. Both public methods open a transaction, execute `SELECT set_config('app.tenant_id', ?, true)`, perform work on that same connection, commit, and always restore auto-commit/close through try-with-resources.

The consumption statement is normative:

```sql
UPDATE authorization_nonce
SET consumed_at = :now,
    consumed_by_workload_id = :workload_id
WHERE tenant_id = :tenant_id
  AND nonce_hash = :nonce_hash
  AND purpose = 'STRICT_MERGE'
  AND scope_type = 'REPOSITORY'
  AND scope_id = :scope_id
  AND immutable_repository_id = :immutable_repository_id
  AND target_ref = :target_ref
  AND expected_target_head_sha = :expected_target_head_sha
  AND source_head_sha = :source_head_sha
  AND verified_result_tree_sha = :verified_result_tree_sha
  AND normalized_diff_digest = :normalized_diff_digest
  AND required_checks_digest = :required_checks_digest
  AND ci_attestation_digest = :ci_attestation_digest
  AND subject_type = :subject_type
  AND subject_id = :subject_id
  AND subject_digest = :subject_digest
  AND consumed_at IS NULL
  AND expires_at > :now
RETURNING nonce_hash, consumed_at, consumed_by_workload_id
```

Zero returned rows always becomes `authorization_token_mismatch_or_consumed`; no preliminary lookup may distinguish absent, mismatched, expired, consumed, or cross-tenant tokens. Emergency reservations and raw nonce handling remain exclusively in the separately credentialed broker database and cannot be adapted into this repository.

- [ ] **Step 5: Authenticate the workload from mTLS and bind it to one purpose**

Create `CallerIdentity.java`:

```java
package com.inforvans.accord.signing.transport;

import com.inforvans.accord.signing.trust.TrustModel.Purpose;
import java.util.UUID;

public record CallerIdentity(UUID tenantId, UUID workloadId, Purpose purpose, String spiffeId) {}
```

Create `MtlsCallerInterceptor.java` as an `io.grpc.ServerInterceptor`. Read `Grpc.TRANSPORT_ATTR_SSL_SESSION`, require a verified peer chain from the configured trust manager, require exactly one URI SAN with `spiffe` scheme, and resolve it through a reloadable immutable caller-policy snapshot. Map the full gRPC method name to one exact purpose; reject unknown methods and mismatches. Put only the resolved `CallerIdentity` in a private gRPC `Context.Key`; handlers never read tenant, workload, or purpose authority from request messages or metadata.

The production TLS context requires TLS 1.3, client authentication, hostname/authority verification, a private CA bundle, and certificate-policy rotation without restarting the process. Policy reload is parse-validate-swap: malformed, duplicate, expired, or multi-purpose entries leave the last valid snapshot active and emit an audit-safe alert. No wildcard SPIFFE entries are permitted.

- [ ] **Step 6: Run race, RLS, mTLS, and log-redaction tests**

Run:

```bash
./gradlew :security-services:signing-service:test --tests '*AuthorizationNoncePostgreSqlTest' --tests '*MtlsCallerInterceptorTest'
./gradlew :security-services:signing-service:test --tests '*SigningLogRedactionTest'
```

Expected: PASS. Exactly one of 32 consumers succeeds; every changed field, expiry, retry, unset context, and cross-tenant request has the same fail-closed result; tenant A and B remain mutually invisible; emergency purpose is rejected before SQL; V002 has forced RLS and least privilege; only a verified allowlisted certificate can invoke the exact-purpose RPC; and raw nonces, subject bodies, and certificate material are absent from logs and traces.

Add a crash-boundary test used by Task 16: persist a strict-merge signing result, simulate termination before `issue`, retry the same idempotency key, assert KMS call count remains one, idempotently issue the exact nonce, and only then make the response observable. A conflicting nonce row must make the retry fail closed and emit no envelope.

- [ ] **Step 7: Commit one-time authorization consumption**

```bash
git add database/signing-service/migrations/V002__authorization_nonce.sql security-services/signing-service
git commit -m "feat: consume exact signing authorization once"
```

### Task 14: Append Hash-Chained Audit Events And Anchor Merkle Roots

**Files:**
- Create: `database/control-plane/migrations/V017__tamper_evident_audit.sql`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/AuditModel.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/AuditAppender.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/AuditVerifier.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/Merkle.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/AuditAnchorWorker.java`
- Create: `apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/AuditFixtures.java`
- Create: `apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/AuditPostgreSqlTest.java`
- Create: `apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/AuditIntegrityTest.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/audit/AuditVerifyCommand.java`
- Create: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/audit/AuditExportVerifier.java`
- Create: `cmd/accordctl/src/test/java/com/inforvans/accord/cli/audit/AuditVerifyCommandTest.java`
- Create: `scripts/test-jlink-launcher.ps1`
- Modify: `apps/control-plane/modules/audit/build.gradle`
- Modify: `cmd/accordctl/build.gradle`
- Modify: `cmd/accordctl/src/main/java/com/inforvans/accord/cli/AccordCtl.java`

- [ ] **Step 1: Write failing chain, mutation, Merkle, anchor, and CLI tests**

Create `AuditIntegrityTest.java` with JUnit 5, AssertJ, and jqwik:

```java
package com.inforvans.accord.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

final class AuditIntegrityTest extends AuditPostgreSqlTest {
    @Test
    void appendsContiguousTenantChainAndDetectsMutationOrTailRemoval() {
        var first = appender().append(AuditFixtures.loginSucceeded(tenantA()));
        var second = appender().append(AuditFixtures.roleBindingChanged(tenantA()));

        assertThat(first.sequence()).isEqualTo(1L);
        assertThat(second.sequence()).isEqualTo(2L);
        assertThat(second.previousHash()).isEqualTo(first.eventHash());
        assertThat(verifier().verifyStream(tenantA())).hasSize(2);
        migrator().mutatePayload(second.eventId());
        assertThatThrownBy(() -> verifier().verifyStream(tenantA()))
            .isInstanceOf(AuditModel.InvalidChain.class);
    }

    @Property
    void aChangedLeafChangesTheRoot(@ForAll @Size(min = 2, max = 128) List<@Size(min = 64, max = 64) String> leaves) {
        var normalized = AuditFixtures.validDigests(leaves);
        var changed = AuditFixtures.changeFirstDigest(normalized);
        assertThat(Merkle.root("accord-merkle-sha256-duplicate-last-v1", normalized))
            .isNotEqualTo(Merkle.root("accord-merkle-sha256-duplicate-last-v1", changed));
    }
}
```

Create `AuditFixtures.java` with valid source-free event builders, digest normalization, a single-leaf mutation helper, and signed export fixtures. Add PostgreSQL tests proving: 32 concurrent appends to one tenant produce sequences `1..32`; tenant A and B have independent heads; runtime cannot update/delete/truncate either events or heads; even the table owner receives SQLSTATE `55000` from the append-only trigger when mutation is attempted with tenant context; deleting the tail through a hostile owner fixture is detected because the stored head still names the removed sequence/hash; an unset context observes no row; and a cross-tenant event ID cannot be inferred.

Create `AuditVerifyCommandTest.java` using Picocli `CommandLine`. Generate a two-event fixture, public trust bundle, signed audit-root DSSE envelope, and Object Lock receipt entirely in memory. Assert exit code 0 and a machine-readable verification report for valid input; changed canonical event bytes, event order, previous hash, root, signature, key purpose, trust window, object version, or retention date must return the documented nonzero verification exit code.

- [ ] **Step 2: Run red tests and verify audit storage and verifier are absent**

Run:

```bash
./gradlew :apps:control-plane:modules:audit:test --tests '*AuditIntegrityTest' --tests '*AuditPostgreSqlTest'
./gradlew :cmd:accordctl:test --tests '*AuditVerifyCommandTest'
```

Expected: FAIL at test compilation because migration V017, `AuditAppender`, `Merkle`, `AuditAnchorWorker`, and `AuditVerifyCommand` do not exist.

- [ ] **Step 3: Add append-only stream heads, events, anchor batches, and receipts**

Create `V017__tamper_evident_audit.sql`. The migration creates:

- `audit_stream_head(tenant_id, next_sequence, last_event_hash, version)` with one forced-RLS row per tenant stream;
- partitioned `audit_event(tenant_id, sequence, event_id, occurred_at, actor_type, actor_id, action, object_type, object_id, project_id, immutable_repository_id, result, reason_code, correlation_id, canonical_payload bytea, previous_hash, event_hash, schema_version)` with primary key `(tenant_id, sequence)`, unique `(tenant_id, event_id)`, and composite tenant-aware foreign keys;
- `audit_anchor_batch(tenant_id, anchor_id, first_sequence, last_sequence, leaf_count, merkle_algorithm, merkle_root, state, lease_owner, lease_generation, lease_expires_at, signed_envelope, signed_envelope_digest, trust_record_id, object_key, object_version_id, object_sha256, retain_until, anchored_at, version)`;
- `audit_anchor_outbox(tenant_id, anchor_id, outbox_id, state, available_at, attempts, claimed_by, claim_generation, claim_expires_at, last_error_code)`.

Use monthly event partitions with a default partition that pages operations instead of dropping writes. Install forced tenant RLS on every table before grants. Revoke update/delete/truncate from runtime on `audit_event`; add a `BEFORE UPDATE OR DELETE` trigger that always raises SQLSTATE `55000`. Only the owner migration role may manage partitions, and no application role can disable triggers or alter policies. The anchor range has an exclusion/unique constraint preventing overlapping sequence ranges per tenant.

- [ ] **Step 4: Append through a locked head with one canonical hash contract**

Create `AuditModel.java`:

```java
package com.inforvans.accord.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class AuditModel {
    private AuditModel() {}

    public record Draft(
        UUID tenantId, UUID eventId, Instant occurredAt, String actorType, UUID actorId,
        String action, String objectType, String objectId, UUID projectId,
        String immutableRepositoryId, String result, String reasonCode,
        UUID correlationId, JsonNode sourceFreeDetails
    ) {}

    public record Appended(UUID eventId, long sequence, String previousHash, String eventHash) {}

    public static final class InvalidChain extends RuntimeException {
        public InvalidChain(String reason) { super(reason); }
    }
}
```

Create `AuditAppender.java`. In one tenant transaction it:

1. sets `app.tenant_id` locally and validates that the verified request identity tenant equals the draft tenant;
2. inserts the stream head if absent, then locks it `FOR UPDATE`;
3. assigns `sequence=next_sequence` and `previous_hash=last_event_hash`, using 64 zeroes only for sequence 1;
4. constructs the complete event projection with UTC microsecond time and canonicalizes it using the same RFC 8785 library and golden fixtures as Task 12;
5. calculates `event_hash = sha256(canonical_event_bytes)`;
6. inserts the event and advances the head with quoted `version` equality before commit.

The canonical event includes `tenant_id`, sequence, event ID, occurrence time, actor, action, object, optional project/repository scope, result, stable reason code, correlation ID, source-free details, schema version, and previous hash. Viewer redaction is applied only after integrity verification and never changes stored canonical bytes.

Create `AuditVerifier.java`. It streams ordered rows without loading a tenant history into memory, recomputes canonical bytes and hashes, checks sequence continuity and each previous hash, and finally compares count/last sequence/last hash with the locked stream head. It returns stable codes for malformed canonical payload, gap, predecessor mismatch, content mismatch, and head mismatch.

- [ ] **Step 5: Compute versioned Merkle roots and anchor signed envelopes in Object Lock**

Create `Merkle.java` with algorithm identifier `accord-merkle-sha256-duplicate-last-v1`. Decode each `sha256:` leaf to 32 bytes. For each level, duplicate the last node when the count is odd, concatenate left and right raw bytes, hash with SHA-256, and continue until one root remains. Empty batches are forbidden. Keep golden vectors for one, two, three, 127, and 128 leaves.

Create `AuditAnchorWorker.java`. Claim one outbox row using PostgreSQL `FOR UPDATE SKIP LOCKED` plus `claim_generation`; read and verify the exact unanchored contiguous range; calculate the versioned root; build a source-free anchor subject containing tenant, anchor ID, first/last sequence, leaf count, algorithm, root, previous anchor envelope digest, and creation time; call the Task 12 `AUDIT_ANCHOR` signing client over mTLS; and write the exact DSSE bytes to the object adapter with compliance-mode retention.

The object key is `audit/{tenant_id}/{yyyy}/{mm}/{anchor_id}.dsse.json`. The adapter must return provider version ID, SHA-256 checksum, retention mode, and retain-until time. Only after a read-after-write checksum and retention verification succeeds may the worker atomically persist envelope bytes/digest, trust-record ID, object receipt, `ANCHORED`, and the outbox completion under the same fencing generation. Retry uses the same object key and requires the same bytes; a version/checksum mismatch is terminal and alerts security operations.

- [ ] **Step 6: Add the self-contained offline verifier command**

Create `AuditExportVerifier.java` in the modular Java CLI. It parses the versioned export schema, validates every canonical event and chain link, compares the final stream head, recomputes every anchor range and Merkle root, parses the single-signature DSSE envelope, checks purpose/domain/content digest, resolves the public key from the supplied signed trust bundle at `issued_at`, verifies ES256, and validates object checksum/version/retention evidence. It never contacts the control plane and never accepts a public key embedded only in the export.

Create `AuditVerifyCommand.java`:

```java
package com.inforvans.accord.cli.audit;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "verify", description = "Verify an exported audit chain and its anchors")
public final class AuditVerifyCommand implements Callable<Integer> {
    @Option(names = "--export", required = true)
    private Path export;

    @Option(names = "--trust-bundle", required = true)
    private Path trustBundle;

    private final AuditExportVerifier verifier;

    public AuditVerifyCommand(AuditExportVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Integer call() {
        return verifier.verify(export, trustBundle).valid() ? 0 : 3;
    }
}
```

Register it as `accordctl audit verify`. `:cmd:accordctl:jlink` must include the command, JCS implementation, Jackson, and JCA providers in the runtime image; the packaged launcher must work without a host JDK. Exit codes are 0 valid, 2 invalid invocation/input, 3 integrity or signature failure, and 4 unsupported schema/algorithm.

Create `scripts/test-jlink-launcher.ps1` with typed `Launcher` and `Arguments` parameters. Resolve the launcher beneath the workspace, reject a path outside it, select the `.bat` suffix on Windows, invoke it with `System.Diagnostics.ProcessStartInfo.ArgumentList` so paths are not reparsed by a shell, capture stdout/stderr, and fail unless exit code is 0 and the JSON report contains `valid: true`, the expected last sequence, and the expected anchor digest.

- [ ] **Step 7: Run chain, tamper, anchor, fault, and packaged-CLI gates**

Run:

```bash
./gradlew :apps:control-plane:modules:audit:test
./gradlew :tests:fault-injection:test --tests '*AuditAnchorWorkerFaultTest'
./gradlew :cmd:accordctl:test :cmd:accordctl:jlink
pwsh -NoProfile -File scripts/test-jlink-launcher.ps1 -Launcher cmd/accordctl/build/image/bin/accordctl -Arguments 'audit verify --export contracts/golden-fixtures/audit/valid-export.json --trust-bundle contracts/golden-fixtures/audit/trust-bundle.json'
```

Expected: PASS. Sequences are contiguous under concurrency; tenant streams are isolated; all mutation paths are denied or detected; changed leaves change the root; worker crash/retry yields one completed fenced batch with a verified locked-object receipt; the packaged CLI accepts the golden export and rejects every single-field mutation with the documented exit code.

- [ ] **Step 8: Commit tamper-evident audit anchoring**

```bash
git add database/control-plane/migrations/V017__tamper_evident_audit.sql apps/control-plane/modules/audit cmd/accordctl scripts/test-jlink-launcher.ps1
git commit -m "feat: anchor tamper-evident audit chains"
```

### Task 15: Enforce Retention, Legal Hold, Waiting Periods, And Deletion Proof

**Files:**
- Create: `database/control-plane/migrations/V018__retention_and_deletion.sql`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/RetentionModel.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/RetentionPolicyService.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/DeletionModel.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/DeletionService.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/DeletionWorker.java`
- Create: `apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/DeletionTestFixtures.java`
- Create: `apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/RetentionPolicyServiceTest.java`
- Create: `apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/DeletionServiceTest.java`
- Create: `apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/DeletionWorkerFaultTest.java`
- Modify: `apps/control-plane/modules/audit/src/test/java/com/inforvans/accord/audit/AuditPostgreSqlTest.java`

- [ ] **Step 1: Write failing retention, hold, waiting-period, retry, and proof tests**

Create `DeletionServiceTest.java` with a deterministic clock and five explicit erasure test adapters named `online`, `objects`, `cache`, `search`, and `keys`.

```java
package com.inforvans.accord.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class DeletionServiceTest extends AuditPostgreSqlTest {
    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

    @Test
    void legalHoldBlocksBeforeAnyErasureAndPersistsBlockedState() {
        var request = fixtures().approvedTenantDeletion(NOW.minus(Duration.ofDays(31)));
        fixtures().placeTenantHold(request.tenantId(), "litigation-42");

        assertThatThrownBy(() -> deletionService().startExecution(request.tenantId(), request.id(), NOW))
            .isInstanceOf(DeletionModel.LegalHoldActive.class);
        assertThat(fixtures().reload(request.id()).state()).isEqualTo(DeletionModel.State.BLOCKED_LEGAL_HOLD);
        assertThat(erasureAdapters()).allSatisfy(adapter -> assertThat(adapter.calls()).isZero());
    }

    @Test
    void crashRetryProducesOneReceiptPerStoreAndOneSignedProof() {
        var request = fixtures().approvedTenantDeletion(NOW.minus(Duration.ofDays(31)));
        var worker = deletionWorkerWithCrashAfter("objects");

        assertThatThrownBy(() -> worker.execute(request.tenantId(), request.id(), "worker-a"))
            .isInstanceOf(SimulatedCrash.class);
        deletionWorker().execute(request.tenantId(), request.id(), "worker-b");

        var completed = fixtures().reload(request.id());
        assertThat(completed.state()).isEqualTo(DeletionModel.State.COMPLETED);
        assertThat(fixtures().receipts(request.id())).extracting(DeletionModel.ErasureReceipt::storeName)
            .containsExactlyInAnyOrder("online", "objects", "cache", "search", "keys");
        assertThat(erasureAdapters()).allSatisfy(adapter -> assertThat(adapter.calls()).isEqualTo(1));
        assertThat(fixtures().proofs(request.id())).hasSize(1);
    }
}
```

Create `DeletionTestFixtures.java` with valid approved tenant/project request builders, five counting idempotent adapters, a signer/anchor test double, and a package-private `SimulatedCrash` used only by the fault worker. Add tests for: cancellation during the waiting period; rejection before `execute_after`; two approvals from distinct eligible natural people and required sides; approval invalidation when policy/version/manifest changes; project deletion that preserves sibling projects and tenant root key material; tenant deletion that destroys the tenant-scoped key hierarchy only after other stores complete; a new legal hold arriving between every pair of adapter calls; active Object Lock retention; provider receipt digest mismatch; customer-managed stores reported as explicit exclusions; a request UUID from another tenant; and retry after every durable transition.

Create `RetentionPolicyServiceTest.java` with category cases for audit evidence, signing evidence, requirements, attachments, operational telemetry, and deletion proofs. Assert a policy version is immutable after activation, only one active head exists per tenant, lowering retention never shortens already committed Object Lock retention, referenced evidence remains until all references and holds are gone, and batches are bounded and restartable.

- [ ] **Step 2: Run the tests and verify lifecycle storage is absent**

Run:

```bash
./gradlew :apps:control-plane:modules:audit:test --tests '*RetentionPolicyServiceTest' --tests '*DeletionServiceTest' --tests '*DeletionWorkerFaultTest'
```

Expected: FAIL at test compilation because migration V018 and the retention/deletion lifecycle types do not exist.

- [ ] **Step 3: Add versioned policies, holds, deletion requests, approvals, receipts, and proofs**

Create `V018__retention_and_deletion.sql` with these tenant-scoped tables and composite keys:

- `retention_policy_version(tenant_id, policy_id, version, state, effective_from, created_by, created_at, policy_digest)`;
- `retention_policy_category(tenant_id, policy_id, version, category, retain_for_seconds, object_lock_mode, deletion_mode)`;
- `retention_policy_head(tenant_id, active_policy_id, active_version, version)`;
- `legal_hold(tenant_id, hold_id, project_id, scope_type, scope_id, reason_digest, placed_by, placed_at, released_by, released_at, version)`;
- `deletion_request(tenant_id, deletion_request_id, target_type, target_id, policy_id, policy_version, manifest_digest, state, requested_by, requested_at, cancel_until, execute_after, blocked_reason, lease_owner, lease_generation, lease_expires_at, completed_at, version)`;
- `deletion_approval(tenant_id, deletion_request_id, approval_id, side, natural_person_id, decision, request_version, manifest_digest, created_at)`;
- `deletion_erasure_receipt(tenant_id, deletion_request_id, store_name, provider_operation_id, erased_count, manifest_digest, receipt_digest, completed_at)`;
- `deletion_proof(tenant_id, deletion_request_id, proof_id, proof_payload, proof_digest, signed_envelope, signed_envelope_digest, trust_record_id, audit_anchor_id, created_at)`.

Constrain target type to `TENANT` or `PROJECT`, state to the closed lifecycle, store name to the five explicit adapters, approval side to `BUSINESS` or `DEVELOPMENT`, and receipt uniqueness to `(tenant_id, deletion_request_id, store_name)`. Proof foreign keys include tenant, request, every receipt digest, and audit anchor. Install forced RLS before least-privilege grants. Runtime has no table ownership, policy management, truncate, trigger-disable, or migration capability.

The state machine is:

```text
WAITING -> CANCELLED
WAITING -> PENDING_APPROVAL
PENDING_APPROVAL -> APPROVED
APPROVED -> BLOCKED_LEGAL_HOLD
APPROVED -> EXECUTING
BLOCKED_LEGAL_HOLD -> APPROVED
EXECUTING -> BLOCKED_LEGAL_HOLD
EXECUTING -> COMPLETED
```

No other edge is valid. A request records an immutable policy version and source-free deletion manifest digest. Any target, policy, binding, or manifest change requires a new request and new approvals.

- [ ] **Step 4: Define explicit deletion ports and transaction-safe state transitions**

Create `DeletionModel.java`:

```java
package com.inforvans.accord.audit;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class DeletionModel {
    private DeletionModel() {}

    public enum State { WAITING, CANCELLED, PENDING_APPROVAL, APPROVED, BLOCKED_LEGAL_HOLD, EXECUTING, COMPLETED }
    public sealed interface Target permits TenantTarget, ProjectTarget {}
    public record TenantTarget(UUID tenantId) implements Target {}
    public record ProjectTarget(UUID tenantId, UUID projectId) implements Target {}
    public record Scope(UUID tenantId, UUID requestId, Target target, String manifestDigest) {}
    public record ErasureReceipt(
        String storeName, String providerOperationId, long erasedCount,
        String manifestDigest, String receiptDigest, Instant completedAt
    ) {}
    public record Proof(
        UUID proofId, Set<String> erasedStores, List<String> exclusions,
        String payloadDigest, String envelopeDigest, UUID auditAnchorId
    ) {}

    public interface Eraser {
        String storeName();
        ErasureReceipt erase(Scope scope, String idempotencyKey);
    }

    public static final class LegalHoldActive extends RuntimeException {
        public LegalHoldActive() { super("legal_hold_active"); }
    }
}
```

Create `DeletionService.java` for request, cancel, approval, and execution authorization. Every command requires the verified tenant, quoted expected version, idempotency key, and for approve/execute a consumed single-action FreshAuth proof bound to the exact request ID, action, actor, policy version, manifest digest, and expected version. Approval stores the natural-person ID; two approvals must be current, eligible, from distinct people, and cover both required sides. Starting execution atomically rechecks waiting period, approvals, active legal holds, target existence, policy version, manifest digest, and state before changing to `EXECUTING` and creating a fenced outbox claim.

- [ ] **Step 5: Execute bounded erasure with a hold check before every external effect**

Create `DeletionWorker.java`. It claims by `(tenant_id, deletion_request_id)` with `lease_generation`. Before each adapter call and again before final proof, open a new tenant transaction and recheck state, fencing generation, manifest, policy version, and legal holds. A newly observed hold commits `BLOCKED_LEGAL_HOLD` before returning and prevents the next store call.

For each store, first read an existing receipt. If absent, call the adapter with idempotency key `{tenant_id}:{deletion_request_id}:{store_name}`, validate store name, provider operation ID, manifest digest, erased count, and receipt digest, then insert it with uniqueness. A retry with a different receipt digest is terminal. Object deletion must respect quarantine, legal hold, immutable retention, versioning, and replication receipts; a locked version keeps the request blocked and records the provider reason without claiming deletion.

Run order is `online`, `objects`, `cache`, `search`, then `keys`. Project deletion never destroys tenant root keys. Tenant key destruction occurs only after the first four receipts are durable. The final source-free proof lists request/target identifiers, policy version, manifest digest, approvals, five receipt digests, explicit customer-managed exclusions, completion time, and no content bodies. Sign it with the deletion-proof purpose key, append a Task 14 audit event, anchor that event, persist proof and `COMPLETED` atomically, and expose only the minimal proof fields.

- [ ] **Step 6: Apply category retention in bounded, resumable batches**

Create `RetentionPolicyService.java`. Activation uses quoted head version equality and makes policy rows immutable. The worker claims PostgreSQL batch leases with fencing; selects only records whose category deadline has passed; excludes active legal holds and evidence still referenced by approvals, signatures, audit anchors, or deletion proofs; calls the appropriate storage adapter with a stable idempotency key; and persists a receipt/cursor before advancing. It may extend but never shorten an existing immutable-storage retain-until value. Policy changes affect future eligibility calculations but do not rewrite historical policy versions or signed evidence.

- [ ] **Step 7: Run lifecycle, RLS, fault, Object Lock, and proof gates**

Run:

```bash
./gradlew :apps:control-plane:modules:audit:test --tests '*RetentionPolicyServiceTest' --tests '*DeletionServiceTest'
./gradlew :tests:fault-injection:test --tests '*DeletionWorkerFaultTest'
./gradlew :tests:security-negative:test --tests '*DeletionIsolationSecurityTest'
```

Expected: PASS. Holds commit a blocked state before callers observe failure; waiting requests cancel and premature execution fails; approvals and FreshAuth are exact and single use; a hold introduced at any boundary stops the next effect; crash/retry performs each physical erasure once and produces one proof; project deletion preserves siblings and tenant root keys; tenant and project IDs cannot cross isolation; immutable provider state is honored; and the completed request contains five digest-constrained receipts, one signed proof, and one anchored audit reference without customer content.

- [ ] **Step 8: Commit retention and deletion evidence**

```bash
git add database/control-plane/migrations/V018__retention_and_deletion.sql apps/control-plane/modules/audit
git commit -m "feat: enforce retention holds and deletion proof"
```

### Task 16: Package The Signing Service And Run The Negative Security Matrix

**Files:**
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/SigningServiceApplication.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/config/SigningServiceProperties.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/config/SigningServiceConfiguration.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/transport/SigningGrpcEndpoint.java`
- Create: `security-services/signing-service/src/main/java/com/inforvans/accord/signing/transport/GrpcServerLifecycle.java`
- Create: `security-services/signing-service/src/main/resources/application.yaml`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/transport/SigningGrpcEndpointTest.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/SigningServiceApplicationTest.java`
- Create: `security-services/signing-service/src/test/java/com/inforvans/accord/signing/SigningServiceConfigurationTest.java`
- Create: `security-services/signing-service/Dockerfile`
- Create: `infra/helm/accord/charts/signing-service/Chart.yaml`
- Create: `infra/helm/accord/charts/signing-service/values.yaml`
- Create: `infra/helm/accord/charts/signing-service/templates/deployment.yaml`
- Create: `infra/helm/accord/charts/signing-service/templates/service.yaml`
- Create: `infra/helm/accord/charts/signing-service/templates/serviceaccount.yaml`
- Create: `infra/helm/accord/charts/signing-service/templates/networkpolicy.yaml`
- Create: `infra/helm/accord/charts/signing-service/templates/pdb.yaml`
- Create: `infra/helm/accord/charts/signing-service/templates/servicemonitor.yaml`
- Create: `tests/security/identity-security-matrix.ps1`
- Create: `tests/security/signing-service-chart.Tests.ps1`
- Create: `docs/architecture/identity-tenancy-audit.md`
- Modify: `security-services/signing-service/build.gradle`
- Modify: `infra/helm/accord/Chart.yaml`
- Modify: `infra/helm/accord/values.yaml`

- [ ] **Step 1: Write the failing black-box gRPC and deployment security tests**

Create `SigningGrpcEndpointTest.java` with an in-process gRPC server and real generated Java messages. Test each public method: `SignDsse`, `VerifyDsse`, `ConsumeAuthorizationToken`, and `IssueBreakGlassAuthorization`. Assert transport messages are converted to closed domain records; tenant/workload authority comes only from `CallerIdentity`; every protobuf presence bit and closed enum is checked; domain exceptions map to stable gRPC status plus safe reason metadata; and no domain service receives protobuf classes.

Add the following hostile cases: request tenant differs from authenticated tenant; repository scope lacks immutable ID; an unknown enum numeric value; generic signing with emergency purpose; emergency binding on generic RPC; strict binding on emergency RPC; changed evidence snapshot; absent or substituted `nonce_hash`; caller purpose mismatch; expired or consumed strict nonce; and any 64 concurrent idempotent requests returning different response bytes.

Create `SigningServiceConfigurationTest.java`. Start the application with each required secret/property removed in turn and assert startup fails before opening a listening socket. Assert production refuses plaintext transport, TLS below 1.3, disabled client authentication, shared control-plane database credentials, wildcard caller policy, empty KMS allowlist, writable root filesystem requirement, and development profiles.

Create `signing-service-chart.Tests.ps1` to render the chart and inspect YAML structurally. It must prove a dedicated ServiceAccount; no automounted token unless workload identity requires it; non-root UID/GID; read-only root filesystem; dropped capabilities; seccomp `RuntimeDefault`; resource requests/limits; topology spread; anti-affinity; PDB; startup/readiness/liveness probes; secret references rather than inline secrets; egress only to signing PostgreSQL, KMS endpoint, DNS, telemetry, and the emergency evidence service; ingress only from allowlisted workloads on the mTLS port; and no control-plane database secret or broad cloud role.

- [ ] **Step 2: Run red gates and verify the executable service is missing**

Run:

```bash
./gradlew :security-services:signing-service:test --tests '*SigningGrpcEndpointTest' --tests '*SigningServiceConfigurationTest'
pwsh -NoProfile -File tests/security/signing-service-chart.Tests.ps1
```

Expected: FAIL because the application entry point, gRPC lifecycle, configuration validation, image, and chart are absent. Already implemented domain tests must remain green.

- [ ] **Step 3: Adapt generated transport contracts without widening authority**

Create `SigningGrpcEndpoint.java` by extending the generated `SigningServiceGrpc.SigningServiceImplBase`. Each method:

1. reads the verified caller from `MtlsCallerInterceptor` context and rejects absence;
2. compares request tenant to caller tenant without treating the request as authority;
3. validates protobuf presence, byte lengths, closed enum mappings, purpose-to-method mapping, and repository identity;
4. maps into Java domain records from Tasks 12-13;
5. invokes exactly one application service;
6. maps a closed result into protobuf and finishes the observer once.

For `SignDsse` with `STRICT_MERGE`, obtain the persisted/replayed signing result first, idempotently issue and exact-compare the Task 13 nonce row, and only then call `onNext`; a failure or crash before nonce success exposes no envelope. Retry uses the completed signing row, performs no KMS call, repairs an absent nonce, and returns the original bytes. `ConsumeAuthorizationToken` first verifies the signed strict-merge envelope with the expected purpose/domain, then compares every signed authorization claim to the consume request, then calls the one-statement nonce repository. `IssueBreakGlassAuthorization` calls only `BreakGlassIssuanceService`; it has no reference to the strict nonce repository. Status mapping is closed: malformed input `INVALID_ARGUMENT`, missing peer `UNAUTHENTICATED`, purpose/scope denial `PERMISSION_DENIED`, mismatch/consumed `FAILED_PRECONDITION`, idempotency conflict `ALREADY_EXISTS`, active owner `ABORTED` with bounded retry metadata, dependency timeout `UNAVAILABLE`, and unclassified internal error `INTERNAL` with a correlation ID only.

- [ ] **Step 4: Start a Spring Boot process with an explicitly configured Netty gRPC server**

Create `SigningServiceApplication.java`:

```java
package com.inforvans.accord.signing;

import com.inforvans.accord.signing.config.SigningServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SigningServiceProperties.class)
public class SigningServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SigningServiceApplication.class, args);
    }
}
```

Create immutable validated `SigningServiceProperties` records for server address/port, certificate/key/private-CA paths, caller-policy path/reload interval, database URL/user and secret-file path, KMS region/allowed key aliases, evidence endpoint/authority/certificate set, idempotency lease, graceful shutdown, and probe policy. Secrets are loaded from mounted files or workload identity, never environment values that could appear in diagnostics. Validate all invariants at binding time.

Add the executable-only dependencies to `security-services/signing-service/build.gradle`; versions come from the locked gRPC/Spring dependency platforms established by Foundation Task 1:

```groovy
dependencies {
    implementation 'io.grpc:grpc-netty-shaded'
    implementation 'io.grpc:grpc-services'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'
}
```

Create `GrpcServerLifecycle.java` as a Spring `SmartLifecycle` around `NettyServerBuilder`. Configure TLS 1.3, `ClientAuth.REQUIRE`, private CA trust, maximum inbound message size, keepalive limits, connection age, graceful shutdown deadline, `MtlsCallerInterceptor`, trace/metrics interceptors, endpoint, and health service. Readiness is true only after Flyway validation, database privilege self-check, caller-policy load, KMS public-key/alias allowlist validation, and evidence-channel TLS validation. Liveness never depends directly on a remote service.

Create separate beans for the signing database pool, jOOQ context factory, KMS client, evidence gRPC channel, signing service, nonce repository, and endpoint. The module must not scan or import control-plane domain packages. Pool initialization uses the signing runtime role and verifies `current_user`, absence of `BYPASSRLS`, and inability to read without tenant context.

- [ ] **Step 5: Build a reproducible non-root image and isolated Helm workload**

Modify `build.gradle` to produce a layered Spring Boot jar, CycloneDX SBOM, test report, and reproducible archive. Create a multi-stage `Dockerfile` pinned by digest: the build stage runs the Gradle wrapper with dependency verification; the runtime stage uses a Java 21 distroless/non-root image, copies only the verified jar and licenses, exposes the gRPC and management ports, sets no secret values, and uses the JVM container limits and graceful shutdown flags. CI signs this image and attaches provenance independently from every other deployable.

Create the chart resources listed above. Use a dedicated image repository/tag/digest, ServiceAccount and cloud workload identity annotation, signing-only secret names, ClusterIP service, three replicas by default, max-unavailable-one rolling updates, PDB min-available two, zone spread, and explicit ingress/egress network policy. Mount TLS and caller-policy secrets read-only. Database, KMS, and evidence credentials are distinct from the control plane. A chart render must fail when a required secret reference, immutable image digest, private CA, KMS allowlist, or network-policy selector is missing.

- [ ] **Step 6: Run the complete negative security matrix**

Create `identity-security-matrix.ps1` as a fail-fast orchestrator that invokes task-local Gradle tests, Testcontainers integration tests, the rendered-chart tests, dependency verification, SBOM/license/vulnerability policy, and black-box gRPC tests. The script emits one JSON result per control and exits nonzero on skips, missing tools, zero selected tests, stale generated contracts, or an unexpected test count.

Run:

```bash
./gradlew :security-services:signing-service:clean :security-services:signing-service:check :security-services:signing-service:bootJar
./gradlew :tests:security-negative:test :tests:fault-injection:test
pwsh -NoProfile -File tests/security/signing-service-chart.Tests.ps1
pwsh -NoProfile -File tests/security/identity-security-matrix.ps1
```

Expected: `identity-security-matrix: PASS`. The matrix proves zero successful cross-tenant reads/writes; no administrator implicit approval; no same-person strict confirmation; no vendor scope escape; no stale repository binding or chained delegation; no FreshAuth replay; no machine human action; no purpose/domain/repository confusion; no expired or consumed strict nonce; no generic/emergency contract confusion; no evidence fail-open; no emergency entry into the strict nonce store; no divergent duplicate envelope; no audit mutation; no deletion through legal hold; no plaintext or unauthenticated service path; and no shared identity, credential, database role, image, or unrestricted network route.

- [ ] **Step 7: Record the implemented trust and operations model**

Create `docs/architecture/identity-tenancy-audit.md` with:

- a process/credential/data-flow diagram showing the control plane, signing service, KMS, signing PostgreSQL, evidence service, object storage, and CLI verifier;
- purpose/domain/key/RPC/caller mappings and exact data allowed across each boundary;
- normal signing, strict nonce issue/consume, emergency issuance, audit anchoring, certificate rotation, caller-policy rotation, key compromise, database failover, and service rollback runbooks;
- SLOs, saturation indicators, bounded retries, deadlines, alerts, dashboards, backup/PITR restore tests, RPO/RTO, and quarterly negative-matrix evidence;
- log/metric/trace redaction rules and the explicit statement that the platform stores structured source-free facts, never source trees, archives, full diffs, raw nonces, private keys, or general Git credentials.

- [ ] **Step 8: Commit the executable service and security gate**

```bash
git add security-services/signing-service infra/helm/accord tests/security docs/architecture/identity-tenancy-audit.md
git commit -m "feat: package isolated signing service"
```

### Task 17: Publish The Production Identity, Project Setup, And Governance HTTP API

**Files:**
- Create: `database/control-plane/migrations/V019__project_setup_and_browser_sessions.sql`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/application/ProjectSetupApplicationService.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/application/IdentityLifecycleApplicationService.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/application/BrowserSessionService.java`
- Create: `apps/control-plane/modules/identity/src/main/java/com/inforvans/accord/identity/application/NotificationPreferenceService.java`
- Create: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/AuditGovernanceApplicationService.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/identityapi/IdentityApiDtos.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/identityapi/IdentityPublicOperation.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/identityapi/SessionController.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/identityapi/ProjectSetupController.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/identityapi/AuthorizationAdminController.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/identityapi/FreshAuthenticationController.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/identityapi/AuditGovernanceController.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/identityapi/RetentionDeletionController.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/security/IdentityHttpSecurity.java`
- Create: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/security/BrowserMutationGuard.java`
- Create: `tests/api/src/test/java/com/inforvans/accord/api/IdentityPublicApiContractTest.java`
- Create: `tests/integration/src/test/java/com/inforvans/accord/integration/ProjectSetupMigrationIT.java`
- Create: `tests/integration/src/test/java/com/inforvans/accord/integration/IdentityPublicHttpIT.java`
- Create: `tests/security-negative/src/test/java/com/inforvans/accord/security/IdentityPublicApiSecurityTest.java`
- Create: `packages/api-client/src/identity-public.contract.test.ts`
- Modify: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/security/VerifiedRequestIdentity.java`
- Modify: `apps/control-plane/api/src/main/java/com/inforvans/accord/controlplane/http/ContractValidationController.java`
- Modify: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/FreshAuthService.java`
- Modify: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/AuthorizationModel.java`
- Modify: `apps/control-plane/modules/authorization/src/main/java/com/inforvans/accord/authorization/JooqAuthorizationService.java`
- Modify: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/DeletionService.java`
- Modify: `apps/control-plane/modules/audit/src/main/java/com/inforvans/accord/audit/AuditAppender.java`
- Modify: `contracts/openapi/accord-control-api.yaml`
- Modify: `docs/architecture/identity-tenancy-audit.md`

- [ ] **Step 1: Write failing cumulative contract, migration, HTTP, browser-security, and lifecycle tests**

Create `IdentityPublicApiContractTest.java` as a packaged `@SpringBootTest`. Parse the cumulative OpenAPI 3.1 file and inspect the real `RequestMappingHandlerMapping`. Compare both against `IdentityPublicOperation`: exactly 72 operations owned by `identity-public`; no duplicate operation ID, HTTP method/path, or controller method; no unregistered public Identity handler; and no missing handler. Every operation's `x-controller-method` must equal the actual `<ControllerSimpleName>#<methodName>`.

The immutable owner set is:

```java
private static final java.util.Set<String> IDENTITY_PUBLIC_OPERATIONS = java.util.Set.of(
    "getCurrentSession", "listCurrentSessionProjects", "createTenantSwitchIntent",
    "consumeTenantSwitchIntent", "revokeCurrentSession", "createProject", "getProject",
    "updateProjectNotificationPolicy", "updateMyProjectNotificationPreferences",
    "createProjectSetup", "getProjectSetup", "resumeProjectSetup", "updateProjectSetupStep",
    "validateProjectSetup", "submitProjectSetup", "confirmProjectSetupDevelopment",
    "confirmProjectSetupBusiness", "activateProjectSetup", "getProjectRepositoryBinding",
    "createRepositoryBindingChangeRequest", "getRepositoryBindingChangeRequest",
    "confirmRepositoryBindingChange", "listProjectRoleCatalog", "listProjectRoleBindings",
    "createProjectRoleBinding", "revokeProjectRoleBinding", "getProjectSidePrincipals",
    "replaceProjectSidePrincipal", "updateProjectSeparationPolicy", "listProjectDelegations",
    "createProjectDelegation", "revokeProjectDelegation", "listExternalSupplierAssignments",
    "createExternalSupplierAssignment", "supersedeExternalSupplierAssignment",
    "revokeExternalSupplierAssignment", "createExternalSupplierReassignment",
    "startFreshAuthentication", "getFreshAuthentication", "completeFreshAuthentication",
    "queryTenantAuditEvents", "getTenantAuditEvent", "queryProjectAuditEvents",
    "getProjectAuditEvent", "createTenantAuditExport", "getTenantAuditExport",
    "createTenantAuditExportDownloadCapability", "createProjectAuditExport",
    "getProjectAuditExport", "createProjectAuditExportDownloadCapability",
    "listRetentionPolicyVersions", "getActiveRetentionPolicy", "createRetentionPolicyVersion",
    "activateRetentionPolicyVersion", "listTenantLegalHolds", "placeTenantLegalHold",
    "releaseTenantLegalHold", "listProjectLegalHolds", "placeProjectLegalHold",
    "releaseProjectLegalHold", "createTenantDeletionRequest", "getTenantDeletionRequest",
    "cancelTenantDeletionRequest", "approveTenantDeletionRequest", "executeTenantDeletionRequest",
    "getTenantDeletionProof", "createProjectDeletionRequest", "getProjectDeletionRequest",
    "cancelProjectDeletionRequest", "approveProjectDeletionRequest", "executeProjectDeletionRequest",
    "getProjectDeletionProof"
);
```

The contract test also proves every mutation references the canonical required `IdempotencyKey`, `ExpectedVersion`, `BrowserCsrfToken`, `ETag`, and `ProblemResponse` components. Exactly these 30 operations additionally require UUID `FreshAuthProof` and `x-fresh-auth: single_action`: `consumeTenantSwitchIntent`, `submitProjectSetup`, both setup confirmations, setup activation, create/confirm repository binding change, revoke role binding, replace side principal, update separation policy, revoke supplier assignment, create supplier reassignment, both tenant audit-export mutations, both project audit-export mutations, create/activate retention policy, place/release tenant hold, place/release project hold, and create/cancel/approve/execute for tenant and project deletion. No other operation may accept that proof.

Create `ProjectSetupMigrationIT.java` against PostgreSQL 17.5. Bootstrap database roles before Flyway, migrate the central directory to exact target 019, and assert every V019 table, composite key, composite foreign key, check constraint, append-only trigger, forced RLS policy, and least-privilege grant. Verify V019 follows V018 and is the only installed version before V020.

Create `IdentityPublicHttpIT.java` and `IdentityPublicApiSecurityTest.java` as black-box `MockMvc` plus PostgreSQL tests. They use only JSON, cookies/tokens, documented headers, and public routes. Required hostile cases include cross-tenant IDs, missing tenant context, spoofed actor headers, mixed cookie/bearer credentials, unsafe cookie request without Origin/Fetch Metadata/CSRF, bearer request with forged tenant header, stale ETag, body/header version mismatch, reused idempotency key with changed digest, reused FreshAuth proof, invisible-resource enumeration, cursor replay under another identity/filter/scope, session rotation/revocation replay, switch-intent replay, strict same-person confirmation, machine confirmation, setup evidence becoming stale, supplier escape, hold bypass, and deletion execution before waiting/approval gates.

- [ ] **Step 2: Run red tests and verify V019 and public ownership are absent**

Run:

```bash
./gradlew :tests:api:test --tests '*IdentityPublicApiContractTest'
./gradlew :tests:integration:test --tests '*ProjectSetupMigrationIT' --tests '*IdentityPublicHttpIT'
./gradlew :tests:security-negative:test --tests '*IdentityPublicApiSecurityTest'
```

Expected: all test source sets compile; the API test fails because the owner set is absent, migration test fails because target 019 is absent, and HTTP/security tests receive 404. Missing fixtures, zero selected tests, or source-set failures are orchestration errors and must be fixed before implementation.

- [ ] **Step 3: Add normalized resumable setup, browser session, export, and capability storage**

Create `V019__project_setup_and_browser_sessions.sql`. It creates these normalized tenant tables without duplicating Task 14/15 policy, hold, approval, or deletion tables:

- `browser_session`: tenant/session composite key, account, natural person, issuer/subject digest, identity version, session epoch, token hash, CSRF ciphertext/digest/key version/epoch, issued/last-seen/idle/absolute expiry, revoked time/reason, version;
- `tenant_switch_intent`: source tenant/session, authenticated encrypted target reference, reference/subject digests, expiry, consumed time, version;
- an additive `ALTER TABLE reauth_session` contract from Task 8: exact allowed action, object type/ID, project, expected object version, authorization digest, originating browser-session ID/epoch, and a constraint requiring all fields for single-use rows and none for reusable rows;
- `project_support_unit_selection_version` and `project_support_unit_selection_item`: immutable versioned support-unit choices;
- `project_notification_policy_version`: four closed channels, urgent immediate policy, digest schedule/timezone, escalation delay/targets, immutable version state;
- `user_project_notification_preference`: per-account/project channel and urgency overrides bounded by project policy;
- `project_setup`, `project_setup_step`, `project_setup_role_selection`, `project_setup_validation_gate`, and append-only `project_setup_confirmation_link`;
- `repository_binding_change_request`: old/new immutable identity, reconciliation evidence, state, exact confirmation link, version;
- `external_assignment_reassignment`: old assignment, impact digest, replacement assignments, state, version;
- `audit_export_job`, append-only `audit_export_download_capability`, and append-only `audit_export_download_consumption`.

All relationships start with `tenant_id`; project children use `(tenant_id, project_id, ...)`. Install forced RLS before runtime grants. Runtime cannot own tables, bypass RLS, truncate, disable triggers, or mutate append-only receipts/capabilities. Use partial unique indexes for one active setup, one active notification policy, one current side principal per side, and one current Business Acceptance Owner selection.

Setup state is closed: `DRAFT -> VALIDATED -> SUBMITTED -> DEVELOPMENT_CONFIRMED -> BUSINESS_CONFIRMED -> ACTIVE`. Resume does not change state. Submit freezes repository, support-unit, delivery mode/separation, role preset/selections, assessment policy, Agent Pack/CI evidence, notification, retention, and guarantee-label inputs. Any frozen-input change creates a new setup version and invalidates validation/confirmation links.

A switch intent stores authenticated ciphertext plus digests of the server-minted opaque tenant reference and subject binding, never a client-selected tenant ID or plaintext target. Consumption decrypts and verifies it, rechecks target membership, revokes the source session, and inserts a new target-tenant session/cookie in one broker-controlled transition. No row ever changes its `tenant_id`.

- [ ] **Step 4: Publish the exact cumulative OpenAPI operation matrix and closed DTOs**

Extend `accord-control-api.yaml`; retain every Foundation operation. The profiles are:

- `Q`: authenticated query, optional opaque cursor, private no-store/no-cache, quoted response ETag, stable Problem Details;
- `S`: session bootstrap query, never 304, quoted session ETag;
- `M`: JSON body plus required idempotency key, quoted If-Match, matching numeric `expected_version`, conditional browser CSRF, and response ETag;
- `MF`: all `M` requirements plus one required exact-action FreshAuth proof consumed in the command transaction.

Use the following normative method/path/profile mapping:

| Method and path | Operation ID | Profile |
| --- | --- | --- |
| `GET /v1/session` | `getCurrentSession` | S |
| `GET /v1/session/projects` | `listCurrentSessionProjects` | Q |
| `POST /v1/session/tenant-switch-intents` | `createTenantSwitchIntent` | M |
| `POST /v1/session/tenant-switch-intents/{intentId}:consume` | `consumeTenantSwitchIntent` | MF, browser only |
| `POST /v1/session:revoke` | `revokeCurrentSession` | M |
| `POST /v1/projects` | `createProject` | M |
| `GET /v1/projects/{projectId}` | `getProject` | Q |
| `PUT /v1/projects/{projectId}/notification-policy` | `updateProjectNotificationPolicy` | M |
| `PUT /v1/projects/{projectId}/notification-preferences/me` | `updateMyProjectNotificationPreferences` | M |
| `POST /v1/projects/{projectId}/setup` | `createProjectSetup` | M |
| `GET /v1/projects/{projectId}/setup` | `getProjectSetup` | Q |
| `POST /v1/projects/{projectId}/setup:resume` | `resumeProjectSetup` | M |
| `PUT /v1/projects/{projectId}/setup/steps/{stepKey}` | `updateProjectSetupStep` | M |
| `POST /v1/projects/{projectId}/setup:validate` | `validateProjectSetup` | M |
| `POST /v1/projects/{projectId}/setup:submit` | `submitProjectSetup` | MF |
| `POST /v1/projects/{projectId}/setup:confirm-development` | `confirmProjectSetupDevelopment` | MF |
| `POST /v1/projects/{projectId}/setup:confirm-business` | `confirmProjectSetupBusiness` | MF |
| `POST /v1/projects/{projectId}/setup:activate` | `activateProjectSetup` | MF |
| `GET /v1/projects/{projectId}/repository-binding` | `getProjectRepositoryBinding` | Q |
| `POST /v1/projects/{projectId}/repository-binding-change-requests` | `createRepositoryBindingChangeRequest` | MF |
| `GET /v1/projects/{projectId}/repository-binding-change-requests/{requestId}` | `getRepositoryBindingChangeRequest` | Q |
| `POST /v1/projects/{projectId}/repository-binding-change-requests/{requestId}:confirm` | `confirmRepositoryBindingChange` | MF |
| `GET /v1/projects/{projectId}/role-catalog` | `listProjectRoleCatalog` | Q |
| `GET /v1/projects/{projectId}/role-bindings` | `listProjectRoleBindings` | Q |
| `POST /v1/projects/{projectId}/role-bindings` | `createProjectRoleBinding` | M |
| `POST /v1/projects/{projectId}/role-bindings/{bindingId}:revoke` | `revokeProjectRoleBinding` | MF |
| `GET /v1/projects/{projectId}/side-principals` | `getProjectSidePrincipals` | Q |
| `PUT /v1/projects/{projectId}/side-principals/{side}` | `replaceProjectSidePrincipal` | MF |
| `PUT /v1/projects/{projectId}/separation-policy` | `updateProjectSeparationPolicy` | MF |
| `GET /v1/projects/{projectId}/delegations` | `listProjectDelegations` | Q |
| `POST /v1/projects/{projectId}/delegations` | `createProjectDelegation` | M |
| `POST /v1/projects/{projectId}/delegations/{delegationId}:revoke` | `revokeProjectDelegation` | M |
| `GET /v1/projects/{projectId}/supplier-assignments` | `listExternalSupplierAssignments` | Q |
| `POST /v1/projects/{projectId}/supplier-assignments` | `createExternalSupplierAssignment` | M |
| `POST /v1/projects/{projectId}/supplier-assignments/{assignmentId}:supersede` | `supersedeExternalSupplierAssignment` | M |
| `POST /v1/projects/{projectId}/supplier-assignments/{assignmentId}:revoke` | `revokeExternalSupplierAssignment` | MF |
| `POST /v1/projects/{projectId}/supplier-reassignments` | `createExternalSupplierReassignment` | MF |
| `POST /v1/fresh-authentications` | `startFreshAuthentication` | M |
| `GET /v1/fresh-authentications/{freshAuthId}` | `getFreshAuthentication` | Q |
| `POST /v1/fresh-authentications/{freshAuthId}:complete` | `completeFreshAuthentication` | M |
| `GET /v1/audit/events` | `queryTenantAuditEvents` | Q |
| `GET /v1/audit/events/{eventId}` | `getTenantAuditEvent` | Q |
| `GET /v1/projects/{projectId}/audit/events` | `queryProjectAuditEvents` | Q |
| `GET /v1/projects/{projectId}/audit/events/{eventId}` | `getProjectAuditEvent` | Q |
| `POST /v1/audit/exports` | `createTenantAuditExport` | MF |
| `GET /v1/audit/exports/{exportId}` | `getTenantAuditExport` | Q |
| `POST /v1/audit/exports/{exportId}/download-capabilities` | `createTenantAuditExportDownloadCapability` | MF |
| `POST /v1/projects/{projectId}/audit/exports` | `createProjectAuditExport` | MF |
| `GET /v1/projects/{projectId}/audit/exports/{exportId}` | `getProjectAuditExport` | Q |
| `POST /v1/projects/{projectId}/audit/exports/{exportId}/download-capabilities` | `createProjectAuditExportDownloadCapability` | MF |
| `GET /v1/retention-policy-versions` | `listRetentionPolicyVersions` | Q |
| `GET /v1/retention-policy-versions/active` | `getActiveRetentionPolicy` | Q |
| `POST /v1/retention-policy-versions` | `createRetentionPolicyVersion` | MF |
| `POST /v1/retention-policy-versions/{version}:activate` | `activateRetentionPolicyVersion` | MF |
| `GET /v1/legal-holds` | `listTenantLegalHolds` | Q |
| `POST /v1/legal-holds` | `placeTenantLegalHold` | MF |
| `POST /v1/legal-holds/{holdId}:release` | `releaseTenantLegalHold` | MF |
| `GET /v1/projects/{projectId}/legal-holds` | `listProjectLegalHolds` | Q |
| `POST /v1/projects/{projectId}/legal-holds` | `placeProjectLegalHold` | MF |
| `POST /v1/projects/{projectId}/legal-holds/{holdId}:release` | `releaseProjectLegalHold` | MF |
| `POST /v1/deletion-requests` | `createTenantDeletionRequest` | MF |
| `GET /v1/deletion-requests/{requestId}` | `getTenantDeletionRequest` | Q |
| `POST /v1/deletion-requests/{requestId}:cancel` | `cancelTenantDeletionRequest` | MF |
| `POST /v1/deletion-requests/{requestId}:approve` | `approveTenantDeletionRequest` | MF |
| `POST /v1/deletion-requests/{requestId}:execute` | `executeTenantDeletionRequest` | MF |
| `GET /v1/deletion-requests/{requestId}/proof` | `getTenantDeletionProof` | Q |
| `POST /v1/projects/{projectId}/deletion-requests` | `createProjectDeletionRequest` | MF |
| `GET /v1/projects/{projectId}/deletion-requests/{requestId}` | `getProjectDeletionRequest` | Q |
| `POST /v1/projects/{projectId}/deletion-requests/{requestId}:cancel` | `cancelProjectDeletionRequest` | MF |
| `POST /v1/projects/{projectId}/deletion-requests/{requestId}:approve` | `approveProjectDeletionRequest` | MF |
| `POST /v1/projects/{projectId}/deletion-requests/{requestId}:execute` | `executeProjectDeletionRequest` | MF |
| `GET /v1/projects/{projectId}/deletion-requests/{requestId}/proof` | `getProjectDeletionProof` | Q |

All schemas use `additionalProperties: false`; closed string enums reject unknown values. Request bodies never carry tenant ID, actor ID, natural-person ID, authorization digest, or a project ID already selected by the path. Every envelope contains `object_ref`, `display_state`, `allowed_actions`, and quoted version; pages additionally contain an opaque authenticated cursor. Stable Problem Details map validation 400, authentication 401, authorization 403, invisible resource 404, idempotency conflict 409, version mismatch 412, semantic state 422, rate limit 429, and dependency failure 503 without leaking row existence.

Create `IdentityApiDtos.java` as a non-instantiable holder of nested public records and enums so one source file remains legal Java. Mirror each OpenAPI component one-for-one. Use `UUID`, `URI`, `Instant`, `LocalTime`, closed enums, immutable `List`/`Set`, and Bean Validation. The setup step value is a sealed interface with explicit records for project, repository, mode/separation, role preset, role assignments, assessment policy, Agent/CI, notification/retention, and review. No generic map or arbitrary JSON field is permitted.

- [ ] **Step 5: Implement one operation registry, thin controllers, and transaction-safe application services**

Create `IdentityPublicOperation.java` as an enum containing the 72 exact operation IDs, controller method reference, profile, permission, object type, high-risk flag, and audit action. Startup validation compares it bidirectionally with OpenAPI and real handlers. Authorization, audit, idempotency, metrics, and generated-client ownership all consume this registry; no layer owns a second manually divergent map.

Controllers accept `@AuthenticationPrincipal VerifiedRequestIdentity`, typed path/query/body fields, exact required headers, and return typed `ResponseEntity`. They never read tenant or actor from headers/body, open transactions, contain domain transitions, or convert invisible 403 to visible detail. `projectId` is only a requested target; application authorization resolves it beneath `identity.tenantId()`. Tenant routes operate on the authenticated current tenant and expose no tenant path.

Implement a shared `JooqCommandGate` used inside application services. For each mutation, one transaction must: set tenant context; lock or load idempotency row; compare canonical request digest; require `If-Match` equals body `expected_version` and current row version; authorize the registry operation; conditionally consume the exact FreshAuth proof; execute the domain transition; append the Task 14 audit event; persist status/body/ETag headers for byte-identical replay; and commit. It receives the transaction-scoped `DSLContext` and never opens a nested connection.

`ProjectSetupApplicationService` evaluates and persists these gates: immutable repository trust current; one current Business Principal and one current Development Principal in both modes; Business Acceptance Owner selected; mode/separation and guarantee label consistent; strict mode uses different natural people across sides; reduced Standard overlap is explicitly acknowledged; role preset selections valid; no chained delegation or supplier widening; assessment thresholds/policy linked; Agent Pack and CI evidence current; notification policy has all four channels and urgent/digest/escalation settings; retention policy active; all validation snapshots match the submitted setup digest. Development confirms first, Business confirms second, and activation re-evaluates every external evidence port inside the command before atomically marking setup/project active.

Repository binding change freezes old/new immutable IDs, requires provider reconciliation, invalidates stale trust/analysis evidence, obtains current Development Principal confirmation, and advances the binding version atomically. Supplier reassignment persists the open-work impact digest and replacement eligibility before superseding the old assignment.

- [ ] **Step 6: Unify bearer and browser authentication, conditional CSRF, tenant switching, and FreshAuth**

Extend `VerifiedRequestIdentity.java`:

```java
package com.inforvans.accord.controlplane.security;

import java.util.UUID;

public record VerifiedRequestIdentity(
    UUID tenantId,
    UUID accountId,
    UUID naturalPersonId,
    AuthenticationMechanism mechanism,
    String issuerSubjectDigest,
    long identityVersion,
    UUID browserSessionId,
    long sessionEpoch,
    long csrfEpoch
) {
    public enum AuthenticationMechanism { OIDC_BEARER, BROWSER_SESSION }
}
```

Both mechanisms resolve issuer/subject through server-side identity mappings and build the same principal. A bearer is selected only when the session cookie is absent; conflicting credentials return `400 MULTIPLE_AUTHENTICATION_MECHANISMS`. No production endpoint accepts `X-Accord-Tenant`, actor headers, or a raw bearer string as an application argument.

Create `BrowserSessionService.java`. The `__Host-accord-session` cookie is authenticated encryption containing tenant locator, session ID, session epoch, 256-bit session secret, key version, and absolute expiry. PostgreSQL stores only token hash and the session record. Cookie attributes are `Path=/; Secure; HttpOnly; SameSite=Lax`, no `Domain`, with absolute lifetime at most eight hours and bounded idle expiry. Authenticate by validating envelope/key version, opening a tenant transaction, constant-time token-hash comparison, identity/session versions, issuer/subject, expiry, and revocation before constructing the principal. Database/KMS failure is 401; there is no cache-only authorization.

Create `BrowserMutationGuard.java` after authentication and before controller authorization. Cookie-authenticated unsafe methods require exact allowed Origin, Fetch Metadata same-origin, and `X-Accord-CSRF`; bearer requests are exempt only by the verified mechanism. The token is unpadded base64url HMAC-SHA-256 over tenant ID, session ID, session epoch, CSRF epoch, and origin with a random 256-bit secret. Store that secret encrypted under a dedicated browser-session KMS purpose plus digest/key version; rotate it on login, FreshAuth callback, tenant switch, and privilege elevation. A bounded process-local decrypted-secret cache lasts at most 60 seconds, is keyed by tenant/session/session epoch/CSRF epoch, and is cleared on revoke/rotation. It never enters logs, traces, distributed state, or error bodies.

Fresh authentication binds an opaque allowed-action record to tenant, account, natural person, identity version, exact registry action, object, project, expected object version, authorization digest, method, and expiry. Completion rotates the browser session before issuing the proof. Consumption is one SQL update with all binding fields and `consumed_at IS NULL AND expires_at > now`, inside the command transaction. Reuse and mismatch expose one fail-closed result.

Tenant switching accepts only a server-minted opaque tenant reference. Consume the intent once, recheck membership, revoke the source session, issue a new target session/cookie, rotate CSRF/session epochs, and append audit in one broker transaction. A full-page callback must bootstrap the rotated session before any mutation.

- [ ] **Step 7: Implement audited governance queries, exports, capabilities, holds, and deletion commands**

Create `AuditGovernanceApplicationService.java` over Tasks 14-15. Every query first opens the authenticated tenant transaction and applies viewer authorization in SQL. Missing and invisible IDs return byte-equivalent 404 bodies after correlation normalization. Tenant audit requires Auditor or Tenant Administrator; project audit also accepts a scoped Auditor binding. Actor presentation is policy-redacted and never exposes immutable subject, email, authorization snapshot, or raw event details.

Audit cursors are authenticated encrypted payloads binding tenant, requesting account/identity version, tenant-or-project scope, canonical filter digest, direction, last `(occurred_at, stream_id, sequence)`, page size, and five-minute expiry. Export jobs persist the same filter digest. Download returns only a single-use, audience-bound, short-lived capability; the API never returns an object-store credential or permanent URL. Capability consumption is append-only and atomic.

Retention, legal hold, and deletion controllers call Task 15 services through the shared command gate. Deletion approval is append-only, from an eligible natural person different from the requester, and bound to request version/manifest. Execute consumes FreshAuth, authorizes execution, creates the fenced worker command, and returns 202; it never performs physical deletion on the HTTP thread. Read models expose waiting/blocked/executing/completed state and only minimal signed proof fields.

Create `identity-public.contract.test.ts` with the same 72 operation IDs. Import only generated endpoint/schema exports and assert each operation is a function. Do not add handwritten URLs, transport DTOs, enums, or validation schemas, and never edit `packages/api-client/src/generated/**`.

- [ ] **Step 8: Generate clients and run contract, migration, security, lifecycle, and browser gates**

Run:

```bash
./gradlew :apps:control-plane:modules:identity:test
./gradlew :apps:control-plane:modules:authorization:test
./gradlew :apps:control-plane:modules:audit:test
./gradlew :apps:control-plane:api:test --tests '*ContractValidationApiTest'
./gradlew :tests:api:test --tests '*IdentityPublicApiContractTest'
./gradlew :tests:integration:test --tests '*ProjectSetupMigrationIT' --tests '*IdentityPublicHttpIT'
./gradlew :tests:security-negative:test --tests '*IdentityPublicApiSecurityTest'
pnpm contracts:test
pnpm contracts:lint
pnpm api:generate
pnpm --filter @accord/api-client test -- identity-public.contract.test.ts
pnpm --filter @accord/api-client typecheck
pnpm --filter @accord/api-client check:generated
```

Expected: every command exits 0. The cumulative OpenAPI retains Foundation operations and owns exactly 72 Identity operations; each has one method/path/controller/application registry/generated-client mapping; all commands enforce durable idempotency, quoted version equality, conditional CSRF, ETag, stable Problem Details, and atomic FreshAuth where declared. Both authentication mechanisms produce server-derived identity; mixed credentials, stale/rotated/revoked sessions, cursor replay, and tenant spoofing fail closed. V019 follows V018, all tenant tables use composite relationships, forced RLS, and least privilege. Setup resumes, freezes, revalidates evidence, requires both current principals plus Business Acceptance Owner and ordered confirmations, and activates atomically. Audit/export/retention/hold/deletion lifecycles pass with no handwritten client fallback.

- [ ] **Step 9: Commit the production Identity public surface**

```bash
git add database/control-plane/migrations/V019__project_setup_and_browser_sessions.sql apps/control-plane/modules/identity apps/control-plane/modules/authorization apps/control-plane/modules/audit apps/control-plane/api contracts/openapi/accord-control-api.yaml tests/api tests/integration tests/security-negative packages/api-client docs/architecture/identity-tenancy-audit.md
git commit -m "feat(api): publish identity setup and governance contracts"
```

## Completion Gate

The Identity, Tenancy, and Audit plan is complete only after Tasks 1-17 run in order and all seventeen task-local commits exist. The completion evidence must prove immutable repository identity, forced tenant isolation, enterprise subject mapping, both current side principals in Standard and Strict modes, explicit reduced-Standard labeling, same-side administrator/principal role overlap, strict cross-side natural-person separation, non-transitive delegation, supplier assignment scope, server-bound FreshAuth, purpose-bound signing, append-only anchored audit, versioned retention/hold/deletion proof, resumable setup, dual authentication with conditional CSRF, and the exact cumulative public HTTP/generated-client contract. Passing domain tests without Task 17's black-box/security/generated-client gates is not completion.

Before claiming this plan complete, run a document check that asserts exactly 17 `### Task` headings, 17 `**Files:**` blocks, and 17 task commit commands; balanced Markdown fences; no duplicate exact `Create:` path; one V019 declaration between V018 and V020; zero tenant-routed public operation signatures; zero client-authoritative tenant/actor request fields; and equality of the 72 OpenAPI/controller/application/generated-client operation IDs.

## Plan Self-Review

- [x] Coverage maps to executable tasks: immutable tenant/repository scope (Tasks 2-3), OIDC/SAML/SCIM and human/Git/workload identity (Task 4), RBAC and highest-principal behavior (Task 5), strict natural-person separation (Task 6), non-transitive delegation (Task 7), fresh-auth replay defense (Task 8), vendor visibility (Task 9), strict-merge plus separate break-glass DSSE/protobuf contracts (Task 10), separate trust/KMS/fenced signing store (Tasks 11-12), strict-only atomic nonces and mTLS (Task 13), hash-chain/Merkle/Object Lock audit (Task 14), retention/legal hold/deletion proof (Task 15), isolated production signing deployment and negative matrix (Task 16), and normalized setup/browser sessions plus the complete Identity/Tenancy/RBAC/FreshAuth/Audit/Retention HTTP surface (Task 17).
- [x] Tenant and repository fields are consistent: every model carries `tenant_id`; only `ScopeIdentity.Repository`, repository bindings, repository audit events, and repository signing scopes carry immutable repository identity. Tenant/project/pre-binding objects do not invent one.
- [x] Authorization types and signatures are consistent across tasks: human actions consume `PrincipalIdentity.Human`; strict comparison uses `naturalPersonId`; delegation is direct and cannot be chained; high-risk FreshAuth sessions and strict-merge authorization nonces consume atomically, while break-glass uses its separate broker reservation generation.
- [x] Signing types are consistent across protobuf, Java, SQL, and JSON Schema: purpose/domain/payload type are paired, ES256 keys are tenant/purpose scoped, long-lived evidence has no expiry, strict merge has exactly four subjects plus an exact nonce, and break-glass has a mutually exclusive closed binding, signed evidence snapshot, fenced byte-replay store, and no entry into the strict nonce path.
- [x] Task 17 uses `VerifiedRequestIdentity` as the only tenant/actor authority, exposes every project resource below `/v1/projects/{projectId}/...`, keeps tenant administration in the current authenticated scope without tenant-routed endpoints, and gives all mutations one idempotency/CAS/CSRF/Problem profile with atomic FreshAuth for high risk.
- [x] Project setup has explicit fields and normalized child tables rather than a generic JSON document; repository/support selections freeze at submit, Agent/Assessment references are verified through Task 13 ports, both modes require one current principal per side, and only explicit reduced Standard policy lowers the guarantee label.
- [x] OpenAPI, controller annotations, application registry, and generated TypeScript exports share the exact same 72-operation owner set; browser code has no handwritten URL or server DTO escape hatch.
- [x] The unfinished-marker scan, repeated-task-reference scan, SQL/Java/protobuf name comparison, and negative-test coverage review have been completed against this document; no deferred implementation language remains.
