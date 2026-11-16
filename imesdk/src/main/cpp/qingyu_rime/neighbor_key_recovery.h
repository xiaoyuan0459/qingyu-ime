#pragma once
#include "word_correction.h"
#include "canonical_syllabary.h"
#include <rime/dict/vocabulary.h>

class NeighborKeyRecovery {
 public:
  rime::an<rime::Translation> Recover(
      const std::string& input, const rime::Segment& segment, rime::Dictionary* dictionary,
      CanonicalSyllabary& syllabary, rime::an<rime::Translation> original, size_t remaining_probes,
      const std::function<bool(const rime::an<rime::Candidate>&)>& admissible,
      const std::function<bool(const rime::an<rime::Candidate>&)>& enabled_spelling,
      const std::function<rime::an<rime::Translation>(const std::string&, const rime::Code&)>& query);
};
