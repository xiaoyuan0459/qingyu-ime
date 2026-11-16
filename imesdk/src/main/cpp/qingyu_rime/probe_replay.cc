// 逐动作回放（评测运行器的引擎侧入口，见 probe.cc 头部的分工说明）
//
// 为什么独立成文件：probe.cc 的四种子命令都是「读已部署二进制、答一个问题」，
// 而回放要起真引擎跑完一整段动作流，职责与体量都值得单独一份。
//
// 计时边界（审查方案 P0-A 第 2 条的硬性要求）：
//   * 只把 process_key 与 get_context 计入逐动作耗时；
//   * 进程启动、会话创建、整串设置码、候选打印都不算；
//   * 输出 JSONL，一行一个事实，运行器逐行聚合。
//
// 会话与学习：每个 case 独立会话；只输入不提交时 librime 不写用户词库，
// 案例间互不污染。需要学习态的流程由运行器另开隔离 user 目录复现。

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <rime_api.h>
#include "native_script.h"
#include "probe_replay.h"
#include "probe_observation.h"

namespace {

// ------------------------------------------------------------------ 逐动作回放
//
// 评测运行器（tools/eval-ime-candidates.py）需要的不是「一个码的最终候选」，
// 而是「每个动作之后引擎给了什么、花了多久」：
//   * 计时只包围 process_key 与 get_context —— 进程启动、会话创建、整串设置码
//     和候选打印都**不算**逐键耗时（审查方案 P0-A 第 2 条的硬性要求）；
//   * 输出 JSONL，一行一个事实，运行器逐行聚合，不解析人类可读格式；
//   * 每个案例（case）独立会话：只输入不提交时 librime 不会写用户词库，
//     案例间互不污染；需要学习态的流程由运行器另开隔离 user 目录复现。
//
// 动作文件格式（UTF-8，# 开头为注释，字段用 \t 分隔）：
//   case <id>            新案例：销毁旧会话、建新会话并切换方案
//   key <字符串>          逐字节 process_key（每字节一个计时动作）
//   backspace [n]        连续 n 次退格（默认 1）
//   select <index>       按 menu 全局序号选候选
//   selectpage <index>   按当前页序号选候选
//   page <next|prev>     翻页（测候选池覆盖用）
//   option <name> <0|1>  设置选项（模糊音/纠错开关等），不计时
//   snapshot             只取上下文，不打键
//   expect <文本>         把期望随动作流写入输出，便于单文件复核（引擎不校验）

constexpr int kBackspaceKeycode = 0xFF08;  // XK_BackSpace
constexpr size_t kMaxEmittedCandidates = 50;

using SteadyClock = std::chrono::steady_clock;

double MicrosSince(const SteadyClock::time_point& start) {
  return std::chrono::duration<double, std::micro>(
             SteadyClock::now() - start)
      .count();
}

// ctx_us 只测 get_context；遍历候选、序列化和释放内存均在计时之外。
void AppendContextJson(std::string* out, RimeApi* rime, RimeSessionId session,
                       double* ctx_us) {
  RIME_STRUCT(RimeContext, ctx);
  const auto start = SteadyClock::now();
  const bool has_context = rime->get_context(session, &ctx) != False;
  *ctx_us = MicrosSince(start);
  out->append(",\"preedit\":");
  JsonAppendString(
      out, has_context && ctx.composition.preedit ? ctx.composition.preedit : "");
  out->append(",\"candidates\":[");
  if (has_context) {
    const size_t count = static_cast<size_t>(ctx.menu.num_candidates);
    bool first = true;
    for (size_t i = 0; i < count && i < kMaxEmittedCandidates; ++i) {
      if (!first)
        out->push_back(',');
      first = false;
      out->append("{\"text\":");
      JsonAppendString(out, ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : "");
      out->append(",\"comment\":");
      JsonAppendString(out, ctx.menu.candidates[i].comment ? ctx.menu.candidates[i].comment : "");
      out->push_back('}');
    }
    out->append("],\"page_size\":");
    out->append(std::to_string(ctx.menu.page_size));
    out->append(",\"page_no\":");
    out->append(std::to_string(ctx.menu.page_no));
    out->append(",\"is_last_page\":");
    out->append(ctx.menu.is_last_page ? "true" : "false");
  } else {
    out->append("]");
  }
  rime->free_context(&ctx);
  out->push_back('}');
}

// 打一个键并立即取上下文；两个耗时分别记录。
void EmitKeyAction(int action_no, const char* action, const std::string& arg,
                   int keycode, RimeApi* rime, RimeSessionId session) {
  std::string ctx;
  ctx.reserve(1024);
  const auto t0 = SteadyClock::now();
  const Bool handled = rime->process_key(session, keycode, 0);
  const double key_us = MicrosSince(t0);
  double ctx_us = 0.0;
  AppendContextJson(&ctx, rime, session, &ctx_us);

  std::string head = "{\"type\":\"action\",\"n\":" + std::to_string(action_no) +
                     ",\"handled\":" + (handled ? "true" : "false") +
                     ",\"key_us\":" + std::to_string(key_us) +
                     ",\"ctx_us\":" + std::to_string(ctx_us) +
                     ",\"action\":\"" + action + "\",\"arg\":";
  JsonAppendString(&head, arg.c_str());
  // ctx 以逗号开头接在 arg 值之后，最后闭合对象。
  std::printf("%s%s\n", head.c_str(), ctx.c_str());
  std::fflush(stdout);
}

// select/page 与按键同构：先做动作（计时），再取上下文（计时），拼一行 JSON。
void EmitSelectAction(int action_no, const char* action, const std::string& arg,
                      const Bool handled, const double key_us,
                      RimeApi* rime, RimeSessionId session) {
  std::string ctx;
  ctx.reserve(1024);
  double ctx_us = 0.0;
  AppendContextJson(&ctx, rime, session, &ctx_us);

  std::string head = "{\"type\":\"action\",\"n\":" + std::to_string(action_no) +
                     ",\"handled\":" + (handled ? "true" : "false") +
                     ",\"key_us\":" + std::to_string(key_us) +
                     ",\"ctx_us\":" + std::to_string(ctx_us) +
                     ",\"action\":\"" + action + "\",\"arg\":";
  JsonAppendString(&head, arg.c_str());
  std::printf("%s%s\n", head.c_str(), ctx.c_str());
  std::fflush(stdout);
}

}  // namespace

bool ReplayActions(const char* shared_dir, const char* user_dir,
                   const char* schema, FILE* input) {
  const auto engine_start = SteadyClock::now();
  RimeApi* rime = rime_get_api();
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared_dir;
  traits.user_data_dir = user_dir;
  traits.app_name = "qingyu_probe";
  rime->setup(&traits);
  rime->initialize(&traits);
  RegisterNativeScriptTranslator();
  const double engine_setup_us = MicrosSince(engine_start);

  RimeSessionId session = 0;
  std::string meta = "{\"type\":\"meta\",\"protocol_version\":3,"
                     "\"timing_version\":2,\"build_type\":\""
                     QINGYU_PROBE_BUILD_TYPE "\",\"engine_source_sha256\":\""
                     QINGYU_PROBE_SOURCE_SHA "\",\"schema\":";
  JsonAppendString(&meta, schema);
  meta.append(",\"engine_setup_us\":" + std::to_string(engine_setup_us));
  meta.append(",\"shared_dir\":");
  JsonAppendString(&meta, shared_dir);
  meta.append(",\"user_dir\":");
  JsonAppendString(&meta, user_dir);
  meta.push_back('}');
  std::printf("%s\n", meta.c_str());

  char buffer[4096];
  int action_no = 0;
  bool failed = false;
  while (!failed && std::fgets(buffer, sizeof buffer, input)) {
    std::string line(buffer);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r'))
      line.pop_back();
    if (line.empty() || line[0] == '#')
      continue;
    std::vector<std::string> fields;
    size_t start = 0;
    while (true) {
      const size_t tab = line.find('\t', start);
      if (tab == std::string::npos) {
        fields.push_back(line.substr(start));
        break;
      }
      fields.push_back(line.substr(start, tab - start));
      start = tab + 1;
    }
    const std::string& cmd = fields[0];

    if (cmd == "case" && fields.size() == 2) {
      const auto session_start = SteadyClock::now();
      if (session)
        rime->destroy_session(session);
      session = rime->create_session();
      if (!session ||
          !rime->select_schema(session, schema)) {
        std::fprintf(stderr, "创建会话或切换方案失败: %s\n", schema);
        failed = true;
        break;
      }
      action_no = 0;
      std::string out = "{\"type\":\"case\",\"id\":";
      JsonAppendString(&out, fields[1].c_str());
      out.append(",\"session_setup_us\":" + std::to_string(MicrosSince(session_start)));
      out.push_back('}');
      std::printf("%s\n", out.c_str());
      std::fflush(stdout);
      continue;
    }

    if (!session) {
      std::fprintf(stderr, "动作在第一个 case 之前: %s\n", cmd.c_str());
      failed = true;
      break;
    }

    if (cmd == "mode" && fields.size() == 2) {
      const bool ok = fields[1].size() == 1 && fields[1][0] >= '0' && fields[1][0] <= '2' &&
                      SetNativeScriptMode(session, fields[1][0] - '0');
      if (!ok) failed = true;
    } else if (cmd == "scan" && fields.size() == 3) {
      const int limit = std::atoi(fields[2].c_str());
      if (limit < 1 || limit > 2000000) { failed = true; break; }
      EmitCandidatePool(rime, session, ++action_no, fields[1], limit);
    } else if (cmd == "key" && fields.size() == 2 && !fields[1].empty()) {
      for (const char c : fields[1]) {
        EmitKeyAction(++action_no, "key", std::string(1, c),
                      static_cast<unsigned char>(c), rime, session);
      }
    } else if (cmd == "backspace" &&
               (fields.size() == 1 || fields.size() == 2)) {
      const int count = fields.size() == 2 ? std::atoi(fields[1].c_str()) : 1;
      for (int i = 0; i < count && i < 1024; ++i)
        EmitKeyAction(++action_no, "backspace", fields.size() == 2 ? fields[1] : "1",
                      kBackspaceKeycode, rime, session);
    } else if ((cmd == "select" || cmd == "selectpage") &&
               fields.size() == 2) {
      const size_t index = static_cast<size_t>(std::strtoull(fields[1].c_str(), nullptr, 10));
      const auto t0 = SteadyClock::now();
      const Bool handled = cmd == "select"
                               ? rime->select_candidate(session, index)
                               : rime->select_candidate_on_current_page(session, index);
      EmitSelectAction(++action_no, cmd.c_str(), fields[1], handled,
                       MicrosSince(t0), rime, session);
    } else if (cmd == "page" && fields.size() == 2 &&
               (fields[1] == "next" || fields[1] == "prev")) {
      const auto t0 = SteadyClock::now();
      const Bool handled =
          rime->change_page(session, fields[1] == "prev" ? True : False);
      EmitSelectAction(++action_no, "page", fields[1], handled,
                       MicrosSince(t0), rime, session);
    } else if (cmd == "option" && fields.size() == 3) {
      rime->set_option(session, fields[1].c_str(),
                       fields[2] == "1" ? True : False);
      std::string out = "{\"type\":\"option\",\"name\":";
      JsonAppendString(&out, fields[1].c_str());
      out.append(",\"value\":");
      out.append(fields[2] == "1" ? "true" : "false");
      out.push_back('}');
      std::printf("%s\n", out.c_str());
      std::fflush(stdout);
    } else if (cmd == "snapshot" && fields.size() == 1) {
      EmitSelectAction(++action_no, "snapshot", "", True, 0.0, rime, session);
    } else if (cmd == "expect" && fields.size() == 2) {
      std::string out = "{\"type\":\"expect\",\"text\":";
      JsonAppendString(&out, fields[1].c_str());
      out.push_back('}');
      std::printf("%s\n", out.c_str());
      std::fflush(stdout);
    } else {
      std::fprintf(stderr, "无法识别的动作: %s\n", line.c_str());
      failed = true;
    }
  }

  if (session)
    rime->destroy_session(session);
  rime->finalize();
  return !failed;
}
