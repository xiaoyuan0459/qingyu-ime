"""Build a bounded, read-only text-prefix index from the selected public dictionaries.

Format (big endian): QYAS0001, u32 count, count * (u32 codepoint, u64 offset,
u32 byteLength), then UTF-8 newline-delimited words. No user data is included.
"""
import os
import re
import struct

LIMIT = 240
MAGIC = b"QYAS0001"


def build_associations(source, destination, dictionary="pinyin.dict.yaml"):
    with open(os.path.join(source, dictionary), encoding="utf-8") as stream:
        header = stream.read().split("...", 1)[0]
    tables = re.findall(r"^\s+-\s+([\w/-]+)", header, re.M)
    if not tables:
        tables = [dictionary.removesuffix(".dict.yaml")]
    groups = {}
    for table in tables:
        path = os.path.join(source, table + ".dict.yaml")
        with open(path, encoding="utf-8-sig") as stream:
            in_entries = False
            for line in stream:
                if not in_entries:
                    in_entries = line.strip() == "..."
                    continue
                fields = line.rstrip("\r\n").split("\t")
                if len(fields) < 2:
                    continue
                word = fields[0]
                if len(word) < 2 or "\n" in word or len(word.encode("utf-8")) > 400:
                    continue
                point = ord(word[0])
                if not (0x3400 <= point <= 0x9fff or 0x20000 <= point <= 0x323af):
                    continue
                try:
                    weight = float(fields[2]) if len(fields) > 2 else 0.0
                except ValueError:
                    weight = 0.0
                group = groups.setdefault(point, {})
                if word not in group:
                    group[word] = weight
                if len(group) > LIMIT * 2:
                    groups[point] = dict(sorted(group.items(), key=lambda x: (-x[1], x[0]))[:LIMIT])
    payloads = []
    for point, group in sorted(groups.items()):
        words = sorted(group, key=lambda word: (-group[word], word))[:LIMIT]
        payloads.append((point, "\n".join(words).encode("utf-8")))
    offset = 12 + 16 * len(payloads)
    with open(destination, "wb") as stream:
        stream.write(MAGIC)
        stream.write(struct.pack(">I", len(payloads)))
        for point, data in payloads:
            stream.write(struct.pack(">IQI", point, offset, len(data)))
            offset += len(data)
        for _, data in payloads:
            stream.write(data)
    print(f"联想索引：{len(payloads):,} 个字头，{offset / 1024 / 1024:.1f} MB；每字最多 {LIMIT} 词")
