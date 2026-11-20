import { HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { createOpenApiHttp } from 'openapi-msw';
import type { Session } from '../auth/session';
import type { paths, Problem, Role } from '../api/types';

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
];

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
