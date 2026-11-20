import type { Bbox } from '../fleet/messages';

/** Where the map looks. It lives in the URL, so a shared link opens the same view. */
export interface MapView {
  lat: number;
  lng: number;
  zoom: number;
}

/** Central Hanoi, where the simulator drives and the road graph lives, wide enough for the inner districts. */
export const DEFAULT_VIEW: MapView = { lat: 21.0285, lng: 105.8542, zoom: 11 };

/** Web Mercator stops short of the poles. MapLibre clamps the centre to this latitude. */
const MAX_LAT = 85.051129;

/** The view from the URL's search params, with the default for each value that is absent. */
export function viewOf(search: Partial<MapView>): MapView {
  return {
    lat: search.lat ?? DEFAULT_VIEW.lat,
    lng: search.lng ?? DEFAULT_VIEW.lng,
    zoom: search.zoom ?? DEFAULT_VIEW.zoom,
  };
}

/**
 * The view as the URL keeps it: 5 decimals of a degree are about 1 m, and 2 decimals of a zoom level are less than a
 * pixel. A longer number would only make the link longer.
 */
export function roundView(view: MapView): MapView {
  return { lat: round(view.lat, 5), lng: round(view.lng, 5), zoom: round(view.zoom, 2) };
}

/** Whether two views differ by less than the rounding: a view and its echo through the URL are the same. */
export function sameView(a: MapView, b: MapView): boolean {
  return Math.abs(a.lat - b.lat) < 1e-4 && Math.abs(a.lng - b.lng) < 1e-4 && Math.abs(a.zoom - b.zoom) < 0.01;
}

export interface Edges {
  west: number;
  south: number;
  east: number;
  north: number;
}

/**
 * The visible area as the server's `bbox`: `[minLng, minLat, maxLng, maxLat]` within the world. The map shows one
 * world copy, but zoomed far out its edges still pass ±180 and ±85, and the server refuses a box outside the world or
 * across the antimeridian.
 */
export function bboxOf({ west, south, east, north }: Edges): Bbox {
  const minLng = clamp(west, -180, 180);
  const maxLng = clamp(east, -180, 180);
  const minLat = clamp(south, -MAX_LAT, MAX_LAT);
  const maxLat = clamp(north, -MAX_LAT, MAX_LAT);
  return [round(minLng, 6), round(minLat, 6), round(Math.max(minLng, maxLng), 6), round(Math.max(minLat, maxLat), 6)];
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function round(value: number, decimals: number): number {
  const factor = 10 ** decimals;
  return Math.round(value * factor) / factor;
}
