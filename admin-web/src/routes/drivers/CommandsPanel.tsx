import { Badge, Button, Group, Loader, Paper, Stack, Table, Text, Title, type MantineColor } from '@mantine/core';
import { type UseQueryResult, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { unwrap } from '../../api/errors';
import type { Command, CommandList, CommandStatus, IssueCommand } from '../../api/types';
import { ErrorAlert } from '../../components/ErrorAlert';
import { formatDateTime } from '../../components/format';
import { ReasonDialog } from '../../components/ReasonDialog';
import { CommandFields, KIND_LABELS } from './CommandFields';
import { commandErrors, emptyDraft, toIssueCommand } from './commandForm';
import { commandPollInterval, commandsQuery, driverKeys } from './queries';

const STATUS_COLORS: Record<CommandStatus, MantineColor> = {
  PENDING: 'gray',
  SENT: 'blue',
  APPLIED: 'green',
  REJECTED: 'red',
  EXPIRED: 'orange',
};

/** A driver's newest commands and the form to send one. ADMIN only: the route answers 403 to anyone else. */
export function CommandsPanel({ driverId }: { driverId: string }) {
  const commands = useQuery(commandsQuery(driverId));
  const [sending, setSending] = useState(false);
  const polling = commandPollInterval(commands.data) !== false;

  return (
    <Paper withBorder p="md" radius="md" component="section" aria-labelledby="commands-title">
      <Stack gap="sm">
        <Group justify="space-between">
          <Title order={3} size="h4" id="commands-title">
            Commands
          </Title>
          <Button
            size="xs"
            onClick={() => {
              setSending(true);
            }}
          >
            Send a command
          </Button>
        </Group>
        {polling && (
          <Text size="sm" c="dimmed">
            A command waits for the device. The list asks again every 3 s.
          </Text>
        )}
        <CommandTable commands={commands} />
      </Stack>
      {sending && (
        <CommandDialog
          driverId={driverId}
          onClose={() => {
            setSending(false);
          }}
        />
      )}
    </Paper>
  );
}

function CommandTable({ commands }: { commands: UseQueryResult<CommandList> }) {
  if (commands.isPending) return <Loader size="sm" aria-label="Loading the commands" />;
  if (commands.data === undefined) return <ErrorAlert error={commands.error} />;
  if (commands.data.items.length === 0) return <Text size="sm">No command was sent to this driver yet.</Text>;
  return (
    <>
      {commands.isError && <ErrorAlert error={commands.error} />}
      <Table.ScrollContainer minWidth={640}>
        <Table striped aria-label="Commands">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Sent</Table.Th>
              <Table.Th>Command</Table.Th>
              <Table.Th>Settings</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>Changed</Table.Th>
              <Table.Th>Expires</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {commands.data.items.map((command) => (
              <Table.Tr key={command.commandId}>
                <Table.Td>{formatDateTime(command.createdAt)}</Table.Td>
                <Table.Td>{KIND_LABELS[command.kind]}</Table.Td>
                <Table.Td>{settingsOf(command)}</Table.Td>
                <Table.Td>
                  <Badge variant="light" color={STATUS_COLORS[command.status]}>
                    {command.status}
                  </Badge>
                </Table.Td>
                <Table.Td>{formatDateTime(command.updatedAt)}</Table.Td>
                <Table.Td>{formatDateTime(command.expiresAt)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
    </>
  );
}

function settingsOf(command: Command): string {
  switch (command.kind) {
    case 'SET_CONSTRAINT':
      return `${command.maxAccuracy ?? '?'}, at most every ${String(command.minIntervalMs ?? '?')} ms. ${command.reason ?? ''}`.trim();
    case 'REGISTER_GEOFENCES': {
      const ids = (command.regions ?? []).map((region) => region.id);
      return `${String(ids.length)} ${ids.length === 1 ? 'region' : 'regions'}: ${ids.join(', ')}`;
    }
    case 'FLUSH_QUEUE':
      return 'Upload the queue now';
  }
}

function CommandDialog({ driverId, onClose }: { driverId: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState(emptyDraft);
  const [checked, setChecked] = useState(false);
  const errors = checked ? commandErrors(draft) : {};

  const issue = useMutation({
    mutationFn: (body: IssueCommand) =>
      api.POST('/api/v1/admin/drivers/{driverId}/commands', { params: { path: { driverId } }, body }).then(unwrap),
    onSuccess: () => {
      // The list shows the new command as PENDING and polls until the device answers.
      void queryClient.invalidateQueries({ queryKey: driverKeys.commands(driverId) });
      onClose();
    },
  });

  return (
    <ReasonDialog
      title="Send a command"
      description="The server keeps the command and sends it when the device is connected. It expires after 10 minutes without an answer."
      confirmLabel="Send"
      // The server keeps the reason only inside a SET_CONSTRAINT command, and not in the audit row. C4 asks for it
      // with every kind all the same.
      reasonDescription="A constraint keeps it with the command. The server keeps no reason for the other kinds yet."
      onClose={onClose}
      pending={issue.isPending}
      error={issue.error}
      validate={() => {
        setChecked(true);
        return Object.keys(commandErrors(draft)).length === 0;
      }}
      onSubmit={(reason) => {
        issue.mutate(toIssueCommand(draft, reason));
      }}
    >
      <CommandFields draft={draft} onChange={setDraft} errors={errors} />
    </ReasonDialog>
  );
}
