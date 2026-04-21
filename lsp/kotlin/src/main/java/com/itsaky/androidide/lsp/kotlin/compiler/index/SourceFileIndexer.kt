package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.toNioPathOrNull
import com.itsaky.androidide.projects.FileManager
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmFieldInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmFunctionInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmParameterInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.appdevforall.codeonthego.indexing.jvm.JvmTypeAliasInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmVisibility
import org.appdevforall.codeonthego.indexing.jvm.KotlinClassInfo
import org.appdevforall.codeonthego.indexing.jvm.KotlinFunctionInfo
import org.appdevforall.codeonthego.indexing.jvm.KotlinPropertyInfo
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadata
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.typeParameters
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.time.Instant
import kotlin.io.path.pathString

internal fun KtFile.toMetadata(project: Project, isIndexed: Boolean = false): KtFileMetadata =
	project.read {
		KtFileMetadata(
			filePath = (virtualFile.toNioPathOrNull() ?: backingFilePath)!!.pathString,
			packageFqName = packageFqName.asString(),
			lastModified = (backingFilePath?.let { FileManager.getLastModified(it) }) ?: Instant.ofEpochMilli(virtualFile.timeStamp),
			modificationStamp = modificationStamp,
			isIndexed = isIndexed,
			symbolKeys = emptyList()
		)
	}

internal suspend fun indexSourceFile(
	project: Project,
	ktFile: KtFile,
	fileIndex: KtFileMetadataIndex,
	symbolsIndex: JvmSymbolIndex,
) {
	val newFile = ktFile.toMetadata(project, isIndexed = true)
	val existingFile = fileIndex.get(newFile.filePath)
	if (KtFileMetadata.shouldBeSkipped(existingFile, newFile) && existingFile?.isIndexed == true) {
		return
	}

	// Remove stale symbols written during the previous indexing pass.
	if (existingFile?.isIndexed == true) {
		symbolsIndex.removeBySource(newFile.filePath)
	}

	val symbols = project.read {
		val list = mutableListOf<JvmSymbol>()
		ktFile.accept(object : KtTreeVisitorVoid() {
			override fun visitDeclaration(dcl: KtDeclaration) {
				val symbol = analyze(dcl) {
					analyzeDeclaration(newFile.filePath, dcl)
				}
				symbol?.let { list.add(it) }
				super.visitDeclaration(dcl)
			}
		})
		list
	}

	symbolsIndex.insertAll(symbols.asSequence())
	fileIndex.upsert(newFile.copy(symbolKeys = symbols.map { it.key }))
}

private fun KaSession.analyzeDeclaration(filePath: String, dcl: KtDeclaration): JvmSymbol? {
	dcl.name ?: return null
	return when (dcl) {
		is KtNamedFunction -> analyzeFunction(filePath, dcl)
		is KtClassOrObject -> analyzeClassOrObject(filePath, dcl)
		is KtParameter     -> analyzeParameter(filePath, dcl)
		is KtProperty      -> analyzeProperty(filePath, dcl)
		is KtTypeAlias     -> analyzeTypeAlias(filePath, dcl)
		else               -> null
	}
}

/**
 * Slash-package / dollar-nesting internal name for this class.
 * Returns null for anonymous/local classes that have no stable FQ name.
 */
private fun KtClassOrObject.internalName(): String? {
	val pkg = containingKtFile.packageFqName.asString()
	val fqName = fqName?.asString() ?: return null
	val relative = if (pkg.isEmpty()) fqName else fqName.removePrefix("$pkg.")
	return if (pkg.isEmpty()) relative.replace('.', '$')
	else "${pkg.replace('.', '/')}/${relative.replace('.', '$')}"
}

/**
 * Walk the PSI parent chain to find the internal name of the nearest
 * enclosing class or object.  Returns null for top-level declarations.
 */
private fun KtDeclaration.containingClassInternalName(): String? {
	var p = parent
	while (p != null) {
		if (p is KtClassOrObject) return p.internalName()
		p = p.parent
	}
	return null
}

private fun KtModifierListOwner.jvmVisibility(): JvmVisibility = when {
	hasModifier(KtTokens.PRIVATE_KEYWORD)   -> JvmVisibility.PRIVATE
	hasModifier(KtTokens.PROTECTED_KEYWORD) -> JvmVisibility.PROTECTED
	hasModifier(KtTokens.INTERNAL_KEYWORD)  -> JvmVisibility.INTERNAL
	else                                    -> JvmVisibility.PUBLIC
}

/**
 * Slash-package / dollar-nesting internal name for a resolved [KaType].
 * Mirrors [KotlinMetadataScanner]'s `kmTypeToName`.
 * Returns an empty string for unresolvable types (type parameters, errors).
 */
private fun KaSession.kaTypeInternalName(type: KaType): String {
	if (type !is KaClassType) return ""
	val classId = type.classId
	val pkg = classId.packageFqName.asString()
	val rel = classId.relativeClassName.asString()
	return if (pkg.isEmpty()) rel.replace('.', '$')
	else "${pkg.replace('.', '/')}/${rel.replace('.', '$')}"
}

/**
 * Short display name (last segment after '/' and '$'), with generic arguments
 * and a trailing '?' for nullable types.
 * Mirrors [KotlinMetadataScanner]'s `kmTypeToDisplayName`.
 */
private fun KaSession.kaTypeDisplayName(type: KaType): String {
	if (type !is KaClassType) return ""
	val base = kaTypeInternalName(type).substringAfterLast('/').substringAfterLast('$')
	val args = type.typeArguments.mapNotNull { it.type?.let { t -> kaTypeDisplayName(t) } }
	return buildString {
		append(base)
		if (args.isNotEmpty()) append("<${args.joinToString(", ")}>")
		if (type.isMarkedNullable) append("?")
	}
}

private fun KaSession.analyzeFunction(filePath: String, dcl: KtNamedFunction): JvmSymbol? {
	val fnName = dcl.name ?: return null
	val visibility = dcl.jvmVisibility()
	if (visibility == JvmVisibility.PRIVATE) return null

	val pkg = dcl.containingKtFile.packageFqName.asString()
	val containingClass = dcl.containingClassInternalName()

	val fnSymbol = dcl.symbol as? KaNamedFunctionSymbol ?: return null

	val parameters = fnSymbol.valueParameters.map { param ->
		JvmParameterInfo(
			name            = param.name.asString(),
			typeName        = kaTypeInternalName(param.returnType),
			typeDisplayName = kaTypeDisplayName(param.returnType),
			hasDefaultValue = param.hasDefaultValue,
			isVararg        = param.isVararg,
		)
	}

	val receiverType = fnSymbol.receiverParameter?.returnType
	val returnType   = fnSymbol.returnType

	// Mirrors KotlinMetadataScanner.extractFunction key / name conventions.
	val qualifiedName = if (containingClass != null) "$containingClass#$fnName"
	else "$pkg#$fnName"
	val key = "$qualifiedName(${parameters.joinToString(",") { it.typeFqName }})"

	val signatureDisplay = buildString {
		append("(")
		append(parameters.joinToString(", ") { "${it.name}: ${it.typeDisplayName}" })
		append("): ")
		append(kaTypeDisplayName(returnType))
	}

	return JvmSymbol(
		key         = key,
		sourceId    = filePath,
		name        = qualifiedName,
		shortName   = fnName,
		packageName = pkg,
		kind        = if (receiverType != null) JvmSymbolKind.EXTENSION_FUNCTION else JvmSymbolKind.FUNCTION,
		language    = JvmSourceLanguage.KOTLIN,
		visibility  = visibility,
		data = JvmFunctionInfo(
			containingClassName   = containingClass ?: "",
			returnTypeName        = kaTypeInternalName(returnType),
			returnTypeDisplayName = kaTypeDisplayName(returnType),
			parameterCount        = parameters.size,
			parameters            = parameters,
			signatureDisplay      = signatureDisplay,
			typeParameters        = fnSymbol.typeParameters.map { it.name.asString() },
			kotlin = KotlinFunctionInfo(
				receiverTypeName        = receiverType?.let { kaTypeInternalName(it) } ?: "",
				receiverTypeDisplayName = receiverType?.let { kaTypeDisplayName(it) } ?: "",
				isSuspend              = fnSymbol.isSuspend,
				isInline               = fnSymbol.isInline,
				isInfix                = fnSymbol.isInfix,
				isOperator             = fnSymbol.isOperator,
				isTailrec              = fnSymbol.isTailRec,
				isExternal             = fnSymbol.isExternal,
				isExpect               = fnSymbol.isExpect,
				isReturnTypeNullable   = returnType.isMarkedNullable,
			),
		),
	)
}

@OptIn(KaExperimentalApi::class)
private fun KaSession.analyzeClassOrObject(filePath: String, dcl: KtClassOrObject): JvmSymbol? {
	dcl.name ?: return null   // anonymous objects have no stable name
	val visibility = dcl.jvmVisibility()
	if (visibility == JvmVisibility.PRIVATE) return null

	val internalName = dcl.internalName() ?: return null
	val pkg          = dcl.containingKtFile.packageFqName.asString()
	val shortName    = internalName.substringAfterLast('/').substringAfterLast('$')
	val containingClass = dcl.containingClassInternalName()

	val clsSymbol = dcl.symbol as? KaClassSymbol ?: return null

	val kind = when (dcl) {
		is KtObjectDeclaration if dcl.isCompanion() -> JvmSymbolKind.COMPANION_OBJECT
		is KtObjectDeclaration -> JvmSymbolKind.OBJECT
		is KtClass if dcl.isInterface() -> JvmSymbolKind.INTERFACE
		is KtClass if dcl.isEnum() -> JvmSymbolKind.ENUM
		is KtClass if dcl.isAnnotation() -> JvmSymbolKind.ANNOTATION_CLASS
		is KtClass if dcl.isData() -> JvmSymbolKind.DATA_CLASS
		is KtClass if dcl.hasModifier(KtTokens.VALUE_KEYWORD) -> JvmSymbolKind.VALUE_CLASS
		is KtClass if dcl.hasModifier(KtTokens.SEALED_KEYWORD) -> JvmSymbolKind.SEALED_CLASS
		else -> JvmSymbolKind.CLASS
	}

	val supertypes = clsSymbol.superTypes.mapNotNull { st ->
		if (st !is KaClassType) return@mapNotNull null
		val sId  = st.classId
		val sPkg = sId.packageFqName.asString()
		val sRel = sId.relativeClassName.asString()
		val sInternal = if (sPkg.isEmpty()) sRel.replace('.', '$')
		else "${sPkg.replace('.', '/')}/${sRel.replace('.', '$')}"
		if (sInternal == "kotlin/Any") null else sInternal
	}

	return JvmSymbol(
		key         = internalName,
		sourceId    = filePath,
		name        = internalName,
		shortName   = shortName,
		packageName = pkg,
		kind        = kind,
		language    = JvmSourceLanguage.KOTLIN,
		visibility  = visibility,
		data = JvmClassInfo(
			internalName        = internalName,
			containingClassName = containingClass ?: "",
			supertypeNames      = supertypes,
			typeParameters      = clsSymbol.typeParameters.map { it.name.asString() },
			isAbstract          = dcl.hasModifier(KtTokens.ABSTRACT_KEYWORD),
			isFinal             = dcl.hasModifier(KtTokens.FINAL_KEYWORD),
			isInner             = dcl is KtClass && dcl.isInner(),
			isStatic            = containingClass != null && !(dcl is KtClass && dcl.isInner()),
			kotlin = KotlinClassInfo(
				isData       = dcl is KtClass && dcl.isData(),
				isValue      = dcl is KtClass && dcl.hasModifier(KtTokens.VALUE_KEYWORD),
				isSealed     = dcl is KtClass && dcl.hasModifier(KtTokens.SEALED_KEYWORD),
				isFunInterface = dcl is KtClass && dcl.hasModifier(KtTokens.FUN_KEYWORD),
				isExpect     = dcl.hasModifier(KtTokens.EXPECT_KEYWORD),
				isActual     = dcl.hasModifier(KtTokens.ACTUAL_KEYWORD),
				isExternal   = dcl.hasModifier(KtTokens.EXTERNAL_KEYWORD),
			),
		),
	)
}

private fun KaSession.analyzeProperty(filePath: String, dcl: KtProperty): JvmSymbol? {
	val propName   = dcl.name ?: return null
	val visibility = dcl.jvmVisibility()
	if (visibility == JvmVisibility.PRIVATE) return null

	val pkg            = dcl.containingKtFile.packageFqName.asString()
	val containingClass = dcl.containingClassInternalName()

	val propSymbol   = dcl.symbol as? KaPropertySymbol ?: return null
	val returnType   = propSymbol.returnType
	val receiverType = propSymbol.receiverParameter?.returnType

	val qualifiedName = if (containingClass != null) "$containingClass#$propName"
	else "$pkg#$propName"

	return JvmSymbol(
		key         = qualifiedName,
		sourceId    = filePath,
		name        = qualifiedName,
		shortName   = propName,
		packageName = pkg,
		kind        = if (receiverType != null) JvmSymbolKind.EXTENSION_PROPERTY else JvmSymbolKind.PROPERTY,
		language    = JvmSourceLanguage.KOTLIN,
		visibility  = visibility,
		data = JvmFieldInfo(
			containingClassName = containingClass ?: "",
			typeName            = kaTypeInternalName(returnType),
			typeDisplayName     = kaTypeDisplayName(returnType),
			kotlin = KotlinPropertyInfo(
				receiverTypeName        = receiverType?.let { kaTypeInternalName(it) } ?: "",
				receiverTypeDisplayName = receiverType?.let { kaTypeDisplayName(it) } ?: "",
				isConst      = dcl.hasModifier(KtTokens.CONST_KEYWORD),
				isLateinit   = dcl.hasModifier(KtTokens.LATEINIT_KEYWORD),
				hasGetter    = dcl.getter != null,
				hasSetter    = dcl.setter != null,
				isDelegated  = dcl.delegateExpression != null,
				isTypeNullable = returnType.isMarkedNullable,
				isExpect     = dcl.hasModifier(KtTokens.EXPECT_KEYWORD),
				isActual     = dcl.hasModifier(KtTokens.ACTUAL_KEYWORD),
				isExternal   = dcl.hasModifier(KtTokens.EXTERNAL_KEYWORD),
			),
		),
	)
}

/**
 * Constructor `val`/`var` parameters are indexed as properties so that
 * they appear in completion and navigation just like explicitly declared
 * properties.  Plain constructor or function parameters are skipped.
 */
private fun KaSession.analyzeParameter(filePath: String, dcl: KtParameter): JvmSymbol? {
	if (!dcl.hasValOrVar()) return null

	val propName   = dcl.name ?: return null
	val visibility = dcl.jvmVisibility()
	if (visibility == JvmVisibility.PRIVATE) return null

	val pkg            = dcl.containingKtFile.packageFqName.asString()
	val containingClass = dcl.containingClassInternalName()

	val paramSymbol = dcl.symbol as? KaValueParameterSymbol ?: return null
	val returnType  = paramSymbol.returnType

	val qualifiedName = if (containingClass != null) "$containingClass#$propName"
	else "$pkg#$propName"

	return JvmSymbol(
		key         = qualifiedName,
		sourceId    = filePath,
		name        = qualifiedName,
		shortName   = propName,
		packageName = pkg,
		kind        = JvmSymbolKind.PROPERTY,
		language    = JvmSourceLanguage.KOTLIN,
		visibility  = visibility,
		data = JvmFieldInfo(
			containingClassName = containingClass ?: "",
			typeName            = kaTypeInternalName(returnType),
			typeDisplayName     = kaTypeDisplayName(returnType),
			kotlin = KotlinPropertyInfo(
				isTypeNullable = returnType.isMarkedNullable,
			),
		),
	)
}

private fun KaSession.analyzeTypeAlias(filePath: String, dcl: KtTypeAlias): JvmSymbol? {
	val aliasName  = dcl.name ?: return null
	val visibility = dcl.jvmVisibility()
	if (visibility == JvmVisibility.PRIVATE) return null

	val pkg = dcl.containingKtFile.packageFqName.asString()

	val aliasSymbol  = dcl.symbol
	val expandedType = aliasSymbol.expandedType

	// Key convention mirrors KotlinMetadataScanner: dot-notation FQ name.
	val fqName = if (pkg.isEmpty()) aliasName else "$pkg.$aliasName"

	return JvmSymbol(
		key         = fqName,
		sourceId    = filePath,
		name        = fqName,
		shortName   = aliasName,
		packageName = pkg,
		kind        = JvmSymbolKind.TYPE_ALIAS,
		language    = JvmSourceLanguage.KOTLIN,
		visibility  = visibility,
		data = JvmTypeAliasInfo(
			expandedTypeName        = kaTypeInternalName(expandedType),
			expandedTypeDisplayName = kaTypeDisplayName(expandedType),
			typeParameters          = aliasSymbol.typeParameters.map { it.name.asString() },
		),
	)
}
