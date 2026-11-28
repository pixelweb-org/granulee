#!/bin/sh
# Registers the local test ClickHouse as a data source of one Granulee user and scans it.
# Does nothing when that user already has a source.
#
# Granulee needs a sign-in: GRANULEE_USER and GRANULEE_PASSWORD name an account that has already chosen its own
# password (sign in once in the browser with the one-time password the first start printed).
set -eu
API="${GRANULEE_API:-http://localhost:8080}"
CH_URL="${CH_URL:-http://localhost:18123}"
CH_PORT="${CH_URL##*:}"
CH_USER="${CH_USER:-granulee_test}"
CH_PASSWORD="${CH_PASSWORD:-granulee_test}"
: "${GRANULEE_USER:?Set GRANULEE_USER and GRANULEE_PASSWORD to a Granulee account, e.g. GRANULEE_USER=admin GRANULEE_PASSWORD=… make source}"
: "${GRANULEE_PASSWORD:?Set GRANULEE_PASSWORD to the password of $GRANULEE_USER}"

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

# The first answer hands out the CSRF token as a cookie; every request that changes something sends it back.
curl -s -o /dev/null -c "$JAR" "$API/api/auth/me" || { echo "Granulee does not answer on $API. Start it with 'make dev'." >&2; exit 1; }
TOKEN="$(awk '$6 == "XSRF-TOKEN" {print $7}' "$JAR")"
call() { curl -sf -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' "$@"; }

# The password travels on standard input, not on a command line other processes can read.
printf '{"username":"%s","password":"%s"}' "$GRANULEE_USER" "$GRANULEE_PASSWORD" | call "$API/api/auth/login" -d @- >/dev/null \
  || { echo "Could not sign in to Granulee as $GRANULEE_USER." >&2; exit 1; }

SOURCES="$(call "$API/api/sources")" || { echo "$GRANULEE_USER cannot list data sources. Has the account chosen its own password yet?" >&2; exit 1; }
if [ "$SOURCES" != "[]" ]; then
  echo "$GRANULEE_USER already has a data source; left as it is."
else
  ID="$(call "$API/api/sources" -d "{
    \"name\": \"local\", \"protocol\": \"http\", \"host\": \"localhost\", \"port\": $CH_PORT,
    \"username\": \"$CH_USER\", \"password\": \"$CH_PASSWORD\",
    \"includeDatabases\": \"\", \"excludeDatabases\": \"system, information_schema, INFORMATION_SCHEMA\",
    \"schedule\": \"every6h\", \"queryLogWindowDays\": 30, \"trialsEnabled\": true, \"scratchDatabase\": \"scratch\"
  }" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
  [ -n "$ID" ] || { echo "Could not add the local ClickHouse as a source." >&2; exit 1; }
  call -X POST "$API/api/sources/$ID/scan" -d '{}' >/dev/null || true
  echo "Added the local ClickHouse as source \"local\" of $GRANULEE_USER and started a scan."
fi
call -X POST "$API/api/auth/logout" -o /dev/null || true
