import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, networkError } from '../api/errors';
import {
  FakeVisibility,
  fakeSockets,
  goldenCluster,
  goldenDelta,
  goldenSnapshot,
  goldenSubscribe,
} from '../test/fleet';
import { CloseCode, type ConnectionState, FleetClient, fleetSocketUrl } from './client';
import type { FleetArea, FleetServerMessage } from './messages';

const AREA: FleetArea = { bbox: [105.78, 20.99, 105.88, 21.06], filters: { online: true } };
const OTHER_AREA: FleetArea = { bbox: [105.7, 20.9, 105.98, 21.12], filters: { onTrip: true } };

/** With random 0.5 the waits are half the ceiling: 500 ms, 1 000 ms, 2 000 ms, … up to 15 000 ms. */
const FIRST_WAIT = 500;

function setup({ tickets }: { tickets?: (n: number, signal: AbortSignal) => Promise<string> } = {}) {
  const sockets = fakeSockets();
  const visibility = new FakeVisibility();
  const messages: FleetServerMessage[] = [];
  const states: ConnectionState[] = [];
  let issued = 0;
  const getTicket = vi.fn((signal: AbortSignal) => {
    issued += 1;
    return tickets === undefined ? Promise.resolve(`ticket-${String(issued)}`) : tickets(issued, signal);
  });
  const client = new FleetClient({
    getTicket,
    openSocket: sockets.open,
    onMessage: (message) => messages.push(message),
    onState: (state) => states.push(state),
    visibility,
    location: { href: 'http://localhost:5173/map?online=true' },
    random: () => 0.5,
  });
  return { client, sockets, visibility, messages, states, getTicket };
}

/** Lets the ticket promise settle without moving the clock. */
async function settle() {
  await vi.advanceTimersByTimeAsync(0);
}

/** Starts the client and opens its first socket. */
async function connected(parts: ReturnType<typeof setup>) {
  parts.client.start();
  await settle();
  const socket = parts.sockets.last();
  socket.serverOpens();
  return socket;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('fleetSocketUrl', () => {
  it('opens the socket on the page origin, with wss on an https page', () => {
    expect(fleetSocketUrl('a b', { href: 'http://localhost:4173/map' })).toBe(
      'ws://localhost:4173/ws/v1/admin/fleet?ticket=a+b',
    );
    expect(fleetSocketUrl('t', { href: 'https://rts-staging.example.ts.net:8443/map?x=1' })).toBe(
      'wss://rts-staging.example.ts.net:8443/ws/v1/admin/fleet?ticket=t',
    );
  });
});

describe('FleetClient', () => {
  it('gets a ticket, opens the socket with it, and subscribes once the socket is open', async () => {
    const parts = setup();
    parts.client.subscribe(AREA);
    parts.client.start();
    expect(parts.client.connection).toEqual({ status: 'connecting' });
    await settle();

    const socket = parts.sockets.last();
    expect(socket.url).toBe('ws://localhost:5173/ws/v1/admin/fleet?ticket=ticket-1');
    expect(socket.sent).toEqual([]);
    socket.serverOpens();
    expect(parts.client.connection).toEqual({ status: 'live' });
    // The same frame as the protocol's golden sample.
    expect(socket.subscriptions()).toEqual([goldenSubscribe]);

    socket.serverSends(goldenSnapshot);
    socket.serverSends(goldenDelta);
    expect(parts.messages).toEqual([goldenSnapshot, goldenDelta]);
    expect(parts.states.map((state) => state.status)).toEqual(['connecting', 'live']);
  });

  it('sends a new subscription at once on an open socket', async () => {
    const parts = setup();
    const socket = await connected(parts);
    expect(socket.sent).toEqual([]);
    parts.client.subscribe(AREA);
    parts.client.subscribe(OTHER_AREA);
    expect(socket.subscriptions().map((message) => message.bbox)).toEqual([AREA.bbox, OTHER_AREA.bbox]);
  });

  it('TC-12-FL-02: after a close it waits, fetches a new ticket and subscribes again with the current area', async () => {
    const parts = setup();
    parts.client.subscribe(AREA);
    const first = await connected(parts);
    first.serverSends(goldenSnapshot);
    // The map moved while connected. The reconnect must use the new area, not the first one.
    parts.client.subscribe(OTHER_AREA);

    first.serverCloses(CloseCode.ABNORMAL);
    expect(parts.client.connection).toEqual({ status: 'waiting', retryInMs: FIRST_WAIT });
    await vi.advanceTimersByTimeAsync(FIRST_WAIT - 1);
    expect(parts.getTicket).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(parts.getTicket).toHaveBeenCalledTimes(2);

    const second = parts.sockets.last();
    expect(second).not.toBe(first);
    expect(second.url).toContain('ticket=ticket-2');
    second.serverOpens();
    expect(second.subscriptions()).toEqual([
      { type: 'fleet_subscribe', bbox: OTHER_AREA.bbox, filters: OTHER_AREA.filters },
    ]);
  });

  it('TC-12-FL-02: a subscription made while disconnected is the one the next socket sends', async () => {
    const parts = setup();
    parts.client.subscribe(AREA);
    const first = await connected(parts);
    first.serverCloses(CloseCode.ABNORMAL);
    parts.client.subscribe(OTHER_AREA);
    await vi.advanceTimersByTimeAsync(FIRST_WAIT);
    const second = parts.sockets.last();
    second.serverOpens();
    expect(second.subscriptions().map((message) => message.bbox)).toEqual([OTHER_AREA.bbox]);
  });

  it('TC-12-FL-03: close 4403 stops for good', async () => {
    const parts = setup();
    const socket = await connected(parts);
    socket.serverCloses(CloseCode.FORBIDDEN_ROLE);
    expect(parts.client.connection).toEqual({ status: 'forbidden' });
    await vi.advanceTimersByTimeAsync(120_000);
    expect(parts.getTicket).toHaveBeenCalledTimes(1);
    // Showing the tab again does not reconnect either.
    parts.visibility.set('hidden');
    parts.visibility.set('visible');
    await settle();
    expect(parts.sockets.all).toHaveLength(1);
    expect(parts.client.connection).toEqual({ status: 'forbidden' });
  });

  it('TC-12-FL-03: close 1013 reconnects at once with a new ticket', async () => {
    const parts = setup();
    parts.client.subscribe(AREA);
    const first = await connected(parts);
    first.serverCloses(CloseCode.TRY_AGAIN_LATER);
    expect(parts.client.connection).toEqual({ status: 'connecting' });
    await settle();
    expect(parts.getTicket).toHaveBeenCalledTimes(2);
    const second = parts.sockets.last();
    expect(second.url).toContain('ticket=ticket-2');
    second.serverOpens();
    expect(second.subscriptions()).toHaveLength(1);
  });

  it('close 1006 waits with a growing backoff, and a snapshot resets it', async () => {
    const parts = setup();
    const waits: number[] = [];
    let socket = await connected(parts);
    for (let i = 0; i < 3; i += 1) {
      socket.serverCloses(CloseCode.ABNORMAL);
      const state = parts.client.connection;
      if (state.status !== 'waiting') throw new Error(`Expected waiting, got ${state.status}`);
      waits.push(state.retryInMs);
      await vi.advanceTimersByTimeAsync(state.retryInMs);
      socket = parts.sockets.last();
      socket.serverOpens();
    }
    expect(waits).toEqual([500, 1_000, 2_000]);

    // A snapshot proves the connection works: the next failure waits the shortest time again.
    socket.serverSends(goldenSnapshot);
    socket.serverCloses(CloseCode.ABNORMAL);
    expect(parts.client.connection).toEqual({ status: 'waiting', retryInMs: FIRST_WAIT });
  });

  it('clusters reset the backoff too, but a delta does not', async () => {
    const parts = setup();
    let socket = await connected(parts);
    socket.serverCloses(CloseCode.ABNORMAL);
    await vi.advanceTimersByTimeAsync(FIRST_WAIT);
    socket = parts.sockets.last();
    socket.serverOpens();
    socket.serverSends(goldenDelta);
    socket.serverCloses(CloseCode.ABNORMAL);
    expect(parts.client.connection).toEqual({ status: 'waiting', retryInMs: 1_000 });

    await vi.advanceTimersByTimeAsync(1_000);
    socket = parts.sockets.last();
    socket.serverOpens();
    socket.serverSends(goldenCluster);
    socket.serverCloses(1011);
    expect(parts.client.connection).toEqual({ status: 'waiting', retryInMs: FIRST_WAIT });
  });

  it('caps the wait at 30 s', async () => {
    const parts = setup({ tickets: () => Promise.reject(networkError(new TypeError('Failed to fetch'))) });
    parts.client.start();
    await settle();
    const waits: number[] = [];
    for (let i = 0; i < 8; i += 1) {
      const state = parts.client.connection;
      if (state.status !== 'waiting') throw new Error(`Expected waiting, got ${state.status}`);
      waits.push(state.retryInMs);
      await vi.advanceTimersByTimeAsync(state.retryInMs);
    }
    expect(waits).toEqual([500, 1_000, 2_000, 4_000, 8_000, 15_000, 15_000, 15_000]);
    expect(parts.sockets.all).toHaveLength(0);
  });

  it('TC-12-FL-04: a hidden page closes the socket with 1000, and a visible page reconnects and gets a snapshot', async () => {
    const parts = setup();
    parts.client.subscribe(AREA);
    const first = await connected(parts);
    first.serverSends(goldenSnapshot);

    parts.visibility.set('hidden');
    expect(first.closedWith).toBe(CloseCode.NORMAL);
    expect(parts.client.connection).toEqual({ status: 'paused' });
    // The close the browser reports later is not a failure: nothing waits to reconnect.
    first.serverCloses(CloseCode.NORMAL);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(parts.getTicket).toHaveBeenCalledTimes(1);

    parts.visibility.set('visible');
    expect(parts.client.connection).toEqual({ status: 'connecting' });
    await settle();
    const second = parts.sockets.last();
    expect(second.url).toContain('ticket=ticket-2');
    second.serverOpens();
    expect(second.subscriptions()).toEqual([goldenSubscribe]);
    second.serverSends(goldenSnapshot);
    expect(parts.messages).toEqual([goldenSnapshot, goldenSnapshot]);
  });

  it('TC-12-FL-04: hiding the page stops a pending reconnect and a ticket request', async () => {
    let resolveTicket: (ticket: string) => void = () => undefined;
    let ticketSignal: AbortSignal | undefined;
    const parts = setup({
      tickets: (n, signal) => {
        if (n === 1) return Promise.resolve('ticket-1');
        ticketSignal = signal;
        return new Promise((resolve) => {
          resolveTicket = resolve;
        });
      },
    });
    const first = await connected(parts);
    first.serverCloses(CloseCode.ABNORMAL);
    parts.visibility.set('hidden');
    await vi.advanceTimersByTimeAsync(60_000);
    expect(parts.getTicket).toHaveBeenCalledTimes(1);

    parts.visibility.set('visible');
    expect(parts.getTicket).toHaveBeenCalledTimes(2);
    parts.visibility.set('hidden');
    expect(ticketSignal?.aborted).toBe(true);
    resolveTicket('ticket-late');
    await settle();
    expect(parts.sockets.all).toHaveLength(1);
    expect(parts.client.connection).toEqual({ status: 'paused' });
  });

  it('starts paused in a hidden tab', async () => {
    const parts = setup();
    parts.visibility.visibilityState = 'hidden';
    parts.client.start();
    await settle();
    expect(parts.client.connection).toEqual({ status: 'paused' });
    expect(parts.getTicket).not.toHaveBeenCalled();
    parts.visibility.set('visible');
    await settle();
    expect(parts.sockets.all).toHaveLength(1);
  });

  it('TC-12-FL-05: a message that fails its schema is logged and dropped, and the connection stays', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const parts = setup();
    const socket = await connected(parts);

    socket.serverSends({ ...goldenSnapshot, drivers: [{ driverId: 'x', lat: 'north' }] });
    socket.serverSends('{ not json');
    socket.serverSends({ type: 'fleet_teleport' });
    socket.serverSendsBinary();
    expect(parts.messages).toEqual([]);
    expect(warn).toHaveBeenCalledTimes(4);
    expect(warn.mock.calls[0]?.[0]).toContain('fleet_snapshot');

    socket.serverSends(goldenSnapshot);
    expect(parts.messages).toEqual([goldenSnapshot]);
    expect(socket.closedWith).toBeUndefined();
    expect(parts.client.connection).toEqual({ status: 'live' });
  });

  it('a 401 on the ticket stops the client, because the app opens the login page', async () => {
    const parts = setup({
      tickets: () => Promise.reject(new ApiError({ status: 401, code: 'UNAUTHORIZED', title: 'Unauthorized' })),
    });
    parts.client.start();
    await settle();
    expect(parts.client.connection).toEqual({ status: 'stopped' });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(parts.getTicket).toHaveBeenCalledTimes(1);
  });

  it('a 403 on the ticket stops the client with no permission', async () => {
    const parts = setup({
      tickets: () => Promise.reject(new ApiError({ status: 403, code: 'FLEET_FORBIDDEN', title: 'Forbidden' })),
    });
    parts.client.start();
    await settle();
    expect(parts.client.connection).toEqual({ status: 'forbidden' });
  });

  it('a 429 on the ticket waits at least Retry-After', async () => {
    const parts = setup({
      tickets: (n) =>
        n === 1
          ? Promise.reject(new ApiError({ status: 429, code: 'RATE_LIMITED', title: 'Too many', retryAfter: 7 }))
          : Promise.resolve('ticket-2'),
    });
    parts.client.start();
    await settle();
    expect(parts.client.connection).toEqual({ status: 'waiting', retryInMs: 7_000 });
    await vi.advanceTimersByTimeAsync(7_000);
    expect(parts.sockets.last().url).toContain('ticket=ticket-2');
  });

  it('a socket that cannot be made counts as a failure', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const parts = setup();
    const client = new FleetClient({
      getTicket: parts.getTicket,
      openSocket: () => {
        throw new SyntaxError('bad url');
      },
      onMessage: () => undefined,
      onState: () => undefined,
      visibility: parts.visibility,
      random: () => 0.5,
    });
    client.start();
    await settle();
    expect(client.connection).toEqual({ status: 'waiting', retryInMs: FIRST_WAIT });
    expect(warn).toHaveBeenCalledOnce();
    client.stop();
  });

  it('stop closes the socket with 1000, and later events change nothing', async () => {
    const parts = setup();
    const socket = await connected(parts);
    parts.client.stop();
    expect(socket.closedWith).toBe(CloseCode.NORMAL);
    expect(parts.client.connection).toEqual({ status: 'stopped' });

    socket.serverCloses(CloseCode.ABNORMAL);
    parts.visibility.set('hidden');
    parts.visibility.set('visible');
    await vi.advanceTimersByTimeAsync(60_000);
    expect(parts.getTicket).toHaveBeenCalledTimes(1);
    expect(parts.client.connection).toEqual({ status: 'stopped' });

    // start() begins again, as React's StrictMode does after its test unmount.
    parts.client.start();
    await settle();
    expect(parts.sockets.all).toHaveLength(2);
  });

  it('start twice opens one socket', async () => {
    const parts = setup();
    parts.client.start();
    parts.client.start();
    await settle();
    expect(parts.sockets.all).toHaveLength(1);
  });
});
