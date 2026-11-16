"""逐行保存设备回放；中断保留原始输出，分块检查点由上层确认。"""

import json
import subprocess
import threading
from pathlib import Path


def run_replay(adb, prefix, root, schema, script, user_dir, workdir: Path):
    local = workdir / f"actions-{schema}.txt"
    local.write_text(script, encoding="utf-8", newline="\n")
    remote = f"{root}/actions-{schema}.txt"
    adb("push", str(local), remote)
    adb("shell", f"chmod 644 {remote}")
    by_case, current, meta = {}, None, None
    with (workdir / f"replay-{schema}.jsonl").open("w", encoding="utf-8", buffering=1) as raw, \
         (workdir / f"stderr-{schema}.log").open("wb") as errors:
        process = subprocess.Popen([*prefix, "shell",
            f"cd {root} && ./probe --replay {root}/runtime {user_dir} {schema} {remote}"],
            stdout=subprocess.PIPE, stderr=errors, text=True, encoding="utf-8", errors="strict")
        timer = threading.Timer(900, process.kill)
        timer.start()
        try:
            for line in process.stdout:
                raw.write(line)
                if not line.startswith("{"):
                    continue
                event = json.loads(line)
                if event.get("type") == "meta":
                    meta = event
                elif event.get("type") == "case":
                    current = event["id"]
                    if current in by_case:
                        raise RuntimeError(f"probe 输出重复案例: {current}")
                    by_case[current] = [event]
                elif event.get("type") == "action" and current:
                    by_case[current].append(event)
            code = process.wait(timeout=30)
            if code:
                raise RuntimeError(f"probe 回放失败 ({code})；原始输出与 stderr 保留于 {workdir}")
        except BaseException:
            process.kill()
            process.wait()
            raise
        finally:
            timer.cancel()
    if not meta or meta.get("protocol_version") != 3 or meta.get("timing_version") != 2:
        raise RuntimeError("probe 协议/计时版本不符；需要重编并 --setup")
    return by_case, meta
