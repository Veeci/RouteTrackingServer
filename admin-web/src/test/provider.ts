import { http, HttpResponse } from 'msw';
import type { OidcSettings } from '../auth/oidc/settings';
import { server } from './server';

// A stand-in for the identity provider (12g): its discovery document and token endpoint, with plain MSW handlers,
// because the provider is not in the server's contract.

export const ISSUER = 'https://sso.test/realms/rts';
export const PROVIDER_SETTINGS: OidcSettings = { issuer: ISSUER, clientId: 'rts-admin-web' };
export const AUTHORIZE_URL = `${ISSUER}/protocol/openid-connect/auth`;
export const TOKEN_URL = `${ISSUER}/protocol/openid-connect/token`;
export const LOGOUT_URL = `${ISSUER}/protocol/openid-connect/logout`;

export const discoveryHandler = http.get(`${ISSUER}/.well-known/openid-configuration`, () =>
  HttpResponse.json({
    issuer: ISSUER,
    authorization_endpoint: AUTHORIZE_URL,
    token_endpoint: TOKEN_URL,
    end_session_endpoint: LOGOUT_URL,
  }),
);

/** An unsigned JWT with these claims. The app never checks signatures; the server does. */
export function fakeJwt(claims: Record<string, unknown>): string {
  const encode = (value: unknown) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'RS256', kid: 'test' })}.${encode(claims)}.signature`;
}

export function accessToken(roles: string[], extra: Record<string, unknown> = {}): string {
  return fakeJwt({ sub: 'kc-user-1', name: 'Olive Operator', realm_access: { roles }, ...extra });
}

export interface TokenAnswer {
  status?: number;
  body?: Record<string, unknown>;
}

/** Answers the token endpoint and records each request's form. */
export function answerTokens(answer: TokenAnswer = {}): URLSearchParams[] {
  const seen: URLSearchParams[] = [];
  server.use(
    discoveryHandler,
    http.post(TOKEN_URL, async ({ request }) => {
      seen.push(new URLSearchParams(await request.text()));
      return HttpResponse.json(
        answer.body ?? {
          access_token: accessToken(['rts-operator']),
          expires_in: 300,
          refresh_token: 'refresh-1',
          id_token: 'id-1',
        },
        { status: answer.status ?? 200 },
      );
    }),
  );
  return seen;
}
