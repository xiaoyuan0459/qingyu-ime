#pragma once
#include <string>
#include <vector>

// Reads the active pack's immutable stroke.reverse.bin; it never opens an input session.
std::vector<std::string> LookupStrokeCodes(const std::string& file,
                                         const std::string& characters);
