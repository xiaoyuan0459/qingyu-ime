#!/usr/bin/env python3
"""把训练好的模型资产化：校验 ONNX、转 NCNN、写 model-manifest.json。

真机上「模型对了但字表错了」只会表现为候选乱码，不会崩溃，所以这里把
类别数、字表哈希、输入口径和文件 SHA-256 一起写进 manifest，由 Android 侧启动时强校验。

用法::

    python tools/handwriting/export-model.py --model .cache/models/v1
    python tools/handwriting/export-model.py --model .cache/models/v1 --ncnn

依赖：校验 ONNX 需要 onnxruntime，转 NCNN 需要 pnnx（都只在构建期用，不进 APK）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check_onnx(model_dir: Path, data_dir: Path) -> dict:
    """用 onnxruntime 复算测试集，确认导出没有改掉任何数值。"""
    try:
        import onnxruntime as ort
        import torch
    except ImportError as exc:
        print(f"  跳过 ONNX 校验：{exc}")
        return {"checked": False}

    sys.path.insert(0, str(HERE))
    from train import HandwritingNet  # noqa: E402

    ckpt = torch.load(model_dir / "model.pt", map_location="cpu")
    model = HandwritingNet(ckpt["num_classes"], ckpt["width"])
    model.load_state_dict(ckpt["state_dict"])
    model.eval()

    data = np.load(data_dir / "test.npz")
    x, y = data["x"][:256], data["y"][:256].astype(np.int64)
    if len(x) == 0:
        return {"checked": False, "reason": "测试集为空"}

    session = ort.InferenceSession(str(model_dir / "model.onnx"),
                                   providers=["CPUExecutionProvider"])
    onnx_logits = session.run(None, {"input": (x.astype(np.float32) / 255.0)[:, None]})[0]

    with torch.no_grad():
        torch_logits = model(torch.from_numpy(x).float().unsqueeze(1) / 255.0).numpy()

    max_diff = float(np.abs(onnx_logits - torch_logits).max())
    agree = float((onnx_logits.argmax(1) == torch_logits.argmax(1)).mean())
    acc = float((onnx_logits.argmax(1) == y).mean())
    print(f"  ONNX 校验：最大 logit 差 {max_diff:.2e}，Top-1 一致率 {agree:.4f}，"
          f"该子集准确率 {acc:.3f}")
    return {"checked": True, "max_logit_diff": max_diff, "top1_agreement": agree,
            "subset_accuracy": acc, "subset_size": int(len(x))}


def convert_ncnn(model_dir: Path) -> dict:
    # pnnx 作为 console script 装在 venv 的 Scripts/ 下，不一定会进 PATH
    candidates = [shutil.which("pnnx"),
                  str(Path(sys.executable).with_name("pnnx.exe")),
                  str(Path(sys.executable).with_name("pnnx"))]
    exe = next((c for c in candidates if c and Path(c).exists()), None)
    if exe is None:
        print("  跳过 NCNN 转换：找不到 pnnx（pip install pnnx）")
        return {"converted": False, "reason": "pnnx 未安装"}
    # pnnx 的工作目录就是 model_dir，必须传绝对路径，否则相对路径会被解析两次
    cmd = [exe, str((model_dir / "model.onnx").resolve()), "inputshape=[1,1,64,64]"]
    print("  执行:", " ".join(cmd))
    result = subprocess.run(cmd, cwd=model_dir, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  pnnx 失败：{result.stderr[-800:]}")
        return {"converted": False, "reason": "pnnx 返回非零"}
    param = model_dir / "model.ncnn.param"
    binary = model_dir / "model.ncnn.bin"
    if not (param.exists() and binary.exists()):
        return {"converted": False, "reason": "pnnx 未产出预期文件"}
    print(f"  NCNN 产物：{param.name} {param.stat().st_size:,} B / "
          f"{binary.name} {binary.stat().st_size:,} B")
    return {"converted": True, "param_sha256": sha256(param), "bin_sha256": sha256(binary),
            "bin_bytes": binary.stat().st_size}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=HERE / ".cache" / "models" / "v1")
    parser.add_argument("--data", type=Path, default=HERE / ".cache" / "dataset" / "v1")
    parser.add_argument("--ncnn", action="store_true", help="同时转 NCNN")
    parser.add_argument("--version", default="qingyu-hw-image-v1")
    args = parser.parse_args()

    charset_path = args.model / "charset.json"
    charset = json.loads(charset_path.read_text(encoding="utf-8"))
    report_path = args.model / "train-report.json"
    report = json.loads(report_path.read_text(encoding="utf-8")) if report_path.exists() else {}

    print("校验 ONNX …")
    onnx_check = check_onnx(args.model, args.data)

    ncnn = {"converted": False}
    if args.ncnn:
        print("转换 NCNN …")
        ncnn = convert_ncnn(args.model)

    files = {}
    for name in ("model.pt", "model.onnx", "charset.json", "model.ncnn.param", "model.ncnn.bin"):
        path = args.model / name
        if path.exists():
            files[name] = {"bytes": path.stat().st_size, "sha256": sha256(path)}

    manifest = {
        "version": args.version,
        "created": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "num_classes": charset["num_classes"],
        "charset_sha256": charset.get("classes_sha256"),
        "charset_source": charset.get("source"),
        "input": {"name": "input", "shape": [1, 1, 64, 64], "dtype": "float32",
                  "scale": "pixel/255.0，笔画≈1，背景≈0（与 preprocess.c 一致）"},
        "output": {"name": "logits", "shape": [1, charset["num_classes"]]},
        "training": {"data": report.get("data"), "num_classes": report.get("num_classes"),
                     "params": report.get("params"), "val": report.get("val"),
                     "test_unseen_style": report.get("test_unseen_style")},
        "onnx_check": onnx_check,
        "ncnn": ncnn,
        "files": files,
        "license_note": "训练语料仅含 OFL-1.1 字体渲染图与（后续）自有采集手写样本，"
                        "不含 CASIA/HWDB 派生数据；字体本身不随包分发。",
    }
    out = args.model / "model-manifest.json"
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n已写入 {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
