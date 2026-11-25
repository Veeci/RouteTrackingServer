import { z } from 'zod';

// The messages of the admin fleet socket, from protocol/src/main/resources/asyncapi/rts-ws-v1.yaml (channel
// `adminFleet`). REST types are checked at compile time. Socket frames arrive as text, so the client checks each one
// here at runtime. The schemas follow the contract's two compatibility rules: a reader ignores fields it does not know
// (z.object strips them), and it accepts error codes the contract does not list.

const instant = z.iso.datetime({ offset: true });

/** One driver on the map. */
export const driverMarker = z.object({
  // Only a key for the store and the map. A format check would drop a whole snapshot over one odd id.
  driverId: z.string().min(1),
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  /** Degrees, 0 = north. Absent when the newest fix had no bearing. */
  bearingDeg: z.number().min(0).lt(360).optional(),
  /** The server heard from the driver within the stale time (60 s), by the server's clock (`heardAt`). */
  online: z.boolean(),
  /** The stage of the driver's current trip. Absent when they are not on one. */
  tripStatus: z.string().optional(),
  batteryPct: z.int().min(0).max(100).optional(),
  /** When the newest position or status was measured, by the phone's clock. Can be old while the driver is online. */
  lastSeenAt: instant,
  /** When the server last heard from the driver, by the server's clock. Absent until it has. */
  heardAt: instant.optional(),
});

/** One grid cell with drivers in it, drawn at the drivers' mean position. */
export const fleetCell = z.object({
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  count: z.int().min(1),
});

export const fleetSnapshot = z.object({
  type: z.literal('fleet_snapshot'),
  drivers: z.array(driverMarker),
  at: instant,
});

export const fleetDelta = z.object({
  type: z.literal('fleet_delta'),
  upserts: z.array(driverMarker),
  removes: z.array(z.string().min(1)),
  at: instant,
});

export const fleetCluster = z.object({
  type: z.literal('fleet_cluster'),
  cells: z.array(fleetCell),
  cellDeg: z.number().positive(),
  total: z.int().min(0),
  at: instant,
});

/** The shared `error` message: a recoverable problem with one client message. */
export const errorMessage = z.object({
  type: z.literal('error'),
  // The contract lists codes, and a client must accept codes it does not list.
  code: z.string(),
  message: z.string(),
  correlatesTo: z.int().optional(),
});

export const fleetServerMessage = z.discriminatedUnion('type', [fleetSnapshot, fleetDelta, fleetCluster, errorMessage]);

export type DriverMarker = z.infer<typeof driverMarker>;
export type FleetCell = z.infer<typeof fleetCell>;
export type FleetSnapshot = z.infer<typeof fleetSnapshot>;
export type FleetDelta = z.infer<typeof fleetDelta>;
export type FleetCluster = z.infer<typeof fleetCluster>;
export type ErrorMessage = z.infer<typeof errorMessage>;
export type FleetServerMessage = z.infer<typeof fleetServerMessage>;

/** `[minLng, minLat, maxLng, maxLat]`, edges inside, minimum first. It may not cross the antimeridian. */
export type Bbox = readonly [minLng: number, minLat: number, maxLng: number, maxLat: number];

/** An absent field does not filter. */
export interface FleetFilters {
  /** Only drivers heard from within the stale time (true), or only the others (false). */
  online?: boolean | undefined;
  /** Only drivers on a trip (true), or only the others (false). */
  onTrip?: boolean | undefined;
}

/** What the client watches: the map area and the filters. */
export interface FleetArea {
  bbox: Bbox;
  filters: FleetFilters;
}

/** The one client message. A new one replaces the previous area and filters. */
export interface FleetSubscribe {
  type: 'fleet_subscribe';
  bbox: Bbox;
  filters: FleetFilters;
}

export function fleetSubscribe({ bbox, filters }: FleetArea): FleetSubscribe {
  return { type: 'fleet_subscribe', bbox, filters };
}

/**
 * The message in one text frame, or undefined when the frame is not JSON or fails its schema. A dropped frame is
 * logged with console.warn and the connection stays (TC-12-FL-05): one bad message should not cost the operator the
 * live map.
 */
export function parseServerMessage(text: string): FleetServerMessage | undefined {
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    console.warn('Fleet socket: dropped a frame that is not JSON');
    return undefined;
  }
  const result = fleetServerMessage.safeParse(json);
  if (!result.success) {
    const type = typeof json === 'object' && json !== null && 'type' in json ? String(json.type) : 'without a type';
    console.warn(`Fleet socket: dropped a message (${type}) that fails its schema\n${z.prettifyError(result.error)}`);
    return undefined;
  }
  return result.data;
}
