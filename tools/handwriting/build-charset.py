#!/usr/bin/env python3
"""构建手写识别的类别表（默认 3,500 汉字 + ASCII/标点，约 3,600 类）。

字表不能凭感觉排：类别顺序即输出维度到字符的映射，错了会在真机上静默乱码，
所以这里把「选哪些字」和「按什么顺序」都固化进 charset.json 并记哈希。

字频来源：jieba 内置 `dict.txt`（MIT），对词频按字累加后排序。它只是排序依据，
不引入任何受限语料；若改用别的字表，用 `--from-file` 直接指定即可。

用法::

    python tools/handwriting/build-charset.py
    python tools/handwriting/build-charset.py --limit 500 --out /tmp/charset.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".cache" / "fonts"
DEFAULT_OUT = HERE / ".cache" / "charset.json"

# 常用中文标点：手写键盘实际会输入的那一批，不追求全角符号全集。
PUNCTUATION = "，。？！、；：“”‘’（）《》…—·"
ASCII = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

# 目标字表规模。字体覆盖决定上限：4 个手写字体只到 GB2312（6763），
# 越过 6763 的字只剩 Klee One / 霞鹜文楷 / Noto 提供样本，属于「印刷体为主」的补充覆盖。
TARGETS = {
    "basic": 3500,     # 一级常用字，最小可用集
    "gb2312": 6763,    # 全部手写字体都能覆盖
    "tongyong": 8105,  # 通用规范汉字表全集
    "cjk": 20992,      # CJK 基本区全量
    "cjk_ext": 27584,  # 基本区 + 扩展 A，当前 OFL 字体能覆盖到的上限
}


def cjk_pool(with_ext_a: bool = True) -> str:
    """CJK 统一汉字基本区（+ 扩展 A）。扩展 B 起没有可用 OFL 字体覆盖，无法渲染。"""
    ranges = [(0x4E00, 0xA000)]
    if with_ext_a:
        ranges.insert(0, (0x3400, 0x4DC0))
    return "".join(chr(c) for lo, hi in ranges for c in range(lo, hi))


def char_frequencies() -> dict[str, int]:
    import jieba  # 延迟导入：只有排字频时才需要

    freq: dict[str, int] = {}
    with jieba.get_dict_file() as handle:
        for line in handle:
            if isinstance(line, bytes):  # jieba 的 dict 以二进制打开
                line = line.decode("utf-8", "ignore")
            parts = line.split()
            if len(parts) < 2:
                continue
            word, raw = parts[0], parts[1]
            try:
                weight = int(raw)
            except ValueError:
                continue
            for ch in word:
                freq[ch] = freq.get(ch, 0) + weight
    return freq


def font_coverage(paths: list[Path]) -> dict[str, set[int]]:
    """每个字体实际包含的码位。缺字的字体会渲染出 .notdef（方框），必须提前剔除。"""
    from fontTools.ttLib import TTFont

    result: dict[str, set[int]] = {}
    for path in paths:
        font = TTFont(str(path), lazy=True, fontNumber=0)
        try:
            cmap = font.getBestCmap() or {}
            result[path.stem] = set(cmap.keys())
        finally:
            font.close()
    return result


def build(limit: int, min_fonts: int, with_ascii: bool) -> dict:
    pool = set(cjk_pool())
    freq = char_frequencies()

    font_files = sorted(p for p in CACHE.glob("*.ttf"))
    if not font_files:
        raise SystemExit(f"没有字体：{CACHE} 为空，先运行 tools/handwriting/fetch-fonts.py")
    coverage = font_coverage(font_files)

    # 生僻字基本不在 jieba 词表里，按 0 频参与排序，排在常用字之后而不是被丢掉
    ranked = sorted(pool, key=lambda c: (-freq.get(c, 0), c))

    def fonts_having(ch: str) -> int:
        return sum(1 for codes in coverage.values() if ord(ch) in codes)

    kept: list[str] = []
    dropped_lowfreq_fonts = 0
    for ch in ranked:
        if fonts_having(ch) < min_fonts:
            dropped_lowfreq_fonts += 1
            continue
        kept.append(ch)
        if len(kept) >= limit:
            break

    if len(kept) < limit:
        raise SystemExit(f"只凑到 {len(kept)} 个字，降低 --min-fonts 或补字体")

    classes = list(kept)
    if with_ascii:
        classes += list(PUNCTUATION) + list(ASCII)

    payload = {
        "version": 1,
        "source": "jieba dict.txt (MIT) 字频排序 + 字体覆盖过滤",
        "num_classes": len(classes),
        "num_hanzi": len(kept),
        "min_fonts_per_char": min_fonts,
        "classes": classes,
        "classes_sha256": hashlib.sha256("".join(classes).encode("utf-8")).hexdigest(),
    }
    return payload, dropped_lowfreq_fonts, coverage


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=sorted(TARGETS), default="cjk_ext",
                        help="字表规模档位，默认 cjk_ext=27584（基本区+扩展A）")
    parser.add_argument("--limit", type=int, default=0, help="直接指定汉字数，覆盖 --target")
    parser.add_argument("--min-fonts", type=int, default=2, help="至少被几个字体覆盖才入选")
    parser.add_argument("--no-ascii", action="store_true", help="不追加数字/字母/标点")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    limit = args.limit or TARGETS[args.target]
    payload, dropped, coverage = build(limit, args.min_fonts, not args.no_ascii)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    print(f"字体 {len(coverage)} 个，覆盖率：")
    for name, codes in coverage.items():
        hit = sum(1 for ch in payload["classes"] if ord(ch) in codes)
        print(f"  {name:24s} {hit}/{len(payload['classes'])}")
    print(f"\n汉字 {payload['num_hanzi']}，总类别 {payload['num_classes']}，"
          f"因字体覆盖不足跳过 {dropped} 个候选")
    print(f"字表哈希 {payload['classes_sha256'][:16]}…")
    print(f"已写入 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
