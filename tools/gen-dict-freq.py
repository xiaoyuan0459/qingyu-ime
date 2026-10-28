#!/usr/bin/env python3
"""融合多源词频，生成轻语自有的频率覆盖表。

上游（雾凇 / 白霜）的词表是多年人工积累，但权重口径是「人工分档」：大量词挤在
同一档里，同档内排序退化成存储序。本脚本不改词表，只重算「词 → 频率」这一层，
把排序从上游手里接过来。

三个来源各自归一化后加权融合：

- 上游权重  输入法场景的人工调校结果，默认权重最高
- rime-essay  LGPL-3.0，rime 官方通用词频，偏书面与文学
- jieba      MIT，新闻语料词频，偏正式书面

缺源时按剩余源重新归一化权重，所以三源齐不齐都能跑。输出是「词 → 参考权重」的
覆盖表，由 `build-rime-pack.py` 在组装阶段注入，上游源文件保持原样不动。

用法::

    python tools/gen-dict-freq.py                       # 默认融合并打印摘要
    python tools/gen-dict-freq.py --no-jieba            # 只用上游 + essay
    python tools/gen-dict-freq.py --min-divergence 0.2  # 只导出分歧大的词
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_CLONE = REPO / "build" / "rime-src"
DEFAULT_ESSAY = REPO / "build" / "m3" / "essay.txt"
DEFAULT_OUT = REPO / "build" / "freq" / "qingyu-freq.tsv"

# 参考权重的量级上限。雾凇最大约 1e6，沿用同一量级，避免和既有调参直觉脱节。
MAX_WEIGHT = 1_000_000
# 输出上限：上游 base 里最大权重接近 2e7，修正值不应被 1e6 截断。
MAX_OUTPUT = 20_000_000

# 融合权重。上游是人调过的输入法场景权重，比纯语料更贴近实际输入，故给最高。
DEFAULT_ALPHA = {"upstream": 0.40, "essay": 0.35, "jieba": 0.25}

# 参与融合的上游分表。8105 / 41448 是字表，权重语义不同，不参与。
DICT_TABLES = ["base", "ext", "tencent", "others"]


def read_upstream(clone: Path) -> tuple[dict[str, float], dict[str, set[str]]]:
    """读上游分表，返回 词 → 最大权重 与 词 → 拼音集合。

    多音词各读音共享同一个词级频率；拼音集合留着算「同音内名次变化」。
    """
    weights: dict[str, float] = {}
    codes: dict[str, set[str]] = {}
    for table in DICT_TABLES:
        path = clone / "cn_dicts" / f"{table}.dict.yaml"
        if not path.exists():
            print(f"跳过缺失的分表：{path}")
            continue
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                if line.startswith("#") or line.startswith("-"):
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 3:
                    continue
                word, code, raw = parts[0], parts[1], parts[2]
                try:
                    weight = float(raw)
                except ValueError:
                    continue
                if weight > weights.get(word, 0.0):
                    weights[word] = weight
                if code:
                    codes.setdefault(word, set()).add(code)
    return weights, codes


def read_essay(path: Path) -> dict[str, float]:
    """读 rime-essay 的 `词<TAB>频率`。权重 0 的词条是上游已知的废词，丢弃。"""
    freq: dict[str, float] = {}
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            try:
                value = float(parts[1])
            except ValueError:
                continue
            if value > 0:
                freq[parts[0]] = value
    return freq


def read_jieba() -> dict[str, float]:
    """读 jieba 内置词典 `词 词频 词性`。jieba 是可选依赖，缺失时返回空表。"""
    try:
        import jieba  # noqa: PLC0415  —— 可选依赖，放函数内按需导入
    except ImportError:
        print("未安装 jieba，本次只用上游 + essay 两源（pip install jieba 可启用三源）")
        return {}

    freq: dict[str, float] = {}
    with jieba.get_dict_file() as handle:
        for line in handle:
            if isinstance(line, bytes):
                line = line.decode("utf-8", "ignore")
            parts = line.split()
            if len(parts) < 2:
                continue
            try:
                value = float(parts[1])
            except ValueError:
                continue
            if value > 0:
                freq[parts[0]] = value
    return freq


def normalize(source: dict[str, float]) -> dict[str, float]:
    """把频率压到 [0,1] 的对数尺度。原始量级差几个数量级，直接加权会被最大值吃掉。"""
    if not source:
        return {}
    top = max(source.values())
    scale = math.log1p(top)
    if scale <= 0:
        return {}
    return {word: math.log1p(value) / scale for word, value in source.items()}


def fuse(upstream: dict[str, float], essay: dict[str, float],
         jieba: dict[str, float], alpha: dict[str, float],
         strength: float) -> list[tuple]:
    """按可用来源加权平均，再与上游权重做几何插值。

    上游权重是「输入法场景的人工调校」，语料频率是「文本出现频率」，两者语义不同：
    语料里稀少的词在输入场景未必罕见（「我公司」就是典型）。所以不直接拿融合值替换，
    而是在对数空间按 strength 插值 —— strength=0 完全保留上游，=1 完全采用融合值。
    这样既改得动明显异常的条目，又不会把整张表的档位结构推倒重来。

    默认 0.3 由 `tools/eval-dict-order.py` 的输入场景评测选出：0.2–0.3 能修好
    上游排错的同音竞争（yi shi / de shi / wu shi），0.4 起开始把口语词让给书面词
    （zhi dao 首位从「知道」变成「指导」）。
    """
    norm_up = normalize(upstream)
    norm_essay = normalize(essay)
    norm_jieba = normalize(jieba)
    layers = [
        ("upstream", norm_up, alpha["upstream"]),
        ("essay", norm_essay, alpha["essay"]),
        ("jieba", norm_jieba, alpha["jieba"]),
    ]
    log_max = math.log1p(MAX_WEIGHT)

    rows = []
    for word, up_weight in upstream.items():
        score = 0.0
        total_alpha = 0.0
        sources = 0
        for _, table, weight in layers:
            value = table.get(word)
            if value is None:
                continue
            score += value * weight
            total_alpha += weight
            sources += 1
        if total_alpha <= 0:
            continue
        score /= total_alpha
        reference = max(1, round(math.expm1(score * log_max)))
        up_norm = norm_up.get(word, 0.0)
        divergence = abs(score - up_norm)
        blended = math.exp(
            (1.0 - strength) * math.log(max(1.0, up_weight))
            + strength * math.log(reference)
        )
        adjusted = min(MAX_OUTPUT, max(1, round(blended)))
        rows.append((word, adjusted, up_weight, divergence, sources))
    return rows


def rescale(rows: list[tuple]) -> tuple[list[tuple], float]:
    """把修正后的权重整体缩放回上游的量级。

    librime 的排序本身只看相对大小，但权重绝对值会影响「系统词 vs 用户词」的竞争：
    整体降一个数量级，用户打过一次的词就会更容易顶掉常用词。这里按中位数对齐，
    只改量级不改相对顺序。
    """
    if not rows:
        return rows, 1.0
    up_sorted = sorted(row[2] for row in rows)
    adj_sorted = sorted(row[1] for row in rows)
    median_up = up_sorted[len(up_sorted) // 2]
    median_adj = adj_sorted[len(adj_sorted) // 2]
    if median_up <= 0 or median_adj <= 0:
        return rows, 1.0
    factor = median_up / median_adj
    if abs(factor - 1.0) < 0.01:
        return rows, 1.0
    scaled = [
        (word, min(MAX_OUTPUT, max(1, round(adjusted * factor))), up_weight, divergence, sources)
        for word, adjusted, up_weight, divergence, sources in rows
    ]
    return scaled, factor


def order_shift(rows: list[tuple], codes: dict[str, set[str]]) -> tuple[int, int, int, int]:
    """统计同一拼音内部的候选名次变化。

    权重绝对值改了多少并不重要 —— librime 只在同音块内比较。真正要盯的是：
    多少词换了名次（改动生效），多少音节的第一位没动（常用词没被挤走）。
    """
    adjusted = {row[0]: row[1] for row in rows}
    upstream = {row[0]: row[2] for row in rows}
    groups: dict[str, list[str]] = {}
    for word, word_codes in codes.items():
        for code in word_codes:
            groups.setdefault(code, []).append(word)

    total = moved = top1 = top1_same = 0
    for words in groups.values():
        if len(words) < 2:
            continue
        before = sorted(words, key=lambda w: (-upstream.get(w, 0.0), w))
        after = sorted(words, key=lambda w: (-adjusted.get(w, 0.0), w))
        rank_before = {word: index for index, word in enumerate(before)}
        for index, word in enumerate(after):
            total += 1
            if rank_before[word] != index:
                moved += 1
        top1 += 1
        if before[0] == after[0]:
            top1_same += 1
    return total, moved, top1, top1_same


def report(rows: list[tuple], min_divergence: float, sample: int) -> None:
    """打印分歧分布与样例，让「改了多少」变成可核对的事实。"""
    total = len(rows)
    bands = [(0.4, "极大"), (0.25, "大"), (0.1, "中"), (0.0, "小")]
    print(f"\n参与融合的词条：{total:,}")
    print("分歧分布（|参考分 − 上游分|，0–1 对数尺度）：")
    previous = 1.1
    for threshold, label in bands:
        count = sum(1 for row in rows if threshold <= row[3] < previous)
        print(f"  {label:<4} ≥{threshold:.2f}  {count:>9,}  {100 * count / total:5.1f}%")
        previous = threshold

    print("权重实际变化幅度（修正后 / 上游）：")
    moved = [(0.5, "减半以上"), (0.2, "降 20% 以上"), (1 / 0.5, "翻倍以上")]
    for ratio, label in moved:
        if ratio < 1:
            count = sum(1 for row in rows if row[1] / max(1.0, row[2]) <= ratio)
        else:
            count = sum(1 for row in rows if row[1] / max(1.0, row[2]) >= ratio)
        print(f"  {label:<12} {count:>9,}  {100 * count / total:5.1f}%")

    marked = [row for row in rows if row[3] >= min_divergence]
    print(f"超过分歧阈值 {min_divergence:.2f} 的词条：{len(marked):,}"
          f"（{100 * len(marked) / total:.1f}%）")

    marked.sort(key=lambda row: -row[3])
    print(f"\n分歧最大的 {sample} 条（上游权重 → 修正后权重）：")
    for word, reference, up_weight, divergence, sources in marked[:sample]:
        ratio = reference / max(1.0, up_weight)
        if ratio >= 1.5:
            label = f"提权 ×{ratio:.1f}"
        elif ratio <= 0.67:
            label = f"降权 ×{ratio:.2f}"
        else:
            label = f"微调 ×{ratio:.2f}"
        print(f"  {word:<10} {up_weight:>9.0f} → {reference:>7}  {label:<12}"
              f" 分歧 {divergence:.2f}  {sources} 源")


def write_table(rows: list[tuple], out: Path, min_divergence: float) -> int:
    """写出覆盖表。默认全量，组装阶段按词替换上游分表的权重列。"""
    marked = sorted(
        (row for row in rows if row[3] >= min_divergence),
        key=lambda row: row[0],
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write("# 轻语自有频率覆盖表（生成产物，勿手改）\n")
        stream.write("# 由 tools/gen-dict-freq.py 融合上游权重 / rime-essay / jieba 得出。\n")
        stream.write("# 词\t权重\n")
        for word, reference, _, _, _ in marked:
            stream.write(f"{word}\t{reference}\n")
    return len(marked)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--clone", type=Path, default=DEFAULT_CLONE,
                        help="上游词库克隆目录（含 cn_dicts/）")
    parser.add_argument("--essay", type=Path, default=DEFAULT_ESSAY, help="rime-essay 的 essay.txt")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="覆盖表输出路径")
    parser.add_argument("--no-jieba", action="store_true", help="不读 jieba 词频")
    parser.add_argument("--min-divergence", type=float, default=0.0,
                        help="只导出分歧不低于该值的词条（默认 0 = 全量覆盖）")
    parser.add_argument("--strength", type=float, default=0.3,
                        help="向融合值靠拢的强度，0=完全保留上游，1=完全采用融合值"
                             "（默认 0.3，依据 tools/eval-dict-order.py 的评测）")
    parser.add_argument("--no-rescale", dest="rescale", action="store_false",
                        help="不把结果缩放回上游量级（默认会按中位数对齐）")
    parser.add_argument("--sample", type=int, default=20, help="打印多少条样例")
    parser.add_argument("--dry-run", action="store_true", help="只打印摘要，不写文件")
    parser.add_argument("--alpha-upstream", type=float, default=DEFAULT_ALPHA["upstream"])
    parser.add_argument("--alpha-essay", type=float, default=DEFAULT_ALPHA["essay"])
    parser.add_argument("--alpha-jieba", type=float, default=DEFAULT_ALPHA["jieba"])
    args = parser.parse_args()

    if not (args.clone / "cn_dicts" / "base.dict.yaml").exists():
        print(f"上游词库不在 {args.clone}。先浅克隆（上游数据不入库）：\n"
              "  git clone --depth 1 https://github.com/iDvel/rime-ice.git build/rime-src")
        return 2
    if not args.essay.exists():
        print(f"essay.txt 不在 {args.essay}。它是词频基线，来自 rime/rime-essay（LGPL-3.0）：\n"
              f"  curl -L -o {args.essay} "
              "https://raw.githubusercontent.com/rime/rime-essay/"
              "e2652ea18609a879eae3e87db5d25b7fbc1a4f93/essay.txt")
        return 2

    alpha = {
        "upstream": args.alpha_upstream,
        "essay": args.alpha_essay,
        "jieba": args.alpha_jieba,
    }
    if min(alpha.values()) < 0:
        print("融合权重不能为负")
        return 2
    if not 0.0 <= args.strength <= 1.0:
        print("--strength 必须落在 0–1")
        return 2

    upstream, codes = read_upstream(args.clone)
    essay = read_essay(args.essay)
    jieba = {} if args.no_jieba else read_jieba()
    if not upstream:
        print("没有读到任何上游词条，检查 --clone 是否指向词库根目录")
        return 2

    print(f"上游 {len(upstream):,} 词 / essay {len(essay):,} 词 / jieba {len(jieba):,} 词")
    print("融合权重：" + "、".join(f"{k} {v:.2f}" for k, v in alpha.items())
          + f"；修正强度 {args.strength:.2f}")

    rows = fuse(upstream, essay, jieba, alpha, args.strength)
    if args.rescale:
        rows, factor = rescale(rows)
        if factor != 1.0:
            print(f"量级对齐上游中位数：整体 ×{factor:.2f}")
    report(rows, args.min_divergence, args.sample)

    total, moved, top1, top1_same = order_shift(rows, codes)
    print("\n同音内名次变化（librime 实际比较的粒度）：")
    print(f"  参与比较 {total:,} 条，名次变化 {moved:,}（{100 * moved / max(1, total):.1f}%）")
    print(f"  音节首位不变 {top1_same:,}/{top1:,}（{100 * top1_same / max(1, top1):.1f}%）")

    if args.dry_run:
        print("\n--dry-run：未写文件")
        return 0

    count = write_table(rows, args.out, args.min_divergence)
    print(f"\n已写入 {os.path.relpath(args.out, REPO)}：{count:,} 条覆盖")
    return 0


if __name__ == "__main__":
    sys.exit(main())
