#!/usr/bin/env python3
"""单字手写分类器训练（64×64 灰度，输出 Top-K）。

定位对应 `imesdk/docs/手写识别开源迁移实施计划.md` 的 A 轨：图像分类、端侧离线、
NCNN 推理。输入口径完全由 render-dataset.py 产出，这里不再做任何图像变换，
避免训练侧和真机侧的预处理悄悄分叉。

用法::

    python tools/handwriting/train.py --data .cache/dataset/v1 --epochs 20
    python tools/handwriting/train.py --data .cache/dataset/v1 --arch tiny   # 冒烟

产物：model.pt / model.onnx / charset.json / train-report.json
"""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

HERE = Path(__file__).resolve().parent


class ConvBlock(nn.Module):
    """conv-bn-relu 的最小单元；不用残差，保证 NCNN int8 量化友好。"""

    def __init__(self, cin: int, cout: int, stride: int = 1):
        super().__init__()
        self.conv = nn.Conv2d(cin, cout, 3, stride, 1, bias=False)
        self.bn = nn.BatchNorm2d(cout)

    def forward(self, x):
        return F.relu(self.bn(self.conv(x)), inplace=True)


class HandwritingNet(nn.Module):
    def __init__(self, num_classes: int, width: int = 32):
        super().__init__()
        w = width
        self.features = nn.Sequential(
            ConvBlock(1, w),                      # 64
            ConvBlock(w, w), nn.MaxPool2d(2),     # 32
            ConvBlock(w, w * 2), nn.MaxPool2d(2), # 16
            ConvBlock(w * 2, w * 4),              # 16
            ConvBlock(w * 4, w * 4), nn.MaxPool2d(2),  # 8
            ConvBlock(w * 4, w * 8),              # 8
            ConvBlock(w * 8, w * 8), nn.MaxPool2d(2),  # 4
        )
        self.head = nn.Sequential(nn.Dropout(0.2), nn.Linear(w * 8, num_classes))

    def forward(self, x):
        x = self.features(x)
        x = F.adaptive_avg_pool2d(x, 1).flatten(1)
        return self.head(x)


def load_split(path: Path) -> tuple[np.ndarray, np.ndarray]:
    data = np.load(path)
    return data["x"], data["y"].astype(np.int64)


@torch.no_grad()
def evaluate(model, x, y, device, batch: int = 512, bounds: list[int] | None = None) -> dict:
    """返回整体与分频段指标。分频段是重点：生僻字样本少，平均值会把问题掩盖掉。"""
    model.eval()
    edges = [0] + list(bounds or []) + [1 << 30]
    names = [f"{lo + 1}-{hi if hi < 1 << 30 else '末'}" for lo, hi in zip(edges[:-1], edges[1:])]

    correct = {1: 0, 5: 0, 10: 0}
    bucket = {n: {"n": 0, 1: 0, 5: 0, 10: 0} for n in names}
    loss_sum = 0.0

    for start in range(0, len(x), batch):
        xb = torch.from_numpy(x[start:start + batch]).to(device).float().unsqueeze(1) / 255.0
        yb = torch.from_numpy(y[start:start + batch]).to(device)
        logits = model(xb)
        loss_sum += F.cross_entropy(logits, yb, reduction="sum").item()
        top = logits.topk(10, dim=1).indices
        labels = yb.cpu().numpy()
        for k in correct:
            hit = (top[:, :k] == yb[:, None]).any(dim=1).cpu().numpy()
            correct[k] += int(hit.sum())
            for (lo, hi), name in zip(zip(edges[:-1], edges[1:]), names):
                mask = (labels >= lo) & (labels < hi)
                bucket[name][k] += int(hit[mask].sum())
        for (lo, hi), name in zip(zip(edges[:-1], edges[1:]), names):
            bucket[name]["n"] += int(((labels >= lo) & (labels < hi)).sum())

    n = max(1, len(x))
    result = {"n": int(len(x)), "loss": loss_sum / n,
              **{f"top{k}": v / n for k, v in correct.items()}}
    result["buckets"] = {
        name: {"n": stat["n"], **{f"top{k}": stat[k] / max(1, stat["n"]) for k in (1, 5, 10)}}
        for name, stat in bucket.items() if stat["n"]
    }
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=HERE / ".cache" / "dataset" / "v1")
    parser.add_argument("--out", type=Path, default=HERE / ".cache" / "models" / "v1")
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch", type=int, default=256)
    parser.add_argument("--lr", type=float, default=3e-3)
    parser.add_argument("--width", type=int, default=32, help="基础通道数；24 更小，48 更强")
    parser.add_argument("--label-smoothing", type=float, default=0.05)
    parser.add_argument("--seed", type=int, default=20261023)
    parser.add_argument("--threads", type=int, default=0)
    parser.add_argument("--device", default="auto", help="auto / cuda / cpu；auto 有 GPU 就用 GPU")
    parser.add_argument("--eval-limit", type=int, default=20000,
                        help="每轮验证最多用多少张，0=全用；大类别时验证集本身就很耗时")
    parser.add_argument("--buckets", default="3500,8105",
                        help="分频段统计的类别边界，逗号分隔；默认 高频/中频/生僻 三段")
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    if args.threads:
        torch.set_num_threads(args.threads)
    if args.device == "auto":
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    else:
        device = torch.device(args.device)
    if device.type == "cuda":
        torch.backends.cudnn.benchmark = True  # 固定 64×64 输入，自动选最快卷积算法
        print(f"设备 {torch.cuda.get_device_name(0)}，显存 "
              f"{torch.cuda.get_device_properties(0).total_memory / 1024**3:.1f} GB")
    else:
        print("设备 CPU（端侧部署口径，但训练慢）")
    use_amp = device.type == "cuda"
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)
    bounds = [int(b) for b in args.buckets.split(",") if b.strip()] if args.buckets else []

    tx, ty = load_split(args.data / "train.npz")
    vx, vy = load_split(args.data / "val.npz")
    sx, sy = load_split(args.data / "test.npz")
    if args.eval_limit and len(vx) > args.eval_limit:
        keep = np.random.default_rng(args.seed).choice(len(vx), args.eval_limit, replace=False)
        keep.sort()
        vx, vy = vx[keep], vy[keep]
    charset = json.loads((args.data / "charset.json").read_text(encoding="utf-8"))
    num_classes = charset["num_classes"]
    print(f"训练 {len(tx)} / 验证 {len(vx)} / 留出风格测试 {len(sx)}，类别 {num_classes}")

    model = HandwritingNet(num_classes, args.width).to(device)
    params = sum(p.numel() for p in model.parameters())
    print(f"参数量 {params / 1e6:.2f}M")

    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    steps_per_epoch = max(1, len(tx) // args.batch)
    scheduler = torch.optim.lr_scheduler.OneCycleLR(
        optimizer, max_lr=args.lr, total_steps=args.epochs * steps_per_epoch,
        pct_start=0.15, div_factor=10, final_div_factor=100,
    )

    rng = np.random.default_rng(args.seed)
    best = -1.0
    history = []
    started = time.time()

    for epoch in range(1, args.epochs + 1):
        model.train()
        order = rng.permutation(len(tx))
        epoch_loss, seen = 0.0, 0
        for start in range(0, len(order) - args.batch + 1, args.batch):
            idx = order[start:start + args.batch]
            xb = torch.from_numpy(tx[idx]).float().unsqueeze(1).to(device, non_blocking=True) / 255.0
            yb = torch.from_numpy(ty[idx]).to(device, non_blocking=True)
            with torch.amp.autocast("cuda", enabled=use_amp):
                logits = model(xb)
                loss = F.cross_entropy(logits, yb, label_smoothing=args.label_smoothing)
            optimizer.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            scheduler.step()
            epoch_loss += loss.item() * len(idx)
            seen += len(idx)

        val = evaluate(model, vx, vy, device, bounds=bounds)
        train_loss = epoch_loss / max(1, seen)
        history.append({"epoch": epoch, "train_loss": round(train_loss, 4),
                        "val_loss": round(val["loss"], 4), "val_top1": round(val["top1"], 4),
                        "val_top5": round(val["top5"], 4)})
        flag = ""
        if val["top1"] > best:
            best = val["top1"]
            args.out.mkdir(parents=True, exist_ok=True)
            torch.save({"state_dict": model.state_dict(), "num_classes": num_classes,
                        "width": args.width, "charset_sha256": charset.get("classes_sha256")},
                       args.out / "model.pt")
            flag = " *"
        print(f"epoch {epoch:2d}/{args.epochs}  loss {train_loss:.3f}  "
              f"val_loss {val['loss']:.3f}  top1 {val['top1']:.3f}  top5 {val['top5']:.3f}  "
              f"{time.time() - started:.0f}s{flag}", flush=True)

    model.load_state_dict(torch.load(args.out / "model.pt", map_location=device)["state_dict"])
    val = evaluate(model, vx, vy, device, bounds=bounds)
    test = evaluate(model, sx, sy, device, bounds=bounds) if len(sx) else {"n": 0}
    print(f"\n验证集  top1 {val['top1']:.3f}  top5 {val['top5']:.3f}  top10 {val['top10']:.3f}")
    if len(sx):
        print(f"留出风格 top1 {test['top1']:.3f}  top5 {test['top5']:.3f}  "
              f"top10 {test['top10']:.3f}（未见过的字体，模拟陌生笔迹）")

    for title, stats in (("验证集分频段", val.get("buckets", {})),
                         ("留出风格分频段", test.get("buckets", {}))):
        if not stats:
            continue
        print(f"{title}（类别序号区间，序号越小越常用）：")
        for name, s in stats.items():
            print(f"  {name:>12s}  n={s['n']:6d}  top1 {s['top1']:.3f}  "
                  f"top5 {s['top5']:.3f}  top10 {s['top10']:.3f}")

    model.eval()
    model.to("cpu")  # ONNX 导出与真机推理都在 CPU 口径上，导出前挪回来
    dummy = torch.zeros(1, 1, 64, 64)
    onnx_path = args.out / "model.onnx"
    onnx_status = "ok"
    try:
        # dynamo=False 走传统导出器：算子集更保守，pnnx 转 NCNN 更稳
        torch.onnx.export(model, dummy, str(onnx_path), input_names=["input"],
                          output_names=["logits"], opset_version=17, dynamo=False,
                          dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}})
    except Exception as exc:  # 导出失败不能吞掉训练结果
        onnx_status = f"failed: {type(exc).__name__}: {exc}"
        print(f"\n! ONNX 导出失败：{onnx_status}")

    report = {
        "created": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "data": str(args.data),
        "num_classes": num_classes,
        "params": int(params),
        "width": args.width,
        "epochs": args.epochs,
        "seconds": round(time.time() - started, 1),
        "val": val,
        "test_unseen_style": test,
        "onnx": onnx_status,
        "history": history,
    }
    (args.out / "train-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.out / "charset.json").write_text(
        json.dumps(charset, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"\n已写入 {args.out}：model.pt / model.onnx / charset.json / train-report.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
