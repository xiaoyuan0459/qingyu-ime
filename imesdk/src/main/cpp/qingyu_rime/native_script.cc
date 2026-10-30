#include "native_script.h"
#include <algorithm>
#include <rime/candidate.h>
#include <rime/component.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/simplifier.h>
#include <rime/gear/poet.h>
#include <rime/dict/corrector.h>
#include <rime/menu.h>
#include <rime/registry.h>
#include <rime/service.h>
#include <rime/translation.h>

namespace {
constexpr const char* kMode = "qingyu_script_mode";
constexpr const char* kOriginal = "qingyu_original";

class OriginalOnlyFilter : public rime::Filter {
 public:
  explicit OriginalOnlyFilter(const rime::Ticket& ticket) : Filter(ticket) {}
  rime::an<rime::Translation> Apply(rime::an<rime::Translation> source,
                                     rime::CandidateList*) override { return source; }
};
class OriginalTranslation : public rime::Translation {
 public:
  explicit OriginalTranslation(rime::an<rime::Translation> source) : source_(source) {
    set_exhausted(!source_ || source_->exhausted());
  }
  bool Next() override {
    candidate_.reset();
    if (exhausted()) return false;
    const bool next = source_->Next();
    set_exhausted(source_->exhausted());
    return next;
  }
  rime::an<rime::Candidate> Peek() override {
    if (exhausted()) return nullptr;
    if (!candidate_) {
      auto original = source_->Peek();
      if (!original) return nullptr;
      candidate_ = rime::New<rime::ShadowCandidate>(original, kOriginal);
      candidate_->set_quality(original->quality() - 8.0);
    }
    return candidate_;
  }
 private:
  rime::an<rime::Translation> source_;
  rime::an<rime::Candidate> candidate_;
};

class NativeScriptTranslator : public rime::ScriptTranslator {
 public:
  explicit NativeScriptTranslator(const rime::Ticket& ticket) : ScriptTranslator(ticket) {}
  rime::an<rime::Translation> Query(const std::string& input, const rime::Segment& segment) override {
    const auto mode = engine_->context()->get_property(kMode);
    const bool traditional_dictionary = name_space_ == "native_traditional";
    if (traditional_dictionary && mode != "1" && mode != "2") return nullptr;
    auto result = ScriptTranslator::Query(input, segment);
    // Preserve the genuine candidate for selection and dictionary-specific learning.
    if (!traditional_dictionary && mode == "1" && result)
      return rime::New<OriginalTranslation>(result);
    return result;
  }
};

bool Original(const rime::an<rime::Candidate>& candidate) {
  if (!candidate) return false;
  if (candidate->type() == kOriginal) return true;
  if (auto combined = rime::As<rime::UniquifiedCandidate>(candidate)) {
    const auto& items = combined->items();
    return !items.empty() && std::all_of(items.begin(), items.end(), Original);
  }
  if (auto shadow = rime::As<rime::ShadowCandidate>(candidate)) {
    return shadow->text() == shadow->item()->text() && Original(shadow->item());
  }
  return false;
}
}

void RegisterNativeScriptTranslator() {
  rime::Registry::instance().Register("qingyu_native", new rime::Component<NativeScriptTranslator>);
  rime::Registry::instance().Register("qingyu_emoji", new rime::Component<rime::Simplifier>);
  // Imported packs cannot reintroduce arbitrary t2s/s2t filters under another option name.
  rime::Registry::instance().Register("simplifier", new rime::Component<OriginalOnlyFilter>);
}

bool SetNativeScriptMode(RimeSessionId session_id, int mode) {
  if (mode < 0 || mode > 2) return false;
  auto session = rime::Service::instance().GetSession(session_id);
  if (!session || !session->context()) return false;
  auto* context = session->context();
  // Old imported packs cannot silently re-enable a conversion fallback.
  if (RimeGetOption(session_id, "simplification")) RimeSetOption(session_id, "simplification", False);
  if (RimeGetOption(session_id, "traditionalization")) RimeSetOption(session_id, "traditionalization", False);
  const auto value = std::to_string(mode);
  if (context->get_property(kMode) != value) {
    context->set_property(kMode, value);
    context->RefreshNonConfirmedComposition();
  }
  return true;
}

bool IsOriginalCandidate(RimeSessionId session_id, size_t index) {
  auto session = rime::Service::instance().GetSession(session_id);
  if (!session || !session->context()->HasMenu()) return false;
  char mode[4] = {};
  char schema[128] = {};
  RimeGetProperty(session_id, kMode, mode, sizeof(mode));
  RimeGetCurrentSchema(session_id, schema, sizeof(schema));
  if (mode[0] == '1' && std::string(schema) == "stroke") return true;
  auto menu = session->context()->composition().back().menu;
  return menu && Original(menu->GetCandidateAt(index));
}
