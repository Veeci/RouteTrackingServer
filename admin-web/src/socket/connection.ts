import { isApiError } from '../api/errors';
import { Backoff } from './backoff';

// The connection life cycle that both admin sockets share (12e choice C1): the fleet socket (12d) and the trip socket.
// Each one authenticates with a single-use ticket, so every connect starts with a new ticket. What a socket sends and
// receives stays with its own client: this class moves text frames and knows nothing of their meaning.

/** Close codes the connection acts on (docs/architecture/protocol.md, `WsCloseCodes`). */
export const CloseCode = {
  /** A normal close: the client's, when the tab is hidden or the page left, or the server's after a final message. */
  NORMAL: 1000,
  /** The browser saw the connection drop, or a refused upgrade. The status of a refused upgrade is not visible. */
  ABNORMAL: 1006,
  /** The server found the client too slow to read the updates. The server is fine, so reconnect at once. */
  TRY_AGAIN_LATER: 1013,
  /** The ticket's role may not use this socket. Asking again will not change that. */
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

/** The part of the browser's WebSocket the connection uses, so tests can pass a fake. */
export interface AppSocket {
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  onerror: ((event: Event) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

/** Where the connection learns that the tab was hidden or shown. The default is `document`. */
export interface VisibilitySource {
  readonly visibilityState: DocumentVisibilityState;
  addEventListener(type: 'visibilitychange', listener: () => void): void;
  removeEventListener(type: 'visibilitychange', listener: () => void): void;
}

/** What every socket client takes, so tests can pass fakes. */
export interface SocketDeps {
  /**
   * A new single-use ticket from `POST /api/v1/admin/ws-tickets`. It rejects with an `ApiError` when the server
   * refuses, and with an AbortError when the client no longer wants it.
   */
  getTicket: (signal: AbortSignal) => Promise<string>;
  /** Opens a socket to a URL. Tests pass a fake. */
  openSocket?: (url: string) => AppSocket;
  visibility?: VisibilitySource;
  /** The page's address, for the socket's same-origin URL. */
  location?: Pick<Location, 'href'>;
  /** For the backoff's jitter. */
  random?: () => number;
}

export interface SocketConnectionOptions extends SocketDeps {
  /** The socket's path on the page's origin, such as `/ws/v1/admin/fleet`. */
  path: string;
  /** The socket's name in log lines, such as "Fleet socket". */
  name: string;
  onState: (state: ConnectionState) => void;
  /** The socket is open. The client sends its first message here, with {@link SocketConnection.send}. */
  onOpen: () => void;
  /** One text frame, as it arrived. A frame that is not text is logged and dropped before it gets here. */
  onText: (text: string) => void;
}

/** `ws:` or `wss:` on the page's own origin, which the dev proxy and nginx forward to the server. */
export function socketUrl(
  path: string,
  ticket: string,
  location: Pick<Location, 'href'> = globalThis.location,
): string {
  const url = new URL(path, location.href);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.searchParams.set('ticket', ticket);
  return url.href;
}

/**
 * One ticket socket's life cycle: get a ticket, open the socket, then live. It holds no React state and parses no
 * message: the client that owns it sends its first message in `onOpen` and reads each frame in `onText`.
 *
 * - A close or a failed ticket waits with full-jitter backoff (1 s, doubling up to 30 s), then starts again with a new
 *   ticket, because a ticket works once. The owner calls {@link resetBackoff} when a message proves the connection
 *   works.
 * - Close 1013 reconnects at once. Close 4403 stops for good. Close 1006 is a network drop, or an upgrade the server
 *   refused, whose HTTP status the browser does not show. Both wait and try again. A close 1000 from the server is
 *   also a drop here: an owner that expects it, after a final message, calls {@link stop} first.
 * - A 401 on the ticket stops the connection: the query client's handler opens the login page (12b). A 403 stops it
 *   with `forbidden`. A 429 waits at least `Retry-After`.
 * - A hidden tab closes the socket with 1000. Visible again, the connection starts over with a new ticket.
 */
export class SocketConnection {
  private state: ConnectionState = { status: 'stopped' };
  private started = false;
  private socket: AppSocket | null = null;
  private ticketRequest: AbortController | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private readonly backoff: Backoff;
  private readonly path: string;
  private readonly name: string;
  private readonly getTicket: SocketDeps['getTicket'];
  private readonly openSocket: (url: string) => AppSocket;
  private readonly onState: SocketConnectionOptions['onState'];
  private readonly onOpen: SocketConnectionOptions['onOpen'];
  private readonly onText: SocketConnectionOptions['onText'];
  private readonly visibility: VisibilitySource;
  private readonly location: Pick<Location, 'href'>;

  constructor(options: SocketConnectionOptions) {
    this.path = options.path;
    this.name = options.name;
    this.getTicket = options.getTicket;
    this.openSocket = options.openSocket ?? ((url) => new WebSocket(url));
    this.onState = options.onState;
    this.onOpen = options.onOpen;
    this.onText = options.onText;
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

  /** Sends one message as JSON when the socket is open. Returns whether it went out. */
  send(message: object): boolean {
    if (this.socket?.readyState !== OPEN) return false;
    this.socket.send(JSON.stringify(message));
    return true;
  }

  /** A message proved that the connection works, so the next failure starts from the shortest wait. */
  resetBackoff(): void {
    this.backoff.reset();
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
    let socket: AppSocket;
    try {
      socket = this.openSocket(socketUrl(this.path, ticket, this.location));
    } catch (error) {
      console.warn(`${this.name}: could not open`, error);
      this.retryIn(this.backoff.next());
      return;
    }
    this.socket = socket;
    socket.onopen = () => {
      this.setState({ status: 'live' });
      this.onOpen();
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
      console.warn(`${this.name}: dropped a frame that is not text`);
      return;
    }
    this.onText(data);
  }

  private closed(code: number): void {
    if (code === CloseCode.FORBIDDEN_ROLE) this.halt({ status: 'forbidden' });
    else if (code === CloseCode.TRY_AGAIN_LATER) this.connect();
    else this.retryIn(this.backoff.next());
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
  private detach(socket: AppSocket): void {
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
