# RouteTracking Server

[![CI](https://github.com/Veeci/RouteTrackingServer/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Veeci/RouteTrackingServer/actions/workflows/ci.yml)

Backend for the [RouteTracking SDK](https://github.com/Veeci/RouteTrackingSdk): driver–guest trip
tracking over WebSocket, server-side trip events, shortest-path routing, remote device control and
SDK health telemetry.

Kotlin · Ktor 3 · PostgreSQL 16 · Docker · GitHub Actions. Modular monolith with bounded contexts
(see [docs/architecture/overview.md](docs/architecture/overview.md)).

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Gradle downloads it automatically if missing (foojay toolchains) |
| Docker | any recent | [OrbStack](https://orbstack.dev) or Docker Desktop. Needed for the local database, integration tests and the image |
| IDE | Android Studio or IntelliJ IDEA | Open the repo root; Gradle sync picks up all modules |

## Getting started

```bash
git clone https://github.com/Veeci/RouteTrackingServer.git
cd RouteTrackingServer
cp deploy/.env.example deploy/.env                     # then edit DB_PASSWORD
docker compose -f deploy/docker-compose.yml up -d db   # start PostgreSQL
./gradlew :app:run                                     # start the server on http://localhost:8080
```

Check it: `curl localhost:8080/health/ready` → `{"status":"UP","checks":{"db":"UP"}}`.
Metrics: `curl localhost:8080/metrics`.

## Everyday commands

| Command | What it does | Needs Docker |
|---|---|---|
| `./gradlew :app:run` | Run the server locally | db only |
| `./gradlew test` | Fast tests: unit, architecture | no |
| `./gradlew integrationTest` | Tests against real Postgres (Testcontainers) | yes |
| `./gradlew e2eTest` | End-to-end journeys | yes |
| `./gradlew check` | Everything above + ktlint + detekt | yes |
| `./gradlew spotlessApply` | Auto-fix formatting (run before committing) | no |
| `./gradlew koverHtmlReport` | Coverage report → `build/reports/kover/html/index.html` | yes |
| `./gradlew :tools:simulator:run` | Fake driver/guest client (commands arrive in phase 2) | no |

Local stack:

| Command | What it starts |
|---|---|
| `docker compose -f deploy/docker-compose.yml up -d db` | PostgreSQL on `localhost:5432` |
| `docker compose -f deploy/docker-compose.yml up --build` | PostgreSQL + the app as a container |
| `docker compose -f deploy/docker-compose.yml --profile observability up --build` | + Jaeger tracing UI on `localhost:16686` |
| `docker compose -f deploy/docker-compose.yml down` | Stop everything (data kept in the `db-data` volume; add `-v` to wipe it) |

Connect to the database: `docker compose -f deploy/docker-compose.yml exec db psql -U rts -d rts`
(or DBeaver/TablePlus on `localhost:5432` with the values from `deploy/.env`).

## Repository layout

| Path | Contents |
|---|---|
| `app/` | The service: bounded contexts, platform, migrations, config, all test suites |
| `protocol/` | WebSocket message classes, shared with the Android SDK |
| `tools/simulator/` | CLI fake driver/guest used for manual testing and e2e tests |
| `deploy/` | Dockerfile, docker-compose, `.env.example` |
| `config/detekt/` | Static analysis overrides |
| `.github/` | CI workflow, Dependabot, PR template |
| `docs/` | Architecture, conventions, protocol, testing strategy, phase plans |

Test source sets in `app/src/`: `test` (fast), `integrationTest` (real Postgres), `e2eTest`
(journeys), `testFixtures` (shared helpers and GPX/OSM fixture files).

## Contributing workflow

1. Branch from `main`: `feat/p<phase>-<topic>`.
2. Follow the phase doc in [docs/plan/](docs/plan/): update API contracts first, write the P0 test cases, implement.
3. `./gradlew spotlessApply check` must pass locally.
4. Open a PR using the template (list the test-case IDs covered). CI must be green; `main` is protected.
5. Squash-merge. Commit messages follow [Conventional Commits](https://www.conventionalcommits.org).

Conventions: [docs/architecture/conventions.md](docs/architecture/conventions.md) ·
Testing: [docs/testing/strategy.md](docs/testing/strategy.md) ·
Roadmap: [docs/README.md](docs/README.md)

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Address already in use` on 8080 | Another server is running: `lsof -iTCP:8080 -sTCP:LISTEN`, then stop it |
| `POSTGRES_PASSWORD` / `DB_PASSWORD` error from compose | `deploy/.env` is missing: copy it from `deploy/.env.example` |
| Integration tests skipped | Docker isn't running; start OrbStack / Docker Desktop |
| Red imports in the IDE after pulling | *File → Sync Project with Gradle Files* |
| `spotlessCheck` fails | Run `./gradlew spotlessApply` and commit the result |
