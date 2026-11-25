// The Content Security Policy of the built app, in one place. `vite preview` sends it (vite.config.ts), and the
// nginx image sends the same headers (12f): its build runs `node csp.ts --nginx`, which prints them as nginx
// `add_header` lines. Print the policy alone with: node csp.ts
//
// - Scripts come only from the app's own origin: no inline script, no eval, no third-party script.
// - Styles allow 'unsafe-inline' because Mantine writes its CSS variables in a <style> tag and sets inline style
//   attributes. Inline styles cannot run code.
// - connect-src 'self' covers same-origin WebSockets too (CSP Level 3). The map's style, tiles, sprites and glyphs
//   come from OpenFreeMap (decision D6). MapLibre fetches them, so the host is in connect-src. It is in img-src too,
//   for a browser where MapLibre falls back to an <img> for the raster tiles and the sprite.
// - worker-src 'self': MapLibre 6 starts its worker from maplibre-gl-worker.mjs, which the build copies next to the
//   app's scripts (src/map/FleetMap.tsx). No blob: worker and no eval (12d, choice C1).

/** The only host outside the app's origin that the browser may reach. */
export const TILE_HOST = 'https://tiles.openfreemap.org';

const directives: Record<string, string[]> = {
  'default-src': ["'self'"],
  'script-src': ["'self'"],
  'style-src': ["'self'", "'unsafe-inline'"],
  'img-src': ["'self'", 'data:', 'blob:', TILE_HOST],
  'font-src': ["'self'"],
  'connect-src': ["'self'", TILE_HOST],
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

/**
 * The headers as nginx lines, for deploy/admin-web/nginx.conf. `always` sends them on error pages too. No value holds
 * a double quote, so wrapping each one in double quotes is safe.
 */
export function nginxHeaders(): string {
  return Object.entries(securityHeaders)
    .map(([name, value]) => `add_header ${name} "${value}" always;`)
    .join('\n');
}

if (import.meta.url === `file://${process.argv[1] ?? ''}`) {
  console.log(process.argv.includes('--nginx') ? nginxHeaders() : contentSecurityPolicy);
}
