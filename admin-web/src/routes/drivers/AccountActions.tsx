import { Button } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { unwrap } from '../../api/errors';
import type { DriverAccount, DriverDetail } from '../../api/types';
import { ReasonDialog } from '../../components/ReasonDialog';
import { driverKeys, driverQuery, isEnabled, withAccount } from './queries';

/** "Disable driver" or "Enable driver", with the reason dialog. ADMIN only: the server answers 403 to anyone else. */
export function AccountActions({ detail }: { detail: DriverDetail }) {
  const [open, setOpen] = useState(false);
  const enabled = isEnabled(detail);
  return (
    <>
      <Button
        size="xs"
        color={enabled ? 'red' : undefined}
        variant={enabled ? 'light' : 'filled'}
        onClick={() => {
          setOpen(true);
        }}
      >
        {enabled ? 'Disable driver' : 'Enable driver'}
      </Button>
      {open && (
        <AccountDialog
          driverId={detail.driver.driverId}
          name={detail.driver.displayName ?? 'The driver'}
          disable={enabled}
          onClose={() => {
            setOpen(false);
          }}
        />
      )}
    </>
  );
}

interface AccountDialogProps {
  driverId: string;
  name: string;
  disable: boolean;
  onClose: () => void;
}

function changeAccount(driverId: string, disable: boolean, reason: string): Promise<DriverAccount> {
  const request = { params: { path: { driverId } }, body: { reason } };
  return disable
    ? api.POST('/api/v1/admin/drivers/{driverId}/disable', request).then(unwrap)
    : api.POST('/api/v1/admin/drivers/{driverId}/enable', request).then(unwrap);
}

function AccountDialog({ driverId, name, disable, onClose }: AccountDialogProps) {
  const queryClient = useQueryClient();
  const change = useMutation({
    mutationFn: (reason: string) => changeAccount(driverId, disable, reason),
    onSuccess: (account) => {
      // Show the server's answer at once, then read the whole driver and the lists again.
      // The typed key of driverQuery checks that the updater gets and returns a DriverDetail.
      queryClient.setQueryData(driverQuery(driverId).queryKey, (old) =>
        old === undefined ? old : withAccount(old, account),
      );
      void queryClient.invalidateQueries({ queryKey: driverKeys.detail(driverId) });
      void queryClient.invalidateQueries({ queryKey: driverKeys.lists });
      onClose();
    },
  });

  return (
    <ReasonDialog
      title={disable ? 'Disable driver' : 'Enable driver'}
      description={
        disable
          ? `${name} can no longer open a tracking session. The server closes the driver's open sockets on every instance.`
          : `${name} can open tracking sessions again.`
      }
      confirmLabel={disable ? 'Disable' : 'Enable'}
      confirmColor={disable ? 'red' : undefined}
      onClose={onClose}
      pending={change.isPending}
      error={change.error}
      onSubmit={(reason) => {
        change.mutate(reason);
      }}
    />
  );
}
