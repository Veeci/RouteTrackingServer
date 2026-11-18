# Phase 1.5 — Staging Deploy on a Home Laptop

Branch: `feat/p1b-staging-repo` · Depends on: 1, 3 (JWT) · Unlocks: real-device testing for every later phase

**Status: repository half built (2026-11-18, branch `feat/p1b-staging-repo`).** The machine setup (T1b.1), the
GitHub and Tailscale setup (T1b.6), the Funnel header check (T1b.4) and the failure drills (T1b.8) are manual and
still open. See [As built](#as-built). Re-scoped on 2026-11-16. The first version of this plan used Fly.io and was deferred on
2026-10-05, because the project uses free tooling only. This version runs staging on one of two old laptops at
home, at no cost. The step-by-step machine setup is in [`docs/ops/staging-setup.md`](../ops/staging-setup.md).

## Goal

Put the server on a public HTTPS and WSS URL, deployed automatically on every merge to `main`, with migrations,
secrets, a readiness check, a smoke test and a rollback path. The Android SDK can then be tested from a real
phone on mobile data, and every later phase ships to staging as soon as it merges.

## Scope

**In:** one `staging` environment on Laptop 2, Postgres in Docker on the same laptop, a public URL through
Tailscale Funnel, a deploy job in GitHub Actions that reaches the laptop over the tailnet, migrations before the
new app starts, secrets kept on the laptop and in GitHub, a readiness-gated deploy with automatic rollback, a smoke
test, nightly backups to Laptop 1, a token endpoint for testers behind a staging access token.

**Out:** multiple instances, Redis, alerts, a domain name, uptime guarantees (phase 10).

## Design

### Topology

```
Android phone ──HTTPS/WSS──> Tailscale Funnel (rts-staging.<tailnet>.ts.net:443)
                                   │  rides the connection tailscaled opened from the laptop
                                   ▼
Laptop 2 "rts-staging" (Debian 13, Docker)              Laptop 1 "rts-backup" (Debian 13)
  tailscaled ──> 127.0.0.1:8080 ──> app container         nightly pg_dump copies
                                     └─> db container      (later: second instance in phase 10)
                                         (postgres:16, volume)
GitHub Actions (GitHub-hosted runner) ──tailnet SSH──> deploy@rts-staging: deploy.sh <sha>
```

| Part | Choice | Why |
|---|---|---|
| Machine | Laptop 2 (i5-4210H, 16 GB, SSD), Debian 13 minimal | The faster CPU parses the Hanoi graph sooner. 16 GB holds the JVM, the graph and Postgres. |
| Public URL | Tailscale Funnel, `https://rts-staging.<tailnet>.ts.net` | Free on all plans, fixed hostname, TLS included. The laptop connects out, so it works behind CGNAT with no router port open. |
| Database | `postgres:16-alpine` in the same compose project, named volume, no published port | The outbox relay polls every 250 ms. Keeping the database on the same machine keeps that poll off the network. |
| Image | The `:<sha>` image CI already pushes to `ghcr.io/veeci/route-tracking-server` | The laptop is x86-64, so no multi-arch build is needed. Staging runs the exact image CI built. |
| Road graph | `/srv/rts/osm/hanoi.osm` on the laptop, mounted read-only at `/app/data/osm/hanoi.osm` | The file is 100 to 200 MB and gitignored. `routing.graphPath` already defaults to that relative path. |
| Deploy transport | GitHub-hosted runner joins the tailnet with `tailscale/github-action@v4` (ephemeral node, `tag:ci`), then SSH to user `deploy` | The repository is public. GitHub's hardening guide says self-hosted runners "should almost never be used for public repositories", because a fork's pull request can run code on them. |
| Backups | Nightly `pg_dump -Fc` on Laptop 2, copied to Laptop 1 over the tailnet, 14 days kept | A disk failure on Laptop 2 must not lose the data. |

Rejected options, for the record: Fly.io (no free tier, about USD 8.50 a month for 1 GB in Singapore, managed
Postgres from USD 38), Render and Koyeb free tiers (they sleep and drop WebSockets), Neon free (the 250 ms relay poll
keeps compute awake and uses up the 100 CU-hours by mid-month), Oracle Always Free (card required, idle reclaim
rule). A Cloudflare named tunnel would also work but needs a domain. The quick tunnel stays as a fallback, with a
URL that changes on every restart.

### Files this phase adds

| File | Purpose |
|---|---|
| `deploy/staging/compose.yml` | The staging stack: `db` and `app`. The app port is bound to `127.0.0.1:8080` only, so Funnel is the one public entry. |
| `deploy/staging/deploy.sh` | Runs on the laptop: pull `:<sha>`, run migrations, start the app, wait for `/health/ready`, roll back to the previous tag on failure, record the tag. |
| `deploy/staging/backup.sh` | Nightly `pg_dump`, copy to Laptop 1, prune old dumps. |
| `.github/workflows/deploy-staging.yml` | After CI succeeds on a push to `main`: join the tailnet, copy the two files, run `deploy.sh`, smoke test, write the summary. `workflow_dispatch` with an `image_sha` input is the rollback button. |
| `app/src/main/resources/config/staging.conf` | Already existed (`migrateOnStart = false`). Now documents the staging token and the forwarded-headers flag. |
| `docs/ops/staging-setup.md` | Machine setup and day-to-day operations. |

The files are in the repository. `docs/ops/staging-setup.md` explains how each one is used on the laptop.

### Migrations

The app runs with `db.migrateOnStart = false` in staging. `deploy.sh` runs migrations as a separate step before the
new app starts, so a failed migration stops the deploy while the old version keeps serving.

T1b.2 adds a `migrate` entry point to the image (a second `main` that runs `Migrations.run` and exits), started as
`docker compose run --rm --no-deps --entrypoint /app/bin/migrate app`. The `--entrypoint` flag matters, because the
image's `ENTRYPOINT` is `/app/bin/app` and a plain command would only be passed to it as arguments. The
`DB_MIGRATEONSTART=true` workaround for the first deploy is no longer needed.

**Rule from now on:** every migration must work with the previous release (expand and contract): add columns as
nullable or with defaults, and never rename or drop a column in the release that stops using it. Rollback depends on
this, because `deploy.sh` restarts the previous image against the already-migrated schema.

### Secrets and config

| Name | Where it lives | Notes |
|---|---|---|
| `DB_PASSWORD`, `JWT_SECRET`, `STAGING_ACCESS_TOKEN` | `/srv/rts/.env` on Laptop 2, mode 600, owner `deploy` | Never in git, the image or the workflow logs. The app refuses to start in staging without `STAGING_ACCESS_TOKEN`, or with fewer than 32 characters |
| `APP_ENV=staging`, `APP_VERSION=<sha>` | Set by `compose.yml` | `APP_VERSION` overrides `app.version`, so logs and `/health` show the running SHA |
| `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET` | GitHub secrets, environment `staging` | Tailscale OAuth client with the writable `auth_keys` scope and tag `tag:ci` |
| `STAGING_SSH_KEY`, `STAGING_KNOWN_HOSTS` | GitHub secrets, environment `staging` | A key used only for deploys, and the laptop's host key, so SSH never trusts on first use |
| `STAGING_URL` | GitHub repository variable | `https://rts-staging.<tailnet>.ts.net`. It must be a repository variable, because a job-level `if` cannot read environment variables. The deploy workflow skips itself while it is unset |

### Getting tokens in staging

Clients need a JWT, but `POST /api/v1/dev/tokens` was mounted only when `APP_ENV` is `dev` or `test`. Staging has
no login yet, so testers would have no way to get a token.

Decision: mount the dev token route in staging too, behind a staging access gate.

- `StagingGate` plugin, installed only when `APP_ENV=staging`, applied to `/api/v1/dev/*`.
- The caller must send `X-Staging-Token: <STAGING_ACCESS_TOKEN>`. Missing or wrong gives 401 Problem.
- All other routes keep their normal JWT checks. `/health/*` and `/ws/v1/echo` stay open.
- The simulator and the SDK debug build read the staging token from local config, never from git.
- Prod never mounts the route (unchanged).

### Client IP behind Funnel

Funnel proxies to `http://127.0.0.1:8080`, so every request reaches Ktor from a loopback address. The per-IP rate
limit (`http.rateLimitPerMinute = 300`) would then become one shared limit for all testers. Task T1b.4 checks which
forwarding headers Funnel sends. If it sends `X-Forwarded-For`, install Ktor's `XForwardedHeaders` plugin in staging,
trusting loopback only. If it sends nothing usable, raise the staging limit and record the reason.

The code half is built: `http.trustForwardedHeaders` (default false, `HTTP_TRUSTFORWARDEDHEADERS`) installs
`XForwardedHeaders` and trusts the headers only from a loopback peer. Phase 10d's nginx reuses the same flag. One
open point remains for the manual check: the app runs in a container, so Docker forwards Funnel's connection from
the bridge gateway (an address such as `172.18.0.1`), not from `127.0.0.1`. If the app then still sees one address
for every tester, the trusted peers must include that gateway. See [As built](#as-built).

### Deploy pipeline

```
ci.yml (existing): push to main ─> build + test ─> image :<sha> and :main pushed to GHCR
deploy-staging.yml (new): on workflow_run of CI, conclusion success, event push, branch main
   1. tailscale/github-action@v4 joins the tailnet as an ephemeral tag:ci node
   2. scp deploy/staging/{compose.yml,deploy.sh} to deploy@rts-staging:/srv/rts/
   3. ssh deploy@rts-staging "bash /srv/rts/deploy.sh <sha>"
        pull :<sha> ─> migrate (fails: stop, old app keeps serving)
        ─> up -d app ─> poll /health/ready for up to 180 s
        ─> not ready: restart the previous tag, exit 1
   4. smoke: curl -fsS $STAGING_URL/health/ready from the runner, through Funnel
   5. job summary: deployed SHA and URL
```

- `concurrency: deploy-staging` with `cancel-in-progress: false`, so two merges deploy one after the other.
- The job filters on `workflow_run.event == 'push'`. Without that filter, a pull request whose branch happens to be
  named `main` could start a deploy.
- `workflow_dispatch` with `image_sha` redeploys any earlier image. This is the manual rollback.
- The readiness window is long because `/health/ready` waits for the Hanoi graph, and the i5-4210H parses a
  100 to 200 MB XML file in tens of seconds. Liveness stays UP meanwhile (phase 6 design).

### Laptop operations

- Lid closed: `HandleLidSwitch=ignore` in `/etc/systemd/logind.conf`, and the sleep targets masked.
- Power: "restore on AC power loss" in the BIOS where the laptop has it. The battery covers short cuts.
- Restarts: `restart: unless-stopped` on both services. Docker and tailscaled start at boot, and Funnel started
  with `--bg` resumes after a reboot.
- Updates: `unattended-upgrades` for Debian security patches.
- Memory: `mem_limit: 2g` on the app. The image's `-XX:MaxRAMPercentage=75` gives a 1.5 GB heap, three times the
  512 MB the graph load needs (TC-6-GR-09).

### Observability in staging

- Logs: `docker compose logs -f app` on the laptop (JSON lines with `traceId`).
- Metrics: read them on the laptop with `curl 127.0.0.1:8080/metrics`. Staging runs no Prometheus.
  Funnel forwards every path to the app, so `/metrics` is also public through Funnel. It holds counts and timings,
  no personal data. Closing it is a follow-up (see As built).
- Traces: the OpenTelemetry agent stays off unless enabled by `APP_OPTS`.

## Tasks

- [ ] T1b.1 Set up both laptops, Tailscale, Funnel and the backup target by following `docs/ops/staging-setup.md`.
- [x] T1b.2 `migrate` entry point in the image; `deploy.sh` uses it instead of `DB_MIGRATEONSTART`.
- [x] T1b.3 `StagingGate` plugin and the dev token route in staging, with tests.
- [ ] T1b.4 Check Funnel's forwarding headers; client IP for rate limiting in staging. **Code done:** the
  `http.trustForwardedHeaders` flag. The header check on the laptop is still open.
- [x] T1b.5 `deploy/staging/compose.yml`, `deploy.sh`, `backup.sh`.
- [ ] T1b.6 Tailscale OAuth client and policy (`tag:ci`, `tag:staging`), deploy SSH key, GitHub environment `staging` with its secrets, and the repository variable `STAGING_URL`.
- [x] T1b.7 `.github/workflows/deploy-staging.yml`: deploy after CI on main, concurrency guard, smoke test, summary, manual redeploy input.
- [ ] T1b.8 Prove the failure paths once (below) and record the run links in the PR.
- [x] T1b.9 Update conventions: the expand and contract migration rule, and "every merge to main deploys to staging".

## As built

Built on 2026-11-18 in branch `feat/p1b-staging-repo`. This section records what the code does where it differs
from, or adds to, the design above.

### The `migrate` entry point (T1b.2)

- `Migrate.kt` holds a second `main` (`MigrateKt`). It loads the config with `AppConfigLoader`, opens a pool of two
  connections, runs `Migrations.run` and exits. It starts neither Koin nor Ktor.
- Exit codes: 0 when the schema is current, 1 for an invalid config or a failed migration. Any other failure, such as
  an unreachable database, is thrown, and the JVM exits with 1 too.
- The pool has two connections because Flyway holds one for its schema history lock and migrates on a second.
  MigrateIT found this: with one connection, Flyway waited 5 s and failed.
- `app/build.gradle.kts` registers `migrateStartScripts`, a second `CreateStartScripts` task, and adds its output to
  the distribution's `bin` folder. The image therefore has `/app/bin/migrate` next to `/app/bin/app`. Its JVM options
  come from `JAVA_OPTS` and `MIGRATE_OPTS`.
- `migrate` reads the same environment as the app, so in staging it also needs `STAGING_ACCESS_TOKEN`. `compose
  run` passes the app service's environment, so `deploy.sh` needs nothing extra.

### The staging gate (T1b.3)

- `StagingGate` (`platform/security/StagingGate.kt`) is a route-scoped plugin. `Route.stagingGate` creates a child
  route for `/api/v1/dev` with `PathPrefixSelector`, the selector the admin area already used, now shared.
- `Application.kt` mounts the dev token route by environment: as before in `dev` and `test`, behind the gate in
  `staging`, and not at all in `prod`.
- The gate compares the header with `MessageDigest.isEqual`. A missing or wrong `X-Staging-Token` gives 401 Problem
  with code `UNAUTHORIZED`.
- The token is config key `staging.access.token`. Hoplite splits environment variable names at each underscore, so
  `STAGING_ACCESS_TOKEN` maps to it with no alias.
- `AppConfigLoader` refuses to load the staging profile without a token of at least 32 characters. `main` then logs
  "Invalid configuration, refusing to start" and exits 1.

### Forwarded headers (T1b.4, code only)

- `http.trustForwardedHeaders`, default false, installs `XForwardedHeaders` with a custom `extractEdgeProxy`. It uses
  the headers only when the peer is a loopback address. It takes the last `X-Forwarded-For` entry, the one the trusted
  proxy appended, and `X-Forwarded-Proto` for the scheme.
- `compose.yml` passes `HTTP_TRUSTFORWARDEDHEADERS` from `/srv/rts/.env`, default false.
- **Open point for the manual check.** Funnel connects to `127.0.0.1:8080` on the host, but Docker forwards that
  connection into the container from the bridge gateway, so the app sees a peer such as `172.18.0.1`. If that holds,
  turning the flag on changes nothing, because the peer is not loopback. The fix is a small change: a list of trusted
  peers in config, set to the compose network's gateway in staging and to nginx's address in phase 10d. Decide it
  after the header check shows what Funnel sends and which peer address the app sees.
- The request log does not record the client address yet, so TC-1b-NET-02 needs one log field added first, or a
  check through the rate limit instead.

### Deploy files and workflow (T1b.5, T1b.7)

Differences from the first draft in `docs/ops/staging-setup.md`:

- `compose.yml` sets `REDIS_URL: ""`, so staging stays one instance without Redis. With Redis off, the app registers
  no Redis readiness check, and the check is never critical anyway. It passes `HTTP_TRUSTFORWARDEDHEADERS` and drops
  `DB_MIGRATEONSTART`.
- `deploy.sh` always runs `/app/bin/migrate`, and refuses an argument that is not a full 40-character SHA. Both scripts
  pass `shellcheck`, which CI now runs.
- The workflow skips both jobs while the repository variable `STAGING_URL` is empty. It also checks
  `workflow_run.head_branch == 'main'`, and refuses an `image_sha` that is not a full SHA before it reaches `ssh`.
- The smoke job reads `STAGING_URL` through `env`, never through `${{ }}` inside the shell line.
- Staging runs no Prometheus. `deploy/monitoring` stays for the local stack and for phase 10.

### Tests

| ID | Where |
|---|---|
| TC-1b-CFG-01 | `AppConfigLoaderTest` (profile, token rules, flag), `StagingGateIT` (no Swagger UI) and `WsEchoIT` TC-1-WS-04 (echo socket open in staging) |
| TC-1b-GATE-01, 02, 03 | `StagingGateIT` |
| TC-1b-MIG-01 | `MigrateIT`. The failing case adds `classpath:db/failing-migration`, a test-only folder with one broken migration |
| Forwarded headers | `ForwardedHeadersTest` (which peers are trusted) and `RequestGuardsIT` (rate limit per forwarded client) |

The DEP, SEC, NET and OPS cases are manual and run on the laptop after T1b.1 and T1b.6.

### Follow-ups found while building

- `/metrics` is public through Funnel. Options: a `tailscale funnel` path mapping that publishes only `/api`, `/ws`
  and `/health`, or the staging gate on `/metrics` too.
- Trusted peers for the forwarded headers, as above.

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-1b-CFG-01 | Unit | `staging` profile decodes; Swagger UI off; echo socket on; `migrateOnStart = false` | P0 |
| TC-1b-GATE-01 | API | `APP_ENV=staging`: `POST /api/v1/dev/tokens` without `X-Staging-Token` gives 401 Problem; with the right token gives a token | P0 |
| TC-1b-GATE-02 | API | `/health/live` and `/health/ready` reachable without any token | P0 |
| TC-1b-GATE-03 | API | Gate not installed and the route behaves as before when `APP_ENV` is `dev` or `test`; route absent in `prod` | P1 |
| TC-1b-MIG-01 | Integration | `migrate` entry point applies pending migrations to an empty DB and exits 0; a failing migration exits non-zero | P0 |
| TC-1b-DEP-01 | CD | Merge to `main` makes staging run the new SHA within about 10 minutes; the summary shows URL and SHA | P0 |
| TC-1b-DEP-02 | CD | A failing migration stops the deploy; the previous version still answers `/health/ready` 200 | P0 |
| TC-1b-DEP-03 | CD | An image whose `/health/ready` never turns UP makes `deploy.sh` restart the previous tag; workflow red | P0 |
| TC-1b-DEP-04 | CD | Two merges in quick succession deploy one after the other; the final SHA is the later commit | P1 |
| TC-1b-DEP-05 | Manual | `workflow_dispatch` with an older SHA rolls staging back to it | P0 |
| TC-1b-DEP-06 | Manual | Reboot Laptop 2: app, database and Funnel come back without manual steps | P0 |
| TC-1b-SEC-01 | Manual | Repo, image layers and workflow logs contain no secret values | P0 |
| TC-1b-SEC-02 | Manual | From outside the tailnet, only the Funnel URL answers; ports 8080 and 5432 are not reachable on the LAN | P1 |
| TC-1b-NET-01 | Manual | A phone on mobile data opens `wss://rts-staging.<tailnet>.ts.net/ws/v1/echo` and exchanges messages for 10 minutes without a drop | P0 |
| TC-1b-NET-02 | Manual | The app's request log shows each tester's real IP, or the staging rate limit is documented as shared | P1 |
| TC-1b-OPS-01 | Manual | Restore last night's dump from Laptop 1 into a scratch database; row counts match | P1 |

## Test implementation notes

- **Failure paths (DEP-02 and DEP-03)** are proven once, on a throwaway branch with `workflow_dispatch`: a broken
  migration, then a readiness check forced DOWN. Push, observe, revert. A rollback that was never exercised is a
  rollback that does not work.
- **WebSockets through Funnel** are not mentioned in Tailscale's Funnel docs. NET-01 is the first thing to run after
  setup. If it fails, fall back to `cloudflared tunnel --url http://localhost:8080` and revisit this plan.
- Gate tests run in the normal API test suite with the `staging` profile overlay and a fixed test token.
- Staging data is disposable. The smoke test uses its own device and session ids.

## Definition of Done

- Every merge to `main` reaches staging automatically, gated by CI, migrations and the readiness check.
- The failure paths (bad migration, bad readiness, manual rollback, reboot) have each been exercised once.
- A phone on mobile data can reach staging over WSS and get a token through the gated route.
- `docs/ops/staging-setup.md` lets someone else rebuild the laptop, deploy and roll back without asking you.
