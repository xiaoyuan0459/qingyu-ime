#!/usr/bin/env python3
"""
从旧预编译库 libyuyanime.so 中还原内嵌的 rime 配置（default.yaml 与各 *.schema.yaml）。

背景
----
旧 SDK 的预编译 .so 把 rime 的配置文件**内嵌在 .rodata** 里，运行时再写出到
shared_data_dir，因此其仓库 assets/rime/ 下只有编译产物 build/*.bin，没有配置源。
本项目自研 native 层后需要这些配置，而它们无法从 assets 获得 —— 只能从旧库中还原。

原理
----
内嵌的 yaml 文本在文件中是**连续字节**，仅由可打印 ASCII、换行与制表符构成。
本脚本扫描整个文件，取出所有长度超过阈值的此类连续块，再按内容特征识别归属。

用法
----
    python extract-embedded-rime-config.py <libyuyanime.so> <输出目录>

产出
----
    <输出目录>/default.yaml
    <输出目录>/<schema_id>.schema.yaml
    <输出目录>/_unidentified/<偏移>.txt   （无法识别的块，供人工检查）
"""

import os
import re
import sys

# 分隔符：ELF .rodata 里的 C 字符串以 NUL 结尾。
# 注意：不能改用「可打印 ASCII」当判据 —— yaml 里含中文标点（UTF-8 多字节），
# 按 ASCII 判定会把 `symbols: "/": ["。"]` 这种行当成块边界，导致文件被腰斩。
_SEPARATOR = 0x00

# 短于此长度的块不考虑。default.yaml 只内嵌了 schema_list 段（318 字节），
# 故阈值取 120 以免漏掉它。
MIN_BLOCK = 120

# 判定为 yaml 的必备特征
# 注意 schema_id 位于 `schema:` 之下、缩进两格，故必须允许行首空白
_SCHEMA_ID = re.compile(r"^\s*schema_id:\s*([A-Za-z0-9_]+)\s*$", re.M)
_SCHEMA_LIST = re.compile(r"^\s*schema_list:\s*$", re.M)


def scan_blocks(data: bytes, min_len: int = MIN_BLOCK):
    """产出 (偏移, 字节) —— 所有以 NUL 分隔且长度达标的块。"""
    blocks = []
    start = 0
    n = len(data)
    for i in range(n + 1):
        if i == n or data[i] == _SEPARATOR:
            if i - start >= min_len:
                blocks.append((start, data[start:i]))
            start = i + 1
    return blocks


def decode(raw: bytes):
    """块内可能混入少量二进制尾巴，从尾部逐字节回退直到能严格解码。"""
    for cut in range(0, min(64, len(raw))):
        try:
            return raw[: len(raw) - cut].decode("utf-8")
        except UnicodeDecodeError:
            continue
    try:
        return raw.decode("utf-8", errors="replace")
    except Exception:
        return None


def is_yaml(text: str) -> bool:
    """轻量判定：有缩进层级、有短横线列表项、以 `key:` 形式组织。"""
    if ":" not in text:
        return False
    lines = [ln for ln in text.splitlines() if ln.strip()]
    if len(lines) < 5:
        return False
    keyish = sum(1 for ln in lines if re.match(r"^\s*(-\s+)?[A-Za-z_][A-Za-z0-9_/]*:", ln))
    return keyish >= max(3, len(lines) // 4)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1

    so_path, out_dir = sys.argv[1], sys.argv[2]
    if not os.path.isfile(so_path):
        print(f"找不到文件: {so_path}")
        return 1

    with open(so_path, "rb") as f:
        data = f.read()

    os.makedirs(out_dir, exist_ok=True)
    unid_dir = os.path.join(out_dir, "_unidentified")

    blocks = scan_blocks(data)
    print(f"{os.path.basename(so_path)}: {len(data)} 字节，命中 {len(blocks)} 个候选块")

    written, skipped, unidentified = [], 0, 0

    for offset, raw in blocks:
        text = decode(raw)
        if text is None or not is_yaml(text):
            skipped += 1
            continue

        m = _SCHEMA_ID.search(text)
        if m:
            # schema 定义文件
            name = f"{m.group(1)}.schema.yaml"
        elif _SCHEMA_LIST.search(text):
            name = "default.yaml"
        else:
            # 其余 yaml（如 user.yaml 片段）留待人工判断
            os.makedirs(unid_dir, exist_ok=True)
            name = os.path.join("_unidentified", f"{offset:08x}.txt")

        dest = os.path.join(out_dir, name)
        # 同名冲突：保留更长的那个（更完整）
        if os.path.exists(dest):
            if os.path.getsize(dest) >= len(raw):
                skipped += 1
                continue
            print(f"  覆盖更短的重复块: {name} ({os.path.getsize(dest)} → {len(raw)})")

        with open(dest, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        written.append((name, offset, len(raw)))

        if name.startswith("_unidentified") or name.startswith(os.path.join("_unidentified")):
            unidentified += 1

    for name, offset, size in sorted(written):
        print(f"  写出 {name:40s} off=0x{offset:08x} {size} 字节")

    print(f"\n写出 {len(written)} 个文件，跳过 {skipped} 个非 yaml 块，"
          f"其中未识别 {unidentified} 个（见 _unidentified/）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
