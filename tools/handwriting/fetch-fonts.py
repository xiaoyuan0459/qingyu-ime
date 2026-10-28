#!/usr/bin/env python3
"""按 fonts.json 下载 OFL 字体，并写入可复现的 fonts.lock.json。

为什么不爬图：字形来源必须是可追溯、可再分发的。搜索结果里的「字体图片」既无
授权链，也无法保证 64×64 口径一致；OFL 字体可以直接从上游仓库取到原始 TTF，
用它渲染字形再增强，既有明确许可，也能精确复现 Android 侧的预处理。

用法::

    python tools/handwriting/fetch-fonts.py           # 下载并生成 lock
    python tools/handwriting/fetch-fonts.py --check   # 只校验本地缓存与 lock

字体只落在 tools/handwriting/.cache/（已 gitignore），不进 APK，也不进仓库。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
MANIFEST = HERE / "fonts.json"
CACHE = HERE / ".cache" / "fonts"
LOCK = HERE / "fonts.lock.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "qingyu-ime-font-fetch/1"})
    with urllib.request.urlopen(req, timeout=120) as resp, tmp.open("wb") as out:
        shutil.copyfileobj(resp, out)
    tmp.replace(dest)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="只校验，不联网下载")
    args = parser.parse_args()

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    lock = json.loads(LOCK.read_text(encoding="utf-8")) if LOCK.exists() else {"fonts": {}}
    entries: dict[str, dict] = {}
    failed: list[str] = []

    for font in manifest["fonts"]:
        fid = font["id"]
        dest = CACHE / font["file"]
        expect = lock["fonts"].get(fid, {})

        if not dest.exists() or dest.stat().st_size == 0:
            if args.check:
                failed.append(f"{fid}: 缺文件 {dest.name}")
                continue
            print(f"下载 {fid} ({font['family']}) …", flush=True)
            fetch(font["url"], dest)

        digest = sha256(dest)
        size = dest.stat().st_size
        if expect.get("sha256") and expect["sha256"] != digest:
            failed.append(f"{fid}: sha256 不匹配（lock {expect['sha256'][:12]}… / 实际 {digest[:12]}…）")
            continue

        entries[fid] = {
            "family": font["family"],
            "file": font["file"],
            "kind": font["kind"],
            "style": font["style"],
            "url": font["url"],
            "license": font["license"],
            "license_url": font["license_url"],
            "sha256": digest,
            "bytes": size,
            "weights": font.get("weights", [400]),
        }
        print(f"  ✓ {fid:14s} {size/1024/1024:6.2f} MB  {digest[:16]}…")

        lic = CACHE / "licenses" / f"{fid}-OFL.txt"
        if not lic.exists() and not args.check:
            try:
                fetch(font["license_url"], lic)
            except Exception as exc:  # 许可证缺失不阻塞，但必须显式报告
                print(f"    ! 许可证下载失败：{exc}")

    if failed:
        print("\n校验失败：")
        for item in failed:
            print("  -", item)
        return 1

    if not args.check:
        LOCK.write_text(
            json.dumps({"version": 1, "fonts": entries}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"\n已写入 {LOCK.relative_to(HERE.parent.parent)}（{len(entries)} 个字体）")
    else:
        print(f"\n本地缓存与 lock 一致（{len(lock['fonts'])} 个字体）")

    print("提醒：这些字体只用于离线渲染，不打包进 APK；若最终分发训练脚本的字体子集，"
          "需在第三方声明中保留 OFL 文本与保留字体名。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
