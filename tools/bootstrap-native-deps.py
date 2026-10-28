"""Prepare pinned native build dependencies without replacing existing checkouts."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "tools/native-deps.lock.json").read_text(encoding="utf-8"))
ABIS = ("arm64-v8a", "x86_64")


def git(*args: str, cwd: Path) -> str:
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


def download(name: str, cache: Path) -> Path:
    spec = LOCK[name]
    archive = cache / spec["url"].rsplit("/", 1)[-1]
    if not archive.is_file():
        print(f"Downloading {name} {spec['version']}", flush=True)
        partial = archive.with_suffix(archive.suffix + ".part")
        request = urllib.request.Request(spec["url"], headers={"User-Agent": "QingyuIme-build"})
        with urllib.request.urlopen(request, timeout=60) as source, partial.open("wb") as target:
            shutil.copyfileobj(source, target)
        partial.replace(archive)
    with archive.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != spec["sha256"]:
        raise ValueError(f"{name}: SHA-256 mismatch; remove the cached archive and retry")
    return archive


def destination(root: Path, relative: str) -> Path:
    parts = PurePosixPath(relative)
    if parts.is_absolute() or ".." in parts.parts or "\\" in relative or ":" in relative:
        raise ValueError(f"Unsafe archive path: {relative}")
    target = root.joinpath(*parts.parts).resolve()
    if not target.is_relative_to(root.resolve()):
        raise ValueError(f"Archive path escapes destination: {relative}")
    target.parent.mkdir(parents=True, exist_ok=True)
    return target


def prepare_boost(target: Path, cache: Path) -> None:
    prefix = "boost_" + LOCK["boost"]["version"].replace(".", "_") + "/boost/"
    with tarfile.open(download("boost", cache), "r:gz") as archive:
        for member in archive:
            if not member.name.startswith(prefix) or member.isdir():
                continue
            if not member.isfile():
                raise ValueError(f"Unsupported Boost archive entry: {member.name}")
            relative = "include/boost/" + member.name[len(prefix):]
            with archive.extractfile(member) as source, destination(target, relative).open("wb") as output:
                shutil.copyfileobj(source, output)


def prepare_ncnn(target: Path, cache: Path) -> None:
    prefix = f"ncnn-{LOCK['ncnn']['version']}-android-vulkan/"
    with zipfile.ZipFile(download("ncnn", cache)) as archive:
        for entry in archive.infolist():
            if not entry.filename.startswith(prefix) or entry.is_dir():
                continue
            relative = entry.filename[len(prefix):]
            if relative.split("/", 1)[0] not in ABIS:
                continue
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"Archive symlink is not allowed: {relative}")
            with archive.open(entry) as source, destination(target, relative).open("wb") as output:
                shutil.copyfileobj(source, output)


def prepare_librime(target: Path) -> None:
    subprocess.run(["git", "init", str(target)], check=True, stdout=subprocess.DEVNULL)
    git("remote", "add", "origin", LOCK["librime"]["url"], cwd=target)
    git("fetch", "--depth", "1", "origin", LOCK["librime"]["revision"], cwd=target)
    git("checkout", "--detach", "FETCH_HEAD", cwd=target)
    git("submodule", "update", "--init", "--recursive", "--depth", "1", cwd=target)


def verify(name: str, target: Path) -> None:
    if name == "librime":
        if git("rev-parse", "HEAD", cwd=target) != LOCK[name]["revision"]:
            raise ValueError("librime revision differs from the lock file")
        statuses = git("submodule", "status", "--recursive", cwd=target)
        if any(line[:1] in ("-", "+", "U") for line in statuses.splitlines()):
            raise ValueError("librime submodules are missing or at the wrong revision")
    elif name == "boost":
        version = (target / "include/boost/version.hpp").read_text(encoding="utf-8")
        expected = "_".join(LOCK[name]["version"].split(".")[:2])
        if f'#define BOOST_LIB_VERSION "{expected}"' not in version:
            raise ValueError("Boost version differs from the lock file")
    else:
        for abi in ABIS:
            version = (target / abi / "lib/cmake/ncnn/ncnnConfigVersion.cmake").read_text()
            if f'set(PACKAGE_VERSION "{LOCK[name]["version"]}")' not in version:
                raise ValueError(f"ncnn {abi} version differs from the lock file")
            if not (target / abi / "lib/libncnn.a").is_file():
                raise ValueError(f"ncnn {abi} static library is missing")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate installed versions without downloading")
    parser.add_argument("--dest", type=Path, default=ROOT / "imesdk/third_party")
    args = parser.parse_args()
    target_root = args.dest.resolve()
    # Temporary directories are cleaned recursively; keep every output inside this checkout.
    if not target_root.is_relative_to(ROOT):
        parser.error("--dest must be inside this repository")
    cache = ROOT / "build/native-deps-cache"
    cache.mkdir(parents=True, exist_ok=True)
    target_root.mkdir(parents=True, exist_ok=True)
    for name, directory in (("librime", "librime"), ("boost", "boost"), ("ncnn", "ncnn-android")):
        target = target_root / directory
        if not target.exists():
            if args.check:
                raise FileNotFoundError(f"Missing {directory}; run this script without --check")
            with tempfile.TemporaryDirectory(prefix="qingyu-", dir=cache) as temporary:
                staged = Path(temporary) / directory
                staged.mkdir()
                if name == "librime":
                    prepare_librime(staged)
                elif name == "boost":
                    prepare_boost(staged, cache)
                else:
                    prepare_ncnn(staged, cache)
                verify(name, staged)
                staged.rename(target)
        verify(name, target)
        print(f"{name} {LOCK[name]['version']}: ready", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Native dependency preparation failed: {error}") from error
