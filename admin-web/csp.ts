// The Content Security Policy of the built app, in one place. `vite preview` sends it (vite.config.ts), and the
// 12f nginx image must send the same value. Print it with: node csp.ts
//
// - Scripts come only from the app's own origin: no inline script, no eval, no third-party script.
// - Styles allow 'unsafe-inline' because Mantine writes its CSS variables in a <style> tag and sets inline style
//   attributes. Inline styles cannot run code.
// - connect-src 'self' covers same-origin WebSockets too (CSP Level 3). The map's style, tiles, sprites and glyphs
//   come from OpenFreeMap (decision D6). MapLibre fetches them, so the host is in connect-src. It is in img-src too,
//   for a browser where MapLibre falls back to an <img> for the raster tiles and the sprite.
// - connect-src also has the identity provider's origin when the build has one (12g, see providerOrigin).
// - worker-src 'self': MapLibre 6 starts its worker from maplibre-gl-worker.mjs, which the build copies next to the
//   app's scripts (src/map/FleetMap.tsx). No blob: worker and no eval (12d, choice C1).

/** The map's tile host, outside the app's origin. */
export const TILE_HOST = 'https://tiles.openfreemap.org';

/**
 * The identity provider's origin (12g), from the same VITE_OIDC_ISSUER the build reads. The app fetches the discovery
 * document and the token endpoint from it, so it goes in connect-src. The login and logout pages are navigations,
 * which connect-src does not govern. Empty for a build with the development login.
 */
export function providerOrigin(issuer: string | undefined): string[] {
  if (issuer === undefined || issuer.trim() === '') return [];
  return [new URL(issuer.trim()).origin];
}

const PROVIDER = providerOrigin(process.env.VITE_OIDC_ISSUER);

const directives: Record<string, string[]> = {
  'default-src': ["'self'"],
  'script-src': ["'self'"],
  'style-src': ["'self'", "'unsafe-inline'"],
  'img-src': ["'self'", 'data:', 'blob:', TILE_HOST],
  'font-src': ["'self'"],
  'connect-src': ["'self'", TILE_HOST, ...PROVIDER],
  'worker-src': ["'self'"],
  'object-src': ["'none'"],
  'base-uri': ["'self'"],
  'form-action': ["'self'"],
  'frame-ancestors': ["'none'"],
};

export const contentSecurityPolicy = Object.entries(directives)
  .map(([name, sources]) => `${name} ${sources.join(' ')}`)
  .join('; ');

/** Headers the server of the built files sends with every response. */
export const securityHeaders: Record<string, string> = {
  'Content-Security-Policy': contentSecurityPolicy,
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
};

if (import.meta.url === `file://${process.argv[1] ?? ''}`) {
  console.log(contentSecurityPolicy);
}
