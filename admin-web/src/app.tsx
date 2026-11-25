import { MantineProvider } from '@mantine/core';
import { type QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, type RouterHistory } from '@tanstack/react-router';
import { useEffect } from 'react';
import { api } from './api/client';
import { createQueryClient } from './api/queryClient';
import { remintSession } from './auth/devLogin';
import { refreshSession } from './auth/oidc/client';
import { oidcSettings } from './auth/oidc/settings';
import { startRemint } from './auth/remint';
import { sessionStore } from './auth/session';
import { type AppRouter, createAppRouter } from './routes/router';
import { theme } from './theme';

export interface AppParts {
  router: AppRouter;
  queryClient: QueryClient;
}

export interface CreateAppOptions {
  /** Tests pass a memory history. The default is the browser's. */
  history?: RouterHistory;
  /** Tests pass 0 so retried reads do not wait. */
  retryDelay?: number;
}

/**
 * A 401 means the session ended. Clear it and open the login page, which brings the user back to this page and
 * search afterwards.
 */
export function sendToLogin(router: AppRouter): void {
  sessionStore.getState().signOut();
  const { location } = router.state;
  if (location.pathname === '/login') return;
  void router.navigate({ to: '/login', search: { redirect: location.href }, replace: true });
}

/** The query client and the router. Each needs the other, so one function makes both. */
export function createApp({ history, retryDelay }: CreateAppOptions = {}): AppParts {
  const queryClient = createQueryClient({
    onUnauthorized: () => {
      sendToLogin(router);
    },
    retryDelay,
  });
  const router = createAppRouter({ queryClient, history });
  return { router, queryClient };
}

export function App({ router, queryClient }: AppParts) {
  // One re-mint loop for the app's life. StrictMode runs this twice in development; the cleanup stops the first.
  useEffect(
    () =>
      startRemint({
        store: sessionStore,
        // The provider's refresh token with a real login (12g), the dev-token route with the dev login.
        mint: (session) =>
          oidcSettings === null ? remintSession(api, session) : refreshSession(oidcSettings, session),
        onUnauthorized: () => {
          sendToLogin(router);
        },
      }),
    [router],
  );

  return (
    <MantineProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>
  );
}
