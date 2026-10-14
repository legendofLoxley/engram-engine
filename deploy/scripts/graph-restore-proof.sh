#!/usr/bin/env bash
# Proves a backup restores: restores it into a scratch directory, opens it with the engine on a DIFFERENT port,
# compares vertex and edge counts per type with the live graph, confirms the owner-jacob user is present in both
# the live and the restored graph, then tears the scratch engine and its directory down. It never opens, writes or locks the live graph, never touches
# 8081, and the scratch engine has no Notion, no model and no network beyond its own loopback port.
#
#   graph-restore-proof.sh <backup.zip | cold-copy-directory>
#
# A zip is a BACKUP DATABASE output (auto/graph-*.zip). A directory is a cold copy of engram-db; it is COPIED into
# the scratch directory first, so the original is never opened.
#
# Environment (all optional): ENGRAM_JAR, JAVA_BIN, ENGRAM_PORT (the live engine, default 8082), SCRATCH_PORT
# (default 8095), SCRATCH_ROOT, LIVE_COUNTS_FILE (compare with counts saved earlier instead of asking the live
# engine now, for a cold copy taken while the engine was stopped), DEBUG_TOKEN_FILE.
# Output is counts, sizes, durations and paths only. Exit 0: counts match and owner-jacob is present in both graphs.
set -euo pipefail
set +x

SRC="${1:?usage: graph-restore-proof.sh <backup.zip | cold-copy-directory>}"
JAR="${ENGRAM_JAR:-/home/halo/development/engram-engine/build/libs/engram-engine.jar}"
JAVA="${JAVA_BIN:-/home/halo/.local/jdks/jdk-21.0.12.1+1/bin/java}"
LIVE_PORT="${ENGRAM_PORT:-8082}"
SCRATCH_PORT="${SCRATCH_PORT:-8095}"
SCRATCH_ROOT="${SCRATCH_ROOT:-/home/halo/backups/engram-graph/.restore-proof}"
TOKEN_FILE="${DEBUG_TOKEN_FILE:-/home/halo/development/hermes-webui-dev/.engram-debug-token}"
LIVE_DATA="/home/halo/development/engram-engine/data"

case "$SCRATCH_PORT" in 8080|8081|8082|8083|8091|8788|"$LIVE_PORT") echo "refusing: port $SCRATCH_PORT belongs to a live service" >&2; exit 2;; esac
[ -e "$SRC" ] || { echo "no such backup: $SRC" >&2; exit 2; }
SRC="$(readlink -f "$SRC")"
case "$SRC" in "$LIVE_DATA"|"$LIVE_DATA"/*) echo "refusing: the source is inside the live data directory" >&2; exit 2;; esac
[ -f "$JAR" ] || { echo "no engine jar at $JAR" >&2; exit 2; }
if (exec 3<>"/dev/tcp/127.0.0.1/$SCRATCH_PORT") 2>/dev/null; then echo "refusing: something already listens on $SCRATCH_PORT" >&2; exit 2; fi

umask 077
mkdir -p "$SCRATCH_ROOT"; chmod 700 "$SCRATCH_ROOT"
SCRATCH="$(mktemp -d "$SCRATCH_ROOT/run-XXXXXX")"
PID=""
teardown() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then kill -TERM "$PID" 2>/dev/null || true; for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done; kill -KILL "$PID" 2>/dev/null || true; fi
  rm -rf "$SCRATCH"
  echo "teardown: scratch engine stopped, scratch directory removed"
}
trap teardown EXIT

t0=$(date +%s)
echo "== restore"
if [ -d "$SRC" ]; then
  cp -a "$SRC" "$SCRATCH/engram-db"
else
  python3 - "$SRC" "$SCRATCH/engram-db" <<'PY'
import sys, zipfile
src, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as z:
    bad = z.testzip()
    if bad is not None: sys.exit("damaged zip entry")
    z.extractall(dest)   # zipfile refuses entries that escape dest
    print("zip entries:", len(z.namelist()))
PY
fi
echo "restored files: $(find "$SCRATCH/engram-db" -type f | wc -l), size: $(du -sb "$SCRATCH/engram-db" | cut -f1) bytes"

echo "== open with the engine on port $SCRATCH_PORT"
SCRATCH_TOKEN="$(head -c 24 /dev/urandom | base64 | tr -d '+/=\n')"
env -i PATH=/usr/bin:/bin HOME="$SCRATCH" PORT="$SCRATCH_PORT" HOST=127.0.0.1 DB_PATH="$SCRATCH/engram-db" \
  DEBUG_CONVERSE_ENABLED=true DEBUG_CONVERSE_TOKEN="$SCRATCH_TOKEN" \
  SUPABASE_ISSUER=https://gtvosmsdzpfkoqtikswp.supabase.co/auth/v1 \
  "$JAVA" --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/java.nio.channels.spi=ALL-UNNAMED -Dpolyglot.engine.WarnInterpreterOnly=false -Xmx768m \
    -jar "$JAR" > "$SCRATCH/engine.log" 2>&1 &
PID=$!
up=""
for _ in $(seq 1 120); do
  if curl -s -m 2 -o /dev/null "http://127.0.0.1:$SCRATCH_PORT/health"; then up=yes; break; fi
  kill -0 "$PID" 2>/dev/null || { echo "the scratch engine exited early (see the scratch log; nothing is copied out)"; exit 1; }
  sleep 1
done
[ -n "$up" ] || { echo "the scratch engine did not come up in 120 s"; exit 1; }
echo "scratch engine up after $(( $(date +%s) - t0 )) s"

get_counts() { # port, token-or-file-flag -> JSON on stdout
  curl -sS -m 60 --config <(printf 'header = "Authorization: Bearer %s"\n' "$2") "http://127.0.0.1:$1/debug/graph-counts"
}
get_counts "$SCRATCH_PORT" "$SCRATCH_TOKEN" > "$SCRATCH/restored.json"
if [ -n "${LIVE_COUNTS_FILE:-}" ]; then
  cp "$LIVE_COUNTS_FILE" "$SCRATCH/live.json"; echo "live counts: from $LIVE_COUNTS_FILE"
else
  [ -r "$TOKEN_FILE" ] || { echo "the debug token file is not readable" >&2; exit 2; }
  get_counts "$LIVE_PORT" "$(tr -d '\n' < "$TOKEN_FILE")" > "$SCRATCH/live.json"; echo "live counts: from the live engine on port $LIVE_PORT, read now"
fi

echo "== compare (vertices and edges per type: live / restored)"
python3 - "$SCRATCH/live.json" "$SCRATCH/restored.json" <<'PY'
import json, sys
live, rest = (json.load(open(p)) for p in sys.argv[1:3])
ok = True
for kind in ("vertices", "edges"):
    names = sorted(set(live[kind]) | set(rest[kind]))
    print(f"-- {kind}: {len(names)} types, live total {sum(live[kind].values())}, restored total {sum(rest[kind].values())}")
    for n in names:
        a, b = live[kind].get(n), rest[kind].get(n)
        flag = "" if a == b else "   <-- DIFFERS"
        if a != b: ok = False
        if a or b or flag: print(f"   {n:<28} {a!s:>8} / {b!s:<8}{flag}")
print("owner-jacob present: live", live["ownerJacobPresent"], "restored", rest["ownerJacobPresent"])
# The one thing that cannot be rebuilt has to be in BOTH graphs. A restored graph that has it proves nothing if the live
# graph does not (the proof then shows no backup of it), and a live graph that has it while the restored one does not
# is a backup that lost it.
if not live["ownerJacobPresent"]:
    ok = False
    print("   <-- the live graph does not have owner-jacob, so this proof cannot show that a backup holds it")
if not rest["ownerJacobPresent"]:
    ok = False
    print("   <-- the restored graph does not have owner-jacob")
print("RESULT:", "counts match and owner-jacob is present in both" if ok else "MISMATCH")
sys.exit(0 if ok else 1)
PY
