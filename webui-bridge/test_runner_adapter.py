"""Focused tests for runner_adapter.py — the actual hermes-webui runner
contract implementation, including per-request authentication enforcement.
"""
from __future__ import annotations

import json
import threading
import time
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
        self.assertEqual(cfg["local_model_provider"], "local")
        self.assertIsNone(cfg["webui_sessions_dir"])
        self.assertIsNone(cfg["local_model_id"])

    def test_local_model_provider_overridable_and_lowercased(self):
        cfg = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k", "LOCAL_MODEL_PROVIDER": "Custom"})
        self.assertEqual(cfg["local_model_provider"], "custom")

    def test_local_model_id_passed_through(self):
        cfg = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k", "LOCAL_MODEL_ID": "Qwen3.6-35B-A3B-UD-Q5_K_XL"})
        self.assertEqual(cfg["local_model_id"], "Qwen3.6-35B-A3B-UD-Q5_K_XL")

    def test_webui_sessions_dir_passed_through(self):
        cfg = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k", "WEBUI_SESSIONS_DIR": "/tmp/sessions"})
        self.assertEqual(cfg["webui_sessions_dir"], "/tmp/sessions")


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

    def test_seed_is_used_only_on_first_reference_to_a_session(self):
        # Regression: this in-memory store must never be the SOLE source of a
        # session's saved history. A seed (WebUI's own already-persisted
        # transcript) primes an unseen session so a restarted adapter's empty
        # memory does not later overwrite a longer, already-durable
        # conversation with a done event carrying only the new turn.
        store = ra.RunStore()
        seed = [{"role": "user", "content": "earlier"}, {"role": "assistant", "content": "reply"}]
        first = store.append_turn_messages("w-1", "hi", "hello", seed=seed)
        self.assertEqual(first, seed + [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}])

    def test_seed_ignored_once_session_already_known_in_memory(self):
        store = ra.RunStore()
        store.append_turn_messages("w-1", "first", "a")
        # A seed passed on a LATER turn for an already-tracked session must not
        # re-prepend/duplicate — in-memory state, once established, is authoritative
        # for the rest of this process's life.
        second = store.append_turn_messages("w-1", "second", "b", seed=[{"role": "user", "content": "unrelated"}])
        self.assertEqual(
            second,
            [
                {"role": "user", "content": "first"}, {"role": "assistant", "content": "a"},
                {"role": "user", "content": "second"}, {"role": "assistant", "content": "b"},
            ],
        )

    def test_empty_seed_does_not_error(self):
        store = ra.RunStore()
        result = store.append_turn_messages("w-1", "hi", "hello", seed=[])
        self.assertEqual(result, [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}])


class LoadPersistedWebuiMessagesTest(unittest.TestCase):
    def test_no_sessions_dir_configured_returns_empty(self):
        self.assertEqual(ra.load_persisted_webui_messages(None, "w-1"), [])

    def test_missing_file_returns_empty(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(ra.load_persisted_webui_messages(d, "no-such-session"), [])

    def test_reads_messages_from_a_real_webui_session_file(self):
        import tempfile, os as _os
        with tempfile.TemporaryDirectory() as d:
            with open(_os.path.join(d, "w-1.json"), "w") as f:
                json.dump({
                    "session_id": "w-1",
                    "messages": [
                        {"role": "user", "content": "hi", "timestamp": 123},
                        {"role": "assistant", "content": "hello", "extra_field": "ignored"},
                    ],
                }, f)
            result = ra.load_persisted_webui_messages(d, "w-1")
        self.assertEqual(result, [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}])

    def test_malformed_json_returns_empty_not_raises(self):
        import tempfile, os as _os
        with tempfile.TemporaryDirectory() as d:
            with open(_os.path.join(d, "w-1.json"), "w") as f:
                f.write("{not json")
            self.assertEqual(ra.load_persisted_webui_messages(d, "w-1"), [])

    def test_non_list_messages_field_returns_empty(self):
        import tempfile, os as _os
        with tempfile.TemporaryDirectory() as d:
            with open(_os.path.join(d, "w-1.json"), "w") as f:
                json.dump({"session_id": "w-1", "messages": "not-a-list"}, f)
            self.assertEqual(ra.load_persisted_webui_messages(d, "w-1"), [])

    def test_malformed_entries_within_messages_are_skipped(self):
        import tempfile, os as _os
        with tempfile.TemporaryDirectory() as d:
            with open(_os.path.join(d, "w-1.json"), "w") as f:
                json.dump({"session_id": "w-1", "messages": [
                    {"role": "user", "content": "ok"},
                    {"role": "assistant"},  # missing content
                    "not-a-dict",
                    {"content": "missing role"},
                ]}, f)
            result = ra.load_persisted_webui_messages(d, "w-1")
        self.assertEqual(result, [{"role": "user", "content": "ok"}])


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

    def test_upstream_failure_done_events_omit_session(self):
        # apperror's own browser handler finalizes the stream and returns
        # before "done" is ever processed (it sets _streamFinalized itself),
        # so the browser-crash bug this once guarded against never applied
        # here — this pins that a genuine upstream/network failure (nothing
        # the Director actually said, transient, retry-worthy) still doesn't
        # carry a session object. Deliberately NOT the same as a rejection
        # (attachments/toolsets/model) — see
        # RunTurnRejectsUnsupportedInputTest / …ModelProviderTest, which DO
        # carry a session so a reload doesn't silently drop a rejected turn.
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
        # Regression: messages.js's apperror handler only renders the message
        # into the visible transcript when the event's session_id matches the
        # browser's current session — omitted entirely, it never matches, and
        # the error is accepted by the stream but shown nowhere (reproduced
        # live as a blank assistant bubble before this field was added).
        self.assertEqual(record["events"][0]["payload"]["session_id"], "w-1")

    def test_network_failure_surfaces_as_apperror_event_not_a_silent_success(self):
        with patch.object(ra, "forward_to_engram", side_effect=ConnectionRefusedError("nope")):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi")
        record = self.store.get(run_id)
        self.assertEqual(record["status"], "error")
        self.assertEqual(record["events"][0]["payload"]["session_id"], "w-1")


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
        self.assertEqual(record["events"][0]["payload"]["session_id"], "w-1")
        self.assertEqual(record["events"][1]["event"], "done")
        self.assertEqual(record["events"][1]["payload"]["status"], "error")

    def test_rejected_turn_done_event_carries_a_session_so_reload_does_not_lose_it(self):
        # A rejected turn is still something the user typed and saw a response
        # to — omitting it from session.messages meant a page reload silently
        # erased both, even though the successful-turn transcript persisted
        # correctly (reproduced live: reload after a rejected cloud-model pick
        # made that whole exchange vanish).
        with patch.object(ra, "forward_to_engram") as mocked:
            run_id = ra.run_turn(
                self.config, self.store, webui_session_id="w-1", message="describe this photo",
                attachments=[{"name": "photo.png"}],
            )
            mocked.assert_not_called()
        done_payload = self.store.get(run_id)["events"][1]["payload"]
        self.assertIn("session", done_payload)
        self.assertEqual(done_payload["session"]["session_id"], "w-1")
        self.assertEqual(done_payload["session"]["messages"][0], {"role": "user", "content": "describe this photo"})
        self.assertIn("attachment", done_payload["session"]["messages"][1]["content"])

    def test_rejected_turn_transcript_accumulates_with_later_successful_turns(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            ra.run_turn(self.config, self.store, webui_session_id="w-1", message="first", attachments=[{"name": "a.png"}])
            mocked.assert_not_called()
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-1"}):
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="second")
        messages = self.store.get(run_id)["events"][1]["payload"]["session"]["messages"]
        self.assertEqual(len(messages), 4)
        self.assertEqual(messages[2], {"role": "user", "content": "second"})
        self.assertEqual(messages[3], {"role": "assistant", "content": "hi"})

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


class UnsupportedProviderMessageTest(unittest.TestCase):
    def test_names_both_provider_and_model_when_given(self):
        msg = ra.unsupported_provider_message("anthropic", "local", "claude-sonnet-4-6")
        self.assertIn("anthropic", msg)
        self.assertIn("claude-sonnet-4-6", msg)
        self.assertIn("local", msg)
        self.assertIn("no cloud model was invoked", msg)

    def test_falls_back_to_provider_only_when_no_model_given(self):
        msg = ra.unsupported_provider_message("anthropic", "local", None)
        self.assertIn("provider 'anthropic'", msg)


class RunTurnRejectsUnsupportedModelProviderTest(unittest.TestCase):
    """Selecting a cloud provider must not silently do nothing, and must not
    let the local backend answer while implying a cloud model was invoked —
    it has to be rejected the same visible way attachments/toolsets are.
    """

    def setUp(self):
        self.config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.store = ra.RunStore()

    def test_cloud_provider_pick_rejected_without_calling_engram(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            run_id = ra.run_turn(
                self.config, self.store, webui_session_id="w-1", message="hi",
                model="claude-sonnet-4-6", model_provider="anthropic",
            )
            mocked.assert_not_called()
        record = self.store.get(run_id)
        self.assertEqual(record["status"], "error")
        self.assertEqual(record["events"][0]["event"], "apperror")
        self.assertEqual(record["events"][0]["payload"]["type"], "unsupported_model")
        self.assertIn("anthropic", record["events"][0]["payload"]["message"])
        self.assertEqual(record["events"][0]["payload"]["session_id"], "w-1")
        self.assertEqual(record["events"][1]["payload"]["status"], "error")

    def test_provider_matching_local_identity_is_allowed(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi back", "sessionId": "e-1"}) as mocked:
            run_id = ra.run_turn(
                self.config, self.store, webui_session_id="w-1", message="hi",
                model="Qwen3.6-35B-A3B-UD-Q5_K_XL", model_provider="local",
            )
            mocked.assert_called_once()
        self.assertEqual(self.store.get(run_id)["status"], "completed")

    def test_provider_match_is_case_insensitive(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi back", "sessionId": "e-1"}) as mocked:
            ra.run_turn(
                self.config, self.store, webui_session_id="w-1", message="hi",
                model_provider="Local",
            )
            mocked.assert_called_once()

    def test_blank_provider_is_allowed_not_rejected(self):
        # The common case: a brand-new or never-explicitly-changed session
        # sends no provider at all. This must behave exactly as before — the
        # local backend answers, it is never treated as an unsupported pick.
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi back", "sessionId": "e-1"}) as mocked:
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi", model_provider=None)
            mocked.assert_called_once()
        self.assertEqual(self.store.get(run_id)["status"], "completed")

    def test_custom_local_provider_id_is_configurable(self):
        config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k", "LOCAL_MODEL_PROVIDER": "custom"})
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-1"}) as mocked:
            ra.run_turn(config, self.store, webui_session_id="w-1", message="hi", model_provider="custom")
            mocked.assert_called_once()
        with patch.object(ra, "forward_to_engram") as mocked2:
            run_id = ra.run_turn(config, self.store, webui_session_id="w-2", message="hi", model_provider="local")
            mocked2.assert_not_called()
        self.assertEqual(self.store.get(run_id)["status"], "error")


class RunTurnRejectsUnsupportedModelIdTest(unittest.TestCase):
    """Regression: this dev WebUI instance has no cloud provider configured
    at all, so its only way to pick a different model is the composer's
    free-text "Custom Model ID" field — which sends the typed value as
    "model" with NO "provider" field (confirmed live: typing
    "anthropic/claude-sonnet-4-6" there and sending sailed straight through
    to the local Director, because the provider-only check saw a blank
    provider and allowed it). LOCAL_MODEL_ID closes that gap.
    """

    def setUp(self):
        self.config = ra.load_runner_config({
            "ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k",
            "LOCAL_MODEL_ID": "Qwen3.6-35B-A3B-UD-Q5_K_XL",
        })
        self.store = ra.RunStore()

    def test_custom_model_id_with_no_provider_field_is_rejected(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            run_id = ra.run_turn(
                self.config, self.store, webui_session_id="w-1", message="hi",
                model="anthropic/claude-sonnet-4-6", model_provider=None,
            )
            mocked.assert_not_called()
        record = self.store.get(run_id)
        self.assertEqual(record["status"], "error")
        self.assertEqual(record["events"][0]["payload"]["type"], "unsupported_model")
        self.assertIn("claude-sonnet-4-6", record["events"][0]["payload"]["message"])

    def test_matching_local_model_id_is_allowed(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-1"}) as mocked:
            ra.run_turn(
                self.config, self.store, webui_session_id="w-1", message="hi",
                model="Qwen3.6-35B-A3B-UD-Q5_K_XL",
            )
            mocked.assert_called_once()

    def test_matching_local_model_id_is_case_insensitive(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-1"}) as mocked:
            ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi", model="qwen3.6-35b-a3b-ud-q5_k_xl")
            mocked.assert_called_once()

    def test_blank_model_is_allowed_not_rejected(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-1"}) as mocked:
            ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hi", model=None)
            mocked.assert_called_once()

    def test_no_local_model_id_configured_skips_this_check(self):
        # Without LOCAL_MODEL_ID set, model text is not checked at all — only
        # the provider-based check (RunTurnRejectsUnsupportedModelProviderTest)
        # applies, preserving prior behavior for operators who don't set it.
        config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-1"}) as mocked:
            ra.run_turn(config, self.store, webui_session_id="w-1", message="hi", model="anthropic/claude-sonnet-4-6")
            mocked.assert_called_once()


class RunTurnSeedsTranscriptFromWebuiPersistedSessionTest(unittest.TestCase):
    """Confirms run_turn actually wires load_persisted_webui_messages into the
    transcript it reports, using config['webui_sessions_dir'] — not just that
    the two pieces work in isolation.
    """

    def test_first_turn_after_restart_prepends_webui_persisted_history(self):
        import tempfile, os as _os
        with tempfile.TemporaryDirectory() as d:
            with open(_os.path.join(d, "w-1.json"), "w") as f:
                json.dump({"session_id": "w-1", "messages": [
                    {"role": "user", "content": "earlier turn"},
                    {"role": "assistant", "content": "earlier reply"},
                ]}, f)
            config = ra.load_runner_config({
                "ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k", "WEBUI_SESSIONS_DIR": d,
            })
            store = ra.RunStore()
            with patch.object(ra, "forward_to_engram", return_value={"reply": "new reply", "sessionId": "e-1"}):
                run_id = ra.run_turn(config, store, webui_session_id="w-1", message="new turn")
            transcript = store.get(run_id)["events"][1]["payload"]["session"]["messages"]
        self.assertEqual(
            transcript,
            [
                {"role": "user", "content": "earlier turn"},
                {"role": "assistant", "content": "earlier reply"},
                {"role": "user", "content": "new turn"},
                {"role": "assistant", "content": "new reply"},
            ],
        )

    def test_no_sessions_dir_configured_behaves_exactly_as_before(self):
        config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        store = ra.RunStore()
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-1"}):
            run_id = ra.run_turn(config, store, webui_session_id="w-1", message="hello")
        transcript = store.get(run_id)["events"][1]["payload"]["session"]["messages"]
        self.assertEqual(transcript, [{"role": "user", "content": "hello"}, {"role": "assistant", "content": "hi"}])


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

    def test_cancel_for_an_unknown_run_reports_unknown_run_not_a_generic_unsupported_message(self):
        conn = self._conn()
        conn.request("POST", "/v1/runs/whatever/cancel", body=b"{}", headers=self._auth_headers())
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        payload = json.loads(resp.read())
        self.assertFalse(payload["ok"])
        self.assertEqual(payload["status"], "unknown_run")

    def test_cancel_for_an_already_terminal_run_honestly_reports_that_rather_than_a_hardcoded_message(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-cancel-terminal"}):
            conn = self._conn()
            body = json.dumps({"session_id": "webui-session-cancel-terminal", "message": "hello"}).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            run_id = json.loads(resp.read())["run_id"]

        conn2 = self._conn()
        conn2.request("POST", f"/v1/runs/{run_id}/cancel", body=b"{}", headers=self._auth_headers())
        resp2 = conn2.getresponse()
        self.assertEqual(resp2.status, 200)
        payload = json.loads(resp2.read())
        self.assertFalse(payload["ok"])
        self.assertEqual(payload["status"], "already_terminal")

    def test_cancel_for_a_genuinely_pending_delegation_run_requests_it_never_claims_already_completed(self):
        engram_result = {
            "reply": "I've asked Hermes to look into it.",
            "sessionId": "e-cancel-pending",
            "trace": {"hermesDelegation": {"assignmentId": "assign-pending", "task": "read the fixture"}},
        }
        with patch.object(ra, "forward_to_engram", return_value=engram_result), \
             patch.object(ra, "threading"):  # never actually spawn the delivery thread for this test
            conn = self._conn()
            body = json.dumps({"session_id": "webui-session-cancel-pending", "message": "check the fixture"}).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            run_id = json.loads(resp.read())["run_id"]

        with patch.object(ra, "request_hermes_cancellation", return_value={"assignmentId": "assign-pending", "requested": True}) as mocked:
            conn2 = self._conn()
            conn2.request("POST", f"/v1/runs/{run_id}/cancel", body=b"{}", headers=self._auth_headers())
            resp2 = conn2.getresponse()
            self.assertEqual(resp2.status, 200)
            payload = json.loads(resp2.read())
            self.assertTrue(payload["ok"])
            self.assertEqual(payload["status"], "cancellation_requested")
            mocked.assert_called_once()
            self.assertEqual(mocked.call_args.args[2], "assign-pending", "the cancel request must target the run's own recorded assignment id")

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

    def test_cloud_provider_through_the_real_endpoint_is_rejected_not_forwarded(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            conn = self._conn()
            body = json.dumps({
                "session_id": "webui-session-cloud",
                "message": "hello",
                "model": "gpt-5.5",
                "provider": "openai",
            }).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            self.assertEqual(resp.status, 200)
            run_id = json.loads(resp.read())["run_id"]
            mocked.assert_not_called()

            conn2 = self._conn()
            conn2.request("GET", f"/v1/runs/{run_id}/events?cursor=0", headers=self._auth_headers())
            events_payload = json.loads(conn2.getresponse().read())
            self.assertEqual(events_payload["events"][0]["payload"]["type"], "unsupported_model")
            self.assertIn("openai", events_payload["events"][0]["payload"]["message"])

            conn3 = self._conn()
            conn3.request("GET", f"/v1/runs/{run_id}", headers=self._auth_headers())
            status_payload = json.loads(conn3.getresponse().read())
            self.assertEqual(status_payload["status"], "error")
            self.assertEqual(status_payload["terminal_state"], "error")

    def test_matching_local_provider_through_the_real_endpoint_succeeds(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-9"}) as mocked:
            conn = self._conn()
            body = json.dumps({
                "session_id": "webui-session-local-provider",
                "message": "hello",
                "model": "Qwen3.6-35B-A3B-UD-Q5_K_XL",
                "provider": "local",
            }).encode()
            conn.request("POST", "/v1/runs", body=body, headers=self._auth_headers())
            resp = conn.getresponse()
            self.assertEqual(resp.status, 200)
            self.assertEqual(json.loads(resp.read())["status"], "completed")
            mocked.assert_called_once()

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


class RunnerHttpWithLocalModelIdConfiguredIntegrationTest(unittest.TestCase):
    """A separate real-HTTP server instance with LOCAL_MODEL_ID set, exercising
    the free-text-Custom-Model-ID scenario reproduced live in the browser: a
    typed "provider/model" string with no "provider" field in the request.
    """

    def setUp(self):
        self.config = ra.load_runner_config({
            "ENGRAM_DEBUG_TOKEN": "engram-secret",
            "RUNNER_API_KEY": "runner-secret",
            "RUNNER_PORT": "0",
            "LOCAL_MODEL_ID": "Qwen3.6-35B-A3B-UD-Q5_K_XL",
        })
        self.store = ra.RunStore()
        handler = ra.make_handler(self.config, self.store)
        self.httpd = ra.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def test_custom_model_id_field_with_no_provider_is_rejected_through_the_real_endpoint(self):
        with patch.object(ra, "forward_to_engram") as mocked:
            conn = HTTPConnection("127.0.0.1", self.port, timeout=5)
            body = json.dumps({
                "session_id": "webui-session-custom-model-id",
                "message": "hello",
                "model": "anthropic/claude-sonnet-4-6",
                # No "provider" key at all — exactly what the browser sent
                # when this was reproduced live via the Custom Model ID field.
            }).encode()
            conn.request("POST", "/v1/runs", body=body, headers={
                "Content-Type": "application/json", "Authorization": "Bearer runner-secret",
            })
            resp = conn.getresponse()
            self.assertEqual(resp.status, 200)
            run_id = json.loads(resp.read())["run_id"]
            mocked.assert_not_called()

            conn2 = HTTPConnection("127.0.0.1", self.port, timeout=5)
            conn2.request("GET", f"/v1/runs/{run_id}/events?cursor=0", headers={"Authorization": "Bearer runner-secret"})
            events_payload = json.loads(conn2.getresponse().read())
            self.assertEqual(events_payload["events"][0]["payload"]["type"], "unsupported_model")
            self.assertIn("claude-sonnet-4-6", events_payload["events"][0]["payload"]["message"])


class RunTurnHermesDelegationTest(unittest.TestCase):
    """A turn whose /debug/converse response names a Hermes assignment
    (PipelineTrace.hermesDelegation) must NOT finalize the run immediately —
    see PENDING_HERMES_STATUS's doc for why that's what makes automatic,
    no-further-user-message delivery possible at all.
    """

    def setUp(self):
        self.config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.store = ra.RunStore()

    def test_a_delegation_turn_leaves_the_run_running_with_no_done_event_yet_and_spawns_exactly_one_delivery_thread(self):
        engram_result = {
            "reply": "I've asked Hermes to look into it.",
            "sessionId": "e-1",
            "trace": {"hermesDelegation": {"assignmentId": "assign-1", "task": "read the fixture"}},
        }
        with patch.object(ra, "forward_to_engram", return_value=engram_result), \
             patch.object(ra, "threading") as mock_threading:
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="check the fixture")

        record = self.store.get(run_id)
        self.assertEqual(record["status"], ra.PENDING_HERMES_STATUS)
        self.assertEqual(len(record["events"]), 1)
        self.assertEqual(record["events"][0]["event"], "token")
        self.assertEqual(record["events"][0]["payload"]["text"], engram_result["reply"])

        mock_threading.Thread.assert_called_once()
        _, kwargs = mock_threading.Thread.call_args
        self.assertIs(kwargs["target"], ra._deliver_hermes_completion)
        thread_args = kwargs["args"]
        self.assertEqual(thread_args[2], run_id)
        self.assertEqual(thread_args[3], "w-1")
        self.assertEqual(thread_args[4], "assign-1")
        self.assertTrue(kwargs.get("daemon"))
        # dispatch_time lets the delivery thread notice a confirmed backend restart
        # itself, without needing the adapter to also restart first — see
        # _deliver_hermes_completion's own doc.
        self.assertIsInstance(kwargs["kwargs"]["dispatch_time"], (int, float))
        mock_threading.Thread.return_value.start.assert_called_once()

    def test_a_turn_with_no_delegation_in_the_trace_behaves_exactly_as_before(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-1"}), \
             patch.object(ra, "threading") as mock_threading:
            run_id = ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hey")

        record = self.store.get(run_id)
        self.assertEqual(record["status"], ra.TERMINAL_COMPLETED_STATUS)
        self.assertEqual(record["events"][1]["event"], "done")
        mock_threading.Thread.assert_not_called()


class HandleCancelRequestTest(unittest.TestCase):
    """Tests handle_cancel_request directly, injecting request_hermes_cancellation via
    patch.object the same way DeliverHermesCompletionTest injects fetch_fn — no real network
    calls, no real thread.
    """

    def setUp(self):
        self.config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.store = ra.RunStore()

    def test_unknown_run_id_is_reported_honestly_not_as_a_generic_unsupported_message(self):
        result = ra.handle_cancel_request(self.config, self.store, "never-created")
        self.assertFalse(result["ok"])
        self.assertEqual(result["status"], "unknown_run")

    def test_already_terminal_run_is_reported_honestly(self):
        run_id = self.store.create(
            webui_session_id="w-1",
            events=[{"event": "done", "seq": 1, "payload": {"status": ra.TERMINAL_COMPLETED_STATUS}}],
            status=ra.TERMINAL_COMPLETED_STATUS,
        )
        result = ra.handle_cancel_request(self.config, self.store, run_id)
        self.assertFalse(result["ok"])
        self.assertEqual(result["status"], "already_terminal")

    def test_genuinely_pending_run_with_no_assignment_id_never_claims_a_cancellation_it_cannot_act_on(self):
        run_id = self.store.create(webui_session_id="w-1", events=[], status=ra.PENDING_HERMES_STATUS)
        result = ra.handle_cancel_request(self.config, self.store, run_id)
        self.assertFalse(result["ok"])
        self.assertEqual(result["status"], "no_assignment")

    def test_genuinely_pending_run_requests_cancellation_and_never_says_already_completed(self):
        run_id = self.store.create(webui_session_id="w-1", events=[], status=ra.PENDING_HERMES_STATUS, assignment_id="assign-1")
        with patch.object(ra, "request_hermes_cancellation", return_value={"assignmentId": "assign-1", "requested": True}):
            result = ra.handle_cancel_request(self.config, self.store, run_id)
        self.assertTrue(result["ok"])
        self.assertEqual(result["status"], "cancellation_requested")
        self.assertNotIn("already completed", result["message"])

    def test_pending_run_where_engram_reports_too_late_is_reported_honestly_not_as_a_success(self):
        run_id = self.store.create(webui_session_id="w-1", events=[], status=ra.PENDING_HERMES_STATUS, assignment_id="assign-1")
        with patch.object(ra, "request_hermes_cancellation", return_value={"assignmentId": "assign-1", "requested": False}):
            result = ra.handle_cancel_request(self.config, self.store, run_id)
        self.assertFalse(result["ok"])
        self.assertEqual(result["status"], "too_late")

    def test_pending_run_where_engram_is_unreachable_is_reported_honestly_not_as_a_success_or_as_already_completed(self):
        run_id = self.store.create(webui_session_id="w-1", events=[], status=ra.PENDING_HERMES_STATUS, assignment_id="assign-1")
        with patch.object(ra, "request_hermes_cancellation", return_value=None):
            result = ra.handle_cancel_request(self.config, self.store, run_id)
        self.assertFalse(result["ok"])
        self.assertEqual(result["status"], "unreachable")

    def test_isolation_from_other_runs_cancelling_one_pending_run_leaves_a_different_one_untouched(self):
        run_a = self.store.create(webui_session_id="w-a", events=[], status=ra.PENDING_HERMES_STATUS, assignment_id="assign-a")
        run_b = self.store.create(webui_session_id="w-b", events=[], status=ra.PENDING_HERMES_STATUS, assignment_id="assign-b")

        with patch.object(ra, "request_hermes_cancellation", return_value={"assignmentId": "assign-a", "requested": True}) as mocked:
            result = ra.handle_cancel_request(self.config, self.store, run_a)
        self.assertTrue(result["ok"])
        mocked.assert_called_once()
        self.assertEqual(mocked.call_args.args[2], "assign-a")

        # run_b's own record must be completely unaffected — still pending, own assignment id intact.
        record_b = self.store.get(run_b)
        self.assertEqual(record_b["status"], ra.PENDING_HERMES_STATUS)
        self.assertEqual(record_b["assignment_id"], "assign-b")


class InterruptionDirectorTest(unittest.TestCase):
    """InterruptionDirector is the one place this adapter decides/authors
    interruption wording — this pins its exact output per reason, independent of
    whatever calls it (_deliver_hermes_completion, reconcile_interrupted_assignments).
    """

    def test_confirmed_backend_restart_never_claims_success_or_a_definite_stop_or_failure(self):
        text = ra.InterruptionDirector.decide(ra.InterruptionReason.CONFIRMED_BACKEND_RESTART)
        self.assertIn("backend restarted", text)
        self.assertIn("no way for me to confirm", text)
        for forbidden in ("Hermes finished", "stopped", "failed"):
            self.assertNotIn(forbidden, text)

    def test_backend_unreachable_is_distinct_wording_from_confirmed_restart(self):
        text = ra.InterruptionDirector.decide(ra.InterruptionReason.BACKEND_UNREACHABLE)
        self.assertIn("can't currently reach", text)
        self.assertNotIn("restarted", text, "must not claim a confirmed restart when the truth is merely 'unreachable'")

    def test_no_response_in_time_preserves_the_original_pre_existing_wording(self):
        text = ra.InterruptionDirector.decide(ra.InterruptionReason.NO_RESPONSE_IN_TIME)
        self.assertIn("hasn't reported back", text)

    def test_unknown_reason_raises_rather_than_silently_composing_something(self):
        with self.assertRaises(ValueError):
            ra.InterruptionDirector.decide("not-a-real-reason")


class BackendConfirmedRestartedSinceTest(unittest.TestCase):
    def test_true_when_reported_start_time_is_after_dispatch(self):
        with patch.object(ra, "time") as mock_time:
            mock_time.time.return_value = 2000.0  # now=2000, uptime=5 -> started at 1995
            result = ra._backend_confirmed_restarted_since("http://x", dispatch_time=1000.0, health_fn=lambda base_url: (True, 5.0))
        self.assertTrue(result)

    def test_false_when_reported_start_time_is_before_dispatch_same_instance(self):
        with patch.object(ra, "time") as mock_time:
            mock_time.time.return_value = 1050.0  # now=1050, uptime=100 -> started at 950
            result = ra._backend_confirmed_restarted_since("http://x", dispatch_time=1000.0, health_fn=lambda base_url: (True, 100.0))
        self.assertFalse(result)

    def test_false_when_unreachable_never_claims_a_restart_it_cannot_confirm(self):
        result = ra._backend_confirmed_restarted_since("http://x", dispatch_time=1000.0, health_fn=lambda base_url: (False, None))
        self.assertFalse(result)


class DeliverHermesCompletionLiveRestartDetectionTest(unittest.TestCase):
    """Covers the acceptance gap a plain adapter-only restart doesn't exercise:
    restarting ONLY engram-dev.service while this thread is already running and
    polling. Without dispatch_time/health_fn, this thread would poll uselessly
    for the full max_wait_seconds before falling back to the generic
    NO_RESPONSE_IN_TIME message — these tests pin the faster, more specific path.
    """

    def setUp(self):
        self.store = ra.RunStore()
        self.config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.ack_text = "I've asked Hermes to look into it."
        transcript = self.store.append_turn_messages("w-1", "check the fixture", self.ack_text)
        self.ack_index = len(transcript) - 1
        self.run_id = self.store.create(
            webui_session_id="w-1", events=[{"event": "token", "seq": 1, "payload": {"text": self.ack_text}}],
            status=ra.PENDING_HERMES_STATUS,
        )

    def test_a_confirmed_restart_detected_mid_poll_delivers_promptly_not_after_the_full_timeout(self):
        calls = {"health": 0}

        def _health(base_url):
            calls["health"] += 1
            return True, 5.0  # backend uptime 5s — far less than this test's own elapsed dispatch age

        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            max_wait_seconds=30.0, poll_interval_seconds=0.01,  # would take 30s WITHOUT the early exit below
            fetch_fn=lambda *a, **k: None,
            dispatch_time=time.time() - 100.0,  # dispatched well before this "restarted" backend's own uptime
            health_fn=_health,
        )
        record = self.store.get(self.run_id)
        self.assertEqual(record["status"], ra.TERMINAL_ERROR_STATUS)
        self.assertIn("backend restarted", record["events"][1]["payload"]["text"])
        self.assertGreater(calls["health"], 0, "the health check must actually have been consulted")

    def test_same_instance_still_times_out_normally_with_the_generic_message(self):
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            max_wait_seconds=0.03, poll_interval_seconds=0.01,
            fetch_fn=lambda *a, **k: None,
            dispatch_time=time.time(),
            health_fn=lambda base_url: (True, 999.0),  # backend has been up far longer than dispatch_time — same instance
        )
        record = self.store.get(self.run_id)
        self.assertEqual(record["status"], ra.TERMINAL_ERROR_STATUS)
        self.assertIn("hasn't reported back", record["events"][1]["payload"]["text"])
        self.assertNotIn("restarted", record["events"][1]["payload"]["text"])

    def test_unreachable_health_during_the_wait_does_not_falsely_confirm_a_restart(self):
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            max_wait_seconds=0.03, poll_interval_seconds=0.01,
            fetch_fn=lambda *a, **k: None,
            dispatch_time=time.time(),
            health_fn=lambda base_url: (False, None),
        )
        record = self.store.get(self.run_id)
        self.assertIn("hasn't reported back", record["events"][1]["payload"]["text"])

    def test_no_dispatch_time_given_never_consults_health_at_all_unchanged_legacy_behavior(self):
        called = {"health": False}

        def _health(base_url):
            called["health"] = True
            return True, 0.0

        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            max_wait_seconds=0.02, poll_interval_seconds=0.01,
            fetch_fn=lambda *a, **k: None,
            health_fn=_health,  # would immediately "confirm" a restart if ever consulted
        )
        self.assertFalse(called["health"], "omitting dispatch_time must disable this check entirely, matching every pre-existing caller of this function")
        record = self.store.get(self.run_id)
        self.assertIn("hasn't reported back", record["events"][1]["payload"]["text"])

    def test_a_real_completion_arriving_first_is_delivered_even_with_dispatch_time_set(self):
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            dispatch_time=time.time(),
            health_fn=lambda base_url: (True, 5.0),  # would look like a restart, but a real completion wins first
            fetch_fn=lambda *a, **k: {"executionOutcome": "Completed", "decision": "Accepted", "text": "DH-FIXTURE-abc123"},
        )
        record = self.store.get(self.run_id)
        self.assertEqual(record["status"], ra.TERMINAL_COMPLETED_STATUS)
        self.assertIn("DH-FIXTURE-abc123", record["events"][1]["payload"]["text"])


class DeliverHermesCompletionTest(unittest.TestCase):
    """Tests _deliver_hermes_completion directly and synchronously (never via a
    real thread or real HTTP/time) by injecting fetch_fn and, for the timeout
    case, tiny max_wait_seconds/poll_interval_seconds.
    """

    def setUp(self):
        self.store = ra.RunStore()
        self.config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.ack_text = "I've asked Hermes to look into it."
        transcript = self.store.append_turn_messages("w-1", "check the fixture", self.ack_text)
        self.ack_index = len(transcript) - 1
        self.run_id = self.store.create(
            webui_session_id="w-1",
            events=[{"event": "token", "seq": 1, "payload": {"text": self.ack_text}}],
            status=ra.PENDING_HERMES_STATUS,
        )

    def test_success_appends_token_then_done_finalizes_completed_and_replaces_the_ack_message(self):
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            fetch_fn=lambda *a, **k: {
                "executionOutcome": "Completed", "decision": "Accepted",
                "text": "Hermes finished checking that — it reported: DH-FIXTURE-abc123",
            },
        )
        record = self.store.get(self.run_id)
        self.assertEqual(record["status"], ra.TERMINAL_COMPLETED_STATUS)
        self.assertEqual(len(record["events"]), 3)
        self.assertEqual(record["events"][1]["event"], "token")
        self.assertIn("DH-FIXTURE-abc123", record["events"][1]["payload"]["text"])
        self.assertEqual(record["events"][2]["event"], "done")
        messages = record["events"][2]["payload"]["session"]["messages"]
        self.assertEqual(messages[-1]["role"], "assistant")
        self.assertIn("DH-FIXTURE-abc123", messages[-1]["content"])
        self.assertIn(self.ack_text, messages[-1]["content"], "the original acknowledgment must be preserved, not discarded")

    def test_adapter_never_composes_its_own_wrapper_it_renders_the_directors_text_exactly(self):
        """The ownership-boundary regression test: this adapter must not prepend/append any
        phrase of its own ("Hermes finished checking that...", "Hermes wasn't able to...", etc.)
        — whatever /debug/hermes-assignment's `text` field says IS the complete reply, decided
        and worded entirely by HermesCompletionDirector on the engram-engine side.
        """
        raw_director_text = "RAW-DIRECTOR-COMPOSED-TEXT-NO-PYTHON-WRAPPER"
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            fetch_fn=lambda *a, **k: {"executionOutcome": "Completed", "decision": "Accepted", "text": raw_director_text},
        )
        record = self.store.get(self.run_id)
        delivered = record["events"][1]["payload"]["text"]
        self.assertEqual(delivered, f"\n\n{raw_director_text}", "the adapter must transport this text verbatim, with no wrapper phrase of its own")
        self.assertNotIn("Hermes finished checking", delivered)
        self.assertNotIn("Hermes wasn't able to complete", delivered)

    def test_a_withheld_decision_still_finalizes_as_completed_not_errored(self):
        """Withheld (whether from an execution failure or an unverified tool result) is a
        legitimate, complete Director reply — the Director successfully decided not to pass
        along findings, and said so honestly. That is not a transport/adapter-level error, so
        TERMINAL_ERROR_STATUS must be reserved for the timeout case alone.
        """
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            fetch_fn=lambda *a, **k: {
                "executionOutcome": "Failed", "decision": "Withheld",
                "text": "Hermes wasn't able to complete that: no tool call observed",
            },
        )
        record = self.store.get(self.run_id)
        self.assertEqual(record["status"], ra.TERMINAL_COMPLETED_STATUS)
        self.assertIn("wasn't able to complete", record["events"][1]["payload"]["text"])
        self.assertIn("no tool call observed", record["events"][1]["payload"]["text"])

    def test_a_withheld_decision_from_an_unverified_completed_result_also_finalizes_as_completed(self):
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            fetch_fn=lambda *a, **k: {
                "executionOutcome": "Completed", "decision": "Withheld",
                "text": "Hermes responded, but I can't confirm the result actually came from reading the right file, so I'm not passing along its specific content.",
            },
        )
        record = self.store.get(self.run_id)
        self.assertEqual(record["status"], ra.TERMINAL_COMPLETED_STATUS)

    def test_timeout_with_no_completion_ever_reports_honestly_rather_than_hanging_forever(self):
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            max_wait_seconds=0.05, poll_interval_seconds=0.01,
            fetch_fn=lambda *a, **k: None,
        )
        record = self.store.get(self.run_id)
        self.assertEqual(record["status"], ra.TERMINAL_ERROR_STATUS, "only a genuine timeout — nothing ever decided — is a transport-level error")
        self.assertIn("hasn't reported back", record["events"][1]["payload"]["text"])

    def test_an_out_of_range_ack_index_falls_back_to_appending_rather_than_corrupting_an_unrelated_message(self):
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, 99,  # no such index
            fetch_fn=lambda *a, **k: {"executionOutcome": "Completed", "decision": "Accepted", "text": "Hermes finished checking that — it reported: DH-FIXTURE-abc123"},
        )
        messages = self.store.get(self.run_id)["events"][-1]["payload"]["session"]["messages"]
        # original ack (index self.ack_index) is untouched — a fresh message was appended instead
        self.assertEqual(messages[self.ack_index]["content"], self.ack_text)
        self.assertEqual(messages[-1]["role"], "assistant")
        self.assertIn("DH-FIXTURE-abc123", messages[-1]["content"])

    def test_delivery_only_ever_happens_once_for_a_given_run(self):
        fetch_fn = lambda *a, **k: {"executionOutcome": "Completed", "decision": "Accepted", "text": "Hermes finished checking that — it reported: DH-FIXTURE-abc123"}
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            fetch_fn=fetch_fn,
        )
        events_after_first = list(self.store.get(self.run_id)["events"])

        # A second delivery attempt for the SAME run_id (the only way this could
        # happen in practice is a bug spawning the thread twice — run_turn never
        # does) must not duplicate events; append_event still succeeds (the run
        # still exists) but nothing in production code calls this twice.
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            fetch_fn=fetch_fn,
        )
        events_after_second = self.store.get(self.run_id)["events"]
        self.assertEqual(len(events_after_second), len(events_after_first) + 2, "each call appends its own token+done — this pins that run_turn's own one-thread-per-assignment invariant is what prevents duplication, not this function")


class LoadRunnerConfigHermesPendingDirTest(unittest.TestCase):
    def test_absent_when_neither_pending_dir_nor_sessions_dir_configured(self):
        cfg = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        self.assertIsNone(cfg["hermes_pending_dir"])

    def test_defaults_to_a_sibling_of_the_sessions_dir(self):
        cfg = ra.load_runner_config({
            "ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k",
            "WEBUI_SESSIONS_DIR": "/home/halo/development/hermes-webui-dev/home/webui/sessions",
        })
        self.assertEqual(cfg["hermes_pending_dir"], "/home/halo/development/hermes-webui-dev/home/webui/hermes-pending")

    def test_explicit_override_wins_over_the_sessions_dir_default(self):
        cfg = ra.load_runner_config({
            "ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k",
            "WEBUI_SESSIONS_DIR": "/x/sessions", "HERMES_PENDING_DIR": "/y/pending",
        })
        self.assertEqual(cfg["hermes_pending_dir"], "/y/pending")

    def test_explicit_override_works_even_with_no_sessions_dir(self):
        cfg = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k", "HERMES_PENDING_DIR": "/y/pending"})
        self.assertEqual(cfg["hermes_pending_dir"], "/y/pending")


class PendingMarkerTest(unittest.TestCase):
    def test_write_then_read_round_trips_all_fields(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            ra.write_pending_marker(
                d, assignment_id="a1", webui_session_id="w-1",
                user_message="check the fixture", ack_text="I've asked Hermes to look into it.",
                created_at=1000.0,
            )
            markers = ra._read_pending_markers(d)
        self.assertEqual(len(markers), 1)
        self.assertEqual(markers[0], {
            "assignment_id": "a1", "webui_session_id": "w-1",
            "user_message": "check the fixture", "ack_text": "I've asked Hermes to look into it.",
            "created_at": 1000.0,
        })

    def test_none_pending_dir_is_a_silent_no_op(self):
        ra.write_pending_marker(None, assignment_id="a1", webui_session_id="w-1", user_message="hi", ack_text="ack", created_at=1.0)
        # No exception, and nothing to read back either — this must never raise for
        # the common case where the feature is simply not configured.
        self.assertEqual(ra._read_pending_markers(""), [])

    def test_creates_the_directory_if_missing(self):
        import tempfile, os as _os
        with tempfile.TemporaryDirectory() as d:
            nested = _os.path.join(d, "does", "not", "exist", "yet")
            ra.write_pending_marker(nested, assignment_id="a1", webui_session_id="w-1", user_message="hi", ack_text="ack", created_at=1.0)
            self.assertEqual(len(ra._read_pending_markers(nested)), 1)

    def test_remove_deletes_the_marker(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            ra.write_pending_marker(d, assignment_id="a1", webui_session_id="w-1", user_message="hi", ack_text="ack", created_at=1.0)
            ra._remove_pending_marker(d, "a1")
            self.assertEqual(ra._read_pending_markers(d), [])

    def test_remove_of_unknown_assignment_is_a_silent_no_op(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            ra._remove_pending_marker(d, "never-existed")  # must not raise

    def test_missing_pending_dir_returns_empty_list_not_raises(self):
        self.assertEqual(ra._read_pending_markers("/no/such/directory/at/all"), [])

    def test_malformed_marker_file_is_dropped_and_excluded(self):
        import tempfile, os as _os
        with tempfile.TemporaryDirectory() as d:
            with open(_os.path.join(d, "bad.json"), "w") as f:
                f.write("{not json")
            ra.write_pending_marker(d, assignment_id="good", webui_session_id="w-1", user_message="hi", ack_text="ack", created_at=1.0)
            markers = ra._read_pending_markers(d)
        self.assertEqual(len(markers), 1)
        self.assertEqual(markers[0]["assignment_id"], "good")
        # The malformed file must also have been removed, not merely skipped — otherwise
        # every future adapter startup re-reads and re-skips the same broken file forever.
        self.assertFalse(_os.path.exists(_os.path.join(d, "bad.json")))


class PersistWebuiSessionMessagesTest(unittest.TestCase):
    def test_writes_messages_and_preserves_other_fields(self):
        import tempfile, os as _os
        with tempfile.TemporaryDirectory() as d:
            path = _os.path.join(d, "w-1.json")
            with open(path, "w") as f:
                json.dump({"session_id": "w-1", "title": "My Chat", "model": "local", "messages": [], "message_count": 0, "updated_at": 1.0}, f)

            ok = ra.persist_webui_session_messages(d, "w-1", [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}])
            self.assertTrue(ok)
            with open(path) as f:
                data = json.load(f)
        self.assertEqual(data["title"], "My Chat", "fields this function does not own must survive untouched")
        self.assertEqual(data["model"], "local")
        self.assertEqual(data["messages"], [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}])
        self.assertEqual(data["message_count"], 2)
        self.assertGreater(data["updated_at"], 1.0)

    def test_missing_session_file_returns_false_not_raises(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.assertFalse(ra.persist_webui_session_messages(d, "no-such-session", [{"role": "user", "content": "hi"}]))

    def test_none_sessions_dir_returns_false(self):
        self.assertFalse(ra.persist_webui_session_messages(None, "w-1", []))

    def test_malformed_existing_json_returns_false_not_raises(self):
        import tempfile, os as _os
        with tempfile.TemporaryDirectory() as d:
            with open(_os.path.join(d, "w-1.json"), "w") as f:
                f.write("{not json")
            self.assertFalse(ra.persist_webui_session_messages(d, "w-1", [{"role": "user", "content": "hi"}]))


class RunTurnWritesPendingMarkerTest(unittest.TestCase):
    def setUp(self):
        import tempfile
        self._tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmpdir.cleanup)
        self.config = ra.load_runner_config({
            "ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k", "HERMES_PENDING_DIR": self._tmpdir.name,
        })
        self.store = ra.RunStore()

    def test_a_delegation_turn_writes_a_marker_with_the_right_fields(self):
        engram_result = {
            "reply": "I've asked Hermes to look into it.",
            "sessionId": "e-1",
            "trace": {"hermesDelegation": {"assignmentId": "assign-1", "task": "read the fixture"}},
        }
        with patch.object(ra, "forward_to_engram", return_value=engram_result), \
             patch.object(ra, "threading") as mock_threading:
            ra.run_turn(self.config, self.store, webui_session_id="w-1", message="check the fixture")

        markers = ra._read_pending_markers(self._tmpdir.name)
        self.assertEqual(len(markers), 1)
        self.assertEqual(markers[0]["assignment_id"], "assign-1")
        self.assertEqual(markers[0]["webui_session_id"], "w-1")
        self.assertEqual(markers[0]["user_message"], "check the fixture")
        self.assertEqual(markers[0]["ack_text"], "I've asked Hermes to look into it.")
        self.assertIsInstance(markers[0]["created_at"], (int, float))

    def test_a_turn_with_no_delegation_writes_no_marker(self):
        with patch.object(ra, "forward_to_engram", return_value={"reply": "hi", "sessionId": "e-1"}), \
             patch.object(ra, "threading") as mock_threading:
            ra.run_turn(self.config, self.store, webui_session_id="w-1", message="hey")
        self.assertEqual(ra._read_pending_markers(self._tmpdir.name), [])


class DeliverHermesCompletionMarkerAndDirectPersistTest(unittest.TestCase):
    """_deliver_hermes_completion must clean up its own marker and persist the
    resolved transcript directly to WebUI's own session file — not only through
    the in-memory RunStore events a live browser poll would otherwise need to
    consume (see persist_webui_session_messages's own doc for why that can't be
    assumed here).
    """

    def setUp(self):
        import tempfile
        self._pending_dir = tempfile.TemporaryDirectory()
        self._sessions_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self._pending_dir.cleanup)
        self.addCleanup(self._sessions_dir.cleanup)
        self.config = ra.load_runner_config({
            "ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k",
            "HERMES_PENDING_DIR": self._pending_dir.name, "WEBUI_SESSIONS_DIR": self._sessions_dir.name,
        })
        self.store = ra.RunStore()
        self.ack_text = "I've asked Hermes to look into it."
        transcript = self.store.append_turn_messages("w-1", "check the fixture", self.ack_text)
        self.ack_index = len(transcript) - 1
        self.run_id = self.store.create(
            webui_session_id="w-1", events=[{"event": "token", "seq": 1, "payload": {"text": self.ack_text}}],
            status=ra.PENDING_HERMES_STATUS,
        )
        import json as _json, os as _os
        with open(_os.path.join(self._sessions_dir.name, "w-1.json"), "w") as f:
            _json.dump({"session_id": "w-1", "messages": [], "message_count": 0, "updated_at": 1.0}, f)
        ra.write_pending_marker(self._pending_dir.name, assignment_id="assign-1", webui_session_id="w-1", user_message="check the fixture", ack_text=self.ack_text, created_at=1.0)

    def test_success_removes_the_marker_and_persists_directly_to_the_session_file(self):
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            fetch_fn=lambda *a, **k: {"executionOutcome": "Completed", "decision": "Accepted", "text": "DH-FIXTURE-abc123"},
        )
        self.assertEqual(ra._read_pending_markers(self._pending_dir.name), [])
        persisted = ra.load_persisted_webui_messages(self._sessions_dir.name, "w-1")
        self.assertEqual(persisted[-1]["role"], "assistant")
        self.assertIn("DH-FIXTURE-abc123", persisted[-1]["content"])
        self.assertIn(self.ack_text, persisted[-1]["content"])

    def test_timeout_also_removes_the_marker_and_persists_directly(self):
        ra._deliver_hermes_completion(
            self.config, self.store, self.run_id, "w-1", "assign-1", self.ack_text, self.ack_index,
            max_wait_seconds=0.02, poll_interval_seconds=0.01, fetch_fn=lambda *a, **k: None,
        )
        self.assertEqual(ra._read_pending_markers(self._pending_dir.name), [])
        persisted = ra.load_persisted_webui_messages(self._sessions_dir.name, "w-1")
        self.assertIn("hasn't reported back", persisted[-1]["content"])


class ReconcileInterruptedAssignmentsTest(unittest.TestCase):
    """Tests reconcile_interrupted_assignments — the recovery pass that runs once
    at adapter startup for whatever write_pending_marker left behind from a
    previous process instance. Every branch is exercised directly (never a real
    thread, real HTTP, or real clock) via injected health_fn/fetch_fn and, for the
    "resume waiting" branch, a mocked threading module (same pattern as
    RunTurnHermesDelegationTest).
    """

    def setUp(self):
        import tempfile
        self._pending_dir = tempfile.TemporaryDirectory()
        self._sessions_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self._pending_dir.cleanup)
        self.addCleanup(self._sessions_dir.cleanup)
        self.config = ra.load_runner_config({
            "ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k",
            "HERMES_PENDING_DIR": self._pending_dir.name, "WEBUI_SESSIONS_DIR": self._sessions_dir.name,
        })
        self.store = ra.RunStore()
        import json as _json, os as _os
        with open(_os.path.join(self._sessions_dir.name, "w-1.json"), "w") as f:
            _json.dump({"session_id": "w-1", "messages": [], "message_count": 0, "updated_at": 1.0}, f)

    def _write_marker(self, created_at: float):
        ra.write_pending_marker(
            self._pending_dir.name, assignment_id="assign-1", webui_session_id="w-1",
            user_message="check the fixture", ack_text="I've asked Hermes to look into it.", created_at=created_at,
        )

    def test_no_markers_is_a_complete_no_op(self):
        called = {"health": False, "fetch": False}

        def _health(base_url):
            called["health"] = True
            return True, 100.0

        def _fetch(*a, **k):
            called["fetch"] = True
            return None

        ra.reconcile_interrupted_assignments(self.config, self.store, health_fn=_health, fetch_fn=_fetch)
        self.assertFalse(called["health"])
        self.assertFalse(called["fetch"])

    def test_no_op_entirely_when_pending_dir_not_configured(self):
        config = ra.load_runner_config({"ENGRAM_DEBUG_TOKEN": "t", "RUNNER_API_KEY": "k"})
        # Must return immediately without touching health_fn/fetch_fn at all — there is
        # nothing to reconcile and nowhere durable to have recorded it anyway.
        ra.reconcile_interrupted_assignments(config, self.store, health_fn=lambda *a: self.fail("must not be called"))

    def test_malformed_marker_is_dropped_without_ever_checking_health(self):
        import os as _os
        with open(_os.path.join(self._pending_dir.name, "assign-bad.json"), "w") as f:
            f.write('{"assignment_id": "assign-bad"}')  # missing required fields
        ra.reconcile_interrupted_assignments(
            self.config, self.store, health_fn=lambda *a: self.fail("must not be called for a malformed marker"),
        )
        self.assertEqual(ra._read_pending_markers(self._pending_dir.name), [])

    def test_health_unreachable_delivers_an_honest_cannot_confirm_message_and_clears_the_marker(self):
        self._write_marker(created_at=1000.0)
        ra.reconcile_interrupted_assignments(
            self.config, self.store,
            health_fn=lambda base_url: (False, None),
            health_max_wait_seconds=0.02, health_poll_interval_seconds=0.01,
        )
        self.assertEqual(ra._read_pending_markers(self._pending_dir.name), [])
        persisted = ra.load_persisted_webui_messages(self._sessions_dir.name, "w-1")
        self.assertEqual(persisted[0]["role"], "user")
        self.assertEqual(persisted[0]["content"], "check the fixture", "the original user message never reached disk before — reconciliation must persist it now")
        self.assertIn("can't currently reach the development backend", persisted[-1]["content"])
        self.assertNotIn("restarted", persisted[-1]["content"], "must not claim a confirmed restart when the truth is merely 'unreachable'")

    def test_backend_restarted_since_dispatch_delivers_a_confirmed_interruption_and_clears_the_marker(self):
        self._write_marker(created_at=1000.0)
        with patch.object(ra, "time") as mock_time:
            # now=2000, uptimeSeconds=5 -> backend started at 1995, AFTER created_at=1000.
            mock_time.time.return_value = 2000.0
            ra.reconcile_interrupted_assignments(self.config, self.store, health_fn=lambda base_url: (True, 5.0))
        self.assertEqual(ra._read_pending_markers(self._pending_dir.name), [])
        persisted = ra.load_persisted_webui_messages(self._sessions_dir.name, "w-1")
        self.assertIn("backend restarted", persisted[-1]["content"])
        self.assertIn("I've asked Hermes to look into it.", persisted[-1]["content"], "the original ack must be preserved, not discarded")
        self.assertNotIn("Hermes finished", persisted[-1]["content"], "must never read as an ordinary success")

    def test_backend_same_instance_with_a_completion_already_recorded_delivers_it_verbatim(self):
        self._write_marker(created_at=1000.0)
        with patch.object(ra, "time") as mock_time:
            # now=1050, uptimeSeconds=100 -> backend started at 950, BEFORE created_at=1000: same instance.
            mock_time.time.return_value = 1050.0
            ra.reconcile_interrupted_assignments(
                self.config, self.store,
                health_fn=lambda base_url: (True, 100.0),
                fetch_fn=lambda *a, **k: {"executionOutcome": "Completed", "decision": "Accepted", "text": "DH-FIXTURE-abc123"},
            )
        self.assertEqual(ra._read_pending_markers(self._pending_dir.name), [])
        persisted = ra.load_persisted_webui_messages(self._sessions_dir.name, "w-1")
        self.assertIn("DH-FIXTURE-abc123", persisted[-1]["content"], "the backend never actually lost this — its real, already-recorded completion must be delivered, not a generic interruption notice")

    def test_backend_same_instance_but_no_completion_yet_resumes_watching_without_redispatching(self):
        self._write_marker(created_at=1000.0)
        with patch.object(ra, "time") as mock_time, patch.object(ra, "threading") as mock_threading:
            mock_time.time.return_value = 1050.0
            ra.reconcile_interrupted_assignments(
                self.config, self.store,
                health_fn=lambda base_url: (True, 100.0),
                fetch_fn=lambda *a, **k: None,
            )
        # Resumed by spawning exactly one more _deliver_hermes_completion thread — never a
        # fresh Hermes dispatch (there is no such function in this adapter at all).
        mock_threading.Thread.assert_called_once()
        _, kwargs = mock_threading.Thread.call_args
        self.assertIs(kwargs["target"], ra._deliver_hermes_completion)
        self.assertEqual(kwargs["args"][3], "w-1")
        self.assertEqual(kwargs["args"][4], "assign-1")
        self.assertTrue(kwargs.get("daemon"))
        # The resumed poll must ALSO carry the original dispatch_time forward — so a
        # second restart while it's still waiting is caught promptly too, not only
        # discoverable via a third adapter startup.
        self.assertEqual(kwargs["kwargs"]["dispatch_time"], 1000.0)
        self.assertIn("health_fn", kwargs["kwargs"])
        mock_threading.Thread.return_value.start.assert_called_once()
        # The marker is deliberately left in place — the resumed thread (mocked away here,
        # so it never actually runs) is what would remove it once it concludes.
        self.assertEqual(len(ra._read_pending_markers(self._pending_dir.name)), 1)

    def test_multiple_markers_are_each_reconciled_independently(self):
        self._write_marker(created_at=1000.0)
        ra.write_pending_marker(
            self._pending_dir.name, assignment_id="assign-2", webui_session_id="w-1",
            user_message="check another fixture", ack_text="Looking into that too.", created_at=1000.0,
        )
        with patch.object(ra, "time") as mock_time:
            mock_time.time.return_value = 2000.0
            ra.reconcile_interrupted_assignments(self.config, self.store, health_fn=lambda base_url: (True, 5.0))
        self.assertEqual(ra._read_pending_markers(self._pending_dir.name), [])
        persisted = ra.load_persisted_webui_messages(self._sessions_dir.name, "w-1")
        self.assertEqual(len(persisted), 4, "both interrupted turns must land in the transcript, in order, none dropped")

    def test_running_reconciliation_again_after_it_already_resolved_a_marker_does_not_duplicate(self):
        self._write_marker(created_at=1000.0)
        with patch.object(ra, "time") as mock_time:
            mock_time.time.return_value = 2000.0
            ra.reconcile_interrupted_assignments(self.config, self.store, health_fn=lambda base_url: (True, 5.0))
            first_pass = ra.load_persisted_webui_messages(self._sessions_dir.name, "w-1")
            # A second pass (e.g. a second, redundant startup call) finds no marker left —
            # nothing left to reconcile, so nothing more is appended.
            ra.reconcile_interrupted_assignments(
                self.config, self.store,
                health_fn=lambda base_url: self.fail("must not be called — no marker should remain"),
            )
        second_pass = ra.load_persisted_webui_messages(self._sessions_dir.name, "w-1")
        self.assertEqual(first_pass, second_pass)


if __name__ == "__main__":
    unittest.main()
