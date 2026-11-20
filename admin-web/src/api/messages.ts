import { ApiError, NETWORK, UNEXPECTED } from './errors';

export interface ErrorText {
  title: string;
  message: string;
}

/** Text for server codes that need more than the server's `detail`. 12c adds the action codes. */
const byCode: Record<string, ErrorText> = {
  FLEET_FORBIDDEN: { title: 'No permission', message: 'Your role may not see the fleet.' },
};

/** What the UI says about a failed call: a text per code, then per status, then the server's own words. */
export function describeError(error: unknown): ErrorText {
  if (!(error instanceof ApiError)) {
    return { title: 'Something went wrong', message: error instanceof Error ? error.message : String(error) };
  }
  const known = byCode[error.code];
  if (known !== undefined) return known;
  if (error.code === NETWORK) {
    return { title: 'Server unreachable', message: 'The RouteTracking server did not answer. Check that it runs.' };
  }
  if (error.status === 401) return { title: 'Session ended', message: 'Log in again.' };
  if (error.status === 403) return { title: 'No permission', message: 'Your role may not do this.' };
  if (error.status === 429) {
    const wait = error.retryAfter === undefined ? 'a moment' : `${error.retryAfter} s`;
    return { title: 'Too many requests', message: `Wait ${wait} and try again.` };
  }
  if (error.code === UNEXPECTED) {
    return {
      title: 'Unexpected answer',
      message: `The server answered HTTP ${error.status} without a problem report.`,
    };
  }
  return { title: error.title, message: error.detail ?? error.title };
}
