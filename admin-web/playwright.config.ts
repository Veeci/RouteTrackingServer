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

// 12g: with RTS_E2E_OIDC_ISSUER set (Keycloak's realm URL), a second build made with VITE_OIDC_ISSUER runs on its own
// port, and the `oidc` project logs in through the provider there. The first build keeps the development login, which
// every other test uses to mint its own tokens.
const OIDC_PREVIEW_URL = 'http://localhost:4174';
const oidcIssuer = process.env.RTS_E2E_OIDC_ISSUER;

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
  projects: [
    { name: 'chromium', testIgnore: /oidc\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    ...(oidcIssuer === undefined
      ? []
      : [
          {
            name: 'oidc',
            testMatch: /oidc\.spec\.ts/,
            use: { ...devices['Desktop Chrome'], baseURL: OIDC_PREVIEW_URL },
          },
        ]),
  ],
  ...(SMOKE_URL === undefined && {
    webServer: [
      {
        command: 'npm run build && npm run preview',
        url: PREVIEW_URL,
        reuseExistingServer: !ci,
        timeout: 120_000,
      },
      ...(oidcIssuer === undefined
        ? []
        : [
            {
              command: 'npx vite build --outDir dist-oidc && npx vite preview --outDir dist-oidc --port 4174',
              url: OIDC_PREVIEW_URL,
              reuseExistingServer: !ci,
              timeout: 120_000,
              // The build reads both; csp.ts adds the issuer's origin to connect-src for `vite preview`.
              env: { VITE_OIDC_ISSUER: oidcIssuer, VITE_OIDC_CLIENT_ID: 'rts-admin-web' },
            },
          ]),
    ],
  }),
});
