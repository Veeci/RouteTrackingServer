import { describe, expect, it } from 'vitest';
import routeUpdatedFrame from '../../../protocol/src/test/resources/golden/route_updated.json';
import { decodePolyline, type Position } from './polyline';

function expectClose(actual: Position[], expected: Position[]) {
  expect(actual).toHaveLength(expected.length);
  actual.forEach(([lng, lat], i) => {
    expect(lng).toBeCloseTo(expected[i]?.[0] ?? Number.NaN, 5);
    expect(lat).toBeCloseTo(expected[i]?.[1] ?? Number.NaN, 5);
  });
}

describe('decodePolyline', () => {
  it("decodes Google's documented example", () => {
    // From Google's "Encoded Polyline Algorithm Format" page: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453).
    expectClose(decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@'), [
      [-120.2, 38.5],
      [-120.95, 40.7],
      [-126.453, 43.252],
    ]);
  });

  it('decodes the golden route_updated frame, which the server wrote', () => {
    expectClose(decodePolyline(routeUpdatedFrame.polyline), [
      [106.65066, 10.67904],
      [106.65162, 10.68614],
    ]);
  });

  it('keeps zero, negative and repeated points', () => {
    expectClose(decodePolyline('??~hbE~hbE??'), [
      [0, 0],
      [-1, -1],
      [-1, -1],
    ]);
  });

  it('reads an empty polyline as no points', () => {
    expect(decodePolyline('')).toEqual([]);
  });

  it('refuses a number cut off in the middle', () => {
    expect(() => decodePolyline('_p~iF~ps|')).toThrow(/ends inside a number/);
  });

  it('refuses a latitude without its longitude', () => {
    expect(() => decodePolyline('_p~iF')).toThrow(/without its longitude/);
  });

  it('refuses a character outside the alphabet', () => {
    expect(() => decodePolyline('_p~iF ps|U')).toThrow(/outside its alphabet/);
  });
});
