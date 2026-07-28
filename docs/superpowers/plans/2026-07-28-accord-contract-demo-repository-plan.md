# Accord Contract Demo Repository Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create and push a minimal private Java repository that demonstrates Accord's platform-owned document workflow and source-free local Codex analysis.

**Architecture:** Use a dependency-light Java 21 Gradle project with one domain service and one JUnit test. Keep all requirement and development documents in Accord; the source repository contains only code, developer instructions, and normal repository documentation.

**Tech Stack:** Java 21, Gradle 8.14.3, JUnit 5, GitHub Actions, GitLab CI.

---

### Task 1: Install And Verify The Accord Developer Skill

**Files:**
- Source: `agent-pack/skills/accord-developer-workflow/SKILL.md`
- Source: `agent-pack/skills/accord-developer-workflow/references/structured-source-analysis.schema.json`
- Install: `C:/Users/m1560/.codex/skills/accord-developer-workflow/`

- [x] **Step 1: Install the local platform Agent Pack skill**

Copy only `SKILL.md` and its referenced structured-analysis schema into the Codex skill directory. Do not copy credentials, runtime configuration, or generated output.

- [x] **Step 2: Verify source and installed SHA-256 values**

Expected: both installed files are byte-identical to the platform source. Codex discovers the new skill on the next turn.

### Task 2: Create The Minimal Java Project With TDD

**Files:**
- Create: `D:/CODE/SELF/AccordContractDemo/settings.gradle`
- Create: `D:/CODE/SELF/AccordContractDemo/build.gradle`
- Create: `D:/CODE/SELF/AccordContractDemo/gradle.properties`
- Create: `D:/CODE/SELF/AccordContractDemo/.gitignore`
- Create: `D:/CODE/SELF/AccordContractDemo/src/main/java/com/inforvans/accord/demo/Contract.java`
- Create: `D:/CODE/SELF/AccordContractDemo/src/main/java/com/inforvans/accord/demo/ContractStatus.java`
- Create: `D:/CODE/SELF/AccordContractDemo/src/main/java/com/inforvans/accord/demo/ContractApprovalService.java`
- Test: `D:/CODE/SELF/AccordContractDemo/src/test/java/com/inforvans/accord/demo/ContractApprovalServiceTest.java`
- Copy: the locked Gradle wrapper from Accord

- [ ] **Step 1: Initialize an independent repository on `main`**

Expected: `D:/CODE/SELF/AccordContractDemo` is a sibling repository, not a nested Accord worktree.

- [ ] **Step 2: Write the failing lifecycle test**

```java
@Test
void requesterSubmitsAndApproverApproves() {
    ContractApprovalService service = new ContractApprovalService();
    Contract draft = Contract.draft("ACME-2026-001", "Acme Ltd");

    Contract submitted = service.submit(draft, "REQUESTER");
    Contract approved = service.approve(submitted, "APPROVER");

    assertThat(approved.status()).isEqualTo(ContractStatus.APPROVED);
}
```

Run: `./gradlew test --tests '*ContractApprovalServiceTest' --no-daemon --console=plain`

Expected: RED because the domain classes do not exist.

- [ ] **Step 3: Implement the minimal state machine**

`Contract` is an immutable record. `submit` accepts only `DRAFT` plus role `REQUESTER`; `approve` and `reject` accept only `SUBMITTED` plus role `APPROVER`. Every invalid role or state throws `IllegalStateException` with a stable semantic message.

- [ ] **Step 4: Run GREEN and commit**

Expected: the focused lifecycle and invalid-transition tests pass.

Commit: `feat: add contract approval sample`

### Task 3: Add The Agent Boundary And Repository Guidance

**Files:**
- Create: `D:/CODE/SELF/AccordContractDemo/AGENTS.md`
- Create: `D:/CODE/SELF/AccordContractDemo/README.md`
- Create: `D:/CODE/SELF/AccordContractDemo/.github/workflows/ci.yml`
- Create: `D:/CODE/SELF/AccordContractDemo/.gitlab-ci.yml`

- [ ] **Step 1: Add `AGENTS.md`**

Name the installed `accord-developer-workflow` skill, its three actions, stable semantic identifiers, required platform-supplied digests, and the complete prohibited-payload boundary. State that Accord maintains documents and never reads or writes repository source.

- [ ] **Step 2: Add the concise README**

Document build/test commands, the current state flow, the follow-up legal-review request for an Accord demo, and GitLab import. Do not commit a platform-owned requirement or impact-analysis document.

- [ ] **Step 3: Add provider-portable CI and commit**

Both CI files select Java 21 and run `./gradlew test --no-daemon --console=plain`.

Commit: `docs: define Accord local-agent workflow`

### Task 4: Verify Once And Push The Private Repository

- [ ] **Step 1: Run the focused verification**

Run the lifecycle test once, `git diff --check`, and a credential-pattern scan over tracked text files. Fix and rerun only a failing lane.

- [ ] **Step 2: Create and push the private remote**

Run:

```powershell
gh repo create wzz123123123/AccordContractDemo --private --source . --remote origin --push
```

- [ ] **Step 3: Verify remote authority**

Expected: `gh repo view` reports `isPrivate=true`, default branch `main`, and `git ls-remote` returns the exact local HEAD.
