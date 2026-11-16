"""把统计使用的分词词表绑定到当前锚源；拒绝同尺度但不同 revision 的参照。"""

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile

from rime_source_provenance import digest, verify_source_snapshot


ROOT = Path(__file__).resolve().parent.parent


def bind_training_source(summary_path: Path, anchor_dir: Path, clone_dir: Path) -> dict:
    source = anchor_dir.parent
    snapshot = verify_source_snapshot(source)
    revision = subprocess.check_output(["git", "-C", str(clone_dir), "rev-parse", "HEAD"], text=True).strip()
    if revision != snapshot["upstream_revision"]:
        raise SystemExit("参照 clone 的 revision 与当前锚源快照不符")
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if not summary.get("userdict"):
        raise SystemExit("训练计数未固定引擎词表分词字典")
    spec = importlib.util.spec_from_file_location("qingyu_chat_stats", ROOT / "tools/freq-chat-stats.py")
    stats = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stats)
    owned = (ROOT / ".tmp").resolve()
    if not owned.is_relative_to(ROOT):
        raise SystemExit("本地验证临时目录越界")
    owned.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="freq-source-", dir=owned) as temporary:
        if not Path(temporary).resolve().is_relative_to(owned):
            raise SystemExit("临时验证路径越界")
        _, identity = stats.build_userdict(anchor_dir, Path(temporary) / "userdict.txt")
        if identity["sha256"] != summary["userdict"]["sha256"]:
            raise SystemExit("统计分词词表与当前锚源不同；拒绝混合不同来源的频率")
    return {"source_sha256": digest(source / "source-provenance.json"),
            "upstream_revision": revision, "userdict_sha256": identity["sha256"],
            "words": identity["words"],
            "method": "canonical userdict reconstructed from effective source and byte-matched to count summary"}
