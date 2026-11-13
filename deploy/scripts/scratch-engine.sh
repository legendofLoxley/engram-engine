#!/usr/bin/env bash
# Runs a SCRATCH engine on a restore of a graph backup, so a goal cycle (or anything else) can be tried without touching a live file.
#
#   scratch-engine.sh [backup.zip]        # default: the newest auto/graph-*.zip
#
# It runs in the foreground until it gets SIGTERM or SIGINT, then removes the scratch directory. What it isolates:
#   - the graph: a restore of the backup in a new mode-700 directory (the live data directory is never opened);
#   - the user's home: -Duser.home points at the scratch directory, because the JVM ignores $HOME. Everything in the engine that defaults to the home directory
#     (the Director tick's output, the owner token, the goal runner's kill file) therefore lands in the scratch directory;
#   - each of those is also set explicitly below, so the isolation does not depend on that default;
#   - the ports and tokens: a port that is not a live one (default 8095), and fresh random debug and owner tokens, written mode 600 to the scratch directory.
# The environment is cleared (`env -i`), so nothing leaks in from the caller; the variables passed on are listed below. No Notion, no Hermes.
# The scratch directory's path, port and token file names go to $SCRATCH_INFO (default: stdout, one `key=value` per line). No token is printed.
#
# Passed through when set: GOAL_RUNNER_* (for example GOAL_RUNNER_REASONING=off), LOCAL_LLM_BASE_URL (default the box's 8081). The engine reaches the box's live model
# servers for plan and step calls; their /slots are checked before a cycle starts, so a conversation in progress makes the runner wait.
set -euo pipefail
set +x

JAR="${ENGRAM_JAR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/build/libs/engram-engine.jar}"
JAVA="${JAVA_BIN:-/home/halo/.local/jdks/jdk-21.0.12.1+1/bin/java}"
PORT="${SCRATCH_PORT:-8095}"
ROOT="${SCRATCH_ROOT:-/home/halo/development/.goalc-scratch}"
BACKUPS="${BACKUP_DIR:-/home/halo/backups/engram-graph/auto}"

case "$PORT" in 8080|8081|8082|8083|8084|8091|8788) echo "refusing: port $PORT belongs to a live service" >&2; exit 2;; esac
[ -f "$JAR" ] || { echo "no engine jar at $JAR" >&2; exit 2; }
ZIP="${1:-$(ls -t "$BACKUPS"/graph-*.zip | head -1)}"
[ -f "$ZIP" ] || { echo "no backup zip: $ZIP" >&2; exit 2; }
case "$(readlink -f "$ZIP")" in /home/halo/development/engram-engine/data/*) echo "refusing: the source is inside the live data directory" >&2; exit 2;; esac
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then echo "refusing: something already listens on $PORT" >&2; exit 2; fi

umask 077
mkdir -p "$ROOT"; chmod 700 "$ROOT"
SCR="$(mktemp -d "$ROOT/run-XXXXXX")"
PID=""
cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then kill -TERM "$PID" 2>/dev/null || true; for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done; kill -KILL "$PID" 2>/dev/null || true; fi
  rm -rf "$SCR"
}
trap cleanup EXIT
trap 'exit 0' TERM INT

python3 - "$ZIP" "$SCR/engram-db" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    assert z.testzip() is None, "damaged zip entry"
    z.extractall(sys.argv[2])
PY
head -c 24 /dev/urandom | base64 | tr -d '+/=\n' > "$SCR/debug-token"
INFO="${SCRATCH_INFO:-/dev/stdout}"
{ echo "dir=$SCR"; echo "port=$PORT"; echo "backup=$(basename "$ZIP")"; echo "debug_token_file=$SCR/debug-token"; echo "owner_token_file=$SCR/owner-token"; } > "$INFO"

PASS=()
for v in $(compgen -e | grep '^GOAL_RUNNER_' || true); do PASS+=("$v=${!v}"); done
env -i PATH=/usr/bin:/bin HOME="$SCR" PORT="$PORT" HOST=127.0.0.1 DB_PATH="$SCR/engram-db" \
  DEBUG_CONVERSE_ENABLED=true DEBUG_CONVERSE_TOKEN="$(cat "$SCR/debug-token")" \
  SUPABASE_ISSUER=https://gtvosmsdzpfkoqtikswp.supabase.co/auth/v1 SCHEMA_ENFORCE_CONSTRAINTS=true \
  LLM_PROVIDER=local LOCAL_LLM_BASE_URL="${LOCAL_LLM_BASE_URL:-http://127.0.0.1:8081/v1}" NOTION_CYCLES_POLLING=off \
  OWNER_IDENTITY=owner-jacob OWNER_TOKEN_FILE="$SCR/owner-token" DIRECTOR_TICK_IDENTITY=owner-jacob DIRECTOR_TICK_DIR="$SCR/tick" \
  GOAL_RUNNER_KILL_FILE="${GOAL_RUNNER_KILL_FILE:-$SCR/never-exists}" "${PASS[@]}" \
  "$JAVA" -Duser.home="$SCR" --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/java.nio.channels.spi=ALL-UNNAMED -Dpolyglot.engine.WarnInterpreterOnly=false -Xmx768m \
    -jar "$JAR" > "$SCR/engine.log" 2>&1 &
PID=$!
wait "$PID" || true
