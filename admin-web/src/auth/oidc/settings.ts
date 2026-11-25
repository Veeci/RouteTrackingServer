// Which login the build uses (12g). A build with VITE_OIDC_ISSUER logs in through the identity provider. A build
// without it keeps the development login (D3), for a local server and the E2E tests that mint their own tokens.
// The values are baked in at build time, like every VITE_ variable, so staging and production are separate builds.

export interface OidcSettings {
  /** The provider's issuer URL, exactly as it writes `iss`: `http://localhost:8180/realms/rts` for local Keycloak. */
  issuer: string;
  /** The dashboard's public client at the provider. A public client has no secret: PKCE protects the code. */
  clientId: string;
}

/** The two values from the build's environment, or null for the development login. */
export function readOidcSettings(env: Record<string, unknown>): OidcSettings | null {
  const issuer = env.VITE_OIDC_ISSUER;
  if (typeof issuer !== 'string' || issuer.trim() === '') return null;
  const clientId = env.VITE_OIDC_CLIENT_ID;
  return {
    issuer: issuer.trim().replace(/\/+$/, ''),
    clientId: typeof clientId === 'string' && clientId.trim() !== '' ? clientId.trim() : 'rts-admin-web',
  };
}

export const oidcSettings: OidcSettings | null = readOidcSettings(import.meta.env);
