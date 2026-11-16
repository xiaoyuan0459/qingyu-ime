#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <vector>
namespace rime { class Dictionary; }

// One translator owns this dictionary-only index; recovery never stores input here.
class CanonicalSyllabary {
 public:
  void Prepare(rime::Dictionary* dictionary);
  const std::vector<std::string>& syllables() const { return syllables_; }
  // Input bytes outside a-z (delimiters, digits) have no leading syllables;
  // indexing the array with them would read past it.
  const std::vector<int32_t>& leading(char letter) const {
    static const std::vector<int32_t> none;
    return letter >= 'a' && letter <= 'z' ? leading_syllables_[letter - 'a'] : none;
  }
  const std::vector<int32_t>& singles() const { return single_letter_syllables_; }
 private:
  rime::Dictionary* dictionary_ = nullptr;
  std::vector<std::string> syllables_;
  std::array<std::vector<int32_t>, 26> leading_syllables_;
  std::vector<int32_t> single_letter_syllables_;
};
