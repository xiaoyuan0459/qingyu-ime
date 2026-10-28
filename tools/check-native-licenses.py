"""Verify the archived native license texts used by the APK against their manifest."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
directory = root / "imesdk/src/main/assets/licenses/native"
entries = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
for entry in entries:
    path = (directory / entry["file"]).resolve()
    if not path.is_relative_to(directory.resolve()) or not path.is_file():
        raise SystemExit("Missing or unsafe license path")
    if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
        raise SystemExit(f"License archive differs: {entry['component']}")
for name in ("librime", "glog", "leveldb", "marisa-trie", "yaml-cpp", "opencc", "boost", "ncnn", "rapidjson"):
    if not any(entry["component"] == name for entry in entries):
        raise SystemExit(f"Missing license: {name}")
print(f"Verified {len(entries)} native license / attribution texts")
