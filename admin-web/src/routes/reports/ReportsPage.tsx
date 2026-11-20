import { Badge, Button, Group, Loader, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { queryOptions, useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { type SubmitEvent, useState } from 'react';
import { api } from '../../api/client';
import { downloadCsv } from '../../api/download';
import { unwrap } from '../../api/errors';
import type { ProviderGroup } from '../../api/types';
import { ADMIN_ONLY } from '../../auth/roles';
import { CsvButton } from '../../components/CsvButton';
import { ErrorAlert } from '../../components/ErrorAlert';
import { formatDecimal, formatRatio } from '../../components/format';
import { NoPermission } from '../../components/NoPermission';
import { RequireRole } from '../../components/RequireRole';
import { type DayRange, dayEnd, dayStart, lastDays, reportRangeProblem } from '../dates';

const routeApi = getRouteApi('/app/reports');

/** The range the page shows when the URL names none. */
export const DEFAULT_DAYS = 7;

/** The instants of a valid range, as the API takes them. */
interface Instants {
  from: string;
  to: string;
}

function instantsOf(range: DayRange): Instants | undefined {
  if (reportRangeProblem(range) !== undefined) return undefined;
  const from = dayStart(range.from);
  const to = dayEnd(range.to);
  return from === undefined || to === undefined ? undefined : { from, to };
}

export function providerReportQuery(instants: Instants) {
  return queryOptions({
    queryKey: ['admin', 'reports', 'providers', instants],
    queryFn: ({ signal }) =>
      api.GET('/api/v1/admin/reports/providers', { params: { query: instants }, signal }).then(unwrap),
  });
}

/** The provider report (ADMIN): session health grouped by provider, GMS, ROM and Android SDK. */
export function ReportsPage() {
  return (
    <Stack>
      <Title order={2}>Provider report</Title>
      <RequireRole roles={ADMIN_ONLY} fallback={<NoPermission message="The provider report is for admins only." />}>
        <ProviderReport />
      </RequireRole>
    </Stack>
  );
}

function ProviderReport() {
  const search = routeApi.useSearch();
  const navigate = routeApi.useNavigate();
  // Computed once per visit, so the default range does not move while the page is open.
  const [defaults] = useState(() => lastDays(DEFAULT_DAYS));
  const range = { from: search.from ?? defaults.from, to: search.to ?? defaults.to };
  // The server refuses more than 31 days. A shared or edited link may ask for more: say so, and send nothing.
  const problem = reportRangeProblem(range);
  const instants = instantsOf(range);

  return (
    <>
      <RangeForm
        key={`${range.from} ${range.to}`}
        range={range}
        onApply={(next) => {
          void navigate({ search: next });
        }}
      />
      {problem !== undefined || instants === undefined ? (
        <ErrorAlert
          error={null}
          text={{ title: 'Range not valid', message: problem ?? 'Pick a first and a last day.' }}
        />
      ) : (
        <ReportTable range={range} instants={instants} />
      )}
    </>
  );
}

function RangeForm({ range, onApply }: { range: DayRange; onApply: (range: DayRange) => void }) {
  const [from, setFrom] = useState(range.from);
  const [to, setTo] = useState(range.to);
  const [error, setError] = useState<string | undefined>();
  const onSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problem = reportRangeProblem({ from, to });
    setError(problem);
    if (problem === undefined) onApply({ from, to });
  };
  return (
    <form onSubmit={onSubmit} aria-label="Report range" noValidate>
      <Group align="flex-start" gap="sm">
        <TextInput
          label="First day"
          type="date"
          value={from}
          onChange={(event) => {
            setFrom(event.currentTarget.value);
          }}
        />
        <TextInput
          label="Last day"
          type="date"
          description="At most 31 days in all."
          value={to}
          error={error}
          onChange={(event) => {
            setTo(event.currentTarget.value);
          }}
        />
        <Button type="submit" variant="default" mt={25}>
          Show
        </Button>
      </Group>
    </form>
  );
}

function ReportTable({ range, instants }: { range: DayRange; instants: Instants }) {
  const report = useQuery(providerReportQuery(instants));
  if (report.isPending) {
    return (
      <Group gap="sm">
        <Loader size="sm" />
        <Text>Loading the report</Text>
      </Group>
    );
  }
  if (report.data === undefined) return <ErrorAlert error={report.error} />;
  const { groups } = report.data;
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          Sessions that started from {range.from} to {range.to}. A session appears 10 to 15 minutes after its last
          message.
        </Text>
        <CsvButton
          label="Export CSV"
          download={() =>
            downloadCsv(
              api.GET('/api/v1/admin/reports/providers.csv', { params: { query: instants }, parseAs: 'blob' }),
              `providers-${range.from}-${range.to}.csv`,
            )
          }
        />
      </Group>
      {report.isError && <ErrorAlert error={report.error} />}
      {groups.length === 0 ? (
        <Text>No session in this range has its health computed.</Text>
      ) : (
        <>
          {groups.some((group) => group.lowSample) && (
            <Text size="sm">
              Dimmed rows have fewer than 3 sessions. Their medians come from too few sessions to trust.
            </Text>
          )}
          <GroupTable groups={groups} />
        </>
      )}
    </Stack>
  );
}

const UNKNOWN = 'Unknown';

function gmsLabel(hasGms: boolean | undefined): string {
  if (hasGms === undefined) return UNKNOWN;
  return hasGms ? 'Yes' : 'No';
}

function GroupTable({ groups }: { groups: ProviderGroup[] }) {
  return (
    <Table.ScrollContainer minWidth={1100}>
      <Table striped aria-label="Provider groups">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Provider</Table.Th>
            <Table.Th>GMS</Table.Th>
            <Table.Th>ROM</Table.Th>
            <Table.Th>SDK</Table.Th>
            <Table.Th>Sessions</Table.Th>
            <Table.Th>Tracked hours</Table.Th>
            <Table.Th>Accepted</Table.Th>
            <Table.Th>Interval p50, p95 (s)</Table.Th>
            <Table.Th>Accuracy p50, p95 (m)</Table.Th>
            <Table.Th>Battery % an hour</Table.Th>
            <Table.Th>Gaps an hour</Table.Th>
            <Table.Th>Doze gaps an hour</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {groups.map((group) => (
            <Table.Tr
              key={[group.primaryProvider, group.hasGms, group.rom, group.androidSdk].join('|')}
              // Dimmed, and named for a screen reader, which sees no opacity.
              style={group.lowSample ? { opacity: 0.5 } : undefined}
              data-low-sample={group.lowSample}
            >
              <Table.Td>{group.primaryProvider ?? UNKNOWN}</Table.Td>
              <Table.Td>{gmsLabel(group.hasGms)}</Table.Td>
              <Table.Td>{group.rom ?? UNKNOWN}</Table.Td>
              <Table.Td>{group.androidSdk ?? UNKNOWN}</Table.Td>
              <Table.Td>
                <Group gap={4} wrap="nowrap">
                  {group.sessions}
                  {group.lowSample && (
                    <Badge size="xs" color="gray" variant="outline">
                      Few sessions
                    </Badge>
                  )}
                </Group>
              </Table.Td>
              <Table.Td>{formatDecimal(group.trackedHours)}</Table.Td>
              <Table.Td>{formatRatio(group.acceptedRatio)}</Table.Td>
              <Table.Td>
                {formatDecimal(group.intervalP50S)}, {formatDecimal(group.intervalP95S)}
              </Table.Td>
              <Table.Td>
                {formatDecimal(group.accuracyP50M)}, {formatDecimal(group.accuracyP95M)}
              </Table.Td>
              <Table.Td>{formatDecimal(group.batteryDrainPctPerHour)}</Table.Td>
              <Table.Td>{formatDecimal(group.gapsPerHour)}</Table.Td>
              <Table.Td>{formatDecimal(group.dozeGapsPerHour)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
