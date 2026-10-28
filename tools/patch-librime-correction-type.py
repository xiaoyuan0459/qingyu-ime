#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给 vendored librime 增加 `correct` 拼写代数规则。

背景：相邻键纠错表若用 `derive`（kNormalSpelling），纠错拼写会和精确拼写同权，
`yuanl` 会被切成 `yua'nl`（华丽）而不是 `yuan'l`（原来）；用 `fuzz` 也不够，因为
kFuzzySpelling 仍排在 kAbbreviation 之前，精确路径里的简拼 `l` 反而更差。

本补丁新增 `kCorrectionSpelling`（排在 kAbbreviation 之后）与 `correct/x/X/` 规则。
音节图在同一位置存在正常/简拼路径时会剪掉纠错边；没有任何正常路径时纠错边保留，
因此 `xhong → 中` 仍然有效。只修改 vendored 的 librime 源码，脚本幂等，构建前由
CMake 调用；改动范围见 docs/adr/0007-纠错切分权重.md。
"""

from __future__ import annotations

import sys
from pathlib import Path

MARKER = "kCorrectionSpelling"


def fail(message: str) -> None:
    print(f"librime correction patch: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"找不到 {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def patch_spelling(algo: Path) -> None:
    path = algo / "spelling.h"
    text = read(path)
    if MARKER in text:
        return
    anchor = "  kAbbreviation,\n  kCompletion,"
    if anchor not in text:
        fail("spelling.h 的 SpellingType 枚举锚点不匹配")
    write(path, text.replace(anchor, "  kAbbreviation,\n  kCorrectionSpelling,\n  kCompletion,", 1))


def patch_calculus_header(algo: Path) -> None:
    path = algo / "calculus.h"
    text = read(path)
    if "class Correction" in text:
        return
    anchor = """// abbrev/zyx/z/
class Abbreviation : public Derivation {
 public:
  static Factory Parse;
  bool Apply(Spelling* spelling);
};"""
    replacement = anchor + """

// correct/x/X/
// 相邻键纠错拼写。独立类型放在 ambiguous 之后、invalid 之前，不改旧 prism 的
// completion/ambiguous 数值；切分策略按类型相等识别，存在精确/简拼路径时剪掉纠错边。
class Correction : public Derivation {
 public:
  static Factory Parse;
  bool Apply(Spelling* spelling);
};"""
    if anchor not in text:
        fail("calculus.h 的 Abbreviation 锚点不匹配")
    write(path, text.replace(anchor, replacement, 1))


def patch_calculus_source(algo: Path) -> None:
    path = algo / "calculus.cc"
    text = read(path)
    if "Correction::Parse" in text:
        return
    penalty_anchor = "const double kFuzzySpellingPenalty = -0.6931471805599453;  // log(0.5)"
    if penalty_anchor not in text:
        fail("calculus.cc 的 penalty 锚点不匹配")
    text = text.replace(
        penalty_anchor,
        penalty_anchor + "\nconst double kCorrectionSpellingPenalty = -0.6931471805599453;  // log(0.5)",
        1,
    )
    register_anchor = '  Register("abbrev", &Abbreviation::Parse);'
    if register_anchor not in text:
        fail("calculus.cc 的 Register 锚点不匹配")
    text = text.replace(register_anchor, register_anchor + '\n  Register("correct", &Correction::Parse);', 1)
    tail = """bool Abbreviation::Apply(Spelling* spelling) {
  bool result = Transformation::Apply(spelling);
  if (result) {
    spelling->properties.type = kAbbreviation;
    spelling->properties.credibility += kAbbreviationPenalty;
  }
  return result;
}

}  // namespace rime"""
    replacement = """bool Abbreviation::Apply(Spelling* spelling) {
  bool result = Transformation::Apply(spelling);
  if (result) {
    spelling->properties.type = kAbbreviation;
    spelling->properties.credibility += kAbbreviationPenalty;
  }
  return result;
}

// Correction

Calculation* Correction::Parse(const vector<string>& args) {
  if (args.size() < 3)
    return NULL;
  const string& left(args[1]);
  const string& right(args[2]);
  if (left.empty())
    return NULL;
  the<Correction> x(new Correction);
  x->pattern_.assign(left);
  x->replacement_.assign(right);
  return x.release();
}

bool Correction::Apply(Spelling* spelling) {
  bool result = Transformation::Apply(spelling);
  if (result) {
    spelling->properties.type = kCorrectionSpelling;
    spelling->properties.credibility += kCorrectionSpellingPenalty;
  }
  return result;
}

}  // namespace rime"""
    if tail not in text:
        fail("calculus.cc 的 namespace 尾部锚点不匹配")
    write(path, text.replace(tail, replacement, 1))


def patch_algebra_dump(algo: Path) -> None:
    path = algo / "algebra.cc"
    text = read(path)
    if '"-ac?!XC"' in text:
        return
    if '"-ac?!"' not in text:
        fail("algebra.cc 的 Dump 类型字符锚点不匹配")
    write(path, text.replace('"-ac?!"', '"-ac?!XC"', 1))


def patch_syllabifier(algo: Path) -> None:
    path = algo / "syllabifier.cc"
    text = read(path)
    if "HasNormalFirstPath" in text:
        return
    helper_anchor = (
        "const double kCorrectionCredibility = -4.605170185988091;"
        "  // log(0.01)\n"
    )
    helper = """const double kCorrectionCredibility = -4.605170185988091;  // log(0.01)

namespace {

bool CanReachEnd(const SyllableGraph& graph, size_t pos, size_t end) {
  if (pos == end)
    return true;
  auto start = graph.edges.find(pos);
  if (start == graph.edges.end())
    return false;
  for (const auto& edge : start->second) {
    bool traversable = false;
    for (const auto& spelling : edge.second) {
      if (spelling.second.type != kCorrectionSpelling &&
          !spelling.second.is_correction) {
        traversable = true;
        break;
      }
    }
    if (traversable && CanReachEnd(graph, edge.first, end))
      return true;
  }
  return false;
}

bool HasNormalFirstPath(const SyllableGraph& graph, size_t end) {
  auto start = graph.edges.find(0);
  if (start == graph.edges.end())
    return false;
  for (const auto& edge : start->second) {
    bool full_syllable = false;
    for (const auto& spelling : edge.second) {
      if (spelling.second.type == kNormalSpelling &&
          !spelling.second.is_correction) {
        full_syllable = true;
        break;
      }
    }
    if (full_syllable && CanReachEnd(graph, edge.first, end))
      return true;
  }
  return false;
}

}  // namespace
"""
    if helper_anchor not in text:
        fail("syllabifier.cc 的 credibility 锚点不匹配")
    text = text.replace(helper_anchor, helper, 1)

    old = """      // remove disqualified syllables (eg. matching abbreviated spellings)
      // when there is a path of more favored type
      SpellingType edge_type = kInvalidSpelling;
      for (auto k = j->second.begin(); k != j->second.end();) {
        if (k->second.is_correction) {
          ++k;
          continue;  // Don't care correction edges
        }
        if (k->second.type > last_type) {
          j->second.erase(k++);
        } else {
          if (k->second.type < edge_type)
            edge_type = k->second.type;
          ++k;
        }
      }"""
    new = """      // remove disqualified syllables (eg. matching abbreviated spellings)
      // when there is a path of more favored type
      SpellingType edge_type = kInvalidSpelling;
      for (auto k = j->second.begin(); k != j->second.end();) {
        if (k->second.is_correction || k->second.type == kCorrectionSpelling) {
          // 纠错边的去留由 BuildSyllableGraph 末尾的统一策略决定：
          // 本码有以完整音节开头的路径时整段移除，否则降为正常拼写参与排序。
          ++k;
          continue;
        }
        if (k->second.type > last_type) {
          j->second.erase(k++);
        } else {
          if (k->second.type < edge_type)
            edge_type = k->second.type;
          ++k;
        }
      }"""
    if old not in text:
        fail("syllabifier.cc 的剪枝块锚点不匹配")
    text = text.replace(old, new, 1)

    policy_anchor = """  graph->input_length = input.length();
  graph->interpreted_length = farthest;"""
    policy = """  // 纠错切分的统一策略：如果本码存在一条「首段是完整音节、后续不含纠错」的通路，
  // 就整段丢掉纠错拼写（yuanl → yuan'l）；否则把纠错降为正常拼写参与排序，
  // 让 xhong/shong/wpman 这类只能靠纠错的输入仍按词频正常出候选。
  const bool exact_path_exists = HasNormalFirstPath(*graph, farthest);
  for (auto& start : graph->edges) {
    for (auto edge = start.second.begin(); edge != start.second.end();) {
      auto& spellings = edge->second;
      for (auto spelling = spellings.begin(); spelling != spellings.end();) {
        if (spelling->second.type != kCorrectionSpelling &&
            !spelling->second.is_correction) {
          ++spelling;
          continue;
        }
        if (exact_path_exists) {
          spellings.erase(spelling++);
        } else {
          spelling->second.type = kNormalSpelling;
          spelling->second.credibility = 0.0;
          ++spelling;
        }
      }
      if (spellings.empty()) {
        edge = start.second.erase(edge);
      } else {
        ++edge;
      }
    }
  }
  if (!exact_path_exists) {
    for (auto& vertex : graph->vertices) {
      if (vertex.second == kCorrectionSpelling)
        vertex.second = kNormalSpelling;
    }
  }

  graph->input_length = input.length();
  graph->interpreted_length = farthest;"""
    if policy_anchor not in text:
        fail("syllabifier.cc 的 input_length 锚点不匹配")
    write(path, text.replace(policy_anchor, policy, 1))


def main() -> int:
    if len(sys.argv) != 2:
        fail("用法: patch-librime-correction-type.py <librime根目录>")
    root = Path(sys.argv[1])
    algo = root / "src" / "rime" / "algo"
    if not algo.is_dir():
        fail(f"{algo} 不存在")
    patch_spelling(algo)
    patch_calculus_header(algo)
    patch_calculus_source(algo)
    patch_algebra_dump(algo)
    patch_syllabifier(algo)
    print("librime correction patch: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
