import { Badge } from '@mantine/core';
import { STATUS_COLORS, STATUS_LABELS, type TripStatus } from '../../trip/status';

export function StatusBadge({ status }: { status: TripStatus }) {
  return (
    <Badge color={STATUS_COLORS[status]} variant="light">
      {STATUS_LABELS[status]}
    </Badge>
  );
}
