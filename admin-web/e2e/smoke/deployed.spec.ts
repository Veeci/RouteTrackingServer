import { expect, type Page, test } from '@playwright/test';
import { logIn, serveBlankTiles, watchConsole } from '../helpers';

// 12f: the dashboard as deployed, behind nginx and `tailscale serve`. It runs only with RTS_SMOKE_URL set
// (playwright.config.ts), after a staging deploy or against `docker compose` on a development machine. It changes no
// data: the test only reads.
//
// Two logins, after the build (12g). With RTS_SMOKE_USERNAME and RTS_SMOKE_PASSWORD, the dashboard is expected to log
// in through Keycloak, as a staging build made with VITE_OIDC_ISSUER does, and the account needs the rts-operator role.
// Without them it uses the development login with RTS_STAGING_TOKEN, which creates one OPERATOR user per run.
//
// The map page covers the whole path: the files and the Content Security Policy from nginx, MapLibre's module worker
// (served as JavaScript), the API and the fleet socket through the proxy to the server's admin port.

const stagingToken = process.env.RTS_STAGING_TOKEN ?? '';
const smokeUsername = process.env.RTS_SMOKE_USERNAME ?? '';
const smokePassword = process.env.RTS_SMOKE_PASSWORD ?? '';
const viaProvider = smokeUsername !== '' && smokePassword !== '';

/**
 * Console errors from the dashboard's own pages, the login callback included, where a CSP block of the token endpoint
 * would show. Keycloak's own pages are not this app's to judge (as in e2e/oidc.spec.ts).
 */
function watchAppConsole(page: Page, appOrigin: string): string[] {
  const problems: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && new URL(page.url()).origin === appOrigin) problems.push(message.text());
  });
  page.on('pageerror', (error) => {
    problems.push(error.message);
  });
  return problems;
}

/** Presses "Log in", then logs in on the provider's page, whose field ids are stable across Keycloak themes. */
async function logInAtProvider(page: Page, appOrigin: string): Promise<void> {
  await page.getByRole('button', { name: 'Log in' }).click();
  await page.waitForURL((url) => url.origin !== appOrigin);
  await page.locator('#username').fill(smokeUsername);
  await page.locator('#password').fill(smokePassword);
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.origin === appOrigin);
}

test.beforeEach(async ({ page }) => {
  await serveBlankTiles(page);
});

test('TC-12-HOST-04: an operator logs in on the deployed dashboard and the live map connects', async ({
  page,
  baseURL,
}) => {
  const appOrigin = new URL(baseURL ?? '').origin;
  const problems = viaProvider ? watchAppConsole(page, appOrigin) : watchConsole(page);

  await page.goto('/map');
  if (viaProvider) {
    await logInAtProvider(page, appOrigin);
  } else {
    await page.getByLabel('Staging access token').fill(stagingToken);
    await logIn(page, 'Staging smoke', 'Operator');
  }

  await expect(page.getByRole('heading', { name: 'Live map' })).toBeVisible();
  // "Live" appears once the fleet socket is open and its first snapshot arrived.
  const status = page.getByRole('region', { name: 'Map status' });
  await expect(status.getByText('Live', { exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('region', { name: 'Map', exact: true })).toBeVisible();
  await expect(page.getByText('The map cannot be drawn')).toHaveCount(0);

  expect(problems).toEqual([]);
});
