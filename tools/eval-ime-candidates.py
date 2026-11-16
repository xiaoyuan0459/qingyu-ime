#!/usr/bin/env python3
"""逐动作候选评测运行器（审查方案 P0-A 的落地工具）。

与旧 `eval-dict-order.py` 的分工：那个只算**词库层**权重（离线近似，不含
essay / 用户词 / 语言模型）；本脚本起**真引擎**回放真实按键，报告每个案例
的候选名次与逐键耗时，是唯一能回答「引擎到底给了什么、慢在哪」的入口。

设计约束（docs/DICTIONARY_REVIEW_AND_PLAN_IMPROVEMENTS.md §4-P0-A）：
  * 读实际 pack manifest 的 schema 与文件清单，不手写固定分表列表；
    manifest 里不存在的表（如腾讯表）显式记为「未启用」，不悄悄参与基线。
  * 每次运行在设备上新建隔离 user 目录（无学习基线）；不连真实用户数据。
  * 指标按 键盘×类别 分列，不算抵消性的总分。
  * 计时边界由 `qingyu_probe --replay` 保证：只含 process_key 与
    get_context，进程启动、会话创建、打印都不算。

用法::

    python tools/eval-ime-candidates.py --pack build/frost/pack \
        --adb <adb.exe> --cases tools/eval-cases.tsv --report build/eval/baseline

    # 端到端前先部署 probe 与 pack（幂等，pack 变了要重跑）：
    python tools/eval-ime-candidates.py ... --setup

    # 质量门禁：基线报告跑完后，用 --gate 对照阈值文件，任何一项不达即退出码 1
    python tools/eval-ime-candidates.py ... --gate tools/eval-gate.json
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import uuid
from collections import defaultdict
from pathlib import Path

from ime_eval_metrics import (METRICS_VERSION, aggregate, aggregate_by, check_gate,
                              evaluate_case)
from ime_eval_identity import (assert_deployed, setup_device, sha256_of,
                               validate_device_root, verify_pack_files)
from ime_eval_inputs import encode_digits
from ime_eval_replay import run_replay

REPO = Path(__file__).resolve().parent.parent
DEFAULT_PROBE = (
    REPO / "imesdk/build/intermediates/cxx/Debug/3cm4w3wq/obj/arm64-v8a/qingyu_probe"
)
DEVICE_ROOT = "/data/local/tmp/qingyu-eval"

ADB = "adb"
ADB_SERIAL = ""


def adb(*args: str, check: bool = True) -> str:
    cmd = [ADB]
    if ADB_SERIAL:
        cmd += ["-s", ADB_SERIAL]
    cmd += list(args)
    result = subprocess.run(cmd, capture_output=True, timeout=600)
    out = result.stdout.decode("utf-8", "ignore")
    err = result.stderr.decode("utf-8", "ignore")
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {args} 失败: {err.strip() or out.strip()}")
    return out


def load_manifest(pack: Path) -> dict:
    manifest_path = pack / "manifest.json"
    if not manifest_path.exists():
        raise SystemExit(f"pack 缺 manifest.json: {pack}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    # 夹具引用的 schema 必须在 manifest 里；缺了就显式失败，不静默跳过。
    return manifest


def read_cases(paths: list[Path]) -> list[dict]:
    """夹具 TSV。列固定：id keyboard schema keys expected max_rank category group source

    keys 为空格分隔的动作 token：k:<字母|数字串>、bs:<n>、sel:<i>、selcur:<i>、
    page:next、page:prev、snap。expected 用 | 分隔允许答案（精确匹配候选文本）。
    """
    cases: list[dict] = []
    for path in paths:
        cases.extend({**row, "dataset": path.stem} for row in _read_case_file(path))
    if not cases:
        raise SystemExit("夹具为空")
    seen = set()
    for row in cases:
        if row["id"] in seen:
            raise SystemExit(f"案例 ID 重复: {row['id']}")
        seen.add(row["id"])
    return cases


def _read_case_file(path: Path) -> list[dict]:
    cases = []
    with path.open(encoding="utf-8-sig") as stream:
        header = stream.readline().rstrip("\n").split("\t")
        required = {"id", "keyboard", "schema", "keys", "expected",
                    "max_rank", "category", "group", "source"}
        missing = required - set(header)
        if missing:
            raise SystemExit(f"夹具缺列: {missing}；表头={header}")
        for lineno, line in enumerate(stream, start=2):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            values = line.split("\t")
            if len(values) != len(header):
                raise SystemExit(f"夹具第 {lineno} 行列数不符: {line}")
            row = dict(zip(header, values))
            row["max_rank"] = int(row["max_rank"] or 0)
            cases.append(row)
    if not cases:
        raise SystemExit(f"夹具为空: {path}")
    return cases


def build_replay_script(cases: list[dict], options: dict[str, str], pool_limit: int = 2000) -> str:
    lines = []
    for row in cases:
        lines.append(f"case\t{row['id']}")
        lines.append(f"mode\t{row.get('mode', '0')}")
        for name, value in options.items():
            lines.append(f"option\t{name}\t{value}")
        for token in row["keys"].split():
            kind, _, arg = token.partition(":")
            if kind == "k":
                lines.append(f"key\t{arg}")
            elif kind == "digits":
                lines.append(f"key\t{encode_digits(arg)}")
            elif kind == "bs":
                lines.append(f"backspace\t{arg or 1}")
            elif kind == "sel":
                lines.append(f"select\t{arg}")
            elif kind == "selcur":
                lines.append(f"selectpage\t{arg}")
            elif kind == "page":
                lines.append(f"page\t{arg}")
            elif kind == "snap":
                lines.append("snapshot")
            else:
                raise SystemExit(f"无法识别的动作 token: {token}（案例 {row['id']}）")
        if pool_limit:
            lines.append(f"scan\t{row['expected']}\t{pool_limit}")
    return "\n".join(lines) + "\n"


def report_path(base: Path, ext: str) -> Path:
    """报告文件名拼扩展名，不按 suffix 替换。

    `Path("x-s0.1").with_suffix(".json")` 会把 `.1` 当后缀换掉，`x-s0.1` 与
    `x-s0.3` 于是写进同一个 `x-s0.json`，后一轮静默覆盖前一轮。
    """
    if base.suffix in (".json", ".md"):
        return base.with_suffix(ext)
    return base.with_name(base.name + ext)


def main() -> int:
    global ADB
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, default=REPO / "build/frost/pack")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="", help="多设备时指定 adb -s 序列号")
    parser.add_argument("--probe", type=Path, default=DEFAULT_PROBE)
    parser.add_argument("--cases", type=Path, action="append", default=None,
                        help="夹具 TSV，可多次给出；默认 手工夹具 + essay 派生夹具")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--setup", action="store_true", help="先部署 probe 与 pack")
    parser.add_argument("--option", action="append", default=[],
                        help="固定引擎选项，如 correction:0；可多次")
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--pool-limit", type=int, default=2000,
                        help="最终候选池遍历上限，遍历另计时；0 仅观察当前页")
    parser.add_argument("--warmup-rounds", type=int, default=0,
                        help="每轮前完整回放但不计分；使用独立 user 目录，性能对照建议 1")
    parser.add_argument("--gate", type=Path, help="阈值 JSON，任何一项不达退出码 1")
    parser.add_argument("--chunk-size", type=int, default=100, help="每个独立进程的案例数；逐块检查点")
    parser.add_argument("--resume", action="store_true", help="核对输入指纹后复用已完成块")
    args = parser.parse_args()
    global ADB_SERIAL
    ADB = args.adb
    ADB_SERIAL = args.serial
    if args.rounds < 1:
        parser.error("--rounds 至少为 1")
    if not 0 <= args.pool_limit <= 2000000:
        parser.error("--pool-limit 必须在 0..2000000")
    if args.warmup_rounds < 0:
        parser.error("--warmup-rounds 不能为负")
    if not 1 <= args.chunk_size <= 1000:
        parser.error("--chunk-size 必须在 1..1000")
    validate_device_root(DEVICE_ROOT)

    adb("shell", f"mkdir -p {DEVICE_ROOT}")
    # 同设备共享部署目录；拒绝并发换包，退出时释放，不触碰应用私有数据。
    locked = False
    try:
        adb("shell", f"mkdir {DEVICE_ROOT}/.run-lock")
        locked = True
        return evaluate(args)
    finally:
        if locked:
            adb("shell", f"rmdir {DEVICE_ROOT}/.run-lock", check=False)


def evaluate(args) -> int:

    manifest = load_manifest(args.pack)
    verified_files = verify_pack_files(args.pack, manifest)
    binary_files = [f for f in verified_files if f["path"].endswith((".table.bin", ".prism.bin"))]
    case_files = args.cases or [REPO / "tools/eval-cases.tsv"]
    if args.cases is None:
        derived = REPO / "tools/eval-cases-essay.tsv"
        if derived.exists():
            case_files.append(derived)
    cases = read_cases(case_files)
    bad_schemas = sorted({c["schema"] for c in cases} - set(manifest["schemas"]))
    if bad_schemas:
        raise SystemExit(f"夹具引用了 manifest 没有的方案: {bad_schemas}")
    options = dict(opt.split(":", 1) for opt in args.option)

    if args.setup:
        identity = setup_device(adb, DEVICE_ROOT, args.probe, args.pack, manifest)
    else:
        identity = assert_deployed(adb, DEVICE_ROOT, args.pack, manifest, args.probe)

    workdir = args.report.parent
    workdir.mkdir(parents=True, exist_ok=True)
    raw_dir = workdir / f"{args.report.name}-checkpoints"
    device = {prop: adb("shell", "getprop", prop).strip() for prop in
              ("ro.product.model", "ro.build.version.sdk", "ro.product.cpu.abi")}
    fingerprint = {"identity": identity, "probe": identity["probe_sha256"], "options": options,
                   "device": device,
                   "evaluator": {name: sha256_of(REPO / "tools" / name) for name in
                                 ("eval-ime-candidates.py", "ime_eval_replay.py", "ime_eval_metrics.py", "ime_eval_inputs.py", "ime_eval_identity.py")},
                   "cases": [{"name": p.name, "sha256": sha256_of(p)} for p in case_files],
                   "rounds": args.rounds, "warmup": args.warmup_rounds,
                   "pool_limit": args.pool_limit, "chunk_size": args.chunk_size}
    fingerprint_path = raw_dir / "inputs.json"
    if fingerprint_path.exists():
        if not args.resume or json.loads(fingerprint_path.read_text(encoding="utf-8")) != fingerprint:
            raise SystemExit("已有检查点；使用 --resume 且保持全部输入指纹相同，或另选报告名")
    else:
        raw_dir.mkdir(parents=True, exist_ok=True)
        fingerprint_path.write_text(json.dumps(fingerprint, ensure_ascii=False, indent=2), encoding="utf-8")
    prefix = [ADB] + (["-s", ADB_SERIAL] if ADB_SERIAL else [])
    all_records = []
    round_summaries = []
    probe_meta = []
    for round_no in range(1, args.rounds + 1):
        # 每轮独立 user 目录：无学习基线，轮间互不污染。
        for schema in sorted({c["schema"] for c in cases}):
            schema_cases = [c for c in cases if c["schema"] == schema]
            for offset in range(0, len(schema_cases), args.chunk_size):
                rows = schema_cases[offset:offset + args.chunk_size]
                chunk_dir = raw_dir / f"r{round_no}-{schema}-{offset:05}"
                done = chunk_dir / "complete.json"
                if done.exists():
                    checkpoint = json.loads(done.read_text(encoding="utf-8"))
                else:
                    attempt = chunk_dir / uuid.uuid4().hex[:12]
                    attempt.mkdir(parents=True)
                    user_dir = f"{DEVICE_ROOT}/user/{uuid.uuid4().hex}"
                    adb("shell", f"mkdir -p {user_dir}")
                    script = build_replay_script(rows, options, args.pool_limit)
                    for warmup in range(args.warmup_rounds):
                        warm_dir = attempt / f"warm-{warmup}"; warm_dir.mkdir()
                        warm_user = user_dir + f"-warm-{warmup}"
                        adb("shell", f"mkdir -p {warm_user}")
                        run_replay(adb, prefix, DEVICE_ROOT, schema, script, warm_user, warm_dir)
                    by_case, meta = run_replay(adb, prefix, DEVICE_ROOT, schema, script, user_dir, attempt)
                    if set(by_case) != {r["id"] for r in rows}:
                        raise RuntimeError("probe 输出案例集合不符")
                    checkpoint = {"meta": meta, "records": [{**evaluate_case(r, by_case[r["id"]]), "round": round_no} for r in rows]}
                    temporary = chunk_dir / "complete.tmp"
                    temporary.write_text(json.dumps(checkpoint, ensure_ascii=False), encoding="utf-8")
                    temporary.replace(done)
                all_records.extend(checkpoint["records"])
                probe_meta.append(checkpoint["meta"])
                print(f"r{round_no} {schema}: {min(offset + args.chunk_size, len(schema_cases))}/{len(schema_cases)}", flush=True)
        round_summaries.append(aggregate(
            [r for r in all_records if r["round"] == round_no]))

    cells = aggregate(all_records)
    report = {
        "metrics_version": METRICS_VERSION,
        "pool_limit": args.pool_limit,
        "created_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "identity": identity,
        "case_files": [{"dataset": path.stem, "sha256": sha256_of(path)} for path in case_files],
        "device": device,
        "probe_meta": probe_meta,
        "raw_dir": str(raw_dir),
        "timing": "process_key + get_context; excludes serialization; nearest-rank pooled actions",
        "missing_scope": "pool absence only when exhausted; truncated pools are explicit",
        "pack": {"id": manifest["id"], "version": manifest["version"],
                 "revision": manifest.get("source", {}).get("revision"),
                 "schemas": manifest["schemas"],
                 "effective_source": json.loads((args.pack / "source-provenance.json").read_text(encoding="utf-8"))
                    if (args.pack / "source-provenance.json").exists() else None,
                 "engine_binaries": binary_files,
                 "tables_enabled": sorted({
                     f["path"] for f in binary_files
                     if f["path"].endswith(".table.bin")})},
        "probe_sha256": identity["probe_sha256"],
        "chunk_size": args.chunk_size,
        "options": options,
        "rounds": args.rounds,
        "warmup_rounds": args.warmup_rounds,
        "user_state": "fresh (无学习基线)",
        "process_initialization_us": [m.get("engine_setup_us") for m in probe_meta],
        "cells": cells,
        "by_dataset": aggregate_by(all_records, "dataset"),
        "by_bucket": aggregate_by(all_records, "bucket"),
        "by_mode": aggregate_by(all_records, "mode"),
        "by_domain": aggregate_by(all_records, "domain"),
        "by_input_length": aggregate_by(all_records, "input_length"),
        "round_summaries": round_summaries,
        "records": all_records,
    }
    report_path(args.report, ".json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    failures = [r for r in all_records
                if r["max_rank"] and (r["rank"] is None or r["rank"] > r["max_rank"])]
    md = ["# 逐动作候选基线报告", "",
          f"- pack: {manifest['id']} {manifest['version']}",
          f"- 启用的 table: {', '.join(report['pack']['tables_enabled'])}",
          f"- user 状态: {report['user_state']}；轮数 {args.rounds}",
          f"- 选项: {options or '引擎默认'}", "",
          f"- 指标版本: {METRICS_VERSION}；逐键 = process_key + get_context，排除序列化；按全部动作求最近秩分位数",
          "- 候选池遍历另计时；只有 pool_exhausted 才证明池内缺失。CER 包含错误和空输出",
          f"- 原始合成动作与 JSONL: {raw_dir}", "",
          "| 键盘/类别 | n | top1 | top4 | MRR | 窗口缺失 | CER均值 | 键p50 µs | 键p95 µs | 键p99 µs |",
          "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for key, cell in cells.items():
        md.append(
            f"| {key} | {cell['n']} | {cell['top1']} | {cell['top4']} | "
            f"{cell['mrr']} | {cell['missing']} | {cell['cer_mean']} | {cell['key_p50_us']} | "
            f"{cell['key_p95_us']} | {cell['key_p99_us']} |")
    for field in ("by_dataset", "by_bucket", "by_mode", "by_domain"):
        md += ["", f"## {field}", "", "| 分组 | 键盘/类别 | n | top1 | top4 | MRR | 窗口缺失 | CER均值 |",
               "| --- | --- | --- | --- | --- | --- | --- | --- |"]
        for group, group_cells in report[field].items():
            for key, cell in group_cells.items():
                md.append(f"| {group} | {key} | {cell['n']} | {cell['top1']} | {cell['top4']} | "
                          f"{cell['mrr']} | {cell['missing']} | {cell['cer_mean']} |")
    md += ["", f"## 未达名次要求的案例（{len(failures)}）", "",
           "| id | 键盘 | 类别 | 期望 | 要求名次 | 实际名次 | 首选 |", "| --- | --- | --- | --- | --- | --- | --- |"]
    for r in failures:
        md.append(f"| {r['id']} | {r['keyboard']} | {r['category']} | "
                  f"{'|'.join(r['expected']).replace('|', '/')} | "
                  f"{r['max_rank']} | {r['rank']} | {r['top1']} |")
    report_path(args.report, ".md").write_text("\n".join(md) + "\n", encoding="utf-8")

    print(f"报告: {report_path(args.report, '.md')}")
    for key, cell in cells.items():
        print(f"  {key:24s} n={cell['n']:3d} top1={cell['top1']:3d} "
              f"mrr={cell['mrr']:.3f} miss={cell['missing']}")

    if args.gate:
        ok, gate_failures = check_gate(cells, args.gate, all_records)
        if not ok:
            print("门禁未过：", file=sys.stderr)
            for line in gate_failures:
                print(f"  {line}", file=sys.stderr)
            return 1
        print("门禁通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
