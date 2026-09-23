package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.snippets.ISnippet
import com.itsaky.androidide.lsp.snippets.SnippetLanguage
import com.itsaky.androidide.lsp.snippets.SnippetRegistry

object KotlinSnippetRepository {
	val snippets: Map<KotlinSnippetScope, List<ISnippet>>
		get() =
			KotlinSnippetScope.entries.associateWith { scope ->
				SnippetRegistry.getSnippets(SnippetLanguage.KOTLIN.id, scope.filename)
			}

	fun init() {
		SnippetRegistry.initBuiltIn(SnippetLanguage.KOTLIN.id, KotlinSnippetScope.entries)
	}
}
