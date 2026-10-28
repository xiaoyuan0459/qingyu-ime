#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""组装并封装轻语内置的雾凇拼音词库。

词库源使用固定 revision 的 rime-ice（GPL-3.0-only），应用整体同样以
GPL-3.0-only 发布。脚本先生成 librime 部署输入，再把设备编译产物封装成带哈希清单的
`rime-base`；APK 只携带运行时产物，完整对应源码可由固定 revision 重建。

词表与拼音继承上游，**权重由本工程自己算**：组装时把 `--freq` 指向的覆盖表
（`tools/gen-dict-freq.py` 的产物）写进各分表的权重列，上游源文件保持原样；
自有增词与钉死权重放在 `imesdk/rime-src/cn_dicts/qingyu_overlay.dict.yaml`。

行尾口径：词库与词表一律按 **LF** 处理——校验用 `sha256_text`（先把 CRLF 折成 LF），
组装时文本也折成 LF 再落盘；因此 Windows 上 `core.autocrlf=true` 的检出不会算出第二个哈希，
编出来的 `*.table.bin` 也与 LF 检出一致。

用法：
    python tools/gen-dict-freq.py                         # 先生成频率覆盖表
    python tools/build-rime-pack.py                      # 组装到 build/m3/src
    python tools/build-rime-pack.py --no-freq            # 退回纯上游权重（对照用）
    python tools/build-rime-pack.py --out build/x/src    # 换输出目录
    python tools/build-rime-pack.py --package             # 封装已编译的 build/m3/out

上游数据缺失时脚本会打印获取命令后退出（不替你联网拉取）。
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

# 同目录模块：自有频率覆盖表的读取与注入（见 tools/rime_freq.py）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rime_freq import apply_freq_table  # noqa: E402
from rime_essay import DAMP_FACTOR, DAMP_FLOOR, damp_traditional, load_traditional_chars  # noqa: E402

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SCHEMA_DIR = os.path.join(REPO, "imesdk", "src", "main", "assets", "rime", "build")
MANIFEST_DIR = os.path.join(REPO, "imesdk", "rime-src")
RIME_ICE_REVISION = "9e66b0729083b37d217312294f6d516c8d7234be"
RIME_ESSAY_REVISION = "e2652ea18609a879eae3e87db5d25b7fbc1a4f93"
ESSAY_SHA256 = "e62fb950175ff5c33bff4480a8d96f7b4fa83e9a9420d32a596877aa61257ead"
# 五笔画词库：rime 官方仓，LGPL-3.0-or-later
RIME_STROKE_REVISION = "1e8fff9b9494ddec23b0cbc526bcfd8171a6fd48"
# 两个哈希都按**仓库 blob 内容（LF）**计算，见 sha256_text。Windows 上 `core.autocrlf=true`
# 的检出会把工作树转成 CRLF：同一份内容算出两个哈希，常量就只能对一半平台成立
# （2026-10-27 踩过——ESSAY 按 LF 定、STROKE 按 CRLF 定）。
STROKE_DICT_SHA256 = "b3e93dce89c185f45c3d6e189b86b3a8626913352cc85e1094c786579a665791"
SCHEMAS = [
    "t9_pinyin", "pinyin", "double_pinyin_natural", "double_pinyin_mspy",
    "double_pinyin_sogou", "double_pinyin_flypy", "double_pinyin_abc",
    "double_pinyin_ziguang", "double_pinyin_ls17", "stroke", "english", "associate",
]
PUBLIC_SCHEMAS = [schema for schema in SCHEMAS if schema != "associate"]

# 中英混输词表是 stabledb —— librime 把 `*.txt` 直接当只读库，不参与编译，
# 所以必须原样随 pack 分发（运行时按「用户目录 → shared 目录」回退解析）。
# 九宫格与乱序17 的键码不是拼音码的直接映射，这两套方案的 cn_en 接线另议。
CN_EN_TABLES = [
    "cn_en", "cn_en_double_pinyin", "cn_en_abc", "cn_en_flypy",
    "cn_en_mspy", "cn_en_sogou", "cn_en_ziguang",
]

RIME_FROST_REVISION = "96278d87bf29403dd16ac854e71c496ab6ee5e71"

# 上游词库源。ice 是随 APK 分发的默认包；frost 只做独立可导入的 pack，不进 APK。
UPSTREAMS = {
    "ice": {
        "clone": os.path.join(REPO, "build", "rime-src"),
        "repo": "https://github.com/iDvel/rime-ice",
        "revision": RIME_ICE_REVISION,
        "id": "rime-ice-qingyu",
        "display": "雾凇拼音（轻语版）",
        # rime-ice 的 README 与 LICENSE 明确写 GPL-3.0 (only)
        "spdx": "GPL-3.0-only",
        "version": "2026.09.20+9e66b07",
        "work": os.path.join(REPO, "build", "m3"),
        "archive": os.path.join(REPO, "imesdk", "src", "main", "assets", "rime-base.zip"),
    },
    "frost": {
        "clone": os.path.join(REPO, "build", "upstream", "rime-frost"),
        "repo": "https://github.com/gaboolic/rime-frost",
        "revision": RIME_FROST_REVISION,
        "id": "rime-frost-qingyu",
        "display": "白霜拼音（轻语版）",
        # 白霜仓内只有 GPL-3.0 正文、README 未声明 only，按 SPDX 惯例记为 or-later
        "spdx": "GPL-3.0-or-later",
        "version": "2026.09.20+96278d8",
        "work": os.path.join(REPO, "build", "frost"),
        "archive": None,  # 独立分发，绝不解进 assets
    },
}


def clone_hint(upstream):
    return (
        "词库源不在 {clone}。先浅克隆（上游数据不入库）：\n"
        # autocrlf=false：检出即仓库 blob，LF 口径的哈希才对得上（脚本自己也会折行，
        # 但保持工作树干净能少一层误判）
        "  git -c core.autocrlf=false clone " + upstream["repo"] + ".git {clone}\n"
        "  git -C {clone} checkout " + upstream["revision"] + "\n"
    )


ESSAY_HINT = (
    "预置词汇表不在 {essay}。它来自 rime/rime-essay（LGPL-3.0），是候选深度与\n"
    "排序的权重基线，缺它 `nihao` 的候选数会从 100 塌到 30：\n"
    f"  curl -L -o {{essay}} https://raw.githubusercontent.com/rime/rime-essay/{RIME_ESSAY_REVISION}/essay.txt\n"
)
STROKE_HINT = (
    "五笔画词库不在 {stroke}。先浅克隆（上游数据不入库）：\n"
    "  git -c core.autocrlf=false clone https://github.com/rime/rime-stroke.git {stroke}\n"
    f"  git -C {{stroke}} checkout {RIME_STROKE_REVISION}\n"
    "该仓是 LGPL-3.0-or-later，主码表源自台湾 CNS11643 全字库、附码表源自北大中文论坛。\n"
)


def require(path, hint, **context):
    """缺文件时打印获取方式。

    `context` 用来纠正提示里的占位符：`require` 常被传具体文件路径，而提示里
    {clone} / {stroke} 指的是目录，直接用 path 填会打印出一条不存在的克隆命令。
    """
    if not os.path.exists(path):
        values = {"path": path, "clone": path, "essay": path, "stroke": path}
        values.update(context)
        sys.exit(hint.format(**values))


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_text(path):
    """文本输入的 SHA-256，先把 CRLF 折成 LF。

    上游 blob 一律是 LF，而 Windows 上 `core.autocrlf=true` 的检出会把工作树换成 CRLF。
    不折的话同一个 revision 在两台机器上算出两个哈希，钉住的常量就只能对一半平台成立
    （2026-10-27 的实际故障：`ESSAY_SHA256` 按 LF 定、`STROKE_DICT_SHA256` 按 CRLF 定，
    于是在 Windows 上 essay 校验必然失败）。
    """
    with open(path, "rb") as stream:
        return hashlib.sha256(stream.read().replace(b"\r\n", b"\n")).hexdigest()


# 组装时按文本复制并折成 LF 的后缀：词库、词表、方案与配置。二进制产物不在其中。
TEXT_SUFFIXES = (".yaml", ".yml", ".txt", ".tsv")


def copy_normalized(src, dst):
    """复制一个文件；文本类把 CRLF 折成 LF，其余原样。

    部署输入必须与检出风格无关：否则同一 revision 在 autocrlf 开/关的机器上编出的
    `*.table.bin` 可能不同（行尾的 `\\r` 会跟着进词条），pack 也失去字节级可复现性。
    """
    with open(src, "rb") as stream:
        data = stream.read()
    if src.endswith(TEXT_SUFFIXES):
        data = data.replace(b"\r\n", b"\n")
    with open(dst, "wb") as stream:
        stream.write(data)
    shutil.copystat(src, dst)


def pack_version(upstream_version, files):
    """版本目录必须随内容变：`RimePackStore` 按 id/version 落盘，同版本内容不同会被
    「同版本 pack 内容不同，请提升版本号」直接拒装，改一条 algebra 就上不了机。
    上游 version 只跟 rime-ice 的发布走，所以再拼一段文件清单摘要作内容标识。"""
    payload = json.dumps(files, sort_keys=True, ensure_ascii=False).encode("utf-8")
    return f"{upstream_version}+c{hashlib.sha256(payload).hexdigest()[:8]}"


def verify_inputs(clone, essay, stroke, expected_revision):
    revision = subprocess.check_output(
        ["git", "-C", clone, "rev-parse", "HEAD"], text=True
    ).strip()
    if revision != expected_revision:
        sys.exit(f"上游词库 revision 不符：{revision}，要求 {expected_revision}")
    actual_essay = sha256_text(essay)
    if actual_essay != ESSAY_SHA256:
        sys.exit(f"essay.txt SHA-256 不符：{actual_essay}（按 LF 口径）")
    stroke_revision = subprocess.check_output(
        ["git", "-C", stroke, "rev-parse", "HEAD"], text=True
    ).strip()
    if stroke_revision != RIME_STROKE_REVISION:
        sys.exit(f"rime-stroke revision 不符：{stroke_revision}，要求 {RIME_STROKE_REVISION}")
    actual_stroke = sha256_text(os.path.join(stroke, "stroke.dict.yaml"))
    if actual_stroke != STROKE_DICT_SHA256:
        sys.exit(f"stroke.dict.yaml SHA-256 不符：{actual_stroke}（按 LF 口径）")


def write_archive(pack_out, archive_out):
    os.makedirs(os.path.dirname(archive_out), exist_ok=True)
    temp = archive_out + ".tmp"
    if os.path.exists(temp):
        os.remove(temp)
    with zipfile.ZipFile(temp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for root, _, names in os.walk(pack_out):
            for name in sorted(names):
                path = os.path.join(root, name)
                relative = os.path.relpath(path, pack_out).replace(os.sep, "/")
                info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                with open(path, "rb") as stream:
                    archive.writestr(info, stream.read(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    os.replace(temp, archive_out)
    print(f"内置 pack 归档：{archive_out}（{os.path.getsize(archive_out) / 1024 / 1024:.1f} MB）")


def package_runtime(source, compiled, clone, stroke, pack_out, archive_out, upstream):
    require(compiled, "编译产物目录不存在: {path}")
    if os.path.isdir(pack_out):
        shutil.rmtree(pack_out)
    os.makedirs(pack_out)
    shutil.copytree(compiled, os.path.join(pack_out, "build"))
    # associate 需要参与编译，但它只是联想内部查询方案，不出现在用户方案列表中。
    with open(os.path.join(pack_out, "build", "default.yaml"), "w", encoding="utf-8", newline="\n") as stream:
        stream.write("menu:\n  page_size: 100\nschema_list:\n")
        stream.writelines(f"  - schema: {schema}\n" for schema in PUBLIC_SCHEMAS)
    # emoji 来自 rime-ice；简繁转换表来自 OpenCC（均可在 GPL-3.0-only 下组合分发）。
    opencc_out = os.path.join(pack_out, "opencc")
    shutil.copytree(os.path.join(REPO, "imesdk", "src", "main", "assets", "rime", "opencc"), opencc_out)
    # 中英混输词表：原样进包，运行时由 librime 当只读 stabledb 打开
    tables_out = os.path.join(pack_out, "en_dicts")
    os.makedirs(tables_out)
    for table in CN_EN_TABLES:
        source = os.path.join(clone, "en_dicts", table + ".txt")
        require(source, f"缺少中英混输词表 {{path}}（应来自 rime-ice 的 en_dicts/）")
        copy_normalized(source, os.path.join(tables_out, table + ".txt"))
    copy_normalized(os.path.join(clone, "LICENSE"), os.path.join(pack_out, "LICENSE"))
    essay_license = os.path.join(REPO, "build", "m3", "LICENSE.rime-essay")
    require(
        essay_license,
        f"缺少 rime-essay 许可证，请下载 https://raw.githubusercontent.com/rime/rime-essay/{RIME_ESSAY_REVISION}/LICENSE 到 {{path}}",
    )
    copy_normalized(essay_license, os.path.join(pack_out, "LICENSE.rime-essay"))
    stroke_license = os.path.join(stroke, "LICENSE")
    require(stroke_license, "缺少 rime-stroke 的 LICENSE，请先克隆到 {path}")
    copy_normalized(stroke_license, os.path.join(pack_out, "LICENSE.rime-stroke"))
    stroke_authors = os.path.join(stroke, "AUTHORS")
    require(stroke_authors, "缺少 rime-stroke 的 AUTHORS（码表归属声明），请先克隆到 {path}")
    copy_normalized(stroke_authors, os.path.join(pack_out, "AUTHORS.rime-stroke"))
    with open(os.path.join(pack_out, "SOURCE.md"), "w", encoding="utf-8", newline="\n") as stream:
        stream.write(
            "# 对应源码\n\n"
            f"- 词库源：{upstream['display']} {upstream['repo']}/tree/{upstream['revision']}\n"
            f"- rime-essay: https://github.com/rime/rime-essay/tree/{RIME_ESSAY_REVISION} "
            f"（essay.txt SHA-256: `{ESSAY_SHA256}`）\n"
            f"- rime-stroke（五笔画，LGPL-3.0-or-later）: "
            f"https://github.com/rime/rime-stroke/tree/{RIME_STROKE_REVISION} "
            f"（stroke.dict.yaml SHA-256: `{STROKE_DICT_SHA256}`，码表归属见 `AUTHORS.rime-stroke`）\n"
            "- 方案装配清单：本仓库 `imesdk/rime-src/`（词典装配表）+ "
            "`imesdk/src/main/assets/rime/build/`（各 `*.schema.yaml` 与 `default.yaml`）\n"
            "- 构建脚本：本仓库 `tools/build-rime-pack.py`（装配源树并调用 `qingyu_deployer` 编译）\n"
            "- OpenCC 简繁转换表：https://github.com/BYVoid/OpenCC\n"
        )

    required = ["build/default.yaml", "build/pinyin.table.bin",
                "build/english.table.bin", "build/stroke.table.bin"]
    required.extend(f"build/{schema}.schema.yaml" for schema in SCHEMAS)
    required.extend(
        f"build/{schema}.prism.bin" for schema in SCHEMAS if schema != "associate"
    )
    missing = [path for path in required if not os.path.isfile(os.path.join(pack_out, *path.split("/")))]
    if missing:
        sys.exit("编译产物不完整：" + ", ".join(missing))

    files = []
    for root, _, names in os.walk(pack_out):
        for name in names:
            path = os.path.join(root, name)
            relative = os.path.relpath(path, pack_out).replace(os.sep, "/")
            if relative == "manifest.json":
                continue
            files.append({"path": relative, "sha256": sha256(path), "size": os.path.getsize(path)})
    files.sort(key=lambda item: item["path"])
    manifest = {
        "formatVersion": 1,
        "id": upstream["id"],
        "version": pack_version(upstream["version"], files),
        "displayName": upstream["display"],
        "license": {"spdx": upstream["spdx"], "file": "LICENSE"},
        "source": {
            "url": f"{upstream['repo']}/tree/{upstream['revision']}",
            "revision": upstream["revision"],
        },
        "schemas": SCHEMAS,
        "files": files,
        "requiredArtifacts": required,
    }
    with open(os.path.join(pack_out, "manifest.json"), "w", encoding="utf-8", newline="\n") as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    total = sum(os.path.getsize(os.path.join(root, name))
                for root, _, names in os.walk(pack_out) for name in names)
    label = "内置 pack" if upstream["archive"] and pack_out.startswith(os.path.dirname(upstream["archive"])) else "pack"
    print(f"{label} 已生成：{pack_out}（{total / 1024 / 1024:.1f} MB）")
    write_archive(pack_out, archive_out)
    print(f"归档：{archive_out}")


SCHEMA_ID_RE = re.compile(r"^\s+schema_id: (\S+)", re.M)
ALGEBRA_RE = re.compile(r"^  algebra:\n(( {4}.*\n)+)", re.M)
RULE_RE = re.compile(r"^ {4}- (.+?)\s*(?:#.*)?$", re.M)


TOP_BLOCK_RE = re.compile(r"^([a-z_]+):\n((?: {2}.*\n)*)", re.M)


def prism_names(text):
    """列出一份 schema 会写出的棱镜名：每个带 dictionary 的 translator 段一个。"""
    names = set()
    for _, body in TOP_BLOCK_RE.findall(text):
        dictionary = re.search(r"^  dictionary: (\S+)", body, re.M)
        if not dictionary:
            continue
        name = dictionary.group(1).strip('"\'')
        if not name or "/" in name:
            continue  # 空词典（stabledb）与子目录词表不产出顶层棱镜
        names.add((re.search(r"^  prism: (\S+)", body, re.M) or dictionary).group(1).strip('"\''))
    return names


def assert_shared_prisms_agree(out):
    """同一份棱镜文件被多个方案共用时，必须由「该词典自己的方案」最后落盘。

    部署器按 default.yaml 的 schema_list 顺序编译，同名棱镜后写的覆盖先写的：
    - associate 复用 pinyin 棱镜，却排在全拼之后，曾把全拼的简拼码整份抹掉；
      共用者只能自带同一套 algebra（现已如此），否则这里拦下。
    - 中文方案里的 table_translator@english 都落到 english.prism.bin，这是有意的
      （英文词表要的是恒等拼写规则），前提是 english 方案排在其后并把最终内容定下来。
    """
    order = []
    with open(os.path.join(out, "default.yaml"), encoding="utf-8") as stream:
        for line in stream:
            match = re.match(r"\s*- schema: (\S+)\s*$", line)
            if match:
                order.append(match.group(1))

    by_prism = {}
    for name in sorted(os.listdir(out)):
        if not name.endswith(".schema.yaml"):
            continue
        text = open(os.path.join(out, name), encoding="utf-8").read()
        schema_id = SCHEMA_ID_RE.search(text)
        schema_id = schema_id.group(1) if schema_id else name
        block = ALGEBRA_RE.search(text)
        rules = tuple(RULE_RE.findall(block.group(1))) if block else ()
        for prism in prism_names(text):
            by_prism.setdefault(prism, []).append((schema_id, rules))

    for prism, users in by_prism.items():
        # 按 default.yaml 的编译顺序排；共用同一份棱镜时后写的覆盖先写的。
        ranked = sorted(users, key=lambda item: order.index(item[0]) if item[0] in order else len(order))
        if len(ranked) < 2:
            continue
        winner = ranked[-1][0]
        clashing = [s for s, rules in ranked[:-1] if rules != ranked[-1][1]]
        if not clashing:
            continue
        owner = winner if winner == prism else next((s for s, _ in ranked if s == prism), None)
        if owner is None or ranked[-1][0] != owner:
            sys.exit(
                f"棱镜 {prism}.prism.bin 被 {', '.join(s for s, _ in ranked)} 共用，"
                f"最后编译的 {winner} 规则与 {', '.join(clashing)} 不同 —— "
                f"要么统一 algebra，要么各用各自的棱镜名"
            )


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--upstream", choices=sorted(UPSTREAMS), default="ice",
                    help="词库源：ice=雾凇（随 APK 分发的默认包），frost=白霜（只独立分发）")
    ap.add_argument("--clone", default=None, help="上游词库克隆目录（默认按 --upstream 取）")
    ap.add_argument("--stroke", default=os.path.join(REPO, "build", "upstream", "rime-stroke"),
                    help="五笔画词库源（rime/rime-stroke）的克隆目录")
    ap.add_argument("--essay", default=os.path.join(REPO, "build", "m3", "essay.txt"),
                    help="预置词汇表 essay.txt")
    ap.add_argument("--freq", default=os.path.join(REPO, "build", "freq", "qingyu-freq.tsv"),
                    help="自有频率覆盖表（tools/gen-dict-freq.py 的产物；缺失则沿用上游权重）")
    ap.add_argument("--no-freq", dest="freq", action="store_const", const=None,
                    help="不注入自有频率，完全沿用上游权重")
    ap.add_argument("--no-essay-damp", dest="essay_damp", action="store_false",
                    help="不给 essay 里的繁体词条降权（默认会降，否则繁体候选会插到简体前面）")
    ap.add_argument("--out", default=None, help="输出的部署输入目录")
    ap.add_argument("--compiled", default=None, help="qingyu_deployer 的编译输出目录")
    ap.add_argument("--pack-out", default=None, help="最终 pack 的临时展开目录")
    ap.add_argument("--archive-out", default=None, help="pack 归档输出路径")
    ap.add_argument("--package", action="store_true", help="封装已编译产物，不重新组装源")
    args = ap.parse_args()

    upstream = UPSTREAMS[args.upstream]
    args.clone = args.clone or upstream["clone"]
    args.out = args.out or os.path.join(upstream["work"], "src")
    args.compiled = args.compiled or os.path.join(upstream["work"], "out")
    args.pack_out = args.pack_out or os.path.join(upstream["work"], "pack")
    if args.archive_out is None:
        args.archive_out = upstream["archive"] or os.path.join(
            REPO, "build", "rime-pack", f"{upstream['id']}-{upstream['version']}.zip"
        )
    if upstream["archive"] is None and "assets" in os.path.normpath(args.archive_out).split(os.sep):
        sys.exit("非默认词库包不得写进 assets/ —— 它必须作为独立 pack 分发（见 LICENSING.md 第六节）")
    hint = clone_hint(upstream)
    require(args.clone, hint)
    require(os.path.join(args.clone, "cn_dicts", "base.dict.yaml"), hint, clone=args.clone)
    require(args.essay, ESSAY_HINT, essay=args.essay)
    require(os.path.join(args.stroke, "stroke.dict.yaml"), STROKE_HINT, stroke=args.stroke)
    verify_inputs(args.clone, args.essay, args.stroke, upstream["revision"])

    if args.package:
        # 编译产物可能来自另一轮的源树，封装前同样要过共用棱镜的卡口
        assert_shared_prisms_agree(args.out)
        package_runtime(args.out, args.compiled, args.clone, args.stroke,
                        args.pack_out, args.archive_out, upstream)
        return 0

    out = args.out
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)

    copied = []

    def copy_tree(src, dst):
        # 逐文件复制而不整树 copytree：文本要折成 LF，行尾不能跟着检出风格走
        target = os.path.join(out, dst)
        for root, _, names in os.walk(os.path.join(src)):
            relative = os.path.relpath(root, os.path.join(src))
            os.makedirs(os.path.join(target, relative), exist_ok=True)
            for name in names:
                copy_normalized(os.path.join(root, name), os.path.join(target, relative, name))
        copied.append(dst + "/")

    def copy_file(src, dst):
        copy_normalized(src, os.path.join(out, dst))
        copied.append(dst)

    # 上游数据（GPL）：分表 + 英文词库
    copy_tree(os.path.join(args.clone, "cn_dicts"), "cn_dicts")
    copy_tree(os.path.join(args.clone, "en_dicts"), "en_dicts")
    # 自有频率：只改组装产物的权重列，上游源文件与 revision 保持原样
    if args.freq and os.path.exists(args.freq):
        total, changed, matched = apply_freq_table(os.path.join(out, "cn_dicts"), args.freq)
        print(f"自有频率：覆盖表 {total:,} 词，命中 {matched:,} 条，改写权重 {changed:,} 条")
        if matched < total * 0.5:
            print(f"警告：覆盖表只有 {100 * matched / max(1, total):.0f}% 的词在本轮上游里命中，"
                  f"确认 --freq 是否由当前 --upstream 生成")
    elif args.freq:
        # 不能静默回退：ADR 0006 的教训是「以为在测新词库，其实跑的是旧包」。
        sys.exit(f"未找到自有频率覆盖表 {args.freq}。先跑 tools/gen-dict-freq.py；"
                 f"确实要用纯上游权重时显式加 --no-freq")
    # 预置词汇表（LGPL）
    copy_file(args.essay, "essay.txt")
    # essay 是跨简繁词表，繁体形式会在同音节里插到简体候选前面（实测「我們」479209
    # 压过降权后的「我们」356572）。只改组装产物，不动上游源文件。
    if args.essay_damp:
        traditional = load_traditional_chars(
            os.path.join(REPO, "imesdk", "src", "main", "assets", "rime", "opencc"))
        changed, total = damp_traditional(os.path.join(out, "essay.txt"), traditional)
        print(f"essay 繁体降权：{changed:,} / {total:,} 条"
              f"（权重 ≥{DAMP_FLOOR}，×{DAMP_FACTOR}）")
        if not traditional:
            print("警告：没有读到 STCharacters.txt，繁体词条未处理")
    # 五笔画词库（LGPL-3.0-or-later，来自另一个上游仓）
    copy_file(os.path.join(args.stroke, "stroke.dict.yaml"), "stroke.dict.yaml")
    # 本工程自写的装配清单
    for name in sorted(os.listdir(MANIFEST_DIR)):
        if name.endswith(".dict.yaml"):
            copy_file(os.path.join(MANIFEST_DIR, name), name)
    # 本工程自有的叠加层分表，与上游分表同目录（import_tables 按 cn_dicts/xxx 引用）
    own_dicts = os.path.join(MANIFEST_DIR, "cn_dicts")
    if os.path.isdir(own_dicts):
        for name in sorted(os.listdir(own_dicts)):
            if name.endswith(".dict.yaml"):
                copy_file(os.path.join(own_dicts, name), os.path.join("cn_dicts", name))
    # 本工程自写的方案与全局配置
    for name in sorted(os.listdir(SCHEMA_DIR)):
        if name.endswith(".schema.yaml") or name == "default.yaml":
            copy_file(os.path.join(SCHEMA_DIR, name), name)

    # 部署器只编译 default.yaml 的 schema_list；associate 需要参与编译但不进用户方案列表。
    with open(os.path.join(out, "default.yaml"), "w", encoding="utf-8", newline="\n") as stream:
        stream.write("menu:\n  page_size: 100\nschema_list:\n")
        stream.writelines(f"  - schema: {schema}\n" for schema in SCHEMAS)

    assert_shared_prisms_agree(out)

    total = sum(os.path.getsize(os.path.join(root, f))
                for root, _, files in os.walk(out) for f in files)
    print(f"部署输入已组装：{out}（{total / 1024 / 1024:.1f} MB）")
    for name in copied:
        print(f"  {name}")

    src_dev = "/data/local/tmp/rime-m3/src"
    out_dev = "/data/local/tmp/rime-m3/out"
    print("""
在设备上编译（词库编译要跑几分钟，放设备上跑是为了复用已打通的 Android 工具链）：
  export MSYS_NO_PATHCONV=1
  adb push {out}/. {src_dev}/
  adb shell "mkdir -p {out_dev} && /data/local/tmp/qingyu_deployer --build {src_dev} {src_dev} {out_dev}"
  adb pull {out_dev} build/m3/out
""".format(out=out, src_dev=src_dev, out_dev=out_dev))
    return 0


if __name__ == "__main__":
    sys.exit(main())
