"""Focused extraction/cache regression tests; no network or toolchain installation."""
import hashlib
import importlib.util
import io
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("setup_native_toolchain", Path(__file__).with_name("setup-native-toolchain.py"))
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class SetupSafetyTest(unittest.TestCase):
    def test_zip_rejects_traversal_before_writing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "unsafe.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("innocent", b"data")
                output.writestr("../outside", b"escape")
            destination = root / "extracted"
            with self.assertRaises(ValueError):
                setup.extract_zip(archive, destination)
            self.assertFalse(destination.exists())
            self.assertFalse((root / "outside").exists())

    def test_zip_accepts_confined_compiler_link_and_preserves_executable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "valid.zip"
            with zipfile.ZipFile(archive, "w") as output:
                binary = zipfile.ZipInfo("bin/clang-21")
                binary.external_attr = (stat.S_IFREG | 0o755) << 16
                output.writestr(binary, b"compiler")
                link = zipfile.ZipInfo("bin/clang")
                link.external_attr = (stat.S_IFLNK | 0o777) << 16
                output.writestr(link, "clang-21")
            destination = root / "extracted"
            setup.extract_zip(archive, destination)
            self.assertEqual((destination / "bin/clang").read_bytes(), b"compiler")
            self.assertTrue((destination / "bin/clang").stat().st_mode & stat.S_IXUSR)

    def test_zip_rejects_escape_link(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "unsafe.zip"
            with zipfile.ZipFile(archive, "w") as output:
                link = zipfile.ZipInfo("bin/clang")
                link.external_attr = (stat.S_IFLNK | 0o777) << 16
                output.writestr(link, "../../../outside")
            with self.assertRaises(ValueError):
                setup.extract_zip(archive, root / "extracted")
            self.assertFalse((root / "extracted/bin/clang").is_symlink())

    def test_tar_rejects_paths_and_links(self):
        for name, link in (("../outside", False), ("/absolute", False), ("link", True)):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                archive = root / "unsafe.tar.gz"
                with tarfile.open(archive, "w:gz") as output:
                    entry = tarfile.TarInfo(name)
                    if link:
                        entry.type = tarfile.SYMTYPE
                        entry.linkname = "/outside"
                    else:
                        entry.size = 1
                    output.addfile(entry, None if link else io.BytesIO(b"x"))
                with self.assertRaises(ValueError):
                    setup.extract_tar(archive, root / "extracted")
                self.assertFalse((root / "extracted").exists())

    def test_bad_cached_archive_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "downloading.zip"
            archive.write_bytes(b"partial")
            with self.assertRaises(ValueError):
                setup.obtain_archive(archive, "https://example.invalid/never-requested", "sha256",
                                     hashlib.sha256(b"complete").hexdigest())
            self.assertEqual(archive.read_bytes(), b"partial")

    def test_offline_missing_archive_does_not_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "missing.tar.gz"
            with self.assertRaisesRegex(ValueError, "offline"):
                setup.obtain_archive(archive, "https://example.invalid/never-requested", "sha256", "00", offline=True)
            self.assertFalse(archive.exists())

    def test_parser_exposes_explicit_paths(self):
        arguments = setup.parser().parse_args(["--sdk", "/sdk", "--cache", "/cache", "--offline"])
        self.assertEqual(arguments.sdk, Path("/sdk"))
        self.assertEqual(arguments.cache, Path("/cache"))
        self.assertTrue(arguments.offline)


if __name__ == "__main__":
    unittest.main()
