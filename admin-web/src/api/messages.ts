import { ApiError, NETWORK, UNEXPECTED } from './errors';

export interface ErrorText {
  title: string;
  message: string;
}

type CodeText = ErrorText | ((error: ApiError) => ErrorText);

const NO_PERMISSION = 'No permission';

/**
 * Text for the server codes the pages can meet, where the server's `detail` alone would read badly. The codes come
 * from the Kotlin error enums behind each route (`FleetError`, `IdentityError`, `CommandError`, `TelemetryError`,
 * `AuditError`, `PlatformError`).
 */
const byCode: Record<string, CodeText> = {
  FLEET_FORBIDDEN: {
    title: NO_PERMISSION,
    message: 'The fleet pages are for operators and admins, and the export is for admins only.',
  },
  ACCOUNT_ACTION_FORBIDDEN: { title: NO_PERMISSION, message: 'Only an admin may disable or enable a driver.' },
  COMMAND_FORBIDDEN: { title: NO_PERMISSION, message: 'Only an admin may send device commands or list them.' },
  SESSION_HEALTH_FORBIDDEN: {
    title: NO_PERMISSION,
    message: 'Session health and the provider report are for admins only.',
  },
  AUDIT_FORBIDDEN: { title: NO_PERMISSION, message: 'The audit log is for admins only.' },
  DRIVER_NOT_FOUND: { title: 'Driver not found', message: 'No driver has this id. Check the link.' },
  REASON_REQUIRED: { title: 'Reason needed', message: 'Say why. The audit log keeps the reason.' },
  SESSION_HEALTH_NOT_FOUND: {
    title: 'Health not computed yet',
    message: "The server computes a session's health 10 to 15 minutes after its last message.",
  },
  INVALID_COMMAND: (error) => ({
    title: 'Command not valid',
    message: error.detail ?? 'The device could not use this command.',
  }),
  VALIDATION_FAILED: (error) => ({
    title: 'Invalid input',
    message:
      error.fieldErrors.length > 0
        ? error.fieldErrors.map(({ field, message }) => `${field} ${message}`).join('. ')
        : (error.detail ?? 'The server refused a field.'),
  }),
  MALFORMED_REQUEST: {
    title: 'Request not readable',
    message: 'The server could not read the request. That is a bug in the dashboard.',
  },
  PAYLOAD_TOO_LARGE: { title: 'Request too large', message: 'Send less at once, for example fewer geofences.' },
  NOT_FOUND: {
    title: 'Not found',
    message: 'The server has no such route. With an admin port, the dashboard must reach that port.',
  },
  INTERNAL_ERROR: { title: 'Server error', message: 'The server failed. Quote the request id when you report it.' },
};

/** What the UI says about a failed call: a text per code, then per status, then the server's own words. */
export function describeError(error: unknown): ErrorText {
  if (!(error instanceof ApiError)) {
    return { title: 'Something went wrong', message: error instanceof Error ? error.message : String(error) };
  }
  const known = byCode[error.code];
  if (known !== undefined) return typeof known === 'function' ? known(error) : known;
  if (error.code === NETWORK) {
    return { title: 'Server unreachable', message: 'The RouteTracking server did not answer. Check that it runs.' };
  }
  if (error.status === 401) return { title: 'Session ended', message: 'Log in again.' };
  if (error.status === 403) return { title: NO_PERMISSION, message: 'Your role may not do this.' };
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
