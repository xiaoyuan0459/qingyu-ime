#pragma once
#include <string>
#include <vector>

// 五笔画通配查询：把 `*` 展开成 5 种笔画，逐个送入独立 stroke 会话，
// 再按各次结果内的名次轮转合并。调用方持有 JNI 互斥锁。
std::vector<std::string> QueryStrokeWildcard(const std::string& pattern,
                                             int max_candidates);

void CloseStrokeWildcardSession();
