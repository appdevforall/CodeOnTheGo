package org.appdevforall.codeonthego.lsp.kotlin.completion

import org.appdevforall.codeonthego.lsp.snippets.ISnippet
import org.appdevforall.codeonthego.lsp.snippets.SnippetLanguage
import org.appdevforall.codeonthego.lsp.snippets.SnippetRegistry

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
