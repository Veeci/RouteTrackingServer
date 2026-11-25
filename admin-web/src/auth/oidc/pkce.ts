// PKCE (RFC 7636). The app makes a random secret, the code verifier, and sends the provider only its SHA-256, the code
// challenge. To trade the authorization code for tokens it must then show the verifier itself. Someone who catches
// the code on its way back (in a log, a browser extension, a history entry) has no verifier, so the code is useless.

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** `bytes` random bytes as base64url. 32 bytes make a 43-character verifier, the shortest RFC 7636 allows. */
export function randomToken(bytes = 32): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(bytes)));
}

/** The S256 challenge for a verifier: base64url(SHA-256(verifier)). */
export async function codeChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}
