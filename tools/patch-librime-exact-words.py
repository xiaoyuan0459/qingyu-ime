#!/usr/bin/env python3
"""Query a proven canonical syllable sequence without parsing it a second time."""
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

QUERY = '''an<Translation> ScriptTranslator::QueryExactWords(
    const string& input, const Code& code, const Segment& segment) {
  if (!dict_ || !dict_->loaded() || !segment.HasTag(tag_)) return nullptr;
  FinishSession();
  const bool use_user = user_dict_ && user_dict_->loaded() && !IsUserDictDisabledFor(input);
  auto result = New<ScriptTranslation>(this, nullptr, poet_.get(), input,
                                       segment.start, engine_->context()->input().length());
  if (!result->EvaluateExactWords(dict_.get(), use_user ? user_dict_.get() : nullptr, code))
    return nullptr;
  auto deduped = New<DistinctTranslation>(result);
  return contextual_suggestions_ ? poet_->ContextualWeighted(deduped, input, segment.start, this) : deduped;
}

'''

GRAPH = '''bool ScriptSyllabifier::BuildExactSyllableGraph(Dictionary* dict, const Code& code) {
  vector<string> sounds;
  if (!dict || code.empty() || code.size() > 4 || input_.size() > 24 ||
      !dict->Decode(code, &sounds) || sounds.size() != code.size()) return false;
  string joined;
  for (const auto& sound : sounds) {
    if (sound.empty()) return false;
    joined += sound;
  }
  if (joined != input_) return false;
  syllable_graph_ = SyllableGraph();
  syllable_graph_.input_length = syllable_graph_.interpreted_length = input_.size();
  size_t start = 0;
  syllable_graph_.vertices[0] = kNormalSpelling;
  for (size_t i = 0; i < sounds.size(); ++i) {
    const size_t end = start + sounds[i].size();
    auto& props = syllable_graph_.edges[start][end][code[i]];
    props.end_pos = end;
    syllable_graph_.vertices[end] = kNormalSpelling;
    syllable_graph_.indices[start][code[i]].push_back(&props);
    start = end;
  }
  return true;
}

'''

EVALUATE = '''bool ScriptTranslation::EvaluateExactWords(
    Dictionary* dict, UserDictionary* user_dict, const Code& code) {
  if (!syllabifier_->BuildExactSyllableGraph(dict, code)) return false;
  const auto& graph = syllabifier_->syllable_graph();
  phrase_ = dict->Lookup(graph, 0, false);
  if (user_dict) user_phrase_ = user_dict->Lookup(graph, 0, 0, 0);
  // Recovery asks for whole words only; retain the stock candidate preparation,
  // user weighting, spelling comments, syllabifier and learning identity.
  if (phrase_) {
    for (auto it = phrase_->begin(); it != phrase_->end();) {
      if (it->first != graph.interpreted_length) it = phrase_->erase(it);
      else ++it;
    }
    if (phrase_->empty()) phrase_.reset();
    else phrase_iter_ = phrase_->rbegin();
  }
  if (user_phrase_) {
    for (auto it = user_phrase_->begin(); it != user_phrase_->end();) {
      if (it->first != graph.interpreted_length) it = user_phrase_->erase(it);
      else ++it;
    }
    if (user_phrase_->empty()) user_phrase_.reset();
    else user_phrase_iter_ = user_phrase_->rbegin();
  }
  return !CheckEmpty();
}

'''


def patch(root):
    revision = json.loads((ROOT / 'tools/native-deps.lock.json').read_text(encoding='utf-8'))['librime']['revision']
    if subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip() != revision:
        raise ValueError('exact word revision mismatch')
    header = 'src/rime/gear/script_translator.h'
    source = 'src/rime/gear/script_translator.cc'
    changes = [
        (header, '  string FormatPreedit(const string& preedit);',
         '  an<Translation> QueryExactWords(const string& input, const Code& code, const Segment& segment);\n\n'),
        (source, '  size_t BuildSyllableGraph(Prism& prism);',
         '  bool BuildExactSyllableGraph(Dictionary* dict, const Code& code);\n'),
        (source, '  bool Evaluate(Dictionary* dict, UserDictionary* user_dict);',
         '  bool EvaluateExactWords(Dictionary* dict, UserDictionary* user_dict, const Code& code);\n'),
        (source, 'string ScriptTranslator::FormatPreedit(const string& preedit) {', QUERY),
        (source, 'size_t ScriptSyllabifier::BuildSyllableGraph(Prism& prism) {', GRAPH),
        (source, 'bool ScriptTranslation::Evaluate(Dictionary* dict, UserDictionary* user_dict) {', EVALUATE),
    ]
    texts = {}
    for name, anchor, addition in changes:
        path = root / name
        text = texts.get(path, path.read_text(encoding='utf-8'))
        replacement = addition + anchor
        if replacement in text:
            continue
        if addition in text or text.count(anchor) != 1:
            raise ValueError('exact word anchor mismatch: ' + name)
        texts[path] = text.replace(anchor, replacement, 1)
    for path, text in texts.items():
        path.write_text(text, encoding='utf-8', newline='\n')
    print(json.dumps({'revision': revision, 'changed_files': len(texts)}))


if __name__ == '__main__':
    try:
        patch(Path(sys.argv[1]))
    except (ValueError, OSError, subprocess.CalledProcessError, IndexError) as error:
        print('librime exact word patch: ' + str(error), file=sys.stderr)
        raise SystemExit(1) from None
