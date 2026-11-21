import { validateStyleMin } from '@maplibre/maplibre-gl-style-spec';
import { describe, expect, it } from 'vitest';
import { FALLBACK_STYLE } from './layers';
import {
  allPositions,
  TRIP_LINES_SOURCE,
  TRIP_POINTS_SOURCE,
  TripLayerId,
  tripLayers,
  tripLines,
  type TripMapData,
  tripPoints,
} from './tripLayers';

const DATA: TripMapData = {
  pickup: { lat: 10.7725, lng: 106.698 },
  destination: { lat: 10.7798, lng: 106.699 },
  route: [
    [106.698, 10.7725],
    [106.699, 10.7798],
  ],
  track: [[106.698, 10.7725]],
  driver: { lat: 10.775, lng: 106.6985, bearingDeg: 90, lastKnown: false },
};

describe('trip layers', () => {
  it('pass the MapLibre style validator', () => {
    const empty = { type: 'FeatureCollection' as const, features: [] };
    const style = {
      ...FALLBACK_STYLE,
      sources: {
        [TRIP_LINES_SOURCE]: { type: 'geojson' as const, data: empty },
        [TRIP_POINTS_SOURCE]: { type: 'geojson' as const, data: empty },
      },
      layers: [...FALLBACK_STYLE.layers, ...tripLayers()],
    };
    expect(validateStyleMin(style)).toEqual([]);
  });

  it('draw the route under the recorded lines, and the driver on top', () => {
    expect(tripLayers().map((layer) => layer.id)).toEqual([
      TripLayerId.route,
      TripLayerId.approach,
      TripLayerId.track,
      TripLayerId.places,
      TripLayerId.driver,
      TripLayerId.heading,
    ]);
  });

  it('make a line only from two points or more', () => {
    expect(tripLines(DATA).features.map((feature) => feature.properties.kind)).toEqual(['route']);
  });

  it('mark the driver as live with a bearing, or as last known without one', () => {
    expect(tripPoints(DATA).features.map((feature) => feature.properties)).toEqual([
      { kind: 'pickup' },
      { kind: 'destination' },
      { kind: 'driver', bearing: 90 },
    ]);
    const seeded = tripPoints({ ...DATA, driver: { lat: 1, lng: 2, bearingDeg: 90, lastKnown: true } });
    expect(seeded.features.at(-1)).toEqual({
      type: 'Feature',
      properties: { kind: 'lastKnown' },
      geometry: { type: 'Point', coordinates: [2, 1] },
    });
    expect(tripPoints({ pickup: DATA.pickup, destination: DATA.destination }).features).toHaveLength(2);
  });

  it('fit the camera to every point, the driver included', () => {
    expect(allPositions(DATA)).toHaveLength(6);
  });
});
