import { z } from 'zod';
import { ApiError, NETWORK } from '../../api/errors';
import type { Role } from '../../api/types';
import type { Session } from '../session';
import { codeChallenge, randomToken } from './pkce';
import type { OidcSettings } from './settings';

// The OpenID Connect Authorization Code flow with PKCE (12g), written out instead of taken from a library: it is four
// HTTP exchanges, and every one of them is visible here. The flow:
//
// 1. startLogin keeps a random `state` and PKCE verifier in sessionStorage and sends the browser to the provider.
// 2. The user logs in at the provider, which sends the browser back to /auth/callback with `code` and `state`.
// 3. finishLogin checks `state`, then trades the code and the verifier for tokens at the token endpoint.
// 4. refreshSession trades the refresh token for new tokens before the access token expires (the re-mint loop).
//
// The tokens live in memory only, like the dev login's. A reload means a new login, which the provider's session
// cookie usually completes without asking for the password again.

/** The page the provider sends the browser back to. Each must be listed in the client's redirect URIs. */
export const CALLBACK_PATH = '/auth/callback';
export const LOGIN_PATH = '/login';

/**
 * The provider's roles that open the dashboard, and where its tokens put them (Keycloak's realm roles). They match the
 * server's defaults (`oidc` in base.conf). The server decides access on its own copy; the app only uses the role to
 * hide what the user may not do.
 */
export const ADMIN_ROLE = 'rts-admin';
export const OPERATOR_ROLE = 'rts-operator';

export type OidcErrorKind = 'provider' | 'state' | 'no-staff-role' | 'token';

/** A login that could not finish. The callback page shows it with a way to try again. */
export class OidcError extends Error {
  override readonly name = 'OidcError';

  constructor(
    readonly kind: OidcErrorKind,
    message: string,
  ) {
    super(message);
  }
}

/** The browser's navigation, as a seam for tests: jsdom cannot leave the page. */
export const browser = {
  assign: (url: string): void => {
    window.location.assign(url);
  },
};

const discoverySchema = z.object({
  issuer: z.string(),
  authorization_endpoint: z.url(),
  token_endpoint: z.url(),
  end_session_endpoint: z.url().optional(),
});
type Discovery = z.infer<typeof discoverySchema>;

const discoveries = new Map<string, Promise<Discovery>>();

/** The provider's endpoints, from its discovery document. One fetch per page load; a failed one is tried again. */
export function discover(settings: OidcSettings): Promise<Discovery> {
  let found = discoveries.get(settings.issuer);
  if (found === undefined) {
    found = fetchJson(`${settings.issuer}/.well-known/openid-configuration`).then((body) => {
      const document = discoverySchema.parse(body);
      // A document for another issuer would send the user, and the code, somewhere else.
      if (document.issuer !== settings.issuer) throw new OidcError('provider', 'The provider names another issuer.');
      return document;
    });
    found.catch(() => discoveries.delete(settings.issuer));
    discoveries.set(settings.issuer, found);
  }
  return found;
}

/** Forgets the cached discovery documents. Tests only. */
export function resetDiscovery(): void {
  discoveries.clear();
}

// The login in progress, kept across the trip to the provider. sessionStorage belongs to this tab only, so a login in
// another tab cannot finish this one.
const PENDING_KEY = 'rts-admin.oidcLogin';
const pendingSchema = z.object({ state: z.string(), verifier: z.string(), redirect: z.string() });
type Pending = z.infer<typeof pendingSchema>;

function savePending(pending: Pending): void {
  sessionStorage.setItem(PENDING_KEY, JSON.stringify(pending));
}

/** Reads and removes the login in progress, so a code can be traded once only. */
function takePending(): Pending | null {
  try {
    const raw = sessionStorage.getItem(PENDING_KEY);
    sessionStorage.removeItem(PENDING_KEY);
    return raw === null ? null : pendingSchema.parse(JSON.parse(raw));
  } catch {
    return null;
  }
}

/** Sends the browser to the provider's login page. `redirect` is where the app goes after the login. */
export async function startLogin(settings: OidcSettings, redirect: string): Promise<void> {
  const { authorization_endpoint } = await discover(settings);
  const state = randomToken(16);
  const verifier = randomToken();
  // Throws when storage is blocked: without it the callback cannot check `state`, so the login must not start.
  savePending({ state, verifier, redirect });
  const url = new URL(authorization_endpoint);
  url.search = new URLSearchParams({
    response_type: 'code',
    client_id: settings.clientId,
    redirect_uri: callbackUrl(),
    scope: 'openid profile',
    state,
    code_challenge: await codeChallenge(verifier),
    code_challenge_method: 'S256',
  }).toString();
  browser.assign(url.toString());
}

export interface FinishedLogin {
  session: Session;
  redirect: string;
}

/** Completes the login on the callback page, from that page's query string. */
export async function finishLogin(settings: OidcSettings, query: URLSearchParams): Promise<FinishedLogin> {
  const pending = takePending();
  const error = query.get('error');
  if (error !== null) {
    throw new OidcError('provider', query.get('error_description') ?? `The provider refused the login (${error}).`);
  }
  const code = query.get('code');
  // A `state` that does not match is a callback this tab did not start: a forged link, or a login from another tab.
  if (pending === null || code === null || query.get('state') !== pending.state) {
    throw new OidcError('state', 'This login was not started here, or it was already used. Log in again.');
  }
  const tokens = await requestTokens(settings, {
    grant_type: 'authorization_code',
    code,
    redirect_uri: callbackUrl(),
    code_verifier: pending.verifier,
  });
  return { session: toOidcSession(tokens), redirect: pending.redirect };
}

/**
 * New tokens for the same person, with the refresh token. The provider rotates the refresh token, so the new session
 * carries the new one. A refused refresh (the provider session ended) is a 401, so the re-mint loop opens the login.
 */
export async function refreshSession(settings: OidcSettings, session: Session): Promise<Session> {
  if (session.refreshToken === undefined) {
    throw new ApiError({ status: 401, code: 'session_ended', title: 'The session ended' });
  }
  const tokens = await requestTokens(settings, { grant_type: 'refresh_token', refresh_token: session.refreshToken });
  return toOidcSession(tokens, session.idToken);
}

/**
 * The provider's logout page, which ends its session too and comes back to the login page. Without that the next
 * "Log in" would sign the same person in again at once, from the provider's cookie.
 */
export async function logoutUrl(settings: OidcSettings, session: Session | null): Promise<string> {
  const { end_session_endpoint } = await discover(settings);
  const back = new URL(LOGIN_PATH, window.location.origin).toString();
  if (end_session_endpoint === undefined) return back;
  const url = new URL(end_session_endpoint);
  const params = new URLSearchParams({ client_id: settings.clientId, post_logout_redirect_uri: back });
  if (session?.idToken !== undefined) params.set('id_token_hint', session.idToken);
  url.search = params.toString();
  return url.toString();
}

const tokenSchema = z.object({
  access_token: z.string().min(1),
  expires_in: z.number().positive(),
  refresh_token: z.string().optional(),
  id_token: z.string().optional(),
});
type Tokens = z.infer<typeof tokenSchema>;

const tokenErrorSchema = z.object({ error: z.string(), error_description: z.string().optional() });

async function requestTokens(settings: OidcSettings, form: Record<string, string>): Promise<Tokens> {
  const { token_endpoint } = await discover(settings);
  let response: Response;
  try {
    response = await fetch(token_endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ ...form, client_id: settings.clientId }),
    });
  } catch (cause) {
    throw new ApiError({ status: 0, code: NETWORK, title: 'The identity provider is unreachable', cause });
  }
  const body: unknown = await response.json().catch(() => null);
  if (response.ok) return tokenSchema.parse(body);
  const refused = tokenErrorSchema.safeParse(body);
  // invalid_grant: the code was used or expired, or the refresh token is no longer valid. Either way, log in again.
  if (refused.success && refused.data.error === 'invalid_grant') {
    throw new ApiError({
      status: 401,
      code: 'session_ended',
      title: 'The session ended',
      detail: refused.data.error_description,
    });
  }
  throw new ApiError({
    status: response.status,
    code: 'token_refused',
    title: 'The identity provider refused the request',
    detail: refused.success ? (refused.data.error_description ?? refused.data.error) : undefined,
  });
}

const accessClaimsSchema = z.object({
  sub: z.string().min(1),
  name: z.string().optional(),
  preferred_username: z.string().optional(),
  realm_access: z.object({ roles: z.array(z.string()).optional() }).optional(),
});

/** The claims of a JWT, without checking the signature: the server checks it. The app only reads name and role. */
export function decodeClaims(jwt: string): unknown {
  const payload = jwt.split('.')[1];
  if (payload === undefined) throw new OidcError('token', 'The provider sent a token the app cannot read.');
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
  const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes)) as unknown;
}

function roleOf(roles: readonly string[]): Role | null {
  if (roles.includes(ADMIN_ROLE)) return 'ADMIN';
  if (roles.includes(OPERATOR_ROLE)) return 'OPERATOR';
  return null;
}

function toOidcSession(tokens: Tokens, previousIdToken?: string): Session {
  const parsed = accessClaimsSchema.safeParse(decodeClaims(tokens.access_token));
  if (!parsed.success) throw new OidcError('token', 'The provider sent a token the app cannot read.');
  const claims = parsed.data;
  const role = roleOf(claims.realm_access?.roles ?? []);
  if (role === null) {
    throw new OidcError('no-staff-role', 'Your account has no operator or admin role. Ask an admin to give you one.');
  }
  const idToken = tokens.id_token ?? previousIdToken;
  return {
    token: tokens.access_token,
    userId: claims.sub,
    role,
    displayName: claims.name ?? claims.preferred_username ?? claims.sub,
    // expires_in, not `exp`: it is relative, so a wrong clock on this computer does not move the refresh.
    expiresAt: Date.now() + tokens.expires_in * 1000,
    ...(tokens.refresh_token === undefined ? {} : { refreshToken: tokens.refresh_token }),
    ...(idToken === undefined ? {} : { idToken }),
  };
}

function callbackUrl(): string {
  return new URL(CALLBACK_PATH, window.location.origin).toString();
}

async function fetchJson(url: string): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(url);
  } catch (cause) {
    throw new ApiError({ status: 0, code: NETWORK, title: 'The identity provider is unreachable', cause });
  }
  if (!response.ok) {
    throw new ApiError({ status: response.status, code: 'provider_error', title: 'The identity provider failed' });
  }
  return response.json() as Promise<unknown>;
}
