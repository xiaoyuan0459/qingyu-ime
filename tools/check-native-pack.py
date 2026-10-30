#!/usr/bin/env python3
"""Audit the shipped native-form pack against its manifest and pinned public source."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import zipfile

root = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise SystemExit(message)


source = root / "imesdk/rime-src/native"
lock = json.loads((source / "source.json").read_text(encoding="utf-8"))
raw = gzip.decompress((source / "cc-cedict-20261028.txt.gz").read_bytes())
require(hashlib.sha256(raw).hexdigest() == lock["sha256"], "Native source checksum differs")
with zipfile.ZipFile(root / "imesdk/src/main/assets/rime-base.zip") as archive:
    names = archive.namelist()
    require(len(names) == len(set(names)), "Duplicate archive entries")
    manifest = json.loads(archive.read("manifest.json"))
    require(manifest["id"] == "rime-frost-qingyu", "Unexpected default dictionary")
    for entry in manifest["files"]:
        data = archive.read(entry["path"])
        require(len(data) == entry["size"], "Pack size mismatch")
        require(hashlib.sha256(data).hexdigest() == entry["sha256"], "Pack hash mismatch")
    metadata = json.loads(archive.read("native-forms.json"))
    require(metadata["formatVersion"] == 1 and metadata["sha256"] == lock["sha256"], "Native metadata differs")
    require(hashlib.sha256(archive.read("LICENSE.cc-cedict")).hexdigest() == lock["licenseSha256"], "Native data license differs")
    require("build/native_hant.table.bin" in names, "Native dictionary missing")
    require("associate.bin" in names and "associate-traditional.bin" in names, "Native prefix index missing")
    for name in names:
        if name.endswith(".schema.yaml"):
            text = archive.read(name).decode("utf-8")
            require("simplifier@" not in text and "option_name: simplification" not in text, "Legacy converter in schema")
            require("opencc_config: s2t" not in text and "opencc_config: t2s" not in text, "Script conversion in schema")
            if "native_traditional:" in text:
                prism = re.search(r"native_traditional:\n.*?^  prism: (\S+)", text, re.M | re.S)
                require(prism and "build/" + prism.group(1) + ".prism.bin" in names, "Layout prism missing")
    require(not any(name in names for name in ("opencc/s2t.json", "opencc/t2s.json")), "Legacy conversion config shipped")
    for line in archive.read("opencc/emoji.txt").decode("utf-8").splitlines():
        if '\t' in line:
            original, forms = line.split('\t', 1)
            require(original in forms.split(' '), "Emoji expansion lost an original word form")
print("Verified shipped native word-form sources, hashes, layout prisms and conversion-free schemas")
