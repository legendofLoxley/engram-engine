"""Focused tests for runner_adapter.py — the actual hermes-webui runner
contract implementation, including per-request authentication enforcement.
"""
from __future__ import annotations

import json
import threading
import unittest
from http.client import HTTPConnection
from unittest.mock import patch

import engram_client
import runner_adapter as ra


class LoadRunnerConfigTest(unittest.TestCase):
    def test_missing_runner_api_key_raises(self):
        with self.assertRaises(ra.RunnerConfigError):
            ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t"})

    def test_missing_engram_token_raises(self):
        with self.assertRaises(engram_client.ConfigError):
            ra.load_runner_config({"RUNNER_API_KEY": "k"})

    def test_defaults_applied(self):
        cfg = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.assertEqual(cfg["runner_host"], "127.0.0.1")
        self.assertEqual(cfg["runner_port"], 8091)


class IsAuthorizedTest(unittest.TestCase):
    def test_exact_match_required(self):
        self.assertTrue(ra.is_authorized("Bearer secret", "secret"))

    def test_missing_header_rejected(self):
        self.assertFalse(ra.is_authorized(None, "secret"))

    def test_wrong_key_rejected(self):
        self.assertFalse(ra.is_authorized("Bearer wrong", "secret"))

    def test_missing_bearer_prefix_rejected(self):
        self.assertFalse(ra.is_authorized("secret", "secret"))

    def test_empty_header_rejected(self):
        self.assertFalse(ra.is_authorized("", "secret"))


class RunStoreTest(unittest.TestCase):
    def test_session_mapping_round_trip(self):
        store = ra.RunStore()
        self.assertIsNone(store.engram_session_for("webui-s1"))
        store.remember_engram_session("webui-s1", "engram-s1")
        self.assertEqual(store.engram_session_for("webui-s1"), "engram-s1")

    def test_events_since_paginates(self):
        store = ra.RunStore()
        run_id = store.create(webui_session_id="s1", events=[{"event": "token", "seq": 1}, {"event": "done", "seq": 2}], status="completed")
        first, cursor = store.events_since(run_id, 0)
        self.assertEqual(len(first), 2)
        self.assertEqual(cursor, 2)
        second, cursor2 = store.events_since(run_id, cursor)
        self.assertEqual(second, [])
        self.assertEqual(cursor2, 2)

    def test_events_since_unknown_run_returns_none(self):
        store = ra.RunStore()
        self.assertIsNone(store.events_since("nope", 0))


class RunTurnTest(unittest.TestCase):
    def setUp(self):
        self.config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.store = ra.RunStore()

    def test_success_produces_token_then_done_events(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hello", "sessionId": "e-1"}):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi")
        record = self.store.get(run_id)
        self.assertEqual(record["status"], "completed")
        self.assertEqual(record["events"][0]["event"], "token")
        self.assertEqual(record["events"][0]["payload"]["text"], "hello")
        self.assertEqual(record["events"][1]["event"], "done")
        self.assertEqual(self.store.engram_session_for("w-1"), "e-1")

    def test_second_turn_reuses_mapped_engram_session(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "a", "sessionId": "e-1"}) as mocked:
            ra.run_turn(self.config, self.store, webui_session_id="w-1", message="first")
        with patch.object(ra, "forward_to_engram", return_value={"reply": "b", "sessionId": "e-1"}) as mocked2:
            ra.run_turn(self.config, self.store, webui_session_id="w-1", message="second")
            sent_payload = mocked2.call_args.args[2]
            self.assertEqual(sent_payload["sessionId"], "e-1")

    def test_upstream_failure_surfaces_as_apperror_event_not_an_exception(self):
        with patch.object(ra, "forward_to_engram", side_effect=engram_client.UpstreamError(401, b"nope")):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi")
        record = self.store.get(run_id)
        self.assertEqual(record["status"], "errored")
        self.assertEqual(record["events"][0]["event"], "apperror")

    def test_network_failure_surfaces_as_apperror_event_not_a_silent_success(self):
        with patch.object(ra, "forward_to_engram", side_effect=ConnectionRefusedError("nope")):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi")
        record = self.store.get(run_id)
        self.assertEqual(record["status"], "errored")


class RunnerHttpIntegrationTest(unittest.TestCase):
    """Exercises the real HTTP handler on an ephemeral loopback port."""

    @classmethod
    def setUpClass(cls):
        cls.config = ra.load_runner_config({
            "ENGRAM_DEBUG_TOKEN": "engram-secret",
            "RUNNER_API_KEY": "runner-secret",
            "RUNNER_PORT": "0",
        })
        cls.store = ra.RunStore()
        handler = ra.make_handler(cls.config, cls.store)
        cls.httpd = ra.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def _conn(self) -> HTTPConnection:
        return HTTPConnection("127.0.0.1", self.port, timeout=5)

    def _auth_headers(self, key: str | None = "runner-secret") -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if key is not None:
            headers["Authorization"] = f"Bearer {key}"
        return headers

    def test_unauthenticated_start_run_is_rejected(self):
        conn = self._conn()
        body = json.dumps({"message": "hi"}).encode()
        conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers(key=None))
        resp = conn.getresponse()
        self.assertEqual(resp.status, 401)

    def test_wrong_key_start_run_is_rejected(self):
        conn = self._conn()
        body = json.dumps({"message": "hi"}).encode()
        conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers(key="wrong-key"))
        resp = conn.getresponse()
        self.assertEqual(resp.status, 401)

    def test_unauthenticated_get_run_is_rejected(self):
        conn = self._conn()
        conn.request("GET", "/v1/runs/anything", headers=self._auth_headers(key=None))
        resp = conn.getresponse()
        self.assertEqual(resp.status, 401)

    def test_unauthenticated_events_is_rejected(self):
        conn = self._conn()
        conn.request("GET", "/v1/runs/anything/events", headers=self._auth_headers(key=None))
        resp = conn.getresponse()
        self.assertEqual(resp.status, 401)

    def test_authenticated_full_run_lifecycle_succeeds(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "Good afternoon.", "sessionId": "e-9"}):
            conn = self._conn()
            body = json.dumps({"session_id": "webui-session-42", "message": "hello"}).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            self.assertEqual(resp.status, 200)
            start_payload = json.loads(resp.read())
            run_id = start_payload["run_id"]
            self.assertEqual(start_payload["session_id"], "webui-session-42")
            self.assertEqual(start_payload["status"], "completed")

            conn2 = self._conn()
            conn2.request("GET", f"/v1/runs/{run_id}/events?cursor=0", headers=self._auth_headers())
            resp2 = conn2.getresponse()
            self.assertEqual(resp2.status, 200)
            events_payload = json.loads(resp2.read())
            self.assertEqual(events_payload["events"][0]["event"], "token")
            self.assertEqual(events_payload["events"][0]["payload"]["text"], "Good afternoon.")
            self.assertEqual(events_payload["events"][1]["event"], "done")

            conn3 = self._conn()
            conn3.request("GET", f"/v1/runs/{run_id}", headers=self._auth_headers())
            resp3 = conn3.getresponse()
            status_payload = json.loads(resp3.read())
            self.assertEqual(status_payload["status"], "completed")
            self.assertEqual(status_payload["terminal_state"], "completed")

    def test_cancel_reports_not_active_rather_than_pretending_to_cancel(self):
        conn = self._conn()
        conn.request("POST", "/v1/runs/whatever/cancel", body=b"{}", headers=self._auth_headers())
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        payload = json.loads(resp.read())
        self.assertFalse(payload["ok"])

    def test_blank_message_rejected_without_calling_upstream(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            conn = self._conn()
            body = json.dumps({"message": ""}).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            self.assertEqual(resp.status, 400)
            mocked.assert_not_called()


if __name__ == "__main__":
    unittest.main()
