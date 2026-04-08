package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.InsertTextFormat
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.analysis.api.KaContextParameterApi
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.components.KaScopeContext
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.renderer.types.KaTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
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
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.types.Variance
import org.slf4j.LoggerFactory

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
	val managedFile = fileManager.getOpenFile(params.file)
	if (managedFile == null) {
		logger.warn("No managed file for {}", params.file)
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

	val completionKtFile =
		managedFile.createInMemoryFileWithContent(
			psiFactory = parser,
			content = textWithPlaceholder
		)

	return try {
		analyzeCopy(
			useSiteElement = completionKtFile,
			resolutionMode = KaDanglingFileResolutionMode.PREFER_SELF,
		) {
			val symbolVisibilityChecker = this@complete.symbolVisibilityChecker
			if (symbolVisibilityChecker == null) {
				logger.error("No symbol visibility checker available!")
				return@analyzeCopy CompletionResult.EMPTY
			}

			val cursorContext = resolveCursorContext(completionKtFile, completionOffset)
			if (cursorContext == null) {
				logger.error(
					"Unable to determine context at offset {} in file {}",
					completionOffset,
					params.file
				)
				return@analyzeCopy CompletionResult.EMPTY
			}

			val (
				psiElement,
				_,
				ktElement,
				scopeContext,
				compositeScope,
				completionContext
			) = cursorContext

			val items = mutableListOf<CompletionItem>()

			when (completionContext) {
				CompletionContext.Scope ->
					collectScopeCompletions(
						scopeContext = scopeContext,
						scope = compositeScope,
						symbolVisibilityChecker = symbolVisibilityChecker,
						ktElement = ktElement,
						partial = partial,
						to = items
					)

				CompletionContext.Member ->
					collectMemberCompletions(
						scope = compositeScope,
						element = psiElement,
						partial = partial,
						to = items
					)
			}

			collectKeywordCompletions(
				ctx = cursorContext,
				partial = partial,
				to = items
			)

			CompletionResult(items)
		}
	} catch (e: Throwable) {
		if (e is CancellationException) {
			throw e
		}

		logger.warn("An error occurred while computing completions for {}", params.file)
		return CompletionResult.EMPTY
	}
}

private fun KaSession.collectMemberCompletions(
	scope: KaScope,
	element: PsiElement,
	partial: String,
	to: MutableList<CompletionItem>
) {
	val qualifiedExpr = element.getParentOfType<KtQualifiedExpression>(strict = false)
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
		partial
	)

	collectMembersFromType(receiverType, partial, to)

	if (qualifiedExpr is KtSafeQualifiedExpression) {
		val nonNullType = receiverType.withNullability(isMarkedNullable = false)
		collectMembersFromType(nonNullType, partial, to)
	}

	collectExtensionFunctions(scope, partial, receiverType, to)
}

@OptIn(KaExperimentalApi::class)
private fun KaSession.collectMembersFromType(
	receiverType: KaType,
	partial: String,
	to: MutableList<CompletionItem>
) {
	val typeScope = receiverType.scope
	if (typeScope != null) {
		val callables =
			typeScope.getCallableSignatures { name -> matchesPrefix(name, partial) }
				.map { it.symbol }

		val classifiers =
			typeScope.getClassifierSymbols { name -> matchesPrefix(name, partial) }

		to += toCompletionItems(callables, partial)
		to += toCompletionItems(classifiers, partial)
		return
	}

	// fallback approach when typeScope is not available
	val classType = receiverType as? KaClassType ?: return
	val classSymbol = classType.symbol as? KaClassSymbol ?: return
	val memberScope = classSymbol.memberScope

	val callables = memberScope.callables { name -> matchesPrefix(name, partial) }
	val classifiers = memberScope.classifiers { name -> matchesPrefix(name, partial) }

	to += toCompletionItems(callables, partial)
	to += toCompletionItems(classifiers, partial)
}

private fun KaSession.collectExtensionFunctions(
	scope: KaScope,
	partial: String,
	receiverType: KaType,
	to: MutableList<CompletionItem>
) {
	val extensionSymbols =
		scope.callables { name -> matchesPrefix(name, partial) }
			.filter { symbol ->
				if (!symbol.isExtension) return@filter false

				val extReceiverType = symbol.receiverType ?: return@filter false
				receiverType.isSubtypeOf(extReceiverType)
			}

	to += toCompletionItems(extensionSymbols, partial)
}

private fun KaSession.collectScopeCompletions(
	scopeContext: KaScopeContext,
	scope: KaScope,
	symbolVisibilityChecker: SymbolVisibilityChecker,
	ktElement: KtElement,
	partial: String,
	to: MutableList<CompletionItem>,
) {
	logger.info(
		"Complete scope members of {}: [{}] matching '{}'",
		ktElement,
		ktElement.text,
		partial
	)

	val callables =
		scope.callables { name -> matchesPrefix(name, partial) }
			.filter { symbol ->

				// always include non-extension functions
				if (!symbol.isExtension) return@filter true

				// include extension functions with matching implicit receivers
				val extReceiverType = symbol.receiverType ?: return@filter true
				scopeContext.implicitReceivers.any { receiver ->
					receiver.type.isSubtypeOf(extReceiverType)
				}
			}
	val classifiers = scope.classifiers { name -> matchesPrefix(name, partial) }

	to += toCompletionItems(callables, partial)
	to += toCompletionItems(classifiers, partial)
}

private fun KaSession.collectKeywordCompletions(
	ctx: CursorContext,
	partial: String,
	to: MutableList<CompletionItem>,
) {
	fun kwItem(name: String) =
		ktCompletionItem(
			name = name,
			kind = CompletionItemKind.KEYWORD,
			partial = partial
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

@JvmName("callablesToCompletionItems")
private fun KaSession.toCompletionItems(
	callables: Sequence<KaCallableSymbol>,
	partial: String
): Sequence<CompletionItem> =
	callables.mapNotNull {
		callableSymbolToCompletionItem(it, partial)
	}

@JvmName("classifiersToCompletionItems")
private fun KaSession.toCompletionItems(
	classifiers: Sequence<KaClassifierSymbol>,
	partial: String
): Sequence<CompletionItem> =
	classifiers.mapNotNull {
		classifierSymbolToCompletionItem(it, partial)
	}

@OptIn(KaExperimentalApi::class)
private fun KaSession.callableSymbolToCompletionItem(
	symbol: KaCallableSymbol,
	partial: String
): CompletionItem? {
	val item = createSymbolCompletionItem(symbol, partial) ?: return null
	val name = item.ideLabel
	item.overrideTypeText = renderName(symbol.returnType)

	when (symbol) {
		is KaNamedFunctionSymbol -> {
			val params = symbol.valueParameters.joinToString(", ") { param ->
				"${param.name.asString()}: ${renderName(param.returnType)}"
			}

			val hasParams = symbol.valueParameters.isNotEmpty()

			item.detail = "${name}($params)"
			item.insertTextFormat = InsertTextFormat.SNIPPET
			item.insertText = if (hasParams) {
				"${name}($0)"
			} else {
				"${name}()$0"
			}

			if (hasParams) {
				item.command = Command("Trigger parameter hints", Command.TRIGGER_PARAMETER_HINTS)
			}

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

@OptIn(KaExperimentalApi::class)
private fun KaSession.classifierSymbolToCompletionItem(
	symbol: KaClassifierSymbol,
	partial: String
): CompletionItem? {
	val item = createSymbolCompletionItem(symbol, partial) ?: return null
	item.detail = when (symbol) {
		is KaClassSymbol -> symbol.classId?.asFqNameString() ?: ""
		is KaTypeAliasSymbol -> renderName(
			symbol.expandedType,
			KaTypeRendererForSource.WITH_QUALIFIED_NAMES
		)

		is KaTypeParameterSymbol -> item.ideLabel
	}
	return item
}

private fun KaSession.createSymbolCompletionItem(
	symbol: KaSymbol,
	partial: String
): CompletionItem? {
	return ktCompletionItem(
		name = symbol.name?.asString() ?: return null,
		kind = kindOf(symbol),
		partial = partial,
	)
}

private fun KaSession.ktCompletionItem(
	name: String,
	kind: CompletionItemKind,
	partial: String,
): CompletionItem {
	val item = KotlinCompletionItem()
	item.ideLabel = name
	item.completionKind = kind
	item.matchLevel = CompletionItem.matchLevel(item.ideLabel, partial)

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

private fun matchesPrefix(name: Name, partial: String): Boolean {
	logger.info(
		"'{}' matches '{}': {}",
		name,
		partial,
		name.asString().startsWith(partial, ignoreCase = true)
	)
	if (partial.isEmpty()) return true
	return name.asString().startsWith(partial, ignoreCase = true)
}
