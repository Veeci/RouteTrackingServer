# Testing Strategy

## Principles

1. **Test against real infrastructure where it matters.** Repositories, migrations and full-stack API
   tests run against real PostgreSQL (Testcontainers), not H2 or mocks. SQL behaviour, constraints
   and transactions are part of what we test.
2. **Fast feedback for business rules.** Domain logic and application services are unit-tested with
   in-memory adapters in milliseconds.
3. **Contracts are executable.** OpenAPI and AsyncAPI documents are validated against real responses
   and messages, so docs can't drift from behaviour.
4. **Determinism.** Injected `Clock`, seeded randomness, virtual time for schedulers, no sleeps.
5. **Performance is tested, not assumed.** Load tests with explicit SLO thresholds run before a phase is closed where throughput matters.

## Test types

| Type | What it proves | Scope | Tools | Source set / task |
|---|---|---|---|---|
| **Unit** | Domain rules, algorithms, value objects | Class/function | JUnit 5, kotest-assertions | `test` |
| **Property** | Invariants over generated inputs (geometry, routing, pipeline) | Function | kotest-property | `test` |
| **Service** | Application service behaviour: orchestration, authorization, events recorded | Service + in-memory adapters | JUnit 5, coroutines-test | `test` |
| **Persistence integration** | Repository SQL, constraints, concurrency, migrations | Adapter + real Postgres | Testcontainers | `integrationTest` |
| **API (component)** | HTTP/WS behaviour of the whole app: routing, auth, serialization, error mapping, contract conformance | Full app in-process + real Postgres | Ktor `testApplication` (`testApp`); WebSocket classes use `socketApp`: Netty + CIO over a real port, because the in-memory engine sometimes upgrades a socket without starting its handler. Testcontainers, OpenAPI validator | `integrationTest` |
| **Contract** | Wire format stability for the SDK | `protocol` module | Golden JSON files, AsyncAPI JSON-schema validation | `protocol:test` |
| **Architecture** | Context boundaries, layer rules, naming | Codebase | Konsist | `test` |
| **Wiring** | DI graph resolves; config loads for each profile | Koin modules, config | Koin `verify()`, Hoplite | `test` |
| **Scenario (E2E)** | User journeys over real sockets: simulator driver + guest + REST | Running app + Postgres (+ Redis in phase 10) | `tools:simulator` clients, Testcontainers | `e2eTest` |
| **Load / performance** | Throughput, latency SLOs, no leaks under sustained load | Deployed container stack | k6 | `load-tests/`, manual + nightly CI |
| **Security** | AuthN/AuthZ matrix, input limits | Full app | API test harness | `integrationTest` |

Rough distribution by count: 60% unit/property/service, 30% integration/API, 10% E2E. Load tests are few and long.

### The admin dashboard (`admin-web/`, phase 12)

The web app has its own layers. They run with npm in `admin-web/`, not with Gradle.

| Type | What it proves | Tools | Where |
|---|---|---|---|
| **Web unit** | Error parsing, retry rules, the redirect check, the session store and the re-mint timer | Vitest, fake timers | `src/**/*.test.ts` |
| **Web component** | Pages and the app shell with the real router and query client: login, the 401 round trip, role gating, error states | Vitest, Testing Library, MSW | `src/**/*.test.tsx` |
| **Web contract** | The app and its mocks match `rts-v1.yaml`: MSW handlers are typed by the generated `paths`, so a body the spec forbids fails to compile | openapi-typescript, openapi-msw, `tsc` | `npm run generate`, then `npm run typecheck` |
| **Web E2E** | The production build under its Content Security Policy against a real server and Postgres | Playwright (Chromium), `vite preview` | `e2e/` |

## Test infrastructure

### Postgres

- One Postgres 16 container per test JVM (singleton, `withReuse(true)` locally), Flyway applied once.
- Isolation per test: `TRUNCATE ... RESTART IDENTITY CASCADE` of all tables in `@BeforeEach`
  (simple, reliable with multiple connections). Transaction-rollback isolation is not used because
  API tests and concurrency tests span multiple connections.
- Test data via **object mothers / builders** (`TripMother.requested()`, `aFix { accuracyM = 5.0 }`)
  and repository calls, not SQL scripts — except migration tests, which use SQL.

### In-memory adapters

For service tests, each outbound port has an `InMemoryXxx` implementation in `src/testFixtures` of
`app`. They behave like the real adapter (e.g. enforce the same uniqueness). Parity is proven by
**port contract tests**: one abstract test class per port, run against both implementations.

```kotlin
abstract class FixBatchRepositoryContract {
    abstract fun repository(): FixBatchRepository
    @Test fun `storing the same (session, seq) twice keeps one copy`() = runTest { ... }
}
class InMemoryFixBatchRepositoryTest : FixBatchRepositoryContract() { ... }
class ExposedFixBatchRepositoryIT : FixBatchRepositoryContract() { ... }   // integrationTest
```

MockK is used only for verifying interactions with things that have no meaningful fake (rare).

### API test harness

```kotlin
class ApiTest {                                 // base class / extension
    val app = TestApp(profile = "test", db = PostgresContainer.shared)   // real Koin graph, real DB, FakeClock override
    fun api(block: suspend ApiClient.() -> Unit)                           // HTTP client with JSON + auth helpers
    fun ws(path: String, token: String, block: suspend WsClient.() -> Unit)
}
```

- Tokens minted directly with the test signing key (`tokens.guest()`, `tokens.driver()`).
- Every HTTP response in API tests passes through an **OpenAPI response validator** (Atlassian
  `swagger-request-validator-core` or `openapi4j`): an undocumented field, status or content type fails the test.
- WS messages in API tests are validated against the JSON schemas extracted from the AsyncAPI document.

### Time and scheduling

- `FakeClock` bound in the test Koin module; services and jobs read time only from `Clock`.
- Jobs take a `CoroutineScope` and dispatcher; tests run them with `StandardTestDispatcher` and
  `advanceTimeBy`.

### Fixtures

- `app/src/testFixtures/resources/routes/*.gpx` — recorded or generated tracks: straight line, jitter,
  GPS jump, tunnel gap, detour, U-turn.
- `app/src/testFixtures/resources/graphs/*.osm` — hand-written OSM graphs with known shortest paths.
- `protocol/src/test/resources/golden/*.json` — one file per message type.

## Case IDs and traceability

Cases are named `TC-<phase>-<AREA>-<nn>` in phase docs. The test's KDoc starts with the ID:

```kotlin
/** TC-2-ACK-03 */
@Test fun `duplicate seq is acknowledged but stored once`() = runTest { ... }
```

Columns in every phase's case table: **ID · Type · Given / When / Then · Priority** (P0 must pass to
merge the phase; P1 should; P2 nice to have). Deferred cases are listed with a reason in the PR.

## Quality gates (CI)

| Gate | Threshold |
|---|---|
| Build, ktlint, detekt | no errors, no new warnings |
| Unit + service + architecture + wiring | 100% pass |
| Integration + API + contract (Testcontainers on GitHub runners) | 100% pass |
| Coverage (Kover, line) | ≥ 80% for `domain` and `application` packages; adapters measured, not gated |
| E2E | 100% pass on `main` and on PRs labelled `e2e` |
| Load (nightly, from phase 2) | SLO thresholds in each phase doc (k6 `thresholds` fail the run) |
| Mutation testing (Pitest, optional from phase 6) | ≥ 70% mutation score on `routing.domain` and `tracking.domain` |
| Admin web (job `Admin web`, every PR) | typecheck, ESLint and Prettier clean, every Vitest test passes, ≥ 80% line coverage in `src/api` and `src/auth`, the build passes, `npm audit` finds nothing high or critical |
| Admin web E2E (job `Admin web E2E`, every PR) | 100% pass. Optional for its first week (phase 12b) |

## Commands

| Command | Runs | Needs Docker |
|---|---|---|
| `./gradlew test` | unit, property, service, architecture, wiring, contract | no |
| `./gradlew integrationTest` | persistence, API, security | yes |
| `./gradlew e2eTest` | scenarios | yes |
| `./gradlew check` | all of the above + lint + coverage verification | yes |
| `k6 run load-tests/<script>.js` | load test against `docker compose up` stack (without a local k6: the `grafana/k6` image, see the phase 2 plan) | yes |
| `npm run coverage` in `admin-web/` | web unit and component tests, with the coverage gate | no |
| `npm run e2e` in `admin-web/` | Playwright against `vite preview`, with a server on `localhost:8080` (`admin-web/README.md`) | db and server |

## Rules of thumb

- Test behaviour via public APIs; name tests by behaviour (`` `rejects fix recorded in the future` ``).
- A bug fix starts with a failing test.
- Don't mock what you own — use the in-memory adapter.
- Don't assert on log output except for security-relevant redaction tests.
- Flaky test = bug. Quarantine with an issue link within a day; never retry-until-green in CI.
