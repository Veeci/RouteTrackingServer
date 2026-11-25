import { expect, test } from '@playwright/test';
import { logIn, serveBlankTiles, watchConsole } from '../helpers';

// 12f: the dashboard as deployed, behind nginx and `tailscale serve`. It runs only with RTS_SMOKE_URL set
// (playwright.config.ts), after a staging deploy or against `docker compose` on a development machine. It changes no
// data: the dev login creates one OPERATOR user per run, and the test only reads.
//
// The map page covers the whole path: the files and the Content Security Policy from nginx, MapLibre's module worker
// (served as JavaScript), the API and the fleet socket through the proxy to the server's admin port.

const stagingToken = process.env.RTS_STAGING_TOKEN ?? '';

test.beforeEach(async ({ page }) => {
  await serveBlankTiles(page);
});

test('TC-12-HOST-04: an operator logs in on the deployed dashboard and the live map connects', async ({ page }) => {
  const problems = watchConsole(page);

  await page.goto('/map');
  await page.getByLabel('Staging access token').fill(stagingToken);
  await logIn(page, 'Staging smoke', 'Operator');

  await expect(page.getByRole('heading', { name: 'Live map' })).toBeVisible();
  // "Live" appears once the fleet socket is open and its first snapshot arrived.
  const status = page.getByRole('region', { name: 'Map status' });
  await expect(status.getByText('Live', { exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('region', { name: 'Map', exact: true })).toBeVisible();
  await expect(page.getByText('The map cannot be drawn')).toHaveCount(0);

  expect(problems).toEqual([]);
});
