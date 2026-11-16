"""有界流式分词：按输入顺序合并块，单/多进程的计数和同频顺序一致。"""

from collections import Counter, deque
from concurrent.futures import ProcessPoolExecutor
import logging
import re


_CJK = re.compile(r"[\u3400-\u9fff\uf900-\ufaff]")
_TOKENIZER = None


def detokenize(text: str) -> str:
    return text.replace(" ", "")


def keep_token(token: str) -> bool:
    return bool(token and not token.isspace() and not token.isascii() and _CJK.search(token))


def initialize(userdict):
    import jieba
    global _TOKENIZER
    jieba.setLogLevel(logging.WARNING)
    _TOKENIZER = jieba.Tokenizer()
    _TOKENIZER.initialize()
    if userdict:
        _TOKENIZER.load_userdict(userdict)


def count_chunk(chunk):
    counts, chars = Counter(), set()
    total = kept = 0
    for text in chunk:
        for token in _TOKENIZER.cut(detokenize(text), HMM=False):
            total += 1
            if keep_token(token):
                counts[token] += 1
                kept += 1
                if len(token) == 1:
                    chars.add(token)
    return counts, {"utterances": len(chunk), "tokens_total": total,
                    "tokens_kept": kept, "chars_seen": chars}


def count_utterances(utterances, userdict=None, workers=1):
    counts = Counter()
    stats = {"utterances": 0, "tokens_total": 0, "tokens_kept": 0,
             "chars_seen": set(), "bad_lines": 0}

    def chunks():
        chunk = []
        for text, bad in utterances:
            if bad:
                stats["bad_lines"] += 1
                continue
            chunk.append(text)
            if len(chunk) == 2000:
                yield chunk
                chunk = []
        if chunk:
            yield chunk

    def merge(result):
        batch, metrics = result
        counts.update(batch)
        for key in ("utterances", "tokens_total", "tokens_kept"):
            stats[key] += metrics[key]
        stats["chars_seen"].update(metrics["chars_seen"])
        if stats["utterances"] % 500000 == 0:
            print(f"counted utterances: {stats['utterances']}", flush=True)

    if workers == 1:
        initialize(str(userdict) if userdict else None)
        for chunk in chunks():
            merge(count_chunk(chunk))
    else:
        with ProcessPoolExecutor(max_workers=workers, initializer=initialize,
                                 initargs=(str(userdict) if userdict else None,)) as pool:
            pending = deque()
            for chunk in chunks():
                pending.append(pool.submit(count_chunk, chunk))
                if len(pending) >= workers * 2:
                    merge(pending.popleft().result())
            while pending:
                merge(pending.popleft().result())
    return counts, stats
