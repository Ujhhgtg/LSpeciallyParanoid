package dev.ujhhgtg.lsparanoid.processor.nativebackend

import dev.ujhhgtg.lsparanoid.processor.StringRegistrar
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Immutable authenticated UTF-16LE record; ciphertext includes the trailing 16-byte tag. */
class NativeStringRecord(
    val id: Long,
    val domain: RecordDomain,
    val utf16Length: Int,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    private val nonceBytes = nonce.clone()
    private val ciphertextBytes = ciphertext.clone()
    val nonce: ByteArray get() = nonceBytes.clone()
    val ciphertext: ByteArray get() = ciphertextBytes.clone()

    init {
        require(utf16Length in 0..MAX_UTF16_LENGTH) { "Protected string exceeds the supported UTF-16 length" }
        require(nonce.size == 12) { "ChaCha20-Poly1305 nonce must be 12 bytes" }
        require(ciphertext.size == utf16Length * 2 + 16) { "Invalid authenticated string length" }
        require((id < 0) == (domain == RecordDomain.RESOURCE)) { "Record ID is outside its domain" }
    }

    companion object { const val MAX_UTF16_LENGTH = 1_048_576 }
}

/** One registry per build domain. Call order is deliberately part of the deterministic test format. */
class NativeStringRegistry @JvmOverloads constructor(
    private val spec: NativeBuildSpec,
    private val domain: RecordDomain = RecordDomain.LITERAL,
) : StringRegistrar {
    private val entries = ArrayList<NativeStringRecord>()
    private val ids = HashSet<Long>()
    private var counter = 0L

    override fun registerString(string: String): Long {
        require(string.length <= NativeStringRecord.MAX_UTF16_LENGTH) { "Protected string exceeds the supported UTF-16 length" }
        var id: Long
        var occurrence: Long
        do {
            check(counter != Long.MAX_VALUE) { "Protected string occurrence counter exhausted" }
            occurrence = counter++
            id = spec.id(domain, occurrence)
        } while (!ids.add(id))
        // Each domain has its own key. A counter is never reused under that key.
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(domain.wireValue).putLong(occurrence).array()
        // Charset encoders replace lone surrogates. Write raw UTF-16 code units instead.
        val plain = ByteArray(string.length * 2)
        string.forEachIndexed { index, char ->
            plain[index * 2] = char.code.toByte()
            plain[index * 2 + 1] = (char.code ushr 8).toByte()
        }
        val key = spec.key(domain)
        val encrypted = try {
            Cipher.getInstance("ChaCha20-Poly1305").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
                updateAAD(associatedData(id, domain, string.length))
                doFinal(plain)
            }
        } finally {
            plain.fill(0)
            key.fill(0)
        }
        entries += NativeStringRecord(id, domain, string.length, nonce, encrypted)
        return id
    }

    fun records(): List<NativeStringRecord> = entries.toList()
}
