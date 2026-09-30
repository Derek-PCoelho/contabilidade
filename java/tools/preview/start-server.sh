#!/usr/bin/env bash
# Starts the central server (development profile) for the live preview.
set -euo pipefail
cd "$(dirname "$0")/../.."
source ../.tools/env.sh 2>/dev/null || true
LOG_DIR="${PREVIEW_LOG_DIR:-/tmp/folhas-preview}"; mkdir -p "$LOG_DIR"
export SPRING_PROFILES_ACTIVE=development
export FOLHAS_DB_URL="${FOLHAS_DB_URL:-jdbc:postgresql://127.0.0.1:55432/folhas_dev}"
export FOLHAS_DB_USER="${FOLHAS_DB_USER:-postgres}"
export PORT="${PORT:-8080}"
setsid nohup java -jar server/build/libs/folhas-server.jar ${FOLHAS_SERVER_ARGS:-} >"$LOG_DIR/server.log" 2>&1 < /dev/null &
for _ in $(seq 1 60); do
  curl -fs "http://127.0.0.1:$PORT/health/live" >/dev/null 2>&1 && { echo "Server up on :$PORT"; exit 0; }
  sleep 1
done
echo "Server did not start; see $LOG_DIR/server.log" >&2; tail -40 "$LOG_DIR/server.log" >&2; exit 1
