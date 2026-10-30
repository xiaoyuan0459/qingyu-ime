#!/usr/bin/env python3
"""Measure native orthography coverage and emit a conversion-free feasibility workspace.

This is a design probe, not the production pack builder. It reads a user-supplied
CC-CEDICT snapshot and a previously assembled White Frost workspace. It never
downloads data or changes APK assets. Source hashes and coverage gaps are reported.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
from rime_native_forms import reading, read_cedict, analyze











def emit_workspace(out, records, weights):
    out.mkdir(parents=True, exist_ok=True)
    tables = {name: {} for name in ("native_hans", "native_hant", "native_original")}
    for hans, hant, sound in records:
        weight = max(1, weights.get((hant, sound), 1))
        for mode, forms in (("native_hans", (hans,)), ("native_hant", (hant,)),
                            ("native_original", (hans, hant))):
            for text in forms:
                tables[mode][text, sound] = max(weight, tables[mode].get((text, sound), 0))
    for name, entries in tables.items():
        with (out / (name + ".dict.yaml")).open("w", encoding="utf-8", newline="\n") as stream:
            stream.write("# Derived from CC-CEDICT; CC-BY-SA-4.0. See SOURCE.json and SOURCE-CEDICT.txt.\n")
            stream.write(f"---\nname: {name}\nversion: 'probe-1'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n")
            for (text, sound), weight in sorted(entries.items()):
                stream.write(f"{text}\t{sound}\t{weight:g}\n")
        (out / (name + ".schema.yaml")).write_text(
            f"schema:\n  schema_id: {name}\n  name: {name}\n"
            "engine:\n  processors: [speller, selector, express_editor]\n"
            "  segmentors: [abc_segmentor, fallback_segmentor]\n"
            "  translators: [script_translator]\n  filters: []\n"
            "speller:\n  alphabet: abcdefghijklmnopqrstuvwxyz\n  delimiter: \"'\"\n"
            f"translator:\n  dictionary: {name}\n  enable_user_dict: false\n"
            "  enable_completion: false\n  enable_sentence: true\n"
            "  spelling_hints: 100\n  always_show_comments: true\nmenu:\n  page_size: 1000\n",
            encoding="utf-8")
    (out / "default.yaml").write_text(
        "schema_list:\n" + "".join(f"  - schema: {name}\n" for name in tables), encoding="utf-8")
    return {mode: len(entries) for mode, entries in tables.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cedict", required=True, type=Path)
    parser.add_argument("--white-workspace", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    records, header = read_cedict(args.cedict)
    stats, weights = analyze(args.white_workspace, records)
    stats["cedictRecordsWithSupportedReadings"] = len(records)
    stats["ambiguousSimplifiedReadingPairs"] = sum(
        len(values) > 1 for values in _pairs(records).values())
    stats["prototypeEntries"] = emit_workspace(args.out, records, weights)
    stats["cedictSha256"] = hashlib.sha256(args.cedict.read_bytes()).hexdigest()
    stats["prototypeScope"] = "CC-CEDICT native forms only, not a complete replacement for White Frost"
    (args.out / "SOURCE-CEDICT.txt").write_text("\n".join(header) + "\n", encoding="utf-8")
    (args.out / "SOURCE.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False, indent=2))


def _pairs(records):
    pairs = collections.defaultdict(set)
    for hans, hant, sound in records:
        pairs[hans, sound].add(hant)
    return pairs


if __name__ == "__main__":
    main()
