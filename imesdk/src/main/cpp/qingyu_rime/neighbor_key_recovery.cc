#include "neighbor_key_recovery.h"
#include <rime/algo/syllabifier.h>
#include <rime/dict/dictionary.h>
#include <rime/gear/poet.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <deque>
#include <map>
#include <set>
#include <string_view>
#include <unordered_set>

namespace {
bool Neighbors(char a, char b) {
  for (std::string_view row : {"qwertyuiop", "asdfghjkl", "zxcvbnm"}) {
    const auto i = row.find(a);
    if (i != row.npos && ((i && row[i-1] == b) || (i+1 < row.size() && row[i+1] == b))) return true;
  }
  return false;
}

bool SingleNeighbor(std::string_view canonical, std::string_view input) {
  if (canonical.size() != input.size()) return false;
  size_t i = 0;
  while (i < input.size() && canonical[i] == input[i]) ++i;
  return i < input.size() && Neighbors(canonical[i], input[i]) &&
      canonical.substr(i+1) == input.substr(i+1);
}

rime::an<rime::Candidate> NeighborWord(const rime::an<rime::Candidate>& candidate,
                                    const std::string& input, size_t end) {
  auto genuine = rime::Candidate::GetGenuineCandidate(candidate);
  auto phrase = rime::As<rime::Phrase>(genuine);
  if (!phrase) return nullptr;
  // librime unwraps one shadow for selection and learning; never nest wrappers.
  auto result = MakeRecoveredWord(genuine, input, end);
  // ScriptTranslation uses exp(log_weight) + translator priority. An absolute
  // 0.5 subtraction overwhelms that probability and hides even strong repairs.
  // Apply the same edit cost to log weight while keeping the translator prior.
  const double probability = std::exp(phrase->entry().weight);
  const double original_quality = candidate->quality() +
      (candidate->type() == "qingyu_spelling_correction" ? 0.5 : 0.0);
  result->set_quality(original_quality + probability * std::expm1(-0.5));
  return result;
}

std::string Canonical(const rime::an<rime::Candidate>& candidate, const CanonicalSyllabary& syllabary) {
  auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
  if (!phrase) return {};
  std::string code;
  for (auto id : phrase->code()) {
    if (id < 0 || static_cast<size_t>(id) >= syllabary.syllables().size() || syllabary.syllables()[id].empty()) return {};
    code += syllabary.syllables()[id];
  }
  return code;
}

std::vector<rime::Code> FindCodes(rime::Dictionary* dictionary, const std::string& input,
                               size_t limit, const CanonicalSyllabary& syllabary) {
  const size_t terminal = 2 * input.size() + 1;
  rime::SyllableGraph graph;
  graph.input_length = graph.interpreted_length = terminal;
  std::array<bool, 34> reachable{};
  reachable[0] = true;
  std::deque<rime::EdgeProperties> properties;
  auto add = [&](size_t from, size_t to, int32_t id) {
    if (properties.size() == 4096) return false;
    properties.emplace_back();
    properties.back().end_pos = to;
    reachable[to] = true;
    graph.indices[from][id].push_back(&properties.back());
    return true;
  };
  // The edited state has only exact edges, so syllables cannot compose two edits.
  for (size_t pos = 0; pos < input.size(); ++pos) {
    for (size_t edited = 0; edited < 2; ++edited) {
      const size_t from = 2 * pos + edited;
      if (!reachable[from]) continue;
      for (char initial = 'a'; initial <= 'z'; ++initial) {
        if (initial != input[pos] && (edited || !Neighbors(initial, input[pos]))) continue;
        for (auto id : syllabary.leading(initial)) {
          const auto& sound = syllabary.syllables()[id];
          const size_t end = pos + sound.size();
          if (end > input.size()) continue;
          // Omission's buckets also include second letters and single-letter
          // syllables. A substitution edge must come from its actual initial once.
          if (sound[0] != initial) continue;
          const auto slice = std::string_view(input).substr(pos, sound.size());
          if (sound == slice) {
            if (!add(from, 2 * end + edited, id)) return {};
          } else if (!edited && SingleNeighbor(sound, slice) && !add(from, 2 * end + 1, id)) return {};
        }
      }
    }
  }
  if (!reachable[terminal]) return {};
  std::array<bool, 34> finishes{};
  finishes[terminal] = true;
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
    std::string spelling;
    for (auto id : entry->code) {
      if (id < 0 || static_cast<size_t>(id) >= syllabary.syllables().size()) { spelling.clear(); break; }
      spelling += syllabary.syllables()[id];
    }
    if (SingleNeighbor(spelling, input) && seen.insert(entry->code).second) codes.push_back(entry->code);
  }
  return codes;
}
}  // namespace

rime::an<rime::Translation> NeighborKeyRecovery::Recover(
    const std::string& input, const rime::Segment& segment, rime::Dictionary* dictionary,
    CanonicalSyllabary& syllabary, rime::an<rime::Translation> original, size_t remaining_probes,
    const std::function<bool(const rime::an<rime::Candidate>&)>& admissible,
    const std::function<bool(const rime::an<rime::Candidate>&)>& enabled_spelling,
    const std::function<rime::an<rime::Translation>(const std::string&, const rime::Code&)>& query) {
  if (!remaining_probes || !dictionary || !dictionary->loaded() || input.size() < 4 || input.size() > 16 ||
      !std::all_of(input.begin(), input.end(), [](char c) { return c >= 'a' && c <= 'z'; })) return original;
  syllabary.Prepare(dictionary);
  if (original && !original->exhausted() && original->Peek()->type() == "qingyu_spelling_correction" &&
      SingleNeighbor(Canonical(original->Peek(), syllabary), input)) return original;
  const auto codes = FindCodes(dictionary, input, remaining_probes, syllabary);
  if (codes.empty()) return original;
  std::vector<rime::an<rime::Candidate>> prefix;
  std::vector<rime::an<rime::Candidate>> leading;
  std::vector<rime::an<rime::Candidate>> literal_words;
  std::unordered_set<std::string> emitted;
  bool preserve_literal_order = false;
  for (size_t rank = 0; rank < 30 && original && !original->exhausted(); ++rank) {
    auto candidate = original->Peek();
    if (!candidate) { original->Next(); continue; }
    if (candidate->end() == segment.end) {
      if (leading.empty()) leading.push_back(candidate);
      else if (leading.size() == 1 && Canonical(candidate, syllabary) == Canonical(leading.front(), syllabary) &&
          ((candidate->type() == "qingyu_spelling_correction" && leading.front()->type() == "qingyu_spelling_correction") ||
           (!admissible(leading.front()) && admissible(candidate)))) leading.push_back(candidate);
      else if (leading.size() == 1 && !admissible(leading.front()) && admissible(candidate)) leading.push_back(candidate);
    }
    if (candidate && candidate->end() == segment.end && admissible(candidate)) {
      auto genuine = rime::Candidate::GetGenuineCandidate(candidate);
      auto phrase = rime::As<rime::Phrase>(genuine);
      // A legal abbreviation keeps its leading interpretation, but does not
      // reserve the entire spelling window like a literal complete word.
      if (phrase && !rime::As<rime::Sentence>(genuine) &&
          Canonical(candidate, syllabary) == input) literal_words.push_back(candidate);
    }
    prefix.push_back(candidate);
    original->Next();
  }
  auto assemble = [&](rime::an<rime::Translation> recovered) -> rime::an<rime::Translation> {
    auto rest = rime::New<rime::FifoTranslation>();
    auto first = rime::New<rime::FifoTranslation>();
    for (const auto& candidate : prefix) {
      // Rime uniquifies equal text even when consumption spans differ. The
      // recovered full word must own selection instead of an earlier prefix.
      if (recovered && candidate->end() != segment.end && emitted.count(candidate->text())) continue;
      const bool protected_item = std::find(leading.begin(), leading.end(), candidate) != leading.end();
      const bool merged_word = std::find(literal_words.begin(), literal_words.end(), candidate) != literal_words.end();
      if (recovered && (protected_item || (preserve_literal_order && merged_word))) first->Append(candidate);
      else if (recovered && merged_word) continue;
      else if (candidate && (!recovered || candidate->type() != "qingyu_spelling_correction")) rest->Append(candidate);
    }
    auto result = rime::New<rime::UnionTranslation>();
    *result += first; *result += recovered; *result += rest; *result += original;
    return result;
  };
  std::vector<rime::an<rime::Candidate>> words;
  std::unordered_set<std::string> seen;
  for (const auto& candidate : leading) seen.insert(candidate->text());
  for (const auto& candidate : literal_words) seen.insert(candidate->text());
  for (const auto& code : codes) {
    std::string spelling;
    for (auto id : code) spelling += syllabary.syllables()[id];
    auto stream = query(spelling, code);
    for (size_t rank = 0; rank < 16 && stream && !stream->exhausted(); ++rank, stream->Next()) {
      auto candidate = stream->Peek();
      if (!candidate || candidate->end() != segment.end || Canonical(candidate, syllabary) != spelling) continue;
      auto genuine = rime::Candidate::GetGenuineCandidate(candidate);
      auto phrase = rime::As<rime::Phrase>(genuine);
      if (!phrase || rime::As<rime::Sentence>(genuine) || !phrase->entry().IsExactMatch()) continue;
      const auto& text = candidate->text();
      const auto length = std::count_if(text.begin(), text.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; });
      if (length < 2 || length > 4 || std::any_of(text.begin(), text.end(), [](unsigned char c) { return c < 0x80; }) ||
          !seen.insert(text).second) continue;
      words.push_back(NeighborWord(candidate, input, segment.end));
    }
  }
  if (words.empty()) return assemble(nullptr);
  std::map<rime::Code, size_t> literal_counts;
  double literal_mass = 0;
  for (const auto& candidate : literal_words) {
    auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
    if (literal_counts[phrase->code()]++ < 16) literal_mass += std::exp(phrase->entry().weight);
  }
  std::map<rime::Code, double> repair_mass;
  for (const auto& candidate : words) {
    auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
    repair_mass[phrase->code()] += std::exp(phrase->entry().weight);
  }
  const double strongest_repair = std::max_element(repair_mass.begin(), repair_mass.end(),
      [](const auto& a, const auto& b) { return a.second < b.second; })->second;
  // A well-supported literal spelling must retain its homophones. Compare
  // code-level evidence, not just the most frequent repaired word form.
  preserve_literal_order = literal_mass >= strongest_repair;
  if (!preserve_literal_order && literal_mass >= strongest_repair * std::exp(-0.5)) {
    for (const auto& candidate : literal_words) {
      if (leading.size() == 2) break;
      if (std::find(leading.begin(), leading.end(), candidate) == leading.end()) leading.push_back(candidate);
    }
  }
  for (const auto& candidate : prefix)
    if (candidate && candidate->type() == "qingyu_spelling_correction" && seen.insert(candidate->text()).second)
      if (auto word = NeighborWord(candidate, input, segment.end)) words.push_back(word);
  std::stable_sort(words.begin(), words.end(), [](const auto& a, const auto& b) { return a->quality() > b->quality(); });
  auto recovered = rime::New<rime::FifoTranslation>();
  const size_t preserved_recovery = std::count_if(leading.begin(), leading.end(), [](const auto& candidate) {
    return candidate->type() == "qingyu_spelling_correction";
  });
  const size_t available = 6 - std::min<size_t>(6, preserved_recovery);
  std::vector<rime::an<rime::Candidate>> combined;
  for (const auto& candidate : literal_words)
    if (!preserve_literal_order && std::find(leading.begin(), leading.end(), candidate) == leading.end()) combined.push_back(candidate);
  for (size_t i = 0; i < std::min(available, words.size()); ++i) {
    combined.push_back(words[i]); emitted.insert(words[i]->text());
  }
  // Literal words retain their original quality; a repaired word already pays
  // the established edit cost. Neither stream gets a whole-prefix priority.
  std::stable_sort(combined.begin(), combined.end(), [](const auto& a, const auto& b) { return a->quality() > b->quality(); });
  for (const auto& candidate : combined) recovered->Append(candidate);
  return assemble(recovered);
}
