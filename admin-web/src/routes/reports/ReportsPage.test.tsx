import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { providerGroup, providerReport } from '../../test/fixtures';
import { nth, renderApp } from '../../test/render';
import { http, recordRequests, server, testSession } from '../../test/server';
import { dayEnd, dayStart } from '../dates';

function reportQuery(seen: string[]) {
  const request = seen.filter((line) => line.startsWith('GET /api/v1/admin/reports/providers?')).at(-1);
  if (request === undefined) return undefined;
  const params = new URL(`http://x${request.slice(4)}`).searchParams;
  return { from: params.get('from'), to: params.get('to') };
}

describe('the provider report', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('TC-12-AUTH-02: says "No permission" to an operator and sends no request', async () => {
    const seen = recordRequests();
    renderApp({ path: '/reports', session: testSession('OPERATOR') });

    expect(await screen.findByText('The provider report is for admins only.')).toBeInTheDocument();
    expect(reportQuery(seen)).toBeUndefined();
  });

  it('asks for the last 7 days when the URL names no range', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 10, 20, 15, 30));
    const seen = recordRequests();
    renderApp({ path: '/reports', session: testSession('ADMIN') });

    expect(await screen.findByText('No session in this range has its health computed.')).toBeInTheDocument();
    expect(screen.getByLabelText('First day')).toHaveValue('2026-11-14');
    expect(screen.getByLabelText(/Last day/)).toHaveValue('2026-11-20');
    expect(reportQuery(seen)).toEqual({ from: dayStart('2026-11-14'), to: dayEnd('2026-11-20') });
  });

  it('refuses more than 31 days before it sends anything', async () => {
    const seen = recordRequests();
    renderApp({ path: '/reports?from=2026-10-01&to=2026-11-20', session: testSession('ADMIN') });

    expect(await screen.findByText('Range not valid')).toBeInTheDocument();
    expect(screen.getByText('A report covers at most 31 days.')).toBeInTheDocument();
    expect(reportQuery(seen)).toBeUndefined();
  });

  it('puts a new range in the URL, and checks it in the form first', async () => {
    const seen = recordRequests();
    const { user, router } = renderApp({
      path: '/reports?from=2026-11-01&to=2026-11-07',
      session: testSession('ADMIN'),
    });

    await screen.findByText('No session in this range has its health computed.');
    const last = screen.getByLabelText(/Last day/);
    await user.clear(last);
    await user.type(last, '2026-12-15');
    await user.click(screen.getByRole('button', { name: 'Show' }));
    expect(screen.getByText('A report covers at most 31 days.')).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ from: '2026-11-01', to: '2026-11-07' });

    await user.clear(last);
    await user.type(last, '2026-11-30');
    await user.click(screen.getByRole('button', { name: 'Show' }));

    await waitFor(() => {
      expect(router.state.location.search).toEqual({ from: '2026-11-01', to: '2026-11-30' });
    });
    await waitFor(() => {
      expect(reportQuery(seen)).toEqual({ from: dayStart('2026-11-01'), to: dayEnd('2026-11-30') });
    });
  });

  it('dims groups with fewer than 3 sessions and says why', async () => {
    server.use(
      http.get('/api/v1/admin/reports/providers', ({ response }) =>
        response(200).json(
          providerReport([
            providerGroup(),
            providerGroup({ primaryProvider: 'AOSP_GPS', hasGms: false, rom: 'MIUI', sessions: 2, lowSample: true }),
            providerGroup({
              primaryProvider: undefined,
              hasGms: undefined,
              androidSdk: undefined,
              sessions: 1,
              lowSample: true,
            }),
          ]),
        ),
      ),
    );
    renderApp({ path: '/reports?from=2026-11-01&to=2026-11-07', session: testSession('ADMIN') });

    const table = within(await screen.findByRole('table', { name: 'Provider groups' }));
    expect(screen.getByText(/^Dimmed rows have fewer than 3 sessions/)).toBeInTheDocument();
    const rows = table.getAllByRole('row').slice(1);
    expect(rows.map((row) => row.getAttribute('data-low-sample'))).toEqual(['false', 'true', 'true']);
    expect(nth(rows, 1)).toHaveStyle({ opacity: '0.5' });
    expect(within(nth(rows, 1)).getByText('Few sessions')).toBeInTheDocument();
    expect(within(nth(rows, 0)).queryByText('Few sessions')).toBeNull();
    expect(within(nth(rows, 2)).getAllByText('Unknown')).toHaveLength(4);
  });

  it('exports the same range as CSV with the token', async () => {
    let asked: { authorization: string | null; from: string | null } | undefined;
    server.use(
      http.get('/api/v1/admin/reports/providers', ({ response }) =>
        response(200).json(providerReport([providerGroup()])),
      ),
      http.get('/api/v1/admin/reports/providers.csv', ({ request, query, response }) => {
        asked = { authorization: request.headers.get('Authorization'), from: query.get('from') };
        return response(200).text('primaryProvider\n', { headers: { 'Content-Type': 'text/csv' } });
      }),
    );
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const { user } = renderApp({ path: '/reports?from=2026-11-01&to=2026-11-07', session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: 'Export CSV' }));

    await waitFor(() => {
      expect(click).toHaveBeenCalledOnce();
    });
    expect(asked).toEqual({ authorization: 'Bearer token-test', from: dayStart('2026-11-01') });
    expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('providers-2026-11-01-2026-11-07.csv');
  });
});
