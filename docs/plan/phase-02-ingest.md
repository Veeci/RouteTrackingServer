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

Tracking domain (`tracking/domain`, as built in T2.3):

```kotlin
@JvmInline value class SessionId(val value: UUID)
@JvmInline value class DeviceId(val value: String)          // 1 to 128 characters

data class FixReading(val lat: Double, val lng: Double, val accuracyM: Double, ...)   // as the device sent it, unchecked
data class LocationFix(val point: GeoPoint, val accuracy: Meters,
                       val speedMps: Double?, val speedAccuracyMps: Double?,
                       val bearingDeg: Double?, val bearingAccuracyDeg: Double?,
                       val altitudeM: Double?, val verticalAccuracyM: Double?,
                       val recordedAt: Instant, val provider: LocationProvider, val mock: Boolean,
                       val satellites: SatelliteHealth?)   // SatelliteHealth(usedInFix: Int, meanCn0DbHz: Double?)
data class AcceptedFix(val fix: LocationFix, val index: Int,
                       val distanceFromPrev: Meters, val cumulative: Meters, val derivedSpeedMps: Double?)
data class Rejection(val index: Int, val reason: RejectionReason)
enum class RejectionReason { INVALID_VALUE, MOCK_LOCATION, POOR_ACCURACY, FUTURE_TIMESTAMP, TOO_OLD, DUPLICATE_TIMESTAMP, IMPLAUSIBLE_JUMP }

class TrackingSession(val id: SessionId, val deviceId: DeviceId, sdkVersion: String, val startedAt: Instant) {  // aggregate
    fun reconnect(deviceId: DeviceId, sdkVersion: String, at: Instant)   // another device → SESSION_DEVICE_MISMATCH
    fun touch(at: Instant)                                               // lastSeenAt never moves backwards
    fun resumeFrom(highestStoredSeq: Long?): Long
}
```

Times are `kotlin.time.Instant` (the same type as the protocol; Exposed maps it). Distances are `Meters`.

Fix pipeline: a domain service with three kinds of steps.

```kotlin
class FixPipeline(config: PipelineConfig) { fun process(batch: List<FixReading>, context: PipelineContext): PipelineResult }
internal fun FixReading.validate(): LocationFix?                                     // null → INVALID_VALUE
internal fun interface FixRule { fun check(fix: LocationFix, context: RuleContext): RejectionReason? }
```

| Step | Rule (defaults from `PipelineConfig`) | Rejection |
|---|---|---|
| `validate()` | `lat`/`lng` in range; `accuracyM` finite and > 0; speed, accuracy values ≥ 0; `bearingDeg` in [0, 360); altitude finite; satellite values ≥ 0. NaN and infinity are invalid | `INVALID_VALUE` |
| `MockRule` | `mock == false` (on by default; `PipelineConfig.rejectMock`) | `MOCK_LOCATION` |
| `AccuracyRule` | `accuracy` ≤ `maxAccuracy` (50 m) | `POOR_ACCURACY` |
| `TimeWindowRule` | `recordedAt` ≤ now + `maxClockSkew` (30 s) and ≥ now − `maxAge` (24 h) | `FUTURE_TIMESTAMP` / `TOO_OLD` |
| `DuplicateRule` | `recordedAt` not equal to the previous accepted fix (from this batch or from the context) | `DUPLICATE_TIMESTAMP` |
| `JumpRule` | implied speed from the previous accepted fix ≤ `maxSpeedMps` (70) after subtracting both accuracies from the distance; fixes with `satellites.usedInFix < 4` use half the tolerance; fixes without `satellites` use the full tolerance | `IMPLAUSIBLE_JUMP` |
| `enrich()` | compute `distanceFromPrev`, `cumulative`, `derivedSpeedMps` | – |

`process` sorts the batch by `recordedAt` (a stable sort, so the first of two equal times is kept) and
walks it once. The rules run in the order above, and the first rule that returns a reason rejects the fix.
Only an accepted fix becomes the next fix's `previous`, so after a rejected jump the next fix is compared
with the last accepted fix. Rejection indexes refer to the original positions. `PipelineContext` carries
`now` and `previous: AcceptedFix?`: the latest accepted fix of the session recorded **at or before** the
batch's earliest fix. "At or before" lets `DuplicateRule` catch a resent fix with the same time.

Changes from the original plan (T2.3):

| Planned | Built | Why |
|---|---|---|
| `LocationFix` holds a `GeoPoint`, and a `ValidateStage` checks the numbers | `FixReading` → `validate()` → `LocationFix` | A `LocationFix` with an invalid position cannot exist, so a stage that receives one cannot check it. The type now shows whether a fix was checked |
| One `FixStage` interface (`StageInput` → `StageOutput`) for all stages | `validate()`, `FixRule`, `enrich()` | The three kinds of step do different jobs. One interface for all three would need a vague input and output type |
| Accuracy over 500 m is `INVALID_VALUE` | Any finite accuracy > 0 is valid; over 50 m is `POOR_ACCURACY` | A 1,500 m cell-tower fix is real but imprecise, not broken |
| `AcceptedFix.batchSeq`, `distanceFromPrevM: Double` | No `batchSeq`; distances are `Meters` | The repository adds the seq when it stores a batch |
| `previous` = latest fix strictly before the batch | At or before the batch's earliest fix | See above. TC-2-DB-04 is changed to match |
| `TrackingSession` has `resumeFrom` and `touch` | Adds `reconnect`, which refuses a different device (`TrackingError.SESSION_DEVICE_MISMATCH`, category `CONFLICT`) | Until authentication exists (phase 3), any device could reuse another device's session id |

Known limits: if the previous accepted fix is itself wrong (for example a bad first fix), later honest fixes
look like jumps; a reset after N rejections in a row would fix this. `DuplicateRule` compares only with the
previous accepted fix, not with every stored fix; the batch numbering rules make overlapping batches unlikely.

Domain exceptions (`ErrorCode`s): `UNSUPPORTED_PROTOCOL_VERSION`, `EMPTY_BATCH`, `BATCH_TOO_LARGE`, `INVALID_SEQ`.
Domain event: `FixesAccepted(sessionId, deviceId, fixes: List<AcceptedFix>)` in `tracking.domain.event`.

### Application (`tracking/application`, as built in T2.4)

Outbound ports (`port/out`):

```kotlin
interface TrackingSessionRepository { suspend fun find(id: SessionId): TrackingSession?; suspend fun save(session: TrackingSession) }
interface FixBatchRepository {
    suspend fun find(sessionId: SessionId, seq: Long): StoredBatch?          // answer a resend
    suspend fun highestSeq(sessionId: SessionId): Long?
    suspend fun lastAcceptedAtOrBefore(sessionId: SessionId, at: Instant): AcceptedFix?
    suspend fun insert(batch: NewBatch): InsertResult                         // Inserted | AlreadyExists(existing)
}
interface TrackingEventOutbox { suspend fun add(event: FixesAccepted) }      // writes in the caller's transaction
```

`TrackingService` (application service, transaction boundary). Time comes from the platform's
`java.time.Clock`; tests use `Clock.fixed`.

| Method | Behaviour |
|---|---|
| `openSession(OpenSession): SessionOpened` | One transaction: load the session and `reconnect` it (another device → `SESSION_DEVICE_MISMATCH`), or create it; save; `resumeFromSeq = session.resumeFrom(highestSeq)` |
| `ingest(IngestBatch): BatchResult` | Before any database access: `seq` < 1 → `INVALID_SEQ`, no fixes → `EMPTY_BATCH`, more than `maxFixesPerBatch` → `BATCH_TOO_LARGE`. Then **one transaction**: a stored `(session, seq)` → the stored counts with `duplicate = true`; an unknown session → `SESSION_NOT_FOUND`; else load `previous`, run the pipeline, `insert`, touch and save the session, and add `FixesAccepted` to the outbox if any fix was accepted. `AlreadyExists` from a concurrent insert → answered like a resend |

The service returns only after commit, which is what makes "ACK after durable store" true. The `find`
before the insert is a shortcut for the common resend; the primary key `(session_id, seq)` is what
guarantees one copy when two requests race.

`FixesAccepted(sessionId: UUID, deviceId: String, seq, positions: List<Position>)` is in
`tracking.domain.event`. It uses only plain and shared-kernel types, because other contexts may import
`domain.event` but not tracking's model (TC-0-ARCH-03).

Test support (`testFixtures`, package `veeci.practicing.rts.testing.tracking`): `InMemoryTrackingSessionRepository`
(stores a copy, so an unsaved change is lost like in a table), `InMemoryFixBatchRepository`,
`InMemoryTrackingEventOutbox`, and `DirectTransactionRunner` (no rollback).

Changes from the original plan (T2.4):

| Planned | Built | Why |
|---|---|---|
| `openSession` checks the protocol version (TC-2-SES-03) | The driver socket checks it (T2.6, TC-2-WS-04) | The version is a wire concern. The application layer does not import the `protocol` module |
| `OpenSession` carries `lastAckedSeq` | Not passed | The server's number wins, so the service has no use for it. The socket logs it |
| `TrackQuery` in T2.4 | Deferred to phase 3 | No context reads tracks before `trip` exists |
| `FixesAccepted(..., fixes: List<AcceptedFix>)` | Plain types and `seq` | See above |
| – | `SESSION_NOT_FOUND`; no event when every fix is rejected | A batch before `hello`; no position for other contexts to react to |
| Object mothers in testFixtures | `Readings` stays in the `test` source set | It uses the internal `validate()`. T2.5 moves what the integration tests need |

### Persistence (`tracking/adapter/out/persistence`, as built in T2.5)

The full database design (diagram, keys, indexes, the outbox, column types, size) is in
[database.md](../architecture/database.md). In short:

- `V2__outbox.sql` creates the platform `outbox` table. `V3__tracking.sql` creates `tracking_sessions`,
  `fix_batches` and `fixes`.
- `fixes` has the primary key `(session_id, seq, idx)`, the protocol's identity of a point, and no
  generated id. Coordinates and distances are `double precision`; values measured by the phone are `real`.
- `ExposedFixBatchRepository.insert` runs `INSERT INTO fix_batches … ON CONFLICT DO NOTHING`
  (`insertIgnore`). 0 rows → `AlreadyExists` with the stored copy. The primary key is the idempotency
  guarantee under concurrent duplicate sends. All fixes of a batch go in one `batchInsert`.
- `ExposedTrackingSessionRepository.save` is an `upsert` that never changes `device_id` or `started_at`.
- `ExposedTrackingEventOutbox` writes `tracking.FixesAccepted.v1` with a JSON payload of plain values
  through the platform `OutboxWriter`, in the caller's transaction.
- `TrackingPersistenceContract` (testFixtures) holds 8 checks. `InMemoryTrackingPersistenceTest` runs them
  against the in-memory repositories, and `ExposedTrackingIT` runs them against Postgres. `TrackingSchemaIT`
  checks the constraints with plain SQL.

Changes from the original plan (T2.5):

| Planned | Built | Why |
|---|---|---|
| One `V2__tracking.sql`, which also creates `outbox` | `V2__outbox.sql` (platform) and `V3__tracking.sql` (tracking) | Each table has one owner. Other contexts will write to `outbox` too |
| `fixes.id bigserial` primary key | Primary key `(session_id, seq, idx)` | It is the protocol's identity of a point. One index less on the biggest table (about 20 % of its size) |
| – | `fixes.derived_speed_mps` | `AcceptedFix` has it, so a stored fix reads back unchanged |
| The relay in T2.5 (TC-2-OUT-02) | Deferred to phase 3 | No consumer exists yet. Rows stay unpublished until the relay arrives |
| `FixBatchRepositoryContract` | `TrackingPersistenceContract` for both repositories | A batch needs its session (foreign key), so the two are tested together |

### Inbound WebSocket adapter (`tracking/adapter/inbound/ws/DriverSocket.kt`, as built in T2.6)

Built on phase 1's `WsSessionRunner`, mounted at `/ws/v1/driver` in every profile:

1. A timer closes the socket with 4401 if no valid `hello` arrives within `tracking.handshakeTimeout`
   (10 s; 300 ms in `test.conf`). A `fix_batch` before `hello` also closes with 4401.
2. `hello`: a protocol version other than 1 closes with 4400. An invalid `sessionId` or `deviceId` gets
   `error{INVALID_MESSAGE}` and the socket keeps waiting for a valid `hello`. Otherwise
   `trackingService.openSession` runs, the timer stops, and `welcome` is sent. A session of another device
   gets `error{SESSION_DEVICE_MISMATCH}`. A second `hello` gets `error{INVALID_MESSAGE}`.
3. Each `fix_batch` → `trackingService.ingest` → `ack`. A `DomainException` (`EMPTY_BATCH`, `BATCH_TOO_LARGE`,
   `INVALID_SEQ`) becomes `error{code, correlatesTo = seq}` and no ack. Any other exception closes the socket
   with 1011 through the runner; the client reconnects and resends.
4. `WsSessionRunner` now answers `error{UNKNOWN_MESSAGE}` for a `type` that is not in the sealed message family,
   and `INVALID_MESSAGE` for a known type with a wrong shape.

Messages of one socket are handled one at a time; sockets run concurrently. `DriverMessageMapper` is the only
code that converts protocol types to tracking types. `trackingModule` (Koin) binds the ports to the Postgres
adapters and builds the pipeline from `TrackingConfig` (`config/*.conf`, section `tracking`). In `dev.conf`,
`rejectMock = false`, so that developers can test with a fake-GPS app.

Changes from the original plan (T2.6):

| Planned | Built | Why |
|---|---|---|
| Package `adapter/in/ws` | `adapter/inbound/ws` | `in` is a Kotlin keyword; the package name would need backticks in every import |
| Remove the echo socket from dev | The echo socket stays (not in prod) | Phase 1's WebSocket framework tests use it, and it is a quick connectivity check without the protocol |
| MDC `sessionId`, `deviceId` | One log line per `hello` with both values | The runner sets the MDC when the socket opens, before the ids are known |
| `finally`: touch the session | No extra write on close | Every stored batch already updates `last_seen_at` |
| – | `testApp(overrides = …)` and `Application.module(config, overrides)` | Lets a test replace one Koin definition (the gated repository of TC-2-WS-10) |

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
- [x] T2.3 `tracking/domain`: model, session aggregate, pipeline stages, `FixPipeline`, `PipelineConfig`.
- [x] T2.4 `TrackingService`, ports, `TrackQuery`; in-memory adapters + object mothers in testFixtures.
- [x] T2.5 Flyway V2, Exposed repositories, outbox table + relay; port contract tests. (The relay moved to phase 3.)
- [x] T2.6 `DriverSocket` + mapper; `TrackingModule` (Koin); remove echo socket from dev. (The echo socket stays; see above.)
- [ ] T2.7 `tools/simulator` driver command; GPX fixtures; TC-2-PIP-16 (needs the GPX reader).
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
| TC-2-PIP-04 | Unit | Each reported value out of range or not finite (NaN latitude, negative speed, bearing 360, infinite altitude, negative satellite count, …) → `INVALID_VALUE`; bearing 0, negative altitude and 0 satellites are valid | P0 |
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
| TC-2-PIP-16 | Unit | `gps_jump.gpx` fixture: exactly the injected spike points are rejected (in T2.7) | P1 |
| TC-2-PIP-17 | Unit | Fix with `mock = true` → rejected `MOCK_LOCATION`; with `rejectMock = false` → accepted | P0 |

### Application service

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-2-SES-01 | Service | New session → upserted, `resumeFromSeq = 1` | P0 |
| TC-2-SES-02 | Service | Session with stored seqs 1..5 → `resumeFromSeq = 6` (the client's `lastAckedSeq` is not used) | P0 |
| TC-2-SES-03 | Service | Moved to TC-2-WS-04: the driver socket checks the protocol version | – |
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
| TC-2-DB-01 | Integration | Flyway migrates an empty DB through V3 without errors | P0 |
| TC-2-DB-02 | Integration | `TrackingPersistenceContract` passes against the Exposed repositories (and, as a unit test, against the in-memory ones) | P0 |
| TC-2-DB-03 | Integration | Two coroutines store the same `(session, seq)` concurrently → exactly one `Inserted`, one `AlreadyExists`; fixes stored once | P0 |
| TC-2-DB-04 | Integration | `lastAcceptedAtOrBefore` returns the latest accepted fix at or before the given instant | P1 |
| TC-2-DB-05 | Integration | Timestamps round-trip with millisecond precision in UTC | P1 |
| TC-2-DB-06 | Integration | `DatabaseHealthIndicator` DOWN when the container is stopped (covered by phase 1's TC-1-HLT-02) | P2 |

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
| TC-2-WS-09 | API | More messages per second than the limit (2 in the test) → `error{code=RATE_LIMITED}` | P2 |
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
| TC-2-OUT-02 | Integration | Relay publishes each outbox row once and marks it published; a relay crash before marking causes a re-publish (consumer must dedupe). Moved to phase 3 with the relay | P1 |
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
