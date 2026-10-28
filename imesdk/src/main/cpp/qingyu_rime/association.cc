#include "association.h"

#include <algorithm>
#include <unordered_set>
#include <rime_api.h>

int RimeGetKeycodeByName(const char* name);

namespace {
RimeSessionId session = 0;
}

void CloseAssociationSession() {
  if (session != 0) RimeDestroySession(session);
  session = 0;
}

std::vector<std::string> QueryAssociation(const std::string& input,
                                          const std::string& prefix,
                                          int max_pages) {
  std::vector<std::string> result;
  // 允许一个音节分隔符：联想探测必须把前缀音节钉死，否则纠错表会把
  // 「yuanl」重新切分成「yua'nl」，联想词被无关候选挤掉。
  const bool valid_input = std::all_of(
      input.begin(), input.end(),
      [](char c) { return (c >= 'a' && c <= 'z') || c == '\''; }) &&
      std::count(input.begin(), input.end(), '\'') <= 1;
  if (input.empty() || input.size() > 32 || prefix.empty() ||
      max_pages < 1 || max_pages > 2 || !valid_input ||
      input.front() == '\'' || input.back() == '\'') {
    return result;
  }
  if (session == 0) {
    session = RimeCreateSession();
    if (session == 0 || !RimeSelectSchema(session, "associate")) {
      CloseAssociationSession();
      return result;
    }
    RimeSetOption(session, "disable_user_dict", True);
  }
  RimeClearComposition(session);
  for (char letter : input) RimeProcessKey(session, letter, 0);
  std::unordered_set<std::string> seen;
  for (int page = 0; page < max_pages; ++page) {
    RimeContext context = {};
    RIME_STRUCT_INIT(RimeContext, context);
    if (!RimeGetContext(session, &context)) break;
    const bool last_page = context.menu.is_last_page;
    for (int i = 0; i < context.menu.num_candidates; ++i) {
      const char* text = context.menu.candidates[i].text;
      if (!text) continue;
      std::string word(text);
      if (word.size() > prefix.size() && word.compare(0, prefix.size(), prefix) == 0 &&
          seen.insert(word).second) result.push_back(std::move(word));
    }
    RimeFreeContext(&context);
    if (last_page || !RimeProcessKey(session, RimeGetKeycodeByName("Page_Down"), 0)) break;
  }
  RimeClearComposition(session);
  return result;
}
