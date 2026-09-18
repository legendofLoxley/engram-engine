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

## Live-browser bugs found and fixed

Verifying through a real connected browser (not just curl against the wire
format) surfaced issues no amount of HTTP-level testing would have caught.

**A `done` event with no `session` object crashes the browser.** WebUI's own
`messages.js` (`_finishDone`) unconditionally reads `d.session.messages` with
no null-check on `d.session` itself. A `done` payload without a `session` key
reproduced live as
`TypeError: Cannot read properties of undefined (reading 'messages')`,
leaving the composer stuck showing "processing" forever with no visible
error. `run_turn()` tracks a running `{role, content}` transcript per WebUI
session (`RunStore.append_turn_messages`) and includes
`session: {session_id, messages: transcript}` in every `done` event —
including a rejected turn's, and including error/rejection paths — see
below for why.

**The composer's model chip needed two separate fixes — one config-only, one
a small vendor patch.** hermes-webui's `boot.js` ships a static fallback
label ("GPT-5.4 Mini") shown before the real model hydrates. Before the first
message, this is fixed with *no vendor-source change at all*:
`HERMES_WEBUI_DEFAULT_MODEL=<model id>` on the WebUI container feeds
`get_effective_default_model()` → `/api/settings.default_model`, and
`boot.js`'s existing (unmodified) hydration code already creates a synthetic
`<option>` for an uncatalogued default and applies it — this dev instance has
no cloud provider configured at all (no API keys, no `config.yaml` — see
"Why a vendor patch was needed" below), so this is the *only* model shown,
which is the truth.

After a message, `run_turn()` reports `effective_model`/`effective_model_provider`
(derived from `trace.model.reasonProvider`/`reasonModel` in engram-engine's
own `/debug/converse` response — see `effective_model_fields()`), and
`routes.py._chat_start_response_from_run_start` already forwards these to the
browser. But `messages.js`'s handler for them called `_applyModelToDropdown`,
which only sets the chip when the value already matches a *registered
catalog* option and silently no-ops otherwise — exactly what an uncatalogued
runner-local model is. The same file already defines
`_ensureModelOptionInDropdown` for precisely this case (try
`_applyModelToDropdown` first, create a synthetic option and sync the chip
only if that fails) — see `vendor-patches/messages_js_model_chip_fallback.patch`,
which swaps in the existing, more-capable function at that one call site.
Verified live in both states: the chip shows the real model before any
message, and stays correct through a turn.

**Selecting a different model must not silently answer locally anyway, or
silently do nothing.** This dev instance has no cloud provider configured, so
the *only* way to pick something other than the local model is the
composer's free-text "Custom Model ID" field. Typing e.g.
`anthropic/claude-sonnet-4-6` there and sending reached the adapter as
`{"model": "anthropic/claude-sonnet-4-6"}` with **no `"provider"` key at
all** — a provider-only check (reject when `provider` is present and
mismatched) never sees this, and sailed the turn straight through to the
local Director, silently answering as if the request had been honored.
`run_turn()` now also compares the bare tail of `model` against
`LOCAL_MODEL_ID` (when configured) and rejects a mismatch through the same
`apperror`/`done` mechanism attachments/toolsets use — verified live: the
composer now shows a clear, visible error naming both what was requested and
what this instance actually serves, and the Director is never invoked.

Making that error *visible* needed one more fix: the browser's `apperror`
handler (`messages.js`) only renders the message into the transcript when the
event's `session_id` (or `session.session_id`) matches the browser's current
session — an `apperror` payload with neither present is accepted by the SSE
stream but never shown, leaving a blank assistant bubble (reproduced live —
the rejection reached the adapter and was logged correctly, but nothing
appeared on screen). Every `apperror` payload now includes `session_id`. This
also retroactively fixes the same silent-rendering gap in the
already-shipped attachments/toolsets rejection.

**Runner-local turns were never durably saved server-side, so a page reload
lost them.** `run_turn()`'s `session.messages` is read by the browser's live
JS state (`S.session`/`S.messages`) but nothing wrote it to WebUI's own
on-disk `Session` store (`get_session()`/`Session.save()`) — confirmed by
inspecting `sessions/<id>.json` directly: every runner-backed session had
`message_count: 0` even after a real, successfully-rendered conversation.
Reloading, or WebUI's own sidebar/history, would show nothing. Fixed with a
small vendor patch (`vendor-patches/routes_py_runner_session_persistence.patch`):
`_stream_runner_run_events` — the one function that already sees every event
from any runner-compliant adapter, not just this one — now persists a `done`
event's `session.messages` onto the real WebUI `Session` object and saves it,
mirroring exactly what the legacy (non-runner) chat path already does for
every turn. Verified live: a fresh conversation, reload, transcript intact;
a brand-new second conversation correctly recalling facts from the first via
the Director's graph (not WebUI's own history — see "Identity binding"),
confirming both persistence and cross-session recall work together. A
rejected turn (attachments/toolsets or an unsupported model) is included too
— reproduced live that omitting it made a rejected exchange vanish on
reload even though real Director turns persisted correctly.

**This adapter's own in-memory transcript is a cache, not the source of
truth — WebUI's on-disk session is.** `RunStore._message_history` is a plain
dict that resets on every adapter restart. Without reconciliation, a restart
mid-conversation would report only the post-restart turns in the next `done`
event, and the routes.py patch above would then *overwrite* WebUI's already
longer, correctly-persisted history with that truncated view — the opposite
of the bug it fixes. `run_turn()` seeds `RunStore` from WebUI's own
`sessions/<id>.json` (`load_persisted_webui_messages()`, via
`WEBUI_SESSIONS_DIR` — the same host path the WebUI container's state dir is
bind-mounted from) the first time it sees a session in this process's
lifetime, before appending the new turn. `WEBUI_SESSIONS_DIR` is optional;
unset, this behaves exactly as before (seeding is a best-effort
reconciliation, never a hard dependency — any read failure returns `[]`).

**A restart mid-delegation (this adapter's own, or engram-engine's) is a
separate, further concern from ordinary seeding above**: a run left at
`PENDING_HERMES_STATUS` has no `done` event yet, so none of the above saves
anything for it at all — not even the user's own message. `write_pending_marker`/
`reconcile_interrupted_assignments` close this gap; see `deploy/README.md`'s
"Interrupted-conversation recovery" section for the full design, the
backend-restart-vs-adapter-restart distinction (`fetch_engram_health`), and
live verification evidence.

## Why some fixes are vendor-source patches, not config

The pinned WebUI image ships **without PyYAML** (`import yaml` fails; `pip
list` is empty) — confirmed directly in the running container. Every
config.yaml-based path (`api/onboarding.py`'s self-hosted-provider setup,
`api/config.py`'s `set_hermes_default_model`) needs it and fails immediately.
So the "first use any supported provider/catalog configuration" options —
registering this instance as a `custom` self-hosted provider, or setting the
default model via config.yaml — are genuinely unavailable in this build, not
just undocumented. `HERMES_WEBUI_DEFAULT_MODEL` (an env var,
`api/config.py`'s `DEFAULT_MODEL`) doesn't touch config.yaml and works fine;
that's why only the *chip-after-a-message* and *session-persistence* fixes
needed an actual source patch.

`vendor-patches/` holds two small, isolated, reproducible patches:

- `routes_py_runner_session_persistence.patch` — see above.
- `messages_js_model_chip_fallback.patch` — see above.

`vendor-patches/apply.sh` regenerates the patched files from the pinned
image's actual source (extracted from a *running* container at the expected
digest — the image only populates `/app` from an internal `/apptoo` at
*container start*, so a `docker create`d-but-never-started container has no
source to copy yet) and applies both patches with `git apply`. The pinned
image itself is never modified or rebuilt: the patched files are bind-mounted
over `/apptoo` (not `/app`) at container start — the entrypoint
(`/hermeswebui_init.bash`) rsyncs `/apptoo/ -> /app/` with `--chown` on every
start to align ownership with the runtime UID, and a bind mount directly at
`/app/...` makes that chown fail across the mount boundary (confirmed: the
container exits 1 immediately). Mounting the *source* side instead means
rsync reads the patched content and writes a freshly, correctly owned copy
into `/app` through the same path every other vendor file takes.

## Identity binding

Every turn is bound to one fixed `ENGRAM_SYNTHETIC_USER_ID` — the caller
cannot choose or override it. This is what keeps Director-side graph recall
continuous across WebUI sessions regardless of which browser conversation
sent a given turn. A WebUI session's own `session_id` is mapped internally
to an engram-engine `sessionId` (for short-term turn continuity within one
conversation); a new WebUI conversation gets a fresh engram session but the
same bound identity, so graph recall still works.

## Running

As of the `deploy/` packaging pass, all three components (engram-engine,
this adapter, and the WebUI container) run under supervision — see
`deploy/README.md` for the full install/start/stop/update/rollback
procedure. `deploy/scripts/install.sh` is the one-command path.

The launch scripts themselves are now committed at `deploy/scripts/run-*.sh`
(they read secret file paths and embed this box's fixed deployment paths,
but the scripts themselves — unlike the secrets and persistent data they
reference — are reproducible from the repo, not host-only):

```bash
# 1. engram-engine's isolated dev backend must already be running
#    (reachable at ENGRAM_BASE_URL, default http://127.0.0.1:8082)
#    — under supervision: sudo systemctl start engram-dev.service

# 2. Regenerate the patched vendor files if they don't exist yet, or this
#    repo's patches changed:
webui-bridge/vendor-patches/apply.sh

# 3. Start the WebUI container: the pinned image + vendor patches overlaid
#    at /apptoo + HERMES_WEBUI_DEFAULT_MODEL:
deploy/scripts/run-hermes-webui-dev.sh

# 4. Start this adapter, bound to an address the WebUI container can reach:
deploy/scripts/run-runner-adapter.sh
#    — under supervision: sudo systemctl start hermes-runner-adapter.service
```

Adapter environment variables (all but `RUNNER_API_KEY`/`ENGRAM_DEBUG_TOKEN`
optional):

| Variable | Purpose |
|---|---|
| `RUNNER_HOST` / `RUNNER_PORT` | Bind address (Docker bridge gateway, see "Network path") |
| `RUNNER_API_KEY` | Must match `HERMES_WEBUI_RUNNER_API_KEY` on the container |
| `LOCAL_MODEL_PROVIDER` | The one provider id this instance serves (default `local`) — a WebUI-supplied `provider` that doesn't match this is rejected |
| `LOCAL_MODEL_ID` | The one model id this instance serves — a WebUI-supplied `model` whose tail doesn't match this is rejected. Unset skips this check (provider-only) |
| `WEBUI_SESSIONS_DIR` | Host path to WebUI's `sessions/` dir, for transcript seeding (see above) |

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
- Attachments/toolsets, and any model/provider other than the one this
  instance actually serves, are explicitly rejected (a clear, visible
  `apperror` before the Director is ever called, included in the persisted
  transcript) — not yet implemented.
- A rejected turn's `apperror` renders as a red error card live, but as a
  plain (non-error-styled) assistant bubble after a reload — the content is
  preserved, the styling isn't; WebUI's error-card rendering only exists in
  the live SSE path, not in its from-disk message rendering.
- Vendor WebUI's own reconnect-after-reload banner ("A response was in
  progress when you last left") and browser tab title (stays "Untitled")
  are unrelated pre-existing UI cosmetics, not something this slice or the
  Hermes delegation increment below touches.

## Director → Hermes delegation (first real increment)

One narrow, bounded slice, on top of everything above: a conversational
utterance naming or referring to one of a small closed set of approved
files makes the Director issue one correlated assignment to the *real*
installed Hermes runtime — not the production `hermes-halo` container, an
isolated **dev** instance of the exact same vendor image, run one-shot per
assignment (`docker run --rm`) against its own empty `HERMES_HOME` and a
read-only bind-mounted workspace directory. Hermes performs a real internal
`read_file` tool call (confirmed live, not assumed) via its actual
invocation interface — Agent Client Protocol (ACP), JSON-RPC over stdio,
already built into the vendor image (`venv/bin/hermes-acp`) — and the
attributed result is ingested via the existing `ActorEventIngestionService`,
independently of any Director turn. A *later* turn picks it up through the
already-existing `SurfacingReason.RecentActorEvidence` Horizon pool; the
Director (not Hermes) composes the reply the user sees.

- Isolated dev Hermes home/workspace: `/home/halo/development/hermes-dev/`
  (host-level, not committed — mirrors the `hermes-webui-dev/` pattern).
  The whole `workspace/` directory is bind-mounted read-only, but that mount
  is not the only enforcement of "only the approved file(s)": every
  assignment's target filename is independently resolved and validated in
  code by `HermesWorkspacePath.resolve` against that same root before
  anything is spawned — real path resolution (`Path.toRealPath()`), so `..`
  traversal and a symlink pointing outside the root are both rejected, not
  just string-matched. A missing or rejected target fails fast (no
  container ever spawned) with an honest `Failed` outcome, never a silent
  fabricated answer.
- Client/dispatcher: `HermesAcpClient`, `HermesDelegationDispatcher`,
  `HermesDelegationTrigger`, `HermesAssignment`, `HermesAssignmentKind`,
  `HermesWorkspacePath` under `cognitive/pipeline/hermes/`. Wired only into
  the debug/dev session pool (`CognitivePipelineFactory.create(db,
  enableHermesDelegation = true)` in `Application.kt`'s
  `DEBUG_CONVERSE_ENABLED` block) — the production `/cognitive/chat` path
  is untouched.
- **Two assignment kinds, two detection mechanisms, two approved documents**
  — still a small closed set, not a general delegation/orchestration
  framework or a filesystem tool: `HermesAssignmentKind.MarkerCheck` (the
  original fixture, reports one marker token verbatim — still an exact
  filename-and-verb regex, `HermesDelegationTrigger.detect`, unchanged) and
  `HermesAssignmentKind.DocumentSummary` (either of
  `HermesDelegationTrigger.APPROVED_DOCUMENTS`, summarized into
  Goal/Deadlines/Risks/Next actions). **Document-summary detection is no
  longer regex-based** — see `HermesDocumentIntentDirector`, a bounded
  Director decision (one structured tool-call turn against the same local
  model, modeled directly on `Interpreter`) that understands paraphrases
  ("give me the rundown"), resolves a bare contextual reference ("that
  project brief") using the session's own recent-turns window, and asks a
  clarifying question when genuinely ambiguous between the two approved
  documents — then reuses that answer on the very next turn. Every
  proposed decision is still independently re-validated in code before
  anything dispatches: the target/candidate filenames against
  `APPROVED_DOCUMENTS`, and — regardless of what the model itself
  self-reports — a deterministic proximity guard against the literal "do
  not summarize" case. Adding a third document/kind means adding a third
  named constant, deliberately never accepting an arbitrary user-supplied
  path. See the increment's own demonstration record for exact evidence and
  observed limitations (in particular: evidence can be committed, eligible,
  and selected into `Conditioners.horizonItems` and still not survive the
  Actor's own prompt-budget ladder for a conversation with enough competing
  history — an existing, documented characteristic of the Horizon/Actor
  budget system, not something this increment introduces or fixes; and a
  live-observed model tendency to connect two same-document facts across
  sections — e.g. attaching an earlier deadline to a later, textually
  separate action item — without labeling that connection as an inference,
  though never observed inventing a fact absent from the source or
  cross-contaminating between the two documents).

## Selected activity independent of conversation (first bounded increment)

A real Hermes document-summary assignment now produces **two** independently-arriving outputs: the
Director's conversational reply (unchanged, above), and a small "Activity" tab inside the WebUI's
existing collapsible workspace panel — alongside its own Files/Artifacts/Todos tabs, never a new
window, a copy of the reply, or another assistant message. It updates on its own poll cycle; no
further user message or extra Director model call is involved.

- **Durable-graph-backed, not completion-store-backed.** `HermesActivityFeed`
  (`cognitive/pipeline/hermes/HermesActivityFeed.kt`) reads directly from
  `HorizonGraphStore.listRecentActorEvents` — a new, restart-surviving query over the same `hermes`
  Source every assignment outcome is already ingested under — and **never** consults
  `HermesAssignmentCompletionStore` (in-memory, one process's lifetime only). Verified live: a real
  `sudo systemctl restart engram-dev.service` (which wipes that in-memory store completely) left the
  exact same activity items, with identical `eventId`s, available immediately afterward.
- **Honest labeling from durable metadata, not inference.** `HermesDelegationDispatcher` now writes
  `assignmentKind`/`targetFilename`/`executionOutcome` directly onto `ActorEventMetadata` (durable,
  `ASSERTS.kindMetadata`) for every outcome — Completed, Failed, *and* Cancelled alike — so
  `"failed"` and `"cancelled"` are distinguishable from durable state alone; `toolSucceeded=false`
  alone cannot tell them apart (both outcomes reported it false before this increment).
- **One event family, one bounded snapshot.** Scoped to `HermesAssignmentKind.DocumentSummary`
  outcomes only (a `MarkerCheck` event is silently excluded — different family). `GET
  /debug/hermes-activity` always returns the latest `HermesActivityFeed.MAX_ITEMS` (50) eligible
  items, newest first — a bounded snapshot, not a cursor, reconciled by `eventId` on the client side.
  An item aging out of that window is a display boundary only; its underlying graph evidence is
  never touched (verified: `findActorEventByEventId` still reports `Found` for an event dropped from
  a 55-item test's view).
- **Identity stays server-side.** `/api/hermes-activity` (browser-facing, vendor-patched) takes no
  identity parameter at all; the runner adapter answers using its own configured
  `ENGRAM_SYNTHETIC_USER_ID`, same as every other call through it. Cross-identity isolation itself is
  a Kotlin-unit-test claim (two distinct `userEmail`s) — this dev deployment only ever runs one fixed
  identity, so the browser never actually exercises a second one.
- **A failed refresh is not a confirmed-empty one.** `HorizonGraphStore.listRecentActorEvents`
  returns `ActorEventListResult.Failed` (never a bare empty list) on a genuine read failure; the
  route reports that as `503`; `fetch_hermes_activity`/the WebUI's own `_loadWorkspacePanelActivity()`
  treat any non-200 as "retain whatever was last successfully shown," never as "nothing here."
- **Independent of the reply path, by construction, not by convention.** The activity fetch is a
  separate HTTP call (`GET /v1/activity` → `GET /debug/hermes-activity`) with no code path shared
  with `/v1/runs*`; a failure there cannot block or affect a chat reply, and graph commitment (via
  `HermesDelegationDispatcher`, unchanged) happens independently of whether the WebUI ever
  successfully delivers that reply into the transcript at all.
- **Vendor extension mechanism**: three new small, isolated patches alongside the existing two —
  `runner_client_py_activity_fetch.patch` (one new `HttpRunnerClient.get_activity()` method),
  `routes_py_hermes_activity_route.patch` (one new `/api/hermes-activity` browser-facing route, at
  the same `/api/*` auth gate every other API route already sits behind), `index_html_activity_tab.patch`
  + `workspace_js_activity_tab.patch` (one new tab in the existing workspace panel, polling every 15s
  while that tab is actually visible — this bridge's existing "no live token streaming" scope
  boundary applies here too).
- **Known limits**: polling-based (15s), not push; the vendor's own pre-existing reconnect-banner
  cosmetic (documented above) is unrelated and untouched; a live cancellation demo landed the request
  after the (fast) assignment had already completed — cancelled-vs-failed-vs-completed labeling is
  verified through `HermesDelegationDispatcherTest`'s new real-dispatcher tests instead, per this
  increment's own "controlled tests where browser timing is impractical" allowance.
