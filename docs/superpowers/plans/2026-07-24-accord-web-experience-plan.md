# Accord Web Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the production-grade Accord V1 web application for tenant-scoped requirement intake, evidence-backed assessment and confirmation, delivery control, acceptance, audit, and administration.

**Architecture:** Use a React 19 authenticated SPA whose route state is shareable, whose server state is owned by TanStack Query, and whose domain commands are generated from the canonical OpenAPI contract. The browser renders server-provided display projections and `allowed_actions`; it never reimplements the server state machine, RBAC policy, assessment formula, or merge gates. Vertical feature modules compose through public interfaces, while SSE sequence recovery, exact version/hash commands, server drafts, tenant cache isolation, and route-level error boundaries provide production correctness.

**Tech Stack:** React 19, TypeScript strict, Vite, React Router 7, TanStack Query/Table/Virtual, React Aria Components, vanilla-extract, React Hook Form, Zod, XState, Zustand, `@xyflow/react`, ELK.js, Uppy, OpenAPI-generated fetch client, native fetch/SSE, Storybook, Vitest, Testing Library, MSW, Playwright, axe-core, and OpenTelemetry-compatible RUM.

---

## Scope And Non-Negotiable Frontend Rules

- The approved product specification is `requirements-agent-platform-design.md`; implementation decisions in this plan must preserve its complete production V1 scope.
- The platform-foundation plan creates `contracts/openapi/accord-control-api.yaml`; every backend domain plan extends that same file before its dependent web slice begins. Task 2 initializes the cumulative client from the foundation baseline, every later contract extension reruns generation, and CI fails if generated output is stale. Web tasks never edit the canonical API input.
- Every resource response used for an action contains `allowed_actions`; every action contains the command endpoint, exact expected version/hash, idempotency requirement, and fresh-auth requirement.
- The frontend never derives whether a requirement is ready, whether a person is authorized, whether an assessment passes, or whether a batch can merge. It renders `display_state`, `gate_explanations`, scores, and `allowed_actions` returned by the server.
- Confirmation, approval, role, permission, attachment classification, batch, candidate, and acceptance mutations are pessimistic: the UI changes authoritative state only after a server receipt.
- Tenant/project IDs in URLs are immutable IDs. Names are labels. Switching tenant clears query data, SSE streams, sensitive drafts, previews, and non-semantic preferences scoped to the previous tenant.
- Business UI uses the approved Chinese labels. Internal enums, digests, claims, and attestations are available in development/audit disclosure panels.
- Desktop is a dense operational console. Mobile uses directory/list/detail projections and never requires the graph canvas to complete a workflow.

## Planned Repository Structure

```text
package.json
pnpm-workspace.yaml
tsconfig.base.json
eslint.config.mjs
contracts/openapi/accord-control-api.yaml        # canonical backend artifact, read-only to web tasks
apps/web/
  index.html
  package.json
  tsconfig.json
  vite.config.ts
  playwright.config.ts
  src/
    main.tsx
    app/                                          # router, providers, auth, shell, errors
    routes/                                       # thin route loaders and page composition
    modules/
      actions/
      requirements/
      intake/
      attachments/
      assessment/
      confirmation/
      project-context/
      delivery/
      acceptance/
      audit/
      settings/
    shared/                                       # api, auth, realtime, telemetry, i18n, utilities
  tests/e2e/
packages/
  api-client/                                    # cumulative generated server types, schemas, and endpoints
  ui/                                            # tokens and accessible UI primitives
  testkit/                                       # MSW handlers, fixtures, role sessions, page objects
tests/architecture/
```

Import rules enforced by ESLint:

```text
routes -> modules public index -> shared/packages
module A -X-> module B internals
shared -X-> modules
ui/api-client -X-> apps/web
generated api-client files -X-> manual edits
```

## Backend Contract Slice Dependencies

`packages/api-client/src/generated/**` is one cumulative generated artifact, not a Web-owned contract. Complete the named backend OpenAPI extension and run `pnpm api:generate && pnpm --filter @accord/api-client check:generated` before implementing its dependent web task. If the named backend plan has no committed extension yet, the web task is blocked; do not invent an endpoint or handwritten response type in the browser.

| Web tasks | Required backend OpenAPI extension |
|---|---|
| 4 and 16 identity, tenancy, roles, suppliers, audit | identity-tenancy-audit public API task |
| 6-11 requirement SSE, ActionRequest, graph, intake, attachments, revisions | requirement-workflow Task 9 |
| 12 Impact Draft/assessment portion and 14 Project Context portion | agent-context-assessment Task 13 |
| 12 proposal/confirmation portion | requirement-workflow Task 9 |
| 13 Ready Pool and DeliveryBatch portions; 14 WorkItem/Git portion | git-delivery-control Task 1 |
| 15 Candidate, AcceptanceRun, and CorrectionRun | candidate-acceptance Task 9 |

The backend task that adds an action-bearing response owns the OpenAPI conformance assertion that its envelope requires `allowed_actions`, `object_ref`, and `display_state`. The web tests consume the generated shape and verify presentation and command behavior; they do not redefine that schema.

## Route Map

```text
/login
/auth/callback
/t/:tenantId/work
/t/:tenantId/work/:actionRequestId
/t/:tenantId/projects
/t/:tenantId/projects/new
/t/:tenantId/admin/:section
/t/:tenantId/p/:projectId/overview
/t/:tenantId/p/:projectId/requirements
/t/:tenantId/p/:projectId/requirements/new
/t/:tenantId/p/:projectId/requirements/:requirementId
/t/:tenantId/p/:projectId/requirements/:requirementId/revisions/:revisionNo/:tab
/t/:tenantId/p/:projectId/requirements/:requirementId/compare
/t/:tenantId/p/:projectId/ready-pool
/t/:tenantId/p/:projectId/delivery/batches
/t/:tenantId/p/:projectId/delivery/batches/:batchId/:tab
/t/:tenantId/p/:projectId/delivery/batches/:batchId/candidates/:candidateId/acceptance-runs/:acceptanceRunId
/t/:tenantId/p/:projectId/context/:tab
/t/:tenantId/p/:projectId/attachments
/t/:tenantId/p/:projectId/attachments/:attachmentId/versions/:versionId
/t/:tenantId/p/:projectId/audit
/t/:tenantId/p/:projectId/settings/:section
```

### Task 1: Extend The Foundation React Workspace

**Files:**
- Modify: `package.json`
- Modify: `pnpm-workspace.yaml`
- Modify: `pnpm-lock.yaml`
- Verify: `tsconfig.base.json`
- Create: `eslint.config.mjs`
- Create: `tests/architecture/workspace.test.mjs`
- Modify: `apps/web/package.json`
- Create: `apps/web/tsconfig.json`
- Create: `apps/web/tsconfig.test.json`
- Create: `apps/web/vite.config.ts`
- Create: `apps/web/index.html`
- Create: `apps/web/src/main.tsx`
- Create: `apps/web/src/app/App.tsx`

The platform-foundation plan Task 1 is a hard prerequisite. It owns the repository bootstrap and creates every file marked `Modify` or `Verify` above with Node `22.22.1`, pnpm `10.12.4`, React `19.1.0`, TypeScript `5.8.3`, and Vite `7.0.2`. This task preserves those pins and extends that single workspace; it does not recreate manifests, introduce a second lockfile, or replace foundation scripts and dependencies unrelated to the web application.

- [ ] **Step 1: Write the failing extension contract test**

```js
// tests/architecture/workspace.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

test('web workspace extends the locked foundation toolchain', async () => {
  const root = JSON.parse(await readFile(new URL('../../package.json', import.meta.url)));
  const web = JSON.parse(await readFile(new URL('../../apps/web/package.json', import.meta.url)));
  const tsconfig = JSON.parse(await readFile(new URL('../../tsconfig.base.json', import.meta.url)));
  const workspace = await readFile(new URL('../../pnpm-workspace.yaml', import.meta.url), 'utf8');

  assert.equal(root.packageManager, 'pnpm@10.12.4');
  assert.equal(root.engines.node, '22.22.1');
  assert.equal(web.dependencies.react, '19.1.0');
  assert.match(web.dependencies['react-router'], /^7\./);
  assert.match(web.dependencies['@tanstack/react-query'], /^5\./);
  assert.equal(tsconfig.compilerOptions.strict, true);
  assert.equal(tsconfig.compilerOptions.noUncheckedIndexedAccess, true);
  assert.match(workspace, /^\s*- packages\/\*\s*$/m);
  assert.equal(root.scripts['build:web'], 'pnpm --filter @accord/web build');
  assert.equal(web.scripts.build, 'tsc -p tsconfig.json --noEmit --pretty false && vite build');
  assert.equal(web.scripts.typecheck, 'tsc -p tsconfig.json --noEmit --pretty false && tsc -p tsconfig.test.json --noEmit --pretty false');
});
```

- [ ] **Step 2: Run the extension test and verify the expected failure**

Run: `node --test tests/architecture/workspace.test.mjs`

Expected: foundation-owned files are present, and the test FAILS because `packages/*`, `build:web`, or the production web build script has not been added yet. An `ENOENT` means the platform-foundation prerequisite was not completed; stop and finish that plan instead of bootstrapping a parallel workspace here.

- [ ] **Step 3: Merge web commands, package globs, and boundary linting into the foundation root**

Keep the foundation-owned `name`, `private`, `packageManager`, `engines`, `check`, `contracts:lint`, Redocly, AJV, and YAML entries unchanged. Merge these exact keys into the existing root objects; do not replace either object wholesale:

```json
// package.json additions merged into the existing file
{
  "scripts": {
    "dev:web": "pnpm --filter @accord/web dev",
    "build:web": "pnpm --filter @accord/web build",
    "typecheck": "pnpm -r typecheck",
    "lint": "eslint . --max-warnings=0",
    "test": "pnpm -r test",
    "test:e2e": "pnpm --filter @accord/web test:e2e",
    "storybook": "pnpm --filter @accord/ui storybook",
    "api:generate": "pnpm --filter @accord/api-client generate",
    "contracts:generate": "pnpm --filter @accord/api-client generate",
    "verify": "pnpm lint && pnpm typecheck && pnpm test && pnpm build:web"
  },
  "devDependencies": {
    "@eslint/js": "9.30.0",
    "eslint": "9.30.0",
    "eslint-plugin-boundaries": "5.0.1",
    "typescript": "5.8.3",
    "typescript-eslint": "8.35.0"
  }
}
```

Extend the foundation workspace to include the packages introduced by later web tasks:

```yaml
# pnpm-workspace.yaml
packages:
  - apps/*
  - packages/*
```

Verify, without rewriting, that foundation-owned `tsconfig.base.json` retains `target: "ES2023"`, DOM libraries, bundler module resolution, `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`, `useUnknownInCatchVariables`, `verbatimModuleSyntax`, `isolatedModules`, and `skipLibCheck: false`. A mismatch is a foundation contract failure and must be corrected in that plan before this task continues.

Extend the existing foundation ESLint config with the following generated-code exclusion and import-boundary policy, preserving any foundation-wide rules already present:

```js
// eslint.config.mjs
import js from '@eslint/js';
import boundaries from 'eslint-plugin-boundaries';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import tseslint from 'typescript-eslint';

const tsconfigRootDir = path.dirname(fileURLToPath(import.meta.url));

export default tseslint.config(
  { ignores: ['**/dist/**', '**/coverage/**', 'packages/api-client/src/generated/**'] },
  js.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    extends: [...tseslint.configs.strictTypeChecked],
    languageOptions: { parserOptions: { projectService: true, tsconfigRootDir } }
  },
  {
    files: ['**/*.{js,mjs,cjs}'],
    extends: [tseslint.configs.disableTypeChecked]
  },
  {
    plugins: { boundaries },
    settings: {
      'boundaries/elements': [
        { type: 'app', pattern: 'apps/web/src/app/*' },
        { type: 'route', pattern: 'apps/web/src/routes/*' },
        { type: 'module', pattern: 'apps/web/src/modules/*' },
        { type: 'shared', pattern: 'apps/web/src/shared/*' },
        { type: 'package', pattern: 'packages/*/src/*' }
      ]
    },
    rules: {
      'boundaries/element-types': ['error', {
        default: 'disallow',
        rules: [
          { from: ['app', 'route'], allow: ['module', 'shared', 'package', 'app', 'route'] },
          { from: ['module'], allow: ['shared', 'package', 'module'] },
          { from: ['shared'], allow: ['shared', 'package'] },
          { from: ['package'], allow: ['package'] }
        ]
      }]
    }
  }
);
```

- [ ] **Step 4: Extend the foundation Vite entrypoint into a buildable application shell**

Preserve `name`, `private`, `version`, and `type` in the existing `apps/web/package.json`. Replace its bootstrap-only scripts with the exact scripts below, retain the foundation-pinned React packages, and merge the remaining exact dependencies without deleting unrelated foundation fields:

```json
// apps/web/package.json keys merged into the existing file
{
  "scripts": {
    "dev": "vite --host 127.0.0.1",
    "build": "tsc -p tsconfig.json --noEmit --pretty false && vite build",
    "typecheck": "tsc -p tsconfig.json --noEmit --pretty false && tsc -p tsconfig.test.json --noEmit --pretty false",
    "test": "vitest run",
    "test:e2e": "playwright test"
  },
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
    "@types/node": "22.15.32",
    "@vitejs/plugin-react": "4.5.2",
    "typescript": "5.8.3",
    "vite": "7.0.2",
    "vitest": "3.2.4"
  }
}
```

```json
// apps/web/tsconfig.json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "jsx": "react-jsx",
    "noEmit": true,
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"] },
    "types": ["vite/client"]
  },
  "include": ["src/**/*.ts", "src/**/*.tsx"],
  "exclude": [
    "src/**/*.test.ts", "src/**/*.test.tsx", "src/**/*.spec.ts", "src/**/*.spec.tsx",
    "src/testing/**", "tests/**"
  ]
}
```

```json
// apps/web/tsconfig.test.json
{
  "extends": "./tsconfig.json",
  "compilerOptions": {
    "noEmit": true,
    "types": ["node", "vite/client", "vitest/globals"]
  },
  "include": [
    "src/**/*.ts", "src/**/*.tsx", "tests/**/*.ts", "tests/**/*.tsx",
    "vite.config.ts", "vitest.config.ts", "playwright.config.ts"
  ],
  "exclude": []
}
```

```ts
// apps/web/vite.config.ts
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: { target: 'es2022', sourcemap: true },
  server: { port: 4173, strictPort: true },
  preview: { port: 4174, strictPort: true }
});
```

```html
<!-- apps/web/index.html -->
<!doctype html>
<html lang="zh-CN">
  <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>合契 Accord</title></head>
  <body><div id="root"></div><script type="module" src="/src/main.tsx"></script></body>
</html>
```

```tsx
// apps/web/src/app/App.tsx
export function App() {
  return <main><h1>易有料·合契</h1><p>Web workspace initialized.</p></main>;
}
```

```tsx
// apps/web/src/main.tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';

const root = document.getElementById('root');
if (!root) throw new Error('Missing #root mount point');
createRoot(root).render(<StrictMode><App /></StrictMode>);
```

- [ ] **Step 5: Update the single lockfile and verify the extended workspace**

Run: `corepack enable && pnpm install && node --test tests/architecture/workspace.test.mjs && pnpm typecheck && pnpm build:web`

Expected: workspace test PASS; `pnpm-lock.yaml` remains the only JavaScript lockfile and preserves the foundation toolchain pins; TypeScript exits 0; Vite reports `built in` with `apps/web/dist/index.html` present.

- [ ] **Step 6: Commit the foundation extension checkpoint**

```bash
git add package.json pnpm-lock.yaml pnpm-workspace.yaml eslint.config.mjs tests/architecture/workspace.test.mjs apps/web/package.json apps/web/tsconfig.json apps/web/tsconfig.test.json apps/web/vite.config.ts apps/web/index.html apps/web/src/main.tsx apps/web/src/app/App.tsx
git commit -m "chore(web): extend foundation React workspace"
```

### Task 2: Initialize And Guard The Cumulative OpenAPI Client

**Files:**
- Verify: `contracts/openapi/accord-control-api.yaml`
- Create: `packages/api-client/package.json`
- Create: `packages/api-client/tsconfig.json`
- Create: `packages/api-client/orval.config.ts`
- Create: `packages/api-client/src/runtime.ts`
- Generate: `packages/api-client/src/generated/**`
- Create: `packages/api-client/src/index.ts`
- Create: `packages/api-client/src/runtime.test.ts`
- Create: `tests/architecture/openapi-contract.test.mjs`
- Modify: `pnpm-lock.yaml`

- [ ] **Step 1: Write tests for the HTTP runtime and foundation OpenAPI baseline**

```ts
// packages/api-client/src/runtime.test.ts
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiProblemError, MissingRequestSecurityContextError, apiFetch, bindSessionCsrf, clearSessionSecurityContext } from './runtime';

afterEach(() => {
  clearSessionSecurityContext();
  vi.unstubAllGlobals();
});

describe('apiFetch', () => {
  it('preserves status, headers, quoted ETag, and parsed body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"items":[]}', {
      status: 200,
      headers: { 'Content-Type': 'application/json', ETag: '"7"', 'X-Correlation-ID': 'srv-1' }
    }));
    vi.stubGlobal('fetch', fetchMock);
    const result = await apiFetch<{ items: readonly unknown[] }>('/v1/projects');
    expect(fetchMock).toHaveBeenCalledWith('/v1/projects', expect.objectContaining({
      credentials: 'include'
    }));
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(request.headers).get('X-Correlation-ID')).toMatch(/^web-/);
    expect(result).toEqual(expect.objectContaining({ status: 200, etag: '"7"', body: { items: [] } }));
    expect(result.headers['x-correlation-id']).toBe('srv-1');
  });

  it('validates non-success bodies with the generated RFC7807 Problem schema', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      type: 'https://accord.example/problems/version-conflict', title: 'Version conflict', status: 409,
      code: 'VERSION_CONFLICT', correlation_id: 'srv-2'
    }), { status: 409, headers: { 'Content-Type': 'application/problem+json', ETag: '"8"' } })));
    const failure = apiFetch('/v1/projects');
    await expect(failure).rejects.toBeInstanceOf(ApiProblemError);
    await expect(failure).rejects.toMatchObject({
      status: 409, etag: '"8"', problem: { code: 'VERSION_CONFLICT', correlation_id: 'srv-2' }
    });
  });

  it('does not send an unsafe request without a trusted browser origin and session-bound CSRF token', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('location', { origin: 'https://accord.example' });
    await expect(apiFetch('/v1/projects/p1/commands', { method: 'POST' })).rejects.toBeInstanceOf(MissingRequestSecurityContextError);
    bindSessionCsrf({ sessionId: 'session-1', token: 'csrf-token-with-at-least-32-bytes-0001' });
    vi.stubGlobal('location', undefined);
    await expect(apiFetch('/v1/projects/p1/commands', { method: 'POST' })).rejects.toBeInstanceOf(MissingRequestSecurityContextError);
    expect(fetchMock).not.toHaveBeenCalled();
    vi.stubGlobal('location', { origin: 'https://accord.example' });
    fetchMock.mockResolvedValue(new Response('{}', { status: 200 }));
    await apiFetch('/v1/projects/p1/commands', { method: 'POST' });
    const mutationRequest = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(mutationRequest.headers).get('X-CSRF-Token')).toBe('csrf-token-with-at-least-32-bytes-0001');
  });
});
```

```js
// tests/architecture/openapi-contract.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { parse } from 'yaml';

test('canonical foundation OpenAPI is parseable and preserves command guards', async () => {
  const source = await readFile(new URL('../../contracts/openapi/accord-control-api.yaml', import.meta.url), 'utf8');
  const api = parse(source);
  assert.equal(api?.openapi, '3.1.0');
  assert.equal(api?.info?.title, 'Accord Control API');
  assert.equal(api?.components?.parameters?.IdempotencyKey?.required, true);
  assert.equal(api?.components?.parameters?.ExpectedVersion?.required, true);
  assert.deepEqual(
    api?.components?.schemas?.Problem?.required,
    ['type', 'title', 'status', 'code', 'correlation_id']
  );
});
```

- [ ] **Step 2: Verify the backend baseline passes and the client package is still absent**

Run: `node --test tests/architecture/openapi-contract.test.mjs && pnpm --filter @accord/api-client test`

Expected: the OpenAPI baseline test PASSES against the completed foundation plan; the package test FAILS because `@accord/api-client` is absent. If the baseline assertion fails, stop and repair the foundation contract instead of changing it from this web plan.

- [ ] **Step 3: Configure cumulative fetch and Zod generation**

```json
// packages/api-client/package.json
{
  "name": "@accord/api-client",
  "private": true,
  "type": "module",
  "exports": { ".": "./src/index.ts" },
  "scripts": {
    "generate": "orval --config orval.config.ts",
    "check:generated": "pnpm generate && git diff --exit-code -- src/generated",
    "typecheck": "tsc --noEmit --pretty false",
    "test": "vitest run"
  },
  "dependencies": { "zod": "3.25.67" },
  "devDependencies": { "orval": "7.10.0", "typescript": "5.8.3", "vitest": "3.2.4" }
}
```

```json
// packages/api-client/tsconfig.json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "noEmit": true,
    "types": ["vitest/globals"]
  },
  "include": ["src/**/*.ts", "orval.config.ts"]
}
```

```ts
// packages/api-client/orval.config.ts
import { defineConfig } from 'orval';

const input = '../../contracts/openapi/accord-control-api.yaml';

export default defineConfig({
  accordFetch: {
    input,
    output: {
      target: './src/generated/endpoints.ts',
      schemas: './src/generated/model',
      client: 'fetch',
      mode: 'tags-split',
      clean: true,
      prettier: true,
      override: {
        mutator: { path: './src/runtime.ts', name: 'apiFetch' },
        fetch: { includeHttpResponseReturnType: true }
      }
    }
  },
  accordSchemas: {
    input,
    output: {
      target: './src/generated/schemas.ts',
      client: 'zod',
      mode: 'single',
      clean: false,
      prettier: true
    }
  }
});
```

`packages/api-client/src/generated/**` is generator output only. `includeHttpResponseReturnType` is mandatory: every generated operation returns the typed body together with transport status, normalized headers, and the quoted response `ETag`; feature code never reconstructs an ETag from a body version. The handwritten surface is limited to `runtime.ts`, `index.ts`, and generator configuration. Backend plans extend the same YAML and rerun this generator; they never create a second client package or overwrite the runtime.

- [ ] **Step 4: Implement the only handwritten HTTP runtime**

```ts
// packages/api-client/src/runtime.ts
import type { Problem } from './generated/model';
import { problem as generatedProblemSchema } from './generated/schemas';

function correlationId(): string {
  return `web-${crypto.randomUUID()}`;
}

export interface ApiResponse<T> {
  readonly status: number;
  readonly headers: Readonly<Record<string, string>>;
  readonly etag: string | null;
  readonly body: T;
}

export class ApiProblemError extends Error {
  constructor(
    readonly status: number,
    readonly headers: Readonly<Record<string, string>>,
    readonly etag: string | null,
    readonly problem: Problem
  ) {
    super(`${problem.code} (${status})`);
    this.name = 'ApiProblemError';
  }
}

export class InvalidProblemResponseError extends Error {
  constructor(readonly status: number, readonly correlationId: string | null) {
    super(`Invalid RFC7807 response (${status})`);
    this.name = 'InvalidProblemResponseError';
  }
}

export class MissingRequestSecurityContextError extends Error {
  constructor(reason: 'origin' | 'csrf' | 'api_path') {
    super(`Unsafe request blocked: missing ${reason}`);
    this.name = 'MissingRequestSecurityContextError';
  }
}

let csrfContext: Readonly<{ sessionId: string; token: string }> | null = null;

export function bindSessionCsrf(context: Readonly<{ sessionId: string; token: string }>): void {
  if (!context.sessionId || context.token.length < 32) throw new MissingRequestSecurityContextError('csrf');
  csrfContext = Object.freeze({ ...context });
}

export function clearSessionSecurityContext(): void {
  csrfContext = null;
}

export function hasSessionSecurityContext(): boolean {
  return csrfContext !== null;
}

function isUnsafe(method: string | undefined): boolean {
  return !['GET', 'HEAD', 'OPTIONS'].includes((method ?? 'GET').toUpperCase());
}

function normalizedHeaders(headers: Headers): Readonly<Record<string, string>> {
  return Object.freeze(Object.fromEntries([...headers.entries()].map(([name, value]) => [name.toLowerCase(), value])));
}

export async function apiFetch<T>(url: string, init: RequestInit = {}): Promise<ApiResponse<T>> {
  if (!/^\/v1(?:\/|\?|$)/.test(url)) throw new MissingRequestSecurityContextError('api_path');
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json, application/problem+json');
  headers.set('X-Correlation-ID', correlationId());
  if (isUnsafe(init.method)) {
    const origin = globalThis.location?.origin;
    if (!origin || origin === 'null' || new URL(url, origin).origin !== origin) throw new MissingRequestSecurityContextError('origin');
    if (!csrfContext) throw new MissingRequestSecurityContextError('csrf');
    headers.set('X-CSRF-Token', csrfContext.token);
  }
  const response = await fetch(url, {
    ...init,
    credentials: 'include',
    headers
  });
  const responseHeaders = normalizedHeaders(response.headers);
  const etag = response.headers.get('ETag');
  if (!response.ok) {
    const unknownBody: unknown = await response.json().catch(() => null);
    const parsed = generatedProblemSchema.safeParse(unknownBody);
    if (!parsed.success) throw new InvalidProblemResponseError(response.status, response.headers.get('X-Correlation-ID'));
    if (response.status === 401) clearSessionSecurityContext();
    throw new ApiProblemError(response.status, responseHeaders, etag, parsed.data);
  }
  const body = response.status === 204 ? undefined as T : await response.json() as T;
  return Object.freeze({ status: response.status, headers: responseHeaders, etag, body });
}
```

The generated Zod export for component `Problem` is fixed as `problem` by the checked-in Orval output and asserted in `openapi-contract.test.mjs`; changing generator naming without updating the runtime fails typecheck. The runtime accepts only relative `/v1` URLs; absolute, proxy-prefixed, and non-versioned paths fail before `fetch`. Browsers supply the protected `Origin` header; code never attempts to forge it. Caller headers cannot replace the in-memory `X-CSRF-Token` because the runtime writes the bound token last. The context is tied to the generated session ID, is never placed in local/session storage, IndexedDB, TanStack Query, logs, RUM, or error bodies, and is cleared on 401, logout, tenant/session change, or failed session refresh.

```ts
// packages/api-client/src/index.ts
export * from './generated/endpoints';
export * from './generated/model';
export * from './generated/schemas';
export * from './runtime';
```

- [ ] **Step 5: Generate, test, typecheck, and prove deterministic output**

Run: `pnpm install && pnpm api:generate && pnpm --filter @accord/api-client test && pnpm --filter @accord/api-client typecheck && pnpm --filter @accord/api-client check:generated`

Expected: runtime tests PASS for response metadata, generated RFC7807 validation, CSRF injection, and local no-fetch behavior when origin or CSRF context is missing; generated functions expose `status`, normalized `headers`, quoted `etag`, and typed `body`; the `Problem` Zod export typechecks; final command exits 0 with no diff after the second generation. Each backend OpenAPI extension repeats this command before its web slice begins, so the directory accumulates operations without handwritten edits.

- [ ] **Step 6: Commit the generated-client checkpoint**

```bash
git add packages/api-client tests/architecture/openapi-contract.test.mjs pnpm-lock.yaml
git commit -m "feat(web): initialize cumulative OpenAPI client"
```

### Task 3: Enforce Generated Server Types And Validated External Payloads

**Files:**
- Modify: `eslint.config.mjs`
- Modify: `apps/web/package.json`
- Modify: `pnpm-lock.yaml`
- Create: `apps/web/src/shared/api/parse-external.ts`
- Create: `apps/web/src/shared/api/parse-external.test.ts`
- Create: `tests/architecture/server-type-ownership.test.mjs`

- [ ] **Step 1: Write failing runtime-boundary and ownership tests**

```ts
// apps/web/src/shared/api/parse-external.test.ts
import { expect, it } from 'vitest';
import { z } from 'zod';
import { ExternalPayloadError, parseExternal } from './parse-external';

const eventSchema = z.object({
  tenant_id: z.string().uuid(),
  sequence: z.number().int().nonnegative()
}).strict();

it('accepts only schema-validated external payloads without retaining the body in errors', () => {
  expect(parseExternal(eventSchema, {
    tenant_id: '10000000-0000-0000-0000-000000000001',
    sequence: 12
  }, 'project-event')).toEqual({
    tenant_id: '10000000-0000-0000-0000-000000000001',
    sequence: 12
  });

  expect(() => parseExternal(eventSchema, {
    tenant_id: 'not-a-uuid',
    sequence: 12,
    source_excerpt: 'secret'
  }, 'project-event')).toThrow(ExternalPayloadError);

  try {
    parseExternal(eventSchema, { source_excerpt: 'secret' }, 'project-event');
  } catch (error) {
    expect(JSON.stringify(error)).not.toContain('secret');
  }
});
```

```js
// tests/architecture/server-type-ownership.test.mjs
import assert from 'node:assert/strict';
import { access, readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import ts from 'typescript';
import { parse } from 'yaml';

async function sourceFiles(root) {
  try {
    const entries = await readdir(root, { withFileTypes: true });
    const nested = await Promise.all(entries.map((entry) => {
      const file = path.join(root, entry.name);
      if (entry.isDirectory()) return sourceFiles(file);
      return /\.(?:ts|tsx)$/.test(entry.name) ? [file] : [];
    }));
    return nested.flat();
  } catch (error) {
    if (error?.code === 'ENOENT') return [];
    throw error;
  }
}

function declaredObjectShapes(source, file) {
  const tree = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const shapes = [];
  const record = (node, name, members) => {
    const properties = members
      .filter(ts.isPropertySignature)
      .map((member) => member.name && (ts.isIdentifier(member.name) || ts.isStringLiteral(member.name)) ? member.name.text : null)
      .filter(Boolean);
    if (properties.length) shapes.push({ name, properties: new Set(properties), line: tree.getLineAndCharacterOfPosition(node.getStart()).line + 1 });
  };
  const visit = (node) => {
    if (ts.isInterfaceDeclaration(node)) record(node, node.name.text, node.members);
    if (ts.isTypeAliasDeclaration(node) && ts.isTypeLiteralNode(node.type)) record(node, node.name.text, node.type.members);
    ts.forEachChild(node, visit);
  };
  visit(tree);
  return shapes;
}

test('every server object shape is owned by generated api-client output', async () => {
  await assert.rejects(access('packages/contracts'), { code: 'ENOENT' });
  const contract = parse(await readFile('contracts/openapi/accord-control-api.yaml', 'utf8'));
  const serverShapes = Object.entries(contract.components.schemas)
    .map(([name, schema]) => ({ name, required: new Set(schema.required ?? []) }))
    .filter(({ required }) => required.size >= 3);
  const files = (await Promise.all([
    sourceFiles('apps/web/src'),
    sourceFiles('packages/ui/src'),
    sourceFiles('packages/testkit/src')
  ])).flat();
  for (const file of files) {
    const source = await readFile(file, 'utf8');
    assert.doesNotMatch(source, /from\s+['"]@accord\/contracts['"]/);
    assert.doesNotMatch(source, /import\s*\{[^}]*\bapiFetch\b[^}]*\}\s*from\s*['"]@accord\/api-client['"]/, `raw runtime import in ${file}`);
    for (const declaration of declaredObjectShapes(source, file)) {
      assert.doesNotMatch(declaration.name, /(?:Dto|Request|Response|Envelope|Projection|Problem|ObjectRef|AllowedAction|DisplayState)$/,
        `server-contract-like declaration ${declaration.name} in ${file}:${declaration.line}`);
      for (const server of serverShapes) {
        const copiesServerShape = [...server.required].every((property) => declaration.properties.has(property));
        assert.equal(copiesServerShape, false,
          `manual shape ${declaration.name} in ${file}:${declaration.line} contains required fields of OpenAPI ${server.name}`);
      }
    }
  }
});
```

- [ ] **Step 2: Run tests and verify the boundary adapter is absent**

Run: `pnpm --filter @accord/web test -- parse-external.test.ts && node --test tests/architecture/server-type-ownership.test.mjs`

Expected: the runtime test FAILS because `parse-external.ts` is absent; the ownership test confirms no manual server-contract package has been introduced.

- [ ] **Step 3: Implement a body-free Zod boundary error**

```ts
// apps/web/src/shared/api/parse-external.ts
import type { ZodIssue, ZodType } from 'zod';

export class ExternalPayloadError extends Error {
  readonly context: string;
  readonly issues: readonly Pick<ZodIssue, 'code' | 'path'>[];

  constructor(context: string, issues: readonly ZodIssue[]) {
    super(`Invalid external payload: ${context}`);
    this.name = 'ExternalPayloadError';
    this.context = context;
    this.issues = issues.map(({ code, path }) => ({ code, path }));
  }
}

export function parseExternal<T>(schema: ZodType<T>, input: unknown, context: string): T {
  const result = schema.safeParse(input);
  if (!result.success) throw new ExternalPayloadError(context, result.error.issues);
  return result.data;
}
```

No error, log, RUM event, or ActionRequest created from this adapter includes the rejected body. Call sites pass a generated Zod schema from `@accord/api-client`; handwritten production schemas for server payloads are forbidden.

- [ ] **Step 4: Add the runtime dependency and import guard**

Run: `pnpm --filter @accord/web add zod@3.25.67`

Merge this rule into the existing TypeScript block in `eslint.config.mjs`:

```js
'no-restricted-imports': ['error', {
  paths: [
    {
      name: '@accord/api-client',
      importNames: ['apiFetch'],
      message: 'Feature code must call operation-specific generated functions; apiFetch is generator-runtime-only.'
    }
  ],
  patterns: [
    {
      group: ['@accord/contracts', '@accord/api-client/src/generated/*', '**/packages/api-client/src/generated/*'],
      message: 'Import generated server types and schemas only through @accord/api-client.'
    }
  ]
}]
```

- [ ] **Step 5: Verify generated-only ownership and safe parsing**

Run: `pnpm --filter @accord/web test -- parse-external.test.ts && node --test tests/architecture/server-type-ownership.test.mjs && pnpm lint && pnpm typecheck`

Expected: both tests PASS; the AST ownership test compares every handwritten interface/type-literal declaration with every cumulative OpenAPI object's required property set and rejects a structural copy regardless of its name or module; lint rejects a manual contract package, deep generated import, or direct feature import of `apiFetch`; typechecking proves the runtime adapter accepts generated Zod schemas without casting.

- [ ] **Step 6: Commit the server-type ownership checkpoint**

```bash
git add apps/web/package.json apps/web/src/shared/api eslint.config.mjs tests/architecture/server-type-ownership.test.mjs pnpm-lock.yaml
git commit -m "chore(web): enforce generated server contract ownership"
```

### Task 4: Build Authenticated Tenant Routing And Cache Isolation

**Files:**
- Modify: `apps/web/package.json`
- Modify: `pnpm-lock.yaml`
- Modify: `package.json`
- Modify: `apps/web/src/main.tsx`
- Delete: `apps/web/src/app/App.tsx`
- Create: `apps/web/src/app/query-client.ts`
- Create: `apps/web/src/app/providers.tsx`
- Create: `apps/web/src/app/router.tsx`
- Create: `apps/web/src/app/tenant-session-boundary.tsx`
- Create: `apps/web/src/app/route-error.tsx`
- Create: `apps/web/vitest.config.ts`
- Create: `apps/web/src/testing/vitest.setup.ts`
- Create: `apps/web/src/routes/login-route.tsx`
- Create: `apps/web/src/routes/tenant-route.tsx`
- Create: `apps/web/src/routes/project-route.tsx`
- Create: `apps/web/src/app/tenant-session-boundary.test.tsx`

- [ ] **Step 1: Write the failing tenant-switch isolation test**

```tsx
// apps/web/src/app/tenant-session-boundary.test.tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, waitFor } from '@testing-library/react';
import { bindSessionCsrf, hasSessionSecurityContext } from '@accord/api-client';
import { describe, expect, it } from 'vitest';
import { TenantSessionBoundary } from './tenant-session-boundary';

describe('TenantSessionBoundary', () => {
  it('removes the previous tenant cache before showing the next tenant', async () => {
    const client = new QueryClient();
    bindSessionCsrf({ sessionId: 'session-a', token: 'tenant-a-csrf-token-with-32-bytes-0001' });
    client.setQueryData(['tenant', 'tenant-a', 'secret'], { value: 'a' });
    const view = render(
      <QueryClientProvider client={client}>
        <TenantSessionBoundary sessionTenantId="tenant-a"><span>A</span></TenantSessionBoundary>
      </QueryClientProvider>
    );
    view.rerender(
      <QueryClientProvider client={client}>
        <TenantSessionBoundary sessionTenantId="tenant-b"><span>B</span></TenantSessionBoundary>
      </QueryClientProvider>
    );
    await waitFor(() => expect(client.getQueryData(['tenant', 'tenant-a', 'secret'])).toBeUndefined());
    expect(client.getQueryCache().getAll()).toHaveLength(0);
    expect(client.getMutationCache().getAll()).toHaveLength(0);
    expect(hasSessionSecurityContext()).toBe(false);
  });
});
```

- [ ] **Step 2: Run the tenant test and verify it fails**

Run: `pnpm --filter @accord/web test -- tenant-session-boundary.test.tsx`

Expected: FAIL because the boundary and browser test environment are absent.

- [ ] **Step 3: Add browser test dependencies and a conservative query client**

Run: `pnpm --filter @accord/web add @accord/api-client@workspace:* && pnpm --filter @accord/web add -D @testing-library/react@16.3.2 @testing-library/jest-dom@7.0.0 @testing-library/user-event@14.6.1 jsdom@29.1.1 vitest-axe@0.1.0`

Create the browser-unit-test boundary before implementing any component:

```ts
// apps/web/vitest.config.ts
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/testing/vitest.setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['tests/**', 'dist/**', 'node_modules/**'],
    clearMocks: true,
    mockReset: true,
    restoreMocks: true,
    unstubGlobals: true,
    unstubEnvs: true,
  },
});
```

```ts
// apps/web/src/testing/vitest.setup.ts
import '@testing-library/jest-dom/vitest';
import 'vitest-axe/extend-expect';
```

The package's `vitest run` command must load this config automatically. Playwright files under `apps/web/tests/**` are excluded from Vitest collection, while the production `tsconfig.json` excludes both the setup file and all tests. `pnpm typecheck` checks them through `tsconfig.test.json`.

```ts
// apps/web/src/app/query-client.ts
import { QueryClient } from '@tanstack/react-query';

export function createAccordQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        gcTime: 5 * 60_000,
        retry: (count, error) => {
          const status = (error as { status?: number }).status;
          return status !== 401 && status !== 403 && status !== 409 && count < 2;
        }
      },
      mutations: { retry: false }
    }
  });
}
```

- [ ] **Step 4: Implement tenant teardown and route providers**

```tsx
// apps/web/src/app/tenant-session-boundary.tsx
import { useQueryClient } from '@tanstack/react-query';
import {
  bindSessionCsrf, clearSessionSecurityContext, consumeTenantSwitchIntent,
  createTenantSwitchIntent, currentSessionEnvelope as currentSessionEnvelopeSchema,
  getCurrentSession, listCurrentSessionProjects, revokeCurrentSession,
  type CurrentSessionEnvelope
} from '@accord/api-client';
import { type ReactNode, useLayoutEffect, useReducer, useRef } from 'react';
import { parseExternal } from '../shared/api/parse-external';

export const sessionQueries = Object.freeze({ getCurrentSession, listCurrentSessionProjects });
export const sessionMutations = Object.freeze({
  createTenantSwitchIntent, consumeTenantSwitchIntent, revokeCurrentSession
});

function bindCurrentSession(session: CurrentSessionEnvelope['session']) {
  clearSessionSecurityContext();
  if (session.authentication_mechanism === 'browser_session') {
    if (!session.session_id || !session.csrf_token || Date.parse(session.expires_at) <= Date.now()) {
      throw new Error('Browser session security material is unavailable');
    }
    bindSessionCsrf({ sessionId: session.session_id, token: session.csrf_token });
  }
  return Object.freeze({
    sessionId: session.session_id,
    account: session.account,
    currentTenantId: session.current_tenant_id,
    currentTenantName: session.current_tenant_name,
    tenantChoices: session.tenant_choices,
    authenticatedAt: session.authenticated_at,
    expiresAt: session.expires_at
  });
}

function requireSessionEtag(etag: string | null): string {
  if (!etag || !/^"[0-9]+"$/.test(etag)) throw new Error('Current session ETag is unavailable');
  return etag;
}

export async function loadSessionAuthority() {
  const response = await sessionQueries.getCurrentSession();
  const envelope = parseExternal(currentSessionEnvelopeSchema, response.body, 'current-session');
  return {
    authority: bindCurrentSession(envelope.session), objectRef: envelope.object_ref,
    displayState: envelope.display_state, allowedActions: envelope.allowed_actions,
    version: envelope.version, etag: requireSessionEtag(response.etag)
  } as const;
}

export interface TenantSessionBoundaryProps { sessionTenantId: string; children: ReactNode; }

export function TenantSessionBoundary({ sessionTenantId, children }: TenantSessionBoundaryProps) {
  const queryClient = useQueryClient();
  const previous = useRef(sessionTenantId);
  const [, commitTeardown] = useReducer((value) => value + 1, 0);
  const changing = previous.current !== sessionTenantId;

  useLayoutEffect(() => {
    if (previous.current !== sessionTenantId) {
      const previousId = previous.current;
      clearSessionSecurityContext();
      queryClient.clear();
      window.dispatchEvent(new CustomEvent('accord:tenant-changed', { detail: { from: previousId, to: sessionTenantId } }));
      previous.current = sessionTenantId;
      commitTeardown();
    }
  }, [queryClient, sessionTenantId]);

  return changing ? <main aria-busy="true">正在切换工作区</main> : children;
}
```

```tsx
// apps/web/src/app/providers.tsx
import { QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, useState } from 'react';
import { createAccordQueryClient } from './query-client';

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createAccordQueryClient);
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
```

- [ ] **Step 5: Create the typed data-router skeleton**

```tsx
// apps/web/src/app/router.tsx
import { Navigate, Outlet, createBrowserRouter, redirect, useLoaderData, type LoaderFunctionArgs } from 'react-router';
import { loadSessionAuthority, TenantSessionBoundary } from './tenant-session-boundary';
import { RouteError } from './route-error';

export async function tenantLoader({ params }: LoaderFunctionArgs) {
  const session = await loadSessionAuthority();
  if (params.tenantId !== session.authority.currentTenantId) {
    throw redirect(`/t/${encodeURIComponent(session.authority.currentTenantId)}/work`);
  }
  return { sessionTenantId: session.authority.currentTenantId } as const;
}

function TenantLayout() {
  const { sessionTenantId } = useLoaderData<typeof tenantLoader>();
  return <TenantSessionBoundary sessionTenantId={sessionTenantId}><Outlet /></TenantSessionBoundary>;
}

export const router = createBrowserRouter([
  { path: '/login', lazy: () => import('../routes/login-route'), errorElement: <RouteError /> },
  {
    path: '/t/:tenantId', loader: tenantLoader, element: <TenantLayout />, errorElement: <RouteError />, children: [
      { index: true, element: <Navigate to="work" replace /> },
      { path: 'work', lazy: () => import('../routes/tenant-route') },
      { path: 'work/:actionRequestId', lazy: () => import('../routes/tenant-route') },
      { path: 'projects', lazy: () => import('../routes/tenant-route') },
      { path: 'projects/new', lazy: () => import('../routes/tenant-route') },
      { path: 'admin/:section', lazy: () => import('../routes/tenant-route') },
      { path: 'p/:projectId/*', lazy: () => import('../routes/project-route') }
    ]
  },
  { path: '*', element: <Navigate to="/login" replace /> }
]);
```

```tsx
// apps/web/src/app/route-error.tsx
import { isRouteErrorResponse, useRouteError } from 'react-router';
export function RouteError() {
  const error = useRouteError();
  const message = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : '页面加载失败';
  return <main role="alert"><h1>无法打开此页面</h1><p>{message}</p><button onClick={() => location.reload()}>重新加载</button></main>;
}
```

```tsx
// apps/web/src/main.tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';
import { AppProviders } from './app/providers';
import { router } from './app/router';
const root = document.getElementById('root');
if (!root) throw new Error('Missing #root mount point');
createRoot(root).render(<StrictMode><AppProviders><RouterProvider router={router} /></AppProviders></StrictMode>);
```

The three thin route modules export React Router `Component` functions and render an `<Outlet />` or a stable heading until their feature tasks replace them. Authentication uses only the exact two-query/three-mutation `sessionQueries`/`sessionMutations` registries. `getCurrentSession` is called directly outside TanStack Query, and the generated envelope is validated before reading `response.body.session.session_id`, `csrf_token`, `expires_at`, and `current_tenant_id`. Its `200` is `no-store` but still carries the Identity-owned quoted numeric ETag for session commands; conditional GET/304 is forbidden. `loadSessionAuthority` fails closed when that ETag is absent or malformed, so `createTenantSwitchIntent` and `revokeCurrentSession` never reconstruct `If-Match` from the body version. Only `loadSessionAuthority` may see the token; it binds browser-session CSRF in memory and returns a deliberately token-free loader value. Redirect 401 to `/login?returnTo=<encoded current path>` only when the normalized return path starts with `/t/`. Missing, null, malformed, or expired cookie-session security material keeps the application in a read-only session-error boundary and no mutation reaches `fetch`. OIDC responses may legitimately have null browser session/CSRF fields and never synthesize them.

The route `tenantId` is a display/navigation echo of `current_tenant_id`, never request authority. A mismatch redirects to the current session workspace and does not switch tenants. Project navigation calls `listCurrentSessionProjects` without tenant input. The tenant selector renders `tenant_choices`, but submits only the chosen opaque `tenant_ref` to `createTenantSwitchIntent`; it never parses a tenant ID from that reference. After the returned intent's exact action obtains Task 16 FreshAuth, `consumeTenantSwitchIntent` receives the intent ID, numeric expected version, quoted intent ETag, stable idempotency key, and proof. Success first clears security context and the entire Query/mutation cache, then performs a full-document navigation to the new response's `current_tenant_id`; the fresh cookie is revalidated by a new `getCurrentSession` call before rendering. The rotation envelope, token, opaque references, and capability-like bindings are never stored, logged, serialized, or placed in Query cache.

`csrf_token` is excluded from Query data, component props, loader serialization, browser persistence, diagnostics, and telemetry. Logout calls only `revokeCurrentSession`, then clears security/cache before full-document navigation. A 401, failed refresh, session-ID change, tenant rotation, and `TenantSessionBoundary` teardown do the same. Runtime tests prove caller-supplied CSRF headers are overwritten; route tests prove raw route IDs never enter generated calls, the opaque reference is preserved byte-for-byte, and a mutation cannot reach `fetch` until the newly validated session projection is bound. The backend remains authoritative for tenant/account/session binding, Origin/Fetch Metadata, expiry, and replay rejection.

The session coordinator schedules a no-store `getCurrentSession` refresh before `expires_at`, rechecks expiry immediately on `visibilitychange`/online recovery, and clears mutation authority before any delayed refresh begins. Timer throttling therefore cannot leave an expired token locally usable: a foreground transition compares `Date.now()` to the last generated `expires_at` before re-enabling commands. Refresh failure stays read-only with retry/login actions; it never continues on cached identity.

- [ ] **Step 6: Run route tests and production build**

Run: `pnpm --filter @accord/web test -- tenant-session-boundary.test.tsx && pnpm --filter @accord/web typecheck && pnpm build:web`

Expected: tenant test PASS with exact `getCurrentSession`, `listCurrentSessionProjects`, `createTenantSwitchIntent`, `consumeTenantSwitchIntent`, and `revokeCurrentSession` registration; both Query and mutation caches plus in-memory CSRF are gone before tenant B renders; `getCurrentSession` reads the nested generated `session` object and returns no token; route IDs are never API authority; switching sends only an opaque `tenant_ref`; missing Origin/CSRF does not send an unsafe request; rotation binds only the newly re-fetched session token; every declared route typechecks; production build exits 0. Source scans find no obsolete session alias, handwritten API URL/DTO, tenant-routed API, actor input, dynamic href, or persisted session secret.

- [ ] **Step 7: Commit the routing checkpoint**

```bash
git add apps/web package.json pnpm-lock.yaml
git commit -m "feat(web): add tenant-isolated application routing"
```

### Task 5: Create The Operational Design System And App Shell

**Files:**
- Create: `packages/ui/package.json`
- Create: `packages/ui/tsconfig.json`
- Create: `packages/ui/.storybook/main.ts`
- Create: `packages/ui/.storybook/preview.ts`
- Create: `packages/ui/src/theme.css.ts`
- Create: `packages/ui/src/stage-banner.tsx`
- Create: `packages/ui/src/icon-button.tsx`
- Create: `packages/ui/src/index.ts`
- Create: `packages/ui/src/stage-banner.test.tsx`
- Create: `packages/ui/src/stage-banner.stories.tsx`
- Create: `apps/web/src/app/app-shell.tsx`
- Create: `apps/web/src/app/app-shell.test.tsx`
- Modify: `apps/web/package.json`
- Modify: `pnpm-lock.yaml`

- [ ] **Step 1: Write failing accessibility tests for status and icon controls**

```tsx
// packages/ui/src/stage-banner.test.tsx
import { render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { expect, it } from 'vitest';
import { StageBanner } from './stage-banner';

it('announces stage, next action, and blockers without relying on color', async () => {
  const view = render(<StageBanner stage="已暂停" originalStage="开发中" nextAction="开发负责人恢复画像" blockers={['项目代码画像已过期']} />);
  expect(screen.getByRole('status')).toHaveTextContent('已暂停');
  expect(screen.getByRole('status')).toHaveTextContent('项目代码画像已过期');
  expect(await axe(view.container)).toHaveNoViolations();
});
```

- [ ] **Step 2: Run the UI test and verify it fails**

Run: `pnpm --filter @accord/ui test -- stage-banner.test.tsx`

Expected: FAIL because the UI package and component are absent.

- [ ] **Step 3: Create tokens and accessible primitives**

Create a resolvable workspace manifest before invoking any filtered command:

```json
// packages/ui/package.json
{
  "name": "@accord/ui",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "sideEffects": ["./src/theme.css.ts"],
  "exports": { ".": "./src/index.ts" },
  "scripts": {
    "test": "vitest run",
    "typecheck": "tsc --noEmit",
    "storybook": "storybook dev --host 127.0.0.1 --port 6006",
    "build-storybook": "storybook build"
  }
}
```

```json
// packages/ui/tsconfig.json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "noEmit": true,
    "types": ["vitest/globals"]
  },
  "include": ["src/**/*.ts", "src/**/*.tsx", ".storybook/**/*.ts"]
}
```

```ts
// packages/ui/src/theme.css.ts
import { createGlobalTheme, globalStyle } from '@vanilla-extract/css';

export const vars = createGlobalTheme(':root', {
  color: {
    canvas: '#f5f6f4', surface: '#ffffff', ink: '#18201d', muted: '#5f6b66',
    border: '#ccd3cf', action: '#0b6b57', warning: '#9a5a00', danger: '#b42318', focus: '#1769aa'
  },
  space: { xs: '4px', sm: '8px', md: '12px', lg: '16px', xl: '24px' },
  radius: { control: '4px', panel: '6px' },
  font: { body: '"Noto Sans SC", "Source Han Sans SC", sans-serif', mono: '"IBM Plex Mono", monospace' }
});
globalStyle('body', { margin: 0, color: vars.color.ink, background: vars.color.canvas, fontFamily: vars.font.body });
globalStyle(':focus-visible', { outline: `3px solid ${vars.color.focus}`, outlineOffset: '2px' });
globalStyle('*', { '@media': { '(prefers-reduced-motion: reduce)': { animationDuration: '0.01ms', animationIterationCount: '1', transitionDuration: '0.01ms', scrollBehavior: 'auto' } } });
```

```tsx
// packages/ui/src/stage-banner.tsx
export interface StageBannerProps {
  stage: string; originalStage?: string | null; nextAction?: string | null; blockers: readonly string[];
}
export function StageBanner({ stage, originalStage, nextAction, blockers }: StageBannerProps) {
  return <section role="status" aria-label="当前流程状态">
    <strong>{stage}</strong>{originalStage ? <span>原阶段：{originalStage}</span> : null}
    {nextAction ? <span>下一步：{nextAction}</span> : null}
    {blockers.length ? <ul aria-label="阻塞原因">{blockers.map((item) => <li key={item}>{item}</li>)}</ul> : null}
  </section>;
}
```

`IconButton` wraps React Aria `Button`, requires an `aria-label`, renders a Lucide icon, and supplies a tooltip on hover/focus. The public package exports tokens, `StageBanner`, `IconButton`, `DataTable`, `EmptyState`, `ErrorState`, `Disclosure`, and `ConfirmBar`; each primitive has a Storybook story for default, focus, disabled, loading, error, and high-contrast states.

- [ ] **Step 4: Implement the app shell around route content**

```tsx
// apps/web/src/app/app-shell.tsx
import { NavLink, Outlet } from 'react-router';
import { StageBanner } from '@accord/ui';

export function AppShell({ tenantId, projectId }: { tenantId: string; projectId?: string }) {
  const base = `/t/${tenantId}`;
  return <div className="app-shell">
    <a className="skip-link" href="#main-content">跳到主要内容</a>
    <header><strong>易有料·合契</strong><span aria-label="当前租户">{tenantId}</span></header>
    <nav aria-label="主导航">
      <NavLink to={`${base}/work`}>待我处理</NavLink>
      <NavLink to={`${base}/projects`}>项目</NavLink>
      {projectId ? <NavLink to={`${base}/p/${projectId}/requirements`}>需求</NavLink> : null}
      {projectId ? <NavLink to={`${base}/p/${projectId}/delivery/batches`}>交付</NavLink> : null}
    </nav>
    <main id="main-content" tabIndex={-1}><Outlet /></main>
    <aside aria-label="系统状态"><StageBanner stage="运行正常" blockers={[]} /></aside>
  </div>;
}
```

- [ ] **Step 5: Configure Storybook and run component checks**

```ts
// packages/ui/.storybook/main.ts
import type { StorybookConfig } from '@storybook/react-vite';
import { vanillaExtractPlugin } from '@vanilla-extract/vite-plugin';

const config: StorybookConfig = {
  stories: ['../src/**/*.stories.@(ts|tsx)'],
  addons: ['@storybook/addon-a11y'],
  framework: { name: '@storybook/react-vite', options: {} },
  viteFinal: async (viteConfig) => ({
    ...viteConfig,
    plugins: [...(viteConfig.plugins ?? []), vanillaExtractPlugin()]
  })
};
export default config;
```

```ts
// packages/ui/.storybook/preview.ts
import type { Preview } from '@storybook/react-vite';
import '../src/theme.css';

export default {
  parameters: { a11y: { test: 'error' }, controls: { expanded: true } }
} satisfies Preview;
```

Run: `pnpm --filter @accord/ui add react@19.1.0 react-dom@19.1.0 react-aria-components@1.19.0 lucide-react@1.26.0 @vanilla-extract/css@1.21.1 && pnpm --filter @accord/ui add -D storybook@10.5.4 @storybook/react-vite@10.5.4 @storybook/addon-a11y@10.5.4 @vanilla-extract/vite-plugin@5.2.5 @testing-library/react@16.3.2 vitest@3.2.4 vitest-axe@0.1.0 jsdom@29.1.1 typescript@5.8.3 @types/react@19.1.8 @types/react-dom@19.1.6 && pnpm --filter @accord/web add @accord/ui@workspace:* && pnpm --filter @accord/ui test && pnpm --filter @accord/ui storybook -- --smoke-test`

Expected: component tests PASS, axe reports zero serious/critical violations, and Storybook smoke test exits 0.

- [ ] **Step 6: Commit the design-system checkpoint**

```bash
git add packages/ui apps/web/src/app apps/web/package.json pnpm-lock.yaml
git commit -m "feat(web): add operational design system and app shell"
```

### Task 6: Implement Ordered SSE Updates And Sequence Recovery

**Files:**
- Create: `apps/web/src/shared/realtime/ordered-event-stream.ts`
- Create: `apps/web/src/shared/realtime/use-project-events.ts`
- Create: `apps/web/src/shared/realtime/ordered-event-stream.test.ts`
- Test: `apps/web/src/shared/realtime/use-project-events.test.tsx`

- [ ] **Step 1: Write failing tests for duplicates and sequence gaps**

```ts
// apps/web/src/shared/realtime/ordered-event-stream.test.ts
import { describe, expect, it, vi } from 'vitest';
import { OrderedEventReducer } from './ordered-event-stream';

describe('OrderedEventReducer', () => {
  it('ignores duplicates and requests recovery on a gap', () => {
    const recover = vi.fn();
    const invalidate = vi.fn();
    const reducer = new OrderedEventReducer(10, invalidate, recover);
    reducer.accept({ sequence: 11, query_keys: [['tenant', 't1', 'actions']] });
    reducer.accept({ sequence: 11, query_keys: [['tenant', 't1', 'actions']] });
    reducer.accept({ sequence: 13, query_keys: [['tenant', 't1', 'actions']] });
    expect(invalidate).toHaveBeenCalledTimes(1);
    expect(recover).toHaveBeenCalledWith({ after: 11, observed: 13 });
  });
});
```

- [ ] **Step 2: Run the SSE test and verify it fails**

Run: `pnpm --filter @accord/web test -- ordered-event-stream.test.ts`

Expected: FAIL because `OrderedEventReducer` does not exist.

- [ ] **Step 3: Implement sequence enforcement**

```ts
// apps/web/src/shared/realtime/ordered-event-stream.ts
import type { OrderedServerEvent } from '@accord/api-client';

type QueryInvalidationEvent = Pick<OrderedServerEvent, 'sequence' | 'query_keys'>;
export class OrderedEventReducer {
  constructor(
    private lastSequence: number,
    private readonly invalidate: (keys: readonly (readonly unknown[])[]) => void,
    private readonly recover: (gap: { after: number; observed: number }) => void
  ) {}
  accept(event: QueryInvalidationEvent): void {
    if (event.sequence <= this.lastSequence) return;
    if (event.sequence !== this.lastSequence + 1) {
      this.recover({ after: this.lastSequence, observed: event.sequence });
      return;
    }
    this.lastSequence = event.sequence;
    this.invalidate(event.query_keys);
  }
  current(): number { return this.lastSequence; }
}
```

- [ ] **Step 4: Connect same-origin SSE to TanStack Query**

Use native `EventSource` for the streaming transport, but obtain its URL only from the generated path builder. Replay remains a normal generated query operation. No realtime module may import `apiFetch`, concatenate an API path, add a tenant/actor parameter, or use the generated fetch operation to buffer an infinite SSE response.

```ts
// apps/web/src/shared/realtime/use-project-events.ts
import {
  getStreamProjectEventsUrl,
  replayProjectEvents,
  streamProjectEvents,
  orderedServerEvent as orderedServerEventSchema,
  projectEventReplay as projectEventReplaySchema
} from '@accord/api-client';
import { parseExternal } from '../api/parse-external';

export const projectEventOperations = Object.freeze({ streamProjectEvents, replayProjectEvents });

export function projectEventStreamUrl(projectId: string, after: number): string {
  return getStreamProjectEventsUrl(projectId, { after, heartbeat_seconds: 30 });
}

export function openProjectEventStream(projectId: string, after: number): EventSource {
  return new EventSource(projectEventStreamUrl(projectId, after), { withCredentials: true });
}

export async function replayGap(projectId: string, after: number) {
  const pages = [];
  let cursor = after;
  for (;;) {
    const response = await projectEventOperations.replayProjectEvents(projectId, { after: cursor, limit: 500 });
    const page = parseExternal(projectEventReplaySchema, response.body, 'project-event-replay');
    pages.push(page);
    if (!page.has_more || page.next_after === null) return pages;
    if (page.next_after <= cursor) throw new Error('Replay cursor did not advance');
    cursor = page.next_after;
  }
}
```

`useProjectEvents` permits exactly one live stream per authenticated project. Tenant and actor remain server-derived from the verified session; `tenantId` only partitions browser cache. Each message is parsed with the generated `orderedServerEventSchema`, then rejected unless its tenant/project match the mounted route, its sequence is the next integer, and every `query_keys` entry starts with `['tenant', tenantId]`. Replay parses the generated `ProjectEventReplay` shape (`project_id`, `after`, `head_sequence`, `events`, `has_more`, `next_after`), verifies page continuity and event ordering, and feeds events through the same reducer. It never patches authoritative cached objects directly.

On a sequence gap, schema failure, `accord:tenant-changed`, unmount, or a tab hidden for more than 60 seconds, close the stream before any recovery. A 410 `REPLAY_WINDOW_EXPIRED` invalidates all `['tenant', tenantId]` queries, waits for their authoritative envelope sequences, and opens a new generated stream URL from the minimum common snapshot; it never guesses the server head. Network reconnect uses one in-flight timer with full-jitter exponential delay capped at 30 seconds, resets after the first valid event, and is cancelled on teardown. Native `EventSource` owns cookies only; secrets, CSRF tokens, and identity headers never enter its URL.

- [ ] **Step 5: Verify duplicate, gap, reconnect, and tenant teardown tests**

Run: `pnpm --filter @accord/web test -- ordered-event-stream.test.ts use-project-events.test.tsx`

Expected: PASS with the exact `streamProjectEvents`/`replayProjectEvents` registry; native `EventSource` uses the generated stream path builder while the generated fetch-style stream function is never invoked; assertions cover one invalidation on duplicate delivery, generated replay paging on a gap, full tenant invalidation on HTTP 410, one bounded reconnect timer, and `EventSource.close()` on tenant change. A source scan finds no handwritten versioned API path literal in `shared/realtime`.

- [ ] **Step 6: Commit the realtime checkpoint**

```bash
git add apps/web/src/shared/realtime
git commit -m "feat(web): recover ordered realtime projections"
```

### Task 7: Deliver The Global ActionRequest Workbench

**Files:**
- Create: `apps/web/src/modules/actions/api.ts`
- Create: `apps/web/src/modules/actions/action-list.tsx`
- Create: `apps/web/src/modules/actions/action-detail.tsx`
- Create: `apps/web/src/modules/actions/action-command.tsx`
- Create: `apps/web/src/modules/actions/index.ts`
- Create: `apps/web/src/modules/actions/action-detail.test.tsx`
- Test: `apps/web/src/modules/actions/action-list.test.tsx`
- Test: `apps/web/src/modules/actions/action-command.test.ts`
- Create: `apps/web/src/routes/work-route.tsx`
- Create: `packages/testkit/package.json`
- Create: `packages/testkit/tsconfig.json`
- Create: `packages/testkit/src/index.ts`
- Create: `packages/testkit/src/fixtures/index.ts`
- Create: `packages/testkit/src/fixtures/actions.ts`
- Modify: `apps/web/package.json`
- Modify: `pnpm-lock.yaml`

- [ ] **Step 1: Write a failing test for one authoritative primary action**

```tsx
// apps/web/src/modules/actions/action-detail.test.tsx
import { render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { ActionDetail } from './action-detail';

it('renders one primary command only when the server allows it', () => {
  render(<ActionDetail envelope={{
    data: { id: 'ar-1', kind: 'business_confirmation', title: '确认库存释放', project_label: '库存', reason: '开发已确认', gate_label: '解除业务确认门禁', due_at: null, risk: 'high', assignee_label: '王琳', waiting_on_current_user: true, target: { type: 'requirement_revision', id: 'rev-9', version: 9, hash: 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }, blocker_code: null, decision_options: ['confirm'], assigned_role: 'business_principal', assigned_account_id: null, underlying_action_key: 'business_confirmation' },
    object_ref: { type: 'action_request', id: 'ar-1', version: 2, hash: null }, sequence: 9,
    display_state: { stage_label: '待业务确认', next_action_label: '确认版本', blocker_labels: [], original_stage_label: null },
    allowed_actions: [{ key: 'business_confirmation', operation_id: 'completeActionRequest', command_href: '/commands/confirm', method: 'POST', expected_version: 2, expected_hash: 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', requires_idempotency_key: true, fresh_auth: 'window', enabled: true, disabled_reason_code: null }]
  }} onCommand={vi.fn()} />);
  expect(screen.getAllByRole('button', { name: '确认版本' })).toHaveLength(1);
  expect(screen.queryByRole('button', { name: '开发确认' })).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run the action test and verify it fails**

Run: `pnpm --filter @accord/web test -- action-detail.test.tsx`

Expected: FAIL because the action module is absent.

- [ ] **Step 3: Implement query options and the master/detail queue**

Create the testkit package before any later task imports it:

```json
// packages/testkit/package.json
{
  "name": "@accord/testkit",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "exports": { ".": "./src/index.ts" },
  "scripts": { "typecheck": "tsc --noEmit" },
  "dependencies": { "@accord/api-client": "workspace:*" },
  "devDependencies": { "@types/node": "24.0.3", "typescript": "5.8.3" }
}
```

```json
// packages/testkit/tsconfig.json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "noEmit": true,
    "types": ["node"]
  },
  "include": ["src/**/*.ts"]
}
```

```ts
// packages/testkit/src/fixtures/actions.ts
import type { ActionRequestEnvelope } from '@accord/api-client';

export const actionRequestFixture = {
  data: { id: 'ar-1', kind: 'business_confirmation', title: '确认库存释放', project_label: '库存', reason: '开发已确认', gate_label: '解除业务确认门禁', due_at: null, risk: 'high', assignee_label: '王琳', waiting_on_current_user: true, target: { type: 'requirement_revision', id: 'rev-9', version: 9, hash: 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }, blocker_code: null, decision_options: ['confirm'], assigned_role: 'business_principal', assigned_account_id: null, underlying_action_key: 'business_confirmation' },
  object_ref: { type: 'action_request', id: 'ar-1', version: 2, hash: null },
  sequence: 9,
  display_state: { stage_label: '待业务确认', next_action_label: '确认版本', blocker_labels: [], original_stage_label: null },
  allowed_actions: []
} as const satisfies ActionRequestEnvelope;

// packages/testkit/src/fixtures/index.ts
export * from './actions';

// packages/testkit/src/index.ts
export * from './fixtures/index';
```

Run: `pnpm --filter @accord/web add -D @accord/testkit@workspace:*`

```ts
// apps/web/src/modules/actions/api.ts
import { queryOptions } from '@tanstack/react-query';
import {
  completeActionRequest, declineActionRequest, delegateActionRequest,
  getActionRequest, listActionRequests
} from '@accord/api-client';
export const actionQueries = Object.freeze({ listActionRequests, getActionRequest });
export const actionMutations = Object.freeze({ completeActionRequest, declineActionRequest, delegateActionRequest });
export const actionKeys = {
  all: (tenantId: string) => ['tenant', tenantId, 'actions'] as const,
  detail: (tenantId: string, id: string) => ['tenant', tenantId, 'actions', id] as const
};
export const actionListOptions = (tenantId: string, scope: 'mine' | 'waiting' | 'team') => queryOptions({
  queryKey: [...actionKeys.all(tenantId), scope],
  queryFn: async () => (await actionQueries.listActionRequests({ scope, limit: 50 })).body
});
export const actionDetailOptions = (tenantId: string, actionRequestId: string) => queryOptions({
  queryKey: actionKeys.detail(tenantId, actionRequestId),
  queryFn: () => actionQueries.getActionRequest(actionRequestId)
});
```

`ActionRequestPage.items` is a list of complete generated `ActionRequestEnvelope` values, not a list of bare `ActionRequest` data. The list reads row content from `item.data`, command/version state from that same item's `object_ref` and `allowed_actions`, and never combines a page-level action with a row. It is a virtualized table sorted by server rank, with text columns for risk, due time, project, stage, and assignee. Tabs are `待我处理`, `等待他人`, and role-gated `团队队列`; only the first contributes to the AppShell badge. Selecting a row updates `/t/:tenantId/work/:actionRequestId` and opens the exact-version detail beside the list.

- [ ] **Step 4: Implement pessimistic commands with exact preconditions**

```tsx
// apps/web/src/modules/actions/action-command.tsx
import {
  type AllowedAction,
  type ApiResponse,
  type CompleteActionRequestRequest,
  type DeclineActionRequestRequest,
  type DelegateActionRequestRequest
} from '@accord/api-client';
import { actionMutations } from './api';

function headersFor(
  response: ApiResponse<unknown>,
  action: AllowedAction,
  idempotencyKey: string,
  freshAuthProof?: string
) {
  if (action.fresh_auth !== 'none' && !freshAuthProof) throw new Error('Fresh authentication is required');
  if (!response.etag || !/^"[0-9]+"$/.test(response.etag)) throw new Error('Missing ActionRequest ETag');
  return {
    'Idempotency-Key': idempotencyKey,
    'If-Match': response.etag,
    ...(freshAuthProof ? { 'X-Accord-Fresh-Auth': freshAuthProof } : {})
  } as const;
}

function requireVersion(action: AllowedAction, expectedVersion: number): void {
  if (!Number.isSafeInteger(expectedVersion) || expectedVersion !== action.expected_version) {
    throw new Error('Allowed action version does not match generated request');
  }
}

export const actionOperationRegistry = {
  completeActionRequest: (id: string, response: ApiResponse<unknown>, action: AllowedAction, request: CompleteActionRequestRequest, idempotencyKey: string, fresh?: string) => {
    requireVersion(action, request.expected_version);
    return actionMutations.completeActionRequest(id, request, { headers: headersFor(response, action, idempotencyKey, fresh) });
  },
  declineActionRequest: (id: string, response: ApiResponse<unknown>, action: AllowedAction, request: DeclineActionRequestRequest, idempotencyKey: string, fresh?: string) => {
    requireVersion(action, request.expected_version);
    return actionMutations.declineActionRequest(id, request, { headers: headersFor(response, action, idempotencyKey, fresh) });
  },
  delegateActionRequest: (id: string, response: ApiResponse<unknown>, action: AllowedAction, request: DelegateActionRequestRequest, idempotencyKey: string, fresh?: string) => {
    requireVersion(action, request.expected_version);
    return actionMutations.delegateActionRequest(id, request, { headers: headersFor(response, action, idempotencyKey, fresh) });
  }
} as const;

export function requireActionOperation(action: AllowedAction): keyof typeof actionOperationRegistry {
  if (!action.enabled || !(action.operation_id in actionOperationRegistry)) throw new Error('Unsupported ActionRequest operation');
  return action.operation_id as keyof typeof actionOperationRegistry;
}
```

`ActionDetail` selects one of these three closed registry entries from `allowed_actions.operation_id`, requires the entry to be enabled, and then constructs the corresponding generated request type. Its detail query retains the complete generated `ApiResponse`, not only the body, so `If-Match` reuses that response's quoted numeric ETag and fails closed if transport metadata is missing. `key` controls presentation only; `command_href` and `method` are neither displayed as a link nor executed. No code accepts a dynamic URL, dynamic method, arbitrary `Record<string, unknown>`, or body spread. Both `AllowedAction.expected_version` and generated request `expected_version` remain numbers and must be equal; the browser never reconstructs an ETag from either value. Revision, Candidate, policy, or manifest hashes remain in their generated request fields; the browser never substitutes a hash into the version header. The shared runtime adds the session-bound CSRF token after these operation-specific headers.

`ActionDetail` shows reason, gate, exact target version, diff deep link, evidence disclosure, and a sticky command bar. A logical command allocates its idempotency UUID once before dispatch and passes the same value through duplicate-click coalescing and every unchanged retry. A 409 response disables the old command, refetches detail, and presents `该待办已基于旧版本失效`; duplicate clicks share one in-flight promise. Reject requires a non-empty reason and server-provided reject action. Delegate opens only if `allowed_actions` contains the namespaced delegate command.

- [ ] **Step 5: Test queue tabs, stale action handling, and idempotency**

Run: `pnpm --filter @accord/web test -- action-detail.test.tsx action-list.test.tsx action-command.test.ts`

Expected: PASS with an exact two-query/three-mutation registry; tests mock only `listActionRequests`, `getActionRequest`, `completeActionRequest`, `declineActionRequest`, and `delegateActionRequest`; page rows remain complete envelopes; MSW observes the generated route, numeric body/action/object versions, quoted `If-Match`, runtime-injected CSRF token, and one shared idempotency key for a double click; old 409 action becomes disabled, unknown or disabled operation IDs cannot enter the registry, `command_href` and dynamic `method` are never passed to `fetch`, and `等待他人` does not increment the personal badge.

- [ ] **Step 6: Commit the ActionRequest checkpoint**

```bash
git add apps/web/src/modules/actions apps/web/src/routes/work-route.tsx apps/web/package.json packages/testkit pnpm-lock.yaml
git commit -m "feat(web): add global action workbench"
```

### Task 8: Build The Requirement Graph, Directory, And List Workspace

**Files:**
- Create: `apps/web/src/modules/requirements/api.ts`
- Create: `apps/web/src/modules/requirements/requirement-workspace.tsx`
- Create: `apps/web/src/modules/requirements/requirement-list.tsx`
- Create: `apps/web/src/modules/requirements/graph/requirement-graph.tsx`
- Create: `apps/web/src/modules/requirements/graph/layout.worker.ts`
- Create: `apps/web/src/modules/requirements/graph/layout.ts`
- Create: `apps/web/src/modules/requirements/graph/node-key.ts`
- Create: `apps/web/src/modules/requirements/graph/accessible-relations.tsx`
- Create: `apps/web/src/modules/requirements/requirement-workspace.test.tsx`
- Test: `apps/web/src/modules/requirements/graph/layout.test.ts`
- Test: `apps/web/src/modules/requirements/graph/node-key.test.ts`
- Test: `apps/web/src/modules/requirements/graph/accessible-relations.test.tsx`
- Create: `apps/web/src/routes/requirements-route.tsx`
- Create: `packages/testkit/src/fixtures/requirements.ts`
- Modify: `packages/testkit/src/fixtures/index.ts`
- Modify: `apps/web/package.json`
- Modify: `pnpm-lock.yaml`

- [ ] **Step 1: Write failing tests for URL state and the non-graph fallback**

```tsx
// apps/web/src/modules/requirements/requirement-workspace.test.tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { expect, it } from 'vitest';
import { RequirementWorkspace } from './requirement-workspace';
import { requirementWorkspaceFixture } from '@accord/testkit';

it('lets a keyboard user inspect every relationship without opening the canvas', async () => {
  render(<MemoryRouter initialEntries={['/t/t1/p/p1/requirements?view=list']}><RequirementWorkspace envelope={requirementWorkspaceFixture} /></MemoryRouter>);
  await userEvent.click(screen.getByRole('button', { name: '查看关系' }));
  expect(screen.getByRole('table', { name: '需求关系' })).toHaveTextContent('触发');
  expect(screen.getByRole('link', { name: '销售通知' })).toHaveAttribute(
    'href', expect.stringContaining('focus=b.00000000-0000-4000-8000-000000000101.3.RB-SALES-NOTICE'),
  );
});
```

- [ ] **Step 2: Run the workspace test and verify it fails**

Run: `pnpm --filter @accord/web test -- requirement-workspace.test.tsx`

Expected: FAIL because the requirement workspace is absent.

- [ ] **Step 3: Add graph, table, virtualization, and preference dependencies**

Run: `pnpm --filter @accord/web add @xyflow/react@12.11.2 elkjs@0.12.0 @tanstack/react-table@8.21.3 @tanstack/react-virtual@3.14.8 zustand@5.0.14`

Expected: `apps/web/package.json` contains the five dependencies and `pnpm-lock.yaml` changes once.

- [ ] **Step 4: Implement route-driven workspace queries**

```ts
// apps/web/src/modules/requirements/api.ts
import { queryOptions } from '@tanstack/react-query';
import { getRequirementGraph, listRequirements } from '@accord/api-client';

export const requirementQueries = Object.freeze({ listRequirements, getRequirementGraph });

type ListParameters = NonNullable<Parameters<typeof listRequirements>[1]>;
type GraphParameters = NonNullable<Parameters<typeof getRequirementGraph>[1]>;

export interface RequirementWorkspaceState {
  view: 'graph' | 'list';
  type?: ListParameters['block_type'];
  risk?: ListParameters['risk'];
  focus?: string;
  query?: string;
  domain?: string;
  phase?: ListParameters['phase'];
  cursor?: string;
  atSequence?: number;
}

export const requirementGraphOptions = (tenantId: string, projectId: string, state: RequirementWorkspaceState) => queryOptions({
  queryKey: ['tenant', tenantId, 'project', projectId, 'requirements', 'graph', state.atSequence, state.domain, state.type, state.risk, state.phase] as const,
  queryFn: async () => (await requirementQueries.getRequirementGraph(projectId, {
    at_sequence: state.atSequence, domain: state.domain,
    block_type: state.type as GraphParameters['block_type'],
    risk: state.risk as GraphParameters['risk'], phase: state.phase,
  })).body,
});
export const requirementListOptions = (tenantId: string, projectId: string, state: RequirementWorkspaceState) => queryOptions({
  queryKey: ['tenant', tenantId, 'project', projectId, 'requirements', 'list', state.cursor, state.query, state.domain, state.type, state.risk, state.phase] as const,
  queryFn: async () => (await requirementQueries.listRequirements(projectId, {
    cursor: state.cursor, query: state.query, business_domain: state.domain,
    block_type: state.type, risk: state.risk, phase: state.phase, limit: 50,
  })).body,
});
```

`RequirementWorkspace` owns no semantic state. It parses search params, renders a business-domain/type directory, lazy-loads `RequirementGraph` only for `view=graph`, renders `RequirementList` otherwise, and opens a right inspector for `focus`. `block_type` and `risk` are authoritative full-snapshot server filters and therefore enter both generated queries and their query keys; changing domain/type/risk/phase clears the old cursor and starts a new snapshot instead of filtering the loaded 50 rows. `view` and `focus` remain presentation-only and never enter a generated operation. Free-text search uses the distinct list-only `query`; `focus` is never reused as search text. Filters and focus are written back with `setSearchParams`; graph viewport and collapsed groups are stored under `accord:graph:{tenant}:{project}:{user}` and never sent in a requirement mutation.

Every business-domain × standard-block-type directory region exposes one accessible `新增需求` command. It opens Task 9's route modal with `intent=create_requirement`, `prefilled_business_domain`, and `prefilled_block_type`; those are generated create-draft hints, not an accepted classification. The final review always displays both values and requires the user to confirm or change them before `classification_confirmed=true`. No canvas coordinate, collapsed-group ID, browser node key, or currently loaded page determines classification.

Create the generated-contract-shaped fixture and export it from the existing testkit barrel:

```ts
// packages/testkit/src/fixtures/requirements.ts
import type { RequirementWorkspaceEnvelope } from '@accord/api-client';

export const requirementWorkspaceFixture = {
  data: {
    nodes: [
      {
        node_kind: 'requirement',
        ref: { node_kind: 'requirement', requirement_id: '00000000-0000-4000-8000-000000000101', revision_no: 3 },
        title: '库存释放', business_domain: '库存', primary_block_type: 'business_rule', phase: 'awaiting_development_confirmation',
        scores: [
          { score_type: 'business_ai_b', value: 82, state: 'current', anomaly: false },
          { score_type: 'development_blended_d', value: null, state: 'pending', anomaly: false },
        ],
        gate_facts: [{ gate_key: 'development_confirmation', state: 'blocked', label: '等待开发确认', blocking: true }],
        risk_facts: [{ code: 'cross_module', label: '跨模块', severity: 'medium', blocking: false, evidence_count: 2 }],
        blocker_count: 1,
        relation_counts: { precedes: 0, depends_on: 0, triggers: 1, constrains: 0, affects: 0, conflicts_with: 0, supersedes: 0, split_from: 0, relates_to: 0 },
      },
      {
        node_kind: 'block',
        ref: { node_kind: 'block', requirement_id: '00000000-0000-4000-8000-000000000101', revision_no: 3, block_id: 'RB-INVENTORY-RULE' },
        block_type: 'business_rule', title: '释放规则', summary: '仓库确认后释放库存', expected_effect: '库存恢复可售',
        owner_display_label: '库存产品组', phase: 'awaiting_development_confirmation',
        risk_facts: [], blocker_count: 0, gate_facts: [],
        relation_counts: { precedes: 0, depends_on: 0, triggers: 1, constrains: 0, affects: 0, conflicts_with: 0, supersedes: 0, split_from: 0, relates_to: 0 },
        attachment_refs: [],
      },
      {
        node_kind: 'block',
        ref: { node_kind: 'block', requirement_id: '00000000-0000-4000-8000-000000000101', revision_no: 3, block_id: 'RB-SALES-NOTICE' },
        block_type: 'report_notification', title: '销售通知', summary: '库存释放后通知销售', expected_effect: '销售及时获知可售状态',
        owner_display_label: '销售运营组', phase: 'awaiting_development_confirmation',
        risk_facts: [], blocker_count: 0, gate_facts: [],
        relation_counts: { precedes: 0, depends_on: 0, triggers: 0, constrains: 0, affects: 0, conflicts_with: 0, supersedes: 0, split_from: 0, relates_to: 0 },
        attachment_refs: [],
      },
    ],
    edges: [{
      id: 'RR-INVENTORY-NOTICE',
      source: { node_kind: 'block', requirement_id: '00000000-0000-4000-8000-000000000101', revision_no: 3, block_id: 'RB-INVENTORY-RULE' },
      target: { node_kind: 'block', requirement_id: '00000000-0000-4000-8000-000000000101', revision_no: 3, block_id: 'RB-SALES-NOTICE' },
      relation_type: 'triggers', label: '触发', rationale: '库存释放成功后通知销售',
    }],
    snapshot_sequence: 14,
    layout_policy_version: 'elk-layered-v1',
  },
  object_ref: { type: 'requirement_workspace', id: '00000000-0000-4000-8000-000000000001', version: 7, hash: null },
  sequence: 14,
  display_state: { stage_label: '持续准备中', next_action_label: null, blocker_labels: [], original_stage_label: null },
  allowed_actions: []
} satisfies RequirementWorkspaceEnvelope;

// append to packages/testkit/src/fixtures/index.ts
export * from './requirements';
```

- [ ] **Step 5: Implement deterministic layout in a worker**

```ts
// apps/web/src/modules/requirements/graph/node-key.ts
import type { RequirementGraphNodeRef } from '@accord/api-client';

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}';
const ROOT_KEY = new RegExp(`^r\\.(${UUID})\\.([1-9][0-9]*)$`, 'i');
const BLOCK_KEY = new RegExp(`^b\\.(${UUID})\\.([1-9][0-9]*)\\.(RB-[A-Z0-9-]{3,64})$`);

export function graphNodeKey(ref: RequirementGraphNodeRef): string {
  if (!Number.isSafeInteger(ref.revision_no) || ref.revision_no < 1) throw new Error('Invalid revision number');
  return ref.node_kind === 'requirement'
    ? `r.${ref.requirement_id}.${ref.revision_no}`
    : `b.${ref.requirement_id}.${ref.revision_no}.${ref.block_id}`;
}

export function parseGraphNodeKey(key: string): RequirementGraphNodeRef {
  const root = ROOT_KEY.exec(key);
  if (root) return { node_kind: 'requirement', requirement_id: root[1]!, revision_no: Number(root[2]) };
  const block = BLOCK_KEY.exec(key);
  if (block) return { node_kind: 'block', requirement_id: block[1]!, revision_no: Number(block[2]), block_id: block[3]! };
  throw new Error('Invalid requirement graph node key');
}
```

`node-key.test.ts` round-trips root and block refs, rejects zero/negative/non-integer revisions and malformed UUID/block IDs, and proves the same block ID in two Requirement Revisions cannot collide. The key is a browser display identity used consistently by ELK, XYFlow, accessible rows, inspector focus, and the `focus` search parameter; it is never persisted as a semantic node ID or sent in a command.

```ts
// apps/web/src/modules/requirements/graph/layout.ts
import ELK from 'elkjs/lib/elk.bundled.js';
import type { RequirementGraphEdge, RequirementGraphNode } from '@accord/api-client';
import { graphNodeKey } from './node-key';
const elk = new ELK();
export async function layout(nodes: readonly RequirementGraphNode[], edges: readonly RequirementGraphEdge[]) {
  const result = await elk.layout({
    id: 'root', layoutOptions: { 'elk.algorithm': 'layered', 'elk.direction': 'RIGHT', 'elk.spacing.nodeNode': '28' },
    children: nodes.map((node) => ({ id: graphNodeKey(node.ref), width: 240, height: node.node_kind === 'requirement' ? 128 : 104 })),
    edges: edges.map((edge) => ({ id: edge.id, sources: [graphNodeKey(edge.source)], targets: [graphNodeKey(edge.target)] }))
  });
  return new Map(result.children?.map((node) => [node.id, { x: node.x ?? 0, y: node.y ?? 0 }]) ?? []);
}
```

The worker receives immutable nodes/edges and returns positions. Root cards render authoritative B/D score facts and gate/risk/blocker summaries; block cards render the generated discriminator-specific type, summary, expected effect, responsible label, risks, blockers, relation counts, and material count. `RequirementGraph` allows pan, zoom, select, focus, and group collapse; it gives drag positions no semantic meaning and exposes no edge drawing or relation mutation. Agent relation suggestions are reviewed only in Task 9's generated draft/revision form and become formal edges atomically with submit. Edge legend labels all fixed relations, and conflict/blocking states include icon and text rather than color alone.

- [ ] **Step 6: Add responsive and accessibility assertions**

Run: `pnpm --filter @accord/web test -- requirement-workspace.test.tsx layout.test.ts node-key.test.ts accessible-relations.test.tsx`

Expected: PASS with exactly `listRequirements` and `getRequirementGraph` registered; the fixture satisfies the generated root/block union and uses `report_notification`; identical input returns identical positions; node keys are collision-free and reversible; list/table contains every loaded node and edge; root and block summaries expose the required server facts; selected node updates `focus`; `block_type/risk` enter both generated calls and trigger a new server snapshot without the old cursor; `focus/view` are absent from both calls; list `query` is independent from `focus`; graph/list domain parameter names remain distinct; and a mobile media-query fixture never imports the graph chunk.

- [ ] **Step 7: Commit the requirement workspace checkpoint**

```bash
git add apps/web/src/modules/requirements apps/web/src/routes/requirements-route.tsx apps/web/package.json packages/testkit pnpm-lock.yaml
git commit -m "feat(web): add requirement graph and list workspace"
```

### Task 9: Implement Route-Backed Requirement Intake With Server Drafts And Voice

**Files:**
- Create: `apps/web/src/modules/intake/intake-machine.ts`
- Create: `apps/web/src/modules/intake/intake-api.ts`
- Create: `apps/web/src/modules/intake/intake-dialog.tsx`
- Create: `apps/web/src/modules/intake/voice-recorder.tsx`
- Create: `apps/web/src/modules/intake/audio-disposition-status.tsx`
- Create: `apps/web/src/modules/intake/structured-review-form.tsx`
- Create: `apps/web/src/modules/intake/intake-dialog.test.tsx`
- Test: `apps/web/src/modules/intake/structured-review-form.test.tsx`
- Test: `apps/web/src/modules/intake/intake-machine.test.ts`
- Test: `apps/web/src/modules/intake/voice-recorder.test.tsx`
- Test: `apps/web/src/modules/intake/audio-disposition-status.test.tsx`
- Create: `apps/web/src/routes/new-requirement-route.tsx`
- Modify: `apps/web/package.json`
- Modify: `pnpm-lock.yaml`

- [ ] **Step 1: Write a failing test for one review submission and draft recovery**

```tsx
// apps/web/src/modules/intake/intake-dialog.test.tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import type { RequirementDraft } from '@accord/api-client';
import { IntakeDialog } from './intake-dialog';

const draft = {
  draft_id: '00000000-0000-4000-8000-000000000001', version: 4,
  intent: 'create_requirement', base_requirement_id: null, base_revision_no: null, base_revision_hash: null,
  content_hash: `sha256:${'a'.repeat(64)}`, source_type: 'speech', input_text: '释放库存',
  edited_transcript: '仓库确认后释放库存', locale: 'zh-CN',
  form: {
    business_domain: '库存', primary_block_type: 'business_rule', classification_source: 'canvas_prefill', title: '释放库存',
    intent: { goal: '让已取消订单的库存恢复销售', current_problem: '仓库确认后库存仍被占用', target_result: '确认后库存恢复可售并通知销售' },
    scope: { in_scope: ['库存释放', '销售通知'], out_of_scope: ['物流单撤销'] },
    blocks: [
      {
        block_id: 'RB-INVENTORY-RULE', block_type: 'business_rule', title: '库存释放规则',
        summary: '仓库确认取消后释放库存', expected_effect: '库存恢复可售', attachment_refs: [],
        rule_kind: 'constraint', rule_statement: '仅仓库确认后允许释放', conditions: ['订单已取消'],
        outcome: '库存状态变为可售', boundary_cases: ['已生成不可撤销物流单'], examples: ['订单 O-100 已取消且仓库已确认'],
      },
      {
        block_id: 'RB-SALES-NOTICE', block_type: 'report_notification', title: '销售通知',
        summary: '库存释放成功后通知销售', expected_effect: '销售及时看到可售库存', attachment_refs: [],
        artifact_kind: 'notification', trigger: '库存释放成功', recipients: ['销售运营'], channel: '站内消息',
        content_requirements: ['订单号', 'SKU', '可售数量'], timing: '一分钟内', escalation: null,
      },
    ],
    edge_cases: ['重复确认必须幂等', '物流单不可撤销时不得释放'],
    success_metrics: [{ metric_id: 'METRIC-1', description: '释放成功通知延迟', target: 'p95 <= 60 秒', measurement_method: '事件时间差' }],
    acceptance_criteria: [{ criterion_id: 'AC-1', business_outcome: '库存恢复可售且销售收到通知', verification_method: '业务验收场景', priority: 'must' }],
    questions: [{ question_id: 'GAP-1', gap_kind: 'edge_case', prompt: '物流单已生成时如何处理？', reason: '影响释放边界', blocking: false, answer: '保持占用并提示人工处理' }],
    relation_suggestions: [{
      suggestion_id: 'REL-SUG-1',
      relation: {
        relation_id: 'RR-INVENTORY-NOTICE',
        source: { endpoint_kind: 'local_block', block_id: 'RB-INVENTORY-RULE' }, relation_type: 'triggers',
        target: { endpoint_kind: 'local_block', block_id: 'RB-SALES-NOTICE' }, rationale: '释放成功后触发通知',
      },
      source: 'agent_suggested', review_tier: 'standard', decision: 'accepted', decision_reason: null,
    }],
  },
  attachments: [], current_extraction_id: '00000000-0000-4000-8000-000000000002',
  current_transcription_id: '00000000-0000-4000-8000-000000000003', audio_disposition: 'delete_after_submit',
  expires_at: '2026-07-25T12:00:00Z',
  object_ref: { type: 'requirement_draft', id: '00000000-0000-4000-8000-000000000001', version: 4, hash: `sha256:${'a'.repeat(64)}` },
  display_state: { stage_label: '待确认', next_action_label: '确认并创建需求', blocker_labels: [], original_stage_label: null },
  allowed_actions: []
} as const satisfies RequirementDraft;

it('recovers a server draft, submits once, and keeps raw-audio deletion pending', async () => {
  const submit = vi.fn().mockResolvedValue({
    requirement_id: '00000000-0000-4000-8000-000000000101', revision_no: 1,
    revision_hash: `sha256:${'b'.repeat(64)}`,
    audio_disposition: {
      transcription_id: '00000000-0000-4000-8000-000000000003',
      requested: 'delete_after_submit', state: 'deletion_pending',
      deletion_operation_id: '00000000-0000-4000-8000-000000000004',
      retained_reference: null, provider_delete_receipt_digest: null, absence_verified_at: null,
      failure_code: null, action_request_id: '00000000-0000-4000-8000-000000000005',
      recorded_at: '2026-07-25T10:00:00Z',
    },
  });
  render(<IntakeDialog draft={draft} onSave={vi.fn()} onSubmit={submit} />);
  expect(screen.getByDisplayValue('仓库确认后释放库存')).toBeInTheDocument();
  expect(screen.getByDisplayValue('让已取消订单的库存恢复销售')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('checkbox', { name: '确认业务域与需求类型' }));
  await userEvent.click(screen.getByRole('button', { name: '确认并创建需求' }));
  expect(submit).toHaveBeenCalledTimes(1);
  expect(submit).toHaveBeenCalledWith(expect.objectContaining({
    expected_version: 4, expected_hash: draft.content_hash,
    extraction_id: draft.current_extraction_id, transcript_confirmed: true,
    classification_confirmed: true, audio_disposition: 'delete_after_submit', form: draft.form,
  }));
  expect(await screen.findByText('原始录音删除处理中')).toBeInTheDocument();
  expect(screen.getByText('00000000-0000-4000-8000-000000000005')).toBeInTheDocument();
  expect(screen.queryByText('原始录音已删除')).not.toBeInTheDocument();
  expect(screen.getByDisplayValue('仓库确认后释放库存')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '打开已创建需求' })).toBeInTheDocument();
});
```

- [ ] **Step 2: Run the intake test and verify it fails**

Run: `pnpm --filter @accord/web test -- intake-dialog.test.tsx`

Expected: FAIL because intake components are absent.

- [ ] **Step 3: Add form and state-machine dependencies**

Run: `pnpm --filter @accord/web add react-hook-form@7.82.0 zod@3.25.67 @hookform/resolvers@5.4.0 xstate@5.32.5 @xstate/react@6.1.0`

- [ ] **Step 4: Define the local-only intake machine**

```ts
// apps/web/src/modules/intake/intake-machine.ts
import { setup } from 'xstate';
export const intakeMachine = setup({
  types: {} as { context: { draftId: string; dirty: boolean }; events: { type: 'GENERATE' } | { type: 'EXTRACTED' } | { type: 'FAILED' } | { type: 'SUBMIT' } | { type: 'CREATED' } },
  guards: { hasDraft: ({ context }) => context.draftId.length > 0 }
}).createMachine({
  id: 'requirement-intake', initial: 'capture', context: ({ input }) => ({ draftId: String(input ?? ''), dirty: false }),
  states: {
    capture: { on: { GENERATE: { target: 'extracting', guard: 'hasDraft' } } },
    extracting: { on: { EXTRACTED: 'review', FAILED: 'review' } },
    review: { on: { SUBMIT: 'submitting' } },
    submitting: { on: { CREATED: 'created', FAILED: 'review' } },
    created: { type: 'final' }
  }
});
```

The machine models browser interaction only. Server draft status, Agent job status, classification, blockers, and creation eligibility come from API projections and `allowed_actions`.

- [ ] **Step 5: Implement the complete generated draft adapter, autosave, and route modal**

```ts
// apps/web/src/modules/intake/intake-api.ts
import {
  completeRequirementDraftTranscriptionUpload, createRequirementDraft,
  createRequirementDraftTranscriptionUpload, extractRequirementDraft,
  getRequirementDraft, getRequirementDraftExtraction, getRequirementDraftTranscription,
  submitRequirementDraft, updateRequirementDraft,
  type ApiResponse
} from '@accord/api-client';

export const intakeQueries = Object.freeze({
  getRequirementDraft, getRequirementDraftExtraction, getRequirementDraftTranscription
});
export const intakeMutations = Object.freeze({
  createRequirementDraft, updateRequirementDraft, extractRequirementDraft,
  createRequirementDraftTranscriptionUpload, completeRequirementDraftTranscriptionUpload,
  submitRequirementDraft
});

export function intakeCommandHeaders(response: ApiResponse<unknown>, idempotencyKey: string) {
  if (!response.etag || !/^"[0-9]+"$/.test(response.etag)) throw new Error('Missing draft ETag');
  return { 'If-Match': response.etag, 'Idempotency-Key': idempotencyKey } as const;
}
```

`/requirements/new` is a browser route only. With no `draft` search parameter it calls generated `createRequirementDraft`, passing `intent=create_requirement` and optional Task 8 domain/block-type prefills, follows the returned draft ref, and replaces the URL with `draft=<id>`; otherwise it calls generated `getRequirementDraft`. A business edit uses the same route with `intent=revise_existing` and the generated exact base Requirement ID, positive revision number, and 71-character hash. The route renders a React Aria `Dialog` over the requirement workspace. Reload always fetches the same durable draft. Text, edited transcript, complete `DraftForm`, attachment refs, and audio-disposition selection autosave after 750 ms through `updateRequirementDraft`, using the latest response ETag, numeric `expected_version`, and one idempotency key per unchanged request fingerprint. A 409 keeps local edits visible, refetches, and asks the user to merge; it never overwrites the newer server draft. Unload does not place content in localStorage, sessionStorage, IndexedDB, or history state. Back closes the route modal and returns focus to the invoking domain/type region or global `新增需求` button. At widths below 768 px the same component renders as a full route page.

Extraction calls `extractRequirementDraft`, polls only `getRequirementDraftExtraction`, and accepts a completed form only when `based_on_version` and `based_on_hash` still equal the displayed draft. `stale`, mismatched, or late responses remain historical and cannot replace edits. Agent failure/unavailability does not dead-end the workflow: the same generated empty/current `DraftForm` remains editable, the user can manually complete every required field, and submit appears only when the refreshed server projection returns the matching action. The browser never supplies a fake extraction result or relaxes a server blocker.

`StructuredReviewForm` uses only generated discriminator unions. Its first screen always exposes goal, current problem, target result, and blocking questions. Expandable sections edit target users and typed scenarios/rules/workflow/data/permission/report-notification/non-functional blocks, in/out scope, edge cases, success metrics, acceptance criteria, and non-blocking gaps. Each block owns an AttachmentPicker binding so material can be attached to the exact block, while draft-level material remains separate. `extension` renders only when the generated project registry projection supplies a pinned schema renderer; unknown extensions fail closed to read-only JSON metadata, never an arbitrary schema or executable widget.

The review shows final business domain and primary standard type even when they came from a canvas hint or Agent suggestion. It sends `classification_confirmed=true` only after an explicit checkbox on the currently displayed values; changing either value clears the checkbox. Relation suggestions render source/target exact refs, type, rationale, and `standard/prominent` risk treatment. Every pending suggestion requires an explicit accept or reject (with a reason for prominent/conflict/ordering relations); only then can the server expose submit. The browser does not create a relation mutation or infer that a highlighted suggestion was accepted. Submit uses generated `SubmitRequirementDraftRequest`; draft ID stays in generated path arguments, not in the body. It waits for the immutable `RequirementDraftSubmissionReceipt`, validates the exact positive Revision number and SHA-256 hash, then renders a created-result state with an explicit `打开已创建需求` link. Requirement creation is complete independently of raw-audio cleanup, so pending/failure never rolls back the Revision or clears confirmed text. For `delete_after_submit`, the immutable receipt is accepted only with initial `audio_disposition.state=deletion_pending`; the browser never mutates that receipt after worker progress or treats it as a final deletion fact.

- [ ] **Step 6: Implement accessible voice capture**

`VoiceRecorder` uses `MediaRecorder`, exposes named `开始录音` and `停止录音` buttons, a visible/live timer, and an `aria-live=polite` transcription state. Stop calls generated `createRequirementDraftTranscriptionUpload`, consumes only its short-lived upload capability, then calls `completeRequirementDraftTranscriptionUpload` and polls `getRequirementDraftTranscription`. Only a `ready` transcript enters the normal editable textarea; failed or superseded jobs preserve recording retry controls and all typed text. A radio group defaults to `提交后删除原始录音` (`delete_after_submit`) and offers `作为参考附件保留` (`retain_as_reference`); text-only drafts use generated `not_applicable` and render no false retention choice. Submit sends the edited transcript confirmation and current generated audio disposition.

`AudioDispositionStatus` keeps the immutable submission fact separate from the current `DraftTranscription.audio_disposition` projection. For `deletion_pending`, it shows `原始录音删除处理中`, the returned ActionRequest identity/owner, and the already-created Revision link; it invalidates/refetches only through ordered Task 6 events plus a bounded visible/online polling fallback using the existing generated `getRequirementDraftTranscription`. `304` retains the last projection, network/provider ambiguity stays pending, polling backs off and pauses while hidden/offline, and no browser timer changes the state. The fallback stops at a terminal state and restarts only after a returned ActionRequest action/event invalidates the query.

`deleted` is rendered only from the current projection when `provider_delete_receipt_digest` and `absence_verified_at` are both present; the browser displays those server facts but does not verify Provider semantics itself. A `deleted` state missing either proof fails closed to `删除证明不完整，仍按处理中显示`. `deletion_failed` requires the returned `failure_code` and `action_request_id`, shows `原始录音删除未完成`, and links to Task 7's authoritative ActionRequest; retry is available only from that ActionRequest's generated `allowed_actions`, never from a locally invented delete/retry call. `retained_as_reference` requires and renders the exact immutable reference Attachment ID/version/hash/binding through Task 10, while `not_applicable` renders no audio claim. Pending, failed, refresh, retry, and malformed projection paths preserve the submitted transcript and structured form. The browser never derives success from the requested disposition, elapsed time, a completed generic task, or the old submission receipt.

The generated contract and Task 3 runtime parser enforce this exact three-disposition/state tuple before `AudioDispositionStatus` renders it. `operation` below means the public `deletion_operation_id` data field, not a new OpenAPI operation or browser command:

| Requested disposition | Current state | `transcription_id` | `deletion_operation_id` / `action_request_id` | Retained reference | Provider receipt + absence time | Failure code |
|---|---|---|---|---|---|---|
| `not_applicable` | `not_applicable` | null | both null | null | both null | null |
| `delete_after_submit` | `deletion_pending` | non-null | both non-null | null | both null | null |
| `delete_after_submit` | `deleted` | non-null | both non-null; ActionRequest is completed | null | both non-null | null |
| `delete_after_submit` | `deletion_failed` | non-null | both non-null; ActionRequest is actionable | null | both null | non-null |
| `retain_as_reference` | `retained_as_reference` | non-null | both null | non-null immutable `reference` binding | both null | null |

Any request/state mismatch, partial proof pair, forbidden extra field, or wrong nullable tuple is rejected at the validated API boundary and rendered by the route error boundary; it is never normalized into a successful status. `not_applicable` and `retained_as_reference` are already terminal and start no deletion polling. The three user choices remain text-only/no audio, delete after submit, and retain as a reference attachment; the delete choice alone has the internal pending/success/failure lifecycle.

```tsx
// apps/web/src/modules/intake/audio-disposition-status.test.tsx
import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import type { AudioDispositionFact } from '@accord/api-client';
import { AudioDispositionStatus } from './audio-disposition-status';

const pending = {
  transcription_id: '00000000-0000-4000-8000-000000000003',
  requested: 'delete_after_submit', state: 'deletion_pending',
  deletion_operation_id: '00000000-0000-4000-8000-000000000004',
  retained_reference: null, provider_delete_receipt_digest: null, absence_verified_at: null,
  failure_code: null, action_request_id: '00000000-0000-4000-8000-000000000005',
  recorded_at: '2026-07-25T10:00:00Z',
} as const satisfies AudioDispositionFact;

const deleted = {
  ...pending, state: 'deleted',
  provider_delete_receipt_digest: `sha256:${'c'.repeat(64)}`,
  absence_verified_at: '2026-07-25T10:01:00Z',
} as const satisfies AudioDispositionFact;

const retained = {
  transcription_id: '00000000-0000-4000-8000-000000000006',
  requested: 'retain_as_reference', state: 'retained_as_reference',
  deletion_operation_id: null,
  retained_reference: {
    attachment_id: '00000000-0000-4000-8000-000000000007', version: 1,
    content_hash: `sha256:${'d'.repeat(64)}`, binding_type: 'reference',
  },
  provider_delete_receipt_digest: null, absence_verified_at: null,
  failure_code: null, action_request_id: null,
  recorded_at: '2026-07-25T10:00:00Z',
} as const satisfies AudioDispositionFact;

const notApplicable = {
  transcription_id: null,
  requested: 'not_applicable', state: 'not_applicable',
  deletion_operation_id: null, retained_reference: null,
  provider_delete_receipt_digest: null, absence_verified_at: null,
  failure_code: null, action_request_id: null,
  recorded_at: '2026-07-25T10:00:00Z',
} as const satisfies AudioDispositionFact;

it('shows deletion only from a current provider-backed projection, never the initial receipt', () => {
  const view = render(<AudioDispositionStatus initialFact={pending} currentFact={pending} />);
  expect(screen.getByText('原始录音删除处理中')).toBeInTheDocument();
  expect(screen.queryByText('原始录音已删除')).not.toBeInTheDocument();

  view.rerender(<AudioDispositionStatus initialFact={pending} currentFact={deleted} />);
  expect(screen.getByText('原始录音已删除')).toBeInTheDocument();
  expect(screen.getByText(deleted.provider_delete_receipt_digest)).toBeInTheDocument();
  expect(screen.getByText('2026-07-25T10:01:00Z')).toBeInTheDocument();
});

it('fails closed on incomplete proof and exposes the returned remediation ActionRequest on failure', () => {
  const view = render(<AudioDispositionStatus
    initialFact={pending}
    currentFact={{ ...deleted, provider_delete_receipt_digest: null }}
  />);
  expect(screen.getByText('删除证明不完整，仍按处理中显示')).toBeInTheDocument();
  expect(screen.queryByText('原始录音已删除')).not.toBeInTheDocument();

  view.rerender(<AudioDispositionStatus initialFact={pending} currentFact={{
    ...pending, state: 'deletion_failed', failure_code: 'PROVIDER_DELETE_RETRY_EXHAUSTED',
  }} />);
  expect(screen.getByText('原始录音删除未完成')).toBeInTheDocument();
  expect(screen.getByText('PROVIDER_DELETE_RETRY_EXHAUSTED')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看删除处理待办' })).toHaveTextContent(pending.action_request_id);
  expect(screen.queryByText('原始录音已删除')).not.toBeInTheDocument();
});

it('renders retention but no false audio claim for the other two disposition paths', () => {
  const view = render(<AudioDispositionStatus
    initialFact={notApplicable} currentFact={notApplicable}
  />);
  expect(screen.queryByText(/原始录音/)).not.toBeInTheDocument();

  view.rerender(<AudioDispositionStatus initialFact={retained} currentFact={retained} />);
  expect(screen.getByText('原始录音已作为参考附件保留')).toBeInTheDocument();
  expect(screen.getByText(retained.retained_reference.content_hash)).toBeInTheDocument();
  expect(screen.queryByText('原始录音删除处理中')).not.toBeInTheDocument();
  expect(screen.queryByText('原始录音已删除')).not.toBeInTheDocument();
});
```

- [ ] **Step 7: Verify input loss, retry, and single-submit behavior**

Run: `pnpm --filter @accord/web test -- intake-dialog.test.tsx structured-review-form.test.tsx intake-machine.test.ts voice-recorder.test.tsx audio-disposition-status.test.tsx`

Expected: PASS with an exact three-query/six-mutation registry; region prefills remain unconfirmed hints; all generated form fields and every typed block round-trip through autosave/refresh; pending relation suggestions and unconfirmed classification block submit; accepted cross-Requirement refs retain exact positive Revision numbers; Agent failure still permits complete manual structured entry without text loss; stale extraction cannot overwrite a newer draft; reload uses `getRequirementDraft`; an unsupported microphone still permits text input; the exact text-only `not_applicable`, default voice `delete_after_submit`, and explicit voice `retain_as_reference` paths enforce their closed nullable tuples; text-only input renders no audio claim or polling; deletion submit returns only immutable `deletion_pending`; the existing transcription query alone observes `deletion_pending → deleted | deletion_failed`; only current Provider receipt plus absence facts render deletion; pending/failure/malformed proof preserves transcript/form and exposes the server ActionRequest; explicit retention produces only an immutable reference Attachment and no deletion polling; submit has no `draft_id` body field or unconditional fresh-auth header; and repeated create clicks share one in-flight command/idempotency key. A source scan finds no API path literal, handwritten server DTO, `apiFetch`, dynamic command href, client relation/state mutation, browser-derived deletion state, or audio-delete/retry request in `modules/intake`.

- [ ] **Step 8: Commit the intake checkpoint**

```bash
git add apps/web/src/modules/intake apps/web/src/routes/new-requirement-route.tsx apps/web/package.json pnpm-lock.yaml
git commit -m "feat(web): add resumable requirement intake"
```

### Task 10: Build Attachment Upload, Preview, Classification, And Access Preflight

**Files:**
- Create: `apps/web/src/modules/attachments/upload-manager.tsx`
- Create: `apps/web/src/modules/attachments/attachment-picker.tsx`
- Create: `apps/web/src/modules/attachments/attachment-preview.tsx`
- Create: `apps/web/src/modules/attachments/access-scope-field.tsx`
- Create: `apps/web/src/modules/attachments/access-preflight.tsx`
- Create: `apps/web/src/modules/attachments/attachment-picker.test.tsx`
- Test: `apps/web/src/modules/attachments/upload-manager.test.tsx`
- Test: `apps/web/src/modules/attachments/attachment-preview.test.tsx`
- Test: `apps/web/src/modules/attachments/access-preflight.test.tsx`
- Create: `apps/web/src/modules/attachments/attachment-api.ts`
- Modify: `apps/web/package.json`
- Modify: `pnpm-lock.yaml`

- [ ] **Step 1: Write a failing test for inherited access and quarantine**

```tsx
// apps/web/src/modules/attachments/attachment-picker.test.tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import type { AttachmentVersion } from '@accord/api-client';
import { AttachmentPicker } from './attachment-picker';

const quarantinedAttachment = {
  attachment_id: '00000000-0000-4000-8000-000000000010', version: 1,
  file_name: 'rule.xlsx', declared_media_type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  detected_media_type: null, size: 10, sha256: `sha256:${'b'.repeat(64)}`,
  state: 'quarantined', binding_type: 'contractual', access_mode: 'project_shared',
  access_scope_id: null, access_label: '项目共享', created_at: '2026-07-25T00:00:00Z',
  object_ref: { type: 'attachment_version', id: '00000000-0000-4000-8000-000000000010', version: 1, hash: `sha256:${'b'.repeat(64)}` },
  display_state: { stage_label: '已隔离', next_action_label: null, blocker_labels: ['文件已隔离'], original_stage_label: null },
  allowed_actions: []
} as const satisfies AttachmentVersion;

it('defaults to project sharing and blocks quarantined material', () => {
  render(<AttachmentPicker inheritedScope={{ id: 'scope-project', label: '项目共享' }} attachments={[quarantinedAttachment]} />);
  expect(screen.getByText('项目共享')).toBeInTheDocument();
  expect(screen.getByText('文件已隔离，Agent 与成员均不可读取')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '用于确认版本' })).toBeDisabled();
});
```

- [ ] **Step 2: Run the attachment test and verify it fails**

Run: `pnpm --filter @accord/web test -- attachment-picker.test.tsx`

Expected: FAIL because the attachment module is absent.

- [ ] **Step 3: Add resumable upload dependencies and implement the global tray**

Run: `pnpm --filter @accord/web add @uppy/core@5.2.0 @uppy/react@5.2.0 @uppy/aws-s3@5.1.0`

`@uppy/aws-s3` is used only as a browser implementation of the provider-neutral presigned multipart protocol. The browser never receives an AWS account, bucket, object key, access key, region credential, or reusable signer; all provider-specific OSS/S3 behavior stays behind the server-owned `ObjectStoragePort` and its certified capability matrix. A deployment whose object provider cannot satisfy the signed-part, checksum, abort, immutable-version, and read-back contract must fail the upload capability gate rather than fall back to provider-specific browser credentials.

Create the closed, zero-DTO adapter before wiring Uppy:

```ts
// apps/web/src/modules/attachments/attachment-api.ts
import {
  abortAttachmentUpload, completeAttachmentUpload, createAttachmentDownloadCapability,
  createAttachmentPreviewCapability, createAttachmentUpload, getAttachmentStatus,
  getAttachmentUpload, getAttachmentVersion, listAttachments, listAttachmentUploadParts,
  runAttachmentAccessPreflight, signAttachmentUploadPart,
  type ScopedAttachmentCapability
} from '@accord/api-client';

export const attachmentQueries = Object.freeze({
  listAttachments, getAttachmentUpload, listAttachmentUploadParts,
  getAttachmentVersion, getAttachmentStatus
});
export const attachmentMutations = Object.freeze({
  createAttachmentUpload, signAttachmentUploadPart, completeAttachmentUpload,
  abortAttachmentUpload, createAttachmentPreviewCapability,
  createAttachmentDownloadCapability, runAttachmentAccessPreflight
});

const capabilityMethod = { upload_part: 'PUT', preview: 'GET', download: 'GET' } as const;

export async function consumeAttachmentCapability(
  scoped: ScopedAttachmentCapability,
  allowedOrigins: ReadonlySet<string>,
  consume: (url: string, init: RequestInit) => Promise<Response>
): Promise<Response> {
  const { capability } = scoped;
  if (capability.maximum_uses !== 1 || capability.method !== capabilityMethod[scoped.purpose]) {
    throw new Error('Invalid attachment capability bounds');
  }
  if (Date.parse(capability.expires_at) <= Date.now()) throw new Error('Attachment capability expired');
  const url = new URL(capability.url);
  if (url.protocol !== 'https:' || !allowedOrigins.has(url.origin)) {
    throw new Error('Attachment capability origin is not approved');
  }
  return consume(url.href, {
    method: capability.method, headers: capability.required_headers, credentials: 'omit',
    cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer'
  });
}
```

`UploadManager` obtains the upload aggregate and each multipart capability from `attachmentMutations`, sets Attachment ID/version returned by the server, and displays `上传中 → 扫描中 → 可用` or `已隔离`. Uppy never receives a bucket, object key, or reusable signer. Every part calls `signAttachmentUploadPart` immediately before upload and discards the one-use capability response after `consumeAttachmentCapability` settles. The tray survives route navigation in `AppShell`; it does not persist file blobs, capability responses, or signed URLs to browser storage. Retry first calls `getAttachmentUpload` plus `listAttachmentUploadParts`; cancel calls `abortAttachmentUpload`; completion calls `completeAttachmentUpload` and status polling uses only `getAttachmentStatus`.

- [ ] **Step 4: Implement safe preview and explicit classification effects**

`AttachmentVersion` and `AttachmentStatus` contain metadata and availability only; they never contain preview/download hrefs. On an explicit click, `AttachmentPreview` calls `createAttachmentPreviewCapability`, consumes the response immediately outside TanStack Query, fetches bytes through the bounded capability executor, and retains only a blob object URL, which is revoked on close, tenant change, 403, expiry, or replacement. Images use that object URL; PDFs use it in a sandboxed viewer; HTML, SVG, and office content are never rendered with active content or `dangerouslySetInnerHTML`. Download repeats the flow with `createAttachmentDownloadCapability`; it streams to a user-selected file when supported and otherwise uses a bounded blob fallback. The capability URL itself never enters a DOM attribute, component state, Query cache, route/search parameter, storage, analytics, RUM, log, error, fixture, or persisted mutation record. The final intake review shows `影响确认版本（改变内容将产生新需求版本）` and `仅作背景（不自动使确认失效）`; the Agent recommendation is selected but remains editable until submit.

- [ ] **Step 5: Implement capability-driven access scope and preflight**

The scope field displays inherited `项目共享` by default and opens member/group search only when `限制成员范围` is selected. It submits scope IDs, not names. `AccessPreflight` invokes only generated `runAttachmentAccessPreflight` with integer `revisionNo`, the response ETag, numeric `expected_version`, exact revision hash, and actor account IDs. It renders the server list of required actors, exact attachment versions, accessible/missing result, and the returned `access_fix` ActionRequest ref. It never determines accessibility from the visible member list and never follows an action `command_href`.

- [ ] **Step 6: Test scan transitions, preview sandbox, and access loss**

Run: `pnpm --filter @accord/web test -- attachment-picker.test.tsx upload-manager.test.tsx attachment-preview.test.tsx access-preflight.test.tsx`

Expected: PASS with an exact five-query/seven-mutation registry; fixtures and metadata contain no pre-issued preview/download link fields; quarantined files expose no preview/download command; project scope is default; capability operations run only on click; one-use method/expiry/HTTPS and exact runtime-origin-allowlist bounds are enforced before network access; no capability URL enters cache, route, DOM, storage, fixture, or logger; and a 403/expiry revokes the object URL, clears all local capability references, and opens the current access-fix projection. A source scan finds no handwritten API URL, server DTO, `apiFetch`, or executed `command_href` in `modules/attachments`.

- [ ] **Step 7: Commit the attachment checkpoint**

```bash
git add apps/web/src/modules/attachments apps/web/package.json pnpm-lock.yaml
git commit -m "feat(web): add secure attachment workflow"
```

### Task 11: Render Business/Development Views And Exact Revision Diffs

**Files:**
- Create: `apps/web/src/modules/requirements/revision-header.tsx`
- Create: `apps/web/src/modules/requirements/business-view.tsx`
- Create: `apps/web/src/modules/requirements/development-view.tsx`
- Create: `apps/web/src/modules/requirements/business-revision-editor.tsx`
- Create: `apps/web/src/modules/requirements/business-question-thread.tsx`
- Create: `apps/web/src/modules/requirements/requirement-collaboration-api.ts`
- Create: `apps/web/src/modules/requirements/revision-diff.tsx`
- Create: `apps/web/src/modules/requirements/revision-detail.test.tsx`
- Test: `apps/web/src/modules/requirements/business-revision-editor.test.tsx`
- Test: `apps/web/src/modules/requirements/business-question-thread.test.tsx`
- Test: `apps/web/src/modules/requirements/revision-header.test.tsx`
- Test: `apps/web/src/modules/requirements/revision-diff.test.tsx`
- Create: `apps/web/src/routes/requirement-revision-route.tsx`
- Create: `apps/web/src/routes/requirement-compare-route.tsx`
- Modify: `packages/testkit/src/fixtures/requirements.ts`

- [ ] **Step 1: Write a failing test proving field ownership follows actions**

```tsx
// apps/web/src/modules/requirements/revision-detail.test.tsx
import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { businessProjectionFixture } from '@accord/testkit';
import { BusinessView } from './business-view';

it('shows development proposal instead of an edit control when that is the allowed action', () => {
  render(<BusinessView projection={{
    ...businessProjectionFixture,
    allowed_actions: [{
      key: 'create_development_proposal', operation_id: 'createDevelopmentProposal',
      command_href: '/commands/not-executed',
      method: 'POST', expected_version: 4, expected_hash: `sha256:${'d'.repeat(64)}`,
      requires_idempotency_key: true, fresh_auth: 'none', enabled: true, disabled_reason_code: null,
    }],
  }} />);
  expect(screen.queryByRole('button', { name: '编辑预期效果' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '提出修改建议' })).toBeInTheDocument();
});
```

```tsx
// apps/web/src/modules/requirements/business-revision-editor.test.tsx
it('revises business-owned fields through a child-revision draft', async () => {
  const startRevision = vi.fn();
  render(<BusinessRevisionEditor revision={confirmedRevision} draft={null} onStartRevision={startRevision} />);
  expect(screen.queryByLabelText('修改受影响接口')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: '修改业务内容' }));
  expect(startRevision).toHaveBeenCalledWith(expect.objectContaining({
    intent: 'revise_existing', base_requirement_id: confirmedRevision.requirement_id,
    base_revision_no: confirmedRevision.revision_no, base_revision_hash: confirmedRevision.revision_hash
  }));
});
```

```tsx
// apps/web/src/modules/requirements/business-question-thread.test.tsx
it('lets business question a development-owned field without editing it', () => {
  render(<BusinessQuestionThread projection={developmentProjection} questions={openQuestionPage} />);
  expect(screen.queryByRole('button', { name: '编辑迁移风险' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '询问开发侧' })).toBeInTheDocument();
  expect(screen.getByText('等待开发侧回答')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run the revision test and verify it fails**

Run: `pnpm --filter @accord/web test -- revision-detail.test.tsx`

Expected: FAIL because revision views are absent.

- [ ] **Step 3: Implement exact-version routes and a fixed revision header**

Register every generated requirement read operation in the route module; do not create a second DTO, URL, or generic data loader:

```ts
// apps/web/src/routes/requirement-revision-route.tsx
import {
  compareRequirementRevisions, getRequirement, getRequirementBusinessProjection,
  getRequirementDevelopmentProjection, getRequirementRevision, listRequirementRevisions
} from '@accord/api-client';

export const requirementRevisionQueries = Object.freeze({
  getRequirement, listRequirementRevisions, getRequirementRevision,
  getRequirementBusinessProjection, getRequirementDevelopmentProjection,
  compareRequirementRevisions
});

export function requireRevisionNo(raw: string | undefined): number {
  if (!raw || !/^[1-9][0-9]*$/.test(raw)) throw new Response('Not found', { status: 404 });
  const revisionNo = Number(raw);
  if (!Number.isSafeInteger(revisionNo)) throw new Response('Not found', { status: 404 });
  return revisionNo;
}
```

The exact route is `/requirements/:requirementId/revisions/:revisionNo`; `revisionNo` is parsed once as a positive safe integer and is passed unchanged to generated operations. No alternate opaque revision identifier exists in browser code. `/requirements/:requirementId` calls `getRequirement` and redirects to the exact integer revision URL returned by the server. The history drawer calls `listRequirementRevisions`; the compare route parses `from` and `to` as integers and calls `compareRequirementRevisions` with only `from_revision_no` and `to_revision_no`. `RevisionHeader` renders Chinese stage, short hash with copy action, version number, effective/old badge, next action, hold reasons, and `查看与上一版本差异`. A stale ActionRequest URL remains on its old read-only revision and provides a link to the current version; it never silently redirects a confirmation command.

- [ ] **Step 4: Implement source-aware dual projections**

The route concurrently calls `getRequirementRevision`, `getRequirementBusinessProjection`, and `getRequirementDevelopmentProjection` for the same integer `revisionNo`, then rejects a concealed ownership/ref/hash mismatch before rendering. Business view renders generated intent (goal/current problem/target result), typed scenarios/rules/workflow/data/permission/report-notification/non-functional blocks, scope, edge cases, relations, success metrics, acceptance criteria, accepted unknowns, decisions, and materials. Development view renders the generated Context basis, status, module/interface/data/permission/state/dependency impacts, feasibility/constraints, modification suggestions, compatibility/migration, risks, ordered WorkItem plan, tests, unknowns, and evidence. Each field uses `field_sources` and evidence refs supplied by the server. Edit/proposal controls are rendered only when the relevant generated envelope has an enabled `allowed_actions.operation_id`; `command_href` is never executed.

Append contract-shaped fixtures to `packages/testkit/src/fixtures/requirements.ts`; these are deliberately complete enough to make a missing generated projection field fail TypeScript rather than disappear from a component test:

```ts
import type { BusinessProjection, DevelopmentProjection } from '@accord/api-client';

const revisionHash = `sha256:${'d'.repeat(64)}`;
const requirementSummary = {
  requirement_id: '00000000-0000-4000-8000-000000000101', title: '库存释放', business_domain: '库存',
  current_revision_no: 4, current_revision_hash: revisionHash, version: 4,
  primary_block_type: 'business_rule', risk_level: 'medium', display_phase: 'under_review',
  next_actor_label: '开发负责人', blockers: [], due_at: null, allowed_actions: [],
} as const;
const businessRuleBlock = {
  block_id: 'RB-INVENTORY-RULE', block_type: 'business_rule', title: '库存释放规则',
  summary: '仓库确认后释放库存', expected_effect: '库存恢复可售', attachment_refs: [],
  rule_kind: 'constraint', rule_statement: '仅仓库确认后允许释放', conditions: ['订单已取消'],
  outcome: '库存状态变为可售', boundary_cases: ['不可撤销物流单'], examples: ['O-100'],
} as const;

export const businessProjectionFixture = {
  requirement: requirementSummary,
  intent: { goal: '恢复可售库存', current_problem: '取消订单仍占库存', target_result: '确认后释放并通知销售' },
  scope: { in_scope: ['库存释放'], out_of_scope: ['物流撤销'] }, blocks: [businessRuleBlock], relations: [],
  edge_cases: ['重复确认必须幂等'],
  success_metrics: [{ metric_id: 'METRIC-1', description: '释放延迟', target: 'p95 <= 60 秒', measurement_method: '事件时间差' }],
  acceptance_criteria: [{ criterion_id: 'AC-1', business_outcome: '库存恢复可售', verification_method: '验收场景', priority: 'must' }],
  accepted_unknowns: [], semantic_decisions: [], material_attachments: [], assessment_summary: '业务 B 82，等待开发评估',
  field_sources: [{ json_pointer: '/intent/goal', source_kind: 'business_user', source_ref: 'draft-1', source_digest: `sha256:${'e'.repeat(64)}` }],
  object_ref: { type: 'requirement_revision', id: '00000000-0000-4000-8000-000000000101', version: 4, hash: revisionHash },
  display_state: { stage_label: '开发审阅中', next_action_label: '审阅影响初稿', blocker_labels: [], original_stage_label: null },
  allowed_actions: [],
} as const satisfies BusinessProjection;

export const developmentProjectionFixture = {
  requirement: requirementSummary, blocks: [businessRuleBlock],
  development_view: {
    status: 'draft',
    context_basis: {
      context_version_id: '00000000-0000-4000-8000-000000000201', context_basis_digest: `sha256:${'1'.repeat(64)}`,
      schema_version: '1.0', agent_pack_version: '1.0.0', analyzer_version: 'java-spring-1.0.0',
      basis_commit_sha: 'a'.repeat(40), basis_tree_sha: 'b'.repeat(40),
      trust_labels: ['platform_structure_verified', 'customer_ci_verified'],
    },
    impacts: [{ impact_id: 'IMP-1', kind: 'module', subject_ref: 'inventory-service', impact_type: 'modify', rationale: '释放状态转换', confidence_label: 'observed', evidence_refs: ['claim:inventory-state'] }],
    constraints: ['保持幂等'], modification_suggestions: ['复用 InventoryReleasePolicy'],
    compatibility_considerations: ['旧客户端忽略新增通知字段'], migration_considerations: [],
    risks: [{ risk_id: 'RISK-1', category: 'consistency', severity: 'medium', description: '并发释放', mitigation: '条件更新', blocking: false }],
    work_item_plan: [{ plan_item_id: 'PLAN-1', title: '实现释放策略', goal: '原子释放', non_goals: ['物流撤销'], covered_block_ids: ['RB-INVENTORY-RULE'], dependencies: [], completion_conditions: ['单元与集成测试通过'] }],
    test_mappings: [{ mapping_id: 'TM-1', criterion_id: 'AC-1', test_level: 'integration', verification: '验证可售状态', evidence_refs: ['test:inventory-release'] }],
    unknowns: [], evidence_refs: ['claim:inventory-state'],
  },
  acceptance_criteria: businessProjectionFixture.acceptance_criteria, material_attachments: [],
  field_sources: [{ json_pointer: '/development_view/impacts/0', source_kind: 'project_context', source_ref: 'claim:inventory-state', source_digest: `sha256:${'2'.repeat(64)}` }],
  object_ref: businessProjectionFixture.object_ref, display_state: businessProjectionFixture.display_state, allowed_actions: [],
} as const satisfies DevelopmentProjection;
```

- [ ] **Step 5: Implement owned business revision editing and BusinessQuestion threads**

Register the five Requirement-owned BusinessQuestion operations once, all from the cumulative generated client:

```ts
// apps/web/src/modules/requirements/requirement-collaboration-api.ts
import {
  addRequirementBusinessQuestionMessage, createRequirementBusinessQuestion,
  getRequirementBusinessQuestion, listRequirementBusinessQuestions,
  resolveRequirementBusinessQuestion
} from '@accord/api-client';

export const businessQuestionQueries = Object.freeze({
  listRequirementBusinessQuestions, getRequirementBusinessQuestion
});
export const businessQuestionMutations = Object.freeze({
  createRequirementBusinessQuestion, addRequirementBusinessQuestionMessage,
  resolveRequirementBusinessQuestion
});
```

`BusinessRevisionEditor` never updates a formal Revision. It obtains the current Requirement aggregate response/ETag and starts Task 9's generated `createRequirementDraft` with `intent=revise_existing` plus exact `base_requirement_id`, integer `base_revision_no`, and `base_revision_hash`. The server-returned draft is prefilled from that Revision; the editor reuses `updateRequirementDraft`, optional extraction, attachment binding, classification confirmation, and `submitRequirementDraft`. Only business-owned discriminated block fields are editable. Development-owned and joint fields render read-only from the base and are not accepted into the draft mutation. Submit atomically creates one child Revision, carries unchanged technical/joint content as server facts, marks impact/assessment/confirmations stale, and follows the returned new integer Revision ref. A 409 parent/hash conflict preserves visible edits for explicit reapplication but never rebases or overwrites automatically.

`BusinessQuestionThread` targets only a generated development-owned field reference in the exact Revision. Business creates a bounded question with reason, blocking flag, and attachment-version evidence; both sides append immutable typed messages, but author identity/side never enters the request. An answer must come from a server-authorized development-side actor. Only business may resolve with the generated closed decision and exact ETag/version/idempotency key. `ANSWER_ACCEPTED` binds the answer; `REVISION_REQUIRED` follows the server-created child Revision; `WITHDRAWN` requires a reason. Open/answered blocking questions remain visible beside the affected field and prevent development confirmation through server gates. A newer Revision supersedes the old thread rather than silently copying it. This is a structured multi-round record, not a generic chat box.

- [ ] **Step 6: Implement a semantic diff that does not compare rendered text**

```ts
// apps/web/src/modules/requirements/revision-diff.tsx
import type { RevisionComparison } from '@accord/api-client';

type RevisionChange = RevisionComparison['entries'][number];

export function RevisionDiff({ changes }: { changes: readonly RevisionChange[] }) {
  return <table aria-label="需求版本差异"><thead><tr><th>字段</th><th>原版本</th><th>新版本</th><th>影响</th></tr></thead><tbody>{changes.filter((change) => change.kind !== 'unchanged').map((change) => <tr key={change.key}><th>{change.label}</th><td>{String(change.before ?? '无')}</td><td>{String(change.after ?? '无')}</td><td>{change.semantic ? '需要重新确认' : '仅展示变化'}</td></tr>)}</tbody></table>;
}
```

Diff data comes from the server canonical semantic comparison, including attachment version and relationship changes. The client never hashes or decides whether a change invalidates confirmation.

- [ ] **Step 7: Verify ownership, exact routing, questions, and diff accessibility**

Run: `pnpm --filter @accord/web test -- revision-detail.test.tsx business-revision-editor.test.tsx business-question-thread.test.tsx revision-header.test.tsx revision-diff.test.tsx`

Expected: PASS with exact six Requirement revision queries and two BusinessQuestion queries/three mutations; complete generated business/development fixtures render every owned field and provenance category; the business editor reuses Task 9's draft registry with exact `revise_existing` base identity; `revisionNo` and comparison numbers stay positive integers; every revision hash matches `^sha256:[0-9a-f]{64}$`; no opaque revision ID exists; route/projection ownership mismatches conceal as 404; old revisions and opposite-side fields stay read-only; blocking questions remain server gates until business resolution; and the diff table announces semantic re-confirmation from server data. A source scan finds no Agent Context operation import, handwritten API URL, response DTO/interface, dynamic href, direct formal-Revision mutation, client-side hash/diff decision, or feature-level generated-client import outside the Requirement-owned registries.

- [ ] **Step 8: Commit the revision-view checkpoint**

```bash
git add apps/web/src/modules/requirements apps/web/src/routes/requirement-*.tsx packages/testkit/src/fixtures/requirements.ts
git commit -m "feat(web): add revision views and semantic diff"
```

### Task 12: Implement Impact Review, Assessment, Proposal Resolution, And Ordered Confirmation

**Files:**
- Create: `apps/web/src/modules/requirements/development-impact-workbench.tsx`
- Create: `apps/web/src/modules/requirements/requirement-impact-api.ts`
- Test: `apps/web/src/modules/requirements/development-impact-workbench.test.tsx`
- Create: `apps/web/src/modules/assessment/assessment-summary.tsx`
- Create: `apps/web/src/modules/assessment/api.ts`
- Create: `apps/web/src/modules/assessment/assessment-brief.tsx`
- Create: `apps/web/src/modules/assessment/development-human-assessment-form.tsx`
- Create: `apps/web/src/modules/assessment/assessment-override-workbench.tsx`
- Create: `apps/web/src/modules/assessment/assessment-summary.test.tsx`
- Test: `apps/web/src/modules/assessment/assessment-brief.test.tsx`
- Test: `apps/web/src/modules/assessment/development-human-assessment-form.test.tsx`
- Test: `apps/web/src/modules/assessment/assessment-override-workbench.test.tsx`
- Create: `apps/web/src/modules/confirmation/confirmation-panel.tsx`
- Create: `apps/web/src/modules/confirmation/fresh-auth.ts`
- Create: `apps/web/src/modules/confirmation/confirmation-panel.test.tsx`
- Test: `apps/web/src/modules/confirmation/fresh-auth.test.ts`
- Create: `apps/web/src/modules/requirements/proposal-workbench.tsx`
- Test: `apps/web/src/modules/requirements/proposal-workbench.test.tsx`
- Create: `packages/testkit/src/fixtures/assessment.ts`
- Modify: `packages/testkit/src/fixtures/index.ts`

- [ ] **Step 1: Write failing assertions for Impact review, score authority, and confirmation order**

```tsx
// apps/web/src/modules/assessment/assessment-summary.test.tsx
import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { AssessmentSummary } from './assessment-summary';
import { assessmentFixture } from '@accord/testkit';

it('shows B and server-provided D without inventing a third total', () => {
  render(<AssessmentSummary assessment={assessmentFixture} />);
  expect(screen.getByText('业务需求 AI 评分')).toBeInTheDocument();
  expect(screen.getByText('开发侧综合分')).toBeInTheDocument();
  expect(screen.getByText(assessmentFixture.development_blended_d.display_formula)).toBeInTheDocument();
  expect(screen.queryByText('最终总分')).not.toBeInTheDocument();
});
```

```tsx
// apps/web/src/modules/confirmation/confirmation-panel.test.tsx
it('does not render business confirmation before the server allows it', () => {
  render(<ConfirmationPanel envelope={developmentPendingEnvelope} />);
  expect(screen.queryByRole('button', { name: '业务确认此版本' })).not.toBeInTheDocument();
  expect(screen.getByText('等待开发侧确认')).toBeInTheDocument();
});
```

```tsx
// apps/web/src/modules/assessment/development-human-assessment-form.test.tsx
it('requires a human score, reason, and evidence for every policy dimension', async () => {
  render(<DevelopmentHumanAssessmentForm envelope={humanAssessmentDraftEnvelope} />);
  await userEvent.type(screen.getByLabelText('技术可行性分数'), '82');
  await userEvent.type(screen.getByLabelText('技术可行性理由'), '现有库存服务可扩展，但需要迁移旧锁记录');
  expect(screen.getByRole('button', { name: '提交开发人工评分' })).toBeDisabled();
  await userEvent.click(screen.getByRole('checkbox', { name: /context-claim:inventory-service/ }));
  expect(screen.getByRole('button', { name: '提交开发人工评分' })).toBeEnabled();
});
```

```tsx
// apps/web/src/modules/assessment/assessment-override-workbench.test.tsx
it('keeps an abnormal AI score nonnumeric and requires explicit override risk facts', () => {
  render(<AssessmentOverrideWorkbench run={abnormalBusinessRun} envelope={overrideRequestEnvelope} />);
  expect(screen.getByText('AI 评分异常')).toBeInTheDocument();
  expect(screen.queryByLabelText('替代分数')).not.toBeInTheDocument();
  expect(screen.getByLabelText('受影响维度')).toBeRequired();
  expect(screen.getByLabelText('补偿控制')).toBeRequired();
  expect(screen.getByLabelText('风险说明')).toBeRequired();
  expect(screen.getByLabelText('到期时间')).toBeRequired();
});
```

```tsx
// apps/web/src/modules/requirements/development-impact-workbench.test.tsx
it('confirms the reviewed Impact Draft into an exact child Revision', async () => {
  const confirm = vi.fn().mockResolvedValue({
    parent_revision_no: 1,
    child_revision_no: 2,
    child_revision_hash: `sha256:${'c'.repeat(64)}`,
  });
  const navigate = vi.fn();
  render(<DevelopmentImpactWorkbench draft={reviewableImpactDraft} onConfirm={confirm} onNavigate={navigate} />);

  const rows = screen.getAllByRole('row', { name: /建议工作项/ });
  expect(rows[0]).toHaveTextContent('库存状态迁移');
  expect(rows[1]).toHaveTextContent('依赖：库存状态迁移');
  await userEvent.click(screen.getByRole('button', { name: '确认影响分析并生成开发版本' }));

  expect(confirm).toHaveBeenCalledWith(expect.objectContaining({
    expected_version: reviewableImpactDraft.version,
    expected_result_digest: reviewableImpactDraft.result_digest,
    development_review_confirmed: true,
  }));
  await waitFor(() => expect(navigate).toHaveBeenCalledWith({
    revision_no: 2,
    revision_hash: `sha256:${'c'.repeat(64)}`,
  }));
  expect(screen.getByText('父版本 1 保持不可变')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run Impact, assessment, and confirmation tests and verify they fail**

Run: `pnpm --filter @accord/web test -- development-impact-workbench.test.tsx assessment-summary.test.tsx development-human-assessment-form.test.tsx assessment-override-workbench.test.tsx confirmation-panel.test.tsx`

Expected: FAIL because the M2 Agent Context client, Impact workbench, assessment, and confirmation modules are absent.

- [ ] **Step 3: Implement development impact review as an exact child-Revision workflow**

This step begins only after Agent Context/Assessment Task 13 has merged its 53-operation OpenAPI overlay and the cumulative generated client has been regenerated. Register the five Agent Context-owned operations once; neither the view nor a feature component imports generated functions directly:

```ts
// apps/web/src/modules/requirements/requirement-impact-api.ts
import {
  confirmRequirementImpactDraft, createRequirementImpactDraft, getRequirementImpactDraft,
  listRequirementImpactDrafts, updateRequirementImpactDraft,
} from '@accord/api-client';

export const requirementImpactQueries = Object.freeze({
  listRequirementImpactDrafts, getRequirementImpactDraft,
});
export const requirementImpactMutations = Object.freeze({
  createRequirementImpactDraft, updateRequirementImpactDraft, confirmRequirementImpactDraft,
});
```

After a business Revision is created, `DevelopmentImpactWorkbench` lists the exact Revision-hash-bound drafts and polls the current generated draft while queued/running. A create/retry button appears only for enabled `createRequirementImpactDraft`; it sends the exact active Context version returned by the server, never a locally selected source baseline. The review renders affected areas, feasibility, suggestions, risks, unknowns, test/acceptance mappings, proposed WorkItems, dependency order, developer questions, Context basis, result digest, evidence refs, stale/current state, and ActionRequests from the generated projection.

Authorized development reviewers edit only the closed development-owned fields in `UpdateRequirementImpactDraftRequest`. Every save uses the draft response ETag, numeric `expected_version`, exact `expected_result_digest`, and one idempotency key per logical edit; a concurrent Context, Requirement, or result change gets a visible 409 diff and never auto-rebases. Business blocks remain read-only and no JSON-pointer patch editor is exposed. Multi-round review is represented by the server's immutable draft versions and audit facts, not a browser-side revision list.

`confirmRequirementImpactDraft` is offered only by its enabled action. It sends `development_review_confirmed=true` and the exact result digest, waits for `ImpactDraftConfirmation`, then navigates to the returned positive child `revision_no` and valid `revision_hash`. The UI does not PATCH a formal Revision, copy the development projection, or declare the parent confirmed. Context stale/rebuild, a blocking unknown, or superseded evidence removes the action through the refreshed server projection. `development-impact-workbench.test.tsx` proves field ownership, proposed WorkItem ordering, ETag/idempotency reuse, stale failure, parent immutability, and exact child-Revision navigation.

- [ ] **Step 4: Render server-provided assessment explanations**

`AssessmentSummary` displays generated `business_ai_b` and `development_blended_d` side by side. Expanding D shows `development_human_h`, `development_ai_a`, `display_formula`, and each generated dimension's score/floor/outcome/reason/evidence. The UI never imports arithmetic helpers. `AssessmentBrief` renders conclusion `建议进入确认`, `补充后再评估`, or `当前不建议开发`, major risks, proposals, accepted unknowns, compensation, and next step. A projection whose status is `overridden` renders `AI 评分异常已批准继续（不等于评分达标）`; its score stays null and the browser never fabricates a replacement B, A, or D.

`DevelopmentHumanAssessmentForm` is the actual input path for `H`, not a read-only score card. It renders the active generated policy's complete ordered development-dimension set and, for each required dimension, a bounded integer `0..100`, a mandatory reason, the configured human floor, and at least one evidence reference selected from the current development projection, Context claims, proposals, attachment metadata, or test/acceptance mapping. It never prepopulates a human score or reason from A/Agent output. Missing dimensions, duplicate keys, out-of-range values, blank reasons, stale policy/revision hashes, and evidence outside the generated selectable projection prevent dispatch. Only an enabled `submitDevelopmentHumanAssessmentRun` action exposes submit; the command sends the generated `DevelopmentHumanAssessmentCommand`, exact `revisionHash`, response ETag, numeric version, and one stable idempotency key, then renders the immutable returned run. A 409 retains the visible entries for deliberate reapplication after refetch but never silently submits them against a newer Revision or policy.

`AssessmentOverrideWorkbench` implements request, approval, rejection, revocation, and history over the existing exact Assessment registries. Request is available only for a generated abnormal B or A run and captures the bound run, score kind, affected dimensions, generated anomaly category, reason, one or more compensating controls, expiry, and risk statement; it exposes no replacement-number field. The requester's account is displayed but never accepted as command input. Approval is absent for the requester and appears only from the server action returned to the correct side's distinct principal. A B override requires a generated manual confirmation row for every affected business dimension, each with `confirmed_meets_floor=true` and a reason; an A override shows the current complete H run and its floors but cannot manufacture an H result. Reject and revoke require reasons and their own exact single-action FreshAuth proofs. Expiry/revocation immediately invalidates dependent readiness and Brief queries. The UI displays `AI 评分异常已批准继续（不等于评分达标）`, leaves B/A and dependent D null where required, and continues to show every non-waivable hard blocker.

```ts
// packages/testkit/src/fixtures/assessment.ts
import type { AssessmentEnvelope } from '@accord/api-client';

type AssessmentSummaryFixture = Pick<AssessmentEnvelope,
  'assessment_version' | 'revision_hash' | 'business_ai_b' | 'development_human_h' |
  'development_ai_a' | 'development_blended_d' | 'gate_predicates' | 'hard_blockers' |
  'validity' | 'allowed_actions' | 'version'>;

export const assessmentFixture = {
  assessment_version: 3,
  revision_hash: `sha256:${'c'.repeat(64)}`,
  business_ai_b: {
    kind: 'business_ai_b', status: 'valid', score: 84, threshold: 70, threshold_outcome: 'pass',
    dimensions: [{ key: 'business_clarity', score: 84, floor: 60, outcome: 'pass', reason: '业务目标、对象和验收预期完整', evidence: ['requirement:block:outcome'] }],
    evidence: ['requirement:block:outcome'], reasons: ['业务目标、对象和验收预期完整']
  },
  development_human_h: {
    kind: 'development_human_h', status: 'valid', score: 80, threshold: 70, threshold_outcome: 'pass',
    dimensions: [{ key: 'feasibility', score: 80, floor: 60, outcome: 'pass', reason: '开发负责人确认改造路径', evidence: ['proposal:0001'] }],
    evidence: ['proposal:0001'], reasons: ['开发人工评分满足门槛']
  },
  development_ai_a: {
    kind: 'development_ai_a', status: 'valid', score: 75, threshold: 70, threshold_outcome: 'pass',
    dimensions: [{ key: 'feasibility', score: 75, floor: 60, outcome: 'pass', reason: '当前 Context 支持改造', evidence: ['context-claim:inventory-service'] }],
    evidence: ['context-claim:inventory-service'], reasons: ['技术依据完整']
  },
  development_blended_d: {
    kind: 'development_blended_d', status: 'valid', score: 78, threshold: 70, threshold_outcome: 'pass',
    dimensions: [{ key: 'feasibility', score: 78, floor: 60, outcome: 'pass', reason: 'H 与 A 均满足门槛', evidence: ['proposal:0001', 'context-claim:inventory-service'] }],
    evidence: ['proposal:0001', 'context-claim:inventory-service'], reasons: ['开发侧综合分满足门槛'],
    human_weight: 0.6, ai_weight: 0.4, display_formula: 'D = 60% H + 40% A'
  },
  gate_predicates: [{ key: 'all_dimension_floors', outcome: 'pass', reason: '全部维度满足下限', evidence: [] }],
  hard_blockers: [],
  validity: 'current', allowed_actions: [], version: 3
} as const satisfies AssessmentSummaryFixture;

// append to packages/testkit/src/fixtures/index.ts
export * from './assessment';
```

- [ ] **Step 5: Register the complete Assessment and collaboration operation sets**

```ts
// apps/web/src/modules/assessment/api.ts
import {
  approveAssessmentOverride, confirmRequirementBusiness, confirmRequirementDevelopment,
  createDevelopmentProposal, generateAssessmentBrief, getAssessmentBrief,
  getAssessmentOverride, getAssessmentRun, getAssessmentVersion,
  getCurrentAssessment, getCurrentAssessmentBrief, getDevelopmentProposal,
  listAssessmentOverrides, listAssessmentRuns, listDevelopmentProposals,
  recalculateAssessment, rejectAssessmentOverride, requestAssessmentOverride,
  resolveDevelopmentProposal, revokeAssessmentOverride, startBusinessAiAssessmentRun,
  startDevelopmentAiAssessmentRun, submitDevelopmentHumanAssessmentRun
} from '@accord/api-client';

export const collaborationQueries = Object.freeze({
  listDevelopmentProposals, getDevelopmentProposal
});
export const collaborationMutations = Object.freeze({
  createDevelopmentProposal, resolveDevelopmentProposal,
  confirmRequirementDevelopment, confirmRequirementBusiness
});
export const assessmentQueries = Object.freeze({
  listAssessmentRuns, getAssessmentRun, getCurrentAssessment, getAssessmentVersion,
  listAssessmentOverrides, getAssessmentOverride, getCurrentAssessmentBrief, getAssessmentBrief
});
export const assessmentMutations = Object.freeze({
  startBusinessAiAssessmentRun, submitDevelopmentHumanAssessmentRun,
  startDevelopmentAiAssessmentRun, recalculateAssessment, requestAssessmentOverride,
  approveAssessmentOverride, rejectAssessmentOverride, revokeAssessmentOverride,
  generateAssessmentBrief
});
```

`ProposalWorkbench` uses only the two collaboration queries and the generated create/resolve mutations. It shows current and proposed values, source evidence, risk, and blocking flag. Accept, partial accept, reject, and request-more are request decisions sent through `resolveDevelopmentProposal` only when that exact enabled operation ID is present. If the receipt contains a new revision ref, navigate to its exact integer revision URL, refetch the old ActionRequest, and show `已产生新需求版本，原确认不迁移`; the browser never attempts a second command to make proposal resolution and ActionRequest completion look atomic.

Assessment pages invoke every lifecycle operation through the two exact registries. `getCurrentAssessment` and all run/override/brief operations receive the immutable `revisionHash` read from the generated Requirement revision response. The visible route continues to use integer `revisionNo`; no adapter converts, truncates, hashes, or substitutes one for the other. AI starts remain async projections, H is entered through the complete human form and becomes an immutable human submission, recalculation names exact B/H/A run or approved override inputs, and history uses `getAssessmentVersion`/`getAssessmentBrief` rather than overwriting current cache entries.

- [ ] **Step 6: Implement ordered confirmation and one-step fresh auth**

```ts
// apps/web/src/modules/confirmation/fresh-auth.ts
import type { AllowedAction } from '@accord/api-client';
export interface FreshAuthGateway {
  ensureWindow(action: AllowedAction): Promise<string>;
  ensureSingleAction(action: AllowedAction): Promise<string>;
}
export async function authTokenFor(action: AllowedAction, gateway: FreshAuthGateway): Promise<string | null> {
  if (action.fresh_auth === 'none') return null;
  if (!action.enabled || !action.opaque_binding) throw new Error('Fresh authentication action binding is unavailable');
  return action.fresh_auth === 'window' ? gateway.ensureWindow(action) : gateway.ensureSingleAction(action);
}
```

`ConfirmationPanel` renders exact revision hash, semantic diff, Assessment Brief digest, Access Preflight, unresolved gates, and the facts included in the single signature. It offers development or business confirmation only when `allowed_actions` contains an enabled `confirmRequirementDevelopment` or `confirmRequirementBusiness` `operation_id`; labels/keys alone cannot dispatch. Both generated confirmation operations receive integer `revisionNo` as their path argument and the exact revision hash in the generated request. Window and single-action step-up both start from that exact enabled action's server-minted `opaque_binding`; the browser never starts an unbound FreshAuth session. A window action performs one step-up at final submit and may be reused only within the server-returned scope/window, while a single-action assessment override never reuses the window or a proof issued for another operation/resource. The mutation sends fresh-auth token, numeric expected version, expected hash, quoted response ETag, and a stable idempotency key, then waits for `ConfirmationReceipt` before showing success. The second business confirmation remains absent until the server returns it after a durable development receipt.

An abnormal B/A run exposes no numeric substitute. The UI may invoke `requestAssessmentOverride` and, for a distinct authorized principal receiving the exact action, `approveAssessmentOverride`; approval displays `score_exception_accepted` and still leaves affected score/D null. Reject/revoke are likewise exact generated mutations. Brief generation starts only after a current assessment version exists, polling occurs through `getCurrentAssessmentBrief`/`getAssessmentBrief`, and a superseded brief is visibly historical rather than silently reused.

- [ ] **Step 7: Test impact, anomalies, sequencing, fresh auth, and stale hashes**

Run: `pnpm --filter @accord/web test -- development-impact-workbench.test.tsx assessment-summary.test.tsx assessment-brief.test.tsx development-human-assessment-form.test.tsx assessment-override-workbench.test.tsx proposal-workbench.test.tsx confirmation-panel.test.tsx fresh-auth.test.ts`

Expected: PASS with exact two-query/three-mutation Impact Draft, two-query/four-mutation collaboration, and eight-query/nine-mutation Assessment registries; Impact confirmation follows the returned positive child Revision/hash while the parent remains immutable, proposed WorkItem dependencies preserve server order, and stale Context/result removes confirmation; fixtures expose generated B/H/A/D names and numeric versions; no third score exists; H cannot submit until every policy dimension has a bounded human score, human reason, and current evidence; abnormal/overridden B or A and dependent D remain null; override request captures run/dimensions/anomaly/reason/controls/expiry/risk, requester self-approval is absent, and B approval requires every generated manual dimension confirmation; integer `revisionNo` is used only by Requirement confirmation while Impact and Assessment bind exact `revisionHash` values; business confirmation is absent until allowed; normal confirmation invokes one window challenge; override invokes one resource/operation-bound single-action challenge; stale inputs/409 disable and refetch without a success toast; and history remains addressable. A source scan finds no pre-M2 Impact import in Task 11, handwritten API URL, server DTO/interface, arithmetic recomputation, dynamic href, client-fabricated score, direct formal-Revision mutation, or Agent Context generated-function import outside `requirement-impact-api.ts`.

- [ ] **Step 8: Commit the impact/assessment/confirmation checkpoint**

```bash
git add apps/web/src/modules/assessment apps/web/src/modules/confirmation apps/web/src/modules/requirements/proposal-workbench.tsx apps/web/src/modules/requirements/proposal-workbench.test.tsx apps/web/src/modules/requirements/requirement-impact-api.ts apps/web/src/modules/requirements/development-impact-workbench.tsx apps/web/src/modules/requirements/development-impact-workbench.test.tsx packages/testkit
git commit -m "feat(web): add impact assessment and ordered confirmation"
```

### Task 13: Build Ready Pool And Delivery Batch Control

**Files:**
- Create: `apps/web/src/modules/delivery/ready-pool.tsx`
- Create: `apps/web/src/modules/delivery/batch-workspace.tsx`
- Create: `apps/web/src/modules/delivery/batch-progress.tsx`
- Create: `apps/web/src/modules/delivery/batch-manifest-review.tsx`
- Create: `apps/web/src/modules/delivery/batch-branch-release-panel.tsx`
- Create: `apps/web/src/modules/delivery/delivery-api.ts`
- Create: `apps/web/src/modules/delivery/ready-pool.test.tsx`
- Create: `apps/web/src/modules/delivery/batch-workspace.test.tsx`
- Test: `apps/web/src/modules/delivery/batch-manifest-review.test.tsx`
- Test: `apps/web/src/modules/delivery/batch-branch-release-panel.test.tsx`
- Test: `apps/web/src/modules/delivery/batch-progress.test.tsx`
- Create: `apps/web/src/routes/ready-pool-route.tsx`
- Create: `apps/web/src/routes/batch-route.tsx`

- [ ] **Step 1: Write failing tests for server eligibility and manifest confirmation**

```tsx
// apps/web/src/modules/delivery/ready-pool.test.tsx
it('allows selecting only server-marked eligible revisions', () => {
  render(<ReadyPool rows={[eligibleRevision, { ...eligibleRevision, id: 'rev-held', eligible: false, gate_labels: ['附件不可访问'] }]} allowedActions={[createBatchAction]} />);
  expect(screen.getByRole('checkbox', { name: /库存释放/ })).toBeEnabled();
  expect(screen.getByRole('checkbox', { name: /附件不可访问/ })).toBeDisabled();
});
```

```tsx
// apps/web/src/modules/delivery/batch-workspace.test.tsx
it('shows degraded assurance above normal lifecycle progress', () => {
  render(<BatchWorkspace envelope={degradedBatchEnvelope} />);
  expect(screen.getByRole('alert')).toHaveTextContent('保证已降级');
  expect(screen.getByText('原阶段：开发中')).toBeInTheDocument();
});
```

```tsx
// apps/web/src/modules/delivery/batch-branch-release-panel.test.tsx
it('does not tell development to start until every repository package and branch release is complete', () => {
  const view = render(<BatchBranchReleasePanel batch={partiallyReleasedBatch} packages={packagesByWorkSet} intents={unknownIntents} />);
  expect(screen.getByRole('status')).toHaveTextContent('部分分支已创建，正在核对其余仓库');
  expect(screen.queryByText('可拉取开发')).not.toBeInTheDocument();

  view.rerender(<BatchBranchReleasePanel batch={readyBatchWithRepositoryReleases} packages={packagesByWorkSet} intents={settledIntents} />);
  expect(screen.getByText('可拉取开发')).toBeInTheDocument();
  for (const release of readyBatchWithRepositoryReleases.repository_releases) {
    expect(screen.getByText(release.delivery_ref)).toBeInTheDocument();
    expect(screen.getByText(release.baseline_commit_sha)).toBeInTheDocument();
    expect(screen.getByText(release.baseline_tree_sha)).toBeInTheDocument();
    expect(release.provider_state).toBe('VERIFIED');
  }
});
```

- [ ] **Step 2: Run delivery tests and verify they fail**

Run: `pnpm --filter @accord/web test -- ready-pool.test.tsx batch-workspace.test.tsx batch-branch-release-panel.test.tsx`

Expected: FAIL because delivery components are absent.

- [ ] **Step 3: Implement Ready Pool as a virtualized operational table**

Rows display exact Revision, receipt validity, AssessmentPolicy, Context basis/reuse, risk, dependency, owner, and server gate explanations. Selection is local only for rows whose projection says `eligible: true`; the `创建交付批次` command is rendered from `allowed_actions`. Submitting selected IDs returns an immutable Batch draft. The browser does not revalidate scores, context, dependencies, or the one-active-batch rule.

Create `delivery-api.ts` as a zero-DTO adapter over the generated operation functions. It contains no URL literal and no response interface; a missing export means Git Delivery Task 1 or cumulative generation was not completed:

```ts
// apps/web/src/modules/delivery/delivery-api.ts
import {
  acceptWorkItemAssignment,
  amendDeliveryBatch,
  applyReconciliationRecoveryCommand,
  authorizeEmergencyChange,
  confirmDeliveryBatchManifest,
  consumeBreakGlassGrant,
  createBatchAbortDecision,
  createBreakGlassGrant,
  createDeliveryBatch,
  createEmergencyChange,
  createWorkItemAssignment,
  getCompletionSet,
  getDevelopmentPackage,
  getDeliveryBatch,
  getRepositoryGitEvidence,
  getRepositoryReconciliation,
  issueEmergencyMergeAuthorization,
  issueStrictMergeAuthorization,
  listDeliveryBatchWorkItems,
  listReadyPool,
  releaseDeliveryBatchBranches,
  requestStrictWorkItemMerge,
  requestWorkItemCompletionReview,
  startRepositoryReconciliation,
} from '@accord/api-client';

export const deliveryApi = Object.freeze({
  acceptWorkItemAssignment,
  amendDeliveryBatch,
  applyReconciliationRecoveryCommand,
  authorizeEmergencyChange,
  confirmDeliveryBatchManifest,
  consumeBreakGlassGrant,
  createBatchAbortDecision,
  createBreakGlassGrant,
  createDeliveryBatch,
  createEmergencyChange,
  createWorkItemAssignment,
  getCompletionSet,
  getDevelopmentPackage,
  getDeliveryBatch,
  getRepositoryGitEvidence,
  getRepositoryReconciliation,
  issueEmergencyMergeAuthorization,
  issueStrictMergeAuthorization,
  listDeliveryBatchWorkItems,
  listReadyPool,
  releaseDeliveryBatchBranches,
  requestStrictWorkItemMerge,
  requestWorkItemCompletionReview,
  startRepositoryReconciliation,
});
```

The registry keys must equal all 24 operation IDs in Git Delivery `DeliveryOpenApiContractTest.contracts`; additions or omissions fail architecture tests. Task 13 directly consumes `listReadyPool`, `createDeliveryBatch`, `getDeliveryBatch`, `confirmDeliveryBatchManifest`, `getDevelopmentPackage`, `releaseDeliveryBatchBranches`, `amendDeliveryBatch`, and `createBatchAbortDecision`. Tests mock these generated functions and assert mutations send the resource ETag as quoted `If-Match`, a stable idempotency key for retries, conditional runtime CSRF, FreshAuth only where the generated contract/action requires it, and the generated request body without a tenant field.

- [ ] **Step 4: Implement manifest review and bilateral batch confirmation**

`BatchManifestReview` shows requirement/revision hashes, WorkItems, assignments, Business Acceptance Owner, branch/base SHA, delivery mode, environment, policy/context versions, and changes from the previous manifest. Each side gets one `batch_confirmation` ActionRequest. If any exact fact changes, refetch replaces the manifest and the old confirm command is disabled; no optimistic frozen state is shown.

- [ ] **Step 5: Implement evidence-gated package retrieval and per-repository branch release before developer start**

`BatchBranchReleasePanel` is the explicit bridge between bilateral manifest confirmation and developer start. For every RepositoryWorkSet it loads the generated `DevelopmentPackageView` and displays its manifest, immutable object version, signing attestation, publication time, and short-lived download authorization without exposing an OSS key or pre-signed URL. It renders `releaseDeliveryBatchBranches` only from the exact enabled batch action after both manifest confirmation receipts and every selected WorkSet package are current. Dispatch uses the batch response's quoted ETag, numeric version, exact `effective_manifest_digest`, the ordered selected `repository_work_set_ids`, one stable idempotency key, conditional CSRF, and exactly the generated action's FreshAuth mode; it never sends a Provider repository ID, branch name, commit/tree, object-storage locator, tenant, actor, or desired phase supplied by the browser.

Each returned `ExternalIntentView` is retained only as a non-authoritative progress projection. `QUEUED`/`IN_FLIGHT` shows the affected repository as releasing; `OUTCOME_UNKNOWN` shows `正在核对远端分支结果` and disables a second logical release for that WorkSet. A partial success renders `repository_release_coverage=PARTIAL`, preserves each verified release, and offers only the exact server-returned action for unreleased WorkSets. A 409 refetches the batch; recovery follows generated `getDeliveryBatch`, event invalidation, and the existing reconciliation operations. There is no client-side `mark released`, destructive compensation, or whole-batch success inferred from one repository.

The panel shows `可拉取开发` only when the authoritative batch is `READY`, `repository_release_coverage=COMPLETE`, and every current RepositoryWorkSet has one current `DevelopmentPackageView` plus one generated `RepositoryReleaseProofView`. Each proof must contain the exact WorkSet, Provider installation and opaque RepositoryBinding IDs, effective manifest, capability/package/Provider-fact digests, delivery ref, unchanged baseline commit/tree, `provider_state=VERIFIED`, receipt-bound `provider_observed_at`, and receipt digest. It cross-renders the matching ref fact from generated `getRepositoryGitEvidence` and fails closed on a missing WorkSet, duplicate proof, null field, non-VERIFIED state, or any installation/repository/ref/commit/tree/manifest/capability/package/fact mismatch. The browser displays `provider_observed_at` but never invents a wall-clock TTL; a later contradictory fact makes the server suspend that WorkSet and removes batch readiness. Developers copy the verified zero-diff ref, use native Git to pull it, and obtain the signed Development Package through `getDevelopmentPackage` or `accordctl`; no Requirement, Context, or package document is read from or written to customer Git.

- [ ] **Step 6: Render batch progress and orthogonal assurance correctly**

```tsx
// apps/web/src/modules/delivery/batch-progress.tsx
export interface BatchProgressProps {
  phaseLabel: string; operationalLabel: string; assuranceLabel: string; originalPhaseLabel: string | null;
}
export function BatchProgress(props: BatchProgressProps) {
  const suspended = props.operationalLabel === '已暂停';
  return <section aria-label="交付批次进度">
    <strong role={props.assuranceLabel === '保证已降级' ? 'alert' : undefined}>{props.assuranceLabel}</strong>
    <span>{suspended ? '已暂停' : props.phaseLabel}</span>
    {suspended && props.originalPhaseLabel ? <span>原阶段：{props.originalPhaseLabel}</span> : null}
  </section>;
}
```

The workspace tabs are overview, requirements, WorkItems, Candidate, acceptance, reconciliation, and activity. The progress rail uses server labels; it does not encode transition eligibility.

- [ ] **Step 7: Verify batch selection, confirmation, package/branch-release proof, suspension, and abort presentation**

Run: `pnpm --filter @accord/web test -- ready-pool.test.tsx batch-workspace.test.tsx batch-manifest-review.test.tsx batch-branch-release-panel.test.tsx batch-progress.test.tsx`

Expected: PASS; held revisions cannot be selected, stale manifests cannot confirm, package retrieval and branch release are unavailable before both receipts, one logical WorkSet release reuses one idempotency key, an unknown Provider outcome cannot be retriggered or presented as success, partial cross-Provider release remains `PARTIAL`, and development start remains absent until every WorkSet has an exact current package plus remote ref/baseline commit/tree/manifest/capability/Provider proof. Suspended preserves the original phase, and aborted is read-only with its decision evidence.

- [ ] **Step 8: Commit the delivery-control checkpoint**

```bash
git add apps/web/src/modules/delivery apps/web/src/routes/ready-pool-route.tsx apps/web/src/routes/batch-route.tsx
git commit -m "feat(web): add ready pool and batch control"
```

### Task 14: Add WorkItem, Git, Project Context, And Reconciliation Views

**Files:**
- Create: `apps/web/src/modules/project-context/context-overview.tsx`
- Create: `apps/web/src/modules/project-context/api.ts`
- Create: `apps/web/src/modules/project-context/context-claim-table.tsx`
- Create: `apps/web/src/modules/project-context/context-rebuild.tsx`
- Create: `apps/web/src/modules/project-context/context-patch-timeline.tsx`
- Create: `apps/web/src/modules/project-context/context-correction-workbench.tsx`
- Create: `apps/web/src/modules/project-context/development-annotation-workbench.tsx`
- Create: `apps/web/src/modules/project-context/development-annotation-api.ts`
- Create: `apps/web/src/modules/project-context/context-overview.test.tsx`
- Test: `apps/web/src/modules/project-context/context-claim-table.test.tsx`
- Test: `apps/web/src/modules/project-context/context-rebuild.test.tsx`
- Test: `apps/web/src/modules/project-context/context-patch-timeline.test.tsx`
- Test: `apps/web/src/modules/project-context/context-correction-workbench.test.tsx`
- Test: `apps/web/src/modules/project-context/development-annotation-workbench.test.tsx`
- Create: `apps/web/src/modules/delivery/work-item-table.tsx`
- Create: `apps/web/src/modules/delivery/git-checks-panel.tsx`
- Create: `apps/web/src/modules/delivery/reconciliation-panel.tsx`
- Create: `apps/web/src/modules/delivery/git-checks-panel.test.tsx`
- Test: `apps/web/src/modules/delivery/work-item-table.test.tsx`
- Test: `apps/web/src/modules/delivery/reconciliation-panel.test.tsx`
- Create: `apps/web/src/routes/project-context-route.tsx`

- [ ] **Step 1: Write failing tests for trust wording and merge assurance**

```tsx
// apps/web/src/modules/project-context/context-overview.test.tsx
it('does not claim that the platform read source code', () => {
  render(<ContextOverview projection={contextProjection} />);
  expect(screen.getByText('客户侧生成，CI 证明分析来源')).toBeInTheDocument();
  expect(screen.queryByText('平台已验证源码结论')).not.toBeInTheDocument();
});
```

```tsx
// apps/web/src/modules/delivery/git-checks-panel.test.tsx
it('labels standard bypass as detected rather than physically prevented', () => {
  render(<GitChecksPanel projection={standardBypassProjection} />);
  expect(screen.getByRole('alert')).toHaveTextContent('检测到平台外合并，批次已暂停并等待对账');
  expect(screen.queryByText('已阻止合并')).not.toBeInTheDocument();
});
```

```tsx
// apps/web/src/modules/project-context/context-patch-timeline.test.tsx
const linkedAppliedPatchFixture = {
  ...appliedPatchFixture,
  context_links: [{
    requirement_revision_ref: {
      requirement_id: '00000000-0000-4000-8000-000000000201',
      revision_no: 7,
      revision_hash: `sha256:${'a'.repeat(64)}`,
    },
    work_item_ref: {
      delivery_batch_id: '00000000-0000-4000-8000-000000000301',
      work_item_id: '00000000-0000-4000-8000-000000000302',
      work_item_version: 3,
      contract_digest: `sha256:${'b'.repeat(64)}`,
    },
  }],
} as const;

const unrelatedAppliedPatchFixture = {
  ...appliedPatchFixture,
  patch_id: '00000000-0000-4000-8000-000000000401',
  patch_sequence: 43,
  context_links: [],
  merge_receipt: {
    ...appliedPatchFixture.merge_receipt,
    previous_watermark: 42,
    next_watermark: 43,
  },
} as const;

it('renders exact typed links while shared-branch merge proof controls advancement', () => {
  const view = render(<ContextPatchTimeline patch={validatedPatchFixture} />);
  expect(screen.getByText('已验证，等待共享开发分支合并')).toBeInTheDocument();
  expect(screen.queryByText('画像已更新')).not.toBeInTheDocument();

  view.rerender(<ContextPatchTimeline patch={linkedAppliedPatchFixture} />);
  expect(screen.getByText('画像已更新')).toBeInTheDocument();
  expect(screen.getByText('水位 41 → 42')).toBeInTheDocument();
  expect(screen.getByText(linkedAppliedPatchFixture.merge_receipt.actual_merge_sha)).toBeInTheDocument();
  expect(screen.getByText('00000000-0000-4000-8000-000000000201')).toBeInTheDocument();
  expect(screen.getByText('Revision 7')).toBeInTheDocument();
  expect(screen.getByText(`sha256:${'a'.repeat(64)}`)).toBeInTheDocument();
  expect(screen.getByText('00000000-0000-4000-8000-000000000301')).toBeInTheDocument();
  expect(screen.getByText('00000000-0000-4000-8000-000000000302')).toBeInTheDocument();
  expect(screen.getByText('版本 3')).toBeInTheDocument();
  expect(screen.getByText(`sha256:${'b'.repeat(64)}`)).toBeInTheDocument();
});

it('accepts empty links for an unrelated merge only with complete receipt and watermark proof', () => {
  const view = render(<ContextPatchTimeline patch={unrelatedAppliedPatchFixture} />);
  expect(screen.getByText('未关联已知需求或工作项')).toBeInTheDocument();
  expect(screen.getByText('画像已更新')).toBeInTheDocument();
  expect(screen.getByText('水位 42 → 43')).toBeInTheDocument();
  expect(screen.queryByText('证明不完整，画像未更新')).not.toBeInTheDocument();

  view.rerender(<ContextPatchTimeline patch={{ ...unrelatedAppliedPatchFixture, merge_receipt: null }} />);
  expect(screen.getByText('未关联已知需求或工作项')).toBeInTheDocument();
  expect(screen.getByText('证明不完整，画像未更新')).toBeInTheDocument();
  expect(screen.queryByText('画像已更新')).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run context/Git tests and verify they fail**

Run: `pnpm --filter @accord/web test -- context-overview.test.tsx context-patch-timeline.test.tsx git-checks-panel.test.tsx`

Expected: FAIL because the modules are absent.

- [ ] **Step 3: Build Project Context projections without source access**

Register the complete Project Context, Patch, and correction surface explicitly; the feature may not import a transport runtime or declare response/request DTOs:

```ts
// apps/web/src/modules/project-context/api.ts
import {
  activateProjectContextVersion, createContextCorrectionSuggestion,
  createProjectContextRebuild, createProjectContextUpload,
  getContextCorrectionSuggestion,
  getProjectContextClaim, getProjectContextOverview, getProjectContextRebuild,
  getProjectContextPatch, getProjectContextStatus, getProjectContextUpload, getProjectContextVersion,
  listContextCorrectionSuggestions, listProjectContextClaims, listProjectContextPatches,
  listProjectContextVersions, resolveContextCorrectionSuggestion,
} from '@accord/api-client';

export const projectContextQueries = Object.freeze({
  getProjectContextOverview, getProjectContextStatus, getProjectContextUpload,
  listProjectContextVersions, getProjectContextVersion,
  listProjectContextClaims, getProjectContextClaim, getProjectContextRebuild,
  listProjectContextPatches, getProjectContextPatch,
  listContextCorrectionSuggestions, getContextCorrectionSuggestion,
});
export const projectContextMutations = Object.freeze({
  createProjectContextUpload, activateProjectContextVersion, createProjectContextRebuild,
  createContextCorrectionSuggestion, resolveContextCorrectionSuggestion,
});
```

The overview displays active version, health (`当前/已过期/需要重建`), analyzed ref/SHA, coverage, unknown/conflict counts, Agent Pack/analyzer versions, and trust badges `结构已校验`, `客户CI已证明来源`, `开发负责人已确认`. The version history is a private subview in `context-overview.tsx` backed by `listProjectContextVersions`/`getProjectContextVersion`. Claims table displays observed/inferred/unknown/conflict, evidence paths/digests, affected requirements, and confidence labels returned by the server. Detail drawers use `getProjectContextClaim`. No operation, prop, fixture, error, or telemetry requests or contains source file bodies, repository credentials, archive bytes, patches, or checkout instructions.

`ContextRebuild` is an ActionRequest-driven workbench. A structured, CI-attested upload invokes `createProjectContextUpload`, progress is read from `getProjectContextUpload` and invalidated by Task 6 events, rebuild starts through `createProjectContextRebuild`, and polling uses `getProjectContextRebuild`. Failures show returned evidence. Activation invokes `activateProjectContextVersion` only when that exact enabled operation ID is returned to a development principal; it uses response ETag, numeric expected version, idempotency, and operation/resource-bound fresh auth. The browser does not invent retry, activation, upload, or job URLs.

- [ ] **Step 4: Build DevelopmentAnnotation, Context correction, and Patch lineage workbenches**

Register all four annotation operations once:

```ts
// apps/web/src/modules/project-context/development-annotation-api.ts
import {
  createDevelopmentAnnotation, getDevelopmentAnnotation,
  listDevelopmentAnnotations, resolveDevelopmentAnnotation,
} from '@accord/api-client';

export const developmentAnnotationQueries = Object.freeze({
  listDevelopmentAnnotations, getDevelopmentAnnotation,
});
export const developmentAnnotationMutations = Object.freeze({
  createDevelopmentAnnotation, resolveDevelopmentAnnotation,
});
```

`DevelopmentAnnotationWorkbench` is available from the exact Requirement Revision and, when applicable, WorkItem. It renders the generated category, blocking flag, expected/observed behavior digests, claim/digest evidence, Pack/analyzer versions, attachment refs, creator display fact, hold/ActionRequest refs, phase, and resolution. UI creation uses only the closed generated input and current ETag/action; it accepts no tenant, actor, source excerpt, diff body, absolute path, or repository URL. A blocking annotation displays the returned hold and waiting owner. Resolution is limited to generated `accepted_for_context_patch`, `requirement_revision_required`, or `rejected`; it never edits a Requirement or active Context directly.

`ContextCorrectionWorkbench` lists and opens suggestions by exact target Context version, claim ID, and expected claim digest. Creation can originate from an annotation or authorized manual review and carries only structured proposed status/statement digest and evidence refs. Acceptance renders the server-created Patch obligation and ActionRequest; `requirement_revision_required` links to the resulting requirement workflow; rejection remains immutable with reason. The UI cannot rewrite a claim, activate a Context version, or mark a correction applied.

`ContextPatchTimeline` uses only `listProjectContextPatches`/`getProjectContextPatch`. It renders the generated `context_links` as explanatory lineage facts. An empty array is a valid unrelated protected-branch change and displays `未关联已知需求或工作项`; it is not an error, does not create a placeholder link, and does not prevent an otherwise fully proven Patch from updating Context. Each nonempty row renders the exact typed Requirement ID, positive Revision number/hash and/or Delivery Batch ID, WorkItem ID/version/contract digest returned by the server. The browser never invents a relation, resolves ownership, checks that a WorkItem belongs to a Revision, or treats a link as merge proof; those are authoritative server validations. A missing `context_links` field or an envelope that violates the generated closed schema fails before rendering instead of being normalized to an empty array.

The timeline also renders source and target Context versions, patch sequence, source head and result tree digests, Pack/analyzer versions, `pending → validated → merged_unapplied → applied` timestamps, rejected/orphaned/conflict/superseded alternatives, actual merge SHA, merge outcome, previous/next watermark, receipt/attestation/provider fact digests, validity, and server gate labels. A PR, personal branch, green CI check, `validated` Patch, or nonempty `context_links` array never changes the displayed active Context. Only an actual shared-development-branch merge receipt may reach `merged_unapplied`; only server application with exact-once receipt consumption and the next contiguous watermark may reach `applied` and update the overview. Linked and unrelated Patches use this identical proof path. Missing sequence/receipt/watermark/consumption fields fail closed to `证明不完整，画像未更新`.

- [ ] **Step 5: Build WorkItem and Git checks around external evidence**

`WorkItemTable` shows assignment, branch, PR, tests, Context Patch/no-context-change, completion receipt, block reason, and latest batch lineage. Its Patch link opens the exact generated `ContextPatchTimeline`; it never labels the Context updated from a PR check alone. `GitChecksPanel` shows required checks, actual provider result, branch protection attestation, merge actor, and standard/strict guarantee. External URLs use `rel="noopener noreferrer"`; commit author is displayed as metadata, never as the verified responsible identity.

These views call only Task 13's `deliveryApi`: `listDeliveryBatchWorkItems`, `createWorkItemAssignment`, `acceptWorkItemAssignment`, `requestWorkItemCompletionReview`, `requestStrictWorkItemMerge`, `getRepositoryGitEvidence`, and `issueStrictMergeAuthorization`. WorkItem strict mode uses only `requestStrictWorkItemMerge` and binds the returned assignment/provider facts; it must not reuse the accepted-Candidate batch authorization operation. `issueStrictMergeAuthorization` is shown only for the accepted Delivery Candidate/batch action defined by Git Delivery. A command button is rendered only when that exact enabled operation ID appears in the response's `allowed_actions`; all strict commands use operation/resource-bound fresh auth, ETag, numeric expected version, stable idempotency, and conditional CSRF. The adapter never synthesizes a strict authorization or completion action and never executes `command_href`.

- [ ] **Step 6: Implement reconciliation as an evidence comparison**

`ReconciliationPanel` compares intended ref/head/tree/patch watermark/artifact with provider-observed values and displays `converged`, `reconciling`, or `diverged` from the server. Its primary action comes from `reconciliation` or `emergency_authorization`; no generic `mark resolved` button exists.

Its query/mutations are exactly `getRepositoryReconciliation`, `startRepositoryReconciliation`, and `applyReconciliationRecoveryCommand`. Emergency and break-glass ActionRequest details dispatch `createEmergencyChange`, `authorizeEmergencyChange`, `issueEmergencyMergeAuthorization`, `createBreakGlassGrant`, or `consumeBreakGlassGrant` from the same generated adapter only when returned by `allowed_actions`. Strict emergency merge uses the dedicated emergency operation and cannot reuse `issueStrictMergeAuthorization` or the WorkItem operation. Unit tests reject `SET_ACTIVE`, arbitrary state payloads, an operation ID outside the exact 24-operation registry, or a handwritten API string anywhere under `src/modules/delivery`.

- [ ] **Step 7: Verify context stale behavior, annotation routing, Patch lineage, and assurance labels**

Run: `pnpm --filter @accord/web test -- context-overview.test.tsx context-claim-table.test.tsx context-rebuild.test.tsx context-patch-timeline.test.tsx context-correction-workbench.test.tsx development-annotation-workbench.test.tsx work-item-table.test.tsx git-checks-panel.test.tsx reconciliation-panel.test.tsx`

Expected: PASS with an exact 12-query/five-mutation Project Context/Patch/correction registry, exact two-query/two-mutation DevelopmentAnnotation registry, and exact 24-operation Git Delivery registry; refresh hydrates overview/version/claim/rebuild/Patch/correction state only through generated operations; stale Context still permits business B but blocks A/D finalization, impact confirmation, development confirmation, and branch release according to server actions; blocking annotations expose holds; correction acceptance creates only a Patch obligation; nonempty `context_links` render exact typed Revision/WorkItem facts, while an empty array renders `未关联已知需求或工作项` without becoming an error; no browser code derives or validates link ownership; no PR/personal-branch/validated Patch advances active Context; linked and unrelated Patches advance only after an applied Patch has a complete actual merge receipt, exact-once consumption, and contiguous watermark; source-bearing fields are rejected; only returned activation is executable; WorkItem, accepted-Candidate, and emergency strict merge use distinct generated operations; strict mode identifies Controller as merger; and standard bypass is labeled detection/recovery. Source scans find no handwritten API URL, `apiFetch`, server DTO/interface, executed `command_href`, generic state mutation, client-derived Patch link/phase/watermark, or source-body field.

- [ ] **Step 8: Commit the context/Git checkpoint**

```bash
git add apps/web/src/modules/project-context apps/web/src/modules/delivery apps/web/src/routes/project-context-route.tsx
git commit -m "feat(web): add context and Git evidence views"
```

### Task 15: Build Candidate, Acceptance, Failure Disposition, And Correction UX

**Files:**
- Create: `apps/web/src/modules/acceptance/candidate-summary.tsx`
- Create: `apps/web/src/modules/acceptance/acceptance-workbench.tsx`
- Create: `apps/web/src/modules/acceptance/api.ts`
- Create: `apps/web/src/modules/acceptance/failure-disposition.tsx`
- Create: `apps/web/src/modules/acceptance/correction-summary.tsx`
- Create: `apps/web/src/modules/acceptance/acceptance-workbench.test.tsx`
- Modify: `apps/web/src/app/router.tsx`
- Test: `apps/web/src/modules/acceptance/failure-disposition.test.tsx`
- Test: `apps/web/src/modules/acceptance/correction-summary.test.tsx`
- Create: `apps/web/src/routes/acceptance-route.tsx`

- [ ] **Step 1: Write a failing test for business-only final acceptance**

```tsx
// apps/web/src/modules/acceptance/acceptance-workbench.test.tsx
it('renders criterion evidence but no final signature without the acceptance action', () => {
  render(<AcceptanceWorkbench envelope={acceptanceWithoutBusinessAction} />);
  expect(screen.getByRole('list', { name: '验收标准' })).toHaveTextContent('库存状态变为可售');
  expect(screen.queryByRole('button', { name: '签署整体验收结果' })).not.toBeInTheDocument();
});

it('registers the exact 12 query and 14 mutation operations from Candidate Task 9', () => {
  expect(Object.keys(acceptanceQueries).sort()).toEqual([
    'getAcceptanceContinuityAssessment', 'getAcceptanceRun', 'getArtifactPromotion',
    'getCorrectionLimitDecision', 'getCorrectionRun', 'getDeliveryCandidate',
    'getDeliveryCandidateEvidence', 'getDeliveryCompletionEvaluation', 'getFailureDisposition',
    'listAcceptanceContinuityAssessments', 'listAcceptanceRuns', 'listDeliveryCandidates'
  ].sort());
  expect(Object.keys(acceptanceMutations).sort()).toEqual([
    'advanceCorrectionRun', 'confirmCorrectionLimitDecision', 'confirmFailureDisposition',
    'createAcceptanceContinuityAssessment', 'createAcceptanceRun', 'createCorrectionRun',
    'linkCorrectionRunWorkItem', 'reconcileArtifactPromotion', 'requestCorrectionLimitDecision',
    'requestFailureDisposition', 'retryArtifactPromotion', 'submitAcceptanceContinuityAttestation',
    'submitAcceptanceRun', 'upsertAcceptanceCriterionDraft'
  ].sort());
});
```

- [ ] **Step 2: Run the acceptance test and verify it fails**

Run: `pnpm --filter @accord/web test -- acceptance-workbench.test.tsx`

Expected: FAIL because acceptance components are absent.

- [ ] **Step 3: Implement an exact Candidate summary**

Candidate summary shows Candidate ID, exact Requirement/revision `ObjectRef` values, repository tree SHA, artifact digest or source-tree-only policy, test/build provenance, environment hash, validity, acceptance-run refs, continuity refs, promotion ref, and acceptance history. Business defaults render the server's bounded labels, criterion expectations, evidence summaries, and attachment metadata; hashes remain copyable in a technical disclosure. It never requests or renders repository source/blob/content, diff/patch, Provider payload, artifact locator, or pre-signed URL. Invalid/expired candidates render no acceptance command even if an old page remains open.

Replace the old `/acceptance/:acceptanceRunId` route with exactly `/t/:tenantId/p/:projectId/delivery/batches/:batchId/candidates/:candidateId/acceptance-runs/:acceptanceRunId`. `acceptance-route.tsx` requires all six parameters and concurrently calls generated `getDeliveryCandidate(projectId, batchId, candidateId)` and `getAcceptanceRun(projectId, batchId, candidateId, acceptanceRunId)`. It verifies response `candidate_id`, `batch_id`, Candidate `object_ref`, AcceptanceRun `candidate_ref`, and route IDs agree before rendering; mismatch becomes the same concealed 404 screen. Candidate navigation obtains IDs only from `acceptance_run_refs` or `listAcceptanceRuns`. A hard refresh therefore hydrates entirely from durable API projections, with no event history, guessed Candidate ID, session storage, or prior TanStack cache.

```tsx
// apps/web/src/routes/acceptance-route.tsx
import { useLoaderData, type LoaderFunctionArgs } from 'react-router';
import { acceptanceQueries } from '../modules/acceptance/api';
import { AcceptanceWorkbench } from '../modules/acceptance/acceptance-workbench';

export async function loader({ params }: LoaderFunctionArgs) {
  const { projectId, batchId, candidateId, acceptanceRunId } = params;
  if (!projectId || !batchId || !candidateId || !acceptanceRunId) throw new Response('Not found', { status: 404 });
  const [candidate, acceptance] = await Promise.all([
    acceptanceQueries.getDeliveryCandidate(projectId, batchId, candidateId),
    acceptanceQueries.getAcceptanceRun(projectId, batchId, candidateId, acceptanceRunId)
  ]);
  if (candidate.body.batch_id !== batchId || candidate.body.candidate_id !== candidateId ||
      candidate.body.object_ref.id !== candidateId || acceptance.body.acceptance_run_id !== acceptanceRunId ||
      acceptance.body.candidate_id !== candidateId || acceptance.body.candidate_ref.id !== candidateId) {
    throw new Response('Not found', { status: 404 });
  }
  return { candidate, acceptance };
}

export function Component() {
  const data = useLoaderData<typeof loader>();
  return <AcceptanceWorkbench candidate={data.candidate} acceptance={data.acceptance} />;
}
```

Task 15 adds that exact relative path under the existing project route in `app/router.tsx`; it does not retain an alias route with only `acceptanceRunId`, because that route cannot prove or hydrate the Candidate/batch ownership chain.

- [ ] **Step 4: Implement the acceptance workbench**

Create the zero-server-DTO adapter below. Every symbol comes from the cumulative generated package, every one of Candidate Task 9's 26 operations is referenced exactly once in a query or mutation registry, and feature code cannot import `apiFetch` or construct a `/v1` URL.

```ts
// apps/web/src/modules/acceptance/api.ts
import {
  advanceCorrectionRun, confirmCorrectionLimitDecision, confirmFailureDisposition,
  createAcceptanceContinuityAssessment, createAcceptanceRun, createCorrectionRun,
  getAcceptanceContinuityAssessment, getAcceptanceRun, getArtifactPromotion,
  getCorrectionLimitDecision, getCorrectionRun, getDeliveryCandidate,
  getDeliveryCandidateEvidence, getDeliveryCompletionEvaluation, getFailureDisposition,
  linkCorrectionRunWorkItem, listAcceptanceContinuityAssessments, listAcceptanceRuns,
  listDeliveryCandidates, reconcileArtifactPromotion, requestCorrectionLimitDecision,
  requestFailureDisposition, retryArtifactPromotion, submitAcceptanceContinuityAttestation,
  submitAcceptanceRun, upsertAcceptanceCriterionDraft,
  type AllowedAction, type ApiResponse
} from '@accord/api-client';

export const acceptanceQueries = Object.freeze({
  listDeliveryCandidates, getDeliveryCandidate, getDeliveryCandidateEvidence,
  listAcceptanceRuns, getAcceptanceRun, getFailureDisposition, getCorrectionRun,
  getCorrectionLimitDecision, listAcceptanceContinuityAssessments,
  getAcceptanceContinuityAssessment, getArtifactPromotion, getDeliveryCompletionEvaluation
});

export const acceptanceMutations = Object.freeze({
  createAcceptanceRun, upsertAcceptanceCriterionDraft, submitAcceptanceRun,
  requestFailureDisposition, confirmFailureDisposition, createCorrectionRun,
  advanceCorrectionRun, linkCorrectionRunWorkItem, requestCorrectionLimitDecision,
  confirmCorrectionLimitDecision, createAcceptanceContinuityAssessment,
  submitAcceptanceContinuityAttestation, retryArtifactPromotion, reconcileArtifactPromotion
});

export const acceptanceFreshAuth = {
  createAcceptanceRun: 'window', upsertAcceptanceCriterionDraft: 'window', submitAcceptanceRun: 'window',
  requestFailureDisposition: 'single_action', confirmFailureDisposition: 'single_action', createCorrectionRun: 'window',
  advanceCorrectionRun: 'window', linkCorrectionRunWorkItem: 'window', requestCorrectionLimitDecision: 'single_action',
  confirmCorrectionLimitDecision: 'single_action', createAcceptanceContinuityAssessment: 'window',
  submitAcceptanceContinuityAttestation: 'single_action', retryArtifactPromotion: 'window',
  reconcileArtifactPromotion: 'window'
} as const satisfies Readonly<Record<keyof typeof acceptanceMutations, 'window' | 'single_action'>>;

export const acceptanceAllowedActionOperation = {
  request_acceptance_run: 'createAcceptanceRun', edit_acceptance_criterion: 'upsertAcceptanceCriterionDraft',
  submit_acceptance_run: 'submitAcceptanceRun', request_failure_disposition: 'requestFailureDisposition',
  confirm_failure_disposition: 'confirmFailureDisposition', create_correction_run: 'createCorrectionRun',
  advance_correction_run: 'advanceCorrectionRun', link_correction_work_item: 'linkCorrectionRunWorkItem',
  request_correction_limit_decision: 'requestCorrectionLimitDecision',
  confirm_correction_limit_decision: 'confirmCorrectionLimitDecision',
  assess_acceptance_continuity: 'createAcceptanceContinuityAssessment',
  attest_acceptance_continuity: 'submitAcceptanceContinuityAttestation',
  retry_artifact_promotion: 'retryArtifactPromotion', reconcile_artifact_promotion: 'reconcileArtifactPromotion'
} as const satisfies Readonly<Record<string, keyof typeof acceptanceMutations>>;

export function requireAllowedOperation(action: AllowedAction): keyof typeof acceptanceMutations {
  const operation = acceptanceAllowedActionOperation[action.key as keyof typeof acceptanceAllowedActionOperation];
  if (!operation) throw new Error('Unsupported acceptance action');
  return operation;
}

export function acceptanceCommandHeaders(response: ApiResponse<unknown>, idempotencyKey: string, freshAuthProof: string) {
  if (!response.etag || !/^"[0-9]+"$/.test(response.etag)) throw new Error('Missing numeric aggregate ETag');
  return { 'If-Match': response.etag, 'Idempotency-Key': idempotencyKey, 'X-Accord-Fresh-Auth': freshAuthProof } as const;
}
```

The three-column desktop layout contains criteria, business expectation/evidence/attachment metadata, and result/evidence controls. Mobile uses sequential sections. `CriterionResultField` is a private component in `acceptance-workbench.tsx`; it derives props from generated `AcceptanceCriterionView` and `UpsertAcceptanceCriterionDraftRequest`, never declares a server interface. Each criterion supports generated `passed`, `failed`, or `unverifiable`, a bounded note, and evidence attachment-version IDs. Autosave constructs `UpsertAcceptanceCriterionDraftRequest` with `expected_version: acceptanceResponse.body.version` as a number and uses the quoted `acceptanceResponse.etag`; no `string` conversion or body/header version drift is permitted. Only an actor receiving `submit_acceptance_run` may sign overall acceptance. Every mutation obtains the exact fresh-auth mode from `acceptanceFreshAuth`, keeps one idempotency key for retries/double clicks, and waits for the immutable server receipt before invalidating queries; the runtime adds the session-bound CSRF header.

- [ ] **Step 5: Implement failure classification and correction continuity**

`FailureDisposition` shows exactly three generated choices with consequences: `实现缺陷` keeps Revision and creates CorrectionRun; `需求变化` follows `successor_revision_ref`; `环境问题` follows `environment_rerun_ref`. It displays both confirmations, next required side, and every `resolved_outcome_ref`; commands exist only when the matching `allowed_actions` entry maps through `acceptanceAllowedActionOperation`.

`CorrectionSummary` renders failed-run/disposition/revision refs, targeted criteria and their failure/evidence summaries, correction WorkItem refs/labels/owners/states, replacement Candidate ref, targeted/full reacceptance scope, reacceptance Run ref, and immutable history. At the configured limit it follows `correction_limit_decision_ref` or the `request_correction_limit_decision` action. The dialog offers exactly `继续缺陷纠正`, `新建需求版本`, `回滚`, and `终止批次`, mapped to generated `continue_correction | new_requirement_revision | rollback | terminate_batch`. Proposal and opposite-side confirmation each require a distinct single-action fresh-auth proof; the second side cannot edit option/scope and the same natural person is never offered both actions. Refresh calls `getCorrectionLimitDecision` and follows `resolved_outcome_refs`, so no option ends in an error-only screen.

The same workspace uses `listAcceptanceContinuityAssessments`/`getAcceptanceContinuityAssessment` to show `unaffected`, `affected`, or `uncertain`, affected criterion refs, evidence metadata, attestation, and targeted reacceptance. It uses `getArtifactPromotion` plus retry/reconcile operations only from returned actions, and `getDeliveryCompletionEvaluation` to render every predicate without a client-side complete command. Candidate/Acceptance/Failure/Correction/limit/continuity/promotion/completion panels all navigate by returned `ObjectRef` values.

- [ ] **Step 6: Verify refresh hydration, generated ownership, and every recovery path**

Run: `pnpm --filter @accord/web test -- acceptance-workbench.test.tsx failure-disposition.test.tsx correction-summary.test.tsx`

Expected: PASS with an exact 12-query/14-mutation registry; the adapter has no handwritten `/v1` string, `apiFetch` import, server DTO/interface, dynamic method, or `Record<string, unknown>` command; a hard refresh at the full batch/Candidate/AcceptanceRun route issues the two exact generated lookups and rejects mismatched refs; invalid Candidate cannot sign; generated criterion `unverifiable` round-trips while `expected_version` remains a number; development-side fixture cannot sign final acceptance; all three failure choices issue distinct generated operations; implementation defect preserves Revision; all four correction-limit options require bilateral distinct-person fresh auth and resume through outcome refs; continuity, promotion, and completion views use their complete generated query/mutation sets. Mutation tests assert quoted response ETag reuse, stable idempotency key, fresh-auth mode, runtime CSRF injection, and no optimistic authoritative transition.

- [ ] **Step 7: Commit the acceptance checkpoint**

```bash
git add apps/web/src/modules/acceptance apps/web/src/routes/acceptance-route.tsx apps/web/src/app/router.tsx
git commit -m "feat(web): add exact candidate acceptance workflow"
```

### Task 16: Deliver Project Setup, Roles, Policies, Audit, And Retention Settings

**Files:**
- Create: `apps/web/src/modules/settings/api.ts`
- Create: `apps/web/src/modules/settings/project-setup-wizard.tsx`
- Create: `apps/web/src/modules/settings/role-preset-step.tsx`
- Create: `apps/web/src/modules/settings/assessment-policy-step.tsx`
- Create: `apps/web/src/modules/settings/repository-step.tsx`
- Create: `apps/web/src/modules/settings/provider-onboarding-api.ts`
- Create: `apps/web/src/modules/settings/provider-connections-panel.tsx`
- Create: `apps/web/src/modules/settings/agent-pack-step.tsx`
- Create: `apps/web/src/modules/settings/agent-pack-api.ts`
- Create: `apps/web/src/modules/settings/external-supplier-panel.tsx`
- Create: `apps/web/src/modules/settings/project-setup-wizard.test.tsx`
- Test: `apps/web/src/modules/settings/role-preset-step.test.tsx`
- Test: `apps/web/src/modules/settings/assessment-policy-step.test.tsx`
- Test: `apps/web/src/modules/settings/provider-connections-panel.test.tsx`
- Test: `apps/web/src/modules/settings/agent-pack-step.test.tsx`
- Test: `apps/web/src/modules/settings/external-supplier-panel.test.tsx`
- Create: `apps/web/src/shared/security/fresh-auth-api.ts`
- Test: `apps/web/src/shared/security/fresh-auth-api.test.ts`
- Create: `apps/web/src/routes/fresh-auth-callback-route.tsx`
- Test: `apps/web/src/routes/fresh-auth-callback-route.test.tsx`
- Modify: `apps/web/src/app/router.tsx`
- Create: `apps/web/src/modules/audit/api.ts`
- Create: `apps/web/src/modules/audit/audit-explorer.tsx`
- Create: `apps/web/src/modules/audit/audit-export.tsx`
- Create: `apps/web/src/modules/audit/retention-policy-panel.tsx`
- Create: `apps/web/src/modules/audit/legal-hold-panel.tsx`
- Create: `apps/web/src/modules/audit/deletion-workbench.tsx`
- Create: `apps/web/src/modules/audit/audit-explorer.test.tsx`
- Test: `apps/web/src/modules/audit/audit-export.test.tsx`
- Test: `apps/web/src/modules/audit/retention-policy-panel.test.tsx`
- Test: `apps/web/src/modules/audit/legal-hold-panel.test.tsx`
- Test: `apps/web/src/modules/audit/deletion-workbench.test.tsx`
- Create: `apps/web/src/routes/settings-route.tsx`
- Create: `apps/web/src/routes/audit-route.tsx`

- [ ] **Step 1: Write failing tests for role presets and read-only audit**

```tsx
// apps/web/src/modules/settings/project-setup-wizard.test.tsx
it('requires one principal per side and keeps strict final accounts distinct', async () => {
  render(<ProjectSetupWizard projection={strictSetupProjection} />);
  await userEvent.click(screen.getByRole('radio', { name: '精简团队' }));
  expect(screen.getByLabelText('业务侧最高负责人')).toBeRequired();
  expect(screen.getByLabelText('开发侧最高负责人')).toBeRequired();
  expect(screen.getByRole('button', { name: '确认项目设置' })).toBeDisabled();
  expect(screen.getByText('严格交付要求两侧最终确认来自不同账号')).toBeInTheDocument();
});

it('allows policy-approved same-side role overlap without removing either principal', async () => {
  render(<RolePresetStep projection={strictSmallTeamProjection} />);
  expect(screen.getByText('项目管理员 / 开发侧最高负责人 / 开发评估')).toHaveTextContent('周明');
  expect(screen.getByLabelText('业务侧最高负责人')).toHaveValue('account-business');
  expect(screen.getByLabelText('开发侧最高负责人')).toHaveValue('account-development');
  expect(screen.getByText('两侧最终确认必须由不同自然人完成')).toBeInTheDocument();
});

it('does not treat administrator status as side authority and labels reduced standard assurance', () => {
  const view = render(<RolePresetStep projection={adminWithoutSideBindingProjection} />);
  expect(screen.queryByRole('button', { name: /业务确认|开发确认|人工评分|批准例外/ })).not.toBeInTheDocument();

  view.rerender(<RolePresetStep projection={reducedStandardProjection} />);
  expect(screen.getByRole('alert')).toHaveTextContent('保证已降级');
  expect(screen.getByText('未达到严格职责分离')).toBeInTheDocument();
  expect(screen.getByLabelText('业务侧最高负责人')).toBeRequired();
  expect(screen.getByLabelText('开发侧最高负责人')).toBeRequired();
});

it('binds only an opaque discovered repository and waits for trust registration', async () => {
  render(<ProviderConnectionsPanel installations={activeInstallations} discoveries={repositoryDiscoveries} />);
  await userEvent.click(screen.getByRole('button', { name: '绑定到当前项目' }));
  expect(providerOnboardingMutations.createProjectRepositoryBinding).toHaveBeenCalledWith(
    expect.objectContaining({ repository_discovery_id: repositoryDiscoveries[0].repository_discovery_id })
  );
  const request = providerOnboardingMutations.createProjectRepositoryBinding.mock.calls[0][0];
  expect(request).not.toHaveProperty('repository_id');
  expect(request).not.toHaveProperty('endpoint');
  expect(screen.getByRole('status')).toHaveTextContent('正在验证仓库身份和能力');
  expect(screen.queryByRole('checkbox', { name: /选择仓库/ })).not.toBeInTheDocument();
});
```

```tsx
// apps/web/src/modules/audit/audit-explorer.test.tsx
it('offers export but never an edit or delete control', () => {
  render(<AuditExplorer projection={auditFixture} allowedActions={[auditExportAction]} />);
  expect(screen.getByRole('button', { name: '导出审计报告' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /编辑|删除/ })).not.toBeInTheDocument();
});
```

```tsx
// apps/web/src/modules/audit/deletion-workbench.test.tsx
it('treats an active legal hold and waiting period as server gates', () => {
  render(<DeletionWorkbench envelope={blockedDeletionFixture} />);
  expect(screen.getByRole('status')).toHaveTextContent('法律保全阻止删除');
  expect(screen.getByText(blockedDeletionFixture.deletion_request.waiting_until)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '执行删除' })).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看法律保全' })).toHaveAttribute('href', expect.stringContaining('legal-holds'));
});
```

- [ ] **Step 2: Run setup/audit tests and verify they fail**

Run: `pnpm --filter @accord/web test -- project-setup-wizard.test.tsx provider-connections-panel.test.tsx audit-explorer.test.tsx`

Expected: FAIL because settings and audit modules are absent.

- [ ] **Step 3: Implement the resumable project setup checklist**

Create a closed adapter for all 32 project/setup/identity-lifecycle operations. Every registry entry is a generated function; no settings component imports `apiFetch`, declares a server DTO, or builds a command URL.

```ts
// apps/web/src/modules/settings/api.ts
import {
  activateProjectSetup, confirmProjectSetupBusiness, confirmProjectSetupDevelopment,
  confirmRepositoryBindingChange, createExternalSupplierAssignment,
  createExternalSupplierReassignment, createProject, createProjectDelegation,
  createProjectRoleBinding, createProjectSetup, createRepositoryBindingChangeRequest,
  getProject, listProjectRepositoryBindings,
  getActiveAssessmentPolicy, getAssessmentPolicy,
  getProjectSetup, getProjectSidePrincipals,
  getRepositoryBindingChangeRequest, listExternalSupplierAssignments,
  listAssessmentPolicies, listProjectDelegations, listProjectRoleBindings, listProjectRoleCatalog,
  recommendAssessmentPolicy, createAssessmentPolicy, confirmAssessmentPolicy, revokeAssessmentPolicy,
  replaceProjectSidePrincipal, resumeProjectSetup, revokeExternalSupplierAssignment,
  revokeProjectDelegation, revokeProjectRoleBinding, submitProjectSetup,
  supersedeExternalSupplierAssignment, updateMyProjectNotificationPreferences,
  updateProjectNotificationPolicy, updateProjectSeparationPolicy,
  updateProjectSetupStep, validateProjectSetup
} from '@accord/api-client';

export const projectSetupQueries = Object.freeze({ getProject, getProjectSetup });
export const projectSetupMutations = Object.freeze({
  createProject, createProjectSetup, resumeProjectSetup, updateProjectSetupStep,
  validateProjectSetup, submitProjectSetup, confirmProjectSetupDevelopment,
  confirmProjectSetupBusiness, activateProjectSetup
});
export const repositoryIdentityQueries = Object.freeze({
  listProjectRepositoryBindings, getRepositoryBindingChangeRequest
});
export const repositoryIdentityMutations = Object.freeze({
  createRepositoryBindingChangeRequest, confirmRepositoryBindingChange
});
export const roleAdministrationQueries = Object.freeze({
  listProjectRoleCatalog, listProjectRoleBindings,
  getProjectSidePrincipals
});
export const roleAdministrationMutations = Object.freeze({
  createProjectRoleBinding, revokeProjectRoleBinding,
  replaceProjectSidePrincipal, updateProjectSeparationPolicy
});
export const delegationQueries = Object.freeze({ listProjectDelegations });
export const delegationMutations = Object.freeze({ createProjectDelegation, revokeProjectDelegation });
export const supplierQueries = Object.freeze({
  listExternalSupplierAssignments
});
export const supplierMutations = Object.freeze({
  createExternalSupplierAssignment, supersedeExternalSupplierAssignment,
  revokeExternalSupplierAssignment, createExternalSupplierReassignment
});
export const assessmentPolicyQueries = Object.freeze({
  listAssessmentPolicies, getActiveAssessmentPolicy, getAssessmentPolicy
});
export const assessmentPolicyMutations = Object.freeze({
  recommendAssessmentPolicy, createAssessmentPolicy, confirmAssessmentPolicy, revokeAssessmentPolicy
});
export const notificationMutations = Object.freeze({
  updateProjectNotificationPolicy, updateMyProjectNotificationPreferences
});
export const settingsIdentityOperations = Object.freeze({
  ...projectSetupQueries, ...projectSetupMutations,
  ...repositoryIdentityQueries, ...repositoryIdentityMutations,
  ...roleAdministrationQueries, ...roleAdministrationMutations,
  ...delegationQueries, ...delegationMutations, ...supplierQueries, ...supplierMutations,
  ...notificationMutations
});
```

Keep Provider connection and discovery in its separate generated owner registry:

```ts
// apps/web/src/modules/settings/provider-onboarding-api.ts
import {
  completeProviderConnectionIntent, createProjectRepositoryBinding,
  createProviderConnectionIntent, getProjectRepositoryBindingOnboarding,
  getProviderConnectionIntent, getProviderInstallation,
  listProviderInstallations, listProviderRepositoryDiscoveries,
  refreshProviderRepositoryDiscovery, retryProjectRepositoryBindingOnboarding,
  revokeProviderInstallation, rotateProviderInstallationCredential,
} from '@accord/api-client';

export const providerOnboardingQueries = Object.freeze({
  getProviderConnectionIntent, listProviderInstallations, getProviderInstallation,
  listProviderRepositoryDiscoveries, getProjectRepositoryBindingOnboarding,
});
export const providerOnboardingMutations = Object.freeze({
  createProviderConnectionIntent, completeProviderConnectionIntent,
  rotateProviderInstallationCredential, revokeProviderInstallation,
  refreshProviderRepositoryDiscovery, createProjectRepositoryBinding,
  retryProjectRepositoryBindingOnboarding,
});
export const providerOnboardingOperations = Object.freeze({
  ...providerOnboardingQueries, ...providerOnboardingMutations,
});
```

The registry contains exactly the 12 generated `provider-onboarding` operations; the callback-edge operation is intentionally absent from the client. `ProviderConnectionsPanel` supports the five closed Provider families and their server-returned Cloud/Enterprise authentication choices. Starting an OAuth/App flow navigates only to the exact HTTPS `authorization_uri` returned for the current intent after requiring its origin to equal the same generated intent projection's `authorization_origin`; no Installation is assumed to exist before callback completion, and a mismatch, userinfo, fragment, or non-HTTPS URI fails closed without navigation. Manual enterprise setup transfers credentials through the returned one-time same-origin Credential Broker capability component and never places a token, PAT, key or OAuth code in React state, URL, analytics, error text or browser storage. Completion and refresh use the returned ETag/version, stable idempotency key, exact FreshAuth mode and conditional CSRF.

Repository discovery renders display name, Provider/deployment label, default-ref label, observation time, expiry and capability gates, but its only submitted identity is `repository_discovery_id`. Binding progress hydrates through `getProjectRepositoryBindingOnboarding`; `PENDING_TRUST`, probing, registration and reconciliation remain non-selectable. Only after the server's Identity `listProjectRepositoryBindings` projection returns the same opaque Binding as `ACTIVE` with current trust/registration/capability gates and an exact CapabilitySnapshot-to-installation credential-epoch match may `repository-step.tsx` offer it in project setup. Rotation or revocation immediately removes affected bindings from selectable results and displays the returned reconciliation ActionRequests; the browser never patches a Binding state.

Project creation uses `listCurrentSessionProjects`' quoted collection ETag and generated `createProject`; it never supplies tenant authority. The server-drafted wizard then uses the exact nine setup mutations and two reads. It covers repository binding, support cell, standard/strict mode, `精简团队/职责分离/外包交付` role preset, exactly one current principal per side, Business Acceptance Owner, AssessmentPolicy questions/recommendation, Agent Pack install/lock status, CI proof, notifications, and retention. Step values render the generated discriminator union; the browser cannot submit a free-form object. Each step renders server gates and only an enabled matching `allowed_actions.operation_id`; mutations reuse response ETag/numeric version, keep one idempotency key per retry, and request fresh auth only for operations classified MF by the Identity matrix. Leaving and returning calls `getProjectSetup`; no draft is reconstructed from local form state.

The repository step uses paged `listProjectRepositoryBindings` and submits a nonempty, ordered, duplicate-free set of opaque RepositoryBinding IDs. It renders Provider family, endpoint/deployment label and display name for human disambiguation, but never submits a Provider numeric repository ID, hostname, owner/name, or URL as authority. An empty result links directly to `ProviderConnectionsPanel`; it is not an instruction for an operator to seed a database row or edit local configuration.

`getProject` is the sole read projection for the active separation policy, active notification policy, and the current natural person's notification preferences; there is no generated `getProjectSeparationPolicy` alias. `listExternalSupplierAssignments` carries each assignment's current impact, so the browser has no `getExternalSupplierAssignmentImpact` call. The notification settings surface invokes `updateProjectNotificationPolicy` only for an enabled Project Admin action and `updateMyProjectNotificationPreferences` only for the current natural person, never with tenant/actor/user selectors. It renders all four closed channels, immutable urgent delivery, digest timezone/time, ordered escalation offsets, retry/backoff bounds, paired quiet hours, and the authoritative in-app ActionRequest guarantee. Both commands use the exact returned ETag/version and stable idempotency key, refetch `getProject` on success or `409`, and cannot convert urgent work to a digest or disable the in-app authority record.

Each RepositoryBinding identity tuple is immutable after activation. A change targets one explicit binding and uses only `createRepositoryBindingChangeRequest`, then the exact development-principal `confirmRepositoryBindingChange`; it displays repository-specific Context/reconciliation impact and never directly edits a Provider/repository value. Submission, development confirmation, business confirmation, and activation are separate durable receipts. The UI does not infer readiness from completed controls and does not skip blocked/unavailable dependency gates.

- [ ] **Step 4: Implement signed Agent Pack catalog, installation resources, and fail-closed compatibility**

```ts
// apps/web/src/modules/settings/agent-pack-api.ts
import {
  createAgentPackDownloadCapability, getAgentPackRelease, listAgentPackReleases,
  type AgentPackDownloadCapability,
} from '@accord/api-client';

export const agentPackQueries = Object.freeze({ listAgentPackReleases, getAgentPackRelease });
export const agentPackMutations = Object.freeze({ createAgentPackDownloadCapability });

const REQUIRED_INSTALL_STEPS = [
  'verify_signature', 'verify_digest', 'install_resources', 'verify_lock',
] as const;

export async function consumeAgentPackCapability(
  capability: AgentPackDownloadCapability,
  applicationOrigin: string,
  consume: (url: string, init: RequestInit) => Promise<Response>,
): Promise<Response> {
  const app = new URL(applicationOrigin);
  const declaredOrigin = new URL(capability.capability_origin);
  const url = new URL(capability.capability_url);
  if (app.protocol !== 'https:' || app.origin !== applicationOrigin ||
      declaredOrigin.origin !== applicationOrigin || capability.capability_origin !== applicationOrigin ||
      url.protocol !== 'https:' || url.origin !== applicationOrigin || url.origin !== declaredOrigin.origin ||
      url.username !== '' || url.password !== '' || url.hash !== '') {
    throw new Error('Invalid Agent Pack capability origin');
  }
  if (capability.method !== 'GET' || capability.audience !== 'accord-agent-pack-download' ||
      !['install', 'upgrade', 'rollback'].includes(capability.purpose) ||
      capability.actor_binding.kind !== 'human_session' || capability.maximum_uses !== 1 ||
      !Number.isSafeInteger(capability.distribution_epoch) || capability.distribution_epoch < 1) {
    throw new Error('Invalid Agent Pack capability bounds');
  }
  if (capability.local_install.installer !== 'codex_managed_pack' ||
      capability.local_install.expected_oci_digest !== capability.oci_digest ||
      capability.local_install.required_steps.length !== REQUIRED_INSTALL_STEPS.length ||
      !capability.local_install.required_steps.every((step, index) => step === REQUIRED_INSTALL_STEPS[index])) {
    throw new Error('Invalid Agent Pack local install profile');
  }
  const expiresAt = Date.parse(capability.expires_at);
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now() || expiresAt - Date.now() > 60_000) {
    throw new Error('Agent Pack capability expired or overlong');
  }
  return consume(url.href, {
    method: 'GET', headers: { Accept: 'application/octet-stream' }, credentials: 'include',
    cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer',
  });
}
```

`AgentPackStep` lists project-compatible releases and opens the exact release projection. It renders release/version/state, OCI/manifest/SBOM/signature-envelope digests, signing identity/trust root, each resource path/media type/size/SHA-256/purpose/install mode, Codex/OS/architecture/Requirement-Schema/Context-Schema/analyzer compatibility rows and certification digest, the closed `install_profile` (`installer=codex_managed_pack`, `artifact_transport=one_time_https_capability`, and the ordered four required steps), published time, lock facts, upgrade/rollback notes, and revocation state. It never renders, reconstructs, or copies a shell command, executable/argument array, `install_command_template`, or `verify_command`; the managed installer consumes the structured profile and remains responsible for signature, manifest, every file hash, path/symlink, compatibility, atomic installation, and lock verification.

Resource download is an explicit user action. It calls `createAgentPackDownloadCapability` outside TanStack Query and immediately validates `capability_url`, repeated `capability_origin`, method, purpose, audience, human-session binding kind, OCI/release metadata digests, positive safe-integer `distribution_epoch`, expiry, one-use bound, and closed `local_install` before network access. The browser never compares the epoch with cached/project state or decides whether it is current; only the gateway's issuance-time and atomic consumption-time checks are authoritative. It streams to a user-selected temporary file while incrementally computing SHA-256; only an exact match with `oci_digest` may finalize the file. Digest mismatch, epoch rejection, redirect, 403, expiry, cancellation, or partial I/O truncates/removes the partial output and requires a new capability. A bounded blob fallback is allowed only below the configured maximum byte count and performs the same digest check before download. The raw URL and actor-binding digest are dropped immediately, and the non-secret epoch is not retained after the operation; none enters component state, DOM attributes, Query cache, routes, storage, clipboard, fixtures, logs, RUM, errors, or persisted mutations. The UI may render the non-secret expected digest and closed install profile/spec; clicking download alone never marks a Pack installed or verified.

Incompatible, experimental-for-a-required-unit, revoked, signature-invalid, or missing-resource releases expose no download/install/lock completion action. Upgrade and rollback notes lead to customer-reviewed changes to the managed Pack block/directory/lock; the platform browser never edits or pushes customer files and never claims an online hot swap. Project setup becomes ready only when the refreshed generated setup projection contains matching `agent-pack.lock`, customer-CI proof, compatibility, and validity facts. Every Pack fixture uses `satisfies AgentPackRelease` or `satisfies AgentPackDownloadCapability` from `@accord/api-client` without a cast, so omission/optionality/type drift of `distribution_epoch` fails Web typecheck. `agent-pack-step.test.tsx` proves incompatible/revoked fail-closed behavior, the exact `install_profile` and `local_install` four-step sequence, required generated `distribution_epoch`, rejection of missing/zero/non-integer epochs before fetch, full resource/hash/signature rendering, HTTPS same-origin single-use capability enforcement, OCI digest verification/partial-output removal, capability non-persistence, and the distinction between download intent and verified installation.

- [ ] **Step 5: Implement threshold explanation and bilateral policy confirmation**

The assessment step uses the exact three-query/four-mutation `assessmentPolicyQueries`/`assessmentPolicyMutations` registry from Agent Context. It shows `快速迭代 65`, `平衡治理 75`, `强控制 85`, and custom values with approved plain-language explanations, dimensions/floors, hard blockers, high-risk categories, and Agent rationale. During creation/import it asks the bounded project questionnaire and calls `recommendAssessmentPolicy`; custom configuration calls `createAssessmentPolicy`. Both operations create a version rather than mutating active policy. The distinct business/development confirmations each call `confirmAssessmentPolicy` only from returned actions; revocation calls `revokeAssessmentPolicy` with both bound receipts and fresh auth. No project becomes ready based on local form completion, a client-calculated threshold, or one side's confirmation alone.

- [ ] **Step 6: Implement role/delegation and supplier lifecycle views**

Role pages load the generated role catalog, bindings, current side principals, and separation policy through the four queries. Bindings show side, role, scope, source preset, effective time, and allowed action summary. Create/revoke/replace/policy mutations are offered only by exact operations. Normal and strict modes both require one explicit current highest principal for each side; no ordinary member inherits authority because a role is vacant. Strict mode permits only policy-returned same-side overlap, so an administrator or same-side principal may hold project management, assessment, approval, and acceptance roles without duplicate confirmation of the same fact. Administrator status by itself never exposes a side's scoring, exception, confirmation, or acceptance action. Cross-side principals remain explicit and strict final confirmations remain distinct-natural-person actions. Standard mode also defaults to cross-account separation; when the tenant policy explicitly permits the same natural person across sides, every role/setup/confirmation view renders `保证已降级` and `未达到严格职责分离` as an alert and never uses a strict-assurance label. `replaceProjectSidePrincipal` is the only principal replacement path; removing the last principal is never simulated locally.

Delegation uses the one query and two generated mutations. It requires delegate, side, scope, bounded actions, start/end, and reason; no re-delegation, chained delegation, scope widening, or generic permission editor exists. External supplier pages use both supplier queries and four mutations. They require an internal sponsor and expiry, fetch exact impact before supersede/revoke/reassign, show pending expiry and affected WorkItems/ActionRequests, and bind reassignment to the returned impact digest. Supplier sessions cannot enumerate unassigned members/domains, and the UI never broadens visibility to populate a picker.

- [ ] **Step 7: Implement the shared FreshAuth gateway and immutable governance adapters**

```ts
// apps/web/src/shared/security/fresh-auth-api.ts
import {
  completeFreshAuthentication, getFreshAuthentication, startFreshAuthentication
} from '@accord/api-client';

export const freshAuthQueries = Object.freeze({ getFreshAuthentication });
export const freshAuthMutations = Object.freeze({
  startFreshAuthentication, completeFreshAuthentication
});
export const freshAuthOperations = Object.freeze({ ...freshAuthQueries, ...freshAuthMutations });
```

The gateway starts only from an enabled generated `AllowedAction.opaque_binding`; it never sends an action digest, operation/path, tenant, actor, or client-authored authorization scope. OIDC reauthentication validates the returned HTTPS `authorize_uri` against the configured issuer and permits exactly one in-flight callback transaction per tab. Before full-document navigation it writes one bounded `accord:fresh-auth:pending` session-storage record containing only the generated challenge ID, numeric challenge version, quoted response ETag, stable idempotency key, expiry, and encrypted one-time `callback_binding`; it stores no authorization code, callback state, PKCE verifier, action binding, tenant, actor, command URL, or proof. A second start is rejected until the record is consumed, explicitly cancelled, or expires. WebAuthn stays in memory and never creates this record.

`fresh-auth-callback-route.tsx` runs before application telemetry. It extracts bounded code/state exactly once, synchronously replaces the browser URL with the query-free callback path, atomically reads and deletes the single pending record, and rejects missing, duplicate, expired, malformed, or non-numeric-ETag state. Because a full OIDC round trip creates a new JavaScript document and loses Task 4's in-memory CSRF context, the callback must first call `loadSessionAuthority()`/generated `getCurrentSession` to validate the still-current cookie session and bind its original CSRF token. Only then may it call generated `completeFreshAuthentication` with the stored challenge ID/version/ETag/idempotency key plus the returned code/state and encrypted binding. On success it immediately clears the old security context and calls `loadSessionAuthority()` again; only this second `getCurrentSession` may bind the server-rotated session/CSRF token. Either bootstrap failure, completion failure, or post-rotation refresh failure clears security context and leaves a terminal retry/login view with no mutation authority. Callback code/state/binding and proof IDs never enter Query cache, analytics, RUM, error bodies, `localStorage`, or application logs. Proof IDs remain operation/resource-bound and are never reused across commands.

Task 16 adds `{ path: '/fresh-auth/callback', lazy: () => import('../routes/fresh-auth-callback-route') }` as a top-level router entry. It is intentionally added here, when the callback module exists, so Task 4's earlier production build remains independently green.

```ts
// apps/web/src/modules/audit/api.ts
import {
  activateRetentionPolicyVersion, approveProjectDeletionRequest,
  approveTenantDeletionRequest, cancelProjectDeletionRequest,
  cancelTenantDeletionRequest, createProjectAuditExport,
  createProjectAuditExportDownloadCapability, createProjectDeletionRequest,
  createRetentionPolicyVersion, createTenantAuditExport,
  createTenantAuditExportDownloadCapability, createTenantDeletionRequest,
  executeProjectDeletionRequest, executeTenantDeletionRequest,
  getActiveRetentionPolicy, getProjectAuditEvent, getProjectAuditExport,
  getProjectDeletionProof, getProjectDeletionRequest, getTenantAuditEvent,
  getTenantAuditExport, getTenantDeletionProof, getTenantDeletionRequest,
  listProjectLegalHolds, listRetentionPolicyVersions, listTenantLegalHolds,
  placeProjectLegalHold, placeTenantLegalHold, queryProjectAuditEvents,
  queryTenantAuditEvents, releaseProjectLegalHold, releaseTenantLegalHold,
  type AuditDownloadCapabilityEnvelope
} from '@accord/api-client';

export const auditQueries = Object.freeze({
  queryTenantAuditEvents, getTenantAuditEvent, queryProjectAuditEvents,
  getProjectAuditEvent, getTenantAuditExport, getProjectAuditExport
});
export const auditMutations = Object.freeze({
  createTenantAuditExport, createTenantAuditExportDownloadCapability,
  createProjectAuditExport, createProjectAuditExportDownloadCapability
});
export const retentionQueries = Object.freeze({
  listRetentionPolicyVersions, getActiveRetentionPolicy
});
export const retentionMutations = Object.freeze({
  createRetentionPolicyVersion, activateRetentionPolicyVersion
});
export const legalHoldQueries = Object.freeze({ listTenantLegalHolds, listProjectLegalHolds });
export const legalHoldMutations = Object.freeze({
  placeTenantLegalHold, releaseTenantLegalHold,
  placeProjectLegalHold, releaseProjectLegalHold
});
export const deletionQueries = Object.freeze({
  getTenantDeletionRequest, getTenantDeletionProof,
  getProjectDeletionRequest, getProjectDeletionProof
});
export const deletionMutations = Object.freeze({
  createTenantDeletionRequest, cancelTenantDeletionRequest,
  approveTenantDeletionRequest, executeTenantDeletionRequest,
  createProjectDeletionRequest, cancelProjectDeletionRequest,
  approveProjectDeletionRequest, executeProjectDeletionRequest
});
export const governanceOperations = Object.freeze({
  ...auditQueries, ...auditMutations, ...retentionQueries, ...retentionMutations,
  ...legalHoldQueries, ...legalHoldMutations, ...deletionQueries, ...deletionMutations
});

export async function consumeAuditCapability(
  envelope: AuditDownloadCapabilityEnvelope,
  consume: (url: string, init: RequestInit) => Promise<Response>
) {
  const capability = envelope.capability;
  if (Date.parse(capability.expires_at) <= Date.now()) throw new Error('Audit capability expired');
  const applicationOrigin = globalThis.location?.origin;
  if (!applicationOrigin || applicationOrigin === 'null') throw new Error('Application origin is unavailable');
  const url = new URL(capability.download_uri, applicationOrigin);
  if (url.protocol !== 'https:' || url.origin !== applicationOrigin) {
    throw new Error('Audit capability must use the application HTTPS origin');
  }
  return consume(url.href, {
    method: 'GET', credentials: 'include', cache: 'no-store',
    redirect: 'error', referrerPolicy: 'no-referrer'
  });
}
```

Audit filters include actor, object, action, time, result, risk, and correlation ID, mapped only to generated bounded query parameters. Tenant queries have no tenant argument; project queries receive only the route project ID. Event detail shows before/after object refs, receipt/attestation digests, causation chain, and retention state without sensitive bodies. Export invokes the exact tenant/project create operation from `allowed_actions`, polls the matching generated get operation, and requests a download capability only after a user click. Consume that response immediately outside Query cache, require the exact application HTTPS origin, include the bound browser session required by the download gateway, reject redirects, stream the response to disk, and drop the URI on close/403/expiry. The URI never enters DOM attributes, state, routes, storage, logs, telemetry, fixtures, or persisted mutations. `audit-export.test.tsx` proves a cross-origin/HTTP URI never reaches `fetch`, a same-origin request uses `credentials: 'include'` plus `redirect: 'error'`, and replay/expiry requires a newly authorized capability. No client-side CSV assembled from partial pages is labeled authoritative.

- [ ] **Step 8: Implement retention, legal-hold, and deletion lifecycle views**

`RetentionPolicyPanel` reads immutable policy history and the active version through the two generated queries. Creation displays category durations, customer-managed exclusions, affected object counts, active holds, minimum/legal constraints, and the server impact digest; activation is a distinct generated mutation. Both mutations require the exact MF action, resource ETag, numeric version, stable idempotency key, and one-use FreshAuth. The UI never mutates an active row, shortens a hold, computes purge dates as authority, or represents a draft as active.

`LegalHoldPanel` has explicit tenant and project modes. Each mode lists only through its scoped generated query and uses its two matching place/release operations; project IDs are path arguments, while current-tenant operations accept no tenant input. It renders scope, reason, issuer, effective/release facts, protected categories, linked deletion requests, and immutable receipts. Place/release requires returned MF actions. The browser cannot release by deleting a row, move a hold between scopes, or hide a hold from deletion eligibility.

`DeletionWorkbench` implements both six-operation lifecycles without aliases. It renders requested, waiting-period, blocked-by-hold, approved, executing, failed, cancelled, and completed server states; exact inventory/manifest digests; required distinct-person approval; earliest execution time; provider progress; exclusions; and signed proof/anchor metadata. Create/cancel/approve/execute exist only when their exact generated MF operation is enabled. Countdown text is informational; `execute*DeletionRequest` is never enabled from the browser clock. A new/active hold removes the command after refetch, 409 refreshes all facts, and completion follows `get*DeletionProof`. Project deletion cannot target a project in the body; tenant deletion has no tenant argument. Completed tenant deletion clears session/cache and moves to a terminal signed-proof screen without trying to query erased data.

- [ ] **Step 9: Verify Provider onboarding, wizard resume, Pack supply chain, governance, and exact operation ownership**

Run: `pnpm --filter @accord/web test -- project-setup-wizard.test.tsx provider-connections-panel.test.tsx role-preset-step.test.tsx agent-pack-step.test.tsx assessment-policy-step.test.tsx external-supplier-panel.test.tsx fresh-auth-api.test.ts fresh-auth-callback-route.test.tsx audit-explorer.test.tsx audit-export.test.tsx retention-policy-panel.test.tsx legal-hold-panel.test.tsx deletion-workbench.test.tsx`

Expected: PASS with Task 4/16 registering exactly the Identity-owned generated operation set plus exact 12-operation Provider Onboarding, three-operation Agent Pack and seven-operation AssessmentPolicy registries. Architecture tests compare sorted keys to backend owner maps and reject aliases, missing/extra operations, callback-edge functions in the browser client, `apiFetch`, handwritten API URLs/DTOs/enums, tenant/actor/Provider-native identity inputs, dynamic methods/hrefs, secret/code fields and non-generated mutation bodies. Provider tests cover all five families, one-time connection/callback/manual-capability flows, self-managed endpoint labels, opaque discovery, pending/reconciling non-selection, current trust/registration/unexpired-capability activation with an exact installation credential-epoch match, rotation/revocation and refresh recovery without browser-held credentials. Pack tests cover resource inventory, digests/signature/trust root, compatibility matrix, closed `install_profile`/`local_install` structures with no shell-command field, required positive generated `distribution_epoch` without client freshness decisions, upgrade/rollback/revocation, same-origin HTTPS one-use download capability, streamed OCI digest verification with partial-output removal, and verified-lock readiness; download intent never becomes installation proof. Strict mode permits configured same-side administrator/principal overlap while retaining both explicit side principals and distinct-natural-person final confirmation; administrator-only fixtures expose no side authority; reduced Standard fixtures visibly downgrade assurance. Setup drafts resume; repository changes require confirmation/reconciliation; supplier expiry links exact impact/reassignment; a cold OIDC callback proves pre-completion session/CSRF bootstrap, one-use pending-record deletion, completion, old-context clearing, and post-rotation session revalidation in that order; audit remains immutable; capability URIs/epochs never persist or leave the application origin; retention is versioned; legal hold dominates; deletion approval/execution/proof are durable and distinct. Every MF action proves quoted ETag/numeric version equality, stable idempotency, runtime conditional CSRF, operation/resource-bound FreshAuth, 409 refetch, and no optimistic authoritative transition.

- [ ] **Step 10: Commit the administration checkpoint**

```bash
git add apps/web/src/modules/settings apps/web/src/modules/audit apps/web/src/shared/security/fresh-auth-api.ts apps/web/src/shared/security/fresh-auth-api.test.ts apps/web/src/routes/fresh-auth-callback-route.tsx apps/web/src/routes/fresh-auth-callback-route.test.tsx apps/web/src/routes/settings-route.tsx apps/web/src/routes/audit-route.tsx apps/web/src/app/router.tsx
git commit -m "feat(web): add identity and governance administration"
```

### Task 17: Add Privacy-Safe RUM, Runtime Security, And Failure UX

**Files:**
- Create: `apps/web/src/shared/telemetry/redact.ts`
- Create: `apps/web/src/shared/telemetry/rum.ts`
- Create: `apps/web/src/shared/telemetry/redact.test.ts`
- Test: `apps/web/src/shared/telemetry/rum.test.ts`
- Create: `apps/web/src/shared/security/runtime-config.ts`
- Test: `apps/web/src/shared/security/runtime-config.test.ts`
- Create: `apps/web/src/shared/security/sensitive-cache.ts`
- Test: `apps/web/src/shared/security/sensitive-cache.test.ts`
- Create: `apps/web/src/app/job-boundary.tsx`
- Test: `apps/web/src/app/job-boundary.test.tsx`
- Create: `apps/web/src/app/failure-state-gallery.stories.tsx`

- [ ] **Step 1: Write failing allowlist and transport tests that reject identity dimensions**

```ts
// apps/web/src/shared/telemetry/redact.test.ts
import { expect, it } from 'vitest';
import { sanitizeTelemetry } from './redact';

const validEvent = {
  schema_version: 1,
  route_template: '/t/:tenantId/p/:projectId/requirements/:requirementId',
  operation: 'getRequirementRevision',
  status: 409,
  outcome: 'conflict',
  duration_ms: 127.6,
  correlation_id: '018f5f4b-7e6c-7c0b-bb2d-4d6705ae91d4',
} as const;

it('emits only the closed non-identifying event shape', () => {
  expect(sanitizeTelemetry(validEvent)).toEqual({
    ...validEvent,
    duration_ms: 128,
  });
});

it.each(['route', 'pathname', 'tenantId', 'projectId', 'tenant_id', 'project_id', 'tenant_id_hash', 'project_id_hash'])('rejects forbidden field %s instead of deriving or hashing it in the browser', (field) => {
  expect(() => sanitizeTelemetry({ ...validEvent, [field]: 'stable-identity-value' })).toThrow();
});
```

```ts
// apps/web/src/shared/telemetry/rum.test.ts
import { expect, it, vi } from 'vitest';
import { emitRum } from './rum';

it('sends no raw or hashed tenant/project dimension and uses no ambient credential', async () => {
  const send = vi.fn().mockResolvedValue(new Response(null, { status: 202 }));
  const result = await emitRum('https://rum.accord.example/v1/events', {
    schema_version: 1,
    route_template: '/t/:tenantId/p/:projectId/requirements',
    operation: 'listRequirements',
    status: 200,
    outcome: 'success',
    duration_ms: 42,
    correlation_id: '018f5f4b-7e6c-7c0b-bb2d-4d6705ae91d4',
  }, send);

  expect(result).toBe('sent');
  const [, init] = send.mock.calls[0] as [string, RequestInit];
  expect(init.credentials).toBe('omit');
  expect(init.redirect).toBe('error');
  expect(JSON.parse(String(init.body))).toEqual({
    schema_version: 1,
    route_template: '/t/:tenantId/p/:projectId/requirements',
    operation: 'listRequirements',
    status: 200,
    outcome: 'success',
    duration_ms: 42,
    correlation_id: '018f5f4b-7e6c-7c0b-bb2d-4d6705ae91d4',
  });
  expect(String(init.body)).not.toMatch(/tenant_id|project_id|tenant_id_hash|project_id_hash|sha256:/);
});

it('drops an event with a forbidden identity field before transport', async () => {
  const send = vi.fn();
  await expect(emitRum('https://rum.accord.example/v1/events', {
    schema_version: 1,
    route_template: '/t/:tenantId/work',
    correlation_id: '018f5f4b-7e6c-7c0b-bb2d-4d6705ae91d4',
    tenant_id_hash: 'stable-pseudonym-not-allowed',
  }, send)).resolves.toBe('dropped');
  expect(send).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Run the telemetry test and verify it fails**

Run: `pnpm --filter @accord/web test -- redact.test.ts rum.test.ts`

Expected: FAIL because telemetry sanitization and transport are absent.

- [ ] **Step 3: Implement allowlist-only telemetry**

```ts
// apps/web/src/shared/telemetry/redact.ts
import { z } from 'zod';

export const TELEMETRY_ROUTE_TEMPLATES = [
  '/login', '/auth/callback', '/t/:tenantId/work', '/t/:tenantId/work/:actionRequestId',
  '/t/:tenantId/projects', '/t/:tenantId/projects/new', '/t/:tenantId/admin/:section',
  '/t/:tenantId/p/:projectId/overview', '/t/:tenantId/p/:projectId/requirements',
  '/t/:tenantId/p/:projectId/requirements/new',
  '/t/:tenantId/p/:projectId/requirements/:requirementId',
  '/t/:tenantId/p/:projectId/requirements/:requirementId/revisions/:revisionNo/:tab',
  '/t/:tenantId/p/:projectId/requirements/:requirementId/compare',
  '/t/:tenantId/p/:projectId/ready-pool', '/t/:tenantId/p/:projectId/delivery/batches',
  '/t/:tenantId/p/:projectId/delivery/batches/:batchId/:tab',
  '/t/:tenantId/p/:projectId/delivery/batches/:batchId/candidates/:candidateId/acceptance-runs/:acceptanceRunId',
  '/t/:tenantId/p/:projectId/context/:tab', '/t/:tenantId/p/:projectId/attachments',
  '/t/:tenantId/p/:projectId/attachments/:attachmentId/versions/:versionId',
  '/t/:tenantId/p/:projectId/audit', '/t/:tenantId/p/:projectId/settings/:section',
  'unmatched',
] as const;

const rumEventSchema = z.object({
  schema_version: z.literal(1),
  route_template: z.enum(TELEMETRY_ROUTE_TEMPLATES),
  operation: z.string().regex(/^[A-Za-z][A-Za-z0-9]{0,79}$/).optional(),
  status: z.number().int().min(100).max(599).optional(),
  outcome: z.enum(['success', 'failure', 'cancelled', 'timeout', 'conflict', 'degraded']).optional(),
  duration_ms: z.number().finite().min(0).max(600_000).transform(Math.round).optional(),
  correlation_id: z.string().uuid(),
  web_vital: z.object({
    name: z.enum(['CLS', 'INP', 'LCP', 'TTFB']),
    value: z.number().finite().min(0).max(600_000),
    rating: z.enum(['good', 'needs-improvement', 'poor']),
  }).strict().optional(),
}).strict();

export type RumEvent = z.infer<typeof rumEventSchema>;

export function sanitizeTelemetry(input: unknown): RumEvent {
  return rumEventSchema.parse(input);
}
```

```ts
// apps/web/src/shared/telemetry/rum.ts
import { sanitizeTelemetry } from './redact';

type RumTransport = (url: string, init: RequestInit) => Promise<Response>;

export async function emitRum(
  endpoint: string,
  candidate: unknown,
  send: RumTransport = fetch,
): Promise<'sent' | 'dropped'> {
  let event;
  try {
    event = sanitizeTelemetry(candidate);
  } catch {
    return 'dropped';
  }
  try {
    const response = await send(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(event),
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
      keepalive: true,
    });
    return response.ok ? 'sent' : 'dropped';
  } catch {
    return 'dropped';
  }
}
```

Each React Router route carries one static `TELEMETRY_ROUTE_TEMPLATES` value in its route `handle`; the RUM adapter reads that matched handle and never receives `location.pathname`, route parameters, tenant ID, project ID, or any hash of those IDs. Call sites may pass only the route template, generated operation name, HTTP status or closed outcome, bounded duration/Web Vital, and a random opaque UUID correlation ID that encodes no actor or domain identifier. They never pass requirement text, field values, attachment names/bodies, claims, source references, member names, tokens, full URLs, query strings, customer-artifact hashes, API response bodies, assurance labels, or raw/hashed tenant/project fields.

The backend telemetry pipeline may associate an opaque correlation ID with tenant/project context only by joining it to a verified server-owned request trace; it never trusts a browser-supplied identity dimension, and an event without a trustworthy join remains unscoped. Only the backend exporter may add a pseudonymous dimension, using `HMAC-SHA-256` with a KMS-held rotating key and a canonical server-side ID; it records the key epoch separately, never returns the HMAC to the browser, prevents correlation across key epochs by default, and applies the telemetry retention policy. Plain SHA-256 of an identifier is forbidden on both sides. Collector schema validation rejects browser payloads containing raw IDs, `*_hash` fields, unknown keys, or values outside these bounds before storage.

- [ ] **Step 4: Add runtime configuration and cache clearing**

`runtime-config.ts` reads `/runtime-config.json`, validates same-origin API/SSE bases, approved RUM endpoint, an exact bounded list of HTTPS attachment capability origins, build SHA, and environment with Zod, then freezes the result. It rejects wildcards, credentials, paths, query strings, fragments, HTTP origins, duplicate origins, and a list beyond the deployment maximum. The same frozen `ReadonlySet` is injected into Task 10's capability executor; a capability origin absent from it never reaches `fetch`. No secret exists in browser config. `sensitive-cache.ts` revokes object URLs, clears QueryClient and tenant-scoped Zustand keys on logout/tenant change/403 membership loss. The deployment generates one CSP from that validated configuration with `default-src 'self'; script-src 'self'; connect-src 'self' <approved-rum-origin> <exact-approved-attachment-capability-origins>; img-src 'self' blob:; frame-src 'self' blob:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'`; `blob:` is limited to the image/PDF object URLs that Task 10 creates and revokes, active HTML/SVG/office content remains forbidden, and startup fails rather than serving when runtime config and the emitted CSP origin set differ. Attachment previews use a sandboxed frame/response with scripts, forms, popups, top navigation, downloads, and same-origin privilege disabled.

- [ ] **Step 5: Implement honest long-job and degradation states**

`JobBoundary` renders queued/running/retrying/failed/completed from server projections, last update time, retry estimate, and current ActionRequest. It never leaves an indefinite spinner. `failure-state-gallery.stories.tsx` covers 401, 403, 409 stale version, 429, Agent unavailable, Git unavailable, CI unavailable, OSS unavailable, context stale, assurance degraded, attachment quarantine, SSE reconnect, and reconciliation required.

- [ ] **Step 6: Verify redaction, config rejection, and all failure stories**

Run: `pnpm --filter @accord/web test -- redact.test.ts rum.test.ts runtime-config.test.ts sensitive-cache.test.ts job-boundary.test.tsx && pnpm --filter @accord/ui storybook -- --smoke-test`

Expected: PASS; RUM snapshots and transport bodies contain neither raw nor hashed tenant/project fields, forbidden identity dimensions are dropped before transport, cross-origin API config is rejected, object URLs are revoked, and every failure state renders a next action or explicit waiting owner.

- [ ] **Step 7: Commit the observability/security checkpoint**

```bash
git add apps/web/src/shared/telemetry apps/web/src/shared/security apps/web/src/app
git commit -m "feat(web): add privacy-safe RUM and failure UX"
```

### Task 18: Enforce Accessibility, Bundle, And Runtime Performance Budgets

**Files:**
- Create: `apps/web/src/shared/a11y/announce.tsx`
- Create: `apps/web/src/shared/a11y/focus-route.tsx`
- Create: `apps/web/src/shared/a11y/a11y.test.tsx`
- Test: `apps/web/src/shared/a11y/focus-route.test.tsx`
- Create: `apps/web/scripts/check-bundle.mjs`
- Create: `apps/web/tests/performance/performance.spec.ts`
- Create: `apps/web/playwright.performance.config.ts`
- Modify: `apps/web/vite.config.ts`
- Modify: `apps/web/package.json`
- Modify: `pnpm-lock.yaml`

- [ ] **Step 1: Write failing accessibility and bundle assertions**

```tsx
// apps/web/src/shared/a11y/a11y.test.tsx
import { render } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { expect, it } from 'vitest';
import { RequirementWorkspace } from '@/modules/requirements';
import { requirementWorkspaceFixture } from '@accord/testkit';
it('has no serious or critical accessibility violations in list mode', async () => {
  const view = render(<RequirementWorkspace envelope={requirementWorkspaceFixture} initialView="list" />);
  expect(await axe(view.container, { rules: { region: { enabled: true } } })).toHaveNoViolations();
});
```

```js
// apps/web/scripts/check-bundle.mjs
import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
const manifest = JSON.parse(await readFile(new URL('../dist/.vite/manifest.json', import.meta.url)));
const entry = Object.values(manifest).find((item) => item.isEntry);
assert.ok(entry, 'missing Vite entry');
const eager = new Set();
const visit = (key) => {
  if (eager.has(key)) return;
  eager.add(key);
  for (const imported of manifest[key]?.imports ?? []) visit(imported);
};
const entryKey = Object.entries(manifest).find(([, item]) => item === entry)?.[0];
assert.ok(entryKey, 'missing Vite entry key');
visit(entryKey);
const eagerFiles = new Set([...eager].map((key) => manifest[key]?.file).filter((file) => file?.endsWith('.js')));
const eagerBytes = (await Promise.all([...eagerFiles].map((file) =>
  stat(new URL(`../dist/${file}`, import.meta.url)).then((value) => value.size)
))).reduce((total, size) => total + size, 0);
assert.ok(eagerBytes <= 250_000, `eager JS ${eagerBytes} exceeds 250000 bytes`);
assert.equal([...eager].some((key) => /requirement-graph|elk|xyflow/i.test(key)), false,
  'graph code must not be eagerly reachable from the application entry');
```

- [ ] **Step 2: Run checks and verify the expected pre-budget failure**

Run: `pnpm --filter @accord/web test -- a11y.test.tsx && pnpm build:web && node apps/web/scripts/check-bundle.mjs`

Expected: accessibility test or bundle check fails until providers, lazy chunks, manifest output, and accessible labels are complete.

- [ ] **Step 3: Add shared focus and live-announcement behavior**

`FocusRoute` moves focus to `#main-content` after pathname changes unless a route-modal opened; closing a route-modal returns focus to its opener. `Announcer` has polite and assertive live regions. Upload/Agent/CI progress uses polite announcements no more than once every two seconds; security hold, confirmation invalidation, and destructive command failure use assertive announcements. Every form has an error summary linking to invalid controls.

- [ ] **Step 4: Split expensive modules and emit the build manifest**

Update Vite to emit `manifest: true`. Keep Graph/ELK, attachment preview, audit explorer, Monaco-like evidence viewers, and Storybook outside the AppShell entry using route-level `lazy` and dynamic imports. Vendor chunking separates React Router/Query from graph and upload packages. Do not create a single generic vendor chunk that forces graph code into initial navigation.

- [ ] **Step 5: Define runtime budgets in Playwright**

Install the browser runner here rather than depending on the later full E2E task, and give this task an isolated production-preview configuration:

Run: `pnpm --filter @accord/web add -D @playwright/test@1.61.1 && pnpm --filter @accord/web exec playwright install chromium`

```ts
// apps/web/playwright.performance.config.ts
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests/performance',
  testMatch: ['**/*.spec.ts'],
  retries: 0,
  workers: 1,
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://127.0.0.1:4174',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'pnpm build && pnpm exec vite preview --host 127.0.0.1',
    url: 'http://127.0.0.1:4174/login',
    reuseExistingServer: false,
  },
});
```

```ts
// apps/web/tests/performance/performance.spec.ts
import { expect, test } from '@playwright/test';
import { actionRequestFixture } from '@accord/testkit';

test.beforeEach(async ({ page }) => {
  await page.route('**/v1/session', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      user_id: 'performance-user',
      active_tenant_id: 'tenant-a',
      roles: ['Business Requester'],
      projects: [{ project_id: 'project-a', name: 'Performance Fixture' }],
    }),
  }));
  await page.route('**/v1/action-requests?*', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(Array.from({ length: 2_000 }, (_, index) => ({
      ...actionRequestFixture,
      data: { ...actionRequestFixture.data, id: `ar-${index}`, title: `Action ${index}` },
      object_ref: { ...actionRequestFixture.object_ref, id: `ar-${index}` },
      sequence: index + 1,
    }))),
  }));
});

test('action queue meets local deterministic interaction budgets', async ({ page }) => {
  await page.goto('/t/tenant-a/work');
  await expect(page.getByRole('heading', { name: '待我处理' })).toBeVisible();
  const resources = await page.evaluate(() => performance.getEntriesByType('resource').map((entry) => entry.name));
  expect(resources.some((url) => url.includes('elk') || url.includes('xyflow'))).toBe(false);
  const nav = await page.evaluate(() => performance.getEntriesByType('navigation')[0]?.duration ?? Infinity);
  expect(nav).toBeLessThan(2_000);
});
```

CI budgets in deterministic preview mode: AppShell entry JS <= 250 KB uncompressed, no graph chunk before graph route, action list with 2,000 rows maintains >= 50 FPS in the scripted scroll trace, graph worker layout of 500 visible nodes/800 edges completes <= 1,500 ms on the CI runner, and no long task exceeds 500 ms. Production RUM records p75 INP and route render duration using sanitized dimensions, without treating external Agent/Git/CI wait as frontend latency.

- [ ] **Step 6: Run accessibility, keyboard, zoom, and performance checks**

Run: `pnpm --filter @accord/web test -- a11y.test.tsx focus-route.test.tsx && pnpm build:web && node apps/web/scripts/check-bundle.mjs && pnpm --filter @accord/web exec playwright test --config playwright.performance.config.ts`

Expected: axe has zero serious/critical findings; keyboard reaches every primary action; 200% zoom has no overlap; reduced motion disables nonessential animation; bundle and deterministic performance budgets pass.

- [ ] **Step 7: Commit the accessibility/performance checkpoint**

```bash
git add apps/web/src/shared/a11y apps/web/scripts apps/web/tests/performance apps/web/playwright.performance.config.ts apps/web/vite.config.ts apps/web/package.json pnpm-lock.yaml
git commit -m "perf(web): enforce accessibility and bundle budgets"
```

### Task 19: Establish MSW Testkit, Cross-Role Playwright Journeys, And Visual Regression

**Files:**
- Modify: `packages/testkit/package.json`
- Create: `packages/testkit/src/server.ts`
- Create: `packages/testkit/src/browser.ts`
- Create: `packages/testkit/src/handlers.ts`
- Modify: `packages/testkit/src/fixtures/index.ts`
- Create: `packages/testkit/src/fixtures/digests.ts`
- Create: `packages/testkit/src/sessions.ts`
- Modify: `packages/testkit/src/index.ts`
- Modify: `eslint.config.mjs`
- Modify: `apps/web/src/main.tsx`
- Modify: `apps/web/src/app/router.tsx`
- Modify: `apps/web/tsconfig.test.json`
- Create: `apps/web/src/app/bootstrap.tsx`
- Create: `apps/web/src/testing/main.e2e.tsx`
- Create: `apps/web/src/testing/e2e-router.tsx`
- Create: `apps/web/src/testing/test-session-route.tsx`
- Create: `apps/web/.env.e2e`
- Generate: `apps/web/public-e2e/mockServiceWorker.js`
- Modify: `apps/web/vite.config.ts`
- Modify: `apps/web/scripts/check-bundle.mjs`
- Create: `apps/web/playwright.config.ts`
- Create: `apps/web/tests/fixtures/accord-test.ts`
- Create: `apps/web/tests/e2e/intake-confirmation.spec.ts`
- Create: `apps/web/tests/e2e/requirement-workspace.spec.ts`
- Create: `apps/web/tests/e2e/structured-intake.spec.ts`
- Create: `apps/web/tests/e2e/requirement-impact.spec.ts`
- Create: `apps/web/tests/e2e/context-governance.spec.ts`
- Create: `apps/web/tests/e2e/agent-pack-supply-chain.spec.ts`
- Create: `apps/web/tests/e2e/external-supplier.spec.ts`
- Create: `apps/web/tests/e2e/delivery-acceptance.spec.ts`
- Create: `apps/web/tests/e2e/realtime-recovery.spec.ts`
- Create: `apps/web/tests/e2e/accessibility.spec.ts`
- Create: `apps/web/tests/visual/critical-pages.spec.ts`
- Create: `.github/workflows/web-ci.yml`
- Modify: `apps/web/package.json`
- Modify: `package.json`
- Modify: `pnpm-lock.yaml`

- [ ] **Step 1: Write the first failing end-to-end requirement journey**

```ts
// apps/web/tests/e2e/intake-confirmation.spec.ts
import { expect, test } from '../fixtures/accord-test';

test('business intake reaches ordered bilateral confirmation on one exact revision', async ({ page }) => {
  await page.goto('/t/tenant-a/p/project-a/requirements');
  await page.getByRole('button', { name: '新增需求' }).click();
  await page.getByLabel('请描述需求').fill('仓库确认后释放库存并通知销售');
  await page.getByRole('button', { name: '生成需求草稿' }).click();
  await expect(page.getByText('还需要你回答')).toBeVisible();
  await page.getByLabel('验收预期').fill('库存状态为可售，销售收到通知');
  await page.getByRole('checkbox', { name: '确认业务域与需求类型' }).check();
  const relationSuggestion = page.getByRole('group', { name: '库存释放触发销售通知' });
  await relationSuggestion.getByRole('radio', { name: '接受' }).check();
  await page.getByRole('button', { name: '确认并创建需求' }).click();
  await expect(page.getByText('原始录音删除处理中')).toBeVisible();
  await expect(page.getByText('原始录音已删除')).toHaveCount(0);
  await page.getByRole('link', { name: '打开已创建需求' }).click();
  await expect(page).toHaveURL(/revisions\/1\/business/);

  await page.goto('/test/session?as=development-principal&return=/t/tenant-a/work');
  await page.getByRole('link', { name: /审阅需求影响/ }).click();
  await page.getByRole('button', { name: '生成影响分析' }).click();
  await expect(page.getByRole('status')).toHaveTextContent('影响分析可审阅');
  await expect(page.getByRole('table', { name: '建议工作项与依赖顺序' })).toBeVisible();
  await page.getByRole('button', { name: '确认影响分析并生成开发版本' }).click();
  await expect(page).toHaveURL(/revisions\/2\/development/);
  await expect(page.getByText('sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc')).toBeVisible();
  await page.getByRole('button', { name: '开发确认此版本' }).click();
  await expect(page.getByText('开发确认回执已生成')).toBeVisible();

  await page.goto('/test/session?as=business-principal&return=/t/tenant-a/work');
  await page.getByRole('link', { name: /业务确认/ }).click();
  await expect(page).toHaveURL(/revisions\/2\/business/);
  await expect(page.getByText('sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc')).toBeVisible();
  await page.getByRole('button', { name: '业务确认此版本' }).click();
  await expect(page.getByText('已进入待开发池')).toBeVisible();
});
```

- [ ] **Step 2: Run the E2E test and verify it fails before testkit setup**

Run: `pnpm --filter @accord/web test:e2e -- tests/e2e/intake-confirmation.spec.ts`

Expected: FAIL because browser/server fixtures and role sessions are absent.

- [ ] **Step 3: Build deterministic MSW handlers and role sessions**

Install exact test-only dependencies into their owning workspaces before creating the handlers and browser matrix:

Run: `pnpm --filter @accord/testkit add msw@2.15.0 && pnpm --filter @accord/web add -D '@accord/testkit@workspace:*' @playwright/test@1.61.1 @axe-core/playwright@4.12.1 && pnpm --dir packages/testkit exec msw init ../../apps/web/public-e2e --save && pnpm --filter @accord/web exec playwright install chromium firefox webkit`

`@accord/testkit` exports canonical fixtures for business requester/principal, development assessor/principal, WorkItem owner, Business Acceptance Owner, external supplier, Project Admin, Tenant Admin, and Auditor. Handlers implement resource envelopes, exact version/hash 409 behavior, idempotency replay, ordered SSE with gap/410 modes, server drafts, upload scan transitions, Context stale/current, standard/strict/degraded batches, Candidate invalidation, and all three FailureDisposition paths. Fixtures contain no production data.

Handlers also model authoritative Requirement root/block snapshots keyed by domain, `block_type`, risk, and phase; completed, stale, and unavailable extraction; pending/accepted/rejected relation suggestions; immutable audio submission receipts whose deletion path starts only at `deletion_pending`, plus current `getRequirementDraftTranscription` projections for text-only `not_applicable`/pending/provider-proven deleted/provider-ambiguous pending/retry-exhausted `deletion_failed`/explicit `retained_as_reference`/malformed deleted-without-proof cases; queued/running/reviewable/confirmed Impact Drafts with ordered proposed WorkItems and an exact child Revision; DevelopmentAnnotation and ContextCorrection outcomes; linked and unrelated validated/`merged_unapplied`/applied/incomplete Context Patches with actual merge receipts, exact-once consumption facts, and contiguous watermarks; and compatible, incompatible, revoked, signature-invalid, experimental-required-unit, and missing-resource Agent Pack releases with current/rotated distribution epochs. The same-key submission replay remains byte-identical and pending even after the current projection reaches a terminal state. The not-applicable fixture has no transcription/deletion/ActionRequest/reference facts; pending, deleted, and failed fixtures carry the same non-null deletion operation plus exact ActionRequest identity; the deleted fixture carries both provider version-delete receipt digest and absence-verification time; and the retained fixture carries immutable reference Attachment metadata but no deletion operation or ActionRequest. Every fixture enforces the exact nullable tuple in Task 9. No handler adds an audio delete/retry OpenAPI operation; retry is an ActionRequest command and returns the projection to pending before later Provider proof.

The linked Patch fixture carries one generated `context_links` row with both exact Revision and WorkItem facts; the unrelated protected-branch fixture carries `context_links=[]` and still requires the complete receipt path. A fabricated Requirement/WorkItem identifier, mismatched Revision/WorkItem pair, duplicate link, more than 100 links, filter change with an old cursor, noncontiguous Patch watermark, invalid digest, missing/nonpositive Pack epoch, forbidden Pack action, or impossible audio state/proof tuple returns a deterministic contract failure instead of being silently normalized by a fixture.

Shared fixture digests are valid canonical contract values and are exported through `packages/testkit/src/fixtures/index.ts`:

```ts
// packages/testkit/src/fixtures/digests.ts
export const revisionOneHash = `sha256:${'b'.repeat(64)}`;
export const revisionTwoHash = `sha256:${'c'.repeat(64)}`;
export const contextPatchReceiptDigest = `sha256:${'d'.repeat(64)}`;
export const agentPackManifestDigest = `sha256:${'e'.repeat(64)}`;
```

```ts
// packages/testkit/src/sessions.ts
export const roleSessions = {
  'business-requester': { user_id: 'user-br', side: 'business', roles: ['Business Requester'] },
  'business-principal': { user_id: 'user-bp', side: 'business', roles: ['Business Principal'] },
  'development-assessor': { user_id: 'user-da', side: 'development', roles: ['Development Assessor'] },
  'development-principal': { user_id: 'user-dp', side: 'development', roles: ['Development Principal'] },
  'work-item-owner': { user_id: 'user-wo', side: 'development', roles: ['WorkItem Owner'], scopes: ['work-item-1'] },
  'external-supplier': { user_id: 'user-ext', side: 'development', roles: ['WorkItem Owner'], scopes: ['work-item-1'] },
  'business-acceptance-owner': { user_id: 'user-ao', side: 'business', roles: ['Business Acceptance Owner'] },
  'project-admin': { user_id: 'user-pa', side: 'neutral', roles: ['Project Admin'] },
  'tenant-admin': { user_id: 'user-ta', side: 'neutral', roles: ['Tenant Admin'] },
  auditor: { user_id: 'user-au', side: 'neutral', roles: ['Auditor'] },
} as const;
```

Update `packages/testkit/src/index.ts` to export `./fixtures/index`, `./handlers`, `./server`, and `./sessions`. Add the explicit package subpath export `"./browser": "./src/browser.ts"`; `browser.ts` creates `setupWorker(...handlers)`, while `server.ts` remains the Node-only `setupServer(...handlers)` adapter used by Vitest. `handlers.ts` imports only generated API types from `@accord/api-client`; it must not declare a parallel response DTO. The package manifest keeps `name: "@accord/testkit"` and its existing workspace API-client dependency while adding exact `msw` resolution through pnpm.

Keep `@accord/testkit` in `apps/web.devDependencies`; production dependencies must not contain it. Create `.env.e2e` with `VITE_ACCORD_E2E=true`, but do not place any MSW import or mode branch in the production entry graph. Refactor mounting into a neutral function and create a physically separate E2E entry:

```tsx
// apps/web/src/app/bootstrap.tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { AppProviders } from './providers';

type ApplicationRouter = Parameters<typeof RouterProvider>[0]['router'];

export function mountApplication(router: ApplicationRouter): void {
  const root = document.getElementById('root');
  if (!root) throw new Error('Missing #root mount point');
  createRoot(root).render(
    <StrictMode><AppProviders><RouterProvider router={router} /></AppProviders></StrictMode>,
  );
}
```

```tsx
// apps/web/src/main.tsx: production entry contains no test import or environment branch
import { mountApplication } from './app/bootstrap';
import { createProductionRouter } from './app/router';

mountApplication(createProductionRouter());
```

In `app/router.tsx`, keep every existing Task 4-18 route object byte-for-byte. Add `type RouteObject` to its React Router import, replace the opening `export const router = createBrowserRouter([` with `export const productionRoutes = [`, and replace that call's closing `]);` with `] satisfies RouteObject[];`. Then append `export function createProductionRouter() { return createBrowserRouter(productionRoutes); }`. This is a mechanical ownership change, not a second route list: `productionRoutes` contains no conditional E2E route and is treated as immutable after router construction.

```tsx
// apps/web/src/testing/e2e-router.tsx
import { createBrowserRouter } from 'react-router-dom';
import { productionRoutes } from '../app/router';
import { TestSessionRoute } from './test-session-route';

export function createE2eRouter() {
  return createBrowserRouter([
    { path: '/test/session', element: <TestSessionRoute /> },
    ...productionRoutes,
  ]);
}
```

```tsx
// apps/web/src/testing/main.e2e.tsx
import { worker } from '@accord/testkit/browser';
import { mountApplication } from '../app/bootstrap';
import { createE2eRouter } from './e2e-router';

await worker.start({
  onUnhandledRequest: 'error',
  serviceWorker: { url: '/mockServiceWorker.js' },
});
mountApplication(createE2eRouter());
```

Export immutable `productionRoutes` and `createProductionRouter()` from `app/router.tsx`; that file never imports `src/testing`. `test-session-route.tsx` accepts only the role IDs in `roleSessions`, rejects a return path not beginning with `/t/`, writes a SameSite=Strict E2E-only role cookie consumed by browser handlers, and redirects to the validated return path. The localhost E2E cookie is deliberately not marked `Secure` because preview uses HTTP; it cannot exist in the production bundle or authenticate a real API. Production session cookies retain the Identity plan's Secure/HttpOnly requirements.

Make entry and static assets mode-exclusive in `vite.config.ts`:

```ts
// merge into apps/web/vite.config.ts
export default defineConfig(({ mode }) => {
  const e2e = mode === 'e2e';
  return {
    plugins: [
      react(),
      {
        name: 'accord-entry-boundary',
        transformIndexHtml(html) {
          return e2e
            ? html.replace('/src/main.tsx', '/src/testing/main.e2e.tsx')
            : html;
        },
      },
    ],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    publicDir: e2e ? 'public-e2e' : 'public',
    build: {
      target: 'es2022',
      sourcemap: true,
      manifest: true,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (/node_modules\/(?:react|react-dom|react-router|@tanstack\/react-query)/.test(id)) return 'react-runtime';
            if (/node_modules\/(?:elkjs|@xyflow)/.test(id)) return 'requirement-graph';
            if (/node_modules\/(?:uppy|pdfjs-dist)/.test(id)) return 'attachment-preview';
            return undefined;
          },
        },
      },
    },
    server: { port: 4173, strictPort: true },
    preview: { port: 4174, strictPort: true },
  };
});
```

Add `**/public-e2e/mockServiceWorker.js` to the ESLint ignore list. Because the production mode uses `public`, Vite cannot copy the worker from `public-e2e`; because `main.tsx` and `router.tsx` have no reference to the testing tree, Rollup cannot emit a hidden MSW/test-session chunk. E2E mode alone selects the alternate entry and public directory.

- [ ] **Step 4: Add the complete production V1 E2E matrix**

The listed spec files assert these exact release-blocking journeys:

1. `requirement-workspace.spec.ts` renders both generated Requirement-root and typed-block nodes plus every accessible relation row. Changing business domain, block type, risk, or phase issues a new generated server request containing `block_type` and `risk`, omits the previous cursor, and renders only the returned snapshot; `focus` and `view` never become server parameters.
2. `structured-intake.spec.ts` round-trips the complete `DraftFormDto` after refresh: intent, in/out scope, every required discriminator-specific block field, block attachments, edge cases, metrics, acceptance criteria, questions/gaps, and classification. A completed Agent extraction remains editable; an unavailable Agent leaves the same form fully manually completable without text loss or a fabricated extraction result.
3. The same structured-intake spec proves unconfirmed classification and every pending relation suggestion suppress submission; accept/reject decisions preserve exact local/existing Requirement refs and prominent-relation decisions require reasons. Its text-only case returns the exact `not_applicable` tuple and produces no audio claim, transcription poll, deletion operation, or ActionRequest. Separate voice cases capture the immutable submission response and prove `delete_after_submit` starts at `audio_disposition.state=deletion_pending`, same-key replay stays byte-identical, and the UI does not announce deletion. The existing generated `getRequirementDraftTranscription` then covers provider ambiguity remaining pending, current `deleted` rendering only with both provider version-delete receipt digest and exact-version absence-verification time, and a malformed `deleted` projection failing closed. A retry-exhausted current projection renders `deletion_failed`, preserves the confirmed transcript/complete form across refresh, opens the exact remediation ActionRequest, and executes retry only through its returned generated action before observing pending again. Explicit retention renders only from the exact `retained_as_reference` nullable tuple plus immutable reference Attachment metadata and performs no deletion polling. The test also proves the old submission receipt remains pending after every later current-state transition and that no audio delete/retry OpenAPI operation exists in browser traffic.
4. `requirement-impact.spec.ts` creates and polls a Revision-hash-bound Impact Draft, reviews affected areas, feasibility, risks, unknowns, questions, test mappings, and ordered proposed WorkItems/dependencies, saves only development-owned fields with ETag/version/result digest, confirms through the returned action, and follows the exact positive child Revision/hash. Stale Context or result removes confirmation and the parent Revision remains immutable.
5. `context-governance.spec.ts` creates and opens a DevelopmentAnnotation from an exact Revision/WorkItem, proves a blocking annotation exposes its hold, and verifies `accepted_for_context_patch`, `requirement_revision_required`, and `rejected` routing. ContextCorrection acceptance creates only a Patch obligation and ActionRequest; no UI path edits a Requirement or active Context directly.
6. The same context-governance spec covers both a linked Patch carrying exact typed Revision/WorkItem facts and an unrelated protected-branch Patch carrying `context_links=[]`. Empty links render `未关联已知需求或工作项` and are not an error; nonempty links are displayed without browser-derived ownership checks. For both paths, `validated` and `merged_unapplied` do not advance active Context; only `applied` with actual shared-development-branch merge SHA, complete receipt proofs, exact-once consumption, and contiguous previous/next watermarks advances the overview. Missing receipt, sequence, consumption, or watermark renders `证明不完整，画像未更新` and exposes no fabricated action. Fabricated refs, a mismatched Revision/WorkItem pair, duplicate links, and an oversized link set are rejected by deterministic server-contract handlers rather than interpreted by the browser.
7. `agent-pack-supply-chain.spec.ts` renders exact OCI, manifest, resource, and signature-envelope digests, signer/trust root, compatibility matrix, closed install profile, lock, and CI proof. It requires a positive generated `distribution_epoch`, rejects missing/zero/non-integer values before fetch, and never treats browser epoch comparison as a freshness decision. Compatible/current preverified lock facts may expose only returned setup actions; incompatible, revoked, signature-invalid, experimental-required-unit, missing-resource, or rotated-epoch fixtures expose no successful download/install/lock completion, and download intent never becomes verified installation.
8. Project setup chooses each role preset, requires one principal per side, confirms AssessmentPolicy, verifies repository and preverified Agent Pack readiness, and resumes a server draft.
9. H scoring, A/B display, Proposal partial acceptance producing a new Revision, AI Override self-approval rejection, developer-first/business-second confirmation, and old ActionRequest supersession.
10. Context stale permits B but disables A/D finalization, impact confirmation, development confirmation, and publish; current recovery restores only server-returned actions.
11. External supplier cannot enumerate unassigned domains, members, or attachments; expiry creates a hold and the sponsor receives reassignment work.
12. Ready Pool, bilateral Batch manifest confirmation, publication evidence, standard bypass degraded state, strict Controller merge, WorkItem progression, and reconciliation use their distinct generated operations.
13. Candidate exact tree/artifact, Business Acceptance Owner signature, three failure dispositions, CorrectionRun targeted reacceptance, and immutable old runs.
14. Attachment quarantine, access preflight/access-fix, contractual/reference classification, inherited/restricted access, reference deletion hold, preview URL expiry, and tenant cache purge.
15. SSE duplicate/gap/410/full-refetch behavior, 409 semantic diff, double-click idempotency, notification-channel failure with intact in-app task, and refresh recovery remain deterministic.
16. Audit export, retention view, legal hold, deletion proof, break-glass/recovery ActionRequest, and no audit edit/delete control are covered for their authorized roles.

The deterministic Playwright preview is HTTP and must not weaken Task 16's production rule that Agent Pack capabilities are HTTPS and same-origin. Task 16 unit tests own successful bounded-capability consumption using a synthetic HTTPS application origin; this browser E2E owns metadata/readiness and fail-closed states only. A pre-production HTTPS smoke report for one-use consumption, replay rejection, digest verification, and non-persistence is mandatory release evidence in Task 20.

- [ ] **Step 5: Run the new contract-critical journeys in isolation**

Run:

```bash
pnpm --filter @accord/web exec playwright test tests/e2e/requirement-workspace.spec.ts tests/e2e/structured-intake.spec.ts tests/e2e/requirement-impact.spec.ts tests/e2e/context-governance.spec.ts tests/e2e/agent-pack-supply-chain.spec.ts --project=chromium --workers=1
```

Expected: exit 0; every request, immutable receipt, current audio projection, route, and fail-closed assertion above passes; MSW reports zero unhandled requests; no fabricated audio terminal state, invalid digest, stale cursor, noncontiguous watermark, or forbidden action is accepted.

- [ ] **Step 6: Add axe and screenshot regression at production viewports**

```ts
// apps/web/tests/visual/critical-pages.spec.ts
import { expect, test } from '../fixtures/accord-test';
for (const viewport of [{ name: 'desktop', width: 1440, height: 1000 }, { name: 'laptop', width: 1280, height: 800 }, { name: 'mobile', width: 390, height: 844 }]) {
  test(`requirement workspace ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto('/t/tenant-a/p/project-a/requirements?view=list');
    await expect(page).toHaveScreenshot(`requirements-${viewport.name}.png`, { animations: 'disabled', maxDiffPixelRatio: 0.005 });
  });
}
```

`accessibility.spec.ts` injects axe and fails on serious/critical issues for AppShell, action detail, graph fallback, intake dialog, assessment, confirmation, batch, acceptance, audit, and settings. It also runs keyboard-only primary journeys, 200% zoom at 1280x800, forced colors, and reduced motion.

- [ ] **Step 7: Configure Playwright projects and CI quality gates**

```ts
// apps/web/playwright.config.ts
import { defineConfig, devices } from '@playwright/test';
export default defineConfig({
  testDir: './tests', testMatch: ['e2e/**/*.spec.ts', 'visual/**/*.spec.ts'], retries: 0, forbidOnly: true, workers: process.env.CI ? 4 : undefined,
  use: { baseURL: 'http://127.0.0.1:4174', trace: 'retain-on-failure', screenshot: 'only-on-failure', video: 'retain-on-failure' },
  webServer: { command: 'pnpm build:e2e && pnpm exec vite preview --host 127.0.0.1', url: 'http://127.0.0.1:4174/login', reuseExistingServer: !process.env.CI },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    { name: 'mobile-chromium', use: { ...devices['Pixel 7'] } }
  ]
});
```

Add `"build:e2e": "tsc -p tsconfig.test.json --noEmit --pretty false && vite build --mode e2e"` to `apps/web/package.json`. Make every browser and visual spec import the shared fixture rather than importing `test` directly from Playwright:

```ts
// apps/web/tests/fixtures/accord-test.ts
import { randomUUID } from 'node:crypto';
import { expect, test as base } from '@playwright/test';

export const test = base.extend<{ accordReady: void }>({
  accordReady: [async ({ context, page, baseURL }, use) => {
    if (!baseURL) throw new Error('Playwright baseURL is required');
    await context.addCookies([{
      name: 'accord_e2e_session',
      value: randomUUID(),
      url: baseURL,
      sameSite: 'Strict',
      secure: false,
      httpOnly: false,
    }]);
    await page.goto('/login');
    const transport = await page.evaluate(async () => {
      await navigator.serviceWorker.ready;
      const response = await fetch('/v1/session', { credentials: 'include' });
      if (!response.ok) throw new Error(`session preflight failed: ${response.status}`);
      return response.json() as Promise<{ transport: string }>;
    });
    expect(transport.transport).toBe('msw-browser');
    await use();
  }, { auto: true }],
});

export { expect };
```

The `/v1/session` MSW handler returns `transport: "msw-browser"` only in the browser adapter. Handler state is partitioned by the random `accord_e2e_session` cookie and initialized on first access, so parallel Playwright workers cannot share drafts, idempotency keys, SSE sequences, roles, or aggregate versions. A missing cookie is an unhandled/error response. Node `setupServer` uses separate per-test stores reset in `afterEach` and is never presented as an interceptor for browser network traffic.

After Task 19, replace `check-bundle.mjs` with a production-artifact boundary check while retaining Task 18's size and eager-graph assertions:

```js
// apps/web/scripts/check-bundle.mjs
import assert from 'node:assert/strict';
import { readFile, readdir, stat } from 'node:fs/promises';

const dist = new URL('../dist/', import.meta.url);
const manifest = JSON.parse(await readFile(new URL('.vite/manifest.json', dist), 'utf8'));
const entries = Object.entries(manifest).filter(([, item]) => item.isEntry);
assert.equal(entries.length, 1, 'production build must have one HTML application entry');
const entryPair = entries[0];
assert.ok(entryPair, 'production manifest entry is missing');
const [entryKey] = entryPair;
const eager = new Set();
const visit = (key) => {
  if (eager.has(key)) return;
  eager.add(key);
  for (const imported of manifest[key]?.imports ?? []) visit(imported);
};
visit(entryKey);
const eagerFiles = new Set([...eager].map((key) => manifest[key]?.file).filter((file) => file?.endsWith('.js')));
const eagerBytes = (await Promise.all([...eagerFiles].map((file) =>
  stat(new URL(file, dist)).then((value) => value.size)
))).reduce((total, size) => total + size, 0);
assert.ok(eagerBytes <= 250_000, `eager JS ${eagerBytes} exceeds 250000 bytes`);
assert.equal([...eager].some((key) => /requirement-graph|elk|xyflow/i.test(key)), false,
  'graph code must not be eagerly reachable from the production entry');

async function files(url) {
  const entries = await readdir(url, { withFileTypes: true });
  const nested = await Promise.all(entries.map((item) => {
    const child = new URL(item.name + (item.isDirectory() ? '/' : ''), url);
    return item.isDirectory() ? files(child) : [child];
  }));
  return nested.flat();
}

const forbidden = /mockServiceWorker|@accord\/testkit|setupWorker|\/test\/session|src\/testing|main\.e2e/i;
for (const file of await files(dist)) {
  const pathname = decodeURIComponent(file.pathname);
  assert.doesNotMatch(pathname, forbidden, `test artifact emitted: ${pathname}`);
  if (/\.(?:js|css|html|json|map)$/.test(pathname)) {
    assert.doesNotMatch(await readFile(file, 'utf8'), forbidden, `test reference emitted: ${pathname}`);
  }
}
```

CI jobs run in this order: contract/generated check; lint/typecheck; Vitest/MSW; Storybook build and axe; production build/bundle budget; Playwright Chromium critical journeys; Firefox/WebKit/mobile matrix; screenshot regression. Upload traces/screenshots only on failure and retain seven days. Dependency cache keys include `pnpm-lock.yaml`; generated API drift blocks all downstream jobs.

- [ ] **Step 8: Run the full local release gate**

Run:

```bash
pnpm api:generate
pnpm --filter @accord/api-client check:generated
pnpm lint
pnpm typecheck
pnpm test
pnpm --filter @accord/ui build-storybook
pnpm build:web
node apps/web/scripts/check-bundle.mjs
pnpm --filter @accord/web exec playwright test --config playwright.performance.config.ts
pnpm test:e2e
```

Expected: all commands exit 0; generated client has no diff; Vitest/Node-MSW pass; Storybook builds; the production bundle contains no E2E route or service-worker registration; the separate E2E build starts browser MSW before application fetches; Playwright passes Chromium, Firefox, WebKit, and mobile with zero retries; axe has no serious/critical violations; screenshot diffs stay at or below 0.5%.

- [ ] **Step 9: Commit the production verification checkpoint**

```bash
git add packages/testkit eslint.config.mjs apps/web/package.json apps/web/tsconfig.test.json apps/web/vite.config.ts apps/web/scripts/check-bundle.mjs apps/web/src/main.tsx apps/web/src/app/bootstrap.tsx apps/web/src/app/router.tsx apps/web/src/testing apps/web/.env.e2e apps/web/public-e2e/mockServiceWorker.js apps/web/playwright.config.ts apps/web/tests .github/workflows/web-ci.yml package.json pnpm-lock.yaml
git commit -m "test(web): verify production V1 journeys"
```

### Task 20: Perform Final Web Release Review And Handoff

**Files:**
- Create: `apps/web/README.md`
- Create: `apps/web/docs/operator-runbook.md`
- Create: `apps/web/docs/accessibility-support.md`
- Create: `apps/web/docs/browser-support.md`
- Create: `apps/web/docs/release-checklist.md`
- Test: `tests/architecture/web-docs.test.mjs`
- Test: `tests/architecture/web-no-placeholders.test.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write the release documentation assertions**

```js
// tests/architecture/web-docs.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
for (const path of ['README.md', 'docs/operator-runbook.md', 'docs/accessibility-support.md', 'docs/browser-support.md', 'docs/release-checklist.md']) {
  test(`${path} documents an executable production contract`, async () => {
    const text = await readFile(new URL(`../../apps/web/${path}`, import.meta.url), 'utf8');
    assert.match(text, /pnpm/);
    assert.doesNotMatch(text, /TO[D]O|TB[D]|place(?:holder)/i);
  });
}

const requiredEvidence = [
  'tenant-routing', 'app-shell', 'action-request',
  'requirement-graph-server-filters', 'structured-intake-agent-fallback',
  'relation-audio-disposition', 'attachment-permissions',
  'business-development-projections', 'assessment-brief', 'ordered-confirmation',
  'requirement-impact-child-revision', 'project-context',
  'development-annotation-context-correction', 'context-patch-merge-watermark',
  'ready-pool-batch', 'git-workitem-reconciliation', 'candidate-acceptance-correction',
  'agent-pack-supply-chain', 'roles-delegation-suppliers', 'audit-settings',
  'sse-recovery', 'accessibility', 'privacy-safe-rum', 'performance',
  'browser-visual-regression',
];

test('release checklist has passing evidence for one immutable build', async () => {
  const checklist = await readFile(new URL('../../apps/web/docs/release-checklist.md', import.meta.url), 'utf8');
  const buildShas = new Set();
  for (const evidenceId of requiredEvidence) {
    const escaped = evidenceId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const row = new RegExp(
      String.raw`\|\s*${escaped}\s*\|\s*PASS\s*\|\s*\`[^\`\r\n]+\`\s*\|` +
      String.raw`\s*\[[^\]]+\]\((?:https:\/\/|\.\/release-reports\/)[^)]+\)\s*\|` +
      String.raw`\s*([0-9a-f]{40}(?:[0-9a-f]{24})?)\s*\|`,
      'i',
    ).exec(checklist);
    assert.ok(row, `missing passing release evidence: ${evidenceId}`);
    buildShas.add(row[1].toLowerCase());
  }
  assert.equal(buildShas.size, 1, 'all release evidence must target one build SHA');
  const [documentedBuildSha] = buildShas;
  if (process.env.WEB_RELEASE_BUILD_SHA) {
    assert.equal(documentedBuildSha, process.env.WEB_RELEASE_BUILD_SHA.toLowerCase());
  }
  assert.doesNotMatch(checklist, /\b(?:TB[D]|TO[D]O|N\/A|waived|deferred)\b/i);
});
```

Create `web-no-placeholders.test.mjs` with a recursive `node:fs/promises` walk over `apps/web`, `packages/ui`, `packages/api-client`, and `packages/testkit`. Skip `dist`, `coverage`, `packages/api-client/src/generated`, and `apps/web/public-e2e/mockServiceWorker.js`; inspect source/config/document extensions and fail with file plus line for `/TO[D]O|TB[D]|implement[ ]later|similar[ ]to/i`. This turns an empty search result into a passing Node test instead of depending on a search command's inverted exit code.

- [ ] **Step 2: Run the documentation test and verify it fails**

Run: `node --test tests/architecture/web-docs.test.mjs`

Expected: FAIL because release documentation is absent.

- [ ] **Step 3: Document exact operations without duplicating server policy**

`README.md` contains Node/pnpm prerequisites, runtime config schema, install/dev/test/build/preview commands, route/module map, OpenAPI generation, and the rule that server projections/allowed actions are authoritative. The operator runbook covers login/tenant isolation, SSE replay/full refresh, stuck jobs, pending/failed audio deletion ActionRequests and DLQ escalation, 409 conflicts, context stale, attachment quarantine, provider degradation, cache purge, RUM troubleshooting, and rollback to the previous immutable web image. Accessibility support lists keyboard flows, screen-reader/browser matrix, graph fallback, zoom/forced colors/reduced motion, and issue escalation. Browser support names the GA-tested Chromium, Firefox, WebKit, and mobile major versions from the release manifest.

`release-checklist.md` is evidence, not a prose assertion. Every required row has exactly `| evidence-id | PASS | \`exact command\` | [report](HTTPS or ./release-reports URI) | 40-or-64-hex build SHA |`; every row names the same immutable candidate build. The `relation-audio-disposition` report combines Task 9 unit tests and Task 19 browser evidence for all three user dispositions and their exact nullable tuples: text-only `not_applicable` without audio work, immutable pending submission/replay, current-query `pending → provider-proven deleted`, ambiguous Provider outcome remaining pending, failed deletion plus returned ActionRequest retry and text/form durability, explicit immutable-reference retention without deletion work, malformed-terminal fail-closed behavior, and absence of a browser audio delete/retry OpenAPI operation. The `agent-pack-supply-chain` report combines Task 16's HTTPS-origin capability unit suite with a pre-production HTTPS smoke result for one-use consumption, replay rejection, signed distribution-epoch rotation rejection before object access, OCI/manifest/resource digest verification, signature/trust-root verification, compatibility enforcement, and capability/epoch non-persistence. A failed or absent sub-result makes the row non-PASS.

- [ ] **Step 4: Run the complete self-review commands**

Run:

```bash
pnpm --filter @accord/web exec playwright test tests/e2e/requirement-workspace.spec.ts tests/e2e/structured-intake.spec.ts tests/e2e/requirement-impact.spec.ts tests/e2e/context-governance.spec.ts tests/e2e/agent-pack-supply-chain.spec.ts --project=chromium --workers=1
node --test tests/architecture
pnpm verify
pnpm --filter @accord/web exec playwright test --config playwright.performance.config.ts
pnpm test:e2e
WEB_RELEASE_BUILD_SHA="$(git rev-parse HEAD)" node --test tests/architecture/web-docs.test.mjs
```

Expected: the five contract-critical specs, recursive architecture checks, full verification, performance suite, and all browser projects exit 0; the final evidence check proves every required report targets the exact supplied candidate SHA.

- [ ] **Step 5: Verify production V1 coverage against the approved specification**

Record all `requiredEvidence` IDs from Step 1, including the seven explicit production gates `requirement-graph-server-filters`, `structured-intake-agent-fallback`, `relation-audio-disposition`, `requirement-impact-child-revision`, `development-annotation-context-correction`, `context-patch-merge-watermark`, and `agent-pack-supply-chain`. The audio row is PASS only when its report contains every asynchronous deletion/retention sub-result named in Step 3 for the same candidate SHA; a synchronous-delete fixture or final state inferred from the submission receipt is an automatic failure. Every candidate-specific row must contain `PASS`, the exact executed command, a nonempty safe report URI, and the same build SHA. A missing row/report, non-PASS state, SHA mismatch, `N/A`, waiver, or deferred result blocks release.

- [ ] **Step 6: Commit the final web-experience checkpoint**

```bash
git add apps/web/README.md apps/web/docs tests/architecture/web-docs.test.mjs tests/architecture/web-no-placeholders.test.mjs package.json
git commit -m "docs(web): add production operations handoff"
git status --short
```

Expected: final `git status --short` is empty.
