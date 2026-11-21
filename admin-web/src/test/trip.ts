// Fixtures for the trip socket: the protocol's golden frames, parsed with the app's own schemas, so a contract change
// that the schemas do not follow fails the tests. The golden trip is 3b24…, ACCEPTED by driver 7c9e….
import driverPositionFrame from '../../../protocol/src/test/resources/golden/driver_position.json';
import routeUpdatedFrame from '../../../protocol/src/test/resources/golden/route_updated.json';
import subscribeFrame from '../../../protocol/src/test/resources/golden/subscribe.json';
import subscribedFrame from '../../../protocol/src/test/resources/golden/subscribed.json';
import subscribedRouteFrame from '../../../protocol/src/test/resources/golden/subscribed_route.json';
import tripEventFrame from '../../../protocol/src/test/resources/golden/trip_event.json';
import cancelledFrame from '../../../protocol/src/test/resources/golden/trip_event_cancelled.json';
import arrivingFrame from '../../../protocol/src/test/resources/golden/trip_event_driver_arriving.json';
import progressFrame from '../../../protocol/src/test/resources/golden/trip_progress.json';
import progressRouteFrame from '../../../protocol/src/test/resources/golden/trip_progress_route.json';
import {
  driverPosition,
  type DriverPosition,
  routeUpdated,
  subscribed,
  type Subscribed,
  tripEvent,
  type TripEvent,
  tripProgress,
} from '../trip/messages';

export const GOLDEN_TRIP_ID = '3b241101-e2bb-4255-8caf-4136c566a962';
export const GOLDEN_DRIVER_ID = '7c9e6679-7425-40de-944b-e07fc1f90ae7';

/** ACCEPTED, without a route. */
export const goldenSubscribed = subscribed.parse(subscribedFrame);
/** IN_PROGRESS, with route version 1. */
export const goldenSubscribedRoute = subscribed.parse(subscribedRouteFrame);
export const goldenPosition = driverPosition.parse(driverPositionFrame);
export const goldenProgress = tripProgress.parse(progressFrame);
export const goldenProgressRoute = tripProgress.parse(progressRouteFrame);
/** status_changed to ARRIVED. */
export const goldenStatusChanged = tripEvent.parse(tripEventFrame);
/** status_changed to CANCELLED by OPERATOR: the trip's last message. */
export const goldenCancelled = tripEvent.parse(cancelledFrame);
export const goldenArriving = tripEvent.parse(arrivingFrame);
/** Route version 2 after an off_route. */
export const goldenRouteUpdated = routeUpdated.parse(routeUpdatedFrame);
export const goldenTripSubscribe = subscribeFrame;

/** A `subscribed` for another trip id or stage, built on the golden one. */
export function subscribedFor(tripId: string, overrides: Partial<Subscribed['trip']> = {}): Subscribed {
  return { type: 'subscribed', trip: { ...goldenSubscribed.trip, tripId, ...overrides } };
}

export function positionAt(n: number, overrides: Partial<DriverPosition> = {}): DriverPosition {
  return {
    ...goldenPosition,
    lat: 10.77 + n / 10_000,
    lng: 106.7 + n / 10_000,
    recordedAt: new Date(Date.UTC(2026, 8, 30, 2, 15, n % 60)).toISOString(),
    ...overrides,
  };
}

export function statusChanged(status: TripEvent['status'], overrides: Partial<TripEvent> = {}): TripEvent {
  return { ...goldenStatusChanged, status, ...overrides };
}
