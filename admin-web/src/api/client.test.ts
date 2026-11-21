import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { http, overviewBody, problem, server } from '../test/server';
import { createApiClient, networkErrorMiddleware } from './client';
import { ApiError, NETWORK, unwrap } from './errors';

describe('the API client', () => {
  it('sends the current token as a bearer token and returns typed data', async () => {
    let token: string | undefined = 'token-1';
    const seen: (string | null)[] = [];
    server.use(
      http.get('/api/v1/admin/overview', ({ request, response }) => {
        seen.push(request.headers.get('Authorization'));
        return response(200).json(overviewBody);
      }),
    );
    const api = createApiClient({ getToken: () => token });

    const first = await api.GET('/api/v1/admin/overview').then(unwrap);
    token = undefined;
    await api.GET('/api/v1/admin/overview').then(unwrap);

    expect(first.onlineDrivers).toBe(12);
    expect(seen).toEqual(['Bearer token-1', null]);
  });

  it('turns a failed fetch into code "network"', async () => {
    server.use(http.get('/api/v1/admin/overview', () => HttpResponse.error()));
    const api = createApiClient({ getToken: () => undefined });

    const failure = api.GET('/api/v1/admin/overview').then(unwrap);

    await expect(failure).rejects.toBeInstanceOf(ApiError);
    await expect(failure).rejects.toMatchObject({ status: 0, code: NETWORK });
  });

  it('keeps an aborted request an AbortError, also one from another realm', async () => {
    const api = createApiClient({ getToken: () => undefined });
    const controller = new AbortController();
    controller.abort();
    const failure = api.GET('/api/v1/admin/overview', { signal: controller.signal });
    await expect(failure).rejects.toMatchObject({ name: 'AbortError' });
    await expect(failure).rejects.not.toBeInstanceOf(ApiError);

    // jsdom's DOMException is not the class Node's fetch throws, so the middleware checks the name only.
    const foreign = Object.assign(new Error('aborted'), { name: 'AbortError' });
    const onError = networkErrorMiddleware.onError;
    expect(onError).toBeDefined();
    const kept: unknown = await onError?.({ error: foreign } as Parameters<NonNullable<typeof onError>>[0]);
    expect(kept).toBeUndefined();
  });

  it('turns a 429 Problem into an ApiError with retryAfter', async () => {
    server.use(
      http.get('/api/v1/admin/overview', ({ response }) =>
        response(429).json(problem(429, 'RATE_LIMITED'), { headers: { 'Retry-After': '7' } }),
      ),
    );
    const api = createApiClient({ getToken: () => 'token-1' });

    await expect(api.GET('/api/v1/admin/overview').then(unwrap)).rejects.toMatchObject({
      status: 429,
      code: 'RATE_LIMITED',
      retryAfter: 7,
    });
  });
});
