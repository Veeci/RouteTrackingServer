import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CloseCode } from '../socket/connection';
import { FakeVisibility, fakeSockets } from '../test/fleet';
import {
  GOLDEN_TRIP_ID,
  goldenCancelled,
  goldenPosition,
  goldenStatusChanged,
  goldenSubscribed,
  goldenTripSubscribe,
  statusChanged,
  subscribedFor,
} from '../test/trip';
import { TripClient, type TripConnectionState } from './client';
import type { TripServerMessage } from './messages';

/** With random 0.5 the waits are half the ceiling: 500 ms, 1 000 ms, 2 000 ms, … */
const FIRST_WAIT = 500;

function setup(tripId = GOLDEN_TRIP_ID) {
  const sockets = fakeSockets();
  const visibility = new FakeVisibility();
  const messages: TripServerMessage[] = [];
  const states: TripConnectionState[] = [];
  let issued = 0;
  const getTicket = vi.fn(() => {
    issued += 1;
    return Promise.resolve(`ticket-${String(issued)}`);
  });
  const client = new TripClient({
    tripId,
    getTicket,
    openSocket: sockets.open,
    onMessage: (message) => messages.push(message),
    onState: (state) => states.push(state),
    visibility,
    location: { href: 'https://admin.example.ts.net/trips/x' },
    random: () => 0.5,
  });
  return { client, sockets, visibility, messages, states, getTicket };
}

async function connected(parts: ReturnType<typeof setup>) {
  parts.client.start();
  await vi.advanceTimersByTimeAsync(0);
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

describe('TripClient', () => {
  it('opens the trip socket with a ticket and subscribes to its trip once the socket is open', async () => {
    const parts = setup();
    const socket = await connected(parts);
    expect(socket.url).toBe('wss://admin.example.ts.net/ws/v1/admin/trip?ticket=ticket-1');
    // The same frame as the protocol's golden sample.
    expect(socket.sent.map((text) => JSON.parse(text) as unknown)).toEqual([goldenTripSubscribe]);

    socket.serverSends(goldenSubscribed);
    socket.serverSends(goldenPosition);
    socket.serverSends(goldenStatusChanged);
    expect(parts.messages).toEqual([goldenSubscribed, goldenPosition, goldenStatusChanged]);
    expect(parts.client.connection).toEqual({ status: 'live' });
  });

  it('after a drop it waits, fetches a new ticket and subscribes to the same trip again', async () => {
    const parts = setup();
    const first = await connected(parts);
    first.serverSends(goldenSubscribed);
    first.serverCloses(CloseCode.ABNORMAL);
    expect(parts.client.connection).toEqual({ status: 'waiting', retryInMs: FIRST_WAIT });

    await vi.advanceTimersByTimeAsync(FIRST_WAIT);
    const second = parts.sockets.last();
    expect(second).not.toBe(first);
    expect(second.url).toContain('ticket=ticket-2');
    second.serverOpens();
    expect(second.sent).toEqual([JSON.stringify({ type: 'subscribe', tripId: GOLDEN_TRIP_ID })]);
  });

  it('subscribed resets the backoff, so the next drop waits the shortest time again', async () => {
    const parts = setup();
    const first = await connected(parts);
    first.serverCloses(CloseCode.ABNORMAL);
    await vi.advanceTimersByTimeAsync(FIRST_WAIT);
    const second = parts.sockets.last();
    second.serverOpens();
    // Without a subscribed the second wait doubles.
    second.serverCloses(CloseCode.ABNORMAL);
    expect(parts.client.connection).toEqual({ status: 'waiting', retryInMs: 2 * FIRST_WAIT });
    await vi.advanceTimersByTimeAsync(2 * FIRST_WAIT);
    const third = parts.sockets.last();
    third.serverOpens();
    third.serverSends(goldenSubscribed);
    third.serverCloses(CloseCode.ABNORMAL);
    expect(parts.client.connection).toEqual({ status: 'waiting', retryInMs: FIRST_WAIT });
  });

  it('a final trip_event ends the stream: the client delivers it, closes, and ignores the close 1000 after it', async () => {
    const parts = setup();
    const socket = await connected(parts);
    socket.serverSends(goldenSubscribed);
    socket.serverSends(goldenCancelled);
    expect(parts.messages.at(-1)).toEqual(goldenCancelled);
    expect(parts.client.connection).toEqual({ status: 'ended' });
    expect(parts.states.at(-1)).toEqual({ status: 'ended' });
    expect(socket.closedWith).toBe(CloseCode.NORMAL);

    // The server's own close arrives after the client let go of the socket: nothing reconnects.
    socket.serverCloses(CloseCode.NORMAL);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(parts.getTicket).toHaveBeenCalledTimes(1);
    expect(parts.client.connection).toEqual({ status: 'ended' });

    // Visible again, or started again by a re-render: still ended.
    parts.visibility.set('hidden');
    parts.visibility.set('visible');
    parts.client.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(parts.getTicket).toHaveBeenCalledTimes(1);
  });

  it('a COMPLETED status_changed ends it too, and a non-final one does not', async () => {
    const parts = setup();
    const socket = await connected(parts);
    socket.serverSends(statusChanged('IN_PROGRESS'));
    expect(parts.client.connection).toEqual({ status: 'live' });
    socket.serverSends(statusChanged('COMPLETED'));
    expect(parts.client.connection).toEqual({ status: 'ended' });
  });

  it('a moment in a final stage is not the end: only status_changed is', async () => {
    const parts = setup();
    const socket = await connected(parts);
    socket.serverSends(statusChanged('COMPLETED', { event: 'driver_back' }));
    expect(parts.client.connection).toEqual({ status: 'live' });
  });

  it('subscribed for a trip that has already ended ends the stream at once', async () => {
    const parts = setup();
    const socket = await connected(parts);
    socket.serverSends(subscribedFor(GOLDEN_TRIP_ID, { status: 'COMPLETED' }));
    expect(parts.messages).toHaveLength(1);
    expect(parts.client.connection).toEqual({ status: 'ended' });
    expect(socket.closedWith).toBe(CloseCode.NORMAL);
  });

  it('a close 1000 without a final message is a drop, and the client reconnects', async () => {
    const parts = setup();
    const socket = await connected(parts);
    socket.serverSends(goldenSubscribed);
    socket.serverCloses(CloseCode.NORMAL);
    expect(parts.client.connection).toEqual({ status: 'waiting', retryInMs: FIRST_WAIT });
    await vi.advanceTimersByTimeAsync(FIRST_WAIT);
    expect(parts.getTicket).toHaveBeenCalledTimes(2);
  });

  it('close 4403 stops with no permission', async () => {
    const parts = setup();
    const socket = await connected(parts);
    socket.serverCloses(CloseCode.FORBIDDEN_ROLE);
    expect(parts.client.connection).toEqual({ status: 'forbidden' });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(parts.getTicket).toHaveBeenCalledTimes(1);
  });

  it('a message that fails its schema is logged and dropped, and the connection stays', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const parts = setup();
    const socket = await connected(parts);
    socket.serverSends({ ...goldenPosition, lat: 'north' });
    socket.serverSends('{ not json');
    socket.serverSends({ type: 'trip_teleport' });
    socket.serverSendsBinary();
    expect(parts.messages).toEqual([]);
    expect(warn).toHaveBeenCalledTimes(4);
    expect(warn.mock.calls[0]?.[0]).toContain('driver_position');
    expect(warn.mock.calls[3]?.[0]).toBe('Trip socket: dropped a frame that is not text');

    socket.serverSends(goldenPosition);
    expect(parts.messages).toEqual([goldenPosition]);
    expect(socket.closedWith).toBeUndefined();
  });

  it('takes event and reason values the contract may add later', async () => {
    const parts = setup();
    const socket = await connected(parts);
    socket.serverSends({ ...goldenStatusChanged, event: 'driver_waved' });
    expect(parts.messages).toHaveLength(1);
  });

  it('a hidden tab closes the socket, and a visible one subscribes again on a new socket', async () => {
    const parts = setup();
    const first = await connected(parts);
    parts.visibility.set('hidden');
    expect(first.closedWith).toBe(CloseCode.NORMAL);
    expect(parts.client.connection).toEqual({ status: 'paused' });
    parts.visibility.set('visible');
    await vi.advanceTimersByTimeAsync(0);
    const second = parts.sockets.last();
    second.serverOpens();
    expect(second.sent).toHaveLength(1);
  });

  it('stop reports stopped, not ended', async () => {
    const parts = setup();
    await connected(parts);
    parts.client.stop();
    expect(parts.client.connection).toEqual({ status: 'stopped' });
  });
});
