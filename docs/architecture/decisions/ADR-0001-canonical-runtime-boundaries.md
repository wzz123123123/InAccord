# ADR-0001: Canonical Runtime And Trust Boundaries

- Status: Accepted
- Date: 2026-07-28
- Compatibility contract: `contracts/compatibility/v1-boundaries.yaml`
- Deployment boundary inventory: `docs/architecture/accord-v1-boundaries.yaml`

## Context

Accord coordinates long-running requirement, delivery, acceptance, and recovery workflows without operating on customer source. The production design needs one runtime topology that remains understandable while preserving the independent identities and credentials required by the trust model.

## Decision

The business control plane is Java 21 with Spring Boot and Spring Modulith. It runs as separately permissioned `control-api` and `control-worker` processes. Independent Java 21 Spring Boot applications implement edge and security boundaries. The Agent Runtime is Python 3.12 with Pydantic, the browser is React 19.1 with TypeScript 5.8 and Vite 7, and `accordctl` is Java 21 with Picocli delivered as a jlink runtime image.

PostgreSQL 17.5 is the sole business fact authority. It owns commands, idempotency, compare-and-swap versions, approvals, outbox/inbox delivery, leases, fencing tokens, one-use capabilities, and certification coordination. Temporal coordinates waits, retries, and progress by identifier; Temporal history is not business truth and cannot replace a PostgreSQL decision record. Provider webhooks are hints that trigger reconciliation and are never authorization or state-transition authority.

Every edge and security process has its own workload identity, database role where persistence is required, purpose-bound credentials, and network policy. Security services may consume generated contracts and bounded Java libraries but may not import control-plane domain modules. No process may reuse another process's credential or another credential purpose.

`webhook-edge` and `provider-auth-callback-edge` select the same signed `apps/webhook-edge` image digest but are distinct workload profiles. They have separate ServiceAccounts, database roles, encryption keys, ingress paths, mTLS audiences, queues, and NetworkPolicies. Neither identity can assume the other.

The Agent Pack Gateway uses a distinct ServiceAccount and IRSA role, a purpose-specific cache KMS key, and egress restricted to identity verification, PostgreSQL, telemetry, the fixed allowlisted OCI registry, and its encrypted cache. It has no customer Git or customer-source egress. Its component chart is owned by the Agent Context plan, but `infra/helm/accord` is the only production release and the Gateway chart is not independently installed.

The control plane, Agent Runtime, Gateway, and security services consume only structured, signed, source-free projections. Developers and their Codex/CI remain solely responsible for cloning, editing, committing, and pushing customer code.

OpenTofu is the infrastructure-as-code authority. Kubernetes, Helm, and Argo CD form the deployment path. AWS Tokyo with Osaka warm DR is the first reference deployment, not an application architecture assumption.

## Rejected Alternatives

- A TypeScript server control plane is rejected because V1 has one Java business runtime and one generated browser client boundary.
- Direct platform access to customer repositories, blobs, diffs, source archives, or general Git content credentials is rejected; the platform operates only on metadata and source-free documents.
- An independently installed Agent Pack Gateway production release is rejected because it would create a second release authority outside `infra/helm/accord`.
- Shared identities, database roles, network policies, signing keys, or provider credentials across security services are rejected because a common implementation language does not collapse authority.
- Provider webhooks as business truth are rejected because deliveries can be delayed, duplicated, forged, reordered, or lost; reconciliation must read current provider metadata facts.
- Temporal history as business truth is rejected because workflow replay and retry state cannot substitute for tenant-scoped, versioned, auditable PostgreSQL records.
- Redis or another cache as an authorization, coordination, replay, or workflow authority is rejected. Disposable bounded caches may be added only for demonstrated performance needs.

## Consequences

Contracts under `contracts/**` are the only cross-runtime interfaces. Java, Python, and TypeScript generated types are derived artifacts. Each new deployable must extend the boundary inventory before it can receive an identity, credential, database role, or network access. Any drift between the compatibility manifest, deployment inventory, production Helm release, and implementation is a release-blocking architecture failure.
