/** Where the login page sends the user when the redirect target is missing or unsafe. */
export const HOME = '/';

/**
 * The redirect target if it is a path of this app, else {@link HOME}. Checking the string alone is not enough: the
 * URL parser treats `/\evil.example` as `//evil.example` and drops a tab or newline inside `/\t/evil.example`. So
 * the check resolves the target against the app's origin and compares origins.
 */
export function safeRedirect(target: unknown, origin: string = globalThis.location.origin): string {
  if (typeof target !== 'string' || !target.startsWith('/') || target.startsWith('//')) return HOME;
  let url: URL;
  try {
    url = new URL(target, origin);
  } catch {
    return HOME;
  }
  if (url.origin !== origin || url.pathname === '/login') return HOME;
  return `${url.pathname}${url.search}${url.hash}`;
}
