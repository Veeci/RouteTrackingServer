# Phase 1 — Platform Skeleton

Branch: `feat/p1-platform` · Depends on: 0 · Unlocks: 2

## Goal

The cross-cutting platform every context will plug into: typed configuration, DI, database access
with migrations and a transaction runner, error handling as Problem Details, request validation,
authentication scaffolding, observability (structured logs, metrics, tracing, health), a WebSocket
session framework, and a contract-first OpenAPI document. No business features yet.

## Scope

**In:** `platform/*` packages, `shared/` kernel basics, Koin bootstrap, Hoplite config, Flyway V1
(`schema_info` only), `TransactionRunner`, `ErrorCatalog` + StatusPages, `RequestValidation`,
CallId + CallLogging + MDC, Micrometer `/metrics`, `/health/live|ready`, OpenAPI skeleton +
Swagger UI (dev), WS framework with `/ws/v1/echo` (dev only), graceful shutdown.

**Out:** users/JWT issuance (phase 3; the verifier interface exists), business tables.

## Design

### Startup sequence (`Application.kt`)

1. Load `AppConfig` (Hoplite: `application.conf` → profile file → env). Fail with all errors listed.
2. Start Koin with `platformModule(config)` + context modules (none yet).
3. Run Flyway migrations (configurable: on in dev/test, off in prod where a migration job runs them).
4. Install plugins in order: CallId → CallLogging → metrics → ContentNegotiation → RequestValidation →
   StatusPages → Authentication → RateLimit → WebSockets → Routing.
5. Mount routes: health, metrics, docs (dev), contexts.
6. Register `ApplicationStopping` hook: stop accepting sockets, close them with 1001, drain for `shutdownGrace`, close pool.

### Platform components

| Component | Responsibility |
|---|---|
| `AppConfig` | `http`, `db` (url, user, password, pool size), `ws` (ping, timeout, maxFrameBytes, rate), `security` (jwt issuer/audience/secret), `app` (env, version) |
| `DataSourceFactory` | HikariCP from config; exposes `DataSource` |
| `TransactionRunner` | `suspend fun <T> inTransaction(block: suspend () -> T): T` over Exposed `newSuspendedTransaction(Dispatchers.IO)`; nested calls join the outer transaction |
| `ErrorCatalog` | `ErrorCode` → `(HttpStatusCode, type URI, title)` |
| StatusPages config | `DomainException` → Problem Details via catalog; `RequestValidationException` → 400 with field errors; `BadRequestException`/`SerializationException` → 400; `Throwable` → 500 with `traceId`, cause logged |
| `HealthRegistry` | list of `HealthIndicator`s (`db`: `select 1` + Flyway state); `/health/ready` aggregates |
| `WsSessionRunner` | Generic loop for socket endpoints: handshake timeout, decode with `ProtocolJson`, per-session rate limiter, error frames, close codes, MDC for the session, structured open/close logs |
| Metrics | `MicrometerMetrics` plugin with Prometheus registry; `http_server_requests` timers; JVM/Hikari binders |
| Logging | `logback.xml` (dev, pattern) and `logback-json.xml` (non-dev, logstash encoder) selected by `APP_ENV` |

### Shared kernel (initial)

`Clock` (interface + `SystemClock`), `DomainException` + `ErrorCode`, `IdGenerator` (UUIDv7).

### OpenAPI

`app/src/main/resources/openapi/rts-v1.yaml` with `info`, servers, security scheme (bearer JWT),
the `Problem` schema, and the health endpoints. Swagger UI at `/docs` when `APP_ENV=dev`.

## Tasks

- [ ] T1.1 Hoplite `AppConfig` with profiles `dev`, `test`, `prod`; `.env.example` updated.
- [ ] T1.2 Koin bootstrap; `platformModule`; test that the graph verifies.
- [ ] T1.3 Hikari + Exposed + Flyway V1; `TransactionRunner`.
- [ ] T1.4 `DomainException`, `ErrorCode`, `ErrorCatalog`, StatusPages → Problem Details.
- [ ] T1.5 RequestValidation, body size limit, RateLimit plugin (default policy).
- [ ] T1.6 CallId, CallLogging with MDC, JSON logging profile.
- [ ] T1.7 Micrometer + `/metrics`; health registry + endpoints.
- [ ] T1.8 `WsSessionRunner` + dev-only `/ws/v1/echo`.
- [ ] T1.9 OpenAPI skeleton + Swagger UI (dev); OpenAPI response validation in the API test harness.
- [ ] T1.10 Graceful shutdown; Dockerfile `HEALTHCHECK` → `/health/live`.
- [ ] T1.11 Remove generator samples.

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-1-CFG-01 | Unit | Complete config for each profile decodes to `AppConfig` | P0 |
| TC-1-CFG-02 | Unit | Missing `db.password` and invalid `http.port` → one startup error listing **both** keys | P0 |
| TC-1-CFG-03 | Unit | Env var `DB_URL` overrides the file value | P1 |
| TC-1-DI-01 | Wiring | Koin `verify()` passes for the full module list | P0 |
| TC-1-TX-01 | Integration | Two inserts in `inTransaction`, second fails → neither row exists | P0 |
| TC-1-TX-02 | Integration | Nested `inTransaction` joins the outer; outer rollback rolls back inner writes | P0 |
| TC-1-MIG-01 | Integration | Flyway migrates an empty database; `flyway_schema_history` has V1 | P0 |
| TC-1-ERR-01 | API | Route throwing `IllegalStateException("secret")` → 500 `application/problem+json`, has `traceId`, body does not contain "secret" | P0 |
| TC-1-ERR-02 | API | Route throwing a `DomainException(TRIP_NOT_FOUND)` (test-only route) → 404 Problem with `code=TRIP_NOT_FOUND` and catalog `type` | P0 |
| TC-1-ERR-03 | API | Malformed JSON body → 400 Problem `code=MALFORMED_REQUEST` | P0 |
| TC-1-ERR-04 | API | Validation failure → 400 Problem with `errors[{field, message}]` | P0 |
| TC-1-ERR-05 | API | Body over the size limit → 413 Problem | P1 |
| TC-1-ERR-06 | API | Unknown route → 404 Problem | P1 |
| TC-1-HLT-01 | API | `/health/live` → 200 even when DB is down | P0 |
| TC-1-HLT-02 | API | `/health/ready` → 200 with `db: UP`; with DB container paused → 503 `db: DOWN` | P0 |
| TC-1-OBS-01 | API | Response carries `X-Request-Id` (generated, or echoed if provided) | P1 |
| TC-1-OBS-02 | API | `/metrics` exposes `http_server_requests_seconds_count` including the previous request's route | P1 |
| TC-1-RL-01 | API | Exceeding the default rate limit → 429 with `Retry-After` | P1 |
| TC-1-WS-01 | API | `/ws/v1/echo` echoes text frames | P0 |
| TC-1-WS-02 | API | Frame larger than `maxFrameBytes` → close 1009 | P0 |
| TC-1-WS-03 | API | Invalid JSON on a `WsSessionRunner` endpoint → `error{code=MALFORMED_MESSAGE}`, session stays open | P0 |
| TC-1-WS-04 | API | `/ws/v1/echo` is not mounted when `APP_ENV=prod` (upgrade → 404) | P1 |
| TC-1-API-01 | Contract | Health responses conform to `rts-v1.yaml` (validator in harness) | P0 |
| TC-1-SHD-01 | Integration | Application stop closes an open socket with 1001 within the grace period | P2 |

## Test implementation notes

- **Test-only routes** (for ERR cases) are added through a Koin override / extra routing block in the
  `TestApp` builder — never in production code.
- **DB down (TC-1-HLT-02):** `container.dockerClient.pauseContainerCmd(id)`; unpause in `finally`.
  Tag the test so it runs last in its class; it's slow-ish.
- **Config tests** use Hoplite's `ConfigLoaderBuilder` with in-memory property sources; no files.

## Definition of Done

- Any exception anywhere becomes a Problem Details response with a trace id and nothing leaked.
- New contexts only need: a Koin module, a routes function, migrations — no platform changes.
- All P0 cases pass; `./gradlew check` green; Docker image healthcheck uses `/health/live`.
