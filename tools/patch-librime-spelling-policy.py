#!/usr/bin/env python3
"""Expose a spelling-admission hook before graph pruning and sentence generation."""
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def patch(root: Path):
    revision = json.loads((ROOT / 'tools/native-deps.lock.json').read_text(encoding='utf8'))['librime']['revision']
    if subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip() != revision:
        raise ValueError('librime spelling policy revision mismatch')
    changes = [
        ('src/rime/algo/syllabifier.h', '  RIME_API void EnableCorrection(Corrector* corrector);',
         '  void SetSpellingFilter(function<bool(SyllableId, const string&, SpellingType)> filter) {\n'
         '    spelling_filter_ = std::move(filter);\n  }\n\n'),
        ('src/rime/algo/syllabifier.h', '  string delimiters_;',
         '  function<bool(SyllableId, const string&, SpellingType)> spelling_filter_;\n'),
        ('src/rime/algo/syllabifier.cc', '          if (strict_spelling_ && matches_input &&',
         '          if (spelling_filter_ && !spelling_filter_(\n'
         '                  syllable_id, input.substr(current_pos, m.length), props.type)) {\n'
         '            accessor.Next();\n            continue;\n          }\n'),
        ('src/rime/gear/script_translator.h', '  virtual bool Memorize(const CommitEntry& commit_entry);',
         '  virtual bool AllowSpelling(int32_t id, const string& input, SpellingType type) {\n'
         '    return true;\n  }\n'),
        ('src/rime/gear/script_translator.cc', '    if (corrector) {\n      syllabifier_.EnableCorrection(corrector);',
         '    syllabifier_.SetSpellingFilter([translator](SyllableId id, const string& input, SpellingType type) {\n'
         '      return translator->AllowSpelling(id, input, type);\n    });\n'),
    ]
    texts = {}
    for name, anchor, addition in changes:
        path = root / name
        text = texts.get(path, path.read_text(encoding='utf8'))
        if addition in text:
            continue
        if text.count(anchor) != 1:
            raise ValueError(f'spelling policy anchor mismatch: {name}')
        texts[path] = text.replace(anchor, addition + anchor, 1)
    for path, text in texts.items():
        path.write_text(text, encoding='utf8', newline='\n')
    print(json.dumps({'revision': revision, 'changed_files': len(texts)}))


if __name__ == '__main__':
    try:
        patch(Path(sys.argv[1]))
    except (ValueError, OSError, subprocess.CalledProcessError, IndexError) as error:
        print(f'librime spelling policy patch: {error}', file=sys.stderr)
        raise SystemExit(1) from None
