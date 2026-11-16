#ifndef QINGYU_PROBE_OBSERVATION_H_
#define QINGYU_PROBE_OBSERVATION_H_

#include <string>
#include <rime_api.h>

void JsonAppendString(std::string* out, const char* text);
void EmitCandidatePool(RimeApi* api, RimeSessionId session, int action_no,
                       const std::string& targets, int limit);
bool PrintSourceIdentity(const char* build_dir);

#endif
