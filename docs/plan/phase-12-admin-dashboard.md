# Phase 12 — Admin Dashboard

Branch: `feat/p12-admin-dashboard` · Depends on: 11 (admin API, fleet socket, tickets), 1.5 for the staging deploy only ·
Status: **12a to 12d built** (2026-11-20), 12e to 12g planned. The decisions at the end were approved on 2026-11-20,
all as recommended

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
repository instead, `admin-web/` (decision D1).

| | Folder in this repository (recommended) | Separate repository |
|---|---|---|
| Contract changes | One PR changes `rts-v1.yaml`, the server and the app. The type check fails in the same PR when they disagree | The app pins a spec commit and upgrades on purpose. Drift shows only when someone upgrades |
| End-to-end tests | CI builds the server and the simulator from the same commit and runs Playwright against them | CI must pull a server image and a simulator image, and the server repository must publish a simulator image |
| Tooling | Gradle and npm side by side. Every CI job runs on every PR, with no path filter, so any job can be a required check. A Kotlin-only change still runs the web jobs | Each repository has one toolchain |
| Fits a team split | Less: one repository for both | More: a frontend team owns its repository |

You are one developer, and the strongest test you can have is Playwright against a server from the same commit. The
folder wins on both counts. The phase 11 `clients/ts/` folder became unnecessary once `admin-web/` generated its own
types, and 12b removed it.

### Stack

| Need | Choice | Why, and the alternative |
|---|---|---|
| Language and build | TypeScript (strict), Vite, React | The most common stack for an admin single-page app, with the most answers online. Vue or Svelte would work as well |
| API types | `openapi-typescript` (7.13.0) plus `openapi-fetch` | Types come straight from `rts-v1.yaml`, and `openapi-fetch` checks each call's path, query and body against them at compile time. A hand-written client would drift from the spec |
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
  origins when they differ, plus the tile host `https://tiles.openfreemap.org`; `img-src 'self' data: blob:` plus the
  tile host; `worker-src 'self'` (MapLibre's worker file is served from the app's own origin, 12d choice C1);
  `frame-ancestors 'none'`. `admin-web/csp.ts` holds the full policy.
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
- [x] T12.3 (12b) Scaffold `admin-web/`: Vite, React, strict TypeScript, ESLint, Prettier, Vitest, MSW, Playwright;
      generated types; remove `clients/ts/` and move its CI job.
- [x] T12.4 (12b) API layer (openapi-fetch, Problem Details, 401), dev login with re-mint, app shell, role gate,
      Overview page.
- [x] T12.5 (12c) Drivers list and detail, disable and enable, commands, session health, CSV export; Audit and
      Reports pages.
- [x] T12.6 (12d) Live map: fleet client, marker and cluster layers, filters, side panel, hidden-tab pause.
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
| TC-12-E2E-03 | E2E | ADMIN sends a command; its status reaches APPLIED while the simulated driver is connected | P1 |
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

## As built (12b)

The four choices approved on 2026-11-20 are built as chosen:

| # | Choice |
|---|---|
| C1 | The token is re-minted by a timer 60 s before `expiresAt`, and at once when the tab becomes visible within 60 s of expiry or past it |
| C2 | One `Admin web` CI job on every PR and every push to `main`, with no path filter. The main session makes it a required check after this PR merges |
| C3 | The Playwright job `Admin web E2E` lands now and stays optional for about a week |
| C4 | Node 24, in `admin-web/.nvmrc` |

- **Tooling.** `admin-web/` pins every version exactly (`.npmrc` has `save-exact=true`, and `package-lock.json` is
  committed): Vite 8.3.8, React 19.3.1, Mantine 9.8.1, TanStack Query 5.108.1, TanStack Router 1.170.46 with routes in
  code, Zustand 5.1.0, zod 4.6.7, openapi-fetch 0.17.0 and openapi-typescript 7.13.0. Tests use Vitest 5.1.1 on
  jsdom 31, Testing Library, MSW 3.0.7 and Playwright 1.65.1. ESLint 10 runs the type-checked `strictTypeChecked` and
  `stylisticTypeChecked` sets of typescript-eslint, which include `no-floating-promises`, plus the React hooks rules.
  Prettier formats with 2 spaces, and the root `.editorconfig` has an `admin-web/**` section that says the same.
- **TypeScript is 5.9.3, not the newest 7.0.2.** typescript-eslint 8.73 accepts TypeScript below 6.1, and
  openapi-typescript 7.13 accepts only 5.x, so 5.9.3 is the newest version both accept. Dependabot proposes the move
  when both widen their ranges.
- **The dev proxy.** `vite.config.ts` sends `/api` and `/ws` (with `ws: true`) to `RTS_API_URL`, by default
  `http://localhost:8080`. `vite preview` uses the same proxy and sends the production headers.
- **The Content Security Policy lives in `admin-web/csp.ts`.** `vite preview` sends it, and `node csp.ts` prints it for
  the 12f nginx image. It differs from "Security in the browser" in five places. `style-src 'self' 'unsafe-inline'`
  is needed because Mantine writes its CSS variables in a `<style>` tag and sets inline style attributes. Inline styles
  cannot run code. `object-src 'none'`, `base-uri 'self'`, `form-action 'self'` and `font-src 'self'` close the gaps
  that `default-src` leaves. `connect-src 'self'` already covers same-origin WebSockets.
- **The API layer (`src/api`).** One `openapi-fetch` client typed by the generated `paths`. A middleware adds
  `Authorization: Bearer` from the session store on every request, outside React. `unwrap` turns a non-2xx answer into
  an `ApiError` with `status`, `code`, `title`, `detail`, `retryAfter` and `traceId`. It checks `response.ok`, because
  openapi-fetch returns `error: undefined` for an error with an empty body. A body without a string `code` becomes
  code `unexpected`, and a failed fetch becomes status 0 with code `network`. Codes the app makes are lower case, so
  they never clash with the server's upper-case codes. `Retry-After` is read as seconds or as an HTTP date.
- **Query defaults.** Reads retry twice, on a network error or a 5xx only. Mutations never retry, because the server
  audits each action. A 401 from any query or mutation clears the session and opens
  `/login?redirect=<path and search>`. The login form's own mutation opts out with `meta.skipAuthRedirect`, because
  its 401 comes from the staging gate and means a wrong staging token.
- **The dev login (`src/auth`).** The token, role, display name and expiry live in a Zustand vanilla store, in memory
  only. The `userId` goes to `localStorage` and the staging token to `sessionStorage`, and every storage access is
  wrapped in try and catch. The response of `POST /api/v1/dev/tokens` gives everything, so the app never decodes the
  JWT. After a 401 the login form shows the staging token from the tab, so the user only presses "Log in". Logging
  out clears the session, the staging token and the query cache, and keeps the `userId`.
- **Re-minting (C1).** `startRemint` returns a stop function that clears the timer, the store listener and the
  visibility listener. A re-mint still in flight when it stops, or when the user logs out, does not write its answer.
  A re-mint refused with 401 or 403 sends the user to the login page. Any other failure tries again 15 s later. A
  re-mint needs only the `userId` and the staging token, not the old token, so it also works after expiry. The timer
  keeps running in a hidden tab, which costs one request every 14 minutes per open tab.
- **The redirect check.** `safeRedirect` resolves the target against the app's origin and keeps it only when the
  origin stays the same. A plain string check would miss `/\evil.example`, which the URL parser reads as
  `//evil.example`, and `/<tab>/evil.example`, from which it drops the tab. A redirect to `/login` becomes `/`.
- **Shell, role gate and Overview.** A Mantine AppShell with the name, the role and "Log out", and a nav that lists
  only Overview. `RequireRole` hides UI from roles that may not use it. A 403 shows "No permission" and keeps the
  session. The Overview page shows four counts and the computed time, and asks again every 10 s while the tab is
  visible. TanStack Query pauses the interval in a hidden tab. It has a loading state, an error state with "Server
  unreachable" for a network error, and a notice above the last numbers when a later refresh fails.
- **Tests.** 71 Vitest tests. Line coverage is 98 % in `src/api` and 98 % in `src/auth`, against the 80 % gate.

  | Case | Where |
  |---|---|
  | TC-12-API-01 | `src/api/errors.test.ts`, and `client.test.ts` for the client's network error and 429 |
  | TC-12-API-02 | `src/app.test.tsx`, "a 401 on a query" |
  | TC-12-AUTH-01 | `src/auth/remint.test.ts`, with the C1 visibility re-mint and the cleanup cases |
  | TC-12-AUTH-02 | `src/auth/roles.test.tsx` for the hidden element, `src/app.test.tsx` for the 403 |
  | TC-12-CON-01 | The `Admin web` job: generate, then typecheck. `src/test/contract.ts` holds three handlers the contract forbids, each under `@ts-expect-error`, so the typecheck fails if the mocks stop being checked |
  | Redirect check | `src/auth/redirect.test.ts` |
  | E2E smoke | `e2e/overview.spec.ts` |

- **The E2E job.** It builds `installDist`, starts Postgres 16 as a service container and runs the server with
  `APP_ENV=test` and a JWT secret made for the run. CI has no road graph, so the job waits for `/health/live`, not
  `/health/ready`. Playwright runs Chromium against `vite preview`. The smoke test logs in as OPERATOR, waits for the
  four counts, and compares them and `computedAt` with the API's own answer until both come from one computation. It
  also fails on any console error, so a Content Security Policy violation fails it. That is a first part of
  TC-12-SEC-01. The report, the traces and the server log are uploaded on failure.
- **`clients/ts/` is gone,** with its `ts-client` job and the `rts-client-<sha>` artifact. Dependabot has an `npm`
  entry for `/admin-web`, weekly, with minor and patch updates grouped.
- **Differences from the plan.**
  - MSW handlers are typed with `openapi-msw`, a small wrapper made for openapi-typescript's `paths`. Writing the same
    types by hand would be more code to keep in step with MSW.
  - The contract does not list two answers the login meets: the staging gate's 401 and the plain 404 where the route is
    not mounted. Their mocks use `response.untyped`. Adding the `X-Staging-Token` header and the 401 to
    `/api/v1/dev/tokens` in `rts-v1.yaml` is a small follow-up.
  - No page has an ADMIN-only element yet, so the first half of TC-12-AUTH-02 tests `RequireRole` on its own. The 12c
    action buttons use it.
  - The build is one 563 kB chunk (172 kB gzipped), and Vite warns above 500 kB. Splitting by route fits once 12c to
    12e add pages, and 12d should load MapLibre only on the map page.

## As built (12c)

The four choices approved on 2026-11-20 are built as chosen:

| # | Choice |
|---|---|
| C1 | Drivers and Audit page by keyset with "First page", "Previous" and "Next", never page numbers. The URL holds the filters and the current `cursor`. The cursors of the earlier pages, the trail, live in the history entry's state (`cursorTrail`, `src/routes/paging.ts`), so a reload and the Back button keep them and the URL stays short. "Previous" shows only with a trail. A shared link has none, so it shows "First page". A filter change opens a new history entry without a cursor and without a trail |
| C2 | All three command kinds. SET_CONSTRAINT takes the highest accuracy and the shortest interval. REGISTER_GEOFENCES has rows of id, latitude, longitude and radius that can be added and removed, checked against the `Geofence` limits. FLUSH_QUEUE takes nothing else |
| C3 | The driver list never refreshes on its own: `staleTime: Infinity`, no refetch on focus or reconnect. It shows "As of" from `FleetPage.at` and a Refresh button. A change to a driver invalidates the list, which then reads again when it is shown. Driver detail asks again every 10 s while the tab is visible, and stops after a 400, 403 or 404, which asking again would not change |
| C4 | Every action dialog needs a reason that is not blank before it sends anything: disable, enable and each command kind |

- **The pages.** Drivers (`/drivers`) shows name, online, account, last seen, battery and trip stage, with the filters
  `online`, `onTrip` and `q` in the URL. A row opens the driver. ADMIN gets "Export CSV". Driver detail
  (`/drivers/$driverId`) shows the account, position, device, trip, latest health, and the 10 recent sessions and
  trips. ADMIN also gets "Disable driver" or "Enable driver", the command list with "Send a command", and a "Health"
  button per session. Audit (`/audit`, ADMIN) filters by action, target type, target id, actor id and days, shows the
  actor as a short id with the role, and links a DRIVER target to the driver. Reports (`/reports`, ADMIN) shows the
  provider groups for whole days, the last 7 by default, with CSV export. An OPERATOR who opens Audit or Reports by
  URL gets "No permission", and the page sends no request.
- **Actions.** `ReasonDialog` (`src/components`) is the one confirm-with-reason dialog. While the mutation runs, its
  submit button shows a loader and ignores clicks, and the dialog cannot be closed, because `POST …/commands` has no
  `Idempotency-Key` and a second click would store a second command. Mutations never retry (the 12b default). After a
  disable or enable, the page writes the returned `DriverAccount` into the cached detail, then invalidates the detail
  and the lists. After a command, the command list reads again and asks every 3 s while any command is PENDING or
  SENT and the tab is visible (`refetchInterval` as a function of the data), and stops once all are final.
- **Session health.** The "Health" button opens the session's stored health. A 404 SESSION_HEALTH_NOT_FOUND shows
  "Not computed yet" with "Compute now", which posts to the same path and shows the answer. "Compute now" has no
  reason dialog: the route takes no body, and C4 names disable, enable and the commands.
- **CSV.** `downloadCsv` (`src/api/download.ts`) takes an `api.GET(…, { parseAs: 'blob' })` call, so the client's
  middleware adds the token and turns a failed fetch into an `ApiError`. A non-2xx answer becomes an `ApiError` too.
  It names the file from `Content-Disposition`, clicks a hidden link to a blob URL, and revokes the URL 40 s later.
  Revoking at once can cancel the download in some browsers. FileSaver.js waits 40 s for the same reason.
- **Errors.** `describeError` has text for every code the 12c routes can return, taken from the Kotlin enums behind
  them: the five FORBIDDEN codes (all "No permission"), DRIVER_NOT_FOUND, REASON_REQUIRED, SESSION_HEALTH_NOT_FOUND,
  INVALID_COMMAND, VALIDATION_FAILED, MALFORMED_REQUEST, PAYLOAD_TOO_LARGE, NOT_FOUND and INTERNAL_ERROR. `ApiError`
  now keeps the `errors` list of a VALIDATION_FAILED answer as `fieldErrors`, and the text lists the fields.
- **Lazy pages.** Every page, the login and Overview too, loads as its own chunk with `lazyRouteComponent`. The search
  schemas stay in the main chunk, because the router checks a link's search before the page's code arrives. No chunk
  is over 500 kB now, so the 12b warning is gone.

  | Chunk | Size | Gzipped |
  |---|---|---|
  | Entry (React, router, TanStack Query, the shell) | 303.6 kB | 94.1 kB |
  | Shared, loaded with the entry (`client`, `roles` in the build output) | 224.0 kB | 71.3 kB |
  | Driver detail | 57.0 kB | 19.3 kB |
  | Audit | 40.9 kB | 13.0 kB |
  | Login | 7.2 kB | 3.0 kB |
  | Reports | 4.9 kB | 2.0 kB |
  | Drivers | 4.3 kB | 1.7 kB |
  | Overview | 1.9 kB | 0.9 kB |

  The first page still loads the entry and the two shared chunks, 528 kB (165 kB gzipped) against 563 kB (172 kB) in
  12b. Library code is nearly all of it: React DOM, zod, TanStack Router and Query, and Mantine (measured from the
  source map). Each page adds its own chunk and the Mantine parts only it uses. 12d should still load MapLibre only on
  the map page.
- **Contract fix.** `POST /api/v1/dev/tokens` lists the optional `X-Staging-Token` header and the staging gate's 401
  (`UNAUTHORIZED`, Problem Details). `StagingGateIT` now checks its 401 and its 200 against the contract. The login
  sends the header as a typed `params.header`, and the two 12b mocks of that 401 are typed. The mock of the plain 404
  where the route is not mounted stays untyped, because the contract does not list it.
- **Tests.** 147 Vitest tests. Line coverage is 100 % in `src/api` and 98 % in `src/auth`. MSW handlers for every 12c
  route are in `src/test/server.ts`, the typed bodies in `src/test/fixtures.ts`, and `recordRequests()` lists every
  request a test made, so a test can show that an OPERATOR sends no ADMIN request.

  | Case | Where |
  |---|---|
  | TC-12-UI-01 | `src/routes/drivers/DriversPage.test.tsx`: filters from the URL, "Next" with the response cursor, "Previous", Back, a filter change, a shared link |
  | TC-12-UI-02 | `src/routes/drivers/DriverDetailPage.test.tsx`: the reason is required, one request per action, the driver read again, the 403 |
  | TC-12-UI-04 | `src/routes/drivers/DriversPage.test.tsx` and `src/api/download.test.ts` |
  | TC-12-AUTH-02, second half | `src/app.test.tsx` for the nav, `DriverDetailPage.test.tsx` for the buttons and the requests, `AuditPage.test.tsx` and `ReportsPage.test.tsx` for the pages |
  | Command form | `DriverDetailPage.test.tsx` per kind, and `src/routes/drivers/commandForm.test.ts` |
  | Command polling | `src/routes/drivers/CommandsPanel.test.tsx`, with fake timers |
  | TC-12-E2E-02, TC-12-E2E-03 | `e2e/drivers.spec.ts` |

- **The E2E job** builds the simulator's `installDist` next to the server's and starts `simulator fleet --drivers 3
  --dashboards 0 --duration 30m` before Playwright. The tests wait for the fleet read model with `expect.poll` on the
  API, never with sleeps. TC-12-E2E-02 creates its own driver through the dev login, so it never disables a simulated
  driver. TC-12-E2E-03 sends FLUSH_QUEUE to an online simulated driver and waits for APPLIED. Both fail on any console
  error. The simulator's log is uploaded on failure.
- **Differences from the plan and the brief.**
  - Driver detail decides "enabled" from the account's `disabledAt`, not from `driver.enabled`. The detail reads the
    account in the same request, but `driver.enabled` comes from the fleet read model, which an outbox event updates a
    moment later. Right after a disable, the refetch said "enabled" again. TC-12-E2E-02 found it. The unit test's mock
    now lags the same way. PR #61 changed the server. The detail now takes `driver.enabled` from the account when it
    reads one, so the two fields agree. The page and its mock still use `disabledAt`, which stays correct.
  - The server kept a command's reason only for SET_CONSTRAINT, inside the command, and dropped it for
    REGISTER_GEOFENCES and FLUSH_QUEUE. PR #61 records the reason on the COMMAND_ISSUED audit row for every kind, so
    the reason the dashboard asks for (C4) now reaches the audit log.
  - The date filters of Audit and Reports are whole days in the browser's time zone, kept in the URL as `YYYY-MM-DD`.
    The API gets the instants of local midnight: the first day's, and the one after the last day. A shared link opened
    in another time zone covers other instants. The 31-day check uses the same instants as the request.
  - TC-12-E2E-03 says APPLIED, not DELIVERED. `CommandStatus` has no DELIVERED, and the simulator answers APPLIED
    (`DriverClient.kt:215`).
  - The code answers INVALID_COMMAND with 422, which the contract did not list for `POST …/commands` until PR #61. The
    body check finds the same faults first and answers 400, so the 422 cannot happen today.
  - jsdom lacks `document.fonts`, which Mantine's autosize textarea uses, and blob URLs. `src/test/setup.ts` adds both.

## As built (12d)

The four choices approved on 2026-11-20 are built as chosen:

| # | Choice |
|---|---|
| C1 | MapLibre's worker runs from the app's own origin. `setWorkerUrl` points at `maplibre-gl-worker.mjs`, which Vite copies into `dist/assets/` through a `?url` import (`src/map/FleetMap.tsx`). `csp.ts` says `worker-src 'self'` and adds `https://tiles.openfreemap.org` to `connect-src` and `img-src`. Checked in the browser under `vite preview`: the style, sprites, glyphs, vector tiles and raster tiles load from that one host, with no violation. Under the 12b policy the same page logs two violations, the worker and the style, so the E2E console check catches a wrong policy |
| C2 | No clustering in the browser. Below the server's 5 000-driver threshold every driver is a dot. Only `fleet_cluster` cells draw as clusters |
| C3 | No smooth movement. Markers jump on each delta, every 2 s |
| C4 | The E2E test sees the map through the status line's driver count, the `?driver=` URL and the side panel's live position. It also checks that MapLibre's canvas is on the page and the WebGL message is not. No hook on `window` |

The defaults the user accepted are built as well. The URL holds `online`, `onTrip`, `lat`, `lng`, `zoom` and `driver`
(`src/routes/map/search.ts`). A map move replaces the history entry, and a filter change or a selection adds one. A
shared link opens the same view. The 12c command form has no geofence map picker.

- **The fleet client (`src/fleet/client.ts`).** A class outside React with the states `connecting`, `live`, `waiting`,
  `paused`, `forbidden` and `stopped`. It gets a ticket, opens `/ws/v1/admin/fleet?ticket=` on the page's own origin,
  and sends `fleet_subscribe` once the socket is open. Each new socket sends the current area and filters again. A close
  waits with full-jitter backoff (`src/fleet/backoff.ts`, 1 s doubling to 30 s, as the simulator's `Backoff.kt`), then
  gets a new ticket. 1013 reconnects at once, 4403 stops with "No permission", and 1006 waits like any drop. A hidden
  tab closes the socket with 1000. Visible again, the client connects with a new ticket and gets a fresh snapshot. The
  client takes a socket factory, a visibility source and a random function, so tests pass fakes.
- **The ticket request (`src/fleet/ticket.ts`)** runs as a TanStack Query mutation through `MutationObserver`. The query
  client's mutation cache then applies the 12b rules: a 401 clears the session and opens the login page, and the
  request never retries on its own. The fleet client stops after a 401, shows "No permission" after a 403, and waits
  at least `Retry-After` after a 429.
- **Schemas (`src/fleet/messages.ts`).** zod schemas for `fleet_snapshot`, `fleet_delta`, `fleet_cluster` and `error`,
  with the contract's ranges. They follow its compatibility rules: unknown fields are dropped and unknown error codes
  pass. `driverId` is any non-empty string, because a UUID check would drop a whole snapshot over one odd id. A frame
  that is not JSON, not text, or fails its schema is logged with `console.warn` and dropped, and the socket stays.
- **The marker store (`src/fleet/store.ts`).** A Zustand vanilla store. A snapshot replaces every marker, a delta
  upserts and removes, clusters replace the clusters and clear the markers, and a snapshot after clusters restores
  them. A delta while clustered changes nothing. Every change makes a new `Map`, so readers compare by reference.
  `syncGeoJson` (`src/fleet/geojson.ts`) converts at most once per animation frame and sets a source only when its part
  of the store is a new object. The status line and the side panel subscribe to the few values they show.
- **The map (`src/map/`).** MapLibre GL JS 6.19.0 with OpenFreeMap's liberty style and its attribution. Layers, bottom
  to top: cluster circles sized by count, cluster counts, a ring for the selected driver, the driver dots (green
  online, blue on a trip, grey offline; offline wins), and heading arrows from zoom 12 for drivers with a bearing. The
  arrow is an SDF image drawn on a canvas, so the app needs no sprite file. The map is flat, north up and one world
  wide, so the bbox matches what the operator sees and never crosses the antimeridian. A click within 6 px of a dot
  selects the driver, and a cluster click zooms in 2 levels. The first subscription goes out at once, before the base
  style loads. Later ones go out after the map has stood still for 300 ms. If the base style fails to load, the map
  switches to a plain background and says so, and the drivers still draw. Without WebGL 2 the page shows a message,
  and the status line and the side panel still work.
- **The page (`src/routes/map/`).** Filters `online` and `onTrip`, a status line (connection, drivers in view or the
  clusters' total, the time of the last update, a colour legend), the map, and a side panel for `?driver=`. The panel
  takes the name and the account state from the 12c `driverQuery`, and the live position, heading, trip stage, battery
  and last seen time from the store. A driver outside the area shows its last known position from the API. The Drivers
  page and the map share the filter options in `src/routes/flags.ts`.
- **Chunks.** The page and MapLibre load only on `/map`. MapLibre has a chunk of its own
  (`build.rolldownOptions.output.codeSplitting`), so an app change keeps its file name and the browser's cached copy.
  `chunkSizeWarningLimit` is 1 100 kB: MapLibre is the one chunk above 500 kB, and the warning still fires if it ever
  lands in the entry.

  | Chunk | Size | Gzipped |
  |---|---|---|
  | First load: entry, `client`, `route` and `roles` | 532.0 kB | 166.7 kB |
  | Map page (`MapPage`) | 19.8 kB | 7.8 kB |
  | MapLibre (`maplibre`) | 1 060.1 kB | 292.3 kB |
  | MapLibre CSS | 71.3 kB | 10.3 kB |
  | MapLibre worker (`maplibre-gl-worker`, an asset) | 550.8 kB | not reported |

  The first load was 528 kB (165 kB gzipped) in 12c, so the map adds 4 kB (2 kB gzipped) to it.
- **Tests.** 214 Vitest tests. Line coverage is 100 % in `src/api`, 98 % in `src/auth` and 99 % in `src/fleet`, which
  now has the same 80 % gate. The socket fixtures are the protocol's golden frames
  (`protocol/src/test/resources/golden/`), parsed with the app's own schemas in `src/test/fleet.ts`, so a contract
  change breaks the unit tests.

  | Case | Where |
  |---|---|
  | TC-12-FL-01 | `src/fleet/store.test.ts` |
  | TC-12-FL-02 | `src/fleet/client.test.ts`: a close, the wait, a new ticket, and the current area on the new socket |
  | TC-12-FL-03 | `client.test.ts` for 4403 and 1013, `src/routes/map/MapPage.test.tsx` for "No permission" on the page |
  | TC-12-FL-04 | `client.test.ts`: hidden closes with 1000 and cancels a pending retry or ticket, visible reconnects |
  | TC-12-FL-05 | `client.test.ts`: a bad snapshot, a frame that is not JSON, an unknown type and a binary frame |
  | 1006 and the backoff reset | `client.test.ts`: waits of 0.5, 1 and 2 s with a fixed random, back to 0.5 s after a snapshot or clusters, capped at 30 s |
  | Page | `MapPage.test.tsx`: the real client over MSW's WebSocket mock (`fleetSocket` in `src/test/server.ts`) and a stub map |
  | Layers | `src/map/layers.test.ts` runs MapLibre's style validator over the layers |
  | TC-12-E2E-01 | `e2e/map.spec.ts` |

- **The E2E job** starts `simulator fleet --drivers 50` and runs the server with `HTTP_RATELIMITPERMINUTE=3000`. The
  drivers, the browser through the preview proxy and the tests all reach the server from 127.0.0.1, so they share one
  limit. TC-12-E2E-01 waits until the API sees all 50 drivers online, opens a shared link with `online=true`, a view of
  Hanoi and a driver, and expects "50 drivers in view" within 5 s of the login. It then polls the panel's position until
  it changes. `serveBlankTiles` (`e2e/helpers.ts`) answers every request to the tile host with a blank style or an empty
  body through `page.route`. The browser checks each request against the policy first, so the test still fails on a
  policy without the tile host. The 12c tests pass with 50 drivers.
- **TC-12-PERF-01** ran on the Mac (Apple M4, Chromium with ANGLE on Metal) against `simulator fleet --drivers 5000
  --dashboards 0`, with all 5 000 drivers in view and a delta every 2 s. Over 20 s the page drew 100 frames a second,
  the slowest frame took 12 ms, and no long task ran. While panning for 10 s it drew 99.9 frames a second, and the
  slowest frame took 22 ms. The target is 30.
- **Differences from the plan and the brief.**
  - MapLibre 6 has no separate CSP build. Its one ESM build starts a module worker from `maplibre-gl-worker.mjs`, found
    next to its own file. A bundler moves that file, so `setWorkerUrl` must point at the copy. The worker holds the one
    `eval` in MapLibre, inside `importScriptInWorkers`, which loads plugins. The app never calls it.
  - MapLibre 6 needs WebGL 2, not WebGL 1. The page makes a test context, releases it at once, and catches the
    constructor's `GPUInitializationError` too.
  - No Chromium flag was needed for WebGL in CI. Playwright 1.65 already starts Chromium with
    `--enable-unsafe-swiftshader`, and a check in the `mcr.microsoft.com/playwright:v1.65.1-noble` image got a WebGL 2
    context with its default switches.
  - The backoff starts over after clusters too, not only after a snapshot, because clusters also prove the subscription
    works.
  - `img-src` has the tile host as the plan says, though Chromium loads the raster tiles and the sprite with `fetch`,
    which `connect-src` covers.
  - The simulator sends no bearing, so the heading arrows were not seen against it. The layer passes the style
    validator.
  - Server clusters were not seen on a live map. The PERF run had exactly 5 000 drivers, which the server still sends
    one by one. A second run with 5 100 drivers, started right after it on the same server, showed no driver online
    in the four minutes I watched. The fleet read model most likely lagged while the server worked through the first
    run's sessions and events, and the 50-driver E2E run right after a restart met the same lag once. The store,
    GeoJSON and page tests cover clusters with the golden `fleet_cluster` frame, and the cluster layers pass the style
    validator.
  - The status line is a labelled region, not a live region: a screen reader would read it out every 2 s.

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
