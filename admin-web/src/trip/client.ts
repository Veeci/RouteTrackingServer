import { type ConnectionState, SocketConnection, type SocketDeps } from '../socket/connection';
import { endsTrip, parseTripMessage, type TripServerMessage, type TripSubscribe } from './messages';

export const TRIP_SOCKET_PATH = '/ws/v1/admin/trip';

/** The connection's states, plus `ended`: the trip reached a final stage, so nothing more will come. Final. */
export type TripConnectionState = ConnectionState | { status: 'ended' };

export interface TripClientOptions extends SocketDeps {
  tripId: string;
  /** Every server message that passed its schema. */
  onMessage: (message: TripServerMessage) => void;
  onState: (state: TripConnectionState) => void;
}

/**
 * The staff trip socket (`/ws/v1/admin/trip`) on top of the shared {@link SocketConnection}: send `subscribe` with the
 * trip id on every new socket, and hand each server message that passes its schema to `onMessage`.
 *
 * - `subscribed` proves the connection works, so the backoff starts over.
 * - After the trip's last message (a `status_changed` to COMPLETED or CANCELLED, or `subscribed` for a trip that has
 *   already ended) the client stops itself and settles in `ended`. The server closes the socket with 1000 right after
 *   that message. Without the stop, the connection would read that close as a drop and connect again.
 * - A close 1000 without a final message, for example a server shutting down, is a drop: the client reconnects and
 *   `subscribed` brings the trip as it is then.
 */
export class TripClient {
  private ended = false;
  private readonly connectionCore: SocketConnection;
  private readonly tripId: string;
  private readonly onMessage: TripClientOptions['onMessage'];
  private readonly onState: TripClientOptions['onState'];

  constructor(options: TripClientOptions) {
    this.tripId = options.tripId;
    this.onMessage = options.onMessage;
    this.onState = options.onState;
    this.connectionCore = new SocketConnection({
      ...options,
      path: TRIP_SOCKET_PATH,
      name: 'Trip socket',
      onState: (state) => {
        // The stop after the last message reports `stopped`. The page needs to know why it stopped.
        this.onState(this.ended && state.status === 'stopped' ? { status: 'ended' } : state);
      },
      onOpen: () => {
        const subscribe: TripSubscribe = { type: 'subscribe', tripId: this.tripId };
        this.connectionCore.send(subscribe);
      },
      onText: (text) => {
        this.received(text);
      },
    });
  }

  get connection(): TripConnectionState {
    const state = this.connectionCore.connection;
    return this.ended && state.status === 'stopped' ? { status: 'ended' } : state;
  }

  /** Connects, or waits for the tab to become visible. Does nothing after the trip ended. */
  start(): void {
    if (this.ended) return;
    this.connectionCore.start();
  }

  /** Closes the socket with 1000 and stops every timer. */
  stop(): void {
    this.connectionCore.stop();
  }

  private received(text: string): void {
    const message = parseTripMessage(text);
    if (message === undefined) return;
    if (message.type === 'subscribed') this.connectionCore.resetBackoff();
    const last = endsTrip(message);
    if (last) this.ended = true;
    this.onMessage(message);
    if (last) this.connectionCore.stop();
  }
}
