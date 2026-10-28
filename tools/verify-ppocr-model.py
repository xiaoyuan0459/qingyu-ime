"""用官方 ONNX 对比验证：模型能否识别真实汉字图像。

不依赖 ncnn（Windows 上不便编译），用 onnxruntime 直接跑官方 ONNX，
确认字表映射与输出维度正确 —— 这是接入前的必要验证。

用法: python verify-ppocr-model.py <图片路径> [更多图片...]
"""

from __future__ import annotations

import sys
import urllib.request
from pathlib import Path

import numpy as np

ONNX_URL = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx"
YML_URL = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml"

# 官方文档给的 rec 输入规格：高 48，宽可变，通道 RGB，均值方差按 PaddleOCR 惯例
REC_H, REC_W = 48, 320


def fetch(url: str, dest: Path) -> Path:
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"  下载 {dest.name}")
    with urllib.request.urlopen(url) as r, dest.open("wb") as f:
        f.write(r.read())
    return dest


def load_dict(yml: Path) -> list[str]:
    lines = yml.read_text(encoding="utf-8", errors="replace").splitlines()
    key = next((i for i, l in enumerate(lines) if l.strip() == "character_dict:"), -1)
    if key < 0:
        return []
    out = []
    for line in lines[key + 1:]:
        s = line.strip(" \t")
        if not s or s.startswith("#"):
            continue
        if not s.startswith("- "):
            break
        v = s[2:].strip(" \t")
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
            v = v[1:-1]
        out.append(v)
    return out


def ctc_greedy(logits: np.ndarray, dict_list: list[str]) -> list[tuple[str, float]]:
    """CTC 贪心解码：每帧取argmax，折叠连续重复，去除 blank。

    通道布局经实测确认（PaddleOCR rec）：
        输出维度 = 字表长度 + blank + 1 个保留通道
        id 0        = blank
        id 1..N     = 字表第 0..N-1 项
        id N+1      = 保留通道
    实测依据：字表首项为 '!'（id 1），但空白帧 argmax 落在 id 0，
    故 blank 是独立的首通道，不能按"末尾为 blank"的常见假设处理。
    """
    idx = logits.argmax(axis=-1)[0]
    conf = logits.max(axis=-1)[0]

    n_dict = len(dict_list)
    blank_id = 0
    offset = 1  # 字表整体后移 1 位

    chars: list[str] = []
    scores: list[float] = []
    prev = -1
    for t, cid in enumerate(idx):
        cid = int(cid)
        if cid != prev and cid != blank_id:
            pos = cid - offset
            if 0 <= pos < n_dict:
                chars.append(dict_list[pos])
                scores.append(float(conf[t]))
        prev = cid
    return list(zip(chars, scores))


def preprocess(img: Path) -> np.ndarray:
    from PIL import Image

    im = Image.open(img).convert("RGB")
    w, h = im.size
    ratio = w / float(h)
    if ratio >= 1:  # 横图，宽按比例，限制最大宽
        tw = min(int(round(REC_H * ratio)), 2000)
        th = REC_H
    else:  # 竖图/方图
        th = int(REC_H / ratio)
        tw = REC_H

    im = im.resize((tw, th), Image.BILINEAR)
    a = np.asarray(im).astype("float32") / 255.0
    a = (a - 0.5) / 0.5
    return a.transpose(2, 0, 1)[None, ...]


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1

    cache = Path.home() / ".cache" / "ppocrv6_verify"
    onnx_path = fetch(ONNX_URL, cache / "inference.onnx")
    yml_path = fetch(YML_URL, cache / "inference.yml")

    import onnxruntime as ort

    dict_list = load_dict(yml_path)
    print(f"字表条目：{len(dict_list)}")
    print(f"其中汉字：{sum(1 for c in dict_list if len(c) == 1 and 0x4E00 <= ord(c) <= 0x9FFF)}")

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    print(f"输入：{inp.name} shape={inp.shape}")
    print(f"输出：{[o.name for o in sess.get_outputs()]}")
    print()

    for arg in sys.argv[1:]:
        p = Path(arg)
        if not p.exists():
            print(f"跳过（不存在）：{p}")
            continue
        blob = preprocess(p)
        outs = sess.run(None, {inp.name: blob})
        logits = np.asarray(outs[0])
        res = ctc_greedy(logits, dict_list)
        print(f"{p.name}: shape={blob.shape} →输出 {logits.shape}")
        if res:
            print("  识别:", "".join(c for c, _ in res), f"(置信度 {min(s for _, s in res):.3f}~{max(s for _, s in res):.3f})")
        else:
            print("  识别: <空>")
    return 0


if __name__ == "__main__":
    sys.exit(main())