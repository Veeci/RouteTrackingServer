// Test doubles and fixtures for the fleet socket. The fixtures are the protocol's golden frames, the samples that a
// server test checks against the AsyncAPI document, parsed with the app's own schemas. A contract change that the
// schemas do not follow then fails these tests.
import clusterFrame from '../../../protocol/src/test/resources/golden/fleet_cluster.json';
import deltaFrame from '../../../protocol/src/test/resources/golden/fleet_delta.json';
import errorFrame from '../../../protocol/src/test/resources/golden/error.json';
import snapshotFrame from '../../../protocol/src/test/resources/golden/fleet_snapshot.json';
import subscribeFrame from '../../../protocol/src/test/resources/golden/fleet_subscribe.json';
import type { FleetSocket, VisibilitySource } from '../fleet/client';
import {
  type DriverMarker,
  errorMessage,
  fleetCluster,
  fleetDelta,
  fleetSnapshot,
  type FleetSubscribe,
} from '../fleet/messages';

/** Two drivers: 7c9e… online on a trip with a bearing, 0f8f… offline without one. */
export const goldenSnapshot = fleetSnapshot.parse(snapshotFrame);
/** Moves 7c9e… and removes 0f8f…. */
export const goldenDelta = fleetDelta.parse(deltaFrame);
/** Two cells, 5 324 drivers in all. */
export const goldenCluster = fleetCluster.parse(clusterFrame);
export const goldenError = errorMessage.parse(errorFrame);
/** The client message, as the server's golden test expects it. */
export const goldenSubscribe = subscribeFrame;

export const ON_TRIP_ID = '7c9e6679-7425-40de-944b-e07fc1f90ae7';
export const OFFLINE_ID = '0f8fad5b-d9cb-469f-a165-70867728950e';

export function marker(n: number, overrides: Partial<DriverMarker> = {}): DriverMarker {
  return {
    driverId: `5a0e3c1d-2b4f-4a6e-8c1d-${String(n).padStart(12, '0')}`,
    lat: 21.02 + n / 1_000,
    lng: 105.84 + n / 1_000,
    online: true,
    lastSeenAt: '2026-11-20T08:30:00Z',
    ...overrides,
  };
}

/** A socket the test drives: it records what the client sends, and the test plays the server's part. */
export class FakeSocket implements FleetSocket {
  readyState = 0;
  onopen: FleetSocket['onopen'] = null;
  onmessage: FleetSocket['onmessage'] = null;
  onclose: FleetSocket['onclose'] = null;
  onerror: FleetSocket['onerror'] = null;
  readonly sent: string[] = [];
  /** The code the client closed with, or undefined while it has not closed. */
  closedWith: number | undefined;

  constructor(readonly url: string) {}

  send(data: string): void {
    if (this.readyState !== 1) throw new Error('send() on a socket that is not open');
    this.sent.push(data);
  }

  close(code = 1000): void {
    this.closedWith = code;
    this.readyState = 3;
  }

  /** The `fleet_subscribe` messages the client sent, parsed. */
  subscriptions(): FleetSubscribe[] {
    return this.sent.map((text) => JSON.parse(text) as FleetSubscribe);
  }

  serverOpens(): void {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }

  /** A text frame: an object is sent as JSON, a string as it is. */
  serverSends(message: unknown): void {
    const data = typeof message === 'string' ? message : JSON.stringify(message);
    this.onmessage?.(new MessageEvent('message', { data }));
  }

  serverSendsBinary(): void {
    this.onmessage?.(new MessageEvent('message', { data: new ArrayBuffer(4) }));
  }

  serverCloses(code: number, reason = ''): void {
    this.readyState = 3;
    this.onerror?.(new Event('error'));
    this.onclose?.(new CloseEvent('close', { code, reason }));
  }
}

/** A socket factory that keeps every socket it made. */
export function fakeSockets() {
  const all: FakeSocket[] = [];
  return {
    all,
    open: (url: string): FakeSocket => {
      const socket = new FakeSocket(url);
      all.push(socket);
      return socket;
    },
    /** The newest socket. */
    last: (): FakeSocket => {
      const socket = all.at(-1);
      if (socket === undefined) throw new Error('No socket was opened');
      return socket;
    },
  };
}

/** The page's visibility, which the test sets. */
export class FakeVisibility implements VisibilitySource {
  visibilityState: DocumentVisibilityState = 'visible';
  private readonly target = new EventTarget();

  addEventListener(type: 'visibilitychange', listener: () => void): void {
    this.target.addEventListener(type, listener);
  }

  removeEventListener(type: 'visibilitychange', listener: () => void): void {
    this.target.removeEventListener(type, listener);
  }

  set(state: DocumentVisibilityState): void {
    this.visibilityState = state;
    this.target.dispatchEvent(new Event('visibilitychange'));
  }
}
