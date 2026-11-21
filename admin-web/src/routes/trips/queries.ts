import { queryOptions } from '@tanstack/react-query';
import { api } from '../../api/client';
import { unwrap } from '../../api/errors';
import type { TrackLeg } from '../../api/types';
import { dayEnd, dayStart } from '../dates';
import type { TripsSearch } from './search';

/** C2: page 1 of the list asks again this often while the tab is visible. */
export const LIST_REFRESH_MS = 10_000;

// Separate prefixes, so that invalidating a trip's detail leaves its track and summary alone: they do not change once
// the trip has ended.
export const tripKeys = {
  lists: ['admin', 'trips'] as const,
  list: (search: TripsSearch) => ['admin', 'trips', search] as const,
  detail: (tripId: string) => ['trip', tripId] as const,
  track: (tripId: string, leg: TrackLeg) => ['trip-track', tripId, leg] as const,
  summary: (tripId: string) => ['trip-summary', tripId] as const,
};

/**
 * C2: page 1 asks again every 10 s, page 2 and later never. The list is ordered by `createdAt`, newest first, and keyset
 * paging keeps a page's rows in place: a new trip lands on page 1 and pushes nothing onto a later page. Only page 1
 * can change, and an operator on it wants the newest trips and their stages without a click. TanStack Query pauses
 * the interval while the tab is hidden.
 */
export function listRefetchInterval(cursor: string | undefined): number | false {
  return cursor === undefined ? LIST_REFRESH_MS : false;
}

export function tripsQuery(search: TripsSearch) {
  const { status, driverId, guestId, from, to, cursor } = search;
  return queryOptions({
    queryKey: tripKeys.list(search),
    queryFn: ({ signal }) =>
      api
        .GET('/api/v1/admin/trips', {
          params: {
            query: {
              status,
              driverId,
              guestId,
              from: from === undefined ? undefined : dayStart(from),
              to: to === undefined ? undefined : dayEnd(to),
              cursor,
            },
          },
          signal,
        })
        .then(unwrap),
    refetchInterval: listRefetchInterval(cursor),
    // A later page changes only on Refresh, as the 12c lists do.
    ...(cursor === undefined ? {} : { staleTime: Infinity, refetchOnWindowFocus: false, refetchOnReconnect: false }),
  });
}

/**
 * The trip. It does not poll: in live mode the socket tells the page when the stage changes, and the page reads the
 * trip again then. An ended trip does not change.
 */
export function tripQuery(tripId: string) {
  return queryOptions({
    queryKey: tripKeys.detail(tripId),
    queryFn: ({ signal }) => api.GET('/api/v1/trips/{id}', { params: { path: { id: tripId } }, signal }).then(unwrap),
  });
}

/** One leg's points and the trip's timeline. Only for an ended trip, and never stale: the record does not change. */
export function trackQuery(tripId: string, leg: TrackLeg) {
  return queryOptions({
    queryKey: tripKeys.track(tripId, leg),
    queryFn: ({ signal }) =>
      api.GET('/api/v1/trips/{id}/track', { params: { path: { id: tripId }, query: { leg } }, signal }).then(unwrap),
    staleTime: Infinity,
  });
}

/**
 * The summary of a completed trip. A 404 SUMMARY_NOT_READY means the server has not computed it yet. It usually does
 * within a second of the trip completing, so the page offers "Check again", and "Compute now" to an admin.
 */
export function summaryQuery(tripId: string) {
  return queryOptions({
    queryKey: tripKeys.summary(tripId),
    queryFn: ({ signal }) =>
      api.GET('/api/v1/trips/{id}/summary', { params: { path: { id: tripId } }, signal }).then(unwrap),
    staleTime: Infinity,
  });
}
