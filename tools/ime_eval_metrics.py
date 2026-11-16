"""候选回放的报告口径；输入为 probe JSONL，缺失只指已观察候选窗口。"""

import hashlib
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path


METRICS_VERSION = 3
SPLIT_SALT = "qingyu-eval-p0a"


def split_bucket(group: str) -> str:
    value = int(hashlib.sha256(f"{SPLIT_SALT}:{group}".encode()).hexdigest()[:8], 16) % 100
    return "train" if value < 60 else "val" if value < 80 else "test"


def percentile(samples: list[float], fraction: float) -> float | None:
    """最近秩法；按动作合并，不能拿各案例分位数再次求分位数。"""
    if not samples:
        return None
    return round(sorted(samples)[max(0, math.ceil(len(samples) * fraction) - 1)], 3)


def edit_distance(a: str, b: str) -> int:
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[-1] + 1,
                               previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def evaluate_case(row: dict, actions: list[dict]) -> dict:
    final = next((a for a in reversed(actions) if "candidates" in a), {})
    expected = [text for text in row["expected"].split("|") if text]
    texts = [c["text"] for c in final.get("candidates", [])]
    offset = final.get("page_no", 0) * final.get("page_size", 0)
    rank = next((offset + i for i, text in enumerate(texts, 1) if text in expected), None)
    if final.get("action") == "scan":
        rank = min((c["engine_index"] + 1 for c in final.get("matches", [])
                    if c["text"] in expected and c.get("full_input", True)), default=None)
    keys = [a for a in actions if a.get("action") in ("key", "backspace")]
    samples = [a["key_us"] + a["ctx_us"] for a in keys]
    record = {
        **{name: row[name] for name in ("id", "keyboard", "schema", "category", "group")},
        "dataset": row.get("dataset", "unspecified"), "source": row.get("source", ""),
        "mode": str(row.get("mode", "0")), "domain": row.get("domain", "unspecified"),
        "bucket": split_bucket(row["group"]), "keys": row.get("keys", ""),
        "expected": expected, "max_rank": row["max_rank"], "rank": rank,
        "top1": texts[0] if texts and offset == 0 else None,
        "missing": bool(expected) and rank is None,
        "candidate_count": len(texts), "page_no": final.get("page_no", 0),
        "pool_scanned": final.get("pool_scanned", len(texts)),
        "pool_exhausted": final.get("pool_exhausted", False),
        "pool_stop_reason": final.get("pool_stop_reason", "page"),
        "pool_truncated": final.get("pool_stop_reason") == "limit" or (rank is None and not final.get("pool_exhausted", False)),
        "session_setup_us": next((a.get("session_setup_us") for a in actions if a.get("type") == "case"), None),
        "pool_us": final.get("pool_us"),
        "candidate_sha256": hashlib.sha256(json.dumps(final.get("candidates", []), ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
        "preedit": final.get("preedit"),
        "input_length": sum(a.get("action") == "key" for a in keys),
        "key_samples_us": samples,
        "process_samples_us": [a["key_us"] for a in keys],
        "context_samples_us": [a["ctx_us"] for a in keys],
        "key_p50_us": percentile(samples, 0.5), "key_p95_us": percentile(samples, 0.95),
    }
    if row["category"] == "sentence" and expected:
        # 首选错误或答案不在窗口时也要计错；允许答案中取最小归一化距离。
        errors, size = min(((edit_distance(record["top1"] or "", target), len(target))
                            for target in expected), key=lambda pair: pair[0] / pair[1])
        record.update(cer=round(errors / size, 6), cer_errors=errors, cer_ref_chars=size)
    return record


def aggregate(records: list[dict]) -> dict:
    grouped = defaultdict(list)
    for record in records:
        grouped[f"{record['keyboard']}/{record['category']}"].append(record)
    cells = {}
    for name, rows in sorted(grouped.items()):
        samples = [value for row in rows for value in row["key_samples_us"]]
        sentences = [row for row in rows if "cer" in row]
        ranks = [row["rank"] for row in rows if row["rank"] is not None]
        cells[name] = {
            "n": len(rows), "top1": ranks.count(1),
            "top4": sum(rank <= 4 for rank in ranks),
            "top4_score": round(sum((5 - rank) / 4 for rank in ranks if rank <= 4) / len(rows), 6),
            "mrr": round(sum(1 / rank for rank in ranks) / len(rows), 6),
            "missing": sum(row["missing"] for row in rows),
            "pool_truncated": sum(row["pool_truncated"] for row in rows),
            "key_samples": len(samples), "key_p50_us": percentile(samples, 0.5),
            "key_p95_us": percentile(samples, 0.95), "key_p99_us": percentile(samples, 0.99),
            "key_max_us": max(samples) if samples else None,
            "session_setup_p50_us": percentile([r["session_setup_us"] for r in rows if r["session_setup_us"] is not None], 0.5),
            "session_setup_p95_us": percentile([r["session_setup_us"] for r in rows if r["session_setup_us"] is not None], 0.95),
            "cer_mean": round(statistics.mean(row["cer"] for row in sentences), 6) if sentences else None,
            "cer_micro": round(sum(row["cer_errors"] for row in sentences) /
                               sum(row["cer_ref_chars"] for row in sentences), 6) if sentences else None,
        }
    return cells


def aggregate_by(records: list[dict], field: str) -> dict:
    groups = defaultdict(list)
    for row in records:
        groups[str(row[field])].append(row)
    return {key: aggregate(rows) for key, rows in sorted(groups.items())}


def check_gate(cells: dict, gate_path: Path, records: list[dict] | None = None) -> tuple[bool, list[str]]:
    rounds = sorted({r.get("round", 1) for r in records or []})
    if len(rounds) > 1:
        # 计数阈值来自单轮基线；多轮必须逐轮过关，不能累加后再套相同配额。
        failures = []
        for number in rounds:
            rows = [r for r in records if r.get("round", 1) == number]
            _, messages = check_gate(aggregate(rows), gate_path, rows)
            failures.extend(f"round {number}: {message}" for message in messages)
        return not failures, failures
    gate = json.loads(gate_path.read_text(encoding="utf-8"))
    failures = []

    def check(actual, limits, prefix=""):
        for key, thresholds in limits.items():
            if key.startswith("_"):
                continue
            cell = actual.get(key)
            if cell is None:
                failures.append(f"{prefix}{key}: 报告里没有该分列")
                continue
            for name, bound in thresholds.items():
                if name.startswith("_"):
                    continue
                maximum = name.startswith("max_") or name.endswith("_max")
                metric = name[4:] if name.startswith("max_") else name.removesuffix("_min").removesuffix("_max")
                value = cell.get(metric)
                if value is None or (value > bound if maximum else value < bound):
                    failures.append(f"{prefix}{key}.{metric} {value} {'>' if maximum else '<'} {bound}")

    check(cells, gate)
    for dataset, limits in gate.get("_by_dataset", {}).items():
        check(aggregate([r for r in records or [] if r["dataset"] == dataset]), limits, dataset + ":")
    for case_id, max_rank in gate.get("_critical", {}).items():
        rows = [r for r in records or [] if r["id"] == case_id]
        if not rows or any(r["rank"] is None or r["rank"] > max_rank for r in rows):
            failures.append(f"关键案例 {case_id}: 要求每轮 rank <= {max_rank}")
    return not failures, failures
