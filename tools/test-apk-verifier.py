#!/usr/bin/env python3
"""Adversarial tests for the native APK v2 verifier (host cc, zlib, cryptography).

Run: python3 tools/test-apk-verifier.py [--apk path/to/real-signed.apk]
Fixtures use cryptography as an independent signer, not the verifier's BearSSL.
No device, Java, native package-manager API, or private signing keys are needed.
"""
from __future__ import annotations

import argparse
import ctypes
import datetime
import hashlib
import io
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import warnings
import zipfile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[1]
GUARD = ROOT / "processor/src/main/resources/nativebackend/guard"
CHUNK = 1024 * 1024
REAL_APK: Path | None = None


def u32(n: int) -> bytes:
    return struct.pack("<I", n)


def lp(b: bytes) -> bytes:
    return u32(len(b)) + b


def unlp(b: bytes) -> tuple[bytes, bytes]:
    n = struct.unpack_from("<I", b)[0]
    return b[4:4 + n], b[4 + n:]


def cert_for(key) -> bytes:
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "LSParanoid guard test")])
    now = datetime.datetime(2025, 1, 1, tzinfo=datetime.timezone.utc)
    return (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
            .public_key(key.public_key()).serial_number(1).not_valid_before(now)
            .not_valid_after(now + datetime.timedelta(days=3650))
            .sign(key, hashes.SHA256()).public_bytes(serialization.Encoding.DER))


def unsigned_zip(*, duplicate=False, comment=b"") -> bytes:
    out = io.BytesIO()
    with warnings.catch_warnings(), zipfile.ZipFile(out, "w") as z:
        warnings.simplefilter("ignore", UserWarning)
        # Crosses two one-MiB content boundaries and exercises a final short chunk.
        z.writestr("classes.dex", bytes(range(256)) * 9000, compress_type=zipfile.ZIP_STORED)
        z.writestr("lib/arm64-v8a/libguard.so", b"ELF fixture\0" * 200, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("empty", b"", compress_type=zipfile.ZIP_DEFLATED)
        if duplicate:
            z.writestr("lib/arm64-v8a/libguard.so", b"second", compress_type=zipfile.ZIP_STORED)
        z.comment = comment
    return out.getvalue()


def zip_parts(data: bytes) -> tuple[bytes, bytes, bytes]:
    # Test helper follows ZIP's exact end-of-file comment length requirement.
    for p in range(len(data) - 22, max(-1, len(data) - 65558), -1):
        if data[p:p + 4] == b"PK\x05\x06" and p + 22 + struct.unpack_from("<H", data, p + 20)[0] == len(data):
            cd = struct.unpack_from("<I", data, p + 16)[0]
            return data[:cd], data[cd:p], data[p:]
    raise ValueError("no EOCD")


def digest_sections(parts: tuple[bytes, bytes, bytes], sha512: bool) -> bytes:
    make = hashlib.sha512 if sha512 else hashlib.sha256
    chunks = []
    for part in parts:
        for at in range(0, len(part), CHUNK):
            chunk = part[at:at + CHUNK]
            chunks.append(make(b"\xa5" + u32(len(chunk)) + chunk).digest())
    return make(b"\x5a" + u32(len(chunks)) + b"".join(chunks)).digest()


def signing_block(pairs: list[tuple[int, bytes]]) -> bytes:
    encoded = b"".join(struct.pack("<Q", 4 + len(v)) + u32(k) + v for k, v in pairs)
    size = struct.pack("<Q", len(encoded) + 24)
    return size + encoded + size + b"APK Sig Block 42"


def sign_apk(data: bytes, key, cert: bytes, algorithms=(0x0103,), *,
             public_key=None, signature_key=None, extra_data=b"", bad_digest=False,
             digest_algorithms=None, signer_count=1, duplicate_block=False,
             salt_delta=0) -> bytes:
    parts = zip_parts(data)
    digest_algs = algorithms if digest_algorithms is None else digest_algorithms
    digests = []
    for alg in digest_algs:
        d = digest_sections(parts, alg in (0x0102, 0x0104, 0x0202))
        if bad_digest:
            d = bytes([d[0] ^ 1]) + d[1:]
        digests.append(lp(u32(alg) + lp(d)))
    signed = lp(b"".join(digests)) + lp(lp(cert)) + lp(b"") + extra_data
    signatures = []
    for alg in algorithms:
        h = hashes.SHA512() if alg in (0x0102, 0x0104, 0x0202) else hashes.SHA256()
        signer = signature_key or key
        if alg in (0x0201, 0x0202):
            sig = signer.sign(signed, ec.ECDSA(h))
        else:
            pad = (padding.PSS(mgf=padding.MGF1(h), salt_length=h.digest_size + salt_delta)
                   if alg in (0x0101, 0x0102) else padding.PKCS1v15())
            sig = signer.sign(signed, pad, h)
        signatures.append(lp(u32(alg) + lp(sig)))
    pub = (public_key or key).public_key().public_bytes(serialization.Encoding.DER,
                                                      serialization.PublicFormat.SubjectPublicKeyInfo)
    signer = lp(signed) + lp(b"".join(signatures)) + lp(pub)
    v2 = lp(lp(signer) * signer_count)
    pairs = [(0x7109871a, v2)] * (2 if duplicate_block else 1)
    block = signing_block(pairs)
    eocd = bytearray(parts[2])
    struct.pack_into("<I", eocd, 16, len(parts[0]) + len(block))
    return parts[0] + block + parts[1] + eocd


def leaf_from_apk(data: bytes) -> bytes:
    prefix, _, _ = zip_parts(data)
    size = struct.unpack_from("<Q", prefix, len(prefix) - 24)[0]
    pairs = prefix[len(prefix) - size:len(prefix) - 24]
    while pairs:
        length, id_ = struct.unpack_from("<QI", pairs)
        if id_ == 0x7109871a:
            signers, _ = unlp(pairs[12:8 + length])
            signer, _ = unlp(signers)
            signed, _ = unlp(signer)
            _, signed = unlp(signed)
            certs, _ = unlp(signed)
            leaf, _ = unlp(certs)
            return leaf
        pairs = pairs[8 + length:]
    raise ValueError("no v2 signer")


class ApkVerifierTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.TemporaryDirectory(prefix="lspar-apk-verifier-")
        cls.path = Path(cls.work.name)
        so = cls.path / "verify.so"
        command = [os.environ.get("CC", "cc"), "-std=c11", "-O2", "-fPIC", "-shared", "-Wall", "-Wextra", "-Werror",
                   str(GUARD / "apk_verify.c"), *map(str, sorted((GUARD / "vendor/bearssl").glob("*.c"))),
                   "-lz", "-Wl,--no-undefined", "-o", str(so)]
        subprocess.run(command, check=True)
        cls.lib = ctypes.CDLL(str(so))
        cls.lib.lsp_apk_verify.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_size_t]
        cls.lib.lsp_apk_verify.restype = ctypes.c_int
        cls.lib.lsp_apk_read_entry.argtypes = [ctypes.c_int, ctypes.c_char_p,
                                              ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_size_t)]
        cls.lib.lsp_apk_read_entry.restype = ctypes.c_int
        cls.libc = ctypes.CDLL(None)
        cls.libc.free.argtypes = [ctypes.c_void_p]
        cls.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.cert = cert_for(cls.key)
        cls.pin = hashlib.sha256(cls.cert).digest()
        cls.zip = unsigned_zip()
        cls.good = sign_apk(cls.zip, cls.key, cls.cert)

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def verify(self, data: bytes, pins: bytes | None = None) -> int:
        p = self.path / "fixture.apk"
        p.write_bytes(data)
        with p.open("rb") as f:
            pin = self.pin if pins is None else pins
            result = self.lib.lsp_apk_verify(f.fileno(), pin, len(pin) // 32)
            self.assertEqual(f.tell(), 0, "native verification must not consume caller fd offset")
            return result

    def entry(self, data: bytes, name: str) -> bytes | None:
        p = self.path / "entry.apk"
        p.write_bytes(data)
        with p.open("rb") as f:
            out, size = ctypes.c_void_p(), ctypes.c_size_t()
            result = self.lib.lsp_apk_read_entry(f.fileno(), name.encode(), ctypes.byref(out), ctypes.byref(size))
            if not result:
                self.assertFalse(out.value)
                self.assertEqual(size.value, 0)
                return None
            try:
                return ctypes.string_at(out, size.value)
            finally:
                self.libc.free(out)

    def test_rsa_all_algorithms(self):
        for alg in (0x0101, 0x0102, 0x0103, 0x0104):
            with self.subTest(algorithm=hex(alg)):
                self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, (alg,))), 1)

    def test_ecdsa_all_curves_and_hashes(self):
        for curve in (ec.SECP256R1(), ec.SECP384R1(), ec.SECP521R1()):
            key = ec.generate_private_key(curve)
            cert = cert_for(key)
            for alg in (0x0201, 0x0202):
                with self.subTest(curve=curve.name, algorithm=hex(alg)):
                    self.assertEqual(self.verify(sign_apk(self.zip, key, cert, (alg,)), hashlib.sha256(cert).digest()), 1)

    def test_rsa_4096(self):
        key = rsa.generate_private_key(public_exponent=65537, key_size=4096)
        cert = cert_for(key)
        self.assertEqual(self.verify(sign_apk(self.zip, key, cert, (0x0104,)), hashlib.sha256(cert).digest()), 1)

    def test_every_signature_and_digest_in_multi_algorithm_signer(self):
        self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, (0x0103, 0x0104))), 1)

    def test_official_apksig_trailing_empty_field(self):
        self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, extra_data=lp(b""))), 1)
        self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, extra_data=lp(b"evil"))), 0)

    def test_wrong_or_missing_pin(self):
        self.assertEqual(self.verify(self.good, bytes(32)), 0)
        self.assertEqual(self.verify(self.good, b""), 0)
        self.assertEqual(self.verify(self.good, bytes(32) + self.pin), 1)

    def test_resigned_apk_rejected(self):
        cert = cert_for(self.other)
        resigned = sign_apk(self.zip, self.other, cert)
        self.assertEqual(self.verify(resigned), 0)
        self.assertEqual(self.verify(resigned, hashlib.sha256(cert).digest()), 1)

    def test_copied_pinned_certificate_with_attacker_key_and_valid_signature(self):
        forged = sign_apk(self.zip, self.other, self.cert)
        self.assertEqual(self.verify(forged), 0)

    def test_correct_certificate_and_public_key_but_wrong_signature(self):
        forged = sign_apk(self.zip, self.key, self.cert, signature_key=self.other)
        self.assertEqual(self.verify(forged), 0)

    def test_signed_wrong_content_digest(self):
        self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, bad_digest=True)), 0)

    def test_mutated_content_with_original_signer_block(self):
        for at in (100, CHUNK + 100, len(self.good) - 25):
            tampered = bytearray(self.good)
            tampered[at] ^= 1
            with self.subTest(offset=at):
                self.assertEqual(self.verify(tampered), 0)

    def test_digest_signature_algorithm_list_mismatch(self):
        self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, digest_algorithms=(0x0101,))), 0)

    def test_duplicate_algorithm_records(self):
        self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, (0x0103, 0x0103))), 0)

    def test_duplicate_v2_block(self):
        self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, duplicate_block=True)), 0)

    def test_multiple_or_absent_signer(self):
        for count in (0, 2):
            self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, signer_count=count)), 0)

    def test_pss_salt_length_is_exact(self):
        self.assertEqual(self.verify(sign_apk(self.zip, self.key, self.cert, (0x0101,), salt_delta=-1)), 0)

    def test_truncated_and_appended_data(self):
        for data in (b"", self.good[:100], self.good[:-1], self.good + b"trailing data", self.zip):
            self.assertEqual(self.verify(data), 0)

    def test_bad_signing_block_lengths(self):
        prefix, _, _ = zip_parts(self.good)
        footer_at = len(prefix) - 24
        block_at = len(prefix) - struct.unpack_from("<Q", prefix, footer_at)[0] - 8
        for at, value in ((block_at, 0), (footer_at, 2**64 - 1), (block_at + 8, 2**64 - 1)):
            data = bytearray(self.good)
            struct.pack_into("<Q", data, at, value)
            self.assertEqual(self.verify(data), 0)

    def test_zip_comments(self):
        for comment in (b"PK\x05\x06 fake EOCD", b"x" * 65535):
            self.assertEqual(self.verify(sign_apk(unsigned_zip(comment=comment), self.key, self.cert)), 1)

    def test_zip_stored_and_deflated_entries(self):
        self.assertEqual(self.entry(self.good, "classes.dex"), bytes(range(256)) * 9000)
        self.assertEqual(self.entry(self.good, "lib/arm64-v8a/libguard.so"), b"ELF fixture\0" * 200)
        self.assertEqual(self.entry(self.good, "empty"), b"")
        self.assertIsNone(self.entry(self.good, "absent"))

    def test_duplicate_zip_entry_rejected(self):
        signed = sign_apk(unsigned_zip(duplicate=True), self.key, self.cert)
        self.assertEqual(self.verify(signed), 1)
        self.assertIsNone(self.entry(signed, "lib/arm64-v8a/libguard.so"))

    def test_zip_local_header_and_crc_rejected(self):
        for at in (8, 30, 100):
            bad = bytearray(self.good)
            bad[at] ^= 1
            self.assertIsNone(self.entry(bad, "classes.dex"))

    def test_real_apksigner_fixture(self):
        if REAL_APK is None:
            self.skipTest("pass --apk to additionally check an Android-build signed APK")
        data = REAL_APK.read_bytes()
        self.assertEqual(self.verify(data, hashlib.sha256(leaf_from_apk(data)).digest()), 1)
        self.assertIsNotNone(self.entry(data, "classes.dex"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--apk", type=Path)
    args, remaining = parser.parse_known_args()
    REAL_APK = args.apk
    unittest.main(argv=[__file__, *remaining], verbosity=2)
