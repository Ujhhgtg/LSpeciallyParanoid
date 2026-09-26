[![Maven Central](https://img.shields.io/maven-central/v/dev.ujhhgtg.lsparanoid/core)](https://central.sonatype.com/artifact/dev.ujhhgtg.lsparanoid/core)
[![Build](https://github.com/Ujhhgtg/LSpeciallyParanoid/actions/workflows/android.yml/badge.svg)](https://github.com/Ujhhgtg/LSpeciallyParanoid/actions/workflows/android.yml)

# LSpeciallyParanoid

Android string protection with an optional generated native decoder and explicit resource adapters.
The Gradle plugin ID remains `dev.ujhhgtg.lsparanoid`, with the `lsparanoid` extension. The existing JVM
backend is the default; native protection is enabled explicitly.

The native backend assigns separate authenticated ciphertext to every selected literal occurrence and
resource item, rebuilds transformed class constant pools, and generates an AArch64 decoder registered
through `JNI_OnLoad`. Each protected build invocation uses fresh random entropy. Optional O-MVLL
hardening targets selected decoder functions. There is no root, debugger, Frida, or LSPosed detection.

This increases the work required for static extraction. It cannot keep plaintext secret from an
attacker controlling the process: decoded Java strings and the native decoder remain observable.
Coverage and exclusions are reported; annotations, metadata, assets, and arbitrary resource consumers
are not automatically protected.

## Native application setup

The initial supported build configuration is a **Linux host, AGP 9.4.1, an application module with
minSdk 28 or newer, and arm64-v8a only**. Use a JDK supported by your AGP/Gradle versions; the reference
build uses JDK 21. Native protection for independently published AARs, dynamic features and AAB output
is outside the initial supported scope. The JVM backend remains available for existing consumers and
host/development workflows.

Make the plugin portal, Google Maven and Maven Central available in `settings.gradle.kts`. Apply:

```kotlin
plugins {
    id("com.android.application")
    id("dev.ujhhgtg.lsparanoid") version "0.12.0"
}

android {
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
    }
}

lsparanoid {
    backend = "native"
    variantFilter = { it.buildType == "release" }
    classFilter = { it.startsWith("com.example.app.") }
    nativeNdkVersion = "29.0.14206865"
}
```

The plugin adds its `core` dependency. With no `classFilter`, classes marked with
`dev.ujhhgtg.lsparanoid.Obfuscate` are selected. `includeDependencies = true` also considers dependency
classes; it does not make every string in those dependencies eligible. Review the coverage report.

Install the decoder NDK separately:

```sh
sdkmanager "ndk;29.0.14206865"
```

The decoder uses `nativeNdkVersion` under the Android SDK; the consuming application's `android.ndkVersion`
may differ. Native compilation fails if the selected toolchain or requested O-MVLL pass fails. It never
silently switches to the JVM backend. JNI keep rules and generated `jniLibs` are wired through AGP.

By default, the generated bridge loads its library with `System.loadLibrary` before its first decoded
read. A new invocation deliberately changes keys, IDs, bridge names and library names, reducing artifact
cache reuse. Gradle configuration-cache reuse remains supported. `seed` applies to the JVM backend;
it does not weaken native entropy or make native release output deterministic.

## Module and injected-process bootstrap

Xposed/Zygisk loading needs an explicit bootstrap under the module classloader:

```kotlin
lsparanoid {
    backend = "native"
    automaticLoading = false
    excludedClassPrefixes = setOf(
        "com.example.app.loader.",
        "com.example.app.EarlyApplication",
    )
}
```

The generated `dev.ujhhgtg.lsparanoid.generated.LspBootstrap` exposes:

```kotlin
LspBootstrap.libraryFileName             // this build's exact lib<name>.so filename
LspBootstrap.namespace                   // this build's resource-token namespace
LspBootstrap.loadInstalled()             // ordinary installed application process
LspBootstrap.loadAbsolute(decoderFile)   // existing absolute file, loaded from module classloader
LspBootstrap.decode(id)                  // for resource adapter integration
```

Extend the module's existing installed-library lookup or Zygisk extraction list with that exact
filename. Load before executing protected initializers or feature code. Exclude the necessary startup,
loader, and error-reporting paths with `excludedClassPrefixes`; these exclusions override `@Obfuscate`.
Do not load every APK `.so` entry indiscriminately. Zygote-time initialization is outside this contract.

An explicitly selected JVM development build generates a no-op loader facade so shared integration
code compiles; resource protection is disabled and resource decoding is unavailable in that backend.

## String resources

Resources are opt-in and require an explicit supported-use contract:

```kotlin
lsparanoid {
    backend = "native"
    resourceIncludes = setOf("string/*", "plurals/*", "array/*")
    resourceExcludes = setOf("string/app_name", "string/pre_bootstrap_message")
    wrappedResourceAccess = true
}
```

Selectors accept `string/name`, `plurals/name`, `array/name`, or a whole-type wildcard such as
`string/*`. Setting `wrappedResourceAccess` asserts that selected resources are only read through the
decoding adapter. It is not an automatic proof of resource-ID flow. The plugin adds its Android
`runtime` AAR when resource protection is enabled.

At the application's existing localization boundary:

```kotlin
import dev.ujhhgtg.lsparanoid.generated.LspBootstrap
import dev.ujhhgtg.lsparanoid.runtime.LspResourceContext

val decoded = LspResourceContext(localizedContext, LspBootstrap::decode, LspBootstrap.namespace)
// Install any displayed-text transformation wrapper around decoded, e.g. WeKit's Meow wrapper.
```

In Compose, provide `decoded.resources` through the application's existing `LocalResources` boundary.
Keep the original Activity/window context for platform operations. `LspResources(Resources, decoder,
namespace)` is available when an application already has its own Context wrapper. Native bootstrap
must finish before a protected read.

The adapter decodes strings, text, plurals and string arrays, including formatted and default-returning
overloads. Android chooses the configuration and plural category; decoding precedes formatting using
the resource configuration's locale. Host strings, spans on unprotected text, another build's tokens,
and caller-owned defaults (including null) pass through. There is no global context or plaintext
cache. Recreate wrappers on configuration changes.

Resource inventory resolves first-party `values*/*.xml` source layers, and uses AAPT2's official
compiled protobuf representation for quoting, whitespace and escape semantics. Every concrete
configuration/plural item/array position gets its own record. Supported entries become build-scoped
tokens in a generated overlay. The final merged manifest is checked again before native packaging to
catch dependency-contributed consumers. A post-compilation guard also tracks direct R fields, inlined
IDs and constant `getIdentifier` results through local variables to known unsafe framework consumers,
such as `TextView.setText(int)`, `Toast.makeText(Context, int, ...)`, resource-based menu titles, and
`Resources.getValue`. Such a path fails the build and identifies the resource and call site. Wrapped
Context/Resources getters and application helpers still rely on the declared integration contract;
this limited guard does not trace IDs through arbitrary fields, arrays, reflection or other methods.

The conservative initial exclusions include:

- Manifest and XML/system consumers, with reference/alias exclusions propagated across configurations.
- Aliases, reference-containing arrays/plurals, generic typed arrays, product-specific declarations,
  styled/nested text, and unsupported compiled values.
- Entries outside the explicit selection, or explicitly excluded by the application.
- Generated and dependency resource inputs not represented in the first-party static inventory;
  their coverage is not claimed.

Raw Context/Activity getters, `getValue`, `TypedArray`, framework APIs accepting resource IDs, unknown
Resources subclasses, third-party code and reflection can bypass the adapter. Exclude those resources
or route the read through the controlled boundary. Getter calls and Compose dependencies are not
universally rewritten. Pseudolocalization must be disabled for protected variants because it changes
tokens. See [the runtime contract](runtime/README.md) for details.

## Optional O-MVLL

The generated policy targets decoder lookup/authentication boundaries and leaves the JNI bootstrap
and cryptographic core outside control-flow flattening. Use the pinned
[O-MVLL 1.9.1 release](https://github.com/open-obfuscator/o-mvll/releases/tag/1.9.1) compatible with NDK r29.
Download its Linux NDK archive and verify its published SHA-256 before extracting; the plugin does not
download or install compiler passes automatically. The explicit setup command installs the pinned
NDK/O-MVLL pair with archive verification and retained licenses:

```sh
python3 tools/setup-native-toolchain.py --sdk "$ANDROID_SDK_ROOT"
```

Its default O-MVLL directory is `~/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1`.
Use `--offline` for existing installations/cached archives. Ordinary Gradle builds never invoke setup.

```kotlin
lsparanoid {
    omvllPlugin = "/absolute/toolchains/omvll-1.9.1/omvll-ndk.so"
    omvllPythonPath = "/absolute/toolchains/omvll-1.9.1/Python-3.10.7/Lib"
}
```

Use the Python directory containing the bundled standard library (`encodings`, etc.). Compiler output
must confirm the selected transforms; unavailable or incompatible hardening fails the build. Keep
compiler/pass versions and release checksums with your build records.

## Reports, private build outputs and tests

Variant reports are written under `build/reports/lspeciallyparanoid/<variant>/`:

| File | Purpose |
| --- | --- |
| `strings.tsv` | Class/string coverage and exclusions without plaintext values. |
| `resources.tsv` | Resource identity, configuration, source and protection/exclusion reason. |
| `native.txt` | Compiler identity, library size, exported symbols and ELF alignment inspection. |

Intermediate output under `build/intermediates/lspeciallyparanoid/<variant>/` includes entropy,
generated sources, resource compiler inputs, encrypted records and private native symbols. Treat
these as private build data; do not publish them with APKs or commit them. Source XML itself contains
the original text. Public reports do not claim that text intentionally left in another resource,
annotation or unselected class has disappeared from the APK.

```sh
./gradlew :core:test :processor:test :runtime:testDebugUnitTest :gradle-plugin:validatePlugins
./gradlew :runtime:connectedDebugAndroidTest
```

Tests include compiled AAPT resource semantics and overlay removal, cryptographic vectors, separate
occurrences, generated decoder execution, and Android resource formatting/configuration behavior.
Device tests install the project's fixture package. The CI workflow runs minified release device tests
only when the repository variable `ARM64_ANDROID_SERIAL` identifies an available arm64 device; otherwise
it explicitly reports device execution as skipped. Audit a built native fixture without installation:

```sh
python3 tools/verify-protected-apk.py --ndk "$ANDROID_SDK_ROOT/ndk/29.0.14206865" \
    --apk testApp/build/outputs/apk/release/testApp-release.apk
```

Use the actual output filename if it differs. This checks known fixture sentinels across all APK entries,
decoder exports, required runtime sections, stripping, and alignment; it is not a proof of arbitrary
application coverage. Passing host tests does not establish Xposed or Zygisk compatibility; validate
the actual loading paths and localized UI. API-floor and 16 KiB device
execution remain distinct requirements from ELF alignment checks. Never pass an APK larger than
100 MiB to jadx; use AAPT2, ZIP/DEX readers, apktool or LLVM tools for artifact inspection.

## Credit and license

LSpeciallyParanoid derives from [Michael Rozumyanskiy's Paranoid](https://github.com/MichaelRocks/paranoid),
[LSPosed's LSParanoid](https://github.com/LSPosed/LSParanoid), and
[Androidacy's LSParanoid](https://github.com/Androidacy/LSParanoid). Thanks to their authors and contributors.

The project is licensed under [Apache License 2.0](LICENSE.txt), retaining the upstream copyrights:
Michael Rozumyanskiy (2021), LSPosed (2023), and Androidacy (2024). The vendored Monocypher source carries
its own [license](processor/src/main/resources/nativebackend/monocypher/LICENCE.md) and
[provenance notes](processor/src/main/resources/nativebackend/monocypher/README.md).
