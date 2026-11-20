import { focusManager } from '@tanstack/react-query';
import { act, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AT, DRIVER_ID, fleetDriver, fleetPage, idOf } from '../../test/fixtures';
import { renderApp } from '../../test/render';
import { http, problem, recordRequests, server, testSession } from '../../test/server';

/** Three pages: no cursor gives driver 1 and `c-2`, `c-2` gives driver 2 and `c-3`, `c-3` gives driver 3 and no more. */
function threePages() {
  server.use(
    http.get('/api/v1/admin/drivers', ({ query, response }) => {
      const cursor = query.get('cursor');
      if (cursor === null) return response(200).json(fleetPage([fleetDriver(1)], 'c-2'));
      if (cursor === 'c-2') return response(200).json(fleetPage([fleetDriver(2)], 'c-3'));
      return response(200).json(fleetPage([fleetDriver(3)]));
    }),
  );
}

function driverRequests(seen: string[]) {
  return seen.filter(
    (request) => request.startsWith('GET /api/v1/admin/drivers?') || request === 'GET /api/v1/admin/drivers',
  );
}

function pager() {
  return screen.queryByRole('group', { name: 'Pages of the list' });
}

function pagerButtons() {
  const group = pager();
  return group === null
    ? []
    : within(group)
        .getAllByRole('button')
        .map((button) => button.textContent);
}

describe('TC-12-UI-01: the driver list keeps its filters and cursor in the URL', () => {
  it('reads the filters from the URL and sends them', async () => {
    const seen = recordRequests();
    threePages();
    renderApp({ path: '/drivers?online=true&q=lan', session: testSession('OPERATOR') });

    expect(await screen.findByRole('link', { name: 'Driver 1' })).toBeInTheDocument();
    expect(screen.getByLabelText('Online')).toHaveValue('true');
    expect(screen.getByLabelText('Trip')).toHaveValue('');
    expect(screen.getByLabelText('Name or driver id')).toHaveValue('lan');
    expect(driverRequests(seen)).toEqual(['GET /api/v1/admin/drivers?online=true&q=lan']);
  });

  it('pages with "Next" and "Previous", keeps the filters, and the Back button keeps the trail', async () => {
    const seen = recordRequests();
    threePages();
    const { user, router } = renderApp({ path: '/drivers?online=true', session: testSession('OPERATOR') });

    await screen.findByRole('link', { name: 'Driver 1' });
    expect(pagerButtons()).toEqual(['Next']);

    // "Next" sends the cursor the response gave, and the URL holds it next to the filter.
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByRole('link', { name: 'Driver 2' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ online: true, cursor: 'c-2' });
    expect(driverRequests(seen).at(-1)).toBe('GET /api/v1/admin/drivers?online=true&cursor=c-2');
    expect(pagerButtons()).toEqual(['First page', 'Previous', 'Next']);

    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByRole('link', { name: 'Driver 3' })).toBeInTheDocument();
    expect(pagerButtons()).toEqual(['First page', 'Previous']);
    // The trail lives in the history entry, not in the URL.
    expect(router.state.location.state.cursorTrail).toEqual([null, 'c-2']);
    expect(router.state.location.href).toBe('/drivers?online=true&cursor=c-3');

    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByRole('link', { name: 'Driver 2' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ online: true, cursor: 'c-2' });

    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByRole('link', { name: 'Driver 1' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ online: true });
    expect(pagerButtons()).toEqual(['Next']);

    // Back returns to the second page with its own trail, so "Previous" is there again.
    act(() => {
      router.history.back();
    });
    expect(await screen.findByRole('link', { name: 'Driver 2' })).toBeInTheDocument();
    expect(pagerButtons()).toEqual(['First page', 'Previous', 'Next']);
  });

  it('starts over at the first page with no trail when a filter changes', async () => {
    const seen = recordRequests();
    threePages();
    const { user, router } = renderApp({ path: '/drivers', session: testSession('OPERATOR') });

    await screen.findByRole('link', { name: 'Driver 1' });
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('link', { name: 'Driver 2' });

    await user.selectOptions(screen.getByLabelText('Trip'), 'true');

    expect(await screen.findByRole('link', { name: 'Driver 1' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ onTrip: true });
    expect(router.state.location.state.cursorTrail).toBeUndefined();
    expect(pagerButtons()).toEqual(['Next']);
    expect(driverRequests(seen).at(-1)).toBe('GET /api/v1/admin/drivers?onTrip=true');

    await user.type(screen.getByLabelText('Name or driver id'), '  Lan {Enter}');
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ onTrip: true, q: 'Lan' });
    });
    expect(driverRequests(seen).at(-1)).toBe('GET /api/v1/admin/drivers?onTrip=true&q=Lan');

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({});
    });
    expect(screen.getByLabelText('Name or driver id')).toHaveValue('');
  });

  it('shows only "First page" on a shared link, which has a cursor but no trail', async () => {
    threePages();
    renderApp({ path: '/drivers?cursor=c-2', session: testSession('OPERATOR') });

    expect(await screen.findByRole('link', { name: 'Driver 2' })).toBeInTheDocument();
    expect(pagerButtons()).toEqual(['First page', 'Next']);
  });

  it('drops a filter value the API would refuse', async () => {
    const seen = recordRequests();
    const { router } = renderApp({ path: '/drivers?online=yes&q=', session: testSession('OPERATOR') });

    await screen.findByRole('link', { name: 'Driver 1' });
    expect(router.state.location.search).toEqual({});
    expect(driverRequests(seen)).toEqual(['GET /api/v1/admin/drivers']);
  });
});

describe('the driver list', () => {
  it('shows each driver with online, account, last seen, battery and trip stage', async () => {
    server.use(
      http.get('/api/v1/admin/drivers', ({ response }) =>
        response(200).json(
          fleetPage([
            fleetDriver(1, {
              online: false,
              enabled: false,
              device: { batteryPct: 15, isCharging: true, at: AT },
              trip: { status: 'ACCEPTED', at: AT },
            }),
            fleetDriver(2, { displayName: undefined, lastSeenAt: undefined, device: undefined }),
          ]),
        ),
      ),
    );
    renderApp({ path: '/drivers', session: testSession('OPERATOR') });

    const first = (await screen.findByRole('link', { name: 'Driver 1' })).closest('tr');
    expect(first).not.toBeNull();
    const cells = within(first as HTMLElement);
    expect(cells.getByText('Offline')).toBeInTheDocument();
    expect(cells.getByText('Disabled')).toBeInTheDocument();
    expect(cells.getByText('15 %, charging')).toBeInTheDocument();
    expect(cells.getByText('ACCEPTED')).toBeInTheDocument();
    const second = within(
      screen.getByRole('link', { name: `Driver ${idOf(DRIVER_ID, 2).slice(0, 8)}` }).closest('tr') as HTMLElement,
    );
    expect(second.getByText('Never')).toBeInTheDocument();
  });

  it('opens the driver when the row is clicked', async () => {
    const { user, router } = renderApp({ path: '/drivers', session: testSession('OPERATOR') });

    const row = (await screen.findByRole('link', { name: 'Driver 2' })).closest('tr') as HTMLElement;
    await user.click(within(row).getByText('Online'));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/drivers/${idOf(DRIVER_ID, 2)}`);
    });
  });

  it('says so when no driver matches', async () => {
    server.use(http.get('/api/v1/admin/drivers', ({ response }) => response(200).json(fleetPage([]))));
    renderApp({ path: '/drivers?q=nobody', session: testSession('OPERATOR') });

    expect(await screen.findByText('No driver matches these filters.')).toBeInTheDocument();
    expect(pager()).toBeNull();
  });

  it('shows the error of a refused list', async () => {
    server.use(
      http.get('/api/v1/admin/drivers', ({ response }) => response(403).json(problem(403, 'FLEET_FORBIDDEN'))),
    );
    renderApp({ path: '/drivers', session: testSession('OPERATOR') });

    expect(await screen.findByText('No permission')).toBeInTheDocument();
  });
});

describe('C3: the driver list does not refresh on its own', () => {
  it('shows "as of" from the page, refetches on Refresh, and not on window focus', async () => {
    const seen = recordRequests();
    const { user } = renderApp({ path: '/drivers', session: testSession('OPERATOR') });

    await screen.findByRole('link', { name: 'Driver 1' });
    expect(screen.getByText(/^As of/).querySelector('time')).toHaveAttribute('dateTime', AT);
    expect(driverRequests(seen)).toHaveLength(1);

    act(() => {
      focusManager.setFocused(false);
      focusManager.setFocused(true);
    });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(driverRequests(seen)).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => {
      expect(driverRequests(seen)).toHaveLength(2);
    });
    focusManager.setFocused(undefined);
  });
});

describe('TC-12-UI-04: CSV export', () => {
  it('sends the Authorization header and downloads the file', async () => {
    let authorization: string | null = null;
    server.use(
      http.get('/api/v1/admin/drivers.csv', ({ request, response }) => {
        authorization = request.headers.get('Authorization');
        return response(200).text('driverId,displayName\n', {
          headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename=drivers.csv' },
        });
      }),
    );
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const { user } = renderApp({ path: '/drivers', session: testSession('ADMIN', { token: 'token-admin' }) });

    await user.click(await screen.findByRole('button', { name: 'Export CSV' }));

    await waitFor(() => {
      expect(click).toHaveBeenCalledOnce();
    });
    expect(authorization).toBe('Bearer token-admin');
    expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('drivers.csv');
  });

  it('shows why an export failed', async () => {
    server.use(
      http.get('/api/v1/admin/drivers.csv', ({ response }) => response(403).json(problem(403, 'FLEET_FORBIDDEN'))),
    );
    const { user } = renderApp({ path: '/drivers', session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: 'Export CSV' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No permission');
  });

  it('is not offered to an operator', async () => {
    renderApp({ path: '/drivers', session: testSession('OPERATOR') });

    await screen.findByRole('link', { name: 'Driver 1' });
    expect(screen.queryByRole('button', { name: 'Export CSV' })).toBeNull();
  });
});
