#!/usr/bin/env python3
"""禁止私密编辑框触发 librime 内置用户词库写入；不改变中文候选生成。"""

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def patch(source: Path):
    expected = json.loads((ROOT / "tools/native-deps.lock.json").read_text(encoding="utf-8"))["librime"]["revision"]
    actual = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if actual != expected:
        raise ValueError(f"librime revision mismatch: expected {expected}, got {actual}")
    path = source / "src/rime/gear/memory.cc"
    before = path.read_text(encoding="utf-8")
    after = before
    for name in ("OnCommit", "OnDeleteEntry"):
        anchor = f"void Memory::{name}(Context* ctx) {{\n"
        guard = anchor + '  if (!ctx || ctx->get_property("qingyu_no_learning") == "1")\n    return;\n'
        if after.count(guard) == 1:
            continue
        if after.count(anchor) != 1 or (anchor + '  if (!user_dict_') not in after:
            raise ValueError(f"memory.cc {name} anchor mismatch; refusing partial patch")
        after = after.replace(anchor, guard, 1)
    if before != after:
        path.write_text(after, encoding="utf-8", newline="\n")
    print(json.dumps({"revision": actual, "patched": before != after,
                      "guard": "SDK context property blocks commit/delete writes; candidate queries unchanged"}))


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("usage: patch-librime-editor-learning.py <pinned librime checkout>")
        patch(Path(sys.argv[1]))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"librime editor learning patch: {error}", file=sys.stderr)
        raise SystemExit(1) from None
