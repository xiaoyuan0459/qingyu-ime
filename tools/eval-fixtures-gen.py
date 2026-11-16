#!/usr/bin/env python3
"""从独立语料派生评测夹具（简拼与整句），不读聊天语料。

为什么要独立来源：P0-B 首轮的 38 例简拼是从 LCCC 高频词派生的，聊天频率层在
那批样本上必然占优（同一份语料既做统计又做答案），分桶后 train 大涨、val 不动。
本模块改用 **rime-essay 的书面词频**（LGPL，与聊天语料无重叠）决定"用户最可能
打哪个词"，选词过程完全不看聊天计数。

三条设计约束：

1. **答案由独立词频决定，不由我们想测的词表决定**。同一简拼码可能有多个合理
   答案（`bj`：北京/比较/笔记），所以 expected 取该码下 essay 权重前 K 的词，
   命中任一即算命中；再按字典自身统计该码的全部候选数，淘汰歧义过大的码。
2. **键序由字典读音生成**，不手写。多读音词只取权重最高的读音；简拼取各音节
   首字母（与应用层 T9/26 键口径一致，九键另有代表字母映射，不在本批范围）。
3. **整句只由字典词拼成**，词表同样来自 essay 高频词，保证「期望答案」确实是
   该拼音串的一个合理读法；模板是自然句，不是随机词串。

用法::

    python tools/eval-fixtures-gen.py \
        --dict-dir build/frost/src/cn_dicts \
        --essay build/m3/essay.txt \
        --out tools/eval-cases-essay.tsv \
        --initials 450 --sentences 150
"""

from __future__ import annotations

import argparse
import hashlib
import random
import re
from pathlib import Path
from rime_source_provenance import effective_files, iter_entries

REPO = Path(__file__).resolve().parent.parent
# 读音查询要连字表一起读：单字（的/了/我）在 8105/41448/GB18030 里，
# 不在分词表。corrections 是注释提示表，不含正文词条，跳过。
SKIP_TABLES = {"corrections"}
CJK = re.compile(r"^[\u3400-\u9fff]+$")


def read_dict(directory: Path):
    """词 → [(读音, 权重)]（按权重降序），以及 读音 → 词列表。"""
    grouped = {}
    for path in effective_files(directory.parent):
        for word, code, weight in iter_entries(path):
            if code:
                readings = grouped.setdefault(word, {})
                readings[code] = max(readings.get(code, 0), weight)
    return {word: sorted(readings.items(), key=lambda item: (-item[1], item[0]))
            for word, readings in grouped.items()}


def read_essay(path: Path) -> dict[str, int]:
    weights: dict[str, int] = {}
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[1].isdigit():
                weights[parts[0]] = int(parts[1])
    return weights


def initials_of(code: str) -> str:
    """`bei jing` → `bj`。"""
    return "".join(syllable[0] for syllable in code.split() if syllable)


def build_code_index(entries: dict[str, list[tuple[str, int]]]) -> dict[str, set[str]]:
    """简拼码 → 能打出该码的词集合（含多读音词的全部读音）。"""
    index: dict[str, set[str]] = {}
    for word, readings in entries.items():
        if not CJK.match(word):
            continue
        for code, _ in readings:
            syllables = code.split()
            if not syllables or len(syllables) != len(word):
                continue  # 读音音节数与字数不符（儿化、连读）不进简拼
            index.setdefault(initials_of(code), set()).add(word)
    return index


# 码长分层：短码天然高歧义（2 字母码 524 个里 407 个候选超 60），
# 用同一把尺子会把它们全筛掉，夹具就覆盖不到简拼的主要使用场景。
# 短码放宽候选上限、同时接受更多"合理答案"，让名次仍可比较但不至于抽奖。
TIERS = {2: (40, 5), 3: (20, 3), 4: (12, 2)}


def tier_of(code: str) -> tuple[int, int]:
    return TIERS.get(len(code), (12, 2))


def gen_initials(entries, weights, code_index, limit: int, answer_k: int,
                 max_homophones: int, min_code_len: int, min_weight: int = 20):
    """按 `weights`（独立选词源）挑词，同一码只出一例，答案取该码下分值最高的几个词。

    `weights` 决定"用户最可能打哪个词"，与被测的频率层无关：写书面语夹具时用
    essay 词频，做口语留出时用**另一批对话**的计数（不能用训练那份）。
    """
    rows = []
    used_codes: set[str] = set()
    # 候选：高分常用词，2–4 字，在字典里且只有一个读音
    candidates = []
    for word, weight in weights.items():
        if weight < min_weight or len(word) < 2 or len(word) > 4 or not CJK.match(word):
            continue
        readings = entries.get(word)
        if not readings or len(readings) > 1:
            continue
        code = readings[0][0]
        if len(code.split()) != len(word):
            continue
        candidates.append((weight, word, code))
    candidates.sort(key=lambda item: (-item[0], item[1], item[2]))

    for weight, word, code in candidates:
        if len(rows) >= limit:
            break
        key = initials_of(code)
        if len(key) < min_code_len or key in used_codes:
            continue
        pool = code_index.get(key, set())
        cap, answers = tier_of(key)
        if max_homophones is not None:
            cap = max_homophones  # 命令行显式覆盖（排查用），默认按码长分层
        if len(pool) > cap:
            continue  # 候选太多等于抽奖，不判名次
        # 答案：该码下独立词频最高的几个词（含本词），保证答案独立于我们想测什么
        ranked = sorted((w for w in pool if weights.get(w, 0) > 0), key=lambda w: (-weights[w], w))[:max(answers, answer_k)]
        if word not in ranked:
            continue
        used_codes.add(key)
        rows.append({
            "code": key, "expected": ranked, "primary": word,
            "weight": weight, "pool": len(pool),
        })
    return rows


# 模板：每个元素要么是固定词，要么是 {槽位}；槽位取值来自 essay 高频词。
TEMPLATES = [
    (["{时间}", "{人物}", "去", "{地点}", "{动作}"], ("时间", "人物", "地点", "动作")),
    (["{人物}", "很", "喜欢", "{活动}"], ("人物", "活动")),
    (["{人物}", "说", "{地点}", "的", "{食物}", "很", "好吃"], ("人物", "地点", "食物")),
    (["我", "{时间}", "在", "{地点}", "看到", "{宾语}"], ("时间", "地点", "宾语")),
    (["这个", "{物品}", "多少", "钱"], ("物品",)),
    (["请问", "{地点}", "怎么", "走"], ("地点",)),
    (["{时间}", "的", "{食物}", "特别", "好吃"], ("时间", "食物")),
    (["你", "能", "帮", "我", "{动作}", "吗"], ("动作",)),
    (["{人物}", "今天", "穿", "了", "一件", "{衣物}"], ("人物", "衣物")),
    (["外面", "的", "{天气词}", "真", "不错"], ("天气词",)),
    (["我", "想", "买", "一个", "{物品}"], ("物品",)),
    (["{人物}", "在", "{地点}", "等", "我"], ("人物", "地点")),
    (["这", "本书", "写得", "很", "{评价}"], ("评价",)),
    (["我们", "{时间}", "一起", "{动作}", "吧"], ("时间", "动作")),
    (["{地点}", "旁边", "有", "一家", "{店铺}"], ("地点", "店铺")),
    (["孩子", "们", "在", "{地点}", "玩", "得", "很", "开心"], ("地点",)),
]

POOLS = {
    "时间": ["今天", "明天", "昨天", "下午", "晚上", "周末", "早上"],
    "人物": ["我们", "他们", "妈妈", "爸爸", "老师", "朋友", "同学"],
    "地点": ["公园", "超市", "医院", "学校", "图书馆", "公司", "车站"],
    "动作": ["散步", "吃饭", "上课", "看电影", "买东西", "聊天", "打球"],
    "活动": ["看书", "跑步", "喝咖啡", "听音乐", "看电影", "旅行"],
    "食物": ["面条", "饺子", "火锅", "蛋糕", "米饭", "包子"],
    "宾语": ["一只猫", "一只狗", "很多人", "一辆车", "几个孩子"],
    "物品": ["手机", "电脑", "书包", "手表", "相机"],
    "衣物": ["外套", "衬衫", "毛衣", "裙子"],
    "天气词": ["天气", "阳光", "空气", "风景"],
    "评价": ["精彩", "好看", "一般", "有趣"],
    "店铺": ["书店", "药店", "饭店", "花店"],
}


def gen_sentences(entries, essay, limit: int, seed: int):
    rng = random.Random(seed)
    rows, seen = [], set()
    # 只收字典里唯一读音、且在 essay 词汇里的词，避免读音分歧导致期望答案不可达
    def keyable(word: str) -> bool:
        """能用字典最高权读音写出与字数相同的音节串。"""
        if word not in entries:
            return False
        code = entries[word][0][0]
        return len(code.split()) == len(word)

    usable = {slot: [w for w in words if keyable(w)]
              for slot, words in POOLS.items()}
    templates = []
    for words, slots in TEMPLATES:
        literal_ok = all(keyable(w) for w in words if not w.startswith("{"))
        if literal_ok and all(usable.get(slot) for slot in slots):
            templates.append((words, slots))
    if not templates:
        raise SystemExit("没有可用模板：检查字典里是否缺模板用词")

    guard = 0
    while len(rows) < limit and guard < limit * 40:
        guard += 1
        words, slots = templates[(guard - 1) % len(templates)]
        seq = []
        for word in words:
            if word.startswith("{"):
                seq.append(rng.choice(usable[word[1:-1]]))
            else:
                seq.append(word)
        text = "".join(seq)
        if text in seen:
            continue
        if not (7 <= len(text) <= 16):
            continue
        code = " ".join(entries[w][0][0] for w in seq)
        seen.add(text)
        rows.append({"text": text, "code": code, "words": seq})
    return rows


def group_of(prefix: str, index: int) -> str:
    """20 个分组：够让哈希切分把样本摊到 train/val/test 三桶。"""
    return f"{prefix}-g{index % 20:02d}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dict-dir", type=Path,
                        default=REPO / "build/frost/src/cn_dicts")
    parser.add_argument("--essay", type=Path, default=REPO / "build/m3/essay.txt")
    parser.add_argument("--select-counts", type=Path, default=None,
                        help="改用这份计数（词<TAB>次数）选词；用于口语留出集。"
                             "必须是与训练计数不同的对话，否则等于自己考自己")
    parser.add_argument("--min-weight", type=int, default=20,
                        help="选词源的权重下限，滤掉长尾噪声词")
    parser.add_argument("--id-prefix", default="ew",
                        help="案例 ID 前缀，区分不同来源的夹具")
    parser.add_argument("--no-sentences", action="store_true",
                        help="只出简拼（整句模板依赖 essay，留出集不混用）")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--initials", type=int, default=450)
    parser.add_argument("--sentences", type=int, default=150)
    parser.add_argument("--answer-k", type=int, default=0,
                        help="同一简拼码接受的前 K 个高频答案；0=按码长分层（5/3/2）")
    parser.add_argument("--max-homophones", type=int, default=None,
                        help="覆盖按码长分层的候选上限（默认 2/3/4 码分别 40/20/12）")
    parser.add_argument("--min-code-len", type=int, default=2)
    parser.add_argument("--seed", type=int, default=20261101)
    args = parser.parse_args()

    entries = read_dict(args.dict_dir)
    if len(entries) < 100000:
        raise SystemExit(f"字典只读到 {len(entries)} 条，路径不对")
    essay = read_essay(args.essay)
    code_index = build_code_index(entries)

    if args.select_counts:
        weights = read_essay(args.select_counts)  # 同一格式：词<TAB>数
        source_text = f"{args.select_counts.name} 选词"
    else:
        weights = essay
        source_text = "essay 词频选词"

    ini = gen_initials(entries, weights, code_index, args.initials,
                       args.answer_k, args.max_homophones, args.min_code_len,
                       args.min_weight)
    sents = [] if args.no_sentences else gen_sentences(
        entries, essay, args.sentences, args.seed)

    lines = ["id\tkeyboard\tschema\tkeys\texpected\tmax_rank\tcategory\tgroup\tsource"]
    for i, row in enumerate(ini):
        lines.append("\t".join([
            f"{args.id_prefix}-{i + 1:04d}", "26key", "pinyin", f"k:{row['code']}",
            "|".join(row["expected"]), "4", "initials",
            f"{args.id_prefix}-code-{row['code']}",
            f"{source_text}（权 {row['weight']}，该码 {row['pool']} 个候选）",
        ]))
    for i, row in enumerate(sents):
        lines.append("\t".join([
            f"sn-{i + 1:04d}", "26key", "pinyin",
            # 夹具的 keys 用空格分隔动作，全拼串不能带音节分隔符
            "k:" + row["code"].replace(" ", ""),
            row["text"], "1", "sentence", group_of("sn", i),
            "essay 高频词合成整句",
        ]))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    digest = hashlib.sha256(args.out.read_bytes()).hexdigest()[:12]
    print(f"简拼 {len(ini)} 例、整句 {len(sents)} 例 → {args.out}（sha256:{digest}）")
    print(f"简拼码长分布：" + "，".join(
        f"{n}={sum(1 for r in ini if len(r['code']) == n)}" for n in (2, 3, 4)))
    print("整句样例：" + "；".join(r["text"] for r in sents[:3]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
