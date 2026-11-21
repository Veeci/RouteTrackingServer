import { Alert, Badge, Box, Flex, Group, Stack, Text, Title } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import type { Trip } from '../../api/types';
import { type Fact, Facts } from '../../components/Facts';
import { formatTime, NONE } from '../../components/format';
import { TripMap } from '../../map/TripMap';
import type { TripMapData } from '../../map/tripLayers';
import { ticketFetcher } from '../../socket/ticket';
import { TripClient, type TripConnectionState } from '../../trip/client';
import type { TripEvent, TripProgress } from '../../trip/messages';
import { decodeOrNothing } from '../../trip/route';
import { STATUS_LABELS } from '../../trip/status';
import { createTripStore, type LivePosition, type TripStore, useTripLive } from '../../trip/store';
import { driverQuery } from '../drivers/queries';
import { MapLegend } from './MapLegend';
import { tripKeys } from './queries';
import { formatDistance, formatDuration, formatSpeed } from './tripFormat';

/**
 * Live mode, for a trip that has not ended: the staff trip socket feeds a store, and the map and the panel read it.
 * The socket client and the store live as long as this view. A status change on the socket reads the trip again, so
 * the page's header follows it, and the final one switches the page to replay mode.
 */
export function LiveTripView({ trip }: { trip: Trip }) {
  const queryClient = useQueryClient();
  const [store] = useState(createTripStore);
  const [client] = useState(
    () =>
      new TripClient({
        tripId: trip.id,
        getTicket: ticketFetcher(queryClient),
        onMessage: (message) => {
          store.getState().apply(message);
          if (message.type === 'trip_event' && message.event === 'status_changed') {
            void queryClient.invalidateQueries({ queryKey: tripKeys.detail(trip.id) });
            void queryClient.invalidateQueries({ queryKey: tripKeys.lists });
          }
        },
        onState: (state) => {
          store.getState().setConnection(state);
          // `subscribed` for a trip that ended before the socket opened ends the stream without a trip_event.
          if (state.status === 'ended') void queryClient.invalidateQueries({ queryKey: tripKeys.detail(trip.id) });
        },
      }),
  );

  useEffect(() => {
    client.start();
    return () => {
      client.stop();
    };
  }, [client]);

  useSeedFromDriver(store, trip.driverId);

  const liveRoute = useTripLive(store, (state) => state.route);
  const position = useTripLive(store, (state) => state.position);
  const trail = useTripLive(store, (state) => state.trail);
  const tripRoute = useMemo(() => decodeOrNothing(trip.route?.polyline), [trip.route?.polyline]);
  const mapData = useMemo<TripMapData>(
    () => ({
      pickup: trip.pickup,
      destination: trip.destination,
      route: liveRoute?.line ?? tripRoute,
      track: trail,
      driver: position,
    }),
    [trip.pickup, trip.destination, liveRoute, tripRoute, trail, position],
  );

  return (
    <Stack gap="sm">
      <ConnectionLine store={store} />
      <Flex gap="md" direction={{ base: 'column', md: 'row' }}>
        <Stack flex={1} miw={0} gap="xs">
          <Box h={480}>
            <TripMap data={mapData} />
          </Box>
          <MapLegend
            items={['pickup', 'destination', 'route', 'track', 'driver', 'lastKnown']}
            trackLabel="Driven since this page opened"
          />
        </Stack>
        <Stack w={{ base: '100%', md: 340 }} gap="md">
          <LivePanel store={store} />
          <EventList store={store} />
        </Stack>
      </Flex>
    </Stack>
  );
}

/**
 * C3: until the first `driver_position`, the driver shows at the position from their detail, labelled "last known".
 * `subscribed` carries no position. The detail is read once, not every 10 s as on the driver page.
 */
function useSeedFromDriver(store: TripStore, driverId: string | undefined) {
  const driver = useQuery({
    ...driverQuery(driverId ?? ''),
    enabled: driverId !== undefined,
    refetchInterval: false,
  });
  const seed = driver.data?.driver.position;
  useEffect(() => {
    if (seed !== undefined) store.getState().seedPosition({ lat: seed.lat, lng: seed.lng, at: seed.at });
  }, [store, seed]);
}

export function connectionText(state: TripConnectionState): { text: string; color: string } {
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
    case 'ended':
      return { text: 'Trip ended', color: 'gray' };
  }
}

function ConnectionLine({ store }: { store: TripStore }) {
  const connection = useTripLive(store, (state) => state.connection);
  const serverError = useTripLive(store, (state) => state.serverError);
  const label = connectionText(connection);
  return (
    <Stack gap={4}>
      <Group component="section" aria-label="Live status" gap="md">
        <Badge color={label.color} variant="light">
          {label.text}
        </Badge>
        {connection.status === 'ended' && <Text size="sm">The trip has ended. Loading the replay.</Text>}
      </Group>
      {connection.status === 'forbidden' && (
        <Alert color="red" title="No permission">
          <Text size="sm">Your role may not watch trips live. The live view is for operators and admins.</Text>
        </Alert>
      )}
      {serverError !== undefined && (
        <Text size="sm" c="red">
          The server refused to watch this trip: {serverError.message}
        </Text>
      )}
    </Stack>
  );
}

function positionText(position: LivePosition | undefined): string {
  if (position === undefined) return 'Not heard yet';
  const at = position.at === undefined ? '' : ` at ${formatTime(position.at)}`;
  return position.lastKnown ? `Last known${at}, before this page opened` : `Live${at}`;
}

function progressText(progress: TripProgress | undefined): string {
  if (progress === undefined) return NONE;
  const target = progress.target === 'PICKUP' ? 'the pickup' : 'the destination';
  const how = progress.basis === 'ROUTE' ? 'along the route' : 'in a straight line';
  return `${formatDistance(progress.remainingM)} to ${target} ${how}, about ${formatDuration(progress.etaS)}`;
}

/** The trip's live values. It re-renders on each message, which is one small panel every few seconds. */
function LivePanel({ store }: { store: TripStore }) {
  const status = useTripLive(store, (state) => state.trip?.status);
  const position = useTripLive(store, (state) => state.position);
  const progress = useTripLive(store, (state) => state.progress);
  const route = useTripLive(store, (state) => state.route);
  const facts: Fact[] = [
    { label: 'Stage now', value: status === undefined ? 'Waiting for the server' : STATUS_LABELS[status] },
    { label: 'Driver position', value: positionText(position) },
    {
      label: 'Speed',
      value: position?.speedMps === undefined || position.lastKnown ? NONE : formatSpeed(position.speedMps),
    },
    { label: 'Progress', value: progressText(progress) },
    {
      label: 'Route followed',
      value:
        route === undefined
          ? NONE
          : `${route.kind === 'APPROACH' ? 'To the pickup' : 'To the destination'}, version ${String(route.routeVersion)}, ${formatDistance(route.distanceM)}`,
    },
  ];
  return (
    <Stack gap="xs" component="section" aria-label="Live trip">
      <Title order={4}>Now</Title>
      <Facts facts={facts} cols={1} />
    </Stack>
  );
}

const EVENT_TEXT: Record<string, (event: TripEvent) => string> = {
  status_changed: (event) =>
    `Stage: ${STATUS_LABELS[event.status]}${event.cancelledBy === undefined ? '' : `, by the ${event.cancelledBy.toLowerCase()}`}`,
  driver_arriving: (event) =>
    `Driver arriving${event.remainingM === undefined ? '' : `, ${formatDistance(event.remainingM)} away`}`,
  driver_low_battery: (event) =>
    `Driver's battery low${event.levelPercent === undefined ? '' : `, ${String(event.levelPercent)} %`}`,
  driver_stale: () => "Driver's phone not heard for 60 s",
  driver_back: () => "Driver's phone heard again",
  off_route: () => 'Driver left the route',
};

/** One event in words. A kind a later server adds shows as its name. */
export function eventText(event: TripEvent): string {
  return EVENT_TEXT[event.event]?.(event) ?? event.event;
}

function EventList({ store }: { store: TripStore }) {
  const events = useTripLive(store, (state) => state.events);
  return (
    <Stack gap="xs" component="section" aria-label="Events">
      <Title order={4}>Events since this page opened</Title>
      {events.length === 0 ? (
        <Text size="sm" c="dimmed">
          None yet
        </Text>
      ) : (
        <Stack gap={2} component="ol" m={0} p={0} style={{ listStyle: 'none' }}>
          {events.map((event, index) => (
            <Text key={`${event.at}-${String(index)}`} size="sm" component="li">
              <time dateTime={event.at}>{formatTime(event.at)}</time> {eventText(event)}
            </Text>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
