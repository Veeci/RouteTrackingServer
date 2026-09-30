# Phase 0 — Foundation

Branch: `feat/p0-foundation` · Depends on: – · Unlocks: everything

## Goal

Everything a backend team sets up before the first feature: project layout, build conventions,
local infrastructure (Docker Compose with Postgres), containerized build, CI with real-database
tests, static analysis, and the test source sets. After this phase, `docker compose up` runs the
service and its database, and CI proves every PR builds, passes tests and produces an image.

## Scope

**In:** Gradle modules `app`, `protocol`, `tools:simulator`; shared build config; version catalog;
Spotless/ktlint, detekt; Kover; source sets `integrationTest`, `e2eTest`, `testFixtures`;
Konsist skeleton; Dockerfile; `deploy/docker-compose.yml` (Postgres, app); `.env.example`;
GitHub Actions (build, test, image); Dependabot; PR template; README "Getting started".

**Out:** business code, typed config and platform plugins (phase 1).

## Design

### Gradle

```
settings.gradle.kts      include(":app", ":protocol", ":tools:simulator")
build.gradle.kts         Spotless + detekt + Kover applied to all Kotlin projects via a small `allprojects` block
                         (three modules don't justify convention plugins; revisit if modules grow)
gradle/libs.versions.toml single catalog (drop the separate ktorLibs catalog for one place of truth, or keep it; pick one and document)
app/build.gradle.kts     kotlin-jvm, serialization, io.ktor.plugin, application, java-test-fixtures, jvm-test-suite (integrationTest, e2eTest)
```

Test suites with Gradle's `jvm-test-suite` plugin:

```kotlin
testing {
    suites {
        val test by getting(JvmTestSuite::class) { useJUnitJupiter() }
        register<JvmTestSuite>("integrationTest") {
            dependencies { implementation(project()); implementation(testFixtures(project())) /* testcontainers, ktor test host */ }
            targets.all { testTask.configure { shouldRunAfter(test) } }
        }
        register<JvmTestSuite>("e2eTest") { /* + project(":tools:simulator") */ }
    }
}
tasks.check { dependsOn(testing.suites.named("integrationTest"), testing.suites.named("e2eTest")) }
```

### Container image

Multi-stage `deploy/Dockerfile`:
1. `gradle:jdk21` stage builds `./gradlew :app:installDist` (layer-cached dependencies).
2. `eclipse-temurin:21-jre` runtime, non-root user, `EXPOSE 8080`, `HEALTHCHECK` on `/health/live`
   (added in phase 1; placeholder `/` until then), OpenTelemetry Java agent copied in (disabled by default via env).

Alternative: Ktor Gradle plugin `buildImage` (Jib). Dockerfile chosen because it's explicit and portable.

### Local stack (`deploy/docker-compose.yml`)

| Service | Image | Purpose |
|---|---|---|
| `db` | `postgres:16-alpine` | Primary database, named volume, healthcheck `pg_isready` |
| `app` | built from Dockerfile | Service, `depends_on: db (healthy)`, env from `.env` |
| `jaeger` (profile `observability`) | `jaegertracing/all-in-one` | Traces in dev (used from phase 1) |

Day-to-day development runs only `db` in Docker and the app from the IDE/Gradle (`./gradlew :app:run`).

### CI (`.github/workflows/ci.yml`)

| Job | Steps |
|---|---|
| `build` | checkout → setup JDK 21 (Temurin) → `gradle/actions/setup-gradle` (cache) → `./gradlew check` → upload test reports on failure |
| `image` (needs build) | `docker build` → on `main`: push `ghcr.io/veeci/route-tracking-server:<sha>` and `:main` |

Branch protection on `main`: PR required, `build` must pass.

### Architecture test skeleton

Konsist rules defined now, trivially passing until contexts exist; they become meaningful from phase 1.

## Tasks

- [ ] T0.1 Create `app`, `protocol`, `tools:simulator` modules; move generated sources into `app` under `veeci.practicing.rts`.
- [ ] T0.2 Single version catalog with all planned dependencies (Ktor, Koin, Hoplite, Exposed, Flyway, Hikari, Postgres, Logback, logstash-encoder, Micrometer, kotest, Testcontainers, Konsist, OpenAPI validator).
- [ ] T0.3 Spotless (ktlint) + detekt (`config/detekt.yml`) + `.editorconfig`; format once in a separate commit.
- [ ] T0.4 Test suites `integrationTest`, `e2eTest`; `testFixtures`; Kover with the coverage rule from the strategy (report only for now).
- [ ] T0.5 Dockerfile + `.dockerignore`; compose file; `.env.example`.
- [ ] T0.6 GitHub Actions `ci.yml`; Dependabot (`gradle`, `github-actions`, `docker`); PR template; branch protection.
- [ ] T0.7 Konsist rule set (below) in `app/src/test/.../architecture/`.
- [ ] T0.8 README: prerequisites (JDK 21, Docker), `docker compose up db`, run, test commands.

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-0-BLD-01 | Build | Clean clone → `./gradlew build` succeeds without Docker running (unit suite only in `build`) | P0 |
| TC-0-BLD-02 | Build | `./gradlew check` with Docker runs `test`, `integrationTest`, `e2eTest` (each may be empty) | P0 |
| TC-0-BLD-03 | Build | A test placed in `integrationTest` is not executed by `./gradlew test` | P0 |
| TC-0-IMG-01 | Build | `docker build -f deploy/Dockerfile .` produces an image that starts and answers `GET /` with 200 | P0 |
| TC-0-IMG-02 | Build | Container runs as non-root (`docker run --rm image id -u` ≠ 0) | P1 |
| TC-0-CMP-01 | Manual | `docker compose -f deploy/docker-compose.yml up` → db healthy, app reachable on :8080 | P0 |
| TC-0-QA-01 | Lint | Introduced formatting violation fails `spotlessCheck` (verified locally, not committed) | P0 |
| TC-0-QA-02 | Lint | Introduced detekt violation fails `detekt` | P1 |
| TC-0-ARCH-01 | Architecture | Nothing under `..domain..` imports `io.ktor`, `org.jetbrains.exposed`, `java.sql`, `org.koin` | P0 |
| TC-0-ARCH-02 | Architecture | `shared..` imports no context package | P0 |
| TC-0-ARCH-03 | Architecture | A context imports another context only via `..application.api..` or `..domain.event..` | P0 |
| TC-0-CI-01 | CI | PR with a failing test shows a red `build` check and cannot be merged | P0 |
| TC-0-CI-02 | CI | Merge to `main` publishes an image tagged with the commit SHA to GHCR | P1 |

## Test implementation notes

- Konsist rules use a list of context names in one place (`val contexts = listOf("identity", "tracking", ...)`)
  so adding a context doesn't require new rules.
- TC-0-IMG-01 can be automated later as a CI step (`docker run -d` + `curl --retry`); for now verify manually and note it in the PR.
- Testcontainers on macOS: with OrbStack or Docker Desktop it works out of the box; set
  `TESTCONTAINERS_RYUK_DISABLED=false` (default) so containers are cleaned up.

## Definition of Done

- `docker compose up` gives a running app + db; `./gradlew check` is green locally and in CI.
- `main` is protected; images are published on merge.
- All P0 cases pass.
