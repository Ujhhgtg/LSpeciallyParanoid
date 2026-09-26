package dev.ujhhgtg.lsparanoid.processor.nativebackend

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NativeBackendTest {
    @TempDir lateinit var temp: Path
    private fun spec(name: String = "test:release") = NativeBuildSpec.create(ByteArray(32) { it.toByte() }, name)

    @Test fun `build entropy and module identity separate names and keys`() {
        assertEquals(spec().buildId, spec().buildId)
        assertEquals(32, spec().namespace.length)
        assertNotEquals(spec().buildId, spec("other:release").buildId)
        assertNotEquals(spec().libraryName, NativeBuildSpec.create(ByteArray(32) { 1 }, "test:release").libraryName)
        assertThrows(IllegalArgumentException::class.java) { NativeBuildSpec.create(ByteArray(4), "bad") }
        assertFalse(spec().key(RecordDomain.LITERAL).contentEquals(spec().key(RecordDomain.RESOURCE)))
    }

    @Test fun `each occurrence receives independent ciphertext and domain identity`() {
        val literals = NativeStringRegistry(spec())
        val a = literals.registerString("identical")
        val b = literals.registerString("identical")
        assertNotEquals(a, b)
        assertTrue(a >= 0 && b >= 0)
        val resource = NativeStringRegistry(spec(), RecordDomain.RESOURCE)
        assertTrue(resource.registerString("identical") < 0)
        val records = literals.records()
        assertFalse(records[0].nonce.contentEquals(records[1].nonce))
        assertFalse(records[0].ciphertext.contentEquals(records[1].ciphertext))
        val same = NativeStringRegistry(spec()).apply { registerString("identical") }.records().single()
        assertArrayEquals(records[0].ciphertext, same.ciphertext)
        val copy = records[0].ciphertext
        copy[0] = (copy[0].toInt() xor 1).toByte()
        assertFalse(copy.contentEquals(records[0].ciphertext))
    }

    @Test fun `record files validate version build identity duplicate ids and truncation`() {
        val registry = NativeStringRegistry(spec()).apply { registerString("\u0000\ud800\udfff漢字") }
        val original = registry.records()
        val file = temp.resolve("records.bin").toFile()
        NativeRecordIO.write(file, spec(), original)
        val restored = NativeRecordIO.read(file, spec()).single()
        assertEquals(original.single().id, restored.id)
        assertArrayEquals(original.single().ciphertext, restored.ciphertext)
        assertThrows(IllegalArgumentException::class.java) { NativeRecordIO.read(file, spec("wrong")) }
        assertThrows(IllegalArgumentException::class.java) { NativeRecordIO.write(file, spec(), original + original) }
        file.appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { NativeRecordIO.read(file, spec()) }
        file.writeBytes(file.readBytes().copyOf(4))
        assertThrows(java.io.EOFException::class.java) { NativeRecordIO.read(file, spec()) }
    }

    @Test fun `empty corpus emits no native source while empty string remains a record`() {
        assertFalse(NativeGenerator.generate(spec(), emptyList(), temp))
        assertFalse(temp.resolve("decoder.c").toFile().exists())
        val registry = NativeStringRegistry(spec()).apply { registerString("") }
        assertEquals(16, registry.records().single().ciphertext.size)
        assertTrue(NativeGenerator.generate(spec(), registry.records(), temp))
    }

    @Test fun `explicit JVM development bootstrap needs no library and rejects native decoding`() {
        NativeGenerator.generateJvmBootstrap(temp)
        val javaHome = File(System.getProperty("java.home"))
        val main = temp.resolve("JvmMain.java").toFile()
        main.writeText("""
            import dev.ujhhgtg.lsparanoid.generated.LspBootstrap;
            public class JvmMain {
                public static void main(String[] args) {
                    LspBootstrap.loadInstalled();
                    LspBootstrap.loadAbsolute(null);
                    if (!LspBootstrap.libraryFileName.isEmpty()) throw new AssertionError();
                    try { LspBootstrap.decode(1); throw new AssertionError(); }
                    catch (UnsupportedOperationException expected) {}
                }
            }
        """.trimIndent())
        run(File(javaHome, "bin/javac").path, "-d", temp.toString(),
            temp.resolve("dev/ujhhgtg/lsparanoid/generated/LspBootstrap.java").toString(), main.path)
        run(File(javaHome, "bin/java").path, "-cp", temp.toString(), "JvmMain")
    }

    @Test fun `JDK and vendored native implementation match RFC8439 section 2_8_2`() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val ad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val ciphertext = hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b6116")
        val tag = hex("1ae10b594f09e26a7e902ecbd0600691")
        val actual = Cipher.getInstance("ChaCha20-Poly1305").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            updateAAD(ad)
            doFinal(plaintext)
        }
        assertArrayEquals(ciphertext + tag, actual)
        val registry = NativeStringRegistry(spec()).apply { registerString("seed") }
        NativeGenerator.generate(spec(), registry.records(), temp)
        val source = temp.resolve("kat.c").toFile()
        source.writeText("""
            #include <string.h>
            #include "monocypher.h"
            static const uint8_t key[] = {${cBytes(key)}};
            static const uint8_t nonce[] = {${cBytes(nonce)}};
            static const uint8_t ad[] = {${cBytes(ad)}};
            static const uint8_t plain[] = {${cBytes(plaintext)}};
            static const uint8_t expected[] = {${cBytes(ciphertext)}};
            static const uint8_t expected_tag[] = {${cBytes(tag)}};
            int main(void) {
                uint8_t encrypted[sizeof(plain)], tag[16], recovered[sizeof(plain)];
                crypto_aead_ctx ctx;
                crypto_aead_init_ietf(&ctx, key, nonce);
                crypto_aead_write(&ctx, encrypted, tag, ad, sizeof(ad), plain, sizeof(plain));
                if (memcmp(encrypted, expected, sizeof(expected)) || memcmp(tag, expected_tag, 16)) return 1;
                crypto_aead_init_ietf(&ctx, key, nonce);
                if (crypto_aead_read(&ctx, recovered, tag, ad, sizeof(ad), encrypted, sizeof(encrypted))) return 2;
                if (memcmp(recovered, plain, sizeof(plain))) return 3;
                encrypted[0] ^= 1;
                memset(recovered, 0x55, sizeof(recovered));
                crypto_aead_init_ietf(&ctx, key, nonce);
                if (crypto_aead_read(&ctx, recovered, tag, ad, sizeof(ad), encrypted, sizeof(encrypted)) != -1) return 4;
                for (unsigned i = 0; i < sizeof(recovered); ++i) if (recovered[i] != 0x55) return 5;
                crypto_wipe(&ctx, sizeof(ctx));
                return 0;
            }
        """.trimIndent())
        run("cc", "-std=c99", "-O2", source.path, temp.resolve("monocypher.c").toString(), "-o", temp.resolve("kat").toString())
        run(temp.resolve("kat").toString())
    }

    @Test fun `native JNI decoder preserves UTF16 and uses explicit module classloader bootstrap`() {
        executeJni(false, false)
    }

    @Test fun `native JNI decoder loads installed library automatically`() {
        executeJni(true, false)
    }

    @Test fun `native JNI decoder rejects tampered record authentication`() {
        executeJni(false, true)
    }

    @Test fun `explicit bootstrap with empty corpus still loads a valid library`() {
        executeJni(false, false, empty = true)
    }

    private fun executeJni(automatic: Boolean, tamper: Boolean, empty: Boolean = false) {
        val build = spec("jni:$automatic:$tamper:$empty")
        val registry = NativeStringRegistry(build)
        val resourceRegistry = NativeStringRegistry(build, RecordDomain.RESOURCE)
        val values = if (empty) emptyList() else listOf("", "normal", "\u0000null\u0000", "\ud800", "\udfff", "\ud83d\ude80", "中文العربية", "repeat", "repeat", "long".repeat(30_000))
        val entries = values.map { registry.registerString(it) to it } +
            if (empty) emptyList() else listOf(resourceRegistry.registerString("resource %1\$s") to "resource %1\$s")
        val records = (registry.records() + resourceRegistry.records()).toMutableList()
        if (tamper) {
            val original = records[1]
            val damaged = original.ciphertext.also { it[0] = (it[0].toInt() xor 1).toByte() }
            records[1] = NativeStringRecord(original.id, original.domain, original.utf16Length, original.nonce, damaged)
        }
        val javaDir = temp.resolve("java").toFile()
        val nativeDir = temp.resolve("native").toFile()
        val classes = temp.resolve("classes").toFile().apply { mkdirs() }
        NativeGenerator.generateBootstrap(build, javaDir, automatic)
        NativeGenerator.generate(build, records, nativeDir, requireRuntime = !automatic)
        val payloadSource = File(nativeDir, "payload.h").readText()
        assertFalse(payloadSource.contains("normal"))
        val javaHome = File(System.getProperty("java.home"))
        val lib = File(nativeDir, build.libraryFileName)
        run("cc", "-std=c99", "-O2", "-shared", "-fPIC", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections", "-Wl,--version-script=${File(nativeDir, "exports.map")}", "-I${File(javaHome, "include")}", "-I${File(javaHome, "include/linux")}", File(nativeDir, "decoder.c").path, File(nativeDir, "monocypher.c").path, "-o", lib.path)
        val symbols = run("nm", "-D", "--defined-only", lib.path)
        assertEquals(1, symbols.lineSequence().count { it.isNotBlank() }, symbols)
        assertTrue(symbols.contains("JNI_OnLoad"), symbols)
        val expected = temp.resolve("expected.bin").toFile()
        DataOutputStream(expected.outputStream()).use { output ->
            output.writeInt(entries.size)
            entries.forEach { (id, value) -> output.writeLong(id); output.writeInt(value.length); value.forEach { output.writeChar(it.code) } }
        }
        val main = File(javaDir, "Main.java")
        main.writeText("""
            import java.io.*;
            import dev.ujhhgtg.lsparanoid.generated.LspBootstrap;
            public final class Main {
                public static void main(String[] args) throws Exception {
                    ${if (!automatic) """
                    try { LspBootstrap.decode(1); throw new AssertionError("Premature decode succeeded"); }
                    catch (UnsatisfiedLinkError expected) {}
                    try { LspBootstrap.loadAbsolute(new File("relative.so")); throw new AssertionError("Relative load succeeded"); }
                    catch (IllegalArgumentException expected) {}
                    LspBootstrap.loadAbsolute(new File(args[0]));
                    LspBootstrap.loadAbsolute(new File(args[0]));
                    """ else ""}
                    try (DataInputStream input = new DataInputStream(new FileInputStream(args[1]))) {
                        int count = input.readInt();
                        for (int i = 0; i < count; ++i) {
                            long id = input.readLong(); int length = input.readInt();
                            char[] chars = new char[length];
                            for (int j = 0; j < length; ++j) chars[j] = input.readChar();
                            if (${tamper} && i == 1) {
                                try { LspBootstrap.decode(id); throw new AssertionError("Damaged record succeeded"); }
                                catch (IllegalStateException expected) {}
                            } else {
                                String wanted = new String(chars);
                                String actual = LspBootstrap.decode(id);
                                if (!wanted.equals(actual)) throw new AssertionError("UTF-16 mismatch at record " + i);
                            }
                        }
                    }
                    try { LspBootstrap.decode(-1); throw new AssertionError("Unknown ID succeeded"); }
                    catch (IllegalArgumentException expected) {}
                }
            }
        """.trimIndent())
        val javaFiles = javaDir.walkTopDown().filter { it.extension == "java" }.map { it.absolutePath }.toList()
        run(*(listOf(File(javaHome, "bin/javac").path, "-d", classes.path) + javaFiles).toTypedArray())
        if (automatic) {
            run(File(javaHome, "bin/java").path, "-Xcheck:jni", "-Djava.library.path=${nativeDir.path}", "-cp", classes.path, "Main", lib.path, expected.path)
        } else {
            // Simulate an injected module: generated classes belong to a separate child loader.
            val runnerDir = temp.resolve("host").toFile().apply { mkdirs() }
            val runner = File(runnerDir, "HostRunner.java")
            runner.writeText("""
                import java.net.*;
                import java.io.*;
                public class HostRunner {
                    public static void main(String[] args) throws Exception {
                        try (URLClassLoader module = new URLClassLoader(
                            new URL[]{new File(args[0]).toURI().toURL()}, ClassLoader.getPlatformClassLoader())) {
                            Class<?> entry = Class.forName("Main", true, module);
                            if (entry.getClassLoader() != module) throw new AssertionError("Wrong module loader");
                            entry.getMethod("main", String[].class).invoke(null, (Object)new String[]{args[1], args[2]});
                        }
                    }
                }
            """.trimIndent())
            run(File(javaHome, "bin/javac").path, "-d", runnerDir.path, runner.path)
            run(File(javaHome, "bin/java").path, "-Xcheck:jni", "-cp", runnerDir.path, "HostRunner", classes.path, lib.path, expected.path)
        }
    }

    private fun run(vararg command: String): String {
        val output = temp.resolve("process-${System.nanoTime()}.log").toFile()
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(output).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Process timed out: ${command.first()}")
        val text = output.readText()
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")}\n$text")
        return text
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun cBytes(bytes: ByteArray): String = bytes.joinToString(",") { (it.toInt() and 255).toString() }
}
