"""评测专用部署校验：校验实际字节与文件集合，不把 ID/版本当内容指纹。"""

import hashlib
import json
import re
import shlex
import uuid
from pathlib import Path, PurePosixPath


def sha256_of(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_pack_files(pack: Path, manifest: dict) -> list[dict]:
    checked = []
    seen = set()
    for entry in manifest["files"]:
        name = entry["path"]
        relative = PurePosixPath(name)
        if (not name or relative.is_absolute() or ".." in relative.parts or
                "\\" in name or "\n" in name or "\r" in name or str(relative) != name or
                name in seen or name == "manifest.json"):
            raise SystemExit(f"manifest 文件路径无效或重复: {name!r}")
        seen.add(name)
        path = pack / name
        if not path.resolve().is_relative_to(pack.resolve()) or not path.is_file():
            raise SystemExit(f"manifest 登记的文件缺失或越界: {name}")
        if path.stat().st_size != entry["size"]:
            raise SystemExit(f"文件大小与 manifest 不符: {name}")
        digest = sha256_of(path)
        if digest != entry["sha256"]:
            raise SystemExit(f"SHA-256 与 manifest 不符: {name}")
        checked.append({"path": name, "size": entry["size"], "sha256": digest})
    actual = {p.relative_to(pack).as_posix() for p in pack.rglob("*") if p.is_file()}
    if actual != seen | {"manifest.json"}:
        raise SystemExit("pack 含有未登记文件，不能作为隔离评测包")
    return checked


def validate_device_root(root: str) -> None:
    if not re.fullmatch(r"/data/local/tmp/qingyu-eval(?:-[a-zA-Z0-9_-]+)?", root):
        raise SystemExit("评测目录必须是 /data/local/tmp/qingyu-eval 或其带连字符的独立名称")


def assert_deployed(adb, root: str, pack: Path, manifest: dict, probe: Path,
                    runtime: str | None = None) -> dict:
    runtime = runtime or root + "/runtime"
    expected = {entry["path"]: entry["sha256"] for entry in manifest["files"]}
    expected["manifest.json"] = sha256_of(pack / "manifest.json")
    names = " ".join(shlex.quote(name) for name in expected)
    output = adb("shell", f"cd {shlex.quote(runtime)} && sha256sum -- {names}")
    actual = {}
    for line in output.splitlines():
        digest, name = line.split(None, 1)
        actual[name.strip()] = digest
    if actual != expected:
        raise SystemExit("设备上 pack 的 SHA-256 与 --pack 不一致；加 --setup 重新部署")
    inventory = adb("shell", f"cd {shlex.quote(runtime)} && find . -type f")
    if {line.removeprefix("./") for line in inventory.splitlines()} != set(expected):
        raise SystemExit("设备 pack 含旧包残留或未登记文件；加 --setup 重新部署")
    remote_probe = adb("shell", f"sha256sum -- {root}/probe").split()[0]
    if remote_probe != sha256_of(probe):
        raise SystemExit("设备 probe 的 SHA-256 与 --probe 不一致；加 --setup 重新部署")
    identity = {"manifest_sha256": expected["manifest.json"], "probe_sha256": remote_probe,
                "verified_files": len(expected), "device_bytes_verified": True}
    source_path = pack / "source-provenance.json"
    if source_path.is_file():
        source = json.loads(source_path.read_text(encoding="utf-8"))
        native = json.loads(adb("shell", f"{root}/probe --source-identity {runtime}/build"))
        if native["pinyin_dict_crc32"] != source["rime_dict_crc32"]:
            raise SystemExit("实际编译字典 checksum 与有效源清单不符")
        identity.update(source_sha256=sha256_of(source_path), source_crc_verified=True)
    return identity


def setup_device(adb, root: str, probe: Path, pack: Path, manifest: dict) -> dict:
    # 先部署到新目录再替换 runtime，避免 adb push 留下旧包额外文件。
    suffix = uuid.uuid4().hex
    staged = f"{root}/runtime-stage-{suffix}"
    previous = f"{root}/runtime-prev-{suffix}"
    adb("shell", f"mkdir -p {staged}")
    adb("push", str(probe), f"{root}/probe")
    adb("shell", f"chmod 755 {root}/probe")
    adb("push", str(pack) + "/.", staged + "/")
    identity = assert_deployed(adb, root, pack, manifest, probe, staged)
    adb("shell", f"if [ -d {root}/runtime ]; then mv {root}/runtime {previous}; fi; "
        f"mv {staged} {root}/runtime")
    # previous 是本函数创建且已校验在评测根目录内的旧 runtime，不涉及应用数据。
    adb("shell", f"rm -rf {previous}")
    return identity
