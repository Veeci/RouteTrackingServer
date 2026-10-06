# Phase 2 — Driver Ingest + ACK

Branch: `feat/p2-ingest` · Depends on: 1 · Unlocks: 3, 9 · Features: I1, I3, I13

## Goal

A driver device connects to `/ws/v1/driver`, performs the `hello`/`welcome` handshake, streams fix
batches, and receives an `ack` for each batch only after it is durably stored. Reconnects resume
without loss or duplication. Every fix passes a validation/filter/enrichment pipeline. A simulator
replays recorded routes so all of this can be exercised without a phone.

## Scope

**In:** protocol v1 driver messages, `tracking` context (session aggregate, fix pipeline, `TrackingService`),
Flyway V2, Exposed repositories, transactional outbox + `FixesAccepted` event, driver socket adapter,
`tools/simulator` driver client, first k6 load test.

**Out:** trips, guests, auth (device identifies itself in `hello`), device status (phase 4/9).

## Design

### Protocol (`protocol` module, as built in T2.1)

```kotlin
@Serializable sealed interface ClientMessage
@Serializable @SerialName("hello")     data class Hello(val protocolVersion: Int, val deviceId: String, val sessionId: String,
                                                        val lastAckedSeq: Long? = null, val sdkVersion: String) : ClientMessage
@Serializable @SerialName("fix_batch") data class FixBatch(val seq: Long, val fixes: List<FixDto>) : ClientMessage

@Serializable sealed interface ServerMessage
@Serializable @SerialName("welcome") data class Welcome(val sessionId: String, val resumeFromSeq: Long,
                                                        val serverTime: Instant, val limits: LimitsDto) : ServerMessage
@Serializable @SerialName("ack")     data class Ack(val seq: Long, val accepted: Int, val rejected: List<RejectionDto>) : ServerMessage
@Serializable @SerialName("error")   data class ErrorMessage(val code: String, val message: String, val correlatesTo: Long? = null) : ServerMessage

data class LimitsDto(val maxFixesPerBatch: Int, val maxFrameBytes: Long, val maxMessagesPerSecond: Int)
data class RejectionDto(val index: Int, val reason: String)
val ProtocolJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
```

`FixDto` fields per [protocol.md](../architecture/protocol.md). `provider` is an enum
`GMS_FUSED | AOSP_GPS | AOSP_NETWORK | AOSP_FUSED | UNKNOWN` (unknown strings decode to `UNKNOWN`).
`provider` and `mock` default to `UNKNOWN` and `false` when absent; `satellites` is optional.
Times are `kotlin.time.Instant`, sent as ISO-8601 strings.

Changes from the original plan:

| Planned | Built | Why |
|---|---|---|
| Messages in a `v1/` package | One package, `veeci.practicing.rts.protocol` | The phase 1 `ErrorMessage` is in the same sealed hierarchy as the new messages, and Kotlin requires one package for a sealed hierarchy. The version is in the URL and in `hello.protocolVersion` |
| `ServerMessage.Error` | `ErrorMessage` (kept from phase 1) | `Error` clashes with `kotlin.Error` |
| `limits { maxFixesPerBatch, maxFrameBytes }` | adds `maxMessagesPerSecond` | The client can pace itself instead of hitting `RATE_LIMITED` |
| `ProtocolJson` without `encodeDefaults` | `encodeDefaults = true` | Default values (`"mock": false`) are written, so a logged frame shows every value the reader uses. Nulls are still left out |
| Rejection reason as an enum | `RejectionDto.reason: String` | The server writes it, so an older SDK must accept reasons that it does not know. Values that the server reads (`provider`) are enums with an `UNKNOWN` fallback |
| AsyncAPI in `app/src/main/resources` | `protocol/src/main/resources/asyncapi/rts-ws-v1.yaml` | The document sits next to the classes that it describes, and one module tests both |
| – | `WsCloseCodes` (4400, 4401) and `WsErrorCodes.UNKNOWN_MESSAGE` | The server and the SDK share the numbers and codes |
| – | `com.networknt:json-schema-validator` 2.0.1 (test only) | Validates golden files against the AsyncAPI schemas. Pinned to the version that swagger-request-validator uses |

### Android SDK alignment (reviewed 2026-10-05)

The fix fields come from the public API of the Android `location_sdk` (`positions: Stream<LocationReading>`).
The upload module (`location_sdk_transport`) does not exist yet; it implements this protocol. Two fields
need a change in the SDK before the transport can fill them:

| Field | Gap in the SDK today | SDK change |
|---|---|---|
| `provider` | GMS fused and AOSP fused fixes both report `"fused"`; the public API does not say which stack produced a fix | Add a source enum to `LocationReading` (`GMS_FUSED`, `AOSP_GPS`, `AOSP_NETWORK`, `AOSP_FUSED`) |
| `satellites` | `GnssReading` is internal; the public API only exposes `sky: Value<SkyView>` | Expose `GnssReading` as a public `Value`; the transport attaches the latest reading to each fix |

The server accepts fixes without these fields (`provider = UNKNOWN`, no `satellites`), so ingest works
before the SDK change. The phase 9 GMS-vs-AOSP report needs both fields. The SDK also provides
`isMock` and three extra accuracy values; the protocol carries them as `mock`, `speedAccuracyMps`,
`bearingAccuracyDeg` and `verticalAccuracyM`.

### Domain (`shared/geo`, `tracking/domain`)

Shared kernel additions (`shared/geo`, as built in T2.2):

- `GeoPoint(lat, lng)`: WGS 84 degrees. The constructor throws `IllegalArgumentException` for a value outside
  [-90, 90] / [-180, 180] or NaN. `GeoPoint.isValid(lat, lng)` lets callers check untrusted input first, so
  that the driver socket mapper can reject one fix with `INVALID_VALUE` instead of throwing.
- `Meters`: a value class with `plus`, `minus` and `compareTo`.
- `Haversine.distance(a, b): Meters`: great-circle distance with the IUGG mean Earth radius (6,371,008.8 m).
  Compared with the WGS 84 ellipsoid it is off by at most about 0.5 %.

```kotlin
@JvmInline value class SessionId(val value: UUID)
@JvmInline value class DeviceId(val value: String)

data class LocationFix(val point: GeoPoint, val accuracyM: Double,
                       val speedMps: Double?, val speedAccuracyMps: Double?,
                       val bearingDeg: Double?, val bearingAccuracyDeg: Double?,
                       val altitudeM: Double?, val verticalAccuracyM: Double?,
                       val recordedAt: Instant, val provider: LocationProvider, val mock: Boolean,
                       val satellites: SatelliteHealth?)   // SatelliteHealth(usedInFix: Int, meanCn0DbHz: Double?)
data class AcceptedFix(val fix: LocationFix, val batchSeq: Long, val index: Int,
                       val distanceFromPrevM: Double, val cumulativeM: Double, val derivedSpeedMps: Double?)
data class Rejection(val index: Int, val reason: RejectionReason)
enum class RejectionReason { POOR_ACCURACY, FUTURE_TIMESTAMP, TOO_OLD, DUPLICATE_TIMESTAMP, IMPLAUSIBLE_JUMP, INVALID_VALUE, MOCK_LOCATION }

class TrackingSession(val id: SessionId, val deviceId: DeviceId, sdkVersion: String, startedAt: Instant) {  // aggregate
    fun resumeFrom(highestStoredSeq: Long?): Long
    fun touch(at: Instant)
}
```

Fix pipeline — a domain service made of small pure stages, composed in order:

```kotlin
fun interface FixStage { fun apply(input: StageInput): StageOutput }
class FixPipeline(private val stages: List<FixStage>) { fun process(batch: List<LocationFix>, context: PipelineContext): PipelineResult }
```

| Stage | Rule (defaults from `PipelineConfig`) | Rejection |
|---|---|---|
| `ValidateStage` | finite numbers; `accuracyM` in (0, 500]; `speedMps` ≥ 0 if present; `bearingDeg` in [0, 360) | `INVALID_VALUE` |
| `MockStage` | `mock == false` (on by default; `PipelineConfig.rejectMock`) | `MOCK_LOCATION` |
| `AccuracyStage` | `accuracyM` ≤ `maxAccuracyM` (50) | `POOR_ACCURACY` |
| `TimeWindowStage` | `recordedAt` ≤ now + `maxClockSkew` (30 s) and ≥ now − `maxAge` (24 h) | `FUTURE_TIMESTAMP` / `TOO_OLD` |
| `DedupStage` | `recordedAt` not equal to an already accepted fix of the session (context) or earlier in the batch | `DUPLICATE_TIMESTAMP` |
| `JumpFilterStage` | implied speed from previous accepted fix ≤ `maxSpeedMps` (70) after subtracting both accuracies from the distance; fixes with `satellites.usedInFix < 4` use half the tolerance; fixes without `satellites` use the full tolerance | `IMPLAUSIBLE_JUMP` |
| `EnrichStage` | compute `distanceFromPrevM`, `cumulativeM`, `derivedSpeedMps` | – |

Fixes inside a batch are sorted by `recordedAt` before the pipeline; rejection indexes refer to the
original positions. `PipelineContext` carries `previous: AcceptedFix?` (latest accepted fix recorded
before this batch's first fix) and `now`.

Domain exceptions (`ErrorCode`s): `UNSUPPORTED_PROTOCOL_VERSION`, `EMPTY_BATCH`, `BATCH_TOO_LARGE`, `INVALID_SEQ`.
Domain event: `FixesAccepted(sessionId, deviceId, fixes: List<AcceptedFix>)` in `tracking.domain.event`.

### Application (`tracking/application`)

Outbound ports (`port/out`):

```kotlin
interface TrackingSessionRepository { suspend fun find(id: SessionId): TrackingSession?; suspend fun save(session: TrackingSession) }
interface FixBatchRepository {
    suspend fun find(sessionId: SessionId, seq: Long): StoredBatch?          // idempotent re-ACK
    suspend fun highestSeq(sessionId: SessionId): Long?
    suspend fun lastAcceptedBefore(sessionId: SessionId, at: Instant): AcceptedFix?
    suspend fun insert(batch: NewBatch): InsertResult                         // Inserted | AlreadyExists(existing)
}
```

`TrackingService` (application service, transaction boundary):

| Method | Behaviour |
|---|---|
| `openSession(cmd: OpenSession): SessionOpened` | Validates protocol version; loads or creates the session aggregate; `resumeFromSeq = session.resumeFrom(highestSeq)`; saves — one transaction |
| `ingest(cmd: IngestBatch): BatchResult` | In **one transaction**: existing batch → return stored result (`duplicate = true`); else load pipeline context, run pipeline, `insert` batch + accepted fixes, write `FixesAccepted` to the outbox. `AlreadyExists` from a concurrent insert → treated as duplicate |

The service returns only after commit, which is what makes "ACK after durable store" true.

Public API for other contexts (`application/api`): `TrackQuery` — `fixesFor(sessionId, from, to)`,
`lastAcceptedFix(sessionId)`; used by `trip` and `telemetry` instead of reading `fixes` directly.

### Persistence (`tracking/adapter/out/persistence`)

Flyway `V2__tracking.sql` (V1 is the platform baseline):

```sql
create table tracking_sessions (id uuid primary key, device_id text not null, sdk_version text not null,
  started_at timestamptz not null, last_seen_at timestamptz not null);
create table fix_batches (session_id uuid not null references tracking_sessions(id), seq bigint not null check (seq > 0),
  received_at timestamptz not null, accepted_count int not null, rejections jsonb not null default '[]',
  primary key (session_id, seq));
create table fixes (id bigserial primary key, session_id uuid not null, seq bigint not null, idx int not null,
  lat double precision not null, lng double precision not null, accuracy_m real not null,
  speed_mps real, bearing_deg real, altitude_m real, recorded_at timestamptz not null, provider text not null,
  speed_accuracy_mps real, bearing_accuracy_deg real, vertical_accuracy_m real, mock boolean not null default false,
  sat_used smallint, sat_mean_cn0_dbhz real, distance_from_prev_m double precision not null, cumulative_m double precision not null,
  foreign key (session_id, seq) references fix_batches(session_id, seq));
create index fixes_session_time on fixes (session_id, recorded_at);
```

`insert` uses `insert into fix_batches … on conflict do nothing`; 0 rows → `AlreadyExists`. The
primary key is the idempotency guarantee under concurrent duplicate sends; fixes use JDBC batch insert.

Outbox: `V2` also creates the platform `outbox(id bigserial, event_type, payload jsonb, created_at, published_at)`
table if phase 1 didn't; the relay (platform/events) publishes to the in-process bus after commit and
marks rows published. Consumers must be idempotent (at-least-once).

### Inbound WebSocket adapter (`tracking/adapter/in/ws/DriverSocket.kt`)

Built on phase 1's `WsSessionRunner`:

1. Handshake: first message must be `hello` within `handshakeTimeout` → else close 4401.
2. `trackingService.openSession` → send `welcome`; MDC `sessionId`, `deviceId`.
3. Each `fix_batch` → `trackingService.ingest` → `ack`; domain exceptions → `error{code}` via `ErrorCatalog`.
4. `finally`: touch session, log close reason and counters.

Sequential per session; sessions run concurrently. Protocol DTO ↔ domain mapping lives in the adapter (`DriverMessageMapper`).

### Simulator (`tools/simulator`)

```
./gradlew :tools:simulator:run --args="driver --url ws://localhost:8080/ws/v1/driver --gpx routes/city_loop.gpx
  --batch-size 10 --speedup 20 --disconnect-every 15 --device dev-sim-1"
```

Reads GPX, keeps an in-memory outbox, sends batches, deletes on ACK, reconnects with
`lastAckedSeq` after forced disconnects. Its `DriverClient` class is a library used by E2E tests and
serves as the reference client behaviour for the SDK team.

### Load test (`load-tests/ingest.js`, k6)

200 virtual drivers, batch of 10 fixes every 5 s, 10 minutes, against the compose stack.
Thresholds: `ack_latency p95 < 250 ms`, error rate `< 0.1%`, no container restarts, heap stable.

## Tasks

- [x] T2.1 Protocol v1 driver messages + `ProtocolJson`; golden files; AsyncAPI document for the driver channel.
- [x] T2.2 `shared/geo`: `GeoPoint`, `Meters`, `Haversine`.
- [ ] T2.3 `tracking/domain`: model, session aggregate, pipeline stages, `FixPipeline`, `PipelineConfig`.
- [ ] T2.4 `TrackingService`, ports, `TrackQuery`; in-memory adapters + object mothers in testFixtures.
- [ ] T2.5 Flyway V2, Exposed repositories, outbox table + relay; port contract tests.
- [ ] T2.6 `DriverSocket` + mapper; `TrackingModule` (Koin); remove echo socket from dev.
- [ ] T2.7 `tools/simulator` driver command; GPX fixtures.
- [ ] T2.8 E2E tests; k6 ingest script; coverage gate on `tracking.domain` / `tracking.application`.
- [ ] T2.9 Extend the staging smoke test (phase 1.5) with a simulator run: replay `straight_2km.gpx`, expect every batch ACKed. **Deferred with phase 1.5.**

## Test plan

### Protocol

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-2-PRO-01 | Unit | Each `ClientMessage`/`ServerMessage` round-trips through `ProtocolJson` unchanged | P0 |
| TC-2-PRO-02 | Unit | JSON with `"type":"fix_batch"` decodes to `ClientMessage.FixBatch` | P0 |
| TC-2-PRO-03 | Unit | Unknown extra fields are ignored (forward compat) | P0 |
| TC-2-PRO-04 | Unit | Unknown `provider` string decodes to `UNKNOWN` | P1 |
| TC-2-PRO-05 | Unit | Golden files: each message type encodes to the exact JSON in `protocol/src/test/resources/golden/<type>.json` (guards accidental contract changes) | P1 |
| TC-2-PRO-06 | Unit | A fix with only the required fields (no `provider`, `mock`, `satellites`) decodes with `provider = UNKNOWN`, `mock = false`, `satellites = null` | P0 |

### Geo

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-2-GEO-01 | Unit | Haversine Ho Chi Minh City Opera House → Ben Thanh Market ≈ known distance ±1% | P0 |
| TC-2-GEO-02 | Property | `distance(a,b) == distance(b,a)` and `distance(a,a) == 0` | P0 |
| TC-2-GEO-03 | Property | Triangle inequality `d(a,c) ≤ d(a,b) + d(b,c) + ε` | P1 |
| TC-2-GEO-04 | Unit | `GeoPoint(91.0, 0.0)` throws `IllegalArgumentException` | P0 |

### Pipeline

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-2-PIP-01 | Unit | Fix with `accuracyM = 80` (max 50) → rejected `POOR_ACCURACY` | P0 |
| TC-2-PIP-02 | Unit | `recordedAt = now + 2 min` → `FUTURE_TIMESTAMP`; `now + 10 s` → accepted (skew allowance) | P0 |
| TC-2-PIP-03 | Unit | `recordedAt = now − 25 h` → `TOO_OLD` | P1 |
| TC-2-PIP-04 | Unit | NaN latitude or negative speed → `INVALID_VALUE` | P0 |
| TC-2-PIP-05 | Unit | Two fixes with identical `recordedAt` in one batch → second rejected `DUPLICATE_TIMESTAMP` | P0 |
| TC-2-PIP-06 | Unit | Fix whose `recordedAt` equals context's previous accepted fix → `DUPLICATE_TIMESTAMP` | P1 |
| TC-2-PIP-07 | Unit | Previous at A, next 2 km away 10 s later (200 m/s) → `IMPLAUSIBLE_JUMP` | P0 |
| TC-2-PIP-08 | Unit | 200 m apart in 2 s (100 m/s raw) with accuracy 40 m each → effective 120 m / 2 s = 60 m/s → accepted (accuracy tolerance applied) | P1 |
| TC-2-PIP-09 | Unit | After a rejected jump, the next fix is compared with the last *accepted* fix, not the rejected one | P0 |
| TC-2-PIP-10 | Unit | Fix with `satellites.usedInFix = 3` and a borderline jump is rejected; same with 12 satellites is accepted | P2 |
| TC-2-PIP-11 | Unit | Batch given out of time order is processed sorted; rejection indexes refer to original positions | P0 |
| TC-2-PIP-12 | Unit | Enrich: 3 accepted fixes 100 m apart → `distanceFromPrevM` 0/100/100 (first uses context previous if any), `cumulativeM` 0/100/200 | P0 |
| TC-2-PIP-13 | Unit | First fix of a session with no previous → `distanceFromPrevM = 0`, never rejected as a jump | P0 |
| TC-2-PIP-14 | Property | For any batch: `accepted.size + rejected.size == input.size` and indexes are a permutation of input indexes | P0 |
| TC-2-PIP-15 | Property | `cumulativeM` is non-decreasing across accepted fixes | P1 |
| TC-2-PIP-16 | Unit | `gps_jump.gpx` fixture: exactly the injected spike points are rejected | P1 |
| TC-2-PIP-17 | Unit | Fix with `mock = true` → rejected `MOCK_LOCATION`; with `rejectMock = false` → accepted | P0 |

### Application service

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-2-SES-01 | Service | New session → upserted, `resumeFromSeq = 1` | P0 |
| TC-2-SES-02 | Service | Session with stored seqs 1..5 → `resumeFromSeq = 6` regardless of client `lastAckedSeq = 3` | P0 |
| TC-2-SES-03 | Service | `protocolVersion = 2` → `DomainException(UNSUPPORTED_PROTOCOL_VERSION)` | P0 |
| TC-2-ACK-01 | Service | Valid batch → stored; result has accepted/rejected counts; `duplicate = false` | P0 |
| TC-2-ACK-02 | Service | Empty batch → `DomainException(EMPTY_BATCH)`, nothing stored | P1 |
| TC-2-ACK-03 | Service | Same `(session, seq)` sent twice → second returns `duplicate = true` with the **original** counts; repository holds one copy | P0 |
| TC-2-ACK-04 | Service | `insert` returns `AlreadyExists` (race) → treated exactly like TC-2-ACK-03 | P0 |
| TC-2-ACK-05 | Service | Batch of 101 fixes (max 100) → `DomainException(BATCH_TOO_LARGE)` | P0 |
| TC-2-ACK-06 | Service | Out-of-order seqs 1, 3, 2 are all stored; `highestSeq = 3` | P1 |
| TC-2-ACK-07 | Service | Repository throws → exception propagates (unexpected), no partial result returned | P1 |

### Data (integration, Docker)

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-2-DB-01 | Integration | Flyway migrates an empty DB to V2 without errors | P0 |
| TC-2-DB-02 | Integration | `FixBatchRepositoryContract` passes against `ExposedFixBatchRepository` | P0 |
| TC-2-DB-03 | Integration | Two coroutines store the same `(session, seq)` concurrently → exactly one `Stored`, one `AlreadyStored`; fixes stored once | P0 |
| TC-2-DB-04 | Integration | `lastAcceptedBefore` returns the latest fix strictly before the given instant | P1 |
| TC-2-DB-05 | Integration | Timestamps round-trip with millisecond precision in UTC | P1 |
| TC-2-DB-06 | Integration | `DatabaseHealthIndicator` DOWN when the container is stopped | P2 |

### Driver socket (component, fakes)

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-2-WS-01 | API | Connect, send `hello` → receive `welcome` with `resumeFromSeq = 1` and limits | P0 |
| TC-2-WS-02 | API | No `hello` within `handshakeTimeout` → closed 4401 (virtual time or 200 ms test timeout) | P0 |
| TC-2-WS-03 | API | First message is `fix_batch` instead of `hello` → closed 4401 | P0 |
| TC-2-WS-04 | API | `hello` with version 2 → closed 4400 | P0 |
| TC-2-WS-05 | API | `fix_batch` after handshake → `ack` with matching `seq` | P0 |
| TC-2-WS-06 | API | Malformed JSON → `error{code=MALFORMED_MESSAGE}` and session stays open (next valid batch is ACKed) | P0 |
| TC-2-WS-07 | API | Unknown `type` → `error{code=UNKNOWN_MESSAGE}`, session open | P1 |
| TC-2-WS-08 | API | Oversized batch → `error{code=BATCH_TOO_LARGE, correlatesTo=seq}`, no ack | P0 |
| TC-2-WS-09 | API | 25 messages in 1 s (limit 20) → at least one `error{code=RATE_LIMITED}` | P2 |
| TC-2-WS-10 | API | `ack` is sent only after `TrackingService.ingest` has committed (gated repository adapter; assert no ack before release) | P0 |
| TC-2-WS-11 | API | Two driver sessions concurrently → each gets only its own acks | P1 |

### E2E (Docker)

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-2-E2E-01 | E2E | Simulator client replays `straight_2km.gpx` in batches of 10 → every batch ACKed; DB fix count = accepted count; last `cumulativeM` ≈ 2 km ±3% | P0 |
| TC-2-E2E-02 | E2E | Disconnect after batch 5 is sent but before its ack is read; reconnect → `welcome.resumeFromSeq` is 5 or 6 depending on whether 5 was stored; after resending, DB has each seq exactly once and no duplicate fixes | P0 |
| TC-2-E2E-03 | E2E | `city_loop_with_tunnel_gap.gpx` with a 90 s gap → accepted; gap does not trigger jump rejection when speed is plausible | P1 |
| TC-2-E2E-04 | E2E | Server restarts mid-stream (stop/start app, same DB) → client resumes, no loss | P1 |

### Contract, load and resilience

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-2-CON-01 | Contract | Every golden message validates against the AsyncAPI JSON schema for its type | P0 |
| TC-2-OUT-01 | Integration | Ingest commits → an outbox row `FixesAccepted` exists in the same transaction; forced rollback → no outbox row | P0 |
| TC-2-OUT-02 | Integration | Relay publishes each outbox row once and marks it published; a relay crash before marking causes a re-publish (consumer must dedupe) | P1 |
| TC-2-LOAD-01 | Load | k6 `ingest.js`: 200 drivers × 10 fixes / 5 s for 10 min → p95 ACK < 250 ms, errors < 0.1% | P1 |
| TC-2-RES-01 | E2E | DB paused for 5 s during ingest → batches in that window are not ACKed; after unpause, client resends and all are stored once | P1 |

## Test implementation notes

- **Gate fake for TC-2-WS-10:** `class GatedFixBatchRepository(delegate): FixBatchRepository` holding a
  `CompletableDeferred<Unit>`; `insert` awaits it; bound via a Koin test override. The test sends a batch, asserts
  `incoming.tryReceive()` is empty after yielding, then completes the gate and awaits the ack.
- **Handshake timeout without real waiting (TC-2-WS-02):** make `handshakeTimeout` configurable and
  set it to 200 ms in `config/test.conf`; that is simpler and more reliable than virtual time
  inside `testApplication`.
- **Testcontainers setup:** the shared `PostgresContainer` from the test strategy (one per JVM, Flyway once, `TRUNCATE` before each test).
- **Concurrency test (TC-2-DB-03):** `coroutineScope { repeat(2) { launch(Dispatchers.IO) { repo.insert(...) } } }`
  and collect results; assert exactly one `Stored`.
- **E2E client:** reuse the simulator's `DriverClient` class as a library (`tools/simulator`
  exposes it; the `e2eTest` suite depends on it). One client implementation, used by tests, the CLI,
  and as a reference for the SDK.
- **GPX parsing** lives in `:tools:simulator` (StAX, no library). Fixture files are small (< 200 points).
- **Property generators:** `Arb.fix(around = GeoPoint, maxJumpM, timeStepS)` producing plausible
  sequences; mutate with injected spikes for negative properties.

## Definition of Done

- Simulator replays a route against the running server; `psql` shows sessions, batches and fixes.
- Kill-and-resume E2E (TC-2-E2E-02) passes reliably 20/20 runs (`--rerun-tasks` loop).
- Protocol doc matches the implementation (golden files pass).
- All P0 cases pass; coverage ≥ 80% on `tracking.domain` + `tracking.application`; k6 thresholds met.
