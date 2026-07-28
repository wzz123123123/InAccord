# Accord Contract Demo Repository Design

**Status:** Approved by the product owner on 2026-07-28.

**Purpose:** Provide one real, independently runnable business repository that can demonstrate Accord's customer-side source analysis and requirement delivery workflow without allowing the Accord platform to read or store source code.

## Repository And Hosting

The repository is named `AccordContractDemo` and is created as a private repository under the currently authenticated GitHub account. GitHub is only its initial source host. The repository must remain portable to GitLab through the standard GitLab import or mirror workflow without changing its build, runtime, analysis contract, or branch model.

Accord currently has a production GitLab Provider adapter but no active GitHub Provider adapter. The demo repository therefore must not be represented as connected in Accord while it exists only on GitHub. A later GitLab import and Provider installation will establish the real Accord repository binding.

No credential, personal environment file, platform model configuration, OSS key, database password, access token, or machine-specific path may be committed.

## Business Scenario

The demo implements an enterprise contract approval service. It supports this auditable lifecycle:

1. A requester creates a contract draft with counterparty, value, currency, effective dates, owner, and risk classification.
2. The requester submits the draft for review.
3. A reviewer records a risk decision and review notes.
4. An approver approves or rejects the reviewed contract.
5. Every accepted state transition appends an immutable business audit event.

The primary aggregate is `Contract`. Its lifecycle is `DRAFT -> IN_REVIEW -> REVIEWED -> APPROVED` or `REJECTED`. Invalid transitions, stale optimistic versions, duplicate idempotency keys, malformed money or date ranges, and unauthorized role actions fail without partially changing business state.

## Architecture

The service is a Java 21 Spring Boot modular monolith built with the Gradle wrapper. It uses PostgreSQL as its only durable fact store and Flyway for versioned schema migration. OpenAPI describes the HTTP boundary. Docker Compose provides a local PostgreSQL dependency, while the application can also run against an externally supplied PostgreSQL instance.

The code is divided into focused packages:

| Package | Responsibility |
| --- | --- |
| `contract.api` | Versioned HTTP requests, responses, validation, and error mapping |
| `contract.application` | Commands, queries, authorization ports, idempotency, and transaction boundaries |
| `contract.domain` | Contract aggregate, lifecycle rules, money and date invariants, and domain events |
| `contract.persistence` | PostgreSQL repositories, optimistic concurrency, and audit event storage |
| `contract.bootstrap` | Spring Boot startup and environment configuration |

The initial repository contains one deployable service rather than artificial microservices. Package boundaries and ports keep later extraction possible without introducing distributed-system complexity into the sample.

## API And Data Flow

The API exposes health/readiness plus endpoints to create, read, list, submit, review, approve, and reject contracts. Mutating calls require an `Idempotency-Key`, an actor identifier, an actor role, and the expected aggregate version where applicable.

Each mutation follows one transaction:

1. Validate request shape, actor role, idempotency fingerprint, and expected version.
2. Load or create the aggregate and apply a permitted domain transition.
3. Persist the aggregate with optimistic concurrency control.
4. Append the corresponding immutable audit event.
5. Persist the command result for deterministic idempotent replay.
6. Commit, then return the new aggregate representation and version.

The schema uses UUID identifiers, UTC timestamps, fixed-precision monetary values, explicit enum checks, unique idempotency constraints, foreign keys, and indexes for the supported queries. Flyway migrations are append-only after publication.

## Accord And Codex Contract

The repository includes an `AGENTS.md` that directs the developer's local Codex to install and use the platform-provided, digest-verified `accord-developer-workflow` Agent Pack. The repository does not copy a mutable replacement of the platform skill and does not make checked-in analysis output authoritative.

For `analyze-context`, local Codex reads the repository locally and emits only the platform schema's `StructuredSourceAnalysis` fields. Claims use stable semantic identifiers for modules, interfaces, data, permissions, states, dependencies, tests, and runtime facts. The output must not contain source, snippets, diffs, patches, paths, repository URLs, credentials, configuration values, or other prohibited material.

For `implement-requirement`, local Codex consumes the signed Development Package and changes the local repository. For `prepare-completion`, it reports only source-free semantic outcomes, addressed claim identifiers, test status, risks, and unknowns. Git operations remain developer or customer-CI actions; Accord never clones, edits, commits, or pushes source.

The repository includes:

- `AGENTS.md` with repository conventions and the Accord Agent Pack handoff;
- `docs/architecture.md` with stable semantic component identifiers;
- `docs/demo-scenarios.md` with a baseline scenario and a later change request suitable for impact analysis;
- `.env.example` containing names and safe local defaults only;
- CI definitions that build and test the repository without platform or personal secrets.

## Error Handling And Security

The HTTP API returns RFC 9457 problem details with a stable error code and correlation identifier. Validation, authorization, transition conflict, optimistic concurrency conflict, idempotency conflict, not-found, and unexpected failures are distinct responses. Unexpected errors do not expose stack traces, SQL, secrets, or internal paths.

The sample uses explicit application roles (`REQUESTER`, `REVIEWER`, `APPROVER`) supplied through a replaceable authentication port. Its local demo adapter accepts headers so the workflow is easy to exercise, but the production profile fails startup unless a real authenticated principal adapter is configured. SQL is parameterized, management endpoints are restricted, secure defaults are documented, and dependency versions are locked.

## Developer Experience

The repository must be usable with a host Java 21 runtime and Docker, and it must also offer a single Docker Compose path for evaluators who do not want to install PostgreSQL. The README contains exact commands for startup, migrations, a complete contract lifecycle, focused tests, and GitLab import. It clearly separates local-demo configuration from production requirements.

Generated files and local state are ignored. The Gradle wrapper and dependency locks are committed so a clone is reproducible. The default branch is protected when provider support permits it; feature changes use pull requests or merge requests.

## Verification And Acceptance

The repository is accepted when all of the following are true:

1. The private remote repository exists and a fresh clone resolves to the same initial commit.
2. Java 21 compilation and focused unit tests pass through the committed Gradle wrapper.
3. A PostgreSQL-backed integration smoke test proves create, submit, review, approve, audit retrieval, optimistic conflict, invalid transition, and idempotent replay.
4. OpenAPI validation succeeds and the README lifecycle commands match the implemented API.
5. Secret scanning finds no credential-shaped or personal configuration material.
6. The repository's Codex instructions conform to the platform Agent Pack and explicitly enforce the no-source platform boundary.
7. The repository is portable to GitLab with no code change, and the documentation identifies GitLab import plus Accord binding as the remaining provider step.

Only targeted verification is required for the initial sample delivery: one compile/test invocation, one database-backed smoke run, one contract check, and one secret scan. Repeated full-suite runs are not part of this delivery.
