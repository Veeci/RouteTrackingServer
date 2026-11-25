#!/usr/bin/env bash
# Checks that the staging realm matches the development realm. The only allowed differences are the test users
# (staging has none) and the dashboard's URLs (staging reads RTS_ADMIN_WEB_URL when Keycloak imports it).
# CI runs this, so a role, client or token setting cannot change in one file only. Needs jq.
#   bash deploy/keycloak/check-realms.sh
set -euo pipefail

cd "$(dirname "$0")"

# Drops what may differ, and sorts keys so that formatting never counts.
normalize() {
  jq -S 'del(.users)
    | .clients |= map(del(.redirectUris) | del(.attributes["post.logout.redirect.uris"]))' "$1"
}

if ! diff <(normalize rts-realm.json) <(normalize staging/rts-realm.json); then
  echo "The two realms differ (lines with < are rts-realm.json, > are staging/rts-realm.json)." >&2
  echo "Change both files, or extend normalize() in $0 if the difference is meant." >&2
  exit 1
fi

# The staging file must hold no users and no localhost URL.
users="$(jq '.users // [] | length' staging/rts-realm.json)"
if [ "$users" -ne 0 ]; then
  echo "staging/rts-realm.json defines ${users} user(s). Create staff accounts in the admin console instead." >&2
  exit 1
fi
if grep -q localhost staging/rts-realm.json; then
  echo "staging/rts-realm.json names localhost. Use \${RTS_ADMIN_WEB_URL}." >&2
  exit 1
fi
echo "Realms agree"
