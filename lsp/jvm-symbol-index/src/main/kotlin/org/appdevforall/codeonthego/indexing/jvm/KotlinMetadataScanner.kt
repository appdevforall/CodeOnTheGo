package org.appdevforall.codeonthego.indexing.jvm

import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory
import java.io.InputStream
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
 * Parses a Kotlin class file's `@Metadata` into [JvmSymbol]s with full Kotlin semantics
 * (extensions, suspend, inline, etc.).
 *
 * Returns nothing for a class file without `@Metadata`.
 */
object KotlinMetadataScanner {
	private val log = LoggerFactory.getLogger(KotlinMetadataScanner::class.java)

	internal fun parseKotlinClass(
		input: InputStream,
		sourceId: String,
	): List<JvmSymbol>? {
		val reader = ClassReader(input)
		val collector = MetadataCollector()
		reader.accept(
			collector,
			ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
		)

		val header = collector.metadataHeader ?: return null

		val metadata =
			try {
				KotlinClassMetadata.readStrict(header)
			} catch (e: Exception) {
				log.debug("Failed to read Kotlin metadata: {}", e.message)
				return null
			}

		return when (metadata) {
			is KotlinClassMetadata.Class -> {
				extractFromClass(metadata.kmClass, sourceId)
			}

			is KotlinClassMetadata.FileFacade -> {
				facadeSymbol(collector, sourceId) +
					extractFromPackage(metadata.kmPackage, collector.packageName, sourceId)
			}

			/*
			 * A part class is a compiler-generated fragment of a facade ("FooKt__Part"). Its
			 * declarations belong to the facade, so they are extracted, but neither language ever names
			 * the part class itself, so it is deliberately left out.
			 */
			is KotlinClassMetadata.MultiFileClassPart -> {
				extractFromPackage(metadata.kmPackage, collector.packageName, sourceId)
			}

			/*
			 * The facade of a @JvmMultifileClass. It declares nothing of its own -- the parts hold the
			 * declarations -- but Java calls the top-level functions through it, so the class still has
			 * to be findable.
			 */
			is KotlinClassMetadata.MultiFileClassFacade -> {
				facadeSymbol(collector, sourceId)
			}

			else -> {
				null
			}
		}
	}

	/**
	 * The symbol for a facade class itself, as opposed to the declarations it carries.
	 *
	 * Metadata for a facade describes a package rather than a class, so without this the class is
	 * absent from the index entirely and a Java file importing `FooKt` has nothing to resolve
	 * against.
	 */
	private fun facadeSymbol(
		collector: MetadataCollector,
		sourceId: String,
	): List<JvmSymbol> {
		val internalName = collector.internalName.ifEmpty { return emptyList() }
		return listOf(
			JvmSymbol(
				key = internalName,
				sourceId = sourceId,
				name = internalName,
				shortName = internalName.substringAfterLast('/'),
				packageName = collector.packageName,
				kind = JvmSymbolKind.FILE_FACADE,
				language = JvmSourceLanguage.KOTLIN,
				data = JvmClassInfo(internalName = internalName),
			),
		)
	}

	private fun extractFromClass(
		klass: KmClass,
		sourceId: String,
	): List<JvmSymbol> {
		val symbols = mutableListOf<JvmSymbol>()
		val className = klass.name
		val packageName =
			className
				.substringBeforeLast('/')
				.replace('/', '.')
		/*
		 * A leading '.' marks a local or anonymous class. The classpath trie never held these -- it
		 * treated any name containing '$' as not top level -- and neither language names them, so
		 * offering one as a completion would be noise. JarSymbolScanner drops its equivalents too.
		 */
		if (className.startsWith('.')) {
			return symbols
		}

		/*
		 * Kotlin metadata nests with '.' in source order and with '$' once compiled, so a nested class
		 * has to be split on either. Splitting on '$' alone left the short name as "Outer.Inner", which
		 * no prefix search for "Inner" could match; splitting on '.' alone left a compiled-nested class
		 * looking top level.
		 */
		val simpleNames = className.substringAfterLast('/')
		val lastSeparator = maxOf(simpleNames.lastIndexOf('.'), simpleNames.lastIndexOf('$'))
		val shortName = if (lastSeparator >= 0) simpleNames.substring(lastSeparator + 1) else simpleNames
		val containingClassName =
			if (lastSeparator >= 0) {
				className.substring(0, className.length - (simpleNames.length - lastSeparator))
			} else {
				""
			}

		val kind =
			when (klass.kind) {
				ClassKind.INTERFACE -> JvmSymbolKind.INTERFACE
				ClassKind.ENUM_CLASS -> JvmSymbolKind.ENUM
				ClassKind.ANNOTATION_CLASS -> JvmSymbolKind.ANNOTATION_CLASS
				ClassKind.OBJECT -> JvmSymbolKind.OBJECT
				ClassKind.COMPANION_OBJECT -> JvmSymbolKind.COMPANION_OBJECT
				ClassKind.CLASS -> JvmSymbolKind.CLASS
				else -> JvmSymbolKind.CLASS
			}

		val supertypes =
			klass.supertypes.mapNotNull { supertype ->
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
				data =
					JvmClassInfo(
						internalName = className,
						containingClassName = containingClassName,
						supertypeNames = supertypes,
						typeParameters = klass.typeParameters.map { it.name },
						isAbstract = klass.modality == Modality.ABSTRACT,
						isFinal = klass.modality == Modality.FINAL,
						kotlin =
							KotlinClassInfo(
								isSealed = klass.modality == Modality.SEALED,
								sealedSubclasses = klass.sealedSubclasses.map { it.replace('/', '.') },
							),
					),
			),
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
						data =
							JvmEnumEntryInfo(
								containingClassName = className,
								ordinal = ordinal,
							),
					),
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
					data =
						JvmTypeAliasInfo(
							expandedTypeName = kmTypeToName(alias.expandedType),
							expandedTypeDisplayName = kmTypeToDisplayName(alias.expandedType),
							typeParameters = alias.typeParameters.map { it.name },
						),
				),
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
		val receiverTypeDisplayName = receiverType?.let { kmTypeToDisplayName(it) } ?: ""
		val kind = if (isExtension) JvmSymbolKind.EXTENSION_FUNCTION else JvmSymbolKind.FUNCTION

		val parameters =
			fn.valueParameters.map { param ->
				JvmParameterInfo(
					name = param.name,
					typeName = kmTypeToName(param.type),
					typeDisplayName = kmTypeToDisplayName(param.type),
					hasDefaultValue = param.declaresDefaultValue,
					isVararg = param.varargElementType != null,
				)
			}

		val name =
			if (containingClass.isNotEmpty()) {
				"$containingClass#${fn.name}"
			} else {
				"$packageName#${fn.name}"
			}
		val key = "$name(${parameters.joinToString(",") { it.typeFqName }})"

		val signatureDisplay =
			buildString {
				if (isExtension) {
					append(receiverTypeDisplayName)
					append('.')
				}

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
			data =
				JvmFunctionInfo(
					containingClassName = containingClass,
					returnTypeName = kmTypeToName(fn.returnType),
					returnTypeDisplayName = kmTypeToDisplayName(fn.returnType),
					parameterCount = parameters.size,
					parameters = parameters,
					signatureDisplay = signatureDisplay,
					typeParameters = fn.typeParameters.map { it.name },
					kotlin =
						KotlinFunctionInfo(
							receiverTypeName = receiverType?.let { kmTypeToName(it) } ?: "",
							receiverTypeDisplayName = receiverTypeDisplayName,
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

		val name =
			if (containingClass.isNotEmpty()) {
				"$containingClass#${prop.name}"
			} else {
				"$packageName#${prop.name}"
			}

		return JvmSymbol(
			key = name,
			sourceId = sourceId,
			name = name,
			shortName = prop.name,
			packageName = packageName,
			kind = kind,
			language = JvmSourceLanguage.KOTLIN,
			visibility = vis,
			data =
				JvmFieldInfo(
					containingClassName = containingClass,
					typeName = kmTypeToName(prop.returnType),
					typeDisplayName = kmTypeToDisplayName(prop.returnType),
					kotlin =
						KotlinPropertyInfo(
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

	private fun kmTypeToName(type: KmType): String =
		when (val c = type.classifier) {
			is KmClassifier.Class -> c.name
			is KmClassifier.TypeAlias -> c.name
			is KmClassifier.TypeParameter -> "T${c.id}"
		}

	private fun kmTypeToDisplayName(type: KmType): String {
		val base =
			kmTypeToName(type)
				.substringAfterLast('/')
				.substringAfterLast('$')
		val args = type.arguments.mapNotNull { it.type?.let { t -> kmTypeToDisplayName(t) } }
		return buildString {
			append(base)
			if (args.isNotEmpty()) append("<${args.joinToString(", ")}>")
			if (type.isNullable) append("?")
		}
	}

	private fun kmVisibility(vis: Visibility) =
		when (vis) {
			Visibility.PUBLIC -> JvmVisibility.PUBLIC
			Visibility.PROTECTED -> JvmVisibility.PROTECTED
			Visibility.INTERNAL -> JvmVisibility.INTERNAL
			Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS, Visibility.LOCAL -> JvmVisibility.PRIVATE
		}

	private class MetadataCollector : ClassVisitor(Opcodes.ASM9) {
		var metadataHeader: Metadata? = null
		var packageName = ""

		/** The visited class's internal name, e.g. `com/example/FooKt`. */
		var internalName = ""

		private var metadataKind: Int? = null
		private var metadataVersion: IntArray? = null
		private var data1: Array<String>? = null
		private var data2: Array<String>? = null
		private var extraString: String? = null
		private var pn: String? = null
		private var extraInt: Int? = null

		override fun visit(
			version: Int,
			access: Int,
			name: String,
			signature: String?,
			superName: String?,
			interfaces: Array<out String>?,
		) {
			internalName = name
			val lastSlash = name.lastIndexOf('/')
			packageName = if (lastSlash >= 0) name.substring(0, lastSlash).replace('/', '.') else ""
		}

		override fun visitAnnotation(
			descriptor: String?,
			visible: Boolean,
		): AnnotationVisitor? {
			if (descriptor != "Lkotlin/Metadata;") return null

			return object : AnnotationVisitor(Opcodes.ASM9) {
				override fun visit(
					name: String?,
					value: Any?,
				) {
					when (name) {
						"mv" -> {
							if (value is IntArray) {
								metadataVersion = value.copyOf()
							}
						}

						"k" -> {
							metadataKind = value as? Int
						}

						"xi" -> {
							extraInt = value as? Int
						}

						"xs" -> {
							extraString = value as? String
						}

						"pn" -> {
							pn = value as? String
						}
					}
				}

				override fun visitArray(name: String?): AnnotationVisitor =
					object : AnnotationVisitor(Opcodes.ASM9) {
						private val values = mutableListOf<Any>()

						override fun visit(
							n: String?,
							value: Any?,
						) {
							value?.let { values.add(it) }
						}

						override fun visitEnd() {
							when (name) {
								"mv" -> {
									metadataVersion =
										values.filterIsInstance<Int>().toIntArray()
								}

								"d1" -> {
									data1 = values.filterIsInstance<String>().toTypedArray()
								}

								"d2" -> {
									data2 = values.filterIsInstance<String>().toTypedArray()
								}
							}
						}
					}

				override fun visitEnd() {
					val kind = metadataKind ?: return
					metadataHeader =
						Metadata(
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
