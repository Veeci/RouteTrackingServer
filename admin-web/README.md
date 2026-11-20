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

## The pages

| Page          | Path                 | Roles           | What it does                                                                              |
| ------------- | -------------------- | --------------- | ----------------------------------------------------------------------------------------- |
| Overview      | `/`                  | OPERATOR, ADMIN | Four fleet counts, asked again every 10 s                                                 |
| Drivers       | `/drivers`           | OPERATOR, ADMIN | Filters and the cursor in the URL, "As of" and Refresh, CSV export for ADMIN              |
| Driver detail | `/drivers/$driverId` | OPERATOR, ADMIN | Account, position, device, trip, health. ADMIN: disable, enable, commands, session health |
| Audit         | `/audit`             | ADMIN           | The audit log with filters, newest first. A driver target links to the driver             |
| Reports       | `/reports`           | ADMIN           | The provider report for whole days, the last 7 by default and at most 31, with CSV export |

Every disable, enable and command asks for a reason in a dialog. The audit log keeps the reason of a disable or an
enable, and a SET_CONSTRAINT command keeps its own. Lists page with "First page", "Previous" and "Next". The cursors of
earlier pages live in the browser's history entry, so a reload and the Back button keep them.

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

## Run the E2E tests

The E2E tests log in through the real server, read its data and act on simulated drivers, so start a server and the
simulator first. The dev profile mounts the dev login and migrates the database on start. CI uses the test profile,
which does the same.

1. Start Postgres:

   ```bash
   docker compose -f ../deploy/docker-compose.yml up -d db
   ```

2. Start the server on port 8080 from the repository root. It reads `APP_ENV`, the database settings and `JWT_SECRET`
   from `deploy/.env`:

   ```bash
   ./gradlew :app:run
   ```

3. Build the simulator and start three drivers from the repository root. They loop a Hanoi route and answer every
   command with `applied`, which TC-12-E2E-03 needs. The start script runs Java 21 or newer, from `JAVA_HOME` or the
   `PATH`:

   ```bash
   ./gradlew :tools:simulator:installDist
   ```

   ```bash
   tools/simulator/build/install/simulator/bin/simulator fleet --drivers 3 --dashboards 0 --duration 30m
   ```

4. Install Chromium for Playwright once, then run the tests:

   ```bash
   npx playwright install chromium
   ```

   ```bash
   npm run e2e
   ```

TC-12-E2E-02 disables a driver it creates itself, never a simulated one, so the tests can run again on the same
database.

Playwright reuses a preview that already runs on port 4173. On a failure it keeps a trace in `test-results/`. Open
it with `npx playwright show-trace <file>`.

## Layout

| Path              | Contents                                                                                                            |
| ----------------- | ------------------------------------------------------------------------------------------------------------------- |
| `src/api/`        | The `openapi-fetch` client, `ApiError` and Problem Details parsing, the query client, error texts, the CSV download |
| `src/auth/`       | The dev login, the session store, re-minting, the redirect check, roles                                             |
| `src/routes/`     | The router, one folder per page, the cursor paging, the search param and day helpers                                |
| `src/components/` | The app shell, the role gate, the reason dialog, the pager, the CSV button, shared error and not-found views        |
| `src/test/`       | Test setup, typed MSW handlers and fixtures, and the contract check `contract.ts`                                   |
| `e2e/`            | Playwright tests                                                                                                    |
| `csp.ts`          | The Content Security Policy. `vite preview` sends it, and `node csp.ts` prints it                                   |
