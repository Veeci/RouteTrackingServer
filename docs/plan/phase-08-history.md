# Phase 8 — Trip Summary, History & Export

Branch: `feat/p8-history` · Depends on: 3 (uses 4/7 data if present) · Features: U10, U11, U12, U13

## Goal

After a trip ends, both parties get a summary; they can list past trips, fetch the full recorded
track and event timeline for replay, and export it as GPX or GeoJSON. All read endpoints are
paginated, authorized, and efficient on large tracks.

## Scope

**In:** `TripSummary` computation on completion, summary persistence, history list with cursor
pagination, trip track + timeline endpoint, GPX and GeoJSON exporters, optional server-side
simplification (Douglas–Peucker) for display.

**Out:** analytics across trips (phase 9), deleting trips / data retention jobs (future, note GDPR-style retention in docs).

## Design

### Summary (`trip/domain/summary`)

```kotlin
data class TripSummary(val tripId: TripId, val distanceM: Double, val durationS: Long, val avgSpeedMps: Double,
                       val maxSpeedMps: Double, val movingTimeS: Long, val pointCount: Int,
                       val rejectedCount: Map<RejectionReason, Int>, val rerouteCount: Int,
                       val startedAt: Instant, val endedAt: Instant)
object TripSummaryCalculator { fun calculate(fixes: List<AcceptedFix>, events: List<TripEvent>, trip: Trip): TripSummary }
```

- The trip's track = accepted fixes of the driver's session between `start` (IN_PROGRESS) and
  `complete` timestamps. Approach fixes (ACCEPTED → ARRIVED) are summarized separately as `approach`.
- `movingTimeS` counts intervals where speed ≥ 1 m/s; `avgSpeed = distance / movingTime`.
- `maxSpeed` uses a 3-point median-smoothed speed series (a single spike must not become the max).
- Computed by `TripSummaryListener` on `TripStatusChanged(→ COMPLETED)` (outbox, after commit) via
  `TripSummaryService.summarize(tripId)`, reading fixes through `tracking`'s `TrackQuery`, and stored in
  `trip_summaries` (`V9__trip_summaries.sql`, primary key `trip_id` → idempotent upsert). Recomputable (idempotent) via an admin endpoint.

### Simplification (`shared/geo/DouglasPeucker`)

`simplify(points, toleranceM)` — iterative (explicit stack) to avoid recursion depth issues; used for
`?simplify=5` on track endpoints.

### Endpoints

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/me/trips?cursor=&limit=20` | `{items:[TripListItemDto], nextCursor}` newest first; only trips where the caller is guest or driver |
| GET | `/api/v1/trips/{id}/summary` | `TripSummaryDto` (404 until completed) |
| GET | `/api/v1/trips/{id}/track?simplify=0` | `{points:[{lat,lng,at,speedMps}], events:[...]}` for replay |
| GET | `/api/v1/trips/{id}/export.gpx` | `application/gpx+xml`, `Content-Disposition: attachment` |
| GET | `/api/v1/trips/{id}/export.geojson` | `application/geo+json` FeatureCollection: LineString + Point features for events |

Pagination: opaque cursor = base64 of `(createdAt, id)`; query `where (created_at, id) < (?, ?) order by created_at desc, id desc limit ?+1`.
Exports stream the response (`respondOutputStream`) and read fixes in pages of 1 000 so large trips
don't load fully into memory.

## Tasks

- [ ] T8.1 `TripSummaryCalculator` + median smoothing + moving time.
- [ ] T8.2 `TripSummaryService` + listener; V9 migration; repository.
- [ ] T8.3 `DouglasPeucker`.
- [ ] T8.4 History query with keyset pagination (+ index `trips(guest_id, created_at desc, id desc)` and same for driver).
- [ ] T8.5 Track endpoint; `GpxWriter`, `GeoJsonWriter` (in `trip/adapter/in/web/export`, since they are presentation formats).
- [ ] T8.6 Streaming exports; authorization for all endpoints.

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-8-SUM-01 | Unit | 3 km straight track at 10 m/s → distance 3 000 ±1%, moving time 300 s, avg 10 m/s | P0 |
| TC-8-SUM-02 | Unit | Track with a 5-minute stop → `durationS` includes the stop, `movingTimeS` does not | P0 |
| TC-8-SUM-03 | Unit | One 60 m/s spike among 10 m/s fixes → `maxSpeed` ≈ 10 (median smoothing) | P0 |
| TC-8-SUM-04 | Unit | Approach fixes are excluded from the trip summary and reported in `approach` | P1 |
| TC-8-SUM-05 | Unit | Trip with zero fixes → summary with zeros, no division by zero | P0 |
| TC-8-SUM-06 | Unit | `rerouteCount` equals the number of `RouteUpdated(off_route)` events | P2 |
| TC-8-SUM-07 | Service | `SummarizeTrip` twice → same stored summary (idempotent) | P1 |
| TC-8-DP-01 | Unit | Collinear points → only endpoints remain | P0 |
| TC-8-DP-02 | Property | Every original point is within `tolerance` of the simplified line; endpoints preserved | P0 |
| TC-8-DP-03 | Unit | 100 000 points simplify without stack overflow | P1 |
| TC-8-HIS-01 | Integration | 45 trips, limit 20 → pages of 20, 20, 5; no duplicates or gaps; newest first | P0 |
| TC-8-HIS-02 | Integration | Two trips with identical `createdAt` are both returned across the page boundary (tie-break by id) | P0 |
| TC-8-HIS-03 | API | Guest A's history never contains Guest B's trips | P0 |
| TC-8-HIS-04 | API | Invalid cursor → 400 `INVALID_CURSOR` | P1 |
| TC-8-TRK-01 | API | Track endpoint returns points ordered by `recordedAt` and the event timeline | P0 |
| TC-8-TRK-02 | API | `?simplify=5` returns fewer points, first and last unchanged | P1 |
| TC-8-EXP-01 | Unit | `GpxWriter` output validates against the GPX 1.1 XSD (schema in test resources) | P0 |
| TC-8-EXP-02 | Unit | `GeoJsonWriter` output: FeatureCollection, LineString with `[lng, lat]` order (not lat/lng) | P0 |
| TC-8-EXP-03 | API | Export endpoints set content type and `Content-Disposition` filename `trip-<id>.gpx` | P1 |
| TC-8-EXP-04 | Integration | Export of a 50 000-point trip completes with bounded memory (reads in pages; assert repository called ≥ 50 times) | P2 |
| TC-8-EXP-05 | Unit | GPX round trip: export → simulator's GPX parser → same points (±1e-7) | P1 |
| TC-8-E2E-01 | E2E | Full trip via simulator → complete → summary available within 1 s; export downloads and parses | P0 |

## Test implementation notes

- **GPX schema validation:** put `gpx.xsd` (GPX 1.1) in test resources; validate with `javax.xml.validation.SchemaFactory`.
- **GeoJSON coordinate order** is the classic bug — TC-8-EXP-02 asserts the first coordinate
  equals `[lng, lat]` of the first fix explicitly.
- **Pagination tests** insert trips with controlled `createdAt` via the repository or SQL fixtures, not via the API (faster, exact timestamps).

## Definition of Done

- A completed trip has a summary, replay data, and valid GPX/GeoJSON exports that open in a map tool (e.g. geojson.io).
- All list endpoints are keyset-paginated and authorized.
- All P0 cases pass.
