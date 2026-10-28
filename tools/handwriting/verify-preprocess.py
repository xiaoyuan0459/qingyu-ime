#!/usr/bin/env python3
"""一致性测试：Python 版预处理 vs `preprocess.c` 真实编译产物。

训练侧（Python）与 Android 侧（JNI）必须给出同一张 64×64 输入。这里用 clang 把
C 源码直接编成 DLL 再逐像素比对，而不是靠「看起来一样」——字典顺序、重采样核、
取整方式任何一处漂移都会让真机精度莫名下降。

用法::

    python tools/handwriting/verify-preprocess.py
    python tools/handwriting/verify-preprocess.py --images 200 --keep
"""

from __future__ import annotations

import argparse
import ctypes
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import preprocess_ref as prep  # noqa: E402

C_SOURCE = REPO / "imesdk" / "src" / "main" / "cpp" / "preprocess.c"
BUILD_DIR = HERE / ".cache" / "build"
DLL = BUILD_DIR / "preprocess.dll"
FONT_DIR = HERE / ".cache" / "fonts"


def build_dll() -> Path:
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    if DLL.exists() and DLL.stat().st_mtime > C_SOURCE.stat().st_mtime:
        return DLL
    print(f"编译 {C_SOURCE.name} → {DLL.name}")
    # Windows 下 clang 默认不导出任何符号，必须显式声明导出，否则 ctypes 找不到函数
    result = subprocess.run(
        ["clang", "-O2", "-shared", "-o", str(DLL), str(C_SOURCE),
         "-Wl,/EXPORT:qingyu_hw_preprocess"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"编译失败（需要 clang + 可用链接器）：\n{result.stderr[-1500:]}")
    return DLL


def load_c(dll: Path):
    lib = ctypes.CDLL(str(dll))
    fn = lib.qingyu_hw_preprocess
    fn.argtypes = [ctypes.POINTER(ctypes.c_uint8), ctypes.c_int, ctypes.c_int,
                   ctypes.POINTER(ctypes.c_float)]
    fn.restype = ctypes.c_int

    def run(gray: np.ndarray) -> tuple[int, np.ndarray]:
        h, w = gray.shape
        out = (ctypes.c_float * (prep.CANVAS_SIZE * prep.CANVAS_SIZE))()
        src = (ctypes.c_uint8 * (w * h)).from_buffer_copy(np.ascontiguousarray(gray).tobytes())
        ok = fn(src, w, h, out)
        return ok, np.frombuffer(out, dtype=np.float32).reshape(prep.CANVAS_SIZE, prep.CANVAS_SIZE)

    return run


def synthetic_cases(rng: np.random.Generator, count: int) -> list[tuple[str, np.ndarray]]:
    """覆盖极端输入：空、单像素、超宽、超高、噪声、真实字形。"""
    cases: list[tuple[str, np.ndarray]] = [
        ("全白", np.full((64, 64), 255, np.uint8)),
        ("全黑", np.zeros((64, 64), np.uint8)),
        ("单像素", np.pad(np.zeros((1, 1), np.uint8), ((31, 32), (31, 32)), constant_values=255)),
        ("1×1", np.zeros((1, 1), np.uint8)),
        ("宽条", np.pad(np.zeros((2, 200), np.uint8), ((20, 20), (0, 0)), constant_values=255)),
        ("高条", np.pad(np.zeros((200, 2), np.uint8), ((0, 0), (20, 20)), constant_values=255)),
        ("阈值边界", np.where(rng.random((80, 90)) < 0.5, 219, 220).astype(np.uint8)),
    ]
    for i in range(count):
        h, w = rng.integers(8, 220, size=2)
        kind = i % 3
        if kind == 0:
            img = rng.integers(0, 256, (h, w), dtype=np.uint8)
        elif kind == 1:
            img = np.full((h, w), 255, np.uint8)
            yy, xx = np.mgrid[0:h, 0:w]
            mask = (xx - w / 2) ** 2 / max(1, (w / 3) ** 2) + (yy - h / 2) ** 2 / max(1, (h / 3) ** 2) < 1
            img[mask] = rng.integers(0, 90)
        else:
            img = np.full((h, w), 255, np.uint8)
            for _ in range(int(rng.integers(1, 5))):
                x0, x1 = sorted(rng.integers(0, w, 2))
                y0, y1 = sorted(rng.integers(0, h, 2))
                img[y0:y1 + 1, x0:x1 + 1] = int(rng.integers(0, 200))
        cases.append((f"随机{i}", img))

    fonts = sorted(FONT_DIR.glob("*.ttf"))
    if fonts:
        font = ImageFont.truetype(str(fonts[0]), 96)
        for ch in "永和九年的春天我写字":
            img = Image.new("L", (140, 140), 255)
            ImageDraw.Draw(img).text((12, 12), ch, font=font, fill=0)
            cases.append((f"字形{ch}", np.asarray(img)))
    return cases


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--images", type=int, default=40, help="随机用例数量")
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--tol", type=int, default=2, help="允许的单像素最大差（float 求和顺序导致）")
    parser.add_argument("--max-mean", type=float, default=0.02, help="允许的平均像素差（0-255 口径）")
    args = parser.parse_args()

    run_c = load_c(build_dll())
    rng = np.random.default_rng(args.seed)
    cases = synthetic_cases(rng, args.images)

    worst = 0
    worst_name = ""
    failures = 0
    total_px = 0
    total_diff_px = 0
    for name, img in cases:
        ok_c, out_c = run_c(img)
        out_py = prep.preprocess(img)
        ok_py = int(out_py.max() > 0)

        if ok_c != ok_py:
            failures += 1
            print(f"  ✗ {name}: 返回值不一致 C={ok_c} Py={ok_py}")
            continue

        diff = np.abs(out_c - out_py) * 255.0
        peak = int(np.ceil(diff.max()))
        mean = float(diff.mean())
        diff_px = int((diff >= 0.5).sum())
        total_px += diff.size
        total_diff_px += diff_px
        if peak > worst:
            worst, worst_name = peak, name
        # 只有个别像素因浮点求和顺序落在 .5 边界上翻一格，属于可接受范围
        if peak > args.tol or mean > args.max_mean:
            failures += 1
            print(f"  ✗ {name}: 最大差 {peak}/255，平均 {mean:.4f}，差 {diff_px} 像素")

    print(f"\n用例 {len(cases)} 个，失败 {failures} 个")
    print(f"最大像素差 {worst}/255（{worst_name or '-'}），容差 {args.tol}/255；"
          f"不一致像素 {total_diff_px}/{total_px} = {total_diff_px / max(1, total_px):.6%}")
    if failures:
        return 1
    print("✓ Python 预处理与 C 实现一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
