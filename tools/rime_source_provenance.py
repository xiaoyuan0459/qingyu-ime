"""Rime 源清单与编译来源：只跟随有效导入，不按目录猜词表。"""

import hashlib
import json
import re
import zlib
from pathlib import Path, PurePosixPath


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def dictionary_header(path: Path) -> tuple[dict, list[str]]:
    header, imports = {}, []
    in_imports = False
    with path.open(encoding="utf-8-sig") as stream:
        for line in stream:
            if line.strip() == "...":
                break
            line = line.split("#", 1)[0].rstrip()
            match = re.match(r"^([\w]+):\s*(.*?)\s*$", line)
            if match:
                name, value = match.groups()
                header[name] = value.strip('"\'')
                in_imports = name == "import_tables"
                if in_imports and value:
                    raise ValueError(f"不支持内联 import_tables: {path.name}")
            elif in_imports:
                match = re.match(r"^\s+-\s+([\w/-]+)\s*$", line)
                if match:
                    imports.append(match[1])
    return header, imports


def effective_files(source: Path, dictionary="pinyin") -> list[Path]:
    primary = source / f"{dictionary}.dict.yaml"
    header, imports = dictionary_header(primary)
    # 与固定 librime DictSettings::GetTables 顺序一致：主词典及直接导入表。
    files = [primary]
    for name in imports:
        relative = PurePosixPath(name + ".dict.yaml")
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("词表路径越界")
        path = source / str(relative)
        if not path.is_file() or not path.resolve().is_relative_to(source.resolve()):
            raise ValueError(f"有效导入缺失或越界: {name}")
        if path in files:
            raise ValueError(f"重复导入: {name}")
        files.append(path)
    if header.get("use_preset_vocabulary", "false") == "true":
        vocabulary = header.get("vocabulary", "essay")
        files.append(source / f"{vocabulary}.txt")
    return files


def iter_entries(path: Path):
    started = False
    with path.open(encoding="utf-8-sig") as stream:
        for line in stream:
            if not started:
                started = line.strip() == "..."
                continue
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.rstrip("\r\n").split("\t")
            text = fields[0]
            code = fields[1].strip() if len(fields) > 1 else ""
            try:
                weight = float(fields[2]) if len(fields) > 2 else 0
            except ValueError:
                weight = 0
            yield text, code, weight


def active_frequency_paths(directory: Path, names) -> list[Path]:
    parent = directory.parent
    dictionary = next((name for name in ("pinyin", "rime_frost", "rime_ice")
                       if (parent / f"{name}.dict.yaml").is_file()), None)
    active = set(effective_files(parent, dictionary)) if dictionary else None
    return [path for name in names if (path := directory / f"{name}.dict.yaml").is_file()
            and (active is None or path in active)]


def source_snapshot(source: Path, upstream: dict, frequency: Path | None = None,
                    frequency_provenance: Path | None = None) -> dict:
    tables, checksum = [], 0xFFFFFFFF
    for path in effective_files(source):
        explicit = implicit = 0
        if path.suffix != ".txt":
            for _, code, _ in iter_entries(path):
                explicit += bool(code)
                implicit += not bool(code)
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1 << 20), b""):
                checksum = zlib.crc32(chunk, checksum)
        tables.append({"path": path.relative_to(source).as_posix(), "sha256": digest(path),
                       "explicit_code_rows": explicit, "implicit_code_rows": implicit})
    schemas = [{"path": p.name, "sha256": digest(p)} for p in sorted(source.glob("*.schema.yaml"))]
    result = {"format_version": 1, "upstream_revision": upstream["revision"],
              "dictionary": "pinyin", "effective_tables": tables, "schemas": schemas,
              "rime_dict_crc32": checksum,
              "input_files": [{"path": p.relative_to(source).as_posix(), "sha256": digest(p)}
                              for p in sorted(source.rglob("*")) if p.is_file() and p.name != "source-provenance.json"],
              "frequency": {"name": frequency.name, "sha256": digest(frequency)} if frequency else None}
    if frequency_provenance:
        report = json.loads(frequency_provenance.read_text(encoding="utf-8"))
        if not frequency or not any(item["sha256"] == digest(frequency) for item in report.get("outputs", {}).values()):
            raise ValueError("频率文件与实验来源报告的输出哈希不符")
        result["frequency_provenance"] = {"report_sha256": digest(frequency_provenance),
            "parameters": {key: report.get(key) for key in ("strength", "shrink_k", "include_single", "anchor_tables")},
            "source_binding": report.get("training_source_binding"),
            "training_inputs": report.get("training_provenance", {}).get("inputs"),
            "excluded_inputs": report.get("training_provenance", {}).get("excluded_inputs"),
            "segmentation": report.get("training_provenance", {}).get("segmentation")}
    (source / "source-provenance.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def verify_source_snapshot(source: Path) -> dict:
    snapshot = json.loads((source / "source-provenance.json").read_text(encoding="utf-8"))
    for item in snapshot["input_files"]:
        if digest(source / item["path"]) != item["sha256"]:
            raise ValueError(f"源快照已变化: {item['path']}")
    actual = {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file() and p.name != "source-provenance.json"}
    if actual != {item["path"] for item in snapshot["input_files"]}:
        raise ValueError("源目录文件集合已变化")
    if [p.relative_to(source).as_posix() for p in effective_files(source)] != [t["path"] for t in snapshot["effective_tables"]]:
        raise ValueError("有效导入清单与源快照不符")
    return snapshot


def verify_compilation(source: Path, compiled: Path) -> dict:
    verify_source_snapshot(source)
    receipt = json.loads((compiled / "build-receipt.json").read_text(encoding="utf-8"))
    if receipt["source_sha256"] != digest(source / "source-provenance.json"):
        raise ValueError("编译产物属于另一份源快照")
    for item in receipt["files"]:
        if digest(compiled / item["path"]) != item["sha256"]:
            raise ValueError(f"编译产物已变化: {item['path']}")
    actual = {p.relative_to(compiled).as_posix() for p in compiled.rglob("*") if p.is_file() and p.name != "build-receipt.json"}
    if actual != {item["path"] for item in receipt["files"]}:
        raise ValueError("编译目录含未登记文件或旧文件残留")
    return receipt
