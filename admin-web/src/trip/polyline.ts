// Google's Encoded Polyline Algorithm Format, precision 5, as the server writes routes (`TripRoute.polyline`,
// `route_updated.polyline`). The format, from Google's "Encoded Polyline Algorithm Format" page:
//
// - Each point is two signed integers, the latitude and the longitude times 10^5, rounded. The first point is stored
//   as it is, every later point as the difference from the one before, which keeps the numbers small.
// - A signed integer v becomes unsigned by "zig-zag": v << 1, inverted when v is negative. The lowest bit then holds
//   the sign.
// - The unsigned number is cut into 5-bit chunks, lowest first. Every chunk but the last has 0x20 added, which says
//   "more follows". Each chunk plus 63 is one printable ASCII character.

/** `[lng, lat]`, the GeoJSON order, so a decoded route goes straight into a LineString. */
export type Position = [lng: number, lat: number];

const PRECISION = 1e5;

/**
 * The points of an encoded polyline, as `[lng, lat]`. Throws on text that is not a whole polyline: a character
 * outside the alphabet, a number cut off in the middle, or a latitude without its longitude. A route that cannot be
 * read is a server bug, and a half-drawn route would mislead the operator.
 */
export function decodePolyline(encoded: string): Position[] {
  const points: Position[] = [];
  let index = 0;
  let lat = 0;
  let lng = 0;

  const nextValue = (): number => {
    let result = 0;
    let shift = 0;
    for (;;) {
      if (index >= encoded.length) throw new Error(`Polyline ends inside a number at character ${String(index)}`);
      const chunk = encoded.charCodeAt(index) - 63;
      if (chunk < 0 || chunk > 63) {
        throw new Error(`Polyline has a character outside its alphabet at ${String(index)}`);
      }
      index += 1;
      // Multiplying instead of shifting keeps the number exact past 32 bits, where `<<` would wrap.
      result += (chunk & 0x1f) * 2 ** shift;
      shift += 5;
      if (chunk < 0x20) break;
    }
    // Undo the zig-zag: an odd number is negative.
    return result % 2 === 1 ? -(result + 1) / 2 : result / 2;
  };

  while (index < encoded.length) {
    lat += nextValue();
    if (index >= encoded.length) throw new Error('Polyline has a latitude without its longitude');
    lng += nextValue();
    points.push([lng / PRECISION, lat / PRECISION]);
  }
  return points;
}
