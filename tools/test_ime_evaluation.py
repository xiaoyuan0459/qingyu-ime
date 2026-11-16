"""评测工具的协议与报告契约；只使用独立合成输入，不连接设备。"""

import importlib.util
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "ime_runner", Path(__file__).with_name("eval-ime-candidates.py"))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


def case(**fields):
    return {"id": "sentence-1", "keyboard": "26key", "schema": "pinyin",
            "keys": "k:nihao", "expected": "你好", "max_rank": 1,
            "category": "sentence", "group": "synthetic", "source": "独立合成",
            "dataset": "synthetic", **fields}


def action(texts, key_us=1, ctx_us=9, **fields):
    return {"action": "key", "key_us": key_us, "ctx_us": ctx_us,
            "preedit": "ni hao", "page_no": 0, "page_size": 30,
            "candidates": [{"text": text} for text in texts], **fields}


class EvaluationContractTest(unittest.TestCase):
    def test_wrong_sentence_outside_window_still_counts_in_cer(self):
        record = RUNNER.evaluate_case(case(), [action(["你号"])])
        self.assertTrue(record["missing"])
        self.assertEqual(record["cer"], 0.5)
        self.assertEqual(RUNNER.aggregate([record])["26key/sentence"]["cer_mean"], 0.5)

    def test_cer_accepts_any_allowed_answer_and_empty_output_is_deletion(self):
        alternative = RUNNER.evaluate_case(
            case(expected="你好|您好"), [action(["您好", "你好"])])
        self.assertEqual(alternative["cer"], 0)
        empty = RUNNER.evaluate_case(case(), [action([])])
        self.assertEqual(empty["cer"], 1)

    def test_latency_pools_actions_and_includes_context_without_snapshots(self):
        first = RUNNER.evaluate_case(case(), [action(["你好"])] * 99)
        second = RUNNER.evaluate_case(case(id="sentence-2"), [
            action(["你好"], key_us=991),
            action(["你好"], key_us=0, ctx_us=10000, action="snapshot")])
        cell = RUNNER.aggregate([first, second])["26key/sentence"]
        self.assertEqual(cell["key_samples"], 100)
        self.assertEqual(cell["key_p95_us"], 10)
        self.assertEqual(cell["key_p99_us"], 10)
        self.assertEqual(cell["key_max_us"], 1000)

    def test_page_two_rank_is_global(self):
        record = RUNNER.evaluate_case(case(), [action(["你好"], page_no=1)])
        self.assertEqual(record["rank"], 31)

    def test_pool_match_beyond_emitted_window_retains_engine_rank(self):
        event = action(["你号"], action="scan", matches=[{"text": "你好", "engine_index": 300}],
                       pool_scanned=301, pool_exhausted=True)
        self.assertEqual(RUNNER.evaluate_case(case(), [event])["rank"], 301)

    def test_partial_commit_candidate_is_not_full_input_success(self):
        event = action(["你好"], action="scan", matches=[{"text": "你好", "engine_index": 0, "full_input": False}],
                       pool_scanned=1, pool_exhausted=True)
        self.assertIsNone(RUNNER.evaluate_case(case(), [event])["rank"])

    def test_critical_case_regression_cannot_hide_in_aggregate(self):
        records = [RUNNER.evaluate_case(case(), [action(["你号"])])]
        with tempfile.TemporaryDirectory() as directory:
            gate = Path(directory) / "gate.json"
            gate.write_text(json.dumps({"_critical": {"sentence-1": 1}}))
            ok, failures = RUNNER.check_gate(RUNNER.aggregate(records), gate, records)
        self.assertFalse(ok)
        self.assertTrue(any("sentence-1" in failure for failure in failures))

    def test_same_size_schema_tamper_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            pack = Path(directory)
            schema = pack / "pinyin.schema.yaml"
            schema.write_bytes(b"schema: a\n")
            manifest = {"files": [{"path": schema.name, "size": schema.stat().st_size,
                                   "sha256": RUNNER.sha256_of(schema)}]}
            schema.write_bytes(b"schema: b\n")
            with self.assertRaisesRegex(SystemExit, "SHA-256"):
                RUNNER.verify_pack_files(pack, manifest)

    def test_absolute_missing_budget_applies_to_each_round(self):
        records = [{**RUNNER.evaluate_case(case(), [action([])]), "round": number}
                   for number in (1, 2)]
        with tempfile.TemporaryDirectory() as directory:
            gate = Path(directory) / "gate.json"
            gate.write_text(json.dumps({"26key/sentence": {"max_missing": 1}}))
            ok, failures = RUNNER.check_gate(RUNNER.aggregate(records), gate, records)
            self.assertTrue(ok, failures)
            records.append({**RUNNER.evaluate_case(case(id="sentence-2"), [action([])]), "round": 2})
            ok, failures = RUNNER.check_gate(RUNNER.aggregate(records), gate, records)
            self.assertFalse(ok)


class ComparisonCliTest(unittest.TestCase):
    def run_comparison(self, directory, baseline, candidate, limits=None, engine_artifacts=None):
        root = Path(directory)
        for name, data in (("baseline", baseline), ("candidate", candidate)):
            (root / f"{name}.json").write_text(json.dumps(data), encoding="utf-8")
        (root / "gate.json").write_text(json.dumps({"_comparison": limits if limits is not None else {
            "top1_rate_drop_max": 0, "top4_rate_drop_max": 0,
            "mrr_drop_max": 0, "missing_rate_increase_max": 0}}))
        return subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("compare-ime-evals.py")),
            "--baseline", str(root / "baseline.json"), "--candidate", str(root / "candidate.json"),
            "--gate", str(root / "gate.json"), "--report", str(root / "comparison"),
            *(["--engine-artifacts", str(engine_artifacts)] if engine_artifacts else [])],
            capture_output=True, encoding="utf-8",
            env={**os.environ, "PYTHONIOENCODING": "utf-8"})

    def reports(self):
        records = [RUNNER.evaluate_case(case(id=dataset, dataset=dataset), [action(["你好"])])
                   for dataset in ("written", "chat")]
        for row in records:
            row["round"] = 1
        metadata = {"metrics_version": 3, "identity": {"device_bytes_verified": True},
                    "probe_sha256": "a" * 64, "options": {}, "rounds": 1, "warmup_rounds": 0, "pool_limit": 0, "chunk_size": 100,
                    "device": {"ro.product.model": "synthetic protocol consumer"},
                    "case_files": [{"dataset": "synthetic", "sha256": "b" * 64}]}
        baseline = {**metadata, "records": records}
        return baseline, json.loads(json.dumps(baseline))

    def test_source_regression_is_not_offset_by_other_source_gain(self):
        baseline, candidate = self.reports()
        baseline["records"][1] = {**RUNNER.evaluate_case(
            case(id="chat", dataset="chat"), [action(["你号", "你好"])]), "round": 1}
        candidate["records"][0] = {**RUNNER.evaluate_case(
            case(id="written", dataset="written"), [action(["你号", "你好"])]), "round": 1}
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_comparison(directory, baseline, candidate)
            self.assertEqual(result.returncode, 1, result.stderr)
            report = json.loads((Path(directory) / "comparison.json").read_text(encoding="utf-8"))
            self.assertTrue(any(f.startswith("written/all/") and "top1_rate_drop_max" in f for f in report["failures"]))

    def test_different_probe_rejected_as_incomparable(self):
        baseline, candidate = self.reports()
        candidate["probe_sha256"] = "c" * 64
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_comparison(directory, baseline, candidate)
            self.assertEqual(result.returncode, 2)
            self.assertIn("probe_sha256", result.stderr)

    def test_engine_artifacts_allow_only_bound_code_with_unchanged_dictionary(self):
        baseline, candidate = self.reports()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = {"format_version": 1, "reason": "synthetic engine protocol upgrade"}
            for name, report in (("baseline", baseline), ("candidate", candidate)):
                data = f"synthetic engine version {name}".encode()
                probe = root / (name + ".bin")
                probe.write_bytes(data)
                report["probe_sha256"] = hashlib.sha256(data).hexdigest()
                report["identity"]["manifest_sha256"] = "d" * 64
                receipt[name] = {"probe": probe.name, "sha256": report["probe_sha256"]}
            path = root / "engine-artifacts.json"
            path.write_text(json.dumps(receipt), encoding="utf-8")
            result = self.run_comparison(directory, baseline, candidate, engine_artifacts=path)
            self.assertEqual(result.returncode, 0, result.stderr)
            candidate["identity"]["manifest_sha256"] = "e" * 64
            result = self.run_comparison(directory, baseline, candidate, engine_artifacts=path)
            self.assertEqual(result.returncode, 2)
            self.assertIn("基础包", result.stderr)
            candidate["identity"]["manifest_sha256"] = "d" * 64
            (root / "candidate.bin").write_bytes(b"modified after receipt")
            result = self.run_comparison(directory, baseline, candidate, engine_artifacts=path)
            self.assertEqual(result.returncode, 2)
            self.assertIn("实际探针字节不符", result.stderr)

    def test_latency_variation_does_not_change_quality_gate(self):
        baseline, candidate = self.reports()
        candidate["records"] = [{**RUNNER.evaluate_case(
            case(id=dataset, dataset=dataset), [action(["你好"], key_us=991)]), "round": 1}
            for dataset in ("written", "chat")]
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_comparison(directory, baseline, candidate)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_different_learning_partition_is_incomparable(self):
        baseline, candidate = self.reports()
        candidate["chunk_size"] = 1
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_comparison(directory, baseline, candidate)
            self.assertEqual(result.returncode, 2)
            self.assertIn("chunk_size", result.stderr)

    def test_misspelled_comparison_budget_cannot_silently_pass(self):
        baseline, candidate = self.reports()
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_comparison(directory, baseline, candidate, {"top_one_drop_max": 0})
            self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
