import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, networkError } from '../api/errors';
import { type PageVisibility, REMINT_BEFORE_MS, REMINT_RETRY_MS, startRemint } from './remint';
import { createSessionStore, type Session, type SessionStore } from './session';

// TC-12-AUTH-01 and choice C1: the timer re-mints 60 s before expiry with the same userId, the page re-mints when it
// becomes visible near or past expiry, and stopping leaves nothing behind.

const START = Date.parse('2026-11-20T08:00:00Z');
const TTL = 15 * 60_000;
const USER_ID = '6f1c1c55-3c9e-4a43-9a0e-2c6a8f3c1d01';

function session(token: string, expiresAt: number): Session {
  return { token, userId: USER_ID, role: 'OPERATOR', displayName: 'Lan', expiresAt };
}

function fakePage(visible = true) {
  const listeners = new Set<() => void>();
  let isVisible = visible;
  const page: PageVisibility = {
    isVisible: () => isVisible,
    onChange: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
  const set = (value: boolean) => {
    isVisible = value;
    listeners.forEach((listener) => {
      listener();
    });
  };
  return {
    page,
    listeners,
    show: () => {
      set(true);
    },
    hide: () => {
      set(false);
    },
  };
}

/** A mint that answers with a fresh 15-minute token for the same person. */
function freshMint() {
  let count = 0;
  return vi.fn((current: Session) => {
    count += 1;
    return Promise.resolve({ ...current, token: `token-${String(count + 1)}`, expiresAt: Date.now() + TTL });
  });
}

let store: SessionStore;

beforeEach(() => {
  vi.useFakeTimers({ now: START });
  store = createSessionStore();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the re-mint timer', () => {
  it('re-mints 60 s before expiry with the same userId, then schedules the next one', async () => {
    store.getState().signIn(session('token-1', START + TTL));
    const mint = freshMint();
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: fakePage().page });

    await vi.advanceTimersByTimeAsync(TTL - REMINT_BEFORE_MS - 1);
    expect(mint).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(mint).toHaveBeenCalledTimes(1);
    expect(mint).toHaveBeenCalledWith(expect.objectContaining({ userId: USER_ID, token: 'token-1' }));
    expect(store.getState().session).toMatchObject({ token: 'token-2', userId: USER_ID });

    await vi.advanceTimersByTimeAsync(TTL - REMINT_BEFORE_MS);
    expect(mint).toHaveBeenCalledTimes(2);
    expect(store.getState().session?.token).toBe('token-3');
    stop();
  });

  it('re-mints at once for a session that is already within 60 s of expiry', async () => {
    store.getState().signIn(session('token-1', START + 30_000));
    const mint = freshMint();
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: fakePage().page });

    await vi.advanceTimersByTimeAsync(0);
    expect(mint).toHaveBeenCalledTimes(1);
    stop();
  });

  it('starts when a session arrives and stops on logout', async () => {
    const mint = freshMint();
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: fakePage().page });
    expect(vi.getTimerCount()).toBe(0);

    store.getState().signIn(session('token-1', START + TTL));
    expect(vi.getTimerCount()).toBe(1);

    store.getState().signOut();
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(TTL);
    expect(mint).not.toHaveBeenCalled();
    stop();
  });
});

describe('the visibility re-mint (C1)', () => {
  it('re-mints when the page becomes visible after the hidden tab slept past expiry', () => {
    store.getState().signIn(session('token-1', START + TTL));
    const mint = freshMint();
    const visibility = fakePage(true);
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: visibility.page });

    visibility.hide();
    // The browser throttled the hidden tab: the clock moved past expiry, and the timer has not run.
    vi.setSystemTime(START + TTL + 5_000);
    expect(mint).not.toHaveBeenCalled();

    visibility.show();
    expect(mint).toHaveBeenCalledTimes(1);
    expect(mint).toHaveBeenCalledWith(expect.objectContaining({ userId: USER_ID }));
    stop();
  });

  it('re-mints when the page becomes visible within 60 s of expiry', () => {
    store.getState().signIn(session('token-1', START + TTL));
    const mint = freshMint();
    const visibility = fakePage(false);
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: visibility.page });

    vi.setSystemTime(START + TTL - REMINT_BEFORE_MS + 1);
    visibility.show();

    expect(mint).toHaveBeenCalledTimes(1);
    stop();
  });

  it('does nothing when the page becomes visible with time left, or becomes hidden', () => {
    store.getState().signIn(session('token-1', START + TTL));
    const mint = freshMint();
    const visibility = fakePage(true);
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: visibility.page });

    vi.setSystemTime(START + 5 * 60_000);
    visibility.hide();
    visibility.show();

    expect(mint).not.toHaveBeenCalled();
    stop();
  });

  it('sends one request when the timer and the visibility change fire together', async () => {
    store.getState().signIn(session('token-1', START + TTL));
    let resolve: (value: Session) => void = () => undefined;
    const mint = vi.fn(() => new Promise<Session>((done) => (resolve = done)));
    const visibility = fakePage(true);
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: visibility.page });

    await vi.advanceTimersByTimeAsync(TTL - REMINT_BEFORE_MS);
    visibility.show();
    resolve(session('token-2', Date.now() + TTL));
    await vi.advanceTimersByTimeAsync(0);

    expect(mint).toHaveBeenCalledTimes(1);
    expect(store.getState().session?.token).toBe('token-2');
    stop();
  });
});

describe('stopping', () => {
  it('leaves no timer and no listener behind', async () => {
    store.getState().signIn(session('token-1', START + TTL));
    const mint = freshMint();
    const visibility = fakePage(true);
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: visibility.page });
    expect(vi.getTimerCount()).toBe(1);
    expect(visibility.listeners.size).toBe(1);

    stop();

    expect(vi.getTimerCount()).toBe(0);
    expect(visibility.listeners.size).toBe(0);
    vi.setSystemTime(START + TTL);
    visibility.show();
    store.getState().signIn(session('token-9', START + 30_000));
    await vi.advanceTimersByTimeAsync(TTL);
    expect(mint).not.toHaveBeenCalled();
  });

  it("survives StrictMode's mount, unmount and mount with one timer", async () => {
    store.getState().signIn(session('token-1', START + TTL));
    const mint = freshMint();
    const visibility = fakePage(true);
    startRemint({ store, mint, onUnauthorized: vi.fn(), page: visibility.page })();
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: visibility.page });

    expect(vi.getTimerCount()).toBe(1);
    await vi.advanceTimersByTimeAsync(TTL - REMINT_BEFORE_MS);
    expect(mint).toHaveBeenCalledTimes(1);
    stop();
  });

  it('ignores the answer of a re-mint that was in flight', async () => {
    store.getState().signIn(session('token-1', START + TTL));
    let resolve: (value: Session) => void = () => undefined;
    const mint = vi.fn(() => new Promise<Session>((done) => (resolve = done)));
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: fakePage().page });

    await vi.advanceTimersByTimeAsync(TTL - REMINT_BEFORE_MS);
    stop();
    resolve(session('token-2', Date.now() + TTL));
    await vi.advanceTimersByTimeAsync(0);

    expect(store.getState().session?.token).toBe('token-1');
  });
});

describe('a failed re-mint', () => {
  it('goes to the login page on a 401, without retrying', async () => {
    store.getState().signIn(session('token-1', START + TTL));
    const onUnauthorized = vi.fn();
    const mint = vi.fn(() => Promise.reject(new ApiError({ status: 401, code: 'UNAUTHORIZED', title: 'x' })));
    const stop = startRemint({ store, mint, onUnauthorized, page: fakePage().page });

    await vi.advanceTimersByTimeAsync(TTL - REMINT_BEFORE_MS);

    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
    stop();
  });

  it('tries again 15 s after a network error', async () => {
    store.getState().signIn(session('token-1', START + TTL));
    const onUnauthorized = vi.fn();
    const mint = vi
      .fn<(current: Session) => Promise<Session>>()
      .mockRejectedValueOnce(networkError(new TypeError('Failed to fetch')))
      .mockImplementation((current) => Promise.resolve({ ...current, token: 'token-2', expiresAt: Date.now() + TTL }));
    const stop = startRemint({ store, mint, onUnauthorized, page: fakePage().page });

    await vi.advanceTimersByTimeAsync(TTL - REMINT_BEFORE_MS);
    expect(mint).toHaveBeenCalledTimes(1);
    expect(store.getState().session?.token).toBe('token-1');

    await vi.advanceTimersByTimeAsync(REMINT_RETRY_MS);
    expect(mint).toHaveBeenCalledTimes(2);
    expect(store.getState().session?.token).toBe('token-2');
    expect(onUnauthorized).not.toHaveBeenCalled();
    stop();
  });

  it('keeps a logout that happened while the call ran', async () => {
    store.getState().signIn(session('token-1', START + TTL));
    let resolve: (value: Session) => void = () => undefined;
    const mint = vi.fn(() => new Promise<Session>((done) => (resolve = done)));
    const stop = startRemint({ store, mint, onUnauthorized: vi.fn(), page: fakePage().page });

    await vi.advanceTimersByTimeAsync(TTL - REMINT_BEFORE_MS);
    store.getState().signOut();
    resolve(session('token-2', Date.now() + TTL));
    await vi.advanceTimersByTimeAsync(0);

    expect(store.getState().session).toBeNull();
    stop();
  });
});
