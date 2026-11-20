import {
  ActionIcon,
  Button,
  Fieldset,
  Group,
  NativeSelect,
  NumberInput,
  Radio,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import type { CommandKind } from '../../api/types';
import {
  ACCURACIES,
  COMMAND_KINDS,
  type CommandDraft,
  type CommandErrors,
  newRegion,
  type RegionDraft,
} from './commandForm';

export const KIND_LABELS: Record<CommandKind, string> = {
  SET_CONSTRAINT: 'Set a constraint',
  REGISTER_GEOFENCES: 'Register geofences',
  FLUSH_QUEUE: 'Flush the queue',
};

export interface CommandFieldsProps {
  draft: CommandDraft;
  onChange: (draft: CommandDraft) => void;
  /** Shown after the first submit. */
  errors: CommandErrors;
}

/** The settings of one command: the kind, then the fields that kind needs. */
export function CommandFields({ draft, onChange, errors }: CommandFieldsProps) {
  return (
    <Stack gap="sm">
      <Radio.Group
        label="Command"
        value={draft.kind}
        onChange={(value) => {
          const kind = COMMAND_KINDS.find((candidate) => candidate === value);
          if (kind !== undefined) onChange({ ...draft, kind });
        }}
      >
        <Group mt="xs">
          {COMMAND_KINDS.map((kind) => (
            <Radio key={kind} value={kind} label={KIND_LABELS[kind]} />
          ))}
        </Group>
      </Radio.Group>
      {draft.kind === 'SET_CONSTRAINT' && <ConstraintFields draft={draft} onChange={onChange} errors={errors} />}
      {draft.kind === 'REGISTER_GEOFENCES' && <RegionFields draft={draft} onChange={onChange} errors={errors} />}
      {draft.kind === 'FLUSH_QUEUE' && (
        <Text size="sm">The device uploads every batch it has queued, at once. The command has no settings.</Text>
      )}
    </Stack>
  );
}

function ConstraintFields({ draft, onChange, errors }: CommandFieldsProps) {
  return (
    <Group grow align="flex-start">
      <NativeSelect
        label="Highest accuracy"
        description="The device uses at most this accuracy."
        required
        value={draft.maxAccuracy}
        data={[{ value: '', label: 'Pick one' }, ...ACCURACIES]}
        error={errors.maxAccuracy}
        onChange={(event) => {
          const picked = ACCURACIES.find((accuracy) => accuracy === event.currentTarget.value);
          onChange({ ...draft, maxAccuracy: picked ?? '' });
        }}
      />
      <NumberInput
        label="Shortest interval (ms)"
        description="The device sends fixes at most this often."
        required
        min={0}
        allowDecimal={false}
        allowNegative={false}
        value={draft.minIntervalMs}
        error={errors.minIntervalMs}
        onChange={(value) => {
          onChange({ ...draft, minIntervalMs: value });
        }}
      />
    </Group>
  );
}

function RegionFields({ draft, onChange, errors }: CommandFieldsProps) {
  const update = (index: number, change: Partial<RegionDraft>) => {
    onChange({
      ...draft,
      regions: draft.regions.map((region, at) => (at === index ? { ...region, ...change } : region)),
    });
  };
  return (
    <Stack gap="xs">
      {draft.regions.map((region, index) => {
        const at = `regions.${String(index)}`;
        const name = `Region ${String(index + 1)}`;
        return (
          <Fieldset key={region.key} legend={name} p="xs">
            <Group align="flex-start" wrap="nowrap" gap="xs">
              <TextInput
                label="Id"
                required
                w="25%"
                value={region.id}
                error={errors[`${at}.id`]}
                onChange={(event) => {
                  update(index, { id: event.currentTarget.value });
                }}
              />
              <NumberInput
                label="Latitude"
                required
                min={-90}
                max={90}
                decimalScale={7}
                value={region.lat}
                error={errors[`${at}.lat`]}
                onChange={(value) => {
                  update(index, { lat: value });
                }}
              />
              <NumberInput
                label="Longitude"
                required
                min={-180}
                max={180}
                decimalScale={7}
                value={region.lng}
                error={errors[`${at}.lng`]}
                onChange={(value) => {
                  update(index, { lng: value });
                }}
              />
              <NumberInput
                label="Radius (m)"
                required
                min={0}
                allowNegative={false}
                value={region.radiusM}
                error={errors[`${at}.radiusM`]}
                onChange={(value) => {
                  update(index, { radiusM: value });
                }}
              />
              <ActionIcon
                variant="subtle"
                color="red"
                mt={26}
                aria-label={`Remove ${name.toLowerCase()}`}
                disabled={draft.regions.length === 1}
                onClick={() => {
                  onChange({ ...draft, regions: draft.regions.filter((_, kept) => kept !== index) });
                }}
              >
                ×
              </ActionIcon>
            </Group>
          </Fieldset>
        );
      })}
      {errors.regions !== undefined && (
        <Text size="sm" c="red">
          {errors.regions}
        </Text>
      )}
      <Button
        variant="default"
        size="xs"
        w="fit-content"
        onClick={() => {
          onChange({ ...draft, regions: [...draft.regions, newRegion()] });
        }}
      >
        Add a region
      </Button>
    </Stack>
  );
}
