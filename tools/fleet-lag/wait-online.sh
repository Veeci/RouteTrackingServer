#!/usr/bin/env bash
# Times how long until GET /api/v1/admin/drivers?online=true lists EXPECTED simulated drivers (sim-driver-NNN).
# Start it at the moment the simulator starts. It prints the count every INTERVAL seconds and exits when the count
# reaches EXPECTED, or after TIMEOUT seconds. Written for docs/ops/fleet-lag-investigation.md. Dev servers only: it
# takes an ADMIN token from the dev token route.
#
# Usage:
#   BASE_URL=http://localhost:8080 EXPECTED=50 tools/fleet-lag/wait-online.sh
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
EXPECTED=${EXPECTED:-50}
INTERVAL=${INTERVAL:-2}
TIMEOUT=${TIMEOUT:-1200}

SIMULATOR=${SIMULATOR:-tools/simulator/build/install/simulator/bin/simulator}
token=$("$SIMULATOR" token --role ADMIN --name fleet-lag-probe --url "$BASE_URL" | awk '$1 == "token:" { print $2 }')

start=$(date +%s)
first=""
while true; do
  now=$(date +%s)
  elapsed=$((now - start))
  count=$(curl -sf -H "Authorization: Bearer $token" \
    "$BASE_URL/api/v1/admin/drivers?online=true&q=sim-driver&limit=100" | jq '[.items[] | select(.online)] | length')
  if [[ $count -gt 0 && -z $first ]]; then
    first=$elapsed
    echo "first online driver after ${first}s"
  fi
  echo "$(date +%H:%M:%S) +${elapsed}s online sim drivers: $count"
  if [[ $count -ge $EXPECTED ]]; then
    echo "all $EXPECTED online after ${elapsed}s"
    exit 0
  fi
  if [[ $elapsed -ge $TIMEOUT ]]; then
    echo "timed out after ${elapsed}s with $count online"
    exit 1
  fi
  sleep "$INTERVAL"
done
