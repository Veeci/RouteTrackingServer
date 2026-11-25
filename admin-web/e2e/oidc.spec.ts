import { expect, type Page, test } from '@playwright/test';
import { serveBlankTiles } from './helpers';

// 12g: the real login, against Keycloak with the realm in deploy/keycloak/rts-realm.json, and the server with
// OIDC_ISSUER set. This project runs a second build of the app, made with VITE_OIDC_ISSUER, on port 4174
// (playwright.config.ts). It needs RTS_E2E_OIDC_ISSUER; without it the project is left out.

const KEYCLOAK = new URL(process.env.RTS_E2E_OIDC_ISSUER ?? 'http://localhost:8180/realms/rts').origin;

/**
 * Console errors from the app's own pages. A Content Security Policy violation is one, so an empty list shows that
 * the policy lets the app reach the provider. Keycloak's own pages are not this app's to judge.
 */
function watchAppConsole(page: Page): string[] {
  const problems: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !page.url().startsWith(KEYCLOAK)) problems.push(message.text());
  });
  page.on('pageerror', (error) => {
    problems.push(error.message);
  });
  return problems;
}

/** Presses "Log in" in the app, then logs in on Keycloak's page. Its field ids are stable across Keycloak themes. */
async function logInAtProvider(page: Page, username: string, password: string): Promise<void> {
  await page.getByRole('button', { name: 'Log in' }).click();
  await page.waitForURL((url) => url.origin === KEYCLOAK);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
}

test('TC-12-E2E-06: an operator logs in through the identity provider, uses the API and the live map, and logs out', async ({
  page,
}) => {
  const problems = watchAppConsole(page);
  await serveBlankTiles(page);

  await page.goto('/drivers');
  await expect(page.getByText('Log in with your staff account.')).toBeVisible();
  await logInAtProvider(page, 'operator', 'operator-dev-only');

  // Back on the page the user asked for, with the name and role from the provider's token.
  await expect(page).toHaveURL(/\/drivers$/);
  await expect(page.getByRole('heading', { name: 'Drivers' })).toBeVisible();
  const header = page.getByRole('banner');
  await expect(header.getByText('Olive Operator')).toBeVisible();
  await expect(header.getByText('OPERATOR', { exact: true })).toBeVisible();
  // The server accepted the RS256 token: the driver list loaded (the simulator's drivers are in it).
  await expect(page.getByRole('link', { name: /^sim-driver-/ }).first()).toBeVisible({ timeout: 30_000 });

  // The live map gets its ticket with the provider's token, and the socket opens with the ticket.
  await page.getByRole('link', { name: 'Live map' }).click();
  await expect(page.getByRole('region', { name: 'Map status' }).getByText('Live', { exact: true })).toBeVisible();

  // Logging out ends Keycloak's session too: "Log in" then asks for the password again.
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.locator('#password')).toBeVisible();

  expect(problems).toEqual([]);
});

test('TC-12-E2E-07: the admin role opens the admin pages', async ({ page }) => {
  await page.goto('/audit');
  await logInAtProvider(page, 'admin', 'admin-dev-only');

  await expect(page.getByRole('heading', { name: 'Audit' })).toBeVisible();
  await expect(page.getByRole('banner').getByText('ADMIN', { exact: true })).toBeVisible();
});

test('TC-12-E2E-08: an account without a staff role is told so and not logged in', async ({ page }) => {
  await page.goto('/');
  await logInAtProvider(page, 'nobody', 'nobody-dev-only');

  await expect(page.getByText('No staff role')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Back to the login' })).toBeVisible();
});
