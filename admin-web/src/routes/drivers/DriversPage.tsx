import { Badge, Button, Group, Loader, NativeSelect, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { type SubmitEvent, useState } from 'react';
import { api } from '../../api/client';
import { downloadCsv } from '../../api/download';
import type { FleetDriver } from '../../api/types';
import { ADMIN_ONLY } from '../../auth/roles';
import { CsvButton } from '../../components/CsvButton';
import { CursorPager } from '../../components/CursorPager';
import { AnchorLink } from '../../components/AnchorLink';
import { ErrorAlert } from '../../components/ErrorAlert';
import { formatDateTime, formatTime, NONE } from '../../components/format';
import { RequireRole } from '../../components/RequireRole';
import { flagValue, ONLINE_OPTIONS, toFlag, TRIP_OPTIONS } from '../flags';
import { type PageMove, useCursorPaging } from '../paging';
import { driversQuery } from './queries';
import type { DriversSearch } from './search';

const routeApi = getRouteApi('/app/drivers');

type Filters = Omit<DriversSearch, 'cursor'>;

/** The fleet's drivers, filtered and paged by keyset, with the filters and cursor in the URL. */
export function DriversPage() {
  const search = routeApi.useSearch();
  const navigate = routeApi.useNavigate();
  // Keep the last page on screen while the next one loads, so the table does not flash empty.
  const list = useQuery({ ...driversQuery(search), placeholderData: keepPreviousData });

  const paging = useCursorPaging(search.cursor, ({ cursor, trail }: PageMove) => {
    void navigate({ search: (previous) => ({ ...previous, cursor }), state: { cursorTrail: trail } });
  });

  // A new filter starts at the first page. The new history entry has no trail, so "Previous" goes away (C1).
  const applyFilters = (filters: Filters) => {
    void navigate({ search: { ...filters, cursor: undefined } });
  };

  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <Title order={2}>Drivers</Title>
        <Group gap="sm">
          {list.data !== undefined && (
            <Text size="sm" c="dimmed">
              As of <time dateTime={list.data.at}>{formatTime(list.data.at)}</time>
            </Text>
          )}
          <Button
            variant="default"
            size="xs"
            loading={list.isFetching}
            onClick={() => {
              void list.refetch();
            }}
          >
            Refresh
          </Button>
          <RequireRole roles={ADMIN_ONLY}>
            <CsvButton
              label="Export CSV"
              download={() => downloadCsv(api.GET('/api/v1/admin/drivers.csv', { parseAs: 'blob' }), 'drivers.csv')}
            />
          </RequireRole>
        </Group>
      </Group>
      <DriverFilters key={search.q ?? ''} search={search} onApply={applyFilters} />
      {list.isPending ? (
        <Group gap="sm">
          <Loader size="sm" />
          <Text>Loading the drivers</Text>
        </Group>
      ) : list.data === undefined ? (
        <ErrorAlert error={list.error} />
      ) : (
        <>
          {list.isError && <ErrorAlert error={list.error} />}
          <DriverTable drivers={list.data.items} />
          <CursorPager paging={paging} nextCursor={list.data.nextCursor} />
        </>
      )}
    </Stack>
  );
}

interface DriverFiltersProps {
  search: DriversSearch;
  onApply: (filters: Filters) => void;
}

/** The two flags apply at once. The text applies on Enter or "Search", so typing does not add history entries. */
function DriverFilters({ search, onApply }: DriverFiltersProps) {
  const { online, onTrip, q } = search;
  const [text, setText] = useState(q ?? '');
  const onSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = text.trim();
    onApply({ online, onTrip, q: trimmed === '' ? undefined : trimmed });
  };
  const filtered = online !== undefined || onTrip !== undefined || q !== undefined;
  return (
    <form onSubmit={onSubmit} role="search" aria-label="Filter the drivers">
      <Group align="flex-end" gap="sm">
        <NativeSelect
          label="Online"
          data={ONLINE_OPTIONS}
          value={flagValue(online)}
          onChange={(event) => {
            onApply({ online: toFlag(event.currentTarget.value), onTrip, q });
          }}
        />
        <NativeSelect
          label="Trip"
          data={TRIP_OPTIONS}
          value={flagValue(onTrip)}
          onChange={(event) => {
            onApply({ online, onTrip: toFlag(event.currentTarget.value), q });
          }}
        />
        <TextInput
          label="Name or driver id"
          maxLength={100}
          value={text}
          onChange={(event) => {
            setText(event.currentTarget.value);
          }}
        />
        <Button type="submit" variant="default">
          Search
        </Button>
        {filtered && (
          <Button
            variant="subtle"
            onClick={() => {
              onApply({});
            }}
          >
            Clear filters
          </Button>
        )}
      </Group>
    </form>
  );
}

function DriverTable({ drivers }: { drivers: FleetDriver[] }) {
  const navigate = routeApi.useNavigate();
  if (drivers.length === 0) return <Text>No driver matches these filters.</Text>;
  return (
    <Table.ScrollContainer minWidth={720}>
      <Table highlightOnHover aria-label="Drivers">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Name</Table.Th>
            <Table.Th>Online</Table.Th>
            <Table.Th>Account</Table.Th>
            <Table.Th>Last seen</Table.Th>
            <Table.Th>Battery</Table.Th>
            <Table.Th>Trip stage</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {drivers.map((driver) => (
            <Table.Tr
              key={driver.driverId}
              style={{ cursor: 'pointer' }}
              onClick={() => {
                void navigate({ to: '/drivers/$driverId', params: { driverId: driver.driverId } });
              }}
            >
              <Table.Td>
                {/* The link serves the keyboard, a middle click and a screen reader. The row serves the mouse. */}
                <AnchorLink
                  to="/drivers/$driverId"
                  params={{ driverId: driver.driverId }}
                  onClick={(event) => {
                    event.stopPropagation();
                  }}
                >
                  {driver.displayName ?? `Driver ${driver.driverId.slice(0, 8)}`}
                </AnchorLink>
              </Table.Td>
              <Table.Td>
                <Badge color={driver.online ? 'green' : 'gray'} variant="light">
                  {driver.online ? 'Online' : 'Offline'}
                </Badge>
              </Table.Td>
              <Table.Td>
                {driver.enabled ? (
                  'Enabled'
                ) : (
                  <Badge color="red" variant="light">
                    Disabled
                  </Badge>
                )}
              </Table.Td>
              <Table.Td>{driver.lastSeenAt === undefined ? 'Never' : formatDateTime(driver.lastSeenAt)}</Table.Td>
              <Table.Td>
                {driver.device === undefined
                  ? NONE
                  : `${String(driver.device.batteryPct)} %${driver.device.isCharging ? ', charging' : ''}`}
              </Table.Td>
              <Table.Td>{driver.trip?.status ?? NONE}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
