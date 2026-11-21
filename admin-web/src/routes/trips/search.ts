import { z } from 'zod';
import { TRIP_STATUSES, tripStatus, type TripStatus } from '../../trip/status';
import { cursorParam, dayParam, uuidParam } from '../search';

/**
 * The stages to list. The router keeps an array as JSON in the URL (`?status=["ACCEPTED","ARRIVED"]`), and a hand-made
 * link may hold one plain value. Unknown values drop out, the rest keep the contract's order, and none means "every
 * stage", so the URL has one spelling per filter.
 */
export const statusParam = z
  .union([z.array(z.unknown()), z.unknown()])
  .transform((value): TripStatus[] | undefined => {
    const given = new Set(
      (Array.isArray(value) ? value : [value]).filter((item) => tripStatus.safeParse(item).success),
    );
    const statuses = TRIP_STATUSES.filter((status) => given.has(status));
    return statuses.length === 0 ? undefined : statuses;
  })
  .optional()
  .catch(undefined);

/** The Trips page's filters and cursor, in the URL. `from` and `to` are whole days, both included. */
export const tripsSearch = z.object({
  status: statusParam,
  driverId: uuidParam,
  guestId: uuidParam,
  from: dayParam,
  to: dayParam,
  cursor: cursorParam,
});

export type TripsSearch = z.infer<typeof tripsSearch>;
