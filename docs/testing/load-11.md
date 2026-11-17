# Load check 11: the live fleet map (TC-11-LOAD-01)

TC-11-LOAD-01 asks: with 1 000 simulated drivers and 20 dashboards on the live map, does each dashboard get a
`fleet_delta` every 2 s ± 0.5 s, and does the ingest SLO hold? It has two parts.

- **Small, in CI.** `FleetLoadE2ETest` (suite `e2eTest`, part of `./gradlew check`) runs 50 drivers and 2 dashboards
  against one instance whose fleet tick is 100 ms. It checks only the cadence: p95 of the gap between two deltas within
  100 ms ± 50 ms.
- **Full, manual.** 1 000 drivers and 20 dashboards against two instances and Redis, below. Results are recorded at the
  end of this page.

## What is measured

`simulator fleet` (phase 11d) runs every driver as a coroutine in one JVM, each with its own `DriverClient` and its own
socket. Driver n is the user `sim-driver-NNN`. It loops `routes/hanoi_loop.gpx`, moved up to 6 km so the fleet covers
Hanoi, and sends a batch of 5 fixes every 5 s (1 fix a second). 1 000 drivers make 200 batches and 1 000 fixes a second.

Each dashboard takes an OPERATOR dev token, a ticket from `POST /api/v1/admin/ws-tickets`, and opens
`/ws/v1/admin/fleet` with `fleet_subscribe` over the Hanoi area (105.70, 20.90 to 105.98, 21.12). It records the time
between two `fleet_delta` messages. Drivers and dashboards alternate between the two instances, so a dashboard on one
instance shows drivers who send to the other: the deltas come from the shared `fleet_drivers` table, not from memory.

The run prints:

- the p50, p95 and max gap between deltas over all dashboards, and whether p95 is within 2 s ± 0.5 s (exit 1 if not);
- the ack latency of the fix batches (time from sending a batch to its `ack`), p50, p95 and max;
- batches acked, fixes accepted, fixes rejected by reason, failed connections.

Pass: delta gap p95 within 2 s ± 0.5 s; ack latency p95 under 250 ms and no failed batches (the ingest SLO of phase
2, `docs/plan/phase-02-ingest.md`).

## Procedure

1. Start Docker (OrbStack) with at least 4 CPUs and 6 GB of memory.
2. Start two instances, Postgres and Redis. Use a project name of its own (`-p rts-load`), so the run gets its own
   database volume. `HTTP_RATELIMITPERMINUTE` must be high: 1 000 token requests and 1 000 socket upgrades come from
   one address.

   ```bash
   REDIS_URL=redis://redis:6379 DB_PASSWORD=loadtest-only JWT_SECRET=$(openssl rand -hex 32) APP_ENV=dev HTTP_RATELIMITPERMINUTE=100000 docker compose -p rts-load -f deploy/docker-compose.yml --profile scale up --build --scale app=2 -d
   ```

3. Wait until both instances are up (ports 8080 and 8081). Without the Hanoi OSM extract in the image,
   `GET /health/ready` stays 503 with `graph` DOWN: routing is off, which this run does not use. Its `db` and `redis`
   checks must be UP.
4. Build and run the simulator. It needs Java 21 (`JAVA_HOME=$(/usr/libexec/java_home -v 21)` on a Mac whose default
   is older):

   ```bash
   ./gradlew :tools:simulator:installDist
   ```

   ```bash
   tools/simulator/build/install/simulator/bin/simulator fleet --url http://localhost:8080,http://localhost:8081 --drivers 1000 --dashboards 20 --duration 5m
   ```

5. Record the printed lines below, with the machine, the Docker limits and the commit.
6. Stop and remove the stack and its volume:

   ```bash
   docker compose -p rts-load -f deploy/docker-compose.yml --profile scale down -v
   ```

For dashboard development (T11.9), the same command with `--drivers 500 --dashboards 0 --duration 2h` keeps 500
drivers moving while the dashboard runs against the server.

## Results

### 2026-11-17, branch `feat/p11d-admin-trips` (on main `a16d644`): 1 000 drivers fail, 500 pass the cadence

Everything ran on one Mac mini (Apple M4, 16 GB): the simulator JVM, and in OrbStack (10 CPUs, 8 GB) two app
instances, Postgres and Redis, with the default pool of 10 connections per instance. The Docker image had no OSM
extract, so routing was off.

| Run | Delta gap (p50, p95, max) | Cadence | Ack latency (p50, p95, max) | Batches acked | Failed connections | Outbox behind at the end |
|---|---|---|---|---|---|---|
| 1 000 drivers, 20 dashboards, 5 min | 2.03 s, 3.26 s, 5.50 s | **fail** | 144 ms, 1 483 ms, 7 711 ms | 46 796 of about 60 000 | 2 535 | 38 268 events |
| 500 drivers, 20 dashboards, 3 min | 2.01 s, 2.25 s, 4.28 s | pass | 17 ms, 350 ms, 2 868 ms | 17 187 of about 18 000 | 0 | 2 942 events |

No fix was rejected in either run, and every dashboard stayed connected.

**TC-11-LOAD-01 does not pass at 1 000 drivers on this machine.** Neither run meets the ingest SLO (ack p95 under
250 ms). The cause is the database, not the fleet socket:

- **Connection pool.** Each instance has 10 connections (`db.maxPoolSize`). At 1 000 drivers, requests waited for a
  connection (`total=10, active=10, waiting=53`) and timed out after 5 s. The driver socket then closed with 1011 and
  the phone reconnected: 989 such closes on one instance, 2 535 failed connections in all. The fleet tick waits for a
  connection too, so ticks slipped and the gap p95 rose to 3.3 s.
- **Outbox relay.** One instance holds the relay lock and delivers every event to every handler (fleet projection,
  progress, arrival, battery) in order. 1 000 drivers make about 200 `FixesAccepted` events a second, more than it
  delivered: 38 268 events were waiting when the run ended, and the instance kept one CPU busy for minutes after it.
  The fleet map shows positions only after the relay writes them to `fleet_drivers`, so a backlog delays the markers,
  even when the deltas come on time.

Ideas for a follow-up, not tried here:
- Raise `db.maxPoolSize` and the Postgres `max_connections` together.
- Deliver `FixesAccepted` in batches per relay pass, so that one transaction covers many events.
- Repeat the run on hardware where the load generator does not share CPUs with the server.
