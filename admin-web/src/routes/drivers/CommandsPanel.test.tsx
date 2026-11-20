import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CommandStatus } from '../../api/types';
import { command, DRIVER_ID } from '../../test/fixtures';
import { renderApp } from '../../test/render';
import { http, recordRequests, server, testSession } from '../../test/server';
import { ApiError } from '../../api/errors';
import { COMMAND_POLL_MS, commandPollInterval, DETAIL_REFRESH_MS, detailRefetchInterval } from './queries';

describe('commandPollInterval', () => {
  const list = (...statuses: CommandStatus[]) => ({ items: statuses.map((status) => command({ status })) });

  it('polls while any command is PENDING or SENT', () => {
    expect(commandPollInterval(list('APPLIED', 'PENDING'))).toBe(COMMAND_POLL_MS);
    expect(commandPollInterval(list('SENT'))).toBe(COMMAND_POLL_MS);
  });

  it('stops once every status is final, and before the first answer', () => {
    expect(commandPollInterval(list('APPLIED', 'REJECTED', 'EXPIRED'))).toBe(false);
    expect(commandPollInterval(list())).toBe(false);
    expect(commandPollInterval(undefined)).toBe(false);
  });
});

describe('detailRefetchInterval', () => {
  const failed = (status: number) => new ApiError({ status, code: 'X', title: 'X' });

  it('asks again every 10 s, also after a failure that may pass', () => {
    expect(detailRefetchInterval(null)).toBe(DETAIL_REFRESH_MS);
    expect(detailRefetchInterval(failed(503))).toBe(DETAIL_REFRESH_MS);
    expect(detailRefetchInterval(failed(429))).toBe(DETAIL_REFRESH_MS);
  });

  it('stops after a bad id, a refusal or an unknown driver', () => {
    for (const status of [400, 403, 404]) expect(detailRefetchInterval(failed(status))).toBe(false);
  });
});

describe('the command list polling', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('asks every 3 s while a command waits, and stops when all are final (fake timers)', async () => {
    // Fake time also runs with the real clock, so Testing Library's own waits still end.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const answers: CommandStatus[] = ['PENDING', 'SENT', 'APPLIED'];
    let calls = 0;
    server.use(
      http.get('/api/v1/admin/drivers/{driverId}/commands', ({ response }) => {
        const status = answers[Math.min(calls, answers.length - 1)] ?? 'APPLIED';
        calls += 1;
        return response(200).json({ items: [command({ status })] });
      }),
    );
    const seen = recordRequests();
    const listCalls = () => seen.filter((request) => request === `GET /api/v1/admin/drivers/${DRIVER_ID}/commands`);
    renderApp({ path: `/drivers/${DRIVER_ID}`, session: testSession('ADMIN') });

    const table = async () => within(await screen.findByRole('table', { name: 'Commands' }));
    expect((await table()).getByText('PENDING')).toBeInTheDocument();
    expect(screen.getByText('A command waits for the device. The list asks again every 3 s.')).toBeInTheDocument();
    expect(listCalls()).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(COMMAND_POLL_MS);
    expect(await (await table()).findByText('SENT')).toBeInTheDocument();
    expect(listCalls()).toHaveLength(2);

    await vi.advanceTimersByTimeAsync(COMMAND_POLL_MS);
    expect(await (await table()).findByText('APPLIED')).toBeInTheDocument();
    expect(listCalls()).toHaveLength(3);
    expect(screen.queryByText(/The list asks again every 3 s/)).toBeNull();

    // Every status is final: no more requests, however long the page stays open.
    await vi.advanceTimersByTimeAsync(COMMAND_POLL_MS * 5);
    expect(listCalls()).toHaveLength(3);
  });
});
