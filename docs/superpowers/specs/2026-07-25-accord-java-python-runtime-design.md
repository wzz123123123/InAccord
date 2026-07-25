# Accord Java And Python Runtime Design

**Status:** Approved by the product owner on 2026-07-25.

**Purpose:** Bind every enterprise implementation plan to one production runtime model before repository bootstrap.

## Runtime Decisions

| Area | Normative implementation |
| --- | --- |
| Control plane | Java 21, Spring Boot 3.5.3, Spring Modulith 1.4.1, jOOQ, Flyway, Gradle 8.14.3 Groovy DSL |
| Edge and security processes | Independent Java 21 Spring Boot applications with distinct identities, database roles, credentials, network policies, and deployable artifacts |
| Agent runtime | Python 3.12.11, Pydantic v2, Temporal Python SDK, uv |
| Browser | React 19.1, TypeScript 5.8, Vite 7, generated OpenAPI client |
| Developer CLI | Java 21, Picocli, `jlink` runtime image; no host Java prerequisite |
| Business facts | PostgreSQL 17.5 only |
| Durable orchestration | Temporal with a dedicated production PostgreSQL cluster |
| Large objects | Private OSS/S3-compatible object storage through a capability-tested adapter |

Kotlin, Go, Redis, and DynamoDB are not V1 runtime or persistence dependencies. Kafka and Camunda remain excluded.

## Process And Trust Boundaries

Language consolidation does not consolidate authority. `control-api`, `control-worker`, `webhook-edge`, `attachment-scanner`, `agent-pack-gateway`, `signing-service`, `requirement-publisher`, `merge-controller`, and any break-glass broker remain separate deployables where the product trust model requires separation. They may share generated contract artifacts and narrowly scoped Java libraries, but security services cannot import control-plane domain modules and no process can reuse another purpose's credential.

The control plane never reads or stores customer source, source archives, full diffs, or general Git content credentials. Customer Codex and customer CI generate Project Context and Context Patch documents. Platform Java and Python processes consume only schema-validated, signed, source-free structured facts.

## Storage Rules

PostgreSQL owns every durable command result, idempotency key, compare-and-swap version, outbox/inbox record, approval, score, lease, fencing token, one-time capability state, and certification coordination fact. Separate schemas, roles, row-level security, partitioning, and, in production, separate high-availability clusters preserve workload isolation.

Process-local caches may hold public or already-authorized immutable data for bounded periods. They are disposable and cannot decide authorization, replay protection, workflow state, or cross-replica coordination. There is no Redis failover, backup, source-canary, IaC, or acceptance gate in V1.

Object bodies belong in private OSS/S3-compatible storage. The storage port exposes immutable version identifiers, checksums, retention/WORM state, legal hold where required, multipart upload, malware quarantine, cross-region replication evidence, and deletion receipts. A deployment can claim a feature only when its provider adapter proves the required capabilities.

## Build And Contract Rules

The repository is a Gradle multi-project build using Groovy DSL and Java source sets. There are no `.kt`, `.kts`, `go.mod`, `go.work`, or `.go` implementation files. Java-to-Java service calls still use versioned protobuf/gRPC and mTLS where process isolation requires RPC; Java-to-Python jobs use closed JSON Schema contracts; browser calls use the cumulative OpenAPI 3.1 document. RFC 8785 JCS, SHA-256, DSSE, golden fixtures, and clean code generation remain release gates.

`accordctl` is a Gradle application module. Picocli commands are tested through their public command line contract, and `jlink` produces platform-specific runtime images. CI builds, signs, inventories, and verifies each image independently.

## Plan Migration Rules

1. Convert all Kotlin production and test paths to `src/main/java` and `src/test/java`; convert examples to Java 21 records, sealed interfaces, and JUnit 5/AssertJ/jqwik as appropriate.
2. Convert Gradle Kotlin DSL files to Groovy DSL (`settings.gradle`, `build.gradle`) and remove Kotlin/Kotest plugins and dependencies.
3. Convert every Go service, library, test, command, and build invocation to its owning Java Gradle subproject without changing its process, credential, or network boundary.
4. Convert `accordctl` paths and commands to the Java Picocli/jlink module and use the generated launcher in end-to-end tests.
5. Replace Redis and DynamoDB authority or coordination with PostgreSQL transactions, constraints, durable claims, leases, and fencing tokens. Remove their infrastructure and failure drills rather than renaming them.
6. Keep Python, React/TypeScript, Temporal, OpenAPI, protobuf, object storage, Kubernetes, Helm, OpenTofu, Argo CD, OpenTelemetry, and supply-chain gates where they remain applicable.
7. Update every task file list, code block, command, expected result, module count, boundary manifest, compatibility fixture, and milestone assertion. A superseding note above stale Kotlin/Go instructions is not sufficient.
8. Repository inception is a pre-FT1 operation. The approved specification and all plans are committed and pushed to `main` before Foundation Task 1 starts on an isolated feature branch; Foundation Task 1 must not run `git init`.

## Acceptance

The migration is acceptable only when static scans find no Kotlin or Go runtime paths, code fences, plugins, commands, module files, or Redis/DynamoDB runtime dependencies; plan task numbering and milestone dependencies remain coherent; and Foundation Task 1 can bootstrap a Java/Python/Node workspace from the tracked design baseline.
