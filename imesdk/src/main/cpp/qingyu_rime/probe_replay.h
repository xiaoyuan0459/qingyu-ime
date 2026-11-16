// 逐动作回放入口声明。实现与动作流格式见 probe_replay.cc 头部注释；
// 调用方（probe.cc --replay）只负责开文件与收退出码。
#ifndef QINGYU_PROBE_REPLAY_H_
#define QINGYU_PROBE_REPLAY_H_

#include <cstdio>

bool ReplayActions(const char* shared_dir, const char* user_dir,
                   const char* schema, FILE* input);

#endif  // QINGYU_PROBE_REPLAY_H_
