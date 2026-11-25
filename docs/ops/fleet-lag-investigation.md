# Fleet read model lag after a large simulator run

Investigation of 2026-11-20. Slice 12d (PR #62) saw a new simulator run show no online driver for minutes after a
5 000-driver run, and a 50-driver E2E run after a server restart meet the same lag once. This page records what
happens, the measurements, the cause and the fix options. No server code changed. The user picks the fix.

## The answer

The suspicion was right: the fleet read model was catching up on the old run's outbox events. Three facts together
hide every driver.

1. The outbox relay is the only writer of `fleet_drivers`, and it is one serial loop. It handles one event after
   another in one transaction per 100 events, with about 4 database round trips per position event. Its speed is set
   by round-trip latency, not by query cost. It delivered 2 430 events a second when nothing else ran, and about
   500 a second when the machine was overloaded.
2. When drivers write events faster than the relay delivers them, the backlog grows without limit. Delivery is first
   in, first out by outbox id, so a new run's events wait behind the whole old backlog. The outbox is a table, so a
   restart keeps the backlog.
3. "Online" compares the phone's fix time with the server's clock: `last_seen_at` within 60 s of now. Once the relay
   is more than 60 s behind, every position it writes is already too old. The list and the map then show nobody
   online, although rows keep changing.

In slice 12d the trigger was load, not a bug in the relay. The 5 000-driver run was still sending when the
5 100-driver run started, so the server had about 10 100 driver sockets. On this Mac that load also broke the ingest
SLO. The relay fell to about 300 events a second while about 1 200 arrived, and reached 230 s behind. 5 000 drivers
alone did not build a backlog, in 12d or in the reproduction.

Ruled out: a slow query (the relay's statements take 0.02 to 3 ms inside Postgres), a failing event (no "Delivering
outbox event" warning in any run), the relay's own pool (1 of 2 connections in use), and GC (654 young pauses in
13 minutes, 5.2 s in total, at most 92 ms).

## What happened in slice 12d

The dev database (`route-tracking-db-1`) still holds every outbox row of those runs. `created_at` is when ingest
wrote the event and `published_at` is when the relay delivered it, so the history can be read back. Times are local
(UTC+7).

| Minute | Sessions sending | Events written | Events delivered | Relay batch period, p50 | Average lag of that minute's events |
|---|---|---|---|---|---|
| 16:15 to 16:17 | 5 050 | 57 900 to 60 600 | 57 000 to 59 000 | 66 to 70 ms | 1 to 5 s |
| 16:18 | 9 772 | 64 000 | 18 900 | 271 ms | 74 s |
| 16:19 to 16:22 | 10 127 to 10 140 | 65 600 to 76 300 | 16 100 to 19 400 | 258 to 346 ms | 186 to 232 s |
| 16:23 | 10 145, then 0 | 47 500 | 41 800 | 51 ms | 170 s |
| 16:24 | 100 | 900 | 121 200 | 39 ms | 104 s |
| 16:25 | 100 | 1 200 | 107 100 | 40 ms | 50 s |
| 16:26 | 100 | 1 200 | 46 000 | 43 ms | 3.5 s |

- `tracking_sessions` shows 5 000 sessions started at 16:14 and 5 100 at 16:18. From 16:18 both sets sent, so the
  first run had not stopped.
- Relay batches were full (98 to 100 events on average) from 16:15 to 16:25. So the loop never waited between
  batches: from 16:18 each batch simply took 4 to 5 times longer.
- At 16:23 every session stopped at once, most likely the server restart. The relay then drained about 316 000
  events at about 2 000 a second.
- The 50-driver E2E run started at 16:24. Its events waited 104 s, then 50 s. Its drivers came online between 16:25
  and 16:26, one to two minutes after the start. That is the "lag after a restart".
- The 50 sessions from 16:07 are a 50-driver simulator that ran through everything, and a second one started at
  16:24. Both use `--duration 60m` and were still running at 16:30, against port 8080 where no server listened any
  more.

## Reproduction on an isolated server

Setup: commit `9bf77e9` (main `f590a0a` differs only in the driver detail endpoint), `./gradlew :app:installDist`, Java 21, `APP_ENV=dev`,
`HTTP_RATELIMITPERMINUTE=1000000`, port 18080, and a fresh Postgres 16 container of its own on port 55432 with
`pg_stat_statements`. The simulator ran on the same Mac (Apple M4, 10 cores, 16 GB), Postgres in OrbStack (10 CPUs,
8 GB). `tools/fleet-lag/sample.sh` took one sample every 10 s and `tools/fleet-lag/wait-online.sh` timed the driver
list (see [how to repeat it](#how-to-repeat-it)).

### Baseline: 50 drivers on a fresh server

The first driver was online after 6 s and all 50 after 10 s. Tokens, sockets and the random start within one 5 s
batch period make up that time.

### 5 000 drivers alone keep up

`fleet --drivers 5000 --dashboards 0 --duration 4m`. 5 000 drivers send one batch every 5 s, so ingest writes about
1 000 `FixesAccepted` events a second.

| Measure | Value |
|---|---|
| Events written, delivered | about 1 000 and about 1 000 a second, every sample after the start |
| Backlog | 0 to 1 000 events, 0 to 1 s behind (5 153 events and 5 s for one sample while 5 000 sockets connected) |
| Relay batch | full (97.6 events on average), one every 56 ms at p50 and 269 ms at p95, so a ceiling of about 1 800 a second |
| Main pool `rts-db` | 20 of 20 active in some samples |
| Ack latency | p50 9.0 ms, p95 221.5 ms, 1 166 failed connections while connecting |

The 50-driver run right after this one proved nothing about lag. There was no backlog to wait behind, and the first
run's `sim-driver-001` to `sim-driver-050` rows stay online for 60 s after their last fix anyway.

### The 12d overlap reproduces the lag

Run A: 5 000 drivers and 1 dashboard (the open map) for 7 minutes from 16:42:03. Run B: 5 100 drivers from 16:44:03
for 5 minutes. Both ended at 16:49:04, and run C (50 drivers) started at once.

| Time | Events written a second | Events delivered a second | Waiting | Behind | Online in `fleet_drivers` |
|---|---|---|---|---|---|
| 16:43:54 (A alone) | 1 000 | 1 000 | 0 | 0 s | 5 000 |
| 16:44:25 (B joined) | 2 058 | 580 | 18 945 | 9 s | 5 000 |
| 16:45:36 | 1 761 | 580 | 86 626 | 61 s | 0 |
| 16:47:01 | 1 738 | 290 | 121 603 | 122 s | 0 |
| 16:49:04 (A and B end) | 1 499 | 830 | 209 412 | 202 s | 0 |
| 16:49:25 (C alone) | 10 | 2 650 | 165 301 | 161 s | 0 |
| 16:50:27 | 10 | 2 860 | 10 817 | 90 s | 0 |
| 16:50:37 | 10 | 1 111 | 4 | 0 s | 50 |

- The online count fell from 5 100 to 0 between 16:45:24 and 16:45:36, as the relay passed 60 s behind, while
  10 100 drivers were sending.
- Run C: the driver list showed 0 online for 86 s, then all 50 at once. On a fresh server it took 10 s.
- The drain ran at 2 430 events a second (a full batch every 40 ms at p50). 209 412 events divided by 2 430 a second
  is 86 s: the wait is the backlog divided by the drain rate.
- Ingest broke too. Run A: ack p95 5.1 s and 27 586 failed connections. Run B: ack p95 6.2 s and 33 809 failed
  connections. The dashboard's gap between deltas reached 6.2 s at p95.

## Where the relay's time goes

The relay's statements are cheap inside Postgres. From `pg_stat_statements` during the overlap:

| Statement | Mean | Calls per event |
|---|---|---|
| `SELECT ... FROM fleet_drivers ... FOR UPDATE` (`FleetProjection.kt:51`) | 0.017 ms | 1 |
| `INSERT INTO fleet_drivers ... ON CONFLICT` (`FleetProjection.kt:52`) | 0.052 ms | 1 when the position is newer |
| `SELECT ... FROM trips` (active trip, by `FixesAcceptedListener` and `ArrivalListener`) | 0.002 ms | 2 |
| `SELECT ... FROM outbox ... FOR UPDATE SKIP LOCKED` | 1.7 ms | once per batch |
| `UPDATE outbox SET published_at` | 2.8 ms (max 378 ms) | once per batch |

That is about 10 ms of database time for a batch of 100, yet a batch took 221 ms at p50 and 676 ms at p95 during
the overlap, against 40 ms when idle. So about 95 % of the batch time is spent between statements: the round trip
from the JVM to Postgres and back, about 400 times per batch. Run 1 counts confirm 4 round trips per position event:
99 166 events, 99 166 fleet reads, 99 116 fleet upserts and 194 166 trip reads.

Three things made each round trip slower under the overlap load.

- CPU. The load average was 11 to 14 on 10 cores. Postgres used 150 to 255 % CPU, the server 110 to 350 %, the
  simulators 24 to 84 %. The relay thread and its Postgres backend waited for a core like every other thread.
- WAL. In two of three samples, 21 backends sat in `COMMIT` waiting on `LWLock:WALWrite`, and one on `IO:WALSync`.
  Since the container started, Postgres ran 6 checkpoints on WAL size and 1 on time (`max_wal_size` is the
  default 1 GB). The relay's own commit
  queues behind the 20 ingest transactions.
- Contention for the main pool. `rts-db` sat at 20 of 20 active. The relay has its own pool, but the ingest
  transactions it competes with on CPU and WAL are many.

The measurements do not split the slowdown between CPU and WAL. Either way the mechanism is the same: the relay
handles one event at a time, so its throughput is 1 divided by the time per event, and any extra latency per round
trip is paid 400 times per batch. Ingest runs 20 transactions at once and hides its latency, so it keeps writing
while the relay falls behind. The relay's design (`OutboxRelay.kt:115`) is serial on purpose: one instance delivers
at a time under advisory lock 10 001 (`OutboxRelay.kt:122`), so that one trip's events are handled in order.

When the relay is behind it never waits: a full batch skips the 250 ms pause (`OutboxRelay.kt:81`). The 250 ms
batch period seen when idle is that pause with nearly empty batches.

## How "online" is judged

This section describes the rule before fix C. [After fix C](#after-fix-c) gives the rule now.

`FleetDriver.lastSeenAt` is the newer of the last position's `recordedAt` and the last device status time, both from
the phone's clock (`FleetDriver.kt:48`). "Online" means `last_seen_at` is later than the server's now minus
`events.staleAfter`, 60 s (`FleetConditions.kt:34`, `ExposedFleetRepository.kt:105`, `base.conf:38`). The relay
writes `last_seen_at` only when it handles the event. So:

- A relay more than 60 s behind hides every driver, even while the fleet rows and the map positions keep changing.
  The map then shows grey markers that move.
- A phone clock more than 60 s slow makes that driver offline at all times, with no backlog. Ingest accepts fixes up
  to 30 s in the future and 24 h in the past (`FixRules.kt:45` and `:46`). A phone 30 s fast stays online for up to
  90 s after it stops.
- A phone that uploads fixes it queued during a gap (Doze, no network) shows offline while it uploads, until a fresh
  fix arrives.

The server already has a server-clock presence. Ingest moves `tracking_sessions.last_seen_at` to the server's now in
the same transaction as the fixes (`TrackingService.kt:120` and `:171`), and the `DriverPresence` port reads it for
the stale-driver job. During a backlog the guest side, which uses that port, still sees the driver present while the
admin side sees the same driver offline. Both use the same 60 s.

## The backlog delays more than the map

Every outbox event goes through the same first-in, first-out lane. During the 202 s lag of the overlap, these were
202 s late as well:

- device commands (`CommandIssuedListener`), including the 12c driver actions,
- trip status for guests and the driver's cancel push (`TripStatusListener`, `DriverCancelListener`),
- trip moments, routes pushed to the driver, and trip summaries.

`OutboxLagging` (`deploy/monitoring/alerts.yml:13`) fires above 30 s, so production would page within a minute of a
backlog like this one. The runbook section ["The outbox is lagging"](runbook.md#the-outbox-is-lagging) already names
the relay as a possible ceiling.

## Side findings

- Two 50-driver simulators from the 12d session were still running at 16:30 (process ids 47718 and 82138, from the
  main checkout, `--duration 60m`). They reconnect to `localhost:8080` and end by themselves at about 17:07 and
  17:24. A simulator outlives the shell command that started it, which is how the 5 000-driver run kept sending.
- Nothing deletes delivered outbox rows. The dev database's `outbox` heap is 854 MB for 600 000 rows. It is not the
  cause here (the partial index `outbox_unpublished` is 8 MB), but the table grows by about 1.4 KB per event, 1 GB per 700 000.
- `OutboxMetrics` reads `count(*)` of waiting events every 15 s. With 100 000 waiting events that query took 470 ms on
  average. The sampler's own `count(*)` took 1.9 s. Both grow with the backlog.
- Hikari's `pending` stays 0 even when requests queue, because `ExposedTransactionRunner` makes callers wait for a
  permit before they ask the pool. `active` at the pool size is the sign of a full pool, which is what
  `DatabasePoolNearlyFull` uses.

## Fix options

### A. Fewer round trips per event, one lane as today

A1, small. Write a position with one statement that applies "newer wins" in SQL, for example
`INSERT ... ON CONFLICT (driver_id) DO UPDATE ... WHERE excluded.position_at > fleet_drivers.position_at`, instead
of `findForUpdate` plus `save`. Let `FixesAcceptedListener` and `ArrivalListener` share one active-trip lookup per
event. That halves the round trips per position event, from 4 to 2, so the relay's ceiling rises up to about twice.

A2, larger. Batch-aware handlers: the relay passes all events of a batch to a handler at once. The fleet projection
writes one multi-row upsert per batch, and the trip handlers read the active trips of all drivers in the batch with
one query, then handle each trip's events in order. A batch then costs about 6 round trips instead of about 400, and
the ceiling depends on database work (about 10 ms per 100 events here) instead of latency.

Trade-offs. A1 moves the newer-wins rule for positions from `FleetDriver.withPosition`, which unit tests cover, into
SQL, and the rebuild must apply the same rule. A2 changes `OutboxEventHandler` for every context. It also makes
failure handling coarser: today the relay knows which event failed (`BatchFailed.eventId`) and delivers the events
before it again, and a handler that takes a whole batch would need that logic redone. Neither changes what happens
beyond the ceiling: a backlog still hides drivers, only later.

### B. A separate, merging consumer for fleet positions

The fleet projection stops handling `FixesAccepted` on the relay. A fleet worker keeps its own cursor (the last
outbox id it applied), reads position events after the cursor in large pages, keeps only the newest per driver
(`DISTINCT ON (driver)`), and writes them with one upsert. When it is behind, it skips positions that a newer one
replaced, so catching up costs one row per driver instead of one per event.

Trade-offs. A second consumer with its own lock and cursor table. A future job that deletes delivered outbox rows must
not delete rows the cursor has not passed. The trip handlers still read every position on the relay, so commands and
trip events still wait behind positions unless A1 or A2 is done too. The map stays fresh during a backlog, but trip
events do not.

### C. Judge "online" on the server's clock

Take presence from the ingest path, which no backlog delays: the server-time `tracking_sessions.last_seen_at` through
the `DriverPresence` port, or a fleet-owned presence table that ingest writes at most once every few seconds per
driver. The list, the counts and the map filter then judge online from it.

Trade-offs. The list stays right during a backlog, and a wrong phone clock no longer hides or prolongs a driver. But a
driver can show online with a position minutes old, so the UI would need to show the position's age. A driver with no
`fleet_drivers` row yet still appears only when the relay creates the row. Reading tracking's table from fleet SQL
crosses the context boundary, and the port gives a set of drivers, which fits keyset paging badly. A presence table
adds writes to the hot path.

### Recommendation

Do A1 first. It is small, keeps the design of phases 3, 10 and 11, and its measured effect is easy to check with
`tools/fleet-lag/sample.sh`. Then do C as its own change, because the phone-clock rule is a correctness bug with no
load at all. Decide on A2 in T10.7, once the relay's ceiling is measured on the target setup. Keep B for a scale where
even A2 cannot keep up, or if the map must stay fresh through long outages.

## After fix A1

Fix A1 is on branch `fix/fleet-relay-round-trips`. A position event for a driver with no trip now costs the relay 2
statements instead of 4, and `FleetProjectionIT` counts them.

- Positions and device statuses write `fleet_drivers` with one statement each. It applies newer wins in SQL:
  `INSERT ... ON CONFLICT (driver_id) DO UPDATE ... WHERE` the stored time is null or older. `last_seen_at` becomes
  `GREATEST` of the position and status times. The rebuild uses the same statements, so the rule has one
  implementation, and `FleetDriver.withPosition` and `withDevice` are gone.
- `DriverMovementService` reads the active trip once per event and hands it to arrival, then progress. Before, Koin's
  hash map happened to run `ArrivalListener` before `FixesAcceptedListener`, so the new order keeps what the code did.

### Idle drain, before and after

Both builds ran on 2026-11-21 on the same Mac, one after the other: `main` at `a9e6471` and the fix. Each run used a
fresh Postgres 16 container on port 55432 and the server on port 18080 (Java 21, `APP_ENV=dev`). The backlog was
200 000 `FixesAccepted` events from 5 000 drivers with no trip, 5 fixes each. One transaction inserted them all, so
the relay drained them with nothing else running. The numbers come from `outbox.published_at`, as in
[how to repeat it](#how-to-repeat-it).

| Run | Events delivered a second | Batch gap, p50 | Batch gap, p95 |
|---|---|---|---|
| Before, run 1 | 1 464 | 48.7 ms | 143.5 ms |
| After, run 1 | 1 621 | 33.8 ms | 256.2 ms |
| Before, run 2 | 382 | 76.7 ms | 900.9 ms |
| After, run 2 | 2 941 | 19.5 ms | 93.7 ms |

Other work shared the Mac during these runs. The load average reached 16 on 10 cores during the second run before
the fix, which explains its 382 events a second, and it had fallen by the second run after. So the rates swing with
the load and give no clean factor. The median gap between batches is the steadier signal: 33.8 and 19.5 ms after,
against 48.7 and 76.7 ms before. In run 1, the pair closest in load, the fix delivered 11 % more events a second and
its median batch was 31 % shorter. Throughput gained less than the median because of slow batches at p95.

These runs are slower than the 2 430 events a second of the investigation's drain. The payload differs (5 fixes per
event here) and the Mac was busier, so compare only runs from the same day.

Not measured: the 5 000-driver run and the 10 100-driver overlap. The work closed before them. T10.7 should measure
the relay under load, with Postgres on another host, where each saved round trip counts more.

## After fix C

Fix C is on branch `fix/fleet-online-server-clock`, cut from fix A1. "Online" now reads the server's
clock: `fleet_drivers.heard_at` (V23) within `events.staleAfter` (60 s) of the server's now.

### What heard_at is and who writes it

`heard_at` is the server time when ingest last accepted data from the driver: a hello, a stored batch or a
device_status. These are the moments that move `tracking_sessions.last_seen_at`, so the admin side and the guest side
(`DriverPresence`) now judge presence on the same times.

- The tracking context publishes a port, `DriverHeard` (`tracking/application/api/DriverHeard.kt`). `TrackingService`
  calls it with the same `now` that it writes into the session, inside the ingest transaction. The fleet context
  implements it, so tracking never imports fleet. This follows the existing rule that another context is reached only
  through its `application.api`.
- `FleetPresence` (fleet) only records the newest time per driver in a `ConcurrentHashMap`. The ingest path therefore
  gains no database write. A worker on every instance flushes the map every `fleet.heardFlush` (5 s): one transaction,
  each driver written at most once per flush, however many batches it sent. "Online" can lag by up to 5 s.
- The flush never waits for a row lock. It locks the rows with `FOR UPDATE SKIP LOCKED` and writes only those, then
  keeps the skipped drivers for the next flush. The relay locks up to 100 rows per transaction in event order, so a
  flush that waited while it held other rows could deadlock with it. Postgres would then roll back one of the two, and
  a rolled-back relay batch counts as a failed attempt against its event.
- A driver with no `fleet_drivers` row gets one from the flush, with only `heard_at` set, in a transaction of its own
  that holds no other row. The other choice, staying invisible until the relay creates the row, would keep the 12d bug
  for new drivers: a new simulator run's drivers have no rows, and during a backlog the relay creates those rows last.
- The flush also moves `updated_at`. The live map's tick reads rows by `updated_at`, so a driver who comes back online
  is sent again even when the relay brings no new position. Going offline still writes nothing: the tick finds those
  drivers by `heard_at BETWEEN` the stale window. Both queries have an index (`fleet_drivers_updated`,
  `fleet_drivers_heard`).
- A rebuild empties the table, so it sets `heard_at` again from the sessions (`DriverActivity.latestHeard`) with the
  flush's own statements. V23 does the same once for existing rows, so drivers online at deploy time stay online.

### What stays on last_seen_at

`last_seen_at` keeps the phone's time of the newest position or status. The driver list is still ordered and paged by
it, with the same index and cursor. Ordering by `heard_at` would change the cursor format, need a second ordered
index, and reshuffle every online driver every 5 s. The list order now means "freshest data first", which matches what
each row shows.

### What the fix changes for the three cases

| Case | Before fix C | After fix C |
|---|---|---|
| Relay more than 60 s behind | Every driver offline | Online, with an old position |
| Phone clock more than 60 s slow | That driver offline at all times | Online |
| Phone uploads fixes queued during Doze | Offline until a fresh fix | Online from the first upload |

A driver can now be online with a position minutes old, so admin-web shows the age next to "Online": "position 3 min
old" in the list and on the driver page, and "last update 0 s old" in the map panel, whose marker carries only the
newer of the position and status times. The age uses the server's `at` of the answer, so the browser's clock plays no
part. With a slow phone clock the age includes the clock's error, which is the honest reading of what the server has.

### Contracts

`heardAt` is next to `lastSeenAt` in `FleetDriver` (driver list and driver detail), in the CSV after `lastSeenAt`, and
in `DriverMarker` on the fleet socket. It is optional everywhere, so old readers ignore it.

### Not fixed by C

A relay backlog still delays the positions on the map, device commands and trip events, as described in
[the backlog delays more than the map](#the-backlog-delays-more-than-the-map). C only stops the backlog from hiding
drivers. A2 or B remain the options for the delay itself. No load run was repeated for C.

## Relation to the phase 10 capacity work (T10.7)

T10.7, the capacity test and report in `docs/ops/capacity.md`, is not done, and the runbook already asks to record the
relay's ceiling there. This investigation gives the first numbers on one Mac:

| Situation | Relay delivered |
|---|---|
| Nothing else running (drain after the load) | 2 430 events a second, a batch every 40 ms |
| 5 000 drivers writing about 1 000 events a second | kept up, a batch every 56 ms, so a ceiling of about 1 800 |
| About 10 100 drivers, Mac overloaded | 290 to 830 events a second, typically 500, a batch every 221 ms |

Because the relay is bound by latency, its ceiling depends on the round-trip time to Postgres. On this Mac a round
trip costs about 0.1 ms. As an estimate, at 0.5 ms per round trip, common between two machines, 4 round trips per
event give about 500 events a second, the load of about 2 500 drivers. T10.7 should therefore measure the relay with
Postgres on another host, and it should pass only if `rts_outbox_oldest_age_seconds` stays low (for example under
5 s at p95). 60 s is where the fleet view breaks.

For local E2E runs after a load test, check that `rts_outbox_pending` is near 0, or start from a fresh database
volume. TC-12-E2E-01 waits for 50 drivers online and fails behind a backlog.

## How to repeat it

Start Postgres and the server with a high rate limit. Then, in three terminals:

```bash
BASE_URL=http://localhost:8080 PSQL="docker exec -i route-tracking-db-1 psql -U rts -d rts" INTERVAL=10 tools/fleet-lag/sample.sh
```

```bash
tools/simulator/build/install/simulator/bin/simulator fleet --drivers 5000 --dashboards 1 --duration 7m
```

After 2 minutes, in a third terminal, start a second fleet of 5 100 drivers for 5 minutes, so both end together:

```bash
tools/simulator/build/install/simulator/bin/simulator fleet --drivers 5100 --dashboards 0 --duration 5m
```

When both end, start 50 drivers and the timer at the same moment:

```bash
tools/simulator/build/install/simulator/bin/simulator fleet --drivers 50 --dashboards 0 --duration 15m
```

```bash
BASE_URL=http://localhost:8080 EXPECTED=50 tools/fleet-lag/wait-online.sh
```

The relay's batch history needs no tool: every event of one batch has the same `published_at`, because
`CURRENT_TIMESTAMP` is the transaction's start time (`OutboxRelay.kt:200`).

```sql
WITH b AS (SELECT published_at, count(*) AS n FROM outbox WHERE published_at > now() - interval '60 seconds' GROUP BY 1),
     g AS (SELECT n, extract(epoch FROM published_at - lag(published_at) OVER (ORDER BY published_at)) * 1000 AS gap_ms FROM b)
SELECT count(*) AS batches, sum(n) AS events, round(avg(n), 1) AS avg_size,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY gap_ms) AS p50_gap_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY gap_ms) AS p95_gap_ms
FROM g;
```
