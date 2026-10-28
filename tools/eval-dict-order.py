#!/usr/bin/env python3
"""用输入场景评测集给 `--strength` 选值。

`tools/gen-dict-freq.py` 的 `--strength` 决定「往上游权重靠」还是「往语料频率靠」。
这个参数不该拍脑袋定：本脚本用一批「敲这个拼音最可能想要哪个词」的用例，
逐档算 top1 / top3 / MRR，把选择变成可比较的数字。

评测只覆盖多字词。单字权重来自 8105 / 41448 字表，频率层不碰，不在评测范围。

注意这是**离线**评测：只算词库层权重，不含 essay 补充词条、用户词频与句子级
语言模型。它适合比较不同 strength 的相对优劣，不能代替真机 `qingyu_probe`。

用法::

    python tools/eval-dict-order.py                       # 跑默认几档
    python tools/eval-dict-order.py --strength 0.5 0.7    # 指定档位
    python tools/eval-dict-order.py --show-fails 15       # 看失败案例
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_CLONE = REPO / "build" / "rime-src"
DEFAULT_ESSAY = REPO / "build" / "m3" / "essay.txt"
DEFAULT_CASES = REPO / "tools" / "dict-order-cases.tsv"
DICT_TABLES = ("base", "ext", "tencent", "others")
DEFAULT_STRENGTHS = (0.0, 0.3, 0.5, 0.6, 0.75, 1.0)


def load_generator():
    """加载融合脚本，复用它的读表与融合逻辑，避免两份实现漂移。"""
    spec = importlib.util.spec_from_file_location(
        "gen_dict_freq", REPO / "tools" / "gen-dict-freq.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_groups(clone: Path) -> dict[str, list[tuple[str, float]]]:
    """按装配清单的 import 顺序读分表并去重（首个权重生效）。"""
    entries: dict[tuple[str, str], float] = {}
    for name in DICT_TABLES:
        path = clone / "cn_dicts" / f"{name}.dict.yaml"
        if not path.exists():
            continue
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                if line.startswith("#") or line.startswith("-"):
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 3:
                    continue
                try:
                    weight = float(parts[2])
                except ValueError:
                    continue
                key = (parts[0], parts[1])
                if key not in entries:
                    entries[key] = weight
    groups: dict[str, list[tuple[str, float]]] = defaultdict(list)
    for (word, code), weight in entries.items():
        groups[code].append((word, weight))
    return groups


def load_cases(path: Path) -> list[tuple[str, str, str]]:
    cases = []
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                kind = parts[2] if len(parts) > 2 else "未分类"
                cases.append((parts[0], parts[1], kind))
    return cases


def evaluate(cases, groups, freq) -> tuple[float, float, float, list]:
    hits1 = hits3 = 0
    reciprocal = 0.0
    fails = []
    for code, expected, kind in cases:
        words = groups.get(code)
        if not words:
            fails.append((code, expected, "词库没有这个拼音", kind))
            continue
        ordered = sorted(words, key=lambda item: (-freq.get(item[0], item[1]), item[0]))
        names = [word for word, _ in ordered]
        if expected not in names:
            fails.append((code, expected, "期望词不在词库", kind))
            continue
        position = names.index(expected) + 1
        if position == 1:
            hits1 += 1
        if position <= 3:
            hits3 += 1
        reciprocal += 1.0 / position
        if position > 1:
            fails.append((code, expected, f"第 {position} 位（首位是「{names[0]}」）", kind))
    total = max(1, len(cases))
    return hits1 / total, hits3 / total, reciprocal / total, fails


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--clone", type=Path, default=DEFAULT_CLONE, help="上游词库目录")
    parser.add_argument("--essay", type=Path, default=DEFAULT_ESSAY, help="essay.txt")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES, help="评测集")
    parser.add_argument("--strength", type=float, nargs="+", default=list(DEFAULT_STRENGTHS),
                        help="要比较的强度档位")
    parser.add_argument("--show-fails", type=int, default=0,
                        help="列出最差档位的前 N 条失败案例（0 不列）")
    args = parser.parse_args()

    for path, hint in (
        (args.clone / "cn_dicts" / "base.dict.yaml", "上游词库"),
        (args.essay, "essay.txt"),
        (args.cases, "评测集"),
    ):
        if not path.exists():
            print(f"缺少{hint}：{path}")
            return 2

    generator = load_generator()
    upstream, _ = generator.read_upstream(args.clone)
    essay = generator.read_essay(args.essay)
    jieba = generator.read_jieba()
    groups = load_groups(args.clone)
    cases = load_cases(args.cases)

    print(f"上游 {len(upstream):,} 词 / essay {len(essay):,} 词 / jieba {len(jieba):,} 词")
    print(f"评测集 {len(cases)} 条，分表分组 {len(groups):,} 个拼音\n")

    header = f"{'strength':>9}  {'top1':>7}  {'top3':>7}  {'MRR':>6}  {'修复':>5}  {'改坏':>5}"
    print(header)
    print("-" * len(header))

    results = []
    baseline_codes = None
    for strength in args.strength:
        rows = generator.fuse(upstream, essay, jieba, generator.DEFAULT_ALPHA, strength)
        rows, _ = generator.rescale(rows)
        freq = {word: adjusted for word, adjusted, _, _, _ in rows}
        top1, top3, mrr, fails = evaluate(cases, groups, freq)
        codes = {code for code, _, _, _ in fails}
        if strength == 0.0:
            baseline_codes = codes
        if baseline_codes is None:
            fixed = broke = "-"
        else:
            fixed = str(len(baseline_codes - codes))
            broke = str(len(codes - baseline_codes))
        results.append((strength, top1, top3, mrr, fails, codes))
        print(f"{strength:>9.2f}  {top1 * 100:>6.1f}%  {top3 * 100:>6.1f}%  {mrr:>6.3f}"
              f"  {fixed:>5}  {broke:>5}")

    if baseline_codes is None:
        print("\n提示：档位里没有 0.00，无法算「修复 / 改坏」相对量")

    best = max(results, key=lambda item: (item[1], item[3]))
    print(f"\n按 top1 最优：strength={best[0]:.2f}"
          f"（top1 {best[1] * 100:.1f}%，MRR {best[3]:.3f}）")

    if args.show_fails:
        worst = min(results, key=lambda item: item[1])
        print(f"\nstrength={worst[0]:.2f} 的失败案例（前 {args.show_fails} 条）：")
        for code, expected, reason, kind in worst[4][: args.show_fails]:
            print(f"  [{kind}] {code:<14} 期望「{expected}」  {reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
