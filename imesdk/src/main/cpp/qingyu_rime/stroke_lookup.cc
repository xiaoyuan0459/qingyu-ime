#include "stroke_lookup.h"
#include <sstream>
#include <rime/dict/reverse_lookup_dictionary.h>

std::vector<std::string> LookupStrokeCodes(const std::string& file,
                                         const std::string& characters) {
  if (characters.empty() || characters.size() > 1024 || file.empty()) return {};
  std::vector<std::string> input;
  std::istringstream stream(characters);
  std::string character;
  while (std::getline(stream, character)) {
    if (character.size() < 3 || character.size() > 4 || input.size() >= 128) return {};
    input.push_back(character);
  }
  rime::ReverseDb database{rime::path(file)};
  if (!database.Load()) return {};
  std::vector<std::string> codes;
  for (const auto& text : input) {
    std::string code;
    database.Lookup(text, &code);
    codes.push_back(std::move(code));
  }
  return codes;
}
