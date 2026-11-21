import {
  Autocomplete,
  Badge,
  Button,
  Group,
  Loader,
  NativeSelect,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { keepPreviousData, queryOptions, useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { type SubmitEvent, useState } from 'react';
import { z } from 'zod';
import { api } from '../../api/client';
import { unwrap } from '../../api/errors';
import type { AuditRecord } from '../../api/types';
import { ADMIN_ONLY } from '../../auth/roles';
import { CursorPager } from '../../components/CursorPager';
import { AnchorLink } from '../../components/AnchorLink';
import { ErrorAlert } from '../../components/ErrorAlert';
import { formatDateTime, shortId } from '../../components/format';
import { NoPermission } from '../../components/NoPermission';
import { RequireRole } from '../../components/RequireRole';
import { dayEnd, dayStart, parseDay } from '../dates';
import { type PageMove, useCursorPaging } from '../paging';
import type { AuditSearch } from './search';

const routeApi = getRouteApi('/app/audit');

type Filters = Omit<AuditSearch, 'cursor'>;

/** The actions the server records today (`AuditEntry` calls in the Kotlin code). The field takes any text too. */
export const KNOWN_ACTIONS = [
  'COMMAND_ISSUED',
  'DRIVER_DISABLED',
  'DRIVER_ENABLED',
  'FLEET_REBUILT',
  'SESSION_HEALTH_RECOMPUTED',
  'TRIP_CANCELLED',
  'TRIP_SUMMARY_RECOMPUTED',
];

const TARGET_TYPES = [
  { value: '', label: 'Any' },
  { value: 'DRIVER', label: 'Driver' },
  { value: 'TRIP', label: 'Trip' },
  { value: 'SESSION', label: 'Session' },
  { value: 'FLEET', label: 'Fleet' },
];

export function auditQuery(search: AuditSearch) {
  const { actorId, action, targetType, targetId, from, to, cursor } = search;
  return queryOptions({
    queryKey: ['admin', 'audit', search],
    queryFn: ({ signal }) =>
      api
        .GET('/api/v1/admin/audit', {
          params: {
            query: {
              actorId,
              action,
              targetType,
              targetId,
              from: from === undefined ? undefined : dayStart(from),
              to: to === undefined ? undefined : dayEnd(to),
              cursor,
            },
          },
          signal,
        })
        .then(unwrap),
    // Records never change, and new ones arrive at the top. The page refetches only when asked to.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}

/** The audit log, newest first (ADMIN). OPERATOR gets "No permission" and no request is sent. */
export function AuditPage() {
  return (
    <Stack>
      <Title order={2}>Audit</Title>
      <RequireRole roles={ADMIN_ONLY} fallback={<NoPermission message="The audit log is for admins only." />}>
        <AuditLog />
      </RequireRole>
    </Stack>
  );
}

function AuditLog() {
  const search = routeApi.useSearch();
  const navigate = routeApi.useNavigate();
  const log = useQuery({ ...auditQuery(search), placeholderData: keepPreviousData });
  const paging = useCursorPaging(search.cursor, ({ cursor, trail }: PageMove) => {
    void navigate({ search: (previous) => ({ ...previous, cursor }), state: { cursorTrail: trail } });
  });
  const applyFilters = (filters: Filters) => {
    void navigate({ search: { ...filters, cursor: undefined } });
  };

  return (
    <>
      <AuditFilters key={JSON.stringify({ ...search, cursor: undefined })} search={search} onApply={applyFilters} />
      <Group justify="flex-end">
        <Button
          variant="default"
          size="xs"
          loading={log.isFetching}
          onClick={() => {
            void log.refetch();
          }}
        >
          Refresh
        </Button>
      </Group>
      {log.isPending ? (
        <Group gap="sm">
          <Loader size="sm" />
          <Text>Loading the audit log</Text>
        </Group>
      ) : log.data === undefined ? (
        <ErrorAlert error={log.error} />
      ) : (
        <>
          {log.isError && <ErrorAlert error={log.error} />}
          <AuditTable records={log.data.items} />
          <CursorPager paging={paging} nextCursor={log.data.nextCursor} />
        </>
      )}
    </>
  );
}

const uuid = z.uuid();

const FILTER_KEYS = ['actorId', 'action', 'targetType', 'targetId', 'from', 'to'] as const satisfies (keyof Filters)[];

interface AuditFiltersProps {
  search: AuditSearch;
  onApply: (filters: Filters) => void;
}

/** Every field applies together on "Apply", because a half-typed id would otherwise send a request per key. */
function AuditFilters({ search, onApply }: AuditFiltersProps) {
  const [actorId, setActorId] = useState(search.actorId ?? '');
  const [action, setAction] = useState(search.action ?? '');
  const [targetType, setTargetType] = useState(search.targetType ?? '');
  const [targetId, setTargetId] = useState(search.targetId ?? '');
  const [from, setFrom] = useState(search.from ?? '');
  const [to, setTo] = useState(search.to ?? '');
  const [errors, setErrors] = useState<{ actorId?: string; to?: string }>({});

  const blankToUndefined = (value: string) => (value.trim() === '' ? undefined : value.trim());

  const onSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found: { actorId?: string; to?: string } = {};
    const actor = blankToUndefined(actorId);
    if (actor !== undefined && !uuid.safeParse(actor).success) found.actorId = 'A whole user id (UUID).';
    const start = parseDay(from);
    const end = parseDay(to);
    if (start !== undefined && end !== undefined && end < start) found.to = 'The last day is before the first day.';
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    onApply({
      actorId: actor,
      action: blankToUndefined(action),
      targetType: blankToUndefined(targetType),
      targetId: blankToUndefined(targetId),
      from: start === undefined ? undefined : from,
      to: end === undefined ? undefined : to,
    });
  };

  const filtered = FILTER_KEYS.some((key) => search[key] !== undefined);
  return (
    <form onSubmit={onSubmit} role="search" aria-label="Filter the audit log" noValidate>
      <Group align="flex-start" gap="sm">
        <Autocomplete label="Action" data={KNOWN_ACTIONS} value={action} onChange={setAction} w={240} />
        <NativeSelect
          label="Target type"
          data={TARGET_TYPES}
          value={targetType}
          onChange={(event) => {
            setTargetType(event.currentTarget.value);
          }}
        />
        <TextInput
          label="Target id"
          value={targetId}
          onChange={(event) => {
            setTargetId(event.currentTarget.value);
          }}
        />
        <TextInput
          label="Actor id"
          value={actorId}
          error={errors.actorId}
          w={320}
          onChange={(event) => {
            setActorId(event.currentTarget.value);
          }}
        />
        <TextInput
          label="From day"
          type="date"
          value={from}
          onChange={(event) => {
            setFrom(event.currentTarget.value);
          }}
        />
        <TextInput
          label="To day"
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
    </form>
  );
}

function AuditTable({ records }: { records: AuditRecord[] }) {
  if (records.length === 0) return <Text>No record matches these filters.</Text>;
  return (
    <Table.ScrollContainer minWidth={960}>
      <Table striped aria-label="Audit records" verticalSpacing="xs">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Time</Table.Th>
            <Table.Th>Actor</Table.Th>
            <Table.Th>Action</Table.Th>
            <Table.Th>Target</Table.Th>
            <Table.Th>Reason</Table.Th>
            <Table.Th>Details</Table.Th>
            <Table.Th>Request id</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {records.map((record) => (
            <Table.Tr key={record.id}>
              <Table.Td>{formatDateTime(record.at)}</Table.Td>
              <Table.Td>
                {/* No endpoint gives staff names, so the actor is a short id with the role. */}
                <Group gap={4} wrap="nowrap">
                  <Text size="sm" ff="monospace" title={record.actorId}>
                    {shortId(record.actorId)}
                  </Text>
                  <Badge size="xs" variant="light">
                    {record.actorRole}
                  </Badge>
                </Group>
              </Table.Td>
              <Table.Td>{record.action}</Table.Td>
              <Table.Td>
                <Target record={record} />
              </Table.Td>
              <Table.Td>{record.reason ?? ''}</Table.Td>
              <Table.Td>
                {Object.entries(record.details).map(([key, value]) => (
                  <Text key={key} size="xs">
                    {key}: {value}
                  </Text>
                ))}
              </Table.Td>
              <Table.Td>
                <Text size="xs" ff="monospace">
                  {record.requestId ?? ''}
                </Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

/** A driver target links to the driver, and a trip target to the trip. */
function Target({ record }: { record: AuditRecord }) {
  const label = `${record.targetType} ${shortId(record.targetId)}`;
  const isId = uuid.safeParse(record.targetId).success;
  if (record.targetType === 'DRIVER' && isId) {
    return (
      <AnchorLink to="/drivers/$driverId" params={{ driverId: record.targetId }} size="sm" title={record.targetId}>
        {label}
      </AnchorLink>
    );
  }
  if (record.targetType === 'TRIP' && isId) {
    return (
      <AnchorLink to="/trips/$tripId" params={{ tripId: record.targetId }} size="sm" title={record.targetId}>
        {label}
      </AnchorLink>
    );
  }
  return (
    <Text size="sm" title={record.targetId}>
      {label}
    </Text>
  );
}
