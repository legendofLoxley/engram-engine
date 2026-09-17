#!/usr/bin/env bash
# Launches the isolated engram-engine dev backend (loopback-only, isolated
# data dir). Committed as the canonical source for this launch: a manual run
# and deploy/systemd/engram-dev.service's ExecStart= both run this exact
# script, so there is exactly one place that defines "how the dev backend
# starts."
#
# Secrets (the debug bearer token) and persistent data (data/dev/engram-db)
# stay outside version control by design — this script only ever reads a
# path to them, never a value, and that path is fixed for this box. Standing
# up a second box means adjusting the paths below, not restructuring this
# script.
#
# Manual stop (bare runs only, no systemd): kill -TERM $(cat /home/halo/development/engram-dev.pid)
# Under systemd: sudo systemctl stop engram-dev.service
set -euo pipefail

JAVA_BIN="/home/halo/.local/jdks/jdk-21.0.12.1+1/bin/java"
JAR="/home/halo/development/engram-engine/build/libs/engram-engine.jar"
DEBUG_TOKEN="$(tr -d '\n' < /home/halo/development/hermes-webui-dev/.engram-debug-token)"

exec env \
  PORT=8082 \
  HOST=127.0.0.1 \
  DB_PATH=/home/halo/development/engram-engine/data/dev/engram-db \
  DEBUG_CONVERSE_ENABLED=true \
  DEBUG_CONVERSE_TOKEN="$DEBUG_TOKEN" \
  SUPABASE_ISSUER=https://gtvosmsdzpfkoqtikswp.supabase.co/auth/v1 \
  LLM_PROVIDER=local \
  LOCAL_LLM_BASE_URL=http://127.0.0.1:8081/v1 \
  "$JAVA_BIN" \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/java.nio.channels.spi=ALL-UNNAMED \
    -Dpolyglot.engine.WarnInterpreterOnly=false \
    -Xmx1536m \
    -jar "$JAR"
