import type { ExpressionSpecification, LayerSpecification, StyleSpecification } from 'maplibre-gl';

// The fleet map's sources and layers, drawn on the GPU from two GeoJSON sources. Plain data, so the layer order and
// the colours read in one place.

/** OpenFreeMap's "liberty" style (decision D6): free, no key, OpenStreetMap data. Its attribution comes with it. */
export const BASE_STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty';

/**
 * Drawn when the base style does not load: a plain background, so the drivers still show. The glyphs are OpenFreeMap's
 * too, for the cluster counts. Without them only the counts are missing.
 */
export const FALLBACK_STYLE: StyleSpecification = {
  version: 8,
  glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
  sources: {},
  layers: [{ id: 'background', type: 'background', paint: { 'background-color': '#f1f3f5' } }],
};

export const DRIVERS_SOURCE = 'fleet-drivers';
export const CLUSTERS_SOURCE = 'fleet-clusters';

export const LayerId = {
  clusters: 'fleet-clusters',
  clusterCounts: 'fleet-cluster-counts',
  selected: 'fleet-selected',
  drivers: 'fleet-drivers',
  headings: 'fleet-headings',
} as const;

export const HEADING_IMAGE = 'fleet-heading';

/** Mantine's green 8, blue 7 and gray 6, the colours the legend shows. */
export const DRIVER_COLORS = {
  online: '#2f9e44',
  onTrip: '#1c7ed6',
  offline: '#868e96',
} as const;

const SELECTED_COLOR = '#e8590c';
const CLUSTER_COLOR = '#5f3dc4';

const statusColor: ExpressionSpecification = [
  'match',
  ['get', 'status'],
  'onTrip',
  DRIVER_COLORS.onTrip,
  'online',
  DRIVER_COLORS.online,
  DRIVER_COLORS.offline,
];

/** Zoom levels and dot radii: smaller dots when zoomed out, so 5 000 drivers over a city stay readable. */
const RADIUS_STOPS: readonly (readonly [zoom: number, radius: number])[] = [
  [5, 2.5],
  [10, 4],
  [14, 6],
  [18, 9],
];

/**
 * The dot radius at the current zoom, plus `extra` pixels. MapLibre accepts a zoom expression only as the outermost
 * `interpolate`, so the extra goes into each stop, not around the expression.
 */
export function driverRadius(extra = 0): ExpressionSpecification {
  return ['interpolate', ['linear'], ['zoom'], ...RADIUS_STOPS.flatMap(([zoom, radius]) => [zoom, radius + extra])];
}

/** The filter that shows only the selected driver's ring. An empty id matches no driver. */
export function selectedFilter(driverId: string | undefined): ExpressionSpecification {
  return ['==', ['get', 'driverId'], driverId ?? ''];
}

export function fleetLayers(selectedDriverId: string | undefined): LayerSpecification[] {
  return [
    {
      id: LayerId.clusters,
      type: 'circle',
      source: CLUSTERS_SOURCE,
      paint: {
        'circle-color': CLUSTER_COLOR,
        'circle-opacity': 0.8,
        'circle-radius': ['step', ['get', 'count'], 14, 100, 18, 1_000, 24, 5_000, 30],
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': 2,
      },
    },
    {
      id: LayerId.clusterCounts,
      type: 'symbol',
      source: CLUSTERS_SOURCE,
      layout: {
        'text-field': ['get', 'label'],
        'text-font': ['Noto Sans Bold'],
        'text-size': 12,
        'text-allow-overlap': true,
        'text-ignore-placement': true,
      },
      paint: { 'text-color': '#ffffff' },
    },
    {
      id: LayerId.selected,
      type: 'circle',
      source: DRIVERS_SOURCE,
      filter: selectedFilter(selectedDriverId),
      paint: {
        'circle-radius': driverRadius(5),
        'circle-color': 'rgba(0, 0, 0, 0)',
        'circle-stroke-color': SELECTED_COLOR,
        'circle-stroke-width': 3,
      },
    },
    {
      id: LayerId.drivers,
      type: 'circle',
      source: DRIVERS_SOURCE,
      paint: {
        'circle-color': statusColor,
        'circle-radius': driverRadius(),
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': ['interpolate', ['linear'], ['zoom'], 5, 0.5, 12, 1.5],
      },
    },
    {
      // An arrow in front of the dot, turned to the bearing. Only drivers with a bearing, and only zoomed in, where
      // the arrows do not cover each other.
      id: LayerId.headings,
      type: 'symbol',
      source: DRIVERS_SOURCE,
      minzoom: 12,
      filter: ['has', 'bearing'],
      layout: {
        'icon-image': HEADING_IMAGE,
        'icon-rotate': ['get', 'bearing'],
        'icon-rotation-alignment': 'map',
        'icon-offset': [0, -14],
        'icon-size': ['interpolate', ['linear'], ['zoom'], 12, 0.6, 16, 1],
        'icon-allow-overlap': true,
        'icon-ignore-placement': true,
      },
      paint: { 'icon-color': statusColor },
    },
  ];
}

/**
 * The heading arrow as a signed distance field image, so the layer can colour it per driver. It points up, to the
 * north, and `icon-rotate` turns it to the bearing. Drawn on a canvas, so the app needs no sprite file.
 */
export function headingArrow(size = 32): ImageData | undefined {
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext('2d');
  if (context === null) return undefined;
  context.fillStyle = '#000000';
  context.beginPath();
  context.moveTo(size / 2, size * 0.1);
  context.lineTo(size * 0.85, size * 0.85);
  context.lineTo(size / 2, size * 0.65);
  context.lineTo(size * 0.15, size * 0.85);
  context.closePath();
  context.fill();
  return context.getImageData(0, 0, size, size);
}
