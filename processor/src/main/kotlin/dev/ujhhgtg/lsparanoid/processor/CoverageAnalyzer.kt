package dev.ujhhgtg.lsparanoid.processor

import com.joom.grip.io.FileSource
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.TypePath

/** Structural coverage only. Reports deliberately never contain original string contents. */
data class StringCoverageSite(
    val className: String,
    val member: String,
    val location: String,
    val status: String,
    val reason: String,
)

class CoverageAnalyzer(private val asmApi: Int = Opcodes.ASM9) {
    fun analyze(sources: Iterable<FileSource>, analysisResult: AnalysisResult): List<StringCoverageSite> {
        val selected = analysisResult.configurationsByType.keys.mapTo(mutableSetOf()) { it.internalName }
        return sources.flatMap { source ->
            val names = mutableListOf<String>()
            source.listFiles { name, type -> if (type == FileSource.EntryType.CLASS) names += name }
            names.sorted().flatMap { name ->
                val bytes = source.readFile(name)
                inspect(bytes, ClassReader(bytes).className in selected)
            }
        }
    }

    fun inspect(classBytes: ByteArray, selected: Boolean): List<StringCoverageSite> {
        val reader = ClassReader(classBytes)
        val className = reader.className.replace('/', '.')
        val sites = mutableListOf<StringCoverageSite>()
        val isInterface = reader.access and Opcodes.ACC_INTERFACE != 0

        fun record(member: String, location: String, protected: Boolean, reason: String) {
            sites += StringCoverageSite(className, member, location,
                if (selected && protected) "protected" else "excluded",
                if (!selected) "class-not-selected" else reason)
        }

        fun annotation(member: String, location: String): AnnotationVisitor {
            return object : AnnotationVisitor(asmApi) {
                private var index = 0
                override fun visit(name: String?, value: Any?) {
                    if (value is String) record(member, "$location/${name ?: index++}", false, "annotation-value")
                }
                override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor =
                    annotation(member, "$location/${name ?: index++}/$descriptor")
                override fun visitArray(name: String?): AnnotationVisitor =
                    annotation(member, "$location/${name ?: index++}")
            }
        }

        fun bootstrap(member: String, location: String, value: Any?) {
            when (value) {
                is String -> record(member, location, false, "bootstrap-argument")
                is ConstantDynamic -> for (i in 0 until value.bootstrapMethodArgumentCount) {
                    bootstrap(member, "$location/constant-dynamic/$i", value.getBootstrapMethodArgument(i))
                }
            }
        }

        reader.accept(object : ClassVisitor(asmApi) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
                annotation("<class>", "annotation/$descriptor")

            override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String,
                visible: Boolean): AnnotationVisitor = annotation("<class>", "type-annotation/$typeRef/$descriptor")

            override fun visitRecordComponent(name: String, descriptor: String,
                signature: String?): RecordComponentVisitor = object : RecordComponentVisitor(asmApi) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
                    annotation(name, "record-annotation/$descriptor")
                override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String,
                    visible: Boolean): AnnotationVisitor = annotation(name, "record-type-annotation/$typeRef/$descriptor")
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?,
                value: Any?): FieldVisitor {
                if (value is String) {
                    val constant = access and (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) ==
                        (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)
                    record(name, "constant-value", constant && !isInterface,
                        when { isInterface -> "interface-constant"; constant -> "static-constant"; else -> "unsupported-field-constant" })
                }
                return object : FieldVisitor(asmApi) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
                        annotation(name, "field-annotation/$descriptor")
                    override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String,
                        visible: Boolean): AnnotationVisitor = annotation(name, "field-type-annotation/$typeRef/$descriptor")
                }
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
                exceptions: Array<out String>?): MethodVisitor {
                val member = name + descriptor
                return object : MethodVisitor(asmApi) {
                    private var literal = 0
                    private var dynamic = 0
                    override fun visitLdcInsn(value: Any?) {
                        if (value is String) record(member, "ldc/${literal++}", true, "literal")
                        else if (value is ConstantDynamic) bootstrap(member, "ldc/${literal++}", value)
                    }
                    override fun visitInvokeDynamicInsn(name: String, descriptor: String,
                        bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any) {
                        val invocation = dynamic++
                        bootstrapMethodArguments.forEachIndexed { index, value ->
                            bootstrap(member, "invokedynamic/$invocation/$index", value)
                        }
                    }
                    override fun visitAnnotationDefault(): AnnotationVisitor = annotation(member, "annotation-default")
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
                        annotation(member, "method-annotation/$descriptor")
                    override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String,
                        visible: Boolean): AnnotationVisitor = annotation(member, "method-type-annotation/$typeRef/$descriptor")
                    override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor =
                        annotation(member, "parameter-annotation/$parameter/$descriptor")
                    override fun visitInsnAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String,
                        visible: Boolean): AnnotationVisitor = annotation(member, "instruction-annotation/$typeRef/$descriptor")
                    override fun visitTryCatchAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String,
                        visible: Boolean): AnnotationVisitor = annotation(member, "try-catch-annotation/$typeRef/$descriptor")
                    override fun visitLocalVariableAnnotation(typeRef: Int, typePath: TypePath?, start: Array<out Label>,
                        end: Array<out Label>, index: IntArray, descriptor: String, visible: Boolean): AnnotationVisitor =
                        annotation(member, "local-annotation/$typeRef/$descriptor")
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return sites
    }
}
