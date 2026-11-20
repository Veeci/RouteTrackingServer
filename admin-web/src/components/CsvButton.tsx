import { Button, Group, Text } from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { describeError } from '../api/messages';

export interface CsvButtonProps {
  label: string;
  /** Fetches the file and saves it: a `downloadCsv` call. */
  download: () => Promise<string>;
}

/** A button that downloads a CSV. A failure shows next to it, and a 401 sends the user to the login page. */
export function CsvButton({ label, download }: CsvButtonProps) {
  const csv = useMutation({ mutationFn: download });
  const failure = csv.isError ? describeError(csv.error) : undefined;
  return (
    <Group gap="xs" wrap="nowrap">
      {failure !== undefined && (
        <Text size="sm" c="red" role="alert">
          {failure.title}. {failure.message}
        </Text>
      )}
      <Button
        variant="default"
        size="xs"
        loading={csv.isPending}
        onClick={() => {
          csv.mutate();
        }}
      >
        {label}
      </Button>
    </Group>
  );
}
