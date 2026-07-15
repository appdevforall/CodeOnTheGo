package com.itsaky.androidide.lsp.kotlin.completion

/**
 * The context for the providing code completions in a file.
 */
enum class CompletionContext {

	/**
	 * Scope completions (local variables, parameters, etc.)
	 */
	Scope,

	/**
	 * Member completions (properties, member functions, extension functions, etc.)
	 */
	Member,
}