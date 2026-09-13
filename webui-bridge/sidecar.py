#!/usr/bin/env python3
"""engram-webui-bridge: a loopback-only, credential-holding proxy.

Lets a Hermes WebUI browser extension (see extension/director-chat.js) send
turns to engram-engine's real Director pipeline (POST /debug/converse)
without the browser ever holding or sending engram-engine's debug bearer
token. The token lives only in this process's environment; the browser talks
to this bridge with no credential at all.

Every browser turn is bound server-side to one fixed synthetic identity
(ENGRAM_SYNTHETIC_USER_ID) — the client cannot choose or override it — so
Director-side graph recall stays continuous across WebUI sessions/reloads
regardless of the browser-side conversation/session id.

Config (env vars):
  ENGRAM_BASE_URL          engram-engine base URL. Default: http://127.0.0.1:8082
  ENGRAM_DEBUG_TOKEN       required; the debug-converse bearer token. Never
                           logged, never echoed back to a caller.
  ENGRAM_SYNTHETIC_USER_ID Fixed Director-side identity for every turn.
                           Default: webui-dev
  BRIDGE_HOST              Default: 127.0.0.1 (loopback only by convention;
                           this process does not enforce the bind address).
  BRIDGE_PORT              Default: 8090
  BRIDGE_ALLOWED_ORIGIN    CORS origin allowed to call this bridge — the dev
                           WebUI's own origin. Default: http://127.0.0.1:8788
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


class ConfigError(RuntimeError):
    pass


def load_config(environ: dict[str, str] | None = None) -> dict[str, Any]:
    source = os.environ if environ is None else environ
    token = str(source.get("ENGRAM_DEBUG_TOKEN") or "").strip()
    if not token:
        raise ConfigError("ENGRAM_DEBUG_TOKEN is required and must not be empty")
    return {
        "engram_base_url": str(source.get("ENGRAM_BASE_URL") or "http://127.0.0.1:8082").rstrip("/"),
        "engram_debug_token": token,
        "synthetic_user_id": str(source.get("ENGRAM_SYNTHETIC_USER_ID") or "webui-dev"),
        "bridge_host": str(source.get("BRIDGE_HOST") or "127.0.0.1"),
        "bridge_port": int(source.get("BRIDGE_PORT") or "8090"),
        "allowed_origin": str(source.get("BRIDGE_ALLOWED_ORIGIN") or "http://127.0.0.1:8788"),
    }


def build_engram_payload(client_body: dict[str, Any], synthetic_user_id: str) -> dict[str, Any]:
    """Translate the browser's request into engram-engine's /debug/converse shape.

    synthetic_user_id is always the server-configured value — a client-supplied
    syntheticUserId (if any) is ignored, never honored. This is the mechanism
    that binds every WebUI turn to one fixed development identity.
    """
    message = client_body.get("message")
    if not isinstance(message, str) or not message.strip():
        raise ValueError("message is required and must be a non-empty string")

    payload: dict[str, Any] = {"message": message, "syntheticUserId": synthetic_user_id}
    session_id = client_body.get("sessionId")
    if isinstance(session_id, str) and session_id.strip():
        payload["sessionId"] = session_id
    request_id = client_body.get("requestId")
    if isinstance(request_id, str) and request_id.strip():
        payload["requestId"] = request_id
    return payload


def cors_headers(allowed_origin: str) -> dict[str, str]:
    return {
        "Access-Control-Allow-Origin": allowed_origin,
        "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type",
        "Vary": "Origin",
    }


class UpstreamError(RuntimeError):
    def __init__(self, status: int, body: bytes):
        super().__init__(f"upstream error {status}")
        self.status = status
        self.body = body


def forward_to_engram(base_url: str, token: str, payload: dict[str, Any], timeout: float = 90.0) -> bytes:
    """POST payload to engram-engine's /debug/converse. Returns the raw response body.

    Raises UpstreamError for a non-2xx upstream response, or the underlying
    urllib exception for a connection failure — both are caller's to map to an
    HTTP status; this function never silently swallows a failure as success.
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
            return resp.read()
    except urllib.error.HTTPError as e:
        raise UpstreamError(e.code, e.read()) from e


def make_handler(config: dict[str, Any]) -> type[BaseHTTPRequestHandler]:
    class BridgeHandler(BaseHTTPRequestHandler):
        server_version = "engram-webui-bridge/1.0"

        def log_message(self, fmt: str, *args: Any) -> None:  # quieter, no query strings/bodies
            sys.stderr.write("%s - - [%s] %s\n" % (self.address_string(), self.log_date_time_string(), fmt % args))

        def _send_json(self, status: int, obj: dict[str, Any]) -> None:
            body = json.dumps(obj).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            for k, v in cors_headers(config["allowed_origin"]).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def do_OPTIONS(self) -> None:  # CORS preflight
            self.send_response(204)
            for k, v in cors_headers(config["allowed_origin"]).items():
                self.send_header(k, v)
            self.end_headers()

        def do_GET(self) -> None:
            if self.path == "/health":
                self._send_json(200, {"status": "ok"})
                return
            self._send_json(404, {"error": "not found"})

        def do_POST(self) -> None:
            if self.path != "/converse":
                self._send_json(404, {"error": "not found"})
                return

            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length > 0 else b""
            try:
                client_body = json.loads(raw or b"{}")
                if not isinstance(client_body, dict):
                    raise ValueError("request body must be a JSON object")
                payload = build_engram_payload(client_body, config["synthetic_user_id"])
            except (ValueError, json.JSONDecodeError) as e:
                self._send_json(400, {"error": str(e)})
                return

            try:
                upstream_body = forward_to_engram(config["engram_base_url"], config["engram_debug_token"], payload)
            except UpstreamError as e:
                self._send_json(502, {"error": "engram-engine rejected the turn", "upstream_status": e.status})
                return
            except Exception as e:  # network failure, timeout, etc. — never silently "succeed"
                self._send_json(502, {"error": f"could not reach engram-engine: {e}"})
                return

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(upstream_body)))
            for k, v in cors_headers(config["allowed_origin"]).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(upstream_body)

    return BridgeHandler


def main() -> None:
    try:
        config = load_config()
    except ConfigError as e:
        print(f"engram-webui-bridge: {e}", file=sys.stderr)
        raise SystemExit(1)

    handler = make_handler(config)
    httpd = ThreadingHTTPServer((config["bridge_host"], config["bridge_port"]), handler)
    print(
        f"engram-webui-bridge listening on {config['bridge_host']}:{config['bridge_port']} "
        f"-> {config['engram_base_url']} (identity={config['synthetic_user_id']}, "
        f"allowed origin={config['allowed_origin']})"
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
