# Protected Android fixture

This application exercises LSpeciallyParanoid's native backend and Android resource adapter. Expected plaintext lives in the separate instrumentation APK; the target APK is scanned for unique sentinels.

The plugin is taken from the root build via `includeBuild("..")`. First run the explicit `tools/setup-native-toolchain.py` setup. Native mode requires an arm64-capable Android device, API 28+, and a Linux build host.

## Release build and tests

From this directory:

```bash
./gradlew assembleRelease assembleReleaseAndroidTest --configuration-cache \
  -PlspTestBuildType=release \
  -PlspOmvllPlugin="$HOME/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1/omvll-ndk.so" \
  -PlspOmvllPythonPath="$HOME/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1/Python-3.10.7/Lib"
```

To execute on an explicitly chosen device, replace the build tasks with `connectedReleaseAndroidTest` and set `ANDROID_SERIAL`. An x86_64 AVD can be used only if its advertised ABI list includes `arm64-v8a` and a native bridge is available. The former default x86 ATD managed-device setup is not a valid native-backend test target.

The release fixture is minified and signed with the debug test key. Its fixture-only keep rules retain APIs, resource IDs and Kotlin runtime paths referenced from the separate test APK. They are not production plugin consumer rules.

## Coverage

- Transformed literal methods, constant fields, Unicode, NULs, lone surrogates, duplicate occurrences and a long string.
- Native registration and loading after R8.
- Resource configuration selection, plural selection, formatting after decoding, arrays, defaults, styled-value exclusion and framework-resource passthrough.
- Invalid IDs and ordinary concurrent access.
- Informational small-registry cold-load/warm-call timing in `NativePerformanceTest`; no device-dependent threshold.

For a cold timing measurement, invoke only `NativePerformanceTest` in a fresh instrumentation process. Running it after another test may record `cold_load=false`.

The JVM backend remains available with `-PlspBackend=jvm` for compatibility builds; native-specific runtime tests are intended for the native backend. Native APK, caller-process and Android-environment verification is mandatory; failures abort.

Run `tools/verify-protected-apk.py` from the root to inspect sentinels, ELF exports, stripping and 16 KiB alignment. Configuration-cache reuse must still produce fresh randomized native output on every invocation. Test and coverage reports are under this build's `build/reports` directory.


`IntegrityProbeActivity` runs in `:integrity_probe` and changes only the extracted library's GNU build-id
note while retaining the authentic signed APK. It is an isolated negative test: the expected outcome is
native SIGABRT with the module-integrity rejection message, not a Java loading error. It is never part of
the production runtime module.
