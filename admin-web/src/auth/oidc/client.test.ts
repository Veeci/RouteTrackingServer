import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/errors';
import {
  accessToken,
  answerTokens,
  AUTHORIZE_URL,
  discoveryHandler,
  ISSUER,
  LOGOUT_URL,
  PROVIDER_SETTINGS,
  TOKEN_URL,
} from '../../test/provider';
import { server, testSession } from '../../test/server';
import {
  browser,
  CALLBACK_PATH,
  finishLogin,
  logoutUrl,
  OidcError,
  refreshSession,
  resetDiscovery,
  startLogin,
} from './client';
import { codeChallenge } from './pkce';
import { readOidcSettings } from './settings';

const callback = `${window.location.origin}${CALLBACK_PATH}`;

/** Starts a login and returns the URL the browser was sent to. */
async function started(redirect = '/drivers?online=true'): Promise<URL> {
  const assign = vi.spyOn(browser, 'assign').mockImplementation(() => undefined);
  await startLogin(PROVIDER_SETTINGS, redirect);
  const target = assign.mock.lastCall?.[0];
  if (target === undefined) throw new Error('startLogin did not navigate');
  return new URL(target);
}

/** The provider's answer on the callback page, for a login that `started` began. */
function callbackQuery(from: URL, overrides: Record<string, string> = {}): URLSearchParams {
  return new URLSearchParams({ code: 'code-1', state: from.searchParams.get('state') ?? '', ...overrides });
}

beforeEach(() => {
  server.use(discoveryHandler);
});

afterEach(() => {
  resetDiscovery();
});

describe('readOidcSettings', () => {
  it('is null without an issuer, so the build keeps the development login', () => {
    expect(readOidcSettings({})).toBeNull();
    expect(readOidcSettings({ VITE_OIDC_ISSUER: '  ' })).toBeNull();
  });

  it('drops a trailing slash and defaults the client id', () => {
    expect(readOidcSettings({ VITE_OIDC_ISSUER: `${ISSUER}/` })).toEqual({ issuer: ISSUER, clientId: 'rts-admin-web' });
    expect(readOidcSettings({ VITE_OIDC_ISSUER: ISSUER, VITE_OIDC_CLIENT_ID: 'other' })?.clientId).toBe('other');
  });
});

describe('codeChallenge', () => {
  it('matches the S256 example of RFC 7636, appendix B', async () => {
    expect(await codeChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });
});

describe('startLogin', () => {
  it('sends the browser to the provider with a PKCE challenge and a state', async () => {
    const url = await started();

    expect(`${url.origin}${url.pathname}`).toBe(AUTHORIZE_URL);
    expect(Object.fromEntries(url.searchParams)).toMatchObject({
      response_type: 'code',
      client_id: 'rts-admin-web',
      redirect_uri: callback,
      scope: 'openid profile',
      code_challenge_method: 'S256',
    });
    expect(url.searchParams.get('state')).toMatch(/^[\w-]{22}$/);
    // The verifier stays in this tab. Only its hash leaves.
    expect(url.searchParams.get('code_challenge')).toMatch(/^[\w-]{43}$/);
    expect(url.search).not.toContain('verifier');
  });

  it('makes a new state and verifier for every login', async () => {
    const first = await started();
    const second = await started();

    expect(second.searchParams.get('state')).not.toBe(first.searchParams.get('state'));
    expect(second.searchParams.get('code_challenge')).not.toBe(first.searchParams.get('code_challenge'));
  });

  it('refuses a discovery document for another issuer', async () => {
    server.use(
      http.get(`${ISSUER}/.well-known/openid-configuration`, () =>
        HttpResponse.json({
          issuer: 'https://evil.example',
          authorization_endpoint: 'https://evil.example/auth',
          token_endpoint: 'https://evil.example/token',
        }),
      ),
    );
    const assign = vi.spyOn(browser, 'assign');

    await expect(startLogin(PROVIDER_SETTINGS, '/')).rejects.toBeInstanceOf(OidcError);
    expect(assign).not.toHaveBeenCalled();
  });
});

describe('finishLogin', () => {
  it('trades the code and the verifier for tokens, and returns the session and the redirect', async () => {
    const url = await started('/trips');
    const seen = answerTokens();

    const { session, redirect } = await finishLogin(PROVIDER_SETTINGS, callbackQuery(url));

    const form = seen[0];
    expect(form?.get('grant_type')).toBe('authorization_code');
    expect(form?.get('code')).toBe('code-1');
    expect(form?.get('redirect_uri')).toBe(callback);
    expect(form?.get('client_id')).toBe('rts-admin-web');
    // The provider recomputes the challenge from the verifier: this is the PKCE check.
    expect(await codeChallenge(form?.get('code_verifier') ?? '')).toBe(url.searchParams.get('code_challenge'));
    expect(redirect).toBe('/trips');
    expect(session).toMatchObject({
      userId: 'kc-user-1',
      role: 'OPERATOR',
      displayName: 'Olive Operator',
      refreshToken: 'refresh-1',
      idToken: 'id-1',
    });
    expect(session.expiresAt).toBeGreaterThan(Date.now() + 299_000);
  });

  it('maps the admin role to ADMIN, which wins over operator', async () => {
    const url = await started();
    answerTokens({
      body: { access_token: accessToken(['rts-operator', 'rts-admin']), expires_in: 300 },
    });

    const { session } = await finishLogin(PROVIDER_SETTINGS, callbackQuery(url));

    expect(session.role).toBe('ADMIN');
  });

  it('refuses an account without a staff role', async () => {
    const url = await started();
    answerTokens({ body: { access_token: accessToken(['offline_access']), expires_in: 300 } });

    await expect(finishLogin(PROVIDER_SETTINGS, callbackQuery(url))).rejects.toMatchObject({ kind: 'no-staff-role' });
  });

  it('refuses a state this tab did not make, without calling the token endpoint', async () => {
    const url = await started();
    const seen = answerTokens();

    await expect(finishLogin(PROVIDER_SETTINGS, callbackQuery(url, { state: 'forged' }))).rejects.toMatchObject({
      kind: 'state',
    });
    expect(seen).toEqual([]);
  });

  it('accepts a callback once only', async () => {
    const url = await started();
    answerTokens();
    await finishLogin(PROVIDER_SETTINGS, callbackQuery(url));

    await expect(finishLogin(PROVIDER_SETTINGS, callbackQuery(url))).rejects.toMatchObject({ kind: 'state' });
  });

  it("shows the provider's refusal", async () => {
    const url = await started();

    await expect(
      finishLogin(PROVIDER_SETTINGS, callbackQuery(url, { error: 'access_denied', error_description: 'Denied.' })),
    ).rejects.toMatchObject({ kind: 'provider', message: 'Denied.' });
  });
});

describe('refreshSession', () => {
  it('sends the refresh token and keeps the ID token when the answer has none', async () => {
    const seen = answerTokens({
      body: { access_token: accessToken(['rts-operator']), expires_in: 300, refresh_token: 'refresh-2' },
    });

    const next = await refreshSession(
      PROVIDER_SETTINGS,
      testSession('OPERATOR', { refreshToken: 'refresh-1', idToken: 'id-1' }),
    );

    expect(seen[0]?.get('grant_type')).toBe('refresh_token');
    expect(seen[0]?.get('refresh_token')).toBe('refresh-1');
    expect(next).toMatchObject({ refreshToken: 'refresh-2', idToken: 'id-1' });
  });

  it('turns invalid_grant into a 401, so the re-mint loop opens the login', async () => {
    answerTokens({ status: 400, body: { error: 'invalid_grant', error_description: 'Session not active' } });

    const failure = await refreshSession(PROVIDER_SETTINGS, testSession('OPERATOR', { refreshToken: 'old' })).catch(
      (error: unknown) => error,
    );

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ status: 401, code: 'session_ended' });
  });

  it('gives a network error status 0, so the re-mint loop tries again', async () => {
    server.use(http.post(TOKEN_URL, () => HttpResponse.error()));

    await expect(
      refreshSession(PROVIDER_SETTINGS, testSession('OPERATOR', { refreshToken: 'r' })),
    ).rejects.toMatchObject({ status: 0 });
  });

  it('is a 401 without a refresh token', async () => {
    await expect(refreshSession(PROVIDER_SETTINGS, testSession())).rejects.toMatchObject({ status: 401 });
  });
});

describe('logoutUrl', () => {
  it("ends the provider's session and comes back to the login page", async () => {
    const url = new URL(await logoutUrl(PROVIDER_SETTINGS, testSession('ADMIN', { idToken: 'id-1' })));

    expect(`${url.origin}${url.pathname}`).toBe(LOGOUT_URL);
    expect(Object.fromEntries(url.searchParams)).toEqual({
      client_id: 'rts-admin-web',
      post_logout_redirect_uri: `${window.location.origin}/login`,
      id_token_hint: 'id-1',
    });
  });
});
