# Phase 1 — Platform Skeleton

Branch: `feat/p1-platform` · Depends on: 0 · Unlocks: 1.5, 2

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

## Design (as built)

### Startup sequence (`Application.kt`)

1. Load `AppConfig` (Hoplite: env vars → `config/<APP_ENV>.conf` → `config/base.conf`). Any problem stops
   the process with exit code 1 and one message listing every bad key.
2. Create the process-wide Prometheus registry; start Koin (`KoinIsolated`) with `appModule(config, metrics)`.
   The connection pool is built eagerly, so an unreachable database fails startup.
3. Run Flyway migrations when `db.migrateOnStart` (dev/test only; prod runs them as a release step).
4. Register the graceful-shutdown hook.
5. Install plugins: CallId + CallLogging → MicrometerMetrics + `/metrics` → ContentNegotiation →
   RequestBodyLimit + RequestValidation + RateLimit → StatusPages → WebSockets.
6. Mount routes: health; `/ws/v1/echo` outside prod; Swagger UI `/docs` in dev; contexts (from phase 2).
7. `embeddedServer(Netty)` with `http.shutdownGracePeriod` / `http.shutdownTimeout`.

On SIGTERM Ktor raises `ApplicationStopPreparing` (readiness → DOWN, every socket closed with 1001), stops
accepting connections, drains in-flight requests, then raises `ApplicationStopping` (Koin closes the pool).

### Platform components

| Component | Where | Responsibility |
|---|---|---|
| `AppConfig` | `platform/config` | `app` (env, version), `http` (port, maxBodyBytes, rateLimitPerMinute, shutdownGracePeriod, shutdownTimeout), `db` (url, user, password as `Secret`, maxPoolSize, migrateOnStart), `ws` (pingPeriod, timeout, maxFrameBytes, messagesPerSecond). `security` arrives with phase 3 |
| `createDataSource` | `platform/db` | HikariCP from config: 5 s connection timeout, pool meters in the registry |
| `Migrations` | `platform/db` | Flyway over `db/migration`; returns how many were applied |
| `TransactionRunner` | `platform/db` | `suspend fun <T> inTransaction(block: suspend () -> T): T` over Exposed `suspendTransaction` on `Dispatchers.IO`; nested calls join the outer transaction; no implicit retries |
| `ErrorCatalog` | `platform/http` | `ErrorCategory` → HTTP status, code → `type` URN and title; `PlatformError` for non-domain failures |
| StatusPages config | `platform/http` | domain → its category's status; validation → 400 with `errors[]`; too large → 413; no converter → 415; unreadable → 400; 429 gets a body; unknown route → 404; anything else → 500 with `traceId`, cause logged, message never sent |
| Request guards | `platform/http` | body limit (not on WebSocket upgrades), `Validatable` bodies checked inside `receive()`, per-IP token bucket |
| Request logging | `platform/observability` | `X-Request-Id` kept (if safe) or generated, in MDC and `Problem.traceId`; one line per request, probes excluded |
| Logging | `resources/logback*.xml` | `LOG_FORMAT=text` (default) or `json` (logstash encoder; the Docker image sets it) |
| Metrics | `platform/observability` | `MicrometerMetrics` → `http_server_requests_seconds_*`; JVM, Hikari and `ws_sessions_open` meters; `/metrics` |
| `HealthRegistry` | `platform/observability` | `HealthIndicator`s (`db`: `Connection.isValid`) run in parallel under a 2 s deadline; DOWN once shutdown begins |
| `WsSessionRunner` | `platform/ws` | decode with `ProtocolJson`, per-session token bucket, `error` frames (session stays open), 1011 on handler bugs, session id in MDC, open/close logs |
| `WsSessionRegistry` | `platform/ws` | open sessions: shutdown closes them with 1001; gauge for `/metrics` |
| Graceful shutdown | `platform/lifecycle` | the `ApplicationStopPreparing` hook above, idempotent |

### Shared kernel (initial)

`DomainException`, `ErrorCode`, `ErrorCategory` (`shared/`). Time is `java.time.Clock` (UTC), injected.

### Protocol module

`ProtocolJson` (discriminator `type`, unknown keys ignored, nulls omitted), `ServerMessage` with
`ErrorMessage`, `WsErrorCodes`; wire-format tests.

### OpenAPI

`app/src/main/resources/openapi/rts-v1.yaml` (OpenAPI 3.1): health and metrics endpoints, `Problem`,
`FieldError`, the shared 429 response, bearer scheme. Swagger UI at `/docs` in dev. `shouldMatchContract()`
validates real responses in API tests; `ApiContractTest` proves the validator rejects broken ones.

### Changes from the original plan

| Planned | Built | Why |
|---|---|---|
| Own `Clock` interface + `SystemClock` | `java.time.Clock` | The JDK type already is that interface, with `fixed()` for tests |
| `ErrorCatalog`: code → status | category → status | A per-code map in `platform` would have to import every context (wrong dependency direction); the category also works on WebSockets, which have no HTTP status |
| `type` as a URL | `urn:rts:error:<code>` | There is no public docs page to link to yet |
| Log format chosen by `APP_ENV` | `LOG_FORMAT` | Logback starts before our config loads; an explicit switch is clearer |
| Health `db`: `select 1` + Flyway state | `Connection.isValid` | Flyway state is checked by startup migrations / the release step instead |
| Handshake timeout in `WsSessionRunner` | Phase 2 | It needs the `hello` message, which arrives in phase 2; so does `UNKNOWN_MESSAGE` (phase 1 has `MALFORMED_MESSAGE` and `INVALID_MESSAGE`) |
| `IdGenerator` (UUIDv7) | Phase 2 | Nothing generates ids yet |
| `/metrics` and per-IP rate limit as is | Phase 1.5 follow-ups | Behind Fly's proxy: protect `/metrics`, and add the forwarded-header plugin so the rate limit sees client IPs |

Found while building, fixed and covered by tests: Ktor's `RequestBodyLimit` stalls WebSocket traffic
(upgrades are now exempt); "payload too large" and "no converter" exceptions fell through to 500; the
logback pattern used `YYYY` (week-based year); Ktor raises the stop event twice (hook is idempotent).

## Tasks

- [x] T1.1 Hoplite `AppConfig` with profiles `dev`, `test`, `prod`; `.env.example` updated.
- [x] T1.2 Koin bootstrap; `platformModule`; test that the graph verifies.
- [x] T1.3 Hikari + Exposed + Flyway V1; `TransactionRunner`.
- [x] T1.4 `DomainException`, `ErrorCode`, `ErrorCatalog`, StatusPages → Problem Details.
- [x] T1.5 RequestValidation, body size limit, RateLimit plugin (default policy).
- [x] T1.6 CallId, CallLogging with MDC, JSON logging profile.
- [x] T1.7 Micrometer + `/metrics`; health registry + endpoints.
- [x] T1.8 `WsSessionRunner` + dev-only `/ws/v1/echo`.
- [x] T1.9 OpenAPI skeleton + Swagger UI (dev); OpenAPI response validation in the API test harness.
- [x] T1.10 Graceful shutdown; Dockerfile `HEALTHCHECK` → `/health/live`.
- [x] T1.11 Remove generator samples.

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
| TC-1-WS-04 | API | `/ws/v1/echo` is mounted in `dev`, `test`, `staging` and not in `prod` (upgrade → 404) | P1 |
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
