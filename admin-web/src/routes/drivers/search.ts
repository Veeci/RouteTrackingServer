import { z } from 'zod';
import { cursorParam, flagParam, textParam } from '../search';

/** The Drivers page's filters and cursor, in the URL (C1). */
export const driversSearch = z.object({
  online: flagParam,
  onTrip: flagParam,
  /** Part of the display name in any case, or a whole driver id (`q` in the API, 1 to 100 characters). */
  q: textParam(100),
  cursor: cursorParam,
});

export type DriversSearch = z.infer<typeof driversSearch>;
