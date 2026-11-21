import { MutationObserver, type QueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { unwrap } from '../api/errors';

/**
 * Asks for a socket ticket as a mutation of the app's query client. A ticket is not tied to one socket, so the fleet
 * socket and the trip socket both use this. The request is a plain REST call, so it follows the 12b rules through the
 * client's mutation cache: a 401 clears the session and opens the login page, and it never retries on its own (the
 * socket's connection decides when to try again). `gcTime: 0` drops the ticket from the cache once it is used.
 */
export function ticketFetcher(queryClient: QueryClient): (signal: AbortSignal) => Promise<string> {
  return async (signal) => {
    const observer = new MutationObserver(queryClient, {
      mutationFn: () => api.POST('/api/v1/admin/ws-tickets', { signal }).then(unwrap),
      gcTime: 0,
    });
    const { ticket } = await observer.mutate();
    return ticket;
  };
}
