import { Badge, CloseButton, Group, Loader, Paper, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { AnchorLink } from '../../components/AnchorLink';
import { ErrorAlert } from '../../components/ErrorAlert';
import { type Fact, Facts } from '../../components/Facts';
import { formatDateTime, formatDecimal, NONE, shortId } from '../../components/format';
import { PresenceBadge } from '../../components/PresenceBadge';
import type { DriverMarker } from '../../fleet/messages';
import { type FleetStore, useFleet } from '../../fleet/store';
import { driverQuery, isEnabled } from '../drivers/queries';

export interface DriverPanelProps {
  store: FleetStore;
  driverId: string;
  onClose: () => void;
}

export function formatPosition(lat: number, lng: number): string {
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}

function liveFacts(marker: DriverMarker): Fact[] {
  return [
    { label: 'Position', value: formatPosition(marker.lat, marker.lng) },
    { label: 'Heading', value: formatDecimal(marker.bearingDeg, '°') },
    { label: 'Trip stage', value: marker.tripStatus ?? NONE },
    { label: 'Battery', value: marker.batteryPct === undefined ? NONE : `${String(marker.batteryPct)} %` },
    { label: 'Last seen', value: formatDateTime(marker.lastSeenAt) },
  ];
}

/**
 * The selected driver: the name and account from the 12c driver detail query (asked again every 10 s), and the live
 * position from the marker store, which changes with every delta. The marker selector re-renders the panel only when
 * this driver's marker is replaced.
 */
export function DriverPanel({ store, driverId, onClose }: DriverPanelProps) {
  const marker = useFleet(store, (state) => state.markers.get(driverId));
  const at = useFleet(store, (state) => state.at);
  const detail = useQuery(driverQuery(driverId));
  const name =
    detail.data?.driver.displayName ?? (detail.isPending ? `Driver ${shortId(driverId)}` : 'Driver without a name');

  return (
    <Paper component="aside" aria-label="Selected driver" withBorder p="md" w={{ base: '100%', md: 320 }}>
      <Stack gap="sm">
        <Group justify="space-between" wrap="nowrap" align="flex-start">
          <Title order={3} size="h4">
            {name}
          </Title>
          <CloseButton aria-label="Close the driver panel" onClick={onClose} />
        </Group>
        <Group gap="xs">
          {marker !== undefined && (
            // The marker carries one time for its position and status, the newer of the two: name it as such.
            <PresenceBadge online={marker.online} subject="last update" dataAt={marker.lastSeenAt} now={at} />
          )}
          {detail.data !== undefined && (
            <Badge color={isEnabled(detail.data) ? 'blue' : 'red'} variant="light">
              {isEnabled(detail.data) ? 'Enabled' : 'Disabled'}
            </Badge>
          )}
          {detail.isPending && <Loader size="xs" aria-label="Loading the driver" />}
        </Group>
        {marker === undefined ? (
          <Stack gap={4}>
            <Text size="sm">Not on the map in this area with these filters.</Text>
            {detail.data?.driver.position !== undefined && (
              <Text size="sm" c="dimmed">
                Last known position {formatPosition(detail.data.driver.position.lat, detail.data.driver.position.lng)}{' '}
                at {formatDateTime(detail.data.driver.position.at)}
              </Text>
            )}
          </Stack>
        ) : (
          <Facts facts={liveFacts(marker)} cols={1} />
        )}
        {detail.isError && <ErrorAlert error={detail.error} />}
        <AnchorLink to="/drivers/$driverId" params={{ driverId }} size="sm">
          Open the driver page
        </AnchorLink>
      </Stack>
    </Paper>
  );
}
