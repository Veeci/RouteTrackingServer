import { type APIRequestContext, expect, test } from '@playwright/test';

// The 12b smoke test: log in as OPERATOR through the dev login, and the overview shows the real server's numbers.
// It waits with Playwright's auto-waiting assertions, never with fixed sleeps.

const COUNTS = [
  { key: 'onlineDrivers', label: 'Online drivers' },
  { key: 'driversOnTrip', label: 'Drivers on a trip' },
  { key: 'activeTrips', label: 'Active trips' },
  { key: 'disabledDrivers', label: 'Disabled drivers' },
] as const;

interface Overview {
  onlineDrivers: number;
  driversOnTrip: number;
  activeTrips: number;
  disabledDrivers: number;
  computedAt: string;
}

/** The overview as the API answers it, through the same preview proxy as the page. */
async function overviewFromApi(request: APIRequestContext): Promise<Overview> {
  const login = await request.post('/api/v1/dev/tokens', { data: { role: 'OPERATOR', displayName: 'E2E check' } });
  expect(login.ok()).toBe(true);
  const { token } = (await login.json()) as { token: string };
  const overview = await request.get('/api/v1/admin/overview', { headers: { Authorization: `Bearer ${token}` } });
  expect(overview.ok()).toBe(true);
  return (await overview.json()) as Overview;
}

test('an operator logs in and sees the fleet numbers from the server', async ({ page, request }) => {
  const problems: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') problems.push(message.text());
  });
  page.on('pageerror', (error) => {
    problems.push(error.message);
  });

  await page.goto('/');
  await expect(page).toHaveURL(/\/login\?redirect=%2F$/);

  await page.getByLabel('Display name').fill('E2E operator');
  await page.getByRole('radio', { name: 'Operator' }).check();
  await page.getByRole('button', { name: 'Log in' }).click();

  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('banner')).toContainText('E2E operator');
  await expect(page.getByRole('banner')).toContainText('OPERATOR');

  for (const { label } of COUNTS) {
    await expect(page.getByRole('group', { name: label })).toHaveText(new RegExp(`^${label}[\\d,.\\s]+$`));
  }
  await expect(page.getByText(/^Computed at /)).toBeVisible();

  // The page shows what the server says. The server caches the numbers for 10 s, so compare the two until they come
  // from the same computation.
  await expect(async () => {
    const api = await overviewFromApi(request);
    const shownAt = await page.locator('time').getAttribute('datetime');
    expect(shownAt).toBe(api.computedAt);
    for (const { key, label } of COUNTS) {
      await expect(page.getByRole('group', { name: label })).toContainText(String(api[key]), { timeout: 1_000 });
    }
  }).toPass({ timeout: 25_000 });

  // The production Content Security Policy blocked nothing the app needs, and nothing threw.
  expect(problems).toEqual([]);
});
