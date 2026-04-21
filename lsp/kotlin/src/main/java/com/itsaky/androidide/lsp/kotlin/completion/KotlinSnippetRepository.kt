package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.snippets.ISnippet
import com.itsaky.androidide.lsp.snippets.SnippetParser
import com.itsaky.androidide.lsp.snippets.SnippetRegistry

object KotlinSnippetRepository {
	val snippets: Map<KotlinSnippetScope, List<ISnippet>>
		get() = KotlinSnippetScope.entries.associateWith { scope ->
			SnippetRegistry.getSnippets("kt", scope.filename)
		}

	fun init() {
		SnippetRegistry.initBuiltIn("kt", KotlinSnippetScope.entries)
	}
}