import { describe, expect, it } from 'vitest';
import { goldenCluster, goldenDelta, goldenError, goldenSnapshot, marker, OFFLINE_ID, ON_TRIP_ID } from '../test/fleet';
import { createFleetStore, driversInView } from './store';

describe('fleet store (TC-12-FL-01)', () => {
  it('a snapshot replaces every marker', () => {
    const store = createFleetStore();
    store
      .getState()
      .apply({ type: 'fleet_snapshot', drivers: [marker(1), marker(2), marker(3)], at: goldenSnapshot.at });
    store.getState().apply(goldenSnapshot);

    const { markers, at } = store.getState();
    expect([...markers.keys()]).toEqual([ON_TRIP_ID, OFFLINE_ID]);
    expect(markers.get(ON_TRIP_ID)).toEqual(goldenSnapshot.drivers[0]);
    expect(at).toBe(goldenSnapshot.at);
    expect(driversInView(store.getState())).toBe(2);
  });

  it('a delta upserts and removes, and keeps the other markers', () => {
    const store = createFleetStore();
    const other = marker(9);
    store.getState().apply({ ...goldenSnapshot, drivers: [...goldenSnapshot.drivers, other] });
    const before = store.getState().markers;

    store.getState().apply(goldenDelta);
    const { markers, at } = store.getState();
    expect(markers.get(ON_TRIP_ID)).toEqual(goldenDelta.upserts[0]);
    expect(markers.has(OFFLINE_ID)).toBe(false);
    expect(markers.get(other.driverId)).toBe(other);
    expect(at).toBe(goldenDelta.at);
    // A new map, so readers see the change by reference. The old one is untouched.
    expect(markers).not.toBe(before);
    expect(before.has(OFFLINE_ID)).toBe(true);
  });

  it('a delta adds a driver that entered the area', () => {
    const store = createFleetStore();
    store.getState().apply({ type: 'fleet_snapshot', drivers: [], at: goldenSnapshot.at });
    store
      .getState()
      .apply({ type: 'fleet_delta', upserts: [marker(4)], removes: ['not-on-the-map'], at: goldenDelta.at });
    expect([...store.getState().markers.keys()]).toEqual([marker(4).driverId]);
  });

  it('clusters replace the clusters and clear the markers', () => {
    const store = createFleetStore();
    store.getState().apply(goldenSnapshot);
    store.getState().apply(goldenCluster);

    const state = store.getState();
    expect(state.markers.size).toBe(0);
    expect(state.clusters).toEqual(goldenCluster.cells);
    expect(state.clusterTotal).toBe(5_324);
    expect(driversInView(state)).toBe(5_324);

    const fewer = { ...goldenCluster, cells: goldenCluster.cells.slice(0, 1), total: 3_120 };
    store.getState().apply(fewer);
    expect(store.getState().clusters).toEqual(fewer.cells);
    expect(driversInView(store.getState())).toBe(3_120);
  });

  it('a delta while clustered changes nothing', () => {
    const store = createFleetStore();
    store.getState().apply(goldenCluster);
    const before = store.getState();
    store.getState().apply(goldenDelta);
    expect(store.getState().markers).toBe(before.markers);
    expect(store.getState().at).toBe(goldenCluster.at);
  });

  it('a snapshot after clusters restores the markers', () => {
    const store = createFleetStore();
    store.getState().apply(goldenCluster);
    store.getState().apply(goldenSnapshot);

    const state = store.getState();
    expect(state.clusters).toBeNull();
    expect(state.clusterTotal).toBe(0);
    expect(state.markers.size).toBe(2);
    expect(driversInView(state)).toBe(2);
  });

  it('keeps the last error until the next snapshot or clusters', () => {
    const store = createFleetStore();
    store.getState().apply(goldenError);
    expect(store.getState().serverError).toEqual({ code: 'BATCH_TOO_LARGE', message: goldenError.message });
    store.getState().apply(goldenDelta);
    expect(store.getState().serverError).toBeDefined();
    store.getState().apply(goldenSnapshot);
    expect(store.getState().serverError).toBeUndefined();
    store.getState().apply(goldenError);
    store.getState().apply(goldenCluster);
    expect(store.getState().serverError).toBeUndefined();
  });

  it('keeps the connection state', () => {
    const store = createFleetStore();
    expect(store.getState().connection).toEqual({ status: 'connecting' });
    store.getState().setConnection({ status: 'waiting', retryInMs: 500 });
    expect(store.getState().connection).toEqual({ status: 'waiting', retryInMs: 500 });
  });
});
