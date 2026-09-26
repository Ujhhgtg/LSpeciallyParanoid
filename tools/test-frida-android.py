#!/usr/bin/env python3
"""Exercise the production scanner against a real Gadget in an owned native Android process."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gadget", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=("before", "late", "control"), required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This fixture runner only operates on an explicitly selected emulator")
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*command, check=True):
        return subprocess.run(["adb", "-s", args.serial, *command], capture_output=True,
                              text=True, check=check, timeout=60)

    abi = adb("shell", "getprop", "ro.product.cpu.abi").stdout.strip()
    triple = {"x86_64": "x86_64-linux-android", "arm64-v8a": "aarch64-linux-android"}.get(abi)
    if triple is None:
        parser.error("A native 64-bit Android emulator is required")
    guard = ROOT / "processor/src/main/resources/nativebackend/guard"
    binary = args.output / "probe"
    clang = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin" / (triple + "28-clang")
    subprocess.run([str(clang), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-I" + str(guard),
                    str(ROOT / "tools/frida/probe.c"), str(guard / "frida_guard.c"),
                    "-Wl,-z,max-page-size=16384", "-ldl", "-o", str(binary)], check=True)
    remote = "/data/local/tmp/lsp-frida-owned-probe"
    script = args.output / "probe.js"
    script.write_text("console.log('LSP_GADGET_STARTED'); setInterval(function () {}, 1000);\n")
    config = args.output / "libfixture.config.so"
    config.write_text(json.dumps({"interaction": {"type": "script", "path": remote + "/probe.js"}}))
    adb("shell", "mkdir", "-p", remote)
    for source, name in [(binary, "probe"), (args.gadget, "libfixture.so"), (script, "probe.js"), (config, "libfixture.config.so")]:
        adb("push", str(source), remote + "/" + name)
    adb("shell", "chmod", "755", remote + "/probe")
    adb("logcat", "-c")
    executed = adb("shell", remote + "/probe", args.mode, remote + "/libfixture.so", check=False)
    time.sleep(1)
    log = adb("logcat", "-d", "-v", "threadtime").stdout
    output = executed.stdout + executed.stderr
    (args.output / "output.txt").write_text(output)
    (args.output / "logcat.txt").write_text(log)
    pid_match = re.search(r"LSP_FRIDA_TEST pid=(\d+)", output)
    pid = pid_match.group(1) if pid_match else None
    own_abort = pid and any(re.search(rf"^\S+\s+\S+\s+{pid}\s+\d+\s", line)
                           and "Fatal signal 6 (SIGABRT)" in line for line in log.splitlines())
    detected = any(message in log for message in ("Frida Gum runtime detected", "Frida agent mapping detected", "Frida thread detected"))
    ready = "GADGET_LOADED" in output
    outcome = "native_abort" if ready and own_abort and detected else (
        "alive" if ready and executed.returncode == 0 and "ALIVE" in output else "inconclusive")
    report = {
        "gadget_sha256": hashlib.sha256(args.gadget.read_bytes()).hexdigest(),
        "guard_sha256": hashlib.sha256((guard / "frida_guard.c").read_bytes()).hexdigest(),
        "serial": args.serial, "abi": abi, "mode": args.mode,
        "sdk": adb("shell", "getprop", "ro.build.version.sdk").stdout.strip(),
        "page_size": adb("shell", "getconf", "PAGESIZE").stdout.strip(),
        "outcome": outcome, "pid": pid, "exit_code": executed.returncode, "output": output,
        "evidence": [line for line in log.splitlines() if "LSP_GADGET_STARTED" in line
                     or "Abort message:" in line or "Fatal signal" in line],
    }
    (args.output / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"outcome": outcome, "report": str(args.output / "result.json")}))
    return 0 if outcome == ("alive" if args.mode == "control" else "native_abort") else 1


if __name__ == "__main__":
    raise SystemExit(main())
