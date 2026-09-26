package dev.ujhhgtg.lsparanoid.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import java.security.KeyStore
import java.security.MessageDigest

/** Extracts public certificate identity only; private key bytes/passwords never enter outputs. */
@CacheableTask
abstract class NativeSigningTask : DefaultTask() {
    @get:Input abstract val explicitPins: SetProperty<String>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val keyStoreFile: RegularFileProperty
    @get:Input @get:Optional abstract val keyAlias: Property<String>
    @get:Internal abstract val storePassword: Property<String>
    @get:OutputFile abstract val certificatePins: RegularFileProperty

    @TaskAction fun extract() {
        val pins = if (explicitPins.get().isNotEmpty()) explicitPins.get().map(::canonicalPin) else {
            require(keyStoreFile.isPresent && keyAlias.isPresent && storePassword.isPresent) {
                "Native verification requires a signing configuration or explicit signerCertificateSha256 pins"
            }
            val password = storePassword.get().toCharArray()
            try {
                val store = KeyStore.getInstance(keyStoreFile.get().asFile, password)
                val certificate = requireNotNull(store.getCertificate(keyAlias.get())) { "Signing certificate alias was not found" }
                listOf(MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) })
            } finally { password.fill('\u0000') }
        }
        certificatePins.get().asFile.apply {
            parentFile.mkdirs()
            writeText(pins.distinct().sorted().joinToString("\n", postfix = "\n"))
        }
    }

    private fun canonicalPin(value: String): String = value.replace(":", "").trim().lowercase().also {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "A signing certificate pin must be a SHA-256 hexadecimal digest" }
    }
}
