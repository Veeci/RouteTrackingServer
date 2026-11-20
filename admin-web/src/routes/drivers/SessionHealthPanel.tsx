import { Button, Group, Loader, Stack, Text } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { isApiError, unwrap } from '../../api/errors';
import type { SessionHealth } from '../../api/types';
import { ErrorAlert } from '../../components/ErrorAlert';
import { type Fact, Facts } from '../../components/Facts';
import { formatDateTime, formatDecimal, formatInteger, formatRatio, NONE } from '../../components/format';
import { driverKeys, sessionHealthQuery } from './queries';

/** One session's stored health, with "Compute now". ADMIN only: the routes answer 403 to anyone else. */
export function SessionHealthPanel({ sessionId, driverId }: { sessionId: string; driverId: string }) {
  const queryClient = useQueryClient();
  const health = useQuery(sessionHealthQuery(sessionId));
  const compute = useMutation({
    mutationFn: () =>
      api.POST('/api/v1/admin/sessions/{id}/health', { params: { path: { id: sessionId } } }).then(unwrap),
    onSuccess: (computed) => {
      queryClient.setQueryData(sessionHealthQuery(sessionId).queryKey, computed);
      // The driver's "latest health" may be this one now.
      void queryClient.invalidateQueries({ queryKey: driverKeys.detail(driverId) });
    },
  });

  const computeButton = (label: string) => (
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
  );

  let body;
  if (health.isPending) {
    body = <Loader size="sm" aria-label="Loading the session health" />;
  } else if (health.data !== undefined) {
    body = (
      <Stack gap="xs">
        <Facts facts={healthFacts(health.data)} cols={3} />
        <Group>{computeButton('Compute again')}</Group>
      </Stack>
    );
  } else if (isApiError(health.error, 404) && health.error.code === 'SESSION_HEALTH_NOT_FOUND') {
    body = (
      <Group>
        <Text size="sm">
          Not computed yet. The server computes it 10 to 15 minutes after the session&apos;s last message.
        </Text>
        {computeButton('Compute now')}
      </Group>
    );
  } else {
    body = <ErrorAlert error={health.error} />;
  }

  return (
    <Stack gap="xs" aria-label="Session health" role="region">
      {body}
      {compute.isError && <ErrorAlert error={compute.error} />}
    </Stack>
  );
}

function healthFacts(health: SessionHealth): Fact[] {
  const gaps = health.gaps.length;
  const dozeGaps = health.gaps.filter((gap) => gap.likelyDoze).length;
  const rejections = Object.entries(health.rejectionsByReason)
    .map(([reason, count]) => `${reason} ${String(count)}`)
    .join(', ');
  const mix = Object.entries(health.providerMix)
    .map(([provider, share]) => `${provider} ${formatRatio(share)}`)
    .join(', ');
  return [
    { label: 'Computed', value: formatDateTime(health.computedAt) },
    { label: 'Last message then', value: formatDateTime(health.sessionLastSeenAt) },
    { label: 'Started', value: formatDateTime(health.startedAt) },
    { label: 'Accepted fixes', value: formatInteger(health.fixCount) },
    {
      label: 'Rejected fixes',
      value:
        rejections === '' ? formatInteger(health.rejectedCount) : `${String(health.rejectedCount)} (${rejections})`,
    },
    { label: 'Accepted share', value: formatRatio(health.acceptedRatio) },
    { label: 'Interval p50 and p95', value: pair(health.intervalP50S, health.intervalP95S, 's') },
    { label: 'Accuracy p50 and p95', value: pair(health.accuracyP50M, health.accuracyP95M, 'm') },
    { label: 'Gaps', value: `${String(gaps)}, of which ${String(dozeGaps)} likely Doze` },
    { label: 'Battery drain', value: formatDecimal(health.batteryDrainPctPerHour, '% an hour') },
    { label: 'Main provider', value: health.primaryProvider ?? NONE },
    { label: 'Provider mix', value: mix === '' ? NONE : mix },
  ];
}

function pair(p50: number | undefined, p95: number | undefined, unit: string): string {
  if (p50 === undefined && p95 === undefined) return NONE;
  return `${formatDecimal(p50)} and ${formatDecimal(p95)} ${unit}`;
}
