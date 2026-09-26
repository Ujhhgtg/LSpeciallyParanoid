package dev.ujhhgtg.lsparanoid.processor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream

/** Executes actual processor output, including generated decoder bytecode, in an isolated loader. */
class GeneratedBytecodeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `literal and field replacements preserve UTF16 and remove unused constant pool text`() {
        val values = linkedMapOf(
            "empty" to "",
            "ascii" to "private-literal-sentinel-704267",
            "unicode" to "中文\u0000\ud800unpaired\udfff𝄞",
            "longValue" to "chunk-boundary-".repeat(1700),
            "duplicate" to "private-literal-sentinel-704267",
        )
        val input = fixture("fixture/Literals") {
            visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "CONSTANT", "Ljava/lang/String;", null,
                "private-field-sentinel-290384").visitEnd()
            visitField(ACC_PUBLIC, "constructed", "Ljava/lang/String;", null, null).visitEnd()
            visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(ALOAD, 0)
                visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(ALOAD, 0)
                visitLdcInsn("private-constructor-sentinel-892347")
                visitFieldInsn(PUTFIELD, "fixture/Literals", "constructed", "Ljava/lang/String;")
                visitInsn(RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            values.forEach { (name, value) -> stringMethod(name, value) }
        }
        val sites = CoverageAnalyzer().inspect(input, true)
        assertTrue(sites.all { it.status == "protected" })
        assertEquals(values.size + 2, sites.size)
        val unselected = CoverageAnalyzer().inspect(input, false)
        assertTrue(unselected.all { it.reason == "class-not-selected" && it.status == "excluded" })
        val classes = process(mapOf("fixture/Literals.class" to input)).classes
        val output = classes.getValue("fixture/Literals.class")
        for (sentinel in listOf(values.getValue("ascii"), "private-field-sentinel-290384",
            "private-constructor-sentinel-892347")) {
            assertFalse(String(output, Charsets.ISO_8859_1).contains(sentinel), sentinel)
        }
        val type = OutputClassLoader(classes).loadClass("fixture.Literals")
        values.forEach { (name, value) -> assertEquals(value, type.getMethod(name).invoke(null)) }
        assertEquals("private-field-sentinel-290384", type.getField("CONSTANT").get(null))
        assertEquals("private-constructor-sentinel-892347",
            type.getField("constructed").get(type.getConstructor().newInstance()))
        assertTrue(classes.keys.count { it.contains("\$Chunk") } > 1, "Exercise chunk boundaries")
        assertEquals(0, stringInstructions(output), "Protected methods must call the generated decoder")
    }

    @Test
    fun `constant initialization composes with an existing static initializer`() {
        val classes = process(mapOf("fixture/Initialized.class" to fixture("fixture/Initialized") {
            visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "CONSTANT", "Ljava/lang/String;", null,
                "constant-before-clinit").visitEnd()
            visitField(ACC_PUBLIC or ACC_STATIC, "COPIED", "Ljava/lang/String;", null, null).visitEnd()
            visitMethod(ACC_STATIC, "<clinit>", "()V", null, null).apply {
                visitCode()
                visitFieldInsn(GETSTATIC, "fixture/Initialized", "CONSTANT", "Ljava/lang/String;")
                visitFieldInsn(PUTSTATIC, "fixture/Initialized", "COPIED", "Ljava/lang/String;")
                visitInsn(RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        })).classes
        val type = OutputClassLoader(classes).loadClass("fixture.Initialized")
        assertEquals("constant-before-clinit", type.getField("COPIED").get(null))
    }

    @Test
    fun `empty registry emits valid classes without an invalid tableswitch`() {
        val classes = process(mapOf("fixture/Empty.class" to fixture("fixture/Empty") {})).classes
        val loader = OutputClassLoader(classes)
        classes.keys.forEach { loader.loadClass(it.removeSuffix(".class").replace('/', '.')).declaredMethods }
        val decoder = loader.loadClass("dev.ujhhgtg.lsparanoid.Deobfuscator\$fixture")
        assertEquals("", decoder.getMethod("getString", Long::class.javaPrimitiveType).invoke(null, 0L))
        assertEquals(0, classes.keys.count { it.contains("\$Chunk") })
    }

    @Test
    fun `unsupported metadata interface constants and bootstrap arguments remain unchanged`() {
        val annotation = "public-annotation-sentinel-108337"
        val constant = "public-interface-sentinel-730871"
        val recipe = "public-concat-sentinel-001488=\u0001"
        val inputs = mapOf(
            "fixture/Metadata.class" to fixture("fixture/Metadata") {
                visitAnnotation("Lfixture/ExternalAnnotation;", false).apply {
                    visit("value", annotation)
                    visitEnd()
                }
                visitMethod(ACC_PUBLIC or ACC_STATIC, "concat", "(I)Ljava/lang/String;", null, null).apply {
                    visitCode()
                    visitVarInsn(ILOAD, 0)
                    visitInvokeDynamicInsn("makeConcatWithConstants", "(I)Ljava/lang/String;", Handle(
                        H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                            "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), recipe)
                    visitInsn(ARETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            },
            "fixture/Constants.class" to fixture("fixture/Constants", ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT) {
                visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "VALUE", "Ljava/lang/String;", null, constant).visitEnd()
            }
        )
        val coverage = inputs.values.flatMap { CoverageAnalyzer().inspect(it, true) }
        assertEquals(setOf("annotation-value", "bootstrap-argument", "interface-constant"),
            coverage.map { it.reason }.toSet())
        assertTrue(coverage.all { it.status == "excluded" })
        for (text in listOf(annotation, constant, recipe)) assertFalse(coverage.toString().contains(text))
        val classes = process(inputs).classes
        assertTrue(String(classes.getValue("fixture/Metadata.class"), Charsets.ISO_8859_1).contains(annotation))
        assertTrue(String(classes.getValue("fixture/Metadata.class"), Charsets.ISO_8859_1).contains(recipe))
        val loader = OutputClassLoader(classes)
        assertEquals(constant, loader.loadClass("fixture.Constants").getField("VALUE").get(null))
        assertEquals("public-concat-sentinel-001488=42",
            loader.loadClass("fixture.Metadata").getMethod("concat", Int::class.javaPrimitiveType).invoke(null, 42))
    }

    @Test
    fun `fixed seed produces identical jars with deterministic entry timestamps`() {
        val inputs = mapOf("fixture/Repeatable.class" to fixture("fixture/Repeatable") {
            stringMethod("value", "repeatable-value")
        })
        val first = process(inputs)
        val second = process(inputs)
        assertArrayEquals(first.jar, second.jar)
        JarInputStream(ByteArrayInputStream(first.jar)).use { jar ->
            generateSequence { jar.nextJarEntry }.forEach { assertEquals(0L, it.time, it.name) }
        }
    }

    private fun process(inputs: Map<String, ByteArray>): Result {
        inputs.forEach { (name, bytes) ->
            val path = directory.resolve(name)
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
        }
        val bytes = ByteArrayOutputStream()
        JarOutputStream(bytes).use { jar ->
            ParanoidProcessor(seed = 42, classpath = emptySet(), inputs = listOf(directory), output = jar,
                projectName = "fixture", classFilter = { it.startsWith("fixture.") }).process()
        }
        val classes = linkedMapOf<String, ByteArray>()
        JarInputStream(ByteArrayInputStream(bytes.toByteArray())).use { jar ->
            generateSequence { jar.nextJarEntry }.forEach { entry ->
                if (entry.name.endsWith(".class")) classes[entry.name] = jar.readBytes()
            }
        }
        return Result(bytes.toByteArray(), classes)
    }

    private data class Result(val jar: ByteArray, val classes: Map<String, ByteArray>)

    private fun fixture(name: String, access: Int = ACC_PUBLIC or ACC_SUPER, body: ClassWriter.() -> Unit): ByteArray {
        return ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS).apply {
            visit(V1_8, access, name, null, "java/lang/Object", null)
            body()
            visitEnd()
        }.toByteArray()
    }

    private fun ClassWriter.stringMethod(name: String, value: String) {
        visitMethod(ACC_PUBLIC or ACC_STATIC, name, "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(value)
            visitInsn(ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun stringInstructions(bytes: ByteArray): Int {
        var count = 0
        ClassReader(bytes).accept(object : ClassVisitor(ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
                exceptions: Array<out String>?): MethodVisitor = object : MethodVisitor(ASM9) {
                override fun visitLdcInsn(value: Any?) { if (value is String) count++ }
            }
        }, 0)
        return count
    }

    private class OutputClassLoader(private val classes: Map<String, ByteArray>) :
        ClassLoader(GeneratedBytecodeTest::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}
