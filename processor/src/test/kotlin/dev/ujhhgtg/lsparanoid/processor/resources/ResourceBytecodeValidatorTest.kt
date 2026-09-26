package dev.ujhhgtg.lsparanoid.processor.resources

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class ResourceBytecodeValidatorTest {
    @TempDir lateinit var temporary: Path
    private val namespace = "dev.example"
    private val protectedId = 0x69010001

    @Test fun `rejects direct resource field into TextView setText independently of annotations`() {
        val bytes = fixture {
            visitVarInsn(ALOAD, 0)
            resource("secret")
            visitMethodInsn(INVOKEVIRTUAL, "android/widget/TextView", "setText", "(I)V", false)
        }
        val error = assertThrows(IllegalStateException::class.java) { verify(bytes) }
        assertTrue(error.message!!.contains("string/secret"))
        assertTrue(error.message!!.contains("dev.example.Screen#use"))
        assertTrue(error.message!!.contains("instruction"))
    }

    @Test fun `preserves origin through local stores loads and tracks inlined integer IDs`() {
        val bytes = fixture {
            visitLdcInsn(protectedId)
            visitVarInsn(ISTORE, 3)
            visitVarInsn(ALOAD, 0)
            visitVarInsn(ILOAD, 3)
            visitMethodInsn(INVOKEVIRTUAL, "android/widget/TextView", "setText", "(I)V", false)
        }
        assertThrows(IllegalStateException::class.java) { verify(bytes) }
    }

    @Test fun `permits wrapped getter Compose and app helper contract`() {
        verify(fixture {
            visitVarInsn(ALOAD, 1)
            resource("secret")
            visitMethodInsn(INVOKEVIRTUAL, "android/content/res/Resources", "getString", "(I)Ljava/lang/String;", false)
            visitInsn(POP)
            resource("secret")
            visitInsn(ACONST_NULL)
            visitInsn(ICONST_0)
            visitMethodInsn(INVOKESTATIC, "androidx/compose/ui/res/StringResources_androidKt", "stringResource", "(ILandroidx/compose/runtime/Composer;I)Ljava/lang/String;", false)
            visitInsn(POP)
            resource("secret")
            visitMethodInsn(INVOKESTATIC, "dev/example/ResourceHelpers", "display", "(I)V", false)
        })
    }

    @Test fun `excluded unrelated and host namespace fields do not fail`() {
        verify(fixture {
            for (emit in listOf<MethodVisitor.() -> Unit>(
                { resource("public") },
                { visitLdcInsn(0x69010002) },
                { visitFieldInsn(GETSTATIC, "com/tencent/host/R\$string", "secret", "I") },
            )) {
                visitVarInsn(ALOAD, 0)
                emit()
                visitMethodInsn(INVOKEVIRTUAL, "android/widget/TextView", "setText", "(I)V", false)
            }
        })
    }

    @Test fun `checks Toast title argument positions and raw Resources getValue`() {
        assertThrows(IllegalStateException::class.java) {
            verify(fixture {
                visitVarInsn(ALOAD, 2)
                resource("secret")
                visitInsn(ICONST_0)
                visitMethodInsn(INVOKESTATIC, "android/widget/Toast", "makeText", "(Landroid/content/Context;II)Landroid/widget/Toast;", false)
                visitInsn(POP)
            })
        }
        assertThrows(IllegalStateException::class.java) {
            verify(fixture {
                visitVarInsn(ALOAD, 1)
                resource("secret")
                visitInsn(ACONST_NULL)
                visitInsn(ICONST_1)
                visitMethodInsn(INVOKEVIRTUAL, "android/content/res/Resources", "getValue", "(ILandroid/util/TypedValue;Z)V", false)
            })
        }
    }

    @Test fun `Menu add examines title resource argument rather than group or item identifiers`() {
        assertThrows(IllegalStateException::class.java) {
            verify(fixture {
                visitInsn(ACONST_NULL)
                repeat(3) { visitInsn(ICONST_0) }
                resource("secret")
                visitMethodInsn(INVOKEINTERFACE, "android/view/Menu", "add", "(IIII)Landroid/view/MenuItem;", true)
                visitInsn(POP)
            })
        }
        verify(fixture {
            visitInsn(ACONST_NULL)
            resource("secret") // A resource ID may legally be used as a menu group identity.
            visitInsn(ICONST_0)
            visitInsn(ICONST_0)
            resource("public")
            visitMethodInsn(INVOKEINTERFACE, "android/view/Menu", "add", "(IIII)Landroid/view/MenuItem;", true)
            visitInsn(POP)
        })
    }

    @Test fun `constant getIdentifier flows to unsafe sink but is allowed with wrapped getter`() {
        fun MethodVisitor.lookup() {
            visitVarInsn(ALOAD, 1)
            visitLdcInsn("secret")
            visitLdcInsn("string")
            visitLdcInsn(namespace)
            visitMethodInsn(INVOKEVIRTUAL, "android/content/res/Resources", "getIdentifier", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I", false)
        }
        assertThrows(IllegalStateException::class.java) {
            verify(fixture {
                visitVarInsn(ALOAD, 0)
                lookup()
                visitMethodInsn(INVOKEVIRTUAL, "android/widget/TextView", "setText", "(I)V", false)
            })
        }
        verify(fixture {
            visitVarInsn(ALOAD, 1)
            lookup()
            visitMethodInsn(INVOKEVIRTUAL, "android/content/res/Resources", "getString", "(I)Ljava/lang/String;", false)
            visitInsn(POP)
        })
    }

    @Test fun `resolves inherited framework setters on app subclass and reads jar inputs`() {
        val bytes = fixture(parent = "android/app/Activity") {
            visitInsn(ACONST_NULL)
            resource("secret")
            visitMethodInsn(INVOKEVIRTUAL, "dev/example/Screen", "setTitle", "(I)V", false)
        }
        val jar = temporary.resolve("classes.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("dev/example/Screen.class"))
            it.write(bytes)
            it.closeEntry()
        }
        val (symbols, report) = metadata()
        assertThrows(IllegalStateException::class.java) {
            ResourceBytecodeValidator.verify(listOf(jar), symbols, report, namespace)
        }
    }

    @Test fun `application override remains explicit helper contract`() {
        val bytes = fixture(parent = "android/app/Activity", customTitle = true) {
            visitInsn(ACONST_NULL)
            resource("secret")
            visitMethodInsn(INVOKEVIRTUAL, "dev/example/Screen", "setTitle", "(I)V", false)
        }
        verify(bytes)
    }

    private fun MethodVisitor.resource(name: String) = visitFieldInsn(GETSTATIC, "dev/example/R\$string", name, "I")

    private fun fixture(parent: String = "java/lang/Object", customTitle: Boolean = false, body: MethodVisitor.() -> Unit): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(V17, ACC_PUBLIC, "dev/example/Screen", null, parent, null)
        writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "use", "(Landroid/widget/TextView;Landroid/content/res/Resources;Landroid/content/Context;)V", null, null).apply {
            visitCode()
            body()
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        if (customTitle) writer.visitMethod(ACC_PUBLIC, "setTitle", "(I)V", null, null).apply {
            visitCode(); visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun metadata(): Pair<Path, Path> {
        val symbols = temporary.resolve("R.txt")
        Files.writeString(symbols, "int string secret 0x69010001\nint string public 0x69010002\n")
        val report = temporary.resolve("resources.tsv")
        Files.writeString(report, "resource\tconfiguration\tstatus\treason\tsource\nstring/secret\tvalues\tprotected\tcontract\tres.xml\nstring/public\tvalues\texcluded\texplicit\tres.xml\n")
        return symbols to report
    }

    private fun verify(bytes: ByteArray) {
        val directory = temporary.resolve("classes")
        Files.createDirectories(directory)
        Files.write(directory.resolve("Screen.class"), bytes)
        val (symbols, report) = metadata()
        ResourceBytecodeValidator.verify(listOf(directory), symbols, report, namespace)
    }
}
