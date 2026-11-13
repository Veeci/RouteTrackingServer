# Phase 7 — Route Tracking (Snapping, ETA, Off-route, Re-route)

Branch: `feat/p7-route-tracking` · Depends on: 4, 6 · Features: U5 (along route), U7, I5, I6, I7

## Goal

Replace straight-line progress with progress **along the planned route**: snap each driver fix
onto the route polyline, report distance left and ETA along the road, detect when the driver leaves
the route, and re-route automatically — with both sides receiving the new route.

## Scope

**In:** polyline projection math, cursor-based matcher, remaining distance, route-based ETA with a
speed correction factor, off-route detection with hysteresis and cooldown, re-routing through
`RouteService`, `route_updated` message, route versioning.

**Out:** full HMM map-matching against the whole road graph (stretch), traffic-aware ETA.

## Design

### Geometry (`shared/geo`)

```kotlin
class MeasuredPolyline(points: List<GeoPoint>) {   // precomputes segment lengths and prefix sums (cumulativeM)
    val lengthM: Double
    fun project(p: GeoPoint, segmentRange: IntRange): Projection
}
data class Projection(val segmentIndex: Int, val fraction: Double, val point: GeoPoint,
                      val alongM: Double, val crossTrackM: Double, val segmentBearingDeg: Double)
```

Projection uses a local equirectangular approximation around the segment (x = Δlng·cos(lat)·R,
y = Δlat·R) — accurate to well under 1 m at city scale and much cheaper than spherical formulas.

### Matcher (`trip/domain/progress/RouteMatcher`)

Per trip, keep a `MatchState(routeVersion, cursorSegment, lastAlongM, offRouteCount, lastRerouteAt)` in the
`ProgressStateStore` port (in-memory now, Redis in phase 10). It is derived state: if lost, the matcher
re-initialises from the start of the route with a wide search window, so it is not persisted in Postgres.

1. Search window: segments `[cursor − 2, cursor + 25]` (clamped). A window, not the whole polyline,
   prevents snapping to a later part of the route that passes nearby (loops, U-turn roads).
2. Candidate score = `crossTrackM + bearingPenalty`, where `bearingPenalty = 30 m` if the driver's
   bearing (speed > 3 m/s) differs from the segment bearing by > 60°.
3. Best candidate becomes the match; `cursor = its segment`.
4. Monotonic guard: if `alongM < lastAlongM − 20 m`, keep `lastAlongM` (GPS jitter must not make
   distance-left grow); larger backward moves are accepted (real U-turn).
5. `remainingM = lengthM − alongM`.

### ETA

`etaS = remainingDurationS(route, alongM) × k`, where `remainingDurationS` sums the route's
per-segment expected durations from the graph speeds, and `k` = EWMA of (expected speed / observed speed),
clamped to `[0.5, 3.0]`. Falls back to straight-line ETA (phase 3) when no route exists.
`basis = ROUTE`.

### Off-route and re-route

| Condition | Result |
|---|---|
| `crossTrackM > 50` **and** fix `accuracyM ≤ 30`, for 3 consecutive fixes | `OffRoute` event |
| Any fix with `crossTrackM ≤ 30` | `offRouteCount = 0` (hysteresis: 50 in, 30 out) |
| `OffRoute` and `now − lastRerouteAt ≥ 30 s` | Re-route from the current fix to the current target; `routeVersion + 1`; persist; publish `RouteUpdated`; reset matcher state |
| Re-route fails (e.g. no road nearby) | Keep old route, emit `OffRoute` only, retry after cooldown |

Poor-accuracy fixes never count toward off-route (they may be inside a tunnel or urban canyon).

### Protocol additions

```
server → route_updated  { tripId, routeVersion, polyline, distanceM, durationS, reason: "off_route" | "status_changed" }
server → trip_progress  { ..., basis: "route", routeVersion }
server → trip_event     { type: "off_route", ... }
```

The driver also receives `route_updated` on its socket (for turn-by-turn in the app later).

### Application flow

`TripProgressService` (phase 3) switches to the matcher when the trip has a route. On `OffRoute` with
cooldown elapsed it calls `TripRouteService.reroute(tripId, from = latestFix)`:

1. `RoutePlanner.plan(...)` — outside any transaction (CPU work, no DB connection held).
2. Transaction: insert `trip_routes` row (next version, kind, `reason = OFF_ROUTE`), append `OffRoute` +
   `RouteUpdated` to `trip_events` and the outbox.
3. After commit the `live` context pushes `route_updated` to the guest; the `tracking` socket adapter
   pushes it to the driver.

Concurrent re-routes for the same trip are prevented by the version column (unique `(trip_id, kind, version)`):
the loser gets a conflict and drops its result.

### Persistence

`V8__route_reason.sql`: adds `reason` to `trip_routes` (created in phase 6) and the unique index above.
The current route = highest version per kind.

## Tasks

- [x] T7.1 `MeasuredPolyline` + projection math.
- [x] T7.2 (state in `ProgressState`) `RouteMatcher` + `MatchState` store.
- [x] T7.3 (with the route's average speed) Route-based ETA with correction factor.
- [ ] T7.4 Off-route rule (hysteresis + accuracy gate) and `TripRouteService.reroute` with cooldown.
- [x] T7.5 Replace straight-line `TrackTripProgress` path when a route exists; keep fallback.
- [ ] T7.6 V8 migration, route versioning; approach route switches to trip route on `start`.
- [ ] T7.7 `route_updated` on guest and driver sockets.
- [ ] T7.8 Fixtures: `detour_off_route.gpx`, `u_turn.gpx`, `loop_route.gpx`, `parallel_street.gpx` (drawn against `tiny_grid`).

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-7-GEO-01 | Unit | Point on the middle of a segment → fraction 0.5, crossTrack ≈ 0, alongM = prefix + half | P0 |
| TC-7-GEO-02 | Unit | Point beyond a segment's end projects onto the endpoint (fraction clamped to [0,1]) | P0 |
| TC-7-GEO-03 | Unit | Point 40 m perpendicular from a segment → crossTrack 40 ± 0.5 m | P0 |
| TC-7-GEO-04 | Property | For points generated *on* the polyline, `alongM` is within 0.5 m of the generating distance | P0 |
| TC-7-GEO-05 | Property | `alongM ∈ [0, lengthM]` for any point | P1 |
| TC-7-MAT-01 | Unit | Fixes driven along the route → `remainingM` decreases monotonically to ≈ 0 | P0 |
| TC-7-MAT-02 | Unit | `loop_route`: when the route passes the same intersection twice, the first pass matches the early segment, not the later one | P0 |
| TC-7-MAT-03 | Unit | Jitter: fixes oscillating ±10 m along track → `remainingM` never increases | P0 |
| TC-7-MAT-04 | Unit | Real U-turn (> 20 m backwards) is accepted and `remainingM` increases | P1 |
| TC-7-MAT-05 | Unit | `parallel_street`: driver on a parallel street 25 m away heading the opposite way → bearing penalty chooses the correct segment or flags off-route | P1 |
| TC-7-ETA-01 | Unit | Driver at expected speed → `k ≈ 1`, ETA ≈ route's remaining duration | P0 |
| TC-7-ETA-02 | Unit | Driver consistently at half the expected speed → ETA ≈ 2× remaining duration | P0 |
| TC-7-ETA-03 | Unit | Extreme ratios are clamped to [0.5, 3.0] | P1 |
| TC-7-ETA-04 | Unit | No route stored → falls back to straight-line basis | P0 |
| TC-7-OFF-01 | Unit | 3 consecutive fixes 60 m off with accuracy 10 → `OffRoute` | P0 |
| TC-7-OFF-02 | Unit | 2 off, 1 on (≤ 30), 2 off → no `OffRoute` (count reset) | P0 |
| TC-7-OFF-03 | Unit | 3 fixes 60 m off but accuracy 45 → no `OffRoute` | P0 |
| TC-7-OFF-04 | Unit | Values between 30 and 50 m neither increment nor reset the counter (hysteresis band) | P1 |
| TC-7-RR-01 | Service | `OffRoute` → `Reroute` called with current fix and current target; version increments; `RouteUpdated` published | P0 |
| TC-7-RR-02 | Service | Second `OffRoute` within 30 s → no re-route (cooldown); after 30 s → re-route | P0 |
| TC-7-RR-03 | Service | Re-route fails → old route kept, `OffRoute` emitted, no `RouteUpdated` | P1 |
| TC-7-RR-04 | Service | After re-route, matcher state is reset (cursor 0 on the new route) | P0 |
| TC-7-RR-05 | Service | Trip `start` → switches from APPROACH route to TRIP route, emits `RouteUpdated(reason=status_changed)` | P1 |
| TC-7-DB-01 | Integration | Route versions stored; "current" query returns the highest version per kind | P0 |
| TC-7-WS-01 | API | Driver deviates in a component test → guest and driver both receive `route_updated` with `routeVersion = 2` | P0 |
| TC-7-E2E-01 | E2E | `detour_off_route.gpx` on `tiny_grid` → exactly one re-route; final `remainingM` < 50 m; guest's `trip_progress.basis = route` throughout | P0 |
| TC-7-E2E-02 | E2E | Same trip replayed with a 45 s tunnel gap in the middle → no false off-route | P1 |

| TC-7-RR-06 | Integration | Two re-routes for the same trip committed concurrently → one version stored, the other rejected; no duplicate `route_updated` | P1 |
| TC-7-LOAD-01 | Load | 200 drivers on active trips with matching + progress enabled → ingest p95 ACK still < 250 ms (phase 2 SLO holds) | P1 |

## Test implementation notes

- **Synthetic tracks from routes:** a helper `track(along: MeasuredPolyline, speedMps, stepS, noiseM, seed)`
  generates fixes along any polyline with Gaussian cross-track noise. Most matcher tests use it
  instead of hand-written coordinates, so expectations are known by construction.
- **Deviations:** `track(...).withDetour(fromM = 400, toM = 700, offsetM = 80)` shifts a section sideways.
- **E2E fixtures on `tiny_grid`:** the grid's known geometry makes the expected route and the
  detour verifiable by hand; generate the GPX files with the helper above and commit them.

## Definition of Done

- The guest sees distance-left along the road decrease smoothly; a detour produces one re-route and the new line.
- No false off-route on the tunnel and jitter fixtures.
- All P0 cases pass.

## As built: slice 7a (route matching and route ETA)

Phase 7 is built in three slices, one PR each: 7a matching and the route ETA; 7b off-route, re-route and
`route_updated` to the guest; 7c the APPROACH route, the switch on `start`, `route_updated` to the driver and the
end-to-end cases. The design was agreed again before 7a, because this plan predates phases 3 to 6:

| Plan | As built | Why |
|---|---|---|
| `V8__route_reason.sql` with a unique `(trip_id, kind, version)` | 7b adds `V12__route_reason.sql` with `reason` only | V8 to V11 are taken. The key is already the primary key of `trip_routes` (V11) |
| `MatchState` in a store of its own | `ProgressState.route` (`FollowedRoute`: kind, version, decoded `MeasuredPolyline`, cursor and distance) in the existing `InMemoryProgressStateStore` | The state is derived from the fixes. A restart decodes the route again and matches from its start |
| ETA sums per-segment durations | `RouteEta`: `durationS × remainingM ÷ distanceM × k`, k = planned average speed ÷ smoothed speed, clamped to [0.5, 3] | `trip_routes` stores only totals. Per-point times need the planner and a new column; a known limit |
| `basis: "route"` | `basis: ROUTE` and an optional `routeVersion` on `trip_progress` | The existing value is upper case (`STRAIGHT_LINE`) |
| The guest gets the route only from `route_updated` | `subscribed.trip.route` holds the current route too (`TripSnapshot.currentRoute`) | A guest who reconnects gets the whole state in one message |
| Search window `[cursor − 2, cursor + 25]` | The same, and when its best match is more than 50 m away, the rest of the route ahead is searched | Finds the driver again after a gap (a tunnel) longer than the window. Without a state the window starts at segment 0 |
| Fixtures `*.gpx` drawn against `tiny_grid` | Tracks made in test code: `polylineFrom` and `track(along, speedMps, stepS, noiseM, seed)` in `testFixtures/.../testing/geo/Tracks.kt` | The expected distance is known by construction. GPX files stay for the simulator demo (7c) |

How it works:
- `MeasuredPolyline` (`shared/geo`) adds up the distance to each point once. `projectOnSegment` works in a flat
  frame around the segment (x = Δlng · cos(lat) · R, y = Δlat · R), clamps the foot of the perpendicular to the
  segment, and returns the distance along, the cross-track distance and the segment's bearing.
- `RouteKind.followedIn(status)` says which route a stage follows: APPROACH for ACCEPTED and ARRIVED, TRIP for
  IN_PROGRESS. `TripProgressService` loads the trip and the newest route of that kind in one transaction (one more
  query by primary key per batch). It decodes the polyline only when the kind or version changed.
- `RouteMatcher` is pure. The score is the cross-track distance, plus 30 m when the driver moves faster than 3 m/s
  against the segment's direction by more than 60°. On a tie the earlier segment wins. A step back of up to 20 m
  keeps the previous distance (jitter); a larger one counts (U-turn).
- Without a route for the stage the progress is the straight line, as in phase 3. Before 7c that is the whole time
  before the ride.
- The arrival rules (`TripEventService`) still use the straight line (decisions row 48).

Known limits: the window counts segments, not metres, so it is shorter where the graph has many short segments.
A driver more than 50 m off the route makes every fix search the rest of the route; 7b re-routes such a driver.
With several instances each keeps its own matcher state (phase 10 moves it to Redis).

Tests: TC-7-GEO-01 to 05, TC-7-MAT-01 to 05 (plus the second pass of a loop and the search ahead after a gap),
TC-7-ETA-01 to 04, the current route in `TripServiceTest`, golden files `subscribed_route` and
`trip_progress_route`, and two `GuestSocketIT` cases (route on subscribe and `basis = ROUTE` during the ride; no
route before it).
