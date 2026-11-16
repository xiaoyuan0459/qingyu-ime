#!/usr/bin/env python3
"""流式统计聊天语料词次（P0-B 的聊天源，独立小模块）。

为什么独立：`gen-dict-freq.py` 的三源融合是「已校准书面源的加权」，聊天源
的口径问题（尺度、读音、覆盖率）要先单独回答；在这里顺手融合会把两件事
搅在一起。本模块只回答一个问题：**这批聊天语料里，每个词出现了多少次。**

设计约束（审查方案 §4-P0-B）：
  * 流式逐行统计，GB 级语料不进内存；
  * **原句不打印、不落入输出计数文件**——输出只有 词→计数 与聚合计数；
  * 切分固定：LCCC 分发文件是**字级**空格切分（每个汉字一个 token），
    先按官方去分词约定（`text.replace(' ', '')`）还原原句，再用 jieba
    固定词典 + 关 HMM 切词（确定性），jieba 版本随摘要落盘；
  * 覆盖率检查只产生「疑似缺失」诊断（上游有、聊天语料零次），不判废词；
    删词决策属于后续实验，不在本模块。

用法::

    python tools/freq-chat-stats.py build/corpus/lccc/lccc_base_train.jsonl.gz \
        --role train --exclude-dialogs build/corpus/lccc/lccc_base_valid.jsonl.gz \
        build/corpus/lccc/lccc_base_test.jsonl.gz \
        --userdict-anchor build/frost/src/cn_dicts \
        --out build/corpus/lccc/chat-train-isolated.tsv \
        --summary build/corpus/lccc/chat-train-isolated.json
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
from pathlib import Path

from freq_chat_provenance import (EXCLUSION_RULE, dialog_digest, excluded_dialogs,
                                  file_identity)
from freq_chat_counting import count_utterances, detokenize, keep_token
from rime_source_provenance import effective_files, iter_entries

REPO = Path(__file__).resolve().parent.parent

# token 过滤：纯 ASCII（英文/数字/标点）不进中文词频表；单字符仅收 CJK。
# LCCC 已过清洗管线（脏词/表情/特殊符号），这里不再做语义过滤，保持口径单一。
_CJK = re.compile(r"[\u3400-\u9fff\uf900-\ufaff]")


def iter_utterances(paths: list[Path], stats: dict, excluded: set[bytes]):
    """逐条产出对话语句。jsonl 每行是 list[str]（字级空格切分的语句列表）。

    stats 在此累积「对话条数」（每行一个对话），语句级计数由调用方做。
    """
    for path in paths:
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            for lineno, line in enumerate(stream, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    yield None, f"{path.name}:{lineno}"
                    continue
                if not isinstance(record, list) or not all(isinstance(t, str) for t in record):
                    yield None, f"{path.name}:{lineno}"
                    continue
                stats["dialogs_read"] += 1
                if excluded and dialog_digest(record) in excluded:
                    stats["dialogs_excluded"] += 1
                    continue
                stats["dialogs"] += 1
                for utterance in record:
                    if isinstance(utterance, str) and utterance.strip():
                        yield utterance, None


def read_upstream_words(clone: Path) -> dict[str, dict[str, int]]:
    """读上游分表词表（不读权重），用于覆盖率诊断。返回 词 → {表名: 1}。"""
    tables = ("base", "ext", "tencent", "others")
    words: dict[str, dict[str, int]] = {}
    for table in tables:
        path = clone / "cn_dicts" / f"{table}.dict.yaml"
        if not path.exists():
            continue
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                if line.startswith("#") or line.startswith("-"):
                    continue
                parts = line.rstrip("\n").split("\t")
                if parts and parts[0]:
                    words.setdefault(parts[0], {})[table] = 1
    return words


def read_lexicon(directory: Path) -> dict[str, int]:
    """读分表词表与权重，用作 jieba 用户词典（词频直接取锚权重）。

    为什么要把引擎词表交给 jieba：jieba 默认词典是新闻口径，会把这些在
    我们词表里的词切散（`大的` → `大`+`的`），于是这些词在聊天源里恒为零次，
    融合时只能回退锚权重；而它们的竞争者（`大德` 之类）却被整词计数推高，
    排序就反了。切分口径与选词口径必须一致，否则统计的不是同一个东西。
    """
    words: dict[str, int] = {}
    if (directory.parent / "pinyin.dict.yaml").is_file():
        for path in effective_files(directory.parent):
            for word, _, weight in iter_entries(path):
                if len(word) > 1:
                    words[word] = max(words.get(word, 0), max(1, int(weight)))
        return words
    for table in ("base", "ext", "tencent", "others"):
        path = directory / f"{table}.dict.yaml"
        if not path.exists():
            continue
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                if line.startswith("#") or line.startswith("-"):
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 3 or not parts[0] or not parts[2].isdigit():
                    continue
                word, weight = parts[0], int(parts[2])
                if len(word) < 2:
                    continue  # 单字在 jieba 已有基础词频，交回默认词典更稳
                if weight > words.get(word, 0):
                    words[word] = weight
    return words


def build_userdict(directory: Path, cache: Path) -> tuple[Path, dict]:
    """把分表词表落成 jieba 用户词典文件，返回 (路径, 摘要)。"""
    words = read_lexicon(directory)
    if len(words) < 1000:
        raise SystemExit(f"{directory} 只读到 {len(words)} 个多字词，用户词典不可信")
    cache.parent.mkdir(parents=True, exist_ok=True)
    with cache.open("w", encoding="utf-8", newline="\n") as stream:
        for word, weight in sorted(words.items(), key=lambda kv: -kv[1]):
            stream.write(f"{word} {weight}\n")
    digest = hashlib.sha256(cache.read_bytes()).hexdigest()
    return cache, {"path": str(cache), "words": len(words), "sha256": digest}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", nargs="+", type=Path)
    parser.add_argument("--role", choices=("train", "evaluation", "diagnostic"), default="diagnostic")
    parser.add_argument("--workers", type=int, default=1,
                        help="有界分块并行数；按输入顺序合并，结果与单进程相同")
    parser.add_argument("--exclude-dialogs", nargs="*", type=Path, default=[],
                        help="按规范化对话摘要排除验证/测试内容；训练计数必须显式给出")
    parser.add_argument("--upstream-clone", type=Path,
                        default=REPO / "build/upstream/rime-frost")
    parser.add_argument("--userdict-anchor", type=Path, default=None,
                        help="用该目录的分表词表做 jieba 用户词典（建议传部署源 cn_dicts/）")
    parser.add_argument("--userdict-cache", type=Path, default=None,
                        help="用户词典落盘路径，默认与 --out 同目录 .userdict.txt")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--corpus-source", default="Caller-provided dialogue files; source and license require a separate manifest",
                        help="本批实际语料来源；非 LCCC 输入必须显式声明")
    args = parser.parse_args()
    if not 1 <= args.workers <= 16:
        parser.error("--workers 必须在 1..16")

    if args.role == "train" and not args.exclude_dialogs:
        parser.error("--role train 必须用 --exclude-dialogs 指定验证/测试语料")
    for path in args.corpus + args.exclude_dialogs:
        if not path.exists():
            raise SystemExit(f"语料文件不存在: {path}")
    inputs = [file_identity(p) for p in args.corpus]
    exclusions = [file_identity(p) for p in args.exclude_dialogs]
    source_hashes = {f["sha256"] for f in inputs}
    if len(source_hashes) != len(inputs) or source_hashes & {f["sha256"] for f in exclusions}:
        raise SystemExit("语料重复或训练输入与排除输入为同一文件")
    excluded = excluded_dialogs(args.exclude_dialogs)

    import jieba  # noqa: PLC0415 —— 切词是本模块的硬依赖，缺失直接失败

    userdict_info = None
    if args.userdict_anchor:
        cache = args.userdict_cache or args.out.with_suffix(".userdict.txt")
        path, userdict_info = build_userdict(args.userdict_anchor, cache)

    stats = {
        "dialogs": 0, "dialogs_read": 0, "dialogs_excluded": 0,
        "utterances": 0, "bad_lines": 0,
        "tokens_total": 0, "tokens_kept": 0,
        "chars_seen": set(),
        "sources": [p.name for p in args.corpus],
    }
    counts, token_stats = count_utterances(iter_utterances(args.corpus, stats, excluded),
                                          path if args.userdict_anchor else None, args.workers)
    stats.update(token_stats)

    if stats["bad_lines"]:
        raise SystemExit(f"语料含 {stats['bad_lines']} 条格式错误；不输出不完整计数")
    if ([file_identity(p) for p in args.corpus] != inputs or
            [file_identity(p) for p in args.exclude_dialogs] != exclusions):
        raise SystemExit("统计期间语料字节变化；不输出计数")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8", newline="\n") as stream:
        for word, count in counts.most_common():
            stream.write(f"{word}\t{count}\n")

    # 覆盖率诊断：上游词表里聊天语料零次的词。只列事实，不做删词判断。
    if args.userdict_anchor and (args.userdict_anchor.parent / "pinyin.dict.yaml").is_file():
        upstream = {}
        for table in effective_files(args.userdict_anchor.parent):
            for word, _, _ in iter_entries(table):
                upstream.setdefault(word, {})[table.stem] = 1
    else:
        upstream = read_upstream_words(args.upstream_clone)
    zero_hits = {w: tables for w, tables in upstream.items() if w not in counts}
    stats["chars_seen"] = len(stats["chars_seen"])
    stats["distinct_words"] = len(counts)
    stats["upstream_words"] = len(upstream)
    stats["upstream_zero_hit"] = len(zero_hits)
    summary = {
        "format_version": 2, "partition_role": args.role,
        "inputs": inputs, "excluded_inputs": exclusions,
        "excluded_dialog_hashes": len(excluded), "exclusion_rule": EXCLUSION_RULE,
        "counts": file_identity(args.out),
        "workers": args.workers,
        "corpus": args.corpus_source,
        "segmentation": f"detok(replace(' ','')) + jieba {jieba.__version__}, HMM=off",
        "userdict": userdict_info,
        "stats": stats,
    }
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    diag_path = args.summary.with_suffix(".zero-hit.tsv")
    with diag_path.open("w", encoding="utf-8", newline="\n") as stream:
        for word, tables in sorted(zero_hits.items()):
            stream.write(f"{word}\t{'+'.join(sorted(tables))}\n")

    print(f"词→计数: {args.out}（{len(counts)} 个词）")
    print(f"摘要: {args.summary}")
    print(f"上游零命中诊断: {diag_path}（{len(zero_hits)} 条，仅事实非删词判断）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
