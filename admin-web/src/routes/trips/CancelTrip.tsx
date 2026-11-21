import { Button, Text } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { isApiError, unwrap } from '../../api/errors';
import type { Trip } from '../../api/types';
import { STAFF_ROLES } from '../../auth/roles';
import { ReasonDialog } from '../../components/ReasonDialog';
import { RequireRole } from '../../components/RequireRole';
import { isFinalStatus } from '../../trip/status';
import { tripKeys } from './queries';

/**
 * "Cancel trip" for OPERATOR and ADMIN, through 12c's reason dialog. The button shows only while the trip has not
 * ended, but the dialog stays open when the trip ends behind it, so its 409 message stays readable (TC-12-UI-03).
 */
export function CancelTrip({ trip }: { trip: Trip }) {
  const [open, setOpen] = useState(false);
  const queryClient = useQueryClient();
  const readAgain = () => {
    void queryClient.invalidateQueries({ queryKey: tripKeys.detail(trip.id) });
    void queryClient.invalidateQueries({ queryKey: tripKeys.lists });
  };
  const cancel = useMutation({
    mutationFn: (reason: string) =>
      api.POST('/api/v1/admin/trips/{id}/cancel', { params: { path: { id: trip.id } }, body: { reason } }).then(unwrap),
    onSuccess: (cancelled) => {
      // The answer has the new stage at once. It has no route (only GET sends one), so the trip is read again too.
      queryClient.setQueryData(tripKeys.detail(trip.id), (previous: Trip | undefined) => ({
        ...previous,
        ...cancelled,
      }));
      readAgain();
      setOpen(false);
    },
    onError: (error) => {
      // 409 ILLEGAL_TRANSITION: the trip ended before the click arrived. The page shows its real stage behind the dialog.
      if (isApiError(error, 409)) readAgain();
    },
  });

  return (
    <RequireRole roles={STAFF_ROLES}>
      {!isFinalStatus(trip.status) && (
        <Button
          color="red"
          variant="light"
          onClick={() => {
            cancel.reset();
            setOpen(true);
          }}
        >
          Cancel trip
        </Button>
      )}
      {open && (
        <ReasonDialog
          title="Cancel this trip"
          description={
            <Text size="sm">
              The guest and the driver get a cancellation from the operator, even during the ride. They never see the
              reason.
            </Text>
          }
          confirmLabel="Cancel trip"
          confirmColor="red"
          reasonDescription="Only the audit log keeps it, as TRIP_CANCELLED."
          pending={cancel.isPending}
          error={cancel.error}
          onSubmit={(reason) => {
            cancel.mutate(reason);
          }}
          onClose={() => {
            setOpen(false);
          }}
        />
      )}
    </RequireRole>
  );
}
