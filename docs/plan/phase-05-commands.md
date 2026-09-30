# Phase 5 — Remote SDK Commands

Branch: `feat/p5-commands` · Depends on: 3 (4 recommended) · Features: I9

## Goal

The server can tell a connected driver SDK to change behaviour — tighten or relax its tracking
constraint, register geofences, flush its offline queue — with delivery guaranteed across reconnects
and acknowledged by the device. On the SDK side this becomes a `RemoteModule` that feeds a
`TrackingConstraint` into `TrackingPolicyEngine`, following the SDK's 5-step hardware blueprint.

## Scope

**In:** command model, persistence until ack/expiry, delivery on connect and live, `command_ack`,
automatic policies (guest watching → high accuracy; near pickup → register geofence), admin REST
endpoint to send a command manually.

**Out:** the SDK implementation itself (separate repo), fleet-wide broadcast.

## Design

### Model (`devicecontrol/domain`)

```kotlin
data class DeviceCommand(val id: CommandId, val sessionId: SessionId, val kind: CommandKind, val payload: CommandPayload,
                         val createdAt: Instant, val expiresAt: Instant, val status: CommandStatus)
sealed interface CommandPayload {
    data class SetConstraint(val maxAccuracy: TrackingAccuracy, val minIntervalMs: Long, val reason: String) : CommandPayload
    data class RegisterGeofences(val regions: List<GeofenceRegion>) : CommandPayload   // id, center, radiusM
    data object FlushQueue : CommandPayload
}
enum class CommandStatus { PENDING, SENT, APPLIED, REJECTED, EXPIRED }
```

`TrackingAccuracy` mirrors the SDK enum (`HIGH, BALANCED, LOW, PASSIVE`) — the protocol DTO uses the
same names so the SDK maps 1:1 onto its `TrackingConstraint`.

### Delivery

| Step | Behaviour |
|---|---|
| Create | `CommandService.issue(cmd, actor)` persists `PENDING` and writes `CommandIssued` to the outbox in one transaction |
| Live | After commit, `CommandIssuedListener` calls the `DeviceChannel` port; the WS adapter (`ConnectedDevices`, per instance) sends `command` if the session is connected here and the service marks it `SENT` |
| Offline | Stays `PENDING`; on next `welcome`, the driver handler sends all `PENDING`/`SENT` non-expired commands in `createdAt` order |
| Ack | `command_ack{applied|rejected}` → status updated; unknown id → `error{UNKNOWN_COMMAND}` |
| Expiry | `CommandExpiryJob` (scheduler + advisory lock) marks overdue `PENDING`/`SENT` as `EXPIRED`; never delivered after that |
| Supersede | A newer `SetConstraint` for the same session expires older undelivered ones (only the latest constraint matters) |

Multi-instance note: `ConnectedDevices` only knows sockets on its own instance. Phase 10 routes delivery
through Redis pub/sub (every instance receives `CommandIssued`, the one holding the socket sends it).

At-least-once delivery: a `SENT` command is re-sent on reconnect until acked, so the SDK must treat
`commandId` idempotently (document in protocol.md).

### Automatic policies

`CommandPolicyListener` reacts to other contexts' events (`TripStatusChanged`, `GuestSubscriptionChanged`) and calls `CommandService.issue` as the system actor:

| Trigger | Command |
|---|---|
| First guest subscribes to a trip | `SetConstraint(HIGH, 1000 ms, "guest_watching")` |
| Last guest unsubscribes and trip not IN_PROGRESS | `SetConstraint(BALANCED, 5000 ms, "no_viewers")` |
| Trip ACCEPTED | `RegisterGeofences([pickup 150 m])` |
| Trip IN_PROGRESS | `RegisterGeofences([destination 150 m])` |
| Trip terminal | `SetConstraint(PASSIVE, 60000 ms, "trip_ended")` |

Debounce: at most one `SetConstraint` per session per 10 s (guest reconnect loops must not spam the device).

### REST (admin)

`POST /api/v1/admin/sessions/{sessionId}/commands` (role ADMIN) → 202 `{commandId}`;
`GET /api/v1/admin/sessions/{sessionId}/commands` → list with statuses.

### Migration `V6__device_commands.sql`

`device_commands(id uuid pk, session_id, kind, payload jsonb, status, created_at, expires_at, updated_at)` + index `(session_id, status)`.

## Tasks

- [ ] T5.1 Model, payloads, `CommandService` (`issue`, `acknowledge`, `expire`, `pendingFor`).
- [ ] T5.2 `DeviceCommandRepository` port + Exposed impl + contract tests.
- [ ] T5.3 `DeviceChannel` port; `ConnectedDevices` (session → socket outbound channel, per instance) in the tracking WS adapter; listener delivering after commit.
- [ ] T5.4 Protocol `command`, `command_ack`; handler: redeliver after welcome; ack branch.
- [ ] T5.5 `CommandPolicies` + debounce, subscribed to hub updates.
- [ ] T5.6 Admin routes.
- [ ] T5.7 Simulator: print received commands, auto-ack `applied` (flag `--reject-commands` for tests).
- [ ] T5.8 protocol.md: idempotency note and command payload schemas.

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-5-CMD-01 | Service | Send to a connected session → persisted PENDING, delivered, marked SENT | P0 |
| TC-5-CMD-02 | Service | Send to an offline session → stays PENDING; `PendingCommandsFor` returns it | P0 |
| TC-5-CMD-03 | Service | Ack `applied` → APPLIED; second identical ack → no change, no error | P0 |
| TC-5-CMD-04 | Service | Ack for an unknown id → `NotFoundException(COMMAND_NOT_FOUND)` | P1 |
| TC-5-CMD-05 | Service | `ExpireCommands` at `now > expiresAt` → EXPIRED; expired commands are never returned for delivery | P0 |
| TC-5-CMD-06 | Service | New SetConstraint expires older undelivered SetConstraint for the same session; other kinds untouched | P1 |
| TC-5-CMD-07 | Service | Ack for an EXPIRED command → stays EXPIRED (late ack ignored), logged at WARN | P2 |
| TC-5-POL-01 | Service | First guest subscribes → one SetConstraint(HIGH) | P0 |
| TC-5-POL-02 | Service | Guest reconnects 5× within 10 s → still one SetConstraint (debounce) | P0 |
| TC-5-POL-03 | Service | Trip ACCEPTED → RegisterGeofences with pickup, radius 150 | P1 |
| TC-5-POL-04 | Service | Trip COMPLETED → SetConstraint(PASSIVE) | P1 |
| TC-5-DB-01 | Integration | `DeviceCommandRepositoryContract` against Exposed; payload polymorphism round-trips | P0 |
| TC-5-WS-01 | API | Admin sends command while driver connected → driver socket receives `command` with same id | P0 |
| TC-5-WS-02 | API | Command created while driver offline; driver connects → receives it right after `welcome`, before any ack | P0 |
| TC-5-WS-03 | API | Driver disconnects before acking a SENT command; reconnects → receives it again (at-least-once) | P0 |
| TC-5-WS-04 | API | `command_ack` for delivered command → admin `GET` shows APPLIED | P1 |
| TC-5-API-01 | API | Non-admin POST to admin commands → 403 | P0 |
| TC-5-API-02 | API | Invalid payload (`minIntervalMs = -1`) → 400 | P1 |
| TC-5-E2E-01 | E2E | Guest subscribes during simulator run → simulator logs SetConstraint(HIGH) and acks; DB row APPLIED | P1 |

## Test implementation notes

- **Device channel in service tests:** use an `InMemoryDeviceChannel` with a
  `connected: MutableSet<SessionId>` so tests control online/offline without sockets.
- **Ordering assertion (TC-5-WS-02):** read frames in order: first `welcome`, then `command`;
  a helper `receiveTyped<T>()` decodes and fails with the actual type if different.
- **Debounce (TC-5-POL-02):** `FakeClock` advanced manually between subscribe events.

## Definition of Done

- A command survives a driver restart and is applied exactly once from the SDK's point of view (idempotent id).
- protocol.md documents the command contract precisely enough to implement `RemoteModule` in the SDK.
- All P0 cases pass.
