package dev.ujhhgtg.lsparanoid.processor.resources

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ResourceProtectorTest {
    @TempDir lateinit var temporary: Path
    private val namespace = "0123456789abcdef0123456789abcdef"
    private val includes = setOf("string/*", "plurals/*", "array/*")

    @Test fun `normalizes real AAPT semantics and keeps separate duplicate occurrences`() {
        val source = temporary.resolve("main")
        xml(source, "values/other.xml", """
            <resources>
              <string name="escaped">  Hello   &amp;  world\n\'\"\u4e2d </string>
              <string name="quoted">" a   b "</string>
              <string name="empty"></string>
              <string name="percent" formatted="false">100%</string>
              <string-array name="array"><item>same</item><item>same</item></string-array>
              <plurals name="quantity"><item quantity="other">%1${'$'}d files</item><item quantity="one">One file</item></plurals>
            </resources>
        """.trimIndent())
        xml(source, "values-zh-rCN-night/extra.xml", "<resources><string name=\"quoted\">中文</string></resources>")
        val records = mutableListOf<String>()
        val result = protect(listOf(ResourceSourceDirectory(source, 0)), records = records)
        assertEquals(9, result.recordCount)
        assertTrue(records.contains("Hello & world\n'\"中"))
        assertTrue(records.contains(" a   b "))
        assertTrue(records.contains(""))
        assertEquals(2, records.count { it == "same" })
        val output = Files.readString(temporary.resolve("overlay/values/lsp_protected.xml"))
        assertFalse(output.contains("same"))
        assertFalse(output.contains("world"))
        assertEquals(8, Regex("~lsp1:$namespace:[0-9a-f]{16}~").findAll(output).map { it.value }.toSet().size)
        assertTrue(Files.exists(temporary.resolve("overlay/values-zh-rCN-night/lsp_protected.xml")))
    }

    @Test fun `excludes manifest and XML targets aliases and styled content across configurations`() {
        val source = temporary.resolve("main")
        xml(source, "values/data.xml", """
            <resources>
              <string name="public_target">Visible</string>
              <string name="alias">@string/public_target</string>
              <string name="label">Name</string>
              <string name="xml_value">Shown in layout</string>
              <string name="styled">Hello <b>world</b></string>
              <string name="safe">safe-value</string>
              <string-array name="refs"><item>@string/safe</item></string-array>
              <string name="still_safe">Secret</string>
            </resources>
        """.trimIndent())
        xml(source, "values-fr/data.xml", "<resources><string name=\"styled\">Sans style</string></resources>")
        xml(source, "layout/screen.xml", "<View xmlns:android=\"http://schemas.android.com/apk/res/android\" android:tag=\"@string/xml_value\"/>")
        val manifest = temporary.resolve("AndroidManifest.xml")
        Files.writeString(manifest, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"@string/label\"/></manifest>")
        val result = protect(listOf(ResourceSourceDirectory(source, 0)), listOf(manifest))
        assertEquals(setOf("string/still_safe"), result.protectedEntries)
        assertTrue(result.report.filter { it.resource == "string/styled" }.all { it.status == "excluded" })
        assertTrue(result.report.single { it.resource == "string/public_target" }.reason.contains("referenced-by-excluded"))
        assertFalse(Files.readString(temporary.resolve("report.tsv")).contains("safe-value"))
    }

    @Test fun `source priorities choose override but equal priority conflicts fail`() {
        val main = temporary.resolve("main")
        val flavor = temporary.resolve("flavor")
        xml(main, "values/data.xml", "<resources><string name=\"text\">base</string></resources>")
        xml(flavor, "values/data.xml", "<resources><string name=\"text\">flavor</string></resources>")
        val records = mutableListOf<String>()
        protect(listOf(ResourceSourceDirectory(main, 0), ResourceSourceDirectory(flavor, 1)), records = records)
        assertEquals(listOf("flavor"), records)
        assertThrows(IllegalArgumentException::class.java) {
            protect(listOf(ResourceSourceDirectory(main, 0), ResourceSourceDirectory(flavor, 0)))
        }
    }

    @Test fun `empty selection reports entries without native records or AAPT`() {
        val source = temporary.resolve("main")
        xml(source, "values/data.xml", "<resources><string name=\"text\">Visible</string></resources>")
        val result = ResourceProtector.protect(listOf(ResourceSourceDirectory(source, 0)), emptyList(), emptySet(), emptySet(), namespace,
            temporary.resolve("no-aapt"), temporary.resolve("work"), temporary.resolve("overlay"), temporary.resolve("report.tsv")) {
            error("No strings should be registered")
        }
        assertEquals(0, result.recordCount)
        assertEquals("outside-explicit-wrapper-contract", result.report.single().reason)
    }

    @Test fun `rejects external entity declarations`() {
        val source = temporary.resolve("main")
        xml(source, "values/data.xml", "<!DOCTYPE resources [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]><resources><string name=\"text\">&leak;</string></resources>")
        assertThrows(Exception::class.java) { protect(listOf(ResourceSourceDirectory(source, 0))) }
    }

    @Test fun `AAPT overlay removes original string from linked resource table`() {
        val source = temporary.resolve("main")
        val sentinel = "RESOURCE_SENTINEL_ONLY_IN_ORIGINAL_a72393"
        xml(source, "values/data.xml", "<resources><string name=\"secret\">$sentinel</string></resources>")
        protect(listOf(ResourceSourceDirectory(source, 0)))
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "${System.getProperty("user.home")}/android-sdk"
        val aapt = Path.of(sdk, "build-tools", "37.0.0", "aapt2")
        fun command(vararg args: String) {
            val process = ProcessBuilder(listOf(aapt.toString()) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        }
        val originalZip = temporary.resolve("original.zip")
        val overlayZip = temporary.resolve("overlay.zip")
        command("compile", "--dir", source.toString(), "-o", originalZip.toString())
        command("compile", "--dir", temporary.resolve("overlay").toString(), "-o", overlayZip.toString())
        val manifest = temporary.resolve("manifest.xml")
        Files.writeString(manifest, "<manifest package=\"dev.lsp.fixture\"/>")
        val linked = temporary.resolve("linked.apk")
        command("link", "--static-lib", "--merge-only", "--manifest", manifest.toString(), "-o", linked.toString(), originalZip.toString(), "-R", overlayZip.toString())
        java.util.zip.ZipFile(linked.toFile()).use { zip ->
            val bytes = zip.getInputStream(zip.getEntry("resources.pb")).use { it.readBytes() }
            assertFalse(bytes.toString(Charsets.UTF_8).contains(sentinel))
            assertTrue(bytes.toString(Charsets.UTF_8).contains("~lsp1:$namespace:"))
        }
    }

    @Test fun `merged dependency manifest rejects selected names before packaging`() {
        val manifest = temporary.resolve("merged.xml")
        val report = temporary.resolve("report.tsv")
        Files.writeString(report, "resource\tconfiguration\tstatus\treason\tsource\nstring/private_label\tvalues\tprotected\texplicit-wrapper-contract\torigin.xml\n")
        Files.writeString(manifest, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"@dev.example:string/private_label\"/></manifest>")
        val failure = assertThrows(IllegalStateException::class.java) { ResourceUsageValidator.verifyManifest(manifest, report) }
        assertTrue(failure.message!!.contains("string/private_label"))
        Files.writeString(manifest, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"@android:string/private_label\"/></manifest>")
        assertDoesNotThrow { ResourceUsageValidator.verifyManifest(manifest, report) }
    }

    private fun protect(sources: List<ResourceSourceDirectory>, manifests: List<Path> = emptyList(), records: MutableList<String> = mutableListOf()): ResourceProtectionResult {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "${System.getProperty("user.home")}/android-sdk"
        val aapt = Path.of(sdk, "build-tools", "37.0.0", "aapt2")
        assumeTrue(Files.isExecutable(aapt), "AAPT2 37.0.0 required for compiled-resource integration tests")
        return ResourceProtector.protect(sources, manifests, includes, emptySet(), namespace, aapt, temporary.resolve("work"), temporary.resolve("overlay"), temporary.resolve("report.tsv")) {
            records.add(it)
            Long.MIN_VALUE + records.size
        }
    }

    private fun xml(directory: Path, relative: String, text: String) {
        val file = directory.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
