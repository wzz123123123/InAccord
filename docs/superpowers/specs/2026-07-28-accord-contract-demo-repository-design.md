# Accord Contract Demo Repository Design

**Status:** Updated and approved by the product owner on 2026-07-28.

**Purpose:** Provide one small real source repository that Accord can use to demonstrate customer-side analysis and platform-owned document iteration.

## Boundary

`AccordContractDemo` is a private GitHub repository under the authenticated account. GitHub is only the initial source host; it can later be imported into company GitLab without changing the project. Until that import and a real GitLab Provider binding exist, Accord must not display the repository as connected.

The repository is deliberately small. It contains a Java 21 contract-approval state machine, one focused test, a README, CI definitions, and an `AGENTS.md`. It does not introduce Spring Boot, a database, Docker, infrastructure, or a second product backend.

Accord owns and versions the business requirement document, development view, impact analysis, proposals, scores, confirmations, and Development Package. Those documents do not become Git facts and Accord does not write them into this repository.

## Developer Agent Workflow

The platform-provided `accord-developer-workflow` skill is installed into the local Codex skill directory from the repository's Agent Pack. The installed copy includes the source-free structured-analysis schema and is byte-verified against the platform source.

The repository `AGENTS.md` tells local Codex to use that skill for exactly one platform-requested action:

- `analyze-context`: read source locally and return only schema-valid semantic claims;
- `implement-requirement`: change local source from a signed Development Package;
- `prepare-completion`: return source-free completion facts after local verification.

Source, snippets, diffs, patches, paths, repository URLs, credentials, configuration values, and secrets never enter an Accord payload. Git clone, edit, test, commit, and push remain developer actions.

## Minimal Project

The Java project models a contract with `DRAFT`, `SUBMITTED`, `APPROVED`, and `REJECTED` states. A requester can submit a draft; an approver can approve or reject a submitted contract. Invalid roles and state transitions fail explicitly. Stable module, interface, data, permission, state, test, and runtime identifiers are documented in `AGENTS.md` so local analysis remains consistent across revisions.

The sample includes a follow-up business request in the README for the platform demo: contracts above a stated amount should require legal review before approval. The request itself is entered and maintained in Accord, not committed as an authoritative requirement document.

## Acceptance

The sample is complete when:

1. the installed skill and structured-analysis schema match their platform source SHA-256 values;
2. Java 21 compilation and the focused state-machine test pass;
3. the repository contains no personal credential or environment file;
4. the private GitHub repository exists and remote `main` equals the local commit;
5. README and `AGENTS.md` state that Accord maintains documents while source stays local;
6. GitLab import and Accord Provider binding are documented as the later real integration step.
