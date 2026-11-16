"""有效导入与编译收据的文件边界，不模拟引擎候选行为。"""

import json
from pathlib import Path
import tempfile
import unittest

from rime_source_provenance import digest, effective_files, source_snapshot, verify_compilation, verify_source_snapshot


class SourceProvenanceTest(unittest.TestCase):
    def source(self, root):
        source = root / "src"; source.mkdir()
        (source / "cn_dicts").mkdir(); (source / "cn_dicts_cell").mkdir()
        (source / "pinyin.dict.yaml").write_text(
            "---\nname: pinyin\nuse_preset_vocabulary: false\nimport_tables:\n"
            "  - cn_dicts/base\n  # - cn_dicts/tencent\n  - cn_dicts_cell/computer\n...\n", encoding="utf-8")
        for name, row in (("cn_dicts/base", "合成\the cheng\t10\n"),
                          ("cn_dicts/tencent", "禁入\tjin ru\t99999\n"),
                          ("cn_dicts_cell/computer", "线程\txian cheng\t20\n")):
            (source / (name + ".dict.yaml")).write_text("---\nname: table\n...\n" + row, encoding="utf-8")
        source_snapshot(source, {"revision": "synthetic-revision"})
        return source

    def test_disabled_table_is_excluded_and_active_cell_table_is_included(self):
        with tempfile.TemporaryDirectory() as directory:
            source = self.source(Path(directory))
            active = {p.relative_to(source).as_posix() for p in effective_files(source)}
            self.assertNotIn("cn_dicts/tencent.dict.yaml", active)
            self.assertIn("cn_dicts_cell/computer.dict.yaml", active)
            snapshot = verify_source_snapshot(source)
            self.assertEqual(sum(row["explicit_code_rows"] for row in snapshot["effective_tables"]), 2)

    def test_source_mutation_and_extra_files_invalidate_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            source = self.source(Path(directory))
            (source / "unexpected.dict.yaml").write_text("unexpected", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "集合"):
                verify_source_snapshot(source)
            (source / "unexpected.dict.yaml").unlink()
            (source / "cn_dicts_cell/computer.dict.yaml").write_text("changed", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "快照已变化"):
                verify_source_snapshot(source)

    def test_compilation_receipt_rejects_other_source_and_unlisted_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = self.source(root)
            compiled = root / "out"; compiled.mkdir()
            binary = compiled / "pinyin.table.bin"; binary.write_bytes(b"synthetic receipt payload")
            receipt = {"source_sha256": "not-this-source", "files": [{"path": binary.name, "sha256": digest(binary)}]}
            path = compiled / "build-receipt.json"; path.write_text(json.dumps(receipt))
            with self.assertRaisesRegex(ValueError, "另一份"):
                verify_compilation(source, compiled)
            receipt["source_sha256"] = digest(source / "source-provenance.json")
            path.write_text(json.dumps(receipt))
            (compiled / "stale.prism.bin").write_bytes(b"stale")
            with self.assertRaisesRegex(ValueError, "旧文件残留"):
                verify_compilation(source, compiled)


if __name__ == "__main__":
    unittest.main()
