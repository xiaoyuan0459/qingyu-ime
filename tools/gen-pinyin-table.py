#!/usr/bin/env python3
"""由 MIT 许可的 pinyin-data 数据集生成轻语输入法使用的汉字拼音表。

数据来源: https://github.com/mozillazg/pinyin-data
         MIT License, Copyright (c) 2016 mozillazg
输入格式: pinyin.txt, 形如  U+4E00: yī,yí,yì  # 一
输出格式: imesdk/src/main/assets/pinyin_table.txt
         首部为许可声明注释，其后每行 `十六进制码位<TAB>拼音`
         拼音取该字的**第一读音**，带声调（如 一 → yī）

用法:
    python tools/gen-pinyin-table.py /path/to/pinyin.txt
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# 覆盖区间：CJK 扩展 A + CJK 基本区（U+3400..U+9FFF）
# 取值权衡：再往上的扩展 B 及以上都是罕用字，拼音4j 原来也不覆盖，暂不纳入。
FIRST_CODEPOINT = 0x3400
LAST_CODEPOINT = 0x9FFF

# 输出路径（相对本脚本所在仓库根目录）
OUTPUT_RELATIVE = Path("imesdk/src/main/assets/pinyin_table.txt")

ENTRY = re.compile(r"^U\+([0-9A-Fa-f]+):\s*([^#\s]+)")
VERSION = re.compile(r"^#\s*version:\s*(\S+)")


def parse(source: Path) -> tuple[dict[int, str], str]:
    """解析 pinyin.txt，返回 {码位: 第一读音} 与数据版本号。"""
    table: dict[int, str] = {}
    version = "unknown"
    with source.open(encoding="utf-8") as handle:
        for raw in handle:
            line = raw.rstrip("\n")
            if not line:
                continue
            if line.startswith("#"):
                matched = VERSION.match(line)
                if matched:
                    version = matched.group(1)
                continue
            matched = ENTRY.match(line)
            if not matched:
                continue
            codepoint = int(matched.group(1), 16)
            if not FIRST_CODEPOINT <= codepoint <= LAST_CODEPOINT:
                continue
            # 多音字取第一个读音（与既有行为一致：候选标注只显示一个读音）
            reading = matched.group(2).split(",")[0]
            table[codepoint] = reading
    return table, version


def write(table: dict[int, str], version: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(
            "# 汉字拼音表：每行 `十六进制码位<TAB>拼音`，拼音取第一读音、带声调\n"
            "# 本文件由 tools/gen-pinyin-table.py 生成，请勿手工编辑\n"
            "#\n"
            f"# 数据来源: pinyin-data v{version}\n"
            "#   https://github.com/mozillazg/pinyin-data\n"
            "#   MIT License, Copyright (c) 2016 mozillazg\n"
            "#\n"
        )
        for codepoint in sorted(table):
            handle.write(f"{codepoint:X}\t{table[codepoint]}\n")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    source = Path(argv[1])
    if not source.is_file():
        print(f"找不到输入文件: {source}")
        return 1

    table, version = parse(source)
    if not table:
        print("解析结果为空，请检查输入文件格式")
        return 1

    target = Path(__file__).resolve().parent.parent / OUTPUT_RELATIVE
    write(table, version, target)
    size_kb = target.stat().st_size / 1024
    print(f"数据版本: {version}")
    print(f"收录汉字: {len(table)}")
    print(f"输出: {target} ({size_kb:.0f} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
