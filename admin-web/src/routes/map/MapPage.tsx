import { Alert, Badge, Box, Flex, Group, NativeSelect, Stack, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { formatInteger, formatTime } from '../../components/format';
import { type ConnectionState, FleetClient } from '../../fleet/client';
import type { Bbox, FleetFilters } from '../../fleet/messages';
import { createFleetStore, driversInView, type FleetStore, useFleet } from '../../fleet/store';
import { ticketFetcher } from '../../socket/ticket';
import { FleetMap, type MapMove } from '../../map/FleetMap';
import { DRIVER_COLORS } from '../../map/layers';
import { viewOf } from '../../map/view';
import { flagValue, ONLINE_OPTIONS, toFlag, TRIP_OPTIONS } from '../flags';
import { DriverPanel } from './DriverPanel';

const routeApi = getRouteApi('/app/map');

/**
 * The whole fleet on a map, live from the fleet socket. The URL holds the filters, the view and the selected driver.
 * The socket client and the marker store live as long as the page, outside React's render cycle.
 */
export function MapPage() {
  const search = routeApi.useSearch();
  const navigate = routeApi.useNavigate();
  const queryClient = useQueryClient();
  const [store] = useState(createFleetStore);
  const [client] = useState(
    () =>
      new FleetClient({
        getTicket: ticketFetcher(queryClient),
        onMessage: (message) => {
          store.getState().apply(message);
        },
        onState: (state) => {
          store.getState().setConnection(state);
        },
      }),
  );

  useEffect(() => {
    client.start();
    return () => {
      client.stop();
    };
  }, [client]);

  const filters = useMemo<FleetFilters>(
    () => ({ online: search.online, onTrip: search.onTrip }),
    [search.online, search.onTrip],
  );
  /** The visible area the map last reported. A filter change subscribes again with it. */
  const bboxRef = useRef<Bbox | null>(null);
  useEffect(() => {
    if (bboxRef.current !== null) client.subscribe({ bbox: bboxRef.current, filters });
  }, [client, filters]);

  const onMove = ({ view, bbox }: MapMove) => {
    bboxRef.current = bbox;
    client.subscribe({ bbox, filters });
    void navigate({ search: (previous) => ({ ...previous, ...view }), replace: true });
  };

  const selectDriver = (driverId: string | undefined) => {
    void navigate({ search: (previous) => ({ ...previous, driver: driverId }) });
  };

  return (
    <Stack h="calc(100dvh - var(--app-shell-header-height, 0px) - 2 * var(--mantine-spacing-md))" mih={480} gap="sm">
      <Group justify="space-between" align="flex-end">
        <Title order={2}>Live map</Title>
        <Group gap="sm" align="flex-end" role="search" aria-label="Filter the map">
          <NativeSelect
            label="Online"
            data={ONLINE_OPTIONS}
            value={flagValue(search.online)}
            onChange={(event) => {
              const online = toFlag(event.currentTarget.value);
              void navigate({ search: (previous) => ({ ...previous, online }) });
            }}
          />
          <NativeSelect
            label="Trip"
            data={TRIP_OPTIONS}
            value={flagValue(search.onTrip)}
            onChange={(event) => {
              const onTrip = toFlag(event.currentTarget.value);
              void navigate({ search: (previous) => ({ ...previous, onTrip }) });
            }}
          />
        </Group>
      </Group>
      <StatusLine store={store} />
      <Flex flex={1} mih={0} gap="md" direction={{ base: 'column', md: 'row' }}>
        <Box flex={1} miw={0} mih={320}>
          <FleetMap
            store={store}
            view={viewOf(search)}
            selectedDriverId={search.driver}
            onMove={onMove}
            onSelectDriver={selectDriver}
          />
        </Box>
        {search.driver !== undefined && (
          <DriverPanel
            key={search.driver}
            store={store}
            driverId={search.driver}
            onClose={() => {
              selectDriver(undefined);
            }}
          />
        )}
      </Flex>
    </Stack>
  );
}

function connectionLabel(state: ConnectionState): { text: string; color: string } {
  switch (state.status) {
    case 'connecting':
      return { text: 'Connecting', color: 'blue' };
    case 'live':
      return { text: 'Live', color: 'green' };
    case 'waiting':
      return {
        text: `Connection lost, trying again in ${String(Math.ceil(state.retryInMs / 1_000))} s`,
        color: 'yellow',
      };
    case 'paused':
      return { text: 'Paused while the tab is hidden', color: 'gray' };
    case 'forbidden':
      return { text: 'No permission', color: 'red' };
    case 'stopped':
      return { text: 'Stopped', color: 'gray' };
  }
}

/**
 * The connection, the drivers in view and the time of the last update. It re-renders on each message, which is
 * one small component every 2 s, not the map.
 */
function StatusLine({ store }: { store: FleetStore }) {
  const connection = useFleet(store, (state) => state.connection);
  const count = useFleet(store, driversInView);
  const clustered = useFleet(store, (state) => state.clusters !== null);
  const at = useFleet(store, (state) => state.at);
  const serverError = useFleet(store, (state) => state.serverError);
  const label = connectionLabel(connection);

  return (
    <Stack gap={4}>
      <Group component="section" aria-label="Map status" gap="md" justify="space-between">
        <Group gap="md">
          <Badge color={label.color} variant="light">
            {label.text}
          </Badge>
          <Text size="sm">
            {count === 1 ? '1 driver in view' : `${formatInteger(count)} drivers in view`}
            {clustered && ', grouped by area'}
          </Text>
          <Text size="sm" c="dimmed">
            Last update {at === undefined ? 'none yet' : <time dateTime={at}>{formatTime(at)}</time>}
          </Text>
        </Group>
        <Legend />
      </Group>
      {connection.status === 'forbidden' && (
        <Alert color="red" title="No permission">
          <Text size="sm">Your role may not watch the fleet. The live map is for operators and admins.</Text>
        </Alert>
      )}
      {serverError !== undefined && (
        <Text size="sm" c="red">
          The server refused the map area: {serverError.message}
        </Text>
      )}
    </Stack>
  );
}

const LEGEND = [
  { label: 'Online', color: DRIVER_COLORS.online },
  { label: 'On a trip', color: DRIVER_COLORS.onTrip },
  { label: 'Offline', color: DRIVER_COLORS.offline },
];

function Legend() {
  return (
    <Group gap="sm" aria-label="Colours">
      {LEGEND.map(({ label, color }) => (
        <Group key={label} gap={4}>
          <Box w={10} h={10} bg={color} style={{ borderRadius: '50%' }} />
          <Text size="xs">{label}</Text>
        </Group>
      ))}
    </Group>
  );
}
