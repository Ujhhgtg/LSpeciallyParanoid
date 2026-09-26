package dev.ujhhgtg.lsparanoid.processor.resources

import com.android.aapt.Resources
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Sources in the same AGP layer have the same priority; a larger number overrides a smaller one. */
data class ResourceSourceDirectory(val path: Path, val priority: Int)

data class ResourceProtectionResult(val protectedEntries: Set<String>, val recordCount: Int, val report: List<ResourceReportEntry>)

data class ResourceReportEntry(
    val resource: String,
    val configuration: String,
    val source: String,
    val status: String,
    val reason: String,
)

/**
 * Protects explicitly selected, first-party values. Selection is a caller contract that all reads use
 * LspResourceContext. This inventory cannot prove that an ID never escapes to third-party code.
 * Android's own compiler is the normalization oracle; its protobuf is read using the official schema.
 */
object ResourceProtector {
    private val reference = Regex("(?<![\\\\\\w])@(?:\\+|\\*)?(?:(?<package>[A-Za-z0-9_.]+):)?(?<type>string|plurals|array)/(?<name>[A-Za-z0-9_.]+)")
    private val namespacePattern = Regex("[0-9a-f]{32}")

    @JvmStatic
    fun protect(
        sourceDirectories: List<ResourceSourceDirectory>,
        manifests: List<Path>,
        includes: Set<String>,
        excludes: Set<String>,
        namespace: String,
        aapt2: Path,
        workspaceDirectory: Path,
        overlayDirectory: Path,
        reportFile: Path,
        register: (String) -> Long,
    ): ResourceProtectionResult {
        require(namespacePattern.matches(namespace)) { "Resource namespace must be 32 lowercase hexadecimal characters" }
        (includes + excludes).forEach(::validatePattern)
        val definitions = linkedMapOf<Pair<String, String>, Definition>()
        val report = mutableListOf<ResourceReportEntry>()
        val unsafe = linkedMapOf<String, MutableSet<String>>()
        fun exclude(key: String, reason: String) { unsafe.getOrPut(key) { linkedSetOf() }.add(reason) }
        fun inspectConsumer(node: Node, source: Path) {
            references(node).forEach { exclude(it, "xml-consumer:$source") }
        }

        for (source in sourceDirectories.sortedWith(compareBy({ it.priority }, { it.path.toString() }))) {
            if (!Files.isDirectory(source.path)) continue
            Files.walk(source.path).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }.sorted().forEach { file ->
                    val folder = source.path.relativize(file).getName(0).toString()
                    if (folder == "raw" || folder.startsWith("raw-")) return@forEach
                    val document = parse(file)
                    if (folder != "values" && !folder.startsWith("values-")) {
                        inspectConsumer(document.documentElement, file)
                        return@forEach
                    }
                    require(document.documentElement.tagName == "resources") { "Expected <resources> in $file" }
                    for (element in document.documentElement.children()) {
                        val type = when (element.tagName) {
                            "string" -> "string"
                            "plurals" -> "plurals"
                            "string-array" -> "array"
                            "item" -> element.getAttribute("type").takeIf { it in setOf("string", "plurals", "array") }
                            // Generic arrays can include integers/typed values; leave them alone.
                            else -> null
                        }
                        if (type == null || !element.hasAttribute("name")) {
                            inspectConsumer(element, file)
                            // These definitions still participate in the resource namespace. Prevent
                            // protecting another configuration of an unsupported generic array.
                            if (element.tagName == "array" && element.hasAttribute("name")) {
                                val key = "array/${element.getAttribute("name")}"
                                exclude(key, "generic-typed-array")
                                report += ResourceReportEntry(key, folder, file.toString(), "excluded", "generic-typed-array")
                            }
                            continue
                        }
                        val key = "$type/${element.getAttribute("name")}"
                        if (element.hasAttribute("product")) {
                            exclude(key, "product-specific-definition")
                            report += ResourceReportEntry(key, "$folder [product=${element.getAttribute("product")}]", file.toString(), "excluded", "product-specific-definition")
                            inspectConsumer(element, file)
                            continue
                        }
                        val definition = Definition(key, folder, file, source.priority, element)
                        val old = definitions[key to folder]
                        require(old == null || old.priority != source.priority) {
                            "Duplicate resource $key ($folder) at equal priority: ${old?.source} and $file"
                        }
                        if (old != null) report += old.report("overridden", "higher-priority-source:$file")
                        definitions[key to folder] = definition
                    }
                }
            }
        }
        manifests.filter(Files::isRegularFile).forEach { inspectConsumer(parse(it).documentElement, it) }

        for (definition in definitions.values) {
            val element = definition.element
            if (!includes.any { matches(it, definition.key) }) exclude(definition.key, "outside-explicit-wrapper-contract")
            if (excludes.any { matches(it, definition.key) }) exclude(definition.key, "explicit-exclusion")
            if (element.hasAttribute("tools:node") || element.hasAttribute("tools:override")) {
                exclude(definition.key, "unsupported-tools-merge-directive")
            }
            val permitted = if (element.tagName in setOf("plurals", "string-array")) element.children() else listOf(element)
            if (permitted.any { it.children().isNotEmpty() }) exclude(definition.key, "styled-or-nested-content")
            if (element.tagName == "item") exclude(definition.key, "top-level-alias-or-typed-item")
            val refs = references(element)
            if (refs.isNotEmpty()) exclude(definition.key, "resource-reference-or-alias")
        }
        // An excluded alias/array can expose its referenced target through a system consumer. Propagate
        // across all configurations, including aliases not selected for protection.
        var changed: Boolean
        do {
            changed = false
            for (definition in definitions.values) {
                if (!unsafe.containsKey(definition.key)) continue
                for (target in references(definition.element)) {
                    val reasons = unsafe.getOrPut(target) { linkedSetOf() }
                    if (reasons.add("referenced-by-excluded:${definition.key}")) changed = true
                }
            }
        } while (changed)

        val candidates = definitions.values.filter { !unsafe.containsKey(it.key) }
        Files.createDirectories(workspaceDirectory)
        Files.createDirectories(overlayDirectory)
        val normalized = linkedMapOf<Pair<String, String>, List<NormalizedItem>>()
        for ((folder, group) in candidates.groupBy { it.folder }.toSortedMap()) {
            normalized.putAll(normalize(aapt2, workspaceDirectory.resolve(folder), group))
        }
        // A non-string primitive (e.g. @null), missing entry or unsupported compiler representation
        // excludes every configuration of the same key, never a partial token/plaintext mixture.
        for (definition in candidates) {
            val values = normalized[definition.key to definition.folder]
            if (values == null) exclude(definition.key, "unsupported-compiled-value")
        }
        var count = 0
        val protected = linkedSetOf<String>()
        val selected = candidates.filter { !unsafe.containsKey(it.key) }
        for ((folder, group) in selected.groupBy { it.folder }.toSortedMap()) {
            val document = newDocument()
            val root = document.createElement("resources")
            document.appendChild(root)
            for (definition in group.sortedBy { it.key }) {
                val element = document.importNode(definition.element, true) as Element
                val values = normalized.getValue(definition.key to definition.folder)
                if (definition.key.startsWith("string/")) {
                    element.textContent = token(namespace, register(values.single().value))
                    count++
                } else {
                    val children = element.children()
                    for ((index, child) in children.withIndex()) {
                        val item = if (element.tagName == "plurals") {
                            values.single { it.selector == child.getAttribute("quantity") }
                        } else values[index]
                        child.textContent = token(namespace, register(item.value))
                        count++
                    }
                }
                // Formatting happens after decoding. Keep translatable and formatting declarations;
                // the token itself contains no format directives and must never be pseudolocalized.
                root.appendChild(element)
                protected += definition.key
                report += definition.report("protected", "explicit-wrapper-contract")
            }
            val target = overlayDirectory.resolve(folder).resolve("lsp_protected.xml")
            write(document, target)
        }
        definitions.values.filter { unsafe.containsKey(it.key) }.forEach {
            report += it.report("excluded", unsafe.getValue(it.key).sorted().joinToString(";"))
        }
        Files.createDirectories(reportFile.toAbsolutePath().parent)
        Files.writeString(reportFile, buildString {
            appendLine("resource\tconfiguration\tstatus\treason\tsource")
            for (row in report.sortedWith(compareBy({ it.resource }, { it.configuration }, { it.source }))) {
                appendLine(listOf(row.resource, row.configuration, row.status, row.reason, row.source).joinToString("\t") { escapeTsv(it) })
            }
            appendLine("# Coverage: first-party supplied source directories only; generated/dependency values require separate inventory.")
            appendLine("# Selection asserts access through LspResourceContext; arbitrary resource-ID flow is not statically proven.")
            appendLine("# Pseudolocalization must be disabled for protected variants.")
        })
        return ResourceProtectionResult(protected, count, report)
    }

    @JvmStatic
    fun token(namespace: String, id: Long): String = "~lsp1:$namespace:${java.lang.Long.toUnsignedString(id, 16).padStart(16, '0')}~"

    internal fun manifestReferences(manifest: Path): Set<String> = references(parse(manifest).documentElement)

    private data class Definition(val key: String, val folder: String, val source: Path, val priority: Int, val element: Element) {
        fun report(status: String, reason: String) = ResourceReportEntry(key, folder, source.toString(), status, reason)
    }
    private data class NormalizedItem(val selector: String, val value: String)

    private fun normalize(aapt2: Path, workspace: Path, definitions: List<Definition>): Map<Pair<String, String>, List<NormalizedItem>> {
        require(Files.isExecutable(aapt2)) { "AAPT2 executable unavailable: $aapt2" }
        val document = newDocument()
        val root = document.createElement("resources")
        document.appendChild(root)
        for (definition in definitions) root.appendChild(document.importNode(definition.element, true))
        // Each private compile contains a single qualifier directory, making configuration mapping
        // independent of AAPT's canonicalized protobuf Configuration fields and implicit versions.
        val folder = definitions.first().folder
        val resources = workspace.resolve("res")
        write(document, resources.resolve(folder).resolve("lsp_oracle.xml"))
        val manifest = workspace.resolve("AndroidManifest.xml")
        Files.writeString(manifest, "<manifest package=\"dev.lsparanoid.oracle\"/>")
        val compiled = workspace.resolve("compiled.zip")
        val linked = workspace.resolve("linked.apk")
        runAapt(aapt2, listOf("compile", "--dir", resources.toString(), "-o", compiled.toString()), workspace)
        runAapt(aapt2, listOf("link", "--static-lib", "--merge-only", "--no-resource-removal", "--no-resource-deduping",
            "--manifest", manifest.toString(), "-o", linked.toString(), compiled.toString()), workspace)
        val table = ZipFile(linked.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("resources.pb")) { "AAPT2 static output has no resources.pb" }
            zip.getInputStream(entry).use(Resources.ResourceTable::parseFrom)
        }
        val result = linkedMapOf<Pair<String, String>, List<NormalizedItem>>()
        for (pkg in table.packageList) for (type in pkg.typeList) for (entry in type.entryList) {
            val value = entry.configValueList.singleOrNull()?.value ?: continue
            val items: List<NormalizedItem>? = when {
                value.hasItem() -> plain(value.item)?.let { listOf(NormalizedItem("", it)) }
                value.hasCompoundValue() && value.compoundValue.hasArray() -> {
                    val entries = value.compoundValue.array.elementList
                    val strings = entries.map { plain(it.item) }
                    if (strings.any { it == null }) null else strings.mapIndexed { i, s -> NormalizedItem(i.toString(), s!!) }
                }
                value.hasCompoundValue() && value.compoundValue.hasPlural() -> {
                    val entries = value.compoundValue.plural.entryList
                    val strings = entries.map { plain(it.item) }
                    if (strings.any { it == null }) null else entries.mapIndexed { i, e -> NormalizedItem(e.arity.name.lowercase(), strings[i]!!) }
                }
                else -> null
            }
            if (items != null) result["${type.name}/${entry.name}" to folder] = items
        }
        return result
    }

    private fun plain(item: Resources.Item): String? = if (item.hasStr()) item.str.value else null

    private fun runAapt(aapt2: Path, args: List<String>, workspace: Path) {
        val log = workspace.resolve("aapt2-${args.first()}.log")
        val process = ProcessBuilder(listOf(aapt2.toString()) + args).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val code = process.waitFor()
        // AAPT diagnostics may quote source text; keep them in private build output instead of logs.
        check(code == 0) { "AAPT2 resource normalization failed ($code). Inspect private diagnostics: $log" }
    }

    private fun references(node: Node): Set<String> {
        val refs = linkedSetOf<String>()
        fun visit(current: Node) {
            val texts = mutableListOf<String>()
            if (current.nodeType == Node.TEXT_NODE || current.nodeType == Node.CDATA_SECTION_NODE) texts += current.nodeValue
            current.attributes?.let { attrs -> for (i in 0 until attrs.length) texts += attrs.item(i).nodeValue }
            for (text in texts) for (match in reference.findAll(text)) {
                if (match.groups["package"]?.value != "android") {
                    refs += "${match.groups["type"]!!.value}/${match.groups["name"]!!.value}"
                }
            }
            for (i in 0 until current.childNodes.length) visit(current.childNodes.item(i))
        }
        visit(node)
        return refs
    }

    private fun validatePattern(pattern: String) {
        require(Regex("(?:string|plurals|array)/(?:[A-Za-z0-9_.]+|\\*)").matches(pattern)) {
            "Resource selector must be type/name or type/*: $pattern"
        }
    }
    private fun matches(pattern: String, key: String) = if (pattern.endsWith("/*")) key.startsWith(pattern.removeSuffix("*")) else pattern == key
    private fun escapeTsv(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
    private fun Element.children(): List<Element> = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
    private fun newFactory() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    private fun newDocument() = newFactory().newDocumentBuilder().newDocument()
    private fun parse(path: Path) = newFactory().newDocumentBuilder().parse(path.toFile())
    private fun write(document: Document, path: Path) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val transformer = TransformerFactory.newInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        Files.newOutputStream(path).use { transformer.transform(DOMSource(document), StreamResult(it)) }
    }
}
