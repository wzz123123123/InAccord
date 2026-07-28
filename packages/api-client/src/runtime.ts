import { z } from 'zod';

const generatedProblemSchema = z.object({
  type: z.string().url(),
  title: z.string().min(1),
  status: z.number().int().min(400).max(599),
  instance: z.string().min(1),
  code: z.string().regex(/^[A-Z][A-Z0-9_]+$/),
  correlation_id: z.string().uuid(),
  expected_version: z.number().int().min(0).optional(),
  actual_version: z.number().int().min(1).nullable().optional(),
  retry_after: z.number().int().min(1).max(120).optional(),
  errors: z.array(z.object({
    field: z.string().min(1).max(512),
    reason: z.string().min(1).max(64).regex(/^[A-Z][A-Z0-9_]*$/)
  }).strict()).min(1).max(128).optional()
}).passthrough();

type RuntimeProblem = z.infer<typeof generatedProblemSchema>;

function correlationId(): string {
  return `web-${crypto.randomUUID()}`;
}

export type NormalizedHeaders = Headers & Readonly<Record<string, string>>;

export interface ApiResponse<T> {
  readonly status: number;
  readonly headers: NormalizedHeaders;
  readonly etag: string | null;
  readonly body: T;
}

type GeneratedHttpResponse = { readonly data: unknown; readonly status: number; readonly headers: Headers };
type ApiFetchResult<T> = T extends GeneratedHttpResponse
  ? T & ApiResponse<T extends { readonly data: infer TBody } ? TBody : never>
  : ApiResponse<T>;

export class ApiProblemError extends Error {
  constructor(
    readonly status: number,
    readonly headers: NormalizedHeaders,
    readonly etag: string | null,
    readonly problem: RuntimeProblem
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

function normalizedHeaders(source: Headers): NormalizedHeaders {
  const headers = new Headers(source) as NormalizedHeaders;
  for (const [name, value] of headers.entries()) {
    Object.defineProperty(headers, name.toLowerCase(), { value, enumerable: true });
  }
  return Object.freeze(headers);
}

export async function apiFetch<T>(url: string, init: RequestInit = {}): Promise<ApiFetchResult<T>> {
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
  const body: unknown = response.status === 204 ? undefined : await response.json();
  return Object.freeze({ status: response.status, headers: responseHeaders, etag, body, data: body }) as ApiFetchResult<T>;
}
