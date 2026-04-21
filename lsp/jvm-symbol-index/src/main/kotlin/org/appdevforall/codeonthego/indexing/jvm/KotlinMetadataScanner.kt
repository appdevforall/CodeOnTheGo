package org.appdevforall.codeonthego.indexing.jvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.pathString
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isConst
import kotlin.metadata.isDelegated
import kotlin.metadata.isExpect
import kotlin.metadata.isExternal
import kotlin.metadata.isInfix
import kotlin.metadata.isInline
import kotlin.metadata.isLateinit
import kotlin.metadata.isNullable
import kotlin.metadata.isOperator
import kotlin.metadata.isSuspend
import kotlin.metadata.isTailrec
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility

/**
 * Scans JAR files using Kotlin metadata to produce [JvmSymbol]s
 * with full Kotlin semantics (extensions, suspend, inline, etc.).
 *
 * Skips non-Kotlin class files (no `@Metadata` annotation).
 */
object KotlinMetadataScanner {

	private val log = LoggerFactory.getLogger(KotlinMetadataScanner::class.java)

	@OptIn(KaImplementationDetail::class)
	fun scan(rootVf: VirtualFile, sourceId: String = rootVf.path): Flow<JvmSymbol> = flow {
		val allFiles = LibraryUtils.getAllVirtualFilesFromRoot(rootVf, includeRoot = true)
		for (vf in allFiles) {
			if (!vf.name.endsWith(".class")) continue
			if (vf.name == "module-info.class") continue
			try {
				vf.contentsToByteArray().inputStream().use { input ->
					parseKotlinClass(input, sourceId)?.forEach { emit(it) }
				}
			} catch (e: Exception) {
				log.debug("Failed to parse {}: {}", vf.path, e.message)
			}
		}
	}
		.flowOn(Dispatchers.IO)

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

				try {
					jar.getInputStream(entry).use { input ->
						parseKotlinClass(input, sourceId)?.forEach { emit(it) }
					}
				} catch (e: Exception) {
					log.debug("Failed to parse {}: {}", entry.name, e.message)
				}
			}
		}
	}
		.flowOn(Dispatchers.IO)

	internal fun parseKotlinClass(input: InputStream, sourceId: String): List<JvmSymbol>? {
		val reader = ClassReader(input)
		val collector = MetadataCollector()
		reader.accept(
			collector,
			ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
		)

		val header = collector.metadataHeader ?: return null

		val metadata = try {
			KotlinClassMetadata.readStrict(header)
		} catch (e: Exception) {
			log.debug("Failed to read Kotlin metadata: {}", e.message)
			return null
		}

		return when (metadata) {
			is KotlinClassMetadata.Class ->
				extractFromClass(metadata.kmClass, sourceId)

			is KotlinClassMetadata.FileFacade ->
				extractFromPackage(metadata.kmPackage, collector.packageName, sourceId)

			is KotlinClassMetadata.MultiFileClassPart ->
				extractFromPackage(metadata.kmPackage, collector.packageName, sourceId)

			else -> null
		}
	}

	private fun extractFromClass(
		klass: KmClass,
		sourceId: String,
	): List<JvmSymbol> {
		val symbols = mutableListOf<JvmSymbol>()
		val className = klass.name
		val packageName = className.substringBeforeLast('/')
			.replace('/', '.')
		val shortName = className.substringAfterLast('/')
			.substringAfterLast('$')

		val kind = when (klass.kind) {
			ClassKind.INTERFACE -> JvmSymbolKind.INTERFACE
			ClassKind.ENUM_CLASS -> JvmSymbolKind.ENUM
			ClassKind.ANNOTATION_CLASS -> JvmSymbolKind.ANNOTATION_CLASS
			ClassKind.OBJECT -> JvmSymbolKind.OBJECT
			ClassKind.COMPANION_OBJECT -> JvmSymbolKind.COMPANION_OBJECT
			ClassKind.CLASS -> JvmSymbolKind.CLASS
			else -> JvmSymbolKind.CLASS
		}

		val supertypes = klass.supertypes.mapNotNull { supertype ->
			when (val c = supertype.classifier) {
				is KmClassifier.Class -> c.name
				else -> null
			}
		}

		symbols.add(
			JvmSymbol(
				key = className,
				sourceId = sourceId,
				name = className,
				shortName = shortName,
				packageName = packageName,
				kind = kind,
				language = JvmSourceLanguage.KOTLIN,
				visibility = kmVisibility(klass.visibility),
				data = JvmClassInfo(
					supertypeNames = supertypes,
					typeParameters = klass.typeParameters.map { it.name },
					isAbstract = klass.modality == Modality.ABSTRACT,
					isFinal = klass.modality == Modality.FINAL,
					kotlin = KotlinClassInfo(
						isSealed = klass.modality == Modality.SEALED,
						sealedSubclasses = klass.sealedSubclasses.map { it.replace('/', '.') },
					),
				),
			)
		)

		for (fn in klass.functions) {
			extractFunction(fn, className, packageName, sourceId)?.let { symbols.add(it) }
		}

		for (prop in klass.properties) {
			extractProperty(prop, className, packageName, sourceId)?.let { symbols.add(it) }
		}

		if (kind == JvmSymbolKind.ENUM) {
			klass.kmEnumEntries.forEachIndexed { ordinal, entry ->
				symbols.add(
					JvmSymbol(
						key = "$className#$entry",
						sourceId = sourceId,
						name = "$className#$entry",
						shortName = entry.name,
						packageName = packageName,
						kind = JvmSymbolKind.ENUM_ENTRY,
						language = JvmSourceLanguage.KOTLIN,
						data = JvmEnumEntryInfo(
							containingClassName = className,
							ordinal = ordinal,
						),
					)
				)
			}
		}

		return symbols
	}

	private fun extractFromPackage(
		pkg: KmPackage,
		packageName: String,
		sourceId: String,
	): List<JvmSymbol> {
		val symbols = mutableListOf<JvmSymbol>()

		for (fn in pkg.functions) {
			extractFunction(fn, "", packageName, sourceId)?.let { symbols.add(it) }
		}

		for (prop in pkg.properties) {
			extractProperty(prop, "", packageName, sourceId)?.let { symbols.add(it) }
		}

		for (alias in pkg.typeAliases) {
			val fqName = if (packageName.isEmpty()) alias.name else "$packageName.${alias.name}"
			symbols.add(
				JvmSymbol(
					key = fqName,
					sourceId = sourceId,
					name = fqName,
					shortName = alias.name,
					packageName = packageName,
					kind = JvmSymbolKind.TYPE_ALIAS,
					language = JvmSourceLanguage.KOTLIN,
					visibility = kmVisibility(alias.visibility),
					data = JvmTypeAliasInfo(
						expandedTypeName = kmTypeToName(alias.expandedType),
						expandedTypeDisplayName = kmTypeToDisplayName(alias.expandedType),
						typeParameters = alias.typeParameters.map { it.name },
					),
				)
			)
		}

		return symbols
	}

	private fun extractFunction(
		fn: KmFunction,
		containingClass: String,
		packageName: String,
		sourceId: String,
	): JvmSymbol? {
		val vis = kmVisibility(fn.visibility)
		if (vis == JvmVisibility.PRIVATE) return null

		val receiverType = fn.receiverParameterType
		val isExtension = receiverType != null
		val kind = if (isExtension) JvmSymbolKind.EXTENSION_FUNCTION else JvmSymbolKind.FUNCTION

		val parameters = fn.valueParameters.map { param ->
			JvmParameterInfo(
				name = param.name,
				typeName = kmTypeToName(param.type),
				typeDisplayName = kmTypeToDisplayName(param.type),
				hasDefaultValue = param.declaresDefaultValue,
				isVararg = param.varargElementType != null,
			)
		}

		val name = if (containingClass.isNotEmpty())
			"$containingClass#${fn.name}" else "$packageName#${fn.name}"
		val key = "$name(${parameters.joinToString(",") { it.typeFqName }})"

		val signatureDisplay = buildString {
			append("(")
			append(parameters.joinToString(", ") { "${it.name}: ${it.typeDisplayName}" })
			append("): ")
			append(kmTypeToDisplayName(fn.returnType))
		}

		return JvmSymbol(
			key = key,
			sourceId = sourceId,
			name = name,
			shortName = fn.name,
			packageName = packageName,
			kind = kind,
			language = JvmSourceLanguage.KOTLIN,
			visibility = vis,
			data = JvmFunctionInfo(
				containingClassName = containingClass,
				returnTypeName = kmTypeToName(fn.returnType),
				returnTypeDisplayName = kmTypeToDisplayName(fn.returnType),
				parameterCount = parameters.size,
				parameters = parameters,
				signatureDisplay = signatureDisplay,
				typeParameters = fn.typeParameters.map { it.name },
				kotlin = KotlinFunctionInfo(
					receiverTypeName = receiverType?.let { kmTypeToName(it) } ?: "",
					receiverTypeDisplayName = receiverType?.let { kmTypeToDisplayName(it) } ?: "",
					isSuspend = fn.isSuspend,
					isInline = fn.isInline,
					isInfix = fn.isInfix,
					isOperator = fn.isOperator,
					isTailrec = fn.isTailrec,
					isExternal = fn.isExternal,
					isExpect = fn.isExpect,
					isReturnTypeNullable = fn.returnType.isNullable,
				),
			),
		)
	}

	private fun extractProperty(
		prop: KmProperty,
		containingClass: String,
		packageName: String,
		sourceId: String,
	): JvmSymbol? {
		val vis = kmVisibility(prop.visibility)
		if (vis == JvmVisibility.PRIVATE) return null

		val receiverType = prop.receiverParameterType
		val isExtension = receiverType != null
		val kind = if (isExtension) JvmSymbolKind.EXTENSION_PROPERTY else JvmSymbolKind.PROPERTY

		val name = if (containingClass.isNotEmpty())
			"$containingClass#${prop.name}" else "$packageName#${prop.name}"

		return JvmSymbol(
			key = name,
			sourceId = sourceId,
			name = name,
			shortName = prop.name,
			packageName = packageName,
			kind = kind,
			language = JvmSourceLanguage.KOTLIN,
			visibility = vis,
			data = JvmFieldInfo(
				containingClassName = containingClass,
				typeName = kmTypeToName(prop.returnType),
				typeDisplayName = kmTypeToDisplayName(prop.returnType),
				kotlin = KotlinPropertyInfo(
					receiverTypeName = receiverType?.let { kmTypeToName(it) } ?: "",
					receiverTypeDisplayName = receiverType?.let { kmTypeToDisplayName(it) } ?: "",
					isConst = prop.isConst,
					isLateinit = prop.isLateinit,
					hasGetter = prop.getter != null,
					hasSetter = prop.setter != null,
					isDelegated = prop.isDelegated,
					isTypeNullable = prop.returnType.isNullable,
				),
			),
		)
	}

	private fun kmTypeToName(type: KmType): String = when (val c = type.classifier) {
		is KmClassifier.Class -> c.name
		is KmClassifier.TypeAlias -> c.name
		is KmClassifier.TypeParameter -> "T${c.id}"
	}

	private fun kmTypeToDisplayName(type: KmType): String {
		val base = kmTypeToName(type).substringAfterLast('/')
			.substringAfterLast('$')
		val args = type.arguments.mapNotNull { it.type?.let { t -> kmTypeToDisplayName(t) } }
		return buildString {
			append(base)
			if (args.isNotEmpty()) append("<${args.joinToString(", ")}>")
			if (type.isNullable) append("?")
		}
	}

	private fun kmVisibility(vis: Visibility) = when (vis) {
		Visibility.PUBLIC -> JvmVisibility.PUBLIC
		Visibility.PROTECTED -> JvmVisibility.PROTECTED
		Visibility.INTERNAL -> JvmVisibility.INTERNAL
		Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS, Visibility.LOCAL -> JvmVisibility.PRIVATE
	}

	private class MetadataCollector : ClassVisitor(Opcodes.ASM9) {
		var metadataHeader: Metadata? = null
		var packageName = ""

		private var metadataKind: Int? = null
		private var metadataVersion: IntArray? = null
		private var data1: Array<String>? = null
		private var data2: Array<String>? = null
		private var extraString: String? = null
		private var pn: String? = null
		private var extraInt: Int? = null

		override fun visit(
			version: Int, access: Int, name: String,
			signature: String?, superName: String?, interfaces: Array<out String>?,
		) {
			val lastSlash = name.lastIndexOf('/')
			packageName = if (lastSlash >= 0) name.substring(0, lastSlash).replace('/', '.') else ""
		}

		override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
			if (descriptor != "Lkotlin/Metadata;") return null

			return object : AnnotationVisitor(Opcodes.ASM9) {
				override fun visit(name: String?, value: Any?) {
					when (name) {
						"mv" -> {
							if (value is IntArray) {
								metadataVersion = value.copyOf()
							}
						}

						"k" -> metadataKind = value as? Int
						"xi" -> extraInt = value as? Int
						"xs" -> extraString = value as? String
						"pn" -> pn = value as? String
					}
				}

				override fun visitArray(name: String?): AnnotationVisitor =
					object : AnnotationVisitor(Opcodes.ASM9) {
						private val values = mutableListOf<Any>()
						override fun visit(n: String?, value: Any?) {
							value?.let { values.add(it) }
						}

						override fun visitEnd() {
							when (name) {
								"mv" -> metadataVersion =
									values.filterIsInstance<Int>().toIntArray()

								"d1" -> data1 = values.filterIsInstance<String>().toTypedArray()
								"d2" -> data2 = values.filterIsInstance<String>().toTypedArray()
							}
						}
					}

				override fun visitEnd() {
					val kind = metadataKind ?: return
					metadataHeader = Metadata(
						kind = kind,
						metadataVersion = metadataVersion ?: intArrayOf(),
						data1 = data1 ?: emptyArray(),
						data2 = data2 ?: emptyArray(),
						extraString = extraString ?: "",
						packageName = pn ?: "",
						extraInt = extraInt ?: 0,
					)
				}
			}
		}
	}
}
