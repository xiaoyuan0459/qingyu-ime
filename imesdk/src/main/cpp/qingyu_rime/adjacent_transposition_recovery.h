#pragma once
#include "word_correction.h"
#include "canonical_syllabary.h"
#include <rime/dict/vocabulary.h>
#include <array>
#include <vector>

class AdjacentTranspositionRecovery {
 public:
  rime::an<rime::Translation> Recover(
      const std::string& input, const rime::Segment& segment, rime::Dictionary* dictionary, CanonicalSyllabary& syllabary,
      rime::an<rime::Translation> original, size_t remaining_probes,
      const std::function<bool(const rime::an<rime::Candidate>&)>& admissible,
      const std::function<bool(const rime::an<rime::Candidate>&)>& enabled_spelling,
      const std::function<rime::an<rime::Translation>(const std::string&, const rime::Code&)>& query);
 private:
  static std::string Canonical(const rime::an<rime::Candidate>& candidate, const std::vector<std::string>& syllables);
  static std::vector<rime::Code> FindCodes(rime::Dictionary* dictionary, const std::string& input, size_t limit,
      const CanonicalSyllabary& syllabary);
};
