import { render } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { createMemoryHistory } from '@tanstack/react-router';
import { App, createApp } from '../app';
import { type Session, sessionStore } from '../auth/session';

export interface RenderAppOptions {
  /** The path and search the app opens at. */
  path?: string;
  /** Logged in as this session, or logged out without one. */
  session?: Session;
}

/** The whole app (providers, router, re-mint loop) at a path, on a memory history, with retries that do not wait. */
export function renderApp({ path = '/', session }: RenderAppOptions = {}) {
  if (session !== undefined) sessionStore.getState().signIn(session);
  const parts = createApp({ history: createMemoryHistory({ initialEntries: [path] }), retryDelay: 0 });
  const user = userEvent.setup();
  return { ...render(<App {...parts} />), ...parts, user };
}

/** The item at `index`, or a test failure that names it. Index access on an array may give undefined. */
export function nth<T>(items: readonly T[], index: number): T {
  const item = items[index];
  if (item === undefined) throw new Error(`No item at index ${String(index)} of ${String(items.length)}`);
  return item;
}
