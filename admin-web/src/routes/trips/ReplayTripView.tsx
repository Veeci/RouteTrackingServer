import { Alert, Box, Button, Group, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';
import { api } from '../../api/client';
import { downloadCsv } from '../../api/download';
import { isApiError, unwrap } from '../../api/errors';
import type { LegSummary, TimelineEntry, TrackLeg, TrackPoint, Trip, TripSummary } from '../../api/types';
import { ADMIN_ONLY } from '../../auth/roles';
import { CsvButton } from '../../components/CsvButton';
import { ErrorAlert } from '../../components/ErrorAlert';
import { type Fact, Facts } from '../../components/Facts';
import { formatDateTime, formatInteger, formatTime, NONE } from '../../components/format';
import { RequireRole } from '../../components/RequireRole';
import { TripMap } from '../../map/TripMap';
import type { TripMapData } from '../../map/tripLayers';
import type { Position } from '../../trip/polyline';
import { decodeOrNothing } from '../../trip/route';
import { STATUS_LABELS } from '../../trip/status';
import { MapLegend } from './MapLegend';
import { summaryQuery, trackQuery, tripKeys } from './queries';
import { formatDistance, formatDuration, formatSpeed } from './tripFormat';

const positionsOf = (points: readonly TrackPoint[] | undefined): Position[] | undefined =>
  points?.map(({ lat, lng }) => [lng, lat]);

/**
 * Replay mode, for an ended trip: the recorded legs from `/track` on the map, the timeline, the summary and the
 * exports. Nothing here changes once the trip has ended, so nothing polls.
 */
export function ReplayTripView({ trip }: { trip: Trip }) {
  const ride = useQuery(trackQuery(trip.id, 'ride'));
  const approach = useQuery(trackQuery(trip.id, 'approach'));
  const route = useMemo(() => decodeOrNothing(trip.route?.polyline), [trip.route?.polyline]);
  const mapData = useMemo<TripMapData>(
    () => ({
      pickup: trip.pickup,
      destination: trip.destination,
      route,
      approach: positionsOf(approach.data?.points),
      track: positionsOf(ride.data?.points),
    }),
    [trip.pickup, trip.destination, route, approach.data, ride.data],
  );
  const expired = ride.data?.pointsExpired === true || approach.data?.pointsExpired === true;

  return (
    <Stack gap="md">
      {expired && (
        <Alert color="yellow" title="GPS points deleted">
          <Text size="sm">
            The trip ended more than 90 days ago, so its GPS points are gone and the map shows no track. The timeline
            and the summary stay. The exports answer 410.
          </Text>
        </Alert>
      )}
      {ride.isError && <ErrorAlert error={ride.error} />}
      {approach.isError && <ErrorAlert error={approach.error} />}
      <Stack gap="xs">
        <Box h={480}>
          <TripMap data={mapData} />
        </Box>
        <Group justify="space-between">
          <MapLegend items={['pickup', 'destination', 'route', 'approach', 'track']} />
          <Text size="xs" c="dimmed">
            {ride.isPending || approach.isPending
              ? 'Loading the track'
              : `${formatInteger(approach.data?.points.length)} points to the pickup, ${formatInteger(ride.data?.points.length)} in the ride`}
          </Text>
        </Group>
      </Stack>
      <Exports tripId={trip.id} />
      <SummaryPanel trip={trip} />
      <Timeline entries={ride.data?.timeline ?? approach.data?.timeline} pending={ride.isPending} />
    </Stack>
  );
}

const LEGS: readonly { leg: TrackLeg; label: string }[] = [
  { leg: 'ride', label: 'Ride' },
  { leg: 'approach', label: 'Drive to the pickup' },
];

/** GPX and GeoJSON of each leg, fetched with the token and saved through a blob URL, as 12c's CSV export does. */
function Exports({ tripId }: { tripId: string }) {
  return (
    <Stack gap="xs" component="section" aria-label="Exports">
      <Title order={4}>Exports</Title>
      {LEGS.map(({ leg, label }) => (
        <Group key={leg} gap="sm">
          <Text size="sm" w={150}>
            {label}
          </Text>
          <CsvButton
            label={`${label}: GPX`}
            download={() =>
              downloadCsv(
                api.GET('/api/v1/trips/{id}/export.gpx', {
                  params: { path: { id: tripId }, query: { leg } },
                  parseAs: 'blob',
                }),
                `trip-${tripId}-${leg}.gpx`,
              )
            }
          />
          <CsvButton
            label={`${label}: GeoJSON`}
            download={() =>
              downloadCsv(
                api.GET('/api/v1/trips/{id}/export.geojson', {
                  params: { path: { id: tripId }, query: { leg } },
                  parseAs: 'blob',
                }),
                `trip-${tripId}-${leg}.geojson`,
              )
            }
          />
        </Group>
      ))}
    </Stack>
  );
}

function legFacts(prefix: string, leg: LegSummary): Fact[] {
  return [
    { label: `${prefix} distance`, value: formatDistance(leg.distanceM) },
    { label: `${prefix} duration`, value: formatDuration(leg.durationS) },
    { label: `${prefix} moving time`, value: formatDuration(leg.movingTimeS) },
    { label: `${prefix} average speed`, value: formatSpeed(leg.avgSpeedMps) },
    { label: `${prefix} top speed`, value: formatSpeed(leg.maxSpeedMps) },
    { label: `${prefix} points`, value: formatInteger(leg.pointCount) },
  ];
}

export function summaryFacts(summary: TripSummary): Fact[] {
  const rejected = Object.entries(summary.rejected)
    .map(([reason, count]) => `${reason} ${String(count)}`)
    .join(', ');
  return [
    ...legFacts('Ride', summary.ride),
    ...(summary.approach === undefined ? [] : legFacts('Approach', summary.approach)),
    {
      label: 'Planned distance',
      value: summary.plannedDistanceM === undefined ? NONE : formatDistance(summary.plannedDistanceM),
    },
    { label: 'Left the route', value: formatInteger(summary.offRouteCount) },
    { label: 'Routes planned again', value: formatInteger(summary.rerouteCount) },
    { label: 'Fixes rejected', value: rejected === '' ? NONE : rejected },
    { label: 'Computed', value: formatDateTime(summary.computedAt) },
  ];
}

/**
 * The summary of a completed trip. A cancelled trip has none, so it asks for nothing. 404 SUMMARY_NOT_READY shows
 * "Not computed yet" with "Check again", and "Compute now" for an admin, as 12c's session health does.
 */
function SummaryPanel({ trip }: { trip: Trip }) {
  const completed = trip.status === 'COMPLETED';
  const queryClient = useQueryClient();
  const summary = useQuery({ ...summaryQuery(trip.id), enabled: completed });
  const compute = useMutation({
    mutationFn: () => api.POST('/api/v1/admin/trips/{id}/summary', { params: { path: { id: trip.id } } }).then(unwrap),
    onSuccess: (computed) => {
      queryClient.setQueryData(tripKeys.summary(trip.id), computed);
    },
  });

  const computeButton = (label: string) => (
    <RequireRole roles={ADMIN_ONLY}>
      <Button
        size="xs"
        variant="default"
        loading={compute.isPending}
        onClick={() => {
          compute.mutate();
        }}
      >
        {label}
      </Button>
    </RequireRole>
  );

  let body;
  if (!completed) {
    body = (
      <Text size="sm">
        A {STATUS_LABELS[trip.status].toLowerCase()} trip has no summary. Only a completed trip gets one.
      </Text>
    );
  } else if (summary.isPending) {
    body = <Loader size="sm" aria-label="Loading the summary" />;
  } else if (summary.data !== undefined) {
    body = (
      <Stack gap="xs">
        {summary.data.approximate && (
          <Text size="sm" c="dimmed">
            Approximate: the trip was stored before stage times existed, so the ride covers its whole life.
          </Text>
        )}
        <Facts facts={summaryFacts(summary.data)} cols={3} />
        <Group>{computeButton('Compute again')}</Group>
      </Stack>
    );
  } else if (isApiError(summary.error, 404) && summary.error.code === 'SUMMARY_NOT_READY') {
    body = (
      <Group>
        <Text size="sm">Not computed yet. The server computes it within a second of the trip completing.</Text>
        <Button
          size="xs"
          variant="default"
          loading={summary.isFetching}
          onClick={() => {
            void summary.refetch();
          }}
        >
          Check again
        </Button>
        {computeButton('Compute now')}
      </Group>
    );
  } else {
    body = <ErrorAlert error={summary.error} />;
  }

  return (
    <Stack gap="xs" component="section" aria-label="Summary">
      <Title order={4}>Summary</Title>
      {body}
      {compute.isError && <ErrorAlert error={compute.error} />}
    </Stack>
  );
}

const ROUTE_REASONS: Record<string, string> = {
  initial: 'first plan',
  off_route: 'planned again after the driver left the route',
  status_changed: 'the ride started',
};

/** One timeline entry in words. A kind a later server adds shows as its name. */
export function timelineText(entry: TimelineEntry): string {
  switch (entry.kind) {
    case 'status_changed':
      return `Stage: ${entry.status === undefined ? 'unknown' : STATUS_LABELS[entry.status]}`;
    case 'driver_arriving':
      return `Driver arriving${entry.remainingM === undefined ? '' : `, ${formatDistance(entry.remainingM)} away`}`;
    case 'driver_low_battery':
      return `Driver's battery low${entry.levelPercent === undefined ? '' : `, ${String(entry.levelPercent)} %`}`;
    case 'driver_stale':
      return `Driver's phone not heard${entry.lastSeenAt === undefined ? '' : ` since ${formatTime(entry.lastSeenAt)}`}`;
    case 'driver_back':
      return "Driver's phone heard again";
    case 'off_route':
      return 'Driver left the route';
    case 'route_updated': {
      const kind = entry.routeKind === 'APPROACH' ? 'Route to the pickup' : 'Route to the destination';
      const version = entry.routeVersion === undefined ? '' : ` version ${String(entry.routeVersion)}`;
      const reason = entry.reason === undefined ? '' : `, ${ROUTE_REASONS[entry.reason] ?? entry.reason}`;
      const distance = entry.distanceM === undefined ? '' : `, ${formatDistance(entry.distanceM)}`;
      return `${kind}${version}${reason}${distance}`;
    }
    default:
      return String(entry.kind);
  }
}

function Timeline({ entries, pending }: { entries: TimelineEntry[] | undefined; pending: boolean }) {
  let body;
  if (pending) body = <Loader size="sm" aria-label="Loading the timeline" />;
  else if (entries === undefined || entries.length === 0) body = <Text size="sm">Nothing recorded.</Text>;
  else {
    body = (
      <Table aria-label="Timeline" verticalSpacing={4}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th w={200}>Time</Table.Th>
            <Table.Th>What happened</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {entries.map((entry, index) => (
            <Table.Tr key={`${entry.at}-${String(index)}`}>
              <Table.Td>
                <time dateTime={entry.at}>{formatDateTime(entry.at)}</time>
              </Table.Td>
              <Table.Td>{timelineText(entry)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    );
  }
  return (
    <Stack gap="xs" component="section" aria-label="Timeline of the trip">
      <Title order={4}>Timeline</Title>
      {body}
    </Stack>
  );
}
