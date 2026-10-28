#!/usr/bin/env python3
"""字形图像增强。

字体渲染出的字形是「印刷级干净」的，与真机上手写笔迹的差距主要在四件事：
位置/角度抖动、笔画粗细与断连、结构形变、以及笔迹灰度不均。这里逐个补上，
所有算子都作用在**预处理之前**的大画布上，让 preprocess_ref 负责最后的归一化，
避免增强与预处理互相污染。

随机源统一用 numpy Generator，种子写进 meta.json，保证可复现。
"""

from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw, ImageFilter


def affine(img: np.ndarray, rng: np.random.Generator, *, max_angle: float = 7.0,
           max_shear: float = 0.12, scale_range: tuple[float, float] = (0.88, 1.12),
           max_shift: float = 0.05) -> np.ndarray:
    """旋转 + 切变 + 缩放 + 平移，一次重采样完成（PIL 的仿射用双线性）。"""
    h, w = img.shape
    angle = rng.uniform(-max_angle, max_angle)
    shear = rng.uniform(-max_shear, max_shear)
    scale = rng.uniform(*scale_range)
    tx = rng.uniform(-max_shift, max_shift) * w
    ty = rng.uniform(-max_shift, max_shift) * h

    # 正向映射：先缩放，再切变(x += shear*y)，再旋转
    rad = np.deg2rad(angle)
    cos_a, sin_a = np.cos(rad), np.sin(rad)
    m = np.array([[scale * cos_a, scale * (shear * cos_a - sin_a)],
                  [scale * sin_a, scale * (shear * sin_a + cos_a)]], dtype=np.float64)
    # PIL 需要逆变换：输出坐标 → 输入坐标
    inv = np.linalg.inv(m)
    cx, cy = w / 2.0, h / 2.0
    offset = np.array([cx, cy]) - inv @ np.array([cx - tx, cy - ty])

    pil = Image.fromarray(img, mode="L")
    out = pil.transform(
        (w, h), Image.AFFINE,
        (inv[0, 0], inv[0, 1], offset[0], inv[1, 0], inv[1, 1], offset[1]),
        resample=Image.BILINEAR, fillcolor=255,
    )
    return np.asarray(out)


def _upsample(coarse: np.ndarray, size: tuple[int, int]) -> np.ndarray:
    h, w = size
    return np.asarray(Image.fromarray(coarse.astype(np.float32), mode="F").resize((w, h), Image.BILINEAR))


def elastic(img: np.ndarray, rng: np.random.Generator, *, alpha: float = 2.5, grid: int = 4) -> np.ndarray:
    """低分辨率随机位移场 + 双线性重采样，模拟同一字的不同写法结构差异。"""
    h, w = img.shape
    dx = _upsample(rng.uniform(-1.0, 1.0, (grid + 1, grid + 1)), (h, w)) * alpha
    dy = _upsample(rng.uniform(-1.0, 1.0, (grid + 1, grid + 1)), (h, w)) * alpha

    yy, xx = np.meshgrid(np.arange(h, dtype=np.float32), np.arange(w, dtype=np.float32), indexing="ij")
    sx = np.clip(xx + dx, 0, w - 1)
    sy = np.clip(yy + dy, 0, h - 1)
    x0 = np.floor(sx).astype(np.int32)
    y0 = np.floor(sy).astype(np.int32)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    fx = (sx - x0).astype(np.float32)
    fy = (sy - y0).astype(np.float32)

    src = img.astype(np.float32)
    top = src[y0, x0] * (1 - fx) + src[y0, x1] * fx
    bottom = src[y1, x0] * (1 - fx) + src[y1, x1] * fx
    return np.clip(top * (1 - fy) + bottom * fy, 0, 255).astype(np.uint8)


def stroke_width(img: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """整体加粗/变细一档，覆盖不同笔压与笔尖粗细。"""
    choice = rng.integers(0, 3)
    if choice == 0:
        return img
    pil = Image.fromarray(img, mode="L")
    filt = ImageFilter.MaxFilter(3) if choice == 1 else ImageFilter.MinFilter(3)
    return np.asarray(pil.filter(filt))


def dropout(img: np.ndarray, rng: np.random.Generator, *, max_blobs: int = 3) -> np.ndarray:
    """擦掉小片笔画，模拟断笔、连笔和采样丢失。"""
    h, w = img.shape
    out = Image.fromarray(img, mode="L")
    draw = ImageDraw.Draw(out)
    for _ in range(int(rng.integers(0, max_blobs + 1))):
        bw = int(rng.integers(3, max(4, h // 12)))
        bh = int(rng.integers(3, max(4, h // 12)))
        cx = int(rng.integers(0, w))
        cy = int(rng.integers(0, h))
        draw.rectangle([cx - bw // 2, cy - bh // 2, cx + bw // 2, cy + bh // 2], fill=255)
    return np.asarray(out)


def tone(img: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """轻微模糊 + 墨水浓度抖动，避免模型只认纯黑 0 值。

    只调**笔画**的深浅，不能整图乘增益：`preprocess.c` 用 `gray < 220` 判定前景，
    背景一旦被压到 191 就整张图都算前景，bbox 变成全画布，字形被缩到 56px 塞进灰底，
    而真机推理时背景恒为纯白——训练与推理口径就此分叉。
    """
    pil = Image.fromarray(img, mode="L")
    if rng.random() < 0.5:
        pil = pil.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(0.2, 0.8))))
    arr = np.asarray(pil).astype(np.float32)
    ink = 255.0 - arr            # 笔画浓度
    gain = rng.uniform(0.75, 1.0)  # <1 表示墨更淡
    return np.clip(255.0 - ink * gain, 0, 255).astype(np.uint8)


def ink_bleed(img: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """墨水洇染：笔画略微变粗 + 边缘羽化，模拟笔尖出墨量与纸张吸墨。"""
    pil = Image.fromarray(img, mode="L")
    pil = pil.filter(ImageFilter.MaxFilter(3))
    pil = pil.filter(ImageFilter.GaussianBlur(radius=float(rng.uniform(0.4, 0.9))))
    arr = np.asarray(pil).astype(np.float32)
    return np.clip(arr, 0, 255).astype(np.uint8)


def pressure(img: np.ndarray, rng: np.random.Generator, strength: float = 0.35) -> np.ndarray:
    """笔压不均：用低频随机场调制笔画深浅，同一笔里有的地方重有的地方轻。"""
    h, w = img.shape
    field = _upsample(rng.uniform(-1.0, 1.0, (3, 3)), (h, w)).astype(np.float32)
    field = np.clip(1.0 + field * strength, 0.45, 1.35)
    stroke = 255.0 - img.astype(np.float32)
    return np.clip(255.0 - stroke * field, 0, 255).astype(np.uint8)


def stroke_shift(img: np.ndarray, rng: np.random.Generator, *,
                 max_shift: float = 3.0, max_rot: float = 8.0) -> np.ndarray:
    """连通域拆分 + 每个笔画独立旋转平移。

    思路参考 MIT 许可的 bamboostrip/Handwriting-sim-rs（`crates/core/src/core/perturb.rs`：
    4-连通标记 + 每笔画刚体变换），此处按本项目的 uint8 灰度口径重写。

    为什么需要它：字体渲染出的字是一个整体，而真人写字的每一笔落笔位置、倾斜角度
    都有独立偏差，笔画之间会错位、部件会松散。全局弹性形变做不到——它用平滑位移场，
    相邻笔画只会一起弯；这里是把每个连通域当刚体单独挪。

    连通域用 4-连通（汉字笔画多为斜向相连，8-连通会把相邻笔画粘成一块）。
    """
    from scipy import ndimage  # 延迟导入：只有启用该算子时才需要 scipy

    fg = img < 200
    labels, count = ndimage.label(fg)  # 默认 4-连通结构
    if count < 2:  # 单笔画字（一、丨）没有可拆的部件
        return img

    h, w = img.shape
    out = img.copy()
    for idx, sl in enumerate(ndimage.find_objects(labels), start=1):
        if sl is None:
            continue
        mask = labels[sl] == idx
        if int(mask.sum()) < 4:  # 噪点跳过
            continue
        y0, y1 = sl[0].start, sl[0].stop
        x0, x1 = sl[1].start, sl[1].stop
        patch = np.where(mask, img[sl], 255).astype(np.uint8)
        out[sl][mask] = 255  # 先擦掉原笔画，避免与贴回结果重影

        angle = float(rng.normal(0.0, max_rot / 2.5))
        dx = float(rng.normal(0.0, max_shift / 2.5))
        dy = float(rng.normal(0.0, max_shift / 2.5))
        rotated = Image.fromarray(patch, mode="L").rotate(
            angle, resample=Image.BILINEAR, expand=True, fillcolor=255)
        rw, rh = rotated.size
        cx = (x0 + x1) / 2.0 + dx
        cy = (y0 + y1) / 2.0 + dy
        tx, ty = int(round(cx - rw / 2.0)), int(round(cy - rh / 2.0))

        sy0, sx0 = max(0, ty), max(0, tx)
        sy1, sx1 = min(h, ty + rh), min(w, tx + rw)
        if sy1 <= sy0 or sx1 <= sx0:
            continue
        src = np.asarray(rotated)[sy0 - ty:sy1 - ty, sx0 - tx:sx1 - tx]
        dst = out[sy0:sy1, sx0:sx1]
        np.minimum(dst, src, out=dst)  # 白底黑笔画，取更暗者即为覆盖

    return out


def augment(img: np.ndarray, rng: np.random.Generator, level: float = 1.0) -> np.ndarray:
    """按固定顺序组合算子；level=0 时退化为恒等（用于产出干净基线样本）。"""
    if level <= 0:
        return img
    out = affine(img, rng, max_angle=7.0 * level, max_shear=0.12 * level,
                 max_shift=0.05 * level)
    if rng.random() < 0.75:
        out = elastic(out, rng, alpha=2.5 * level)
    if rng.random() < 0.65:
        # 笔画位移强度设上限：整体 level 再大也不能把部件拆散到不可辨
        strength = min(level, 1.1)
        out = stroke_shift(out, rng, max_shift=3.0 * strength, max_rot=8.0 * strength)
    out = stroke_width(out, rng)
    if rng.random() < 0.35:
        out = dropout(out, rng)
    if rng.random() < 0.5:
        out = ink_bleed(out, rng)
    if rng.random() < 0.6:
        out = pressure(out, rng, strength=0.35 * level)
    return tone(out, rng)
