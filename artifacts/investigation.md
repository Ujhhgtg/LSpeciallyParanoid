# LSpeciallyParanoid: independent investigation

Investigated 2026-09-26. Project baseline: `e908b78`. Reference app: WeKit at `70145f1f`, including its existing working-tree changes. WeKit was inspected only; none of those changes were modified. Input: `/home/ujhhgtg/Downloads/conversation.md` (1,909 lines).

This document distinguishes source observations, reproduced behavior, and design inferences. The accompanying plan is a proposal, not a claim that native/resource protection has been implemented.

## Confirmed requirements

- Name: **LSpeciallyParanoid**; Android AArch64 only.
- Automatically build a native string decoder and increase the effort required to reverse engineer protected strings.
- Protect JVM strings and string resources; other resource types are outside the requested encryption scope.
- Attacker: rooted device, Frida, LSPosed, and LLM assistance.
- Compatibility reference: `/home/ujhhgtg/coding/WeKit`, itself an Xposed module.
- Protect supported resources and report every exclusion.
- Follow-up decisions: Xposed and Zygisk support; no detection in this phase; Frida entry-mode compatibility is unnecessary; separate per-occurrence ciphertext; randomized release builds; API breaks allowed. Prefer a separately pinned decoder NDK over changing WeKit's native toolchain. Concrete adapters are proposed for review rather than assumed accepted.

## What the current project actually does

| Finding | Evidence | Consequence |
|---|---|---|
| Literal replacement and decoder generation are already separated. | [ParanoidProcessor.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/processor/src/main/kotlin/dev/ujhhgtg/lsparanoid/processor/ParanoidProcessor.kt:55) patches before generating classes. | The transcript identifies a useful seam for adding a native backend. |
| The registry deduplicates strings, uses one 32-bit seed, resets the PRNG for every entry, and puts that seed in the ID's low bits. | [StringRegistry.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/processor/src/main/kotlin/dev/ujhhgtg/lsparanoid/processor/StringRegistry.kt:54). | The weakness is stronger than a recognizable table: entries reuse the same keystream positions and carry the decoding seed. Moving this format into C++ is a migration test, not the finished protection. |
| Encoding is big-endian Java UTF-16 code units, with a 16-bit length limit. | `writeChar`, the `0xFFFF` length guard, and [RandomHelper.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/core/src/main/kotlin/dev/ujhhgtg/lsparanoid/RandomHelper.kt). | Native differential tests must include embedded NUL and unpaired surrogates, not just valid Unicode text. |
| The transform copies the original constant pool. | [Patcher.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/processor/src/main/kotlin/dev/ujhhgtg/lsparanoid/processor/Patcher.kt:99) constructs [StandaloneClassWriter](/home/ujhhgtg/coding/LSpeciallyParanoid/processor/src/main/kotlin/dev/ujhhgtg/lsparanoid/processor/StandaloneClassWriter.kt:50) with `ClassReader`. | Replaced plaintext survives in intermediate class/JAR output. See reproduction below. Do not infer final DEX leakage without inspecting DEX. |
| Current coverage is selected classes' string `LDC` instructions and selected static constant fields. | [StringLiteralsClassPatcher.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/processor/src/main/kotlin/dev/ujhhgtg/lsparanoid/processor/StringLiteralsClassPatcher.kt:59), `Analyzer`, `StringConstantsClassPatcher`. | Annotations, Kotlin metadata, bootstrap arguments, interface constants, strings in unselected callers, resources and assets require separate coverage accounting. “All strings” would be inaccurate. |
| Own Java/Kotlin compilation gets inline string-concatenation flags. | [LSParanoidPlugin.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/gradle-plugin/src/main/java/dev/ujhhgtg/lsparanoid/plugin/LSParanoidPlugin.kt). | Already compiled dependencies can still contain `invokedynamic` concat recipes. `includeDependencies` does not solve this by itself. |
| Plaintext is emitted to info-level build logs. | [StringLiteralsClassPatcher.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/processor/src/main/kotlin/dev/ujhhgtg/lsparanoid/processor/StringLiteralsClassPatcher.kt:69), `ParanoidProcessor.AnalysisResult.dump`. | Remove plaintext from routine diagnostics in the hardening work. |
| File-backed encoding still becomes a full in-memory byte array during generation. | [DeobfuscatorGenerator.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/processor/src/main/kotlin/dev/ujhhgtg/lsparanoid/processor/DeobfuscatorGenerator.kt:50). | Do not claim bounded memory. Large resource catalogues make this relevant; measure before designing a more complex pipeline. |
| A fixed seed does not by itself establish reproducible artifacts. | [JarUtils.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/processor/src/main/kotlin/dev/ujhhgtg/lsparanoid/processor/commons/JarUtils.kt:29) does not set entry timestamps; random seed generation happens during task configuration. | Specify ordering, timestamps, random inputs, and configuration-cache behavior explicitly. |
| Consumer rules describe older generated names and a reflection method absent from the current generator. | [consumer-rules.pro](/home/ujhhgtg/coding/LSpeciallyParanoid/core/consumer-rules.pro), current `Deobfuscator$<project>` naming. | Existing rules cannot be reused for a randomized name-based JNI binding. This is not proof of a current JVM runtime failure: direct references allow R8 to track current calls. |

### Verification performed

1. `./gradlew :core:test :processor:test :gradle-plugin:validatePlugins --offline --console=plain` succeeded. Existing outputs were up to date; `processor:test` had no sources.
2. `./gradlew :core:test --rerun-tasks --offline --console=plain` freshly rebuilt and ran **129 tests, 0 failures, 0 errors, 0 skipped**.
3. A temporary Java fixture using the installed ASM 9.10.1 replaced a unique string `LDC` through a visitor. With `ClassWriter(reader, flags)`, the original plaintext remained in output bytes; with `ClassWriter(flags)`, it did not. This reproduces the exact constant-pool copying mechanism used here, not an end-to-end APK exploit.
4. Inspected the locally resolved AGP **9.4.1 source JAR**, rather than guessing API names: `Sources.jniLibs`, layered `res.static`, generated-source registration, `SdkComponents.ndkDirectory`, and `Variant.proguardFiles` exist. Public `SingleArtifact` does **not** expose a `MERGED_RES` transform.
5. Inspected WeKit resources and loaders. No APK decompiler, device installation, WeChat modification, or WeKit build was performed. No native decoder or O-MVLL compilation was performed.

Current `IntegrationTest` checks the registry against `DeobfuscatorHelper`, not the generated ASM decoder. There are no processor/plugin test source directories. CI builds a minified test APK but runs debug instrumentation. A successful existing suite therefore does not prove generated JNI, R8 binding, or native loading correctness.

## WeKit changes the design

| Observation | Evidence and design impact |
|---|---|
| API 28 minimum, API 37 target/compile, JDK 21, AGP 9.4.1, Kotlin 2.4.20, NDK 30.0.14904198. | [Version catalog](/home/ujhhgtg/coding/WeKit/gradle/libs.versions.toml). Use API 28 as the initial reference-app floor; broader plugin support is a separate decision. |
| Arm64-only; standard/legacy entrypoint flavors; APK also serves as a Zygisk payload. | [app/build.gradle.kts](/home/ujhhgtg/coding/WeKit/app/build.gradle.kts). Preserve pre-signing packaging and both flavors. |
| Resource package ID is `0x69`, not the conventional app `0x7f`. | `androidResources.additionalParameters`, plus startup checks. Never hard-code `0x7f` in the resource bridge. |
| Existing native loading uses explicit absolute paths and fixed names. | [NativeLoader.kt](/home/ujhhgtg/coding/WeKit/app/src/main/java/dev/ujhhgtg/wekit/loader/utils/NativeLoader.kt:53), [ZygiskNativePayload.kt](/home/ujhhgtg/coding/WeKit/app/src/main/java/dev/ujhhgtg/wekit/loader/utils/ZygiskNativePayload.kt:29). Merely packaging a randomly named `.so` will not add it to these loaders. |
| Native setup happens after earlier module code has executed. | [ModuleLoader.kt](/home/ujhhgtg/coding/WeKit/app/src/main/java/dev/ujhhgtg/wekit/loader/startup/ModuleLoader.kt:128). Blanket literal replacement can require native decoding before bootstrap knows the module path. Establish an explicit bootstrap boundary and report its exclusions. |
| In-memory class loading is a supported path. | Zygisk entry and payload loader; API 28 extraction is already documented in WeKit. Do not assume `System.loadLibrary` can discover the generated decoder in every module classloader. |
| Resources are injected into host contexts. | [ResourcesInjector.kt](/home/ujhhgtg/coding/WeKit/app/src/main/java/dev/ujhhgtg/wekit/loader/utils/ResourcesInjector.kt). Decode only this build's tokens; preserve host and extension-pack strings and dispatch behavior. |
| Compose receives localized `LocalResources`. | [WeKitLocaleProvider.kt](/home/ujhhgtg/coding/WeKit/app/src/main/java/dev/ujhhgtg/wekit/i18n/WeKitLocaleProvider.kt:39). A bridge using an application-global Context or `Locale.getDefault()` would be wrong. |
| MeowResources transforms results, including formatting overloads. | [MeowResources.kt](/home/ujhhgtg/coding/WeKit/app/src/main/java/dev/ujhhgtg/wekit/i18n/MeowResources.kt:32). A post-return token decoder can encounter an already modified token. Decode inside the delegate path before formatting/text transformation in the proper existing order. |
| Frida was also an explicit module entry mode when inspected. | The then-present `/home/ujhhgtg/coding/WeKit/app/src/main/java/dev/ujhhgtg/wekit/loader/entry/frida/FridaInjectEntry.kt` defined those entry points; it was no longer present during final link validation. The user excluded this mode from the compatibility requirement and requested no detection at all. This investigation made no changes to that file. |
| Desktop DexKit work and dynamic extension classloading exist. | WeKit AGENTS.md and `ScriptDepsPack`. Arm64 Android native decoding cannot be assumed to work in desktop JVM tasks. |
| The resource catalogue is substantial. | XML inventory of `app/src/*/res/values*/*.xml`: **10,261 string definitions, 345 plural definitions, 7 string-array definitions**, across default, `zh-rCN`, and `zh-rTW`. Counts include configurations, not unique names or plural items. No nested markup in `<string>` definitions was found by this inventory. |
| Some string-family resources necessarily remain public. | Manifest uses `app_name`, `app_description`, and `xposed_scope`. These are exclusions, not implementation bugs. |

WeKit does not currently apply this plugin in the inspected app build script. Integration is new work, not a toggle of an existing installation.

## Independent assessment of the transcript

**Adopt:** retain Android configuration/plural selection; decode before formatting; use JNI UTF-16 creation; register native methods explicitly; hide internal exports; keep native debug artifacts privately; build incrementally with differential tests.

**Revise:** a shared registry should mean a shared immutable format and namespace, not mutable state shared between Gradle tasks. Source resource generation happens before class compilation. If resource decisions depend on post-compile analysis that itself needs generated resources, a task cycle results. Generate candidates early and validate actual bytecode usage later; fail packaging if the preselected protection cannot be honored.

**Revise:** `res.static` explicitly omits task-generated resources. It is not the complete merged application resource universe. Dependency resources, generated resources, merged manifest references, overlays, aliases and configuration fallbacks need explicit inventory. Reading `res.all` while contributing one's own generated directory risks a self-dependency.

**Reject as a safety proof:** “no XML consumers means safe.” `TextView.setText(resourceId)`, menu/Toast APIs, third-party code, `TypedArray`, reflection and dynamic IDs can resolve resources without any transformed getter at the originating call site. Report uncertainty rather than silently classifying it as protected coverage.

**Revise:** raw XML text is not necessarily the text Android returns. Quoting, whitespace processing, escapes, XLIFF and spans need Android-compatible normalization. AAPT2 must be the semantic oracle for supported inputs; do not quietly implement a partial XML-to-string converter.

**Revise:** multiple accessor signatures and randomized names may inconvenience static scripts, but all roads still lead to native registration and plaintext creation. For the stated attacker, measure resistance against actual extraction, not visual ugliness of decompiled code. A hidden symbol is still a callable runtime function, and JNI necessarily returns observable plaintext.

**Defer:** resource-name collapsing is an AAPT2 **optimize** option, not simply a generic extra link flag. Its documented behavior spans resource names generally; protecting strings only requires exclusions for other types and name-based consumers. Integrating it correctly before signing and for bundles is a separate project, not a free first-release switch. [AAPT2 reference](https://developer.android.com/tools/aapt2).

**Defer:** universal metadata stripping, protocol-field renaming, assets encryption, arbitrary JVM CFG transformation, and post-R8 DEX rewriting. These exceed the requested string scope or need a separate compatibility contract.

**Reject for the supported baseline:** intentionally malformed ELF and blanket removal of unwind information. Current bionic reads section headers and validates dynamic-section relationships; it also rejects a zero section-name-table index under applicable target-SDK checks. Renaming section labels is at most an optional measured experiment, not substantial protection against a competent analyst. [Bionic loader source](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp), [LLVM strip reference](https://llvm.org/docs/CommandGuide/llvm-strip.html).

## Toolchain facts independently checked

- NDK Clang supports direct invocation with an Android target/API triple. A generated single-purpose decoder need not force a CMake project on consumers. [NDK integration guide](https://developer.android.com/ndk/guides/other_build_systems).
- O-MVLL supports AArch64 and AArch32; its configuration is an LLVM pass plugin with Python support files and host-library requirements. Native Windows builds are not advertised as tested/provided. [O-MVLL introduction](https://obfuscator.re/omvll/introduction/), [setup](https://obfuscator.re/omvll/introduction/getting-started/).
- The release page identifies **O-MVLL 1.9.1 with NDK r29 compatibility**. WeKit uses r30. Therefore loading that binary into WeKit's selected compiler is not a verified combination. Exact compiler/plugin builds need pinning and a smoke test. The GitHub API request was rate-limited; the release HTML provided the compatibility statement. [Release 1.9.1](https://github.com/open-obfuscator/o-mvll/releases/tag/1.9.1).
- Native integration adds a **16 KiB page-size compatibility** obligation. Check ELF segment alignment and final APK packaging, and run on both page sizes. Newer NDK defaults help but are not evidence about a transformed final library. [Android page-size guide](https://developer.android.com/guide/practices/page-sizes).
- Android recommends explicit native registration and narrow exported symbols; name-based registration needs corresponding shrinker rules. [JNI guidance](https://developer.android.com/ndk/guides/jni-tips), [symbol visibility](https://developer.android.com/ndk/guides/symbol-visibility).
- Framework formatted resource calls obtain a format string and then format using resource configuration. Current Compose resource helpers use `LocalResources`, also confirmed in the locally cached AndroidX UI 1.12.0 sources. [Resources source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/Resources.java), [Compose source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/res/StringResources.android.kt).

External documentation describes a candidate design; only the local baseline tests and ASM mechanism probe above were executed. Device behavior, protection effectiveness, exact O-MVLL/r29 build identity, and full resource coverage remain validation gates.
