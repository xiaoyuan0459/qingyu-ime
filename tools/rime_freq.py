#!/usr/bin/env python3
"""自有频率覆盖表的读取与注入。

轻语的词表与拼音继承上游，权重自己算。`tools/gen-dict-freq.py` 产出
`词<TAB>权重` 的覆盖表，本模块负责把它写进上游分表的权重列。

上游源文件保持原样（可追溯、可由固定 revision 重建），只改组装产物 —— 这是
「词表继承上游、频率自己掌握」的落点：词条与拼音不动，只换排序依据。
"""

from __future__ import annotations

import os

# 参与频率注入的分表。8105 / 41448 是字表，权重语义不同，不在此列。
FREQ_TABLES = ("base", "ext", "tencent", "others")


def read_freq_table(path):
    """读覆盖表（`词<TAB>权重`），返回 词 → 权重字符串。"""
    table = {}
    with open(path, encoding="utf-8") as stream:
        for line in stream:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[1].isdigit():
                table[parts[0]] = parts[1]
    return table


def apply_freq_table(cn_dicts, freq_path):
    """把覆盖表注入分表权重列，返回 (覆盖表词数, 改写条数, 命中条数)。

    命中条数会大于覆盖表词数：多音词在分表里有多条，共用同一个词级频率。
    """
    table = read_freq_table(freq_path)
    changed = matched = 0
    for name in FREQ_TABLES:
        path = os.path.join(cn_dicts, name + ".dict.yaml")
        if not os.path.exists(path):
            continue
        rows = []
        with open(path, encoding="utf-8", newline="") as stream:
            for line in stream:
                if line.startswith("#") or line.startswith("-") or "\t" not in line:
                    rows.append(line)
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 3:
                    weight = table.get(parts[0])
                    if weight is not None:
                        matched += 1
                        if weight != parts[2]:
                            parts[2] = weight
                            changed += 1
                            rows.append("\t".join(parts) + "\n")
                            continue
                rows.append(line)
        with open(path, "w", encoding="utf-8", newline="\n") as stream:
            stream.writelines(rows)
    return len(table), changed, matched
