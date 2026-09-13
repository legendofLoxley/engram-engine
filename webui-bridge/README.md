# webui-bridge

A minimal, standalone integration that lets a separate development instance of
the vendor Hermes WebUI (`ghcr.io/nesquena/hermes-webui`) send text turns to
engram-engine's real Director pipeline and display the reply — without Hermes
ever seeing the message, and without the browser ever holding engram-engine's
debug bearer token.

This is the smallest integration that lets the Director own the conversation
for the built pinned WebUI version: the app's fully-supported
[Extensions](https://github.com/nesquena/hermes-webui/blob/master/docs/EXTENSIONS.md)
mechanism (injected same-origin JS/CSS, `HERMES_WEBUI_EXTENSION_DIR`) plus a
tiny loopback sidecar. The vendor's newer `HERMES_WEBUI_RUNTIME_ADAPTER=runner-local`
/ `HERMES_WEBUI_RUNNER_BASE_URL` seam was evaluated and not used here: its own
module docstring documents it as an in-progress seam, not a settled contract,
and reusing it would require reverse-engineering an undocumented run/event
schema to render correctly in the built-in Chat view. The Extensions mechanism
is stable, fully documented, and lets this slice avoid touching Hermes's
conversation internals at all.

## Pieces

- `sidecar.py` — a stdlib-only (no dependencies) HTTP server. Holds
  `ENGRAM_DEBUG_TOKEN` and forwards `POST /converse` to engram-engine's
  `/debug/converse`, injecting the `Authorization` header itself. Every
  request is bound to one fixed `ENGRAM_SYNTHETIC_USER_ID` regardless of what
  the browser sends — the browser cannot choose or spoof the Director-side
  identity. Also serves `GET /health`.
- `extension/` — a WebUI extension: `director-chat.js` injects a floating
  "Director (dev)" panel (message list + composer), `director-chat.css`
  styles it, `manifest.json` declares both plus the sidecar for WebUI's
  Settings → Extensions diagnostics.

## Running

1. Start engram-engine's isolated dev backend first (see the project root for
   its own launch instructions) — the bridge assumes it's reachable at
   `ENGRAM_BASE_URL` (default `http://127.0.0.1:8082`).
2. Start the bridge:
   ```bash
   ENGRAM_DEBUG_TOKEN="$(cat /path/to/debug-token-file)" \
   python3 webui-bridge/sidecar.py
   ```
3. Point a WebUI instance's extension config at `webui-bridge/extension/`:
   ```bash
   HERMES_WEBUI_EXTENSION_DIR=/path/to/webui-bridge/extension \
   HERMES_WEBUI_EXTENSION_MANIFEST=manifest.json \
   ...
   ```

## Tests

```bash
cd webui-bridge && python3 -m unittest test_sidecar -v
```

No third-party dependencies — stdlib `unittest`/`http.server` only.

## Known scope boundaries (this slice)

- No streaming — one request, one full-text reply.
- No cancel/interrupt — a submitted turn always runs to completion.
- Attach/Tools/Approve controls are visibly present but disabled, not hidden.
- The sidecar trusts any request reaching its loopback port (matching the
  vendor's own documented trust model for local sidecars — see
  "Trusted local sidecars" in EXTENSIONS.md); it does not independently
  re-verify the browser's WebUI login. Network exposure is bounded by binding
  both the sidecar and the WebUI instance to `127.0.0.1` only.
