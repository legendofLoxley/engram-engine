#!/usr/bin/env bash
# Launches the isolated dev hermes-webui-dev container: the pinned vendor
# image, unmodified, with the runner-session-persistence, cancel-routing and
# model-chip vendor patches (engram-engine/webui-bridge/vendor-patches/)
# overlaid via read-only bind mounts, plus HERMES_WEBUI_DEFAULT_MODEL so the
# composer's model chip shows the real local model before the first message
# (no vendor patch needed for that part — see webui-bridge/README.md).
#
# The patched files are bind-mounted over /apptoo (the image's read-only
# source-of-truth the entrypoint rsyncs FROM), not /app — the entrypoint's
# /hermeswebui_init.bash rsyncs /apptoo/ -> /app/ with --chown on every
# container start to align ownership with the runtime UID, and a bind mount
# directly at /app/... makes that chown fail (cross-mount, exit 1). Mounting
# the source side instead means rsync reads our patched content and writes a
# freshly-owned copy into /app itself, exactly like every other vendor file —
# no fight with the entrypoint, no changes to it either.
#
# Regenerate the patched files first if they don't exist yet or the repo's
# patches changed:
#   engram-engine/webui-bridge/vendor-patches/apply.sh
#
# --restart unless-stopped matches the production hermes-webui/hermes-halo/
# signal-cli containers (/opt/halo-onboard/docker/docker-compose.yml) —
# Docker's own daemon now recovers this container after a crash or host
# reboot without anyone re-running this script. The `docker rm -f` guard
# below is for deliberate reconfiguration (new image, new patches, new env)
# via a manual re-run, not routine crash/reboot recovery.
#
# Stop for good (not just a restart): docker stop hermes-webui-dev && docker rm hermes-webui-dev
set -euo pipefail

DEV_DIR="/home/halo/development/hermes-webui-dev"
VENDOR_PATCH_DIR="$DEV_DIR/vendor-patch"

if [ ! -f "$VENDOR_PATCH_DIR/routes.py" ] || [ ! -f "$VENDOR_PATCH_DIR/messages.js" ]; then
  echo "missing patched vendor files — run engram-engine/webui-bridge/vendor-patches/apply.sh first" >&2
  exit 1
fi

RUNNER_API_KEY="$(tr -d '\n' < "$DEV_DIR/.runner-api-key")"
WEBUI_PASSWORD="$(tr -d '\n' < "$DEV_DIR/.webui-password")"

docker rm -f hermes-webui-dev >/dev/null 2>&1 || true

exec docker run -d \
  --name hermes-webui-dev \
  --restart unless-stopped \
  --add-host=host.docker.internal:host-gateway \
  -p 127.0.0.1:8788:8787 \
  -v "$DEV_DIR/home:/home/hermeswebui/.hermes:Z" \
  -v "$VENDOR_PATCH_DIR/routes.py:/apptoo/api/routes.py:ro" \
  -v "$VENDOR_PATCH_DIR/messages.js:/apptoo/static/messages.js:ro" \
  -e HERMES_WEBUI_PORT=8787 \
  -e HERMES_WEBUI_HOST=0.0.0.0 \
  -e HERMES_HOME=/home/hermeswebui/.hermes \
  -e HERMES_WEBUI_STATE_DIR=/home/hermeswebui/.hermes/webui \
  -e HERMES_WEBUI_SKIP_ONBOARDING=1 \
  -e HERMES_WEBUI_PASSWORD="$WEBUI_PASSWORD" \
  -e HERMES_WEBUI_RUNTIME_ADAPTER=runner-local \
  -e HERMES_WEBUI_RUNNER_BASE_URL=http://host.docker.internal:8091 \
  -e HERMES_WEBUI_RUNNER_API_KEY="$RUNNER_API_KEY" \
  -e HERMES_WEBUI_DEFAULT_MODEL="Qwen3.6-35B-A3B-UD-Q5_K_XL" \
  ghcr.io/nesquena/hermes-webui@sha256:48ba6ee4a837079955c00e997b751065cc0324ae6eaa0f2fec592c8f4b2a746e
