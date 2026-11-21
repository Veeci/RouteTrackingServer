import {
  type AppSocket,
  CloseCode,
  type ConnectionState,
  SocketConnection,
  type SocketDeps,
  socketUrl,
  type VisibilitySource,
} from '../socket/connection';
import { type FleetArea, type FleetServerMessage, fleetSubscribe, parseServerMessage } from './messages';

// The connection life cycle moved to src/socket/connection.ts in 12e (choice C1). These names stay here, so the 12d
// code and tests read as before.
export { CloseCode, type ConnectionState, type VisibilitySource };
export type FleetSocket = AppSocket;

export const FLEET_SOCKET_PATH = '/ws/v1/admin/fleet';

export interface FleetClientOptions extends SocketDeps {
  /** Every server message that passed its schema. */
  onMessage: (message: FleetServerMessage) => void;
  onState: (state: ConnectionState) => void;
}

export function fleetSocketUrl(ticket: string, location: Pick<Location, 'href'> = globalThis.location): string {
  return socketUrl(FLEET_SOCKET_PATH, ticket, location);
}

/**
 * The fleet socket's messages on top of the shared {@link SocketConnection}: send `fleet_subscribe` once the socket is
 * open, and hand each server message that passes its schema to `onMessage`. It holds no React state and no marker.
 *
 * - Each new socket subscribes again with the current area and filters, so a reconnect shows the same view.
 * - A snapshot or clusters mean the connection works, so the backoff starts over. A delta does not prove that the
 *   subscription went through.
 * - Close codes, tickets, the backoff and the hidden tab are the connection's rules (see {@link SocketConnection}).
 */
export class FleetClient {
  private area: FleetArea | undefined;
  private readonly connectionCore: SocketConnection;
  private readonly onMessage: FleetClientOptions['onMessage'];

  constructor(options: FleetClientOptions) {
    this.onMessage = options.onMessage;
    this.connectionCore = new SocketConnection({
      ...options,
      path: FLEET_SOCKET_PATH,
      name: 'Fleet socket',
      onOpen: () => {
        this.sendSubscription();
      },
      onText: (text) => {
        this.received(text);
      },
    });
  }

  get connection(): ConnectionState {
    return this.connectionCore.connection;
  }

  /** Connects, or waits for the tab to become visible. Calling it again does nothing. */
  start(): void {
    this.connectionCore.start();
  }

  /** Closes the socket with 1000 and stops every timer. `start` begins again. */
  stop(): void {
    this.connectionCore.stop();
  }

  /** Watch this area with these filters. It is sent now when the socket is open, and again on every new socket. */
  subscribe(area: FleetArea): void {
    this.area = area;
    this.sendSubscription();
  }

  private received(text: string): void {
    const message = parseServerMessage(text);
    if (message === undefined) return;
    if (message.type === 'fleet_snapshot' || message.type === 'fleet_cluster') this.connectionCore.resetBackoff();
    this.onMessage(message);
  }

  private sendSubscription(): void {
    if (this.area === undefined) return;
    this.connectionCore.send(fleetSubscribe(this.area));
  }
}
