#!/usr/bin/env python3
"""从应用自己的乱序17键表生成 speller/algebra。

单一事实来源是 `LX17PinYinUtils.kt` 里的 `lx17PinyinMap`（大写二码 → 全拼音节），
键盘侧、拼音提示侧都读它。棱镜里过去只有零声母和简拼规则，二码一条都没有，
所以「乱序17」从来打不出字。本脚本把那张表逐对翻成
`derive/<音节>$/<二码>/`，写回 `double_pinyin_ls17.schema.yaml`。

用法：
    python tools/gen-ls17-algebra.py            # 打印统计，不改文件
    python tools/gen-ls17-algebra.py --write     # 写回 schema
"""

import argparse
import os
import re
import sys

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
KEY_TABLE = os.path.join(
    REPO, "imesdk", "src", "main", "java", "com", "qingyu", "inputmethod", "util",
    "LX17PinYinUtils.kt",
)
SCHEMA = os.path.join(
    REPO, "imesdk", "src", "main", "assets", "rime", "build",
    "double_pinyin_ls17.schema.yaml",
)

ENTRY_RE = re.compile(
    r'lx17PinyinMap\.put\("([A-Z]+)",\s*"([^"]*)"\)'
)

# 键盘只送这 17 个声母键；单键项是拼音提示用的前缀，不是完整码
INITIAL_KEYS = 17


def load_pairs():
    text = open(KEY_TABLE, encoding="utf-8").read()
    codes = {}
    for code, syllables in ENTRY_RE.findall(text):
        if len(code) != 2:
            continue
        for syllable in syllables.split(","):
            syllable = syllable.strip()
            if syllable:
                codes.setdefault(syllable, set()).add(code)
    return codes


def render(codes):
    lines = [
        "  # 乱序17 的「二码 → 音节」是枚举表，不像小鹤那样能由声母/韵母规则组合出来，\n"
        "  # 所以这份 algebra 由 tools/gen-ls17-algebra.py 从 LX17PinYinUtils.kt 的\n"
        "  # lx17PinyinMap 生成。改键表请改那边，然后重跑本脚本 + 重新编译棱镜。\n"
        "  algebra:\n",
    ]
    for syllable in sorted(codes):
        for code in sorted(codes[syllable]):
            lines.append(f"    - derive/^{syllable}$/{code}/\n")
    lines.append("    - derive/^a$/qa/          # 兼容旧棱镜里已有的三种零声母写法\n")
    lines.append("    - derive/^e$/qe/\n")
    lines.append("    - derive/^o$/qo/\n")
    lines.append("    - abbrev/^([a-z]).+$/$1/      # 超级简拼：单字母\n")
    lines.append("    - abbrev/^([zcs]h).+$/$1/     # 简拼里 zh/ch/sh 视为整体\n")
    return "".join(lines)


ALGEBRA_RE = re.compile(r"^  algebra:\n(?: {4}.*\n)+", re.M)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true", help="写回 schema")
    args = ap.parse_args()

    codes = load_pairs()
    if len(codes) < 200:
        sys.exit(f"键表解析结果过少（{len(codes)} 个音节），请检查 LX17PinYinUtils.kt 格式")
    block = render(codes)
    text = open(SCHEMA, encoding="utf-8").read()
    if not ALGEBRA_RE.search(text):
        sys.exit("schema 里找不到 speller/algebra 块，拒绝盲写")
    print(f"音节 {len(codes)} 个，derive {block.count('    - derive/')} 条")
    print(f"用到的键：{''.join(sorted({c for s in codes.values() for c in s}))}")
    if not args.write:
        print("（未写回，加 --write 生效）")
        return 0
    open(SCHEMA, "w", encoding="utf-8", newline="\n").write(ALGEBRA_RE.sub(block, text, count=1))
    print(f"已写回 {os.path.relpath(SCHEMA, REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
