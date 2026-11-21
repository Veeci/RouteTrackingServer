import { screen, waitFor, within } from '@testing-library/react';
import { delay } from 'msw';
import { describe, expect, it } from 'vitest';
import type { AccountChangeRequest, DriverDetail, IssueCommand } from '../../api/types';
import {
  account,
  AT,
  command,
  DRIVER_ID,
  idOf,
  driverDetail,
  fleetDriver,
  fleetPage,
  SESSION_ID,
  sessionHealth,
} from '../../test/fixtures';
import { renderApp } from '../../test/render';
import { http, problem, recordRequests, server, testSession } from '../../test/server';

const DETAIL_PATH = `/drivers/${DRIVER_ID}`;

/**
 * A server that remembers the account, as the real one does: the detail's `disabledAt` and `disabledReason` come
 * from the account at once, while `driver.enabled` comes from the fleet read model and stays as it was until an event
 * reaches it.
 */
function accountServer(start: DriverDetail = driverDetail()) {
  let current = start;
  const posts: { action: 'disable' | 'enable'; body: AccountChangeRequest }[] = [];
  const change = (action: 'disable' | 'enable') =>
    http.post(`/api/v1/admin/drivers/{driverId}/${action}`, async ({ request, response }) => {
      const body = await request.json();
      posts.push({ action, body });
      const disabled = action === 'disable';
      current = {
        ...current,
        disabledAt: disabled ? AT : undefined,
        disabledReason: disabled ? body.reason : undefined,
      };
      return response(200).json(account(disabled, body.reason));
    });
  server.use(
    http.get('/api/v1/admin/drivers/{driverId}', ({ response }) => response(200).json(current)),
    change('disable'),
    change('enable'),
  );
  return posts;
}

function detailRequests(seen: string[]) {
  return seen.filter((request) => request === `GET /api/v1/admin/drivers/${DRIVER_ID}`);
}

function section(name: string) {
  return within(screen.getByRole('region', { name }));
}

describe('the driver detail', () => {
  it('shows the account, position, device, trip, latest health, sessions and trips', async () => {
    server.use(
      http.get('/api/v1/admin/drivers/{driverId}', ({ response }) =>
        response(200).json(
          driverDetail({
            health: {
              sessionId: SESSION_ID,
              startedAt: AT,
              computedAt: AT,
              fixCount: 1200,
              acceptedRatio: 0.5,
              gapCount: 2,
              primaryProvider: 'GMS_FUSED',
            },
          }),
        ),
      ),
    );
    renderApp({ path: DETAIL_PATH, session: testSession('OPERATOR') });

    expect(await screen.findByRole('heading', { name: 'Driver 1' })).toBeInTheDocument();
    expect(section('Account').getByText('Enabled')).toBeInTheDocument();
    expect(section('Position').getByText('21.03000, 105.85000')).toBeInTheDocument();
    expect(section('Device').getByText('80 %')).toBeInTheDocument();
    expect(section('Trip').getByText('IN_PROGRESS')).toBeInTheDocument();
    expect(section('Latest session health').getByText('1,200')).toBeInTheDocument();
    expect(section('Latest session health').getByText('GMS_FUSED')).toBeInTheDocument();
    expect(section('Recent sessions').getByText('Google Pixel 7, SDK 35')).toBeInTheDocument();
    expect(section('Recent trips').getByText('IN_PROGRESS')).toBeInTheDocument();
    // 12e: each trip, the current one and the driver's whole list link to the trip pages.
    const tripId = idOf(DRIVER_ID, 900);
    expect(section('Recent trips').getByRole('link', { name: tripId.slice(0, 8) })).toHaveAttribute(
      'href',
      `/trips/${tripId}`,
    );
    expect(section('Trip').getByRole('link', { name: tripId.slice(0, 8) })).toHaveAttribute('href', `/trips/${tripId}`);
    expect(section('Recent trips').getByRole('link', { name: 'All trips of this driver' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/trips?driverId=`),
    );
    expect(screen.getByText(/^As of/)).toHaveTextContent('The page asks again every 10 s.');
  });

  it('shows "Driver not found" for an unknown driver', async () => {
    server.use(
      http.get('/api/v1/admin/drivers/{driverId}', ({ response }) =>
        response(404).json(problem(404, 'DRIVER_NOT_FOUND')),
      ),
    );
    renderApp({ path: DETAIL_PATH, session: testSession('OPERATOR') });

    expect(await screen.findByText('Driver not found')).toBeInTheDocument();
  });
});

describe('TC-12-UI-02: disable and enable', () => {
  it('requires a reason before it sends anything', async () => {
    const posts = accountServer();
    const { user } = renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: 'Disable driver' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Disable driver' }));
    await user.type(dialog.getByLabelText(/Reason/), '   ');
    await user.click(dialog.getByRole('button', { name: 'Disable' }));

    expect(dialog.getByText('Give a reason.')).toBeInTheDocument();
    expect(posts).toEqual([]);
  });

  it('disables with the reason, shows the answer, and reads the driver again', async () => {
    const posts = accountServer();
    const seen = recordRequests();
    const { user, queryClient } = renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: 'Disable driver' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Disable driver' }));
    await user.type(dialog.getByLabelText(/Reason/), '  Phone stolen ');
    const before = detailRequests(seen).length;
    await user.click(dialog.getByRole('button', { name: 'Disable' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(posts).toEqual([{ action: 'disable', body: { reason: 'Phone stolen' } }]);
    // The driver is read again. Its read-model flag still says enabled, and the page must trust the account.
    await waitFor(() => {
      expect(detailRequests(seen).length).toBeGreaterThan(before);
      expect(queryClient.isFetching()).toBe(0);
    });
    expect(section('Account').getByText('Disabled')).toBeInTheDocument();
    expect(section('Account').getByText('Phone stolen')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Enable driver' })).toBeInTheDocument();
  });

  it('asks for a reason to enable too, where the server would take none (C4)', async () => {
    const posts = accountServer(
      driverDetail({ driver: fleetDriver(1, { enabled: false }), disabledAt: AT, disabledReason: 'Old' }),
    );
    const { user } = renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: 'Enable driver' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Enable driver' }));
    await user.click(dialog.getByRole('button', { name: 'Enable' }));
    expect(dialog.getByText('Give a reason.')).toBeInTheDocument();

    await user.type(dialog.getByLabelText(/Reason/), 'Phone found');
    await user.click(dialog.getByRole('button', { name: 'Enable' }));

    expect(await screen.findByRole('button', { name: 'Disable driver' })).toBeInTheDocument();
    expect(posts).toEqual([{ action: 'enable', body: { reason: 'Phone found' } }]);
  });

  it('sends one request however often the button is pressed while it runs', async () => {
    const posts: unknown[] = [];
    server.use(
      http.post('/api/v1/admin/drivers/{driverId}/disable', async ({ request, response }) => {
        posts.push(await request.json());
        await delay(100);
        return response(200).json(account(true, 'Twice'));
      }),
    );
    const { user } = renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: 'Disable driver' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Disable driver' }));
    await user.type(dialog.getByLabelText(/Reason/), 'Twice');
    const submit = dialog.getByRole('button', { name: 'Disable' });
    await user.click(submit);
    await user.click(submit);
    await user.click(submit);

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(posts).toHaveLength(1);
  });

  it('shows "No permission" when the server refuses, and keeps the dialog open', async () => {
    server.use(
      http.post('/api/v1/admin/drivers/{driverId}/disable', ({ response }) =>
        response(403).json(problem(403, 'ACCOUNT_ACTION_FORBIDDEN')),
      ),
    );
    const { user } = renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: 'Disable driver' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Disable driver' }));
    await user.type(dialog.getByLabelText(/Reason/), 'Test');
    await user.click(dialog.getByRole('button', { name: 'Disable' }));

    expect(await dialog.findByText('No permission')).toBeInTheDocument();
    expect(dialog.getByText('Only an admin may disable or enable a driver.')).toBeInTheDocument();
  });

  it('makes the driver list read again after a change', async () => {
    accountServer();
    const seen = recordRequests();
    server.use(http.get('/api/v1/admin/drivers', ({ response }) => response(200).json(fleetPage([fleetDriver(1)]))));
    const { user } = renderApp({ path: '/drivers', session: testSession('ADMIN') });

    await user.click(await screen.findByRole('link', { name: 'Driver 1' }));
    await user.click(await screen.findByRole('button', { name: 'Disable driver' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Disable driver' }));
    await user.type(dialog.getByLabelText(/Reason/), 'Test');
    await user.click(dialog.getByRole('button', { name: 'Disable' }));
    await screen.findByRole('button', { name: 'Enable driver' });
    await user.click(screen.getByRole('link', { name: 'All drivers' }));

    await waitFor(() => {
      expect(seen.filter((request) => request === 'GET /api/v1/admin/drivers')).toHaveLength(2);
    });
  });
});

describe('TC-12-AUTH-02: an operator on the driver detail', () => {
  it('sees no disable, enable, command or health button, and sends no ADMIN request', async () => {
    const seen = recordRequests();
    renderApp({ path: DETAIL_PATH, session: testSession('OPERATOR') });

    await screen.findByRole('heading', { name: 'Driver 1' });
    await screen.findByRole('region', { name: 'Recent sessions' });
    for (const name of ['Disable driver', 'Enable driver', 'Send a command', /^Health/]) {
      expect(screen.queryByRole('button', { name })).toBeNull();
    }
    expect(screen.queryByRole('region', { name: 'Commands' })).toBeNull();
    expect(seen).toEqual([`GET /api/v1/admin/drivers/${DRIVER_ID}`]);
  });

  it('sees no enable button on a disabled driver either', async () => {
    server.use(
      http.get('/api/v1/admin/drivers/{driverId}', ({ response }) =>
        response(200).json(driverDetail({ driver: fleetDriver(1, { enabled: false }), disabledAt: AT })),
      ),
    );
    renderApp({ path: DETAIL_PATH, session: testSession('OPERATOR') });

    expect(await screen.findByText('Disabled', { selector: '.mantine-Badge-label' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Enable driver' })).toBeNull();
  });
});

describe('session health', () => {
  it('says "not computed yet" for a 404 and computes it on "Compute now"', async () => {
    let computed = 0;
    server.use(
      http.post('/api/v1/admin/sessions/{id}/health', ({ response }) => {
        computed += 1;
        return response(200).json(sessionHealth);
      }),
    );
    const { user } = renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });

    const toggle = await screen.findByRole('button', { name: /^Health of the session/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    const panel = within(await screen.findByRole('region', { name: 'Session health' }));
    expect(await panel.findByText(/^Not computed yet/)).toBeInTheDocument();

    await user.click(panel.getByRole('button', { name: 'Compute now' }));

    expect(await panel.findByText('3 (ACCURACY 3)')).toBeInTheDocument();
    expect(panel.getByText('1, of which 1 likely Doze')).toBeInTheDocument();
    expect(panel.getByRole('button', { name: 'Compute again' })).toBeInTheDocument();
    expect(computed).toBe(1);

    await user.click(screen.getByRole('button', { name: /^Health of the session/ }));
    expect(screen.queryByRole('region', { name: 'Session health' })).toBeNull();
  });

  it('shows a stored health at once', async () => {
    server.use(http.get('/api/v1/admin/sessions/{id}/health', ({ response }) => response(200).json(sessionHealth)));
    const { user } = renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: /^Health of the session/ }));

    const panel = within(await screen.findByRole('region', { name: 'Session health' }));
    expect(await panel.findByText('GMS_FUSED 100%')).toBeInTheDocument();
  });

  it('shows other errors as they are', async () => {
    server.use(
      http.get('/api/v1/admin/sessions/{id}/health', ({ response }) =>
        response(403).json(problem(403, 'SESSION_HEALTH_FORBIDDEN')),
      ),
    );
    const { user } = renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: /^Health of the session/ }));

    expect(await screen.findByText('Session health and the provider report are for admins only.')).toBeInTheDocument();
  });
});

describe('the command form (C2)', () => {
  function captureCommands() {
    const bodies: IssueCommand[] = [];
    server.use(
      http.post('/api/v1/admin/drivers/{driverId}/commands', async ({ request, response }) => {
        bodies.push(await request.json());
        return response(202).json({ commandId: '9a0b1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d' });
      }),
    );
    return bodies;
  }

  async function openForm() {
    const rendered = renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });
    await rendered.user.click(await screen.findByRole('button', { name: 'Send a command' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Send a command' }));
    return { ...rendered, dialog };
  }

  it('needs an accuracy, an interval and a reason to set a constraint', async () => {
    const bodies = captureCommands();
    const seen = recordRequests();
    const { user, dialog } = await openForm();

    await user.click(dialog.getByRole('button', { name: 'Send' }));
    expect(dialog.getByText('Pick an accuracy.')).toBeInTheDocument();
    expect(dialog.getByText('A whole number of 0 or more.')).toBeInTheDocument();
    expect(dialog.getByText('Give a reason.')).toBeInTheDocument();
    expect(bodies).toEqual([]);

    await user.selectOptions(dialog.getByLabelText(/Highest accuracy/), 'BALANCED');
    await user.type(dialog.getByLabelText(/Shortest interval/), '5000');
    await user.type(dialog.getByLabelText(/Reason/), 'Save battery');
    await user.click(dialog.getByRole('button', { name: 'Send' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(bodies).toEqual([
      { kind: 'SET_CONSTRAINT', maxAccuracy: 'BALANCED', minIntervalMs: 5000, reason: 'Save battery' },
    ]);
    // The list is read again after the POST, so the new command shows.
    await waitFor(() => {
      expect(seen.filter((request) => request.endsWith('/commands'))).toEqual([
        `GET /api/v1/admin/drivers/${DRIVER_ID}/commands`,
        `POST /api/v1/admin/drivers/${DRIVER_ID}/commands`,
        `GET /api/v1/admin/drivers/${DRIVER_ID}/commands`,
      ]);
    });
  });

  it('checks every geofence row against the contract limits, and adds and removes rows', async () => {
    const bodies = captureCommands();
    const { user, dialog } = await openForm();

    await user.click(dialog.getByRole('radio', { name: 'Register geofences' }));
    await user.click(dialog.getByRole('button', { name: 'Send' }));
    const first = within(dialog.getByRole('group', { name: 'Region 1' }));
    expect(first.getByText('Give an id.')).toBeInTheDocument();
    expect(first.getByText('From -90 to 90.')).toBeInTheDocument();
    expect(first.getByText('From -180 to 180.')).toBeInTheDocument();
    expect(first.getByText('More than 0.')).toBeInTheDocument();
    expect(first.getByRole('button', { name: 'Remove region 1' })).toBeDisabled();

    await user.type(first.getByLabelText(/Id/), 'depot');
    await user.type(first.getByLabelText(/Latitude/), '21.0285');
    await user.type(first.getByLabelText(/Longitude/), '105.8542');
    await user.type(first.getByLabelText(/Radius/), '150');
    expect(first.queryByText('Give an id.')).toBeNull();

    await user.click(dialog.getByRole('button', { name: 'Add a region' }));
    const second = within(dialog.getByRole('group', { name: 'Region 2' }));
    await user.type(second.getByLabelText(/Id/), 'far');
    await user.type(second.getByLabelText(/Latitude/), '-91');
    expect(second.getByText('From -90 to 90.')).toBeInTheDocument();
    await user.click(second.getByRole('button', { name: 'Remove region 2' }));
    expect(dialog.queryByRole('group', { name: 'Region 2' })).toBeNull();

    await user.type(dialog.getByLabelText(/Reason/), 'Depot alerts');
    await user.click(dialog.getByRole('button', { name: 'Send' }));

    await waitFor(() => {
      expect(bodies).toEqual([
        {
          kind: 'REGISTER_GEOFENCES',
          regions: [{ id: 'depot', lat: 21.0285, lng: 105.8542, radiusM: 150 }],
          reason: 'Depot alerts',
        },
      ]);
    });
  });

  it('flushes the queue with only a reason', async () => {
    const bodies = captureCommands();
    const { user, dialog } = await openForm();

    await user.click(dialog.getByRole('radio', { name: 'Flush the queue' }));
    await user.click(dialog.getByRole('button', { name: 'Send' }));
    expect(dialog.getByText('Give a reason.')).toBeInTheDocument();

    await user.type(dialog.getByLabelText(/Reason/), 'Need the latest fixes');
    await user.click(dialog.getByRole('button', { name: 'Send' }));

    await waitFor(() => {
      expect(bodies).toEqual([{ kind: 'FLUSH_QUEUE', reason: 'Need the latest fixes' }]);
    });
  });

  it('lists the commands with their settings and statuses', async () => {
    server.use(
      http.get('/api/v1/admin/drivers/{driverId}/commands', ({ response }) =>
        response(200).json({
          items: [
            command({
              kind: 'SET_CONSTRAINT',
              maxAccuracy: 'LOW',
              minIntervalMs: 10000,
              reason: 'Battery',
              status: 'REJECTED',
            }),
            command({ kind: 'REGISTER_GEOFENCES', regions: [{ id: 'depot', lat: 21, lng: 105, radiusM: 100 }] }),
            command({ status: 'EXPIRED' }),
          ],
        }),
      ),
    );
    renderApp({ path: DETAIL_PATH, session: testSession('ADMIN') });

    const table = within(await screen.findByRole('table', { name: 'Commands' }));
    expect(table.getByText('LOW, at most every 10000 ms. Battery')).toBeInTheDocument();
    expect(table.getByText('1 region: depot')).toBeInTheDocument();
    expect(table.getByText('Upload the queue now')).toBeInTheDocument();
    expect(table.getByText('REJECTED')).toBeInTheDocument();
    expect(table.getByText('EXPIRED')).toBeInTheDocument();
  });
});

describe('fleet lag fix C: presence on the driver page', () => {
  it('shows the position age next to Online, and when the server last heard from the driver', async () => {
    const detail = driverDetail();
    server.use(
      http.get('/api/v1/admin/drivers/{driverId}', ({ response }) =>
        response(200).json({
          ...detail,
          driver: { ...detail.driver, position: { lat: 21.03, lng: 105.85, at: '2026-11-20T08:28:00Z' } },
        }),
      ),
    );
    renderApp({ path: DETAIL_PATH, session: testSession('OPERATOR') });

    expect(await screen.findByText('position 2 min old')).toBeInTheDocument();
    expect(screen.getByText('Last heard')).toBeInTheDocument();
  });
});
