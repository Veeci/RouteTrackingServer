import { defineConfig, devices } from '@playwright/test';

// The E2E tests run the production build under `vite preview`, which sends the real Content Security Policy and
// proxies /api and /ws to the RouteTracking server at RTS_API_URL (default http://localhost:8080). Start the server
// first: the tests log in through its dev-token route and read real data.
//
// With RTS_SMOKE_URL set, the run is the 12f smoke test instead: only e2e/smoke, against a deployed dashboard (nginx in
// front of the server's admin port), with no local server. RTS_STAGING_TOKEN is the staging access token.
const PREVIEW_URL = 'http://localhost:4173';
const SMOKE_URL = process.env.RTS_SMOKE_URL;
const ci = process.env.CI !== undefined;

export default defineConfig({
  testDir: 'e2e',
  ...(SMOKE_URL === undefined ? { testIgnore: 'smoke/**' } : { testMatch: 'smoke/**/*.spec.ts' }),
  forbidOnly: ci,
  retries: 0,
  reporter: ci ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: SMOKE_URL ?? PREVIEW_URL,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  ...(SMOKE_URL === undefined && {
    webServer: {
      command: 'npm run build && npm run preview',
      url: PREVIEW_URL,
      reuseExistingServer: !ci,
      timeout: 120_000,
    },
  }),
});
