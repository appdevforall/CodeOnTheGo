package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.snippets.ISnippetScope

/**
 * Snippet scopes for Kotlin source files.
 *
 * @author Akash Yadav
 */
enum class KotlinSnippetScope(override val filename: String) : ISnippetScope {

	/**
	 * Snippets that can be used at the top level. This includes snippets such as class, interface,
	 * enum templates.
	 */
	TOP_LEVEL("top-level"),

	/** Snippets that can be used at the member level i.e. inside a class tree. */
	MEMBER("member"),

	/** Snippets that can be used at a local level. E.g. inside a method or constructor. */
	LOCAL("local"),

	/** Snippets that can be used anywhere in the code, irrespective of the current scope. */
	GLOBAL("global"),
}