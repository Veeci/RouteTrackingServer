import { z } from 'zod';

/**
 * Codes the app makes itself are lower case. The server's codes match `^[A-Z][A-Z0-9_]*$` (rts-v1.yaml, `Problem`),
 * so the two sets never clash.
 */
export const UNEXPECTED = 'unexpected';
export const NETWORK = 'network';

export interface ApiErrorInit {
  status: number;
  code: string;
  title: string;
  detail?: string | undefined;
  retryAfter?: number | undefined;
  traceId?: string | undefined;
  cause?: unknown;
}

/** Every failed API call ends as this one type: a Problem Details answer, an unreadable answer, or no answer. */
export class ApiError extends Error {
  override readonly name = 'ApiError';
  /** The HTTP status, or 0 when no response arrived. */
  readonly status: number;
  /** The server's `code`, or {@link UNEXPECTED} or {@link NETWORK}. */
  readonly code: string;
  readonly title: string;
  readonly detail: string | undefined;
  /** Seconds to wait, from `Retry-After`. */
  readonly retryAfter: number | undefined;
  /** The response's `X-Request-Id`, for a bug report. */
  readonly traceId: string | undefined;

  constructor(init: ApiErrorInit) {
    super(init.detail ?? init.title, { cause: init.cause });
    this.status = init.status;
    this.code = init.code;
    this.title = init.title;
    this.detail = init.detail;
    this.retryAfter = init.retryAfter;
    this.traceId = init.traceId;
  }
}

// Only the members the app reads. A body without a string `code` is not this API's Problem.
const problemSchema = z.object({
  code: z.string().min(1),
  title: z.string().optional(),
  detail: z.string().optional(),
  traceId: z.string().optional(),
});

/**
 * Seconds from a `Retry-After` value. The server sends whole seconds. A proxy may send an HTTP date instead, which
 * RFC 9110 also allows.
 */
export function parseRetryAfter(value: string | null, now: number = Date.now()): number | undefined {
  if (value === null) return undefined;
  const trimmed = value.trim();
  if (/^\d+$/.test(trimmed)) return Number(trimmed);
  // An HTTP date always ends in GMT. Date.parse alone would read "-5" as the year -5.
  if (!trimmed.endsWith(' GMT')) return undefined;
  const at = Date.parse(trimmed);
  if (Number.isNaN(at)) return undefined;
  return Math.max(0, Math.ceil((at - now) / 1000));
}

/** The error for a response that is not 2xx. `body` is what openapi-fetch read: parsed JSON, text, or undefined. */
export function toApiError(response: Response, body: unknown): ApiError {
  const retryAfter = parseRetryAfter(response.headers.get('Retry-After'));
  const requestId = response.headers.get('X-Request-Id') ?? undefined;
  const problem = problemSchema.safeParse(body);
  if (problem.success) {
    return new ApiError({
      status: response.status,
      code: problem.data.code,
      title: problem.data.title ?? statusTitle(response),
      detail: problem.data.detail,
      retryAfter,
      traceId: problem.data.traceId ?? requestId,
    });
  }
  // A proxy's HTML error page, an empty body, or JSON from something that is not this API.
  return new ApiError({
    status: response.status,
    code: UNEXPECTED,
    title: statusTitle(response),
    retryAfter,
    traceId: requestId,
  });
}

/** The error for a request that got no response: the server is down, the network is gone, or the proxy refused. */
export function networkError(cause: unknown): ApiError {
  return new ApiError({ status: 0, code: NETWORK, title: 'Server unreachable', cause });
}

function statusTitle(response: Response): string {
  return response.statusText.length > 0 ? response.statusText : `HTTP ${response.status}`;
}

/** The shape of an openapi-fetch result: a success branch with `data`, an error branch with `error`. */
export interface FetchResult {
  data?: unknown;
  error?: unknown;
  response: Response;
}

/** The data type of the success branch. The error branch has an optional `data`, so it gives `never`. */
export type SuccessData<R> = R extends { data: infer D; error?: never } ? D : never;

/**
 * The data of a successful call, or a thrown {@link ApiError}. Checks `response.ok`, not `error`, because
 * openapi-fetch returns `error: undefined` for an error response with an empty body.
 */
export function unwrap<R extends FetchResult>(result: R): SuccessData<R> {
  if (!result.response.ok) throw toApiError(result.response, result.error);
  return result.data as SuccessData<R>;
}

export function isApiError(error: unknown, status?: number): error is ApiError {
  return error instanceof ApiError && (status === undefined || error.status === status);
}
