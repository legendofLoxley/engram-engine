#!/usr/bin/env bash
# Launches engram-engine/webui-bridge/runner_adapter.py, bound to the Docker
# bridge gateway address so the hermes-webui-dev container can reach it via
# host.docker.internal (see webui-bridge/README.md, "Network path"). Committed
# as the canonical source: deploy/systemd/hermes-runner-adapter.service's
# ExecStart= runs this exact script.
#
# Manual stop (bare runs only, no systemd): kill $(cat /home/halo/development/hermes-webui-dev/runner-adapter.pid)
# Under systemd: sudo systemctl stop hermes-runner-adapter.service
set -euo pipefail

DEV_DIR="/home/halo/development/hermes-webui-dev"
BRIDGE_DIR="/home/halo/development/engram-engine/webui-bridge"

exec env \
  ENGRAM_BASE_URL=http://127.0.0.1:8082 \
  ENGRAM_DEBUG_TOKEN="$(tr -d '\n' < "$DEV_DIR/.engram-debug-token")" \
  ENGRAM_SYNTHETIC_USER_ID=webui-dev \
  RUNNER_HOST=172.17.0.1 \
  RUNNER_PORT=8091 \
  RUNNER_API_KEY="$(tr -d '\n' < "$DEV_DIR/.runner-api-key")" \
  LOCAL_MODEL_PROVIDER=local \
  LOCAL_MODEL_ID="Qwen3.6-35B-A3B-UD-Q5_K_XL" \
  WEBUI_SESSIONS_DIR="$DEV_DIR/home/webui/sessions" \
  python3 -u "$BRIDGE_DIR/runner_adapter.py"
