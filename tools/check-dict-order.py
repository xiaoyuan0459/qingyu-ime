#!/usr/bin/env python3
"""对比上游权重与自有频率下的同音节候选顺序。

`tools/gen-dict-freq.py` 改了权重，但权重本身不是用户看到的东西 —— 用户看到的是
「敲 yi shi 之后前几个是什么」。本脚本把这件事变成可核对的差异：同一拼音下，
上游排第几、轻语排第几、换了多少条、首位有没有动。

这是离线对比：只算词库层的权重，不含 essay 预置词汇加成、用户词频与句子级的
语言模型。真机最终排序请用 `qingyu_probe --query`。

用法::

    python tools/check-dict-order.py --code "yi shi" --code "shi hou"
    python tools/check-dict-order.py --changed 10        # 自动挑变化最大的 10 个音节
    python tools/check-dict-order.py --code "de shi" --top 8 --all
"""

from __future__ import annotations

import argparse
import os
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_CLONE = REPO / "build" / "rime-src"
DEFAULT_FREQ = REPO / "build" / "freq" / "qingyu-freq.tsv"
OVERLAY = REPO / "imesdk" / "rime-src" / "cn_dicts" / "qingyu_overlay.dict.yaml"

# 与装配清单的 import 顺序一致：靠前的表在重复词条上优先。
DICT_TABLES = ("base", "ext", "tencent", "others")


def read_table(path: Path, columns: int) -> list[tuple[str, str, float]]:
    """读分表，返回 (词, 拼音, 权重)。"""
    rows = []
    if not path.exists():
        return rows
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if line.startswith("#") or line.startswith("-"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < columns:
                continue
            try:
                weight = float(parts[columns - 1])
            except ValueError:
                continue
            rows.append((parts[0], parts[1], weight))
    return rows


def read_freq(path: Path) -> dict[str, float]:
    table = {}
    if not path.exists():
        return table
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[1].isdigit():
                table[parts[0]] = float(parts[1])
    return table


def load(clone: Path, freq_path: Path) -> tuple[dict[str, list], dict[str, float]]:
    """按 import 顺序读分表并去重，返回 拼音 → [(词, 上游权重)] 与 词 → 自有权重。"""
    entries: dict[tuple[str, str], float] = {}
    sources = [OVERLAY] + [clone / "cn_dicts" / f"{name}.dict.yaml" for name in DICT_TABLES]
    for path in sources:
        columns = 3
        for word, code, weight in read_table(path, columns):
            key = (word, code)
            if key not in entries:  # 首个权重生效
                entries[key] = weight

    freq = read_freq(freq_path)
    groups: dict[str, list] = defaultdict(list)
    for (word, code), weight in entries.items():
        groups[code].append((word, weight))
    return groups, freq


def rank_of(words: list[tuple[str, float]], freq: dict[str, float], adjusted: bool):
    if adjusted:
        ordered = sorted(words, key=lambda item: (-freq.get(item[0], item[1]), item[0]))
    else:
        ordered = sorted(words, key=lambda item: (-item[1], item[0]))
    return ordered


def compare(words: list[tuple[str, float]], freq: dict[str, float]):
    """返回 (上游顺序, 轻语顺序, 名次映射, 变化条数, 首位是否变化)。"""
    before = rank_of(words, freq, adjusted=False)
    after = rank_of(words, freq, adjusted=True)
    rank_before = {word: index for index, (word, _) in enumerate(before)}
    moved = sum(1 for index, (word, _) in enumerate(after) if rank_before[word] != index)
    return before, after, rank_before, moved, before[0][0] != after[0][0]


def show(code: str, words: list, freq: dict[str, float], top: int, show_all: bool) -> None:
    before, after, rank_before, moved, top_changed = compare(words, freq)
    before_weight = {word: weight for word, weight in before}
    flag = "首位变化" if top_changed else "首位不变"
    print(f"\n【{code}】{len(words)} 条，名次变化 {moved} 条（{flag}）")
    limit = len(after) if show_all else min(top, len(after))
    for index, (word, weight) in enumerate(after[:limit], start=1):
        delta = rank_before[word] + 1 - index
        if delta > 0:
            mark = f"↑{delta}"
        elif delta < 0:
            mark = f"↓{-delta}"
        else:
            mark = "—"
        print(f"  {index:>2}. {word:<10} {freq.get(word, weight):>8.0f}"
              f"   （上游 {before_weight[word]:>9.0f}，第 {rank_before[word] + 1}）  {mark}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--clone", type=Path, default=DEFAULT_CLONE, help="上游词库目录（含 cn_dicts/）")
    parser.add_argument("--freq", type=Path, default=DEFAULT_FREQ, help="自有频率覆盖表")
    parser.add_argument("--code", action="append", default=[], help="要对比的拼音，可重复")
    parser.add_argument("--changed", type=int, default=0, help="自动挑名次变化最多的 N 个音节")
    parser.add_argument("--top", type=int, default=6, help="每个音节显示前几条（默认 6）")
    parser.add_argument("--all", action="store_true", help="显示音节下全部词条，不只前 N 条")
    parser.add_argument("--min-entries", type=int, default=2, help="只统计词条数不低于该值的音节")
    args = parser.parse_args()

    if not (args.clone / "cn_dicts" / "base.dict.yaml").exists():
        print(f"上游词库不在 {args.clone}。先浅克隆到固定 revision，见 tools/build-rime-pack.py 的提示。")
        return 2
    if not args.freq.exists():
        print(f"自有频率覆盖表不在 {args.freq}。先跑 tools/gen-dict-freq.py")
        return 2

    groups, freq = load(args.clone, args.freq)
    covered = sum(1 for words in groups.values() for word, _ in words if word in freq)
    total = sum(len(words) for words in groups.values())
    contested = sum(1 for words in groups.values() if len(words) >= 2)
    print(f"词条 {total:,}，其中 {covered:,} 条有自有权重"
          f"（{100 * covered / max(1, total):.1f}%）；"
          f"音节 {len(groups):,} 个，其中 {contested:,} 个存在同音竞争")

    targets = list(args.code)
    if args.changed:
        scored = []
        for code, words in groups.items():
            if len(words) < args.min_entries:
                continue
            _, _, _, moved, top_changed = compare(words, freq)
            scored.append((moved, top_changed, code))
        scored.sort(key=lambda item: (-item[0], not item[1], item[2]))
        targets.extend(code for _, _, code in scored[: args.changed])

    if not targets:
        print("没有指定 --code 或 --changed，无事可做")
        return 0

    for code in targets:
        words = groups.get(code)
        if not words:
            print(f"\n【{code}】词库里没有这个拼音")
            continue
        show(code, words, freq, args.top, args.all)

    return 0


if __name__ == "__main__":
    sys.exit(main())
