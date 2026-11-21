import { Button, Checkbox, Group, Loader, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { type SubmitEvent, useState } from 'react';
import { z } from 'zod';
import type { Trip } from '../../api/types';
import { AnchorLink } from '../../components/AnchorLink';
import { CursorPager } from '../../components/CursorPager';
import { ErrorAlert } from '../../components/ErrorAlert';
import { formatDateTime, formatTime, shortId } from '../../components/format';
import { STATUS_LABELS, TRIP_STATUSES } from '../../trip/status';
import { parseDay } from '../dates';
import { type PageMove, useCursorPaging } from '../paging';
import { LIST_REFRESH_MS, tripsQuery } from './queries';
import { StatusBadge } from './StatusBadge';
import type { TripsSearch } from './search';

const routeApi = getRouteApi('/app/trips');

type Filters = Omit<TripsSearch, 'cursor'>;

const FILTER_KEYS = ['status', 'driverId', 'guestId', 'from', 'to'] as const satisfies (keyof Filters)[];

/** Every trip, newest first, filtered and paged by keyset, with the filters and the cursor in the URL. */
export function TripsPage() {
  const search = routeApi.useSearch();
  const navigate = routeApi.useNavigate();
  const list = useQuery({ ...tripsQuery(search), placeholderData: keepPreviousData });
  const paging = useCursorPaging(search.cursor, ({ cursor, trail }: PageMove) => {
    void navigate({ search: (previous) => ({ ...previous, cursor }), state: { cursorTrail: trail } });
  });
  const applyFilters = (filters: Filters) => {
    void navigate({ search: { ...filters, cursor: undefined } });
  };
  const onFirstPage = search.cursor === undefined;

  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <Title order={2}>Trips</Title>
        <Group gap="sm">
          {list.data !== undefined && (
            <Text size="sm" c="dimmed">
              {onFirstPage ? `Updates every ${String(LIST_REFRESH_MS / 1_000)} s. ` : ''}As of{' '}
              <time dateTime={new Date(list.dataUpdatedAt).toISOString()}>
                {formatTime(new Date(list.dataUpdatedAt).toISOString())}
              </time>
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
        </Group>
      </Group>
      <TripFilters key={JSON.stringify({ ...search, cursor: undefined })} search={search} onApply={applyFilters} />
      {list.isPending ? (
        <Group gap="sm">
          <Loader size="sm" />
          <Text>Loading the trips</Text>
        </Group>
      ) : list.data === undefined ? (
        <ErrorAlert error={list.error} />
      ) : (
        <>
          {list.isError && <ErrorAlert error={list.error} />}
          <TripTable trips={list.data.items} />
          <CursorPager paging={paging} nextCursor={list.data.nextCursor} />
        </>
      )}
    </Stack>
  );
}

const uuid = z.uuid();

interface FilterErrors {
  driverId?: string;
  guestId?: string;
  to?: string;
}

/** Every field applies together on "Apply", as on the Audit page, so a half-typed id sends no request. */
function TripFilters({ search, onApply }: { search: TripsSearch; onApply: (filters: Filters) => void }) {
  const [statuses, setStatuses] = useState<string[]>(search.status ?? []);
  const [driverId, setDriverId] = useState(search.driverId ?? '');
  const [guestId, setGuestId] = useState(search.guestId ?? '');
  const [from, setFrom] = useState(search.from ?? '');
  const [to, setTo] = useState(search.to ?? '');
  const [errors, setErrors] = useState<FilterErrors>({});

  const onSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found: FilterErrors = {};
    const driver = driverId.trim();
    const guest = guestId.trim();
    if (driver !== '' && !uuid.safeParse(driver).success) found.driverId = 'A whole driver id (UUID).';
    if (guest !== '' && !uuid.safeParse(guest).success) found.guestId = 'A whole guest id (UUID).';
    const start = parseDay(from);
    const end = parseDay(to);
    if (start !== undefined && end !== undefined && end < start) found.to = 'The last day is before the first day.';
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    const chosen = TRIP_STATUSES.filter((status) => statuses.includes(status));
    onApply({
      status: chosen.length === 0 ? undefined : chosen,
      driverId: driver === '' ? undefined : driver,
      guestId: guest === '' ? undefined : guest,
      from: start === undefined ? undefined : from,
      to: end === undefined ? undefined : to,
    });
  };

  const filtered = FILTER_KEYS.some((key) => search[key] !== undefined);
  return (
    <form onSubmit={onSubmit} role="search" aria-label="Filter the trips" noValidate>
      <Stack gap="sm">
        <Checkbox.Group
          label="Stage"
          description="None ticked means every stage."
          value={statuses}
          onChange={setStatuses}
        >
          <Group gap="md" mt={4}>
            {TRIP_STATUSES.map((status) => (
              <Checkbox key={status} value={status} label={STATUS_LABELS[status]} />
            ))}
          </Group>
        </Checkbox.Group>
        <Group align="flex-start" gap="sm">
          <TextInput
            label="Driver id"
            value={driverId}
            error={errors.driverId}
            w={320}
            onChange={(event) => {
              setDriverId(event.currentTarget.value);
            }}
          />
          <TextInput
            label="Guest id"
            value={guestId}
            error={errors.guestId}
            w={320}
            onChange={(event) => {
              setGuestId(event.currentTarget.value);
            }}
          />
          <TextInput
            label="Created from day"
            type="date"
            value={from}
            onChange={(event) => {
              setFrom(event.currentTarget.value);
            }}
          />
          <TextInput
            label="Created to day"
            type="date"
            value={to}
            error={errors.to}
            onChange={(event) => {
              setTo(event.currentTarget.value);
            }}
          />
          <Group gap="xs" mt={25}>
            <Button type="submit" variant="default">
              Apply
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
        </Group>
      </Stack>
    </form>
  );
}

function TripTable({ trips }: { trips: Trip[] }) {
  const navigate = routeApi.useNavigate();
  if (trips.length === 0) return <Text>No trip matches these filters.</Text>;
  return (
    <Table.ScrollContainer minWidth={760}>
      <Table highlightOnHover aria-label="Trips">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Trip</Table.Th>
            <Table.Th>Stage</Table.Th>
            <Table.Th>Created</Table.Th>
            <Table.Th>Updated</Table.Th>
            <Table.Th>Driver</Table.Th>
            <Table.Th>Guest</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {trips.map((trip) => (
            <Table.Tr
              key={trip.id}
              style={{ cursor: 'pointer' }}
              onClick={() => {
                void navigate({ to: '/trips/$tripId', params: { tripId: trip.id } });
              }}
            >
              <Table.Td>
                {/* The link serves the keyboard, a middle click and a screen reader. The row serves the mouse. */}
                <AnchorLink
                  to="/trips/$tripId"
                  params={{ tripId: trip.id }}
                  ff="monospace"
                  title={trip.id}
                  onClick={(event) => {
                    event.stopPropagation();
                  }}
                >
                  {shortId(trip.id)}
                </AnchorLink>
              </Table.Td>
              <Table.Td>
                <StatusBadge status={trip.status} />
              </Table.Td>
              <Table.Td>{formatDateTime(trip.createdAt)}</Table.Td>
              <Table.Td>{formatDateTime(trip.updatedAt)}</Table.Td>
              <Table.Td>
                {trip.driverId === undefined ? (
                  <Text size="sm" c="dimmed">
                    None yet
                  </Text>
                ) : (
                  <AnchorLink
                    to="/drivers/$driverId"
                    params={{ driverId: trip.driverId }}
                    ff="monospace"
                    title={trip.driverId}
                    onClick={(event) => {
                      event.stopPropagation();
                    }}
                  >
                    {shortId(trip.driverId)}
                  </AnchorLink>
                )}
              </Table.Td>
              <Table.Td>
                {/* No endpoint gives guest names, so the guest is a short id. */}
                <Text size="sm" ff="monospace" title={trip.guestId}>
                  {shortId(trip.guestId)}
                </Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
