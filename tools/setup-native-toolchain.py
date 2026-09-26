#!/usr/bin/env python3
"""Explicit Linux x86_64 setup for LSP's pinned NDK r29 / O-MVLL 1.9.1 pair.

Ordinary Gradle builds never invoke this script or download a compiler. Existing
archives are verified and never replaced (including incomplete downloads).
"""

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import platform
import posixpath
import shlex
import shutil
import stat
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

NDK_VERSION = "29.0.14206865"
NDK_URL = "https://dl.google.com/android/repository/android-ndk-r29-linux.zip"
# Google's repository2-1.xml remotePackage ndk;29.0.14206865, linux archive,
# independently checked 2026-09-26. Google publishes SHA-1 for this archive.
# https://dl.google.com/android/repository/repository2-1.xml
NDK_SIZE = 783549481
NDK_SHA1 = "87e2bb7e9be5d6a1c6cdf5ec40dd4e0c6d07c30b"
OMVLL_VERSION = "1.9.1"
OMVLL_URL = ("https://github.com/open-obfuscator/o-mvll/releases/download/1.9.1/"
             "omvll_v1-9-1_linux_2026-07-06T09_44_15.tar.gz")
# Digest published on https://github.com/open-obfuscator/o-mvll/releases/tag/1.9.1
OMVLL_SHA256 = "f1f8f88812e173ea44d74372502c4a09600810de3254dbae1f82599bfc339aa0"
# Vendored verbatim from https://raw.githubusercontent.com/open-obfuscator/o-mvll/1.9.1/LICENSE
OMVLL_LICENSE_SHA256 = "a6cba85bc92e0cff7a450b1d873c0eaa2e9fc96bf472df0247a26bec77bf3ff9"
PYTHON_DIRECTORY = "Python-3.10.7"


def verify_archive(path, algorithm, expected, expected_size=None):
    if expected_size is not None and path.stat().st_size != expected_size:
        raise ValueError(f"Wrong archive size: {path}. The file may still be downloading; it was not changed.")
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != expected:
        raise ValueError(f"{algorithm} mismatch: {path}. Existing files are never replaced automatically.")


def obtain_archive(path, url, algorithm, expected, expected_size=None, offline=False):
    if path.exists():
        verify_archive(path, algorithm, expected, expected_size)
        print(f"Verified cached archive: {path}")
        return path
    if offline:
        raise ValueError(f"Archive unavailable in offline mode: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {url}", flush=True)
    # A separate temporary file leaves the cache's final filename absent until verified.
    descriptor, temporary_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".partial", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "LSpeciallyParanoid-toolchain-setup/1"})
        with os.fdopen(descriptor, "wb") as output, urllib.request.urlopen(request, timeout=60) as source:
            shutil.copyfileobj(source, output, length=1024 * 1024)
        verify_archive(temporary, algorithm, expected, expected_size)
        if path.exists():
            raise ValueError(f"Archive destination appeared during download; preserving it: {path}")
        temporary.rename(path)
        return path
    finally:
        temporary.unlink(missing_ok=True)


def member_path(root, name):
    """Return a confined destination; reject ambiguous archive names before writing."""
    path = PurePosixPath(name)
    if "\\" in name or path.is_absolute() or ".." in path.parts or (path.parts and ":" in path.parts[0]):
        raise ValueError(f"Unsafe archive path: {name}")
    if not path.parts:
        return root
    return root.joinpath(*path.parts)


def write_file(target, source, mode):
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("xb") as output:
        shutil.copyfileobj(source, output, length=1024 * 1024)
    target.chmod(0o755 if mode & 0o111 else 0o644)


def create_links(root, links):
    """NDK ZIPs may contain compiler symlinks; only links within the extracted tree are accepted."""
    paths = {path for path, _ in links}
    for path, target in links:
        if any(parent in paths for parent in path.parents):
            raise ValueError(f"Archive uses a symbolic link as a directory: {path}")
        if "\\" in target or PurePosixPath(target).is_absolute():
            raise ValueError(f"Unsafe archive link: {path} -> {target}")
        relative = posixpath.normpath(str(path.parent.relative_to(root) / target))
        member_path(root, relative)  # Reject a target escaping the extraction root.
        path.parent.mkdir(parents=True, exist_ok=True)
        path.symlink_to(target)
    for path, _ in links:
        try:
            path.resolve(strict=True).relative_to(root.resolve())
        except (OSError, RuntimeError, ValueError) as error:
            raise ValueError(f"Broken, cyclic or escaping archive link: {path}") from error


def extract_zip(archive, root):
    links = []
    with zipfile.ZipFile(archive) as source:
        # Validate all paths before creating any files. Symlinks are created only after regular files.
        entries = [(entry, member_path(root, entry.filename)) for entry in source.infolist()]
        for entry, target in entries:
            mode = entry.external_attr >> 16
            if target == root or entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            elif stat.S_ISLNK(mode):
                links.append((target, source.read(entry).decode("utf-8")))
            elif stat.S_IFMT(mode) not in (0, stat.S_IFREG):
                raise ValueError(f"Unsupported ZIP entry type: {entry.filename}")
            else:
                with source.open(entry) as contents:
                    write_file(target, contents, mode)
    create_links(root, links)


def extract_tar(archive, root):
    with tarfile.open(archive, "r:gz") as source:
        entries = [(entry, member_path(root, entry.name)) for entry in source.getmembers()]
        # The pinned O-MVLL archive needs no links, devices, FIFOs or ownership restoration.
        if any(not (entry.isdir() or entry.isfile()) for entry, _ in entries):
            raise ValueError("Unsupported TAR entry: only regular files and directories are allowed")
        for entry, target in entries:
            if target == root or entry.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                with source.extractfile(entry) as contents:
                    write_file(target, contents, entry.mode)


def check_ndk(directory):
    properties = directory / "source.properties"
    if not properties.is_file() or f"Pkg.Revision = {NDK_VERSION}" not in properties.read_text():
        raise ValueError(f"Existing NDK has the wrong revision or is incomplete: {directory}")
    for tool in ("clang", "llvm-strip", "llvm-readelf", "llvm-nm"):
        path = directory / "toolchains/llvm/prebuilt/linux-x86_64/bin" / tool
        if not path.is_file() or not os.access(path, os.X_OK):
            raise ValueError(f"Required NDK tool unavailable: {path}")


def check_omvll(directory):
    for relative in ("omvll-ndk.so", f"{PYTHON_DIRECTORY}/Lib/os.py", f"{PYTHON_DIRECTORY}/LICENSE"):
        if not (directory / relative).is_file():
            raise ValueError(f"Incomplete O-MVLL installation: {directory / relative}")


def install_ndk(archive, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".lsp-ndk-", dir=destination.parent) as temporary:
        root = Path(temporary)
        extract_zip(archive, root)
        extracted = root / "android-ndk-r29"
        check_ndk(extracted)
        if destination.exists():
            raise ValueError(f"Refusing to replace existing NDK: {destination}")
        extracted.rename(destination)


def install_omvll(archive, destination):
    license_file = Path(__file__).resolve().parent / "licenses/OMVLL-1.9.1-LICENSE.txt"
    verify_archive(license_file, "sha256", OMVLL_LICENSE_SHA256)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".lsp-omvll-", dir=destination.parent) as temporary:
        extracted = Path(temporary) / "payload"
        extracted.mkdir()
        extract_tar(archive, extracted)
        check_omvll(extracted)
        shutil.copyfile(license_file, extracted / "OMVLL-LICENSE.txt")
        (extracted / "archive.sha256").write_text(OMVLL_SHA256 + "\n")
        if destination.exists():
            raise ValueError(f"Refusing to replace existing O-MVLL installation: {destination}")
        extracted.rename(destination)


def parser():
    default_sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "~/Android/Sdk"
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("--sdk", type=Path, default=Path(default_sdk), help="Android SDK directory (NDK installs under ndk/29.0.14206865)")
    arguments.add_argument("--cache", type=Path, default=Path("~/.cache/lspeciallyparanoid/toolchains"), help="Archive cache directory")
    arguments.add_argument("--omvll-dir", type=Path, default=Path("~/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1"), help="Private O-MVLL installation directory")
    arguments.add_argument("--ndk-archive", type=Path, help="Existing NDK ZIP; checked against Google's pinned checksum")
    arguments.add_argument("--omvll-archive", type=Path, help="Existing O-MVLL TAR.GZ; checked against its release SHA-256")
    arguments.add_argument("--offline", action="store_true", help="Use installed tools or cached archives only; never download")
    return arguments


def main(argv=None):
    arguments = parser().parse_args(argv)
    if platform.system() != "Linux" or platform.machine().lower() not in ("x86_64", "amd64"):
        raise ValueError("This pinned toolchain supports Linux x86_64 build hosts only")
    sdk = arguments.sdk.expanduser().resolve()
    cache = arguments.cache.expanduser().resolve()
    ndk = sdk / "ndk" / NDK_VERSION
    omvll = arguments.omvll_dir.expanduser().resolve()
    if ndk.exists():
        check_ndk(ndk)
        print(f"Using existing NDK revision: {ndk}")
    else:
        archive = (arguments.ndk_archive or cache / "ndk-r29/android-ndk-r29-linux.zip").expanduser().resolve()
        install_ndk(obtain_archive(archive, NDK_URL, "sha1", NDK_SHA1, NDK_SIZE, arguments.offline), ndk)
    if omvll.exists():
        check_omvll(omvll)
        receipt = omvll / "archive.sha256"
        if not receipt.is_file() or receipt.read_text().strip() != OMVLL_SHA256:
            raise ValueError(f"Existing O-MVLL directory was not installed by this script; choose an empty --omvll-dir: {omvll}")
        print(f"Using pinned O-MVLL installation: {omvll}")
    else:
        archive = (arguments.omvll_archive or cache / "omvll-1.9.1/release.tar.gz").expanduser().resolve()
        install_omvll(obtain_archive(archive, OMVLL_URL, "sha256", OMVLL_SHA256, offline=arguments.offline), omvll)
    plugin = omvll / "omvll-ndk.so"
    python = omvll / PYTHON_DIRECTORY / "Lib"
    print(f"\nNDK licenses/notices retained under: {ndk}")
    print(f"O-MVLL / Python licenses retained under: {omvll}")
    print("\nUse these Gradle project arguments (the plugin supplies O-MVLL's runtime environment):")
    print(f"  -PlspNdkVersion={NDK_VERSION} " + shlex.quote(f"-PlspOmvllPlugin={plugin}") + " " + shlex.quote(f"-PlspOmvllPythonPath={python}"))
    print("\nOptional shell variables for passing those arguments:")
    print("export LSP_OMVLL_PLUGIN=" + shlex.quote(str(plugin)))
    print("export LSP_OMVLL_PYTHONPATH=" + shlex.quote(str(python)))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, tarfile.TarError, zipfile.BadZipFile) as error:
        print(f"setup failed: {error}", file=sys.stderr)
        sys.exit(1)
