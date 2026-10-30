#pragma once

#include <string>
#include <vector>

// Callers hold the JNI mutex: librime serializes both input and lookup sessions.
std::vector<std::string> QueryAssociation(const std::string& input,
                                          const std::string& prefix,
                                          int max_pages, int mode);
void CloseAssociationSession();
