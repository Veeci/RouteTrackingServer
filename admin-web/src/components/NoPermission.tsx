import { Alert, Text } from '@mantine/core';

/** Shown in place of a page that the user's role may not use. No request is sent. */
export function NoPermission({ message }: { message: string }) {
  return (
    <Alert color="red" title="No permission">
      <Text size="sm">{message}</Text>
    </Alert>
  );
}
