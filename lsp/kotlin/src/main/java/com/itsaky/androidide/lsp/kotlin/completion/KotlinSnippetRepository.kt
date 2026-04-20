package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.snippets.ISnippet
import com.itsaky.androidide.lsp.snippets.SnippetParser

object KotlinSnippetRepository {
	lateinit var snippets: Map<KotlinSnippetScope, List<ISnippet>>
		private set

	fun init() {
		snippets = SnippetParser.parse("kt", KotlinSnippetScope.entries)
	}
}