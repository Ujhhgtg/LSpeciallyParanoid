/*
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

import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import dev.ujhhgtg.lsparanoid.processor.ParanoidProcessor
import dev.ujhhgtg.lsparanoid.processor.resources.ResourceBytecodeValidator
import dev.ujhhgtg.lsparanoid.processor.nativebackend.NativeVerificationPolicy
import dev.ujhhgtg.lsparanoid.processor.nativebackend.NativeBuildSpec
import dev.ujhhgtg.lsparanoid.processor.nativebackend.NativeRecordIO
import java.util.jar.JarOutputStream

@CacheableTask
abstract class LSParanoidTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val jars: ListProperty<RegularFile>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val dirs: ListProperty<Directory>
    @get:OutputFile abstract val coverageReport: RegularFileProperty
    @get:OutputFile abstract val output: RegularFileProperty
    @get:Classpath abstract val bootClasspath: ListProperty<RegularFile>
    @get:CompileClasspath abstract var classpath: FileCollection
    @get:Input abstract val seed: Property<Int>
    @get:Input @get:Optional abstract var classFilter: ((className: String) -> Boolean)?
    @get:Input abstract val projectName: Property<String>
    @get:Input abstract val requireNativeRuntime: Property<Boolean>
    @get:Input abstract val backend: Property<String>
    @get:Input abstract val excludedClassPrefixes: SetProperty<String>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val entropy: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val resourceRecords: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val resourceReport: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val runtimeSymbols: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val signerPins: RegularFileProperty
    @get:Input abstract val protectedApplicationId: Property<String>
    @get:Input abstract val allowedHostCertificates: MapProperty<String, List<String>>
    @get:Input abstract val applicationNamespace: Property<String>
    @get:OutputDirectory abstract val nativeSources: DirectoryProperty

    @TaskAction fun taskAction() {
        val inputs = jars.get() + dirs.get()
        if (resourceReport.isPresent) {
            ResourceBytecodeValidator.verify(inputs.map { it.asFile.toPath() }, runtimeSymbols.get().asFile.toPath(),
                resourceReport.get().asFile.toPath(), applicationNamespace.get())
        }
        val spec = if (backend.get() == "native") NativeBuildSpec.create(entropy.get().asFile.readBytes(), projectName.get()) else null
        nativeSources.get().asFile.apply { deleteRecursively(); mkdirs() }
        val outputFile = output.get().asFile.apply { parentFile.mkdirs() }
        JarOutputStream(outputFile.outputStream().buffered()).use { jarOutput ->
            ParanoidProcessor(
                seed = seed.get(), inputs = inputs.map { it.asFile.toPath() },
                classpath = bootClasspath.get().map { it.asFile.toPath() }.toSet() + classpath.files.map { it.toPath() },
                output = jarOutput, projectName = projectName.get(), classFilter = classFilter,
                nativeSpec = spec, nativeOutput = nativeSources.get().asFile.toPath(),
                resourceRecords = if (spec != null && resourceRecords.isPresent) NativeRecordIO.read(resourceRecords.get().asFile, spec) else emptyList(),
                requireNativeRuntime = requireNativeRuntime.get(),
                verificationPolicy = if (spec != null) NativeVerificationPolicy(protectedApplicationId.get(),
                    signerPins.get().asFile.readLines().filter { it.isNotBlank() }, allowedHostCertificates.get()) else null,
                excludedClassPrefixes = excludedClassPrefixes.get(), coverageReport = coverageReport.get().asFile.toPath(),
            ).process()
        }
    }
}
