# Architecture Overview

## Style: modular monolith, hexagonal inside each module

One deployable service, split internally into **bounded contexts** (business capabilities). Each
context is a vertical slice with its own domain model, application services, and adapters
(ports & adapters / hexagonal). Contexts talk to each other only through explicit public interfaces
or domain events — never through each other's tables or internal classes.

Why this and not microservices: one team, one database, one deploy is the right cost for this scope.
Why not a flat "controllers/services/repositories" layout: routing, tracking and trips have genuinely
different models and change for different reasons; slicing by context keeps each one understandable
and lets any of them be extracted later (e.g. routing as its own service) without a rewrite.

## Bounded contexts

| Context | Responsibility | Owns (tables) | Publishes | Consumes |
|---|---|---|---|---|
| `identity` | Users, roles, token verification | `users` | – | – |
| `tracking` | Device sessions, fix ingest, fix pipeline, ACK semantics | `tracking_sessions`, `fix_batches`, `fixes`, `device_status_samples` | `FixesAccepted`, `DeviceStatusReported`, `SessionClosed` | – |
| `trip` | Trip lifecycle, state machine, progress, trip events, summaries, history queries | `trips`, `trip_routes`, `trip_events`, `trip_summaries` | `TripStatusChanged`, `TripEventRaised`, `RouteUpdated` | `FixesAccepted`, `DeviceStatusReported` |
| `routing` | Road graph, spatial index, shortest path | none (graph is loaded from file) | – | – (called synchronously via `RoutePlanner`) |
| `devicecontrol` | Commands to devices, delivery and acknowledgement, command policies | `device_commands` | – | `TripStatusChanged`, `GuestSubscriptionChanged` |
| `live` | Real-time fan-out to guest sockets, presence | none (in-memory, Redis in phase 10) | `GuestSubscriptionChanged` | `TripEventRaised`, `FixesAccepted`, `RouteUpdated` |
| `telemetry` | Session health metrics, provider comparison reports | `session_health` | – | `SessionClosed` (reads `tracking` data through its query interface) |

## Layers inside a context

```
<context>/
  domain/            entities, value objects, domain services, domain events, domain exceptions — pure Kotlin
  application/       application services (transaction boundary, orchestration, authorization),
                     port interfaces: port/out (repositories, gateways), public API interfaces for other contexts
  adapter/in/        web (REST routes), ws (socket handlers), event (listeners for other contexts' events), job (scheduled)
  adapter/out/       persistence (Exposed), external systems, publishers
  <Context>Module.kt Koin module wiring this context
```

Dependency direction inside a context: `adapter → application → domain`. Domain depends on nothing
but `shared` kernel. Application depends on domain and its own ports. Adapters implement ports.

### Rules between contexts (enforced by Konsist tests)

1. A context may import another context's `application.api` package (its published interfaces and
   DTO-like records) and its `domain.event` package (event classes). Nothing else.
2. No context reads another context's tables. Cross-context reads go through `application.api`
   query interfaces (e.g. `tracking.application.api.TrackQuery` used by `trip` and `telemetry`).
3. `shared/` contains only the shared kernel (ids, `GeoPoint`, units, `Clock`, base exception) and
   must not import any context.
4. `platform/` (technical infrastructure) may be used by adapters, never by `domain`.

## Repository layout

```
route-tracking-server/
├── app/                              the service (single Gradle module)
│   └── src/main/kotlin/veeci/practicing/rts/
│       ├── Application.kt            entry point: loads config, starts Koin, installs platform, mounts contexts
│       ├── platform/                 technical cross-cutting concerns
│       │   ├── config/               typed AppConfig (Hoplite), profiles
│       │   ├── db/                   DataSource (Hikari), Flyway, TransactionRunner, Exposed setup
│       │   ├── http/                 Problem Details, validation, pagination, idempotency keys, OpenAPI serving
│       │   ├── ws/                   socket session framework: codec, rate limit, close codes, MDC
│       │   ├── security/             JWT verification, principal → Actor, role guards
│       │   ├── events/               in-process event bus + transactional outbox relay
│       │   ├── jobs/                 scheduler, distributed lock (Postgres advisory lock)
│       │   └── observability/        logging (JSON), metrics (Micrometer), health checks, tracing hooks
│       ├── shared/                   shared kernel
│       ├── identity/  tracking/  trip/  routing/  devicecontrol/  live/  telemetry/
│       └── ...
│   └── src/main/resources/           application.conf, db/migration (Flyway), openapi/, asyncapi/
├── protocol/                         wire contract library (kotlinx.serialization DTOs), published for the SDK
├── tools/simulator/                  driver/guest CLI clients, also used by scenario tests
├── load-tests/                       k6 scripts
├── deploy/                           Dockerfile, docker-compose.yml, otel-collector config
└── docs/
```

Why only three Gradle modules: the service is one deployable; internal boundaries are enforced by
architecture tests, which is the common practice for modular monoliths (Spring Modulith-style).
Separate Gradle modules are used only where there is a real separate artifact: `protocol` (consumed
by the Android SDK) and `simulator` (a separate executable).

## Runtime view

```
                 ┌─────────────────────────── app (one JVM) ────────────────────────────┐
Driver SDK ─ws──►│ tracking.adapter.in.ws ─► IngestService ─tx─► fixes + outbox          │
                 │                                   │ after commit                        │
                 │                                   ▼                                     │
                 │                        event bus: FixesAccepted                         │
                 │           ┌───────────────────────┼──────────────────────┐              │
                 │           ▼                       ▼                      ▼              │
                 │  trip.adapter.in.event    live (position fan-out)   telemetry           │
                 │  TripProgressService ─► TripEventRaised ─► live ─ws──────────────────────┼─► Guest app
                 │  routing.RoutePlanner (sync call)                                       │
Guest app ─REST─►│ trip.adapter.in.web ─► TripService ─tx─► trips                          │
                 └───────────────┬──────────────────────────────────────────────────────────┘
                                 ▼
                           PostgreSQL 16         (Redis added in phase 10 for multi-instance fan-out/presence)
```

## Key decisions

| Decision | Choice | Alternatives | Why |
|---|---|---|---|
| Framework | Ktor 3 (Netty) | Spring Boot, Micronaut | Coroutine-native WebSockets, explicit setup, small footprint |
| Architecture | Modular monolith, hexagonal per context | Layered MVC, microservices | Clear ownership without distributed-system cost |
| DI | Koin (`koin-ktor`), one module per context | Manual wiring, Ktor DI plugin | Standard in the Ktor ecosystem; `verify()` catches wiring errors in tests |
| Config | Hoplite, HOCON + env overrides | Ktor `ApplicationConfig` by hand | Typed, validated at startup, 12-factor friendly |
| DB access | Exposed (DSL, not DAO) + HikariCP | jOOQ, JDBI, JPA | Type-safe SQL in Kotlin, no ORM magic, explicit queries |
| Migrations | Flyway, SQL files | Liquibase | Plain SQL, reviewable |
| Transactions | Application service is the transaction boundary (`TransactionRunner`) | Per-repository transactions | A use case's writes (+ outbox) commit or roll back together |
| Errors | Domain exceptions with stable codes → RFC 9457 Problem Details | Result types everywhere | Idiomatic for services; one mapping point; clients switch on `code` |
| Events | In-process bus + transactional outbox | Direct calls, Kafka | Reliable after-commit publishing without new infra; Kafka if extracted later |
| REST contract | OpenAPI 3.1, contract-first (`openapi/rts-v1.yaml`), Swagger UI in dev | Code-first generation | The spec is reviewed like code; tests validate responses against it |
| WS contract | AsyncAPI 3.0 (`asyncapi/rts-ws-v1.yaml`) + golden JSON samples | Prose only | Machine-readable contract the SDK can test against |
| Realtime fan-out | In-process `SharedFlow`; Redis pub/sub in phase 10 | Redis from day one | YAGNI until multi-instance; the port makes it a drop-in |
| Logging/metrics/tracing | SLF4J + Logback (JSON in non-dev), Micrometer/Prometheus, OpenTelemetry Java agent | Custom | Industry standard, zero-code tracing |
| Packaging | Docker image (multi-stage), docker-compose for local stack | Fat JAR only | Same artifact from laptop to prod |
