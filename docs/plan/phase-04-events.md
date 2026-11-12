# Phase 4 — Trip Events

Branch: `feat/p4-events` · Depends on: 3 · Unlocks: 7 · Features: U6, U8, U9, I2 (presence), I4 (hysteresis)

## Goal

The server detects meaningful moments from the raw stream and pushes them as events:
*arriving*, *arrived* (auto-transitions the trip), *driver stale* / *driver back*, *low battery*.
Detection is robust to GPS noise (hysteresis) and fully testable with virtual time.

## Scope

**In:** `device_status` client message, presence tracking, event rules with hysteresis, a periodic
stale detector as a cluster-safe scheduled job, auto-arrive transition, persisted trip events delivered via the outbox, domain metrics.

**Out:** off-route (phase 7), push notifications to phones when the app is closed (FCM — future).

## Design

### Trip events (`trip/domain/event`)

```kotlin
sealed interface TripEvent { val tripId: TripId; val at: Instant
    data class DriverArriving(..., val remainingM: Double) : TripEvent
    data class DriverArrived(...) : TripEvent
    data class DriverStale(..., val lastSeenAt: Instant) : TripEvent
    data class DriverBack(...) : TripEvent
    data class DriverLowBattery(..., val levelPercent: Int) : TripEvent
    data class StatusChanged(..., val from: TripStatus, val to: TripStatus) : TripEvent
}
```

`TripEvent`s are persisted in `trip_events` and written to the outbox in the same transaction; the
`live` context consumes them (`TripEventRaised`) and pushes `trip_event` frames to guests.

### Rules with hysteresis

Every rule is a pure domain function: `(RuleState, Input, now) -> (RuleState, List<TripEvent>)`.
Rule state is persisted per trip (`trip_rule_state(trip_id, rule, state jsonb)`) so it survives restarts
and works with several instances; it is updated in the same transaction as the events it produces.

| Rule | Enters when | Leaves / resets when | Emits |
|---|---|---|---|
| `ArrivingRule` | trip ACCEPTED and `remainingM ≤ 200` for **2 consecutive** fixes | `remainingM > 300` for 2 consecutive fixes | `DriverArriving` once per entry |
| `ArrivedRule` | trip ACCEPTED and `remainingM ≤ 30` for **3 consecutive** fixes **or** `≤ 30` and speed < 1.5 m/s for 10 s | – (one-shot) | `DriverArrived` + `trip.arrive(Actor.System)` in the same transaction |
| `StaleRule` | no frame from the driver's session for `staleAfter` (60 s) | next frame received | `DriverStale` / `DriverBack` (paired, never two Stale in a row) |
| `LowBatteryRule` | `levelPercent ≤ 15` and not charging, **or** `BatteryProfile = CRITICAL` | `levelPercent ≥ 25` or charging | `DriverLowBattery` once per entry |

Thresholds live in `EventRulesConfig`. The 200/300 gap and the N-consecutive requirement are the
hysteresis: noise around one threshold cannot make an event flicker.

A **system actor** (`Actor.System`) is allowed to perform `arrive`; add it to the aggregate's rules and the phase 3 table.

`TripEventService.onFixes(FixesAccepted)` and `onDeviceStatus(DeviceStatusReported)` load the trip + rule
state, evaluate rules, apply transitions, append events, save — one transaction per event.

### Presence

- `PresenceStore` port (`live` context) records `lastFrameAt` per session on every inbound frame
  (Ktor doesn't surface pongs; the SDK sends `device_status` every 30 s as an application heartbeat).
  In-memory adapter now; Redis (TTL keys) in phase 10.
- `StaleDriverJob` (`trip/adapter/in/job`) runs every 10 s via `platform/jobs`. The scheduler takes a
  Postgres advisory lock (`pg_try_advisory_lock(<job id>)`) per run, so with several instances only one
  evaluates. It calls `TripEventService.detectStale(now)` for active trips.

### Device status (protocol)

```
client → device_status { at, battery: { levelPercent, isCharging, temperatureC, profile }, motion: "STILL"|"WALKING"|"IN_VEHICLE"|null }
```

Mapped from the SDK's `BatteryReading`/`BatteryProfile` and activity transitions. Handled by the
`tracking` context (it owns device sessions), which publishes `DeviceStatusReported` (outbox). Stored in phase 9.

### Persistence

`V5__trip_events.sql`: `trip_events(id bigserial, trip_id fk, type text, payload jsonb, at timestamptz)` + index `(trip_id, at)`,
and `trip_rule_state`. Events reach guests only through the outbox, so a guest can never see an event
that history doesn't have, and a crash after commit still delivers it (at-least-once; the guest socket
dedupes by event id).

### Metrics

Domain metrics on the phase 1 registry: `rts_driver_sessions_active`, `rts_guest_sessions_active`,
`rts_fixes_ingested_total{result}`, `rts_ack_latency_seconds` (histogram), `rts_trip_events_total{type}`.

## As built: slice 4a (arriving and auto-arrive)

Phase 4 is built in four slices, one PR each: 4a arriving and auto-arrive, 4b `device_status` and low battery,
4c stale and back, 4d metrics and simulator flags. Slice 4a differs from the design above in these ways:

| Plan | As built | Why |
|---|---|---|
| A system actor (`Actor.System`) performs `arrive` | `Trip.autoArrive(now)`, with no actor. The change is recorded with `cause = AUTO` (`StatusCause`, new field `cause` in `TripStatusChangedV1`) | No person acts, so there is nobody to authorize. A SYSTEM role would let the dev token endpoint issue system tokens |
| One sealed `TripEvent` with `StatusChanged` and `DriverArrived` | `TripStatusChanged` stays the only status event. New sealed `TripMoment` (`DriverArriving` now; stale, back and low battery in 4b and 4c), payload `TripMomentRaisedV1` | Two events for one status change could disagree. `TripEvent` is already the guest message's name. Arriving at the pickup is the status change itself |
| `V5__trip_events.sql` | `V8__trip_moments.sql`: `trip_moments` and `trip_rule_state(trip_id, rule, state jsonb)` | V5 to V7 were taken in phase 3 |
| `TripEventService` opens its own transaction | It runs in the outbox relay's transaction (`TransactionRunner` joins it) | Rule state, moment, status change, new outbox rows and "batch delivered" commit together |
| `trip_event` gets new events and an optional `status` | `status` stays required. `remainingM` is added. The AsyncAPI document says clients ignore unknown `event` values | An older SDK keeps decoding the message |

How it works:
- `ArrivalListener` (trip) handles `FixesAccepted`, next to the progress listener. `TripEventService` loads the
  driver's active trip, and only for an ACCEPTED trip it locks the arrival state (`INSERT ... ON CONFLICT DO
  NOTHING`, then `SELECT ... FOR UPDATE`), runs `ArrivalRules` over every fix of the batch, stores
  `DriverArriving` in `trip_moments` and the outbox, and calls `autoArrive` when the rule says arrived.
- `ArrivalRules` is pure. It skips a fix at or before the last fix it counted, so a batch the relay delivers
  twice raises nothing the second time. A fix without a measured speed counts only for the fixes-in-a-row rule.
- When the trip changed in between (the driver tapped "arrived" at the same moment), the optimistic update fails,
  the service throws CONCURRENT_MODIFICATION, and the relay rolls everything back and delivers the batch again.
  The next attempt sees ARRIVED and does nothing.
- `TripMomentListener` (live) turns `TripMomentRaisedV1` into a `TripMomentUpdate`. The guest socket sends it as
  `trip_event{event: driver_arriving}`.

Not done in 4a: the guest socket does not drop a moment delivered twice (the plan's dedupe by event id), the
thresholds are `ArrivalConfig` defaults and not in HOCON yet, and `trip_rule_state` rows stay after a trip ends.

Tests: TC-4-ARV-01 to 05, TC-4-ARD-01 to 04, TC-4-PER-01 and 02, TC-4-WS-01 (arriving, then ARRIVED) and
TC-4-E2E-01 (inside TC-3-E2E-01, which now waits for the automatic ARRIVED). Check: 206 unit, 173 integration and
9 end-to-end tests pass.

## As built: slice 4b (device_status and low battery)

| Plan | As built | Why |
|---|---|---|
| `device_status` with a bad value gets `error{VALIDATION_FAILED}` | `error{INVALID_MESSAGE}` | The socket has no VALIDATION_FAILED code; INVALID_MESSAGE already means "valid JSON, wrong content" |
| The battery `profile` is an enum (`BatteryProfile`) | Free strings for `profile` and `motion`; the rule only knows `CRITICAL` | A new SDK value cannot break an older server |
| Separate stores per rule | One `RuleStateStore` port (`lockArrival`, `lockBattery`, ...) and one `ExposedRuleStateStore` over `trip_rule_state` | Same table, lock and transaction for every rule |

How it works:
- The driver socket maps `device_status` (`DriverMessageMapper.reportStatus`, null for a level outside 0 to 100)
  and calls `TrackingService.reportStatus`. It moves the session's `last_seen_at` to the server's time (4c reads it
  for presence) and writes `DeviceStatusReported` (payload `DeviceStatusReportedV1`) to the outbox. No reply.
- `BatteryListener` (trip) calls `TripEventService.onDeviceStatus`. For the driver's active trip (ACCEPTED,
  ARRIVED or IN_PROGRESS) it runs `BatteryRules` with the stored state and raises `DriverLowBattery`.
- `BatteryRules` is pure: low at 15 % or less and not charging, or profile CRITICAL; reset at 25 % or charging;
  low wins over reset, so a CRITICAL profile while charging is still low. A status at or before the last one
  counted is skipped.

Tests: TC-4-BAT-01 to 04, TC-4-WS-01 (guest gets driver_low_battery), TC-4-WS-02 (150 % gets INVALID_MESSAGE),
plus no reply to `device_status`, 4401 before hello, and the last-seen time. Check: 225 unit, 177 integration
and 9 end-to-end tests pass.

## As built: slice 4c (stale and back)

| Plan | As built | Why |
|---|---|---|
| `PresenceStore` in memory in the live context | `DriverPresence` in `tracking.application.api`, reading `tracking_sessions.last_seen_at` (index in V9) | With several instances, the instance that runs the job cannot see the others' memory; the database is shared. Redis in phase 10 |
| `pg_try_advisory_lock` per run | `pg_try_advisory_xact_lock` inside the run's transaction (`PostgresJobLock`) | A session lock stays on a pooled connection when the unlock is skipped and stops the job everywhere; a transaction lock ends with the transaction |
| TC-4-STL-06: one instance runs per period | One instance at a time; a second run in the same period finds nothing new | The transaction lock serializes runs. `detectStale` raises nothing twice, because the presence state pairs stale and back |
| "Back" when the next frame arrives | "Back" at the job's next run, within `events.staleCheckEvery` (10 s) | One place decides both; no extra work per batch |

How it works:
- `platform/jobs`: `ScheduledJob`, `JobLock`, `JobScheduler`. The scheduler waits a period, then runs the job
  only if the lock is free. A failed run is logged and the next one happens on time. Graceful shutdown stops the
  scheduler before the relay, so no job uses the pool after it closes.
- `StaleDriverJob` (trip, lock key 4001) calls `TripEventService.detectStale()`: for each active trip it reads
  the driver's last-seen time (a driver never heard on the trip counts from the trip's last change), runs
  `PresenceRules` with the stored state, and raises `DriverStale(lastSeenAt)` or `DriverBack`.
- `events.staleAfter` (60 s) and `events.staleCheckEvery` (10 s) are in `base.conf`.

Tests: TC-4-STL-01 to 07 (STL-04 and 05 with virtual time; STL-06 with two pools against Postgres),
TC-4-E2E-02 as a socket test (stale after 1 s, back when heard again). Check: 242 unit, 182 integration and 9
end-to-end tests pass.

Found on the way: a JUnit test written as `fun test() = runBlocking { ... x shouldBe y }` returns the Boolean of
its last line, and JUnit skips non-void methods without a word. The lock test declares `: Unit`.

## As built: slice 4d (metrics and simulator)

Metrics on `/metrics` (Prometheus names):

| Metric | Type | Where |
|---|---|---|
| `rts_driver_sessions_active`, `rts_guest_sessions_active` | gauge | `WsSessionRegistry`, one gauge per endpoint, made on its first session |
| `rts_fixes_ingested_total{result=accepted\|rejected}` | counter | `DriverSocket`, for newly stored batches only (a resend is not counted again) |
| `rts_ack_latency_seconds` | histogram | `DriverSocket`, from a batch's arrival to its ack, with the commit inside |
| `rts_trip_events_total{type}` | counter | live listeners, on delivery to guests (`status_changed`, `driver_arriving`, ...). A delivery the relay retries counts again |

Simulator (`simulator driver`):
- A `device_status` every 30 s of route time, like the SDK's heartbeat. `--battery N` sets the start level
  (default 80) and `--battery-drain P` the percent lost per minute of route time.
- `--pause-at S:D`: after S seconds of route, the phone sends nothing for D real seconds. With the server's
  60 s stale time, `--pause-at 120:70` shows `driver_stale`, then `driver_back`.
- `DeviceScript` decides both; `DriverClient.hooks` (`BatchHooks`) runs them before each batch.
- `simulator guest` prints every `trip_event` kind in words.

Tests: TC-4-MET-01 (3 acked batches give 6 accepted fixes, 3 ack timings and 1 open driver session), the trip
events counter after arriving, and `DeviceScriptTest`. Check: 246 unit, 183 integration and 9 end-to-end tests pass.

## Tasks

- [x] T4.1 (4a: arriving and arrived rules) `TripEvent` hierarchy, rule functions, `EventRulesConfig`.
- [ ] T4.2 `TripEventService` + listeners for `FixesAccepted` / `DeviceStatusReported`.
- [x] T4.3 (as `autoArrive`) System actor + auto-arrive inside the aggregate.
- [x] T4.4 `device_status` message in `tracking` (socket branch, mapping, event).
- [x] T4.5 `platform/jobs` scheduler with advisory lock; `StaleDriverJob`; `PresenceStore`.
- [x] T4.6 (V8, V9) V5 migration, repositories, contract tests.
- [x] T4.7 (without the dedupe) `live`: `TripEventRaised` → `trip_event` frames, dedupe by event id.
- [x] T4.8 Domain metrics.
- [x] T4.9 Simulator: `--battery-drain` and `--pause-at <seconds>:<duration>` to trigger low-battery and stale.

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-4-ARV-01 | Unit | Distances 250, 190, 180 → one `DriverArriving` at the second ≤200 reading | P0 |
| TC-4-ARV-02 | Unit | Distances 190, 210, 190, 210 (flicker) → no `DriverArriving` (never 2 consecutive) | P0 |
| TC-4-ARV-03 | Unit | Arriving emitted, then 190, 250, 190 (never 2 consecutive > 300) → no second emission | P0 |
| TC-4-ARV-04 | Unit | Arriving, then 2× > 300, then 2× ≤ 200 → second `DriverArriving` (re-entry allowed) | P1 |
| TC-4-ARV-05 | Unit | Rule ignores trips not in ACCEPTED | P1 |
| TC-4-ARD-01 | Unit | 3 consecutive ≤ 30 m → `DriverArrived` exactly once | P0 |
| TC-4-ARD-02 | Unit | ≤ 30 m and speed < 1.5 m/s sustained 10 s (2 fixes 10 s apart) → arrived | P1 |
| TC-4-ARD-03 | Unit | `TripEventService` on `DriverArrived` applies `arrive` as system actor in the same transaction; trip becomes ARRIVED and `TripStatusChanged` is in the outbox | P0 |
| TC-4-ARD-04 | Unit | Driver already manually arrived → rule emits nothing (idempotent w.r.t. status) | P1 |
| TC-4-STL-01 | Unit | Last frame at t0; `detectStale` at t0+59 s → nothing; at t0+61 s → `DriverStale` | P0 |
| TC-4-STL-02 | Unit | Stale emitted; still no frames at t0+120 s → no second `DriverStale` | P0 |
| TC-4-STL-03 | Unit | Stale, then a frame arrives → `DriverBack` once | P0 |
| TC-4-STL-04 | Unit | Scheduler runs `StaleDriverJob` every 10 s under `TestScope`: `advanceTimeBy(35 s)` → 3 invocations | P1 |
| TC-4-STL-05 | Unit | Scheduler stops when its scope is cancelled (no invocations after cancel) | P1 |
| TC-4-STL-06 | Integration | Two scheduler instances against the same DB: in each period only one acquires the advisory lock and runs the job | P0 |
| TC-4-STL-07 | Integration | Rule state survives an application restart (stale emitted before restart is not emitted again after) | P1 |
| TC-4-BAT-01 | Unit | 20% → 15% not charging → one `DriverLowBattery(15)` | P0 |
| TC-4-BAT-02 | Unit | 15%, 14%, 13% → still only one event | P0 |
| TC-4-BAT-03 | Unit | Low → plug in (charging) → reset; unplug at 14% → new event | P1 |
| TC-4-BAT-04 | Unit | Profile CRITICAL at 30% → event (temperature case) | P2 |
| TC-4-PER-01 | Service | If saving fails, the transaction rolls back: no event row, no outbox row, no status change | P0 |
| TC-4-PER-02 | Integration | `TripEventRepositoryContract` against Exposed; payload jsonb round-trips each event type | P0 |
| TC-4-WS-01 | API | `device_status` with 10% battery on an active trip → guest receives `trip_event{driver_low_battery}` | P0 |
| TC-4-WS-02 | API | `device_status` with invalid level (150) → `error{VALIDATION_FAILED}`, session open | P1 |
| TC-4-MET-01 | API | After 3 ACKed batches, `/metrics` shows `rts_fixes_ingested_total` increased accordingly | P2 |
| TC-4-E2E-01 | E2E | Simulator replays approach to pickup → guest sees `arriving` then `arrived`, trip status ARRIVED via REST | P0 |
| TC-4-E2E-02 | E2E | Simulator pauses 70 s (test uses `staleAfter = 2 s` config) → guest sees `driver_stale` then `driver_back` | P1 |

## Test implementation notes

- **Rules are pure** → table-driven tests: a list of `(distance, speed, at)` inputs and the expected
  event list; fold inputs through the rule and compare. One helper, many cases.
  ```kotlin
  fun ArrivingRule.run(vararg meters: Double): List<TripEvent> =
      meters.fold(RuleState.initial to emptyList<TripEvent>()) { (s, out), m -> evaluate(s, input(m)).let { (ns, ev) -> ns to out + ev } }.second
  ```
- **Virtual time for the scheduler (TC-4-STL-04/05):** `JobScheduler(scope, dispatcher, lock, period)`; in the test
  pass `StandardTestDispatcher(testScheduler)` and `backgroundScope`; `FakeClock` reads
  `testScheduler.currentTime` so rule time and coroutine time agree.
- **E2E timing:** override `staleAfter` and the job period to seconds in the `test` profile;
  never wait 60 s in a test.

## Definition of Done

- No event can flicker under the fixture `stationary_drift.gpx` parked 180–220 m from pickup (add as TC-4-ARV-06 scenario if time allows).
- `/metrics` scrapeable; event counts visible.
- All P0 cases pass.
