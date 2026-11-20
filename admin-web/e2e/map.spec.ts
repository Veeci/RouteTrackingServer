import { expect, test } from '@playwright/test';
import { adminGet, type DriverRow, logIn, mint, serveBlankTiles, watchConsole } from './helpers';

// 12d against a real server and the simulator (`simulator fleet --drivers 50 --dashboards 0`, started before the
// tests). The test sees the map only through what an operator uses (choice C4): the status line's driver count, and
// the selected driver in the URL with its live position in the side panel. The fleet ticks every 2 s, so the test
// waits with expect.poll and auto-waiting assertions, never with fixed sleeps.

const SIMULATED_DRIVERS = 50;

interface FleetPage {
  items: DriverRow[];
}

test.beforeEach(async ({ page }) => {
  await serveBlankTiles(page);
});

test('TC-12-E2E-01: an operator sees the 50 simulated drivers live, and a selected driver moves', async ({
  page,
  request,
}) => {
  const problems = watchConsole(page);
  const checker = await mint(request, 'OPERATOR', 'E2E check');
  // The simulator starts its drivers within a few seconds. Wait until the API sees every one of them online.
  let driverId = '';
  await expect
    .poll(
      async () => {
        const online = await adminGet<FleetPage>(
          request,
          checker.token,
          '/api/v1/admin/drivers?online=true&q=sim-driver&limit=100',
        );
        driverId = online.items[0]?.driverId ?? '';
        return online.items.length;
      },
      { timeout: 60_000 },
    )
    .toBe(SIMULATED_DRIVERS);

  // A shared link: online drivers over Hanoi, wide enough for every simulated loop, with one driver selected.
  await page.goto(`/map?online=true&lat=21.0285&lng=105.8542&zoom=10&driver=${driverId}`);
  await logIn(page, 'E2E operator', 'Operator');
  await expect(page.getByRole('heading', { name: 'Live map' })).toBeVisible();

  const status = page.getByRole('region', { name: 'Map status' });
  await expect(status.getByText(`${String(SIMULATED_DRIVERS)} drivers in view`)).toBeVisible({ timeout: 5_000 });
  await expect(status.getByText('Live', { exact: true })).toBeVisible();
  // MapLibre started: its canvas is on the page, and the WebGL message is not.
  await expect(page.getByRole('region', { name: 'Map', exact: true })).toBeVisible();
  await expect(page.getByText('The map cannot be drawn')).toHaveCount(0);

  const panel = page.getByRole('complementary', { name: 'Selected driver' });
  await expect(panel.getByRole('heading', { name: /^sim-driver-/ })).toBeVisible();
  const position = panel.locator('dt:text-is("Position") + dd');
  await expect(position).toHaveText(/^-?\d+\.\d{5}, -?\d+\.\d{5}$/);
  const first = await position.textContent();
  // The simulated driver sends a batch every 5 s, and the next delta carries its new position.
  await expect.poll(() => position.textContent(), { timeout: 20_000 }).not.toBe(first);

  expect(problems).toEqual([]);
});
