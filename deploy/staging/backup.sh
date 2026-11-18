#!/usr/bin/env bash
# Nightly database dump. Runs on Laptop 2 as user deploy, from cron. Keeps 14 days here and on rts-backup.
set -euo pipefail

cd /srv/rts || exit 1
FILE="backups/rts-$(date +%F).dump"

# docker exec, not "docker compose exec": compose would need IMAGE_TAG to read compose.yml.
docker exec rts-staging-db-1 pg_dump -U rts -d rts -Fc > "${FILE}"

scp -q "${FILE}" backup@rts-backup:/srv/rts-backups/
find backups -name 'rts-*.dump' -mtime +14 -delete
ssh backup@rts-backup "find /srv/rts-backups -name 'rts-*.dump' -mtime +14 -delete"
echo "$(date -Is) backup ok: ${FILE} ($(du -h "${FILE}" | cut -f1))"
