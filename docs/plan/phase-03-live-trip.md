# Phase 3 — Trips & Live Tracking

Branch: `feat/p3-live-trip` · Depends on: 2 · Unlocks: 4, 5, 6, 8 · Features: U1, U2, U4, U5 (straight line), I4, I6, I10

## Goal

A guest creates a trip; a driver accepts it; while the trip is active, the driver's accepted fixes
are pushed live to the guest's socket together with distance-left and ETA (straight-line for now).
Access is authenticated and scoped: a guest only ever sees their own trip.

## Scope

**In:** `identity` context (users, roles, JWT verification, dev token endpoint), `trip` context
(aggregate with state machine, `TripService`, progress), `live` context (fan-out bus, guest socket),
REST API with ETag/If-Match and Idempotency-Key, event-driven link from `tracking` to `trip`,
authorization matrix tests.

**Out:** automatic arrival detection, stale/low battery (phase 4), road routing (phase 6),
route-based distance (phase 7), dispatch algorithm (drivers pick from open requests).

## Design

### Identity (`identity/`)

- `users(id, display_name, role)`, `role ∈ {GUEST, DRIVER, ADMIN}`.
- JWT verification (phase 1 `platform/security`) configured with `iss`, `aud`, 30 s leeway; claims `sub`, `role`.
- `POST /api/v1/dev/tokens {userId}` is mounted only when `APP_ENV ∈ {dev, test}`; production would
  plug in an external identity provider behind the same verifier interface.
- Inbound adapters convert the principal into `Actor(userId, role)`; services authorize.
- Driver socket requires role `DRIVER`; the session aggregate records `userId`.

### Trip aggregate (`trip/domain`)

```kotlin
class Trip private constructor(val id: TripId, val guestId: UserId, driverId: UserId?, val pickup: GeoPoint,
                               val destination: GeoPoint, status: TripStatus, version: Long, ...) {
    fun accept(driver: Actor, now: Instant)
    fun arrive(by: Actor, now: Instant)
    fun start(by: Actor, now: Instant)
    fun complete(by: Actor, now: Instant)
    fun cancel(by: Actor, now: Instant)
    fun isVisibleTo(actor: Actor): Boolean
    fun pullEvents(): List<TripDomainEvent>      // TripStatusChanged(from, to, at)
    companion object { fun request(guest: Actor, pickup: GeoPoint, destination: GeoPoint, id: TripId, now: Instant): Trip }
}
enum class TripStatus { REQUESTED, ACCEPTED, ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED }
```

Transitions (enforced inside the aggregate; violations throw `ConflictException(ILLEGAL_TRANSITION)`
or `ForbiddenException(TRIP_ACTION_FORBIDDEN)`):

| From | Action | Actor | To |
|---|---|---|---|
| REQUESTED | accept | any driver (becomes `driverId`) | ACCEPTED |
| ACCEPTED | arrive | assigned driver (manual now; system actor in phase 4) | ARRIVED |
| ARRIVED | start | assigned driver | IN_PROGRESS |
| IN_PROGRESS | complete | assigned driver | COMPLETED |
| REQUESTED, ACCEPTED, ARRIVED | cancel | guest or assigned driver | CANCELLED |

Visibility: an actor who can't see a trip gets `NotFoundException(TRIP_NOT_FOUND)` — identical to a
non-existent trip, so ids can't be probed.

### Application (`trip/application`)

`TripService` (each method = one transaction; saves with optimistic version check; events → outbox):

| Method | Notes |
|---|---|
| `request(cmd: RequestTrip, actor)` | GUEST only; `Idempotency-Key` handled by the platform HTTP layer |
| `get(id, actor)` / `listOpen(actor, page)` | queries |
| `accept / arrive / start / complete / cancel(cmd, actor)` | loads, calls aggregate, saves with `expectedVersion`; version mismatch → `ConflictException(CONCURRENT_MODIFICATION)`; driver already active → `ConflictException(DRIVER_ALREADY_ON_TRIP)` |

`TripProgressService` — `trip.adapter.in.event.FixesAcceptedListener` calls it for each `FixesAccepted` event:

1. Find the driver's active trip (`TripRepository.findActiveForDriver`); none → return.
2. Target = pickup (ACCEPTED/ARRIVED) or destination (IN_PROGRESS).
3. `remainingM = haversine(latest, target)`; speed EWMA `s = α·v + (1−α)·s_prev` (α = 0.3) kept in `ProgressStateStore`.
4. `etaS = remainingM / max(s, 2.0)`; `basis = STRAIGHT_LINE`.
5. Publish `DriverPositionUpdated` and `TripProgressUpdated` to the `live` context via its `LiveUpdates` API
   (fire-and-forget, not persisted: positions are ephemeral).

Ports: `TripRepository { insert; find; findOpen(page); findActiveForDriver; update(trip, expectedVersion): Boolean }`,
`ProgressStateStore`. Public API: `TripQuery` (for `live` authorization and later contexts).

### Live (`live/`)

- `LiveUpdateBus` port with `publish(tripId, LiveUpdate)` / `subscribe(tripId): Flow<LiveUpdate>`;
  adapter `InMemoryLiveUpdateBus`: one `MutableSharedFlow(replay = 1, extraBufferCapacity = 64, DROP_OLDEST)` per trip,
  removed when terminal and unsubscribed. Phase 10 adds `RedisLiveUpdateBus` for multi-instance.
- Positions are "latest wins": a slow guest skips stale positions and never blocks ingest.
  Status changes come from `TripStatusChanged` outbox events (reliable); on any status event the
  guest socket re-reads the snapshot through `TripQuery`, so a dropped buffer item can't leave a guest in a wrong state.
- `GuestSocket` (`live/adapter/in/ws`): role GUEST → `subscribe{tripId}` → authorize via `TripQuery`
  → `subscribed{snapshot}` → stream `driver_position`, `trip_progress`, `trip_event` → on terminal status send and close 1000.

### REST (`trip/adapter/in/web`, documented in `rts-v1.yaml`)

| Method | Path | Role | Result |
|---|---|---|---|
| POST | `/api/v1/trips` `{pickup, destination}` (+ `Idempotency-Key`) | GUEST | 201 `TripResponse`, `Location`, `ETag` |
| GET | `/api/v1/trips/{id}` | guest of trip, assigned driver | 200 + `ETag` / 404 |
| GET | `/api/v1/trips?status=REQUESTED&cursor=&limit=` | DRIVER | 200 page |
| POST | `/api/v1/trips/{id}/accept\|arrive\|start\|complete\|cancel` (optional `If-Match`) | per table | 200 / 403 / 404 / 409 / 412 |

### Migrations

`V3__identity.sql` (`users`), `V4__trips.sql`: `trips(id uuid pk, guest_id fk, driver_id fk null, pickup_lat/lng,
destination_lat/lng, status text check (...), version bigint, created_at, updated_at)`, index `(status, created_at)`,
partial unique index `one_active_trip_per_driver on trips(driver_id) where status in ('ACCEPTED','ARRIVED','IN_PROGRESS')`,
`idempotency_keys(key, principal, request_hash, response jsonb, created_at)`.

## Tasks

- [ ] T3.1 `identity` context: users, dev tokens, `Actor`; driver socket requires DRIVER.
- [ ] T3.2 `Trip` aggregate + events; `TripService`; `TripQuery`; in-memory adapters + mothers.
- [ ] T3.3 V3/V4 migrations; `ExposedTripRepository` (optimistic `update ... where version = ?`); contract tests.
- [ ] T3.4 Platform: Idempotency-Key support, ETag/If-Match helpers.
- [ ] T3.5 Trip REST routes; OpenAPI paths and schemas.
- [ ] T3.6 `FixesAcceptedListener` + `TripProgressService`.
- [ ] T3.7 `live` context: bus, `GuestSocket`; AsyncAPI guest channel.
- [ ] T3.8 Simulator `guest --trip <id>` command; `--token` for driver.
- [ ] T3.9 Tests below, including the authorization matrix.

## Test plan

### Trip aggregate & service

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-3-SM-01 | Unit | Every legal row of the transition table succeeds and records `TripStatusChanged`; `updatedAt = now` | P0 |
| TC-3-SM-02 | Property | For every (status, action) pair **not** in the table → `ConflictException(ILLEGAL_TRANSITION)` | P0 |
| TC-3-SM-03 | Unit | `accept` sets `driverId` to the actor | P0 |
| TC-3-SM-04 | Unit | `start` by a driver who is not the assigned driver → `ForbiddenException` | P0 |
| TC-3-SM-05 | Unit | Guest can cancel REQUESTED/ACCEPTED/ARRIVED; not IN_PROGRESS | P0 |
| TC-3-SM-06 | Unit | A guest attempting `accept` → `ForbiddenException` | P1 |
| TC-3-UC-01 | Service | `request` by a DRIVER actor → `ForbiddenException` | P1 |
| TC-3-UC-02 | Service | `accept` when repository `update` returns false → `ConflictException(CONCURRENT_MODIFICATION)` | P0 |
| TC-3-UC-03 | Service | Driver with an active trip accepting another → `ConflictException(DRIVER_ALREADY_ON_TRIP)` | P0 |
| TC-3-UC-04 | Service | `get` by an unrelated guest → `NotFoundException(TRIP_NOT_FOUND)` (not `Forbidden`, so trip ids can't be probed for existence) | P1 |
| TC-3-UC-05 | Service | `TripQuery.snapshotFor(tripId, actor)` for the trip's guest → snapshot; another guest → `NotFoundException` | P0 |

### Progress

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-3-PRG-01 | Service | ACCEPTED trip → target is pickup; IN_PROGRESS → destination | P0 |
| TC-3-PRG-02 | Service | Driver 1 000 m from target at steady 10 m/s → `etaS ≈ 100` after EWMA converges | P0 |
| TC-3-PRG-03 | Service | Speed 0 (stopped) → ETA uses `minSpeedMps`, never divides by zero | P0 |
| TC-3-PRG-04 | Service | Driver with no active trip → nothing published | P0 |
| TC-3-PRG-05 | Service | EWMA: first sample initializes to that speed; subsequent samples follow α | P1 |
| TC-3-PRG-06 | Service | Publishes `DriverPosition` before `Progress` for the same fix | P2 |

### Live bus (coroutines-test + Turbine)

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-3-HUB-01 | Unit | Subscriber receives updates published after subscribing, in order | P0 |
| TC-3-HUB-02 | Unit | Late subscriber receives the latest update immediately (replay 1) | P0 |
| TC-3-HUB-03 | Unit | Updates for trip A are never delivered to trip B's subscriber | P0 |
| TC-3-HUB-04 | Unit | Non-collecting (slow) subscriber doesn't suspend `publish` (publisher completes 1 000 publishes) | P0 |
| TC-3-HUB-05 | Unit | After terminal status and last unsubscribe, the trip's flow is removed (map size back to 0) | P1 |

### Persistence (integration)

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-3-DB-01 | Integration | `TripRepositoryContract` against Exposed | P0 |
| TC-3-DB-02 | Integration | `update` with stale `expectedVersion` returns false and changes nothing | P0 |
| TC-3-DB-03 | Integration | Two drivers accept the same trip concurrently (real service + DB) → exactly one 200, the other 409 | P0 |
| TC-3-DB-04 | Integration | Partial unique index prevents a second active trip for one driver even if app check is bypassed | P1 |

### REST + auth (API)

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-3-API-01 | API | No token → 401 on every `/api/v1/trips*` route | P0 |
| TC-3-API-02 | API | Guest `POST /trips` → 201, `status = REQUESTED`, `Location` header | P0 |
| TC-3-API-03 | API | Invalid coordinates in body → 400 Problem `code=VALIDATION_FAILED` with `errors[].field` | P0 |
| TC-3-API-04 | API | Driver `GET /trips?status=REQUESTED` lists only REQUESTED trips, paginated | P1 |
| TC-3-API-05 | API | Full happy path accept → arrive → start → complete returns 200 each with the new status | P0 |
| TC-3-API-06 | API | Illegal transition → 409 `ILLEGAL_TRANSITION` | P0 |
| TC-3-API-07 | API | Expired JWT → 401; token with wrong signature → 401 | P0 |
| TC-3-API-08 | API | `/api/v1/dev/tokens` returns 404 when `APP_ENV=prod` | P0 |
| TC-3-API-09 | API | Guest token on driver socket → closed 4403 | P0 |

### Guest socket (API)

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-3-GWS-01 | API | Guest subscribes to own trip → `subscribed` with snapshot | P0 |
| TC-3-GWS-02 | API | Guest subscribes to someone else's trip → `error{TRIP_NOT_FOUND}` (same as a non-existent trip) | P0 |
| TC-3-GWS-03 | API | Driver sends batch while trip ACCEPTED → guest receives `driver_position` then `trip_progress` | P0 |
| TC-3-GWS-04 | API | Trip completed via REST → guest receives `trip_event{status_changed COMPLETED}` then close 1000 | P0 |
| TC-3-GWS-05 | API | Guest disconnects → hub subscriber count decreases (no leak) | P1 |

### E2E

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-3-E2E-01 | E2E | Guest creates trip at pickup near the end of `straight_2km.gpx`; driver accepts; simulator replays; guest receives monotonically decreasing `remainingM` (allow ±accuracy noise) ending < 50 m | P0 |
| TC-3-E2E-02 | E2E | Guest cancels mid-approach → driver's later batches still ACKed; guest receives CANCELLED; no more positions published | P1 |

### HTTP semantics & security

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-3-HTTP-01 | API | `POST /trips` twice with the same `Idempotency-Key` and body → same 201 response, one trip in DB | P0 |
| TC-3-HTTP-02 | API | Same `Idempotency-Key` with a different body → 422 `IDEMPOTENCY_KEY_REUSED` | P1 |
| TC-3-HTTP-03 | API | `accept` with stale `If-Match` → 412; with current ETag → 200 and new ETag | P1 |
| TC-3-SEC-01 | Security | Authorization matrix: every trip route × {anonymous, guest-owner, other guest, assigned driver, other driver, admin} returns the expected status (table-driven, one test per cell) | P0 |
| TC-3-SEC-02 | Security | Token with wrong `aud` or `iss` → 401 | P0 |
| TC-3-CON-01 | Contract | All trip responses in API tests conform to `rts-v1.yaml` (harness validator) | P0 |

## Test implementation notes

- **JWT in tests:** the test profile has a fixed signing key; `tokens.guest(id)`, `tokens.driver(id)`,
  `tokens.expired()` mint tokens directly — the dev endpoint is only tested by TC-3-API-08.
- **TC-3-SM-02 property test:** `Arb.enum<TripStatus>()` × `Arb.enum<TripAction>()`; the legal set is
  written **independently** in the test as a literal table (reusing production data would prove nothing).
- **TC-3-SEC-01 matrix** lives in one file as data: `listOf(Case(route, actor, expectedStatus), ...)`,
  turned into dynamic tests (`@TestFactory`). Adding a route without adding matrix rows fails a
  completeness check that compares the matrix with the routes registered in the app.
- **TC-3-HUB-04:** subscribe with a collector that suspends forever, then
  `withTimeout(1.seconds) { repeat(1000) { bus.publish(...) } }` must complete.
- **Guest + driver in one API test:** open both sockets in the same `TestApp`; wrap each `receive`
  in `withTimeout` so a missing message fails fast.
- **TC-3-DB-03:** two `TripService.accept` calls against the real DB launched concurrently; count successes.

## Definition of Done

- Two terminals: `simulator driver` + `simulator guest` show live positions and ETA shrinking.
- Konsist rule: web/ws adapters contain no authorization decisions (they pass `Actor` to services).
- Authorization matrix complete; OpenAPI/AsyncAPI updated; all P0 cases pass.
