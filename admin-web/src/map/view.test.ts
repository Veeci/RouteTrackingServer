import { describe, expect, it } from 'vitest';
import { bboxOf, DEFAULT_VIEW, roundView, sameView, viewOf } from './view';

describe('viewOf', () => {
  it('fills each missing value from the default view', () => {
    expect(viewOf({})).toEqual(DEFAULT_VIEW);
    expect(viewOf({ zoom: 14 })).toEqual({ ...DEFAULT_VIEW, zoom: 14 });
    expect(viewOf({ lat: 10.5, lng: 106.7, zoom: 9 })).toEqual({ lat: 10.5, lng: 106.7, zoom: 9 });
  });
});

describe('roundView and sameView', () => {
  it('rounds a view to what the URL keeps, and calls the two the same', () => {
    const exact = { lat: 21.028_512_34, lng: 105.854_198_76, zoom: 12.345_6 };
    const rounded = roundView(exact);
    expect(rounded).toEqual({ lat: 21.02851, lng: 105.8542, zoom: 12.35 });
    expect(sameView(exact, rounded)).toBe(true);
  });

  it('tells a real move from rounding', () => {
    expect(sameView(DEFAULT_VIEW, { ...DEFAULT_VIEW, lat: DEFAULT_VIEW.lat + 0.001 })).toBe(false);
    expect(sameView(DEFAULT_VIEW, { ...DEFAULT_VIEW, zoom: DEFAULT_VIEW.zoom + 0.1 })).toBe(false);
  });
});

describe('bboxOf', () => {
  it('orders the edges as the server wants them', () => {
    expect(bboxOf({ west: 105.78, south: 20.99, east: 105.88, north: 21.06 })).toEqual([105.78, 20.99, 105.88, 21.06]);
  });

  it('keeps a zoomed-out view inside the world', () => {
    expect(bboxOf({ west: -230.5, south: -89, east: 250.1, north: 89 })).toEqual([-180, -85.051129, 180, 85.051129]);
  });

  it('never puts the minimum after the maximum', () => {
    const [minLng, minLat, maxLng, maxLat] = bboxOf({ west: 190, south: 86, east: 200, north: 88 });
    expect(minLng).toBeLessThanOrEqual(maxLng);
    expect(minLat).toBeLessThanOrEqual(maxLat);
  });
});
