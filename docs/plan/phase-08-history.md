# Phase 8 — Trip Summary, History & Export

Branch: `feat/p8-history` · Depends on: 3, and reads the data of 4, 6 and 7 · Features: U10, U11, U12, U13

Revised on 2026-11-16 with `main` at `b3eb038` (phases 0 to 7 merged). The first version was written before
phases 4 to 7. [What changed from the first plan](#what-changed-from-the-first-plan) lists every correction.

## Goal

After a trip ends, both parties get a summary. They can list their past trips, fetch the recorded track and
the timeline for a replay, and export the track as GPX or GeoJSON. Every read endpoint is paginated,
authorized, and stays fast on long tracks.

## Scope

**In:** stage timestamps on trips, the trip summary computed at completion, the history list with keyset
pagination, the track and timeline endpoint, GPX and GeoJSON exports, and optional Douglas–Peucker
simplification for display.

**Out:** analytics across trips (phase 9), deleting trips and a retention job (open question 9 in
[decisions.md](../product/decisions.md)), and summaries of cancelled trips (see the open questions below).

## What the code already gives us

| Need | Where it is today |
|---|---|
| The driver's fixes | `fixes` (V3), keyed by `(session_id, seq, idx)`, with `distance_from_prev_m`, `derived_speed_mps` and the index `fixes_session_time (session_id, recorded_at)` |
| Which driver owns a session | `tracking_sessions.driver_id` (V5) |
| Rejected fixes | `batches.rejections` (V3), a JSON list of reasons for each batch, with `received_at` and no per-fix time |
| Server-noticed moments | `trip_moments` (V8), with the index `trip_moments_trip_at (trip_id, at)`. Holds arriving, low battery, stale, back and off-route |
| Planned routes | `trip_routes` (V11, V12): every TRIP and APPROACH version with its `reason` (INITIAL or OFF_ROUTE) |
| When the trip changed stage | **Nothing.** `trips` has only `created_at` and `updated_at`. `TripStatusChanged` goes through the outbox, but the outbox is not a history store |
| A read API for fixes | **Nothing.** `tracking.application.api` has only `DriverLink` and `DriverPresence` |

## Design

### 8a. Stage timestamps (`V13__trip_history.sql`)

Add `accepted_at`, `arrived_at`, `started_at` and `ended_at` (all `timestamptz`, nullable) to `trips`.
`Trip` sets each one in the transition that reaches the stage, so it is saved in the same update and needs
no new table. `ended_at` is set by both COMPLETED and CANCELLED.

Trips stored before V13 keep nulls. A summary of such a trip falls back to the time window
`[created_at, updated_at]` and says so with `"approximate": true`.

The alternative is a `trip_status_changes` table with one row per transition. It would keep repeated stages
and their actors, but the state machine never repeats a stage, and four columns are simpler to read and index.

### 8a. Reading the track across contexts (`tracking.application.api.DriverTrack`)

A trip has no session id. The driver can open several sessions during one trip (a reconnect with a new
`sessionId`, or a second phone), so the track is defined by the driver and a time window:

```kotlin
interface DriverTrack {
    /** Accepted fixes of every session of [driver] with recordedAt in [from, to), oldest first, at most [limit]. */
    suspend fun fixes(driver: UserId, from: Instant, to: Instant, after: TrackCursor?, limit: Int): List<TrackPoint>
    /** Rejected fixes of the same sessions, counted by reason, for batches received in [from, to). */
    suspend fun rejections(driver: UserId, from: Instant, to: Instant): Map<String, Int>
}
```

- `TrackPoint` is an API type (lat, lng, recordedAt, accuracyM, speedMps, derivedSpeedMps). The `trip`
  context never sees the tracking domain, which keeps the Konsist rule.
- The query joins `tracking_sessions` on `driver_id` and reads `fixes` through `fixes_session_time`. It
  pages by `(recorded_at, session_id, seq, idx)`, so equal timestamps from two sessions never skip a point.
- Rejections are counted by batch `received_at`, because a rejected fix has no stored time. A batch that
  straddles a stage boundary is counted in the window it arrived in.

### 8a. Summary (`trip/domain/summary`)

```kotlin
data class TripSummary(
    val tripId: TripId,
    val ride: LegSummary,            // started_at to ended_at
    val approach: LegSummary?,       // accepted_at to arrived_at; null when the trip has no approach
    val plannedDistanceM: Double?,   // the first TRIP route (version 1)
    val offRouteCount: Int,          // OffRoute moments: times the driver left the route
    val rerouteCount: Int,           // trip_routes rows with reason OFF_ROUTE: re-plans that succeeded
    val rejected: Map<String, Int>,
    val approximate: Boolean,
)
data class LegSummary(
    val distanceM: Double, val durationS: Long, val movingTimeS: Long,
    val avgSpeedMps: Double, val maxSpeedMps: Double, val pointCount: Int,
    val startedAt: Instant, val endedAt: Instant,
)
object TripSummaryCalculator { fun leg(points: Sequence<TrackPoint>, from: Instant, to: Instant): LegSummary }
```

- **Distance** sums the distance between consecutive points of the window. It does not reuse
  `distance_from_prev_m`, because that value is per session and the first point of the window would carry a
  distance from before the window.
- **Gaps.** When two points are more than 60 s apart (the phone was offline or the driver switched phones),
  the gap adds no distance and no moving time. It still counts toward `durationS`.
- **Moving time** counts intervals whose speed is 1 m/s or more. `avgSpeedMps = distanceM / movingTimeS`,
  and 0 when the moving time is 0.
- **Max speed** uses a 3-point median of the speed series, so one GPS spike cannot become the max. The speed
  is `speedMps` when the phone reports it, else `derivedSpeedMps`.
- **Planned and actual distance.** `plannedDistanceM` against `ride.distanceM` shows how far the driver
  strayed from the plan. Phase 6 and 7 data makes this possible, and the first plan did not have it.
- The calculator reads a `Sequence`, so it works in one pass over pages and never holds the whole track.

`TripSummaryService.summarize(tripId)` reads the trip, the two windows, the routes and the moments, then
upserts `trip_summaries` (V13, primary key `trip_id`, the summary as `jsonb` plus `computed_at`). The upsert
makes it idempotent, so the outbox may deliver the event twice.

`TripSummaryListener` handles `TripStatusChanged` to COMPLETED. `TripFinishedListener` (phase 7) already
handles the same event type to drop progress state, and the two do not depend on each other.

The relay runs handlers inside its transaction, so a slow summary holds up every event behind it. One hour at
1 fix per second is 3 600 points, which takes milliseconds. If summaries ever get slow, the listener can mark
the trip and a `ScheduledJob` can compute it instead. That is not needed now.

### 8b. History list

`GET /api/v1/me/trips?cursor=&limit=20` returns `{items: [TripHistoryItem], nextCursor}`, newest first. The token
decides whose trips and on which side: a guest gets the trips they booked, a driver the trips they accepted. An
admin has no trips of their own and gets 403.

- The cursor is opaque: base64url of `(created_at, id)`, the same format as the open-trips list. A bad cursor
  gets 400 `VALIDATION_FAILED` with a `cursor` field error, as on the open-trips list.
- The query is `where guest_id = ? and (created_at, id) < (?, ?) order by created_at desc, id desc limit ? + 1`.
  The extra row says whether a next page exists.
- V14 adds `trips (guest_id, created_at desc, id desc)` and `trips (driver_id, created_at desc, id desc)`, with the
  query that needs them (an index ships with its first query, see database.md).
- A list item has the status, the stage times, pickup and destination, and the ride distance when a summary
  exists. It never holds the track.

### 8c. Track, timeline and exports

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/trips/{id}/summary` | `TripSummaryDto`. 404 `SUMMARY_NOT_READY` until it is computed |
| GET | `/api/v1/trips/{id}/track?leg=ride\|approach&simplify=0` | `{points: [{lat, lng, at, speedMps}], timeline: [...]}` for replay |
| GET | `/api/v1/trips/{id}/export.gpx` | `application/gpx+xml`, `Content-Disposition: attachment; filename="trip-<id>.gpx"` |
| GET | `/api/v1/trips/{id}/export.geojson` | `application/geo+json`: a LineString for the ride, a Point for each timeline entry |
| POST | `/api/v1/admin/trips/{id}/summary` | Admin only: compute the summary again |

- **Timeline** merges three sources in time order: the stage timestamps, `trip_moments`, and the
  `trip_routes` versions. Each entry is `{at, kind, ...}`, with the same kind names the guest socket uses.
- **Simplification.** `shared/geo/DouglasPeucker.simplify(points, toleranceM)` uses an explicit stack, so
  100 000 points cannot overflow the call stack. `simplify` is capped at 50 m. The timeline is never
  simplified.
- **Exports stream.** They use `respondOutputStream` and read 1 000 points a page through `DriverTrack`.
  `GpxWriter` and `GeoJsonWriter` live in `trip/adapter/inbound/web/export`, because file formats are a
  presentation concern.
- **Track and export work only on ended trips.** A live trip gets 409 `TRIP_NOT_ENDED`. The guest socket
  already shows a live trip, and this keeps a half-written track out of exports.

### Authorization

The same rule as `GET /trips/{id}`: the guest, the assigned driver, or an admin. Anyone else gets
`TRIP_NOT_FOUND`, so a guessed id reveals nothing (decisions.md row 10).

The guest sees the ride leg and its timeline. The approach leg (where the driver drove before the pickup) is
returned only to the driver and to admins. A guest who asks for `leg=approach` gets 403
`TRIP_ACTION_FORBIDDEN`: the guest knows the trip exists, so a 404 would hide nothing.

## Tasks

- [x] T8.1 V13: stage timestamps and `trip_summaries`. `Trip` sets the timestamps.
- [x] T8.2 `DriverTrack` in `tracking.application.api` with its Exposed adapter and keyset paging.
- [x] T8.3 `TripSummaryCalculator` (gaps, median max speed, moving time) and `LegSummary`.
- [x] T8.4 `TripSummaryService`, `TripSummaryListener`, `GET /summary`, the admin recompute endpoint.
- [x] T8.5 V14 history indexes, the history query with keyset pagination, `GET /me/trips`.
- [x] T8.6 `DouglasPeucker`.
- [x] T8.7 Timeline merge, `GET /track`.
- [x] T8.8 `GpxWriter`, `GeoJsonWriter`, streaming export endpoints.
- [ ] T8.9 OpenAPI contract and golden files for every new endpoint, written first.

Slices: **8a** = T8.1 to T8.4, **8b** = T8.5, **8c** = T8.6 and T8.7, **8d** = T8.8. T8.9 goes with each slice.

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-8-STG-01 | Unit | Each transition sets its timestamp once; CANCELLED sets `ended_at` | P0 |
| TC-8-STG-02 | Integration | A trip stored before V13 gets an approximate summary from `created_at` and `updated_at` | P1 |
| TC-8-TRK-01 | Integration | Fixes from two sessions of one driver inside the window come back merged in time order | P0 |
| TC-8-TRK-02 | Integration | Fixes before `started_at` or after `ended_at` are left out | P0 |
| TC-8-TRK-03 | Integration | Two fixes with the same `recorded_at` in two sessions both come back across a page boundary | P0 |
| TC-8-SUM-01 | Unit | 3 km straight track at 10 m/s gives distance 3 000 ±1%, moving time 300 s, average 10 m/s | P0 |
| TC-8-SUM-02 | Unit | A 5-minute stop is in `durationS` and not in `movingTimeS` | P0 |
| TC-8-SUM-03 | Unit | One 60 m/s spike among 10 m/s fixes gives `maxSpeed` close to 10 | P0 |
| TC-8-SUM-04 | Unit | A 10-minute gap between points adds no distance and no moving time | P0 |
| TC-8-SUM-05 | Unit | Approach fixes go to `approach` and not to `ride` | P1 |
| TC-8-SUM-06 | Unit | Zero fixes give a summary of zeros, with no division by zero | P0 |
| TC-8-SUM-07 | Integration | 2 OffRoute moments and 1 OFF_ROUTE route give `offRouteCount` 2 and `rerouteCount` 1 | P2 |
| TC-8-SUM-08 | Service | Summarizing twice stores the same summary | P1 |
| TC-8-DP-01 | Unit | Collinear points keep only the two ends | P0 |
| TC-8-DP-02 | Property | Every original point is within the tolerance of the simplified line, and both ends stay | P0 |
| TC-8-DP-03 | Unit | 100 000 points simplify without a stack overflow | P1 |
| TC-8-HIS-01 | Integration | 45 trips, limit 20, give pages of 20, 20 and 5, newest first, with no duplicate or gap | P0 |
| TC-8-HIS-02 | Integration | Two trips with the same `created_at` both come back across a page boundary | P0 |
| TC-8-HIS-03 | API | Guest A's history never holds guest B's trips | P0 |
| TC-8-HIS-04 | API | A bad cursor gets 400 `VALIDATION_FAILED` with a `cursor` field error | P1 |
| TC-8-API-01 | API | The track of a live trip gets 409 `TRIP_NOT_ENDED` | P1 |
| TC-8-API-02 | API | A guest asking for `leg=approach` gets 403; the driver gets the points | P0 |
| TC-8-API-03 | API | The timeline holds stages, moments and route versions in time order | P0 |
| TC-8-API-04 | API | `?simplify=5` returns fewer points with the first and last unchanged | P1 |
| TC-8-EXP-01 | Unit | `GpxWriter` output validates against the GPX 1.1 XSD | P0 |
| TC-8-EXP-02 | Unit | `GeoJsonWriter` writes `[lng, lat]`, not `[lat, lng]` | P0 |
| TC-8-EXP-03 | API | Exports set the content type and the file name `trip-<id>.gpx` | P1 |
| TC-8-EXP-04 | Unit | A 50 000-point export reads at least 50 pages | P2 |
| TC-8-EXP-05 | Unit | GPX round trip through the test fixtures' GPX parser gives the same points (±1e-7) | P1 |
| TC-8-E2E-01 | E2E | A full simulator trip, then complete: the summary is ready within 1 s, and the export downloads and parses | P0 |

## Test implementation notes

- **GPX schema:** put `gpx.xsd` (GPX 1.1) in test resources and validate with `javax.xml.validation.SchemaFactory`.
- **GPX parser:** `GpxTrack` in `app/src/testFixtures/.../testing/geo` (added in 8d: `Fixtures.kt` had none), not the simulator.
- **GeoJSON order:** TC-8-EXP-02 checks the first coordinate against `[lng, lat]` of the first fix.
- **Paging tests** insert rows with set timestamps through SQL fixtures, not through the API.
- **Socket and e2e tests** run on Netty through `socketApp`, as in phases 3 to 7.

## Decisions

Accepted by the product owner on 2026-11-16:

1. A cancelled trip gets no summary. It shows in the history with its stage times only.
2. The guest never sees the approach leg, for the driver's privacy.
3. Phase 8 deletes nothing. How long tracks are kept stays open question 9 in decisions.md.

## What changed from the first plan

| First plan | Now | Why |
|---|---|---|
| Migration `V9__trip_summaries.sql` | `V13__trip_history.sql` (8a), `V14` (8b indexes) | V9 to V12 are taken |
| Track between the `start` and `complete` timestamps | New stage columns on `trips` | No stage times are stored anywhere |
| Fixes through `tracking`'s `TrackQuery` | New `DriverTrack` API, by driver and time window | `TrackQuery` does not exist, and one trip can span several sessions |
| `TripSummaryCalculator.calculate(fixes: List, …)` | One pass over a `Sequence` of pages | A long trip must not be loaded at once |
| `rerouteCount` from `RouteUpdated(off_route)` events | `offRouteCount` from `trip_moments`, `rerouteCount` from `trip_routes` | Phase 7 stores both, and a failed re-plan is an off-route without a re-route |
| Generic `TripEvent` list | Timeline of stages, `trip_moments` and `trip_routes` | Those are the stored sources phases 4 to 7 created |
| Nothing on gaps | Gaps over 60 s add no distance | A reconnect or a second phone would draw a straight line across the city |
| No planned distance | `plannedDistanceM` from the first TRIP route | Phase 6 stores it |
| One track for everyone | The approach leg only for the driver and admins | The driver's movements before the pickup are not the guest's business |
| `/history` reachable during the trip | 409 until the trip ends | The live socket covers a running trip |
| `?role=guest\|driver`, and both sides without it | No `role`: the token's role picks the side, an admin gets 403 | A user has one role, and `guest_id = ? OR driver_id = ?` cannot be read in order from one index |
| 400 `INVALID_CURSOR` | 400 `VALIDATION_FAILED` with a `cursor` field error | The open-trips list already answers a bad cursor this way |
| A guest asking for the approach gets 404 | 403 `TRIP_ACTION_FORBIDDEN` | The guest knows the trip exists; 403 states the real reason, as for an admin's history |
| TC-8-EXP-04 as an integration test | A unit test with a counting `DriverTrack` | It checks the paging, which a fake counts exactly; 50 000 rows in Postgres would test the database |
| 8c holds the track, the timeline and the exports | 8c: track and timeline. 8d: exports | The exports bring streaming, XML and the XSD; two PRs of 8b's size review better |

## As built (8a)

- `Trip.stages` (`StageTimes`) holds the four times. `Trip` sets them in `moveTo`, the one place that changes the
  status, so auto-arrive sets `arrived_at` too.
- `DriverTrack` is implemented by `TrackingHistory` over a new port, `TrackHistoryRepository`, rather than by
  adding methods to `FixBatchRepository`: the write side and its in-memory fake stay unchanged.
- The summarizer is `LegSummarizer`, an accumulator fed one sample at a time, not a function over a `Sequence`:
  pages come from a suspend call, and a plain accumulator needs no flow.
- A sample's speed is the phone's `speedMps`, else the stored `derivedSpeedMps`, else the speed of its segment.
- `TRIP_NOT_ENDED` (409) arrived in 8a, for the admin recompute of a trip that is not completed.
- The history indexes moved to 8b (V14), because database.md adds an index with its first query.
- **Known limit.** The summary is computed when the completion is delivered. A batch recorded during the ride
  that reaches the server after that is stored but missing from the summary until an admin computes it again.
- TC-8-SUM-05 and TC-8-SUM-07 are covered by TripSummaryIT and ExposedTripIT, not by their own tests.

## As built (8b)

- `TripRepository.findHistory(user, party, before, limit)` with `TripParty` GUEST or DRIVER. It runs in the
  shared `TripRepositoryContract`, so the in-memory and the Postgres versions pass the same paging tests
  (TC-8-HIS-01, TC-8-HIS-02).
- `TripHistoryService` reads one page plus one row, then the page's summaries in one query
  (`TripSummaryRepository.findAll`), not one query per trip.
- `PageQuery` parses `cursor` and `limit` for both trip lists, so they answer a bad value the same way.
- `trips_driver_history` is a partial index (`WHERE driver_id IS NOT NULL`).
- TC-8-STG-02 is in TripHistoryIT: it clears a completed trip's stage times, as V13 left old trips, and the admin
  recompute marks the summary approximate.
- `/api/v1/me/trips` is outside `/api/v1/trips`, so AuthorizationMatrixIT does not cover it. TripHistoryIT tests
  its callers instead: guests, drivers, an admin (403) and no token (401).

## As built (8c)

- `Trip.rideWindow()` and `Trip.approachWindow()` (`TripWindows.kt`) decide which fixes belong to a leg, for the
  summary and the track alike. The fallback to `created_at..updated_at` applies only to a COMPLETED trip without
  stage times. A trip cancelled before its ride has no ride window, so it never shows approach fixes as the ride.
- `TripTimeline.of` merges the stages, `TripMomentRepository.findAll` and `TripRouteRepository.findAll`. It
  concatenates them and does one stable sort by time, then by source (stage, moment, route). The lists are a
  few dozen entries, so a hand-written three-way merge would add code and save nothing.
- `TripTrackService` reads the whole leg into memory, because Douglas–Peucker needs every point before it drops
  one. The endpoint is not paged. A 2-hour ride at one fix a second is about 7 200 points.
- `DouglasPeucker` (shared/geo) measures in one flat frame around the first point and keeps its ranges on an
  `ArrayDeque<Long>`, two indexes packed in one Long, so the stack holds no objects. TC-8-DP-03 runs a
  100 000-point zigzag that keeps every point, the deepest case.
- The track is for the guest, the assigned driver and admins. Unlike `Trip.isVisibleTo`, a driver does not see
  another driver's trip, and a REQUESTED trip of their own guest gets 409, not 404.
- TC-8-TRK-01 to 03 ran in 8a against `DriverTrack` (TrackHistoryIT). TC-8-API-01 to 04 run in the service unit
  test and in TripTrackIT.
- The ride helpers of TripSummaryIT moved to `Rides.kt`, shared with TripTrackIT.

## As built (8d)

- `TripTrackService.export` runs the checks and reads the timeline in one short transaction, then returns a
  `TrackExport` whose `pages` is a cold `Flow<List<TrackPoint>>`. Each page of 1 000 fixes is read in its own
  transaction when the writer collects it, so a slow download holds no pool connection between pages. `track`
  uses the same page flow inside its single transaction.
- The route collects the flow inside `respondOutputStream`. Every check runs before the stream opens; a failure
  after the headers can only cut the connection.
- `GpxWriter` uses StAX. Coordinates have 7 decimals. Speed is left out, because GPX 1.1 has it only in
  `<extensions>`.
- `GeoJsonWriter` writes the leg as a LineString, then one Point per timeline entry at the last fix at or before
  its time, found in the same pass. Timeline points use the closed window `from..to`, unlike fixes, so COMPLETED
  sits at the ride's last fix. A LineString needs two positions, so the writer holds the first fix until a
  second one arrives, and a shorter leg gets `"geometry": null`.
- The contract validator parses `application/geo+json` as JSON, so the GeoJSON response has a real schema,
  `TrackFeatureCollection`, not `type: string`.
- Golden files for a fixed five-fix ride are in `app/src/test/resources/export`. Run the test with
  `UPDATE_GOLDEN=true` to rewrite them on purpose.
- TC-8-E2E-01 ends in TripTrackIT: a ride recorded over the socket downloads as GPX and GeoJSON, and both parse.

## Definition of Done

- A completed trip has a summary, replay data, and GPX and GeoJSON exports that open in a map tool such as geojson.io.
- Every list endpoint is keyset-paginated and authorized.
- The OpenAPI contract and golden files cover every new endpoint.
- Every P0 case passes.
