#!/usr/bin/env python3
"""
就地修正 librime 依赖中**与新版 clang 不兼容**的第三方源码。

为什么需要这个脚本
------------------
`third_party/librime` 是浅克隆的上游源码，其 `deps/` 下几个依赖被上游锁在较老的
版本上。NDK r30（clang 21，`-std=gnu++17`）会对其中某些写法直接报错，导致构建失败。
这些目录不进我们的仓库（见 `imesdk/.gitignore`），因此**补丁无法以 git 形式保存**，
必须做成"每次构建前自动重放"的步骤，否则重新克隆 librime 后会再次踩坑。

本脚本是幂等的：已打过补丁的文件会被识别并跳过。

由 `imesdk/src/main/cpp/qingyu_rime/CMakeLists.txt` 在配置阶段调用。

用法
----
    python tools/patch-opencc-deps.py <librime 目录>
"""

import os
import sys


def _read(path: str):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(path: str, text: str):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def patch_rapidjson(opencc_root: str) -> bool:
    """修正 rapidjson 1.1.0 的 GenericStringRef。

    原始代码：
        const Ch* const s;
        const SizeType length;
        ...
        GenericStringRef& operator=(const GenericStringRef& rhs) { s = rhs.s; length = rhs.length; }

    `operator=` 给两个 **const 限定**的数据成员赋值，这在 C++11 起即为不合法；
    旧编译器只是不诊断，clang 会直接报错：

        error: cannot assign to non-static data member 'length'
               with const-qualified type 'const SizeType'

    rapidjson 上游后续版本已修掉该问题。这里采取与上游一致的思路：去掉成员上的
    顶层 const（`const Ch* const` → `const Ch*`、`const SizeType` → `SizeType`），
    保持 `operator=` 的原有语义不变。

    返回是否做了修改。
    """
    doc = os.path.join(opencc_root, "deps", "rapidjson-1.1.0", "rapidjson", "document.h")
    if not os.path.isfile(doc):
        print(f"  跳过：未找到 {doc}")
        return False

    text = _read(doc)

    # 注意：锚点必须是**单行**子串。该文件用 CRLF 换行（2575 处），
    # 跨行锚点会因换行符形式不同而失配。
    # 带上行尾注释可确保唯一 —— `const Ch* const` 在别处还作为局部变量出现过。
    edits = [
        (
            "const Ch* const s; //!< plain CharType pointer",
            "const Ch* s; //!< plain CharType pointer",
        ),
        (
            "const SizeType length; //!< length of the string (excluding the trailing NULL terminator)",
            "SizeType length; //!< length of the string (excluding the trailing NULL terminator)",
        ),
    ]

    changed = False
    for old, new in edits:
        if new in text and old not in text:
            continue  # 该条已打过
        occurrences = text.count(old)
        if occurrences == 0:
            print(f"  rapidjson: 未找到待修正片段「{old[:40]}…」，跳过")
            continue
        if occurrences > 1:
            # 宁可不动，也不要误改
            print(f"  rapidjson: 片段出现 {occurrences} 次，无法安全定位，跳过")
            continue
        text = text.replace(old, new, 1)
        changed = True

    if not changed:
        print("  rapidjson: 已是最新或无需修正，跳过")
        return False

    _write(doc, text)
    print("  rapidjson: 已修正 GenericStringRef 的 const 成员赋值问题")
    return True


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 1

    librime_root = sys.argv[1]
    opencc_root = os.path.join(librime_root, "deps", "opencc")
    if not os.path.isdir(opencc_root):
        print(f"找不到 OpenCC 目录: {opencc_root}")
        return 1

    print(f"检查 {librime_root} 下的依赖是否需要打补丁：")
    changed = patch_rapidjson(opencc_root)
    print("完成" + ("（有修改）" if changed else "（无修改）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
