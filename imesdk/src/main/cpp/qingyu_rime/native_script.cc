#include "native_script.h"
#include "spelling_policy.h"
#include "word_correction.h"
#include "missing_letter_recovery.h"
#include "adjacent_transposition_recovery.h"
#include "neighbor_key_recovery.h"
#include "t9_key_recovery.h"
#include <algorithm>
#include <array>
#include <optional>
#include <rime/candidate.h>
#include <rime/component.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/simplifier.h>
#include <rime/gear/poet.h>
#include <rime/dict/corrector.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/table.h>
#include <rime/menu.h>
#include <rime/registry.h>
#include <rime/service.h>
#include <rime/schema.h>
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
  explicit NativeScriptTranslator(const rime::Ticket& ticket) : ScriptTranslator(ticket) {
    if (engine_->schema()->schema_id() == "pinyin" && name_space_ != "native_traditional" &&
        dict() && dict()->loaded() && dict()->primary_table()) {
      dict()->primary_table()->PrepareSyllableLookup();
      syllabary_.Prepare(dict());
    }
  }
  bool AllowSpelling(int32_t id, const std::string& input, rime::SpellingType type) override {
    return !policy_enabled_ || spelling_policy_.Allows(dict(), fuzzy_groups_, t9_, id, input, type);
  }
  rime::an<rime::Translation> Query(const std::string& input, const rime::Segment& segment) override {
    const auto mode = engine_->context()->get_property(kMode);
    const bool traditional_dictionary = name_space_ == "native_traditional";
    if (traditional_dictionary && mode != "1" && mode != "2") return nullptr;
    // Original-form mode merges both dictionaries without rewriting either, so a
    // word present in both takes two candidate slots and pushes every answer one
    // rank down. Abbreviated or completed spellings double the whole list that
    // way (4,950-case set: 2 up, 186 down), and such a form is not one the user
    // typed. The Traditional side therefore answers only spellings that the input
    // spells out in full, which is what this mode did before completion worked.
    const bool spelled_out = traditional_dictionary && mode == "2";
    if (spelled_out && dict() && dict()->prism()) {
      // BuildSyllableGraph reuses the vertex map it is given, so every probe
      // needs a fresh graph; a cached one starts at "vertex 0 already visited".
      rime::SyllableGraph typed_graph;
      rime::Syllabifier typed_probe(delimiters(), false, false);
      typed_probe.SetSpellingFilter(
          [](rime::SyllableId, const std::string&, rime::SpellingType type) {
            return type <= rime::kFuzzySpelling;
          });
      if (typed_probe.BuildSyllableGraph(input, *dict()->prism(), &typed_graph) <
          input.size())
        return nullptr;
    }
    const auto& schema = engine_->schema()->schema_id();
    // The sparse Traditional dictionary competes with the original-form stream.
    // Rebuilding its sentence graph can displace an already valid fallback.
    const bool apply_policy = !traditional_dictionary && (schema == "pinyin" || schema == "t9_pinyin");
    policy_enabled_ = false;
    t9_ = schema == "t9_pinyin";
    fuzzy_groups_ = 0;
    const char* names[] = {"qingyu_fuzzy_zh_z", "qingyu_fuzzy_n_l", "qingyu_fuzzy_f_h", "qingyu_fuzzy_nasal"};
    for (int i = 0; i < 4; ++i)
      if (engine_->context()->get_option(names[i])) fuzzy_groups_ |= 1 << i;
    auto result = ScriptTranslator::Query(input, segment);
    // 九键的漏键恢复与全拼不共用编辑模型（见 t9_key_recovery.h），
    // 但纠错开关是同一个：关掉它，两条路径都不补候选。
    const bool correction_option = (schema == "pinyin" || t9_) &&
        engine_->context()->get_option("qingyu_word_correction");
    const bool recovery = schema == "pinyin" && correction_option &&
        (!traditional_dictionary || mode == "1");
    // The Traditional table is only indexed when it actually recovers; preparing
    // it while the schema's components are built runs before that table is usable.
    if (recovery && traditional_dictionary && dict() && dict()->loaded() &&
        dict()->primary_table()) {
      dict()->primary_table()->PrepareSyllableLookup();
      syllabary_.Prepare(dict());
    }
    size_t remaining_probes = 32;
    auto query_word = [&](const std::string& corrected) -> rime::an<rime::Translation> {
      if (!remaining_probes) return nullptr;
      --remaining_probes;
      return ScriptTranslator::Query(corrected, segment);
    };
    // The same leading candidates cross policy and recovery stages for one input.
    const bool recovery_input = recovery && input.size() >= 4 && input.size() <= 16 &&
        std::all_of(input.begin(), input.end(), [](char c) { return c >= 'a' && c <= 'z'; });
    struct AdmissionMemo {
      const std::string& input;
      std::optional<std::array<std::pair<rime::an<rime::Candidate>, bool>, 32>> candidates;
      size_t count = 0;
    } admission{input};
    if (recovery_input) admission.candidates.emplace();
    auto admissible = [&](const rime::an<rime::Candidate>& candidate) {
      for (size_t i = 0; i < admission.count; ++i)
        if ((*admission.candidates)[i].first == candidate) return (*admission.candidates)[i].second;
      const bool keep = spelling_policy_.Keeps(dict(), fuzzy_groups_, t9_, candidate, admission.input);
      if (admission.candidates && admission.count < admission.candidates->size())
        (*admission.candidates)[admission.count++] = {candidate, keep};
      return keep;
    };
    auto query_exact_word = [&](const std::string& corrected, const rime::Code& code) -> rime::an<rime::Translation> {
      if (!remaining_probes) return nullptr;
      --remaining_probes;
      return ScriptTranslator::QueryExactWords(corrected, code, segment);
    };
    auto enabled_spelling = [&](const rime::an<rime::Candidate>& candidate) {
      return spelling_policy_.UsesEnabledSpelling(dict(), fuzzy_groups_, t9_, candidate, input);
    };
    auto recover = [&](rime::an<rime::Translation> source) {
      policy_enabled_ = apply_policy;
      return RecoverWholeWord(input, segment, dict(), source, admissible, query_word);
    };
    // Resolve repeated-letter edits while the original word stream is intact.
    // Other inputs retain the cheaper established sentence/word search order.
    const bool recover_first = recovery &&
        std::adjacent_find(input.begin(), input.end()) != input.end();
    if (recover_first) result = recover(result);
    if (apply_policy && fuzzy_groups_ != 15) {
      // Preserve the established order whenever the first page already contains
      // a complete admissible result. Removing unrelated edges can otherwise
      // change a correct sentence's search path and score.
      auto scanned = rime::New<rime::FifoTranslation>();
      bool complete = false;
      bool checked_first = false;
      for (int i = 0; i < 30 && result && !result->exhausted(); ++i) {
        auto candidate = result->Peek();
        auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
        if (candidate && admissible(candidate)) {
          const bool existing_word = !checked_first && phrase && !rime::As<rime::Sentence>(phrase) &&
              phrase->code().size() > 1 && std::adjacent_find(input.begin(), input.end()) == input.end();
          checked_first = true;
          if (candidate->end() == segment.end || existing_word) {
            complete = true; break;
          }
        }
        if (candidate) scanned->Append(candidate);
        result->Next();
      }
      if (!scanned->exhausted()) {
        auto restored = rime::New<rime::UnionTranslation>();
        *restored += scanned; *restored += result; result = restored;
      }
      if (!complete) {
        policy_enabled_ = true;
        auto alternative = ScriptTranslator::Query(input, segment);
        if (alternative && !alternative->exhausted() && alternative->Peek()->end() == segment.end &&
            !alternative->Peek()->text().empty() &&
            std::none_of(alternative->Peek()->text().begin(), alternative->Peek()->text().end(),
                         [](unsigned char c) { return c < 0x80; }) &&
            admissible(alternative->Peek())) {
          auto combined = rime::New<rime::UnionTranslation>();
          *combined += alternative; *combined += result; result = combined;
        }
      }
    }
    policy_enabled_ = apply_policy;
    if (recovery && !recover_first) result = recover(result);
    if (recovery) result = missing_letters_.Recover(input, segment, dict(), syllabary_, result,
                                                    remaining_probes, admissible, enabled_spelling, query_exact_word);
    if (recovery_input && remaining_probes) result = transpositions_.Recover(input, segment, dict(), syllabary_, result,
                                                    remaining_probes, admissible, enabled_spelling, query_exact_word);
    if (recovery_input && remaining_probes) result = neighbors_.Recover(input, segment, dict(), syllabary_, result,
                                                    remaining_probes, admissible, enabled_spelling, query_exact_word);
    // 繁体流（m1 的主来源）也要接：否则九键漏键在繁体模式只有简体回退，还要被降 8.0。
    // 它和简体流共用同一个键形模型，只是音节表来自 native_hant（含 87 条单字母读音）。
    if (t9_ && correction_option) {
      auto make_recovered_word = [&](const rime::Code& code,
                                     const rime::an<rime::DictEntry>& entry,
                                     double quality) -> rime::an<rime::Candidate> {
        auto phrase = rime::New<rime::Phrase>(language(), "phrase", segment.start,
                                              segment.end, entry);
        phrase->set_comment(Spell(code));
        phrase->set_quality(quality);
        return phrase;
      };
      result = t9_keys_.RecoverMissing(input, segment, dict(), syllabary_, result,
                                      remaining_probes, initial_quality(), make_recovered_word);
      // 重复按键（T02）：连按同一键，键串里多出一个相邻重复键。与漏键共用同一套
      // 切分/打分/合并，只是编辑方向相反。
      result = t9_keys_.RecoverRepeated(input, segment, dict(), syllabary_, result,
                                        remaining_probes, initial_quality(), make_recovered_word);
    }
    // Preserve the genuine candidate for selection and dictionary-specific learning.
    if (!traditional_dictionary && mode == "1" && result)
      return rime::New<OriginalTranslation>(result);
    return result;
  }
 private:
  QingyuSpellingPolicy spelling_policy_;
  CanonicalSyllabary syllabary_;
  MissingLetterRecovery missing_letters_;
  AdjacentTranspositionRecovery transpositions_;
  NeighborKeyRecovery neighbors_;
  T9KeyRecovery t9_keys_;
  bool policy_enabled_ = false;
  bool t9_ = false;
  int fuzzy_groups_ = 0;
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

// Correction provenance follows emoji/shadow text changes; original-form labels do not.
bool Corrected(const rime::an<rime::Candidate>& candidate) {
  if (!candidate) return false;
  if (candidate->type() == "qingyu_spelling_correction") return true;
  if (auto combined = rime::As<rime::UniquifiedCandidate>(candidate)) {
    const auto& items = combined->items();
    return !items.empty() && std::any_of(items.begin(), items.end(), Corrected);
  }
  if (auto shadow = rime::As<rime::ShadowCandidate>(candidate)) return Corrected(shadow->item());
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

bool IsSpellingCorrectionCandidate(RimeSessionId session_id, size_t index) {
  auto session = rime::Service::instance().GetSession(session_id);
  if (!session || !session->context()->HasMenu()) return false;
  auto menu = session->context()->composition().back().menu;
  return menu && Corrected(menu->GetCandidateAt(index));
}
