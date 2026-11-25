#!/usr/bin/env bash
# Smoke test of the admin dashboard's nginx (phase 12f). Needs only bash and curl.
#   smoke-admin-web.sh <base-url> [expected-sha]          the whole path: nginx, then the app's admin port
#   smoke-admin-web.sh --static <base-url> [expected-sha]  nginx alone, for the CI image job (no app behind it)
# Examples:
#   bash deploy/staging/smoke-admin-web.sh http://127.0.0.1:8090
#   bash deploy/staging/smoke-admin-web.sh https://rts-staging.<tailnet>.ts.net:8443 "$SHA"
set -euo pipefail

STATIC=false
if [ "${1:-}" = "--static" ]; then
  STATIC=true
  shift
fi
BASE="${1:?usage: smoke-admin-web.sh [--static] <base-url> [expected-sha]}"
BASE="${BASE%/}"
EXPECTED_SHA="${2:-}"

failures=0
# check <description> <command...>: runs the command, then prints ok or FAIL. Every check runs, even after a failure.
check() {
  local description="$1"
  shift
  if "$@"; then
    echo "ok   ${description}"
  else
    echo "FAIL ${description}" >&2
    failures=$((failures + 1))
  fi
}
contains() { [[ "$1" == *"$2"* ]]; }
equals() { [ "$1" = "$2" ]; }

# The response headers of one GET, without the body. header() matches a name in any case.
head_of() { curl -sS -o /dev/null -D - --max-time 10 "$@" | tr -d '\r'; }
status_of() { curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$@" || true; }
header() { grep -i "^$1:" | head -1 | cut -d' ' -f2- || true; }

# 1. nginx itself.
check "/healthz answers 200" equals "$(status_of "$BASE/healthz")" 200

# 2. The app shell: the production Content Security Policy, and no-cache so a deploy shows at the next load.
headers="$(head_of "$BASE/" || true)"
csp="$(header content-security-policy <<< "$headers")"
check "/ sends the CSP with script-src 'self'" contains "$csp" "script-src 'self'"
check "/ sends the CSP with worker-src 'self'" contains "$csp" "worker-src 'self'"
check "/ sends X-Content-Type-Options: nosniff" equals "$(header x-content-type-options <<< "$headers")" nosniff
check "/ is Cache-Control: no-cache" equals "$(header cache-control <<< "$headers")" no-cache

# 3. A deep link gets index.html, so the router can handle it.
check "a deep link answers 200" equals "$(status_of "$BASE/drivers/smoke-test")" 200

# 4. version.json: the commit, and the worker file to check next.
version="$(curl -fsS --max-time 10 "$BASE/version.json" || true)"
sha="$(sed -nE 's/.*"sha":"([^"]*)".*/\1/p' <<< "$version")"
worker="$(sed -nE 's/.*"worker":"([^"]*)".*/\1/p' <<< "$version")"
if [ -n "$EXPECTED_SHA" ]; then
  check "version.json names commit ${EXPECTED_SHA} (got '${sha}')" equals "$sha" "$EXPECTED_SHA"
fi

# 5. MapLibre's module worker. The browser refuses it with any MIME type but JavaScript, and the map stays blank.
check "version.json names the worker file" contains "$worker" ".mjs"
if [ -n "$worker" ]; then
  headers="$(head_of "$BASE$worker" || true)"
  type="$(header content-type <<< "$headers")"
  check "${worker} is served as JavaScript (got '${type}')" contains "$type" javascript
  check "${worker} is cached as immutable" contains "$(header cache-control <<< "$headers")" immutable
fi

# 6. The proxy reaches the app's ADMIN port. Without a token the admin API answers 401 there. The public port would
#    answer 404 for the same path (phase 12a), so a 404 here means nginx points at the wrong port.
if [ "$STATIC" = false ]; then
  code="$(status_of "$BASE/api/v1/admin/overview")"
  check "/api reaches the admin port: 401 without a token (got ${code})" equals "$code" 401

  # The fleet socket: a WebSocket handshake without a ticket is refused with 401 before the upgrade.
  code="$(status_of "$BASE/ws/v1/admin/fleet" -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: c21va2UtdGVzdC1rZXktMTI=')"
  check "/ws reaches the admin port: 401 without a ticket (got ${code})" equals "$code" 401
fi

if [ "$failures" -gt 0 ]; then
  echo "${failures} check(s) failed against ${BASE}" >&2
  exit 1
fi
echo "Admin web smoke passed against ${BASE}"
