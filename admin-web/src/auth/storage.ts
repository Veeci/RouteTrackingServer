// The two values the dev login keeps beyond memory. Every access is wrapped: a browser with site data blocked throws
// on `localStorage` itself, and the login must still work there, only without the remembered values.

const USER_ID_KEY = 'rts-admin.userId';
const STAGING_TOKEN_KEY = 'rts-admin.stagingToken';

function read(storage: () => Storage, key: string): string | undefined {
  try {
    return storage().getItem(key) ?? undefined;
  } catch {
    return undefined;
  }
}

function write(storage: () => Storage, key: string, value: string | undefined): void {
  try {
    if (value === undefined || value === '') storage().removeItem(key);
    else storage().setItem(key, value);
  } catch {
    // Blocked or full: the app works without it.
  }
}

/** The dev login's user id, kept across visits so the audit log shows one actor per person. */
export const savedUserId = {
  get: (): string | undefined => read(() => localStorage, USER_ID_KEY),
  set: (value: string | undefined): void => {
    write(() => localStorage, USER_ID_KEY, value);
  },
};

/** The staging access token, kept for the tab's life so a re-mint needs no prompt. The browser drops it on close. */
export const savedStagingToken = {
  get: (): string | undefined => read(() => sessionStorage, STAGING_TOKEN_KEY),
  set: (value: string | undefined): void => {
    write(() => sessionStorage, STAGING_TOKEN_KEY, value);
  },
};
