import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ADMIN_ID, auditPage, auditRecord, DRIVER_ID } from '../../test/fixtures';
import { nth, renderApp } from '../../test/render';
import { http, problem, recordRequests, server, testSession } from '../../test/server';
import { dayEnd, dayStart } from '../dates';

function auditRequests(seen: string[]) {
  return seen.filter((request) => request.startsWith('GET /api/v1/admin/audit'));
}

describe('the audit page', () => {
  it('TC-12-AUTH-02: says "No permission" to an operator and sends no request', async () => {
    const seen = recordRequests();
    renderApp({ path: '/audit', session: testSession('OPERATOR') });

    expect(await screen.findByText('No permission')).toBeInTheDocument();
    expect(screen.getByText('The audit log is for admins only.')).toBeInTheDocument();
    expect(auditRequests(seen)).toEqual([]);
  });

  it('shows the actor shortened with the role, the reason, the details and the request id', async () => {
    server.use(
      http.get('/api/v1/admin/audit', ({ response }) =>
        response(200).json(
          auditPage([
            auditRecord(2, {
              action: 'COMMAND_ISSUED',
              reason: undefined,
              details: { kind: 'FLUSH_QUEUE', commandId: 'c-1' },
            }),
            auditRecord(1, {
              action: 'TRIP_CANCELLED',
              targetType: 'TRIP',
              targetId: '5d0f3c2a-1111-4222-8333-944455556666',
            }),
          ]),
        ),
      ),
    );
    renderApp({ path: '/audit', session: testSession('ADMIN') });

    const table = within(await screen.findByRole('table', { name: 'Audit records' }));
    const rows = table.getAllByRole('row');
    const command = within(nth(rows, 1));
    const trip = within(nth(rows, 2));
    expect(command.getByText(ADMIN_ID.slice(0, 8))).toHaveAttribute('title', ADMIN_ID);
    expect(command.getByText('ADMIN')).toBeInTheDocument();
    expect(command.getByText('kind: FLUSH_QUEUE')).toBeInTheDocument();
    expect(command.getByText('req-2')).toBeInTheDocument();
    // A driver target links to the driver. A trip target is text until the trip pages exist (12e).
    expect(command.getByRole('link', { name: `DRIVER ${DRIVER_ID.slice(0, 8)}` })).toHaveAttribute(
      'href',
      `/drivers/${DRIVER_ID}`,
    );
    expect(trip.queryByRole('link')).toBeNull();
    expect(trip.getByText('TRIP 5d0f3c2a')).toBeInTheDocument();
  });

  it('puts the filters in the URL and sends whole days as instants', async () => {
    const seen = recordRequests();
    const { user, router } = renderApp({ path: '/audit', session: testSession('ADMIN') });

    await screen.findByText('No record matches these filters.');
    await user.type(screen.getByRole('combobox', { name: 'Action' }), 'DRIVER_DISABLED');
    await user.selectOptions(screen.getByLabelText('Target type'), 'DRIVER');
    await user.type(screen.getByLabelText('Target id'), DRIVER_ID);
    await user.type(screen.getByLabelText('From day'), '2026-11-01');
    await user.type(screen.getByLabelText('To day'), '2026-11-20');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() => {
      expect(router.state.location.search).toEqual({
        action: 'DRIVER_DISABLED',
        targetType: 'DRIVER',
        targetId: DRIVER_ID,
        from: '2026-11-01',
        to: '2026-11-20',
      });
    });
    const sent = new URL(`http://x${(auditRequests(seen).at(-1) ?? '').slice(4)}`).searchParams;
    expect(sent.get('action')).toBe('DRIVER_DISABLED');
    expect(sent.get('from')).toBe(dayStart('2026-11-01'));
    expect(sent.get('to')).toBe(dayEnd('2026-11-20'));
  });

  it('refuses an actor id that is not a UUID and a reversed range', async () => {
    const { user, router } = renderApp({ path: '/audit', session: testSession('ADMIN') });

    await screen.findByText('No record matches these filters.');
    await user.type(screen.getByLabelText('Actor id'), 'lan');
    await user.type(screen.getByLabelText('From day'), '2026-11-20');
    await user.type(screen.getByLabelText('To day'), '2026-11-01');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(screen.getByText('A whole user id (UUID).')).toBeInTheDocument();
    expect(screen.getByText('The last day is before the first day.')).toBeInTheDocument();
    expect(router.state.location.search).toEqual({});
  });

  it('pages with the response cursor and clears the filters', async () => {
    const seen = recordRequests();
    server.use(
      http.get('/api/v1/admin/audit', ({ query, response }) =>
        query.get('cursor') === null
          ? response(200).json(auditPage([auditRecord(2)], '2'))
          : response(200).json(auditPage([auditRecord(1, { reason: 'Second page' })])),
      ),
    );
    const { user, router } = renderApp({ path: '/audit?action=DRIVER_DISABLED', session: testSession('ADMIN') });

    await user.click(await screen.findByRole('button', { name: 'Next' }));

    expect(await screen.findByText('Second page')).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ action: 'DRIVER_DISABLED', cursor: '2' });
    expect(auditRequests(seen).at(-1)).toBe('GET /api/v1/admin/audit?action=DRIVER_DISABLED&cursor=2');
    expect(screen.getByRole('button', { name: 'Previous' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({});
    });
  });

  it('shows the server error', async () => {
    server.use(http.get('/api/v1/admin/audit', ({ response }) => response(403).json(problem(403, 'AUDIT_FORBIDDEN'))));
    renderApp({ path: '/audit', session: testSession('ADMIN') });

    expect(await screen.findByText('The audit log is for admins only.')).toBeInTheDocument();
  });
});
