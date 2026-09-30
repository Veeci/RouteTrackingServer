# Phase 6 — Routing (Shortest Path)

Branch: `feat/p6-routing` · Depends on: 3 · Unlocks: 7 · Features: U3, I8

## Goal

Compute a road route between two points inside one city using our own graph and search code —
Dijkstra first, then A* — and attach it to trips. This is the algorithm-heavy phase; the emphasis is
on correctness proofs through tests (known answers, properties, and cross-checking two algorithms).

## Scope

**In:** OSM XML import for one bounding box, road graph in compressed (CSR) form, largest strongly
connected component, grid spatial index, Dijkstra, A*, distance and time metrics, polyline encoding,
`RoutePlanner` API, route on trip creation and on accept, `GET /api/v1/routes`, readiness waits for graph.

**Out:** turn restrictions, live traffic, contraction hierarchies (stretch), whole-country graphs.

## Design

### Data source

- Download once with Overpass (documented in `docs/data/osm.md`): `way[highway~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service)(_link)?$"](bbox); (._;>;); out body;`
- Store at `data/osm/<city>.osm` (gitignored, path in config). Tests use hand-written fixtures under
  `app/src/testFixtures/resources/graphs/`.

### Graph (`routing/domain`)

```kotlin
class RoadGraph internal constructor(
    val nodeLat: DoubleArray, val nodeLng: DoubleArray,
    val edgeOffsets: IntArray,      // size = nodeCount + 1  (CSR)
    val edgeTargets: IntArray,
    val edgeLengthM: FloatArray,
    val edgeSpeedMps: FloatArray,   // from highway type
) { val nodeCount: Int; fun forEachEdge(from: Int, block: (to: Int, lengthM: Float, speedMps: Float) -> Unit) }
class RoadGraphBuilder { fun addNode(osmId: Long, point: GeoPoint); fun addWay(nodeOsmIds: List<Long>, highway: String, oneway: Oneway); fun build(): RoadGraph }
```

Build rules:
- Only nodes referenced by kept ways; OSM ids remapped to dense `0..n-1`.
- `oneway=yes|true|1` → forward only; `oneway=-1` → reverse only; `junction=roundabout` → forward only; otherwise both directions.
- Edge length = haversine between consecutive way nodes.
- Default speeds (km/h): motorway 80, trunk 70, primary 50, secondary 45, tertiary 40, residential/unclassified 30, service/living_street 15.
- Keep only the **largest strongly connected component** (Tarjan or Kosaraju, iterative — no recursion on large graphs) so every node can reach every other.

Why CSR arrays: a city graph has ~100k–500k nodes; object-per-edge graphs cost several times the
memory and are slower to traverse. Learning goal: cache-friendly data layout.

### Spatial index

`GridIndex(graph, cellSizeM = 250)`: bucket nodes by cell; `nearest(point, maxDistanceM)` searches
rings of cells outward until the best candidate is closer than the next ring's minimum distance.

### Search

```kotlin
interface ShortestPath { fun search(graph: RoadGraph, from: Int, to: Int, metric: Metric): SearchResult? }
class Dijkstra : ShortestPath
class AStar : ShortestPath        // h = haversine(node, target) for DISTANCE; haversine / maxSpeed for TIME (admissible)
data class SearchResult(val nodes: IntArray, val costM: Double, val costS: Double, val settled: Int)
enum class Metric { DISTANCE, TIME }
```

Binary heap with lazy deletion (`PriorityQueue<LongPacked>` or a custom `IntDoubleHeap`); `settled`
count is recorded to compare Dijkstra vs A* efficiency.

### RoutePlanner (public API of the `routing` context)

```kotlin
interface RoutePlanner {                                   // routing/application/api
    fun plan(from: GeoPoint, to: GeoPoint, metric: Metric = DISTANCE): PlannedRoute   // throws UnprocessableException
}
class GraphRoutePlanner(graphHolder: RoadGraphHolder, search: ShortestPath, config) : RoutePlanner   // routing/application
data class PlannedRoute(val polyline: List<GeoPoint>, val distanceM: Double, val durationS: Double, val metric: Metric)
```

1. Snap `from` and `to` with `index.nearest(maxSnapDistanceM = 300)`; none → `UnprocessableException(NO_ROAD_NEARBY)` with `which` in details.
2. Same node → zero-length route of `[from, to]`.
3. Search; null → `UnprocessableException(NO_ROUTE_FOUND)`.
4. Polyline = `[from] + node points + [to]`; distance adds the two snap legs.

`UnprocessableException(OUTSIDE_COVERAGE)` when a point is outside the graph's bounding box (+1 km margin).

The graph is immutable after loading and shared across requests (thread-safe reads, no locks).
`RoadGraphHolder` swaps in a new graph atomically if it is reloaded (admin endpoint, P2).
Routing is CPU-bound: `plan` runs on `Dispatchers.Default` with a per-request timeout (500 ms).

### Polyline encoding (`shared/geo/PolylineCodec`)

Google Encoded Polyline (precision 5) for compact storage and transport.

### Integration

- `TripService.request` calls `RoutePlanner` **before** opening the transaction (no DB connection held during CPU work), then stores
  the route in `trip_routes` (`V7__trip_routes.sql`, kind `TRIP`, version 1). Routing failure → 422 with the routing error code, no trip created.
- `accept` computes `driverPosition → pickup` (last fix via `tracking`'s `TrackQuery`) and stores it as kind `APPROACH` (used in phase 7).
- `GET /api/v1/routes?from=lat,lng&to=lat,lng&metric=distance|time` → `RouteDto(polyline, distanceM, durationS)`.
- `GraphHealthIndicator` DOWN until the graph is loaded; loading runs at startup on `Dispatchers.Default`, so the pod receives no traffic until ready.

## Tasks

- [ ] T6.1 `docs/data/osm.md`; fixtures `tiny_grid.osm`, `one_way_trap.osm`, `two_islands.osm`, `roundabout.osm`.
- [ ] T6.2 `RoadGraphBuilder` + `RoadGraph` (CSR) + SCC pruning.
- [ ] T6.3 `OsmXmlGraphLoader` (StAX) in `routing/adapter/out/osm`.
- [ ] T6.4 `GridIndex`.
- [ ] T6.5 `Dijkstra`, then `AStar`; shared heap.
- [ ] T6.6 `PolylineCodec`.
- [ ] T6.7 `GraphRoutePlanner` + error codes; used by `TripService.request` / `accept`; migration V7.
- [ ] T6.8 `GET /api/v1/routes` (OpenAPI), graph health indicator, planning timeout.
- [ ] T6.9 Tests, including property cross-check and benchmark.
- [ ] T6.10 (Stretch) Bidirectional Dijkstra; binary graph cache for fast startup.

## Test plan

### Graph building

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-6-GR-01 | Unit | Two-way way A-B-C → 4 directed edges; lengths equal haversine | P0 |
| TC-6-GR-02 | Unit | `oneway=yes` A→B → only edge A→B; `oneway=-1` → only B→A | P0 |
| TC-6-GR-03 | Unit | `junction=roundabout` without `oneway` → forward only | P1 |
| TC-6-GR-04 | Unit | Nodes not on any kept way are dropped; ids dense 0..n-1 | P0 |
| TC-6-GR-05 | Unit | `two_islands.osm` → only the larger component remains | P0 |
| TC-6-GR-06 | Unit | One-way dead end (reachable but no way out) is removed by SCC pruning | P1 |
| TC-6-GR-07 | Unit | CSR invariants: `edgeOffsets` non-decreasing, last = edge count, all targets < nodeCount | P0 |
| TC-6-GR-08 | Integration | Loader parses `tiny_grid.osm` to the expected node and edge counts; ignores `highway=footway` | P0 |
| TC-6-GR-09 | Integration | Loader handles the real city extract in < 10 s and < 512 MB heap (smoke, tagged `slow`) | P2 |

### Spatial index

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-6-IDX-01 | Unit | Point exactly on a node → that node | P0 |
| TC-6-IDX-02 | Property | `nearest` equals brute-force nearest for random points within the bbox | P0 |
| TC-6-IDX-03 | Unit | Point 1 km from any node with `maxDistance = 300` → null | P0 |
| TC-6-IDX-04 | Unit | Nearest node lies in a neighbouring cell, not the query's cell → still found | P0 |

### Search

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-6-SP-01 | Unit | `tiny_grid`: corner to corner → cost equals the known Manhattan-style minimum; path nodes are a valid chain | P0 |
| TC-6-SP-02 | Unit | `one_way_trap`: the short-looking path is against a one-way → algorithm takes the legal detour | P0 |
| TC-6-SP-03 | Unit | `from == to` → path `[from]`, cost 0 | P0 |
| TC-6-SP-04 | Unit | Unreachable target (graph built without SCC pruning in the test) → null | P1 |
| TC-6-SP-05 | Unit | TIME metric prefers a longer primary road over a shorter residential one when faster | P1 |
| TC-6-SP-06 | Property | On random connected graphs, `AStar.costM == Dijkstra.costM` (±1e-6) for random pairs | P0 |
| TC-6-SP-07 | Property | `AStar.settled ≤ Dijkstra.settled` for the same query | P1 |
| TC-6-SP-08 | Property | Returned path's summed edge lengths == `costM`; each consecutive pair is an existing edge | P0 |
| TC-6-SP-09 | Property | For graphs with only two-way edges, `cost(a,b) == cost(b,a)` | P1 |
| TC-6-SP-10 | Property | Heuristic admissibility: `h(n) ≤ trueCost(n, target)` for sampled nodes (TIME and DISTANCE) | P1 |
| TC-6-SP-11 | Benchmark | City graph: median A* query < 50 ms over 100 random pairs (tagged `slow`, informational) | P2 |

### Polyline & planner

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-6-PL-01 | Unit | Encoding Google's reference points `(38.5,-120.2),(40.7,-120.95),(43.252,-126.453)` equals the reference string (see notes; it contains a pipe and a backtick, so it lives in a Kotlin raw string) | P0 |
| TC-6-PL-02 | Property | `decode(encode(points))` equals points rounded to 1e-5 | P0 |
| TC-6-RS-01 | Unit | Route between two snapped points → polyline starts at `from`, ends at `to`; distance includes snap legs | P0 |
| TC-6-RS-02 | Unit | `from` in the sea (no road within 300 m) → `UnprocessableException(NO_ROAD_NEARBY)` naming `from` | P0 |
| TC-6-RS-03 | Unit | Point outside bbox → `OUTSIDE_COVERAGE` | P1 |
| TC-6-RS-04 | Unit | Both points snap to the same node → 2-point polyline, distance = direct | P1 |

### Integration with trips & API

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-6-API-01 | API | `GET /routes?from&to` on `tiny_grid` graph → 200 with polyline and distance | P0 |
| TC-6-API-02 | API | Malformed `from=abc` → 400 | P0 |
| TC-6-API-03 | API | No road nearby → 422 `code=NO_ROAD_NEARBY` | P0 |
| TC-6-API-04 | API | `POST /trips` stores and returns `route.distanceM`; unroutable pickup → 422, no trip created | P0 |
| TC-6-API-05 | API | `accept` stores approach route from driver's last fix to pickup | P1 |
| TC-6-API-06 | API | Before the graph is loaded, `/health/ready` → 503 with `graph` DOWN | P1 |

### Performance

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-6-PERF-01 | Load | k6 `routes.js`: 50 rps random city pairs for 5 min → p95 < 150 ms, no errors, CPU not saturated on 2 vCPU | P1 |
| TC-6-PERF-02 | API | Planning exceeding the 500 ms timeout → 503 `ROUTING_TIMEOUT` (forced with a slow test planner) | P2 |

## Test implementation notes

- **TC-6-PL-01 expected value**, from Google's Encoded Polyline documentation:
  ```kotlin
  val expected = """_p~iF~ps|U_ulLnnqC_mqNvxq`@"""
  ```

- **Random graph generator (`Arb.roadGraph`)**: generate a grid-like planar graph with random
  jitter, random one-ways (≤ 20%), then SCC-prune — realistic enough and always connected. Keep sizes
  small (50–500 nodes) so properties run fast; print the seed on failure.
- **Independent oracle:** for TC-6-SP-06 Dijkstra is the oracle for A*. For TC-6-SP-01 the expected
  cost is computed by hand and written in the test — never computed by the code under test.
- **Fixture OSM files** are tiny XML files written by hand (10–30 nodes) with a comment block drawing the grid in ASCII and listing expected shortest costs.
- **Benchmarks:** plain JUnit test with `measureTime` and warm-up, tagged `slow` and excluded from
  `check`; or kotlinx-benchmark (JMH) if you want proper numbers.

## Definition of Done

- A* and Dijkstra agree on every property run; A* settles fewer nodes on average (report the ratio in the PR).
- Trip creation returns a real road route on the city graph; the guest/driver apps can draw it.
- All P0 cases pass.
