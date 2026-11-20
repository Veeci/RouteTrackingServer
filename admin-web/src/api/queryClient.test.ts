import { MutationObserver } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, networkError } from './errors';
import { createQueryClient, isRetryable, shouldRetryRead } from './queryClient';

const apiError = (status: number) => new ApiError({ status, code: `HTTP_${String(status)}`, title: 'x' });

describe('read retries', () => {
  it('retry network errors and 5xx, never 4xx or an error from the app', () => {
    expect(isRetryable(networkError(new TypeError('Failed to fetch')))).toBe(true);
    expect(isRetryable(apiError(503))).toBe(true);
    expect(isRetryable(apiError(404))).toBe(false);
    expect(isRetryable(apiError(401))).toBe(false);
    expect(isRetryable(apiError(429))).toBe(false);
    expect(isRetryable(new TypeError('x is undefined'))).toBe(false);
  });

  it('stop after two retries', () => {
    expect(shouldRetryRead(0, apiError(503))).toBe(true);
    expect(shouldRetryRead(1, apiError(503))).toBe(true);
    expect(shouldRetryRead(2, apiError(503))).toBe(false);
  });

  it('run a failing read three times in all', async () => {
    const queryClient = createQueryClient({ onUnauthorized: vi.fn(), retryDelay: 0 });
    const queryFn = vi.fn(() => Promise.reject(apiError(503)));

    await expect(queryClient.query({ queryKey: ['x'], queryFn })).rejects.toThrow(ApiError);
    expect(queryFn).toHaveBeenCalledTimes(3);
  });
});

describe('a 401', () => {
  it('from a query calls onUnauthorized once, without a retry', async () => {
    const onUnauthorized = vi.fn();
    const queryClient = createQueryClient({ onUnauthorized, retryDelay: 0 });
    const queryFn = vi.fn(() => Promise.reject(apiError(401)));

    await expect(queryClient.query({ queryKey: ['x'], queryFn })).rejects.toThrow(ApiError);
    expect(queryFn).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('from a mutation calls onUnauthorized, unless the mutation says skipAuthRedirect', async () => {
    const onUnauthorized = vi.fn();
    const queryClient = createQueryClient({ onUnauthorized });
    const mutationFn = () => Promise.reject(apiError(401));

    await expect(new MutationObserver(queryClient, { mutationFn }).mutate()).rejects.toThrow(ApiError);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);

    const login = new MutationObserver(queryClient, { mutationFn, meta: { skipAuthRedirect: true } });
    await expect(login.mutate()).rejects.toThrow(ApiError);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('is the only status that ends the session', async () => {
    const onUnauthorized = vi.fn();
    const queryClient = createQueryClient({ onUnauthorized, retryDelay: 0 });

    await expect(queryClient.query({ queryKey: ['x'], queryFn: () => Promise.reject(apiError(403)) })).rejects.toThrow(
      ApiError,
    );
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});

describe('mutations', () => {
  it('never retry, even after a 5xx', async () => {
    const queryClient = createQueryClient({ onUnauthorized: vi.fn() });
    const mutationFn = vi.fn(() => Promise.reject(apiError(503)));

    await expect(new MutationObserver(queryClient, { mutationFn }).mutate()).rejects.toThrow(ApiError);
    expect(mutationFn).toHaveBeenCalledTimes(1);
  });
});
