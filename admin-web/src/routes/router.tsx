import type { QueryClient } from '@tanstack/react-query';
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  lazyRouteComponent,
  Outlet,
  redirect,
  type RouterHistory,
} from '@tanstack/react-router';
import { z } from 'zod';
import { sessionStore } from '../auth/session';
import { AppLayout } from '../components/AppLayout';
import { NotFound } from '../components/NotFound';
import { auditSearch } from './audit/search';
import { driversSearch } from './drivers/search';
import { mapSearch } from './map/search';
import { reportsSearch } from './reports/search';
import { tripsSearch } from './trips/search';

export interface RouterContext {
  queryClient: QueryClient;
}

// Routes are defined in code. A route's search params are validated here, so a page reads typed values. Each page
// loads lazily, as its own chunk, when the router first opens it. The search schemas stay in the main chunk, because
// the router validates a link's search before the page's code arrives.

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
  component: lazyRouteComponent(() => import('./login/LoginPage'), 'LoginPage'),
});

/** Where the identity provider sends the browser back after a login (12g). Outside the login guard. */
const callbackRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/auth/callback',
  component: lazyRouteComponent(() => import('./login/CallbackPage'), 'CallbackPage'),
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
  component: lazyRouteComponent(() => import('./overview/OverviewPage'), 'OverviewPage'),
});

const mapRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/map',
  validateSearch: mapSearch,
  // MapLibre loads with this page only, in the page's own chunks.
  component: lazyRouteComponent(() => import('./map/MapPage'), 'MapPage'),
});

const driversRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/drivers',
  validateSearch: driversSearch,
  component: lazyRouteComponent(() => import('./drivers/DriversPage'), 'DriversPage'),
});

const driverRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/drivers/$driverId',
  component: lazyRouteComponent(() => import('./drivers/DriverDetailPage'), 'DriverDetailPage'),
});

const tripsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/trips',
  validateSearch: tripsSearch,
  component: lazyRouteComponent(() => import('./trips/TripsPage'), 'TripsPage'),
});

const tripRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/trips/$tripId',
  // The trip map loads MapLibre in the same lazy chunk as the live map.
  component: lazyRouteComponent(() => import('./trips/TripDetailPage'), 'TripDetailPage'),
});

const auditRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/audit',
  validateSearch: auditSearch,
  component: lazyRouteComponent(() => import('./audit/AuditPage'), 'AuditPage'),
});

const reportsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/reports',
  validateSearch: reportsSearch,
  component: lazyRouteComponent(() => import('./reports/ReportsPage'), 'ReportsPage'),
});

const routeTree = rootRoute.addChildren([
  loginRoute,
  callbackRoute,
  appRoute.addChildren([
    overviewRoute,
    mapRoute,
    driversRoute,
    driverRoute,
    tripsRoute,
    tripRoute,
    auditRoute,
    reportsRoute,
  ]),
]);

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
