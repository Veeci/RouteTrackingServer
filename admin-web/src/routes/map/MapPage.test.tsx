import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FleetSubscribe } from '../../fleet/messages';
import type { FleetMapProps } from '../../map/FleetMap';
import { goldenCluster, goldenDelta, goldenSnapshot, ON_TRIP_ID } from '../../test/fleet';
import { renderApp } from '../../test/render';
import { fleetSocket, http, problem, recordRequests, server, testSession } from '../../test/server';
import { formatPosition } from './DriverPanel';

// The page with the real fleet client, over MSW's WebSocket mock, and a stub in place of MapLibre, which needs WebGL.
// The stub reports its first view on mount, as the real map does, and has buttons for a move and a marker click.

const MAP_BBOX = [105.78, 20.99, 105.88, 21.06];
const MOVED_BBOX = [105.6, 20.8, 105.7, 20.9];

vi.mock('../../map/FleetMap', async () => {
  const { useEffect } = await import('react');
  const fleet = await import('../../test/fleet');
  return {
    FleetMap: ({ view, selectedDriverId, onMove, onSelectDriver }: FleetMapProps) => {
      useEffect(() => {
        onMove({ view, bbox: [105.78, 20.99, 105.88, 21.06] });
        // The real map reports its first view once, when it starts.
        // eslint-disable-next-line react-hooks/exhaustive-deps
      }, []);
      return (
        <div>
          <p>
            Map at {view.lat}, {view.lng}, zoom {view.zoom}, selected {selectedDriverId ?? 'none'}
          </p>
          <button
            type="button"
            onClick={() => {
              onMove({ view: { lat: 20.85, lng: 105.65, zoom: 13 }, bbox: [105.6, 20.8, 105.7, 20.9] });
            }}
          >
            Move the map
          </button>
          <button
            type="button"
            onClick={() => {
              onSelectDriver(fleet.ON_TRIP_ID);
            }}
          >
            Click a marker
          </button>
        </div>
      );
    },
  };
});

interface ServerSide {
  send: (data: string) => void;
  close: (code?: number, reason?: string) => void;
}

/** The server's side of the fleet socket: what the page subscribed to, and a way to send and close. */
function fleetServer() {
  const subscriptions: FleetSubscribe[] = [];
  const connections: ServerSide[] = [];
  server.use(
    fleetSocket.addEventListener('connection', ({ client }) => {
      connections.push(client);
      client.addEventListener('message', (event) => {
        if (typeof event.data === 'string') subscriptions.push(JSON.parse(event.data) as FleetSubscribe);
      });
    }),
  );
  const current = () => {
    const connection = connections.at(-1);
    if (connection === undefined) throw new Error('The page opened no fleet socket');
    return connection;
  };
  return {
    subscriptions,
    connections,
    send: (message: unknown) => {
      current().send(JSON.stringify(message));
    },
    close: (code: number) => {
      current().close(code);
    },
  };
}

function statusLine() {
  return screen.getByRole('region', { name: 'Map status' });
}

async function live() {
  await waitFor(() => {
    expect(within(statusLine()).getByText('Live')).toBeInTheDocument();
  });
}

beforeEach(() => {
  vi.spyOn(console, 'warn');
});

afterEach(() => {
  // A dropped message logs a warning. None of these tests sends one.
  expect(console.warn).not.toHaveBeenCalled();
});

describe('MapPage', () => {
  it('subscribes with the map area and the URL filters, and counts the drivers in view', async () => {
    const fleet = fleetServer();
    renderApp({ path: '/map?online=true&onTrip=false', session: testSession('OPERATOR') });

    expect(await screen.findByRole('heading', { name: 'Live map' })).toBeInTheDocument();
    await live();
    await waitFor(() => {
      expect(fleet.subscriptions).toEqual([
        { type: 'fleet_subscribe', bbox: MAP_BBOX, filters: { online: true, onTrip: false } },
      ]);
    });
    expect(within(statusLine()).getByText('0 drivers in view')).toBeInTheDocument();
    expect(within(statusLine()).getByText(/none yet/)).toBeInTheDocument();

    fleet.send(goldenSnapshot);
    expect(await within(statusLine()).findByText('2 drivers in view')).toBeInTheDocument();
    expect(within(statusLine()).getByText(new Date(goldenSnapshot.at).toLocaleTimeString())).toBeInTheDocument();
    // The delta moves one driver and removes the other.
    fleet.send(goldenDelta);
    expect(await within(statusLine()).findByText('1 driver in view')).toBeInTheDocument();
  });

  it('writes the first view into the URL without a new history entry', async () => {
    fleetServer();
    const { router } = renderApp({ path: '/map', session: testSession('OPERATOR') });
    await live();
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ lat: 21.0285, lng: 105.8542, zoom: 11 });
    });
    expect(router.history.length).toBe(1);
  });

  it('shows the clusters total while the area is crowded, and the markers again after a snapshot', async () => {
    const fleet = fleetServer();
    renderApp({ path: '/map', session: testSession('OPERATOR') });
    await live();
    fleet.send(goldenCluster);
    expect(await within(statusLine()).findByText('5,324 drivers in view, grouped by area')).toBeInTheDocument();
    fleet.send(goldenSnapshot);
    expect(await within(statusLine()).findByText('2 drivers in view')).toBeInTheDocument();
  });

  it('a filter change subscribes again with the same area and adds a history entry', async () => {
    const fleet = fleetServer();
    const { router, user } = renderApp({ path: '/map', session: testSession('OPERATOR') });
    await live();
    await waitFor(() => {
      expect(fleet.subscriptions).toHaveLength(1);
    });
    const before = router.history.length;

    await user.selectOptions(screen.getByLabelText('Trip'), 'On a trip');
    await waitFor(() => {
      expect(fleet.subscriptions.at(-1)).toEqual({
        type: 'fleet_subscribe',
        bbox: MAP_BBOX,
        filters: { onTrip: true },
      });
    });
    expect(router.state.location.search).toMatchObject({ onTrip: true });
    expect(router.history.length).toBe(before + 1);

    await user.selectOptions(screen.getByLabelText('Online'), 'Offline');
    await waitFor(() => {
      expect(fleet.subscriptions.at(-1)?.filters).toEqual({ online: false, onTrip: true });
    });
  });

  it('a map move replaces the view in the URL and subscribes with the new area', async () => {
    const fleet = fleetServer();
    const { router, user } = renderApp({ path: '/map?online=true', session: testSession('OPERATOR') });
    await live();
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ zoom: 11 });
    });
    const before = router.history.length;

    await user.click(screen.getByRole('button', { name: 'Move the map' }));
    await waitFor(() => {
      expect(fleet.subscriptions.at(-1)).toEqual({
        type: 'fleet_subscribe',
        bbox: MOVED_BBOX,
        filters: { online: true },
      });
    });
    expect(router.state.location.search).toEqual({ online: true, lat: 20.85, lng: 105.65, zoom: 13 });
    expect(router.history.length).toBe(before);
  });

  it('a shared link opens the same view', async () => {
    fleetServer();
    renderApp({ path: '/map?lat=10.77&lng=106.7&zoom=13.5', session: testSession('OPERATOR') });
    expect(await screen.findByText(/Map at 10.77, 106.7, zoom 13.5/)).toBeInTheDocument();
  });

  it('a driver in the URL opens the panel: the name from the detail, the position from the socket', async () => {
    const fleet = fleetServer();
    renderApp({ path: `/map?driver=${ON_TRIP_ID}`, session: testSession('OPERATOR') });

    const panel = await screen.findByRole('complementary', { name: 'Selected driver' });
    expect(await within(panel).findByRole('heading', { name: 'Driver 1' })).toBeInTheDocument();
    // Before the snapshot the store has no marker: the panel says so and shows the last position the API knows.
    expect(within(panel).getByText('Not on the map in this area with these filters.')).toBeInTheDocument();
    expect(within(panel).getByText(/Last known position 21.03000, 105.85000/)).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: 'Open the driver page' })).toHaveAttribute(
      'href',
      `/drivers/${ON_TRIP_ID}`,
    );

    await live();
    fleet.send(goldenSnapshot);
    expect(await within(panel).findByText(formatPosition(21.0285, 105.8542))).toBeInTheDocument();
    expect(within(panel).getByText('IN_PROGRESS')).toBeInTheDocument();
    expect(within(panel).getByText('64 %')).toBeInTheDocument();
    fleet.send(goldenDelta);
    expect(await within(panel).findByText(formatPosition(21.0291, 105.855))).toBeInTheDocument();
    expect(within(panel).getByText('92.5 °')).toBeInTheDocument();
  });

  it('a marker click selects the driver, and closing the panel clears it', async () => {
    fleetServer();
    const { router, user } = renderApp({ path: '/map', session: testSession('OPERATOR') });
    await live();

    await user.click(screen.getByRole('button', { name: 'Click a marker' }));
    expect(await screen.findByRole('complementary', { name: 'Selected driver' })).toBeInTheDocument();
    expect(router.state.location.search).toMatchObject({ driver: ON_TRIP_ID });
    expect(screen.getByText(new RegExp(`selected ${ON_TRIP_ID}`))).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Close the driver panel' }));
    await waitFor(() => {
      expect(screen.queryByRole('complementary', { name: 'Selected driver' })).not.toBeInTheDocument();
    });
    expect(router.state.location.search).not.toHaveProperty('driver');
  });

  it('TC-12-FL-03: close 4403 shows No permission', async () => {
    const fleet = fleetServer();
    renderApp({ path: '/map', session: testSession('OPERATOR') });
    await live();
    fleet.close(4403);
    expect(await screen.findByRole('alert')).toHaveTextContent('Your role may not watch the fleet');
    expect(within(statusLine()).getByText('No permission')).toBeInTheDocument();
  });

  it('a 403 on the ticket shows No permission and opens no socket', async () => {
    const fleet = fleetServer();
    server.use(
      http.post('/api/v1/admin/ws-tickets', ({ response }) => response(403).json(problem(403, 'FLEET_FORBIDDEN'))),
    );
    renderApp({ path: '/map', session: testSession('OPERATOR') });
    expect(await screen.findByRole('alert')).toHaveTextContent('Your role may not watch the fleet');
    expect(fleet.connections).toHaveLength(0);
  });

  it('a 401 on the ticket opens the login page and comes back to the map', async () => {
    server.use(
      http.post('/api/v1/admin/ws-tickets', ({ response }) => response(401).json(problem(401, 'UNAUTHORIZED'))),
    );
    const requests = recordRequests();
    const { router } = renderApp({ path: '/map?online=true', session: testSession('OPERATOR') });
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
    });
    expect(router.state.location.search).toMatchObject({ redirect: expect.stringMatching(/^\/map\?/) as unknown });
    expect(requests.filter((request) => request.includes('ws-tickets'))).toHaveLength(1);
  });
});
