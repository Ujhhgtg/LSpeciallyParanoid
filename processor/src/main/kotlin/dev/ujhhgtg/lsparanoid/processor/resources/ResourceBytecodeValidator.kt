package dev.ujhhgtg.lsparanoid.processor.resources

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * A deliberately local guard against obvious resource-ID escapes, independent of literal selection.
 * Traces direct R fields, inlined integer IDs and constant getIdentifier results through local copies
 * and branches to known Android sinks. It is not interprocedural analysis or a proof of wrapped access:
 * dynamic names, fields/arrays, reflective calls and arbitrary helpers remain the caller's contract.
 */
object ResourceBytecodeValidator {
    @JvmStatic
    fun verify(inputs: List<Path>, runtimeSymbols: Path, protectionReport: Path, applicationNamespace: String) {
        val selected = Files.readAllLines(protectionReport).asSequence()
            .map { it.split('\t') }.filter { it.size >= 3 && it[2] == "protected" }.map { it[0] }.toSet()
        if (selected.isEmpty()) return
        val ids = linkedMapOf<Int, MutableSet<String>>()
        for (line in Files.readAllLines(runtimeSymbols)) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size != 4 || fields[0] != "int") continue
            val key = "${fields[1]}/${fields[2]}"
            if (key !in selected) continue
            val integer = if (fields[3].startsWith("0x")) fields[3].substring(2).toLong(16).toInt() else fields[3].toInt()
            ids.getOrPut(integer) { linkedSetOf() }.add(key)
        }
        val classes = mutableMapOf<String, ClassInfo>()
        forEachClass(inputs) { bytes ->
            val reader = ClassReader(bytes)
            val members = mutableSetOf<String>()
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    members += name + descriptor
                    return null
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            classes[reader.className] = ClassInfo(reader.superName, reader.interfaces.toList(), members)
        }
        val appPrefix = applicationNamespace.replace('.', '/') + "/"
        val rPrefix = appPrefix + "R$"
        forEachClass(inputs) { bytes ->
            val node = ClassNode(Opcodes.ASM9)
            ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            for (method in node.methods) {
                val instructions = method.instructions.toArray()
                val sinks = instructions.mapIndexedNotNull { index, instruction ->
                    (instruction as? MethodInsnNode)?.let { call ->
                        unsupportedArguments(call, classes, appPrefix).takeIf { it.isNotEmpty() }?.let { index to it }
                    }
                }
                if (sinks.isEmpty()) continue
                val frames: Array<Frame<SourceValue>?> = try {
                    Analyzer(CopyPreservingInterpreter()).analyze(node.name, method)
                } catch (error: AnalyzerException) {
                    throw IllegalStateException("Cannot validate resource usage in ${node.name.replace('/', '.')}#${method.name}${method.desc}; bytecode analysis failed", error)
                }
                val indices = instructions.withIndex().associate { it.value to it.index }
                fun arguments(call: MethodInsnNode): List<SourceValue>? {
                    val frame = frames[indices.getValue(call)] ?: return null
                    val count = Type.getArgumentTypes(call.desc).size
                    return (0 until count).map { frame.getStack(frame.stackSize - count + it) }
                }
                fun strings(value: SourceValue): Set<String?> = value.insns.mapNotNullTo(linkedSetOf()) {
                    when {
                        it is LdcInsnNode && it.cst is String -> it.cst as String
                        else -> null
                    }
                }.let { values ->
                    if (value.insns.any { it.opcode == Opcodes.ACONST_NULL }) values + setOf<String?>(null) else values
                }
                fun lookup(call: MethodInsnNode): Set<String> {
                    if (call.name != "getIdentifier" || call.desc != "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I" ||
                        !hasAncestor(call.owner, classes) { it == "android/content/res/Resources" }) return emptySet()
                    val args = arguments(call) ?: return emptySet()
                    return buildSet {
                        for (name in strings(args[0])) for (type in strings(args[1])) for (pkg in strings(args[2])) {
                            resolveName(name, type, pkg, applicationNamespace)?.takeIf { it in selected }?.let(::add)
                        }
                    }
                }
                fun keys(value: SourceValue): Set<String> = buildSet {
                    for (source in value.insns) when (source) {
                        is LdcInsnNode -> (source.cst as? Int)?.let { ids[it]?.let(::addAll) }
                        is FieldInsnNode -> if (source.opcode == Opcodes.GETSTATIC && source.desc == "I" && source.owner.startsWith(rPrefix)) {
                            val type = source.owner.removePrefix(rPrefix)
                            val key = "$type/${source.name}"
                            if (type in setOf("string", "plurals", "array") && key in selected) add(key)
                        }
                        is MethodInsnNode -> addAll(lookup(source))
                    }
                }
                for ((index, positions) in sinks) {
                    val call = instructions[index] as MethodInsnNode
                    val values = arguments(call) ?: continue // Dead instructions do not execute.
                    val reached = positions.flatMapTo(linkedSetOf()) { position ->
                        if (Type.getArgumentTypes(call.desc)[position].descriptor == "Ljava/lang/String;") {
                            strings(values[position]).mapNotNull { resolveName(it, "string", null, applicationNamespace) }.filter { it in selected }
                        } else keys(values[position])
                    }
                    check(reached.isEmpty()) {
                        "Protected resources ${reached.sorted().joinToString()} reach unsupported framework resource API " +
                            "${call.owner.replace('/', '.')}.${call.name}${call.desc} at ${node.name.replace('/', '.')}#${method.name}${method.desc} " +
                            "instruction $index. Decode through LspResourceContext before passing text, or exclude these resources and rebuild. " +
                            "wrappedResourceAccess does not permit passing protected IDs to framework consumers."
                    }
                }
            }
        }
    }

    private class CopyPreservingInterpreter : SourceInterpreter(Opcodes.ASM9) {
        // ASM's default interpreter replaces the origin at every ILOAD/ISTORE/DUP, hiding R constants.
        override fun copyOperation(instruction: AbstractInsnNode, value: SourceValue): SourceValue = value
    }

    private data class ClassInfo(val parent: String?, val interfaces: List<String>, val members: Set<String>)

    private fun unsupportedArguments(call: MethodInsnNode, classes: Map<String, ClassInfo>, appPrefix: String): Set<Int> {
        val types = Type.getArgumentTypes(call.desc).map { it.descriptor }
        if (types.isEmpty()) return emptySet()
        // An actual application override/helper owns its contract. An inherited framework method on
        // an application subclass must still be checked (e.g. MyActivity.setTitle(resourceId)).
        var declaring: String? = call.owner
        val visited = mutableSetOf<String>()
        while (declaring != null && visited.add(declaring)) {
            val info = classes[declaring] ?: break
            if (call.name + call.desc in info.members) {
                if (declaring.startsWith(appPrefix)) return emptySet()
                break
            }
            declaring = info.parent
        }
        fun family(predicate: (String) -> Boolean) = hasAncestor(call.owner, classes, predicate)
        if (family { it == "android/content/res/Resources" }) {
            if (call.name == "getValue" && types in listOf(listOf("I", "Landroid/util/TypedValue;", "Z"), listOf("Ljava/lang/String;", "Landroid/util/TypedValue;", "Z"))) return setOf(0)
            if (call.name == "getValueForDensity" && types == listOf("I", "I", "Landroid/util/TypedValue;", "Z")) return setOf(0)
            if (call.name == "obtainTypedArray" && types == listOf("I")) return setOf(0)
        }
        if (call.owner == "android/widget/Toast" && call.name == "makeText" && types == listOf("Landroid/content/Context;", "I", "I")) return setOf(1)
        if (family { it == "android/view/Menu" || it == "android/view/SubMenu" || it.startsWith("androidx/appcompat/view/menu/") }) {
            if (call.name in setOf("add", "addSubMenu")) {
                if (types == listOf("I")) return setOf(0)
                if (types == listOf("I", "I", "I", "I")) return setOf(3)
            }
        }
        if (family { it == "android/view/MenuItem" || it == "android/view/SubMenu" || it.startsWith("androidx/appcompat/view/menu/") }) {
            if (call.name in setOf("setTitle", "setHeaderTitle") && types == listOf("I")) return setOf(0)
        }
        if (types[0] != "I") return emptySet()
        if (family { it.startsWith("android/widget/") || it.startsWith("androidx/appcompat/widget/") || it.startsWith("com/google/android/material/") }) {
            if (call.name in setOf("setText", "setHint") && (types == listOf("I") || types == listOf("I", "Landroid/widget/TextView\$BufferType;"))) return setOf(0)
        }
        if (family { it == "android/app/Activity" || it == "android/app/Dialog" || it == "android/app/AlertDialog\$Builder" ||
                    it == "androidx/activity/ComponentActivity" || it == "androidx/core/app/ComponentActivity" ||
                    it.startsWith("androidx/appcompat/app/") || it == "androidx/fragment/app/FragmentActivity" ||
                    it == "com/google/android/material/dialog/MaterialAlertDialogBuilder" }) {
            if (call.name in setOf("setTitle", "setMessage") && types == listOf("I")) return setOf(0)
            if (call.name in setOf("setPositiveButton", "setNegativeButton", "setNeutralButton") && types == listOf("I", "Landroid/content/DialogInterface\$OnClickListener;")) return setOf(0)
        }
        if (family { it.startsWith("android/preference/") || it.startsWith("androidx/preference/") }) {
            if (call.name in setOf("setTitle", "setSummary", "setDialogTitle", "setDialogMessage", "setPositiveButtonText", "setNegativeButtonText", "setEntries", "setEntryValues") && types == listOf("I")) return setOf(0)
        }
        return emptySet()
    }

    private fun hasAncestor(owner: String, classes: Map<String, ClassInfo>, predicate: (String) -> Boolean): Boolean {
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.add(owner)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            if (predicate(current)) return true
            classes[current]?.let { info ->
                info.parent?.let(pending::addLast)
                info.interfaces.forEach(pending::addLast)
            }
        }
        return false
    }

    private fun resolveName(name: String?, defaultType: String?, defaultPackage: String?, namespace: String): String? {
        if (name == null) return null
        var value = name.removePrefix("@")
        val pkg = if (':' in value) value.substringBefore(':').also { value = value.substringAfter(':') } else defaultPackage
        if (pkg != null && pkg != namespace) return null
        val type = if ('/' in value) value.substringBefore('/') else defaultType
        val entry = value.substringAfter('/')
        if (type !in setOf("string", "plurals", "array") || !entry.matches(Regex("[A-Za-z0-9_.]+"))) return null
        return "$type/$entry"
    }

    private fun forEachClass(inputs: List<Path>, consumer: (ByteArray) -> Unit) {
        for (path in inputs.sortedBy { it.toString() }) {
            if (Files.isDirectory(path)) {
                Files.walk(path).use { files -> files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.sorted().forEach { consumer(Files.readAllBytes(it)) } }
            } else if (path.toString().endsWith(".class")) {
                consumer(Files.readAllBytes(path))
            } else {
                ZipFile(path.toFile()).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.sortedBy { it.name }.forEach { entry ->
                        zip.getInputStream(entry).use { consumer(it.readBytes()) }
                    }
                }
            }
        }
    }
}
