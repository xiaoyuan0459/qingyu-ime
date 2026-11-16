"""Explicit admission for comparing two bound engine binaries against identical data."""
import json
from pathlib import Path

from ime_eval_identity import sha256_of


def verify_engine_artifacts(path: Path, baseline: dict, candidate: dict) -> dict:
    receipt = json.loads(path.read_text(encoding="utf-8"))
    if receipt.get("format_version") != 1 or not str(receipt.get("reason", "")).strip():
        raise ValueError("引擎变更收据缺版本或明确原因")
    for name, report in (("baseline", baseline), ("candidate", candidate)):
        item = receipt.get(name, {})
        probe = Path(item.get("probe", ""))
        if not probe.is_absolute():
            probe = path.parent / probe
        digest = item.get("sha256")
        if (not probe.is_file() or not digest or digest != report.get("probe_sha256") or
                sha256_of(probe) != digest):
            raise ValueError(f"引擎变更收据的 {name} 实际探针字节不符")
    old_pack = baseline.get("identity", {}).get("manifest_sha256")
    new_pack = candidate.get("identity", {}).get("manifest_sha256")
    if not old_pack or old_pack != new_pack:
        raise ValueError("引擎代码比较要求相同基础包 manifest，不能同时改变词库")
    return {"receipt_sha256": sha256_of(path), "reason": receipt["reason"],
            "baseline_probe_sha256": receipt["baseline"]["sha256"],
            "candidate_probe_sha256": receipt["candidate"]["sha256"]}
