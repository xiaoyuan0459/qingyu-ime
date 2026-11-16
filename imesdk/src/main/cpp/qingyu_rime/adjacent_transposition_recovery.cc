#include "adjacent_transposition_recovery.h"
#include <rime/algo/syllabifier.h>
#include <rime/dict/dictionary.h>
#include <rime/gear/poet.h>
#include <algorithm>
#include <deque>
#include <set>
#include <string_view>
#include <unordered_set>

namespace {
bool SingleSwap(std::string_view canonical, std::string_view input) {
  if (canonical.size() != input.size()) return false;
  size_t i = 0;
  while (i < input.size() && canonical[i] == input[i]) ++i;
  return i + 1 < input.size() && canonical[i] == input[i+1] && canonical[i+1] == input[i] &&
      canonical.compare(i+2, std::string::npos, input, i+2, std::string::npos) == 0;
}

}  // namespace

std::string AdjacentTranspositionRecovery::Canonical(const rime::an<rime::Candidate>& candidate, const std::vector<std::string>& syllables_) {
  auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
  if (!phrase) return {};
  std::string code;
  for (auto id : phrase->code()) {
    if (id < 0 || static_cast<size_t>(id) >= syllables_.size() || syllables_[id].empty()) return {};
    code += syllables_[id];
  }
  return code;
}

std::vector<rime::Code> AdjacentTranspositionRecovery::FindCodes(
    rime::Dictionary* dictionary, const std::string& input, size_t limit, const CanonicalSyllabary& syllabary) {
  const auto& syllables_ = syllabary.syllables();
  const size_t size = input.size();
  // States 0/1 mean no swap / one completed swap. A pending state records
  // the first letter required from the next syllable after a boundary swap.
  auto node = [](size_t pos, size_t state) { return 28 * pos + state; };
  const size_t terminal = node(size, 1);
  rime::SyllableGraph graph;
  graph.input_length = graph.interpreted_length = terminal;
  std::deque<rime::EdgeProperties> properties;
  std::array<bool, 28 * 16 + 2> reachable{};
  reachable[0] = true;
  auto add = [&](size_t from, size_t to, int32_t id) {
    if (properties.size() == 4096) return false;
    properties.emplace_back();
    auto& props = properties.back();
    props.end_pos = to;
    reachable[to] = true;
    graph.indices[from][id].push_back(&props);
    return true;
  };
  for (size_t pos = 0; pos < size; ++pos) {
    for (size_t state = 0; state < 28; ++state) {
      const auto from = node(pos, state);
      if (!reachable[from]) continue;
      const size_t initials[] = {state < 2 ? size_t(input[pos]-'a') : state-2,
                                pos+1 < size ? size_t(input[pos+1]-'a') : size_t(input[pos]-'a')};
      for (size_t initial = 0; initial < (state == 0 && initials[0] != initials[1] ? 2u : 1u); ++initial) {
       for (auto id : syllabary.leading('a' + initials[initial])) {
        const auto& sound = syllables_[id];
        if (size_t(sound[0]-'a') != initials[initial]) continue;
        const auto end = pos + sound.size();
        if (end > size) continue;
        if (state >= 2) {
          if (input.compare(pos+1, sound.size()-1, sound, 1, sound.size()-1) == 0 &&
              !add(from, node(end, 1), id)) return {};
          continue;
        }
        if (input.compare(pos, sound.size(), sound) == 0 && !add(from, node(end, state), id)) return {};
        if (state != 0) continue;
        if (SingleSwap(sound, std::string_view(input).substr(pos, sound.size())) && !add(from, node(end, 1), id)) return {};
        if (end < size && sound.back() == input[end] && sound.back() != input[end-1] &&
            input.compare(pos, sound.size()-1, sound, 0, sound.size()-1) == 0 &&
            !add(from, node(end, 2 + input[end-1]-'a'), id)) return {};
       }
      }
    }
  }
  if (!reachable[terminal]) return {};
  std::array<bool, 28 * 16 + 2> finishes{};
  finishes[terminal] = true;
  // Partial dictionary matches are useless here. Remove branches that cannot
  // finish the one-swap spelling before asking the dictionary to traverse them.
  for (auto position = graph.indices.rbegin(); position != graph.indices.rend(); ++position) {
    auto& sounds = position->second;
    for (auto sound = sounds.begin(); sound != sounds.end();) {
      auto& edges = sound->second;
      edges.erase(std::remove_if(edges.begin(), edges.end(), [&](const auto* edge) {
        return !finishes[edge->end_pos];
      }), edges.end());
      if (edges.empty()) sound = sounds.erase(sound);
      else { finishes[position->first] = true; ++sound; }
    }
  }
  auto found = dictionary->Lookup(graph, 0, false);
  if (!found) return {};
  auto full = found->find(terminal);
  if (full == found->end()) return {};
  auto& entries = full->second;
  std::set<rime::Code> seen;
  std::vector<rime::Code> codes;
  for (size_t inspected = 0; inspected < 512 && !entries.exhausted() && codes.size() < limit;
       ++inspected, entries.Next()) {
    const auto entry = entries.Peek();
    if (entry->code.size() < 2 || entry->code.size() > 4) continue;
    std::string canonical;
    for (auto id : entry->code) {
      if (id < 0 || static_cast<size_t>(id) >= syllables_.size()) { canonical.clear(); break; }
      canonical += syllables_[id];
    }
    if (SingleSwap(canonical, input) && seen.insert(entry->code).second) codes.push_back(entry->code);
  }
  return codes;
}

rime::an<rime::Translation> AdjacentTranspositionRecovery::Recover(
    const std::string& input, const rime::Segment& segment, rime::Dictionary* dictionary,
    CanonicalSyllabary& syllabary, rime::an<rime::Translation> original, size_t remaining_probes,
    const std::function<bool(const rime::an<rime::Candidate>&)>& admissible,
    const std::function<bool(const rime::an<rime::Candidate>&)>& enabled_spelling,
    const std::function<rime::an<rime::Translation>(const std::string&, const rime::Code&)>& query) {
  if (!remaining_probes || !dictionary || !dictionary->loaded() || input.size() < 4 || input.size() > 16 ||
      !std::all_of(input.begin(), input.end(), [](char c) { return c >= 'a' && c <= 'z'; })) return original;
  syllabary.Prepare(dictionary);
  const auto& syllables_ = syllabary.syllables();
  if (original && !original->exhausted()) {
    const auto first = original->Peek();
    if (first && first->end() == segment.end) {
      auto genuine = rime::Candidate::GetGenuineCandidate(first);
      const bool word = rime::As<rime::Phrase>(genuine) && !rime::As<rime::Sentence>(genuine);
      const auto spelling = Canonical(first, syllables_);
      const bool completion = spelling.size() > input.size() && spelling.compare(0, input.size(), input) == 0;
      if (first->type() == "qingyu_spelling_correction" ||
          (word && (spelling == input || ((completion || SingleSwap(spelling, input)) && admissible(first))))) return original;
    }
  }
  const auto codes = FindCodes(dictionary, input, remaining_probes, syllabary);
  if (codes.empty()) return original;
  std::vector<rime::an<rime::Candidate>> prefix;
  rime::an<rime::Candidate> leading;
  bool already_recovered = false;
  bool leading_in_tail = false;
  for (size_t rank = 0; rank < 30 && original && !original->exhausted(); ++rank) {
    auto candidate = original->Peek();
    if (!candidate) { original->Next(); continue; }
    if (candidate->end() == segment.end) {
      auto genuine = rime::Candidate::GetGenuineCandidate(candidate);
      const bool word = rime::As<rime::Phrase>(genuine) && !rime::As<rime::Sentence>(genuine);
      const auto spelling = Canonical(candidate, syllables_);
      const bool completion = spelling.size() > input.size() && spelling.compare(0, input.size(), input) == 0;
      if (candidate->type() == "qingyu_spelling_correction" ||
          (word && (spelling == input || ((completion || SingleSwap(spelling, input)) && admissible(candidate))))) {
        already_recovered = true;
        break;
      }
      if (!leading && admissible(candidate) &&
          (HasLiteralWordSpelling(dictionary, candidate, input) || enabled_spelling(candidate))) leading = candidate;
    }
    // Leaving the last item in the source avoids generating a needless 31st word.
    if (rank + 1 == 30) { leading_in_tail = leading == candidate; break; }
    prefix.push_back(candidate);
    original->Next();
  }
  auto assemble = [&](rime::an<rime::Translation> recovered) -> rime::an<rime::Translation> {
    auto rest = rime::New<rime::FifoTranslation>();
    auto first = rime::New<rime::FifoTranslation>();
    for (const auto& candidate : prefix) {
      if (recovered && candidate == leading) first->Append(candidate);
      else rest->Append(candidate);
    }
    auto result = rime::New<rime::UnionTranslation>();
    *result += first; *result += recovered; *result += rest; *result += original;
    return result;
  };
  if (already_recovered) return assemble(nullptr);
  std::vector<rime::an<rime::Candidate>> words;
  std::unordered_set<std::string> seen;
  for (const auto& code : codes) {
    std::string spelling;
    for (auto id : code) spelling += syllables_[id];
    auto stream = query(spelling, code);
    for (size_t rank = 0; rank < 16 && stream && !stream->exhausted(); ++rank, stream->Next()) {
      auto candidate = stream->Peek();
      if (!candidate || candidate->end() != segment.end || Canonical(candidate, syllables_) != spelling) continue;
      auto genuine = rime::Candidate::GetGenuineCandidate(candidate);
      auto phrase = rime::As<rime::Phrase>(genuine);
      if (!phrase || rime::As<rime::Sentence>(genuine) || !phrase->entry().IsExactMatch()) continue;
      const auto& text = candidate->text();
      const auto length = std::count_if(text.begin(), text.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; });
      if (length < 2 || length > 4 || std::any_of(text.begin(), text.end(), [](unsigned char c) { return c < 0x80; }) ||
          !seen.insert(text).second) continue;
      words.push_back(MakeRecoveredWord(candidate, input, segment.end));
    }
  }
  if (words.empty()) return assemble(nullptr);
  if (leading_in_tail) { prefix.push_back(leading); original->Next(); }
  std::stable_sort(words.begin(), words.end(), [](const auto& a, const auto& b) { return a->quality() > b->quality(); });
  auto recovered = rime::New<rime::FifoTranslation>();
  for (size_t i = 0; i < std::min<size_t>(6, words.size()); ++i) recovered->Append(words[i]);
  return assemble(recovered);
}
