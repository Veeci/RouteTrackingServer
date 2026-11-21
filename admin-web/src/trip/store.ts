import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';
import type { ServerError } from '../fleet/store';
import type { TripConnectionState } from './client';
import type { GuestRoute, GuestTrip, TripEvent, TripProgress, TripServerMessage } from './messages';
import type { TripStatus } from './status';
import { decodePolyline, type Position } from './polyline';

/** C4: the live trail keeps this many positions, the newest. About 70 minutes at one fix every 2 s. */
export const TRAIL_CAP = 2_000;
/** The event list keeps this many, the newest. */
export const EVENTS_CAP = 50;

/** The driver's position on the map. */
export interface LivePosition {
  lat: number;
  lng: number;
  bearingDeg?: number | undefined;
  speedMps?: number | undefined;
  /** When the phone recorded it, or when the server last heard from the driver for a seeded position. */
  at: string | undefined;
  /** C3: seeded from the driver's detail before the first `driver_position`. The page labels it "last known". */
  lastKnown: boolean;
}

/** The route the driver follows now, decoded for the map. */
export interface LiveRoute {
  kind: GuestRoute['kind'];
  routeVersion: number;
  distanceM: number;
  durationS: number;
  line: Position[];
}

export interface TripLiveState {
  /** The trip from the last `subscribed`, with the stage kept current by `status_changed`. */
  trip: GuestTrip | undefined;
  route: LiveRoute | undefined;
  position: LivePosition | undefined;
  /** C4: every position since the page opened, oldest first, at most {@link TRAIL_CAP}. A reload starts it over. */
  trail: readonly Position[];
  progress: TripProgress | undefined;
  /** Every `trip_event` since the page opened, newest first, at most {@link EVENTS_CAP}. */
  events: readonly TripEvent[];
  connection: TripConnectionState;
  serverError: ServerError | undefined;
  apply: (message: TripServerMessage) => void;
  setConnection: (connection: TripConnectionState) => void;
  /** C3: a position to show until the first `driver_position`. Ignored once the socket sent one. */
  seedPosition: (position: { lat: number; lng: number; at: string | undefined }) => void;
}

/** The route as the map draws it, or undefined when its polyline cannot be read. */
export function liveRoute(route: GuestRoute): LiveRoute | undefined {
  try {
    const line = decodePolyline(route.polyline);
    return {
      kind: route.kind,
      routeVersion: route.routeVersion,
      distanceM: route.distanceM,
      durationS: route.durationS,
      line,
    };
  } catch (error) {
    console.warn(`Trip socket: could not read route version ${String(route.routeVersion)}`, error);
    return undefined;
  }
}

/** The trail with one more position: an exact repeat of the last one adds nothing, and the oldest fall off. */
export function extendTrail(trail: readonly Position[], next: Position): readonly Position[] {
  const last = trail.at(-1);
  if (last?.[0] === next[0] && last[1] === next[1]) return trail;
  const extended = [...trail, next];
  return extended.length > TRAIL_CAP ? extended.slice(extended.length - TRAIL_CAP) : extended;
}

function withStatus(trip: GuestTrip | undefined, status: TripStatus): GuestTrip | undefined {
  return trip === undefined || trip.status === status ? trip : { ...trip, status };
}

/**
 * One trip's live view, in a vanilla store outside React: the trip socket writes it, and the page subscribes to the
 * values it shows. Every change makes new objects, so a reader compares by reference.
 */
export function createTripStore() {
  return createStore<TripLiveState>()((set) => ({
    trip: undefined,
    route: undefined,
    position: undefined,
    trail: [],
    progress: undefined,
    events: [],
    connection: { status: 'connecting' },
    serverError: undefined,
    apply: (message) => {
      switch (message.type) {
        case 'subscribed': {
          // A reconnect sends the trip as it is now. The trail and the events stay: they are what this page saw.
          const route = message.trip.route === undefined ? undefined : liveRoute(message.trip.route);
          set({ trip: message.trip, route, serverError: undefined });
          return;
        }
        case 'driver_position': {
          const { lat, lng, bearingDeg, speedMps, recordedAt } = message;
          set((state) => ({
            position: { lat, lng, bearingDeg, speedMps, at: recordedAt, lastKnown: false },
            trail: extendTrail(state.trail, [lng, lat]),
          }));
          return;
        }
        case 'trip_progress':
          set({ progress: message });
          return;
        case 'trip_event':
          set((state) => ({
            events: [message, ...state.events].slice(0, EVENTS_CAP),
            trip: message.event === 'status_changed' ? withStatus(state.trip, message.status) : state.trip,
          }));
          return;
        case 'route_updated': {
          const route = liveRoute(message);
          // An unreadable new route leaves the old one on the map, which is still the best guess.
          if (route !== undefined) set({ route });
          return;
        }
        case 'error':
          set({ serverError: { code: message.code, message: message.message } });
          return;
      }
    },
    setConnection: (connection) => {
      set({ connection });
    },
    seedPosition: ({ lat, lng, at }) => {
      set((state) => (state.position === undefined ? { position: { lat, lng, at, lastKnown: true } } : {}));
    },
  }));
}

export type TripStore = ReturnType<typeof createTripStore>;

export function useTripLive<T>(store: TripStore, selector: (state: TripLiveState) => T): T {
  return useStore(store, selector);
}
