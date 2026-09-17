#!/usr/bin/env python3
"""engram-runner-adapter: implements hermes-webui's external "runner" HTTP
contract (api/runner_client.py's HttpRunnerClient — POST /v1/runs,
GET /v1/runs/{id}/events, GET /v1/runs/{id}, POST /v1/runs/{id}/cancel),
translating it to a single synchronous call to engram-engine's real Director
pipeline (/debug/converse).

This lets the WebUI's OWN built-in chat composer and conversation display
send turns to the Director and render the reply — no new chat interface, no
Hermes agent involved. Point a WebUI instance at this adapter with:

  HERMES_WEBUI_RUNTIME_ADAPTER=runner-local
  HERMES_WEBUI_RUNNER_BASE_URL=http://host.docker.internal:<this port>
  HERMES_WEBUI_RUNNER_API_KEY=<shared secret, also set as RUNNER_API_KEY here>

Network path: browser -> WebUI's own origin (same-origin, the one forwarded
port) -> WebUI's Python backend -> this adapter (server-to-server, never
touched by the browser). The browser never sees this adapter's address or
either bearer token.

Per-request authentication: every request must carry
``Authorization: Bearer <RUNNER_API_KEY>``, matching HERMES_WEBUI_RUNNER_API_KEY.
HttpRunnerClient sends this on every call, and only WebUI's own
already-authenticated /api/chat/* route handlers ever construct an
HttpRunnerClient — so a request reaching this adapter with the correct key
is, transitively, one that passed the WebUI session's own login check.
Unauthenticated/mismatched requests are rejected outright (401), not merely
kept out by network topology or a client-side identity label.

engram-engine's own debug bearer token lives only in this process's
environment — the WebUI container never sees it, and neither does the browser.

A turn that makes the Director issue a real Hermes assignment (see
PipelineTrace.hermesDelegation) is the one exception to "single synchronous
call": that run is deliberately left open (PENDING_HERMES_STATUS, no `done`
event yet) while a background thread (_deliver_hermes_completion) polls
engram-engine's separate, explicit /debug/hermes-assignment completion channel
and appends the real result once Hermes reports back. WebUI's own runner
event-stream polling loop (api/routes.py's _stream_runner_run_events) already
re-polls GET /v1/runs/{id}/events on its own schedule until a run's status
becomes terminal — this is what delivers the completion automatically into the
originating conversation, with no further user message and no new
vendor-source patch.

This adapter never decides whether Hermes's findings are trustworthy or how to
word them — that decision (accept/withhold) is made entirely on the
engram-engine side by HermesCompletionDirector before this adapter ever sees
the completion. /debug/hermes-assignment's `text` field is that already-composed
reply; _deliver_hermes_completion only transports it into the conversation.

Interrupted-assignment recovery: RunStore is in-memory (see its own class doc)
and engram-engine's own HermesActiveAssignmentRegistry/HermesAssignmentCompletionStore
are documented as in-memory-only too — so a delegation-triggering turn whose
run never reaches `done` before either process restarts leaves its WebUI
conversation permanently waiting, with no record anywhere. write_pending_marker
gives this one thing a durable footprint: a small JSON file, one per
outstanding assignment, written just before its delivery thread starts and
removed once that thread (or, after a restart, reconcile_interrupted_assignments)
resolves it. This is deliberately not a general durable job queue — it never
causes Hermes to be re-dispatched, only lets THIS adapter process, on its next
startup, honestly finish the conversation that a previous instance of itself
left hanging. See reconcile_interrupted_assignments's own doc for how a backend
restart (confirmed loss, detected via /health's uptimeSeconds) is told apart
from a runner-adapter-only restart (the backend may still be working on it, or
may have already finished).
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from engram_client import (
    ConfigError,
    UpstreamError,
    build_engram_payload,
    fetch_engram_health,
    fetch_hermes_assignment_completion,
    forward_to_engram,
    load_engram_config,
    request_hermes_cancellation,
)


class RunnerConfigError(RuntimeError):
    pass


def load_runner_config(environ: dict[str, str] | None = None) -> dict[str, Any]:
    source = os.environ if environ is None else environ
    api_key = str(source.get("RUNNER_API_KEY") or "").strip()
    if not api_key:
        raise RunnerConfigError("RUNNER_API_KEY is required and must not be empty")
    config = dict(load_engram_config(source))
    config["runner_host"] = str(source.get("RUNNER_HOST") or "127.0.0.1")
    config["runner_port"] = int(source.get("RUNNER_PORT") or "8091")
    config["runner_api_key"] = api_key
    # The one provider id this instance actually serves. Compared
    # case-insensitively against the WebUI-supplied "provider" field so an
    # explicit cloud-model pick can be rejected instead of silently answered
    # by the local backend anyway (see unsupported_provider_message).
    config["local_model_provider"] = str(source.get("LOCAL_MODEL_PROVIDER") or "local").strip().lower()
    # The one model id this instance actually serves (bare id, no "provider/"
    # prefix — e.g. "Qwen3.6-35B-A3B-UD-Q5_K_XL"). This dev WebUI instance has
    # no cloud provider configured at all (no API keys), so its model picker
    # has no catalog of cloud models to choose from either — the ONLY way to
    # pick something else is the composer's free-text "Custom Model ID"
    # field, which sends whatever the user typed as "model" with NO
    # "provider" field at all (that split only happens server-side in
    # WebUI's legacy, non-runner chat path). Checking "provider" alone would
    # never catch this — a typed "anthropic/claude-sonnet-4-6" sails through
    # with model_provider empty. Optional: unset means skip this check
    # (provider-only, as before).
    config["local_model_id"] = str(source.get("LOCAL_MODEL_ID") or "").strip() or None
    # Optional: WebUI's own on-disk session store (the host path backing the
    # container's bind-mounted HERMES_WEBUI_STATE_DIR), used only to seed this
    # adapter's in-memory transcript from whatever WebUI already durably saved
    # — see load_persisted_webui_messages. Absent by default; a missing/unset
    # dir just means no seeding, never a startup failure.
    config["webui_sessions_dir"] = str(source.get("WEBUI_SESSIONS_DIR") or "").strip() or None
    # Where write_pending_marker/reconcile_interrupted_assignments keep their durable
    # markers. An explicit HERMES_PENDING_DIR always wins; otherwise, if a sessions dir
    # is configured, default to a sibling directory next to it (reuses the same
    # bind-mounted host path the sessions dir already lives under — no new mount
    # needed). Absent entirely when neither is set: recovery across a runner-adapter
    # restart is then simply inert, same optionality convention as webui_sessions_dir
    # itself — never a startup failure.
    pending_dir_override = str(source.get("HERMES_PENDING_DIR") or "").strip()
    if pending_dir_override:
        config["hermes_pending_dir"] = pending_dir_override
    elif config["webui_sessions_dir"]:
        sessions_dir = config["webui_sessions_dir"]
        config["hermes_pending_dir"] = os.path.join(os.path.dirname(os.path.normpath(sessions_dir)), "hermes-pending")
    else:
        config["hermes_pending_dir"] = None
    return config


def is_authorized(auth_header: str | None, expected_key: str) -> bool:
    """True only for an exact 'Bearer <expected_key>' match. No fallback, no
    "empty key means open" case — load_runner_config already refuses to start
    with a blank key.
    """
    if not auth_header:
        return False
    prefix = "Bearer "
    if not auth_header.startswith(prefix):
        return False
    return auth_header[len(prefix):] == expected_key


class RunStore:
    """In-memory run records. Deliberately not persisted: this adapter owns no
    long-lived run/session state beyond what's needed to answer the polling
    contract for runs it just completed synchronously.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._runs: dict[str, dict[str, Any]] = {}
        self._session_map: dict[str, str] = {}  # webui session_id -> engram sessionId
        self._message_history: dict[str, list[dict[str, str]]] = {}  # webui session_id -> [{role, content}]

    def engram_session_for(self, webui_session_id: str | None) -> str | None:
        if not webui_session_id:
            return None
        with self._lock:
            return self._session_map.get(webui_session_id)

    def remember_engram_session(self, webui_session_id: str | None, engram_session_id: str | None) -> None:
        if not webui_session_id or not engram_session_id:
            return
        with self._lock:
            self._session_map[webui_session_id] = engram_session_id

    def append_turn_messages(
        self,
        webui_session_id: str,
        user_message: str,
        assistant_reply: str,
        *,
        seed: list[dict[str, str]] | None = None,
    ) -> list[dict[str, str]]:
        """Appends this turn's user+assistant messages to the session's running
        transcript and returns the full transcript so far (a copy).

        WebUI's own frontend (messages.js's done-event handler) unconditionally
        reads `d.session.messages` when a run completes successfully — it has no
        independent memory of a runner-backed conversation's history, since (per
        the vendor's own agent-api-contract.md audit) that history normally lives
        in WebUI's own SessionDB, which a runner integration is not supposed to
        open directly. This is the minimal stand-in: enough of a `messages` list
        for that one read site to render correctly, not a SessionDB replacement.

        `seed` (typically WebUI's own already-persisted transcript for this
        session — see load_persisted_webui_messages) is only used the first
        time this session is seen by THIS process. It exists so a restarted
        adapter's empty in-memory history does not become the transcript of
        record and overwrite a longer, already-durable conversation the next
        time a `done` event is sent — this in-memory dict must never be the
        sole source of a session's saved history across a restart.
        """
        with self._lock:
            if webui_session_id not in self._message_history and seed:
                self._message_history[webui_session_id] = list(seed)
            history = self._message_history.setdefault(webui_session_id, [])
            history.append({"role": "user", "content": user_message})
            history.append({"role": "assistant", "content": assistant_reply})
            return list(history)

    def create(
        self,
        *,
        webui_session_id: str,
        events: list[dict[str, Any]],
        status: str,
        effective_model: str | None = None,
        effective_model_provider: str | None = None,
        assignment_id: str | None = None,
    ) -> str:
        run_id = uuid.uuid4().hex
        with self._lock:
            self._runs[run_id] = {
                "session_id": webui_session_id,
                "events": events,
                "status": status,
                "created_at": time.time(),
                "effective_model": effective_model,
                "effective_model_provider": effective_model_provider,
                # Only set for a delegation-triggering run — the one thing the cancel handler
                # needs to translate "cancel this run" into "cancel this Hermes assignment".
                "assignment_id": assignment_id,
            }
        return run_id

    def get(self, run_id: str) -> dict[str, Any] | None:
        with self._lock:
            record = self._runs.get(run_id)
            return dict(record) if record else None

    def events_since(self, run_id: str, cursor: int) -> tuple[list[dict[str, Any]], int] | None:
        with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                return None
            events = record["events"]
            return events[cursor:], len(events)

    def append_event(self, run_id: str, event: dict[str, Any]) -> bool:
        """Appends one more event to an already-created run's event list — the
        mechanism behind delivering a Hermes assignment's completion into a run
        that was deliberately left open (status "running", no `done` yet) at
        creation time. WebUI's own runner-polling loop (api/routes.py's
        _stream_runner_run_events) re-polls GET /v1/runs/{id}/events on a fixed
        cadence for exactly this reason: it will pick up whatever this appends,
        without the browser needing to do anything.
        """
        with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                return False
            record["events"].append(event)
            return True

    def set_status(self, run_id: str, status: str) -> bool:
        with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                return False
            record["status"] = status
            return True

    def replace_message_at(self, webui_session_id: str, index: int, role: str, content: str) -> list[dict[str, str]] | None:
        """Replaces one specific, already-persisted message in a session's running
        transcript by INDEX (stable across later appends — a later turn only ever
        appends past this index, never shifts it) rather than "the most recent
        message with this role", which a later, unrelated turn could make wrong.
        Returns None (no-op) if the index is out of range or its role no longer
        matches — e.g. the in-memory history was reset by a restart — so a caller
        can fall back to appending instead of silently corrupting an unrelated
        message.
        """
        with self._lock:
            history = self._message_history.get(webui_session_id)
            if not history or not (0 <= index < len(history)) or history[index].get("role") != role:
                return None
            history[index] = {"role": role, "content": content}
            return list(history)

    def append_assistant_message(self, webui_session_id: str, content: str) -> list[dict[str, str]]:
        with self._lock:
            history = self._message_history.setdefault(webui_session_id, [])
            history.append({"role": "assistant", "content": content})
            return list(history)


def _parse_cursor(raw: str | None) -> int:
    try:
        return max(0, int(raw)) if raw not in (None, "") else 0
    except (TypeError, ValueError):
        return 0


#: Terminal-state strings _stream_runner_run_events() (WebUI's SSE polling
#: loop, api/routes.py) actually recognizes as terminal. Using anything else
#: here (e.g. the "errored" this used to send) leaves the loop polling
#: forever — it never sees a reason to stop, so the browser never receives
#: stream_end and the composer hangs instead of showing the error.
TERMINAL_ERROR_STATUS = "error"
TERMINAL_COMPLETED_STATUS = "completed"

#: A genuinely non-terminal status. _stream_runner_run_events() only stops polling
#: once GET /v1/runs/{id} reports a status in its own recognized terminal set
#: ("completed"/"complete"/"failed"/"error"/"cancelled"/"canceled") — anything else,
#: including this, keeps its polling loop alive (heartbeating every
#: _SSE_HEARTBEAT_INTERVAL_SECONDS=5s server-side) until a later append_event() call
#: adds new events and set_status() flips this to a real terminal value. This is the
#: whole mechanism behind delivering a Hermes assignment's completion automatically,
#: with no new user message and no new vendor-source patch: the vendor's own
#: reconnect/polling loop already does the waiting.
PENDING_HERMES_STATUS = "running"

#: How long a background poller waits for a real Hermes assignment to complete
#: before giving up and honestly reporting a timeout, and how often it checks.
#: Comfortably above HermesAcpClient's own default 180s subprocess timeout
#: (Kotlin side) plus container-startup/graph-commit overhead observed in practice
#: (worst case seen: well under a minute).
HERMES_COMPLETION_MAX_WAIT_SECONDS = 240.0
HERMES_COMPLETION_POLL_INTERVAL_SECONDS = 2.0


def unsupported_input_message(attachments: list[Any], toolsets: list[Any]) -> str:
    parts = []
    if attachments:
        n = len(attachments)
        parts.append(f"{n} attachment{'s' if n != 1 else ''}")
    if toolsets:
        names = ", ".join(str(t) for t in toolsets)
        parts.append(f"tool selection ({names})")
    joined = " and ".join(parts)
    pronoun = "it" if len(parts) == 1 else "them"
    return (
        f"This development slice's Director backend does not support {joined} yet. "
        f"Remove {pronoun} and resend your message as plain text."
    )


def unsupported_provider_message(requested_provider: str, local_provider: str, requested_model: str | None = None) -> str:
    if requested_model and requested_provider:
        picked = f"'{requested_model}' (provider '{requested_provider}')"
    elif requested_model:
        picked = f"'{requested_model}'"
    else:
        picked = f"provider '{requested_provider}'"
    return (
        f"This development instance only serves its local Director-routed model "
        f"(provider '{local_provider}'). {picked} is not available here — no "
        f"cloud model was invoked. Pick the local model and resend."
    )


def load_persisted_webui_messages(sessions_dir: str | None, webui_session_id: str) -> list[dict[str, str]]:
    """Best-effort read of WebUI's own on-disk transcript for one session
    (HERMES_WEBUI_STATE_DIR's sessions/<id>.json, reachable from this host
    process via the same bind-mounted dir the WebUI container writes into).

    Used only to seed RunStore's in-memory transcript on first reference to a
    session, so this adapter's own memory is never the sole source of a
    session's saved history — see RunStore.append_turn_messages. Returns []
    on any miss (no configured dir, no file yet, unexpected shape) rather than
    raising: seeding is a best-effort reconciliation, not a hard dependency.
    """
    if not sessions_dir or not webui_session_id:
        return []
    path = os.path.join(sessions_dir, f"{webui_session_id}.json")
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    if not isinstance(data, dict):
        return []
    messages = data.get("messages")
    if not isinstance(messages, list):
        return []
    result = []
    for m in messages:
        if isinstance(m, dict) and isinstance(m.get("role"), str) and isinstance(m.get("content"), str):
            result.append({"role": m["role"], "content": m["content"]})
    return result


def persist_webui_session_messages(sessions_dir: str | None, webui_session_id: str, messages: list[dict[str, str]]) -> bool:
    """Best-effort DIRECT write of WebUI's own on-disk transcript for one session —
    the write-side counterpart to load_persisted_webui_messages, used only by
    _deliver_hermes_completion and reconcile_interrupted_assignments.

    This duplicates (rather than depends on) what the vendor's own
    _persist_runner_done_session patch does when a `done` SSE event streams past a
    connected browser: that patch fires only while something is actively polling
    this run's events, which reconciliation can never assume after a restart (the
    browser has no way to know a new run_id exists) and which even ordinary
    operation cannot always assume (a closed browser tab never receives the `done`
    event either). Writing directly here means an outstanding turn's resolution
    reaches disk regardless of whether anyone is watching it live.

    Preserves every other field already on the session file untouched — only
    `messages`, `message_count`, and `updated_at` are updated, mirroring exactly
    what the vendor patch's own `s.messages = ...; s.save()` does. Returns False
    (never raises) on a missing directory/file/malformed JSON, or if the write
    itself fails — same best-effort philosophy as load_persisted_webui_messages;
    a lost recovery notice must never crash this process.

    Known accepted race: this is a second, independent writer of a file the
    hermes-webui container itself also writes (via the vendor patch above). Both
    do a plain read-modify-write with no cross-process lock, so a write from one
    landing in the middle of the other's read-modify-write cycle could lose that
    other write. In practice this only matters in the narrow window right after a
    restart, for a session with no other concurrent activity — accepted for this
    bounded dev-only recovery feature rather than adding real file locking.
    """
    if not sessions_dir or not webui_session_id:
        return False
    path = os.path.join(sessions_dir, f"{webui_session_id}.json")
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return False
    if not isinstance(data, dict):
        return False
    data["messages"] = list(messages)
    data["message_count"] = len(messages)
    data["updated_at"] = time.time()
    tmp_path = f"{path}.tmp-{os.getpid()}"
    try:
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(data, f)
        os.replace(tmp_path, path)
    except OSError:
        try:
            os.remove(tmp_path)
        except OSError:
            pass
        return False
    return True


def _pending_marker_path(pending_dir: str, assignment_id: str) -> str:
    return os.path.join(pending_dir, f"{assignment_id}.json")


def write_pending_marker(
    pending_dir: str | None,
    *,
    assignment_id: str,
    webui_session_id: str,
    user_message: str,
    ack_text: str,
    created_at: float,
) -> None:
    """Best-effort durable record of one outstanding Hermes delegation, written just
    before its background delivery thread starts (see run_turn) — see this module's
    own doc for why this exists at all. Removed by whichever of
    _deliver_hermes_completion or reconcile_interrupted_assignments's own
    _finalize_reconciled_marker eventually resolves this exact assignment_id.

    No-op if pending_dir is None (feature inert — same optionality convention as
    webui_sessions_dir) or if the write itself fails for any reason: a lost marker
    only means a future restart cannot reconcile this ONE delegation — it must
    never block, slow, or fail the delegation it is merely recording.
    """
    if not pending_dir:
        return
    try:
        os.makedirs(pending_dir, exist_ok=True)
        path = _pending_marker_path(pending_dir, assignment_id)
        tmp_path = f"{path}.tmp-{os.getpid()}"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump({
                "assignment_id": assignment_id,
                "webui_session_id": webui_session_id,
                "user_message": user_message,
                "ack_text": ack_text,
                "created_at": created_at,
            }, f)
        os.replace(tmp_path, path)
    except OSError:
        pass


def _read_pending_markers(pending_dir: str) -> list[dict[str, Any]]:
    """Best-effort listing of every leftover marker at adapter startup. A malformed
    or unreadable entry is dropped (removed) rather than retried forever — it can
    never be reconciled correctly anyway, and leaving it would jam every future
    startup on the same broken file.
    """
    result: list[dict[str, Any]] = []
    try:
        names = os.listdir(pending_dir)
    except OSError:
        return result
    for name in names:
        if not name.endswith(".json") or name.endswith(".tmp"):
            continue
        assignment_id = name[: -len(".json")]
        path = os.path.join(pending_dir, name)
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            _remove_pending_marker(pending_dir, assignment_id)
            continue
        if not isinstance(data, dict):
            _remove_pending_marker(pending_dir, assignment_id)
            continue
        result.append(data)
    return result


def _remove_pending_marker(pending_dir: str | None, assignment_id: str | None) -> None:
    if not pending_dir or not assignment_id:
        return
    try:
        os.remove(_pending_marker_path(pending_dir, assignment_id))
    except OSError:
        pass


def _reject_turn(
    config: dict[str, Any],
    store: RunStore,
    *,
    webui_session_id: str,
    user_message: str,
    rejection_type: str,
    rejection_message: str,
) -> str:
    """Records a rejected turn (attachments/toolsets, or an unsupported
    model/provider pick) as a completed-but-errored run, WITHOUT calling
    engram-engine.

    Tracks the rejection in the same running transcript success replies use
    (RunStore.append_turn_messages) and includes it in the `done` event's
    `session.messages`, exactly like a successful turn — otherwise a reload
    silently drops the rejected turn from the visible conversation (the user
    typed something and saw an error, then a refresh erases both), which is
    the same class of gap the success-path session-persistence fix closes.
    Reproduced live: reloading after a rejected cloud-model turn made it
    vanish before this.

    session_id is required on the apperror payload itself for a different
    reason: messages.js's apperror handler only renders the message into the
    visible transcript when the event's session_id/session.session_id matches
    the browser's current session — omit it and the error is accepted by the
    stream but shown nowhere (also reproduced live, as a blank assistant
    bubble).
    """
    seed = load_persisted_webui_messages(config.get("webui_sessions_dir"), webui_session_id)
    transcript = store.append_turn_messages(webui_session_id, user_message, rejection_message, seed=seed)
    events = [
        {"event": "apperror", "seq": 1, "payload": {
            "type": rejection_type,
            "message": rejection_message,
            "session_id": webui_session_id,
        }},
        {"event": "done", "seq": 2, "payload": {
            "status": TERMINAL_ERROR_STATUS,
            "session": {"session_id": webui_session_id, "messages": transcript},
        }},
    ]
    return store.create(webui_session_id=webui_session_id, events=events, status=TERMINAL_ERROR_STATUS)


def run_turn(
    config: dict[str, Any],
    store: RunStore,
    *,
    webui_session_id: str,
    message: str,
    attachments: list[Any] | None = None,
    toolsets: list[Any] | None = None,
    model: str | None = None,
    model_provider: str | None = None,
) -> str:
    """Executes one Director turn synchronously and records it as a completed run.

    Returns the new run_id. Never raises for an upstream failure — that is
    recorded as an 'apperror' run event instead, so the browser renders a
    visible chat error rather than the composer hanging or a bare 500.

    Attachments/toolsets and an unsupported model/provider pick are rejected
    here, before any call to engram-engine — the Director is never invoked
    for a turn it can't fully honor, and the rejection reaches the user
    through the same apperror/done event pair (and the same chat-bubble
    rendering) any other run failure uses, not a new mechanism.
    """
    attachments = attachments or []
    toolsets = toolsets or []
    if attachments or toolsets:
        return _reject_turn(
            config, store, webui_session_id=webui_session_id, user_message=message,
            rejection_type="unsupported_input",
            rejection_message=unsupported_input_message(attachments, toolsets),
        )

    requested_provider = str(model_provider or "").strip()
    # Blank/missing provider is the common case (a brand-new session, or a
    # session that has never had its model explicitly changed) and is always
    # allowed — this only rejects a REAL, non-matching provider value, so it
    # can't reject on account of a field WebUI simply didn't send.
    provider_mismatch = bool(requested_provider) and requested_provider.lower() != config["local_model_provider"]

    requested_model = str(model or "").strip()
    local_model_id = config.get("local_model_id")
    # A bare model id compared against the tail of whatever was sent, so a
    # "provider/model" or "@provider:model" typed into the composer's
    # free-text Custom Model ID field (this instance's only way to pick
    # something other than the local model, since no cloud provider is
    # configured — see load_runner_config) still compares against just the
    # model part.
    model_mismatch = bool(requested_model and local_model_id and requested_model.rsplit("/", 1)[-1].lower() != local_model_id.lower())

    if provider_mismatch or model_mismatch:
        return _reject_turn(
            config, store, webui_session_id=webui_session_id, user_message=message,
            rejection_type="unsupported_model",
            rejection_message=unsupported_provider_message(requested_provider, config["local_model_provider"], model),
        )

    engram_session_id = store.engram_session_for(webui_session_id)
    try:
        payload = build_engram_payload(message, config["synthetic_user_id"], session_id=engram_session_id)
        result = forward_to_engram(config["engram_base_url"], config["engram_debug_token"], payload)
        store.remember_engram_session(webui_session_id, result.get("sessionId"))
        reply_text = str(result.get("reply") or "")
        seed = load_persisted_webui_messages(config.get("webui_sessions_dir"), webui_session_id)
        transcript = store.append_turn_messages(webui_session_id, message, reply_text, seed=seed)
        model, provider = effective_model_fields(result)

        # A turn that just issued a real Hermes assignment (PipelineTrace.hermesDelegation,
        # set by CognitivePipeline only when hermesDelegationDispatcher fired) gets this
        # run left deliberately open — see PENDING_HERMES_STATUS's doc — instead of the
        # normal immediate "done". The acknowledgment reply_text is still shown right away
        # via the token event below; the real completion is delivered later, into this
        # SAME run, by _deliver_hermes_completion running in a background thread.
        hermes_delegation = (result.get("trace") or {}).get("hermesDelegation")
        assignment_id = hermes_delegation.get("assignmentId") if isinstance(hermes_delegation, dict) else None

        if assignment_id:
            ack_index = len(transcript) - 1  # the assistant entry append_turn_messages just added
            events = [{"event": "token", "seq": 1, "payload": {"text": reply_text}}]
            run_id = store.create(
                webui_session_id=webui_session_id, events=events, status=PENDING_HERMES_STATUS,
                effective_model=model, effective_model_provider=provider, assignment_id=assignment_id,
            )
            write_pending_marker(
                config.get("hermes_pending_dir"),
                assignment_id=assignment_id,
                webui_session_id=webui_session_id,
                user_message=message,
                ack_text=reply_text,
                created_at=time.time(),
            )
            thread = threading.Thread(
                target=_deliver_hermes_completion,
                args=(config, store, run_id, webui_session_id, assignment_id, reply_text, ack_index),
                daemon=True,
            )
            thread.start()
            return run_id

        events = [
            {"event": "token", "seq": 1, "payload": {"text": reply_text}},
            # WebUI's own done-event handler (messages.js _finishDone) unconditionally
            # reads d.session.messages with no null-check on d.session itself — a
            # done event without a `session` object throws
            # "TypeError: Cannot read properties of undefined (reading 'messages')"
            # and leaves the browser's stream stuck showing "processing" forever
            # (reproduced live; see webui-bridge/README.md). `session` here is a
            # minimal stand-in sized to satisfy that one read site, not a
            # SessionDB replacement.
            {"event": "done", "seq": 2, "payload": {
                "status": TERMINAL_COMPLETED_STATUS,
                "session": {"session_id": webui_session_id, "messages": transcript},
            }},
        ]
        return store.create(
            webui_session_id=webui_session_id, events=events, status=TERMINAL_COMPLETED_STATUS,
            effective_model=model, effective_model_provider=provider,
        )
    except UpstreamError as e:
        detail = e.body.decode("utf-8", errors="replace")[:500]
        events = [
            {"event": "apperror", "seq": 1, "payload": {
                "type": "error",
                "message": f"engram-engine rejected the turn (HTTP {e.status}): {detail}",
                "session_id": webui_session_id,
            }},
            {"event": "done", "seq": 2, "payload": {"status": TERMINAL_ERROR_STATUS}},
        ]
        return store.create(webui_session_id=webui_session_id, events=events, status=TERMINAL_ERROR_STATUS)
    except Exception as e:  # network failure, timeout, etc. — surfaced, never swallowed
        events = [
            {"event": "apperror", "seq": 1, "payload": {
                "type": "error",
                "message": f"could not reach engram-engine: {e}",
                "session_id": webui_session_id,
            }},
            {"event": "done", "seq": 2, "payload": {"status": TERMINAL_ERROR_STATUS}},
        ]
        return store.create(webui_session_id=webui_session_id, events=events, status=TERMINAL_ERROR_STATUS)


def handle_cancel_request(config: dict[str, Any], store: RunStore, run_id: str) -> dict[str, Any]:
    """Backs the native WebUI Stop action (`POST /v1/runs/{run_id}/cancel`).

    Never claims a run "already completed" when this adapter's own tracked status says
    otherwise — that was the previous behavior (a single hardcoded response regardless of
    real state) and is exactly what this replaces. Three genuinely distinct, honestly
    reported cases:

    1. Unknown run_id — this adapter never created it. Reported, not silently 200'd.
    2. A real run_id that has already reached ANY terminal status (this adapter's own
       tracked status, which is authoritative for "have we already delivered a final
       reply" regardless of what engram-engine/Hermes are doing internally) — cancelling
       is genuinely moot, and saying so is now actually true rather than a hardcoded lie.
    3. A genuinely still-`PENDING_HERMES_STATUS` run — the one case this adapter previously
       could not act on at all. Looks up the assignment_id this run's own delegation turn
       recorded (see RunStore.create), and forwards the request to engram-engine's real
       cancellation endpoint (POST /debug/hermes-assignment/{id}/cancel), which is the
       Director-side HermesActiveAssignmentRegistry — never claims this adapter itself
       stopped anything, since it does not run Hermes; it only ever requests.

    The eventual delivered reply (via _deliver_hermes_completion, unchanged by this
    function) is what actually carries confirmed information about what happened — this
    handler only ever reports whether a request was made, consistent with
    HermesCancellationResponse's own "request, not a guarantee" doc.
    """
    record = store.get(run_id)
    if record is None:
        return {"ok": False, "status": "unknown_run", "message": "No such run."}

    if record["status"] != PENDING_HERMES_STATUS:
        return {
            "ok": False,
            "status": "already_terminal",
            "message": f"This run already reached a terminal status ({record['status']}) — there is nothing left to cancel.",
        }

    assignment_id = record.get("assignment_id")
    if not assignment_id:
        # Should not happen in practice — only a delegation-triggering run is ever left
        # PENDING_HERMES_STATUS — but never claim a cancellation we have no way to act on.
        return {"ok": False, "status": "no_assignment", "message": "This run has no associated Hermes assignment to cancel."}

    result = request_hermes_cancellation(
        config["engram_base_url"], config["engram_debug_token"], assignment_id, config["synthetic_user_id"],
    )
    if result is None:
        return {"ok": False, "status": "unreachable", "message": "Could not reach engram-engine to request cancellation."}
    if result.get("requested"):
        return {
            "ok": True,
            "status": "cancellation_requested",
            "message": (
                "Cancellation requested. Hermes may still report a result if it was already "
                "finishing — that result will not be delivered as an ordinary success."
            ),
        }
    return {
        "ok": False,
        "status": "too_late",
        "message": "This assignment could not be cancelled — it had very likely already finished by the time the request arrived.",
    }


def _deliver_hermes_completion(
    config: dict[str, Any],
    store: RunStore,
    run_id: str,
    webui_session_id: str,
    assignment_id: str,
    ack_text: str,
    ack_index: int,
    *,
    max_wait_seconds: float = HERMES_COMPLETION_MAX_WAIT_SECONDS,
    poll_interval_seconds: float = HERMES_COMPLETION_POLL_INTERVAL_SECONDS,
    fetch_fn=fetch_hermes_assignment_completion,
) -> None:
    """Runs in a background thread, one per delegation-triggering turn. Polls
    /debug/hermes-assignment/{assignment_id} — the explicit delivery channel,
    independent of graph ingestion/RecentActorEvidence — until Hermes reports
    back or max_wait_seconds elapses, then appends the real result to the SAME
    run left open by run_turn(). WebUI's own runner-polling loop picks this up
    on its next poll and renders/persists it automatically: no further user
    message, no new vendor-source patch (see PENDING_HERMES_STATUS's doc).

    This function is pure transport for whatever it gets back: `completion["text"]`
    is already the Director's own composed reply (HermesCompletionDirector, Kotlin
    side, decides accept/withhold and writes the exact wording) — this adapter
    renders it verbatim and must never wrap, rephrase, or branch on `executionOutcome`/
    `decision` itself. The one exception is the timeout branch below, where
    engram-engine never got a chance to decide anything at all, because no
    response ever arrived here.

    fetch_fn is injectable purely for testing this function's own logic
    (timeout vs. a real completion arriving) without real HTTP or real time.

    Runs exactly once per run_id (run_turn spawns exactly one such thread per
    delegation) and always finalizes to a real terminal status before
    returning, so this run can never stay "running" forever from this
    function's own perspective — the bounded max_wait_seconds is the ceiling.
    """
    deadline = time.time() + max_wait_seconds
    completion: dict[str, Any] | None = None
    while time.time() < deadline:
        completion = fetch_fn(
            config["engram_base_url"], config["engram_debug_token"], assignment_id, config["synthetic_user_id"],
        )
        if completion is not None:
            break
        time.sleep(poll_interval_seconds)

    if completion is None:
        # The one case this adapter itself is entitled to compose wording for: engram-engine
        # never got a chance to decide anything, because no response ever arrived here at all.
        # Every other case below is a real Director decision (HermesCompletionDirector, Kotlin
        # side) — its `text` is rendered exactly as received, never rewrapped or reinterpreted.
        delivery_text = (
            "Hermes hasn't reported back within the expected time — something may "
            "have gone wrong with that request."
        )
        final_status = TERMINAL_ERROR_STATUS
    else:
        delivery_text = str(completion.get("text") or "")
        # Both "Accepted" (Hermes's findings were trusted) and "Withheld" (the Director
        # explicitly declined to pass along unverified/failed findings) are legitimate,
        # complete Director replies — not adapter/transport-level errors. TERMINAL_ERROR_STATUS
        # is reserved for the timeout branch above, where nothing was ever decided.
        final_status = TERMINAL_COMPLETED_STATUS

    combined_text = f"{ack_text}\n\n{delivery_text}"
    # Prefer replacing the exact ack message this turn created (stable by index
    # regardless of later, unrelated turns appended since — see
    # RunStore.replace_message_at). Fall back to a fresh assistant message only if
    # that index no longer holds an assistant entry (e.g. in-memory history was
    # reset by an adapter restart) — never silently overwrite an unrelated message.
    updated_transcript = store.replace_message_at(webui_session_id, ack_index, "assistant", combined_text)
    if updated_transcript is None:
        updated_transcript = store.append_assistant_message(webui_session_id, delivery_text)

    store.append_event(run_id, {"event": "token", "seq": 2, "payload": {"text": f"\n\n{delivery_text}"}})
    store.append_event(run_id, {"event": "done", "seq": 3, "payload": {
        "status": final_status,
        "session": {"session_id": webui_session_id, "messages": updated_transcript},
    }})
    store.set_status(run_id, final_status)
    # Persist directly rather than relying solely on a live browser polling this run's
    # events (which is what actually triggers the vendor session-persistence patch) —
    # see persist_webui_session_messages's own doc for why that assumption does not
    # hold here (no browser may be watching after a restart, or the tab may simply be
    # closed). Also removes this assignment's durable marker: whatever happened here
    # (a real completion, a timeout) is now fully recorded, so a future adapter
    # restart must not try to reconcile it again.
    persist_webui_session_messages(config.get("webui_sessions_dir"), webui_session_id, updated_transcript)
    _remove_pending_marker(config.get("hermes_pending_dir"), assignment_id)


#: How long reconcile_interrupted_assignments waits, in total, for engram-engine's
#: /health to become reachable before giving up on one specific leftover marker and
#: reporting "can't currently confirm" — this pass runs exactly once per marker at
#: adapter startup, never a retrying queue (see this module's own doc).
RECONCILE_HEALTH_MAX_WAIT_SECONDS = 30.0
RECONCILE_HEALTH_POLL_INTERVAL_SECONDS = 3.0


def _await_engram_health(
    base_url: str,
    max_wait_seconds: float,
    poll_interval_seconds: float,
    *,
    health_fn=fetch_engram_health,
) -> float | None:
    """Returns engram-engine's reported uptimeSeconds once /health answers, retrying
    for up to max_wait_seconds (the backend may be mid-restart itself, e.g. both
    services were bounced together). None only after every attempt failed.
    """
    deadline = time.time() + max_wait_seconds
    while True:
        reachable, uptime = health_fn(base_url)
        if reachable:
            return uptime
        if time.time() >= deadline:
            return None
        time.sleep(poll_interval_seconds)


def _finalize_reconciled_marker(
    config: dict[str, Any],
    pending_dir: str,
    *,
    assignment_id: str,
    webui_session_id: str,
    user_message: str,
    combined_text: str,
) -> None:
    """Directly persists one reconciled turn (the original user_message, which never
    reached disk since this run's `done` event never fired — see this module's own
    doc — plus combined_text, whatever was ultimately decided about it) and removes
    its marker. Shared by every reconcile_interrupted_assignments branch that
    resolves immediately, rather than by resuming a live poll (see
    _deliver_hermes_completion for that path's own equivalent cleanup).
    """
    sessions_dir = config.get("webui_sessions_dir")
    existing = load_persisted_webui_messages(sessions_dir, webui_session_id)
    updated = existing + [
        {"role": "user", "content": user_message},
        {"role": "assistant", "content": combined_text},
    ]
    persist_webui_session_messages(sessions_dir, webui_session_id, updated)
    _remove_pending_marker(pending_dir, assignment_id)


def reconcile_interrupted_assignments(
    config: dict[str, Any],
    store: RunStore,
    *,
    health_fn=fetch_engram_health,
    fetch_fn=fetch_hermes_assignment_completion,
    max_wait_seconds: float = HERMES_COMPLETION_MAX_WAIT_SECONDS,
    poll_interval_seconds: float = HERMES_COMPLETION_POLL_INTERVAL_SECONDS,
    health_max_wait_seconds: float = RECONCILE_HEALTH_MAX_WAIT_SECONDS,
    health_poll_interval_seconds: float = RECONCILE_HEALTH_POLL_INTERVAL_SECONDS,
) -> None:
    """Runs once at adapter startup (see main()), in its own background thread so it
    never delays the HTTP server coming up. For every marker write_pending_marker
    left behind by a delivery thread that died with a previous process instance
    (i.e. THIS adapter process restarted before that turn ever resolved), decides
    — and truthfully delivers — exactly what happened, then removes the marker.
    Never re-dispatches to Hermes; the backend, not this function, is what actually
    executes/owns an assignment (see this module's own top-of-file doc).

    The one piece of information that makes an honest decision possible at all:
    engram-engine's /health now reports uptimeSeconds (added for unrelated
    deployment monitoring, reused here as-is — no new backend endpoint or state).
    Comparing "backend process start time" (now - uptimeSeconds) against this
    marker's own created_at (this adapter's dispatch-time clock) distinguishes:

    - Health unreachable even after retrying: cannot confirm anything — reported
      as exactly that, never as a stopped/failed assignment.
    - Backend started AFTER this assignment was dispatched: confirmed loss. The
      backend that owned HermesActiveAssignmentRegistry/HermesAssignmentCompletionStore
      for this assignment no longer exists; nothing will ever answer for it.
    - Backend is the SAME instance that dispatched it (only this adapter process
      restarted): the backend never lost anything. A completion may already be
      sitting in HermesAssignmentCompletionStore (delivered immediately, verbatim,
      exactly like the ordinary path) — or the assignment may still be genuinely
      running, in which case this resumes polling it (a fresh RunStore run +
      another _deliver_hermes_completion thread), never a fresh Hermes dispatch.
    """
    pending_dir = config.get("hermes_pending_dir")
    if not pending_dir:
        return
    for marker in _read_pending_markers(pending_dir):
        assignment_id = marker.get("assignment_id")
        webui_session_id = marker.get("webui_session_id")
        user_message = marker.get("user_message")
        ack_text = marker.get("ack_text")
        created_at = marker.get("created_at")
        if not (assignment_id and webui_session_id and isinstance(created_at, (int, float))):
            _remove_pending_marker(pending_dir, assignment_id)  # malformed beyond use — drop it
            continue

        uptime = _await_engram_health(
            config["engram_base_url"], health_max_wait_seconds, health_poll_interval_seconds, health_fn=health_fn,
        )
        if uptime is None:
            _finalize_reconciled_marker(
                config, pending_dir,
                assignment_id=assignment_id, webui_session_id=webui_session_id, user_message=user_message,
                combined_text=f"{ack_text}\n\n" + (
                    "I can't currently reach the development backend to check on this, so I "
                    "don't know whether it finished. This conversation is ready for another "
                    "message whenever you'd like."
                ),
            )
            continue

        backend_started_at = time.time() - uptime
        if backend_started_at > created_at:
            _finalize_reconciled_marker(
                config, pending_dir,
                assignment_id=assignment_id, webui_session_id=webui_session_id, user_message=user_message,
                combined_text=f"{ack_text}\n\n" + (
                    "The development backend restarted while this was still outstanding, so "
                    "there's no way for me to confirm whether it finished. This conversation is "
                    "ready for another message whenever you'd like."
                ),
            )
            continue

        # The backend is the same instance that dispatched this — it never lost the
        # assignment. It may already have a recorded completion...
        completion = fetch_fn(config["engram_base_url"], config["engram_debug_token"], assignment_id, config["synthetic_user_id"])
        if completion is not None:
            _finalize_reconciled_marker(
                config, pending_dir,
                assignment_id=assignment_id, webui_session_id=webui_session_id, user_message=user_message,
                combined_text=f"{ack_text}\n\n{str(completion.get('text') or '')}",
            )
            continue

        # ...or it may still be genuinely running. Resume watching it exactly like an
        # ordinary delegation turn would — this only restores THIS adapter's own poll
        # of the backend's already-live assignment; nothing is re-dispatched.
        seed = load_persisted_webui_messages(config.get("webui_sessions_dir"), webui_session_id)
        transcript = store.append_turn_messages(webui_session_id, user_message, ack_text, seed=seed)
        ack_index = len(transcript) - 1
        run_id = store.create(
            webui_session_id=webui_session_id,
            events=[{"event": "token", "seq": 1, "payload": {"text": ack_text}}],
            status=PENDING_HERMES_STATUS,
            assignment_id=assignment_id,
        )
        thread = threading.Thread(
            target=_deliver_hermes_completion,
            args=(config, store, run_id, webui_session_id, assignment_id, ack_text, ack_index),
            kwargs={
                "max_wait_seconds": max_wait_seconds,
                "poll_interval_seconds": poll_interval_seconds,
                "fetch_fn": fetch_fn,
            },
            daemon=True,
        )
        thread.start()
        # NOTE: the marker for this assignment_id is deliberately left in place here —
        # the resumed _deliver_hermes_completion thread above removes it when IT
        # concludes, exactly like an uninterrupted delegation's own delivery thread does.


def effective_model_fields(engram_result: dict[str, Any]) -> tuple[str | None, str | None]:
    """Derives the (model, provider) pair to report to WebUI from engram-engine's
    own debug-converse trace (trace.model.reasonProvider/reasonModel) — the same
    fields CognitivePipeline populates from the actual LlmResponse that answered
    the turn (see engram-engine's LlmResponse.providerName/modelName), not a
    label WebUI or this adapter invents.

    Absent for a turn a phrase-pool branch answered without calling an LLM at
    all (e.g. a short-circuited SOCIAL turn) — trace.model.reasonProvider is
    null in that case, and this returns (None, None) so the caller omits both
    fields rather than reporting a fabricated model for a turn with none.

    reasonModel is often a full gguf file path for a local model; this reports
    only its basename (extension stripped) so the WebUI model chip shows a
    short label instead of a raw filesystem path.
    """
    model_trace = (engram_result.get("trace") or {}).get("model") or {}
    provider = model_trace.get("reasonProvider")
    raw_model = model_trace.get("reasonModel")
    if not provider or not raw_model:
        return None, None
    label = os.path.basename(str(raw_model))
    if label.endswith(".gguf"):
        label = label[: -len(".gguf")]
    return label, provider


def _unsupported(message: str) -> dict[str, Any]:
    return {"ok": False, "status": "unsupported", "message": message}


def make_handler(config: dict[str, Any], store: RunStore) -> type[BaseHTTPRequestHandler]:
    class RunnerHandler(BaseHTTPRequestHandler):
        server_version = "engram-runner-adapter/1.0"

        def log_message(self, fmt: str, *args: Any) -> None:
            sys.stderr.write("%s - - [%s] %s\n" % (self.address_string(), self.log_date_time_string(), fmt % args))

        def _authorized(self) -> bool:
            return is_authorized(self.headers.get("Authorization"), config["runner_api_key"])

        def _send_json(self, status: int, obj: dict[str, Any]) -> None:
            body = json.dumps(obj).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _read_json_body(self) -> dict[str, Any]:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length > 0 else b""
            parsed = json.loads(raw or b"{}")
            return parsed if isinstance(parsed, dict) else {}

        def _require_auth(self) -> bool:
            if not self._authorized():
                self._send_json(401, {"error": "unauthorized"})
                return False
            return True

        def do_GET(self) -> None:
            if not self._require_auth():
                return
            path = self.path.split("?", 1)[0]
            parts = [p for p in path.split("/") if p]
            # /v1/runs/{id}/events  or  /v1/runs/{id}
            if len(parts) == 4 and parts[:2] == ["v1", "runs"] and parts[3] == "events":
                run_id = parts[2]
                query = self.path.split("?", 1)[1] if "?" in self.path else ""
                cursor_param = None
                for kv in query.split("&"):
                    if kv.startswith("cursor="):
                        cursor_param = kv.split("=", 1)[1]
                found = store.events_since(run_id, _parse_cursor(cursor_param))
                if found is None:
                    self._send_json(404, {"error": "unknown run_id"})
                    return
                events, new_cursor = found
                self._send_json(200, {"run_id": run_id, "events": events, "cursor": str(new_cursor)})
                return
            if len(parts) == 3 and parts[:2] == ["v1", "runs"]:
                run_id = parts[2]
                record = store.get(run_id)
                if record is None:
                    self._send_json(404, {"error": "unknown run_id"})
                    return
                self._send_json(200, {
                    "run_id": run_id,
                    "session_id": record["session_id"],
                    "status": record["status"],
                    "terminal_state": record["status"],
                    "active_controls": [],
                })
                return
            self._send_json(404, {"error": "not found"})

        def do_POST(self) -> None:
            if not self._require_auth():
                return
            path = self.path.split("?", 1)[0]
            parts = [p for p in path.split("/") if p]

            if parts == ["v1", "runs"]:
                body = self._read_json_body()
                message = body.get("message")
                webui_session_id = str(body.get("session_id") or "").strip() or uuid.uuid4().hex
                if not isinstance(message, str) or not message.strip():
                    self._send_json(400, {"error": "message is required"})
                    return
                attachments = body.get("attachments") if isinstance(body.get("attachments"), list) else []
                toolsets = body.get("toolsets") if isinstance(body.get("toolsets"), list) else []
                # HttpRunnerClient.start_run() sends these as "model"/"provider"
                # (StartRunRequest.model / .provider) — see api/runner_client.py.
                model = body.get("model") if isinstance(body.get("model"), str) else None
                model_provider = body.get("provider") if isinstance(body.get("provider"), str) else None
                run_id = run_turn(
                    config, store,
                    webui_session_id=webui_session_id,
                    message=message,
                    attachments=attachments,
                    toolsets=toolsets,
                    model=model,
                    model_provider=model_provider,
                )
                record = store.get(run_id)
                response = {
                    "run_id": run_id,
                    "stream_id": run_id,
                    "session_id": webui_session_id,
                    "status": record["status"] if record else "completed",
                    "active_controls": [],
                }
                # WebUI's routes.py._chat_start_response_from_run_start only forwards
                # these two keys to the browser when present in this payload, and
                # messages.js uses them to correct the model chip away from its
                # static placeholder default ("GPT-5.4 Mini") to whatever actually
                # answered. Omitted (not sent as null) when engram-engine's own
                # trace didn't name an LLM for this turn — see effective_model_fields.
                if record and record.get("effective_model"):
                    response["effective_model"] = record["effective_model"]
                if record and record.get("effective_model_provider"):
                    response["effective_model_provider"] = record["effective_model_provider"]
                self._send_json(200, response)
                return

            if len(parts) == 4 and parts[:2] == ["v1", "runs"] and parts[3] == "cancel":
                self._send_json(200, handle_cancel_request(config, store, parts[2]))
                return
            if len(parts) == 4 and parts[:2] == ["v1", "runs"] and parts[3] == "approval":
                self._send_json(200, _unsupported("Approval is not supported in this development slice."))
                return
            if len(parts) == 4 and parts[:2] == ["v1", "runs"] and parts[3] == "messages":
                self._send_json(200, _unsupported("Queuing an additional message mid-run is not supported in this development slice."))
                return
            if len(parts) >= 4 and parts[:2] == ["v1", "runs"] and "clarifications" in parts:
                self._send_json(200, _unsupported("Clarification responses are not supported in this development slice."))
                return
            if len(parts) == 3 and parts[:2] == ["v1", "sessions"]:
                self._send_json(200, _unsupported("Goal actions are not supported in this development slice."))
                return

            self._send_json(404, {"error": "not found"})

    return RunnerHandler


def main() -> None:
    try:
        config = load_runner_config()
    except (RunnerConfigError, ConfigError) as e:
        print(f"engram-runner-adapter: {e}", file=sys.stderr)
        raise SystemExit(1)

    store = RunStore()
    if config.get("hermes_pending_dir"):
        # Backgrounded so a slow/unreachable engram-engine at boot (see
        # RECONCILE_HEALTH_MAX_WAIT_SECONDS) never delays this adapter from accepting
        # ordinary requests. reconcile_interrupted_assignments is itself a no-op when
        # there is nothing left over from a previous process instance.
        threading.Thread(target=reconcile_interrupted_assignments, args=(config, store), daemon=True).start()
    handler = make_handler(config, store)
    httpd = ThreadingHTTPServer((config["runner_host"], config["runner_port"]), handler)
    print(
        f"engram-runner-adapter listening on {config['runner_host']}:{config['runner_port']} "
        f"-> {config['engram_base_url']} (identity={config['synthetic_user_id']})"
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
