# RouteTrackingServer — Documentation

Backend for the RouteTracking SDK/app: driver–guest trip tracking over WebSocket, server-side trip
events, shortest-path routing, remote device control, and SDK health telemetry.

Built phase by phase, to the standards of a production backend: a modular monolith with bounded
contexts, contract-first APIs, transactional outbox, real-database testing, containerized delivery,
and observability from day one.

## Map

| Area | Doc | What it answers |
|---|---|---|
| Product | [product/features.md](product/features.md) | What we build and for whom |
| Architecture | [architecture/overview.md](architecture/overview.md) | Bounded contexts, layers, boundaries, key decisions |
| | [architecture/conventions.md](architecture/conventions.md) | Services & transactions, errors (RFC 9457), REST design, config, persistence, security, observability, delivery |
| | [architecture/protocol.md](architecture/protocol.md) | WebSocket message contract, ACK/resume semantics (formalised in AsyncAPI) |
| Testing | [testing/strategy.md](testing/strategy.md) | Test types, infrastructure, contract validation, quality gates |
| Plan | [plan/](plan/) | One file per phase: scope, design, tasks, test cases, Definition of Done |

## Phases

| # | Phase | Branch | Delivers | Depends on |
|---|---|---|---|---|
| 0 | [Foundation](plan/phase-00-foundation.md) | `feat/p0-foundation` | Modules, lint, test suites, Dockerfile, compose, CI + image | – |
| 1 | [Platform](plan/phase-01-platform.md) | `feat/p1-platform` | Config, DI, DB + transactions, Problem Details, validation, observability, WS framework, OpenAPI | 0 |
| 2 | [Ingest + ACK](plan/phase-02-ingest.md) | `feat/p2-ingest` | `tracking` context, fix pipeline, idempotent ingest, outbox, simulator, first load test | 1 |
| 3 | [Trips & live tracking](plan/phase-03-live-trip.md) | `feat/p3-live-trip` | `identity`, `trip`, `live` contexts; REST with idempotency/ETags; guest socket; authz matrix | 2 |
| 4 | [Trip events](plan/phase-04-events.md) | `feat/p4-events` | Hysteresis rules, auto-arrive, stale detection job (cluster-safe), device status | 3 |
| 5 | [Device commands](plan/phase-05-commands.md) | `feat/p5-commands` | `devicecontrol` context, at-least-once delivery, command policies | 3 |
| 6 | [Routing](plan/phase-06-routing.md) | `feat/p6-routing` | OSM graph (CSR), grid index, Dijkstra/A*, `RoutePlanner` | 3 |
| 7 | [Route tracking](plan/phase-07-route-tracking.md) | `feat/p7-route-tracking` | Snapping, along-route ETA, off-route, re-route | 4, 6 |
| 8 | [History & export](plan/phase-08-history.md) | `feat/p8-history` | Summaries, keyset-paginated history, replay, GPX/GeoJSON | 3 |
| 9 | [Telemetry](plan/phase-09-telemetry.md) | `feat/p9-telemetry` | Session health, GMS vs AOSP report | 2, 4 |
| 10 | [Production & scale](plan/phase-10-production.md) | `feat/p10-production` | Redis adapters, multi-instance, deploy pipeline, ops, capacity | 3–5 (ideally all) |

After phase 3, phases 4, 5, 6, 8 are independent; 7 needs 4 and 6; 9 needs 4 for device status.

## How to use a phase doc

Sections, in order: **Goal → Scope → Design → Tasks → Test plan → Test implementation notes →
Definition of Done**. Update the OpenAPI/AsyncAPI contract first, write the P0 tests (failing), then
implement until they pass.
