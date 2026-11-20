import { isApiError } from '../api/errors';
import { Backoff } from './backoff';
import { type FleetArea, type FleetServerMessage, fleetSubscribe, parseServerMessage } from './messages';

export const FLEET_SOCKET_PATH = '/ws/v1/admin/fleet';

/** Close codes the client acts on (docs/architecture/protocol.md, `WsCloseCodes`). */
export const CloseCode = {
  /** The client closed on purpose: the tab is hidden or the page left. */
  NORMAL: 1000,
  /** The browser saw the connection drop, or a refused upgrade. The status of a refused upgrade is not visible. */
  ABNORMAL: 1006,
  /** The server found the client too slow to read the updates. The server is fine, so reconnect at once. */
  TRY_AGAIN_LATER: 1013,
  /** The ticket's role may not watch the fleet. Asking again will not change that. */
  FORBIDDEN_ROLE: 4403,
} as const;

/** `WebSocket.OPEN`. The constant is on the class, which a fake socket does not have. */
const OPEN = 1;

/**
 * Where the connection is. `waiting` is the pause before the next try. `paused` means the tab is hidden. `forbidden`
 * and `stopped` are final: nothing reconnects from them.
 */
export type ConnectionState =
  | { status: 'connecting' }
  | { status: 'live' }
  | { status: 'waiting'; retryInMs: number }
  | { status: 'paused' }
  | { status: 'forbidden' }
  | { status: 'stopped' };

/** The part of the browser's WebSocket the client uses, so tests can pass a fake. */
export interface FleetSocket {
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  onerror: ((event: Event) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

/** Where the client learns that the tab was hidden or shown. The default is `document`. */
export interface VisibilitySource {
  readonly visibilityState: DocumentVisibilityState;
  addEventListener(type: 'visibilitychange', listener: () => void): void;
  removeEventListener(type: 'visibilitychange', listener: () => void): void;
}

export interface FleetClientOptions {
  /**
   * A new single-use ticket from `POST /api/v1/admin/ws-tickets`. It rejects with an `ApiError` when the server
   * refuses, and with an AbortError when the client no longer wants it.
   */
  getTicket: (signal: AbortSignal) => Promise<string>;
  /** Opens a socket to a URL. Tests pass a fake. */
  openSocket?: (url: string) => FleetSocket;
  /** Every server message that passed its schema. */
  onMessage: (message: FleetServerMessage) => void;
  onState: (state: ConnectionState) => void;
  visibility?: VisibilitySource;
  /** The page's address, for the socket's same-origin URL. */
  location?: Pick<Location, 'href'>;
  /** For the backoff's jitter. */
  random?: () => number;
}

/** `ws:` or `wss:` on the page's own origin, which the dev proxy and nginx forward to the server. */
export function fleetSocketUrl(ticket: string, location: Pick<Location, 'href'> = globalThis.location): string {
  const url = new URL(FLEET_SOCKET_PATH, location.href);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.searchParams.set('ticket', ticket);
  return url.href;
}

/**
 * The fleet socket's connection life cycle: get a ticket, open the socket, send `fleet_subscribe`, then live. It holds
 * no React state and no marker: it hands each message to `onMessage` and each state to `onState`.
 *
 * - A close or a failed ticket waits with full-jitter backoff (1 s, doubling up to 30 s), then starts again with a new
 *   ticket, because a ticket works once. The backoff starts over after a snapshot or clusters arrive.
 * - Close 1013 reconnects at once. Close 4403 stops for good. Close 1006 is a network drop, or an upgrade the server
 *   refused, whose HTTP status the browser does not show. Both wait and try again.
 * - A 401 on the ticket stops the client: the query client's handler opens the login page (12b). A 403 stops it with
 *   `forbidden`. A 429 waits at least `Retry-After`.
 * - A hidden tab closes the socket with 1000. Visible again, the client connects with a new ticket and gets a fresh
 *   snapshot. Each new socket subscribes again with the current area and filters.
 */
export class FleetClient {
  private state: ConnectionState = { status: 'stopped' };
  private started = false;
  private socket: FleetSocket | null = null;
  private ticketRequest: AbortController | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private area: FleetArea | undefined;
  private readonly backoff: Backoff;
  private readonly getTicket: FleetClientOptions['getTicket'];
  private readonly openSocket: (url: string) => FleetSocket;
  private readonly onMessage: FleetClientOptions['onMessage'];
  private readonly onState: FleetClientOptions['onState'];
  private readonly visibility: VisibilitySource;
  private readonly location: Pick<Location, 'href'>;

  constructor(options: FleetClientOptions) {
    this.getTicket = options.getTicket;
    this.openSocket = options.openSocket ?? ((url) => new WebSocket(url));
    this.onMessage = options.onMessage;
    this.onState = options.onState;
    this.visibility = options.visibility ?? document;
    this.location = options.location ?? globalThis.location;
    this.backoff = new Backoff(1_000, 30_000, options.random);
  }

  get connection(): ConnectionState {
    return this.state;
  }

  /** Connects, or waits for the tab to become visible. Calling it again does nothing. */
  start(): void {
    if (this.started) return;
    this.started = true;
    this.visibility.addEventListener('visibilitychange', this.onVisibilityChange);
    if (this.visibility.visibilityState === 'hidden') this.setState({ status: 'paused' });
    else this.connect();
  }

  /** Closes the socket with 1000 and stops every timer. `start` begins again. */
  stop(): void {
    this.started = false;
    this.visibility.removeEventListener('visibilitychange', this.onVisibilityChange);
    this.halt({ status: 'stopped' });
  }

  /** Watch this area with these filters. It is sent now when the socket is open, and again on every new socket. */
  subscribe(area: FleetArea): void {
    this.area = area;
    this.sendSubscription();
  }

  private readonly onVisibilityChange = () => {
    if (!this.started || this.isFinal()) return;
    if (this.visibility.visibilityState === 'hidden') {
      this.halt({ status: 'paused' });
    } else if (this.state.status === 'paused') {
      // Hiding the tab was not a failure, so the next failure starts from the shortest wait.
      this.backoff.reset();
      this.connect();
    }
  };

  private isFinal(): boolean {
    return this.state.status === 'forbidden' || this.state.status === 'stopped';
  }

  private connect(): void {
    this.clearRetry();
    this.setState({ status: 'connecting' });
    const request = new AbortController();
    this.ticketRequest = request;
    void this.getTicket(request.signal).then(
      (ticket) => {
        if (request.signal.aborted) return;
        this.ticketRequest = null;
        this.open(ticket);
      },
      (error: unknown) => {
        if (request.signal.aborted) return;
        this.ticketRequest = null;
        this.ticketFailed(error);
      },
    );
  }

  private ticketFailed(error: unknown): void {
    if (isApiError(error, 401)) {
      // The query client's 401 handler has already cleared the session and opened the login page.
      this.halt({ status: 'stopped' });
    } else if (isApiError(error, 403)) {
      this.halt({ status: 'forbidden' });
    } else {
      const retryAfterMs = isApiError(error, 429) && error.retryAfter !== undefined ? error.retryAfter * 1_000 : 0;
      this.retryIn(Math.max(this.backoff.next(), retryAfterMs));
    }
  }

  private open(ticket: string): void {
    let socket: FleetSocket;
    try {
      socket = this.openSocket(fleetSocketUrl(ticket, this.location));
    } catch (error) {
      console.warn('Fleet socket: could not open', error);
      this.retryIn(this.backoff.next());
      return;
    }
    this.socket = socket;
    socket.onopen = () => {
      this.setState({ status: 'live' });
      this.sendSubscription();
    };
    socket.onmessage = (event) => {
      this.received(event.data);
    };
    socket.onclose = (event) => {
      this.detach(socket);
      this.closed(event.code);
    };
    // Every error event is followed by a close event, which decides what happens next.
    socket.onerror = null;
  }

  private received(data: unknown): void {
    if (typeof data !== 'string') {
      console.warn('Fleet socket: dropped a frame that is not text');
      return;
    }
    const message = parseServerMessage(data);
    if (message === undefined) return;
    // A snapshot or clusters mean the connection works, so the next failure starts from the shortest wait.
    if (message.type === 'fleet_snapshot' || message.type === 'fleet_cluster') this.backoff.reset();
    this.onMessage(message);
  }

  private closed(code: number): void {
    if (code === CloseCode.FORBIDDEN_ROLE) this.halt({ status: 'forbidden' });
    else if (code === CloseCode.TRY_AGAIN_LATER) this.connect();
    else this.retryIn(this.backoff.next());
  }

  private sendSubscription(): void {
    if (this.area === undefined || this.socket?.readyState !== OPEN) return;
    this.socket.send(JSON.stringify(fleetSubscribe(this.area)));
  }

  private retryIn(ms: number): void {
    this.clearRetry();
    this.setState({ status: 'waiting', retryInMs: ms });
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      this.connect();
    }, ms);
  }

  /** Cancels the ticket request and the timer, closes the socket with 1000, and settles in `state`. */
  private halt(state: ConnectionState): void {
    this.ticketRequest?.abort();
    this.ticketRequest = null;
    this.clearRetry();
    const socket = this.socket;
    if (socket !== null) {
      this.detach(socket);
      socket.close(CloseCode.NORMAL);
    }
    this.setState(state);
  }

  /** Forgets a socket. Events it still fires reach no handler, so an old socket cannot start a reconnect. */
  private detach(socket: FleetSocket): void {
    socket.onopen = null;
    socket.onmessage = null;
    socket.onclose = null;
    socket.onerror = null;
    if (this.socket === socket) this.socket = null;
  }

  private clearRetry(): void {
    if (this.retryTimer !== undefined) clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
  }

  private setState(state: ConnectionState): void {
    this.state = state;
    this.onState(state);
  }
}
