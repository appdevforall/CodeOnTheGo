package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.completion.DeclarationContext
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens

object ContextKeywords {

	/** Hard keywords valid as *statement starters* inside a function body */
	val STATEMENT_KEYWORDS = setOf(
		KtTokens.IF_KEYWORD,
		KtTokens.ELSE_KEYWORD,
		KtTokens.WHEN_KEYWORD,
		KtTokens.WHILE_KEYWORD,
		KtTokens.DO_KEYWORD,
		KtTokens.FOR_KEYWORD,
		KtTokens.TRY_KEYWORD,
		KtTokens.RETURN_KEYWORD,
		KtTokens.THROW_KEYWORD,
		KtTokens.BREAK_KEYWORD,
		KtTokens.CONTEXT_KEYWORD,
		KtTokens.VAL_KEYWORD,
		KtTokens.VAR_KEYWORD,
		KtTokens.FUN_KEYWORD,// local declarations
		KtTokens.OBJECT_KEYWORD,// anonymous / local object
		KtTokens.CLASS_KEYWORD,// local class (rare but legal)
	)

	/** Declaration starters at top-level / class body */
	val DECLARATION_KEYWORDS = setOf(
		KtTokens.VAL_KEYWORD,
		KtTokens.VAR_KEYWORD,
		KtTokens.FUN_KEYWORD,
		KtTokens.CLASS_KEYWORD,
		KtTokens.INTERFACE_KEYWORD,
		KtTokens.OBJECT_KEYWORD,
		KtTokens.TYPE_ALIAS_KEYWORD,
		KtTokens.CONSTRUCTOR_KEYWORD,
		KtTokens.INIT_KEYWORD,
	)

	val TOP_LEVEL_ONLY = setOf(KtTokens.PACKAGE_KEYWORD, KtTokens.IMPORT_KEYWORD)

	/**
	 * Resolve valid keywords for the given declaration context.
	 *
	 * @param ctx The declaration context.
	 * @return The keyword tokens for the declaration context.
	 */
	fun keywordsFor(ctx: DeclarationContext): Set<KtKeywordToken> = when (ctx) {
		DeclarationContext.TOP_LEVEL,
		DeclarationContext.SCRIPT_TOP_LEVEL -> TOP_LEVEL_ONLY + DECLARATION_KEYWORDS

		DeclarationContext.CLASS_BODY -> DECLARATION_KEYWORDS +
				setOf(KtTokens.INIT_KEYWORD, KtTokens.CONSTRUCTOR_KEYWORD)

		DeclarationContext.INTERFACE_BODY -> setOf(
			KtTokens.VAL_KEYWORD,
			KtTokens.VAR_KEYWORD,
			KtTokens.FUN_KEYWORD,
			KtTokens.CLASS_KEYWORD,
			KtTokens.INTERFACE_KEYWORD,
			KtTokens.OBJECT_KEYWORD,
			KtTokens.TYPE_ALIAS_KEYWORD
		)

		DeclarationContext.OBJECT_BODY,
		DeclarationContext.ENUM_BODY -> DECLARATION_KEYWORDS - setOf(KtTokens.CONSTRUCTOR_KEYWORD)

		DeclarationContext.ANNOTATION_BODY -> setOf(KtTokens.VAL_KEYWORD)   // annotation params only

		DeclarationContext.FUNCTION_BODY -> STATEMENT_KEYWORDS
	}
}