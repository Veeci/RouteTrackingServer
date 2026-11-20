# Phase 12 — Admin Dashboard

Branch: `feat/p12-admin-dashboard` · Depends on: 11 (admin API, fleet socket, tickets), 1.5 for the staging deploy only ·
Status: **12a built** (2026-11-20), 12b to 12g planned. The decisions at the end were approved on 2026-11-20, all as
recommended

## Goal

Give operators a web app on top of the phase 11 admin API: headline numbers, a live map of the whole fleet, driver
and trip lists with detail pages, the actions staff may take (disable a driver, send a command, cancel a trip), the
audit log and the provider report. Phase 11's Definition of Done needs it: "the dashboard shows 500 simulated
drivers moving on the map, the driver list, driver detail with a working command button, and the audit log".

The app is a static single-page app. Every piece of data comes from the server through the REST API and two
WebSockets. The app holds no secret and no data of its own.

## Scope

**In:** a React and TypeScript single-page app; typed API access generated from `rts-v1.yaml`; a live fleet map on
the `/ws/v1/admin/fleet` socket; driver, trip, audit and report pages; role-aware UI for OPERATOR and ADMIN; a
development login for dev and staging; unit, component and end-to-end tests; a CI job; hosting on the staging laptop;
the server changes the app needs (an admin port and an admin trip-watch socket).

**Out:** a real login with an identity provider (slice 12g, needed before production use, planned here but built
last); mobile layouts beyond "usable on a tablet"; translations; dark mode; editing drivers or trips beyond the
existing admin actions; push notifications; analytics.

## What the server already gives the app

Everything below exists on `main` today (phases 9 to 11). The app needs no new REST endpoint for its first five
slices.

| Page | Endpoints | Role |
|---|---|---|
| Overview | `GET /api/v1/admin/overview` (cached 10 s on the server) | OPERATOR, ADMIN |
| Live map | `POST /api/v1/admin/ws-tickets`, then `/ws/v1/admin/fleet?ticket=` | OPERATOR, ADMIN |
| Drivers | `GET /api/v1/admin/drivers` (filters `online`, `onTrip`, `q`, `bbox`, keyset paging), `GET /api/v1/admin/drivers.csv` (ADMIN) | OPERATOR, ADMIN |
| Driver detail | `GET /api/v1/admin/drivers/{driverId}`, `POST …/disable` and `…/enable` (ADMIN), `GET` and `POST …/commands` (ADMIN), `GET` and `POST /api/v1/admin/sessions/{id}/health` (ADMIN) | mixed |
| Trips | `GET /api/v1/admin/trips` (filters `status`, `driverId`, `guestId`, `from`, `to`), `POST /api/v1/admin/trips/{id}/cancel` | OPERATOR, ADMIN |
| Trip detail | `GET /api/v1/trips/{id}`, `…/track`, `…/summary`, `…/export.geojson`, `…/export.gpx`, `POST /api/v1/admin/trips/{id}/summary` (ADMIN) | staff read every trip since 11a |
| Audit | `GET /api/v1/admin/audit` | ADMIN |
| Reports | `GET /api/v1/admin/reports/providers` and `.csv` | ADMIN |
| Maintenance | `POST /api/v1/admin/fleet/rebuild` | ADMIN |
| Login (dev) | `POST /api/v1/dev/tokens` with `role`, `displayName`, `userId` (reuse an account), behind `X-Staging-Token` in staging | anyone with the staging token |

Facts the design must respect, each checked in the code:

- **Tokens are HS256 only.** `AccessTokens.kt:39` signs and verifies with `Algorithm.HMAC256`. Staff tokens last 15
  minutes and there is no refresh token. A real login (12g) needs the server to also verify RS256 tokens from an
  identity provider.
- **CORS matches whole origins exactly.** `BrowserSecurity.kt` allows `it in origins` from `ADMIN_ORIGINS`, with
  credentials off, on the admin routes only. A host with a new URL for every preview build would need each URL
  listed.
- **The fleet socket takes a ticket, never a token.** Tickets are single use and valid 30 s, so every reconnect needs
  a new ticket. A socket that does not take a message within 5 s is closed with 1013.
- **The guest socket admits only GUEST.** `GuestSocket.kt:27` closes any other role with 4403. A live trip view for
  staff needs a server change (12a).
- **Every admin response says `Cache-Control: no-store`**, so the browser never keeps driver data on disk.

## Design

### Repository: a folder in this repository

The phase 11 plan named a separate repository, `RouteTrackingAdmin`. This plan recommends a folder in this
repository instead, `admin-web/`, next to the existing `clients/ts/` (decision D1).

| | Folder in this repository (recommended) | Separate repository |
|---|---|---|
| Contract changes | One PR changes `rts-v1.yaml`, the server and the app. The type check fails in the same PR when they disagree | The app pins a spec commit and upgrades on purpose. Drift shows only when someone upgrades |
| End-to-end tests | CI builds the server and the simulator from the same commit and runs Playwright against them | CI must pull a server image and a simulator image, and the server repository must publish a simulator image |
| Tooling | Gradle and npm side by side. CI jobs filter on paths, so a Kotlin-only change skips the web jobs | Each repository has one toolchain |
| Fits a team split | Less: one repository for both | More: a frontend team owns its repository |

You are one developer, and the strongest test you can have is Playwright against a server from the same commit. The
folder wins on both counts. `clients/ts/` becomes unnecessary once `admin-web/` generates its own types, and is
removed in 12b.

### Stack

| Need | Choice | Why, and the alternative |
|---|---|---|
| Language and build | TypeScript (strict), Vite, React | The most common stack for an admin single-page app, with the most answers online. Vue or Svelte would work as well |
| API types | `openapi-typescript` (7.13.0, as in `clients/ts`) plus `openapi-fetch` | Types come straight from `rts-v1.yaml`, and `openapi-fetch` checks each call's path, query and body against them at compile time. A hand-written client would drift from the spec |
| Server state | TanStack Query | Caching, background refresh, retries and invalidation after a mutation. It is the web version of a repository that exposes a cached `Flow` and refreshes it from the network |
| Live state | A small store (Zustand) fed by the fleet socket | The map needs one mutable map of markers updated every 2 s, outside React's render cycle |
| Routing | TanStack Router | Search params are typed and validated, so list filters and the cursor live in the URL and survive a reload or a shared link. React Router is the more common alternative, with untyped search params |
| Components | Mantine | Tables, forms, dialogs, date pickers and notifications in one library, so the app needs few design decisions. shadcn/ui with Tailwind gives more control but more assembly |
| Map | MapLibre GL JS with the OpenFreeMap style | Free, open source, no API key. Self-hosted Protomaps tiles of Hanoi are the later alternative (D6) |
| Runtime checks | zod schemas for the WebSocket messages | REST types are checked at compile time. Socket messages arrive as text, so the app validates them at runtime |
| Tests | Vitest, Testing Library, MSW (Mock Service Worker), Playwright | Unit, component and end-to-end, as in "Test plan" |

Pin exact versions in `package-lock.json` at scaffold time, and let Dependabot propose updates (it already watches
Gradle, Actions and Docker).

### App structure

```
admin-web/
  src/
    api/          openapi-fetch client, Problem Details parsing, auth header, 401 handling
    auth/         dev login, token store (memory), re-mint before expiry, role helpers
    fleet/        fleet socket client, ticket fetch, reconnect, marker store, zod schemas
    map/          MapLibre setup, marker and cluster layers
    routes/       one folder per page: overview, map, drivers, trips, audit, reports
    components/   shared table, filters, confirm-with-reason dialog, role gate
  generated/      rts-v1.ts from openapi-typescript (gitignored, generated by npm run generate)
  e2e/            Playwright tests
```

### Authentication in the browser

Slices 12b to 12f use a **development login** (D3). 12g replaces it with a real one.

1. The login page asks for a display name, the role (OPERATOR or ADMIN) and, in staging, the staging access token.
2. It calls `POST /api/v1/dev/tokens`. The first call returns a `userId`. The app keeps that id in `localStorage`
   and sends it on later logins, so the audit log shows one actor per person, not a new account per login.
3. The access token stays in memory only. A reload means a new login, which the plan accepts for dev and staging.
4. Staff tokens last 15 minutes. One minute before expiry the app mints a new token with the same `userId`. To do
   that without asking again, it keeps the staging token in `sessionStorage`, which the browser clears when the tab
   closes.
5. Any 401 sends the user to the login page and keeps the page they were on.

The staging token in `sessionStorage` is readable by any script on the page. Three things limit the risk: a strict
Content Security Policy that allows only the app's own scripts, no third-party scripts at all, and (with D2) a
dashboard that only tailnet devices can load. Production never mounts the dev route, so production needs 12g first.

**12g, the real login.** The browser runs the OpenID Connect Authorization Code flow with PKCE against an identity
provider. The server verifies RS256 tokens with the provider's published keys (JWKS), next to today's HS256 tokens,
and maps a role claim to OPERATOR or ADMIN. PKCE is the same flow the AppAuth library uses on Android. The provider is
decision D7.

### Live map

```
map moves (debounced 300 ms) ──> fleet_subscribe { bbox, filters }
                                         │
server ──> fleet_snapshot ──> store: replace all markers
       ──> fleet_delta     ──> store: apply upserts, apply removes
       ──> fleet_cluster   ──> store: replace clusters, clear markers
store changed ──> one setData() on the GeoJSON source (at most once a frame)
```

- **Markers are never React components.** 5 000 components re-rendering every 2 s would be the web version of 5 000
  composables recomposing on every tick. The store converts markers to one GeoJSON FeatureCollection, and MapLibre
  draws it on the GPU with a circle layer and a symbol layer for the heading.
- **Clusters** arrive from the server as counts per grid cell. They draw as sized circles with the count. Clicking one
  zooms in, which sends a new `fleet_subscribe`.
- **Connection life cycle.** The client holds a small state machine: get a ticket, connect, subscribe, live,
  reconnect. A close or an error waits with full-jitter backoff (as the simulator's `Backoff` does), then fetches a
  new ticket. Close 4403 stops reconnecting and shows "no permission". Close 1013 reconnects at once, because it means
  the client was slow, not the server.
- **Hidden tab.** When the page is hidden (Page Visibility API), the client closes the socket. When the page is
  visible again, it reconnects and gets a fresh snapshot. A forgotten tab then costs the server nothing.
- **Clicking a marker** opens a side panel with the driver's detail from `GET /api/v1/admin/drivers/{driverId}`.
- **Smooth movement** between ticks (moving each marker from its old to its new position over 2 s) is a stretch goal.
  Markers jump every 2 s without it.

### Lists and filters

- Filters, sort and the cursor live in the URL search params, typed by the router. A shared link opens the same view.
- Keyset paging shows "Next" and "Previous", never page numbers, because the API pages by cursor.
- CSV export cannot be a plain link, because the request needs the `Authorization` header. The app fetches the CSV
  with the header, makes a blob URL and clicks it. The driver list is small enough to hold in memory.

### Actions

Every action that changes data asks for a reason in a dialog, because the server audits it. The button is hidden for
a role that may not use it, but the server stays the authority: a 403 shows "no permission" and changes nothing.

| Action | Role | After success |
|---|---|---|
| Disable or enable a driver | ADMIN | Refresh the driver query. The driver's sockets close with 4403 on every instance |
| Send a command | ADMIN | Refresh the command list, which shows the delivery status |
| Cancel a trip | OPERATOR, ADMIN | Refresh the trip and the list. A trip that already ended shows the 409 message |
| Recompute a summary or session health | ADMIN | Refresh that panel |
| Rebuild the fleet read model | ADMIN | Show the driver count from the response |

### Errors

The server answers errors as Problem Details (RFC 9457) with a `code`. The API layer turns them into one type, and
the UI shows a message per `code`, with the server's `detail` as the fallback. A 429 shows how long to wait from
`Retry-After`. A network error shows "server unreachable" and TanStack Query retries reads.

### Security in the browser

- **Content Security Policy.** `default-src 'self'`; `script-src 'self'`; `connect-src 'self'` plus the API and `wss:`
  origins when they differ, plus the tile host; `img-src 'self' data: blob:` plus the tile host; `worker-src blob:`
  (MapLibre runs its workers from blob URLs); `frame-ancestors 'none'`.
- **No third-party scripts**, no analytics, no fonts from a CDN.
- **Text from the server** (names, reasons) is rendered as text. React escapes it, and the app never uses
  `dangerouslySetInnerHTML`.
- **Dependencies** are pinned, updated by Dependabot and checked with `npm audit` in CI.

### Server changes (slice 12a)

| Change | Why |
|---|---|
| An admin port, `http.adminPort` (0 means "same port", the dev default). When it is set, the admin REST routes, the admin sockets and the ticket route answer only on that port, with Ktor's `localPort` route selector, and the public port answers 404 for them | With D2 the admin plane is not reachable from the internet at all. The public port keeps the driver, guest, dev-token and health routes |
| An admin trip-watch socket, `/ws/v1/admin/trip?ticket=`, for staff, reusing the guest socket's watcher | The live trip view. The guest socket stays as it is: GUEST only, token in the header |
| Tickets usable on both admin sockets | The browser cannot set headers on either socket |
| The per-IP rate limit behind the dashboard's proxy | Every operator's request reaches the app from the proxy's address, so they share one limit. With a few operators that is fine. Raise the admin limit, or trust the proxy's `X-Forwarded-For` on the admin port only |

## Hosting

### Does our hosting help? Yes, in three ways

1. **Development needs no hosting at all.** Slices 12a to 12e run on the Mac: the Vite dev server proxies `/api` and
   `/ws` to the local server on port 8080, so the browser sees one origin and CORS never comes up. `simulator fleet
   --drivers 500 --dashboards 0` keeps drivers moving on the map.
2. **The staging laptop can serve the built app for free.** The app is a folder of static files, a few hundred KB
   without the map library. The laptop already runs Docker, gets deployed by CI on every merge and has HTTPS through
   Tailscale.
3. **The tailnet gives a private network for the admin plane, which no free public host gives.** Operators reach the
   dashboard and the admin API only from devices in your tailnet. The internet sees only the SDK's routes through
   Funnel.

Only the last slice (12f) and phase 11's Definition of Done need the hosting. Both wait for the laptop setup
(T1b.1 and T1b.6).

### The options

| | H1. Tailnet-only on the laptop (recommended) | H2. Public on the laptop, same origin | H3. Cloudflare Pages, API on Funnel | H4. Cloudflare Pages with Cloudflare Access |
|---|---|---|---|---|
| How | `tailscale serve` (tailnet only) on HTTPS port 8443 to an nginx container that serves the app and proxies `/api` and `/ws` to the app's admin port | Funnel to an nginx container that serves the app under `/admin` and proxies the rest to the app | Static files on Cloudflare's free plan. The browser calls the Funnel URL with CORS | As H3, with Cloudflare's login page in front of the static files |
| Cost | Free | Free | Free | Free for a small team |
| Admin API on the internet | No | Yes | Yes | Yes: Access guards the files, not the API on `ts.net` |
| CORS | Not needed | Not needed | Needed, each origin listed | Needed |
| Safe with the dev login | Yes: only tailnet devices reach it | No: needs 12g first | No: needs 12g first | Partly |
| Preview URL per PR | No (the Vite dev server covers it) | No | Yes | Yes |
| Operators need | The Tailscale app and an invite to your tailnet | A browser | A browser | A browser and an Access login |
| Same pattern as production (10d, nginx on Laptop 2) | Yes | Yes | No | No |
| Server change | The admin port (12a) | None | `ADMIN_ORIGINS` | `ADMIN_ORIGINS` |

**Recommendation: H1.** It keeps the admin API off the internet, which turns a leaked `JWT_SECRET` or a bug in a role
check from a public hole into a tailnet-only one. It needs no CORS, costs nothing and makes the dev login safe enough
for staging, so 12g can wait. The cost is that every operator installs Tailscale and joins your tailnet. Tailscale's
free Personal plan limits the number of users, so check the current limit before inviting operators. H3 is the
fallback if operators cannot install Tailscale, and it needs 12g first.

### H1 in detail

```
Operator laptop or tablet (in the tailnet)
  └─HTTPS─> rts-staging.<tailnet>.ts.net:8443   tailscale serve, tailnet only
               └─> 127.0.0.1:8090  admin-web container (nginx)
                      ├─ /           the app's static files, index.html for unknown paths
                      ├─ /api/       proxy to app:8081 (admin port)
                      └─ /ws/        proxy to app:8081, with the Upgrade headers for WebSockets

Phone with the SDK (anywhere)
  └─HTTPS/WSS─> rts-staging.<tailnet>.ts.net:443   tailscale funnel, public
                  └─> 127.0.0.1:8080  app public port (driver, guest, dev tokens, health)
```

- **The `admin-web` image.** CI builds `ghcr.io/veeci/route-tracking-admin-web:<sha>` (nginx with the built app baked
  in) next to the server image, with the same SHA. `deploy.sh` starts both, so a rollback rolls both back together.
- **nginx settings.** It answers unknown paths with `index.html`, so the router handles deep links. Hashed assets get
  `Cache-Control: public, max-age=31536000, immutable`, and `index.html` gets `no-cache`. It sets the Content Security
  Policy and `X-Content-Type-Options`.
- **Two ports on the laptop.** `compose.yml` publishes the app's 8080 and 8081 and the admin-web's 8090 on
  `127.0.0.1` only. Funnel points at 8080 (unchanged). `tailscale serve --bg --https=8443 http://127.0.0.1:8090` adds
  the tailnet-only entry. Check on the laptop that serve and funnel run side by side on one node, with
  `tailscale serve status`.
- **CI reaches it.** The deploy job already joins the tailnet as `tag:ci`, so the Playwright smoke test can open
  `https://rts-staging.<tailnet>.ts.net:8443`. The ACL policy must let `tag:ci` reach port 8443.
- **Production (10d).** The same `admin-web` image runs on Laptop 2 next to the edge nginx, with the admin entry on
  the tailnet only. 12g must be done before production gets the dashboard.

## Tasks

- [x] T12.1 Approve the decisions below, then record the choices in `docs/product/decisions.md` (rows 95 to 100).
- [x] T12.2 (12a) Admin port with `localPort`, admin trip-watch socket with tickets, AsyncAPI and OpenAPI updates,
      authorization matrix rows for the admin port.
- [ ] T12.3 (12b) Scaffold `admin-web/`: Vite, React, strict TypeScript, ESLint, Prettier, Vitest, MSW, Playwright;
      generated types; remove `clients/ts/` and move its CI job.
- [ ] T12.4 (12b) API layer (openapi-fetch, Problem Details, 401), dev login with re-mint, app shell, role gate,
      Overview page.
- [ ] T12.5 (12c) Drivers list and detail, disable and enable, commands, session health, CSV export; Audit and
      Reports pages.
- [ ] T12.6 (12d) Live map: fleet client, marker and cluster layers, filters, side panel, hidden-tab pause.
- [ ] T12.7 (12e) Trips list and detail with the track on a map, exports, cancel, live trip view.
- [ ] T12.8 (12f) `admin-web` image in CI, staging compose service, `tailscale serve` entry, deploy and rollback with
      the server, Playwright smoke test against staging, staging guide update.
- [ ] T12.9 (12g) OpenID Connect login with PKCE; RS256 and JWKS verification on the server; role claim mapping.

## Slices

| Slice | Delivers | Needs |
|---|---|---|
| 12a | Server: admin port, admin trip socket | 11 |
| 12b | `admin-web/` scaffold, login, shell, Overview, CI job | 12a for the dev proxy to port 8081 (or `adminPort = 0`) |
| 12c | Drivers, driver detail with actions, Audit, Reports | 12b |
| 12d | Live map | 12b |
| 12e | Trips, trip detail, live trip view | 12a, 12b (12d for the map component) |
| 12f | Hosting on the staging laptop, deploy, smoke test | 12b to 12e, and staging setup T1b.1 and T1b.6 |
| 12g | Real login | 12b. Needed before production (10d) |

12c and 12d are independent and can run in parallel sessions.

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-12-SRV-01 | API | `adminPort` set: every admin route answers 404 on the public port and works on the admin port | P0 |
| TC-12-SRV-02 | API | `adminPort = 0`: every route works on one port, as before | P0 |
| TC-12-SRV-03 | API | Admin trip socket: OPERATOR ticket watches any trip; GUEST or DRIVER ticket closes 4403; used ticket refused | P0 |
| TC-12-API-01 | Unit | A Problem Details body becomes the app's error type with `code`; a non-JSON error becomes "unexpected" | P0 |
| TC-12-API-02 | Component | A 401 on any query sends the user to login and back to the same page after login | P0 |
| TC-12-AUTH-01 | Unit | The token is re-minted one minute before expiry with the same `userId` (fake timers) | P0 |
| TC-12-AUTH-02 | Component | OPERATOR sees no ADMIN-only button; a 403 from the server shows "no permission" | P0 |
| TC-12-FL-01 | Unit | Store: snapshot replaces, delta upserts and removes, cluster clears markers, a new snapshot after cluster restores markers | P0 |
| TC-12-FL-02 | Unit | Client: close then reconnect fetches a new ticket and resubscribes with the current area (fake timers, fake socket) | P0 |
| TC-12-FL-03 | Unit | Close 4403 stops reconnecting; close 1013 reconnects at once | P1 |
| TC-12-FL-04 | Unit | Hidden page closes the socket; visible page reconnects and gets a snapshot | P1 |
| TC-12-FL-05 | Unit | A socket message that fails its zod schema is logged and dropped, the connection stays | P1 |
| TC-12-UI-01 | Component | Driver list: filters and cursor round-trip through the URL; "Next" uses the response cursor (MSW) | P0 |
| TC-12-UI-02 | Component | Disable dialog requires a reason; success refreshes the driver | P0 |
| TC-12-UI-03 | Component | Cancel on an ended trip shows the 409 message | P1 |
| TC-12-UI-04 | Component | CSV export downloads a file with the `Authorization` header (MSW) | P2 |
| TC-12-E2E-01 | E2E | Local server and simulator with 50 drivers: login as OPERATOR, the map shows 50 markers within 5 s, a driver moves between two ticks | P0 |
| TC-12-E2E-02 | E2E | ADMIN disables a driver from the detail page; the audit page shows `DRIVER_DISABLED` with the reason | P0 |
| TC-12-E2E-03 | E2E | ADMIN sends a command; its status reaches DELIVERED while the simulated driver is connected | P1 |
| TC-12-E2E-04 | E2E | OPERATOR cancels a trip; the trip shows CANCELLED and the live view ends | P1 |
| TC-12-PERF-01 | Manual | 5 000 simulated drivers in view: the map stays above 30 frames a second on the Mac while deltas arrive | P1 |
| TC-12-SEC-01 | E2E | Production build: the Content Security Policy blocks an inline script; no request goes to a host outside the allowed list | P1 |
| TC-12-HOST-01 | Manual | From a device outside the tailnet, port 8443 does not answer, and the Funnel URL answers 404 for `/api/v1/admin/overview` | P0 |
| TC-12-HOST-02 | CD | A merge to `main` deploys the matching `admin-web` image; a rollback restores both images | P0 |
| TC-12-CON-01 | CI | `npm run generate` from the current `rts-v1.yaml` then `tsc --noEmit` passes; a removed field breaks the build | P0 |

## Test implementation notes

- **MSW handlers are typed** with the generated `paths` type, so a handler that returns a body the spec does not
  allow fails to compile.
- **Fake WebSocket.** The fleet client takes a socket factory, so tests pass a fake that records sends and lets the
  test push messages and closes.
- **End-to-end in CI.** The job builds the server's `installDist` and the simulator, starts Postgres in a service
  container, runs the server with `APP_ENV=test`, starts `simulator fleet --drivers 50 --dashboards 0` and runs
  Playwright against the Vite preview build. The fleet tick stays at 2 s, so tests wait with `expect.poll`, never with
  fixed sleeps.
- **Socket fixtures** come from the AsyncAPI examples, so a change in the contract shows up in the unit tests.

## As built (12a)

- **The admin port.** `http.adminPort` (`HTTP_ADMINPORT`) defaults to 0, which keeps every route on `http.port` as
  before. When it is set, `main` starts a second Netty connector, and `adminPlane` (`platform/http/AdminPlane.kt`)
  wraps the admin sockets and the `adminArea` in Ktor's `localPort(adminPort)` selector. On the public port no route
  matches them, so it answers 404. The selector compares `request.local.localPort`, the port the connection reached.
  The forwarded-header plugin rewrites only `request.origin`, so no header can move a request onto the admin plane.
  `AppConfigLoader` refuses an admin port equal to `http.port` or outside 1 to 65535.
- **Where each route answers when the ports are split.** Routes outside `adminPlane` match on any port, so the admin
  port is a superset of the public one.

  | Routes | Public port | Admin port | Why |
  |---|---|---|---|
  | `/api/v1/admin/**`, `/ws/v1/admin/**` | 404 | Yes | The point of the split (D2) |
  | Driver, guest and echo sockets, trips, routes | Yes | Yes | The dashboard's trip detail reads `/api/v1/trips/{id}`, its track and its exports through the admin port |
  | `/api/v1/dev/tokens` | Yes | Yes | Testers' phones log in through Funnel. The dashboard's dev login (D3) goes through nginx to the admin port |
  | `/health/live`, `/health/ready` | Yes | Yes | `deploy.sh`, the image's health check and the CI smoke test probe 8080. The 12f proxy can probe its own upstream on 8081 |
  | `/metrics` | Yes | Yes | Unchanged. Prometheus scrapes `app:8080` (`deploy/monitoring/prometheus.yml`), and the staging guide reads it on 8080. That `/metrics` is public through Funnel stays the phase 1.5 follow-up. Moving it to the admin port is one more option for that follow-up |

- **The staff trip socket (D8).** `/ws/v1/admin/trip?ticket=` lives in the live context (`AdminTripSocket`), inside
  `authenticate(AUTH_TICKET)` next to the fleet socket. OPERATOR and ADMIN stay. Any other role's ticket gets close
  4403. A used or expired ticket, or an access token in a header or in `?token=`, is refused with 401 before the
  upgrade. The ticket code (`WsTickets`, `TicketAuthentication`) did not change, because a ticket was never tied to
  one socket.
- **One watcher for both trip sockets.** `TripWatcher` holds what `GuestSocket` did after its role check: `subscribe`,
  the snapshot through `TripQuery`, forwarding the bus updates, and the close after a final stage. `GuestSocket` and
  `AdminTripSocket` keep only their role rule and their endpoint name (`guest`, `admin_trip`) for logs and session
  metrics. The guest socket behaves as before: GUEST only, token in the header, and `GuestSocketIT` passes unchanged.
- **Staff see every trip.** `Trip.isVisibleTo` already admits ADMIN and OPERATOR for every trip
  (`trip/domain/Trip.kt:119`, since 11a), so the trip context needed no change.
- **A staff watcher counts for the precision signal.** The bus counts every subscriber of a trip. A staff watcher is
  one more watcher, so the first watcher on any instance tells device control (`GuestWatching.firstGuestWatching`),
  and so does the last one to leave. Counting guests only would need a second count per trip, in memory and in Redis.
  A staff view of a trip is rare and short, and it wants the precision a guest gets. `GuestWatching` keeps its name.
- **Contracts.** AsyncAPI has a channel `adminTrip` with the guest channel's messages and three operations. Its info
  text says the admin channels move to the admin port when there is one. OpenAPI says the same in its description,
  and `ws-tickets` names both sockets. No message type is new, so the golden files did not change.
- **Deploy files.** The staging compose sets `HTTP_ADMINPORT=8081` and publishes `127.0.0.1:8081`. Funnel still points
  at 8080. The local compose keeps 0 by default and publishes `127.0.0.1:9080-9081:8081`, used once a developer sets
  `HTTP_ADMINPORT=8081` in `deploy/.env`. Host port 8081 is already the second instance's public port under
  `--scale app=2`. The local default stays 0 because the simulator's `--dashboards` ask for tickets and open the
  fleet socket on the one `--url`. The image exposes 8081 too.
- **Tests.** TC-12-SRV-01 and TC-12-SRV-02 are `AdminPortIT`. They walk the routing tree the app serves
  (`routeSignatures()`, a new fixture that both authorization matrices now share), so a new admin route is checked
  without a change to the test. They also require the known admin routes in the walk, so an empty filter cannot pass.
  With the `localPort` wrapper removed, both split-port tests fail. `socketApp(adminPort = true)` starts a second
  connector on a free port, because the routes need the admin port before the server binds. TC-12-SRV-03 is
  `AdminTripSocketIT`. `FleetAuthorizationIT` has a socket matrix for both admin sockets (401, 4403 or open for each
  caller) with its own completeness check. The ticket helpers moved to the `Tickets.kt` fixture, and `FleetSocketIT`
  uses them. `AppConfigLoaderTest` checks that no profile sets an admin port and that a bad one refuses to load.
- **Not done here.**
  - The per-IP rate limit behind the dashboard's proxy ("Server changes"). In 12f every operator's request reaches
    the app from the nginx container's address. The forwarded-header plugin trusts only a loopback peer, which a
    container on the compose network is not. With a few operators the default 300 a minute is enough. 12f decides
    between a higher limit on the admin port and trusting the proxy's address.
  - The simulator cannot open dashboards against a split server, because `--url` names one base URL for drivers and
    dashboards. An `--admin-url` option fixes that when a load run needs dashboards against staging.

## Definition of Done

- An operator in the tailnet opens the staging dashboard, logs in, and sees 500 simulated drivers moving on the map.
- The driver list, driver detail with a working command button, trip list with cancel, and the audit log work against
  staging. Phase 11's Definition of Done is then met.
- A merge to `main` deploys the dashboard with the server, and a rollback restores both.
- From outside the tailnet, no admin route and no dashboard file answers.
- All P0 cases pass in CI.
- 12g has its own Definition of Done: a login through the identity provider, and no dev route in production.

## Decisions (approved 2026-11-20, all as recommended)

| # | Question | Chosen | Alternative not taken |
|---|---|---|---|
| D1 | Where does the code live? | `admin-web/` in this repository | The separate `RouteTrackingAdmin` repository from the phase 11 plan |
| D2 | Where does staging host the dashboard? | H1: tailnet only, on the laptop, behind nginx, with an admin port on the server | H3: Cloudflare Pages with CORS to the Funnel URL, after 12g |
| D3 | How do operators log in before 12g? | Dev login with re-mint, the staging token in `sessionStorage` | A new login on every page load and every 15 minutes |
| D4 | Component library | Mantine | shadcn/ui with Tailwind |
| D5 | Router | TanStack Router (typed search params) | React Router |
| D6 | Map tiles | OpenFreeMap (free, no key) | Protomaps tiles of Hanoi, served by the same nginx: no third party, but a build step for the tile file |
| D7 | Identity provider for 12g | Decide at 12g. Keycloak on the laptop keeps everything self-hosted | A hosted provider (Auth0, Clerk or Zitadel): no server to run, but a third party holds the staff accounts |
| D8 | Live trip view for staff | A new `/ws/v1/admin/trip` socket with tickets | Let staff use `/ws/v1/guest`, which would need tickets there too and mixes two audiences on one socket |
