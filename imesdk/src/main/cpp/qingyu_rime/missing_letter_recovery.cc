#include "missing_letter_recovery.h"
#include <rime/algo/syllabifier.h>
#include <rime/dict/dictionary.h>
#include <rime/gear/translator_commons.h>
#include <algorithm>
#include <deque>
#include <map>
#include <set>
#include <unordered_set>

namespace {
constexpr size_t kMaxEdges = 4096;
constexpr size_t kMaxEntries = 512;

// Match exactly one missing letter of this canonical syllable, never a second edit.
bool MissingOne(const std::string& sound, const std::string& input, size_t start) {
  if (sound.empty() || sound.size() - 1 > input.size() - start) return false;
  size_t i = 0;
  while (i + 1 < sound.size() && sound[i] == input[start + i]) ++i;
  return input.compare(start + i, sound.size() - i - 1, sound, i + 1,
                       sound.size() - i - 1) == 0;
}

bool ExactPhrase(const rime::an<rime::Candidate>& candidate, size_t end) {
  if (!candidate || candidate->end() != end) return false;
  auto genuine = rime::Candidate::GetGenuineCandidate(candidate);
  auto phrase = rime::As<rime::Phrase>(genuine);
  return phrase && !rime::As<rime::Sentence>(genuine) && phrase->entry().IsExactMatch();
}
}  // namespace

std::vector<rime::Code> MissingLetterRecovery::FindCodes(
    rime::Dictionary* dictionary, const std::string& input, size_t limit, CanonicalSyllabary& syllabary) {
  if (!dictionary || !dictionary->loaded() || !dictionary->primary_table() || !limit) return {};
  syllabary.Prepare(dictionary);
  const auto& syllables_ = syllabary.syllables();
  const size_t size = input.size();
  // Delimiters and digits are not syllable initials; they carry no edges here.
  if (!std::all_of(input.begin(), input.end(), [](char c) { return c >= 'a' && c <= 'z'; }))
    return {};
  auto node = [size](size_t depth, size_t pos, bool edited) {
    return 2 * (depth * (size + 1) + pos) + edited;
  };
  struct Edge { int32_t syllable; size_t consumed; bool missing; };
  std::vector<std::vector<Edge>> edges(size + 1);
  for (size_t pos = 0; pos <= size; ++pos) {
    const auto& ids = pos == size ? syllabary.singles() : syllabary.leading(input[pos]);
    for (auto id : ids) {
      const auto& sound = syllables_[id];
      if (input.compare(pos, sound.size(), sound) == 0)
        edges[pos].push_back({static_cast<int32_t>(id), sound.size(), false});
      if (MissingOne(sound, input, pos))
        edges[pos].push_back({static_cast<int32_t>(id), sound.size() - 1, true});
    }
  }
  rime::SyllableGraph graph;
  graph.input_length = graph.interpreted_length = node(4, size, true);
  std::vector<bool> reachable(graph.interpreted_length + 1);
  reachable[0] = true;
  // Dictionary::Lookup consumes the graph synchronously; deque keeps all edge
  // pointers stable until it has materialized independent dictionary iterators.
  std::deque<rime::EdgeProperties> properties;
  for (size_t depth = 0; depth < 4; ++depth) {
    for (size_t pos = 0; pos <= size; ++pos) {
      for (int edited = 0; edited < 2; ++edited) {
        const auto from = node(depth, pos, edited);
        if (!reachable[from]) continue;
        for (const auto& edge : edges[pos]) {
          if (edited && edge.missing) continue;
          if (properties.size() == kMaxEdges) return {};
          properties.emplace_back();
          auto& props = properties.back();
          props.end_pos = node(depth + 1, pos + edge.consumed, edited || edge.missing);
          reachable[props.end_pos] = true;
          graph.indices[from][edge.syllable].push_back(&props);
        }
      }
    }
  }
  auto found = dictionary->Lookup(graph, 0, false);
  if (!found) return {};
  std::vector<rime::DictEntryIterator*> streams;
  for (size_t depth = 2; depth <= 4; ++depth) {
    auto stream = found->find(node(depth, size, true));
    if (stream != found->end()) streams.push_back(&stream->second);
  }
  std::set<rime::Code> seen;
  std::vector<rime::Code> result;
  for (size_t inspected = 0; inspected < kMaxEntries && result.size() < limit; ++inspected) {
    rime::DictEntryIterator* best = nullptr;
    for (auto stream : streams)
      if (!stream->exhausted() && (!best || stream->Peek()->weight > best->Peek()->weight)) best = stream;
    if (!best) break;
    const auto entry = best->Peek();
    std::string code;
    for (auto id : entry->code) {
      if (id < 0 || static_cast<size_t>(id) >= syllables_.size()) { code.clear(); break; }
      code += syllables_[id];
    }
    if (code.size() == input.size() + 1 && MissingOne(code, input, 0) && seen.insert(entry->code).second)
      result.push_back(entry->code);
    // Never copy and independently consume DictEntryIterator: its cursor is shared.
    best->Next();
  }
  return result;
}

rime::an<rime::Translation> MissingLetterRecovery::Recover(
    const std::string& input, const rime::Segment& segment, rime::Dictionary* dictionary,
    CanonicalSyllabary& syllabary, rime::an<rime::Translation> original, size_t remaining_probes,
    const std::function<bool(const rime::an<rime::Candidate>&)>& admissible,
    const std::function<bool(const rime::an<rime::Candidate>&)>& enabled_spelling,
    const std::function<rime::an<rime::Translation>(const std::string&, const rime::Code&)>& query) {
  if (!remaining_probes || input.size() < 4 || input.size() > 16 ||
      !std::all_of(input.begin(), input.end(), [](char c) { return c >= 'a' && c <= 'z'; })) return original;
  std::vector<rime::an<rime::Candidate>> prefix;
  std::vector<rime::an<rime::Candidate>> trailing_words;
  std::unordered_set<std::string> seen;
  size_t preserve = 0;
  bool literal_word = false;
  for (size_t rank = 0; rank < 30 && original && !original->exhausted(); ++rank) {
    auto candidate = original->Peek();
    original->Next();
    if (!candidate) continue;
    prefix.push_back(candidate);
    if (auto trailing = RecoverTrailingLetterWord(dictionary, candidate, input, segment.end))
      if (seen.insert(trailing->text()).second) trailing_words.push_back(trailing);
    if (candidate->end() != segment.end) continue;
    const bool literal = HasLiteralWordSpelling(dictionary, candidate, input);
    auto genuine = rime::Candidate::GetGenuineCandidate(candidate);
    const bool word = rime::As<rime::Phrase>(genuine) && !rime::As<rime::Sentence>(genuine);
    // Ordinary last-syllable completion already offers a usable whole word.
    // A disabled fuzzy edge is not such a completion and must not suppress recovery.
    if (word && (literal || enabled_spelling(candidate) || (HasPrefixWordSpelling(dictionary, candidate, input) &&
        (candidate->type() == "qingyu_spelling_correction" || admissible(candidate))))) {
      literal_word = true;
      break;
    }
    if (literal && !preserve) preserve = prefix.size();
  }
  auto assemble = [&](rime::an<rime::Translation> recovery, bool replace_recovered = false) -> rime::an<rime::Translation> {
    auto first = rime::New<rime::FifoTranslation>();
    auto rest = rime::New<rime::FifoTranslation>();
    for (size_t i = 0; i < prefix.size(); ++i) {
      if (replace_recovered && prefix[i]->type() == "qingyu_spelling_correction") continue;
      (i < preserve ? first : rest)->Append(prefix[i]);
    }
    auto result = rime::New<rime::UnionTranslation>();
    *result += first; *result += recovery; *result += rest; *result += original;
    return result;
  };
  if (literal_word) return assemble(nullptr);
  // These real dictionary words already prove a one-letter trailing repair;
  // querying their canonical codes again would only rebuild the same word stream.
  auto variants = trailing_words.empty() ? FindCodes(dictionary, input, remaining_probes, syllabary) : std::vector<rime::Code>{};
  std::vector<rime::an<rime::Candidate>> words = std::move(trailing_words);
  const auto& syllables_ = syllabary.syllables();
  for (const auto& syllable_code : variants) {
    std::string code;
    for (auto id : syllable_code) code += syllables_[id];
    auto stream = query(code, syllable_code);
    for (size_t rank = 0; rank < 16 && stream && !stream->exhausted(); ++rank, stream->Next()) {
      auto candidate = stream->Peek();
      if (!ExactPhrase(candidate, segment.start + code.size())) continue;
      std::vector<std::string> sounds;
      auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
      if (!dictionary->Decode(phrase->code(), &sounds)) continue;
      std::string canonical;
      for (const auto& sound : sounds) canonical += sound;
      const auto& text = candidate->text();
      const auto length = std::count_if(text.begin(), text.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; });
      if (canonical != code || length < 2 || length > 4 ||
          std::any_of(text.begin(), text.end(), [](unsigned char c) { return c < 0x80; }) ||
          !seen.insert(text).second) continue;
      words.push_back(MakeRecoveredWord(candidate, input, segment.end));
    }
  }
  if (words.empty()) return assemble(nullptr);
  // Both edit families have the same correction penalty. Merge by the existing
  // word quality, with one shared six-word output bound rather than 6 + 6.
  for (const auto& candidate : prefix)
    if (candidate->type() == "qingyu_spelling_correction" && seen.insert(candidate->text()).second)
      words.push_back(candidate);
  std::stable_sort(words.begin(), words.end(), [](const auto& a, const auto& b) { return a->quality() > b->quality(); });
  auto recovery = rime::New<rime::FifoTranslation>();
  // A repeated key already supports deletion recovery. Preserve its homophone
  // order instead of forcing other insertion interpretations into the menu.
  if (std::adjacent_find(input.begin(), input.end()) != input.end()) {
    for (size_t i = 0; i < std::min<size_t>(6, words.size()); ++i) recovery->Append(words[i]);
    return assemble(recovery, true);
  }
  // The menu represents spelling alternatives as well as homophones. Reserve a
  // pair of word forms per canonical code before filling the remaining slots,
  // so one code's homophones cannot crowd out another plausible spelling.
  std::map<rime::Code, size_t> group_index;
  std::vector<std::vector<size_t>> groups;
  for (size_t i = 0; i < words.size(); ++i) {
    auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(words[i]));
    if (!phrase) continue;
    auto inserted = group_index.emplace(phrase->code(), groups.size());
    if (inserted.second) groups.emplace_back();
    groups[inserted.first->second].push_back(i);
  }
  std::vector<bool> emitted(words.size());
  size_t count = 0;
  for (const auto& group : groups) {
    for (size_t j = 0; j < std::min<size_t>(2, group.size()) && count < 6; ++j) {
      const auto i = group[j];
      recovery->Append(words[i]); emitted[i] = true; ++count;
    }
  }
  for (size_t i = 0; i < words.size() && count < 6; ++i) {
    if (emitted[i]) continue;
    recovery->Append(words[i]); ++count;
  }
  return assemble(recovery, true);
}
