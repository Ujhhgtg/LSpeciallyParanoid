/*
 * Copyright 2021 Michael Rozumyanskiy
 * Copyright 2023 LSPosed
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.ujhhgtg.lsparanoid.plugin

import com.android.build.api.variant.Variant

/** Native protection is explicit so JVM-only consumers and desktop tooling remain usable. */
open class LSParanoidExtension {
    var seed: Int? = null
    var classFilter: ((className: String) -> Boolean)? = null
    var includeDependencies: Boolean = false
    var variantFilter: (Variant) -> Boolean = { true }
    var backend: String = "jvm"
    var automaticLoading: Boolean = true
    var nativeNdkVersion: String = "29.0.14206865"
    /** Required for protected native variants; null selects the pinned setup-tool installation. */
    var omvllPlugin: String? = null
    /** Override for custom installations; the pinned default's bundled Python is selected automatically. */
    var omvllPythonPath: String? = null
    /** Optional explicit certificate pins; otherwise derived from this variant's signing keystore. */
    var signerCertificateSha256: Set<String> = emptySet()
    /** Additional injected host package -> signing certificate SHA-256 pins. Own app is implicit. */
    var allowedHostCertificates: Map<String, Set<String>> = emptyMap()
    /** Explicit resource identities (string/name, plurals/name, array/name) or glob patterns. */
    var resourceIncludes: Set<String> = emptySet()
    var resourceExcludes: Set<String> = emptySet()
    /** Acknowledges that selected resources are only read through the decoding resource context. */
    var wrappedResourceAccess: Boolean = false
    /** Hard exclusions also override @Obfuscate; use for code executed before native bootstrap. */
    var excludedClassPrefixes: Set<String> = emptySet()
}
