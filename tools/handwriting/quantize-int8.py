#!/usr/bin/env python3
"""把 NCNN fp16 模型量化成 int8，并实测精度损失。

流程（全部用 ncnn 官方工具，不自己造量化器）：

    ncnnoptimize  fp16 → fp32（int8 量化的输入要求 fp32）
    ncnn2table    fp32 + 校准图 → 激活值范围表
    ncnn2int8     fp32 + 表 → int8 模型

校准图直接取训练集，**像素口径必须和 `preprocess.c` 一致**：存储的 uint8 就是
「笔画亮、背景黑」，所以 `norm=[1/255]`、`mean=0`、`pixel=GRAY`。

用法::

    python tools/handwriting/quantize-int8.py --model tools/handwriting/.cache/models/v4
    python tools/handwriting/quantize-int8.py --model ... --method aciq --calib 800
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
NCNN_DIR = HERE / ".cache" / "ncnn" / "ncnn-20261020-windows-vs2022" / "x64" / "bin"


def sha256(path: Path) -> str:
    import hashlib

    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd: list[str], cwd: Path | None = None) -> str:
    print("  " + " ".join(Path(str(c)).name if i == 0 else str(c) for i, c in enumerate(cmd)))
    result = subprocess.run([str(c) for c in cmd], cwd=cwd, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"失败：{cmd[0]}\n{result.stdout[-1500:]}\n{result.stderr[-1500:]}")
    return result.stdout


def dump_calibration(data_dir: Path, out_dir: Path, count: int, seed: int = 0) -> Path:
    """从训练集抽 N 张存成 PNG，并写 ncnn2table 需要的列表文件。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    data = np.load(data_dir / "train.npz")
    x, y = data["x"], data["y"]
    idx = np.random.default_rng(seed).choice(len(x), min(count, len(x)), replace=False)
    listing = out_dir / "calib.txt"
    lines = []
    for i, k in enumerate(idx):
        name = f"{i:05d}.png"
        Image.fromarray(x[k], mode="L").save(out_dir / name)
        lines.append(f"{name} {int(y[k])}")
    listing.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return listing


def blob_names(param: Path) -> tuple[str, str]:
    """从 param 里读真实的输入/输出 blob 名。pnnx 导出的名字是 in0/out0，
    不是 ONNX 里的 input/logits，写死会直接报 find_blob_index_by_name failed。"""
    layers = [ln.split() for ln in param.read_text(encoding="utf-8").splitlines()[2:] if ln.strip()]
    input_name = layers[0][4 + int(layers[0][2])]
    last = layers[-1]
    output_name = last[4 + int(last[2])]  # 跳过 bottom blob，才是 top blob
    return input_name, output_name


def evaluate_ncnn(param: Path, binary: Path, data: Path, limit: int, int8: bool) -> dict:
    import ncnn

    in_name, out_name = blob_names(param)
    net = ncnn.Net()
    net.opt.use_vulkan_compute = False
    net.opt.num_threads = 8
    net.opt.use_int8_inference = int8
    net.load_param(str(param))
    net.load_model(str(binary))

    data_npz = np.load(data)
    x, y = data_npz["x"], data_npz["y"].astype(np.int64)
    if limit and len(x) > limit:
        keep = np.random.default_rng(1).choice(len(x), limit, replace=False)
        keep.sort()
        x, y = x[keep], y[keep]

    correct = {1: 0, 5: 0, 10: 0}
    started = time.time()
    for i in range(len(x)):
        mat_in = ncnn.Mat(np.ascontiguousarray(x[i], dtype=np.float32) / 255.0)
        ex = net.create_extractor()
        ex.input(in_name, mat_in)
        _, out = ex.extract(out_name)
        scores = np.array(out).reshape(-1)
        top = np.argpartition(-scores, 10)[:10]
        top = top[np.argsort(-scores[top])]
        for k in correct:
            if y[i] in top[:k]:
                correct[k] += 1
    n = max(1, len(x))
    return {"n": int(len(x)), "seconds": round(time.time() - started, 1),
            **{f"top{k}": round(v / n, 4) for k, v in correct.items()}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=HERE / ".cache" / "models" / "v4")
    parser.add_argument("--data", type=Path, default=HERE / ".cache" / "dataset" / "v4")
    parser.add_argument("--calib", type=int, default=400, help="校准图数量")
    parser.add_argument("--method", default="kl", choices=["kl", "aciq", "eq"])
    parser.add_argument("--eval-limit", type=int, default=8000, help="精度对比用的测试图数量")
    parser.add_argument("--skip-eval", action="store_true")
    args = parser.parse_args()

    for tool in ("ncnnoptimize.exe", "ncnn2table.exe", "ncnn2int8.exe"):
        if not (NCNN_DIR / tool).exists():
            raise SystemExit(f"缺 {tool}：先解压 ncnn Windows 包到 {NCNN_DIR}")

    model = args.model
    fp16_param = model / "model.ncnn.param"
    fp16_bin = model / "model.ncnn.bin"
    if not fp16_param.exists():
        raise SystemExit("缺 model.ncnn.param，先跑 export-model.py --ncnn")

    work = model / "int8"
    work.mkdir(exist_ok=True)
    fp32_param, fp32_bin = work / "model-fp32.param", work / "model-fp32.bin"
    table = work / "model.table"
    int8_param, int8_bin = work / "model-int8.param", work / "model-int8.bin"

    print("1/3 fp16 → fp32")
    run([NCNN_DIR / "ncnnoptimize.exe", fp16_param, fp16_bin, fp32_param, fp32_bin, "0"])

    print(f"2/3 生成校准表（{args.calib} 张，method={args.method}）")
    calib_dir = work / "calib"
    listing = dump_calibration(args.data, calib_dir, args.calib)
    # 列表里的图片名是相对 calib_dir 的，所以 cwd 必须是 calib_dir，
    # 其余路径一律绝对化，否则会被解析两次
    run([NCNN_DIR / "ncnn2table.exe", fp32_param.resolve(), fp32_bin.resolve(),
         listing.name, table.resolve(),
         "mean=[0.0]", "norm=[0.003921569]", "shape=[64,64]", "pixel=GRAY",
         f"method={args.method}", "thread=8"], cwd=calib_dir)

    print("3/3 fp32 + 表 → int8")
    # ncnn2int8 的参数顺序是 in.param in.bin out.param out.bin table（表在最后）
    run([NCNN_DIR / "ncnn2int8.exe", fp32_param, fp32_bin, int8_param, int8_bin, table])

    sizes = {
        "fp16": fp16_bin.stat().st_size,
        "fp32": fp32_bin.stat().st_size,
        "int8": int8_bin.stat().st_size,
    }
    print("\n体积：")
    for name, size in sizes.items():
        print(f"  {name:5s} {size / 1024 / 1024:6.2f} MB")

    report = {"created": time.strftime("%Y-%m-%dT%H:%M:%S"), "calib_images": args.calib,
              "method": args.method, "bytes": sizes,
              "int8_bin_sha256": sha256(int8_bin),
              "int8_param_sha256": sha256(int8_param)}

    if not args.skip_eval:
        print("\n精度对比（同一批测试图，ncnn CPU 推理）：")
        fp16_stats = evaluate_ncnn(fp16_param, fp16_bin, args.data / "test.npz",
                                   args.eval_limit, int8=False)
        int8_stats = evaluate_ncnn(int8_param, int8_bin, args.data / "test.npz",
                                   args.eval_limit, int8=True)
        print(f"  fp16  top1 {fp16_stats['top1']:.3f}  top5 {fp16_stats['top5']:.3f}  "
              f"top10 {fp16_stats['top10']:.3f}  ({fp16_stats['seconds']}s)")
        print(f"  int8  top1 {int8_stats['top1']:.3f}  top5 {int8_stats['top5']:.3f}  "
              f"top10 {int8_stats['top10']:.3f}  ({int8_stats['seconds']}s)")
        delta = int8_stats["top1"] - fp16_stats["top1"]
        print(f"  差值  top1 {delta * 100:+.2f}pp")
        report["eval"] = {"fp16": fp16_stats, "int8": int8_stats, "top1_delta_pp": round(delta * 100, 2)}

    (work / "int8-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 把 int8 产物并入资产清单，Android 侧只需要认一个 manifest
    manifest_path = model / "model-manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["int8"] = {
            "method": args.method,
            "calib_images": args.calib,
            "bytes": sizes["int8"],
            "accuracy": report.get("eval", {}),
            "note": "int8 推理需 ncnn opt.use_int8_inference=True；输入口径与 fp16 完全一致",
        }
        manifest.setdefault("files", {})
        for name in ("int8/model-int8.param", "int8/model-int8.bin", "int8/model.table"):
            path = model / name
            if path.exists():
                manifest["files"][name] = {"bytes": path.stat().st_size, "sha256": sha256(path)}
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"已更新 {manifest_path.name}")

    print(f"\n已写入 {work}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
