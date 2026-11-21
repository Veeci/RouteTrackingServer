import { decodePolyline, type Position } from './polyline';

/**
 * A polyline's points, or undefined when there is none or it cannot be read. A page draws the rest of the trip
 * without the route then, and the warning names the problem for a bug report.
 */
export function decodeOrNothing(polyline: string | undefined): Position[] | undefined {
  if (polyline === undefined) return undefined;
  try {
    return decodePolyline(polyline);
  } catch (error) {
    console.warn('Trip: could not read the route', error);
    return undefined;
  }
}
