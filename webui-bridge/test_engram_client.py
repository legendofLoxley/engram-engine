"""Focused tests for engram_client.py (pure logic, no HTTP server)."""
from __future__ import annotations

import unittest

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


if __name__ == "__main__":
    unittest.main()
