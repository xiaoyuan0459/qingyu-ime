#!/usr/bin/env python3
"""用独立设备目录编译固定源，并把实际编译工具/输入/输出绑定为收据。"""

import argparse
import json
import shlex
import subprocess
import time
import uuid
from pathlib import Path

from rime_source_provenance import digest, verify_source_snapshot


ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="")
    parser.add_argument("--deployer", type=Path, default=ROOT / "imesdk/build/intermediates/cxx/Debug/3cm4w3wq/obj/arm64-v8a/qingyu_deployer")
    args = parser.parse_args()
    snapshot = verify_source_snapshot(args.source)
    if args.out.exists():
        parser.error("--out 已存在；使用新的独立输出目录，避免混入旧编译产物")
    prefix = [args.adb] + (["-s", args.serial] if args.serial else [])

    def adb(*command):
        result = subprocess.run([*prefix, *command], capture_output=True, timeout=3600)
        if result.returncode:
            raise RuntimeError(f"adb 阶段失败 ({result.returncode}): {result.stderr.decode('utf-8', 'replace')[:2000]}")
        return result.stdout.decode("utf-8", "strict")

    remote = "/data/local/tmp/qingyu-compile-" + uuid.uuid4().hex
    started = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    adb("shell", f"mkdir -p {remote}/src {remote}/out")
    print("Deploying isolated compiler and source", flush=True)
    adb("push", str(args.deployer), remote + "/deployer")
    adb("shell", f"chmod 755 {remote}/deployer")
    adb("push", str(args.source) + "/.", remote + "/src/")
    tool_sha = adb("shell", f"sha256sum {remote}/deployer").split()[0]
    if tool_sha != digest(args.deployer):
        raise RuntimeError("设备编译工具字节不符")
    files = snapshot["input_files"] + [{"path": "source-provenance.json", "sha256": digest(args.source / "source-provenance.json")}]
    names = " ".join(shlex.quote(item["path"]) for item in files)
    actual = {}
    for line in adb("shell", f"cd {remote}/src && sha256sum -- {names}").splitlines():
        sha, name = line.split(None, 1)
        actual[name.strip()] = sha
    if actual != {item["path"]: item["sha256"] for item in files}:
        raise RuntimeError("设备源文件字节与源快照不符")
    print("Compiling dictionaries and all schema prisms on device", flush=True)
    adb("shell", f"{remote}/deployer --build {remote}/src {remote}/src {remote}/out")
    verify_source_snapshot(args.source)
    args.out.mkdir(parents=True)
    adb("pull", remote + "/out/.", str(args.out))
    outputs = [{"path": p.relative_to(args.out).as_posix(), "sha256": digest(p), "size": p.stat().st_size}
               for p in sorted(args.out.rglob("*")) if p.is_file()]
    receipt = {"format_version": 1, "source_sha256": digest(args.source / "source-provenance.json"),
               "deployer_sha256": tool_sha, "started_utc": started,
               "finished_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
               "abi": adb("shell", "getprop ro.product.cpu.abi").strip(),
               "sdk": adb("shell", "getprop ro.build.version.sdk").strip(), "files": outputs}
    (args.out / "build-receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(f"Compiled and bound {len(outputs)} files: {args.out}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
