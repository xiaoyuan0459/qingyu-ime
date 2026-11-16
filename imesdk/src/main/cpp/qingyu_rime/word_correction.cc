#include "word_correction.h"
#include <rime/gear/translator_commons.h>
#include <rime/gear/poet.h>
#include <rime/dict/dictionary.h>
#include <algorithm>
#include <unordered_set>

namespace {
constexpr size_t kMaxProbes = 32;
constexpr size_t kMaxCandidates = 6;
constexpr const char* kCorrection = "qingyu_spelling_correction";

class CorrectedWord : public rime::ShadowCandidate {
 public:
  CorrectedWord(rime::an<rime::Candidate> word, const std::string& input, size_t end,
                double penalty)
      : ShadowCandidate(word, kCorrection), input_(input) {
    set_end(end);
    set_quality(word->quality() - penalty);
  }
  std::string preedit() const override { return input_; }
 private:
  std::string input_;
};

bool ExactWord(const rime::an<rime::Candidate>& candidate, size_t end) {
  if (!candidate || candidate->end() != end) return false;
  auto genuine = rime::Candidate::GetGenuineCandidate(candidate);
  auto phrase = rime::As<rime::Phrase>(genuine);
  return phrase && !rime::As<rime::Sentence>(genuine) && phrase->entry().IsExactMatch();
}

std::string Canonical(rime::Dictionary* dictionary, const rime::an<rime::Candidate>& candidate) {
  if (!dictionary || !candidate) return {};
  auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
  std::vector<std::string> sounds;
  if (!phrase || !dictionary->Decode(phrase->code(), &sounds)) return {};
  std::string result;
  for (const auto& sound : sounds) result += sound;
  return result;
}

bool Abbreviated(rime::Dictionary* dictionary, const rime::an<rime::Candidate>& candidate,
                 const std::string& input) {
  auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
  std::vector<std::string> sounds;
  if (!dictionary || !phrase || !dictionary->Decode(phrase->code(), &sounds)) return false;
  std::vector<bool> reachable(input.size()+1); reachable[0] = true;
  for (const auto& sound : sounds) {
    if (sound.empty()) return false;
    std::vector<std::string> forms{sound, sound.substr(0, 1)};
    if (sound.size() > 1 && sound[1] == 'h' && std::string("zcs").find(sound[0]) != std::string::npos)
      forms.push_back(sound.substr(0, 2));
    if (sound.size() > 1 && sound[1] == 'u' && std::string("jqxy").find(sound[0]) != std::string::npos) {
      auto alias = sound; alias[1] = 'v'; forms.push_back(alias);
    }
    std::vector<bool> next(input.size()+1);
    for (size_t start = 0; start < input.size(); ++start) {
      if (!reachable[start]) continue;
      for (const auto& form : forms)
        if (start + form.size() <= input.size() && input.compare(start, form.size(), form) == 0)
          next[start + form.size()] = true;
    }
    reachable = std::move(next);
  }
  return reachable.back();
}

std::vector<std::string> Variants(const std::string& input, bool repeated_only = false) {
  std::vector<std::string> result;
  std::unordered_set<std::string> seen;
  auto add = [&](std::string variant) {
    if (variant != input && result.size() < kMaxProbes && seen.insert(variant).second)
      result.push_back(std::move(variant));
  };
  // Whole-string edits also recover swaps across a syllable boundary.
  for (size_t i = 1; i < input.size(); ++i) {
    if (input[i] == input[i-1]) add(input.substr(0, i) + input.substr(i+1));
    else if (!repeated_only) { auto s = input; std::swap(s[i-1], s[i]); add(std::move(s)); }
  }
  if (repeated_only) return result;
  // A missing terminal nasal g is an insertion typo, independently of fuzzy mode.
  for (size_t i = 1; i < input.size(); ++i)
    if (input[i-1] == 'n' && input[i] != 'g') add(input.substr(0, i) + "g" + input.substr(i));
  // A mistyped nasal g may land on a neighbouring QWERTY key. Restrict
  // substitution to the nasal position and keep the same total probe bound.
  for (size_t i = 1; i < input.size(); ++i)
    if (input[i-1] == 'n' && std::string("fhtyvb").find(input[i]) != std::string::npos) {
      auto s = input; s[i] = 'g'; add(std::move(s));
    }
  return result;
}
}  // namespace

bool HasLiteralWordSpelling(rime::Dictionary* dictionary,
    const rime::an<rime::Candidate>& candidate, const std::string& input) {
  return Canonical(dictionary, candidate) == input || Abbreviated(dictionary, candidate, input);
}

bool HasPrefixWordSpelling(rime::Dictionary* dictionary,
    const rime::an<rime::Candidate>& candidate, const std::string& input) {
  const auto spelling = Canonical(dictionary, candidate);
  return spelling.size() > input.size() && spelling.compare(0, input.size(), input) == 0;
}

rime::an<rime::Candidate> MakeRecoveredWord(const rime::an<rime::Candidate>& word,
    const std::string& input, size_t end, double penalty) {
  return rime::New<CorrectedWord>(word, input, end, penalty);
}

rime::an<rime::Candidate> RetagAsCorrection(const rime::an<rime::Candidate>& word) {
  if (!word) return nullptr;
  return rime::New<rime::ShadowCandidate>(word, kCorrection);
}

rime::an<rime::Candidate> RecoverTrailingLetterWord(rime::Dictionary* dictionary,
    const rime::an<rime::Candidate>& word, const std::string& input, size_t end) {
  auto genuine = rime::Candidate::GetGenuineCandidate(word);
  auto phrase = rime::As<rime::Phrase>(genuine);
  if (!phrase || rime::As<rime::Sentence>(genuine) ||
      phrase->code().size() < 2 || phrase->code().size() > 4) return nullptr;
  const auto spelling = Canonical(dictionary, word);
  if (spelling.size() != input.size() + 1 || spelling.compare(0, input.size(), input) != 0) return nullptr;
  const auto& text = word->text();
  const auto length = std::count_if(text.begin(), text.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; });
  if (length < 2 || length > 4 ||
      std::any_of(text.begin(), text.end(), [](unsigned char c) { return c < 0x80; })) return nullptr;
  return MakeRecoveredWord(word, input, end);
}

rime::an<rime::Translation> RecoverWholeWord(
    const std::string& input, const rime::Segment& segment,
    rime::Dictionary* dictionary,
    rime::an<rime::Translation> original,
    const std::function<bool(const rime::an<rime::Candidate>&)>& admissible,
    const std::function<rime::an<rime::Translation>(const std::string&)>& query) {
  if (input.size() < 4 || input.size() > 16 ||
      !std::all_of(input.begin(), input.end(), [](char c) { return c >= 'a' && c <= 'z'; })) return original;
  const bool repeated = std::adjacent_find(input.begin(), input.end()) != input.end();
  bool preserve_sentence = false;
  if (original && !original->exhausted()) {
    const auto first = original->Peek();
    const auto spelling = Canonical(dictionary, first);
    preserve_sentence = first && first->end() == segment.end && repeated && spelling == input &&
        rime::As<rime::Sentence>(rime::Candidate::GetGenuineCandidate(first));
    if (spelling == input && !preserve_sentence) return original;
    if (first && first->end() == segment.end && !repeated && Abbreviated(dictionary, first, input)) return original;
    if (ExactWord(first, segment.end) && spelling.empty()) return original;
  }
  // The first raw candidate may be hidden by the SDK fuzzy gate. Protect the
  // admissible words behind it as well, including ordinary syllable correction.
  auto scanned = rime::New<rime::FifoTranslation>();
  bool protected_word = false;
  bool checked_first = false;
  for (int rank = 0; rank < 30 && original && !original->exhausted(); ++rank) {
    const auto candidate = original->Peek();
    auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
    if (candidate && admissible(candidate)) {
      const bool partial_word = !checked_first && !repeated && phrase && phrase->code().size() > 1;
      checked_first = true;
      const bool complete_word = candidate->end() == segment.end &&
          (!repeated || Canonical(dictionary, candidate) == input);
      if (phrase && !rime::As<rime::Sentence>(phrase) && (complete_word || partial_word)) {
        protected_word = true;
        break;
      }
    }
    if (candidate) scanned->Append(candidate);
    original->Next();
  }
  if (!scanned->exhausted()) {
    auto restored = rime::New<rime::UnionTranslation>();
    *restored += scanned; *restored += original; original = restored;
  }
  if (protected_word) return original;
  std::vector<rime::an<rime::Candidate>> words;
  std::unordered_set<std::string> seen;
  // A literal sentence can explain an extra vowel as another word. Keep that
  // interpretation, but permit a single duplicate deletion to find a real word.
  for (const auto& variant : Variants(input, preserve_sentence)) {
    auto source = query(variant);
    if (!source || source->exhausted()) continue;
    // Candidate recovery must not apply a second fuzzy/abbreviation/correction hop.
    for (int rank = 0; rank < 16 && !source->exhausted(); ++rank, source->Next()) {
      auto candidate = source->Peek();
      if (!ExactWord(candidate, segment.start + variant.size()) || Canonical(dictionary, candidate) != variant) continue;
      const auto& text = candidate->text();
      if (std::any_of(text.begin(), text.end(), [](unsigned char c) { return c < 0x80; })) continue;
      const auto length = std::count_if(text.begin(), text.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; });
      if (length < 2 || length > 4 || !seen.insert(text).second) continue;
      words.push_back(MakeRecoveredWord(candidate, input, segment.end));
    }
  }
  if (words.empty()) return original;
  std::stable_sort(words.begin(), words.end(), [](const auto& a, const auto& b) { return a->quality() > b->quality(); });
  auto recovered = rime::New<rime::FifoTranslation>();
  for (size_t i = 0; i < std::min(words.size(), kMaxCandidates); ++i) recovered->Append(words[i]);
  auto result = rime::New<rime::UnionTranslation>();
  if (preserve_sentence && original && !original->exhausted()) {
    auto leading = rime::New<rime::FifoTranslation>();
    leading->Append(original->Peek());
    original->Next();
    *result += leading;
  }
  *result += recovered;
  if (original) *result += original;
  return result;
}
