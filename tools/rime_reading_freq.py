"""读音频率注入的写入边界；装配与独立 CLI 共用，检查通过后才改文件。"""

from pathlib import Path
import math

from rime_freq import FREQ_TABLES
from rime_source_provenance import active_frequency_paths


def apply_reading_table(directory: Path, freq: Path, min_hit_rate: float = 1.0) -> dict:
    wanted = {}
    for lineno, line in enumerate(freq.read_text(encoding="utf-8").splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if (len(parts) != 3 or not parts[0] or not parts[1] or
                not parts[2].isascii() or not parts[2].isdigit()):
            raise SystemExit(f"读音覆盖表第 {lineno} 行格式错误；写入前拒绝注入")
        key = (parts[0], parts[1])
        if key in wanted:
            raise SystemExit(f"读音覆盖表第 {lineno} 行键重复；写入前拒绝注入")
        wanted[key] = parts[2]
    if not wanted:
        raise SystemExit(f"覆盖表为空: {freq}")
    hit_keys, staged, maxima = set(), [], {}
    for path in active_frequency_paths(directory, FREQ_TABLES):
        rows = path.read_text(encoding="utf-8").splitlines()
        for line in rows:
            if line.startswith(("#", "-")):
                continue
            parts = line.split("\t")
            if len(parts) < 3 or (parts[0], parts[1]) not in wanted:
                continue
            key = (parts[0], parts[1])
            try:
                weight = float(parts[2])
            except ValueError:
                raise SystemExit(f"源表 {path.name} 的目标权重不是数值；拒绝注入") from None
            if not math.isfinite(weight) or weight < 0:
                raise SystemExit(f"源表 {path.name} 的目标权重无效；拒绝注入")
            maxima[key] = max(maxima.get(key, 0), weight)
            hit_keys.add(key)
        staged.append((path, rows))
    rate = len(hit_keys) / len(wanted)
    if rate < min_hit_rate:
        raise SystemExit(f"命中率 {rate:.1%} 低于门槛 {min_hit_rate:.0%}；"
                         "覆盖表与分表口径不符，写入前拒绝注入")
    updates, preserved_zero = [], 0
    for path, rows in staged:
        changed = 0
        for i, line in enumerate(rows):
            if line.startswith(("#", "-")):
                continue
            parts = line.split("\t")
            if len(parts) < 3 or (parts[0], parts[1]) not in wanted:
                continue
            key = (parts[0], parts[1]); weight = float(parts[2])
            if weight == 0:
                preserved_zero += 1
                continue
            scaled = weight * int(wanted[key]) / maxima[key]
            value = max(1, round(scaled)) if weight.is_integer() else scaled
            if value != weight:
                parts[2] = str(value)
                rows[i] = "\t".join(parts); changed += 1
        if changed:
            updates.append((path, "\n".join(rows) + "\n", changed))
    for path, content, _ in updates:
        path.write_text(content, encoding="utf-8", newline="\n")
    return {"wanted": len(wanted), "hit_keys": len(hit_keys), "hit_rate": rate,
            "preserved_zero_rows": preserved_zero,
            "duplicate_policy": "scale each row relative to the source maximum for its word/reading",
            "rewritten_rows": sum(count for _, _, count in updates),
            "tables": [path.stem for path, _, _ in updates]}
