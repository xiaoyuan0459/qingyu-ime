// 码空间探针（命令行工具，不打进 APK）
//
// 为什么需要它：继承来的 11 个 schema 是从旧闭源库的部署产物里抠出来的，
// `speller/algebra` 被剥掉了（见 MIGRATION.md），仓库里也没有双拼/九宫格码表
// （`DoublePinYinUtils.kt` 只有 4 条显示用的映射）。于是「重编 prism 之后码空间
// 是否还是原来那个」无从判断 —— 少一条 derive 规则的表现不是崩溃，而是
// 用户突然发现某个双拼码打不出字，属于静默回归。
//
// 本工具直接读已部署的 `*.prism.bin` / `*.table.bin`，把「哪些输入码被接受、
// 解析成哪些音节、以什么拼写类型」打印成 TSV，用来：
//   1. 在替换数据**前**导出旧棱镜的码空间基线；
//   2. 重编后逐码差分，要求零差异（差异必须能逐条定性）；
//   3. 从双拼的 676 个码 → 音节映射反推出我们自己写的 algebra。
//
// 顺带输出 prism 与 table 各自的 `dict_file_checksum`：两者不一致就说明
// 「换了 table 没换 prism」——M2 踩过的九宫格错位正是这个状态，以前只能靠
// 人记住，现在可以机器判定。
//
//   qingyu_probe --syllabary <table.bin>
//   qingyu_probe --codes <prism.bin> <table.bin> [<codes.txt | ->]
//   qingyu_probe --entries <table.bin> <音节>
//   qingyu_probe --words <table.bin> <音节> [<limit>]

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <rime/algo/spelling.h>
#include <rime/dict/prism.h>
#include <rime/dict/table.h>
#include <rime_api.h>
#include "native_script.h"

namespace {

const char* SpellingTypeName(rime::SpellingType type) {
  switch (type) {
    case rime::kNormalSpelling:
      return "normal";
    case rime::kFuzzySpelling:
      return "fuzzy";
    case rime::kAbbreviation:
      return "abbrev";
    case rime::kCompletion:
      return "completion";
    case rime::kAmbiguousSpelling:
      return "ambiguous";
    case rime::kInvalidSpelling:
      return "invalid";
  }
  return "unknown";
}

bool DumpSyllabary(const char* table_path) {
  rime::Table table{rime::path(table_path)};
  if (!table.Load()) {
    std::fprintf(stderr, "装载 table 失败: %s\n", table_path);
    return false;
  }
  const uint32_t count = table.metadata()->num_syllables;
  std::printf("#table_dict_checksum\t%u\n", table.dict_file_checksum());
  std::printf("#num_syllables\t%u\n", count);
  for (uint32_t i = 0; i < count; ++i)
    std::printf("%u\t%s\n", i, table.GetSyllableById((int)i).c_str());
  return true;
}

// 逐码探测：每行一个码，输出该码在棱镜里的全部解释。
bool ProbeCodes(const char* prism_path, const char* table_path, FILE* input) {
  rime::Prism prism{rime::path(prism_path)};
  if (!prism.Load()) {
    std::fprintf(stderr, "装载 prism 失败: %s\n", prism_path);
    return false;
  }
  rime::Table table{rime::path(table_path)};
  if (!table.Load()) {
    std::fprintf(stderr, "装载 table 失败: %s\n", table_path);
    return false;
  }

  std::printf("#prism_file\t%s\n", prism_path);
  std::printf("#prism_dict_checksum\t%u\n", prism.dict_file_checksum());
  std::printf("#table_dict_checksum\t%u\n", table.dict_file_checksum());
  std::printf("#prism_array_size\t%zu\n", prism.array_size());
  // 棱镜是按某份词库的音节表建的；音节表变了（重编词库）就必须重建棱镜，
  // 否则音节 id 指向的是另一份表 —— 表现为「能出候选但字序错位」。
  std::printf("#checksum_pair_%s\t%s\n",
      prism.dict_file_checksum() == table.dict_file_checksum() ? "match" : "STALE",
      prism_path);

  char buffer[512];
  while (std::fgets(buffer, sizeof buffer, input)) {
    std::string line(buffer);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r'))
      line.pop_back();
    if (line.empty() || line[0] == '#')
      continue;

    std::vector<rime::Prism::Match> matches;
    prism.CommonPrefixSearch(line, &matches);

    bool hit = false;
    std::string explanations;
    for (const auto& match : matches) {
      if (match.length != line.size())
        continue;  // 只要「整码命中」，前缀命中是补全/切分用的，不是本码的解释
      hit = true;
      auto accessor = prism.QuerySpelling(match.value);
      while (!accessor.exhausted()) {
        const auto syllable_id = accessor.syllable_id();
        const auto props = accessor.properties();
        if (!explanations.empty())
          explanations += "|";
        explanations += table.GetSyllableById((int)syllable_id).c_str();
        explanations += ":";
        explanations += SpellingTypeName(props.type);
        explanations += ":";
        explanations += std::to_string(props.credibility);
        accessor.Next();
      }
    }
    std::printf("%s\t%s\t%s\n", line.c_str(), hit ? "hit" : "miss", explanations.c_str());
  }
  return true;
}

// 按音节统计词库里的词条数 —— 「候选深度塌陷」（如 `shi` 只剩 1 页）的直接测量，
// 不用起会话、也不受分页参数影响。
bool CountEntriesBySyllable(const char* table_path, const char* syllable) {
  rime::Table table{rime::path(table_path)};
  if (!table.Load()) {
    std::fprintf(stderr, "装载 table 失败: %s\n", table_path);
    return false;
  }
  const uint32_t count = table.metadata()->num_syllables;
  for (uint32_t i = 0; i < count; ++i) {
    if (table.GetSyllableById((int)i) != syllable)
      continue;
    auto accessor = table.QueryWords((int)i);
    std::printf("%s\t%zu\n", syllable, accessor.remaining());
    return true;
  }
  std::fprintf(stderr, "词库里没有音节: %s\n", syllable);
  return false;
}

// 按词库**存储序**打印某个音节的词条 —— 候选面板的顺序就是这个序（同音块内部
// 不再重排），所以「权重 1 的生僻字为什么排在常用字前面」只能这样复现。
bool DumpWords(const char* table_path, const char* syllable, size_t limit) {
  rime::Table table{rime::path(table_path)};
  if (!table.Load()) {
    std::fprintf(stderr, "装载 table 失败: %s\n", table_path);
    return false;
  }
  const uint32_t count = table.metadata()->num_syllables;
  for (uint32_t i = 0; i < count; ++i) {
    if (table.GetSyllableById((int)i) != syllable)
      continue;
    auto accessor = table.QueryWords((int)i);
    size_t n = 0;
    while (!accessor.exhausted() && n < limit) {
      const auto* e = accessor.entry();
      std::printf("%zu\t%s\t%.1f\n", n,
                  table.GetEntryText(*e).c_str(), e->weight);
      ++n;
      if (!accessor.Next())
        break;
    }
    return true;
  }
  std::fprintf(stderr, "词库里没有音节: %s\n", syllable);
  return false;
}

// 起一次真引擎，把一个输入码的候选连**注释（真实读音）**一起打出来。
// prism/table 差分只能回答「码空间认不认」，回答不了「引擎按什么顺序给了什么」——
// 模糊音的降权、注释能不能用来归因，只有这里看得见全貌。
bool QueryCandidates(const char* shared_dir, const char* user_dir,
                     const char* schema, const char* code, size_t limit) {
  RimeApi* rime = rime_get_api();
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared_dir;
  traits.user_data_dir = user_dir;
  traits.app_name = "qingyu_probe";
  rime->setup(&traits);
  rime->initialize(&traits);
  RegisterNativeScriptTranslator();
  RimeSessionId session = rime->create_session();
  if (!session) {
    std::fprintf(stderr, "创建会话失败（检查 %s/build 是否齐全）\n", shared_dir);
    rime->finalize();
    return false;
  }
  if (!rime->select_schema(session, schema))
    std::fprintf(stderr, "切换方案失败: %s\n", schema);
  for (const char* p = code; *p; ++p)
    rime->process_key(session, static_cast<unsigned char>(*p), 0);

  RIME_STRUCT(RimeContext, ctx);
  const bool has_context = rime->get_context(session, &ctx) != False;
  const char* preedit = has_context && ctx.composition.preedit ? ctx.composition.preedit : "";
  std::printf("#schema\t%s\t#code\t%s\t#preedit\t%s\n", schema, code, preedit);
  if (has_context) {
    const size_t count = static_cast<size_t>(ctx.menu.num_candidates);
    for (size_t i = 0; i < count && i < limit; ++i) {
      std::printf("%zu\t%s\t%s\n", i,
                  ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : "",
                  ctx.menu.candidates[i].comment ? ctx.menu.candidates[i].comment : "");
    }
    std::printf("#page_size\t%d\t#page_no\t%d\t#last_page\t%d\n",
                ctx.menu.page_size, ctx.menu.page_no, ctx.menu.is_last_page);
  }
  rime->free_context(&ctx);
  rime->destroy_session(session);
  rime->finalize();
  return has_context;
}

void Usage(const char* argv0) {
  std::fprintf(stderr,
      "用法:\n"
      "  %s --syllabary <table.bin>\n"
      "  %s --codes <prism.bin> <table.bin> [<codes.txt | -]\n"
      "  %s --entries <table.bin> <音节>\n"
      "  %s --words <table.bin> <音节> [<limit>]\n"
      "  %s --query <shared目录> <user目录> <方案> <码> [<limit>]\n",
      argv0, argv0, argv0, argv0, argv0);
}

}  // namespace

int main(int argc, char* argv[]) {
  if (argc < 2) {
    Usage(argv[0]);
    return 2;
  }
  const std::string mode = argv[1];
  if (mode == "--syllabary" && argc == 3)
    return DumpSyllabary(argv[2]) ? 0 : 1;
  if (mode == "--entries" && argc == 4)
    return CountEntriesBySyllable(argv[2], argv[3]) ? 0 : 1;
  if (mode == "--words" && (argc == 4 || argc == 5)) {
    const size_t limit =
        argc == 5 ? (size_t)strtoull(argv[4], nullptr, 10) : 40;
    return DumpWords(argv[2], argv[3], limit) ? 0 : 1;
  }
  if (mode == "--query" && (argc == 6 || argc == 7)) {
    const size_t limit =
        argc == 7 ? (size_t)strtoull(argv[6], nullptr, 10) : 30;
    return QueryCandidates(argv[2], argv[3], argv[4], argv[5], limit) ? 0 : 1;
  }
  if (mode == "--codes") {
    if (argc != 4 && argc != 5) {
      Usage(argv[0]);
      return 2;
    }
    FILE* input = stdin;
    if (argc == 5) {
      input = std::fopen(argv[4], "r");
      if (!input) {
        std::fprintf(stderr, "打不开码表文件: %s\n", argv[4]);
        return 1;
      }
    }
    const bool ok = ProbeCodes(argv[2], argv[3], input);
    if (argc == 5)
      std::fclose(input);
    return ok ? 0 : 1;
  }
  Usage(argv[0]);
  return 2;
}
