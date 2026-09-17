#!/usr/bin/env bash
# Installs/updates systemd supervision for the two native dev components and
# (re)launches the supervised hermes-webui-dev container. Idempotent — safe
# to re-run after a `git pull` to pick up unit-file or script changes: it
# always stops any stray manual instance first, then re-applies the
# committed units/container config.
#
# Requires passwordless sudo for the running user (already granted to `halo`
# on this box) for exactly three commands: copying the two unit files into
# /etc/systemd/system, `daemon-reload`, and `enable --now`. No other
# privileged action is performed.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_DIR="$REPO_DIR/deploy"

echo "==> stopping any manually-launched instances first"
"$DEPLOY_DIR/scripts/stop-manual.sh"

echo "==> installing systemd units"
sudo cp "$DEPLOY_DIR/systemd/engram-dev.service" "$DEPLOY_DIR/systemd/hermes-runner-adapter.service" /etc/systemd/system/
sudo systemctl daemon-reload

echo "==> enabling + starting engram-dev.service"
sudo systemctl enable --now engram-dev.service

echo "==> enabling + starting hermes-runner-adapter.service"
sudo systemctl enable --now hermes-runner-adapter.service

echo "==> (re)launching the supervised hermes-webui-dev container"
"$DEPLOY_DIR/scripts/run-hermes-webui-dev.sh"

echo "==> done. Check status with:"
echo "    systemctl status engram-dev.service hermes-runner-adapter.service"
echo "    docker inspect hermes-webui-dev --format '{{.HostConfig.RestartPolicy.Name}}'"
