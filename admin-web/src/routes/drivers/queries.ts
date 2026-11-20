import { queryOptions } from '@tanstack/react-query';
import { api } from '../../api/client';
import { isApiError, unwrap } from '../../api/errors';
import type { CommandList, CommandStatus, DriverAccount, DriverDetail } from '../../api/types';
import type { DriversSearch } from './search';

/** C3: the detail page asks again this often while the tab is visible. */
export const DETAIL_REFRESH_MS = 10_000;
/** The command list asks again this often while a command waits for the device. */
export const COMMAND_POLL_MS = 3_000;

// Separate prefixes, so that invalidating a driver's detail does not also refetch its commands or session health.
export const driverKeys = {
  lists: ['admin', 'drivers'] as const,
  list: (search: DriversSearch) => ['admin', 'drivers', search] as const,
  detail: (driverId: string) => ['admin', 'driver', driverId] as const,
  commands: (driverId: string) => ['admin', 'commands', driverId] as const,
  sessionHealth: (sessionId: string) => ['admin', 'session-health', sessionId] as const,
};

export function driversQuery(search: DriversSearch) {
  const { online, onTrip, q, cursor } = search;
  return queryOptions({
    queryKey: driverKeys.list(search),
    queryFn: ({ signal }) =>
      api.GET('/api/v1/admin/drivers', { params: { query: { online, onTrip, q, cursor } }, signal }).then(unwrap),
    // C3: the list never refreshes on its own. It is ordered newest seen first, so rows would move while the operator
    // reads them. The page says "as of" and has a Refresh button. An action that changes a driver invalidates the
    // list, which then refetches when it is shown again.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}

/** Answers that asking again will not change: a bad id, no permission, no such driver. */
const FINAL_STATUSES = [400, 403, 404];

/** Every 10 s, unless the last answer was final. */
export function detailRefetchInterval(error: unknown): number | false {
  return FINAL_STATUSES.some((status) => isApiError(error, status)) ? false : DETAIL_REFRESH_MS;
}

export function driverQuery(driverId: string) {
  return queryOptions({
    queryKey: driverKeys.detail(driverId),
    queryFn: ({ signal }) =>
      api.GET('/api/v1/admin/drivers/{driverId}', { params: { path: { driverId } }, signal }).then(unwrap),
    // TanStack Query pauses the interval while the tab is hidden (refetchIntervalInBackground is false).
    refetchInterval: (query) => detailRefetchInterval(query.state.error),
  });
}

/** PENDING and SENT still wait for the device. APPLIED, REJECTED and EXPIRED are final. */
export function isWaiting(status: CommandStatus): boolean {
  return status === 'PENDING' || status === 'SENT';
}

/** Poll while any command waits for the device, and stop once every status is final. */
export function commandPollInterval(list: CommandList | undefined): number | false {
  return list?.items.some((command) => isWaiting(command.status)) === true ? COMMAND_POLL_MS : false;
}

export function commandsQuery(driverId: string) {
  return queryOptions({
    queryKey: driverKeys.commands(driverId),
    queryFn: ({ signal }) =>
      api.GET('/api/v1/admin/drivers/{driverId}/commands', { params: { path: { driverId } }, signal }).then(unwrap),
    refetchInterval: (query) => commandPollInterval(query.state.data),
  });
}

export function sessionHealthQuery(sessionId: string) {
  return queryOptions({
    queryKey: driverKeys.sessionHealth(sessionId),
    queryFn: ({ signal }) =>
      api.GET('/api/v1/admin/sessions/{id}/health', { params: { path: { id: sessionId } }, signal }).then(unwrap),
  });
}

/**
 * Whether the driver's account is enabled. `disabledAt` comes from the account, read in the same request. The
 * `driver.enabled` flag comes from the fleet read model, which an outbox event updates a moment after the change. Right
 * after a disable the two disagree, so the account decides.
 */
export function isEnabled(detail: DriverDetail): boolean {
  return detail.disabledAt === undefined;
}

/** The detail with the account the server answered after a disable or enable. */
export function withAccount(detail: DriverDetail, account: DriverAccount): DriverDetail {
  return {
    ...detail,
    driver: { ...detail.driver, enabled: !account.disabled, displayName: account.displayName },
    disabledAt: account.disabledAt,
    disabledReason: account.disabledReason,
  };
}
