#pragma once
#include <rime/algo/spelling.h>
#include <rime/dict/vocabulary.h>
#include <unordered_map>
#include <unordered_set>

namespace rime { class Dictionary; class Candidate; }

// Per-translator cache contains schema syllables only, never editor text.
class QingyuSpellingPolicy {
 public:
  bool Allows(rime::Dictionary* dict, int groups, bool t9, int32_t syllable,
              const std::string& input, rime::SpellingType type);
  bool Keeps(rime::Dictionary* dict, int groups, bool t9,
             const rime::an<rime::Candidate>& candidate, const std::string& input);
  bool UsesEnabledSpelling(rime::Dictionary* dict, int groups, bool t9,
             const rime::an<rime::Candidate>& candidate, const std::string& input);
 private:
  void Prepare(rime::Dictionary* dict, int groups, bool t9);
  bool IsCompleteSyllable(const std::string& input) const;
  const std::unordered_set<std::string>& FormsFor(int32_t syllable, bool all_groups);
  bool Matches(const rime::Code& code, const std::string& input, bool all_groups,
               bool allow_partial = true);
  rime::Dictionary* dictionary_ = nullptr;
  int groups_ = -1;
  bool t9_ = false;
  std::unordered_map<int32_t, std::unordered_set<std::string>> spellings_;
  std::unordered_map<int32_t, std::unordered_set<std::string>> all_spellings_;
};
