import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../api/client';
import { ApiError } from '../api/errors';
import type { DevTokenRequest } from '../api/types';
import { http, MINTED_USER_ID, problemResponse, server, testSession } from '../test/server';
import { logIn, logOut, remintSession, STAGING_TOKEN_HEADER } from './devLogin';
import { createSessionStore } from './session';
import { savedStagingToken, savedUserId } from './storage';

const api = createApiClient({ getToken: () => undefined });
const OTHER_USER_ID = '2d6a2b3e-8f0e-4c71-b1f4-6e2a4d9c0b55';

/** Records each dev-token request's body and staging header, and answers like the server. */
function recordTokenRequests() {
  const seen: { body: DevTokenRequest; staging: string | null }[] = [];
  server.use(
    http.post('/api/v1/dev/tokens', async ({ request, response }) => {
      const body = await request.json();
      seen.push({ body, staging: request.headers.get(STAGING_TOKEN_HEADER) });
      return response(200).json({
        token: `token-${String(seen.length)}`,
        userId: body.userId ?? MINTED_USER_ID,
        role: body.role,
        displayName: body.displayName ?? 'generated',
        expiresAt: '2026-11-20T08:15:00Z',
      });
    }),
  );
  return seen;
}

describe('logIn', () => {
  it('creates a user on the first login and remembers the user id', async () => {
    const seen = recordTokenRequests();
    const store = createSessionStore();

    const session = await logIn(api, store, { displayName: ' Lan ', role: 'OPERATOR', stagingToken: '' });

    expect(seen).toEqual([{ body: { role: 'OPERATOR', displayName: 'Lan' }, staging: null }]);
    expect(session).toEqual({
      token: 'token-1',
      userId: MINTED_USER_ID,
      role: 'OPERATOR',
      displayName: 'Lan',
      expiresAt: Date.parse('2026-11-20T08:15:00Z'),
    });
    expect(store.getState().session).toEqual(session);
    expect(savedUserId.get()).toBe(MINTED_USER_ID);
    expect(savedStagingToken.get()).toBeUndefined();
  });

  it('reuses the remembered user id and sends the staging token', async () => {
    savedUserId.set(OTHER_USER_ID);
    const seen = recordTokenRequests();

    await logIn(api, createSessionStore(), { displayName: 'Lan', role: 'ADMIN', stagingToken: ' s3cret ' });

    expect(seen).toEqual([{ body: { role: 'ADMIN', displayName: 'Lan', userId: OTHER_USER_ID }, staging: 's3cret' }]);
    expect(savedStagingToken.get()).toBe('s3cret');
  });

  it('drops a remembered user id that is not a UUID', async () => {
    savedUserId.set('not-a-uuid');
    const seen = recordTokenRequests();

    await logIn(api, createSessionStore(), { displayName: 'Lan', role: 'OPERATOR' });

    expect(seen[0]?.body).toEqual({ role: 'OPERATOR', displayName: 'Lan' });
  });

  it('keeps the session empty when the server refuses', async () => {
    // The staging gate's 401 is not in rts-v1.yaml, so the mock marks it off-contract.
    server.use(
      http.post('/api/v1/dev/tokens', ({ response }) => response.untyped(problemResponse(401, 'UNAUTHORIZED'))),
    );
    const store = createSessionStore();

    await expect(logIn(api, store, { displayName: 'Lan', role: 'OPERATOR', stagingToken: 'wrong' })).rejects.toThrow(
      ApiError,
    );
    expect(store.getState().session).toBeNull();
    expect(savedStagingToken.get()).toBeUndefined();
  });

  it('still works when the browser blocks storage', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    recordTokenRequests();
    const store = createSessionStore();

    await logIn(api, store, { displayName: 'Lan', role: 'OPERATOR', stagingToken: 's3cret' });

    expect(store.getState().session?.token).toBe('token-1');
  });
});

describe('remintSession', () => {
  it('asks for a token for the same person with the staging token of this tab', async () => {
    savedStagingToken.set('s3cret');
    const seen = recordTokenRequests();
    const current = testSession('ADMIN', { userId: OTHER_USER_ID, displayName: 'Minh' });

    const next = await remintSession(api, current);

    expect(seen).toEqual([{ body: { role: 'ADMIN', displayName: 'Minh', userId: OTHER_USER_ID }, staging: 's3cret' }]);
    expect(next).toMatchObject({ token: 'token-1', userId: OTHER_USER_ID, role: 'ADMIN' });
  });
});

describe('logOut', () => {
  it('ends the session and forgets the staging token, but keeps the user id', () => {
    const store = createSessionStore();
    store.getState().signIn(testSession());
    savedUserId.set(OTHER_USER_ID);
    savedStagingToken.set('s3cret');

    logOut(store);

    expect(store.getState().session).toBeNull();
    expect(savedStagingToken.get()).toBeUndefined();
    expect(savedUserId.get()).toBe(OTHER_USER_ID);
  });
});
