package dev.ujhhgtg.lsparanoid.plugin

import dev.ujhhgtg.lsparanoid.processor.nativebackend.NativeBuildSpec
import dev.ujhhgtg.lsparanoid.processor.nativebackend.NativeGenerator
import dev.ujhhgtg.lsparanoid.processor.resources.ResourceUsageValidator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject

@DisableCachingByDefault(because = "Checks an external toolchain before protected variant tasks run")
abstract class ValidateNativeToolchainTask : DefaultTask() {
    @get:Input abstract val pluginPath: Property<String>
    @get:Input @get:Optional abstract val pythonPath: Property<String>

    @TaskAction fun validate() {
        require(File(pluginPath.get()).isFile) {
            "Native protection requires O-MVLL for native string encoding. Run tools/setup-native-toolchain.py " +
                "or configure lsparanoid.omvllPlugin and omvllPythonPath. Missing: ${pluginPath.get()}"
        }
        if (pythonPath.isPresent) require(File(pythonPath.get(), "encodings/__init__.py").isFile) {
            "Native protection requires the configured O-MVLL Python standard library. Run tools/setup-native-toolchain.py " +
                "or correct lsparanoid.omvllPythonPath: ${pythonPath.get()}"
        }
    }
}

@DisableCachingByDefault(because = "Each native build intentionally uses fresh entropy")
abstract class NativeEntropyTask : DefaultTask() {
    @get:OutputFile abstract val output: RegularFileProperty
    init { outputs.upToDateWhen { false } }
    @TaskAction fun generate() {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}

@CacheableTask
abstract class NativeBootstrapTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val entropy: RegularFileProperty
    @get:Input abstract val moduleIdentity: Property<String>
    @get:Input abstract val automaticLoading: Property<Boolean>
    @get:OutputDirectory abstract val sources: DirectoryProperty
    @get:OutputFile abstract val keepRules: RegularFileProperty
    @TaskAction fun generate() {
        val spec = NativeBuildSpec.create(entropy.get().asFile.readBytes(), moduleIdentity.get())
        val directory = sources.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        NativeGenerator.generateBootstrap(spec, directory.toPath(), automaticLoading.get())
        keepRules.get().asFile.apply { parentFile.mkdirs(); writeText(NativeGenerator.keepRules(spec)) }
    }
}

@CacheableTask
abstract class JvmBootstrapTask : DefaultTask() {
    @get:OutputDirectory abstract val sources: DirectoryProperty
    @TaskAction fun generate() {
        sources.get().asFile.apply { deleteRecursively(); mkdirs() }
        NativeGenerator.generateJvmBootstrap(sources.get().asFile)
    }
}

@CacheableTask
abstract class CompileNativeTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) abstract val ndk: DirectoryProperty
    @get:Input abstract val minSdk: Property<Int>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val omvllPlugin: RegularFileProperty
    @get:InputDirectory @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val omvllPythonPath: DirectoryProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val mergedManifest: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val resourceReport: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:OutputDirectory abstract val symbols: DirectoryProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun compile() {
        if (resourceReport.isPresent) {
            ResourceUsageValidator.verifyManifest(mergedManifest.get().asFile.toPath(), resourceReport.get().asFile.toPath())
        }
        val input = sources.get().asFile
        val out = output.get().asFile
        val privateSymbols = symbols.get().asFile
        out.deleteRecursively(); out.mkdirs()
        privateSymbols.deleteRecursively(); privateSymbols.mkdirs()
        val manifest = input.resolve("library-name.txt")
        if (!manifest.exists()) {
            report.get().asFile.apply { parentFile.mkdirs(); writeText("No protected strings; native compilation skipped.\n") }
            return
        }
        require(input.resolve("verification-required.txt").isFile && input.resolve("guard_policy.h").isFile) {
            "Refusing to compile a native codec fixture without APK/runtime verification policy"
        }
        require(omvllPlugin.isPresent) { "Native string encoding requires O-MVLL; unprotected native compilation is not supported" }
        require(System.getProperty("os.name").lowercase().contains("linux")) {
            "Native protection currently supports Linux build hosts only"
        }
        val bin = ndk.get().asFile.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
        val clang = bin.resolve("clang")
        require(clang.isFile) { "Incomplete NDK: $clang. Install the configured nativeNdkVersion first." }
        val libraryName = manifest.readText().trim()
        require(libraryName.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid generated library name" }
        val library = privateSymbols.resolve("lib$libraryName.so")
        val arguments = mutableListOf(
            clang.absolutePath, "--target=aarch64-linux-android${minSdk.get()}", "-std=c11", "-shared", "-fPIC", "-O2", "-g",
            "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections", "-fno-ident",
            "-ffile-prefix-map=${input.absolutePath}=lsp", "-fdebug-prefix-map=${ndk.get().asFile.absolutePath}=ndk",
            "-Wl,--gc-sections,--no-undefined", "-Wl,-z,relro,-z,now,-z,noexecstack",
            "-Wl,-z,max-page-size=16384,-z,common-page-size=16384", "-Wl,--build-id=sha1",
            "-Wl,--version-script=${input.resolve("exports.map").absolutePath}", "-Wl,-soname,lib$libraryName.so",
            "-I${input.absolutePath}", "-o", library.absolutePath,
        )
        if (omvllPlugin.isPresent) arguments += "-fpass-plugin=${omvllPlugin.get().asFile.absolutePath}"
        arguments += input.walkTopDown().filter { it.isFile && it.extension == "c" }.sortedBy { it.path }.map { it.absolutePath }.toList()
        arguments += listOf("-lz", "-ldl")
        val environment = mutableMapOf<String, String>()
        val stringManifest = privateSymbols.resolve("native-string-literals.tsv")
        if (omvllPlugin.isPresent) {
            val config = input.resolve("omvll_config.py")
            require(config.isFile) { "Missing generated O-MVLL policy" }
            environment["OMVLL_CONFIG"] = config.absolutePath
            environment["PYTHONDONTWRITEBYTECODE"] = "1"
            environment["LSP_NATIVE_STRING_MANIFEST"] = stringManifest.absolutePath
            environment["LD_LIBRARY_PATH"] = bin.parentFile.resolve("lib64").absolutePath
            if (omvllPythonPath.isPresent) environment["OMVLL_PYTHONPATH"] = omvllPythonPath.get().asFile.absolutePath
        }
        val compilerOutput = ByteArrayOutputStream()
        val result = exec.exec {
            it.commandLine(arguments); it.workingDir(privateSymbols); it.environment(environment)
            it.standardOutput = compilerOutput; it.errorOutput = compilerOutput; it.isIgnoreExitValue = true
        }
        val compileLog = compilerOutput.toString(Charsets.UTF_8)
        privateSymbols.resolve("compiler.log").writeText(compileLog)
        check(result.exitValue == 0) { "Native compilation failed:\n$compileLog" }
        if (omvllPlugin.isPresent && !input.resolve("build-id.txt").readLines().contains("records=0")) {
            for (function in listOf("lsp_resolve", "crypto_aead_read", "lsp_guard_init", "lsp_apk_verify", "lsp_frida_check")) {
                check(compileLog.contains("LSP_OMVLL_SELECTED flatten_cfg $function")) {
                    "O-MVLL did not confirm the required protection for $function; see private compiler.log"
                }
            }
            check(compileLog.contains("LSP_OMVLL_SELECTED arithmetic lsp_resolve")) {
                "O-MVLL did not select resolver arithmetic protection"
            }
            val passLogs = privateSymbols.resolve("omvll-logs").walkTopDown()
                .filter { it.isFile && it.extension == "log" }.map { it.readText() }.toList()
            for ((pass, module) in listOf("ControlFlowFlattening" to "decoder.c", "ControlFlowFlattening" to "monocypher.c", "Arithmetic" to "decoder.c", "ControlFlowFlattening" to "runtime_guard.c", "ControlFlowFlattening" to "apk_verify.c", "ControlFlowFlattening" to "frida_guard.c")) {
                val applied = Regex("\\[omvll::$pass] Changes\\s+applied on module[^\\n]*${Regex.escape(module)}")
                check(passLogs.any { applied.containsMatchIn(it) }) {
                    "O-MVLL selected $pass for $module but did not report applying it; see private pass logs"
                }
            }
            for (module in listOf("decoder.c", "runtime_guard.c", "apk_verify.c", "frida_guard.c")) {
                check(compileLog.contains("LSP_OMVLL_SELECTED strings $module")) {
                    "O-MVLL did not select native string encoding for $module"
                }
                val applied = Regex("\\[omvll::StringEncoding] Changes\\s+applied on module[^\\n]*${Regex.escape(module)}")
                check(passLogs.any { applied.containsMatchIn(it) }) {
                    "O-MVLL did not apply native string encoding for $module; see private pass logs"
                }
            }
        }
        val abiDir = out.resolve("arm64-v8a").apply { mkdirs() }
        val packaged = abiDir.resolve(library.name)
        library.copyTo(packaged, overwrite = true)
        exec.exec { it.commandLine(bin.resolve("llvm-strip"), "--strip-unneeded", "--remove-section=.comment", packaged) }.assertNormalExitValue()
        check(stringManifest.isFile) { "Missing native string encoding manifest" }
        val nativeStrings = stringManifest.readLines().map { line ->
            val hex = line.substringAfter('\t')
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.ISO_8859_1)
        }.distinct()
        val nativeBytes = packaged.readBytes().toString(Charsets.ISO_8859_1)
        // Short fragments can coincide with instructions or encrypted payload bytes.
        // Eight-byte literals are useful static-analysis clues and have negligible collision probability.
        // Policy literals must be hidden even if a future compiler misses a table initializer.
        val policyStrings = Regex("\"([^\"]+)\"").findAll(input.resolve("guard_policy.h").readText())
            .map { it.groupValues[1] }.toList()
        val auditedStrings = (nativeStrings + policyStrings).distinct().filter { it.length >= 8 }
        check(auditedStrings.isNotEmpty()) { "Native string encoding selected no auditable literals" }
        check(auditedStrings.none { it in nativeBytes }) {
            "Selected native string plaintext survived obfuscation; inspect private native-string-literals.tsv"
        }
        check(listOf("LSP guard:", "android/app/ActivityThread", "APK Sig Block 42", "gum-js-loop").none { it in nativeBytes }) {
            "A native guard marker survived string encoding"
        }
        val exports = capture(listOf(bin.resolve("llvm-nm").absolutePath, "-D", "--defined-only", packaged.absolutePath))
        val exportedNames = exports.lineSequence().filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")).last().substringBefore('@') }.toSet()
        require(exportedNames == setOf("JNI_OnLoad")) { "Unexpected native exports: $exportedNames" }
        val elf = capture(listOf(bin.resolve("llvm-readelf").absolutePath, "-h", "-lW", packaged.absolutePath))
        require(elf.contains("AArch64") && elf.contains("DYN")) { "Generated library is not an AArch64 shared object" }
        val loadSegments = elf.lineSequence().filter { it.trimStart().startsWith("LOAD ") }.toList()
        require(loadSegments.isNotEmpty() && loadSegments.all { it.trim().split(Regex("\\s+")).last().removePrefix("0x").toLong(16) >= 16384 }) {
            "Generated library does not have 16 KiB load-segment alignment"
        }
        val dynamic = capture(listOf(bin.resolve("llvm-readelf").absolutePath, "-d", packaged.absolutePath))
        require(!dynamic.contains("libc++_shared")) { "Decoder unexpectedly requires a shared C++ runtime" }
        val compiler = capture(listOf(clang.absolutePath, "--version"))
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText("Library: ${packaged.name}\nABI: arm64-v8a\nMinimum API: ${minSdk.get()}\nO-MVLL: ${omvllPlugin.isPresent}\nNative strings: encoded; ${auditedStrings.size} distinct literals of 8+ bytes audited\nVerification: APK-v2, pinned host, loaded ELF, Android runtime; failure=abort\nPackaged bytes: ${packaged.length()}\n$compiler\n$exports\n$elf")
        }
    }

    private fun capture(arguments: List<String>): String {
        val buffer = ByteArrayOutputStream()
        exec.exec { it.commandLine(arguments); it.standardOutput = buffer }.assertNormalExitValue()
        return buffer.toString(Charsets.UTF_8)
    }
}
