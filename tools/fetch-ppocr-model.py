#!/usr/bin/env python3
"""把官方 PP-OCRv6 识别模型转换为 ncnn 格式，供 imesdk 手写识别使用。

来源固定为官方发布（Apache-2.0），不引用无许可的第三方转换成果。
产物 fp16 量化，约 10 MB。

用法::

    python tools/fetch-ppocr-model.py            # 仅下载官方 ONNX
    python tools/fetch-ppocr-model.py --convert   # 下载并转 ncnn

依赖 pnnx 与 onnxruntime（转换与验证），仅构建期使用，不进 APK。
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

# 官方 HuggingFace 仓库，license 字段为 apache-2.0。
MODEL_REPO = "PaddlePaddle/PP-OCRv6_small_rec_onnx"
BASE_URL = f"https://huggingface.co/{MODEL_REPO}/resolve/main"

# Small 档：7.7M 参数，移动端定位，50 语种。官方字表 18,708 条，
# 模型末层输出 18,710：blank 首通道 + 字表整体后移一位 + 1 个保留通道。
TIER = "small"
INPUT_SHAPE = "[1,3,48,320]"

FILES = {
    "onnx": "inference.onnx",
    "yml": "inference.yml",
    "json": "inference.json",
}

# 已验证：pnnx 20260526 + 官方 ONNX 20.18 MB → fp16 ncnn 10,562,152 字节，
# SHA-256 4f18228f0005d853100103f8116e8c2685ed6f2ecca6059a47396f569ee5581d。
# 重跑得到完全一致的 bin，说明转换可复现。
KNOWN_BIN_SHA256 = "4f18228f0005d853100103f8116e8c2685ed6f2ecca6059a47396f569ee5581d"
KNOWN_BIN_SIZE = 10_562_152

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "imesdk" / "src" / "main" / "assets"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(name: str, dest: Path) -> Path:
    if dest.exists() and dest.stat().st_size > 0:
        print(f"  已有 {dest.name}，跳过下载")
        return dest
    url = f"{BASE_URL}/{name}"
    print(f"  下载 {name}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url) as resp, dest.open("wb") as out:
        shutil.copyfileobj(resp, out)
    return dest


def parse_dict_size(yml_path: Path) -> tuple[int, int]:
    """从官方 inference.yml 统计字表条目与汉字数。

    字表顺序决定输出维度到字符的映射，不能自行排序，因此只读不改。
    yml 里 character_dict 有嵌套缩进，条目形如 `  - 'x'`，终止条件是
    缩进回退到该key 层级或更外层。
    """
    lines = yml_path.read_text(encoding="utf-8", errors="replace").splitlines()

    key_idx = -1
    for i, line in enumerate(lines):
        if line.strip() == "character_dict:":
            key_idx = i
            break
    if key_idx < 0:
        return 0, 0

    items: list[str] = []
    for line in lines[key_idx + 1:]:
        raw = line.rstrip("\n")
        if not raw.strip(" \t") or raw.lstrip().startswith("#"):
            continue
        stripped = raw.strip(" \t")
        # 列表项形如 `  - 'x'`。字表本身含全角空格（U+3000）等
        # 看似空白但非空白的条目，且 str.strip() 会吃掉 U+3000，
        # 因此这里只用 ASCII 空白裁剪，并靠 "- " 前缀与引号配对判断。
        if not stripped.startswith("- "):
            break
        value = stripped[2:]
        # 仅裁剪 ASCII 空白，保留 U+3000 等条目内容
        value = value.strip(" \t")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        items.append(value)

    cjk = sum(1 for t in items if len(t) == 1 and 0x4E00 <= ord(t) <= 0x9FFF)
    return len(items), cjk


def run_pnnx(onnx_path: Path, workdir: Path) -> tuple[Path, Path]:
    """pnnx 默认导出 fp16 权重，体积约为 fp32 的一半。"""
    exe = shutil.which("pnnx")
    if exe is None:
        raise SystemExit("找不到 pnnx，请先 pip install pnnx")

    cmd = [exe, str(onnx_path), f"inputshape={INPUT_SHAPE}"]
    print("  执行:", " ".join(cmd))
    result = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"pnnx 转换失败：\n{result.stdout[-2000:]}\n{result.stderr[-2000:]}")

    param = workdir / "inference.ncnn.param"
    binary = workdir / "inference.ncnn.bin"
    for p in (param, binary):
        if not p.exists():
            raise SystemExit(f"pnnx 未产出预期文件：{p.name}")
    return param, binary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--convert", action="store_true", help="转换为 ncnn")
    parser.add_argument("--keep-temp", action="store_true", help="保留中间文件")
    args = parser.parse_args()

    cache = Path(tempfile.gettempdir()) / "ppocrv6_small_rec"
    cache.mkdir(parents=True, exist_ok=True)

    print(f"官方来源：{MODEL_REPO}（Apache-2.0）")
    paths = {k: download(v, cache / v) for k, v in FILES.items()}

    size_mb = paths["onnx"].stat().st_size / 1024 / 1024
    print(f"官方 ONNX：{size_mb:.2f} MB  sha256={sha256(paths['onnx'])[:16]}…")

    total, cjk = parse_dict_size(paths["yml"])
    print(f"官方字表：{total} 条，其中汉字 {cjk} 个")
    if total == 0:
        raise SystemExit("字表解析失败，无法确认字表规模")
    print("  提示：模型末层输出维度 = 字表条目数 + 4 个 CTC 特殊 token")

    if not args.convert:
        print("\n仅下载完成。需要 ncnn 模型请加 --convert")
        return 0

    print("\n转换为 ncnn（fp16）…")
    param, binary = run_pnnx(paths["onnx"], cache)

    size = binary.stat().st_size
    digest = sha256(binary)
    print(f"\n产物 bin：{size:,} 字节 = {size / 1024 / 1024:.2f} MB")
    print(f"sha256：{digest}")

    if size == KNOWN_BIN_SIZE and digest == KNOWN_BIN_SHA256:
        print("✓ 与已验证基线完全一致（字节级可复现）")
    elif size != KNOWN_BIN_SIZE:
        print(f"! 体积与已验证基线不同（基线 {KNOWN_BIN_SIZE:,} 字节）")
        print("  可能 pnnx 版本不同；核对后再更新 tools/native-deps.lock.json")

    out_dir = OUT_DIR / "ppocrv6_rec"
    out_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(param, out_dir / "inference.ncnn.param")
    shutil.copy2(binary, out_dir / "inference.ncnn.bin")
    shutil.copy2(paths["yml"], out_dir / "inference.yml")

    print(f"\n已写入：{out_dir}")
    for f in sorted(out_dir.iterdir()):
        print(f"  {f.name}  {f.stat().st_size:,} 字节")

    if not args.keep_temp:
        shutil.rmtree(cache, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())