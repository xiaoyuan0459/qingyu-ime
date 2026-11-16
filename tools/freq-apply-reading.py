#!/usr/bin/env python3
"""按 (词, 读音) 粒度把频率层写进分表权重列（P0-B3 的实验注入口）。

现有 `rime_freq.apply_freq_table` 是词级注入：多音词所有读音共用一个词级
权重——这会把「X得 de 116 → 145」这类读音级收益抹平。本模块读
`词<TAB>读音码<TAB>权重` 的读音级覆盖表，逐行改写分表权重列；词级条目
（code 为空的单字表行等）不动，留给独立实验。

上游源文件不动：本模块只处理**组装产物**（build-rime-pack.py 已经把上游
复制到 out 之后的 cn_dicts）。命中不足会显式失败——口径不符时宁可不发布，
不悄悄变成两源包。

用法::

    python tools/freq-apply-reading.py <pack-src>/cn_dicts \
        --freq build/corpus/lccc/chat-blend-s0.2.reading.tsv
"""

from __future__ import annotations

import argparse
from pathlib import Path

from rime_reading_freq import apply_reading_table


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cn_dicts", type=Path)
    parser.add_argument("--freq", type=Path, required=True)
    parser.add_argument("--min-hit-rate", type=float, default=1.0,
                        help="写入前检查覆盖率；默认要求所有读音覆盖项都能定位")
    args = parser.parse_args()
    if not 0 <= args.min_hit_rate <= 1:
        parser.error("--min-hit-rate 必须在 [0,1]")

    result = apply_reading_table(args.cn_dicts, args.freq, args.min_hit_rate)
    print(f"reading overrides: {result}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
