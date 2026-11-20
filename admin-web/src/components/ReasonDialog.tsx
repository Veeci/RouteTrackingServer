import { Button, Group, Modal, Stack, Text, Textarea, type MantineColor } from '@mantine/core';
import { type ReactNode, type SubmitEvent, useState } from 'react';
import { ErrorAlert } from './ErrorAlert';

export interface ReasonDialogProps {
  title: string;
  /** What the action does, above the form. */
  description: ReactNode;
  confirmLabel: string;
  confirmColor?: MantineColor;
  /** Called with the trimmed reason, only when it is not blank and {@link validate} passed. */
  onSubmit: (reason: string) => void;
  onClose: () => void;
  /** The action's mutation runs. The dialog then refuses a second submit and cannot be closed. */
  pending: boolean;
  /** The mutation's error, shown above the buttons. */
  error: unknown;
  /** Checks the extra fields on each submit. The caller shows their errors. */
  validate?: () => boolean;
  /** Extra fields above the reason, such as a command's settings. */
  children?: ReactNode;
  /** Where the server keeps the reason, under the field. */
  reasonDescription?: string;
}

/**
 * The confirm-with-reason dialog of every action (C4). The reason is required even where the server would accept
 * none, because the audit log is only useful when each change says why. Render it only while it is open, so each
 * opening starts empty.
 */
export function ReasonDialog({
  title,
  description,
  confirmLabel,
  confirmColor,
  onSubmit,
  onClose,
  pending,
  error,
  validate,
  children,
  reasonDescription = 'The audit log keeps it.',
}: ReasonDialogProps) {
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | null>(null);

  const onFormSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    // A second click while the first request runs would send the action twice: POST commands has no
    // Idempotency-Key, so the server would store two commands.
    if (pending) return;
    const trimmed = reason.trim();
    const fieldsValid = validate?.() ?? true;
    setReasonError(trimmed === '' ? 'Give a reason.' : null);
    if (trimmed !== '' && fieldsValid) onSubmit(trimmed);
  };

  return (
    <Modal
      opened
      onClose={onClose}
      title={title}
      closeOnClickOutside={!pending}
      closeOnEscape={!pending}
      withCloseButton={!pending}
      size="lg"
    >
      <form onSubmit={onFormSubmit} noValidate>
        <Stack>
          {typeof description === 'string' ? <Text size="sm">{description}</Text> : description}
          {children}
          <Textarea
            label="Reason"
            description={reasonDescription}
            required
            autosize
            minRows={2}
            maxLength={500}
            value={reason}
            error={reasonError}
            onChange={(event) => {
              setReason(event.currentTarget.value);
            }}
          />
          {error !== null && error !== undefined && <ErrorAlert error={error} />}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose} disabled={pending}>
              Cancel
            </Button>
            <Button type="submit" color={confirmColor} loading={pending}>
              {confirmLabel}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
