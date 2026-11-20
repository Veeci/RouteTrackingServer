import { expect, test } from '@playwright/test';
import { adminGet, type DriverRow, logIn, mint, watchConsole } from './helpers';

// 12c against a real server and the simulator (`simulator fleet --drivers 3 --dashboards 0`, started before the
// tests). The fleet read model learns of new accounts and positions from events, a moment later, so the tests wait
// with expect.poll on the API, never with fixed sleeps.

interface FleetPage {
  items: DriverRow[];
}

test('TC-12-E2E-02: an admin disables a driver, and the audit log shows DRIVER_DISABLED with the reason', async ({
  page,
  request,
}) => {
  const problems = watchConsole(page);
  const name = `E2E driver ${String(Date.now())}`;
  const reason = `E2E: phone reported lost ${String(Date.now())}`;
  const driver = await mint(request, 'DRIVER', name);
  const checker = await mint(request, 'ADMIN', 'E2E check');
  await expect
    .poll(async () => {
      const found = await adminGet<FleetPage>(
        request,
        checker.token,
        `/api/v1/admin/drivers?q=${encodeURIComponent(name)}`,
      );
      return found.items.map((item) => item.driverId);
    })
    .toEqual([driver.userId]);

  // A deep link without a session goes through the login and comes back with its filter.
  await page.goto(`/drivers?q=${encodeURIComponent(name)}`);
  await logIn(page, 'E2E admin', 'Admin');
  await page.getByRole('link', { name }).click();

  await expect(page.getByRole('heading', { name })).toBeVisible();
  await page.getByRole('button', { name: 'Disable driver' }).click();
  const dialog = page.getByRole('dialog', { name: 'Disable driver' });
  await dialog.getByLabel(/Reason/).fill(reason);
  await dialog.getByRole('button', { name: 'Disable', exact: true }).click();
  await expect(dialog).toBeHidden();

  const accountCard = page.getByRole('region', { name: 'Account' });
  await expect(accountCard).toContainText('Disabled');
  await expect(accountCard).toContainText(reason);
  await expect(page.getByRole('button', { name: 'Enable driver' })).toBeVisible();

  await page.getByRole('navigation', { name: 'Pages' }).getByRole('link', { name: 'Audit' }).click();
  await page.getByLabel('Target id').fill(driver.userId);
  await page.getByRole('button', { name: 'Apply' }).click();

  const record = page.getByRole('table', { name: 'Audit records' }).getByRole('row').filter({ hasText: reason });
  await expect(record).toHaveCount(1);
  await expect(record).toContainText('DRIVER_DISABLED');
  await expect(record).toContainText('ADMIN');

  expect(problems).toEqual([]);
});

test('TC-12-E2E-03: an admin sends a command, and its status reaches APPLIED while the driver is connected', async ({
  page,
  request,
}) => {
  const problems = watchConsole(page);
  const checker = await mint(request, 'ADMIN', 'E2E check');
  // The simulator's drivers are sim-driver-001 to 003. Wait until one is online: heard from within 60 s.
  let driverId = '';
  await expect
    .poll(
      async () => {
        const online = await adminGet<FleetPage>(
          request,
          checker.token,
          '/api/v1/admin/drivers?online=true&q=sim-driver',
        );
        driverId = online.items[0]?.driverId ?? '';
        return driverId;
      },
      { timeout: 60_000 },
    )
    .not.toBe('');

  await page.goto(`/drivers/${driverId}`);
  await logIn(page, 'E2E admin', 'Admin');
  await expect(page.getByRole('heading', { name: /^sim-driver-/ })).toBeVisible();

  await page.getByRole('button', { name: 'Send a command' }).click();
  const dialog = page.getByRole('dialog', { name: 'Send a command' });
  await dialog.getByRole('radio', { name: 'Flush the queue' }).check();
  await dialog.getByLabel(/Reason/).fill('E2E: upload the queue now');
  await dialog.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(dialog).toBeHidden();

  // The list polls every 3 s while the command waits. The simulator acks it when it next reads from its socket.
  const newest = page.getByRole('table', { name: 'Commands' }).getByRole('row').nth(1);
  await expect(newest).toContainText('Flush the queue');
  await expect(newest).toContainText('APPLIED', { timeout: 30_000 });
  await expect(page.getByText(/The list asks again every 3 s/)).toBeHidden();

  expect(problems).toEqual([]);
});
