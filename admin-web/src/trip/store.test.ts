import { describe, expect, it, vi } from 'vitest';
import {
  goldenArriving,
  goldenCancelled,
  goldenPosition,
  goldenProgress,
  goldenRouteUpdated,
  goldenSubscribed,
  goldenSubscribedRoute,
  positionAt,
  statusChanged,
} from '../test/trip';
import { createTripStore, EVENTS_CAP, extendTrail, TRAIL_CAP } from './store';

describe('trip store', () => {
  it('subscribed sets the trip and decodes its route', () => {
    const store = createTripStore();
    store.getState().apply(goldenSubscribedRoute);
    const { trip, route } = store.getState();
    expect(trip?.status).toBe('IN_PROGRESS');
    expect(route).toMatchObject({ kind: 'TRIP', routeVersion: 1, distanceM: 812.4 });
    expect(route?.line).toHaveLength(2);
  });

  it('a trip without a route has none on the map', () => {
    const store = createTripStore();
    store.getState().apply(goldenSubscribedRoute);
    store.getState().apply(goldenSubscribed);
    expect(store.getState().route).toBeUndefined();
  });

  it('route_updated replaces the route, and an unreadable one keeps the old route', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const store = createTripStore();
    store.getState().apply(goldenSubscribedRoute);
    store.getState().apply(goldenRouteUpdated);
    expect(store.getState().route).toMatchObject({ routeVersion: 2, distanceM: 655 });
    store.getState().apply({ ...goldenRouteUpdated, routeVersion: 3, polyline: '_p~iF' });
    expect(store.getState().route?.routeVersion).toBe(2);
    expect(warn).toHaveBeenCalledOnce();
  });

  it('a position moves the driver and extends the trail, in [lng, lat]', () => {
    const store = createTripStore();
    store.getState().apply(goldenPosition);
    expect(store.getState().position).toEqual({
      lat: goldenPosition.lat,
      lng: goldenPosition.lng,
      bearingDeg: 87,
      speedMps: 11.2,
      at: goldenPosition.recordedAt,
      lastKnown: false,
    });
    expect(store.getState().trail).toEqual([[goldenPosition.lng, goldenPosition.lat]]);
  });

  it('C3: a seed shows until the first position, and never replaces one', () => {
    const store = createTripStore();
    store.getState().seedPosition({ lat: 10.7, lng: 106.6, at: '2026-09-30T02:10:00Z' });
    expect(store.getState().position).toMatchObject({ lat: 10.7, lastKnown: true });
    // A seed is not a position the page received, so it is not on the trail.
    expect(store.getState().trail).toEqual([]);
    store.getState().apply(goldenPosition);
    store.getState().seedPosition({ lat: 1, lng: 1, at: undefined });
    expect(store.getState().position).toMatchObject({ lat: goldenPosition.lat, lastKnown: false });
  });

  it('C4: the trail keeps the newest 2 000 positions and skips exact repeats', () => {
    const store = createTripStore();
    for (let n = 0; n < TRAIL_CAP + 5; n += 1) store.getState().apply(positionAt(n));
    const { trail } = store.getState();
    expect(trail).toHaveLength(TRAIL_CAP);
    const first = positionAt(5);
    expect(trail[0]).toEqual([first.lng, first.lat]);

    const before = store.getState().trail;
    store.getState().apply(positionAt(TRAIL_CAP + 4));
    expect(store.getState().trail).toBe(before);
  });

  it('extendTrail returns a new array, so readers compare by reference', () => {
    const trail = extendTrail([], [1, 2]);
    expect(extendTrail(trail, [1, 3])).not.toBe(trail);
  });

  it('a reconnect keeps the trail and the events', () => {
    const store = createTripStore();
    store.getState().apply(goldenSubscribed);
    store.getState().apply(goldenPosition);
    store.getState().apply(goldenArriving);
    store.getState().apply(goldenSubscribed);
    expect(store.getState().trail).toHaveLength(1);
    expect(store.getState().events).toHaveLength(1);
  });

  it('status_changed keeps the stage current, newest event first; a moment does not change the stage', () => {
    const store = createTripStore();
    store.getState().apply(goldenSubscribed);
    store.getState().apply(statusChanged('ARRIVED'));
    store.getState().apply(goldenArriving);
    expect(store.getState().trip?.status).toBe('ARRIVED');
    store.getState().apply(goldenCancelled);
    expect(store.getState().trip?.status).toBe('CANCELLED');
    expect(store.getState().events.map((event) => event.event)).toEqual([
      'status_changed',
      'driver_arriving',
      'status_changed',
    ]);
  });

  it('keeps the newest 50 events', () => {
    const store = createTripStore();
    for (let n = 0; n < EVENTS_CAP + 3; n += 1) store.getState().apply(goldenArriving);
    expect(store.getState().events).toHaveLength(EVENTS_CAP);
  });

  it('keeps the progress and the server error', () => {
    const store = createTripStore();
    store.getState().apply(goldenProgress);
    store.getState().apply({ type: 'error', code: 'TRIP_NOT_FOUND', message: 'No such trip' });
    expect(store.getState().progress).toEqual(goldenProgress);
    expect(store.getState().serverError).toEqual({ code: 'TRIP_NOT_FOUND', message: 'No such trip' });
    store.getState().apply(goldenSubscribed);
    expect(store.getState().serverError).toBeUndefined();
  });

  it('keeps the connection', () => {
    const store = createTripStore();
    store.getState().setConnection({ status: 'ended' });
    expect(store.getState().connection).toEqual({ status: 'ended' });
  });
});
