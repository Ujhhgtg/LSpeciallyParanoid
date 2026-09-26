# Native replay adversarial evidence

The starting implementation was vulnerable to the exact reuse attack described
in the request. All three independent replay paths recovered all 94 public IDs
discovered in the owned fixture: 85 DEX callsites and 9 protected resource tokens.

| Baseline attack | Observed result | Evidence |
| --- | --- | --- |
| Bare unidbg ARM64 VM, foreign process name, extracted library and bridge | 94 plaintexts; approximately 0.20 seconds after JVM startup, including emulator creation and replay | Historical observation; raw baseline report removed |
| Differently signed Android shell containing copied library and bridge declaration | 94 plaintexts | [baseline-shell.json](baseline-shell.json) |
| Differently signed Android shell loading the unchanged signed original APK through DexClassLoader | 94 plaintexts | [baseline-original-dex.json](baseline-original-dex.json) |

Both Android attacks ran on `emulator-5580`, Android API 37 with 16,384-byte pages
and ARM64 native translation. No physical phone was used. Baseline unidbg also
recovered 94 plaintexts when supplied with the original APK's public metadata
and plausible Android 15 physical-device properties.

The APK snapshot's SHA-256 was
`503f73868e1ac07cfd8df7656ea7d4f98a1deaaf664a67577fe168686637d0f1`.
Its library's SHA-256 was
`760369bdab5038ef855720d65e46b7cebb743aa9e511349052c7d810c284342e`.
The snapshot is retained locally under ignored `build/adversarial/baseline`.
The pre-guard production implementation is committed as `65ec843`. The extraction
utility, shell generator and raw baseline unidbg report have since been removed.

The attacker inputs were the signed APK alone. The tools extracted the randomized
native bridge declaration, library name, DEX constants and resource token IDs.
They did not use source plaintext, generated C, entropy files, record metadata,
or the protected app's signing key. The shell's separate private key was generated
solely to sign the attacker's disposable app.

The unidbg adaptation implemented Android RELR relocations missing in unidbg
0.9.9; no target instructions or guard conditions were patched. JSON preserves
lone UTF-16 surrogates in the unidbg results. The Android shell's ordinary Java
UTF-8 JSON writer replaces a lone surrogate with `?`; its result still demonstrates
that the native decoder returned that fixture's String successfully.

The remaining [shell runner](../../tools/adversarial/README.md) requires an already
built fixture; this repository no longer generates replay shells. Native abort, Java loading
errors, unsupported emulation and timeouts are separate outcomes. Only the first
is a successful rejection for the guarded regression tests.

## Guarded results (0.13.0)

The release fixture uses NDK r29 and O-MVLL 1.9.1, including verified application
of the requested passes to guard initialization and APK verification. Each JSON
report identifies the actual APK tested; randomized rebuilds change that hash.

| Guarded attempt | Observed result | Evidence |
| --- | --- | --- |
| Bare unidbg, extracted library and public DEX IDs | Native abort; zero plaintexts | [guarded-unidbg.json](guarded-unidbg.json) |
| unidbg with original APK metadata, plausible device properties and protected package process name | Native abort; zero plaintexts | [guarded-unidbg-spoofed.json](guarded-unidbg-spoofed.json) |
| Differently signed shell with copied bridge/library | Native SIGABRT; zero plaintexts | [guarded-shell.json](guarded-shell.json) |
| Differently signed shell loading unchanged original signed APK | Native SIGABRT; zero plaintexts | [guarded-original-dex.json](guarded-original-dex.json) |
| Authorized fixture process loading an extracted library with one altered GNU build-id byte; signed APK unchanged | Native SIGABRT with integrity rejection | [guarded-integrity.json](guarded-integrity.json) |

The same final release passed all 10 Android instrumentation tests on the API 37
AVD with 16 KiB pages and ARM64 translation. Its six known fixture sentinels were
absent in UTF-8/UTF-16 scans, and the decoder passed export/stripping/alignment
checks. Independent native APK-verifier tests passed 23 cases, covering all six
supported signature algorithms, NIST curves, certificate/public-key mismatch,
wrong pins, corrupt signatures/content digests, malformed signing blocks and ZIP
entry validation. These use Python cryptography to sign fixtures, independently
of the native BearSSL verifier. Core/processor/runtime host suites passed 163
tests; setup tooling passed 10 tests.

Local diagnostic logs are retained under ignored `build/`: `guarded-release-tests.log`,
`apk-verifier-tests.log`, `guarded-apk-audit.log`, and `adversarial/guarded-release-*`.
The integration and its separate validation limits are recorded in
[the WeKit notes](/home/ujhhgtg/coding/wekit_dev/artifacts/lspeciallyparanoid-integration-2026-09-26.md).

## What changed and what remains attackable

The decoder independently discovers APK candidates through the bridge classloader,
its mapped library, process mappings and open descriptors. It verifies the module
certificate pin, APK v2 signature and all signed content chunks, then compares the
authenticated library entry with its nonwritable loaded segments and the complete
extracted file when applicable. No caller-supplied module APK path reaches JNI.
An original APK alone is insufficient: the current host must also match a configured
package, signing pin, process name, kernel UID and app data owner. Package information
comes from a fresh Binder service lookup, with boot-loader framework-class checks,
rather than trusting mutable cached package-manager objects. Basic JNI, kernel,
Binder, ART and Android property consistency is mandatory. Failures abort directly.

This is load-time authentication with inexpensive bridge/PID/UID checks at each
decode. It does **not** authorize individual Java callsites, continuously hash code,
authenticate in-memory DEX, prevent native patching, or prevent an attacker already
executing inside an authorized signed host from calling the decoder and collecting
plaintext. In-memory LSPosed/Zygisk loading explicitly trusts that authorized host
after module APK/library authentication. There is no blanket root or hook-framework
ban. Genuine Android emulators remain valid Android environments.

Supported APK format is deliberately bounded: v2, one signer, RSA through 4096 bits
or NIST ECDSA, SHA-256/SHA-512, no ZIP64; individual extracted entries are capped at
128 MiB. The implementation uses a pinned, unmodified BearSSL verification subset;
format references and source hashes are recorded in its
[provenance](../../processor/src/main/resources/nativebackend/guard/vendor/bearssl/README.md).

Actual injected WeKit execution under modern Xposed and Zygisk, and Android API 28
execution, remain unverified. Automated approval review rejected implementation
of a deeper Binder-spoofing attack harness; that attack was not completed or tested. The defensive
Binder changes therefore have build/positive-path coverage and the existing replay
regressions, not evidence of resistance to that stronger attack.
