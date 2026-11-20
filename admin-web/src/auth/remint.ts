import { ApiError } from '../api/errors';
import type { Session, SessionStore } from './session';

/** Re-mint this long before the token expires. Staff tokens last 15 minutes. */
export const REMINT_BEFORE_MS = 60_000;
/** After a failed re-mint that was not a 401 or 403, try again this much later. */
export const REMINT_RETRY_MS = 15_000;

/** The page's visibility, as a seam for tests. */
export interface PageVisibility {
  isVisible: () => boolean;
  /** Calls the listener on every visibility change. Returns the function that stops it. */
  onChange: (listener: () => void) => () => void;
}

export const documentVisibility: PageVisibility = {
  isVisible: () => document.visibilityState === 'visible',
  onChange: (listener) => {
    document.addEventListener('visibilitychange', listener);
    return () => {
      document.removeEventListener('visibilitychange', listener);
    };
  },
};

export interface RemintOptions {
  store: SessionStore;
  /** Gets a new token for the same person. */
  mint: (session: Session) => Promise<Session>;
  /** Called when the server refuses the re-mint with 401 or 403. */
  onUnauthorized: () => void;
  now?: () => number;
  page?: PageVisibility;
}

/**
 * Keeps the session's token fresh (choice C1): a timer re-mints 60 s before `expiresAt`, and the page re-mints at
 * once when it becomes visible with the token within 60 s of expiry or past it. The second rule exists because
 * browsers slow timers in hidden tabs (Chrome runs them at most once a minute after 5 minutes hidden), so the timer
 * can fire after the token has expired.
 *
 * Returns the function that stops everything. React's StrictMode mounts effects twice, so stopping must leave no
 * timer and no listener, and a re-mint still in flight must not write to the store afterwards.
 */
export function startRemint({
  store,
  mint,
  onUnauthorized,
  now = Date.now,
  page = documentVisibility,
}: RemintOptions): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let inFlight = false;
  let stopped = false;

  const clearTimer = () => {
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
  };

  // Only the timer and the visibility listener call this, and stopping removes both. So it checks `stopped` only
  // after the await, where it can have changed.
  const remintNow = async () => {
    const session = store.getState().session;
    if (session === null || inFlight) return;
    inFlight = true;
    clearTimer();
    try {
      const next = await mint(session);
      // A logout, or a login as someone else, while the call ran wins over this answer.
      if (stopped || store.getState().session?.userId !== session.userId) return;
      store.getState().signIn(next);
    } catch (error) {
      if (stopped) return;
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        onUnauthorized();
        return;
      }
      // The server or the network failed. The token may still work for a while, so try again soon.
      timer = setTimeout(() => void remintNow(), REMINT_RETRY_MS);
    } finally {
      inFlight = false;
    }
  };

  const schedule = () => {
    clearTimer();
    const session = store.getState().session;
    if (session === null || stopped) return;
    const delay = Math.max(0, session.expiresAt - REMINT_BEFORE_MS - now());
    timer = setTimeout(() => void remintNow(), delay);
  };

  const onVisibilityChange = () => {
    const session = store.getState().session;
    if (session === null || !page.isVisible()) return;
    if (session.expiresAt - now() <= REMINT_BEFORE_MS) void remintNow();
  };

  // A new session (login, re-mint) moves the timer. A logout clears it.
  const unsubscribe = store.subscribe((state, previous) => {
    if (state.session !== previous.session) schedule();
  });
  const stopListening = page.onChange(onVisibilityChange);
  schedule();

  return () => {
    stopped = true;
    clearTimer();
    unsubscribe();
    stopListening();
  };
}
