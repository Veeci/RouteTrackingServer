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

## As built: slice 5a (commands, storage, admin API, expiry)

Phase 5 is built in three slices: 5a commands, storage, admin API and expiry; 5b `command` and `command_ack` on
the driver socket with live delivery and resending after `welcome`; 5c automatic policies. 5a differs from the
design above in these ways:

| Plan | As built | Why |
|---|---|---|
| A command targets a `sessionId` | It targets the driver (`UserId`, column `driver_id`); the admin path is `/api/v1/admin/drivers/{driverId}/commands` | The SDK starts a new session each time tracking starts; a command for an old session would never be delivered |
| `V6__device_commands.sql` | `V10__device_commands.sql`, with an index for a driver's commands and a partial index of open commands by expiry | V6 to V9 were taken |
| Policies issue as a system actor | `CommandService.issueAsServer(driver, payload)` takes no actor; `issue(..., actor)` requires ADMIN (COMMAND_FORBIDDEN otherwise) | Same as auto-arrive: no fake user that a token could name |
| Expiry time not set | `commands.expireAfter` = 10 min, checked every `commands.expiryCheckEvery` = 30 s by `CommandExpiryJob` (lock key 5001) | A constraint older than that belongs to a trip that moved on |
| Supersede: older undelivered SetConstraints expire | Only PENDING ones (never sent); a SENT one stays and may still be applied | The device may already be applying it; the newer one follows anyway |

Notes:
- `DeviceCommand` is the aggregate: PENDING, SENT, then APPLIED, REJECTED or EXPIRED. A repeated or late ack
  changes nothing and is no error (`acknowledge` returns false and the service logs it at WARN).
- `CommandIssued` (`CommandIssuedV1`, `devicecontrol.CommandIssued.v1`) goes to the outbox with every new command.
  Nothing handles it yet; 5b delivers it to a connected driver.
- Command ids are UUID version 7 (time-ordered), like trip ids.
- The admin body is one flat shape for every kind (`kind` says which fields apply), validated field by field.

Found on the way: the request body class kept its constants in a `private companion object`. `@Serializable`
puts `serializer()` in the companion, so Ktor could not find it, and every request failed as MALFORMED_REQUEST.
The constants are now top-level.

Tests: TC-5-CMD-02 to 07 (service), TC-5-DB-01 (contract against memory and Postgres), TC-5-API-01 and 02, plus
admin round trip, forbidden list, bad driver id and 401. Check: 260 unit, 194 integration and 9 end-to-end tests pass.

## As built: slice 5b (delivery over the driver socket)

| Plan | As built | Why |
|---|---|---|
| `DeviceChannel` port in device control, `ConnectedDevices` in tracking's socket | `DriverLink` in `tracking.application.api` (send, onConnected, onCommandAck), implemented by `ConnectedDrivers` in tracking's socket adapter. Device control registers with it | One direction only: device control depends on tracking, tracking never imports device control |
| Unknown id gets `error{UNKNOWN_COMMAND}` | `error{COMMAND_NOT_FOUND}` (the service's code); a bad id or result gets `INVALID_MESSAGE` | One code for "no such command" in REST and on the socket |

How it works:
- Protocol: server `command { commandId, kind, maxAccuracy?, minIntervalMs?, reason?, regions? }`, client
  `command_ack { commandId, result: applied|rejected, reason? }`. No reply to an ack.
- Live: `CommandIssuedListener` (outbox) calls `CommandDelivery.deliver`, which sends through `DriverLink` if the
  driver has a socket on this instance and then marks the command SENT. It runs after the command committed.
- After `welcome`: the driver socket registers itself with `ConnectedDrivers`, whose connect listener sends every
  open, unexpired command, oldest first, before the socket reads its next message.
- At least once: a SENT command without an ack is sent again after every reconnect. The device handles a
  `commandId` once and acks it again.
- Ack: `ConnectedDrivers` calls `CommandService.acknowledge`; COMMAND_NOT_FOUND comes back as an error frame.
- Simulator: `DriverClient` answers every `command` with `command_ack` (`BatchHooks.answer`); `simulator driver`
  prints each command, and `--reject-commands true` answers rejected.

Tests: TC-5-WS-01 to 04 and TC-5-CMD-01 on Netty (`CommandDeliveryIT`), plus a bad ack result; golden files for
`command`, `command` with geofences and `command_ack`. Check: 269 unit, 199 integration and 9 end-to-end tests pass.

## As built: slice 5c (automatic policies)

| Plan | As built | Why |
|---|---|---|
| `GuestSubscriptionChanged` event | The guest socket calls device control's `GuestWatching` API (`firstGuestWatching`, `lastGuestLeft`) in process, when the trip's watcher count on this instance goes from 0 to 1 or to 0 | Watchers live in memory per instance; a lost signal only leaves a tracking setting as it was, so it needs no outbox |
| Geofences need the trip's pickup and destination | `TripStatusChanged` (and `TripStatusChangedV1`) carry `pickup` and `destination`, added as optional fields | Device control may not read the trip context's tables or ask with an actor |
| Debounce: at most one SetConstraint per session per 10 s | A constraint equal to the driver's latest one is never sent again; "no_viewers" is skipped when any constraint went out less than 10 s ago. "guest_watching" and "trip_ended" always go through | A plain 10 s window would drop the raise when a guest returns, or the passive setting at the end of a trip |

`CommandPolicies` issues the commands with `issueAsServer` (no actor). `TripPolicyListener` reacts to
`TripStatusChanged` through the outbox: ACCEPTED gives a 150 m geofence at the pickup, IN_PROGRESS one at the
destination, COMPLETED or CANCELLED `SetConstraint(PASSIVE, 60000 ms, trip_ended)`. A policy that fails is
logged; the guest's socket goes on.

Known limit: a guest who subscribes before a driver accepted the trip does not raise the driver's accuracy
(there is no driver yet); the ACCEPTED geofence still goes out.

Test changes: driver helpers in `GuestSocketIT` skip `command` frames, which now arrive after `welcome` once a
trip is accepted. The device-status helper waits for a reply (an ack for an unknown command) before closing, so
the server has read the status.

Tests: TC-5-POL-01 to 04 plus the debounce cases (`CommandPoliciesTest`), TC-5-E2E-01 as a socket test
(`CommandPoliciesIT`: accept gives a pickup geofence, a watching guest a high accuracy). Check: 277 unit, 200
integration and 9 end-to-end tests pass.

## Tasks

- [x] T5.1 Model, payloads, `CommandService` (`issue`, `acknowledge`, `expire`, `pendingFor`).
- [x] T5.2 `DeviceCommandRepository` port + Exposed impl + contract tests.
- [x] T5.3 `DeviceChannel` port; `ConnectedDevices` (session → socket outbound channel, per instance) in the tracking WS adapter; listener delivering after commit.
- [x] T5.4 Protocol `command`, `command_ack`; handler: redeliver after welcome; ack branch.
- [x] T5.5 `CommandPolicies` + debounce, subscribed to hub updates.
- [x] T5.6 Admin routes.
- [x] T5.7 Simulator: print received commands, auto-ack `applied` (flag `--reject-commands` for tests).
- [x] T5.8 protocol.md: idempotency note and command payload schemas.

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
