import { type APIRequestContext, expect, type Page } from '@playwright/test';

export interface Minted {
  token: string;
  userId: string;
}

/** A user and a token from the dev login, through the same preview proxy as the page. */
export async function mint(request: APIRequestContext, role: string, displayName: string): Promise<Minted> {
  const response = await request.post('/api/v1/dev/tokens', { data: { role, displayName } });
  expect(response.ok()).toBe(true);
  return (await response.json()) as Minted;
}

/** A GET on the admin API with a staff token. */
export async function adminGet<T>(request: APIRequestContext, token: string, path: string): Promise<T> {
  const response = await request.get(path, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), `${path} answered ${String(response.status())}`).toBe(true);
  return (await response.json()) as T;
}

export interface DriverRow {
  driverId: string;
  displayName?: string;
  online: boolean;
}

/** Fills the login form the app opened and logs in. */
export async function logIn(page: Page, displayName: string, role: 'Operator' | 'Admin'): Promise<void> {
  await page.getByLabel('Display name').fill(displayName);
  await page.getByRole('radio', { name: role }).check();
  await page.getByRole('button', { name: 'Log in' }).click();
}

/**
 * Collects console errors and uncaught errors. A Content Security Policy violation is a console error, so a test that
 * ends with an empty list also shows that the production policy blocked nothing the page needs.
 */
export function watchConsole(page: Page): string[] {
  const problems: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') problems.push(message.text());
  });
  page.on('pageerror', (error) => {
    problems.push(error.message);
  });
  return problems;
}
