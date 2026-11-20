import type { DriverMarker, FleetCell } from './messages';
import type { FleetState, FleetStore } from './store';

// The store as GeoJSON for the map's two sources. The types are the few parts of RFC 7946 the map uses, so this
// module does not depend on MapLibre.

export interface PointFeature<P> {
  type: 'Feature';
  geometry: { type: 'Point'; coordinates: [number, number] };
  properties: P;
}

export interface FeatureCollection<P> {
  type: 'FeatureCollection';
  features: PointFeature<P>[];
}

/** How the map colours a driver. Offline wins: an offline driver's position and trip are stale. */
export type DriverStatus = 'offline' | 'onTrip' | 'online';

export interface DriverProperties {
  driverId: string;
  status: DriverStatus;
  /** Absent when the newest fix had no bearing. The heading layer draws only features that have it. */
  bearing?: number;
}

export interface ClusterProperties {
  count: number;
  /** The count as the map writes it: 950, 3.1k, 12k. */
  label: string;
}

export function driverStatus(marker: Pick<DriverMarker, 'online' | 'tripStatus'>): DriverStatus {
  if (!marker.online) return 'offline';
  return marker.tripStatus === undefined ? 'online' : 'onTrip';
}

function point<P>(lng: number, lat: number, properties: P): PointFeature<P> {
  return { type: 'Feature', geometry: { type: 'Point', coordinates: [lng, lat] }, properties };
}

export function driverFeatures(markers: ReadonlyMap<string, DriverMarker>): FeatureCollection<DriverProperties> {
  const features: PointFeature<DriverProperties>[] = [];
  for (const marker of markers.values()) {
    const properties: DriverProperties = { driverId: marker.driverId, status: driverStatus(marker) };
    if (marker.bearingDeg !== undefined) properties.bearing = marker.bearingDeg;
    features.push(point(marker.lng, marker.lat, properties));
  }
  return { type: 'FeatureCollection', features };
}

const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });

export function clusterFeatures(cells: readonly FleetCell[]): FeatureCollection<ClusterProperties> {
  return {
    type: 'FeatureCollection',
    features: cells.map((cell) => point(cell.lng, cell.lat, { count: cell.count, label: compact.format(cell.count) })),
  };
}

/** The map's two GeoJSON sources. */
export interface GeoJsonTargets {
  setDrivers: (data: FeatureCollection<DriverProperties>) => void;
  setClusters: (data: FeatureCollection<ClusterProperties>) => void;
}

/** requestAnimationFrame and its cancel. Tests pass a fake that runs frames on demand. */
export interface FrameScheduler {
  request: (callback: () => void) => number;
  cancel: (handle: number) => void;
}

const browserFrames: FrameScheduler = {
  request: (callback) => requestAnimationFrame(callback),
  cancel: (handle) => {
    cancelAnimationFrame(handle);
  },
};

/**
 * Keeps the map's sources in step with the store, converting at most once per animation frame. Several messages in
 * one frame cost one conversion, and a source is set only when its part of the store is a new object. Returns a stop
 * function.
 */
export function syncGeoJson(
  store: FleetStore,
  targets: GeoJsonTargets,
  frames: FrameScheduler = browserFrames,
): () => void {
  let pending: number | undefined;
  let drawnMarkers: FleetState['markers'] | undefined;
  let drawnClusters: FleetState['clusters'] | undefined;

  const draw = () => {
    pending = undefined;
    const { markers, clusters } = store.getState();
    if (markers !== drawnMarkers) {
      drawnMarkers = markers;
      targets.setDrivers(driverFeatures(markers));
    }
    if (clusters !== drawnClusters) {
      drawnClusters = clusters;
      targets.setClusters(clusterFeatures(clusters ?? []));
    }
  };

  draw();
  const unsubscribe = store.subscribe(() => {
    pending ??= frames.request(draw);
  });
  return () => {
    unsubscribe();
    if (pending !== undefined) frames.cancel(pending);
  };
}
