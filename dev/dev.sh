#!/bin/sh
# Runs the backend and the frontend dev server together. Ctrl+C stops both.
# Started by `make dev`. It starts nothing else: point Granulee at a ClickHouse under Data sources,
# or use `make clickhouse seed` and `make source` for a local one with sample data.
# On an empty data directory the backend prints the one-time password of the user "admin" here.
set -eu
cd "$(dirname "$0")/.."

API="${GRANULEE_API:-http://localhost:8080}"

if curl -sf "$API/api/health" >/dev/null 2>&1; then
  echo "Something already answers on $API. Stop it first, or run 'make frontend' to use it." >&2
  exit 1
fi

(cd backend && exec ./gradlew bootRun --console=plain -q) &
BACKEND=$!

stop() {
  trap - EXIT INT TERM
  kill "$BACKEND" 2>/dev/null || true
  # bootRun forks the application; make sure the port is free again.
  PIDS="$(lsof -tiTCP:8080 -sTCP:LISTEN 2>/dev/null || true)"
  [ -z "$PIDS" ] || kill $PIDS 2>/dev/null || true
  wait "$BACKEND" 2>/dev/null || true
}
trap stop EXIT INT TERM

(
  n=0
  until curl -sf "$API/api/health" >/dev/null 2>&1; do
    n=$((n + 1))
    [ "$n" -le 180 ] || { echo "The backend did not come up on $API within 3 minutes." >&2; exit 0; }
    sleep 1
  done
  echo
  echo "  Granulee is up: http://localhost:5173"
  echo
) &

cd frontend && npm run dev
