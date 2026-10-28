#!/usr/bin/env python3
"""用 OFL 字体渲染字形 → 增强 → 过与 Android 一致的预处理 → 64×64 数据集。

输出直接是模型输入口径（`preprocess.c` 的结果），训练脚本不需要再做任何图像处理，
这样「训练侧和真机侧同图同结果」不是靠约定，而是靠同一条代码路径。

用法::

    python tools/handwriting/render-dataset.py --limit 500 --per-style 3
    python tools/handwriting/render-dataset.py            # 全量 3500 类

产物（默认 tools/handwriting/.cache/dataset/v1）::

    charset.json  meta.json  train.npz  val.npz  test.npz  preview.png
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import augment as aug  # noqa: E402
import preprocess_ref as prep  # noqa: E402

CACHE = HERE / ".cache"
FONT_DIR = CACHE / "fonts"

_FONTS: dict[tuple[str, int, int], ImageFont.FreeTypeFont] = {}
_CFG: dict = {}


def _font(path: str, size: int, weight: int) -> ImageFont.FreeTypeFont:
    key = (path, size, weight)
    if key not in _FONTS:
        font = ImageFont.truetype(path, size)
        if weight:
            try:
                font.set_variation_by_axes([weight])
            except Exception:
                pass  # 静态字体没有可变轴，忽略即可
        _FONTS[key] = font
    return _FONTS[key]


def render_glyph(path: str, ch: str, size: int, weight: int, canvas: int) -> np.ndarray:
    """在白色画布上居中绘制单字，返回 uint8 灰度（255=白，0=笔画）。"""
    font = _font(path, size, weight)
    img = Image.new("L", (canvas, canvas), 255)
    draw = ImageDraw.Draw(img)
    left, top, right, bottom = draw.textbbox((0, 0), ch, font=font)
    x = (canvas - (right - left)) / 2 - left
    y = (canvas - (bottom - top)) / 2 - top
    draw.text((x, y), ch, font=font, fill=0)
    return np.asarray(img)


def _task(job: tuple) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """一个批次 = 一种字形风格 × 一段字表。返回 (train_x, train_y, val_x, val_y)。

    cfg 随任务传进来而不是用模块全局：Windows 用 spawn 启动子进程，父进程里
    赋值的全局变量不会带过去，这是最容易踩的坑。
    """
    cfg, style_idx, path, weight, pairs, per_style, per_val = job
    tr_x, tr_y, va_x, va_y = [], [], [], []
    for char_idx, ch in pairs:
        for k in range(per_style):
            # 种子只依赖 (style, char, k)，与并行切分方式无关，重跑结果稳定
            seed = (cfg["seed"] * 1_000_003 + style_idx * 10_007 + char_idx * 131 + k) % (2**32)
            rng = np.random.default_rng(seed)
            gray = render_glyph(path, ch, cfg["font_size"], weight, cfg["canvas"])
            gray = aug.augment(gray, rng, level=cfg["aug_level"])
            sample = prep.to_uint8(prep.preprocess(gray))
            if k < per_val:
                va_x.append(sample); va_y.append(char_idx)
            else:
                tr_x.append(sample); tr_y.append(char_idx)

    def pack(xs, ys, shape):
        if not xs:
            return np.zeros(shape, np.uint8), np.zeros((0,), np.int16)
        return np.stack(xs), np.asarray(ys, dtype=np.int16)

    return (*pack(tr_x, tr_y, (0, prep.CANVAS_SIZE, prep.CANVAS_SIZE)),
            *pack(va_x, va_y, (0, prep.CANVAS_SIZE, prep.CANVAS_SIZE)))


def _codepoints(path: str) -> set[int]:
    """字体真实包含的码位。缺字时 PIL 会画 .notdef 方框，等于往训练集里塞噪声。"""
    from fontTools.ttLib import TTFont

    font = TTFont(path, lazy=True, fontNumber=0)
    try:
        return set((font.getBestCmap() or {}).keys())
    finally:
        font.close()


def _styles(fonts: dict, holdout: set[str], weights: set[int] | None = None) -> list[tuple[str, str, int]]:
    result = []
    for font in fonts:
        path = FONT_DIR / font["file"]
        if not path.exists():
            raise SystemExit(f"缺字体文件 {path}，先运行 tools/handwriting/fetch-fonts.py")
        available = [int(w) for w in font.get("weights", [400])]
        if weights:
            picked = [w for w in available if w in weights] or [available[len(available) // 2]]
        else:
            picked = available
        for weight in picked:
            result.append((font["id"], str(path), weight))
    return result


def _preview(samples: list[tuple[str, str, np.ndarray]], out: Path, cols: int = 12) -> None:
    cell = prep.CANVAS_SIZE * 2
    rows = (len(samples) + cols - 1) // cols
    sheet = Image.new("L", (cols * cell, rows * cell), 235)
    for i, (_, _, img) in enumerate(samples):
        tile = Image.fromarray(((1.0 - img) * 255.0).astype(np.uint8), mode="L")
        tile = tile.resize((cell - 4, cell - 4), Image.NEAREST)
        sheet.paste(tile, ((i % cols) * cell + 2, (i // cols) * cell + 2))
    sheet.save(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--charset", type=Path, default=CACHE / "charset.json")
    parser.add_argument("--limit", type=int, default=0, help="只取字表前 N 类（0=全部），用于快速验证")
    parser.add_argument("--per-style", type=int, default=4, help="每个字形风格生成几张（增强变体）")
    parser.add_argument("--val-per-style", type=int, default=1, help="其中划给验证集的数量")
    parser.add_argument("--val-cap", type=int, default=0,
                        help="验证集最多保留多少张（0=不限）；大类别时按比例切会切出几十万张")
    parser.add_argument("--test-per-style", type=int, default=3, help="留出风格每个字生成几张")
    parser.add_argument("--holdout", default="longcang", help="留作测试集的字体 id，逗号分隔")
    parser.add_argument("--weights", default="400",
                        help="可变字体取哪些字重，逗号分隔；字表很大时用单一字重控制数据量")
    parser.add_argument("--canvas", type=int, default=160)
    parser.add_argument("--font-size", type=int, default=120)
    parser.add_argument("--aug-level", type=float, default=1.0)
    parser.add_argument("--seed", type=int, default=20261023)
    parser.add_argument("--workers", type=int, default=0, help="0=CPU 核数")
    parser.add_argument("--out", type=Path, default=CACHE / "dataset" / "v1")
    args = parser.parse_args()

    charset = json.loads(args.charset.read_text(encoding="utf-8"))
    classes = charset["classes"]
    if args.limit:
        classes = classes[: args.limit]
    fonts = json.loads((HERE / "fonts.json").read_text(encoding="utf-8"))["fonts"]
    holdout = {x.strip() for x in args.holdout.split(",") if x.strip()}

    global _CFG
    cfg = {"seed": args.seed, "aug_level": args.aug_level, "canvas": args.canvas,
           "font_size": args.font_size}
    _CFG = cfg

    styles = _styles(fonts, holdout,
                     {int(w) for w in args.weights.split(",") if w.strip()} if args.weights else None)
    unknown = holdout - {s[0] for s in styles}
    if unknown:
        raise SystemExit(f"--holdout 里有未知字体：{sorted(unknown)}")

    import os
    workers = args.workers or (os.cpu_count() or 4)
    started = time.time()
    train_x, train_y, val_x, val_y, test_x, test_y = [], [], [], [], [], []

    print(f"类别 {len(classes)}，风格 {len(styles)}，留出字体 {sorted(holdout) or '无'}，"
          f"进程 {workers}")
    jobs = []
    uncovered: set[int] = set(range(len(classes)))
    for style_idx, (fid, path, weight) in enumerate(styles):
        codes = _codepoints(path)
        pairs = [(i, ch) for i, ch in enumerate(classes) if ord(ch) in codes]
        uncovered -= {i for i, _ in pairs}
        if len(pairs) < len(classes):
            print(f"  {fid:14s} 缺 {len(classes) - len(pairs)} 字，按实际覆盖渲染")
        is_test = fid in holdout
        per = args.test_per_style if is_test else args.per_style
        per_val = 0 if is_test else min(args.val_per_style, args.per_style - 1)
        for start in range(0, len(pairs), 200):
            jobs.append((cfg, style_idx, path, weight, pairs[start:start + 200], per, per_val))

    if uncovered:
        sample = "".join(classes[i] for i in sorted(uncovered)[:20])
        print(f"! {len(uncovered)} 个字没有任何字体覆盖，将没有训练样本：{sample}…")

    with ProcessPoolExecutor(max_workers=workers) as pool:
        for i, (tr_x, tr_y, va_x, va_y) in enumerate(pool.map(_task, jobs)):
            fid = styles[jobs[i][1]][0]
            if fid in holdout:
                test_x.append(tr_x); test_y.append(tr_y)
            else:
                train_x.append(tr_x); train_y.append(tr_y)
                val_x.append(va_x); val_y.append(va_y)
            if i % 10 == 0:
                print(f"  {i + 1}/{len(jobs)} 批次  {time.time() - started:.0f}s", flush=True)

    def stack(xs, ys):
        if not xs:
            return np.zeros((0, 64, 64), np.uint8), np.zeros((0,), np.int16)
        return np.concatenate(xs), np.concatenate(ys)

    tx, ty = stack(train_x, train_y)
    vx, vy = stack(val_x, val_y)
    sx, sy = stack(test_x, test_y)

    if args.val_cap and len(vx) > args.val_cap:
        keep = np.random.default_rng(args.seed).choice(len(vx), args.val_cap, replace=False)
        keep.sort()
        vx, vy = vx[keep], vy[keep]

    args.out.mkdir(parents=True, exist_ok=True)
    for name, x, y in (("train", tx, ty), ("val", vx, vy), ("test", sx, sy)):
        np.savez_compressed(args.out / f"{name}.npz", x=x, y=y)
        print(f"  {name:5s} {x.shape[0]:7d} 张")

    (args.out / "charset.json").write_text(json.dumps(
        {**charset, "num_classes": len(classes), "classes": classes}, ensure_ascii=False) + "\n",
        encoding="utf-8")
    meta = {
        "created": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "charset_sha256": hashlib.sha256("".join(classes).encode()).hexdigest(),
        "num_classes": len(classes),
        "styles": [{"font": f, "weight": w} for f, _, w in styles],
        "holdout_fonts": sorted(holdout),
        "per_style": args.per_style,
        "test_per_style": args.test_per_style,
        "val_cap": args.val_cap,
        "aug_level": args.aug_level,
        "seed": args.seed,
        "preprocess": {"canvas": prep.CANVAS_SIZE, "content": prep.CONTENT_SIZE,
                       "fg_threshold": prep.FG_THRESHOLD},
        "counts": {"train": int(tx.shape[0]), "val": int(vx.shape[0]), "test": int(sx.shape[0])},
    }
    (args.out / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n",
                                        encoding="utf-8")

    picks = []
    for style_idx, (fid, path, weight) in enumerate(styles[:6]):
        for ch in classes[:2]:
            rng = np.random.default_rng(style_idx + 7)
            gray = aug.augment(render_glyph(path, ch, args.font_size, weight, args.canvas),
                               rng, level=args.aug_level)
            picks.append((fid, ch, prep.preprocess(gray)))
    _preview(picks, args.out / "preview.png")

    print(f"\n完成：{args.out}（{time.time() - started:.0f}s）")
    print(f"预览图：{args.out / 'preview.png'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
