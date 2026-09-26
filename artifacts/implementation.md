# LSpeciallyParanoid implementation and validation

Implemented 2026-09-26 as version **0.12.0**. Source changes are in this working tree; Maven artifacts were published **locally only** for integration testing.

## Delivered

- Native application backend with fresh 256-bit build entropy, randomized bridge/library names, independent encrypted records for every occurrence, and ChaCha20-Poly1305 authentication. Literal and resource records use separate domains. Monocypher 4.0.3 is vendored under its CC0 option with provenance and the full upstream license retained.
- Generated JNI registration, explicit and automatic loading modes, exact R8 rules, and a JVM development facade. The original JVM backend remains the default; select `backend = "native"` explicitly. Native mode never falls back to JVM decoding at runtime.
- Gradle tasks for entropy, bootstrap, class transformation, AAPT2 resource normalization/overlays, native compilation, stripping, diagnostics and coverage. Configuration-cache reuse was exercised while native release outputs continued to change between invocations.
- Pinned, separately installed NDK r29 and optional O-MVLL 1.9.1. Selected flattening and arithmetic transforms must appear in both configuration callbacks and applied-pass logs; requesting O-MVLL cannot silently produce an ordinary build. No detection, anti-hooking policy, or malformed ELF transformations were added.
- First-party strings, plurals and string arrays behind an explicit resource-access contract. Android performs resource selection; decoding happens before formatting. XML/manifest consumers, unsupported values and explicit exceptions remain usable and are reported. A late manifest check and a bounded bytecode analysis reject known unsupported framework consumers.
- Android resource wrappers, constant-pool rebuilding, coverage of unsupported string locations, removal of plaintext from ordinary processor logging, deterministic JVM JAR timestamps, and corrected empty generated decoders.
- WeKit bootstrap/localization adapters, setup tooling, APK auditing, native execution tooling and updated CI/documentation.

The resource validator is intentionally not a whole-program proof. It catches tracked IDs at known framework sinks, including local-variable aliases and constant name lookups. Application helpers, dynamic IDs, reflection, field/array propagation and unrelated resource generators still need the documented integration contract. Reports explicitly identify the static first-party scope.

## Validation completed

| Check | Result |
|---|---|
| Core tests | 129 passed |
| Processor/codegen/native/resource tests | 31 passed, including 10 native and 9 resource-bytecode-guard tests |
| Android runtime unit tests | 3 passed |
| Python setup/audit tooling tests | 10 passed |
| Gradle plugin validation | Passed |
| JVM compatibility samples | Both application and all three library release builds passed |
| Generated native code under host JVM | Real JNI execution and isolated classloader tests passed, including authenticated failure and explicit empty-library behavior |
| Actual ARM64 machine-code execution | Baseline and O-MVLL binaries each passed 62 UTF-16 inputs plus tampering, unknown-ID, NewString failure and pending-exception checks under QEMU |
| Minified protected release APK | Built with stable r29 + O-MVLL; all **10 instrumentation tests passed** on the API 37 / 16 KiB AVD |
| Android runtime framework tests | 3 passed earlier on API 36 / 4 KiB hardware |
| Final fixture APK audit | Six unique sentinels absent in UTF-8, UTF-16LE and UTF-16BE; ELF/exports/stripping and 16 KiB ZIP alignment checks passed |
| WeKit | Standard and legacy debug builds passed; both minified release flavors passed with stable r29, O-MVLL and the resource guard |
| WeKit standalone application | Standard release installed and launched on the AVD; startup completed and visible module UI contained decoded text, with no raw resource token found in its UI hierarchy |

The AVD is `lsp-api37-16k`, serial `emulator-5580`, API 37, with `PAGE_SIZE=16384`. Its host ABI is x86_64 and Android's `libndk_translation.so` executes the packaged ARM64 library. This is Android/ART/native-bridge execution, not physical ARM64 hardware. The separate QEMU smoke runs real ARM64 instructions with minimal JNI callbacks and is not an ART or Xposed test.

The initial minified instrumentation harness exposed classes used only from the test APK being removed from the target APK. Fixture-only keep rules retain its Kotlin test runtime, resource IDs and public fixture entry points; these rules were **not** added to production consumer rules. The resource adapter itself remains in the optimized application call graph.

## Measurements

The standard WeKit release protected **63,405 literal/field sites** and **10,595 resource configuration rows**. Expanded plural/array items bring its combined native record count to **74,235**. Its resource report contains **15 excluded configuration rows**. The legacy flavor has 63,380 protected literal/field sites and 74,210 combined records.

| WeKit flavor | Generated stripped decoder | Final APK |
|---|---:|---:|
| Standard release | 8,600,736 bytes | 19,212,644 bytes |
| Legacy release | 8,598,528 bytes | 19,205,359 bytes |

These are observed output sizes, not measured growth against an otherwise identical unprotected WeKit build. Separate ciphertext per occurrence is intentionally retained as requested. Reports also enumerate excluded class strings and metadata; the protected counts are not claims of complete application-string coverage.

A small, two-record compiler fixture grew from **11,168 to 12,672 bytes** after selective native hardening. Applied-pass logs and disassembly confirmed actual changes, rather than only positive policy callbacks.

One informational run on the API 37 AVD, with a small fixture registry and 20,000 calls per measurement, recorded:

- Cold library load: **72.1 ms**, including native translation/JNI registration.
- Warm 36-code-unit string: **2.60 µs/call** mean.
- Warm 523-code-unit string: **7.34 µs/call** mean.

These are emulator fixture timings, not WeKit startup measurements, device performance guarantees or tail-latency estimates. No fixed performance threshold was introduced.

## Reproduce

From the repository root:

```bash
python3 tools/setup-native-toolchain.py --sdk "$ANDROID_HOME"
./gradlew :core:test :processor:test :runtime:testDebugUnitTest \
  :gradle-plugin:validatePlugins publishToMavenLocal
python3 -m unittest discover -s tools -p 'test_*.py'
```

Build the minified fixture:

```bash
cd testApp
./gradlew assembleRelease assembleReleaseAndroidTest --configuration-cache \
  -PlspTestBuildType=release \
  -PlspOmvllPlugin="$HOME/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1/omvll-ndk.so" \
  -PlspOmvllPythonPath="$HOME/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1/Python-3.10.7/Lib"
```

Use [the APK audit tool](/home/ujhhgtg/coding/LSpeciallyParanoid/tools/verify-protected-apk.py) on the generated fixture APK, and [the ARM64 execution runner](/home/ujhhgtg/coding/LSpeciallyParanoid/tools/test-native-arm64.py) for the separate baseline/hardened native check. See [its README](/home/ujhhgtg/coding/LSpeciallyParanoid/tools/native-arm64/README.md).

The task's AVD needed Bluetooth/UWB emulator services disabled to avoid a host emulator hang:

```bash
emulator -avd lsp-api37-16k -port 5580 -no-window -no-audio \
  -no-snapshot -no-boot-anim -gpu software \
  -feature -Vulkan,-BluetoothEmulation,-Uwb -cores 2 -memory 4096
```

CI uses a configured `ARM64_ANDROID_SERIAL` only when present. An ARM64-capable emulator may be used; absence of a configured device produces an explicit execution-skip notice, not a claim that Android tests ran.

## Subsequent native verification work

Version 0.13.0 adds APK signer/content verification, loaded-library integrity and
approved Android host verification. See the [adversarial report](adversarial/README.md)
for measured before/after replay results and remaining limits. The 0.12 measurements
above are historical; the WeKit integration has since moved to a single modern
Xposed/Zygisk build through an independent repository refactor.

## Evidence and remaining validation

- [Host validation log](/home/ujhhgtg/coding/LSpeciallyParanoid/build/lsp-final-validation.log)
- [Minified release build log](/home/ujhhgtg/coding/LSpeciallyParanoid/testApp/build/native-release.log)
- [AVD instrumentation output](/home/ujhhgtg/coding/LSpeciallyParanoid/build/avd-release-instrumentation.log)
- [Cold/warm fixture measurement](/home/ujhhgtg/coding/LSpeciallyParanoid/build/avd-performance.log)
- [ARM64 execution report](/home/ujhhgtg/coding/LSpeciallyParanoid/build/native-arm64-smoke/report.json)
- [WeKit release build log](/home/ujhhgtg/coding/LSpeciallyParanoid/build/wekit-release.log)
- [WeKit integration notes](/home/ujhhgtg/coding/wekit_dev/artifacts/lspeciallyparanoid-integration-2026-09-26.md)

Actual injected execution inside WeChat under legacy/modern Xposed and Zygisk, including host-side Meow locale selection, remains to be tested on a suitable rooted setup. No WeKit APK was installed on the user's physical phone. API 28 runtime execution and an independent analyst extraction-effort study also remain unverified. Passing artifact scans does not prevent runtime plaintext observation on an attacker-controlled device.

Native protected AARs, dynamic-feature/independent extension payloads, non-Linux toolchain certification, resource-name collapsing, generic asset/metadata/protocol transformations and parser-hostile ELF tricks remain outside this initial implementation. Build-local private symbols are retained for the current build; archive matching symbols/mappings separately for shipped releases before rebuilding.
