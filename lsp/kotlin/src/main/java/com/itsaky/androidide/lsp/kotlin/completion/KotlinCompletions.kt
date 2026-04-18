package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.api.describeSnippet
import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.AnalysisContext
import com.itsaky.androidide.lsp.kotlin.utils.ContextKeywords
import com.itsaky.androidide.lsp.kotlin.utils.ModifierFilter
import com.itsaky.androidide.lsp.kotlin.utils.containingTopLevelClassDeclaration
import com.itsaky.androidide.lsp.kotlin.utils.resolveAnalysisContext
import com.itsaky.androidide.lsp.models.ClassCompletionData
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.InsertTextFormat
import com.itsaky.androidide.preferences.utils.indentationString
import com.itsaky.androidide.project.ProjectInfo
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.CancellationException
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmFunctionInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.appdevforall.codeonthego.indexing.jvm.JvmTypeAliasInfo
import org.jetbrains.kotlin.analysis.api.KaContextParameterApi
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.renderer.types.KaTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassifierSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.types.Variance
import org.slf4j.LoggerFactory
import kotlin.io.path.name

private const val KT_COMPLETION_PLACEHOLDER = "KT_COMPLETION_PLACEHOLDER"

private val logger = LoggerFactory.getLogger("KotlinCompletions")

/**
 * Provide code completion for the given completion parameters.
 *
 * @param CompilationEnvironment The compilation environment to use for the code completion.
 * @param params The completion parameters.
 * @return The completion result.
 */
internal fun CompilationEnvironment.complete(params: CompletionParams): CompletionResult {
	val ktFile = ktSymbolIndex.getOpenedKtFile(params.file)
	if (ktFile == null) {
		logger.warn("File {} is not open", params.file)
		return CompletionResult.EMPTY
	}

	// Need to use the original document contents here, instead of
	// managedFile.inMemoryKtFile.text
	val originalText = FileManager.getDocumentContents(params.file)
	val requestPosition = params.position
	val completionOffset = requestPosition.requireIndex()
	val prefix = params.requirePrefix()
	val partial = partialIdentifier(prefix)

	// insert placeholder to fix broken trees
	val textWithPlaceholder = buildString {
		append(originalText, 0, completionOffset)
		append(KT_COMPLETION_PLACEHOLDER)
		append(originalText, completionOffset, originalText.length)
	}

	val completionKtFile = project.read {
		parser.createFile(
			fileName = params.file.name,
			text = textWithPlaceholder
		).apply {
			originalFile = ktFile
			originalKtFile = ktFile
		}
	}

	return try {
		project.read {
			analyzeCopy(
				useSiteElement = completionKtFile,
				resolutionMode = KaDanglingFileResolutionMode.PREFER_SELF,
			) {
				val ctx =
					resolveAnalysisContext(
						env = this@complete,
						file = params.file,
						ktFile = completionKtFile,
						offset = completionOffset,
						partial = partial
					)

				if (ctx == null) {
					logger.error(
						"Unable to determine context at offset {} in file {}",
						completionOffset,
						params.file
					)
					return@analyzeCopy CompletionResult.EMPTY
				}

				context(ctx) {
					val items = mutableListOf<CompletionItem>()
					val completionContext = determineCompletionContext(ctx.psiElement)
					when (completionContext) {
						CompletionContext.Scope ->
							collectScopeCompletions(to = items)

						CompletionContext.Member ->
							collectMemberCompletions(to = items)
					}

					collectKeywordCompletions(to = items)
					collectSnippetCompletions(to = items)
					CompletionResult(items)
				}
			}
		}
	} catch (e: Throwable) {
		if (e is CancellationException) {
			throw e
		}

		logger.warn("An error occurred while computing completions for {}", params.file, e)
		return CompletionResult.EMPTY
	}
}

context(ctx: AnalysisContext)
private fun KaSession.collectMemberCompletions(
	to: MutableList<CompletionItem>
) {
	val qualifiedExpr = ctx.psiElement.getParentOfType<KtQualifiedExpression>(strict = false)
	if (qualifiedExpr == null) {
		logger.error("No qualified expression found requested position")
		return
	}

	val receiver = qualifiedExpr.receiverExpression
	val receiverType = receiver.expressionType

	if (receiverType == null) {
		logger.error("Unable to find receiver expression type")
		return
	}

	logger.info(
		"Complete members of {}: {} [{}] matching '{}'",
		receiver,
		receiverType,
		receiver.text,
		ctx.partial
	)

	collectMembersFromType(receiverType, to)

	if (qualifiedExpr is KtSafeQualifiedExpression) {
		val nonNullType = receiverType.withNullability(isMarkedNullable = false)
		collectMembersFromType(nonNullType, to)
	}

	collectExtensionFunctions(receiverType, to)
}

context(ctx: AnalysisContext)
@OptIn(KaExperimentalApi::class)
private fun KaSession.collectMembersFromType(
	receiverType: KaType,
	to: MutableList<CompletionItem>
) {
	val typeScope = receiverType.scope
	if (typeScope != null) {
		val callables =
			typeScope.getCallableSignatures { name -> matchesPrefix(name) }
				.map { it.symbol }

		val classifiers =
			typeScope.getClassifierSymbols { name -> matchesPrefix(name) }

		to += toCompletionItems(callables)
		to += toCompletionItems(classifiers)
		return
	}

	// fallback approach when typeScope is not available
	val classType = receiverType as? KaClassType ?: return
	val classSymbol = classType.symbol as? KaClassSymbol ?: return
	val memberScope = classSymbol.memberScope

	val callables = memberScope.callables { name -> matchesPrefix(name) }
	val classifiers = memberScope.classifiers { name -> matchesPrefix(name) }

	to += toCompletionItems(callables)
	to += toCompletionItems(classifiers)
}

context(ctx: AnalysisContext)
private fun KaSession.collectExtensionFunctions(
	receiverType: KaType,
	to: MutableList<CompletionItem>
) {
	val extensionSymbols =
		ctx.scope.callables { name -> matchesPrefix(name) }
			.filter { symbol ->
				if (!symbol.isExtension) return@filter false

				val extReceiverType = symbol.receiverType ?: return@filter false
				receiverType.isSubtypeOf(extReceiverType)
			}

	to += toCompletionItems(extensionSymbols)
}

context(env: CompilationEnvironment, ctx: AnalysisContext)
private fun KaSession.collectScopeCompletions(
	to: MutableList<CompletionItem>,
) {
	val ktElement = ctx.ktElement
	val scope = ctx.scope
	val scopeContext = ctx.scopeContext

	logger.info(
		"Complete scope members of {}: [{}] matching '{}'",
		ktElement,
		ktElement.text,
		ctx.partial
	)

	val callables =
		scope.callables { name -> matchesPrefix(name) }
			.filter { symbol ->

				// always include non-extension functions
				if (!symbol.isExtension) return@filter true

				// include extension functions with matching implicit receivers
				val extReceiverType = symbol.receiverType ?: return@filter true
				scopeContext.implicitReceivers.any { receiver ->
					receiver.type.isSubtypeOf(extReceiverType)
				}
			}

	val classifiers = scope.classifiers { name -> matchesPrefix(name) }

	to += toCompletionItems(callables)
	to += toCompletionItems(classifiers)

	collectUnimportedSymbols(to)
}

context(env: CompilationEnvironment, ctx: AnalysisContext)
private fun KaSession.collectUnimportedSymbols(
	to: MutableList<CompletionItem>
) {
	val currentPackage = ctx.ktElement.containingKtFile.packageDirective?.fqName?.asString()
	val useSiteModule = this.useSiteModule

	// Library symbols: JAR-based, use full SymbolVisibilityChecker
	val visibilityChecker = env.symbolVisibilityChecker
	env.libraryIndex?.findByPrefix(ctx.partial, limit = 0)
		?.forEach { symbol ->
			val isVisible = visibilityChecker.isVisible(
				symbol = symbol,
				useSiteModule = useSiteModule,
				useSitePackage = currentPackage,
			)
			if (!isVisible) return@forEach
			buildUnimportedSymbolItem(symbol)?.let { to += it }
		}

	// Source symbols: project .kt files — skip private and same-package symbols
	env.sourceIndex?.findByPrefix(ctx.partial, limit = 0)
		?.forEach { symbol ->
			if (symbol.packageName == currentPackage) return@forEach

			val isVisible = visibilityChecker.isVisible(
				symbol = symbol,
				useSiteModule = useSiteModule,
				useSitePackage = currentPackage
			)

			if (!isVisible) return@forEach

			buildUnimportedSymbolItem(symbol)?.let { to += it }
		}

	// Generated symbols: R.jar etc. — all public by construction, no visibility check needed.
	env.generatedIndex?.findByPrefix(ctx.partial, limit = 0)
		?.forEach { symbol ->
			if (symbol.packageName == currentPackage) return@forEach
			buildUnimportedSymbolItem(symbol)?.let { to += it }
		}
}

context(ctx: AnalysisContext)
private fun KaSession.buildUnimportedSymbolItem(symbol: JvmSymbol): CompletionItem? {
	if (symbol.kind.isCallable && !symbol.isTopLevel && !symbol.isExtension) {
		// member-level, non-extension callable symbols should not be
		// completed in scope completions
		return null
	}

	if (symbol.isExtension) {
		val receiverTypeName = symbol.receiverTypeName
		if (receiverTypeName != null) {
			val receiverClassId = internalNameToClassId(receiverTypeName)
			val receiverType = findClass(receiverClassId)
			if (receiverType != null) {
				val satisfiesImplicitReceivers =
					ctx.scopeContext.implicitReceivers.any { receiver ->
						receiver.type.isSubtypeOf(receiverType)
					}
				// the extension property/function's receiver type
				// is not available in current context, so ignore this sym
				if (!satisfiesImplicitReceivers) return null
			} else return null
		}
	}

	val item = ktCompletionItem(
		name = symbol.shortName,
		kind = kindOf(symbol),
	)

	item.overrideTypeText = symbol.returnTypeDisplay
	when (symbol.kind) {
		JvmSymbolKind.EXTENSION_FUNCTION, JvmSymbolKind.FUNCTION, JvmSymbolKind.CONSTRUCTOR -> {
			val data = symbol.data as JvmFunctionInfo
			item.detail = data.signatureDisplay
			item.setInsertTextForFunction(
				name = symbol.shortName,
				hasParams = data.parameterCount > 0,
			)

			item.additionalEditHandler = KotlinAutoImportEditHandler(
				analysisContext = ctx,
				symbolToImport = symbol
			)

			if (symbol.kind == JvmSymbolKind.CONSTRUCTOR) {
				item.overrideTypeText = symbol.shortName
			}
		}

		in JvmSymbolKind.CALLABLE_KINDS -> {
			item.additionalEditHandler = KotlinAutoImportEditHandler(
				analysisContext = ctx,
				symbolToImport = symbol
			)
		}

		JvmSymbolKind.TYPE_ALIAS -> {
			item.detail = (symbol.data as JvmTypeAliasInfo).expandedTypeFqName
		}

		in JvmSymbolKind.CLASSIFIER_KINDS -> {
			val classInfo = symbol.data as JvmClassInfo
			item.detail = symbol.fqName
			item.setClassCompletionData(
				className = symbol.fqName,
				isNested = classInfo.isInner,
				topLevelClass = classInfo.containingClassFqName,
			)
		}

		else -> {}
	}

	return item
}

private fun internalNameToClassId(internalName: String): ClassId {
	val isLocal = false
	val packageName = internalName.substringBeforeLast('/')
	val relativeName = internalName.substringAfterLast('/')
	return ClassId(
		packageFqName = FqName.fromSegments(packageName.split('/')),
		relativeClassName = FqName.fromSegments(relativeName.split('$')),
		isLocal = isLocal
	)
}

context(ctx: AnalysisContext)
private fun KaSession.collectKeywordCompletions(
	to: MutableList<CompletionItem>,
) {
	fun kwItem(name: String) =
		ktCompletionItem(
			name = name,
			kind = CompletionItemKind.KEYWORD,
		)

	if (!ctx.isInsideModifierList) {
		ContextKeywords.keywordsFor(ctx.declarationContext).mapTo(to) { kw ->
			kwItem(kw.value)
		}
	}

	ModifierFilter.validModifiers(ctx).mapTo(to) { kw ->
		kwItem(kw.value)
	}
}

context(ctx: AnalysisContext)
private fun KaSession.collectSnippetCompletions(to: MutableList<CompletionItem>) {
	val snippets = buildList {
		// add global snippets, if any
		KotlinSnippetRepository.snippets[KotlinSnippetScope.GLOBAL]?.also { addAll(it) }

		val snippetScope = when (ctx.declarationKind) {
			DeclarationKind.CLASS,
			DeclarationKind.INTERFACE,
			DeclarationKind.OBJECT,
			DeclarationKind.ENUM_CLASS,
			DeclarationKind.ANNOTATION_CLASS -> KotlinSnippetScope.MEMBER

			DeclarationKind.CONSTRUCTOR,
			DeclarationKind.FUN -> KotlinSnippetScope.LOCAL

			DeclarationKind.UNKNOWN -> KotlinSnippetScope.TOP_LEVEL.takeIf { ctx.declarationContext == DeclarationContext.TOP_LEVEL }

			DeclarationKind.PROPERTY_VAL -> null
			DeclarationKind.PROPERTY_VAR -> null
			DeclarationKind.TYPEALIAS -> null
		}

		logger.info(
			"Adding completions for snippet scope: {} (context: {}, kind: {})",
			snippetScope,
			ctx.declarationContext,
			ctx.declarationKind
		)
		KotlinSnippetRepository.snippets[snippetScope]?.also { addAll(it) }
	}

	val indent = computeIndentLevelAt(ctx.ktElement)
	for (snippet in snippets) {
		to += ktCompletionItem(snippet.prefix, CompletionItemKind.SNIPPET).apply {
			detail = snippet.description
			ideSortText = "00000${snippet.prefix}"
			snippetDescription = describeSnippet(ctx.partial)

			val indentation = indentationString(indent)
			insertTextFormat = InsertTextFormat.SNIPPET
			insertText = snippet.body.joinToString(separator = System.lineSeparator()) {
				it.replace("\t", indentation)
					.replace("\n", "\n${indentation}")
			}
		}
	}
}

private fun computeIndentLevelAt(ktElement: KtElement): Int {
	var indentLevel = 0
	var current = ktElement.parent

	while (current != null) {
		if (current is KtBlockExpression ||
			current is KtClassBody ||
			current is KtWhenExpression ||
			current is KtFunction
		) {
			indentLevel++
		}
		current = current.parent
	}

	return indentLevel
}

context(ctx: AnalysisContext)
@JvmName("callablesToCompletionItems")
private fun KaSession.toCompletionItems(
	callables: Sequence<KaCallableSymbol>,
): Sequence<CompletionItem> =
	callables.mapNotNull {
		callableSymbolToCompletionItem(it)
	}

context(ctx: AnalysisContext)
@JvmName("classifiersToCompletionItems")
private fun KaSession.toCompletionItems(
	classifiers: Sequence<KaClassifierSymbol>,
): Sequence<CompletionItem> =
	classifiers.mapNotNull {
		classifierSymbolToCompletionItem(it)
	}

context(ctx: AnalysisContext)
@OptIn(KaExperimentalApi::class)
private fun KaSession.callableSymbolToCompletionItem(
	symbol: KaCallableSymbol,
): CompletionItem? {
	val item = createSymbolCompletionItem(symbol) ?: return null
	val name = item.ideLabel
	item.overrideTypeText = renderName(symbol.returnType)

	when (symbol) {
		is KaNamedFunctionSymbol -> {
			val params = symbol.valueParameters.joinToString(", ") { param ->
				"${param.name.asString()}: ${renderName(param.returnType)}"
			}

			val hasParams = symbol.valueParameters.isNotEmpty()

			item.detail = "${name}($params)"
			item.setInsertTextForFunction(name, hasParams)

			// TODO(itsaky): provide method completion data in order to show API info
			//               in completion items
		}

		// TODO: For properties, we can check if they're a compile-time constant
		//       and include that constant value in the "detail" field of the
		// 		 completion item

		else -> {}
	}

	return item
}

context(ctx: AnalysisContext)
private fun CompletionItem.setInsertTextForFunction(
	name: String,
	hasParams: Boolean,
) {
	insertTextFormat = InsertTextFormat.SNIPPET
	insertText = if (hasParams) {
		"${name}($0)"
	} else {
		"${name}()$0"
	}

	snippetDescription = describeSnippet(prefix = ctx.partial, allowCommandExecution = true)

	if (hasParams) {
		command = Command("Trigger parameter hints", Command.TRIGGER_PARAMETER_HINTS)
	}
}

context(ctx: AnalysisContext)
@OptIn(KaExperimentalApi::class, KaIdeApi::class)
private fun KaSession.classifierSymbolToCompletionItem(
	symbol: KaClassifierSymbol,
): CompletionItem? {
	val item = createSymbolCompletionItem(symbol) ?: return null
	item.detail = when (symbol) {
		is KaClassSymbol -> symbol.classId?.asFqNameString() ?: ""
		is KaTypeAliasSymbol -> renderName(
			symbol.expandedType,
			KaTypeRendererForSource.WITH_QUALIFIED_NAMES
		)

		is KaTypeParameterSymbol -> item.ideLabel
	}

	if (symbol is KaClassLikeSymbol) {
		val classFqn = symbol.classId?.asFqNameString()
		if (classFqn != null) {
			item.setClassCompletionData(
				className = classFqn,
				isNested = symbol.classId?.isNestedClass ?: false,
				topLevelClass = symbol.containingTopLevelClassDeclaration?.classId?.asFqNameString()
					?: ""
			)
		}
	}

	return item
}

context(ctx: AnalysisContext)
private fun CompletionItem.setClassCompletionData(
	className: String,
	isNested: Boolean = false,
	topLevelClass: String = "",
) {
	data = ClassCompletionData(
		className,
		isNested,
		topLevelClass
	)

	additionalEditHandler = KotlinAutoImportEditHandler(analysisContext = ctx)
}

context(ctx: AnalysisContext)
private fun KaSession.createSymbolCompletionItem(
	symbol: KaSymbol,
): CompletionItem? {
	return ktCompletionItem(
		name = symbol.name?.asString() ?: return null,
		kind = kindOf(symbol),
	)
}

context(ctx: AnalysisContext)
private fun KaSession.ktCompletionItem(
	name: String,
	kind: CompletionItemKind,
): CompletionItem {
	val item = KotlinCompletionItem()
	item.ideLabel = name
	item.completionKind = kind
	item.matchLevel = CompletionItem.matchLevel(item.ideLabel, ctx.partial)

	return item
}

private fun KaSession.kindOf(symbol: KaSymbol): CompletionItemKind {
	return when (symbol) {
		is KaClassSymbol -> when (symbol.classKind) {
			KaClassKind.CLASS -> CompletionItemKind.CLASS
			KaClassKind.ENUM_CLASS -> CompletionItemKind.ENUM
			KaClassKind.ANNOTATION_CLASS -> CompletionItemKind.ANNOTATION_TYPE
			KaClassKind.OBJECT -> CompletionItemKind.CLASS
			KaClassKind.COMPANION_OBJECT -> CompletionItemKind.CLASS
			KaClassKind.INTERFACE -> CompletionItemKind.INTERFACE
			KaClassKind.ANONYMOUS_OBJECT -> CompletionItemKind.CLASS
		}

		is KaTypeParameterSymbol -> CompletionItemKind.TYPE_PARAMETER
		is KaTypeAliasSymbol -> CompletionItemKind.CLASS
		is KaFunctionSymbol -> when (symbol) {
			is KaConstructorSymbol -> CompletionItemKind.CONSTRUCTOR
			else -> CompletionItemKind.METHOD
		}

		is KaPropertySymbol -> CompletionItemKind.PROPERTY
		is KaLocalVariableSymbol -> CompletionItemKind.VARIABLE
		is KaValueParameterSymbol -> CompletionItemKind.VARIABLE
		is KaEnumEntrySymbol -> CompletionItemKind.ENUM_MEMBER
		else -> CompletionItemKind.NONE
	}
}

private fun KaSession.kindOf(symbol: JvmSymbol): CompletionItemKind =
	when (symbol.kind) {
		JvmSymbolKind.CLASS -> CompletionItemKind.CLASS
		JvmSymbolKind.INTERFACE -> CompletionItemKind.INTERFACE
		JvmSymbolKind.ENUM -> CompletionItemKind.ENUM
		JvmSymbolKind.ENUM_ENTRY -> CompletionItemKind.ENUM_MEMBER
		JvmSymbolKind.ANNOTATION_CLASS -> CompletionItemKind.ANNOTATION_TYPE
		JvmSymbolKind.OBJECT -> CompletionItemKind.CLASS
		JvmSymbolKind.COMPANION_OBJECT -> CompletionItemKind.CLASS
		JvmSymbolKind.DATA_CLASS -> CompletionItemKind.CLASS
		JvmSymbolKind.VALUE_CLASS -> CompletionItemKind.CLASS
		JvmSymbolKind.SEALED_CLASS -> CompletionItemKind.CLASS
		JvmSymbolKind.SEALED_INTERFACE -> CompletionItemKind.INTERFACE
		JvmSymbolKind.FUNCTION -> CompletionItemKind.FUNCTION
		JvmSymbolKind.EXTENSION_FUNCTION -> CompletionItemKind.FUNCTION
		JvmSymbolKind.CONSTRUCTOR -> CompletionItemKind.CONSTRUCTOR
		JvmSymbolKind.PROPERTY -> CompletionItemKind.PROPERTY
		JvmSymbolKind.EXTENSION_PROPERTY -> CompletionItemKind.PROPERTY
		JvmSymbolKind.FIELD -> CompletionItemKind.FIELD
		JvmSymbolKind.TYPE_ALIAS -> CompletionItemKind.CLASS
	}

@OptIn(KaExperimentalApi::class, KaContextParameterApi::class)
private fun KaSession.renderName(
	type: KaType,
	renderer: KaTypeRenderer = KaTypeRendererForSource.WITH_SHORT_NAMES,
	position: Variance = Variance.INVARIANT
): String {
	return type.run {
		render(renderer, position)
	}
}

private fun partialIdentifier(prefix: String): String {
	return prefix.takeLastWhile { char -> Character.isJavaIdentifierPart(char) }
}

context(ctx: AnalysisContext)
private fun matchesPrefix(name: Name): Boolean {
	if (ctx.partial.isEmpty()) return true
	return name.asString().startsWith(ctx.partial, ignoreCase = true)
}

private fun determineCompletionContext(element: PsiElement): CompletionContext {
	// Walk up to find a qualified expression where we're the selector
	val dotExpr = element.getParentOfType<KtDotQualifiedExpression>(strict = false)
	if (dotExpr != null && isInSelectorPosition(element, dotExpr)) {
		return CompletionContext.Member
	}

	val safeExpr = element.getParentOfType<KtSafeQualifiedExpression>(strict = false)
	if (safeExpr != null && isInSelectorPosition(element, safeExpr)) {
		return CompletionContext.Member
	}

	return CompletionContext.Scope
}

private fun isInSelectorPosition(
	element: PsiElement,
	qualifiedExpr: KtQualifiedExpression,
): Boolean {
	val selector = qualifiedExpr.selectorExpression ?: return false
	val elementOffset = element.startOffset
	return elementOffset >= selector.startOffset
}

