import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Trip } from '../../api/types';
import type { TripMapData } from '../../map/tripLayers';
import { driverDetail, fleetDriver, track, trip, TRIP_ID, tripSummary } from '../../test/fixtures';
import { renderApp } from '../../test/render';
import { http, problem, recordRequests, server, testSession, tripSocket } from '../../test/server';
import { goldenCancelled, goldenPosition, goldenProgress, goldenSubscribed, statusChanged } from '../../test/trip';

// The page with the real trip client over MSW's WebSocket mock, and a stub in place of MapLibre, which needs WebGL.
// The stub shows what the page asks the map to draw.

const drawn: TripMapData[] = [];

vi.mock('../../map/TripMap', () => ({
  TripMap: ({ data }: { data: TripMapData }) => {
    drawn.push(data);
    const driver = data.driver;
    return (
      <p data-testid="trip-map">
        Route {data.route?.length ?? 0} points, track {data.track?.length ?? 0}, approach {data.approach?.length ?? 0},
        driver {driver === undefined ? 'none' : `${String(driver.lat)} ${driver.lastKnown ? 'last known' : 'live'}`}
      </p>
    );
  },
}));

/** The trip that GET /api/v1/trips/{id} answers. A test changes it to play a stage change on the server. */
let current: Trip;

function serveTrip(value: Trip) {
  current = value;
  server.use(http.get('/api/v1/trips/{id}', ({ response }) => response(200).json(current)));
}

interface ServerSide {
  send: (data: string) => void;
  close: (code?: number, reason?: string) => void;
}

/** The server's side of the trip socket: what the page sent, and a way to send and close. */
function tripServer() {
  const received: unknown[] = [];
  const connections: ServerSide[] = [];
  server.use(
    tripSocket.addEventListener('connection', ({ client }) => {
      connections.push(client);
      client.addEventListener('message', (event) => {
        if (typeof event.data === 'string') received.push(JSON.parse(event.data));
      });
    }),
  );
  const last = () => {
    const connection = connections.at(-1);
    if (connection === undefined) throw new Error('The page opened no trip socket');
    return connection;
  };
  return {
    received,
    connections,
    send: (message: unknown) => {
      last().send(JSON.stringify(message));
    },
    close: (code: number) => {
      last().close(code);
    },
  };
}

function liveStatus() {
  return screen.getByRole('region', { name: 'Live status' });
}

function mapText() {
  return screen.getByTestId('trip-map').textContent;
}

beforeEach(() => {
  drawn.length = 0;
  vi.spyOn(console, 'warn');
});

afterEach(() => {
  expect(console.warn).not.toHaveBeenCalled();
});

describe('live mode', () => {
  it('subscribes to the trip, seeds the driver as last known, then draws live positions and a trail', async () => {
    serveTrip(trip({ status: 'ACCEPTED' }));
    server.use(
      http.get('/api/v1/admin/drivers/{driverId}', ({ params, response }) =>
        response(200).json(
          driverDetail({
            driver: fleetDriver(1, {
              driverId: params.driverId,
              position: { lat: 10.7, lng: 106.6, at: '2026-11-20T08:29:00Z' },
            }),
          }),
        ),
      ),
    );
    const socket = tripServer();
    renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });

    expect(await screen.findByRole('heading', { name: 'Live' })).toBeInTheDocument();
    await waitFor(() => {
      expect(within(liveStatus()).getByText('Live')).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(socket.received).toEqual([{ type: 'subscribe', tripId: TRIP_ID }]);
    });
    // C3: before the first driver_position, the position from the driver's detail.
    await waitFor(() => {
      expect(mapText()).toContain('driver 10.7 last known');
    });
    expect(screen.getByText(/Last known at .*, before this page opened/)).toBeInTheDocument();

    socket.send(goldenSubscribed);
    socket.send(goldenPosition);
    socket.send({ ...goldenPosition, lat: 10.778, recordedAt: '2026-09-30T02:15:06Z' });
    socket.send(goldenProgress);
    await waitFor(() => {
      expect(mapText()).toContain('driver 10.778 live');
    });
    // C4: the trail holds the two positions received since the page opened. The route is the trip's.
    expect(mapText()).toContain('track 2');
    expect(mapText()).toContain('Route 2 points');
    expect(screen.getByText(/1.2 km to the pickup in a straight line, about 1 min 52 s/)).toBeInTheDocument();
    expect(screen.getByText('40.3 km/h')).toBeInTheDocument();
  });

  it('follows a stage change, and a final trip_event with close 1000 switches to replay mode', async () => {
    serveTrip(trip({ status: 'ACCEPTED' }));
    const seen = recordRequests();
    const socket = tripServer();
    renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });
    await screen.findByRole('heading', { name: 'Live' });
    await waitFor(() => {
      expect(socket.connections).toHaveLength(1);
    });
    socket.send(goldenSubscribed);

    // A stage change reads the trip again, so the header follows the socket.
    current = trip({ status: 'IN_PROGRESS' });
    socket.send(statusChanged('IN_PROGRESS'));
    await waitFor(() => {
      expect(screen.getAllByText('In progress')).toHaveLength(2);
    });
    expect(screen.getByRole('region', { name: 'Events' })).toHaveTextContent('Stage: In progress');

    current = trip({ status: 'CANCELLED' });
    socket.send(goldenCancelled);
    // A browser delivers the last frame before the close that follows it. MSW's mock can dispatch the close first, so
    // the server closes only once the page has the final message, as the real order guarantees.
    expect(await screen.findByRole('heading', { name: 'Replay' })).toBeInTheDocument();
    socket.close(1000);
    expect(screen.queryByRole('region', { name: 'Live status' })).not.toBeInTheDocument();
    // The replay reads both legs, and no summary for a cancelled trip.
    await waitFor(() => {
      expect(seen).toContain(`GET /api/v1/trips/${TRIP_ID}/track?leg=ride`);
    });
    expect(seen).toContain(`GET /api/v1/trips/${TRIP_ID}/track?leg=approach`);
    expect(seen.some((request) => request.includes('/summary'))).toBe(false);
    expect(screen.getByText('A cancelled trip has no summary. Only a completed trip gets one.')).toBeInTheDocument();
    // The client stopped: no second socket after the server's close.
    expect(socket.connections).toHaveLength(1);
  });

  it('shows no permission after close 4403', async () => {
    serveTrip(trip({ status: 'IN_PROGRESS' }));
    const socket = tripServer();
    renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });
    await screen.findByRole('heading', { name: 'Live' });
    await waitFor(() => {
      expect(socket.connections).toHaveLength(1);
    });
    socket.close(4403);
    expect(
      await screen.findByText('Your role may not watch trips live. The live view is for operators and admins.'),
    ).toBeInTheDocument();
  });
});

describe('cancel', () => {
  it('TC-12-UI-03: cancel on a trip that has ended shows the 409 message and the trip as it is now', async () => {
    serveTrip(trip({ status: 'IN_PROGRESS' }));
    tripServer();
    server.use(
      http.post('/api/v1/admin/trips/{id}/cancel', ({ response }) => {
        // The trip completed between the page load and the click.
        current = trip({ status: 'COMPLETED' });
        return response(409).json(problem(409, 'ILLEGAL_TRANSITION', 'Trip is COMPLETED'));
      }),
    );
    const { user } = renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });
    await user.click(await screen.findByRole('button', { name: 'Cancel trip' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this trip' });
    await user.type(within(dialog).getByLabelText(/Reason/), 'Guest called');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel trip' }));

    expect(await within(dialog).findByText('The trip has already ended')).toBeInTheDocument();
    // The page behind the dialog shows the real stage, and the dialog stays open with its message.
    expect(await screen.findByRole('heading', { name: 'Replay' })).toBeInTheDocument();
    expect(within(dialog).getByText('The trip has already ended')).toBeInTheDocument();
    // Closed, the dialog leaves a page without the button.
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Cancel trip' })).not.toBeInTheDocument();
    });
  });

  it('needs a reason, sends it once, and shows the trip cancelled', async () => {
    serveTrip(trip({ status: 'ACCEPTED' }));
    tripServer();
    const bodies: unknown[] = [];
    server.use(
      http.post('/api/v1/admin/trips/{id}/cancel', async ({ request, response }) => {
        bodies.push(await request.json());
        current = trip({ status: 'CANCELLED' });
        // POST answers without the route: only GET sends one.
        return response(200).json({ ...current, route: undefined });
      }),
    );
    const { user } = renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });
    await user.click(await screen.findByRole('button', { name: 'Cancel trip' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this trip' });
    await user.click(within(dialog).getByRole('button', { name: 'Cancel trip' }));
    expect(within(dialog).getByText('Give a reason.')).toBeInTheDocument();
    expect(bodies).toEqual([]);

    await user.type(within(dialog).getByLabelText(/Reason/), '  Guest called  ');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel trip' }));
    expect(await screen.findByRole('heading', { name: 'Replay' })).toBeInTheDocument();
    expect(bodies).toEqual([{ reason: 'Guest called' }]);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // Read again after the answer, which has no route: the planned route is back on the map.
    await waitFor(() => {
      expect(mapText()).toContain('Route 2 points');
    });
  });

  it('shows the server text for 422 REASON_REQUIRED', async () => {
    serveTrip(trip({ status: 'ACCEPTED' }));
    tripServer();
    server.use(
      http.post('/api/v1/admin/trips/{id}/cancel', ({ response }) =>
        response(422).json(problem(422, 'REASON_REQUIRED')),
      ),
    );
    const { user } = renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('ADMIN') });
    await user.click(await screen.findByRole('button', { name: 'Cancel trip' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this trip' });
    await user.type(within(dialog).getByLabelText(/Reason/), 'x');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel trip' }));
    expect(await within(dialog).findByText('Reason needed')).toBeInTheDocument();
  });
});

describe('replay mode', () => {
  it('draws both legs and the planned route, and lists the timeline and the summary', async () => {
    renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });
    expect(await screen.findByRole('heading', { name: 'Replay' })).toBeInTheDocument();
    await waitFor(() => {
      expect(mapText()).toContain('track 3, approach 3');
    });
    expect(mapText()).toContain('Route 2 points');
    const timeline = screen.getByRole('table', { name: 'Timeline' });
    expect(within(timeline).getByText('Route to the pickup version 1, first plan, 1.2 km')).toBeInTheDocument();
    expect(within(timeline).getByText('Driver arriving, 180 m away')).toBeInTheDocument();
    const summary = screen.getByRole('region', { name: 'Summary' });
    expect(await within(summary).findByText('2.4 km')).toBeInTheDocument();
    expect(within(summary).getByText('POOR_ACCURACY 4')).toBeInTheDocument();
    // The recompute is ADMIN only.
    expect(within(summary).queryByRole('button', { name: 'Compute again' })).not.toBeInTheDocument();
    // No socket for an ended trip.
    expect(screen.queryByRole('region', { name: 'Live status' })).not.toBeInTheDocument();
  });

  it('SUMMARY_NOT_READY: "Check again" for everyone, "Compute now" for an admin', async () => {
    server.use(
      http.get('/api/v1/trips/{id}/summary', ({ response }) => response(404).json(problem(404, 'SUMMARY_NOT_READY'))),
      http.post('/api/v1/admin/trips/{id}/summary', ({ response }) =>
        response(200).json(tripSummary({ offRouteCount: 7 })),
      ),
    );
    const { user } = renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('ADMIN') });
    const summary = await screen.findByRole('region', { name: 'Summary' });
    expect(await within(summary).findByText(/Not computed yet/)).toBeInTheDocument();
    expect(within(summary).getByRole('button', { name: 'Check again' })).toBeInTheDocument();
    await user.click(within(summary).getByRole('button', { name: 'Compute now' }));
    expect(await within(summary).findByText('7')).toBeInTheDocument();
  });

  it('an operator gets no "Compute now"', async () => {
    server.use(
      http.get('/api/v1/trips/{id}/summary', ({ response }) => response(404).json(problem(404, 'SUMMARY_NOT_READY'))),
    );
    renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });
    const summary = await screen.findByRole('region', { name: 'Summary' });
    await within(summary).findByText(/Not computed yet/);
    expect(within(summary).queryByRole('button', { name: 'Compute now' })).not.toBeInTheDocument();
  });

  it('shows pointsExpired, and an export answered 410 TRACK_EXPIRED says why', async () => {
    server.use(
      http.get('/api/v1/trips/{id}/track', ({ query, response }) =>
        response(200).json(
          track(query.get('leg') === 'approach' ? 'approach' : 'ride', { points: [], pointsExpired: true }),
        ),
      ),
      http.get('/api/v1/trips/{id}/export.gpx', ({ response }) => response(410).json(problem(410, 'TRACK_EXPIRED'))),
    );
    const { user } = renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });
    expect(await screen.findByText('GPS points deleted')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Ride: GPX' }));
    expect(await screen.findByText(/^GPS points deleted\. The trip ended more than 90 days ago/)).toBeInTheDocument();
  });

  it('exports a leg as a file through the token-carrying client', async () => {
    let authorization: string | null = null;
    server.use(
      http.get('/api/v1/trips/{id}/export.geojson', ({ request, query }) => {
        authorization = request.headers.get('Authorization');
        expect(query.get('leg')).toBe('approach');
        return new Response('{"type":"FeatureCollection","features":[]}', {
          headers: {
            'Content-Type': 'application/geo+json',
            'Content-Disposition': `attachment; filename=trip-${TRIP_ID}.geojson`,
          },
        });
      }),
    );
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const { user } = renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR', { token: 'token-x' }) });
    await user.click(await screen.findByRole('button', { name: 'Drive to the pickup: GeoJSON' }));
    await waitFor(() => {
      expect(click).toHaveBeenCalledOnce();
    });
    expect(authorization).toBe('Bearer token-x');
    expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe(`trip-${TRIP_ID}.geojson`);
  });
});

describe('the trip', () => {
  it('links the driver and the guest, and shows a missing trip', async () => {
    renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });
    expect(await screen.findByRole('link', { name: '7f1c2a9e' })).toHaveAttribute(
      'href',
      expect.stringContaining('/drivers/'),
    );
    expect(screen.getByRole('link', { name: '5d1e9a2c' })).toHaveAttribute(
      'href',
      expect.stringContaining('/trips?guestId='),
    );
  });

  it('shows TRIP_NOT_FOUND in words', async () => {
    server.use(http.get('/api/v1/trips/{id}', ({ response }) => response(404).json(problem(404, 'TRIP_NOT_FOUND'))));
    renderApp({ path: `/trips/${TRIP_ID}`, session: testSession('OPERATOR') });
    expect(await screen.findByText('Trip not found')).toBeInTheDocument();
  });
});
