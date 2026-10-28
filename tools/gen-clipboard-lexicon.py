#!/usr/bin/env python3
"""生成剪切板分词用的中文词表（assets/clipboard/lexicon.txt.gz）。

来源：jieba 内置 `dict.txt`（MIT），只保留纯汉字、长度 2–6、词频靠前的条目。
Android 自带的 `BreakIterator` 对中文没有词典，只能按标点切，所以「按词拆分」
必须自带词表；用词频做 DAG 最大概率路径，比正向最大匹配更准。

用法::

    python tools/gen-clipboard-lexicon.py
    python tools/gen-clipboard-lexicon.py --limit 80000
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "imesdk" / "src" / "main" / "assets" / "clipboard" / "lexicon.txt.gz"


def is_han(ch: str) -> bool:
    return "\u4e00" <= ch <= "\u9fff"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=60000, help="保留词频最高的多少个词")
    parser.add_argument("--min-freq", type=int, default=2, help="低于该词频的词丢弃")
    args = parser.parse_args()

    import jieba

    rows: list[tuple[str, int]] = []
    with jieba.get_dict_file() as handle:
        for line in handle:
            if isinstance(line, bytes):
                line = line.decode("utf-8", "ignore")
            parts = line.split()
            if len(parts) < 2:
                continue
            word, raw = parts[0], parts[1]
            try:
                freq = int(raw)
            except ValueError:
                continue
            if not 2 <= len(word) <= 6 or freq < args.min_freq:
                continue
            if not all(is_han(c) for c in word):
                continue
            rows.append((word, freq))

    rows.sort(key=lambda item: -item[1])
    rows = rows[: args.limit]
    if not rows:
        raise SystemExit("没有可用词条")

    payload = "".join(f"{w}\t{f}\n" for w, f in rows).encode("utf-8")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(OUT, "wb", compresslevel=9) as handle:
        handle.write(payload)

    size = OUT.stat().st_size
    print(f"词条 {len(rows):,}（词频 {rows[-1][1]}–{rows[0][1]}）")
    print(f"原始 {len(payload) / 1024:.0f} KB → 压缩 {size / 1024:.0f} KB")
    print(f"sha256 {hashlib.sha256(OUT.read_bytes()).hexdigest()}")
    print(f"已写入 {OUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
