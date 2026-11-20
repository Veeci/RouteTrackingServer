import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';
import type { ConnectionState } from './client';
import type { DriverMarker, FleetCell, FleetServerMessage } from './messages';

/** A recoverable problem the server reported with the last `fleet_subscribe`. */
export interface ServerError {
  code: string;
  message: string;
}

export interface FleetState {
  /** The drivers on the map, by id. Empty while the server sends clusters. */
  markers: ReadonlyMap<string, DriverMarker>;
  /** The grid cells while the area holds too many drivers to send one by one, else null. */
  clusters: readonly FleetCell[] | null;
  /** Matching drivers in the area while it is clustered. */
  clusterTotal: number;
  /** The server time of the last snapshot, delta or clusters. */
  at: string | undefined;
  connection: ConnectionState;
  serverError: ServerError | undefined;
  apply: (message: FleetServerMessage) => void;
  setConnection: (connection: ConnectionState) => void;
}

/**
 * The live map's data, in a vanilla store outside React: the socket writes it, the map reads it once a frame, and the
 * status line and the side panel subscribe to the few values they show. Markers are never React components.
 *
 * Every change makes a new `markers` map or `clusters` array. A reader then tells "changed" from "the same" by
 * reference, and a selector of one driver re-renders only when that driver's marker is replaced.
 */
export function createFleetStore() {
  return createStore<FleetState>()((set) => ({
    markers: new Map(),
    clusters: null,
    clusterTotal: 0,
    at: undefined,
    connection: { status: 'connecting' },
    serverError: undefined,
    apply: (message) => {
      set((state) => reduce(state, message));
    },
    setConnection: (connection) => {
      set({ connection });
    },
  }));
}

export type FleetStore = ReturnType<typeof createFleetStore>;

type Data = Pick<FleetState, 'markers' | 'clusters' | 'clusterTotal' | 'at' | 'serverError'>;

/** The store after one server message (TC-12-FL-01). */
export function reduce(state: Data, message: FleetServerMessage): Partial<Data> {
  switch (message.type) {
    case 'fleet_snapshot':
      // Replaces everything, and ends a clustered view: the area holds few enough drivers again.
      return {
        markers: new Map(message.drivers.map((marker) => [marker.driverId, marker])),
        clusters: null,
        clusterTotal: 0,
        at: message.at,
        serverError: undefined,
      };
    case 'fleet_delta': {
      // The server sends no delta while it sends clusters. A late one has no markers to change.
      if (state.clusters !== null) return {};
      const markers = new Map(state.markers);
      for (const marker of message.upserts) markers.set(marker.driverId, marker);
      for (const driverId of message.removes) markers.delete(driverId);
      return { markers, at: message.at };
    }
    case 'fleet_cluster':
      return {
        markers: new Map(),
        clusters: message.cells,
        clusterTotal: message.total,
        at: message.at,
        serverError: undefined,
      };
    case 'error':
      return { serverError: { code: message.code, message: message.message } };
  }
}

/** The drivers the status line counts: the markers, or the clusters' total. */
export function driversInView(state: Pick<FleetState, 'markers' | 'clusters' | 'clusterTotal'>): number {
  return state.clusters === null ? state.markers.size : state.clusterTotal;
}

export function useFleet<T>(store: FleetStore, selector: (state: FleetState) => T): T {
  return useStore(store, selector);
}
