#!/usr/bin/env bash
# Deploys one image to staging. Runs on Laptop 2 as user deploy:  bash /srv/rts/deploy.sh <full-commit-sha>
# Order: pull, migrate, start, wait for readiness. If the new version never gets ready, start the previous one again.
set -euo pipefail

NEW_TAG="${1:?usage: deploy.sh <full-commit-sha>}"
if [[ ! "${NEW_TAG}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Expected a full 40-character commit SHA, got: ${NEW_TAG}" >&2
  exit 2
fi

cd /srv/rts || exit 1
set -a
# shellcheck source=/dev/null
. ./.env
set +a
PREV_TAG="$(cat current-tag 2>/dev/null || true)"

compose() { docker compose -p rts-staging -f compose.yml "$@"; }

# /health/ready waits for the Hanoi graph, which takes tens of seconds on this CPU. 90 tries x 2 s = 180 s.
wait_ready() {
  for _ in $(seq 1 90); do
    if curl -fsS http://127.0.0.1:8080/health/ready > /dev/null 2>&1; then return 0; fi
    sleep 2
  done
  return 1
}

echo "Deploying ${NEW_TAG} (previous: ${PREV_TAG:-none})"
export IMAGE_TAG="${NEW_TAG}"
compose pull app
# --wait blocks until the db healthcheck passes. migrate runs with --no-deps, so depends_on does not wait for it.
compose up -d --wait db

# Migrations run before the new app starts, with the new image's bin/migrate. --entrypoint is needed because the
# image's ENTRYPOINT is /app/bin/app: a plain command would only reach it as arguments. If a migration fails,
# set -e stops here and the old app keeps serving.
compose run --rm --no-deps --entrypoint /app/bin/migrate app

compose up -d app

if wait_ready; then
  echo "${NEW_TAG}" > current-tag
  docker image prune -f > /dev/null
  echo "Ready: ${NEW_TAG}"
  exit 0
fi

echo "Not ready after 180 s. Last app logs:" >&2
compose logs --tail 100 app >&2 || true
if [ -n "${PREV_TAG}" ]; then
  export IMAGE_TAG="${PREV_TAG}"
  compose up -d app
  if wait_ready; then
    echo "Rolled back to ${PREV_TAG}" >&2
  else
    echo "Rollback to ${PREV_TAG} is not ready either" >&2
  fi
fi
exit 1
