package dev.ujhhgtg.lsparanoid.processor.nativebackend

import java.io.File
import java.io.Writer
import java.nio.file.Path

/** Generates source only. Toolchain execution belongs to the Gradle task owning its output directory. */
object NativeGenerator {
    /** Integration shim for an explicitly selected JVM development backend; never a native fallback. */
    @JvmStatic
    fun generateJvmBootstrap(outputDir: Path) = generateJvmBootstrap(outputDir.toFile())

    @JvmStatic
    fun generateJvmBootstrap(outputDir: File) {
        val packageDir = File(outputDir, "dev/ujhhgtg/lsparanoid/generated").apply { mkdirs() }
        File(packageDir, "LspBootstrap.java").writeText("""
            package dev.ujhhgtg.lsparanoid.generated;
            /** Generated only for the explicitly selected JVM development backend. */
            public final class LspBootstrap {
                public static final String libraryFileName = "";
                public static final String namespace = "00000000000000000000000000000000";
                private LspBootstrap() {}
                public static boolean isLoaded() { return true; }
                public static void loadInstalled() {}
                public static void loadAbsolute(java.io.File decoderFile) {}
                public static String decode(long id) {
                    throw new UnsupportedOperationException("Native resource decoding is unavailable in JVM development mode");
                }
            }
        """.trimIndent() + "\n")
    }

    @JvmStatic
    fun generateBootstrap(spec: NativeBuildSpec, outputDir: Path, automaticLoading: Boolean) =
        generateBootstrap(spec, outputDir.toFile(), automaticLoading)

    @JvmStatic
    fun generateBootstrap(spec: NativeBuildSpec, outputDir: File, automaticLoading: Boolean) {
        val bridgeClass = spec.bridgeInternalName.substringAfterLast('/')
        val packageDir = File(outputDir, "dev/ujhhgtg/lsparanoid/generated").apply { mkdirs() }
        File(packageDir, "$bridgeClass.java").writeText("""
            package dev.ujhhgtg.lsparanoid.generated;
            public final class $bridgeClass {
                private $bridgeClass() {}
                public static String ${spec.bridgeMethodName}(long id) {
                    ${if (automaticLoading) "if (!LspBootstrap.isLoaded()) LspBootstrap.loadInstalled();" else ""}
                    return ${spec.bridgeNativeMethodName}(id);
                }
                private static native String ${spec.bridgeNativeMethodName}(long id);
            }
        """.trimIndent() + "\n")
        File(packageDir, "LspBootstrap.java").writeText("""
            package dev.ujhhgtg.lsparanoid.generated;
            /** App-local integration point. Load from the module classloader before protected feature code. */
            public final class LspBootstrap {
                public static final String libraryFileName = "${spec.libraryFileName}";
                public static final String namespace = "${spec.namespace}";
                private static volatile boolean loaded;
                private LspBootstrap() {}
                public static boolean isLoaded() { return loaded; }
                public static String decode(long id) { return $bridgeClass.${spec.bridgeMethodName}(id); }
                public static synchronized void loadInstalled() {
                    if (loaded) return;
                    System.loadLibrary("${spec.libraryName}");
                    loaded = true;
                }
                public static synchronized void loadAbsolute(java.io.File decoderFile) {
                    if (loaded) return;
                    if (decoderFile == null || !decoderFile.isAbsolute() || !decoderFile.isFile()) {
                        throw new IllegalArgumentException("LSP decoder must be an existing absolute library file");
                    }
                    System.load(decoderFile.getAbsolutePath());
                    loaded = true;
                }
            }
        """.trimIndent() + "\n")
    }

    @JvmStatic
    fun keepRules(spec: NativeBuildSpec): String = """
        # Generated JNI registration binds these exact names and signatures.
        -keep class ${spec.bridgeInternalName.replace('/', '.')} { *; }
        -keep class ${NativeBuildSpec.BOOTSTRAP_CLASS} { *; }
    """.trimIndent() + "\n"

    /** Returns false for an empty registry unless an explicit loader requires the runtime library. */
    @JvmStatic @JvmOverloads
    fun generate(spec: NativeBuildSpec, records: List<NativeStringRecord>, outputDir: Path, requireRuntime: Boolean = false): Boolean =
        generate(spec, records, outputDir.toFile(), requireRuntime)

    @JvmStatic @JvmOverloads
    fun generate(spec: NativeBuildSpec, records: List<NativeStringRecord>, outputDir: File, requireRuntime: Boolean = false): Boolean {
        require(records.map { it.id }.toSet().size == records.size) { "Duplicate protected record IDs during assembly" }
        if (records.isEmpty() && !requireRuntime) return false
        outputDir.mkdirs()
        val sorted = records.sortedWith { a, b -> java.lang.Long.compareUnsigned(a.id, b.id) }
        File(outputDir, "payload.h").bufferedWriter().use { output ->
            output.appendLine("/* Generated encrypted UTF-16LE data. Format ${NativeBuildSpec.FORMAT_VERSION}. */")
            for (domain in RecordDomain.entries) {
                val key = spec.key(domain)
                output.append("static const uint8_t lsp_key_${domain.wireValue}[32] = {")
                output.bytes(key)
                output.appendLine("};")
                key.fill(0)
            }
            output.appendLine("static const uint8_t lsp_payload[] = {")
            if (sorted.isEmpty()) output.appendLine("0,")
            sorted.forEach { output.bytes(it.ciphertext); output.appendLine() }
            output.appendLine("};")
            output.appendLine("static const lsp_record lsp_records[] = {")
            if (sorted.isEmpty()) output.appendLine("{0, 0, 0, {0}, 0},")
            var offset = 0L
            for (record in sorted) {
                output.append("{UINT64_C(0x${java.lang.Long.toUnsignedString(record.id, 16)}), ${record.utf16Length}u, UINT64_C($offset), {")
                output.bytes(record.nonce)
                output.appendLine("}, ${record.domain.wireValue}u},")
                offset += record.ciphertext.size
            }
            output.appendLine("};")
            output.appendLine("#define LSP_RECORD_COUNT ${sorted.size}u")
            output.appendLine("#define LSP_FORMAT_VERSION ${NativeBuildSpec.FORMAT_VERSION}u")
            output.appendLine("#define LSP_MAX_UTF16_LENGTH ${NativeStringRecord.MAX_UTF16_LENGTH}u")
            output.appendLine("#define LSP_BRIDGE_CLASS \"${spec.bridgeInternalName}\"")
            output.appendLine("#define LSP_NATIVE_METHOD \"${spec.bridgeNativeMethodName}\"")
        }
        copyResource("decoder.c", outputDir)
        copyResource("omvll_config.py", outputDir)
        copyResource("monocypher/monocypher.c", outputDir)
        copyResource("monocypher/monocypher.h", outputDir)
        copyResource("monocypher/LICENCE.md", outputDir)
        File(outputDir, "exports.map").writeText("{ global: JNI_OnLoad; local: *; };\n")
        File(outputDir, "library-name.txt").writeText(spec.libraryName + "\n")
        File(outputDir, "build-id.txt").writeText("format=${NativeBuildSpec.FORMAT_VERSION}\nbuildId=${spec.buildId}\nrecords=${records.size}\n")
        return true
    }

    private fun Writer.bytes(bytes: ByteArray) {
        bytes.forEachIndexed { index, byte ->
            append("0x")
            val n = byte.toInt() and 255
            append("0123456789abcdef"[n ushr 4])
            append("0123456789abcdef"[n and 15])
            append(',')
            if (index % 24 == 23) append('\n')
        }
    }

    private fun copyResource(name: String, directory: File) {
        val input = NativeGenerator::class.java.getResourceAsStream("/nativebackend/$name")
            ?: error("Missing packaged native template $name")
        input.use { source -> File(directory, name.substringAfterLast('/')).outputStream().use { source.copyTo(it) } }
    }
}
