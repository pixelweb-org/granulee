#!/bin/sh
# Registers the local test ClickHouse as a data source in a running Granulee and scans it.
# Does nothing when Granulee already has a source.
set -eu
API="${GRANULEE_API:-http://localhost:8080}"
CH_URL="${CH_URL:-http://localhost:18123}"
CH_PORT="${CH_URL##*:}"
CH_USER="${CH_USER:-granulee_test}"
CH_PASSWORD="${CH_PASSWORD:-granulee_test}"

SOURCES="$(curl -sf "$API/api/sources")" || { echo "Granulee does not answer on $API. Start it with 'make dev'." >&2; exit 1; }
if [ "$SOURCES" != "[]" ]; then
  echo "Granulee already has a data source; left as it is."
  exit 0
fi

ID="$(curl -sf "$API/api/sources" -H 'Content-Type: application/json' -d "{
  \"name\": \"local\", \"protocol\": \"http\", \"host\": \"localhost\", \"port\": $CH_PORT,
  \"username\": \"$CH_USER\", \"password\": \"$CH_PASSWORD\",
  \"includeDatabases\": \"\", \"excludeDatabases\": \"system, information_schema, INFORMATION_SCHEMA\",
  \"schedule\": \"every6h\", \"queryLogWindowDays\": 30, \"trialsEnabled\": true, \"scratchDatabase\": \"scratch\"
}" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
[ -n "$ID" ] || { echo "Could not add the local ClickHouse as a source." >&2; exit 1; }
curl -sf -X POST "$API/api/sources/$ID/scan" -H 'Content-Type: application/json' -d '{}' >/dev/null || true
echo "Added the local ClickHouse as source \"local\" and started a scan."
