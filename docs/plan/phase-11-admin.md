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

`RouteTrackingAdmin`: React + TypeScript + Vite, MapLibre GL (map), TanStack Query (REST cache),
a small WS client for the fleet channel, Playwright for E2E against staging. Pages: Overview · Live map ·
Drivers (list, detail) · Trips (list, live view reusing the guest channel with operator rights) · Audit.
Deployed as static files (Cloudflare Pages / Netlify) pointing at the staging API.

## Tasks

- [ ] T11.1 PostGIS image everywhere; `V11__fleet.sql`; Exposed column type for `geography`.
- [ ] T11.2 `fleet` context: projection handlers (idempotent, coalesced), `FleetQuery`, rebuild command.
- [ ] T11.3 `FleetPositionCache` + fleet socket with snapshot, conflated deltas, clustering fallback.
- [ ] T11.4 Roles `OPERATOR`, operator actor in trip aggregate; driver enable/disable in `identity`.
- [ ] T11.5 Admin REST endpoints + OpenAPI; CSV export.
- [ ] T11.6 Audit log (table, writer used inside services, query endpoint, DB grants).
- [ ] T11.7 CORS, WebSocket tickets, admin token expiry, security headers.
- [ ] T11.8 CI job generating and publishing the TypeScript client.
- [ ] T11.9 Seed script: 500 simulated drivers across the city for dashboard development (`simulator fleet --drivers 500`).

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
| TC-11-API-03 | API | Operator cancels an IN_PROGRESS trip → 200; guest and driver receive CANCELLED with reason | P1 |
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

## Definition of Done

- The dashboard (running locally against staging) shows 500 simulated drivers moving on the map, the driver list, driver detail with a working command button, and the audit log.
- All P0 cases pass; OpenAPI/AsyncAPI updated; TypeScript client published by CI.
