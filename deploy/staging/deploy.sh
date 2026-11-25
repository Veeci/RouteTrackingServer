#!/usr/bin/env bash
# Deploys one commit to staging. Runs on Laptop 2 as user deploy:  bash /srv/rts/deploy.sh <full-commit-sha>
# RTS_DIR names another folder than /srv/rts, for a trial run on a development machine.
# Order: pull, migrate, start the app, wait for readiness, start the admin dashboard (12f), smoke-test it. If either
# never gets ready, start the previous versions of both again.
set -euo pipefail

NEW_TAG="${1:?usage: deploy.sh <full-commit-sha>}"
if [[ ! "${NEW_TAG}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Expected a full 40-character commit SHA, got: ${NEW_TAG}" >&2
  exit 2
fi

cd "${RTS_DIR:-/srv/rts}" || exit 1
set -a
# shellcheck source=/dev/null
. ./.env
set +a
PREV_TAG="$(cat current-tag 2>/dev/null || true)"
# The dashboard's tag is kept apart, because a commit from before 12f has no admin-web image.
PREV_ADMIN_TAG="$(cat current-admin-web-tag 2>/dev/null || true)"

compose() { docker compose -p rts-staging -f compose.yml "$@"; }

# /health/ready waits for the Hanoi graph, which takes tens of seconds on this CPU. 90 tries x 2 s = 180 s.
wait_ready() {
  for _ in $(seq 1 90); do
    if curl -fsS http://127.0.0.1:8080/health/ready > /dev/null 2>&1; then return 0; fi
    sleep 2
  done
  return 1
}

# nginx starts in about a second. The smoke test then checks the files, the headers and the proxy to the admin port.
admin_web_ok() {
  for _ in $(seq 1 15); do
    if curl -fsS http://127.0.0.1:8090/healthz > /dev/null 2>&1; then
      bash ./smoke-admin-web.sh http://127.0.0.1:8090 "${ADMIN_WEB_TAG}"
      return
    fi
    sleep 2
  done
  echo "admin-web /healthz did not answer in 30 s" >&2
  return 1
}

echo "Deploying ${NEW_TAG} (previous: ${PREV_TAG:-none}, dashboard: ${PREV_ADMIN_TAG:-none})"
export IMAGE_TAG="${NEW_TAG}"
export ADMIN_WEB_TAG="${NEW_TAG}"
compose pull app
# A missing admin-web image is not fatal: a manual deploy of a commit from before 12f keeps the running dashboard.
if ! compose pull admin-web; then
  echo "No admin-web image for ${NEW_TAG} (a commit before 12f?). Keeping dashboard ${PREV_ADMIN_TAG:-none}." >&2
  ADMIN_WEB_TAG="${PREV_ADMIN_TAG}"
fi
# --wait blocks until the db healthcheck passes. migrate runs with --no-deps, so depends_on does not wait for it.
compose up -d --wait db

# Migrations run before the new app starts, with the new image's bin/migrate. --entrypoint is needed because the
# image's ENTRYPOINT is /app/bin/app: a plain command would only reach it as arguments. If a migration fails,
# set -e stops here and the old app keeps serving.
compose run --rm --no-deps --entrypoint /app/bin/migrate app

compose up -d app

healthy=false
if wait_ready; then
  if [ -z "${ADMIN_WEB_TAG}" ]; then
    healthy=true
  else
    compose up -d --no-deps admin-web
    if admin_web_ok; then healthy=true; fi
  fi
else
  echo "App not ready after 180 s. Last app logs:" >&2
  compose logs --tail 100 app >&2 || true
fi

if [ "${healthy}" = true ]; then
  echo "${NEW_TAG}" > current-tag
  if [ -n "${ADMIN_WEB_TAG}" ]; then echo "${ADMIN_WEB_TAG}" > current-admin-web-tag; fi
  docker image prune -f > /dev/null
  echo "Ready: ${NEW_TAG} (dashboard: ${ADMIN_WEB_TAG:-none})"
  exit 0
fi

# Roll both back. Their images are still on this machine: prune removes only untagged ones.
if [ -n "${PREV_TAG}" ]; then
  export IMAGE_TAG="${PREV_TAG}"
  compose up -d app
  if wait_ready; then
    echo "Rolled the app back to ${PREV_TAG}" >&2
  else
    echo "Rollback to ${PREV_TAG} is not ready either" >&2
  fi
fi
if [ -n "${PREV_ADMIN_TAG}" ]; then
  export ADMIN_WEB_TAG="${PREV_ADMIN_TAG}"
  compose up -d --no-deps admin-web
  echo "Rolled the dashboard back to ${PREV_ADMIN_TAG}" >&2
fi
exit 1
