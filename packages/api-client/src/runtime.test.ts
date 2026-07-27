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
      instance: '/v1/projects/p1', code: 'VERSION_CONFLICT', correlation_id: '2f458e8a-bd2f-4302-98b7-3f90a8259f37'
    }), { status: 409, headers: { 'Content-Type': 'application/problem+json', ETag: '"8"' } })));
    const failure = apiFetch('/v1/projects');
    await expect(failure).rejects.toBeInstanceOf(ApiProblemError);
    await expect(failure).rejects.toMatchObject({
      status: 409, etag: '"8"', problem: {
        code: 'VERSION_CONFLICT', correlation_id: '2f458e8a-bd2f-4302-98b7-3f90a8259f37'
      }
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
