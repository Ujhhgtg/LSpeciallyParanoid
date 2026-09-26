import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("verify_protected_apk", Path(__file__).with_name("verify-protected-apk.py"))
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class SentinelScanTest(unittest.TestCase):
    def scan(self, payload):
        with tempfile.TemporaryDirectory() as temporary:
            file = Path(temporary) / "fixture.apk"
            with zipfile.ZipFile(file, "w", compression=zipfile.ZIP_DEFLATED) as apk:
                apk.writestr("classes.dex", payload)
            with zipfile.ZipFile(file) as apk:
                audit.scan_sentinels(apk)

    def test_detects_all_declared_encodings(self):
        for label, sentinel in audit.SENTINELS.items():
            for encoding in ("utf-8", "utf-16le", "utf-16be"):
                with self.subTest(label=label, encoding=encoding):
                    with self.assertRaisesRegex(ValueError, label):
                        self.scan(b"header" + sentinel.encode(encoding) + b"trailer")

    def test_detects_sentinel_crossing_stream_chunk_boundary(self):
        sentinel = next(iter(audit.SENTINELS.values())).encode("utf-8")
        with self.assertRaisesRegex(ValueError, "literal-ascii"):
            self.scan(b"x" * (1024 * 1024 - 7) + sentinel)

    def test_accepts_other_values_without_claiming_general_coverage(self):
        self.scan(b"Intentionally public strings and unrelated values")


if __name__ == "__main__":
    unittest.main()
