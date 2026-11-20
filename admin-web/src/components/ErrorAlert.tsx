import { Alert, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import { ApiError } from '../api/errors';
import { describeError, type ErrorText } from '../api/messages';

export interface ErrorAlertProps {
  error: unknown;
  /** Text that replaces the default one for this error. */
  text?: ErrorText;
  children?: ReactNode;
}

/** A failed call, in words. The request id lets the server's logs be matched to it. */
export function ErrorAlert({ error, text, children }: ErrorAlertProps) {
  const { title, message } = text ?? describeError(error);
  const traceId = error instanceof ApiError ? error.traceId : undefined;
  return (
    <Alert color="red" title={title}>
      <Text size="sm">{message}</Text>
      {children}
      {traceId !== undefined && (
        <Text size="xs" c="dimmed" mt={4}>
          Request id: {traceId}
        </Text>
      )}
    </Alert>
  );
}
