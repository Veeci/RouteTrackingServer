import { describe, expect, it, vi } from 'vitest';
import { goldenCluster, goldenDelta, goldenSnapshot, marker, OFFLINE_ID, ON_TRIP_ID } from '../test/fleet';
import { clusterFeatures, driverFeatures, driverStatus, type FrameScheduler, syncGeoJson } from './geojson';
import { createFleetStore } from './store';

/** Frames that run only when the test says so. */
function manualFrames() {
  const callbacks = new Map<number, () => void>();
  let next = 1;
  const frames: FrameScheduler = {
    request: (callback) => {
      callbacks.set(next, callback);
      return next++;
    },
    cancel: (handle) => {
      callbacks.delete(handle);
    },
  };
  return {
    frames,
    pending: () => callbacks.size,
    run: () => {
      const due = [...callbacks.values()];
      callbacks.clear();
      for (const callback of due) callback();
    },
  };
}

describe('driverStatus', () => {
  it('colours offline first, then on a trip, then online', () => {
    expect(driverStatus({ online: false, tripStatus: 'IN_PROGRESS' })).toBe('offline');
    expect(driverStatus({ online: true, tripStatus: 'APPROACHING' })).toBe('onTrip');
    expect(driverStatus({ online: true })).toBe('online');
  });
});

describe('driverFeatures', () => {
  it('makes one point per driver at [lng, lat], with a bearing only when the marker has one', () => {
    const markers = new Map(goldenSnapshot.drivers.map((driver) => [driver.driverId, driver]));
    expect(driverFeatures(markers)).toEqual({
      type: 'FeatureCollection',
      features: [
        {
          type: 'Feature',
          geometry: { type: 'Point', coordinates: [105.8542, 21.0285] },
          properties: { driverId: ON_TRIP_ID, status: 'onTrip', bearing: 87 },
        },
        {
          type: 'Feature',
          geometry: { type: 'Point', coordinates: [105.8411, 21.0301] },
          properties: { driverId: OFFLINE_ID, status: 'offline' },
        },
      ],
    });
  });

  it('keeps a bearing of 0, which is north', () => {
    const north = marker(1, { bearingDeg: 0 });
    expect(driverFeatures(new Map([[north.driverId, north]])).features[0]?.properties.bearing).toBe(0);
  });
});

describe('clusterFeatures', () => {
  it('makes one point per cell with the count and a short label', () => {
    const features = clusterFeatures([...goldenCluster.cells, { lat: 21, lng: 105.8, count: 950 }]).features;
    expect(features.map((feature) => feature.properties)).toEqual([
      { count: 3_120, label: '3.1K' },
      { count: 2_204, label: '2.2K' },
      { count: 950, label: '950' },
    ]);
    expect(features[0]?.geometry.coordinates).toEqual([105.8433, 21.0212]);
  });
});

describe('syncGeoJson', () => {
  it('sets both sources at once, then at most once a frame, and only the part that changed', () => {
    const store = createFleetStore();
    const { frames, run, pending } = manualFrames();
    const setDrivers = vi.fn();
    const setClusters = vi.fn();
    syncGeoJson(store, { setDrivers, setClusters }, frames);
    expect(setDrivers).toHaveBeenCalledOnce();
    expect(setClusters).toHaveBeenCalledOnce();

    store.getState().apply(goldenSnapshot);
    store.getState().apply(goldenDelta);
    expect(pending()).toBe(1);
    expect(setDrivers).toHaveBeenCalledOnce();
    run();
    expect(setDrivers).toHaveBeenCalledTimes(2);
    // Two messages in one frame: one conversion, of the state after both.
    expect(setDrivers.mock.lastCall?.[0]).toEqual(driverFeatures(store.getState().markers));
    expect(setClusters).toHaveBeenCalledOnce();

    // A change that is not data, such as the connection state, sets no source.
    store.getState().setConnection({ status: 'paused' });
    run();
    expect(setDrivers).toHaveBeenCalledTimes(2);
    expect(setClusters).toHaveBeenCalledOnce();
  });

  it('clusters clear the drivers source and fill the clusters source', () => {
    const store = createFleetStore();
    const { frames, run } = manualFrames();
    const setDrivers = vi.fn();
    const setClusters = vi.fn();
    store.getState().apply(goldenSnapshot);
    syncGeoJson(store, { setDrivers, setClusters }, frames);

    store.getState().apply(goldenCluster);
    run();
    expect(setDrivers.mock.lastCall?.[0]).toEqual({ type: 'FeatureCollection', features: [] });
    expect(setClusters.mock.lastCall?.[0]).toEqual(clusterFeatures(goldenCluster.cells));
  });

  it('stop cancels the frame it asked for and listens no more', () => {
    const store = createFleetStore();
    const { frames, pending } = manualFrames();
    const stop = syncGeoJson(store, { setDrivers: vi.fn(), setClusters: vi.fn() }, frames);
    store.getState().apply(goldenSnapshot);
    expect(pending()).toBe(1);
    stop();
    expect(pending()).toBe(0);
    store.getState().apply(goldenDelta);
    expect(pending()).toBe(0);
  });
});
