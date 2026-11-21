import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DRIVER_ID, GUEST_ID, trip, tripPage } from '../../test/fixtures';
import { nth, renderApp } from '../../test/render';
import { http, idOfTrip, recordRequests, server, testSession } from '../../test/server';
import { LIST_REFRESH_MS, listRefetchInterval, tripsQuery } from './queries';
import { tripsSearch } from './search';

/** No cursor gives trip 1 and `c-2`, `c-2` gives trip 2 and no more. */
function twoPages() {
  server.use(
    http.get('/api/v1/admin/trips', ({ query, response }) =>
      query.get('cursor') === null
        ? response(200).json(tripPage([trip({ id: idOfTrip(1) })], 'c-2'))
        : response(200).json(tripPage([trip({ id: idOfTrip(2), status: 'CANCELLED' })])),
    ),
  );
}

function tripRequests(seen: string[]) {
  return seen.filter((request) => request.startsWith('GET /api/v1/admin/trips'));
}

describe('the trip list', () => {
  it('reads the filters from the URL and sends each stage as its own status param', async () => {
    const seen = recordRequests();
    twoPages();
    renderApp({
      path: `/trips?status=${encodeURIComponent('["ARRIVED","ACCEPTED"]')}&driverId=${DRIVER_ID}&from=2026-11-01&to=2026-11-02`,
      session: testSession('OPERATOR'),
    });

    expect(await screen.findByRole('link', { name: '3b241101' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Accepted' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Driver arrived' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Completed' })).not.toBeChecked();
    expect(screen.getByLabelText('Driver id')).toHaveValue(DRIVER_ID);

    const [request] = tripRequests(seen);
    const query = new URL(`http://x${(request ?? '').slice('GET '.length)}`).searchParams;
    // The contract's order, not the URL's, and the API's repeated form.
    expect(query.getAll('status')).toEqual(['ACCEPTED', 'ARRIVED']);
    expect(query.get('driverId')).toBe(DRIVER_ID);
    // Local midnight of the first day, and the midnight after the last day.
    expect(query.get('from')).toBe(new Date(2026, 10, 1).toISOString());
    expect(query.get('to')).toBe(new Date(2026, 10, 3).toISOString());
  });

  it('applies the filters together, starting at the first page', async () => {
    twoPages();
    const { user, router } = renderApp({ path: '/trips', session: testSession('OPERATOR') });
    await screen.findByRole('link', { name: '3b241101' });

    await user.click(screen.getByRole('checkbox', { name: 'In progress' }));
    await user.click(screen.getByRole('checkbox', { name: 'Requested' }));
    await user.type(screen.getByLabelText('Guest id'), GUEST_ID);
    // Nothing applies before "Apply".
    expect(router.state.location.search).toEqual({});
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() => {
      expect(router.state.location.search).toEqual({ status: ['REQUESTED', 'IN_PROGRESS'], guestId: GUEST_ID });
    });
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({});
    });
  });

  it('refuses an id that is not a UUID and a range that ends before it starts', async () => {
    const { user, router } = renderApp({ path: '/trips', session: testSession('OPERATOR') });
    await screen.findByRole('table', { name: 'Trips' });
    await user.type(screen.getByLabelText('Driver id'), 'abc');
    await user.type(screen.getByLabelText('Created from day'), '2026-11-05');
    await user.type(screen.getByLabelText('Created to day'), '2026-11-01');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(screen.getByText('A whole driver id (UUID).')).toBeInTheDocument();
    expect(screen.getByText('The last day is before the first day.')).toBeInTheDocument();
    expect(router.state.location.search).toEqual({});
  });

  it('pages with "Next" and the response cursor, and a row links to the trip and its driver', async () => {
    const seen = recordRequests();
    twoPages();
    const { user, router } = renderApp({ path: '/trips', session: testSession('OPERATOR') });
    await screen.findByRole('link', { name: '3b241101' });
    expect(screen.getByText(/Updates every 10 s/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Next' }));
    const table = await screen.findByRole('table', { name: 'Trips' });
    await waitFor(() => {
      expect(within(table).getByText('Cancelled')).toBeInTheDocument();
    });
    expect(router.state.location.search).toEqual({ cursor: 'c-2' });
    expect(tripRequests(seen).at(-1)).toBe('GET /api/v1/admin/trips?cursor=c-2');
    // A later page does not refresh on its own (C2).
    expect(screen.queryByText(/Updates every/)).not.toBeInTheDocument();

    const row = within(nth(within(table).getAllByRole('row'), 1));
    expect(row.getByRole('link', { name: '3b241101' })).toHaveAttribute('href', `/trips/${idOfTrip(2)}`);
    expect(row.getByRole('link', { name: '7f1c2a9e' })).toHaveAttribute('href', `/drivers/${DRIVER_ID}`);
  });

  it('says so when no trip matches', async () => {
    server.use(http.get('/api/v1/admin/trips', ({ response }) => response(200).json(tripPage([]))));
    renderApp({ path: '/trips', session: testSession('OPERATOR') });
    expect(await screen.findByText('No trip matches these filters.')).toBeInTheDocument();
  });
});

describe('C2: page 1 refreshes, later pages do not', () => {
  it('asks again every 10 s on page 1 only', () => {
    expect(listRefetchInterval(undefined)).toBe(LIST_REFRESH_MS);
    expect(listRefetchInterval('c-2')).toBe(false);
    expect(tripsQuery({}).refetchInterval).toBe(10_000);
    const later = tripsQuery({ cursor: 'c-2' });
    expect(later.refetchInterval).toBe(false);
    expect(later.staleTime).toBe(Infinity);
  });
});

describe('the status param', () => {
  it('keeps known stages in the contract order and drops the rest', () => {
    expect(tripsSearch.parse({ status: ['CANCELLED', 'nope', 'REQUESTED', 'CANCELLED'] }).status).toEqual([
      'REQUESTED',
      'CANCELLED',
    ]);
    expect(tripsSearch.parse({ status: 'ARRIVED' }).status).toEqual(['ARRIVED']);
    expect(tripsSearch.parse({ status: ['nope'] }).status).toBeUndefined();
    expect(tripsSearch.parse({ status: 42 }).status).toBeUndefined();
  });
});
