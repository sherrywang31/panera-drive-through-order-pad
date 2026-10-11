"""Offline checks for scoring and execution; these do not measure LLM accuracy."""
import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import order_eval as ev


class EvaluationTest(unittest.TestCase):
    def setUp(self):
        self.spec = ev.schema(ev.catalog())
        self.cases = ev.read_cases(ev.HERE / "cases/bagel_conversations.json", self.spec)
        self.expected = copy.deepcopy(self.cases[0]["turns"][0]["expected"])

    def test_dataset_matches_android_vocabulary(self):
        self.assertEqual(24, len(self.cases))
        self.assertEqual(42, sum(len(c["turns"]) for c in self.cases))

    def test_order_independent_but_duplicate_lines_count(self):
        actual = copy.deepcopy(self.expected)
        actual["cars"][0]["groups"][0]["bagels"].reverse()
        self.assertTrue(ev.score(self.expected, actual, self.spec)["exact"])
        actual["cars"][0]["groups"][0]["bagels"].append(copy.deepcopy(actual["cars"][0]["groups"][0]["bagels"][0]))
        self.assertFalse(ev.score(self.expected, actual, self.spec)["exact"])

    def test_silent_default_is_an_accuracy_failure(self):
        actual = copy.deepcopy(self.expected)
        actual["cars"][0]["groups"][0]["bagels"][0]["toast"] = "UNTOASTED"
        scored = ev.score(self.expected, actual, self.spec)
        self.assertFalse(scored["exact"])
        self.assertEqual(1, scored["invented_unknowns"])

    def test_schema_errors_fail_instead_of_disappearing(self):
        for value in ({}, None, {"cars": [], "activeCarNumber": True}):
            scored = ev.score(self.expected, value, self.spec)
            self.assertFalse(scored["exact"])
            self.assertTrue(scored["schema_errors"])

    def test_duplicate_car_and_none_with_items_are_invalid(self):
        actual = copy.deepcopy(self.expected)
        actual["cars"].append(copy.deepcopy(actual["cars"][0]))
        self.assertTrue(ev.state_errors(actual, self.spec))
        actual["cars"] = actual["cars"][:1]
        actual["cars"][0]["status"] = "NONE"
        self.assertTrue(ev.state_errors(actual, self.spec))

    def test_spread_size_and_clarification_are_scored(self):
        case = next(c for c in self.cases if c["id"] == "complete-dozen")
        expected = case["turns"][0]["expected"]
        actual = copy.deepcopy(expected)
        actual["cars"][1]["groups"][0]["spreads"][0]["size"] = "SINGLE"
        actual["cars"][1]["needsClarification"] = True
        self.assertEqual(2, len(ev.score(expected, actual, self.spec)["diffs"]))

    def test_request_contains_context_but_no_ground_truth(self):
        request = ev.make_request({"model": "test"}, "prompt", self.spec, ev.catalog(),
                                  self.cases[0]["initial_state"], [], self.cases[0]["turns"][0])
        payload = json.loads(request["messages"][1]["content"])
        self.assertNotIn("expected", payload)
        self.assertEqual("s1", payload["new_segment"]["id"])

    def test_cost_includes_cached_and_completion_tokens(self):
        model = {"price_per_million_tokens": {"input": 1, "cached_input": .1, "output": 2}}
        usage = {"prompt_tokens": 1000, "completion_tokens": 200,
                 "prompt_tokens_details": {"cached_tokens": 500}}
        self.assertAlmostEqual(.00095, ev.estimated_cost(usage, model))
        self.assertIsNone(ev.estimated_cost({}, model))

    def test_provider_request_and_response_adapter(self):
        response = {"choices": [{"finish_reason": "stop", "message": {
            "content": json.dumps(self.expected)}}], "model": "pinned-test",
            "usage": {"prompt_tokens": 100, "completion_tokens": 20}}
        opener = MagicMock()
        opener.open.return_value = io.StringIO(json.dumps(response))
        with patch.object(ev, "build_opener", return_value=opener), patch.dict(ev.os.environ, {"TEST_KEY": "placeholder"}):
            predicted, details = ev.call_model({"base_url": "https://example.com/v1", "api_key_env": "TEST_KEY"}, {"model": "test"}, 5)
        self.assertEqual(self.expected, predicted)
        self.assertEqual("pinned-test", details["returned_model"])
        req = opener.open.call_args.args[0]
        self.assertEqual("https://example.com/v1/chat/completions", req.full_url)
        self.assertEqual("POST", req.method)
        self.assertNotIn("placeholder", json.dumps(details))

    def test_provider_refusal_and_incomplete_output_are_failures(self):
        for reason, message in [("length", {"content": "{}"}), ("stop", {"refusal": "no"})]:
            opener = MagicMock()
            opener.open.return_value = io.StringIO(json.dumps({"choices": [{"finish_reason": reason, "message": message}]}))
            with patch.object(ev, "build_opener", return_value=opener), self.assertRaises(RuntimeError):
                ev.call_model({"base_url": "http://localhost:8080/v1"}, {}, 5)

    def test_remote_plaintext_and_embedded_credentials_rejected(self):
        for url in ("http://example.com/v1", "https://user:password@example.com/v1"):
            with self.assertRaises(ValueError):
                ev.call_model({"base_url": url}, {}, 5)

    def run_cli(self, temp, *extra):
        output = Path(temp) / "run"
        with contextlib.redirect_stdout(io.StringIO()):
            code = ev.main(["--models", "gpt-4o-mini", "--prompts", "contextual",
                            "--output", str(output), *extra])
        return code, output

    def test_dry_run_is_network_free(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(ev, "call_model") as call:
            code, output = self.run_cli(temp, "--dry-run")
            self.assertEqual(0, code)
            call.assert_not_called()
            self.assertTrue((output / "sample_requests.json").exists())
            self.assertFalse((output / "summary.json").exists())

    def test_offline_replay_all_checkpoints(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(ev, "call_model") as call:
            replay = Path(temp) / "predictions.jsonl"
            rows = [{"model": "gpt-4o-mini", "prompt": "contextual", "case_id": c["id"],
                     "turn_id": t["id"], "prediction": t["expected"]}
                    for c in self.cases for t in c["turns"]]
            replay.write_text("\n".join(json.dumps(row) for row in rows))
            code, output = self.run_cli(temp, "--predictions", str(replay))
            self.assertEqual(0, code)
            call.assert_not_called()
            summary = ev.load(output / "summary.json")[0]
            self.assertEqual(1, summary["exact_turn_rate"])
            self.assertEqual(0, summary["api_calls"])
            self.assertIsNone(summary["estimated_cost_usd"])

    def test_partial_pause_and_duplicate_do_not_call_model(self):
        case = next(c for c in self.cases if c["id"] == "pause-resume")
        with tempfile.TemporaryDirectory() as temp:
            data = Path(temp) / "cases.json"
            data.write_text(json.dumps([case]))
            calls = []
            def fake(model, request, timeout):
                calls.append(request)
                return case["turns"][0 if len(calls) == 1 else -1]["expected"], {
                    "latency_seconds": .1, "usage": {"prompt_tokens": 100, "completion_tokens": 30}}
            with patch.dict(ev.os.environ, {"OPENAI_API_KEY": "test-not-a-secret"}), patch.object(ev, "call_model", side_effect=fake):
                code, output = self.run_cli(temp, "--cases", str(data))
            self.assertEqual(0, code)
            self.assertEqual(2, len(calls))
            self.assertEqual(2, ev.load(output / "summary.json")[0]["api_calls"])

    def test_rollout_preserves_wrong_prediction_in_next_request(self):
        case = next(c for c in self.cases if c["id"] == "repeat-total")
        wrong = copy.deepcopy(case["turns"][0]["expected"])
        wrong["cars"][0]["groups"][0]["bagels"][0]["quantity"] = 8
        with tempfile.TemporaryDirectory() as temp:
            data = Path(temp) / "cases.json"
            data.write_text(json.dumps([case]))
            calls = []
            def fake(model, request, timeout):
                calls.append(request)
                return wrong if len(calls) == 1 else case["turns"][-1]["expected"], {"latency_seconds": .1, "usage": {}}
            with patch.dict(ev.os.environ, {"OPENAI_API_KEY": "test-not-a-secret"}), patch.object(ev, "call_model", side_effect=fake):
                code, output = self.run_cli(temp, "--cases", str(data))
            self.assertEqual(1, code)
            self.assertEqual(wrong, json.loads(calls[1]["messages"][1]["content"])["current_state"])
            summary = ev.load(output / "summary.json")[0]
            self.assertEqual(.5, summary["exact_turn_rate"])
            self.assertEqual(0, summary["all_turns_correct_conversation_rate"])
            self.assertEqual(1, summary["final_state_exact_rate"])

    def test_missing_replay_predictions_are_failures(self):
        with tempfile.TemporaryDirectory() as temp:
            replay = Path(temp) / "empty.jsonl"
            replay.write_text("")
            code, output = self.run_cli(temp, "--predictions", str(replay))
            self.assertEqual(1, code)
            summary = ev.load(output / "summary.json")[0]
            self.assertGreater(summary["errors"], 0)
            self.assertLess(summary["exact_turn_rate"], 1)


if __name__ == "__main__":
    unittest.main()
