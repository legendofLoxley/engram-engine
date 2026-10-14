#!/usr/bin/env bash
# Runs one backup of the live graph by asking the running engine to run its own BACKUP DATABASE
# (POST /debug/graph-backup on loopback). Called daily by engram-graph-backup.timer; safe to run by hand.
#
# The engine does the work and writes into GRAPH_BACKUP_DIR (set on engram-dev.service); this script names no
# path and sends no body. It prints the engine's reply, which is metadata only (file name, size, entries,
# duration, counts). It reads the debug token from the same mode-600 file run-engram-dev.sh reads, passes it to
# curl through a file descriptor (never on a command line, never in the environment) and never prints it.
set -euo pipefail
set +x

PORT="${ENGRAM_PORT:-8082}"
TOKEN_FILE="${DEBUG_TOKEN_FILE:-/home/halo/development/hermes-webui-dev/.engram-debug-token}"

[ -r "$TOKEN_FILE" ] || { echo "graph-backup: the debug token file is not readable" >&2; exit 2; }

# The timer's catch-up run (a missed 05:20 fires once at the next boot) can reach this point before the engine has
# opened its listener: After=engram-dev.service only waits for the process to start. Wait a bounded time for /health
# on loopback, then ask. This never starts the engine; a stopped engine is still a failed run, now after the wait.
READY_WAIT_SECONDS="${READY_WAIT_SECONDS:-180}"
case "$READY_WAIT_SECONDS" in ''|*[!0-9]*) READY_WAIT_SECONDS=180 ;; esac
deadline=$((SECONDS + READY_WAIT_SECONDS))
until curl -fsS -m 5 -o /dev/null "http://127.0.0.1:${PORT}/health" 2>/dev/null; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "graph-backup: FAILED the engine did not answer on port ${PORT} after waiting ${READY_WAIT_SECONDS}s (this script does not start it)" >&2
    exit 1
  fi
  sleep 5
done

out="$(mktemp)"
trap 'rm -f "$out"' EXIT

code="$(curl -sS -m 900 -o "$out" -w '%{http_code}' -X POST \
  --config <(printf 'header = "Authorization: Bearer %s"\n' "$(tr -d '\n' < "$TOKEN_FILE")") \
  "http://127.0.0.1:${PORT}/debug/graph-backup" || true)"

if [ "$code" = "200" ]; then
  echo "graph-backup: ok $(cat "$out")"
else
  # An error reply is a class name only (never graph content), so it is safe to show.
  echo "graph-backup: FAILED http=${code:-none} $(head -c 300 "$out" 2>/dev/null)" >&2
  exit 1
fi
