#!/usr/bin/env python3
"""从 PP-OCRv6 官方 inference.yml 生成端上字表 charset.json。

输出层布局（已由 tools/verify-ppocr-model.py 实测）：
  id 0      = blank
  id 1..N   = character_dict[0..N-1]
  id >N     = 保留通道，忽略
因此端上只保存 character_dict 的原始顺序，识别时用 output_index - 1 映射。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
YML = ROOT / "imesdk/src/main/assets/ppocrv6_rec/inference.yml"
OUT = ROOT / "imesdk/src/main/assets/ppocrv6_rec/charset.json"


def load_dict(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    key = next((i for i, line in enumerate(lines) if line.strip() == "character_dict:"), -1)
    if key < 0:
        raise SystemExit("inference.yml 中找不到 character_dict")
    items: list[str] = []
    for line in lines[key + 1:]:
        stripped = line.strip(" \t")
        if not stripped or stripped.startswith("#"):
            continue
        if not stripped.startswith("- "):
            break
        value = stripped[2:].strip(" \t")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        items.append(value)
    return items


def main() -> int:
    chars = load_dict(YML)
    if not chars:
        raise SystemExit("字表为空")
    han = sum(
        1 for c in chars
        if len(c) == 1 and (
            0x4E00 <= ord(c) <= 0x9FFF or
            0x3400 <= ord(c) <= 0x4DBF or
            0xF900 <= ord(c) <= 0xFAFF or
            0x20000 <= ord(c) <= 0x2FA1F
        )
    )
    payload = {"num_classes": len(chars), "chars": chars}
    OUT.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print(f"charset: {len(chars)} entries ({han} Han) -> {OUT.relative_to(ROOT)}")
    if len(chars) != 18708:
        print(f"warning: 官方字表长度变为 {len(chars)}，请同步检查输出层 {len(chars) + 2}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
