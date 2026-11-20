import { HttpResponse, ws } from 'msw';
import { setupServer } from 'msw/node';
import { createOpenApiHttp } from 'openapi-msw';
import type { Session } from '../auth/session';
import type { paths, Problem, Role } from '../api/types';
import { auditPage, driverDetail, fleetDriver, fleetPage, providerReport } from './fixtures';

/**
 * MSW typed by the generated contract (TC-12-CON-01): a handler for a path the spec lacks, or a body the spec does
 * not allow for that status, fails the typecheck.
 */
export const http = createOpenApiHttp<paths>();

export const overviewBody = {
  onlineDrivers: 12,
  driversOnTrip: 5,
  activeTrips: 6,
  disabledDrivers: 1,
  computedAt: '2026-11-20T08:30:00Z',
};

export function problem(status: number, code: string, detail?: string): Problem {
  return {
    type: `urn:rts:error:${code.toLowerCase().replaceAll('_', '-')}`,
    title: code,
    status,
    code,
    traceId: 'trace-test',
    ...(detail === undefined ? {} : { detail }),
  };
}

/** The headers and body of a Problem Details answer, as the server sends it. */
export function problemResponse(status: number, code: string, detail?: string) {
  return HttpResponse.json(problem(status, code, detail), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

export const MINTED_USER_ID = '0b9c6a7e-4a52-4b4f-9a1e-3f1f2a8f6d10';

export const TICKET = 'ticket-test';

/**
 * The fleet socket on any host. MSW intercepts the browser's WebSocket, so a page test runs the real fleet client:
 * `server.use(fleetSocket.addEventListener('connection', ({ client }) => …))`.
 */
export const fleetSocket = ws.link('ws://*/ws/v1/admin/fleet');

export const defaultHandlers = [
  http.post('/api/v1/dev/tokens', async ({ request, response }) => {
    const body = await request.json();
    return response(200).json({
      token: `token-${body.role}-${String(Date.now())}`,
      userId: body.userId ?? MINTED_USER_ID,
      role: body.role,
      displayName: body.displayName ?? 'generated',
      expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    });
  }),
  http.get('/api/v1/admin/overview', ({ response }) => response(200).json(overviewBody)),
  // 12c: one page of two drivers, a driver, no command, no session health, an empty audit log and report.
  http.get('/api/v1/admin/drivers', ({ response }) => response(200).json(fleetPage([fleetDriver(1), fleetDriver(2)]))),
  http.get('/api/v1/admin/drivers/{driverId}', ({ params, response }) =>
    response(200).json(driverDetail({ driver: fleetDriver(1, { driverId: params.driverId }) })),
  ),
  http.get('/api/v1/admin/drivers/{driverId}/commands', ({ response }) => response(200).json({ items: [] })),
  http.get('/api/v1/admin/sessions/{id}/health', ({ response }) =>
    response(404).json(problem(404, 'SESSION_HEALTH_NOT_FOUND')),
  ),
  http.get('/api/v1/admin/audit', ({ response }) => response(200).json(auditPage([]))),
  // 12d: a ticket for the fleet socket. A test that opens the socket adds a handler on `fleetSocket`.
  http.post('/api/v1/admin/ws-tickets', ({ response }) =>
    response(200).json({ ticket: TICKET, expiresAt: new Date(Date.now() + 30_000).toISOString() }),
  ),
  http.get('/api/v1/admin/reports/providers', ({ query, response }) =>
    response(200).json(providerReport([], query.get('from'), query.get('to'))),
  ),
];

/** Every request the app sends from now on, as `METHOD /path?search`, in order. */
export function recordRequests(): string[] {
  const seen: string[] = [];
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    seen.push(`${request.method} ${url.pathname}${url.search}`);
  });
  return seen;
}

export const server = setupServer(...defaultHandlers);

export function testSession(role: Role = 'OPERATOR', overrides: Partial<Session> = {}): Session {
  return {
    token: 'token-test',
    userId: MINTED_USER_ID,
    role,
    displayName: 'Test operator',
    expiresAt: Date.now() + 15 * 60_000,
    ...overrides,
  };
}
