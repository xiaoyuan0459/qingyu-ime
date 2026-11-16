#include "spelling_policy.h"
#include "t9_keys.h"
#include <rime/dict/dictionary.h>
#include <rime/dict/table.h>
#include <rime/gear/translator_commons.h>
#include <algorithm>

namespace {
using Forms = std::unordered_set<std::string>;

template<class Rewrite>
void Derive(Forms* forms, Rewrite rewrite) {
  const auto source = *forms;
  for (const auto& form : source) forms->insert(rewrite(form));
}

// 键形换算放在 t9_keys.h：原生侧的音节索引与这里必须用同一张表。
std::string Encode(std::string input, bool t9) {
  return t9 ? EncodeKeys(input) : input;
}

Forms BaseForms(const std::string& sound) {
  Forms forms{sound};
  if (sound.size() == 2 && std::string("jqxy").find(sound[0]) != std::string::npos && sound[1] == 'u') {
    auto alias = sound; alias[1] = 'v'; forms.insert(alias);
  }
  if (sound.size() == 3 && (sound[0] == 'n' || sound[0] == 'l') && sound.substr(1) == "ve")
    forms.insert(sound.substr(0, 1) + "ue");
  return forms;
}

Forms AllowedForms(const std::string& sound, int groups, bool t9) {
  Forms forms = BaseForms(sound);
  if (groups & 1) {
    Derive(&forms, [](std::string s) {
      if (s.size() > 1 && std::string("zcs").find(s[0]) != std::string::npos && s[1] == 'h') s.erase(1, 1);
      return s;
    });
    Derive(&forms, [](std::string s) {
      if (s.size() > 1 && std::string("zcs").find(s[0]) != std::string::npos && s[1] != 'h') s.insert(1, "h");
      return s;
    });
  }
  for (const auto& rule : {std::make_pair(2, std::string("nl")), {4, "fh"}}) {
    if (!(groups & rule.first)) continue;
    for (int direction = 0; direction < 2; ++direction) {
      Derive(&forms, [&](std::string s) {
        if (!s.empty() && s[0] == rule.second[direction]) s[0] = rule.second[1-direction];
        return s;
      });
    }
  }
  if (groups & 8) {
    Derive(&forms, [](std::string s) {
      if (s.size() >= 3 && s.substr(s.size()-2) == "ng" && std::string("aeiou").find(s[s.size()-3]) != std::string::npos) s.pop_back();
      return s;
    });
    Derive(&forms, [](std::string s) {
      if (s.size() >= 2 && s.back() == 'n' && std::string("aeiou").find(s[s.size()-2]) != std::string::npos) s += 'g';
      return s;
    });
  }
  Forms result;
  for (const auto& form : forms) {
    result.insert(Encode(form, t9));
    if (form.size() < 2) continue;
    result.insert(Encode(form.substr(0, 1), t9));
    if (form[1] == 'h' && std::string("zcs").find(form[0]) != std::string::npos)
      result.insert(Encode(form.substr(0, 2), t9));
  }
  return result;
}
}  // namespace

void QingyuSpellingPolicy::Prepare(rime::Dictionary* dict, int groups, bool t9) {
  if (dictionary_ != dict || groups_ != groups || t9_ != t9) {
    dictionary_ = dict; groups_ = groups; t9_ = t9; spellings_.clear(); all_spellings_.clear();
  }
}

bool QingyuSpellingPolicy::IsCompleteSyllable(const std::string& input) const {
  if (!dictionary_ || dictionary_->tables().empty() || !dictionary_->primary_table()) return false;
  const auto& table = dictionary_->primary_table();
  if (table->HasLoadedSyllable(input)) return true;
  // Match the same canonical aliases as BaseForms, without rebuilding the
  // dictionary syllabary in each translator or keystroke.
  if (input.size() == 2 && input[1] == 'v' && std::string("jqxy").find(input[0]) != std::string::npos)
    return table->HasLoadedSyllable(input.substr(0, 1) + "u");
  if (input.size() == 3 && (input[0] == 'n' || input[0] == 'l') && input.substr(1) == "ue")
    return table->HasLoadedSyllable(input.substr(0, 1) + "ve");
  return false;
}

const Forms& QingyuSpellingPolicy::FormsFor(int32_t syllable, bool all_groups) {
  auto& cache = all_groups ? all_spellings_ : spellings_;
  auto found = cache.find(syllable);
  if (found == cache.end()) {
    rime::Code code; code.push_back(syllable);
    std::vector<std::string> reading;
    static const Forms empty;
    if (!dictionary_ || !dictionary_->Decode(code, &reading) || reading.size() != 1) return empty;
    found = cache.emplace(syllable, AllowedForms(reading[0], all_groups ? 15 : groups_, t9_)).first;
  }
  return found->second;
}

bool QingyuSpellingPolicy::Allows(rime::Dictionary* dict, int groups, bool t9,
                                  int32_t syllable, const std::string& input,
                                  rime::SpellingType type) {
  if (groups == 15 || (type != rime::kFuzzySpelling && type != rime::kAbbreviation)) return true;
  Prepare(dict, groups, t9);
  const auto& forms = FormsFor(syllable, false);
  // Unknown/custom codes retain the existing SDK guard rather than disappearing.
  if (forms.empty()) return true;
  if (forms.count(input)) return true;
  // Incomplete full-key syllables (zhon→zhong) are ordinary completion, even
  // when the prism represents them as fuzzy edges. Complete lan must still
  // reject lang with nasal OFF. T9 key groups have no such partial-letter state.
  if (t9 || input.empty() ||
      !std::all_of(input.begin(), input.end(), [](char c) { return c >= 'a' && c <= 'z'; })) return false;
  if (std::none_of(forms.begin(), forms.end(), [&](const auto& form) {
    return input.size() < form.size() && form.compare(0, input.size(), input) == 0;
  })) return false;
  return dictionary_ && !dictionary_->tables().empty() && dictionary_->primary_table() &&
      dictionary_->primary_table()->metadata() && dictionary_->primary_table()->metadata()->num_syllables &&
      !IsCompleteSyllable(input);
}

bool QingyuSpellingPolicy::Matches(const rime::Code& code, const std::string& input, bool all_groups,
                                  bool allow_partial) {
  if (code.size() > input.size()) return false;
  std::vector<bool> reachable(input.size()+1); reachable[0] = true;
  const bool partial = allow_partial && !t9_ && std::all_of(input.begin(), input.end(), [](char c) { return c >= 'a' && c <= 'z'; });
  for (auto syllable : code) {
    std::vector<bool> next(input.size()+1);
    for (size_t start = 0; start < input.size(); ++start) {
      if (!reachable[start]) continue;
      for (const auto& form : FormsFor(syllable, all_groups)) {
        const auto length = std::min(form.size(), input.size()-start);
        for (size_t count = 1; count <= length; ++count) {
          if (input[start+count-1] != form[count-1]) break;
          if (count == form.size() ||
              (partial && !IsCompleteSyllable(input.substr(start, count))))
            next[start+count] = true;
        }
      }
    }
    reachable = std::move(next);
  }
  return reachable.back();
}

bool QingyuSpellingPolicy::Keeps(rime::Dictionary* dict, int groups, bool t9,
                                const rime::an<rime::Candidate>& candidate, const std::string& input) {
  if (groups == 15 || !candidate || input.size() > 256) return true;
  auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
  if (!phrase || phrase->code().empty() || phrase->code().size() > 128) return true;
  Prepare(dict, groups, t9);
  std::string compact;
  for (char c : input) if (c != '\'' && c != ' ') compact += c;
  if (compact.empty() || Matches(phrase->code(), compact, false)) return true;
  // Retain corrections and custom codes that cannot be attributed solely to fuzzy spelling.
  return !Matches(phrase->code(), compact, true);
}

bool QingyuSpellingPolicy::UsesEnabledSpelling(rime::Dictionary* dict, int groups, bool t9,
    const rime::an<rime::Candidate>& candidate, const std::string& input) {
  if (!candidate || input.size() > 256) return false;
  auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
  if (!phrase || phrase->code().empty() || phrase->code().size() > 128) return false;
  Prepare(dict, groups, t9);
  std::string compact;
  for (char c : input) if (c != '\'' && c != ' ') compact += c;
  // Protection requires complete forms (including aliases, abbreviations and
  // enabled fuzzy forms). A partial syllable is still a recoverable typo intent.
  return !compact.empty() && Matches(phrase->code(), compact, false, false);
}
