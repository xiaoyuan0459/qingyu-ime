"""训练/评测对话分离及计数溯源契约；全部数据独立合成。"""

import gzip
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from freq_chat_provenance import (EXCLUSION_RULE, dialog_digest, excluded_dialogs,
                                  file_identity, validate_training_counts)

SPEC = importlib.util.spec_from_file_location(
    "chat_stats", Path(__file__).with_name("freq-chat-stats.py"))
STATS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STATS)


def write_corpus(path, records):
    with gzip.open(path, "wt", encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")


class ChatPartitionTest(unittest.TestCase):
    def test_normalized_evaluation_dialog_never_reaches_training_tokenizer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_corpus(root / "evaluation.gz", [["你 好！", "谢 谢"]])
            write_corpus(root / "train.gz", [["你好", "谢谢。"], ["独 立 测 试"]])
            stats = {"dialogs": 0, "dialogs_read": 0, "dialogs_excluded": 0}
            rows = list(STATS.iter_utterances([root / "train.gz"], stats,
                        excluded_dialogs([root / "evaluation.gz"])))
            self.assertEqual(rows, [("独 立 测 试", None)])
            self.assertEqual(stats, {"dialogs": 1, "dialogs_read": 2, "dialogs_excluded": 1})

    def test_same_concatenation_with_different_turns_stays_distinct(self):
        self.assertNotEqual(dialog_digest(["甲乙", "丙"]), dialog_digest(["甲", "乙丙"]))

    def test_bad_exclusion_record_fails_without_echoing_text(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evaluation.gz"
            write_corpus(path, [{"bad": "synthetic secret"}])
            with self.assertRaises(SystemExit) as raised:
                excluded_dialogs([path])
            self.assertNotIn("synthetic secret", str(raised.exception))

    def test_fusion_accepts_bound_training_counts_and_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            counts = root / "counts.tsv"
            counts.write_text("独立\t3\n", encoding="utf-8")
            summary = {"format_version": 2, "partition_role": "train", "counts": file_identity(counts),
                       "inputs": [{"sha256": "a" * 64}], "excluded_inputs": [{"sha256": "b" * 64}],
                       "excluded_dialog_hashes": 1, "exclusion_rule": EXCLUSION_RULE,
                       "segmentation": "synthetic contract", "userdict": None}
            path = root / "summary.json"
            path.write_text(json.dumps(summary), encoding="utf-8")
            self.assertEqual(validate_training_counts(counts, path)["counts"], file_identity(counts))
            counts.write_text("独立\t4\n", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "SHA-256"):
                validate_training_counts(counts, path)


    def test_fusion_rejects_diagnostic_counts_and_overlapping_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            counts = root / "counts.tsv"
            counts.write_bytes(b"synthetic\t3\n")
            summary = {"format_version": 2, "partition_role": "diagnostic"}
            path = root / "summary.json"
            path.write_text(json.dumps(summary))
            with self.assertRaisesRegex(SystemExit, "train"):
                validate_training_counts(counts, path)
            summary.update(partition_role="train", counts=file_identity(counts),
                inputs=[{"sha256": "a" * 64}], excluded_inputs=[{"sha256": "a" * 64}],
                exclusion_rule=EXCLUSION_RULE)
            path.write_text(json.dumps(summary))
            with self.assertRaisesRegex(SystemExit, "同一文件"):
                validate_training_counts(counts, path)


class ReadingInjectionTest(unittest.TestCase):
    def run_injection(self, root):
        return subprocess.run([sys.executable, "-B",
            str(Path(__file__).with_name("freq-apply-reading.py")), str(root),
            "--freq", str(root / "overrides.tsv")], capture_output=True)

    def test_reading_overrides_cover_all_anchor_tables(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "base.dict.yaml").write_text("合成\the cheng\t10\n", encoding="utf-8")
            (root / "others.dict.yaml").write_text("独立\tdu li\t20\n", encoding="utf-8")
            (root / "overrides.tsv").write_text("合成\the cheng\t11\n独立\tdu li\t21\n", encoding="utf-8")
            result = self.run_injection(root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((root / "others.dict.yaml").read_text(encoding="utf-8"), "独立\tdu li\t21\n")

    def test_coverage_failure_does_not_partially_rewrite_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = "合成\the cheng\t10\n"
            source = root / "base.dict.yaml"
            source.write_text(original, encoding="utf-8")
            (root / "overrides.tsv").write_text("合成\the cheng\t11\n缺项\tque xiang\t21\n", encoding="utf-8")
            result = self.run_injection(root)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(source.read_text(encoding="utf-8"), original)

    def test_invalid_override_row_does_not_rewrite_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = "合成\the cheng\t10\n"
            source = root / "base.dict.yaml"
            source.write_text(original, encoding="utf-8")
            (root / "overrides.tsv").write_text("合成\the cheng\t11\ninvalid-row\n", encoding="utf-8")
            result = self.run_injection(root)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(source.read_text(encoding="utf-8"), original)

    def test_duplicate_rows_keep_ratios_and_zero_weight_semantics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "base.dict.yaml").write_text("复合\tfu he\t10\n空词\tkong ci\t0\n大权\tda quan\t1000001\n", encoding="utf-8")
            (root / "others.dict.yaml").write_text("复合\tfu he\t5\n空词\tkong ci\t5\n", encoding="utf-8")
            (root / "overrides.tsv").write_text("复合\tfu he\t20\n空词\tkong ci\t10\n大权\tda quan\t1000003\n", encoding="utf-8")
            result = self.run_injection(root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((root / "base.dict.yaml").read_text(encoding="utf-8"), "复合\tfu he\t20\n空词\tkong ci\t0\n大权\tda quan\t1000003\n")
            self.assertEqual((root / "others.dict.yaml").read_text(encoding="utf-8"), "复合\tfu he\t10\n空词\tkong ci\t10\n")



if __name__ == "__main__":
    unittest.main()
