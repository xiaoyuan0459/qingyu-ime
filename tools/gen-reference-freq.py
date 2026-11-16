#!/usr/bin/env python3
"""在当前有效锚表上复用历史三源算法；只生成独立实验，不读聊天计数。"""

import argparse
import importlib.util
import json
from pathlib import Path

from rime_source_provenance import digest, verify_source_snapshot

ROOT = Path(__file__).resolve().parent.parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--essay", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    verify_source_snapshot(args.source)
    legacy = load("legacy_freq", "gen-dict-freq.py")
    chat = load("chat_freq", "freq-chat-merge.py")
    upstream, entries, codes, tables = chat.read_tables(args.source / "cn_dicts")
    essay, jieba = legacy.read_essay(args.essay), legacy.read_jieba()
    rows, factor = legacy.rescale(legacy.fuse(upstream, essay, jieba, legacy.DEFAULT_ALPHA, 0.3))
    values = {row[0]: row[1] for row in rows}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    # 全部因素对照使用同一读音注入边界；三源只改变参考频率，不再混入读音抹平。
    with args.out.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write("# historical three-source, reading ratios preserved; no chat layer\n")
        for (word, code), weight in sorted(entries.items()):
            target = max(1, round(weight * values[word] / upstream[word]))
            stream.write(f"{word}\t{code}\t{target}\n")
    import jieba as jieba_module
    with jieba_module.get_dict_file() as handle:
        jieba_sha = __import__("hashlib").file_digest(handle, "sha256").hexdigest()
    report = {"strength": 0.3, "alpha": legacy.DEFAULT_ALPHA, "anchor_tables": tables,
        "include_single": False, "method": "historical log-normalized three-source + median rescale; reading-ratio injection",
        "rescale_factor": factor, "source_sha256": digest(args.source / "source-provenance.json"),
        "essay_sha256": digest(args.essay), "jieba_version": jieba_module.__version__, "jieba_dictionary_sha256": jieba_sha,
        "generator_sha256": digest(Path(__file__)), "legacy_algorithm_sha256": digest(ROOT / "tools/gen-dict-freq.py"),
        "words": len(values), "entries": len(entries), "outputs": {"reading": {"sha256": digest(args.out), "name": args.out.name}}}
    args.out.with_suffix(".json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"words":len(values), "entries":len(entries), "rescale":factor}))


if __name__ == "__main__":
    main()
