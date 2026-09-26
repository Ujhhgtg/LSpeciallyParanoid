#!/usr/bin/env python3
"""Audit the native testApp APK against its known fixture sentinels and ELF contract.

This is not a general proof that arbitrary application strings have been removed. It never
runs an APK decompiler, installs an APK, or modifies the supplied APK/NDK.
"""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

# These values belong to testApp/src/main, with expectations in the separate androidTest APK.
SENTINELS = {
    "literal-ascii": "lsp-native-literal-sentinel-b69ce2d6",
    "literal-duplicate": "lsp-duplicate-site-sentinel-733019",
    "literal-long-prefix": "lsp-long-sentinel-c70b32e8:",
    "resource-greeting": "LSP_SENTINEL_GREETING",
    "resource-array": "LSP_SENTINEL_ARRAY",
    "resource-zh-greeting": "原生你好 %1$s",
}
DECODER_ENTRY = re.compile(r"lib/arm64-v8a/liblsp_[0-9a-f]{24}\.so")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(tool, *arguments):
    result = subprocess.run([str(tool), *map(str, arguments)], text=True, capture_output=True)
    require(result.returncode == 0, f"{tool.name} failed ({result.returncode}): {result.stderr.strip()}")
    return result.stdout


def scan_sentinels(apk):
    patterns = [(label, encoding, text.encode(encoding))
                for label, text in SENTINELS.items()
                for encoding in ("utf-8", "utf-16le", "utf-16be")]
    overlap = max(len(pattern) for _, _, pattern in patterns) - 1
    for entry in apk.infolist():
        if entry.is_dir():
            continue
        with apk.open(entry) as stream:
            tail = b""
            while True:
                block = stream.read(1024 * 1024)
                if not block:
                    break
                window = tail + block
                for label, encoding, pattern in patterns:
                    require(pattern not in window,
                            f"Known fixture plaintext remains in {entry.filename}: {label} ({encoding})")
                tail = window[-overlap:]


def verify_elf(library, bin_directory):
    readelf = bin_directory / "llvm-readelf"
    nm = bin_directory / "llvm-nm"
    header = run(readelf, "-h", library)
    require(re.search(r"Class:\s+ELF64", header) is not None, "Decoder is not ELF64")
    require(re.search(r"Machine:\s+AArch64", header) is not None, "Decoder is not AArch64")
    require(re.search(r"Type:\s+DYN", header) is not None, "Decoder is not a shared object")
    segments = run(readelf, "-lW", library)
    loads = [line.split() for line in segments.splitlines() if line.lstrip().startswith("LOAD ")]
    require(loads, "Decoder has no load segments")
    require(all(int(fields[-1], 16) >= 16384 for fields in loads),
            "Decoder load-segment alignment is less than 16 KiB")
    exports = {line.split()[-1].split("@")[0] for line in run(nm, "-D", "--defined-only", library).splitlines() if line.strip()}
    require(exports == {"JNI_OnLoad"}, f"Unexpected decoder exports: {sorted(exports)}")
    dynamic = run(readelf, "-dW", library)
    require(not re.search(r"NEEDED.*(?:libc\+\+|libstdc\+\+)", dynamic),
            "Decoder depends on a shared C++ runtime")
    sections = set(re.findall(r"^\s*\[\s*\d+\]\s+(\S+)", run(readelf, "-SW", library), re.MULTILINE))
    required_sections = {".text", ".dynsym", ".dynstr", ".dynamic", ".shstrtab"}
    require(required_sections <= sections,
            f"Required runtime sections missing: {sorted(required_sections - sections)}")
    require(bool(sections & {".hash", ".gnu.hash"}), "No dynamic symbol hash section")
    debug = {name for name in sections if name.startswith((".debug", ".zdebug")) or name == ".symtab"}
    require(not debug, f"Private debug/symbol sections remain packaged: {sorted(debug)}")
    print(f"ELF verified: {library.name}; AArch64, 16 KiB alignment, JNI_OnLoad-only exports, stripped")


def find_zipalign(ndk):
    build_tools = ndk.parent.parent / "build-tools"
    candidates = []
    if build_tools.is_dir():
        for directory in build_tools.iterdir():
            version = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", directory.name)
            binary = directory / "zipalign"
            # -P 16 is available in modern build tools; avoid silently using an older checker.
            if version and int(version[1]) >= 35 and os.access(binary, os.X_OK):
                candidates.append((tuple(map(int, version.groups())), binary))
    return max(candidates)[1] if candidates else None


def verify(apk_path, ndk):
    require(apk_path.is_file(), f"APK not found: {apk_path}")
    bin_directory = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    for name in ("llvm-readelf", "llvm-nm"):
        require(os.access(bin_directory / name, os.X_OK), f"Missing executable: {bin_directory / name}")
    with zipfile.ZipFile(apk_path) as apk:
        names = apk.namelist()
        require(len(names) == len(set(names)), "APK has duplicate ZIP entries")
        require("resources.arsc" in names and any(re.fullmatch(r"classes\d*\.dex", name) for name in names),
                "Expected an application APK with DEX and a binary resource table")
        scan_sentinels(apk)
        native_entries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        decoder_entries = [name for name in native_entries if DECODER_ENTRY.fullmatch(name)]
        require(len(decoder_entries) == 1, "Expected exactly one arm64 LSP decoder in the fixture APK")
        require(native_entries == decoder_entries,
                f"Unexpected ABI or native library in fixture APK: {sorted(set(native_entries) - set(decoder_entries))}")
        with tempfile.TemporaryDirectory(prefix="lsp-apk-audit-") as temporary:
            library = Path(temporary) / Path(decoder_entries[0]).name
            with apk.open(decoder_entries[0]) as source, library.open("wb") as target:
                while block := source.read(1024 * 1024):
                    target.write(block)
            verify_elf(library, bin_directory)
    zipalign = find_zipalign(ndk)
    if zipalign:
        run(zipalign, "-c", "-P", "16", "4", apk_path)
        print("ZIP alignment verified with 16 KiB native-library alignment check")
    else:
        print("ZIP alignment not checked: build-tools 35+ zipalign unavailable")
    print(f"Fixture audit passed: {len(SENTINELS)} known sentinels absent in UTF-8/UTF-16LE/UTF-16BE across all APK entries")
    print("This does not prove coverage of arbitrary strings or runtime resistance.")


def main():
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("--apk", required=True, type=Path, help="Native testApp application APK, not its instrumentation APK")
    arguments.add_argument("--ndk", required=True, type=Path, help="NDK containing Linux LLVM inspection tools")
    args = arguments.parse_args()
    verify(args.apk.expanduser().resolve(), args.ndk.expanduser().resolve())


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(f"APK audit failed: {error}", file=sys.stderr)
        sys.exit(1)
