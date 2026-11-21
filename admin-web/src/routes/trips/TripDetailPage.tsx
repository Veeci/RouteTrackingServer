import { Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import type { Trip } from '../../api/types';
import { AnchorLink } from '../../components/AnchorLink';
import { ErrorAlert } from '../../components/ErrorAlert';
import { type Fact, Facts } from '../../components/Facts';
import { formatDateTime, shortId } from '../../components/format';
import { isFinalStatus } from '../../trip/status';
import { CancelTrip } from './CancelTrip';
import { LiveTripView } from './LiveTripView';
import { tripQuery } from './queries';
import { ReplayTripView } from './ReplayTripView';
import { StatusBadge } from './StatusBadge';
import { formatDistance, formatDuration } from './tripFormat';

const routeApi = getRouteApi('/app/trips/$tripId');

function place({ lat, lng }: { lat: number; lng: number }): string {
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}

function tripFacts(trip: Trip): Fact[] {
  return [
    { label: 'Created', value: formatDateTime(trip.createdAt) },
    { label: 'Last change', value: formatDateTime(trip.updatedAt) },
    {
      label: 'Driver',
      value:
        trip.driverId === undefined ? (
          'None yet'
        ) : (
          <AnchorLink to="/drivers/$driverId" params={{ driverId: trip.driverId }} ff="monospace" title={trip.driverId}>
            {shortId(trip.driverId)}
          </AnchorLink>
        ),
    },
    {
      label: 'Guest',
      value: (
        <AnchorLink to="/trips" search={{ guestId: trip.guestId }} ff="monospace" title={`Trips of ${trip.guestId}`}>
          {shortId(trip.guestId)}
        </AnchorLink>
      ),
    },
    { label: 'Pickup', value: place(trip.pickup) },
    { label: 'Destination', value: place(trip.destination) },
    {
      label: 'Planned route',
      value:
        trip.route === undefined
          ? 'None'
          : `${formatDistance(trip.route.distanceM)}, about ${formatDuration(trip.route.durationS)} without traffic`,
    },
  ];
}

/**
 * One trip. Live mode while it has not ended, over the staff trip socket. Replay mode once it is COMPLETED or
 * CANCELLED, from the recorded track. The switch follows the trip query: the live view reads the trip again on each
 * stage change, so the final one swaps the views.
 */
export function TripDetailPage() {
  const { tripId } = routeApi.useParams();
  const trip = useQuery(tripQuery(tripId));

  if (trip.isPending) {
    return (
      <Group gap="sm">
        <Loader size="sm" />
        <Text>Loading the trip</Text>
      </Group>
    );
  }
  if (trip.data === undefined) {
    return (
      <Stack>
        <Title order={2}>Trip {shortId(tripId)}</Title>
        <ErrorAlert error={trip.error} />
      </Stack>
    );
  }
  const data = trip.data;
  const live = !isFinalStatus(data.status);
  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <Stack gap={4}>
          <Group gap="sm">
            <Title order={2}>Trip {shortId(data.id)}</Title>
            <StatusBadge status={data.status} />
          </Group>
          <Text size="xs" c="dimmed" ff="monospace">
            {data.id}
          </Text>
        </Stack>
        <CancelTrip trip={data} />
      </Group>
      {trip.isError && <ErrorAlert error={trip.error} />}
      <Facts facts={tripFacts(data)} cols={4} />
      <Title order={3}>{live ? 'Live' : 'Replay'}</Title>
      {/* The key starts a fresh view per trip, so a socket never outlives the trip it watches. */}
      {live ? <LiveTripView key={data.id} trip={data} /> : <ReplayTripView key={data.id} trip={data} />}
    </Stack>
  );
}
