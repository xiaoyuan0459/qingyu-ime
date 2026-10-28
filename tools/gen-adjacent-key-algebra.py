#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「相邻键变体音节表」，写进全拼与联想两份 schema 的 speller/algebra。

搜狗式按键纠错：26 键上把某个字母敲成物理相邻键（z 敲成 x，出来 xhong），
引擎仍要给出本音节（zhong）的候选。librime 1.11.2 内建的
translator/enable_correction 不可用——纠错候选上限 4 条（max_corrections_ 写死）
且按词频与本码混排，打对的拼音反而选不到本码字（见 docs/TECHNICAL_DEBT.md
DEBT-CORRECTION-01）——所以改为棱镜侧预展开：对每个拼音音节 S、每个字母位、
每个 QWERTY 相邻键 N，生成 `correct/^S$/V/`（V = S 的该位换成 N）。`correct`
是本工程给 librime 加的独立拼写类型（不改旧 prism 的 completion/ambiguous 数值），见
docs/adr/0007-纠错切分权重.md。

变体 V 命中以下任一情形即丢弃：
- 长度不足 2：单字母是超级简拼的领地，不能让简拼带上纠错目标；
- V 本身是合法音节，或与 jv/qv/xv/yv、lue/nue 等基础派生写法撞车：
  敲对另一个音节的输入不能被纠到别处。

一个变体可以由多个音节一步产生（xhong ← zhong 的 z→x，也 ← chong 的 c→x），
此时每个产出音节各生成一条规则：敲出 xhong 时「中 / 冲」按词频同场竞争，
这正是参考输入法的行为，不做人为取舍。

规则块插在 fuzz 之后（algebra 末尾），两份 schema 同步写（共用 pinyin 棱镜，
build-rime-pack 的卡口要求逐条一致）。顺序是硬约束：fuzz 若在纠错之后跑，
`^([zcs])h/$1/` 会把变体 shong 折叠成 song——正码输入被挂上模糊候选，
「song 出中系字」就是这么来的；纠错放在最后，变体就永远不会被模糊化。
反向的代价只是 zh/z 孪生音节的变体互纠（xhong 同时命中中/总），按词频混排可接受。

音节表的事实来源是 `LX17PinYinUtils.kt` 的 `lx17PinyinMap`（与
tools/gen-ls17-algebra.py 同源），改音节表请改那边再重跑本脚本。

用法：
    python tools/gen-adjacent-key-algebra.py            # 打印统计，不改文件
    python tools/gen-adjacent-key-algebra.py --write     # 写回两份 schema
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCHEMA_DIR = REPO / "imesdk" / "src" / "main" / "assets" / "rime" / "build"
KEY_TABLE = (
    REPO / "imesdk" / "src" / "main" / "java" / "com" / "qingyu" / "inputmethod"
    / "util" / "LX17PinYinUtils.kt"
)
SCHEMA_NAMES = ("pinyin.schema.yaml", "associate.schema.yaml")

ENTRY_RE = re.compile(r'lx17PinyinMap\.put\("([A-Z]+)",\s*"([^"]*)"\)')

# QWERTY 物理相邻（八邻域，只取字母键；行位按 a 在 q/w 之间错半格折算）
ADJACENCY = {
    "q": "wa", "w": "qeasd", "e": "wrsdf", "r": "etdfg", "t": "ryfgh",
    "y": "tughj", "u": "yihjk", "i": "uojkl", "o": "ipkl", "p": "ol",
    "a": "qwsz", "s": "qweadzxc", "d": "wersfxc", "f": "ertdgvcb",
    "g": "rtyfhvbn", "h": "tyugjbnm", "j": "yuihknm", "k": "uiojlm",
    "l": "kiop", "z": "asx", "x": "zasdc", "c": "xsdfv", "v": "cdfgb",
    "b": "vfghn", "n": "bghjm", "m": "nhjk",
}

# 与两份 schema 里 abbrev 之前的基础派生规则逐条对应（见 load_alternates）
BASE_DERIVES = [
    (re.compile(r"^([jqxy])u"), r"\1v"),
    (re.compile(r"^([nl])ve$"), r"\1ue"),
]

# 纠错块的落点：algebra 末尾（最后一条 fuzz 之后）。fuzz 之后是硬约束，
# 见模块 docstring；变体绝不能被模糊规则二次加工。
ANCHOR_RE = re.compile(r'^( {4}- "fuzz/\(\[aeiou\]\)n\$/\$1ng/".*)$', re.M)
BLOCK_BEGIN = "    # >>> 相邻键纠错表（tools/gen-adjacent-key-algebra.py 生成，勿手改）"
BLOCK_END = "    # <<< 相邻键纠错表结束"
BLOCK_RE = re.compile(
    re.escape(BLOCK_BEGIN) + r".*?" + re.escape(BLOCK_END) + r"\n?", re.S
)


def load_syllables():
    text = KEY_TABLE.read_text(encoding="utf-8")
    syllables = set()
    for code, value in ENTRY_RE.findall(text):
        if len(code) != 2:
            continue  # 单键项是声母提示，不是完整音节
        for syllable in value.split(","):
            syllable = syllable.strip()
            if syllable:
                syllables.add(syllable)
    return syllables


def load_alternates(syllables):
    """基础派生规则会往棱镜里加的替代写法，纠错变体不能与它们撞车。"""
    alternates = set()
    for pattern, replacement in BASE_DERIVES:
        for syllable in syllables:
            if pattern.search(syllable):
                alternates.add(pattern.sub(replacement, syllable))
    return alternates - syllables


def build_table(syllables, alternates):
    """返回 [(音节, 变体)] 与按原因分类的丢弃统计。"""
    blocked = syllables | alternates
    table = set()
    stats = {"len": 0, "valid_or_alternate": 0}
    for syllable in sorted(syllables):
        for i, ch in enumerate(syllable):
            for neighbor in ADJACENCY[ch]:
                variant = syllable[:i] + neighbor + syllable[i + 1:]
                if len(variant) < 2:
                    stats["len"] += 1
                elif variant in blocked:
                    stats["valid_or_alternate"] += 1
                else:
                    table.add((syllable, variant))
    stats["variants"] = len({variant for _, variant in table})
    return sorted(table), stats


def render(table):
    lines = [
        BLOCK_BEGIN + "\n",
        "    # 每条把一步邻键误触的变体纠回本音节；本身是合法音节或基础派生写法的\n",
        "    # 变体已剔除，多音节共有的变体各留一条（按词频同场竞争）。\n",
        "    # 用 correct 而不是 derive：纠错拼写被标成 kCorrectionSpelling，类型排在\n",
        "    # abbrev 之后；同一位置存在精确/简拼路径时音节图会剪掉纠错边，只有没有\n",
        "    # 正常路径时才保留纠错（yuanl 不再被 yua'nl 抢走）。\n",
        "    # 重跑生成：python tools/gen-adjacent-key-algebra.py --write\n",
    ]
    lines += [f'    - "correct/^{syllable}$/{variant}/"\n' for syllable, variant in table]
    lines.append(BLOCK_END + "\n")
    return "".join(lines)


def upsert(schema_path: Path, block):
    text = schema_path.read_text(encoding="utf-8")
    # 先剥掉旧位置的生成块（历史上插在 abbrev 之后），保证换锚点时能整体搬家
    if BLOCK_BEGIN in text:
        text = BLOCK_RE.sub("", text, count=1)
    anchor = ANCHOR_RE.search(text)
    if not anchor:
        sys.exit(f"{schema_path} 里找不到 fuzz 锚点行，拒绝盲写")
    line_end = anchor.end(1)
    return text[:line_end] + "\n" + block.rstrip("\n") + text[line_end:]


def schema_paths():
    """待写回的两份 schema；路径规范化后必须都落在仓库的 build 目录里。"""
    root = SCHEMA_DIR.resolve()
    paths = []
    for name in SCHEMA_NAMES:
        target = (root / name).resolve()
        if target.parent != root:
            sys.exit(f"schema 路径越出允许目录：{target}")
        paths.append(target)
    return paths


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true", help="写回两份 schema")
    args = ap.parse_args()

    syllables = load_syllables()
    if len(syllables) < 400:
        sys.exit(f"音节表解析结果过少（{len(syllables)} 个），请检查 LX17PinYinUtils.kt")
    alternates = load_alternates(syllables)
    table, stats = build_table(syllables, alternates)
    print(f"音节 {len(syllables)} 个，基础派生写法 {len(alternates)} 个")
    print(f"变体规则 {len(table)} 条（覆盖 {stats['variants']} 个变体）；丢弃："
          f"单字母 {stats['len']}、撞合法码 {stats['valid_or_alternate']}")
    spot = {syllable: variant for syllable, variant in table
            if syllable in ("zhong", "song", "zong")}
    print(f"抽查：{spot if spot else '（无 zhong/song/zong 规则，异常）'}")
    if not args.write:
        print("（未写回，加 --write 生效）")
        return 0
    block = render(table)
    for target in schema_paths():
        target.write_text(upsert(target, block), encoding="utf-8")
        print(f"已写回 {target.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
