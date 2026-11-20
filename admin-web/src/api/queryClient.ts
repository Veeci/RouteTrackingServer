import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError, NETWORK } from './errors';

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: {
      /** The login form's own call: its 401 means a wrong staging token, not an ended session. */
      skipAuthRedirect?: boolean;
    };
  }
}

/** Reads retry at most this many times after the first failure. */
export const READ_RETRIES = 2;

/**
 * Whether a failed read is worth another try: no response at all, or a 5xx. A 4xx never changes on a retry, and an
 * error from the app's own code is a bug, not a hiccup.
 */
export function isRetryable(error: unknown): boolean {
  return error instanceof ApiError && (error.code === NETWORK || error.status >= 500);
}

/** TanStack Query calls this after each failure, with the number of failures before this one. */
export function shouldRetryRead(failureCount: number, error: unknown): boolean {
  return failureCount < READ_RETRIES && isRetryable(error);
}

export interface QueryClientOptions {
  /** Called on a 401 from any query or mutation, except a mutation marked `skipAuthRedirect`. */
  onUnauthorized: () => void;
  /** Tests pass 0 so retries do not wait. The default backs off 1 s, then 2 s. */
  retryDelay?: number;
}

export function createQueryClient({ onUnauthorized, retryDelay }: QueryClientOptions): QueryClient {
  const onError = (error: unknown) => {
    if (error instanceof ApiError && error.status === 401) onUnauthorized();
  };
  return new QueryClient({
    queryCache: new QueryCache({ onError }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (mutation.meta?.skipAuthRedirect !== true) onError(error);
      },
    }),
    defaultOptions: {
      queries: { retry: shouldRetryRead, ...(retryDelay === undefined ? {} : { retryDelay }) },
      // A write is never repeated on its own: the user decides, because the server audits every action.
      mutations: { retry: false },
    },
  });
}
