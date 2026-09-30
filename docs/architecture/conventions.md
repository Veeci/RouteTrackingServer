# Engineering Conventions

## Application services

- One service per aggregate or cohesive capability, not one class per operation:
  `TripService` (create, accept, arrive, start, complete, cancel), `IngestService`, `CommandService`.
- Each public method takes a **command** or **query** object and the calling `Actor`, and returns a
  domain object or a read model: `fun accept(cmd: AcceptTrip, actor: Actor): Trip`.
- A service method is the **transaction boundary**: `tx.inTransaction { ... }`. Repositories never
  open transactions; they join the caller's.
- Authorization happens in the service (it knows the aggregate), not in routes. Routes authenticate
  (who are you), services authorize (may you do this to this trip).
- Side effects for other contexts are **domain events** recorded during the transaction and written
  to the outbox; they are dispatched after commit. No cross-context calls inside a transaction,
  except synchronous read-only calls through `application.api` interfaces (e.g. `RoutePlanner`).

## Domain model

- Aggregates enforce their invariants; state changes go through methods (`trip.accept(driver, now)`),
  not setters. Aggregates record events (`trip.pullEvents()`).
- Value objects are immutable and validated at construction (`GeoPoint`, `Meters`, `TripId`).
- Ids: `@JvmInline value class TripId(val value: UUID)`; UUIDv7 generated in the application layer
  (time-ordered, index-friendly).
- Time comes from an injected `Clock`. Never `Instant.now()` in domain or application code.

## Errors

| Kind | Examples | Mechanism | HTTP / WS |
|---|---|---|---|
| Validation | malformed body, lat out of range | `RequestValidation` plugin + value-object `require` → `ValidationException(field errors)` | 400 / `error{VALIDATION_FAILED}` |
| Not found | unknown trip, or a trip the actor may not see | `NotFoundException(code)` | 404 |
| Forbidden | guest calling a driver action | `ForbiddenException(code)` | 403 / close 4403 |
| Conflict | illegal transition, stale version, driver already on a trip | `ConflictException(code)` | 409 |
| Unprocessable | no road near pickup | `UnprocessableException(code)` | 422 |
| Rate limited | too many requests | Ktor `RateLimit` | 429 + `Retry-After` |
| Unexpected | bugs, DB down | any other exception | 500 / close 1011; logged with trace id, never echoed |

- Domain exceptions extend `DomainException(code: ErrorCode, message)`. `ErrorCode` is an enum of
  stable `SCREAMING_SNAKE` codes; clients switch on `code`, never on `message`.
- REST error bodies are **RFC 9457 Problem Details** (`application/problem+json`):
  `{ "type": "https://rts.dev/problems/trip-not-found", "title": "Trip not found", "status": 404, "code": "TRIP_NOT_FOUND", "detail": "...", "traceId": "..." }`,
  plus `errors: [{field, message}]` for validation.
- One mapping table `ErrorCatalog` (code → status, type, title) used by StatusPages and the WS layer.
- Expected per-item outcomes (rejected fixes in a batch) are **data**, not exceptions.

## REST API design

- Contract-first: edit `openapi/rts-v1.yaml`, then implement. Component tests validate every
  response against the spec. Swagger UI served at `/docs` in dev only.
- Base path `/api/v1`. Resources are nouns; state transitions are sub-resource actions
  (`POST /trips/{id}/accept`), which is clearer than PATCHing a status field.
- JSON `camelCase`; timestamps ISO-8601 UTC; units in names when ambiguous (`distanceM`, `durationS`).
- `POST` creating resources: `201` + `Location`; supports `Idempotency-Key` header (stored 24 h,
  replay returns the original response).
- Optimistic concurrency: responses carry `ETag: "<version>"`; state-changing requests may send
  `If-Match`; mismatch → `412`. Internal version check → `409 CONCURRENT_MODIFICATION`.
- Collections: keyset pagination (`?cursor=&limit=`, max 100), response `{ items, nextCursor }`.
- Versioning: breaking change → `/api/v2`; additive changes are non-breaking; clients ignore unknown fields.
- Headers: `X-Request-Id` accepted/propagated; `traceparent` (W3C) honoured.

## Configuration (12-factor)

- `application.conf` (HOCON) holds defaults; every environment-specific or secret value is overridable
  by environment variable (`DB_URL`, `DB_PASSWORD`, `JWT_SECRET`, ...).
- Hoplite decodes into a typed `AppConfig`; startup fails fast with a list of all invalid/missing keys.
- `deploy/.env.example` documents every variable; real `.env` files are gitignored.
- Environments: `dev`, `test`, `prod` (`APP_ENV`). Dev-only features (Swagger UI, dev token endpoint)
  are mounted only when `APP_ENV=dev|test`, checked at startup.

## Persistence

- Flyway SQL migrations `V<n>__<snake_description>.sql`; merged migrations are immutable.
  Every migration is tested by the migration test (applies all from zero on a real Postgres).
- Exposed DSL in adapters only; one repository per aggregate; repositories return domain objects.
- Constraints live in the database: FKs, `NOT NULL`, `CHECK`s, unique indexes for idempotency and
  business rules (e.g. one active trip per driver). App checks give nice errors; DB constraints give correctness.
- Time columns `timestamptz` (UTC). Money/units never as floats where exactness matters (not an issue here; coordinates are `double precision`).
- Every query used on a hot path has an index; check `EXPLAIN ANALYZE` in the PR for new ones.
- Pool: HikariCP, size from config (default 10); blocking JDBC runs on `Dispatchers.IO`.

## Concurrency

- Structured concurrency only; every long-lived coroutine belongs to the application scope or a
  socket session scope. No `GlobalScope`, no `runBlocking` outside `main` and tests.
- Per driver session, messages are processed sequentially (ordering); across sessions, concurrently.
- Shared mutable state lives behind a port (`PresenceStore`, `TripUpdateBus`) so it can move to Redis.
- Scheduled jobs acquire a Postgres advisory lock so that only one instance runs them.

## Security

- JWT (HS256 in dev, RS256/JWKS-ready interface): verify `iss`, `aud`, `exp` with 30 s leeway; roles from claims.
- Least privilege per endpoint; an **authorization matrix test** covers every route × role.
- Input limits: body size limit, WS frame limit, batch size limit, rate limits per principal.
- No secrets in logs; tokens redacted; coordinates logged only at DEBUG (location is personal data).
- Dependencies: Dependabot/Renovate enabled; `./gradlew dependencyUpdates` reviewed monthly.

## Observability

- Logs: SLF4J + Logback; human-readable in `dev`, JSON (logstash encoder) otherwise. MDC:
  `traceId`, `requestId`, `sessionId`, `tripId`, `userId`.
- Levels: `ERROR` actionable by a human; `WARN` rejected input/degradation; `INFO` lifecycle and
  state changes; `DEBUG` per-message detail.
- Metrics: Micrometer → `/metrics` (Prometheus). RED metrics per route (rate, errors, duration),
  plus domain metrics (`rts_fixes_ingested_total`, `rts_ack_latency_seconds`, `rts_ws_sessions_active`).
- Tracing: OpenTelemetry Java agent attached in the Docker image (Ktor, JDBC auto-instrumented);
  Jaeger in the local compose stack.
- Health: `/health/live` (process), `/health/ready` (DB, migrations applied, routing graph loaded).

## Code style

- ktlint (official) via Spotless, detekt; both gate CI.
- Kotlin: prefer `data class`/`value class`, sealed hierarchies for closed sets, no `!!`,
  `internal` by default for adapter classes.
- Naming: `XxxService` (application), `XxxRepository` (port) / `ExposedXxxRepository` (adapter),
  `XxxRoutes.kt` (REST adapter), `XxxSocket.kt` (WS adapter), `XxxListener` (event adapter),
  `XxxJob` (scheduled adapter), `XxxRequest`/`XxxResponse` (REST DTOs), `InMemoryXxx` (test or single-node adapters).

## Git and delivery

- Trunk-based: short-lived branches `feat/<phase>-<topic>`, squash-merge to `main`; `main` is always deployable.
- Conventional Commits (`feat(trip): add accept endpoint`); changelog generated from them.
- PR checklist: tests (case IDs), OpenAPI/AsyncAPI updated, migration reviewed, docs updated, no new warnings.
- CI on every PR: build, lint, all tests (Testcontainers), Docker image build. On `main`: push image to GHCR tagged with the git SHA.
