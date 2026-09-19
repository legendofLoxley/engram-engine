#!/usr/bin/env bash
# Regenerates the patched vendor files this dev bridge bind-mounts over the
# pinned hermes-webui image at container-start time. The pulled image itself
# is never modified — only a read-only bind mount overlays these files inside
# the running container. See ../README.md ("Model chip and cloud-provider
# rejection (vendor-source patches)") for why these exist.
#
# hermes-webui's entrypoint (/hermeswebui_init.bash) populates /app at
# container START (rsynced from /apptoo, for correct ownership — see the
# bind-mount note at the bottom of this script), not at image-build time — a
# `docker create`d-but-never-started container has no /app/api/routes.py yet.
#
# Source: a throwaway container started fresh from the pinned image, with NO
# bind mounts — never the long-running dev instance. Once that instance has
# ever been started with these patches bind-mounted over /apptoo, its OWN
# /app is rsynced from the *already-patched* /apptoo on every subsequent
# restart, so reading "pristine" source from it re-applies already-applied
# hunks and fails (confirmed the hard way regenerating this exact set of
# patches a second time). A fresh, mount-free container's /apptoo is always
# the real, untouched image content, so its rsynced /app is too.
#
# Usage:
#   webui-bridge/vendor-patches/apply.sh [output_dir]
#
# output_dir defaults to a `vendor-patch/` dir alongside the dev instance's
# own home dir (hermes-webui-dev/), a sibling of this repo checkout — NOT
# committed here. Only the small .patch files in this directory are.
set -euo pipefail

EXPECTED_DIGEST="sha256:48ba6ee4a837079955c00e997b751065cc0324ae6eaa0f2fec592c8f4b2a746e"
PINNED_IMAGE="ghcr.io/nesquena/hermes-webui@${EXPECTED_DIGEST}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${1:-$SCRIPT_DIR/../../../hermes-webui-dev/vendor-patch}"
TMP_CONTAINER="hermes-webui-apply-sh-pristine-$$"

cleanup() { docker rm -f "$TMP_CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$TMP_CONTAINER" "$PINNED_IMAGE" >/dev/null
for _ in $(seq 1 20); do
  docker exec "$TMP_CONTAINER" sh -c "test -f /app/api/routes.py" 2>/dev/null && break
  sleep 1
done
docker exec "$TMP_CONTAINER" sh -c "test -f /app/api/routes.py" || {
  echo "refusing: throwaway container never populated /app within 20s" >&2
  exit 1
}

mkdir -p "$OUT_DIR"
docker cp "$TMP_CONTAINER:/app/api/routes.py" "$OUT_DIR/routes.py"
docker cp "$TMP_CONTAINER:/app/static/messages.js" "$OUT_DIR/messages.js"
docker cp "$TMP_CONTAINER:/app/api/runner_client.py" "$OUT_DIR/runner_client.py"
docker cp "$TMP_CONTAINER:/app/static/index.html" "$OUT_DIR/index.html"
docker cp "$TMP_CONTAINER:/app/static/workspace.js" "$OUT_DIR/workspace.js"

# git apply (not the `patch` utility, which isn't guaranteed present) — does
# not require OUT_DIR to be a git repo, just run from inside it so the
# patch's a/<file> b/<file> headers (-p1-stripped) resolve to plain
# ./<file> here.
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/routes_py_runner_session_persistence.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/routes_py_runner_cancel_routing.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/routes_py_hermes_activity_route.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/routes_py_hermes_activity_cancel_route.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/messages_js_model_chip_fallback.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/runner_client_py_activity_fetch.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/runner_client_py_hermes_activity_cancel.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/index_html_activity_tab.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/workspace_js_activity_tab.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/workspace_js_activity_running_cancel.patch")

echo "Patched vendor files written to $OUT_DIR"
echo "Bind-mount them over the pinned (untouched) image's /apptoo — NOT"
echo "/app, whose ownership the entrypoint rsyncs from /apptoo on every"
echo "start and cannot chown across a direct /app/... bind mount:"
echo "  -v $OUT_DIR/routes.py:/apptoo/api/routes.py:ro"
echo "  -v $OUT_DIR/messages.js:/apptoo/static/messages.js:ro"
echo "  -v $OUT_DIR/runner_client.py:/apptoo/api/runner_client.py:ro"
echo "  -v $OUT_DIR/index.html:/apptoo/static/index.html:ro"
echo "  -v $OUT_DIR/workspace.js:/apptoo/static/workspace.js:ro"
