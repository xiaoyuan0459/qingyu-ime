#!/usr/bin/env python3
"""Validate public orthography examples against the isolated native Android probe.

Only candidate retrieval is covered. This does not test Android editor commits,
mode switching in the app, or arbitrary source-dictionary coverage.
"""
import argparse
import json
from pathlib import Path
import subprocess

CASES = [
    ("women", "我们", "我們"),
    ("qiankun", "乾坤", "乾坤"),
    ("qianlong", "乾隆", "乾隆"),
    ("ganjing", "干净", "乾淨"),
    ("liaowang", "瞭望", "瞭望"),
    ("shenqi", "神祇", "神祇"),
    ("qianxian", "乾县", "乾縣"),
    ("toufa", "头发", "頭髮"),
    ("fazhan", "发展", "發展"),
    ("huanghou", "皇后", "皇后"),
    ("houmian", "后面", "後面"),
    ("qian", "乾", "乾"),
    ("zhi", "徵", "徵"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--device-root", required=True)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    root = args.device_root
    if not root.startswith("/data/local/tmp/") or ".." in root:
        parser.error("probe must live in an isolated /data/local/tmp directory")
    observations = []

    def check(mode, code, expected):
        result = subprocess.run(
            [args.adb, "-d", "shell", root + "/probe", "--query", root + "/runtime",
             root + "/user", mode, code, "1000"], capture_output=True, timeout=30)
        output = result.stdout.decode("utf-8", errors="strict")
        candidates = {parts[1] for line in output.splitlines()
                      if len(parts := line.split("\t")) >= 3 and parts[0].isdigit()}
        success = result.returncode == 0 and set(expected) <= candidates
        observations.append({"mode": mode, "code": code, "expected": expected,
                             "candidateCount": len(candidates), "passed": success})

    for code, hans, hant in CASES:
        check("native_hans", code, [hans])
        check("native_hant", code, [hant])
        check("native_original", code, sorted({hans, hant}))
    check("native_hant", "fa", ["發", "髮"])
    check("native_original", "fa", ["发", "發", "髮"])
    report = {"scope": "native candidate retrieval only; no conversion filters",
              "passed": sum(row["passed"] for row in observations),
              "total": len(observations), "observations": observations}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Native candidate checks: {report['passed']}/{report['total']}")
    if report["passed"] != report["total"]:
        for row in observations:
            if not row["passed"]:
                print(json.dumps(row, ensure_ascii=False))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
