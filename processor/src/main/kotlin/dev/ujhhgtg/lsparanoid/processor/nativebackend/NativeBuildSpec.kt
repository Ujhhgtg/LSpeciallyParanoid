package dev.ujhhgtg.lsparanoid.processor.nativebackend

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** All names and keys for one module/variant build, derived from private execution-time entropy. */
class NativeBuildSpec private constructor(private val root: ByteArray) {
    val buildId: String = derive("build-id").copyOf(16).hex()
    val namespace: String = derive("resource-namespace").copyOf(16).hex()
    val libraryName: String = "lsp_" + derive("library-name").copyOf(12).hex()
    val libraryFileName: String = "lib$libraryName.so"
    val bridgeInternalName: String = "dev/ujhhgtg/lsparanoid/generated/B" + derive("bridge-name").copyOf(12).hex()
    val bridgeMethodName: String = "d" + derive("method-name").copyOf(8).hex()
    val bridgeNativeMethodName: String = "n" + derive("native-method-name").copyOf(8).hex()

    internal fun key(domain: RecordDomain): ByteArray = derive("aead-key/${domain.name}")
    internal fun id(domain: RecordDomain, counter: Long): Long {
        val raw = ByteBuffer.wrap(derive("record-id/${domain.name}/$counter")).long and Long.MAX_VALUE
        return if (domain == RecordDomain.RESOURCE) raw or Long.MIN_VALUE else raw
    }

    internal fun derive(label: String): ByteArray = hmac(root, label.toByteArray(Charsets.UTF_8))

    companion object {
        const val FORMAT_VERSION: Int = 1
        const val BOOTSTRAP_CLASS: String = "dev.ujhhgtg.lsparanoid.generated.LspBootstrap"

        @JvmStatic
        fun create(entropy: ByteArray, moduleIdentity: String): NativeBuildSpec {
            require(entropy.size == 32) { "Native build entropy must contain exactly 32 bytes" }
            require(moduleIdentity.isNotBlank()) { "Native module/variant identity must not be blank" }
            return NativeBuildSpec(hmac(entropy, ("LSpeciallyParanoid/v1/" + moduleIdentity).toByteArray(Charsets.UTF_8)))
        }
    }
}

enum class RecordDomain(val wireValue: Int) { LITERAL(0), RESOURCE(1) }

internal fun hmac(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256"))
    doFinal(input)
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun associatedData(id: Long, domain: RecordDomain, utf16Length: Int): ByteArray =
    ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(NativeBuildSpec.FORMAT_VERSION).putInt(domain.wireValue).putLong(id).putInt(utf16Length).array()
