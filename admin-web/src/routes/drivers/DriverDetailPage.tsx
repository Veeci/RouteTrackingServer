import { Badge, Button, Group, Loader, Paper, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { Fragment, type ReactNode, useState } from 'react';
import type { DriverDetail } from '../../api/types';
import { ADMIN_ONLY } from '../../auth/roles';
import { AnchorLink } from '../../components/AnchorLink';
import { ErrorAlert } from '../../components/ErrorAlert';
import { type Fact, Facts } from '../../components/Facts';
import {
  formatDateTime,
  formatDecimal,
  formatInteger,
  formatRatio,
  formatTime,
  NONE,
  shortId,
} from '../../components/format';
import { RequireRole } from '../../components/RequireRole';
import { AccountActions } from './AccountActions';
import { CommandsPanel } from './CommandsPanel';
import { driverQuery, isEnabled } from './queries';
import { SessionHealthPanel } from './SessionHealthPanel';

const routeApi = getRouteApi('/app/drivers/$driverId');

/** One driver: account, position, device, trip, health, recent sessions and trips, and the ADMIN actions. */
export function DriverDetailPage() {
  const { driverId } = routeApi.useParams();
  const detail = useQuery(driverQuery(driverId));

  return (
    <Stack>
      <AnchorLink to="/drivers" size="sm">
        All drivers
      </AnchorLink>
      {detail.isPending ? (
        <Group gap="sm">
          <Loader size="sm" />
          <Text>Loading the driver</Text>
        </Group>
      ) : detail.data === undefined ? (
        <>
          <Title order={2}>Driver</Title>
          <ErrorAlert error={detail.error} />
        </>
      ) : (
        <>
          {detail.isError && (
            <ErrorAlert error={detail.error}>
              <Text size="sm">The page shows the driver as of {formatTime(detail.data.at)}.</Text>
            </ErrorAlert>
          )}
          <DriverView detail={detail.data} />
        </>
      )}
    </Stack>
  );
}

function DriverView({ detail }: { detail: DriverDetail }) {
  const { driver } = detail;
  return (
    <>
      <Group justify="space-between" align="flex-start">
        <Stack gap={4}>
          <Title order={2}>{driver.displayName ?? 'Driver without a name'}</Title>
          <Group gap="xs">
            <Badge color={driver.online ? 'green' : 'gray'} variant="light">
              {driver.online ? 'Online' : 'Offline'}
            </Badge>
            <Badge color={isEnabled(detail) ? 'blue' : 'red'} variant="light">
              {isEnabled(detail) ? 'Enabled' : 'Disabled'}
            </Badge>
            <Text size="xs" c="dimmed" ff="monospace">
              {driver.driverId}
            </Text>
          </Group>
        </Stack>
        <RequireRole roles={ADMIN_ONLY}>
          <AccountActions detail={detail} />
        </RequireRole>
      </Group>
      <Text size="sm" c="dimmed">
        As of <time dateTime={detail.at}>{formatTime(detail.at)}</time>. The page asks again every 10 s.
      </Text>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Card title="Account">
          <Facts facts={accountFacts(detail)} />
        </Card>
        <Card title="Position">
          <Facts facts={positionFacts(detail)} />
        </Card>
        <Card title="Device">
          <Facts facts={deviceFacts(detail)} />
        </Card>
        <Card title="Trip">
          <Facts facts={tripFacts(detail)} />
        </Card>
      </SimpleGrid>
      <Card title="Latest session health">
        {detail.health === undefined ? (
          <Text size="sm">No session of this driver has its health computed yet.</Text>
        ) : (
          <Facts facts={latestHealthFacts(detail.health)} cols={3} />
        )}
      </Card>
      <RecentSessions detail={detail} />
      <RecentTrips detail={detail} />
      <RequireRole roles={ADMIN_ONLY}>
        <CommandsPanel driverId={driver.driverId} />
      </RequireRole>
    </>
  );
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Paper withBorder p="md" radius="md" component="section" aria-label={title}>
      <Title order={3} size="h4" mb="xs">
        {title}
      </Title>
      {children}
    </Paper>
  );
}

function accountFacts(detail: DriverDetail): Fact[] {
  if (isEnabled(detail)) return [{ label: 'Status', value: 'Enabled' }];
  return [
    { label: 'Status', value: 'Disabled' },
    { label: 'Disabled at', value: formatDateTime(detail.disabledAt) },
    { label: 'Reason', value: detail.disabledReason ?? NONE },
  ];
}

function positionFacts({ driver }: DriverDetail): Fact[] {
  const { position } = driver;
  if (position === undefined) return [{ label: 'Position', value: 'Never sent' }];
  return [
    { label: 'Latitude and longitude', value: `${position.lat.toFixed(5)}, ${position.lng.toFixed(5)}` },
    { label: 'Recorded', value: formatDateTime(position.at) },
    { label: 'Speed', value: formatDecimal(position.speedMps, 'm/s') },
    { label: 'Bearing', value: formatDecimal(position.bearingDeg, '°') },
  ];
}

function deviceFacts({ driver }: DriverDetail): Fact[] {
  const { device } = driver;
  const facts: Fact[] = [
    { label: 'Last seen', value: driver.lastSeenAt === undefined ? 'Never' : formatDateTime(driver.lastSeenAt) },
  ];
  if (device === undefined) return [...facts, { label: 'Battery', value: 'No status yet' }];
  return [
    ...facts,
    { label: 'Battery', value: `${String(device.batteryPct)} %${device.isCharging ? ', charging' : ''}` },
    { label: 'Motion', value: device.motion ?? NONE },
    { label: 'Status sent', value: formatDateTime(device.at) },
  ];
}

function tripFacts({ driver }: DriverDetail): Fact[] {
  const { trip } = driver;
  if (trip === undefined) return [{ label: 'Stage', value: 'No trip yet' }];
  return [
    { label: 'Stage', value: trip.status },
    { label: 'Since', value: formatDateTime(trip.at) },
    { label: 'Current trip', value: trip.currentTripId ?? 'None, the trip has ended' },
  ];
}

function latestHealthFacts(health: NonNullable<DriverDetail['health']>): Fact[] {
  return [
    { label: 'Session', value: shortId(health.sessionId) },
    { label: 'Computed', value: formatDateTime(health.computedAt) },
    { label: 'Accepted fixes', value: formatInteger(health.fixCount) },
    { label: 'Accepted share', value: formatRatio(health.acceptedRatio) },
    { label: 'Interval p50', value: formatDecimal(health.intervalP50S, 's') },
    { label: 'Accuracy p50', value: formatDecimal(health.accuracyP50M, 'm') },
    { label: 'Gaps', value: formatInteger(health.gapCount) },
    { label: 'Battery drain', value: formatDecimal(health.batteryDrainPctPerHour, '% an hour') },
    { label: 'Main provider', value: health.primaryProvider ?? NONE },
  ];
}

function RecentSessions({ detail }: { detail: DriverDetail }) {
  const [open, setOpen] = useState<string | null>(null);
  const sessions = detail.recentSessions;
  return (
    <Card title="Recent sessions">
      {sessions.length === 0 ? (
        <Text size="sm">No tracking session yet.</Text>
      ) : (
        <Table.ScrollContainer minWidth={560}>
          <Table aria-label="Recent sessions">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Started</Table.Th>
                <Table.Th>Last message</Table.Th>
                <Table.Th>Phone</Table.Th>
                <RequireRole roles={ADMIN_ONLY}>
                  <Table.Th>Health</Table.Th>
                </RequireRole>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {sessions.map((session) => {
                const expanded = open === session.sessionId;
                const phone = [session.manufacturer, session.model].filter(Boolean).join(' ');
                return (
                  <Fragment key={session.sessionId}>
                    <Table.Tr>
                      <Table.Td>{formatDateTime(session.startedAt)}</Table.Td>
                      <Table.Td>{formatDateTime(session.lastSeenAt)}</Table.Td>
                      <Table.Td>
                        {phone === '' ? NONE : phone}
                        {session.androidSdk === undefined ? '' : `, SDK ${String(session.androidSdk)}`}
                      </Table.Td>
                      <RequireRole roles={ADMIN_ONLY}>
                        <Table.Td>
                          <Button
                            size="compact-xs"
                            variant="default"
                            aria-expanded={expanded}
                            aria-label={`Health of the session started ${formatDateTime(session.startedAt)}`}
                            onClick={() => {
                              setOpen(expanded ? null : session.sessionId);
                            }}
                          >
                            {expanded ? 'Hide health' : 'Health'}
                          </Button>
                        </Table.Td>
                      </RequireRole>
                    </Table.Tr>
                    {expanded && (
                      <Table.Tr>
                        <Table.Td colSpan={4}>
                          <SessionHealthPanel sessionId={session.sessionId} driverId={detail.driver.driverId} />
                        </Table.Td>
                      </Table.Tr>
                    )}
                  </Fragment>
                );
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Card>
  );
}

function RecentTrips({ detail }: { detail: DriverDetail }) {
  const trips = detail.recentTrips;
  return (
    <Card title="Recent trips">
      {trips.length === 0 ? (
        <Text size="sm">No trip yet.</Text>
      ) : (
        <Table.ScrollContainer minWidth={560}>
          <Table aria-label="Recent trips">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Trip</Table.Th>
                <Table.Th>Stage</Table.Th>
                <Table.Th>Booked</Table.Th>
                <Table.Th>Changed</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {/* Trip links come with the trip pages in 12e. */}
              {trips.map((trip) => (
                <Table.Tr key={trip.id}>
                  <Table.Td ff="monospace" title={trip.id}>
                    {shortId(trip.id)}
                  </Table.Td>
                  <Table.Td>{trip.status}</Table.Td>
                  <Table.Td>{formatDateTime(trip.createdAt)}</Table.Td>
                  <Table.Td>{formatDateTime(trip.updatedAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Card>
  );
}
