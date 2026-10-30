#pragma once
#include <cstddef>
#include <rime_api.h>
void RegisterNativeScriptTranslator();
bool SetNativeScriptMode(RimeSessionId session, int mode);
bool IsOriginalCandidate(RimeSessionId session, size_t index);
