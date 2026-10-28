#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""码空间工具：为 qingyu_probe 生成探测码表，并对新旧探测结果做差分。

背景（见 imesdk/src/main/cpp/qingyu_rime/probe.cc 头部）：继承的 schema 里
`speller/algebra` 被剥掉了，仓库内也没有双拼/九宫格码表，所以「重编 prism 后
码空间是否不变」必须靠差分证明，不能靠肉眼。

子命令：
  gen-double  <out>            双拼：a-z 的全部 1..2 长度组合（734 个码）
  gen-t9      <out>            九宫格：字母键码 A D G J M P T W 的 1..6 长度组合
  gen-pinyin  <syl.tsv> <out>  全拼：以音节表为种子，扩散出简拼/纠错假设码
  diff        <old.tsv> <new.tsv>
"""

import itertools
import sys

# 九宫格每键取首字母后的键码集合（M2 已用 T9PinYinUtils.pinyinMap 校验过，
# 437 个拼音 0 处不一致）。送进引擎的是大写字母，不是数字。
T9_KEYS = "ADGJMPTW"

# 拼音声母；zh/ch/sh 双字母，必须在单字母之前匹配
INITIALS = ["zh", "ch", "sh"] + list("bpmfdtnlgkhjqxrzcsyw")

ALPHABET = [chr(c) for c in range(ord("a"), ord("z") + 1)]


def write_codes(path, codes):
    codes = sorted(set(codes))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(codes) + "\n")
    print(f"{path}: {len(codes)} 个码")


def gen_double(out):
    codes = list(ALPHABET)
    codes += [a + b for a, b in itertools.product(ALPHABET, repeat=2)]
    write_codes(out, codes)


def gen_t9(out):
    codes = []
    for n in range(1, 7):
        codes += ["".join(c) for c in itertools.product(T9_KEYS, repeat=n)]
    write_codes(out, codes)


def split_syllable(syl):
    """把音节切成 (声母, 韵母)；零声母时声母为空串。"""
    for initial in INITIALS:
        if syl.startswith(initial):
            return initial, syl[len(initial):]
    return "", syl


def gen_pinyin(syllabary_tsv, out):
    """探测集必须**同时覆盖**新旧两份棱镜的码空间，差分才有意义。

    以音节表为种子扩散出四类假设码：完整音节、简拼（前缀）、
    单点纠错（替换/删除/插入）、相邻换位。这是 410 个音节量级下能廉价穷举的
    上界，不含多点组合（那种规则真实方案里几乎不用）。
    """
    syllables = set()
    with open(syllabary_tsv, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[1]:
                syllables.add(parts[1])
    print(f"音节表：{len(syllables)} 个音节")

    codes = set()
    for syl in syllables:
        codes.add(syl)
        for n in range(1, len(syl)):  # 简拼：任意前缀
            codes.add(syl[:n])
        for i, ch in enumerate(syl):  # 单点替换
            for alt in ALPHABET:
                codes.add(syl[:i] + alt + syl[i + 1:])
            codes.add(syl[:i] + syl[i + 1:])  # 单点删除
        for i in range(len(syl) + 1):  # 单点插入
            for alt in ALPHABET:
                codes.add(syl[:i] + alt + syl[i:])
        for i in range(len(syl) - 1):  # 相邻换位
            codes.add(syl[:i] + syl[i + 1] + syl[i] + syl[i + 2:])

    write_codes(out, codes)


def load_tsv(path):
    result = {}
    header = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("#"):
                key, _, value = line[1:].partition("\t")
                header[key] = value
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                # 解释按集合比较：同一组音节的列出顺序取决于词库条目顺序，
                # 不是行为差异（差分要抓的是「哪些码可用、解析成什么」）。
                exps = frozenset(e for e in parts[2].split("|") if e) if len(parts) > 2 else frozenset()
                result[parts[0]] = (parts[1], exps)
    return header, result


def render(code, value):
    return f"{code}\t{','.join(sorted(x.split(':')[0] + ':' + x.split(':')[1] for x in value[1]))}"


def diff(old_path, new_path):
    old_head, old = load_tsv(old_path)
    new_head, new = load_tsv(new_path)
    print(f"旧：{len(old)} 码 / hit {sum(1 for v in old.values() if v[0] == 'hit')}"
          f"  dict_checksum={old_head.get('prism_dict_checksum')}")
    print(f"新：{len(new)} 码 / hit {sum(1 for v in new.values() if v[0] == 'hit')}"
          f"  dict_checksum={new_head.get('prism_dict_checksum')}")

    old_hits = {k for k, v in old.items() if v[0] == "hit"}
    new_hits = {k for k, v in new.items() if v[0] == "hit"}

    lost = sorted(old_hits - new_hits)
    added = sorted(new_hits - old_hits)
    print(f"\n回归（旧有新无）：{len(lost)}")
    for code in lost[:200]:
        print("  -" + render(code, old[code]))
    if len(lost) > 200:
        print(f"  …共 {len(lost)} 条")
    print(f"多出的码（新有旧无）：{len(added)}")
    for code in added[:200]:
        print("  +" + render(code, new[code]))
    if len(added) > 200:
        print(f"  …共 {len(added)} 条")

    changed = [k for k in old_hits & new_hits if old[k][1] != new[k][1]]
    print(f"解释不同（同一码解析出的音节集合变了）：{len(changed)}")
    for code in changed[:100]:
        print(f"  ~{code}\t旧={render(code, old[code])}\t新={render(code, new[code])}")

    return 0 if not (lost or added or changed) else 1


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    if cmd == "gen-double" and len(argv) == 3:
        gen_double(argv[2])
    elif cmd == "gen-t9" and len(argv) == 3:
        gen_t9(argv[2])
    elif cmd == "gen-pinyin" and len(argv) == 4:
        gen_pinyin(argv[2], argv[3])
    elif cmd == "diff" and len(argv) == 4:
        return diff(argv[2], argv[3])
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
