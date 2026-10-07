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
| **API (component)** | HTTP/WS behaviour of the whole app: routing, auth, serialization, error mapping, contract conformance | Full app in-process + real Postgres | Ktor `testApplication`, Testcontainers, OpenAPI validator | `integrationTest` |
| **Contract** | Wire format stability for the SDK | `protocol` module | Golden JSON files, AsyncAPI JSON-schema validation | `protocol:test` |
| **Architecture** | Context boundaries, layer rules, naming | Codebase | Konsist | `test` |
| **Wiring** | DI graph resolves; config loads for each profile | Koin modules, config | Koin `verify()`, Hoplite | `test` |
| **Scenario (E2E)** | User journeys over real sockets: simulator driver + guest + REST | Running app + Postgres (+ Redis in phase 10) | `tools:simulator` clients, Testcontainers | `e2eTest` |
| **Load / performance** | Throughput, latency SLOs, no leaks under sustained load | Deployed container stack | k6 | `load-tests/`, manual + nightly CI |
| **Security** | AuthN/AuthZ matrix, input limits | Full app | API test harness | `integrationTest` |

Rough distribution by count: 60% unit/property/service, 30% integration/API, 10% E2E. Load tests are few and long.

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

## Commands

| Command | Runs | Needs Docker |
|---|---|---|
| `./gradlew test` | unit, property, service, architecture, wiring, contract | no |
| `./gradlew integrationTest` | persistence, API, security | yes |
| `./gradlew e2eTest` | scenarios | yes |
| `./gradlew check` | all of the above + lint + coverage verification | yes |
| `k6 run load-tests/<script>.js` | load test against `docker compose up` stack (without a local k6: the `grafana/k6` image, see the phase 2 plan) | yes |

## Rules of thumb

- Test behaviour via public APIs; name tests by behaviour (`` `rejects fix recorded in the future` ``).
- A bug fix starts with a failing test.
- Don't mock what you own — use the in-memory adapter.
- Don't assert on log output except for security-relevant redaction tests.
- Flaky test = bug. Quarantine with an issue link within a day; never retry-until-green in CI.
