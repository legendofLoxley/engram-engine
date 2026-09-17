#!/usr/bin/env bash
# Regenerates the two small patched vendor files this dev bridge bind-mounts
# over the pinned hermes-webui image at container-start time. The pulled
# image itself is never modified — only a read-only bind mount overlays
# these two files inside the running container. See ../README.md
# ("Model chip and cloud-provider rejection (vendor-source patches)") for why
# these exist.
#
# hermes-webui's entrypoint (/hermeswebui_init.bash) populates /app at
# container START, not at image-build time — a `docker create`d-but-never-
# started container has no /app/api/routes.py yet. So this copies the base
# files out of a *running* container from the pinned image (by default the
# dev instance itself), after verifying it really is running the expected
# digest, rather than out of a throwaway un-started one.
#
# Usage:
#   webui-bridge/vendor-patches/apply.sh [source_container] [output_dir]
#
# output_dir defaults to a `vendor-patch/` dir alongside the dev instance's
# own home dir (hermes-webui-dev/), a sibling of this repo checkout — NOT
# committed here. Only the small .patch files in this directory are.
set -euo pipefail

EXPECTED_DIGEST="sha256:48ba6ee4a837079955c00e997b751065cc0324ae6eaa0f2fec592c8f4b2a746e"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_CONTAINER="${1:-hermes-webui-dev}"
OUT_DIR="${2:-$SCRIPT_DIR/../../../hermes-webui-dev/vendor-patch}"

actual_digest="$(docker inspect --format '{{.Image}}' "$SRC_CONTAINER" | xargs docker inspect --format '{{index .RepoDigests 0}}' | grep -o 'sha256:[0-9a-f]*')"
if [ "$actual_digest" != "$EXPECTED_DIGEST" ]; then
  echo "refusing: $SRC_CONTAINER is not running the pinned digest" >&2
  echo "  expected: $EXPECTED_DIGEST" >&2
  echo "  actual:   $actual_digest" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
docker cp "$SRC_CONTAINER:/app/api/routes.py" "$OUT_DIR/routes.py"
docker cp "$SRC_CONTAINER:/app/static/messages.js" "$OUT_DIR/messages.js"

# git apply (not the `patch` utility, which isn't guaranteed present) — does
# not require OUT_DIR to be a git repo, just run from inside it so the
# patch's a/<file> b/<file> headers (-p1-stripped) resolve to plain
# ./<file> here.
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/routes_py_runner_session_persistence.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/routes_py_runner_cancel_routing.patch")
(cd "$OUT_DIR" && git apply -p1 "$SCRIPT_DIR/messages_js_model_chip_fallback.patch")

echo "Patched vendor files written to $OUT_DIR"
echo "Bind-mount them over the pinned (untouched) image's /apptoo — NOT"
echo "/app, whose ownership the entrypoint rsyncs from /apptoo on every"
echo "start and cannot chown across a direct /app/... bind mount:"
echo "  -v $OUT_DIR/routes.py:/apptoo/api/routes.py:ro"
echo "  -v $OUT_DIR/messages.js:/apptoo/static/messages.js:ro"
