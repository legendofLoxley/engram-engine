"""Pure logic for calling engram-engine's /debug/converse and
/debug/hermes-assignment endpoints.

Shared by runner_adapter.py (the live WebUI integration). No HTTP-server
concerns live here — just config loading, payload shaping, and the upstream
calls — so it can be unit-tested without spinning up any server.
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


class ConfigError(RuntimeError):
    pass


class UpstreamError(RuntimeError):
    def __init__(self, status: int, body: bytes):
        super().__init__(f"upstream error {status}")
        self.status = status
        self.body = body


def load_engram_config(environ: dict[str, str] | None = None) -> dict[str, Any]:
    source = os.environ if environ is None else environ
    token = str(source.get("ENGRAM_DEBUG_TOKEN") or "").strip()
    if not token:
        raise ConfigError("ENGRAM_DEBUG_TOKEN is required and must not be empty")
    return {
        "engram_base_url": str(source.get("ENGRAM_BASE_URL") or "http://127.0.0.1:8082").rstrip("/"),
        "engram_debug_token": token,
        "synthetic_user_id": str(source.get("ENGRAM_SYNTHETIC_USER_ID") or "webui-dev"),
    }


def build_engram_payload(message: str, synthetic_user_id: str, session_id: str | None = None) -> dict[str, Any]:
    """Build the /debug/converse request body.

    synthetic_user_id is always the server-configured value, never a
    caller-supplied one — this is the mechanism that binds every WebUI turn
    to one fixed development identity regardless of which browser session
    sent it.
    """
    if not isinstance(message, str) or not message.strip():
        raise ValueError("message is required and must be a non-empty string")

    payload: dict[str, Any] = {"message": message, "syntheticUserId": synthetic_user_id}
    if isinstance(session_id, str) and session_id.strip():
        payload["sessionId"] = session_id
    return payload


def forward_to_engram(base_url: str, token: str, payload: dict[str, Any], timeout: float = 90.0) -> dict[str, Any]:
    """POST payload to engram-engine's /debug/converse. Returns the parsed JSON response.

    Raises UpstreamError for a non-2xx upstream response, or the underlying
    urllib exception for a connection failure — both are the caller's to map,
    never silently swallowed as success.
    """
    body_bytes = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{base_url}/debug/converse",
        data=body_bytes,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as e:
        raise UpstreamError(e.code, e.read()) from e
    return json.loads(raw)


def fetch_hermes_assignment_completion(
    base_url: str,
    token: str,
    assignment_id: str,
    synthetic_user_id: str,
    timeout: float = 10.0,
) -> dict[str, Any] | None:
    """GET engram-engine's /debug/hermes-assignment/{assignment_id}.

    Returns the parsed completion body on 200 ("found — Hermes has reported back"),
    or None on a 404 ("not found yet — still outstanding, or an unknown/mismatched
    id") or any transient failure (connection error, timeout, non-2xx/404 status).
    None deliberately covers both "keep polling" and "give up eventually" — the
    caller's own bounded max-wait is what distinguishes them, not this function
    raising a different exception per case, since a poller wants exactly one
    "not ready" signal to act on regardless of the underlying reason.
    """
    url = (
        f"{base_url}/debug/hermes-assignment/{assignment_id}"
        f"?syntheticUserId={urllib.parse.quote(synthetic_user_id)}"
    )
    req = urllib.request.Request(
        url,
        method="GET",
        headers={"Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError:
        return None
    except (urllib.error.URLError, OSError, TimeoutError):
        return None
    try:
        parsed = json.loads(raw)
    except ValueError:
        return None
    return parsed if isinstance(parsed, dict) else None
