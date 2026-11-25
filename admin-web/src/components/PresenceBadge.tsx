import { Badge, Group, Text } from '@mantine/core';
import { formatAge } from './format';

export interface PresenceBadgeProps {
  /** Judged by the server on its own clock: it heard from the driver within the last 60 seconds. */
  online: boolean;
  /** What the age is of, as the reader should see it: "position", or "last update" when only that time is known. */
  subject?: string;
  /** When the shown data was measured, by the phone's clock. Undefined: there is none yet. */
  dataAt: string | undefined;
  /** The server time the answer was judged at. Undefined: no age is shown. */
  now: string | undefined;
}

/**
 * Online or offline, and, for an online driver, how old the shown data is. Since fleet lag fix C a driver is online
 * when the server hears from them, even while their position is minutes old (a slow phone clock, or positions still on
 * their way through the server), so the age tells staff how far to trust the position.
 */
export function PresenceBadge({ online, subject = 'position', dataAt, now }: PresenceBadgeProps) {
  return (
    <Group gap={6} wrap="nowrap">
      <Badge color={online ? 'green' : 'gray'} variant="light">
        {online ? 'Online' : 'Offline'}
      </Badge>
      {online && now !== undefined && (
        <Text size="xs" c="dimmed">
          {dataAt === undefined ? `no ${subject} yet` : `${subject} ${formatAge(dataAt, now)} old`}
        </Text>
      )}
    </Group>
  );
}
