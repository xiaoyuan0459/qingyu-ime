#include "t9_key_recovery.h"
#include "t9_keys.h"
#include <rime/algo/syllabifier.h>
#include <rime/candidate.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/table.h>
#include <rime/gear/translator_commons.h>
#include <algorithm>
#include <cmath>
#include <unordered_set>

namespace {
// 音节键形最长 6 键（`zhuang` → `WAAMG`）。
constexpr size_t kMaxKeyLength = 6;
// 词条最多 4 个音节（`ScriptSyllabifier::BuildExactSyllableGraph` 也是这个上限）。
constexpr size_t kMaxSyllables = 4;
// 九键一次只补少量：把原解释挤下去太多，用户会以为分词错了。
constexpr size_t kMaxCandidates = 3;
// 参与合并的原候选数；后面的不重排，避免动到句子流的长尾。
constexpr size_t kPeek = 30;
// 单次查询最多枚举多少个候选切分；每个只做一次词库精确查询（不建候选）。
constexpr size_t kMaxGuesses = 96;
// 漏键恢复（T01）每段保留的切分条数，沿用冻结时的取值。
constexpr size_t kSegments = 4;
// 重复键恢复（T02）每段保留的切分条数。九键的键形歧义远大于拼音：`AT` 同时是 `at`/`bu`、
// `PG` 同时是 `pi`/`qi`，三音节输入的正确组合很容易被 4 条上限挤出（`DTGATPG` 就是这样
// 查不到「对不起」）。T01 冻在 4 条不动，避免放宽后多出的生僻候选挤掉正常输入。
constexpr size_t kRepeatSegments = 16;
// 重复键恢复的最短输入。4 键输入里叠键占掉一半，与「缩拼码」无法区分（`AWWT` 是
// 操作系统的简拼，删掉一个 W 会变成「阿武」）；实测 150 例真重复键全部 >= 5 键。
constexpr size_t kMinRepeatKeys = 5;
// 词条概率 p = exp(log_weight)，候选质量是 initial_quality + p。这里的罚分就是
// 「这条解释要多常见才值得盖过精确解释」的阈值：p > 5e-4（权重约 > -7.6）才浮到
// 同码精确候选之前，否则排在参与合并的 30 条之后。取固定名次会在常见词上漏、
// 在生僻词上乱插，用概率阈值才随词频连续变化。
constexpr double kPenalty = 5e-4;

bool ChineseWord(const std::string& text) {
  const auto length =
      std::count_if(text.begin(), text.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; });
  return length >= 2 && length <= 4 &&
      std::none_of(text.begin(), text.end(), [](unsigned char c) { return c < 0x80; });
}

// 九键送进来的是每键首字母的大写键码；小写、分隔符与数字串都说明不是这条路径。
bool AllUpperKeys(const std::string& input) {
  return std::all_of(input.begin(), input.end(), [](char c) { return c >= 'A' && c <= 'Z'; });
}
}  // namespace

void T9KeyRecovery::Prepare(rime::Dictionary* dictionary, CanonicalSyllabary& syllabary) {
  if (!dictionary || !dictionary->loaded() || !dictionary->primary_table()) return;
  if (dictionary_ == dictionary) return;
  dictionary->primary_table()->PrepareSyllableLookup();
  syllabary.Prepare(dictionary);
  const auto& syllables = syllabary.syllables();
  std::vector<std::string> forms(syllables.size());
  std::unordered_map<std::string, std::vector<int32_t>> exact;
  std::unordered_map<std::string, std::vector<int32_t>> missing;
  size_t max_keys = 1;
  for (size_t id = 0; id < syllables.size(); ++id) {
    const auto& spelling = syllables[id];
    if (spelling.empty()) continue;
    auto keys = EncodeKeys(spelling);
    if (keys.empty() || keys.size() > kMaxKeyLength) continue;
    max_keys = std::max(max_keys, keys.size());
    forms[id] = keys;
    exact[keys].push_back(static_cast<int32_t>(id));
    // 单键音节（a/e/o）删掉一个键就是空串，没有「漏按」可言。
    for (size_t i = 0; keys.size() > 1 && i < keys.size(); ++i)
      missing[keys.substr(0, i) + keys.substr(i + 1)].push_back(static_cast<int32_t>(id));
  }
  spellings_ = syllables;
  key_forms_ = std::move(forms);
  exact_ = std::move(exact);
  missing_ = std::move(missing);
  max_keys_ = max_keys;
  dictionary_ = dictionary;
}

std::vector<rime::Code> T9KeyRecovery::Segments(const std::string& input, size_t from,
                                                size_t to, size_t limit) const {
  std::vector<std::vector<rime::Code>> paths(to - from + 1);
  paths[0].push_back({});
  for (size_t pos = from; pos < to; ++pos) {
    const auto& current = paths[pos - from];
    if (current.empty()) continue;
    // 从长到短：九键的 `a`/`e`/`o` 都是单键音节，按短到长会先切出一串单键噪声。
    for (size_t length = std::min(max_keys_, to - pos); length >= 1; --length) {
      auto found = exact_.find(input.substr(pos, length));
      if (found == exact_.end()) continue;
      auto& bucket = paths[pos - from + length];
      for (auto id : found->second)
        for (const auto& path : current) {
          if (bucket.size() >= limit) break;
          auto next = path;
          next.push_back(id);
          bucket.push_back(std::move(next));
        }
    }
  }
  return std::move(paths[to - from]);
}

rime::an<rime::DictEntry> T9KeyRecovery::ExactEntry(const rime::Code& code,
                                                    const std::string& spelling) const {
  if (!dictionary_ || code.empty() || spelling.empty()) return nullptr;
  // 按音节序列自己搭一张精确音节图：graph.edges 的节点地址稳定，indices 只存指针。
  rime::SyllableGraph graph;
  graph.input_length = graph.interpreted_length = spelling.size();
  size_t start = 0;
  for (auto id : code) {
    if (id < 0 || static_cast<size_t>(id) >= spellings_.size()) return nullptr;
    const auto& syllable = spellings_[id];
    const size_t end = start + syllable.size();
    auto& props = graph.edges[start][end][id];
    props.end_pos = end;
    graph.vertices[end] = rime::kNormalSpelling;
    graph.indices[start][id].push_back(&props);
    start = end;
  }
  auto found = dictionary_->Lookup(graph, 0, false);
  if (!found) return nullptr;
  auto stream = found->find(spelling.size());
  if (stream == found->end() || stream->second.exhausted()) return nullptr;
  return stream->second.Peek();
}

rime::an<rime::DictEntry> T9KeyRecovery::LiteralEntry(
    const std::vector<rime::Code>& paths) const {
  rime::an<rime::DictEntry> best;
  for (const auto& path : paths) {
    if (path.empty() || path.size() > kMaxSyllables) continue;
    std::string spelling;
    for (auto id : path) spelling += spellings_[id];
    auto entry = ExactEntry(path, spelling);
    if (entry && (!best || entry->weight > best->weight)) best = std::move(entry);
  }
  return best;
}

// 原串已有的「漏一键」解释里最高的词频。扫描会消费候选流，所以把扫过的部分重新接回去，
// 顺序与身份都不变。词条 weight 是对数概率（多为负），没有解释时返回 -1e9 而不是 0。
double T9KeyRecovery::ExplainedWeight(const std::string& input,
                                      rime::an<rime::Translation>* stream) const {
  auto source = *stream;
  if (!source || source->exhausted()) return -1e9;
  double best = -1e9;
  auto scanned = rime::New<rime::FifoTranslation>();
  for (size_t i = 0; i < kPeek && !source->exhausted(); ++i) {
    auto candidate = source->Peek();
    source->Next();
    if (!candidate) continue;
    scanned->Append(candidate);
    auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
    if (!phrase) continue;
    std::string form;
    for (auto id : phrase->code()) {
      if (id < 0 || static_cast<size_t>(id) >= key_forms_.size()) {
        form.clear();
        break;
      }
      form += key_forms_[id];
    }
    // 恰好多一个键、且删掉那个键就是输入：这才是「漏按一键」的解释，补全/前缀不算。
    if (form.size() != input.size() + 1) continue;
    size_t at = 0;
    while (at < input.size() && form[at] == input[at]) ++at;
    if (form.compare(at + 1, std::string::npos, input, at, std::string::npos) != 0) continue;
    best = std::max(best, phrase->entry().weight);
  }
  if (source->exhausted()) {
    *stream = scanned;
  } else {
    auto restored = rime::New<rime::UnionTranslation>();
    *restored += scanned;
    *restored += source;
    *stream = restored;
  }
  return best;
}

rime::an<rime::Translation> T9KeyRecovery::RecoverMissing(
    const std::string& input, const rime::Segment& segment, rime::Dictionary* dictionary,
    CanonicalSyllabary& syllabary, rime::an<rime::Translation> original,
    size_t remaining_probes, double initial_quality, const WordBuilder& make_word) {
  if (!remaining_probes || input.size() < 4 || input.size() > 16) return original;
  if (!AllUpperKeys(input)) return original;
  Prepare(dictionary, syllabary);
  if (exact_.empty()) return original;
  // 漏按一键的候选不能靠逐个试拼：同一个短键串能对应一整个音节的同类写法
  // （`GD` 对应 bie/die/jie/xie… 七八个），按顺序碰运气命中率极低。这里先把所有
  // 「差一个键」的切分列出来，用一次词库精确查询给每条打分，再只给最高的几条建候选。
  struct Guess {
    rime::Code code;
    std::string spelling;
    double weight = 0;
  };
  std::vector<Guess> guesses;
  std::unordered_set<std::string> seen;
  const size_t max_guesses = std::min(kMaxGuesses, remaining_probes * 8);
  const size_t size = input.size();
  // 引擎给出的精确切分边界最可能出问题（漏按几乎都落在某个音节的起止处）：先扫边界，
  // 预算还有余再扫其余位置。
  // 整串必须能按**完整键形**切开才算「漏按一个键」。简拼/缩拼码（每个音节只按首键）
  // 切不开，它们的漏键是另一类编辑（用户少按的是一个首字母，不是某个音节的中间键），
  // 混进来只会把简拼的首选顶掉，所以这里直接不接。
  const auto full = Segments(input, 0, size, kSegments);
  if (full.empty()) return original;
  std::vector<size_t> order;
  for (const auto& path : full) {
    size_t at = 0;
    for (auto id : path) {
      order.push_back(at);
      at += key_forms_[id].size();
    }
  }
  for (size_t pos = 0; pos < size; ++pos)
    if (std::find(order.begin(), order.end(), pos) == order.end()) order.push_back(pos);
  for (size_t pos : order) {
    if (guesses.size() >= max_guesses) break;
    const auto left = Segments(input, 0, pos, kSegments);
    if (left.empty()) continue;
    for (size_t length = std::min(max_keys_, size - pos); length >= 1; --length) {
      auto found = missing_.find(input.substr(pos, length));
      if (found == missing_.end()) continue;
      const auto right = Segments(input, pos + length, size, kSegments);
      if (right.empty()) continue;
      for (auto id : found->second) {
        for (const auto& prefix : left) {
          for (const auto& suffix : right) {
            if (guesses.size() >= max_guesses) break;
            rime::Code code = prefix;
            code.push_back(id);
            code.insert(code.end(), suffix.begin(), suffix.end());
            if (code.size() > kMaxSyllables) continue;
            std::string spelling;
            for (auto syllable : code) spelling += spellings_[syllable];
            if (!seen.insert(spelling).second) continue;
            // 输入是候选键形的**前缀**时属于「补全」：引擎本来就会产这条，展示层按
            // 补全/模糊归因把它压掉是有意为之（`WAMGGTAM` 下的「阳光」就是这样压掉的）。
            // 这里再把它包装成纠错候选等于绕过那道判定，会把真正精确的词顶下去。
            // 只接「键缺在中间」的情形，那才是引擎补不出来的漏按。
            const auto candidate_keys = EncodeKeys(spelling);
            if (candidate_keys.size() > input.size() &&
                candidate_keys.compare(0, input.size(), input) == 0)
              continue;
            auto entry = ExactEntry(code, spelling);
            if (!entry) continue;
            guesses.push_back({std::move(code), std::move(spelling), entry->weight});
          }
        }
      }
    }
  }
  if (guesses.empty()) return original;
  std::stable_sort(guesses.begin(), guesses.end(),
                   [](const Guess& a, const Guess& b) { return a.weight > b.weight; });
  std::vector<rime::an<rime::Candidate>> words;
  std::unordered_set<std::string> texts;
  for (const auto& guess : guesses) {
    if (words.size() >= kMaxCandidates) break;
    // 同一拼串下的同音词按词频排列，取最高那条即该切分最可能对应的词。
    auto entry = ExactEntry(guess.code, guess.spelling);
    if (!entry || !ChineseWord(entry->text) || !texts.insert(entry->text).second) continue;
    auto word = make_word(guess.code, entry, std::exp(entry->weight) + initial_quality);
    if (!word) continue;
    words.push_back(MakeRecoveredWord(word, input, segment.end, kPenalty));
  }
  return Merge(std::move(words), std::move(original));
}

rime::an<rime::Translation> T9KeyRecovery::RecoverRepeated(
    const std::string& input, const rime::Segment& segment, rime::Dictionary* dictionary,
    CanonicalSyllabary& syllabary, rime::an<rime::Translation> original,
    size_t remaining_probes, double initial_quality, const WordBuilder& make_word) {
  if (!remaining_probes || input.size() < kMinRepeatKeys || input.size() > 16) return original;
  if (!AllUpperKeys(input)) return original;
  // 没有相邻叠键就没有可做的编辑。先挡掉，免得为大多数输入白扫一遍候选流。
  if (std::adjacent_find(input.begin(), input.end()) == input.end()) return original;
  Prepare(dictionary, syllabary);
  if (exact_.empty()) return original;
  // 原串本身也可能是词（`MGGAM` = 你好 / `DTGAATPG` 能凑出「对啊不是」）。所以不能
  // 一见原串有词就弃权，那会挡掉 3 例真错例；也不能只看词频比——`你好` 删一个 G 是
  // `mian`（面，词频高两个量级）。两条一起用才分得开：
  //   ① 修好的词至少要 2 个音节（`MGGAM` 的修复结果是一个音节，不接）；
  //   ② 修好的词必须比原串能凑出的词更常见（生僻的原串解释让位给常见词）。
  const auto original_paths = Segments(input, 0, input.size(), kRepeatSegments);
  const auto literal = LiteralEntry(original_paths);
  // 同一个键串常常既是「漏一键」也是「多一键」：`DTWTTAM` 补一个 D 是 服务端/服务员，
  // 删一个 T 是 复原。两条编辑方向相反，谁更常见谁就该在前面。这与上面的 literal 守卫
  // 是同一条原则，只是把「原串的精确解释」扩展到「原串已有的漏键解释」。
  // 扫描会逼引擎真造最多 kPeek 条候选，所以等到确有候选要仲裁时才扫，且只扫一次。
  double explained = -1e9;
  bool explained_scanned = false;

  struct Guess {
    rime::Code code;
    std::string spelling;
    double weight = 0;
  };
  std::vector<Guess> guesses;
  std::unordered_set<std::string> seen;
  const size_t max_guesses = std::min(kMaxGuesses, remaining_probes * 8);
  const size_t size = input.size();
  for (size_t i = 1; i < size && guesses.size() < max_guesses; ++i) {
    if (input[i] != input[i - 1]) continue;
    size_t end = i;
    while (end < size && input[end] == input[i - 1]) ++end;
    const size_t run_start = i - 1;
    // 两种编辑都试。整段收缩成一个键覆盖「连按 N 次多出 N-1 个键」；但正确码本身
    // 可能就在音节边界上含相邻双键（`确定` = que|ding，边界是双 3），整段收缩会把它
    // 一起毁掉，所以再试一次「段内只删一个键」。后者只在原串凑不出词时试：原串已是词
    // （`下班` = WGAAAM，xia|ban 天然三个 A）时多一种编辑就是多一批竞争者，会把它顶下去。
    std::vector<std::string> fixed_forms{input.substr(0, run_start + 1) + input.substr(end)};
    if (!literal && end - run_start > 2)
      fixed_forms.push_back(input.substr(0, run_start) + input.substr(run_start + 1));
    i = end - 1;  // 同一段叠键只处理一次（for 循环还会 ++i）
    // 段贴到串尾时删键等于把输入截短，而「全音节 + 下一字首字母」的合法简拼
    // （`生活` = sheng|g、`相关` = xiang|g）与真连按同形，所以尾部一律不放宽边界。
    const bool tail = end == size;
    for (const auto& fixed : fixed_forms) {
      const auto paths = Segments(fixed, 0, fixed.size(), kRepeatSegments);
      if (paths.empty()) continue;
      // 叠键起点正好是音节边界，是最可靠的连按证据（`WAG|JJ|GAM` 删一个 J 后 J 仍是
      // `jian` 的首键）。但真连按也会落在音节内部（`已经` = yi|jing，多按的 4 在 jing 里面），
      // 所以原串凑不出词、且段不在尾部时放宽。放宽条件不能再加「原串完全切不出音节」：
      // 繁体流的音节表含单字母读音，`WGJGGMG` 在简体切不开、在繁体能切成 xi|ji|i|mi，
      // 那样同一个输入在 m1/m2 就恢复不出繁体形（r2 实测「已经」丢了 已經）。
      bool on_boundary = false;
      for (const auto& path : paths) {
        size_t at = 0;
        for (auto id : path) {
          if (at == run_start) {
            on_boundary = true;
            break;
          }
          at += key_forms_[id].size();
        }
        if (on_boundary) break;
      }
      if (!on_boundary && (literal || tail)) continue;
      for (const auto& path : paths) {
        if (guesses.size() >= max_guesses) break;
        if (path.size() < 2 || path.size() > kMaxSyllables) continue;
        std::string spelling;
        for (auto id : path) spelling += spellings_[id];
        if (!seen.insert(spelling).second) continue;
        auto entry = ExactEntry(path, spelling);
        if (!entry) continue;
        if (literal && entry->weight <= literal->weight) continue;
        if (!explained_scanned) {
          explained = ExplainedWeight(input, &original);
          explained_scanned = true;
        }
        if (entry->weight <= explained) continue;
        guesses.push_back({path, std::move(spelling), entry->weight});
      }
    }
  }
  if (guesses.empty()) return original;
  std::stable_sort(guesses.begin(), guesses.end(),
                   [](const Guess& a, const Guess& b) { return a.weight > b.weight; });
  std::vector<rime::an<rime::Candidate>> words;
  std::unordered_set<std::string> texts;
  for (const auto& guess : guesses) {
    if (words.size() >= kMaxCandidates) break;
    auto entry = ExactEntry(guess.code, guess.spelling);
    if (!entry || !ChineseWord(entry->text) || !texts.insert(entry->text).second) continue;
    auto word = make_word(guess.code, entry, std::exp(entry->weight) + initial_quality);
    if (!word) continue;
    words.push_back(MakeRecoveredWord(word, input, segment.end, kPenalty));
  }
  return Merge(std::move(words), std::move(original));
}

rime::an<rime::Translation> T9KeyRecovery::Merge(
    std::vector<rime::an<rime::Candidate>> words, rime::an<rime::Translation> original) {
  if (words.empty()) return original;
  std::stable_sort(words.begin(), words.end(),
                   [](const auto& a, const auto& b) { return a->quality() > b->quality(); });
  // 原译器可能已经给过同一个词（补全路径），但那条会被展示层按模糊音归因挡掉；
  // 这里用纠错版本顶替它，避免同名候选去重后只剩被挡的那条。
  std::vector<bool> emitted(words.size());
  auto by_text = [&](const std::string& text) -> size_t {
    for (size_t i = 0; i < words.size(); ++i)
      if (!emitted[i] && words[i]->text() == text) return i;
    return words.size();
  };
  auto merged = rime::New<rime::FifoTranslation>();
  size_t next = 0;
  auto flush_until = [&](double quality) {
    while (next < words.size() && !emitted[next] && words[next]->quality() > quality) {
      emitted[next] = true;
      merged->Append(words[next++]);
    }
  };
  for (size_t i = 0; i < kPeek && original && !original->exhausted(); ++i) {
    const auto candidate = original->Peek();
    original->Next();
    const size_t same = by_text(candidate->text());
    if (same < words.size()) {
      // 引擎已经给过这个词，位次和质量都不动，只改类型让展示层别按模糊/补全归因挡掉。
      // 换成自己的候选（质量 -5e-4）会打乱原字模式简繁双流的质量交错。
      emitted[same] = true;
      merged->Append(RetagAsCorrection(candidate));
      continue;
    }
    flush_until(candidate->quality());
    merged->Append(candidate);
  }
  flush_until(-1e9);
  if (!original || original->exhausted()) return merged;
  auto result = rime::New<rime::UnionTranslation>();
  *result += merged;
  *result += original;
  return result;
}
