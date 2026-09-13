"""Focused tests for webui-bridge/sidecar.py.

Run with: python3 -m unittest webui-bridge/test_sidecar.py -v
(from the repo root; no third-party dependencies required)
"""
from __future__ import annotations

import json
import threading
import unittest
import urllib.error
import urllib.request
from http.client import HTTPConnection
from unittest.mock import patch

import sidecar


class LoadConfigTest(unittest.TestCase):
    def test_missing_token_raises(self):
        with self.assertRaises(sidecar.ConfigError):
            sidecar.load_config({})

    def test_defaults_applied(self):
        cfg = sidecar.load_config({"ENGRAM_DEBUG_TOKEN": "secret"})
        self.assertEqual(cfg["engram_base_url"], "http://127.0.0.1:8082")
        self.assertEqual(cfg["synthetic_user_id"], "webui-dev")
        self.assertEqual(cfg["bridge_host"], "127.0.0.1")
        self.assertEqual(cfg["bridge_port"], 8090)
        self.assertEqual(cfg["allowed_origin"], "http://127.0.0.1:8788")

    def test_token_never_defaulted_or_guessed(self):
        # An empty string is explicitly rejected, not silently accepted as "no auth".
        with self.assertRaises(sidecar.ConfigError):
            sidecar.load_config({"ENGRAM_DEBUG_TOKEN": "   "})


class BuildEngramPayloadTest(unittest.TestCase):
    def test_forces_configured_identity_ignoring_client_value(self):
        payload = sidecar.build_engram_payload(
            {"message": "hi", "syntheticUserId": "attacker-chosen@evil.example"},
            synthetic_user_id="webui-dev",
        )
        self.assertEqual(payload["syntheticUserId"], "webui-dev")

    def test_passes_through_session_and_request_id(self):
        payload = sidecar.build_engram_payload(
            {"message": "hi", "sessionId": "s-1", "requestId": "r-1"},
            synthetic_user_id="webui-dev",
        )
        self.assertEqual(payload["sessionId"], "s-1")
        self.assertEqual(payload["requestId"], "r-1")

    def test_omits_session_id_when_absent(self):
        payload = sidecar.build_engram_payload({"message": "hi"}, synthetic_user_id="webui-dev")
        self.assertNotIn("sessionId", payload)

    def test_rejects_blank_message(self):
        with self.assertRaises(ValueError):
            sidecar.build_engram_payload({"message": "   "}, synthetic_user_id="webui-dev")

    def test_rejects_missing_message(self):
        with self.assertRaises(ValueError):
            sidecar.build_engram_payload({}, synthetic_user_id="webui-dev")


class CorsHeadersTest(unittest.TestCase):
    def test_scoped_to_configured_origin_only(self):
        headers = sidecar.cors_headers("http://127.0.0.1:8788")
        self.assertEqual(headers["Access-Control-Allow-Origin"], "http://127.0.0.1:8788")
        self.assertNotEqual(headers["Access-Control-Allow-Origin"], "*")


class BridgeHttpIntegrationTest(unittest.TestCase):
    """Exercises the real HTTP handler on an ephemeral loopback port, with
    forward_to_engram patched so no real network call to engram-engine happens.
    """

    @classmethod
    def setUpClass(cls):
        cls.config = sidecar.load_config({
            "ENGRAM_DEBUG_TOKEN": "test-token",
            "ENGRAM_SYNTHETIC_USER_ID": "webui-dev",
            "BRIDGE_PORT": "0",
        })
        handler = sidecar.make_handler(cls.config)
        cls.httpd = sidecar.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def _conn(self) -> HTTPConnection:
        return HTTPConnection("127.0.0.1", self.port, timeout=5)

    def test_health_ok(self):
        conn = self._conn()
        conn.request("GET", "/health")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        self.assertEqual(json.loads(resp.read())["status"], "ok")

    def test_unknown_path_is_404(self):
        conn = self._conn()
        conn.request("GET", "/nope")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 404)

    def test_options_preflight_has_cors_headers(self):
        conn = self._conn()
        conn.request("OPTIONS", "/converse")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 204)
        self.assertEqual(resp.getheader("Access-Control-Allow-Origin"), "http://127.0.0.1:8788")

    def test_converse_forwards_and_relays_upstream_body_verbatim(self):
        upstream_reply = json.dumps({"reply": "hello from Director", "sessionId": "s-9"}).encode()
        with patch.object(sidecar, "forward_to_engram", return_value=upstream_reply) as mocked:
            conn = self._conn()
            body = json.dumps({"message": "hi there", "syntheticUserId": "spoofed@evil"}).encode()
            conn.request("POST", "/converse", body=body, headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            self.assertEqual(resp.status, 200)
            self.assertEqual(json.loads(resp.read())["reply"], "hello from Director")

        # The identity actually sent upstream was the configured one, not the client's.
        sent_payload = mocked.call_args.args[2]
        self.assertEqual(sent_payload["syntheticUserId"], "webui-dev")

    def test_converse_rejects_blank_message_without_calling_upstream(self):
        with patch.object(sidecar, "forward_to_engram") as mocked:
            conn = self._conn()
            body = json.dumps({"message": ""}).encode()
            conn.request("POST", "/converse", body=body, headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            self.assertEqual(resp.status, 400)
            mocked.assert_not_called()

    def test_upstream_failure_is_reported_not_swallowed(self):
        with patch.object(sidecar, "forward_to_engram", side_effect=sidecar.UpstreamError(401, b"nope")):
            conn = self._conn()
            body = json.dumps({"message": "hi"}).encode()
            conn.request("POST", "/converse", body=body, headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            self.assertEqual(resp.status, 502)
            payload = json.loads(resp.read())
            self.assertIn("error", payload)

    def test_network_failure_returns_502_not_200(self):
        with patch.object(sidecar, "forward_to_engram", side_effect=urllib.error.URLError("connection refused")):
            conn = self._conn()
            body = json.dumps({"message": "hi"}).encode()
            conn.request("POST", "/converse", body=body, headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            self.assertEqual(resp.status, 502)


if __name__ == "__main__":
    unittest.main()
