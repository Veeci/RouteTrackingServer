# Phase 10 — Production Readiness & Horizontal Scale

Branch: `feat/p10-production` · Depends on: 3, 4, 5 (best after all feature phases)

## Goal

Run two or more instances behind a load balancer with no behaviour change: live updates, presence,
commands and jobs work regardless of which instance holds which socket. Promote the staging
pipeline from phase 1.5 to a production environment with rolling deploys, monitoring, alerts and
backups, and prove capacity with load tests.

## Scope

**In:** Redis (pub/sub + TTL keys), Redis adapters for `LiveUpdateBus`, `PresenceStore`,
`ProgressStateStore` and device-command routing; sticky-free WebSocket scaling; Flyway as a
separate migration step; deployment to one PaaS (Fly.io or Render) or a single VM with compose;
dashboards and alerts; backup/restore drill; data-retention job; capacity test.

**Out:** Kubernetes, multi-region, Kafka.

## Design

### What is instance-local today, and its replacement

| State | Phase introduced | Problem with 2 instances | Replacement |
|---|---|---|---|
| `LiveUpdateBus` (SharedFlow per trip) | 3 | Guest on instance B misses positions ingested on A | `RedisLiveUpdateBus`: publish to channel `trip:{id}`; each instance subscribes only for trips with local guests |
| `PresenceStore` | 4 | Stale detection sees only local drivers | Redis key `presence:{sessionId}` with `SET ... PX` on each frame; stale = key missing |
| `ProgressStateStore` / matcher state | 3, 7 | Driver reconnecting to another instance loses EWMA/cursor | Redis hash per trip with TTL; loss is tolerable (re-initialises) |
| `ConnectedDevices` | 5 | Command issued on A, driver socket on B | `CommandIssued` fanned out on channel `device:{sessionId}`; instance holding the socket delivers |
| Scheduled jobs | 4, 5 | Duplicate runs | Already solved: Postgres advisory locks |
| `FleetPositionCache` + fleet subscribers | 11 | Dashboard on B misses drivers ingested on A | Redis hash of latest markers + pub/sub of changes; each instance runs ticks for its own subscribers |
| Rate limiting (Ktor in-memory) | 1 | Limits per instance, not global | Acceptable (limit × instances); document it. Redis-backed limiter only if abuse appears |

The ports introduced in earlier phases make each replacement an adapter swap selected by config
(`live.bus = memory | redis`); in-memory adapters stay for dev and tests.

### Corrections to this plan (approved 2026-11-17)

The table above was written before phases 4 to 9. Reading the code changed it:

- **The real cause is the outbox relay.** It locks events with `FOR UPDATE SKIP LOCKED`, so with two instances
  each event's handlers run on whichever instance locked it, not where the socket is. Two events of one trip can
  also run at the same moment on two instances, out of order. Fix: only one instance delivers at a time
  (`pg_try_advisory_xact_lock`, key 10_001), and it always takes the oldest event.
- **Presence needs nothing.** Stale detection reads `tracking_sessions.last_seen_at` in Postgres since phase 4, and
  every job holds an advisory lock. The `PresenceStore` row is dropped.
- **Progress state goes to Postgres, not Redis.** Progress runs inside the relay's transaction, so a row written in
  that transaction (V17) rolls back with the event. Redis could not.
- **Commands are routed per driver** on `driver:{driverId}`, not per session: commands are addressed to drivers
  (V10). The same channel carries `route_updated`, because both go through `DriverLink`.
- **Guest watching was instance-local too.** `GuestSocket` counted guests on its own instance before telling device
  control that the first guest arrived or the last left. It now asks the bus whether anyone watches anywhere.
- **Redis is not critical for readiness.** TC-10-RED-02 said Redis down makes `/health/ready` DOWN. That would take
  every instance out of the load balancer during a Redis outage, and stop ingest, which does not need Redis.
  Redis is reported in readiness, but only the database and the road graph decide it.
- **Slices:** 10a is T10.1 to T10.3 plus the relay lock and V17. 10b is T10.4 and reconnect backoff. 10c is the
  retention job, metrics, alerts and the runbook. 10d is the production environment, deploys, the restore drill and
  the capacity test, after the staging laptop (phase 1.5) is running.

### WebSockets behind a load balancer

- No sticky sessions required: a socket lives on one instance for its lifetime; cross-instance
  delivery goes through Redis.
- LB idle timeout > ping period (15 s). Graceful shutdown closes sockets with 1001; SDK and simulator
  reconnect with jittered exponential backoff (1 s → 30 s) to avoid reconnect storms after a deploy.
- Rolling deploy: new instance becomes ready (`/health/ready`) before the old one drains.

### Deployment pipeline (extends phase 1.5)

Phase 1.5 already deploys `main` to staging with migrations, health checks, smoke test and rollback.
This phase adds a `prod` environment promoted from staging:

```
PR → CI → merge → staging deploy + smoke (phase 1.5) → manual approval (GitHub environment protection) → prod deploy job:
   1. run migrations: container `flyway migrate` against prod DB (expand/contract migrations only)
   2. rolling update app instances to :sha
   3. smoke test: /health/ready + one synthetic trip via the simulator against prod-like env
   4. on failure: redeploy previous :sha (migrations are backward compatible by rule)
```

Migration rule (expand/contract): never drop or rename a column in the same release that stops using
it; release N adds, release N+1 migrates code, release N+2 removes.

Secrets: platform secret store → env vars (`DB_PASSWORD`, `JWT_SECRET`, `REDIS_URL`). Nothing secret in the image or repo.

### Operations

- Dashboards (Grafana or the PaaS's metrics): RED per route, active sockets, ACK latency p95,
  fixes/s, outbox lag (oldest unpublished row age), DB pool usage, JVM heap/GC.
- Alerts: ACK p95 > 500 ms for 5 min; outbox lag > 30 s; 5xx rate > 1%; readiness failing; DB connections > 80%.
- Backups: daily logical dump + point-in-time recovery if the provider supports it; a **restore drill** is part of this phase's DoD.
- Data retention: `RetentionJob` deletes raw fixes older than 90 days (summaries kept) — location data minimisation.
- Runbook `docs/ops/runbook.md`: deploy, rollback, restore, rotate JWT secret, drain an instance.

## Tasks

- [x] T10.1 Redis in compose + Testcontainers; `platform/redis` client (Lettuce) with health indicator.
- [x] T10.2 Redis adapters: live bus, presence, progress state, device command routing; config switch.
- [x] T10.3 Contract tests for each port run against the Redis adapter too.
- [x] T10.4 Multi-instance E2E harness: two app instances + Postgres + Redis in Testcontainers (or compose).
- [ ] T10.5 `prod` environment: app, managed Postgres with backups, secrets, promotion job with approval, rolling strategy.
- [ ] T10.6 Dashboards, alerts, runbook; retention job.
- [ ] T10.7 Capacity test and report (`docs/ops/capacity.md`).

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-10-RED-01 | Integration | `LiveUpdateBusContract`, `PresenceStoreContract`, `ProgressStateStoreContract` pass against Redis adapters | P0 |
| TC-10-RED-02 | Integration | Redis unavailable → `/health/ready` DOWN; ingest still ACKs (live updates degrade, durable data unaffected) | P0 |
| TC-10-MI-01 | E2E | Driver on instance A, guest on instance B → guest receives positions, progress and events | P0 |
| TC-10-MI-02 | E2E | Command issued via instance A's admin API while the driver socket is on B → delivered once | P0 |
| TC-10-MI-03 | E2E | Driver stops sending; stale detected exactly once across two instances | P0 |
| TC-10-MI-04 | E2E | Kill instance A during a trip → driver and guest reconnect to B, resume without data loss (seq resume) and without duplicate events (dedupe by event id) | P0 |
| TC-10-DEP-01 | Manual | Rolling deploy under simulator load → zero failed ACKs beyond reconnect window; no 5xx on REST | P0 |
| TC-10-DEP-02 | Manual | Rollback to previous image after a migration-carrying release works (expand/contract respected) | P1 |
| TC-10-OPS-01 | Manual | Restore drill: restore last backup into a fresh DB, app starts, trip history intact | P0 |
| TC-10-OPS-02 | Integration | `RetentionJob` deletes fixes older than 90 days, keeps summaries and trips | P1 |
| TC-10-LOAD-01 | Load | 2 instances, 1 000 drivers + 1 000 guests, 30 min soak → ACK p95 < 250 ms, guest update delay p95 < 1 s, error rate < 0.1%, heap and socket count stable | P0 |
| TC-10-LOAD-02 | Load | Reconnect storm: all 1 000 drivers disconnect at once → all resumed within 60 s, no instance OOM | P1 |

## Test implementation notes

- **Multi-instance E2E:** start two `embeddedServer` instances in the same JVM on random ports sharing
  Testcontainers Postgres and Redis, each with its own Koin application. Point the simulator's driver
  client at one port and the guest client at the other.
- **Guest update delay** is measured by the simulator: fix `recordedAt` vs guest receive time (same machine clock).
- Load tests run against the deployed staging environment or `docker compose --scale app=2` behind the compose `nginx` LB.

## As built (10a)

- `OutboxRelay` takes `pg_try_advisory_xact_lock(10_001)` at the start of each event's transaction and skips the
  round when another instance holds it. The lock ends with the transaction, so a crashed instance never keeps it,
  and the next event may go to any instance. TC-10-REL-01: two relays deliver 100 events in order, never two at once.
- `ExposedProgressStateStore` (V17 `trip_progress_state`) replaces the in-memory store. The followed route is
  stored as kind and version only. Each instance keeps up to 1 000 decoded lines in an LRU cache, filled from
  `trip_routes` on a miss. A route version never changes, so a cached line is never out of date. The port's
  methods became `suspend`.
- `RedisConnections` (Lettuce 7.9) holds one command connection and one pub/sub connection. Redis is on when
  `REDIS_URL` is set and not blank. Without it, the app runs alone exactly as before, with the in-memory adapters.
- `RedisLiveUpdateBus` wraps an `InMemoryLiveUpdateBus`, which still fans out to the local sockets.
  - A Lua script numbers each update (`INCR trip:seq:{id}`), keeps it as the trip's latest (`trip:last:{id}`) and
    publishes it on `trip:{id}`, atomically. Both keys expire after `redis.lastUpdateTtl`, 1 hour by default.
  - An instance subscribes to `trip:{id}` only while it has a guest of the trip, waits for the confirmation, then
    reads `trip:last:{id}`. The number drops a copy that is older than one already seen.
  - `isWatched` is local guests or `PUBSUB NUMSUB trip:{id}`, which counts the instances subscribed. An instance
    that dies drops its subscriptions with its connection, so the count never stays too high.
  - A publish never waits and never throws: it gives up after `redis.publishTimeout`, 500 ms by default.
- `RedisDriverLink` wraps `ConnectedDrivers`. It sends locally first. Otherwise it publishes on `driver:{id}`, and
  each instance subscribes to that channel while it holds a socket of the driver, so `PUBLISH` returns how many
  instances got it. Zero, or Redis down, answers false, and the command waits as PENDING for the next `welcome`.
- Starting an instance with `REDIS_URL` set needs Redis to answer, because Lettuce connects at startup. A running
  instance survives a Redis outage and reconnects by itself.
- `deploy/docker-compose.yml` has a `redis` service in the `scale` profile and a port range for `--scale app=2`.

## As built (10b)

- `twoInstances { }` in testFixtures starts two Netty servers in one JVM, each with its own Koin graph, on one
  Redis and one fresh database. The fresh database matters: with the shared one, these instances' relays would
  deliver the other tests' outbox events.
- `MultiInstanceE2ETest` (e2eTest, about 11 s) drives them with the simulator's clients and checks the database:
  - TC-10-MI-01: driver on A, guest on B, positions, progress and stage events reach the guest.
  - TC-10-MI-02: a command issued through A's admin API reaches the raw driver socket on B once, and is APPLIED.
  - TC-10-MI-03: with a 2 s stale time and both instances checking every 200 ms, one DRIVER_STALE moment is stored.
  - TC-10-MI-04: A stops after 5 batches. The guest reconnects to B by itself (`watchUntilEnded`), the driver resends
    to B, every seq is stored, no moment is stored twice, and the guest's watch ends with 1000 on COMPLETED.
- "Kill A" stops it with no grace period. A real SIGKILL cannot happen inside one JVM, so the manual deploy test in
  10d covers it.
- The simulator's `Backoff` uses full jitter, 1 s doubling up to 30 s, in `DriverClient.sendAll` and the new
  `GuestClient.watchUntilEnded`. `DriverClient.url` is now a `var`, so a test can move the client to the other
  instance, as a load balancer would.
- A guest who reconnects gets the latest update again. That is the state, not a new event: `protocol.md` now says
  clients treat `trip_event` as idempotent by `(event, at)`.

## Definition of Done

- Two instances behind a load balancer pass all multi-instance E2E cases.
- The service is deployed with an automated pipeline, dashboards, alerts, a runbook, and a completed restore drill.
- Capacity report documents limits and the bottleneck found.
