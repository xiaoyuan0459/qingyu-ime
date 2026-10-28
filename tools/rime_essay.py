#!/usr/bin/env python3
"""处理 `essay.txt` 里的繁体词条。

`rime-essay` 是跨简繁的通用词表，44 万条里有 29 万条含繁体字，其中 1.8 万条权重
≥1000 —— 足以在同音节里插到简体候选前面。实测「我們」权重 479209，比我们降权后的
「我们」（356572）还高，输入 `women` 时繁体跑到第一。

简体词库不需要这些繁体词条：用户切繁体时由 `simplifier@traditionalize` 把简体
候选转过去，不依赖词表里的繁体形式。所以组装阶段把它们降权，既清掉插队，
又不减少候选深度（词条仍在，只是排到后面）。

判断依据是 OpenCC 的 `STCharacters.txt`（简 → 繁 映射）反查出的繁体字集合，
与 `assets/rime/opencc/` 里随包分发的是同一份数据。
"""

from __future__ import annotations

import os

# 低于该权重的词条本来就排不进前列，不动它，避免无谓改写。
DAMP_FLOOR = 1000
DAMP_FACTOR = 0.01


def load_traditional_chars(opencc_dir: str) -> set[str]:
    """从 STCharacters.txt 反查繁体字集合（排除与简体同形的字）。"""
    chars: set[str] = set()
    path = os.path.join(opencc_dir, "STCharacters.txt")
    if not os.path.exists(path):
        return chars
    with open(path, encoding="utf-8") as stream:
        for line in stream:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            simplified = parts[0]
            for variant in parts[1].split():
                if variant != simplified:
                    chars.add(variant)
    return chars


def damp_traditional(essay_path: str, traditional: set[str],
                     floor: float = DAMP_FLOOR,
                     factor: float = DAMP_FACTOR) -> tuple[int, int]:
    """把含繁体字且权重不低于 floor 的词条降权，返回 (改写条数, 总条数)。"""
    if not traditional or not os.path.exists(essay_path):
        return 0, 0

    rows = []
    changed = total = 0
    with open(essay_path, encoding="utf-8", newline="") as stream:
        for line in stream:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 2:
                rows.append(line)
                continue
            total += 1
            word, raw = parts[0], parts[1]
            try:
                weight = float(raw)
            except ValueError:
                rows.append(line)
                continue
            if weight < floor or not any(ch in traditional for ch in word):
                rows.append(line)
                continue
            rows.append(f"{word}\t{max(1, round(weight * factor))}\n")
            changed += 1

    if changed:
        with open(essay_path, "w", encoding="utf-8", newline="\n") as stream:
            stream.writelines(rows)
    return changed, total
