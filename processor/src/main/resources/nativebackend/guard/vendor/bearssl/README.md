# BearSSL verification subset

Unmodified source from the official repository <https://www.bearssl.org/git/BearSSL>,
commit **7bea48e5e850ab4cafbe68d3765cdaba13a86d6f** (2026-04-06).
The upstream MIT license is in `LICENSE.txt`; original copyright notices are
retained. `SOURCES.txt` records original source paths and `SHA256SUMS` pins every
vendored C/header/license file. Public headers and `inner.h`/`config.h` are copied
without changes. C sources are the transitive link dependencies of the verifier,
flattened into this directory; no TLS, private key operation, random generator,
networking, or encryption implementation is included.

Used primitives: SHA-256, SHA-512, RSA i31 PKCS#1 v1.5/PSS verification, ECDSA i31
verification with NIST P-256/P-384/P-521, and the X.509 public-key decoder.
Upstream's RSA limit is 4096 bits; larger RSA keys fail closed. Certificate trust
is the generated leaf certificate SHA-256 pin, not CA validation or validity
dates. APK signing certificates are identity containers, not HTTPS certificates.

Compile all C files in this directory as independent translation units. The
surrounding guard links against Android's public `libz` for ZIP DEFLATE. Exclude
these vendor functions from O-MVLL transformations, just as the existing
Monocypher primitives are excluded; obfuscate the guard policy and checks.

Reference format: <https://source.android.com/docs/security/features/apksigning/v2>.
The optional final empty field emitted by current AOSP `V2SchemeSigner` is
accepted: <https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/internal/apk/v2/V2SchemeSigner.java>.
