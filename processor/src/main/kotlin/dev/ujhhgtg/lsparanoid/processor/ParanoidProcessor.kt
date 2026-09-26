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

package dev.ujhhgtg.lsparanoid.processor

import com.joom.grip.Grip
import com.joom.grip.GripFactory
import com.joom.grip.io.IoFactory
import com.joom.grip.mirrors.getObjectTypeByInternalName
import dev.ujhhgtg.lsparanoid.processor.commons.closeQuietly
import dev.ujhhgtg.lsparanoid.processor.commons.createFile
import dev.ujhhgtg.lsparanoid.processor.logging.getLogger
import dev.ujhhgtg.lsparanoid.processor.model.Deobfuscator
import dev.ujhhgtg.lsparanoid.processor.nativebackend.NativeBuildSpec
import dev.ujhhgtg.lsparanoid.processor.nativebackend.NativeGenerator
import dev.ujhhgtg.lsparanoid.processor.nativebackend.NativeStringRegistry
import dev.ujhhgtg.lsparanoid.processor.nativebackend.NativeStringRecord
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.Method
import java.nio.file.Path
import java.util.jar.JarOutputStream

class ParanoidProcessor(
    private val seed: Int,
    classpath: Set<Path>,
    private val inputs: List<Path>,
    private val output: JarOutputStream,
    private val asmApi: Int = Opcodes.ASM9,
    private val projectName: String,
    private val classFilter: ((className: String) -> Boolean)?,
    private val nativeSpec: NativeBuildSpec? = null,
    private val nativeOutput: Path? = null,
    private val resourceRecords: List<NativeStringRecord> = emptyList(),
    private val excludedClassPrefixes: Set<String> = emptySet(),
    private val coverageReport: Path? = null,
    private val requireNativeRuntime: Boolean = false,
) {

    private val logger = getLogger()

    private val sortedInputs = inputs.distinct().sorted()
    private val grip: Grip = GripFactory.newInstance(asmApi).create(classpath + sortedInputs)

    fun process() {
        dumpConfiguration()
        StringRegistryImpl(seed).use { stringRegistry ->
            val analyzed = Analyzer(grip, classFilter).analyze(sortedInputs)
            val analysisResult = analyzed.copy(configurationsByType = analyzed.configurationsByType.filterKeys { type ->
                !type.className.startsWith("dev.ujhhgtg.lsparanoid.generated.") &&
                    !type.className.startsWith("dev.ujhhgtg.lsparanoid.runtime.") &&
                    excludedClassPrefixes.none { type.className.startsWith(it) }
            })
            analysisResult.dump()

            val nativeRegistry = nativeSpec?.let { NativeStringRegistry(it) }
            val registrar: StringRegistrar = nativeRegistry ?: stringRegistry
            val deobfuscator = nativeSpec?.let { spec ->
                Deobfuscator(getObjectTypeByInternalName(spec.bridgeInternalName), Method(spec.bridgeMethodName, "(J)Ljava/lang/String;"))
            } ?: createDeobfuscator()
            logger.info("Prepare to generate {}", deobfuscator)

            val sources = sortedInputs.map { input ->
                IoFactory.createFileSource(input)
            }

            try {
                coverageReport?.let { report ->
                    java.nio.file.Files.createDirectories(report.parent)
                    java.nio.file.Files.newBufferedWriter(report).use { writer ->
                        writer.appendLine("class\tmember\tlocation\tstatus\treason")
                        CoverageAnalyzer(asmApi).analyze(sources, analysisResult).forEach { site ->
                            writer.appendLine(listOf(site.className, site.member, site.location, site.status, site.reason)
                                .joinToString("\t") { it.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r") })
                        }
                    }
                }
                Patcher(
                    deobfuscator,
                    registrar,
                    analysisResult,
                    grip.classRegistry,
                    grip.fileRegistry,
                    asmApi
                ).copyAndPatchClasses(sources.asSequence(), output)
                if (nativeSpec != null) {
                    NativeGenerator.generate(nativeSpec, nativeRegistry!!.records() + resourceRecords, requireNotNull(nativeOutput), requireNativeRuntime)
                } else {
                    val deobfuscatorClasses =
                        DeobfuscatorGenerator(
                            deobfuscator,
                            stringRegistry,
                            grip.classRegistry,
                            grip.fileRegistry
                        ).generateDeobfuscatorClasses()

                    // Write all generated classes (main + chunk classes)
                    deobfuscatorClasses.forEach { (className, classBytes) ->
                        output.createFile(className, classBytes)
                    }
                }
            } finally {
                sources.forEach { source ->
                    source.closeQuietly()
                }
            }
        }
    }

    private fun dumpConfiguration() {
        logger.info("Starting ParanoidProcessor:")
        logger.info("  inputs        = {}", inputs)
        logger.info("  output        = {}", output)
    }

    private fun AnalysisResult.dump() {
        if (configurationsByType.isEmpty()) {
            logger.info("No classes to obfuscate")
        } else {
            logger.info("Classes to obfuscate:")
            configurationsByType.forEach {
                val (type, configuration) = it
                logger.info("  {}:", type.internalName)
                logger.info("    {} constant string fields", configuration.constantStringsByFieldName.size)
            }
        }
    }

    private fun createDeobfuscator(): Deobfuscator {
        val deobfuscatorInternalName =
            "dev/ujhhgtg/lsparanoid/Deobfuscator${composeDeobfuscatorNameSuffix()}"
        val deobfuscatorType = getObjectTypeByInternalName(deobfuscatorInternalName)
        val deobfuscationMethod =
            Method("getString", Type.getType(String::class.java), arrayOf(Type.LONG_TYPE))
        return Deobfuscator(deobfuscatorType, deobfuscationMethod)
    }

    private fun composeDeobfuscatorNameSuffix(): String {
        val normalizedProjectName =
            projectName.filter { it.isLetterOrDigit() || it == '_' || it == '$' }
        return if (normalizedProjectName.isEmpty() || normalizedProjectName.startsWith('$')) {
            normalizedProjectName
        } else {
            "$$normalizedProjectName"
        }
    }
}
