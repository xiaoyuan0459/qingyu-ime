// 评测的候选池观测与已编译字典身份；不改变候选生成或排序。
#include "probe_observation.h"
#include "native_script.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <set>
#include <utility>
#include <vector>
#include <rime/dict/table.h>
#include <rime/context.h>
#include <rime/menu.h>
#include <rime/gear/translator_commons.h>
#include <rime/service.h>

void JsonAppendString(std::string* out, const char* text) {
  out->push_back('"');
  for (const auto* p = reinterpret_cast<const unsigned char*>(text); *p; ++p) {
    switch (*p) {
      case '"': out->append("\\\""); break;
      case '\\': out->append("\\\\"); break;
      case '\n': out->append("\\n"); break;
      case '\r': out->append("\\r"); break;
      case '\t': out->append("\\t"); break;
      default:
        if (*p < 0x20) {
          char buffer[8];
          std::snprintf(buffer, sizeof buffer, "\\u%04x", *p);
          out->append(buffer);
        } else {
          out->push_back(static_cast<char>(*p));
        }
    }
  }
  out->push_back('"');
}

void EmitCandidatePool(RimeApi* api, RimeSessionId session, int action_no,
                       const std::string& targets, int limit) {
  std::set<std::string> wanted, found;
  for (size_t start = 0; start < targets.size();) {
    const auto end = targets.find('|', start);
    wanted.insert(targets.substr(start, end - start));
    if (end == std::string::npos) break;
    start = end + 1;
  }
  RIME_STRUCT(RimeContext, ctx);
  const bool context_ok = api->get_context(session, &ctx) != False;
  const std::string preedit = context_ok && ctx.composition.preedit ? ctx.composition.preedit : "";
  const int page_size = context_ok ? ctx.menu.page_size : 30;
  api->free_context(&ctx);
  const auto live = rime::Service::instance().GetSession(session);
  const auto input_length = live && live->context() ? live->context()->input().size() : 0;
  std::vector<std::string> candidates, matches;
  RimeCandidateListIterator iterator = {};
  const auto start = std::chrono::steady_clock::now();
  bool next = api->candidate_list_begin(session, &iterator) && api->candidate_list_next(&iterator);
  int count = 0;
  bool answer_stop = false;
  while (next && count < limit) {
    const std::string text = iterator.candidate.text ? iterator.candidate.text : "";
    if (count < 200 || wanted.count(text)) {
      std::string item = "{\"text\":";
      JsonAppendString(&item, text.c_str());
      item.append(",\"engine_index\":" + std::to_string(iterator.index));
      size_t input_end = 0;
      std::string candidate_type;
      double quality = 0;
      double log_weight = std::nan("");
      if (live && live->context()->HasMenu()) {
        const auto menu = live->context()->composition().back().menu;
        const auto candidate = menu->GetCandidateAt(iterator.index);
        if (candidate) {
          input_end = candidate->end(); candidate_type = candidate->type(); quality = candidate->quality();
          auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(candidate));
          if (phrase) log_weight = phrase->entry().weight;
        }
      }
      const bool full_input = input_end == input_length;
      item.append(",\"input_end\":" + std::to_string(input_end));
      item.append(",\"full_input\":"); item.append(full_input ? "true" : "false");
      item.append(",\"candidate_type\":"); JsonAppendString(&item, candidate_type.c_str());
      auto number = [&](const char* name, double value) {
        item.append(name);
        if (!std::isfinite(value)) { item.append("null"); return; }
        char buffer[64]; std::snprintf(buffer, sizeof buffer, "%.17g", value); item.append(buffer);
      };
      number(",\"quality\":", quality);
      number(",\"log_weight\":", log_weight);
      item.append(",\"is_original\":");
      item.append(IsOriginalCandidate(session, iterator.index) ? "true" : "false");
      item.append(",\"comment\":");
      JsonAppendString(&item, iterator.candidate.comment ? iterator.candidate.comment : "");
      item.push_back('}');
      if (count < 200) candidates.push_back(item);
      if (full_input && wanted.count(text) && found.insert(text).second) matches.push_back(item);
    }
    ++count;
    next = api->candidate_list_next(&iterator);
    if (!found.empty() && count >= 200) { answer_stop = next; break; }
  }
  api->candidate_list_end(&iterator);
  const double elapsed = std::chrono::duration<double, std::micro>(
      std::chrono::steady_clock::now() - start).count();
  std::string out = "{\"type\":\"action\",\"action\":\"scan\",\"n\":" + std::to_string(action_no) +
                    ",\"handled\":true,\"key_us\":0,\"ctx_us\":0,\"page_no\":0,\"page_size\":" +
                    std::to_string(page_size) + ",\"preedit\":";
  JsonAppendString(&out, preedit.c_str());
  out.append(",\"candidates\":[");
  for (size_t i = 0; i < candidates.size(); ++i) {
    if (i) out.push_back(',');
    out.append(candidates[i]);
  }
  out.append("],\"matches\":[");
  for (size_t i = 0; i < matches.size(); ++i) {
    if (i) out.push_back(',');
    out.append(matches[i]);
  }
  out.append("],\"pool_scanned\":" + std::to_string(count));
  out.append(",\"pool_exhausted\":");
  out.append(next ? "false" : "true");
  out.append(",\"pool_stop_reason\":");
  JsonAppendString(&out, !next ? "exhausted" : answer_stop ? "answer-found" : "limit");
  out.append(",\"pool_us\":" + std::to_string(elapsed) + "}");
  std::puts(out.c_str());
  std::fflush(stdout);
}

bool PrintSourceIdentity(const char* build_dir) {
  rime::Table table{rime::path(std::string(build_dir) + "/pinyin.table.bin")};
  if (!table.Load()) return false;
  std::printf("{\"pinyin_dict_crc32\":%u}\n", table.dict_file_checksum());
  return true;
}
