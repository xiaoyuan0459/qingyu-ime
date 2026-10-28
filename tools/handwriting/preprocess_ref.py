#!/usr/bin/env python3
"""`imesdk/src/main/cpp/preprocess.c` 的逐行 Python 镜像。

训练侧和 Android 侧必须走同一套预处理，否则模型在真机上会静默降精度。
这里不调用 PIL 的 resize，而是把 C 里的 separable 三角核重采样按同一顺序
（先横后纵、中间结果量化到 uint8）重写一遍，保证语义一致。

与 C 的唯一差异是累加精度：C 用 float32，这里用 float64。因此像素值允许
有 ±1 的差（见 `verify-preprocess.py` 的一致性测试）。
"""

from __future__ import annotations

import numpy as np

CANVAS_SIZE = 64
CONTENT_SIZE = 56
FG_THRESHOLD = 220


def _axis_weights(in_dim: int, out_dim: int) -> np.ndarray:
    """生成 out_dim × in_dim 的三角核权重矩阵，等价 C 的预算 (xmin, xmax, weights)。"""
    scale = in_dim / out_dim
    filterscale = scale if scale > 1.0 else 1.0
    inv_filterscale = 1.0 / filterscale

    w = np.zeros((out_dim, in_dim), dtype=np.float32)
    for out_idx in range(out_dim):
        center = (out_idx + 0.5) * scale
        lo = max(0, int(center - filterscale + 0.5))
        hi = min(in_dim, int(center + filterscale + 0.5))
        if hi <= lo:
            hi = min(in_dim, lo + 1)
        taps = np.empty(hi - lo, dtype=np.float32)
        for i in range(lo, hi):
            d = ((i + 0.5) - center) * inv_filterscale
            taps[i - lo] = max(0.0, 1.0 - abs(d))
        total = taps.sum()
        if total > 0:
            taps /= total
        w[out_idx, lo:hi] = taps
    return w


def _quantize(arr: np.ndarray) -> np.ndarray:
    """C 的 iround_f + clamp：正数上取整到最近整数，再夹到 0..255。"""
    return np.clip(np.floor(arr + 0.5), 0, 255).astype(np.uint8)


def resample(src: np.ndarray, dst_w: int, dst_h: int) -> np.ndarray:
    """两趟 separable 重采样，中间结果按 C 的做法量化到 uint8。"""
    src_h, src_w = src.shape
    if (src_w, src_h) == (dst_w, dst_h):
        return src

    horizontal = _axis_weights(src_w, dst_w)
    tmp = _quantize(src.astype(np.float32) @ horizontal.T)

    vertical = _axis_weights(src_h, dst_h)
    return _quantize(vertical @ tmp.astype(np.float32))


def preprocess(gray: np.ndarray) -> np.ndarray:
    """(H, W) uint8 灰度图（255=白底）→ (64, 64) float32，笔画≈1.0，背景≈0.0。

    返回全零表示没有前景（对应 C 返回 0）。
    """
    if gray.ndim != 2:
        raise ValueError(f"需要二维灰度图，收到 shape={gray.shape}")
    if gray.dtype != np.uint8:
        gray = np.clip(gray, 0, 255).astype(np.uint8)

    mask = gray < FG_THRESHOLD
    if not mask.any():
        return np.zeros((CANVAS_SIZE, CANVAS_SIZE), dtype=np.float32)

    rows = np.flatnonzero(mask.any(axis=1))
    cols = np.flatnonzero(mask.any(axis=0))
    y0, y1 = int(rows[0]), int(rows[-1])
    x0, x1 = int(cols[0]), int(cols[-1])
    crop = gray[y0:y1 + 1, x0:x1 + 1]

    crop_h, crop_w = crop.shape
    scale = CONTENT_SIZE / max(crop_w, crop_h)
    new_w = max(1, int(crop_w * scale + 0.5))
    new_h = max(1, int(crop_h * scale + 0.5))

    resized = resample(crop, new_w, new_h)

    canvas = np.full((CANVAS_SIZE, CANVAS_SIZE), 255, dtype=np.uint8)
    off_x = (CANVAS_SIZE - new_w) // 2
    off_y = (CANVAS_SIZE - new_h) // 2
    canvas[off_y:off_y + new_h, off_x:off_x + new_w] = resized

    return ((255.0 - canvas.astype(np.float32)) / 255.0).astype(np.float32)


def to_uint8(x: np.ndarray) -> np.ndarray:
    """float[0,1] → uint8[0,255]，用于落盘（4096 字节/样本，比 float32 省 4 倍）。"""
    return np.clip(np.rint(x * 255.0), 0, 255).astype(np.uint8)


def from_uint8(x: np.ndarray) -> np.ndarray:
    return (x.astype(np.float32) / 255.0)
