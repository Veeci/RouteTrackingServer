import { defineConfig, devices } from '@playwright/test';

// The E2E tests run the production build under `vite preview`, which sends the real Content Security Policy and
// proxies /api and /ws to the RouteTracking server at RTS_API_URL (default http://localhost:8080). Start the server
// first: the tests log in through its dev-token route and read real data.
const PREVIEW_URL = 'http://localhost:4173';
const ci = process.env.CI !== undefined;

export default defineConfig({
  testDir: 'e2e',
  forbidOnly: ci,
  retries: 0,
  reporter: ci ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: PREVIEW_URL,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run build && npm run preview',
    url: PREVIEW_URL,
    reuseExistingServer: !ci,
    timeout: 120_000,
  },
});
