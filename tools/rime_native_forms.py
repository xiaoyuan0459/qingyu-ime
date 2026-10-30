"""Native lexical forms: direct CC-CEDICT entries, with source frequencies joined by word/readings."""
import collections
import re
import gzip
import hashlib
import json
from pathlib import Path
ENTRY = re.compile(r"^(\S+) (\S+) \[([^]]+)\] /")
CODE = re.compile(r"^[a-z]+(?: [a-z]+)*$")


def configure_schema(text):
    """Keep original entries; add a second native dictionary instead of a converter."""
    for block in ("simplifier", "traditionalize"):
        text = re.sub(r"^" + block + r":\n.*?(?=^[a-z_]+:|\Z)", "", text, flags=re.M | re.S)
    text = re.sub(r'^ +-[^\n]*(?:simplifier@traditionalize|^- simplifier)[^\n]*\n', '', text, flags=re.M)
    text = re.sub(r'^ +- simplifier\s*(?:#[^\n]*)?\n', '', text, flags=re.M)
    # Remove the old explanatory t2s comments along with that implementation.
    text = re.sub(r'^# 关闭「繁体输入」.*?(?=^[a-z_]+:|\Z)', '', text, flags=re.M | re.S)
    if 'native_traditional:' in text:
        return text
    schema = re.search(r'^  schema_id: (\S+)', text, re.M).group(1)
    if schema in {"english", "stroke"}:
        return text
    translator = re.search(r'^translator:\n(.*?)(?=^[a-z_]+:|\Z)', text, re.M | re.S)
    if not translator:
        raise ValueError("Missing primary translator: " + schema)
    body = translator.group(1)
    if re.search(r'^  dictionary: pinyin$', body, re.M) is None:
        raise ValueError("Unexpected primary dictionary: " + schema)
    text = re.sub(r'^( +)- script_translator\s*$', r'\1- "qingyu_native@translator"\n\1- "qingyu_native@native_traditional"', text, flags=re.M)
    native = re.sub(r'^  dictionary: pinyin$', '  dictionary: native_hant', body, flags=re.M)
    native = re.sub(r'^  prism:.*\n', '', native, flags=re.M)
    native = re.sub(r'^  user_dict:.*\n', '', native, flags=re.M)
    return text.rstrip() + '\n\nnative_traditional:\n' + native + f'  prism: {schema}_native_hant\n  user_dict: native_hant\n'


def build_native_forms(workspace, repository):
    source = repository / "imesdk/rime-src/native"
    metadata = json.loads((source / "source.json").read_text(encoding="utf-8"))
    data = gzip.decompress((source / "cc-cedict-20261028.txt.gz").read_bytes())
    if hashlib.sha256(data).hexdigest() != metadata["sha256"]:
        raise ValueError("CC-CEDICT source checksum mismatch")
    plain = workspace / "cc-cedict-source.txt"
    plain.write_bytes(data)
    records, header = read_cedict(plain)
    variants = {}
    for line in data.decode('utf-8-sig').splitlines():
        match = ENTRY.match(line)
        if not match:
            continue
        hant, _, sound = match.groups()
        definition = line[match.end():]
        # Source-annotated alternative spellings remain available; canonical forms rank first.
        factor = 0.25 if re.match(r'(?:(?:old|archaic) )?variant of ', definition) else 1.0
        key = (hant, reading(sound))
        variants[key] = max(variants.get(key, 0), factor)
    stats, weights = analyze(workspace, records)
    plain.unlink()
    entries = {}
    for _, hant, sound in records:
        entries[hant, sound] = max(entries.get((hant, sound), 0),
            max(1, weights.get((hant, sound), 1)) * variants.get((hant, sound), 1.0))
    with (workspace / "native_hant.dict.yaml").open("w", encoding="utf-8", newline="\n") as stream:
        stream.write('# CC-CEDICT input: CC-BY-SA-4.0; frequency-joined adaptation: GPL-3.0-only. See native-forms.json\n')
        stream.write('---\nname: native_hant\nversion: "2026.10.28"\nsort: by_weight\nuse_preset_vocabulary: false\n...\n')
        for (text, sound), weight in sorted(entries.items()):
            stream.write(f'{text}\t{sound}\t{weight:g}\n')
    metadata.update({"records":len(entries),"adapterSpdx":"GPL-3.0-only","compatibility":"https://creativecommons.org/compatible-licenses/","coverage":stats,"selection":"native word forms only; all ambiguous alternatives retained","changes":"English definitions omitted; native traditional entries indexed with joined source word frequencies"})
    (workspace / "native-forms.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding="utf-8")
    (workspace / "AUTHORS.cc-cedict").write_text('\n'.join(header) + '\n\nQingyu modifications: native word-form dictionary, word-frequency join, prefix index.\nAdapter license: GPL-3.0-only (CC BY-SA 4.0 compatible license); original CC-BY-SA-4.0 terms and attribution retained.\n', encoding="utf-8")


def native_compile_schemas(workspace):
    """Pinned deployer compiles translator/dictionary only; hidden schemas build other prisms."""
    schemas = []
    for path in sorted(workspace.glob('*.schema.yaml')):
        text = path.read_text(encoding='utf-8')
        native = re.search(r'^native_traditional:\n(.*?)(?=^[a-z_]+:|\Z)', text, re.M | re.S)
        if not native:
            continue
        schema = re.search(r'^  schema_id: (\S+)', text, re.M).group(1)
        build_id = 'native_' + schema
        speller = re.search(r'^speller:\n.*?(?=^[a-z_]+:|\Z)', text, re.M | re.S)
        if not speller:
            raise ValueError('Missing spelling rules: ' + schema)
        text = (f'schema:\n  schema_id: {build_id}\n  name: {build_id}\n'
                'engine:\n  processors: [speller, selector, express_editor]\n'
                '  segmentors: [abc_segmentor, fallback_segmentor]\n'
                '  translators: [script_translator]\nmenu:\n  page_size: 30\n'
                + speller.group(0) + 'translator:\n' + native.group(1))
        (workspace / (build_id + '.schema.yaml')).write_text(text, encoding='utf-8', newline='\n')
        schemas.append(build_id)
    return schemas

def reading(value):
    return re.sub(r"[1-5]", "", value.lower().replace("u:", "v").replace("ü", "v")).strip()


def read_cedict(path):
    records = []
    header = []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.startswith("#"):
            header.append(line)
        match = ENTRY.match(line)
        if not match:
            continue
        hant, hans, sound = match.groups()
        sound = reading(sound)
        if CODE.fullmatch(sound):
            records.append((hans, hant, sound))
    return records, header


def analyze(workspace, records):
    forms = collections.defaultdict(set)
    for hans, hant, sound in records:
        forms[hans, sound].add(hant)
        forms[hant, sound].add(hant)
    header = (workspace / "pinyin.dict.yaml").read_text(encoding="utf-8").split("...", 1)[0]
    tables = re.findall(r"^\s+-\s+([\w/-]+)", header, re.M)
    total = matched = weighted_total = weighted_matched = 0
    weights = {}
    for table in tables:
        entries = False
        with (workspace / (table + ".dict.yaml")).open(encoding="utf-8-sig") as stream:
            for line in stream:
                if not entries:
                    entries = line.strip() == "..."
                    continue
                fields = line.rstrip("\r\n").split("\t")
                if len(fields) < 2:
                    continue
                text, sound = fields[:2]
                sound = reading(sound)
                if not CODE.fullmatch(sound):
                    continue
                total += 1
                try:
                    weight = max(0, float(fields[2])) if len(fields) > 2 else 0
                except ValueError:
                    weight = 0
                weighted_total += weight
                if (text, sound) in forms:
                    matched += 1
                    weighted_matched += weight
                    for hant in forms[text, sound]:
                        weights[hant, sound] = max(weights.get((hant, sound), 0), weight)
    stats = {
        "whiteSourceRows": total,
        "exactWordAndReadingRows": matched,
        "exactRowCoveragePercent": round(100 * matched / max(total, 1), 4),
        "weightCoveragePercent": round(100 * weighted_matched / max(weighted_total, 1), 4),
        "coverageMeaning": "source row and source weight ratios, not accuracy or real usage coverage",
        "tonePolicy": "toneless Rime; native alternatives are retained, never reduced to the first mapping",
    }
    return stats, weights
