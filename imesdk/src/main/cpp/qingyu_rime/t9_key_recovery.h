#pragma once
#include "word_correction.h"
#include "canonical_syllabary.h"
#include <rime/dict/vocabulary.h>
#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

/**
 * 九键键级恢复（T01 漏键 / T02 重复键）。
 *
 * 全拼那套「按音节拼写删一个字符再补回来」的模型在九键上不成立：九键送进引擎的是
 * 每键首字母的键串（`dang` → `DAMG`），一个音节本来就只有 2–4 个键；而且同一个键串
 * 往往同时是许多词的**完整**键形（`DAMPAM` 既是 `fan'pan` 也是 `dang'ran` 少按一键），
 * 所以「已有整词就早退」这类保护会整类失效。
 *
 * 本模块直接在建好的键形索引里工作：词库音节表 → 键形 → 两张表（完整键形、
 * 去掉一个键的键形）。两类编辑共用同一套切分与打分：
 *
 * - `RecoverMissing`：恰好用一个「少一个键」的音节补回输入（漏按一键）；
 * - `RecoverRepeated`：删掉一个相邻叠键（连按两次），要求叠键起点落在切分边界上。
 *
 * 命中候选打 `qingyu_spelling_correction`，与全拼的四类恢复同源，展示层据此不再按
 * 模糊音归因。
 */
class T9KeyRecovery {
 public:
  // [make_word] 由译器构建词条候选：只有它能拿到 language()/Spell()/initial_quality()，
  // 本模块只负责找出「差一个键」或「多一个键」的词条。
  using WordBuilder = std::function<rime::an<rime::Candidate>(
      const rime::Code& code, const rime::an<rime::DictEntry>& entry, double quality)>;

  rime::an<rime::Translation> RecoverMissing(
      const std::string& input, const rime::Segment& segment,
      rime::Dictionary* dictionary, CanonicalSyllabary& syllabary,
      rime::an<rime::Translation> original, size_t remaining_probes,
      double initial_quality, const WordBuilder& make_word);

  rime::an<rime::Translation> RecoverRepeated(
      const std::string& input, const rime::Segment& segment,
      rime::Dictionary* dictionary, CanonicalSyllabary& syllabary,
      rime::an<rime::Translation> original, size_t remaining_probes,
      double initial_quality, const WordBuilder& make_word);

 private:
  void Prepare(rime::Dictionary* dictionary, CanonicalSyllabary& syllabary);
  // 把 [from, to) 这一段按精确键形切成若干条音节序列（最长音节优先）。[limit] 是每段
  // 保留的切分条数：漏键（T01）用 4 条即可且已冻结；重复键（T02）需要更大的上限，
  // 否则 `DTGATPG` 这种键形歧义较多的串会把「对不起」挤出候选。
  std::vector<rime::Code> Segments(const std::string& input, size_t from, size_t to,
                                   size_t limit) const;
  // 该切分是不是一条真实词条；是则返回词频最高的那条。
  rime::an<rime::DictEntry> ExactEntry(const rime::Code& code, const std::string& spelling) const;
  // 原串自身能凑出的最高词频词条。重复键恢复用它做两件事：修好的词必须比它更常见；
  // 原串已是词时不放宽边界守卫、也不试第二种编辑（`你好` `MGGAM` → `mian` 属于重新解释输入）。
  rime::an<rime::DictEntry> LiteralEntry(const std::vector<rime::Code>& paths) const;
  // 原串已有的「漏一键」解释里最高的词频（读音键形比输入多一个键、删掉即等于输入）。
  // 重复键恢复必须比它更常见才插到前面：同一个键串往往两个方向都解释得通。
  // 扫描会消费流，扫过的候选按原顺序接回 [*stream]。
  double ExplainedWeight(const std::string& input, rime::an<rime::Translation>* stream) const;
  // 把已建好的候选按质量并入原候选流；同名候选只重贴类型，不动位次。
  rime::an<rime::Translation> Merge(std::vector<rime::an<rime::Candidate>> words,
                                    rime::an<rime::Translation> original);

  rime::Dictionary* dictionary_ = nullptr;
  // 音节 id → 键形；空串表示该 id 没有可用拼写。
  std::vector<std::string> key_forms_;
  // 音节 id → 拼音拼写（查词时要把它拼回整串交给 QueryExactWords）。
  std::vector<std::string> spellings_;
  // 完整键形 → 音节 id。
  std::unordered_map<std::string, std::vector<int32_t>> exact_;
  // 去掉任意一个键后的键形 → 原本的音节 id（即「用户漏按了这一键」）。
  std::unordered_map<std::string, std::vector<int32_t>> missing_;
  size_t max_keys_ = 1;
};
