package dev.ujhhgtg.lsparanoid.processor.nativebackend

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Private build artifact: versioned ciphertext records; never the original source text or key. */
object NativeRecordIO {
    private const val MAGIC = 0x4c535031
    private const val MAX_RECORDS = 1_000_000

    @JvmStatic
    fun write(file: File, spec: NativeBuildSpec, records: List<NativeStringRecord>) {
        require(records.size <= MAX_RECORDS) { "Too many native records" }
        require(records.map { it.id }.toSet().size == records.size) { "Duplicate protected record IDs" }
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(NativeBuildSpec.FORMAT_VERSION)
            output.writeUTF(spec.buildId)
            output.writeInt(records.size)
            records.forEach { record ->
                output.writeLong(record.id)
                output.writeByte(record.domain.wireValue)
                output.writeInt(record.utf16Length)
                output.write(record.nonce)
                output.write(record.ciphertext)
            }
        }
    }

    @JvmStatic
    fun read(file: File, spec: NativeBuildSpec): List<NativeStringRecord> =
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == NativeBuildSpec.FORMAT_VERSION) {
                "Unsupported protected record format: ${file.name}"
            }
            require(input.readUTF() == spec.buildId) { "Protected records belong to a different build: ${file.name}" }
            val count = input.readInt()
            require(count in 0..MAX_RECORDS) { "Invalid protected record count" }
            // 41 bytes is the smallest possible record. Check before allocating from file-controlled sizes.
            require(count.toLong() * 41 <= file.length()) { "Truncated protected record file" }
            val ids = HashSet<Long>()
            val records = ArrayList<NativeStringRecord>(count)
            repeat(count) {
                val id = input.readLong()
                val wireDomain = input.readUnsignedByte()
                val domain = RecordDomain.entries.firstOrNull { it.wireValue == wireDomain }
                    ?: throw IllegalArgumentException("Unknown protected record domain")
                val length = input.readInt()
                require(length in 0..NativeStringRecord.MAX_UTF16_LENGTH) { "Invalid protected UTF-16 length" }
                val nonce = ByteArray(12).also { input.readFully(it) }
                val ciphertext = ByteArray(length * 2 + 16).also { input.readFully(it) }
                require(ids.add(id)) { "Duplicate protected record ID" }
                records += NativeStringRecord(id, domain, length, nonce, ciphertext)
            }
            require(input.read() == -1) { "Unexpected trailing protected record data" }
            records
        }
}
