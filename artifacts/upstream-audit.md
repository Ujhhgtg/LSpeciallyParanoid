# LSPosed upstream audit

Reviewed 2026-09-26 against freshly fetched repository heads and the current LSpeciallyParanoid working tree, including the uncommitted native/resource implementation.

## Conclusion

There are **21 LSPosed-only commits**, but no missing decoder, encryption or bytecode-transform improvement to port. Androidacy already implements the two substantive plugin/JAR behaviors, and this fork uses newer dependencies and wrappers. Selectively applied two maintenance changes; no history merge or cherry-pick was performed.

The comparison is divergence, not a simple chronological age ranking: Androidacy has **109 commits absent from LSPosed**, while LSPosed has **21 absent from Androidacy**. Patch identity/history membership does not tell whether the resulting behavior has been independently implemented.

## Compared revisions

| Repository/reference | Commit |
|---|---|
| Androidacy master | [d60fc49159fbefa84d84ae5d3487534b136669b4](https://github.com/Androidacy/LSParanoid/commit/d60fc49159fbefa84d84ae5d3487534b136669b4) |
| LSPosed master | [35a9709e68e93fbde2b6c48dbee9c16dc7b7e422](https://github.com/LSPosed/LSParanoid/commit/35a9709e68e93fbde2b6c48dbee9c16dc7b7e422) |
| Common ancestor | `fedcb85ea6fd4ebc37bb9add9385fca3195a36a0` |
| Local committed baseline | `e908b78`, plus current working-tree changes |

Fetched into `audit-androidacy/master` and `audit-lsposed/master`. The existing branch and all earlier implementation changes were preserved.

## Changes applied

1. Adopted the Dependabot grouping from [3e36ba5](https://github.com/LSPosed/LSParanoid/commit/3e36ba50c7c8095885bdf67bc62d3d563344dfaf) in [.github/dependabot.yml](/home/ujhhgtg/coding/LSpeciallyParanoid/.github/dependabot.yml). Gradle dependency updates can now be reviewed together. Retained the existing schedule, registry configuration and pull-request limit.
2. Applied the `actions/setup-java@v4` migration from [90546f6](https://github.com/LSPosed/LSParanoid/commit/90546f65c89c8b06ef9c755de6fd718ea54c1e85) to the remaining v3 reference in [.github/workflows/publish-maven.yml](/home/ujhhgtg/coding/LSpeciallyParanoid/.github/workflows/publish-maven.yml). Main Android CI already used v4. The publishing JDK and publication behavior are unchanged.

These are maintenance improvements, not stronger obfuscation features.

## Functional and structural review

| Upstream change | Finding and disposition |
|---|---|
| `90546f6`: replace deprecated Kotlin compile options | Already present in Androidacy and [LSParanoidPlugin.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/gradle-plugin/src/main/java/dev/ujhhgtg/lsparanoid/plugin/LSParanoidPlugin.kt): `KotlinCompilationTask` with `compilerOptions.freeCompilerArgs`. Current task registration also uses `configureEach`. No code port needed. |
| `0b92001`: ignore duplicate root `module-info.class` entries | Already covered by [JarUtils.kt](/home/ujhhgtg/coding/LSpeciallyParanoid/processor/src/main/kotlin/dev/ujhhgtg/lsparanoid/processor/commons/JarUtils.kt). The fork's predicate is broader than upstream's exact-name check; this does not leave the upstream fix missing. Left current behavior unchanged. |
| `04b823e`: newer wrappers and working sample builds | Superseded. Root, samples and testApp use Gradle 9.8.0; wrapper JARs and launch scripts were checked as well as version properties. Local scripts already have the upstream CDPATH/Java-command/error-output fixes. |
| `90546f6`: fold samples into the root build and share its catalog | Organizational change, not a missing runtime fix. Keep standalone samples and testApp: they exercise published plugin/core/runtime artifacts and already build successfully. Porting the layout would require changing task paths and verification workflows without a demonstrated benefit. |
| `90546f6`: SDK/build-tools/annotation sample changes | Current SDK 37/tooling supersede its SDK 35 migration. The incidental compile-only annotation bump is not a demonstrated fix needed by these samples. |
| `35a9709`: include hidden files in artifact upload | Inapplicable. It fixes upstream's upload of `~/.m2`; current CI does not upload that directory. Do not add hidden-file inclusion to unrelated report/APK uploads. |
| All other version bumps | Superseded by this fork's newer toolchain/dependencies; see table below. |

The complete common-ancestor-to-LSPosed diff changes **no `core` source**. Its only `processor` change is the duplicate-JAR-entry condition. There is no upstream string algorithm, runtime decoder, native backend or resource obfuscator in these 21 commits to import. The `LSParanoidTask` diff is import cleanup.

## All 21 commits

| Commit | Change | Disposition |
|---|---|---|
| `fc17bcd` | Kotlin 1.9.23 | Superseded |
| `eaad264` | AGP 8.3.1 | Superseded |
| `6930163` | ASM 9.7 | Superseded |
| `d975315` | AGP 8.3.2 | Superseded |
| `e252665` | AGP 8.4.0 | Superseded |
| `682c74b` | AGP 8.5.0 | Superseded |
| `47dab95` | Kotlin 2.0.0 (commit subject is `---`) | Superseded |
| `b6478da` | AGP 8.5.1 | Superseded |
| `f91a7a8` | Kotlin 2.0.10 | Superseded |
| `c9a5420` | AGP 8.5.2 | Superseded |
| `e468aac` | AGP 8.6.0 | Superseded |
| `fc29d70` | Kotlin 2.0.20 | Superseded |
| `0c88c8b` | ASM 9.7.1 | Superseded |
| `61bf75d` | Kotlin 2.0.21 | Superseded |
| `04b823e` | Gradle/sample build fixes | Superseded |
| `130466f` | AGP 8.7.1 | Superseded |
| `91e1231` | AGP 8.7.2 | Superseded |
| `90546f6` | AGP/Kotlin API/CI/sample modernization | Functional parts already present; publishing action updated selectively |
| `3e36ba5` | Group dependency updates | Applied grouping |
| `0b92001` | Duplicate root module descriptor | Already implemented |
| `35a9709` | Upload hidden Maven artifacts | Inapplicable to current CI |

| Component | LSPosed tip | Current fork |
|---|---:|---:|
| AGP | 8.8.0 | 9.4.1 |
| Kotlin plugin | 2.1.10 | 2.4.20 |
| ASM | 9.7.1 | 9.10.1 |
| Grip | 0.9.1 | 0.11.0 |
| Main Gradle wrapper | 8.12.1 | 9.8.0 |

## Validation

Both modified YAML files were parsed and checked for the intended grouping/action values. `git diff --check` passed. No production code or dependency version was changed, so native compilation/device tests were not repeated for this maintenance-only edit. No publishing workflow was executed.

History commands used:

```bash
git rev-list --left-right --count audit-androidacy/master...audit-lsposed/master
git log --reverse audit-androidacy/master..audit-lsposed/master
git diff fedcb85ea6fd4ebc37bb9add9385fca3195a36a0..audit-lsposed/master
```
