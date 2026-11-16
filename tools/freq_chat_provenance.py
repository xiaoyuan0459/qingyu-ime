"""聊天训练数据边界；仅保存文件身份、对话摘要与计数，不输出语料原句。"""

import gzip
import hashlib
import json
import unicodedata
from pathlib import Path


EXCLUSION_RULE = "NFKC; keep Unicode letters/numbers; preserve turn boundaries; SHA-256"


def file_identity(path: Path) -> dict:
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"name": path.name, "size": path.stat().st_size, "sha256": digest}


def dialog_digest(record: list[str]) -> bytes:
    normalized = ["".join(c for c in unicodedata.normalize("NFKC", text)
                           if unicodedata.category(c)[0] in "LN") for text in record]
    # 保留每个话轮的位置，避免 [甲乙,丙] 与 [甲,乙丙] 被拼成同一条对话。
    return hashlib.sha256(json.dumps(normalized, ensure_ascii=False).encode("utf-8")).digest()


def excluded_dialogs(paths: list[Path]) -> set[bytes]:
    hashes = set()
    for path in paths:
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            for lineno, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    raise SystemExit(f"排除语料格式错误: {path.name}:{lineno}") from None
                if not isinstance(record, list) or not all(isinstance(t, str) for t in record):
                    raise SystemExit(f"排除语料格式错误: {path.name}:{lineno}")
                hashes.add(dialog_digest(record))
    return hashes


def validate_training_counts(counts: Path, summary_path: Path) -> dict:
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if summary.get("format_version") != 2 or summary.get("partition_role") != "train":
        raise SystemExit("聊天源必须是带分区溯源的 train 计数；旧全量计数不能用于训练实验")
    if summary.get("counts") != file_identity(counts):
        raise SystemExit("聊天计数的 SHA-256/大小/文件名与统计摘要不一致")
    sources = summary.get("inputs", [])
    excluded = summary.get("excluded_inputs", [])
    if not sources or not excluded or summary.get("exclusion_rule") != EXCLUSION_RULE:
        raise SystemExit("训练计数必须记录验证/测试排除文件及对话去重规则")
    if {f["sha256"] for f in sources} & {f["sha256"] for f in excluded}:
        raise SystemExit("训练输入与排除输入包含同一文件")
    return {key: summary[key] for key in ("partition_role", "inputs", "excluded_inputs",
            "exclusion_rule", "excluded_dialog_hashes", "counts", "segmentation", "userdict")}
