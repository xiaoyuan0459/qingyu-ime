#include "stroke_wildcard.h"

#include <algorithm>
#include <cctype>
#include <unordered_set>
#include <rime_api.h>

namespace {
RimeSessionId session = 0;
}

void CloseStrokeWildcardSession() {
  if (session != 0) RimeDestroySession(session);
  session = 0;
}

std::vector<std::string> QueryStrokeWildcard(const std::string& pattern,
                                             int max_candidates) {
  std::vector<std::string> result;
  if (pattern.empty() || pattern.size() > 32 || max_candidates < 1 ||
      max_candidates > 500) {
    return result;
  }
  int wildcards = 0;
  for (char c : pattern) {
    if (c == '*') {
      ++wildcards;
    } else if (c != 'h' && c != 's' && c != 'p' && c != 'n' && c != 'z') {
      return result;
    }
  }
  // 每个通配 5 路展开，两处通配已是 25 次引擎查询；再多会把主线程按键挡住。
  if (wildcards < 1 || wildcards > 2) return result;

  std::vector<std::string> codes{""};
  for (char c : pattern) {
    if (c == '*') {
      std::vector<std::string> next;
      next.reserve(codes.size() * 5);
      for (const auto& code : codes) {
        for (char stroke : {'h', 's', 'p', 'n', 'z'}) next.push_back(code + stroke);
      }
      codes.swap(next);
    } else {
      for (auto& code : codes) code.push_back(c);
    }
  }
  if (codes.empty() || codes.size() > 25) return result;

  if (session == 0) {
    session = RimeCreateSession();
    if (session == 0 || !RimeSelectSchema(session, "stroke")) {
      CloseStrokeWildcardSession();
      return result;
    }
    // 只读查询：不打开用户词库，避免与主会话争同一份 LevelDB。
    RimeSetOption(session, "disable_user_dict", True);
    RimeSetOption(session, "traditionalization", False);
    RimeSetOption(session, "emoji", False);
  }

  std::vector<std::vector<std::string>> per_code;
  per_code.reserve(codes.size());
  for (const auto& code : codes) {
    RimeClearComposition(session);
    for (char c : code) {
      RimeProcessKey(session, std::toupper(static_cast<unsigned char>(c)), 0);
    }
    RimeContext context = {};
    RIME_STRUCT_INIT(RimeContext, context);
    if (!RimeGetContext(session, &context)) continue;
    std::vector<std::string> found;
    for (int i = 0; i < context.menu.num_candidates; ++i) {
      const char* text = context.menu.candidates[i].text;
      if (!text || !*text) continue;
      found.emplace_back(text);
    }
    RimeFreeContext(&context);
    if (!found.empty()) per_code.push_back(std::move(found));
  }
  RimeClearComposition(session);

  std::unordered_set<std::string> seen;
  for (size_t rank = 0; result.size() < static_cast<size_t>(max_candidates); ++rank) {
    bool any = false;
    for (const auto& found : per_code) {
      if (rank >= found.size()) continue;
      any = true;
      if (seen.insert(found[rank]).second) result.push_back(found[rank]);
      if (result.size() >= static_cast<size_t>(max_candidates)) break;
    }
    if (!any) break;
  }
  return result;
}
