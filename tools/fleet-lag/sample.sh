#!/usr/bin/env bash
# Samples the path from a driver fix to the fleet read model, one line every INTERVAL seconds, until stopped.
# Written for docs/ops/fleet-lag-investigation.md.
#
# Columns:
#   time          wall clock
#   written/s     outbox rows written per second (max(id) delta)
#   delivered/s   events the relay delivered per second (rts_relay_delivered_total delta)
#   waiting       outbox rows with published_at IS NULL
#   behind_s      age of the oldest waiting row: how far the relay is behind, in seconds
#   main a/p      rts-db pool: active and pending connections
#   relay a/p     rts-db-relay pool: active and pending connections
#   online        fleet_drivers rows with last_seen_at within STALE_S of the database clock
#   cpu           the server process's CPU use as a share of all cores (Micrometer's process_cpu_usage, 1.00 = all)
#
# Each sample runs one count(*) over the waiting events, which costs seconds with a large backlog: keep INTERVAL at
# 10 s or more during a load run.
#
# Usage:
#   BASE_URL=http://localhost:8080 PSQL="docker exec -i route-tracking-db-1 psql -U rts -d rts" tools/fleet-lag/sample.sh
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
PSQL=${PSQL:-docker exec -i route-tracking-db-1 psql -U rts -d rts}
INTERVAL=${INTERVAL:-5}
STALE_S=${STALE_S:-60}

metric() { # name{labels} from a /metrics scrape, or 0
  awk -v m="$1" 'index($0, m" ") == 1 { print $2; found = 1 } END { if (!found) print 0 }' <<<"$2"
}

prev_id=""
prev_delivered=""
printf '%-8s %9s %11s %8s %8s %8s %9s %6s %5s\n' time written/s delivered/s waiting behind_s "main a/p" "relay a/p" online cpu
while true; do
  scrape=$(curl -s --max-time 2 "$BASE_URL/metrics" || true)
  # shellcheck disable=SC2086 # PSQL is a command line on purpose: split it into words.
  row=$($PSQL -At -F ' ' -c "
    SELECT coalesce(max(id), 0),
           (SELECT count(*) FROM outbox WHERE published_at IS NULL),
           coalesce((SELECT extract(epoch FROM now() - min(created_at))::int FROM outbox WHERE published_at IS NULL), 0),
           (SELECT count(*) FROM fleet_drivers WHERE last_seen_at > now() - make_interval(secs => $STALE_S))
    FROM outbox")
  read -r max_id waiting behind online <<<"$row"
  written_rate="-"
  delivered_rate="-"
  [[ -n $prev_id ]] && written_rate=$(((max_id - prev_id) / INTERVAL))
  prev_id=$max_id
  if [[ -n $scrape ]]; then
    delivered=$(metric 'rts_relay_delivered_total' "$scrape")
    delivered=${delivered%.*}
    [[ -n $prev_delivered ]] && delivered_rate=$(((delivered - prev_delivered) / INTERVAL))
    prev_delivered=$delivered
  else
    # The scrape timed out (a busy server): skip this sample's rate rather than print a false one.
    prev_delivered=""
  fi
  main="$(metric 'hikaricp_connections_active{pool="rts-db"}' "$scrape" | cut -d. -f1)/$(metric 'hikaricp_connections_pending{pool="rts-db"}' "$scrape" | cut -d. -f1)"
  relay="$(metric 'hikaricp_connections_active{pool="rts-db-relay"}' "$scrape" | cut -d. -f1)/$(metric 'hikaricp_connections_pending{pool="rts-db-relay"}' "$scrape" | cut -d. -f1)"
  cpu=$(metric 'process_cpu_usage' "$scrape")
  printf '%-8s %9s %11s %8s %8s %8s %9s %6s %5.2f\n' "$(date +%H:%M:%S)" "$written_rate" "$delivered_rate" "$waiting" "$behind" "$main" "$relay" "$online" "$cpu"
  sleep "$INTERVAL"
done
