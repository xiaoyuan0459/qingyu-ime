// P0 的隔离状态旅程。与候选计时探针分开，学习和重启不污染无学习基线。
#include "native_script.h"
#include "probe_observation.h"
#include <algorithm>
#include <cstdio>
#include <stdexcept>
#include <string>
#include <vector>
#include <rime_api.h>

namespace {

void Require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

class Session {
 public:
  Session(const char* shared, const char* user) : api_(rime_get_api()) {
    RIME_STRUCT_INIT(RimeTraits, traits_);
    traits_.shared_data_dir = shared;
    traits_.user_data_dir = user;
    traits_.app_name = "qingyu_p0_workflows";
    api_->setup(&traits_);
    Start();
  }
  ~Session() { Stop(); }
  void Start() {
    api_->initialize(&traits_);
    running_ = true;
    RegisterNativeScriptTranslator();
    id_ = api_->create_session();
    Schema("pinyin");
  }
  void Stop() {
    if (!running_) return;
    if (id_) api_->destroy_session(id_);
    id_ = 0;
    api_->finalize();
    running_ = false;
  }
  void Restart() { Stop(); Start(); }
  void Schema(const char* schema) {
    Require(api_->select_schema(id_, schema), "schema switch failed");
    Require(SetNativeScriptMode(id_, mode_), "restore script mode failed");
  }
  void Mode(int mode) {
    Require(SetNativeScriptMode(id_, mode), "script mode failed");
    mode_ = mode;
  }
  void Type(const char* keys) {
    api_->clear_composition(id_);
    Drain();
    for (const char* key = keys; *key; ++key)
      Require(api_->process_key(id_, static_cast<unsigned char>(*key), 0), "key rejected");
  }
  void Key(int keycode) { Require(api_->process_key(id_, keycode, 0), "edit key rejected"); }
  std::string Drain() {
    RIME_STRUCT(RimeCommit, commit);
    std::string text;
    if (api_->get_commit(id_, &commit)) {
      if (commit.text) text = commit.text;
      api_->free_commit(&commit);
    }
    return text;
  }
  std::string Preedit() {
    RIME_STRUCT(RimeContext, context);
    std::string result;
    if (api_->get_context(id_, &context) && context.composition.preedit)
      result = context.composition.preedit;
    api_->free_context(&context);
    return result;
  }
  std::vector<std::string> First(int limit) {
    RimeCandidateListIterator iterator = {};
    std::vector<std::string> result;
    if (api_->candidate_list_begin(id_, &iterator)) {
      while (static_cast<int>(result.size()) < limit && api_->candidate_list_next(&iterator))
        result.emplace_back(iterator.candidate.text ? iterator.candidate.text : "");
    }
    api_->candidate_list_end(&iterator);
    return result;
  }
  int Find(const char* text, bool native_only = false, bool relative = false) {
    RimeCandidateListIterator iterator = {};
    int found = -1, native_rank = 0;
    if (api_->candidate_list_begin(id_, &iterator)) {
      while (api_->candidate_list_next(&iterator) && iterator.index < 50000) {
        const bool eligible = !native_only || !IsOriginalCandidate(id_, iterator.index);
        if (eligible) ++native_rank;
        if (eligible && iterator.candidate.text && std::string(iterator.candidate.text) == text) {
          found = relative ? native_rank : iterator.index;
          break;
        }
      }
    }
    api_->candidate_list_end(&iterator);
    return found;
  }
  std::string Select(const char* text) {
    const int index = Find(text);
    Require(index >= 0, "fixed selection target absent");
    Require(api_->select_candidate(id_, index), "select engineIndex failed");
    return Drain();
  }
  int PageSize() {
    RIME_STRUCT(RimeContext, context);
    Require(api_->get_context(id_, &context), "context absent");
    const int size = context.menu.page_size;
    api_->free_context(&context);
    return size;
  }
  std::string SelectOnPage(int global_index) {
    const int size = PageSize();
    Require(size > 0, "page size invalid");
    for (int page = 0; page < global_index / size; ++page)
      Require(api_->change_page(id_, False), "next page failed");
    Require(api_->select_candidate_on_current_page(id_, global_index % size), "page-relative selection failed");
    return Drain();
  }
 private:
  RimeApi* api_;
  RimeTraits traits_ = {};
  RimeSessionId id_ = 0;
  int mode_ = 0;
  bool running_ = false;
};

void Emit(const char* name, const std::string& details = "") {
  std::string out = "{\"passed\":true,\"workflow\":";
  JsonAppendString(&out, name);
  if (!details.empty()) out.append("," + details);
  out.push_back('}');
  std::puts(out.c_str());
  std::fflush(stdout);
}

void Learning(Session* session) {
  session->Mode(0); session->Type("shishi");
  const int primary_before = session->Find("史诗");
  Require(primary_before > 0, "learning fixture must start below first place");
  session->Mode(2); session->Type("shishi");
  const int original_before = session->Find("史诗");
  session->Mode(1); session->Type("shishi");
  const int hant_before = session->Find("史詩", true, true);
  Require(hant_before > 1, "native learning fixture must start below first place");
  session->Mode(0);
  for (int i = 0; i < 8; ++i) {
    session->Type("shishi");
    Require(session->Select("史诗") == "史诗", "primary learning commit changed text");
  }
  session->Type("shishi");
  const int primary_after = session->Find("史诗");
  Require(primary_after >= 0 && primary_after < primary_before, "primary learning did not improve rank");
  session->Mode(2); session->Type("shishi");
  const int original_after = session->Find("史诗");
  Require(original_after >= 0 && original_after < original_before, "original mode did not share source-word learning");
  session->Restart(); session->Mode(2); session->Type("shishi");
  Require(session->Find("史诗") == original_after, "original mode learning did not survive restart");
  session->Mode(1); session->Type("shishi");
  Require(session->Find("史詩", true, true) == hant_before, "primary learning leaked into native_hant namespace");
  for (int i = 0; i < 8; ++i) {
    session->Type("shishi");
    Require(session->Select("史詩") == "史詩", "native learning commit changed text");
  }
  session->Type("shishi");
  const int hant_after = session->Find("史詩", true, true);
  Require(hant_after > 0 && hant_after < hant_before, "native_hant learning did not improve rank");
  session->Restart(); session->Mode(1); session->Type("shishi");
  Require(session->Find("史詩", true, true) == hant_after, "native_hant learning did not survive restart");
  session->Mode(0); session->Type("shishi");
  Require(session->Find("史诗") == primary_after, "source-word learning did not survive restart");
  Emit("synthetic_learning_mode_isolation_restart",
       "\"primary_before\":" + std::to_string(primary_before + 1) + ",\"primary_after\":" + std::to_string(primary_after + 1) +
       ",\"native_before\":" + std::to_string(hant_before) + ",\"native_after\":" + std::to_string(hant_after));
}

void Selection(Session* session) {
  session->Mode(0); session->Type("beijingdaxue");
  Require(session->Select("北京").empty(), "partial confirmation committed too early");
  Require(!session->Preedit().empty(), "partial confirmation lost composition");
  Require(session->Select("大学") == "北京大学", "partial confirmation assembled wrong commit");
  Emit("partial_confirmation_engine_index");
  session->Type("nihao"); const auto fresh = session->First(20);
  session->Type("nihaox"); session->Key(0xFF08);
  Require(session->First(20) == fresh, "incremental backspace differs from fresh query");
  Require(session->Select("你好") == "你好", "edited input committed wrong text");
  Emit("backspace_equals_fresh_query_and_commit");
  session->Type("shi");
  const int target = session->Find("贳");
  Require(target >= session->PageSize(), "page fixture must require paging");
  Require(session->SelectOnPage(target) == "贳", "page-relative index committed wrong text");
  Emit("paging_preserves_global_engine_index", "\"target_index\":" + std::to_string(target));
}

void ModeAndSchema(Session* session) {
  session->Schema("pinyin"); session->Mode(0); session->Type("women");
  const auto input = session->Preedit();
  session->Mode(1);
  Require(session->Preedit() == input, "mode change lost composition");
  Require(session->Find("我們", true) >= 0, "native traditional form absent");
  session->Mode(2);
  Require(session->Preedit() == input, "original mode lost composition");
  Require(session->Find("我们") >= 0 && session->Find("我們") >= 0, "original mode lost native alternatives");
  session->Schema("t9_pinyin"); session->Mode(0); session->Type("MGGAM");
  Require(session->Select("你好") == "你好", "numeric keyboard mapping or commit wrong");
  session->Schema("pinyin"); session->Type("nihao");
  Require(session->Select("你好") == "你好", "schema return committed stale content");
  Emit("script_modes_schema_switch_and_composition");
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 3 || std::string(argv[2]).find("/data/local/tmp/qingyu-") != 0) {
    std::fprintf(stderr, "usage: qingyu_workflow_probe <shared> </data/local/tmp/qingyu-... isolated user>\n");
    return 2;
  }
  try {
    Session session(argv[1], argv[2]);
    Learning(&session);
    Selection(&session);
    ModeAndSchema(&session);
  } catch (const std::exception& error) {
    std::fprintf(stderr, "P0 workflow failed: %s\n", error.what());
    return 1;
  }
  return 0;
}
