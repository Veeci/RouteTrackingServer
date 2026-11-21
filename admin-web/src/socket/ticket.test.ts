import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/errors';
import { createQueryClient } from '../api/queryClient';
import { sessionStore } from '../auth/session';
import { http, problem, problemResponse, recordRequests, server, testSession, TICKET } from '../test/server';
import { ticketFetcher } from './ticket';

function setup() {
  const onUnauthorized = vi.fn();
  const queryClient = createQueryClient({ onUnauthorized, retryDelay: 0 });
  return { onUnauthorized, getTicket: ticketFetcher(queryClient) };
}

describe('ticketFetcher', () => {
  it('posts with the session token and returns the ticket', async () => {
    sessionStore.getState().signIn(testSession('OPERATOR', { token: 'token-ticket' }));
    let authorization: string | null = null;
    server.events.on('request:start', ({ request }) => {
      authorization = request.headers.get('Authorization');
    });
    const { getTicket } = setup();
    await expect(getTicket(new AbortController().signal)).resolves.toBe(TICKET);
    expect(authorization).toBe('Bearer token-ticket');
  });

  it('a 401 goes to the query client handler, as any 12b request does', async () => {
    server.use(
      http.post('/api/v1/admin/ws-tickets', ({ response }) => response(401).json(problem(401, 'UNAUTHORIZED'))),
    );
    const { getTicket, onUnauthorized } = setup();
    const failure = getTicket(new AbortController().signal);
    await expect(failure).rejects.toBeInstanceOf(ApiError);
    await expect(failure).rejects.toMatchObject({ status: 401 });
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('a 403 rejects without the login handler', async () => {
    server.use(
      http.post('/api/v1/admin/ws-tickets', ({ response }) => response(403).json(problem(403, 'FLEET_FORBIDDEN'))),
    );
    const { getTicket, onUnauthorized } = setup();
    await expect(getTicket(new AbortController().signal)).rejects.toMatchObject({ code: 'FLEET_FORBIDDEN' });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('never retries on its own: the fleet client decides when to ask again', async () => {
    server.use(
      http.post('/api/v1/admin/ws-tickets', ({ response }) =>
        response.untyped(problemResponse(503, 'UNAVAILABLE', 'Down for a moment.')),
      ),
    );
    const requests = recordRequests();
    const { getTicket } = setup();
    await expect(getTicket(new AbortController().signal)).rejects.toMatchObject({ status: 503 });
    expect(requests).toEqual(['POST /api/v1/admin/ws-tickets']);
  });

  it('an aborted request rejects and never reaches the login handler', async () => {
    // In a browser this is an AbortError. Under jsdom, Node's fetch throws a DOMException of another realm, which the
    // 12b middleware turns into a network error. The fleet client ignores both, because it checks its own signal.
    const { getTicket, onUnauthorized } = setup();
    const request = new AbortController();
    request.abort();
    await expect(getTicket(request.signal)).rejects.toBeDefined();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
