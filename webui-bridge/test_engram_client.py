"""Focused tests for engram_client.py (pure logic, no HTTP server)."""
from __future__ import annotations

import io
import unittest
import urllib.error
from unittest.mock import patch

import engram_client as ec


class LoadEngramConfigTest(unittest.TestCase):
    def test_missing_token_raises(self):
        with self.assertRaises(ec.ConfigError):
            ec.load_engram_config({})

    def test_blank_token_raises(self):
        with self.assertRaises(ec.ConfigError):
            ec.load_engram_config({"ENGRAM_DEBUG_TOKEN": "   "})

    def test_defaults_applied(self):
        cfg = ec.load_engram_config({"ENGRAM_DEBUG_TOKEN": "secret"})
        self.assertEqual(cfg["engram_base_url"], "http://127.0.0.1:8082")
        self.assertEqual(cfg["synthetic_user_id"], "webui-dev")


class BuildEngramPayloadTest(unittest.TestCase):
    def test_forces_configured_identity(self):
        payload = ec.build_engram_payload("hi", "webui-dev")
        self.assertEqual(payload["syntheticUserId"], "webui-dev")

    def test_omits_session_id_when_absent(self):
        payload = ec.build_engram_payload("hi", "webui-dev", session_id=None)
        self.assertNotIn("sessionId", payload)

    def test_includes_session_id_when_present(self):
        payload = ec.build_engram_payload("hi", "webui-dev", session_id="s-1")
        self.assertEqual(payload["sessionId"], "s-1")

    def test_rejects_blank_message(self):
        with self.assertRaises(ValueError):
            ec.build_engram_payload("   ", "webui-dev")

    def test_rejects_non_string_message(self):
        with self.assertRaises(ValueError):
            ec.build_engram_payload(None, "webui-dev")  # type: ignore[arg-type]


class _FakeResponse:
    def __init__(self, body: bytes):
        self._body = body

    def read(self) -> bytes:
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FetchHermesAssignmentCompletionTest(unittest.TestCase):
    def test_200_returns_the_parsed_completion(self):
        body = b'{"assignmentId":"a1","outcome":"Completed","text":"DH-FIXTURE-abc123","graphIngestOutcome":"Committed"}'
        with patch("urllib.request.urlopen", return_value=_FakeResponse(body)):
            result = ec.fetch_hermes_assignment_completion("http://x", "tok", "a1", "webui-dev")
        self.assertEqual(result, {
            "assignmentId": "a1", "outcome": "Completed", "text": "DH-FIXTURE-abc123", "graphIngestOutcome": "Committed",
        })

    def test_404_is_not_found_yet_returns_none_not_an_exception(self):
        error = urllib.error.HTTPError("http://x", 404, "not found", {}, io.BytesIO(b""))
        try:
            with patch("urllib.request.urlopen", side_effect=error):
                result = ec.fetch_hermes_assignment_completion("http://x", "tok", "a1", "webui-dev")
            self.assertIsNone(result)
        finally:
            error.close()

    def test_connection_failure_returns_none_rather_than_raising(self):
        with patch("urllib.request.urlopen", side_effect=urllib.error.URLError("connection refused")):
            result = ec.fetch_hermes_assignment_completion("http://x", "tok", "a1", "webui-dev")
        self.assertIsNone(result)

    def test_malformed_json_body_returns_none_rather_than_raising(self):
        with patch("urllib.request.urlopen", return_value=_FakeResponse(b"not json")):
            result = ec.fetch_hermes_assignment_completion("http://x", "tok", "a1", "webui-dev")
        self.assertIsNone(result)

    def test_assignment_id_and_synthetic_user_id_are_url_encoded_into_the_request(self):
        captured = {}

        def _capture(req, timeout=None):
            captured["url"] = req.full_url
            captured["auth"] = req.get_header("Authorization")
            return _FakeResponse(b'{"outcome":"Completed","text":"x","graphIngestOutcome":"Committed"}')

        with patch("urllib.request.urlopen", side_effect=_capture):
            ec.fetch_hermes_assignment_completion("http://x", "secret-tok", "a1", "webui dev")
        self.assertIn("/debug/hermes-assignment/a1", captured["url"])
        self.assertIn("syntheticUserId=webui%20dev", captured["url"])
        self.assertEqual(captured["auth"], "Bearer secret-tok")


if __name__ == "__main__":
    unittest.main()
