import { Group, Loader, Paper, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { queryOptions, useQuery } from '@tanstack/react-query';
import { useId } from 'react';
import { api } from '../../api/client';
import { unwrap } from '../../api/errors';
import type { FleetOverview } from '../../api/types';
import { ErrorAlert } from '../../components/ErrorAlert';

/** The server counts at most once every 10 s (`GET /api/v1/admin/overview`), so asking more often gains nothing. */
export const OVERVIEW_REFRESH_MS = 10_000;

export const overviewQuery = queryOptions({
  queryKey: ['admin', 'overview'],
  queryFn: ({ signal }) => api.GET('/api/v1/admin/overview', { signal }).then(unwrap),
  // TanStack Query pauses the interval while the tab is hidden (refetchIntervalInBackground is false).
  refetchInterval: OVERVIEW_REFRESH_MS,
  staleTime: OVERVIEW_REFRESH_MS,
});

type Count = Exclude<keyof FleetOverview, 'computedAt'>;

const STATS: readonly { key: Count; label: string }[] = [
  { key: 'onlineDrivers', label: 'Online drivers' },
  { key: 'driversOnTrip', label: 'Drivers on a trip' },
  { key: 'activeTrips', label: 'Active trips' },
  { key: 'disabledDrivers', label: 'Disabled drivers' },
];

const numberFormat = new Intl.NumberFormat();
const timeFormat = new Intl.DateTimeFormat(undefined, { timeStyle: 'medium' });

export function OverviewPage() {
  return (
    <Stack>
      <Title order={2}>Overview</Title>
      <OverviewNumbers />
    </Stack>
  );
}

function OverviewNumbers() {
  const overview = useQuery(overviewQuery);

  if (overview.isPending) {
    return (
      <Group gap="sm">
        <Loader size="sm" />
        <Text>Loading the fleet numbers</Text>
      </Group>
    );
  }
  if (overview.data === undefined) return <ErrorAlert error={overview.error} />;

  const { data } = overview;
  const computedAt = timeFormat.format(new Date(data.computedAt));
  return (
    <>
      {overview.isError && (
        <ErrorAlert error={overview.error}>
          <Text size="sm">The numbers below are from {computedAt}.</Text>
        </ErrorAlert>
      )}
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
        {STATS.map((stat) => (
          <StatCard key={stat.key} label={stat.label} value={data[stat.key]} />
        ))}
      </SimpleGrid>
      <Text size="sm" c="dimmed">
        Computed at <time dateTime={data.computedAt}>{computedAt}</time>. The page asks again every 10 s.
      </Text>
    </>
  );
}

function StatCard({ label, value }: { label: string; value: number }) {
  const labelId = useId();
  return (
    <Paper withBorder p="md" radius="md" role="group" aria-labelledby={labelId}>
      <Text id={labelId} size="sm" c="dimmed">
        {label}
      </Text>
      <Text fz={32} fw={700}>
        {numberFormat.format(value)}
      </Text>
    </Paper>
  );
}
