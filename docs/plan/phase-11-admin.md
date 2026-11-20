# Phase 11 — Admin API & Live Fleet

Branch: `feat/p11-admin` · Depends on: 3, 4 (5 and 9 enrich it) · Can run before phase 10 · Consumer: `RouteTrackingAdmin` web app (separate repo)

## Goal

Give operators a fleet-wide view: every driver's status and live position on a map, all trips, driver
detail with health and commands, and headline numbers — through an admin REST API and an admin
WebSocket designed for a browser dashboard. Every admin action is audited.

## Scope

**In:** `fleet` context (read model across contexts), PostGIS for spatial queries, admin REST API
(drivers, trips, overview, audit), live fleet WebSocket with viewport subscription and throttling,
browser-friendly auth (CORS, WebSocket tickets), roles `ADMIN` / `OPERATOR`, audit log, driver
enable/disable, OpenAPI-generated TypeScript client for the dashboard.

**Out:** the dashboard implementation details (its own repo and plan), real SSO/OIDC login (noted as follow-up),
multi-tenant fleets (one fleet only).

## Design

### Why a separate read model

Admin screens ask questions no single context answers: "online drivers inside this map area, with battery
and current trip, sorted by last seen". Joining across contexts' tables would break the context rules
and be slow. Instead, `fleet` keeps a **projection** — a denormalized table updated from events
(CQRS-style read model):

| Event (source) | Updates in `fleet_drivers` |
|---|---|
| `FixesAccepted` (tracking) | `last_position` (latest fix), `last_seen_at`, `speed_mps`, `bearing_deg` |
| `DeviceStatusReported` (tracking) | `battery_pct`, `is_charging`, `motion` |
| `SessionClosed` (tracking) | `online = false` |
| `TripStatusChanged` (trip) | `current_trip_id`, `trip_status` |
| `DriverDisabled/Enabled` (identity) | `enabled` |

```sql
-- V11__fleet.sql
create extension if not exists postgis;
create table fleet_drivers (
  driver_id uuid primary key, display_name text not null, enabled boolean not null default true,
  online boolean not null default false, last_seen_at timestamptz,
  last_position geography(Point, 4326), speed_mps real, bearing_deg real,
  battery_pct smallint, is_charging boolean, motion text,
  current_trip_id uuid, trip_status text, sdk_version text, provider text,
  updated_at timestamptz not null, last_event_id bigint not null default 0);
create index fleet_drivers_position on fleet_drivers using gist (last_position);
create index fleet_drivers_online on fleet_drivers (online, last_seen_at desc);
```

- Projection handlers are **idempotent** (they skip events with id ≤ `last_event_id`), because the outbox is at-least-once.
- Position updates to the table are **coalesced**: at most one write per driver per 5 s (the in-memory
  cache below has the latest anyway), so 1 000 drivers don't mean 1 000 writes/s.
- A rebuild command (`POST /api/v1/admin/fleet/rebuild`, ADMIN) replays from source data if the projection is ever wrong.
- Postgres image changes to `postgis/postgis:16-3.4` in compose, Testcontainers and staging.

### Live fleet channel

`/ws/v1/admin/fleet` (roles ADMIN, OPERATOR):

```
client → fleet_subscribe  { bbox: [minLng, minLat, maxLng, maxLat], filters: { online: true, onTrip: null } }
server → fleet_snapshot   { drivers: [DriverMarker, ...], at }                  // everything in the bbox now
server → fleet_delta      { upserts: [DriverMarker...], removes: [driverId...], at }   // every 2 s, only changes
client → fleet_subscribe  { bbox: ... }                                          // map panned: replaces the subscription
```

`DriverMarker = { driverId, lat, lng, bearingDeg, online, tripStatus?, batteryPct?, lastSeenAt }`.

- Source: `FleetPositionCache` (latest marker per driver, in memory, fed by `FixesAccepted` after commit).
- **Conflation**: each subscriber gets one delta per tick (2 s) containing only markers that changed
  and are inside its bbox — not one message per fix. Bandwidth stays flat as the fleet grows.
- Snapshot on subscribe comes from the projection (PostGIS `ST_Intersects(last_position, ST_MakeEnvelope(...))`), so a fresh dashboard is correct even after a restart.
- Max bbox area and max markers per snapshot (e.g. 5 000); beyond that the server returns
  `fleet_cluster` counts per grid cell instead of individual markers.
- Phase 10: `FleetPositionCache` gets a Redis adapter (listed in phase 10's instance-local table).

### Admin REST API (`/api/v1/admin`, in `rts-v1.yaml` under tag `admin`)

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/drivers?online=&onTrip=&q=&bbox=&sort=lastSeen&cursor=&limit=` | OPERATOR | Paginated, filterable driver list from the projection |
| GET | `/drivers/{id}` | OPERATOR | Detail: profile, live status, recent sessions, recent trips, latest health (phase 9) |
| POST | `/drivers/{id}/disable` / `/enable` `{reason}` | ADMIN | Disabled drivers can't open sessions; open sockets closed with 4403 |
| POST | `/drivers/{id}/commands` | ADMIN | Wraps phase 5 `CommandService.issue` |
| GET | `/trips?status=&driverId=&guestId=&from=&to=&cursor=` | OPERATOR | All trips |
| POST | `/trips/{id}/cancel` `{reason}` | OPERATOR | Cancel as operator (new actor type, allowed for non-terminal states) |
| GET | `/overview` | OPERATOR | Active drivers, active trips, fixes/min, avg ACK latency, rejected-fix ratio; cached 10 s |
| GET | `/audit?actorId=&action=&targetId=&from=&to=&cursor=` | ADMIN | Audit log |
| GET | `/drivers.csv` | ADMIN | Export (streamed) |

### Audit log

```sql
create table audit_log (id bigserial primary key, at timestamptz not null, actor_id uuid not null, actor_role text not null,
  action text not null, target_type text not null, target_id text not null, reason text,
  details jsonb not null default '{}', request_id text, trace_id text);
create index audit_log_target on audit_log (target_type, target_id, at desc);
```

- Written by the application service **in the same transaction** as the action (an action without an
  audit row cannot commit).
- Append-only: no update/delete endpoints; the DB role used by the app has no `UPDATE/DELETE` on this table.

### Browser concerns

| Concern | Solution |
|---|---|
| Cross-origin calls from the dashboard | CORS plugin: allow only configured origins (`ADMIN_ORIGINS`), credentials off, methods/headers explicit |
| Browsers can't set headers on WebSocket | `POST /api/v1/admin/ws-tickets` → single-use ticket valid 30 s, bound to the user; socket connects with `?ticket=`. The JWT never appears in a URL or access log. |
| Token storage | Dashboard keeps the access token in memory; short expiry (15 min) for admin roles. Real login via an OIDC provider (Keycloak / Auth0 / Clerk) is a follow-up; the verifier interface already supports RS256/JWKS |
| API client | CI generates a TypeScript client from `rts-v1.yaml` (`openapi-typescript`) and publishes it as an artifact; the dashboard repo pins a version |
| Security headers | `X-Content-Type-Options`, `Referrer-Policy`, no caching of admin responses (`Cache-Control: no-store`) |

### Dashboard (separate repo, for reference)

Superseded by [phase 12](phase-12-admin-dashboard.md), which plans the dashboard in full and proposes a folder in this
repository instead of a separate one. The paragraph below is the original sketch.

`RouteTrackingAdmin`: React + TypeScript + Vite, MapLibre GL (map), TanStack Query (REST cache),
a small WS client for the fleet channel, Playwright for E2E against staging. Pages: Overview · Live map ·
Drivers (list, detail) · Trips (list, live view reusing the guest channel with operator rights) · Audit.
Deployed as static files (Cloudflare Pages / Netlify) pointing at the staging API.

## Corrections to this plan (approved 2026-11-17)

The plan was written before phases 4 to 10. These replace the parts they name:

- Migrations start at V19, not V11.
- There is no `SessionClosed` event. "Online" is computed from `last_seen_at` against the stale time.
- No PostGIS. Plain `lat`, `lng` columns, `BETWEEN` and a btree index answer a map area over at most about 10 000
  drivers; clusters use `floor(lat / cell)`. PostGIS comes when a radius or polygon query needs it.
- No in-memory `FleetPositionCache`. Since 10a the projection handlers run on whichever instance holds the relay
  lock, so a cache would be full on one instance and empty on the others. The projection table is the source; each
  instance's 2 s tick reads the rows changed since its last tick. Position writes are not coalesced.
- WebSocket tickets live in Postgres and are redeemed with `DELETE … RETURNING`, single use on any instance.
- Disabling a driver closes their sockets on every instance through `DriverLink.disconnect` (Redis, as commands).
- `audit_log` is append-only by a trigger, not by grants: the app's user owns the table, and an owner can grant
  itself any right back.
- Slices: 11a roles, audit and driver disable; 11b fleet projection and driver API; 11c the live fleet socket,
  tickets, CORS and headers; 11d operator cancel, trip list, TypeScript client, simulator fleet, load check.

## Tasks

- [x] T11.1 ~~PostGIS image everywhere; `V11__fleet.sql`; Exposed column type for `geography`.~~ Replaced by the corrections: `V20__fleet_drivers.sql` with plain `lat`, `lng`.
- [x] T11.2 `fleet` context: projection handlers (idempotent by newer-wins, not coalesced), the driver query, rebuild command.
- [x] T11.3 ~~`FleetPositionCache`~~ + fleet socket with snapshot, conflated deltas, clustering fallback. Replaced by the corrections: no cache, one tick per instance reads `fleet_drivers`.
- [x] T11.4 Roles `OPERATOR`, operator actor in trip aggregate; driver enable/disable in `identity`. Roles and disable in 11a, the operator actor in 11d.
- [x] T11.5 Admin REST endpoints + OpenAPI; CSV export. Driver list, detail, overview, CSV and rebuild in 11b; the trip list and the operator cancel in 11d.
- [x] T11.6 Audit log (table, writer used inside services, query endpoint, DB grants).
- [x] T11.7 CORS, WebSocket tickets, admin token expiry, security headers. Token expiry in 11a, the rest in 11c.
- [x] T11.8 CI job generating and publishing the TypeScript client. Published as a build artifact, not to npm (11d).
- [x] T11.9 Seed script: 500 simulated drivers across the city for dashboard development (`simulator fleet --drivers 500`).

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-11-PRJ-01 | Service | `FixesAccepted` → projection row has the latest position and `online = true` | P0 |
| TC-11-PRJ-02 | Service | Same event delivered twice → one update (skipped by `last_event_id`) | P0 |
| TC-11-PRJ-03 | Service | 20 position events for one driver within 5 s → at most one DB write; cache holds the latest | P1 |
| TC-11-PRJ-04 | Service | `TripStatusChanged(→COMPLETED)` clears `current_trip_id` | P0 |
| TC-11-PRJ-05 | Integration | Rebuild from source data produces the same rows as incremental updates (compare tables) | P1 |
| TC-11-GEO-01 | Integration | Bbox query returns exactly the drivers inside it; drivers on the edge are included; antimeridian not required (document) | P0 |
| TC-11-GEO-02 | Integration | `EXPLAIN` of the bbox query uses the GiST index with 10 000 rows | P1 |
| TC-11-FL-01 | API | Subscribe → `fleet_snapshot` contains only drivers in the bbox matching filters | P0 |
| TC-11-FL-02 | API | Driver moves inside bbox → next tick delta has one upsert; driver leaves bbox → next delta has one remove | P0 |
| TC-11-FL-03 | Unit | 50 fixes from one driver between ticks → one upsert with the last position (conflation) | P0 |
| TC-11-FL-04 | API | Re-subscribe with a new bbox → new snapshot; markers from the old bbox no longer sent | P0 |
| TC-11-FL-05 | API | Bbox too large or > 5 000 markers → `fleet_cluster` instead of markers | P2 |
| TC-11-FL-06 | API | GUEST or DRIVER token/ticket → rejected 4403 | P0 |
| TC-11-API-01 | API | Driver list filters (`online`, `onTrip`, `q`) and keyset pagination work together; no duplicates across pages | P0 |
| TC-11-API-02 | API | Disable driver → their open socket closes with 4403; new `hello` rejected; enable restores | P0 |
| TC-11-API-03 | API | Operator cancels an IN_PROGRESS trip → 200; guest and driver receive CANCELLED with `cancelledBy = OPERATOR` (the reason stays in the audit log) | P1 |
| TC-11-API-06 | API | Trip list filters (`status` repeated, `driverId`, `guestId`, `from`, `to`) and keyset pagination work together | P0 |
| TC-11-API-04 | API | `/overview` numbers match seeded data; second call within 10 s served from cache | P1 |
| TC-11-API-05 | API | CSV export streams all drivers with a header row | P2 |
| TC-11-AUD-01 | Service | Every admin mutation writes exactly one audit row with actor, target, reason, requestId | P0 |
| TC-11-AUD-02 | Integration | Action fails after the audit insert → both rolled back (no orphan audit row) | P0 |
| TC-11-AUD-03 | Integration | App DB role cannot `UPDATE` or `DELETE` `audit_log` (permission error) | P1 |
| TC-11-SEC-01 | Security | Authorization matrix extended: every admin route × {anonymous, guest, driver, operator, admin} | P0 |
| TC-11-SEC-02 | API | CORS preflight from an allowed origin → allowed; from another origin → no `Access-Control-Allow-Origin` | P0 |
| TC-11-SEC-03 | API | WS ticket: works once; reuse → rejected; after 30 s → rejected; ticket of user A can't be used by B's connection | P0 |
| TC-11-SEC-04 | API | Admin responses carry `Cache-Control: no-store` | P2 |
| TC-11-CON-01 | Contract | Admin endpoints conform to OpenAPI; fleet messages validate against AsyncAPI | P0 |
| TC-11-CI-01 | CI | TypeScript client generated from the spec compiles (`tsc --noEmit`) | P1 |
| TC-11-LOAD-01 | Load | 1 000 simulated drivers + 20 dashboards on the live map → each dashboard receives a delta every 2 s ± 0.5 s, ingest SLO unchanged | P1 |

## Test implementation notes

- **Fleet tick in tests:** the tick is a platform job with an injected dispatcher; API tests set the
  tick to 100 ms in the test profile, service tests use virtual time.
- **Spatial fixtures:** seed drivers at known coordinates around a bbox (inside, on each edge, outside)
  so TC-11-GEO-01 expectations are exact.
- **Projection vs rebuild (TC-11-PRJ-05):** run the simulator scenario, snapshot the table, truncate,
  rebuild, compare — the best single check that the event handlers are correct.

## As built (11a)

- `Role.OPERATOR` (V19 widens the check). `Role.isStaff` is OPERATOR or ADMIN. An operator sees every trip and its
  track like an admin, has no trip history, and cannot use the ADMIN-only endpoints. Staff tokens last 15 minutes
  (`jwt.staffTokenTtl`); others keep `jwt.tokenTtl`.
- `audit_log` (V19) with indexes by target and by actor, newest first. A trigger refuses UPDATE, DELETE and
  TRUNCATE for every role. `AuditLog.record` joins the caller's transaction and stores the request id from the MDC.
  `GET /api/v1/admin/audit` (ADMIN) filters by actor, action, target and time, paged by id.
- Audited actions: `COMMAND_ISSUED` (an admin's command, not the server's own), `TRIP_SUMMARY_RECOMPUTED`,
  `SESSION_HEALTH_RECOMPUTED`, `DRIVER_DISABLED`, `DRIVER_ENABLED`.
- `POST /api/v1/admin/drivers/{id}/disable` (a reason is required) and `/enable` (ADMIN). The account change and its
  audit row commit together. After the commit, `DriverLink.disconnect` closes the driver's sockets with 4403 on
  every instance: locally, and through Redis with a marker message on `driver:{id}`. `DriverSocket` refuses the
  `hello` of a disabled driver through identity's public `DriverAccounts`. A user with no account row counts as
  enabled.
- `AuthorizationMatrixIT` has an OPERATOR column, expected to answer like ADMIN on every trip route, and runs with a
  higher rate limit because 7 callers × every route exceed 300 requests a minute.

## As built (11b)

- A `fleet` context with one table, `fleet_drivers` (V20): name, enabled, newest position, battery, motion and current
  trip per driver, with indexes on `(lat, lng)`, `(last_seen_at DESC NULLS LAST, driver_id)` and `updated_at`. No
  PostGIS. V20 also indexes `session_health` by driver, for the driver detail.
- Four outbox handlers keep it, inside the relay's transaction: `FixesAccepted` (the batch's last fix),
  `DeviceStatusReported`, `TripStatusChanged`, and a new identity event `DriverAccountChanged` (driver id, name,
  enabled, time). Identity writes that event in the same transaction as every DRIVER account change: the dev token
  endpoint, disable and enable. Events without a driver (old sessions, a trip nobody accepted) are skipped.
- Idempotency is "newer data wins", in `FleetDriver` (domain, no database): a position, status or trip stage
  applies only if its time is after the stored one. A repeated or late event writes nothing, not even `updated_at`.
  The end of a trip clears the current trip only if it is that trip. The account carries no time and the last one
  wins: the relay delivers events in order.
- `last_seen_at` is the newer of the fix's recorded time and the status time, the phone's own clock, not the
  server's receive time. That keeps a rebuild equal to the events. Online is `last_seen_at` within
  `events.staleAfter` (60 s), computed on read. `speed_mps` and `bearing_deg` are `real` like `fixes`, so both paths
  round to the same value.
- Other contexts are read only through their public APIs. New methods: identity `DriverAccounts.driver` and
  `drivers`; tracking `DriverActivity` (recent sessions, newest fix and status per driver); trip `DriverTrips`
  (recent trips of a driver, newest trip per driver, active trip count); telemetry `DriverHealth.latestFor`.
- Routes (contract in `rts-v1.yaml`):
  - `GET /api/v1/admin/drivers` (OPERATOR, ADMIN) filters by `online`, `onTrip`, `q` (part of the name, `%` and `_`
    taken literally, or a whole driver id) and `bbox` (edges inside, not across the antimeridian). Keyset paging by
    `(last_seen_at DESC NULLS LAST, driver_id)`, 50 a page, 100 at most.
  - `GET /api/v1/admin/drivers/{driverId}` (OPERATOR, ADMIN): the row with its online flag, the account's disable
    reason, the 10 latest sessions and trips, and the latest computed session health. A driver with an account but
    no row yet still answers; 404 when neither exists.
  - `GET /api/v1/admin/overview` (OPERATOR, ADMIN): online drivers, drivers on a trip, active trips (from the trip
    context), disabled drivers, and `computedAt`. Cached 10 s on each instance; one caller counts, the others wait.
  - `GET /api/v1/admin/drivers.csv` (ADMIN): every driver, streamed 500 rows per transaction, with the CSV rules of
    the provider report. The role is checked before the first byte, so a refusal is still a 403.
  - `POST /api/v1/admin/fleet/rebuild` (ADMIN), audited as `FLEET_REBUILT` with the driver count. One transaction:
    it empties the table first, then reads the sources and writes the rows. Emptying first takes the table lock, so
    an event handler waits and applies its change on top. Reading first would let an event delivered in between be
    written and then wiped.
- Tests: `FleetDriverTest` and `FleetWebTest` (unit); `FleetProjectionIT` (TC-11-PRJ-01, 02, 04),
  `FleetRebuildIT` (TC-11-PRJ-05, drivers acting through the real services), `FleetApiIT` (TC-11-GEO-01, API-01,
  API-04, API-05, detail, audited rebuild), `FleetAuthorizationIT` (TC-11-SEC-01 for the five routes × five
  callers, with a completeness check). TC-11-PRJ-03 and GEO-02 no longer apply: there is no coalescing and no GiST.

## As built (11c)

- `POST /api/v1/admin/ws-tickets` (OPERATOR and ADMIN, others get 403 `TICKET_FORBIDDEN`) answers `{ticket, expiresAt}`:
  32 random bytes in base64url, valid 30 s. `ws_tickets` (V21) stores only the SHA-256 with `user_id`, `role` and
  `expires_at`. `WsTickets.redeem` is one `DELETE ... WHERE hash = ? AND expires_at > now RETURNING user_id, role`,
  so a ticket works once on any instance: two instances racing for it cannot both delete the row. The retention job
  (`RetentionJob`, lock 10_002) also deletes expired tickets and counts them as `rts_retention_deleted_total{table="ws_tickets"}`.
- A second authentication provider, `AUTH_TICKET`, reads `?ticket=` and nothing else. Only the fleet socket uses it.
  A missing, used or expired ticket is answered 401 before the upgrade, like a missing token on the other sockets. The
  driver and guest sockets keep `AUTH_JWT` (header, or `?token=` as before).
- `/ws/v1/admin/fleet` (contract: AsyncAPI channel `adminFleet`). A ticket of a role other than OPERATOR or ADMIN gets
  close 4403. `fleet_subscribe {bbox, filters: {online, onTrip}}` replaces the subscription and answers
  `fleet_snapshot`, read with the 11b driver query (`FleetRepository.page` with the bbox). An invalid bbox gets
  `error` INVALID_MESSAGE and the socket stays open. `bearingDeg` in a `DriverMarker` is optional: a fix may have no
  bearing.
- `FleetLive` is a `BackgroundWorker`, one per instance. Every `fleet.tick` (2 s, 100 ms in tests) it runs one query
  for all its sockets, `FleetRepository.changedSince`: rows with `updated_at` after the oldest subscriber's last
  message minus 5 s, and rows with `last_seen_at` between that time minus the stale time and now minus the stale
  time. The second part finds drivers who just went offline, which writes no row. Each subscriber filters the rows in
  memory against its area, its filters and its sent map (`SentMarkers`, driver id to marker): a different marker is an
  upsert, a driver that left the area or the filters is a remove, a duplicate is dropped. Every subscriber gets a
  `fleet_delta` every tick, empty when nothing changed, so a dashboard can tell a quiet fleet from a dead socket.
- Sends run in their own coroutines and the tick does not wait for them. A subscriber still busy with its last
  message skips the tick (`Mutex.tryLock`). Its next query window starts at its own last message, so nothing is
  lost. A send that takes over `fleet.sendTimeout` (5 s) closes the socket with 1013, or drops the connection when
  even the close frame does not fit.
- More than `fleet.maxMarkers` (5 000) drivers in the area: `fleet_cluster {cells, cellDeg, total, at}` every tick and
  no deltas. Cells come from `GROUP BY floor(lat / cell), floor(lng / cell)` with the cell a sixteenth of the area's
  longer side (at least 0.0001°), each with the mean position of its drivers. The cell size is a SQL literal, because
  Postgres matches a `GROUP BY` expression with the `SELECT` one by its text and two bind parameters never match. When
  the area holds few enough drivers again, a `fleet_snapshot` follows and deltas resume.
- CORS: `adminArea` mounts every `/api/v1/admin` route under a route selector that matches the prefix without
  consuming it, so the routes keep their full paths. Ktor's CORS plugin is installed there only when `ADMIN_ORIGINS`
  (comma separated) is not empty: methods GET and POST, headers `Authorization`, `Content-Type` and `X-Request-Id`,
  no credentials, preflight cached 10 minutes. An `OPTIONS` route under the prefix lets a preflight reach the
  route-scoped plugin. The admin trip summary recompute moved to its own route function,
  `adminTripSummaryRoutes`, because `tripSummaryRoutes` also serves the guest's `GET /api/v1/trips/{id}/summary`.
- `SecurityHeaders` (an application plugin) adds `X-Content-Type-Options: nosniff` and `Referrer-Policy:
  no-referrer` to every response, and `Cache-Control: no-store` to every `/api/v1/admin` response, errors included.
- Tests:
  - `FleetLiveTest`: TC-11-FL-03, overlap duplicates, going offline, cluster mode and back, a slow socket.
  - `FleetSocketIT`: TC-11-FL-01, 02, 04, 05, 06, SEC-03, an invalid area, going offline over the socket.
  - `WsTicketsIT`: SEC-03 binding and single use, hashed at rest, retention.
  - `BrowserSecurityIT`: SEC-02, SEC-04.
  - `FleetAuthorizationIT` has the tickets row. The fleet messages have golden files checked against the AsyncAPI
    document (TC-11-CON-01).

## As built (11d)

- Staff are an actor of the trip aggregate. `Trip.mayPerform` lets OPERATOR and ADMIN cancel, and a second table,
  `Trip.STAFF_TRANSITIONS`, holds the one move only staff have: IN_PROGRESS to CANCELLED. A guest or driver who tries
  that move still gets 409 ILLEGAL_TRANSITION, as before, because for them the move does not exist. A trip that has
  ended answers ILLEGAL_TRANSITION to staff too.
- `POST /api/v1/admin/trips/{id}/cancel {reason}` (OPERATOR, ADMIN) runs `TripAdminService.cancel`: one transaction
  with the trip update, the outbox event and the audit row `TRIP_CANCELLED` (target TRIP, the reason, and the stage
  it left in `details.from`). A blank or missing reason is 422 REASON_REQUIRED. The user route
  `POST /api/v1/trips/{id}/cancel` refuses staff with 403, so every staff cancel has a reason and an audit row; the
  trip authorization matrix keeps its 403 for ADMIN and OPERATOR on that route.
- `TripStatusChanged` has `cancelledBy` (GUEST, DRIVER, OPERATOR; ADMIN counts as OPERATOR), set on every change to
  CANCELLED. The outbox JSON `trip.TripStatusChanged.v1` gains the optional field, which old readers ignore. The guest's
  `trip_event` carries it. The guest socket reads the trip again on each status update; it adds `cancelledBy` only when
  both the update and the trip say CANCELLED, so a stale update never guesses.
- The driver socket had no status messages before. `DriverCancelPush` (an outbox handler) now sends the assigned driver
  the same `trip_event` frame when their trip is cancelled, through `DriverLink`, on any instance. It is sent once and
  not again after a reconnect: the `trip_ended` command and `GET /api/v1/trips/{id}` cover a driver who was offline.
  `TripEvent` is now a `ServerMessage` too, like `route_updated`. Both contracts are updated: AsyncAPI (`trip_event` on
  the `driver` channel, `cancelledBy`, a new golden file) and `protocol.md`.
- `GET /api/v1/admin/trips` (OPERATOR, ADMIN) filters by `status` (repeat it for several), `driverId`, `guestId`,
  `from` (inclusive) and `to` (exclusive) on `created_at`, newest first, keyset paged by `(created_at DESC, id DESC)`
  with the existing `TripCursor`, 50 a page and 100 at most. A bad parameter is 400 with every field error at once.
- `EXPLAIN ANALYZE` over 200 000 trips (Postgres 16, before V22): the status, guest and driver filters already used
  `trips_status_created`, `trips_guest_history` and `trips_driver_history` (under 0.1 ms). The unfiltered list, the
  dashboard's first page, and a `from`/`to` range ran a parallel sequential scan with a top-N sort: 18.3 ms for the
  first page, 18.5 ms for a deep page, 12.7 ms for one day. V22 adds `trips_created (created_at DESC, id DESC)`. After
  it, each of those is an index scan of 51 rows: 0.08 ms, 0.06 ms and 0.04 ms, and `status=COMPLETED&status=CANCELLED`
  uses it too.
- TypeScript client (TC-11-CI-01): the CI job `ts-client` runs `openapi-typescript` 7.13.0 on `rts-v1.yaml`, then
  `tsc --noEmit` (TypeScript 5.9.3, the generator's peer range) over the output and `clients/ts/usage.ts`, which uses
  the trip list, the cancel body, the driver list and the ticket types. Versions are pinned in
  `clients/ts/package-lock.json`. It uploads the artifact `rts-client-<sha>` with the generated file and the spec,
  kept 90 days. Nothing goes to npm.
- `simulator fleet --drivers N [--dashboards M] [--duration D]` (T11.9) runs every driver as a coroutine with its own
  `DriverClient`. Driver n is `sim-driver-NNN` with a user id derived from the name. The dev token endpoint does not
  look users up by name, it creates a new user unless the request has a `userId`, so the simulator sends that stable
  id and the endpoint updates the same user on every run. No server change was needed. Each driver loops
  `routes/hanoi_loop.gpx` (a new synthetic loop around Hoan Kiem Lake, 300 fixes 1 s apart), moved up to 6 km in each
  direction, from a random point, after a random wait. `DriverClient` gained an optional fix source, so it sends
  without end, and a `sending` hook to measure ack latency. Dashboards use an OPERATOR dev token, a ticket each, and
  the fleet socket over the Hanoi area; the run prints p50, p95 and max of the time between two deltas and exits 1
  when p95 is outside 2 s ± 0.5 s. `--url` takes several instances.
- `docker-compose.yml` passes `HTTP_RATELIMITPERMINUTE` to the app: 1 000 drivers from one address need more than the
  default 300 requests a minute for their tokens and socket upgrades.
- Tests:
  - `TripTest`: staff cancel each stage that has not ended, never one that has, and never accept or drive; guests and
    drivers still cannot cancel IN_PROGRESS; `cancelledBy` for each actor.
  - `TripAdminServiceTest`: the audit row and the event, the reason rule, forbidden callers, the race, paging.
  - `TripRepositoryContract` (TC-11-API-06 on both repositories) and `AdminTripsIT`: TC-11-API-03 with a guest socket
    and a driver socket, the audit row over the API, 409, 422, 404, the user route's 403 for staff, and the list
    filters and paging with contract checks.
  - `FleetAuthorizationIT` has both routes, with a fresh trip for each cancel call.
  - `FleetLoadE2ETest`: TC-11-LOAD-01 small, 50 drivers, 2 dashboards, 100 ms tick, cadence only.
  - The full TC-11-LOAD-01 run is manual: `docs/testing/load-11.md`. Run on 2026-11-17 on one Mac: 500 drivers keep
    the 2 s cadence (p95 2.25 s), 1 000 drivers do not (p95 3.26 s). Both miss the ingest SLO (ack p95 350 ms and
    1 483 ms against 250 ms). The limits are the pool of 10 database connections per instance and the single outbox
    relay, not the fleet socket. TC-11-LOAD-01 is open.

## Definition of Done

- The dashboard (running locally against staging) shows 500 simulated drivers moving on the map, the driver list, driver detail with a working command button, and the audit log.
- All P0 cases pass; OpenAPI/AsyncAPI updated; TypeScript client published by CI.

Status after 11d (2026-11-17):

- Server side done: every admin route of the plan, the fleet socket, and `simulator fleet --drivers 500` to feed a
  dashboard. The dashboard itself is the `RouteTrackingAdmin` repo and is not built yet, so the first point is open.
- All P0 cases pass in `./gradlew check`. OpenAPI and AsyncAPI are updated. CI builds the TypeScript client as the
  artifact `rts-client-<sha>` (the first run is on this PR).
- Open: TC-11-LOAD-01 (P1) at 1 000 drivers, see `docs/testing/load-11.md`.
