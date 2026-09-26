# Design decisions and remaining questions

Updated 2026-09-26 after clarification and implementation. The subsequent request to implement the plan authorized its concrete adapters and initial defaults. See [implementation status](/home/ujhhgtg/coding/LSpeciallyParanoid/artifacts/implementation.md). The questions below are retained as review/future-scope decisions, not implementation blockers.

## Answers already incorporated

| Topic | User decision |
|---|---|
| Attacker | Rooted device, Frida, LSPosed, LLM assistance. |
| Reference application | WeKit. |
| Resource exclusions | Protect supported resources and report every exclusion. |
| Execution modes | Support Xposed and Zygisk; ignore WeKit Frida-mode compatibility because that mode will be removed. |
| Detection | None in this phase. |
| App adapters | Concrete plan adapters implemented following the implementation request; see implementation status. |
| Performance | Reduce overhead; no hard limit yet; measure and review. |
| NDK | Prefer solving inside LSpeciallyParanoid; WeKit r29 downgrade is acceptable if necessary. |
| Duplicate literals | Separate storage rather than deduplication. |
| Release variability | Randomized. |
| API compatibility | Breaking changes allowed. |

## Highest-impact review questions

1. **Is the proposed WeKit adapter footprint acceptable?** It adds an explicit decoder entry to the existing installed/Zygisk loaders and inserts a decoding resource context below `MeowResources` in `LocalizedContextFactory`. This preserves ordinary Compose calls. Alternative: require Gradle-only installation and accept substantially more bytecode/dependency transformation work. Recommendation: approve the small adapter after its fixture proves correct.

2. **What must become materially harder to extract?** WeKit has visible UI translations, hook matching strings, feature technical IDs, protocol names, and internal diagnostics. Which of these matters most? A rooted observer can read UI text while using the app. Recommendation: cover selected strings broadly but prioritize measurement around non-UI internal literals; avoid equating encrypted translations with hidden program logic.

3. **How should resource coverage be reported when use is uncertain?** Static analysis cannot prove every computed ID is confined to intercepted readers. Recommendation: exclude known unsafe resources early with reasons; fail if later validation finds a selected resource escaping the supported boundary. Is requiring an explicit exclusion for that case acceptable?

4. **Does “separate per occurrence” include each source-level read of the same resource?** Proposed meaning: independent literal sites and independent resource configuration/items, while reads of one resource entry share its token. Per-read resource ciphertext requires another indirection/design and larger data. Recommendation: accept the proposed meaning initially.

5. **Is fresh randomization on every release build invocation intentional even for no source changes?** It changes outputs and prevents most protected-output cache reuse. Recommendation: yes, following your answer; share one fresh entropy output throughout each variant build and retain matching private symbols. A randomized release identifier supplied by CI would preserve more cache reuse but changes that contract.

6. **May desktop and ordinary development variants use the existing JVM decoder or disable protection?** WeKit's desktop DexKit workers run on a non-Android JVM. Recommendation: keep a separate explicit development backend and run native release validation on arm64 Android. Do not package a JVM fallback with protected release strings.

## Scope decisions that can use provisional defaults

7. **Build hosts:** Linux only initially, or macOS/Windows too? Recommendation: certify Linux first; additional host support needs exact O-MVLL/compiler packaging. Target ABI support and build-host support are separate choices.

8. **Minimum Android/AGP support:** should the plugin promise anything older than WeKit's API 28 and AGP 9.4.1? Recommendation: validate those first and publish an explicit matrix before expanding it.

9. **Application-only versus protected AARs:** must an independently published AAR carry native code and resource tokens usable without this plugin in the consuming app? Recommendation: application builds first. AARs introduce ownership, duplicate processing, bridge naming, native packaging and shrinker propagation contracts.

10. **Dynamic extension packs and child processes:** should separately loaded packs get separate registries/libraries in v1, and which child processes run protected module classes? Recommendation: protect the app's own payload first, pass foreign resources through, and inventory actual child-process startup. Do not infer that Zygisk support solves every classloader arrangement.

11. **Resource family scope:** are plurals and string arrays part of “string resources”? The conversation discusses them and WeKit uses both. Recommendation: include all three plain-text families; exclude unsupported spans and public Xposed metadata.

12. **Compiler provisioning:** is a one-time pinned toolchain installation acceptable, or must the plugin fetch its compiler/pass dependencies automatically? Recommendation: explicit setup with verified checksums and no ordinary build-time “latest” download; no WeKit NDK change by default.

13. **New public naming:** should the plugin ID become `dev.ujhhgtg.lspeciallyparanoid`, with a similarly named DSL and coordinates? Breaking changes are allowed, but an exact published name is still a user-facing choice. Recommendation: pick once before the first new release; avoid multiple permanent aliases without demand.

14. **Native error behavior:** on a missing library, invalid own-build token or incompatible payload, should initialization fail visibly, or should the module disable protected features? Recommendation: explicit initialization failure flowing through WeKit's existing error handling; no host termination policy and no silent plaintext fallback. This is decoder correctness behavior, not detection.

15. **Private diagnostics retention:** how long should build identifiers, native symbols, R8 mappings and coverage reports be kept, and is there a private CI artifact store? Recommendation: retain matching symbols/mappings for shipped builds; avoid putting plaintext or keys into public reports/releases.

16. **ELF experiments:** is valid-ELF section-name randomization worth a later benchmark, or should it be dropped unless ordinary hardening proves insufficient? Recommendation: defer it; exclude malformed ELF and unwind removal from supported builds.

17. **Performance review:** which device and representative screens/features should define baseline costs? Recommendation: measure WeChat module startup, first protected string, a realistic settings list, repeated formatting, APK growth and clean release build time. Establish budgets from those results rather than selecting arbitrary thresholds now.

The current request is fulfilled by the investigation and plan. No implementation changes to either project, publishing, Frida-mode removal, or device testing are implied by this question list.
