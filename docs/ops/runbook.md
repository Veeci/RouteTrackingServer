# Runbook

What to do when something goes wrong, and how to do the routine operations. Each alert in
`deploy/monitoring/alerts.yml` links to its section here. Commands assume the compose stack in `deploy/`; on the
staging laptop the project name is `rts-staging` and the files are in `/srv/rts` (phase 1.5,
`docs/ops/staging-setup.md`).

## Where to look first

| What | Where |
|---|---|
| Dashboards | Grafana, `http://localhost:3000`, dashboard "Route Tracking overview" (`--profile monitoring`) |
| Firing alerts | Prometheus `http://localhost:9090/alerts`, Alertmanager `http://localhost:9093` |
| Is an instance ready, and why not | `curl -s localhost:8080/health/ready`: `db` and `graph` decide; `redis` is reported only |
| Logs | `docker compose -f deploy/docker-compose.yml logs -f app` (JSON lines with `traceId`) |
| Raw metrics of one instance | `curl -s localhost:8080/metrics` |

## Alerts

### Ack latency is high

`AckLatencyHigh`: the p95 time from a fix batch's arrival to its ack is over 500 ms for 5 minutes. The ack waits for
the database commit, so this is almost always the database.

1. Look at the "Database pool" panel. A full pool means requests wait for a connection: see
   [the database pool is nearly full](#the-database-pool-is-nearly-full).
2. Look for slow queries: `SELECT pid, now() - query_start AS running, query FROM pg_stat_activity WHERE state <> 'idle' ORDER BY running DESC;`
3. A long `DELETE FROM fixes` is the retention job. It purges at most `retention.sessionsPerRun` sessions per run;
   lower it (`RETENTION_SESSIONSPERRUN`) and restart.

### The outbox is lagging

`OutboxLagging`: the oldest undelivered event is over 30 s old. Guests see the driver late, trip summaries and
moments come late, but nothing is lost: the events wait in the `outbox` table.

1. One instance delivers at a time (phase 10a). Compare "Relay deliveries per second" with "Fixes per second": if
   deliveries are flat at a ceiling while the backlog grows, the relay is the bottleneck. Record the ceiling in
   `docs/ops/capacity.md`.
2. Look for one event that keeps failing: `grep "Delivering outbox event" ` in the logs. After 5 failures it is
   skipped and the rest go on.
3. Look for a handler that got slow: the logs of `TripProgressService` and the route planner.

### Server errors

`ServerErrors`: over 1 % of requests answer 5xx for 5 minutes.

1. Find the route on the "Requests per second, by route" panel, then the stack traces: `docker compose logs app | grep '"level":"ERROR"'`.
2. If it started with a deploy, [roll back](#roll-back).

### An instance is down

`InstanceDown`: Prometheus cannot scrape an instance for 1 minute.

1. `docker compose ps`: restarting, exited, or out of memory (`docker inspect <container> --format '{{.State.OOMKilled}}'`).
2. Its drivers and guests reconnect to the other instances by themselves, with jittered backoff. Their batches are
   resent with the same `seq` and stored once.

### The database pool is nearly full

`DatabasePoolNearlyFull`: over 80 % of the connections are in use for 5 minutes.

1. Each instance has two pools: `DB_MAXPOOLSIZE` (20) for requests and jobs, and `DB_RELAYPOOLSIZE` (2) for the outbox
   relay alone. The alert watches only the first (`pool="rts-db"`). Each instance uses both sizes together in
   Postgres connections, so check `max_connections` (default 100) before scaling out: 4 instances use 88.
2. A transaction left open holds a connection: `SELECT pid, now() - xact_start, query FROM pg_stat_activity WHERE xact_start IS NOT NULL ORDER BY 2 DESC;`

### Redis is down

`RedisPublishFailing`, and `redis` DOWN in `/health/ready`. What still works: fixes are stored and acked, trips
change stage, summaries and health are computed. What stops: guests on another instance than the driver see no
live updates, and commands to a driver on another instance wait as PENDING until the driver reconnects.

1. `docker compose ps redis`, then `docker compose restart redis`.
2. The instances reconnect to Redis by themselves and subscribe again. Nothing needs a restart.
3. An instance cannot start while Redis is down when `REDIS_URL` is set: start Redis first.

## Routine operations

### Deploy

Staging deploys every merge to `main` (phase 1.5): pull the image, run migrations, start the new app, wait for
`/health/ready`. Every migration must work with the previous release (expand and contract), because a rollback
runs the old image on the new schema.

### Roll back

On staging, run the "Deploy staging" workflow by hand with `image_sha` set to the last good commit. Locally:
`git checkout <sha>` and `docker compose up --build app`.

### Restore the database

1. Stop the app: `docker compose stop app`.
2. Restore into a new database and check it before switching:
   `createdb -U rts rts_restore && pg_restore -U rts -d rts_restore --no-owner <dump file>`.
3. Compare row counts of `trips`, `trip_summaries` and `tracking_sessions` with the dashboard's last values.
4. Point `DB_URL` at the restored database and start the app. Flyway applies any newer migrations.

### Rotate the JWT secret

There is one secret and no key id, so a new secret invalidates every token at once: every user signs in again,
and the SDK reconnects with a new token. Set a new `JWT_SECRET` (at least 32 characters) in the `.env`, then
restart every instance. Supporting two secrets during a rotation is a later change.

### Drain an instance

`docker compose stop app` on that instance. It reports `/health/ready` DOWN first, so the load balancer stops sending
new connections, then closes its sockets with 1001; the clients reconnect elsewhere with jittered backoff.

### Retention

The retention job deletes the GPS fixes and status samples of sessions last seen over 90 days ago, 20 sessions
every 2 minutes. The "Retention deletes per hour" panel shows it working. Trips older than 90 days keep their
summary and timeline; their track shows `pointsExpired`, and their exports and summary recompute answer 410
TRACK_EXPIRED.
