import type { ExpressionSpecification, LayerSpecification } from 'maplibre-gl';
import type { Position } from '../trip/polyline';
import { DRIVER_COLORS, HEADING_IMAGE } from './layers';

// The trip map's sources and layers: one GeoJSON source of lines and one of points, each feature tagged with a `kind`.
// Plain data, so the layer order and the colours read in one place, and the style validator checks them in a test.

export const TRIP_LINES_SOURCE = 'trip-lines';
export const TRIP_POINTS_SOURCE = 'trip-points';

export const TripLayerId = {
  route: 'trip-route',
  approach: 'trip-approach',
  track: 'trip-track',
  places: 'trip-places',
  driver: 'trip-driver',
  heading: 'trip-heading',
} as const;

/** The colours the page's legend shows. */
export const TRIP_COLORS = {
  route: '#5c7cfa',
  approach: '#f08c00',
  track: DRIVER_COLORS.onTrip,
  pickup: '#2f9e44',
  destination: '#e03131',
  driver: '#1c7ed6',
  lastKnown: DRIVER_COLORS.offline,
} as const;

export interface LatLng {
  lat: number;
  lng: number;
}

/** What the trip map draws. Every part but the two places is optional. */
export interface TripMapData {
  pickup: LatLng;
  destination: LatLng;
  /** The planned road route: the trip's route in replay, the route the driver follows now in live mode. */
  route?: readonly Position[] | undefined;
  /** Replay: where the driver drove from accept to the pickup. */
  approach?: readonly Position[] | undefined;
  /** Replay: the recorded ride. Live: the trail of positions since the page opened (C4). */
  track?: readonly Position[] | undefined;
  /** Live: the driver now, or the last known position before the first update (C3). */
  driver?: (LatLng & { bearingDeg?: number | undefined; lastKnown: boolean }) | undefined;
}

type LineKind = 'route' | 'approach' | 'track';
type PointKind = 'pickup' | 'destination' | 'driver' | 'lastKnown';

export interface Collection<P> {
  type: 'FeatureCollection';
  features: {
    type: 'Feature';
    properties: P;
    geometry: { type: 'LineString'; coordinates: readonly Position[] } | { type: 'Point'; coordinates: Position };
  }[];
}

/** The lines, bottom to top as drawn. A line needs two points. */
export function tripLines(data: TripMapData): Collection<{ kind: LineKind }> {
  const lines: [LineKind, readonly Position[] | undefined][] = [
    ['route', data.route],
    ['approach', data.approach],
    ['track', data.track],
  ];
  return {
    type: 'FeatureCollection',
    features: lines
      .filter((entry): entry is [LineKind, readonly Position[]] => entry[1] !== undefined && entry[1].length >= 2)
      .map(([kind, coordinates]) => ({
        type: 'Feature',
        properties: { kind },
        geometry: { type: 'LineString', coordinates },
      })),
  };
}

/** The pickup, the destination and the driver. */
export function tripPoints(data: TripMapData): Collection<{ kind: PointKind; bearing?: number }> {
  const point = (kind: PointKind, { lat, lng }: LatLng, bearing?: number) => ({
    type: 'Feature' as const,
    properties: { kind, ...(bearing === undefined ? {} : { bearing }) },
    geometry: { type: 'Point' as const, coordinates: [lng, lat] as Position },
  });
  const { driver } = data;
  return {
    type: 'FeatureCollection',
    features: [
      point('pickup', data.pickup),
      point('destination', data.destination),
      ...(driver === undefined
        ? []
        : [point(driver.lastKnown ? 'lastKnown' : 'driver', driver, driver.lastKnown ? undefined : driver.bearingDeg)]),
    ],
  };
}

/** Every coordinate on the map, for the first camera fit. */
export function allPositions(data: TripMapData): Position[] {
  const places: Position[] = [
    [data.pickup.lng, data.pickup.lat],
    [data.destination.lng, data.destination.lat],
  ];
  if (data.driver !== undefined) places.push([data.driver.lng, data.driver.lat]);
  return [...places, ...(data.route ?? []), ...(data.approach ?? []), ...(data.track ?? [])];
}

const isKind = (kind: string): ExpressionSpecification => ['==', ['get', 'kind'], kind];

export function tripLayers(): LayerSpecification[] {
  return [
    {
      id: TripLayerId.route,
      type: 'line',
      source: TRIP_LINES_SOURCE,
      filter: isKind('route'),
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': TRIP_COLORS.route, 'line-width': 4, 'line-opacity': 0.6, 'line-dasharray': [2, 1.5] },
    },
    {
      id: TripLayerId.approach,
      type: 'line',
      source: TRIP_LINES_SOURCE,
      filter: isKind('approach'),
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': TRIP_COLORS.approach, 'line-width': 3 },
    },
    {
      id: TripLayerId.track,
      type: 'line',
      source: TRIP_LINES_SOURCE,
      filter: isKind('track'),
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': TRIP_COLORS.track, 'line-width': 4 },
    },
    {
      id: TripLayerId.places,
      type: 'circle',
      source: TRIP_POINTS_SOURCE,
      filter: ['in', ['get', 'kind'], ['literal', ['pickup', 'destination']]],
      paint: {
        'circle-radius': 7,
        'circle-color': ['match', ['get', 'kind'], 'pickup', TRIP_COLORS.pickup, TRIP_COLORS.destination],
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': 2,
      },
    },
    {
      // A last known position is hollow and grey: the operator must not read it as live.
      id: TripLayerId.driver,
      type: 'circle',
      source: TRIP_POINTS_SOURCE,
      filter: ['in', ['get', 'kind'], ['literal', ['driver', 'lastKnown']]],
      paint: {
        'circle-radius': 8,
        'circle-color': ['match', ['get', 'kind'], 'driver', TRIP_COLORS.driver, '#ffffff'],
        'circle-stroke-color': ['match', ['get', 'kind'], 'driver', '#ffffff', TRIP_COLORS.lastKnown],
        'circle-stroke-width': 3,
      },
    },
    {
      id: TripLayerId.heading,
      type: 'symbol',
      source: TRIP_POINTS_SOURCE,
      filter: ['all', isKind('driver'), ['has', 'bearing']],
      layout: {
        'icon-image': HEADING_IMAGE,
        'icon-rotate': ['get', 'bearing'],
        'icon-rotation-alignment': 'map',
        'icon-offset': [0, -16],
        'icon-allow-overlap': true,
        'icon-ignore-placement': true,
      },
      paint: { 'icon-color': TRIP_COLORS.driver },
    },
  ];
}
