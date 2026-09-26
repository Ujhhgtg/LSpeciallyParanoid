package dev.ujhhgtg.lsparanoid.processor.resources

import java.nio.file.Files
import java.nio.file.Path

/** Late validation may fail a selected entry, but must never rewrite an already consumed overlay. */
object ResourceUsageValidator {
    /**
     * Check the final merged manifest, including dependency contributions, after resource generation.
     * Invoke before native packaging; making the early overlay depend on this artifact creates a
     * resource/manifest task cycle in AGP. Values in this report are identifiers, never plaintext.
     */
    @JvmStatic
    fun verifyManifest(manifest: Path, protectionReport: Path) {
        val selected = Files.readAllLines(protectionReport).asSequence()
            .filterNot { it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size >= 3 && it[2] == "protected" }
            .map { it[0] }
            .toSet()
        if (selected.isEmpty()) return
        val escaped = ResourceProtector.manifestReferences(manifest).intersect(selected)
        check(escaped.isEmpty()) {
            "Protected resources are consumed by the merged manifest: ${escaped.sorted().joinToString()}. " +
                "Exclude these resources from protection and rebuild; Android reads manifest values " +
                "outside the resource adapter. Manifest: $manifest"
        }
    }
}
