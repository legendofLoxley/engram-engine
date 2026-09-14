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

from engram_client import ConfigError, UpstreamError, build_engram_payload, forward_to_engram, load_engram_config


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

    def create(self, *, webui_session_id: str, events: list[dict[str, Any]], status: str) -> str:
        run_id = uuid.uuid4().hex
        with self._lock:
            self._runs[run_id] = {
                "session_id": webui_session_id,
                "events": events,
                "status": status,
                "created_at": time.time(),
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


def run_turn(
    config: dict[str, Any],
    store: RunStore,
    *,
    webui_session_id: str,
    message: str,
    attachments: list[Any] | None = None,
    toolsets: list[Any] | None = None,
) -> str:
    """Executes one Director turn synchronously and records it as a completed run.

    Returns the new run_id. Never raises for an upstream failure — that is
    recorded as an 'apperror' run event instead, so the browser renders a
    visible chat error rather than the composer hanging or a bare 500.

    Attachments/toolsets are rejected here, before any call to engram-engine
    — the Director is never invoked for a turn it can't fully honor, and the
    rejection reaches the user through the same apperror/done event pair (and
    the same chat-bubble rendering) any other run failure uses, not a new
    mechanism.
    """
    attachments = attachments or []
    toolsets = toolsets or []
    if attachments or toolsets:
        events = [
            {"event": "apperror", "seq": 1, "payload": {
                "type": "unsupported_input",
                "message": unsupported_input_message(attachments, toolsets),
            }},
            {"event": "done", "seq": 2, "payload": {"status": TERMINAL_ERROR_STATUS}},
        ]
        return store.create(webui_session_id=webui_session_id, events=events, status=TERMINAL_ERROR_STATUS)

    engram_session_id = store.engram_session_for(webui_session_id)
    try:
        payload = build_engram_payload(message, config["synthetic_user_id"], session_id=engram_session_id)
        result = forward_to_engram(config["engram_base_url"], config["engram_debug_token"], payload)
        store.remember_engram_session(webui_session_id, result.get("sessionId"))
        reply_text = str(result.get("reply") or "")
        events = [
            {"event": "token", "seq": 1, "payload": {"text": reply_text}},
            {"event": "done", "seq": 2, "payload": {"status": TERMINAL_COMPLETED_STATUS}},
        ]
        return store.create(webui_session_id=webui_session_id, events=events, status=TERMINAL_COMPLETED_STATUS)
    except UpstreamError as e:
        detail = e.body.decode("utf-8", errors="replace")[:500]
        events = [
            {"event": "apperror", "seq": 1, "payload": {"type": "error", "message": f"engram-engine rejected the turn (HTTP {e.status}): {detail}"}},
            {"event": "done", "seq": 2, "payload": {"status": TERMINAL_ERROR_STATUS}},
        ]
        return store.create(webui_session_id=webui_session_id, events=events, status=TERMINAL_ERROR_STATUS)
    except Exception as e:  # network failure, timeout, etc. — surfaced, never swallowed
        events = [
            {"event": "apperror", "seq": 1, "payload": {"type": "error", "message": f"could not reach engram-engine: {e}"}},
            {"event": "done", "seq": 2, "payload": {"status": TERMINAL_ERROR_STATUS}},
        ]
        return store.create(webui_session_id=webui_session_id, events=events, status=TERMINAL_ERROR_STATUS)


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
                run_id = run_turn(
                    config, store,
                    webui_session_id=webui_session_id,
                    message=message,
                    attachments=attachments,
                    toolsets=toolsets,
                )
                record = store.get(run_id)
                self._send_json(200, {
                    "run_id": run_id,
                    "stream_id": run_id,
                    "session_id": webui_session_id,
                    "status": record["status"] if record else "completed",
                    "active_controls": [],
                })
                return

            if len(parts) == 4 and parts[:2] == ["v1", "runs"] and parts[3] == "cancel":
                self._send_json(200, _unsupported("This run already completed — cancellation is not applicable to a single-request Director reply in this slice."))
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
    handler = make_handler(config, store)
    httpd = ThreadingHTTPServer((config["runner_host"], config["runner_port"]), handler)
    print(
        f"engram-runner-adapter listening on {config['runner_host']}:{config['runner_port']} "
        f"-> {config['engram_base_url']} (identity={config['synthetic_user_id']})"
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
