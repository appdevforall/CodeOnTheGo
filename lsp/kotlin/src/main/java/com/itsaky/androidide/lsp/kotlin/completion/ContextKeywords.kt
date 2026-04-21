package com.itsaky.androidide.lsp.kotlin.completion

import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens.*

/**
 *
 */
object ContextKeywords {

	/** Hard keywords valid as *statement starters* inside a function body */
	val STATEMENT_KEYWORDS = setOf(
		IF_KEYWORD, ELSE_KEYWORD, WHEN_KEYWORD, WHILE_KEYWORD, DO_KEYWORD, FOR_KEYWORD,
		TRY_KEYWORD, RETURN_KEYWORD, THROW_KEYWORD, BREAK_KEYWORD, CONTEXT_KEYWORD,
		VAL_KEYWORD, VAR_KEYWORD, FUN_KEYWORD,// local declarations
		OBJECT_KEYWORD,// anonymous / local object
		CLASS_KEYWORD,// local class (rare but legal)
	)

	/** Declaration starters at top-level / class body */
	val DECLARATION_KEYWORDS = setOf(
		VAL_KEYWORD, VAR_KEYWORD, FUN_KEYWORD, CLASS_KEYWORD, INTERFACE_KEYWORD, OBJECT_KEYWORD,
		TYPE_ALIAS_KEYWORD, CONSTRUCTOR_KEYWORD, INIT_KEYWORD,
	)

	val TOP_LEVEL_ONLY = setOf(PACKAGE_KEYWORD, IMPORT_KEYWORD)

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
				setOf(INIT_KEYWORD, CONSTRUCTOR_KEYWORD)

		DeclarationContext.INTERFACE_BODY -> setOf(
			VAL_KEYWORD, VAR_KEYWORD, FUN_KEYWORD, CLASS_KEYWORD,
			INTERFACE_KEYWORD, OBJECT_KEYWORD, TYPE_ALIAS_KEYWORD
		)

		DeclarationContext.OBJECT_BODY,
		DeclarationContext.ENUM_BODY -> DECLARATION_KEYWORDS - setOf(CONSTRUCTOR_KEYWORD)

		DeclarationContext.ANNOTATION_BODY -> setOf(VAL_KEYWORD)   // annotation params only

		DeclarationContext.FUNCTION_BODY -> STATEMENT_KEYWORDS
	}
}