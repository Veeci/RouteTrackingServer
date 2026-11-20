// The Content Security Policy of the built app, in one place. `vite preview` sends it (vite.config.ts), and the
// 12f nginx image must send the same value. Print it with: node csp.ts
//
// - Scripts come only from the app's own origin: no inline script, no eval, no third-party script.
// - Styles allow 'unsafe-inline' because Mantine writes its CSS variables in a <style> tag and sets inline style
//   attributes. Inline styles cannot run code.
// - connect-src 'self' covers same-origin WebSockets too (CSP Level 3). 12d adds the map tile host here and in
//   img-src.
// - worker-src blob: is for MapLibre (12d), which starts its workers from blob URLs.

const directives: Record<string, string[]> = {
  'default-src': ["'self'"],
  'script-src': ["'self'"],
  'style-src': ["'self'", "'unsafe-inline'"],
  'img-src': ["'self'", 'data:', 'blob:'],
  'font-src': ["'self'"],
  'connect-src': ["'self'"],
  'worker-src': ['blob:'],
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
