#!/usr/bin/env bash
# Nightly database dumps. Runs on Laptop 2 as user deploy, from cron. Keeps 14 days here and on rts-backup.
# The app's database always, and Keycloak's (12g) when its container exists: the realm, the staff accounts and their
# roles live only there, because --import-realm seeds the realm once and every later change goes to that database.
set -euo pipefail

cd /srv/rts || exit 1
DAY="$(date +%F)"
FILES=("backups/rts-${DAY}.dump")

# docker exec, not "docker compose exec": compose would need IMAGE_TAG to read compose.yml.
docker exec rts-staging-db-1 pg_dump -U rts -d rts -Fc > "${FILES[0]}"

if docker container inspect rts-staging-keycloak-db-1 > /dev/null 2>&1; then
  FILES+=("backups/keycloak-${DAY}.dump")
  docker exec rts-staging-keycloak-db-1 pg_dump -U keycloak -d keycloak -Fc > "${FILES[1]}"
fi

scp -q "${FILES[@]}" backup@rts-backup:/srv/rts-backups/
find backups \( -name 'rts-*.dump' -o -name 'keycloak-*.dump' \) -mtime +14 -delete
ssh backup@rts-backup "find /srv/rts-backups \( -name 'rts-*.dump' -o -name 'keycloak-*.dump' \) -mtime +14 -delete"
for file in "${FILES[@]}"; do
  echo "$(date -Is) backup ok: ${file} ($(du -h "${file}" | cut -f1))"
done
