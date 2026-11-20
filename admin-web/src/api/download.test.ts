import { HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, type MockInstance, vi } from 'vitest';
import { sessionStore } from '../auth/session';
import { http, problem, server, testSession } from '../test/server';
import { api } from './client';
import { downloadCsv, fileNameOf, REVOKE_AFTER_MS } from './download';
import { ApiError, NETWORK } from './errors';

const csv = 'driverId,displayName\n1,Lan\n';

function fetchDriversCsv() {
  return api.GET('/api/v1/admin/drivers.csv', { parseAs: 'blob' });
}

describe('downloadCsv', () => {
  let click: MockInstance<HTMLAnchorElement['click']>;

  beforeEach(() => {
    sessionStore.getState().signIn(testSession('ADMIN', { token: 'token-csv' }));
    // jsdom would try to navigate to the blob URL and log that it cannot.
    click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('fetches with the session token, clicks a link to a blob URL and revokes the URL later', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout'] });
    let authorization: string | null = null;
    server.use(
      http.get('/api/v1/admin/drivers.csv', ({ request, response }) => {
        authorization = request.headers.get('Authorization');
        return response(200).text(csv, {
          headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename=drivers.csv' },
        });
      }),
    );
    const create = vi.spyOn(URL, 'createObjectURL');
    const revoke = vi.spyOn(URL, 'revokeObjectURL');

    const name = await downloadCsv(fetchDriversCsv(), 'fallback.csv');

    expect(authorization).toBe('Bearer token-csv');
    expect(name).toBe('drivers.csv');
    expect(create).toHaveBeenCalledOnce();
    expect(await (create.mock.calls[0]?.[0] as Blob).text()).toBe(csv);
    const link = click.mock.contexts[0] as HTMLAnchorElement;
    expect(link.download).toBe('drivers.csv');
    expect(link.href).toBe('blob:http://localhost/test');
    expect(link.isConnected).toBe(false);

    expect(revoke).not.toHaveBeenCalled();
    vi.advanceTimersByTime(REVOKE_AFTER_MS);
    expect(revoke).toHaveBeenCalledWith('blob:http://localhost/test');
  });

  it('uses the fallback name when the answer names no file', async () => {
    server.use(http.get('/api/v1/admin/drivers.csv', ({ response }) => response(200).text(csv)));

    expect(await downloadCsv(fetchDriversCsv(), 'fallback.csv')).toBe('fallback.csv');
  });

  it('throws the ApiError of a refused export and saves nothing', async () => {
    server.use(
      http.get('/api/v1/admin/drivers.csv', ({ response }) => response(403).json(problem(403, 'FLEET_FORBIDDEN'))),
    );

    await expect(downloadCsv(fetchDriversCsv(), 'x.csv')).rejects.toMatchObject({
      status: 403,
      code: 'FLEET_FORBIDDEN',
    });
    expect(click).not.toHaveBeenCalled();
  });

  it('throws a network ApiError when the server does not answer', async () => {
    server.use(http.get('/api/v1/admin/drivers.csv', () => HttpResponse.error()));

    const error = await downloadCsv(fetchDriversCsv(), 'x.csv').catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ code: NETWORK });
  });

  it('refuses an answer that is not a file', async () => {
    const call = Promise.resolve({ data: 'text', response: new Response('text') });

    await expect(downloadCsv(call, 'x.csv')).rejects.toMatchObject({ code: 'unexpected' });
  });
});

describe('fileNameOf', () => {
  it('reads a plain or quoted file name', () => {
    expect(fileNameOf('attachment; filename=providers-1-2.csv')).toBe('providers-1-2.csv');
    expect(fileNameOf('attachment; filename="drivers list.csv"')).toBe('drivers list.csv');
    expect(fileNameOf('attachment;filename=a.csv; size=3')).toBe('a.csv');
  });

  it('drops path parts and gives nothing for a missing or empty name', () => {
    expect(fileNameOf('attachment; filename="../../etc/x.csv"')).toBe('x.csv');
    expect(fileNameOf('attachment')).toBeUndefined();
    expect(fileNameOf('attachment; filename=""')).toBeUndefined();
    expect(fileNameOf(null)).toBeUndefined();
  });
});
