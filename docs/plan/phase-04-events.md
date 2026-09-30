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

## Tasks

- [ ] T4.1 `TripEvent` hierarchy, rule functions, `EventRulesConfig`.
- [ ] T4.2 `TripEventService` + listeners for `FixesAccepted` / `DeviceStatusReported`.
- [ ] T4.3 System actor + auto-arrive inside the aggregate.
- [ ] T4.4 `device_status` message in `tracking` (socket branch, mapping, event).
- [ ] T4.5 `platform/jobs` scheduler with advisory lock; `StaleDriverJob`; `PresenceStore`.
- [ ] T4.6 V5 migration, repositories, contract tests.
- [ ] T4.7 `live`: `TripEventRaised` → `trip_event` frames, dedupe by event id.
- [ ] T4.8 Domain metrics.
- [ ] T4.9 Simulator: `--battery-drain` and `--pause-at <seconds>:<duration>` to trigger low-battery and stale.

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
