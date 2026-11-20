import { z } from 'zod';
import { flagParam, numberParam, uuidParam } from '../search';

/**
 * The live map's filters, view and selected driver, in the URL. Map moves replace the history entry, so Back does
 * not step through every pan. A filter change or a selection adds an entry. A shared link opens the same view.
 */
export const mapSearch = z.object({
  online: flagParam,
  onTrip: flagParam,
  lat: numberParam(-85.051129, 85.051129),
  lng: numberParam(-180, 180),
  zoom: numberParam(0, 22),
  /** The driver whose panel is open. */
  driver: uuidParam,
});

export type MapSearch = z.infer<typeof mapSearch>;
