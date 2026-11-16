#include "canonical_syllabary.h"
#include <rime/dict/dictionary.h>
#include <algorithm>
#include <array>

void CanonicalSyllabary::Prepare(rime::Dictionary* dictionary) {
  if (!dictionary || !dictionary->loaded() || !dictionary->primary_table()) return;
  if (dictionary_ == dictionary) return;
  auto table = dictionary->primary_table();
  auto metadata = table->metadata();
  if (!metadata || metadata->num_syllables > 1024) return;
  // Build locally and publish once: a partially filled index must never be
  // visible to a query, and the published one keeps its storage.
  std::vector<std::string> syllables;
  std::array<std::vector<int32_t>, 26> leading;
  std::vector<int32_t> singles;
  syllables.reserve(metadata->num_syllables);
  for (size_t id = 0; id < metadata->num_syllables; ++id) {
    auto sound = table->GetSyllableById(id);
    if (sound.empty() || sound.size() > 6 ||
        !std::all_of(sound.begin(), sound.end(), [](char c) { return c >= 'a' && c <= 'z'; })) {
      syllables.emplace_back();
      continue;
    }
    syllables.push_back(sound);
    if (sound.size() == 1) {
      singles.push_back(static_cast<int32_t>(id));
      for (auto& ids : leading) ids.push_back(static_cast<int32_t>(id));
    } else {
      leading[sound[0] - 'a'].push_back(static_cast<int32_t>(id));
      if (sound[1] != sound[0]) leading[sound[1] - 'a'].push_back(static_cast<int32_t>(id));
    }
  }
  syllables_ = std::move(syllables);
  leading_syllables_ = std::move(leading);
  single_letter_syllables_ = std::move(singles);
  dictionary_ = dictionary;
}
