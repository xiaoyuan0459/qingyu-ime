#!/usr/bin/env python3
"""聊天词次与目标词表权重的校准融合（P0-B3，独立小模块）。

上游权重是「人工分档的排序锚」，聊天计数是「原始词次」，两者尺度不同：
不能拿第三方 β=0.5 直接当本工程旋钮，也不能假设词次分布和权重分布同形。
本模块的做法：

1. **对着要写入的表校准**：默认读 `${--anchor-dir}`（部署源 `cn_dicts/`），
   也就是 `rime_freq.apply_freq_table` 稍后要改写权重列的同一批文件。
   早期版本对着某个 clone 校准，遇上「默认包是白霜、clone 是雾凇」时把
   雾凇尺度（十万级）写进白霜表（千级），把 `大德` 从 184 抬到 27255，
   整轮 A/B 报废——所以这里改成「校准源 = 写入源」。
2. **分位数校准**：在两表共有的词上，把聊天计数的分位映射到锚权重的分位
   （chat 第 q 分位 → 锚第 q 分位的权重），自动对齐尺度，不假设分布。
3. **几何插值**：`blended = up^(1-s) * chat_scaled^s`。权重是对数量级，
   线性插值会被高权词淹没；s=0 严格等于零改动。
4. **计数收缩**（`--shrink-k`，默认 0 关闭）：低计数的聊天频率本身是噪声，
   把强度按 `s_eff = s * n/(n+k)` 收缩，n 是该词聊天计数。k=0 时与旧行为一致，
   打开后只压小样本，不动高频词——这是「相对保护」的可调旋钮，单独做实验。
5. **语料未见回退**：聊天零命中的词保持锚权重（不判废词、不降权）。
6. **读音级覆盖**：词级因子 `blended/up` 乘到每个 (词, 读音) 条目上，
   多音词内部比例原样保留——修掉现有词级注入抹平读音的问题。
7. **单字独立实验**：默认不含单字词（--include-single 开启才含）。

一次只改变一个因素：本模块只融合「锚 × 聊天」，不叠加 essay/jieba 三源层。

用法::

    python tools/freq-chat-merge.py \
        --chat build/corpus/lccc/chat-train-isolated.tsv \
        --chat-summary build/corpus/lccc/chat-train-isolated.json \
        --anchor-dir build/frost/src/cn_dicts \
        --strength 0.2 \
        --out-word build/corpus/lccc/chat-blend-s0.2.tsv \
        --out-reading build/corpus/lccc/chat-blend-s0.2.reading.tsv \
        --report build/corpus/lccc/chat-blend-s0.2.report.json
"""

from __future__ import annotations

import argparse
import bisect
import json
import math
from pathlib import Path

from freq_chat_provenance import file_identity, validate_training_counts
from freq_chat_source_binding import bind_training_source
from rime_source_provenance import active_frequency_paths

REPO = Path(__file__).resolve().parent.parent
DEFAULT_ANCHOR = REPO / "build/frost/src/cn_dicts"
# 尺度交叉校验的参照 clone（白霜）。写死为当前默认包的上游，换包时改这里。
DEFAULT_CLONE = REPO / "build/upstream/rime-frost/cn_dicts"

from rime_freq import FREQ_TABLES  # noqa: E402 —— 注入范围与读取范围必须一致


def read_chat(path: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[1].isdigit():
                counts[parts[0]] = int(parts[1])
    return counts


def read_tables(directory: Path):
    """读一组分表：词级最大权重、(词, 读音) 级条目权重、词→读音集合。"""
    word_weight: dict[str, float] = {}
    entry_weight: dict[tuple[str, str], float] = {}
    word_readings: dict[str, set[str]] = {}
    found = []
    for path in active_frequency_paths(directory, FREQ_TABLES):
        table = path.name.split(".")[0]
        found.append(table)
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
                if weight > word_weight.get(word, 0.0):
                    word_weight[word] = weight
                if code:
                    key = (word, code)
                    if weight > entry_weight.get(key, 0.0):
                        entry_weight[key] = weight
                    word_readings.setdefault(word, set()).add(code)
    if not found:
        raise SystemExit(f"{directory} 下没有可读分表（找过 {', '.join(FREQ_TABLES)}）")
    return word_weight, entry_weight, word_readings, found


def quantile_scale(common_chat: list[int], common_up: list[float]):
    """返回 chat 计数 → 锚尺度权重的单调映射（分位对分位线性插值）。"""
    chat_sorted = sorted(common_chat)
    up_sorted = sorted(common_up)
    n = len(chat_sorted)

    def scale(count: int) -> float:
        rank = bisect.bisect_right(chat_sorted, count) / n
        pos = rank * (len(up_sorted) - 1)
        lo = math.floor(pos)
        hi = math.ceil(pos)
        frac = pos - lo
        return up_sorted[lo] * (1 - frac) + up_sorted[hi] * frac

    return scale


def median(values: list[float]) -> float:
    ordered = sorted(values)
    return ordered[len(ordered) // 2] if ordered else 0.0


def check_scale(anchor: dict[str, float], clone_dir: Path, tolerance: float):
    """锚表与参照 clone 的尺度是否一致；不一致说明校准源拿错了。

    同一批词在两边的权重中位数比值落在 [1/tol, tol] 之外就判定为不同尺度。
    """
    if not clone_dir or not clone_dir.exists():
        return None
    clone, _, _, _ = read_tables(clone_dir)
    shared = [w for w in anchor if w in clone and anchor[w] > 0 and clone[w] > 0]
    if len(shared) < 1000:
        return {"checked": False, "reason": f"共有词仅 {len(shared)}"}
    anchor_median = median([anchor[w] for w in shared])
    clone_median = median([clone[w] for w in shared])
    ratio = anchor_median / clone_median if clone_median else 0.0
    info = {
        "checked": True,
        "clone_dir": str(clone_dir),
        "shared_words": len(shared),
        "anchor_median": anchor_median,
        "clone_median": clone_median,
        "ratio": round(ratio, 3),
    }
    if ratio <= 0 or not (1 / tolerance <= ratio <= tolerance):
        raise SystemExit(
            f"校准源尺度不符：锚表中位权重 {anchor_median:g}，"
            f"{clone_dir} 中位权重 {clone_median:g}（比值 {ratio:.2f}）。\n"
            "两者不是同一套权重量级，写进去会把词抬升或压低一到两个数量级。"
        )
    return info


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chat", type=Path, required=True)
    parser.add_argument("--chat-summary", type=Path, required=True,
                        help="对应统计摘要；必须是排除验证/测试对话的 train 计数")
    parser.add_argument("--anchor-dir", type=Path, default=DEFAULT_ANCHOR,
                        help="要写入的分表目录（部署源 cn_dicts/），校准也以此为准")
    parser.add_argument("--clone-dir", type=Path, default=DEFAULT_CLONE,
                        help="参照 clone 目录，只做尺度交叉校验；不存在则跳过")
    parser.add_argument("--tolerance", type=float, default=1.5,
                        help="尺度校验容差（中位权重比值允许范围 1/tol..tol）")
    parser.add_argument("--strength", type=float, required=True,
                        help="融合强度 s∈[0,1]；0=零改动")
    parser.add_argument("--shrink-k", type=float, default=0.0,
                        help="计数收缩常数 k：s_eff = s*n/(n+k)；0=不收缩")
    parser.add_argument("--include-single", action="store_true",
                        help="含单字词（默认不含，单字表独立实验）")
    parser.add_argument("--out-word", type=Path, required=True)
    parser.add_argument("--out-reading", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if not 0.0 <= args.strength <= 1.0:
        raise SystemExit("--strength 必须在 [0,1]")
    if args.shrink_k < 0:
        raise SystemExit("--shrink-k 不能为负")

    training_provenance = validate_training_counts(args.chat, args.chat_summary)
    training_source_binding = bind_training_source(args.chat_summary, args.anchor_dir, args.clone_dir)
    chat = read_chat(args.chat)
    word_weight, entry_weight, _, tables = read_tables(args.anchor_dir)
    scale_info = check_scale(word_weight, args.clone_dir, args.tolerance)

    common = [w for w in word_weight if w in chat]
    if len(common) < 1000:
        raise SystemExit(f"共有词仅 {len(common)}，校准不可信，先查两表口径")
    scale = quantile_scale([chat[w] for w in common],
                           [word_weight[w] for w in common])

    blended: dict[str, int] = {}
    shrunk = 0
    untouched = 0
    for word, up in word_weight.items():
        if up <= 0:
            continue
        is_single = len(word) == 1
        if is_single and not args.include_single:
            continue
        count = chat.get(word)
        if not count:
            untouched += 1
            continue  # 语料未见：回退锚权重（不输出，词级表只写有改动的词）
        strength = args.strength
        if args.shrink_k:
            strength *= count / (count + args.shrink_k)
            if abs(strength - args.strength) > 1e-9:
                shrunk += 1
        chat_scaled = scale(count)
        if chat_scaled <= 0:
            untouched += 1
            continue
        value = math.exp((1 - strength) * math.log(up)
                         + strength * math.log(chat_scaled))
        if round(value) != round(up):
            blended[word] = max(1, round(value))

    args.out_word.parent.mkdir(parents=True, exist_ok=True)
    with args.out_word.open("w", encoding="utf-8", newline="\n") as stream:
        for word in sorted(blended, key=lambda w: -blended[w]):
            stream.write(f"{word}\t{blended[word]}\n")

    # 读音级：词级因子乘到每个 (词, 读音) 条目，内部比例保留。
    reading_rows = 0
    with args.out_reading.open("w", encoding="utf-8", newline="\n") as stream:
        for (word, code), entry in sorted(entry_weight.items()):
            factor = blended.get(word)
            if factor is None:
                continue
            up_word = word_weight[word]
            value = entry * factor / up_word
            if round(value) != round(entry):
                stream.write(f"{word}\t{code}\t{max(1, round(value))}\n")
                reading_rows += 1

    # 校准曲线抽样：把「计数 → 锚尺度权重」摊开，便于人工核对量级是否合理。
    curve = [{"chat_count": n, "scaled_weight": round(scale(n), 1)}
             for n in (1, 10, 100, 1000, 10000, 100000) if n <= max(chat.values())]
    up_median = median([v for v in word_weight.values() if v > 0])

    report = {
        "training_provenance": training_provenance,
        "training_source_binding": training_source_binding,
        "outputs": {"word": file_identity(args.out_word), "reading": file_identity(args.out_reading)},
        "strength": args.strength,
        "shrink_k": args.shrink_k,
        "shrunk_words": shrunk,
        "include_single": args.include_single,
        "anchor_dir": str(args.anchor_dir),
        "anchor_tables": tables,
        "anchor_words": len(word_weight),
        "anchor_median_weight": up_median,
        "scale_check": scale_info,
        "chat_words": len(chat),
        "common_words": len(common),
        "word_level_changed": len(blended),
        "word_level_untouched_zero_hit": untouched,
        "reading_level_changed": reading_rows,
        "calibration": "quantile mapping on common words, geometric blend",
        "calibration_curve": curve,
        "sample_moves": [
            {"word": w, "up": word_weight[w], "chat_count": chat.get(w),
             "blended": blended[w]}
            for w in sorted(blended, key=lambda w: -abs(
                math.log2(blended[w] / word_weight[w])))[:10]
        ],
    }
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"锚表 {args.anchor_dir}（{'+'.join(tables)}，{len(word_weight):,} 词，"
          f"中位权重 {up_median:g}）")
    if scale_info and scale_info.get("checked"):
        print(f"尺度校验：对比 clone 中位 {scale_info['clone_median']:g}，"
              f"比值 {scale_info['ratio']}")
    print(f"校准曲线：" + "，".join(
        f"{c['chat_count']}次→{c['scaled_weight']:g}" for c in curve))
    print(f"词级改动 {len(blended)} 条（零命中回退 {untouched} 条"
          f"{f'，计数收缩 {shrunk} 条' if args.shrink_k else ''}），"
          f"读音级 {reading_rows} 条 → {args.out_word}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
