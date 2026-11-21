import { z } from 'zod';
import { errorMessage } from '../fleet/messages';
import { isFinalStatus, tripStatus } from './status';

// The messages of the staff trip socket, from protocol/src/main/resources/asyncapi/rts-ws-v1.yaml (channel
// `adminTrip`, the `guest` channel's messages). Frames arrive as text, so the client checks each one here at runtime.
// The schemas follow the contract's compatibility rules: unknown fields are dropped (z.object strips them), and the
// fields whose values "a later server may add" (`event`, `reason`) take any string.

const instant = z.iso.datetime({ offset: true });
const lat = z.number().min(-90).max(90);
const lng = z.number().min(-180).max(180);

export const point = z.object({ lat, lng });

const routeKind = z.enum(['TRIP', 'APPROACH']);

/** The route the driver follows now: APPROACH (driver to pickup) before the ride, TRIP during it. */
export const guestRoute = z.object({
  kind: routeKind,
  routeVersion: z.int().min(1),
  polyline: z.string(),
  distanceM: z.number().min(0),
  durationS: z.number().min(0),
});

export const guestTrip = z.object({
  tripId: z.string().min(1),
  status: tripStatus,
  pickup: point,
  destination: point,
  driverId: z.string().min(1).optional(),
  updatedAt: instant,
  route: guestRoute.optional(),
});

export const subscribed = z.object({ type: z.literal('subscribed'), trip: guestTrip });

export const driverPosition = z.object({
  type: z.literal('driver_position'),
  tripId: z.string(),
  lat,
  lng,
  bearingDeg: z.number().min(0).lt(360).optional(),
  speedMps: z.number().min(0).optional(),
  recordedAt: instant,
});

export const tripProgress = z.object({
  type: z.literal('trip_progress'),
  tripId: z.string(),
  target: z.enum(['PICKUP', 'DESTINATION']),
  remainingM: z.number().min(0),
  etaS: z.number().min(0),
  basis: z.enum(['STRAIGHT_LINE', 'ROUTE']),
  routeVersion: z.int().min(1).optional(),
});

export const tripEvent = z.object({
  type: z.literal('trip_event'),
  tripId: z.string(),
  // status_changed, driver_arriving, driver_low_battery, driver_stale, driver_back, off_route, and later ones.
  event: z.string(),
  status: tripStatus,
  at: instant,
  remainingM: z.number().min(0).optional(),
  levelPercent: z.int().min(0).max(100).optional(),
  lastSeenAt: instant.optional(),
  cancelledBy: z.string().optional(),
});

export const routeUpdated = z.object({
  type: z.literal('route_updated'),
  tripId: z.string(),
  kind: routeKind,
  routeVersion: z.int().min(1),
  polyline: z.string(),
  distanceM: z.number().min(0),
  durationS: z.number().min(0),
  // initial, status_changed, off_route, and later ones.
  reason: z.string(),
});

export const tripServerMessage = z.discriminatedUnion('type', [
  subscribed,
  driverPosition,
  tripProgress,
  tripEvent,
  routeUpdated,
  errorMessage,
]);

export type GuestTrip = z.infer<typeof guestTrip>;
export type GuestRoute = z.infer<typeof guestRoute>;
export type Subscribed = z.infer<typeof subscribed>;
export type DriverPosition = z.infer<typeof driverPosition>;
export type TripProgress = z.infer<typeof tripProgress>;
export type TripEvent = z.infer<typeof tripEvent>;
export type RouteUpdated = z.infer<typeof routeUpdated>;
export type TripServerMessage = z.infer<typeof tripServerMessage>;

/** The one client message: watch this trip. */
export interface TripSubscribe {
  type: 'subscribe';
  tripId: string;
}

/**
 * Whether this message is the trip's last: a `status_changed` to a final stage, after which the server closes the
 * socket with 1000. `subscribed` for a trip that has already ended counts too.
 */
export function endsTrip(message: TripServerMessage): boolean {
  if (message.type === 'subscribed') return isFinalStatus(message.trip.status);
  return message.type === 'trip_event' && message.event === 'status_changed' && isFinalStatus(message.status);
}

/**
 * The message in one text frame, or undefined when the frame is not JSON or fails its schema. A dropped frame is
 * logged with console.warn and the connection stays, as on the fleet socket (TC-12-FL-05).
 */
export function parseTripMessage(text: string): TripServerMessage | undefined {
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    console.warn('Trip socket: dropped a frame that is not JSON');
    return undefined;
  }
  const result = tripServerMessage.safeParse(json);
  if (!result.success) {
    const type = typeof json === 'object' && json !== null && 'type' in json ? String(json.type) : 'without a type';
    console.warn(`Trip socket: dropped a message (${type}) that fails its schema\n${z.prettifyError(result.error)}`);
    return undefined;
  }
  return result.data;
}
