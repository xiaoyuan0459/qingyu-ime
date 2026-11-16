#pragma once
#include <rime/translation.h>
#include <rime/segmentation.h>
#include <functional>
namespace rime { class Dictionary; }

bool HasLiteralWordSpelling(rime::Dictionary* dictionary,
    const rime::an<rime::Candidate>& candidate, const std::string& input);
bool HasPrefixWordSpelling(rime::Dictionary* dictionary,
    const rime::an<rime::Candidate>& candidate, const std::string& input);
// [penalty] 从词条质量里扣掉的分数。全拼的四类恢复用 0.5（错码几乎没有精确解释），
// 九键漏键用更小的值：那里「精确解释」和「漏一键」都是常见情况，靠罚分当概率阈值，
// 见 t9_missing_key_recovery.cc。
rime::an<rime::Candidate> MakeRecoveredWord(const rime::an<rime::Candidate>& word,
    const std::string& input, size_t end, double penalty = 0.5);
// 只改类型，质量 / end / preedit 全部保留。用于「这个词引擎本来就给过、只是被展示层
// 当成模糊或补全压掉了」的情形：重排位次会打乱原字模式的双流交错，重新贴标签不会。
rime::an<rime::Candidate> RetagAsCorrection(const rime::an<rime::Candidate>& word);
rime::an<rime::Candidate> RecoverTrailingLetterWord(rime::Dictionary* dictionary,
    const rime::an<rime::Candidate>& word, const std::string& input, size_t end);

rime::an<rime::Translation> RecoverWholeWord(
    const std::string& input, const rime::Segment& segment,
    rime::Dictionary* dictionary,
    rime::an<rime::Translation> original,
    const std::function<bool(const rime::an<rime::Candidate>&)>& admissible,
    const std::function<rime::an<rime::Translation>(const std::string&)>& query);
