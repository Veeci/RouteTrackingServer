import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeEach } from 'vitest';
import { sessionStore } from '../auth/session';
import { server } from './server';

// jsdom lacks these browser APIs, and Mantine calls them. Plain functions, so restoreMocks never resets them.
// The media query list leaves out the deprecated addListener and removeListener, which Mantine does not call.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) =>
    Object.assign(new EventTarget(), { matches: false, media: query, onchange: null }) as unknown as MediaQueryList,
});
class NoResizeObserver {
  observe() {
    /* jsdom has no layout */
  }
  unobserve() {
    /* jsdom has no layout */
  }
  disconnect() {
    /* jsdom has no layout */
  }
}
window.ResizeObserver = NoResizeObserver;
window.HTMLElement.prototype.scrollIntoView = () => undefined;
// Mantine's autosize textarea listens for web fonts that finish loading. jsdom has no font loading.
Object.defineProperty(document, 'fonts', { value: new EventTarget(), configurable: true });
// jsdom has no blob URLs. Tests of the CSV download spy on these.
URL.createObjectURL = () => 'blob:http://localhost/test';
URL.revokeObjectURL = () => undefined;
window.scrollTo = () => undefined;

// Listen before any test module loads. An unhandled request fails the test: every call must have a handler.
server.listen({ onUnhandledFrame: 'error' });

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  server.events.removeAllListeners();
  sessionStore.getState().signOut();
});

afterAll(() => {
  server.close();
});
