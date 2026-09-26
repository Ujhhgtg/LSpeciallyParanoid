# LSpeciallyParanoid implementation plan

Date: 2026-09-26. Status: **initial implementation delivered**. See [implementation and validation](/home/ujhhgtg/coding/LSpeciallyParanoid/artifacts/implementation.md) for the completed scope, evidence and remaining platform/injected-runtime checks. The design below records the plan approved by the subsequent implementation request.

Evidence and validation limits are recorded in [investigation.md](/home/ujhhgtg/coding/LSpeciallyParanoid/artifacts/investigation.md). Remaining decisions are in [design-questions.md](/home/ujhhgtg/coding/LSpeciallyParanoid/artifacts/design-questions.md).

## 1. Decisions and scope

### Confirmed by the user

- Android AArch64 only; WeKit is the first real compatibility target.
- Support Xposed and Zygisk. Frida entry-mode compatibility is unnecessary; its removal is separate WeKit work.
- **No debugger, root, Frida, LSPosed, or other detection in this phase.**
- Protect supported string resources and report every exclusion.
- Separate identities **and ciphertext** for repeated string occurrences; no content deduplication.
- Randomize release builds. API/DSL compatibility may be broken.
- Prefer a decoder toolchain independent of WeKit's NDK; changing WeKit to r29 is acceptable if genuinely necessary.
- Minimize overhead, but measure first; no hard performance/size/build-time budget yet.
- Show concrete adapters before deciding whether their integration cost is acceptable.

### Proposed initial boundaries

Application-module output, Linux build host, API 28+ reference testing, AGP 9.4.1, and a standalone generated C/C++ decoder `.so`. Start with WeKit's standard/legacy builds and normal module-app execution. Keep the existing JVM backend as an explicitly selected development/desktop option. **Never silently fall back to it in a protected release.** Published protected AARs, independently loaded extension packs, macOS/Windows certification and AAB/dynamic-feature support are not claimed in the first release unless added to the acceptance matrix.

“Occurrence” means each emitted literal site or moved field initializer gets independent encrypted storage; each resource configuration/plural item/array item also gets independent storage. Multiple runtime reads of the same resource entry naturally reuse its token. Giving each resource retrieval call site separate ciphertext would require a different resource architecture and is left as an explicit question.

Only string-family resource values are candidates: strings, plurals, and string arrays. Styles, layouts, drawables, assets, serialization keys in protocols, and runtime annotations are not generically rewritten. XML and manifests are inspected to discover unsafe consumers, not encrypted as collateral work.

## 2. Protection objective

Make unattended static extraction less effective and increase the work required to build a reusable extractor for each protected release. Evaluate this against a rooted, instrumentable device and an analyst with source access and LLM assistance.

A native decoder still returns a Java String. Export hiding, encryption, names and control-flow transforms cannot make that plaintext inaccessible to the stated attacker. Consequently, success must be measured as **coverage and increased extraction effort**, not a promise that strings cannot be dumped. Do not base correctness on a secret algorithm, a hidden JNI method, or an undetectable runtime hook.

Measure at least: plaintext remaining in final DEX/resources/ELF; time and effort for a basic static extractor; ability to invoke the decoder for an identified token; and amount of plaintext obtainable during ordinary UI execution. Run these comparisons on our own small fixture before escalating protection complexity. No detection work is implied by this evaluation.

## 3. Architecture

Preserve the useful ASM pipeline and separate three concerns: deciding coverage, generating protected data, and executing native compilation.

```text
execution-time release entropy + variant/module identity
                       |
          +------------+------------------+
          |                               |
  early bootstrap names             resource inventory
  generated source                  + selected candidates
          |                               |
          |                      token overlay + resource records
          |                               |
          +-------> AAPT2 / R / JVM compilation
                                          |
                               class transform + usage validation
                               patched classes + literal records
                                          |
 resource records ------------------------+
                                          |
                              immutable protected-data assembly
                                          |
                              decoder source + encrypted payload
                                          |
                              pinned NDK / optional LLVM pass
                                          |
                              strip + ELF inspection + jniLibs
                                          |
                              APK packaging + artifact audit
```

The resource task must not depend on compiled classes that themselves require its output. Pre-compilation selection uses source/resource inventory and an explicit supported-use contract. A post-compilation check can reject a selected entry that escaped that contract; it must not mutate an already consumed resource table. Known unsupported entries are excluded early and reported. Newly discovered incompatible usage produces an actionable build failure requiring exclusion/reclassification, rather than shipping tokens or pretending protection succeeded.

Use immutable task outputs, unique task output directories and provider dependencies. No shared mutable Gradle registry, global daemon singleton, custom executor pool or speculative concurrency machinery. Apply the repository's concurrency criteria before treating any interleaving as a defect.

### Proposed module responsibilities

| Area | Change |
|---|---|
| `processor` | Preserve analysis/patching entry points; separate JVM and native generation; rebuild constant pools; emit versioned literal records; implement coverage reports and resource-call validation independently from literal class filters. |
| `core` | Keep annotation/JVM functionality usable for host tests. Avoid making the processor depend on Android framework runtime classes. |
| New small Android runtime module | Token parser, string-family resource bridge/decorator, native bootstrap support. No global app Context or locale cache. |
| `gradle-plugin` | Entropy, bootstrap generation, resources, class transform, record assembly, native compile, hardening, and reporting tasks with declared inputs/outputs. |
| Native templates/resources | A fixed reviewed decoder implementation plus generated data; pass configuration and linker export map. Avoid generating thousands of decryptor functions. |
| Fixtures/test application | Generated-class verification, minified Android/JNI execution, resource semantics and loading scenarios. |

A public DSL rename is allowed, but choose the new plugin coordinates before publishing. Proposed grouping: backend, string coverage, resources, native toolchain, and diagnostics. Do not expose many protection presets before measured differences justify them.

## 4. Native backend and string format

### Establish a correct bridge first

1. Retain the `(long) -> String` patching interface initially.
2. Generate a public bridge class and public static native method usable by transformed classes in arbitrary packages. The transcript's package-private Java sketch is insufficient for that access pattern.
3. Register functions from `JNI_OnLoad`; generate exact keep rules for the randomized class and method names, plus bootstrap reachability. Wire through AGP's public `Variant.proguardFiles` API and verify in a minified build.
4. Port the existing format only as a differential-test milestone. Use unsigned arithmetic with the original truncation/rotation behavior and JNI `NewString` for UTF-16 code units. Do not ship this compatibility format as the final hardening result.
5. Validate token, offset and length arithmetic before native reads. Handle allocation failure and pending JNI exceptions explicitly; avoid allowing C++ exceptions across JNI. Keep plaintext allocations bounded to the requested entry and erase the temporary native buffer with a method verified to survive optimization.
6. Emit no decoder or native compile work when the selected registry is empty, unless an explicitly required runtime feature needs it. Test empty variants and empty strings separately.

Android documents explicit registration and restricted exports as standard JNI practice. [JNI guidance](https://developer.android.com/ndk/guides/jni-tips), [symbol visibility](https://developer.android.com/ndk/guides/symbol-visibility).

### Ship a versioned replacement format

- Independent random-looking 64-bit IDs, independent per-record nonces, explicit lengths and offsets, and a per-build key. No seed embedded in IDs and no reused keystream for distinct records.
- Recommended initial cryptographic candidate: a reviewed small ChaCha20-Poly1305 implementation with known-answer tests, UTF-16 bytes defined in the format, and record identity/version/length bound as associated data. Authentication detects damaged or mismatched records; it is not a security boundary against someone who controls the process and can obtain the key. Use the construction and test vectors in [RFC 8439](https://www.rfc-editor.org/rfc/rfc8439).
- Select the exact implementation/dependency after measuring footprint, checking its maintenance and redistribution terms, and comparing native/JVM fixture outputs. Do not invent an encryption primitive. A less expensive unauthenticated format is a separate decision, not an accidental omission.
- Separate ciphertext even for identical text. Deterministically assign per-record nonce counters within each domain of a fresh build key, or collision-check generated nonces. Domain-separate literal/resource IDs and keys to prevent cross-task collisions.
- Avoid embedding plaintext in native source literals, symbol names, logs or public reports. Intermediate build records remain private build outputs; the source repository already contains the original text.
- Start with a compact sorted ID index and binary search. Table offsets/lengths are not secrets. Encrypting/permuting more metadata is an experiment only if extraction tests show a benefit; do not scan the entire table per lookup.
- No persistent plaintext cache initially. Measure JNI and decrypt costs before choosing a bounded opt-in cache. Runtime Java Strings remain subject to the ordinary application's lifetime.

Randomization policy: one execution-time entropy task per built protected variant, always executed and not cache-restored, supplies every dependent task in that invocation. A new build invocation deliberately changes protected outputs and reduces cache reuse. Save the build identifier, format version, toolchain fingerprint and private symbol association for diagnosis. Tests may inject fixed entropy. Never derive a 256-bit key solely from the old 32-bit seed or from the current clock.

## 5. Concrete WeKit adapter proposal

These are proposed integration shapes, not implemented APIs. They are included so the user can approve a specific integration cost.

### A. Generated bootstrap with two loading modes

Generate a tiny app-local bootstrap source before compilation, with a stable facade name and randomized internal bridge/library names. Exclude the bootstrap and its load-path dependencies from native literal transformation. The stable facade is an integration convenience, not a claimed protection boundary.

```kotlin
// Proposed generated API, illustrative only.
LspBootstrap.loadInstalled()             // normal module app process
LspBootstrap.loadAbsolute(decoderFile)    // injected process, existing module loader
LspBootstrap.libraryFileName             // generated lib<name>.so
```

`loadAbsolute` invokes `System.load` from the module's classloader; the generated library registers the corresponding bridge there. External-bootstrap mode has no automatic bridge `<clinit>` that recursively tries to load the same library.

For installed Xposed execution, extend `NativeLoader` to resolve the generated filename beside the existing installed libraries. For Zygisk, extend the existing extraction list to include that exact APK entry and load it from the extracted file. Do not load arbitrary `.so` entries: WeKit also packages executables under native-library-like names. Preserve the existing extraction permissions and packaging flow.

Initialize as soon as each entry path has the module path/classloader information, before entering protected feature code. Exclude and report the transitive bootstrap path (entry initialization, essential loader/error/logging helpers) until it is proven not to decode prematurely. An unused loader method containing protected text is not itself a boot problem; an actually executed initializer is. Map the execution path rather than excluding the whole app indiscriminately.

Normal module-app startup gets its own early initialization point. Any standalone child process executing protected classes needs equivalent initialization or an explicitly separate development backend. Zygote-time decoding is outside the initial contract; do not load app decoder state into the zygote by accident.

### B. Decode beneath WeKit's existing localization wrapper

The smallest promising integration is a **resource-only decorator at the already controlled localization boundary**, not a system-wide hook and not a universal replacement for Android Resources.

In [LocalizedContextFactory.kt](/home/ujhhgtg/coding/WeKit/app/src/main/java/dev/ujhhgtg/wekit/i18n/LocalizedContextFactory.kt), after configuring/injecting the resources but before the Meow wrapper:

```kotlin
// Proposed replacement for the final return block; illustrative only.
val decoded = LspResourceContext(localized)
return if (locale == SupportedLocale.MEOW_CHINESE) {
    MeowResourcesContext(decoded)
} else {
    decoded
}
```

The intended order becomes:

```text
Android selects module resource using current configuration
 -> LSP decodes the selected token
 -> formatting uses that configuration
 -> Meow applies its existing text transformation
 -> existing Compose/imperative consumer receives text
```

This preserves `LocalContext` as the platform context. `WeKitLocaleProvider` already publishes the localized resource object as `LocalResources`, so ordinary Compose string/plural/array functions can keep their source APIs. It also avoids manually manufacturing Compose compiler flags. The decorator must cover the used string/text/plural/array overloads, including default-returning text APIs, while retaining pass-through behavior for host resources and non-token values.

Do not claim this covers raw `HostInfo.application.getString`, other contexts, framework XML resolution, `TypedArray`, `getValue`, or code that bypasses the localized factory. Inventory those paths; either transform a supported getter at the caller, use an explicit resource helper, or exclude its referenced resource. A thin explicit adapter is preferable to patching every Compose dependency and rewriting unknown overridden methods.

The Resources subclass constructor is deprecated and this design uses it only at a boundary where WeKit already employs such wrappers. Prototype API 28/current Android locale updates and identity behavior before accepting it. If it cannot preserve those semantics, use explicit runtime helpers plus a small Compose adapter compiled with the actual Compose toolchain. Resource-only wrappers must not be passed as substitutes for Activity/window contexts.

**Expected first integration footprint:** one bootstrap entry in each selected loading path, one generated-library entry in installed/Zygisk loading, one localized-factory insertion, and explicit exceptions discovered by the resource inventory. This is a proposal to validate, not a promise of a fixed line count.

## 6. Resource build pipeline

1. Inventory all relevant `values*/*.xml` files, not only files named `strings.xml`. Resolve flavor/build-type priority per resource key and full qualifier set. Diagnose conflicting definitions at equal priority.
2. Establish the supported source set: first-party application resources first. Report generated/dependency inputs that cannot be observed correctly. AGP `res.static` omits generated sources; there is no public `SingleArtifact.MERGED_RES` hook in the inspected API. Prove the source task graph in a small fixture before committing to production overlays. [Layered source API](https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/variant/SourceDirectories.Layered).
3. Inspect the merged manifest and all relevant XML references, resource aliases and references inside arrays/plurals. Propagate exclusions through aliases and across configurations where a resource's consumer is unsupported. Do not encrypt an alias as if its `@string/...` spelling were displayed text.
4. Normalize supported values against AAPT2 semantics. Prototype a private AAPT2-compiled resource representation as the extraction oracle; validate quoting, escapes, whitespace, formatting metadata and references. Do not parse human-readable `aapt2 dump` output as a durable production format. If a maintainable compiled-data path is unavailable, start with a clearly reported supported syntax subset and fail/skip the rest according to selection policy.
5. Replace each supported concrete value with a versioned, build-scoped token, preserving every qualifier and plural quantity/array position. Preserve `translatable`, formatting behavior and other relevant declarations. No token should contain plaintext, a plaintext hash used as a stable identifier, or the decryption key.
6. Emit immutable resource records separately from literal records; combine them at native assembly time. Never serialize a live registry between task instances.
7. Let Android choose the configuration, plural branch, and array. Decode the selected value. For formatting APIs decode the raw selected format before formatting; do not format the token. Preserve the relevant resource locale and existing override behavior.
8. Strictly recognize this build's token grammar and namespace. Pass normal strings through; reject mismatched/corrupt own-build tokens with useful diagnostics. Do not recursively decode a decrypted string that happens to resemble a token.
9. Recreate resource-only wrappers on configuration changes through the existing localized factory. Do not cache by numeric resource ID alone; locale, qualifier, source and build identity matter.
10. Inspect final resource tables and DEX with known fixture sentinels. An overlay that looks correct in generated XML is insufficient evidence that original plaintext is absent from packaged tables.

Known exclusions: manifest labels/descriptions/Xposed scope metadata; unsupported XML/system consumers; styled values until span support is real; unresolved generated/dependency entries; unknown resource-ID escapes into unmodified code; and pre-bootstrap reads. Reports list resource identity/configuration, reason and source/consumer locations, not plaintext. Text also used in an intentionally public resource can still appear publicly; report that residual exposure rather than claiming complete byte removal.

No generic resource-name collapsing in the first release. Revisit it separately with string-type selection, `getIdentifier`/name consumers, package identity, and pre-signing packaging tests. [AAPT2 optimization options](https://developer.android.com/tools/aapt2).

## 7. Compiler and ELF handling

Use a task-local NDK toolchain selection independent of the consuming app's native build. Candidate: an exact NDK r29 revision matched to O-MVLL 1.9.1; verify the exact binary/compiler pairing before pinning it. WeKit's NDK r30 remains untouched unless the independent decoder build proves unsuitable. [O-MVLL release compatibility](https://github.com/open-obfuscator/o-mvll/releases/tag/1.9.1).

Invoke NDK Clang with `--target=aarch64-linux-android<minSdk>` via injected Gradle `ExecOperations`, using declared toolchain/config/template inputs. Prefer C or a restricted C++ subset with explicit allocation to avoid an unnecessary `libc++_shared.so` dependency in a host process. Do not compile against private Android symbols.

First make an ordinary native build work; then enable selected O-MVLL passes on resolution/key/decrypt routines. Keep JNI entry/registration and allocation/error paths straightforward unless a measured need justifies otherwise. Audit post-optimization output and pass logs so inlining or version mismatch cannot silently leave all intended functions unprotected. Pin Python support files and plugin contents as build inputs. No automatic unsigned “latest” compiler/plugin download during an ordinary release build.

Baseline ELF policy: PIC, hidden visibility, a version script with only the intended JNI entry exported, appropriate dead-section elimination, release stripping, and separately retained symbols/build identification. Preserve loadable/dynamic structures, unwind data, required notes/properties and 16 KiB compatibility. Verify both pre-packaging and extracted final APK libraries, including AGP's own stripping stage. [NDK direct builds](https://developer.android.com/ndk/guides/other_build_systems), [page sizes](https://developer.android.com/guide/practices/page-sizes).

Do not remove the section-header table, zero `e_shstrndx`, poison section sizes, or strip all notes indiscriminately. Use standard LLVM inspection plus targeted invariants and actual load tests instead of maintaining a duplicate Android linker validator. Optional section-name randomization is a later experiment only if it measurably improves resistance and survives packaging/symbolication. [Bionic source](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp), [LLVM strip behavior](https://llvm.org/docs/CommandGuide/llvm-strip.html).

## 8. Implementation sequence and exit gates

| Phase | Deliverable | Exit gate |
|---|---|---|
| 0 — Baseline and coverage | Generated ASM fixtures, plaintext/coverage inventory, constant-pool rebuilding, accurate keep-rule/test docs, sanitized routine logs. | Generated classes load and preserve values; selected plaintext absent from their constant pools; unsupported string locations explicitly reported; current 129 tests remain green. |
| 1 — Feasibility spikes | Bootstrap/classloader fixture; resource overlay task graph + AAPT2 normalization fixture; exact NDK/O-MVLL compiler smoke test. | No task cycles, correct locale/format behavior, JNI library loads in ordinary and representative injected classloaders. Document failed assumptions before broad implementation. |
| 2 — Native literal vertical slice | Native generator, compile task, generated JNI keep rules and APK integration, old-format differential decoder. | Minified arm64 test app runs generated calls with empty, long, NUL, surrogate and non-ASCII strings; wrong library/ABI fails clearly. Old format remains a temporary verification backend. |
| 3 — New format and randomization | Per-occurrence records, chosen encryption implementation, release entropy task, versioned payload/index, private diagnostics. | Known-answer and differential tests pass; repeated values have separate IDs/nonces/ciphertext; two release invocations differ consistently; fixed test entropy is deterministic. |
| 4 — Resource vertical slice | Tokenization of verified plain strings/plurals/arrays and runtime helpers; exclusions report. | Default/Chinese variants, aliases, escaping, formatting, arrays, locale changes, unsupported consumers and final-table absence verified. No visible tokens. |
| 5 — WeKit pilot | Proposed bootstrap and localization adapters, Xposed legacy/modern and Zygisk integration. | Real-device manual checks in normal module app and WeChat, both flavors, normal/meow locales, startup/feature paths and supported host modes. Existing host behavior retained. |
| 6 — LLVM hardening | Pinned selectively obfuscated compiler path, conservative ELF policy and final-artifact inspection. | Same behavior as unobfuscated native backend; intended passes actually apply; 4/16 KiB device checks; matching private symbols; measured build/startup/runtime/size costs. |
| 7 — Release decision | Extraction comparison, scope report, documentation, example app and migration instructions. | User reviews measured benefit, integration footprint, exclusions and costs; unresolved compatibility claims remain out of the advertised matrix. |

Do not publish the intermediate old-format native port as the promised stronger product. If the resource feasibility spike fails, retain native literal progress and explicitly revise resource scope rather than quietly omitting i18n protection.

## 9. Validation matrix

- **JVM/codegen:** generated decoder execution, fresh constant pools, class verification, constructors/static initializers, interfaces/constants, large corpora, duplicate occurrences, untouched annotations, dependency concat recipes and string identity behavior. Existing deobfuscation already produces fresh Strings; native work must not introduce additional semantic differences or silently promise literal interning.
- **Gradle:** clean build, second invocation with fresh release entropy, configuration-cache reuse, fixed-entropy fixture cache hits, variant isolation, changed resources/NDK/pass config invalidation, zero selected records, plugin ordering and consumer without other JNI libraries. Test the public generated-resource/jniLib APIs instead of relying on task-name ordering.
- **Resources:** compare with an unprotected fixture on-device; default/zh-CN/zh-TW plus test languages with nontrivial plurals; full qualifiers, aliases, escapes, `%` formatting, array references, explicit locale vs device locale, Meow transformation, null/default text behavior, host-resource pass-through and exclusion reports.
- **Native:** known-answer crypto, JNI UTF-16 equivalence, bounded invalid-input handling, OOM/error paths, exported symbols/imports, absence of accidental plaintext, stripping/package survival, API floor/current Android, 4/16 KiB pages. Host-native tests may help the pure decoder but do not substitute for arm64 Android loading.
- **WeKit:** use its established build commands and manual device workflow. Xposed/LSPosed presence is expected. Confirm loader lists, earliest protected initializer, installed versus copied Zygisk APK, module-app entry, both flavors, localized Compose/imperative UI and all actually used child-process paths. Do not add simulated WeChat unit tests or run unrelated expensive DexKit corpora.
- **Attacker-cost comparison:** compare existing JVM, native-only, new-format native, and selectively obfuscated native builds on identical fixture coverage. Evaluate static extraction and observable plaintext at the runtime boundary. Drop optional tricks with negligible measured benefit.

No APK larger than 100 MiB is ever passed to jadx. Prefer AAPT2/apktool, ZIP inspection, DEX-specific readers and LLVM tools. No device installs or WeChat changes are part of this planning deliverable.

## 10. Delivery rules

Implementation changes should be reviewable in small Conventional Commit units (`test:`, `fix:`, `feat:`, `docs:` as appropriate), with each phase's evidence recorded. The implementation is currently available as working-tree changes for review.

Keep LSpeciallyParanoid planning artifacts in this directory. If actual WeKit development follows, its own development artifacts belong in `~/coding/wekit_dev/artifacts` per its AGENTS.md. Preserve its existing working-tree changes and do not remove its Frida entry point as a side effect of this project.
