package dev.ujhhgtg.lsparanoid.processor.nativebackend

import java.io.File

/** Public trust anchors embedded into the native binary, never caller-supplied APK locations. */
class NativeVerificationPolicy(
    val applicationId: String,
    signerPins: Collection<String>,
    hosts: Map<String, Collection<String>> = emptyMap(),
) {
    val signerPins = signerPins.map(::pin).distinct().sorted()
    val hosts = hosts.toSortedMap().mapValues { (_, values) -> values.map(::pin).distinct().sorted() }

    init {
        require(packageName.matches(applicationId)) { "Invalid protected application ID" }
        require(this.signerPins.isNotEmpty()) { "Native verification requires at least one signing certificate" }
        this.hosts.forEach { (name, values) ->
            require(packageName.matches(name) && name != applicationId) { "Invalid additional host package: $name" }
            require(values.isNotEmpty()) { "Every additional host requires a signing certificate pin" }
        }
    }

    fun write(spec: NativeBuildSpec, directory: File) {
        File(directory, "guard_policy.h").writeText(buildString {
            appendLine("/* Generated public verification policy. There is no runtime disable switch. */")
            appendLine("#define LSP_GUARD_APPLICATION_ID \"$applicationId\"")
            appendLine("#define LSP_GUARD_LIBRARY_ENTRY \"lib/arm64-v8a/${spec.libraryFileName}\"")
            appendPins("lsp_guard_module_signers", signerPins)
            appendLine("#define LSP_GUARD_MODULE_SIGNER_COUNT ${signerPins.size}")
            hosts.entries.forEachIndexed { index, (_, values) -> appendPins("lsp_guard_host_signers_$index", values) }
            appendLine("static const lsp_guard_host_policy lsp_guard_hosts[] = {")
            if (hosts.isEmpty()) appendLine("{0, 0, 0},")
            hosts.entries.forEachIndexed { index, (name, values) ->
                appendLine("{\"$name\", lsp_guard_host_signers_$index, ${values.size}},")
            }
            appendLine("};")
            appendLine("#define LSP_GUARD_HOST_COUNT ${hosts.size}")
        })
        File(directory, "verification-required.txt").writeText("apk-v2\n$applicationId\n")
    }

    private fun StringBuilder.appendPins(name: String, values: List<String>) {
        appendLine("static const uint8_t $name[][32] = {")
        values.forEach { value -> appendLine("{" + value.chunked(2).joinToString(",") { "0x$it" } + "},") }
        appendLine("};")
    }

    companion object {
        private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private fun pin(value: String): String = value.replace(":", "").trim().lowercase().also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Signing certificate pins must be SHA-256 hexadecimal digests" }
        }
    }
}
