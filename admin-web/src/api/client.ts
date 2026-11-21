import createClient, { type Middleware } from 'openapi-fetch';
import { sessionStore } from '../auth/session';
import { networkError } from './errors';
import type { paths } from './types';

export interface ApiClientOptions {
  /** Where the API lives. The default is the page's own origin, which the dev proxy and nginx forward. */
  baseUrl?: string;
  /** The access token to send, read on every request. */
  getToken: () => string | undefined;
}

/** Adds `Authorization: Bearer` when there is a token. It reads the token per request, outside React. */
export function authMiddleware(getToken: () => string | undefined): Middleware {
  return {
    onRequest({ request }) {
      const token = getToken();
      if (token !== undefined) request.headers.set('Authorization', `Bearer ${token}`);
      return request;
    },
  };
}

/** Turns a failed fetch into an {@link ApiError} with code `network`. A cancelled request stays an AbortError. */
export const networkErrorMiddleware: Middleware = {
  onError({ error }) {
    // Returning nothing keeps the original error. TanStack Query cancels a query with an AbortError. The check reads
    // the name, not `instanceof DOMException`: under jsdom the aborted fetch throws a DOMException of Node's realm,
    // which jsdom's DOMException class does not match, so a cancel became a "network" error in the tests.
    if (error instanceof Error && error.name === 'AbortError') return undefined;
    return networkError(error);
  },
};

export function createApiClient({ baseUrl = globalThis.location.origin, getToken }: ApiClientOptions) {
  const client = createClient<paths>({
    baseUrl,
    // Look fetch up on every call. openapi-fetch would otherwise keep the fetch that existed when the module loaded,
    // and a test's fetch interceptor installed later would never see the request.
    fetch: (request: Request) => globalThis.fetch(request),
  });
  client.use(authMiddleware(getToken), networkErrorMiddleware);
  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;

/** The app's one client. It sends the token of the current session. */
export const api: ApiClient = createApiClient({ getToken: () => sessionStore.getState().session?.token });
