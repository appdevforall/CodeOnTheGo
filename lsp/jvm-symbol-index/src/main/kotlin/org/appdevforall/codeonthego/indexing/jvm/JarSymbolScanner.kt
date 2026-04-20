package org.appdevforall.codeonthego.indexing.jvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.pathString

/**
 * Scans JAR files using ASM and produces [JvmSymbol]s lazily.
 *
 * For Java class files, this gives complete information.
 * For Kotlin class files, use [KotlinMetadataScanner] or
 * [CombinedJarScanner] instead — ASM cannot see Kotlin-specific
 * semantics like extensions, suspend, or nullable types.
 */
object JarSymbolScanner {

    private val log = LoggerFactory.getLogger(JarSymbolScanner::class.java)

    fun scan(jarPath: Path, sourceId: String = jarPath.pathString): Flow<JvmSymbol> = flow {
		val jar = try {
			JarFile(jarPath.toFile())
		} catch (e: Exception) {
			log.warn("Failed to open JAR: {}", jarPath, e)
			return@flow
		}

		jar.use {
			val entries = jar.entries()
			while (entries.hasMoreElements()) {
				val entry = entries.nextElement()
				if (!entry.name.endsWith(".class")) continue
				if (entry.name == "module-info.class") continue
				if (entry.name == "package-info.class") continue

				try {
					jar.getInputStream(entry).use { input ->
						for (symbol in parseClassFile(input, sourceId)) {
							emit(symbol)
						}
					}
				} catch (e: Exception) {
					log.debug("Failed to parse {}: {}", entry.name, e.message)
				}
			}
		}
	}
		.flowOn(Dispatchers.IO)

    internal fun parseClassFile(input: InputStream, sourceId: String): List<JvmSymbol> {
        val reader = ClassReader(input)
        val collector = SymbolCollector(sourceId)
        reader.accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return collector.symbols
    }

    private class SymbolCollector(
        private val sourceId: String,
    ) : ClassVisitor(Opcodes.ASM9) {

        val symbols = mutableListOf<JvmSymbol>()

        private var className = ""
        private var classFqName = ""
        private var packageName = ""
        private var shortClassName = ""
        private var classAccess = 0
        private var isKotlinClass = false
        private var superName: String? = null
        private var interfaces: Array<out String>? = null
        private var isInnerClass = false
        private var classDeprecated = false

        override fun visit(
            version: Int, access: Int, name: String,
            signature: String?, superName: String?,
            interfaces: Array<out String>?,
        ) {
            className = name
            classFqName = name.replace('/', '.').replace('$', '.')
            classAccess = access
            this.superName = superName
            this.interfaces = interfaces
            classDeprecated = false

            val lastSlash = name.lastIndexOf('/')
            packageName = if (lastSlash >= 0) name.substring(0, lastSlash).replace('/', '.') else ""

            val afterPackage = if (lastSlash >= 0) name.substring(lastSlash + 1) else name
            shortClassName = afterPackage.replace('$', '.')

            isInnerClass = name.contains('$')
        }

        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
            if (descriptor == "Ljava/lang/Deprecated;") classDeprecated = true
            if (descriptor == "Lkotlin/Metadata;") isKotlinClass = true
            return null
        }

        override fun visitEnd() {
            if (!isPublicOrProtected(classAccess)) return

            val isAnonymous = isInnerClass &&
                    shortClassName.split('.').last().firstOrNull()?.isDigit() == true
            if (isAnonymous) return

            val kind = classKindFromAccess(classAccess)
            val language = if (isKotlinClass) JvmSourceLanguage.KOTLIN else JvmSourceLanguage.JAVA

            val supertypes = buildList {
                superName?.let {
                    if (it != "java/lang/Object") add(it)
                }
                interfaces?.forEach { add(it) }
            }

            val containingClass = if (isInnerClass) {
				className.substringBeforeLast('$')
            } else ""

            symbols.add(
                JvmSymbol(
                    key = className,
                    sourceId = sourceId,
                    name = classFqName,
                    shortName = shortClassName.split('.').last(),
                    packageName = packageName,
                    kind = kind,
                    language = language,
                    visibility = visibilityFromAccess(classAccess),
                    isDeprecated = classDeprecated,
                    data = JvmClassInfo(
						internalName = className,
                        containingClassName = containingClass,
                        supertypeNames = supertypes,
                        isAbstract = hasFlag(classAccess, Opcodes.ACC_ABSTRACT),
                        isFinal = hasFlag(classAccess, Opcodes.ACC_FINAL),
                        isInner = isInnerClass && !hasFlag(classAccess, Opcodes.ACC_STATIC),
                        isStatic = isInnerClass && hasFlag(classAccess, Opcodes.ACC_STATIC),
                    ),
                )
            )
        }

        override fun visitMethod(
            access: Int, name: String, descriptor: String,
            signature: String?, exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (!isPublicOrProtected(access)) return null
            if (!isPublicOrProtected(classAccess)) return null
            if (name.startsWith("access$")) return null
            if (hasFlag(access, Opcodes.ACC_BRIDGE)) return null
            if (hasFlag(access, Opcodes.ACC_SYNTHETIC)) return null
            if (name == "<clinit>") return null

            val methodType = Type.getMethodType(descriptor)
            val paramTypes = methodType.argumentTypes
            val returnType = methodType.returnType

            val isConstructor = name == "<init>"
            val methodName = if (isConstructor) shortClassName.split('.').last() else name
            val kind = if (isConstructor) JvmSymbolKind.CONSTRUCTOR else JvmSymbolKind.FUNCTION
            val language = if (isKotlinClass) JvmSourceLanguage.KOTLIN else JvmSourceLanguage.JAVA

            val parameters = paramTypes.map { type ->
                JvmParameterInfo(
                    name = "",  // not available without -parameters flag
                    typeName = typeToName(type),
                    typeDisplayName = typeToDisplayName(type),
                )
            }

            val fqName = "$className#$methodName"
            val key = "$fqName(${parameters.joinToString(",") { it.typeName }})"

            val signatureDisplay = buildString {
                append("(")
                append(parameters.joinToString(", ") { it.typeDisplayName })
                append(")")
                if (!isConstructor) {
                    append(": ")
                    append(typeToDisplayName(returnType))
                }
            }

            symbols.add(
                JvmSymbol(
                    key = key,
                    sourceId = sourceId,
                    name = fqName,
                    shortName = methodName,
                    packageName = packageName,
                    kind = kind,
                    language = language,
                    visibility = visibilityFromAccess(access),
                    isDeprecated = classDeprecated,
                    data = JvmFunctionInfo(
                        containingClassName = className,
                        returnTypeName = typeToName(returnType),
                        returnTypeDisplayName = typeToDisplayName(returnType),
                        parameterCount = paramTypes.size,
                        parameters = parameters,
                        signatureDisplay = signatureDisplay,
                        isStatic = hasFlag(access, Opcodes.ACC_STATIC),
                        isAbstract = hasFlag(access, Opcodes.ACC_ABSTRACT),
                        isFinal = hasFlag(access, Opcodes.ACC_FINAL),
                    ),
                )
            )

            return null
        }

        override fun visitField(
            access: Int, name: String, descriptor: String,
            signature: String?, value: Any?,
        ): FieldVisitor? {
            if (!isPublicOrProtected(access)) return null
            if (!isPublicOrProtected(classAccess)) return null
            if (hasFlag(access, Opcodes.ACC_SYNTHETIC)) return null

            val fieldType = Type.getType(descriptor)
            val kind = if (isKotlinClass) JvmSymbolKind.PROPERTY else JvmSymbolKind.FIELD
            val language = if (isKotlinClass) JvmSourceLanguage.KOTLIN else JvmSourceLanguage.JAVA
            val iName = "$className#$name"

            symbols.add(
                JvmSymbol(
                    key = iName,
                    sourceId = sourceId,
                    name = iName,
                    shortName = name,
                    packageName = packageName,
                    kind = kind,
                    language = language,
                    visibility = visibilityFromAccess(access),
                    isDeprecated = classDeprecated,
                    data = JvmFieldInfo(
                        containingClassName = className,
                        typeName = typeToName(fieldType),
                        typeDisplayName = typeToDisplayName(fieldType),
                        isStatic = hasFlag(access, Opcodes.ACC_STATIC),
                        isFinal = hasFlag(access, Opcodes.ACC_FINAL),
                        constantValue = value?.toString() ?: "",
                    ),
                )
            )

            return null
        }

        private fun isPublicOrProtected(access: Int) =
            hasFlag(access, Opcodes.ACC_PUBLIC) || hasFlag(access, Opcodes.ACC_PROTECTED)

        private fun hasFlag(access: Int, flag: Int) = (access and flag) != 0

        private fun classKindFromAccess(access: Int) = when {
            hasFlag(access, Opcodes.ACC_ANNOTATION) -> JvmSymbolKind.ANNOTATION_CLASS
            hasFlag(access, Opcodes.ACC_ENUM) -> JvmSymbolKind.ENUM
            hasFlag(access, Opcodes.ACC_INTERFACE) -> JvmSymbolKind.INTERFACE
            else -> JvmSymbolKind.CLASS
        }

        private fun visibilityFromAccess(access: Int) = when {
            hasFlag(access, Opcodes.ACC_PUBLIC) -> JvmVisibility.PUBLIC
            hasFlag(access, Opcodes.ACC_PROTECTED) -> JvmVisibility.PROTECTED
            hasFlag(access, Opcodes.ACC_PRIVATE) -> JvmVisibility.PRIVATE
            else -> JvmVisibility.PACKAGE_PRIVATE
        }

        private fun typeToName(type: Type): String = when (type.sort) {
            Type.VOID -> "V"
            Type.BOOLEAN -> "Z"
            Type.BYTE -> "B"
            Type.CHAR -> "C"
            Type.SHORT -> "S"
            Type.INT -> "I"
            Type.LONG -> "J"
            Type.FLOAT -> "F"
            Type.DOUBLE -> "D"
            Type.ARRAY -> "[".repeat(type.dimensions) + typeToName(type.elementType)
            Type.OBJECT -> type.internalName
            else -> type.internalName
        }

        private fun typeToDisplayName(type: Type): String = when (type.sort) {
			Type.BOOLEAN -> "boolean"
			Type.BYTE -> "byte"
			Type.CHAR -> "char"
			Type.SHORT -> "short"
			Type.INT -> "int"
			Type.LONG -> "long"
			Type.FLOAT -> "float"
			Type.DOUBLE -> "double"
            Type.VOID -> "void"
            Type.ARRAY -> typeToDisplayName(type.elementType) + "[]".repeat(type.dimensions)
            Type.OBJECT -> type.className.substringAfterLast('.')
            else -> typeToName(type)
        }
    }
}
