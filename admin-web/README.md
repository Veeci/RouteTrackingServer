# Admin web

The admin dashboard for RouteTrackingServer: a React single-page app on the admin API. The plan, the decisions and
what each slice built are in [`docs/plan/phase-12-admin-dashboard.md`](../docs/plan/phase-12-admin-dashboard.md).

It holds no secret and no data of its own. Every piece of data comes from the server through the REST API, and the
access token lives in memory only, so a reload means a new login.

## Run it against a local server

You need Node 24 (`.nvmrc`) and a RouteTracking server on `localhost:8080` with `APP_ENV` dev, test or staging, which
mount the dev login. The root `README.md` shows how to start one.

```bash
npm ci
```

```bash
npm run generate
```

```bash
npm run dev
```

Open http://localhost:5173 and log in with any display name and a role. Leave the staging token empty for a local
server.

- `npm run generate` writes `generated/rts-v1.ts` from `../app/src/main/resources/openapi/rts-v1.yaml`. Run it again
  after the spec changes. The folder is not in git.
- The dev server forwards `/api` and `/ws` to `http://localhost:8080`. Set `RTS_API_URL` to use another server, for
  example the admin port of a server with `HTTP_ADMINPORT=8081`:

```bash
RTS_API_URL=http://localhost:8081 npm run dev
```

## Check it

| Command                | What it does                                                                                    |
| ---------------------- | ----------------------------------------------------------------------------------------------- |
| `npm run typecheck`    | TypeScript over the app, the tests, the configs and the E2E tests. Run `npm run generate` first |
| `npm run lint`         | ESLint with the type-checked rules                                                              |
| `npm run format:check` | Prettier, without writing. `npm run format` fixes the files                                     |
| `npm test`             | Unit and component tests (Vitest, Testing Library, MSW)                                         |
| `npm run coverage`     | The same with coverage. It fails below 80 % of lines in `src/api` and `src/auth`                |
| `npm run build`        | The production build in `dist/`                                                                 |
| `npm run preview`      | Serves `dist/` on port 4173 with the production headers and the same proxy                      |
| `npm run e2e`          | Builds, starts the preview, and runs Playwright against it                                      |

CI runs all of them in the `Admin web` and `Admin web E2E` jobs (`.github/workflows/ci.yml`).

## Run the E2E test

The E2E test logs in through the real server and reads its numbers, so start a server first. The dev profile mounts
the dev login and migrates the database on start. CI uses the test profile, which does the same.

1. Start Postgres:

   ```bash
   docker compose -f ../deploy/docker-compose.yml up -d db
   ```

2. Start the server on port 8080 from the repository root. It reads `APP_ENV`, the database settings and `JWT_SECRET`
   from `deploy/.env`:

   ```bash
   ./gradlew :app:run
   ```

3. Install Chromium for Playwright once, then run the test:

   ```bash
   npx playwright install chromium
   ```

   ```bash
   npm run e2e
   ```

Playwright reuses a preview that already runs on port 4173. On a failure it keeps a trace in `test-results/`. Open
it with `npx playwright show-trace <file>`.

## Layout

| Path              | Contents                                                                                                              |
| ----------------- | --------------------------------------------------------------------------------------------------------------------- |
| `src/api/`        | The `openapi-fetch` client, `ApiError` and Problem Details parsing, the query client and its retry rules, error texts |
| `src/auth/`       | The dev login, the session store, re-minting, the redirect check, roles                                               |
| `src/routes/`     | The router, and one folder per page                                                                                   |
| `src/components/` | The app shell, the role gate, shared error and not-found views                                                        |
| `src/test/`       | Test setup, typed MSW handlers, and the contract check `contract.ts`                                                  |
| `e2e/`            | Playwright tests                                                                                                      |
| `csp.ts`          | The Content Security Policy. `vite preview` sends it, and `node csp.ts` prints it                                     |
