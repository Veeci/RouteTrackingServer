import { Box, Group, Text } from '@mantine/core';
import { TRIP_COLORS } from '../../map/tripLayers';

type LegendItem = keyof typeof TRIP_COLORS;

const LABELS: Record<LegendItem, string> = {
  pickup: 'Pickup',
  destination: 'Destination',
  route: 'Planned route',
  approach: 'Drive to the pickup',
  track: 'Ride',
  driver: 'Driver',
  lastKnown: 'Driver, last known',
};

const LINES: readonly LegendItem[] = ['route', 'approach', 'track'];

/** The trip map's colours, for the items the view draws. */
export function MapLegend({ items, trackLabel }: { items: readonly LegendItem[]; trackLabel?: string }) {
  return (
    <Group gap="md" aria-label="Map colours" role="list">
      {items.map((item) => (
        <Group key={item} gap={4} role="listitem">
          {LINES.includes(item) ? (
            <Box w={16} h={4} bg={TRIP_COLORS[item]} />
          ) : (
            <Box
              w={10}
              h={10}
              bg={item === 'lastKnown' ? 'white' : TRIP_COLORS[item]}
              style={{
                borderRadius: '50%',
                border: item === 'lastKnown' ? `2px solid ${TRIP_COLORS.lastKnown}` : undefined,
              }}
            />
          )}
          <Text size="xs">{item === 'track' && trackLabel !== undefined ? trackLabel : LABELS[item]}</Text>
        </Group>
      ))}
    </Group>
  );
}
