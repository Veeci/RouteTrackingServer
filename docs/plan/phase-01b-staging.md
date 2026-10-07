# Phase 1.5 — Staging Deploy (Walking Skeleton)

Branch: `feat/p1b-staging` · Depends on: 1 · Unlocks: real-device testing for every later phase

**Status: deferred (2026-10-05).** The project uses free tooling only, and the platforms in this plan
cost money (Fly.io: about USD 4–7 per month for one machine). Until this phase is done, nothing deploys
automatically, and a phone reaches a server on a laptop through a Cloudflare Quick Tunnel
(`cloudflared tunnel --url http://localhost:8080`).

## Goal

Put the platform skeleton on a public HTTPS/WSS URL, deployed automatically on every merge to `main`,
with migrations, secrets, health checks, a smoke test and a rollback path. From here on, every
phase ships to staging as soon as it merges, and the Android SDK can be tested from a real phone on
mobile data.

## Scope

**In:** one `staging` environment on a PaaS, managed Postgres, CD job in GitHub Actions, migrations as
a release step, secrets in the platform store, TLS, health-check-gated rollout, smoke test,
documented rollback, access gate for pre-auth phases, basic log/metrics access.

**Out:** multiple instances, Redis, autoscaling, alerts, backups policy (phase 10).

## Design

### Platform choice

| Option | Pros | Cons |
|---|---|---|
| **Fly.io** (recommended) | Runs the Docker image as-is, WebSockets work out of the box, `release_command` for migrations, CLI-driven, good GitHub Actions support | Paid beyond small usage; check current pricing |
| Render | Simple UI, managed Postgres, Docker deploys | WebSocket idle limits on some plans |
| Railway | Very quick setup | Less control over release steps |
| Single VM + compose (Hetzner, etc.) | Cheapest, full control, teaches Linux ops | You own TLS (Caddy), updates, security patches |

Database: the platform's managed Postgres, or a separate managed Postgres (e.g. Neon). Requirement: Postgres 16,
TLS connections, a connection string provided as a secret.

### Deployment config (Fly.io example)

```toml
# deploy/fly.staging.toml
app = "rts-staging"
primary_region = "sin"                     # Singapore: closest to Vietnam

[build]
  image = "ghcr.io/veeci/route-tracking-server:${SHA}"   # CD passes the image built by CI; no rebuild

[deploy]
  release_command = "/app/bin/migrate"     # Flyway migrate, then exit; deploy aborts if it fails
  strategy = "immediate"                   # single instance for now; "rolling" in phase 10

[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = "off"               # sockets must not be cut by scale-to-zero

  [[http_service.checks]]
    path = "/health/ready"
    interval = "15s"
    timeout = "3s"
    grace_period = "20s"
```

`/app/bin/migrate` is a second entry point in the same image (a `main` that runs Flyway only), so the
schema version always matches the code in that image. The app itself runs with
`db.migrateOnStartup = false` in staging and prod.

### Secrets and config

| Variable | Where it's set | Notes |
|---|---|---|
| `APP_ENV=staging` | `fly.staging.toml` `[env]` | New profile `application-staging.conf` (JSON logs, no Swagger, no dev endpoints) |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `fly secrets set` | Never in the repo or the image |
| `JWT_SECRET` | `fly secrets set` | Used from phase 3 |
| `STAGING_ACCESS_TOKEN` | `fly secrets set` | Access gate, below |
| `FLY_API_TOKEN` | GitHub repo secret | Deploy token scoped to this app only |

### Access gate (until phase 3 auth exists)

Once public, **anyone on the internet can reach the server**. Phase 2's driver socket accepts unauthenticated
uploads, so staging needs a gate until JWT auth lands in phase 3:

- A platform plugin that requires `X-Staging-Token: <secret>` (or `?st=` for WebSocket clients that can't set headers)
  on everything except `/health/*`. Missing or wrong → 401.
- Enabled only when `APP_ENV=staging`; removed (or kept as defence in depth) after phase 3.
- Rate limiting from phase 1 stays on.
- The simulator and the SDK debug build read the token from local config, never committed.

### CD pipeline

```
.github/workflows/ci.yml       (phase 0)  on PR and main: check + docker build
.github/workflows/deploy.yml   (new)      on push to main, after ci succeeds:

  1. docker push ghcr.io/veeci/route-tracking-server:<sha>       (if not already pushed by ci)
  2. flyctl deploy --config deploy/fly.staging.toml --image ghcr.io/...:<sha>
       └─ release_command runs migrations → fails? deploy stops, old version keeps running
       └─ new machine must pass /health/ready → fails? Fly keeps the old machine
  3. smoke test job:
       curl -f https://rts-staging.fly.dev/health/ready
       simulator driver --url wss://rts-staging.fly.dev/ws/v1/driver --gpx routes/straight_2km.gpx --expect-all-acked   (from phase 2)
  4. on smoke failure: flyctl deploy --image <previous sha>  and mark the workflow failed
```

- `concurrency: deploy-staging` in the workflow so two merges never deploy at the same time.
- The workflow summary prints the deployed SHA and the staging URL.
- Manual trigger (`workflow_dispatch`) with an `image_sha` input doubles as the rollback button.

### Migration rule (from now on)

Staging runs migrations before the new code starts, while the old code may still be serving. So every
migration must be **backward compatible with the previous release** (expand/contract): add columns
as nullable or with defaults; never rename or drop in the same release that stops using a column.

### Observability in staging

- Logs: `flyctl logs` (JSON, one line per request, with `traceId`).
- Metrics: `/metrics` exposed on an internal port only (not public), scraped by Fly's Prometheus integration or read via `flyctl proxy`.
- Traces: OpenTelemetry agent disabled by default; enable by env var when needed.

### Phone testing

- SDK debug build points to `wss://rts-staging.fly.dev/ws/v1/driver` with the staging token.
- TLS is real, so no cleartext exceptions are needed in the Android network security config.

## Tasks

- [ ] T1b.1 Choose the platform; create the `rts-staging` app and managed Postgres (Postgres 16, region close to you).
- [ ] T1b.2 `application-staging.conf` profile; `migrate` entry point in the image; `migrateOnStartup` flag.
- [ ] T1b.3 Access gate plugin (staging only) + tests.
- [ ] T1b.4 `deploy/fly.staging.toml` with health checks and release command.
- [ ] T1b.5 Secrets set in the platform; `FLY_API_TOKEN` in GitHub; document them in `deploy/.env.example` (names only).
- [ ] T1b.6 `deploy.yml`: deploy on main after CI, concurrency guard, smoke test, rollback on failure, manual redeploy input.
- [ ] T1b.7 `docs/ops/staging.md`: URL, how to deploy, roll back, read logs, connect with `psql` via `flyctl proxy`, rotate secrets.
- [ ] T1b.8 Update conventions: expand/contract migration rule; "every merge to main deploys to staging".

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-1b-CFG-01 | Unit | `staging` profile decodes; Swagger UI and the dev token endpoint disabled, echo socket enabled; `migrateOnStartup = false` | P0 |
| TC-1b-GATE-01 | API | `APP_ENV=staging`, request without `X-Staging-Token` → 401 Problem; with correct token → passes through | P0 |
| TC-1b-GATE-02 | API | `/health/live` and `/health/ready` reachable without the token (platform health checks need them) | P0 |
| TC-1b-GATE-03 | API | WebSocket upgrade without token → rejected; with `?st=<token>` → accepted | P0 |
| TC-1b-GATE-04 | API | Gate not installed when `APP_ENV=dev` or `test` | P1 |
| TC-1b-MIG-01 | Integration | `migrate` entry point applies pending migrations to an empty DB and exits 0; on a failing migration exits non-zero | P0 |
| TC-1b-DEP-01 | CD | Merge to `main` → staging runs the new SHA within ~10 min; workflow summary shows the URL and SHA | P0 |
| TC-1b-DEP-02 | CD | A deliberately failing migration → deploy aborts, previous version still serves `/health/ready` 200 | P0 |
| TC-1b-DEP-03 | CD | New image whose `/health/ready` fails → platform keeps the old machine; workflow red | P0 |
| TC-1b-DEP-04 | CD | Smoke test failure → workflow redeploys the previous SHA automatically | P1 |
| TC-1b-DEP-05 | CD | Two merges in quick succession → deploys run one after the other, final SHA is the later commit | P1 |
| TC-1b-DEP-06 | Manual | `workflow_dispatch` with an older SHA rolls staging back to it | P0 |
| TC-1b-SEC-01 | Manual | Repo, image layers and logs contain no secret values (`docker history`, grep of logs for the DB password) | P0 |
| TC-1b-SEC-02 | Manual | `/metrics` is not reachable from the public URL | P1 |
| TC-1b-NET-01 | Manual | Real phone on mobile data connects to `wss://rts-staging.fly.dev/ws/v1/echo?st=<token>` (echo is mounted in dev and staging, never prod) and exchanges messages | P0 |

## Test implementation notes

- **Proving failure paths (DEP-02/03/04)** is done once, on a throwaway branch configured to deploy to staging:
  add a broken migration or a health check that returns 503, push, observe, then revert. Record the
  workflow run links in the PR. These are the most valuable tests in this phase — a rollback you've
  never exercised is a rollback that doesn't work.
- **Gate tests** run in the normal API test suite with the `staging` profile overlay and a fixed test token.
- Keep staging data disposable; the smoke test uses its own device/session ids so it never collides with manual testing.

## Definition of Done

- Every merge to `main` reaches staging automatically, gated by CI, migrations and health checks.
- Failure paths (bad migration, bad health, failed smoke test) have each been exercised once and recovered.
- A phone on mobile data can reach staging over WSS.
- `docs/ops/staging.md` lets someone else deploy and roll back without asking you.
