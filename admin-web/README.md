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

## Log in through Keycloak (12g)

A build with `VITE_OIDC_ISSUER` logs in through the identity provider instead of the dev login. Start Keycloak with the
realm in [`deploy/keycloak`](../deploy/keycloak/README.md), start the server with
`OIDC_ISSUER=http://localhost:8180/realms/rts`, then run the dev server with the same issuer:

```bash
VITE_OIDC_ISSUER=http://localhost:8180/realms/rts npm run dev
```

Log in as `operator`, `admin` or `nobody` (no staff role). The passwords are in the realm file.
`VITE_OIDC_CLIENT_ID` defaults to `rts-admin-web`. `node csp.ts` prints the policy with the issuer's origin in
`connect-src` when `VITE_OIDC_ISSUER` is set, so set it there too when you build the nginx image.

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
| Live map      | `/map`               | OPERATOR, ADMIN | The fleet live on a map, with filters, a status line and a driver panel                   |
| Drivers       | `/drivers`           | OPERATOR, ADMIN | Filters and the cursor in the URL, "As of" and Refresh, CSV export for ADMIN              |
| Driver detail | `/drivers/$driverId` | OPERATOR, ADMIN | Account, position, device, trip, health. ADMIN: disable, enable, commands, session health |
| Audit         | `/audit`             | ADMIN           | The audit log with filters, newest first. A driver target links to the driver             |
| Reports       | `/reports`           | ADMIN           | The provider report for whole days, the last 7 by default and at most 31, with CSV export |

## The live map

The map page opens the admin fleet socket with a single-use ticket and draws every matching driver in the visible area.
The server sends a snapshot, then a delta every 2 s. Above 5 000 drivers in the area it sends counts per grid cell
instead, which the map draws as clusters. A click on a cluster zooms in, and a click on a dot opens the driver panel.

- The URL holds the filters (`online`, `onTrip`), the view (`lat`, `lng`, `zoom`) and the selected `driver`, so a
  shared link opens the same view. Moving the map does not add history entries.
- The status line shows the connection, the drivers in view and the time of the last update. A dropped connection
  tries again with a new ticket after a wait of up to 30 s. A hidden tab closes the socket, and the map catches up with
  a fresh snapshot when the tab is shown again.
- The base map is OpenFreeMap's liberty style from `tiles.openfreemap.org`, the only outside host the app reaches. If
  it does not load, the drivers draw on a plain background. The map needs WebGL 2. Without it the page says so, and the
  status line and the driver panel still work.

To watch simulated drivers move, start a server as in "Run it against a local server", then build the simulator and
start 50 drivers from the repository root. The start script runs Java 21 or newer, from `JAVA_HOME` or the `PATH`:

```bash
./gradlew :tools:simulator:installDist
```

```bash
tools/simulator/build/install/simulator/bin/simulator fleet --drivers 50 --dashboards 0 --duration 30m
```

Open http://localhost:5173/map. The drivers loop routes around Hanoi, the map's default view. Every simulated driver
asks for a token and opens a socket from your machine's address, which shares the server's limit of 300 requests a
minute per address with the browser. For 500 drivers or more, start the server with `HTTP_RATELIMITPERMINUTE=30000`.

## Actions and lists

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
| `npm run coverage`     | The same with coverage. It fails below 80 % of lines in `src/api`, `src/auth` or `src/fleet`    |
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
   from `deploy/.env`. The drivers, the browser and the tests share one rate limit per address, so raise it as CI does:

   ```bash
   HTTP_RATELIMITPERMINUTE=3000 ./gradlew :app:run
   ```

3. Build the simulator and start 50 drivers from the repository root. They loop Hanoi routes and answer every command
   with `applied`. TC-12-E2E-01 counts all 50 on the map, and TC-12-E2E-03 sends a command to one. The start script
   runs Java 21 or newer, from `JAVA_HOME` or the `PATH`:

   ```bash
   ./gradlew :tools:simulator:installDist
   ```

   ```bash
   tools/simulator/build/install/simulator/bin/simulator fleet --drivers 50 --dashboards 0 --duration 30m
   ```

4. Install Chromium for Playwright once, then run the tests:

   ```bash
   npx playwright install chromium
   ```

   ```bash
   npm run e2e
   ```

TC-12-E2E-02 disables a driver it creates itself, never a simulated one, so the tests can run again on the same
database. TC-12-E2E-01 waits until exactly 50 simulated drivers are online, so stop any larger simulator run first and
give its drivers a minute to go offline. The map test answers every request to `tiles.openfreemap.org` itself with a
blank style, so it needs no internet.

Playwright reuses a preview that already runs on port 4173. On a failure it keeps a trace in `test-results/`. Open
it with `npx playwright show-trace <file>`.

## Layout

| Path              | Contents                                                                                                            |
| ----------------- | ------------------------------------------------------------------------------------------------------------------- |
| `src/api/`        | The `openapi-fetch` client, `ApiError` and Problem Details parsing, the query client, error texts, the CSV download |
| `src/auth/`       | The dev login, the session store, re-minting, the redirect check, roles. `oidc/` is the real login (12g)            |
| `src/fleet/`      | The fleet socket client, the backoff, the zod schemas, the marker store and its GeoJSON, the ticket request         |
| `src/map/`        | The MapLibre map, its layers and colours, the view in the URL                                                       |
| `src/routes/`     | The router, one folder per page, the cursor paging, the search param, flag and day helpers                          |
| `src/components/` | The app shell, the role gate, the reason dialog, the pager, the CSV button, shared error and not-found views        |
| `src/test/`       | Test setup, typed MSW handlers and fixtures, the fleet socket fakes, and the contract check `contract.ts`           |
| `e2e/`            | Playwright tests                                                                                                    |
| `csp.ts`          | The Content Security Policy. `vite preview` sends it, and `node csp.ts` prints it                                   |
