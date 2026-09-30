# Phase 10 — Production Readiness & Horizontal Scale

Branch: `feat/p10-production` · Depends on: 3, 4, 5 (best after all feature phases)

## Goal

Run two or more instances behind a load balancer with no behaviour change: live updates, presence,
commands and jobs work regardless of which instance holds which socket. Deploy the service to a real
environment with migrations, secrets, monitoring and a rollback path, and prove capacity with load tests.

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
| Rate limiting (Ktor in-memory) | 1 | Limits per instance, not global | Acceptable (limit × instances); document it. Redis-backed limiter only if abuse appears |

The ports introduced in earlier phases make each replacement an adapter swap selected by config
(`live.bus = memory | redis`); in-memory adapters stay for dev and tests.

### WebSockets behind a load balancer

- No sticky sessions required: a socket lives on one instance for its lifetime; cross-instance
  delivery goes through Redis.
- LB idle timeout > ping period (15 s). Graceful shutdown closes sockets with 1001; SDK and simulator
  reconnect with jittered exponential backoff (1 s → 30 s) to avoid reconnect storms after a deploy.
- Rolling deploy: new instance becomes ready (`/health/ready`) before the old one drains.

### Deployment pipeline

```
PR → CI (check + image) → merge → image :sha pushed → deploy job:
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

- [ ] T10.1 Redis in compose + Testcontainers; `platform/redis` client (Lettuce) with health indicator.
- [ ] T10.2 Redis adapters: live bus, presence, progress state, device command routing; config switch.
- [ ] T10.3 Contract tests for each port run against the Redis adapter too.
- [ ] T10.4 Multi-instance E2E harness: two app instances + Postgres + Redis in Testcontainers (or compose).
- [ ] T10.5 Deployment target, pipeline job, migration step, secrets.
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

## Definition of Done

- Two instances behind a load balancer pass all multi-instance E2E cases.
- The service is deployed with an automated pipeline, dashboards, alerts, a runbook, and a completed restore drill.
- Capacity report documents limits and the bottleneck found.
