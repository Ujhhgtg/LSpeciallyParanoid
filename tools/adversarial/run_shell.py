#!/usr/bin/env python3
"""Run the owned replay shell on an explicitly selected Android emulator."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expect", choices=("recover", "abort"), required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This adversarial fixture runner intentionally accepts emulator serials only")
    args.output.mkdir(parents=True, exist_ok=True)
    package = "dev.lsp.adversarial.shell"

    def adb(*arguments, check=True):
        return subprocess.run(["adb", "-s", args.serial, *arguments], check=check, capture_output=True, text=True, errors="replace", timeout=30)

    # This fixed application ID belongs only to our disposable test shell.
    adb("uninstall", package, check=False)
    installed = adb("install", str(args.apk.resolve()))
    (args.output / "install.log").write_text(installed.stdout + installed.stderr)
    adb("logcat", "-c")
    launch = adb("shell", "am", "start", "-W", "-n", package + "/.MainActivity")
    (args.output / "launch.log").write_text(launch.stdout + launch.stderr)
    log = ""
    shell_pid = None
    fatal_lines = []
    for _ in range(20):
        log = adb("logcat", "-d", "-v", "threadtime").stdout
        attempt = re.search(r"^\S+\s+\S+\s+(\d+)\s+\d+\s+I\s+LSP_ADVERSARIAL: ATTEMPT", log, re.MULTILINE)
        if attempt:
            shell_pid = attempt.group(1)
            fatal_lines = [line for line in log.splitlines() if re.search(rf"^\S+\s+\S+\s+{shell_pid}\s+\d+\s", line) and "Fatal signal 6 (SIGABRT)" in line]
        if "RECOVERED " in log or "JAVA_ERROR" in log or fatal_lines:
            # Give debuggerd time to write the abort message and backtrace.
            time.sleep(1)
            log = adb("logcat", "-d", "-v", "threadtime").stdout
            break
        time.sleep(0.5)
    (args.output / "logcat.txt").write_text(log)
    data = adb("exec-out", "run-as", package, "cat", "files/result.json", check=False)
    result = {
        "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "serial": args.serial,
        "sdk": adb("shell", "getprop", "ro.build.version.sdk").stdout.strip(),
        "abis": adb("shell", "getprop", "ro.product.cpu.abilist").stdout.strip(),
        "page_size": adb("shell", "getconf", "PAGESIZE").stdout.strip(),
        "outcome": "inconclusive", "recovered": [], "shell_pid": shell_pid,
    }
    if data.returncode == 0 and data.stdout.lstrip().startswith("{"):
        result.update(json.loads(data.stdout))
    elif fatal_lines:
        result["outcome"] = "native_abort"
        result["abort_evidence"] = [line for line in log.splitlines() if "Abort message:" in line or "Fatal signal 6 (SIGABRT)" in line]
    elif "JAVA_ERROR" in log:
        result["outcome"] = "java_error"
    (args.output / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=True) + "\n")
    print(json.dumps({"outcome": result["outcome"], "recovered_count": len(result["recovered"]), "report": str(args.output / "result.json")}))
    return 0 if result["outcome"] == {"recover": "plaintext_recovered", "abort": "native_abort"}[args.expect] else 1


if __name__ == "__main__":
    raise SystemExit(main())
