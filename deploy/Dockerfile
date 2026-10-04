# syntax=docker/dockerfile:1.7
# Multi-stage build: compile with a JDK, run on a slim JRE as a non-root user.
# Build from the repo root:  docker build -f deploy/Dockerfile -t route-tracking-server .

ARG JAVA_VERSION=21
ARG OTEL_AGENT_VERSION=2.31.1

# ---- build stage ------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
WORKDIR /workspace

# 1) Build scripts first, so dependency resolution is cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY app/build.gradle.kts app/
COPY protocol/build.gradle.kts protocol/
COPY tools/simulator/build.gradle.kts tools/simulator/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :app:dependencies > /dev/null

# 2) Sources, then an installable distribution (bin/ script + lib/ jars). Tests run in CI, not here.
COPY app/src app/src
COPY protocol/src protocol/src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :app:installDist

# OpenTelemetry Java agent: bundled but inactive unless JAVA_TOOL_OPTIONS enables it.
ARG OTEL_AGENT_VERSION
ADD https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar /workspace/otel/opentelemetry-javaagent.jar

# ---- runtime stage ----------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime

# curl is only for the HEALTHCHECK.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/app/build/install/app/ /app/
COPY --from=build --chown=app:app /workspace/otel/ /app/otel/

USER app
EXPOSE 8080

# Container-aware heap sizing, fail fast on OOM, production mode for Ktor.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dio.ktor.development=false"
# One JSON object per log line, so log platforms can search by field (see logback.xml).
ENV LOG_FORMAT=json

# Phase 0 placeholder: `/` until phase 1 adds /health/live.
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/ > /dev/null || exit 1

ENTRYPOINT ["/app/bin/app"]
