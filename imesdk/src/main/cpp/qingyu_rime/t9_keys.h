#pragma once
#include <string>

// 九宫格键形：每个字母折成它所在键的首字母（abc→A、def→D … wxyz→W）。
//
// 必须与下列三处逐条一致，任何一处改动都要同步，否则键形索引会和棱镜的码空间错开，
// 表现为「候选凭空消失」而不是报错：
// - `imesdk/src/main/assets/rime/build/t9_pinyin.schema.yaml` 里 `derive/[abc]/A/`
//   起的那一组规则（棱镜由它编译）；
// - 应用层 `T9PinYinUtils.pinyinMap`（数字键 → 送进引擎的字母）；
// - 本函数（原生侧按音节反查词条时用）。
inline std::string EncodeKeys(const std::string& input) {
  std::string result = input;
  for (char& c : result) {
    if (c >= 'a' && c <= 'c') c = 'A';
    else if (c >= 'd' && c <= 'f') c = 'D';
    else if (c >= 'g' && c <= 'i') c = 'G';
    else if (c >= 'j' && c <= 'l') c = 'J';
    else if (c >= 'm' && c <= 'o') c = 'M';
    else if (c >= 'p' && c <= 's') c = 'P';
    else if (c >= 't' && c <= 'v') c = 'T';
    else if (c >= 'w' && c <= 'z') c = 'W';
  }
  return result;
}
