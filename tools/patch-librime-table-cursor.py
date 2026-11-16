#!/usr/bin/env python3
"""Read-only table cursors and explicit syllable lookup preparation for the IME."""
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def patch(root: Path):
    revision = json.loads((ROOT / 'tools/native-deps.lock.json').read_text(encoding='utf8'))['librime']['revision']
    if subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip() != revision:
        raise ValueError('librime table cursor revision mismatch')
    cursor_declaration = '''  table::TrunkIndexNode* FindNode(table::TrunkIndex* index,
                                  SyllableId key) const;
  mutable table::TrunkIndex* cursor_index_ = nullptr;
  mutable table::TrunkIndexNode* cursor_node_ = nullptr;
  mutable SyllableId cursor_key_ = -1;

'''
    cursor_implementation = '''table::TrunkIndexNode* TableQuery::FindNode(table::TrunkIndex* index,
                                                 SyllableId key) const {
  auto first = index->begin();
  const auto last = index->end();
  auto lower_bound = [](auto begin, auto end, SyllableId value) {
    return std::lower_bound(begin, end, value,
        [](const table::TrunkIndexNode& node, SyllableId id) { return node.key < id; });
  };
  if (cursor_index_ == index && key >= cursor_key_) {
    first = cursor_node_;
    if (key == cursor_key_ || first == last || first->key >= key)
      return first == last || first->key != key ? last : first;
    // Graph ids arrive sorted; bound the next search near the previous result.
    auto remaining = last - first;
    decltype(remaining) step = 1;
    while (step < remaining && first[step].key < key) {
      first += step;
      remaining -= step;
      step = step > remaining / 2 ? remaining : step * 2;
    }
    auto bound = step < remaining ? first + step + 1 : last;
    cursor_node_ = lower_bound(first, bound, key);
  } else {
    cursor_node_ = lower_bound(first, last, key);
  }
  cursor_index_ = index;
  cursor_key_ = key;
  return cursor_node_ == last || cursor_node_->key != key ? last : cursor_node_;
}

'''
    syllable_implementation = '''void Table::PrepareSyllableLookup() {
  std::lock_guard<std::mutex> lock(syllable_lookup_mutex_);
  if (syllable_lookup_ready_ || !syllabary_ || !string_table_) return;
  vector<string> lookup;
  lookup.reserve(syllabary_->size);
  for (size_t id = 0; id < syllabary_->size; ++id)
    lookup.push_back(GetSyllableById(static_cast<SyllableId>(id)));
  std::sort(lookup.begin(), lookup.end());
  syllable_lookup_ = std::move(lookup);
  syllable_lookup_ready_ = true;
}

bool Table::HasLoadedSyllable(const string& spelling) const {
  return std::binary_search(syllable_lookup_.begin(), syllable_lookup_.end(), spelling);
}

'''
    reset = '  syllable_lookup_.clear();\n  syllable_lookup_ready_ = false;\n'
    changes = [
        ('src/rime/dict/table.h', '#include <cstring>', '#include <mutex>\n', False),
        ('src/rime/dict/table.h', '  bool Walk(SyllableId syllable_id);', cursor_declaration, False),
        ('src/rime/dict/table.h', '  bool GetSyllabary(Syllabary* syllabary);',
         '  RIME_API void PrepareSyllableLookup();\n  RIME_API bool HasLoadedSyllable(const string& spelling) const;\n', False),
        ('src/rime/dict/table.h', '  the<StringTable> string_table_;',
         '  std::mutex syllable_lookup_mutex_;\n  bool syllable_lookup_ready_ = false;\n  vector<string> syllable_lookup_;\n', False),
        ('src/rime/dict/table.cc', 'bool TableQuery::Walk(SyllableId syllable_id) {', cursor_implementation, False),
        ('src/rime/dict/table.cc', 'bool Table::GetSyllabary(Syllabary* result) {', syllable_implementation, False),
        ('src/rime/dict/table.cc', 'bool Table::OnBuildStart() {\n', reset, True),
        ('src/rime/dict/table.cc', '  string_table_.reset(new StringTable(metadata_->string_table.get(),\n                                      metadata_->string_table_size));\n', reset, True),
    ]
    texts = {}
    for name, anchor, addition, after in changes:
        path = root / name
        text = texts.get(path, path.read_text(encoding='utf8'))
        replacement = anchor + addition if after else addition + anchor
        if replacement not in text:
            if text.count(anchor) != 1:
                raise ValueError(f'table cursor anchor mismatch: {name}')
            texts[path] = text.replace(anchor, replacement, 1)
    path = root / 'src/rime/dict/table.cc'
    text = texts.get(path, path.read_text(encoding='utf8'))
    for index in ('lv2_index_', 'lv3_index_', 'index'):
        expression = f'find_node({index}->begin(), {index}->end(), syllable_id)'
        replacement = f'FindNode({index}, syllable_id)'
        if replacement not in text:
            if text.count(expression) != 1:
                raise ValueError('table cursor lookup anchor mismatch')
            text = text.replace(expression, replacement, 1)
    if text != path.read_text(encoding='utf8'):
        texts[path] = text
    for path, text in texts.items():
        path.write_text(text, encoding='utf8', newline='\n')
    print(json.dumps({'revision': revision, 'changed_files': len(texts)}))


if __name__ == '__main__':
    try:
        patch(Path(sys.argv[1]))
    except (ValueError, OSError, subprocess.CalledProcessError, IndexError) as error:
        print(f'librime table cursor patch: {error}', file=sys.stderr)
        raise SystemExit(1) from None
