import { describe, expect, it } from 'vitest';
import { ApiError, NETWORK, networkError, parseRetryAfter, toApiError, UNEXPECTED, unwrap } from './errors';
import { describeError } from './messages';

// TC-12-API-01: every failed call ends as one ApiError, with the server's code when it sent Problem Details.

const problemJson = { 'Content-Type': 'application/problem+json' };

describe('toApiError', () => {
  it('reads code, title, detail and traceId from a Problem Details body', () => {
    const response = new Response(null, { status: 409, headers: problemJson });
    const error = toApiError(response, {
      type: 'urn:rts:error:illegal-transition',
      title: 'Illegal transition',
      status: 409,
      detail: 'The trip has ended.',
      code: 'ILLEGAL_TRANSITION',
      traceId: 'req-1',
    });

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 409,
      code: 'ILLEGAL_TRANSITION',
      title: 'Illegal transition',
      detail: 'The trip has ended.',
      traceId: 'req-1',
    });
    expect(error.message).toBe('The trip has ended.');
  });

  it('makes a non-JSON error body code "unexpected"', () => {
    const response = new Response(null, { status: 502, statusText: 'Bad Gateway' });
    const error = toApiError(response, '<html><body>502 Bad Gateway</body></html>');

    expect(error).toMatchObject({ status: 502, code: UNEXPECTED, title: 'Bad Gateway', detail: undefined });
  });

  it('makes an empty body or JSON without a code "unexpected"', () => {
    expect(toApiError(new Response(null, { status: 500 }), undefined)).toMatchObject({
      code: UNEXPECTED,
      title: 'HTTP 500',
    });
    expect(toApiError(new Response(null, { status: 400 }), { message: 'nope' }).code).toBe(UNEXPECTED);
  });

  it('parses Retry-After and falls back to X-Request-Id for the trace id', () => {
    const response = new Response(null, { status: 429, headers: { 'Retry-After': '30', 'X-Request-Id': 'req-9' } });
    const error = toApiError(response, { code: 'RATE_LIMITED', title: 'Too many requests' });

    expect(error).toMatchObject({ status: 429, code: 'RATE_LIMITED', retryAfter: 30, traceId: 'req-9' });
  });
});

describe('parseRetryAfter', () => {
  const now = Date.parse('2026-11-20T08:00:00Z');

  it('reads whole seconds', () => {
    expect(parseRetryAfter('120', now)).toBe(120);
    expect(parseRetryAfter(' 5 ', now)).toBe(5);
  });

  it('reads an HTTP date as the seconds until then, never below 0', () => {
    expect(parseRetryAfter('Fri, 20 Nov 2026 08:00:30 GMT', now)).toBe(30);
    expect(parseRetryAfter('Fri, 20 Nov 2026 07:59:00 GMT', now)).toBe(0);
  });

  it('ignores a missing or unreadable value', () => {
    expect(parseRetryAfter(null, now)).toBeUndefined();
    expect(parseRetryAfter('soon', now)).toBeUndefined();
    expect(parseRetryAfter('-5', now)).toBeUndefined();
  });
});

describe('unwrap', () => {
  it('returns the data of a 2xx response', () => {
    const result = { data: { onlineDrivers: 3 }, response: new Response(null, { status: 200 }) };

    expect(unwrap(result)).toEqual({ onlineDrivers: 3 });
  });

  it('throws on a non-2xx response even when openapi-fetch read no error body', () => {
    const result = { error: undefined, response: new Response(null, { status: 503 }) };

    expect(() => unwrap(result)).toThrow(ApiError);
  });
});

describe('describeError', () => {
  const api = (status: number, code: string, extra: Partial<ApiError> = {}) =>
    new ApiError({ status, code, title: code, ...extra });

  it('says "No permission" for any 403', () => {
    expect(describeError(api(403, 'TRIP_ACTION_FORBIDDEN')).title).toBe('No permission');
    expect(describeError(api(403, 'FLEET_FORBIDDEN')).title).toBe('No permission');
  });

  it('says "Server unreachable" when no response arrived', () => {
    const error = networkError(new TypeError('Failed to fetch'));

    expect(error).toMatchObject({ status: 0, code: NETWORK });
    expect(describeError(error).title).toBe('Server unreachable');
  });

  it('says how long to wait on a 429', () => {
    expect(describeError(api(429, 'RATE_LIMITED', { retryAfter: 12 })).message).toBe('Wait 12 s and try again.');
    expect(describeError(api(429, 'RATE_LIMITED')).message).toBe('Wait a moment and try again.');
  });

  it("falls back to the server's detail, then its title", () => {
    expect(describeError(api(409, 'X', { detail: 'Already ended.' }))).toEqual({
      title: 'X',
      message: 'Already ended.',
    });
    expect(describeError(api(409, 'X'))).toEqual({ title: 'X', message: 'X' });
  });

  it('names an unexpected answer, a 401 and an error from the app itself', () => {
    expect(describeError(api(502, UNEXPECTED)).message).toContain('HTTP 502');
    expect(describeError(api(401, 'UNAUTHORIZED')).title).toBe('Session ended');
    expect(describeError(new Error('boom'))).toEqual({ title: 'Something went wrong', message: 'boom' });
  });
});
