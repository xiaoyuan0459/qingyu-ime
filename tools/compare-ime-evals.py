#!/usr/bin/env python3
"""同引擎、夹具、选项和设备的配对 A/B 门禁；按来源和哈希桶分别报告。"""

import argparse
import json
import math
import sys
from pathlib import Path

from ime_eval_metrics import METRICS_VERSION, aggregate, check_gate
from ime_engine_comparison import verify_engine_artifacts


def compare(baseline: dict, candidate: dict, gate_path: Path, engine_artifacts: Path | None = None) -> dict:
    for report in (baseline, candidate):
        if report.get("metrics_version") != METRICS_VERSION:
            raise ValueError("指标版本不同或缺失；历史报告必须用新探针重跑，不能混比时延/CER")
        if not report.get("identity", {}).get("device_bytes_verified"):
            raise ValueError("报告没有设备实际字节校验记录")
    engine_change = verify_engine_artifacts(engine_artifacts, baseline, candidate) if engine_artifacts else None
    for field in ("probe_sha256", "options", "device", "case_files", "rounds", "warmup_rounds", "pool_limit", "chunk_size"):
        if field == "probe_sha256" and engine_change:
            continue
        if field not in baseline or field not in candidate or baseline[field] != candidate[field]:
            raise ValueError(f"A/B 条件不一致或缺失: {field}")
    left = {(r["id"], r["round"]): r for r in baseline["records"]}
    right = {(r["id"], r["round"]): r for r in candidate["records"]}
    if (len(left) != len(baseline["records"]) or len(right) != len(candidate["records"]) or
            set(left) != set(right)):
        raise ValueError("A/B 案例/轮次集合不一致或包含重复记录")
    if not left:
        raise ValueError("A/B 报告为空")
    definitions = ("dataset", "keyboard", "schema", "keys", "expected", "category", "group", "bucket", "mode", "domain")
    for key, row in left.items():
        if any(row.get(field) != right[key].get(field) for field in definitions):
            raise ValueError(f"A/B 案例定义不同: {key}")

    gate = json.loads(gate_path.read_text(encoding="utf-8"))
    limits = gate.get("_comparison")
    if not limits:
        raise ValueError("门禁未预先冻结 _comparison 退步预算")
    allowed = {"top1_rate_drop_max", "top4_rate_drop_max", "mrr_drop_max",
               "missing_rate_increase_max", "cer_mean_increase_max"}
    for metric, maximum in limits.items():
        if metric.startswith("_"):
            continue
        if metric not in allowed or not isinstance(maximum, (int, float)) or not math.isfinite(maximum) or maximum < 0:
            raise ValueError(f"未知或无效的配对门禁预算: {metric}")
    ok, failures = check_gate(aggregate(candidate["records"]), gate_path, candidate["records"])
    groups = {}
    # 整体只用于浏览；门禁在 来源×键盘×类别 及 来源×桶×键盘×类别 运行。
    for dataset, mode, domain in sorted({(r["dataset"], r["mode"], r["domain"]) for r in left.values()}):
        for bucket in [None, "train", "val", "test"]:
            selected = [r for r in left.values() if r["dataset"] == dataset and r["mode"] == mode and r["domain"] == domain and
                        (bucket is None or r["bucket"] == bucket)]
            a = aggregate(selected)
            b = aggregate([right[(r["id"], r["round"])] for r in selected])
            for cell, old in a.items():
                new = b[cell]
                name = f"{dataset}/{bucket or 'all'}/mode-{mode}/{domain}/{cell}"
                delta = {
                    "top1_rate": (new["top1"] - old["top1"]) / old["n"],
                    "top4_rate": (new["top4"] - old["top4"]) / old["n"],
                    "mrr": new["mrr"] - old["mrr"],
                    "missing_rate": (new["missing"] - old["missing"]) / old["n"],
                    "cer_mean": (new["cer_mean"] - old["cer_mean"]
                                 if old["cer_mean"] is not None else None),
                }
                groups[name] = {"n": old["n"], "baseline": old, "candidate": new,
                                "delta": {k: round(v, 6) if v is not None else None for k, v in delta.items()}}
                for metric, maximum in limits.items():
                    if metric.startswith("_"):
                        continue
                    increase = metric.endswith("_increase_max")
                    field = metric.removesuffix("_increase_max").removesuffix("_drop_max")
                    change = delta.get(field)
                    if change is not None and (change if increase else -change) > maximum + 1e-6:
                        failures.append(f"{name}: {metric} 超预算，变化 {change:+.6f}，预算 {maximum}")
    movements = []
    for key, old in left.items():
        new = right[key]
        if (old["rank"], old["top1"]) != (new["rank"], new["top1"]):
            movements.append({"id": key[0], "round": key[1], "dataset": old["dataset"],
                              "bucket": old["bucket"], "expected": old["expected"],
                              "old_rank": old["rank"], "new_rank": new["rank"],
                              "old_top1": old["top1"], "new_top1": new["top1"]})
    return {"passed": ok and not failures, "failures": failures,
            "groups": groups, "movements": movements,
            "engine_change": engine_change,
            "note": "哈希桶是夹具分组标签；不证明训练语料与测试语料独立"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--gate", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True, help="不带扩展名的输出路径")
    parser.add_argument("--engine-artifacts", type=Path, help="显式绑定两份探针实际字节的代码变更收据；词库/设备/选项仍须相同")
    args = parser.parse_args()
    try:
        result = compare(json.loads(args.baseline.read_text(encoding="utf-8")),
                         json.loads(args.candidate.read_text(encoding="utf-8")), args.gate, args.engine_artifacts)
    except ValueError as error:
        print(f"不可比: {error}", file=sys.stderr)
        return 2
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.with_name(args.report.name + ".json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# 候选配对比较", "", f"门禁：{'通过' if result['passed'] else '失败'}", "",
             result["note"], "", "| 来源/桶/键盘/类别 | n | Δtop1率 | Δtop4率 | ΔMRR | Δ窗口缺失率 | ΔCER |",
             "| --- | --- | --- | --- | --- | --- | --- |"]
    for key, group in result["groups"].items():
        d = group["delta"]
        lines.append(f"| {key} | {group['n']} | {d['top1_rate']} | {d['top4_rate']} | "
                     f"{d['mrr']} | {d['missing_rate']} | {d['cer_mean']} |")
    lines += ["", "## 失败项", ""] + [f"- {failure}" for failure in result["failures"]]
    lines += ["", "## 逐例变化", "", "| ID/轮次 | 来源 | 原名次 | 新名次 | 原首选 | 新首选 |",
              "| --- | --- | --- | --- | --- | --- |"]
    for row in result["movements"]:
        lines.append(f"| {row['id']}/{row['round']} | {row['dataset']} | {row['old_rank']} | "
                     f"{row['new_rank']} | {row['old_top1']} | {row['new_top1']} |")
    args.report.with_name(args.report.name + ".md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"门禁{'通过' if result['passed'] else '失败'}；{len(result['failures'])} 项；"
          f"{len(result['movements'])} 条名次/首选变化。报告：{args.report}.md")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
