# webui-bridge

Connects the vendor Hermes WebUI's own built-in chat composer and conversation
display (`ghcr.io/nesquena/hermes-webui`, pinned) to engram-engine's real
Director pipeline — no new chat interface, no Hermes agent in the message
path, no bearer token reaching the browser.

## How it connects

hermes-webui has a real, live-wired external "runner" HTTP contract
(`api/runner_client.py` / `api/runtime_adapter.py`, selected via
`HERMES_WEBUI_RUNTIME_ADAPTER=runner-local` + `HERMES_WEBUI_RUNNER_BASE_URL`).
An earlier pass at this integration dismissed that seam based on a stale
module docstring calling it "in progress" without checking whether it was
actually wired to the live routes. It is: `api/routes.py`'s shared
`_start_run()` helper (used by both `/api/chat/start` and the background
wakeup path) and `_stream_runner_run_events()` (the real browser-facing SSE
handler at `/api/chat/stream`) both call into it when the mode is enabled —
confirmed by reading those call sites, not by trusting a comment, and then by
sending real turns through the actual native composer/session/SSE endpoints
and watching the reply render.

`runner_adapter.py` implements that contract:

- `POST /v1/runs` — runs one Director turn synchronously (`/debug/converse`)
  and returns immediately with the completed result.
- `GET /v1/runs/{id}/events` — returns a `token` event (the full reply text)
  then a `done` event. WebUI's own polling loop synthesizes the browser-facing
  `stream_end` SSE event itself once `GET /v1/runs/{id}` reports
  `status: completed` — no need to emit it here.
- `GET /v1/runs/{id}` — run status.
- `POST /v1/runs/{id}/cancel` / `.../approval` / `.../messages` /
  `.../clarifications/.../respond` / `/v1/sessions/{id}/goal` — all report a
  clear "not supported in this development slice" result rather than
  pretending to honor them.

`engram_client.py` holds the pure, reusable logic for calling
engram-engine's `/debug/converse` (payload shaping, identity binding, the
upstream HTTP call) — kept separate from the HTTP-server/runner-contract code
so it can be unit-tested without a server, and reused by any future adapter.

## Network path (same-origin through the one forwarded port)

The browser only ever talks to the WebUI's own origin — the single port an
operator forwards. `HttpRunnerClient` (vendor code, already in the pinned
image) makes the actual `/v1/runs*` calls **server-side**, from inside the
WebUI container to this adapter. Nothing about this adapter's address, port,
or credentials is ever sent to or reachable from the browser.

The adapter binds to the Docker bridge gateway address (e.g. `172.17.0.1`),
not `127.0.0.1` — a plain loopback bind is not reachable from inside a
container via `host.docker.internal` (that hostname resolves to the bridge
gateway, a different interface; this is the same reason the box's own
`llama-server` binds `0.0.0.0` rather than `127.0.0.1`). Binding to the bridge
gateway specifically (rather than `0.0.0.0`) keeps the adapter reachable from
this host's containers and processes without exposing it on any actual
external network interface.

## Authentication (enforced per request, not just by network placement)

Every `/v1/runs*` request must carry `Authorization: Bearer <RUNNER_API_KEY>`,
matching `HERMES_WEBUI_RUNNER_API_KEY` configured on the WebUI container.
`HttpRunnerClient` sends this on every call, and only WebUI's own
already-login-gated `/api/chat/*` route handlers ever construct one — so a
request reaching this adapter with the correct key is, transitively, one that
passed the WebUI session's own authentication. A request without the header,
or with the wrong key, is rejected with `401` regardless of where it
originates — verified directly against the adapter, bypassing WebUI entirely,
not just inferred from CORS or the browser's own restrictions.

engram-engine's own debug bearer token is a *second*, independent secret held
only in this adapter process's environment — the WebUI container never sees
it, and neither does the browser.

## Identity binding

Every turn is bound to one fixed `ENGRAM_SYNTHETIC_USER_ID` — the caller
cannot choose or override it. This is what keeps Director-side graph recall
continuous across WebUI sessions regardless of which browser conversation
sent a given turn. A WebUI session's own `session_id` is mapped internally
to an engram-engine `sessionId` (for short-term turn continuity within one
conversation); a new WebUI conversation gets a fresh engram session but the
same bound identity, so graph recall still works.

## Running

```bash
# 1. engram-engine's isolated dev backend must already be running
#    (reachable at ENGRAM_BASE_URL, default http://127.0.0.1:8082)

# 2. Start this adapter, bound to an address the WebUI container can reach
ENGRAM_DEBUG_TOKEN="$(cat /path/to/debug-token-file)" \
RUNNER_API_KEY="$(cat /path/to/runner-key-file)" \
RUNNER_HOST=172.17.0.1 RUNNER_PORT=8091 \
python3 webui-bridge/runner_adapter.py

# 3. Point the WebUI container at it
docker run ... \
  --add-host=host.docker.internal:host-gateway \
  -e HERMES_WEBUI_RUNTIME_ADAPTER=runner-local \
  -e HERMES_WEBUI_RUNNER_BASE_URL=http://host.docker.internal:8091 \
  -e HERMES_WEBUI_RUNNER_API_KEY="$(cat /path/to/runner-key-file)" \
  ...
```

Use the WebUI's own UI (or `/api/session/new` + `/api/chat/start` +
`/api/chat/stream`) exactly as normal — no new page, no new controls.

## Tests

```bash
cd webui-bridge && python3 -m unittest test_engram_client test_runner_adapter -v
```

No third-party dependencies — stdlib `unittest`/`http.server` only.
`test_runner_adapter.py` includes real HTTP-level checks that an
unauthenticated or wrong-key request is rejected (`401`) at the adapter
itself, not merely kept out by network placement.

## Known scope boundaries (this slice)

- No live token-by-token streaming — the full reply arrives as one `token`
  event once the Director call completes (typically several seconds).
- No cancel/interrupt, approval, clarify, mid-run message queueing, or goal
  actions — each reports a clear "not supported in this development slice"
  result if the UI's controls for them are used, rather than silently
  dropping the request.
- Attachments/toolsets sent by the composer are currently ignored rather than
  rejected — not yet addressed in this slice.
