#!/usr/bin/env python3
"""Exercise the production Frida scanner in isolated Linux processes and real ELF mappings."""
import ctypes
from pathlib import Path
import resource
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "processor/src/main/resources/nativebackend/guard/frida_guard.c"


def worker(arguments):
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    guard = ctypes.CDLL(arguments[0])
    guard.lsp_frida_check.argtypes = [ctypes.c_int]
    mode = arguments[1]
    if mode == "late":
        guard.lsp_frida_check(1)
    if mode in ("gum-js-loop", "gmain", "gdbus", "jit-cache"):
        ctypes.CDLL(None).prctl(15, mode.encode(), 0, 0, 0)
    libraries = [ctypes.CDLL(path) for path in arguments[2:]]  # Retain handles through the check.
    if mode == "late":
        time.sleep(1.1)
    guard.lsp_frida_check(0 if mode == "late" else 1)
    return 0


class FridaGuardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="lsp-frida-test-")
        cls.directory = Path(cls.temporary.name)
        cls.guard = cls.directory / "libguard.so"
        subprocess.run(["cc", "-std=c11", "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror",
                        str(SOURCE), "-o", str(cls.guard)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def library(self, name, types):
        source = self.directory / (name + ".c")
        strings = "\\0".join(types) + "\\0"
        source.write_text('__attribute__((used,visibility("default"))) const char markers[] = "' + strings + '";\n')
        library = self.directory / (name + ".so")
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", str(source), "-o", str(library)], check=True)
        return library

    def run_probe(self, mode="clean", libraries=(), abort=False):
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--worker", str(self.guard), mode,
                                 *map(str, libraries)], capture_output=True, timeout=10)
        self.assertEqual(result.returncode, -signal.SIGABRT if abort else 0, result.stderr.decode())

    def test_clean_process_and_detector_do_not_match_themselves(self):
        self.run_probe()

    def test_generic_glib_and_jit_names_are_not_frida_evidence(self):
        for name in ("gmain", "gdbus", "jit-cache"):
            with self.subTest(name=name):
                self.run_probe(name)

    def test_known_thread_aborts(self):
        self.run_probe("gum-js-loop", abort=True)

    def test_known_executable_agent_mapping_aborts(self):
        library = self.library("libfrida-agent-64", ["ordinary code"])
        self.run_probe(libraries=[library], abort=True)

    def test_renamed_quickjs_elf_aborts(self):
        library = self.library("renamed-quick", ["GumInterceptor", "GumScriptScheduler", "GumQuickScript"])
        self.run_probe(libraries=[library], abort=True)

    def test_renamed_v8_elf_aborts(self):
        library = self.library("renamed-v8", ["GumInterceptor", "GumScriptScheduler", "GumV8Script"])
        self.run_probe(libraries=[library], abort=True)

    def test_inspected_reversed_rodata_signature_aborts(self):
        library = self.library("renamed-patched", ["GumInterceptor", "tpircSmuGScheduler", "GumQuickScript"])
        self.run_probe(libraries=[library], abort=True)

    def test_signatures_must_coexist_in_one_elf(self):
        one = self.library("ordinary-one", ["GumInterceptor"])
        two = self.library("ordinary-two", ["GumScriptScheduler", "GumQuickScript"])
        self.run_probe(libraries=[one, two])

    def test_new_image_after_bootstrap_aborts_on_periodic_check(self):
        library = self.library("late-image", ["GumInterceptor", "GumScriptScheduler", "GumQuickScript"])
        self.run_probe("late", [library], abort=True)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        raise SystemExit(worker(sys.argv[2:]))
    unittest.main()
