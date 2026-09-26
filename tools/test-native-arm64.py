#!/usr/bin/env python3
"""Execute generated ARM64 native code under QEMU; this does not exercise ART or Android loading."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(command, *, cwd, log, env=None, timeout=180):
    with log.open("w") as output:
        result = subprocess.run([str(part) for part in command], cwd=cwd, env=env,
                                stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    text = log.read_text(errors="replace")
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command[0]}\n{text}\nLog: {log}")
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path, help="Installed NDK r29 root")
    parser.add_argument("--omvll-plugin", required=True, type=Path)
    parser.add_argument("--omvll-python", required=True, type=Path, help="Matching Python stdlib directory")
    parser.add_argument("--qemu", default="qemu-aarch64")
    parser.add_argument("--output", type=Path, default=ROOT / "build/native-arm64-smoke")
    args = parser.parse_args()
    clang = args.ndk.resolve() / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
    plugin = args.omvll_plugin.resolve()
    python_path = args.omvll_python.resolve()
    qemu = shutil.which(args.qemu)
    for executable in (clang, plugin):
        if not executable.is_file():
            parser.error(f"Missing file: {executable}")
    if not python_path.is_dir() or not qemu or not shutil.which("javac") or not shutil.which("java"):
        parser.error("Matching Python stdlib, qemu-aarch64, java and javac are required")
    args.output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.output.resolve()))
    classpath_file = work / "classpath.txt"
    # Read the project's actual runtime classpath; do not infer versions from Gradle's cache.
    init = work / "classpath.gradle"
    init.write_text("""
allprojects {
    if (path == ':processor') {
        plugins.withId('org.jetbrains.kotlin.jvm') {
            tasks.register('lspArm64SmokeClasspath') {
                dependsOn tasks.named('classes')
                dependsOn sourceSets.main.runtimeClasspath
                doLast { new File(providers.gradleProperty('lspSmokeClasspathFile').get()).text = sourceSets.main.runtimeClasspath.asPath }
            }
        }
    }
}
""")
    run([ROOT / "gradlew", "--no-configuration-cache", "--console=plain", "-I", init,
         f"-PlspSmokeClasspathFile={classpath_file}", ":processor:lspArm64SmokeClasspath"],
        cwd=ROOT, log=work / "gradle.log", timeout=300)
    classpath = classpath_file.read_text().strip()
    classes = work / "classes"
    classes.mkdir()
    run(["javac", "--release", "17", "-cp", classpath, "-d", classes,
         ROOT / "tools/native-arm64/GenerateFixture.java"], cwd=ROOT, log=work / "javac.log")
    generated = work / "generated"
    print(run(["java", "-cp", str(classes) + os.pathsep + classpath, "GenerateFixture", generated],
              cwd=ROOT, log=work / "fixture.log").strip())
    # Keep a stable harness module basename for applied-pass checks.
    harness = work / "decoder-smoke.c"
    shutil.copyfile(ROOT / "tools/native-arm64/decoder-smoke.c", harness)
    results = {}
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", OMVLL_CONFIG=str(generated / "omvll_config.py"),
               OMVLL_PYTHONPATH=str(python_path), LD_LIBRARY_PATH=str(clang.parent.parent / "lib64"))
    for name, flags in (("baseline", []), ("hardened", [f"-fpass-plugin={plugin}"])):
        directory = work / name
        directory.mkdir()
        executable = directory / "decoder-smoke"
        compile_log = run([clang, "--target=aarch64-linux-android28", "-std=c11", "-static", "-O2", "-g",
                           "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections",
                           f"-I{generated}", harness, generated / "monocypher.c", "-o", executable, *flags],
                          cwd=directory, env=env, log=directory / "compiler.log")
        if name == "hardened":
            for marker in ("flatten_cfg lsp_resolve", "flatten_cfg crypto_aead_read", "arithmetic lsp_resolve"):
                if "LSP_OMVLL_SELECTED " + marker not in compile_log:
                    raise RuntimeError(f"Missing O-MVLL selection: {marker}")
            logs = "\n".join(path.read_text(errors="replace") for path in directory.rglob("*.log"))
            import re
            for transform, module in (("ControlFlowFlattening", "decoder-smoke.c"),
                                      ("ControlFlowFlattening", "monocypher.c"), ("Arithmetic", "decoder-smoke.c")):
                pattern = rf"\[omvll::{transform}\] Changes\s+applied on module[^\n]*{re.escape(module)}"
                if not re.search(pattern, logs):
                    raise RuntimeError(f"O-MVLL did not apply {transform} to {module}")
        result = run([qemu, executable, generated / "expected.bin"], cwd=directory, log=directory / "execution.log")
        if not result.startswith("PASS:"):
            raise RuntimeError(f"Decoder did not confirm successful execution: {result}")
        print(f"{name}: {result.strip()}")
        results[name] = {"result": result.strip(), "executable": str(executable), "bytes": executable.stat().st_size}
    report = {"scope": "QEMU executes real ARM64 code with minimal JNI callbacks; not ART, APK loading, Xposed or Zygisk validation",
              "ndk": str(args.ndk.resolve()), "omvll_plugin": str(plugin), "work": str(work), "results": results}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Report: {args.output / 'report.json'}")
    print(report["scope"])


if __name__ == "__main__":
    main()
