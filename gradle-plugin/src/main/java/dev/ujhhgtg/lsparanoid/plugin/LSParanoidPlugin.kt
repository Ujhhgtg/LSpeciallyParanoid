/*
 * Copyright 2020 Michael Rozumyanskiy
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

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts.Scope
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.security.SecureRandom

class LSParanoidPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("lsparanoid", LSParanoidExtension::class.java)
        project.plugins.withId("com.android.application") { configureAndroid(project, extension) }
        project.plugins.withId("com.android.library") { configureAndroid(project, extension) }
        project.tasks.withType(JavaCompile::class.java).configureEach {
            it.options.compilerArgs.add("-XDstringConcat=inline")
        }
        project.tasks.withType(KotlinCompilationTask::class.java).configureEach {
            it.compilerOptions.freeCompilerArgs.add("-Xstring-concat=inline")
        }
    }

    @Suppress("UnstableApiUsage")
    private fun configureAndroid(project: Project, extension: LSParanoidExtension) {
        project.dependencies.add("implementation", "dev.ujhhgtg.lsparanoid:core:${Build.VERSION}")
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.finalizeDsl { dsl ->
            if (extension.backend == "native") {
                require(dsl is ApplicationExtension) { "Native protection currently supports application modules, not published AARs" }
                val filters = dsl.defaultConfig.ndk.abiFilters
                require(filters.isEmpty() || filters == setOf("arm64-v8a")) { "Native string protection supports arm64-v8a only" }
                filters.add("arm64-v8a")
                for (configuration in dsl.productFlavors.map { it.ndk } + dsl.buildTypes.map { it.ndk }) {
                    require(configuration.abiFilters.all { it == "arm64-v8a" }) {
                        "Native string protection does not support additional flavor/build-type ABIs"
                    }
                }
                if (extension.resourceIncludes.isNotEmpty()) {
                    require(dsl.buildTypes.none { it.isPseudoLocalesEnabled }) {
                        "Protected resource tokens cannot be pseudolocalized; disable isPseudoLocalesEnabled for protected builds"
                    }
                }
            }
        }
        var runtimeAdded = false
        components.onVariants { variant ->
            if (!extension.variantFilter(variant)) {
                if (variant is ApplicationVariant) {
                    val stub = project.tasks.register("lspJvmBootstrap${variant.name.replaceFirstChar { it.uppercase() }}", JvmBootstrapTask::class.java) {
                        it.sources.set(project.layout.buildDirectory.dir("intermediates/lspeciallyparanoid/${variant.name}/bootstrap"))
                    }
                    variant.sources.java!!.addGeneratedSourceDirectory(stub, JvmBootstrapTask::sources)
                }
                return@onVariants
            }
            val backend = extension.backend
            // Snapshot variant options: later variantFilter callbacks may change the extension.
            val classFilter = extension.classFilter
            val seed = extension.seed
            val includeDependencies = extension.includeDependencies
            val automaticLoading = extension.automaticLoading
            val excludedPrefixes = extension.excludedClassPrefixes.toSet()
            val resourceIncludes = extension.resourceIncludes.toSet()
            val resourceExcludes = extension.resourceExcludes.toSet()
            val wrappedResourceAccess = extension.wrappedResourceAccess
            val defaultOmvll = project.providers.systemProperty("user.home").get() +
                "/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1"
            val omvllPlugin = extension.omvllPlugin ?: if (backend == "native") "$defaultOmvll/omvll-ndk.so" else null
            val omvllPythonPath = extension.omvllPythonPath ?:
                if (backend == "native" && extension.omvllPlugin == null) "$defaultOmvll/Python-3.10.7/Lib" else null
            val explicitSignerPins = extension.signerCertificateSha256.toSet()
            val hostCertificates = extension.allowedHostCertificates.mapValues { it.value.toList() }
            require(backend == "jvm" || backend == "native") { "lsparanoid.backend must be jvm or native" }
            require(backend == "native" || resourceIncludes.isEmpty()) { "Resource protection requires the native backend" }
            val capitalized = variant.name.replaceFirstChar { it.uppercase() }
            val identity = "${project.rootProject.name}:${project.path}:${variant.name}"
            val base = "intermediates/lspeciallyparanoid/${variant.name}"
            val transform = project.tasks.register("lsparanoid$capitalized", LSParanoidTask::class.java) {
                it.bootClasspath.set(components.sdkComponents.bootClasspath)
                it.classpath = variant.compileClasspath
                it.seed.set(seed ?: if (backend == "jvm") SecureRandom().nextInt() else 0)
                it.classFilter = classFilter
                it.projectName.set(identity)
                it.applicationNamespace.set(variant.namespace)
                it.protectedApplicationId.set(if (variant is ApplicationVariant) variant.applicationId else variant.namespace)
                it.allowedHostCertificates.set(hostCertificates)
                it.backend.set(backend)
                it.requireNativeRuntime.set(!automaticLoading)
                it.coverageReport.set(project.layout.buildDirectory.file("reports/lspeciallyparanoid/${variant.name}/strings.tsv"))
                it.excludedClassPrefixes.set(excludedPrefixes)
                it.nativeSources.set(project.layout.buildDirectory.dir("$base/native-source"))
            }
            variant.artifacts.forScope(if (includeDependencies) Scope.ALL else Scope.PROJECT)
                .use(transform).toTransform(ScopedArtifact.CLASSES, LSParanoidTask::jars, LSParanoidTask::dirs, LSParanoidTask::output)
            if (backend == "jvm" && variant is ApplicationVariant) {
                val bootstrap = project.tasks.register("lspJvmBootstrap$capitalized", JvmBootstrapTask::class.java) {
                    it.sources.set(project.layout.buildDirectory.dir("$base/bootstrap"))
                }
                variant.sources.java!!.addGeneratedSourceDirectory(bootstrap, JvmBootstrapTask::sources)
            }
            if (backend == "native") {
                require(variant is ApplicationVariant) { "Native protection currently supports application modules, not published AARs" }
                require(variant.minSdk.apiLevel >= 28) { "Native protection currently requires minSdk 28 or newer" }
                // The verifier checks APK v2 signed contents itself rather than trusting PackageManager's signer fields.
                variant.signingConfig.enableV2Signing.set(true)
                val android = project.extensions.getByType(ApplicationExtension::class.java)
                val signing = variant.buildType?.let { android.buildTypes.findByName(it)?.signingConfig }
                    ?: variant.productFlavors.firstNotNullOfOrNull { (_, name) -> android.productFlavors.findByName(name)?.signingConfig }
                    ?: android.defaultConfig.signingConfig
                require(explicitSignerPins.isNotEmpty() || signing != null) {
                    "Native verification requires a signed APK; configure signingConfig or explicit signerCertificateSha256"
                }
                val signer = project.tasks.register("lspSigner$capitalized", NativeSigningTask::class.java) {
                    it.explicitPins.set(explicitSignerPins)
                    if (explicitSignerPins.isEmpty()) {
                        it.keyStoreFile.set(signing!!.storeFile)
                        it.keyAlias.set(signing.keyAlias)
                        it.storePassword.set(signing.storePassword)
                        // AGP owns creation of the default debug keystore; never generate or replace it ourselves.
                        it.dependsOn("validateSigning$capitalized")
                    }
                    it.certificatePins.set(project.layout.buildDirectory.file("$base/public/signer-sha256.txt"))
                }
                transform.configure { it.signerPins.set(signer.flatMap { t -> t.certificatePins }) }
                val toolchain = project.tasks.register("lspToolchain$capitalized", ValidateNativeToolchainTask::class.java) {
                    it.pluginPath.set(omvllPlugin!!)
                    omvllPythonPath?.let { path -> it.pythonPath.set(path) }
                }
                val entropy = project.tasks.register("lspEntropy$capitalized", NativeEntropyTask::class.java) {
                    it.dependsOn(toolchain)
                    it.output.set(project.layout.buildDirectory.file("$base/private/entropy.bin"))
                }
                val bootstrap = project.tasks.register("lspBootstrap$capitalized", NativeBootstrapTask::class.java) {
                    it.entropy.set(entropy.flatMap { t -> t.output })
                    it.moduleIdentity.set(identity)
                    it.automaticLoading.set(automaticLoading)
                    it.sources.set(project.layout.buildDirectory.dir("$base/bootstrap"))
                    it.keepRules.set(project.layout.buildDirectory.file("$base/bridge.pro"))
                }
                variant.sources.java!!.addGeneratedSourceDirectory(bootstrap, NativeBootstrapTask::sources)
                variant.proguardFiles.add(bootstrap.flatMap { it.keepRules })
                transform.configure { it.entropy.set(entropy.flatMap { t -> t.output }) }
                var resourceTask: org.gradle.api.tasks.TaskProvider<ResourceProtectionTask>? = null
                if (resourceIncludes.isNotEmpty()) {
                    require(wrappedResourceAccess) {
                        "Set wrappedResourceAccess=true only after selected resources are confined to LspResourceContext (see resource coverage contract)."
                    }
                    if (!runtimeAdded) {
                        project.dependencies.add("implementation", "dev.ujhhgtg.lsparanoid:runtime:${Build.VERSION}")
                        runtimeAdded = true
                    }
                    val sources = requireNotNull(variant.sources.res)
                    val resources = project.tasks.register("lspResources$capitalized", ResourceProtectionTask::class.java) {
                        it.sourceDirectories.set(sources.static.map { layers -> layers.flatten() })
                        it.priorities.set(sources.static.map { layers -> layers.flatMapIndexed { i, dirs -> dirs.map { layers.size - i } } })
                        it.manifests.set(variant.sources.manifests.all)
                        it.entropy.set(entropy.flatMap { t -> t.output })
                        it.moduleIdentity.set(identity)
                        it.includes.set(resourceIncludes)
                        it.excludes.set(resourceExcludes)
                        it.aapt2.set(components.sdkComponents.aapt2.flatMap { tool -> tool.executable })
                        it.overlay.set(project.layout.buildDirectory.dir("$base/res"))
                        it.records.set(project.layout.buildDirectory.file("$base/private/resources.bin"))
                        it.report.set(project.layout.buildDirectory.file("reports/lspeciallyparanoid/${variant.name}/resources.tsv"))
                        it.workspace.set(project.layout.buildDirectory.dir("$base/resource-work"))
                    }
                    resourceTask = resources
                    sources.addGeneratedSourceDirectory(resources, ResourceProtectionTask::overlay)
                    transform.configure {
                        it.resourceRecords.set(resources.flatMap { t -> t.records })
                        it.resourceReport.set(resources.flatMap { t -> t.report })
                        it.runtimeSymbols.set(variant.artifacts.get(SingleArtifact.RUNTIME_SYMBOL_LIST))
                    }
                }
                val ndkVersion = extension.nativeNdkVersion
                val native = project.tasks.register("lspCompileNative$capitalized", CompileNativeTask::class.java) {
                    it.sources.set(transform.flatMap { t -> t.nativeSources })
                    resourceTask?.let { task ->
                        it.resourceReport.set(task.flatMap { t -> t.report })
                        it.mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    }
                    it.ndk.set(components.sdkComponents.sdkDirectory.map { sdk -> sdk.dir("ndk/$ndkVersion") })
                    it.minSdk.set(variant.minSdk.apiLevel)
                    omvllPlugin?.let { path -> it.omvllPlugin.set(project.layout.projectDirectory.file(path)) }
                    omvllPythonPath?.let { path -> it.omvllPythonPath.set(project.layout.projectDirectory.dir(path)) }
                    it.output.set(project.layout.buildDirectory.dir("$base/jniLibs"))
                    it.symbols.set(project.layout.buildDirectory.dir("$base/private/symbols"))
                    it.report.set(project.layout.buildDirectory.file("reports/lspeciallyparanoid/${variant.name}/native.txt"))
                }
                variant.sources.jniLibs!!.addGeneratedSourceDirectory(native, CompileNativeTask::output)
            }
        }
    }
}
