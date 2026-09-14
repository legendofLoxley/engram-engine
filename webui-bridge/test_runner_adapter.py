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

    def test_append_turn_messages_accumulates_per_session(self):
        store = ra.RunStore()
        first = store.append_turn_messages("w-1", "hi", "hello")
        self.assertEqual(first, [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}])
        second = store.append_turn_messages("w-1", "how are you", "good")
        self.assertEqual(len(second), 4)
        self.assertEqual(second[:2], first)

    def test_append_turn_messages_isolated_per_webui_session(self):
        store = ra.RunStore()
        store.append_turn_messages("w-1", "a", "b")
        other = store.append_turn_messages("w-2", "c", "d")
        self.assertEqual(other, [{"role": "user", "content": "c"}, {"role": "assistant", "content": "d"}])


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

    def test_success_done_event_carries_a_session_object(self):
        # Regression for a live browser crash: WebUI's messages.js done-event
        # handler (_finishDone) unconditionally reads d.session.messages with
        # no null-check on d.session itself. A done payload with no `session`
        # key throws "Cannot read properties of undefined (reading 'messages')"
        # and the browser's stream is left stuck showing "processing" forever
        # — reproduced live against this exact code path before this fix.
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hello", "sessionId": "e-1"}):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi")
        record = self.store.get(run_id)
        done_payload = record["events"][1]["payload"]
        self.assertIn("session", done_payload)
        self.assertEqual(done_payload["session"]["session_id"], "w-1")
        self.assertEqual(
            done_payload["session"]["messages"],
            [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}],
        )

    def test_transcript_accumulates_across_turns_in_the_same_webui_session(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "a", "sessionId": "e-1"}):
            ra.run_turn(self.config, self.store, webui_session_id="w-1", message="first")
        with patch.object(ra, "forward_to_engram", return_value={"reply": "b", "sessionId": "e-1"}):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="second")
        messages = self.store.get(run_id)["events"][1]["payload"]["session"]["messages"]
        self.assertEqual(
            messages,
            [
                {"role": "user", "content": "first"},
                {"role": "assistant", "content": "a"},
                {"role": "user", "content": "second"},
                {"role": "assistant", "content": "b"},
            ],
        )

    def test_error_done_events_are_unchanged_by_the_session_fix(self):
        # apperror's own browser handler finalizes the stream and returns before
        # "done" is ever processed (it sets _streamFinalized itself), so the
        # crash above never applied to the error/rejection paths — this pins
        # that those still don't carry a session object, so no one "fixes" them
        # again believing the earlier bug applied here too.
        with patch.object(ra, "forward_to_engram", side_effect=engram_client.UpstreamError(401, b"nope")):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi")
        done_payload = self.store.get(run_id)["events"][1]["payload"]
        self.assertNotIn("session", done_payload)

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
        # Must be a status the WebUI's own SSE polling loop actually recognizes as
        # terminal (api/routes.py's _stream_runner_run_events checks this exact
        # string) — anything else leaves the browser's stream open forever
        # instead of showing the error. This used to be the (unrecognized)
        # "errored" and would have hung.
        self.assertEqual(record["status"], "error")
        self.assertEqual(record["events"][0]["event"], "apperror")

    def test_network_failure_surfaces_as_apperror_event_not_a_silent_success(self):
        with patch.object(ra, "forward_to_engram", side_effect=ConnectionRefusedError("nope")):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi")
        record = self.store.get(run_id)
        self.assertEqual(record["status"], "error")


class EffectiveModelFieldsTest(unittest.TestCase):
    def test_extracts_provider_and_basename_without_gguf_extension(self):
        result = {"trace": {"model": {
            "reasonProvider": "local",
            "reasonModel": "/var/lib/llama/models/Qwen3.6-35B-A3B-MTP-Q5_K_XL/Qwen3.6-35B-A3B-UD-Q5_K_XL.gguf",
        }}}
        model, provider = ra.effective_model_fields(result)
        self.assertEqual(provider, "local")
        self.assertEqual(model, "Qwen3.6-35B-A3B-UD-Q5_K_XL")

    def test_absent_when_no_llm_answered_the_turn(self):
        # A phrase-pool/SOCIAL turn: engram-engine's own trace has no reasonProvider.
        result = {"trace": {"model": {"reasonProvider": None, "reasonModel": None}}}
        self.assertEqual(ra.effective_model_fields(result), (None, None))

    def test_absent_when_trace_missing_entirely(self):
        self.assertEqual(ra.effective_model_fields({}), (None, None))

    def test_non_path_model_name_passed_through(self):
        result = {"trace": {"model": {"reasonProvider": "anthropic", "reasonModel": "claude-sonnet-4-6"}}}
        model, provider = ra.effective_model_fields(result)
        self.assertEqual(model, "claude-sonnet-4-6")
        self.assertEqual(provider, "anthropic")


class RunTurnReportsEffectiveModelTest(unittest.TestCase):
    """Regression for the composer's model chip permanently showing the static
    placeholder default ("GPT-5.4 Mini") instead of the backend that actually
    answered — traced to WebUI never being told an effective_model at all.
    """

    def setUp(self):
        self.config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.store = ra.RunStore()

    def test_success_records_effective_model_from_engram_trace(self):
        engram_result = {
            "reply": "hi", "sessionId": "e-1",
            "trace": {"model": {"reasonProvider": "local", "reasonModel": "/models/qwen3.6-35b-a3b.gguf"}},
        }
        with patch.object(ra, "forward_to_engram", return_value=engram_result):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi")
        record = self.store.get(run_id)
        self.assertEqual(record["effective_model"], "qwen3.6-35b-a3b")
        self.assertEqual(record["effective_model_provider"], "local")

    def test_success_with_no_llm_in_trace_records_no_effective_model(self):
        engram_result = {"reply": "Good afternoon.", "sessionId": "e-1", "trace": {"model": {}}}
        with patch.object(ra, "forward_to_engram", return_value=engram_result):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi")
        record = self.store.get(run_id)
        self.assertIsNone(record["effective_model"])
        self.assertIsNone(record["effective_model_provider"])


class UnsupportedInputMessageTest(unittest.TestCase):
    def test_attachments_only(self):
        msg = ra.unsupported_input_message([{"name": "a.png"}], [])
        self.assertIn("1 attachment", msg)
        self.assertNotIn("tool selection", msg)

    def test_multiple_attachments_pluralized(self):
        msg = ra.unsupported_input_message([{"name": "a"}, {"name": "b"}], [])
        self.assertIn("2 attachments", msg)

    def test_toolsets_only_names_them(self):
        msg = ra.unsupported_input_message([], ["web_search", "code_exec"])
        self.assertIn("tool selection (web_search, code_exec)", msg)

    def test_both_combined(self):
        msg = ra.unsupported_input_message([{"name": "a"}], ["web_search"])
        self.assertIn("attachment", msg)
        self.assertIn("tool selection", msg)


class RunTurnRejectsUnsupportedInputTest(unittest.TestCase):
    """Attachments/tool selections must be rejected before the Director is
    ever invoked — not silently dropped, not processed as if they'd been
    honored.
    """

    def setUp(self):
        self.config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.store = ra.RunStore()

    def test_attachments_reject_without_calling_engram(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            run_id = ra.run_turn(
                self.config, self.store, webui_session_id="w-1", message="hi",
                attachments=[{"name": "photo.png"}],
            )
            mocked.assert_not_called()
        record = self.store.get(run_id)
        self.assertEqual(record["status"], "error")
        self.assertEqual(record["events"][0]["event"], "apperror")
        self.assertEqual(record["events"][0]["payload"]["type"], "unsupported_input")
        self.assertIn("attachment", record["events"][0]["payload"]["message"])
        self.assertEqual(record["events"][1]["event"], "done")
        self.assertEqual(record["events"][1]["payload"]["status"], "error")

    def test_toolsets_reject_without_calling_engram(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            run_id = ra.run_turn(
                self.config, self.store, webui_session_id="w-1", message="hi",
                toolsets=["web_search"],
            )
            mocked.assert_not_called()
        record = self.store.get(run_id)
        self.assertEqual(record["events"][0]["payload"]["type"], "unsupported_input")
        self.assertIn("tool selection", record["events"][0]["payload"]["message"])

    def test_plain_message_with_no_attachments_or_toolsets_is_unaffected(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi back", "sessionId": "e-1"}) as mocked:
            run_id = ra.run_turn(
                self.config, self.store, webui_session_id="w-1", message="hi",
                attachments=[], toolsets=[],
            )
            mocked.assert_called_once()
        record = self.store.get(run_id)
        self.assertEqual(record["status"], "completed")
        self.assertEqual(record["events"][0]["event"], "token")


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

    def test_effective_model_surfaces_through_the_real_start_response(self):
        engram_result = {
            "reply": "hi", "sessionId": "e-9",
            "trace": {"model": {"reasonProvider": "local", "reasonModel": "/models/qwen3.6-35b-a3b.gguf"}},
        }
        with patch.object(ra, "forward_to_engram", return_value=engram_result):
            conn = self._conn()
            body = json.dumps({"session_id": "webui-session-model", "message": "hello"}).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            start_payload = json.loads(resp.read())
        self.assertEqual(start_payload["effective_model"], "qwen3.6-35b-a3b")
        self.assertEqual(start_payload["effective_model_provider"], "local")

    def test_no_effective_model_keys_when_engram_reports_none(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-9"}):
            conn = self._conn()
            body = json.dumps({"session_id": "webui-session-nomodel", "message": "hello"}).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            start_payload = json.loads(resp.read())
        self.assertNotIn("effective_model", start_payload)
        self.assertNotIn("effective_model_provider", start_payload)

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

    def test_attachment_through_the_real_endpoint_is_rejected_not_forwarded(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            conn = self._conn()
            body = json.dumps({
                "session_id": "webui-session-attach",
                "message": "please look at this file",
                "attachments": [{"name": "screenshot.png"}],
            }).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            # The run itself still "starts" successfully at the HTTP layer — the
            # rejection is a run event (apperror+done), the existing mechanism
            # the native chat UI already renders errors through — not an HTTP
            # failure that would surface as a raw/opaque error.
            self.assertEqual(resp.status, 200)
            start_payload = json.loads(resp.read())
            run_id = start_payload["run_id"]
            mocked.assert_not_called()

            conn2 = self._conn()
            conn2.request("GET", f"/v1/runs/{run_id}/events?cursor=0", headers=self._auth_headers())
            events_payload = json.loads(conn2.getresponse().read())
            self.assertEqual(events_payload["events"][0]["event"], "apperror")
            self.assertEqual(events_payload["events"][0]["payload"]["type"], "unsupported_input")
            self.assertIn("attachment", events_payload["events"][0]["payload"]["message"])

            conn3 = self._conn()
            conn3.request("GET", f"/v1/runs/{run_id}", headers=self._auth_headers())
            status_payload = json.loads(conn3.getresponse().read())
            # Must be the exact terminal string WebUI's polling loop recognizes,
            # or the browser-facing stream never receives stream_end.
            self.assertEqual(status_payload["status"], "error")
            self.assertEqual(status_payload["terminal_state"], "error")

    def test_toolsets_through_the_real_endpoint_is_rejected_not_forwarded(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            conn = self._conn()
            body = json.dumps({
                "session_id": "webui-session-tools",
                "message": "search the web for this",
                "toolsets": ["web_search"],
            }).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            self.assertEqual(resp.status, 200)
            run_id = json.loads(resp.read())["run_id"]
            mocked.assert_not_called()

            conn2 = self._conn()
            conn2.request("GET", f"/v1/runs/{run_id}/events?cursor=0", headers=self._auth_headers())
            events_payload = json.loads(conn2.getresponse().read())
            self.assertEqual(events_payload["events"][0]["payload"]["type"], "unsupported_input")
            self.assertIn("tool selection", events_payload["events"][0]["payload"]["message"])


if __name__ == "__main__":
    unittest.main()
