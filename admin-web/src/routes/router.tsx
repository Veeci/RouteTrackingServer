import type { QueryClient } from '@tanstack/react-query';
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  Outlet,
  redirect,
  type RouterHistory,
} from '@tanstack/react-router';
import { z } from 'zod';
import { sessionStore } from '../auth/session';
import { AppLayout } from '../components/AppLayout';
import { NotFound } from '../components/NotFound';
import { LoginPage } from './login/LoginPage';
import { OverviewPage } from './overview/OverviewPage';

export interface RouterContext {
  queryClient: QueryClient;
}

// Routes are defined in code. A route's search params are validated here, so a page reads typed values.

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
  notFoundComponent: NotFound,
});

export const loginSearch = z.object({
  // The router parses search values as JSON, so `?redirect=1` arrives as a number. Anything but a string is dropped.
  redirect: z.string().optional().catch(undefined),
});

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  validateSearch: loginSearch,
  component: LoginPage,
});

/** Every page behind the login. Without a session the user goes to the login page and comes back after it. */
const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'app',
  beforeLoad: ({ location }) => {
    if (sessionStore.getState().session === null) {
      throw redirect({ to: '/login', search: { redirect: location.href }, replace: true });
    }
  },
  component: AppLayout,
});

const overviewRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/',
  component: OverviewPage,
});

const routeTree = rootRoute.addChildren([loginRoute, appRoute.addChildren([overviewRoute])]);

export interface AppRouterOptions {
  queryClient: QueryClient;
  history?: RouterHistory | undefined;
}

export function createAppRouter({ queryClient, history }: AppRouterOptions) {
  return createRouter({
    routeTree,
    context: { queryClient },
    ...(history === undefined ? {} : { history }),
  });
}

export type AppRouter = ReturnType<typeof createAppRouter>;

declare module '@tanstack/react-router' {
  interface Register {
    router: AppRouter;
  }
}
