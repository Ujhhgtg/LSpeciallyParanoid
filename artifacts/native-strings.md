# Native string encoding — 0.13.2

Date: 2026-09-27. This change covers LSP's own native text. The existing arithmetic
and control-flow flattening selections, record layout and per-occurrence encrypted
storage are unchanged; size tuning was dropped at the user's request.

## Implementation

The O-MVLL policy selects local string encoding for text in `decoder.c`,
`runtime_guard.c`, `apk_verify.c` and `frida_guard.c`. This covers diagnostics,
JNI names/signatures, native policy strings, APK verification text and detection
indicators. Native text is decoded on use. The policy excludes binary byte arrays
and third-party crypto sources. This distinction matters because O-MVLL 1.9.1's
[eligibility test](https://github.com/open-obfuscator/o-mvll/blob/1.9.1/src/passes/string-encoding/StringEncoding.cpp)
also accepts NUL-terminated byte arrays that are not text.

The selected [local encoding mode](https://obfuscator.re/omvll/passes/strings-encoding/)
uses generated decode routines and mutable buffers. It prevents direct plaintext
extraction from the shipped ELF, but decoded text remains observable in process
memory after use. It is not encryption against an attacker controlling execution.

Protected native builds now require O-MVLL. The plugin automatically selects the
existing pinned installation under
`~/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1`, including bundled Python.
Explicit plugin/Python paths remain supported. Missing tools fail the protected
build with setup instructions. Excluded/unprotected and JVM variants remain usable
without the native obfuscator. Gradle does not download it automatically.

The build verifies selection and actual application of the string pass in all four
owned modules. It then scans the stripped ELF for every selected literal and every
generated policy string of at least eight bytes, plus explicit guard markers.
Surviving plaintext fails the build. Shorter fragments are encoded too when eligible,
but excluded from the bulk absence check because instructions and ciphertext can
coincidentally contain short sequences. The compiler `.comment` section is removed.

The literal manifest is written only beneath private native symbols as
`native-string-literals.tsv`; pass logs also contain native literals. Public reports
show only the audit count. Preserve those private files for diagnosis, not publication.

## Validation

- Fixture: 145 distinct meaningful literals audited; protected minified ARM64 release
  passed all 10 Android instrumentation tests on the API 37 / 16 KiB AVD.
- WeKit: 146 literals audited, including its generated host policy; native compilation
  and the full release build passed without explicit O-MVLL properties, exercising
  default toolchain selection. Standalone AVD cold start completed in 1,789 ms and
  the process remained alive without a guard failure or Java fatal exception.
- Both prior binaries exposed 63 `LSP guard:` diagnostics. The new binaries contain
  zero occurrences of that prefix, and no audited policy literals in plaintext.
- Protected WeKit toolchain validation rejects an explicitly missing O-MVLL plugin.
  Unprotected debug-only, test and explicitly unprotected release task configuration
  succeeds with that same missing path. Validation runs as a protected task dependency,
  so configuring an unused protected variant cannot block an unprotected build.
- The native Android Frida harness now optionally uses the production O-MVLL policy.
  With string encoding enabled, a real upstream Gadget still triggers the periodic
  Gum-image abort, and a zer0def Gadget triggers the immediate Gum-image abort.
  The upstream control runs normally. This is native x86_64 Android detector testing;
  the separate protected application exercises the ARM64 production library.
  Reproduce with `tools/test-frida-android.py` and its `--omvll-plugin` and
  `--omvll-python` options pointing to the pinned installation.
- The final fixture APK passes native export, stripping, sentinel and 16 KiB alignment
  checks. Processor tests, Gradle plugin validation and all 19 Python tooling tests pass.

Android-required dynamic symbols, library names, ELF metadata, runtime support text
and standard cryptographic constants can still be recognizable. The audit does not
claim the ELF contains no printable bytes. It targets LSP's own meaningful native
text; it does not change the remaining limitations of native integrity or Frida
detection documented in their reports.
Real injected WeChat execution remains unverified; the WeKit launch check covers
its standalone application only.

Local diagnostic logs are under ignored `build/native-tuning/`, named during the
initial investigation. The public audit counts/hashes are in
[native-string-audit.json](native-string-audit.json); no literal manifest is published.
