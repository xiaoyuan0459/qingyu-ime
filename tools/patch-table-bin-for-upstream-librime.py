#!/usr/bin/env python3
"""把旧闭源 SDK 产出的 *.table.bin 头部改写成上游 librime 的布局。

背景
----
`imesdk/src/main/assets/rime/build/*.table.bin` 是从旧工程继承的**预编译词库**，
由旧 `libyuyanime.so` 内嵌的那份 librime 私有分叉产出。该分叉在
`rime::table::Metadata` 里多插了一个 4 字节字段，于是 `string_table` /
`string_table_size` 两个字段的位置整体后移了 4 字节：

    分叉布局（头 0x48 字节）        上游 1.11.2（头 0x44 字节）
    0x40 OffsetPtr<char> string_table    →  0x3C OffsetPtr<char> string_table
    0x44 uint32_t            string_table_size → 0x40 uint32_t string_table_size

上游按 0x3C 读到的恒为 0，而 `OffsetPtr::get()` 把 0 解释成 NULL，于是
`Table::OnLoad()` → `StringTable(nullptr, size)` → `stringstream::write(nullptr, size)`
在 `memmove` 里 SIGSEGV。这就是「引擎能建会话、一装载词典就崩」的根因。

`*.prism.bin` 不受影响：实测其头部与上游 1.11.2 的 `prism::Metadata` 逐字段吻合。

做法
----
只改写头部这 8 个字节，文件其余部分**一字节不动** —— Metadata 之后的
syllabary / index / entry 全部由各自字段里的相对偏移定位，与头部大小无关，
因此把 0x3C/0x40 填成分叉里的真实值即可，不需要重排文件。

幂等：已转换过的文件（0x3C 处非零且 marisa 魔数对得上）会跳过。

用法：
    python tools/patch-table-bin-for-upstream-librime.py [--check] <文件或目录>...
"""

import argparse
import os
import struct
import sys

# marisa 序列化镜像的魔数（grimoire 的自述字符串），用来验证偏移算对了。
MARISA_MAGIC = b"We love Marisa.\x00"

UPSTREAM_FMT = b"Rime::Table/"
UPSTREAM_STRING_TABLE = 0x3C          # OffsetPtr<char>，相对自身地址
UPSTREAM_STRING_TABLE_SIZE = 0x40      # uint32
FORK_STRING_TABLE = 0x40
FORK_STRING_TABLE_SIZE = 0x44
HEADER_SIZE = 0x48                     # 分叉的 sizeof(table::Metadata)


def classify(path):
    """返回 (状态, 说明)。状态 ∈ {ok, needs_patch, not_table, unknown}。"""
    size = os.path.getsize(path)
    with open(path, "rb") as fh:
        head = fh.read(HEADER_SIZE)
    if len(head) < HEADER_SIZE or not head[:16].startswith(UPSTREAM_FMT):
        return "not_table", "不是 Rime::Table 文件"

    def read_u32(off):
        return struct.unpack("<i", head[off:off + 4])[0]

    for label, off_ptr, off_size in (
        ("上游", UPSTREAM_STRING_TABLE, UPSTREAM_STRING_TABLE_SIZE),
        ("分叉", FORK_STRING_TABLE, FORK_STRING_TABLE_SIZE),
    ):
        image_off = read_u32(off_ptr)
        image_size = read_u32(off_size)
        if image_off <= 0 or image_size <= 0:
            continue
        abs_off = off_ptr + image_off
        if abs_off + image_size != size:
            continue
        with open(path, "rb") as fh:
            fh.seek(abs_off)
            if fh.read(len(MARISA_MAGIC)) != MARISA_MAGIC:
                continue
        return ("ok" if label == "上游" else "needs_patch"), label
    return "unknown", "两种布局都对不上，需人工核对"


def patch(path):
    """把分叉头部改写成上游布局；返回 True 表示有写入。"""
    size = os.path.getsize(path)
    with open(path, "r+b") as fh:
        head = fh.read(HEADER_SIZE)
        image_abs = FORK_STRING_TABLE + struct.unpack("<i", head[FORK_STRING_TABLE:FORK_STRING_TABLE + 4])[0]
        image_size = struct.unpack("<I", head[FORK_STRING_TABLE_SIZE:FORK_STRING_TABLE_SIZE + 4])[0]
        fh.seek(image_abs)
        if fh.read(len(MARISA_MAGIC)) != MARISA_MAGIC:
            raise SystemExit(f"{path}: 偏移 {image_abs} 处没有 marisa 魔数，拒绝改写")
        if image_abs + image_size != size:
            raise SystemExit(f"{path}: 镜像长度 {image_size} 与文件尾部不吻合，拒绝改写")
        fh.seek(UPSTREAM_STRING_TABLE)
        fh.write(struct.pack("<iI", image_abs - UPSTREAM_STRING_TABLE, image_size))
    return True


def targets(args):
    for p in args:
        if os.path.isdir(p):
            for name in sorted(os.listdir(p)):
                if name.endswith(".table.bin"):
                    yield os.path.join(p, name)
        else:
            yield p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只报告，不写入")
    ap.add_argument("paths", nargs="+")
    opts = ap.parse_args()

    changed = 0
    for path in targets(opts.paths):
        state, detail = classify(path)
        if state == "ok":
            print(f"已是上游布局  {path}")
        elif state == "needs_patch":
            if opts.check:
                print(f"需转换        {path}")
                changed += 1
            else:
                patch(path)
                print(f"已转换        {path}  ({classify(path)[0]})")
                changed += 1
        else:
            print(f"跳过（{detail}）  {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
