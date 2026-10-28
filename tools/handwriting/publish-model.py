#!/usr/bin/env python3
"""把训练产物发布成 App 资产（assets/handwriting/qingyu-hw-image-v1/）。

只拷 int8 模型：8.05 MB，是实际打进 APK 的那一份；fp16/fp32 只留在 .cache 里做对照。
字表直接复用训练侧的 charset.json —— `classes` 数组的顺序就是输出维度到字符的映射，
App 侧按同一个数组解析，不再维护第二份 `char_to_idx`，避免两套顺序漂移。

用法::

    python tools/handwriting/publish-model.py --model tools/handwriting/.cache/models/v4
    python tools/handwriting/publish-model.py --model ... --dry-run
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ASSET_ROOT = REPO / "imesdk" / "src" / "main" / "assets" / "handwriting"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=HERE / ".cache" / "models" / "v4")
    parser.add_argument("--version", default="qingyu-hw-image-v1")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    src = args.model / "int8"
    param = src / "model-int8.param"
    binary = src / "model-int8.bin"
    charset = args.model / "charset.json"
    manifest = args.model / "model-manifest.json"

    for path in (param, binary, charset, manifest):
        if not path.exists():
            raise SystemExit(f"缺 {path}，先跑 export-model.py --ncnn 和 quantize-int8.py")

    info = json.loads(charset.read_text(encoding="utf-8"))
    classes = info["classes"]
    if len(classes) != info["num_classes"]:
        raise SystemExit(f"字表自相矛盾：classes {len(classes)} vs num_classes {info['num_classes']}")
    if len(set(classes)) != len(classes):
        raise SystemExit("字表有重复字符，输出维度到字符的映射会错位")

    # App 侧启动时会用这个哈希做校验，发布前必须先自己验一遍，
    # 否则装到手机上才会发现模型加载失败
    declared = info.get("classes_sha256", "")
    actual = hashlib.sha256("".join(classes).encode("utf-8")).hexdigest()
    if declared and declared != actual:
        raise SystemExit(f"classes_sha256 不匹配：字段 {declared[:16]}… 实算 {actual[:16]}…")

    out_dir = ASSET_ROOT / args.version
    plan = [
        (param, out_dir / "model.ncnn.param"),
        (binary, out_dir / "model.ncnn.bin"),
        (charset, out_dir / "charset.json"),
        (manifest, out_dir / "model-manifest.json"),
    ]

    total = sum(p.stat().st_size for p, _ in plan)
    print(f"类别 {len(classes)}，资产合计 {total / 1024 / 1024:.2f} MB → {out_dir}")
    for source, dest in plan:
        print(f"  {source.name:22s} {source.stat().st_size / 1024 / 1024:6.2f} MB  "
              f"sha256 {sha256(source)[:16]}…")

    if args.dry_run:
        print("\n--dry-run：未写入")
        return 0

    out_dir.mkdir(parents=True, exist_ok=True)
    for source, dest in plan:
        shutil.copy2(source, dest)

    print(f"\n已写入 {out_dir.relative_to(REPO)}：")
    for path in sorted(out_dir.iterdir()):
        print(f"  {path.name:24s} {path.stat().st_size:>10,} 字节")
    print("\n提醒：发布前确认 LICENSE 声明包含 OFL 字体来源（见 tools/handwriting/README.md）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
